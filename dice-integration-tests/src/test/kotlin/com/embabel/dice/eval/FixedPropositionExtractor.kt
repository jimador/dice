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
package com.embabel.dice.eval

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.SuggestedPropositions

/**
 * No-LLM [PropositionExtractor] for the canonical-flow harness.
 *
 * Mirrors the call-counting stub shape used elsewhere in the suite, but returns a fixed,
 * deterministic set of fully-resolved propositions (each with a SUBJECT and an OBJECT mention,
 * all ACTIVE) instead of an empty list — so the downstream graph/query/link/report stages have
 * real edges to operate on. Extraction performs no model or network call.
 *
 * [extractCalls] is exposed so a test can assert extraction is invoked the expected number of
 * times (e.g. no extra calls after a deduplication hit).
 *
 * @param propositions The fixed propositions produced for each ingested chunk.
 */
class FixedPropositionExtractor(
    private val propositions: List<Proposition> = CanonicalFlowFixtures.propositions(),
) : PropositionExtractor {

    var extractCalls = 0
        private set

    override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions {
        extractCalls++
        // The propositions are returned fully-formed in resolvePropositions; the suggestion
        // container only needs to carry the chunk id forward through the pipeline.
        return SuggestedPropositions(chunkId = chunk.id, propositions = emptyList())
    }

    override fun toSuggestedEntities(
        suggestedPropositions: SuggestedPropositions,
        context: SourceAnalysisContext,
        sourceText: String?,
        mentionFilter: MentionFilter?,
    ): SuggestedEntities = SuggestedEntities(emptyList())

    override fun resolvePropositions(
        suggestedPropositions: SuggestedPropositions,
        resolutions: Resolutions<SuggestedEntityResolution>,
        context: SourceAnalysisContext,
    ): List<Proposition> = propositions
}
