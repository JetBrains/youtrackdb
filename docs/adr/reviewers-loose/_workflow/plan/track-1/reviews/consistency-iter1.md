<!-- MANIFEST
role: reviewer-plan
phase: 2
review: consistency
tier: minimal
target: track-1
iteration: 1
findings: 1
blockers: 0
flags: none
index:
  - id: CR1
    sev: should-fix
    anchor: "### CR1 [should-fix]"
    loc: "track-1.md §Context and Orientation / §Plan of Work / §Interfaces and Dependencies / D3 — \"sub-step 4a\""
    cert: "Ref: step-implementation.md sub-step 4a label"
    basis: mechanical
evidence_base: "8 Ref certificates over the live .claude/workflow/** files (review-agent-selection.md, code-review-protocol.md, track-code-review.md, step-implementation.md, workflow.md, risk-tagging.md, track-review.md) plus the 10 reviewer agent definitions under .claude/agents/. 7 MATCHES, 1 PARTIAL (the sub-step 4a label). Verified via Read + Grep on prose/section/line numbers — the authoritative tool for markdown prose; no PSI/Java symbol audit in scope, so no grep-vs-PSI reference-accuracy caveat applies."
-->

## Findings

### CR1 [should-fix]
**Certificate**: Ref: step-implementation.md sub-step 4a label
**Location**: `track-1.md` — `## Context and Orientation` (the implicit reference via the routing pointer), `## Plan of Work` step 1 (line 69), `## Interfaces and Dependencies` Out-of-scope bullet (line 114), and Decision Log D3 (lines 38, 40). All say **"sub-step 4a"** of `step-implementation.md`. Code location: `step-implementation.md:421` (`**Sub-step 4 — Dimensional review loop**`) with lettered item `a.` at lines 430-450.
**Issue**: The track labels the step-level dispatch site **"sub-step 4a"**, but `step-implementation.md` has no `4a` token anywhere. The dimensional-review step is **sub-step 4** (heading at line 421), and its lettered sub-items are written as `a.` / `b.` / `c.` / `d.` and cited elsewhere in the workflow as **"sub-step 4(a)"** / **"sub-step 4(b)"** / **"sub-step 4c"** (e.g. `step-implementation.md:64,684,776`, `code-review-protocol.md:64`). The referenced content is real and at the labeled place: item `a.` (lines 430-450) carries the `(see §Step-level vs track-level routing in \`review-agent-selection.md\`)` pointer at lines 437 and 448, and restates the `hook-safety, prompt-design` step-level narrowing at lines 442-443. So this is a label-precision mismatch, not a phantom reference — an implementer would still land in the right place, but the cited label does not match the file's own convention.
**Evidence**: `grep -nE "4a"` over `step-implementation.md` returns nothing; `grep -n "sub-step 4"` shows the canonical forms are `Sub-step 4`, `sub-step 4(a)`, `sub-step 4(b)`, `sub-step 4c`, `sub-step 4(d)`. The pointer and the hook-safety/prompt-design restatement the track attributes to "sub-step 4a" exist verbatim in scope at lines 437/442-443/448, under the `a.` item of sub-step 4. The two skip-gate citations (`code-review-protocol.md §Single-step tracks` 58-64, `track-code-review.md §Single-Step Track` 105-113), the routing SSOT (`review-agent-selection.md §Step-level vs track-level routing` 104-106 / 111-121 / 145-155), `risk-tagging.md §Prose-only workflow steps` 285-309, `track-review.md` coherence 832-844, `workflow.md:348`, and all ten reviewer agent definitions all verified as MATCHES (see Evidence base).
**Proposed fix**: Replace "sub-step 4a" with the file's own citation form throughout the track — e.g. "sub-step 4(a)" (matching `step-implementation.md:684` / `:776`) or "sub-step 4, item a." — at `track-1.md` lines 38, 40, 69, and 114. Mechanical search-and-replace; no semantic change.
**Classification**: mechanical
**Justification**: Current-state claim (the cited structure of `step-implementation.md` at branch baseline, in `## Context and Orientation` / `## Interfaces and Dependencies` / Decision Log — all current-state per the per-section rules), single unambiguous correct rendering (the file's existing `sub-step 4(a)` convention), and the fix changes only the description, not the plan's goals or scope.

## Evidence base

#### Ref: review-agent-selection.md §Step-level vs track-level routing
- **Document claim**: The section exists; it is the single source of truth the step and track dispatch sites consume (track cites lines 104-106); it narrows the step-level baseline to `review-bugs-concurrency` plus glob-matched workflow reviewers and defers the rest (track cites 111-121); and lines 145-155 note the worst case — a high step editing only `.claude/workflow/*.md` matches neither step-level workflow glob and fires zero step-level workflow reviewers.
- **Search performed**: `Read` `review-agent-selection.md:95-164`; `Grep` for "Step-level vs track-level routing".
- **Code location**: `review-agent-selection.md:104-109` (SSOT declaration: "This note is the single source of truth for that timing; the dispatch points in `step-implementation.md` (step) and `track-code-review.md` (track) consume it"); 111-121 (baseline group: at a high step only `review-bugs-concurrency` runs, the other three defer); 145-155 (`**High step editing only \`.claude/workflow/*.md\`.**` — "draws zero step-level reviewers and fully defers to the track pass").
- **Actual signature/role**: Matches on all three cited ranges, line-exact.
- **Verdict**: MATCHES
- **Detail**: none.

#### Ref: code-review-protocol.md §Single-step tracks
- **Document claim**: The section exists and states the single-step-`risk:high` Phase-C skip on the premise "step-level dimensional review already ran against the identical diff" (track cites 58-64; research log cites 55-64). Baseline-deferral mention at lines 30-33.
- **Search performed**: `Read` / `awk` `code-review-protocol.md:25-74`.
- **Code location**: Heading `## Single-step tracks` at line 55; body 58-64 — "Single-step tracks skip the code review portion of Phase C only when the single step is `risk: high` — i.e., step-level dimensional review already ran against the identical diff." Baseline-by-level + override at lines 30-37 (covers the 30-33 mention).
- **Actual signature/role**: Premise wording matches verbatim. Cited 58-64 (track) and 55-64 (research log, heading-inclusive) both correct.
- **Verdict**: MATCHES
- **Detail**: none.

#### Ref: track-code-review.md §Single-Step Track
- **Document claim**: The section exists and states the skip on the "fully reviewed in Phase B" premise (track cites 105-113; research log cites 102-118).
- **Search performed**: `Read` / `awk` `track-code-review.md:98-122`.
- **Code location**: Heading `## Single-Step Track: Skip Code Review, Proceed to Track Completion` at line 102; body 105-113 — "If the track has exactly **1 step** AND that step is tagged `risk: high` ... the step-level review in Phase B already covered the identical diff"; the `(skipped — single-step track, fully reviewed in Phase B)` note at lines 111-112; the medium/low fallthrough at 115-118.
- **Actual signature/role**: Both premises ("already covered the identical diff" / "fully reviewed in Phase B") present. Track's 105-113 and research log's 102-118 (heading-to-fallthrough) both correct.
- **Verdict**: MATCHES
- **Detail**: none.

#### Ref: step-implementation.md sub-step 4a label
- **Document claim**: Sub-step **4a** exists, carries a `(see §Step-level vs track-level routing)` pointer to the routing SSOT, and restates the inline `hook-safety, prompt-design` default-case narrowing.
- **Search performed**: `Read` `step-implementation.md:421-464`; `Grep` for "4a", "sub-step 4", "Per-Step Orchestration".
- **Code location**: `step-implementation.md:421` heading `**Sub-step 4 — Dimensional review loop (only when \`step.risk_tag == 'high'\`)**`; lettered item `a.` at lines 430-450 — pointer `(see §Step-level vs track-level routing in \`review-agent-selection.md\`)` at lines 437 and 448; hook-safety/prompt-design restatement at lines 442-443.
- **Actual signature/role**: The *content* (pointer + hook-safety/prompt-design narrowing) exists exactly as described, inside sub-step 4 item `a.`. But the *label* "4a" does not appear — `grep -nE "4a"` returns nothing; the file's convention is `Sub-step 4` / `sub-step 4(a)` / `sub-step 4c`.
- **Verdict**: PARTIAL
- **Detail**: Content present and correctly characterized; the label "sub-step 4a" mismatches the file's own `sub-step 4(a)` citation form. Drives CR1.

#### Ref: workflow.md:348 resume-state row mentioning the single-step-high skip
- **Document claim**: `workflow.md:348` mentions the single-step-high Phase-C skip in the resume-state table but stays accurate (track `## Interfaces and Dependencies`, Out-of-scope).
- **Search performed**: `awk 'NR==348'` + `sed -n '344,352p'` on `workflow.md`.
- **Code location**: `workflow.md:348` — the `steps-done-review-pending` resume row: "Run Phase C from the current iteration (single-step tracks skip code review but still run track completion — see track-code-review.md:orchestrator,reviewer-dim-track:3C)."
- **Actual signature/role**: Line 348 is in the resume-state table and references the single-step code-review skip. The fix only adds a precondition and keeps the skip, so the row stays accurate — matching the track's out-of-scope rationale.
- **Verdict**: MATCHES
- **Detail**: none.

#### Ref: risk-tagging.md prose-only cap
- **Document claim**: A workflow-prose change is capped at `risk:low` only when it changes no review gate and no reviewer-dispatch logic (research log cites 285-309).
- **Search performed**: `sed -n '280,315p'` + `grep -n "Prose-only workflow steps"` on `risk-tagging.md`.
- **Code location**: Heading `## Prose-only workflow steps` at line 282; cap at 285-291 — "A workflow-machinery step that edits ONLY prose (no hook/script/settings change AND no gate/dispatch/schema change) is at most `low`"; full qualifier restated at 305-309.
- **Actual signature/role**: "no gate/dispatch/schema change" is the file's rendering of the track/research-log paraphrase "changes no review gate and no reviewer-dispatch logic." The branch's edit changes both the single-step skip gate and the step-level reviewer-dispatch rule, so the cap correctly does not apply (current-state, consistent).
- **Verdict**: MATCHES
- **Detail**: none.

#### Ref: track-review.md high-step coherence rule
- **Document claim**: Coherence is mandatory for `high` steps; a single coherent HIGH change stays in one step ("file count alone never forces a split") (research log cites 832-844).
- **Search performed**: `sed -n '828,848p'` on `track-review.md`.
- **Code location**: `track-review.md:831-839` — "**Coherence (mandatory for `high`, preferred for `low`/`medium`).** For `high` steps coherence is mandatory ... coherence alone forces a split only at `high`, and file count alone never forces a split."
- **Actual signature/role**: Verbatim match for both the mandatory-coherence-at-high claim and the "file count alone never forces a split" clause, within the cited 832-844 window.
- **Verdict**: MATCHES
- **Detail**: none.

#### Ref: reviewer-group agent names (baseline + workflow groups)
- **Document claim**: Baseline group — `review-code-quality`, `review-test-behavior`, `review-test-completeness`; workflow group — `review-workflow-consistency`, `review-workflow-context-budget`, `review-workflow-writing-style`, `review-workflow-instruction-completeness`, `review-workflow-hook-safety`, `review-workflow-prompt-design`, `review-bugs-concurrency`. None phantom.
- **Search performed**: `ls .claude/agents/ | grep -E "review-(...)"` for all ten names; cross-checked against `review-agent-selection.md` §Step-level routing references.
- **Code location**: `.claude/agents/` — `review-bugs-concurrency.md`, `review-code-quality.md`, `review-test-behavior.md`, `review-test-completeness.md`, `review-workflow-consistency.md`, `review-workflow-context-budget.md`, `review-workflow-hook-safety.md`, `review-workflow-instruction-completeness.md`, `review-workflow-prompt-design.md`, `review-workflow-writing-style.md` (all present). Names also appear in `review-agent-selection.md:112-143`.
- **Actual signature/role**: All ten reviewer-name references resolve to real agent definition files. No phantom reviewer.
- **Verdict**: MATCHES
- **Detail**: none.

#### Gap: orphan constructs the track should reference but does not
- **Document claim**: (orphan-construct bullet — the only GAPS bullet that runs under `minimal`) The track should reference every in-scope workflow construct its fix touches.
- **Search performed**: Cross-checked the track's enumerated in-scope/out-of-scope files (`review-agent-selection.md`, `code-review-protocol.md`, `track-code-review.md`, `step-implementation.md`, `workflow.md:348`) against the live files' single-step/step-level routing surface.
- **Code location**: The track's `## Interfaces and Dependencies` covers all three editable files plus the two out-of-scope consumers (`step-implementation.md` sub-step 4 item `a.`, `workflow.md:348`). No additional gate file references the single-step-high skip premise: `grep`-level survey of the routing surface surfaces no further consumer the track omits.
- **Actual signature/role**: No orphan construct found. The `step-implementation.md` sub-step 4(a) consumer is referenced (under the mislabeled "4a"); `workflow.md:348` is referenced; the two skip-gate homes are referenced.
- **Verdict**: MATCHES
- **Detail**: none (the sub-step 4(a) consumer is referenced, just under the wrong label — captured as CR1, not as an orphan gap).
