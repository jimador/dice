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
import com.embabel.agent.rag.service.NamedEntityDataRepository
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
    fun `adopts existing node when a resolved mention is present in the repository`() {
        val repo = mockk<NamedEntityDataRepository>()
        every { repo.findById("user-rod") } returns mockk<NamedEntityData>()

        val resolver = RepositoryBackedReconciler(repo)
        val decision = resolver.reconcile(
            proposition(
                listOf(
                    EntityMention("Rod", "Person", resolvedId = "user-rod", role = MentionRole.SUBJECT),
                ),
            ),
            "neo4j",
        )

        assertEquals(ReconciliationDecision.Adopt("user-rod"), decision)
        verify { repo.findById("user-rod") }
    }

    @Test
    fun `creates new when the resolved id is absent from the repository`() {
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
    fun `adopts a later live mention when an earlier resolved id is stale`() {
        val repo = mockk<NamedEntityDataRepository>()
        // First resolved mention points at a stale/ghost id; the second is live.
        every { repo.findById("ghost-rod") } returns null
        every { repo.findById("contact-tom") } returns mockk<NamedEntityData>()

        val resolver = RepositoryBackedReconciler(repo)
        val decision = resolver.reconcile(
            proposition(
                listOf(
                    EntityMention("Rod", "Person", resolvedId = "ghost-rod", role = MentionRole.SUBJECT),
                    EntityMention("Tom", "Contact", resolvedId = "contact-tom", role = MentionRole.OBJECT),
                ),
            ),
            "neo4j",
        )

        assertEquals(ReconciliationDecision.Adopt("contact-tom"), decision)
        verify { repo.findById("ghost-rod") }
        verify { repo.findById("contact-tom") }
    }
}
