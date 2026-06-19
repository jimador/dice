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

import com.embabel.dice.proposition.Proposition

/**
 * The outcome of ingesting a single artifact within a batch.
 *
 * Per-artifact outcomes are explicit so callers can distinguish "already seen"
 * from "extracted nothing" and isolate failures to the offending artifact.
 */
sealed interface ArtifactOutcome {

    /** The source id of the artifact this outcome describes. */
    val sourceId: String

    /**
     * The artifact was newly ingested, yielding [propositions].
     *
     * @property sourceId The artifact's source id
     * @property propositions The propositions extracted from the artifact (unsaved)
     */
    data class Ingested(
        override val sourceId: String,
        val propositions: List<Proposition>,
    ) : ArtifactOutcome

    /**
     * The artifact's content was already seen and was skipped before extraction.
     * Carries no propositions — distinct from an [Ingested] result with an empty list.
     *
     * @property sourceId The artifact's source id
     * @property contentHash The deduplication key that matched a prior ingestion
     */
    data class Deduplicated(
        override val sourceId: String,
        val contentHash: String,
    ) : ArtifactOutcome

    /**
     * Ingesting the artifact failed; the rest of the batch is unaffected.
     *
     * @property sourceId The artifact's source id
     * @property cause The failure that prevented ingestion
     */
    data class Failed(
        override val sourceId: String,
        val cause: Throwable,
    ) : ArtifactOutcome
}

/**
 * The aggregate result of ingesting a batch, with one [ArtifactOutcome] per artifact.
 *
 * @property outcomes Per-artifact outcomes in batch order.
 */
data class IngestionResult(
    val outcomes: List<ArtifactOutcome>,
) {

    /**
     * All propositions across the batch's [ArtifactOutcome.Ingested] outcomes.
     * These are unsaved; persistence is the caller's concern.
     */
    val propositions: List<Proposition>
        get() = outcomes.filterIsInstance<ArtifactOutcome.Ingested>().flatMap { it.propositions }
}
