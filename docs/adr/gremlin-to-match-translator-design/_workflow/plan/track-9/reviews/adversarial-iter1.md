<!-- MANIFEST
findings: 5   severity: {blocker: 1, should-fix: 3, suggestion: 1}
index:
  - {id: A1, sev: blocker,    loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:125", anchor: "### A1 ", cert: CH1, basis: "item 4's fix bucket has no fix-vs-defer rule, no size marker, and no escalation valve; Track 10's identical bucket became 483 repairs, and the plan file still claims a Scope-line 'unsized' marker the split dropped"}
  - {id: A2, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:143", anchor: "### A2 ", cert: VS1, basis: "the twelve deferred defects have no translator-off attribution run and no deferral destination; with the kill-switch true by default, every translator-caused one ships at merge as a live equal-multiset violation with no downstream owner, and the decline-the-shape exit is never listed"}
  - {id: A3, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:112", anchor: "### A3 ", cert: VS2, basis: "option 3's 'touches SQL MATCH only by proximity' holds in only one of the two possible loop orderings; in the other, the mandated AND-compose doubles every filtered SQL MATCH alias's WHERE (w AND w) at the shared :2064 site"}
  - {id: A4, sev: should-fix, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:29", anchor: "### A4 ", cert: AT5, basis: "R17's residual stands: lines 29 and 154 state the re-trigger rule in the unqualified any-commit form the criterion at :144 qualifies as 'the clause that makes the rule terminate', and the Outcomes closure bullet omits R17 from the applied-residuals list"}
  - {id: A5, sev: suggestion, loc: "docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:137", anchor: "### A5 ", cert: VS4, basis: "union-child and captured-fragment shapes are mechanically covered by an option-2 fix but nothing witnesses them; Track 8's own review found buildResult's multi-plan path silently discarding accepted work once already"}
evidence_base: {section: "## Evidence base", certs: 12, matches: 5}
cert_index:
  - {id: CH1, verdict: NO, anchor: "#### CH1 "}
  - {id: CH2, verdict: YES, anchor: "#### CH2 "}
  - {id: CH3, verdict: WEAK, anchor: "#### CH3 "}
  - {id: AT1, verdict: HOLDS, anchor: "#### AT1 "}
  - {id: AT2, verdict: HOLDS, anchor: "#### AT2 "}
  - {id: AT3, verdict: HOLDS, anchor: "#### AT3 "}
  - {id: AT4, verdict: BREAKS, anchor: "#### AT4 "}
  - {id: AT5, verdict: BREAKS, anchor: "#### AT5 "}
  - {id: VS1, verdict: CONSTRUCTIBLE, anchor: "#### VS1 "}
  - {id: VS2, verdict: CONSTRUCTIBLE, anchor: "#### VS2 "}
  - {id: VS3, verdict: INFEASIBLE, anchor: "#### VS3 "}
  - {id: VS4, verdict: THEORETICAL, anchor: "#### VS4 "}
flags: [CONTRACT_OK]
-->

# Track 9 adversarial review — track realization, iteration 1

The cross-track handoff is real and the fix mechanism is sound: Track 10's dispositions artifact exists and says exactly what this track claims (21 rows, nine with the dropped-filter signature in the named classes, no `gremlin-feature-compliance-tests` row), the six `plan/track-11.md` lines in the escalation must-amend list all exist with the content attributed to them, and an option-2 fix in `GremlinStepWalker.buildResult` mechanically reaches union children and captured fragments because both commit through the same single-plan path. The blocker is item 4. Its "fix what belongs to this track" bucket has no decision rule, no size marker, and no escalation valve, in the track that exists because the last two tracks blew the review-burden threshold — and Track 10's identical bucket became 483 repaired failures. Behind it sit three should-fixes: the twelve deferred defects would ship at merge under a default-ON kill-switch with no attribution run, no deferral destination, and no listed decline exit; option 3's SQL-safety claim is ordering-dependent and unstated; and R17's residual contradiction survived the cap-close while the Outcomes bullet records the closure as complete.

**Tooling caveat.** PSI was unavailable per the spawn (`steroid_list_projects` reports the IDE open on project `design.md`; `steroid_execute_code` is a known cold-start timeout on this repository — no attempt was spent). Every symbol result below is `grep -n` / `find` plus an end-to-end read of the declaring method or file. The negatives that carry weight — "union children re-walk through the single-plan `buildResult`", "captured fragments commit through `ctx.putAliasFilter`", "no plan document names a destination for item-4 deferrals" — are bounded by full reads of `UnionForkHost`, `UnionStepRecogniser`, `ConnectiveStepSupport`, `SubTraversalPredicateAdapter`, and the plan's Non-Goals section, not established by PSI. A reflective or cross-module reader would not appear.

## Findings

### A1 [blocker]
**Certificate**: CH1 (challenge to DR-S1's realization at item 4), supported by AT4
**Target**: Decision DR-S1 (the split) as realized by `## Plan of Work` item 4 and the plan entry's Scope line
**Challenge**: The split moved the sizing problem into item 4 instead of solving it. Item 4 says "Fix what belongs to this track; record a disposition for everything else" with no rule for what belongs, over a bucket holding 21 deferred process-compliance failures plus the post-fix Cucumber residue (42 upstream failures pre-fix, residue unknown until item 3). The twelve non-dropped-filter defects sit in translator result-shaping and ordering code (`ClassCast String → Property`, group cardinality, order divergence) — if they "belong", the fix set spans the Track 6 shaping surface and the track becomes the third consecutive over-threshold PR; if they defer, nothing says so. Item 1 got an ESCALATE trigger with a budget for exactly this shape of unbounded work; item 4 got nothing. The plan file makes the gap worse: Track 8's strategy-refresh paragraph (`implementation-plan.md:615-617`) still asserts the "Cucumber triage bucket its own Scope line marks unsized until the first run", but the post-split Track 9 entry (`:678-692`) marks only the item-1 diagnosis and the `embedded` A/B unsized — the triage-bucket marker was dropped in the split rewrite, so the ~8–14 file figure silently absorbs an unbounded item. The orchestrator decomposes immediately after this pass and cannot cut item 4 into sized steps as written.
**Evidence**: `track-9.md:125` (item 4, no bound, no rule); `implementation-plan.md:678-692` (Scope line, no triage sizing) against `:615-617` (the marker the split dropped); Track 10 precedent — its equivalent triage item produced 483 repairs and the branch's largest track (`implementation-plan.md:721`); `plan/track-10/core-compliance-failure-dispositions.md:54-63` (the twelve defects and their subsystems).
**Proposed fix**: Three amendments before decomposition. (1) Give item 4 an explicit fix-vs-defer rule — e.g. in-track fixes are limited to the dropped-filter family (expected to pass via item 2) plus defects whose diagnosis lands on files already in this track's scope; everything else is dispositioned, not fixed. (2) Restore the unsized marker: the plan entry's Scope line states the triage bucket is unsized until item 3's residue exists. (3) Give item 4 an ESCALATE trigger symmetric with item 1's — if the residue triage exceeds a stated budget, the surplus moves to a named follow-up rather than growing the track.

### A2 [should-fix]
**Certificate**: VS1 (violation scenario, constructible), supported by AT4
**Target**: Invariant "translator-on and translator-off produce equal result multisets for every RECOGNIZED shape" (plan Architecture Notes) against item 4's deferral path and the acceptance criterion at `track-9.md:143`
**Challenge**: The acceptance criteria make the nine dropped-filter failures pass and require the other twelve only to be "diagnosed and dispositioned". Three exits could make that honest — fix, decline the shape (D3 machinery, cheap, restores the invariant by construction), or flip the kill-switch default before merge — and the track lists none of them. `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` is true by default (`GlobalConfiguration.java:1019`, "True by default"), Track 11 owns terminators and reads this track's baseline as the set of expected failures, and no later track exists. So every translator-caused defect among the twelve ships at merge as a live invariant violation: `GroupTest` over-emission (4→5), `ElementMapTest` under-emission (4→2), and `OrderTest` divergence are silent wrong answers, not loud crashes. Worse, the track cannot currently even attribute them: the dispositions file records translator-ON runs only ("fails byte-identically at the track base" — the base default is ON), so nobody knows which of the twelve fail natively too. Item 4's own method elsewhere in this track is the A/B; item 4 drops it exactly where attribution is load-bearing.
**Evidence**: `GlobalConfiguration.java:1019-1027`; `plan/track-10/core-compliance-failure-dispositions.md:14-37` (Measurement section — no off-arm run); plan `### Non-Goals` (none of the twelve shapes is in the Phase 2 decline list — they are recognized shapes shipped by Tracks 4–6); `plan/track-11.md:87` (no-regression criterion normalizes the twelve as expected failures).
**Proposed fix**: Item 4 gains three clauses. (1) One translator-off control run of `gremlin-process-compliance-tests` at the item-3 SHA (same compile-in-the-same-run discipline), recorded in the baseline artifact, so each of the 21-plus-residue failures carries an on/off attribution. (2) Every disposition names a destination: fixed here, shape declined here, or a named follow-up (YouTrack issue or a plan Non-Goals amendment) — "deferred" with no owner is not a disposition, and Track 10's format cannot be reused verbatim because its pointers all said "Track 9". (3) A translator-caused silent-wrong-multiset defect (not a crash) must take the fix or decline exit before track completion, or the track records an explicit user-approved waiver; leaving it live under a default-ON switch is a decision the plan's invariant does not permit an implementer to make silently.

### A3 [should-fix]
**Certificate**: VS2 (violation scenario, constructible), supported by CH3
**Target**: `## Plan of Work` item 2, option 3's trade-off sentence ("a separate pattern walk touches SQL `MATCH` only by proximity", `track-9.md:112`)
**Challenge**: The safety claim is true in exactly one of the two orderings an implementer can choose at the `:2064` call site, and the bullet states neither the hazard nor the ordering. Item 2 mandates merge semantics: AND-compose with whatever the item carries, skip aliases absent from the map. On the SQL path, `aliasFilters` is non-empty (populated by `buildPatterns` from the statement's own expressions) and the pattern's items are the statement AST. If the new pattern walk runs after the existing `rebindFilters(aliasFilters)` call, each filtered SQL item ends as `w AND w` — the overwrite at `MatchExecutionPlanner.java:6019` installs the consolidated clause, then the mandated merge composes the same clause onto it. Every SQL MATCH WHERE evaluates twice per row, plan prettyPrint text drifts, and the only net is the ~32-minute `MatchStatementExecutionTest` gate. If the walk runs before, the overwrite resets the item and SQL is unaffected — safe by accident, with nothing in the track distinguishing the two. The existing loop is safe under double execution because overwrite is idempotent; the mandated merge is not.
**Evidence**: `MatchExecutionPlanner.java:2064` (shared call site), `:6012-6021` (unconditional overwrite body), `:2049-2050` (`aliasFilters` flows into scheduling on the SQL path); `track-9.md:104` (the merge mandate), `:112` (the proximity claim).
**Proposed fix**: One sentence in option 3's bullet: the second walk must be gated on the additive-path condition (`matchExpressions.isEmpty()`, which `MatchPlanInputs`' compact constructor normalises — the same fact T34 added) or explicitly ordered before the existing rebind; ungated and ordered after, it double-composes every filtered SQL MATCH alias. This keeps the enumeration honest for an implementer who departs from the option-2 default with a written reason.

### A4 [should-fix]
**Certificate**: AT5 (assumption test, breaks)
**Target**: Assumption that the six-round fix cycle left no standing contradictions; the re-trigger rule at `track-9.md:29` and `:154` against the criterion at `:144`
**Challenge**: R17's residual survived the cap-close. The criterion at `:144` states the terminating form — re-trigger on qualifying commits only, path-filtered to `core`/`embedded`, with the `docs/adr/**` exclusion named as "the clause that makes the rule terminate". The Decision Log bullet at `:29` ("Any commit that lands after a baseline run and before track completion re-triggers that run") and the recovery bullet at `:154` ("any other commit landing between the run and track completion") both state the unqualified form. Read literally, `:29` is unsatisfiable: committing the baseline artifact is itself a commit after the run, so the rule re-triggers on its own output — the same defect shape R8's iteration-3 "must equal HEAD" regression had, in the two sections an implementer reads for the rule and its recovery. The risk gate's iteration 4 marked R17 STILL OPEN on exactly these lines; the Outcomes bullet at `:37` records the closure as "the four residual items (R23–R25 plus R20's direction word) were applied", which silently omits R17's residual, so Phase B has no signal that the contradiction was left rather than resolved.
**Evidence**: `track-9.md:29`, `:37`, `:144`, `:154`; `plan/track-9/reviews/risk-gate-verification-iter4.md` (verdicts block: R17 STILL OPEN; body: "`:29` and `:153` still state the re-trigger rule in the unqualified 'any commit' form the criterion at `:143` now qualifies").
**Proposed fix**: Qualify `:29` and `:154` with the same path-prefix language the criterion carries (a qualifying commit is one `git log <sha>..HEAD -- core embedded` reports), and amend the Outcomes bullet to list this application, so the closure record matches what was actually applied.

### A5 [suggestion]
**Certificate**: VS4 (violation scenario, theoretical), supported by AT2
**Target**: The acceptance set at `track-9.md:137-139` against the invariant-restoration claim for shapes outside it
**Challenge**: The fix mechanism is generic — union children re-walk through the single-plan `buildResult` (`UnionForkHost.walkFork` "runs a full production walk"; nested unions decline), and `where()`/`and()` fragments commit their captured filters into the parent through `ctx.putAliasFilter` (`ConnectiveStepSupport:56-57,70-71`), so an option-2 pass covers both — but no acceptance shape witnesses either family. The nine compliance failures cover `and`/`where`/`select` shapes indirectly; nothing anywhere exercises a union child's post-hop filter (`g.V(marko).union(__.out().hasId(vadas), __.out())`), and `rewriteReturnAlias` sits between the child's fix site and the executed plan as one more moving part. Track 8's own review is the precedent that this exact seam fails silently: post-union suffix steps were "accepted by their own recognisers and then silently discarded by the multi-plan `buildResult`", returning wrong rows with no error (`plan/track-8.md:85`).
**Evidence**: `GremlinStepWalker.java:457-467` (multi-plan early return precedes the single-plan section where the fix lands), `:469-477`; `UnionStepRecogniser.java:113-114` (`rewriteReturnAlias` on child inputs); `ConnectiveStepSupport.java:55-73`; `plan/track-8.md:85`.
**Proposed fix**: Two cheap additions to the equivalence-suite work item 2 already books: one union-child post-hop filter shape and one `where(__.out().has(...))` fragment shape, both watched to fail before the fix like the four named shapes. Reference-accuracy caveat: the child-walk routing claim is a grep-plus-read result, which is itself a reason to pin it with a test rather than trust the read.

## Evidence base

Certificates are grouped by review criterion. Verdicts: challenges YES/NO/WEAK (does the chosen approach survive), assumption tests HOLDS/FRAGILE/BREAKS, violation scenarios CONSTRUCTIBLE/THEORETICAL/INFEASIBLE.

**Scope and sizing**

#### CH1 Challenge: DR-S1 — the split, as realized by item 4 — NO
- **Chosen approach**: split the oversized final track; this track carries diagnosis + filter fix + baseline + triage (DR-S1).
- **Best rejected alternative**: the same split with item 4 reduced to diagnose-and-disposition only (fixes limited to the dropped-filter family), the surplus routed to a named follow-up.
- **Counterargument trace**: (1) Item 4 (`track-9.md:125`) instructs "Fix what belongs to this track" with no membership rule; the bucket is 21 deferred failures plus unmeasured post-fix Cucumber residue. (2) The twelve non-dropped-filter defects live in translator shaping/ordering code (`core-compliance-failure-dispositions.md:54-63`); fixing even half of them touches the Track 6 result-shaping surface, files the Scope line never counts. (3) Track 10's equivalent bucket became 483 repairs and the branch's largest track. (4) The plan still claims a sizing marker the split dropped: `implementation-plan.md:615-617` says the "Cucumber triage bucket its own Scope line marks unsized until the first run"; the post-split entry at `:678-692` marks only the diagnosis and the `embedded` A/B unsized.
- **Codebase evidence**: file/line cites above; the ~8–14 figure enumerates fix + tests + artifact only.
- **Survival test**: NO — the split's stated purpose (keep this branch off a run of over-threshold tracks) is defeated by an unbounded item inside the split product. Finding A1.

#### CH2 Challenge: DR-S1 — the split does not solve the Phase C burden problem — YES
- **Chosen approach**: DR-S1 concedes the Phase C burden check reads code plus `_workflow/` prose and predicts a prose half "sized like its predecessors".
- **Best rejected alternative**: none workable — prose volume is Phase A's output, not the track's code footprint.
- **Counterargument trace**: this track's reviews already total 2,597 lines plus a 178-line track file (measured this session), past the ~4,000-line threshold's halfway mark before any step file, episode, or baseline artifact exists; the burden gate will fire on prose alone.
- **Codebase evidence**: `wc -l` over `plan/track-9/reviews/*.md`; DR-S1's own text at `track-9.md:25` predicting exactly this.
- **Survival test**: YES — DR-S1 states the distinction (code-only figure for reviewability, summed figure for the gate) and expects the gate to read the sum. The decision survives; no finding.

#### AT1 Assumption test: item 4 decomposes into a sane step roster — FRAGILE
- **Claim**: the four Plan-of-Work items decompose into sized, committable steps.
- **Stress scenario**: item 1 is budget-bounded (two days / twenty runs, escalation branch defined) and items 2–3 are step-shaped; item 4 as written cannot be pre-sized because its fix membership is undefined.
- **Code evidence**: `track-9.md:87-125`.
- **Verdict**: FRAGILE — holds for items 1–3, breaks at item 4; carried by finding A1 rather than a separate finding.

**Cross-track-episode reality**

#### AT2 Assumption test: the Track 7/8 planner path is as item 2 assumes — HOLDS
- **Claim**: item 2's fix lands on a path whose Track 7/8 realization matches the track file's description.
- **Stress scenario**: Track 8's multi-plan `buildResult` rewrite or Track 7's `ResultShaping` carrier could have moved the seam the fix targets.
- **Code evidence**: `GremlinStepWalker.buildResult:457-467` returns multi-plan early, `:469-509` is the single-plan section exactly as the track describes; union children are produced by `UnionForkHost.walkFork` running "a full production walk" per child (file javadoc `:37-38`), so each child's inputs pass through the single-plan section; Track 7's carrier sits on `ResultShaping` and `buildResult` was not rewired (`plan/track-7.md:93`). `ConnectiveStepSupport.commitPureFilterChild`/`commitEdgeBearingChild` route captured fragment filters into `ctx.putAliasFilter` (`:56-57`, `:70-71`), reaching `finalAliasFilters`.
- **Verdict**: HOLDS. Grep-based; bounded by full reads of the four files named in the caveat.

#### AT3 Assumption test: Track 10's handoff artifact and the track-11 must-amend list say what this track claims — HOLDS
- **Claim**: `plan/track-10/core-compliance-failure-dispositions.md` enumerates 21 deferred failures (nine dropped-filter: `HasTest` ×5, `AndTest` ×2, `WhereTest`, `SelectTest`; twelve separate), its per-execution table has no `gremlin-feature-compliance-tests` row, and `plan/track-11.md` lines 5, 9, 52, 60, 69, 87, 104 carry the content the escalation branch attributes to them.
- **Stress scenario**: a renumbered or rewritten artifact would make the dependency claim and the must-amend list stale.
- **Code evidence**: the dispositions file's table sums 5+2+1+1 = 9 and 2+2+1+1+1+1+1+1+1+1 = 12 in exactly the named classes; its Measurement table lists four executions, none the feature-compliance one; all seven track-11 lines verified against the current file — line 69 names the single-fork `gremlin-feature-compliance-tests` execution, line 87 hard-codes "completes", line 60 pins the bare `surefire:test@` invocation the Decision Log rejects, matching the track's unconditional note.
- **Verdict**: HOLDS on every particular checked.

#### AT4 Assumption test: item 4 can disposition the twelve honestly without an off-arm control — BREAKS
- **Claim** (implicit in item 4): reading the 21 deferred failures against the item-3 baseline suffices to diagnose and disposition them.
- **Stress scenario**: a disposition must attribute the failure (translator defect vs branch/develop debt vs TinkerPop-compliance quirk); the only recorded runs are translator-ON (the branch default at every measurement SHA in the dispositions file).
- **Code evidence**: `core-compliance-failure-dispositions.md:14-21` — control and subject both ran with the default configuration; no `toMatchTranslator.enabled=false` run of `gremlin-process-compliance-tests` exists anywhere in the track record.
- **Verdict**: BREAKS — feeds findings A1 and A2.

#### AT5 Assumption test: six rounds of fixes left no standing contradictions — BREAKS
- **Claim** (the closure record's implicit claim at `track-9.md:37`): everything the panels left open was either applied or recorded.
- **Stress scenario**: a residual applied at one named location but not the others survives a cap-close that lists applications by finding ID.
- **Code evidence**: `risk-gate-verification-iter4.md` verdicts R17 STILL OPEN at `:29`/`:153`; current `track-9.md:29` and `:154` still carry the unqualified "any commit" form; the criterion at `:144` carries the qualified terminating form; the Outcomes bullet lists R23–R25 plus R20's direction word and not R17.
- **Verdict**: BREAKS — finding A4.

**Invariant violation**

#### VS1 Violation scenario: deferred wrong-multiset shapes ship at merge under a default-ON switch — CONSTRUCTIBLE
- **Invariant claim**: translator-on and translator-off produce equal result multisets for every RECOGNIZED shape (plan Architecture Notes).
- **Violation construction**: (1) Start: track closes per its criteria — nine dropped-filter failures fixed, twelve "diagnosed and dispositioned", dispositions deferred with no destination. (2) Track 11 lands terminators; its criterion is no-regression against this track's baseline, so the twelve remain expected failures. (3) Branch merges; `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults true (`GlobalConfiguration.java:1019-1027`). (4) Violation point: any user running the `GroupTest`/`ElementMapTest`/`OrderTest` shapes gets a silently wrong multiset (4→5, 4→2, wrong order) on translated execution. (5) Observable consequence: wrong query answers in production with no error and no owner tracking the defect.
- **Feasibility**: CONSTRUCTIBLE for each defect that is translator-caused; the attribution gap (AT4) means the track cannot currently say which those are. Finding A2.

#### VS2 Violation scenario: option 3's ungated second loop on the SQL path — CONSTRUCTIBLE
- **Invariant claim**: item 2's fix leaves SQL `MATCH` semantics untouched unless the planner-side site is chosen with its stated gate cost.
- **Violation construction**: (1) Start: implementer departs to option 3 with a Decision Log entry, adds the mandated merge walk (`AND-compose, skip-if-absent`) at `MatchExecutionPlanner:2064` after the existing `rebindFilters(aliasFilters)` call. (2) A SQL `MATCH` with `WHERE` on two aliases plans: `buildPatterns` populates `aliasFilters`; `rebindFilters` overwrites each item filter with the consolidated clause (`:6019`); the new walk then AND-composes the same map value onto it. (3) Intermediate state: every filtered item carries `w AND w`. (4) Violation point: per-row double evaluation of every SQL MATCH filter; prettyPrint plan text drifts. (5) Observable consequence: `MatchStatementExecutionTest` failures after a ~32-minute run at best; a silent cost regression if its assertions do not cover the drifted text. The reverse ordering is safe because the overwrite resets the item — safety is ordering-dependent and the bullet states neither.
- **Feasibility**: CONSTRUCTIBLE. Finding A3.

#### CH3 Challenge: option 2 as default — WEAK only at the option-3 bullet
- **Chosen approach**: option 2 default, departures need a written reason (R12).
- **Best rejected alternative**: option 3, argued from "already has both maps at a shared site".
- **Counterargument trace**: VS2 shows the shared site is a liability, not a convenience — the second loop needs an additive-path gate the bullet never states, and its "only by proximity" sentence claims unconditional safety.
- **Codebase evidence**: as VS2.
- **Survival test**: the option-2 default survives (this challenge strengthens it); the enumeration text is WEAK. Finding A3's proposed fix repairs the bullet rather than the decision.

#### VS3 Violation scenario: plans cached under the pre-fix behaviour survive the fix — INFEASIBLE
- **Invariant claim**: after item 2 lands, no execution path serves a pre-fix translation.
- **Violation construction attempted**: `GremlinPlanCache` is the only translated-plan cache; it is in-memory per `SharedContext` (`GremlinPlanCache.java:130`, `db.getSharedContext().getGremlinPlanCache()`), an LRU keyed by post-walk fingerprint, schema-invalidated. A code change to the fix sites requires a new JVM; no cache content crosses a process boundary, and the walk (where the option-2 mutation runs) executes before every cache put. The one cross-JVM stale-code channel is the `embedded` module resolving a stale `youtrackdb-core` jar from the local repository — already covered by `## Clarifications` and the mandatory `-am install` in the acceptance commands.
- **Feasibility**: INFEASIBLE — no finding. Recorded because the interaction was an explicit review question.

#### VS4 Violation scenario: a union child's post-hop filter is still dropped after the fix — THEORETICAL
- **Invariant claim**: item 2 restores the equal-multiset invariant for every shape whose alias filters reach `finalAliasFilters`.
- **Violation construction attempted**: `g.V(marko).union(__.out().hasId(vadas), __.out())` — each child re-walks through `walkFork` to a single-plan `TranslationResult`, so the child's own `buildResult` runs the fix; `rewriteReturnAlias` then renames the child's boundary alias on `MatchPlanInputs` after the items are already bound. A rename that rebuilt items without their bound filters would re-drop the filter, but the binding lives on the item's `SQLMatchFilter` object, which the rename does not reconstruct on the read path traced.
- **Feasibility**: THEORETICAL — no concrete break found, but the path is untested by any acceptance shape and this exact seam (multi-plan `buildResult` silently discarding accepted work) broke once in Track 8 (`plan/track-8.md:85`). Finding A5 (suggestion).

**Kill-switch invariant (no certificate promoted to a finding)**

The A/B discipline challenges were exhausted by the risk panel (R9: `-DargLine=` replaces `core`'s property block and is inert against `embedded`'s inline configuration; the off-arm self-witness criterion). Attempted fresh angles — a mid-JVM flag flip serving translator-built cached plans after the switch goes off — die at the same fact as VS3: the strategy consults the flag before any cache path (`GremlinToMatchStrategy:338`), and a flag flip changes no cached content, only whether translation is attempted. No finding.
