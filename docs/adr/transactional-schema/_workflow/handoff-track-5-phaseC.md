# Handoff: Phase C — Track 5 track-completion approval (user pre-selected Review mode)

**Paused:** 2026-07-02
**Phase:** C
**Context level at pause:** warning
**Branch:** transactional-schema
**HEAD:** d8da8bb1d1a9e6401b1bbc1c238c35e09fa82bb9 "Record Track 5 track-review outcomes"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `docs/adr/transactional-schema/_workflow/plan/track-5.md` — Progress carries all review-loop entries and the code-review `[x]`; `## Outcomes & Retrospective` carries the full track-review record (33 findings, 2/3 iterations, all VERIFIED, not-applied list, inspection-pass outcome, burden figure); `## Surprises & Discoveries` carries the two iteration-2 discoveries (deferred-create `ignoreNullValues` divergence; null placeholder in committed `collectionsToIndex`).
- `docs/adr/transactional-schema/_workflow/plan/track-5/reviews/*-track-iter1.md` — the nine §2.5 review files (committed `74339a0cf3`, `2034325cc0`).
- Commits `cd50c1ed16` (Review fix: harden index-commit failure paths) and `5ff107dd8a` (Review fix: tighten commit path and test precision) — the two fix iterations, both gate-checked VERIFIED.
- YTDB-1192 — reflection issue for this session (already filed; do not re-run reflection for the paused session's frictions).

## Pending decision

Track 5 track-completion approval panel (Approve / Review mode / ESCALATE). **The user has pre-selected Review mode**: on resume, render the completion summary below, then enter the conversational refinement loop per `review-mode.md` § Flow immediately — collect observations across turns, classify silently (`FIX_FINDING` / `QUESTION`), and surface the accumulated set on the completion signal. `FIX_FINDING` items spawn a fresh implementer (`level=track`, `mode=FIX_REVIEW_FINDINGS`, Completion spawns are budgetless); note the standing user directive that high-risk implementer spawns run on the Fable 5 model. After Apply, re-compile the episode against current HEAD and re-render the three-option panel.

## Verbatim re-present text

**What was built** (4 steps, 0 failed, all `risk: high`): the index half of transactional schema plus the tx-aware snapshot. An index created or dropped inside a transaction lives in a definition-only `IndexOverlay` (created / dropped / rename / membership categories) resolved through a new per-session routing seam; its engine is built at commit inside the exclusive-locked window (lock-free scan + final-state re-derivation, empty-source v1 bound with a loud YTDB-1064 rejection); the shared-map publish defers past `commitChanges`. `SchemaProxy.makeSnapshot()` now resolves the tx-local schema during a schema/index tx, so `EntityImpl.validate()` enforces same-tx classes, types, and rules (I-P5, D21). Step 4 (mid-Phase-B split, user-approved) carries provisional collection ids (`<= -2`) in RIDs end-to-end so a same-tx query returns the transaction's own rows; the commit rewrites them to real ids before the working set and asserts none survive (I-A2).

**Track-level code review**: 9 dimensional reviewers over the cumulative diff → 33 findings (0 blockers, 10 should-fix, 23 suggestions), cleared in 2 of 3 iterations, every gate-check verdict VERIFIED.

- Iteration 1 (`cd50c1ed16`) — the should-fix set: closed the Gremlin plan-cache schema-tx staleness hole (`GqlExecutionPlanCache` get/put bypass, mirroring the YQL fix from Step 4); two failure-path undo gaps in commit-time index reconciliation (phantom engine registration when wiring throws; unreverted eager membership when enrollment throws — both I-A4 holes); out-of-window asserts on the five commit-window primitives; five test-gap closures (I-P5 constraint branches, UNIQUE dup-key build, membership removal, membership-revert arm, backward-scan order, stuck-thread timeouts).
- Iteration 2 (`5ff107dd8a`) — the 14 suggestion items: test-falsifiability tightenings, the O(R) provisional-rewrite guard, exact engine-file-stem matching, honest test names/Javadoc, crash-deferral breadcrumb, durable reload arms, two-index-one-commit and multi-value/null-key build tests, a bounded concurrent membership reader.
- A pre-PR IntelliJ inspection pass scoped to the changed ranges found nothing actionable (2 production DataFlowIssue hits are invariant-guarded commit-path arguments; the rest is test-idiom noise and cosmetic casts).

**Not applied, with reasons** (8): a crash+WAL-replay IT for the commit-time engine lifecycle (two `@Ignore` breadcrumbs in code defer it to the IT layer); a deterministic mid-publish reader test (the accepted D19 best-effort publish, YTDB-1101/Track 7 boundary); five pre-existing or accepted-shape observations (latent `IndexAbstract` reload fallback, `ChangeableRecordId` wrong-field guard, commit-branch nesting, unused-until-Track-6 rename category, accepted D21 O(N²) interleaved DDL/DML).

**Plan corrections**: none — nothing routed to another track.

**New discoveries for follow-up triage** (from the fix iterations, not yet filed anywhere durable):
1. A tx-created (deferred) index never applies the `ignoreNullValues` config — it keeps the constructor default `true` and silently skips null keys, while an identical committed-path create indexes them. Blast radius widens when property-create de-guards.
2. The membership ripple for a tx-created subclass under an indexed parent records a null placeholder that rides into the committed index's `collectionsToIndex` set.

**Review burden (recorded)**: the reviewed cumulative diff reached ~7,000 insertions (~4,200 code-only at review start), well past the ~4,000 advisory threshold and flagged after Steps 2, 3, and 4 — this track was a retroactive split candidate; the episode records it to calibrate future planning.

**Standing reminders**: `MetadataWriteMutexTest` stays branch-RED (Track 3/7 merge-blocker, unchanged); `SchemaCommitReconciliationTest` joined the same disk-profile promotion-read flake family (isolated runs pass). Coverage remains CI-arbitrated (host cannot run the coverage profile).

**Commits**: 21 on the track (`a33f0aa2c6..e74048de2f`) plus the outcome-record commit `d8da8bb1d1`, all pushed; tree clean. On approval the plan-file Track 5 entry collapses to intro + episode + track-file pointer.

## Resume notes

- Do NOT redo: the nine dimensional reviews, both gate-check fan-outs (all PASS, iteration count 2/3 in Progress), the inspection pass, and the reflection (YTDB-1192 filed). No plan corrections were needed. The staged `/tmp` diff files from the paused session are gone — regenerate via Phase C Startup steps 7–8 only if a FIX_FINDING implementer round needs a fresh fan-out.
- Base commit for any implementer spawn: `54000d904b4daf34f9e1c3488ac1f549e2401859` (verified ancestor of HEAD).
- On user approval (after Review mode concludes): compile the track episode from the track file's Outcomes/Surprises/Episodes plus the review record above, write it to the plan file, collapse the Track 5 checklist entry, mark `[x]`, commit `Mark Track 5 complete`, push, remove this handoff + PAUSED marker + memory cross-reference in the resolution flow.
- On fixes requested (the expected path — Review mode): follow `review-mode.md` § Flow and § Completion FIX_FINDING outcome mapping; implementer spawns unnamed, `model: fable`, static-review hazards per the track file's session notes (no concurrent Maven; targeted `-Dtest=` only; coverage CI-arbitrated).
- Follow-up triage to raise during Review mode if the user does not: whether to file the two new discoveries as YTDB issues.
