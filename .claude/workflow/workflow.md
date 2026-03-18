# Execution Workflow

## Overview

This is the session entry point for Phase 3 execution. You are a single agent
that reads the plan, determines where execution left off, and either performs
a strategy refresh or begins/resumes track execution.

There are no agent teams or sub-teams. You execute tracks directly. Sub-agents
are used only for self-contained review tasks (technical/risk/adversarial
reviews, code review, track-level code review) where fresh perspective or
parallel execution is valuable.

### Terminology: Phases 1/2/3 vs Phases A/B/C

The overall workflow has three stages:
- **Phase 1 (Planning)**: `/create-plan` — develop the implementation plan
- **Phase 2 (Structural Review)**: `/review-plan` — validate plan structure
- **Phase 3 (Execution)**: `/execute-tracks` — implement and review tracks

Within Phase 3, each track goes through three sub-phases:
- **Phase A**: Review + Decomposition (`track-review.md`)
- **Phase B**: Step Implementation (`step-implementation.md`)
- **Phase C**: Track-Level Code Review (`track-code-review.md`)

**Each session handles exactly one sub-phase of one track.** After completing
a sub-phase, the session ends and the user re-runs `/execute-tracks` to
start the next sub-phase with fresh context. This prevents context
dilution — review context doesn't clutter implementation, and implementation
context doesn't bias the code review.

Between sessions, the step file's **Progress** section and step episodes
bridge context. The user clears the session and re-runs `/execute-tracks`
at every phase boundary.

---

## Session Lifecycle

```mermaid
flowchart TD
    START["/execute-tracks"]
    READ["Read plan file\n+ step file\nIdentify state"]

    START --> READ

    READ -->|"Track just completed\n(no strategy refresh yet)"| SR["Strategy Refresh\n(CONTINUE / ADJUST / ESCALATE)"]
    READ -->|"Fresh start"| PA["Phase A: Review +\nDecomposition"]
    READ -->|"Phase A done,\nsteps incomplete"| PB["Phase B: Step\nImplementation"]
    READ -->|"All steps done,\ncode review incomplete"| PC["Phase C: Track-Level\nCode Review"]
    READ -->|"All phases done,\nplan not updated"| TC["Track Completion\nProtocol"]

    SR -->|CONTINUE / ADJUST| PA
    SR -->|ESCALATE| REPLAN["Inline Replanning"]

    REPLAN -->|"Revised plan"| END_S["Session ends"]
    REPLAN -->|"Plan fundamentally broken"| EXIT_P1["Advise: restart\nfrom /create-plan"]

    PA --> END_A["Session ends\n(Phase A complete)"]
    PB --> END_B["Session ends\n(Phase B complete)"]
    PC --> END_C["Session ends\n(Phase C complete)"]

    TC --> PRESENT["Present track results\nUser reviews"]
    PRESENT -->|Approved| END_TRACK["Session ends\n(track complete)"]
    PRESENT -->|"Fixes needed"| FIX["Apply fixes"] --> PRESENT
    PRESENT -->|"Fundamental rework"| REPLAN

    END_A -->|"Next session"| START
    END_B -->|"Next session"| START
    END_C -->|"Next session"| START
    END_TRACK -->|"Next session"| START
```

Each session handles **one phase of one track**. Phase boundaries are
mandatory session boundaries — the user clears context and re-runs
`/execute-tracks` after each phase completes. This keeps each session
focused: review context doesn't dilute implementation, and implementation
context doesn't bias code review.

Strategy refresh for a just-completed track happens at the **start of the
next session**, not the end of the current one — this gives fresh
perspective on cross-track impact.

---

## Startup Protocol (Auto-Resume)

1. **Read the plan file** at `docs/adr/<dir-name>/implementation-plan.md`.

2. **Identify all tracks** and their status:
   - `[ ]` — not started
   - `[x]` — completed
   - `[~]` — skipped

3. **Determine session state** using the detection rules in conventions.md
   §Session state detection:

   **State A — Track just completed (needs strategy refresh):**
   The last `[x]` track has a track episode but no `**Strategy refresh:**`
   line. Perform strategy refresh first (see Strategy Refresh below), then
   proceed to Phase A of the next track.

   **State B — Fresh start on next track:**
   All `[x]` tracks have strategy refresh lines (or no tracks are completed
   yet). The next `[ ]` track has no step file. Begin Phase A (review +
   decomposition). **This session handles Phase A only** — end the session
   after writing the step file.

   **State C — Mid-track resume (step file exists):**
   A `[ ]` track has a step file. Read the **Progress** section in the step
   file to determine the exact resume point. **Each resume handles exactly
   one phase:**
   - `Review + decomposition` is `[ ]` → resume Phase A (check **Reviews
     completed** section, re-run only missing reviews, then decompose steps).
     End session after Phase A completes.
   - `Review + decomposition` is `[x]`, steps incomplete → run Phase B
     (step implementation). End session after Phase B completes (all steps
     done, or mid-track checkpoint reached).
   - All steps `[x]`, `Track-level code review` is `[ ]` → run Phase C
     (track-level code review). End session after Phase C completes.
   - All phases `[x]` → write track episode to plan file, mark `[x]`,
     present results to user.

4. **Inform the user** of the auto-resume decision:
   - Which track you're working on and why
   - If resuming mid-track: which steps are done, which is next
   - If strategy refresh is needed: do it and present results before
     proceeding

   The user can override: reorder tracks, skip a track, or choose a different
   resume point. But by default, you proceed without waiting for confirmation.

---

## Strategy Refresh

Triggered at the **start of a new session** when the previous session
completed a track (State A above).

### Process

1. **Read the full plan** with all track episodes accumulated so far.

2. **Assess remaining tracks** against accumulated discoveries:
   - Do any track episodes contradict assumptions in upcoming tracks?
   - Has the Component Map changed in ways affecting remaining tracks?
   - Are any Decision Records weakened by what was learned?
   - Are there new dependencies between tracks not in the original plan?

3. **Produce a strategy refresh report** (presented to the user, not
   persisted):

   ```markdown
   ### Strategy Refresh — After Track <N>

   **Episodes reviewed**: <count>
   **Discoveries with downstream impact**: <list or "none">

   **Assessment**: CONTINUE | ADJUST | ESCALATE

   **Adjustments** (if any):
   - Track M: description needs to account for <discovery>
   - Track P: constraint X no longer applies because <reason>

   **Rationale**: <brief explanation of why this assessment was chosen>
   ```

4. **Present the report to the user.** Wait for the user's decision:

   - **CONTINUE** — no issues found. Proceed to the next track.

   - **ADJUST** — minor fixes needed. Apply adjustments to the plan file
     (update track descriptions, reorder if needed), then proceed to the
     next track. Adjustments must be small and targeted.

     **ADJUST must NOT modify Decision Records.** Decision Records are
     immutable during normal execution — they can only be revised via
     ESCALATE, which triggers inline replanning and structural review
     of the revised plan (see Inline Replanning below). If a discovery
     invalidates a Decision Record, that is an automatic ESCALATE.

   - **ESCALATE** — accumulated discoveries have fundamentally changed the
     picture. Enter inline replanning (see Inline Replanning below).

5. **Write the `**Strategy refresh:**` line** to the plan file under the
   completed track's block (see conventions-execution.md §After strategy refresh for
   format). For CONTINUE, a one-liner suffices. For ADJUST, include a brief
   summary of what was adjusted. ESCALATE does not write a strategy refresh
   line — it triggers replanning which restructures the plan directly.

6. **Proceed** to Phase A of the next track in the same session.

   **Note:** Strategy refresh + Phase A share a single session — this is
   the only exception to mandatory phase boundaries. Strategy refresh is
   lightweight (no code reading, no implementation) and its output directly
   informs Phase A decomposition. After Phase A completes, end the session
   as usual.

---

## Cross-Track Impact Monitoring

After each step implementation, do a lightweight assessment — this is a quick
check, not a full strategy refresh. You have the plan context in your session,
so this is a natural self-check.

For each completed step, assess:

1. **Assumption validity** — Does this discovery contradict assumptions in any
   upcoming track's description?
2. **Architecture impact** — Does this change affect the Component Map or
   Decision Records in ways that touch other tracks?
3. **Dependency ordering** — Does this invalidate the dependency ordering of
   remaining tracks?

### If impact is detected

Alert the user immediately with:

- Which upcoming track(s) are affected
- What assumption is weakened or invalidated
- What the step discovered that triggered this alert
- Recommended action:
  - **Continue** (minor impact — note it, address during that track's review)
  - **Pause and ADJUST** (remaining steps in current track need revision)
  - **ESCALATE** (the discovery fundamentally changes the plan)

### If no impact is detected

Continue to the next step. No user notification needed.

---

## Session Boundary Rules

### When to end a session

Phase boundaries are **mandatory** session boundaries. Each session handles
exactly one phase:

- **After Phase A (review + decomposition)** — step file is written and
  committed with all steps as `[ ]` and `Review + decomposition` marked
  `[x]`. Session ends. Next session starts Phase B.

- **After Phase B (step implementation)** — all steps are implemented,
  tested, committed, and have episodes. `Step implementation` is marked
  `[x]`. Session ends. Next session starts Phase C.

- **After Phase C (track-level code review)** — review is complete,
  `Track-level code review` is marked `[x]`. Session ends. Next session
  writes the track episode and presents results to the user.

- **After track completion** — track episode written, track marked `[x]`,
  user approved. Session ends. Strategy refresh happens in the next session.

- **Mid-Phase B checkpoint** — if you've completed 5+ steps and the track
  has more steps remaining, suggest ending the session. The step file with
  episodes provides full continuity. The next session resumes Phase B from
  the next incomplete step.

- **After ESCALATE resolution** — if inline replanning produces a revised
  plan, end the session. The next session starts fresh with the revised plan.

### Why mandatory phase boundaries

Each phase has a distinct cognitive mode:
- **Phase A** is exploratory — reading code, validating assumptions,
  planning steps. The codebase knowledge is useful but the "reviewer
  mindset" context is not helpful during implementation.
- **Phase B** is productive — writing code, running tests, iterating on
  fixes. The accumulated implementation detail would bias the code reviewer.
- **Phase C** is evaluative — reviewing the full diff with fresh eyes,
  catching systematic issues. Fresh context is essential for objective review.

Mixing phases in one session dilutes focus and carries stale context
forward. Episodes bridge what matters across sessions; everything else is
deliberately shed.

### What persists across phase boundaries

The step file bridges context between sessions:
- **Progress section** — which sub-phases are complete
- **Reviews completed** — which reviews ran and their outcomes
- **Step episodes** — what was discovered and implemented
- **Review files** — full review findings in `reviews/track-N-*.md`

Deliberately NOT carried forward (shed with the session):
- Implementation context (variable names, debugging history, workaround
  decisions) — Phase C needs fresh eyes for objective review
- Reviewer context (exploration notes, plan assumption reasoning) —
  Phase B needs focus on the code, not planning rationale
- Code review context (step-level review findings, fix iterations) —
  Phase C does systematic cross-step review, not localized fixes

### What to do before ending a session

- Ensure all code changes are committed
- Ensure all step episodes are written to the step file and committed
- Update the **Progress** section in the step file to reflect the
  current phase completion state
- Commit the step file update
- Inform the user of the session state so the next `/execute-tracks`
  auto-resumes correctly

---

## User Interaction Model

User interaction is minimal and happens at specific points:

| When | What you present | What the user decides |
|---|---|---|
| **Session start** | Auto-resume decision (which track, which phase) | Confirm or override |
| **Strategy refresh** | Assessment report (CONTINUE / ADJUST / ESCALATE) | Accept or override |
| **Phase complete** | Phase summary, what was done, next phase | User clears session, re-runs `/execute-tracks` |
| **Cross-track impact** | Which tracks affected, what broke, recommendation | Continue, pause, or escalate |
| **Track complete** | Track episode, step episodes, git log of commits | Approve, request fixes, or rework |
| **Step failure (2nd attempt)** | What failed twice, what was tried, options | Retry differently, adjust, or escalate |

### What does NOT involve the user

Everything within a phase session is fully autonomous:

- Phase A: track reviews (as sub-agents), step decomposition
- Phase B: step implementation, testing, coverage, step-level code review
  iterations (up to 3 per step), episode production, within-track adaptation
- Phase C: track-level code review (up to 3 iterations)
- Cross-track impact checks (unless impact is detected)

---

## Failure Handling

### Step failure

If a step fails (tests won't pass, coverage can't be met, wrong API
assumption):

1. Revert uncommitted changes
2. Produce a failed episode (see conventions-execution.md §2.2)
3. Write the failed episode to the step file and commit it
4. Decide: **retry** with a different approach, or **split** the step

### Two-failure rule

If the same step fails twice (original attempt + one retry):

- **Stop and present the situation to the user.** Include both failed
  episodes, what was tried, and why it failed.
- The user decides: retry with specific guidance, adjust the approach,
  skip the step, or escalate.

### Track-level failure

If a failure undermines the track's overall approach (not just one step):

- Present the situation to the user with full context
- Recommend ESCALATE if the approach is fundamentally wrong
- The user decides how to proceed

---

## Inline Replanning (ESCALATE)

When strategy refresh produces ESCALATE, you handle replanning directly —
you have all the context: every track episode, the full plan file, and
architecture notes.

### When ESCALATE triggers

- Strategy refresh assessment is ESCALATE
- An ADJUST would require modifying Decision Records (automatic ESCALATE)
- Cross-track impact monitoring detects a fundamental assumption failure
- A step failure affects the track's approach at a level additional commits
  cannot fix
- User requests escalation during track review ("fundamental rework")

### Process

**1. Stop** — do not start new steps.

**2. Assess** — present the full situation to the user:

- All track episodes so far (completed tracks)
- Partial progress from any incomplete track (step episodes)
- What assumptions broke and why
- Which remaining tracks are affected and how
- What Decision Records are weakened or invalidated

**3. Propose** — draft a revised plan:

- New or modified tracks for remaining work
- Updated architecture notes (Component Map, Decision Records with revision
  notes, Invariants, Integration Points)
- Reordered dependencies based on what was learned
- Removed tracks that are no longer needed
- Clear rationale for each change

Decision Record revisions follow this format:
```markdown
#### D3: <Decision title> (revised after Track N)
- **Original decision**: <what was decided in planning>
- **What changed**: <discovery that invalidated it>
- **Revised decision**: <new approach>
- **Alternatives considered**: <what else was on the table>
- **Rationale**: <why this revision>
- **Risks/Caveats**: <known downsides>
- **Implemented in**: Track M (revised), Track P (new)
```

**4. Review** — spawn a sub-agent to validate the revised plan using the
structural review protocol from Phase 2 (see structural-review.md). The sub-agent
receives the full plan file including both completed track episodes and the
proposed revisions.

**5. Iterate** — if the review finds blockers, revise and re-review. Maximum
3 iterations.

**6. Resume or exit:**

- **Review PASS** — update the plan file with the revised plan. End the
  session. The next session picks up the revised plan and continues.

- **Blockers persist after 3 iterations** — the plan is fundamentally broken
  at a level that incremental revision cannot fix. Advise the user to restart
  from Phase 1 (`/create-plan`) with accumulated episodes as input context.

---

## Track Completion Protocol

After track-level code review passes (or max iterations):

1. **Compile the track episode** from all step episodes in the step file.
   The track episode is a strategic summary — what was built, key
   discoveries, plan deviations with cross-track impact.

2. **Write the track episode** to the plan file:

   ```markdown
   - [x] Track N: <title>
     > <description>
     >
     > **Track episode:**
     > <strategic summary — length proportional to cross-track impact>
     >
     > **Step file:** `tracks/track-N.md` (M steps, K failed)
   ```

3. **Mark the track as `[x]`** in the plan file.

4. **Present track results to the user:**
   - Track episode
   - All step episodes from the step file
   - Git log of track commits
   - Any unresolved track-level code review findings

5. **Wait for user response:**
   - **Approved** — session ends. Strategy refresh happens next session.
   - **Fixes needed** — apply the user's specific fixes as additional
     commits. Re-run track-level code review if fixes are substantial.
     Present updated results.
   - **Fundamental rework** — trigger ESCALATE.

---

## Conventions

This document defines the session lifecycle and cross-track coordination.
For other workflow components, see:

- **`conventions.md`** — shared formats, glossary, plan file structure,
  scope indicators, review iteration protocol
- **`conventions-execution.md`** — execution-specific: episodes, commit
  format, code review, complexity tiers, decomposition rules
- **`track-review.md`** — Phase A: review + decomposition
- **`step-implementation.md`** — Phase B: step implementation
- **`track-code-review.md`** — Phase C: track-level code review
- **`planning.md`** — Phase 1 (planning)
- **`structural-review.md`** — Phase 2 (structural review)
