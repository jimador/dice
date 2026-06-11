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
package com.embabel.dice.operations.consolidation

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsolidationPassResultTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String = "p"): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = 0.8,
            decay = 0.1,
        )

    @Test
    fun `Changed carries passName and propositionsToSave with empty defaults`() {
        val p = proposition()
        val changed = ConsolidationPassResult.Changed("abstraction", propositionsToSave = listOf(p))

        assertEquals("abstraction", changed.passName)
        assertEquals(listOf(p), changed.propositionsToSave)
        assertTrue(changed.propositionsToDelete.isEmpty())
        assertEquals(0, changed.skipped)
        assertEquals("", changed.summary)
    }

    @Test
    fun `NoOp carries passName and reason`() {
        val noOp = ConsolidationPassResult.NoOp("decay-sweep", "below threshold")

        assertEquals("decay-sweep", noOp.passName)
        assertEquals("below threshold", noOp.reason)
    }

    @Test
    fun `NoOp reason defaults to empty`() {
        assertEquals("", ConsolidationPassResult.NoOp("decay-sweep").reason)
    }

    @Test
    fun `Failed carries passName and the originating cause`() {
        val ex = IllegalStateException("boom")
        val failed = ConsolidationPassResult.Failed("contradiction-resolution", ex)

        assertEquals("contradiction-resolution", failed.passName)
        assertSame(ex, failed.cause)
    }

    @Test
    fun `every result case exposes passName via the sealed parent`() {
        val results: List<ConsolidationPassResult> = listOf(
            ConsolidationPassResult.Changed("a"),
            ConsolidationPassResult.NoOp("b"),
            ConsolidationPassResult.Failed("c", RuntimeException()),
        )

        assertEquals(listOf("a", "b", "c"), results.map { it.passName })
    }

    @Test
    fun `a pass runs over the given snapshot without any repository`() {
        val snapshot = listOf(proposition("one"), proposition("two"))
        val pass = object : ConsolidationPass {
            override val name = "echo"
            override fun run(
                contextId: ContextId,
                propositions: List<Proposition>,
            ): ConsolidationPassResult =
                ConsolidationPassResult.Changed(name, propositionsToSave = propositions)
        }

        val result = pass.run(contextId, snapshot)

        assertEquals("echo", pass.name)
        assertTrue(result is ConsolidationPassResult.Changed)
        assertEquals(snapshot, (result as ConsolidationPassResult.Changed).propositionsToSave)
    }
}
