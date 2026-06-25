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
package com.embabel.dice.projection.graph

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.common.Relations
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.RepositoryBackedReconciler
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Proves that projecting propositions whose mentions resolve to ids of nodes
 * already present in a real graph adds relationships but no new nodes.
 *
 * Seeds two nodes in a container-backed graph, then drives the real
 * [GraphProjectionService] + [NamedEntityDataRepositoryGraphRelationshipPersister]
 * + [RepositoryBackedReconciler] against a repository view that delegates
 * its id lookup / save / merge to the same graph. The post-projection node count
 * must equal the pre-projection count.
 *
 * Gated so the suite stays green where no container runtime is available.
 */
@Testcontainers(disabledWithoutDocker = true)
class SeededGraphNoDuplicateNodesIT {

    private val contextId = ContextId("seeded-graph")

    @Test
    fun `projecting onto pre-seeded nodes adds edges but no nodes`() {
        GraphDatabase.driver(
            neo4j.boltUrl,
            AuthTokens.basic("neo4j", neo4j.adminPassword),
        ).use { driver ->
            // (1) Seed two nodes with explicit ids matching the resolved mentions.
            driver.session().use { session ->
                session.run("MATCH (n) DETACH DELETE n")
                session.run(
                    "CREATE (:Person {id: \$rod, name: 'Rod'}), (:Person {id: \$tom, name: 'Tom'})",
                    mapOf<String, Any>("rod" to ROD_ID, "tom" to TOM_ID),
                )
            }

            val before = countNodes(driver)

            // (2) Repository view over the seeded graph: id lookup, verbatim re-save,
            //     and id-keyed relationship merge all delegate to the container.
            val repository = graphBackedRepository(driver)

            val projector = RelationBasedGraphProjector
                .from(Relations.empty().withProcedural("knows", "is acquainted with"))
                .withLenientPolicy(0.0)
            val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repository)
            // A record store is supplied so the reconciler is actually
            // consulted during projection — without it the service short-circuits
            // and the resolver never runs.
            val recordStore = InMemoryProjectionRecordStore()
            val service = GraphProjectionService(
                graphProjector = projector,
                persister = persister,
                schema = DataDictionary.fromClasses("seeded-graph"),
                recordStore = recordStore,
                reconciler = RepositoryBackedReconciler(repository),
            )

            // (3) Project a proposition whose subject/object resolve to the seeded ids.
            service.projectAndPersist(
                listOf(
                    Proposition(
                        id = "prop-1",
                        contextId = contextId,
                        text = "Rod knows Tom",
                        mentions = listOf(
                            EntityMention("Rod", "Person", resolvedId = ROD_ID, role = MentionRole.SUBJECT),
                            EntityMention("Tom", "Person", resolvedId = TOM_ID, role = MentionRole.OBJECT),
                        ),
                        confidence = 0.95,
                    ),
                ),
            )

            val after = countNodes(driver)

            // (4) The seeded endpoint nodes are reused by id-keyed persistence, while the newly
            //     created relationship is recorded as the projected artifact.
            assertTrue(
                recordStore.all().any {
                    it.lifecycle == ProjectionLifecycle.PROJECTED &&
                        it.targetRef == "$ROD_ID-[KNOWS]->$TOM_ID"
                },
                "lineage must reference the produced relationship edge, not an endpoint node",
            )
            assertEquals(before, after, "projection must not mint duplicate nodes")
            assertEquals(2L, after, "exactly the two seeded nodes should remain")
            assertEquals(1L, countRelationships(driver), "the projected edge should be present")
        }
    }

    /**
     * Repository view that satisfies the persister + resolver against the live
     * container. Only the methods exercised by the projection path touch the
     * graph; everything else is relaxed and unused by this proof.
     */
    private fun graphBackedRepository(driver: Driver): NamedEntityDataRepository {
        val repository = mockk<NamedEntityDataRepository>(relaxed = true)

        every { repository.findById(any()) } answers {
            val id = firstArg<String>()
            driver.session().use { session ->
                val result = session.run(
                    "MATCH (n {id: \$id}) RETURN labels(n) AS labels, n.name AS name LIMIT 1",
                    mapOf<String, Any>("id" to id),
                )
                if (!result.hasNext()) {
                    null
                } else {
                    val record = result.next()
                    val labels = record["labels"].asList { it.asString() }.toSet()
                    SimpleNamedEntityData(
                        id = id,
                        name = record["name"].asString(id),
                        description = "",
                        labels = labels,
                        properties = emptyMap(),
                    ) as NamedEntityData
                }
            }
        }

        // Re-save is verbatim and id-keyed: MERGE on id never mints a second node.
        every { repository.save(any()) } answers {
            val entity = firstArg<NamedEntityData>()
            driver.session().use { session ->
                session.run(
                    "MERGE (n {id: \$id}) SET n.name = \$name",
                    mapOf<String, Any>("id" to entity.id, "name" to entity.name),
                )
            }
            entity
        }

        every { repository.mergeRelationship(any(), any(), any()) } answers {
            val source = firstArg<RetrievableIdentifier>()
            val target = secondArg<RetrievableIdentifier>()
            val rel = thirdArg<RelationshipData>()
            driver.session().use { session ->
                session.run(
                    "MATCH (a {id: \$source}), (b {id: \$target}) " +
                        "MERGE (a)-[r:RELATED {type: \$type}]->(b)",
                    mapOf<String, Any>(
                        "source" to source.id,
                        "target" to target.id,
                        "type" to rel.name,
                    ),
                )
            }
        }

        return repository
    }

    private fun countNodes(driver: Driver): Long =
        driver.session().use { it.run("MATCH (n) RETURN count(n) AS c").single()["c"].asLong() }

    private fun countRelationships(driver: Driver): Long =
        driver.session().use { it.run("MATCH ()-[r]->() RETURN count(r) AS c").single()["c"].asLong() }

    companion object {
        private const val ROD_ID = "person-rod"
        private const val TOM_ID = "person-tom"

        @Container
        @JvmStatic
        val neo4j: Neo4jContainer<*> = Neo4jContainer("neo4j:5-community")
    }
}
