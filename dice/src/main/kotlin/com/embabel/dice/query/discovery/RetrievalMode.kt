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
package com.embabel.dice.query.discovery

/**
 * The retrieval policy selecting which capability fragment a discovery request routes to.
 *
 * Each mode maps to exactly one underlying retrieval path. A mode whose backing fragment is absent
 * degrades to a typed-empty result with a `supported = false` signal — it never silently falls back
 * to a full scan.
 */
enum class RetrievalMode {

    /** Vector similarity over proposition text (requires a vector-capable store). */
    VECTOR,

    /** Propositions mentioning a given entity, scoped to the routed context. */
    ENTITY,

    /** Graph-neighbourhood expansion around an entity, derived from proposition edges. */
    GRAPH_WALK,

    /** Propositions created within a time window (requires a temporal-capable store). */
    TEMPORAL,

    /** Vector similarity unioned with graph-neighbourhood expansion, merged deterministically. */
    HYBRID,
}
