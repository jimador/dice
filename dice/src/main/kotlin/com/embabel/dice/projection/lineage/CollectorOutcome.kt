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
package com.embabel.dice.projection.lineage

/**
 * What the collector did to a single proposition during a collection run.
 *
 * Distinct from a projection lifecycle: a collector marks, transitions, or (only
 * when explicitly opted in) hard-deletes propositions, and may skip those that are
 * exempt (e.g. pinned). The outcome is recorded on a [CollectorRecord] alongside the
 * typed reason so a reviewer can trace why each proposition was acted upon.
 */
enum class CollectorOutcome {

    /** The proposition was marked for collection but not yet swept. */
    MARKED,

    /** The proposition's status was transitioned (the non-destructive default sweep). */
    TRANSITIONED,

    /** The proposition was permanently removed (explicit opt-in only; never the default). */
    HARD_DELETED,

    /** The proposition was intentionally not acted upon (e.g. exempt or not eligible). */
    SKIPPED
}
