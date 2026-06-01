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

/**
 * Decision about how a proposition should be projected relative to existing
 * artifacts in a target backend — i.e. how to reconcile the projection with
 * what is already there.
 *
 * This is the backend-agnostic seam for "project onto an existing domain node
 * vs create new vs align", letting DICE avoid duplicating nodes in a domain
 * graph it does not own. (Conceptually the get-or-create/MERGE choice a target
 * store like Neo4j makes, surfaced as an explicit SPI result.)
 */
sealed interface ReconciliationDecision {

    /**
     * No matching artifact exists; create a new one in the target.
     */
    object CreateNew : ReconciliationDecision

    /**
     * Adopt an existing target artifact as the projection of this proposition.
     * The proposition's projected identity becomes [targetRef].
     *
     * @property targetRef Reference to the existing artifact to adopt
     */
    data class Adopt(val targetRef: String) : ReconciliationDecision {
        init {
            require(targetRef.isNotBlank()) { "targetRef must not be blank" }
        }
    }

    /**
     * Align this proposition with an existing target artifact without fully
     * adopting it — e.g. add/merge attributes while keeping distinct identity.
     *
     * @property targetRef Reference to the existing artifact to align with
     */
    data class Align(val targetRef: String) : ReconciliationDecision {
        init {
            require(targetRef.isNotBlank()) { "targetRef must not be blank" }
        }
    }
}
