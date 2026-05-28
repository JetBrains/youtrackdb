# Self-Improvement Reflection

A mandatory final step at the end of every session run by a calling
skill that opts into reflection (`/execute-tracks` today;
`/migrate-workflow` from this track forward). The session-running
agent reflects on what it just did and proposes 0..N workflow-
improvement issues, **created directly in YouTrack** under the
`YTDB` project with the `dev-workflow` tag. The user gates which
proposals become real issues.

This is **process feedback**, not code review and not plan correction.
Code findings belong to the dimensional review loop. Plan flaws belong
to inline replanning or the plan-review pass. Reflection only captures
problems with the workflow itself: ambiguous instructions, missing
recipes, brittle automation, recurring frictions, gaps where a future
agent would have benefited from a rule that did not exist.

---

## Inputs

- `session-type` — one of `execute-tracks` (default; existing
  behavior) or `migrate-workflow` (new). A calling skill names the
  value on the line that invokes this document
  (e.g., "Invoke `.claude/workflow/self-improvement-reflection.md`
  with `session-type=migrate-workflow`"). When the caller omits the
  parameter, the document behaves as `session-type=execute-tracks`.
  Any value other than `execute-tracks` or `migrate-workflow` halts
  the document with `ERROR: unrecognized session-type "<value>";
  expected `execute-tracks` or `migrate-workflow`` and ends the
  session without writing any issue.

Four clauses branch on `session-type`: §"When it runs" (which
phase identifiers apply and which skip conditions fire), §"What
counts as a worth-recording issue" (the migration-shaped friction
sub-bullets apply only when `session-type=migrate-workflow`),
§"Reflection procedure" Step 2 (the commit-clean check is skipped
for `migrate-workflow`), and §"Issue body template" (the `**Phase:**`
field and the `**Source session:**` template literal extend to carry
the `migrate-workflow` value). Every other section of this document
is session-type-agnostic.

---

## YouTrack MCP requirement

Reflection writes its output to YouTrack via the YouTrack MCP server
(the `mcp__youtrack__*` tools). The protocol is gated on that server
being reachable.

- **MCP reachable** → run reflection as documented below.
- **MCP NOT reachable** → print one notice line and skip reflection
  entirely. There is no local fallback. The notice MUST appear in
  the user-facing end-of-session message:

  > YouTrack MCP unreachable — self-improvement reflection skipped
  > for this session.

  Then proceed to "End the session" per the phase's normal end-of-
  session instructions. No proposals are presented to the user, no
  files are written, no commits are made.

The check is purely a tool-listing inspection: scan the session's
available tools (including any deferred tools surfaced in the
session-start system reminders) for an `mcp__youtrack__*` entry. If
none appear, treat the server as unreachable and emit the skip
notice immediately. The listing IS the test — do not call any
`mcp__youtrack__*` tool to "probe" availability, and do not retry.
Reflection is best-effort, not load-bearing.

---

## When it runs

As the **last step** of every session run by a calling skill that
opts into reflection, immediately before "End the session". The
phase identifiers a session contributes depend on `session-type`:

- `session-type=execute-tracks` covers:
  - State 0 (autonomous plan review)
  - Phase A (review + decomposition)
  - Phase B (step implementation)
  - Phase C (code review + track completion)
  - Phase 4 (final artifacts)
- `session-type=migrate-workflow` covers the single phase identifier
  `migrate-workflow` (the migration replay loop end-to-end).

Reflection runs on every session that reached at least one phase
step applicable to its `session-type` (the five `/execute-tracks`
phase steps above, the single `migrate-workflow` phase identifier,
or, for `/execute-tracks`, the auto-resume / Track Pre-Flight gate
logic). The friction that triggered an early exit (context-window
warning, ESCALATE, two-failure rule, designed-in user gate at
Track Pre-Flight or State 0) is itself often the most valuable
finding. On a designed-in user gate the agent should default to
N=0; if the gate fired because the docs gave no rule for the
situation, the gap is exactly what reflection should record.

Reflection is skipped only when:

- the calling skill's startup protocol could not start any session
  work because of a missing prerequisite (for `/execute-tracks`:
  plan file does not exist, MCP cwd does not match, user cancels at
  the startup prompt; for `/migrate-workflow`: any halt before
  Step 5's final summary completes — the Step 1 narrow-scope
  dirty-tree check, the Step 1 argument mismatch, the Step 2
  both-arrays-empty halt, the Step 2.0 three-rejected-attempts
  bootstrap halt, the Step 3 stale `range_start`, the Step 4.1
  context-window halt, or the Step 4.3 stamp-format-change in-flight
  halt; the next session's reflection
  at Step 6 reports the friction once the migration completes
  successfully) — there is no session content to reflect on; or
- YouTrack MCP is not reachable (see §YouTrack MCP requirement) —
  there is no sink for the output.

---

## What counts as a worth-recording issue

In scope (record):

- Workflow document was ambiguous, contradicted itself, or sent the
  agent down a dead end.
- A workflow rule was missing — the agent had to invent a one-off
  decision because the docs did not cover the case.
- Automation was brittle: a script timed out, a hook misfired, a tool
  routed the agent to a stale code path, a recipe failed in a way that
  required manual intervention.
- The agent had to repeat the same correction more than once in a
  session because no rule prevented it.
- A sub-agent prompt produced output that the orchestrator had to
  rewrite or reject in a recurring way.
- Tooling gap: the agent reached for a recipe that should exist
  (`mcp-steroid` recipe, build script, helper) and had to roll it
  manually.
- A reviewer (Phase A or Phase C sub-agent) repeatedly raised the
  same low-value finding the workflow could have prevented upstream.
- Migration-shaped frictions (`session-type=migrate-workflow` only):
  - Ambiguous classification rules — replay logic could not decide
    whether a commit-shape entry belonged in the migration scope or
    outside it, and the docs did not resolve the ambiguity.
  - Missing replay patterns — the migration encountered a commit-
    shape pattern the replay rules did not cover, and the agent had
    to invent a one-off decision.
  - Halt-and-ask conditions firing without docs — the skill's
    halt-and-ask gate triggered for a situation the documentation
    did not anticipate, leaving the agent without a documented
    recovery path.
  - Edge cases in the renames tracker — the renames tracker did not
    handle a real-world rename (multi-hop chain, rename-plus-edit,
    conflicting rename targets) and the agent had to reconstruct
    intent by hand.

Out of scope (do not record here):

- Code-quality findings — those belong to the dimensional review loop
  output and the step / track episodes.
- Plan flaws — those go through inline replanning or are surfaced in
  State 0 plan review.
- "Future feature" ideas that did not actually bite this session.
- One-off transient failures (CI flake, network blip) with no
  workflow-level fix.
- General "I think the project should …" opinions unrelated to the
  workflow that produced them.

The bar is: *the user, looking only at the YouTrack issue, should be
able to act on it without re-deriving the context.* If the agent
cannot describe the reproduction context, the finding is not yet
ready to record — drop it or sharpen it.

---

## Cost-benefit gate

Every candidate (Bug or Feature) must clear a single inequality
before it is recorded. The gate compares the cost of carrying a
preventive rule in a workflow doc against the cost of letting the
friction recur and self-heal across future sessions. Both costs are
expressed in **turn-equivalents** (one turn-equivalent ≈ one agent
turn of work) so the two sides can be compared directly without
tracking raw tokens.

```
file if:  load_cost  ≤  self_fix_cost / 5     # at least a 5× margin
```

Horizon for both sides: **6 months**.

### load_cost — what the fix costs to carry

```
load_cost = paragraphs_added × per-paragraph cost from the table below
```

Pick the tier of the doc the fix would land in:

| Target doc tier                                                     | Per-paragraph cost (turn-equiv / horizon) |
|---------------------------------------------------------------------|-------------------------------------------|
| Always-loaded base (`CLAUDE.md`, `conventions.md`, `workflow.md`)   | ~5                                        |
| Phase doc (`track-review.md`, `step-implementation.md`, this guide) | ~2                                        |
| ESCALATE / on-demand recipe                                         | ~0.3                                      |

Rough paragraph counts: a 1-line caveat ≈ 0.2 paragraphs, a new
sub-section ≈ 1, a new recipe ≈ 3–5. The per-paragraph numbers
encode order-of-magnitude differences between tiers (always-loaded
paragraphs cost roughly 15–20× more than `ESCALATE`-only paragraphs
over the horizon, because they ride every session); calibrate by
tier, not by significant figures.

### self_fix_cost — what the friction costs if left alone

```
self_fix_cost = turns_per_occurrence × population_in_horizon
```

- **turns_per_occurrence**: the cost of one recovery this session.
  A doc re-read counts as 1, a sub-agent re-spawn as 3, a user
  prompt as 2; chain them as needed.
- **population_in_horizon**: an explicit integer naming the number
  of sessions over the 6-month horizon that will actually hit this
  trigger. The agent writes `population_in_horizon = <integer>`
  with a one-line justification (e.g., "every Phase A session that
  loads `track-review.md`", "only sessions whose plan covers a
  rename track"). The justification names *which* sessions hit the
  trigger, not just "all of them".
  - **Tier caps** (never exceed these without explicit justification):
    - Always-loaded base (`CLAUDE.md`, `conventions.md`,
      `workflow.md`) — cap ≈ 100 sessions / 6 months.
    - Phase doc (`track-review.md`, `step-implementation.md`, this
      guide) — cap ≈ 50 sessions / 6 months.
    - On-demand recipe (ESCALATE path, narrow recipe) — cap ≈ 5
      sessions / 6 months.
    The caps are ceilings, not free credits. A finding that fires
    only on rename-track Phase A sessions does not get the full
    phase-doc cap of 50 — count the rename-track sessions you
    actually expect.
  - **Recurrence floor**: if `population_in_horizon < 3`, the
    candidate fails the gate without computing the rest. A friction
    that bit one session and will plausibly bite fewer than three
    is project-shaped, not workflow-shaped.

### Worked examples

1. **Drop — recurrence floor fails.** Agent misread one heading
   once, recovered in 1 turn, no reason to expect it recurs.
   `population_in_horizon = 1` ("this session, no plausible
   future hits"). Fails the recurrence floor (1 < 3); drop without
   computing either side.

2. **File — wide population, cheap self-fix.** A wrong section name
   in `track-review.md` fires on every Phase A session that loads
   the doc.
   - `population_in_horizon = 50` ("every Phase A session over
     the horizon", at the phase-doc cap).
   - `self_fix_cost = 1 turn × 50 = 50`
   - Fix is a 1-line caveat (0.2 paragraphs) in a phase doc:
     `load_cost = 0.2 × 2 = 0.4`
   - Ratio `50 / 0.4 = 125×` → **file**. A naïve "feels cheap to
     self-fix" reading would miss the cumulative cost across 50
     future sessions.

3. **File — expensive per occurrence.** Phase A reviewer re-flags
   the same low-value finding on every iteration (~3 / Phase A).
   - `population_in_horizon = 150` ("3 iterations × every Phase A
     session — phase-doc cap × 3, justified by the inner-loop
     fan-out").
   - `self_fix_cost = 1 turn × 150 = 150`
   - Fix is a 1-paragraph upstream filter in the reviewer prompt
     (phase doc): `load_cost = 1 × 2 = 2`
   - Ratio `150 / 2 = 75×` → **file**.

4. **Drop — near-tie.** Agent re-read one doc and asked the user
   once (3 turns of recovery). Plausible on a small number of
   future sessions.
   - `population_in_horizon = 3` ("rename-track sessions that touch
     the renames tracker — three plausible in the horizon").
   - `self_fix_cost = 3 turns × 3 = 9`
   - The only place a preventive rule fits is `conventions.md`
     (always-loaded). A 1-paragraph clarification:
     `load_cost = 1 × 5 = 5`
   - Ratio `9 / 5 = 1.8×`, well inside the 5× margin → **drop**
     (see checklist). Surface as project-shaped guidance in the
     session's normal output, not under `dev-workflow`.

### Scope-match check

A candidate also fails the gate if its §Proposed fix is more than
**~5×** the cost of the friction it cures, measured in paragraphs
added or files touched. A widget-rule paper-cut that takes two
turns to recover from should not produce a fix that rewrites a
phase doc and touches three sub-agent prompts. These are
project-shaped or ADR-shaped findings, not workflow paper-cuts.
Surface them in the session's normal output, do not file them
under `dev-workflow`. The check is a sanity floor on top of the
ratio inequality: an oversized fix can clear the 5× margin on raw
numbers and still be the wrong shape for this queue.

### Quick checklist

- Did you actually compute both sides? If you wrote "feels worth
  it" without numbers, you skipped the gate.
- Compute the ratio `r = self_fix_cost / load_cost` and read off
  the verdict:
  - `r ≥ 5` (self-fix dominates by ≥5×) → **record**.
  - `0.2 < r < 5` (tie or near-tie band) → **drop**. The unit is
    coarse; only clear wins should make it through, and the
    workflow's signal-to-noise ratio matters more than
    completeness.
  - `r ≤ 0.2` (load dominates by ≥5×) → **drop**. The friction
    may still be real, but the fix is project- or ADR-shaped,
    not workflow-shaped. Surface it in the session's normal
    output if useful, but do not file it under `dev-workflow`.

---

## Per-session cap

At most **1** issue per session — this is a ceiling, not a target.
Zero is the expected outcome on most sessions; one should feel
exceptional. **Do not invent a finding to hit the cap.** Padding the
slot with a thin, manufactured friction just to file something is a
worse failure mode than filing zero, because the triager has to
spend turns rejecting it and the `dev-workflow` queue loses its
signal.

If reflection turns up more than one real friction, keep the
highest-impact one (highest severity, most frequent, or blocking the
most downstream work) and discard the rest. The others will resurface
the next time they bite. Quality of the proposal matters more than
completeness.

If reflection turns up zero, say so explicitly to the user and end
the session. A clean session that produces no findings is the
correct outcome, not a failure to look hard enough.

---

## Reflection procedure

1. **Verify YouTrack MCP availability.** Confirm the
   `mcp__youtrack__*` tools are reachable (see §YouTrack MCP
   requirement). If not, emit the skip notice and end the session
   per the phase's normal end-of-session instructions.

2. **Verify session work is committed.** Run
   `git status --porcelain`; the working tree must be clean. The
   phase docs invoke reflection *after* their own commit-and-push
   step, so a non-empty working tree here means an earlier step
   skipped a commit. Do not proceed — commit the pending work
   first, then resume reflection from this step. Issue creation
   relies on `git rev-parse HEAD` pointing at the session's final
   commit.

   *If `session-type=migrate-workflow`, skip this check —
   migration intentionally leaves the worktree dirty for user
   review. The Source line points at `HEAD` (pre-migration);
   `git rev-parse HEAD` and `git rev-parse --abbrev-ref HEAD` in
   Step 3 still run.*

3. **Capture branch + commit SHA.** These are baked into every
   issue's body so the triager can trace the issue back to the
   exact session state that produced it.
   ```bash
   git rev-parse --abbrev-ref HEAD   # branch name
   git rev-parse HEAD                # commit SHA (40-char hex)
   ```

4. **Scan the session.** Walk back through what was done in this
   phase: which workflow doc steps were followed, what the sub-
   agents returned, where the agent had to deviate, where automation
   misbehaved, where a decision had to be made without a rule. Two
   prompts to ask:
   - *"What was harder than it should have been?"*
   - *"What would I want a future agent in my exact position to know,
     that the current docs do not tell them?"*

5. **Draft candidate proposals.** Apply the filters in order:

   1. Drop any friction whose severity is below `medium` (see
      §Severity guide). Low-severity annoyances are noise — do not
      file them, and do not promote them to medium just to keep the
      proposal alive.
   2. Drop any friction that fails the §Cost-benefit gate. Compute
      both sides of the inequality explicitly: `load_cost` from the
      doc-tier table and `self_fix_cost` from
      `turns_per_occurrence × population_in_horizon`. Apply the
      checklist's 5× margin rule and the §Scope-match check. A
      friction that survives severity but fails the gate is a
      project- or ADR-shaped finding; mention it in the session's
      normal output if useful, but do not file it. The computed
      numbers are rendered in the issue body's §Cost-benefit gate
      section (see §Issue body template) so a later triager can
      challenge the verdict by reading the math off the issue, not
      the session log.
   3. Cap the surviving list at 1 (highest severity, most frequent,
      or blocking the most downstream work — see §Per-session cap).

   If every friction in the session is filtered out by the first
   two passes, that is a zero-finding session; skip to step 8 with
   the empty-result template.

   For each surviving candidate, draft:
   - Title (imperative summary, ≤80 chars)
   - One-line summary
   - Type: `Bug` if the friction is the workflow producing wrong
     outputs, silent failures, blocked sessions, or other forms of
     "broken behaviour"; `Feature` if the friction is a missing
     rule, missing recipe, missing automation, or other enhancement
     filling a gap (see §Type guide).
   - Severity (medium/high — see §Severity guide). Severity is
     rendered into the issue body for traceability **and** mapped to
     the YouTrack `Priority` field at creation time. The agent sets
     `Priority` on every issue from the severity mapping in
     §Severity guide; the triager can re-calibrate later, but the
     default is the agent's call.
   - Body draft (Symptom, Reproduction context, Why it's a problem,
     Proposed fix, Acceptance criteria — see §Issue body template)

6. **Merge same-session siblings.** Before checking against
   YouTrack, scan the surviving candidates for siblings: any pair
   where (a) the §Proposed fix lands in the same file + section, or
   (b) the §Symptom paragraphs share ≥2 named docs, tools, or
   sub-agents. Pick the strongest representative and merge the
   others into it, folding their §Symptom / §Reproduction context
   text where it adds detail and dropping it where it duplicates.
   Keep a list of merged-out titles for the Step 8 user-facing
   message so the user sees what was consolidated. This step exists
   because Step 7's YouTrack-side dedup compares against existing
   issues only, not against other candidates in the same draft —
   widget-rule-at-three-call-sites and "inverse halves of one
   carve-out" patterns escape that filter otherwise.

7. **Filter against existing dev-workflow issues.** Search YouTrack
   for recent `project: YTDB tag: dev-workflow` issues:
   ```
   mcp__youtrack__search_issues(
     query="project: YTDB tag: dev-workflow sort by: created desc",
     limit=20
   )
   ```
   For each candidate, compare its title + one-line summary against
   the returned issue summaries (semantic match, not just substring
   — "Phase A reviewer always re-flags X" and "X review-finding
   loop" may be the same friction worded differently). Drop any
   candidate that is clearly a duplicate of an open or recently-
   closed dev-workflow issue. The 20-newest window already biases
   toward live frictions, so no separate age cutoff is needed; if a
   long-closed rule has regressed, the new candidate's reproduction
   context will read differently enough from the closed one that it
   should not match. Keep a list of which candidates were filtered
   and which existing issue id matched — it surfaces in step 8
   alongside the same-session sibling-merge list from step 6.

8. **Present surviving candidates to the user.** Single message:

   ```
   ## Self-improvement reflection

   Branch: <branch> @ <short-SHA>

   Filtered N candidate(s) as duplicates of: YTDB-NNNN, YTDB-MMMM, ...

   M proposed for creation under YTDB (tag `dev-workflow`):

   1. [<Type>] <title> — <one-line summary>
   2. [<Type>] <title> — <one-line summary>
   ...

   Reply with the issue numbers to create (e.g. "1,3"), "all", or
   "none". I will create the chosen issues in YouTrack and end
   the session.
   ```

   If no candidates surfaced at all (M=0 and N=0), present:

   ```
   ## Self-improvement reflection

   No improvements proposed.
   ```

   and end the session.

   If everything was filtered as a duplicate (M=0, N>0), present:

   ```
   ## Self-improvement reflection

   N candidate(s) all filtered as duplicates of: YTDB-NNNN, YTDB-MMMM, ...
   No new issues proposed.
   ```

   and end the session.

9. **Create the chosen issues in YouTrack.**

   First, fetch the YTDB project's issue-fields schema **once for
   the whole batch**:
   ```
   mcp__youtrack__get_issue_fields_schema(project="YTDB")
   ```
   Use the result to:

   - **Validate the `Type` enum.** If the project's `Type` field
     does not include `"Bug"` and/or `"Feature"` verbatim, map each
     candidate to the closest accepted value: candidates the
     reflection drafted as `Bug` map to the first
     defect-/bug-flavoured value the schema accepts (`Bug`,
     `Defect`, `Issue`, …); candidates drafted as `Feature` map to
     the first enhancement-flavoured value (`Feature`, `Task`,
     `Enhancement`, `Improvement`, …). Note any mapping in the
     follow-up report (Step 10).
   - **Cover required fields beyond `Type`.** If the schema marks
     other custom fields as required at creation (e.g., `State`,
     `Subsystem`), set each one to a safe default the project
     documents — typically `State: Open` (or the equivalent
     "new / unconfirmed" state the schema lists first). If a
     required field has no value the agent can confidently pick,
     abort that candidate, surface the gap to the user in Step 10,
     and continue with the next.
   - **Set `Priority` from severity.** Map the candidate's drafted
     severity to the closest accepted value in the schema's
     `Priority` enum using the table in §Severity guide. Set
     `Priority` on every issue, regardless of whether the schema
     marks it required — the triager re-calibrates as needed, but
     the default is the agent's call. If the schema's `Priority`
     enum is missing one of the canonical values, fall back to the
     nearest accepted value and note the substitution in the
     Step 10 follow-up report. If the schema does not expose a
     `Priority` field at all, skip it and note that in Step 10.
   - **Cache the result for this session.** Do not re-fetch per
     candidate — the schema does not change mid-session.

   If `get_issue_fields_schema` itself fails, abort the whole batch
   (no `create_issue` calls). Report the failure to the user with
   the candidate titles so they can file the issues manually, then
   end the session.

   Then for each approved candidate:

   1. Call `mcp__youtrack__create_issue` with:
      - `project`: `"YTDB"`
      - `summary`: the title
      - `description`: the rendered Markdown body (see §Issue body
        template). The **Source: branch `<branch>`, commit `<SHA>`**
        line at the top of the body is mandatory.
      - `customFields`: include the mapped `Type` value, the
        mapped `Priority` value, plus any required fields the
        preflight identified (e.g.,
        `{"Type": "Bug", "Priority": "Major", "State": "Open"}`).
        Omit `Priority` only when the schema does not expose that
        field at all.
   2. Capture the returned issue id (e.g., `YTDB-1234`).
   3. Call `mcp__youtrack__manage_issue_tags` with:
      - `issueId`: the returned id
      - `operation`: `"add"`
      - `tag`: `"dev-workflow"`

   If `create_issue` fails for a given candidate, log the failure
   to the user and continue with the remaining approved candidates
   — do not abort the whole batch.

   If `create_issue` succeeds but `manage_issue_tags` fails (most
   likely cause: the `dev-workflow` tag does not yet exist in
   YouTrack and the calling user lacks permission to create it),
   keep the issue but report it back to the user with a note:
   "Created `<id>` but tagging failed — please add the
   `dev-workflow` tag manually." Do not delete the issue.

10. **Report created issues to the user.** Single follow-up message:

   ```
   Created N YouTrack issue(s):
   - YTDB-1234 — <title>
   - YTDB-1235 — <title>
   ...
   ```

   Append any per-issue tagging failures noted in step 9.

11. **End the session** per the phase's normal end-of-session
    instructions. **No commit is produced by reflection** — the
    issues live in YouTrack, nothing was added to the repo.

---

## Issue body template

The Markdown body submitted to `create_issue.description`:

```markdown
**Source:** branch `<branch-name>`, commit `<40-char-SHA>`
**Severity:** medium | high
**Phase:** state-0 | phase-a | phase-b | phase-c | phase-4 | migrate-workflow
**Source session:** <YYYY-MM-DD> /execute-tracks <adr-dir-name> | /migrate-workflow <adr-dir-name>

## Cost-benefit gate

- `load_cost = <paragraphs> × <tier multiplier> = <value>` (tier:
  always-loaded | phase-doc | on-demand)
- `population_in_horizon = <integer>` — <one-line justification
  naming which sessions actually hit the trigger>
- `self_fix_cost = <turns_per_occurrence> × <population_in_horizon>
  = <value>`
- `ratio = self_fix_cost / load_cost = <value>×`
- verdict: **record** (ratio ≥ 5) | drop (ratio < 5)
- scope-match check: §Proposed fix is ≤ ~5× the friction it cures
  (paragraphs added / files touched) — pass | fail

## Symptom

What the agent observed during the session, in one short paragraph
(≤2 paragraphs). Concrete, not abstract — name the doc, the step,
the tool, the sub-agent.

## Reproduction context

(≤6 bullets)

- Phase: <state-0 / phase-a / phase-b / phase-c / phase-4 / migrate-workflow>
- Workflow doc(s) involved: `path/to/doc.md` §Section
- Tool / sub-agent involved (if any): <name>
- ADR directory at the time: `docs/adr/<dir-name>/`
- Trigger condition: <what kicks this off — e.g., "any Phase B step
  whose implementer return value is non-SUCCESS">
- Prior-session evidence (for `medium` severity, see §Severity guide):
  `<commit-SHA | path/to/handoff.md | log line>` — names a session
  where this friction has already bitten.

## Why it's a problem

One short paragraph (≤1 paragraph) on the impact: wasted turns,
wrong outputs, silent failures, blocked sessions, recurring
corrections.

## Proposed fix

A specific, actionable change (≤3 paragraphs, OR an enumerated list
of ≤3 options with one-line trade-offs). Edit `<file>` §<section> to
<do X>. Or add a new recipe in `<file>` for <Y>. Or split <doc> into
<A> and <B>.

## Acceptance criteria

(≤4 bullets)

- <Workflow change visible at <path>>
- <Reproduction context no longer triggers the symptom>
- <If applicable: regression check — e.g., grep that the bad pattern
  is gone>
```

### Section length caps

The template literals above enforce hard caps. Total body length
**≤600 words** (excluding the `**Source:**` / `**Severity:**` /
`**Phase:**` / `**Source session:**` header block and the
§Cost-benefit gate section, which are fixed-shape). A thin friction
deserves a thin issue — padding the body to justify the gate verdict
is the same anti-pattern as inventing findings to fill the
§Per-session cap.

The **Source** line is mandatory — it lets the triager check out
the exact branch and commit that produced the friction.

For `session-type=migrate-workflow`, the recorded commit refers to
pre-migration HEAD; the user's subsequent migration-output commit
moves HEAD forward. The Source line is the migration's starting
point, not its ending point.

The `**Source session:**` field is a pipe-separated enumeration:
pick the calling skill; drop the other branch.

---

## Type guide

YouTrack `Type` field — pick one per issue:

- **Bug**: the workflow rule actively produces wrong outputs, silent
  failures, or blocks the session in an unrecoverable way. Examples:
  - Startup-protocol resume table fails to cover a real intermediate
    state, and the agent re-runs the step instead of routing to the
    recovery procedure.
  - A hook misfires and silently corrupts the track file.
  - A sub-agent prompt produces output the orchestrator cannot parse,
    causing the session to wedge.

- **Feature**: missing rule, missing recipe, missing automation, or
  any other gap-fill / enhancement. Examples:
  - An `mcp-steroid` recipe is referenced in two different docs but
    neither links to the canonical recipes index, so the agent looks
    it up twice in one phase.
  - A Phase A review sub-agent (technical, risk, or adversarial)
    repeatedly returns the same low-value finding that the
    orchestrator must override every iteration; no upstream filter
    exists.
  - The workflow has no recipe for a recurring pattern the agent had
    to roll manually.

When in doubt: content describes "broken" → `Bug`; content describes
"missing" → `Feature`. YouTrack does not have an "Enhancement" type
in this project — `Feature` is the closest match and is used for all
gap-fill work surfaced by reflection.

Both `Bug` and `Feature` candidates must clear the §Cost-benefit
gate. There is no Bug-vs-Feature asymmetry: the inequality compares
`load_cost` against `self_fix_cost` the same way for either type.
Framing the issue as "broken" or "missing" does not change the math.

---

## Severity guide

Reflection only records frictions whose severity is **medium or
higher**. Low-severity annoyances — a one-off recipe lookup, a
single ambiguous sentence, costing a turn or two without affecting
output correctness — are noise and should be dropped at draft time
(see Step 5). The two-level scale below is the full menu; there
is no `low` tier in the issue body, the priority mapping, or the
filter. If a friction does not clear the medium bar, do not file
it and do not relabel it to medium to keep it alive.

- `medium` — recurring friction or ambiguity that **(a)** bit
  THIS session AND **(b)** has already bitten at least one prior
  session whose log line, commit SHA, or handoff file the agent can
  cite by path in the issue body's §Reproduction context. The
  friction must still cost multiple turns per occurrence or cause
  occasional wrong outputs the user catches. A friction that bit
  only this session and is *plausibly* recurring does not clear
  medium — drop it; speculative-recurrence findings are noise. The
  evidence-floor citation is the price of admission to the
  `dev-workflow` queue.
  - *Example*: a Phase A review sub-agent (technical, risk, or
    adversarial) repeatedly returns the same low-value finding
    that the orchestrator must override every iteration; no
    upstream filter exists. Prior-session evidence: commit
    `<SHA>` in the previous track's Phase A log. (Clears the
    §Cost-benefit gate: `population_in_horizon = 150` ("3
    iterations × every Phase A session"),
    `self_fix_cost = 1 × 150 = 150`; an upstream-filter paragraph
    in a phase doc costs `load_cost = 1 × 2 = 2`;
    ratio `150 / 2 = 75×` clears the 5× margin.)
- `high` — blocks a phase, causes silent wrong outputs, or pushes
  the agent into an unrecoverable state. A `high` finding should
  be rare and almost always points to a missing rule or a
  contradiction.
  - *Example*: the startup-protocol resume table fails to cover
    a real intermediate state (e.g., orphan implementer commit
    with no episode), and the agent re-runs the step instead of
    routing to the recovery procedure.

### Severity → YouTrack `Priority` mapping

Set the YouTrack `Priority` custom field at creation time from the
candidate's severity. Use the project's schema (fetched once in
Step 9) to pick the closest accepted enum value:

| Severity | Preferred `Priority`            | Fallback order if missing      |
|----------|---------------------------------|--------------------------------|
| `medium` | `Normal`                        | `Major` → `Minor`              |
| `high`   | `Major`                         | `Critical` → `Show-stopper`    |

Reserve `Critical` / `Show-stopper` for `high` findings that
actively blocked the session and have no documented recovery path
— most `high` findings still map to `Major`. If the schema exposes
only a subset of these names, fall back to the nearest accepted
value and note the substitution in the Step 10 follow-up report so
the triager can verify it.

---

## What the agent must not do

- **Do not** auto-create YouTrack issues without user confirmation.
  Every issue created by reflection passes through the user gate in
  §step 8.
- **Do not** spawn a sub-agent for reflection. It is a single
  main-agent step; sub-agent overhead is not justified.
- **Do not** treat reflection as a place to dump code-review
  findings, plan corrections, or general project ideas. Stay on
  workflow-process problems.
- **Do not** exceed the 1-issue cap. If more bubble up, pick the
  top one and let the rest go — they will resurface naturally if they
  really matter.
- **Do not** invent a finding to fill the 1-issue cap. The cap is a
  ceiling; zero real findings is the typical outcome. Manufactured
  frictions waste triage turns and dilute the `dev-workflow` queue's
  signal.
- **Do not** record low-severity findings. Reflection's bar is
  medium-and-above (see §Severity guide). Annoyances that cost a
  turn or two without affecting workflow correctness are noise —
  observe them mentally and move on, do not file them, and do not
  relabel them as medium to slip them past the filter.
- **Do not** skip reflection on early-exit sessions (context warning,
  ESCALATE, two-failure rule). The friction that caused the early
  exit is usually the highest-value input. The only valid skip is
  YouTrack MCP being unreachable — see §YouTrack MCP requirement.
- **Do not** create issues in any project other than `YTDB`, or with
  any tag other than `dev-workflow`. The triager filters by both.
- **Do not** omit the YouTrack `Priority` custom field when the
  schema exposes it. Map the candidate's drafted severity to the
  nearest accepted enum value (see §Severity → YouTrack `Priority`
  mapping) and set it at creation. The triager can re-calibrate,
  but the default priority is the agent's responsibility.
- **Do not** record any candidate (Bug or Feature) that fails the
  §Cost-benefit gate. The bar is `load_cost ≤ self_fix_cost / 5`
  (a 5× margin between the sides): `load_cost` is paragraphs added
  × the doc-tier multiplier; `self_fix_cost` is turns per
  occurrence × `population_in_horizon`. Populations below 3 fail
  the recurrence floor, and ADR-specific frictions are
  project-shaped, not workflow-shaped.
- **Do not** record any candidate whose §Proposed fix is more than
  ~5× the cost of the friction it cures (§Scope-match check). A
  paper-cut friction with a phase-doc-rewriting fix is the
  workflow telling you the finding is project-shaped or
  ADR-shaped. Surface it in the session's normal output instead.
  This is a separate failure mode from the ratio inequality: an
  oversized fix can clear the 5× margin on raw numbers and still
  fail the scope-match check.
- **Do not** write local `workflow-issues/*.md` files or any other
  local issue buffer. The YouTrack sink is the only output channel;
  local files are intentionally gone.
- **Do not** omit the `Source: branch <…>, commit <…>` header from
  the issue body. It is the only durable link from the YouTrack
  issue back to the session that produced it.
