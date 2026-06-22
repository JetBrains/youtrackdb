<!-- MANIFEST
verdict: NEEDS REVISION
findings: 3
review: technical
iteration: 1
track: 1
phase: 3A
prefix: T
evidence_base: 12 certificates (9 premise, 1 edge case, 2 integration); all reference-accuracy premises CONFIRMED via grep+Read over the three in-scope files plus step-implementation.md, risk-tagging.md, workflow.md.
index:
  - id: T1
    sev: should-fix
    anchor: T1
    loc: review-agent-selection.md:145-155 (in-scope §Step-level vs track-level routing); track-1.md Plan of Work step 1 / Interfaces "Out of scope"
    cert: "Premise: §145-155 zero-reviewer paragraph"
    basis: rule-coherence
  - id: T2
    sev: should-fix
    anchor: T2
    loc: track-1.md Plan of Work step 1; review-agent-selection.md §Step-level vs track-level routing (two group paragraphs, lines 111-143)
    cert: "Premise: two-group structure of §Step-level routing"
    basis: instruction-completeness
  - id: T3
    sev: suggestion
    anchor: T3
    loc: track-1.md Validation and Acceptance bullet 2; review-agent-selection.md §Per-agent file-pattern triggers
    cert: "Premise: per-agent globs for a workflow-only step"
    basis: prompt-design-soundness
-->

# Technical review — Track 1, iteration 1

Verdict: **NEEDS REVISION** (0 blocker, 2 should-fix, 1 suggestion).

The track's approach is sound and its reference accuracy is clean — every workflow file path, §-anchor, and cited line range resolves to the prose the track claims, the §1.7(k) opt-out / unavoidably-high self-trap analysis is correct against live `risk-tagging.md`, and the D4 generalization holds against how `review-agent-selection.md` and `code-review-protocol.md` define the step/track reviewer split. The two should-fix findings are completeness gaps in the Plan of Work, both inside the already-in-scope `review-agent-selection.md` §Step-level vs track-level routing section: a third paragraph in that section directly contradicts the widening rule and the plan does not name it for amendment (T1), and the plan does not say the widened paragraph must cover both reviewer groups (T2).

## Findings

### T1 [should-fix]
**Certificate**: Premise — "§145-155 zero-reviewer paragraph" (CONFIRMED).
**Location**: `.claude/workflow/review-agent-selection.md:145-155` (the "High step editing only `.claude/workflow/*.md`" paragraph, inside the in-scope §Step-level vs track-level routing section); `track-1.md` Plan of Work step 1 and `## Interfaces and Dependencies` "Out of scope".
**Issue**: The §Step-level routing section the track widens contains a third paragraph (lines 145-155) that states: *"a step matches neither step-level workflow trigger … so it draws zero step-level reviewers and fully defers to the track pass. This is correct on its own terms …"* — closing *"A single step's slice of a multi-file gate change cannot be checked for completeness in isolation."* After Move 1 adds the single-step-high widening rule, this paragraph **directly contradicts it** for the exact population the fix targets: a single-step `risk:high` `.claude/workflow/*.md` track (this branch is one) now must run the full selection at the step, yet this paragraph still asserts the same step "draws zero step-level reviewers and fully defers" and that doing so is "correct." Two live statements in one in-scope section then say opposite things about the same step. This is a rule-coherence defect that `review-workflow-consistency` would flag, and the Plan of Work does not call this paragraph out — step 1 only says "Widen the step-level selection," and the track's `## Context and Orientation` cites lines 145-155 merely as background (the worst-case note), not as an edit target. The contradiction is not cosmetic: the zero-reviewer paragraph's stated rationale (a multi-file gate change can only be judged completeness-wise on the cumulative diff) is precisely what the skip-gate premise correction overrides for the single-step case, where there *is* no cumulative track pass.
**Proposed fix**: Add the lines-145-155 paragraph to the Plan of Work step 1 edit scope and to `## Interfaces and Dependencies` in-scope notes. The paragraph must be narrowed so its "zero step-level reviewers / fully defers" claim is explicitly scoped to **multi-step** high `.claude/workflow/*.md` steps (where the Phase C track pass does run and the deferral loses nothing), and the new single-step-high carve-out is stated as the exception. Keep the paragraph's underlying insight (the multi-file gate-completeness argument) but condition its "fully defers" conclusion on the track pass actually running.

### T2 [should-fix]
**Certificate**: Premise — "two-group structure of §Step-level routing" (CONFIRMED).
**Location**: `track-1.md` Plan of Work step 1; `.claude/workflow/review-agent-selection.md` §Step-level vs track-level routing — the **Baseline group** paragraph (lines 111-121) and the **Workflow-review group** paragraph (lines 123-143).
**Issue**: §Step-level routing does not state its timing in one place; it splits the rule across two group paragraphs — the baseline group (only `review-bugs-concurrency` at the step; the other three defer) and the workflow-review group (only `hook-safety` + `prompt-design` at the step; the other four defer). The D4 generalization the track commits to ("full track-pass-equivalent selection" — both classes) therefore has to **override the per-group narrowing in both paragraphs** for the single-step-high case. Plan of Work step 1 states the rule as a single widening paragraph but does not say where it lands relative to the two existing group paragraphs or how a reader reconciles "full selection at the step" against the still-present "only `review-bugs-concurrency`" / "only `hook-safety`, `prompt-design`" narrowings sitting immediately above it. Without that, an implementer could add the widening paragraph and leave both group paragraphs' narrowings unqualified, producing an under-specified rule: a Phase-B dispatch reader hitting the baseline paragraph first would still narrow to `review-bugs-concurrency`. This is the risk D3's own Risks/Caveats flags ("that paragraph must state the rule prominently enough that every dispatch site applies it") but the Plan of Work does not operationalize it against the two-paragraph structure.
**Proposed fix**: In Plan of Work step 1, specify that the single-step-high widening is stated as an explicit override that **both** the baseline-group and workflow-review-group narrowing paragraphs point to (e.g., a lead sentence in each group paragraph: "unless the high step is the sole step of its track — then the full group runs, see below"), or that the widening paragraph is positioned and worded so a dispatch reader cannot apply a group narrowing without first seeing the single-step-high exception. State which structural choice the implementer takes so the rule is deterministic at both dispatch reads, not just present somewhere in the section.

### T3 [suggestion]
**Certificate**: Premise — "per-agent globs for a workflow-only step" (CONFIRMED).
**Location**: `track-1.md` `## Validation and Acceptance` bullet 2; `.claude/workflow/review-agent-selection.md` §Per-agent file-pattern triggers.
**Issue**: Acceptance bullet 2 reads: *"A single-step `risk:high` workflow-only track runs `review-workflow-consistency` plus the other always-on and glob-triggered workflow reviewers at the step."* For a `.claude/workflow/*.md`-only diff, the §Per-agent file-pattern triggers table fires `review-workflow-consistency` + `review-workflow-context-budget` (always-launched) + `review-workflow-instruction-completeness` (`.claude/workflow/*.md` glob) + `review-workflow-writing-style` (`.claude/**/*.md` glob) — i.e., four of the six; `prompt-design` and `hook-safety` do not match a bare `.md` workflow-rule edit (confirmed against the worked "Workflow-only diff" example at lines 321-326). The phrase "glob-triggered workflow reviewers" is correct but is the weakest part of the acceptance set: it leaves the exact expected roster implicit, and the whole point of the fix is that the *deferred* reviewers (consistency/context-budget/writing-style/instruction-completeness) now fire at the step. The acceptance criterion would be more falsifiable if it named the expected four-reviewer roster for the workflow-only case, mirroring the precision of bullet 4 (which enumerates the three Java baselines).
**Proposed fix**: Reword Validation bullet 2 to enumerate the expected workflow-only roster: `review-workflow-consistency`, `review-workflow-context-budget`, `review-workflow-instruction-completeness`, and `review-workflow-writing-style` (the four that previously deferred), explicitly noting `prompt-design`/`hook-safety` stay glob-gated off a bare `.claude/workflow/*.md` diff. This makes the self-application acceptance check (the branch's own Phase C) a concrete roster comparison rather than a judgment call.

## Evidence base

#### Premise: review-agent-selection.md §Step-level vs track-level routing is the declared single source of truth at lines 104-106
- **Track claim**: D3 / Plan-of-Work — `review-agent-selection.md:104-106` declares §Step-level routing the single source of truth that the step and track dispatch sites consume.
- **Search performed**: grep + Read, `.claude/workflow/review-agent-selection.md:94-171`.
- **Code location**: review-agent-selection.md:104-109.
- **Actual behavior**: Lines 104-106 read "This note is the single source of truth for that timing; the dispatch points in `step-implementation.md` (step) and `track-code-review.md` (track) consume it." Exactly as the track claims.
- **Verdict**: CONFIRMED.

#### Premise: review-agent-selection.md:145-155 notes the zero-step-level-workflow-reviewer worst case
- **Track claim**: Context/Orientation — lines 145-155 already note the worst case (a high step editing only `.claude/workflow/*.md` fires zero step-level workflow reviewers).
- **Search performed**: Read review-agent-selection.md:145-155.
- **Code location**: review-agent-selection.md:145-155.
- **Actual behavior**: The paragraph "High step editing only `.claude/workflow/*.md`" states the step "draws zero step-level reviewers and fully defers to the track pass" and calls this "correct on its own terms." Confirms the track's citation — and is the source of finding T1 (it will contradict the new widening rule).
- **Verdict**: CONFIRMED.

#### Premise: §Step-level routing splits timing across two group paragraphs
- **Track claim**: D4 — widen to the full track-pass-equivalent selection covering both the deferred baselines and the deferred workflow reviewers.
- **Search performed**: Read review-agent-selection.md:111-143.
- **Code location**: review-agent-selection.md:111-121 (Baseline group), 123-143 (Workflow-review group).
- **Actual behavior**: Baseline paragraph: at a high step only `review-bugs-concurrency` runs; the other three defer. Workflow paragraph: only `hook-safety` + `prompt-design` run at the step; the other four defer. The timing rule is split across two paragraphs, each narrowing its own group. Source of finding T2 (the widening must override both).
- **Verdict**: CONFIRMED.

#### Premise: code-review-protocol.md §Single-step tracks at lines 58-64 states the skip on the "identical diff" premise
- **Track claim**: Plan-of-Work step 2 / Context — `code-review-protocol.md` §Single-step tracks (lines 58-64) skips Phase C on the "step-level dimensional review already ran against the identical diff" premise.
- **Search performed**: Read code-review-protocol.md:55-65.
- **Code location**: code-review-protocol.md:58-64.
- **Actual behavior**: "Single-step tracks skip the code review portion of Phase C only when the single step is `risk: high` — i.e., step-level dimensional review already ran against the identical diff." Matches verbatim; this is the false premise the fix corrects.
- **Verdict**: CONFIRMED.

#### Premise: track-code-review.md §Single-Step Track at lines 105-113 states the skip on the "fully reviewed in Phase B" premise
- **Track claim**: Plan-of-Work step 3 / Context — `track-code-review.md` §Single-Step Track (lines 105-113) states the skip on the "fully reviewed in Phase B" premise.
- **Search performed**: Read track-code-review.md:102-118.
- **Code location**: track-code-review.md:105-113.
- **Actual behavior**: "If the track has exactly 1 step AND that step is tagged `risk: high` … the code review portion of Phase C is skipped — the step-level review in Phase B already covered the identical diff …" plus the Progress note "(skipped — single-step track, fully reviewed in Phase B)". Matches; second home of the false premise.
- **Verdict**: CONFIRMED.

#### Premise: step-implementation.md sub-step 4(a) carries the pointer and is not the routing rule's home
- **Track claim**: D3 / Plan-of-Work — sub-step 4(a) only restates the rule and defers via its existing `(see §Step-level vs track-level routing)` pointer; it is out of scope under the opt-out.
- **Search performed**: grep + Read step-implementation.md:421-450.
- **Code location**: step-implementation.md:430-450 (sub-step 4 "a. Select review agents").
- **Actual behavior**: Sub-step 4(a) narrows the baseline to `review-bugs-concurrency`, names the step-level workflow reviewers (`hook-safety`, `prompt-design`) by their file-pattern triggers, and carries **two** `(see §Step-level vs track-level routing in review-agent-selection.md)` pointers (lines 437-438, 448-449). It restates and defers; it is the dispatch consumer, not the rule's home. Confirms the widened rule placed in §Step-level routing reaches this dispatch site without editing 4(a) — D3 is correct.
- **Verdict**: CONFIRMED.

#### Premise: risk-tagging.md HIGH "Workflow machinery" makes a gate/control-flow edit HIGH
- **Track claim**: D2/D3 / Context — `risk-tagging.md` caps a workflow-prose change at `risk:low` only when it changes no gate and no dispatch logic; this edit changes both, so the cap does not apply and the step stays `high`.
- **Search performed**: Read risk-tagging.md:159-180 (HIGH "Workflow machinery") and 282-309 (prose-only cap).
- **Code location**: risk-tagging.md:171-173 (HIGH: "Edits a load-bearing gate or control-flow protocol … the review-iteration protocol …"); risk-tagging.md:284-289, 306-307 (cap requires "no gate/dispatch/schema change").
- **Actual behavior**: HIGH category line 171-173 makes editing "a load-bearing gate or control-flow protocol" HIGH. The prose-only cap (line 286, 306) explicitly excludes any edit with a "gate/dispatch/schema change." This edit changes the single-step skip gate and the step-level reviewer-dispatch rule, so the cap cannot apply. The self-trap analysis (the branch's single step is unavoidably `high`) is accurate against live prose.
- **Verdict**: CONFIRMED.

#### Premise: §Per-agent file-pattern triggers — workflow-only diff fires four reviewers
- **Track claim**: Validation bullet 2 — a single-step `risk:high` workflow-only track runs `review-workflow-consistency` plus the other always-on and glob-triggered workflow reviewers at the step.
- **Search performed**: Read review-agent-selection.md:226-233 (triggers table) and 321-326 (worked workflow-only example).
- **Code location**: review-agent-selection.md:228-233; 321-326.
- **Actual behavior**: For a `.claude/workflow/*.md`-only diff the fired set is `review-workflow-consistency` + `review-workflow-context-budget` (always-launched) + `review-workflow-instruction-completeness` (`.claude/workflow/*.md`) + `review-workflow-writing-style` (`.claude/**/*.md`) = four; `prompt-design` and `hook-safety` do not match a bare workflow-rule `.md` edit. The worked example at 321-326 confirms exactly this four-reviewer set. Acceptance bullet 2 is correct but under-specified — source of suggestion T3.
- **Verdict**: CONFIRMED (acceptance phrasing is loose, not wrong).

#### Premise: D4 generalization — the three deferred baselines for a Java single-step-high track
- **Track claim**: D4 / Validation bullet 4 — a single-step-high Java track also drops `review-code-quality`, `review-test-behavior`, `review-test-completeness`; the full-selection rule recovers them.
- **Search performed**: Read review-agent-selection.md:111-121; code-review-protocol.md:30-37.
- **Code location**: review-agent-selection.md:111-114; code-review-protocol.md:30-32.
- **Actual behavior**: "At a high step only `review-bugs-concurrency` runs; `review-code-quality`, `review-test-behavior`, and `review-test-completeness` defer to the track pass." code-review-protocol.md:30-32 restates the same. The same defer-to-track-pass mechanism that drops the four workflow reviewers also drops these three baselines for a Java single-step-high track. The D4 generalization holds; "full track-pass-equivalent selection" covers both classes with one rule.
- **Verdict**: CONFIRMED.

#### Premise: workflow.md:348 single-step-high skip mention stays accurate
- **Track claim**: Interfaces "Out of scope" — `workflow.md:348` mentions the skip in the resume-state table but stays accurate, since the fix keeps the skip and only adds a precondition.
- **Search performed**: Read workflow.md:344-352.
- **Code location**: workflow.md:348 ("`steps-done-review-pending` … single-step tracks skip code review but still run track completion").
- **Actual behavior**: The line states single-step tracks skip code review but still run track completion — a resume-state description, not the skip's licensing premise. The fix keeps the skip and adds a precondition (full selection ran at the step), so this descriptive line stays accurate and needs no edit. Out-of-scope call is correct.
- **Verdict**: CONFIRMED.

#### Edge case: single-step-high `.claude/workflow/*.md` track (this branch) under the fixed rule
- **Trigger**: A track with exactly one step, that step `risk:high`, diff is `.claude/workflow/*.md`-only (the branch's own scenario).
- **Code path trace**:
  1. Phase B sub-step 4 fires (step.risk_tag == 'high') @ step-implementation.md:421.
  2. Sub-step 4(a) "Select review agents" reads §Step-level routing via pointer @ step-implementation.md:437-449.
  3. With the widened rule, §Step-level routing must say: sole step of its track → run full track-pass-equivalent selection. The four workflow reviewers that match a `.claude/workflow/*.md` diff (consistency, context-budget, instruction-completeness, writing-style) fire at the step.
  4. Phase C: code-review-protocol.md §Single-step tracks / track-code-review.md §Single-Step Track skip the track pass — now licensed because the full selection ran at step.
- **Outcome**: The deferred reviewers run at the step; the Phase C skip is sound. Correct — **provided** the lines-145-155 paragraph is reconciled (T1); otherwise step 3 reads two contradictory rules.
- **Track coverage**: Partially. The widening and premise corrections cover the path, but the contradiction at lines 145-155 is not addressed by the Plan of Work (T1).

#### Integration: §Step-level routing → Phase B dispatch (sub-step 4a) and Phase C skip gate
- **Plan claim**: The widened rule in §Step-level routing reaches every dispatch site via the existing pointers; the skip gate's premise corrections reference it.
- **Actual entry point**: step-implementation.md:437-449 (Phase B dispatch pointer); code-review-protocol.md:58-64 and track-code-review.md:105-113 (Phase C skip gate).
- **Caller analysis**: Sub-step 4(a) consumes §Step-level routing through two `(see …)` pointers (grep-confirmed; prose pointers, not symbol references). The two skip gates state the premise the corrections amend. No other live consumer of §Step-level routing's single-step-high timing exists beyond these three sites plus track-code-review.md's track-pass dispatch.
- **Breaking change risk**: Low — the edit is prose; no consumer parses a schema. The only risk is the rule-coherence contradiction at lines 145-155 (T1) and the two-paragraph override ambiguity (T2), both inside the same in-scope section.
- **Verdict**: MATCHES (with the two should-fix completeness gaps noted).
