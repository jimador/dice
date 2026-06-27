# dice-report

This module turns a set of propositions into human-readable output. It contains three independent projectors and the data types they produce. None of them touch the proposition store directly — the caller queries propositions and hands the list in.

## What's here

**Rationale** — explains why a proposition (or a group of related propositions) is believed.

- `RationaleProjector` — interface with two methods: `rationale(Proposition)` and `rationale(PropositionGroup)`.
- `LlmRationaleProjector` — the only shipped implementation. Calls the embabel-agent `Ai` handle with the `dice/explain_rationale` prompt template. Built via a fluent builder: `LlmRationaleProjector.withLlm(opts).withAi(ai)`. Embeds proposition text directly in the prompt — treat ingested content as untrusted (see indirect-prompt-injection note in the class KDoc).
- `RationaleArtifact` — the output: `text`, `sourcePropositionIds`, `confidence`. Implements `Projection` so it traces back to its source propositions. `decay` is hardcoded to 0.0 because rationale is regenerated on demand.
- `RationaleResponse` — the structured type the LLM returns (`rationale: String`, `confidence: ZeroToOne`). Clamped to [0.0, 1.0] before writing into the artifact.

**Structured report** — aggregates a list of propositions with no LLM or external call.

- `ReportProjector` — interface: `report(propositions, title): Report`.
- `StructuredReportProjector` — the shipped deterministic implementation. Groups by `PropositionStatus` and abstraction level, selects the top-N (default 5) by effective confidence descending, ties broken by id. Stable across calls on the same input. Create via `StructuredReportProjector.create(topN)`.
- `Report` — the output data class: `title`, `totalCount`, `byStatus`, `byLevel`, `topByConfidence`, `sourcePropositionIds`. `summary()` renders a concise text breakdown.

**Semantic links** — discovers non-obvious, multi-hop connections between entities.

- `SemanticLinkDiscoverer` — interface: `discover(propositions): List<SemanticLink>`. Operates purely over the given propositions — no LLM, vector store, or graph database.
- `TwoHopSemanticLinkDiscoverer` — the shipped implementation. Finds entity pairs that never directly co-occur in any proposition but share a common intermediary (A–X, X–B). Emits one `SemanticLink` per pair with the connecting entities merged and sorted. Fully deterministic; only ACTIVE propositions participate.
- `SemanticLink` — a `Projection` carrying `sourceEntityId`, `targetEntityId`, `connectingEntityIds`, a `LinkKind` (EXPLICIT / INFERRED / AMBIGUOUS), evidence `sourcePropositionIds`, a `ReviewStatus`, `confidence`, and an optional `rationale` string (filled later by a rationale projector if desired).
- `LinkKind`, `ReviewStatus` — supporting enums.

## Dependencies

- `dice` (core) — `Proposition`, `Projection`, `PropositionStatus`, `PropositionGroup`, `EntityMention`.
- `embabel-agent-api` (provided) — `Ai`, `LlmOptions`.
- `embabel-agent-rag-core` (provided) — `Retrievable`, supertype of `Proposition`.

Both `provided` deps are supplied at runtime by the consuming application; this module does not pull them transitively.

## Gotchas

- `LlmRationaleProjector` embeds raw proposition text into the LLM prompt. Sanitize ingested content upstream; do not grant rationale output undue authority.
- `TwoHopSemanticLinkDiscoverer` is fixed at two hops (one shared intermediary). It does not do multi-hop or weighted discovery.
- All three projectors are stateless and produce `Projection` values with `decay = 0.0` — they are recomputed on demand, not stored.
- The `StructuredReportProjector` sorts ties by `id` for a stable, reproducible order; changing proposition ids changes the tie-break output.
