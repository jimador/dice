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

/**
 * Why a proposition was marked for collection by a [CollectorStrategy].
 *
 * A sealed family so the set of recognized reasons is closed for the built-in
 * strategies, while remaining open for consumer-specific signals via [Custom].
 * Every case exposes a stable machine [key] so a downstream audit record can label
 * the reason consistently without pattern-matching on the concrete type.
 *
 * @property key Stable machine label for audit/grouping (e.g. `"stale"`, `"duplicate"`).
 */
sealed interface MarkReason {

    val key: String

    /**
     * The proposition's decayed utility dropped below the staleness threshold.
     */
    data object Stale : MarkReason {
        override val key: String = "stale"
    }

    /**
     * The proposition duplicates another, surviving proposition.
     *
     * @property survivorId ID of the proposition that should be kept.
     */
    data class Duplicate(val survivorId: String) : MarkReason {
        override val key: String = "duplicate"
    }

    /**
     * A consumer-defined reason, carrying its own machine key and human description.
     *
     * @property key Stable machine label supplied by the consumer.
     * @property description Human-readable explanation of the reason.
     */
    data class Custom(override val key: String, val description: String) : MarkReason
}
