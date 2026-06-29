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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.VectorIndex as VectorIndexAnnotation
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Test
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/**
 * Guards the one invariant that keeps Proposition vector search working: the index name resolved for
 * the schema (DDL), for `findClusters`, and for the annotation-bound `loadNearest` path must all be
 * the same string. They diverged once — a blank `vector-index.name` derived `Proposition_embedding_vector`
 * on the query side but flowed through as `""` on the DDL side, so the query targeted an index the DDL
 * never created and every search silently returned empty.
 */
class VectorIndexNameResolutionTest {

    private val config = DiceStorageAutoConfiguration()

    /** Recompute the name `loadNearest` infers from the @VectorIndex annotation, the way Drivine does. */
    private fun annotationDerivedName(): String {
        val label = PropositionNode::class.findAnnotation<NodeFragment>()!!.labels.first()
        val prop = PropositionNode::class.memberProperties.single { it.name == "embedding" }
        val annotation = prop.findAnnotation<VectorIndexAnnotation>()!!
        return annotation.name.ifEmpty { "${label}_${prop.name}_vector" }
    }

    @Test
    fun `zero-config name resolves to the canonical annotation-bound name`() {
        val resolved = config.resolveVectorIndexName(DiceStoreProperties.VectorIndex())
        assertThat(resolved).isEqualTo(DrivinePropositionRepository.VECTOR_INDEX)
    }

    @Test
    fun `all three resolvers agree on the same index name`() {
        val vi = DiceStoreProperties.VectorIndex()
        val resolved = config.resolveVectorIndexName(vi)

        // DDL path: the name the schema would register.
        val ddlName = VectorIndexSpec(
            label = vi.label,
            property = vi.property,
            dimensions = 1536,
            similarity = SimilarityFunction.COSINE,
            name = resolved,
        ).effectiveName

        // findClusters path: the default the repository queries.
        val findClustersName = DrivinePropositionRepository.VECTOR_INDEX

        // loadNearest path: inferred from the annotation, not configurable.
        val loadNearestName = annotationDerivedName()

        assertThat(setOf(resolved, ddlName, findClustersName, loadNearestName)).containsExactly(
            DrivinePropositionRepository.VECTOR_INDEX,
        )
    }

    @Test
    fun `explicit name equal to the canonical name is accepted`() {
        val vi = DiceStoreProperties.VectorIndex(name = DrivinePropositionRepository.VECTOR_INDEX)
        assertThat(config.resolveVectorIndexName(vi)).isEqualTo(DrivinePropositionRepository.VECTOR_INDEX)
    }

    @Test
    fun `a blank name is rejected`() {
        assertThatThrownBy { config.resolveVectorIndexName(DiceStoreProperties.VectorIndex(name = "")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not be blank")
    }

    @Test
    fun `a whitespace name is rejected`() {
        assertThatThrownBy { config.resolveVectorIndexName(DiceStoreProperties.VectorIndex(name = "   ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not be blank")
    }

    @Test
    fun `a custom name that diverges from the canonical name is rejected`() {
        val vi = DiceStoreProperties.VectorIndex(name = "proposition_embedding_index")
        assertThatThrownBy { config.resolveVectorIndexName(vi) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must equal the derived")
    }

    @Test
    fun `a label or property that re-derives away from the canonical name is rejected`() {
        val vi = DiceStoreProperties.VectorIndex(label = "Claim")
        assertThatThrownBy { config.resolveVectorIndexName(vi) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not match the annotation-bound")
    }
}
