# Track-Based Development: YTDB Deltas

The generic workflow ships with the `ytdb-slate` npm package. Read `track-workflow.md` and
`pr-publishing.md` for that protocol.

This document carries only YTDB-specific deltas. Draft pull request publishing is enabled in
`.pi/slate.json`.

## Base branch

The repository's default development branch is `develop`, not `main`. The umbrella PR
targets it, and track 01's base is the merge-base with it.

## Branch, title, and template conventions

- Branch names carry the YTDB issue number; CI auto-prefixes the PR title from the branch
  name. Title rules — multi-issue bracket lists, the `[no-test-number-check]` gate marker,
  per-push title/description sync — live in
  `docs-internal/agents/orchestrator-guidelines.md` § Git Conventions.
- The PR description uses `.github/pull_request_template.md`.
  This template instantiates the rules in `pr-publishing.md`.
  YTDB per-push synchronization makes the package measurements recur.
- The template has no Open questions subsection, so open questions carried over from the
  research log live in Risks & accepted trade-offs — which is also where the flip checklist
  wants each remaining one resolved, or its user-approved deferral recorded.

## Peer review

YTDB peer-review rules live in `docs-internal/agents/slate-doctrine-extra.md`.

## Verification integration

This section is the gate reference for work inside a track: which command gates a commit, how
the verification sets are computed, which gate runs when, and when a gate must be re-run. The
coverage measurement procedure — how to produce a coverage number, and how to tell a real pass
from a vacuous one — is in `docs-internal/dev-workflow/coverage-verification.md`.

Intermediate commits inside a track never reach `develop`, since one PR is one squashed commit,
so a per-commit unit-test and coverage rule buys latency on every step and protects nothing
that survives the merge. The cheap gate therefore runs on every commit, while the expensive
gate runs at least once per track — before that track's agent code review — and again before
the track's closing event whenever anything but prose landed in between.

### Mid-track commit gate

A commit inside a track requires exactly this to pass:

```bash
./mvnw -pl <modules with changed files> -am -amd test-compile
```

No unit tests, no integration tests, no coverage. `test-compile` rather than `compile` because
it compiles test sources — under bare `compile` test code rots silently for the length of a
track — and because it still triggers Spotless and all source generation.

**`-am` is mandatory.** Without it a `-pl` build resolves siblings from the local repository
instead of the working tree, so a stale installed snapshot can report green over code that does
not compile against the tree.

**`-amd` is mandatory too, and is not interchangeable with `-am`.** `-am` (`--also-make`) adds
upstream dependencies; `-amd` (`--also-make-dependents`) adds the downstream modules that
depend on the named ones, which are otherwise never compiled against the change. On a widely
consumed module such as `core` this expands to a near-reactor-wide `test-compile`; that is the
intended price of the gate.

**Shading exception.** A change affecting the `embedded` module's shaded artifact replaces the
phase, because shading binds to `package` and `test-compile` never exercises it:

```bash
./mvnw -pl embedded -am -amd package -DskipTests
```

`-DskipTests` is required: a bare `package` runs surefire, including `embedded`'s Cucumber
suites, turning the compile gate into a full test run. Not `-Dmaven.test.skip=true`, which also
skips test compilation.

**Build files that belong to no module.** The root `pom.xml`, `.mvn/jvm.config` and
`project-config/eclipse-formatter.xml` affect every module and live in none, so `-pl` has
nothing to name. Their compile gate is reactor-wide:

```bash
./mvnw test-compile
```

**No-Maven changes.** A change with no Java files and no module content — documentation, `.pi/`
configuration, prompts — carries no Maven gate at all.

### Three gate sets: compile, test, and integration

Verification scope is not one set. Carrying the compile-gate definition into a test gate
silently converts that gate into a reactor-wide test run. The three names below distinguish
each scope.

**Compile-gate set** — the modules containing changed files **plus their downstream consumers
in the reactor**, which is what `-am -amd` expresses. Deliberately wide, and not to be
narrowed: a green `-pl core -am test-compile` says nothing about whether `server` still
compiles.

**Test-gate set** — the modules containing changed files, plus any downstream module whose own
tests exercise the changed behavior. No automatic `-amd` fan-out here: a dependent that merely
compiles against a changed API is already covered by the compile gate, and re-running its
suites costs tens of minutes for no new signal. When the changed behavior does reach a
dependent's tests, name that dependent in `-pl` explicitly.

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

First, search by package proximity. These commands search the same package in the same module:

```bash
changed='<module>/src/main/java/<package>/<Class>.java'
module=${changed%%/*}
package_path=${changed#*/src/main/java/}
package_path=${package_path%/*}
test_root="$module/src/test/java"
[ ! -d "$test_root/$package_path" ] ||
  find "$test_root/$package_path" -type f -name '*IT.java' -print | sort
```

If this returns nothing, remove one trailing segment with
`package_path=${package_path%/*}` and repeat the guarded `find` command.

Second, search integration test sources for references to the changed class's simple name:

```bash
simple_name=$(basename "$changed" .java)
[ ! -d "$test_root" ] ||
  grep -Rlw --include='*IT.java' -- "$simple_name" "$test_root" | sort
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

For build files belonging to no module, the compile-gate set is the whole reactor and the
test-gate set is the modules whose behavior the change can actually alter — a dependency-version
bump in the root `pom.xml` means that dependency's consumers — falling back to the whole reactor
when it cannot be bounded that way.

The reactor order determines the two module sets. Maven resolves this order from the dependency
graph, not from the module order listed in the root `pom.xml`:

> youtrackdb-parent → test-commons → gremlin-annotations → core → driver → server → tests
> → embedded → examples → console → docker-tests → jmh-ldbc

Everything to the right of a changed module is a candidate consumer; `-amd` computes the actual
ones at the compile gate, and the order is what lets you bound a test-gate set by hand.

### End-of-track gate

At the end of each track's implementation, and **before** that track's agent code review, the
full verification runs:

1. **Unit tests** for the test-gate modules, green.
2. **Integration tests** for the integration-gate set, green. Follow
   `docs-internal/agents/thread-guidelines.md` for command syntax. If the set remains uncertain,
   record the searches and results in the thread report. The orchestrator then chooses the
   verification path.
3. **The coverage gate** over the changed lines, at the thresholds owned by
   orchestrator-guidelines § Test Policy. **Read
   `docs-internal/dev-workflow/coverage-verification.md` and follow it before producing the
   number.** A coverage result produced without that procedure is not a gate result and must
   not be reported as one: its report-set assertion is what separates a measured pass from a
   vacuous one, and nothing in this section can tell them apart.

The full local integration suite is no longer a gate for any change class. It takes about five
hours.
Integration tests run unless the pull request is a draft. Worker threads do most work during the
draft phase. The pipeline skips integration tests for a pull request whose branch lives in a
fork.

A title tag is a bracketed keyword in the pull request title. The `[no-it-tests]` title tag means
no integration tests and skips that pipeline run. Use it only when the change cannot affect
integration tests.

Gating only at the track's closing event would have reviewers reviewing unverified code; gating
only before the review would let review-fix commits land unverified. Both holes are closed by
this pre-review placement plus the re-run rule below.

**This applies to every change class, including single-track changes.** A single-track change
has no marker commit. It still runs the gate before any required agent code review.

### Re-running the gate before the track's closing event

Every track has a **closing event**: the marker commit for a track inside a multi-track change,
and the ready-for-review flip for a single-track change, which has no marker commit. Keying the
obligation to the closing event gives a single-track change a verification trigger.

The gate must be re-run before the closing event **unless every commit landed since the gate is
provably outcome-neutral — documentation or comments only.** It is an exclusion rule rather than
an allowlist of safe paths, so build-affecting non-source files (module POMs, `.mvn/jvm.config`,
formatter configuration) are covered by construction. If you cannot show that the only thing
that changed was prose, re-run.

**Approval reopening.** The approval in question is the MANDATORY user review of the track
(`.pi/npm/node_modules/ytdb-slate/docs/track-workflow.md`). This workflow
has no separate "gate approval" event. Any commit landing after that review reopens it for those
commits, before the closing event — neither a marker commit nor a ready-for-review flip ever
certifies code the user has not seen.

### Deferred test authorship

When test work becomes substantial, the orchestrator may split it into its own track without a
user approval gate. The test track lands before review of the affected behavior. Test work
cannot move to a follow-up issue or pull request. The coverage gate has no bypass for that debt.

### Committing, and landing red

Never commit over a red result you actually observed. **Landing red is the one carve-out**, and
not one the agent may take on its own: it requires explicit user approval, recorded in the PR's
Risks & accepted trade-offs, naming the failing tests and the track that will fix them. The
track may then close over precisely the named red result; any red result not named there still
blocks the commit.

### After the flip

Any post-flip commit that touches code runs the test-gate modules' tests before being pushed.
Once the PR is non-draft CI enforces on every push regardless, including the coverage gate,
which runs only on non-draft PRs and so re-engages at the flip. During the draft phase there is
no CI at all, which is why the end-of-track gate is the only verification signal a track has
while it is being implemented.

### Encouraged, never required

Running the single closest test class mid-track for a risky change. It is cheap, it catches the
obvious break early, and no gate depends on it.

## MCP server configuration

The `pi-mcp-adapter` package needs a machine-local server configuration, in the same way that
model routing needs a machine-local `models.json`. Setup, credential handling, and the current
worker-thread limitation live in `mcp-server-configuration.md` in this directory.
