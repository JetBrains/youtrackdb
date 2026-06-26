<!-- MANIFEST
findings: 9   severity: {blocker: 2, should-fix: 4, suggestion: 3}
index:
  - {id: A1, sev: blocker,    loc: SQLParenthesisExpression.java:38-72, anchor: "### A1 ", cert: V1, basis: "Parenthesized arithmetic (a+b)*c is in-subset but log marks all ParenthesisExpression out-of-scope; breaks I1 round-trip parity"}
  - {id: A2, sev: blocker,    loc: SQLBinaryCondition.java:99-109, anchor: "### A2 ", cert: V2, basis: "Comparison parity claimed structural but AST applies collation transform before operator.execute; collated-column comparison diverges"}
  - {id: A3, sev: should-fix, loc: SQLNeOperator.java:23, anchor: "### A3 ", cert: A1, basis: "NE passes null session to QueryOperatorEquals.equals while EQ passes real session; direct static reuse risks parity drift"}
  - {id: A4, sev: should-fix, loc: research-log.md:438-441, anchor: "### A4 ", cert: O1, basis: "OQ6 track decomposition unresolved; the four-track shape is the load-bearing scope decision a plan derives from"}
  - {id: A5, sev: should-fix, loc: SQLMathExpression.java:727-829, anchor: "### A5 ", cert: C1, basis: "Lowerer must reimplement the shunting-yard precedence fold; replicating <= left-assoc + NULL_VALUE sentinel is a parity-fragile second implementation, not the only option"}
  - {id: A6, sev: should-fix, loc: SQLExpression.java:99-119, anchor: "### A6 ", cert: A2, basis: "SQLExpression union has a 'value' fallback path + SimpleNode.value the log's field enumeration omits; field-walk completeness for I2 unverified"}
  - {id: A7, sev: suggestion, loc: SQLMathExpression.java:568-655, anchor: "### A7 ", cert: C2, basis: "D5-R whole-enum lift-and-shift adds no hot-path indirection vs narrow option; static delegation, JIT-inlinable; rationale holds"}
  - {id: A8, sev: suggestion, loc: research-log.md:425-428, anchor: "### A8 ", cert: O2, basis: "OQ2 bind-parameter future shape open, but S0 throws at the leaf; the S0 decision is made, so non-load-bearing"}
  - {id: A9, sev: suggestion, loc: AnalyzedExpr (D1/D2), anchor: "### A9 ", cert: C3, basis: "Sealed + static-dispatch monomorphism rationale holds; no live S0 consumer makes the perf argument aspirational, not measured"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 6}
cert_index:
  - {id: C1, verdict: WEAK, anchor: "#### C1 "}
  - {id: C2, verdict: HOLDS, anchor: "#### C2 "}
  - {id: C3, verdict: HOLDS, anchor: "#### C3 "}
  - {id: V1, verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: V2, verdict: CONSTRUCTIBLE, anchor: "#### V2 "}
  - {id: A1, verdict: BREAKS, anchor: "#### AT1 "}
  - {id: A2, verdict: FRAGILE, anchor: "#### AT2 "}
  - {id: O1, verdict: OPEN-LOADBEARING, anchor: "#### O1 "}
  - {id: O2, verdict: OPEN-NONLOADBEARING, anchor: "#### O2 "}
flags: [CONTRACT_OK]
-->

# Research-log adversarial gate — iteration 1

Verdict: **NEEDS REVISION: 2 blocker, 4 should-fix, 3 suggestion**

Target: `docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md`. Emphasis lenses: Architecture / cross-component coordination, Performance hot path. IDE (mcp-steroid) reachable; `analyzed-expression` project open and matched. All symbol/caller facts below are PSI-grounded (`ReferencesSearch` find-usages); orientation reads via grep.

BLUF: the carried decision set (D1-D9, D5-R, I1-I3) is sound at the *type-design* level — the sealed IR, the whole-enum `NumericOps` lift-and-shift, and the visitor/transform shape all survive. The gate fails on two **lowering-coverage** gaps the re-validation block half-saw but did not fold into the covered-subset definition: parenthesized arithmetic is silently classed out-of-scope (A1), and the "comparison parity is structural" claim ignores the AST's collation transform (A2). Both make I1 round-trip parity unachievable on inputs the log lists as in-subset. The remaining four should-fix items strengthen rationale or close an open question (A3-A6); three suggestions confirm decisions that hold (A7-A9).

## Findings

### A1 [blocker]
**Certificate**: V1 (Violation scenario — I1 round-trip parity on parenthesized arithmetic)
**Target**: Scope and non-goals / Re-validation block "subquery/statement ParenthesisExpression are out of S0 scope (throw)" + Invariant I1
**Challenge**: The re-validation block (research-log.md:393-397) records "`CaseExpression` (CASE WHEN) and subquery/statement `ParenthesisExpression` are out of S0 scope (throw)" and the leaf inventory treats `ParenthesisExpression` as a single throw-shape. That is only half the node. `SQLParenthesisExpression` carries **two** mutually-exclusive fields: `expression` (a parenthesized `SQLExpression` — pure grouping) and `statement` (a subquery). `FirstLevelExpression()` (YouTrackDBSql.jjt:2315) admits `ParenthesisExpression()` as a first-class math leaf, and `SQLParenthesisExpression.execute` (SQLParenthesisExpression.java:38-72) delegates straight to `expression.execute(...)` when `expression != null` — it is a transparent arithmetic wrapper, fully inside the `+ - * /` subset. Parentheses are *the* mechanism a user writes to force grouping (`(a + b) * c`), so a covered subset that throws on every `ParenthesisExpression` cannot round-trip the most common precedence-override expression. A lowerer that throws `UnsupportedAnalyzedNodeException` on `(a+b)*c` violates I1 by construction (the AST evaluates it; the IR refuses to lower it).
**Evidence**: `SQLParenthesisExpression.execute(Result,...)` line 50-52 returns `expression.execute(...)` for the grouping case; only `statement != null` (line 53+) is a subquery. `FirstLevelExpression()` grammar (jjt:2354-2364) wraps `Expression()` inside `<LPAREN>...<RPAREN>`.
**Proposed fix**: Split the `ParenthesisExpression` disposition in the covered-subset wording and in a Decision Log entry: `expression != null` → recurse (`lower(expression)`), `statement != null` (and `CaseExpression`) → throw. Add a round-trip parity case for `(a + b) * c` and `a * (b + c)` to the I1 test plan. This interacts with A5 (the precedence fold) — parentheses and the flat-list fold together determine nesting.

### A2 [blocker]
**Certificate**: V2 (Violation scenario — I1 parity on collated-column comparison)
**Target**: Re-validation block "Comparison shape + reuse point" — "I1 parity for comparisons is structural"
**Challenge**: The log asserts the IR evaluator "reuses `QueryOperatorEquals.equals` for EQ/NE and `SQLBinaryCompareOperator.doCompare` for ordering — both static, both exactly what the AST uses, so I1 parity for comparisons is structural." This is false for any collated column. `SQLBinaryCondition.evaluate(Result, ctx)` (SQLBinaryCondition.java:99-109) does **not** call `operator.execute` on raw operand values — it first computes `collate = left.getCollate(...)` (falling back to `right.getCollate(...)`), and when non-null transforms **both** operands (`leftVal = collate.transform(leftVal)`) before `operator.execute`. `SQLBaseExpression.getCollate` (SQLBaseExpression.java:359-392) returns a non-null `Collate` for a column reference whose property carries a non-default collation (e.g. a case-insensitive `ci` string property). The S0 covered subset explicitly includes "column refs" and comparisons `= != < <= > >=`, so `name = 'Foo'` against a `ci`-collated `name` is in-subset. The AST returns `true` for stored `"foo"`; an IR evaluator that calls `QueryOperatorEquals.equals` (or `doCompare`) on the raw values returns `false`. Round-trip parity (I1) breaks, and because the log declares comparison parity "structural" it would be discovered only when a collated-column test is written — which the parity plan does not yet require.
**Evidence**: `SQLBinaryCondition.java:101-108` (collate fetch + transform). `SQLBaseExpression.getCollate` line 365 returns `identifier.getCollate(...)` for a bare column. The `Identifiable` overload (line 44-67) deliberately skips collation ("No collation guard — the existing overload never applies collation"), so the two AST overloads already disagree on collation — which is exactly why D3's single-`Result` overload must pick the collation-applying path to match the executor's real behavior.
**Proposed fix**: Replace the "parity is structural" claim with a decision that the IR comparison evaluator either (a) replicates the `getCollate` + `transform` step before delegating, or (b) routes through `operator.execute(session, l, r)` *after* the collation transform — and explicitly carve collated-column comparison into the I1 test plan. Prefer routing through the parser `operator` instance over the bare static methods (see A3).

### A3 [should-fix]
**Certificate**: AT1 (Assumption test — equality reuse via the static `QueryOperatorEquals.equals`)
**Target**: Surprises "`QueryOperatorEquals` is the existing equality routine the evaluator should reuse for EQ/NE"
**Challenge**: The reuse pointer is correct that `QueryOperatorEquals.equals(session, l, r)` is the canonical equality routine (PSI: 36 references, including `SQLEqualsOperator`, `SQLNeOperator`, `SQLNeqOperator`, `QueryOperatorIn`, `QueryOperatorContains*`). But reusing the **static method directly** drops a nuance the AST encodes per-operator: `SQLEqualsOperator.execute` calls `QueryOperatorEquals.equals(session, l, r)` with the real session (SQLEqualsOperator.java:27), while `SQLNeOperator.execute` calls `!QueryOperatorEquals.equals(null, l, r)` with a **null** session (SQLNeOperator.java:23). `equals(session, ...)` threads `session` into `PropertyTypeInternal.convert` (QueryOperatorEquals.java:101), so a null session can change conversion behavior for type-coercing comparisons (the "ALL OTHER CASES" branch). An IR evaluator that calls one shared `equals(session, ...)` for both EQ and NE silently diverges from the AST on NE.
**Evidence**: PSI find-usages of `QueryOperatorEquals.equals(DatabaseSessionEmbedded,Object,Object)` = 36 refs across the operator family; `SQLNeOperator.java:23` passes `null`, `SQLEqualsOperator.java:27` passes `session`.
**Proposed fix**: Have the IR comparison evaluator delegate to the parser operator instance's `execute(session, l, r)` (the same object the AST holds), not the bare static method. This makes the EQ/NE session-threading, the ordering `doCompare`-vs-0 mapping, and any future operator nuance structurally identical to the AST — strengthening the "exactly what the AST uses" claim into a real guarantee. Record this as the equality/ordering reuse decision (it also subsumes the A2 collation routing).

### A4 [should-fix]
**Certificate**: O1 (Open-question challenge — OQ6 track decomposition is load-bearing)
**Target**: Open Questions OQ6 "Does the S0 plan still hold on current develop? Re-validate the four-track decomposition…"
**Challenge**: OQ6 is unresolved and bears directly on the artifact that derives next: the track decomposition. The log itself flags (research-log.md:461-464) that the lowering track is heavier than the prior plan assumed (it must carry the precedence fold per A5 and now the parenthesis + collation coverage per A1/A2) and that the `NumericOps` track scope shifted under D5-R (whole-enum, not narrow). A plan derived over an unresolved track shape risks a mis-sized lowering track. Per the gate rule, an unresolved open question bearing on a load-bearing decision is at least a should-fix: it must be resolved into the Decision Log or explicitly waived before a plan derives.
**Evidence**: research-log.md:438-441 (OQ6 text), :461-464 (re-validation update flagging T3 heavier and T2 scope dependent on D5). A1/A2/A5 in this review each enlarge the lowering track beyond the prior sketch.
**Proposed fix**: Resolve OQ6 into a Decision Log entry fixing the track shape (or explicitly defer it to Phase A track-sizing with a written note). At minimum fold the A1 (parenthesis), A2 (collation), and A5 (precedence-fold) coverage into the lowering track's scope indicator so the decomposer sizes it correctly.

### A5 [should-fix]
**Certificate**: C1 (Challenge — lowerer reimplements the precedence fold)
**Target**: Re-validation block "the lowerer must replicate this precedence fold to build a correctly-nested `BinaryOp` IR tree" + Invariant I1
**Challenge**: The chosen approach has the lowerer re-implement the AST's execute-time shunting-yard fold (`calculateWithOpPriority` → `iterateOnPriorities`, SQLMathExpression.java:727-829) at lowering time to produce a nested `BinaryOp` tree, then evaluate the tree. This is a *second* implementation of a subtle algorithm: the `<=` left-associative reduction, the `NULL_VALUE` sentinel that distinguishes a genuine null result from an empty stack (line 733, 746), and the two-pass reverse iteration in `iterateOnPriorities`. A second hand-written implementation is the classic drift surface D5/D5-R were created to avoid for numeric promotion — yet here the same risk is reintroduced for precedence. The best rejected alternative the log does not name: have the lowerer build a **flat** `BinaryOp`-list-equivalent IR node (mirroring `SQLMathExpression`'s `childExpressions` + `operators`) and reuse the *same* priority-fold at IR-evaluate time, or extract the fold into a shared helper (the way `NumericOps` is shared), so the AST and IR fold are one routine. Then precedence parity is structural rather than a re-implementation that must be tested into agreement.
**Evidence**: `calculateWithOpPriority` (SQLMathExpression.java:727-755) and `iterateOnPriorities` (787-829) carry the fold; both use the `NULL_VALUE` sentinel and `getPriority()` `<=` reduction. The fold is private and AST-internal — nothing reuses it today (PSI: `apply(Number,Operator,Number)` has 6 refs, all inside `SQLMathExpression.java`).
**Proposed fix**: Add a Decision Log entry choosing between (a) lower-into-nested-tree by reimplementing the fold (current implicit choice — accept the drift risk, require a precedence-mixing parity matrix `a+b*c`, `a*b+c`, `a-b-c`, `a-b+c` in the I1 tests), or (b) extract/share the fold so AST and IR agree by construction. Either is defensible; the log currently assumes (a) without weighing (b), so the rationale needs strengthening. Decision survives but is under-argued.

### A6 [should-fix]
**Certificate**: AT2 (Assumption test — the `SQLExpression` union field enumeration is complete)
**Target**: Surprises "`SQLExpression` union is wider than the log recorded" (the enumerated field set) + Invariant I2
**Challenge**: The re-validation block lists the `SQLExpression` non-null-one-of fields as `rid / mathExpression / arrayConcatExpression / json / booleanExpression / booleanValue / literalValue / isNull` plus quote flags. The actual class carries an additional inherited `value` field (from `SimpleNode`) and `SQLExpression.execute` has a trailing "old executor" fallback chain (SQLExpression.java:99-119) that reads `value` as `SQLNumber`, `SQLRid`, `SQLMathExpression`, `SQLArrayConcatExpression`, `SQLJson`, `String`, or `Number`. I2 ("a successful `lower(...)` return means full IR coverage of the input") depends on the lowerer's field-walk being **exhaustive over reachable post-parse shapes** — it must throw, never silently mis-read, on any field it does not cover. The log's enumeration omits `value`, so the field-walk completeness argument for I2 is built on an incomplete inventory. If `value` can be non-null after a normal (non-legacy) parse for an in-subset expression, a field-walk that checks only the enumerated fields could read the wrong field or fall through.
**Evidence**: `SQLExpression.java:99-119` (the `value`-based fallback chain, commented "only for old executor"). The log's field list (research-log.md:382-389) does not mention `value`.
**Proposed fix**: Verify via PSI/Read at Phase A whether `value` is ever non-null for an expression produced by the current parser path (vs only the legacy/manually-replaced-params path), and state the answer in the lowering design. If `value` is dead on the modern path, document that the field-walk may ignore it; if reachable, the walk must handle or throw on it. Strengthen the I2 rationale to cite an exhaustive, re-verified field set.

### A7 [suggestion]
**Certificate**: C2 (Challenge — D5-R whole-enum delegation adds hot-path indirection)
**Target**: D5-R (whole-enum `NumericOps` lift-and-shift) — performance-hot-path lens
**Challenge**: The performance-lens worry is that routing all 12 operators' promotion logic through `NumericOps` adds an indirection layer to the live `SQLMathExpression.Operator.apply` math evaluation hot path (the path `iterateOnPriorities` drives). Examined against the code, the worry does not hold. The hot entry is `apply(Object, Object)` (PSI: 24 refs, the dispatch used by `iterateOnPriorities`/`calculateWithOpPriority`) which already calls the shared `apply(Number, Operator, Number)` widening helper (line 576). A lift-and-shift to a `final class NumericOps` with all-static methods turns these into static calls across a class boundary — JIT-inlinable, no new virtual dispatch, no allocation. The delegation `SQLMathExpression.Operator.apply` → `NumericOps` is one extra static frame that the JIT collapses. So D5-R adds no measurable hot-path cost over the narrow Option A, and the cleaner single-home boundary is a real win. Decision holds.
**Evidence**: PSI find-usages — `apply(Object,Object)` 24 refs, `apply(Number,Operator,Number)` 6 refs (all intra-file); all `apply` overloads are static-dispatchable on a `final` extraction target. No polymorphic call site outside `SQLMathExpression`/`MathExpressionTest`.
**Proposed fix**: None required. Optionally note in D5-R that the hot path stays static/inlinable post-extraction so the perf concern is pre-answered, and that the acceptance gate is the existing `MathExpressionTest` (222 lines, PSI-confirmed sole external `apply` caller).

### A8 [suggestion]
**Certificate**: O2 (Open-question challenge — OQ2 bind parameters, non-load-bearing for S0)
**Target**: Open Questions OQ2 "Bind parameters (`?`, `:name`)"
**Challenge**: OQ2 (does a future slice lower bind params to a dedicated `Param` variant or thread bound values at evaluate-time) is genuinely open, but it does **not** gate S0: the S0 decision is already made — lowering throws `UnsupportedAnalyzedNodeException` at the `inputParam` leaf (consistent with I2). The future-slice shape is a non-S0 design question, so per the gate rule it is a suggestion, not a should-fix. No artifact derived now depends on resolving it.
**Evidence**: research-log.md:425-428 (OQ2), :458-460 (OQ2 still open, S0 throws). `SQLBaseExpression.inputParam` leaf (SQLBaseExpression.java:34) is the throw point.
**Proposed fix**: Leave OQ2 open but annotate it explicitly as "out of S0 scope; S0 throws" in the Decision Log so a reader does not mistake it for a blocking gap. (The S0 disposition is decided; only the future shape is open.)

### A9 [suggestion]
**Certificate**: C3 (Challenge — sealed-type monomorphic-dispatch rationale D1/D2)
**Target**: D1 (sealed-interface IR) + D2 (static `switch` dispatch, no `accept`) — performance-hot-path lens
**Challenge**: D1/D2 justify the sealed-type + static-dispatcher design partly on removing "the megamorphic virtual-dispatch cost of an `accept(visitor)` method." With **no live S0 consumer** (the substrate ships behind no flag, S1 wires the first consumer), the dispatch-cost argument is aspirational — there is no hot path exercising the IR in S0, so the perf claim cannot be measured this slice. That said, the decision is correct on type-design grounds independent of perf: exhaustive `switch` over a sealed type gives compile-time variant-coverage (I3), records give value-equality for golden tests, and the static dispatcher keeps nodes pure data. The perf framing is a reasonable forward bet (Calcite `RexShuttle`, Spark `TreeNode` precedent cited in D9), not a load-bearing S0 claim. Decision holds; only the rationale's emphasis is slightly ahead of the evidence.
**Evidence**: research-log.md:56-61 (D1 perf rationale), :79-82 (D2), Initial-request "no live executor consumer in S0." No IR evaluator hot path exists until S1+.
**Proposed fix**: None required. Optionally reframe the D1/D2 perf sentences as "preserves a monomorphic dispatch shape for the S1+ optimizer pipeline" rather than implying a measured S0 win, so the rationale matches the no-consumer reality.

## Evidence base

#### C1 Challenge: precedence fold — lowerer reimplements vs shares the AST fold
- **Chosen approach**: Lowerer replicates the AST's execute-time shunting-yard precedence fold to build a nested `BinaryOp` IR tree; IR evaluator then walks the nested tree.
- **Best rejected alternative**: Build a flat IR node mirroring `childExpressions`+`operators` and share one fold routine (extracted like `NumericOps`) between AST execute and IR evaluate, so precedence parity is structural.
- **Counterargument trace**:
  1. In scenario `a + b * c` (mixed precedence), the AST collects `[a,b,c]`+`[PLUS,STAR]` flat and folds at execute time via `iterateOnPriorities` (SQLMathExpression.java:787-829), reducing by `getPriority()` with `<=` left-assoc and a `NULL_VALUE` sentinel.
  2. The chosen lowerer must reproduce that exact reduction order to nest `BinaryOp(PLUS, a, BinaryOp(STAR, b, c))`; any off-by-one in associativity or sentinel handling yields a differently-nested tree.
  3. A second hand-written fold is exactly the drift surface D5/D5-R eliminated for numeric promotion — reintroduced for precedence, where parity is now test-dependent not structural.
- **Codebase evidence**: `calculateWithOpPriority` (727-755) + `iterateOnPriorities` (787-829); fold is private and AST-internal (PSI: no external reuse).
- **Survival test**: WEAK (decision is defensible but the share-the-fold alternative is unweighed and the parity-matrix test obligation is unstated).

#### C2 Challenge: D5-R whole-enum delegation hot-path indirection
- **Chosen approach**: Move all 12 operators' promotion logic to `final class NumericOps`; `Operator.apply` becomes a thin static delegator.
- **Best rejected alternative**: Narrow Option A (extract only `+ - * /`) to minimize the delegated surface on the hot path.
- **Counterargument trace**:
  1. In scenario evaluating `a * b + c` at runtime, `iterateOnPriorities` calls `Operator.apply(Object,Object)` (the 24-ref hot entry) which calls the shared `apply(Number,Operator,Number)` widening (line 576).
  2. Post-extraction these become static cross-class calls into `NumericOps` — JIT-inlinable, no virtual dispatch, no allocation.
  3. The extra static frame collapses under inlining; no measurable difference from Option A on the hot path.
- **Codebase evidence**: PSI — `apply(Object,Object)` 24 refs, `apply(Number,Operator,Number)` 6 intra-file refs; `final` target makes all calls static.
- **Survival test**: YES (rationale holds; perf concern is pre-answered by the static/inlinable call shape).

#### C3 Challenge: sealed + static-dispatch perf rationale with no S0 consumer
- **Chosen approach**: Sealed interface + static `dispatch` switch, justified partly on avoiding megamorphic `accept` dispatch cost.
- **Best rejected alternative**: Abstract-class + subclasses (codebase incumbent, `SQLBooleanExpression` + 21 subclasses) — keeps per-node virtual dispatch.
- **Counterargument trace**:
  1. In S0 there is no live IR consumer, so no hot path exercises dispatch — the perf delta is unmeasurable this slice.
  2. The incumbent abstract-class pattern would also work for a no-consumer substrate.
  3. But the sealed design wins on compile-time exhaustiveness (I3) and record value-equality independent of perf; the perf framing is a forward bet, not a load-bearing S0 claim.
- **Codebase evidence**: Initial-request "no live executor consumer in S0"; Surprises "No sealed types exist in the codebase yet."
- **Survival test**: YES (type-design rationale holds; perf framing is slightly ahead of evidence but harmless).

#### V1 Violation scenario: I1 round-trip parity breaks on parenthesized arithmetic
- **Invariant claim**: For every SQL fragment in the S0 covered subset, `lower(parse(sql)).evaluate(...)` equals `parse(sql).execute(...)`.
- **Violation construction**:
  1. Start state: input `(a + b) * c` over numeric columns; `a + b` and `* c` are all in the `+ - * /` subset.
  2. Action sequence: parser builds `SQLMathExpression` whose `FirstLevelExpression()` for the first child is a `SQLParenthesisExpression` with `expression != null` (jjt:2315, 2354); AST `execute` delegates through `expression.execute` (SQLParenthesisExpression.java:50-52) and evaluates correctly.
  3. Intermediate state: the lowerer's field-walk hits a `SQLParenthesisExpression`.
  4. Violation point: the log's covered-subset wording classes all `ParenthesisExpression` as out-of-scope → lowerer throws `UnsupportedAnalyzedNodeException` (research-log.md:393-397).
  5. Observable consequence: `lower(parse("(a+b)*c"))` throws where `parse("(a+b)*c").execute(...)` returns a value — I1 is unsatisfiable for this in-subset input.
- **Feasibility**: CONSTRUCTIBLE (real grammar path, real execute delegation).

#### V2 Violation scenario: I1 parity breaks on collated-column comparison
- **Invariant claim**: comparison round-trip parity is "structural" because both layers use `QueryOperatorEquals.equals` / `doCompare`.
- **Violation construction**:
  1. Start state: property `name` has a case-insensitive collation; stored value `"foo"`; expression `name = 'Foo'` (in-subset: column ref + EQ + string literal).
  2. Action sequence: AST `SQLBinaryCondition.evaluate(Result,ctx)` computes `collate = left.getCollate(...)` non-null (SQLBaseExpression.java:359-365), transforms both operands (`"foo"`/`"foo"`), then `operator.execute` → `QueryOperatorEquals.equals` → `true` (SQLBinaryCondition.java:101-109).
  3. Intermediate state: IR evaluator (per the "structural" plan) calls `QueryOperatorEquals.equals(session, "foo", "Foo")` on raw values.
  4. Violation point: no collation transform applied → `"foo".equals("Foo")` path → `false`.
  5. Observable consequence: AST `true`, IR `false`; I1 broken for a collated in-subset comparison.
- **Feasibility**: CONSTRUCTIBLE (collation is a live property attribute; the AST transform is unconditional when `getCollate` is non-null).

#### AT1 Assumption test: equality reuse via the bare static `QueryOperatorEquals.equals`
- **Claim**: The IR evaluator can reuse `QueryOperatorEquals.equals` directly for both EQ and NE and match the AST.
- **Stress scenario**: A type-coercing NE comparison where session-dependent conversion matters (`PropertyTypeInternal.convert` reads `session`, QueryOperatorEquals.java:101).
- **Code evidence**: `SQLNeOperator.execute` passes `null` session (SQLNeOperator.java:23); `SQLEqualsOperator.execute` passes the real session (SQLEqualsOperator.java:27). A single shared `equals(session, ...)` for both diverges from the AST's NE-with-null-session.
- **Verdict**: FRAGILE (holds for the common non-coercing case; breaks where session-threaded conversion differs — fixed cleanly by delegating to `operator.execute`).

#### AT2 Assumption test: the `SQLExpression` union field enumeration is complete for the I2 field-walk
- **Claim**: The field-walk over the enumerated union fields is exhaustive, so a successful `lower` means full coverage (I2).
- **Stress scenario**: An expression whose payload lands on the inherited `value` field or the "old executor" fallback chain rather than the enumerated typed fields.
- **Code evidence**: `SQLExpression.execute` lines 99-119 read `value` as `SQLNumber`/`SQLRid`/`SQLMathExpression`/`SQLArrayConcatExpression`/`SQLJson`/`String`/`Number` — none in the log's enumerated set (research-log.md:382-389).
- **Verdict**: FRAGILE (the walk is likely complete on the modern parse path, but the `value` field is un-inventoried; completeness for I2 is asserted over an incomplete field list and must be re-verified).

#### O1 Open-question challenge: OQ6 track decomposition is load-bearing
- **Question**: Does the four-track decomposition still hold on current develop?
- **Load-bearing?**: YES — the next artifact (plan + track files) derives the track shape directly; the log itself flags T3 (lowering) heavier and T2 (`NumericOps`) scope shifted under D5-R.
- **Grade**: should-fix — must be resolved into the Decision Log or explicitly deferred to Phase A track-sizing with a written note.

#### O2 Open-question challenge: OQ2 bind parameters
- **Question**: Future-slice lowering shape for bind params (`Param` variant vs evaluate-time threading)?
- **Load-bearing?**: NO for S0 — the S0 disposition (throw at the `inputParam` leaf) is already decided and consistent with I2; only the future shape is open.
- **Grade**: suggestion — annotate as out-of-S0-scope to avoid being mistaken for a gap.
