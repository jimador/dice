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

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.operations.PropositionGroup
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmRationaleProjectorTest {

    private val contextId = ContextId("test")

    private fun proposition(id: String, text: String): Proposition = Proposition(
        id = id,
        contextId = contextId,
        text = text,
        mentions = emptyList(),
        confidence = 0.9,
    )

    private fun mockAi(response: RationaleResponse): Ai {
        val mockAi = mockk<Ai>()
        val mockPromptRunner = mockk<PromptRunner>()
        val mockCreating = mockk<PromptRunner.Creating<RationaleResponse>>()

        every { mockAi.withLlm(any<LlmOptions>()) } returns mockPromptRunner
        every { mockPromptRunner.withId(any()) } returns mockPromptRunner
        every { mockPromptRunner.creating(RationaleResponse::class.java) } returns mockCreating
        every { mockCreating.fromTemplate(any(), any()) } returns response

        return mockAi
    }

    @Test
    fun `produces rationale artifact grounded in source propositions`() {
        val response = RationaleResponse("Because A relates to B via X", 0.8)
        val projector = LlmRationaleProjector.withLlm(LlmOptions()).withAi(mockAi(response))

        val prop = proposition("p1", "A relates to B")
        val artifact = projector.rationale(prop)

        assertEquals("Because A relates to B via X", artifact.text)
        assertTrue(artifact.sourcePropositionIds.contains("p1"))
        assertEquals(0.8, artifact.confidence)
    }

    @Test
    fun `group rationale grounds in every member id`() {
        val response = RationaleResponse("They form a coherent picture", 0.7)
        val projector = LlmRationaleProjector.withLlm(LlmOptions()).withAi(mockAi(response))

        val group = PropositionGroup.of(
            "Topic",
            proposition("p1", "A relates to B"),
            proposition("p2", "B relates to C"),
        )
        val artifact = projector.rationale(group)

        assertEquals("They form a coherent picture", artifact.text)
        assertTrue(artifact.sourcePropositionIds.containsAll(listOf("p1", "p2")))
    }
}
