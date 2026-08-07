# Track-Based Development: YTDB Deltas

The generic track-based workflow protocol no longer lives in this repository. It ships with
the `ytdb-slate` npm package (pinned in `.pi/settings.json`) as two documents, cited by
absolute path in the orchestrator doctrine so agents can read the authoritative text on
demand:

- **track-workflow.md** — research phase, lazy research log, mandatory user design review,
  mandatory pre-implementation adversarial review, the per-track loop (agent code review →
  mandatory user review → marker commit), marker-commit mechanics, and the change-size
  scaling table.
- **pr-publishing.md** — umbrella draft PR mechanics: creation before implementation,
  description rules (Motivation, Planned changes, Tracks, plus the 16,384-UTF-8-byte body
  target and the size exception that must carry measurements), ready-for-review flip
  checklist, user-performed merge.

This document carries ONLY the YTDB-specific deltas layered on that baseline. Draft-PR
publishing is ENABLED for this repository (`workflow.draftPRs` in `.pi/slate.json`), so
pr-publishing.md applies in full: every change gets an umbrella draft PR before
implementation, and the research log folds into its description.

## Base branch

The repository's default development branch is `develop`, not `main`. The umbrella PR
targets it, and track 01's base is the merge-base with it.

## Branch, title, and template conventions

- Branch names carry the YTDB issue number; CI auto-prefixes the PR title from the branch
  name. Title rules — multi-issue bracket lists, the `[no-test-number-check]` gate marker,
  per-push title/description sync — live in
  `docs-internal/agents/orchestrator-guidelines.md` § Git Conventions.
- The PR description follows the repository template at `.github/pull_request_template.md`,
  which instantiates the generic description rules owned by pr-publishing.md (Motivation,
  Planned changes with its subsections and hard guards, Tracks). What the template does NOT
  carry, and pr-publishing.md § Description rules still requires: keep the description body
  at or below 16,384 UTF-8 bytes (a target, not a gate — measure the GitHub API `body`
  string, never a formatted `git log %b`), and when it runs over, record a size exception in
  Risks & accepted trade-offs carrying the measurements — the overrun, every top-level
  section's byte count, and the before-count of anything condensed. Measure at the
  ready-for-review flip, after every post-flip description change, and again at the final
  handoff for merge (pr-publishing.md § After the flip); YTDB's per-push title/description
  sync, per the bullet above, is what makes those points recur.
- The template has no Open questions subsection, so open questions carried over from the
  research log live in Risks & accepted trade-offs — which is also where the flip checklist
  wants each remaining one resolved, or its user-approved deferral recorded.

## Peer review

YTDB layers an optional peer-review step on the baseline via the slate doctrine
extension (`doctrineExtraPath` → `docs-internal/agents/slate-doctrine-extra.md`). Peer
review is optional and, when the user wants it, runs directly on the ready umbrella PR at
the ready-for-review flip — there are no separate review branches or PRs. The rule that
layered peer review supplements, never replaces, the mandatory per-track user review is
owned by track-workflow.md § Peer review.

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

The full local integration suite is no longer a gate at any tier. It takes about five hours.
Integration tests run unless the pull request is a draft. Worker threads do most work during the
draft phase. The pipeline skips integration tests for a pull request whose branch lives in a
fork.

A title tag is a bracketed keyword in the pull request title. The `[no-it-tests]` title tag means
no integration tests and skips that pipeline run. Use it only when the change cannot affect
integration tests.

Gating only at the track's closing event would have reviewers reviewing unverified code; gating
only before the review would let review-fix commits land unverified. Both holes are closed by
this pre-review placement plus the re-run rule below.

**This applies at every tier, including single-track changes.** A single-track change has no
marker commit, but it still runs the gate before its agent code review, not merely before the
ready-for-review flip.

### Re-running the gate before the track's closing event

Every track has a **closing event**: the marker commit for a track inside a multi-track change,
and the ready-for-review flip for a single-track change, which has no marker commit. Keying the
obligation to the closing event is what keeps the single-track tier — the most common one — from
having no trigger at all.

The gate must be re-run before the closing event **unless every commit landed since the gate is
provably outcome-neutral — documentation or comments only.** It is an exclusion rule rather than
an allowlist of safe paths, so build-affecting non-source files (module POMs, `.mvn/jvm.config`,
formatter configuration) are covered by construction. If you cannot show that the only thing
that changed was prose, re-run.

**Approval reopening.** The approval in question is the MANDATORY user review of the track
(`.pi/npm/node_modules/ytdb-slate/docs/track-workflow.md` § Track loop, step 4); this workflow
has no separate "gate approval" event. Any commit landing after that review reopens it for those
commits, before the closing event — neither a marker commit nor a ready-for-review flip ever
certifies code the user has not seen.

### Deferred test authorship

When test work for a track is substantial enough to be split out, it becomes **its own task
within the same track**, landing before that track's agent code review. Promoting it to a track
of its own requires explicit user approval. It cannot be deferred to a follow-up issue or PR:
the CI coverage gate hard-fails a ready PR with no bypass, so the debt cannot leave this PR.

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

## Model routing

Action-level model routing is ENABLED for this repository (`router` in `.pi/slate.json`).
The mechanics — guards, resolution, effort ladders, per-model profile data — are owned by
the package's model-routing.md; this section records only the choices this project made and
what follows from them.

**The candidate list.** Four models are routable: `anthropic/claude-opus-5`,
`anthropic/claude-sonnet-5`, `openai/gpt-5.6-sol`, `openai/gpt-5.6-luna`.
`openai/gpt-5.6-terra` is excluded: its shipped profile marks it never-auto-select with no
defensible routing niche, and every routable model adds a row to the routing table Slate
renders into the orchestrator doctrine on EVERY turn, so listing a model no action should
land on spends context every turn and buys nothing. `anthropic/claude-sonnet-5` is listed
despite carrying the same never-auto-select marker, solely for the one niche its profile
names: only if sol is unavailable, at `high`. `anthropic/claude-fable-5` is excluded on two
counts: never-auto-select, and it has no zero-data-retention option.

**Maintaining the list.** The routable set is CLOSED to the nine models the package ships
profiles for — project-supplied profiles do not exist — so adding a model takes a ytdb-slate
release, not a config edit. Write canonical `provider/id` specs only: the profile table's
alias spellings resolve a profile but are not routable specs, since pi's registry decides
what can be dispatched and most aliases are absent from it. A spec that differs by one
character is dropped as unprofiled or as unknown to the registry, leaving a three-model list
that still routes — read the warnings, or the typo passes for a working config. If every
entry drops, the router turns OFF entirely and dispatches fall back to the host session's
model.

**When warnings appear.** Only config-SHAPE checks run at session start. On the `router`
side those are a value that is not an object, an unknown key under it, a non-array `models`,
a malformed entry, and a non-boolean `allowUnmeasuredEffort` or `showWarnings`. This project
enables `writing` too, which adds a malformed `writing` object, an unknown key under it, a
non-boolean `check` or `remind`, and a `remindPercent` that is not a finite number in
(0, 100]. `threadChoice` is the exception: Slate reads its shape at the first continuation
dispatch, not at session start. A fault there reaches that dispatch's warnings, and the
acceptance check below never sees it. Candidate resolution — profile lookup, registry and
credential checks, effort-ladder readability, failover coverage — is memoized and runs at
the FIRST consultation instead: the doctrine build in orchestrator mode, or the first
dispatch outside it. A session that builds no doctrine and dispatches nothing emits none of
those warnings, which is what the acceptance check below has to work around. Resolution
warnings now carry a class, and Slate gates only one of the two. A configuration fault
always reaches the user, and a candidate with no `modelFailover` entry is one.
`router.showWarnings: true` is what reveals the model data notes, and the package's
model-routing.md owns which warning is which.

**Effort policy.** `router.allowUnmeasuredEffort` is `true`. Slate therefore warns about an
explicit level that sits on the model's ladder but carries no capability measurement, rather
than refusing it. The dispatch runs. A ⚠ notice calls the result unevidenced. Slate marks the
episode header `(unmeasured level)`. Only a level the dispatch names can draw that notice or
a refusal. Every model on this project's candidate list carries measured levels, so a
dispatch that omits `effort` gets one of them. A failover retry is the exception. It names no
level, so it can land on an unevidenced one. The episode header then carries no marker to say
so. What failover ignores records that case. Two nearby refusals come from elsewhere and
stand whatever this key says: a level off the model's ladder, and one the provider rejects
outright. One rule alone keeps a review or a gate action on a measured level: the Model
routing rule in `docs-internal/agents/slate-doctrine-extra.md`. The package states it as
doctrine only, not a code-enforced guard.

**Unsized dispatches.** A dispatch that names neither `model` nor `effort` runs on the
thread's base. For a NEW thread that base is `openai/gpt-5.6-luna@medium` — the cheapest
preferred candidate at its lowest measured level. A thread that predates the router is the
exception worth knowing: when its stored pre-router pin is itself a listed model, the router
keeps that pin as the base and derives that model's own lowest measured level, silently and
with no warning — a thread pinned to `claude-opus-5` bases to `claude-opus-5@low`. Only a
base that is absent or off-list is re-seeded, and a re-seed does warn. Either way the router
lowers the pre-router floor of `claude-opus-5@xhigh` (`.pi/settings.json`), and no candidate
list restores it: even a list of `claude-opus-5` and `openai/gpt-5.6-sol` alone bases a new
thread to `claude-opus-5@low`. The compensating discipline is the Model routing rule in
`docs-internal/agents/slate-doctrine-extra.md`, which Slate does not enforce in code — name
both arguments on every action that needs more than the base.

**Episode compression.** `episodeModel` is pinned to `anthropic/claude-sonnet-5`. Left
unpinned it resolves from registry state — the newest available Anthropic Sonnet, by numeric
version comparison — so a future model release could move compression onto a model the
failover map does not cover, without saying so. The pin keeps that answer stable.

**The failover map.** `modelFailover` covers three consumers, and Slate checks only the
first: the router's candidates (an uncovered candidate produces one aggregate warning at the
first consultation), the orchestrator's own session model (`defaultModel` in
`.pi/settings.json`, today `claude-opus-5`), and the pinned `episodeModel`. Nothing warns
about the last two, and dropping a model from `router.models` does not end its need for cover
while it is still one of them. The current pairing keeps every route cross-provider, so one
provider's outage cannot take out both sides of a pair: `claude-opus-5 → gpt-5.6-sol` (which
also covers the orchestrator session), `claude-sonnet-5 → gpt-5.6-sol` (also the episode
compressor), `gpt-5.6-sol → claude-opus-5`, `gpt-5.6-luna → claude-opus-5`.

**What failover ignores.** A failover switch bypasses the list and effort guards entirely —
the package's deliberate carve-out, since a model that just failed is worse than an unlisted
one that works — and it requests no level, so pi re-applies the session's current level to
the fallback model. The episode header drops its `(unmeasured level)` marker whenever the
model changed mid-action, so an unmeasured retry level leaves no trace there either. Each
pair above retries on a level its target has a measurement at, with one accepted residual:
`claude-opus-5@low` retries as `gpt-5.6-sol@low`, and sol carries no measurement at `low`.

**Machine-local prerequisite.** Routing here assumes a per-developer, untracked
`~/.pi/agent/models.json` that raises the registry context window of the `gpt-5.6-*` models
to 1,050,000. pi documents this exact override in its own `docs/models.md` § Per-model
Overrides (installed with the `@earendil-works/pi-coding-agent` package), including the
warning that above 272K total input the whole request bills at GPT-5.6's long-context rates:

```json
{
  "providers": {
    "openai": {
      "modelOverrides": {
        "gpt-5.6-sol": { "contextWindow": 1050000 },
        "gpt-5.6-luna": { "contextWindow": 1050000 }
      }
    }
  }
}
```

Slate's profile records 1,050,000 for those models while the base registry reports 272,000,
and the window guard judges against the REGISTRY figure. A developer without the override
sees three extra warnings per session — a context-window divergence for `gpt-5.6-luna`, one
for `gpt-5.6-sol`, and one aggregate line noting that the registry window equals those
models' own long-context billing threshold — and the guard then treats both GPT models as
narrower than `claude-opus-5` (1,000,000), so a long thread is substituted off them onto an
Anthropic candidate rather than the reverse. The override is deliberately not reproduced
repo-side: `models.json` is a user-global pi file, not project config.

**Acceptance check.** Router health is invisible to a bare `pi -p "<prompt>"`: the
orchestrator-mode auto-seed is gated to TUI sessions, so a print-mode run builds no doctrine,
never consults the resolver, and emits ZERO router warnings however broken the list is. Turn
the mode on in the same session instead — `pi -p "/slate on" "hi"`, which runs the command
first and the prompt second — and the warnings reach stderr. A healthy configuration emits
one general note about untraced research-table data, then one model-data note per model in
the candidate list above. Those notes name those models, in that order, and no
configuration-fault line appears. Match each note's model id against that list, position by
position, rather than counting notes. A missing note, an extra one, a note naming a model
the list does not, or ids in a different order: each is a finding, and each is what a
silently dropped or swapped candidate looks like. The notes are visible because
`router.showWarnings` is `true`. No hidden-warnings summary line appears. This check
deliberately omits per-model fact counts, because package model data can change. A
configuration-fault line, a hidden-warnings summary line, or a non-zero exit is a finding
too.

**Authority.** The routing table Slate renders into the doctrine each turn is the single
authority on per-model guidance, prices and measured levels. Leave every such fact in that
table. This section restates it in exactly four deliberate places, each recording a project
decision that a reader cannot follow without the fact it rests on: the profile markers the
candidate list cites to justify every inclusion and exclusion, the base model-and-level pairs
under Unsized dispatches, the accepted residual under What failover ignores, and the dated
price step this paragraph names next. Every pin bump re-checks those four. A pin bump also
re-checks the Effort policy claim that every listed model carries measured levels. A new
list entry or a refreshed profile table can falsify it. The figures drift: prices are dated
schedules (`claude-sonnet-5`'s step up 50% on 2026-09-01, which changes the table's numbers
but not its order, since `nonPreferred` sorts that model last on both sides of the step), and
measured levels, route-for/avoid-for guidance and the profiled model set itself change
whenever the package is republished.

## MCP server configuration

The `pi-mcp-adapter` package needs a machine-local server configuration, in the same way that
model routing needs a machine-local `models.json`. Setup, credential handling, and the current
worker-thread limitation live in `mcp-server-configuration.md` in this directory.

## Package pin bumps

Changing the `ytdb-slate` version pin in `.pi/settings.json` is a tracked change like any
other — it takes the full workflow. In addition, whoever bumps the pin MUST re-read this
document and `docs-internal/agents/slate-doctrine-extra.md` against the NEW package docs and
fix any skew: the deltas here are valid only relative to the package version they were
written against.

Last reconciled against **ytdb-slate 0.9.0**: track-workflow.md, pr-publishing.md,
model-routing.md, model-failover.md, review-rules.md, writing-guidance.md,
thread-cache-cost.md and context-budget.md. Those are the package documents these deltas
rest on. This reconciliation read each of them against the six `.pi/slate.json` switches
that this change turned on across `router`, `writing` and `threadChoice`. The list
deliberately omits design-principles.md, because no delta here rests on it.
