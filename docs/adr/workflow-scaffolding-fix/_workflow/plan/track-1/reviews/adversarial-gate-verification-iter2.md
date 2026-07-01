<!-- workflow-sha: 03eac656fa115a8e6af3f53d8805d8f16f3bad50 -->
<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index:
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: REJECTED}
  - {id: A3, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Adversarial gate-verification — Track 1: Robust Phase-4 _workflow/ cleanup (iter2)

- **Reviewer role:** reviewer-adversarial (gate-verification)
- **Phase:** 3A
- **Mode:** prose-only workflow-machinery, §1.7(b) staging. No `staged-workflow/` subtree exists yet, so the live `.claude/workflow/**` files were read. References verified as workflow paths / `§`-anchors via grep + Read; no PSI.
- **Under re-check:** A1 (should-fix, accepted, fix applied), A2 (suggestion, rejected), A3 (suggestion, accepted, note applied).
- **Overall:** PASS — 1 VERIFIED, 1 REJECTED (sound, no downstream issue), 1 VERIFIED; 0 new findings.

## Findings

<!-- No new findings surfaced by this verification pass. -->

## Verification certificates

#### Verify A1: acceptance grep under-covers the D2 reconciliation scope
- **Original issue**: the mechanical acceptance check `grep -rn "git rm -r docs/adr" .claude/workflow` covered only 3 of 6 in-scope sites; a partial fix that missed a descriptive `git rm -r _workflow/` site (`conventions-execution.md:372`/`:747`) or the `` `git rm -r`s `` site (`mid-phase-handoff.md:493`) could pass the check while a doc still contradicted the fix.
- **Fix applied**: the track's `## Validation and Acceptance` (bullet 1, line 97) and `## Invariants & Constraints` (bullet 2, line 135) now specify `grep -rnE "git rm -r([^f]|$)" .claude/workflow` returning **no match** after the fix, with the in-scope sites enumerated.
- **Re-check**:
  - Track-file location: `track-1.md:97` and `track-1.md:135` — both carry the broadened `-rnE "git rm -r([^f]|$)"` pattern.
  - **(a) Catches all in-scope bare-`-r` sites (run live today):** `grep -rnE "git rm -r([^f]|$)" .claude/workflow` returns exactly 7 hits — the two operative sites (`create-final-design.md:609`, `workflow.md:764`), the "sweeps automatically" descriptive line (`create-final-design.md:617`), and the four other descriptive sites (`commit-conventions.md:153`, `conventions-execution.md:372`, `conventions-execution.md:747`, `mid-phase-handoff.md:493`). This is the full D2 footprint; the pre-fix narrow grep skipped `conventions-execution.md:372`/`:747` and `mid-phase-handoff.md:493`. The prompt's enumeration listed "609, 764, 617, commit-conventions:153, conventions-execution:372/:747, mid-phase-handoff:493" — all seven confirmed present in the live result. (Post-fix these sites become `-rf`; the assertion is that the pattern returns **no match** once the implementer applies the fix, which is exactly what it will do since `git rm -rf` is excluded — see (b).)
  - **(b) Excludes `git rm -rf`:** `grep -rn "git rm -rf" .claude/workflow` returns 0 hits today (no `-rf` yet), and the pattern's `([^f]|$)` class after `git rm -r` cannot match the `f` in `-rf`, so once the operative sites become `git rm -rf docs/adr/...` they drop out of the match set — the "no match" post-fix assertion is mechanically sound.
  - **(c) Does NOT match the three benign forward-pointers:** `workflow.md:695` ("before the cleanup `git rm` runs"), `workflow.md:757` ("`git rm` below deletes the log"), and `conventions-execution.md:383` ("blanket recursive `rm` above") carry no `git rm -r` token at all — confirmed by reading each line; none appears in the broadened-pattern result. Correctly not matched.
  - **(d) Out-of-scope fixture outside grep scope:** the sixth `git rm -r` fixture match lives at `.claude/scripts/tests/fixtures/review-file-valid-strategic.md:33`, under `.claude/scripts/`, outside the `.claude/workflow` grep root. It does not appear in the workflow-scoped result. Confirmed.
  - Criteria met: the broadened check now covers every site D2 requires reconciled, closing the CONSTRUCTIBLE violation scenario from iter1 (a partial fix missing a descriptive site can no longer pass the automated invariant clean).
- **Regression check**: checked the enumeration text in both bullets against the live grep — the site list matches the actual match set with no phantom or omitted site; the pattern change did not alter any other track claim (the second Validation bullet's per-site read requirement is unchanged and complementary). Clean.
- **Verdict**: VERIFIED

#### Verify A3: note that `rm -rf` blast radius is safe because cleanup runs after promote
- **Original issue**: optional note recommended, recording that the `rm -rf` is safe because Step-6 cleanup runs after Step-4 promotion (an already-promoted copy is discarded, never live files).
- **Fix applied**: `## Decision Log` D1 **Risks** (track-1.md:32) now carries a sentence: the `rm -rf docs/adr/<dir-name>/_workflow/` blast radius is bounded by the Phase-4 step ordering — cleanup runs *after* promote-staged-workflow has copied+committed the staged subtree, so it discards only an already-promoted copy and never touches the live workflow files or the durable `design-final.md`/`adr.md`.
- **Re-check**:
  - Track-file location: `track-1.md:32` (D1 Risks, final sentence).
  - Ordering accuracy: `create-final-design.md` places **Step 4 — Promote staged workflow changes** at line 503 (the `cp -r "$STAGED_DIR/.claude/." .claude/` + commit + push block, lines 535–552) and **Step 6 — Cleanup commit** at line 601 (the `git rm` block at 609). Step 4 strictly precedes Step 6; Step 6's own prose (line 560) confirms "the cleanup commit in Step 6 below removes the staged subtree." `workflow.md § Final Artifacts` (the cleanup command at line 764) mirrors this. The note's ordering claim is accurate.
  - Scope accuracy: the durable artifacts `design-final.md`/`adr.md` live at `docs/adr/<dir-name>/` outside `_workflow/` (create-final-design Step 6, lines 603–606, names them as the survivors), so the `_workflow/`-scoped `rm -rf` cannot reach them. Accurate.
  - Criteria met: the note is present, accurate, and correctly cross-references `create-final-design.md § Step 6` / `workflow.md § Final Artifacts`.
- **Regression check**: the added sentence sits inside D1 Risks and does not contradict D1's Decision/Rationale or the `## Interfaces and Dependencies` §1.7 routing prose (which independently states promotion precedes cleanup at line 127). No new inconsistency. Clean.
- **Verdict**: VERIFIED

#### Verify A2 (REJECTED): D1 rationale lists `.pyc` caches among "untracked remnants git rm cannot reach"
- **Rejection reason**: the orchestrator left D1 unedited, reasoning that (1) the fix outcome is unaffected — `rm -rf` clears both untracked-ignored and untracked-unignored files — and (2) D1's phrasing "untracked remnants ... git rm cannot reach" is accurate for gitignored files too, since `git rm` also will not touch ignored files, so no wording change is needed.
- **Downstream check**: verified both prongs. (1) `.gitignore:208-209` confirms `__pycache__/` and `*.pyc` are globally ignored, so `.pyc` caches are untracked-AND-ignored — a distinct git mechanism from the untracked-unignored cold-read/params files, exactly as iter1 A2 observed. But the outcome claim holds: a single `rm -rf docs/adr/<dir-name>/_workflow/` removes every on-disk entry regardless of git's view, so both classes are cleared and the command is correct. (2) The literal phrase "untracked remnants ... `git rm` cannot reach" (track-1.md:31, D1 Rationale) is true of ignored files: `git rm` does not delete gitignored paths any more than it deletes untracked-unignored ones. The wording does not assert the *reason* git skips them, so it does not misstate the mechanism — it groups all `git rm`-unreachable files under one true umbrella clause. iter1 A2 was a precision suggestion, not a correctness defect; leaving it unfixed ships no contradiction and no wrong instruction. A Phase-C `review-workflow-consistency` pass greps command shapes, not remnant-taxonomy prose, so no downstream re-raise is triggered. No downstream issue.
- **Verdict**: REJECTED (no action needed)

## Evidence base

#### A1 live grep evidence
- `grep -rnE "git rm -r([^f]|$)" .claude/workflow` → 7 hits: `commit-conventions.md:153`, `mid-phase-handoff.md:493`, `conventions-execution.md:372`, `conventions-execution.md:747`, `workflow.md:764`, `create-final-design.md:609`, `create-final-design.md:617`. MATCHES the D2 six in-scope sites plus the `:617` "sweeps" descriptive line (also in-scope per D2).
- `grep -rn "git rm -rf" .claude/workflow` → 0 hits (no `-rf` present yet; pattern will exclude it post-fix). MATCHES.
- Benign forward-pointers read live: `workflow.md:695` = "before the cleanup `git rm` runs"; `workflow.md:757` = "`git rm` below deletes the log"; `conventions-execution.md:383` = "blanket recursive `rm` above" — none carries `git rm -r`, none in the match set. MATCHES.
- `grep -rn "git rm -r" .claude/scripts/tests/fixtures/` → `review-file-valid-strategic.md:33` only; outside `.claude/workflow` scope, not in the workflow-scoped result. MATCHES.

#### A3 ordering evidence
- `create-final-design.md`: Step 4 (promote) header at line 503; promote bash `cp -r "$STAGED_DIR/.claude/." .claude/` + commit + push at 547–550; Step 6 (cleanup) header at 601; cleanup `git rm` at 609; survivors named at 603–606. Step 4 < Step 6. MATCHES the note.
- `track-1.md:127` (`## Interfaces and Dependencies`) independently states the Phase-4 promote step precedes and the fix goes live at promotion — consistent with the D1 Risks note. MATCHES.

#### A2 gitignore evidence
- `.gitignore:208` = `__pycache__/`, `.gitignore:209` = `*.pyc` — `.pyc` caches are untracked-AND-ignored. Confirms iter1's mechanism distinction, but the D1 umbrella phrasing ("git rm cannot reach") remains true for ignored files, and `rm -rf` clears them regardless. Rejection sound.

## Summary

**PASS.** A1 VERIFIED (broadened acceptance grep spans all in-scope bug shapes, excludes `git rm -rf`, skips the benign forward-pointers and the out-of-scope fixture). A3 VERIFIED (blast-radius note present and accurate; Step-4 promote precedes Step-6 cleanup). A2 REJECTED with a sound rejection reason and no downstream issue. No new findings.
