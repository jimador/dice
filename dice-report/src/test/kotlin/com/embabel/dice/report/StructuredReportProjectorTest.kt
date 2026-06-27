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

    @Test
    fun `empty input returns empty report with title preserved`() {
        val report = StructuredReportProjector().report(emptyList(), "Empty Report")

        assertEquals("Empty Report", report.title)
        assertEquals(0, report.totalCount)
        assertTrue(report.byStatus.isEmpty(), "byStatus must be empty for empty input")
        assertTrue(report.byLevel.isEmpty(), "byLevel must be empty for empty input")
        assertTrue(report.topByConfidence.isEmpty(), "topByConfidence must be empty for empty input")
        assertTrue(report.sourcePropositionIds.isEmpty(), "sourcePropositionIds must be empty for empty input")
    }

    @Test
    fun `topN caps the top-confidence list to the requested size`() {
        val props = (1..7).map { i ->
            proposition("p$i", "text $i", i * 0.1)
        }

        val report = StructuredReportProjector(topN = 3).report(props, "Capped Report")

        assertEquals(3, report.topByConfidence.size, "topByConfidence must be capped at topN=3")
        val ids = report.topByConfidence.map { it.id }
        assertEquals(listOf("p7", "p6", "p5"), ids, "must surface the three highest-confidence propositions in descending order")
    }

    @Test
    fun `ties in confidence are broken by id in ascending order`() {
        val p1 = proposition("z", "text z", 0.8)
        val p2 = proposition("a", "text a", 0.8)

        val report = StructuredReportProjector(topN = 2).report(listOf(p1, p2), "Tie Report")

        assertEquals(2, report.topByConfidence.size)
        assertEquals("a", report.topByConfidence[0].id, "lower id must come first when confidence is equal")
        assertEquals("z", report.topByConfidence[1].id)
    }

    @Test
    fun `byLevel groups propositions by abstraction level`() {
        val p0a = proposition("p0a", "raw fact A", 0.8)
        val p0b = proposition("p0b", "raw fact B", 0.7)
        val p1 = proposition("p1", "derived", 0.9).copy(level = 1, sourceIds = listOf("p0a"))

        val report = StructuredReportProjector().report(listOf(p0a, p0b, p1), "Level Report")

        assertEquals(2, report.byLevel[0]?.size, "level 0 must contain 2 propositions")
        assertEquals(1, report.byLevel[1]?.size, "level 1 must contain 1 proposition")
    }

    @Test
    fun `summary renders a readable breakdown with title, total, status names, and level numbers`() {
        val props = listOf(
            proposition("p1", "active fact", 0.9, PropositionStatus.ACTIVE),
            proposition("p2", "superseded fact", 0.4, PropositionStatus.SUPERSEDED),
            proposition("p3", "another active", 0.7, PropositionStatus.ACTIVE).copy(level = 1, sourceIds = listOf("p1")),
        )

        val report = StructuredReportProjector().report(props, "My Report")
        val summary = report.summary()

        assertTrue(summary.startsWith("# My Report"), "summary must start with the report title as a heading")
        assertTrue(summary.contains("3"), "summary must mention the total count")
        assertTrue(summary.contains("ACTIVE"), "summary must include the ACTIVE status name")
        assertTrue(summary.contains("SUPERSEDED"), "summary must include the SUPERSEDED status name")
        assertTrue(summary.contains("0"), "summary must include level 0")
        assertTrue(summary.contains("1"), "summary must include level 1")
    }

    @Test
    fun `default title is Report when omitted`() {
        val prop = proposition("p1", "some fact", 0.8)

        val report = StructuredReportProjector().report(listOf(prop))

        assertEquals("Report", report.title)
    }
}
