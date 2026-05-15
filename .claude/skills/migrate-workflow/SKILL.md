---
name: migrate-workflow
description: "Migrate an existing branch's per-branch workflow artifacts (docs/adr/<dir>/_workflow/**) to match workflow-format changes that have landed on develop since the branch was forked. Walks each workflow-touching commit between the branch's fork point and develop HEAD and applies the same format updates to the branch's artifacts. Run this skill from the develop worktree; the migration branch must be checked out in a separate worktree. Checks context usage after each commit and pauses with a resumable progress file when context reaches the warning level."
argument-hint: "<migration-branch-name>"
user-invocable: true
---

Migrate a branch's per-branch workflow artifacts so they match the current workflow format on `develop`. The skill is invoked from the `develop` worktree. It diffs `.claude/workflow/**` and `.claude/skills/**` between the branch's fork point and current `develop` HEAD, then applies each format-relevant commit to the corresponding artifact files in the migration worktree.

The skill edits files in place in the migration worktree and leaves it dirty for the user to review and commit. No automatic commits.

## Inputs

`$ARGUMENTS` — the name of the migration branch. Required.

If `$ARGUMENTS` is empty, halt with:
> Pass the migration branch name as the only argument, e.g., `/migrate-workflow ytdb-614-property-map`.

## Step 0 — TaskCreate progress tracker (MANDATORY)

Before any other action, create one task per step below using `TaskCreate`. Mark each `in_progress` when starting, `completed` when done. This list is your checklist; do not skip entries.

1. Preflight: verify develop + clean tree
2. Resolve migration worktree path
3. Compute commit range + format-relevant commit list
4. Load or initialize progress file
5. Per-commit migration loop (one task per commit will be added in Step 3)
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

# Argument must be a real branch
git rev-parse --verify "refs/heads/$ARGUMENTS" \
  || { echo "ERROR: branch $ARGUMENTS does not exist"; exit 1; }
```

If develop is clean but not up to date with the remote, tell the user and ask whether to `git fetch && git pull --ff-only origin develop` first. Do not pull silently.

## Step 2 — Resolve migration worktree path

The migration branch must be checked out in a separate worktree (never the develop worktree itself).

```bash
git worktree list --porcelain
```

Parse the porcelain output and find the entry whose `branch refs/heads/<X>` matches the argument. Capture its `worktree` path. Halt with a clear error if:

- No worktree is checked out on the branch — instruct the user to run `git worktree add ../<dir> <branch>` and re-invoke.
- The matching worktree is the current develop worktree (would mean the branch label moved). Should not happen given Step 1; defensive check.

The migration worktree must also be clean:

```bash
git -C "<migration-worktree-path>" status --porcelain
```

If dirty, halt and ask the user to commit or stash there first. Migrations are non-trivial edits — overlapping unrelated dirty work makes review impossible.

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

Otherwise, for each commit in the list, add a `TaskCreate` entry: `Migrate commit <short-sha> <subject>`. These are the per-commit migration tasks for Step 5.

## Step 4 — Load or initialize progress file

The progress file lives in the **migration worktree**, not develop:

```
<migration-worktree>/docs/adr/<branch-or-dir>/_workflow/.migration-progress
```

Resolve `<branch-or-dir>` to `$ARGUMENTS` unless a single `docs/adr/<X>/_workflow/` directory clearly belongs to the branch (e.g., named after the branch or its YTDB-NNN ticket). If multiple `_workflow/` dirs exist on the branch, ask the user which one to migrate; the skill targets exactly one plan at a time.

Progress file format (one line per migrated commit, oldest first):

```
# migrate-workflow progress
# fork=<FORK-sha> head=<HEAD_DEVELOP-sha-at-skill-start>
<sha>	<subject>
<sha>	<subject>
```

- If the file does not exist, create it with the header lines and an empty body. Do not add the migration worktree's `.migration-progress` to git; it is a sentinel that gets removed when the user is done (`.gitignore` is not required — the user manages it).
- If the file exists, read its header. If `fork=` matches but `head=` is older than the current `$HEAD_DEVELOP`, append any new commits to the queue (the develop tip moved during the migration). If `fork=` differs, halt and ask the user to delete the stale progress file before re-running — the branch has been rebased since the migration started.
- The commits already listed in the body are **done**. Skip them in Step 5.

## Step 5 — Per-commit migration loop

For each commit in the queue, in order:

### 5.1 Context check (mandatory before starting the commit)

```bash
cat /tmp/claude-code-context-usage-$PPID.txt 2>/dev/null || echo "ctx: unknown"
```

If the level is `warning` (≥30%) or `critical` (≥40%):

1. Do NOT start the next commit.
2. Report progress to the user: which commits are done, which is next, where the progress file lives.
3. Instruct: "Context window is at <level>. Run `/clear` and re-invoke `/migrate-workflow <branch>` to resume — already-migrated commits will be skipped via the progress file."
4. End the session.

If `info` (20–29%): continue, but use sub-agents for any commit whose diff exceeds ~500 lines or whose migration touches more than ~10 files. Delegate to `Explore` for diff reading and `general-purpose` for batched edits.

If `safe` (<20%) or unknown: continue normally.

### 5.2 Read the commit

```bash
git show --stat <sha>
git show <sha> -- .claude/workflow .claude/skills
git log -1 --format='%B' <sha>
```

The commit message is load-bearing — it states the intent of the format change. Read it first, then the diff.

### 5.3 Classify the commit

Decide one of:

- **Format change** — modifies workflow rules, conventions, plan-file structure, section names, required fields, or commit-message conventions. These produce migration edits.
- **Skill addition / removal** — adds or removes a skill in `.claude/skills/`. Usually no artifact migration needed; record and move on unless the skill change implies a structural shift in `_workflow/` (e.g., a new mandatory artifact like `design-mutations.md`).
- **Workflow doc rename / move** — produces no artifact migration on its own, but later steps may reference the new path.
- **Editorial / typo / clarification** — no migration. Record as `(no-op)` in the progress file and skip 5.4.

Write the classification verdict and a one-sentence reason as a tool-side note before doing any edits.

### 5.4 Apply the migration in the migration worktree

For **Format change** commits, identify which files in the migration worktree's `_workflow/**` (or `.claude/**` if the branch carries local workflow overrides) match the format being changed. Use `Read` and `Bash` (`git -C <migration-worktree> ls-files docs/adr/*/\_workflow/`) to find them.

Apply edits with the `Edit` tool against absolute paths inside the migration worktree. **Do not** edit files in the develop worktree — develop is the source of truth.

Common migration patterns to expect (not exhaustive — read the actual diff):

- A required section was added to `implementation-plan.md` — add the section with `[ ]` placeholder to the branch's plan.
- A section was renamed (e.g., `## Plan Review` → `## Plan Review (Phase 2)`) — rename it in every matching file under the branch's `_workflow/`.
- The step-file schema gained a new field (`## Description`, `## Base commit`) — add it if missing.
- A glossary term was renamed — global find-replace in the branch's plan and step files, with a re-read of each touched file to confirm context fits.
- A commit-message convention changed — record it for the user; do NOT rewrite past commits.
- A new mandatory artifact appeared (e.g., `design-mutations.md`) — create it empty with a header.

If the diff is too ambiguous to apply mechanically, halt the loop and ask the user how to translate that specific commit. Resume after they answer.

### 5.5 Update the progress file

Append one line to `.migration-progress`:

```
<sha>	<subject>
```

Use `Edit` with `old_string` = trailing newline of file, or read+rewrite via `Write`. Do not stage or commit it — the file lives outside git history.

### 5.6 Mark the per-commit task completed

`TaskUpdate` the matching task to `completed`. Move to the next commit.

## Step 6 — Final summary

When the queue is exhausted, output:

- Count of commits migrated, of each classification (`format`, `skill`, `rename`, `noop`).
- Total files edited in the migration worktree (from `git -C <migration-worktree> status --porcelain | wc -l`).
- One-line next-step prompt: "Review the diff in `<migration-worktree>`, then commit when satisfied. Delete `.migration-progress` after the commit."

Then end the session.

## Notes

- **No commits in either worktree.** The skill is read-only on develop and edit-only on the migration worktree.
- **Sub-agents for large diffs.** Use `Explore` to summarize commit diffs and `general-purpose` to apply repetitive edits across many files; pass absolute paths inside the migration worktree.
- **PSI is not required.** This is a docs-only migration; mcp-steroid IDE control adds no value.
- **Re-entrance.** Re-invoking with the same `$ARGUMENTS` is safe; commits listed in `.migration-progress` are skipped. To force a full re-run, delete the progress file.
