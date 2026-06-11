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
package com.embabel.dice.operations.consolidation

import com.embabel.agent.core.ContextId
import com.embabel.dice.projection.memory.CollectorRunner
import com.embabel.dice.proposition.Proposition

/**
 * The decay pass in a consolidation cycle: a thin wrapper that delegates entirely to an injected
 * [CollectorRunner] and translates its outcome into a [ConsolidationPassResult].
 *
 * This pass owns no sweep logic — the runner handles candidate selection, pinned-proposition
 * exemption, staleness evaluation, persistence, and lifecycle events. The pass just drives the
 * runner and reports what happened.
 *
 * ## Soft STALE vs. hard-delete
 *
 * The behavior depends on the [SweepPolicy] the runner was built with. The default policy retires
 * propositions softly to `STALE` and never hard-deletes, so a [DecaySweepPass] over a default
 * runner is non-destructive. For hard-delete, build the runner with a policy that returns
 * `SweepAction.HardDelete` for stale marks — this pass surfaces those deletes in its summary
 * but never puts ids in [ConsolidationPassResult.Changed.propositionsToDelete].
 *
 * ## Dual-threshold hysteresis
 *
 * Decay transitions use a two-threshold band to avoid status flapping:
 * - [staleThresholdH] (default `0.1`): a proposition goes `STALE` only when effective confidence
 *   drops below H.
 * - [recoveryThresholdS] (default `0.25`): the upper edge of the hysteresis band `[H, S)`.
 *   Revival out of `STALE` is reinforcement-driven (happens on ingest/reinforce), never
 *   sweep-driven. These thresholds are surfaced here for documentation and NoOp report text.
 *
 * ## Write boundary
 *
 * The [CollectorRunner] writes transitions itself inside [CollectorRunner.run]. To avoid a
 * double-write, this pass is report-only: its [ConsolidationPassResult.Changed] always carries
 * empty `propositionsToSave` and `propositionsToDelete`. The orchestrator gets nothing to
 * re-persist from this pass.
 *
 * @property collectorRunner The mark-and-sweep runner this pass delegates to.
 * @property staleThresholdH Lower hysteresis threshold (ACTIVE -> STALE below this). Defaults to `0.1`.
 * @property recoveryThresholdS Upper hysteresis threshold; revival is reinforcement-driven, not
 *   sweep-driven. Defaults to `0.25`.
 */
class DecaySweepPass @JvmOverloads constructor(
    private val collectorRunner: CollectorRunner,
    private val staleThresholdH: Double = 0.1,
    private val recoveryThresholdS: Double = 0.25,
) : ConsolidationPass {

    override val name: String = "decay-sweep"

    /**
     * Runs the collector's full mark-and-sweep over [contextId] and reports the outcome.
     *
     * The [propositions] snapshot is not used here — the runner queries its own ACTIVE candidates
     * from the repository it was built with. Returns [ConsolidationPassResult.NoOp] when nothing
     * decayed below H, a report-only [ConsolidationPassResult.Changed] (empty save/delete lists —
     * the runner already wrote) when transitions or hard-deletes occurred, or
     * [ConsolidationPassResult.Failed] if the runner threw.
     *
     * @param contextId The context whose ACTIVE propositions the runner evaluates.
     * @param propositions The orchestrator's snapshot — unused by this pass.
     */
    override fun run(contextId: ContextId, propositions: List<Proposition>): ConsolidationPassResult {
        return try {
            val result = collectorRunner.run(contextId, dryRun = false)
            if (result.applied.isEmpty() && result.hardDeleted.isEmpty()) {
                ConsolidationPassResult.NoOp(name, "no propositions below H=$staleThresholdH (recovery band up to S=$recoveryThresholdS)")
            } else {
                ConsolidationPassResult.Changed(
                    passName = name,
                    propositionsToSave = emptyList(),
                    propositionsToDelete = emptyList(),
                    skipped = result.skipped.size,
                    summary = "decay sweep: ${result.applied.size} -> STALE, ${result.hardDeleted.size} hard-deleted",
                )
            }
        } catch (e: Throwable) {
            // Match the catch breadth of the sibling passes (SessionConsolidationPass,
            // AbstractionPass): isolate ANY Throwable from the delegate into a Failed result so one
            // pass's failure never aborts the rest of the cycle.
            ConsolidationPassResult.Failed(name, e)
        }
    }
}
