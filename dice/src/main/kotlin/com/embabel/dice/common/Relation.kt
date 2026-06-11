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
package com.embabel.dice.common

/**
 * Defines a relationship type that can exist between entities.
 * Used to provide the LLM with vocabulary for expressing relationships in propositions.
 *
 * @param predicate The verb phrase expressing the relationship, e.g., "likes", "works at"
 * @param meaning A description of what the relationship means, e.g., "expresses positive preference for"
 * @param knowledgeType The epistemological nature of this relationship
 * @param subjectType Optional constraint on the subject entity type, e.g., "Person".
 *   When set from a Kotlin class via [withSubject] it is the class `simpleName`; a
 *   mention's type must match this (tolerant of case) for an edge to project.
 * @param objectType Optional constraint on the object entity type, e.g., "Company".
 *   When set from a Kotlin class via [withObject] it is the class `simpleName`; a
 *   mention's type must match this (tolerant of case) for an edge to project.
 * @param evidenceFloor Optional minimum evidence required before this relation may be asserted at
 *   full strength. Left null, the relation has no floor and behaves as before. When set, a gate such
 *   as `EvidenceFloorGate` reads it to demote or hold under-supported assertions — so a structural
 *   signal can't masquerade as a strong claim.
 */
data class Relation @JvmOverloads constructor(
    val predicate: String,
    val meaning: String,
    val knowledgeType: KnowledgeType,
    val subjectType: String? = null,
    val objectType: String? = null,
    val evidenceFloor: EvidenceFloor? = null,
) {

    fun withSubject(type: Class<*>): Relation =
        copy(subjectType = type.simpleName)

    fun withObject(type: Class<*>): Relation =
        copy(objectType = type.simpleName)

    /** Declare the minimum evidence required before this relation may be asserted at full strength. */
    fun withEvidenceFloor(floor: EvidenceFloor): Relation =
        copy(evidenceFloor = floor)

    companion object {

        /** A factual relation with no subject or object type constraint. */
        @JvmStatic
        @JvmOverloads
        fun semantic(predicate: String, meaning: String = predicate): Relation =
            Relation(predicate, meaning, KnowledgeType.SEMANTIC)

        /** A preference/behavioral relation with no subject or object type constraint. */
        @JvmStatic
        @JvmOverloads
        fun procedural(predicate: String, meaning: String = predicate): Relation =
            Relation(predicate, meaning, KnowledgeType.PROCEDURAL)

        /** An event-based relation with no subject or object type constraint. */
        @JvmStatic
        @JvmOverloads
        fun episodic(predicate: String, meaning: String = predicate): Relation =
            Relation(predicate, meaning, KnowledgeType.EPISODIC)

        /** A factual relation that only applies when the subject matches the given type. */
        @JvmStatic
        fun semanticForSubject(predicate: String, meaning: String, subjectType: String): Relation =
            Relation(predicate, meaning, KnowledgeType.SEMANTIC, subjectType = subjectType)

        /** A preference/behavioral relation that only applies when the subject matches the given type. */
        @JvmStatic
        fun proceduralForSubject(predicate: String, meaning: String, subjectType: String): Relation =
            Relation(predicate, meaning, KnowledgeType.PROCEDURAL, subjectType = subjectType)

        /** A factual relation constrained to a specific subject and object type pair. */
        @JvmStatic
        fun semanticBetween(predicate: String, meaning: String, subjectType: String, objectType: String): Relation =
            Relation(predicate, meaning, KnowledgeType.SEMANTIC, subjectType = subjectType, objectType = objectType)
    }
}
