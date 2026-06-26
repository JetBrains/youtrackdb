# Plan Review

- Plan review (consistency + structural) — passed. Consistency review passed at
  iteration 2; structural review returned PASS at iteration 1 and its mechanical
  fixes were gate-verified at iteration 2. Tier `full`; workflow-modifying
  (§1.7-staged). The "code" under review is the `.claude/**` workflow machinery
  (Markdown/shell/python), verified by exact `Read`/`Grep`, not Java PSI.

**Auto-fixed (mechanical)** — structural review S1–S4, all gate-verified:

- **S1** — trimmed Track 1 `D10` (Phase-ledger schema delta) from ~36 to ~25
  lines: the four-field schema sub-list became a pointer to the design Data
  model table; the three-caveat Risks block compressed to the load-bearing
  resume-collision risk plus a pointer to `## Invariants & Constraints` (which
  already states torn-append safety and the track-scoped read).
- **S2** — trimmed Track 2 `D7` (bugs/concurrency ownership) from ~40 to ~27
  lines: the three-item routing sub-list compressed to a pointer to design
  Part 6, keeping the cognitive-mode boundary, symmetric tiebreak, and triage
  backstop.
- **S3** — trimmed Track 1 `D8a` and Track 2 `D8b` from ~33 to ~27 lines each:
  rejected-alternatives prose compressed to a pointer to design §"Artifact
  derivation" (the seed carries the full derivation).
- **S4** — synced the plan: Track 1 Scope line `~12`→`~13 files` naming
  `implementation-review.md`; Track 2 Scope line `~19`→`~20 files` naming
  `review-iteration.md`; the two Component Map bullets updated to match the
  CR1/CR3 additions below.

**Escalated (design decisions)** — consistency review CR1–CR5, all resolved by
the user via the design's zero-file-straddle ownership model (each file edited
by exactly one track; Track 2 depends on Track 1). No `design.md` edit was
needed — the design already specifies the behavior by axis (D8/D10); the tracks
only needed to enumerate the missing files. `design.md` stays frozen.

- **CR1** (blocker) — `implementation-review.md` §"Tier-driven pass selection"
  reads the ledger `tier` Track 1 removes, and was in no track's scope.
  Resolved → **Track 1**: added to in-scope and Plan-of-Work step 3, re-keyed to
  `design_gate` + plan-presence/track-count.
- **CR2** — `workflow.md` §"Final Artifacts (Phase 4)" carries a stale per-tier
  adr carrier table. Resolved → **Track 1** (owns the file): re-key §"Final
  Artifacts" to the axis-derived carrier per Track 2's D8b.
- **CR3** — `review-iteration.md` §"Finding ID prefixes" (the prefix-family
  owner) was named only as a contract, not in any edit scope. Resolved →
  **Track 2** (owns the roster split): added to in-scope and Plan-of-Work
  step 4 (retire `BC`, add the two new rows, keep `TB`/`TC`).
- **CR4** — `conventions.md` §"Per-tier artifact set" adr row encodes Track 2's
  D8b predicate inside a Track-1-owned table; coupling was unstated. Resolved →
  **Track 1** (owns the file): stated the adr row encodes `adr ⟺ ∃ track ≥
  medium`; D8a note clarifies Track 1 authors it, Track 2 owns its computation.
- **CR5** — `design-review.md` `tier=full` fidelity gate is a design-presence
  proxy that should read `design_gate`; Track 2's stated duty did not cover it.
  Resolved → **Track 2** (owns the file): extended its `design-review.md` duty
  to re-key the gate to `design_gate=yes`.
