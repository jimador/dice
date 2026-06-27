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
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticLinkDiscovererTest {

    private val contextId = ContextId("test")

    private fun proposition(
        id: String,
        firstId: String,
        secondId: String,
    ): Proposition = proposition(id, firstId, secondId, status = PropositionStatus.ACTIVE)

    private fun proposition(
        id: String,
        vararg entityIds: String,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ): Proposition = Proposition(
        id = id,
        contextId = contextId,
        text = entityIds.joinToString(" relates to "),
        mentions = entityIds.map { EntityMention(span = it, type = "Entity", resolvedId = it, role = MentionRole.SUBJECT) },
        confidence = 0.9,
        status = status,
    )

    @Test
    fun `surfaces a two-hop indirect link with connecting entity and evidence`() {
        // A-X and X-B are directly co-mentioned; A and B never are.
        val prop1 = proposition("prop1", "A", "X")
        val prop2 = proposition("prop2", "X", "B")

        val links = TwoHopSemanticLinkDiscoverer().discover(listOf(prop1, prop2))

        assertEquals(1, links.size, "expected exactly one inferred link")
        val link = links.single()
        assertEquals(LinkKind.INFERRED, link.kind)
        // Canonical order: A < B.
        assertEquals("A", link.sourceEntityId)
        assertEquals("B", link.targetEntityId)
        assertTrue(link.connectingEntityIds.contains("X"), "connecting path must include X")
        assertTrue(link.sourcePropositionIds.contains("prop1"), "evidence must include prop1")
        assertTrue(link.sourcePropositionIds.contains("prop2"), "evidence must include prop2")
    }

    @Test
    fun `directly co-mentioned pair yields no inferred link`() {
        // A and B are directly co-mentioned, so no indirect link should be produced.
        val direct = proposition("direct", "A", "B")

        val links = TwoHopSemanticLinkDiscoverer().discover(listOf(direct))

        assertTrue(links.isEmpty(), "directly co-mentioned pairs must not produce inferred links")
    }

    @Test
    fun `multiple intermediaries are merged into one link with all connecting entities`() {
        // A co-mentions X and Y in the same proposition (making X-Y a direct pair),
        // then X-B and Y-B each bridge to B.  A and B share two intermediaries, but
        // no new indirect pair is created between X and Y since they are directly
        // co-mentioned — so exactly one link (A-B) should surface.
        val prop1 = proposition("prop1", "A", "X", "Y")   // direct pairs: A-X, A-Y, X-Y
        val prop2 = proposition("prop2", "X", "B")
        val prop3 = proposition("prop3", "Y", "B")

        val links = TwoHopSemanticLinkDiscoverer().discover(listOf(prop1, prop2, prop3))

        assertEquals(1, links.size, "both intermediaries must merge into one link")
        val link = links.single()
        assertEquals("A", link.sourceEntityId)
        assertEquals("B", link.targetEntityId)
        assertEquals(listOf("X", "Y"), link.connectingEntityIds.sorted(), "connecting entities must include X and Y")
        assertTrue(link.sourcePropositionIds.containsAll(listOf("prop2", "prop3")),
            "evidence must include the propositions backing the X-B and Y-B edges")
    }

    @Test
    fun `non-ACTIVE propositions are excluded from link discovery`() {
        // The only path A→X→B has a SUPERSEDED hop; no link should surface.
        val prop1 = proposition("prop1", "A", "X", status = PropositionStatus.ACTIVE)
        val prop2 = proposition("prop2", "X", "B", status = PropositionStatus.SUPERSEDED)

        val links = TwoHopSemanticLinkDiscoverer().discover(listOf(prop1, prop2))

        assertTrue(links.isEmpty(), "a SUPERSEDED proposition must not participate in discovery")
    }

    @Test
    fun `result is ordered by source entity id then target entity id`() {
        // Two independent indirect links; verify they surface in lexicographic order.
        val prop1 = proposition("prop1", "A", "M")
        val prop2 = proposition("prop2", "M", "B")
        val prop3 = proposition("prop3", "C", "N")
        val prop4 = proposition("prop4", "N", "D")

        val links = TwoHopSemanticLinkDiscoverer().discover(listOf(prop1, prop2, prop3, prop4))

        assertEquals(2, links.size)
        assertEquals("A", links[0].sourceEntityId)
        assertEquals("B", links[0].targetEntityId)
        assertEquals("C", links[1].sourceEntityId)
        assertEquals("D", links[1].targetEntityId)
    }

    @Test
    fun `direct co-mention suppresses the inferred two-hop path between the same pair`() {
        // A and B are directly mentioned together; the path A→X→B must not generate a link.
        val direct = proposition("prop1", "A", "B")
        val hop1 = proposition("prop2", "A", "X")
        val hop2 = proposition("prop3", "X", "B")

        val links = TwoHopSemanticLinkDiscoverer().discover(listOf(direct, hop1, hop2))

        assertTrue(links.isEmpty(), "direct co-mention must suppress the inferred indirect link")
    }

    @Test
    fun `empty input yields empty result`() {
        val links = TwoHopSemanticLinkDiscoverer().discover(emptyList())

        assertTrue(links.isEmpty(), "empty proposition list must produce no links")
    }

    @Test
    fun `no shared intermediary yields empty result`() {
        // A-X and Y-B do not share any bridging entity.
        val prop1 = proposition("prop1", "A", "X")
        val prop2 = proposition("prop2", "Y", "B")

        val links = TwoHopSemanticLinkDiscoverer().discover(listOf(prop1, prop2))

        assertTrue(links.isEmpty(), "unconnected entity clusters must not produce any link")
    }
}
