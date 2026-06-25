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
package com.embabel.dice.projection.lineage

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.RelationshipDirection
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.projection.graph.ProjectedRelationship
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RepositoryBackedReconcilerTest {

    private val contextId = ContextId("test")

    private fun proposition(mentions: List<EntityMention>): Proposition =
        Proposition(
            id = "prop-1",
            contextId = contextId,
            text = "Rod knows Tom",
            mentions = mentions,
            confidence = 0.8,
        )

    @Test
    fun `creates new when only an endpoint node exists without a projected relationship`() {
        val repo = mockk<NamedEntityDataRepository>()

        val resolver = RepositoryBackedReconciler(repo)
        val decision = resolver.reconcile(
            proposition(
                listOf(
                    EntityMention("Rod", "Person", resolvedId = "user-rod", role = MentionRole.SUBJECT),
                ),
            ),
            "neo4j",
        )

        assertEquals(ReconciliationDecision.CreateNew, decision)
        verify(exactly = 0) { repo.findById(any()) }
    }

    @Test
    fun `creates new when the projected relationship source is absent from the repository`() {
        val repo = mockk<NamedEntityDataRepository>()
        every { repo.findById("ghost") } returns null

        val resolver = RepositoryBackedReconciler(repo)
        val decision = resolver.reconcile(
            proposition(
                listOf(
                    EntityMention("Ghost", "Person", resolvedId = "ghost", role = MentionRole.SUBJECT),
                ),
            ),
            "neo4j",
            ProjectedRelationship(
                sourceId = "ghost",
                targetId = "contact-tom",
                type = "KNOWS",
                confidence = 0.9,
                sourcePropositionIds = listOf("prop-1"),
            ),
        )

        assertEquals(ReconciliationDecision.CreateNew, decision)
        verify { repo.findById("ghost") }
    }

    @Test
    fun `creates new when no mention carries a resolved id`() {
        val repo = mockk<NamedEntityDataRepository>()

        val resolver = RepositoryBackedReconciler(repo)
        val decision = resolver.reconcile(
            proposition(
                listOf(
                    EntityMention("Rod", "Person", resolvedId = null, role = MentionRole.SUBJECT),
                    EntityMention("Tom", "Person", resolvedId = null, role = MentionRole.OBJECT),
                ),
            ),
            "neo4j",
        )

        assertEquals(ReconciliationDecision.CreateNew, decision)
        verify(exactly = 0) { repo.findById(any()) }
    }

    @Test
    fun `adopts an existing projected relationship by edge ref`() {
        val repo = mockk<NamedEntityDataRepository>()
        val source = mockk<NamedEntityData>()
        val target = mockk<NamedEntityData>()
        every { source.labels() } returns setOf("Person")
        every { target.id } returns "contact-tom"
        every { repo.findById("user-rod") } returns source
        every {
            repo.findRelated(
                RetrievableIdentifier("user-rod", "Person"),
                "KNOWS",
                RelationshipDirection.OUTGOING,
            )
        } returns listOf(target)

        val resolver = RepositoryBackedReconciler(repo)
        val decision = resolver.reconcile(
            proposition(
                listOf(
                    EntityMention("Rod", "Person", resolvedId = "user-rod", role = MentionRole.SUBJECT),
                    EntityMention("Tom", "Contact", resolvedId = "contact-tom", role = MentionRole.OBJECT),
                ),
            ),
            "neo4j",
            ProjectedRelationship(
                sourceId = "user-rod",
                targetId = "contact-tom",
                type = "KNOWS",
                confidence = 0.9,
                sourcePropositionIds = listOf("prop-1"),
            ),
        )

        assertEquals(ReconciliationDecision.Adopt("user-rod-[KNOWS]->contact-tom"), decision)
        verify { repo.findById("user-rod") }
        verify {
            repo.findRelated(
                RetrievableIdentifier("user-rod", "Person"),
                "KNOWS",
                RelationshipDirection.OUTGOING,
            )
        }
    }
}
