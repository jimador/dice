# Proposition lifecycle: trust, conflict, supersession, and decay

DICE keeps what it knows as **propositions** — small natural-language statements grounded in
source material, like "Alice works at Acme." This document isn't about the classes that store
them; you can read those. It's about the decisions behind how a proposition *behaves* over its
life: how it earns or loses trust, what happens when two of them disagree, when one quietly
replaces others, and why knowledge fades rather than being deleted. These are the choices you
can't recover by reading any single type.

## Lifecycle overview

A proposition is born **active** and stays that way for as long as it's believed and current.
From there a handful of things can happen to it, and the shape of those transitions is itself a
design decision — most exits are one-way, and almost none of them actually destroy the record.

```mermaid
stateDiagram-v2
    [*] --> ACTIVE : ingested
    ACTIVE --> PROMOTED : projected into the typed graph
    ACTIVE --> CONTRADICTED : a newer fact clashes with it
    ACTIVE --> SUPERSEDED : folded into a higher-level abstraction
    ACTIVE --> STALE : effective confidence decays past the floor
    STALE --> ACTIVE : reinforced when the fact is seen again
    STALE --> [*] : deliberately retired by hard delete
    CONTRADICTED --> [*] : kept for audit, no auto-revival
    SUPERSEDED --> [*] : kept for audit, no auto-revival
```

The rest of this document is the reasoning behind those transitions.

## Trust scoring is advisory

Every proposition can be scored for how much its *source* should be believed, but that score
never deletes, rewrites, or hides anything. It's advisory: it ranks, and the consumer decides
what to do with the ranking.

The reason is that destructive automation on a knowledge base is hard to undo and easy to get
wrong. A confident-sounding extraction from a sketchy source shouldn't silently erase a quieter
fact from a good one — it should just lose when something has to choose. So trust is a number a
query filter or a ranker can lean on, not a gate baked into the store.

This is why the shipped scorer is deliberately neutral (it trusts everything equally). The seam
exists so that real deployments opt *in* to a judgment model; nothing changes until they do.

## Source authority from provenance

When DICE does reason about trust, the dominant signal is **where a fact came from**, not how
confident the language model sounded. Sources fall into tiers: first-party records outrank named
external sources, which outrank derived or inferred material, which outranks "we don't know."
When a proposition has mixed grounding, the strongest source it can point to wins.

Two things drove this. First, provenance is a more honest trust signal than self-reported
confidence — a primary record is trustworthy for reasons that have nothing to do with how a
sentence is phrased. Second, "unknown" has to be a real tier and the fail-safe default, because
plenty of propositions arrive with thin grounding and we'd rather treat those cautiously than
flatter them.

## Conflict classification

DICE separates two things: working out how a new proposition relates to what's already stored, and
naming *what kind* of clash it is when they conflict.

The reviser does the first. When a proposition arrives, it classifies the relationship to the
existing facts and acts on it — merging an identical fact, reinforcing a similar one, demoting the
existing fact when the two contradict (its confidence is cut and its status set to contradicted),
recording a generalization, or storing an unrelated fact as new.

```mermaid
sequenceDiagram
    autonumber
    participant New as New proposition
    participant Reviser
    participant Store
    New->>Reviser: arrives for revision
    Reviser->>Store: find candidate matches
    Store-->>Reviser: closest existing propositions
    Reviser->>Reviser: classify the relationship
    alt identical
        Reviser->>Store: merge into the existing fact
    else similar enough
        Reviser->>Store: reinforce the existing fact
    else contradictory
        Reviser->>Store: demote the existing fact (cut confidence, mark CONTRADICTED)
        Note over Reviser: a conflict detector labels the kind —<br/>revision / contradiction / world progression
    else generalizes
        Reviser->>Store: record the more general fact
    else unrelated
        Reviser->>Store: store as a new fact
    end
```

A conflict detector does the second, and only for the contradictory case — it labels *why* the two
clash:

- **Revision** — the new statement is a more accurate version of the same fact (a correction).
- **Contradiction** — the two are mutually exclusive; one of them is wrong.
- **World progression** — both were true at different times; the world moved on (Alice changed
  employers).
- a **custom** kind, for domain-specific clashes the first three don't cover.

The label is recorded on the result for downstream consumers to use; the existing fact is demoted
either way. Capturing the kind matters because treating every disagreement as a flat contradiction
throws away the difference between a correction, a real conflict, and a fact that's simply newer.
The shipped detector is conservative — it labels every clash a contradiction — until a richer one
is wired in.

## Supersession vs. contradiction

These two look similar — both end with an older proposition stepping aside — but they mean
opposite things, and collapsing them would either lose nuance or wrongly discredit good facts.

```mermaid
flowchart TB
    subgraph contradiction [Contradiction · a clash]
        direction TB
        C1[A new fact arrives and conflicts] --> C2["The losing fact is demoted:<br/>confidence cut, marked CONTRADICTED"]
        C2 --> C3[Meaning: we no longer believe it]
    end
    subgraph supersession [Supersession · a summary]
        direction TB
        S1[Many related true facts accumulate] --> S2["They are folded into one<br/>higher-level proposition"]
        S2 --> S3[The sources are marked SUPERSEDED]
        S3 --> S4[Meaning: still true, just absorbed]
    end
```

**Contradiction** happens in the moment a new proposition arrives and is judged to conflict with an
existing one. The loser is demoted — its confidence is cut and it's marked contradicted. The
statement we're making is "this is no longer believed."

**Supersession** happens later and for the opposite reason. During background consolidation, a
cluster of true, low-level facts about the same thing gets abstracted into a single higher-level
proposition. The originals aren't wrong — they've been *absorbed* — so they're marked superseded
rather than contradicted. The statement is "there's now a better way to say all of this at once."

Two consequences fall out of keeping them distinct: each transition answers a different question
("was this wrong?" versus "is there a better summary now?"), and in both cases the original record
is kept for audit. We don't pretend we never believed something.

## Decay instead of deletion

A proposition's believability falls off over time, but the clock is anchored to the last time its
**content** changed — not to housekeeping touches like re-scoring trust or flipping a flag.
Re-deriving an old conclusion shouldn't look fresh, and re-scoring a fact shouldn't make it look
stale. So administrative edits leave the decay clock alone.

Decay moves a proposition toward **stale**, not toward the trash. The boundary uses two thresholds
— a fact has to fall well below the line to go stale and climb well above it to come back — so it
doesn't flap back and forth near the edge. And the only thing that genuinely revives a stale fact
is seeing it again (reinforcement), not the passage of time. Hard removal exists, but it's a
separate, deliberate step: the default preference is "cold" over "gone," because a knowledge base
that quietly deletes things is one you stop trusting.

## End-to-end walkthrough

Follow one fact through its life. It's ingested **active** and scored by the authority of its
source. It may get **promoted** when it's projected into the typed graph. Later a conflicting fact
arrives; the reviser demotes the older fact (its confidence cut and its status set to contradicted)
and a conflict detector labels what kind of clash it was. If nothing references the fact for a while its
effective confidence decays until it goes **stale**, where it waits to be either reinforced back to
active or eventually retired. Or, if many siblings about the same entity pile up, a consolidation
pass folds them into one abstraction and our fact is marked **superseded** — still true, now said
more concisely.

## Configurable behavior

Trust scoring, authority resolution,
conflict characterization, and the rules for status transitions are all pluggable. The defaults
that ship are intentionally conservative — neutral trust, provenance-based authority,
assume-contradiction, gentle decay — so the safe behaviour is the one you get out of the box, and a
real deployment is expected to swap in the judgment that fits its domain.
