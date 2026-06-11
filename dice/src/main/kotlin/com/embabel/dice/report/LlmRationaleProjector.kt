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
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.operations.PropositionGroup
import com.embabel.dice.proposition.Proposition
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory

/**
 * LLM-backed [RationaleProjector] that turns a proposition (or group) into
 * human-readable rationale prose grounded in the source propositions.
 *
 * **Security note (indirect prompt injection).** Proposition text and group labels
 * are embedded in the LLM prompt. Since this content typically comes from ingested
 * source documents, it must be treated as untrusted: a crafted document could embed
 * instructions the rationale model may follow. The template wraps proposition text
 * in a labelled data block as a mitigation, but that is not a guarantee. Callers
 * are responsible for sanitizing ingested content upstream and for not granting the
 * rationale output undue authority.
 *
 * Example usage:
 * ```kotlin
 * val projector = LlmRationaleProjector
 *     .withLlm(llmOptions)
 *     .withAi(ai)
 *
 * val artifact = projector.rationale(group)
 * println(artifact.text)
 * ```
 */
data class LlmRationaleProjector(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
) : RationaleProjector {

    companion object {

        @JvmStatic
        fun withLlm(llm: LlmOptions): Builder = Builder(llm)

        class Builder(private val llmOptions: LlmOptions) {

            fun withAi(ai: Ai): LlmRationaleProjector =
                LlmRationaleProjector(
                    llmOptions = llmOptions,
                    ai = ai,
                )
        }
    }

    private val logger = LoggerFactory.getLogger(LlmRationaleProjector::class.java)

    override fun rationale(proposition: Proposition): RationaleArtifact =
        explain(listOf(proposition), groupLabel = "")

    override fun rationale(group: PropositionGroup): RationaleArtifact =
        explain(group.propositions, groupLabel = group.label)

    private fun explain(propositions: List<Proposition>, groupLabel: String): RationaleArtifact {
        val propositionData = propositions.mapIndexed { index, p ->
            mapOf(
                "index" to index,
                "text" to p.text,
                "confidence" to p.confidence,
                "importance" to p.importance,
            )
        }

        val response = ai
            .withLlm(llmOptions)
            .withId("explain-rationale")
            .creating(RationaleResponse::class.java)
            .fromTemplate(
                "dice/explain_rationale",
                mapOf(
                    "propositions" to propositionData,
                    "groupLabel" to groupLabel,
                )
            )

        logger.info(
            "Generated rationale from {} proposition(s){}",
            propositions.size,
            if (groupLabel.isNotBlank()) " about '$groupLabel'" else ""
        )

        return RationaleArtifact(
            text = response.rationale,
            sourcePropositionIds = propositions.map { it.id },
            confidence = response.confidence.coerceIn(0.0, 1.0),
        )
    }
}

/**
 * Structured response for rationale generation.
 */
data class RationaleResponse(
    @param:JsonPropertyDescription("Clear, human-readable prose explaining why the propositions are believed and how they connect")
    val rationale: String,

    @param:JsonPropertyDescription("Confidence in this rationale (0.0-1.0)")
    val confidence: ZeroToOne = 0.7,
)
