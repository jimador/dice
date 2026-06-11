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
import com.embabel.dice.common.AuthorityTier
import com.embabel.dice.common.EvidenceFloor
import com.embabel.dice.common.FixedAuthorityResolver
import com.embabel.dice.common.PropositionDemoted
import com.embabel.dice.common.PropositionProjectionSkipped
import com.embabel.dice.common.PropositionRejected
import com.embabel.dice.common.PropositionRoutedToReview
import com.embabel.dice.common.RecordingDiceEventListener
import com.embabel.dice.common.Relations
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.revision.RevisionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end behavioural test for the gate layer as a standalone, consumer-invoked
 * pre-persistence stage: real standard gates composed in a pipeline, wrapped for
 * observation, with each final decision routed by the consumer the way the public KDoc
 * documents. The per-component tests cover each piece in isolation; this exercises the
 * documented composition that a consumer actually wires up.
 */
class GatePipelineConsumerFlowTest {

    private val contextId = ContextId("ctx")

    private fun proposition(
        id: String,
        confidence: Double = 0.9,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ): Proposition = Proposition(
        contextId = contextId,
        text = "proposition $id",
        mentions = emptyList(),
        confidence = confidence,
        status = status,
    )

    @Test
    fun `consumer composes standard gates in a pipeline and routes each proposition by its final decision`() {
        val recorder = RecordingDiceEventListener()
        // Convention ordering: confidence, merge, conflict, trust, projection-eligibility,
        // each wrapped so routing events are observed.
        val pipeline = ExtractionGatePipeline(
            listOf(
                ObservableGate(ConfidenceGate(0.5), recorder),
                ObservableGate(MergeCandidateGate(), recorder),
                ObservableGate(ConflictClassificationGate(), recorder),
                ObservableGate(TrustGate(0.4), recorder),
                ObservableGate(ProjectionEligibilityGate(0.3), recorder),
            ),
            shortCircuitOnReject = true,
        )

        val rejected = proposition("rejected", confidence = 0.2)
        val merged = proposition("merged")
        val lowTrust = proposition("low-trust")
        val skipped = proposition("skipped", status = PropositionStatus.CONTRADICTED)
        val clean = proposition("clean")

        // Per-proposition context, as a consumer would derive it from pipeline output.
        val contextFor: (Proposition) -> GateContext = { prop ->
            when (prop.id) {
                merged.id -> GateContext(revisionResult = RevisionResult.Merged(prop, prop))
                lowTrust.id -> GateContext(trustScore = 0.1)
                else -> GateContext()
            }
        }

        val results = pipeline.evaluateAll(
            listOf(rejected, merged, lowTrust, skipped, clean),
            contextFor,
        )

        // The consumer routes each proposition by the final decision; we capture where each lands.
        val persisted = mutableListOf<Proposition>()
        val reviewQueue = mutableListOf<Proposition>()
        val projected = mutableListOf<Proposition>()
        results.forEach { result ->
            when (result.finalDecision) {
                is GateDecision.Persist -> {
                    persisted += result.proposition
                    projected += result.proposition
                }
                is GateDecision.RouteToReview -> reviewQueue += result.proposition
                is GateDecision.Reject -> { /* dropped: not persisted */ }
                is GateDecision.SkipProjection -> persisted += result.proposition // saved, not projected
                is GateDecision.Demote -> {
                    // Saved as-is and projected, but under the weaker predicate the consumer applies.
                    persisted += result.proposition
                    projected += result.proposition
                }
            }
        }

        // rejected: dropped entirely.
        assertTrue(persisted.none { it.id == rejected.id })
        assertTrue(reviewQueue.none { it.id == rejected.id })
        // merged: routed to review (first non-Persist), not persisted directly.
        assertTrue(reviewQueue.any { it.id == merged.id })
        assertTrue(persisted.none { it.id == merged.id })
        // low-trust: trust gate routes to review.
        assertTrue(reviewQueue.any { it.id == lowTrust.id })
        // contradicted: persisted but excluded from projection.
        assertTrue(persisted.any { it.id == skipped.id })
        assertTrue(projected.none { it.id == skipped.id })
        // clean: persisted and projected.
        assertTrue(persisted.any { it.id == clean.id })
        assertTrue(projected.any { it.id == clean.id })

        // Observation: every non-Persist final decision produced exactly the matching event.
        assertEquals(1, recorder.eventsOfType<PropositionRejected>().size)
        assertEquals(2, recorder.eventsOfType<PropositionRoutedToReview>().size)
        assertEquals(1, recorder.eventsOfType<PropositionProjectionSkipped>().size)
    }

    @Test
    fun `EvidenceFloorGate in a composed pipeline produces a Demote decision and emits PropositionDemoted`() {
        val recorder = RecordingDiceEventListener()

        // A "works for" relation requiring confidence >= 0.7 from at least SECONDARY authority,
        // demoting to "affiliated with" when the floor is not met.
        val relations = Relations.empty()
            .withSemanticBetween(
                subjectType = "Person",
                objectType = "Organization",
                predicate = "works for",
                meaning = "is employed by",
                floor = EvidenceFloor(
                    minConfidence = 0.7,
                    minAuthority = AuthorityTier.SECONDARY,
                    demoteTo = "affiliated with",
                ),
            )

        // Authority resolver always returns DERIVED, which is below the SECONDARY floor.
        val pipeline = ExtractionGatePipeline(
            listOf(
                ObservableGate(
                    EvidenceFloorGate(relations, FixedAuthorityResolver(AuthorityTier.DERIVED)),
                    recorder,
                ),
            ),
        )

        // Proposition text matches "works for"; confidence is fine but authority is too weak.
        val prop = Proposition(
            contextId = contextId,
            text = "Alice works for Acme",
            mentions = emptyList(),
            confidence = 0.9,
        )

        val results = pipeline.evaluateAll(listOf(prop)) { GateContext() }

        // Final decision must be Demote, not Persist.
        val result = results.single()
        assertInstanceOf(GateDecision.Demote::class.java, result.finalDecision)
        assertEquals("affiliated with", result.demoteTo)

        // ObservableGate must have emitted exactly one PropositionDemoted event.
        val event = recorder.eventsOfType<PropositionDemoted>().single()
        assertEquals(prop, event.proposition)
        assertEquals("affiliated with", event.toRelation)
    }

    @Test
    fun `the gate stage does not run inside the extraction pipeline so an empty stage persists everything unchanged`() {
        // A consumer that wires up no gates must get the canonical proposition back untouched:
        // the gate stage is additive and standalone, never mutating pipeline output.
        val pipeline = ExtractionGatePipeline(emptyList())
        val input = listOf(proposition("a"), proposition("b"))

        val results = pipeline.evaluateAll(input) { GateContext() }

        assertEquals(input, results.map { it.proposition })
        assertTrue(results.all { it.shouldPersist })
    }
}
