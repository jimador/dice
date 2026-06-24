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
import com.embabel.agent.rag.service.Cluster
import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.spi.MarkReason
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DuplicateCollectorStrategyTest {

    private val contextId = ContextId("test-context")

    private fun proposition(
        text: String,
        confidence: Double = 0.8,
        reinforceCount: Int = 0,
    ): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = confidence,
            decay = 0.0,
            reinforceCount = reinforceCount,
        )

    private fun cluster(anchor: Proposition, vararg similar: Proposition): Cluster<Proposition> =
        Cluster(anchor, similar.map { SimilarityResult.create(it, 0.95) })

    @Test
    fun `marks only the weaker member of a two-member cluster with the survivor id`() {
        val strong = proposition("strong", confidence = 0.9)
        val weak = proposition("weak", confidence = 0.3)
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findClusters(any(), any(), any()) } returns listOf(cluster(strong, weak))

        val marks = DuplicateCollectorStrategy()
            .mark(listOf(strong, weak), repository, contextId)

        assertEquals(1, marks.size)
        assertEquals(weak.id, marks[0].propositionId)
        assertEquals(MarkReason.Duplicate(strong.id), marks[0].reason)
        assertEquals("duplicate", marks[0].strategyName)
    }

    @Test
    fun `never marks the survivor`() {
        val strong = proposition("strong", confidence = 0.9)
        val weak = proposition("weak", confidence = 0.3)
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findClusters(any(), any(), any()) } returns listOf(cluster(strong, weak))

        val marks = DuplicateCollectorStrategy()
            .mark(listOf(strong, weak), repository, contextId)

        assertFalse(marks.any { it.propositionId == strong.id })
    }

    @Test
    fun `tie on effectiveConfidence is broken by reinforceCount`() {
        val survivor = proposition("a", confidence = 0.6, reinforceCount = 5)
        val loser = proposition("b", confidence = 0.6, reinforceCount = 1)
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findClusters(any(), any(), any()) } returns listOf(cluster(survivor, loser))

        val marks = DuplicateCollectorStrategy()
            .mark(listOf(survivor, loser), repository, contextId)

        assertEquals(1, marks.size)
        assertEquals(loser.id, marks[0].propositionId)
        assertEquals(MarkReason.Duplicate(survivor.id), marks[0].reason)
    }

    @Test
    fun `a single-member cluster produces no marks`() {
        val only = proposition("only", confidence = 0.8)
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findClusters(any(), any(), any()) } returns
            listOf(Cluster(only, emptyList()))

        val marks = DuplicateCollectorStrategy()
            .mark(listOf(only), repository, contextId)

        assertTrue(marks.isEmpty())
    }

    @Test
    fun `performs no repository writes`() {
        val strong = proposition("strong", confidence = 0.9)
        val weak = proposition("weak", confidence = 0.3)
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findClusters(any(), any(), any()) } returns listOf(cluster(strong, weak))

        DuplicateCollectorStrategy().mark(listOf(strong, weak), repository, contextId)

        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { repository.delete(any<String>()) }
    }

    @Test
    fun `ignores cluster members absent from the runner candidate snapshot`() {
        val strong = proposition("strong", confidence = 0.9)
        val weak = proposition("weak", confidence = 0.3)
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findClusters(any(), any(), any()) } returns listOf(cluster(strong, weak))

        // candidates intentionally empty: members outside the swept candidate set are not marked,
        // so every emitted mark maps to a runner-selected candidate.
        val marks = DuplicateCollectorStrategy().mark(emptyList(), repository, contextId)

        assertTrue(marks.isEmpty())
        verify(exactly = 0) { repository.findById(any<String>()) }
    }

    @Test
    fun `is idempotent and deterministic under overlapping clusters`() {
        // Two overlapping clusters sharing member b: {a, b} and {b, c}.
        // b is a non-survivor in one cluster and could be a survivor in the other;
        // global component selection must pick a single survivor and mark each loser once.
        val a = proposition("a", confidence = 0.5)
        val b = proposition("b", confidence = 0.9)
        val c = proposition("c", confidence = 0.7)
        val candidates = listOf(a, b, c)
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findClusters(any(), any(), any()) } returns
            listOf(cluster(a, b), cluster(b, c))

        val first = DuplicateCollectorStrategy().mark(candidates, repository, contextId)
        val second = DuplicateCollectorStrategy().mark(candidates, repository, contextId)

        // Identical marks across runs (deterministic, ordered by propositionId).
        assertEquals(first.map { it.propositionId }, second.map { it.propositionId })
        // No proposition marked more than once.
        assertEquals(first.size, first.distinctBy { it.propositionId }.size)
        // b is the strongest in the {a,b,c} component, so it survives and is never marked.
        assertFalse(first.any { it.propositionId == b.id })
        // a and c are the losers, both pointing at b as survivor.
        assertEquals(setOf(a.id, c.id), first.map { it.propositionId }.toSet())
        assertTrue(first.all { it.reason == MarkReason.Duplicate(b.id) })
    }

    @Test
    fun `full tie on confidence and reinforceCount is broken deterministically by id`() {
        val one = proposition("one", confidence = 0.6, reinforceCount = 2)
        val two = proposition("two", confidence = 0.6, reinforceCount = 2)
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findClusters(any(), any(), any()) } returns listOf(cluster(one, two))

        val marks = DuplicateCollectorStrategy().mark(listOf(one, two), repository, contextId)

        // Survivor is the larger id; the smaller id is marked. Deterministic regardless of input order.
        val expectedSurvivor = maxOf(one.id, two.id)
        val expectedLoser = minOf(one.id, two.id)
        assertEquals(1, marks.size)
        assertEquals(expectedLoser, marks[0].propositionId)
        assertEquals(MarkReason.Duplicate(expectedSurvivor), marks[0].reason)
    }
}
