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

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelType
import com.embabel.common.ai.model.PricingModel

/**
 * Deterministic, fully offline [EmbeddingService] for the reusable canonical-flow
 * harness.
 *
 * [embed] derives a stable three-component vector purely from the text's hash code, so
 * the same text always maps to the same vector with no model, network, or external call.
 * This lets the vector path through a store be exercised without a real embedding model.
 *
 * The full member surface of the on-classpath [EmbeddingService] is implemented by hand
 * (rather than a mocking framework) so this fixture is self-contained and a future store
 * adapter can reuse it directly.
 */
class FixedVectorEmbeddingService(
    override val name: String = "fixed-vector",
    override val provider: String = "in-test",
) : EmbeddingService {

    /** Hand-written, deterministic per-text vector. */
    override fun embed(text: String): FloatArray {
        val h = text.hashCode()
        return floatArrayOf(
            (h and 0xFF) / 255f,
            ((h shr 8) and 0xFF) / 255f,
            ((h shr 16) and 0xFF) / 255f,
        )
    }

    override fun embed(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    override val dimensions: Int = 3

    override val type: ModelType = ModelType.EMBEDDING

    override val pricingModel: PricingModel = PricingModel.ALL_YOU_CAN_EAT
}
