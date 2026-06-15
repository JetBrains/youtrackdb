<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Adversarial gate verification — Track 1, iteration 3

Re-check of the still-open / new findings carried from iteration 2 (A1
blocker, A2 should-fix, A5 should-fix) after the iter-3 fixes. A3 and A4
were VERIFIED at iteration 2 and are not re-checked. The branch is
workflow-modifying under §1.7(b) (plan `### Constraints`); the
staged-workflow tree is empty (Phase A, pre-implementation), so every
`.claude/**` read resolves to the live file per §1.7(d).

## Findings

(no new findings)

## Evidence base

#### Verify A1: §1.7-scripts resolution incomplete — promotion `git add` omits `.claude/scripts`
- **Original issue (iter2, STILL OPEN)**: D14 extends §1.7 to cover
  `.claude/scripts/**`, and Phase 4's `cp -r "$STAGED_DIR/.claude/." .claude/`
  copies the staged `scripts/` subtree onto live. But the promotion `git add`
  staged only `.claude/workflow .claude/skills .claude/agents`, so the copied
  scripts were never committed and never reached develop — the §1.7-to-scripts
  extension was only half-backed.
- **Fix applied (iter3)**: Track 2 (`track-2.md`) now realizes D14's Track-2
  half explicitly, and the plan/track-1 prose now names the create-final-design
  edits as the home of that half.
- **Re-check**:
  - **Live gap is real (confirmed).** `create-final-design.md`:
    - line 450 — divergence check
      `git log "$(git merge-base origin/develop HEAD)..origin/develop" -- .claude/workflow .claude/skills .claude/agents`
      omits `.claude/scripts`.
    - line 457 — `git add .claude/workflow .claude/skills .claude/agents`
      omits `.claude/scripts`.
    - line 456 — `cp -r "$STAGED_DIR/.claude/." .claude/` already copies the
      whole `.claude/` subtree (scripts included); the only defect is that the
      copied scripts are never `git add`ed. (Line 452 is the error-message echo
      text — non-load-bearing; gate behavior is driven by line 450's `git log`.)
    - `create-final-design.md` is an in-scope Track 2 file (`track-2.md`
      `## Interfaces and Dependencies` line 241), so the edit is correctly
      planned there, not yet applied to the live prompt — exactly as the iter-3
      note requires.
  - **Plan/track files now direct the Track-2 implementer to close it
    (confirmed).**
    - `track-2.md` Plan-of-Work step 2 (lines 166-175) realizes D14's Track-2
      half: (i) extend implementer-rules §1.7(e) pre-commit gate to refuse a
      live `.claude/scripts/**` edit outside the Phase-4 promotion commit; (ii)
      extend `create-final-design.md` so "its pre-promotion divergence check
      **and** its `git add` both include `.claude/scripts`," with the explicit
      rationale that the `cp -r` already copies but the narrow `git add` never
      commits. Both live locations (line 450 + line 457) are named.
    - `track-2.md` `## Validation and Acceptance` gained two bullets (lines
      208-213): one for the §1.7(e) gate refusing live `.claude/scripts/**`
      outside promotion, one for the Phase-4 promotion committing the staged
      `.claude/scripts/**` so a promoted precheck script reaches develop.
    - D14 `Implemented in` now names these create-final-design edits in **both**
      `track-1.md` (lines 173-176) and `implementation-plan.md` (lines 301-305):
      "Track 2 (implementer-rules §1.7(e) gate … ; `create-final-design.md`
      Phase-4 promotion divergence check and `git add` both include
      `.claude/scripts`)."
    - `track-1.md` `## Context and Orientation` "Branch-local script staging"
      paragraph (lines 203-205) now states Track 2 widens the Phase-4 promotion
      `git add` and divergence check to include `.claude/scripts`, "so the
      copied files are committed and reach develop."
  - **End-to-end coherence (confirmed).** The A1 hazard is resolved across all
    three legs: (a) the live `.claude/scripts/**` files are never touched during
    the branch — the implementer authors the script and tests by manual
    copy-then-edit at the staged path (`track-1.md` Context and Orientation
    lines 192-205; develop-era gate does not guard scripts, so the discipline is
    manual but stated); (b) the §1.7 extension is coherent — D14 adds
    `.claude/scripts/**` to §1.7(a)/(b)/(d)/(e) in Track 1 and the gate +
    promotion enforcement in Track 2; (c) Phase 4 both promotes (`cp -r`) and
    **ships** (widened `git add`) the staged scripts. The widened divergence
    check keeps the §1.7(f) rebase-precedes-promotion guard honest for the new
    prefix.
  - **Live anchor confirmed for the gate half.** The live
    `implementer-rules.md` pre-commit gate (line 403) currently checks only
    `git diff --cached --name-only -- .claude/workflow/ .claude/skills/ .claude/agents/`
    — it does not include `.claude/scripts/`. That is precisely the gap Track 2
    step 2(i) directs the implementer to widen; `implementer-rules.md` is in
    Track 2's in-scope list (`track-2.md` line 248). The plan is internally
    consistent.
  - Criteria met: the §1.7-to-scripts resolution is now fully backed — the
    promotion `git add` and divergence check are both targeted for widening, the
    edit is owned by an in-scope Track 2 file, and the prose cross-references
    (D14 Implemented-in on both plan and track-1, Context-and-Orientation,
    Track 2 step 2 + Validation) close the loop.
- **Regression check**: Checked (1) whether widening `git add`/divergence to
  `.claude/scripts` interacts with the Step 6 cleanup (`git rm -r
  docs/adr/<dir>/_workflow/`, line 517) — no overlap, the cleanup removes the
  `_workflow/` staging tree, not the promoted live `.claude/scripts`; (2)
  whether any other live three-prefix occurrence in `create-final-design.md`
  needs widening — only lines 450/452/457 carry the triple; 450 and 457 are
  named, 452 is a non-load-bearing echo; (3) the promotion-commit-prefix
  allow-clause (line 464) is unchanged and still matches the implementer-rules
  gate. Clean — no new issue.
- **Verdict**: VERIFIED

#### Verify A2: plan-side D3 cited the wrong interrupted-write mechanism
- **Original issue (iter2, STILL OPEN)**: only the track-side D3 was reworded;
  the plan-side D3 `Risks/Caveats` still credited "the existing interrupted-write
  reconciliation" for torn-append safety, conflating the ledger's atomic
  temp-file-plus-rename append with the unrelated roster-vs-`## Progress`
  track-file reconciliation.
- **Fix applied (iter3)**: `implementation-plan.md` D3 `Risks/Caveats`
  reworded.
- **Re-check**:
  - Plan location: `implementation-plan.md` D3 `Risks/Caveats` (lines 151-155).
  - Current state: "a torn append must not corrupt state — the atomic
    temp-file-plus-rename append handles it: the rename publishes the new tail
    atomically, so a crash mid-append leaves the prior ledger intact. The
    roster-vs-`## Progress` interrupted-write reconciliation is a separate
    track-file mechanism and does not apply to the ledger." This now credits the
    correct mechanism (atomic temp-file-plus-rename) and explicitly demotes the
    roster reconciliation to a separate, non-applicable track-file mechanism.
  - Consistency with the track-side D3: `track-1.md` D3 `Risks/Caveats`
    (lines 71-76) carries the same correction with the same two-part phrasing.
    The two D3 copies now agree.
  - Criteria met: the caveat names the real durability mechanism and no longer
    asserts a mechanism that does not exist for the ledger.
- **Regression check**: Checked the plan `### Constraints` backward-compat
  bullet (lines 52-56) and Invariants (lines 308-309) for any lingering claim
  that the ledger reuses the track-file reconciliation — none; the two-level
  resume rule is stated consistently. Clean.
- **Verdict**: VERIFIED

#### Verify A5: Phase-4 promotion `git add` + divergence check omit `.claude/scripts`
- **Original issue (iter2, NEW)**: `create-final-design.md` line 457
  (`git add .claude/workflow .claude/skills .claude/agents`) and line 450
  (divergence check) omit `.claude/scripts`. (Same root defect as A1's second
  half, surfaced as a standalone should-fix.)
- **Fix applied (iter3)**: same as A1's half-(ii) — Track 2 widens both the
  `git add` and the divergence check to include `.claude/scripts`.
- **Re-check**:
  - Live location: `create-final-design.md` lines 450 (divergence) and 457
    (`git add`) — both omit `.claude/scripts` today (confirmed by grep).
  - Plan capture: `track-2.md` Plan-of-Work step 2(ii) (lines 172-175) names
    **both** the divergence check and the `git add`, with the rationale that
    without the wider `git add` the `cp -r`-copied scripts "are copied yet never
    committed, so they never reach develop." The Validation bullet (lines
    211-213) asserts the promoted scripts reach develop. The edit is owned by an
    in-scope Track 2 file.
  - Criteria met: both omission sites are explicitly targeted for the Track-2
    implementer; A5 collapses into the same closed loop as A1's second half.
- **Regression check**: Same regression sweep as A1 — no new issue from
  widening the two prefix lists. Clean.
- **Verdict**: VERIFIED

## Summary

PASS. The iter-3 fixes land correctly in the plan and track files. A1's
end-to-end hazard is fully resolved: the live `.claude/scripts/**` stays
untouched during the branch (manual staging), the §1.7-to-scripts extension is
coherent across Track 1 (conventions §1.7(a)/(b)/(d)/(e)) and Track 2 (the
implementer-rules §1.7(e) gate plus the widened Phase-4 promotion `git add` and
divergence check), and Phase 4 both promotes and ships the staged scripts so a
promoted precheck script reaches develop. A2's plan-side D3 caveat now credits
the atomic temp-file-plus-rename append and demotes the roster reconciliation to
an inapplicable track-file mechanism, matching the track-side D3. A5 collapses
into A1's second half and is closed by the same Track-2 edits. No new findings.
