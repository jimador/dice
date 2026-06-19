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
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the ingestion handoff SPI is implementable outside any shipped handler
 * and that the single-artifact convenience delegates to the batch method.
 */
class IngestionHandlerSpiTest {

    private val schema = DataDictionary.fromClasses("test")
    private val context = SourceAnalysisContext(
        schema = schema,
        entityResolver = AlwaysCreateEntityResolver,
        contextId = ContextId("test-context"),
    )

    private fun artifact(sourceId: String = "doc-1") =
        IngestedArtifact
            .withSourceId(sourceId)
            .withLocator(UriLocator("https://example.com/$sourceId"))
            .withText("some extracted text")

    /**
     * A custom handler with no parsing or pipeline: it records the batch it was
     * given so the test can prove the single-artifact path routes through batch.
     */
    private class RecordingHandler : IngestionHandler {
        var lastBatch: IngestionBatch? = null

        override fun ingest(batch: IngestionBatch, context: SourceAnalysisContext): IngestionResult {
            lastBatch = batch
            return IngestionResult(
                batch.artifacts.map { ArtifactOutcome.Ingested(it.sourceId, emptyList()) },
            )
        }
    }

    @Test
    fun `custom handler implements the SPI and runs against a batch`() {
        val handler = RecordingHandler()

        val result = handler.ingest(IngestionBatch.of(artifact()), context)

        assertEquals(1, result.outcomes.size)
        assertTrue(result.outcomes.single() is ArtifactOutcome.Ingested)
    }

    @Test
    fun `single-artifact convenience delegates to a one-element batch`() {
        val handler = RecordingHandler()

        handler.ingest(artifact("solo"), context)

        val recorded = handler.lastBatch
        assertEquals(1, recorded?.artifacts?.size)
        assertEquals("solo", recorded?.artifacts?.single()?.sourceId)
    }

    @Test
    fun `in-memory ledger round-trips a content hash`() {
        val ledger = InMemoryIngestionLedger()
        val hash = "abc123"

        assertFalse(ledger.seen(hash))
        ledger.record(hash)
        assertTrue(ledger.seen(hash))
    }
}
