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
package com.embabel.dice.proposition.revision

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * How a new proposition relates to an existing one — determines what the reviser does with it.
 */
enum class PropositionRelation {
    /** Both propositions say the same thing — merge them. */
    IDENTICAL,

    /** Related but not identical — reinforce the existing one. */
    SIMILAR,

    /** No meaningful overlap — store the new proposition separately. */
    UNRELATED,

    /** They contradict each other — reduce the existing proposition's confidence. */
    CONTRADICTORY,

    /** The new proposition is a higher-level abstraction of existing ones — store it as new. */
    GENERALIZES,
}

/**
 * A candidate proposition from the repository, tagged with how it relates to the incoming proposition.
 */
data class ClassifiedProposition(
    val proposition: Proposition,
    val relation: PropositionRelation,
    val similarity: Double,
    val reasoning: String? = null,
)

/**
 * What the reviser decided to do with a proposition.
 */
sealed class RevisionResult {
    /** The incoming proposition said the same thing as an existing one — they were merged. */
    data class Merged(
        val original: Proposition,
        val revised: Proposition,
    ) : RevisionResult()

    /** The incoming proposition corroborated an existing one — the existing was reinforced. */
    data class Reinforced(
        val original: Proposition,
        val revised: Proposition,
    ) : RevisionResult()

    /** The incoming proposition contradicts an existing one — both are stored, and the old one's confidence is reduced. */
    data class Contradicted(
        val original: Proposition,
        val new: Proposition,
        val conflictType: ConflictType = ConflictType.Contradiction,
    ) : RevisionResult()

    /** No similar proposition was found — stored as a brand-new entry. */
    data class New(
        val proposition: Proposition,
    ) : RevisionResult()

    /** The incoming proposition is a higher-level abstraction of existing ones — stored as new alongside the propositions it generalizes. */
    data class Generalized(
        val proposition: Proposition,
        val generalizes: List<Proposition>,
    ) : RevisionResult()
}

/**
 * Compares an incoming proposition against existing ones in the repository and decides what to do
 * with it — merge, reinforce, contradict, or store as new.
 *
 * The reviser reads from the repository but never writes to it. The pipeline is responsible for
 * persisting the returned [RevisionResult].
 */
interface PropositionReviser {

    /**
     * Revise a single incoming proposition against the store.
     *
     * Reads from the repository but does not persist anything — the caller decides what to save.
     *
     * @param newProposition the freshly extracted proposition to check
     * @param repository the store to search for similar or conflicting propositions
     * @return what happened: merged, reinforced, contradicted, or new
     */
    fun revise(
        newProposition: Proposition,
        repository: PropositionRepository,
    ): RevisionResult

    /**
     * Revise a batch of propositions, returning one result per input.
     *
     * @param propositions the propositions to revise
     * @param repository the store to search
     * @return results in the same order as the input list
     */
    fun reviseAll(
        propositions: List<Proposition>,
        repository: PropositionRepository,
    ): List<RevisionResult> = propositions.map { revise(it, repository) }

    /**
     * Classify each candidate's relationship to the incoming proposition.
     *
     * @param newProposition the proposition being evaluated
     * @param candidates existing propositions retrieved from the store
     * @return each candidate tagged with its [PropositionRelation] and similarity score
     */
    fun classify(
        newProposition: Proposition,
        candidates: List<Proposition>,
    ): List<ClassifiedProposition>
}

/**
 * The LLM's classification response for a single proposition against its candidates.
 */
data class ClassificationResponse(
    @param:JsonPropertyDescription("Classification results for each candidate proposition")
    val classifications: List<ClassificationItem> = emptyList(),
)

data class ClassificationItem(
    @param:JsonPropertyDescription("ID of the proposition being classified")
    val propositionId: String,
    @param:JsonPropertyDescription("Relation type: IDENTICAL, SIMILAR, CONTRADICTORY, UNRELATED, or GENERALIZES")
    val relation: String,
    @param:JsonPropertyDescription("Similarity score 0.0-1.0")
    val similarity: Double,
    @param:JsonPropertyDescription("Brief reasoning for this classification")
    val reasoning: String,
)

/**
 * Response structure for batch classification of multiple propositions at once.
 */
data class BatchClassificationResponse(
    @param:JsonPropertyDescription("Classification results for each new proposition")
    val propositions: List<PropositionClassifications> = emptyList(),
)

data class PropositionClassifications(
    @param:JsonPropertyDescription("Zero-based index of the new proposition in the batch")
    val propositionIndex: Int,
    @param:JsonPropertyDescription("Classifications of each candidate for this proposition")
    val classifications: List<ClassificationItem> = emptyList(),
)
