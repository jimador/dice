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

import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.operations.PropositionGroup
import com.embabel.dice.proposition.Projection
import com.embabel.dice.proposition.Proposition

/**
 * Produces a human-readable rationale explaining why a proposition (or a group of
 * related propositions) is believed and how the supporting evidence connects.
 *
 * Unlike [ReportProjector], rationale generation is inherently interpretive and is
 * expected to be LLM-backed — see [LlmRationaleProjector].
 */
interface RationaleProjector {

    /**
     * Explain a single proposition.
     *
     * @param proposition The proposition to explain
     * @return A [RationaleArtifact] grounded in the proposition
     */
    fun rationale(proposition: Proposition): RationaleArtifact

    /**
     * Explain a group of related propositions, describing how they connect.
     *
     * @param group The labeled group of propositions to explain
     * @return A [RationaleArtifact] grounded in every group member
     */
    fun rationale(group: PropositionGroup): RationaleArtifact
}

/**
 * A human-readable rationale derived from one or more propositions.
 *
 * Implements [Projection] so the prose traces back to its supporting propositions
 * via [sourcePropositionIds].
 *
 * @property text The generated human-readable rationale prose
 * @property sourcePropositionIds Ids of the propositions this rationale explains
 * @property confidence Confidence in the rationale (0.0-1.0)
 */
data class RationaleArtifact @JvmOverloads constructor(
    val text: String,
    override val sourcePropositionIds: List<String>,
    override val confidence: ZeroToOne = 0.5,
) : Projection {

    /** Rationale prose is regenerated on demand and does not decay. */
    override val decay: ZeroToOne = 0.0
}
