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

import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.proposition.Proposition

/**
 * [Reconciler] that adopts an existing target node when a proposition's
 * mention already resolves to one in the backing [NamedEntityDataRepository].
 *
 * Unlike [AlwaysCreateReconciler], this consults the repository: it walks
 * every mention carrying a non-null resolved id and, as soon as
 * [NamedEntityDataRepository.findById] returns a node for one of those ids,
 * returns [ReconciliationDecision.Adopt] with that id. Walking (rather than only
 * checking the first resolved id) ensures that a stale/ghost id on an earlier
 * mention does not mask a live, adoptable node referenced by a later mention —
 * which would otherwise mint a duplicate node. This lets projection reuse a
 * pre-existing node (no duplicate) rather than minting a new one. When no
 * resolved mention maps to an existing node, it falls back to
 * [ReconciliationDecision.CreateNew].
 *
 * The lookup is intentionally narrow (exact id only). Name-based and fuzzy
 * matching against the mention span/type are a deliberate future follow-up, so
 * reconciliation stays deterministic for the no-duplicate-node guarantee.
 *
 * @property repository The entity store consulted for existing nodes
 */
class RepositoryBackedReconciler(
    private val repository: NamedEntityDataRepository,
) : Reconciler {

    override fun reconcile(proposition: Proposition, target: String): ReconciliationDecision {
        proposition.mentions.asSequence()
            .mapNotNull { it.resolvedId }
            .firstOrNull { repository.findById(it) != null }
            ?.let { return ReconciliationDecision.Adopt(it) }

        return ReconciliationDecision.CreateNew
    }
}
