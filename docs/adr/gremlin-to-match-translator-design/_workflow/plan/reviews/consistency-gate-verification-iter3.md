<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: CR10, sev: suggestion, loc: "plan/track-9.md:5", anchor: "### CR10 ", cert: V9, basis: "the CR9 rewrite added a third clause under 'Today neither holds', so a two-item word now heads a three-item list"}
verdicts:
  - {id: CR8, verdict: VERIFIED}
  - {id: CR9, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 3, matches: 2}
cert_index:
  - {id: V7, verdict: MATCHES, anchor: "#### V7 "}
  - {id: V8, verdict: MATCHES, anchor: "#### V8 "}
  - {id: V9, verdict: MISMATCHES, anchor: "#### V9 "}
flags: [CONTRACT_OK]
-->

# Consistency gate verification — iteration 3 (2026-08-03, 11-track plan)

Both fixes hold and no blocker remains. CR8's new Clarifications bullet reproduces Track 9's pinned
pair command for command, states the same reactor-exclusion mechanism, and its
`plan/track-9.md` § Validation and Acceptance cross-reference resolves to a section that carries the
rule it claims. CR9's Purpose rewrite converges the track file onto the plan file, which was already
`core`-scoped for the non-termination claim, and the widened "today" clause now agrees with item 1's
"unsized" language and acceptance bullet 2's "never been measured" wording. One residual comes out of
the re-scan: the rewrite added a third clause under a word that means two. It is a one-word repair,
**not a blocker** — the overall verdict is **PASS**.

Artifacts re-read: `plan/track-9.md` and `plan/track-11.md` in full; the Track 9 and Track 11 plan
entries plus `## Implementation state` in `implementation-plan.md`; `plan/track-10.md`'s three
`embedded` mentions; `consistency-gate-verification-iter2.md`; and `git diff HEAD` over both track
files to isolate the applied hunks.

CR1–CR7 were verified in iterations 1 and 2 and are not re-opened here.

**Reference-accuracy caveat.** No PSI query was run. `steroid_execute_code` has timed out on this
repository in all three prior spawns this session (the documented cold-kotlinc failure), and this
iteration's checks are prose reads, cross-reference resolution, and one `find` over source paths.
None is a reference-accuracy symbol audit where a missed usage would flip a verdict. The single
symbol-shaped claim touched below — that Track 11's production classes live in `core` — was settled
by locating four files on disk, not by counting callers.

## Verification certificates

#### Verify CR8: Track 11 inherits the pinned two-command `embedded` gate
- **Original issue**: Track 11 item 6 re-runs Track 9's two-runner gate and pinned no command for the
  `embedded` half. The file's only command guidance, the Clarifications bullet at `:60`, is
  `core`-only, so a decomposer reading this file alone would reach for `./mvnw -pl embedded test` —
  the shape CR6 established resolves `core` from a 2026-07-02 jar in `~/.m2`. Track 11's whole
  Cucumber-measured deliverable is new `core` code, so that run exercises none of it while still
  reporting "no regression".
- **Fix applied**: a new Clarifications bullet at `plan/track-11.md:61`, directly after the
  bare-`./mvnw -pl core test` bullet, pinning `./mvnw -pl core -am install -DskipTests` then
  `./mvnw -pl embedded test`, stating the reactor-exclusion mechanism and the `core` test-jar's role,
  requiring the install be repeated after every code change the re-run measures, and cross-referencing
  `plan/track-9.md` § Validation and Acceptance.
- **Re-check**:
  - Search/trace performed: `Read` of `plan/track-11.md` in full and `git diff HEAD` over it to
    isolate the hunk; `grep -n` for `mvnw`, `install` and `-am` across both track files; a
    repo-wide `grep -rn -- "-pl embedded"` over `_workflow/`; `find` for
    `GremlinStepWalker.java` / `RecognitionContext.java` / `ListShapingOp.java` /
    `UnionStepRecogniser.java` to test the bullet's "new `core` code" premise. Tool: `grep`, `Read`,
    `find`. No PSI (see the caveat).
  - Code location: `plan/track-11.md:61`, read against `:60`, `:69` (item 6), `:87` (the
    no-regression acceptance bullet); `plan/track-9.md:79` (the section heading) and `:81` (the rule).
  - Current state: **the bullet is consistent with Track 9 and its cross-reference resolves.**
    Certificate V7 carries the trace. Both files pin the identical pair,
    `./mvnw -pl core -am install -DskipTests` then `./mvnw -pl embedded test`, and give the identical
    mechanism — `-pl embedded` leaves `core` out of the reactor, so `youtrackdb-core:0.5.0-SNAPSHOT`
    resolves from the local repository — with Track 11 dropping only the 2026-07-02 install date,
    which is Track 9's own machine detail. Both name the `core` test-jar as the supplier of
    `EmbeddedGraphFeatureTest`'s feature files and `GraphFeatureWorld`. The cross-reference target
    exists: `plan/track-9.md`'s `## Validation and Acceptance` opens at `:79` and its second bullet
    `:81` is the rule, headed "**The install step is not optional.**" The bullet's premise checks out
    — all four of Track 11's named production classes sit under `core/src/main/java/`, so a run
    against a stale installed jar really does exercise none of the code item 6 measures.
- **Regression check**: checked four adjacent claims.
  (1) **No contradiction in when the install must be repeated.** Track 9 says "re-install before every
  `embedded` measurement, including item 3's post-fix re-run"; Track 11 says "repeat the install after
  every code change the re-run is meant to measure". Track 9's is the stronger form and Track 11's
  closing "Same rule as `plan/track-9.md` § Validation and Acceptance" imports it, so the weaker
  local phrasing cannot license skipping an install Track 9 requires. Clean.
  (2) **No reader is left reaching for a bare `./mvnw -pl embedded test`.** The repo-wide sweep
  returns exactly two `-pl embedded` sites in the track files, `plan/track-9.md:81` and
  `plan/track-11.md:61`, and both are the pinned pair. Every other `embedded` mention in either file
  — `track-9.md:5`, `:68`, `:70`, `:82`, `:89`, `:103`, `:107` and `track-11.md:69`, `:105` — names
  the runner without an invocation. Clean.
  (3) **The `-Dmaven.test.failure.ignore=true` tension is inherited, not created.** Track 11's `:60`
  says to use the flag "for full-suite gates" while `:61` pins a flagless `embedded` command — the
  same looseness iteration 2 examined at `plan/track-9.md:90` and declined to raise, for the same
  reason: `embedded/pom.xml` declares one surefire plugin with no `<executions>` block, so there is
  no early abort for the flag to defuse. Not raised.
  (4) **Item 6 and acceptance `:87` still read against the two-runner artifact.** Item 6 names both
  runners and says Track 9's baseline records both; `:87`'s singular "the full TinkerPop Cucumber
  suite" is the same wording lag iteration 2 assessed at `plan/track-9.md:88` and left below the
  finding bar, with both neighbours explicit about the two runners. Clean.
- **Verdict**: VERIFIED

#### Verify CR9: the Purpose and Context sentences that kept the pre-widening `core` scope
- **Original issue**: two sentences carried the pre-CR7 scope in opposite directions. The Purpose
  over-claimed — "Today neither holds: the suite does not terminate with the strategy enabled" read as
  an established fact that `embedded` also fails to terminate, which item 1 and acceptance bullet
  `:81` each contradict. The Context line under-claimed — `:43` called the `core` per-directory table
  "the baseline artifact everything downstream reads", where `:103` defines the artifact as one file
  with a half per runner.
- **Fix applied**: `:5`'s "today" clause now scopes non-termination to `core` and adds "the `embedded`
  runner has never been measured on this branch at all"; `:43` now reads "the per-directory table is
  the `core` half of the baseline artifact everything downstream reads".
- **Re-check**:
  - Search/trace performed: `Read` of `plan/track-9.md` in full; `git diff HEAD` over it to confirm
    the two hunks and nothing else in those sections; `grep -n` for `embedded|Embedded` across
    `plan/track-9.md`, `plan/track-10.md` and `implementation-plan.md`; a `grep -rniE` sweep of
    `_workflow/` for any recorded `embedded` scenario count or build result; `Read` of
    `implementation-plan.md:662-745`. Tool: `grep` plus `Read`.
  - Code location: `plan/track-9.md:5`, `:43`, read against `:68` (item 1), `:81` and `:89`
    (acceptance), `:103`; `implementation-plan.md:664`, `:671-672`, `:688-690`, `:721`;
    `plan/track-10.md:34`, `:158`.
  - Current state: **both sentences now say what the rest of the track says.** Certificate V8 carries
    the trace. The Purpose's "never been measured on this branch at all" matches item 1's "**unsized**
    — no measured A/B exists for it" and acceptance `:81`'s "that run has never been measured on this
    branch"; no sentence anywhere in the file now asserts a measured `embedded` failure. `:43` ends at
    the `core` half and leaves `:103` and item 1 to define the whole artifact. The fix also closed a
    gap the finding did not name: `implementation-plan.md` was already `core`-scoped at `:664` ("the
    `core` feature suite does not complete with the translator on"), `:671-672` and `:721` ("the
    `core` feature suite does not terminate with the translator on, and no Cucumber baseline exists
    for this branch"), so the track file converged onto the plan file rather than the two drifting
    apart.
- **Regression check**: checked three things.
  (1) **The "never measured at all" absolute survives Track 10's record.** Acceptance `:89` says
  `EmbeddedGraphFeatureTest` is the runner "which Track 10 recorded as the executing runner at that
  time", which reads as a prior observation. It is not a measurement. `plan/track-10.md:34` and
  `:158` derive the claim from the `sequential-tests` `<groups>SequentialTest</groups>` filter
  rejecting `YTDBGraphFeatureTest`'s child descriptions — an inference about which runner would
  execute, with no scenario count, no build result and no command recorded. The `_workflow` sweep for
  an `embedded` scenario count or build result returns nothing. So `:89` and the Purpose describe
  different things and do not conflict. Clean.
  (2) **No remaining `core`-only scoping the two-runner decision should have moved.** Every surviving
  `core` scope in the file is correctly `core`'s: the A/B table at `:32-37`, the corroborating logs at
  `:39-41`, the stall analysis at `:41`, the per-directory paragraph at `:43`, Track 10's handover at
  `:51`, acceptance `:80`, and the R6 command bullet at `:90` all describe `core` measurements or
  `core/pom.xml` wiring. Clean.
  (3) **New issue.** The rewrite added a third clause under "Today neither holds", a word that governs
  two (CR10).
- **Verdict**: VERIFIED (both sites correct; one fix-shifted wording defect raised as CR10)

## Findings

### CR10 [suggestion]
**Certificate**: V9
**Location**: `plan/track-9.md:5` (`## Purpose / Big Picture`, the "Today neither holds" clause)

**Issue**: The CR9 edit added a third item to a list headed by a two-item word. Before the fix the
colon expansion held two clauses — the suite does not terminate, and a filter on a non-root alias is
discarded — which "neither" governed correctly. The fix inserted "the `embedded` runner has never
been measured on this branch at all" between them, so "neither" now heads three. Neither reading of
its antecedent rescues it: the promises in the preceding sentence also number three (both runners
complete, each runner's failure set is committed, the filter is fixed). A reader who counts pauses to
work out which two of the three the sentence means, and the Purpose is the first paragraph of the
file.

**Evidence**: `git diff HEAD` on `plan/track-9.md` shows the before text — "Today neither holds: the
suite does not terminate with the strategy enabled, and a filter on a non-root alias is discarded
without declining" — against the after text, which reads "Today neither holds: the `core` suite does
not terminate with the strategy enabled, the `embedded` runner has never been measured on this branch
at all, and a filter on a non-root alias is discarded without declining". Two comma-separated clauses
became three. `grep -n "neither\|Neither"` returns this line and `:47`, where the usage is the
ordinary two-item one ("with neither a `WHERE` nor a class") and is correct.

**Proposed fix**: Replace "Today neither holds" with a number-neutral opener — "Today none of that
holds" reads cleanly against three clauses and needs no other change to the sentence.

**Classification**: mechanical
**Justification**: a two-word substitution in a sentence whose content is already settled. The track's
goals, scope and gates do not move, no cost trade-off is implied, and the rendering is unambiguous
because the clause count fixes it. The defect is internal to one sentence and was introduced by the
CR9 edit rather than inherited from the pre-split track.

## Evidence base

Three certificates: two confirming the applied fixes, one supporting the new finding. Every entry
rests on `grep`, direct `Read`, `git diff`, and one `find` over source paths. PSI was not attempted —
see the reference-accuracy caveat above.

#### V7 Ref: the Track 11 Clarifications bullet against Track 9's pinned pair
- **Document claim**: `plan/track-11.md:61` — the `embedded` half of item 6 runs as
  `./mvnw -pl core -am install -DskipTests` then `./mvnw -pl embedded test`; the install is not
  optional because `-pl embedded` leaves `core` out of the reactor; the `core` test-jar supplies
  `EmbeddedGraphFeatureTest`'s feature files and `GraphFeatureWorld`; the same rule lives at
  `plan/track-9.md` § Validation and Acceptance.
- **Search performed**: `git diff HEAD` over `plan/track-11.md`; `Read` of the file in full;
  `grep -n` for `mvnw|install|-am` over both track files; `grep -rn -- "-pl embedded"` over
  `_workflow/`; `find` for the four Track 11 production classes named in `## Interfaces and
  Dependencies`.
- **Code location**: `plan/track-11.md:60-61`, `:69`, `:87`, `:101-105`; `plan/track-9.md:79`, `:81`,
  `:90`; `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/`
  (`GremlinStepWalker`, `RecognitionContext`, `UnionStepRecogniser`) and `.../translator/step/`
  (`ListShapingOp`).
- **Actual signature/role**: the two files pin the same two commands and the same mechanism; Track 11
  omits only the 2026-07-02 install date, a machine detail belonging to Track 9's own measurement.
  The cross-reference target is real — `## Validation and Acceptance` at `plan/track-9.md:79`, rule
  at `:81`. The repeat-install rules differ in trigger (Track 9: every measurement; Track 11: every
  code change measured) but not in direction, and Track 11's closing sentence imports Track 9's. All
  four production classes resolve under `core/src/main/java/`, so the "new `core` code" premise
  holds for everything the Cucumber re-run measures; item 7's JMH classes live in `jmh-ldbc` and are
  outside that re-run.
- **Verdict**: MATCHES
- **Detail**: Supports **Verify CR8**. The way this fix could have failed — a bare pointer to Track 9
  leaving a decomposer with no command in the file they are reading — did not happen: the bullet
  quotes both commands in full and the pointer is corroboration rather than the payload.

#### V8 Ref: `embedded` measurement status across the branch's `_workflow` artifacts
- **Document claim**: `plan/track-9.md:5` — "the `embedded` runner has never been measured on this
  branch at all", read against `:68`, `:81` and `:89`.
- **Search performed**: `Read` of `plan/track-9.md` in full; `git diff HEAD` over it; `grep -n` for
  `embedded|Embedded` across `plan/track-9.md`, `plan/track-10.md` and `implementation-plan.md`;
  `grep -rniE "embedded.*(scenario|Tests run|BUILD)"` over `_workflow/`; `Read` of
  `implementation-plan.md:662-745`.
- **Code location**: `plan/track-9.md:5`, `:43`, `:68`, `:81`, `:89`, `:103`;
  `plan/track-10.md:34`, `:158`; `implementation-plan.md:664`, `:671-672`, `:688-690`, `:721`.
- **Actual signature/role**: no `embedded` run exists anywhere in the branch's artifacts. Track 10's
  two claims are identification only — "the executing Cucumber runner is `embedded`'s
  `EmbeddedGraphFeatureTest`", derived from the `sequential-tests` group filter rejecting
  `YTDBGraphFeatureTest`'s child descriptions — and `plan/track-9.md:63` records that the conclusion
  drawn from that wiring was itself withdrawn. The sweep for an `embedded` scenario count or build
  result returns only forward-looking plan text. The plan file independently carries the `core` scope
  at `:664`, `:671-672` and `:721` and the "unsized until first run" phrasing at `:688-690`.
- **Verdict**: MATCHES
- **Detail**: Supports **Verify CR9**. The strengthened absolute ("at all") was the one clause that
  could have over-reached, since acceptance `:89` cites a Track 10 record about the same runner.
  Track 10 identified the runner; it never ran it, so the two statements occupy different registers
  and the absolute stands.

#### V9 Ref: clause count under "Today neither holds"
- **Document claim**: `plan/track-9.md:5` — "Today neither holds: the `core` suite does not terminate
  with the strategy enabled, the `embedded` runner has never been measured on this branch at all, and
  a filter on a non-root alias is discarded without declining".
- **Search performed**: `git diff HEAD` over `plan/track-9.md` to read the pre-fix sentence beside the
  post-fix one; `grep -n "neither\|Neither"` over the file; a clause count of the preceding sentence's
  promises.
- **Code location**: `plan/track-9.md:5`; `:47` (the other `neither`, correct).
- **Actual signature/role**: the pre-fix colon expansion held two clauses and the post-fix one holds
  three. The preceding sentence's promises also number three. "Neither" governs two under any reading,
  so it now matches neither the expansion nor the promises.
- **Verdict**: MISMATCHES
- **Detail**: Feeds CR10. Contained to one sentence, with no gate, cross-reference or scope claim
  resting on it — the three clauses themselves are each accurate and each corroborated elsewhere in
  the file. That is why this is a suggestion and not a repeat of CR9.

## Summary

**PASS.** CR8 and CR9 both verify, and no blocker remains at the end of the three-iteration cap.

CR8's bullet reproduces Track 9's pinned pair command for command with the same reactor-exclusion
reasoning, its repeat-install rule does not contradict Track 9's stronger one and explicitly imports
it, and its cross-reference resolves to `plan/track-9.md:79-81`. The premise it rests on holds: all
four of Track 11's named production classes live under `core/src/main/java/`, so a stale installed jar
really would leave item 6's no-regression claim vacuous. A repo-wide sweep confirms the two pinned-pair
sites are now the only `-pl embedded` occurrences in the plan artifacts, so no reader is left reaching
for the bare command.

CR9's rewrite agrees with item 1's "unsized" language and acceptance bullet 2's "never been measured"
claim, no sentence in the file asserts a measured `embedded` failure, and the track file has converged
onto `implementation-plan.md`, which was already `core`-scoped for non-termination. The strengthened
absolute survives the one artifact that could have refuted it: Track 10 identified the `embedded`
runner as the executing one from a surefire group filter and never ran it.

One residual, **not a blocker**: CR10 (suggestion, mechanical) — the CR9 edit added a third clause
under "Today neither holds", so a two-item word heads a three-item list. The repair is "Today none of
that holds", a two-word substitution the orchestrator can apply and close on without a further
verification round.
