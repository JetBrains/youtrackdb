<!-- MANIFEST
findings: 9   severity: {blocker: 2, should-fix: 5, suggestion: 2}
index:
  - {id: A1, sev: blocker,    loc: MatchPatternBuilder.java:121, anchor: "### A1 ", cert: C1, basis: "planned addEdge call shape does not exist; edge filter lands on the target-vertex slot and evaluates against the wrong element"}
  - {id: A2, sev: blocker,    loc: StartStepRecogniser.java:32,  anchor: "### A2 ", cert: V1, basis: "chain-target @class narrowing reintroduces the BC2 undercount; native out() never class-filters targets"}
  - {id: A3, sev: should-fix, loc: GremlinStepWalker.java:58,    anchor: "### A3 ", cert: C5, basis: "out(L) and outE(L) share the concrete VertexStep class; two recognisers cannot both register under it (D9)"}
  - {id: A4, sev: should-fix, loc: WalkerContext.java:28,        anchor: "### A4 ", cert: V2, basis: "peek-ahead accumulates into ctx before the claim decision; violates no-mutation-on-decline the track itself asserts"}
  - {id: A5, sev: should-fix, loc: YTDBMatchPlanStep.java:437,   anchor: "### A5 ", cert: C6, basis: "ELEMENT boundary and WalkerContext.polymorphic already landed in Track 2; the real walker delta (MAX_RECOGNISED_STEPS) is unnamed"}
  - {id: A6, sev: should-fix, loc: track-3.md:52,                anchor: "### A6 ", cert: C8, basis: "anon-alias generator + $-prefix pre-flight deferred to Track 3 by Track 2, but no Plan of Work item builds them"}
  - {id: A7, sev: should-fix, loc: StartStepRecogniser.java:149, anchor: "### A7 ", cert: C9, basis: "chain recognisers must overwrite boundary metadata and replace the single return entry; the handoff is unplanned"}
  - {id: A8, sev: suggestion, loc: track-3.md:53,                anchor: "### A8 ", cert: C11, basis: "fixture lacks edge-subclass and parallel-edge cases; edge-label polymorphism equivalence between MATCH out() and native out() is unverified"}
  - {id: A9, sev: suggestion, loc: track-3.md:46,                anchor: "### A9 ", cert: C13, basis: "7-item decomposition survives with the A1/A5/A6 amendments; net footprint stays inside the 12-25 band, no split or merge"}
evidence_base: {section: "## Evidence base", certs: 15, matches: 3}
cert_index:
  - {id: C1,  verdict: NO,            anchor: "#### C1 "}
  - {id: C2,  verdict: BREAKS,        anchor: "#### C2 "}
  - {id: C3,  verdict: BREAKS,        anchor: "#### C3 "}
  - {id: V1,  verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: C4,  verdict: BREAKS,        anchor: "#### C4 "}
  - {id: C5,  verdict: NO,            anchor: "#### C5 "}
  - {id: V2,  verdict: CONSTRUCTIBLE, anchor: "#### V2 "}
  - {id: C6,  verdict: BREAKS,        anchor: "#### C6 "}
  - {id: C7,  verdict: BREAKS,        anchor: "#### C7 "}
  - {id: C8,  verdict: BREAKS,        anchor: "#### C8 "}
  - {id: C9,  verdict: FRAGILE,       anchor: "#### C9 "}
  - {id: C10, verdict: HOLDS,         anchor: "#### C10 "}
  - {id: C11, verdict: FRAGILE,       anchor: "#### C11 "}
  - {id: C12, verdict: HOLDS,         anchor: "#### C12 "}
  - {id: C13, verdict: YES,           anchor: "#### C13 "}
flags: [CONTRACT_OK]
-->

# Adversarial review — Track 3, iteration 1

Track 3's goal is right and the track is needed (no `skip` candidate). Two
blockers sit in the realization, not the design: the edge-filter mechanism is
specified against a `MatchPatternBuilder.addEdge` signature and semantics that
Track 1 did not deliver, and the chain-target `@class` narrowing instruction
reproduces the BC2 subclass-undercount that Track 2 removed from the start
node. Five should-fix findings cover a D9 registry-key collision between the
two planned recognisers, a no-mutation-on-decline violation written into the
peek-ahead item, stale scope inherited from pre-Track-2 wording, the missing
anon-alias-generator work item, and the unplanned boundary-metadata handoff.
Two assumptions the track leans on were tested and hold: the fork's
`LazyBarrierStrategy` never injects a barrier inside the `outE…inV` claim
window, and MATCH's simple-mode hop preserves parallel-edge multiplicity.

## Findings

### A1 [blocker]
**Certificate**: C1 (with C2, C3)
**Target**: Plan of Work item 2 + `## Interfaces and Dependencies` Signatures line ("`MatchPatternBuilder.addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)`; `SQLMatchPathItem.filter`")
**Challenge**: The call the plan writes does not exist, and the nearest real
overload does the wrong thing. Landed `addEdge` (Track 1) is 7-param — no
`edgeAlias` — and parks `edgeFilter` on the target-vertex `{…}` block
(`toFilter.setFilter(edgeFilter)`, `MatchPatternBuilder.java:136-150`; the
Javadoc at :110 says "target-vertex filter" verbatim, and Track 1's Step 3
episode recorded the same). The executor evaluates that WHERE against the
element the hop lands on (`MatchEdgeTraverser.executeTraversal` → `filter`,
`MatchEdgeTraverser.java:361-392,436-450`) — for an `out`/`in`/`both` item,
the target vertex. So `outE("KNOWS").has("creationDate", lte(d)).inV()`
translated per item 2 evaluates `creationDate` against the friend vertex.
In LDBC, Person vertices carry `creationDate` too, so the result is non-empty
and silently wrong — the equivalence test fails and the track's central
mechanism needs a mid-track redesign.
**Evidence**: The IR route that works is a two-item chain
`.outE(L){as: $g2m_edge_N, where: …}.inV()` — the edge becomes an intermediate
pattern node whose item output IS the edge, so its filter is edge-side.
`SQLMethodCall.graphMethods` includes `outE`/`inE`/`bothE`/`inV`/`outV`
(`SQLMethodCall.java:30`), and `MatchStatementExecutionNewTest.java:1402`
already plans and runs `.outE('TriangleE').inV(){as: …, where: …}` — so the
design's "no executor or planner change" claim survives via this route. What
does not survive: `MatchPatternBuilder` can only emit single `out`/`in`/`both`
items, so Track 3 must extend it (a `match/builder/` file absent from the
track's In-scope lists), and the pattern topology gains one node + two pattern
edges per filtered hop, which the plan's single-`addEdge` description hides.
**Proposed fix**: Add a Plan of Work item: extend `MatchPatternBuilder` with a
method-chain edge hop (`outE(L)` item carrying `{as: edgeAlias, where:
edgeFilter}` + closing `inV`/`outV` item), or route the edge filter through
`addNode(edgeAlias, …, where, …)` on the intermediate node. Add
`MatchPatternBuilder` (modified) + its test to In-scope; correct the
Signatures line to the real 7-param shape; reword item 2 and the mermaid
`AddEdge` node.

### A2 [blocker]
**Certificate**: V1 (with C4)
**Target**: Plan of Work item 1 ("applies the same `@class = '<className>'` … narrowing as the start recogniser") + Validation line "`polymorphic=false` narrows chain-target nodes, not just the start node"
**Challenge**: There is no start-recogniser narrowing to be "the same as."
Track 2's BC2 fix removed it: `StartStepRecogniser` emits no `@class`
narrowing because the native pipeline returns the full polymorphic set for a
bare vertex source regardless of the flag (`StartStepRecogniser.java:32-34,
156-160`). The same fact holds for chain targets: native `out(label)` is a raw
link-bag traversal (`YTDBVertexImpl.vertices` →
`getRawEntity().asVertex().getVertices(dir, labels)`,
`YTDBVertexImpl.java:73-86`) — no class filter on targets, and the
polymorphic flag never reaches it. Narrowing an anonymous `out()` target to
`@class = 'V'` under `polymorphic=false` excludes every subclass instance.
**Evidence**: Violation is constructible: non-polymorphic session,
`g.V().out("knows")`, targets all of class `Person extends V`. Translator with
item-1 narrowing emits `@class = 'V'` on `$g2m_anon_M`; `matchesClass` filters
on `isSubClassOf("V")` — that passes, but exact-match narrowing per the
BC2-era code shape (`@class = 'V'` equality WHERE) returns zero rows while
native returns every Person. This is the exact bug class Track 2's Surprises
log flags: "Any later recogniser that adds a class filter on the bare vertex
source reintroduces the BC2 subclass-undercount bug" — a chain target minted
as bare `"V"` is the same bare source, one hop later.
**Proposed fix**: Anonymous chain targets get NO `@class` WHERE-narrowing in
Track 3 — mirror the start recogniser: root the alias at `V` polymorphically
via `aliasClasses` only. Class narrowing enters with `hasLabel`-derived class
info in Track 4, where a user-named class exists to narrow to. Flip the
Validation line accordingly and re-scope `MatchClassFilters` (item 4) to
Track 4, or keep it here only as the future chokepoint with no Track 3
call site that emits a WHERE.

### A3 [should-fix]
**Certificate**: C5
**Target**: Plan of Work items 1–2 + In-scope (new) — `VertexStepRecogniser` and `EdgeStepRecogniser` as two registered recognisers
**Challenge**: `out(L)` and `outE(L)` are the same concrete step class —
TinkerPop's `VertexStep`, differing only in `returnClass` (fork jar confirms
`VertexStep`, `EdgeVertexStep`, `EdgeOtherVertexStep` as the three classes;
`LazyBarrierStrategy` bytecode dispatches on `VertexStepContract.returnsEdge()`).
The D9 registry is one entry per concrete class (`Map.of` at
`GremlinStepWalker.java:58`; D9: "One map entry per Step class; each
recognizer handles every variant internally"). Registering both recognisers
under `VertexStep.class` throws at map construction. Also, the closing
`otherV()` is `EdgeOtherVertexStep`, not an "otherV-form `VertexStep`" as item
2 and the design section say.
**Proposed fix**: One registered recogniser under `VertexStep.class` that
branches on `returnsVertex()`/`returnsEdge()` (folded-hop path vs peek-ahead
path); keep the two behaviors as internal strategies if that helps testing,
but the registry entry and the In-scope class list must reflect one dispatch
point. Correct the `otherV` wording to `EdgeOtherVertexStep`.

### A4 [should-fix]
**Certificate**: V2
**Target**: Plan of Work item 2 + Invariant "no-mutation-on-decline"
**Challenge**: Item 2 as written mutates before deciding: it "mints
`$g2m_edge_N`, peeks successive `HasStep`s and AND-merges … into
`ctx.edgeFilters[$g2m_edge_N]`", and only then "declines on a non-`HasStep` …
or no closing hop". `WalkerContext`'s contract is explicit: "Recognisers must
not mutate any field unless they are about to return true. The walker does not
roll back partial mutations" (`WalkerContext.java:28-32`). A decline after
accumulation leaves a stale `edgeFilters` entry and an advanced alias counter
— and the track's own Validation section asserts the opposite ("Decline cases
leave `WalkerContext` unmutated"). The unit test the track promises would fail
against the implementation the track prescribes.
**Proposed fix**: Reword item 2: peek and accumulate into locals (filter list,
prospective aliases), commit to `ctx` (builder, `edgeFilters`, counters,
`stepIndex += N`) only after the closing hop is confirmed. State the same rule
for the mint — counters advance on claim, not on attempt.

### A5 [should-fix]
**Certificate**: C6 (with C7)
**Target**: `## Purpose / Big Picture` + Plan of Work item 6 + In-scope (modified)
**Challenge**: Three scope statements describe work that is already done or
mis-name the work that remains. (a) "Track 3 is the first track that wires a
boundary step output type at all (`ELEMENT`)" — Track 2 wired it end to end:
`BoundaryOutputType.ELEMENT` exists, `YTDBMatchPlanStep` projects rows via
`switch (outputType) { case ELEMENT -> projectElement… }` →
`YTDBVertexImpl` (`YTDBMatchPlanStep.java:437-481`), and Track 2's A5
review-resolution says so. The projection is alias-generic, so the In-scope
"`YTDBMatchPlanStep` (wire `ELEMENT` projection)" item is a no-op for Track
3's vertex-emitting shapes. (b) Item 6 lists `polymorphic` as a new
`WalkerContext` field — it landed as a `final boolean` with walker-owned
resolution post-Track-2 (`WalkerContext.java:86`, commit 4fc1a40c4a). (c) The
real walker deltas go unnamed: the loop is a for-each with a trailing
`ctx.stepIndex++` (`GremlinStepWalker.java:129-139`) that must become
index-driven with recogniser-controlled advancement, and
`MAX_RECOGNISED_STEPS = 1` (`GremlinStepWalker.java:79,110`) declines every
multi-step traversal until raised — no Plan of Work item mentions it.
**Proposed fix**: Reword Purpose/Context to "first multi-alias pattern /
first chain-target boundary re-pointing"; drop `YTDBMatchPlanStep` from
In-scope (modified) unless a concrete delta is named; item 6 becomes: loop
refactor + recogniser-driven index advancement + raise/replace
`MAX_RECOGNISED_STEPS` + new fields `edgeFilters`, alias counters.

### A6 [should-fix]
**Certificate**: C8
**Target**: Plan of Work item 6 + In-scope (new); Track 2 Decision Log scope-down
**Challenge**: Track 2 deferred the whole anonymous-alias facility to Track 3:
"The generator and its reserved-`$`-label collision pre-flight land with the
first multi-alias shape (Track 3 edge chains)". Track 3's plan carries only a
context field name (`anonEdgeAliasGenerator`) — no item builds the generator
producing `$g2m_anon_M` vertex aliases and `$g2m_edge_N` edge aliases, neither
appears in In-scope (new), and the `$`-prefix pre-flight (design §"Anonymous
alias generation" mandates a walker scan of every step's `getLabels()` that
declines on a `$`-prefixed user label) appears nowhere in the track file. The
walker today has no labels scan (`GremlinStepWalker.walk`,
`GremlinStepWalker.java:102-160`) and the sole alias is the `$g2m_v0`
constant in `StartStepRecogniser`.
**Proposed fix**: Add an explicit item: alias generator (two per-walk
counters or one class with two prefixes) + the pre-flight `$`-label scan in
the walker + both in In-scope (new/modified). Cheap addition, and it closes a
design commitment that otherwise silently slips to Track 4.

### A7 [should-fix]
**Certificate**: C9
**Target**: Plan of Work items 1–2; walker terminator contract
**Challenge**: The walker's contract says boundary metadata is "pinned by the
recogniser that owns the terminator", and `StartStepRecogniser` currently pins
all three fields plus one return entry `$g2m_v0 AS $g2m_v0` unconditionally on
claim (`StartStepRecogniser.java:149-151`; Track 2 Step 4 episode: later
tracks "must keep this contract"). On a chain, every hop recogniser must
overwrite `boundaryAlias`/`returnClass` AND replace — not append to — the
single `returnItems`/`returnAliases` entry, otherwise the planner projects the
start alias (results are start vertices re-labeled as targets) or emits two
columns. No Plan of Work item mentions the handoff, and it interacts with A4's
commit-on-claim discipline (the replace must also be claim-time only).
**Proposed fix**: Name the mechanic in item 1: chain recognisers re-pin
boundary metadata and rewrite the single return projection entry on every
claimed hop (last claim wins), with an equivalence-test case asserting the
projected column is the chain target.

### A8 [suggestion]
**Certificate**: C11 (with C12)
**Target**: Plan of Work item 7 (`EdgeTraversalEquivalenceTest` fixture)
**Challenge**: The Person/Place + Knows/Likes/Follows fixture never exercises
two equivalence blind spots. (1) Edge-label polymorphism: native `out("knows")`
routes through `Vertex.getVertices(dir, labels)`; MATCH `out('knows')` routes
through the SQL `out` graph function (`SQLMethodCall.executeGraphFunction`,
`SQLMethodCall.java:197-209`). Both are entity-level link traversals, but
whether they resolve edge-class subclasses identically under both polymorphic
settings is unverified — a divergence is exactly the class of silent multiset
bug BC1/BC2 were. (2) Parallel edges: MATCH's simple mode streams the raw
link bag with no dedup (`traversePatternEdge`,
`MatchEdgeTraverser.java:531-546`), matching Gremlin's one-traverser-per-edge
— it holds in code today but nothing pins it.
**Proposed fix**: Add fixture cases: an edge subclass of `Knows` traversed via
`out("knows")` under polymorphic on and off, and a parallel-edge pair between
two vertices asserting multiplicity 2.

### A9 [suggestion]
**Certificate**: C13
**Target**: Track sizing / decomposition (scope indicator ~15 files, 7 items)
**Challenge**: Tested whether any item is secretly two tracks or the track
should merge/split. It survives: the A1 builder extension and A6 generator +
pre-flight add ~3 files; A5 removes the `YTDBMatchPlanStep` edit; A3 merges
two recogniser classes into one dispatch point. Net ~16-17 in-scope files —
inside the 12–25 band, and the items are one coherent capability (edge
traversal) with a single new invariant surface. The predicate-adapter skeleton
(item 5) is correctly minimal — only the `has(key, value)`/`P` subset the
edge chain needs — and moving it to Track 4 would force Track 4 to re-open the
edge recogniser, which is worse.
**Proposed fix**: None beyond the amendments above; update the scope indicator
file count when the Plan of Work is amended.

## Evidence base

### DECISION / REALIZATION CHALLENGES

#### C1 Challenge: Plan of Work item 2 — edge filter through `addEdge(… edgeAlias, accumulatedEdgeFilter)`
- **Chosen approach**: single `addEdge` call carrying an edge alias and the
  accumulated edge filter, "parked on the `SQLMatchPathItem.filter` slot".
- **Best rejected alternative** (implicit): two-item method chain
  `.outE(L){as, where}.inV()` — the shape the parsed MATCH language already
  executes.
- **Counterargument trace**:
  1. Track 3 calls `addEdge(from, $g2m_anon_M, OUT, L, $g2m_edge_N, filter)` —
     no such overload; the landed method is 7-param with no alias
     (`MatchPatternBuilder.java:121-128`).
  2. Fitting the real signature (`addEdge(from, to, dir, L, filter, null,
     null)`) attaches the filter to the target-vertex `{…}` block
     (`toFilter.setFilter(edgeFilter)`, :136-150; Javadoc :110).
  3. `MatchEdgeTraverser.executeTraversal` evaluates that WHERE against each
     element `traversePatternEdge` returns — vertices, for an `out` item
     (`MatchEdgeTraverser.java:361-392`). Edge predicates run against target
     vertices; LDBC IC2's `creationDate` exists on Person vertices, so the
     wrong evaluation returns plausible non-empty wrong results.
- **Codebase evidence**: `MatchStatementExecutionNewTest.java:1402` executes
  `.outE('TriangleE').inV(){as: …, where: …}` through the planner today —
  the two-item route needs no engine change, only builder support.
- **Survival test**: NO — the plan's call shape cannot compile as written and
  the semantically nearest real call produces wrong results. → A1

#### C4 Challenge: item 1 — "the same `@class` narrowing as the start recogniser"
- **Chosen approach**: chain targets inherit the start recogniser's
  non-polymorphic `@class` narrowing via `MatchClassFilters`.
- **Best rejected alternative**: no narrowing on anonymous targets (the BC2
  resolution applied one hop later).
- **Counterargument trace**: the start recogniser has no narrowing to inherit
  — BC2 removed it (`StartStepRecogniser.java:32-34,156-160`); the referenced
  logic does not exist anywhere on the branch (`MatchClassFilters` is new in
  this track).
- **Codebase evidence**: Track 2 Surprises log: "bare `g.V()` must never
  narrow by class — the only place `@class` narrowing legitimately reappears
  is Track 4's folded `hasLabel`."
- **Survival test**: NO (BREAKS) — supports A2.

#### C5 Challenge: two recognisers for one concrete step class
- **Chosen approach**: separate `VertexStepRecogniser` and
  `EdgeStepRecogniser` classes, each a registry entry.
- **Best rejected alternative**: one recogniser under `VertexStep.class`
  branching on `returnsVertex()`/`returnsEdge()` — D9's own prescription.
- **Counterargument trace**: `out(L)` and `outE(L)` construct the same
  concrete `VertexStep` (fork jar:
  `org/apache/tinkerpop/gremlin/process/traversal/step/map/VertexStep.class`;
  `LazyBarrierStrategy` bytecode branches on
  `VertexStepContract.returnsEdge()`). The registry is class-keyed with a
  duplicate-key guard (`Map.of`, `GremlinStepWalker.java:58`; D9 duplicate-key
  assertion). Two entries under one key fail at construction.
- **Codebase evidence**: D9 rationale text: "each recognizer handles every
  variant internally". `otherV()` is `EdgeOtherVertexStep` (third class,
  fork jar), consumed inside the multi-step claim, not dispatched.
- **Survival test**: NO — the split as written cannot register. → A3

#### C13 Scope challenge: sizing and decomposition
- **Chosen approach**: 7 items, ~15 files, one track.
- **Trace**: A1 adds builder extension + test (~2 files); A6 adds generator +
  pre-flight (~1-2 files); A5 removes the boundary edit (−1); A3 merges two
  classes into one entry (±0). Net ~16-17 in-scope files, one capability, one
  test fixture. No item decomposes into an independently mergeable PR without
  breaking the equivalence-test story.
- **Survival test**: YES — sizing holds after amendments. → A9

### INVARIANT CHALLENGES

#### V1 Violation scenario: "Translator-on and translator-off produce equal result multisets" vs chain-target narrowing
- **Invariant claim**: equal multisets for every RECOGNIZED shape.
- **Violation construction**:
  1. Start state: schema `Person extends V`, `knows` edges between Persons;
     session with `polymorphicQuery=false`.
  2. Action: `g.V().out("knows")` — recognized per item 1, which narrows
     `$g2m_anon_M` with an exact `@class = 'V'` WHERE under
     `polymorphic=false`.
  3. Intermediate: MATCH plan filters hop results through the alias WHERE;
     native pipeline streams the raw link bag
     (`YTDBVertexImpl.vertices` → `getVertices(dir, labels)`,
     `YTDBVertexImpl.java:73-86` — no class filter, flag never consulted).
  4. Violation point: every target is `Person`, `@class = 'V'` equality
     rejects all of them; translated result is empty, native is not.
  5. Observable consequence: silent undercount — the BC2 bug one hop later.
- **Feasibility**: CONSTRUCTIBLE — same mechanism BC2 exhibited on the start
  node in Track 2 Step 4; the flag is settable per query options. → A2

#### V2 Violation scenario: "no-mutation-on-decline" vs item 2's accumulate-then-decline
- **Invariant claim**: a recogniser returning false leaves `WalkerContext`
  unmutated (plan Invariants; track Validation).
- **Violation construction**:
  1. Start state: walk of `g.V().outE("knows").has("since", gt(5)).order()`.
  2. Action: recogniser mints `$g2m_edge_0` (counter++), AND-merges the
     `since` filter into `ctx.edgeFilters["$g2m_edge_0"]` per item 2's text,
     then peeks `OrderGlobalStep` — a non-`HasStep`.
  3. Intermediate: `ctx.edgeFilters` holds an entry; the alias counter is
     advanced.
  4. Violation point: recogniser returns false; the walker does not roll back
     (`WalkerContext.java:28-32` states rollback is not provided).
  5. Observable consequence: the per-recogniser unit invariant test the track
     itself promises ("Decline cases leave `WalkerContext` unmutated") fails
     against the implementation the same track prescribes.
- **Feasibility**: CONSTRUCTIBLE. → A4

### ASSUMPTION CHALLENGES

#### C2 Assumption test: the 8-param `addEdge` with `edgeAlias` exists (Signatures line)
- **Claim**: `MatchPatternBuilder.addEdge(from, to, dir, label, edgeAlias,
  edgeFilter, while_, maxDepth)`.
- **Stress scenario**: compile item 2's call.
- **Code evidence**: landed signature is `addEdge(fromAlias, toAlias, dir,
  edgeLabel, edgeFilter, whileCondition, maxDepth)`
  (`MatchPatternBuilder.java:121-128`); Track 1 Step 3 episode lists the same
  seven.
- **Verdict**: BREAKS. → A1

#### C3 Assumption test: "`SQLMatchPathItem.filter` … edge filtering needs no executor or planner change — only translator-side peek-ahead"
- **Claim**: the filter slot is edge-side as reachable from the current
  builder.
- **Stress scenario**: attach an edge-property WHERE via the landed `addEdge`.
- **Code evidence**: the slot is per-item and filters the item's traversal
  output; for `out/in/both` items that output is vertices
  (`MatchEdgeTraverser.java:361-392,448-450`). Edge-side only via an
  `outE`-form item (`SQLMethodCall.java:30` vocabulary), which the builder
  cannot emit — so "no executor/planner change" holds, "only translator-side
  peek-ahead" does not (builder change required).
- **Verdict**: BREAKS (builder gap), engine claim itself HOLDS. → A1

#### C6 Assumption test: "Track 3 is the first track that wires a boundary step output type at all (`ELEMENT`)"
- **Claim**: `ELEMENT` projection is Track 3 work (In-scope modified:
  `YTDBMatchPlanStep`).
- **Code evidence**: `switch (outputType) { case ELEMENT ->
  projectElement(row, armingGraph) }` → `new YTDBVertexImpl(graph, rawVertex)`
  (`YTDBMatchPlanStep.java:437-481`); Track 2 Outcomes: "Track 2 wires the
  `ELEMENT` boundary and returns results end to end" (A5 reconciliation);
  Track 2 Step 5 smoke tests assert parity through it.
- **Verdict**: BREAKS — already done; the projection is alias-generic, so
  Track 3's vertex-emitting chains need no boundary edit. → A5

#### C7 Assumption test: item 6's "new `WalkerContext` fields (`polymorphic`, …)"
- **Claim**: `polymorphic` is added by Track 3.
- **Code evidence**: `final boolean polymorphic` with walker-owned resolution
  already on the branch (`WalkerContext.java:78-86`,
  `GremlinStepWalker.java:121-127`; commit 4fc1a40c4a).
- **Verdict**: BREAKS — only `edgeFilters` and the alias counters are new;
  the genuinely missing walker deltas are the index-driven loop and the
  `MAX_RECOGNISED_STEPS = 1` gate raise (`GremlinStepWalker.java:79,110`),
  which no item names. → A5

#### C8 Assumption test: the anon-alias facility only needs a context field
- **Claim**: item 6's `anonEdgeAliasGenerator` field is the extent of the
  generator work.
- **Stress scenario**: mint `$g2m_anon_M` (item 1) and `$g2m_edge_N` (item 2)
  and honour the design's collision policy.
- **Code evidence**: Track 2 Decision Log scope-down defers the generator AND
  the reserved-`$` pre-flight to Track 3; the walker has no `getLabels()` scan
  (`GremlinStepWalker.java:102-160`), the only alias is the `$g2m_v0` constant,
  and design §"Anonymous alias generation" requires the pre-flight before any
  recogniser dispatch. None of generator class, vertex-alias counter, or
  pre-flight appears in the Plan of Work or In-scope lists.
- **Verdict**: BREAKS. → A6

#### C9 Assumption test: boundary/return metadata "just works" for chains
- **Claim** (implicit in items 1–2): setting pattern nodes/edges suffices; the
  boundary follows.
- **Stress scenario**: `g.V().out("knows")` — start recogniser pins
  `boundaryAlias=$g2m_v0`, `returnItems=[$g2m_v0 AS $g2m_v0]`
  (`StartStepRecogniser.java:149-151`; Track 2 Step 4 episode: later tracks
  "must keep this contract"). If the hop recogniser only adds the edge, the
  plan projects `$g2m_v0` — start vertices — while the boundary believes it
  emits targets.
- **Code evidence**: `buildResult` packages `ctx.returnItems` as-is
  (`GremlinStepWalker.java:168-192`); nothing recomputes the projection from
  the boundary alias.
- **Verdict**: FRAGILE — works only if every hop recogniser overwrites all
  three boundary fields and replaces the single return entry; unplanned. → A7

#### C10 Assumption test: no `NoOpBarrierStep` lands inside the `outE…inV` claim window
- **Claim** (implicit in item 2's "successive `HasStep`s" peek): the
  multi-step window is barrier-free.
- **Stress scenario**: `g.V().outE(L).has(a).has(b).inV()` under the default
  strategy chain.
- **Code evidence**: fork `LazyBarrierStrategy.apply` bytecode — the
  insertion-eligible branch excludes every `VertexStepContract` with
  `returnsEdge() == true` (offset 216-234 jumps past insertion), so no barrier
  follows `outE/inE/bothE` regardless of the next step; `HasStep` is not a
  `FlatMapStep`, so none follows it either. Between consecutive vertex hops
  (`out().out()`) a barrier IS inserted (eligible branch, next not
  Barrier/Discard/Empty/Profile), which is exactly what item 3's
  `NoOpBarrierRecogniser` handles at top level.
- **Verdict**: HOLDS — and independently confirms item 3 is necessary.

#### C11 Assumption test: MATCH `out('label')` and native `out(label)` resolve edge-label polymorphism identically
- **Claim** (implicit in the equivalence invariant): the two hop primitives
  agree on which edge classes a label matches.
- **Stress scenario**: `KnowsPlus extends Knows`; `g.V().out("knows")` under
  polymorphic on and off.
- **Code evidence**: native routes through `Vertex.getVertices(dir, labels)`
  (`YTDBVertexImpl.java:73-86`); MATCH routes through the SQL `out` function
  (`SQLMethodCall.java:197-209, 238-245`). Both are entity-level traversals,
  but no test on the branch pins their agreement, and the track fixture
  (Knows/Likes/Follows, no subclasses) cannot detect a divergence.
- **Verdict**: FRAGILE — likely holds, unpinned. → A8

#### C12 Assumption test: parallel-edge multiplicity is preserved by MATCH's simple-mode hop
- **Claim**: `g.V().out("knows")` with two parallel knows-edges emits the
  target twice on both pipelines.
- **Code evidence**: simple mode streams the raw link bag with no dedup —
  `traversePatternEdge` executes the method and wraps the iterable
  (`MatchEdgeTraverser.java:531-546`); the `visited` RidSet dedup applies only
  to the recursive WHILE/maxDepth branch (:394-413).
- **Verdict**: HOLDS in code, untested in the fixture — folded into A8's
  fixture suggestion.
