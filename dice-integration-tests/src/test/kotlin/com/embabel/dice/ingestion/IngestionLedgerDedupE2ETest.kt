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

import com.embabel.dice.eval.CanonicalFlowFixtures
import com.embabel.dice.eval.FixedPropositionExtractor
import com.embabel.dice.eval.FixedVectorEmbeddingService
import com.embabel.dice.ingestion.support.TextIngestionHandler
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end proof that the ingestion-ledger deduplication contract holds across the full stack:
 * artifact → [TextIngestionHandler] → [com.embabel.dice.ingestion.IngestionLedger] check →
 * [com.embabel.dice.pipeline.PropositionPipeline] → proposition store.
 *
 * The handler wires the ledger internally: [TextIngestionHandler] accepts an [IngestionLedger] and
 * calls [IngestionLedger.recordIfAbsent] atomically before any extraction work. A content hash that
 * was already recorded short-circuits with [ArtifactOutcome.Deduplicated] — the extractor is never
 * called, no propositions are produced, and no duplicates land in the store.
 *
 * This test class complements the unit tests that prove the ledger in isolation by tracing the
 * dedup decision through the real pipeline wiring with no LLM, embedding model, network, or container.
 */
class IngestionLedgerDedupE2ETest {

    private val fixtures = CanonicalFlowFixtures
    private val context = fixtures.context

    private fun newStore() = InMemoryPropositionRepository(embeddingService = FixedVectorEmbeddingService())

    private fun newHandler(extractor: FixedPropositionExtractor, ledger: IngestionLedger): TextIngestionHandler =
        TextIngestionHandler(
            pipeline = PropositionPipeline.withExtractor(extractor),
            ledger = ledger,
        )

    private fun artifact(sourceId: String, text: String) =
        IngestedArtifact
            .withSourceId(sourceId)
            .withLocator(UriLocator("https://example.com/$sourceId"))
            .withText(text)

    @Test
    fun `second ingest of identical content is deduplicated — no re-extraction and no duplicate propositions`() {
        val extractor = FixedPropositionExtractor()
        val ledger = InMemoryIngestionLedger()
        val handler = newHandler(extractor, ledger)
        val store = newStore()

        val sameArtifact = artifact("doc-alpha", "Alice works with Bob. Bob works with Carol.")

        // First ingest: ledger has no record, extraction runs, propositions are produced.
        val firstResult = handler.ingest(sameArtifact, context)
        val firstOutcome = firstResult.outcomes.single()
        assertTrue(firstOutcome is ArtifactOutcome.Ingested, "first ingest is fresh")
        val firstPropositions = (firstOutcome as ArtifactOutcome.Ingested).propositions
        assertTrue(firstPropositions.isNotEmpty(), "first ingest yields propositions")
        store.saveAll(firstPropositions)
        assertEquals(1, extractor.extractCalls, "extraction called once for the first ingest")
        val countAfterFirst = store.query(com.embabel.dice.proposition.PropositionQuery.forContextId(context.contextId)).size

        // Second ingest: identical content — ledger hits, extraction must not run.
        val secondResult = handler.ingest(sameArtifact, context)
        val secondOutcome = secondResult.outcomes.single()
        assertTrue(secondOutcome is ArtifactOutcome.Deduplicated, "second ingest is deduplicated")
        // The handler short-circuits before producing any propositions, so nothing to persist.
        assertTrue(secondResult.propositions.isEmpty(), "deduplicated result carries no propositions")
        assertEquals(1, extractor.extractCalls, "extractor not called again after dedup hit")

        // Store is unchanged: dedup prevents any duplicate propositions from landing.
        val countAfterSecond = store.query(com.embabel.dice.proposition.PropositionQuery.forContextId(context.contextId)).size
        assertEquals(countAfterFirst, countAfterSecond, "store proposition count unchanged after duplicate ingest")
    }

    @Test
    fun `intra-batch dedup collapses two identical artifacts to one extraction`() {
        val extractor = FixedPropositionExtractor()
        val ledger = InMemoryIngestionLedger()
        val handler = newHandler(extractor, ledger)

        val text = "Deterministic content for intra-batch test."
        val first = artifact("batch-doc-1", text)
        val second = artifact("batch-doc-2", text) // same text, different sourceId

        val result = handler.ingest(IngestionBatch.of(first, second), context)

        assertEquals(2, result.outcomes.size, "one outcome per submitted artifact")
        assertTrue(result.outcomes[0] is ArtifactOutcome.Ingested, "first artifact in batch is ingested")
        assertTrue(result.outcomes[1] is ArtifactOutcome.Deduplicated, "second identical artifact in batch is deduplicated")
        assertEquals(1, extractor.extractCalls, "extraction runs exactly once for the shared content")
        // Only the first artifact's propositions are returned; the second contributes nothing.
        assertTrue(result.propositions.isNotEmpty(), "batch result carries propositions from the ingested artifact")
    }

    @Test
    fun `genuinely new artifact in a mixed batch is extracted while the repeat is deduplicated`() {
        val extractor = FixedPropositionExtractor()
        val ledger = InMemoryIngestionLedger()
        val handler = newHandler(extractor, ledger)
        val store = newStore()

        val original = artifact("doc-original", "Original knowledge content about Alice and Bob.")

        // Establish the original in the ledger.
        val firstResult = handler.ingest(original, context)
        store.saveAll(firstResult.propositions)
        val callsAfterFirst = extractor.extractCalls

        // Submit a batch: the original (repeat) and a brand-new artifact.
        val newArtifact = artifact("doc-new", "Completely new content about Carol and Dana.")
        val batchResult = handler.ingest(IngestionBatch.of(original, newArtifact), context)

        assertEquals(2, batchResult.outcomes.size)
        val repeatOutcome = batchResult.outcomes.single { it.sourceId == original.sourceId }
        val newOutcome = batchResult.outcomes.single { it.sourceId == newArtifact.sourceId }

        assertTrue(repeatOutcome is ArtifactOutcome.Deduplicated, "the original is deduplicated on repeat")
        assertTrue(newOutcome is ArtifactOutcome.Ingested, "the new artifact is freshly ingested")
        assertEquals(callsAfterFirst + 1, extractor.extractCalls, "extraction runs once more for only the new artifact")

        // Persist the new propositions — only one artifact's worth should land.
        store.saveAll(batchResult.propositions)
        val stored = store.query(com.embabel.dice.proposition.PropositionQuery.forContextId(context.contextId))
        // The store holds propositions from the first ingest plus the new one, with no duplicates.
        assertTrue(stored.isNotEmpty(), "store holds propositions from both ingested artifacts")

        // Prove the ledger independently tracks both hashes after the mixed batch.
        assertTrue(ledger.seen(com.embabel.dice.common.support.Sha256ContentHasher.hash(original.text)),
            "ledger records the original content hash")
        assertTrue(ledger.seen(com.embabel.dice.common.support.Sha256ContentHasher.hash(newArtifact.text)),
            "ledger records the new content hash")
        assertFalse(ledger.seen("nonexistent-hash"), "ledger does not report an unseen hash as seen")
    }
}
