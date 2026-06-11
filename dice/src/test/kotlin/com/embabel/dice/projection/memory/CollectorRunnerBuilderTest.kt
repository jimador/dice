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

import com.embabel.dice.proposition.PropositionRepository
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Oracles for the [CollectorRunner] SPI surface: the immutable [CollectorRunResult] shape and the
 * fluent [CollectorRunner.withRepository] builder with its documented defaults.
 */
class CollectorRunnerBuilderTest {

    private val repository: PropositionRepository = mockk(relaxed = true)

    @Test
    fun `CollectorRunResult is an immutable data class with the documented fields`() {
        val started = Instant.now()
        val finished = started.plusSeconds(1)
        val result = CollectorRunResult(
            runId = "run-1",
            dryRun = true,
            marks = emptyList(),
            applied = emptyList(),
            skipped = emptyList(),
            hardDeleted = emptyList(),
            startedAt = started,
            finishedAt = finished,
        )

        assertEquals("run-1", result.runId)
        assertTrue(result.dryRun)
        assertEquals(started, result.startedAt)
        assertEquals(finished, result.finishedAt)
        // copy() proves data-class immutability semantics
        assertFalse(result.copy(dryRun = false).dryRun)
    }

    @Test
    fun `CollectorRunResult finishedAt defaults to now`() {
        val before = Instant.now()
        val result = CollectorRunResult(
            runId = "run-2",
            dryRun = false,
            marks = emptyList(),
            applied = emptyList(),
            skipped = emptyList(),
            hardDeleted = emptyList(),
            startedAt = before,
        )
        assertTrue(!result.finishedAt.isBefore(before))
    }

    @Test
    fun `withRepository builds a DefaultCollectorRunner with documented defaults`() {
        val runner = CollectorRunner.withRepository(repository).build()
        assertInstanceOf(DefaultCollectorRunner::class.java, runner)
    }

    @Test
    fun `builder with-chain returns the builder for fluent composition`() {
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(DecayCollectorStrategy(retireBelow = 0.3))
            .withPolicy(StatusTransitionSweepPolicy())
            .withRecordStore(com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore())
            .withEventListener(com.embabel.dice.common.DiceEventListener.DEV_NULL)
            .build()
        assertInstanceOf(DefaultCollectorRunner::class.java, runner)
    }
}
