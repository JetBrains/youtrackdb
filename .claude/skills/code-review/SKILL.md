---
name: code-review
description: "Review code, test, and workflow-machinery changes across multiple dimensions using specialized agents with triage-based selection."
argument-hint: "[branch | commit-range | uncommitted | PR-number]"
user-invocable: true
---

Review code, test, and workflow-machinery changes across multiple dimensions by dispatching to specialized review agents and synthesizing their findings. Production code, test code, and workflow files (skills, agents, hooks, settings, prompts, CLAUDE.md, plan/design artifacts) are reviewed in one pass with triage-driven agent selection.

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

Before dispatching agents, perform a quick triage pass over the **entire diff** (both production and test code) to determine which review dimensions are actually relevant. This avoids wasting time on agents that have nothing meaningful to review.

### 5a: Categorize Each Changed File

Scan the diff and assign one or more categories to **every** changed file — production code, test code, and other files alike:

| Category | Signals |
|---|---|
| **storage-engine** | Files in `storage/`, `cache/`, `wal/`, `StorageComponent` subclasses, page read/write logic, `DiskStorage`, `WriteCache`, `ReadCache`, `LogSequenceNumber`, double-write log |
| **concurrency** | `synchronized`, `Lock`, `Atomic*`, `volatile`, `StampedLock`, `ReentrantLock`, thread pools, `ConcurrentHashMap`, `CompletableFuture`, shared mutable state, `@GuardedBy`, `ConcurrentTestHelper`, `CountDownLatch`, `CyclicBarrier` |
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
| **workflow-machinery** | Files under `.claude/` (skills, agents, hooks, scripts, settings, workflow rules, workflow prompts, output styles, docs), project root `CLAUDE.md`, plan/design artifacts under `docs/adr/<dir>/_workflow/` and the durable `design-final.md` / `adr.md` |
| **docs-only** | Markdown documentation outside `.claude/` and `docs/adr/<dir>/_workflow/`, end-user docs under `docs/`, comments-only changes |

A file can belong to multiple categories (e.g., a lock change in storage code is both `storage-engine` and `concurrency`). Production and test files in the same domain should share the same categories. `workflow-machinery` is exclusive with `docs-only` — markdown under `.claude/` is `workflow-machinery`, not `docs-only`.

### 5b: Map Categories to Agents

There are **16 specialized review agents** in three groups:

**Code-review agents** (review production code):

| Agent | Launch when ANY of these categories are present |
|---|---|
| **review-code-quality** | Always launched (unless `docs-only` is the ONLY category) |
| **review-bugs-concurrency** | `concurrency`, `storage-engine`, `index-data-structures`, `network-server`, `serialization`, `gremlin`, `sql-query` |
| **review-crash-safety** | `crash-durability` |
| **review-security** | `network-server`, `public-api`, `sql-query`, `serialization`, `configuration`, OR when new dependencies are added in `pom.xml` |
| **review-performance** | `storage-engine`, `index-data-structures`, `concurrency`, `serialization`, `sql-query`, `gremlin` |

**Test-review agents** (review test quality and coverage gaps):

| Agent | Launch when |
|---|---|
| **review-test-behavior** | Always launched (unless `docs-only` or `build-config` are the ONLY categories) |
| **review-test-completeness** | Always launched (unless `docs-only` or `build-config` are the ONLY categories) |
| **review-test-structure** | Any test files are changed (reviews isolation, readability, setup/teardown of test code itself) |
| **review-test-concurrency** | `concurrency`, OR production code touches shared mutable state / threading primitives even if no concurrency tests exist yet |
| **review-test-crash-safety** | `crash-durability` |

Categories from **both** production and test code count for the test-review side — for example, if production code adds a new `synchronized` block but tests don't exercise threading, `review-test-concurrency` should still launch to flag the gap.

**Workflow-review agents** (review changes to the workflow machinery itself):

| Agent | Launch when |
|---|---|
| **review-workflow-consistency** | `workflow-machinery` is present — always launched for this group |
| **review-workflow-prompt-design** | `workflow-machinery` AND changes touch any file under `.claude/skills/`, `.claude/agents/`, or `.claude/workflow/prompts/` |
| **review-workflow-instruction-completeness** | `workflow-machinery` AND changes touch skill bodies, agent bodies, workflow rules under `.claude/workflow/`, or workflow prompts |
| **review-workflow-hook-safety** | `workflow-machinery` AND changes touch `.claude/hooks/`, `.claude/scripts/`, or `.claude/settings*.json` |
| **review-workflow-context-budget** | `workflow-machinery` AND changes affect always-loaded surface — skill/agent `description:` fields, project root `CLAUDE.md`, `MEMORY.md` index, SessionStart hook stdout |
| **review-workflow-writing-style** | `workflow-machinery` AND any markdown content changed |

The workflow-review agents focus on `.claude/`, root `CLAUDE.md`, and plan artifacts under `docs/adr/<dir>/_workflow/`. They ignore Java code changes — the code-review and test-review agents handle those.

### 5c: Log Your Triage Decision

Before launching agents, output a brief triage summary so the user can see the reasoning:

```
### Triage Summary
- **Categories detected**: storage-engine, concurrency, index-data-structures
- **Code agents selected**: review-code-quality, review-bugs-concurrency, review-crash-safety, review-performance
- **Test agents selected**: review-test-behavior, review-test-completeness, review-test-structure, review-test-concurrency, review-test-crash-safety
- **Workflow agents selected**: (none — no workflow-machinery changes)
- **Agents skipped**: review-security (no network/API/SQL/config/dependency changes)
```

### 5d: Edge Cases
- If **all categories are `docs-only`**: Skip all agents. Just report that only end-user documentation changed and no review is needed.
- If **all categories are `build-config`**: Launch only `review-code-quality` (to check for misconfigurations) and `review-security` (to check for dependency changes). Skip all test-review and workflow-review agents.
- If **all categories are `tests-only`**: Launch `review-code-quality` and `review-bugs-concurrency` (test logic can have bugs too), plus the full test-review set selected by the test-side rules above.
- If **all categories are `workflow-machinery`**: Skip all code-review and test-review agents (no Java code or tests to evaluate). Launch the workflow-review agents selected by the workflow-side rules.
- If the diff mixes `workflow-machinery` with code/test categories: launch each group's agents on its in-scope files. Each agent's prompt restricts its scope, so cross-contamination is bounded.
- If **in doubt** about whether an agent is relevant: **launch it**. False positives (an agent finding nothing) are better than false negatives (missing a real issue).

## Step 6: Dispatch Selected Review Agents

Launch the selected agents **in parallel** using the Agent tool. Each agent receives the same context but reviews from its own dimension.

For each agent, use this prompt template (fill in the agent-specific name):

```
Review the following changes from your specialized perspective.

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

## Tooling
Use **mcp-steroid PSI find-usages / find-implementations / type-hierarchy
via `steroid_execute_code`, not grep**, for any reference-accuracy
question about a Java symbol in this diff (callers/overrides/usages of
a method, field, class, or annotation; whether a slot is genuinely
unused; whether a renamed symbol still has stale references; for test
review, which production methods a test exercises and where else they
are called). Grep is acceptable for filename globs, unique string
literals, and orientation reads, but the load-bearing answer behind a
finding must be PSI-backed when the mcp-steroid MCP server is
reachable per the SessionStart hook (`steroid_list_projects` once at
the start confirms the open project matches the working tree). Fall
back to grep with an explicit reference-accuracy caveat in the finding
only when mcp-steroid is unreachable. See `CLAUDE.md` § MCP Steroid →
"Grep vs PSI — when to switch" for the full routing rule.

## Diff
{DIFF}
```

The 16 possible agents (launch only those selected in Step 5):

**Code-review agents:**
1. **review-code-quality** — code quality, conventions, readability
2. **review-bugs-concurrency** — bugs, logic errors, concurrency, resource leaks
3. **review-crash-safety** — WAL correctness, durability, crash recovery
4. **review-security** — injection, auth, data exposure, dependencies
5. **review-performance** — algorithmic complexity, allocations, lock contention, I/O

**Test-review agents:**
6. **review-test-behavior** — behavior-driven quality, assertion precision, exception testing
7. **review-test-completeness** — corner cases, boundary conditions, test data quality
8. **review-test-structure** — isolation, independence, readability, documentation
9. **review-test-concurrency** — concurrent behavior testing quality
10. **review-test-crash-safety** — crash/recovery test quality, production assert statements

**Workflow-review agents:**
11. **review-workflow-consistency** — cross-file references, threshold sync, hook wiring, recipe paths, glossary drift
12. **review-workflow-prompt-design** — prompts-as-prompts-to-an-LLM: description discriminability, deterministic decision rules, clean-context invocation, sub-agent delegation annotations
13. **review-workflow-instruction-completeness** — branch coverage, gate resume paths, sub-agent input/output handshake, error recovery, loop termination
14. **review-workflow-hook-safety** — shell hygiene, `/tmp` collision safety, hook performance, secret hygiene, JSON schema validity
15. **review-workflow-context-budget** — always-loaded surface (descriptions, CLAUDE.md, MEMORY.md, SessionStart stdout), load-on-demand discipline
16. **review-workflow-writing-style** — concise-doc style: banned vocabulary, em-dash cap, BLUF lead, 200-word section cap, repo-anchored voice

Set `subagent_type` to the agent name and `model` to `opus` for each.

## Step 7: Synthesize the Results

After all selected agents complete, produce a unified review report. Do NOT simply concatenate the outputs. Instead:

1. **Deduplicate**: If multiple agents flagged the same issue (e.g., a resource leak flagged by both bugs-concurrency and performance, or a missing crash-recovery test flagged by both crash-safety and test-crash-safety), merge into one finding and note which dimensions it affects.

2. **Prioritize**: Order findings by severity:
   - **blocker** — must fix before merge (bugs, security vulns, crash safety, data corruption, tests that give false confidence, missing tests for dangerous code paths)
   - **should-fix** — should fix before merge (likely bugs, serious performance issues, concurrency risks, missing corner cases for critical code, weak assertions that could hide bugs)
   - **suggestion** — recommended improvements (code quality, moderate performance, style, optional optimizations, test data quality, naming, optional edge cases)

3. **Attribute**: For each finding, indicate which review dimension(s) identified it. Use a short label (e.g., `[code-quality]`, `[bugs-concurrency]`, `[test-behavior]`, `[test-crash-safety]`).

4. **Summarize**: Write a brief overall assessment (2-3 sentences) covering both code quality and test quality.

### Output Format

```markdown
## Review: {REVIEW_SCOPE}

### Overall Assessment
[2-3 sentences: is this ready to merge? What are the main concerns on the
code side and on the test side?]

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
- **Standalone command**: This command uses the same dimensional review agents as the Phase 3 workflow but with a different context structure (PR description and commit log instead of implementation plan and step file). Severity scale uses the same blocker/should-fix/suggestion levels as the workflow (see `.claude/workflow/review-iteration.md`).
