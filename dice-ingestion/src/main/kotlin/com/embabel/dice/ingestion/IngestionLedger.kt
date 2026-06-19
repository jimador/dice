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
package com.embabel.dice.ingestion

import java.util.concurrent.ConcurrentHashMap

/**
 * A lean deduplication ledger recording which content hashes have been seen.
 *
 * The ledger lets a handler short-circuit re-ingestion of identical content
 * before any extraction work runs. Hashes are caller-supplied identity, not a
 * tamper-proof security control. Implementations may persist their record to
 * deduplicate across sessions; DICE ships an in-memory default.
 */
interface IngestionLedger {

    /**
     * Whether content with the given hash has already been recorded.
     *
     * @param contentHash The deduplication key to check
     * @return true if the hash was previously recorded
     */
    fun seen(contentHash: String): Boolean

    /**
     * Record that content with the given hash has been ingested.
     *
     * @param contentHash The deduplication key to record
     */
    fun record(contentHash: String)

    /**
     * Atomically claim a content hash: record it and report whether the claim
     * was new. This collapses the check-then-record sequence into a single step
     * so concurrent attempts to ingest identical content cannot both pass a
     * separate seen-check before either records.
     *
     * The default implementation is a non-atomic fallback over [seen]/[record]
     * for existing implementations; ledgers used under concurrent ingestion
     * should override this with a genuinely atomic operation.
     *
     * @param contentHash The deduplication key to claim
     * @return true if the hash was newly recorded, false if already present
     */
    fun recordIfAbsent(contentHash: String): Boolean {
        if (seen(contentHash)) return false
        record(contentHash)
        return true
    }

    /**
     * Release a previously recorded content hash so it is no longer treated as
     * seen. Used to undo a claim when the work it guarded did not complete,
     * keeping retries of failed content un-deduplicated.
     *
     * @param contentHash The deduplication key to release
     */
    fun forget(contentHash: String)
}

/**
 * In-memory [IngestionLedger] backed by a concurrent set of seen hashes.
 *
 * Suitable for a single process or tests; consumers needing cross-session
 * deduplication supply a durable implementation.
 */
class InMemoryIngestionLedger : IngestionLedger {

    private val seenHashes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun seen(contentHash: String): Boolean = contentHash in seenHashes

    override fun record(contentHash: String) {
        seenHashes.add(contentHash)
    }

    override fun recordIfAbsent(contentHash: String): Boolean = seenHashes.add(contentHash)

    override fun forget(contentHash: String) {
        seenHashes.remove(contentHash)
    }
}
