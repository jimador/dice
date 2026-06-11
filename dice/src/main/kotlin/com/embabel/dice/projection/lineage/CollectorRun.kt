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

import java.time.Instant

/**
 * A record of a collection run — a single batch of mark-and-sweep work over propositions.
 *
 * Groups many [CollectorRecord]s under a shared [runId]. Carries a [dryRun] flag so
 * non-mutating previews are recorded separately from runs that actually transition
 * propositions. Computed counts are intentionally out of scope; this is a plain record.
 *
 * @property runId Unique identifier for this run (matches [CollectorRecord.runId])
 * @property startedAt When the run started
 * @property finishedAt When the run finished, or null if still running
 * @property dryRun Whether this run was a non-mutating preview (no propositions changed)
 */
data class CollectorRun @JvmOverloads constructor(
    val runId: String,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val dryRun: Boolean = false,
) {

    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(finishedAt == null || !finishedAt.isBefore(startedAt)) {
            "finishedAt must be null or >= startedAt"
        }
    }

    /**
     * Create a copy marked finished at the given instant.
     *
     * @param at When the run finished (defaults to now)
     * @return a copy with [finishedAt] set
     */
    fun finished(at: Instant = Instant.now()): CollectorRun = copy(finishedAt = at)
}
