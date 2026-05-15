---
name: migrate-workflow
description: "Migrate a branch's `docs/adr/<dir>/_workflow/**` artifacts to match workflow-format commits that landed on `develop` after the fork point. Replays each format-relevant commit's edits onto the branch's artifacts. Resumable across `/clear`. TRIGGER: branch has stale `_workflow/` after a workflow-format change on develop. SKIP: branches with no `_workflow/`."
argument-hint: "<migration-branch-name>"
user-invocable: true
---

Diff `.claude/workflow/**` and `.claude/skills/**` between the branch's fork point and current `develop` HEAD, then apply each format-relevant commit to the corresponding artifact files in the migration worktree. The skill runs from the `develop` worktree; the migration branch lives in a separate worktree.

The skill edits files in place in the migration worktree and leaves it dirty for the user to review and commit. No automatic commits.

## Inputs

`$ARGUMENTS` — the name of the migration branch. Required.

If `$ARGUMENTS` is empty, halt with:
> Pass the migration branch name as the only argument, e.g., `/migrate-workflow ytdb-614-property-map`.

## Step 0 — Create progress tracker

Before any other tool call, create one task per step below using `TaskCreate`. Mark each `in_progress` when starting, `completed` when done. This list is the checklist; do not skip entries.

1. Preflight: verify develop + clean tree
2. Resolve migration worktree path
3. Compute commit range + format-relevant commit list
4. Load or initialize progress file
5. Per-commit migration loop (one task per commit will be added at the start of Step 5, after Step 4 trims the resume queue)
6. Final summary

## Step 1 — Preflight

Run these checks. Halt on any failure.

```bash
# Must be on develop in the current worktree
test "$(git branch --show-current)" = "develop" \
  || { echo "ERROR: run from develop worktree"; exit 1; }

# Working tree must be clean
test -z "$(git status --porcelain)" \
  || { echo "ERROR: develop worktree has uncommitted changes"; exit 1; }

# Argument must look like a plain branch name, not a ref path or SHA
case "$ARGUMENTS" in
  refs/*|origin/*) echo "ERROR: pass a plain branch name, not a ref path"; exit 1;;
esac
echo "$ARGUMENTS" | grep -Eq '^[0-9a-f]{7,40}$' \
  && { echo "ERROR: pass a branch name, not a commit SHA"; exit 1; }

# Argument must be a real branch
git rev-parse --verify "refs/heads/$ARGUMENTS" \
  || { echo "ERROR: branch $ARGUMENTS does not exist"; exit 1; }
```

If develop is clean but not up to date with the remote, tell the user and ask whether to `git fetch && git pull --ff-only origin develop` first. Do not pull silently. Cover three failure modes:

- If `pull --ff-only` fails (local develop diverged from origin), halt and ask the user to resolve manually.
- If no `origin` remote is configured, proceed against the local develop tip and record that fact in the final summary.
- If the user declines the pull, also proceed against the local tip and record the choice.

## Step 2 — Resolve migration worktree path

The migration branch must be checked out in a separate worktree (never the develop worktree itself).

```bash
git worktree list --porcelain
```

Parse the porcelain output and find the entry whose `branch refs/heads/<X>` matches the argument. Capture its `worktree` path. Halt with a clear error if:

- No worktree is checked out on the branch. Instruct the user to run `git worktree add ../<dir> <branch>` and re-invoke.
- More than one worktree is checked out on the branch. Git is in an unusual state and the skill cannot decide which one to migrate; ask the user to consolidate first.
- The matching worktree is the current develop worktree (would mean the branch label moved). Should not happen given Step 1; defensive check.

After capturing the worktree path, verify it exists on disk (`test -d "<migration-worktree-path>"`); halt if the path is gone (stale porcelain entry).

The migration worktree must also be clean. Filter out the skill's own progress file (created in Step 4) so a resumed run isn't flagged as dirty:

```bash
git -C "<migration-worktree-path>" status --porcelain \
  | grep -v '^?? \.migration-progress$'
```

If the filtered output is non-empty, halt and ask the user to commit or stash there first. Migrations are non-trivial edits, and overlapping unrelated dirty work makes review impossible.

## Step 3 — Compute commit range

```bash
# Fork point: where the branch diverged from develop
FORK=$(git merge-base develop "$ARGUMENTS")

# Current develop tip
HEAD_DEVELOP=$(git rev-parse develop)

# Workflow-touching commits in chronological order (oldest first)
git log --reverse --format='%H %s' "$FORK..$HEAD_DEVELOP" \
  -- .claude/workflow .claude/skills
```

Record `$FORK` and `$HEAD_DEVELOP` — both are referenced in the progress file and the final summary.

If the list is empty, halt with:
> No workflow-touching commits between fork point `<short-FORK>` and develop HEAD `<short-HEAD>`. Nothing to migrate.

Otherwise, record the commit list as an in-conversation note. Do NOT call `TaskCreate` per commit here — per-commit tasks are added at the start of Step 5, after Step 4 has trimmed the resume queue, so the task list never drifts from the actual queue.

## Step 4 — Load or initialize progress file and identify target `_workflow/`

The progress file lives at the **migration worktree root**, not inside `_workflow/`:

```
<migration-worktree>/.migration-progress
```

Placing it at the worktree root keeps it outside any tracked subtree, so it does not interfere with the `_workflow/` review diff and `git status` only surfaces it as a single untracked sentinel (filtered out by Step 2's clean check).

Also identify the **target `_workflow/` directory** that Step 5.4 will edit. Enumerate `docs/adr/*/_workflow/` directories in the migration worktree:

```bash
ls -d "<migration-worktree>"/docs/adr/*/_workflow/ 2>/dev/null
```

Apply this ladder:

1. **Zero matches** — halt with "no `_workflow/` directory on `$ARGUMENTS`; nothing to migrate".
2. **Exactly one match** — use it. Do not require the parent directory name to match `$ARGUMENTS`; the branch name and the ADR directory name are not guaranteed to match.
3. **More than one match** — list them and ask the user which one to migrate. The skill targets exactly one plan at a time.

Progress file format:

```
# migrate-workflow progress
# fork=<FORK-sha> head=<HEAD_DEVELOP-sha-at-skill-start>
# renames:
#   <old-path> -> <new-path>
<sha>	<classification>	<subject>
<sha>	<classification>	<subject>
```

- Header lines: `# migrate-workflow progress`, `# fork=... head=...`, and a `# renames:` block. The renames block starts empty; rename-classified commits (Step 5.5) append one indented `#   <old> -> <new>` line per recorded rename. Step 5.4 consults this block when resolving paths for later commits.
- Body lines: one tab-separated record per migrated commit. Classification is one of `format`, `skill`, `rename`, `noop` (canonical short-names from Step 5.3). Step 6 reads these to produce per-classification counts.

File-existence handling:

- If the file does not exist, create it with the three header lines and an empty body. The file is a sentinel that the user removes after committing the migration. Do not modify `.gitignore`.
- If the file exists, read its header and apply these checks in order:
  - If `fork=<recorded-sha>` differs from the current `git merge-base develop $ARGUMENTS`, halt and ask the user to delete the stale progress file before re-running. The branch has been rebased since the migration started. Warn that the migration worktree may carry partial edits from the prior run; recommend `git -C "<migration-worktree>" stash` (or commit-then-revert) before deleting the progress file.
  - If `head=<recorded-sha>` is not reachable from the current `develop` (check via `git merge-base --is-ancestor <head-sha> develop`), halt and ask the user to fetch or update develop before resuming. The local `develop` was reset to an older commit than the one the prior run recorded.
  - If `fork=` matches and `head=` is older than the current `$HEAD_DEVELOP`, append any new commits to the queue (the develop tip moved during the migration).
- The commits already listed in the body are **done**. Skip them in Step 5.

## Step 5 — Per-commit migration loop

On first entry to Step 5:

1. Mark Step 0's umbrella task 5 ("Per-commit migration loop") as `in_progress`.
2. For each commit *remaining* in the trimmed queue (i.e., the Step-3 list minus the commits already recorded in `.migration-progress`), call `TaskCreate` with `Migrate commit <short-sha> <subject>`. These are the per-commit tasks consumed by 5.6.

Then iterate. For each commit in the queue, in order:

### 5.1 Context check (mandatory before starting the commit)

```bash
cat /tmp/claude-code-context-usage-$PPID.txt 2>/dev/null || echo "ctx: unknown"
```

If the level is `warning` (≥30%) or `critical` (≥40%):

1. Do NOT start the next commit.
2. Report progress to the user: which commits are done, which is next, where the progress file lives.
3. Instruct: "Context window is at <level>. Run `/clear` and re-invoke `/migrate-workflow <branch>` to resume — already-migrated commits will be skipped via the progress file."
4. End the session.

If `info` (`20% ≤ ctx < 30%`): continue, but delegate to sub-agents for any commit whose `git show --stat <sha>` shows either (a) more than 5 files touched under `.claude/workflow/` or `.claude/skills/`, or (b) total changed lines greater than 500. The trigger is derivable from `git show --stat` before the full diff is read into orchestrator context, so the delegation decision itself does not burn context.

**Sub-agent contracts.** The orchestrator must interpolate `$ARGUMENTS`, the absolute migration-worktree path, and per-commit values into the sub-agent prompt before launch; sub-agents inherit no conversation context.

- **`Explore`** — diff reading.
  - Input: absolute migration-worktree path, commit SHA, list of files to inspect.
  - Output: bullet list of `(file, intent-of-change)` pairs. No source quotes longer than 5 lines.
- **`general-purpose`** — batched edits.
  - Input: absolute paths inside the migration worktree, plus exact find→replace pairs or a section-insertion template.
  - Output: list of files edited and the line count changed per file.

This is a docs-only migration: sub-agents should use `git show`, `Read`, and `Grep`; mcp-steroid PSI is not required.

If `safe` (`ctx < 20%`): continue normally. The `ctx: unknown` fallback (file missing or `$PPID` resolution failed) is treated as `safe` — the file is best-effort, not load-bearing.

### 5.2 Read the commit

```bash
git show --stat <sha>
git show <sha> -- .claude/workflow .claude/skills
git log -1 --format='%B' <sha>
```

The commit message is load-bearing: it states the intent of the format change. Read it first, then the diff.

### 5.3 Classify the commit

**First, detect path renames as a side concern** (independent of the classification chosen below):

```bash
git show --diff-filter=R --name-status <sha> -- .claude/workflow .claude/skills
```

For each `R<percentage>\t<old>\t<new>` entry, plan to append one `#   <old> -> <new>` line under the `# renames:` header in Step 5.5. The renames block is populated regardless of which classification wins, so later commits can follow path mappings.

**Then classify the commit into one canonical short-name.** Apply the predicates in order; the first match wins:

1. **`format`** — the commit modifies `.claude/workflow/*.md` (rules, conventions, prompts), or makes substantive (non-typo, non-rename-only) edits to `.claude/skills/*/SKILL.md` bodies. Produces migration edits in Step 5.4.
2. **`skill`** — the commit adds or removes a `.claude/skills/*/SKILL.md` file with no `.claude/workflow/` changes. Promote to `format` iff the new or changed SKILL body contains either (a) a reference to `_workflow/`, `docs/adr/*/`, or a per-branch artifact filename (e.g., `implementation-plan.md`, `design-mutations.md`, `tracks/`); or (b) a `MANDATORY` / `required` / `must` statement creating a new mandatory artifact. Otherwise stay `skill`; no edits in Step 5.4.
3. **`rename`** — the commit's diff under `.claude/workflow/` and `.claude/skills/` consists exclusively of file renames (no content changes beyond the rename detection threshold). The rename block was populated above; no further edits.
4. **`noop`** — comment-only, whitespace, or single-line typo or wording fixes that do not rename sections, add or remove required fields, or change conventions. Skip 5.4.

Before invoking any edit tool, print one line to the user in the form `commit <short-sha>: <classification> — <reason>` (e.g., `commit 1de3cb0e: format — adds Phase-2 review section to implementation-plan.md`).

A fifth classification value, `manual-review-needed`, may be set in Step 5.5 only when the user invokes the "skip" escape from a Step 5.4 ambiguity halt. It is not selected here in 5.3.

### 5.4 Apply the migration in the migration worktree

For **Format change** commits, identify which files in the migration worktree's `_workflow/**` (or `.claude/**` if the branch carries local workflow overrides) match the format being changed. Use `Read` and `Bash` (`git -C "<migration-worktree>" ls-files docs/adr/*/_workflow/`) to find them.

When the develop-side diff references a path that may have been renamed earlier in the branch's history, consult the `# renames:` header block in `.migration-progress` and follow any `<old> -> <new>` mappings recorded by prior `rename`-classified commits.

Apply edits with the `Edit` tool against absolute paths inside the migration worktree. **Do not** edit files in the develop worktree; develop is the source of truth.

Common migration patterns to expect (not exhaustive; read the actual diff):

- A required section was added to `implementation-plan.md`: add the section with a `[ ]` placeholder to the branch's plan.
- A section was renamed (e.g., `## Plan Review` → `## Plan Review (Phase 2)`): rename it in every matching file under the branch's `_workflow/`.
- The step-file schema gained a new field (`## Description`, `## Base commit`): add it if missing.
- A glossary term was renamed: global find-replace in the branch's plan and step files, then re-read each touched file to confirm context fits.
- A commit-message convention changed: record the change for the user. Do NOT rewrite past commits.
- A new mandatory artifact appeared (e.g., `design-mutations.md`): create it empty with a header.

Halt the loop and ask the user only if one of these three concrete conditions holds:

- (a) The diff renames a section but the new section name already exists in the branch's artifact (cannot rename onto an existing name without losing content).
- (b) The diff deletes a section that contains user-authored content (non-template wording) in the branch's artifact (the deletion would discard data).
- (c) The diff adds a required field whose value cannot be inferred from existing content in the branch's artifact (no safe default).

If none of these holds, apply the edit mechanically and continue.

**Halt resume contract.** When the halt fires, include this warning in the question: *"If you `/clear` or interrupt before resolving, this commit will be replayed on resume. Run `git -C "<migration-worktree>" diff` first to detect duplicate edits."* (Same crash-window contract as Step 5.5.)

User outcomes:

1. **User supplies a translation** — apply the edit per their guidance. Proceed to 5.5 and record the commit with its original classification (typically `format`).
2. **User says "skip"** — apply no edit. Proceed to 5.5 and record the commit with classification `manual-review-needed`. Step 6 surfaces the count so the user knows which commits to revisit manually.

### 5.5 Update the progress file

Append one tab-separated line to the body of `.migration-progress` at the migration worktree root:

```
<sha>	<classification>	<subject>
```

`<classification>` is the canonical short-name from Step 5.3 (`format`, `skill`, `rename`, or `noop`), or the special value `manual-review-needed` when the user invoked "skip" from a Step 5.4 ambiguity halt.

For `rename`-classified commits, **also** insert one indented `#   <old-path> -> <new-path>` line under the `# renames:` header (one line per rename recorded by the commit) so later commits can follow the mapping. For `manual-review-needed`, skip the renames update and apply no edits; the body line alone is enough to keep the commit out of the replay queue on the next run.

Mechanism: read the file with `Read`, mutate the contents in memory (append the body line and, for renames, insert the header line(s) under `# renames:`), then write the full file back with `Write`. Do not use `Edit` here; the initial file may have no trailing newline, so the `old_string`-based path is unreliable. Do not stage or commit the file; it lives outside git history.

If the process is killed between applying edits in 5.4 and updating `.migration-progress` here, the next run will replay the same commit. After a crash, run `git -C "<migration-worktree>" diff` before resuming to detect duplicate edits.

### 5.6 Mark the per-commit task completed

`TaskUpdate` the matching task to `completed`. Move to the next commit.

## Step 6 — Final summary

When the queue is exhausted, mark Step 0's umbrella task 5 ("Per-commit migration loop") as `completed`, then output:

- Count of commits migrated, of each classification (`format`, `skill`, `rename`, `noop`, `manual-review-needed`). Compute by reading the body of `.migration-progress` and counting each value of column 2.
- If any commits were recorded as `manual-review-needed`, list their short-SHA and subject so the user knows which to revisit manually.
- Total files edited in the migration worktree, excluding the progress-file sentinel: `git -C "<migration-worktree>" status --porcelain | grep -v '^?? \.migration-progress$' | wc -l`.
- One-line next-step prompt: "Review the diff in `<migration-worktree>`, then commit when satisfied. Delete `.migration-progress` after the commit."

Then end the session.

## Notes

- The skill is read-only on develop and edit-only on the migration worktree; no automatic commits in either worktree.
- For large diffs, delegate to `Explore` to summarize commit diffs and `general-purpose` to apply repetitive edits across many files; pass absolute paths inside the migration worktree.
- This is a docs-only migration; mcp-steroid IDE control adds no value here.
- Re-invoking with the same `$ARGUMENTS` is safe: commits already in `.migration-progress` are skipped. To force a full re-run, delete the progress file.
