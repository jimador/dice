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
package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.Chunk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Controls how the [PropositionPipeline] dispatches the stateless per-chunk extraction phase
 * (Phase 1). Implementations choose serial or concurrent execution; Phase 2 (entity resolution)
 * always runs serially regardless of the strategy, preserving cross-chunk entity identity.
 *
 * ## Contract
 * - **Input order is preserved**: the returned list has exactly `chunks.size` elements and
 *   `result[i]` corresponds to `chunks[i]` (or `null` if extraction of `chunks[i]` failed).
 * - **Failure is a `null` slot**: when `extract(chunk)` throws, that slot becomes `null` rather
 *   than propagating the exception. The pipeline maps a `null` slot to a
 *   [ChunkPropositionResult.Failed]. No element is ever dropped or reordered.
 *
 * ## Executor ownership
 * Each implementation owns its execution mechanism. The [execute] signature intentionally
 * does not mention [ExecutorService] — any executor is an injected implementation detail.
 * The executor lifecycle is always the caller's responsibility; no strategy ever calls
 * `executor.shutdown()`.
 *
 * ## Thread-safety gate for production
 * The default is [SerialExtractionStrategy] (no concurrency). Before switching to
 * [ParallelExtractionStrategy] or [BatchedExtractionStrategy] with `batchSize > 1`, verify
 * that the `Ai` implementation backing your `PropositionExtractor` is thread-safe for
 * concurrent `extract()` calls. Until then, `BatchedExtractionStrategy(batchSize = 1)` is
 * the safe degrade-to-serial path.
 *
 * Note: this is a plain `interface` rather than a `fun interface` because Kotlin SAM
 * conversion does not support abstract methods with their own type parameters (`fun <T> execute(...)`).
 */
interface ExtractionExecutionStrategy {

    /**
     * Apply [extract] to each chunk, returning results in INPUT ORDER. A `null` slot at
     * index `i` means `extract(chunks[i])` threw and was caught (see contract above).
     *
     * @param chunks the chunks to extract, in order
     * @param extract the stateless per-chunk extraction function
     * @return a list of size `chunks.size`; `result[i]` is the extraction of `chunks[i]` or `null` on failure
     */
    fun <T> execute(chunks: List<Chunk>, extract: (Chunk) -> T): List<T?>
}

/**
 * Default strategy: processes each chunk one at a time on the calling thread. A failed
 * extraction produces a `null` slot rather than propagating the exception.
 */
object SerialExtractionStrategy : ExtractionExecutionStrategy {
    override fun <T> execute(chunks: List<Chunk>, extract: (Chunk) -> T): List<T?> =
        chunks.map { runCatching { extract(it) }.getOrNull() }
}

/**
 * Fans all chunks out concurrently on the injected [executor] via [CompletableFuture], then
 * joins them in input order. A failed extraction yields a `null` slot.
 *
 * The [executor] is caller-owned — this class never calls [ExecutorService.shutdown].
 *
 * Production use requires verifying extractor thread-safety first (see [ExtractionExecutionStrategy]).
 */
class ParallelExtractionStrategy @JvmOverloads constructor(
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()),
) : ExtractionExecutionStrategy {

    override fun <T> execute(chunks: List<Chunk>, extract: (Chunk) -> T): List<T?> =
        chunks.map { chunk ->
            CompletableFuture.supplyAsync({
                runCatching { extract(chunk) }.getOrElse { null }
            }, executor)
        }.map { it.join() }
}

/**
 * Processes chunks in fixed-size windows: each window fans out concurrently on the injected
 * [executor] and is fully joined before the next window starts. This is the rate-limit lever —
 * it caps in-flight `extract()` calls to [batchSize] at a time. Results are returned in input order.
 *
 * `BatchedExtractionStrategy(batchSize = 1)` is the safe degrade-to-serial path until extractor
 * thread-safety is verified (see [ExtractionExecutionStrategy]). The [executor] is caller-owned —
 * this class never calls [ExecutorService.shutdown].
 *
 * @param batchSize maximum concurrent extractions per window; must be positive
 */
class BatchedExtractionStrategy @JvmOverloads constructor(
    private val batchSize: Int = 5,
    private val executor: ExecutorService = Executors.newFixedThreadPool(batchSize),
) : ExtractionExecutionStrategy {

    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    override fun <T> execute(chunks: List<Chunk>, extract: (Chunk) -> T): List<T?> =
        chunks.chunked(batchSize).flatMap { window ->
            window.map { chunk ->
                CompletableFuture.supplyAsync({
                    runCatching { extract(chunk) }.getOrElse { null }
                }, executor)
            }.map { it.join() }
        }
}
