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

import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionDemoted
import com.embabel.dice.common.PropositionProjectionSkipped
import com.embabel.dice.common.PropositionRejected
import com.embabel.dice.common.PropositionRoutedToReview
import com.embabel.dice.common.RecordingDiceEventListener
import com.embabel.dice.common.SafeDiceEventListener
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ObservableGateTest {

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

    private fun gateDeciding(decision: GateDecision): ExtractionGate =
        ExtractionGate { proposition, _ -> GateEvaluation("TestGate", proposition, decision) }

    @Test
    fun `a Reject decision emits PropositionRejected carrying the proposition and reason`() {
        val prop = proposition()
        val recorder = RecordingDiceEventListener()
        val gate = ObservableGate(gateDeciding(GateDecision.Reject("low")), recorder)

        gate.evaluate(prop, GateContext())

        assertEquals(1, recorder.count())
        val event = recorder.eventsOfType<PropositionRejected>().single()
        assertEquals(prop, event.proposition)
        assertEquals("low", event.reason)
    }

    @Test
    fun `a RouteToReview decision emits PropositionRoutedToReview carrying the proposition and reason`() {
        val prop = proposition()
        val recorder = RecordingDiceEventListener()
        val gate = ObservableGate(gateDeciding(GateDecision.RouteToReview("dup")), recorder)

        gate.evaluate(prop, GateContext())

        assertEquals(1, recorder.count())
        val event = recorder.eventsOfType<PropositionRoutedToReview>().single()
        assertEquals(prop, event.proposition)
        assertEquals("dup", event.reason)
    }

    @Test
    fun `a SkipProjection decision emits PropositionProjectionSkipped carrying the proposition and reason`() {
        val prop = proposition()
        val recorder = RecordingDiceEventListener()
        val gate = ObservableGate(gateDeciding(GateDecision.SkipProjection("low conf")), recorder)

        gate.evaluate(prop, GateContext())

        assertEquals(1, recorder.count())
        val event = recorder.eventsOfType<PropositionProjectionSkipped>().single()
        assertEquals(prop, event.proposition)
        assertEquals("low conf", event.reason)
    }

    @Test
    fun `a Demote decision emits PropositionDemoted carrying the proposition, toRelation, and reason`() {
        val prop = proposition()
        val recorder = RecordingDiceEventListener()
        val gate = ObservableGate(gateDeciding(GateDecision.Demote("affiliated with", "floor not met")), recorder)

        gate.evaluate(prop, GateContext())

        assertEquals(1, recorder.count())
        val event = recorder.eventsOfType<PropositionDemoted>().single()
        assertEquals(prop, event.proposition)
        assertEquals("affiliated with", event.toRelation)
        assertEquals("floor not met", event.reason)
    }

    @Test
    fun `a Persist decision emits nothing`() {
        val prop = proposition()
        val recorder = RecordingDiceEventListener()
        val gate = ObservableGate(gateDeciding(GateDecision.Persist), recorder)

        gate.evaluate(prop, GateContext())

        assertEquals(0, recorder.count())
    }

    @Test
    fun `evaluate returns the delegate evaluation unchanged`() {
        val prop = proposition()
        val delegateEvaluation = GateEvaluation("TestGate", prop, GateDecision.Reject("low"))
        val delegate = ExtractionGate { _, _ -> delegateEvaluation }
        val gate = ObservableGate(delegate, RecordingDiceEventListener())

        val result = gate.evaluate(prop, GateContext())

        assertSame(delegateEvaluation, result)
        assertEquals("TestGate", result.gateName)
        assertTrue(result.decision is GateDecision.Reject)
    }

    @Test
    fun `the listener defaults to DEV_NULL when none is supplied`() {
        val prop = proposition()
        val gate = ObservableGate(gateDeciding(GateDecision.Reject("low")))

        // No listener supplied: must not throw and must still return the delegate evaluation.
        val result = gate.evaluate(prop, GateContext())

        assertTrue(result.decision is GateDecision.Reject)
    }

    @Test
    fun `a throwing listener wrapped in SafeDiceEventListener does not break the gate run`() {
        val prop = proposition()
        val throwing = DiceEventListener { throw RuntimeException("boom") }
        val gate = ObservableGate(gateDeciding(GateDecision.Reject("low")), SafeDiceEventListener(throwing))

        // The throw is isolated; evaluate completes and returns the delegate evaluation.
        val result = gate.evaluate(prop, GateContext())

        assertTrue(result.decision is GateDecision.Reject)
    }
}
