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

class ExtractionGateTest {

    private fun proposition(): Proposition = Proposition.create(
        id = "p1",
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

    @Test
    fun `GateContext default-constructs with all-null and empty fields`() {
        val context = GateContext()
        assertEquals(null, context.revisionResult)
        assertEquals(null, context.trustScore)
        assertEquals(null, context.sourceContext)
        assertTrue(context.metadata.isEmpty())
    }

    @Test
    fun `GateContext reads an injected trust score`() {
        val context = GateContext(trustScore = 0.4)
        assertEquals(0.4, context.trustScore)
    }

    @Test
    fun `GateDecision has exactly four cases and Persist is a singleton`() {
        assertTrue(GateDecision.Persist is GateDecision)
        assertTrue(GateDecision.RouteToReview("dup") is GateDecision)
        assertTrue(GateDecision.Reject("low") is GateDecision)
        assertTrue(GateDecision.SkipProjection("noisy") is GateDecision)
        assertEquals("low", (GateDecision.Reject("low")).reason)
    }

    @Test
    fun `GateEvaluation pairs gate name, proposition, and decision`() {
        val prop = proposition()
        val eval = GateEvaluation("ConfidenceGate", prop, GateDecision.Reject("low"))
        assertEquals("ConfidenceGate", eval.gateName)
        assertEquals(prop, eval.proposition)
        assertTrue(eval.decision is GateDecision.Reject)
    }

    @Test
    fun `GatedPropositionResult exposes shouldPersist for a Persist decision`() {
        val prop = proposition()
        val result = GatedPropositionResult(prop, emptyList(), GateDecision.Persist)
        assertTrue(result.shouldPersist)
        assertFalse(result.isRejected)
        assertFalse(result.shouldReview)
        assertFalse(result.skipProjection)
    }

    @Test
    fun `GatedPropositionResult exposes shouldReview for a RouteToReview decision`() {
        val prop = proposition()
        val result = GatedPropositionResult(prop, emptyList(), GateDecision.RouteToReview("dup"))
        assertTrue(result.shouldReview)
        assertFalse(result.shouldPersist)
    }

    @Test
    fun `GatedPropositionResult exposes isRejected for a Reject decision`() {
        val prop = proposition()
        val result = GatedPropositionResult(prop, emptyList(), GateDecision.Reject("low"))
        assertTrue(result.isRejected)
        assertFalse(result.shouldPersist)
    }

    @Test
    fun `GatedPropositionResult exposes skipProjection for a SkipProjection decision`() {
        val prop = proposition()
        val result = GatedPropositionResult(prop, emptyList(), GateDecision.SkipProjection("noisy"))
        assertTrue(result.skipProjection)
        assertFalse(result.shouldPersist)
    }

    @Test
    fun `ExtractionGate is a SAM that returns a GateEvaluation`() {
        val prop = proposition()
        val gate = ExtractionGate { proposition, _ ->
            GateEvaluation("AlwaysPersist", proposition, GateDecision.Persist)
        }
        val eval = gate.evaluate(prop, GateContext())
        assertEquals("AlwaysPersist", eval.gateName)
        assertTrue(eval.decision is GateDecision.Persist)
    }
}
