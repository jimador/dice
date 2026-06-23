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
package com.embabel.dice.spi

import com.embabel.dice.proposition.PropositionStatus

/**
 * What the sweep phase should do with a marked proposition, as decided by a [SweepPolicy].
 *
 * A sealed family so the runner can exhaustively dispatch over the recognized outcomes.
 */
sealed interface SweepAction {

    /**
     * Soft, recoverable retirement: transition the proposition to [newStatus]
     * (e.g. [PropositionStatus.STALE]). This is the non-destructive default outcome.
     *
     * @property newStatus The lifecycle status to transition the proposition to.
     */
    data class TransitionStatus(val newStatus: PropositionStatus) : SweepAction

    /**
     * Permanently remove the proposition. This is destructive and unrecoverable, so it is
     * never the default — a policy must opt in explicitly, and the default
     * [StatusTransitionSweepPolicy] never returns it.
     */
    data object HardDelete : SweepAction

    /** Leave the proposition untouched (e.g. it is pinned, or carries no marks). */
    data object Skip : SweepAction
}
