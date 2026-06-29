<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: R1, prior_sev: blocker,    verdict: VERIFIED, cert: "#### Verify R1 "}
  - {id: R2, prior_sev: should-fix, verdict: VERIFIED, cert: "#### Verify R2 "}
  - {id: R3, prior_sev: should-fix, verdict: VERIFIED, cert: "#### Verify R3 "}
  - {id: R4, prior_sev: should-fix, verdict: VERIFIED, cert: "#### Verify R4 "}
  - {id: R5, prior_sev: suggestion, verdict: VERIFIED, cert: "#### Verify R5 "}
  - {id: R6, prior_sev: suggestion, verdict: VERIFIED, cert: "#### Verify R6 "}
overall: PASS
flags: [CONTRACT_OK]
-->

# Track 2 — Risk review gate-verification (iteration 2)

All six iteration-1 findings (1 blocker, 3 should-fix, 2 suggestion) were ACCEPTED and the
fixes landed at the planning level. The track now names every concrete edit the findings
called under-described and asserts each in `## Validation and Acceptance` as a repo-wide
`.claude/**` grep. The two scope-correctness facts the findings turned on still hold in the
tree — the staged `workflow-startup-precheck.sh` rejects `--tier` (R1 premise), and the live
roster / `tier`-read references R2/R3/R4 flagged are still present in the develop-state live
tree (expected: §1.7 staging keeps the live workflow at develop-state until the Phase-4
promotion, so the track's job is to *enumerate* those edits, which it now does). No new
finding surfaced; no fix introduced a regression. Overall **PASS**.

This is workflow-prose under `s17=workflow-modifying` — the criteria are rule coherence,
instruction completeness, prompt-design soundness, and breakage of dependent prompts at the
staged→live promotion, not Java/WAL/crash safety.

## Findings

<!-- Pure verification pass: no new findings. findings: 0. -->

## Verification certificates

#### Verify R1: `inline-replanning.md` D11/D12 writes the dropped `--tier` flag
- **Original issue**: `inline-replanning.md:169` runs `workflow-startup-precheck.sh
  --append-ledger --tier <new-tier>` — a flag Track 1 removed from the precheck — so after the
  Phase-4 promotion ships the staged precheck live, the first mid-flight ESCALATE tier upgrade
  fails the flag-parse (`exit 2`) and the upgrade never propagates. Plan-of-Work step (6)
  under-described it as a one-clause "tier-escalation path → complexity" re-key.
- **Fix applied**: Plan of Work step (6) now names the literal write — "line ~169 runs
  `workflow-startup-precheck.sh --append-ledger --tier <new-tier>`, a flag Track 1 removed, so
  after promotion that invocation `exit 2`s mid-escalation" — and directs the whole D11/D12
  "tier upgrade rides ESCALATE" mechanism (materialize-then-write ordering, the ledger append,
  the "every Phase-2/3A/4 selector reads the `tier` field ledger-first" prose) to be
  re-expressed in axis terms (a `design_gate` flip and/or a per-track tag raise written through
  the new flags), explicitly **not** a mechanical `tier`→`complexity` search-replace. The
  `## Interfaces` `inline-replanning.md` entry now calls it the "single live `tier`-**writer**
  (the D11/D12 `--append-ledger --tier` call at line ~169 that `exit 2`s post-promotion)".
- **Re-check**:
  - Track-file location: Plan of Work step (6) (lines 405-416); `## Interfaces`
    `inline-replanning.md` entry (lines 510-513); `## Validation and Acceptance` "No surviving
    tier read or write" line (470-474).
  - Codebase corroboration: `inline-replanning.md:141` ("Tier upgrade rides this same path
    (D12)") and `:169` (`--append-ledger --tier <new-tier>`) confirm the live writer exists; the
    staged `workflow-startup-precheck.sh` accepts only `--design-gate / --tracks /
    --phase1-complete / --reconciled-tag` (grep for `--tier` over the staged script returns no
    argument branch), confirming the post-promotion `exit 2`.
  - Current state: the blocker is resolved at the planning level — the track requires the
    concrete `--tier`-write removal and asserts its absence in acceptance ("No promoted file …
    runs `--append-ledger --tier`; … the `inline-replanning.md` `--tier` writer … re-key[s] onto
    the complexity axes — verified by a grep over the promoted set").
  - Criteria met: instruction completeness (the largest single edit is named, not a prose
    label), breakage-of-dependent-prompts (the promotion can no longer ship a workflow that
    calls a rejected flag because acceptance greps for it).
- **Regression check**: checked that the axis re-expression does not conflict with Track 1's
  ledger schema — step (6) keys the rewrite to the flags Track 1 added (`--design-gate`,
  `--reconciled-tag`), which the staged precheck accepts; no new dangling write introduced.
  Clean.
- **Verdict**: VERIFIED

#### Verify R2: removed-agent names in three out-of-scope live files
- **Original issue**: `execute-tracks/SKILL.md:109`, `review-workflow-consistency.md:63`, and
  `review-workflow-instruction-completeness.md:24` carry removed-agent names but sit outside the
  track's in-scope set, so the "five mirror sites" acceptance would pass while three live
  references dangle.
- **Fix applied**: the in-scope list now adds all three files (lines 529-538) with explicit
  `*(Added at Phase A: out-of-Track-1-scope dangling-reference site; Track 2 is the last track,
  so no later track exists to own it.)*` annotations, plus `dimensional-review-gate-check.md`
  (the `BC3` example). Step (4)'s sweep is now a "**repo-wide sweep over all `.claude/**`**"
  naming each of these four files. The dangling-ref acceptance and the invariant are repo-wide
  `.claude/**` (lines 464-469, 623-629).
- **Re-check**:
  - Track-file location: `## Interfaces` in-scope additions (529-541); Plan of Work step (4)
    (367-390); `## Validation and Acceptance` "No dangling roster references" (464-469);
    `## Invariants & Constraints` last bullet (623-629).
  - Codebase corroboration: the three live references confirmed present at
    `execute-tracks/SKILL.md:109`, `review-workflow-consistency.md:63`,
    `review-workflow-instruction-completeness.md:24` — exactly the files the in-scope list now
    names; they remain live because §1.7 staging holds the live tree at develop-state until
    promotion.
  - Current state: the should-fix is resolved — the references are no longer outside scope; the
    track owns their re-key and asserts zero hits repo-wide.
  - Criteria met: rule coherence (the consistency reviewer greps the whole `.claude/` tree; the
    track now matches that scope), instruction completeness.
- **Regression check**: confirmed the four added in-scope files are genuine references (each
  grep hit is a functional name, not Javadoc/illustration noise), and that adding them does not
  collide with Track 1's out-of-scope set (none of the four appears in the "Out of scope (Track
  1 owns these)" list at 543-547). Clean.
- **Verdict**: VERIFIED

#### Verify R3: in-scope roster refs the Plan of Work step did not name
- **Original issue**: `risk-tagging.md:68` (the `high`-row step-level-review cell) and
  `conventions-execution.md:528` (the per-tier baseline-subset note) carry
  `review-bugs-concurrency`, but the per-step file→work mapping did not name them, inviting a
  decomposer to leave them.
- **Fix applied**: Plan of Work step (1) now names "re-key the `### Risk tagging` risk-level
  table's step-level-review cell (its `high` row names the removed `review-bugs-concurrency`)
  onto the D3 split roster". Step (4)'s repo-wide sweep explicitly names "the in-scope roster
  references the per-file Plan-of-Work steps do not separately name
  (`conventions-execution.md`'s baseline-subset note and its §2.5 `BC`-prefix schema examples,
  `risk-tagging.md`'s risk-level table cell)".
- **Re-check**:
  - Track-file location: Plan of Work step (1) (316-333, the new "Also re-key the `### Risk
    tagging` risk-level table's step-level-review cell" sentence); step (4) (367-390).
  - Codebase corroboration: `risk-tagging.md:68` (`| high | … review-bugs-concurrency …`) and
    `conventions-execution.md:528` (`subset (review-bugs-concurrency only)`) confirmed live;
    `conventions-execution.md` §2.5 `BC`-prefix examples confirmed at lines 559-560, 568-569,
    587-641 — all now named by the track.
  - Current state: resolved — each in-scope roster site is named by the step that edits its
    file; the in-scope grep can no longer surface a self-inflicted miss.
  - Criteria met: instruction completeness (per-step mapping no longer invites a narrow read),
    rule coherence.
- **Regression check**: checked that step (1)'s added cell re-key and step (2)'s panel re-key
  do not both claim the same `track-review.md` lines — they touch different sections (step 1 →
  `risk-tagging.md` cell; step 2 → `track-review.md` panel/reconciliation). No overlap. Clean.
- **Verdict**: VERIFIED

#### Verify R4: `create-final-design.md` / `design-review.md` read the dropped `tier` field
- **Original issue**: steps (5)/(6) named the carrier-table/gate *intent* but not the
  underlying `tier` ledger-read mechanism — `create-final-design.md:41-46,89-92` reads "the
  confirmed tier ledger-first" from the dropped `tier` field with an `implementation-plan.md`
  tier-line fallback; `design-review.md:67-69` declares a `tier` input param feeding the
  `tier=full` gate at `:235-255`.
- **Fix applied**: step (5) now states the re-derivation "includes re-keying the prompt's own
  ledger-read **mechanism** (its 'read the confirmed tier ledger-first' steps that fetch the
  dropped `tier` field, and the `implementation-plan.md` tier-line fallback) onto `design_gate`
  (carrier 1) and the reconciled-tag scan (carrier 2) — not only the carrier table it feeds".
  Step (6) now states the `design-review.md` re-key covers "the `tier` **input param** and its
  spawn-site, the roster / tag / review references, and its `tier=full` fidelity gate … →
  `design_gate=yes`". The `## Interfaces` `design-review.md` entry (525-528) repeats the input
  param + spawn-site + gate.
- **Re-check**:
  - Track-file location: Plan of Work step (5) (392-400); step (6) `design-review.md` clause
    (414-416); `## Interfaces` entries (523-528).
  - Codebase corroboration: `create-final-design.md:41-44,90-92` ("read the confirmed tier
    ledger-first — the phase ledger's `tier` field … fall back to the … tier line in
    `implementation-plan.md`") confirmed; `design-review.md:67-68` (`tier` optional input param)
    and `:235` (`Full-tier fidelity criterion (tier=full, target=tracks)`) confirmed — all named
    by the track now.
  - Current state: resolved — both prompts' full set of `tier`-read sites is named; the
    "internally inconsistent carrier re-keyed while the read still fetches `tier`" failure mode
    is closed by acceptance's "No promoted file reads the dropped `tier` ledger field" grep over
    the four named live tier-readers.
  - Criteria met: instruction completeness, breakage-of-dependent-prompts (the spawn contract
    that passes `tier=` is named, so it cannot drift past the gate re-key).
- **Regression check**: confirmed the four "live tier-readers" the acceptance line enumerates
  (`inline-replanning.md`, `track-review.md`, `create-final-design.md`, `design-review.md`)
  match the Track-1 handoff's forward-obligation set exactly — no reader added or dropped.
  Clean.
- **Verdict**: VERIFIED

#### Verify R5: reconciled-tag write must co-emit with `--track` on one line
- **Original issue**: the D5 reconciled-tag write must ride the existing A→C `--append-ledger`
  line (the one carrying `--track <N>`), not a second append that would split the boundary
  record; and `max(step tags)` must be recomputed deterministically on resume for idempotence.
- **Fix applied**: Plan of Work step (2) now states "append `--reconciled-tag <max(step tags)>`
  onto the **existing** A→C boundary append (the `track-review.md` call already carrying
  `--track <N> --substate steps-partial`), not a separate line — the track-scoped reader
  resolves the tag only on a ledger line that also carries its `track=` token — and recompute
  `max(step tags)` from the committed `## Concrete Steps` roster on every (re)entry so the write
  is idempotent on resume".
- **Re-check**:
  - Track-file location: Plan of Work step (2) (344-350).
  - Codebase corroboration: the staged precheck accepts `--reconciled-tag <low|medium|high>`
    (parse branch at line 209; usage at 160) and the `--append-ledger` line accepts `--track`
    and `--reconciled-tag` together — confirming the single-line co-emit is mechanically
    supported.
  - Current state: resolved — the suggestion's two correctness notes (single-line co-emit,
    deterministic recompute) are both now explicit writer instructions.
  - Criteria met: instruction completeness (resume idempotence is stated, not left implicit).
- **Regression check**: checked that "recompute from the committed `## Concrete Steps` roster"
  is consistent with step-5 commit ordering (the step tags land on disk before the boundary
  append) — no ordering contradiction. Clean.
- **Verdict**: VERIFIED

#### Verify R6: acceptance grep scope and new-prefix registration
- **Original issue**: acceptance scoped the removed-agent grep to "the five selection mirror
  sites" and the prefix grep to "the `finding-synthesis-recipe` prefix family"; the real
  reference set is wider, and the canonical `review-iteration.md` owner table needs the two new
  prefixes registered and `BC` retired.
- **Fix applied**: the "No dangling roster references" acceptance is now "A repo-wide grep over
  all `.claude/**` (staged copies for in-scope files, the live tree otherwise)"; the "Prefixes
  resolve" line now asserts "`review-iteration.md` §'Finding ID prefixes' (the canonical owner
  table) retires the `BC` row and registers the two new `review-bugs` / `review-concurrency`
  prefixes". `review-iteration.md` is in the in-scope list (504-506) with the same retire/add
  instruction.
- **Re-check**:
  - Track-file location: `## Validation and Acceptance` "No dangling roster references"
    (464-469) and "Prefixes resolve" (455-460); `## Interfaces` `review-iteration.md` entry
    (504-506).
  - Codebase corroboration: `review-iteration.md` §"Finding ID prefixes" owner table confirmed
    at line 42 with the live `BC` row at line 56 — the canonical home the acceptance now targets.
  - Current state: resolved — acceptance is repo-wide and asserts the owner-table retire/add, so
    a missed owner-table entry (invisible to a synthesis-recipe-only grep) is now caught.
  - Criteria met: testability (the grep safety net matches the real blast radius), rule
    coherence (owner table is the canonical prefix home).
- **Regression check**: confirmed the prefix invariant keeps `TB`/`TC` verbatim and `TX`
  unchanged (track DR D7 and the "Finding-prefix family" key contract at 571-576 agree); no
  surviving-prefix reference broken. Clean.
- **Verdict**: VERIFIED

## Summary

**PASS.** All six findings VERIFIED; zero regressions; zero new findings.
