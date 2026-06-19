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
package com.embabel.dice.ingestion

/**
 * A group of [IngestedArtifact]s submitted together through the ingestion handoff.
 *
 * The batch is the primary public handoff surface: handlers process artifacts as
 * a batch, isolating per-artifact failures rather than aborting the whole group.
 * Single-artifact ingestion is a convenience that wraps one artifact in a batch.
 *
 * @property artifacts The artifacts to ingest, in submission order.
 */
data class IngestionBatch @JvmOverloads constructor(
    val artifacts: List<IngestedArtifact> = emptyList(),
) {

    companion object {
        /**
         * Build a batch from the given artifacts.
         * @param artifacts The artifacts to include
         * @return An [IngestionBatch] over those artifacts
         */
        @JvmStatic
        fun of(vararg artifacts: IngestedArtifact): IngestionBatch =
            IngestionBatch(artifacts.toList())
    }
}
