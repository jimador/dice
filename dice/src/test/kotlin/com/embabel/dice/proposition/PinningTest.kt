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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Store-level pinning: the pin/unpin/findPinned helpers and the `pinned` query filter. */
class PinningTest {

    private val ctx = ContextId("ctx")

    private fun prop(id: String, pinned: Boolean = false): Proposition =
        Proposition(id = id, contextId = ctx, text = id, mentions = emptyList(), confidence = 0.8, pinned = pinned)

    @Test
    fun `pin and unpin toggle the flag and persist it`() {
        val repo = InMemoryPropositionRepository()
        repo.save(prop("p1"))

        val pinned = repo.pin("p1")
        assertNotNull(pinned)
        assertTrue(pinned!!.pinned)
        assertTrue(repo.findById("p1")!!.pinned)

        val unpinned = repo.unpin("p1")
        assertFalse(unpinned!!.pinned)
        assertFalse(repo.findById("p1")!!.pinned)
    }

    @Test
    fun `pin returns null for a missing id`() {
        assertNull(InMemoryPropositionRepository().pin("nope"))
    }

    @Test
    fun `findPinned returns only the pinned propositions in the context`() {
        val repo = InMemoryPropositionRepository()
        repo.saveAll(listOf(prop("p1", pinned = true), prop("p2"), prop("p3", pinned = true)))

        assertEquals(setOf("p1", "p3"), repo.findPinned(ctx).map { it.id }.toSet())
    }

    @Test
    fun `the pinned query filter selects pinned or unpinned`() {
        val repo = InMemoryPropositionRepository()
        repo.saveAll(listOf(prop("p1", pinned = true), prop("p2")))

        assertEquals(setOf("p1"), repo.query(PropositionQuery.forContextId(ctx).withPinned(true)).map { it.id }.toSet())
        assertEquals(setOf("p2"), repo.query(PropositionQuery.forContextId(ctx).withPinned(false)).map { it.id }.toSet())
    }
}
