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
 * A reusable, immutable collection of [Relation] types with a fluent builder API.
 *
 * Build one up with `withSemantic`, `withProcedural`, etc., then pass it to a
 * `SourceAnalysisContext` via `withRelations`. The same instance can be shared
 * across multiple contexts.
 *
 * ```kotlin
 * val relations = Relations.empty()
 *     .withSemantic("works at", "is employed by")
 *     .withProcedural("prefers", "has preference for")
 *     .withProceduralForSubject("Person", "likes", "dislikes", "prefers")
 *
 * val context = SourceAnalysisContext.withContextId("ctx")
 *     .withEntityResolver(resolver)
 *     .withSchema(schema)
 *     .withRelations(relations)
 * ```
 *
 * @property relations The relation types in this collection.
 */
data class Relations(
    val relations: List<Relation> = emptyList(),
) : Iterable<Relation> {

    companion object {
        /** Start with an empty collection and build up with `with*` calls. */
        @JvmStatic
        fun empty(): Relations = Relations()

        /** Wrap an existing vararg of relations. */
        @JvmStatic
        fun of(vararg relations: Relation): Relations = Relations(relations.toList())

        /** Wrap an existing list of relations. */
        @JvmStatic
        fun of(relations: List<Relation>): Relations = Relations(relations)
    }

    override fun iterator(): Iterator<Relation> = relations.iterator()

    /** Number of relations in this collection. */
    fun size(): Int = relations.size

    /** True if this collection has no relations. */
    fun isEmpty(): Boolean = relations.isEmpty()

    /** Returns a new collection containing the relations from both. */
    operator fun plus(other: Relations): Relations =
        Relations(relations + other.relations)

    /** Returns a new collection with the given relation appended. */
    operator fun plus(relation: Relation): Relations =
        Relations(relations + relation)

    /** Add a semantic (factual) relation. */
    @JvmOverloads
    fun withSemantic(predicate: String, meaning: String = predicate): Relations =
        this + Relation.semantic(predicate, meaning)

    /** Add a procedural (behavioral/preference) relation. */
    @JvmOverloads
    fun withProcedural(predicate: String, meaning: String = predicate): Relations =
        this + Relation.procedural(predicate, meaning)

    /** Add an episodic (event-based) relation. */
    @JvmOverloads
    fun withEpisodic(predicate: String, meaning: String = predicate): Relations =
        this + Relation.episodic(predicate, meaning)

    /** Add a semantic relation that only applies when the subject is of the given type. */
    fun withSemanticForSubject(subjectType: String, predicate: String, meaning: String): Relations =
        this + Relation.semanticForSubject(predicate, meaning, subjectType)

    /** Add a procedural relation that only applies when the subject is of the given type. */
    fun withProceduralForSubject(subjectType: String, predicate: String, meaning: String): Relations =
        this + Relation.proceduralForSubject(predicate, meaning, subjectType)

    /** Add a semantic relation constrained to a specific subject and object type pair. */
    fun withSemanticBetween(
        subjectType: String,
        objectType: String,
        predicate: String,
        meaning: String,
    ): Relations = this + Relation.semanticBetween(predicate, meaning, subjectType, objectType)

    /**
     * Add a semantic relation constrained to a specific subject and object type pair, with an
     * evidence floor declared inline. This lets you stay in the fluent builder chain rather than
     * stepping outside it to call [Relation.withEvidenceFloor] separately.
     *
     * ```kotlin
     * Relations.empty()
     *     .withSemanticBetween(
     *         subjectType = "Person", objectType = "Organization",
     *         predicate = "works for", meaning = "is employed by",
     *         floor = EvidenceFloor.ofConfidence(0.7, demoteTo = "affiliated with"),
     *     )
     * ```
     *
     * Java callers: use [Relation.semanticBetween] + [Relation.withEvidenceFloor] to attach a
     * floor, since this overload is not `@JvmOverloads`-compatible with the four-parameter form.
     *
     * @param floor the minimum evidence the relation requires before it may be asserted at full
     *   strength; pass null to add the relation without a floor (equivalent to the overload above)
     */
    fun withSemanticBetween(
        subjectType: String,
        objectType: String,
        predicate: String,
        meaning: String,
        floor: EvidenceFloor?,
    ): Relations {
        val relation = Relation.semanticBetween(predicate, meaning, subjectType, objectType)
        return this + if (floor != null) relation.withEvidenceFloor(floor) else relation
    }

    /**
     * Add several relations at once, all sharing the same subject type and knowledge type.
     * The predicate string is used as the meaning for each one.
     *
     * @param subjectType Entity type that can be the subject (string form, e.g. `"Person"`)
     * @param knowledgeType Knowledge type shared by all added relations
     * @param predicates Predicate strings to add
     */
    fun withPredicatesForSubject(
        subjectType: String,
        knowledgeType: KnowledgeType,
        vararg predicates: String,
    ): Relations {
        val newRelations = predicates.map { predicate ->
            Relation(
                predicate = predicate,
                meaning = predicate,
                knowledgeType = knowledgeType,
                subjectType = subjectType,
            )
        }
        return Relations(relations + newRelations)
    }

    /**
     * Same as [withPredicatesForSubject] but takes a class instead of a string — the subject
     * type is recorded as the class `simpleName`. A mention's type must match it (case-insensitive)
     * for an edge to project.
     *
     * @param subjectType Entity class whose simple name is used as the subject type constraint
     * @param knowledgeType Knowledge type shared by all added relations
     * @param predicates Predicate strings to add
     */
    fun withPredicatesForSubject(
        subjectType: Class<*>,
        knowledgeType: KnowledgeType,
        vararg predicates: String,
    ): Relations = withPredicatesForSubject(subjectType.simpleName, knowledgeType, *predicates)

    /** Add several procedural relations at once for a subject type. */
    fun withProceduralPredicatesForSubject(subjectType: String, vararg predicates: String): Relations =
        withPredicatesForSubject(subjectType, KnowledgeType.PROCEDURAL, *predicates)

    /** Add several semantic relations at once for a subject type. */
    fun withSemanticPredicatesForSubject(subjectType: String, vararg predicates: String): Relations =
        withPredicatesForSubject(subjectType, KnowledgeType.SEMANTIC, *predicates)

    /** The relations as a plain list. */
    fun toList(): List<Relation> = relations
}
