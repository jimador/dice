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
package com.embabel.dice.proposition.gate

import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.revision.RevisionResult

/**
 * A policy check applied to a proposition after extraction but before it is persisted or
 * projected. A gate inspects a single proposition together with its [GateContext] and returns
 * a [GateEvaluation] expressing how the proposition should be routed.
 *
 * Gates are a standalone, consumer-invoked stage that operates on pipeline output BEFORE the
 * consumer calls `save()`. They are not embedded in the extraction pipeline itself — the
 * proposition remains the canonical source of truth and gates only route or annotate.
 *
 * Example usage:
 * ```kotlin
 * // Run the extraction pipeline as usual.
 * val results = pipeline.process(chunks, context)
 *
 * // A simple confidence gate.
 * val confidenceGate = ExtractionGate { proposition, _ ->
 *     // Use effectiveConfidence() (decay-adjusted), not the raw extraction-time confidence,
 *     // so the gate threshold stays consistent with the query layer.
 *     val decision = if (proposition.effectiveConfidence() < 0.5) {
 *         GateDecision.Reject("confidence below threshold")
 *     } else {
 *         GateDecision.Persist
 *     }
 *     GateEvaluation("ConfidenceGate", proposition, decision)
 * }
 *
 * // Evaluate each proposition before persisting, reading the trust score from metadata.
 * results.propositions.forEach { proposition ->
 *     val gateContext = GateContext(
 *         // Coerce numerically: a wrong-typed value (Float, Int, BigDecimal) would otherwise
        // read as null via `as? Double` and the gate would silently fail open.
        trustScore = (proposition.metadata["dice.trust.score"] as? Number)?.toDouble(),
 *     )
 *     val evaluation = confidenceGate.evaluate(proposition, gateContext)
 *     when (evaluation.decision) {
 *         is GateDecision.Persist -> repository.save(proposition)
 *         is GateDecision.RouteToReview -> reviewQueue.add(proposition)
 *         is GateDecision.Reject -> { /* drop */ }
 *         is GateDecision.SkipProjection -> repository.save(proposition) // persist but do not project
 *     }
 * }
 * ```
 */
fun interface ExtractionGate {

    /**
     * Evaluate a single proposition against this gate's policy.
     *
     * @param proposition the proposition to evaluate
     * @param context the surrounding gate context (trust score, revision result, source context)
     * @return an evaluation pairing this gate's name with its decision for the proposition
     */
    fun evaluate(
        proposition: Proposition,
        context: GateContext,
    ): GateEvaluation
}

/**
 * Context passed to a gate alongside the proposition under evaluation.
 *
 * @property revisionResult the revision outcome for this proposition, if it was revised against
 *   existing knowledge; null when no revision was performed
 * @property trustScore a consumer-injected trust value READ from the proposition's metadata.
 *   The gate layer never computes this score — the consumer supplies the cached value so gates
 *   can route on it without coupling to any scoring component. Callers should normalize the raw
 *   metadata value to `Double` (e.g. `(value as? Number)?.toDouble()`) because a wrong-typed
 *   value reads as missing and the trust gate then fails open.
 * @property sourceContext the analysis context the proposition was extracted under, if available
 * @property metadata free-form additional context for custom gates
 */
data class GateContext @JvmOverloads constructor(
    val revisionResult: RevisionResult? = null,
    val trustScore: Double? = null,
    val sourceContext: SourceAnalysisContext? = null,
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * The routing decision a gate reaches for a proposition. Exactly five outcomes are possible.
 */
sealed interface GateDecision {

    /** Persist the proposition normally. */
    data object Persist : GateDecision

    /** Hold the proposition for human or downstream review rather than persisting it directly. */
    data class RouteToReview(val reason: String) : GateDecision

    /** Reject the proposition outright; it should not be persisted. */
    data class Reject(val reason: String) : GateDecision

    /** Persist the proposition but exclude it from projection to downstream representations. */
    data class SkipProjection(val reason: String) : GateDecision

    /**
     * Persist the proposition, but signal that the relation it asserts should be projected as a
     * weaker predicate ([toRelation]) because its evidence did not clear the relation's floor. The
     * proposition itself stays canonical and untouched — the consumer applies the relabel when it
     * projects, so a cheap structural signal lands as the modest claim it actually supports.
     *
     * @throws IllegalArgumentException if [toRelation] or [reason] is blank.
     */
    data class Demote(val toRelation: String, val reason: String) : GateDecision {
        init {
            require(toRelation.isNotBlank()) { "toRelation must not be blank" }
            require(reason.isNotBlank()) { "reason must not be blank" }
        }
    }
}

/**
 * The outcome of evaluating a single proposition against a single gate.
 *
 * @property gateName the name of the gate that produced this evaluation
 * @property proposition the proposition that was evaluated
 * @property decision the routing decision the gate reached
 */
data class GateEvaluation(
    val gateName: String,
    val proposition: Proposition,
    val decision: GateDecision,
)

/**
 * The aggregated outcome of running one or more gates against a proposition, together with the
 * final routing decision.
 *
 * @property proposition the proposition that was gated
 * @property evaluations the per-gate evaluations that contributed to the final decision
 * @property finalDecision the decision the consumer should act on
 */
data class GatedPropositionResult(
    val proposition: Proposition,
    val evaluations: List<GateEvaluation>,
    val finalDecision: GateDecision,
) {
    /** True when the proposition should be persisted normally (a plain [GateDecision.Persist]). */
    val shouldPersist: Boolean get() = finalDecision is GateDecision.Persist

    /**
     * True when the proposition should be written to storage at all — that is, every decision
     * except [GateDecision.Reject] and [GateDecision.RouteToReview]. Use this (not [shouldPersist])
     * to guard a `save()`, since [GateDecision.Demote] and [GateDecision.SkipProjection] both keep
     * the proposition; they only change how it is projected.
     */
    val shouldSave: Boolean
        get() = finalDecision !is GateDecision.Reject && finalDecision !is GateDecision.RouteToReview

    /** True when the proposition should be routed to review. */
    val shouldReview: Boolean get() = finalDecision is GateDecision.RouteToReview

    /** True when the proposition was rejected. */
    val isRejected: Boolean get() = finalDecision is GateDecision.Reject

    /** True when the proposition should be persisted but excluded from projection. */
    val skipProjection: Boolean get() = finalDecision is GateDecision.SkipProjection

    /** True when the asserted relation should be demoted to a weaker predicate at projection time. */
    val isDemoted: Boolean get() = finalDecision is GateDecision.Demote

    /** The weaker predicate to project, when the final decision is a demotion; null otherwise. */
    val demoteTo: String? get() = (finalDecision as? GateDecision.Demote)?.toRelation
}
