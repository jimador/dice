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

import com.embabel.dice.spi.PropositionMark
import java.time.Instant

/**
 * Immutable summary of a single collector run.
 *
 * Reports what the run marked and, when not a preview, what it actually applied. The
 * [applied]/[skipped]/[hardDeleted] partitions let a caller audit the outcome without
 * re-querying the repository.
 *
 * @property runId Unique identifier for this run, shared with its audit records in the record store.
 *   Blank for pure-read [CollectorRunner.collect] results — those are never persisted.
 * @property dryRun Whether this was a preview run (no propositions were changed).
 * @property marks Every mark produced by the configured strategies during this run.
 * @property applied Marks whose proposition was actually transitioned (empty on a dry run).
 * @property skipped Marks whose proposition was intentionally left untouched (e.g. pinned).
 * @property hardDeleted IDs of propositions permanently removed (only via an opt-in policy).
 * @property startedAt When the run started.
 * @property finishedAt When the run finished; defaults to now.
 */
data class CollectorRunResult @JvmOverloads constructor(
    val runId: String,
    val dryRun: Boolean,
    val marks: List<PropositionMark>,
    val applied: List<PropositionMark>,
    val skipped: List<PropositionMark>,
    val hardDeleted: List<String>,
    val startedAt: Instant,
    val finishedAt: Instant = Instant.now(),
)
