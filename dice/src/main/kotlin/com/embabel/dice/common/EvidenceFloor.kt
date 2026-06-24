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
package com.embabel.dice.common

import com.embabel.dice.spi.AuthorityTier

/**
 * The minimum evidence a proposition must carry before a relation may be asserted at full strength.
 *
 * This is the knob that stops a cheap structural signal — two people sharing an email domain, a
 * single calendar co-attendance — from being promoted to a strong semantic claim like "works for"
 * or "collaborates with". A relation can declare a floor on two independent dimensions, and an
 * assertion has to clear both:
 *
 * - [minConfidence]: the proposition's decay-adjusted confidence must reach this value.
 * - [minAuthority]: the proposition's source must be at least this authoritative (see [AuthorityTier],
 *   where a *lower* ordinal means *more* authoritative).
 *
 * Either dimension may be left null, in which case it is simply not checked; a floor with both null
 * never blocks anything. When an assertion falls short, [demoteTo] names a weaker predicate to fall
 * back to (for example "works for" → "affiliated with"). If [demoteTo] is null the assertion is held
 * for review instead.
 *
 * DICE only carries and checks the floor — it never decides what the threshold values should be or
 * what "weaker" means for a given predicate. Those are the consumer's to declare on each [Relation].
 *
 * @property minConfidence Lowest decay-adjusted confidence that clears the floor, in `0.0..1.0`;
 *   null to skip the confidence check.
 * @property minAuthority Weakest source authority that clears the floor; null to skip the authority
 *   check.
 * @property demoteTo Predicate to fall back to when the floor is not met; null to hold for review
 *   instead of demoting.
 * @throws IllegalArgumentException if [minConfidence] is outside `0.0..1.0`, or if [demoteTo] is blank.
 */
data class EvidenceFloor @JvmOverloads constructor(
    val minConfidence: Double? = null,
    val minAuthority: AuthorityTier? = null,
    val demoteTo: String? = null,
) {

    init {
        require(minConfidence == null || minConfidence in 0.0..1.0) {
            "minConfidence must be in 0.0..1.0, was $minConfidence"
        }
        require(demoteTo == null || demoteTo.isNotBlank()) { "demoteTo must not be blank" }
    }

    /**
     * Does an assertion with this [confidence] and source [authority] clear the floor?
     *
     * A null bound on either dimension passes that dimension. Authority clears when it is at least
     * as strong as [minAuthority], i.e. its ordinal is less than or equal to the floor's.
     */
    fun isSatisfiedBy(confidence: Double, authority: AuthorityTier): Boolean {
        val confidenceOk = minConfidence == null || confidence >= minConfidence
        val authorityOk = minAuthority == null || authority.ordinal <= minAuthority.ordinal
        return confidenceOk && authorityOk
    }

    companion object {

        /** A floor on confidence alone. */
        @JvmStatic
        @JvmOverloads
        fun ofConfidence(minConfidence: Double, demoteTo: String? = null): EvidenceFloor =
            EvidenceFloor(minConfidence = minConfidence, demoteTo = demoteTo)

        /** A floor on source authority alone. */
        @JvmStatic
        @JvmOverloads
        fun ofAuthority(minAuthority: AuthorityTier, demoteTo: String? = null): EvidenceFloor =
            EvidenceFloor(minAuthority = minAuthority, demoteTo = demoteTo)
    }
}
