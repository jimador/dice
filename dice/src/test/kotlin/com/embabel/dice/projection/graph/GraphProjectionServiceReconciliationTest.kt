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
package com.embabel.dice.projection.graph

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.RelationshipDirection
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.projection.lineage.ReconciliationDecision
import com.embabel.dice.projection.lineage.Reconciler
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.RepositoryBackedReconciler
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.ProjectionSuccess
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that [GraphProjectionService] records the correct [ProjectionLifecycle]
 * (PROJECTED vs ADOPTED) and target reference when a [Reconciler] is in play.
 */
class GraphProjectionServiceReconciliationTest {

    private val mockProjector = mockk<GraphProjector>()
    private val mockPersister = mockk<GraphRelationshipPersister>()
    private val mockSchema = DataDictionary.fromDomainTypes("test", emptyList())

    private fun proposition(id: String): Proposition =
        Proposition(
            id = id,
            contextId = ContextId("ctx"),
            text = "text for $id",
            mentions = emptyList(),
            confidence = 1.0,
        )

    private fun success(p: Proposition): ProjectionSuccess<ProjectedRelationship> =
        ProjectionSuccess(
            p,
            ProjectedRelationship(
                sourceId = "created-${p.id}",
                targetId = "node-target",
                type = "KNOWS",
                confidence = 1.0,
                sourcePropositionIds = listOf(p.id),
            ),
        )

    @Test
    fun `Adopt and Align record ADOPTED with the decision targetRef`() {
        val pAdopt = proposition("p-adopt")
        val pAlign = proposition("p-align")
        val propositions = listOf(pAdopt, pAlign)

        val results = ProjectionResults(listOf(success(pAdopt), success(pAlign)))
        val persistence = RelationshipPersistenceResult(persistedCount = 2, failedCount = 0)

        every { mockProjector.projectAll(propositions, mockSchema) } returns results
        every { mockPersister.persist(results) } returns persistence

        val resolver = object : Reconciler {
            override fun reconcile(proposition: Proposition, target: String): ReconciliationDecision =
                when (proposition.id) {
                    "p-adopt" -> ReconciliationDecision.Adopt("node-42")
                    "p-align" -> ReconciliationDecision.Align("node-77")
                    else -> ReconciliationDecision.CreateNew
                }
        }

        val store = InMemoryProjectionRecordStore()
        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema, store, resolver)

        service.projectAndPersist(propositions)

        val byProposition = store.all().associateBy { it.propositionId }

        val adopted = byProposition.getValue("p-adopt")
        assertEquals(ProjectionLifecycle.ADOPTED, adopted.lifecycle)
        assertEquals("node-42", adopted.targetRef)

        val aligned = byProposition.getValue("p-align")
        assertEquals(ProjectionLifecycle.ADOPTED, aligned.lifecycle)
        assertEquals("node-77", aligned.targetRef)
    }

    @Test
    fun `existing endpoint nodes do not mark a newly-created relationship as adopted`() {
        val p = Proposition(
            id = "p-endpoints-only",
            contextId = ContextId("ctx"),
            text = "Rod knows Tom",
            mentions = listOf(
                EntityMention("Rod", "Person", resolvedId = "person-rod", role = MentionRole.SUBJECT),
                EntityMention("Tom", "Person", resolvedId = "person-tom", role = MentionRole.OBJECT),
            ),
            confidence = 1.0,
        )
        val relationship = ProjectedRelationship(
            sourceId = "person-rod",
            targetId = "person-tom",
            type = "KNOWS",
            confidence = 1.0,
            sourcePropositionIds = listOf(p.id),
        )
        val results = ProjectionResults(listOf(ProjectionSuccess(p, relationship)))
        val persistence = RelationshipPersistenceResult(persistedCount = 1, failedCount = 0)

        every { mockProjector.projectAll(listOf(p), mockSchema) } returns results
        every { mockPersister.persist(results) } returns persistence

        val repository = mockk<NamedEntityDataRepository>(relaxed = true)
        val source = mockk<NamedEntityData>()
        every { source.labels() } returns setOf("Person")
        every { repository.findById("person-rod") } returns source
        every {
            repository.findRelated(
                RetrievableIdentifier("person-rod", "Person"),
                "KNOWS",
                RelationshipDirection.OUTGOING,
            )
        } returns emptyList()

        val store = InMemoryProjectionRecordStore()
        val service = GraphProjectionService(
            mockProjector,
            mockPersister,
            mockSchema,
            store,
            RepositoryBackedReconciler(repository),
        )

        service.projectAndPersist(listOf(p))

        val record = store.all().single()
        assertEquals(ProjectionLifecycle.PROJECTED, record.lifecycle)
        assertEquals("person-rod-[KNOWS]->person-tom", record.targetRef)
    }

    @Test
    fun `default constructor (no resolver) records PROJECTED for successes`() {
        val p = proposition("p-default")
        val propositions = listOf(p)

        val results = ProjectionResults(listOf(success(p)))
        val persistence = RelationshipPersistenceResult(persistedCount = 1, failedCount = 0)

        every { mockProjector.projectAll(propositions, mockSchema) } returns results
        every { mockPersister.persist(results) } returns persistence

        val store = InMemoryProjectionRecordStore()
        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema, store)

        service.projectAndPersist(propositions)

        val record = store.all().single()
        assertEquals(ProjectionLifecycle.PROJECTED, record.lifecycle)
        // PROJECTED references the produced edge (source-[type]->target), not just the source node.
        assertEquals("created-p-default-[KNOWS]->node-target", record.targetRef)
    }

    @Test
    fun `the reconciler is consulted before the persister writes`() {
        // A repository-backed reconciler decides new-vs-existing by looking the node up. If we
        // persisted first it would always find the just-written node and never record PROJECTED, so
        // the reconcile must happen against the pre-persist state.
        val p = proposition("p-order")
        val results = ProjectionResults(listOf(success(p)))
        val persistence = RelationshipPersistenceResult(persistedCount = 1, failedCount = 0)
        every { mockProjector.projectAll(listOf(p), mockSchema) } returns results
        every { mockPersister.persist(results) } returns persistence

        val reconciler = mockk<Reconciler>()
        every { reconciler.reconcile(any(), any(), any()) } returns ReconciliationDecision.CreateNew
        val service = GraphProjectionService(
            mockProjector, mockPersister, mockSchema, InMemoryProjectionRecordStore(), reconciler,
        )

        service.projectAndPersist(listOf(p))

        verifyOrder {
            mockProjector.projectAll(listOf(p), mockSchema)
            reconciler.reconcile(p, "neo4j", results.projected.single())
            mockPersister.persist(results)
        }
    }
}
