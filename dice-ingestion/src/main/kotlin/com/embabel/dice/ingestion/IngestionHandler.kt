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

import com.embabel.dice.common.SourceAnalysisContext

/**
 * The front-door handoff for turning normalized source material into propositions.
 *
 * The batch method is the primary surface; the single-artifact method is a
 * convenience that wraps one artifact in a one-element batch and delegates.
 *
 * Parsing and connector concerns are deliberately out of scope: external adapters
 * parse their native formats into [IngestedArtifact] text and then implement this
 * interface (or delegate to a shipped handler). A third party can supply its own
 * implementation without touching core.
 */
interface IngestionHandler {

    /**
     * Ingest a batch of artifacts, returning one outcome per artifact.
     *
     * The shipped text handler processes a batch SEQUENTIALLY, in submission
     * order. Its intra-batch deduplication (two artifacts in one batch resolving
     * to the same content hash collapse to a single ingest) depends on that
     * sequential processing — an earlier artifact's deduplication claim is
     * visible to a later identical one. An implementation that processes a batch
     * in parallel must provide its own atomic deduplication rather than relying
     * on processing order.
     *
     * @param batch The artifacts to ingest (the primary handoff surface)
     * @param context The analysis context (schema, resolver, context id)
     * @return The aggregate result with a per-artifact outcome
     */
    fun ingest(batch: IngestionBatch, context: SourceAnalysisContext): IngestionResult

    /**
     * Convenience for ingesting a single artifact. Delegates to [ingest] with a
     * one-element batch so implementations only define the batch path.
     *
     * @param artifact The single artifact to ingest
     * @param context The analysis context
     * @return The aggregate result (with one outcome)
     */
    fun ingest(artifact: IngestedArtifact, context: SourceAnalysisContext): IngestionResult =
        ingest(IngestionBatch.of(artifact), context)
}
