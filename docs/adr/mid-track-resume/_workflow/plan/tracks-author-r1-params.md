# design-author params — create-plan Step 4b (track authoring), round 1

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/design.md
- round: 1

## What to write

Two track files already exist as skeletons with the line-1 `<!-- workflow-sha: ... -->`
stamp and all 15 section headings with placeholder comments:

- `plan/track-1.md` — Track 1: Ledger `substate` primitive, dual-path resolution, wrap-fix, tests, grammar
- `plan/track-2.md` — Track 2: Wire the `substate` append sites across the resume protocol

**Edit** these files to fill the prose. **Never touch line 1** (the stamp) or the
`# Track N:` title on line 2. Fill the Phase-1 plan-at-start sections only:
`## Purpose / Big Picture`, `## Context and Orientation`, `## Plan of Work`,
`## Interfaces and Dependencies`, `## Decision Log`, `## Invariants & Constraints`,
`## Validation and Acceptance`. Leave the continuous-log sections
(`## Surprises & Discoveries`, `## Outcomes & Retrospective`, `## Episodes`,
`## Artifacts and Notes`) and the Phase-A placeholders (`## Concrete Steps`,
`## Idempotence and Recovery`) as they are. Leave `## Progress` as the four-item
checklist already present.

## Grounding

This is **bash + markdown workflow machinery**, NOT Java. The seed is the frozen
`design.md` at `design_path` (the authoritative decision source) plus the research
log. Ground the prose in the **live code**:

- `.claude/scripts/workflow-startup-precheck.sh` — `roster_scan` (line ~1306),
  `determine_c_substate` (~1713, calls `roster_scan` ~1724), `determine_state_from_ledger`
  (~1778, calls `determine_c_substate` ~1813), `ledger_tail_value` (~1675),
  `reject_bad_ledger_value` (~1562) and its validation block (~1600-1606),
  `append_ledger` (the `LEDGER_*` vars + the pre-`categories` append construction),
  and the ledger grammar comment in the script header.
- The append sites: `track-review.md:596,1048` (A→C, `--append-ledger --phase C --track N`);
  `track-code-review.md:1409` (track advance, `--track N+1`), `:1411` (last track, `--phase D`),
  and the pre-approval code-review-complete commit around `:743`; `step-implementation.md`
  §Phase B Completion (currently marks `Step implementation [x]` then ends with NO commit
  and NO append); `inline-replanning.md` (`:169` `--tier`, `:249` ESCALATE `--phase 0`).

mcp-steroid is **NOT reachable** this session (no Java symbols here anyway) — use
`grep`/`Read`, and note the reference-accuracy caveat in your summary.

## Settled decomposition (do not re-derive the split — render it as cold-readable prose)

### Track 1 — the read-side primitive (~4 in-scope files; merge-candidate, justification below)

- **BLUF:** A finished track resumes into code review instead of back into Phase B,
  because the precheck reads the within-track sub-state from the phase ledger — with
  a wrap-fixed roster parse kept as the fallback.
- **Intro paragraph:** restate the plan checklist entry (land the read side: the
  `substate` ledger key + track-scoped reader, the dual-path resolution preferring the
  ledger and falling back to a wrap-fixed roster parse, and the full test surface;
  this track also delivers the literal YTDB-1134 wrap fix and lands the primitive
  dormant — correct and mergeable with no append site wired, because an empty
  `substate` read routes to the fallback).
- **In-scope files:** `.claude/scripts/workflow-startup-precheck.sh`,
  `.claude/scripts/tests/test_workflow_startup_precheck.py`,
  `.claude/workflow/conventions.md` (Phase-ledger glossary key set),
  `.claude/workflow/conventions-execution.md` (§2.1 — note the ledger `substate` as the
  within-track resume signal). **Out-of-scope:** the append-site docs (Track 2),
  `workflow.md` step-5 routing (unchanged — the four slugs are identical).
- **Plan of Work (the edits):** (1) add `substate` to the validated bare-token set in
  `reject_bad_ledger_value`'s call block; (2) add a `--substate` arg case filling
  `LEDGER_SUBSTATE`; (3) add the pre-`categories` append line
  `[ -n "$LEDGER_SUBSTATE" ] && line="$line substate=$LEDGER_SUBSTATE"`; (4) add the
  track-scoped reader `ledger_tail_value_for_track <key> <track>` (keeps the last
  `substate` on a line whose `track=` equals the active track); (5) in
  `determine_state_from_ledger`, read the track-scoped `substate` before calling
  `determine_c_substate` — emit it directly when non-empty, fall through to
  `determine_c_substate` when empty (the pre-this-change-ledger signal); (6) fix
  `roster_scan` to join each roster entry with its continuation lines before reading
  the status checkbox (terminators: next column-0 `N. ` line, next `## ` heading, EOF;
  keep the fenced-code/blockquote guards); (7) update the ledger-grammar comment in the
  script header; (8) `conventions.md` Phase-ledger glossary — add `substate` to the
  `{phase, track, tier, categories, s17, paused}` key set (it becomes the seventh);
  (9) `conventions-execution.md §2.1` — note the ledger `substate` now owns the
  within-track resume routing signal; (10) the test surface (5 groups): ledger path
  (one per committed slug + a track-scoping case), fallback path (empty `substate`),
  dual-path parity (D2 mandate — strip the ledger `substate` for the fallback arm),
  wrapped-roster regression (the issue's acceptance criterion), `--substate` append
  validation (space/newline → exit 3 + stderr).
- **Decision Records this track owns (seed full inline from design.md):**
  - **D1 (read side):** Source the State-C sub-state from a track-scoped `substate`
    ledger key. The read is track-scoped because the ledger is last-value-wins across
    the whole file. State the append cadence as the contract Track 2 implements (the
    four committed boundaries; `failed-step` excluded because its writes are
    uncommitted, working-tree-reconciled). Implemented in: this track (read side);
    append sites in Track 2. Add `**Full design**: design.md §"Ledger grammar and the
    script function structure"` and `§"The dual-path sub-state resolution"`.
  - **D2:** Drop `section-discrepancy` from routing; keep and fix `roster_scan` as the
    fallback. Full four-bullet form (alternatives: fully retire `roster_scan` — breaks
    mid-flight + pre-ledger plans; keep `section-discrepancy` on the ledger path — dead
    code there). `**Full design**: design.md §"The dual-path sub-state resolution"`,
    §"The wrapped-roster fallback fix".
- **Invariants & Constraints (testable):** S1 (track-scoped read — verified by the
  track-scoping ledger-path test); S2 read-behavior (empty `substate` → roster fallback
  — verified by the fallback-path test); S3 (dual-path parity — verified by the parity
  test); S5 (wrap-fixed fallback correctness — verified by the wrapped-roster regression
  test); S6 (loud-reject append validation — verified by the append-validation test).
  Non-testable constraints (state as constraints, not invariants): the phase enum
  `{0,A,C,D,Done}` is unchanged; append atomicity and the existing keys are unchanged;
  this is a §1.7-staged workflow-modifying change — all edits land under
  `_workflow/staged-workflow/.claude/...` and promote in Phase 4 (state this in Context
  and Orientation or as a constraint, not as a testable invariant).
- **Validation and Acceptance:** all five test groups pass; the wrapped-roster
  regression (YTDB-1134's literal acceptance: count a wrapped step) passes; dual-path
  parity holds.
- **Sizing justification (REQUIRED — ~4 files is below the ~12 floor, a merge
  candidate):** the split is at the core→consumer dependency boundary. Track 1 is the
  independently-mergeable primitive that also delivers the literal YTDB-1134 wrap fix
  and lands the dormant ledger primitive (safe alone: with no append site wired, every
  `substate` read is empty → always-fallback → pre-change behavior plus the wrap fix).
  Folding Track 2 in would mix a tested bash/python primitive with resume-protocol
  prose edits and forfeit landing and validating the primitive before the wiring. Put
  this in `## Interfaces and Dependencies` (or `## Plan of Work`).
- **Track-level Mermaid diagram** (3+ components → include one in
  `## Context and Orientation`): the script function structure + dual-path resolution
  (`append_ledger`/`--substate` → ledger → `ledger_tail_value_for_track` →
  `determine_state_from_ledger` → empty? → `determine_c_substate` + wrap-fixed
  `roster_scan`).

### Track 2 — the append-site wiring (~4 in-scope files; merge-candidate, justification below)

- **BLUF:** Every within-track boundary records its sub-state on the ledger, so the
  Track 1 ledger-primary read drives the resume instead of the roster.
- **Intro paragraph:** restate the plan checklist entry (activate the primitive: a
  `--substate` append at each of the four committed boundaries plus an inline-replan
  revert; the Phase B→C boundary gains a new Workflow-update commit to carry its append;
  the other three ride commits already in the protocol).
- **In-scope files:** `.claude/workflow/track-review.md`,
  `.claude/workflow/step-implementation.md`, `.claude/workflow/track-code-review.md`,
  `.claude/workflow/inline-replanning.md`. **Out-of-scope:** the script + tests +
  grammar (Track 1); `workflow.md` step-5 routing (unchanged — slugs identical).
- **Depends on:** Track 1 (the `--substate` flag, the key, and the reader must exist
  first; the appends call a flag Track 1 introduces).
- **Plan of Work (the four committed boundaries + the replan revert):**
  | Boundary | substate | Rides commit |
  |---|---|---|
  | Phase A decomposition complete | `steps-partial` | A→C commit (`track-review.md:596,1048`) |
  | All steps complete (Phase B→C) | `steps-done-review-pending` | a NEW Phase-B-complete Workflow-update commit (`step-implementation.md` §Phase B Completion — today no commit/append; this adds one staging the `Step implementation [x]` flip + the append, symmetric with A→C, incidentally fixing the uncommitted-flip wart) |
  | Code review passed (pre-approval) | `review-done-track-open` | the pre-approval code-review-complete commit (`track-code-review.md` ~:743), NOT the post-approval completion commit |
  | Track complete → next track | `decomposition-pending` (for track N+1) | the track-completion / track-advance commit (`track-code-review.md:1409`) |
  Plus: an inline replan that adds steps to a review-pending track appends
  `substate=steps-partial` to revert (`inline-replanning.md`).
- **Edge cases to state:** a single-step track skips code review (no one appends
  `review-done-track-open`); the track-completion append carries it past review; a `[~]`
  skipped step counts toward all-steps-complete like `[x]`.
- **Decision Records this track owns (seed full inline from design.md):**
  - **D1 (append cadence side):** the four committed boundaries and the commits they
    ride; the new Phase-B-complete commit; `failed-step` excluded. Reference D1's read
    side in Track 1 (`**Full design**: design.md §"Resume state machine and the
    per-track substate lifecycle"`). Implemented in: this track (append sites).
  - **D3:** Track-advance append sets `substate=decomposition-pending` explicitly, so an
    empty `substate` on a `phase=C` track means exactly "pre-this-change ledger." Full
    four-bullet form (alternative: default an empty read to `decomposition-pending` —
    conflates "not decomposed" with "append lost / old ledger," reviving the
    silent-default failure mode). State the **D1+D3 wiring-pair constraint**: both append
    sites (A→C `steps-partial` and track-advance `decomposition-pending`) MUST land
    together; a half-implementation leaves a `phase=C` track with no `substate` and
    silently triggers the fallback when it should not. Both append sites are in this
    track, so the constraint is satisfied within Track 2.
    `**Full design**: design.md §"The dual-path sub-state resolution"`,
    §"Decision records".
- **Invariants & Constraints:** S2 closure (on a current-scheme ledger every `phase=C`
  track carries an explicit `substate` — the A→C, track-advance, and two Phase-C appends
  cover every `phase=C` track; verified by the closure argument + Track 1's fallback-path
  test); S4 (committed-boundary cadence — every `substate` append rides a commit that
  survives `git reset --hard HEAD`; note S4 has NO direct unit test — it lands in prose,
  verified by the append-cadence table review and Track 1's `steps-done-review-pending`
  ledger-path test). Constraint: the D1+D3 wiring pair lands together (above);
  §1.7-staged.
- **Validation and Acceptance:** the append cadence table holds at each boundary; the
  new Phase-B-complete commit is symmetric with the A→C commit; the S2 closure invariant
  holds (every `phase=C` track gets an explicit `substate`).
- **Sizing justification (REQUIRED — ~4 files is below the ~12 floor, a merge
  candidate):** the split is at the core→consumer dependency boundary. This track is the
  doc-only append-site wiring that depends on Track 1's `--substate` flag and cannot
  merge before it (it calls a flag Track 1 introduces). Folding it into Track 1 would
  mix resume-protocol prose with the tested primitive and forfeit Track 1's independent
  landing and validation. Put this in `## Interfaces and Dependencies` (or
  `## Plan of Work`).

## Reminders

- House style applies (`.claude/output-styles/house-style.md`): BLUF lead, no banned
  sentence/analysis patterns, no padding. The track files are the durable human-facing
  carrier — write so a mid-level developer can rebuild each mechanism from the file alone.
- Return only a thin summary (what you drafted, where, open questions) — never the
  drafted track content (the by-reference return contract).
