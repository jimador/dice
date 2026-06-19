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
import com.embabel.dice.proposition.Projection

/**
 * How two entities in a [SemanticLink] came to be connected.
 */
enum class LinkKind {

    /** A and B are directly co-mentioned in at least one proposition. */
    EXPLICIT,

    /** A and B are connected only through one or more intermediary entities. */
    INFERRED,

    /** A connecting path exists, but the supporting evidence is weak or conflicting. */
    AMBIGUOUS
}

/**
 * The human-review lifecycle of a discovered [SemanticLink].
 */
enum class ReviewStatus {

    /** Freshly discovered; awaiting human review. */
    CANDIDATE,

    /** A reviewer confirmed the link is meaningful. */
    ACCEPTED,

    /** A reviewer dismissed the link as spurious or uninteresting. */
    REJECTED,

    /** The link's supporting propositions have aged out or decayed below relevance. */
    STALE,

    /** A newer link (or direct evidence) replaced this one. */
    SUPERSEDED
}

/**
 * A reviewable indirect link between two entities, discovered structurally from
 * the propositions that ground it.
 *
 * A [SemanticLink] is a [Projection]: it derives from one or more propositions and
 * traces back to them via [sourcePropositionIds]. It models the *existence* of a
 * connection and the path that produced it — it deliberately carries no surprise
 * score, rubric, or ranking signal. Ranking and surprise scoring are a separate,
 * later concern; [confidence] here is plain evidence confidence only.
 *
 * @property sourceEntityId The first endpoint of the link (A).
 * @property targetEntityId The second endpoint of the link (B).
 * @property connectingEntityIds The intermediary entities forming the path from A to
 *   B (e.g. `[X]` for the two-hop path A->X, X->B). Empty for a direct/[EXPLICIT] link.
 * @property kind How the link was established (see [LinkKind]).
 * @property sourcePropositionIds The ids of the propositions that evidence this link.
 *   This is the [Projection] grounding.
 * @property reviewStatus Where this link sits in the human-review lifecycle.
 * @property confidence Plain evidence confidence (0.0-1.0). NOT a surprise or ranking
 *   score — it reflects only how well the supporting propositions ground the link.
 * @property rationale Optional human-readable prose explaining the link, filled in
 *   later by a rationale projector. Null until generated.
 */
data class SemanticLink @JvmOverloads constructor(
    val sourceEntityId: String,
    val targetEntityId: String,
    val connectingEntityIds: List<String>,
    val kind: LinkKind = LinkKind.INFERRED,
    override val sourcePropositionIds: List<String>,
    val reviewStatus: ReviewStatus = ReviewStatus.CANDIDATE,
    override val confidence: ZeroToOne = 0.5,
    val rationale: String? = null,
) : Projection {

    override val decay: ZeroToOne = 0.0

    /** Return a copy with the given review status. */
    fun withReviewStatus(status: ReviewStatus): SemanticLink = copy(reviewStatus = status)

    /** Return a copy with the given human-readable rationale. */
    fun withRationale(text: String): SemanticLink = copy(rationale = text)

    /** Return a copy with the given evidence confidence. */
    fun withConfidence(c: ZeroToOne): SemanticLink = copy(confidence = c)
}
