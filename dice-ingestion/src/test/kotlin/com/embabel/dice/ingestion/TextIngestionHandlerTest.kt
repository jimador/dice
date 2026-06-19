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

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.ingestion.support.TextIngestionHandler
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Critical-behavior coverage for the shipped text front door: single and batch
 * handoff, dedup short-circuit before extraction, and source-locator propagation
 * into grounding. Runs against a real reviser-free pipeline driven by a
 * call-counting stub extractor.
 */
class TextIngestionHandlerTest {

    private val schema = DataDictionary.fromClasses("test")
    private val context = SourceAnalysisContext(
        schema = schema,
        entityResolver = AlwaysCreateEntityResolver,
        contextId = ContextId("test-context"),
    )

    /**
     * Stub extractor returning one fixed proposition per chunk and counting how
     * many times extraction is invoked, so a dedup hit can be proven to skip it.
     */
    private class CountingStubExtractor : PropositionExtractor {
        var extractCalls = 0

        override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions {
            extractCalls++
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
        ): List<Proposition> = listOf(
            Proposition.create(
                id = "prop-${suggestedPropositions.chunkId}",
                contextIdValue = context.contextId.value,
                text = "a fixed fact",
                mentions = emptyList(),
                confidence = 0.9,
                decay = 0.0,
                reasoning = null,
                grounding = listOf(suggestedPropositions.chunkId),
                created = Instant.now(),
                revised = Instant.now(),
                status = PropositionStatus.ACTIVE,
            ),
        )
    }

    private fun handlerWith(extractor: PropositionExtractor): TextIngestionHandler =
        TextIngestionHandler(PropositionPipeline.withExtractor(extractor))

    private fun artifact(sourceId: String, text: String = "extracted text for $sourceId") =
        IngestedArtifact
            .withSourceId(sourceId)
            .withLocator(UriLocator("https://example.com/$sourceId"))
            .withText(text)

    @Test
    fun `single artifact yields one ingested outcome with propositions`() {
        val handler = handlerWith(CountingStubExtractor())

        val result = handler.ingest(artifact("doc-1"), context)

        val outcome = result.outcomes.single()
        assertTrue(outcome is ArtifactOutcome.Ingested)
        assertEquals(1, (outcome as ArtifactOutcome.Ingested).propositions.size)
    }

    @Test
    fun `batch of two distinct artifacts yields two ordered ingested outcomes`() {
        val handler = handlerWith(CountingStubExtractor())

        val result = handler.ingest(
            IngestionBatch.of(artifact("doc-1"), artifact("doc-2")),
            context,
        )

        assertEquals(2, result.outcomes.size)
        assertTrue(result.outcomes.all { it is ArtifactOutcome.Ingested })
        assertEquals(listOf("doc-1", "doc-2"), result.outcomes.map { it.sourceId })
    }

    @Test
    fun `re-ingesting identical content deduplicates before extraction runs`() {
        val extractor = CountingStubExtractor()
        val handler = handlerWith(extractor)
        val same = artifact("doc-1", text = "identical content")

        val first = handler.ingest(same, context).outcomes.single()
        val second = handler.ingest(same, context).outcomes.single()

        assertTrue(first is ArtifactOutcome.Ingested)
        assertTrue(second is ArtifactOutcome.Deduplicated)
        assertEquals(1, extractor.extractCalls)
    }

    /**
     * Stub extractor that throws on a designated chunk text and otherwise behaves
     * like [CountingStubExtractor], so failure isolation and retry-safety can be
     * proven independently of which artifact fails.
     */
    private class ThrowingOnTextExtractor(private val failOnText: String) : PropositionExtractor {
        var extractCalls = 0

        override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions {
            extractCalls++
            if (chunk.text == failOnText) {
                throw IllegalStateException("extraction blew up for $failOnText")
            }
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
        ): List<Proposition> = listOf(
            Proposition.create(
                id = "prop-${suggestedPropositions.chunkId}",
                contextIdValue = context.contextId.value,
                text = "a fixed fact",
                mentions = emptyList(),
                confidence = 0.9,
                decay = 0.0,
                reasoning = null,
                grounding = listOf(suggestedPropositions.chunkId),
                created = Instant.now(),
                revised = Instant.now(),
                status = PropositionStatus.ACTIVE,
            ),
        )
    }

    @Test
    fun `a throwing artifact fails in isolation and stays re-ingestable`() {
        val extractor = ThrowingOnTextExtractor(failOnText = "boom")
        val handler = handlerWith(extractor)
        val good = artifact("doc-good", text = "fine content")
        val bad = artifact("doc-bad", text = "boom")

        val result = handler.ingest(IngestionBatch.of(good, bad), context)

        // Sibling artifact still succeeds despite the other failing.
        assertEquals(2, result.outcomes.size)
        val goodOutcome = result.outcomes.single { it.sourceId == "doc-good" }
        assertTrue(goodOutcome is ArtifactOutcome.Ingested)
        val badOutcome = result.outcomes.single { it.sourceId == "doc-bad" }
        assertTrue(badOutcome is ArtifactOutcome.Failed)
        assertTrue((badOutcome as ArtifactOutcome.Failed).cause is IllegalStateException)

        // Retry-safety: the previously failed content is not deduplicated, so
        // extraction runs again rather than returning a Deduplicated marker.
        val callsAfterFirst = extractor.extractCalls
        val retry = handler.ingest(bad, context).outcomes.single()
        assertTrue(retry is ArtifactOutcome.Failed)
        assertEquals(callsAfterFirst + 1, extractor.extractCalls)
    }

    @Test
    fun `returned propositions carry the artifact source locator in grounding`() {
        val handler = handlerWith(CountingStubExtractor())
        val locator = UriLocator("https://example.com/doc-1")
        val art = IngestedArtifact
            .withSourceId("doc-1")
            .withLocator(locator)
            .withText("grounded text")

        val outcome = handler.ingest(art, context).outcomes.single()

        val proposition = (outcome as ArtifactOutcome.Ingested).propositions.single()
        assertTrue(proposition.provenanceEntries.any { it.locator == locator })
    }
}
