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
package com.embabel.dice.projection.lineage

import com.embabel.dice.projection.memory.MarkReason
import com.embabel.dice.proposition.PropositionStatus
import java.time.Instant

/**
 * A record of one proposition being acted upon by a collector during a run.
 *
 * Collectively these records form an audit trail — "which propositions were marked
 * or swept, why, and by which strategy" — so a reviewer can trace any retired or
 * removed proposition back to the run and reason that produced the outcome.
 *
 * The reason is a typed [MarkReason] rather than a free-form string, and the
 * action is a typed [CollectorOutcome] rather than a lifecycle/magic value, so the
 * collector audit trail stays semantically distinct from projection lineage.
 *
 * @property propositionId ID of the proposition that was acted upon
 * @property reason Typed explanation of why the proposition was marked
 * @property outcome What the collector did to the proposition
 * @property strategyName Name of the collector strategy that produced this record
 * @property runId ID of the collection run that produced this record
 * @property at When this record was created
 * @property previousStatus The proposition's status before the outcome, or null if not applicable
 * @property newStatus The proposition's status after the outcome, or null if not applicable
 */
data class CollectorRecord @JvmOverloads constructor(
    val propositionId: String,
    val reason: MarkReason,
    val outcome: CollectorOutcome,
    val strategyName: String,
    val runId: String,
    val at: Instant = Instant.now(),
    val previousStatus: PropositionStatus? = null,
    val newStatus: PropositionStatus? = null,
) {

    init {
        require(propositionId.isNotBlank()) { "propositionId must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
    }

    companion object {

        /**
         * Java-friendly factory method to create a [CollectorRecord].
         *
         * @param propositionId ID of the proposition that was acted upon
         * @param reason Typed explanation of why the proposition was marked
         * @param outcome What the collector did to the proposition
         * @param strategyName Name of the collector strategy that produced this record
         * @param runId ID of the collection run
         * @param at When this record was created
         * @param previousStatus The proposition's status before the outcome
         * @param newStatus The proposition's status after the outcome
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            propositionId: String,
            reason: MarkReason,
            outcome: CollectorOutcome,
            strategyName: String,
            runId: String,
            at: Instant = Instant.now(),
            previousStatus: PropositionStatus? = null,
            newStatus: PropositionStatus? = null,
        ): CollectorRecord = CollectorRecord(
            propositionId = propositionId,
            reason = reason,
            outcome = outcome,
            strategyName = strategyName,
            runId = runId,
            at = at,
            previousStatus = previousStatus,
            newStatus = newStatus,
        )
    }
}
