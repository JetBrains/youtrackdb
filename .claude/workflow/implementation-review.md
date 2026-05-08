# Implementation Review (Phase 2)

> **Loaded on-demand.** This document is read only when the
> `/execute-tracks` startup protocol detects **State 0** (the plan
> file's `## Plan Review` checklist entry is `[ ]`), or when the user
> manually invokes `/review-plan` to re-validate after inline
> replanning. Non-State-0 sessions never load this file, so its
> length carries no per-session cost.

## Overview

Phase 2 validates the plan before track execution begins. It runs **autonomously** —
the orchestrator applies mechanical fixes itself and escalates only **design-level**
decisions to the user (missing Decision Records, track contradictions, target-state
vs. current-state ambiguity, ASPIRATIONAL/VIOLATED invariants). Two steps run in
sequence:

1. **Consistency Review** — reads the design document, implementation plan,
   backlog, and actual codebase to find gaps and inconsistencies between
   them. Each finding carries a `Classification: mechanical | design-decision`
   tag emitted by the sub-agent. Mechanical fixes apply automatically; design
   decisions are batched and escalated to the user once at the end of the step.
2. **Structural Review** — validates plan-internal structure (dependency
   ordering, track sizing, scope indicators, architecture notes, bloat).
   All bloat findings are classified `mechanical` by construction; ordering
   and contradiction findings classify as `design-decision`. Same
   autonomous flow.

After both steps pass, the orchestrator marks `## Plan Review` as `[x]` in the
plan file (with a brief audit summary), commits the workflow update, and ends
the session. The next `/execute-tracks` invocation enters State A (pre-Phase-A
— the Track Pre-Flight gate runs against Track 1, with Panel 1 skipped because
no track has completed yet).

**Division of labor with the design mutation discipline.** Phase 2
does **not** separately review the narrative quality of `design.md`
(per-section shape, top-level caps, consolidation form, D/S code
discipline, length-trigger compliance, etc.). Those checks are
gated at **write time** by the design-mutation action defined in
[`design-document-rules.md`](design-document-rules.md) § Mutation
discipline — every modification to `design.md` runs the
mechanical checks + cold-read sub-agent before the change stands.
Phase 2's Consistency Review still verifies design ↔ code ↔ plan
alignment (i.e., are the diagrams accurate and the cross-links
resolvable), and the Structural Review still owns plan-internal
quality. Narrative readability of the design document is the
mutation action's responsibility.

```mermaid
flowchart TD
    START["/execute-tracks State 0\n(or /review-plan manual)"]
    CR["Step 1: Consistency Review\n(reads code + design + plan)"]
    CR_CLASSIFY["Classify findings:\nmechanical | design-decision"]
    CR_AUTO["Auto-apply mechanical fixes\n(Edit / edit-design)"]
    CR_ESC{"Any design-decision\nfindings?"}
    CR_USER["Batch-escalate to user\n(present alternatives,\napply user resolutions)"]
    CR_GATE["Gate verification\n(verify fixes,\ncatch regressions)"]
    SR["Step 2: Structural Review\n(plan-internal quality)"]
    SR_CLASSIFY["Classify findings:\nmechanical | design-decision"]
    SR_AUTO["Auto-apply mechanical fixes"]
    SR_ESC{"Any design-decision\nfindings?"}
    SR_USER["Batch-escalate to user"]
    SR_GATE["Gate verification"]
    AUDIT["Write audit summary\nto ## Plan Review"]
    DONE["Phase 2 PASS\nCommit + push\nEnd session"]

    START --> CR
    CR --> CR_CLASSIFY
    CR_CLASSIFY --> CR_AUTO
    CR_AUTO --> CR_ESC
    CR_ESC -->|No| CR_GATE
    CR_ESC -->|Yes| CR_USER --> CR_GATE
    CR_GATE -->|"PASS"| SR
    CR_GATE -->|"FAIL (max 3 iter)"| CR_CLASSIFY
    SR --> SR_CLASSIFY
    SR_CLASSIFY --> SR_AUTO
    SR_AUTO --> SR_ESC
    SR_ESC -->|No| SR_GATE
    SR_ESC -->|Yes| SR_USER --> SR_GATE
    SR_GATE -->|"PASS"| AUDIT
    SR_GATE -->|"FAIL (max 3 iter)"| SR_CLASSIFY
    AUDIT --> DONE
```

## How to run

Phase 2 has two entry points:

1. **Autonomous (default).** Triggered by `/execute-tracks` when the startup
   protocol detects State 0 (plan file's `## Plan Review` entry is `[ ]` or
   missing). The orchestrator loads this document on-demand and runs the flow
   below.
2. **Manual re-run.** The user runs `/review-plan` to re-validate the plan
   after inline replanning produced a revised plan, or to audit the plan
   independently. The skill at `.claude/skills/review-plan/SKILL.md` loads
   this document and runs the same flow regardless of the current `## Plan
   Review` checkbox state.

Both entry points share the same orchestration. The only difference is what
the orchestrator does after the flow completes:
- Autonomous entry: marks `## Plan Review` as `[x]`, commits, ends the
  session so the next `/execute-tracks` enters Phase A of Track 1.
- Manual re-entry: marks `## Plan Review` as `[x]` (re-stamping with the
  fresh iteration count), commits, ends the session.

### Precondition (both entry points)

Before starting the flow, check that the plan-review files have no
uncommitted changes:

```bash
git status --porcelain \
  docs/adr/<dir-name>/_workflow/implementation-plan.md \
  docs/adr/<dir-name>/_workflow/implementation-backlog.md \
  docs/adr/<dir-name>/_workflow/design.md \
  docs/adr/<dir-name>/_workflow/design-mechanics.md \
  docs/adr/<dir-name>/_workflow/design-mutations.md
```

The last two paths are checked when present — `design-mechanics.md`
exists only when the length trigger has fired, and `design-mutations.md`
is created on the first `edit-design` run. Non-existent paths produce
no output, so listing them is safe.

If the output is non-empty, halt and ask the user to commit (or stash)
those edits first — uncommitted changes to these files would otherwise
be bundled into the audit-trail commit alongside the auto-fixes,
muddling the trace. Other dirty paths in the working tree are safe to
ignore (the audit-trail `git add` is path-scoped to the files above).

---

## Step 1: Consistency Review

Checks that the design document, implementation plan, and actual codebase
are aligned. Unlike structural review, this step **reads the codebase** to
verify code references, call flows, and class relationships.

Because the consistency review's findings are factual claims about the
code (a method exists / does not exist, a flow has these participants,
this class has these callers), they are reference-accuracy questions
under the rule in [`conventions.md`](conventions.md) §1.4 *Tooling
discipline*. When mcp-steroid is reachable per the SessionStart hook,
the verification routes through the IntelliJ PSI rather than grep —
preflight via `steroid_list_projects`, and instruct the consistency
sub-agent to use PSI find-usages for symbol questions (its prompt
already contains this instruction). When mcp-steroid is unreachable,
fall back to grep with explicit reference-accuracy caveats in the
findings.

### What it checks

- **Design ↔ Code**: class diagrams match real classes, workflow diagrams
  match real call flows, complex-part sections describe actual behavior
- **Plan ↔ Code**: Component Map and Decision Records reference real
  constructs, Integration Points exist, track descriptions don't reference
  phantom code
- **Design ↔ Plan**: diagrams align with track descriptions and Decision
  Records, scope indicators are consistent with design complexity
- **Gaps**: plan elements without design coverage, design elements no track
  covers, codebase constructs the documents should reference but don't

Each pending track's detailed description
(`**What/How/Constraints/Interactions**` subsections and any track-level
Mermaid diagram) lives in `implementation-backlog.md` rather than
inline in the plan file; the consistency review reads the backlog
alongside the plan.

### Sub-agent prompt

**Prompt file:** [`prompts/consistency-review.md`](prompts/consistency-review.md)

The prompt embeds the **intent-axis pre-screen** (current-state vs.
target-state) and the **classification rules** (see §Mechanical vs.
design-decision classifier below). Each finding the sub-agent emits
carries a `Classification` field — the orchestrator routes on that
field, not on severity.

### Gate verification

**Prompt file:** [`prompts/consistency-gate-verification.md`](prompts/consistency-gate-verification.md)

### Autonomous orchestration loop

```
Iteration 1 (full review):
  1. Spawn the consistency sub-agent with plan, backlog, design, codebase.
  2. Receive findings, each tagged Classification: mechanical | design-decision.
  3. Apply ALL mechanical fixes immediately:
     - Plan/backlog edits: native Edit.
     - design.md edits: route through the edit-design skill (mutation
       discipline — see §Mutation discipline for design.md fixes below).
  4. If any design-decision findings remain: batch-present to the user
     once (single message listing all open design questions with proposed
     alternatives). Wait for user resolutions. Apply user-approved fixes
     using the same plan-vs-design routing.
  5. Spawn the gate verification sub-agent with the updated artifacts and
     the iteration's findings.

Iteration 2-3 (gate, then full re-review if structure changed):
  - If the gate finds new findings, classify and re-route as in iteration 1.
  - If iteration 1 fixes significantly restructured the plan or design
    (tracks reordered, classes/flows redesigned, scope indicators changed
    substantially), re-run the FULL consistency review instead of the gate
    to catch cascading inconsistencies.

Cap: 3 iterations. Findings IDs cumulative (CR1, CR2, ... CR6, CR7).
```

If blockers persist after 3 iterations, escalate to the user with the
remaining open findings and recommend returning to Phase 1 (Planning) to
rework the plan/design before re-entering.

### Review output

Findings ride in the orchestrator's conversation context for the
iteration loop; plan and design fixes are applied via Edit / edit-design
to `implementation-plan.md`, `implementation-backlog.md`, and the
design document. The review itself is not persisted to a separate file —
once the gate passes, the conversation is the only in-flight record,
and the durable trace is:

- The resulting plan/design state.
- The gate-PASS commit.
- The audit summary written into the plan file's `## Plan Review`
  section (see §Audit trail below).

---

## Step 2: Structural Review

Runs **automatically** after the consistency review passes. Validates
plan-internal structure without reading the codebase.

### What it checks

Dependency ordering, track sizing, scope indicators, architecture notes
completeness, design document structure, decision traceability, internal
consistency, and plan-file bloat.

Each pending track's detailed description (the subject of TRACK
DESCRIPTIONS checks, plus several cross-file bullets in TRACK SIZING,
SCOPE INDICATORS, and CONSISTENCY) lives in `implementation-backlog.md`
rather than inline in the plan file; the structural review reads the
backlog alongside the plan.

**Full details:** [`structural-review.md`](structural-review.md)

### Sub-agent prompt

**Prompt file:** [`prompts/structural-review.md`](prompts/structural-review.md)

### Gate verification

**Prompt file:** [`prompts/structural-gate-verification.md`](prompts/structural-gate-verification.md)

### Autonomous orchestration loop

Same shape as the consistency review:

```
Iteration 1 (full review):
  1. Spawn the structural sub-agent with plan, backlog, design.
  2. Receive findings, each tagged Classification: mechanical | design-decision.
     Per-prompt rule: ALL bloat findings classify as mechanical; track-ordering
     and contradiction findings classify as design-decision (see §Mechanical
     vs. design-decision classifier below).
  3. Apply mechanical fixes immediately (Edit for plan/backlog, edit-design
     for design.md).
  4. Batch-escalate any design-decision findings to the user once. Apply
     user-approved fixes.
  5. Spawn the gate verification sub-agent with the updated artifacts and
     the iteration's findings.

Iteration 2-3: gate or full re-review (re-run full review if fixes
significantly restructured the plan).

Cap: 3 iterations. Findings IDs cumulative (S1, S2, ... S6, S7).
```

If structural fixes significantly restructure the plan (tracks reordered,
tracks added/removed, scope indicators changed substantially), re-run
the full structural review instead of the gate check to catch cascading
issues.

### Review output

Same as the consistency review — findings ride in the conversation
during the loop; the durable trace is the plan-file edits, the gate-PASS
commit, and the audit-summary entry in `## Plan Review`.

---

## Mechanical vs. design-decision classifier

The classifier lives in the review prompts so the sub-agent emits the
classification alongside each finding. The orchestrator trusts the
sub-agent's tag and routes on it.

### `mechanical` — orchestrator applies the fix without asking

ALL of these must hold for a consistency finding to classify as
`mechanical`:

1. The plan/design claim is about **current state** — code that should
   already exist at the time of writing, not code a track will create.
   (Aspirational claims about target state are pre-filtered; see
   §Intent-axis pre-screen below.)
2. There is exactly one unambiguous correct rendering — rename to the
   actual class, update to the real signature, fix the participant
   name in a sequenceDiagram, drop a phantom reference, etc.
3. Applying the fix doesn't change what the plan is trying to achieve.
   Only the description is updated; the plan's goals, scope, and
   architecture are unchanged.

For structural findings, **all bloat findings** are mechanical by
construction (DR length, intro length, component-intent length,
integration-point length, plan/design duplication, superseded DR
retained, plan-file budget, missing track-reference annotation,
scope-indicator format). The fix follows the rule mechanically —
trim back to the four-bullet form, move long-form material to
`design.md`, replace duplicated body with a one-line link, delete the
superseded DR, etc.

### `design-decision` — orchestrator escalates to the user

ANY of these triggers `design-decision`:

- The discrepancy reveals a **missing Decision Record** for a non-obvious
  choice. The user has the rationale; the orchestrator does not.
- The discrepancy is a **contradiction between two tracks** (Track 1
  assumes X, Track 3 assumes not-X). Which one is right is a design
  call.
- An **ASPIRATIONAL invariant has no implementing track**. Do we add a
  track or change the invariant? Both are plausible.
- A **VIOLATED invariant** exists. Do we fix the code (track scope
  expansion) or restate the invariant (design retreat)?
- **Design ↔ code mismatch where the plan describes a target state**
  (a `[ ]` track is meant to create what the design claims) and the
  divergence is whether to keep the target shape or change it. The
  pre-screening rule (§Intent-axis pre-screen) catches the
  no-divergence case; what reaches this rule is genuine design choice.
- **Track ordering** issues where reordering changes the contract
  (e.g., Track B's scope mentions wiring X, but X is introduced in
  Track C — does B move after C, or does X move into B?).

The classifier rules belong in the prompt files — the orchestrator
does not re-classify; it acts on the tag.

### Intent-axis pre-screen (consistency review only)

Before emitting any finding, the consistency sub-agent classifies each
plan/design claim along the **intent axis**:

- **Current-state claim** — the plan/design says something about code
  that should already exist (Component Map describing a current
  module's role, design.md class diagram describing pre-existing
  classes, Architecture Notes referencing today's SPI). Discrepancies
  here become findings.
- **Target-state claim** — the plan/design says something about code
  a `[ ]` track will create (`**What**:` bullets in the backlog,
  forward-looking Decision Records, design.md sections describing
  the post-implementation state). The current code naturally won't
  match — this is **not a finding** unless the target is unreachable
  from the current code (in which case the finding is
  `design-decision`).

The same rule already applies to invariants via the
`ENFORCED / ASPIRATIONAL / VIOLATED` tagging. The pre-screen extends
the same idea to all claims: a divergence with target-state code is
expected and silenced; a divergence with current-state code is a
finding.

---

## Audit trail

Two durable traces survive the autonomous flow:

### 1. The `## Plan Review` section in the plan file

Before Phase 2 runs (or after `/create-plan` produced the initial
plan), the section reads:

```markdown
## Plan Review
- [ ] Plan review (consistency + structural) — autonomous; runs as the first phase of `/execute-tracks`
```

After Phase 2 passes, the orchestrator overwrites the section with:

```markdown
## Plan Review
- [x] Plan review (consistency + structural) — passed at iteration <N>

**Auto-fixed (mechanical)**: <CR/S finding IDs with one-line summaries>.

**Escalated (design decisions)**: <CR/S finding IDs with one-line user resolutions, or "none">.
```

If the user re-runs `/review-plan` later, the orchestrator overwrites
this section again with the new iteration's audit. The `[x]` checkbox
is what the startup protocol's State 0 detection reads.

If the section is missing entirely (a pre-existing plan from before
this convention), the orchestrator treats it as `[ ]` and runs the
autonomous flow, then writes the section for the first time at the
end. Pre-existing plans don't break.

### 2. The workflow-update commit

After Phase 2 passes, the orchestrator stages the plan, backlog, and
design files, and commits with the message:

```
Plan review autonomous fixes for <plan-name>

Auto-fixed: <CR/S IDs>
Escalated: <CR/S IDs, or "none">
```

This is a single Workflow update commit per the table in
`commit-conventions.md` § Commit type prefixes. Push after commit.

---

## Mutation discipline for design.md fixes

Every fix that touches `design.md` (or `design-mechanics.md`) routes
through the `edit-design` skill — see [`design-document-rules.md`](design-document-rules.md)
§ Mutation discipline. The skill applies the mechanical checks +
cold-read sub-agent gate before the change stands.

The autonomous flow handles this by invoking `edit-design` for each
design.md mechanical fix; the cold-read sub-agent is the safety net
for narrative breakage. If the cold-read rejects the mutation, the
orchestrator escalates that specific fix to the user (effectively
re-classifying it from `mechanical` to `design-decision`).

Plan-file and backlog edits use `Edit` directly — no mutation
discipline applies to those files.

---

## Replanning

**Not a separate phase.** Replanning is handled within Phase 3
by the execution agent's ESCALATE flow (see "Inline Replanning
(ESCALATE)" in `workflow.md`).

**Why:** The execution agent reads all track episodes from the plan file
and can read/write it directly. It has the context to revise the plan
within the session. A separate phase would add unnecessary context loss.

**What happens on ESCALATE:**
1. Execution agent stops starting new steps.
2. Presents full situation to user (all episodes, what broke, what
   assumptions failed).
3. Proposes revised plan (new/modified tracks, reordering, removed
   tracks).
4. Spawns a structural review sub-agent to validate the revised plan
   (uses the same prompt as Phase 2 Step 2; classifier still applies).
5. On review PASS — updates the plan file with the revised plan and
   ends the session. Sets `## Plan Review` back to `[ ]` so the next
   `/execute-tracks` re-runs the full Phase 2 flow against the revised
   plan, picking up any new consistency issues introduced by the
   replan.
6. On review FAIL with persistent blockers — advises user to restart
   from Phase 1 (`/create-plan`) with accumulated episodes as input.

The only case that exits to Phase 1 is when the plan is so fundamentally
broken that incremental revision cannot fix it.

---

## Completion

When both reviews pass:

1. Update `## Plan Review` with the audit summary (see §Audit trail).
2. Stage and commit the plan/backlog/design changes. The
   `design*.md` glob picks up `design.md` plus
   `design-mechanics.md` and `design-mutations.md` when
   `edit-design` touched them during the review (`design.md`
   always exists post-Phase-1, so the glob always matches at least
   one path):
   ```bash
   git add docs/adr/<dir-name>/_workflow/implementation-plan.md \
           docs/adr/<dir-name>/_workflow/implementation-backlog.md \
           docs/adr/<dir-name>/_workflow/design*.md
   git commit -m "Plan review autonomous fixes for <plan-name>

   Auto-fixed: <IDs>
   Escalated: <IDs, or 'none'>"
   git push
   ```
3. Inform the user that Phase 2 passed. The next `/execute-tracks`
   session will resume per the startup protocol — typically State A
   (pre-Phase-A — Track Pre-Flight runs against the first incomplete
   track) for a fresh plan, or whichever state was active before the
   manual re-validation. Remind them that technical/risk/adversarial
   reviews will happen per-track during execution.
4. **Run self-improvement reflection.** Load
   `.claude/workflow/self-improvement-reflection.md` on-demand and
   follow it. Reflection runs even when Phase 2 passed cleanly —
   ambiguity in `implementation-review.md` itself, classifier
   misfires, or sub-agent prompt issues are exactly the kind of
   workflow-process friction this step is meant to capture. If the
   user approves any proposed issues, write the chosen
   `workflow-issues/*.md` files, commit + push per the protocol
   §Commit format, then proceed to Step 5.
5. **End the session.** Do not proceed to Phase A of Track 1 in the
   same session — the session boundary is mandatory (see
   `workflow.md` §Session Boundary Rules).
