# Handoff: Phase 1 (Step 4a→4b boundary) — design frozen, awaiting review + draft PR

**Paused:** 2026-06-15
**Phase:** 1 (Step 4a complete; Step 4b pending)
**Context level at pause:** warning
**Branch:** transactional-schema
**HEAD:** 57ffe11282 "Add initial design"
**Unpushed:** 0 commits

## Durable artifacts on disk
- `docs/adr/transactional-schema/_workflow/design.md` — frozen Phase-1 seed design (796 lines, 15 `##` sections, 4 Parts), committed `57ffe11282`, cold-read PASS. Single file, no design-mechanics companion.
- `docs/adr/transactional-schema/_workflow/research-log.md` — consolidated: `## Invariants and Test Requirements` (25 I-* invariants) + `## Delegated to implementation` (12 entries); `## Adversarial gate record` shows the Phase-0→1 gate CLEARED (iter-3 PASS).
- `docs/adr/transactional-schema/_workflow/design-mutations.md` — Mutation 1 (phase1-creation) logged.
- `docs/adr/transactional-schema/_workflow/reviews/research-log-adversarial-iter{1,2,3}.md` — the gate review trail.
- No `implementation-plan.md` and no `plan/track-N.md` yet — those are Step 4b's output.

## Pending decision
The user is **reviewing the frozen `design.md`** before authorizing the draft PR. When offered the draft-PR open (prefix `[YTDB-382]`), they chose **HOLD** to review first. Pending: (a) the review outcome — approve as-is, or request changes; (b) the draft-PR open.

## Verbatim re-present text
> Design authored, reviewed, frozen, committed (`57ffe11282`), pushed. Step 4a complete. The design is at `docs/adr/transactional-schema/_workflow/design.md` (796 lines, 15 sections, 4 Parts): Overview → 9 Core Concepts → Class Design + Workflow diagrams → Part 1 (transactional schema model) / Part 2 (index transactionality) / Part 3 (concurrency + locking) / Part 4 (schema-format migration). Mechanism detail stayed in the research log's delegated list and the future tracks. Review: mechanical clean, cold-read PASS, absorption complete (all 20 decisions + 25 invariants seeded, none invented); fixed one cold-read should-fix (the Workflow diagram had the four-lock acquisition order reversed against the deadlock-freedom proof).
>
> Two paths once you've reviewed `design.md`: (1) looks right → I open the draft PR (`[YTDB-382]` or your prefix), which ends Step 4a, then a fresh `/create-plan` session derives Step 4b (aggregator plan + per-track files, seeded from the frozen design); (2) want changes → I route them through `edit-design` (the design is frozen, so only the mutation discipline edits it — decision-shaped changes re-enter the adversarial gate first, wording changes go straight through).
>
> One thing to settle during Step 4b, not now: the populated-class index-build boundary (D12/F57) — loud-reject pointing at YTDB-1064 vs accept with a documented heap envelope. Left open inside Part 1's `Commit-time reconciliation`.

## Resume notes
- **CRITICAL ROUTING.** A fresh `/create-plan` session's Step 1c will see `design.md` committed-and-clean + no `implementation-plan.md` and **auto-resume into Step 4b** (plan derivation). Do NOT let it barrel into Step 4b before the user's design review is resolved. Order on resume: surface this handoff → let the user finish reviewing / state any changes → open the draft PR on their OK → only then derive Step 4b.
- **On approve-as-is:** open the draft PR (`gh pr create --draft --base develop`, title `[YTDB-382] Transactional schema operations` or the user's prefix, body distilled from the design Overview, `## Status` line noting `_workflow/` is removed in the Phase 4 cleanup). Then derive Step 4b: read `planning.md`, author `implementation-plan.md` (full-tier aggregator, tier line `full`) + one `plan/track-N.md` per track, seed the track `## Decision Log` records from the frozen design's D-records, run the Step-4b cold-read (`target=tracks`), commit `Add initial implementation plan`, push.
- **On changes requested:** route through `edit-design` (mutation discipline; design is frozen). The D15 review-hold window is OPEN (design presented, outcome PASS) — batch findings per `create-plan` Step 4 review-hold batching; decision-shaped findings re-enter the Phase-0→1 adversarial gate before applying.
- **Do NOT redo:** the consolidation pass (`c455ca35cf`), the 3-iteration adversarial gate (CLEARED, `7109eb7b01`), the design authoring + cold-read (`57ffe11282`) — all committed and pushed. Do NOT re-attack settled D1-D20 / F1-F129 ground or the cleared gate.
- **Env note:** model `fable` (D14's full-tier adversarial-spawn choice) is **unavailable in this environment** — every gate and cold-read spawn this session fell back to `opus` (session default). Re-confirm fable availability next session before relying on the D14 model pin; if still unavailable, the opus fallback stands.

## Review-hold queue (D15)
**Presented artifact:** design.md
**Presentation outcome:** PASS (Step-4a cold-read passed; `57ffe11282`)
**Escape-hatch findings already processed this hold:** none

Queued findings (process as one batch on resume — do NOT re-raise to the user):
- [clarification] design.md "Tx-local index overlay" section: tighten the loose "force-rebuilt on every mid-transaction create/drop" to **index** create/drop (`createIndex` / `dropIndex`). I-P2 already pins it precisely; the design prose reads ambiguously (a reader took it for class create/drop). Wording-only, no decision change. — raised during the design review, re: the overlay force-rebuild sentence.
- [clarification] design.md cross-reference: in the "Tx-local index overlay" section, make explicit that a tx-created index's engine entries come from the commit-time **re-derivation** (Part 2 "Index build and query-usability" / D12 / I-P4), NOT from the per-tx `ClassIndexManager` tracking the force-rebuild surfaces. The F66 BLOCKER resolution made re-derivation the authoritative source for tx-created indexes, so I-P2's "entries ride the per-tx key-entry tracking" framing applies to pre-existing indexes only. An implementer who reads it literally re-introduces F66. Carry the same disambiguation into the index track's decision records at Step 4b (where I-P2/I-P4 become track DRs). ESCALATION: if realized by rewording the research-log I-P2 invariant statement rather than adding a design.md cross-reference clause, it becomes [decision] and must clear the adversarial gate first (the in-mutation escape valve also catches this: a decision-shaped finding exits to the gate before any fix). — raised during the design review, re: forceSnapshot/tracking vs commit-time re-derivation filling a tx-created index.
- [clarification] design.md "Mutex lifecycle and the permit handshake" section (Part 3): this is a comprehension expansion, not a one-word fix. Fold the engage/teardown handshake in as an interleaving diagram (ASCII/Mermaid) and gloss the load-bearing entities the prose names without defining — the `Semaphore(1)`-not-`ReentrantLock` choice, the `(owning session, acquire ordinal, acquiring thread)` ownership triple and which member is the release key vs diagnostic-only, the session-keyed compare-and-clear, the volatile teardown-intent mark (a dedicated flag, not `STATUS.CLOSED`), `checkOpenness`, the Dekker store/load pairing. Decisions/invariants are UNCHANGED (D7, I-handshake-1, I-C3); this expands the explanation of already-settled content for a reader without the research log. Invoke the §"Section length cap exception" for protocol mechanism. A comprehensive chat explanation + ASCII handshake diagram were drafted this session — reuse them. — raised during the design review, re: terse Part-3 protocol prose (see the reflection on what caused the terseness).
- [clarification] design.md "The freezer gate" section (Part 3): same Part-3 compression issue. Fold in an interleaving diagram and gloss the per-window cases the prose compresses — operator park-mode vs throw-mode freeze, transient internal quiesce, the operator-arm cut-and-unpark, the kind-aware park-decision check, the wake bound. Decisions/invariants UNCHANGED (I-freezer-1); expand the explanation for a reader without the research log and invoke the section-cap exception. — raised during the design review, re: terse Part-3 protocol prose.
