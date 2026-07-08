<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 2, suggestion: 4}
index:
  - {id: CQ1, sev: should-fix, loc: GremlinPatternAssembler.java:87, anchor: "### CQ1 ", cert: n/a, basis: "orphaned Javadoc documents the wrong method; rePinBoundaryToTarget left undocumented"}
  - {id: CQ2, sev: should-fix, loc: GremlinStepWalker.java:108, anchor: "### CQ2 ", cert: n/a, basis: "reserved-$ namespace guard duplicated as two independent constants; drift risk on a correctness guard"}
  - {id: CQ3, sev: suggestion, loc: EdgeStepRecogniser.java:83, anchor: "### CQ3 ", cert: n/a, basis: "recognize() ~90 lines with nested peek-ahead loop exceeds method-length guideline"}
  - {id: CQ4, sev: suggestion, loc: VertexStepRecogniser.java:80, anchor: "### CQ4 ", cert: n/a, basis: "single-edge-label validation duplicated across the two vertex-step recognisers"}
  - {id: CQ5, sev: suggestion, loc: EdgeStepRecogniserTest.java:305, anchor: "### CQ5 ", cert: n/a, basis: "recogniser test fixture helpers copy-pasted across three test classes"}
  - {id: CQ6, sev: suggestion, loc: MatchClassFilters.java:37, anchor: "### CQ6 ", cert: n/a, basis: "MatchClassFilters ships with no production caller in this track"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### CQ1 [should-fix] Orphaned Javadoc in GremlinPatternAssembler — re-pin doc documents the wrong method

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPatternAssembler.java` (line 87-100)

**Issue**: Two Javadoc blocks are stacked back-to-back. The first (lines 87-91) reads "Re-pins the boundary metadata and replaces the single RETURN column…" — that describes `rePinBoundaryToTarget`. The second (lines 92-100) describes `toBuilderDirection`, which is the method that immediately follows at line 101. Java (and the Javadoc tool) attach only the immediately-preceding comment to a member, so the 87-91 block is a dead/dangling comment, and `rePinBoundaryToTarget` (line 109) is left with no Javadoc at all. This is a leftover from reordering the methods: the re-pin doc got separated from its method. CLAUDE.md's "keep comments in sync" rule applies — a comment attached to the wrong method is worse than none.

**Suggestion**: Move the 87-91 Javadoc block down to sit directly above `rePinBoundaryToTarget` at line 109, leaving `toBuilderDirection` with its own single (92-100) block.

### CQ2 [should-fix] Reserved-`$` namespace guard duplicated as two independent constants

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinStepWalker.java` (line 108) and `.../GremlinPredicateAdapter.java` (line 68)

**Issue**: `private static final String RESERVED_ALIAS_PREFIX = "$";` is declared independently in both `GremlinStepWalker` (guards `as(...)` labels in the pre-flight scan) and `GremlinPredicateAdapter` (guards `has(...)` property keys). Both back the same reserved-namespace guard — the load-bearing correctness fix that keeps a `$`-prefixed identifier from being resolved as a query context variable. The two also share intent with `WalkerContext.ANON_VERTEX_ALIAS_PREFIX` / `EDGE_ALIAS_PREFIX`, which both begin with `$`. `GremlinPredicateAdapter`'s own Javadoc already calls itself a mirror of `GremlinStepWalker.RESERVED_ALIAS_PREFIX`, confirming these are meant to be one value. With no single source of truth, changing the reserved sentinel in one guard but not the other silently splits the guard across the two input surfaces (labels vs keys) it is supposed to cover uniformly.

**Suggestion**: Define the reserved prefix once (e.g. a `RESERVED_ALIAS_PREFIX` on `WalkerContext`, which already owns the `$g2m_` prefixes) and reference it from both the walker scan and the predicate adapter.

### CQ3 [suggestion] EdgeStepRecogniser.recognize is ~90 lines with a nested peek-ahead loop

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeStepRecogniser.java` (line 83-181)

**Issue**: `recognize` runs roughly 90 lines and mixes three concerns: the up-front shape gates, the `while` peek-ahead scan (with a nested `for` over `HasContainer`s) that accumulates edge filters and locates the closing hop, and the commit block that mints aliases and calls the assembler. That exceeds the ~40-line / deep-nesting guideline and makes the no-mutation-on-decline boundary harder to verify at a glance (every early `return false` must be confirmed to precede any mutation).

**Suggestion**: Extract the peek-ahead scan into a small helper that returns the accumulated filters plus the closing direction (or a decline signal), leaving `recognize` as gates → scan → commit. The helper is also independently unit-testable.

### CQ4 [suggestion] Single-edge-label validation duplicated across the two vertex-step recognisers

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/VertexStepRecogniser.java` (line 80) and `.../EdgeStepRecogniser.java` (line 97)

**Issue**: Both recognisers repeat the same guard trio: `boundaryAlias == null` decline, `getEdgeLabels().length != 1` decline, and `edgeLabel == null || edgeLabel.isBlank()` decline. The logic and the intent (single non-blank label only) are identical; the two copies can drift as the label rules evolve.

**Suggestion**: Extract a shared helper such as `singleEdgeLabelOrNull(VertexStep)` (returning the validated label or `null` to decline) used by both recognisers.

### CQ5 [suggestion] Recogniser test fixtures copy-pasted across three test classes

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeStepRecogniserTest.java` (line 305), plus `VertexStepRecogniserTest.java` and `NoOpBarrierRecogniserTest.java`

**Issue**: `contextWithStartBoundary(...)` is duplicated near-verbatim in three recogniser test classes; `assertContextUnmutated(...)` in two; `withNonPolymorphicDefault(...)` in `EdgeTraversalEquivalenceTest` and `GremlinStepWalkerTest`; and the translator on/off toggle helpers (`setTranslatorEnabled` / `withTranslatorDisabled`) recur across the fixture files. As more recognisers land in Tracks 4-6 this fixture will be re-copied again, and a change to the pre-seeded boundary shape must be applied in every copy.

**Suggestion**: Lift the shared fixture into a small test base (or a package-private test util) — a `contextWithStartBoundary`, `assertContextUnmutated`, and the config-toggle helpers — that the recogniser test classes reuse.

### CQ6 [suggestion] MatchClassFilters ships with no production caller in this track

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/MatchClassFilters.java` (line 37)

**Issue**: `MatchClassFilters` has no production call site — grep finds only `{@link}` references in sibling Javadoc and its own unit test; its first real caller is the folded `hasLabel` recogniser planned for Track 4. Shipping unused production code ahead of its consumer is a maintainability smell: a reader has to reconstruct why the class exists, and its behavioural contract is fixed only by the test rather than by a caller. (grep-only: mcp-steroid was unreachable, so this "no production caller" claim rests on a textual search that cannot see reflective or generated dispatch — none is expected in this package, but the caveat stands.)

**Suggestion**: This is plan-sanctioned (Track 3 Plan of Work item 4 / Concrete Step 2 explicitly scope the helper here for Track 4's `hasLabel`), so the low-friction resolution is to accept it. If the team prefers no forward-scaffolding, move `MatchClassFilters` and its test to the Track 4 commit that first calls it.

## Evidence base
