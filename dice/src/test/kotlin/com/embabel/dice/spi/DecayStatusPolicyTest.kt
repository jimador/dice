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
package com.embabel.dice.spi

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Direct tests for [DecayStatusPolicy.evaluate] — the hysteresis band and the importance/reinforce
 * utility weighting. Propositions are created with `decay = 0.0`, so a fresh proposition's effective
 * confidence equals its raw confidence and the utility math is deterministic regardless of timing.
 */
class DecayStatusPolicyTest {

    private val contextId = ContextId("test-context")

    private fun proposition(
        confidence: Double,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        importance: Double = 0.5,
        reinforceCount: Int = 0,
        pinned: Boolean = false,
    ): Proposition = Proposition(
        contextId = contextId,
        text = "p",
        mentions = emptyList(),
        confidence = confidence,
        decay = 0.0,
        importance = importance,
        reinforceCount = reinforceCount,
        pinned = pinned,
        status = status,
    )

    // Default thresholds: staleness 0.1, recovery 0.2; default weights are 0.0.

    @Test
    fun `pinned propositions never transition`() {
        assertNull(DecayStatusPolicy().evaluate(proposition(confidence = 0.01, pinned = true)))
    }

    @Test
    fun `ACTIVE below the staleness floor goes STALE`() {
        assertEquals(PropositionStatus.STALE, DecayStatusPolicy().evaluate(proposition(confidence = 0.05)))
    }

    @Test
    fun `ACTIVE inside the hysteresis band does not transition`() {
        // 0.15 is above the staleness floor (0.1) but below recovery (0.2): no change.
        assertNull(DecayStatusPolicy().evaluate(proposition(confidence = 0.15)))
    }

    @Test
    fun `STALE above the recovery ceiling returns to ACTIVE`() {
        assertEquals(
            PropositionStatus.ACTIVE,
            DecayStatusPolicy().evaluate(proposition(confidence = 0.9, status = PropositionStatus.STALE)),
        )
    }

    @Test
    fun `STALE inside the hysteresis band does not revive`() {
        assertNull(DecayStatusPolicy().evaluate(proposition(confidence = 0.15, status = PropositionStatus.STALE)))
    }

    @Test
    fun `importance weight can lift utility above the staleness floor`() {
        // With default weights, 0.08 < 0.1 retires.
        assertEquals(PropositionStatus.STALE, DecayStatusPolicy().evaluate(proposition(confidence = 0.08, importance = 1.0)))
        // With importance weighting, utility = 0.08 * (1 + 0.5 * 1.0) = 0.12 >= 0.1, so it stays ACTIVE.
        assertNull(DecayStatusPolicy(importanceWeight = 0.5).evaluate(proposition(confidence = 0.08, importance = 1.0)))
    }

    @Test
    fun `reinforce weight can lift utility above the staleness floor`() {
        // With default weights, 0.095 < 0.1 retires.
        assertEquals(PropositionStatus.STALE, DecayStatusPolicy().evaluate(proposition(confidence = 0.095, reinforceCount = 10)))
        // ln(1 + 10) ~= 2.40, so utility = 0.095 * (1 + 0.1 * 2.40) ~= 0.118 >= 0.1, staying ACTIVE.
        assertNull(DecayStatusPolicy(reinforceWeight = 0.1).evaluate(proposition(confidence = 0.095, reinforceCount = 10)))
    }

    @Test
    fun `a status other than ACTIVE or STALE never transitions`() {
        assertNull(DecayStatusPolicy().evaluate(proposition(confidence = 0.01, status = PropositionStatus.CONTRADICTED)))
    }
}
