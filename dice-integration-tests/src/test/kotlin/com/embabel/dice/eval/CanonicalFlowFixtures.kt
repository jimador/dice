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

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.Relations
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.ingestion.IngestedArtifact
import com.embabel.dice.ingestion.IngestionBatch
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.UriLocator
import java.time.Instant

/**
 * Deterministic, offline fixture data for the canonical knowledge-flow harness.
 *
 * Every fixture proposition is ACTIVE and carries two resolved [EntityMention]s (a SUBJECT and an
 * OBJECT), so the graph-query, two-hop link, and projection stages all have non-empty edges to work
 * with. The predicate text matches a [relations] entry so the relation-based, AI-free projector can
 * produce an edge with no model call.
 *
 * The fixture also seeds one low-utility proposition ([decayCandidateId]) that a decay collector
 * strategy will mark and a sweep will transition off ACTIVE — driving the collector and event
 * stages of the flow.
 */
object CanonicalFlowFixtures {

    const val PREDICATE = "works with"

    const val ALICE = "entity-alice"
    const val BOB = "entity-bob"
    const val CAROL = "entity-carol"
    const val DANA = "entity-dana"

    /** Id of the proposition seeded to be collected (low effective confidence). */
    const val decayCandidateId = "prop-decay-candidate"

    val contextId: ContextId = ContextId("canonical-flow")

    val schema: DataDictionary = DataDictionary.fromClasses("canonical")

    /** Predicate the relation-based projector matches against, AI-free. */
    val relations: Relations = Relations.empty().withProcedural(PREDICATE)

    val context: SourceAnalysisContext = SourceAnalysisContext(
        schema = schema,
        entityResolver = AlwaysCreateEntityResolver,
        contextId = contextId,
    )

    private fun mention(span: String, id: String, role: MentionRole) =
        EntityMention(span = span, type = "Person", resolvedId = id, role = role)

    private fun edgeProposition(
        id: String,
        text: String,
        subjectSpan: String,
        subjectId: String,
        objectSpan: String,
        objectId: String,
        confidence: Double,
        decay: Double,
    ): Proposition = Proposition.create(
        id = id,
        contextIdValue = contextId.value,
        text = text,
        mentions = listOf(
            mention(subjectSpan, subjectId, MentionRole.SUBJECT),
            mention(objectSpan, objectId, MentionRole.OBJECT),
        ),
        confidence = confidence,
        decay = decay,
        reasoning = null,
        grounding = listOf("chunk-$id"),
        created = Instant.EPOCH,
        revised = Instant.EPOCH,
        status = PropositionStatus.ACTIVE,
    )

    /**
     * The canonical fixture propositions:
     * - alice—bob and bob—carol are high-confidence direct edges (so alice and carol become a
     *   two-hop indirect link via bob);
     * - the decay candidate (carol—dana) is low effective confidence so a decay sweep collects it.
     */
    fun propositions(): List<Proposition> = listOf(
        edgeProposition(
            id = "prop-alice-bob",
            text = "Alice $PREDICATE Bob",
            subjectSpan = "Alice", subjectId = ALICE,
            objectSpan = "Bob", objectId = BOB,
            confidence = 0.95, decay = 0.0,
        ),
        edgeProposition(
            id = "prop-bob-carol",
            text = "Bob $PREDICATE Carol",
            subjectSpan = "Bob", subjectId = BOB,
            objectSpan = "Carol", objectId = CAROL,
            confidence = 0.95, decay = 0.0,
        ),
        edgeProposition(
            id = decayCandidateId,
            text = "Carol $PREDICATE Dana",
            subjectSpan = "Carol", subjectId = CAROL,
            objectSpan = "Dana", objectId = DANA,
            confidence = 0.2, decay = 0.9,
        ),
    )

    /** A single-artifact batch carrying the source text the stub extractor maps to fixtures. */
    fun ingestionBatch(): IngestionBatch = IngestionBatch.of(
        IngestedArtifact
            .withSourceId("canonical-doc")
            .withLocator(UriLocator("https://example.com/canonical-doc"))
            .withText("Alice works with Bob. Bob works with Carol. Carol works with Dana."),
    )
}
