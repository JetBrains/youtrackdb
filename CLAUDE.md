# CLAUDE.md - YouTrackDB Project Guide

## Project Overview

YouTrackDB is a general-purpose object-oriented graph database developed by JetBrains, used internally in production. It implements the Apache TinkerPop API with Gremlin query language support and features O(1) link traversal, schema-less/mixed/full modes, and encryption at rest. The project is a fork of OrientDB, re-architected under the `com.jetbrains.youtrackdb` package namespace.

- **License**: Apache 2.0
- **JDK**: 21+ required
- **Build**: Maven with Maven Wrapper (`./mvnw`)
- **Group ID**: `io.youtrackdb`
- **Version**: `0.5.0-SNAPSHOT` (CI-friendly: `${revision}${sha1}${changelist}`)
- **Issue tracker**: https://youtrack.jetbrains.com/issues/YTDB (YouTrack project code: `YTDB`)
- **Repository**: https://github.com/JetBrains/youtrackdb

## Build Commands

```bash
# Full build (skip tests for speed)
./mvnw clean package -DskipTests

# Full build with unit tests (in-memory storage, default)
./mvnw clean package

# Full build with unit tests on disk storage (as CI does)
./mvnw clean package -Dyoutrackdb.test.env=ci

# Run integration tests (separate from PR pipeline, used by nightly CI)
./mvnw clean verify -P ci-integration-tests

# Build with Docker images (requires Docker)
./mvnw clean package -P docker-images

# Run a single test class
./mvnw -pl core clean test -Dtest=SomeTestClass

# Run a single test method
./mvnw -pl core clean test -Dtest=SomeTestClass#testMethodName
```

**JVM memory**: `.mvn/jvm.config` sets `-Xmx8192m` for Maven itself. Tests use `-Xms4096m -Xmx4096m` (configurable via `heapSize` property).

**Important**: Tests require numerous `--add-opens` JVM flags for Java module system compatibility. These are configured in each module's `pom.xml` `<argLine>` property — do not remove them.

## Project Documentation

The `docs/` folder contains project documentation. See `docs/README.md` for the index.

## Architecture

Storage engine, Gremlin integration, RID format, generated code pipeline, and the table of key entry-point classes (`YourTracks`, `YouTrackDB`, `ServerMain`, `GlobalConfiguration`, `DiskStorage`, `AbstractStorage`, etc.) are documented in `.claude/docs/architecture.md`. Load on demand when the change actually touches storage / Gremlin / parser / generated-code areas.

## Code Style

- **Indent**: 2 spaces (Java, XML, JSON, etc.)
- **Continuation indent**: 4 spaces
- **Line width**: 100 characters
- **Braces**: Always required for `if`, `while`, `for`, `do-while` (force braces = always)
- **Imports**: No wildcard imports (threshold set to 999); import order: static imports first, then regular imports (enforced by Spotless)
- **Wrapping**: Wrap if long for parameters, extends, throws, method chains, binary/ternary operations
- **Binary operators**: Sign on next line when wrapping
- **Blank lines**: 1 blank line after class header, max 1 blank line in code

### Comments and Documentation
- **Comment non-obvious code**: Add comments to any logic that is not immediately self-evident, so reviewers can easily verify intent without reverse-engineering the code.
- **Test descriptions**: Every test must have a detailed description (in a comment or descriptive method name) explaining what scenario is being tested and what the expected outcome is, so a reviewer can quickly grasp the purpose.
- **Keep comments in sync**: When modifying code, always update the surrounding comments to match the new behavior. Stale or contradictory comments are worse than no comments.

### Formatting (Spotless)

Code formatting is enforced by [Spotless](https://github.com/diffplug/spotless) (`com.diffplug.spotless:spotless-maven-plugin`), which runs the `check` goal automatically during the `process-sources` phase of every build. Builds will fail if formatting violations are found.

- **Formatter**: Eclipse formatter configured in `project-config/eclipse-formatter.xml`
- **Ratchet mode**: Only files changed since the `spotless-baseline` git tag are checked — existing code is not reformatted
- **Import order**: Static imports first (`\#`), then regular imports
- **Excludes**: Generated code (`**/internal/core/sql/parser/**`, `**/generated-sources/**`, `**/generated-test-sources/**`)

```bash
# Check formatting (runs automatically during build)
./mvnw spotless:check

# Auto-fix formatting violations
./mvnw spotless:apply

# Check/fix for a single module
./mvnw -pl core spotless:check
./mvnw -pl core spotless:apply
```

**After modifying code, always run `./mvnw -pl {module} spotless:apply`** before committing to ensure formatting compliance. If the build fails with a Spotless error, run `spotless:apply` to auto-fix.

## Writing Style for Design Docs and Issues

The default LLM register is verbose, hedging, list-heavy, and full of "AI tells" (delve, leverage, robust, "It's not X — it's Y", em-dash overuse). Documents in this register are slow to read and reviewers ignore them. Use the **House Style** output style at `.claude/output-styles/house-style.md` whenever you draft:

- **All Markdown files in the repo** — every `*.md` surface is in scope. This includes ADR / design documents under `docs/adr/**` (the `_workflow/` drafts during a branch's lifetime, plus the final `adr.md` and `design-final.md` that survive the squash-merge), GitHub issue bodies you write for the user to post (e.g., the `issue-*.md` scratch files at the repo root), `CLAUDE.md` and module `README.md` files, workflow specifications under `.claude/workflow/**`, review reports, track files, status updates, and any other Markdown drafted during a session.
- **PR titles and descriptions** — the squashed-commit message is built from these, so they go straight into git history.
- **Commit message bodies** — durable git history; the imperative subject line stays terse, the body uses house-style prose.
- **YouTrack issue bodies** created via the YouTrack MCP tools.

The broader scope (all Markdown files plus the three non-Markdown surfaces above) mirrors the tier mapping in `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts`, which is the canonical anchor every workflow prompt, review agent, implementer file, and orchestrator file cross-references. Java and Kotlin source files get only the AI-tell subset at code-comment scale; see the conventions.md tier table for the specific sections that apply.

The output style is **mandatory style guidance for these artifacts, not optional**. Apply its rules (BLUF lead, banned-vocabulary list, em-dash cap, soft section length cap with template-bound exemptions — see `house-style.md § Structural rules` "Section length cap exception" and "Padding-based finding criterion" for the full clause, repo-anchored voice) whether or not the user has activated the style with `/output-style`. Read the file once at the start of any session that will produce documents in those paths; treat its self-check list as a pre-return checklist for every draft.

When the user wants the style toggled for the whole session (e.g., a session focused on writing an ADR), suggest `/output-style house-style`. Otherwise, just apply the rules silently.

## Testing

### Test Requirements
- **NEVER run multiple test processes simultaneously in the same worktree/directory.** Always wait for one `./mvnw test` or `./mvnw verify` invocation to finish before starting another in the same working directory. Running tests in parallel within the same worktree causes classloading errors, database file locking conflicts, and false test failures. This applies to all test execution — unit tests, integration tests, and coverage runs. Tests in separate worktrees/directories do not conflict.
- **All code changes must have associated tests** that cover the new or modified behavior.
- **All bug fixes must include a regression test** reproducing the bug, unless one already exists.
- Prefer adding tests to **existing test classes** when the change fits their scope. Only create new test classes when there is no suitable existing one.
- **Coverage target**: 85% line coverage and 70% branch coverage for new/changed code (enforced by CI coverage gate).
- **Coverage verification**: Always use the `coverage-gate.py` script (see [Pre-Commit Verification](#pre-commit-verification)) to check coverage instead of computing it by hand. The script contains special-case logic — for example, it excludes Java `assert` statement lines (including multi-line continuations) from both line and branch coverage calculations, because JaCoCo reports phantom uncovered branches and unreachable failure-message lines for asserts. Manual arithmetic will not account for these exclusions and will give incorrect results.

### Test Modules at a Glance
- **Unit tests**: `./mvnw -pl <module> clean test`. Core/server use JUnit 4 (`surefire-junit47` runner); the `tests` module uses JUnit 5 with `EmbeddedTestSuite` (shared DB, fixed class/method order via `@SelectClasses` / `@Order`).
- **Integration tests**: `./mvnw clean verify -P ci-integration-tests` (uses failsafe in `core` and `server`).
- **Test utilities**: `test-commons` provides `TestBuilder`, `TestFactory`, `ConcurrentTestHelper`.

For TinkerPop Cucumber feature-test details (~1900 scenarios), Docker tests, LDBC and legacy JMH benchmarks, and the per-test JVM properties (`bufferSize`, `createDefaultUsers`, `checksumMode`, `directMemory.trackMode`): see `.claude/docs/testing-details.md`.

## Git Conventions

### Branches
- **`develop` is the default development branch** for this project, not `main`.
- `main` - Used for delivery of artifacts once all tests on `develop` have passed (auto-merged from develop nightly after integration tests pass)

### Commit Messages
- Git hook `.githooks/prepare-commit-msg` auto-prepends YTDB issue prefix based on branch name
- **Format**:
  ```
  [Imperative summary, under 50 chars]

  [Detailed explanation of WHY this change was made — motivation, context,
  trade-offs. Not a restatement of the diff.]
  ```

### Force Pushing
- **Always use `--force-with-lease`** instead of `--force` when force pushing. This prevents accidentally overwriting commits pushed by others since your last fetch.

### Pull Requests
- **No merge commits** (enforced by CI - `block-merge-commits.yml`)
- PR title auto-prefixed with YTDB issue number from branch name
- Target branch: `develop`
- **1 PR = 1 squashed commit** — all branch commits are squashed on merge
- **Must use the PR template** at `.github/pull_request_template.md`. Every PR must include the Motivation section explaining WHY the change was made.
- **Keep the PR title and description in sync with follow-up commits.** The squashed commit message is built from the PR title and description, not from individual commit messages — update them with every push so the merge commit reflects all changes.
- **Test count gate bypass**: Add `[no-test-number-check]` to the PR title to skip the test count gate. Use this only for intentional test refactorings that restructure or consolidate tests without reducing coverage.

### Workflow Artifacts
- Some squashed commits include `docs/adr/<dir-name>/design-final.md` and `docs/adr/<dir-name>/adr.md` — post-implementation workflow artifacts documenting the final design and architectural decisions. These are the only workflow files that survive merge into `develop`.
- During the branch's lifetime the implementation plan, design draft, step files, reviews, and other working files are tracked under `docs/adr/<dir-name>/_workflow/` so the draft PR shows real-time progress and a local-disk loss never destroys planning work. The entire `_workflow/` directory is removed in a single cleanup commit at the end of Phase 4 (after `design-final.md` and `adr.md` land), so the squash-merge into `develop` carries only the durable artifacts plus the implemented code. See `.claude/workflow/conventions.md` §1.2 and `.claude/workflow/workflow.md` § Final Artifacts for the full lifecycle.

## Pre-Commit Verification

**Every task MUST be verified before committing.** Follow this workflow:

1. **Run unit tests** for the affected module(s) before committing:
   ```bash
   # If changes are in core module
   ./mvnw -pl core clean test

   # If changes span multiple modules, test all affected ones
   ./mvnw -pl core,server clean test

   # If unsure which modules are affected, run the full unit test suite
   ./mvnw clean package
   ```

2. **Run related integration tests** if the change touches areas covered by integration tests:
   ```bash
   # Run integration tests for the affected module(s)
   ./mvnw -pl core clean verify -P ci-integration-tests

   # Or run the full integration test suite
   ./mvnw clean verify -P ci-integration-tests
   ```

3. **Check coverage of changed code** by running tests with the `coverage` profile and verifying coverage locally:
   ```bash
   # Run unit tests with coverage collection
   ./mvnw clean package -P coverage

   # Check coverage of changed lines against thresholds (85% line, 70% branch)
   python3 .github/scripts/coverage-gate.py \
     --line-threshold 85 \
     --branch-threshold 70 \
     --compare-branch origin/develop \
     --coverage-dir .coverage/reports
   ```
   If coverage is below the threshold, add or improve tests for uncovered lines before committing.

4. **Do not commit if tests fail.** Fix the failures first, then re-run the tests.

5. **Determining which tests to run:**
   - Changes to `core` module: always run `./mvnw -pl core clean test`
   - Changes to `server` module: run `./mvnw -pl server clean test`
   - Changes to storage, WAL, or index code: also run integration tests (`-P ci-integration-tests`)
   - Changes to Gremlin integration or transaction handling: also run integration tests
   - Changes to `embedded` module: run `./mvnw -pl embedded clean test` (includes Cucumber feature tests)
   - Changes to `tests` module: run `./mvnw -pl tests clean test`
   - If in doubt, run the full test suite: `./mvnw clean package`

## Tips for Working with This Codebase

1. **Always use `./mvnw`** (Maven Wrapper) instead of system Maven
2. **The `core` module is massive** — most logic lives here. When searching, start in `core/src/main/java/`
3. **Don't edit files in `core/.../sql/parser/`** — they are generated from `YouTrackDBSql.jjt`
4. **Public API vs Internal**: Only classes in `com.jetbrains.youtrackdb.api` are public API. Everything under `internal` is implementation detail
5. **SPI pattern**: Engines, indexes, collations, SQL functions are loaded via `META-INF/services` (Java ServiceLoader)
6. **Custom TinkerPop fork**: The project uses its own fork of TinkerPop under `io.youtrackdb` group ID — don't confuse with upstream `org.apache.tinkerpop`
7. **Test infrastructure**: Core tests use JUnit 4; the `tests` module uses JUnit 5 (Jupiter) with JUnit Platform Suite for ordered execution
8. **The `lucene` module is excluded from the build** — it exists only as reference code for future reimplementation

The JaCoCo+`assert` coverage trap and the Gremlin annotation-processor build details are in `.claude/docs/architecture.md`.

## Documentation Sync

### When to Update Documentation

1. **When modifying source code**: Review docs in `docs/` and module `README.md` files to see if any cover the area you changed. Update them if needed.
2. **When adding new features**: If the feature affects public API, configuration, build process, or CI/CD, update the relevant docs.

## Context Window Monitor

Context window usage is tracked on-demand via a file written by the statusline script. No hooks or `additionalContext` are used — zero conversation pollution.

### Checking context usage on demand
```bash
cat /tmp/claude-code-context-usage-$PPID.txt
```
Output example: `ctx: 7% level=safe`

### Levels

| Level | Trigger | What it means |
|---|---|---|
| **safe** | <20% | Plenty of room. |
| **info** | 20% (~200K of 1M) | Context growing. Delegate exploration to subagents, avoid reading large files, write decisions to files. |
| **warning** | 30% (~300K of 1M) | Quality degradation likely. Save state to files, run `/compact` with key context, or delegate remaining work to subagents. |
| **critical** | 40% (~400K of 1M) | Severe degradation. Stop immediately, save all state to `/tmp/claude-code-session-state.md`, tell the user to `/clear` and resume. |

For the threshold rationale (Opus 4.7 retrieval data, calibration sources), statusline implementation, session isolation, and configuration paths: see `.claude/docs/context-monitor.md`.

The thresholds in this section MUST stay in sync with:
- `.claude/scripts/statusline-command.sh` — the line that writes `level=...`
- `.claude/workflow/workflow.md` — § Context Consumption Check (the level table)
- `.claude/workflow/track-review.md` and `.claude/workflow/track-code-review.md` — the inline "warning (≥30%) or critical (≥40%)" gates
- `.claude/workflow/implementation-review.md` — the State 0 inline gate
- `.claude/workflow/step-implementation.md` — the Phase B inline gate
- `.claude/workflow/prompts/create-final-design.md` — the Phase 4 inline gate
- `.claude/workflow/mid-phase-handoff.md` — § When this protocol fires (defines the trigger thresholds)

If you change a threshold here, grep for the others and update them in the same commit.

## MCP Steroid — IntelliJ IDE Control

When the `localhost-6315` MCP server (mcp-steroid) is connected, its skill guides are exposed as MCP resources and fetched via `steroid_fetch_resource` (resolve the exact `mcp-steroid://` URI from the server's resource list if needed — upstream source: https://github.com/jonnyzzz/mcp-steroid/tree/main/prompts/src/main/prompts/skill). Do NOT vendor or copy these guides into the project — fetch lazily; some are large (the Spring guide is ~100 KB).

The MCP server registry itself lives in user-global `~/.claude.json` under `mcpServers["localhost-6315"]` — the project does not own that entry. Everything else (rules, hooks, settings) is project-scoped under `.claude/`.

### Session-start preflight (mandatory)

A SessionStart hook (`.claude/hooks/mcp-steroid-probe.sh`, wired in `.claude/settings.json`) auto-probes mcp-steroid liveness at every session start and emits an `mcp-steroid:` status line as additional context at turn 1. **That status is the canonical IDE state for the entire session — treat it as load-bearing and do not re-probe.**

For any session that may involve Java code analysis (research, planning, or refactoring), the routing rules are:

- **`mcp-steroid: reachable`** → run `steroid_list_projects` once before the first symbol-related action. The hook only checks port liveness, not cwd-vs-open-project alignment, so this one tool call is still required to confirm the right project is open. If the relevant project is open and matches the working tree, route every symbol-usage question through PSI for the rest of the session.
- **`mcp-steroid: NOT reachable`** → IDE control is unavailable. Symbol audits use grep with explicit reference-accuracy caveats documented in every audit conclusion that depends on the result.
- **cwd mismatch** (`steroid_list_projects` reports a different open project than the working tree) → ask the user to switch the open project before running any load-bearing symbol audit. Do not silently fall back to grep.

**Design and research sessions are NOT exempt.** Design conclusions often hinge on reference-accuracy facts that grep can silently miss — e.g., "this method has no production callers", "this field is referenced only inside its declaring class", "this slot has no consumer that needs it." The cost of one `steroid_list_projects` call is trivial and amortizes across every subsequent symbol question, even when the session never edits code.

### Skill loading

The catalogue of `mcp-steroid://` skills (which one to fetch via `steroid_fetch_resource` for which task — PSI navigation, refactoring, VFS, threading, debugger, execute-code, apply-patch, Maven, Gradle, plus the "skip by default" list) is in `.claude/docs/mcp-steroid/skills.md`. Load only the skills needed for the current task; don't pre-load. Prefer mcp-steroid IDE operations (PSI search, refactor, execute-code) over raw `grep`/`Edit`/`Bash` when the IDE is connected and the task benefits.

### Grep vs PSI — when to switch (for Java code)

Default to PSI for **symbol-level questions** (callers, overrides, find-usages, type hierarchies); default to grep for **textual / filename / one-shot questions** (path globs, unique literals, error-message search, files outside the IDE-indexed project). **Load-bearing audits — anything driving a deletion, rename, signature change, or any action where a missed reference would break production code — MUST use PSI when the IDE is reachable.** Sub-agents default to grep, so delegations to Explore (or any sub-agent) MUST explicitly say *"use mcp-steroid PSI find-usages, not grep"* — an unannotated delegation routes through grep regardless of the question's shape.

For the full table, common grep traps PSI avoids (Javadoc/comment matches, polymorphic call sites, name collisions, post-rename stale results), and cost-awareness notes: see `.claude/docs/mcp-steroid/grep-vs-psi.md`.

### Maven — when to route through mcp-steroid

**Default:** keep `./mvnw` invocations on Bash. They're durable across IDE restarts, support `run_in_background`, and integrate with project scripts (`coverage-gate.py`, CI flags, JMH profiles). **Switch to `steroid_execute_code`** only for single-test reruns and compile-fix loops where the structured pass/fail tree and noise reduction are worth the IDE coupling. Anything >5 min, full-suite runs, integration/Docker tests, JMH benchmarks, coverage runs, and `dependency:tree` stay on Bash.

For the full routing table, the **mandatory two-call launch+poll pattern** (single-call gets cancelled by the ~60 s MCP HTTP timeout), the hard rules (`-am` is BANNED in IDE-routed Maven, surefire-summary-only reads, one test class per `-Dtest=…`, post-POM-edit re-import via `MavenProjectsManager.scheduleUpdateAllMavenProjects`), and JDK-selection notes for fresh containers: see `.claude/docs/mcp-steroid/maven.md`.

### Refactoring — IDE refactor vs raw Edit (Java)

For any Java refactor that updates more than one reference site (rename method/field/class/package, move class, change signature, extract method/variable, inline, pull-up/push-down, change inheritance), route through the **IDE refactoring engine via mcp-steroid**. Raw `Edit`/`sed`/text-replace silently breaks polymorphic call sites, generic dispatch, string-literal class names, Javadoc `{@link}`, and cross-module callers. For multi-site literal-text replaces that don't need the refactoring engine (e.g., across non-Java files or Spotless-only fixes), use `steroid_apply_patch` over chained native `Edit` calls — the native tool bypasses IntelliJ and leaves VFS / PSI / search indices stale.

For the full table (when to use IDE vs apply-patch vs raw Edit), preflight steps (clean working tree, project-cwd match), and YouTrackDB-specific reasons (custom TinkerPop fork shadowing `org.apache.tinkerpop` symbols, `internal/api` split, generated parser code, Gremlin DSL annotation processor): see `.claude/docs/mcp-steroid/refactoring.md`.

### Recipes

The catalogue of concrete IDE-control recipes lives in `.claude/docs/mcp-steroid/recipes.md`. Each recipe assumes mcp-steroid is reachable, the relevant project is open, and the working tree matches (preflight already done). Available recipes:

- **Safe delete** — production-caller gate before removing a method, field, or class (uses `ReferencesSearch` + `SafeDeleteProcessor`).
- **Inheritance hierarchy search** — every implementer of an interface or override of an abstract method (uses `ClassInheritorsSearch` + `OverridingMethodsSearch`); essential for SPI contract changes.
- **Call hierarchy** — multi-hop upward impact for low-level signature changes.
- **Structured test failure details / statistics** — read failures, stack traces, slow-test list directly from `RunContentManager` instead of parsing surefire XML.
- **IntelliJ inspections on changed code** — pre-PR pass for redundant casts, atomic-on-volatile, format-string mismatches, etc., scoped to `git diff --name-only origin/develop...HEAD`.
- **Project / module dependency graph** — answer "does X depend on Y?" without reading several `pom.xml` files.
- **Class-shape refactors** — extract-interface / pull-up / push-down for consolidating sibling-class duplication.
- **Add parameter via change-signature** — adding a parameter to a heavily-overridden method (SPI interface, abstract storage method) without missing polymorphic call sites.
- **Auto-open project (check-then-open-and-wait)** — open a closed project for sessions that need PSI access, then poll `steroid_list_windows` for readiness. Composes top-level tools (`steroid_list_projects` → `steroid_open_project` → `steroid_list_windows`); doesn't override the cwd-mismatch preflight rule.

Each recipe points at the matching `mcp-steroid://` resource(s) — fetch via `steroid_fetch_resource` and adapt rather than reconstruct from memory.
