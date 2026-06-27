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
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.proposition.ProjectionFailed
import com.embabel.dice.proposition.ProjectionFailureReason
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.ProjectionSkipped
import com.embabel.dice.proposition.ProjectionSuccess
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that [GraphProjectionService] emits the right [ProjectionLifecycle] and reason
 * on each [ProjectionRecord] — one per result, all sharing a single run ID.
 */
class GraphProjectionServiceLineageTest {

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

    @Test
    fun `records one ProjectionRecord per result with correct lifecycle and reason`() {
        val pSuccess = proposition("p-success")
        val pSkipped = proposition("p-skipped")
        val pFailed = proposition("p-failed")

        val skipReason = ProjectionFailureReason.NoMatchingPredicate("nothing matched")
        val failReason = ProjectionFailureReason.PolicyRejected("rejected by policy")

        val results = ProjectionResults(
            listOf(
                ProjectionSuccess(
                    pSuccess,
                    ProjectedRelationship(
                        sourceId = "node-1",
                        targetId = "node-2",
                        type = "KNOWS",
                        confidence = 1.0,
                        sourcePropositionIds = listOf("p-success"),
                    ),
                ),
                ProjectionSkipped(pSkipped, reason = "skip text", structuredReason = skipReason),
                ProjectionFailed(pFailed, reason = "fail text", structuredReason = failReason),
            ),
        )
        val persistence = RelationshipPersistenceResult(persistedCount = 1, failedCount = 0)
        val propositions = listOf(pSuccess, pSkipped, pFailed)

        every { mockProjector.projectAll(propositions, mockSchema) } returns results
        every { mockPersister.persist(results) } returns persistence

        val store = InMemoryProjectionRecordStore()
        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema, store)

        service.projectAndPersist(propositions)

        val records = store.all()
        assertEquals(3, records.size)

        val byProposition = records.associateBy { it.propositionId }
        assertEquals(setOf("p-success", "p-skipped", "p-failed"), byProposition.keys)

        val success = byProposition.getValue("p-success")
        assertEquals(ProjectionLifecycle.PROJECTED, success.lifecycle)
        assertNull(success.reason)

        val skipped = byProposition.getValue("p-skipped")
        assertEquals(ProjectionLifecycle.SKIPPED, skipped.lifecycle)
        assertEquals(skipReason.describe(), skipped.reason)

        val failed = byProposition.getValue("p-failed")
        assertEquals(ProjectionLifecycle.FAILED, failed.lifecycle)
        assertEquals(failReason.describe(), failed.reason)

        // all share one runId, target is neo4j
        assertEquals(1, records.map { it.runId }.toSet().size)
        assertTrue(records.all { it.target == "neo4j" })
    }

    @Test
    fun `records FAILED when relationship persistence fails after projection succeeds`() {
        val p = proposition("p-persist-failed")
        val relationship = ProjectedRelationship(
            sourceId = "node-1",
            targetId = "node-2",
            type = "KNOWS",
            confidence = 1.0,
            sourcePropositionIds = listOf(p.id),
        )
        val results = ProjectionResults(listOf(ProjectionSuccess(p, relationship)))
        val persistence = RelationshipPersistenceResult(
            persistedCount = 0,
            failedCount = 1,
            errors = listOf("merge failed"),
        )

        every { mockProjector.projectAll(listOf(p), mockSchema) } returns results
        every { mockPersister.persist(results) } returns persistence

        val store = InMemoryProjectionRecordStore()
        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema, store)

        service.projectAndPersist(listOf(p))

        val record = store.all().single()
        assertEquals(ProjectionLifecycle.FAILED, record.lifecycle)
        assertEquals("relationship persistence failed: merge failed", record.reason)
        assertNull(record.targetRef, "failed persistence must not claim a produced edge targetRef")
    }

    @Test
    fun `in a partial-failure batch only the attributed edge is FAILED`() {
        val pOk = proposition("p-ok")
        val pBad = proposition("p-bad")
        val edgeOk = ProjectedRelationship("node-1", "node-2", "KNOWS", 1.0, sourcePropositionIds = listOf(pOk.id))
        val edgeBad = ProjectedRelationship("node-3", "node-4", "KNOWS", 1.0, sourcePropositionIds = listOf(pBad.id))
        val results = ProjectionResults(listOf(ProjectionSuccess(pOk, edgeOk), ProjectionSuccess(pBad, edgeBad)))
        val persistence = RelationshipPersistenceResult(
            persistedCount = 1,
            failedCount = 1,
            errors = listOf("merge failed for ${edgeBad.edgeRef}"),
            persistedRelationshipRefs = setOf(edgeOk.edgeRef),
            failedRelationshipRefs = setOf(edgeBad.edgeRef),
        )

        every { mockProjector.projectAll(listOf(pOk, pBad), mockSchema) } returns results
        every { mockPersister.persist(results) } returns persistence

        val store = InMemoryProjectionRecordStore()
        GraphProjectionService(mockProjector, mockPersister, mockSchema, store).projectAndPersist(listOf(pOk, pBad))

        val byProposition = store.all().associateBy { it.propositionId }
        assertEquals(ProjectionLifecycle.PROJECTED, byProposition.getValue("p-ok").lifecycle)
        assertEquals(ProjectionLifecycle.FAILED, byProposition.getValue("p-bad").lifecycle)
    }

    @Test
    fun `a partial failure the persister cannot attribute does not paint a succeeded edge FAILED`() {
        val pOk = proposition("p-ok")
        val pBad = proposition("p-bad")
        val edgeOk = ProjectedRelationship("node-1", "node-2", "KNOWS", 1.0, sourcePropositionIds = listOf(pOk.id))
        val edgeBad = ProjectedRelationship("node-3", "node-4", "KNOWS", 1.0, sourcePropositionIds = listOf(pBad.id))
        val results = ProjectionResults(listOf(ProjectionSuccess(pOk, edgeOk), ProjectionSuccess(pBad, edgeBad)))
        // failedCount > 0 but no per-edge refs: the persister can't say which edge failed. With the
        // batch only partially failed, we must not invent a FAILED lifecycle for a succeeded edge.
        val persistence = RelationshipPersistenceResult(persistedCount = 1, failedCount = 1, errors = listOf("one failed"))

        every { mockProjector.projectAll(listOf(pOk, pBad), mockSchema) } returns results
        every { mockPersister.persist(results) } returns persistence

        val store = InMemoryProjectionRecordStore()
        GraphProjectionService(mockProjector, mockPersister, mockSchema, store).projectAndPersist(listOf(pOk, pBad))

        assertTrue(
            store.all().none { it.lifecycle == ProjectionLifecycle.FAILED },
            "an unattributable partial failure must not mark a succeeded edge FAILED",
        )
    }

    @Test
    fun `with no store the returned pair is unchanged and nothing is recorded`() {
        val propositions = listOf<Proposition>()
        val results = ProjectionResults<ProjectedRelationship>(emptyList())
        val persistence = RelationshipPersistenceResult(persistedCount = 0, failedCount = 0)

        every { mockProjector.projectAll(propositions, mockSchema) } returns results
        every { mockPersister.persist(results) } returns persistence

        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema)
        val result = service.projectAndPersist(propositions)

        assertSame(results, result.first)
        assertSame(persistence, result.second)
    }
}
