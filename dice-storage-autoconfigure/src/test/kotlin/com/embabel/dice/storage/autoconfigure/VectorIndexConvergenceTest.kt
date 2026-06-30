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
package com.embabel.dice.storage.autoconfigure

import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.model.PropositionNode
import org.assertj.core.api.Assertions.assertThat
import org.drivine.annotation.NodeFragment
import org.drivine.schema.SimilarityFunction
import org.junit.jupiter.api.Test
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import org.drivine.annotation.VectorIndex as VectorIndexAnnotation

/**
 * Guards the one invariant that keeps Proposition vector search working: the index the schema (DDL)
 * creates, the index `findClusters` queries, and the index the annotation-bound `loadNearest` path
 * infers must all be the same. They diverged once — a blank `vector-index.name` derived
 * `Proposition_embedding_vector` on the query side but flowed through as `""` to the DDL side, so the
 * query targeted an index the DDL never created and every search silently returned empty.
 *
 * The fix made the `@VectorIndex` annotation on `PropositionNode.embedding` the single source of
 * truth and pinned the other paths to it. These tests check the canonical constants still match that
 * annotation, so a change to one without the other can't quietly reintroduce the split.
 */
class VectorIndexConvergenceTest {

    private val config = DiceStorageAutoConfiguration()

    /** What `loadNearest` infers from the annotation: label from @NodeFragment, name derived if empty. */
    private val annotationLabel = PropositionNode::class.findAnnotation<NodeFragment>()!!.labels.first()
    private val annotationProperty = PropositionNode::class.memberProperties
        .single { it.findAnnotation<VectorIndexAnnotation>() != null }.name
    private val annotation = PropositionNode::class.memberProperties
        .single { it.name == annotationProperty }.findAnnotation<VectorIndexAnnotation>()!!
    private val annotationName = annotation.name.ifEmpty { "${annotationLabel}_${annotationProperty}_vector" }

    @Test
    fun `the canonical constants match the annotation on Proposition embedding`() {
        assertThat(DrivinePropositionRepository.VECTOR_INDEX_LABEL).isEqualTo(annotationLabel)
        assertThat(DrivinePropositionRepository.VECTOR_INDEX_PROPERTY).isEqualTo(annotationProperty)
        assertThat(DrivinePropositionRepository.VECTOR_INDEX).isEqualTo(annotationName)
    }

    @Test
    fun `the schema, findClusters, and loadNearest names all agree`() {
        val schemaName = config.propositionVectorIndexSpec(dimensions = 1536).effectiveName
        val findClustersName = DrivinePropositionRepository.VECTOR_INDEX
        val loadNearestName = annotationName

        assertThat(setOf(schemaName, findClustersName, loadNearestName))
            .containsExactly(DrivinePropositionRepository.VECTOR_INDEX)
    }

    @Test
    fun `the schema spec mirrors the annotation's label, property, and similarity`() {
        val spec = config.propositionVectorIndexSpec(dimensions = 1536)

        assertThat(spec.label).isEqualTo(annotationLabel)
        assertThat(spec.property).isEqualTo(annotationProperty)
        assertThat(spec.similarity).isEqualTo(annotation.similarity)
        assertThat(spec.similarity).isEqualTo(SimilarityFunction.COSINE)
        assertThat(spec.name).isEqualTo(DrivinePropositionRepository.VECTOR_INDEX)
    }
}
