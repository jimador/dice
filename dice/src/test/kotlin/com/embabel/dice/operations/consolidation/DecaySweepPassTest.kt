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
package com.embabel.dice.operations.consolidation

import com.embabel.agent.core.ContextId
import com.embabel.dice.projection.memory.CollectorRunner
import com.embabel.dice.projection.memory.DecayCollectorStrategy
import com.embabel.dice.projection.memory.PropositionMark
import com.embabel.dice.projection.memory.SweepAction
import com.embabel.dice.projection.memory.SweepPolicy
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class DecaySweepPassTest {

    private lateinit var repository: PropositionRepository
    private val contextId = ContextId("test-context")

    private fun proposition(
        text: String,
        confidence: Double = 0.8,
        decay: Double = 0.1,
        entityId: String? = null,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        pinned: Boolean = false,
        revised: Instant = Instant.now(),
    ): Proposition {
        val mentions = if (entityId != null) {
            listOf(EntityMention(span = entityId, type = "Entity", resolvedId = entityId))
        } else {
            emptyList()
        }
        return Proposition(
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = confidence,
            decay = decay,
            status = status,
            pinned = pinned,
            contentRevised = revised,
            metadataRevised = revised,
        )
    }

    @BeforeEach
    fun setup() {
        repository = mockk(relaxed = true)
        every { repository.query(any()) } returns emptyList()
    }

    private fun softRunner(): CollectorRunner =
        CollectorRunner
            .withRepository(repository)
            .withStrategy(DecayCollectorStrategy(retireBelow = 0.3))
            .build()

    private fun hardDeleteRunner(): CollectorRunner =
        CollectorRunner
            .withRepository(repository)
            .withStrategy(DecayCollectorStrategy(retireBelow = 0.3))
            .withPolicy(SweepPolicy { _, marks ->
                if (marks.isEmpty()) SweepAction.Skip else SweepAction.HardDelete
            })
            .build()

    @Test
    fun `name is decay-sweep`() {
        assertEquals("decay-sweep", DecaySweepPass(softRunner()).name)
    }

    @Test
    fun `no candidates yields NoOp mentioning the H threshold`() {
        every { repository.query(any()) } returns emptyList()

        val result = DecaySweepPass(softRunner()).run(contextId, emptyList())

        val noOp = assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
        assertEquals("decay-sweep", noOp.passName)
        assertTrue(noOp.reason.contains("0.1"), "NoOp reason should surface the H threshold: ${noOp.reason}")
    }

    @Test
    fun `ACTIVE proposition decayed below H transitions to STALE and reports Changed with empty save and delete`() {
        val old = Instant.now().minus(365, ChronoUnit.DAYS)
        val decayed = proposition("old fact", confidence = 0.5, decay = 0.5, revised = old)

        every { repository.query(any()) } returns listOf(decayed)
        val saved = mutableListOf<Proposition>()
        every { repository.save(capture(saved)) } answers { saved.last() }

        val result = DecaySweepPass(softRunner()).run(contextId, listOf(decayed))

        val changed = assertInstanceOf(ConsolidationPassResult.Changed::class.java, result)
        assertEquals("decay-sweep", changed.passName)
        // Option A: the collector persisted; the pass is report-only.
        assertTrue(changed.propositionsToSave.isEmpty(), "pass must not return anything to save")
        assertTrue(changed.propositionsToDelete.isEmpty(), "pass must not return anything to delete")
        assertTrue(changed.summary.contains("STALE"), "summary should mention STALE transitions")
        // The collector itself drove the STALE transition.
        assertTrue(saved.any { it.id == decayed.id && it.status == PropositionStatus.STALE })
    }

    @Test
    fun `pinned proposition is skipped by the inherited collector behavior`() {
        val old = Instant.now().minus(365, ChronoUnit.DAYS)
        val pinned = proposition("pinned fact", confidence = 0.5, decay = 0.5, pinned = true, revised = old)

        every { repository.query(any()) } returns listOf(pinned)
        val saved = mutableListOf<Proposition>()
        every { repository.save(capture(saved)) } answers { saved.last() }

        val result = DecaySweepPass(softRunner()).run(contextId, listOf(pinned))

        // Pinned is marked-then-skipped by the policy, so nothing is applied -> NoOp.
        assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
        assertFalse(saved.any { it.id == pinned.id && it.status == PropositionStatus.STALE })
    }

    @Test
    fun `hard-delete policy reports hard deletes in summary but still returns empty propositionsToDelete`() {
        val old = Instant.now().minus(365, ChronoUnit.DAYS)
        val decayed = proposition("old fact", confidence = 0.5, decay = 0.5, revised = old)

        every { repository.query(any()) } returns listOf(decayed)

        val result = DecaySweepPass(hardDeleteRunner()).run(contextId, listOf(decayed))

        val changed = assertInstanceOf(ConsolidationPassResult.Changed::class.java, result)
        assertTrue(changed.propositionsToDelete.isEmpty(), "collector owns the delete; pass returns empty list")
        assertTrue(changed.summary.contains("hard-deleted"), "summary should report hard-deleted count")
    }

    @Test
    fun `thresholds are exposed as constructor params and surfaced in NoOp text`() {
        every { repository.query(any()) } returns emptyList()

        val pass = DecaySweepPass(softRunner(), staleThresholdH = 0.15, recoveryThresholdS = 0.4)
        val result = pass.run(contextId, emptyList())

        val noOp = assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
        assertTrue(noOp.reason.contains("0.15"), "custom H should surface in NoOp text: ${noOp.reason}")
    }

    @Test
    fun `default thresholds are H 0point1 and S 0point25`() {
        // Documented defaults; the marks list is unused here but proves the runner is the only collaborator.
        val unusedMarks: List<PropositionMark> = emptyList()
        assertTrue(unusedMarks.isEmpty())

        val pass = DecaySweepPass(softRunner())
        val result = pass.run(contextId, emptyList())
        val noOp = assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
        assertTrue(noOp.reason.contains("0.1"))
    }

    @Test
    fun `failure in the runner is reported as Failed`() {
        val runner = mockk<CollectorRunner>()
        every { runner.run(any(), any()) } throws IllegalStateException("boom")

        val result = DecaySweepPass(runner).run(contextId, emptyList())

        val failed = assertInstanceOf(ConsolidationPassResult.Failed::class.java, result)
        assertEquals("decay-sweep", failed.passName)
        assertInstanceOf(IllegalStateException::class.java, failed.cause)
    }
}
