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
package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.text2graph.*

/**
 * Unconditionally mints a fresh [NewEntity] for every suggestion.
 *
 * Because it never consults any repository, the IDs it mints will never match
 * existing graph nodes — every mention becomes a brand-new entity. This makes it
 * suitable only for development, tests, and one-off seeding of an empty store.
 *
 * For any flow where mentions must be matched against already-persisted entities
 * (i.e. anything resembling production), use [EscalatingEntityResolver] instead.
 */
object AlwaysCreateEntityResolver : EntityResolver {

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary,
    ): Resolutions<SuggestedEntityResolution> {
        val resolvedEntities = suggestedEntities.suggestedEntities.map {
            NewEntity(it)
        }
        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = resolvedEntities,
        )
    }

}
