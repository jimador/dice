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
    ): Proposition = Proposition(
        id = id,
        contextId = contextId,
        text = "$firstId relates to $secondId",
        mentions = listOf(
            EntityMention(span = firstId, type = "Entity", resolvedId = firstId, role = MentionRole.SUBJECT),
            EntityMention(span = secondId, type = "Entity", resolvedId = secondId, role = MentionRole.OBJECT),
        ),
        confidence = 0.9,
        status = PropositionStatus.ACTIVE,
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
}
