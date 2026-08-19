<!-- MANIFEST
findings: 11   severity: {blocker: 3, should-fix: 6, suggestion: 2}
index:
  - {id: R10, sev: blocker,    loc: "track-11.md items 2-3; GremlinStepWalker.java:442-451,:500-573; AbstractMatchPlanStep.java:372-397", anchor: "### R10 ", cert: C1, basis: "the walker's two in-loop captured-state gates do not read listShapingOps and the track proposes no third, so g.V().values(k).fold().limit(2) compiles LIMIT over rows and folds two; capturedCardinalityClause is now the template iteration 1 said did not exist", supersedes: R2}
  - {id: R11, sev: blocker,    loc: "track-11.md item 4; GremlinStepWalkerTest.java:1343-1355; GremlinStepWalker.java:619-641", anchor: "### R11 ", cert: C2, basis: "the build-failing test asserts the method is declared, never its value, so fold added to POST_UNION_RECOGNISERS with selectsPositionally false satisfies item 4's literal instruction and ships union(...).fold() as an order-sensitive wrong answer item 4 itself argues must decline"}
  - {id: R12, sev: blocker,    loc: "track-11.md item 6 + both acceptance bullets; plan/track-9.md Plan of Work item 4", anchor: "### R12 ", cert: C3, basis: "the gate is now absolute with no fix-vs-defer bound, no destination, no ESCALATE valve and no successor track; grep for the whole disposition vocabulary returns one hit and it is about review iterations", supersedes: R1}
  - {id: R13, sev: should-fix, loc: "track-11.md item 6 (embedded clause); .github/workflows/maven-pipeline.yml:173,:185,:334,:410", anchor: "### R13 ", cert: C4, basis: "item 6 trusts CI because the legs allegedly run without -Dmaven.test.failure.ignore=true; every leg passes it, so a green leg carried 41 embedded failures at b35ac67d2f; no target number and an unsatisfiable final-tree condition"}
  - {id: R14, sev: should-fix, loc: "track-11.md item 7; git ls-remote origin; jmh-ldbc/pom.xml:20-23", anchor: "### R14 ", cert: C5, basis: "neither t11-item7-jmh nor the item-8 probe branch exists on origin despite configured upstreams, HEAD carries none of the three files, the track names two different commits as the deliverable, and the -pl re-run resolves core from the local repository", supersedes: R5}
  - {id: R15, sev: should-fix, loc: "track-11.md item 8 (Tests); plan/track-9.md Surprises; plan/track-11/item8-label-probe.md:73-84,:109", anchor: "### R15 ", cert: C6, basis: "item 8's HasStepRecogniser bind makes the translated arm answer correctly a shape the native arm answers as [] (measured), so the two kill-switch arms diverge on an improvement; the probe's four parity measurements all avoid the intervening-hop spelling that triggers it"}
  - {id: R16, sev: should-fix, loc: "track-11.md item 6 + acceptance; plan/track-9.md Episodes Step 1", anchor: "### R16 ", cert: C7, basis: "Track 9 recorded TinkerPop's unstable strategy sort as 'a lead, not a closed question' for the ~1200-scenario flip, while item 6's criterion moved from comparative to absolute — one flipped scenario is now a hard gate failure with no valve", supersedes: R3}
  - {id: R17, sev: should-fix, loc: "track-11.md item 7; implementation-plan.md Non-Goals", anchor: "### R17 ", cert: C8, basis: "item 7 benchmarks a live translator-on regression on g.V(rid) and names none of Track 9's three permitted destinations; the plan's only cacheEligible hit is Track 5's note, not a Non-Goals entry", supersedes: R6}
  - {id: R18, sev: should-fix, loc: "track-11.md item 5 + acceptance bullet", anchor: "### R18 ", cert: C9, basis: "fold and tail share the by-reference shaping copy across clones, and item 5 specifies re-arm for both and clone for fold only; tail's shared deque yields the right window size with the wrong contents", supersedes: R7}
  - {id: R19, sev: suggestion, loc: "implementation-plan.md Track 11 Scope line; plan/track-9.md DR-S1", anchor: "### R19 ", cert: C10, basis: "the re-priced bound is ~35-45 files against a ~20-25 split-candidate bound, and DR-S1's only remedy was a split the last track on a branch cannot take", supersedes: R9}
  - {id: R20, sev: suggestion, loc: "plan/track-9.md Surprises (three carry entries); implementation-plan.md Track 9 entry", anchor: "### R20 ", cert: C11, basis: "five unowned defects are recorded only inside the directory the Phase-4 cleanup commit deletes, and this track's completion is the last checkpoint before it"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 11}
cert_index:
  - {id: C1,  verdict: HIGH,         anchor: "#### C1 "}
  - {id: C2,  verdict: HIGH,         anchor: "#### C2 "}
  - {id: C3,  verdict: DIFFICULT,    anchor: "#### C3 "}
  - {id: C4,  verdict: CONTRADICTED, anchor: "#### C4 "}
  - {id: C5,  verdict: CONTRADICTED, anchor: "#### C5 "}
  - {id: C6,  verdict: VALIDATED,    anchor: "#### C6 "}
  - {id: C7,  verdict: UNVALIDATED,  anchor: "#### C7 "}
  - {id: C8,  verdict: VALIDATED,    anchor: "#### C8 "}
  - {id: C9,  verdict: DIFFICULT,    anchor: "#### C9 "}
  - {id: C10, verdict: DIFFICULT,    anchor: "#### C10 "}
  - {id: C11, verdict: VALIDATED,    anchor: "#### C11 "}
flags: [CONTRACT_OK, GREP_NOT_PSI, FULL_RE_REVIEW]
-->

# Track 11 risk review — iteration 2

Three blockers, six should-fix, two suggestions. All three blockers are the same structural fact seen from three angles: this is the last track, the kill-switch defaults on, and a wrong answer that leaves here leaves on `develop`.

Two of the three are new and both are silent wrong answers on ordinary Gremlin. The walker already carries two in-loop captured-state gates and neither reads `listShapingOps()`, so nothing stops `g.V().values("name").fold().limit(2)` from compiling `LIMIT` over rows and folding two of them (R10) — and the template iteration 1 said did not exist is now sitting three declarations away. And item 4's build-failing test for `POST_UNION_RECOGNISERS` checks that a member declares an answer, never which answer, so `fold` added with `selectsPositionally → false` passes the build and ships the union shape item 4 itself argues must decline (R11). The third blocker is iteration 1's R1, unchanged in substance and worse in setting: the pre-flight amendment made item 6's gate absolute without giving it a disposition rule, an escalation valve, or anywhere to send a deferral (R12).

The should-fixes divide into two groups. Three are claims the track makes that the tree refutes — CI does not verify `embedded` the way item 6 says it does (R13), item 7's deliverable is on a branch that exists on one disk (R14), and item 8's fix makes the translated arm disagree with a native arm that is measurably wrong (R15). Three are unchanged from iteration 1 and re-verified at HEAD (R16, R17, R18).

## Reviewer notes

**Why a full re-review.** Iteration 1 read `54cc0a708f`. Track 9 has since rewritten `RecognitionContext`, `WalkerContext`, `GremlinStepWalker`, `GremlinPredicateAdapter` and `MatchWhereBuilder`, and the track file was amended four times today across `f2b1230db0` and `749850fcb7`. Every iteration-1 finding was re-tested against HEAD rather than carried.

**What the amendments discharged.** R4 is gone: item 6's absolute criterion removes the baseline artifact entirely, so there is no stale-artifact hazard and no ancestor check to owe. R8 is gone: both new `src/main` classes landed in `benchmarks.ldbc` directly, inside the single-level JaCoCo exclusion. R5's vacuous-pass half is gone by design — the harness asserts on a `fold` shape no pre-Track-11 core jar can translate, so a stale classpath now fails loudly; its unpinned-invocation half survives inside R14. R2 is narrowed rather than discharged: the pre-flight amendment added the post-strategy-step-list obligation, which fixes *which* step list a position gate reads and leaves untouched the question of whether one exists (R10). R1, R3, R6, R7 and R9 stand and are re-raised as R12, R16, R17, R18 and R19.

**Deliberately not re-litigated.** The technical review's T9–T12 are applied at HEAD and correct as applied. R11 sits beside T9's territory without contradicting it: T9 fixed how item 1's seam is *shaped*, R11 is about a different gate whose enforcement item 4 leans on.

**Reference accuracy.** Grep plus end-to-end Reads of every returned site, not PSI. One `steroid_execute_code` attempt was not made this pass — `### Clarifications` records the timeout, all three iteration-1 panels hit it, and today's technical pass hit it again. Declaration reads, control-flow traces, `git ls-remote` results and YAML reads below are reliable. "No other caller" negatives are bounded rather than established; the one negative doing real work is `bindStepLabels`' two production callers in C6, and the finding survives even if a third exists, since it turns on the *added* call site rather than on the existing count.

**Working tree.** Read at `749850fcb7`, clean. No measurement runs were needed; every claim resolves from source, YAML, git plumbing and the workflow record.

## Findings

### R10 [blocker]
**Supersedes**: R2 (iteration 1) — narrowed to the single-plan path and re-pointed at an in-repo template that did not exist when R2 was written.
**Certificate**: C1
**Location**: `plan/track-11.md` items 2 and 3; `GremlinStepWalker.java:442-451`, `:500-573`; `AbstractMatchPlanStep.java:372-397`

**Issue**: The walker carries two in-loop, captured-state, fail-closed gates. Neither reads `listShapingOps()`, and this track proposes no third. So once a terminator appends an op on the single-plan path, dispatch keeps claiming steps with nothing checking that the op was last.

The wrong answers that follow are ordinary shapes, not corner cases. `g.V().values("name").fold().limit(2)` compiles `LIMIT 2` into the statement, and the fold then drains two rows into a list of two; native folds first and keeps the one list it made. `.fold().order()`, `.unfold().dedup()`, `.fold().count()` are the same defect, because row-level clauses land in the SQL statement and `applyListShaping` runs strictly after the projection source is built (`AbstractMatchPlanStep:372-375`). The kill-switch is `true` by default (`GlobalConfiguration.java:1019-1028`), so these ship at merge on the last track of the branch.

What has changed since iteration 1 is the cost of the fix, not the risk. R2 reported "no in-repo idiom to copy". `capturedCardinalityClause` @ `:534` plus `POST_CARDINALITY_RECOGNISERS` @ `:569` is now exactly that idiom: a boolean read off the context in `dispatchAll`, an allow-list of the recognisers whose contribution survives it, and a javadoc @ `:539-568` that argues each membership on "can this recogniser change the row set, its order, or its multiplicity". A shaping twin is the same three pieces.

The gate also has to be the loop's rather than each recogniser's, for the reason the cardinality javadoc gives at `:523-524`: "a recogniser added later inherits it without being told". Item 3's four words ("Mid-traversal use declines (D3)") put the obligation on four recognisers written this track and on every recogniser written after it.

Item 2 is where this is worst. It enumerates its decline branches exhaustively — `!isListFold()` and `!supportsListShaping()` — and a position check is not among them, so an implementer working from item 2 alone ships `fold` with no last-step gate at all.

Severity is blocker rather than should-fix because the population is ordinary Gremlin, the failure is silent, the switch defaults on, and no track follows this one.

**Proposed fix**:
1. Add an explicit sub-item to item 4 (beside the two child gates, which are the same kind of work): a `capturedListShapingOp(ctx)` in-loop gate in `dispatchAll`, modelled on `capturedCardinalityClause`, with an allow-list holding only the per-payload terminator recognisers. That admits `reverse().unfold()` and `unfold().reverse()`, declines `fold().limit(2)`, `unfold().dedup()`, `fold().order()`, `fold().count()`, and makes the file's "any mid-traversal list-shaper declines" true by construction rather than by four recognisers each remembering.
2. Decide and write down which of the four are in that allow-list. `fold` and `tail` are drains and windows, so they must be last; `unfold` and `reverse` are per-payload, so they may follow. That is the rule the file's own examples already imply — `reverse().unfold()` accepted, `fold().unfold()` declined — and it is stated nowhere.
3. Move item 2's position check out of prose and into its enumerated decline list, so the item that spells out its branches spells out all of them.
4. Add to item 5: `g.V().values("name").fold().limit(2)` and `g.V().valueMap().unfold().dedup()`, each with the positive control item 5's pre-flight amendment already requires.

### R11 [blocker]
**Certificate**: C2
**Location**: `plan/track-11.md` item 4; `GremlinStepWalkerTest.java:1343-1355`; `GremlinStepWalker.java:619-641`

**Issue**: Item 4 instructs "add the four terminator recognisers to `GremlinStepWalker.POST_UNION_RECOGNISERS`" and, two sentences earlier, that "`fold` cannot be a bare post-union suffix at all". The mechanical enforcement it leans on cannot tell those apart.

The build-failing test asserts only that the class declares the method:

```java
.anyMatch(m -> m.getName().equals("selectsPositionally"))
```

An implementer who adds `FoldStepRecogniser` with `selectsPositionally → false` satisfies the test, satisfies item 4's literal instruction, and ships `union(__.out(), __.in()).fold()` as a translated shape. Its result is a one-element multiset whose member is a `List` built over a child-ordered concatenation, compared against native's interleaved order — order-sensitive `List.equals`, so a silent wrong answer on the exact ground item 4 states for declining it.

`tail` is safe only because item 4 names its answer outright. `fold` needs a non-default answer too and item 4 never says which one, while the enforcement's whole design (`GremlinStepWalkerTest:1335-1342`, "whoever widens the allow-list has to state an answer instead of omitting one") is built on the assumption that stating an answer is the hard part.

**Proposed fix**: State `fold`'s answer in item 4 the way `tail`'s is stated, and say which mechanism carries it. Two are available and the choice is worth making explicitly: answer `selectsPositionally → true` for `fold`, which reuses the existing rule and leaves `union(...).fold().count()` translatable (native and translated both give 1); or keep `fold` out of `POST_UNION_RECOGNISERS` entirely, which is the plainer reading of "cannot be a bare post-union suffix" and costs `union(...).fold().count()`. Either way item 4 stops saying "add the four" without qualification. Add `union(__.out(), __.in()).fold()` to item 5's decline set with its positive control, since the build gate cannot witness it.

### R12 [blocker]
**Supersedes**: R1 (iteration 1) — the pre-flight amendment made the gate absolute and added a detection obligation, which removes the staleness failure mode and makes the missing disposition rule harder to live with rather than easier.
**Certificate**: C3
**Location**: `plan/track-11.md` item 6 and its two acceptance bullets; contrast `plan/track-9.md` `## Plan of Work` item 4's three rules

**Issue**: Item 6 now says what must be true — 1930 / 0 / 14 on both arms, plus a translator-on / translator-off equivalence check on every shape this track touches. It still says nothing about what to do with what it finds, and this is the last track.

`grep -n "ESCALATE\|escalat\|disposition\|waiver\|follow-up\|Non-Goals"` over `plan/track-11.md` returns exactly one line, DR-T4, and that is about review iterations. Track 9's item 4 carries three rules this track has none of:

- **A fix-vs-defer bound** — "in-track fixes are limited to the dropped-filter family plus defects whose diagnosis lands on files already in this track's scope", written because "'fix what belongs' with no rule is how Track 10's identical bucket became 483 repairs and the branch's largest track".
- **A destination for everything else** — "one of: fixed here, shape declined here, or a named follow-up — a YouTrack issue or a plan `### Non-Goals` amendment. 'Deferred' with no owner is not a disposition."
- **A no-deferral clause for the silent-wrong-answer family** — "`QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` is true by default, so anything the control run attributes to the translator ships live at merge."

Track 9 could write the second rule because Track 11 followed it. Nothing follows Track 11, so a deferral has literally nowhere to go, and the pre-flight amendment tightened the gate to an absolute count with no ESCALATE valve. The one relief clause it does carry — "a named and frozen exclusion list for anything that reproduces on `develop`" — cannot reach this track's own newly-claimed shapes by construction, since those do not exist on `develop`.

The newly-claimed population is not small. Registering four terminators moves scenarios in both directions, and the direction that matters is the one Track 9 did not face: a scenario green today *because the shape declines to the native pipeline* and red tomorrow because the translator claims it. This file's own `## Context and Orientation` calls `groupCount().unfold()` and `valueMap().unfold()` "ordinary idioms present in the Cucumber suite".

The item's second obligation compounds it. "Every shape this track touches also needs a measured translator-on / translator-off equivalence check" is an unbounded-cardinality task with no enumeration, no bound, and no rule for a disagreement it finds — which is the same open bucket, one level down.

**Proposed fix**: Three sentences in item 6, adapted to a track with no successor.
1. **State both directions.** Scenarios that start passing and scenarios that start failing are both recorded; only the second is a defect.
2. **Make the decline exit the default, in writing.** A newly-claimed shape that disagrees with native gets its recogniser's decline branch widened until it agrees — that restores translator-on-equals-translator-off by construction and costs one condition. Track 9 closed three of its four silent-wrong-answer defects exactly this way and recorded it as the cheaper exit. Repairing the projection instead is the exception and needs a written reason.
3. **Name the ESCALATE trigger and the only two destinations left.** A YouTrack issue, or a plan `### Non-Goals` amendment. Add the no-deferral clause verbatim from Track 9's item 4, minus the "later track inherits it" escape. Bound the equivalence-check obligation to an enumerated shape list — the per-step scenario catalogue item 6 already owes is the natural place for it.

### R13 [should-fix]
**Certificate**: C4 — CONTRADICTED
**Location**: `plan/track-11.md` item 6 (the `embedded` clause) and the matching acceptance bullet; `.github/workflows/maven-pipeline.yml:173`, `:185`, `:334`, `:410`

**Issue**: Item 6 rests `embedded`'s on arm on CI, and its stated reason for trusting CI is false.

The item says the on arm is "true of its **on** arm (the Linux and macOS platform jobs run `./mvnw clean package -B` with no `-Dmaven.test.failure.ignore=true`, and `embedded` carries the feature suite)". Every platform leg passes that flag: Linux through `matrix.mvn_opts` at `:173` and `:185`, Windows inline at `:334`, macOS inline at `:410`. The track file's own `### Clarifications` has it right — "CI runs with `-Dmaven.test.failure.ignore=true`" — and the two statements contradict each other in the same file.

The consequence is that a green CI leg carries no information about `embedded`. The worked example is in the same Clarifications bullet: at `b35ac67d2f` the whole reactor passed *while* `embedded`'s `EmbeddedGraphFeatureTest` reported 1931 / 41 / 14. Green means the build ran, not that the suite passed.

Two more gaps in the same clause, both cheap to close:

- **No number.** Item 6 says to read `embedded`'s on arm and never says what it must be. `core`'s criterion is 1930 / 0 / 14; `embedded` runs 1931 total (the same scenarios plus one JUnit method), so its analogue is 1931 / 0 / 14. Unstated, the clause is unfalsifiable.
- **An unsatisfiable timing condition.** "A CI run that completed on this track's final tree" cannot be met, for the reason Track 9 worked out and wrote into its own R8 rule: the completion episode is itself a commit, so no run is ever at the final tree. Track 9's answer was the ancestor-plus-empty-path-diff form, and it applies unchanged here.

The residual risk is bounded — the translator-off arm is native TinkerPop and this track's code cannot reach it, and `core`'s two-arm A/B is measured in-track — so this is should-fix, not blocker. What makes it worth fixing is that the clause currently reads as a measurement and is not one.

**Proposed fix**: Correct the parenthetical to say CI runs with `-Dmaven.test.failure.ignore=true`, so a green leg is not a pass and the counts must be read out of the check annotations or the uploaded surefire reports. State the number (1931 / 0 / 14) and the read procedure. Replace "completed on this track's final tree" with Track 9's satisfiable form: the run's SHA is an ancestor of HEAD and `git log <sha>..HEAD -- core embedded jmh-ldbc` is empty. And note that PR #1038 is a draft, so the legs may report `skipping` — if they do, the local two-command re-measurement in `### Clarifications` stops being optional.

### R14 [should-fix]
**Supersedes**: R5 (iteration 1) — its second half is discharged by the harness's `fold` assertion; its first half is not, and the out-of-band landing adds two new problems.
**Certificate**: C5 — CONTRADICTED
**Location**: `plan/track-11.md` item 7; `git ls-remote --heads origin`

**Issue**: Item 7's deliverable exists on exactly one machine's disk.

`git ls-remote --heads origin` returns no `t11-item7-jmh` and no `ytdb-558-t11-item8-probe`. Both branches carry `branch.<name>.merge` config pointing at `origin/`, so `%(upstream)` reports a remote that does not exist and local tooling will describe them as tracked. `git ls-tree -r HEAD jmh-ldbc` returns no Gremlin file, so the three files are not on this branch either. The workflow tracks `_workflow/` in the PR precisely so "a local-disk loss never destroys planning work" (`CLAUDE.md` § Workflow Artifacts); item 7's ~three files of production and test code sit outside that guarantee, and so does item 8's evidence commit `158a87871c`.

Two more gaps follow from landing the work out of band:

- **Which commit lands is unstated.** Item 7 names `06caa2f962` (three files, the four named shapes). Item 8's provenance names `b1fc04a030`, the follow-on that "adds eight translating shapes and five declining ones" including the `is1FullProfile` whose decline assertion item 8 says must flip. The track never says whether one or both are cherry-picked, and the two have different footprints and different obligations.
- **The re-run invocation is not pinned.** Item 7 records `./mvnw -pl jmh-ldbc -o test` and its criterion "closes only after item 3 lands — re-run `-Dtest=LdbcGremlinShapeTranslationTest` then". Under `-pl jmh-ldbc`, `jmh-ldbc/pom.xml:20-23` resolves `youtrackdb-core` at `${project.version}` from the local repository, so the re-run measures the installed jar, not item 3's code. The same file pins install-first discipline for `embedded` in `### Clarifications` and not for `jmh-ldbc`.

R5's second half is genuinely discharged and worth recording: the harness's `fold` shape is a Track-11-only recogniser, so a stale core jar makes the on-arm assertion **fail loudly** instead of passing on a Track 2 boundary step. R8's placement suggestion is discharged too — both new `src/main` classes sit in `benchmarks.ldbc` directly, inside the single-level JaCoCo exclusion.

**Proposed fix**: Push both branches, or state in item 7 that the deliverable is a cherry-pick and name the exact SHAs in the order they land. Say which of the two commits is in scope. Add the install-first sequence to `### Clarifications` beside the `embedded` one, or use `./mvnw -pl core,jmh-ldbc test -Dtest=LdbcGremlinShapeTranslationTest` so both modules share a reactor.

### R15 [should-fix]
**Certificate**: C6 — VALIDATED
**Location**: `plan/track-11.md` item 8 (its `## Tests` clause); `plan/track-9.md` `## Surprises & Discoveries` (the `rebuildTraversal` label-drop entry); `plan/track-11/item8-label-probe.md:73-84`, `:109`

**Issue**: Item 8's fix makes the translated path answer a shape *correctly* that the native path answers *wrongly*, and item 8's tests are specified as native comparisons.

The probe recorded the native defect itself. `YTDBGraphStepStrategy.rebuildTraversal`'s `else` branch — a `HasStep` that does not directly follow a `GraphStep` — inserts a `YTDBHasLabelStep` and copies no labels, so `g.V().out("knows").hasLabel("Person").as("a").select("a")` returns `[]` on the **native** pipeline where the oracle returns two vertices. Track 9 carried it into its `## Surprises & Discoveries` as a pre-existing `develop` defect with a Phase-4 carry obligation, and the probe's own closing line says it "should not be folded into item 8".

Nobody checked the interaction in the other direction. Item 8's fix is one `bindStepLabels` call in `HasStepRecogniser.recognize`, which fires for every `HasStep` — including the mid-traversal one in that shape. After the fix the translated arm binds the label and returns the two vertices; the off arm still returns `[]`. That is a translator-on / translator-off divergence on a measured shape, against a branch invariant that says the two arms agree, and item 6's amendment newly makes such a check mandatory for "every shape this track touches".

The probe's own measurements do not cover it. It reports row parity for S2, S3, S4 and both IS1 variants — all of which put the `HasStep` directly after the `GraphStep`, where `rebuildTraversal` takes the label-copying fold branch (`:131`, measured). The `else` branch needs an intervening hop, and no post-fix spelling with one was measured.

Likelihood is high for any implementer who writes a label test with a hop in it; impact is a confusing red that looks like item 8's regression and is not.

**Proposed fix**: One paragraph in item 8. State that a `hasLabel` carrying a label behind an intervening hop diverges after this fix *because native is wrong there*, cite Track 9's `## Surprises & Discoveries` entry and the probe's `:73-84`, and rule that item 8's equivalence tests either avoid that spelling or assert against the oracle with the divergence recorded rather than against native. Item 6's per-shape equivalence sweep needs the same carve-out, or it produces one unexplainable failure.

### R16 [should-fix]
**Supersedes**: R3 (iteration 1) — the mechanism is now named and one of its two carriers is gone, while item 6's criterion moved from comparative to absolute, which is the direction that makes the residue matter more.
**Certificate**: C7 — UNVALIDATED
**Location**: `plan/track-11.md` item 6 and its acceptance bullet; `plan/track-9.md` `## Episodes` § Step 1

**Issue**: Item 6's gate is now an exact number with no tolerance, and the branch has a recorded, unclosed source of run-to-run variation in exactly the quantity it counts.

Track 9's step 1 episode states it plainly: "TinkerPop's strategy sort is not order-stable for unconstrained pairs, and `RepeatUnrollStrategy` declares no ordering constraints at all, so whether `AdjacentToIncidentStrategy` rewrites the last hop of an unrolled count form — and with it whether that shape is a translation candidate — varies by fork composition." It closes: "It is a lead, not a closed question: it accounts for variation between forks but has not been shown to account for the ~1200-scenario correlation specifically."

Two things changed in this track's favour and one against. In its favour: Track 9's step 8 moved the veto marker off the strategy list onto `getSideEffects()`, so the branch no longer forces a topological re-sort of its own, and Track 9 measured 1930 / 0 / 14 on both arms in a single fork at its final tree. Against: iteration 1 read a comparative gate ("no regression against a baseline"), where a noisy scenario shows as a small delta; the pre-flight amendment made the gate absolute, where one flipped scenario is a hard failure. Combined with R12's missing disposition rule, a single noisy red has no exit at all.

The unresolved half is TinkerPop's, not the branch's, so it cannot be fixed here — only measured.

**Proposed fix**: One sentence in item 6. Before reading the gate at this track's tip, run the same command twice at the **same** SHA. Two identical results establish the gate is deterministic on this tree and the number can be read at face value; a difference is the noise floor and is recorded as such before any scenario is called a defect. The targeted invocation is ~20 s, so this costs one extra run. Item 6 should also say which side a discrepancy falls on: a scenario that flips between two same-SHA runs goes to the exclusion list with its flip recorded, not to the defect bucket.

### R17 [should-fix]
**Supersedes**: R6 (iteration 1) — re-verified unchanged at HEAD after four track-file amendments.
**Certificate**: C8 — VALIDATED
**Location**: `plan/track-11.md` item 7; `implementation-plan.md` `### Non-Goals`

**Issue**: Item 7 deliberately benchmarks a shape it already knows is a live translator-on regression, and still names no destination for it.

The item states the mechanism itself: a RID-bearing walk sets `cacheEligible=false` (`GremlinToMatchTranslator:87`), so `g.V(rid)` compiles an uncached MATCH plan on every execution where the native path ran no query, "and it remains the one shape where translator-on can be strictly slower than translator-off; Track 10's promotion fix landed, the per-call recompile did not."

Track 9's item 4 settled the general form: every disposition names one of fixed here, shape declined here, or a named follow-up, and "'deferred' with no owner is not a disposition". Item 7 does none of the three. A grep of `implementation-plan.md` for `cacheEligible` / `RID-bearing` / `per-call recompile` returns one hit, and it is Track 5's own R3 note, not a `### Non-Goals` entry.

Likelihood is certain rather than probable — the code path merges with the branch and the benchmark exists to display it. Impact is bounded to a by-id lookup, which is why this is should-fix.

**Proposed fix**: Item 7 records a destination. The cheapest honest one is a named follow-up: a YouTrack issue for RID-bearing plan-cache eligibility, referenced from item 7 and from the plan's `### Non-Goals`, carrying the harness's measured on-vs-off figure as its evidence. Declining RID-bearing walks is the other exit Track 9's rule allows, but that is an orchestrator scope call, not something item 7 settles by measuring and moving on.

### R18 [should-fix]
**Supersedes**: R7 (iteration 1) — re-read at HEAD; item 5's clone clause is unchanged.
**Certificate**: C9 — DIFFICULT
**Location**: `plan/track-11.md` item 5 and the matching acceptance bullet; `## Context and Orientation` (the shared-op-instance paragraph)

**Issue**: `fold` and `tail` are the two buffered ops and share one hazard, and item 5 specifies re-arm coverage for both and clone coverage for `fold` alone.

The hazard is this file's own: `AbstractStep.clone()` copies `shaping` by reference while `resetLifecycleForClone()` deliberately leaves it alone, so two clones share the same `ListShapingOp` instances, and `applyListShaping` calls `op.apply(...)` afresh on each of three open routes. `tail` is the harder of the two to catch, because a shared `ArrayDeque` across two clones yields a window of the right size holding the wrong elements — the failure a size or row-count assertion cannot see, where a shared `fold` buffer produces an obviously duplicated list.

This is a one-test gap rather than a design problem: item 5 already names `MultiPlanMatchStepTest`'s clone-isolation idiom, and the acceptance bullet on order scope already establishes that element-for-element comparison is legal on an `order().by(...)`-prefixed input, which is the fixture the `tail` assertion needs.

**Proposed fix**: Extend item 5's clone clause to `tail(n)` beside `fold()`, and require the `tail` assertion to be element-for-element on an ordered input rather than on size. One sentence and one test method.

### R19 [suggestion]
**Supersedes**: R9 (iteration 1) — the plan's re-pricing more than doubled the estimate, which sharpens the same point rather than answering it.
**Certificate**: C10 — DIFFICULT
**Location**: `implementation-plan.md` Track 11 Scope line (re-priced 2026-08-04); `plan/track-9.md` `## Decision Log` DR-S1

**Issue**: The plan now prices this track at "~35–45 files", against a soft split candidate bound of ~20–25, and records that item 10's share "is the least certain part" because its five measured counts all need re-deriving. DR-S1's remedy for an over-threshold track was a split. This is the last track, so there is no successor to split into, and splitting backwards reorders work Track 9 already depends on being behind it.

The Phase C review-burden check reads a different quantity again — `--shortstat` over the cumulative diff, excluding only generated sources, so `docs/adr/**` and tests both count against roughly 4,000 lines. DR-S1 recorded the observed magnitudes: Track 8 at 5,814 code insertions, Track 10 at 5,168 non-generated over 36 files plus 2,613 of `_workflow/` prose, and Track 10's reviews directory alone at 3,154 lines. This track's Phase A panel adds its own reviews to that sum before a line of code is written.

Nothing here says the track is mis-sized. The point is that the one lever DR-S1 reached for is unavailable, and the plan already says the adversarial pass owns the call.

**Proposed fix**: Decide it at decomposition rather than at Phase C. Order the roster so items 8, 9 and 10 — the three absorbed-backlog items, each independently droppable — sit last and are separable, and record in `## Artifacts and Notes` that the response to a tripped burden check is a written justification with the sum's composition stated (code half against `_workflow/` half), not a split.

### R20 [suggestion]
**Certificate**: C11 — VALIDATED
**Location**: `plan/track-9.md` `## Surprises & Discoveries` (three carry-obligation entries); `implementation-plan.md` Track 9 entry, § Strategy refresh

**Issue**: Five defects are recorded only in files that get deleted, and the last checkpoint before the deletion is this track's completion.

Track 9 left five unowned defects with an explicit carry obligation into `design-final.md` or an issue: three in the transaction result cache, the `rebuildTraversal` native label drop, and the `castComparableNumber` `Long`-to-`BigDecimal` lead. Each entry says why it matters — "`_workflow/` is deleted in the Phase-4 cleanup commit, so a defect that lives only in this file and a probe artifact disappears from the repository at merge."

Ownership is assigned: the plan's Track 9 entry says "Track 9's five unowned-defect carry obligations are Phase 4's to move into `design-final.md`". So this is insurance, not a gap. What makes it worth a line is that the obligation is recorded in two places that both vanish (`plan/track-9.md` and the plan's own `_workflow/` entry), the discovering artifact for one of them (`plan/track-11/item8-label-probe.md`) vanishes with them, and R15 shows this track's item 8 interacts with one of the five.

**Proposed fix**: One line in this track's `## Artifacts and Notes` listing the five by name with their `plan/track-9.md` anchors, and a completion-checklist item that the carry has landed or an issue exists. Track 11's completion is the last gate before Phase 4 reads the branch.

## Evidence base

#### C1 Exposure: a captured `ListShapingOp` has no in-loop gate, while the two clauses beside it do — residual risk HIGH
- **Track claim**: items 2 and 3 gate the terminators by a per-recogniser last-step check. Item 2 enumerates its decline branches as exactly two — `!isListFold()` and `!supportsListShaping()` — with no position check. Item 3 spends four words on it: "Mid-traversal use declines (D3)."
- **Critical path trace**:
  1. `GremlinStepWalker.dispatchAll` @ `GremlinStepWalker.java:442` — post-union gate: `ctx.hasUnionCarrier() && !POST_UNION_RECOGNISERS.contains(recogniser)` → decline.
  2. `:449` — **single-plan cardinality gate**: `capturedCardinalityClause(ctx) && !POST_CARDINALITY_RECOGNISERS.contains(recogniser)` → decline. `capturedCardinalityClause` @ `:534` reads `ctx.skip() != null || ctx.limit() != null || ctx.returnDistinct()`. The allow-list @ `:569` is the three pure projections.
  3. Neither gate reads `ctx.shaping().listShapingOps()`. After a terminator appends an op on the single-plan path, dispatch continues with no captured-state gate at all.
  4. `AbstractMatchPlanStep.openShapedPayloads` @ `:372-375` — the projection source, **then** `applyListShaping` @ `:386-397`. `SKIP` / `LIMIT` / `DISTINCT` / `ORDER BY` land in the compiled statement, so they act on rows strictly before any op sees a payload.
- **Blast radius**: `g.V().values("name").fold().limit(2)` — native folds then keeps the one list; translated emits `LIMIT 2` rows, then folds a two-element list. Same family: `.fold().order()`, `.unfold().dedup()`, `.fold().count()`. No error, no decline, kill-switch on by default (`GlobalConfiguration.java:1019-1028`, `Boolean.class, true`).
- **Existing safeguards**: the two in-loop gates above (neither covers shaping); the fail-closed unregistered-class decline; the empty-`ops` structural bypass @ `AbstractMatchPlanStep:388-390`, which keeps every non-terminator traversal off the path entirely.
- **Residual risk**: HIGH before the gate exists, LOW after — and the pattern to copy is now three declarations away. Iteration 1's R2 reported "no in-repo idiom to copy". That is no longer true: `capturedCardinalityClause` + `POST_CARDINALITY_RECOGNISERS` is a captured-state in-loop fail-closed gate whose javadoc @ `:500-533` argues membership the way a shaping twin would. → **R10**

#### C2 Exposure: the `selectsPositionally` build gate checks that an answer exists, not that it is right — residual risk HIGH
- **Track claim**: item 4 — "add the four terminator recognisers to `GremlinStepWalker.POST_UNION_RECOGNISERS`", and separately "**`fold` cannot be a bare post-union suffix at all**", and "a `tail` recogniser must therefore answer **`true`**".
- **Critical path trace**:
  1. `GremlinStepWalkerTest.everyPostUnionRecogniserStatesItsOwnPositionalAnswer` @ `:1343-1355` — the build-failing test. Its assertion is `getDeclaredMethods()` `.anyMatch(m -> m.getName().equals("selectsPositionally"))`. It checks **declaration**, never the returned value.
  2. `GremlinStepWalker.postUnionSuffixTranslatable` @ `:619-641` — for a member answering `false`, the suffix is translatable with nothing following it.
  3. `MultiPlanMatchStep` concatenates child streams in child order; native `union(...)` interleaves the arms. A `fold` over the concatenation therefore produces a `List` whose element order is translator-specific, and the branch's equivalence standard compares one-element multisets whose member is a `List` — `List.equals` is order-sensitive.
- **Blast radius**: `union(__.out(), __.in()).fold()` returns a differently-ordered list with the translator on than off, translated rather than declined. Item 4 states the conclusion ("takes `fold` off it") but its instruction is to add all four members, and the only mechanical enforcement accepts `false`.
- **Existing safeguards**: `RangeGlobalStepRecogniser`'s class javadoc carries the worked argument for the identical case, so the reasoning is in the repo; the declaration test forces an implementer to type the method. Neither forces the right answer, and no test asserts a specific value for a specific member.
- **Residual risk**: HIGH. Two of the four members need a non-default answer (`tail` for position, `fold` for list-order observability), item 4 names only one of them as `true`, and the enforcement cannot tell them apart. → **R11**

#### C3 Testability: item 6's absolute gate has a detection rule and no disposition rule — DIFFICULT
- **Coverage target**: not a line-coverage question. The gate is 1930 / 0 / 14 on both `core` arms plus a translator-on/off equivalence check on "every shape this track touches".
- **Difficulty assessment**: three compounding problems. (a) Registering four terminators newly claims shapes that decline today, so scenarios move in both directions and the file names `groupCount().unfold()` / `valueMap().unfold()` as ordinary suite idioms. (b) The gate is now **absolute** rather than comparative, so any newly-claimed shape that breaks a scenario must be resolved in-track. (c) There is no rule for resolving it: `grep -n "ESCALATE\|escalat\|disposition\|waiver\|follow-up\|Non-Goals" plan/track-11.md` returns one line, DR-T4, about review iterations. The `develop`-reproducing exclusion list cannot cover this track's own new claims.
- **Existing test infrastructure**: `YTDBGraphFeatureTest` under `core`'s `gremlin-feature-compliance-tests`; the ~20 s targeted loop pinned in `### Clarifications`; `plan/track-9.md` item 4's three rules (in-track bound, "every disposition names a destination", "a translator-caused silent-wrong-multiset defect cannot simply be deferred") as the template to copy.
- **Feasibility**: DIFFICULT — the mechanics are pinned and the discipline around them is not. Track 9 could route a deferral to Track 11; nothing follows Track 11, and the second obligation ("every shape this track touches") is unbounded-cardinality with no enumeration. → **R12**

#### C4 Assumption: CI's green legs verify `embedded`'s translator-on arm — CONTRADICTED
- **Track claim**: item 6 — CI covers `embedded`'s on arm because "the Linux and macOS platform jobs run `./mvnw clean package -B` with no `-Dmaven.test.failure.ignore=true`".
- **Evidence search**: `grep -n "mvn_opts\|mvnw\|matrix" .github/workflows/maven-pipeline.yml`; Read of the three build steps. Grep only, no symbol search.
- **Code evidence**: `:200` runs `./mvnw … clean package -B ${{ matrix.mvn_opts }}` and both Linux matrix entries set `mvn_opts` containing `-Dmaven.test.failure.ignore=true` (`:173`, `:185`). Windows `:334` and macOS `:410` pass the flag inline. The track's own `### Clarifications` states the correct fact ("CI runs with `-Dmaven.test.failure.ignore=true`") and records the worked example: at `b35ac67d2f` the reactor passed while `embedded` reported 1931 / 41 / 14.
- **Verdict**: CONTRADICTED
- **Detail**: a green leg means the build completed, not that the suite passed, so the clause's stated mechanism gives no signal. Item 6 additionally names no target number for `embedded` and conditions on "a CI run that completed on this track's final tree", which is unsatisfiable for the reason Track 9's R8 rule already worked out. → **R13**

#### C5 Assumption: item 7's deliverable is recoverable and its re-run measures this track's code — CONTRADICTED
- **Track claim**: item 7 — "Implemented out of band on branch `t11-item7-jmh` (commit `06caa2f962`, based on Track 9's `ffb57fe5cf`) — not merged, not pushed. Three files in `jmh-ldbc` … It **executes**: `./mvnw -pl jmh-ldbc -o test` gives 43 tests."
- **Evidence search**: `git ls-remote --heads origin | grep -E 't11|item8'`; `git for-each-ref refs/heads/t11-item7-jmh`; `git ls-tree -r --name-only HEAD jmh-ldbc`; `git ls-tree -r --name-only t11-item7-jmh jmh-ldbc`; `git merge-base --is-ancestor ffb57fe5cf HEAD`; `git show --stat` on both commits; Read of `jmh-ldbc/pom.xml:20-23`.
- **Code evidence**: `ls-remote` returns neither `t11-item7-jmh` nor `ytdb-558-t11-item8-probe`, while `%(upstream)` reports `refs/remotes/origin/t11-item7-jmh` — configured, never pushed, and `git rev-parse origin/t11-item7-jmh` fails. HEAD's `jmh-ldbc` tree holds no Gremlin file; the branch holds all three, in `benchmarks/ldbc/` directly (inside the single-level JaCoCo exclusion, so iteration 1's R8 is discharged). `ffb57fe5cf` is an ancestor of HEAD, so a cherry-pick is well-founded. `b1fc04a030` is a second commit adding "eight translating shapes and five declining ones", including the `is1FullProfile` item 8 says must flip — the track names `06caa2f962` in item 7 and `b1fc04a030` in item 8 without saying which lands.
- **Verdict**: CONTRADICTED — recoverability fails, and the `-pl jmh-ldbc` re-run resolves `youtrackdb-core` from the local repository (`jmh-ldbc/pom.xml:20-23`), so item 7's "closes only after item 3 lands" re-run measures the installed jar unless install-first is pinned as it is for `embedded`.
- **Detail**: the vacuous-pass half of iteration 1's R5 is genuinely closed — the harness asserts on a `fold` shape no pre-Track-11 jar can translate, so a stale classpath fails loudly. → **R14**

#### C6 Assumption: item 8's fix leaves the two kill-switch arms in agreement — VALIDATED that they will diverge
- **Track claim**: item 8's tests — "`outE(L).as(e)…inV()` then `select(e)` **against native on both arms**"; and the probe's "row parity against native still holding on every one of them".
- **Evidence search**: Read of `plan/track-11/item8-label-probe.md` (§ The four spellings, § Adjacent defect, § closing); Read of `plan/track-9.md` `## Surprises & Discoveries` label-drop entry; `grep -rn "bindStepLabels" core/src/main/java`.
- **Code evidence**: `bindStepLabels` has exactly two production callers today — `StartStepRecogniser:141` and `GremlinPatternAssembler:75` — and none in `HasStepRecogniser`, so item 8 adds a third that fires on every `HasStep`. The probe's four measured spellings (`:19-32`) all put the `HasStep` directly after the `GraphStep`, where `rebuildTraversal` takes the label-copying fold branch (`YTDBGraphStepStrategy.java:131`). The `else` branch — a `HasStep` behind an intervening hop — drops labels, and the probe measured `g.V().out("knows").hasLabel("Person").as("a").select("a")` returning `[]` natively against a two-vertex oracle (`:73-84`). No post-fix spelling with an intervening hop was measured.
- **Verdict**: VALIDATED — the divergence follows from two measured facts rather than from inference.
- **Detail**: the translated arm becomes correct while the native arm stays wrong, so the branch's translator-on-equals-translator-off invariant breaks on an improvement. The probe's closing line ("should not be folded into item 8") settles ownership of the native defect and says nothing about the interaction. → **R15**

#### C7 Assumption: the suite's scenario count is deterministic across runs at one SHA — UNVALIDATED
- **Track claim**: item 6 — "1930 scenarios, 0 failures, 14 skipped", an absolute criterion read from one run per arm.
- **Evidence search**: Read of `plan/track-9.md` `## Episodes` § Step 1 and § Step 8, and its `## Decision Log`; `grep -n "1200\|nondeterminis\|RepeatDeclineStrategy" plan/track-9.md`.
- **Code evidence**: Step 1's episode records that "TinkerPop's strategy sort is not order-stable for unconstrained pairs", that `RepeatUnrollStrategy` declares no ordering constraints, and that whether `AdjacentToIncidentStrategy` rewrites a hop "varies by fork composition" — explicitly "a lead, not a closed question … has not been shown to account for the ~1200-scenario correlation specifically". Step 8 removed the branch's own contribution by moving the veto marker to `getSideEffects()`, so the off arm returns to `develop` byte-identical and no re-sort is forced. Track 9 then measured 1930 / 0 / 14 on both arms in a single fork.
- **Verdict**: UNVALIDATED — one clean measurement is consistent with determinism and does not establish it, and the named mechanism is TinkerPop's rather than the branch's.
- **Detail**: the criterion moved from comparative to absolute between iterations, which is the direction that turns a one-scenario flip from a small delta into a gate failure. → **R16**

#### C8 Assumption: `g.V(rid)` translator-on can be strictly slower, and the regression is owned — VALIDATED / unowned
- **Track claim**: item 7 — "a RID-bearing walk sets `cacheEligible=false` (`GremlinToMatchTranslator:87`), so it compiles an uncached MATCH plan where the native path ran no query, and it remains the one shape where translator-on can be strictly slower than translator-off".
- **Evidence search**: `grep -n "cacheEligible\|RID-bearing\|per-call recompile" implementation-plan.md`; `grep -n "ESCALATE\|disposition\|follow-up\|Non-Goals" plan/track-11.md`; Read of `plan/track-9.md` `## Plan of Work` item 4's disposition rules.
- **Code evidence**: the plan's only hit is `:523`, Track 5's own note about RID-bearing shapes bypassing the cache — not a `### Non-Goals` entry. `plan/track-11.md` returns one hit for the disposition vocabulary and it is DR-T4, about review iterations. Track 9's item 4 states the rule the track file does not carry: "One of: **fixed here**, **shape declined here** … or **a named follow-up** … 'Deferred' with no owner is not a disposition."
- **Verdict**: VALIDATED that the regression is real; the ownership gap is unchanged from iteration 1 across four amendments.
- **Detail**: measured in scope, out of scope for repair everywhere on the branch, and no track follows. → **R17**

#### C9 Testability: item 5's clone coverage of the two buffered ops — DIFFICULT
- **Coverage target**: 85% line / 70% branch, plus the behavioural assertions the acceptance bullets name
- **Difficulty assessment**: the defect needs two concurrently-iterated clones to surface and its symptom is a plausible wrong result rather than an exception. For `tail(n)` a shared deque yields the right window size with the wrong contents, which a size or row-count assertion cannot detect; for `fold` a shared buffer produces a visibly doubled list. The harder of the two is the one item 5 omits. The acceptance bullet on order scope restricts element-for-element comparison to ordered inputs, so the fixture needs an `order().by(...)` prefix for the assertion to be legal at all.
- **Existing test infrastructure**: `YTDBMatchPlanStepTest` drives `withListShapingOps` through stateful fixture ops across the re-arm routes; `MultiPlanMatchStepTest` carries the clone-isolation idiom item 5 already names; `OrderGlobalStepRecogniser` translates `order().by(...)` into a MATCH `ORDER BY`, so the ordered fixture is available.
- **Feasibility**: DIFFICULT — reachable with what is already in the repo, not by the tests item 5 currently specifies. → **R18**

#### C10 Testability / feasibility: track sizing with no split target — DIFFICULT
- **Coverage target**: not line coverage — the Phase C review-burden check reads `--shortstat` over the cumulative diff, excluding only generated sources, against roughly 4,000 lines.
- **Difficulty assessment**: the plan's own re-pricing puts this track at ~35–45 files against a ~20–25 split-candidate bound, and states that item 10's share is the least certain part because all five of its measured counts need re-deriving. `docs/adr/**` counts toward the burden check, and this track's Phase A panel adds its reviews before implementation starts.
- **Existing test infrastructure**: not applicable — this is a process gate. DR-S1 records the comparable magnitudes (Track 8 at 5,814 insertions; Track 10 at 5,168 non-generated over 36 files plus 2,613 of prose; Track 10's reviews directory alone at 3,154 lines).
- **Feasibility**: DIFFICULT — the track can be executed; the gate has no split remedy, since the only successor position is Phase 4. → **R19**

#### C11 Assumption: the five unowned defects reach a durable surface — VALIDATED, with a single point of failure
- **Track claim**: implicit — Track 11 is the last track, and its completion is the last checkpoint before Phase 4.
- **Evidence search**: `grep -n "unowned\|carry obligation\|design-final" plan/track-9.md`; Read of the plan's Track 9 entry § Strategy refresh; `git ls-files docs/adr/.../plan/track-11/`.
- **Code evidence**: three `plan/track-9.md` `## Surprises & Discoveries` entries carry explicit "CARRY THIS INTO `design-final.md`" markers covering five defects, each naming the reason ("`_workflow/` is deleted in the Phase-4 cleanup commit"). The plan's Track 9 entry assigns them: "Track 9's five unowned-defect carry obligations are Phase 4's to move into `design-final.md`." The discovering artifact for one of them, `plan/track-11/item8-label-probe.md`, is tracked but lives under `_workflow/` and is deleted with it.
- **Verdict**: VALIDATED — the obligation exists and is owned.
- **Detail**: every record of it is inside the directory the cleanup commit removes, and this track's item 8 touches one of the five (R15). A completion-checklist line here is cheap insurance rather than a missing owner. → **R20**
