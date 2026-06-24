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
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.common.RecordingDiceEventListener
import com.embabel.dice.operations.consolidation.DecaySweepPass
import com.embabel.dice.projection.lineage.CollectorOutcome
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * End-to-end integration test for the decay path through the maintenance stack, wiring real
 * components with no mocks: a real proposition store, the mark-and-sweep collector with its
 * decay strategy and default sweep policy, an audit record store, an event listener, and the
 * dream-loop orchestrator driving a [DecaySweepPass]. It proves the whole chain — orchestrator →
 * pass → collector → strategy → sweep policy → store writes → lifecycle events → audit trail →
 * cycle report — behaves coherently together, not just in isolation.
 */
class DreamLoopReclamationEndToEndTest {

    private val contextId = ContextId("e2e-context")

    private fun proposition(
        text: String,
        confidence: Double,
        decay: Double,
        contentRevised: Instant,
    ): Proposition = Proposition(
        contextId = contextId,
        text = text,
        mentions = emptyList(),
        confidence = confidence,
        decay = decay,
        contentRevised = contentRevised,
    )

    @Test
    fun `a dream-loop decay sweep retires decayed propositions to STALE across the whole stack`() {
        val repository = InMemoryPropositionRepository()
        val recordStore = InMemoryCollectorRecordStore()
        val listener = RecordingDiceEventListener()

        val now = Instant.now()
        val longAgo = now.minus(365, ChronoUnit.DAYS)
        // Fresh and confident: stays ACTIVE. Two old, heavily-decayed facts: should retire.
        val fresh = proposition("fresh fact", confidence = 0.9, decay = 0.1, contentRevised = now)
        val decayedA = proposition("old fact A", confidence = 0.5, decay = 0.5, contentRevised = longAgo)
        val decayedB = proposition("old fact B", confidence = 0.5, decay = 0.5, contentRevised = longAgo)
        repository.saveAll(listOf(fresh, decayedA, decayedB))

        val collector = CollectorRunner
            .withRepository(repository)
            .withStrategy(DecayCollectorStrategy(retireBelow = 0.3))
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()
        val orchestrator = DefaultDreamLoopOrchestrator
            .withRepository(repository)
            .withPass(DecaySweepPass(collector))

        val report = orchestrator.consolidateNow(contextId)

        // The store reflects the transitions: only the fresh fact is still ACTIVE.
        val active = repository.query(PropositionQuery.forContextId(contextId).withStatus(PropositionStatus.ACTIVE))
        val stale = repository.query(PropositionQuery.forContextId(contextId).withStatus(PropositionStatus.STALE))
        assertEquals(setOf("fresh fact"), active.map { it.text }.toSet())
        assertEquals(setOf("old fact A", "old fact B"), stale.map { it.text }.toSet())

        // Each retirement emitted the standard lifecycle event, so observers see collector
        // transitions identically to any other status change.
        val events = listener.eventsOfType<PropositionStatusChanged>()
        assertEquals(2, events.size)
        assertTrue(events.all { it.newStatus == PropositionStatus.STALE })

        // The run is auditable: one run header plus a TRANSITIONED record per swept proposition.
        assertEquals(1, recordStore.runs().size)
        val records = recordStore.all()
        assertEquals(2, records.size)
        assertTrue(records.all { it.outcome == CollectorOutcome.TRANSITIONED })

        // The cycle report counts the externally-applied decay transitions.
        assertEquals(2, report.totalTransitioned)
    }
}
