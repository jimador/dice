# dice-ingestion

This module is the front door for getting source material into DICE. It defines the SPI for turning normalized text into propositions and ships a content-hash deduplication ledger so the same source isn't extracted twice.

Core design decision: this module never parses. External adapters (document connectors, web scrapers, etc.) extract text into an `IngestedArtifact` before calling in — core receives pre-extracted text only.

## What's here

**The handoff types**

- `IngestedArtifact` — a normalized unit of source material: `sourceId` (stable dedup key, must not be blank), a `SourceLocator` for provenance, `text` (pre-extracted, must not be blank), an optional `contentHash` (caller-supplied dedup key; computed by the handler when absent), a `trust: AuthorityTier` (defaults to UNKNOWN), and optional timestamps. Has a Java-friendly fluent builder: `IngestedArtifact.withSourceId("…").withLocator(…).withText("…")`.
- `IngestionBatch` — a list of `IngestedArtifact`s submitted together. The primary handoff surface; single-artifact ingestion is a convenience that wraps in a one-element batch. Factory: `IngestionBatch.of(vararg artifacts)`.

**The SPI**

- `IngestionHandler` — interface with one real method: `ingest(batch: IngestionBatch, context: SourceAnalysisContext): IngestionResult`. The single-artifact overload is a default that delegates to the batch path. Adapters implement this or delegate to `TextIngestionHandler`.

**The result types**

- `IngestionResult` — wraps `List<ArtifactOutcome>`. The `propositions` property flattens the `Ingested` outcomes into a flat list (unsaved — persistence is the caller's concern).
- `ArtifactOutcome` — sealed interface with three variants:
  - `Ingested(sourceId, propositions)` — newly extracted; carries the unsaved propositions.
  - `Deduplicated(sourceId, contentHash)` — content hash already seen; no extraction ran.
  - `Failed(sourceId, cause)` — extraction failed; the rest of the batch is unaffected.

**Deduplication ledger**

- `IngestionLedger` — interface: `seen(hash)`, `record(hash)`, `forget(hash)`, and `recordIfAbsent(hash)` (atomic check-and-claim; the default is non-atomic, override for concurrent use).
- `InMemoryIngestionLedger` — ships as the default. Backed by a `ConcurrentHashMap` key set. `recordIfAbsent` is truly atomic via `ConcurrentHashMap.add`. Survives only the process lifetime; supply a durable implementation for cross-session dedup.

**Shipped handler**

- `TextIngestionHandler` (`support/` subpackage) — the one shipped `IngestionHandler`. For each artifact it: (1) resolves the content hash (caller-supplied or SHA-256 of text), (2) atomically claims it via `ledger.recordIfAbsent` — short-circuits to `Deduplicated` if already seen, (3) bridges text to a `Chunk`, runs the `PropositionPipeline`, (4) stamps each returned proposition with a `ProvenanceEntry` carrying the artifact's locator. Failures release the claimed hash via `ledger.forget` so retries are not wrongly deduplicated. Processes a batch sequentially — intra-batch dedup relies on that ordering.

## Dependencies

- `dice` (core) — `Proposition`, `PropositionPipeline`, `SourceAnalysisContext`, `ProvenanceEntry`, `SourceLocator`, `AuthorityTier`.
- `embabel-agent-api` (provided) — `Chunk`, agent API types.
- `embabel-agent-rag-core` (provided) — `Retrievable`, supertype of `Proposition`.

## Gotchas

- Adapters must extract text before constructing `IngestedArtifact` — core never parses native formats.
- The default `InMemoryIngestionLedger` is process-scoped. Restart the process and it forgets everything; any re-submitted content will be re-extracted. Wire a durable ledger to prevent that.
- `TextIngestionHandler` processes batches sequentially. A parallel handler must supply its own atomic deduplication rather than relying on processing order.
- Propositions returned in `IngestionResult.propositions` are not yet persisted. The caller is responsible for saving them.
- `contentHash` in `IngestedArtifact` is caller-asserted, not verified. Pass a stable, content-derived hash; an unstable or wrong hash defeats deduplication.
