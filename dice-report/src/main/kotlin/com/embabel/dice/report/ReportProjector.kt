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

import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.Projection
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus

/**
 * Aggregates a set of propositions into a structured [Report].
 *
 * Mirrors the single-method, list-aggregating shape of the memory projector: the
 * caller controls the query (which propositions to include) and the projector
 * organizes the resulting set into a report. Implementations are expected to be
 * deterministic — see [StructuredReportProjector] for the pure-structural default.
 *
 * Example usage:
 * ```kotlin
 * val props = repository.query(PropositionQuery.forContextId(ctx))
 * val report = StructuredReportProjector().report(props, "Context Overview")
 * println(report.summary())
 * ```
 */
interface ReportProjector {

    /**
     * Aggregate the given propositions into a structured report.
     *
     * @param propositions The propositions to aggregate (caller controls query)
     * @param title Human-readable title for the report
     * @return A [Report] projection summarizing the propositions
     */
    fun report(propositions: List<Proposition>, title: String = "Report"): Report
}

/**
 * A structured, deterministic aggregation of a set of propositions.
 *
 * Implements [Projection] so the report traces back to the propositions it was
 * derived from via [sourcePropositionIds].
 *
 * @property title Human-readable title for the report
 * @property totalCount Total number of propositions aggregated
 * @property byStatus Propositions grouped by lifecycle [PropositionStatus]
 * @property byLevel Propositions grouped by abstraction level
 * @property topByConfidence Highest-effective-confidence propositions, ordered descending
 * @property sourcePropositionIds Ids of every proposition that fed this report
 */
data class Report @JvmOverloads constructor(
    val title: String,
    val totalCount: Int,
    val byStatus: Map<PropositionStatus, List<Proposition>>,
    val byLevel: Map<Int, List<Proposition>>,
    val topByConfidence: List<Proposition>,
    override val sourcePropositionIds: List<String>,
) : Projection {

    /** Reports are structural aggregations, not confidence-bearing derivations. */
    override val confidence: ZeroToOne = 1.0

    /** Reports do not decay; they are recomputed structurally on demand. */
    override val decay: ZeroToOne = 0.0

    /**
     * Render a concise, human-readable breakdown: counts by status, counts by
     * abstraction level, and the overall total.
     */
    fun summary(): String = buildString {
        appendLine("# $title")
        appendLine("Total propositions: $totalCount")
        if (byStatus.isNotEmpty()) {
            appendLine("By status:")
            byStatus.entries
                .sortedBy { it.key.name }
                .forEach { (status, props) -> appendLine("- ${status.name}: ${props.size}") }
        }
        if (byLevel.isNotEmpty()) {
            appendLine("By level:")
            byLevel.entries
                .sortedBy { it.key }
                .forEach { (level, props) -> appendLine("- level $level: ${props.size}") }
        }
    }.trimEnd()

    companion object {
        /** An empty report with no propositions. */
        @JvmStatic
        val EMPTY = Report(
            title = "Report",
            totalCount = 0,
            byStatus = emptyMap(),
            byLevel = emptyMap(),
            topByConfidence = emptyList(),
            sourcePropositionIds = emptyList(),
        )
    }
}
