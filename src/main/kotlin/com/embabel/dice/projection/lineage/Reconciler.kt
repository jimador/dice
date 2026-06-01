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

import com.embabel.dice.proposition.Proposition

/**
 * SPI for reconciling a proposition's projection against existing artifacts in
 * a target backend — deciding whether to create new, adopt, or align.
 *
 * Implementations encode backend-specific matching strategy (exact key, fuzzy
 * name, vector similarity, etc.), but the contract itself is backend-agnostic:
 * given a proposition and a target name, return a [ReconciliationDecision].
 *
 * Mirrors the existing entity-resolution SPI pattern (see
 * `com.embabel.dice.common.EntityResolver`), but for projection targets rather
 * than entity mentions.
 */
interface Reconciler {

    /**
     * Decide how [proposition] should be projected to [target].
     *
     * @param proposition The proposition being projected
     * @param target The projection target (e.g. "neo4j")
     * @return whether to create new, adopt, or align with an existing artifact
     */
    fun reconcile(proposition: Proposition, target: String): ReconciliationDecision
}
