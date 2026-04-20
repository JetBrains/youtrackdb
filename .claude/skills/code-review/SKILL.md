---
name: code-review
description: "Review code changes across multiple dimensions (quality, bugs, concurrency, crash-safety, security, performance) using specialized agents with triage-based selection."
argument-hint: "[branch | commit-range | uncommitted | PR-number]"
user-invocable: true
---

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

## Step 5: Triage — Categorize Changes and Select Relevant Agents

Before dispatching agents, perform a quick triage pass over the diff to determine which review dimensions are actually relevant. This avoids wasting time on agents that have nothing meaningful to review.

### 5a: Categorize Each Changed File

Scan the diff and assign one or more categories to each changed file:

| Category | Signals |
|---|---|
| **storage-engine** | Files in `storage/`, `cache/`, `wal/`, `StorageComponent` subclasses, page read/write logic, `DiskStorage`, `WriteCache`, `ReadCache`, `LogSequenceNumber`, double-write log |
| **concurrency** | `synchronized`, `Lock`, `Atomic*`, `volatile`, `StampedLock`, `ReentrantLock`, thread pools, `ConcurrentHashMap`, `CompletableFuture`, shared mutable state, `@GuardedBy` |
| **index-data-structures** | Files in `index/`, B-tree, hash index, `SBTree`, `CellBTree`, histogram, `IndexEngine` |
| **network-server** | Files in `server/`, `driver/`, Gremlin Server, protocol handling, TLS/SSL, authentication, session management |
| **sql-query** | Files in `sql/` (excluding `parser/`), query execution, command handlers, `SELECT`/`INSERT`/`UPDATE`/`DELETE` logic |
| **gremlin** | Files in `gremlin/`, traversal steps, `YTDBGraph*` classes, TinkerPop integration |
| **public-api** | Files in `com.jetbrains.youtrackdb.api`, `YourTracks`, `YouTrackDB` interface |
| **serialization** | Record serializers, binary format, property map encoding/decoding |
| **crash-durability** | WAL operations, crash simulation, durable `StorageComponent` recovery, page corruption handling, transaction atomicity under failure, `LogSequenceNumber` manipulation, double-write log, Java `assert` statements in production code |
| **configuration** | `GlobalConfiguration`, config parameters, system properties |
| **tests-only** | Changes exclusively in test files with no production code changes |
| **build-config** | `pom.xml`, CI workflows, Maven profiles, Docker configs |
| **docs-only** | Markdown, documentation, comments-only changes |

A file can belong to multiple categories (e.g., a lock change in storage code is both `storage-engine` and `concurrency`).

### 5b: Map Categories to Agents

Use the following mapping to decide which agents to launch:

| Agent | Launch when ANY of these categories are present |
|---|---|
| **review-code-quality** | Always launched (unless `docs-only` is the ONLY category) |
| **review-bugs-concurrency** | `concurrency`, `storage-engine`, `index-data-structures`, `network-server`, `serialization`, `gremlin`, `sql-query` |
| **review-crash-safety** | `crash-durability` |
| **review-security** | `network-server`, `public-api`, `sql-query`, `serialization`, `configuration`, OR when new dependencies are added in `pom.xml` |
| **review-performance** | `storage-engine`, `index-data-structures`, `concurrency`, `serialization`, `sql-query`, `gremlin` |

### 5c: Log Your Triage Decision

Before launching agents, output a brief triage summary so the user can see the reasoning:

```
### Triage Summary
- **Categories detected**: storage-engine, concurrency, index-data-structures
- **Agents selected**: review-code-quality, review-bugs-concurrency, review-crash-safety, review-performance
- **Agents skipped**: review-security (no network/API/SQL/config/dependency changes)
```

### 5d: Edge Cases
- If **all categories are `docs-only`**: Skip all agents. Just report that only documentation changed and no code review is needed.
- If **all categories are `build-config`**: Launch `review-code-quality` (to check for misconfigurations) and `review-security` (to check for dependency changes).
- If **all categories are `tests-only`**: Launch only `review-code-quality` and `review-bugs-concurrency` (test logic can have bugs too).
- If **in doubt** about whether an agent is relevant: **launch it**. False positives (an agent finding nothing) are better than false negatives (missing a real issue).

## Step 6: Dispatch Selected Review Agents

Launch the selected agents **in parallel** using the Agent tool. Each agent receives the same context but reviews from its own dimension.

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

The 5 possible agents (launch only those selected in Step 5):
1. **review-code-quality** — code quality, conventions, readability
2. **review-bugs-concurrency** — bugs, logic errors, concurrency, resource leaks
3. **review-crash-safety** — WAL correctness, durability, crash recovery
4. **review-security** — injection, auth, data exposure, dependencies
5. **review-performance** — algorithmic complexity, allocations, lock contention, I/O

Set `subagent_type` to the agent name and `model` to `opus` for each.

## Step 7: Synthesize the Results

After all selected agents complete, produce a unified review report. Do NOT simply concatenate the outputs. Instead:

1. **Deduplicate**: If multiple agents flagged the same issue (e.g., a resource leak flagged by both bugs-concurrency and performance), merge into one finding and note which dimensions it affects.

2. **Prioritize**: Order findings by severity:
   - **blocker** — must fix before merge (bugs, security vulns, crash safety, data corruption)
   - **should-fix** — should fix before merge (likely bugs, serious performance issues, concurrency risks)
   - **suggestion** — recommended improvements (code quality, moderate performance, style, optional optimizations)

3. **Attribute**: For each finding, indicate which review dimension(s) identified it.

4. **Summarize**: Write a brief overall assessment (2-3 sentences).

### Output Format

```markdown
## Code Review: {REVIEW_SCOPE}

### Overall Assessment
[2-3 sentences: is this ready to merge? What are the main concerns?]

### Blockers
[Must fix before merge]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Should-Fix
[Should fix before merge]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Suggestions
[Recommended improvements]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Questions for the Author
[Clarifying questions aggregated from all reviewers]
```

If a priority level has no findings, omit it entirely.

## Important Rules

- **Always use `gh` CLI** for GitHub API calls, not WebFetch.
- **All selected agents must run in parallel** — do not wait for one before launching the next.
- **Only launch agents selected by the triage step** — do not launch agents for irrelevant dimensions.
- **Do not add your own review findings** — only synthesize what the agents report.
- **Do not soften or dismiss agent findings** — if an agent flags something as a blocker, keep it as a blocker unless another agent's context clearly contradicts it.
- **If the diff is very large** (>200 files or >5000 lines), warn the user and offer to review in batches by module or directory.
- **Standalone command**: This command uses the same dimensional review agents as the Phase 3 workflow but with a different context structure (PR description and commit log instead of implementation plan and step file). Severity scale uses the same blocker/should-fix/suggestion levels as the workflow (see `conventions.md` §1.3).
