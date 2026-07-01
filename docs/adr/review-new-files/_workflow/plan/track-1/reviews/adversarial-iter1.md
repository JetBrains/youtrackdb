<!-- workflow-sha: 38bd7a0b1539ec1b3529e077fa0fba57df312574 -->
# Adversarial review — Track 1 (iter1)

- **Track:** Track 1: Cover genuinely-new staged files in Phase B/C review scope
- **Scope:** Phase 3A track-realization review, narrowed per D9 + Track-1 exception (scope/sizing RUNS, cross-track-episode DROPPED, invariant-violation RUNS; the D1/D2/D3 decisions themselves are NOT re-challenged — the Phase 0→1 research-log gate vetted them across two iterations).
- **Tooling:** mcp-steroid reachable; `review-new-files` project open and matches the working tree. This track names no Java symbols — every reference is a workflow file path or `§`-anchor, so the Workflow-machinery criteria (grep + Read for path/anchor resolution) apply and no `findClass` is used. Symbol audits therefore carry no grep reference-accuracy caveat: the audits here are file-path and prose-content greps, which grep resolves exactly.
- **Verdict:** PASS — 0 blocker, 3 should-fix, 2 suggestion.

## Findings

### A1 [should-fix]
**Certificate**: Violation scenario — Inv 2 (context block separates the two cases) — residual empty-file gate
**Target**: Invariant 2 / Plan of Work (d) — context-block rewrite
**Challenge**: The context block's rewrite risk is not only the blanket "the rest is out of scope" sentence D1/A2 already flagged. The block opens with a second gate the track's Plan of Work (d) does not name: the current text keys everything on the delta file being *non-empty* ("When that file is non-empty, scope your findings to the delta … When it is empty … review the diff as usual" — `track-code-review.md:461-465`, `step-implementation.md:617-621`). Post-fix a delta file that contains only `=== NEW staged file … ===` markers (a track that adds one new agent and edits nothing live) is non-empty, so it enters the "scope your findings to the delta" branch. If the rewrite replaces the inner "out of scope" sentence but leaves the outer non-empty gate saying "scope your findings to the delta", a NEW-only delta file routes the reviewer into a delta-scoping instruction with no delta lines to scope to — the reviewer reads a marker naming a file and an instruction to "scope to the delta" that has no delta. The reviewer might reasonably conclude there is nothing to review (empty delta after the marker), reintroducing the unreviewed-file bug through the non-empty-but-delta-less path.
**Evidence**: `track-code-review.md:461` "When that file is non-empty, scope your findings to the delta"; the else-branch marker (Plan of Work (b)) writes only a header line and a blank line, no `diff` body, so a NEW-only delta file is non-empty yet carries zero delta lines.
**Proposed fix**: The rewrite must move the delta-vs-NEW distinction to be **per-entry keyed on the marker**, and drop or subordinate the outer "when non-empty, scope to the delta" framing — the non-empty gate can only decide "read the delta file at all vs skip it", not "scope to the delta" as a blanket instruction. Plan of Work (d) already says "per-entry, mutually-exclusive distinction keyed on the marker" and the empty case "still says review the diff as usual"; make the acceptance criterion explicit that the *non-empty* framing is also rewritten so it does not pre-commit every non-empty file to delta-scoping. This strengthens Inv 2's check ("no residual blanket sentence") to also cover the non-empty gate, not just the "out of scope" sentence.

### A2 [should-fix]
**Certificate**: Assumption test — reviewers actually launch on a NEW staged file
**Target**: Assumption (implicit) — the fix makes NEW files reviewed
**Challenge**: The track's goal is "a genuinely-new staged file … is now reviewed in full." The context-block rewrite tells a reviewer, once launched, to review a NEW file in full. But it takes for granted that the dimensional reviewers are *dispatched* on the NEW file at all. Reviewer dispatch is gated separately, in `code-review/SKILL.md:246` (Staged-path normalization) and `review-agent-selection.md:362`: staged `.claude/…` paths are string-normalized to their live `.claude/…` form before the Step 5b glob match, so the glob-gated workflow reviewers launch. Stress scenario: a track adds a brand-new `.claude/agents/foo.md`. Its staged path `docs/adr/<dir>/_workflow/staged-workflow/.claude/agents/foo.md` normalizes (pure prefix-strip, no live-existence check) to `.claude/agents/foo.md`, which matches the workflow-review globs — so the reviewers DO launch. The assumption holds. But it holds because of a mechanism in a *third* file the track does not mention and treats as out of scope; if that normalization ever regressed to a live-existence check, the fix in these two files would silently do nothing (reviewers never launch, context block never consulted).
**Evidence**: `code-review/SKILL.md:246` "a path matching the anchored prefix … is replaced by its captured `.claude/…` remainder" — a pure string operation, no `[ -f "$live" ]`-style existence check, so NEW files normalize and match globs. Verdict: HOLDS.
**Proposed fix**: No code change needed — the assumption holds. Strengthen the track's rationale by adding one sentence to `## Context and Orientation` or `## Interfaces and Dependencies` noting that reviewer *dispatch* for a NEW staged file already works via the Step 5b staged-path normalization (`code-review/SKILL.md`, `review-agent-selection.md`), so this track only needs to fix the *scoping* the launched reviewer is told. This records why the fix is complete at two files and pre-empts a future reader asking "but do the reviewers even run on the new file?".

### A3 [should-fix]
**Certificate**: Violation scenario — Inv 3 (cross-file consistency) — burden-measure prose drift
**Target**: Invariant 3 / Invariant 5 — cross-file consistency and no-third-copy
**Challenge**: Inv 3 constrains the two edited files to diverge only in temp-file path and indentation, and Inv 5 asserts no third file carries a copy of the loop or context block. Both hold for the loop and context block. But `track-code-review.md:341` (step 9, review-burden measurement) carries prose that *reasons about* step 8's output: "the `diff <live> <staged>` delta from step 8 is the truer measure of what a reviewer reads." Post-fix, for a NEW file there is no `diff <live> <staged>` delta — the whole-file line count *is* the true review surface, the opposite of what step 9 asserts for the delta case. This is not a copy of the loop (Inv 5 is not violated) and not in the two setup regions (Inv 3 is not violated), but it is prose that will read as stale/contradictory to a reviewer who just learned NEW files are reviewed in full. Feasibility: CONSTRUCTIBLE as a coherence gap a consistency review would flag; the track's own D1 rationale ("editing a subset leaves a contradiction that a consistency review flags") is the standard being applied here.
**Evidence**: `track-code-review.md:338-342` — the burden-measure caveat assumes every freshly-staged copy is a `diff <live> <staged>`-shrinkable whole-file copy; a NEW file is a whole-file copy that does NOT shrink under a delta.
**Proposed fix**: Either add step 9's burden caveat to the track's in-scope edits (a one-clause qualifier: "except a NEW-file staged add, whose whole-file count is the true review surface"), or explicitly record it in `## Interfaces and Dependencies` → Out of scope with a note that the step-9 caveat stays accurate because a NEW file's whole-file count already reflects real review surface. The track currently names only `conventions.md §1.7(k)` as the reviewed-adjacent-but-out-of-scope reference; step 9 is a closer neighbor and should be dispositioned so Phase C does not surface it as an unexpected inconsistency.

### A4 [suggestion]
**Certificate**: Scope/sizing challenge — split the single step vs. keep it whole
**Target**: Scope and sizing (single-step decomposition)
**Challenge**: The track is one step covering 8 edit points across 2 files. Could it be split — e.g., one commit per file, or a "loop + preamble + post-loop" commit and a separate "context block rewrite" commit? Argument for splitting: the context block is a rewrite (higher-risk, D1) while the else branch is a mechanical add; separating them isolates the risky edit. Argument against (survives): D1 establishes the defect is *one logical bug* whose scoping is stated four times per file, and that editing a subset leaves a self-contradiction a consistency review flags. A per-file split would land `track-code-review.md` fully fixed while `step-implementation.md` still contradicts the code across a commit boundary — exactly the "Phase B copy silently under-covering" hazard D1 rejects. A loop-vs-context-block split would leave the loop emitting NEW markers while the context block still folds them into "out of scope" between commits. Both splits create an intermediate committed state where the prose contradicts the code. Survival: YES — the single-step decomposition is correct; the eight edits are atomic-by-necessity because any partial state is internally inconsistent.
**Evidence**: Track D1 "Editing a subset leaves a contradiction that a consistency review flags"; the four locations per file are mutually dependent (loop emits marker → post-loop must describe it → context block must scope it → preamble must announce it).
**Proposed fix**: None — keep the single step. Recording the survival test here strengthens the rationale for why an 8-edit-point change is nonetheless one commit (it is not "too much for one step"; it is the minimal internally-consistent unit).

### A5 [suggestion]
**Certificate**: Violation scenario — Inv 1 (NEW markers cover no-counterpart adds) — derivation-collision edge
**Target**: Invariant 1
**Challenge**: Inv 1 claims a staged add with no live counterpart always appears under a NEW marker, never silently absent. Construct the near-miss: the else branch fires when `[ -f "$live" ]` is false, i.e., the derived live path does not exist *on disk in the working tree at review time*. Consider a track that stages a copy of a live file AND, in the same range, deletes the live original (a rename-via-stage). The staged copy's derived live path no longer exists on disk, so `[ -f "$live" ]` is false and the file is recorded under a NEW marker — but it is not genuinely new, it is a copy of a now-deleted live file, and its true review surface is the delta against the *deleted* file's `develop` content, not a full read. Feasibility: THEORETICAL — the workflow's staging discipline (`conventions.md §1.7`, staged copy mirrors the live path; the live file is not deleted while staged) makes a stage+delete-original in one range an off-pattern operation, and even if it happened, reviewing the file in full is conservative (over-review, not under-review — the Inv 1 safety direction). No data loss, only extra review work.
**Evidence**: Plan of Work (b) else branch keys purely on `[ -f "$live" ]` (working-tree existence), not on whether the file was ever live on `develop`; `conventions.md §981` "Each staged file mirrors its live counterpart's relative path" — the discipline keeps the live file present.
**Proposed fix**: None required — the edge is off-pattern and its failure mode is conservative over-review. Optionally note in the Validation section that the else branch keys on working-tree existence of the derived live path, so the (rare) stage-a-copy-then-delete-original case reviews in full, which is safe.

## Evidence base

### Scope / sizing challenges

#### Challenge: Single-step decomposition of an 8-edit-point, 2-file change
- **Chosen approach**: One step, one commit, all eight edit points (four per file) landed together (D1; track `## Plan of Work` "Apply the same four-location fix to each file").
- **Best rejected alternative**: Split into per-file commits, or a "mechanical loop/preamble/post-loop" commit plus a separate "context-block rewrite" commit to isolate the higher-risk rewrite.
- **Counterargument trace**:
  1. In a per-file split, after commit 1 `track-code-review.md` is fully fixed but `step-implementation.md:508` still says the trigger "fires only on a new-file add … that has a live counterpart" while — no, its loop is unchanged too, so it is internally consistent but under-covering; the cross-file drift (Inv 3) is transiently real across the commit boundary.
  2. In a loop-vs-context-block split, after commit 1 the loop emits `=== NEW staged file … ===` (Plan of Work (b)) but the context block (`track-code-review.md:461`) still folds any non-empty delta into "out of scope" — the loop and block contradict within one file.
  3. This produces a committed intermediate state where prose contradicts code, exactly the failure D1 rejects.
- **Codebase evidence**: Track D1 "Editing a subset leaves a contradiction that a consistency review flags"; the four per-file locations are mutually dependent (`track-code-review.md:271, 283-289, 293-295, 461-465`).
- **Survival test**: YES — the single-step decomposition is correct; the eight edits form the minimal internally-consistent unit and any split creates a contradictory committed state.

### Invariant challenges

#### Violation scenario: Inv 2 — the context block separates the two cases with no residual blanket sentence
- **Invariant claim**: The rewritten context block presents delta-scoped and NEW as a per-entry mutually-exclusive distinction keyed on the marker, with no residual blanket sentence folding a NEW file into "out of scope".
- **Violation construction**:
  1. Start state: a track adds one new `.claude/agents/foo.md` and edits no live file. The step-8 loop's else branch (Plan of Work (b)) writes `=== NEW staged file (no live counterpart): …/foo.md ===` plus a blank line to the delta temp file; no `=== delta: … ===` entry exists.
  2. Action sequence: reviewer reads the context block (`track-code-review.md:461-465` post-rewrite). The rewrite (per Plan of Work (d)) fixes the inner "the rest is out of scope" sentence but the *outer* gate still reads "When that file is non-empty, scope your findings to the delta".
  3. Intermediate state: the delta file is non-empty (it has the NEW marker line), so the reviewer enters the "scope your findings to the delta" branch.
  4. Violation point: `track-code-review.md:461` — "scope your findings to the delta" for a file that has a marker but zero delta lines; the reviewer has no delta to scope to and may read "nothing in the delta → nothing to review."
  5. Observable consequence: the NEW file is again effectively unreviewed, this time through the non-empty-but-delta-less path rather than the missing else branch — the original bug re-expressed.
- **Feasibility**: CONSTRUCTIBLE — a NEW-only staged track is the exact motivating case for this fix; whether it manifests depends entirely on whether the rewrite also reworks the outer non-empty gate, not just the inner "out of scope" sentence.

#### Violation scenario: Inv 3 / Inv 5 — cross-file consistency and no third copy, vs. the step-9 burden-measure prose
- **Invariant claim**: The two files diverge only in temp-file path and indentation (Inv 3); no third file carries a copy of the loop or context block (Inv 5).
- **Violation construction**:
  1. Start state: fix landed; `track-code-review.md:341` (step 9, review-burden measurement) still reads "the `diff <live> <staged>` delta from step 8 is the truer measure of what a reviewer reads."
  2. Action sequence: a Phase C consistency reviewer reads step 8 (NEW files reviewed in full) then step 9 (burden of a staged copy is measured by its delta).
  3. Intermediate state: for a NEW file there is no `diff <live> <staged>` delta; the whole-file count is the real surface.
  4. Violation point: `track-code-review.md:341` — the caveat asserts the delta is the truer measure, which is false for the NEW-file case step 8 now handles.
  5. Observable consequence: a coherence gap between step 8 and step 9 that a consistency review flags — not a copy of the loop (Inv 5 intact) nor a setup-region drift (Inv 3 intact), but adjacent prose that reads stale.
- **Feasibility**: CONSTRUCTIBLE — the track's own D1 standard ("a subset edit leaves a contradiction a consistency review flags") applies to this neighbor; it should be dispositioned in scope or explicitly out of scope.

#### Violation scenario: Inv 1 — NEW markers cover no-counterpart adds
- **Invariant claim**: A staged add with no live counterpart always appears under a NEW marker, never silently absent.
- **Violation construction**:
  1. Start state: a track stages a copy of live file `X` AND deletes the live `X` in the same reviewed range (stage-as-rename).
  2. Action sequence: the loop derives `live` for the staged copy; `[ -f "$live" ]` is now false (live `X` was deleted).
  3. Intermediate state: the else branch fires; the file is recorded under a NEW marker.
  4. Violation point: the file is labeled NEW though it is a copy of a formerly-live file; its true review surface is the delta against the deleted file, not a full read.
  5. Observable consequence: the reviewer reads it in full — conservative over-review, not under-review; no correctness loss.
- **Feasibility**: THEORETICAL — off-pattern per `conventions.md §981` (staged copy mirrors the live path; the live file is not concurrently deleted), and the failure direction is safe (over-review).

### Assumption challenges

#### Assumption test: reviewers are dispatched on a NEW staged file at all
- **Claim**: Fixing the context block in these two files makes a NEW staged file reviewed in full.
- **Stress scenario**: A NEW staged file must (a) be dispatched to the workflow reviewers and (b) be scoped as review-in-full. This track fixes (b); (a) lives in `code-review/SKILL.md:246` staged-path normalization, a file the track treats as out of scope.
- **Code evidence**: `code-review/SKILL.md:246` normalizes `docs/adr/<dir>/_workflow/staged-workflow/(\.claude/…)` → `.claude/…` by pure prefix-strip with no live-existence check, so a NEW staged path matches the Step 5b workflow-review globs and the reviewers launch. `review-agent-selection.md:362` carries the same rule.
- **Verdict**: HOLDS — dispatch works for NEW files today; the fix's two-file footprint is complete. Worth a one-sentence rationale note so the completeness is visible.

### Ordinary-plan inertness (Inv 4) — confirmed, no finding

`--diff-filter=A -- 'docs/adr/*/_workflow/staged-workflow/.claude/*'` returns zero entries on a plan with no staged adds (an ordinary, non-workflow-modifying plan produces no `staged-workflow/` tree at all), so the loop iterates zero times, the delta file is empty, and the context block's empty-file clause ("review the diff as usual") keeps it inert. The else branch is unreachable without a staged add present. Inv 4 holds with no construction available; no finding.

---

## Manifest

```yaml
verdict: PASS
findings: 5
blocker: 0
should_fix: 3
suggestion: 2
index:
  - id: A1
    sev: should-fix
    anchor: "### A1 "
    loc: "track-code-review.md:461; step-implementation.md:617"
    cert: "Violation scenario — Inv 2 residual empty/non-empty gate"
    basis: invariant
  - id: A2
    sev: should-fix
    anchor: "### A2 "
    loc: "code-review/SKILL.md:246; review-agent-selection.md:362"
    cert: "Assumption test — reviewers launch on NEW staged file"
    basis: assumption
  - id: A3
    sev: should-fix
    anchor: "### A3 "
    loc: "track-code-review.md:341"
    cert: "Violation scenario — Inv 3/Inv 5 step-9 burden-measure drift"
    basis: invariant
  - id: A4
    sev: suggestion
    anchor: "### A4 "
    loc: "track-1.md Plan of Work; D1"
    cert: "Scope/sizing — single-step decomposition survives"
    basis: scope
  - id: A5
    sev: suggestion
    anchor: "### A5 "
    loc: "track-code-review.md:283-289 else branch"
    cert: "Violation scenario — Inv 1 stage-then-delete edge (theoretical, safe)"
    basis: invariant
evidence_base:
  challenges: 1
  violation_scenarios: 3
  assumption_tests: 1
```
