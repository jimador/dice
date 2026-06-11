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
package com.embabel.dice.agent

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.projection.memory.CollectorRunResult
import com.embabel.dice.projection.memory.CollectorRunner
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.query.discovery.RetrievalMode
import com.embabel.dice.query.discovery.RetrievalRouter
import com.embabel.dice.query.graph.GraphQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Shape and degradation contract for the agent-facing discovery tool group.
 *
 * The tools are thin presentation adapters over the one [RetrievalRouter] and the leak-free
 * discovery DTOs: they reflect into a registerable list of exactly five tools with stable
 * behaviour-named names, never trigger a full scan when a fragment is absent, and return leak-free
 * JSON.
 */
class DiscoveryToolsTest {

    private val contextId = ContextId("discovery-tools-test")

    /**
     * A base-only store that FAILS the test if it is ever scanned. Proves a vector query through the
     * tools degrades gracefully (supported=false) without a findAll() fallback.
     */
    private class ScanForbiddenStore : PropositionStore {
        override fun save(proposition: Proposition): Proposition = proposition
        override fun findById(id: String): Proposition? = null
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> =
            throw AssertionError("findAll() must not be invoked for a fragment-absent mode")
        override fun query(query: PropositionQuery): List<Proposition> =
            throw AssertionError("query() must not be invoked for a vector query")
        override fun delete(id: String): Boolean = false
        override fun count(): Int = 0
    }

    private val emptyRecordStore = object : ProjectionRecordStore {
        override fun record(record: ProjectionRecord) = Unit
        override fun all(): List<ProjectionRecord> = emptyList()
    }

    private val noopCollectorRunner = object : CollectorRunner {
        override fun collect(contextId: ContextId): CollectorRunResult = empty(contextId)
        override fun run(contextId: ContextId, dryRun: Boolean): CollectorRunResult = empty(contextId)
        private fun empty(contextId: ContextId) = CollectorRunResult(
            runId = "dry-${contextId.value}",
            dryRun = true,
            marks = emptyList(),
            applied = emptyList(),
            skipped = emptyList(),
            hardDeleted = emptyList(),
            startedAt = Instant.now(),
        )
    }

    private fun tools(store: PropositionStore = ScanForbiddenStore()): DiscoveryTools {
        val router = RetrievalRouter(store, GraphQuery(store, contextId), contextId)
        return DiscoveryTools(router, emptyRecordStore, noopCollectorRunner, contextId)
    }

    @Test
    fun `asTools reflects exactly the five discovery tools with stable names`() {
        val store = ScanForbiddenStore()
        val router = RetrievalRouter(store, GraphQuery(store, contextId), contextId)
        val list = DiscoveryTools.asTools(router, emptyRecordStore, noopCollectorRunner, contextId)

        assertEquals(5, list.size, "expected exactly five tools, got ${list.size}")
        val names = list.map { it.definition.name }.toSet()
        assertEquals(
            setOf("query_propositions", "graph_path", "why_explain", "projection_health", "collector_dry_run"),
            names,
        )
    }

    @Test
    fun `vector query against a base-only store degrades to supported=false without scanning`() {
        val result = tools().queryPropositions(mode = "vector", text = "anything")

        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("\"supported\":false"), "vector with no fragment should be unsupported: $text")
        assertTrue(text.contains("\"mode\":\"VECTOR\""), "should echo the routed mode: $text")
    }

    @Test
    fun `an unparseable mode yields an error result naming the valid modes`() {
        val result = tools().queryPropositions(mode = "not-a-mode")

        assertTrue(result is Tool.Result.Error, "bad mode should yield an error, got: $result")
        assertEquals(5, RetrievalMode.entries.size)
    }

    @Test
    fun `projection health over an empty index is a leak-free empty summary`() {
        val result = tools().projectionHealth()

        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("perTarget"), "should expose the per-target summary: $text")
        assertFalse(text.contains("neo4j", ignoreCase = true), "must not leak store identifiers: $text")
    }

    @Test
    fun `collector dry-run returns a non-mutating preview`() {
        val result = tools().collectorDryRun()

        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("\"dryRun\":true"), "preview must be flagged dryRun: $text")
    }
}
