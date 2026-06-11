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
package com.embabel.dice.report

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuredReportProjectorTest {

    private val contextId = ContextId("test")

    private fun proposition(
        id: String,
        text: String,
        confidence: Double,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ): Proposition = Proposition(
        id = id,
        contextId = contextId,
        text = text,
        mentions = emptyList(),
        confidence = confidence,
        status = status,
    )

    @Test
    fun `aggregates propositions into a deterministic structured report`() {
        val props = listOf(
            proposition("p1", "Alice likes jazz", 0.9),
            proposition("p2", "Bob likes rock", 0.7),
            proposition("p3", "Carol likes blues", 0.5),
            proposition("p4", "Dave used to like pop", 0.6, PropositionStatus.SUPERSEDED),
        )

        val report = StructuredReportProjector().report(props, "Test Report")

        assertEquals("Test Report", report.title)
        assertEquals(4, report.totalCount)
        assertEquals(3, report.byStatus[PropositionStatus.ACTIVE]?.size)
        assertEquals(1, report.byStatus[PropositionStatus.SUPERSEDED]?.size)
        assertTrue(report.sourcePropositionIds.containsAll(listOf("p1", "p2", "p3", "p4")))

        // topByConfidence ordered highest-first
        val confidences = report.topByConfidence.map { it.effectiveConfidence() }
        assertEquals(confidences.sortedDescending(), confidences)
        assertEquals("p1", report.topByConfidence.first().id)
    }
}
