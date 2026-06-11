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
package com.embabel.dice.pipeline

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.*
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.proposition.*
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the pipeline's event surface (`PropositionPipeline.withEventListener`).
 *
 * - `process()` emits exactly one [ExtractionBatchCompleted] per run, carrying
 *   [PropositionExtractionStats].
 * - Per-proposition candidate events (Discovered/Merged/...) are revision-only: a pipeline
 *   without a reviser emits none of them. The canonical durable signal in that case is
 *   `PropositionPersisted`, emitted by the repository decorator on save.
 *   A pipeline with a reviser + `withEventListener` does emit candidate events.
 */
class PropositionPipelineEventTest {

    private val testContextId = ContextId("test-context")
    private val schema = DataDictionary.fromClasses("test")

    /** Minimal extractor producing one proposition per "mentions:..." chunk (mirrors PropositionPipelineTest). */
    private class MockExtractor : PropositionExtractor {
        override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions {
            val text = chunk.text
            if (!text.startsWith("mentions:")) return SuggestedPropositions(chunk.id, emptyList())
            val names = text.substringAfter("mentions:").split(",").map { it.trim() }
            val mentions = names.mapIndexed { i, n ->
                SuggestedMention(span = n, type = "Person", role = if (i == 0) "SUBJECT" else "OBJECT")
            }
            return SuggestedPropositions(
                chunkId = chunk.id,
                propositions = listOf(
                    SuggestedProposition(
                        text = "Proposition about ${names.joinToString(" and ")}",
                        mentions = mentions,
                        confidence = 0.9,
                    )
                ),
            )
        }

        override fun toSuggestedEntities(
            suggestedPropositions: SuggestedPropositions,
            context: SourceAnalysisContext,
            sourceText: String?,
            mentionFilter: com.embabel.dice.common.filter.MentionFilter?,
        ): SuggestedEntities {
            val entities = suggestedPropositions.propositions.flatMap { it.mentions }.map {
                SuggestedEntity(labels = listOf(it.type), name = it.span, summary = it.span, chunkId = suggestedPropositions.chunkId)
            }.distinctBy { it.name }
            return SuggestedEntities(suggestedEntities = entities, sourceText = sourceText)
        }

        override fun resolvePropositions(
            suggestedPropositions: SuggestedPropositions,
            resolutions: Resolutions<SuggestedEntityResolution>,
            context: SourceAnalysisContext,
        ): List<Proposition> {
            val nameToId = resolutions.resolutions.mapNotNull { r -> r.recommended?.let { r.suggested.name.lowercase() to it.id } }.toMap()
            return suggestedPropositions.propositions.map { sp ->
                Proposition(
                    contextId = context.contextId,
                    text = sp.text,
                    mentions = sp.mentions.map { m ->
                        EntityMention(span = m.span, type = m.type, role = MentionRole.valueOf(m.role), resolvedId = nameToId[m.span.lowercase()])
                    },
                    confidence = sp.confidence,
                    grounding = listOf(suggestedPropositions.chunkId),
                )
            }
        }
    }

    /** Reviser that classifies every proposition as New (a candidate event source). */
    private class AllNewReviser : PropositionReviser {
        override fun revise(newProposition: Proposition, repository: PropositionRepository): RevisionResult =
            RevisionResult.New(newProposition)

        override fun classify(newProposition: Proposition, candidates: List<Proposition>) = emptyList<com.embabel.dice.proposition.revision.ClassifiedProposition>()
    }

    private fun chunks() = listOf(
        Chunk(id = "chunk-1", text = "mentions:Alice,Bob", metadata = emptyMap(), parentId = ""),
    )

    private fun context() = SourceAnalysisContext(
        schema = schema,
        entityResolver = AlwaysCreateEntityResolver,
        contextId = testContextId,
    )

    @Test
    fun `process emits exactly one ExtractionBatchCompleted carrying stats`() {
        val recording = RecordingDiceEventListener()
        val pipeline = PropositionPipeline
            .withExtractor(MockExtractor())
            .withRevision(AllNewReviser(), inMemoryRepo())
            .withEventListener(recording)

        pipeline.process(chunks(), context())

        val emitted = recording.eventsOfType<ExtractionBatchCompleted>()
        assertEquals(1, emitted.size, "exactly one ExtractionBatchCompleted per process()")
        // Stats shape is the PropositionExtractionStats from the run.
        assertTrue(emitted.first().stats.total >= 0)
    }

    @Test
    fun `pipeline without reviser emits no per-proposition candidate events`() {
        val recording = RecordingDiceEventListener()
        val pipeline = PropositionPipeline
            .withExtractor(MockExtractor())
            .withEventListener(recording)

        pipeline.process(chunks(), context())

        assertTrue(
            recording.eventsOfType<PropositionDiscovered>().isEmpty(),
            "candidate events are revision-only; a reviser-less pipeline must not emit PropositionDiscovered",
        )
    }

    @Test
    fun `pipeline with reviser emits candidate events`() {
        val recording = RecordingDiceEventListener()
        val pipeline = PropositionPipeline
            .withExtractor(MockExtractor())
            .withRevision(AllNewReviser(), inMemoryRepo())
            .withEventListener(recording)

        pipeline.process(chunks(), context())

        assertTrue(
            recording.eventsOfType<PropositionDiscovered>().isNotEmpty(),
            "a reviser classifying New must yield PropositionDiscovered candidate events",
        )
    }

    @Test
    fun `a throwing listener never aborts the run or discards the result`() {
        val throwing = DiceEventListener { throw RuntimeException("boom") }
        val pipeline = PropositionPipeline
            .withExtractor(MockExtractor())
            .withRevision(AllNewReviser(), inMemoryRepo())
            .withEventListener(throwing)

        // Both grains emit through the listener; neither may propagate the throw out of the pipeline,
        // and process() must still return the fully-computed result.
        val result = assertDoesNotThrow<PropositionResults> {
            pipeline.process(chunks(), context())
        }
        assertTrue(result.propositionExtractionStats.total >= 0)
    }

    private fun inMemoryRepo(): PropositionRepository = object : PropositionRepository {
        private val store = mutableMapOf<String, Proposition>()
        override val luceneSyntaxNotes: String = "test"
        override fun save(proposition: Proposition): Proposition { store[proposition.id] = proposition; return proposition }
        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: com.embabel.agent.rag.service.RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findSimilarWithScores(textSimilaritySearchRequest: com.embabel.common.core.types.TextSimilaritySearchRequest) = emptyList<com.embabel.common.core.types.SimilarityResult<Proposition>>()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store.values.toList()
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }
}
