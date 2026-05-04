---
name: edit-design
description: "Apply an edit to design.md or design-mechanics.md through the mutation discipline: apply → auto-review → iterate → present. Use this instead of directly Editing those files."
argument-hint: "<plan-dir-path> [<mutation-kind>]"
user-invocable: false
---

Apply an edit to `design.md` (or `design-mechanics.md`) through the **mutation
discipline** defined in `.claude/workflow/design-document-rules.md`. The skill
bundles `(apply edit → auto-review → bounded iterate → present)` into one
atomic action so the structural rules are self-enforcing.

**You MUST use this skill — not raw `Edit`/`Write` — for every modification to
`design.md` or `design-mechanics.md`.** That includes initial creation in
Phase 1, interactive iteration ("add a section about X"), inline replanning
during Phase 3 ESCALATE, and Phase 4 production of `design-final.md`.

## Skill inputs

The invoking agent supplies these when calling the skill:

| Input | What it carries |
|---|---|
| `design_path` | Absolute path to `design.md` (or `design-final.md` in Phase 4). |
| `design_mechanics_path` | Absolute path to `design-mechanics.md` (or `null` if no companion). |
| `plan_path` | Absolute path to `implementation-plan.md` (for `**Full design**` link resolution). |
| `backlog_path` | Absolute path to `implementation-backlog.md` (same purpose). |
| `target_file` | One of `design.md` or `design-mechanics.md` — the file the edit applies to. |
| `intended_edit` | Either `(old_string, new_string)` for a focused edit, or full new content for a section-add / section-rewrite. |
| `mutation_kind` | One of: `content-edit`, `section-add`, `section-remove`, `section-rename`, `section-move`, `structural-rewrite`, `length-trigger-crossing`. |
| `changed_section` | The title of the section being changed (for bounded cold-read scope). For `section-rename`, supply the **new** name. |
| `iteration_budget` | Default `3` — max number of (apply → review) rounds. |

If any required input is missing, **ask the user before proceeding.** The
mutation discipline depends on the agent stating the mutation kind explicitly
so the cold-read scope is correct; do not guess.

## Workflow

### Step 1: Apply the edit

Use the `Edit` tool (for focused edits) or `Write` (for full-file rewrites or
new sections). Read the target file first to satisfy the `Edit` precondition.

Do not retry the apply — if `Edit` fails because the `old_string` is not
unique or doesn't match, surface that to the user and stop. The mutation
action does not paper over a malformed edit.

### Step 2: Determine cold-read scope

Per `.claude/workflow/design-document-rules.md § Mutation discipline §
Cold-read scope by mutation kind`:

| Mutation kind | Scope |
|---|---|
| `content-edit` | `bounded` — changed section + 1-2 surrounding sections + Reader Orientation + Overview |
| `section-add` | `bounded` — new section + Reader Orientation + Overview + ToC |
| `section-remove` | `whole-doc` |
| `section-rename` | `whole-doc` |
| `section-move` | `whole-doc` |
| `structural-rewrite` | `whole-doc` |
| `length-trigger-crossing` | `whole-doc` |

Track a mutation counter: every Nth mutation (default `N=5`, counted from
entries in `<plan-dir>/reviews/design-mutations.md`) escalates to
`whole-doc` regardless of the kind. Read the existing log first to count.

### Step 3: Run mechanical checks

```bash
python3 .claude/scripts/design-mechanical-checks.py \
    --design-path <design_path> \
    --design-mechanics-path <design_mechanics_path or omit> \
    --plan-path <plan_path or omit> \
    --backlog-path <backlog_path or omit> \
    --changed-section "<title>" \
    --scope <bounded|whole-doc>
```

The script prints JSON to stdout. Exit code `0` ⇒ no blockers; `1` ⇒ NEEDS
REVISION. Capture and parse the JSON; do not act on the exit code alone —
the findings list is what drives iteration.

### Step 4: Run the cold-read sub-agent

**Skip cold-read when mechanical checks have any `blocker` finding.** No
point asking a sub-agent to assess comprehension if the structure is broken
— iterate on mechanical first, then cold-read once the doc is structurally
sound.

When mechanical has zero blockers (only `should-fix` and/or `suggestion`,
or PASS), spawn the cold-read sub-agent via the `Agent` tool:

- `subagent_type`: `general-purpose`
- `description`: `"Cold-read design review (<mutation_kind>)"`
- `prompt`: the full content of `.claude/workflow/prompts/design-review.md`,
  with the `## Inputs` block at the top extended by literal substitutions:

```
- design_path: <abs path>
- design_mechanics_path: <abs path or "(none)">
- scope: <bounded|whole-doc>
- bounded_scope: <changed_section name + surrounding section names, when bounded>
- mutation_kind: <kind>
- plan_path: <abs path or "(none)">
- backlog_path: <abs path or "(none)">
```

The sub-agent returns a structured Markdown verdict per the prompt's output
format. Parse the **Verdict** line (`PASS` or `NEEDS REVISION`) and the
**Structural findings** list. Map cold-read findings into the same severity
schema as mechanical findings.

### Step 5: Merge findings

Combine mechanical + cold-read findings into a single list. Sort by
severity: `blocker` → `should-fix` → `suggestion`. Deduplicate by `(rule,
location)` if the same issue appears in both halves.

### Step 6: Iterate

If any `blocker` remains and the iteration budget is not exhausted:

1. **Try auto-apply for mechanical findings.** When a finding has
   `auto_applicable: true`, apply its `suggested_fix` directly with `Edit`.
   The current auto-applicable rule is `dsc-parenthetical-aside` (regex
   strip).
2. **For non-auto-fixable findings**, attempt the fix yourself based on
   the `suggested_fix` text and the surrounding context. Use `Edit` for
   focused changes.
3. **Re-run mechanical checks and (if mechanical now passes) cold-read.**
4. **Decrement the iteration budget.**

If the budget exhausts with blockers remaining: **the action does not
succeed.** Leave the partial edits on disk (do not revert), append a clear
warning to the review log, and present findings + diff to the user for
manual resolution.

If only `should-fix` and/or `suggestion` findings remain after one round,
attempt a second pass to address them; if they persist, log them and
complete with a warning.

### Step 7: Append to the review log

Determine the plan dir from `design_path`'s parent. Append to
`<plan-dir>/reviews/design-mutations.md` (create the directory and file if
they don't exist). Format per `design-document-rules.md § Mutation
discipline § Review log`:

```markdown
## Mutation N — <ISO date YYYY-MM-DD> — <mutation kind>

**Diff summary**: <one paragraph describing the change>

**Mechanical checks**: <PASS / N findings>
**Cold-read** (scope: <bounded|whole-doc>): <PASS / N findings / SKIPPED>

**Findings**:
- <severity>: <description>

**Iterations**: <i> of <budget> (PASS | BLOCKER REMAINS | SHOULD-FIX REMAINS)
```

Use `Read` to find the highest existing mutation number and increment by
one. The first mutation is `## Mutation 1 — ...`.

### Step 8: Present to the user

Show:

1. The diff applied (`git diff <design_path>` or a manual summary if not
   in a git repo).
2. The review-log entry just written.
3. A one-line outcome: `PASS`, `BLOCKER REMAINS — manual resolution
   required`, or `COMPLETE WITH SHOULD-FIX FINDINGS`.

The action is then complete. The agent returns control to the user / parent
flow.

## Tools used

- `Read` — verify file state, read review log for mutation count.
- `Edit` / `Write` — apply the edit and any auto-fixes.
- `Bash` — run the mechanical-checks script.
- `Agent` — spawn the cold-read sub-agent.
- `Edit` (append-mode via full-content read) — write the review log entry.

## When NOT to use this skill

- Edits to `implementation-plan.md`, `implementation-backlog.md`, or any
  other workflow file. Those have their own gates (Phase 2 structural
  review).
- Edits to source code, tests, or docs outside `docs/adr/<plan-dir>/`.
- Pre-Phase-1 scratch notes that haven't been promoted to `design.md`
  yet — only the canonical paths above trigger the discipline.

## Failure modes and recovery

- **Script not found** at `.claude/scripts/design-mechanical-checks.py`:
  the project may not have the discipline wired up. Stop and ask the
  user; do not silently fall back to direct `Edit`.
- **Cold-read sub-agent times out or returns malformed output**: re-run
  once. If it fails again, treat the cold-read half as `INCONCLUSIVE`,
  log the result, and continue with mechanical findings only. Add a
  `should-fix` line to the review log noting the cold-read failure.
- **Budget exhausted**: do not loop further. The user is the gate when
  the action can't self-correct.

## Examples

**Example 1 — content edit, bounded scope.**
The user asks: "Add a bullet to the Edge cases / Gotchas list of the
'Three Load Variants' section noting that variant (1) is unsafe under
foo."

The skill:
1. Reads `design.md`, finds the section + sub-block.
2. Applies the `Edit` adding the bullet.
3. Runs mechanical checks, scope=`bounded`, changed-section="Three Load
   Variants".
4. Mechanical PASS (no rule violated by adding one bullet).
5. Spawns cold-read with `bounded` scope, the section name passed in.
6. Cold-read returns PASS, comprehension intact.
7. Appends Mutation N entry to review log.
8. Presents diff + log entry, action complete.

**Example 2 — section rename, whole-doc scope.**
The user asks: "Rename '## DPB (D33)' to '## Architectural redesign:
Dirty Page Bitset (D33)' to match the surrounding pattern."

The skill:
1. Applies the `Edit` to rename the heading.
2. Runs mechanical checks with `--plan-path` and `--backlog-path` so the
   `full-design-link-resolution` check fires on the rename.
3. Mechanical reports `blocker: full-design-link-resolution` for every
   `**Full design**: design.md §"DPB (D33)"` line that didn't get
   updated.
4. Iteration: applies `Edit` to each plan/backlog ref to use the new
   name.
5. Re-runs mechanical: PASS.
6. Spawns cold-read with `whole-doc` scope.
7. Cold-read PASS.
8. Logs and presents.

**Example 3 — budget exhausted.**
The user asks: "Replace the entire Workflow section with a single
sentence."

The skill:
1. Applies the `Write` (or large `Edit`).
2. Mechanical reports `blocker: per-section-shape:tldr` and
   `blocker: per-section-shape:references-footer` (Workflow becomes
   shape-exempt by name, but the rewrite leaves dangling references in
   downstream sections).
3. Iteration 1: tries to add the trivial shape, but content is too thin
   to support a meaningful TL;DR.
4. Iteration 2: cold-read flags "this design no longer makes sense"
   blockers.
5. Iteration 3: same.
6. Budget exhausted. Action presents the diff, warns the user, and
   stops. The user must decide: accept the malformed state and fix
   manually, or revert.

## Reference

- Rules: `.claude/workflow/design-document-rules.md` § Mutation discipline
- Cold-read prompt: `.claude/workflow/prompts/design-review.md`
- File layout: `.claude/workflow/conventions.md` §1.2
- Mechanical script: `.claude/scripts/design-mechanical-checks.py`
