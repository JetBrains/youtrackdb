<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: CR8, sev: should-fix, loc: "plan/track-11.md:68", anchor: "### CR8 ", cert: V5, basis: "Track 11 item 6 re-runs the two-runner gate but inherits neither the two-command shape nor the not-optional install warning, so its no-regression claim can be measured against a stale core"}
  - {id: CR9, sev: suggestion, loc: "plan/track-9.md:5", anchor: "### CR9 ", cert: V6, basis: "two sentences still carry the pre-CR7 core scope — the Purpose's Today clause over-claims about an unmeasured runner, and the Context line calls the core per-directory table the whole downstream baseline"}
verdicts:
  - {id: CR6, verdict: VERIFIED}
  - {id: CR7, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 4, matches: 2}
cert_index:
  - {id: V3, verdict: MATCHES, anchor: "#### V3 "}
  - {id: V4, verdict: MATCHES, anchor: "#### V4 "}
  - {id: V5, verdict: MISMATCHES, anchor: "#### V5 "}
  - {id: V6, verdict: MISMATCHES, anchor: "#### V6 "}
flags: [CONTRACT_OK]
-->

# Consistency gate verification — iteration 2 (2026-08-03, 11-track plan)

Both findings verify. The CR6 command works as pinned — the load-bearing question, whether
`-DskipTests` still produces the `core` test-jar the `embedded` runner reads its feature files from,
resolves yes, and every other factual claim in the new paragraph checks out against the POMs and the
on-disk repository. CR7's four sites are widened correctly and acceptance bullet 9's appeal to the
Purpose now resolves literally. Two new findings come out of the re-scan, both on CR6's and CR7's
own blast radius: Track 11 re-runs the two-runner gate without inheriting the command or the install
warning, and two sentences in `plan/track-9.md` still carry the pre-widening `core` scope. Neither
blocks; the overall verdict is **PASS**.

Artifacts re-read: `plan/track-9.md` and `plan/track-11.md` in full, the Track 9 and Track 11 entries
plus `## Implementation state` in `implementation-plan.md`, `consistency-gate-verification-iter1.md`,
and live build state — `core/pom.xml`, `embedded/pom.xml`, the root `pom.xml`, `maven-jar-plugin`
3.5.0's own `plugin.xml`, `EmbeddedGraphFeatureTest`, `ShadedJarSmokeTest`, and
`~/.m2/repository/io/youtrackdb/youtrackdb-core/0.5.0-SNAPSHOT/`.

CR1–CR5 were verified in iteration 1 and are not re-opened here.

**Reference-accuracy caveat.** No PSI query was run. `steroid_execute_code` has timed out on this
repository in both prior spawns this session (the documented cold-kotlinc failure), and the checks
this iteration needs are POM element reads, a plugin-descriptor parameter lookup, an annotation
read, a directory listing, and prose cross-references — none of them reference-accuracy symbol
audits where a missed usage would flip a verdict. The one symbol-shaped claim touched below,
`EmbeddedGraphFeatureTest` extending `GraphFeatureWorld` from the `core` test-jar, is a direct
`import` and `extends` in a 67-line file, read whole.

## Verification certificates

#### Verify CR6: the pinned `embedded` command resolves `core` from the local repository
- **Original issue**: the acceptance bullet pinned a bare `./mvnw -pl embedded test`, which leaves
  `core` out of the reactor. `youtrackdb-core:0.5.0-SNAPSHOT` then resolves from `~/.m2`, where the
  branch machine holds a jar installed 2026-07-02. The `embedded` completion gate and the `embedded`
  A/B would both measure a `core` predating most of the branch, and item 3's post-fix re-run would
  show no movement because the fix would not be in the jar under test.
- **Fix applied**: the user picked rendering (b). `plan/track-9.md:81` now pins
  `./mvnw -pl core -am install -DskipTests` followed by `./mvnw -pl embedded test`, and carries a
  "**The install step is not optional**" paragraph stating the reactor-exclusion mechanism, the
  2026-07-02 install date, the `core` test-jar's role in supplying the feature files and
  `GraphFeatureWorld`, and the instruction to re-install before every `embedded` measurement
  including item 3's post-fix re-run.
- **Re-check**:
  - Search/trace performed: `Read` of `plan/track-9.md:79-90` and `git diff HEAD` for the applied
    hunks; `grep` over `core/pom.xml` and the root `pom.xml` for the `maven-jar-plugin` declaration
    and version; `unzip` of `~/.m2/.../maven-jar-plugin-3.5.0.jar` plus a parse of its
    `META-INF/maven/plugin.xml` to read the `test-jar` mojo's parameter descriptors; `grep` over
    `embedded/pom.xml` for the `youtrackdb-core` dependency pair and the surefire block; `find` for
    the `gremlintest/features` directory; `Read` of `EmbeddedGraphFeatureTest.java` in full and
    `ShadedJarSmokeTest.java`'s class javadoc; `ls -la` and a metadata read of the local
    `youtrackdb-core` directory; `git log` for the Track 7 completion date. Tool: `grep`, `Read`,
    and shell inspection. No PSI (see the caveat).
  - Code location: `core/pom.xml:478-490`; root `pom.xml:476-477`; `embedded/pom.xml:53-56`,
    `:71-76`, `:405-411`; `core/src/test/resources/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/features`;
    `embedded/src/test/java/com/jetbrains/youtrackdb/shade/EmbeddedGraphFeatureTest.java:24-42`.
  - Current state: **the command does what the bullet says it does.** Certificate V3 carries the
    trace. The question that would have kept CR6 open — whether `-DskipTests` suppresses the
    test-jar — resolves in the command's favour: `maven-jar-plugin` 3.5.0's `test-jar` mojo declares
    `<skip implementation="boolean">${maven.test.skip}</skip>`, so the goal keys on
    `maven.test.skip` and not on `skipTests`. `-DskipTests` skips surefire execution while
    `test-compile` and `process-test-resources` still run, so `core/target/test-classes` is
    populated and the `test-jar` execution at `core/pom.xml:483-489` packages it at `package`,
    inside `install`'s lifecycle. Both artifacts land in `~/.m2` on every run of the first command.
    The rest of the paragraph is accurate as well. The local repository holds
    `youtrackdb-core-0.5.0-SNAPSHOT.jar` at Jul 2 14:05 and `-tests.jar` at Jul 2 14:11 with
    `<localCopy>true</localCopy>` and `lastUpdated 20260702121006`; Track 7 completed 2026-07-28 and
    Track 10 on 2026-08-02, so "predating Tracks 7, 8 and 10" holds. The feature files live under
    `core/src/test/resources/...`, so they ship in the test-jar exactly as claimed, `GQL Match
    Support` included. `EmbeddedGraphFeatureTest` binds
    `classpath:/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/features` in its
    `@CucumberOptions` `features` array and extends
    `com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.GraphFeatureWorld`, both from that
    jar. The install also covers everything else `embedded` needs from the reactor: `-am` pulls the
    parent, `youtrackdb-test-commons` and `youtrackdb-gremlin-annotations` alongside `core`, and
    `core` is `embedded`'s only reactor dependency (compile jar at `:53-56`, test-jar at `:71-76`).
- **Regression check**: checked five adjacent claims.
  (1) **No contradiction with the R6 pinned-commands bullet (`:90`).** R6's early-abort reasoning is
  specific to `core/pom.xml`'s five sequential surefire executions, where execution 3's failure
  aborts the `test` phase before executions 4 and 5 run. `embedded/pom.xml:405-411` declares one
  surefire plugin with no `<executions>` block and no group filter, so its classes all run and
  report at the end and there is no early abort for `-Dmaven.test.failure.ignore=true` to defuse.
  R6's universal ("every full-suite gate here") is therefore loose wording rather than a live
  conflict with the flagless `embedded` command two bullets above it. Iteration 1 reached the same
  conclusion and declined to raise it; the CR6 fix made the tension more visible by spelling the
  command out, and no more wrong. Not raised.
  (2) **`./mvnw -pl embedded test` reaches the runner and finishes.** The module's other
  surefire-bound class, `ShadedJarSmokeTest`, states in its own javadoc that it "runs during the
  `test` phase (before shading)" and exercises an in-memory database, so nothing in the module needs
  the shade plugin's `package`-phase output to get through `test`. `ShadedJarIT` is failsafe-bound
  and does not run. Clean.
  (3) **Items 1 and 3 read consistently with the new command.** Item 1 gives the `embedded` half its
  own translator-on/off A/B and completion check; item 3 says both runners get a post-fix number;
  the bullet's closing "re-install before every `embedded` measurement, including item 3's post-fix
  re-run" is the wire between them. Clean.
  (4) **One loose clause, not a defect.** The paragraph says `EmbeddedGraphFeatureTest` reads its
  feature files "and `GraphFeatureWorld`" from
  `classpath:/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/features`. The class sits
  one package up, at `.../gremlintest/GraphFeatureWorld`, not under `features`. Both ship in the
  same test-jar, which is the load-bearing half of the claim, so an executor is not misdirected.
  Not raised.
  (5) **New issue.** Track 11 item 6 re-runs the same two-runner gate and inherits neither the
  command nor the warning (CR8).
- **Verdict**: VERIFIED (fix correct and the pinned command executable; one adjacent issue raised as
  CR8)

#### Verify CR7: `core`-only descriptions surviving the two-runner widening
- **Original issue**: after CR1 widened Track 9 to two runners, three descriptions of the
  deliverable still scoped it to `core`, and acceptance bullet 9 cited one of them — the Purpose —
  as its own authority. Read literally, bullet 9's appeal failed, giving a decomposer a documented
  reason to narrow the gate back to `core`.
- **Fix applied**: four sites moved. `:5` names both runners by test class; `:55`'s Mermaid node
  reads "two-runner Cucumber baseline"; `:82` asks each runner for its own recorded shape; `:103`
  describes the artifact as one file with a labelled half per runner.
- **Re-check**:
  - Search/trace performed: `Read` of `plan/track-9.md` in full; `git diff HEAD` over the same file
    to isolate the four hunks from CR1's; `grep -n` for `per-directory`, `single-fork`, `two-runner`
    and `per-runner` across `plan/track-9.md` and `implementation-plan.md` to find any site the
    widening missed; `Read` of `implementation-plan.md:680-745`. Tool: `grep` plus `Read`.
  - Code location: `plan/track-9.md:5`, `:55`, `:82`, `:103`; read against `:68`, `:70`, `:89`,
    `:107` and `implementation-plan.md:688-690`.
  - Current state: **bullet 9's cross-reference now resolves.** The Purpose opens "After this track
    both TinkerPop feature runners — `core`'s `YTDBGraphFeatureTest` and the `embedded` module's
    `EmbeddedGraphFeatureTest` — complete with the translator on, each runner's failure set is a
    committed artifact rather than a promise", so bullet 9's "a suite that completes in `core` and
    hangs in `embedded` has not met **this track's Purpose**" is literally supported by the sentence
    it points at. The other three land as proposed. `:82` no longer imposes `core`'s per-directory
    decomposition on the second runner — it asks for "`core`'s per-directory and single-fork sets,
    and `embedded`'s in whatever shape item 1 finds it runs in" — which matches what item 1 actually
    commissions. `:103`'s "one file carrying a labelled half per runner" matches item 1's "landing
    in the same artifact as a clearly labelled second half" word for word in substance. The diagram
    node reads "two-runner Cucumber baseline". Certificate V4 carries the trace.
- **Regression check**: checked four things.
  (1) **No plan-file drift.** `implementation-plan.md:688-690` already named "the two-runner
  baseline artifact (`core`'s `gremlin-feature-compliance-tests` plus the `embedded` module's
  `EmbeddedGraphFeatureTest`, whose A/B is unsized until first run)", so the track file converged on
  the plan file rather than the two diverging. Clean.
  (2) **Track 11 item 6 still reads against the widened artifact.** "Track 9's baseline records both
  and this re-run reads both" has a producer for each half. Clean on scope; its command gap is CR8,
  a separate axis.
  (3) **Acceptance bullet `:88` considered and not raised.** "The post-fix baseline is recorded and
  named as the number Track 11 reads" keeps a singular "the number" while item 3 produces one per
  runner. The artifact really is one file (`:103`), the two neighbouring bullets are explicit about
  both runners, and no reader gets from `:88` to a single-runner conclusion. Wording lag below the
  finding bar.
  (4) **Two sentences did not move.** `:5`'s "Today neither holds" clause and `:43`'s "the
  per-directory table is the baseline artifact everything downstream reads" both still carry the
  pre-widening scope, in opposite directions — CR9.
- **Verdict**: VERIFIED (all four sites correct; residual scope lag raised as CR9)

## Findings

### CR8 [should-fix]
**Certificate**: V5
**Location**: `plan/track-11.md:68` (item 6) and `:60` (the `### Clarifications` command bullet);
read against `plan/track-9.md:81` (the pinned two-command gate) and `plan/track-11.md:86` (the
no-regression acceptance bullet)

**Issue**: Track 11 re-runs Track 9's two-runner gate and inherits none of the command discipline
that makes the second runner meaningful. Item 6 says the suite "runs from `YTDBGraphFeatureTest`
under `core`'s `gremlin-feature-compliance-tests` execution and from `EmbeddedGraphFeatureTest` in
the `embedded` module" without pinning a command for either, and the file's only command guidance,
the Clarifications bullet at `:60`, is `core`-only. A Track 11 decomposer reading this file would
reach for `./mvnw -pl embedded test`, the shape `CLAUDE.md`'s module routing suggests and the one
CR6 established is broken. The hazard is strictly worse here than in Track 9: Track 11's whole
deliverable is new `core` code — the `RecognitionContext` seam, four recognisers,
`GremlinStepWalker`'s allow-list, `UnionStepRecogniser`'s child gate — so a run against a stale
installed jar exercises none of it, and item 6's claim is "no regression". The gate would read as a
pass while measuring a `core` that has neither the code under test nor Track 9's filter fix.

**Evidence**: `grep` over `plan/track-11.md` for `install`, `-am`, `mvnw` and
`maven.test.failure.ignore` returns exactly one line, `:60`, which pins
`-Dmaven.test.failure.ignore=true` and `surefire:test@gremlin-feature-compliance-tests` and names no
`embedded` command. `embedded` appears in the file three times — item 6, the `**Signatures:**` line
at `:104`, and the `## Purpose` chain — always as a runner name, never with an invocation. Track
11's `## Validation and Acceptance` has no pinned-commands bullet at all; `:86` states the
no-regression criterion without naming how to measure it. Meanwhile `plan/track-9.md:81` carries the
full rule — "**The install step is not optional**", ending "re-install before every `embedded`
measurement" — scoped by its own wording to Track 9's items. The resolution mechanism is
track-independent: `-pl embedded` puts one module in the reactor, and
`~/.m2/repository/io/youtrackdb/youtrackdb-core/0.5.0-SNAPSHOT/maven-metadata-local.xml` records
`<localCopy>true</localCopy>` with `lastUpdated 20260702121006`, so the stale install wins
resolution for Track 11 exactly as it does for Track 9.

**Proposed fix**: Give Track 11 the same pinned pair. Add one Clarifications bullet beside `:60`, or
one acceptance bullet, stating that the `embedded` half of item 6 runs as
`./mvnw -pl core -am install -DskipTests` followed by `./mvnw -pl embedded test`, that the install is
not optional because `-pl embedded` leaves `core` out of the reactor, and that it must be repeated
after every code change the re-run is meant to measure. Cross-referencing `plan/track-9.md:81` by
name is enough if the two-command shape is quoted alongside it — a bare pointer leaves a decomposer
reading only this file with no command.

**Classification**: mechanical
**Justification**: the fix renders one settled decision into a second file. The user already chose
rendering (b) at CR6 and the reason it applies to Track 11 is the same Maven resolution fact, so
there is one unambiguous rendering and no new cost trade-off. Track 11's goals, scope and gates are
unchanged — only the instruction for executing a gate it already commits to.

### CR9 [suggestion]
**Certificate**: V6
**Location**: `plan/track-9.md:5` (`## Purpose / Big Picture`, the "Today neither holds" clause) and
`:43` (`## Context and Orientation`, "the per-directory table is the baseline artifact everything
downstream reads")

**Issue**: Two sentences kept the pre-CR7 `core` scope, and they now sit on opposite sides of the
widened claim. The Purpose over-claims: its second sentence, "Today neither holds: the suite does
not terminate with the strategy enabled", was accurate when the first sentence promised a `core`
run, but the first sentence now promises both runners, so the "today" clause reads as an established
fact that `embedded` also fails to terminate. The track says the opposite twice — item 1 calls the
`embedded` half "**unsized** — no measured A/B exists for it", and acceptance bullet `:81` says
"that run has never been measured on this branch". The Context line under-claims in the other
direction: `:43` still calls the `core` per-directory table "the baseline artifact everything
downstream reads", where `:103` now defines the artifact as one file with a half per runner.

**Evidence**: `:5` reads "After this track both TinkerPop feature runners … complete with the
translator on … Today neither holds: the suite does not terminate with the strategy enabled". Every
completion measurement in `## Context and Orientation` is `core`'s: the A/B table at `:32-37` runs
`./mvnw -pl core -o surefire:test@gremlin-feature-compliance-tests`, and the corroborating artifacts
at `:39-41` are Track 10's `core` logs and `/tmp/track10-final-verify.log`. No `embedded` run
appears anywhere in the section, consistent with item 1's "unsized". At `:43`, the sentence closes a
paragraph about the seven per-upstream-directory `core` runs, so its subject is unambiguous, but its
predicate — "the baseline artifact everything downstream reads" — is now the two-half file.

**Proposed fix**: At `:5`, scope the "today" clause to what is measured: say the `core` suite does
not terminate with the strategy enabled and the `embedded` side has not been measured on this
branch, which is also what item 1 exists to establish. At `:43`, end the sentence at the `core`
half — the per-directory table is the `core` half of the baseline — leaving `:103` and item 1 to
define the whole artifact.

**Classification**: mechanical
**Justification**: both are current-state claims contradicted by the track file's own text rather
than by the code, and each has one unambiguous rendering that follows from the already-settled
two-runner scope. `## Context and Orientation` is the intent-axis carve-out and always reads as
current-state; the `:5` clause is explicitly present-tense inside an otherwise target-state section.
Neither fix changes what the track sets out to do.

## Evidence base

Four certificates: two confirming the applied fixes, two supporting the new findings. Every entry
rests on `grep`, direct `Read`, and shell inspection of the POMs, the plugin descriptor and the
local Maven repository. PSI was not attempted — see the reference-accuracy caveat above.

#### V3 Ref: `./mvnw -pl core -am install -DskipTests` produces and installs the `core` test-jar
- **Document claim**: `plan/track-9.md:81` — the install step is not optional, and the `core`
  test-jar is load-bearing because `EmbeddedGraphFeatureTest` reads its feature files and
  `GraphFeatureWorld` from it.
- **Search performed**: `grep` for `maven-jar-plugin` across every `pom.xml`; `Read` of
  `core/pom.xml:478-490` and root `pom.xml:476-477`; `unzip` of
  `~/.m2/repository/org/apache/maven/plugins/maven-jar-plugin/3.5.0/maven-jar-plugin-3.5.0.jar` and
  a parse of the `test-jar` mojo block in `META-INF/maven/plugin.xml`; `find` for the
  `gremlintest/features` directory; `grep` of `embedded/pom.xml` for `io.youtrackdb` dependencies;
  `Read` of `EmbeddedGraphFeatureTest.java`; `ls -la` plus `maven-metadata-local.xml` read of the
  local `youtrackdb-core/0.5.0-SNAPSHOT/` directory; `git log --date=short` for the Track 7
  completion commit.
- **Code location**: `core/pom.xml:483-489` (the `test-jar` execution, no `<phase>` override, so the
  goal's default `package` binding applies); root `pom.xml:477` (`<version>3.5.0</version>`);
  `maven-jar-plugin-3.5.0` `plugin.xml`, `test-jar` mojo, `<skip
  implementation="boolean">${maven.test.skip}</skip>`; `embedded/pom.xml:53-56` and `:71-76`;
  `core/src/test/resources/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/features`;
  `EmbeddedGraphFeatureTest.java:7` (the `GraphFeatureWorld` import), `:39-40` (the `features`
  paths), `:52-57` (`EmbeddedGraphWorld extends GraphFeatureWorld`).
- **Actual signature/role**: the `test-jar` goal keys its skip on `maven.test.skip`, not on
  `skipTests`, so `-DskipTests` suppresses surefire alone. `test-compile` and
  `process-test-resources` still run, `core/target/test-classes` is populated with the compiled test
  classes and the `src/test/resources` feature files, and `package` attaches the test-jar, which
  `install` deposits in `~/.m2` beside the main jar. `-am` adds the parent,
  `youtrackdb-test-commons` and `youtrackdb-gremlin-annotations` to the reactor, and `core` is the
  only reactor artifact `embedded` consumes. The local repository state the bullet cites is current:
  main jar Jul 2 14:05, `-tests.jar` Jul 2 14:11, `<localCopy>true</localCopy>`,
  `lastUpdated 20260702121006`; Track 7 completed 2026-07-28 and Track 10 on 2026-08-02.
- **Verdict**: MATCHES
- **Detail**: Supports **Verify CR6**. The one way rendering (b) could have failed — an install that
  refreshes the production jar while leaving a month-old test-jar in place, which would have kept
  the stale-feature-file half of CR6 alive and been harder to spot than the original defect — does
  not occur at this plugin version and configuration. Had the bullet pinned `-Dmaven.test.skip=true`
  instead, it would.

#### V4 Ref: the two-runner scope across `plan/track-9.md` after the CR7 widening
- **Document claim**: `plan/track-9.md:89` — "a suite that completes in `core` and hangs in
  `embedded` has not met **this track's Purpose**", with `:5`, `:55`, `:82` and `:103` as the
  descriptions it and its neighbours lean on.
- **Search performed**: `Read` of `plan/track-9.md` in full and `plan/track-11.md` in full; `git
  diff HEAD` over `plan/track-9.md` to separate the CR7 hunks from CR1's; `grep -n` for
  `per-directory|single-fork|per-runner|two-runner` across `plan/track-9.md` and
  `implementation-plan.md`; `Read` of `implementation-plan.md:680-745`.
- **Code location**: `plan/track-9.md:5`, `:55`, `:82`, `:103`, cross-read against `:68`, `:70`,
  `:89`, `:107`; `implementation-plan.md:688-690`.
- **Actual signature/role**: the Purpose names both runners by test class, so bullet 9's appeal is
  satisfied by the sentence it cites. The diagram node, the artifact bullet and the In-scope-new
  line all now describe one artifact with a per-runner half, matching what item 1 commissions and
  what item 3 re-measures. The plan-file Scope line already carried the two-runner phrasing, so the
  two documents agree. The sweep returns two sentences the widening did not reach, `:5`'s "today"
  clause and `:43`'s downstream-baseline claim; every other `per-directory` occurrence is a
  correctly `core`-scoped description of the measured runs or of the `-Dcucumber.features=` loop.
- **Verdict**: MATCHES
- **Detail**: Supports **Verify CR7**. The self-referential failure CR7 identified — an acceptance
  bullet justified by a Purpose that did not say what the bullet claimed — is closed at the exact
  site that mattered. The residue is prose scope lag with no cross-reference resting on it, which is
  why CR9 is a suggestion rather than a repeat of CR7.

#### V5 Ref: Track 11's `embedded` re-run has no pinned command
- **Document claim**: `plan/track-11.md:68` — "Re-run the full Cucumber suite — both runners — and
  show no regression against Track 9's post-fix baseline … Track 9's baseline records both and this
  re-run reads both."
- **Search performed**: `grep -n` over `plan/track-11.md` for `install`, `-am`, `mvnw`,
  `maven.test.failure.ignore` and `embedded|Embedded`; `Read` of the file's `### Clarifications`,
  `## Plan of Work` and `## Validation and Acceptance` sections in full; comparison against
  `plan/track-9.md:81` and `:90`.
- **Code location**: `plan/track-11.md:60` (the only command bullet, `core`-only), `:68` (item 6),
  `:86` (the no-regression acceptance bullet), `:104` (`**Signatures:**`, both runners named).
- **Actual signature/role**: the file names `EmbeddedGraphFeatureTest` three times and pins no
  invocation for it anywhere. Its Clarifications bullet reproduces the `core` half of Track 9's R6
  rule and stops there. Its `## Validation and Acceptance` has no pinned-commands bullet, so `:86`'s
  no-regression criterion carries no measurement recipe for either runner beyond `:60`'s `core`
  guidance.
- **Verdict**: MISMATCHES
- **Detail**: Feeds CR8. The failure runs in the same direction CR6 flagged and lands on a bigger
  target — a stale `core` jar would be missing every line Track 11 writes, so a "no regression"
  reading would be vacuously true. Track 9 at least carries the warning where its own executor will
  read it; Track 11's executor has no reason to open `plan/track-9.md`'s acceptance section.

#### V6 Ref: two pre-widening sentences in `plan/track-9.md`
- **Document claim**: `plan/track-9.md:5` — "Today neither holds: the suite does not terminate with
  the strategy enabled"; `:43` — "the per-directory table is the baseline artifact everything
  downstream reads".
- **Search performed**: `Read` of `## Purpose / Big Picture` and `## Context and Orientation` in
  full; `git diff HEAD` to confirm neither sentence moved in the CR7 hunks; cross-read of item 1
  (`:68`) and acceptance bullet `:81` for the measurement status of the `embedded` runner; `grep`
  for `per-directory` to place `:43` among the other occurrences.
- **Code location**: `plan/track-9.md:5`, `:32-37` (the `core`-only A/B table), `:39-41` (the `core`
  corroborating logs), `:43`, `:68`, `:81`, `:103`.
- **Actual signature/role**: the Purpose's first sentence now covers both runners while its second
  sentence's evidence base, everything in `## Context and Orientation`, is `core`'s alone; item 1
  and `:81` both state that the `embedded` A/B has never been run. `:43` describes the seven
  per-directory `core` runs correctly and then attributes the whole downstream baseline role to that
  one table, which `:103` reassigns to a two-half file.
- **Verdict**: MISMATCHES
- **Detail**: Feeds CR9. Both are single-sentence lags with no gate or cross-reference resting on
  them — item 1, `:81`, `:82` and `:103` each state the correct scope within a screen of the lagging
  text, so a decomposer who reads the section is corrected in place. That containment is what keeps
  this a suggestion.

## Summary

**PASS.** CR6 and CR7 both verify. CR6's pinned command survives the check that could have sunk it:
`maven-jar-plugin`'s `test-jar` goal keys its skip on `maven.test.skip`, so `-DskipTests` still
installs the `core` test-jar the `embedded` runner reads its feature files and `GraphFeatureWorld`
from, and every other factual claim in the new paragraph — the reactor-exclusion mechanism, the
2026-07-02 install, the track dates, the feature-file location — holds. The paragraph does not
conflict with the R6 bullet: R6's early-abort hazard is specific to `core`'s five sequential surefire
executions and `embedded` has one. CR7's four sites are widened correctly, and acceptance bullet 9's
appeal to the Purpose now resolves against text that says what the bullet claims. No blockers remain.

Two new findings are carried forward: CR8 (should-fix, mechanical — Track 11 re-runs the two-runner
gate with no pinned `embedded` command and no install prerequisite, so its no-regression claim can be
measured against a stale `core`) and CR9 (suggestion, mechanical — the Purpose's "today" clause and
one Context sentence still carry the pre-widening `core` scope).
</content>
</invoke>
