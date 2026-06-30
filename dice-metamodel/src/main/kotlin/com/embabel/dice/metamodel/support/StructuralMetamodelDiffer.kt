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
package com.embabel.dice.metamodel.support

import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelDiffer
import com.embabel.dice.metamodel.MetamodelVersion
import org.slf4j.LoggerFactory

/**
 * The default [MetamodelDiffer]: a deterministic, structural comparison of two [MetamodelVersion]s.
 *
 * It works on the snapshot data each version already carries — entity-type names, per-type label and
 * property sets, and relationship descriptors — and reports what was added, removed, or modified.
 * Sets are compared directly (never via a delimiter-joined projection), so a label or property whose
 * name contains a delimiter can't collapse two genuinely different sets into a false "unchanged".
 *
 * Stateless and thread-safe — a single shared instance is fine.
 */
class StructuralMetamodelDiffer : MetamodelDiffer {

    private val logger = LoggerFactory.getLogger(StructuralMetamodelDiffer::class.java)

    override fun diff(from: MetamodelVersion, to: MetamodelVersion): MetamodelDiff {
        val changes = mutableListOf<MetamodelChange>()

        // --- Entity type changes ---
        val fromTypes = from.entityTypeNames.toSet()
        val toTypes = to.entityTypeNames.toSet()

        val removedTypes = fromTypes - toTypes
        val addedTypes = toTypes - fromTypes

        removedTypes.sorted().mapTo(changes) { MetamodelChange.EntityTypeRemoved(it) }
        addedTypes.sorted().mapTo(changes) { MetamodelChange.EntityTypeAdded(it) }

        // Detect modified entity types: a type whose name is preserved but whose label or property
        // set changed. Compare the sets directly — never a delimiter-joined projection — so a label
        // or property whose name contains the delimiter (spaces are common in free-text / LLM-extracted
        // names) can't collapse two genuinely different sets into a false "unchanged".
        val commonTypes = fromTypes intersect toTypes
        for (typeName in commonTypes.sorted()) {
            val fromLabels = from.entityTypeLabels[typeName].orEmpty()
            val toLabels = to.entityTypeLabels[typeName].orEmpty()
            val fromProperties = from.entityTypeProperties[typeName].orEmpty()
            val toProperties = to.entityTypeProperties[typeName].orEmpty()

            val addedLabels = toLabels - fromLabels
            val removedLabels = fromLabels - toLabels
            val addedProperties = toProperties - fromProperties
            val removedProperties = fromProperties - toProperties

            if (addedLabels.isNotEmpty() || removedLabels.isNotEmpty() ||
                addedProperties.isNotEmpty() || removedProperties.isNotEmpty()
            ) {
                changes += MetamodelChange.EntityTypeModified(
                    typeName = typeName,
                    addedLabels = addedLabels,
                    removedLabels = removedLabels,
                    addedProperties = addedProperties,
                    removedProperties = removedProperties,
                )
            }
        }

        // --- Relationship changes ---
        val fromRels = from.relationshipNames.toSet()
        val toRels = to.relationshipNames.toSet()

        val removedRels = fromRels - toRels
        val addedRels = toRels - fromRels

        removedRels.sorted().mapTo(changes) { MetamodelChange.RelationshipRemoved(it) }
        addedRels.sorted().mapTo(changes) { MetamodelChange.RelationshipAdded(it) }

        logger.debug(
            "Metamodel diff '{}' → '{}': {} added types, {} removed types, {} modified types, " +
                "{} added rels, {} removed rels",
            from.schemaName,
            to.schemaName,
            addedTypes.size,
            removedTypes.size,
            changes.count { it is MetamodelChange.EntityTypeModified },
            addedRels.size,
            removedRels.size,
        )

        return MetamodelDiff(fromVersion = from, toVersion = to, changes = changes)
    }
}
