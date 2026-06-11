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
package com.embabel.dice.report

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end, fully deterministic demonstration that ties the surprising-link
 * surface together over a real proposition store.
 *
 * The narrative: a small knowledge base mentions Ada Lovelace and the Analytical
 * Engine in one proposition, and Charles Babbage and the Analytical Engine in
 * another — but nothing ever co-mentions Ada and Babbage directly. The two-hop
 * discoverer should nonetheless surface the indirect Ada~Babbage connection,
 * routed through the Analytical Engine, as a reviewable candidate. The same
 * stored set is then aggregated into a structured report.
 *
 * Everything runs locally: an [InMemoryPropositionRepository] with no embedding
 * service (vector/cluster paths degrade to empty), structural-only discovery, and
 * a pure-structural report. No live LLM, Neo4j, or vector store is involved, so a
 * single run always produces the same result.
 */
class SurprisingLinkDemoTest {

    private val contextId = ContextId("surprising-link-demo")

    private fun proposition(
        id: String,
        text: String,
        first: Pair<String, String>,
        second: Pair<String, String>,
    ): Proposition = Proposition(
        id = id,
        contextId = contextId,
        text = text,
        mentions = listOf(
            EntityMention(span = first.first, type = "Entity", resolvedId = first.second, role = MentionRole.SUBJECT),
            EntityMention(span = second.first, type = "Entity", resolvedId = second.second, role = MentionRole.OBJECT),
        ),
        confidence = 0.9,
    )

    @Test
    fun `seeded repo surfaces the indirect ada-babbage link and aggregates a report`() {
        // 1. Seed a deterministic in-memory store (no embedder => no vector paths).
        val repository = InMemoryPropositionRepository(embeddingService = null)

        val adaEngine = proposition(
            id = "p-ada-engine",
            text = "Ada Lovelace wrote the first notes on the Analytical Engine.",
            first = "Ada Lovelace" to "ada",
            second = "Analytical Engine" to "analytical-engine",
        )
        val babbageEngine = proposition(
            id = "p-babbage-engine",
            text = "Charles Babbage designed the Analytical Engine.",
            first = "Charles Babbage" to "babbage",
            second = "Analytical Engine" to "analytical-engine",
        )
        // Unrelated noise — must not produce any link with the triad.
        val noise = proposition(
            id = "p-noise",
            text = "Grace Hopper popularized the term debugging.",
            first = "Grace Hopper" to "hopper",
            second = "debugging" to "debugging",
        )

        listOf(adaEngine, babbageEngine, noise).forEach { repository.save(it) }

        // 2. Round-trip through the store rather than reusing the local list.
        val stored = repository.findAll()
        assertEquals(3, stored.size, "all seeded propositions should be retrievable")

        // 3. Discover indirect links structurally over the stored propositions.
        val links = TwoHopSemanticLinkDiscoverer().discover(stored)

        assertEquals(1, links.size, "only the ada~babbage indirect link should surface")
        val link = links.single()

        // Canonical ordering is sourceEntityId < targetEntityId: "ada" < "babbage".
        assertEquals("ada", link.sourceEntityId)
        assertEquals("babbage", link.targetEntityId)
        assertEquals(LinkKind.INFERRED, link.kind)
        assertEquals(ReviewStatus.CANDIDATE, link.reviewStatus)

        // The connecting path runs through the Analytical Engine.
        assertEquals(listOf("analytical-engine"), link.connectingEntityIds)

        // Evidence traces back to both source propositions.
        assertTrue(
            link.sourcePropositionIds.containsAll(listOf("p-ada-engine", "p-babbage-engine")),
            "link evidence must include both connecting propositions: ${link.sourcePropositionIds}",
        )

        // 4. Aggregate the same stored set into a structured report.
        val report = StructuredReportProjector().report(stored, "Knowledge Report")

        assertEquals(3, report.totalCount, "report aggregates every stored proposition")
        assertTrue(
            report.sourcePropositionIds.containsAll(listOf("p-ada-engine", "p-babbage-engine", "p-noise")),
            "report must trace back to all stored propositions",
        )
        assertTrue(report.summary().isNotBlank(), "report renders a human-readable summary")
    }
}
