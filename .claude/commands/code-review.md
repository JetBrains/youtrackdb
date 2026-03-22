Review code changes across multiple dimensions by dispatching to specialized review agents and synthesizing their findings.

Use `$ARGUMENTS` as the review target if provided (branch name, commit range, or "uncommitted").

## Step 1: Determine What to Review

Determine the review context using the following priority:

### If `$ARGUMENTS` is provided:
1. **Branch name** (e.g., `ytdb-605-unified-edges`): Review all changes on that branch that are absent from the base branch.
2. **Commit range** (e.g., `abc123..def456`): Review that specific range.
3. **"last N commits"** (e.g., `last 3 commits`): Review `HEAD~N...HEAD`.
4. **"uncommitted"** or **"working tree"**: Review uncommitted changes (`git diff HEAD`).
5. **PR number or URL** (e.g., `#42` or `https://github.com/.../pull/42`): Fetch PR details and review its diff.

### If `$ARGUMENTS` is empty:
1. Check if the current branch has an open PR:
   ```bash
   gh pr list --head $(git branch --show-current) --json number,title,body,baseRefName,url --limit 1
   ```
2. If a PR exists, use it as the review target (the PR's base branch becomes the comparison base).
3. If no PR exists, check if the current branch differs from `develop`:
   ```bash
   git log develop..HEAD --oneline
   ```
4. If there are commits ahead of `develop`, review those.
5. If the branch IS `develop` or has no commits ahead, ask the user what to review.

## Step 2: Detect the Base Branch

The base branch determines what "new changes" means:

1. If reviewing a PR: use the PR's `baseRefName` (fetched via `gh pr view`).
2. If reviewing a branch (no PR): default to `develop`.
3. If reviewing a commit range or uncommitted changes: no base branch needed.

## Step 3: Gather the Review Context

Based on the review mode, collect:

### For branch or PR review:
```bash
# Changed files
git diff {base}...HEAD --name-only

# Full diff
git diff {base}...HEAD

# Commit log
git log {base}..HEAD --oneline
```

### For commit range:
```bash
git diff {start}..{end} --name-only
git diff {start}..{end}
git log {start}..{end} --oneline
```

### For uncommitted changes:
```bash
git diff HEAD --name-only
git diff HEAD
```

### PR description (if available):
```bash
gh pr view {number} --json body --jq '.body'
```

Store the collected context:
- `DIFF` — the full diff output
- `CHANGED_FILES` — the list of changed file paths
- `COMMIT_LOG` — the commit history
- `PR_DESCRIPTION` — the PR body text (empty string if no PR)
- `REVIEW_SCOPE` — human-readable description of what's being reviewed (e.g., "Branch `ytdb-605-unified-edges` vs `develop` (15 commits, 23 files)")

## Step 4: Filter Non-Reviewable Files

Before dispatching, note files that should be skipped:
- Files under `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/`
- Generated Gremlin DSL classes
- Files under `generated-sources/` or `generated-test-sources/`

Include this filter note in the context passed to agents.

## Step 5: Dispatch to Review Agents

Launch all 5 review agents **in parallel** using the Agent tool. Each agent receives the same context but reviews from its own dimension.

For each agent, use this prompt template (fill in the agent-specific name):

```
Review the following code changes from your specialized perspective.

## Review Scope
{REVIEW_SCOPE}

## PR Description
{PR_DESCRIPTION or "No PR associated with these changes."}

## Commit Log
{COMMIT_LOG}

## Changed Files
{CHANGED_FILES}

## Skip These Files (generated code)
- core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/*
- Any files under generated-sources/ or generated-test-sources/
- Generated Gremlin DSL classes

## Diff
{DIFF}
```

The 5 agents to launch (all in parallel):
1. **review-code-quality** — code quality, conventions, readability
2. **review-bugs-concurrency** — bugs, logic errors, concurrency, resource leaks
3. **review-crash-safety** — WAL correctness, durability, crash recovery
4. **review-security** — injection, auth, data exposure, dependencies
5. **review-performance** — algorithmic complexity, allocations, lock contention, I/O

Set `subagent_type` to the agent name and `model` to `opus` for each.

## Step 6: Synthesize the Results

After all 5 agents complete, produce a unified review report. Do NOT simply concatenate the outputs. Instead:

1. **Deduplicate**: If multiple agents flagged the same issue (e.g., a resource leak flagged by both bugs-concurrency and performance), merge into one finding and note which dimensions it affects.

2. **Prioritize**: Order findings by severity:
   - **Critical** — must fix before merge (bugs, security vulns, crash safety, data corruption)
   - **High** — should fix before merge (likely bugs, serious performance issues, concurrency risks)
   - **Medium** — recommended improvements (code quality, moderate performance, hardening)
   - **Low** — minor suggestions (style nits, optional optimizations)

3. **Attribute**: For each finding, indicate which review dimension(s) identified it.

4. **Summarize**: Write a brief overall assessment (2-3 sentences).

### Output Format

```markdown
## Code Review: {REVIEW_SCOPE}

### Overall Assessment
[2-3 sentences: is this ready to merge? What are the main concerns?]

### Critical Issues
[Must fix before merge]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### High Priority
[Should fix before merge]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Medium Priority
[Recommended improvements]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Low Priority
[Minor suggestions]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Questions for the Author
[Clarifying questions aggregated from all reviewers]
```

If a priority level has no findings, omit it entirely.

## Important Rules

- **Always use `gh` CLI** for GitHub API calls, not WebFetch.
- **All 5 agents must run in parallel** — do not wait for one before launching the next.
- **Do not add your own review findings** — only synthesize what the agents report.
- **Do not soften or dismiss agent findings** — if an agent flags something as critical, keep it critical unless another agent's context clearly contradicts it.
- **If the diff is very large** (>200 files or >5000 lines), warn the user and offer to review in batches by module or directory.
