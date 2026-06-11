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

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.AuthorityTier
import com.embabel.dice.common.EvidenceFloor
import com.embabel.dice.common.FixedAuthorityResolver
import com.embabel.dice.common.Relation
import com.embabel.dice.common.Relations
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class EvidenceFloorGateTest {

    private val contextId = ContextId("test-context")

    private fun proposition(
        text: String = "Alice works for Acme",
        confidence: Double = 0.9,
    ): Proposition = Proposition(
        contextId = contextId,
        text = text,
        mentions = emptyList(),
        confidence = confidence,
    )

    /** A "works for" relation that demands strong confidence and a named source, demoting to "affiliated with". */
    private fun worksForWithFloor(demoteTo: String? = "affiliated with"): Relations =
        Relations.of(
            Relation.semanticBetween("works for", "is employed by", "Person", "Organization")
                .withEvidenceFloor(
                    EvidenceFloor(minConfidence = 0.7, minAuthority = AuthorityTier.SECONDARY, demoteTo = demoteTo),
                ),
        )

    private fun gate(relations: Relations, authority: AuthorityTier) =
        EvidenceFloorGate(relations, FixedAuthorityResolver(authority))

    @Test
    fun `persists when confidence and authority clear the floor`() {
        val decision = gate(worksForWithFloor(), AuthorityTier.PRIMARY)
            .evaluate(proposition(confidence = 0.9), GateContext()).decision
        assertEquals(GateDecision.Persist, decision)
    }

    @Test
    fun `demotes to the weaker predicate when confidence falls short`() {
        val decision = gate(worksForWithFloor(), AuthorityTier.PRIMARY)
            .evaluate(proposition(confidence = 0.5), GateContext()).decision
        val demote = assertInstanceOf(GateDecision.Demote::class.java, decision)
        assertEquals("affiliated with", demote.toRelation)
    }

    @Test
    fun `demotes when source authority is too weak even with high confidence`() {
        // A bare email domain resolves to DERIVED authority — below the SECONDARY floor.
        val decision = gate(worksForWithFloor(), AuthorityTier.DERIVED)
            .evaluate(proposition(confidence = 0.95), GateContext()).decision
        assertInstanceOf(GateDecision.Demote::class.java, decision)
    }

    @Test
    fun `holds for review when the floor is unmet and no demote target is declared`() {
        val decision = gate(worksForWithFloor(demoteTo = null), AuthorityTier.DERIVED)
            .evaluate(proposition(confidence = 0.5), GateContext()).decision
        assertInstanceOf(GateDecision.RouteToReview::class.java, decision)
    }

    @Test
    fun `fails open when the proposition matches no declared relation`() {
        val decision = gate(worksForWithFloor(), AuthorityTier.DERIVED)
            .evaluate(proposition(text = "Bob likes pizza", confidence = 0.1), GateContext()).decision
        assertEquals(GateDecision.Persist, decision)
    }

    @Test
    fun `fails open when the matched relation declares no floor`() {
        val noFloor = Relations.of(Relation.semanticBetween("works for", "is employed by", "Person", "Organization"))
        val decision = gate(noFloor, AuthorityTier.DERIVED)
            .evaluate(proposition(confidence = 0.1), GateContext()).decision
        assertEquals(GateDecision.Persist, decision)
    }

    @Test
    fun `persists when confidence is exactly at the floor boundary`() {
        // confidence == minConfidence (0.7) must pass — isSatisfiedBy uses >=.
        val decision = gate(worksForWithFloor(), AuthorityTier.SECONDARY)
            .evaluate(proposition(confidence = 0.7), GateContext()).decision
        assertEquals(GateDecision.Persist, decision)
    }

    @Test
    fun `demotes when confidence is just below the floor boundary`() {
        // confidence just below minConfidence (0.7) must demote.
        val decision = gate(worksForWithFloor(), AuthorityTier.SECONDARY)
            .evaluate(proposition(confidence = 0.6999), GateContext()).decision
        assertInstanceOf(GateDecision.Demote::class.java, decision)
    }

    @Test
    fun `persists when authority is exactly at the floor boundary`() {
        // authority == minAuthority (SECONDARY) must pass — isSatisfiedBy uses <=  on ordinal.
        val decision = gate(worksForWithFloor(), AuthorityTier.SECONDARY)
            .evaluate(proposition(confidence = 0.9), GateContext()).decision
        assertEquals(GateDecision.Persist, decision)
    }
}
