/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice.query.graph

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.GraphQueryCapable
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Store-agnosticism contract: the facade works over the base persistence port alone and routes to a
 * native override when the store declares the capability fragment.
 *
 * Mirrors the two-stub proof used for the proposition store template: one stub implements only
 * [PropositionStore] (degrades to empty), the other additionally declares [GraphQueryCapable] with
 * a sentinel override (the facade must route to it).
 */
class GraphQueryStoreAgnosticTest {

    private val contextId = ContextId("agnostic-test")

    private fun proposition(id: String): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = "fact $id",
            mentions = listOf(EntityMention(span = "A", type = "Entity", resolvedId = "A", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )

    private fun edge(id: String, ctx: ContextId, a: String, b: String): Proposition =
        Proposition(
            id = id,
            contextId = ctx,
            text = "$a relates to $b",
            mentions = listOf(
                EntityMention(span = a, type = "Entity", resolvedId = a, role = MentionRole.SUBJECT),
                EntityMention(span = b, type = "Entity", resolvedId = b, role = MentionRole.OBJECT),
            ),
            confidence = 0.9,
        )

    /** Implements ONLY the base persistence port — no entity-axis graph capability. */
    private open class BaseOnlyStore : PropositionStore {
        private val store = mutableMapOf<String, Proposition>()
        override fun save(proposition: Proposition): Proposition {
            store[proposition.id] = proposition
            return proposition
        }
        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store.values.toList()
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }

    /** Declares the entity-axis capability with sentinel overrides the facade must route to. */
    private class NativeGraphStore : BaseOnlyStore(), GraphQueryCapable {
        val sentinelNeighbourId = "SENTINEL-NEIGHBOUR"
        override fun neighborhood(entityId: String, depth: Int): GraphNeighborhood =
            GraphNeighborhood(entityId, listOf(RelatedEntity(sentinelNeighbourId, emptyList())))

        override fun pathBetween(entityIdA: String, entityIdB: String): List<GraphPath> =
            listOf(GraphPath(listOf(entityIdA, entityIdB), emptyList()))
    }

    /** Native methods deliberately ignore context, as the current capability SPI gives them none. */
    private class ContextBlindNativeGraphStore(
        private val foreign: Proposition,
    ) : BaseOnlyStore(), GraphQueryCapable {
        var neighborhoodCalls = 0
            private set
        var pathCalls = 0
            private set
        var whyCalls = 0
            private set

        override fun neighborhood(entityId: String, depth: Int): GraphNeighborhood {
            neighborhoodCalls++
            return GraphNeighborhood(entityId, listOf(RelatedEntity("FOREIGN", listOf(foreign))))
        }

        override fun pathBetween(entityIdA: String, entityIdB: String): List<GraphPath> {
            pathCalls++
            return listOf(GraphPath(listOf(entityIdA, "FOREIGN", entityIdB), listOf(foreign)))
        }

        override fun whyExplain(propositionId: String): PropositionLineage? {
            whyCalls++
            return PropositionLineage(
                proposition = foreign,
                provenanceEntries = emptyList(),
                groundingChunkIds = emptyList(),
                sources = emptyList(),
                reinforceCount = foreign.reinforceCount,
                status = foreign.status,
                temporal = foreign.temporal,
            )
        }
    }

    @Test
    fun `base-only store degrades to empty without throwing`() {
        val store = BaseOnlyStore().apply { save(proposition("p1")) }
        val gq = GraphQuery(store, contextId)

        assertFalse(gq.supportsNativeGraph, "a base-only store does not declare the graph capability")
        val neighbours = assertDoesNotThrow<GraphNeighborhood> { gq.neighborhood("A") }
        val paths = assertDoesNotThrow<List<GraphPath>> { gq.pathBetween("A", "B") }
        assertTrue(neighbours.neighbours.isEmpty(), "no entity-axis edges are derived for a base store")
        assertTrue(paths.isEmpty(), "no path is found for a base store")
    }

    @Test
    fun `base-only store still assembles lineage from findById`() {
        val store = BaseOnlyStore().apply { save(proposition("p1")) }
        val gq = GraphQuery(store, contextId)

        val lineage = gq.whyExplain("p1")
        assertEquals("p1", lineage?.proposition?.id, "lineage comes from findById, not a graph backend")
    }

    @Test
    fun `native graph store routes to the override sentinel`() {
        val gq = GraphQuery(NativeGraphStore())

        assertTrue(gq.supportsNativeGraph, "a native store declares the graph capability")
        assertEquals(
            "SENTINEL-NEIGHBOUR",
            gq.neighborhood("A").neighbours.single().entityId,
            "the facade routes neighborhood to the native override",
        )
        assertEquals(
            listOf("A", "B"),
            gq.pathBetween("A", "B").single().entityIds,
            "the facade routes pathBetween to the native override",
        )
    }

    @Test
    fun `context-bound graph query uses the scoped portable path instead of context-blind native methods`() {
        val ctxA = ContextId("ctxA")
        val ctxB = ContextId("ctxB")
        val foreign = edge("foreign", ctxB, "A", "FOREIGN")
        val store = ContextBlindNativeGraphStore(foreign).apply {
            save(edge("local", ctxA, "A", "B"))
            save(foreign)
        }
        val gq = GraphQuery(store, ctxA)

        val neighborhood = gq.neighborhood("A")
        val paths = gq.pathBetween("A", "B")
        val foreignLineage = gq.whyExplain("foreign")

        assertEquals(listOf("B"), neighborhood.neighbours.map { it.entityId })
        assertEquals(listOf("A", "B"), paths.single().entityIds)
        assertEquals(null, foreignLineage, "a scoped query must not explain a foreign-context proposition")
        assertEquals(0, store.neighborhoodCalls, "scoped neighborhood must not use the native context-blind method")
        assertEquals(0, store.pathCalls, "scoped pathBetween must not use the native context-blind method")
        assertEquals(0, store.whyCalls, "scoped whyExplain must not use the native context-blind method")
    }
}
