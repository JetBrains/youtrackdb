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

Three clauses branch on `session-type`: §"When it runs" (which
phase identifiers apply and which skip conditions fire),
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
or — for `/execute-tracks` — the auto-resume / Track Pre-Flight
gate logic). The friction that triggered an early exit (context-
window warning, ESCALATE, two-failure rule, designed-in user gate
at Track Pre-Flight or State 0) is itself often the most valuable
finding. On a designed-in user gate the agent should default to
N=0 — unless the gate fired because the docs gave no rule for the
situation, in which case the gap is exactly what reflection should
record.

Reflection is skipped only when:

- the calling skill's startup protocol could not start any session
  work because of a missing prerequisite (for `/execute-tracks`:
  plan file does not exist, MCP cwd does not match, user cancels at
  the startup prompt; for `/migrate-workflow`: no `_workflow/`
  artifacts to migrate, MCP cwd does not match, user cancels at the
  startup prompt) — there is no session content to reflect on; or
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
file if:  load_cost  ≤  self_fix_cost / 2     # at least a 2× margin
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
self_fix_cost = turns_per_occurrence × occurrences_in_horizon
```

- **turns_per_occurrence**: the cost of one recovery this session.
  A doc re-read counts as 1, a sub-agent re-spawn as 3, a user
  prompt as 2; chain them as needed.
- **occurrences_in_horizon**: a trigger is **deterministic** if a
  code path or doc rule will make the same input produce the same
  friction on every future session that hits it; it is
  **non-deterministic** if the friction depends on session-specific
  context (this branch's code, this user's prompt, this run's
  timing).
  - **Deterministic-recur trigger**: every matching session fires.
    Use the matching tier's session count over the horizon
    (always-loaded ≈ 100, phase doc ≈ 50, on-demand ≈ 5).
  - **Non-deterministic, plausible on ≥3 future sessions**: use 3.
  - **Non-deterministic, fewer than 3 plausible future hits**: the
    candidate fails the recurrence floor; drop without computing.

### Worked examples

1. **Drop — recurrence floor fails.** Agent misread one heading
   once, recovered in 1 turn, no reason to expect it recurs. Fails
   the recurrence floor; drop without computing either side.

2. **File — deterministic, cheap self-fix.** A wrong section name
   in `track-review.md` deterministically fires on every Phase A
   session.
   - `self_fix_cost = 1 turn × 50 sessions = 50`
   - Fix is a 1-line caveat (0.2 paragraphs) in a phase doc:
     `load_cost = 0.2 × 2 = 0.4`
   - `0.4 << 50` → **file**. A naïve "feels cheap to self-fix"
     reading would miss the cumulative cost across 50 future
     sessions; the recurrence-floor branch catches it.

3. **File — expensive per occurrence.** Phase A reviewer re-flags
   the same low-value finding on every iteration (~3 / Phase A,
   deterministic).
   - `self_fix_cost = 1 turn × (3 × 50) = 150`
   - Fix is a 1-paragraph upstream filter in the reviewer prompt
     (phase doc): `load_cost = 1 × 2 = 2`
   - `2 << 150` → **file**.

4. **Drop — near-tie.** Agent re-read one doc and asked the user
   once (3 turns of recovery). Plausible on ~3 future sessions,
   not deterministic.
   - `self_fix_cost = 3 × 3 = 9`
   - The only place a preventive rule fits is `conventions.md`
     (always-loaded). A 1-paragraph clarification:
     `load_cost = 1 × 5 = 5`
   - Ratio `self_fix / load = 9 / 5 = 1.8×`, inside the 2× margin
     → **drop** (see checklist). Surface as project-shaped
     guidance in the session's normal output, not under
     `dev-workflow`.

### Quick checklist

- Did you actually compute both sides? If you wrote "feels worth
  it" without numbers, you skipped the gate.
- Compute the ratio `r = self_fix_cost / load_cost` and read off
  the verdict:
  - `r ≥ 2` (self-fix dominates by ≥2×) → **record**.
  - `0.5 < r < 2` (tie or near-tie) → **drop**. The unit is
    coarse; only clear wins should make it through, and the
    workflow's signal-to-noise ratio matters more than
    completeness.
  - `r ≤ 0.5` (load dominates by ≥2×) → **drop**. The friction
    may still be real, but the fix is project- or ADR-shaped,
    not workflow-shaped. Surface it in the session's normal
    output if useful, but do not file it under `dev-workflow`.

---

## Per-session cap

At most **3** issues per session — this is a ceiling, not a target.
Zero or one real finding is the expected outcome on most sessions;
two or three should feel exceptional. **Do not invent findings to
hit the cap.** Padding the buffer with thin, manufactured frictions
just to fill three slots is a worse failure mode than filing zero,
because the triager has to spend turns rejecting them and the
`dev-workflow` queue loses its signal.

If reflection turns up more than three real frictions, keep the
three highest-impact ones (highest severity, most frequent, or
blocking the most downstream work) and discard the rest. Quality
of the proposals matters more than completeness.

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
      `turns_per_occurrence × occurrences_in_horizon`. Apply the
      checklist's 2× margin rule. A friction that survives severity
      but fails the gate is a project- or ADR-shaped finding;
      mention it in the session's normal output if useful, but do
      not file it. The computed numbers stay in the agent's draft
      and are not persisted into the issue body; a later triager
      who challenges the verdict re-runs the gate from the
      reproduction context.
   3. Cap the surviving list at 3 (highest severity, most frequent,
      or blocking the most downstream work — see §Per-session cap).

   If every friction in the session is filtered out by the first
   two passes, that is a zero-finding session; skip to step 7 with
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

6. **Filter against existing dev-workflow issues.** Search YouTrack
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
   and which existing issue id matched — it surfaces in step 7.

7. **Present surviving candidates to the user.** Single message:

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

8. **Create the chosen issues in YouTrack.**

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
     follow-up report (Step 9).
   - **Cover required fields beyond `Type`.** If the schema marks
     other custom fields as required at creation (e.g., `State`,
     `Subsystem`), set each one to a safe default the project
     documents — typically `State: Open` (or the equivalent
     "new / unconfirmed" state the schema lists first). If a
     required field has no value the agent can confidently pick,
     abort that candidate, surface the gap to the user in Step 9,
     and continue with the next.
   - **Set `Priority` from severity.** Map the candidate's drafted
     severity to the closest accepted value in the schema's
     `Priority` enum using the table in §Severity guide. Set
     `Priority` on every issue, regardless of whether the schema
     marks it required — the triager re-calibrates as needed, but
     the default is the agent's call. If the schema's `Priority`
     enum is missing one of the canonical values, fall back to the
     nearest accepted value and note the substitution in the
     Step 9 follow-up report. If the schema does not expose a
     `Priority` field at all, skip it and note that in Step 9.
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

9. **Report created issues to the user.** Single follow-up message:

   ```
   Created N YouTrack issue(s):
   - YTDB-1234 — <title>
   - YTDB-1235 — <title>
   ...
   ```

   Append any per-issue tagging failures noted in step 8.

10. **End the session** per the phase's normal end-of-session
    instructions. **No commit is produced by reflection** — the
    issues live in YouTrack, nothing was added to the repo.

---

## Issue body template

The Markdown body submitted to `create_issue.description`:

```markdown
**Source:** branch `<branch-name>`, commit `<40-char-SHA>`
**Severity:** medium | high
**Phase:** state-0 | phase-a | phase-b | phase-c | phase-4 | migrate-workflow
**Source session:** <YYYY-MM-DD> /execute-tracks <adr-dir-name>
                  | <YYYY-MM-DD> /migrate-workflow <adr-dir-name>

## Symptom

What the agent observed during the session, in one short paragraph.
Concrete, not abstract — name the doc, the step, the tool, the
sub-agent.

## Reproduction context

- Phase: <state-0 / phase-a / phase-b / phase-c / phase-4 / migrate-workflow>
- Workflow doc(s) involved: `path/to/doc.md` §Section
- Tool / sub-agent involved (if any): <name>
- ADR directory at the time: `docs/adr/<dir-name>/`
- Trigger condition: <what kicks this off — e.g., "any Phase B step
  whose implementer return value is non-SUCCESS">

## Why it's a problem

One short paragraph on the impact: wasted turns, wrong outputs,
silent failures, blocked sessions, recurring corrections.

## Proposed fix

A specific, actionable change. Edit `<file>` §<section> to <do X>.
Or add a new recipe in `<file>` for <Y>. Or split <doc> into <A> and
<B>. If multiple options exist, list them with one-line trade-offs.

## Acceptance criteria

- <Workflow change visible at <path>>
- <Reproduction context no longer triggers the symptom>
- <If applicable: regression check — e.g., grep that the bad pattern
  is gone>
```

The **Source** line is mandatory — it lets the triager check out
the exact branch and commit that produced the friction.

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

- `medium` — recurring friction or ambiguity that costs multiple
  turns per occurrence, or causes occasional wrong outputs that
  the user catches.
  - *Example*: a Phase A review sub-agent (technical, risk, or
    adversarial) repeatedly returns the same low-value finding
    that the orchestrator must override every iteration; no
    upstream filter exists. (Clears the §Cost-benefit gate:
    `self_fix_cost = 1 turn × 3 iterations × 50 Phase A sessions
    = 150`; an upstream-filter paragraph in a phase doc costs
    `load_cost = 1 × 2 = 2`; 2 << 150.)
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
Step 8) to pick the closest accepted enum value:

| Severity | Preferred `Priority`            | Fallback order if missing      |
|----------|---------------------------------|--------------------------------|
| `medium` | `Normal`                        | `Major` → `Minor`              |
| `high`   | `Major`                         | `Critical` → `Show-stopper`    |

Reserve `Critical` / `Show-stopper` for `high` findings that
actively blocked the session and have no documented recovery path
— most `high` findings still map to `Major`. If the schema exposes
only a subset of these names, fall back to the nearest accepted
value and note the substitution in the Step 9 follow-up report so
the triager can verify it.

---

## What the agent must not do

- **Do not** auto-create YouTrack issues without user confirmation.
  Every issue created by reflection passes through the user gate in
  §step 7.
- **Do not** spawn a sub-agent for reflection. It is a single
  main-agent step; sub-agent overhead is not justified.
- **Do not** treat reflection as a place to dump code-review
  findings, plan corrections, or general project ideas. Stay on
  workflow-process problems.
- **Do not** exceed the 3-issue cap. If more bubble up, pick the top
  three and let the rest go — they will resurface naturally if they
  really matter.
- **Do not** invent findings to fill the 3-issue cap. The cap is a
  ceiling; zero or one real finding is the typical outcome.
  Manufactured frictions waste triage turns and dilute the
  `dev-workflow` queue's signal.
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
  §Cost-benefit gate. The bar is `load_cost ≤ self_fix_cost / 2`
  (a 2× margin between the sides): `load_cost` is paragraphs added
  × the doc-tier multiplier; `self_fix_cost` is turns per
  occurrence × occurrences in horizon. Non-deterministic one-offs
  fail the recurrence floor, and ADR-specific frictions are
  project-shaped, not workflow-shaped.
- **Do not** write local `workflow-issues/*.md` files or any other
  local issue buffer. The YouTrack sink is the only output channel;
  local files are intentionally gone.
- **Do not** omit the `Source: branch <…>, commit <…>` header from
  the issue body. It is the only durable link from the YouTrack
  issue back to the session that produced it.
