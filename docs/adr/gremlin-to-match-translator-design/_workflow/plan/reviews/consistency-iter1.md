<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 4, suggestion: 1}
index:
  - {id: CR1, sev: should-fix, loc: "plan/track-9.md:68", anchor: "### CR1 ", cert: C30, basis: "Track 11 item 6 consumes an embedded-runner baseline half that no Track 9 Plan-of-Work item produces"}
  - {id: CR2, sev: should-fix, loc: "plan/track-7.md:126", anchor: "### CR2 ", cert: C26, basis: "adjacent Interfaces lines in track-7/track-8 contradict on terminator ownership; track-10 asserts the amendment sweep was complete"}
  - {id: CR3, sev: should-fix, loc: "implementation-plan.md:711", anchor: "### CR3 ", cert: C16, basis: "walkChild/walkFork attributed to GremlinStepWalker; they live on RecognitionContext and UnionForkHost"}
  - {id: CR4, sev: should-fix, loc: "plan/track-9.md:99", anchor: "### CR4 ", cert: C27, basis: "T9 dropped from both halves of Track 9's finding partition while Track 11 claims it"}
  - {id: CR5, sev: suggestion, loc: "implementation-plan.md:719", anchor: "### CR5 ", cert: C29, basis: "Implementation-state narrative tags Track 11's terminators (D3) against the same commit's conformance sentence"}
evidence_base: {section: "## Evidence base", certs: 31, matches: 22}
cert_index:
  - {id: C1,  verdict: MATCHES,    anchor: "#### C1 "}
  - {id: C2,  verdict: MATCHES,    anchor: "#### C2 "}
  - {id: C3,  verdict: MATCHES,    anchor: "#### C3 "}
  - {id: C4,  verdict: MATCHES,    anchor: "#### C4 "}
  - {id: C5,  verdict: PARTIAL,    anchor: "#### C5 "}
  - {id: C6,  verdict: PARTIAL,    anchor: "#### C6 "}
  - {id: C7,  verdict: MATCHES,    anchor: "#### C7 "}
  - {id: C8,  verdict: PARTIAL,    anchor: "#### C8 "}
  - {id: C9,  verdict: MATCHES,    anchor: "#### C9 "}
  - {id: C10, verdict: MATCHES,    anchor: "#### C10 "}
  - {id: C11, verdict: MATCHES,    anchor: "#### C11 "}
  - {id: C12, verdict: MATCHES,    anchor: "#### C12 "}
  - {id: C13, verdict: MATCHES,    anchor: "#### C13 "}
  - {id: C14, verdict: MATCHES,    anchor: "#### C14 "}
  - {id: C15, verdict: MATCHES,    anchor: "#### C15 "}
  - {id: C16, verdict: MISMATCHES, anchor: "#### C16 "}
  - {id: C17, verdict: MATCHES,    anchor: "#### C17 "}
  - {id: C18, verdict: MATCHES,    anchor: "#### C18 "}
  - {id: C19, verdict: MATCHES,    anchor: "#### C19 "}
  - {id: C20, verdict: MATCHES,    anchor: "#### C20 "}
  - {id: C21, verdict: MATCHES,    anchor: "#### C21 "}
  - {id: C22, verdict: MATCHES,    anchor: "#### C22 "}
  - {id: C23, verdict: MATCHES,    anchor: "#### C23 "}
  - {id: C24, verdict: MATCHES,    anchor: "#### C24 "}
  - {id: C25, verdict: MATCHES,    anchor: "#### C25 "}
  - {id: C26, verdict: MISMATCHES, anchor: "#### C26 "}
  - {id: C27, verdict: MISMATCHES, anchor: "#### C27 "}
  - {id: C28, verdict: MATCHES,    anchor: "#### C28 "}
  - {id: C29, verdict: PARTIAL,    anchor: "#### C29 "}
  - {id: C30, verdict: MISMATCHES, anchor: "#### C30 "}
  - {id: C31, verdict: PARTIAL,    anchor: "#### C31 "}
flags: [CONTRACT_OK]
-->

# Consistency review — iteration 1 (2026-08-03 re-validation, 11-track plan after the Track 9 split)

Five findings, none blocking. The 2026-08-03 inline replan (`96c37d3e74`) split the former Track 9
into Track 9 (Cucumber suite completion + the dropped per-alias filter) and Track 11 (list-shaping
terminators + JMH harness). Every load-bearing code reference in both pending track files resolves
against live source — the whole dropped-filter mechanism chain Track 9 rests on, and every fork-jar
step semantic Track 11 rests on. All five findings sit on the split's seams: one inter-track
artifact half with a consumer but no producer (CR1), stale terminator ownership left in two
completed track files (CR2), a member-to-class misattribution repeated in three places (CR3), a
review-finding partition that drops one finding (CR4), and one decision-record tag the same commit
contradicts fifteen lines later (CR5).

Artifacts compared: `implementation-plan.md`, `plan/track-9.md`, `plan/track-11.md`, the frozen
`design.md` (`design_gate=yes`, so all four axes ran), and the codebase at `96c37d3e74`.

**Reference-accuracy caveat — applies to every certificate below.** mcp-steroid is reachable and
the open IntelliJ project points at this working tree, but it is registered under the project name
`design.md`, and `steroid_execute_code` timed out on the first PSI query — the documented
cold-kotlinc failure on this repository, now four plan reviews running. Every certificate rests on
`grep`, direct source `Read`, and `javap` against the resolved fork jar. Declaration-level facts (a
symbol exists, at this line, with this modifier, in this class) are reliable. Negative reference
claims ("no other caller", "the only reader") are **not** established by these tools; where a
certificate depends on one it says so inline. C6, C12, and C16 are the certificates PSI would
strengthen most.

## Findings

### CR1 [should-fix]
**Certificate**: C30
**Location**: `plan/track-9.md` `## Plan of Work` items 1 and 3, and `## Validation and Acceptance`
bullets 1–2 and 9; consumed by `plan/track-11.md` `## Plan of Work` item 6 and `## Validation and
Acceptance` bullet 8

**Issue**: Track 11's regression gate reads a baseline half that no Track 9 Plan-of-Work item
produces. Track 9's acceptance bullet 9 commits to both Cucumber runners — "The completion gate and
the baseline artifact cover **both**; a suite that completes in `core` and hangs in `embedded` has
not met this track's Purpose" — and Track 11's item 6 spends that commitment: "Track 9's baseline
records both and this re-run reads both." But Track 9's item 1 records only the `core` per-directory
table ("1888 upstream scenarios, 42 failures, plus the 42 local scenarios"), item 3's re-measurement
inherits the same scope, and the first acceptance bullet pins only the `core` command
(`./mvnw -pl core -o surefire:test@gremlin-feature-compliance-tests`). `embedded` appears on the
producing side in exactly one place: the acceptance bullet that assumes the work already happened.

**Evidence**: `EmbeddedGraphFeatureTest` exists at
`embedded/src/test/java/com/jetbrains/youtrackdb/shade/EmbeddedGraphFeatureTest.java` (C9), so the
runner is real and the criterion is reachable. What is missing is the Plan-of-Work item that
measures it. Track 10 recorded `embedded` as the *executing* Cucumber runner at that time
(`plan/track-10.md:34`), which is why acceptance bullet 9 was added; Track 9's Clarifications then
withdrew the conclusion drawn from that discovery but left the two-runner acceptance standing. The
`embedded` module also carries no measured A/B in Track 9's `## Context and Orientation` — the
translator-on/off table is `core`-only — so the second half is unsized as well as unassigned.

**Proposed fix**: Extend Track 9 item 1 to run and record the `embedded` runner alongside `core`
(the A/B, the completion check, and the failure set), and extend item 3's re-measurement the same
way, so the committed artifact under `plan/track-9/` has the two halves Track 11 item 6 reads. If
the `embedded` run proves prohibitively expensive or needs its own diagnosis, the alternative is to
narrow acceptance bullet 9 and Track 11 item 6 to `core` and record `embedded` as a named deferral
— that choice changes what the branch's final gate covers.

**Classification**: design-decision
**Justification**: multiple plausible fix renderings, and the second retreats a validation
commitment the user made deliberately after Track 10's runner discovery.

### CR2 [should-fix]
**Certificate**: C26
**Location**: `plan/track-7.md` lines 5, 26, 33–35, 53, 62, 74, 87, 92–94, 101, 126;
`plan/track-8.md` lines 25, 37, 39, 52, 55, 93, 100, 185, 192, 209; the claim at
`plan/track-10.md:11`

**Issue**: The replan amended exactly one line in each completed track file — both the
`## Interfaces and Dependencies` **Out of scope** line — and left every sibling reference naming
Track 9 as the terminator owner. The two amended lines now sit directly above unamended lines that
say the opposite. In `plan/track-7.md`, line 125 reads "the four terminator recognisers (Track 11
after the 2026-08-03 split)" and line 126, the next line, reads "the ordered post-process carrier
that Track 9's terminators register into". In `plan/track-8.md`, line 208 reads "list-shaping
terminators (Track 11 after the 2026-08-03 split)" and line 209 reads "Track 9's Cucumber re-run
validates union end to end" — which after the split is Track 11's item 6, not Track 9's baseline
run.

Separately, `plan/track-10.md:11`'s amendment asserts the sweep was complete: "the split moved the
terminators to Track 11 anyway, so those Track 7 / Track 8 references are now stale and carry
bracketed amendments of their own." Twenty-four of the twenty-six lines carry no amendment.

**Evidence**: A sweep for `Track 9` / `Track 11` across `_workflow/**` (excluding the two pending
track files and the review directories) returns 15 matching lines in `plan/track-7.md` and 11 in
`plan/track-8.md`, of which one each was amended by `96c37d3e74`. Load-bearing among the unamended:
`plan/track-8.md:55` (DR-U4, the decision record that assigns the post-union suffix relaxation —
"List-shaping terminators (`fold`/…) remain Track 9"), `plan/track-8.md:185` ("Track 9 may relax the
'union is last step' rule"), and `plan/track-7.md:126` (the supply line for the carrier Track 11's
four ops register into). The plan file itself is correct throughout — Checklist, Component Map, D8,
and Invariants were all re-pointed — so an executor reading only `implementation-plan.md` gets the
right answer. An executor following Track 11's own `## Interfaces and Dependencies` into Track 7's
and Track 8's does not.

**Proposed fix**: Two admissible resolutions. (a) Sweep the remaining references in the two
completed track files with the bracketed-amendment convention `96c37d3e74` already used — this is
user-pause-gated, since editing completed-track content was settled as a user call at the
2026-07-15 re-validation, on this exact shape. (b) Leave the historical text and narrow the claim
at `plan/track-10.md:11` to what is true: the two out-of-scope lines were amended, and the rest is
as-of-completion text the plan file supersedes. At minimum the two adjacent-line contradictions
(`track-7.md:126`, `track-8.md:209`) should be resolved, since they sit inside the sections a
downstream track file points at by name.

**Classification**: design-decision
**Justification**: multiple plausible fix renderings, and the sweeping option edits completed-track
content, which the 2026-07-15 re-validation established as a user call rather than an orchestrator
one.

### CR3 [should-fix]
**Certificate**: C16
**Location**: `implementation-plan.md:711` (Track 11 `**Scope:**` line); `plan/track-11.md`
`## Plan of Work` item 4, `## Interfaces and Dependencies` **In scope (modified)** and
**Signatures**

**Issue**: `walkChild` and `walkFork` are attributed to `GremlinStepWalker` in three places. Neither
is a member of that class. The plan's Track 11 Scope line names "the two child gates
(`UnionStepRecogniser`, `GremlinStepWalker.walkChild`)"; the track's In-scope-modified entry reads
"`GremlinStepWalker` — the `POST_UNION_RECOGNISERS` allow-list plus the new registry entries and the
`walkChild` combinator gate"; the Signatures line lists "`GremlinStepWalker.POST_UNION_RECOGNISERS`,
`dispatchAll`, `postUnionSuffixTranslatable`, `subWalk` (`:399-411`), `walkFork`, `walkChild`" under
one class prefix.

**Evidence**: `walkChild` is declared on `RecognitionContext.java:333` and implemented on
`WalkerContext.java:598` and `SubTraversalPredicateAdapter.java:413`. `WalkerContext.walkChild`
delegates: `return GremlinStepWalker.subWalk(child, this, recognisers);` — `subWalk` is the
walker-side member, and the plan already names it correctly. `walkFork` is declared on
`UnionForkHost.java:40`, implemented on `UnionForkHostImpl.java:74`, and called from
`UnionStepRecogniser.java:95`. `GremlinStepWalker` declares neither. The four members the plan does
attribute to `GremlinStepWalker` are all present: `POST_UNION_RECOGNISERS` (`:193`, `Set.of(Count,
Range, Dedup)`), `dispatchAll` (`:310`), `postUnionSuffixTranslatable` (`:370`), `subWalk` (`:399`).

The gate mechanism the track describes is unaffected — DR-T2 and item 1 put the combinator gate on
`SubTraversalPredicateAdapter.supportsListShaping()` plus per-recogniser declines, and the union
gate on `UnionStepRecogniser` after its `host.walkFork(...)` call. Both are the right sites. Only
the class attribution is wrong, and a decomposer following the Scope line opens `GremlinStepWalker`
looking for a method that is not there.

**Proposed fix**: In the plan's Track 11 Scope line, change `GremlinStepWalker.walkChild` to
`RecognitionContext.walkChild` (or to `SubTraversalPredicateAdapter`, the class the gate actually
edits per item 1). In `plan/track-11.md`, change the In-scope-modified clause to "the `walkChild`
combinator path, gated on `SubTraversalPredicateAdapter`", and split the Signatures line so
`walkFork` reads `UnionForkHost.walkFork` / `UnionForkHostImpl.walkFork` and `walkChild` reads
`RecognitionContext.walkChild`, leaving `POST_UNION_RECOGNISERS` / `dispatchAll` /
`postUnionSuffixTranslatable` / `subWalk` under `GremlinStepWalker`.

**Classification**: mechanical
**Justification**: current-state claim about existing members with one unambiguous correct rendering
(rename the owning class); the plan's goals, scope, and gate mechanism are unchanged.

### CR4 [should-fix]
**Certificate**: C27
**Location**: `plan/track-9.md:99` (`## Artifacts and Notes`); `plan/track-11.md:97`
(`## Artifacts and Notes`)

**Issue**: The two files disagree on where finding T9 went, and Track 9's partition drops it
entirely. Track 9 says the terminator-facing findings are "T1–T7, T10–T16, T18, R2, R7" and that
"the findings that shaped this track are R1, R3, R4, R5, R6, and T8". Track 11 says "The
terminator-facing findings are T1–T7, T9–T16, T18, R2, and R7". T9 appears in neither of Track 9's
two sets, so by Track 9's accounting it belongs to no track.

**Evidence**: The four pre-split review files under `plan/track-9/reviews/` carry T1–T11
(`technical-iter1.md`), T12–T16 (`technical-gate-verification-iter2.md`), T17–T18
(`technical-gate-verification-iter3.md`), and R1–R7 (`risk-iter1.md`) — 18 technical plus 7 risk.
Track 9 accounts for 16 of the 18 technical findings and separately retires T17 ("a stale plan-file
file-count pair … retired by the split's rewritten Scope lines"), leaving T9 as the only unaccounted
one. Reading T9 (`technical-iter1.md:170`) settles it: its Location is "Track 9 Plan of Work item 7
and the last `## Validation and Acceptance` bullet", its subject is `LdbcBenchmarkState` and the JMH
on/off harness, and its proposed fix is the in-track `jmh-ldbc/src/test` installation check. Item 7
and that acceptance bullet are Track 11's after the split, and Track 11's item 7 already carries
T9's fix verbatim (the fixture-backed in-track run and the throwing installation check rather than a
Java `assert`). Track 11's list is right; Track 9's has an off-by-one at the boundary of the range.

**Proposed fix**: In `plan/track-9.md:99`, change "T1–T7, T10–T16, T18" to "T1–T7, T9–T16, T18" so
the two Artifacts sections agree and the 18 technical findings partition cleanly into
{terminator-facing: T1–T7, T9–T16, T18}, {this track: T8}, {retired: T17}.

**Classification**: mechanical
**Justification**: current-state claim about the contents of on-disk review files with one
unambiguous correct rendering; T9's own text places it in Track 11's item 7 and Track 11 already
claims it, so the correction preserves both tracks' intent.

### CR5 [suggestion]
**Certificate**: C29
**Location**: `implementation-plan.md:719` (`## Implementation state`, last sentence)

**Issue**: The narrative tags Track 11's terminators with a decision record the next paragraph says
does not cover them. Line 719 ends "Track 11 owns the list-shaping terminators and the JMH harness
(D3), measured against the baseline Track 9 publishes." Line 735, rewritten in the same commit,
corrects the older reading: "D3 is *all-or-nothing decline*, not the terminators — it is enforced by
every recogniser, including Track 11's four, whose mid-traversal and child-path declines are the
split's new D3 surface."

**Evidence**: D3 (`implementation-plan.md:168`) is "All-or-nothing translation, no hybrid prefix",
implemented in Track 2 and enforced by every recognizer. The trailing "(D3)" on line 719 is
inherited verbatim from the pre-split sentence ("Track 9 still owns list-shaping terminators +
Cucumber green + JMH harness (D3)"), which the same commit's conformance rewrite was written to
disown.

**Proposed fix**: Drop the bare "(D3)" from line 719, or expand it to match line 735: "…and the JMH
harness, whose mid-traversal and child-path declines are the split's new D3 surface, measured
against the baseline Track 9 publishes."

**Classification**: mechanical
**Justification**: current-state claim about the plan's own decision records with one unambiguous
correct rendering; the conformance sentence in the same document already states the intended
reading.

## Evidence base

Certificates grouped by review axis. Tool recorded per certificate; every entry used `grep`,
direct `Read`, or `javap` — never PSI. See the reference-accuracy caveat above.

**Axis — Plan ↔ Code.**

#### C1 Ref: `MatchExecutionPlanner.rebindFilters`
- **Document claim**: `plan/track-9.md` `## Context and Orientation` and `**Signatures:**` — "a
  private method of `MatchExecutionPlanner` called from `:2064` and `:5677`, both on the common
  path"; "The one routine that would populate them, `rebindFilters`, walks `matchExpressions`".
- **Search performed**: `grep -rn "rebindFilters" core/src/main/java`; `Read` of
  `MatchExecutionPlanner.java:6005-6035, 2055-2070, 5670-5682`.
- **Code location**: `core/.../sql/executor/match/MatchExecutionPlanner.java:6012` (declaration),
  `:2064` and `:5677` (call sites).
- **Actual signature/role**: `private void rebindFilters(Map<String, SQLWhereClause> aliasFilters)`,
  body `for (var expression : matchExpressions) { … item.getFilter().setFilter(newFilter); }`.
- **Verdict**: MATCHES
- **Detail**: Line numbers, visibility, the `matchExpressions` walk, and both call sites are exact.

#### C2 Ref: the three `MatchExecutionPlanner` entry points
- **Document claim**: `plan/track-9.md` — "Three entry points construct the planner:
  `SQLMatchStatement:191,201` (SQL `MATCH`), `GremlinToMatchStrategy:486` (the translator), and
  `GqlMatchStatement:88` (GQL)."
- **Search performed**: `grep -rn "new MatchExecutionPlanner" core/src/main/java`.
- **Code location**: `SQLMatchStatement.java:191` and `:201`; `GremlinToMatchStrategy.java:486`;
  `GqlMatchStatement.java:88`.
- **Actual signature/role**: `new MatchExecutionPlanner(this)` at both `SQLMatchStatement` sites
  (`createExecutionPlan` / `createExecutionPlanNoCache`); `new MatchExecutionPlanner(inputs)` at the
  strategy; `new MatchExecutionPlanner(ir.pattern(), ir.aliasClasses(), ir.aliasFilters())` at GQL.
- **Verdict**: MATCHES
- **Detail**: Four sites, three files, all at the claimed lines. The grep covers
  `core/src/main/java` only, so the "exactly three" claim is established for production core; other
  modules were not swept.

#### C3 Ref: `MatchPatternBuilder` filter-construction surface
- **Document claim**: `plan/track-9.md` — "`MatchPatternBuilder` builds positive path items through
  `SQLMatchFilter.fromAliasAndClass(toAlias, null)` with neither a `WHERE` nor a class";
  `mergedTargetFilter` "already performs this merge for NOT expressions"; `buildNotExpression`
  "shows the pattern's edges hold the very `SQLMatchPathItem` objects the traverser reads".
- **Search performed**: `grep -rn "mergedTargetFilter\|buildNotExpression\|fromAliasAndClass"
  core/src/main/java`.
- **Code location**: `MatchPatternBuilder.java:147-148` (`fromFilter` / `toFilter`), `:344`
  (`buildNotExpression`), `:365` (the `mergedTargetFilter` call inside it), `:377`
  (`private SQLMatchFilter mergedTargetFilter(...)`), `:386`; `SQLMatchFilter.java:65`.
- **Actual signature/role**: `var toFilter = SQLMatchFilter.fromAliasAndClass(toAlias, null);` at
  `:148`; `item.setFilter(mergedTargetFilter(item.getFilter(), targetAlias,
  supplementalAliasFilters));` at `:365`.
- **Verdict**: MATCHES

#### C4 Ref: `detectNotInAntiJoin` and the `:2064` push-back purpose
- **Document claim**: `plan/track-9.md` — the `:2064` call site "exists to push
  `detectNotInAntiJoin()`'s stripped `NOT IN` conditions back into the item AST".
- **Search performed**: `grep -rn "detectNotInAntiJoin"`; `Read` of
  `MatchExecutionPlanner.java:2055-2070`.
- **Code location**: `MatchExecutionPlanner.java:4581` (declaration), `:4408` (call), `:2060-2064`
  (the comment and the rebind).
- **Actual signature/role**: the in-source comment reads "Re-bind filters after optimization:
  `detectNotInAntiJoin()` may have stripped NOT IN conditions from `aliasFilters` … Without this,
  the MatchStep would still evaluate the original un-stripped filter."
- **Verdict**: MATCHES
- **Detail**: The constraint item 2 must satisfy is taken verbatim from the guard comment it cites.

#### C5 Ref: `matchExpressions` empty on the additive translator path
- **Document claim**: `plan/track-9.md` — "`rebindFilters`, walks `matchExpressions`, which is empty
  on the additive translator path because the pattern arrives pre-built."
- **Search performed**: `grep -n "matchExpressions"` over `MatchExecutionPlanner.java`,
  `MatchPlanInputs.java`, and the translator package; `Read` of
  `MatchExecutionPlanner.java:5609-5628`.
- **Code location**: `MatchPlanInputs.java:71` (`matchExpressions = matchExpressions == null ?
  List.of() : matchExpressions`), `MatchExecutionPlanner.java:453` (`this.matchExpressions =
  List.of()` on the pattern+alias-maps ctor), `:550` (copy from `inputs`), `:5609-5621`
  (`buildPatterns` early return on the additive path), `GremlinStepWalker.java:480`.
- **Actual signature/role**: no translator site sets `matchExpressions` on the builder, and the
  record normalises `null` to `List.of()`.
- **Verdict**: PARTIAL
- **Detail**: The conclusion holds — nothing populates the path items on the translator path. One
  nuance the track compresses: `buildPatterns` returns early when `pattern != null`, so the `:5677`
  `rebindFilters` is not reached at all on the additive path rather than looping over an empty list;
  only the `:2064` site runs, and it no-ops. Immaterial to item 2's fix choice, recorded so a
  decomposer tracing the flow is not surprised.

#### C6 Ref: `SQLMatchStatement.rebindFilters` (name collision)
- **Document claim**: none — checked as a potential decomposition trap under the GAPS
  orphan-construct bullet.
- **Search performed**: `grep -rn "rebindFilters" core/src/main/java`; `Read` of
  `SQLMatchStatement.java:210-245`.
- **Code location**: `SQLMatchStatement.java:232` (declaration), `:226` (call from
  `buildPatterns()`).
- **Actual signature/role**: `private void rebindFilters(Map<String, SQLWhereClause> aliasFilters)`
  — a second, near-identical private method on the SQL statement, walking the statement's own
  `matchExpressions` before the planner is constructed.
- **Verdict**: PARTIAL
- **Detail**: Not a finding. Both plan-side references are class-qualified
  (`MatchExecutionPlanner.rebindFilters` in `plan/track-9.md` `**Signatures:**` and in the plan's
  Track 9 Scope line), so the ambiguity is already closed. Recorded because an unqualified
  instruction at decomposition would land on the wrong file. PSI find-usages would settle whether
  the SQL-side copy is reachable on any plan path item 2 touches; grep cannot.

#### C7 Ref: `MatchStatementExecutionTest` size
- **Document claim**: `plan/track-9.md` § Clarifications — "the SQL `MATCH` regression net and costs
  about 32 minutes for 159 test methods".
- **Search performed**: `grep -c "@Test"` on the file.
- **Code location**: `core/src/test/java/.../sql/executor/MatchStatementExecutionTest.java`.
- **Actual signature/role**: 159 `@Test` annotations.
- **Verdict**: MATCHES
- **Detail**: `plan/track-10.md` says 156 for the same class; Track 9's figure is the current one.
  The runtime figure was not re-measured.

#### C8 Ref: `core/pom.xml` surefire execution order
- **Document claim**: `plan/track-9.md` acceptance bullet R6 — "`core/pom.xml` binds five surefire
  executions to `test` in order — `default-test`, `sequential-tests`,
  `gremlin-process-compliance-tests`, `gremlin-structure-compliance-tests`,
  `gremlin-feature-compliance-tests` — so a bare `./mvnw -pl core test` **stops at the third**".
- **Search performed**: `grep -n` for the five execution ids and the profile boundaries in
  `core/pom.xml`.
- **Code location**: `core/pom.xml:237` (`<id>gremlin-compliance-suites</id>` profile opening),
  `:250` / `:263` / `:276` (the three compliance executions), `:292` (profile close), `:394`
  (`default-test`), `:419` (`sequential-tests`).
- **Actual signature/role**: all five executions exist with the claimed ids; the three compliance
  ones live inside the `gremlin-compliance-suites` profile and the two base ones in the main build
  section.
- **Verdict**: PARTIAL
- **Detail**: The five ids and the "stops at the third" consequence are right. The "in order"
  wording describes Maven's runtime order (profile executions appended after base executions), not
  document order, where the compliance block appears first. The track backs the runtime claim with a
  recorded abort at `/tmp/core-final2-track10.log:4624`, not re-verified here. No finding — the
  operative instruction (`-Dmaven.test.failure.ignore=true` on full-suite gates) is correct either
  way.

#### C9 Ref: the two Cucumber runners
- **Document claim**: `plan/track-9.md` acceptance bullet 9 and `plan/track-11.md` item 6 — the
  suite runs from `YTDBGraphFeatureTest` (core, `gremlin-feature-compliance-tests`) and
  `EmbeddedGraphFeatureTest` (embedded).
- **Search performed**: `find . -name "<class>.java" -not -path "*/target/*"`.
- **Code location**: `core/src/test/java/.../gremlin/gremlintest/YTDBGraphFeatureTest.java`;
  `embedded/src/test/java/com/jetbrains/youtrackdb/shade/EmbeddedGraphFeatureTest.java`.
- **Verdict**: MATCHES
- **Detail**: Both classes exist. Whether the `embedded` runner exhibits the same non-completion was
  not measured; that gap is CR1, not this certificate.

#### C10 Ref: the two equivalence suites
- **Document claim**: `plan/track-9.md` — "`EdgeTraversalEquivalenceTest` and
  `PredicateTraversalEquivalenceTest` are both green while those four return three rows".
- **Search performed**: `find` by class name.
- **Code location**: `core/src/test/java/.../gremlin/translator/strategy/`
  `EdgeTraversalEquivalenceTest.java` and `PredicateTraversalEquivalenceTest.java`.
- **Verdict**: MATCHES
- **Detail**: Existence and package confirmed; the green-vs-defect claim is a measurement the track
  records, not re-run here.

#### C11 Ref: the translator kill switch
- **Document claim**: both track files — "`GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_
  ENABLED` (read by `GremlinToMatchStrategy:338`)".
- **Search performed**: `Read` of `GremlinToMatchStrategy.java:334-342`.
- **Code location**: `GremlinToMatchStrategy.java:338`.
- **Actual signature/role**: `configuration.getValueAsBoolean(GlobalConfiguration
  .QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED)`, gating the session return.
- **Verdict**: MATCHES

#### C12 Ref: `ResultShaping.withListShapingOps`
- **Document claim**: `plan/track-11.md` item 1 — "`ResultShaping.withListShapingOps(@Nonnull
  List<ListShapingOp>)` exists at `ResultShaping.java:106`, replaces the list wholesale, and has no
  production caller yet."
- **Search performed**: `grep -rn "withListShapingOps\|listShapingOps" core/src/main/java`.
- **Code location**: `ResultShaping.java:106`.
- **Actual signature/role**: `public ResultShaping withListShapingOps(@Nonnull List<ListShapingOp>
  ops)`; the record's canonical ctor defensive-copies at `:57`.
- **Verdict**: MATCHES
- **Detail**: The line number and the wholesale-replace semantics are exact. The "no production
  caller" negative rests on the single grep hit across `core/src/main/java`; the reference-accuracy
  caveat applies, and PSI find-usages would be the load-bearing check.

#### C13 Ref: `RecognitionContext.appendPostConcatOp` (the throwing precedent)
- **Document claim**: `plan/track-11.md` DR-T2 and `**Signatures:**` — "copying
  `appendPostConcatOp` throws `UnsupportedOperationException` out of `TraversalStrategy.apply()`";
  cited at `:286`.
- **Search performed**: `grep -rn "appendPostConcatOp"`; `Read` of `RecognitionContext.java:250-300`.
- **Code location**: `RecognitionContext.java:286` (`default void appendPostConcatOp(...)` →
  `throw new UnsupportedOperationException("post-concat ops are top-level only")`);
  `WalkerContext.java:676` (the override); three recogniser call sites.
- **Verdict**: MATCHES

#### C14 Ref: `SubTraversalPredicateAdapter` line anchors
- **Document claim**: `plan/track-11.md` `**Signatures:**` — "`:89` shared-registry comment, `:397`
  `setResultShaping` swallow"; DR-T2 — the swallow is the wrong template to copy.
- **Search performed**: `Read` of `SubTraversalPredicateAdapter.java:85-92` and `:393-402`.
- **Code location**: `:89-91` (the `recognisers` field javadoc, "the same registry the top-level
  walk uses"); `:397` (`public void setResultShaping(@Nonnull ResultShaping shaping)` with a
  "Swallowed" comment body).
- **Verdict**: MATCHES

#### C15 Ref: `GremlinStepWalker` post-union surface
- **Document claim**: `plan/track-11.md` item 4 — "`GremlinStepWalker.POST_UNION_RECOGNISERS` (today
  `count` / `range` / `dedup`, Track 8 DR-U4); both readers are the walker's own — `dispatchAll`'s
  fail-closed gate and `postUnionSuffixTranslatable`'s look-ahead"; `subWalk` at `:399-411`.
- **Search performed**: `grep -n` over `GremlinStepWalker.java`; `Read` of `:185-200`.
- **Code location**: `:193` (`private static final Set<StepRecogniser> POST_UNION_RECOGNISERS =
  Set.of(CountGlobalStepRecogniser.INSTANCE, RangeGlobalStepRecogniser.INSTANCE,
  DedupGlobalStepRecogniser.INSTANCE)`), `:310` (`dispatchAll`), `:322` (the gate), `:370`
  (`postUnionSuffixTranslatable`), `:380` (the look-ahead), `:399` (`subWalk`).
- **Verdict**: MATCHES
- **Detail**: The field's own javadoc at `:188-189` states the two-reader property the track relies
  on, so the "one field covers both paths" claim is source-backed.

#### C16 Ref: `walkFork` / `walkChild` ownership
- **Document claim**: `implementation-plan.md:711` "the two child gates (`UnionStepRecogniser`,
  `GremlinStepWalker.walkChild`)"; `plan/track-11.md` In-scope-modified "`GremlinStepWalker` — … and
  the `walkChild` combinator gate"; Signatures "`GremlinStepWalker.POST_UNION_RECOGNISERS`,
  `dispatchAll`, `postUnionSuffixTranslatable`, `subWalk` (`:399-411`), `walkFork`, `walkChild`".
- **Search performed**: `grep -rn "walkFork\|walkChild" core/src/main/java`; `Read` of
  `WalkerContext.java:594-615`.
- **Code location**: `RecognitionContext.java:333` (`SubTraversalPredicateAdapter
  walkChild(Traversal.Admin<?,?> child)`), `WalkerContext.java:598` (impl),
  `SubTraversalPredicateAdapter.java:413` (impl); `UnionForkHost.java:40` (`walkFork`),
  `UnionForkHostImpl.java:74` (impl), `UnionStepRecogniser.java:95` (call).
- **Actual signature/role**: `WalkerContext.walkChild` delegates: `return
  GremlinStepWalker.subWalk(child, this, recognisers);`. `GremlinStepWalker` declares neither
  `walkChild` nor `walkFork`.
- **Verdict**: MISMATCHES
- **Detail**: Feeds CR3. The gate mechanism the track describes is correct; only the owning class is
  wrong, in three places.

#### C17 Ref: `AbstractMatchPlanStep` lifecycle and shaping surface
- **Document claim**: `plan/track-11.md` — "seven lifecycle states … `NEW`, `OPEN`, `DRAINED`,
  `REARMED`, `CLOSED`, plus Track 10's `CLOSED_UNSTARTED` and `REARMED_AFTER_CLOSE`"; "three open
  routes (`NEW`, `REARMED`, `REARMED_AFTER_CLOSE`)"; `applyListShaping` / `openShapedPayloads` /
  `projectOrSkip` (four arms) / the private seven-constant `State` enum; `resetLifecycleForClone()`
  "deliberately does not touch" `shaping`.
- **Search performed**: `grep -n` over `AbstractMatchPlanStep.java`.
- **Code location**: `:188` (`private enum State`), `:202-245` (the seven constants), `:248`,
  `:316-324` (the terminal set and the three-route open branch), `:372` (`openShapedPayloads`),
  `:386` (`applyListShaping`), `:387` (`shaping.listShapingOps()`), `:683` (`protected final void
  resetLifecycleForClone()`), `:694` (`projectOrSkip`), `:697` (`case MAP -> projectMap(row)`),
  `:708` (`projectMap`), `:447` (`accumulatedGroupMapSource`).
- **Verdict**: MATCHES
- **Detail**: Seven constants, private enum, three open routes at `:320`, and the group-map source
  the track's `unfold`-over-`Map` reasoning depends on are all as described.

#### C18 Ref: `ListShapingOp` javadoc defects
- **Document claim**: `plan/track-11.md` — "`ListShapingOp`'s javadoc says … that the base rebuilds
  its shaped iterator 'once per child plan for a multi-plan boundary' — which is false"; and
  "`unfold`'s one-line description … today says only 'expands a list payload into its elements'".
- **Search performed**: full `Read` of `ListShapingOp.java`.
- **Code location**: `core/.../gremlin/translator/step/ListShapingOp.java`.
- **Actual signature/role**: "after a `reset()` and reopen, and once per child plan for a
  multi-plan boundary" appears verbatim; the flat-map bullet reads "`unfold` expands a list payload
  into its elements".
- **Verdict**: MATCHES
- **Detail**: Both stale clauses are exactly where the track says. The surrounding advice the track
  keeps ("allocate the buffer inside the returned iterator, hold no state across calls") is present
  and is correct for the reset-and-reopen case.

#### C19 Ref: `BoundaryOutputType` constants
- **Document claim**: `plan/track-11.md` DR-T1 — "`BoundaryOutputType` keeps its four constants
  (`ELEMENT`, `MAP`, `SINGLE_VALUE`, `SCALAR`)"; In-scope-modified — its "class-javadoc opening
  sentence naming only `YTDBMatchPlanStep`".
- **Search performed**: `Read` of `BoundaryOutputType.java:1-46`.
- **Code location**: `:23` (`public enum BoundaryOutputType`), `:29` / `:35` / `:40` / `:46`.
- **Actual signature/role**: exactly four constants, no `LIST`; the opening sentence reads "The
  shape that {@link YTDBMatchPlanStep} emits as TinkerPop traversers".
- **Verdict**: MATCHES

#### C20 Ref: the four stale javadoc sites
- **Document claim**: `plan/track-11.md` In-scope-modified — `UnionStepRecogniser`'s "the
  list-shaping terminators are not translated yet" class comment; the "seven flags" wording on
  `RecognitionContext.setResultShaping` and on `WalkerContext.shaping` (whose "a terminator replaces
  it through `setResultShaping`" clause becomes wrong); `BoundaryOutputType`'s opening sentence.
  "`AbstractMatchPlanStep.shaping`'s javadoc is already current and needs no edit."
- **Search performed**: `grep -rn "seven flags\|seven row-projection" core/src/main/java`; `Read` of
  `WalkerContext.java:118-130`, `RecognitionContext.java:262-270`,
  `AbstractMatchPlanStep.java:153-161`, `UnionStepRecogniser.java:28-29`.
- **Code location**: `UnionStepRecogniser.java:28-29`; `RecognitionContext.java:263`;
  `WalkerContext.java:121` (the `shaping` **field** javadoc, whose `:125-126` clause reads "a
  terminator replaces it through {@link #setResultShaping}"); `AbstractMatchPlanStep.java:154`.
- **Verdict**: MATCHES
- **Detail**: All four claims hold, including the negative. `AbstractMatchPlanStep.java:154` does say
  "seven", but scoped as "the seven **row-projection** flags … **plus** the ordered list-shaping ops
  applied to the projected payload stream afterward", so it is already current exactly as the track
  states. The `WalkerContext.shaping` anchor is the field at `:128`, not the accessor at `:583`; the
  accessor javadoc carries neither stale clause. The track's shorthand resolves correctly because
  the field and its reader share the name.

#### C21 Ref: `UnionStepRecogniser` shaping comparison and `PostConcatOp` singletons
- **Document claim**: `plan/track-11.md` DR-T3 — "`UnionStepRecogniser` compares
  `!agreedShaping.equals(childResult.shaping())` and `ResultShaping` is a record whose `equals`
  compares `listShapingOps` element-wise"; "record singletons (this codebase's house style, see
  `PostConcatOp.Count.INSTANCE`)".
- **Search performed**: `grep -n` over `UnionStepRecogniser.java` and `PostConcatOp.java`.
- **Code location**: `UnionStepRecogniser.java:105` (`agreedShaping = childResult.shaping()`),
  `:108` (`|| !agreedShaping.equals(childResult.shaping())`), `:124`
  (`ctx.setResultShaping(agreedShaping)`); `PostConcatOp.java:16-17` (`public sealed interface
  PostConcatOp permits PostConcatOp.Count, PostConcatOp.Range, PostConcatOp.Dedup`), `:21`
  (`Count.INSTANCE`), `:38` (`Dedup.INSTANCE`).
- **Verdict**: MATCHES
- **Detail**: Also confirms Track 8's "sealed type permitting `Count` / `Range` / `Dedup` only",
  which the plan's Track 8 Strategy-refresh cites when arguing `PostConcatOp` and `ListShapingOp`
  are separate mechanisms. The `:124` `setResultShaping(agreedShaping)` call is the one item 1 names
  as what keeps the append and the full-replace from colliding.

#### C22 Ref: `MultiPlanMatchStep.startPlanStream` / `MultipleExecutionStream`
- **Document claim**: `plan/track-11.md` — "`MultiPlanMatchStep.startPlanStream()` returns one
  `MultipleExecutionStream` over a lazy per-child producer, the base's `openShapedPayloads()` runs
  once per arming over that single stream, and `MultiPlanMatchStep`'s class javadoc says so
  outright."
- **Search performed**: `grep -rn "startPlanStream\|MultipleExecutionStream"
  MultiPlanMatchStep.java`.
- **Code location**: `MultiPlanMatchStep.java:36-37` (class javadoc), `:308` (`protected
  ExecutionStream startPlanStream()`), `:337` (`ExecutionStream stream = new
  MultipleExecutionStream(producer)`).
- **Verdict**: MATCHES
- **Detail**: Confirms the contradiction the track reports between this javadoc and
  `ListShapingOp`'s (C18) — the two say opposite things about per-child rebuilds, and
  `MultiPlanMatchStep` is the one that matches the code.

#### C23 Ref: TinkerPop fork step semantics
- **Document claim**: `plan/track-11.md` `## Context and Orientation` — `FoldStep` has
  `(Traversal.Admin)` and `(Traversal.Admin, Supplier, BiFunction)` ctors "distinguished by a
  `listFold` boolean behind `isListFold()`"; `UnfoldStep.flatMap` dispatches five ways;
  `ReverseStep.map` is a per-value transform; both `TailGlobalStep` and `TailGlobalStepPlaceholder`
  implement `TailGlobalStepContract.getLimit()` and `CONCRETE_STEPS` is the registration source of
  truth; `getLimitAsGValue()` exists.
- **Search performed**: `javap -cp <fork jar>` on `FoldStep`, `UnfoldStep`, `ReverseStep`,
  `TailGlobalStepContract`, `TailGlobalStepPlaceholder`; `unzip -l` to locate the classes.
- **Code location**: `~/.m2/repository/io/youtrackdb/gremlin-core/3.8.1-67860f6-SNAPSHOT/`
  `gremlin-core-3.8.1-67860f6-SNAPSHOT.jar` (the newest of three resolved fork versions, built
  2026-07-27).
- **Actual signature/role**: `FoldStep extends ReducingBarrierStep` with both ctors and `public
  boolean isListFold()`; `UnfoldStep extends FlatMapStep` with `protected Iterator<E>
  flatMap(Traverser$Admin<S>)`; `ReverseStep extends ScalarMapStep` with `protected E
  map(Traverser$Admin<S>)`; `TailGlobalStepContract` declares `public static final List<Class<?
  extends Step>> CONCRETE_STEPS`, `public abstract Long getLimit()`, `public default GValue<Long>
  getLimitAsGValue()`; `TailGlobalStepPlaceholder` implements `TailGlobalStepContract` and
  `GValueHolder`, overriding both limit readers.
- **Verdict**: MATCHES
- **Detail**: Every fork-jar claim verifies at the declaration level. The five-arm `flatMap`
  dispatch and the `pinVariable` side effect inside `TailGlobalStepPlaceholder.getLimit()` are
  body-level facts `javap` cannot show; the track marks them as read from the fork source and they
  were not independently re-derived.

#### C24 Ref: the `getLimit()` GValue precedent
- **Document claim**: `plan/track-11.md` — "`RangeGlobalStepPlaceholder.getLowRange()` has
  byte-identical pinning and `RangeGlobalStepRecogniser.normalize` reads it before its own decline
  branches".
- **Search performed**: `grep -n` over `RangeGlobalStepRecogniser.java`.
- **Code location**: `RangeGlobalStepRecogniser.java:39` (`var normalized = normalize(range)`),
  `:40` / `:43` (the decline branches after it), `:87` (`private static NormalizedRange
  normalize(RangeGlobalStepContract<?> range)`), `:88` (`var lowObj = range.getLowRange()`).
- **Verdict**: MATCHES
- **Detail**: The read-then-decline ordering is confirmed. The byte-identity of the two
  placeholders' pinning bodies was not compared.

#### C25 Ref: JMH and clone-isolation test anchors
- **Document claim**: `plan/track-11.md` item 7 — "`jmh-ldbc/src/test` is not fixture-less —
  `LdbcQueryCorrectnessTest` builds a small deterministic in-memory social graph"; and
  "`LdbcBenchmarkState` does need the LDBC dataset"; item 5 — "`MultiPlanMatchStepTest` already has
  the clone-isolation idiom to copy".
- **Search performed**: `find` by class name.
- **Code location**:
  `jmh-ldbc/src/test/java/com/jetbrains/youtrackdb/benchmarks/ldbc/LdbcQueryCorrectnessTest.java`;
  `jmh-ldbc/src/main/java/.../LdbcBenchmarkState.java`;
  `core/src/test/java/.../gremlin/translator/step/MultiPlanMatchStepTest.java`.
- **Verdict**: MATCHES
- **Detail**: All three exist at the claimed module and source-set. The fixture's content and the
  test idiom were not read; the T9 finding this rests on (`technical-iter1.md:170`) records both.

**Axis — Plan ↔ Plan (cross-track references, feeding Gaps).**

#### C26 Ref: the "Track 9" cross-reference sweep
- **Document claim**: `plan/track-10.md:11` — "the split moved the terminators to Track 11 anyway,
  so those Track 7 / Track 8 references are now stale and carry bracketed amendments of their own."
- **Search performed**: `grep -rn "Track 9\|Track 11\|track-9\|track-11" _workflow --include=*.md`,
  excluding the two pending track files and the review directories; cross-read against the
  `96c37d3e74` diff.
- **Code location**: `plan/track-7.md` lines 5, 26, 33, 34, 35, 53, 62, 74, 87, 92, 93, 94, 101,
  125, 126; `plan/track-8.md` lines 25, 37, 39, 52, 55, 93, 100, 185, 192, 208, 209.
- **Actual signature/role**: `96c37d3e74` amended `track-7.md:125` and `track-8.md:208` only. The
  other 24 lines still name Track 9 as the terminator owner, including `track-7.md:126` and
  `track-8.md:209` — the lines immediately below the two that were amended, inside the same
  `## Interfaces and Dependencies` section.
- **Verdict**: MISMATCHES
- **Detail**: Feeds CR2. Materially load-bearing among the unamended: `track-8.md:55` (DR-U4, the
  decision record assigning the post-union suffix relaxation), `track-8.md:185` ("Track 9 may relax
  the 'union is last step' rule"), and `track-7.md:126` (the carrier-supply line). The plan file's
  own Checklist, Component Map, D8, and Invariants were all correctly re-pointed to Track 11.

#### C27 Ref: the Phase A finding partition across the two Artifacts sections
- **Document claim**: `plan/track-9.md:99` — terminator-facing = "T1–T7, T10–T16, T18, R2, R7"; this
  track = "R1, R3, R4, R5, R6, and T8"; T17 retired. `plan/track-11.md:97` — terminator-facing =
  "T1–T7, T9–T16, T18, R2, and R7".
- **Search performed**: `grep -nE '^### [A-Z]+[0-9]+ '` over the four files in
  `plan/track-9/reviews/`; `Read` of `technical-iter1.md:156-200`.
- **Code location**: `technical-iter1.md` (T1–T11), `technical-gate-verification-iter2.md`
  (T12–T16), `technical-gate-verification-iter3.md` (T17–T18), `risk-iter1.md` (R1–R7).
- **Actual signature/role**: T9's Location is "Track 9 Plan of Work item 7 and the last
  `## Validation and Acceptance` bullet"; its subject is `LdbcBenchmarkState`, the on/off harness,
  and the throwing installation check — all Track 11's item 7 after the split, where the fix already
  appears verbatim. T8's Location is "Track 9 Plan of Work item 1a and `## Interfaces and
  Dependencies`" — the dropped filter and its GQL blast radius, correctly Track 9's.
- **Verdict**: MISMATCHES
- **Detail**: Feeds CR4. Track 11's set is right; Track 9's drops T9 from both halves. The risk
  findings partition cleanly on both sides (R2 / R7 to Track 11, R1 / R3–R6 to Track 9, 7 total).

#### C28 Ref: the dispositions file's renumbered pointers
- **Document claim**: `plan/track-10/core-compliance-failure-dispositions.md:41-56` — the nine
  dropped-filter-signature failures go to "Track 9 **item 2**" and the other twelve to "Track 9
  **item 4**"; "The terminator work moved to Track 11 and owns none of these."
- **Search performed**: `Read` of the dispositions table; cross-read against `plan/track-9.md`
  `## Plan of Work`.
- **Code location**: `plan/track-9.md:69` (item 2, "Bind per-alias filters for non-root aliases"),
  `:71` (item 4, "Triage the residue and record dispositions").
- **Verdict**: MATCHES
- **Detail**: Both pointers resolve to the items their descriptions require, and Track 9's
  acceptance bullet 7 restates the same split (nine pass, twelve diagnosed and dispositioned). The
  file's `## References` pointer was also re-aimed from "item 1a" to "`plan/track-9.md` § Context
  and Orientation", which is where the mechanism now lives.

#### C29 Ref: `## Implementation state` narrative and table
- **Document claim**: `implementation-plan.md:719-735` — the 11-track narrative, the twelve-row
  table, and the decision-conformance sentence.
- **Search performed**: `Read` of `implementation-plan.md:715-737`; diff against the pre-split text
  in `96c37d3e74`.
- **Code location**: `implementation-plan.md:719` (narrative), `:721-733` (table), `:735`
  (conformance).
- **Actual signature/role**: the narrative names Tracks 9 and 11 "in that order" and describes each
  track's ownership correctly; the table carries rows 1–8, 10, 9, 11 with row 11 added and rows 9
  and 10 re-worded; the conformance sentence adds D-TEXT-OPS → Track 4 and re-states D3.
- **Verdict**: PARTIAL
- **Detail**: One residue feeds CR5 — the narrative's trailing "(D3)" on Track 11's terminators
  contradicts the conformance sentence rewritten fifteen lines later in the same commit. Row 10's
  "21 deferred to Track 9 with dispositions" is correct after the split (C28). The non-ascending
  Checklist order (Track 8 → 10 → 9 → 11) is intended and mechanically sound; not a finding.

#### C30 Ref: the Track 9 → Track 11 baseline supply/consume contract
- **Document claim**: `plan/track-11.md` item 6 — "The suite runs from `YTDBGraphFeatureTest` under
  `core`'s `gremlin-feature-compliance-tests` execution and from `EmbeddedGraphFeatureTest` in the
  `embedded` module; Track 9's baseline records both and this re-run reads both."
- **Search performed**: `Read` of `plan/track-9.md` `## Plan of Work` (items 1–4) and
  `## Validation and Acceptance` (all ten bullets); `plan/track-11.md` item 6 and acceptance
  bullet 8.
- **Code location**: `plan/track-9.md:68` (item 1), `:70` (item 3), `:80-81` (acceptance bullets
  1–2), `:88` (acceptance bullet 9, the two-runner commitment).
- **Actual signature/role**: item 1 records the `core` per-directory table only; item 3 re-measures
  "immediately after item 2" with no runner named beyond item 1's; acceptance bullet 1 pins the
  `core` command; acceptance bullet 9 asserts both runners are covered. `embedded` appears in the
  Plan of Work zero times and in the measured A/B in `## Context and Orientation` zero times.
- **Verdict**: MISMATCHES
- **Detail**: Feeds CR1. The consumer (Track 11 item 6, and its acceptance bullet 8 "The full
  TinkerPop Cucumber suite completes and shows **no regression** against Track 9's post-fix
  baseline") is unambiguous about reading both halves; the producer never makes the second half.

**Axis — Design ↔ Plan and Design ↔ Code.**

#### C31 Ref: `design.md` terminator rows and post-process mechanism
- **Document claim**: the frozen `design.md` assigns `fold()` / `unfold()` / `reverse()` / `tail(n)`
  to **Track 6** (`:158-161`, `:536`), specifies `fold()` → `BoundaryOutputType.LIST` (`:158`,
  `:590`), and describes the post-process as order-less flags `unfoldOutput` / `reverseOutput` /
  `tailLimit` (`:543`, `:614`).
- **Search performed**: `grep -n "Track [0-9]"` and `grep -n "fold()\|unfold\|reverse()\|tail(\|
  list-shaping\|BoundaryOutputType"` over `design.md`; cross-read against `plan/track-11.md` DR-T1
  and `plan/track-7.md`'s ordered-carrier decision.
- **Code location**: `design.md:158-161`, `:524-543`, `:578-622`; against `BoundaryOutputType.java`
  (four constants, C19) and `ListShapingOp.java` (the ordered carrier, C18).
- **Verdict**: PARTIAL
- **Detail**: **Not a finding — expected frozen-design lag, recorded for Phase 4.** Three
  divergences, each covered by a revised Decision Record the replan intended. (1) Track attribution:
  the terminators moved 6 → 7 → 9 → 11 across three replans and the frozen design still reads
  Track 6. (2) `BoundaryOutputType.LIST`: dropped by `plan/track-11.md` DR-T1 as the wrong mechanism
  — `projectOrSkip` is a per-row projector with no expressible N→1 arm, and re-pinning `outputType`
  would erase the per-element projection the drain needs — confirmed against the live four-constant
  enum. (3) The order-less flags: superseded by Track 7's ordered `ListShapingOp` list, which the
  plan's Track 6 Strategy-refresh parenthetical already records. Per the consistency-review rule on
  revised DRs, a `**Revised decision**` diverging from the frozen design is the expected state, not
  a finding. These join the standing Phase-4 deferrals: the 2026-07-15 class-diagram lag; the
  2026-07-27 `MultiPlanMatchStep : "Track 6"` / `extends YTDBMatchPlanStep` provenance, re-confirmed
  at `design.md:91`; and the 2026-08-01 S6 `reset()` contract and metrics-capture gaps.
  `design-final.md` reconciles all of them. No divergence was found between the frozen design and a
  Decision Record the replan did **not** revise.
</content>
