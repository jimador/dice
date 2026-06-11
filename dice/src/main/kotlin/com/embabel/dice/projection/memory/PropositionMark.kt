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

import java.time.Instant

/**
 * An immutable record that a [CollectorStrategy] has marked a proposition for collection.
 *
 * A mark is purely descriptive — producing one never mutates the repository. The sweep
 * phase ([SweepPolicy]) decides what, if anything, to do about a marked proposition.
 *
 * @property propositionId ID of the marked proposition; must not be blank.
 * @property reason Why the proposition was marked.
 * @property strategyName Name of the strategy that produced the mark (for audit/grouping).
 * @property at When the mark was created.
 */
data class PropositionMark @JvmOverloads constructor(
    val propositionId: String,
    val reason: MarkReason,
    val strategyName: String,
    val at: Instant = Instant.now(),
) {

    init {
        require(propositionId.isNotBlank()) { "propositionId must not be blank" }
    }
}
