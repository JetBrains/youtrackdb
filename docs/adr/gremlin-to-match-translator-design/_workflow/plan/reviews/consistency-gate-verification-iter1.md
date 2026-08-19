<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 2, suggestion: 0}
index:
  - {id: CR6, sev: should-fix, loc: "plan/track-9.md:81", anchor: "### CR6 ", cert: V1, basis: "the newly pinned ./mvnw -pl embedded test resolves youtrackdb-core from ~/.m2 (installed 2026-07-02), so the embedded half of the gate measures a month-old core, not the branch"}
  - {id: CR7, sev: should-fix, loc: "plan/track-9.md:5", anchor: "### CR7 ", cert: V2, basis: "Purpose, the item-1 diagram node and the In-scope-new artifact line still scope the deliverable to core while acceptance bullet 9 cites the Purpose as the two-runner gate's authority"}
verdicts:
  - {id: CR1, verdict: VERIFIED}
  - {id: CR2, verdict: VERIFIED}
  - {id: CR3, verdict: VERIFIED}
  - {id: CR4, verdict: VERIFIED}
  - {id: CR5, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 2, matches: 0}
cert_index:
  - {id: V1, verdict: MISMATCHES, anchor: "#### V1 "}
  - {id: V2, verdict: MISMATCHES, anchor: "#### V2 "}
flags: [CONTRACT_OK]
-->

# Consistency gate verification — iteration 1 (2026-08-03, 11-track plan)

All five iteration-1 findings verify. CR3, CR4 and CR5 were mechanical and land exactly as
proposed; CR1 and CR2 were the two user-decided ones and both fixes hold. Two new findings come out
of the re-scan, both on CR1's blast radius: the `embedded` runner the fix newly commits to is
pinned to a command that resolves `youtrackdb-core` from the local repository rather than the
working tree, and three places in `plan/track-9.md` still describe a `core`-only deliverable —
including the Purpose that acceptance bullet 9 cites as its own authority. Neither blocks; the
overall verdict is **PASS**.

Artifacts re-read: `implementation-plan.md` (Track 9 / Track 11 entries, `## Implementation
state`), `plan/track-9.md` and `plan/track-11.md` in full, the amended lines in `plan/track-7.md`,
`plan/track-8.md` and `plan/track-10.md`, the four review files under `plan/track-9/reviews/`, and
live source under `core/src/main/java` plus `embedded/pom.xml`.

**Reference-accuracy caveat.** One PSI query was attempted for the CR3 symbol re-check —
`steroid_execute_code` against the open project (registered as `design.md`, path correct), reading
declarations and line numbers for `walkChild` / `walkFork` and the four `GremlinStepWalker`
members. It timed out, the documented cold-kotlinc failure on this repository, now five plan
reviews running. Per the no-retry instruction the CR3 verdict rests on `grep -rn` over
`core/src/main/java` plus direct `Read` of the declaration sites; **Verify CR3** is the one verdict
below that PSI would strengthen. Declaration-level facts (a member exists, at this line, in this
class) are reliable from grep. The negative half of CR3 — that `GremlinStepWalker` declares neither
`walkChild` nor `walkFork` — is a same-file absence rather than a repository-wide "no other caller"
claim, so grep establishes it: the file's only occurrences of either name are javadoc `{@link
RecognitionContext#walkChild}` references. CR1, CR2, CR4 and CR5 are document-and-build facts with
no symbol-reference dependency; V1 rests on `embedded/pom.xml`, the module's test sources, and the
on-disk state of `~/.m2`.

## Verification certificates

#### Verify CR1: Track 11's regression gate reads a baseline half Track 9 never produced
- **Original issue**: Track 11 item 6 and its acceptance bullet 8 read a two-runner baseline, and
  Track 9's acceptance bullet 9 committed to both runners, but no Track 9 Plan-of-Work item
  measured `embedded`. The producing side named it in exactly one place — the acceptance bullet
  that assumed the work had happened.
- **Fix applied**: Track 9 item 1 (`plan/track-9.md:68`) now runs `EmbeddedGraphFeatureTest` with
  its own translator-on/off A/B, its own completion check and its own failure set, landing in the
  same artifact as a labelled second half, flagged **unsized** with the same ESCALATE trigger. Item
  3 (`:70`) extends the re-measurement to both runners. A new acceptance bullet (`:81`) pins
  `./mvnw -pl embedded test`; the artifact bullet (`:82`) now says "for **both** runners". The
  plan's Track 9 `**Scope:**` line (`implementation-plan.md:688-690`) names the two-runner artifact
  and flags the `embedded` A/B as unsized until first run.
- **Re-check**:
  - Search/trace performed: `Read` of `plan/track-9.md` `## Plan of Work` and `## Validation and
    Acceptance` in full and of `plan/track-11.md` item 6 and acceptance bullets;
    `grep -n "embedded" plan/track-9.md`; `git diff` against HEAD for the applied hunks. Tool:
    `grep` plus `Read`.
  - Code location: `plan/track-9.md:68`, `:70`, `:81`, `:82`, `:89`, `:107`;
    `plan/track-11.md:68`; `implementation-plan.md:688-690`.
  - Current state: the supply/consume contract closes. `embedded` now appears on the producing side
    five times in `plan/track-9.md` — items 1 and 3, acceptance bullets 2 and 9, and the
    `**Signatures:**` line — where before it appeared only in bullet 9. Track 11 item 6's "Track
    9's baseline records both and this re-run reads both" now has a producer for each half. The
    runner is real: `embedded/src/test/java/com/jetbrains/youtrackdb/shade/
    EmbeddedGraphFeatureTest.java`, `@RunWith(Cucumber.class)` over the same two feature roots the
    `core` runner uses, its javadoc stating it "runs as part of the default surefire test phase" —
    so the criterion is reachable by the command shape the bullet pins.
- **Regression check**: checked four adjacent claims.
  (1) **The `~8–14 files` Scope figure still holds.** The `embedded` half adds measurement and one
  labelled section of an artifact Track 9 was already writing, not new files; any `embedded`-side
  defect it uncovers falls under the same "unsized until localized" clause and ESCALATE trigger the
  Scope line already carries, which item 1 now restates for the second runner. Clean.
  (2) **No contradiction with the R6 pinned-commands bullet (`:90`).** R6's "stops at the third"
  reasoning is specific to `core/pom.xml`'s five surefire executions. `embedded/pom.xml` declares
  one `maven-surefire-plugin` with no `<executions>` block beyond the default and no group filter,
  and its three test classes are `EmbeddedGraphFeatureTest`, `ShadedJarSmokeTest` and `ShadedJarIT`
  (the last failsafe-bound). A single execution runs every class and reports at the end, so there
  is no early-abort hazard for `-Dmaven.test.failure.ignore=true` to defuse, and R6's universal
  ("every full-suite gate here") is loose wording rather than a live contradiction. Clean.
  (3) **Track 11 needed no change.** Item 6 and `**Signatures:**` already name both runners. Its
  acceptance bullet at `:86` says "the full TinkerPop Cucumber suite" without qualifying the
  runner, but item 6 two sections earlier is explicit, so a reader is not misled. Clean.
  (4) **New issues.** The pinned command resolves `youtrackdb-core` from `~/.m2` rather than the
  reactor (CR6), and three `core`-only descriptions survive in Track 9, one of which acceptance
  bullet 9 cites by name (CR7).
- **Verdict**: VERIFIED (fix correct; two adjacent issues raised as CR6 and CR7)

#### Verify CR2: contradicting terminator-ownership lines and an over-broad sweep claim
- **Original issue**: `96c37d3e74` amended one line in each completed track file and left the next
  line saying the opposite — `track-7.md:126` ("the ordered post-process carrier that Track 9's
  terminators register into") directly under an amended `:125`, and `track-8.md:209` ("Track 9's
  Cucumber re-run validates union end to end") directly under an amended `:208`. Separately,
  `track-10.md:11` claimed those Track 7 / Track 8 references "carry bracketed amendments of their
  own" when 24 of 26 did not.
- **Fix applied**: the user chose fix-the-contradictions-and-narrow-the-claim over a full sweep.
  Bracketed amendments were added to both `**Inter-track dependencies:**` lines, and
  `track-10.md:11` was narrowed to claim four amended lines and to record the rest as
  as-of-completion text the plan file supersedes.
- **Re-check**:
  - Search/trace performed: `grep -n "\[Amended" plan/track-7.md plan/track-8.md` and
    `grep -n "2026-08-03"` over the same two files, to count amended lines independently of the
    claim; `grep -n "Track 9" implementation-plan.md` with a `Read` of every hit that touches
    terminator ownership, to test the "supersedes" half. Tool: `grep` plus `Read`.
  - Code location: `plan/track-7.md:125-126`; `plan/track-8.md:208-209`; `plan/track-10.md:11`;
    `implementation-plan.md:556`, `:562`, `:606`, `:615`, `:636-644`, `:670`, `:698`, `:721`,
    `:735`, `:737`.
  - Current state: **the narrowed claim is literally accurate.** The `2026-08-03` sweep returns
    exactly four lines across the two files — `track-7.md:125` and `:126`, `track-8.md:208` and
    `:209` — and they are exactly "each file's **Out of scope** and **Inter-track dependencies**
    line", as the claim says. Two carry the square-bracket `[Amended 2026-08-03: …]` form and two
    carry the round-parenthetical form `(Track 11 after the 2026-08-03 split; …)`; "bracketed"
    covers both, and it is the word the original sentence already used for the parenthetical pair.
    Both adjacent-line contradictions are gone: a reader of `track-7.md:126` now gets "the split
    moved the terminators to Track 11 — its four `ListShapingOp` implementations are what register
    into this carrier" inside the same line, and `track-8.md:209` gets "the split moved the
    no-regression re-run to Track 11 item 6; Track 9 publishes the baseline that re-run reads".
    The "supersedes" half also holds: every `Track 9` hit in `implementation-plan.md` that touches
    terminator ownership carries an inline bracketed correction (`:556`, `:562`, `:606`, `:615`,
    `:642`), and the operative surfaces — both Checklist entries, the `## Implementation state`
    narrative and table, and the D3 conformance sentence — assign the terminators to Track 11
    without qualification.
- **Regression check**: checked the claim's own precision and the unamended residue. The appositive
  "the two places a downstream track file points at by name" is loose — it means the two
  `## Interfaces and Dependencies` sections, one per file, which `plan/track-11.md:102-103` names
  by track number — but it is not a factual error. Track 8's DR-U4 (`track-8.md:55`) and
  `track-8.md:185` remain unamended, which is now what `track-10.md:11` says rather than what it
  denies. The surrounding Numbering-note prose still reads coherently with the longer amendment
  spliced in. Clean.
- **Verdict**: VERIFIED

#### Verify CR3: `walkChild` / `walkFork` attributed to `GremlinStepWalker`
- **Original issue**: three places attributed `walkChild` and `walkFork` to `GremlinStepWalker`,
  which declares neither. A decomposer following the plan's Track 11 Scope line would open that
  class looking for a method that is not there.
- **Fix applied**: `implementation-plan.md:711` now reads `RecognitionContext.walkChild`;
  `plan/track-11.md:101` splits the In-scope-modified clause so the `walkChild` combinator path is
  attributed to `RecognitionContext` and gated through the `SubTraversalPredicateAdapter` override;
  `:104` splits `walkFork` onto `UnionForkHost` (`:40`) / `UnionForkHostImpl` (`:74`) and
  `walkChild` onto `RecognitionContext` (`:333`), implemented on `WalkerContext` (`:598`) and
  `SubTraversalPredicateAdapter` (`:413`).
- **Re-check**:
  - Search/trace performed: **PSI attempted and timed out** (see the caveat above). Fallback:
    `grep -rn "walkChild" core/src/main/java`, `grep -rn "walkFork" core/src/main/java`, and a
    declaration-shaped grep for `POST_UNION_RECOGNISERS` / `dispatchAll` /
    `postUnionSuffixTranslatable` / `subWalk` over `GremlinStepWalker.java`, with a `Read` of
    `GremlinStepWalker.java:395-425`.
  - Code location: every cited line resolves. `RecognitionContext.java:333` —
    `SubTraversalPredicateAdapter walkChild(Traversal.Admin<?, ?> child);` (interface declaration).
    `WalkerContext.java:598` and `SubTraversalPredicateAdapter.java:413` — both
    `public SubTraversalPredicateAdapter walkChild(Traversal.Admin<?, ?> child) {`.
    `UnionForkHost.java:40` — `@Nullable GremlinToMatchTranslator.TranslationResult walkFork(`.
    `UnionForkHostImpl.java:74` — `public GremlinToMatchTranslator.TranslationResult walkFork(`.
    The four members still attributed to `GremlinStepWalker` are all declared there:
    `POST_UNION_RECOGNISERS` at `:193`, `dispatchAll` at `:310`, `postUnionSuffixTranslatable` at
    `:370`, `subWalk` at `:399`.
  - Current state: all six line numbers are exact, and `GremlinStepWalker` declares neither
    `walkChild` nor `walkFork` — its only occurrences of either name are the javadoc `{@link
    RecognitionContext#walkChild}` at `:389` and the delegation `WalkerContext.walkChild` makes
    back into `GremlinStepWalker.subWalk`, which the plan already names correctly.
- **Regression check**: checked three things. (1) A sweep of `_workflow/**` for
  `GremlinStepWalker.walkChild` / `GremlinStepWalker.walkFork` returns no residue; the one line that
  still contains both strings is `plan/track-11.md:101`, where a semicolon separates the
  `GremlinStepWalker` clause from the `walkChild` clause and each carries its own owning class.
  (2) The two documents now render the gate from different angles — the plan's Scope line calls it
  `RecognitionContext.walkChild` (the path), the track file calls it `SubTraversalPredicateAdapter`
  (the gate). CR3's proposed fix admitted both, DR-T2 and item 4 agree with each, and they describe
  one mechanism. Not a contradiction. (3) The pre-existing `subWalk (:399-411)` range hint is
  short — the method runs `:399-418`. It predates this fix, C15 recorded the declaration line as
  correct, and a range hint is not a reference an executor resolves. Not raised.
- **Verdict**: VERIFIED

#### Verify CR4: T9 dropped from Track 9's finding partition
- **Original issue**: Track 9 listed the terminator-facing findings as "T1–T7, T10–T16, T18" while
  Track 11 listed "T1–T7, T9–T16, T18". T9 appeared in neither of Track 9's two sets, so by Track
  9's accounting it belonged to no track.
- **Fix applied**: `plan/track-9.md:100` now reads `T1–T7, T9–T16, T18`.
- **Re-check**:
  - Search/trace performed: `grep -oE '^### [A-Z]+[0-9]+ '` over each of the four files in
    `plan/track-9/reviews/` to enumerate the actual finding IDs, then set arithmetic against both
    `## Artifacts and Notes` lists; `grep -rn "T10–T16"` across `_workflow/**` outside the review
    directories to catch any other copy of the old range. Tool: `grep`.
  - Code location: `plan/track-9.md:100`; `plan/track-11.md:97`;
    `plan/track-9/reviews/technical-iter1.md` (T1–T11),
    `technical-gate-verification-iter2.md` (T12–T16),
    `technical-gate-verification-iter3.md` (T17–T18), `risk-iter1.md` (R1–R7).
  - Current state: the on-disk IDs are exactly T1–T18 and R1–R7, and the two lists now agree
    verbatim. The 18 technical findings partition with no gap and no double-claim:
    terminator-facing {T1–T7, T9–T16, T18} = 16, this track {T8} = 1, retired {T17} = 1. The seven
    risk findings partition the same way: {R2, R7} to Track 11, {R1, R3, R4, R5, R6} to Track 9.
    Every ID appears in exactly one bucket.
- **Regression check**: the sweep for the old `T10–T16` range returns nothing outside the review
  directories, so no third copy drifted. `plan/track-11.md:97` was already correct and is
  unchanged, so the fix converged the two lists rather than moving the disagreement. Clean.
- **Verdict**: VERIFIED

#### Verify CR5: a bare `(D3)` the same commit's conformance sentence disowns
- **Original issue**: `implementation-plan.md:719` ended "Track 11 owns the list-shaping terminators
  and the JMH harness (D3)", tagging the terminators with a decision record that line 735 —
  rewritten in the same commit — says does not cover them.
- **Fix applied**: the bare `(D3)` was dropped. The sentence now ends "…the list-shaping terminators
  and the JMH harness, measured against the baseline Track 9 publishes."
- **Re-check**:
  - Search/trace performed: `Read` of `implementation-plan.md:719-740`; `grep -n "(D3)"` over
    `implementation-plan.md`, `plan/track-9.md` and `plan/track-11.md`. Tool: `grep` plus `Read`.
  - Code location: `implementation-plan.md:721` (the narrative, renumbered by CR1's two-line
    insertion in the Track 9 Scope block), `:737` (the conformance sentence).
  - Current state: the narrative carries no decision-record tag on Track 11, and `:737` still reads
    "D3 is *all-or-nothing decline*, not the terminators — it is enforced by every recogniser,
    including Track 11's four, whose mid-traversal and child-path declines are the split's new D3
    surface." The two sentences now agree.
- **Regression check**: the remaining `(D3)` occurrences are each correct in their own right —
  `implementation-plan.md:22` and `:115` state the all-or-nothing rule, `plan/track-11.md:33` and
  `:65` invoke it as the last-step / mid-traversal decline rule, which is what `:737` says D3 is.
  Dropping the tag does not leave Track 11 anomalous in the narrative: Track 9's sentence carries no
  tag either, and the tags that remain (D5 on Track 5, D8 on Track 8) mark decision records those
  tracks implement. Clean.
- **Verdict**: VERIFIED

## Findings

### CR6 [should-fix]
**Certificate**: V1
**Location**: `plan/track-9.md:81` (`## Validation and Acceptance`, the new `embedded` bullet);
read against `:90` (the R6 pinned-commands bullet) and `plan/track-11.md:68` (item 6, which re-runs
the same gate)

**Issue**: The command the new bullet pins does not compile the branch's `core`. `./mvnw -pl
embedded test` puts only the `embedded` module in the reactor, so its `youtrackdb-core` dependency
resolves from the local repository rather than the working tree. On this machine that resolves to a
jar installed on 2026-07-02 — before Tracks 7, 8 and 10, and a month before item 2's filter fix.
The `embedded` completion gate and the `embedded` A/B would both measure a `core` that has none of
the code they exist to test, and item 3's post-fix re-measurement would show no movement because
the fix is not in the jar under test.

**Evidence**: `embedded/pom.xml:53-56` declares `io.youtrackdb:youtrackdb-core:${project.version}`
at compile scope and `:71-76` the matching `test-jar`.
`~/.m2/repository/io/youtrackdb/youtrackdb-core/0.5.0-SNAPSHOT/` holds
`youtrackdb-core-0.5.0-SNAPSHOT.jar` dated 2026-07-02 14:05 and `-tests.jar` dated 2026-07-02
14:11, and `maven-metadata-local.xml` records `<localCopy>true</localCopy>` with
`lastUpdated 20260702121006`, so Maven prefers that install over the remote timestamped snapshots
also present in the directory. The test-jar is load-bearing beyond the production classes:
`EmbeddedGraphFeatureTest`'s `@CucumberOptions` reads
`classpath:/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/features` and binds
`GraphFeatureWorld`, both of which ship in that jar — so a stale install also means stale local
feature files, including the `GQL Match Support` feature where the `core` runner stalls. The rest
of the bullet is sound: the `embedded` module has one surefire execution with no group filter and
`EmbeddedGraphFeatureTest` runs in the default `test` phase, so the runner is reachable and R6's
`core`-specific early-abort hazard does not apply here.

**Proposed fix**: Pin a command that builds `core` from source in the same invocation, and say
which. Three renderings, with different costs. (a) `./mvnw -pl embedded -am test` — rebuilds `core`
into the reactor, but also runs `core`'s test phase, which R6 documents as ~31 minutes with an
early abort. (b) `./mvnw -pl core -am install -DskipTests` followed by `./mvnw -pl embedded test` —
two commands, no `core` test cost, and the shape that matches how the branch's other gates are run.
(c) `./mvnw -pl core,embedded test -Dmaven.test.failure.ignore=true` — one invocation covering both
runners, at the full `core` suite cost the track is already paying elsewhere. Whichever is chosen,
add the note R6 already carries in its own form: a future reader must not mistake a run against an
installed jar for a run against the branch.

**Classification**: design-decision
**Justification**: multiple plausible fix renderings whose costs differ materially (a full `core`
suite run per `embedded` measurement versus a separate install step), so the orchestrator cannot
pick one without making a gate-cost choice.

### CR7 [should-fix]
**Certificate**: V2
**Location**: `plan/track-9.md:5` (`## Purpose / Big Picture`), `:55` (the `## Context and
Orientation` Mermaid node), `:82` (`## Validation and Acceptance`, the both-runners artifact
bullet) and `:103` (`## Interfaces and Dependencies` **In scope (new)**); read against `:89`
(acceptance bullet 9)

**Issue**: Three descriptions of the deliverable still scope it to `core`, and acceptance bullet 9
cites one of them as its own authority. Bullet 9 reads "a suite that completes in `core` and hangs
in `embedded` has not met **this track's Purpose**" — but the Purpose at `:5` promises only that
"the `core` TinkerPop feature suite completes in a single fork with the translator on". Read
literally, bullet 9's appeal fails: the Purpose it points at is satisfied by a `core`-only run. The
In-scope-new line and the diagram node carry the same lag in a milder form.

**Evidence**: After the CR1 fix, `plan/track-9.md` commits to both runners in item 1 (`:68`), item 3
(`:70`), acceptance bullets 1–3 (`:80-82`) and bullet 9 (`:89`), and names
`EmbeddedGraphFeatureTest` in `**Signatures:**` (`:107`). The three sites above did not move. `:103`
still describes "the per-directory and single-fork Cucumber baseline artifact" — vocabulary that
fits only `core`, since item 1 gives the `embedded` half a translator-on/off A/B, a completion check
and a failure set with no per-directory decomposition. `:82` inherits the same phrasing and asks for
"the per-directory and single-fork Cucumber failure sets for **both** runners", a shape item 1 does
not produce for the second one. `:55`'s node label reads "item 1 artifact: per-directory Cucumber
baseline". The plan-file entry is unaffected: `implementation-plan.md:688-690` names the two-runner
artifact explicitly.

**Proposed fix**: Widen the three descriptions to the scope the rest of the track already carries.
At `:5`, name both runners in the opening sentence so bullet 9's cross-reference resolves. At
`:103`, describe the artifact as one file with a per-runner half rather than by `core`'s run shape,
and adjust `:82` to ask each runner for its own recorded shape instead of imposing `core`'s. At
`:55`, relabel the node to "two-runner Cucumber baseline".

**Classification**: mechanical
**Justification**: current-state claim about the track file's own settled scope — the two-runner
decision is already made and recorded in items 1 and 3 and four acceptance bullets, so aligning the
three lagging descriptions has one unambiguous rendering and changes nothing the plan is trying to
achieve.

## Evidence base

Two certificates, both supporting new findings. Tool recorded per certificate; PSI was attempted
once and timed out, so every entry rests on `grep`, direct `Read`, and on-disk inspection of
`~/.m2`. See the reference-accuracy caveat above.

#### V1 Ref: `./mvnw -pl embedded test` dependency resolution
- **Document claim**: `plan/track-9.md:81` — "`./mvnw -pl embedded test` completes with the
  translator on, under the same three conditions, with its scenario count in the same range as its
  own translator-off run."
- **Search performed**: `Read` of `embedded/pom.xml` (dependencies, surefire and failsafe blocks,
  profiles); `find embedded/src/test -name "*.java"`; `Read` of
  `EmbeddedGraphFeatureTest.java:1-60` and `ShadedJarSmokeTest.java:1-40`;
  `ls -la ~/.m2/repository/io/youtrackdb/youtrackdb-core/0.5.0-SNAPSHOT/` and `cat` of its
  `maven-metadata-local.xml`.
- **Code location**: `embedded/pom.xml:53-56` (core, compile scope), `:71-76` (core test-jar),
  `:405-411` (the single surefire plugin declaration, no `<executions>`), `:376-401` (failsafe,
  `integration-test` / `verify` only);
  `embedded/src/test/java/com/jetbrains/youtrackdb/shade/EmbeddedGraphFeatureTest.java:25-42`.
- **Actual signature/role**: the runner is reachable by the pinned command — one surefire
  execution, no group filter, `EmbeddedGraphFeatureTest` picked up by the default `*Test` include
  in the `test` phase, before the shade plugin's `package` binding. What the command does not do is
  build `core`: without `-am` the reactor holds `embedded` alone, and
  `youtrackdb-core:0.5.0-SNAPSHOT` resolves to the local install of 2026-07-02, flagged
  `<localCopy>true</localCopy>`.
- **Verdict**: MISMATCHES
- **Detail**: Feeds CR6. The failure mode runs in the worst direction — a stale `core` predating
  most of the branch's translator work would very likely complete, so the gate reads as a pass
  while measuring nothing the track built. The stale test-jar compounds it by supplying the local
  feature files too. The `~/.m2` timestamps are this machine's state rather than a property of the
  plan; the resolution semantics that make them load-bearing are not.

#### V2 Ref: `core`-only residue in `plan/track-9.md` after the CR1 widening
- **Document claim**: `plan/track-9.md:89` — "a suite that completes in `core` and hangs in
  `embedded` has not met **this track's Purpose**"; `:82` — "The per-directory and single-fork
  Cucumber failure sets for **both** runners are recorded"; `:103` — "the per-directory and
  single-fork Cucumber baseline artifact under `plan/track-9/`".
- **Search performed**: `grep -n "embedded" plan/track-9.md` (5 hits, all listed in Verify CR1); a
  grep for `per-directory and single-fork`, the `core` TinkerPop-feature-suite phrase, and
  `per-directory Cucumber baseline` over the same file; `Read` of the Purpose, the Mermaid block
  and `## Interfaces and Dependencies` in full.
- **Code location**: `plan/track-9.md:5`, `:55`, `:82`, `:89`, `:103`.
- **Actual signature/role**: the Purpose sentence names `core` and no other runner; the diagram node
  reads "item 1 artifact: per-directory Cucumber baseline"; the In-scope-new line describes one
  artifact in `core`'s run vocabulary. Item 1 gives the `embedded` half no per-directory
  decomposition, so `:82`'s "per-directory … for **both** runners" over-specifies the second half.
- **Verdict**: MISMATCHES
- **Detail**: Feeds CR7. The self-reference at `:89` is what raises this above wording lag: an
  acceptance bullet justifying itself by pointing at a Purpose that does not say what the bullet
  claims gives a decomposer a documented reason to narrow the gate back to `core` — the retreat the
  user declined when choosing the two-runner option.

## Summary

**PASS.** All five iteration-1 findings verify: CR1 and CR2 (the two user-decided ones) and CR3, CR4
and CR5 (mechanical) are each applied correctly, with no fix landing wrong and no prior finding
still open. No blockers remain.

Two new should-fix findings sit on CR1's blast radius and are carried forward rather than blocking:
CR6 (design-decision — the pinned `embedded` command resolves `core` from the local repository, so
the gate can pass against a stale jar) and CR7 (mechanical — Purpose, the item-1 diagram node and
the In-scope-new artifact line still describe a `core`-only deliverable, and acceptance bullet 9
cites the Purpose as its authority).
</content>
