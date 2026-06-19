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
package com.embabel.dice.eval

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.projection.graph.GraphProjector
import com.embabel.dice.projection.graph.GraphRelationshipPersister
import com.embabel.dice.projection.graph.ProjectedRelationship
import com.embabel.dice.projection.graph.RelationshipPersistenceResult
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.ProjectionSuccess
import com.embabel.dice.proposition.Proposition

/**
 * In-test [GraphRelationshipPersister] that records projected relationships in an in-memory list
 * with no RAG, named-entity-data repository, or Neo4j bean.
 *
 * [projectAndPersist] runs the supplied projector over the propositions and "persists" every
 * successful relationship by appending it to [persisted], returning a [RelationshipPersistenceResult]
 * whose counts reflect the projection outcome. This is enough to drive the project stage of the
 * canonical flow offline and to assert which edges were produced.
 */
class InMemoryGraphRelationshipPersister : GraphRelationshipPersister {

    private val store = mutableListOf<ProjectedRelationship>()

    /** Relationships persisted so far, in insertion order. */
    val persisted: List<ProjectedRelationship> get() = store.toList()

    override fun persist(
        results: ProjectionResults<ProjectedRelationship>,
    ): RelationshipPersistenceResult = persist(results.projected)

    override fun persist(
        relationships: List<ProjectedRelationship>,
    ): RelationshipPersistenceResult {
        relationships.forEach(::persistRelationship)
        return RelationshipPersistenceResult(persistedCount = relationships.size, failedCount = 0)
    }

    override fun persistRelationship(relationship: ProjectedRelationship) {
        store.add(relationship)
    }

    override fun projectAndPersist(
        propositions: List<Proposition>,
        graphProjector: GraphProjector,
        schema: DataDictionary,
    ): Pair<ProjectionResults<ProjectedRelationship>, RelationshipPersistenceResult> {
        val results = ProjectionResults(propositions.map { graphProjector.project(it, schema) })
        val relationships = results.results
            .filterIsInstance<ProjectionSuccess<ProjectedRelationship>>()
            .map { it.projected }
        val persistence = persist(relationships)
        return results to persistence
    }
}
