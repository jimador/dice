# Codex Review Issues for PR 49

Source: adversarial review of `embabel/dice#49` (`feat/graph-backend-and-retrieval`).

## F-01: Discovery REST wiring is not safely auto-importable or context-safe

Severity: high

`DiscoveryController` is imported by `DiceRestConfiguration`, but the controller is only conditional on `PropositionStore`. Its constructor also requires `GraphQuery`, `ProjectionRecordStore`, and `CollectorRunner`. Storage autoconfiguration creates record stores, but does not create `GraphQuery` or `CollectorRunner`, so adding the optional REST config can break application startup for users who only have a proposition store.

The controller also injects a singleton `GraphQuery` and then constructs per-request `RetrievalRouter` instances with a path `ContextId`. That cannot make the injected `GraphQuery` follow the request path, and is unsafe if a user supplies a scoped or differently configured `GraphQuery` bean.

## F-02: Projection lineage can claim an edge was projected even when persistence failed

Severity: high

`NamedEntityDataRepositoryGraphRelationshipPersister` catches merge failures and returns aggregate `RelationshipPersistenceResult` counts, while `GraphProjectionService` records `ProjectionLifecycle.PROJECTED` for every successful projection result without checking whether the persistence step failed. A failed relationship write can therefore leave projection lineage and health reporting claiming an edge exists when it was not persisted.

## F-03: Repository-backed reconciliation adopts endpoint nodes, not the projected relationship

Severity: medium

`RepositoryBackedReconciler` adopts the first existing resolved entity mention ID when projecting graph relationships. That treats reused endpoint nodes as if the relationship artifact already existed. A newly created edge can be recorded as `ADOPTED` against an endpoint node instead of `PROJECTED` against the edge target reference, which makes projection health and `findByTargetRef` semantics misleading.

## F-04: Native graph-query capability has no context parameter

Severity: medium

`GraphQuery` routes to `GraphQueryCapable` native methods before applying the portable context-scoped query path. The native capability methods do not accept `ContextId` or a scoped `PropositionQuery`, so an adapter cannot honor the same context-isolation contract. `RetrievalRouter` can filter returned propositions after the fact, but it cannot prevent native traversal through foreign-context edges.
