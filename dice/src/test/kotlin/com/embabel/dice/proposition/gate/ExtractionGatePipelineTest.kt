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

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ExtractionGatePipelineTest {

    private fun proposition(id: String = "p1"): Proposition = Proposition.create(
        id = id,
        contextIdValue = "ctx",
        text = "The sky is blue",
        mentions = emptyList(),
        confidence = 0.8,
        decay = 0.0,
        reasoning = null,
        grounding = emptyList(),
        created = Instant.now(),
        revised = Instant.now(),
        status = PropositionStatus.ACTIVE,
    )

    private fun gate(name: String, decision: GateDecision): ExtractionGate =
        ExtractionGate { proposition, _ -> GateEvaluation(name, proposition, decision) }

    private fun alwaysPersist(name: String = "Persist"): ExtractionGate =
        gate(name, GateDecision.Persist)

    @Test
    fun `two persist gates produce a Persist final decision and record both evaluations`() {
        val pipeline = ExtractionGatePipeline(listOf(alwaysPersist("a"), alwaysPersist("b")))
        val result = pipeline.evaluate(proposition(), GateContext())
        assertTrue(result.finalDecision is GateDecision.Persist)
        assertTrue(result.shouldPersist)
        assertEquals(2, result.evaluations.size)
        assertEquals(listOf("a", "b"), result.evaluations.map { it.gateName })
    }

    @Test
    fun `first non-Persist wins so a later Reject cannot override an earlier RouteToReview`() {
        val pipeline = ExtractionGatePipeline(
            listOf(
                alwaysPersist("persist"),
                gate("review", GateDecision.RouteToReview("dup")),
                gate("reject", GateDecision.Reject("low")),
            ),
            shortCircuitOnReject = true,
        )
        val result = pipeline.evaluate(proposition(), GateContext())

        // First non-Persist decision wins: RouteToReview, NOT the later Reject.
        assertTrue(result.finalDecision is GateDecision.RouteToReview)
        assertEquals("dup", (result.finalDecision as GateDecision.RouteToReview).reason)

        // The Reject gate is still reached and recorded; short-circuit fires after it.
        assertEquals(listOf("persist", "review", "reject"), result.evaluations.map { it.gateName })
    }

    @Test
    fun `short-circuit stops execution after the first Reject`() {
        val pipeline = ExtractionGatePipeline(
            listOf(
                gate("reject", GateDecision.Reject("a")),
                gate("skip", GateDecision.SkipProjection("b")),
            ),
            shortCircuitOnReject = true,
        )
        val result = pipeline.evaluate(proposition(), GateContext())
        assertTrue(result.finalDecision is GateDecision.Reject)
        assertEquals("a", (result.finalDecision as GateDecision.Reject).reason)
        // The skipProjection gate never runs.
        assertEquals(listOf("reject"), result.evaluations.map { it.gateName })
    }

    @Test
    fun `first non-Persist wins still yields the earlier Reject when short-circuit is off`() {
        val pipeline = ExtractionGatePipeline(
            listOf(
                gate("reject", GateDecision.Reject("a")),
                gate("skip", GateDecision.SkipProjection("b")),
            ),
            shortCircuitOnReject = false,
        )
        val result = pipeline.evaluate(proposition(), GateContext())
        assertTrue(result.finalDecision is GateDecision.Reject)
        assertEquals("a", (result.finalDecision as GateDecision.Reject).reason)
    }

    @Test
    fun `with short-circuit off every gate runs and every evaluation is recorded`() {
        val pipeline = ExtractionGatePipeline(
            listOf(
                gate("review", GateDecision.RouteToReview("dup")),
                gate("reject", GateDecision.Reject("low")),
                gate("skip", GateDecision.SkipProjection("noisy")),
            ),
            shortCircuitOnReject = false,
        )
        val result = pipeline.evaluate(proposition(), GateContext())
        // First non-Persist encountered is RouteToReview.
        assertTrue(result.finalDecision is GateDecision.RouteToReview)
        assertEquals(3, result.evaluations.size)
        assertEquals(listOf("review", "reject", "skip"), result.evaluations.map { it.gateName })
    }

    @Test
    fun `evaluateAll maps a batch with a per-proposition context factory`() {
        val seen = mutableListOf<Double?>()
        val recordingGate = ExtractionGate { proposition, context ->
            seen.add(context.trustScore)
            GateEvaluation("recorder", proposition, GateDecision.Persist)
        }
        val pipeline = ExtractionGatePipeline(listOf(recordingGate))
        val p1 = proposition("p1")
        val p2 = proposition("p2")

        val results = pipeline.evaluateAll(listOf(p1, p2)) { prop ->
            GateContext(trustScore = if (prop.id == "p1") 0.1 else 0.9)
        }

        assertEquals(2, results.size)
        assertEquals(p1, results[0].proposition)
        assertEquals(p2, results[1].proposition)
        // Each proposition was evaluated with the context produced by the factory.
        assertEquals(listOf(0.1, 0.9), seen)
    }

    @Test
    fun `an empty gate list yields a Persist final decision and no evaluations`() {
        val pipeline = ExtractionGatePipeline(emptyList())
        val result = pipeline.evaluate(proposition(), GateContext())
        assertTrue(result.finalDecision is GateDecision.Persist)
        assertTrue(result.evaluations.isEmpty())
    }

    @Test
    fun `mutating the caller's backing list after construction does not change pipeline behaviour`() {
        val backing = mutableListOf(gate("reject", GateDecision.Reject("low")))
        val pipeline = ExtractionGatePipeline(backing)

        // Mutate the list the caller passed in after the pipeline was constructed.
        backing.clear()
        backing.add(alwaysPersist("persist"))

        val result = pipeline.evaluate(proposition(), GateContext())

        // The pipeline still runs its snapshot (the original Reject gate), unaffected by mutation.
        assertTrue(result.isRejected)
        assertEquals(listOf("reject"), result.evaluations.map { it.gateName })
    }
}
