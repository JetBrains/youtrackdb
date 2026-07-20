<!-- MANIFEST
findings: 2   severity: {blocker: 1, should-fix: 1, suggestion: 0}
index:
  - {id: BG1, sev: blocker, loc: "SubTraversalPredicateAdapter.java:245-247 (appendPattern); AndStepRecogniser.java:61-66 (hasEdges branch); locked by SubTraversalPredicateAdapterTest.java:256", anchor: "### BG1 ", cert: C1, basis: "appendPattern merges hop edges into an enclosing adapter but never flips hasEdges, so nested and(and(out,…), …) is misclassified pure-filter and commitPureFilterChild drops the hops — silent over-large MATCH results"}
  - {id: BG2, sev: should-fix, loc: "OrStepRecogniser.java:38-43 (putAliasFilter only); ConnectiveStepSupport.java:30-36 (re-type commit lives only on AND pure-filter path); HasStepRecogniser.java:156-168", anchor: "### BG2 ", cert: C2, basis: "OR commits only the out-of-band boolean and never applies child hasLabel boundary re-types; under polymorphic mode classEquals is omitted from WHERE, so or(hasLabel(A).has(…), hasLabel(B).has(…)) loses label discrimination"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 4}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: MATCHES, anchor: "#### C3 "}
  - {id: C4, verdict: MATCHES, anchor: "#### C4 "}
  - {id: C5, verdict: MATCHES, anchor: "#### C5 "}
  - {id: C6, verdict: MATCHES, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
flags: [CONTRACT_OK, MCP_STEROID_UNAVAILABLE]
-->

Step-2 bugs review (`deff77513b..2b9e22dc07`). mcp-steroid was unreachable; symbol/caller claims used grep + direct reads — reference-accuracy caveat on any finding that leans on a caller set.

## Findings

### BG1 [blocker]
**Certificate**: C1
**Location**: `SubTraversalPredicateAdapter.appendPattern` (SubTraversalPredicateAdapter.java:245-247); consumed by `AndStepRecogniser.walkAndCommit` (AndStepRecogniser.java:61-66) via `ConnectiveStepSupport.commitEdgeBearingChild` → `ctx.appendPattern`. Regression lock-in: `SubTraversalPredicateAdapterTest.appendPattern_mergesCapturedHopIntoAdapter` (SubTraversalPredicateAdapterTest.java:256) asserts `hasEdges()` stays false after merging a one-edge fragment.
**Issue**: Edge-bearing classification is `hasEdges`, flipped only by `addEdge` / `addEdgeAsNode`. Nested `AndStep` children do not call those on the enclosing adapter: the inner `AndStepRecogniser` walks grandchild hops (which flip `hasEdges` on *fresh* grandchild adapters), then commits with `commitEdgeBearingChild(enclosingAdapter, grandchild)` → `enclosingAdapter.appendPattern(...)`. `appendPattern` only runs `capturedPattern.appendFrom(captured)` and leaves `hasEdges` false even when the merged fragment has edges.

The outer `AndStepRecogniser` then reads `adapter.hasEdges() == false` and takes `commitPureFilterChild`, which copies alias filters and `registeredAliasClasses()` via `addNode` but never appends edges. Concrete failure:

`g.V().and(__.and(__.out("a"), __.out("b")), __.has("age", P.eq(30)))`

- Inner `and(out(a), out(b))` correctly builds two hops in the middle adapter's pattern buffer.
- Middle adapter stays `hasEdges == false`.
- Outer commit drops both hops; at best it re-registers orphan target aliases without edges.
- MATCH keeps vertices that fail the native `and(out(a), out(b))` filter (too-large multiset). Flat `and(out(a), out(b))` is fine because each child adapter receives `addEdge` directly — only the nested combinator path is wrong.

**Refutation considered**: (1) Does `ConnectiveStrategy` flatten nested `AndStep` so this never reaches production? No — that strategy turns infix `and()` markers into `AndStep`; `__.and(__.and(...), ...)` already is nested `AndStep` children and stays nested (C7). (2) Could `commitPureFilterChild` still ship the hops via `registeredAliasClasses`? No — that path only `addNode`s classes; topology lives in `pattern.numOfEdges` / node `out` lists that pure-filter commit never copies. (3) Is the unit test proving intentional non-flip? It asserts the broken classification while also asserting one edge landed in the buffer — that locks the bug in rather than documenting a safe invariant. **Verdict: CONFIRMED.**
**Reference-accuracy caveat**: mcp-steroid PSI unavailable. `appendPattern` call sites were grepped under `**/gremlin/translator/strategy/**` plus a direct read of `ConnectiveStepSupport.commitEdgeBearingChild` and both `RecognitionContext` implementations; a missed caller would not remove this nested-AND path.
**Suggestion**: In `SubTraversalPredicateAdapter.appendPattern`, after `appendFrom`, set `hasEdges = true` when the source fragment contributed any edge (e.g. `source` edge count &gt; 0, or `capturedPattern` edge count increased). Update `appendPattern_mergesCapturedHopIntoAdapter` to expect `hasEdges() == true`. Add an `AndStepRecogniser` / equivalence case for `and(and(out(a), out(b)), has(...))`.

### BG2 [should-fix]
**Certificate**: C2
**Location**: `OrStepRecogniser.recognize` (OrStepRecogniser.java:38-43); contrast `ConnectiveStepSupport.commitPureFilterChild` (ConnectiveStepSupport.java:30-36), which AND uses to apply `registeredAliasClasses` re-types; producer `HasStepRecogniser` (HasStepRecogniser.java:156-168).
**Issue**: OR correctly builds one `MatchWhereBuilder.or(...)` expression and commits it once via `putAliasFilter` (avoids per-child AND-composition). It never commits the child's captured pattern re-types. Under polymorphic mode, `hasLabel(L)` is re-type-only (`ctx.addNode(boundary, L)` with no `classEquals` in WHERE). So a child like `__.hasLabel("Person").has("age", 30)` captures a Person re-type in `capturedPattern` and an age filter in `capturedAliasFilters`; OR keeps only the age (and sibling) predicates.

Failure shape: `g.V().or(__.hasLabel("Person").has("age", 30), __.hasLabel("Company").has("age", 40))` with `polymorphic == true` translates to a boundary WHERE roughly `(age = 30) OR (age = 40)` on an un-narrowed `V` start — Company/age=30 and Person/age=40 both survive, unlike native. Non-polymorphic mode is mostly safe because `classEquals` is folded into each child's captured filter and therefore into the OR expression; the missing re-type then matters less for result membership.

**Refutation considered**: (1) Does OR decline any child that carried a re-type? No — `collectOrExpressions` only requires `!hasEdges` and exactly one boundary filter; a hasLabel+has child is accepted. (2) Is polymorphic OR-of-hasLabel out of scope? Track C&amp;O / focus item 5 call out hasLabel re-type commit for pure-filter AND/OR children; AND implements it, OR does not. (3) Could a single MATCH node not accept two re-types anyway? Correct for hasLabel-only OR arms (and those often decline today via empty filter) — but mixed hasLabel+property arms need the class predicate inside each OR operand, which polymorphic mode currently leaves only as a discarded re-type. **Verdict: CONFIRMED.**
**Reference-accuracy caveat**: mcp-steroid unavailable; HasStepRecogniser polymorphic branch was read directly. Grep was used only to confirm OrStepRecogniser has no `addNode` / `registeredAliasClasses` usage.
**Suggestion**: For each accepted OR child, either (a) fold the child's boundary re-type into that child's OR operand as `classEquals` (even when the walk is polymorphic), or (b) decline OR when any pure-filter child has a non-empty `registeredAliasClasses` entry that did not produce a boundary WHERE — never accept and drop the re-type. Mirror AND's `commitPureFilterChild` re-type handling where it is expressible as a boolean operand.

## Evidence base

#### C1: appendPattern does not flip hasEdges → nested AND edge-bearing child misclassified (BG1)
- CONFIRMED-as-issue: `appendPattern` (:245-247) merges edges without setting `hasEdges`; `AndStepRecogniser` (:61-66) keys commit policy on `hasEdges`; nested `and(and(out,…), …)` therefore takes `commitPureFilterChild` and drops hops. Unit test (:256) asserts the false classification. See BG1 body; reference-accuracy caveat applies.

#### C2: OrStepRecogniser commits OR boolean only — polymorphic hasLabel re-types never applied (BG2)
- CONFIRMED-as-issue: OR (:38-43) only `putAliasFilter`s the merged expression; re-type commit exists solely on AND's `commitPureFilterChild` (:30-36); polymorphic `hasLabel` (:156-168) emits re-type without `classEquals`. See BG2 body; reference-accuracy caveat applies.

#### C3: OR does not AND-compose via repeated putAliasFilter
- **Hypothesis**: OR might call `putAliasFilter` once per child and AND-compose siblings.
- **Refutation**: `collectOrExpressions` accumulates `SQLBooleanExpression`s out-of-band and returns one `WHERE.or(...)` (or a single expr); `OrStepRecogniser` performs exactly one `putAliasFilter(boundary, wrap(merged))`. No per-child put on the OR path.
- **Verdict**: MATCHES — focus item 1 holds.

#### C4: flat and(out(a), out(b)) alias isolation
- **Hypothesis**: Sibling hop children could collide on `$g2m_anon_0`.
- **Refutation**: Each child adapter delegates `nextAnonVertexAlias` to the shared parent `AliasSequence`; `AndStepRecogniserTest.edgeBearingChildren_appendsDistinctHopAliases` and `EdgeTraversalEquivalenceTest.andTwoOutHops_differingTargets_matchesNative` pin distinct aliases and native multiset equality for the flat shape.
- **Verdict**: MATCHES — focus item 6 holds for the flat (non-nested) case. Nested edge AND is BG1, not an alias-counter bug.

#### C5: top-level bare outE still declines; sub-walk singleton returnsEdge→hop allowed
- **Hypothesis**: VertexStepRecognizer / VertexHopRecogniser changes could translate top-level `outE(L)`.
- **Refutation**: Router sends returnsEdge-without-following-HasStep to `VertexHopRecogniser`; that path declines when `!(ctx instanceof SubTraversalPredicateAdapter)` (VertexHopRecogniser.java:66-72). Top-level ctx is `WalkerContext` → DECLINE. Sub-walk adapter ctx accepts the AdjacentToIncident singleton artifact.
- **Verdict**: MATCHES — focus item 4 holds. (`VertexStepRecogniserTest.bareEdgeTerminal_routesToEdgeHopRecogniserAndDeclines` still passes on outcome equality but its path claim is stale — both paths decline.)

#### C6: AND pure-filter hasLabel re-type is committed
- **Hypothesis**: AND might drop hasLabel re-types the way OR does.
- **Refutation**: `commitPureFilterChild` iterates `capturedPattern().registeredAliasClasses()` and `ctx.addNode(...)`. Step-1 hop-only `hasEdges` fix keeps hasLabel children on this path.
- **Verdict**: MATCHES — focus item 5 holds for AND; OR gap is BG2.

#### C7: ConnectiveStrategy flattens nested AndStep so BG1 is unreachable
- **Hypothesis**: Production strategies rewrite `and(and(out,out), has)` into a flat `AndStep` with three children, so `appendPattern` into an enclosing adapter never runs.
- **Refutation**: `ConnectiveStrategy` (decoration) converts infix connective markers into `AndStep`/`OrStep`; it does not flatten an already-nested `__.and(__.and(...), ...)` tree. Nested `AndStep` remains a first-class child of the outer `AndStep`, and `AndStepRecogniser` is registered for both levels — the enclosing commit ctx is a `SubTraversalPredicateAdapter` whose `appendPattern` is exactly the BG1 path. Step-1 episode text and adapter Javadoc already describe nested `and(and(...), ...)` as a supported recursive `walkChild` shape.
- **Verdict**: REFUTED — nested AND is reachable; BG1 stands.
