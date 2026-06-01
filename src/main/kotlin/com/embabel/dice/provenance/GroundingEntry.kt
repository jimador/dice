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
package com.embabel.dice.provenance

/**
 * A rich grounding entry linking a proposition back to its source material.
 *
 * This is the richer replacement for bare chunk-id grounding (the legacy
 * `grounding: List<String>` of chunk IDs). A [GroundingEntry] carries a
 * [SourceLocator] (where the material lives), plus optional locating details
 * within that source: the originating chunk, an offset range, and a content
 * hash captured at extraction time.
 *
 * All locating detail beyond the [locator] is optional, so coarse grounding
 * ("this came from that document") and precise grounding ("characters 120-180
 * of chunk 4") share the same shape.
 *
 * @property locator Reference to where the source material lives
 * @property chunkId Optional ID of the chunk this grounding came from
 * @property startOffset Optional inclusive start character offset within the source/chunk
 * @property endOffset Optional exclusive end character offset within the source/chunk
 * @property contentHash Optional hash of the grounding content captured at extraction time,
 *   useful for detecting source drift
 */
data class GroundingEntry @JvmOverloads constructor(
    val locator: SourceLocator,
    val chunkId: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val contentHash: String? = null,
) {

    init {
        require(startOffset == null || startOffset >= 0) { "startOffset must be non-negative" }
        require(endOffset == null || endOffset >= 0) { "endOffset must be non-negative" }
        require(
            startOffset == null || endOffset == null || endOffset >= startOffset
        ) { "endOffset must be >= startOffset" }
    }
}
