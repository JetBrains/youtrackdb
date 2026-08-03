<!-- MANIFEST
findings: 8   severity: {blocker: 0, should-fix: 6, suggestion: 2}
index:
  - {id: R8,  sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:130", anchor: "### R8 ", cert: X5, basis: "the handoff baseline is pinned to the end of item 4, but Phase C review fixes land after the last Plan-of-Work item; Track 10 committed MatchExecutionPlanner and AbstractMatchPlanStep changes after publishing its dispositions artifact"}
  - {id: R9,  sev: should-fix, loc: "core/pom.xml:36", anchor: "### R9 ", cert: X6, basis: "core takes argLine from a POM property a CLI -DargLine replaces, embedded declares it inline where a CLI -DargLine is inert, so the same A/B technique breaks one runner's JVM config and silently drops the kill-switch on the other"}
  - {id: R10, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:85", anchor: "### R10 ", cert: X7, basis: "the ESCALATE trigger is an AND whose evidence conjunct needs a live safepoint-reachable fork; jcmd cannot deliver it against a dead fork or a stalled VMThread, and there is no branch for an uncapturable diagnostic"}
  - {id: R11, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:87", anchor: "### R11 ", cert: X8, basis: "the per-partition fallback starts a fresh JVM per directory, which is exactly the signal Track 11's stateful ListShapingOp re-arm and clone hazards need; the escalation branch does not name the loss"}
  - {id: R12, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:110", anchor: "### R12 ", cert: X9, basis: "three fix sites with a 32-minute gate between them and no decision rule, and the rollback note is deferred to a step that item 4's triage fixes then stack on top of"}
  - {id: R13, sev: should-fix, loc: "core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java:383", anchor: "### R13 ", cert: X10, basis: "mergedTargetFilter's sole caller is buildNotExpression, so the dead class branch T30 found is a live class-drop on every translated not(); the track lists the method in scope and names no not() acceptance shape"}
  - {id: R14, sev: suggestion,  loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:85", anchor: "### R14 ", cert: A9, basis: "no directory stalls alone, so the minimal stalling set has two or more members among seven; a growing-prefix scan inside six attempts cannot isolate it, making escalation the expected outcome rather than the exception"}
  - {id: R15, sev: suggestion,  loc: "docs/adr/gremlin-to-match-translator-design/_workflow/implementation-plan.md:678", anchor: "### R15 ", cert: A10, basis: "the Scope line counts code plus one artifact while DR-S1's threshold claim is over all non-generated lines; Track 9's Phase A has already written 1,741 lines of review prose before a step file exists"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 3}
cert_index:
  - {id: X5,  verdict: HIGH, anchor: "#### X5 "}
  - {id: X6,  verdict: HIGH, anchor: "#### X6 "}
  - {id: X7,  verdict: MEDIUM, anchor: "#### X7 "}
  - {id: X8,  verdict: MEDIUM, anchor: "#### X8 "}
  - {id: X9,  verdict: MEDIUM, anchor: "#### X9 "}
  - {id: X10, verdict: MEDIUM, anchor: "#### X10 "}
  - {id: X11, verdict: LOW, anchor: "#### X11 "}
  - {id: A9,  verdict: UNVALIDATED, anchor: "#### A9 "}
  - {id: A10, verdict: UNVALIDATED, anchor: "#### A10 "}
  - {id: A11, verdict: VALIDATED, anchor: "#### A11 "}
  - {id: TS2, verdict: ACHIEVABLE, anchor: "#### TS2 "}
flags: [CONTRACT_OK]
-->

# Track 9 — risk review, post-split scope, iteration 2

The fourth measurement variant exists and Track 10 already shipped it. Track 10 committed its dispositions artifact at `a0b3e96e15` and then committed `5db5b41a3d` — a Phase C review fix touching `MatchExecutionPlanner.java` and `AbstractMatchPlanStep.java`, 219 insertions across eight files — before marking the track complete. Track 9's acceptance criterion 12 pins the handoff baseline to "the end of item 4", which is the end of the Plan of Work and not the end of the track, so the same sequence reproduces by construction: the number Track 11 reads is measured, then production code moves under it, and nothing re-triggers the measurement. The Decision Log rule generalizes the *trigger* correctly and assigns no owner past the last numbered item.

A fifth variant is in the two POMs. `core` gets its fork JVM from an `<argLine>` **property** (`core/pom.xml:36-73`), which a CLI `-DargLine=` replaces wholesale — `-ea`, the 4 GB heap pin, the storage tuning, every `--add-opens`. `embedded` declares `<argLine>` **inline in the surefire configuration** (`embedded/pom.xml:419-444`), where a CLI property is inert. So the same technique for setting the kill-switch mangles one runner's JVM and silently drops the flag on the other, leaving an `embedded` "translator off" arm that ran with the translator on. `core` has a known-good off-side number (1930) that would expose a botched arm. `embedded` has none, which is the half item 1 has to build from scratch.

The escalation branch is the other concentration of risk, in two directions the track does not connect. Its evidence precondition needs a live fork that answers `jcmd`, and Track 10's three runs show the fork *dying*; the trigger is an AND with no branch for a diagnostic that cannot be captured, so the track can end up with no permitted exit. And the fallback it defines removes the one signal Track 11 most needs: a per-directory partition starts a fresh JVM per directory, while Track 11's own file records that its `ListShapingOp` re-arm and clone hazards only manifest across many scenarios in one fork.

Not everything came back bad. The `embedded` stale-install trap is honoured at all four measurement points (X11), `./mvnw -pl core -am install -DskipTests` does cover everything `embedded` resolves from the local repository (A11), and the four named acceptance shapes are ordinary additions to two existing equivalence suites with no fixture work (TS2).

**Tooling caveat.** PSI was not attempted. The two preceding rounds each lost a call to it on this repository — `steroid_execute_code` timing out on kotlinc cold-start, then `Project not found` — and the track file's own `## Clarifications` records the limitation. Every symbol and call-site result below is `grep -n` / `find` / `git` plus an end-to-end read of the declaring file. The negatives that carry weight — "`mergedTargetFilter` has one caller", "`core/pom.xml` declares no inline surefire `argLine`", "`embedded` declares no reactor dependency other than `youtrackdb-core`" — were each established by reading the whole declaring file, which bounds the usual grep risk without eliminating it. A caller in another module or reached by reflection would not appear. The measurements that are not symbol searches — the commit ordering in X5, the POM structure in X6 — are exact.

## Findings

### R8 [should-fix]
**Certificate**: X5 (the handoff baseline against the track's own later commits)
**Location**: Track 9 `## Validation and Acceptance` criterion 12 (line 130), `## Plan of Work` item 4 (line 112), `## Decision Log` second bullet (line 26); commits `a0b3e96e15`, `5db5b41a3d`, `7c77a4544f`

**Issue**: The criterion pins the published baseline to the end of the Plan of Work, and the track continues past that point.

Criterion 12 reads "The baseline handed to Track 11 is measured at the end of item 4, not after item 2". Item 4 is the last numbered item; the track then runs Phase C (track-level code review) and a completion commit, both listed in `## Progress`. Track 10 is the worked example and the sequence is in the log, newest last:

```
a0b3e96e15  Record compliance-failure dispositions for Track 10   (the measurement artifact)
5db5b41a3d  Review fix: consolidate the plan walk ...             (8 files, 219 insertions)
7c77a4544f  Mark Track 10 complete
```

`5db5b41a3d` changed `MatchExecutionPlanner.java` and `AbstractMatchPlanStep.java` — the planner this track is about to edit and the boundary base every translated query runs through. Track 10's own published artifact was therefore stale at the moment the track closed, by the track's own rule, and nobody noticed because the rule's trigger list stops at the Plan of Work.

The same gap swallows a second invalidator. Track 10's episode was caused by a rebase; this branch rebased on 2026-08-02 mid-track and the pattern is recurring. If `develop` moves between Track 9's completion and Track 11's Phase B, Track 11's no-regression claim reads against a baseline measured on a different base. `plan/track-11.md`'s Decision Log says Track 9 "publishes that artifact explicitly for this purpose" and neither track names an owner for re-validating it.

Likelihood is high — one of the two invalidators fired on the immediately preceding track. Impact is that Track 11's headline claim is unfalsifiable in the same way Track 10's was, which is the failure this whole split was ordered around.

**Proposed fix**: Restate criterion 12 against HEAD rather than against an item: the published baseline names a SHA, and that SHA must equal the track's final HEAD at completion. Add the complement to the Decision Log's second bullet — a commit landing after the baseline run re-triggers the run, Phase C review fixes included — and add one line to `## Idempotence and Recovery` naming the re-run as the recovery. On the Track 11 side, add a precondition to item 6: confirm the baseline's SHA is an ancestor of Track 11's base with no intervening `core` commit, and re-take it otherwise.

### R9 [should-fix]
**Certificate**: X6 (the two runners' `argLine` surfaces and the kill-switch path)
**Location**: Track 9 `## Validation and Acceptance` criteria 1 and 2 (lines 121-122) and criterion 14 (line 132), `## Plan of Work` item 1's `embedded` clause (line 83); `core/pom.xml:36-73`, `embedded/pom.xml:419-444`

**Issue**: The two runners answer the same A/B technique differently, and only one of them has a number that would catch the difference.

`core/pom.xml` declares `<argLine>` in `<properties>` at `:36-73` and configures no inline `argLine` anywhere in the file — the surefire parameter picks it up through its `argLine` user property. A CLI `-DargLine=…` therefore **replaces** the whole block: `-ea`, `-Xms4096m -Xmx4096m`, the `youtrackdb.storage.*` tuning, and all fourteen `--add-opens` / `--add-exports`. `embedded/pom.xml` declares `<argLine>` **inside** the surefire `<configuration>` at `:419-444`, where plugin configuration beats the user property, so a CLI `-DargLine=…` is inert there and anything smuggled inside it never reaches the fork.

That asymmetry is live in this track's own evidence trail. The pre-split risk review's run 3 set the kill-switch through `-DargLine=…` and run 4 through a plain `-D`; the reviewer called run 4 "the controlled one" and the track quotes run 4's number, but records neither run 3 nor the reason it was discarded. An implementer building item 1's `embedded` A/B from scratch has no such warning, and the two failure shapes differ:

- On `core`, the off-arm runs in a different JVM from the on-arm — default heap, assertions disabled, module access closed. The A/B stops being one flag apart, which is the sentence the whole attribution rests on.
- On `embedded`, the off-arm silently runs **with the translator on**. If `embedded` also stalls, both arms stall, and the natural reading is "the strategy is not the cause here" — the exact inverse of the truth, published into the artifact Track 11 reads.

`core` is protected by accident: 1930 green in 17 s is a loud signal that the off-arm really was off. `embedded` has no such number, and the track says so — "That run has never been measured on this branch". The unprotected half is the one being built.

**Proposed fix**: Pin the property form explicitly in criteria 1 and 2 — a plain `-Dyoutrackdb.query.gremlin.toMatchTranslator.enabled=false`, never inside `-DargLine=` — and add one clause to criterion 14 recording why: on `core` a CLI `argLine` replaces the POM's `-ea` / heap / `--add-opens` block, and on `embedded` it is ignored, so the same mistake produces two different wrong measurements. Require the `embedded` off-arm to be self-witnessing before its number is published: assert the boundary step is absent from one recognised traversal's plan under the off-arm, the same check `plan/track-11.md` item 7 already specifies for the JMH harness.

### R10 [should-fix]
**Certificate**: X7 (item 1's escalation exit and its diagnostic precondition)
**Location**: Track 9 `## Plan of Work` item 1's ESCALATE trigger (line 85)

**Issue**: The trigger is a conjunction, and the conjunct the track added to make escalation honest is the one that may be impossible to satisfy.

The trigger fires when **both** hold: the committed artifact carries a thread dump and heap histogram from a stalled fork, and the bisect has run its budget. There is no third branch. If the diagnostic cannot be captured, escalation is not permitted, and the track has no defined exit from an unsized engine-level diagnosis — which is the specific unbounded cost the trigger was written to bound.

Three ways the capture fails, none hypothetical:

- **No live process.** `jcmd` needs a pid. Track 10's three runs did not stall and wait; the fork *died* — `Tests run: 0` followed by `The forked VM terminated without properly saying goodbye` after 31:35. Track 9's own on-side runs were killed by the operator at 15, 7 and 15 minutes, so the operator-kill shape does leave a window, but the shape that produced the corroborating evidence does not.
- **No safepoint.** `jcmd <pid> Thread.print` needs the target JVM to reach a safepoint. A thread spinning in a counted loop without safepoint polls, or a VMThread already stuck, leaves `jcmd` hanging with no output and no error.
- **The histogram perturbs the subject.** `jcmd <pid> GC.class_histogram` forces a full GC before counting. On a 4 GB fork already in a collection death spiral, that either takes minutes or clears the very accumulation being measured.

Machine evidence narrows what is left. This host has 62 GB with 45 available against a 4 GB fork heap, so an OS OOM-kill is not the explanation, and `grep -c OutOfMemoryError` over Track 10's three logs returned zero. A live-but-unresponsive fork is the likely shape, which is precisely the shape `jcmd` handles worst.

**Proposed fix**: Make the evidence precondition satisfiable-or-waived: "a thread dump and heap histogram from a stalled fork, or a recorded account of what was attempted and why it returned nothing (no live pid, `jcmd` attach timeout, `GC.class_histogram` not completing)". Reduce the dependence on catching the fork by hand — add `-XX:+HeapDumpOnOutOfMemoryError` with an explicit `-XX:HeapDumpPath`, and a surefire fork timeout, to the diagnostic runs so the stall self-documents on death rather than requiring an operator at the keyboard. Say in the branch that a waived diagnostic still escalates; a trigger that can be blocked by its own precondition is a trigger that never fires.

### R11 [should-fix]
**Certificate**: X8 (the per-partition baseline as Track 11's regression net)
**Location**: Track 9 `## Plan of Work` item 1's escalation branch (lines 87-89), `## Validation and Acceptance` criteria 1, 2 and 13; `plan/track-11.md:69,87` and its `## Context and Orientation` boundary-lifecycle paragraph

**Issue**: The fallback defers completion and, unremarked, removes the only measurement that can see Track 11's most likely defect class.

The track's own diagnosis of the stall is that "the failure is a property of running them in one JVM — accumulated state, a leak, or a deadlock across scenarios". Under escalation the published baseline becomes the seven-invocation per-directory run and Track 11's item 6 re-runs in that shape. Seven Maven starts is seven fresh JVMs, so no cross-scenario accumulation is observable in any of them.

Track 11's dominant risk is cross-scenario accumulation. Its own `## Context and Orientation` records two mechanisms: `applyListShaping` calls `op.apply(...)` afresh on every open across three open routes, so an op that allocates its buffer outside the returned iterator replays the first pass's payloads on the second; and `AbstractStep.clone()` copies `shaping` by reference while `resetLifecycleForClone()` deliberately does not touch it, so two clones share the same `ListShapingOp` instances. Both defects need many scenarios in one fork to surface, and both live in `fold` and `tail` — the two ops that need buffers. Track 11's item 5 adds targeted re-arm and clone unit tests, which is the right first net; the Cucumber run in one fork is the net that catches what the unit tests did not anticipate, and escalation removes it.

The escalation branch enumerates six documents to amend and says the completion goal "moves to a follow-up". Neither it nor `plan/track-11.md` says that the successor track loses a regression signal it was relying on.

**Proposed fix**: Add one clause to the escalation branch naming the loss — under a partitioned baseline no runner observes cross-scenario state, which is the class of defect Track 11's `ListShapingOp` buffers can introduce — and specify the compensating gate Track 11 inherits: one single-fork run of the largest partition (`map`, 811 scenarios) with the translator on, plus the re-arm and clone tests item 5 already plans, as the substitute for suite-wide in-fork coverage. Add the same clause to `plan/track-11.md`'s must-amend list so the successor reads it at its own Phase A rather than discovering it at item 6.

### R12 [should-fix]
**Certificate**: X9 (item 2's three sites, their costs, and the revert path)
**Location**: Track 9 `## Plan of Work` item 2 (lines 100-110), `## Validation and Acceptance` criteria 7-8 (lines 127-128)

**Issue**: The three sites carry materially different costs, the track gives no rule for choosing between them, and the rollback note is deferred to a step that later work stacks on.

The enumeration is now factually correct in every particular the technical panel checked, and it reads as three equals. It is not three equals:

- **Option 2** (post-`build()` pass in `GremlinStepWalker.buildResult`) is one file, translator-only, and C57 confirmed the one thing that could sink it — `Pattern.copy()` shares `SQLMatchPathItem` nodes and the planner takes the pattern by reference, so a post-build mutation reaches the objects `MatchEdgeTraverser` reads.
- **Option 1** (a `build(Map<String, SQLWhereClause>)` overload) widens a builder consumed by eleven `core` files, and C58's own verdict is that it "buys nothing option 2 lacks".
- **Option 3** (planner-side at `:2064`) requires a *second* loop because the existing one iterates an empty `matchExpressions` (T34), is reached by SQL `MATCH`, and books `MatchStatementExecutionTest` — 159 methods, about 32 minutes — as a gate the track pays every time it re-runs.

An implementer handed a flat list with the note that blast radius is "the second constraint" can pick option 3 and pay both the 32 minutes and the SQL `MATCH` exposure for no benefit that the track identifies.

The rollback obligation compounds it. Item 2 ends "Record the rollback story in the same step: which files revert, and what signal says the revert is complete" — the artifact is deferred to the step that lands the code. But item 4 then fixes more failures **on top of** item 2 inside the same track, and item 4's re-measure is the published handoff. Reverting item 2 after item 4 has landed is a multi-step unwind whose completion signal has itself moved twice: the item-3 number is post-item-2 and the item-4 number is post-triage, so neither survives the revert. The only number that does is item 1's pre-fix artifact, and the rollback note as scoped will not be written until after the point where it stops being cheap.

**Proposed fix**: Name option 2 as the default in item 2 and require a written reason to depart from it, with the reason recorded in `## Decision Log`. Move the rollback note earlier: it is written before item 2's code lands, states the revert order (item 4's triage fixes first, then item 2), and names item 1's pre-fix per-runner artifact as the signal that the revert is complete, since it is the only recorded number that survives both reverts.

### R13 [should-fix]
**Certificate**: X10 (`mergedTargetFilter`'s sole caller and the NOT path)
**Location**: Track 9 `## Plan of Work` item 2's `mergedTargetFilter` paragraph (line 98) and `## Interfaces and Dependencies` "Signatures" (line 151); `MatchPatternBuilder.java:344-403`

**Issue**: The method the track analyses as a template is a live component of a path the track does not scope, and the defect T30 found in it is a real defect there.

`mergedTargetFilter` has exactly one caller, `buildNotExpression` (`:344-375`), which every translated `not(...)` sub-traversal goes through. Two consequences follow that the track states neither:

**The class-drop is not hypothetical on the NOT path.** T30 established that `className` is read at `:381` and consumed only in the `else` branch at `:386`, and that every additive-path positive item already carries a filter so the copy branch always wins. `buildNotExpression` does `var item = edge.item.copy()` at `:363` and passes `item.getFilter()` as `existingItemFilter`. Those items come from the same two factories (`MatchEdgePathItems:66,84`), so `existingItemFilter` is never null there either, and the class is never bound on a NOT item. Under polymorphic mode, where `HasStepRecogniser:163-164` puts the class nowhere else, `not(__.out().hasLabel(software))` drops its class constraint today. The track scopes the defect to a per-alias `WHERE` reaching only the root alias; the class half of the same defect exists on a second path the track calls out of scope by omission.

**Editing the template changes the NOT path.** T30's remedy is that "the chosen site must set the class on the copied filter too". An implementer who reads `mergedTargetFilter` as the reference and adds the class there — the smallest edit that satisfies the sentence — changes every translated `not()` as a side effect. `mergedTargetFilter` is listed in "Signatures", which is the list an implementer greps before touching anything, with no note that its only caller is NOT.

The acceptance set has no `not()` shape. Item 4's triage would meet the residue in the Cucumber set with no diagnosis prepared for it.

**Proposed fix**: Add one sentence to item 2's `mergedTargetFilter` paragraph: the method is NOT-path-only, its `WHERE` merge already works there because `buildNotExpression` passes a supplemental map, and its dead class branch is a live class-drop on translated `not()` under polymorphic mode. Decide explicitly whether item 2 fixes that too. If yes, add `g.V().not(__.out().hasLabel(software))` to `## Validation and Acceptance` on the class-binding side and name `NotStepRecogniser`'s suite as the regression net. If no, add the NOT-path class-drop to `## Out of scope` so item 4's triage dispositions it as a known separate defect rather than rediscovering it.

### R14 [suggestion]
**Certificate**: A9 (the bisect budget against the shape of the search)
**Location**: Track 9 `## Plan of Work` item 1 (lines 83-85)

**Issue**: The budget is sound as a cost bound and unlikely to hold as a search plan, which makes escalation the expected path rather than the exception.

Every one of the seven upstream directories completes alone, so the minimal stalling set has at least two members among seven. The prescribed diagnostic is a growing prefix — "two upstream directories, then four, until it stalls" — which answers "how many directories does it take" and not "which ones". If the interacting pair is not adjacent in the chosen order, isolating it from a stalling prefix is delta-debugging over subsets, worst case on the order of twenty runs for a two-element interaction among seven. Six attempts does not cover that, and each stalling attempt costs the operator's kill timeout: 15, 7 and 15 minutes are the three observed.

Two working days is a reasonable bound on absorbed cost and should stay. The attempt count is the one that decides the outcome, and at six it selects escalation almost regardless of how the search goes.

That is not automatically wrong. It becomes wrong only because the escalation branch is written as an exception path — six documents amended under time pressure at the trigger, with R11's signal loss unrecorded. If escalation is the expected outcome, the amendments are cheaper to make once, up front.

**Proposed fix**: Either state the search as delta-debugging over subsets rather than a growing prefix and raise the attempt budget to match, or accept escalation as the planned path: make the per-partition baseline the primary deliverable, apply the six document amendments now, and treat single-fork completion as the upside case that retires them. Recording which of the two was chosen, and why, in `## Decision Log` is what keeps the choice from being made silently by the clock.

### R15 [suggestion]
**Certificate**: A10 (the Scope line against the threshold DR-S1 invokes)
**Location**: `implementation-plan.md:678` (Track 9's Scope line) and Track 9 `## Decision Log` DR-S1 (line 25)

**Issue**: The sizing statement and the threshold it is meant to satisfy measure different quantities, so nothing in the track can detect a third over-threshold track.

DR-S1 justifies the split partly on keeping the branch "off a third consecutive track past the ~4,000-line review-burden threshold", and cites Track 8 at 38 files / 5,814 insertions and Track 10 at 39 / 5,331. The threshold is over all non-generated changed lines. The plan's Scope line answers with "~8–14 files", enumerating the fix site, the equivalence-suite shapes, and the baseline artifact — code plus one document.

Measured, the omitted half is the larger one. Track 10's range splits 24 files / 2,718 insertions of code against 15 files / 2,613 insertions under `docs/adr`. Track 9's `_workflow/` output already stands at 1,741 lines across seven Phase A review files with the panel unfinished, before a single step file, episode, or baseline artifact exists; Track 10's `plan/track-10/reviews/` alone totals 3,154 lines. Track 9's code half will be genuinely small, so the split probably does hold — but the claim is unfalsifiable as instrumented, because the number a future reader checks does not count the thing the threshold counts.

**Proposed fix**: Either restate DR-S1's claim on the code-only figure, which is the comparable number and the one the pre-split R5 recommended, or extend the Scope line with a `_workflow/` estimate so the sizing statement and the threshold measure the same quantity. Either way, say which figure the ESCALATE-on-size instruction reads.

## Evidence base

#### X5 Exposure: the published handoff baseline against the track's own later commits
- **Track claim**: `## Validation and Acceptance` criterion 12 — "The baseline handed to Track 11 is measured at the end of item 4 … The re-run is unconditional, so no artifact stamped item-3 is ever the handoff." `## Decision Log` second bullet — "Recompute the measured baseline whenever anything moves the measured behaviour."
- **Critical path trace**:
  1. `## Progress` lists four gates in order: review + decomposition, step implementation, track-level code review, track completion. Item 4 is the last Plan-of-Work item, so it completes inside gate 2.
  2. Phase C (gate 3) produces code changes. On Track 10 it produced six, of which `5db5b41a3d` landed after the measurement artifact.
  3. `git log --format='%h %s' a0b3e96e15..7c77a4544f` returns exactly two commits: `5db5b41a3d` then `7c77a4544f`. `git show --stat 5db5b41a3d` lists `AbstractMatchPlanStep.java` (+43), `MatchExecutionPlanner.java` (+8/-8), `ExecutionPlanIntrospection.java` (+76), and five test files — 8 files, 219 insertions, 122 deletions.
  4. `git show --stat a0b3e96e15` lists only `track-10.md` and `core-compliance-failure-dispositions.md`, so the artifact carried no code and was not re-taken afterwards.
- **Blast radius**: Track 11's item 6 no-regression claim, its headline acceptance criterion, and `plan/track-11.md`'s Decision Log bullet naming Track 9's artifact as the comparison point. A stale baseline reproduces Track 10's Phase C handoff error one track later, on the same branch, with the same shape.
- **Existing safeguards**: the Decision Log's generalized trigger ("anything moves the measured behaviour") covers the case in principle. Nothing operationalises it — no criterion reads the baseline's SHA against HEAD, and `## Idempotence and Recovery` is an empty Phase A placeholder.
- **Residual risk**: HIGH — produces R8.

#### X6 Exposure: the two runners' `argLine` surfaces and the kill-switch path
- **Track claim**: `## Context and Orientation`'s A/B table — "same command, one flag apart"; criterion 2 requires the `embedded` run's scenario count "in the same range as its own translator-off run", a number that does not yet exist.
- **Critical path trace**:
  1. `grep -n argLine core/pom.xml` returns exactly two hits, `:36` and `:73` — the open and close of an `<argLine>` element inside `<properties>` (`:25` opens `<properties>`). The surefire `<configuration>` at `:375-386` carries `systemPropertyVariables` and a `listener` property and no `argLine`; no execution-level `argLine` exists in the file.
  2. Surefire's `argLine` parameter is bound to the `argLine` user property, so it resolves from the POM property. A CLI `-DargLine=…` is a user property and overrides a POM `<properties>` entry, replacing `-ea`, `-Xms${heapSize} -Xmx${heapSize}` (`pom.xml:103` → `4096m`), the `youtrackdb.storage.*` settings, and every `--add-opens` / `--add-exports` in `:36-73`.
  3. `embedded/pom.xml:414-445` puts `<argLine>` inside the surefire `<configuration>`, spanning `:419-444` and ending `-XX:+IgnoreUnrecognizedVMOptions`. Plugin configuration takes precedence over the parameter's user-property default, so a CLI `-DargLine=…` is discarded there and anything inside it never reaches the fork.
  4. The plain form works on both: surefire forwards the Maven process's system properties into the fork, which is why the pre-split control run 4 (plain `-D`) reported 1930 green while runs 1-2 hung.
- **Blast radius**: both halves of item 1's baseline, item 3's gate, item 4's handoff, and Track 11's re-run. On `embedded` specifically, a mis-set off-arm produces two on-arms and inverts the strategy attribution for the runner that has no reference number.
- **Existing safeguards**: `core`'s 1930/17 s off-side is a de-facto self-check. `embedded` has none — the track states the run "has never been measured on this branch at all". Neither POM asymmetry is recorded anywhere in the track file or in `plan/track-11.md`.
- **Residual risk**: HIGH — produces R9.

#### X7 Exposure: item 1's escalation exit and its diagnostic precondition
- **Track claim**: item 1 — "The trigger fires when **both** hold: the committed artifact carries a thread dump and heap histogram from a stalled fork … and the bisect has not narrowed the stall to a fixable defect within two working days or six bisect attempts."
- **Critical path trace**:
  1. `jcmd` resolves on this host (`/home/sandra-adamiec/.jdks/jbr-25.0.2/bin/jcmd`), so tool availability is not the gap.
  2. `jcmd <pid> Thread.print` requires the target JVM to reach a safepoint before the dump is produced. A thread in a counted loop without safepoint polls, or a VMThread already blocked, leaves the command without output.
  3. `jcmd <pid> GC.class_histogram` performs a full collection before counting unless `-all` is passed, so on a 4 GB fork in a collection death spiral it either takes minutes or perturbs the accumulation under investigation.
  4. The fork must still exist. Track 10's three runs recorded the fork dying — `Tests run: 0` plus `The forked VM terminated without properly saying goodbye` after 31:35 at `/tmp/track10-final-verify.log`. Track 9's own on-side runs were operator-killed at 15 / 7 / 15 minutes and so did leave a window, but the two shapes are not the same and only one is guaranteed.
  5. Environment: `free -g` reports 62 GB total with 45 available against a 4 GB fork heap (`pom.xml:103`), and `grep -c OutOfMemoryError` over Track 10's three logs returned 0, so neither an OS OOM-kill nor a reported heap exhaustion explains the death.
- **Blast radius**: the whole track. With no exit from item 1, an unsized engine-level diagnosis absorbs unbounded time in front of items 2, 3 and 4, which is exactly the failure Track 10's risk review produced this trigger shape to prevent.
- **Existing safeguards**: the budget clause bounds the *search*. It does not bound the trigger, because the trigger is an AND and the budget is only one conjunct.
- **Residual risk**: MEDIUM — produces R10. Bounded because the operator-kill shape has been observed to leave a live fork twice; unbounded on the branch where it does not.

#### X8 Exposure: the per-partition baseline as Track 11's regression net
- **Track claim**: item 1's escalation branch — "the seven-invocation per-directory run becomes `core`'s published baseline … Items 3 and 4 re-measure in those shapes and acceptance criteria 1 and 2 relax to 'completes per partition with the recorded failure set'."
- **Critical path trace**:
  1. The stall's own diagnosis, from `## Context and Orientation`: "No scenario hangs in isolation, so the failure is a property of running them in one JVM — accumulated state, a leak, or a deadlock across scenarios."
  2. Seven `-Dcucumber.features=` invocations are seven Maven starts and seven forks. Nothing accumulates across them.
  3. `plan/track-11.md` `## Context and Orientation` records two accumulation hazards its own work introduces: `applyListShaping` calls `op.apply(...)` afresh on every open across three open routes (`NEW`, `REARMED`, `REARMED_AFTER_CLOSE`), so an op allocating its buffer outside the returned iterator replays the first pass; and `AbstractStep.clone()` copies `shaping` by reference while `resetLifecycleForClone()` does not touch it, so two clones share the same `ListShapingOp` instances.
  4. Both hazards sit in `fold` and `tail` — the two of four ops that need buffers — and both surface only when many boundary steps open, re-arm and clone inside one process.
  5. `plan/track-11.md:69` is the item that performs the re-run and names the single-fork `gremlin-feature-compliance-tests` execution; under escalation it becomes the partition. T35 already requires that line to be amended, for a different reason.
- **Blast radius**: Track 11's ability to detect its own dominant defect class before merge. The unit-level re-arm and clone tests item 5 plans remain, so the loss is the unanticipated case rather than the anticipated one.
- **Existing safeguards**: Track 11 item 5's targeted re-arm and clone tests, and `MultiPlanMatchStepTest`'s clone-isolation idiom. Neither runs many scenarios in one fork.
- **Residual risk**: MEDIUM — produces R11.

#### X9 Exposure: item 2's three sites, their costs, and the revert path
- **Track claim**: item 2 — "the blast radius is the *second* constraint on the choice, not the first"; the three sites are presented as a numbered enumeration with no recommendation. "Record the rollback story in the same step: which files revert, and what signal says the revert is complete."
- **Critical path trace**:
  1. Option 2 lands in `GremlinStepWalker.buildResult`, reached only by the translator. C57 verified `Pattern.copy()` shares `SQLMatchPathItem` nodes (`Pattern.java:252`, `edgeCopy.item = edge.item`) and that the planner assigns `inputs.pattern()` by reference, so a post-`build()` mutation reaches the objects `MatchEdgeTraverser` reads at `:476`, `:481`, `:486`.
  2. Option 1 adds a public overload to `MatchPatternBuilder`, which eleven `core` files consume (C36). C58's verdict: "It costs one more public method than option 2 and buys nothing option 2 lacks."
  3. Option 3 is at `MatchExecutionPlanner:2064`, reached by all three front-ends, and T34 established that its existing loop runs zero times on the translated path — so it needs a second loop over `pattern`, at a call site SQL `MATCH` shares. Criterion 8 books `MatchStatementExecutionTest` (159 `@Test` methods, ~32 minutes) as gate cost when it is chosen.
  4. Revert ordering: item 4's triage fixes land on top of item 2 inside this track, and item 4's re-measure is the published handoff. Reverting item 2 afterwards unwinds two layers, and both post-item-2 numbers (item 3's and item 4's) are invalidated by the revert.
- **Blast radius**: for option 3, every SQL `MATCH` query in the product plus a 32-minute gate on each iteration. For a late revert, the handoff artifact and Track 11's start.
- **Existing safeguards**: criterion 4 catches a `WHERE`-only or class-only fix after implementation; criterion 5 catches an overwriting merge; criteria 7-8 catch a SQL `MATCH` regression. All three fire after the code is written, and item 1's pre-fix artifact is the one number that survives a full revert — the track does not say so.
- **Residual risk**: MEDIUM — produces R12. Recoverable in every branch, at a cost the track does not price.

#### X10 Exposure: `mergedTargetFilter`'s sole caller and the NOT path
- **Track claim**: item 2 — "`MatchPatternBuilder.mergedTargetFilter` (`:377-402`) is the right template for the `WHERE` merge and for copy-not-replace, and **not** for the class (T30)"; `mergedTargetFilter` is listed in `## Interfaces and Dependencies` "Signatures".
- **Critical path trace**:
  1. `mergedTargetFilter` is `private` and has one caller, `buildNotExpression` (`MatchPatternBuilder.java:344-375`), read end to end.
  2. `buildNotExpression` walks the captured fragment's linear hop chain, does `var item = edge.item.copy()` (`:363`), and calls `item.setFilter(mergedTargetFilter(item.getFilter(), targetAlias, supplementalAliasFilters))` (`:365`).
  3. Inside `mergedTargetFilter`, `className = aliasClasses.get(alias)` at `:381` is consumed only at `:386`, inside `else`. `existingItemFilter` is `item.getFilter()`, which the two factories always populate (`MatchEdgePathItems:66,84`), so the copy branch at `:383-384` always fires and no class is set on a NOT item.
  4. The `WHERE` does bind on this path, from two sources: `aliasFilters.get(alias)` (the builder's own map, empty on the translated path per T29) and `supplementalAliasFilters.get(alias)`, which the caller supplies — so the NOT path already has the merged-map channel the positive path lacks.
  5. `HasStepRecogniser:162-164` calls `ctx.addNode(boundary, labelClass)` unconditionally and adds the `@class = 'L'` term to the `WHERE` only under `!ctx.polymorphic()`, so in polymorphic mode a dropped class is a dropped constraint.
- **Blast radius**: every translated `not(...)` carrying a `hasLabel` on a hop target, today. Plus, if an implementer satisfies T30's "set the class on the copied filter" by editing `mergedTargetFilter` in place, every translated `not(...)` changes behaviour with no acceptance criterion watching.
- **Existing safeguards**: `NotStepRecogniser`'s own suite is the only net; `## Validation and Acceptance` names no `not()` shape, and `## Out of scope` does not mention the NOT path.
- **Residual risk**: MEDIUM — produces R13. Reference-accuracy caveat: the "one caller" negative is a repository grep over `core/src/main/java` plus an end-to-end read of `MatchPatternBuilder`; a reflective or cross-module caller would not appear.

#### X11 Exposure: the `embedded` stale-install trap across every measurement point
- **Track claim**: `## Clarifications` — "The `embedded` runner cannot be measured with a bare `./mvnw -pl embedded test` … A future reader must not mistake a run against an installed jar for a run against the branch."
- **Critical path trace**: I checked every place the track schedules an `embedded` measurement. Item 1's second-runner A/B (line 83), item 3's re-measurement (line 111), item 4's handoff re-run (line 112), and criterion 2 (line 122). Criterion 2 carries the blanket obligation — "The install step is not optional and is repeated before every `embedded` measurement, including item 3's gate re-run and item 4's published handoff re-run" — which covers all four by construction. `plan/track-11.md`'s fifth Clarification repeats the same rule for item 6 and points back at Track 9's Clarifications for the mechanism. The test-jar half is covered too: the track names `GraphFeatureWorld` and the local feature files explicitly, and `-DskipTests` skips execution rather than test compilation, so the jar is produced.
- **Blast radius**: would have been the whole `embedded` half of the baseline.
- **Existing safeguards**: the blanket criterion, restated in the successor track.
- **Residual risk**: LOW — no finding. Recorded because the opposite result would have invalidated the handoff artifact, and because it is the one measurement rule this track inherited that is stated as an invariant rather than as an instance.

#### A9 Assumption: six bisect attempts cover the search item 1 prescribes
- **Track claim**: item 1 — "the bisect has not narrowed the stall to a fixable defect within **two working days or six bisect attempts**, whichever comes first", with the search stated as "`-Dcucumber.features=` with two upstream directories, then four, until it stalls".
- **Evidence search**: the per-directory results recorded in the track (`branch` 134, `data` 98, `filter` 369, `integrated` 175, `map` 811, `semantics` 97, `sideEffect` 204 — all complete alone) and the three observed stall durations (15 / 7 / 15 minutes, operator-killed). Tool: reading the track's own recorded measurements, not a symbol search.
- **Code evidence**: `plan/track-9.md:48` (every directory completes in seconds), `:41` (the three kill times).
- **Verdict**: UNVALIDATED
- **Detail**: a growing prefix determines the stalling prefix length, not the interacting members. With no single-directory culprit the minimal set has two or more of seven, and isolating a two-element interaction from a stalling prefix is a subset search on the order of twenty runs in the worst case. Six attempts bounds cost but does not bound the search, so escalation is selected by the budget rather than by the evidence. Produces R14 at suggestion severity — the budget is doing its intended job; what is missing is the acknowledgement that its likely outcome is the branch, not the main line.

#### A10 Assumption: Track 9's stated scope keeps it under the review-burden threshold
- **Track claim**: DR-S1 — the split "keeps this branch off a third consecutive track past the ~4,000-line review-burden threshold"; `implementation-plan.md:678` — "**Scope:** ~8–14 files".
- **Evidence search**: `git diff --shortstat f007749249..7c77a4544f`, the same restricted to `docs/adr` and to `*.java` / `*.xml`; `wc -l` over `plan/track-9/reviews/*.md` and `plan/track-10/reviews/*.md`.
- **Code evidence**: Track 10 total 39 files / 5,331 insertions; `docs/adr` half 15 files / 2,613; code half 24 files / 2,718. `plan/track-9/reviews/` currently 7 files / 1,741 lines with the post-split panel incomplete. `plan/track-10/reviews/` 3,154 lines.
- **Verdict**: UNVALIDATED
- **Detail**: the conclusion is probably right — Track 9's code half is genuinely small — but the instrument cannot show it. The Scope line counts code plus one artifact; the threshold counts all non-generated changed lines, where `_workflow/` was the larger half on the preceding track. Produces R15.

#### A11 Assumption: `./mvnw -pl core -am install -DskipTests` covers everything `embedded` resolves from the local repository
- **Track claim**: criterion 2 pins that command as the prerequisite for every `embedded` measurement.
- **Evidence search**: `grep -n "<module>" pom.xml`; full read of `embedded/pom.xml`'s `<dependencies>` block (`:50-108`); `grep -n "youtrackdb-test-commons\|gremlin-annotations" core/pom.xml`.
- **Code evidence**: `embedded/pom.xml` declares exactly one reactor artifact — `youtrackdb-core`, twice (compile, and `<type>test-jar</type>` at test scope). Its other test dependencies are `junit`, `slf4j-jdk14`, `io.youtrackdb:gremlin-test`, `io.youtrackdb:tinkergraph-gremlin`, `io.cucumber:cucumber-java`, `io.cucumber:cucumber-junit`, and Guice. The two `io.youtrackdb` Gremlin artifacts are the TinkerPop fork's published jars, not reactor modules (`pom.xml:44-54` lists the eleven modules and neither appears). `core/pom.xml:618` and `:718` declare `youtrackdb-gremlin-annotations` and `youtrackdb-test-commons`, both reactor modules, so `-am` from `core` builds and installs them.
- **Verdict**: VALIDATED
- **Detail**: no finding. The command's reactor closure is sufficient. Reference-accuracy caveat: dependency reading, not a resolved `dependency:tree` — a transitive reactor artifact pulled in through a BOM would not have surfaced.

#### TS2 Testability: the named acceptance shapes and the suites that must carry them
- **Coverage target**: 85% line / 70% branch
- **Difficulty assessment**: low. The four `WHERE`-side and class-side shapes and the edge-alias preservation shape are plain traversals over the four-vertex modern graph, added to two suites that already build that fixture. C47 measured the gap and confirmed the shapes are additions rather than modifications: `EdgeTraversalEquivalenceTest` has 33 `@Test` methods covering bare and edge-as-node hops with no post-hop predicate, and `PredicateTraversalEquivalenceTest` has 42, every one rooted at `g.V()`. The track's "watch it fail before production code changes" clause is the right discipline and costs nothing extra here.
- **Existing test infrastructure**: `EdgeTraversalEquivalenceTest` and `PredicateTraversalEquivalenceTest` (the two named suites); `GqlMatchStatementPlanPrettyPrintTest` and `GqlMatchStatementTest` for the shared-mutator pin; `HashJoinPlannerIntegrationTest:1197,2505` for the anti-join regressions.
- **Feasibility**: ACHIEVABLE
- **Detail**: no finding on the shapes the track names. The gaps are elsewhere and are covered above — the NOT surface has no shape at all (R13), and the `embedded` off-arm has no self-check (R9). The item-1 diagnosis is not a coverage question: whatever it turns out to touch is unsized, and the track books that honestly.
