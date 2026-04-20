---
name: test-review
description: "Review test quality across multiple dimensions (behavior, completeness, structure, concurrency, crash-safety) using specialized agents with triage-based selection."
argument-hint: "[branch | commit-range | uncommitted | PR-number]"
user-invocable: true
---

Review test quality across multiple dimensions by dispatching to specialized test review agents and synthesizing their findings.

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

1. If reviewing a PR: use the PR's `baseRefName` (fetched via `gh pr view`).
2. If reviewing a branch (no PR): default to `develop`.
3. If reviewing a commit range or uncommitted changes: no base branch needed.

## Step 3: Gather the Review Context

Based on the review mode, collect:

### For branch or PR review:
```bash
git diff {base}...HEAD --name-only
git diff {base}...HEAD
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
- `REVIEW_SCOPE` — human-readable description of what's being reviewed

## Step 4: Filter Non-Reviewable Files

Note files that should be skipped:
- Files under `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/`
- Generated Gremlin DSL classes
- Files under `generated-sources/` or `generated-test-sources/`

## Step 5: Triage — Categorize Changes and Select Relevant Agents

Before dispatching agents, perform a quick triage pass over the **entire diff** (both production and test code) to determine which test review dimensions are actually relevant. This avoids wasting time on agents that have nothing meaningful to review.

### 5a: Categorize All Changed Files

Scan the diff and assign one or more categories to **every** changed file — production code, test code, and other files alike:

| Category | Signals |
|---|---|
| **storage-engine** | Files in `storage/`, `cache/`, `wal/`, `StorageComponent` subclasses, page read/write logic, `DiskStorage`, `WriteCache`, `ReadCache`, `LogSequenceNumber`, double-write log |
| **concurrency** | `synchronized`, `Lock`, `Atomic*`, `volatile`, `StampedLock`, `ReentrantLock`, thread pools, `ConcurrentHashMap`, `CompletableFuture`, shared mutable state, `@GuardedBy`, `ConcurrentTestHelper`, `CountDownLatch`, `CyclicBarrier` |
| **crash-durability** | WAL operations, crash simulation, durable `StorageComponent` recovery, page corruption handling, transaction atomicity under failure, `LogSequenceNumber` manipulation, double-write log, Java `assert` statements in production code |
| **index-data-structures** | Files in `index/`, B-tree, hash index, `SBTree`, `CellBTree`, histogram, `IndexEngine` |
| **serialization** | Record serializers, binary format, property map encoding/decoding |
| **sql-query** | Files in `sql/` (excluding `parser/`), query execution, command handlers |
| **gremlin** | Files in `gremlin/`, traversal steps, `YTDBGraph*` classes, TinkerPop integration |
| **network-server** | Files in `server/`, `driver/`, Gremlin Server, protocol handling, authentication |
| **public-api** | Files in `com.jetbrains.youtrackdb.api`, `YourTracks`, `YouTrackDB` interface |
| **configuration** | `GlobalConfiguration`, config parameters, system properties |
| **build-config** | `pom.xml`, CI workflows, Maven profiles, Docker configs |
| **docs-only** | Markdown, documentation, comments-only changes |

A file can belong to multiple categories (e.g., a concurrent index test is both `concurrency` and `index-data-structures`). Production and test files in the same domain should share the same categories.

### 5b: Map Categories to Agents

Two agents **always run** because they catch general gaps regardless of domain. The remaining three are specialized and only launch when their domain is relevant. Categories from **both** production and test code count — for example, if production code adds a new `synchronized` block but tests don't exercise threading, `review-test-concurrency` should still launch to flag the gap.

| Agent | When to launch |
|---|---|
| **review-test-behavior** | **Always** (unless `docs-only` or `build-config` are the ONLY categories) |
| **review-test-completeness** | **Always** (unless `docs-only` or `build-config` are the ONLY categories) |
| **review-test-structure** | Any test files are changed (reviews isolation, readability, setup/teardown of test code itself) |
| **review-test-concurrency** | `concurrency`, OR production code touches shared mutable state / threading primitives even if no concurrency tests exist yet |
| **review-test-crash-safety** | `crash-durability` |

### 5c: Log Your Triage Decision

Before launching agents, output a brief triage summary so the user can see the reasoning:

```
### Triage Summary
- **Categories detected**: storage-engine, index-data-structures
- **Agents selected**: review-test-behavior, review-test-completeness, review-test-structure, review-test-crash-safety
- **Agents skipped**: review-test-concurrency (no threading primitives or shared mutable state in changes)
```

### 5d: Edge Cases
- If **all categories are `docs-only` or `build-config`** with no production or test code changes: Skip all agents. Report that no reviewable code was changed.
- If **in doubt** about whether a specialized agent is relevant: **launch it**. False positives (an agent finding nothing) are better than false negatives (missing a real issue).

## Step 6: Dispatch Selected Test Review Agents

Launch the selected agents **in parallel** using the Agent tool. Each agent receives the same context but reviews from its own dimension.

For each agent, use this prompt template:

```
Review the following code changes from your specialized test quality perspective.

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
1. **review-test-behavior** — behavior-driven quality, assertion precision, exception testing
2. **review-test-completeness** — corner cases, boundary conditions, test data quality
3. **review-test-structure** — isolation, independence, readability, documentation
4. **review-test-concurrency** — concurrent behavior testing quality
5. **review-test-crash-safety** — crash/recovery test quality, production assert statements

Set `subagent_type` to the agent name and `model` to `opus` for each.

## Step 7: Synthesize the Results

After all selected agents complete, produce a unified review report. Do NOT simply concatenate the outputs. Instead:

1. **Deduplicate**: If multiple agents flagged the same issue (e.g., a missing test for a concurrent crash scenario flagged by both concurrency and crash-safety), merge into one finding and note which dimensions it affects.

2. **Prioritize**: Order findings by severity:
   - **blocker** — tests that give false confidence, missing tests for dangerous code paths
   - **should-fix** — missing corner cases for critical code, weak assertions that could hide bugs
   - **suggestion** — recommended improvements (test data quality, readability, additional scenarios, naming, organization, optional edge cases)

3. **Attribute**: For each finding, indicate which review dimension(s) identified it.

4. **Summarize**: Write a brief overall assessment (2-3 sentences).

### Output Format

```markdown
## Test Quality Review: {REVIEW_SCOPE}

### Overall Assessment
[2-3 sentences: are tests meaningful and thorough? What are the main gaps?]

### Blockers
[Tests that give false confidence or missing tests for dangerous code paths]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Should-Fix
[Missing corner cases, weak assertions hiding real bugs]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Suggestions
[Recommended improvements, test data quality, naming, optional edge cases]

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
- **If the diff is very large** (>200 files or >5000 lines), warn the user and offer to review in batches.
- **Standalone command**: This command uses the same dimensional test review agents as the Phase 3 workflow but with a different context structure (PR description and commit log instead of implementation plan and step file). Severity scale uses the same blocker/should-fix/suggestion levels as the workflow (see `conventions.md` §1.3).
