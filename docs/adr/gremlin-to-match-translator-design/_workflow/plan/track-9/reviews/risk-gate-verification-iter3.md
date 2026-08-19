<!-- MANIFEST
findings: 7   severity: {blocker: 0, should-fix: 4, suggestion: 3}
index:
  - {id: R16, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:138", anchor: "### R16 ", cert: "#### Verify R13", basis: "the new not() acceptance shape returns the same multiset with and without the class on every fixture the track names, so its 'fails before the fix' clause is false and the watch-it-fail gate passes vacuously"}
  - {id: R17, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:144", anchor: "### R17 ", cert: "#### Verify R8", basis: "'the recorded SHA must equal HEAD at track completion' cannot hold — recording the artifact is itself a commit — and the same file's recovery bullet states the satisfiable ancestor form instead"}
  - {id: R18, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:123", anchor: "### R18 ", cert: "#### Verify R12", basis: "R12's fix added the write-it-early instruction at :115 and left the superseded write-it-with-the-code sentence at :123, so item 2 now carries both"}
  - {id: R19, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:25", anchor: "### R19 ", cert: "#### Verify R15", basis: "on the code-only figure Track 10 measures 2,718 and is not past ~4,000, so DR-S1's 'third consecutive' premise is falsified by its own numbers, and track-code-review.md's actual measurement excludes only generated code"}
  - {id: R20, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:96", anchor: "### R20 ", cert: "#### Verify R11", basis: "R13's inserted criterion shifted the acceptance ordinals, so the escalation must-amend list's 'criterion 13' now names the pinned-commands criterion instead of the two-runner one"}
  - {id: R21, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:164", anchor: "### R21 ", cert: "#### Verify R13", basis: "the NOT-path fix is a second edit in a second file, and '## Interfaces and Dependencies' still lists only the three item-2 sites plus SQLMatchFilter"}
  - {id: R22, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:86", anchor: "### R22 ", cert: "#### Verify R10", basis: "the added surefire fork timeout kills the fork the same sentence says to capture jcmd from, and HeapDumpOnOutOfMemoryError cannot fire on the no-OOM shape the R10 paragraph itself diagnoses"}
verdicts:
  - {id: R8,  verdict: REGRESSION}
  - {id: R9,  verdict: VERIFIED}
  - {id: R10, verdict: VERIFIED}
  - {id: R11, verdict: VERIFIED}
  - {id: R12, verdict: REGRESSION}
  - {id: R13, verdict: REGRESSION}
  - {id: R14, verdict: VERIFIED}
  - {id: R15, verdict: REGRESSION}
overall: FAIL
flags: [CONTRACT_OK]
-->

# Track 9 — risk gate verification, iteration 3

Four of the eight fixes landed clean; four introduced a new defect while fixing the old one. R9, R10, R11 and R14 verify — the POM asymmetry is real and stated in the right direction, the escalation trigger can no longer be blocked by its own precondition, the signal loss Track 11 inherits is named with a compensating gate, and the twenty-run budget is still a bound rather than a licence. R8, R12, R13 and R15 each traded the reviewed problem for a checkable false statement: an equality rule that cannot hold, a rollback instruction that now appears twice with opposite content, an acceptance shape that cannot fail before the fix it witnesses, and a sizing claim its own numbers refute.

The pattern is the same one the technical panel hit at its iteration 2 — the fix text is added and the superseded text stays. Three rounds of insertions have also drifted the acceptance-criteria ordinals that the escalation branch addresses by number.

**Tooling caveat.** PSI was not attempted, per the spawn. Every symbol result below is `grep -rn` over `core/src` plus an end-to-end read of the declaring method, and the POM and fixture results are direct reads. The negatives that carry weight — `mergedTargetFilter` has one caller, `buildNotExpression` has one production caller (`NotStepRecogniser:93`), `core/pom.xml` declares no inline surefire `argLine` — were each established by reading the whole declaring file. A reflective or cross-module caller would not appear.

## Verification certificates

#### Verify R8: the published baseline against the track's own later commits
- **Original issue**: criterion 12 pinned the handoff baseline to the end of item 4, which is the end of the Plan of Work and not the end of the track; Track 10 landed a production-code Phase C fix after publishing its measurement artifact.
- **Fix applied**: the criterion (now `:144`) is restated — the baseline names a SHA and "the recorded SHA must equal HEAD at track completion", with the Track 10 sequence quoted. A `## Decision Log` bullet at `:29` extends the re-trigger list to track completion including Phase C review fixes and states Track 11's reciprocal ancestor check. `## Idempotence and Recovery`, previously an empty placeholder, now carries the re-run recovery at `:154`.
- **Re-check**:
  - Location: `track-9.md:29`, `:144`, `:154`.
  - Current state: the substance of R8 is addressed — the trigger list runs past the Plan of Work, Phase C fixes are named, and the recovery exists. Three statements of the same rule now disagree on what is checked. `:144` requires equality with HEAD. `:154` says the check is "a one-line `git merge-base --is-ancestor`", which is ancestry, not equality. `:29` says Track 11 confirms the SHA "is an ancestor of its own base with no intervening `core` commit", which is ancestry plus a path filter.
  - Criteria met: the trigger's scope is fixed; its operationalisation is not.
- **Regression check**: checked the three statements against each other and against the track's own commit sequence. Equality cannot hold: recording the artifact is a commit, the episode record is a commit, and "Mark Track N complete" is a commit — all three land after the measurement, exactly as they did on Track 10 (`a0b3e96e15` → `5db5b41a3d` → `7c77a4544f`). Read literally the criterion is unmeetable and an implementer discards it, which is the R8 failure mode. New issue — R17.
- **Verdict**: REGRESSION

#### Verify R9: the two runners' `argLine` surfaces
- **Original issue**: `core` takes its fork JVM from a POM property that a CLI `-DargLine=` replaces, `embedded` declares it inline where a CLI `-DargLine=` is inert, so one technique produces two different wrong measurements and only `core` has a number that exposes it.
- **Fix applied**: a `## Decision Log` bullet at `:31` records the asymmetry and mandates the plain `-D` form; criterion 2 (`:135`) requires the `embedded` off-arm to be self-witnessing; the pinned-commands criterion (`:146`) carries the never-inside-`argLine` rule.
- **Re-check**:
  - Location: `core/pom.xml`, `embedded/pom.xml`, `track-9.md:31`, `:135`, `:146`.
  - Current state: I re-read both POMs. `grep -n argLine core/pom.xml` returns exactly two lines, `:36` and `:73`, the open and close of an `<argLine>` element inside the `<properties>` block that opens at `:25`; no surefire `<configuration>` in the file carries an `argLine`. `embedded/pom.xml` returns four: `:386-388` inside the **failsafe** `<configuration>` (`-ea` only, integration-test path, not on the `test` path) and `:419-444` inside the **surefire** `<configuration>`, ending `-XX:+IgnoreUnrecognizedVMOptions`. The direction of each consequence holds: a CLI user property overrides a POM `<properties>` entry, so `core`'s `-ea` / `-Xms4096m -Xmx4096m` / storage tuning / fourteen `--add-opens` are replaced; plugin `<configuration>` beats a parameter's user-property default, so `embedded` discards the CLI value entirely.
  - Criteria met: the mechanism is stated accurately, the mandate is unambiguous, and the unprotected half now has a self-check.
- **Regression check**: verified the cross-reference in criterion 2. `plan/track-11.md:105` (item 7) does specify "asserting the boundary step is installed in the first and absent in the second" for the JMH harness, so the check it points at exists and is the right shape. Clean.
- **Verdict**: VERIFIED

#### Verify R10: the escalation trigger's diagnostic precondition
- **Original issue**: the ESCALATE trigger was an AND whose evidence conjunct required a live safepoint-reachable fork, which the observed failure shapes may never provide, leaving the track with no permitted exit.
- **Fix applied**: the trigger at `:88` now reads "either a thread dump and heap histogram from a stalled fork, or a recorded account of what was attempted and why it returned nothing". A paragraph at `:90` records the three failure modes and the host's memory context. Item 1 gained `-XX:+HeapDumpOnOutOfMemoryError`, an explicit `-XX:HeapDumpPath`, and a surefire fork timeout.
- **Re-check**:
  - Location: `track-9.md:86`, `:88`, `:90`.
  - Current state: the conjunct is satisfiable-or-waived and the waiver is conditioned on a recorded attempt, so it is not a blank exemption. The three failure modes are stated with the evidence each rests on.
  - Criteria met: the trigger can now fire on every branch the evidence supports.
- **Regression check**: checked the interaction the spawn flagged — can the waiver escalate on attempt one? No. The budget conjunct at `:88` is untouched and independent: escalation still requires that the bisect has not narrowed the stall "within two working days or twenty bisect runs, whichever comes first". A waiver on run one leaves the first conjunct false. Separately, the two mechanical mitigations do not fit the shape the same paragraph diagnoses, and one of them contradicts an instruction four sentences earlier — R22, at suggestion severity, not enough to hold the verdict.
- **Verdict**: VERIFIED

#### Verify R11: the per-partition baseline as Track 11's regression net
- **Original issue**: the escalation fallback removes cross-scenario in-fork coverage, which is the defect class Track 11's `ListShapingOp` buffers introduce, and nothing said so.
- **Fix applied**: a paragraph at `:94` names the loss, restates both accumulation mechanisms, and specifies the compensating gate — one single-fork run of the `map` partition, 811 scenarios, translator on, alongside Track 11's item-5 re-arm and clone tests. The clause joins the must-amend list.
- **Re-check**:
  - Location: `track-9.md:94`, `plan/track-11.md:69,87,105`.
  - Current state: the loss is stated where the escalation decision is made rather than discovered at Track 11 item 6. `map` at 811 is the largest partition by the track's own per-directory numbers (134 + 98 + 369 + 175 + 811 + 97 + 204 = 1,888, matching the recorded total).
  - Criteria met: the successor's inherited gate is named and sized.
- **Regression check**: `plan/track-11.md` is unmodified in the working tree. That is acceptable — the compensating gate only binds under escalation, the must-amend list is the mechanism that lands it, and Track 11's Phase A runs after Track 9 completes, so the amendment precedes the read. The clause's own back-reference points the wrong way, folded into R20.
- **Verdict**: VERIFIED

#### Verify R12: item 2's default site and the rollback ordering
- **Original issue**: three fix sites with materially different costs presented as equals and no decision rule, plus a rollback note deferred to the step that item 4's triage then stacks on.
- **Fix applied**: a paragraph at `:113` names option 2 the default, compares all three costs, and requires a `## Decision Log` entry to depart. A paragraph at `:115` moves the rollback note before the code lands, with the revert order and item 1's pre-fix artifact as the completion signal. Both are echoed in `## Idempotence and Recovery` at `:155`.
- **Re-check**:
  - Location: `track-9.md:113`, `:115`, `:155`; `GremlinStepWalker.java:457-508`; `UnionStepRecogniser.java:95-114`; `MatchPatternBuilder.java:417-423`.
  - Current state: option 2 satisfies every constraint T29 and T34 impose. I read `buildResult` end to end. At the single-plan branch all three inputs are in scope in the same method: `ir.pattern()` (`:484`), `ir.aliasClasses()` (`:485`), and `finalAliasFilters`, built at `:470-477` by merging `ctx.aliasFilters` over `ir.aliasFilters()` with `andWhere`. Merge-not-overwrite is satisfiable there because the AND-composition helper already exists at the site. The edge-alias `WHERE` constraint holds through the skip rule: `finalAliasFilters` and `aliasClasses` are keyed by node aliases only — the edge predicate goes to `ctx.edgeFilters` through `putEdgeFilter`, which never reaches `MatchPlanInputs` — so an edge path item has no entry in either map and the skip rule leaves its filter alone.
  - Criteria met: the default is named, its cost advantage is stated, and departing is recorded.
- **Regression check**: two areas. Union children are covered — `UnionStepRecogniser:95` obtains each child through `host.walkFork(...)`, which returns a `TranslationResult` produced by the same `buildResult`, so a fix in the single-plan branch reaches every child plan; no coverage hole. The rollback fix did regress: `:115` says write the note before the code lands, and `:123` — carried unchanged from `HEAD`, where it was the sentence R12 objected to — still says "Record the rollback story in the same step". Item 2 now instructs both. New issue — R18.
- **Verdict**: REGRESSION

#### Verify R13: the NOT path and the fix-it decision
- **Original issue**: `mergedTargetFilter`'s dead class branch is a live class-drop on every translated `not()` under polymorphic mode, the track listed the method as a template without saying so, and no `not()` acceptance shape existed.
- **Fix applied**: the orchestrator chose fix-it. A paragraph at `:117` states the method is NOT-path-only, that the class-drop is live there, and that this track fixes it in the same step. A `not()` acceptance criterion was added at `:138` with `NotStepRecogniser`'s suite as the regression net.
- **Re-check**:
  - Location: `track-9.md:117`, `:138`; `MatchPatternBuilder.java:344-403`; `NotStepRecogniser.java:93`; `NotStepRecogniserTest.java`.
  - Current state: the code claims hold. `mergedTargetFilter` (`:377`) is private with one caller, `buildNotExpression:365`; `buildNotExpression` has one production caller, `NotStepRecogniser:93`; `className = aliasClasses.get(alias)` at `:381` is consumed only in the `else` branch at `:386`, and the caller passes `item.getFilter()` from a factory-built item, so the copy branch wins and no class is bound.
  - Is the expansion a second project? No. It is one branch in one private method — set the class on the copied filter — and the track says plainly that this edit lands on the NOT path while the four named shapes need whichever of the three sites item 2 chose. Bounded, and honestly labelled as a second site.
- **Regression check**: the acceptance shape does not do the job it is given. `g.V().not(__.out().hasLabel(software))` returns the same multiset with and without the class binding on every fixture the track names, so its "fails before the fix" clause is false and the watch-it-fail discipline passes vacuously — R16. The named net is weaker than described: `NotStepRecogniserTest` has ten tests, all structural assertions over the emitted `SQLMatchExpression` (origin alias, item count, leaf `WHERE` text), and none asserts a class on a NOT item, so it detects change rather than witnessing the fix. The second fix site is also missing from the track's scope bookkeeping — R21.
- **Verdict**: REGRESSION

#### Verify R14: the bisect budget against the shape of the search
- **Original issue**: a growing-prefix scan inside six attempts cannot isolate a non-adjacent interacting pair among seven directories, so escalation was selected by the budget rather than by the evidence.
- **Fix applied**: item 1's diagnostic at `:86` is restated as delta-debugging over subsets, "down from a stalling set rather than up from an empty one". The budget is two working days or twenty bisect runs. A `## Decision Log` bullet at `:30` records the choice — size the budget to the search — and its reasoning.
- **Re-check**:
  - Location: `track-9.md:30`, `:86`, `:88`.
  - Current state: the search shape and the budget now match, and the Decision Log states which of the two options R14 offered was taken and why.
  - Criteria met: the recorded choice is the one the spawn describes.
- **Regression check**: checked the interaction with R10's waiver, which the spawn flagged. The budget is still a bound: "whichever comes first" caps absorbed cost at two working days regardless of run count, and twenty runs at the worst observed kill time of 15 minutes is about five hours, comfortably inside it. The waiver relaxes only the evidence conjunct and cannot substitute for the budget conjunct, so a longer search does not become an open-ended licence. Clean.
- **Verdict**: VERIFIED

#### Verify R15: the Scope line against the threshold DR-S1 invokes
- **Original issue**: DR-S1's threshold claim was over all non-generated changed lines while the Scope line counted code plus one artifact, so nothing could detect a third over-threshold track.
- **Fix applied**: DR-S1 at `:25` now says the claim "is made on the code-only figure, and the ESCALATE-on-size instruction reads the same one", quoting Track 8 at 38 files / 5,814 insertions and Track 10 at 24 files / 2,718 insertions of code against 15 files / 2,613 under `docs/adr`.
- **Re-check**:
  - Location: `track-9.md:25`; `.claude/workflow/track-code-review.md:312-353`.
  - Current state: the two quantities now agree with each other, which was the ask. I verified Track 8's split directly — `git diff bc8641e51a^..88eb31413a` gives 58 files / 9,280 insertions total, 20 / 3,466 under `docs/adr`, 38 / 5,814 for `*.java` and `*.xml` — so the quoted Track 8 pair is indeed the code-only figure and the comparison is like-for-like.
  - Criteria met: the mismatch R15 identified is gone.
- **Regression check**: the restatement falsifies the sentence it supports and redefines a threshold this track does not own. On the code-only figure Track 10 measures 2,718, which is not past ~4,000, so "a third consecutive track past the ~4,000-line review-burden threshold" is contradicted by the numbers in the same bullet. And `track-code-review.md` step 9 computes the figure from a `--shortstat` that excludes only generated sources and the generated parser — `docs/adr/**` is counted — so the instruction DR-S1 claims reads the code-only figure does not. New issue — R19.
- **Verdict**: REGRESSION

## Findings

### R16 [should-fix]
**Location**: `track-9.md:138` (the new `not()` acceptance criterion), `:53` (the fixture the track's other shapes are measured on)

The added criterion asserts that `g.V().not(__.out().hasLabel(software))` "returns the native multiset under polymorphic mode, and **fails before the fix**". It does not fail before the fix on any fixture the track names.

Dropping the class from a NOT hop target turns `not(out().hasLabel(software))` into `not(out())`. The two differ only when some vertex has out-edges and none of them lands on a `software` vertex. On the four-vertex graph the track measures its other four shapes against (`:53` — marko `-knows->` vadas, josh; `-created->` lop) marko is the only vertex with out-edges and one of them is lop, so both readings exclude marko and return `{vadas, josh, lop}`. On the standard six-vertex modern graph the three vertices with out-edges — marko, josh, peter — each have at least one software out-neighbour, so both readings return `{vadas, lop, ripple}`. The class-drop is invisible in both.

The consequence is not a weak test, it is a misleading one. The track requires each new shape to be "watched to fail before production code changes" (`:137`). This shape will pass at that gate, and the implementer's reasonable inference is that the NOT-path defect the same round just brought into scope does not reproduce — so the R13 fix gets dropped on evidence that proves nothing.

`NotStepRecogniser`'s suite does not compensate. `NotStepRecogniserTest` has ten tests and every assertion is structural: origin alias, item count, `notMatchExpressions` size, leaf `WHERE` text contains a property name, plan builds without throwing. None reads a class off a NOT item. It is a change detector for the emitted expression, not a witness for class binding.

**Proposed fix**: replace the traversal with one that discriminates, and name the fixture. Either add a person-to-person hop to the four-vertex graph so a vertex exists whose only out-neighbour is not software, or state the shape on the six-vertex modern graph as `g.V().not(__.out().hasLabel(person))` — native returns five vertices, the class-dropped translation returns three. State the criterion as the fixture property rather than the traversal alone: the graph must contain a vertex with at least one out-edge and no out-edge to the filtered class. Add one assertion to `NotStepRecogniserTest` reading the class off the NOT item's filter, so the unit-level net actually covers the branch being changed.

### R17 [should-fix]
**Location**: `track-9.md:144` (the baseline criterion), `:29` (the Decision Log trigger bullet), `:154` (the recovery bullet)

The rule R8 produced cannot be satisfied as written, and the file states it three ways that do not agree.

`:144` requires "the recorded SHA must equal HEAD at track completion". Recording the artifact is itself a commit. So is the episode record, and so is the completion marker — the exact three-commit tail Track 10 shows at `a0b3e96e15` → `5db5b41a3d` → `7c77a4544f`. The recorded SHA is HEAD at measurement time and is never HEAD at completion. `:29` states the trigger as "any commit that lands after a baseline run and before track completion re-triggers that run", which as literal text is non-terminating: the commit that records the re-run re-triggers the re-run. `:154` gives the satisfiable form — `git merge-base --is-ancestor` — and Track 11's reciprocal at `:29` gives the useful one, ancestry plus "no intervening `core` commit".

The failure mode is the one R8 was written to prevent. An implementer who reads an unmeetable criterion at completion time either ignores it or declares it met by fiat, and either way nothing catches the Track 10 shape where production code moves under a published number.

**Proposed fix**: state the rule once, in the ancestry-plus-path-filter form the recovery bullet and the Track 11 reciprocal already use. The recorded SHA is an ancestor of the track's final HEAD with no intervening commit that touches the measured surface, and the measured surface is named: `core/src` (which carries the Cucumber glue and the local feature files, per item 4 at `:125`) and `embedded/`. Doc-only commits under `docs/adr/**` do not re-trigger. Make `:144` and `:29` restate that rule rather than two weaker variants of it.

### R18 [should-fix]
**Location**: `track-9.md:115` (the fix R12 asked for) and `:123` (the sentence it was meant to replace)

Item 2 now carries two rollback instructions with opposite content. `:115` says "Write the rollback note before the code lands, not with it", states the revert order, and names item 1's pre-fix artifact as the completion signal. `:123`, unchanged from `HEAD` and the exact sentence risk-iter2 quoted as the defect, still says "Record the rollback story in the same step: which files revert, and what signal says the revert is complete."

`:123` is the last line of item 2, which is where an implementer working the item from the bottom finishes reading. Following it reproduces the deferral R12 was accepted to remove: the note gets written with the code, item 4 then stacks triage fixes on top, and the completion signal has moved twice before anyone writes it down.

**Proposed fix**: delete `:123`. Its content is fully subsumed by `:115`, which says the same thing with the ordering and the signal attached.

### R19 [should-fix]
**Location**: `track-9.md:25` (DR-S1); `.claude/workflow/track-code-review.md:312-353`

Restating DR-S1's threshold claim on the code-only figure breaks the sentence it supports and points at an instruction that measures something else.

The bullet says the split "keeps this branch off a third consecutive track past the ~4,000-line review-burden threshold" and then quotes Track 10's code half at 2,718 insertions. 2,718 is not past 4,000. Track 8's code half is 5,814 (I verified: `git diff bc8641e51a^..88eb31413a -- '*.java' '*.xml'` gives 38 files / 5,814, against 20 / 3,466 under `docs/adr` and 58 / 9,280 total). So on the figure the bullet now nominates there is one over-threshold track, not two consecutive ones, and "a third consecutive" is false against its own evidence.

The second half is the operational one. `track-code-review.md` step 9 is the ESCALATE-on-size instruction, and its `--shortstat` excludes only `**/generated-sources/**`, `**/generated-test-sources/**` and `**/internal/core/sql/parser/**`. `docs/adr/**` is counted; the file says explicitly that test code is kept "because reviewing test behavior is real review work". DR-S1 cannot redefine what that check measures by asserting it. The practical effect of the assertion is a gate blind to the larger half of the burden: on Track 10 the `_workflow/` prose was 2,613 lines against 2,718 of code, and this track's Phase A output already stands past 1,700 with the panel unfinished.

**Proposed fix**: keep the code-only figure as the *comparison* number — it is the right one for judging whether a correctness fix is reviewable on its own merits — and drop the claim that the ESCALATE instruction reads it. Say instead that Phase C's review-burden check reads the full non-generated figure, that Track 10's was 5,331, and that this track expects a small code half against a `_workflow/` half sized like its predecessors. Replace "a third consecutive track past the threshold" with what the numbers support: Track 8 was past it on code alone, Track 10 was past it on the full figure, and a combined Track 9 would have been past it on both.

### R20 [suggestion]
**Location**: `track-9.md:96` (the must-amend list's "acceptance criterion 13"), `:94` (the R11 clause's "the must-amend list above")

Two escalation-branch cross-references drifted this round, and the escalation branch is the part of the track that gets executed under time pressure.

R13's insertion at `:138` added a criterion in the middle of `## Validation and Acceptance`, shifting every ordinal below it. The list now runs to thirteen and the two-runner criterion — the one carrying "a suite that meets the completion gate in `core` and hangs in `embedded` has not met this track's Purpose", which is what "hard-codes completion" means — is number twelve. Number thirteen is the pinned-commands criterion. The must-amend list at `:96` says "acceptance criterion 13 below", so under escalation the implementer amends the wrong one. The ordinals have moved in each of the three fix rounds; the pre-round file had eleven criteria and the risk-iter2 findings cited numbers that matched neither state.

Separately, `:94` ends "This clause joins the must-amend list above", and the must-amend list is at `:96`, below it.

**Proposed fix**: address criteria by name, not by ordinal — "the two-runner coverage criterion (S1)" instead of "criterion 13" — everywhere the track cross-references one. Fix the direction word at `:94`.

### R21 [suggestion]
**Location**: `track-9.md:164` (`## Interfaces and Dependencies`, "In scope (modified)"), `:166` (`## Out of scope`)

R13's fix-it decision expands the track to a second fix site in a second file, and the section that declares what the track modifies does not record it.

`:164` lists "the per-alias filter binding for non-root aliases, at one of the three sites item 2 enumerates … plus `SQLMatchFilter` if the merge needs a class-setting helper the copy path lacks". Under the default (option 2, in `GremlinStepWalker.buildResult`) the track now also edits `MatchPatternBuilder.mergedTargetFilter`'s copy branch. The two are genuinely disjoint code paths: NOT sub-expressions are built by `buildNotExpression` into `ctx.notMatchExpressions` and are not part of `ir.pattern()`, so no post-`build()` pass over the pattern's edge items touches them.

That section is the one an implementer greps before touching anything, and it is what a Phase C reviewer reads to know whether a changed file was planned.

**Proposed fix**: add the NOT-path class binding to "In scope (modified)" as its own entry naming `MatchPatternBuilder.mergedTargetFilter`, and note in "Signatures" that the method is NOT-path-only so the entry is not read as a template reference.

### R22 [suggestion]
**Location**: `track-9.md:86` (item 1's diagnostic instructions), `:90` (the waiver paragraph)

The two mechanical mitigations added under R10 do not fit the failure shape the same round diagnosed, and one of them contradicts an instruction four sentences earlier.

`:86` says to capture `jcmd <pid> Thread.print` and `GC.class_histogram` "from the stalled fork **before** killing it", then in the next sentence to give the diagnostic runs "a surefire fork timeout, so a stall self-documents on death instead of requiring an operator at the keyboard". A surefire fork timeout kills the fork. Set short it pre-empts the manual capture the previous sentence requires; set long it does not shorten a run the operator was already killing by hand at 7 to 15 minutes. No value is given, so the conflict is unresolved rather than tuned.

`-XX:+HeapDumpOnOutOfMemoryError` fires only on a Java heap OOM. `:90` records that `grep -c OutOfMemoryError` over Track 10's three logs returns zero and concludes "a live-but-unresponsive fork is the likely shape". On that shape the flag produces nothing, so "a stall self-documents on death" overstates what was added. The flag is free and worth keeping for the case where the diagnosis is wrong; the sentence claiming it removes the operator dependency is not supported.

**Proposed fix**: give the fork timeout a value and a stated role — long enough to clear the observed kill window (say 20 minutes) so it is a backstop for unattended runs rather than a competitor to the manual capture — and say which runs are attended and which are not. Soften the self-documentation claim to what the flags deliver: a heap dump if the fork does exhaust its heap, and a bounded run time if nobody is watching. For the diagnosed shape the recorded-attempt waiver is still the operative exit.
