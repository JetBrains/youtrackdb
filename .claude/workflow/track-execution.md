# Track Execution

## Overview

This document covers how to execute a single track within a session. A track
goes through three phases in order, all within the same session context:

1. **Phase A: Review + Decomposition** — explore codebase (read-only), run
   reviews as sub-agents, decompose into steps, write step file
2. **Phase B: Step Implementation** — implement each step sequentially:
   code, tests, commit, code review loop, episode
3. **Phase C: Track-Level Code Review** — sub-agent reviews the full track
   diff for systematic issues

After Phase C, control returns to the session workflow (see workflow.md
§Track Completion Protocol) for user review and session end.

---

## Phase A: Review + Decomposition

> **In this phase, you are a reviewer and planner, not an implementer. You
> NEVER edit source code, test files, or build files. You explore the
> codebase (read-only) to validate the track's approach and decompose it
> into steps. The only files you write are: the step file
> (`tracks/track-N.md`) and review files (`reviews/track-N-*.md`).**

### What You Do

1. **Assess track complexity** to determine which reviews to run
2. **Run track-scoped reviews** as sub-agents (technical, risk, adversarial
   as warranted). After each review completes, update the **Reviews
   completed** section in the step file (create the step file early with
   just the Progress and Reviews sections if it doesn't exist yet).
3. **Write review files** to `docs/adr/<dir-name>/reviews/track-N-<type>.md`
4. **Decompose scope indicators** into concrete steps
5. **Write the step file** to `docs/adr/<dir-name>/tracks/track-N.md` with
   all steps as `[ ]` items. Mark `Review + decomposition` as `[x]` in the
   Progress section. Commit the step file.

### Complexity Assessment

| Track complexity | Review pipeline |
|---|---|
| **Simple** (1-2 steps) | Technical review only. |
| **Moderate** (3-5 steps) | Technical review as baseline. Risk and/or Adversarial when warranted. |
| **Complex** (6-7 steps, or critical path / high-risk) | Full: Technical + Risk + Adversarial. |

### Which reviews to run

| Track characteristics | Reviews to run |
|---|---|
| Any track | Technical |
| Track touches critical paths or has performance constraints | + Risk |
| Track has major architectural decisions or non-obvious scope | + Adversarial |

### Track-scoped technical review

Spawn a sub-agent with this prompt:

```
You are reviewing ONE TRACK of an implementation plan for technical soundness.
You MUST read the codebase to validate this track's assumptions.

Inputs:
- Plan file: {plan_path} (read the full plan for context, but focus on
  the specified track)
- Track to review: {track_name}
- Codebase root: {codebase_path}
- Episodes from completed tracks: {prior_episodes}
- Previous findings from other reviews: {previous_findings}

Start by reading the track description, its component diagram (if any), and
the relevant Decision Records. Then explore the parts of the codebase this
track touches.

Use episodes from completed tracks to inform your review — they may
reveal codebase realities that the original plan didn't anticipate.

Review against these criteria:

COMPONENT MAP ACCURACY (for this track)
- Do the components referenced actually exist (or are clearly marked new)?
- Are the relationships (calls, depends-on, extends) accurate?
- Are there components this track misses that will be affected?

DESIGN FEASIBILITY
- Does the described approach work given the current code structure?
- Are there APIs, interfaces, or contracts the track assumes but that don't
  exist or work differently?
- Are there simpler approaches the planning phase missed?
- Does anything learned from prior tracks invalidate this track's approach?

EDGE CASES & ERROR PATHS
- What happens on failure (exceptions, timeouts, partial state)?
- Does the track handle concurrent access where relevant?
- What happens during recovery (crash, restart) for durable state changes?

INTEGRATION POINTS
- Do documented integration points match actual code?
- Will changes break existing callers or consumers?

INVARIANT VALIDITY
- Are stated invariants enforceable given the codebase?
- Do prior track changes affect invariants assumed here?

BACKWARD COMPATIBILITY
- Will existing data/formats still work?
- Are migrations needed that the plan doesn't mention?

For each issue found, produce a finding:

### Finding T<N> [blocker|should-fix|suggestion]
**Location**: <where in the track + relevant source file(s)>
**Issue**: <what's wrong, with evidence from the codebase>
**Proposed fix**: <concrete change — may include modifying steps,
  updating the track description, adding decision records, etc.>

Severity guide:
- blocker: Track will fail during execution (wrong API, missing component)
- should-fix: Track will produce fragile or incomplete results
- suggestion: Improvement based on codebase knowledge
- skip: Track is no longer needed (functionality already exists, prior track
  made it redundant, etc.). Recommend SKIP with rationale.
```

### Track-scoped risk review

Spawn a sub-agent with this prompt:

```
You are reviewing ONE TRACK of an implementation plan for risks and
feasibility. You MUST read the codebase to assess risk realistically.

Inputs:
- Plan file: {plan_path} (full plan for context, focus on specified track)
- Track to review: {track_name}
- Codebase root: {codebase_path}
- Episodes from completed tracks: {prior_episodes}
- Previous findings: {previous_findings}

Review against these criteria:

CRITICAL PATH EXPOSURE
- Which steps in this track touch critical system paths (storage, WAL,
  transactions, indexes, cache)?
- What is the blast radius if those steps have bugs?

UNKNOWNS & ASSUMPTIONS
- Where is the track asserting things without evidence?
- Are there "it should work" assumptions that need validation?
- Did prior tracks reveal anything that changes risk assessment here?

PERFORMANCE IMPLICATIONS
- Do any changes add work to hot paths?
- Are there new allocations, locks, or I/O in performance-sensitive code?

TESTABILITY & COVERAGE
- Can each step realistically achieve 85% line / 70% branch coverage?
- Are there steps hard to test in isolation?

ROLLBACK & RECOVERY
- If a step's approach fails, what's the rollback story?
- Are there irreversible state changes?

For each issue found, produce a finding:

### Finding R<N> [blocker|should-fix|suggestion]
**Location**: <where in the track + relevant source/test file(s)>
**Issue**: <the risk, with likelihood and impact assessment>
**Proposed fix**: <mitigation — reorder steps, add verification steps,
  note the risk explicitly, etc.>

Severity guide:
- blocker: High likelihood of failure with no obvious recovery
- should-fix: Meaningful risk that should be mitigated
- suggestion: Low-probability risk worth noting
```

### Track-scoped adversarial review

Spawn a sub-agent with this prompt:

```
You are the devil's advocate reviewing ONE TRACK of an implementation plan.
Challenge assumptions, argue against decisions, find weak spots.
You MUST read the codebase to ground your challenges in reality.

Inputs:
- Plan file: {plan_path} (full plan for context, focus on specified track)
- Track to review: {track_name}
- Codebase root: {codebase_path}
- Episodes from completed tracks: {prior_episodes}
- Previous findings: {previous_findings}

DECISION CHALLENGES
- For each Decision Record relevant to this track: argue for the best
  rejected alternative using codebase evidence.
- Are there alternatives not even listed?

SCOPE CHALLENGES
- Is this track trying to do too much? Could it be split?
- Are there cheap additions that would increase value?

INVARIANT CHALLENGES
- For each Invariant in this track: construct a concrete violation scenario.

ASSUMPTION CHALLENGES
- What does this track take for granted that might not hold?
- Did prior tracks reveal anything that weakens assumptions here?

SIMPLIFICATION CHALLENGES
- Could the same goals be achieved with fewer steps?
- Is there an existing mechanism that replaces a proposed new component?

For each challenge, produce a finding:

### Finding A<N> [blocker|should-fix|suggestion]
**Target**: <Decision D<N> | Non-Goal | Invariant | Assumption>
**Challenge**: <the strongest counter-argument>
**Evidence**: <codebase or domain evidence>
**Proposed fix**: <strengthen rationale, change decision, add step, etc.>

Severity guide:
- blocker: Will likely cause execution failure or major rework
- should-fix: Decision survives but rationale needs strengthening
- suggestion: Interesting challenge but existing decision holds
```

### Review gate verification

After fixes are applied, spawn a sub-agent to verify:

```
You are re-checking a track of the plan after fixes were applied.

Inputs:
- Updated plan file: {plan_path}
- Track reviewed: {track_name}
- Previous findings: {findings}
- Review type: {technical|risk|adversarial}

For each previous finding:
1. If the finding was ACCEPTED: check if the fix was applied correctly
   and if the fix introduced new issues.
2. If the finding was REJECTED: verify the rejection reason is sound
   and no downstream issue was introduced. Mark as REJECTED.

Output:
- For each finding: VERIFIED, STILL OPEN (with explanation), or
  REJECTED (no action needed)
- New findings (if any) with cumulative numbering
- Summary: PASS or FAIL
```

### Review iteration protocol

Max 3 iterations per review type, findings cumulative. If blockers persist
after 3 iterations, note them and proceed with caution — the step
implementation phase will surface concrete issues if they exist.

### Step Decomposition

After track review passes, decompose scope indicators into concrete steps.
Decompose **all steps at once** — tracks are capped at ~5-7 steps, making
full upfront decomposition feasible.

#### Inputs for decomposition

- Track description, scope indicators, component diagram, and relevant
  Decision Records
- Track episodes from all completed tracks
- Codebase knowledge gained from track review

#### Decomposition rules

- Each step = one commit
- Each step = fully tested, self-contained change with 85% line / 70%
  branch coverage
- If a step touches more than ~3 files or does unrelated things, split it
- Cross-cutting concerns are separate steps

#### Output

Write decomposed steps to the **step file**
(`docs/adr/<dir-name>/tracks/track-N.md`), creating it if it doesn't exist.
Scope indicators in the plan file are NOT replaced — step details live only
in the step file.

The scope indicators serve as a starting point, not a binding contract. You
may produce more or fewer steps than the indicator suggested, or cover
different aspects, based on what is actually needed.

---

## Phase B: Step Implementation

Implement steps sequentially, one at a time, within the same session context.
You retain full context from Phase A (review findings, codebase knowledge,
decomposition rationale).

### Step Completion Gate

**A step is not complete until all six sub-steps below are done.** Do NOT
begin implementing the next step until the current step's episode is
committed and cross-track impact is assessed. This is a hard gate, not a
guideline.

**Why this matters:** Skipping the gate — even for steps that "feel
mechanical" — defeats the workflow's purpose. Code review catches issues
early (before they propagate to later steps). Episodes record discoveries
that inform remaining steps. Cross-track checks catch assumption failures
before they compound. Batching these activities across steps loses all
three benefits.

**Prohibited patterns:**
- Starting Step N+1 code before Step N's episode is committed
- Batching code reviews across multiple steps
- Batching episodes across multiple steps ("retroactive" episodes)
- Running tests in the background and continuing to the next step
  without waiting for results

### Per-Step Workflow

For each step in the step file, execute sub-steps 1–6 **in order, to
completion**, before moving to the next step:

1. **Implement the code** with defensive assertions generously (without
   performance penalty).
2. **Write tests**, ensure all tests pass, ensure 85% line / 70% branch
   coverage using JaCoCo (triggered by `coverage` Maven profile). Run clean
   phase for each Maven command. **Wait for test results before proceeding.**
3. **Stage and commit** the code changes. You know exactly which files you
   modified, so stage them explicitly (no `git add -A`). Follow the project's
   commit message conventions (see `CLAUDE.md`).
4. **Code review loop** (up to 3 iterations, within your context):
   a. Spawn a **code-reviewer sub-agent** (fresh sub-agent each iteration).
   b. If findings are returned, fix them, commit fixes, and re-submit.
   c. Repeat until approved OR **max 3 iterations** reached.
   d. If max iterations reached, note remaining findings in the episode.
5. **Produce the step episode** — a structured record of what happened
   (see conventions.md §1.3 for format). Write it to the step file under the
   step item. Mark the step as `[x]`. Update the **Progress** section's
   `Step implementation` count (e.g., `(3/5 complete)`). If this is the last
   step, mark `Step implementation` as `[x]`. Commit the episode as a
   **separate episode commit**.
6. **Cross-track impact check** — quick self-assessment against the plan
   (see workflow.md §Cross-Track Impact Monitoring). Alert the user only if
   impact is detected.

**→ GATE: Step is now complete. Proceed to the next step.**

### Episode Production

After committing code (including any review fix commits), produce the
structured episode. You have full context of what you implemented, what you
discovered, and what deviated from the plan.

The episode includes:
- **What was done** — factual summary reconstructed from your changes
- **What was discovered** — unexpected findings (fill this whenever anything
  unexpected is found, even if it didn't block the step)
- **What changed from the plan** — deviations, naming affected future steps
- **Key files** — files created/modified with (new)/(modified) annotations
- **Critical context** — anything essential that doesn't fit above (use
  sparingly)

Write the episode to the step file and commit it as a separate episode commit.

### Step Failure

If you encounter a fundamental issue you cannot resolve within the step's
scope (wrong API assumption, tests cannot pass, code reviewer finds
architectural problems, coverage cannot be met):

1. Revert any uncommitted changes (`git checkout -- .`)
2. Produce a **failed episode** (see conventions.md §1.3)
3. Write the failed episode to the step file and commit it
4. Decide: **retry** with a different approach, or **split** the step into
   smaller pieces that can succeed independently

### Two-Failure Rule

If the same step fails twice (original attempt + one retry):

- **Stop.** Do not attempt a third time.
- Present both failed episodes to the user with:
  - What was tried each time
  - Why it failed
  - Your assessment of whether this is a step-level issue or a track-level
    issue
- The user decides: retry with specific guidance, adjust the approach, skip
  the step, or escalate.

### Mid-Track Checkpoint

If you've completed 5+ steps and more remain, assess whether the session
context is becoming heavy. If so, suggest ending the session:

- Ensure all episodes are written and committed
- Inform the user: "Completed N of M steps. Suggest clearing session and
  re-running `/execute-tracks` to resume with fresh context."
- The next session auto-resumes from the next incomplete step (State C in
  workflow.md §Startup Protocol)

This is a suggestion, not a hard rule. If only 1-2 steps remain, finishing
in the same session is usually better.

---

## Phase C: Track-Level Code Review

After all steps are committed, spawn a **sub-agent** to review the full track
diff. This is deliberately a sub-agent — fresh eyes catch systematic issues
that you (as the implementer) are blind to.

### Sub-agent prompt

```
You are reviewing the full diff of a completed track for systematic issues
that per-step review cannot catch.

Inputs:
- Base commit: {base_commit} (commit before the track's first step)
- Track description: {track_description}
- Step file: {step_file_path} (for context — step episodes explain why
  certain choices were made)

Review the full track diff (`git diff {base_commit}..HEAD`) for:

SYSTEMATIC PATTERNS
- The same mistake repeated across steps
- Inconsistent patterns for the same thing across steps

ACCUMULATED TECHNICAL DEBT
- Individually acceptable compromises that are collectively problematic

INTEGRATION ISSUES
- Steps that compile independently but combine with subtle interactions
- State management across step boundaries

CROSS-STEP CONSISTENCY
- Naming consistency across files modified in different steps
- Error handling patterns that should be uniform

For each issue found, produce a finding:

### Finding C<N> [blocker|should-fix|suggestion]
**Location**: <file(s) and line(s)>
**Issue**: <what's wrong — include cross-step context if applicable>
**Proposed fix**: <concrete change>

Severity guide:
- blocker: Will cause runtime failures or data corruption
- should-fix: Inconsistency or debt that should be addressed now
- suggestion: Improvement that could be deferred
```

### Review loop

1. If the sub-agent returns findings that need fixes:
   - Apply fixes as **additional commits** (never amend prior commits)
   - Run tests to verify fixes don't break anything
   - Spawn a fresh sub-agent to verify (gate check)
2. Max 3 iterations
3. If blockers persist after 3 iterations, note them — they'll be presented
   to the user during track review (workflow.md §Track Completion Protocol)
4. When the review passes (or max iterations reached), mark
   `Track-level code review` as `[x]` in the step file's Progress section.
   Commit this update.

---

## Context Advantage

Because all three phases run in the same session, you benefit from:

- **Review findings inform implementation** — you don't need compressed
  summaries of what the review found; you were there
- **Implementation context informs episodes** — you know exactly why you
  made each choice, what surprised you, and what deviated
- **Cumulative codebase knowledge** — each step builds on what you learned
  in prior steps and the review phase, without re-reading files
- **Natural cross-step consistency** — you remember the patterns you
  established in step 1 when implementing step 5

This is the primary reason for the single-agent model. The episode system
handles cross-session continuity; within a session, full context is preserved
naturally.

---

## Conventions

This document defines track execution within a session. For other workflow
components, see:

- **`conventions.md`** — shared formats, glossary, plan file structure,
  episode formats, commit conventions, complexity tiers, review protocols
- **`workflow.md`** — session lifecycle, startup protocol, strategy refresh,
  cross-track impact monitoring, failure handling, inline replanning
- **`planning.md`** — Phase 1 (planning) and Phase 2 (structural review)
