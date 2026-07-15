<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: T8, sev: should-fix, loc: "track-4.md:9", anchor: "### T8 ", cert: C16, basis: "Purpose/Big Picture headline still names HasStep / YTDBHasLabelStep / HasIdStep recognisers — the two phantom recognisers T1/T2 removed everywhere else; a decomposer reading the summary reintroduces the blockers"}
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: T5, verdict: VERIFIED}
  - {id: T6, verdict: VERIFIED}
  - {id: T7, verdict: VERIFIED}
overall: FAIL
evidence_base: {section: "## Evidence base", certs: 1, matches: 1}
cert_index:
  - {id: C16, verdict: CONFIRMED, anchor: "#### C16 "}
flags: [CONTRACT_OK]
-->

# Track 4 technical review — gate verification (iteration 2)

Reviewer role reviewer-technical, phase 3A. All seven iteration-1 findings (T1–T7)
are VERIFIED: the fixes landed in Context & Orientation, Plan of Work, Interfaces,
Validation, and Signatures, and each rests on a HEAD code fact I re-confirmed by
direct source read. One new should-fix (T8) surfaced — the T1/T2 fix scrubbed the
phantom `HasLabelStepRecogniser` / `HasIdStepRecogniser` from three sections but
left the Purpose/Big Picture headline still naming them, so a decomposer reading
top-to-bottom hits a contradiction that recreates the two blockers. Overall FAIL:
one more scrub of the summary paragraph closes the gate.

**Reference-accuracy caveat.** mcp-steroid PSI was unavailable (IDE mid-index,
timing out per the spawn note and the iteration-1 record), so symbol claims rest
on direct reads of the HEAD source (`WalkerContext.java`, `RecognitionContext.java`,
`MatchWhereBuilder.java`, `MatchExecutionPlanner.java`, `StartStepRecogniser.java`,
`GremlinStepWalker.java`, `YTDBGraphStepStrategy.java`) plus grep. The one class-
existence negative that needs the dependency jar (no `HasIdStep` in the TinkerPop
fork) is carried forward from iteration 1's `unzip -l` / `javap` on
`gremlin-core-3.8.1-af9db90-SNAPSHOT.jar` — bytecode is authoritative there and the
fix did not touch that fact.

## Findings

### T8 [should-fix]
**Certificate**: C16
**Location**: `track-4.md` `## Purpose / Big Picture` (line 9); also the strategic
plan's Track 4 checklist entry (`implementation-plan.md` lines 419–420)
**Issue**: The T1/T2 fix collapsed the three `Has*`-family recognisers into one
`HasStepRecogniser` in Context & Orientation (line 35), Plan of Work item 2 (line
43: "No separate `HasLabelStepRecogniser` / `HasIdStepRecogniser` — neither
`YTDBHasLabelStep` nor a `HasIdStep` class exists at translation time"), and
Interfaces (line 78: "a single `HasStepRecogniser` (unpacks property / `~label` /
`~id` containers)"). The Purpose/Big Picture paragraph was not scrubbed — line 9
still reads "adds `HasStep` / `YTDBHasLabelStep` / `HasIdStep` recognisers", naming
the two phantom recognisers the blockers removed. The headline synthesis paragraph
now directly contradicts the authoritative Plan of Work below it. The same stale
triad also survives in the strategic plan's Track 4 checklist entry
(`implementation-plan.md` line 419: "`HasStep` / `HasLabelStep` / `HasIdStep` +
presence-form recognisers").
**Failure scenario**: A decomposer reads the Purpose/Big Picture first (it is the
track's headline) and decomposes "add `HasStep` / `YTDBHasLabelStep` / `HasIdStep`
recognisers" into three recogniser steps. That recreates both iteration-1 blockers:
a `HasIdStepRecogniser` keyed on `HasIdStep.class` does not compile (no such symbol
in the fork jar), and a recogniser keyed on `YTDBHasLabelStep.class` is dead code
(the translator runs before `YTDBGraphStepStrategy`, the sole producer of that
step). The wasted-effort blast radius the two blockers guarded against reopens.
**Proposed fix**: Rewrite line 9 to name the single `HasStepRecogniser` (unpacking
property / `~label` / `~id` containers), matching Plan of Work item 2 and Interfaces.
Scrub the same triad from `implementation-plan.md` lines 419–420.

## Verification certificates

#### Verify T1: no `HasIdStep` class — fold id into the single `HasStepRecogniser`
- **Original issue**: Plan introduced a standalone `HasIdStepRecogniser` keyed on a
  `HasIdStep` class that does not exist; `hasId` compiles to `HasStep(~id)`, so under
  D9 exact-class dispatch it cannot bind a separate key.
- **Fix applied**: Plan of Work collapsed to a single `HasStepRecogniser`
  (`track-4.md:43`) that iterates `HasStep.getHasContainers()` and, for a `~id`
  container, emits an `@rid IN [...]` WHERE on the alias filter (single + multi both
  route through `@rid IN`, the single case collapsed by `promoteStaticRidsFromFilters`);
  `HasIdStepRecogniser` dropped from Plan of Work and Interfaces.
- **Re-check**:
  - Location: `## Plan of Work` item 2 (line 43), `## Context and Orientation`
    (line 35), `## Interfaces` (line 78).
  - Current state: item 2 states "No separate `HasLabelStepRecogniser` /
    `HasIdStepRecogniser` — neither `YTDBHasLabelStep` nor a `HasIdStep` class exists
    at translation time"; C&O explains `has` / `hasLabel` / `hasId` all arrive as one
    `HasStep` distinguished by container key, `~id` → `@rid IN` WHERE.
  - Criteria met: recogniser dispatch is now consistent with D9 exact-class keying;
    the `@rid IN` + `promoteStaticRidsFromFilters` mechanism matches
    `StartStepRecogniser` lines 125–133 (confirmed: `ctx.putAliasFilter(...,
    buildRidInExpression(rids))` and the promote comment).
- **Regression check**: Checked C&O, Plan of Work, Interfaces — consistent. But the
  `## Purpose / Big Picture` headline (line 9) still lists `HasIdStep` recognisers →
  raised as T8.
- **Verdict**: VERIFIED

#### Verify T2: `hasLabel` is a plain `HasStep(~label)` at translation time
- **Original issue**: Track claimed `hasLabel` is folded into the start step by
  `YTDBGraphStepStrategy` and added a `HasLabelStepRecogniser` keyed on
  `YTDBHasLabelStep`; both are dead at translation time.
- **Fix applied**: C&O rewritten — g2m runs before `YTDBGraphStepStrategy`, `GraphStep`
  is not a `HasContainerHolder`, so `has` / `hasLabel` / `hasId` all arrive as one
  `HasStep` distinguished by `HasContainer` key; `~label` handled inside
  `HasStepRecogniser`; the `HasLabelStepRecogniser` / `YTDBHasLabelStep` narrative
  deleted.
- **Re-check**:
  - Location: `## Context and Orientation` (line 35), `## Plan of Work` item 2 (line 43).
  - Current state: line 35 reads "The g2m translator runs **before**
    `YTDBGraphStepStrategy` (`YTDBGraphStepStrategy.applyPrior()` returns
    `{GremlinToMatchStrategy.class}`), and `GraphStep` is not a `HasContainerHolder`,
    so at translation time no GraphStep fold has happened, no `YTDBHasLabelStep`
    exists, and `hasLabel` is never folded onto the start step."
  - Criteria met: matches HEAD — `YTDBGraphStepStrategy.applyPrior()` returns
    `Set.of(GremlinToMatchStrategy.class)` (line 182 confirmed), so the translator
    runs first; `new YTDBHasLabelStep` is produced only inside that strategy (line 149
    confirmed).
- **Regression check**: C&O / Plan of Work / Interfaces consistent. Purpose headline
  (line 9) still names `YTDBHasLabelStep` recognisers → T8.
- **Verdict**: VERIFIED

#### Verify T3: same-alias filter contributions AND-compose, not overwrite
- **Original issue**: `WalkerContext.putAliasFilter` does `aliasFilters.put`
  (overwrite) and `buildResult` merges with `putAll` (overwrite); two contributions
  to one alias silently drop the earlier one → over-match.
- **Fix applied**: C&O adds a same-alias AND-compose rule; Interfaces "In scope
  (modified)" lists the AND-composing `putAliasFilter` + `buildResult` merge; a
  regression test added to Validation.
- **Re-check**:
  - Location: `## Context and Orientation` (line 39), `## Interfaces` (line 79),
    `## Validation` (line 59).
  - Current state: line 39 requires `putAliasFilter` (and the `buildResult` merge) to
    AND an incoming clause with any existing one via `MatchWhereBuilder.and`, and
    enumerates the four two-filter shapes; Validation pins
    `g.V(id1,id2).has("age",30)` returning only the two ids' age-30 rows and
    `hasLabel(L).has(k,v)` intersecting.
  - Criteria met: matches the multiset-equality contract. HEAD state confirmed —
    `WalkerContext.putAliasFilter` line 237 does `aliasFilters.put` (overwrite);
    `GremlinStepWalker.buildResult` line 243 does `finalAliasFilters.putAll` — so the
    AND-compose spec correctly targets both sites; `MatchWhereBuilder.and` exists
    (line 172).
- **Regression check**: AND-composing is safe (keyed per alias; a first contribution
  with no existing entry just inserts). No new issue.
- **Verdict**: VERIFIED

#### Verify T4: `bindParam` moved to `RecognitionContext`; adapter parameterizes only predicate values
- **Original issue**: D5 placed `bindParam` on `WalkerContext`, but recognisers /
  adapter see only `RecognitionContext` (no `bindParam`); adapter renders inline
  literals via `MatchLiteralBuilder.toLiteral`.
- **Fix applied**: Plan of Work item 7 moves `bindParam(value) → SQLPositionalParameter`
  to `RecognitionContext` (implemented by `WalkerContext`); adapter emits
  `SQLPositionalParameter` for predicate comparison values only; structural tokens
  (class names, `~label` values, `@rid IN` RIDs) stay inline.
- **Re-check**:
  - Location: `## Plan of Work` item 7 (line 48), `## Interfaces` (line 78).
  - Current state: item 7 puts `bindParam` on `RecognitionContext` "so recognisers and
    `GremlinPredicateAdapter` — which see only `RecognitionContext` — can reach it",
    and spells out the structural-vs-value split with the wrong-plan risk.
  - Criteria met: HEAD confirms the gap — `RecognitionContext` has no `bindParam`
    (read in full: `polymorphic()`, `boundaryAlias()`, minting, `addNode`/`addEdge`/
    `addEdgeAsNode`, `putAliasFilter`, `putEdgeFilter`, `pinBoundary`,
    `setSingleReturnColumn` only); `GremlinPredicateAdapter` renders inline via
    `MatchLiteralBuilder.toLiteral(value)` (line 103). The fix reaches the right seam.
- **Regression check**: Structural-token exclusion is stated explicitly, closing the
  D5 wrong-plan risk. No new issue.
- **Verdict**: VERIFIED

#### Verify T5: edge-bearing NOT wiring enumerated + second decline precondition added
- **Original issue**: `notMatchExpressions` list / `RecognitionContext` sink /
  `buildResult` wiring absent and unlisted; `manageNotPatterns` also throws when the
  NOT origin alias carries a WHERE filter (unhandled).
- **Fix applied**: Plan of Work item 5 enumerates the three additions and adds the
  decline-when-first-NOT-alias-carries-a-filter precondition; Interfaces and
  Signatures updated.
- **Re-check**:
  - Location: `## Plan of Work` item 5 (line 46), `## Interfaces` (line 79),
    `## Signatures` (line 82).
  - Current state: item 5 routes edge-bearing NOT to "a new `notMatchExpressions` list
    on `WalkerContext` (via a new `RecognitionContext` sink, wired into `buildResult`'s
    `MatchPlanInputs.builder(...)`)" and declines "when the first NOT alias is absent
    from the positive pattern **or** when that alias would carry a WHERE filter";
    Signatures line 82 records both throw conditions.
  - Criteria met: HEAD confirms every claim — `WalkerContext` has no
    `notMatchExpressions` field; `RecognitionContext` has no sink; `buildResult`
    (lines 250–256) sets only `aliasClasses` / `aliasFilters` / `return*`, never
    `.notMatchExpressions(...)`; `MatchExecutionPlanner.manageNotPatterns` throws at
    line 760 (alias absent) and line 766 (`exp.getOrigin().getFilter() != null`). Both
    are hard `CommandExecutionException`s.
- **Regression check**: The planner side (`MatchPlanInputs.notMatchExpressions`,
  builder, additive ctor) already exists, so only the walker-side wiring is new. No new
  issue.
- **Verdict**: VERIFIED

#### Verify T6: `~label` → `classEquals` gated on `ctx.polymorphic()` false
- **Original issue**: `MatchWhereBuilder.classEquals` emits exact `@class =`
  (non-polymorphic), so an unconditional `classEquals` for `hasLabel` under-matches in
  polymorphic mode.
- **Fix applied**: `~label` → `classEquals` gated on `ctx.polymorphic()` false (decline
  or subclass-inclusive predicate otherwise); a polymorphic-vs-non-polymorphic
  acceptance test added.
- **Re-check**:
  - Location: `## Context and Orientation` (line 35), `## Plan of Work` item 2 (line
    43), `## Validation` (line 58).
  - Current state: C&O narrows `~label` "only when `ctx.polymorphic()` is false"
    (with the exact-`@class=`-excludes-subclasses rationale); Validation pins both
    modes with an equivalence test.
  - Criteria met: `RecognitionContext.polymorphic()` exists (line 37);
    `MatchWhereBuilder.classEquals` is exact (line 65). Gate is correct: exact-class
    match fits non-polymorphic `hasLabel`, and polymorphic mode declines / broadens.
- **Regression check**: Clean; the gate uses the flag that exists precisely for this.
- **Verdict**: VERIFIED

#### Verify T7: accuracy drift — no `aliasRids`/`aliasClasses` slot; `endsWith`/`matchesRegex` are new
- **Original issue**: Track described `aliasRids[a]` / `aliasClasses[a]` map slots
  (absent from `WalkerContext`) and hedged `startsWith` / `endsWith` / `matchesRegex`
  as "if stubbed in Track 1".
- **Fix applied**: slot framing dropped (rid/class contributions stated as WHERE
  clauses); `endsWith` / `matchesRegex` stated as new, `startsWith` as existing.
- **Re-check**:
  - Location: `## Context and Orientation` (line 35), `## Plan of Work` items 2/4
    (lines 43, 45), `## Interfaces` (line 79).
  - Current state: C&O states "there is no `aliasRids`/`aliasClasses` slot on the
    context"; Interfaces reads "`startsWith` already exists, while `endsWith` /
    `matchesRegex` are **new** here".
  - Criteria met: HEAD confirms — `WalkerContext` has no `aliasRids` / `aliasClasses`
    field (read in full); `MatchWhereBuilder` has `startsWith` (line 152) but
    `endsWith` / `matchesRegex` are absent (grep returned none).
- **Regression check**: Clean. (Note: `StartStepRecogniser`'s own Javadoc still uses
  the word "aliasRids" for a planner-side concept, but that is HEAD-code doc, not the
  context slot the track disclaimed — the track's statement about the context is
  accurate.)
- **Verdict**: VERIFIED

## Evidence base

#### C16 Purpose/Big Picture still names the two phantom recognisers
- **Track claim**: (Purpose/Big Picture, line 9) "adds `HasStep` / `YTDBHasLabelStep` /
  `HasIdStep` recognisers".
- **Search performed**: Read `track-4.md` in full; grepped `implementation-plan.md` for
  the Track 4 checklist entry.
- **Location**: `track-4.md:9`; `implementation-plan.md:419`.
- **Actual state**: Line 9 lists three separate recognisers including
  `YTDBHasLabelStep` and `HasIdStep`, contradicting Plan of Work item 2 (line 43:
  "No separate `HasLabelStepRecogniser` / `HasIdStepRecogniser`") and Interfaces
  (line 78: "a single `HasStepRecogniser`"). The strategic plan checklist (line 419)
  carries the same stale triad "`HasStep` / `HasLabelStep` / `HasIdStep`".
- **Verdict**: CONFIRMED (produces T8)

## Summary

FAIL. T1–T7 all VERIFIED — every iteration-1 fix landed in the authoritative sections
(Context & Orientation, Plan of Work, Interfaces, Validation, Signatures) and each
matches a re-confirmed HEAD code fact. One new should-fix, T8: the Purpose/Big Picture
headline (and the strategic plan's Track 4 checklist entry) still names the two phantom
`YTDBHasLabelStep` / `HasIdStep` recognisers the T1/T2 blockers removed everywhere else,
so the track contradicts itself and a decomposer following the headline would reopen the
blockers. Scrub line 9 (and `implementation-plan.md` line 419) to name the single
`HasStepRecogniser`, then the gate passes.
