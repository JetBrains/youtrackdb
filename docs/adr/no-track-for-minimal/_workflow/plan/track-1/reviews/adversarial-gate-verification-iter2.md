<!-- MANIFEST
verdicts:
  - {id: A1, sev: blocker,    verdict: STILL OPEN, cert: "#### Verify A1 "}
  - {id: A2, sev: should-fix, verdict: STILL OPEN, cert: "#### Verify A2 "}
  - {id: A3, sev: should-fix, verdict: VERIFIED,   cert: "#### Verify A3 "}
  - {id: A4, sev: suggestion, verdict: VERIFIED,   cert: "#### Verify A4 "}
overall: FAIL
findings: 1
index:
  - {id: A5, sev: should-fix, loc: "create-final-design.md:457 / track-2.md:160-169", anchor: "### A5 ", basis: "Phase-4 promotion git add covers only the three legacy prefixes, not .claude/scripts, so the staged script edits cp-r'd onto live are never committed and never reach develop — the A1 resolution's 'Phase 4 cp -r promotes' claim is half-backed"}
-->

# Track 1 adversarial gate verification — iteration 2

Re-check of the four iteration-1 adversarial findings (A1 blocker, A2/A3
should-fix, A4 suggestion) after Phase-A review fixes. Scope and method per
`review-gate-verification.md §Semi-Formal Verification Protocol`. This is a
workflow-machinery track (§1.7(b) marker), so reference checks run via grep +
Read against the **live** `.claude/**` tree (the staged subtree
`_workflow/staged-workflow/` is empty at Phase A — no edits applied yet, which
itself confirms A1's premise that no staged `.claude/scripts/` path exists). IDE
not used for symbol re-checks; the targets are workflow prose and shell, not Java
symbols. The frozen `design.md` predates this Phase-A finding set, so the
verification reads the track file and plan as the authoritative current state.

## Verification certificates

#### Verify A1 (blocker): staging scope vs the track's `.claude/scripts/**` footprint
- **Original issue**: the track stages `.claude/scripts/**` (the precheck
  script + two test files), but `conventions.md §1.7(a)` covers only
  `.claude/workflow/`, `.claude/skills/`, `.claude/agents/` — so an implementer
  routes the four prose docs to staging but writes the `.sh`/`.py` files LIVE,
  and this branch's own session-start `--mode full` then runs the half-rewritten
  `determine_state`, breaking the I6 invariant.
- **Fix applied** (user-ratified resolution: extend §1.7 to scripts):
  - New **D14** added to track-1 `## Decision Log` (lines 154-176) and plan
    Architecture Notes (lines 282-298): "§1.7 staging covers `.claude/scripts/**`."
  - Plan-of-Work step 3 (track-1.md lines 263-267) extends §1.7(a)/(b)/(d)/(e)
    to add `.claude/scripts/**` to the staged prefix set.
  - `## Context and Orientation` gains a "Branch-local script staging (D14)"
    paragraph (track-1.md lines 190-201): this branch authors the script + tests
    by manual copy-then-edit under
    `_workflow/staged-workflow/.claude/scripts/`, live script untouched.
  - Track 2 step 2 (track-2.md lines 166-169) extends the implementer-rules
    §1.7(e) pre-commit gate to refuse live `.claude/scripts/**` edits.
  - Plan `### Constraints` staging bullet updated (plan lines 44-46).
- **Re-check**:
  - Live convention confirmed: `conventions.md` line 896 reads verbatim "No
    other prefixes participate: workflow files outside `.claude/workflow/`,
    `.claude/skills/`, and `.claude/agents/` are not stageable under this
    convention" — so the live precheck this branch runs does NOT auto-route
    scripts. The track's two-part design correctly addresses this:
    (1) the *manual copy-then-edit at the staged path* keeps the LIVE
    `.claude/scripts/**` files untouched during this branch's own execution, so
    this branch's `--mode full` resume keeps running the develop-era live script
    — the direct I6 hazard A1 named is closed; and
    (2) the §1.7(a)/(b)/(d)/(e) extension (D14) backs the design's D12 "every
    `.claude/**` edit stages" assertion for *future* branches, taking effect only
    after Phase 4 promotion.
  - The coherence of (1) holds: the manual-staging discipline does not depend on
    the develop-era gate (the gate does not guard scripts), the live files stay
    at develop state, and Phase 4's `cp -r "$STAGED_DIR/.claude/." .claude/`
    (create-final-design.md line 456) does copy a staged `scripts/` subtree onto
    the live tree.
- **Regression check**: NOT clean — the fix introduced a downstream
  incompleteness on its own load-bearing promotion path. The A1 resolution
  rests twice (track-1.md lines 157, 200) on "Phase 4 `cp -r .claude/.`
  promotes" the staged `scripts/` subtree. But the Phase-4 promotion in
  `create-final-design.md` is `cp -r` (line 456, copies scripts onto the live
  working tree) **immediately followed by** `git add .claude/workflow
  .claude/skills .claude/agents` (line 457) — which does NOT add
  `.claude/scripts`. The promotion commit (line 458,
  `git diff --cached --quiet || git commit`) therefore records only the three
  legacy prefixes; the copied `.claude/scripts/**` changes sit unstaged in the
  working tree. The Step 6 cleanup commit
  (`git rm -r docs/adr/<dir-name>/_workflow/` + bare `git commit`, lines
  517-518, no `git add`/`-a`) records only the deletions, so it does not catch
  them either. Net: the staged precheck script + tests are `cp`-ied onto live
  but **never committed**, so they never reach `develop` — the very payload of
  this branch is dropped at promotion. `create-final-design.md` is in Track 2's
  in-scope set (track-2.md line 231), but Track 2's Plan-of-Work step 2
  (lines 160-169) only re-points its tier-line read and extends the
  *implementer-rules* gate; neither track plans the `git add .claude/scripts`
  extension to the Phase-4 promotion script. Filed as new finding **A5**.
- **Verdict**: STILL OPEN. The core blocker hazard (live-script edit
  destabilizing this branch's resume) IS resolved by the manual-staging design,
  and the §1.7 extension is coherently threaded for future branches. But the
  resolution is **incomplete**: its own "Phase 4 promotes the staged scripts"
  claim is not backed — the promotion script commits only the three legacy
  prefixes, so the staged script never lands on `develop`. Per the gate rule
  ("`overall` is FAIL if A1's resolution is incomplete or incoherent"), this
  keeps A1 open and forces `overall: FAIL`.

#### Verify A2 (should-fix): D3/D6 cite an inapplicable interrupted-write reconciliation
- **Original issue**: D3's risk line named two mechanisms covering a torn
  ledger append; the second — "the existing interrupted-write reconciliation" —
  is the roster-vs-`## Progress` State-C reconciliation, which reads track-file
  sections and has no knowledge of the ledger. Loc cited both
  `track-1.md:71-73` and `plan:147-149`.
- **Fix applied**: D3 `Risks/Caveats` reworded to the atomic
  temp-file-plus-rename guard.
- **Re-check**:
  - Track-side D3 (track-1.md lines 71-76): now reads "the atomic
    temp-file-plus-rename append covers it: a partial write lands in the temp
    file and the rename publishes the new tail atomically … (The existing
    roster-vs-`## Progress` interrupted-write reconciliation is a separate
    track-file mechanism and does not apply to the ledger.)" This is the exact
    correction A2 asked for, and it explicitly disclaims the inapplicable
    mechanism. The track-side fix is VERIFIED.
  - Plan-side D3 (implementation-plan.md lines 151-153): **still carries the
    original flawed wording** — "a torn append must not corrupt state — handled
    by the atomic temp-file-plus-rename append and the existing interrupted-write
    reconciliation." This is verbatim the text A2 flagged at `plan:147-149`. The
    plan mirror was not updated.
- **Regression check**: clean on the track side; the plan side is left stale —
  a plan reader still sees the category-error rationale, and D1's drift-gate
  premise of one-owner-per-fact is dented by a track/plan mismatch on the same
  decision's risk line.
- **Verdict**: STILL OPEN — the track-side D3 is fixed, but the plan-side D3
  (`implementation-plan.md` lines 151-153), which A2 explicitly named, retains
  the original flawed "existing interrupted-write reconciliation" wording. The
  fix is partial. (should-fix; does not independently force FAIL, but is left
  open for the same one-sentence edit on the plan.)

#### Verify A3 (should-fix): sub-floor track without the gate-required justification
- **Original issue**: ~8-file Track 1 below the ~12 merge floor with Track 2
  depending on it, but no written sub-floor justification — `planning.md`
  Argumentation gate requires one in the track file.
- **Fix applied**: a "Sub-floor sizing justification" block added to
  `## Interfaces and Dependencies`.
- **Re-check**:
  - Track-file location: `track-1.md` lines 348-357, a bolded "Sub-floor sizing
    justification (~8 in-scope files, below the ~12 merge floor)" block.
  - Current state: it gives the two required reasons — (1) producer/consumer
    dependency boundary (merging would yield one ~21-file track mixing
    definition and consumption), and (2) dense large files breach review
    capacity below the ~20-25 ceiling — and names the define/consume seam as the
    natural boundary.
  - Criteria met: `planning.md` Argumentation gate (lines 604-614) requires the
    justification to "name why the track is not folded," in the *track file*.
    Both conditions are satisfied: the rationale is present and lives in the
    track file (not only the plan), so the gate now passes autonomously rather
    than escalating as a `design-decision` finding.
- **Regression check**: clean — the block sits in `## Interfaces and
  Dependencies` near the existing inter-track-dependency line, introduces no
  contradiction, and the ~21-file combined-footprint arithmetic matches the
  plan's two scope notes (~8 + ~13).
- **Verdict**: VERIFIED.

#### Verify A4 (suggestion): "add the ledger to the drift logic … not folded into the stamp walk" self-contradictory
- **Original issue**: Plan-of-Work step 1 read both "Add the ledger to the drift
  logic as an unstamped artifact" and "(not folded into the §1.6(h) stamp
  walk)" — pulling opposite directions and risking the implementer adding a
  fifth walk glob, which would trip the conformance fixture.
- **Fix applied**: step 1 and D13 reworded so the ledger is excluded by omission
  from `detect_drift`'s hardcoded list; only the §1.6(f) doc entry changes.
- **Re-check**:
  - Plan-of-Work step 1 (track-1.md lines 246-249): now reads "Record the ledger
    as an unstamped artifact in the §1.6(f) exclusion list (doc-only); no
    `detect_drift` code change is needed because its walk enumerates a hardcoded
    artifact list that does not include the ledger filename." The contradictory
    "add to the drift logic" instruction is gone; the affirmative-edit risk is
    removed.
  - D13 (track-1.md lines 138-152): `Implemented in` now states "No
    `detect_drift` code change is needed: its walk enumerates a hardcoded
    artifact list (`implementation-plan.md`, `design.md`, `design-mechanics.md`,
    `plan/track-*.md`), so the ledger is excluded by omission — the implementer
    confirms the ledger filename is not added to that list." This matches the
    actual mechanic and the Validation bullet (track-1.md lines 311-312, "the
    ledger is reported unstamped without tripping the drift gate").
- **Regression check**: clean — the reworded step and D13 agree with each other
  and with the §1.6(f) exclusion entry the track edits; no instruction now
  invites a walk-glob edit. (Note: D13 in the *plan* at lines 272-280 still says
  "`Implemented in`: Track 1 (conventions §1.6(f), precheck.sh drift fold)" —
  the "precheck.sh drift fold" clause is looser than the track's "no code
  change" wording, but it is not self-contradictory and does not instruct a walk
  edit, so it does not reopen A4; it is the same plan-vs-track drift class as A2
  and is folded into A5's neighborhood rather than reopening A4.)
- **Verdict**: VERIFIED.

## Findings

### A5 [should-fix]
**Certificate**: regression introduced by the A1 resolution (Phase-4 promotion
does not commit the staged `.claude/scripts/**`).
**Target**: the A1/D14 resolution's load-bearing claim "Phase 4 `cp -r
.claude/.` promotes the staged `scripts/` subtree" (track-1.md lines 157, 200;
plan D14 lines 293-296), against the live Phase-4 promotion script in
`create-final-design.md`.
**Challenge**: D14 extends §1.7(a)/(b)/(d)/(e) (the staging *prefix set*) and
Track 2 extends the *implementer-rules* pre-commit gate to refuse live
`.claude/scripts/**` writes. Both are necessary. But the promotion side is not
wired: `create-final-design.md` line 456 `cp -r "$STAGED_DIR/.claude/." .claude/`
copies a staged `scripts/` subtree onto the live working tree, and line 457
`git add .claude/workflow .claude/skills .claude/agents` then stages only the
three legacy prefixes. The line-458 commit therefore omits the copied
`.claude/scripts/**`; the Step-6 cleanup commit (lines 517-518,
`git rm -r _workflow/` + bare `git commit`) records only deletions. The staged
precheck script and its tests — this branch's primary payload — are copied to
live but never committed, so they never reach `develop`. The branch would merge
with its conventions/planning prose promoted but the actual script change
silently dropped.
**Evidence**: `create-final-design.md` lines 456-458 (`cp -r` then three-prefix
`git add`), lines 517-518 (cleanup `git rm -r` + bare commit). `create-final-
design.md` is in Track 2's in-scope set (track-2.md line 231), but Track 2's
Plan-of-Work step 2 (lines 160-169) extends only the tier-line read and the
implementer-rules gate; no step extends the Phase-4 promotion `git add` to
include `.claude/scripts`. Track 1's D14 does not mention the promotion `git
add` line at all.
**Survival**: the *decision* (extend §1.7 to scripts, manual-stage this branch)
survives; only the promotion wiring is missing — a one-token edit
(`git add … .claude/scripts`) to the Track 2 `create-final-design.md` edit. So
this is should-fix in isolation, but because it leaves A1's resolution
incomplete (the staged script never lands), it is the concrete reason A1 stays
STILL OPEN and `overall` is FAIL.
**Proposed fix**: In Track 2's Plan-of-Work step 2 (and the `create-final-
design.md` in-scope note), add: extend the Phase-4 promotion `git add` to
`git add .claude/workflow .claude/skills .claude/agents .claude/scripts` so the
`cp -r`'d staged script + tests are committed by the promotion commit. Cross-
reference D14 from that Track 2 step so the producer-side §1.7 extension and the
promotion-side commit extension are visibly paired. (Optionally also widen the
implementer-rules live-path gate's allow-clause and the §1.7(e) promotion-commit
description so the new `.claude/scripts` add is not itself refused — verify the
gate keys on the commit-message prefix, which it does per create-final-design.md
lines 463-468, so the wider `git add` is allowed.)

## Summary

**FAIL.** A3 and A4 are VERIFIED. A2 is partially fixed (track-side D3 corrected;
plan-side D3 at implementation-plan.md lines 151-153 still carries the original
inapplicable-reconciliation wording) — STILL OPEN as should-fix. A1's core
blocker hazard (a live-script edit destabilizing this branch's own `--mode full`
resume) IS resolved by the manual copy-then-edit staging discipline, and the
§1.7 extension is coherently threaded for future branches. But the A1 resolution
is **incomplete**: its load-bearing "Phase 4 `cp -r` promotes the staged scripts"
claim is not backed — the promotion script's `git add` (create-final-design.md
line 457) commits only the three legacy prefixes, so the `cp`-ied
`.claude/scripts/**` is never committed and never reaches `develop` (new finding
A5). Per the gate rule that A1 incompleteness forces FAIL, the overall verdict is
FAIL. Resolving A5 (one-token `git add` extension in the Track 2
`create-final-design.md` edit, cross-referenced from D14) plus the plan-side D3
one-sentence edit closes both open items.
