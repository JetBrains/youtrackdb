<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 2, suggestion: 1}
index:
  - {id: R23, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/implementation-plan.md:688", anchor: "### R23 ", cert: "#### Verify R10", basis: "the plan file's Track 9 entry states the escalation trigger in the pre-R10 / pre-R14 form (evidence required, six attempts) while track-9.md:88 states the fixed form (evidence satisfiable-or-waived, twenty runs), and both edits are uncommitted in the same working tree"}
  - {id: R24, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:143", anchor: "### R24 ", cert: "#### Verify R17", basis: "the new path filter says 'production or test sources', which excludes the .feature resources item 4 names as measurement-movers and both POMs whose surefire config the R9 bullet makes load-bearing, so a stale baseline passes the check"}
  - {id: R25, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:25", anchor: "### R25 ", cert: "#### Verify R15", basis: "DR-S1 labels 5,331 'the full non-generated figure' when step 9 measures 5,168, and says the ~8-14 files Scope line is stated on the code-only figure when that line counts the baseline artifact"}
verdicts:
  - {id: R8,  verdict: VERIFIED}
  - {id: R12, verdict: VERIFIED}
  - {id: R13, verdict: VERIFIED}
  - {id: R15, verdict: VERIFIED}
  - {id: R16, verdict: VERIFIED}
  - {id: R17, verdict: STILL OPEN}
  - {id: R18, verdict: VERIFIED}
  - {id: R19, verdict: VERIFIED}
  - {id: R20, verdict: STILL OPEN}
  - {id: R21, verdict: VERIFIED}
  - {id: R22, verdict: VERIFIED}
overall: FAIL
flags: [CONTRACT_OK]
-->

# Track 9 — risk gate verification, iteration 4

Nine of the eleven fixes land clean; two land at one location and leave the finding's other named locations untouched. The four iteration-3 regressions are gone: the unsatisfiable equality rule, the doubled rollback instruction, the vacuous `not()` acceptance shape, and the sizing claim its own numbers refuted. R16's replacement traversal survives independent re-derivation — I counted the modern graph by hand in both readings and traced the shape end-to-end through `NotStepRecogniser` to the branch that drops the class.

R17 and R20 are STILL OPEN for the same reason: each fix was applied at the anchor line and not at the other lines the finding named. `:29` and `:153` still state the re-trigger rule in the unqualified "any commit" form the criterion at `:143` now qualifies, and `:94`'s "the must-amend list above" still points up at a list that sits below it.

Two new should-fix findings. The larger one is outside the track file: `implementation-plan.md`'s Track 9 entry, edited in this same uncommitted working tree, states the escalation trigger in the exact pre-R10 / pre-R14 form — evidence required rather than waivable, six attempts rather than twenty. The track file's fixes never reached it. The smaller one is inside R17's own replacement text: its path filter reads "production or test sources", which excludes the `.feature` files item 4 names as measurement-movers.

**Tooling caveat.** PSI was not attempted, per the spawn. Every symbol result below is `grep -rn` over `core/src` plus an end-to-end read of the declaring method; the git figures and file counts are direct measurements re-run in this session. The negatives that carry weight — `rebindFilters` iterates `matchExpressions` and never `notMatchExpressions`, `buildNotExpression` does not call `build()` — were established by reading the whole declaring method. A reflective or cross-module caller would not appear.

## Verification certificates

#### Verify R8: the published baseline against the track's own later commits
- **Original issue**: the handoff baseline was pinned to the end of item 4, which is the end of the Plan of Work and not the end of the track. Iteration 3 marked it REGRESSION because the replacement rule ("must equal HEAD") could not hold.
- **Fix applied**: the criterion at `:143` now states the ancestor form and says in the same sentence why equality is unsatisfiable. `:29` still carries the trigger-scope bullet, `:153` the recovery.
- **Re-check**:
  - Location: `track-9.md:29`, `:143`, `:153`.
  - Current state: R8's own substance — the trigger list running past the Plan of Work through Phase C and completion — is stated at `:29` and restated at `:143` ("Any qualifying commit landing after the run — Phase C review fixes included — re-triggers it"). The Track 10 worked example is intact and its commit sequence still reads correctly.
  - Criteria met: the trigger's scope is fixed and its operationalisation is now satisfiable at the criterion.
- **Regression check**: the residual disagreement between the three statements is R17's, not R8's, and is carried there. Nothing in this round narrowed R8's trigger scope. Clean.
- **Verdict**: VERIFIED

#### Verify R12: item 2's default site and the rollback ordering
- **Original issue**: three fix sites with different costs presented as equals and no decision rule, plus a rollback note deferred to the step that item 4's triage then stacks on. Iteration 3 marked it REGRESSION because the replacement instruction at `:115` and the superseded one at `:123` both stood.
- **Fix applied**: R18 deleted the superseded sentence.
- **Re-check**:
  - Location: `track-9.md:113`, `:115`, `:154`.
  - Current state: `grep -n rollback track-9.md` returns exactly one line, `:115`. The default-site paragraph at `:113` is unchanged and still names option 2, compares all three costs, and requires a `## Decision Log` entry to depart. `## Idempotence and Recovery` at `:154` echoes the revert order and the completion signal.
  - Criteria met: one rollback instruction, stated once, with the ordering and the signal attached.
- **Regression check**: item 2 now ends at `:121` with the `NOT IN` narrowing paragraph, which is a scoping statement rather than an instruction, so the bottom-up reader no longer finishes on a contradicted directive. The deletion shifted the acceptance criteria up by one line; the two ordinal references that survive in the file (`:92` and `:144`, both "criteria 1 and 2") still resolve to `:133` and `:134`. Clean.
- **Verdict**: VERIFIED

#### Verify R13: the NOT path and the fix-it decision
- **Original issue**: `mergedTargetFilter`'s dead class branch is a live class-drop on every translated `not()` under polymorphic mode. Iteration 3 marked it REGRESSION because the acceptance shape could not fail before the fix and the second fix site was missing from the scope bookkeeping.
- **Fix applied**: R16 replaced the traversal; R21 added the second site to `## Interfaces and Dependencies`.
- **Re-check**:
  - Location: `track-9.md:117`, `:137`, `:163`; `MatchPatternBuilder.java:344-402`; `NotStepRecogniser.java:89-98`.
  - Current state: the code claims at `:117` re-verify. `mergedTargetFilter` reads `aliasClasses.get(alias)` and consumes it only in the `else` branch; `buildNotExpression` passes `item.getFilter()` from a factory-built item, so the `existingItemFilter.copy()` branch wins and the class is never bound. Both halves of R13's remedy are now present and each is verified separately below.
  - Criteria met: the defect is named where it lives, the acceptance shape discriminates, and the second edit site is declared.
- **Regression check**: covered under R16 and R21. Clean.
- **Verdict**: VERIFIED

#### Verify R15: the Scope line against the threshold DR-S1 invokes
- **Original issue**: DR-S1's threshold claim was over all non-generated changed lines while the Scope line counted code plus one artifact. Iteration 3 marked it REGRESSION because the replacement quoted Track 10 at 2,718 under a sentence claiming "a third consecutive track past ~4,000", and asserted the ESCALATE instruction reads the code-only figure.
- **Fix applied**: R19 rewrote the bullet around the two-figure distinction.
- **Re-check**:
  - Location: `track-9.md:25`; `.claude/workflow/track-code-review.md:312-353`.
  - Current state: "a third consecutive track past the ~4,000-line review-burden threshold" is gone, replaced by "a run of over-threshold tracks" plus three specific claims. The claim that the gate reads the code-only figure is gone and replaced by its negation, which is correct: I re-read step 9 and its `--shortstat` excludes only `**/generated-sources/**`, `**/generated-test-sources/**` and `**/internal/core/sql/parser/**`, with an explicit sentence keeping test code "because reviewing test behavior is real review work". `docs/adr/**` is counted.
  - Criteria met: both halves of R19 are addressed.
- **Regression check**: I re-derived every figure rather than trusting the restatement. Track 8, `bc8641e51a^..88eb31413a`: 58 files / 9,280 insertions total, 20 / 3,466 under `docs/adr`, 38 / 5,814 for `*.java` and `*.xml` — the quoted pair is exact. Track 10, `f007749249..7c77a4544f`: 39 / 5,331 total, 24 / 2,718 code, 15 / 2,613 under `docs/adr`, and every `docs/adr` path is under `_workflow/`, so "2,613 of `_workflow/` prose" is exact too. `plan/track-10/reviews/` is 3,154 lines, also exact. Two quoted figures do not match the definitions the bullet gives them — R25, suggestion. Not enough to hold the verdict, since the conclusion each supports survives on the corrected number.
- **Verdict**: VERIFIED

#### Verify R16: the `not()` acceptance shape
- **Original issue**: `g.V().not(__.out().hasLabel(software))` returns the same multiset with and without the class on every fixture the track named, so its "fails before the fix" clause was false and the watch-it-fail gate would pass vacuously.
- **Fix applied**: the criterion at `:137` now uses the six-vertex modern graph and `g.V().not(__.out().hasLabel(person))`, quotes five native against three class-dropped, states the discriminating fixture property, explains why the four-vertex fixture cannot witness it, and records that `NotStepRecogniserTest` gains a class-reading assertion.
- **Re-check**:
  - Location: `track-9.md:137`; `NotStepRecogniser.java:39-99`; `MatchPatternBuilder.java:377-402`; `HasStepRecogniser.java:160-166`; `GlobalConfiguration.java:962-967`; `NotStepRecogniserTest.java`.
  - Current state: I counted both readings by hand off the standard modern graph (marko, vadas, josh, peter as `person`; lop, ripple as `software`; marko→vadas, marko→josh, marko→lop, josh→ripple, josh→lop, peter→lop). Native `not(out().hasLabel(person))` excludes only marko, whose out-set contains vadas and josh, and returns **five** — vadas, lop, josh, ripple, peter. The class-dropped reading is `not(out())`, which excludes every vertex with any out-edge (marko, josh, peter) and returns **three** — vadas, lop, ripple. Both figures are right, and the discriminating vertices are josh and peter, which is exactly the property the criterion states.
  - Criteria met: the shape fails before the fix, on a fixture whose defining property is written down rather than left implicit.
- **Regression check**: three areas, all clean.
  - *Reachability.* I traced the shape through the recogniser rather than assuming it. `hasNotPresenceKey` returns null (the child has two steps), the single child is walked, `hasEdges()` is true, and the origin is in the positive pattern. `edgeBearingNotCapturesUnsupportedOriginConstraints` tests only the **origin** alias against `capturedAliasFilters` and `registeredAliasClasses`; `hasLabel(person)` lands on the post-hop alias, so neither test fires and the shape reaches `buildNotExpression`. Label-free `out()` is a supported hop — `VertexStepRecogniser` routes `returnsEdge() == false` to `VertexHopRecogniser`, and `EdgeTraversalEquivalenceTest.labelLessHop_returnsSameMultisetAsNative` pins the top-level form. So the criterion is pinned on a shape the translator accepts.
  - *The drop is live at that shape.* In the sub-walk `HasStepRecogniser:162` calls `ctx.addNode(boundary, "person")`, which `SubTraversalPredicateAdapter:242` forwards to `capturedPattern.addNode(alias, className, null, false)`, so the sub-builder's `aliasClasses` carries `person` for the hop target. `buildNotExpression` then copies the edge item and calls `mergedTargetFilter`, whose copy branch discards `className`. Under polymorphic mode `HasStepRecogniser` adds no `@class` term, so `capturedAliasFilters` is empty and the supplemental merge rescues nothing. The class is gone.
  - *Polymorphic is the default, so the criterion is readable as written.* `YTDBStrategyUtil.isPolymorphic` falls back to `GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT`, whose declared default is `true`. Under `polymorphic=false` the same `hasLabel` emits `@class = 'person'` into `capturedAliasFilters`, `mergedTargetFilter` merges it as a supplemental clause, and the defect does not appear — which is why the criterion's "under polymorphic mode" qualifier is load-bearing rather than decorative.
  - *The ten-test claim.* `grep -c '@Test'` on `NotStepRecogniserTest` returns 10. Reading every assertion: outcome equality, rendered boundary-filter text, `notMatchExpressions` size, origin alias, item count, leaf filter alias, leaf `WHERE` text containing `city`, `getNumOfEdges()` zero, `assertThatCode(...).doesNotThrowAnyException()`, and a boundary-step count of one. None reads a class off a NOT item. The criterion's characterisation is accurate.
- **Verdict**: VERIFIED

#### Verify R17: the baseline SHA rule
- **Original issue**: "the recorded SHA must equal HEAD at track completion" cannot be satisfied, and the file stated the rule three ways that did not agree. R17's proposed fix named two locations: make `:144` (now `:143`) and `:29` restate one rule.
- **Fix applied**: `:143` was rewritten. `:29` and the recovery bullet were not.
- **Re-check**:
  - Location: `track-9.md:29`, `:143`, `:153`.
  - Current state: `:143` is correct and self-explaining — ancestor of HEAD, path-filtered, with the reason equality fails stated inline. The other two still carry the variants R17 flagged. `:29` reads "**Any** commit that lands after a baseline run and before track completion re-triggers that run", unqualified, which is the non-terminating form: the commit recording the re-run re-triggers the re-run. `:153` reads "any other commit landing between the run and track completion" and describes the check as "a one-line `git merge-base --is-ancestor`", which is the ancestry half without the path filter the criterion now requires. `:29`'s Track 11 reciprocal names "no intervening `core` commit" and omits `embedded`, which `:143` and criterion 12 both put in scope.
  - Criteria met: the unsatisfiability is fixed; the three-way disagreement R17 was written about is reduced but not resolved.
- **Regression check**: the replacement text introduces a narrower path filter than the invalidator set the track's own rules establish — R24, should-fix.
- **Verdict**: STILL OPEN — bring `:29` and `:153` onto the `:143` wording (qualified by the same path filter, with `embedded` named in the Track 11 reciprocal), so the file states one rule.

#### Verify R18: the duplicated rollback instruction
- **Original issue**: item 2 carried two rollback instructions with opposite content, `:115` (write it early) and `:123` (write it with the code).
- **Fix applied**: `:123` was deleted.
- **Re-check**:
  - Location: `track-9.md:115`.
  - Current state: `grep -n "rollback" track-9.md` returns one line. "Record the rollback story in the same step" no longer appears anywhere in the file.
  - Criteria met: one instruction, and it is the one with the ordering and the completion signal attached.
- **Regression check**: `:115`'s content subsumes what `:123` said — which files revert, and what signal says the revert is complete — so nothing was lost with the sentence. `## Idempotence and Recovery:154` carries the same rule and does not contradict it. Clean.
- **Verdict**: VERIFIED

#### Verify R19: DR-S1's threshold restatement
- **Original issue**: restating the threshold claim on the code-only figure falsified the sentence it supported and asserted a definition for a check the track does not own.
- **Fix applied**: the bullet now separates the two figures, states which one answers DR-S1's question, and states explicitly that the gate does not read it.
- **Re-check**:
  - Location: `track-9.md:25`; `.claude/workflow/track-code-review.md:325-353`.
  - Current state: verified above under R15. The exclusion list in the fix text matches step 9's actual globs, and step 9's own prose confirms test code is kept deliberately.
  - Criteria met: the false premise is gone and the operational claim now points at what the instruction really measures.
- **Regression check**: the sentence "this track's Phase A output already stands past 1,700 with no step file, episode, or baseline artifact yet written" remains true — `plan/track-9/reviews/` is now 2,409 lines across nine files, so the figure is a lower bound that will only grow. Two quoted numbers are mislabelled; see R25. Clean otherwise.
- **Verdict**: VERIFIED

#### Verify R20: the escalation branch's cross-references
- **Original issue**: two cross-references in the escalation branch had drifted — "acceptance criterion 13 below" named the wrong criterion after R13's insertion shifted the ordinals, and `:94` said "the must-amend list above" when the list is below it.
- **Fix applied**: the first was fixed. The second was not.
- **Re-check**:
  - Location: `track-9.md:94`, `:96`, `:144`.
  - Current state: `:96` now names the criterion by its text — "the acceptance criterion "**Both Cucumber runners are in scope, not just `core`'s**" below (named rather than numbered — R13's inserted criterion already shifted the ordinals once, R20)" — and the quoted string matches `:144` exactly. That is ordinal-proof and it is the right fix. `:94` still ends "This clause joins the must-amend list above", and the list is at `:96`, two lines below. The direction word R20 named is unchanged.
  - Criteria met: the ordinal drift is fixed; the direction word is not.
- **Regression check**: the fix added an `(S1)` tag to `:144`, which nothing references. The must-amend list quotes the criterion by text and never by that tag, no other criterion carries an S-tag, and the file already uses `DR-S1` at `:11` and `:25` for a Decision Record, so a reader grepping `S1` gets two unrelated hits. Harmless today, but the tag is a scheme with one member and a name collision. I re-derived the ordinals for completeness: `## Validation and Acceptance` runs `:133`–`:145`, thirteen criteria, the two-runner one is twelfth and the pinned-commands one thirteenth, matching iteration 3.
- **Verdict**: STILL OPEN — change "above" to "below" at `:94`, and either drop the unused `(S1)` tag or give the criteria a numbering scheme the cross-references actually use.

#### Verify R21: the NOT-path fix in the scope bookkeeping
- **Original issue**: R13's fix-it decision adds a second fix site in a second file and `## Interfaces and Dependencies` recorded only the three item-2 sites.
- **Fix applied**: `:163` now names `MatchPatternBuilder.mergedTargetFilter`'s class branch as "a **second edit at a second site** (R21) … which none of the three fix sites reaches on its own, with `NotStepRecogniserTest` gaining a class-reading assertion".
- **Re-check**:
  - Location: `track-9.md:163`; `MatchPatternBuilder.java:344-402`; `MatchExecutionPlanner.java:6012-6022`; `MatchPlanInputs.java:47-48,71-72`.
  - Current state: I tested the disjointness claim against each of the three sites rather than accepting it. Site 1 is a change inside `build()`; `buildNotExpression`'s Javadoc and body confirm it never calls `build()`, so a `build()`-side merge cannot reach the NOT items. Site 2 walks `ir.pattern()`'s edge items; the NOT expressions are separate `SQLMatchExpression` objects carried in `MatchPlanInputs.notMatchExpressions`, a field distinct from `matchExpressions` and from the pattern, so a post-`build()` pass over the pattern does not see them. Site 3 is `rebindFilters`, whose whole body iterates `matchExpressions` only (`:6013`), and the planner keeps `notMatchExpressions` in a separate list it hands to `manageNotPatterns`; a second loop at the same call site over `pattern`'s edge items has the same blind spot as site 2. All three miss it. The scope note is right, and right in the direction it claims.
  - Criteria met: the second site is declared where a Phase C reviewer checks whether a changed file was planned.
- **Regression check**: `## Out of scope` at `:165` does not contradict the addition — it excludes `rebindFilters`' `:5677` call site and `SQLMatchStatement`'s same-named private method, neither of which is the NOT path. The `Signatures` line at `:167` still lists `mergedTargetFilter` in a flat list without the NOT-path-only note R21 suggested, but `:117` and `:163` both say it in prose, so the risk of reading it as a template reference is covered. Clean.
- **Verdict**: VERIFIED

#### Verify R22: the fork timeout and the heap-dump flag
- **Original issue**: the fork timeout added under R10 kills the fork the previous sentence says to attach `jcmd` to, with no value given to resolve the conflict, and `-XX:+HeapDumpOnOutOfMemoryError` cannot fire on the no-OOM shape the same round diagnosed.
- **Fix applied**: `:86` now frames both as conditional — "Two instrumentation options exist and neither is unconditional, so pick per run rather than adding both by default" — scopes the timeout to unattended bisect runs and away from the diagnostic run, and restates the heap dump's value as bounding the other hypothesis.
- **Re-check**:
  - Location: `track-9.md:86`, `:90`.
  - Current state: the contradiction is gone, resolved by scoping rather than by tuning. R22 offered a value as one route; the per-run split is the other, and it is the stronger one — a single timeout value cannot be both a backstop for an unattended run and safe for an attended capture. "A stall self-documents on death" no longer appears; the text now says the flag "cannot fire on the shape this track actually diagnoses" and cites the same zero-OOM evidence `:90` rests on.
  - Criteria met: neither mitigation is presented as removing the operator dependency, and the recorded-attempt waiver at `:90` remains the operative exit for the diagnosed shape.
- **Regression check**: this is the text R10 verified, so I re-read `:88` and `:90` end to end. The trigger's evidence conjunct is untouched — "either a thread dump and heap histogram from a stalled fork, or a recorded account of what was attempted and why it returned nothing". The budget conjunct is untouched — "two working days or twenty bisect runs, whichever comes first". The three failure modes and the host-memory context at `:90` are unchanged. R10 and R14 stand in the track file. They do **not** stand in the plan file — R23. No value is given for the timeout, which is now an implementation choice rather than an unresolved conflict, so it does not hold the verdict.
- **Verdict**: VERIFIED

## Findings

### R23 [should-fix]
**Location**: `implementation-plan.md:688-690` (the Track 9 entry's ESCALATE clause); `track-9.md:88` and `:30`

The plan file states this track's escalation trigger in the exact form R10 and R14 were accepted to replace, and both files are uncommitted in the same working tree.

`implementation-plan.md:688` reads "escalation **requires** a committed thread dump and heap histogram from a stalled fork, and fires once the bisect has run two working days or **six attempts** without pinning a fixable defect." `track-9.md:88` reads "within **two working days or twenty bisect runs**, whichever comes first … **and** the committed artifact carries **either** a thread dump and heap histogram from a stalled fork, **or** a recorded account of what was attempted and why it returned nothing." Two documents, two different triggers, on both axes at once.

Both rewrites were substantive rather than editorial. R10 established that requiring the diagnostic outright lets the trigger be blocked by its own precondition — `jcmd` needs a pid and Track 10's forks died rather than hanging, `Thread.print` needs a safepoint, `GC.class_histogram` forces a full GC on a 4 GB fork in a collection spiral. R14 established that a six-attempt budget selects escalation almost regardless of how the search goes, because isolating a non-adjacent interacting pair among seven directories runs on the order of twenty attempts. The plan file preserves both defects verbatim.

The plan is not a stale copy anyone can ignore. `git diff --stat` shows this clause is one of the two hunks changed in the working tree, so it was written this session, after the split. It is also the strategic-context input every Phase A reviewer and the orchestrator read for this track, and Track 11's own panel reads the same file for the dependency restatement two lines below it. An implementer who escalates on attempt six with a missing thread dump can point at the plan and be correct.

**Proposed fix**: restate `implementation-plan.md:688-690` on `track-9.md:88`'s wording — twenty bisect runs, and evidence satisfiable-or-waived with the attempt recorded — or replace the clause with a pointer to the track file so one document owns the trigger. If the pointer form is taken, keep the "escalation branch does not stall the track" sentence, which is the part the plan entry genuinely needs.

### R24 [should-fix]
**Location**: `track-9.md:143` (the baseline criterion's path filter); `:124` (item 4); `:31` (the `argLine` Decision Log bullet)

The rule R17 produced is mechanically checkable, which was the point, but its path filter is narrower than the invalidator set the same file establishes elsewhere.

`:143` requires that at track completion the recorded SHA "is an ancestor of HEAD with no intervening commit touching `core` or `embedded` **production or test sources**". Two classes of measurement-moving file fall outside a literal reading of "sources":

*Cucumber feature files.* They live at `core/src/test/resources/com/jetbrains/youtrackdb/internal/core/gremlin/gremlintest/features/` — resources, not sources. Item 4 at `:124` names them explicitly as movers: "a change to the Cucumber glue or a local feature file moves it too, and the `core` test-jar carries both", and gives that as the reason its re-run is unconditional. `GqlMatch.feature` is in that directory, which is where the `core` runner stalls. So the criterion's filter excludes a file class the same track file says invalidates the number.

*Both POMs.* `:31` makes `core/pom.xml`'s `<argLine>` block and `embedded/pom.xml`'s inline surefire `<configuration>` load-bearing for the measurement — a change to either alters the fork the number was taken in. Neither is a source file.

The failure mode is a stale baseline that passes the check. It is the same shape as Track 10's, one level down: the rule fires, the implementer runs it, and it returns clean on a commit that moved the measurement.

**Proposed fix**: state the filter as path prefixes rather than a file-kind description, so the check is the one-liner the recovery bullet promises: the recorded SHA is an ancestor of HEAD and `git log <sha>..HEAD -- core embedded` is empty. That covers sources, resources, and POMs with no judgment call, and it still excludes the `docs/adr/**` commits — episode, artifact, completion marker — that necessarily land after the run. Say that exclusion out loud, since it is the clause that makes the rule terminate.

### R25 [suggestion]
**Location**: `track-9.md:25` (DR-S1's two quoted figures)

DR-S1 now draws its conclusion from two figures whose labels do not match how they were measured. Neither error changes the conclusion, which is why this is a suggestion — but the bullet's whole subject is which figure answers which question, so a mislabelled figure is the one defect it can least afford.

*"Track 10 was past it on the full non-generated figure (5,331)."* 5,331 is the **unfiltered** insertion count for `f007749249..7c77a4544f`. Step 9's measurement excludes `**/internal/core/sql/parser/**`, and Track 10 touched three files there — `SQLSuffixIdentifier.java`, `SQLWhereClause.java`, `SQLSuffixIdentifierTest.java`, hand-written files that live under the generated-parser directory and are excluded by path. The step-9 figure is 36 files / 5,168 insertions, or 5,325 counting deletions the way step 9's "total `+`/`-` line count" phrasing implies. Still comfortably past 4,000, so the claim survives; the number attached to the label does not.

*"the plan's `~8–14 files` Scope line is stated on it [the code-only figure]."* The Scope line at `implementation-plan.md:678-686` enumerates the filter fix, `GqlMatchPatternAssembler` and the Track 1 GQL prettyPrint tests, the equivalence-suite shapes, **and** the two-runner baseline artifact — a `docs/adr/**` document. So it is code plus one artifact, which is what R15 originally objected to, and it is a file count rather than a line count, so it does not sit on either of the two line figures the bullet is distinguishing.

**Proposed fix**: quote 5,168 (or 5,325 with deletions) for the non-generated figure, and note that the three excluded files are hand-written code under the parser directory rather than generated output, since that is a surprise worth recording once. For the Scope line, say what it is — a file count covering code plus the baseline artifact — rather than tying it to the code-only line figure.
