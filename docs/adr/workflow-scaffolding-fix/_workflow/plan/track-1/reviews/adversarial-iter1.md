<!-- workflow-sha: 03eac656fa115a8e6af3f53d8805d8f16f3bad50 -->
# Adversarial review — Track 1: Robust Phase-4 _workflow/ cleanup (iter1)

- **Reviewer role:** reviewer-adversarial
- **Phase:** 3A (track-scoped, narrowed to track realization per D9)
- **Scope:** scope/sizing, invariant violation, realization-bearing decision/assumption challenges. Cross-track-episode challenge DROPPED (Track 1, no prior episode). Inline D1/D2/D3 decisions NOT re-challenged (vetted by the Phase-0→1 research-log adversarial gate, iter2 PASS).
- **Mode:** prose-only workflow-machinery, §1.7(b) staging. No `staged-workflow/` subtree exists yet, so the live `.claude/workflow/**` files were read. References verified as workflow paths / `§`-anchors via grep + Read; no PSI.
- **Verdict:** NEEDS REVISION — 0 blocker, 1 should-fix, 2 suggestion.

## Findings

### A1 [should-fix]
**Certificate**: Violation scenario — "no descriptive site still claims the recursive `git rm` sweeps untracked files" (`## Validation and Acceptance`, bullet 3) / Assumption test — the acceptance grep spans the full D2 scope
**Target**: Invariant "No bare `git rm -r` remains for the `_workflow/` cleanup" + Decision D2 (reconcile every descriptive mention)
**Challenge**: The primary mechanical acceptance check — `grep -rn "git rm -r docs/adr" .claude/workflow` (`## Validation and Acceptance` bullet 1; `## Invariants & Constraints` bullet 2) — covers only 3 of the 6 in-scope sites. Verified live: pattern `git rm -r docs/adr` matches `create-final-design.md:609`, `workflow.md:764`, and `commit-conventions.md:153`. It does NOT match the three remaining in-scope descriptive sites, which use a different string shape: `conventions-execution.md:372` and `:747` use `git rm -r _workflow/` (no `docs/adr` segment), and `mid-phase-handoff.md:493` uses `` `git rm -r`s `` (no path at all). D2 requires reconciling all of these, but the only automated invariant check leaves them to "verified by reading." A partial implementation that fixes the operative commands and `commit-conventions.md` but misses one of the two `conventions-execution.md` mentions would pass the grep-based invariant clean, while a doc still asserts the buggy bare-command / untracked-sweep behavior — the exact contradiction D2 exists to prevent (a Phase-C `review-workflow-consistency` grep of `git rm` would then re-raise it). The invariant's testable assertion under-covers the decision it is meant to gate.
**Evidence**: `grep -rn "git rm -r docs/adr" .claude/workflow` → 3 hits (609, 764, `commit-conventions.md:153`). `grep -rn "git rm -r _workflow" .claude/workflow` → 2 hits (`conventions-execution.md:372`, `:747`). `grep -rn "git rm -r" .claude/workflow` → 7 hits total (the 6 in-scope + the `create-final-design.md:617` "sweeps" line, also bare). The `:617` descriptive line matches none of the two narrow patterns either.
**Proposed fix**: Broaden the acceptance grep to a pattern that spans all in-scope shapes and asserts the `-f` presence — e.g. add a second check `grep -rn "git rm -r[^f]" .claude/workflow` (or `grep -rnE "git rm -r([^f]|$)"`) returning no match after the fix, alongside the existing `git rm -r docs/adr` check. Alternatively enumerate all six line-anchored sites in the acceptance bullet and assert each carries the `-rf` shape (or the corrected prose), rather than leaning on a single `docs/adr`-scoped grep that silently skips half the D2 footprint. This is a check-completeness gap, not a plan-approach defect — the fix survives; the acceptance criterion needs to actually cover it.

### A2 [suggestion]
**Certificate**: Assumption test — D1 rationale mechanism for the `.pyc` remnants
**Target**: Decision D1 (robust cleanup command) — its rationale and the recurring "`git rm` silently ignores untracked files" framing
**Challenge**: D1's rationale and the track prose (`## Purpose / Big Picture`, `## Plan of Work`) justify the follow-up `rm -rf` partly by "the `.pyc` caches under `staged-workflow/`" as untracked files `git rm` cannot reach. That is imprecise for the `.pyc` case specifically: `.gitignore:208-209` globally ignores `__pycache__/` and `*.pyc`, so those files are untracked-AND-ignored — they are never candidates for `git rm` at all (git never warns about or considers them), a different mechanism from the untracked-but-unignored cold-read `output_path` / per-round params files that `git rm` would list but not delete. The fix outcome is unaffected — `rm -rf` clears both classes regardless — but the stated mechanism conflates two distinct git behaviors. A Phase-C consistency reviewer or a future maintainer reading "git rm silently ignores the `.pyc` files" could be misled into thinking a gitignored path behaves like a tracked-but-modified one.
**Evidence**: `.gitignore:208` (`__pycache__/`), `.gitignore:209` (`*.pyc`). Empirical repro (`/tmp`): with no `.gitignore`, an untracked `cache.pyc` shows as `?? ...cache.pyc` and survives `git rm -rf <dir>`; in the real repo it would not even appear in `git status` as untracked. The untracked cold-read/params files (not ignored) are the true "git rm lists but won't delete" case.
**Proposed fix**: Tighten the rationale wording at D1 and in `## Plan of Work` to distinguish the two: the follow-up `rm -rf` clears (a) untracked-but-tracked-eligible remnants (cold-read `output_path`, per-round params) that `git rm` reports but leaves on disk, and (b) gitignored caches (`.pyc` / `__pycache__`) that `git rm` never touches. Both need `rm -rf`; the reason differs. Low priority — the command is correct; only the explanatory mechanism is loose.

### A3 [suggestion]
**Certificate**: Challenge — single-step decomposition (SCOPE) / Violation scenario — `rm -rf` deleting more than intended (INVARIANT)
**Target**: Scope/sizing (single step, five files) + Invariant "single-cleanup-commit contract holds" + "no `_workflow/` content left on disk"
**Challenge (two prongs, both survive)**: (1) *Sizing.* The single-step, five-file decomposition is right and should not split. All six edits are one logical change (make every Phase-4-cleanup doc describe the same corrected command) that must land atomically to avoid a transient internal contradiction; there is no independently-mergeable sub-unit, and the footprint (5 files, all word-level prose) is far under the split threshold. Splitting would create a window where operative commands and descriptive prose disagree — strictly worse. (2) *`rm -rf` blast radius.* I constructed the violation scenario for the added `rm -rf docs/adr/<dir-name>/_workflow/`: could it delete something it must not, or break the single-cleanup-commit contract, or race the §1.7 promotion? Traced against the real Phase-4 step ordering in `create-final-design.md` (Step 4 promote → Step 5 final-artifacts → Step 6 cleanup) and `workflow.md` § Final Artifacts (three-commit workflow-modifying shape): the `rm -rf` is scoped to the `_workflow/` subtree only, runs at Step 6 strictly AFTER the Step-4 promotion has already copied the staged subtree onto the live `.claude/` tree (outside `_workflow/`) and committed+pushed it, so removing `_workflow/staged-workflow/` at cleanup discards only an already-promoted copy — nothing live is lost. The empirical repro confirms `git rm -rf` (stages tracked-modified deletion) + `rm -rf` (clears untracked) + one `git commit` yields exactly one new commit, clean `git status`, and no `_workflow/` on disk. Both the single-cleanup-commit and no-remnant invariants hold. Feasibility of a violation: INFEASIBLE given the fixed step ordering and the `_workflow/`-scoped path.
**Evidence**: `create-final-design.md` Step 4 bash (lines 535-552: `cp -r "$STAGED_DIR/.claude/." .claude/` then commit+push, guarded by `[ -d "$STAGED_DIR/.claude" ]`); Step ordering (Step 4 at :503, Step 6 at :601). `workflow.md:764-772` (cleanup removes "the staged subtree if present"). Empirical `/tmp` repro: modified tracked + untracked sibling + `.pyc` → `git rm -rf` exit 0, untracked survive on disk, `rm -rf` exit 0, single commit, `git status` clean, `rev-list --count` +1, no leftover.
**Proposed fix**: None required — decision and invariants survive. Recorded to strengthen rationale: the track file could add a one-line note at D1's Risks or in `## Interfaces and Dependencies` that the `rm -rf` is safe specifically because Step-4 promotion precedes Step-6 cleanup, so `staged-workflow/` is an already-promoted copy at delete time. Optional.

## Evidence base

#### Challenge: Decision D1 — Robust cleanup command (`git rm -rf` + `rm -rf`)
- **Chosen approach**: `git rm -rf docs/adr/<dir-name>/_workflow/` then `rm -rf docs/adr/<dir-name>/_workflow/`, then the single `git commit`.
- **Best rejected alternative**: (c) commit `_workflow/` before cleanup so nothing is modified/untracked — or (b) drop the `design-mutations.md` log write.
- **Counterargument trace**:
  1. At Phase-4 cleanup time the `_workflow/` state has both a tracked-modified file (`design-mutations.md`) and untracked files (cold-read `output_path`, per-round params) — `create-final-design.md:588-593` stages only top-level artifacts, explicitly not `_workflow/`.
  2. Alternative (b)/(c) each address only one class: (b) removes the modified-tracked case but leaves untracked remnants; (c) staging `_workflow/` would create an extra commit and fight the ephemeral-artifact intent.
  3. Only (a) clears both classes in one no-extra-commit step.
- **Codebase evidence**: `.gitignore:208-209` shows the `.pyc` sub-case is gitignored (a third, distinct class) — the rationale conflates it with the untracked-unignored files (A2). Empirical `/tmp` repro confirms (a) works end-to-end.
- **Survival test**: YES — (a) is the only alternative covering all remnant classes; the mechanism wording is loose (A2) but the decision holds.

#### Violation scenario: "No descriptive site still claims the recursive `git rm` sweeps untracked files" (acceptance bullet 3 / invariant)
- **Invariant claim**: After the edits, no doc contradicts the fix; verified in part by `grep -rn "git rm -r docs/adr" .claude/workflow`.
- **Violation construction**:
  1. Start state: implementer fixes `create-final-design.md:609`, `workflow.md:764`, `commit-conventions.md:153` (the three sites the acceptance grep covers) but misses `conventions-execution.md:372` (shape `git rm -r _workflow/`).
  2. Action sequence: run the acceptance grep `grep -rn "git rm -r docs/adr" .claude/workflow` → returns no match (verified: `conventions-execution.md` uses `_workflow/`, not `docs/adr`).
  3. Intermediate state: the mechanical invariant check passes.
  4. Violation point: `conventions-execution.md:372`/`:747` still describe `git rm -r _workflow/` — a doc contradicting the fix survives, undetected by the automated check (only "verified by reading" catches it).
  5. Observable consequence: an internal contradiction ships; a Phase-C `review-workflow-consistency` grep of `git rm` re-raises it, or the docs disagree post-merge.
- **Feasibility**: CONSTRUCTIBLE — the pattern/shape mismatch is verified live; the acceptance grep genuinely skips half the D2 footprint.

#### Assumption test: D1 rationale mechanism for the `.pyc` remnants
- **Claim**: `git rm` "silently ignores" the untracked `.pyc` caches, hence the follow-up `rm -rf`.
- **Stress scenario**: A `.pyc` under `staged-workflow/` at cleanup time in the real repo (with `.gitignore` active).
- **Code evidence**: `.gitignore:208-209` globally ignores `*.pyc`/`__pycache__/`; such files are untracked-AND-ignored — `git rm` never considers them, distinct from untracked-unignored files it lists-but-won't-delete. `/tmp` repro (no gitignore) shows `?? cache.pyc` and survival past `git rm -rf`.
- **Verdict**: HOLDS (fix outcome correct — `rm -rf` clears both) but the stated mechanism is imprecise (FRAGILE as an explanation): it conflates gitignored with untracked-unignored.

#### Challenge: single-step, five-file decomposition (SCOPE)
- **Chosen approach**: one atomic step, five files, no split.
- **Best rejected alternative**: split command-fix from prose-reconciliation into two steps.
- **Counterargument trace**:
  1. Splitting creates a commit window where operative commands are `-rf` but descriptive prose still says bare `-r` — a transient internal contradiction.
  2. There is no independently-testable/mergeable sub-unit; the acceptance grep checks the union.
  3. A split is strictly worse (transient inconsistency) with no benefit.
- **Codebase evidence**: 5 files, all word-level prose edits; footprint far under the ~20-25-file split threshold (`planning.md` §Track descriptions).
- **Survival test**: YES — single step is correct.

#### Violation scenario: the added `rm -rf docs/adr/<dir-name>/_workflow/` deletes something it must not / breaks single-cleanup-commit / races §1.7 promotion
- **Invariant claim**: cleanup stays one commit; no live content lost; `_workflow/` fully gone; promotion unaffected.
- **Violation construction**:
  1. Start state: workflow-modifying branch at Phase 4; `_workflow/staged-workflow/.claude/` present.
  2. Action sequence: Step 4 promote (`cp -r "$STAGED_DIR/.claude/." .claude/`; commit; push — `create-final-design.md:535-552`) → Step 5 final-artifacts → Step 6 `git rm -rf docs/adr/<dir>/_workflow/` + `rm -rf docs/adr/<dir>/_workflow/` + commit.
  3. Intermediate state: after Step 4, promoted content lives at live `.claude/workflow/**` etc., already committed; `_workflow/staged-workflow/` is a now-redundant copy.
  4. Violation point: sought a path where `rm -rf` removes live content or runs before promotion — none exists: `rm -rf` is `_workflow/`-scoped and Step 6 runs strictly after Step 4.
  5. Observable consequence: none — only the already-promoted staged copy and ephemeral working files are deleted.
- **Feasibility**: INFEASIBLE — fixed step ordering (Step 4 before Step 6) and `_workflow/`-scoped path preclude it. Empirical `/tmp` repro confirms single commit, clean status, no leftover.
