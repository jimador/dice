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
package com.embabel.dice.projection.lineage

import com.embabel.dice.projection.memory.MarkReason
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class CollectorRecordTest {

    @Test
    fun `CollectorRun constructs with finishedAt null and dryRun false by default`() {
        val startedAt = Instant.now()
        val run = CollectorRun("r1", startedAt)

        assertEquals("r1", run.runId)
        assertEquals(startedAt, run.startedAt)
        assertNull(run.finishedAt)
        assertEquals(false, run.dryRun)
    }

    @Test
    fun `CollectorRun finished returns a copy with finishedAt set`() {
        val startedAt = Instant.now()
        val run = CollectorRun("r1", startedAt)
        val finishedInstant = startedAt.plusSeconds(5)

        val finished = run.finished(finishedInstant)

        assertEquals(finishedInstant, finished.finishedAt)
        assertNull(run.finishedAt) // original unchanged
    }

    @Test
    fun `CollectorRun with blank runId throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            CollectorRun("  ", Instant.now())
        }
    }

    @Test
    fun `CollectorRun with finishedAt before startedAt throws`() {
        val startedAt = Instant.now()
        assertThrows(IllegalArgumentException::class.java) {
            CollectorRun("r1", startedAt, finishedAt = startedAt.minusSeconds(1))
        }
    }

    @Test
    fun `CollectorRun carries the dryRun flag`() {
        val run = CollectorRun("r1", Instant.now(), dryRun = true)
        assertTrue(run.dryRun)
    }

    @Test
    fun `CollectorOutcome has exactly MARKED TRANSITIONED HARD_DELETED SKIPPED`() {
        assertEquals(
            listOf("MARKED", "TRANSITIONED", "HARD_DELETED", "SKIPPED"),
            CollectorOutcome.entries.map { it.name },
        )
    }

    @Test
    fun `CollectorRecord constructs and exposes a typed MarkReason`() {
        val record = CollectorRecord(
            propositionId = "p1",
            reason = MarkReason.Stale,
            outcome = CollectorOutcome.TRANSITIONED,
            strategyName = "decay",
            runId = "r1",
            previousStatus = PropositionStatus.ACTIVE,
            newStatus = PropositionStatus.STALE,
        )

        val reason: MarkReason = record.reason
        assertEquals(MarkReason.Stale, reason)
        assertEquals(CollectorOutcome.TRANSITIONED, record.outcome)
        assertEquals(PropositionStatus.ACTIVE, record.previousStatus)
        assertEquals(PropositionStatus.STALE, record.newStatus)
    }

    @Test
    fun `CollectorRecord carries a Duplicate reason with survivorId`() {
        val record = CollectorRecord(
            propositionId = "p1",
            reason = MarkReason.Duplicate(survivorId = "p2"),
            outcome = CollectorOutcome.MARKED,
            strategyName = "duplicate",
            runId = "r1",
        )

        val reason = record.reason
        assertTrue(reason is MarkReason.Duplicate)
        assertEquals("p2", (reason as MarkReason.Duplicate).survivorId)
    }

    @Test
    fun `CollectorRecord of factory mirrors the constructor`() {
        val record = CollectorRecord.of(
            propositionId = "p1",
            reason = MarkReason.Stale,
            outcome = CollectorOutcome.SKIPPED,
            strategyName = "decay",
            runId = "r1",
        )

        assertEquals("p1", record.propositionId)
        assertEquals(CollectorOutcome.SKIPPED, record.outcome)
    }

    @Test
    fun `CollectorRecord with blank propositionId throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            CollectorRecord(
                propositionId = "  ",
                reason = MarkReason.Stale,
                outcome = CollectorOutcome.MARKED,
                strategyName = "decay",
                runId = "r1",
            )
        }
    }

    @Test
    fun `CollectorRecord with blank runId throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            CollectorRecord(
                propositionId = "p1",
                reason = MarkReason.Stale,
                outcome = CollectorOutcome.MARKED,
                strategyName = "decay",
                runId = "   ",
            )
        }
    }
}
