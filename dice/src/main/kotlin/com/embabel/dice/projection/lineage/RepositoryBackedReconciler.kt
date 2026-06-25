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
package com.embabel.dice.projection.lineage

import com.embabel.agent.rag.model.RelationshipDirection
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.projection.graph.ProjectedRelationship
import com.embabel.dice.proposition.Projection
import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory

/**
 * [Reconciler] that adopts an existing graph relationship when the backing
 * [NamedEntityDataRepository] can prove the exact projected edge already exists.
 *
 * Endpoint nodes are deliberately not adopted as the projection artifact: graph projection creates or
 * reuses a relationship edge, while node reuse is handled by the persister's id-keyed saves/MERGE.
 * Without a concrete projected edge this reconciler returns [ReconciliationDecision.CreateNew].
 *
 * @property repository The entity store consulted for existing nodes
 */
class RepositoryBackedReconciler(
    private val repository: NamedEntityDataRepository,
) : Reconciler {

    private val logger = LoggerFactory.getLogger(RepositoryBackedReconciler::class.java)

    override fun reconcile(proposition: Proposition, target: String): ReconciliationDecision {
        logger.debug(
            "No projected artifact supplied for proposition {} -> {}; will create new",
            proposition.id.take(8),
            target,
        )
        return ReconciliationDecision.CreateNew
    }

    override fun reconcile(proposition: Proposition, target: String, projected: Projection): ReconciliationDecision {
        val relationship = projected as? ProjectedRelationship ?: return reconcile(proposition, target)
        val sourceEntity = repository.findById(relationship.sourceId) ?: run {
            logger.debug(
                "No source node {} found for projected edge {} from proposition {}; will create new",
                relationship.sourceId.take(8),
                relationship.edgeRef,
                proposition.id.take(8),
            )
            return ReconciliationDecision.CreateNew
        }
        val sourceType = sourceEntity.labels().firstOrNull() ?: "Entity"
        val existing = runCatching {
            repository.findRelated(
                RetrievableIdentifier(relationship.sourceId, sourceType),
                relationship.type,
                RelationshipDirection.OUTGOING,
            ).any { it.id == relationship.targetId }
        }.getOrElse {
            logger.debug(
                "Could not inspect existing relationship {} for proposition {}: {}",
                relationship.edgeRef,
                proposition.id.take(8),
                it.message,
            )
            false
        }

        if (existing) {
            logger.debug(
                "Adopt existing relationship {} for proposition {} -> {}",
                relationship.edgeRef,
                proposition.id.take(8),
                target,
            )
            return ReconciliationDecision.Adopt(relationship.edgeRef)
        }
        logger.debug("No existing relationship {} for proposition {} -> {}; will create new", relationship.edgeRef, proposition.id.take(8), target)
        return ReconciliationDecision.CreateNew
    }
}
