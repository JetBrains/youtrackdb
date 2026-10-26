# Track-Based Development: YTDB Deltas

This document carries YTDB-specific workflow rules. Draft pull request publishing is enabled in
`.pi/slate.json`.

## Base branch

The default development branch is `develop`, not `main`.
The `main` branch delivers artifacts. It receives nightly automatic merges from `develop`
after all unit and integration tests pass.

## Branch, title, and template conventions

- Before naming a branch, ask the user whether this change has a YTDB issue. Reuse an
  applicable prior answer. An issue number is optional. With an issue, start the branch name
  with `YTDB-<number>`, as in `YTDB-123-short-name`. CI adds the issue prefix to the PR title.
  Without an issue, CI leaves the title unchanged. Individual commit subjects need no issue number.
- For multiple issues, list all issue IDs in the title, separated by commas and wrapped in
  square brackets: `[YTDB-123, YTDB-456] <summary>`.
- CI forbids merge commits through `.github/workflows/block-merge-commits.yml`.
  Each pull request merges as one squashed commit.
- Use `--force-with-lease`, never `--force`, for force pushes.
- The PR description uses `.github/pull_request_template.md`.
- Add `[no-test-number-check]` to the PR title only for intentional test refactorings that
  restructure or consolidate tests without reducing coverage. This skips the test count gate.

## Peer review

The YTDB review charter lives in `docs-internal/agents/review-perspectives.md`.

## Rebase conflict resolution

When a rebase produces conflicts in prose-heavy files, reread every resolved file from start
to end before continuing. Check the whole document, not just conflict hunks. Unchanged
paragraphs can refer to rules renamed or removed on the other side.

## Verification integration

This section is the gate reference for work inside a code track: which command gates a commit, how
the verification sets are computed, which gate runs when, and when a gate must be re-run. The
coverage measurement procedure — how to produce a coverage number, and how to tell a real pass
from a vacuous one — is in `docs-internal/dev-workflow/coverage-verification.md`.

Intermediate code-track commits do not reach `develop` individually. Each level pull request
merges as one squashed commit. Running unit tests and coverage checks on every commit adds
delay without protecting a separately merged result. The compile gate runs on every applicable
commit. The unit-test and coverage gate runs before each code track's agent code review.
Re-run it before the closing event when anything but prose lands after it.

### Mid-track commit gate

A commit inside a code track that changes Java or module content must pass the full-reactor
compile gate:

```bash
./mvnw package -DskipTests
```

The full reactor builds every upstream dependency and downstream consumer from the working
tree. No module compiles against an installed sibling snapshot. Do not narrow this gate with
`-pl`, `-am`, or `-amd`.

The `package` phase creates the shaded artifacts in `gremlin-shaded` and `embedded` that
consumers need. It also compiles test sources and runs Spotless and source generation.
Bare `compile` does not compile test sources.

Use `-DskipTests`, not `-Dmaven.test.skip=true`, which also skips test compilation.
A bare `package` runs unit tests, including `embedded`'s Cucumber suites.
The compile gate runs no unit tests, integration tests, or coverage checks.

**Build files that belong to no module.** The root `pom.xml`, `.mvn/jvm.config` and
`project-config/eclipse-formatter.xml` use the same full-reactor compile gate.

**No-Maven changes.** A change with no Java files and no module content — documentation, `.pi/`
configuration, prompts — carries no Maven gate at all.

### Three gate sets: compile, test, and integration

Verification scope is not one set. Carrying the compile-gate definition into a test gate
silently converts that gate into a reactor-wide test run. The three names below distinguish
each scope.

**Compile-gate set** — the full reactor. Build it with `./mvnw package -DskipTests`
so every dependency and consumer compiles against the working tree.

**Test-gate set** — only the reactor modules containing changed files. A developer machine
runs tests only in this set. Do not add upstream or downstream modules to it.
This rule also excludes a downstream module whose tests exercise the changed behavior.
The pull request build owns those tests. This keeps a local change from requiring almost
all module suites.

The pull request build runs the whole unit suite, including the full disk-mode suite.
A draft pull request runs no continuous integration (CI).
For build-relevant changes, the whole unit suite runs at the ready-for-review flip.
A manual workflow run also runs the whole unit suite. Markdown-only changes skip the build.
Integration tests follow the rules below.

A developer machine never runs the full unit suite in disk mode. Disk mode means starting the
run with `-Dyoutrackdb.test.env=ci`. The targeted subset stays inside the test-gate set.
It includes tests covering changed production code and tests changed by the same work.
Local verification relies on the pull request build for wider disk-mode coverage.

A developer machine never runs an integration test. The pull request pipeline skips integration
tests for drafts or Markdown-only changes. When the changed-file list is known, it also skips
them if no reactor module has both a changed `.java` file and integration test sources. The
exact marker `[no-it-tests]` skips them when it appears in the first line of the head commit
message on a same-repository pull request.
Fork pull requests cannot use the marker.

**Integration-gate set** — the integration test classes covering the subsystems touched by the
changed files. This set names classes, not modules, because module scope alone still runs the
whole module suite.

A changed or added integration test class always belongs to the integration-gate set before any
search runs.

The two searches below apply only to main source files inside a module with a package path.
Do not pass other changed files to them. Report each other path and its potential effect for an
orchestrator decision.

Other files include a build file such as `pom.xml`, anything under `.mvn/`, a Maven wrapper, a
workflow file, or a documentation file. A root build file can change behavior in every module.
No local integration subset is trustworthy for that case. The thread reports the situation for
pull request verification.

Derive the integration-gate set with two searches for each applicable file. Use this process
for storage, write-ahead log (WAL), index, Gremlin integration, transaction handling, and any
other subsystem.

Every module, including the copied TinkerPop modules, uses the same four integration source
patterns: `IT*.java`, `*IT.java`, `*ITCase.java`, and `*IntegrateTest.java`.
The copied unit runner excludes these names, and its integration runner includes them.
Both searches, CI selection, and failure-gate inference use this set.

First, search by package proximity. These commands search the same package in the same module:

```bash
changed='<module>/src/main/java/<package>/<Class>.java'
module=${changed%%/*}
package_path=${changed#*/src/main/java/}
package_path=${package_path%/*}
test_root="$module/src/test/java"
[ ! -d "$test_root/$package_path" ] ||
  find "$test_root/$package_path" -type f \( -name 'IT*.java' -o -name '*IT.java' \
    -o -name '*ITCase.java' -o -name '*IntegrateTest.java' \) -print | sort
```

If this returns nothing, remove one trailing segment with
`package_path=${package_path%/*}` and repeat the guarded `find` command.

Second, search integration test sources for references to the changed class's simple name:

```bash
simple_name=$(basename "$changed" .java)
[ ! -d "$test_root" ] ||
  grep -Rlw --include='IT*.java' --include='*IT.java' --include='*ITCase.java' \
    --include='*IntegrateTest.java' -- "$simple_name" "$test_root" | sort
```

Combine and deduplicate both result sets. Review each candidate and include every class that
exercises the changed behavior.

For example, use `FreeSpaceMap.java` as the changed file:

```bash
changed='core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/FreeSpaceMap.java'
```

The package search returns:

```text
core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/FreeSpaceMapTestIT.java
core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/LocalPaginatedCollectionV2TestIT.java
```

The reference search returns:

```text
core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/FreeSpaceMapTestIT.java
```

When both searches return nothing, report the commands and empty results. Do not run the full
suite. Do not silently assume that integration coverage is absent.

A build file inside a reactor module puts that module in the test-gate set.
A build file belonging to no module adds no module to the test-gate set.
For example, a root `pom.xml` change requires no module test run on a developer machine.
It relies on the pull request build for tests. Its compile-gate set stays the whole reactor.
Other changed files inside modules still select those modules for the test-gate set.

The reactor order describes the full-reactor compile-gate set.
Maven resolves this order from the dependency graph, not from the module order in the root
`pom.xml`:

> youtrackdb-parent → tinkerpop-parent → gremlin-shaded → gremlin-language → gremlin-core
> → gremlin-test → tinkergraph-gremlin → gremlin-util → test-commons → gremlin-annotations
> → gremlin-groovy → gremlin-socket-server → gremlin-driver → gremlin-server → gremlin-console
> → core → driver → server → tests → embedded → examples → console → docker-tests → jmh-ldbc

The copied modules add upstream dependencies to the graph. Maven places `gremlin-core`
before `gremlin-annotations`, which supplies the processor for `gremlin-groovy`.
Read the current Reactor Build Order from Maven when checking build dependencies.
The compile gate builds all modules in that order. Reactor order does not select test-gate
modules. Select them from the changed-file paths.

### End-of-track gate

At the end of each code track's implementation, and **before** its agent code review, the
full verification runs:

1. **Unit tests** for the test-gate modules, green.
2. **Integration tests** run in the pull request pipeline for reactor modules with a changed
   `.java` file and integration test sources when the changed-file list is known. The skip rules
   above still apply. The pipeline can miss integration-gate classes in dependent modules.
   The Failsafe report gate requires each expected module to execute at least one non-skipped
   test. Individual skipped tests are allowed when that module also executes tests.

   Develop runs the full suite after merge. A developer machine never runs an integration test.
   If the integration-gate set remains uncertain, record the searches and results in the thread report. The
   orchestrator then chooses the verification path.
3. **The coverage gate** over the changed lines, at the thresholds owned by
   `docs-internal/dev-workflow/coverage-verification.md` § Shared coverage rule. **Read
   `docs-internal/dev-workflow/coverage-verification.md` and follow it before producing the
   number.** A coverage result produced without that procedure is not a gate result and must
   not be reported as one: its report-set assertion is what separates a measured pass from a
   vacuous one, and nothing in this section can tell them apart.

The full integration suite is not a gate for any proved focus area. The full integration
test suite usually takes several hours. A developer machine never runs an integration test.

The integration-gate set above selects classes that cover changed behavior. The pull
request pipeline uses a different rule. It runs all integration tests in each reactor module
with both a changed `.java` file and integration test sources. It does not add dependent modules.

A Java path outside the reactor selects no module. Other file types do not start the pull request
integration job. If the changed-file list is unknown, the pull request runs the full suite.
Manual runs stay full. Develop runs the full suite after merge.

The Maven Failsafe plugin runs integration tests in Java virtual machines (JVMs).
The Maven property `it.storage.callFsync` controls storage file synchronization (fsync) in
these JVMs. It defaults to `true`, so local builds keep fsync enabled.
GitHub integration test runs pass `-Dit.storage.callFsync=false` to disable it.
The small-cache integration job uses the same flag. The WAL retention test enables fsync in
its own database configuration. This property does not change unit tests or Docker-based tests.
Production keeps fsync enabled by default.

Integration tests do not run for a draft pull request or when every changed file is a Markdown
file. A merge queue orders approved pull requests for merging. A merge group temporarily
combines changes GitHub tests before a merge queue writes them to the target branch. Merge groups
do not rerun integration tests because the pull request ran them for its changed modules.
Develop runs the full suite after merge. Worker threads do most work during the draft phase.

The exact lowercase marker `[no-it-tests]` skips integration tests when it appears in the first
line of the head commit message. The marker comparison respects letter case and works only for a
pull request from the same repository. Use it only when the change cannot affect integration
tests. It is a commit-subject marker, not a pull request title tag.

GitHub applies required fork approval before any workflow job starts. The repository setting
**Approval for running fork pull request workflows from contributors** has the current API value
`all_external_contributors`. Under this policy, GitHub checks the pull request author and the
actor who triggered the event.

An external contributor is not a repository member or owner and does not belong to the JetBrains
organisation. JetBrains organisation members are not external contributors. Their workflows from
personal forks do not wait for this approval.

Verify the value with this command:

```bash
gh api repos/JetBrains/youtrackdb/actions/permissions/fork-pr-contributor-approval
```

Changing that repository setting can remove the approval control. When approval is required, the
pull request page shows **Awaiting approval**. A maintainer with write access must approve the
workflow run. Every new push starts another workflow run, and GitHub evaluates approval for that
run.

The run conditions above apply after GitHub grants approval. Fork gate failures provide details
in the job log because fork workflows cannot write pull request comments.

Gating only at the track's closing event would have reviewers reviewing unverified code; gating
only before the review would let review-fix commits land unverified. Both holes are closed by
this pre-review placement plus the re-run rule below.

**This applies regardless of proved focus areas, including single-track changes.** A
single-track change has no marker commit. It still runs the gate before any required agent
code review.

### Re-running the gate before the track's closing event

Every code track has a **closing event**. Draft publishing uses one pull request per level
with code tracks. A multi-track change closes each code track at its marker commit, with or
without draft publishing. A single-track change with a draft pull request closes at the
ready-for-review flip. Without draft publishing, a single-track change closes at final
acceptance. This boundary gives each code track a verification trigger.

The gate must be re-run before the closing event **unless every commit landed since the gate is
provably outcome-neutral — documentation or comments only.** It is an exclusion rule rather than
an allowlist of safe paths, so build-affecting non-source files (module POMs, `.mvn/jvm.config`,
formatter configuration) are covered by construction. If you cannot show that the only thing
that changed was prose, re-run.

### Deferred test authorship

A separate test track must land before review of the affected behavior. Test work cannot move
to a follow-up issue or pull request. The coverage gate has no bypass for that debt.

### Committing, and landing red

Supply the intended commit message when dispatching a commit task.

Never commit over a red result you actually observed. Landing red is the only exception.
It requires explicit user approval in the owning research log and delivery package.
Name the failing tests and the track that will fix them. The track may close over only that
named red result. Any other observed red result still blocks the commit.

### After the flip

Any post-flip commit that touches code runs the test-gate modules' tests before being pushed.
Once the PR is non-draft CI enforces on every push regardless, including the coverage gate,
which runs only on non-draft PRs and so re-engages at the flip. During the draft phase there is
no CI at all, which is why the end-of-track gate is the only verification signal a track has
while it is being implemented.

### Encouraged, never required

Run the single closest test class mid-track when a proved focus area warrants it. This can
catch a failure early. No gate depends on it.
