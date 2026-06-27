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

import java.time.Instant

/**
 * A leak-free request for the discovery surface.
 *
 * The request carries only primitive / String / Instant / enum fields — no store, RAG, or graph
 * types cross the caller boundary. The context is NOT part of the request: it is baked into the
 * router so a caller cannot read across contexts.
 *
 * Path-style queries (connecting two entities) are not a retrieval mode; they are served by the
 * dedicated path endpoint/tool which takes its two entity ids directly, so this request carries no
 * destination-entity field.
 *
 * @property mode which retrieval policy to apply
 * @property text the query text for VECTOR / HYBRID modes
 * @property entityId the anchor entity for ENTITY / GRAPH_WALK / HYBRID modes
 * @property from the inclusive start of the TEMPORAL window
 * @property to the inclusive end of the TEMPORAL window
 * @property topK the maximum number of results to return, applied to every mode (clamped by the router)
 * @property depth the graph traversal depth for GRAPH_WALK / HYBRID (clamped by the router)
 * @property similarityThreshold the minimum vector similarity for VECTOR / HYBRID hits, 0.0..1.0
 *   (clamped by the router). Defaults to 0.0, which keeps every hit the index returns.
 */
data class DiscoveryQuery(
    val mode: RetrievalMode,
    val text: String? = null,
    val entityId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val topK: Int = 10,
    val depth: Int = 1,
    val similarityThreshold: Double = 0.0,
)
