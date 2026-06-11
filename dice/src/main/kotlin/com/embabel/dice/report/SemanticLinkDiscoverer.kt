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

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus

/**
 * Discovers indirect [SemanticLink]s between entities from a set of propositions.
 *
 * Implementations are expected to be deterministic and to operate purely over the
 * propositions they are given — no LLM, vector store, or graph database is involved.
 */
interface SemanticLinkDiscoverer {

    /**
     * Discover indirect links among the entities referenced by [propositions].
     *
     * @param propositions The propositions to analyse. Only ACTIVE propositions
     *   participate in discovery; others are ignored.
     * @return The discovered links, in a deterministic order.
     */
    fun discover(propositions: List<Proposition>): List<SemanticLink>
}

/**
 * A deterministic, structural [SemanticLinkDiscoverer] that surfaces two-hop
 * indirect links.
 *
 * Two entities A and B are linked when they are never directly co-mentioned but
 * both are directly co-mentioned with some shared intermediary entity X (so A-X
 * and X-B are direct edges). The link records X as a connecting entity and the
 * propositions backing the A-X and X-B edges as evidence.
 *
 * Discovery is fully deterministic: it traverses only the resolved entity ids
 * ([EntityMention.resolvedId][com.embabel.dice.proposition.EntityMention.resolvedId])
 * of ACTIVE propositions and uses no LLM, vector store, or Neo4j.
 *
 * **Dedupe and ordering.** Each unordered entity pair is emitted at most once with
 * `sourceEntityId < targetEntityId` lexicographically (mirroring the canonical
 * ordering convention used elsewhere in the codebase for cluster dedupe). When
 * multiple intermediaries connect the same pair, their ids are *merged* into a
 * single link's [SemanticLink.connectingEntityIds] (sorted) rather than emitting
 * one link per intermediary. The result list is sorted by source id, then target
 * id, then the connecting-id list.
 *
 * The path length is fixed at two hops (a single shared intermediary); multi-hop
 * discovery is intentionally out of scope for this implementation.
 */
class TwoHopSemanticLinkDiscoverer : SemanticLinkDiscoverer {

    override fun discover(propositions: List<Proposition>): List<SemanticLink> {
        val active = propositions.filter { it.status == PropositionStatus.ACTIVE }

        // Direct co-mention edges keyed by canonical unordered pair, with the set
        // of evidence proposition ids; plus a per-entity neighbour set.
        val edgeEvidence = LinkedHashMap<Pair<String, String>, MutableSet<String>>()
        val neighbours = LinkedHashMap<String, MutableSet<String>>()

        for (prop in active) {
            val ids = prop.mentions.mapNotNull { it.resolvedId }.distinct()
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val (a, b) = canonical(ids[i], ids[j])
                    edgeEvidence.getOrPut(a to b) { linkedSetOf() }.add(prop.id)
                    neighbours.getOrPut(a) { linkedSetOf() }.add(b)
                    neighbours.getOrPut(b) { linkedSetOf() }.add(a)
                }
            }
        }

        val directPairs = edgeEvidence.keys

        // For each candidate (A,B) not directly connected, find shared intermediaries.
        val links = LinkedHashMap<Pair<String, String>, MutableSet<String>>()
        val evidenceByPair = LinkedHashMap<Pair<String, String>, MutableSet<String>>()

        val entities = neighbours.keys.toList()
        for (i in entities.indices) {
            for (j in i + 1 until entities.size) {
                val (a, b) = canonical(entities[i], entities[j])
                if ((a to b) in directPairs) continue
                val shared = neighbours[a].orEmpty().intersect(neighbours[b].orEmpty())
                if (shared.isEmpty()) continue
                val connecting = links.getOrPut(a to b) { sortedSetOf() }
                val evidence = evidenceByPair.getOrPut(a to b) { linkedSetOf() }
                for (x in shared) {
                    connecting.add(x)
                    evidence += edgeEvidence[canonical(a, x)].orEmpty()
                    evidence += edgeEvidence[canonical(x, b)].orEmpty()
                }
            }
        }

        return links.entries
            .map { (pair, connecting) ->
                SemanticLink(
                    sourceEntityId = pair.first,
                    targetEntityId = pair.second,
                    connectingEntityIds = connecting.toList(),
                    kind = LinkKind.INFERRED,
                    sourcePropositionIds = evidenceByPair[pair].orEmpty().toList(),
                    confidence = 0.5,
                )
            }
            .sortedWith(
                compareBy(
                    { it.sourceEntityId },
                    { it.targetEntityId },
                    { it.connectingEntityIds.joinToString(",") },
                ),
            )
    }

    private fun canonical(x: String, y: String): Pair<String, String> =
        if (x <= y) x to y else y to x
}
