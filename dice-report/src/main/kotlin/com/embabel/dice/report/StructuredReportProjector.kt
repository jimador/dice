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

import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory

/**
 * Pure-structural, deterministic [ReportProjector].
 *
 * Aggregates propositions with no LLM, vector store, or external call: groups by
 * status and abstraction level, and surfaces the top-N propositions by effective
 * confidence. Ordering is stable so repeated calls over the same input always
 * yield the same report — this makes it safe to anchor a reproducible demo.
 *
 * @property topN How many propositions to surface in [Report.topByConfidence]
 */
data class StructuredReportProjector @JvmOverloads constructor(
    private val topN: Int = 5,
) : ReportProjector {

    private val logger = LoggerFactory.getLogger(StructuredReportProjector::class.java)

    /**
     * Aggregate [propositions] into a [Report].
     *
     * If the list is empty, returns [Report.EMPTY] with the given title immediately —
     * all maps and lists in the result will be empty. Otherwise:
     * - Groups by [Proposition.status] and [Proposition.level], preserving encounter
     *   order within each group.
     * - Selects the top [topN] (default 5) propositions by effective confidence descending,
     *   ties broken by id for a stable, reproducible order.
     *
     * @param propositions The propositions to aggregate (may be empty)
     * @param title Title for the resulting report
     * @return A deterministic [Report] projection
     */
    override fun report(propositions: List<Proposition>, title: String): Report {
        if (propositions.isEmpty()) {
            logger.debug("report '{}': no propositions, returning empty", title)
            return Report.EMPTY.copy(title = title)
        }

        // Stable grouping: preserve encounter order within each group.
        val byStatus = propositions.groupBy { it.status }
        val byLevel = propositions.groupBy { it.level }

        // Deterministic ordering: effective confidence descending, ties broken by id.
        val topByConfidence = propositions
            .sortedWith(
                compareByDescending<Proposition> { it.effectiveConfidence() }
                    .thenBy { it.id }
            )
            .take(topN)

        val report = Report(
            title = title,
            totalCount = propositions.size,
            byStatus = byStatus,
            byLevel = byLevel,
            topByConfidence = topByConfidence,
            sourcePropositionIds = propositions.map { it.id },
        )
        logger.debug(
            "report '{}': {} total, {} status groups, {} level groups, top-{} by confidence",
            title, report.totalCount, byStatus.size, byLevel.size, topByConfidence.size,
        )
        return report
    }

    companion object {
        /** Create a projector surfacing [topN] propositions by effective confidence. */
        @JvmStatic
        @JvmOverloads
        fun create(topN: Int = 5): StructuredReportProjector = StructuredReportProjector(topN)
    }
}
