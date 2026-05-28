---
name: migrate-workflow
description: "Migrate a branch's docs/adr/<dir>/_workflow/** artifacts by replaying workflow-format commits from the per-artifact stamp base through HEAD. Resumable across /clear. TRIGGER: branch has stale _workflow/ after a workflow-format change on develop. SKIP: branches with no _workflow/."
argument-hint: "[branch-name]"
user-invocable: true
---

Auto-detection runs in `/create-plan` Step 1.5 and `/execute-tracks` startup via [`workflow-drift-check.md`](../../workflow/workflow-drift-check.md).

The skill runs inside the branch's own worktree. The branch is a self-contained capsule: workflow-format commits enter its view only when the user explicitly rebases or merges `develop`, so the migration's commit range is derived from per-artifact stamps and HEAD (see `conventions.md` §1.6), never from a develop-relative fork point. The skill applies each format-relevant commit's edits to the corresponding artifact files under the active plan's `_workflow/`.

The skill edits files in place and leaves the worktree dirty for the user to review and commit. No automatic commits.

## Inputs

`$ARGUMENTS` — optional. When supplied, it must equal the current
branch name (`git branch --show-current`); the migration always runs
in the current worktree on the current branch. Step 1 enforces the
equality and rejects mismatches.

## Step 0 — Create progress tracker

Before any other tool call, create one task per step below using `TaskCreate`. Mark each `in_progress` when starting, `completed` when done. This list is the checklist; do not skip entries.

1. Preflight: verify clean tree under the active plan's `_workflow/` and resolve the active plan dir
2. Bootstrap unstamped artifacts: prompt for a base SHA covering any unstamped `_workflow/**` artifact
3. Compute commit range + format-relevant commit list
4. Load or initialize progress file
5. Per-commit migration loop (one task per commit will be added at the start of Step 4, after Step 3 trims the resume queue)
6. Final stamp-to-HEAD batch
7. Final summary
8. Self-improvement reflection: invoke `.claude/workflow/self-improvement-reflection.md` with `session-type=migrate-workflow`

## Step 1 — Preflight

Run these checks in order. Halt on any failure.

**Argument check.** When `$ARGUMENTS` is non-empty, it must equal the
current branch name. The single equality covers every legacy reject
case — `refs/heads/...`, `origin/...`, a 7- to 40-character hex SHA,
or a non-existent branch name all fail this check, since none of them
can equal `git branch --show-current`'s plain-branch-name output:

```bash
if [ -n "$ARGUMENTS" ] \
   && [ "$ARGUMENTS" != "$(git branch --show-current)" ]; then
  echo "ERROR: \$ARGUMENTS ($ARGUMENTS) does not match the current branch ($(git branch --show-current))"
  exit 1
fi
```

**Active-plan-dir resolution.** Enumerate `docs/adr/*/_workflow/`
directories in the current worktree:

```bash
ls -d docs/adr/*/_workflow/ 2>/dev/null
```

Apply this ladder and capture the result as `$PLAN_DIR` (the parent
directory, without the trailing `/_workflow/`):

1. **Zero matches** — halt with "no `_workflow/` directory on the
   current branch; nothing to migrate".
2. **Exactly one match** — use it. The parent directory name need
   not match the current branch; branch names and ADR directory
   names are not guaranteed to match.
3. **More than one match** — list them and ask the user which one to
   migrate. One plan at a time.

Subsequent steps reference `$PLAN_DIR` (e.g.,
`$PLAN_DIR/_workflow/implementation-plan.md`) instead of re-running
the ladder.

**Narrow-scope dirty check.** Refuse to start when any tracked file
under the active plan's §1.6(h) artifact paths has uncommitted
changes (working tree or index), or when any untracked file lives
there. The scope covers the implementation plan, the design files,
and every track file under `$PLAN_DIR/_workflow/plan/`. The staged
subtree at `$PLAN_DIR/_workflow/staged-workflow/` is deliberately
outside the check: on workflow-modifying branches, in-flight
workflow-document changes accumulate under that subtree during
execution (Phase 4 then promotes them to the live paths in one
commit), and a whole-subtree check would refuse every such session
with staged content present — the dogfood path this skill accepts.

```bash
DIRTY=$(git status --porcelain -- \
  "$PLAN_DIR/_workflow/implementation-plan.md" \
  "$PLAN_DIR/_workflow/design.md" \
  "$PLAN_DIR/_workflow/design-mechanics.md" \
  "$PLAN_DIR/_workflow/plan/" \
  | grep -v '^?? \.migration-progress$')

if [ -n "$DIRTY" ]; then
  echo "ERROR: uncommitted or untracked changes under the active plan's _workflow/ artifacts:"
  printf '%s\n' "$DIRTY"
  echo "Commit, stash, or remove these files before re-running /migrate-workflow."
  exit 1
fi
```

The `.migration-progress` sentinel (Step 3) is the only carve-out;
it is allowed to be untracked or modified mid-session. Any tracked
file under the narrow scope showing a non-`?` status (modified,
added, deleted, renamed, etc.) or any untracked file showing `??`
halts the session with the offending paths printed; the user must
commit, stash, or remove them before re-invoking.

The check is deliberately narrow. The dropped develop-side
whole-tree check incidentally guarded the live skill file
(`.claude/skills/migrate-workflow/SKILL.md`); the staging convention
for workflow-modifying branches (in-flight rewrites land under
`staged-workflow/` rather than the live paths) now keeps the live
skill at develop's state throughout execution, so the migration
always reads the develop-state skill regardless of staged rewrites —
the side-effect protection is no longer needed.

## Step 2.0 — Bootstrap unstamped artifacts

Walk the active plan's `_workflow/**` per `conventions.md` §1.6(h) and
classify each artifact as stamped (line-1 stamp parses) or unstamped
(no stamp). The classification feeds both this step's bootstrap prompt
and Step 2's range derivation.

```bash
STAMPED_SHAS=""
UNSTAMPED_FILES=""
# design-mechanics.md is optional; absent until the length trigger fires.
# The ls 2>/dev/null swallows the stderr for any artifact kind that is not
# yet present on disk, so missing files do not abort the walk.
for f in $(ls "$PLAN_DIR/_workflow/implementation-plan.md" \
              "$PLAN_DIR/_workflow/design.md" \
              "$PLAN_DIR/_workflow/design-mechanics.md" \
              "$PLAN_DIR/_workflow/plan/"track-*.md 2>/dev/null); do
    SHA="$(head -1 "$f" | grep -oE 'workflow-sha: [0-9a-f]{40}' | grep -oE '[0-9a-f]{40}$')"
    if [ -n "$SHA" ]; then
        STAMPED_SHAS="$STAMPED_SHAS $SHA"
    else
        UNSTAMPED_FILES="$UNSTAMPED_FILES $f"
    fi
done
```

The walk pattern matches `conventions.md` §1.6(h); `$PLAN_DIR` is
documentary here (resolved in Step 1), versus the literal
placeholder `docs/adr/<resolved-dir-name>` in §1.6(h)'s canonical
form. Step 2 repeats the walk with a paired `(file, sha)` array so
merge-base failures can name artifact paths; this step only needs
the classification, so the simpler form lands here.

**When `$UNSTAMPED_FILES` is empty.** No prompt fires. Continue to
Step 2.

**When `$UNSTAMPED_FILES` is non-empty.** Print the list of unstamped
artifacts and ask the user once for a base SHA covering the set. The
prompt must include the rationale below so the user understands why
the migration cannot guess:

> The artifacts listed above carry no `<!-- workflow-sha: ... -->`
> stamp on line 1, so the migration has no anchor for "what workflow
> version was this artifact last synced to." An auto-computed default
> (the branch's fork-point with `develop`, `git merge-base
> origin/develop HEAD`, HEAD itself, or any other variant) would shift
> forward whenever the branch is rebased onto a newer `develop` and
> silently mark unstamped artifacts as already-current, skipping the
> migration. Provide the SHA of the workflow-format commit these
> artifacts were last synced to; a short prefix or the full 40-char
> form are both accepted.

Validate the response with the two-subcommand check from
`conventions.md` §1.6(d). The `^{commit}` peel rejects tag and ref
names; only commit SHAs pass. The reachability check enforces the
range's upper-bound rule from §1.6(c) (HEAD is the comparison anchor,
so the bootstrap SHA must be reachable from HEAD).

The validation block below runs inside a retry loop bounded at three
attempts. On either subcommand failure, print the cause, increment
the attempt counter, and re-prompt the user with the same artifact
list. On the third rejection halt the session with `ERROR: three
rejected attempts; bootstrap aborted` and exit with no edits applied;
do not fall through to Step 2:

```bash
if ! CANON_SHA="$(git rev-parse --verify "$SHA^{commit}" 2>&1)"; then
    echo "ERROR: $SHA is not a valid commit SHA: $CANON_SHA"
    # increment attempt counter, re-enter the prompt; see retry policy below
elif ! git merge-base --is-ancestor "$CANON_SHA" HEAD 2>/dev/null; then
    echo "ERROR: $SHA ($CANON_SHA) is not reachable from HEAD."
    # increment attempt counter, re-enter the prompt; see retry policy below
else
    USER_BOOTSTRAP_SHA="$CANON_SHA"
fi
```

Store the **canonical 40-char `rev-parse` stdout** (`$CANON_SHA`), not
the user's raw input, as `$USER_BOOTSTRAP_SHA`. The canonicalization
is mandatory: Step 4's per-commit lockstep advance writes the value
into artifact stamps, and the stamp regex `[0-9a-f]{40}` in §1.6(a1)
rejects shorter values on subsequent parse, so a short-prefix stamp
would fail every drift-check re-read.

**Retry policy (bounded, session-bound counter).** On validation
failure (either subcommand returns non-zero), print the failure cause
and re-prompt the user with the same artifact list. Cap the retry
count at three attempts per `conventions.md` §1.6(d); after the third
rejection print a one-line diagnostic naming the current HEAD SHA
(`echo "Reachability is checked against git rev-parse HEAD =
$(git rev-parse HEAD); if the SHA you supplied is on a different
branch, check out the migration branch first."`), then halt the
session with `ERROR: three rejected attempts; bootstrap aborted` and
exit with no edits applied. The user `/clear`s the session to abandon
the migration.

The counter is **session-bound**: it lives in the conversation, not on
disk. The orchestrator holds the counter across both Step 2.0's
initial prompt and Step 2's recovery re-prompt; the bash blocks above
run once per attempt, and the orchestrator decides whether to
re-invoke them. A `/clear` between attempts resets it. Step 3's
`.migration-progress` file does not exist yet at Step 2.0 time, so no
persistent counter is available; the bound is by design soft against
`/clear`-based abandonment and re-entry. This matches §1.6(d)'s
explicit "`/clear`s the session to abandon the migration" exit shape.

The prompt records the user's best guess; the per-commit replay
loop's halt-on-ambiguity in Step 4 is a partial safety net, not a
guarantee. A too-old SHA silently bloats the replay range; a too-new
SHA silently skips needed migrations. Both failure modes are
documented in `conventions.md` §1.6(d) so debug sessions have a
starting point.

Step 2 consumes `$USER_BOOTSTRAP_SHA` (when set) alongside
`$STAMPED_SHAS` in its fold input set. When Step 2.0 did not fire
(because `$UNSTAMPED_FILES` was empty), `$USER_BOOTSTRAP_SHA` stays
unset and the fold runs over `$STAMPED_SHAS` alone.

## Step 2 — Compute commit range

The range derivation runs in two phases. **Phase 1** walks the active
plan's `_workflow/**` artifacts and classifies each as stamped or
unstamped (byte-copied from `conventions.md` §1.6(h) so the migration
and the drift check agree on what "drift" means). **Phase 2** folds
the stamp set pairwise through `git merge-base` to derive `BASE_SHA`,
then runs `git log $BASE_SHA..HEAD` against workflow paths.

Step 2.0 has already walked the same artifacts and set
`$STAMPED_SHAS` / `$UNSTAMPED_FILES` / `$USER_BOOTSTRAP_SHA`. This step
repeats the walk because the fold needs a **paired `(file, sha)`
array** so merge-base failures can name artifact paths in the recovery
re-prompt rather than bare SHAs; Step 2.0's simpler-form walk did not
carry that pairing.

The Phase 1 walk block, byte-for-byte from `conventions.md` §1.6(h),
extended with a paired path-and-SHA array (`STAMPED_PAIRS` — one
`<path>=<sha>` entry per stamped artifact) so the Phase 2 recovery
path can name failing artifact paths:

```bash
PLAN_DIR="docs/adr/<resolved-dir-name>"
STAMPED_SHAS=""
UNSTAMPED_FILES=""
STAMPED_PAIRS=""
# design-mechanics.md is optional; absent until the length trigger fires.
# The ls 2>/dev/null swallows the stderr for any artifact kind that is not
# yet present on disk, so missing files do not abort the walk.
for f in $(ls "$PLAN_DIR/_workflow/implementation-plan.md" \
              "$PLAN_DIR/_workflow/design.md" \
              "$PLAN_DIR/_workflow/design-mechanics.md" \
              "$PLAN_DIR/_workflow/plan/"track-*.md 2>/dev/null); do
    SHA="$(head -1 "$f" | grep -oE 'workflow-sha: [0-9a-f]{40}' | grep -oE '[0-9a-f]{40}$')"
    if [ -n "$SHA" ]; then
        STAMPED_SHAS="$STAMPED_SHAS $SHA"
        STAMPED_PAIRS="$STAMPED_PAIRS $f=$SHA"
    else
        UNSTAMPED_FILES="$UNSTAMPED_FILES $f"
    fi
done
```

The `<resolved-dir-name>` placeholder is the active plan dir captured
as `$PLAN_DIR` in Step 1 per `conventions.md` §1.6(g); substitute it
literally at invocation time. The walk silently skips artifacts not
yet on disk (`design-mechanics.md` before the length trigger, any
`track-*.md` not yet created), so absent optional artifacts contribute
neither a stamp nor an unstamped flag. Apart from the `STAMPED_PAIRS`
extension, the loop body is text-identical to the drift check's copy
in `workflow-drift-check.md`; a future edit to the §1.6(h) block
applies to both files in lockstep.

**Phase 1 halt — no stampable artifacts.** When both `$STAMPED_SHAS`
and `$UNSTAMPED_FILES` are empty after the walk, the active plan has
no stampable artifacts on disk (a freshly-created `_workflow/` dir
holding only a transient `handoff-*.md`, for example). Per
`conventions.md` §1.6(h)'s both-arrays-empty rule, halt the migration
with `no artifacts to migrate` and exit without entering Phase 2. This
case is distinct from the all-stamped case (where Phase 2 runs the
fold) and from the partially-stamped case (where Step 2.0 already
prompted for `$USER_BOOTSTRAP_SHA`).

```bash
if [ -z "$STAMPED_SHAS" ] && [ -z "$UNSTAMPED_FILES" ]; then
    echo "ERROR: no artifacts to migrate (the active plan's _workflow/ has no stampable files)."
    exit 1
fi
```

Phase 2 — fold the stamp set pairwise through `git merge-base` to
derive `BASE_SHA`, the oldest stamp reachable from HEAD per
`conventions.md` §1.6(c). The fold input set is `$STAMPED_SHAS` plus
`$USER_BOOTSTRAP_SHA` when Step 2.0 fired; when Step 2.0 was a no-op
(every artifact stamped), `$USER_BOOTSTRAP_SHA` stays unset and the
fold runs over `$STAMPED_SHAS` alone:

```bash
FOLD_INPUT="$STAMPED_SHAS"
if [ -n "$USER_BOOTSTRAP_SHA" ]; then
    FOLD_INPUT="$FOLD_INPUT $USER_BOOTSTRAP_SHA"
fi

# Continue-and-collect fold: every failing pair contributes to
# MERGE_BASE_FAILED. The fold continues past failures rather than
# breaking on the first one so a single recovery re-prompt covers the
# full failing set, matching the batch semantics in conventions.md
# §1.6(c).
BASE_SHA=""
MERGE_BASE_FAILED=""
for SHA in $FOLD_INPUT; do
    if [ -z "$BASE_SHA" ]; then
        BASE_SHA="$SHA"; continue
    fi
    NEW_BASE="$(git merge-base "$BASE_SHA" "$SHA" 2>/dev/null)" || NEW_BASE=""
    if [ -z "$NEW_BASE" ]; then
        # merge-base failure: a stamp pointing at a git-gc-pruned commit,
        # or two stamps with no reachable common ancestor in the local
        # repo. Collect both SHAs into MERGE_BASE_FAILED; the recovery
        # path below routes their owning artifacts back through the
        # bootstrap prompt per conventions.md §1.6(c).
        MERGE_BASE_FAILED="$MERGE_BASE_FAILED $BASE_SHA $SHA"
        # Reset BASE_SHA so the fold continues from the next stamp
        # without propagating the failed value into subsequent
        # merge-base calls.
        BASE_SHA=""
        continue
    fi
    BASE_SHA="$NEW_BASE"
done
```

The drift check uses `break` on its first merge-base failure because
the gate has no recovery prompt to amortise. The migration's
bootstrap re-prompt is user-interactive, so the fold continues past
each failure to collect every failing SHA into `MERGE_BASE_FAILED`
before exiting the loop. A single recovery re-prompt then covers the
combined unstamped + merge-base-failed set in one user interaction;
the break-shape would re-prompt the user once per failing pair
encountered serially.

**Merge-base failure recovery.** When `$MERGE_BASE_FAILED` is
non-empty, resolve each failing SHA to the artifact path that emitted
it via the `$STAMPED_PAIRS` table, then route the **combined**
unstamped + merge-base-failed file set back through Step 2.0's
bootstrap prompt per `conventions.md` §1.6(c). The user supplies one
new SHA covering the combined set; the validated value **replaces**
the prior `$USER_BOOTSTRAP_SHA` (the variable stays singular, matching
`conventions.md` §1.6(d)'s one-SHA-per-prompt shape).

Drop the merge-base-failed SHAs from `STAMPED_SHAS` before restarting
the fold. Their owning artifacts are reclassified as unstamped for
the rest of the session (matching the "treat as unstamped" framing in
`conventions.md` §1.6(c)), so the user's fresh `$USER_BOOTSTRAP_SHA`
serves as the bootstrap anchor for both the originally-unstamped set
and the dropped stamps' artifacts. Without this drop, the restarted
fold would re-run `git merge-base` over the same failing pair and
fail again at the same point regardless of how valid the user's
bootstrap SHA is, exhausting the 3-attempt cap on input the user
cannot fix. After the re-prompt, restart this step's Phase 2 fold
from the top with the pruned `$STAMPED_SHAS` plus the fresh
`$USER_BOOTSTRAP_SHA` in `FOLD_INPUT`:

```bash
if [ -n "$MERGE_BASE_FAILED" ]; then
    # Resolve failing SHAs to artifact paths via STAMPED_PAIRS.
    FAILED_FILES=""
    for FAILED_SHA in $MERGE_BASE_FAILED; do
        for PAIR in $STAMPED_PAIRS; do
            case "$PAIR" in
                *"=$FAILED_SHA")
                    FAILED_FILES="$FAILED_FILES ${PAIR%=*}"
                    ;;
            esac
        done
    done
    # Drop the merge-base-failed SHAs from STAMPED_SHAS so the
    # restarted fold does not re-run git merge-base over the same
    # failing pair. The dropped stamps' artifacts are now covered by
    # the upcoming $USER_BOOTSTRAP_SHA.
    PRUNED_STAMPED_SHAS=""
    for SHA in $STAMPED_SHAS; do
        DROP=0
        for FAILED_SHA in $MERGE_BASE_FAILED; do
            [ "$SHA" = "$FAILED_SHA" ] && DROP=1 && break
        done
        [ "$DROP" = "0" ] && PRUNED_STAMPED_SHAS="$PRUNED_STAMPED_SHAS $SHA"
    done
    STAMPED_SHAS="$PRUNED_STAMPED_SHAS"
    # Combine with any pre-existing unstamped files and re-enter the
    # bootstrap prompt. The 3-attempt cap in conventions.md §1.6(d) is
    # SHARED with Step 2.0's initial prompt; the counter is session-
    # wide, not per-prompt. A user who already burned 2 attempts in
    # Step 2.0's initial run has 1 attempt left here.
    UNSTAMPED_FILES="$UNSTAMPED_FILES$FAILED_FILES"
    # After the append, $UNSTAMPED_FILES is guaranteed non-empty
    # because the recovery only fires when $MERGE_BASE_FAILED is
    # non-empty; Step 2.0's prompt branch fires unconditionally on
    # re-entry.
    # Re-enter Step 2.0's prompt with the combined file set; on
    # success, restart Phase 2 with the pruned STAMPED_SHAS plus the
    # fresh $USER_BOOTSTRAP_SHA. On three rejected attempts (shared
    # counter), halt the session.
fi
```

The re-prompt's user-facing text uses the combined file list. The
3-attempt counter is **shared** across Step 2.0's initial prompt and
this recovery prompt — the counter is session-wide so a user cannot
chain failures across both prompts to escape the bound. On three
rejections (across any combination of initial + recovery attempts)
the migration halts with `ERROR: three rejected attempts; bootstrap
aborted` and exits with no edits applied.

**Range computation and the empty-log halt.** Once Phase 2 produces
a non-empty `BASE_SHA` with no outstanding merge-base failures, run
the path-scoped `git log` over the range `BASE_SHA..HEAD`:

```bash
# Workflow-touching commits in chronological order (oldest first)
git log --reverse --format='%H %s' "$BASE_SHA..HEAD" \
  -- .claude/workflow/ .claude/skills/
```

If the list is empty, halt with:
> No workflow-touching commits between stamp base `<short-BASE_SHA>` and HEAD `<short-HEAD>`. Nothing to migrate.

The empty-log halt fires on a fully-stamped branch whose stamps
already point at HEAD (or at every workflow-touching commit between
them and HEAD): the fold collapses to a stamp that is itself the
newest workflow-format commit reachable from HEAD, so the range is
empty and the migration has nothing to replay.

Otherwise, record the commit list as an in-conversation note, and
record `$BASE_SHA`. Both values are referenced in the progress file
and the final summary. Do NOT call `TaskCreate` per commit here; per-commit tasks
are added at the start of Step 4, after Step 3 has trimmed the resume
queue, so the task list never drifts from the actual queue.

## Step 3 — Load or initialize progress file

The progress file lives at the worktree root, not inside `_workflow/`:

```
.migration-progress
```

Placing it at the worktree root keeps it outside any tracked subtree, so it does not interfere with the `_workflow/` review diff and `git status` only surfaces it as a single untracked sentinel (the `.migration-progress` carve-out in Step 1's narrow-scope clean check filters it out).

Step 1 has already resolved the active plan directory as `$PLAN_DIR`; Step 4.4 edits files under `$PLAN_DIR/_workflow/`. No second enumeration is needed.

Progress file format:

```
# migrate-workflow progress
# range_start=<BASE_SHA> range_end=<HEAD-sha-at-skill-start>
# renames:
#   <old-path> -> <new-path>
<sha>	<classification>	<subject>
<sha>	<classification>	<subject>
```

- Header lines: `# migrate-workflow progress`, `# range_start=... range_end=...`, and a `# renames:` block. `range_start` is the `BASE_SHA` produced by Step 2's stamp-walking fold (the oldest ancestor reachable from every fold input); `range_end` is `git rev-parse HEAD` captured at skill start — together they pin the commit range Step 4 replays. The renames block starts empty; rename-classified commits (Step 4.6) append one indented `#   <old> -> <new>` line per recorded rename. Step 4.4 consults this block when resolving paths for later commits.
- Body lines: one tab-separated record per migrated commit. Classification is one of `format`, `skill`, `rename`, `noop` (canonical short-names from Step 4.3). Step 5 reads these to produce per-classification counts.

File-existence handling:

- If the file does not exist, create it with the three header lines and an empty body. The file is a sentinel that the user removes after committing the migration. Do not modify `.gitignore`.
- If the file exists, read its header and apply this check:
  - If `range_start=<recorded-sha>` differs from the freshly-computed `BASE_SHA` (Step 2's fold output for this session), halt and ask the user to delete the stale progress file before re-running. The branch's stamp set has shifted since the prior session (new stamps landed, an earlier stamp was rewritten, or the branch was rebased), and the recorded body refers to a range that no longer matches the current artifact state. Warn that the worktree may carry partial edits from the prior run; recommend `git stash` (or commit-then-revert) before deleting the progress file.
  - If `range_start` matches but the recorded `range_end` differs from `git rev-parse HEAD` at this session's start, the branch has advanced since the prior session. Step 2's freshly-computed `git log $BASE_SHA..HEAD` already covers any commits added after the prior run; Step 4's queue is the new log minus the commits already recorded in the body. Update the progress file's `range_end` field to current HEAD before entering Step 4.
- The commits already listed in the body are **done**. Skip them in Step 4.

## Step 4 — Per-commit migration loop

On first entry to Step 4:

1. Mark Step 0's umbrella task 5 ("Per-commit migration loop") as `in_progress`.
2. For each commit *remaining* in the trimmed queue (i.e., the Step-2 list minus the commits already recorded in `.migration-progress`), call `TaskCreate` with `Migrate commit <short-sha> <subject>`. These are the per-commit tasks consumed by 4.7.

Then iterate. For each commit in the queue, in order:

### 4.1 Context check (mandatory before starting the commit)

```bash
cat /tmp/claude-code-context-usage-$PPID.txt 2>/dev/null || echo "ctx: unknown"
```

If the level is `warning` (≥30%) or `critical` (≥40%):

1. Do NOT start the next commit.
2. Report progress to the user: which commits are done, which is next, where the progress file lives.
3. Instruct: "Context window is at <level>. Run `/clear` and re-invoke `/migrate-workflow` to resume — already-migrated commits will be skipped via the progress file. If you `/clear` between 4.4 and 4.5, the next session re-replays this commit's edits over already-edited content; run `git diff` before re-invoking."
4. End the session.

Reflection at Step 6 is deliberately skipped on this early-exit path to protect the already-tight context budget; the next session's reflection at Step 6 (once the migration completes successfully) reports this halt as friction.

If `info` (`20% ≤ ctx < 30%`): continue, but delegate to sub-agents for any commit whose `git show --stat <sha>` shows either (a) more than 5 files touched under `.claude/workflow/` or `.claude/skills/`, or (b) total changed lines greater than 500. The trigger is derivable from `git show --stat` before the full diff is read into orchestrator context, so the delegation decision itself does not burn context.

**Sub-agent contracts.** The orchestrator must interpolate `$ARGUMENTS` and per-commit values into the sub-agent prompt before launch; sub-agents inherit no conversation context and operate against the current worktree.

- **`Explore`** — diff reading.
  - Input: commit SHA and list of files to inspect under the active plan's `_workflow/`.
  - Output: bullet list of `(file, intent-of-change)` pairs. No source quotes longer than 5 lines.
- **`general-purpose`** — batched edits.
  - Input: absolute paths under the active plan's `_workflow/`, plus exact find→replace pairs or a section-insertion template.
  - Output: list of files edited and the line count changed per file.

This is a docs-only migration: sub-agents should use `git show`, `Read`, and `Grep`; mcp-steroid PSI is not required.

If `safe` (`ctx < 20%`): continue normally. The `ctx: unknown` fallback (file missing or `$PPID` resolution failed) is treated as `safe` — the file is best-effort, not load-bearing.

### 4.2 Read the commit

```bash
git show --stat <sha>
git show <sha> -- .claude/workflow .claude/skills
git log -1 --format='%B' <sha>
```

The commit message is load-bearing: it states the intent of the format change. Read it first, then the diff.

### 4.3 Classify the commit

**First, detect path renames as a side concern** (independent of the classification chosen below):

```bash
git show --diff-filter=R --name-status <sha> -- .claude/workflow .claude/skills
```

For each `R<percentage>\t<old>\t<new>` entry, plan to append one `#   <old> -> <new>` line under the `# renames:` header in Step 4.6. The renames block is populated regardless of which classification wins, so later commits can follow path mappings.

**Then classify the commit into one canonical short-name.** Apply the predicates in order; the first match wins:

1. **`format`** — the commit modifies `.claude/workflow/*.md` (rules, conventions, prompts), or makes substantive (non-typo, non-rename-only) edits to `.claude/skills/*/SKILL.md` bodies. Produces migration edits in Step 4.4.
2. **`skill`** — the commit adds or removes a `.claude/skills/*/SKILL.md` file with no `.claude/workflow/` changes. Promote to `format` iff the new or changed SKILL body contains either (a) a reference to `_workflow/`, `docs/adr/*/`, or a per-branch artifact filename (e.g., `implementation-plan.md`, `design-mutations.md`, `tracks/`); or (b) a `MANDATORY` / `required` / `must` statement creating a new mandatory artifact. Otherwise stay `skill`; no edits in Step 4.4.
3. **`rename`** — the commit's diff under `.claude/workflow/` and `.claude/skills/` consists exclusively of file renames (no content changes beyond the rename detection threshold). The rename block was populated above; no further edits.
4. **`noop`** — comment-only, whitespace, or single-line typo or wording fixes that do not rename sections, add or remove required fields, or change conventions. Skip 4.4.

Before invoking any edit tool, print one line to the user in the form `commit <short-sha>: <classification> — <reason>` (e.g., `commit 1de3cb0e: format — adds Phase-2 review section to implementation-plan.md`).

**Stamp-format-change halt.** If the commit's diff modifies `conventions.md` §1.6 stamp-format definition (the `workflow-sha:` regex shape or the line-1 stamp position rule), halt and route the commit to manual review — the in-place migration cannot self-bootstrap a stamp-format change because the writer's `old_string`-match contract assumes the prior format's text shape. Record the commit at 4.6 with classification `manual-review-needed` and skip the per-commit edits in 4.4.

A fifth classification value, `manual-review-needed`, may be set in Step 4.6 only when the user invokes the "skip" escape from a Step 4.4 ambiguity halt, or when 4.3's stamp-format-change halt fires above. It is not selected here in 4.3 as a primary classification.

### 4.4 Apply the migration

For **Format change** commits, identify which files under the active plan's `_workflow/**` (or `.claude/**` if the branch carries local workflow overrides) match the format being changed. Use `Read` and `Bash` (`git ls-files "$PLAN_DIR/_workflow/"`) to find them.

When the diff references a path that may have been renamed earlier in the branch's history, consult the `# renames:` header block in `.migration-progress` and follow any `<old> -> <new>` mappings recorded by prior `rename`-classified commits.

Apply edits with the `Edit` tool against absolute paths under the active plan's `_workflow/`. The migration mutates the current worktree in place; there is no separate source-of-truth worktree.

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

**Halt resume contract.** When the halt fires, include this warning in the question: *"If you `/clear` or interrupt before resolving, this commit will be replayed on resume. Run `git diff` first to detect duplicate edits."* (Same crash-window contract as Step 4.6.)

User outcomes:

1. **User supplies a translation** — apply the edit per their guidance. Proceed to 4.6 and record the commit with its original classification (typically `format`).
2. **User says "skip"** — apply no edit. Proceed to 4.6 and record the commit with classification `manual-review-needed`. Step 5 surfaces the count so the user knows which commits to revisit manually.

### 4.5 Advance stamps in lockstep

After sub-step 4.4's edits land and before sub-step 4.6 records the commit in `.migration-progress`, rewrite line 1 of every artifact enumerated by `conventions.md` §1.6(h)'s canonical walk under the active plan's `_workflow/` to `<!-- workflow-sha: <current-commit-sha> -->`. The order is load-bearing: edits → advance → progress sentinel, never another order. Reversing edits and advance, or deferring the advance past the progress sentinel, breaks crash resume — see design.md §"Per-commit replay and lockstep advance" for the case analysis. The design.md sequence diagram in §"Migration replay loop" is the authoritative ordering; §"Per-commit replay and lockstep advance" carries the crash-window analysis.

For each stamped artifact, use the `Edit` tool against line 1 with the **prior stamp** as `old_string` and the new `<!-- workflow-sha: <current-commit-sha> -->` as `new_string`. For the K-th commit in the queue (K ≥ 2), the **prior stamp** is the SHA 4.5 wrote on its previous iteration. Re-read `head -1 "$f"` at the start of each 4.5 fire to capture the current `PREV_SHA`; do not reuse the session-start stamp value. For K=1, `PREV_SHA` is the artifact's line-1 stamp at session start (Step 2's stamped set), or the bootstrap SHA after 4.5's unstamped-artifact branch ran for the artifact. The exact-string match makes the writer idempotent on equal SHAs (re-running on an artifact already at this commit's SHA: the prior stamp text is no longer present, so `Edit` raises an `old_string` mismatch — treat this as a benign no-op, do not retry and do not halt, because the post-condition `<!-- workflow-sha: <current-commit-sha> -->` on line 1 already holds) and portable across platforms (no `sed -i` BSD/GNU dialect difference).

Artifacts that were not stamped at session start gain their first stamp here as a side effect. At 4.5 invocation time, an artifact takes this bootstrap branch iff `head -1 "$f"` does not start with `<!-- workflow-sha:`; the `$UNSTAMPED_FILES` set Step 2.0 computed is informational (it drives Step 5's count summary), so do not rely on its membership at 4.5 — re-check per artifact. For files on the bootstrap branch, the `Edit` writer has no prior stamp to match. Use `Read` to capture the **full file content**, prepend a new line `<!-- workflow-sha: <current-commit-sha> -->\n` above the existing line 1, then `Write` the entire concatenated content back to the same path. After the first successful per-commit replay these artifacts join the stamped set and the standard `Edit` path applies on subsequent commits. A crash between `Read` and `Write` on this path leaves the artifact unstamped (the on-disk content is identical to its pre-4.5 state). The next session re-enters Step 2.0 with the same `$UNSTAMPED_FILES` set, re-prompts for the bootstrap SHA, and 4.5's bootstrap branch fires again on this artifact for the same commit — idempotent under the user supplying the same SHA.

This sub-step is the crash-resume marker: the next session reads any stamp, finds the last successfully-replayed SHA, and resumes at the next commit. See design.md §"Migration replay loop" for the sequence diagram and §"Per-commit replay and lockstep advance" for the crash-window analysis.

### 4.6 Update the progress file

Append one tab-separated line to the body of `.migration-progress` at the worktree root:

```
<sha>	<classification>	<subject>
```

`<classification>` is the canonical short-name from Step 4.3 (`format`, `skill`, `rename`, or `noop`), or the special value `manual-review-needed` when the user invoked "skip" from a Step 4.4 ambiguity halt.

For `rename`-classified commits, **also** insert one indented `#   <old-path> -> <new-path>` line under the `# renames:` header (one line per rename recorded by the commit) so later commits can follow the mapping. For `manual-review-needed`, skip the renames update and apply no edits; the body line alone is enough to keep the commit out of the replay queue on the next run.

Mechanism: read the file with `Read`, mutate the contents in memory (append the body line and, for renames, insert the header line(s) under `# renames:`), then write the full file back with `Write`. Do not use `Edit` here; the initial file may have no trailing newline, so the `old_string`-based path is unreliable. Do not stage or commit the file; it lives outside git history.

If the process is killed between applying edits in 4.4 and updating `.migration-progress` here, the next run will replay the same commit. After a crash, run `git diff` before resuming to detect duplicate edits.

### 4.7 Mark the per-commit task completed

`TaskUpdate` the matching task to `completed`. Move to the next commit. When the queue is exhausted, mark Step 0's umbrella task 5 ("Per-commit migration loop") as `completed` before falling through to sub-step 4.8.

### 4.8 Final stamp-to-HEAD batch

Mark Step 0's umbrella task 6 ("Final stamp-to-HEAD batch") as `in_progress`. After the per-commit loop exits (the queue is exhausted) and before Step 5's final summary runs, re-stamp every artifact present on disk to `git rev-parse HEAD`. This sub-step lands invariant I2: at session end, every stamped artifact's line-1 SHA equals HEAD's SHA.

Capture HEAD once via `git rev-parse HEAD` into `$HEAD_SHA`. Then walk every artifact under `$PLAN_DIR/_workflow/**` per `conventions.md` §1.6(h)'s enumeration; for each artifact, read the current line-1 stamp (`head -1 "$f"`) and call `Edit` with that value as `old_string` and `<!-- workflow-sha: $HEAD_SHA -->` as `new_string`. Artifacts already at HEAD (their prior 4.5 fire already wrote this SHA) fail the `old_string` precondition — treat the mismatch as a benign no-op (the post-condition already holds), do not retry, do not halt. Optional artifacts absent from disk (`design-mechanics.md` on branches that never crossed the length trigger) are silently skipped by §1.6(h)'s `ls 2>/dev/null` shape.

Re-stamp every artifact in the walk, including artifacts whose stamp the per-commit advance in sub-step 4.5 already updated to a SHA inside the range. A re-stamp that does change content covers two cases — an artifact whose last per-commit advance landed at a non-HEAD SHA inside the range (because no later replayed commit touched its file shape), and an artifact whose first stamp arrived through 4.5's unstamped-artifact bootstrap branch at a pre-HEAD SHA.

The final batch and the per-commit advance use the same `Edit`-against-line-1 writer, so the recovery story is uniform: a killed-mid-4.8 session leaves some artifacts at `HEAD_SHA` and the rest at the prior per-commit advance SHA, which the next `/migrate-workflow` session reads as non-uniform stamps. Step 2.0's fold collapses to the lowest, range derivation re-queues the not-yet-stamped commits (an empty range when every commit has already been replayed), and 4.8 fires again to land I2.

After the walk exits, mark Step 0's umbrella task 6 as `completed`.

## Step 5 — Final summary

Mark Step 0's umbrella task 7 ("Final summary") as `in_progress`. `$HEAD_SHA` is the value sub-step 4.8 captured via `git rev-parse HEAD`; re-derive it here by running `git rev-parse HEAD` again — the value is unchanged since 4.8. `$USER_BOOTSTRAP_SHA` was captured at Step 2.0; if it is no longer in conversation state, recover it by reading line 1 of any artifact in `$UNSTAMPED_FILES` (the bootstrap branch stamped them at this value during the first commit's 4.5 fire). If Step 2.0 did not run this session (no unstamped artifacts), omit the bootstrap-count summary line entirely.

Output:

- Workflow stamps now at `$HEAD_SHA` (the value sub-step 4.8 captured from `git rev-parse HEAD`). This is the post-condition I2 lands; surfacing it in the summary gives the user a one-glance confirmation that every artifact in the active plan reached the same SHA.
- When `$USER_BOOTSTRAP_SHA` is set (Step 2.0 fired because the session started with at least one unstamped artifact), emit: "Bootstrapped N previously unstamped artifacts using SHA `$USER_BOOTSTRAP_SHA`", where N is the size of the `$UNSTAMPED_FILES` set Step 2.0 collected. Skip this line entirely when `$USER_BOOTSTRAP_SHA` is unset (fully-stamped session) so the summary stays terse on the common path.
- Count of commits migrated, of each classification (`format`, `skill`, `rename`, `noop`, `manual-review-needed`). Compute by reading the body of `.migration-progress` and counting each value of column 2.
- If any commits were recorded as `manual-review-needed`, list their short-SHA and subject so the user knows which to revisit manually.
- Total files edited in the branch's worktree, excluding the progress-file sentinel: `git status --porcelain | grep -v '^?? \.migration-progress$' | wc -l`.
- One-line next-step prompt: "Review the diff in this worktree, then commit + push when satisfied. Delete `.migration-progress` after the commit."

After printing the summary lines above, mark Step 0's umbrella task 7 as `completed`. Then proceed to Step 6.

## Step 6 — Self-improvement reflection

Mark Step 0's umbrella task 8 (`Self-improvement reflection`) as `in_progress`, then mark umbrella task 8 as `completed` **before** invoking the reflection protocol. The flip is intentional: `self-improvement-reflection.md` ends the session at its Step 11 and never returns control here, so any post-invoke completion line is unreachable. Treating the umbrella task as "I am about to invoke reflection" (not "reflection completed") keeps the user's task list consistent even though the reflection itself runs after the flip. Then invoke `.claude/workflow/self-improvement-reflection.md` with `session-type=migrate-workflow` (see `self-improvement-reflection.md` §"What counts as a worth-recording issue" for the migration-shaped friction examples). The protocol owns its own YouTrack MCP-reachability check and end-of-session contract; nothing else fires after it returns.

## Notes

- The skill edits artifacts in the branch's own worktree and never commits on the user's behalf; the user reviews the diff and commits + pushes after the session ends.
- For large diffs, delegate to `Explore` to summarize commit diffs and `general-purpose` to apply repetitive edits across many files; pass absolute paths inside the branch's worktree.
- This is a docs-only migration; mcp-steroid IDE control adds no value here.
- Re-invoking the skill on the same branch is safe: commits already recorded in `.migration-progress` are skipped. To force a full re-run, delete the progress file.
