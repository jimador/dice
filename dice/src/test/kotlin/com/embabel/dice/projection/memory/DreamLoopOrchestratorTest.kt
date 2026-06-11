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
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.dice.operations.consolidation.ConsolidationPass
import com.embabel.dice.operations.consolidation.ConsolidationPassResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DreamLoopOrchestratorTest {

    private lateinit var repository: PropositionRepository
    private val contextId = ContextId("test-context")

    private fun proposition(text: String): Proposition =
        Proposition(contextId = contextId, text = text, mentions = emptyList(), confidence = 0.8)

    /** Tiny fake pass returning a fixed result, recording how many times it ran. */
    private class FakePass(
        override val name: String,
        private val result: (List<Proposition>) -> ConsolidationPassResult,
    ) : ConsolidationPass {
        var runCount: Int = 0
        override fun run(contextId: ContextId, propositions: List<Proposition>): ConsolidationPassResult {
            runCount++
            return result(propositions)
        }
    }

    @BeforeEach
    fun setup() {
        repository = mockk(relaxed = true)
        every { repository.query(any()) } returns emptyList()
    }

    @Test
    fun `withPass appends and returns a new instance preserving order`() {
        val base = DefaultDreamLoopOrchestrator.withRepository(repository)
        val a = FakePass("a") { ConsolidationPassResult.NoOp("a") }
        val b = FakePass("b") { ConsolidationPassResult.NoOp("b") }

        val withA = base.withPass(a)
        val withAB = withA.withPass(b)

        assertNotSame(base, withA)
        assertNotSame(withA, withAB)

        val report = withAB.consolidateNow(contextId)
        // registration order = execution order: results reported a then b
        assertEquals(listOf("a", "b"), report.passResults.map { it.passName })
    }

    @Test
    fun `consolidateNow runs each pass once over a single snapshot`() {
        val snapshot = listOf(proposition("p1"), proposition("p2"))
        every { repository.query(any()) } returns snapshot

        val a = FakePass("a") { ConsolidationPassResult.NoOp("a") }
        val b = FakePass("b") { ConsolidationPassResult.NoOp("b") }

        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(a)
            .withPass(b)

        val report = orchestrator.consolidateNow(contextId)

        assertEquals(1, a.runCount)
        assertEquals(1, b.runCount)
        assertTrue(report.triggered)
        assertEquals(2, report.totalExamined)
    }

    @Test
    fun `consolidateNow aggregates all saves into a single saveAll`() {
        val saved = proposition("saved")
        val a = FakePass("a") {
            ConsolidationPassResult.Changed("a", propositionsToSave = listOf(saved))
        }
        val b = FakePass("b") {
            ConsolidationPassResult.Changed("b", propositionsToSave = listOf(proposition("saved-b")))
        }

        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(a)
            .withPass(b)

        val captured = slot<Collection<Proposition>>()
        every { repository.saveAll(capture(captured)) } returns Unit

        orchestrator.consolidateNow(contextId)

        verify(exactly = 1) { repository.saveAll(any()) }
        assertEquals(2, captured.captured.size)
    }

    @Test
    fun `allowHardDelete defaults off so deletes are ignored`() {
        val pass = FakePass("d") {
            ConsolidationPassResult.Changed("d", propositionsToDelete = listOf("doomed"))
        }
        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(pass)

        orchestrator.consolidateNow(contextId)

        verify(exactly = 0) { repository.delete(any()) }
    }

    @Test
    fun `allowHardDelete on deletes each aggregated id`() {
        val pass = FakePass("d") {
            ConsolidationPassResult.Changed("d", propositionsToDelete = listOf("doomed-1", "doomed-2"))
        }
        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(pass)
            .withAllowHardDelete(true)

        orchestrator.consolidateNow(contextId)

        verify(exactly = 1) { repository.delete("doomed-1") }
        verify(exactly = 1) { repository.delete("doomed-2") }
    }

    @Test
    fun `consolidate returns null below the change-volume threshold`() {
        // active count delta from 0 is 3, below default threshold of 10
        every { repository.query(any()) } returns List(3) { proposition("p$it") }

        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(FakePass("a") { ConsolidationPassResult.NoOp("a") })

        assertNull(orchestrator.consolidate(contextId))
    }

    @Test
    fun `consolidate returns a triggered report at or above the threshold`() {
        every { repository.query(any()) } returns List(15) { proposition("p$it") }

        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(FakePass("a") { ConsolidationPassResult.NoOp("a") })

        val report = orchestrator.consolidate(contextId)
        assertNotNull(report)
        assertTrue(report!!.triggered)
    }

    @Test
    fun `consolidate honors a lowered threshold`() {
        every { repository.query(any()) } returns List(3) { proposition("p$it") }

        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(FakePass("a") { ConsolidationPassResult.NoOp("a") })
            .withChangeVolumeThreshold(2)

        assertNotNull(orchestrator.consolidate(contextId))
    }

    @Test
    fun `consolidateNow always runs regardless of threshold`() {
        every { repository.query(any()) } returns List(1) { proposition("p$it") }

        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(FakePass("a") { ConsolidationPassResult.NoOp("a") })

        val report = orchestrator.consolidateNow(contextId)
        assertNotNull(report)
        assertTrue(report.triggered)
    }

    @Test
    fun `consolidateNow with no passes returns an all-NoOp report and saves nothing of substance`() {
        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)

        val report = orchestrator.consolidateNow(contextId)

        assertTrue(report.passResults.isEmpty())
        assertTrue(report.triggered)
        verify(exactly = 0) { repository.saveAll(any()) }
    }

    @Test
    fun `copy-built orchestrators do not share trigger state`() {
        // A snapshot below the default threshold-10 delta. After a consolidate() call the
        // orchestrator records this count as its per-context baseline. A copy()-derived sibling must
        // start from its OWN empty baseline, not inherit the first orchestrator's recorded count.
        every { repository.query(any()) } returns List(3) { proposition("p$it") }

        val base = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(FakePass("a") { ConsolidationPassResult.NoOp("a") })
            .withChangeVolumeThreshold(3)

        // First call: baseline is 0, delta == 3 >= threshold(3) -> triggers, then records baseline=3.
        assertNotNull(base.consolidate(contextId))
        // Second call on the SAME instance: delta == 3 - 3 == 0 < threshold -> skips (state recorded).
        assertNull(base.consolidate(contextId))

        // A sibling built via the copy-backed builder must NOT see base's recorded baseline.
        // If trigger state were shared by reference, this would skip (delta 0); it must trigger.
        val sibling = base.withChangeVolumeThreshold(3)
        assertNotNull(sibling.consolidate(contextId))
    }

    @Test
    fun `maintain runs a cycle and returns a legacy-shaped empty MaintenanceResult`() {
        // This orchestrator also satisfies the shared MemoryMaintenanceOrchestrator contract:
        // maintain() must run a consolidation cycle (so passes execute) yet report nothing in the
        // legacy-shaped result, since dream-loop output is surfaced via consolidateNow/DreamLoopReport.
        val snapshot = listOf(proposition("p1"), proposition("p2"))
        every { repository.query(any()) } returns snapshot

        val pass = FakePass("a") { ConsolidationPassResult.NoOp("a") }
        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository).withPass(pass)

        val result = orchestrator.maintain(contextId, sessionPropositions = emptyList())

        // A cycle actually ran: the registered pass executed.
        assertEquals(1, pass.runCount)
        // Legacy-shaped result is empty (no consolidation/abstractions/superseded/retired).
        assertNull(result.consolidation)
        assertTrue(result.abstractions.isEmpty())
        assertTrue(result.superseded.isEmpty())
        assertTrue(result.retired.isEmpty())
    }

    @Test
    fun `a failing pass is captured as Failed and the cycle continues`() {
        val boom = FakePass("boom") { throw IllegalStateException("kaboom") }
        val after = FakePass("after") { ConsolidationPassResult.NoOp("after") }

        val orchestrator = DefaultDreamLoopOrchestrator.withRepository(repository)
            .withPass(boom)
            .withPass(after)

        val report = orchestrator.consolidateNow(contextId)

        val failed = report.passResults.filterIsInstance<ConsolidationPassResult.Failed>()
        assertEquals(1, failed.size)
        assertEquals("boom", failed.single().passName)
        // cycle continued: the later pass still ran
        assertEquals(1, after.runCount)
        assertFalse(report.passResults.none { it.passName == "after" })
    }
}
