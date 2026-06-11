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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DecayCollectorStrategyTest {

    private val contextId = ContextId("test-context")

    private fun proposition(
        text: String = "p",
        confidence: Double,
        decay: Double = 0.0,
    ): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = confidence,
            decay = decay,
        )

    @Test
    fun `marks candidates below the threshold with Stale and strategyName decay`() {
        val low = proposition("low", confidence = 0.2)
        val high = proposition("high", confidence = 0.95)
        val repository = mockk<PropositionRepository>(relaxed = true)

        val marks = DecayCollectorStrategy(retireBelow = 0.5)
            .mark(listOf(low, high), repository, contextId)

        assertEquals(1, marks.size)
        assertEquals(low.id, marks[0].propositionId)
        assertEquals(MarkReason.Stale, marks[0].reason)
        assertEquals("decay", marks[0].strategyName)
    }

    @Test
    fun `candidates at or above the threshold produce no marks`() {
        val high = proposition("high", confidence = 0.95)
        val repository = mockk<PropositionRepository>(relaxed = true)

        val marks = DecayCollectorStrategy(retireBelow = 0.5)
            .mark(listOf(high), repository, contextId)

        assertTrue(marks.isEmpty())
    }

    @Test
    fun `performs no repository writes`() {
        val low = proposition("low", confidence = 0.1)
        val repository = mockk<PropositionRepository>(relaxed = true)

        DecayCollectorStrategy(retireBelow = 0.5).mark(listOf(low), repository, contextId)

        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { repository.delete(any<String>()) }
    }

    @Test
    fun `filters on effectiveConfidence so decay-free candidates are judged by raw confidence`() {
        // Freshly created propositions have age 0, so effectiveConfidence == confidence
        // regardless of k. This pins the filter to effectiveConfidence rather than a
        // raw field comparison, for both the default and a custom retireDecayK.
        val justBelow = proposition("below", confidence = 0.49)
        val justAbove = proposition("above", confidence = 0.51)
        val repository = mockk<PropositionRepository>(relaxed = true)

        val withDefaultK = DecayCollectorStrategy(retireBelow = 0.5)
            .mark(listOf(justBelow, justAbove), repository, contextId)
        val withCustomK = DecayCollectorStrategy(retireBelow = 0.5, retireDecayK = 5.0)
            .mark(listOf(justBelow, justAbove), repository, contextId)

        assertEquals(listOf(justBelow.id), withDefaultK.map { it.propositionId })
        assertEquals(listOf(justBelow.id), withCustomK.map { it.propositionId })
    }

    @Test
    fun `mark at defaults to roughly now`() {
        val low = proposition("low", confidence = 0.1)
        val repository = mockk<PropositionRepository>(relaxed = true)
        val before = Instant.now()

        val marks = DecayCollectorStrategy(retireBelow = 0.5).mark(listOf(low), repository, contextId)

        assertTrue(!marks[0].at.isBefore(before))
    }
}
