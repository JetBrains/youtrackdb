## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (files with no `## ` headings carry none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains your role (or your role is `any`, or the row's Roles is `any`) AND Phases contains your phase (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections.

Your role: final-designer.
Your phase: 4.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

Read and follow the workflow for Phase 4 (Final Artifacts).

Prose produced by this file follows the project house-style at `.claude/output-styles/house-style.md`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the canonical workflow-level anchor and tier mapping; the four banned-section heading slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`.

**Step 1 — Read workflow documents.**

Read these before doing anything else:
1. `.claude/workflow/conventions.md` — shared formats, plan file structure
2. `.claude/workflow/design-document-rules.md` — design document rules
   (especially § Mutation discipline and the `phase4-creation` row in
   the cold-read scope table)
3. `.claude/workflow/workflow.md` — §Final Artifacts (Phase 4)
4. `.claude/skills/edit-design/SKILL.md` — the orchestrator skill that
   `design-final.md` creation routes through (see Step 3 below)

**Step 2 — Read all workflow working files and the implemented code.**

Plan directory name: if "$ARGUMENTS" is non-empty, use it as the directory
name. Otherwise, default to the current git branch name
(`git branch --show-current`).

Read:
- `docs/adr/<dir-name>/_workflow/implementation-plan.md` — full plan with track episodes
- `docs/adr/<dir-name>/_workflow/design.md` — original design document (do NOT modify)
- `docs/adr/<dir-name>/_workflow/plan/track-*.md` — all track files with step
  episodes. Each track file carries the track's original description
  across its `## Purpose / Big Picture`, `## Context and Orientation`,
  `## Plan of Work`, and `## Interfaces and Dependencies` sections
  (written there by `create-plan` at Phase 1), so "what each track was
  supposed to do" lives in the track file. Per-step content (one block
  per completed step) lives in each track file's `## Episodes`
  section. Skipped tracks may have had their track files deleted by
  `track-skip` — for those tracks read the `[~] Track N`'s
  `**Skipped:**` line in the plan file instead.

Using the plan's Architecture Notes and track episodes as a guide, read the
actual implemented code: all classes, interfaces, and components mentioned
in the plan, plus any that emerged during execution.

**Tooling — PSI is required for symbol verification.** The two final
artifacts are committed to git, so the class diagrams, workflow
diagrams, and Decision Record "Implemented in" details must reflect
the *actual* code precisely. Use mcp-steroid PSI find-usages /
find-implementations / type-hierarchy when the mcp-steroid MCP server
is reachable to verify class hierarchies, method signatures, callers
of integration points, and override sets. Grep silently misses
polymorphic call sites, generic dispatch, and identifiers inside
Javadoc/comments — exactly the kinds of mistakes that would mislead
future readers of `design-final.md` and `adr.md`. Fall back to grep
only when mcp-steroid is unreachable, and note any reference-accuracy
caveats inline.

The verification cases listed above are **illustrative, not
exhaustive**. The operative criterion is reference accuracy — would
a missed or spurious match make a diagram, signature, caller list,
or "Implemented in" reference in the committed artifacts wrong?
When in doubt, route through PSI. `CLAUDE.md` § MCP Steroid → "Grep vs PSI — when to switch" is the last
authoritative source for edge cases.

**How to invoke:**
- The MCP server is `mcp-steroid`. Its tools are deferred, so load their schemas via ToolSearch first.
- Call `steroid_list_projects` once at session start to confirm the IDE has the right project open and matches the working tree.
- Run PSI queries (find-usages, find-implementations, type-hierarchy) via `steroid_execute_code`, which evaluates a Kotlin snippet against the PSI tree — there is no dedicated `find_usages` tool.
- For Kotlin recipes, fetch the `coding-with-intellij-psi` skill via `steroid_fetch_resource`.

**Step 3 — Produce the two final artifacts.**

### Ephemeral identifier rule (applies to BOTH artifacts)
<!-- roles=final-designer phases=4 summary="Strip working-file identifiers from both committed artifacts; the authoritative rule lives in a dedicated file." -->

`design-final.md` and `adr.md` are the **only** workflow files that
survive merge into `develop`. Every other workflow file —
`implementation-plan.md`, `plan/track-N.md`, `design.md`,
`design-mechanics.md`, `design-mutations.md` — lives under
`docs/adr/<dir-name>/_workflow/` and is removed in the cleanup commit
at the end of Phase 4 (Step 6 below) before the PR is merged. Anything
these final artifacts say must survive that deletion.

**The authoritative rule, forbidden/allowed lists, and rewrite examples
live in ephemeral-identifier-rule.md:final-designer:4.**
Read that file and apply it to both artifacts. (The `§2.3` stub in
`../conventions-execution.md` is a quick recap pointing at the same
file.)

Phase-4-specific reminders that follow from the shared rule:

- The `adr.md` Decision Records section is where the final IDs `D1`,
  `D2`, … live. Retain the numbering from the plan for traceability,
  **but only for IDs you are restating in `adr.md` itself**. Do not
  cite a plan-file-only `D<N>` that has no corresponding entry here.
- Do not write `Implemented in: Track X` or `Track X Step Y` lines in
  Decision Records — replace with a prose description, a file/class
  reference, or a commit SHA.
- Key Discoveries is the section most prone to leaks — it is typically
  synthesized from step episodes and easily carries track / step /
  finding labels along with the substance. Rewrite each discovery to
  stand on its own: strip any specific track or step numbers (`Track
  3`, `Step 2 of Track 4`, etc.) and any review-finding IDs, and keep
  only the substance, plus a file/class reference or commit SHA if
  "where" still matters.

Re-scan both artifacts before the commit step with the pre-commit
gate regex. Phase 4 commits go directly via this skill rather than
through the implementer sub-agent, so the gate is run by hand here
under the "ad-hoc commits outside the workflow" branch of
ephemeral-identifier-rule.md:final-designer:4
`§"Self-check before commit"` (also restated in the `§2.3` stub of
`../conventions-execution.md`).

### Artifact 1: Final Design Document (`design-final.md`)
<!-- roles=final-designer phases=4 summary="Produce the final design reflecting the actual implementation: verify diagrams against code, then use edit-design." -->

Produce `docs/adr/<dir-name>/design-final.md` (the top-level final
artifact, not under `_workflow/`) reflecting the **actual
implementation**. Same shape rules as the original `design.md` —
concept-first Overview, Core Concepts vocabulary primer (when the doc
has Parts or ≥3 new domain terms), Class Design, Workflow, per-section
TL;DR + mechanism overview + edge cases + References footer. The
canonical structure template lives in
design-document-rules.md:final-designer:4
`§ Structure`; do **not** restate it here.

**Sub-step A — Verification protocol (before invoking the skill).**
Build verification tables to ensure every diagram element traces to
real code. PSI is required where reachable per the rule above.

For class diagrams:
```
| Diagram Element        | Code Location        | Verified? | Notes           |
|------------------------|----------------------|-----------|-----------------|
| Class X                | file:line            | YES/NO    | actual name/role |
| X extends Y            | file:line            | YES/NO    |                 |
| X.method(args): return | file:line            | YES/NO    | actual signature |
```

For workflow/sequence diagrams:
```
| Step | Diagram Claim                 | Code Location | Actual Behavior | Match? |
|------|-------------------------------|---------------|-----------------|--------|
| 1    | Caller → method(args)         | file:line     | [what happens]  | YES/NO |
| 2    | Method → delegate(args)       | file:line     | [what happens]  | YES/NO |
```

Every element in the diagram must have a corresponding row. Do not
include classes, methods, or flows that you have not verified exist in
the current code. The tables do not appear in the final artifact —
they are working notes that ensure accuracy.

**Sub-step B — Invoke the edit-design skill.** With the verification
tables in hand, route the artifact creation through the mutation
discipline. Do **not** call `Write` / `Edit` directly on
`design-final.md` — invoke
edit-design/SKILL.md:final-designer:4
with:

- `mutation_kind`: `phase4-creation`
- `design_path`: `docs/adr/<dir-name>/design-final.md`
- `design_mechanics_path`: `docs/adr/<dir-name>/design-mechanics-final.md`
  if the original design had a mechanics companion (or if the final
  content would cross the length trigger), else `null`
- `target`: `both` when a mechanics-final companion exists, else
  `design` (no `.md` suffix — these values pass through to the
  script's `--target` flag verbatim)
- `plan_path` / `plan_dir`: **omit**. Phase 4 produces a new
  committed artifact whose section structure may differ from the
  original `design.md`; the plan and track-file `**Full design**` refs
  continue to point at the (frozen) original. The cross-file ref check
  is naturally skipped when these paths are absent.
- `intended_edit`: full file content for both files. Section names match
  between `design-final.md` and `design-mechanics-final.md` from the
  start (same rule as Phase 1).

The skill runs the standard atomic action — apply, mechanical checks
(`--target=both` or `--target=design`), `whole-doc` cold-read on
`design-final.md` via the design-review sub-agent, bounded iterate, and
present diff + log entry. The cold-read for `phase4-creation` carries an
extra check (per `prompts/design-review.md`): the artifact must stand on
its own as committed documentation, with no leaked working-file
identifiers (track / step / review-finding labels).

Rules (these are enforced by the discipline; listed here for orientation):

- All diagrams must be Mermaid. Reflect reality, not the plan.
- Pair every diagram with prose.
- Keep diagrams focused (class ≤ ~12 classes, sequence ≤ ~8 participants).
- Complex parts (concurrency, crash recovery, performance paths) are
  mandatory dedicated sections.
- Do **NOT** modify the original `design.md` (and `design-mechanics.md`
  if present) — those are frozen after Phase 1.

**Context consumption check between artifacts.** After
`design-final.md` is written and committed, run
`cat /tmp/claude-code-context-usage-$PPID.txt`. If the level is
`warning` (≥30%) or `critical` (≥40%), do NOT start `adr.md`. Save
all work and ask the user for a session refresh (see
`workflow.md` §Context Consumption Check). Write a handoff file at
`docs/adr/<dir-name>/_workflow/handoff-phase4.md` per
mid-phase-handoff.md:orchestrator:4: `design-final.md`
is on disk but `adr.md` is not, so the next session resumes at the
ADR step without re-reading every episode or re-writing the
final-design content. The same applies to mid-`adr.md` pauses;
capture which sections of `adr.md` are already drafted in the
handoff. If the file does not exist or the command fails, this is
**not an error** — treat as `safe` and continue.

### Artifact 2: ADR (`adr.md`)
<!-- roles=final-designer phases=4 summary="Write the ADR from the plan adjusted for actual outcomes, aggregating step and track episodes; append telemetry last." -->

Write `docs/adr/<dir-name>/adr.md` — a post-implementation Architecture
Decision Record derived from `implementation-plan.md`, adjusted for actual
outcomes using insights from all episodic memories.

**Episodic memory aggregation:** Scan **all step episodes first** (they
contain ground-truth details — "What was discovered", "What was done",
"What changed from the plan"), then cross-reference with **track episodes**
(which add strategic framing). Both levels must be aggregated — track
episodes are summaries that may omit step-level details important for
future work. Every discovery and plan deviation from either level should
be evaluated for inclusion in the ADR.

```
# <Feature Name> — Architecture Decision Record

## Summary
<What problem it solves, what was built.>

## Goals
<Adjusted for actual outcomes. Note descoped or changed goals.>

## Constraints
<Note relaxed constraints or new ones discovered.>

## Architecture Notes

### Component Map
<Updated Mermaid diagram + bullet list reflecting actual topology.>

### Decision Records
<All decisions from the plan, updated for actual outcomes:
- Implemented as planned → note it
- Modified during execution → update rationale, note what changed and why
- New decisions that emerged → add with rationale
Retain D1, D2, ... numbering; append new decisions at the end.>

### Invariants & Contracts (if applicable)
### Integration Points (if applicable)
### Non-Goals (if applicable)

## Key Discoveries
<Synthesized from both track episodes AND step episodes — important things
learned during implementation that weren't known at planning time. Step
episodes are the primary source (ground truth); track episodes provide
strategic framing. Include discoveries that would affect future work in
the same area, even if they seem minor at the step level.>

<!-- Telemetry section placeholder. The ENTIRE section — the
`## Token usage telemetry` heading AND its body — is the verbatim stdout of
`measure-read-share.py`, pasted by the invocation block below. Do NOT write
the heading by hand and do NOT reproduce this placeholder as a literal
heading: the script's first output line is the heading. Always present, even
on the skip paths — the script emits the same H2 with an explanatory body
when it cannot measure. See the invocation block below. -->
```

Rules:
- No track details — captures decisions and outcomes, not execution process.
- Aggregate from both episode levels — do not rely on track episodes alone,
  as they may omit step-level details.
- Apply the Ephemeral identifier rule from the top of Step 3 (full
  rule in `../ephemeral-identifier-rule.md`) to the whole file —
  especially to Decision Records ("Implemented in: …" lines) and Key
  Discoveries, which are the two most frequent leak sites.

**Telemetry section — run the script, paste its output.** After writing
the rest of `adr.md` (through `## Key Discoveries`), populate the
`## Token usage telemetry` section by running the measurement script and
appending its stdout verbatim as the final section of the file:

```bash
python3 .claude/scripts/measure-read-share.py
```

Capture the script's stdout and append it unchanged as the last section of
`adr.md`. The script prints a complete `## Token usage telemetry` H2 with
its body, so its stdout IS the whole section: do not write the heading by
hand and do not edit the rows. The output is publication-safe by
construction (percentages-only, repo-relative paths; the only absolute
numbers are the session and transcript counts). Always paste whatever the
script prints: when the script cannot produce a measurement — any case
where it skips rather than measures, including run from the main checkout,
not running inside a git checkout, an empty transcript folder, or an
unparseable transcript — it emits the SAME `## Token usage telemetry` H2
with an explanatory skip body and exits 0. The prompt therefore does NOT
branch on the script's exit code — once `python3` parses and runs the
script there is exactly one path: run it, paste the output.

One exception covers the script never running at all. If the command fails
at the shell level — a non-zero shell exit with empty stdout, e.g. `python3`
is unavailable or the script file is missing — there is no stdout to paste.
In that case write a one-line `## Token usage telemetry` section recording
that telemetry was unavailable because the script could not be invoked, and
continue. Do not block the `adr.md` commit on it; the section stays present
either way.

Run the telemetry step exactly once, after every other `adr.md` section is
final (it is the only Step 3 sub-instruction with a hard run-last ordering,
so the snapshot reflects the finished ADR). On a resumed or re-run Step 3,
if a `## Token usage telemetry` section already exists from a prior partial
run, replace that section rather than appending a second one — and if the
ADR is otherwise complete, leave the existing section as is rather than
regenerating it against a different snapshot.

**Step 4 — Promote staged workflow changes (workflow-modifying plans only).**

Workflow-modifying plans accumulate every `.claude/workflow/**` and
`.claude/skills/**` edit under `<dir-name>/_workflow/staged-workflow/`
throughout Phase B and Phase C per
conventions.md:final-designer:4 `§1.7`; this step copies the
staged subtree onto the live tree in one commit before the
final-artifacts commit. The promotion is additive only per
`../conventions.md` `§1.7(e)`; plans that need to delete live workflow
files land the deletion outside staging. Non-workflow-modifying plans
have no staged subtree on disk and the step is a silent no-op for them
— Phase 4 stays in its two-commit shape.

The directory-presence guard checks for the `.claude/` subdirectory
under the staged path rather than the bare staged directory: a
partially-stripped or empty `staged-workflow/` shell would otherwise
trigger a no-op promotion commit on a non-workflow-modifying plan.

Before copying, the step runs a divergence sanity check against
`origin/develop`'s live workflow content. A non-empty divergence means
`origin/develop` carries workflow commits this branch has not absorbed
via rebase, so `cp -r` would silently overwrite live changes that
already exist on `develop`'s side — the rebase-precedes-promotion rule
in `../conventions.md` `§1.7(f)`. The step halts with a
manual-reconciliation instruction in that case; the user rebases the
branch onto current `origin/develop` and restarts Phase 4.

Run:

```bash
PLAN_DIR="docs/adr/<dir-name>"
STAGED_DIR="$PLAN_DIR/_workflow/staged-workflow"

if [ -d "$STAGED_DIR/.claude" ]; then
  git fetch origin develop --quiet
  DIVERGENCE=$(git log "$(git merge-base origin/develop HEAD)..origin/develop" -- .claude/workflow .claude/skills)
  if [ -n "$DIVERGENCE" ]; then
    echo "ERROR: origin/develop carries .claude/workflow or .claude/skills commits this branch has not absorbed."
    echo "Rebase the branch onto current origin/develop before promotion (conventions.md §1.7(f))."
    exit 1
  fi
  cp -r "$STAGED_DIR/.claude/." .claude/
  git add .claude/workflow .claude/skills
  git diff --cached --quiet || git commit -m "Promote workflow changes from $STAGED_DIR"
  git push
fi
```

The commit message prefix
`Promote workflow changes from docs/adr/<dir-name>/_workflow/staged-workflow`
matches the implementer-rules live-workflow-path gate's allow-clause
verbatim per `../conventions.md` `§1.7(b)/(e)` and
`../implementer-rules.md` § *Pre-commit gate, live-workflow-path
check*. Any deviation from this prefix causes the gate to refuse the
commit. The cleanup commit in Step 6 below removes the staged subtree
alongside the rest of `_workflow/`; the live tree carries the promoted
content forward to the merge.

**Step 5 — Commit the final artifacts.**

By this point the `edit-design` skill has already written
`design-final.md` (and `design-mechanics-final.md` if applicable) to
disk and presented the diff + review-log entry. `adr.md` was written
directly. Stage and commit the final artifacts in a single commit:

```
Add final design and ADR

Post-implementation artifacts:
- design-final.md: actual design reflecting implemented code
- (optional) design-mechanics-final.md: long-form mechanism content
- adr.md: architecture decision record with actual outcomes
```

Stage **only** the top-level final artifacts in this commit
(`design-final.md`, `design-mechanics-final.md` if present, and
`adr.md`). Do **not** stage anything under
`docs/adr/<dir-name>/_workflow/` — the ephemeral
`design-mutations.md` log and every other working file under
`_workflow/` are removed wholesale by the cleanup commit in Step 6
below.

Push immediately after committing:

```bash
git push
```

**Step 6 — Cleanup commit: remove the workflow scaffolding.**

After the final-artifacts commit lands, remove the entire `_workflow/`
subtree in a single second commit so only `design-final.md`,
`design-mechanics-final.md` (if present), and `adr.md` survive merge
into `develop`:

```bash
git rm -r docs/adr/<dir-name>/_workflow/
git commit -m "Remove workflow scaffolding"
git push
```

This deletes the plan, `design.md`, `design-mechanics.md`, every track
file under `plan/`, and the design-mutations log in one commit.
The squash-merge folds this deletion together with the rest of the
branch's history; on `develop`, the final state is the two (or three)
durable artifacts plus the implemented code.

**Step 7 — Run self-improvement reflection.**

Load `../self-improvement-reflection.md` on-demand and follow it.
Phase 4 friction worth recording typically lives in the
`design-final.md` / `adr.md` templates, the cleanup-commit
mechanics, the Ephemeral identifier rule's interaction with
durable artifacts, or the Phase 4 resume markers. Reflection runs
before the user-visible "Phase 4 complete" message in Step 8. The
protocol creates approved proposals as YouTrack issues under
`YTDB` with the `dev-workflow` tag (or skips with a notice if the
YouTrack MCP server is unreachable); reflection produces no
commit. Then proceed to Step 8.

**Step 8 — Inform the user.**

Tell the user Phase 4 is complete and the branch is ready for review.
The user manually flips the draft PR to "ready for review" when
satisfied — Claude does **not** run `gh pr ready` automatically.

If reflection created any YouTrack issues in Step 7, list the issue
ids in the "Phase 4 complete" message so the user has a quick path
back to them — but no further action is required of the
implementer; the issues live in YouTrack and are independent of the
branch's merge state.
