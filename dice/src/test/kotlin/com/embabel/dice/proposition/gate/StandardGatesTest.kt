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
package com.embabel.dice.proposition.gate

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.revision.RevisionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.temporal.ChronoUnit

class StandardGatesTest {

    private val contextId = ContextId("test-context")

    private fun proposition(
        confidence: Double = 0.8,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ): Proposition = Proposition(
        contextId = contextId,
        text = "Alice knows Kubernetes",
        mentions = emptyList(),
        confidence = confidence,
        status = status,
    )

    /**
     * A proposition whose raw confidence is high but which has aged enough, with a nonzero decay
     * rate, that its decay-adjusted effective confidence drops well below the raw value.
     */
    private fun agedProposition(
        confidence: Double = 0.9,
        decay: Double = 0.5,
        ageInDays: Long = 60,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ): Proposition = Proposition(
        contextId = contextId,
        text = "Alice knows Kubernetes",
        mentions = emptyList(),
        confidence = confidence,
        decay = decay,
        status = status,
        contentRevised = Instant.now().minus(ageInDays, ChronoUnit.DAYS),
    )

    @Nested
    inner class ConfidenceGateTests {

        @Test
        fun `rejects below threshold with reason naming value and threshold`() {
            val evaluation = ConfidenceGate(0.5).evaluate(proposition(confidence = 0.4), GateContext())
            val decision = evaluation.decision
            assertTrue(decision is GateDecision.Reject)
            val reason = (decision as GateDecision.Reject).reason
            assertTrue(reason.contains("0.4"))
            assertTrue(reason.contains("0.5"))
        }

        @Test
        fun `persists at threshold`() {
            val evaluation = ConfidenceGate(0.5).evaluate(proposition(confidence = 0.5), GateContext())
            assertEquals(GateDecision.Persist, evaluation.decision)
        }

        @Test
        fun `persists above threshold`() {
            val evaluation = ConfidenceGate(0.5).evaluate(proposition(confidence = 0.9), GateContext())
            assertEquals(GateDecision.Persist, evaluation.decision)
        }

        @Test
        fun `gate name is simple class name`() {
            val evaluation = ConfidenceGate(0.5).evaluate(proposition(), GateContext())
            assertEquals("ConfidenceGate", evaluation.gateName)
        }

        @Test
        fun `rejects out-of-range policy at construction`() {
            assertThrows<IllegalArgumentException> { ConfidenceGate(1.5) }
        }

        @Test
        fun `thresholds an aged proposition on decay-adjusted effective confidence not raw`() {
            val aged = agedProposition(confidence = 0.9)
            // Sanity: raw confidence is above threshold, effective (decayed) confidence is below it.
            assertTrue(aged.confidence >= 0.6)
            assertTrue(aged.effectiveConfidence() < 0.6)

            val evaluation = ConfidenceGate(0.6).evaluate(aged, GateContext())
            val decision = evaluation.decision
            assertTrue(decision is GateDecision.Reject)
            assertTrue((decision as GateDecision.Reject).reason.contains("effective confidence"))
        }
    }

    @Nested
    inner class MergeCandidateGateTests {

        private val gate = MergeCandidateGate()
        private val prop = proposition()

        @Test
        fun `routes merged to review`() {
            val context = GateContext(revisionResult = RevisionResult.Merged(prop, prop))
            assertTrue(gate.evaluate(prop, context).decision is GateDecision.RouteToReview)
        }

        @Test
        fun `routes reinforced to review`() {
            val context = GateContext(revisionResult = RevisionResult.Reinforced(prop, prop))
            assertTrue(gate.evaluate(prop, context).decision is GateDecision.RouteToReview)
        }

        @Test
        fun `persists new`() {
            val context = GateContext(revisionResult = RevisionResult.New(prop))
            assertEquals(GateDecision.Persist, gate.evaluate(prop, context).decision)
        }

        @Test
        fun `fails open to persist when revision result is null`() {
            assertEquals(GateDecision.Persist, gate.evaluate(prop, GateContext()).decision)
        }

        @Test
        fun `gate name is simple class name`() {
            assertEquals("MergeCandidateGate", gate.evaluate(prop, GateContext()).gateName)
        }
    }

    @Nested
    inner class ConflictClassificationGateTests {

        private val gate = ConflictClassificationGate()
        private val prop = proposition()

        @Test
        fun `routes contradicted to review`() {
            val context = GateContext(revisionResult = RevisionResult.Contradicted(prop, prop))
            assertTrue(gate.evaluate(prop, context).decision is GateDecision.RouteToReview)
        }

        @Test
        fun `persists new`() {
            val context = GateContext(revisionResult = RevisionResult.New(prop))
            assertEquals(GateDecision.Persist, gate.evaluate(prop, context).decision)
        }

        @Test
        fun `persists merged`() {
            val context = GateContext(revisionResult = RevisionResult.Merged(prop, prop))
            assertEquals(GateDecision.Persist, gate.evaluate(prop, context).decision)
        }

        @Test
        fun `fails open to persist when revision result is null`() {
            assertEquals(GateDecision.Persist, gate.evaluate(prop, GateContext()).decision)
        }

        @Test
        fun `gate name is simple class name`() {
            assertEquals("ConflictClassificationGate", gate.evaluate(prop, GateContext()).gateName)
        }
    }

    @Nested
    inner class TrustGateTests {

        @Test
        fun `routes below threshold to review`() {
            val evaluation = TrustGate(0.6).evaluate(proposition(), GateContext(trustScore = 0.4))
            assertTrue(evaluation.decision is GateDecision.RouteToReview)
        }

        @Test
        fun `persists at or above threshold`() {
            val evaluation = TrustGate(0.6).evaluate(proposition(), GateContext(trustScore = 0.8))
            assertEquals(GateDecision.Persist, evaluation.decision)
        }

        @Test
        fun `applies default persist on missing score`() {
            val evaluation = TrustGate(0.6).evaluate(proposition(), GateContext(trustScore = null))
            assertEquals(GateDecision.Persist, evaluation.decision)
        }

        @Test
        fun `applies configured on-missing decision when score is null`() {
            val gate = TrustGate(0.6, onMissingScore = GateDecision.Reject("no trust"))
            val evaluation = gate.evaluate(proposition(), GateContext(trustScore = null))
            assertEquals(GateDecision.Reject("no trust"), evaluation.decision)
        }

        @Test
        fun `gate name is simple class name`() {
            assertEquals("TrustGate", TrustGate(0.6).evaluate(proposition(), GateContext()).gateName)
        }

        @Test
        fun `rejects out-of-range policy at construction`() {
            assertThrows<IllegalArgumentException> { TrustGate(-0.1) }
        }
    }

    @Nested
    inner class ProjectionEligibilityGateTests {

        @Test
        fun `skips projection for low confidence`() {
            val evaluation = ProjectionEligibilityGate(0.3).evaluate(proposition(confidence = 0.2), GateContext())
            assertTrue(evaluation.decision is GateDecision.SkipProjection)
        }

        @Test
        fun `skips projection for contradicted status regardless of confidence`() {
            val prop = proposition(confidence = 0.9, status = PropositionStatus.CONTRADICTED)
            val evaluation = ProjectionEligibilityGate(0.3).evaluate(prop, GateContext())
            assertTrue(evaluation.decision is GateDecision.SkipProjection)
        }

        @Test
        fun `persists eligible active proposition`() {
            val prop = proposition(confidence = 0.5, status = PropositionStatus.ACTIVE)
            assertEquals(GateDecision.Persist, ProjectionEligibilityGate(0.3).evaluate(prop, GateContext()).decision)
        }

        @Test
        fun `default threshold is usable`() {
            assertEquals(GateDecision.Persist, ProjectionEligibilityGate().evaluate(proposition(confidence = 0.5), GateContext()).decision)
        }

        @Test
        fun `gate name is simple class name`() {
            assertEquals("ProjectionEligibilityGate", ProjectionEligibilityGate().evaluate(proposition(), GateContext()).gateName)
        }

        @Test
        fun `rejects out-of-range policy at construction`() {
            assertThrows<IllegalArgumentException> { ProjectionEligibilityGate(1.5) }
        }

        @Test
        fun `skips projection for an aged proposition on decay-adjusted effective confidence not raw`() {
            val aged = agedProposition(confidence = 0.9)
            assertTrue(aged.confidence >= 0.5)
            assertTrue(aged.effectiveConfidence() < 0.5)

            val evaluation = ProjectionEligibilityGate(0.5).evaluate(aged, GateContext())
            assertTrue(evaluation.decision is GateDecision.SkipProjection)
        }
    }
}
