---
name: code-review
description: "Review code, test, and workflow-machinery changes across multiple dimensions using specialized agents with triage-based selection."
argument-hint: "[branch | commit-range | uncommitted | PR-number]"
user-invocable: true
---

Review code, test, and workflow-machinery changes across multiple dimensions by dispatching to specialized review agents and synthesizing their findings. Production code, test code, and workflow files (skills, agents, hooks, settings, prompts, CLAUDE.md, plan/design artifacts) are reviewed in one pass with triage-driven agent selection.

Use `$ARGUMENTS` as the review target if provided (branch name, commit range, or "uncommitted").

## Step 1: Determine what to review

### If `$ARGUMENTS` is provided:
1. **Branch name** (e.g., `ytdb-605-unified-edges`): Review all changes on that branch that are absent from the base branch.
2. **Commit range** (e.g., `abc123..def456`): Review that specific range.
3. **"last N commits"** (e.g., `last 3 commits`): Review `HEAD~N...HEAD`.
4. **"uncommitted"** or **"working tree"**: Review uncommitted changes (`git diff HEAD`).
5. **PR number or URL** (e.g., `#42` or `https://github.com/.../pull/42`): Fetch PR details and review its diff.
6. **None of the above** (malformed input, non-existent branch, unresolvable PR, free-form phrase): Report what you tried to parse, then ask the user for a valid target. Do not guess.

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
5. If the branch IS `develop` or has no commits ahead, ask the user what to review. Treat the user's reply as if it were `$ARGUMENTS` and restart Step 1.

## Step 2: Detect the base branch

The base branch determines what "new changes" means:

1. If reviewing a PR: use the PR's `baseRefName` (fetched via `gh pr view`).
2. If reviewing a branch (no PR): default to `develop`.
3. If reviewing a commit range or uncommitted changes: no base branch needed.

## Step 3: Gather the review context

Based on the review mode, collect the changed-file list and commit log inline, but **write the full diff to a temp file** so each agent can `Read` it on demand instead of receiving it interpolated into its prompt. This keeps per-agent prompt size bounded and removes the diff-size ceiling that inline interpolation would impose.

Use a unique `/tmp` filename to avoid collisions with concurrent Claude Code agents on the same host (per the user-global rule). Generate the suffix once and reuse it for the whole dispatch:

```bash
# Generate a unique suffix for this review's temp file
DIFF_FILE=/tmp/claude-code-review-diff-$$.txt   # or use $(uuidgen)
```

### For branch or PR review:
```bash
git diff {base}...HEAD --name-only           # → CHANGED_FILES
git diff {base}...HEAD > "$DIFF_FILE"        # → DIFF_FILE
git log {base}..HEAD --oneline               # → COMMIT_LOG
```

### For commit range:
```bash
git diff {start}..{end} --name-only          # → CHANGED_FILES
git diff {start}..{end} > "$DIFF_FILE"       # → DIFF_FILE
git log {start}..{end} --oneline             # → COMMIT_LOG
```

### For uncommitted changes:
```bash
git diff HEAD --name-only                    # → CHANGED_FILES
git diff HEAD > "$DIFF_FILE"                 # → DIFF_FILE
```
For uncommitted changes there is no commit log; set `COMMIT_LOG` to the literal sentinel `"(uncommitted changes — no commit history)"`.

### PR description (if available):
```bash
gh pr view {number} --json body --jq '.body'
```

Store the collected context:
- `DIFF_FILE` — absolute path to the temp file containing the full diff (e.g., `/tmp/claude-code-review-diff-12345.txt`). Each agent reads this file via the `Read` tool.
- `CHANGED_FILES` — the list of changed file paths
- `COMMIT_LOG` — the commit history, or the uncommitted-changes sentinel above
- `PR_DESCRIPTION` — the PR body text, or the literal string `"No PR associated with these changes."` if absent
- `REVIEW_SCOPE` — human-readable description of what's being reviewed (e.g., "Branch `ytdb-605-unified-edges` vs `develop` (15 commits, 23 files)")

The temp file is ephemeral — remove it at the end of Step 7 (synthesis) so orphans don't accumulate. The unique suffix prevents collision with concurrent agents while the review is in flight.

## Step 4: Filter non-reviewable files

Before dispatching, note files that should be skipped:
- Files under `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/`
- Generated Gremlin DSL classes
- Files under `generated-sources/` or `generated-test-sources/`

Include this filter note in the context passed to agents.

## Step 5: Triage — categorize changes and select relevant agents

Before dispatching agents, perform a quick triage pass over the **entire diff** (both production and test code) to determine which review dimensions are actually relevant. This avoids wasting time on agents that have nothing meaningful to review.

### 5a: Categorize each changed file

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
| **workflow-machinery** | Files under `.claude/` (skills, agents, hooks, scripts, settings, workflow rules, workflow prompts, output styles, docs), project root `CLAUDE.md`, all files under `docs/adr/<dir>/` (plan/design artifacts in `_workflow/` and the durable `design-final.md` / `adr.md`) |
| **docs-only** | Markdown documentation under `docs/` excluding `docs/adr/<dir>/`, plus comments-only changes |
| **other** | Files matching no category above (e.g., `.gitattributes`, miscellaneous root files). Triaged as a no-op — no agents dispatch on this category. |

A file can belong to multiple categories (e.g., a lock change in storage code is both `storage-engine` and `concurrency`). Production and test files in the same domain should share the same categories. `workflow-machinery` is exclusive with `docs-only`: any file under `.claude/` or `docs/adr/<dir>/` is `workflow-machinery`; anything else under `docs/` is `docs-only`.

### 5b: Map categories to agents

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
| **review-test-concurrency** | `concurrency` is present on any changed file (production or test) |
| **review-test-crash-safety** | `crash-durability` |

Categories from **both** production and test code count for the test-review side — for example, if production code adds a new `synchronized` block but tests don't exercise threading, `review-test-concurrency` should still launch to flag the gap.

**Workflow-review agents** (review changes to the workflow machinery itself):

| Agent | Launch when |
|---|---|
| **review-workflow-consistency** | `workflow-machinery` is present — always launched for this group |
| **review-workflow-prompt-design** | `workflow-machinery` AND any changed file matches `.claude/skills/*/SKILL.md`, `.claude/agents/*.md`, or `.claude/workflow/prompts/*.md` |
| **review-workflow-instruction-completeness** | `workflow-machinery` AND any changed file matches `.claude/skills/*/SKILL.md`, `.claude/agents/*.md`, `.claude/workflow/*.md`, or `.claude/workflow/prompts/*.md` |
| **review-workflow-hook-safety** | `workflow-machinery` AND any changed file matches `.claude/hooks/*.sh`, `.claude/scripts/**`, or `.claude/settings*.json` |
| **review-workflow-context-budget** | `workflow-machinery` is present — always launched for this group. The agent itself decides whether the diff affects any of three axes — always-loaded surface (skill/agent `description:` fields, project root `CLAUDE.md`, SessionStart hook stdout), load-on-demand discipline (workflow rule files, workflow prompts, docs split), or instant per-operation consumption (orchestrator-side reads, sub-agent return surface, inlined recipes, multi-phase content reuse) — and emits an empty findings list when none are affected. |
| **review-workflow-writing-style** | `workflow-machinery` AND any changed file matches `.claude/**/*.md`, root `CLAUDE.md`, or `docs/adr/**/*.md` |

The workflow-review agents focus on `.claude/`, root `CLAUDE.md`, and plan artifacts under `docs/adr/<dir>/_workflow/`. They ignore Java code changes — the code-review and test-review agents handle those.

### 5c: Log your triage decision

Before launching agents, output a brief triage summary so the user can see the reasoning:

```
### Triage summary
- **Categories detected**: storage-engine, concurrency, index-data-structures
- **Code agents selected**: review-code-quality, review-bugs-concurrency, review-crash-safety, review-performance
- **Test agents selected**: review-test-behavior, review-test-completeness, review-test-structure, review-test-concurrency, review-test-crash-safety
- **Workflow agents selected**: (none — no workflow-machinery changes)
- **Agents skipped**: review-security (no network/API/SQL/config/dependency changes)
```

### 5d: Edge cases

The rules below cover combinations not handled by the per-row tables above. Where a combination matches more than one row, the **last matching row wins**. Workflow-machinery cases are listed first because workflow-only diffs are common and short-circuit the entire code/test agent dispatch.

- If **the only category present is `workflow-machinery`**: Skip all code-review and test-review agents (no Java code or tests to evaluate). Launch the workflow-review agents selected by the workflow-side rules.
- If **the only categories are `docs-only` and `workflow-machinery`** (any mix): Treat as `workflow-machinery`-only — skip code-review and test-review agents, launch the workflow-review group on the `workflow-machinery` files.
- If the diff **mixes `workflow-machinery` with production-code or test categories**: launch each group's agents on its in-scope files. Each group is dispatched with a pre-filtered `IN_SCOPE_FILES` list (see Step 6) so cross-contamination is bounded.
- If **the only category present is `docs-only`**: Skip all agents. Just report that only end-user documentation changed and no review is needed.
- If **the only category present is `build-config`**: Launch only `review-code-quality` (to check for misconfigurations) and `review-security` (to check for dependency changes). Skip all test-review and workflow-review agents.
- If **the only category present is `tests-only`**: Launch `review-code-quality` and `review-bugs-concurrency` (test logic can have bugs too), plus the full test-review set selected by the test-side rules above.
- If **the only categories are `build-config` and `tests-only`** (e.g., a CI tweak plus the test it enables): Launch `review-code-quality`, `review-security`, and the full test-review set. The `tests-only` rule wins over `build-config`'s "skip test-review" because the tests are the substantive change.
- If **a file matches no category** (e.g., `.gitattributes`): assign it the `other` category — it does not dispatch any agent. If `other` is the only category present, skip all agents and report that nothing reviewable changed.
- If **in doubt** about whether an agent is relevant: **launch it**. False positives (an agent finding nothing) are better than false negatives (missing a real issue).

## Step 6: Dispatch selected review agents

Launch the selected agents **in parallel** using the Agent tool. Each agent receives a scope-filtered context: build `IN_SCOPE_FILES` per agent group from the triage categories assigned in Step 5a.

- **Code-review group**: files categorized as anything other than `docs-only`, `workflow-machinery`, or `other`. (Files categorized as `build-config` stay in scope — `review-code-quality` and `review-security` need to see `pom.xml` and CI changes per Step 5d.)
- **Test-review group**: files that match the agent's individual launch rule plus any file categorized `tests-only`.
- **Workflow-review group**: files categorized as `workflow-machinery`.

The `IN_SCOPE_FILES` list narrows the agent's focus; the full `DIFF` is still passed so the agent can read context lines around each change.

The Tooling section differs by group. Use the **code/test variant** for the code-review and test-review groups, and the **workflow variant** for the workflow-review group.

### Prompt template — code-review and test-review groups

```
Review the following changes from your specialized perspective.

## Review Scope
{REVIEW_SCOPE}

## In-Scope Files
{IN_SCOPE_FILES}

## PR Description
{PR_DESCRIPTION}

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
The full diff is at: {DIFF_FILE}
Read it with the `Read` tool before forming findings. For diffs over
2000 lines, read the file in chunks using the `offset` and `limit`
parameters. Do not infer diff content from {CHANGED_FILES} alone — the
file list does not show what changed inside each file.
```

### Prompt template — workflow-review group

```
Review the following changes from your specialized perspective.

## Review Scope
{REVIEW_SCOPE}

## In-Scope Files
{IN_SCOPE_FILES}

## PR Description
{PR_DESCRIPTION}

## Commit Log
{COMMIT_LOG}

## Changed Files
{CHANGED_FILES}

## Tooling
PSI does not apply — these are markdown, shell, and JSON files, not Java
symbols. Use `Read` on the in-scope files and on any referenced skill,
agent, hook, or output-style file. Use `Grep` for "is this string
mentioned anywhere else in the repo" questions. The Java-targeted
mcp-steroid PSI rule does not apply to workflow-machinery review.

## Diff
The full diff is at: {DIFF_FILE}
Read it with the `Read` tool before forming findings. For diffs over
2000 lines, read the file in chunks using the `offset` and `limit`
parameters.
```

### Handling missing fields

If `PR_DESCRIPTION` is the literal string `"No PR associated with these changes."` or `COMMIT_LOG` is the uncommitted-changes sentinel, treat the field as carrying no signal. Do not infer requirements from the absence.

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
15. **review-workflow-context-budget** — always-loaded surface (descriptions, CLAUDE.md, MEMORY.md, SessionStart stdout), load-on-demand discipline, instant per-operation consumption (sub-agent delegation, output caps, `/tmp` staging, targeted reads)
16. **review-workflow-writing-style** — concise-doc style: banned vocabulary, em-dash cap, BLUF lead, 200-word section cap, repo-anchored voice

Set `subagent_type` to the agent name. The agent's frontmatter declares its model; do not override it from the dispatch call unless the user explicitly asks for a different model.

## Step 7: Synthesize the results

After all selected agents complete, produce a unified review report. Do NOT simply concatenate the outputs. Instead:

1. **Map sub-agent severities to synthesized severities.** Most sub-agents emit findings under `Critical / Recommended / Minor`, but three of the older code-review agents use legacy scales. Translate as:
   - `Critical` → **blocker** (all agents)
   - `Recommended` → **should-fix** (most agents)
   - `Minor` → **suggestion** (most agents)
   - `Likely Issues` → **should-fix** (`review-bugs-concurrency`)
   - `Potential Concerns` → **suggestion** (`review-bugs-concurrency`)
   - `Concerning` → **should-fix** (`review-crash-safety`)
   - `Informational` → **suggestion** (`review-crash-safety`)
   - `High` → **blocker** (`review-security`)
   - `Medium` → **should-fix** (`review-security`)
   - `Low` → **suggestion** (`review-security`)

   Apply this mapping verbatim. The "do not soften" rule below means: do not demote a sub-agent's blocker-level finding to anything below `blocker`. The only legal override is promoting (e.g., raising a `should-fix` to `blocker` because another sub-agent's blocker-level finding on the same line escalates the severity).

2. **Deduplicate.** Findings merge when they share the same `(file, line-range, root issue)`. Different review dimensions on the same line merge into one finding listing all dimensions. Different lines do not merge. Workflow-review findings on a shell or JSON file may merge with code-review findings on the same file when they describe the same root issue.

3. **Attribute.** For each finding, indicate which review dimension(s) identified it. Use a short label (e.g., `[code-quality]`, `[bugs-concurrency]`, `[test-behavior]`, `[test-crash-safety]`, `[workflow-consistency]`, `[workflow-hook-safety]`, `[workflow-writing-style]`).

4. **Summarize.** Write a brief overall assessment (2-3 sentences). Cover whichever of code, tests, and workflow machinery actually appear in the diff. Do not write about a dimension that produced no findings.

### Handling agent failures and empty output

- If a sub-agent returns empty output, errors out, or times out, add it to a `### Failed reviewers` section at the top of the report with a one-line note and continue. Do not block the synthesis.
- If a sub-agent emits agent-specific preface sections beyond `Critical / Recommended / Minor` (for example, `review-workflow-context-budget` emits `Always-loaded delta` and `Instant-consumption delta` tables), propagate them under a `### Reviewer notes` section after the severity blocks rather than dropping them silently.
- If all selected agents return zero findings, emit an explicit `### All clear` block under the overall assessment instead of an empty report.
- After the synthesized report is produced (whether with findings or `### All clear`), remove the temp diff file from Step 3: `rm "$DIFF_FILE"`. The file is no longer needed once every sub-agent has returned.

### Output format

```markdown
## Review: {REVIEW_SCOPE}

### Failed reviewers
[Omit if none. One line per failed agent.]

### Overall assessment
[2-3 sentences. Cover whichever of code, tests, and workflow machinery
produced findings — skip the dimensions that returned clean.]

### Blockers
[Must fix before merge]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Should-fix
[Should fix before merge]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Suggestions
[Recommended improvements]

1. **[Dimension]** `path/to/file.ext` (line X-Y)
   - **Issue**: ...
   - **Suggestion**: ...

### Reviewer notes
[Agent-specific preface sections propagated verbatim. Omit if none.]

### Questions for the author
[Clarifying questions aggregated from all reviewers. Omit if none.]
```

If a priority level has no findings, omit it entirely. If all priority levels are empty, replace them with `### All clear` and a one-line note.

## Important rules

- **Always use `gh` CLI** for GitHub API calls, not WebFetch.
- **All selected agents must run in parallel** — do not wait for one before launching the next.
- **Only launch agents selected by the triage step** — do not launch agents for irrelevant dimensions.
- **Do not add your own review findings** — only synthesize what the agents report.
- **Do not soften or dismiss agent findings** — keep `Critical` at `blocker`, `Recommended` at `should-fix`, `Minor` at `suggestion`. Promotion is allowed when another sub-agent's higher-severity finding on the same line argues for it.
- **If the diff is very large** (>500 files or >50,000 lines), abort and ask the user to scope the review more narrowly (by directory, module, or commit range). The file-based diff hand-off in Step 3 lifts the per-prompt limit, so the practical ceiling is the per-agent context window — only very large reviews need user-side scoping.
- **Standalone command**: This command uses the same dimensional review agents as the Phase 3 workflow but with a different context structure (PR description and commit log instead of implementation plan and step file). Severity scale uses the same blocker/should-fix/suggestion levels as the workflow (see `.claude/workflow/review-iteration.md`).
