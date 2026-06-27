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

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.Proposition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GraphProjectionServiceTest {

    private val mockProjector = mockk<GraphProjector>()
    private val mockPersister = mockk<GraphRelationshipPersister>()
    private val mockSchema = DataDictionary.fromDomainTypes("test", emptyList())

    @Test
    fun `projectAndPersist projects then persists relationships`() {
        val propositions = listOf<Proposition>()
        val projectionResults = ProjectionResults<ProjectedRelationship>(emptyList())
        val persistenceResult = RelationshipPersistenceResult(
            persistedCount = 0,
            failedCount = 0,
        )

        every { mockProjector.projectAll(propositions, mockSchema) } returns projectionResults
        every { mockPersister.persist(projectionResults) } returns persistenceResult

        val service = GraphProjectionService(mockProjector, mockPersister, mockSchema)
        val result = service.projectAndPersist(propositions)

        assertSame(projectionResults, result.first)
        assertSame(persistenceResult, result.second)
        verify(exactly = 1) {
            mockProjector.projectAll(propositions, mockSchema)
            mockPersister.persist(projectionResults)
        }
    }

    @Test
    fun `create factory method constructs service`() {
        val propositions = listOf<Proposition>()
        val projectionResults = ProjectionResults<ProjectedRelationship>(emptyList())
        val persistenceResult = RelationshipPersistenceResult(
            persistedCount = 0,
            failedCount = 0,
        )

        every { mockProjector.projectAll(propositions, mockSchema) } returns projectionResults
        every { mockPersister.persist(projectionResults) } returns persistenceResult

        val service = GraphProjectionService.create(mockProjector, mockPersister, mockSchema)
        val result = service.projectAndPersist(propositions)

        assertEquals(0, result.first.successCount)
        assertEquals(0, result.second.persistedCount)
    }
}
