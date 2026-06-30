<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
# Track 4: Evaluator + round-trip parity

## Purpose / Big Picture
After this track lands, a lowered `AnalyzedExpr` tree can be evaluated to a value by
`AnalyzedExprEvaluator`, and the round-trip parity suite proves that value equals the
AST's own `execute` result for every covered SQL fragment. This suite is S0's whole
acceptance bar — there is no other consumer to validate against.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Track 4 adds the `AnalyzedExprVisitor`-based evaluator — arithmetic via the shared
`NumericOps`, comparison by replicating the AST's exact `SQLBinaryCondition.evaluate`
sequence — and the round-trip parity test suite that is S0's whole acceptance
criterion. It depends on Track 1 (IR types), Track 2 (`NumericOps`), and Track 3
(lowering, to produce trees to evaluate).

The evaluator is the runtime over the analyzed IR: it walks an `AnalyzedExpr` tree and
produces a value. It implements `AnalyzedExprVisitor<Object>` directly — so the compiler
forces it to enumerate every variant (invariant I3). It has two load-bearing mechanisms.
First, arithmetic delegates to the shared `NumericOps`, so AST/IR arithmetic cannot drift.
Second, the comparison path replicates the AST's exact sequence, so parity is structural —
the IR runs the same code the AST runs.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-06-29T17:08Z [ctx=info] Review + decomposition complete

## Surprises & Discoveries
- **Phase A review (2026-06-29): the evaluator's three reuse seams need integration-shape detail
  the original Plan of Work left implicit.** All three reviewers (technical, risk, adversarial)
  converged, PSI-grounded, on the same gaps — 0 blockers, all should-fix/suggestion, folded into
  the Plan of Work, the Validation matrix, and the live Decision Log:
  - **Arithmetic** reaches `NumericOps` through the AST `SQLMathExpression.Operator.apply(Object,
    Object)` after an IR→AST enum map the track authors; calling `NumericOps.apply(Number,
    Operator, Number)` directly throws on null and skips `Date ± Long` / `String` concat.
  - **Comparison** reconstructs a fresh `SQLBinaryCompareOperator` per IR enum constant via
    `new SQLXxxOperator(-1)` (the IR carries only the enum; `INSTANCE` is absent on
    `SQLNeOperator`/`SQLNeqOperator`/`SQLGeOperator`). Parity holds by class identity since the
    operators are stateless.
  - **Collate** mirrors the guarded `SQLSuffixIdentifier.getCollate` — `isEntity()` guard,
    `EntityImpl` downcast, collate off `SchemaClass.getProperty(name)`, null on any miss.
  - **`visitFuncCall`** is in-subset (the Track 3 lowerer emits `FuncCall`) but had no matrix
    row; added one plus the `SQLEngine.getMethod`→`SQLMethod.execute` sequence.
- **Phase-4 `design-final` reconciliation items (frozen `design.md`, not edited now).** The live
  track Decision Log was corrected; the same passages in the frozen `design.md` carry the
  superseded shapes and join the Track-3 reconciliation backlog (D14 `literalValue` wording,
  design.md:469 method names):
  - `design.md` class diagram (≈138-141) names `NumericOps.apply(Number, BinaryOperator, Number)`
    — that signature does not exist; the parity-faithful entry is the AST `Operator.apply(Object,
    Object)`.
  - `design.md` (≈679/712/714) says the comparison evaluator delegates to "the same operator
    object the AST holds" — false by construction; it builds a fresh instance of the same class.
  - `design.md` (≈717/732) gives the collate chain off the `Entity` interface
    (`getImmutableSchemaClass`/`getProperty`) — those live on `EntityImpl` / `SchemaClass`.
  - D15's "~12 callers of `evaluate(Identifiable)`" attaches the count to the base
    `SQLBooleanExpression` dispatch surface, not the concrete override (0 direct callers).

## Decision Log
<!-- Full inline Decision Records this track owns (four-bullet form). One block per decision: -->

#### D3: Single `evaluate(Result, CommandContext)` overload
- **Alternatives considered**: dual `(Result, …)` + `(Identifiable, …)` overloads matching
  the AST's shape; a unified `AnalyzedExprInput` wrapper abstraction. (`Result` is a
  query-result row exposing typed columns; `Identifiable` is a bare record reference — a
  RID.)
- **Rationale**: the evaluator exposes one `evaluate(expr, row, ctx)` overload over
  `Result`. The AST carries dual overloads for historical reasons (PSI-confirmed:
  `SQLBinaryCondition` has both `evaluate(Identifiable, ctx)` and `evaluate(Result, ctx)`),
  and only the `Result` overload applies collation — the `Identifiable` overload's skip is a
  deliberate AST inconsistency (D15). The analyzed tree is greenfield and serves
  higher-layer callers (executor steps, optimizer passes) that already operate on `Result`,
  so a single `Result` overload keeps every visitor from implementing two paths and unifies
  the inconsistency. An `Identifiable`-only caller arriving in S1+ wraps its input in a
  synthetic entity-backed `Result` via a small adapter helper, so it too evaluates through
  the collation-applying path.
- **Risks/Caveats**: the adapter allocates a synthetic `Result` per `Identifiable` call —
  trivial, and a hot path that later cannot tolerate the wrap can grow a second path without
  breaking existing code. The behavioral consequence (collation now applies on the
  `Identifiable` path) is the deliberate convergence recorded in D15, not an incidental side
  effect.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"Evaluator interface: single Result overload" -->

#### D11: IR comparison evaluator replicates `SQLBinaryCondition.evaluate` — collate transform plus the parser-operator instance, not bare statics
- **Alternatives considered**: bare static `QueryOperatorEquals.equals` +
  `SQLBinaryCompareOperator.doCompare` (drops collation and the NE null-session nuance — the
  blocker/should-fix that surfaced this); excluding collated columns from the subset
  (collation is a per-property attribute, not syntactic, so it cannot be excluded at the
  expression level).
- **Rationale**: the IR comparison evaluator reproduces `SQLBinaryCondition.evaluate(Result,
  ctx)`'s exact four-step sequence — evaluate both operands, fetch the collate
  left-then-right, apply the collate transform when non-null, delegate to a freshly built
  `SQLBinaryCompareOperator` of the same concrete class the AST uses for the operator (the IR
  `BinaryOp` carries only the enum constant — the lowerer discarded the AST operator instance —
  so the evaluator reconstructs one per enum constant) — rather than the bare static routines,
  because two AST nuances break parity otherwise. **Collation** is a per-property transform (a `ci` property
  compares `'Foo'` and `'foo'` as equal) that a raw static `equals` skips. **Session
  threading**: EQ calls `QueryOperatorEquals.equals` with the real session while NE passes a
  `null` session, changing how the cross-type coercion (`PropertyTypeInternal.convert`)
  resolves, so EQ and NE can differ on mixed-type operands. Delegating to a reconstructed
  operator instance of the right concrete class runs the AST's exact branch per operator,
  reproducing both nuances by construction — the operators are stateless, so a fresh instance is
  behaviorally identical to the AST's, and `INSTANCE` is absent on `SQLNeOperator` /
  `SQLNeqOperator` / `SQLGeOperator`, so the evaluator uses the `new SQLXxxOperator(-1)`
  constructor (ordering operators carry too: their shared `doCompare` returns a sign each maps
  against 0).
  The slow path is the parity reference — the in-place fast path (`tryInPlaceComparison`)
  returns the `FALLBACK` sentinel whenever collation or coercion could change the result and
  defers to the slow path, so the IR need not encode it. Full worked passages are in
  `design.md §"Comparison: replicate the AST sequence"`.
- **Risks/Caveats**: because the IR holds a `Var` — a lexical name path in the IR, holding
  no parse-node reference — not an `SQLExpression`, it cannot call the parser's
  `getCollate`; it re-implements the single-property resolution
  directly (see D6-R). Collation cannot be excluded at the expression level — it is a
  per-property attribute — so the IR must reproduce the collate transform. The S0 evaluator
  is slow-path-only by design (D16); that scope is S0-specific.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"Comparison: replicate the AST sequence" -->

#### D15: The AST `evaluate(Identifiable)` collation skip is a deliberate AST inconsistency the analyzed layer unifies; collation applies uniformly via the single `Result` overload
- **Alternatives considered**: keep the "a bare `Identifiable` has no schema context"
  justification (factually wrong — a DD reviewer flagged it); preserve the AST `Identifiable`
  skip in the S1+ adapter for strict behavioral parity (rejected — the umbrella's goal is
  one correct analyzed layer; perpetuating a deliberate-but-inconsistent skip defeats it).
- **Rationale**: the AST's `SQLBinaryCondition.evaluate(Identifiable, ctx)` overload skips
  collation — it goes straight to `operator.execute(...)` and never calls `getCollate` —
  while the `evaluate(Result, ctx)` overload applies it. Inline author comments mark the skip
  as intentional fast-path design. The skip is **not** a missing-context limitation: an
  `Identifiable` is a record reference (a RID), and loading it yields an `EntityImpl` whose
  `getImmutableSchemaClass` → `getProperty` → `getCollate` chain is exactly what the
  `Result` resolution consumes, so the schema context is recoverable; the AST overload simply
  never attempts it. The analyzed layer unifies this inconsistency by applying collation
  uniformly through the one `Result` overload (D3): when S1+ wraps an `Identifiable`-only
  caller in a synthetic entity-backed `Result`, collation is applied on that path too. This
  is a recorded correctness convergence, not a defect fix.
- **Risks/Caveats**: `evaluate(Identifiable)` is not a rare caller. PSI find-usages returns
  ~12 production callers on the base `SQLBooleanExpression.evaluate(Identifiable, ctx)` dispatch
  surface (the concrete `SQLBinaryCondition` override has 0 direct callers — the count belongs to
  the polymorphic base method, so the S1/S7 re-verification must search the base method, not the
  override, where it would find zero and wrongly conclude the convergence is dead), including
  `SQLWhereClause` and `SecurityEngine` (the component that evaluates access-control predicates). So the S1+ convergence is an observable behavior
  change: a `ci`-collated comparison begins matching case-insensitively where the AST
  `Identifiable` path did not. Concretely, a previously case-sensitive `name = 'admin'`
  security check against a `ci`-collated `name` would begin matching `'Admin'` once
  `SecurityEngine` runs through the unified path — which is exactly why the S1/S7 validation
  obligation below exists. S0 is unaffected — its round-trip tests use `Result` rows, which
  already apply collation — so this is a framing correction plus an S1+ forward commitment.
  S1 (FilterStep/WHERE, YTDB-916) and S7 (`SecurityEngine`, YTDB-922) must validate the ~12
  callers — especially the security-comparison change — before wiring the `Identifiable`
  path.
- **Implemented in**: this track records the convergence (the single `Result` overload that
  realizes it); the cross-caller validation is an S1+/S7 obligation, not S0 work.
<!-- **Full design**: design.md §"Comparison: replicate the AST sequence" / §"Evaluator interface: single Result overload" -->

#### D16: "Fast path need not mirror" is scoped to S0; the S1+ evaluator must reproduce the AST evaluation fast paths on the hot path
- **Alternatives considered**: pull the fast path into S0's evaluator now (rejected — S0 has
  no consumer, so it is dead optimization with no parity or perf signal to validate it);
  leave the claim flat / unscoped (rejected — it is the source of a latent S1 regression).
- **Rationale**: the claim that the comparison fast path (`tryInPlaceComparison`) "is an
  AST-internal optimization the IR need not mirror" is correct *for S0* — S0 ships with no
  live executor consumer, so its only acceptance is round-trip parity and there is no
  production path to regress — but it is not a permanent property of the evaluator. When S1
  (YTDB-916) lowers `SQLWhereClause` and `FilterStep` evaluates `AnalyzedExpr` on the hot
  path, the analyzed evaluator must reproduce the AST evaluation fast paths or it regresses
  the most common filter shapes. Two fast paths:
    - **In-place comparison.** `tryInPlaceComparison` →
      `EntityImpl.isPropertyEqualTo`/`comparePropertyTo` avoids property deserialization for
      `property <op> constant`. It is also the parity-preserving seam: it returns `FALLBACK`
      whenever collation or coercion could change the result and falls through to the slow
      path. So the S1 equivalent must keep that fall-through and never let the fast path
      change an answer.
    - **Boolean `AND`/`OR` short-circuit.** `SQLAndBlock`/`SQLOrBlock` stop at the first
      decisive sub-block. This is correctness as well as performance: eager evaluation can
      throw where the AST short-circuits past the throwing sub-block.
- **Risks/Caveats**: `AND`/`OR` are out of the S0 IR operator set today (the comparison and
  arithmetic operators are the whole `BinaryOperator` enum), so the short-circuit obligation
  lands when S1 extends lowering to `SQLWhereClause`. This decision is recorded here and
  carried to YTDB-916 as a durable comment so the S1 implementer does not inherit a silent
  regression (the research log is ephemeral).
- **Implemented in**: this track (the S0 slow-path-only scope is explicit); the S1+
  fast-path obligation is recorded for YTDB-916, not implemented in S0.
<!-- **Full design**: design.md §"Comparison: replicate the AST sequence" -->

#### D6-R: Collate fetch pinned to single-property resolution; multi-segment `Var` throws, deferred to S1+
- **Alternatives considered**: re-implement the runtime link-chain traversal in the S0 IR
  evaluator (faithful, but pulls link materialization, the `in_`/`out_` carve-out, and the
  nested-links-only restriction into a no-consumer substrate); delegate `getCollate` to the
  originating parse node (violates D6 — `Var` is a lexical path holding no parse-node
  reference).
- **Rationale**: this is **one logical decision carried in two tracks** — Track 3 (the
  lowering throw on a multi-segment path) and Track 4 (the collate-resolution constraint,
  recorded here). Because S0 lowers single-segment `Var`s only (D6-R), the IR comparison
  evaluator's collate fetch is the AST's single-property resolution chain:

  ```text
  // mirror SQLSuffixIdentifier.getCollate — guarded at every hop
  if (!result.isEntity()) return null;
  var entity = (EntityImpl) result.asEntity();        // getImmutableSchemaClass is on EntityImpl, not the Entity interface
  var schemaClass = entity.getImmutableSchemaClass(session);
  if (schemaClass == null) return null;
  var property = schemaClass.getProperty(name);       // SchemaProperty off the schema class, not Entity.getProperty (the value)
  if (property == null) return null;
  return property.getCollate();
  ```

  Two rules govern that fetch. It returns `null` for any non-`Var` operand — a literal or
  computed sub-expression has no column to resolve. And it is tried on the left operand
  first, then the right, matching the AST's single-segment `getCollate` branch. A
  multi-segment path is not reachable here because lowering throws it (Track 3), so the
  evaluator never has to reproduce the AST's multi-segment runtime link traversal.
- **Risks/Caveats**: the collate resolution applies only to `Var` operands; a non-`Var`
  operand resolves to `null` collate, which matches the AST's behavior for a non-column
  operand. The sibling Track 3 records the same decision as the lowering throw — keep both
  faithful to the same design seed.
- **Implemented in**: this track (the collate-resolution constraint; step references added
  during execution). Also carried in Track 3 (the lowering throw).
<!-- **Full design**: design.md §"Comparison: replicate the AST sequence" -->

#### D19: Every functional slice under YTDB-901 extends per-functionality JMH benchmark coverage on top of LDBC neutrality (blanket S1–S7); S0 stays on its correctness gate
- **Alternatives considered**: scope the obligation to hot-path child issues only and let
  compile/parse-time-only slices ride the LDBC neutrality net alone (rejected at the user's
  call for rule simplicity); literally extend the LDBC SNB query set per child — `LDBC SNB`
  is the Social Network Benchmark, a standardized fixed graph-benchmark query set — rejected
  because that fixed query set is what makes runs comparable, and arbitrary added queries
  break run-to-run comparability.
- **Rationale**: this is recorded here as the **verification-and-delivery context** for S0,
  not an S0 obligation. Each child slice under the YTDB-901 umbrella must extend
  performance-benchmark coverage for the expression-evaluation functionality it introduces —
  a targeted JMH microbenchmark exercising the eval path(s) it touches, on top of the LDBC
  SF1 neutrality gate (`SF1` is the SNB scale factor — the dataset size; the neutrality gate
  is the check that proves a change does not regress those fixed SNB queries) — because LDBC
  neutrality alone only proves no regression on paths the standard SNB queries already
  exercise. A new expression path the suite does not hit would pass the neutrality gate green
  while never being measured. The obligation is **blanket**
  across S1–S7 and recorded on YTDB-901 so every child inherits it. **S0 (YTDB-915) is
  itself one of those slices, but the substrate ships with no live consumer, so its gate
  stays correctness parity (I1) per D17** — the obligation bites from S1 onward.
- **Risks/Caveats**: because no automated gate judges whether a per-child benchmark actually
  exercises new behavior, a nominal benchmark that measures nothing new passes review like a
  real one (gate A15, accepted). The mitigation is that each child issue must **name which
  expression-evaluation path(s) its added benchmark exercises**, putting the relevance claim
  on record where a reviewer can check it by eye. For S0 specifically there is no benchmark
  to add — its correctness gate is the round-trip parity suite below.
- **Implemented in**: this track records the context; no S0 benchmark is added (S0 has no
  live consumer to measure). The obligation is implemented per child slice S1+.
<!-- **Full design**: design.md §"Round-trip parity and the test matrix" -->

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 2 (3 findings — 1 should-fix, 2 suggestions; 3 accepted, all VERIFIED)
- [x] Risk: PASS at iteration 2 (6 findings — 3 should-fix, 3 suggestions; 6 accepted, all VERIFIED)
- [x] Adversarial: PASS at iteration 2 (6 findings — 3 should-fix, 3 suggestions; 6 accepted, all VERIFIED)

## Context and Orientation
`AnalyzedExprEvaluator` is a new class in `core/.../query/analyzed/` (greenfield package,
PSI-confirmed absent on develop) that implements `AnalyzedExprVisitor<Object>` directly. It
reuses two existing pieces: the shared `NumericOps` (Track 2) for arithmetic, and the AST's
own `SQLBinaryCompareOperator` instance for comparison. The six S0 comparison operators are
PSI-confirmed present in `core/.../sql/parser/`: `SQLEqualsOperator` (=),
`SQLNeOperator`/`SQLNeqOperator` (!= / <>), `SQLLtOperator` (<), `SQLLeOperator` (<=),
`SQLGtOperator` (>), `SQLGeOperator` (>=).

This track has the most inter-track dependencies: it depends on Track 1 (the IR types and
the `AnalyzedExprVisitor` interface it implements), Track 2 (the shared `NumericOps` it
delegates `+ - * /` to), and Track 3 (lowering, which produces the IR trees the round-trip
suite evaluates). The round-trip suite is the deliverable that needs both lowering and
evaluation present, which is why it lives here.

The comparison path is the track's subtle mechanism. The IR runs the AST's exact comparison
sequence so parity is structural rather than re-derived:

```mermaid
sequenceDiagram
    participant E as AnalyzedExprEvaluator
    participant R as Result (row)
    participant Op as SQLBinaryCompareOperator
    participant Q as QueryOperatorEquals / doCompare

    E->>E: evaluate left, right operands
    E->>R: getCollate(left Var) — single-property resolution
    Note over E,R: result.asEntity() → schemaClass.getProperty(name) → property.getCollate()<br/>fall back to right Var; null for any non-Var operand (D6-R)
    E->>E: if collate != null, collate.transform(both operands)
    E->>Op: operator.execute(session, leftVal, rightVal)
    Note over Op: EQ passes real session, NE passes null (D11) — a fresh instance of the same operator class the AST uses
    Op->>Q: equals(session, l, r) / doCompare(l, r) vs 0
    Q-->>Op: result
    Op-->>E: boolean
```

## Plan of Work
The work is the evaluator class, a collate-resolution helper, the S1+ `Identifiable`
adapter, and the round-trip parity suite. A natural build order:

1. **Evaluator skeleton.** Implement `AnalyzedExprVisitor<Object>` directly with all five
   `visitX` methods (the compiler forces exhaustiveness, I3); expose one `evaluate(expr,
   Result, CommandContext)` overload (D3). `visitVar` resolves the name path against the
   `Result`; `visitConst` returns the literal. `visitFuncCall` reproduces the AST method-call
   path — evaluate `args[0]` as the target, resolve the method by name via
   `SQLEngine.getMethod(name)`, evaluate the remaining args as parameters, and call
   `SQLMethod.execute(target, params, ctx, …)`. The method call performs its own coercion;
   there is no separate column-type coercion step (the `FuncCall.args()` list is read-only by
   the Track 1 convention — evaluate without mutating it).
2. **Arithmetic.** `visitBinaryOp` for `+ - * /` reaches `NumericOps` through the AST
   `SQLMathExpression.Operator.apply(Object, Object)` entry: map the IR `BinaryOperator`
   arithmetic constant to its `SQLMathExpression.Operator` counterpart, then call that
   constant's `apply(Object, Object)`, which routes through
   `NumericOps.plusObject`/`minusObject`/`applyObject` and carries null-propagation,
   `Date ± Long`, and `String` concat. Do **not** call `NumericOps.apply(Number, Operator,
   Number)` directly — it throws on a null operand and skips those object-level semantics, so
   the null-propagation and `Date + Long` matrix rows would go red. No reusable IR→AST inverse
   map exists (`AnalyzedExprLowerer.toArithmeticOperator` is AST→IR and private), so this track
   authors the four-constant map. The IR fold (Track 3) already nested the tree, so the
   evaluator only walks it and applies promotion at each node.
3. **Comparison.** `visitBinaryOp` for the six comparison operators replicates
   `SQLBinaryCondition.evaluate(Result, ctx)`'s slow-path sequence (D11): evaluate both
   operands; fetch the collate via the single-property resolution (D6-R), left-then-right;
   apply `collate.transform` to both when non-null; delegate to a freshly constructed
   `SQLBinaryCompareOperator` of the same concrete class the AST uses for this operator
   (`EQ → SQLEqualsOperator`, `NE → SQLNeOperator`, `LT → SQLLtOperator`, …) via the public
   `new SQLXxxOperator(-1)` constructor — `INSTANCE` is absent on `SQLNeOperator` /
   `SQLNeqOperator` / `SQLGeOperator`, so a uniform `INSTANCE` accessor will not compile. The
   operators are stateless, so a reconstructed instance runs the identical `execute` body and
   reproduces the EQ/NE session difference and the ordering `doCompare`-vs-0 mapping by class
   identity. The IR `BinaryOp` carries only the enum constant (the lowerer discarded the AST
   operator instance), so the reconstruction is this track's work, not reuse of an AST object.
   The S0 evaluator targets the slow path only (D16).
4. **Boolean `NOT`.** `visitUnaryOp` for `NOT` negates the boolean operand.
5. **Collate-resolution helper.** Mirror `SQLSuffixIdentifier.getCollate` exactly: guard on
   `result.isEntity()`, cast `asEntity()` to `EntityImpl` (the type that exposes
   `getImmutableSchemaClass(session)` — the public `Entity` interface does not), null-guard
   the schema class, read the collate off `SchemaClass.getProperty(name).getCollate()` (not
   `Entity.getProperty(name)`, which returns the column value), and return `null` on any miss
   and for any non-`Var` operand (D6-R). The guard-free happy path would throw on a schemaless
   row or an absent column where the AST returns `null` collate, breaking parity.
6. **`Identifiable` adapter (S1+ seam).** A small helper wrapping an `Identifiable` in a
   synthetic entity-backed `Result` (D3/D15) — recorded for the S1+ path. S0 drives only the
   `Result` overload, so the adapter carries its own focused unit test (wrap an `Identifiable`,
   evaluate a `ci`-collated comparison, assert the convergence) so the coverage gate covers it
   and the D15 behavior change is exercised rather than shipping unverified.
7. **Round-trip parity suite.** Assert the matrix in § Validation and Acceptance. The suite
   lives in `core/.../query/analyzed/` so it can call the package-visible
   `AnalyzedExprLowerer.lowerBoolean` for the comparison and `NOT` rows (`lower` is public,
   `lowerComparison` private). The oracle is shape-dependent — `SQLExpression.execute(Result,
   ctx)` for arithmetic, `SQLBinaryCondition.evaluate(Result, ctx)` for comparison and boolean
   — so the harness dispatches the oracle by parsed shape; `Objects.equals` compares boxed
   `Boolean` on the comparison rows.

Invariants to preserve: I1 (round-trip parity — the AST is the oracle); I3 (the evaluator
enumerates every variant). Comparison parity is structural — never re-derive the AST's
collate/session logic; run a fresh instance of the AST's own operator class.

## Concrete Steps

1. Implement `AnalyzedExprEvaluator` — the `AnalyzedExprVisitor<Object>` runtime over the IR (the single `evaluate(AnalyzedExpr, Result, CommandContext)` overload; `visitVar`/`visitConst`/`visitFuncCall`; arithmetic via the AST `SQLMathExpression.Operator.apply(Object, Object)` after an authored IR→AST enum map; comparison via freshly constructed `SQLBinaryCompareOperator` instances plus the guarded collate-resolution helper; `visitUnaryOp(NOT)`), the S1+ `Identifiable`→synthetic-`Result` adapter with its own focused test, and the round-trip parity suite asserting the § Validation matrix (in `core/.../query/analyzed/` so it can call package-visible `lowerBoolean`) — risk: high (architecture: introduces the IR-runtime abstraction layer, the capstone of the S0 substrate, and its comparison-parity mechanism — collation plus the EQ/NE session difference — is S0's whole acceptance surface)  [ ]

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
The round-trip parity suite is S0's whole acceptance bar: for every covered SQL fragment,
`lower(parse(sql)).evaluate(row, ctx)` must be `Objects.equals` to the AST's own result on the
same row, including null and type-coercion outcomes. The oracle method is shape-dependent —
`SQLExpression.execute(Result, ctx)` for arithmetic and `SQLBinaryCondition.evaluate(Result,
ctx)` for comparison and boolean fragments — so the harness dispatches the oracle by parsed
shape and lowers comparison/`NOT` fragments through the package-visible `lowerBoolean`. The AST
is the reference; a divergence is a real evaluator or `NumericOps` bug, never a reason to relax
the test.

The minimum matrix (each row pins a mechanism a naive implementation would get wrong):

| Fragment | Mechanism it pins |
|---|---|
| `a + b * c` | Precedence fold: `STAR` binds tighter than `PLUS` (Track 3) |
| `a * b + c` | Precedence fold, operators in the other order |
| `a - b - c` | Left-associative reduction `(a - b) - c`, not `a - (b - c)` |
| `a - b + c` | Mixed same-priority left-associativity |
| `a / b / c` | Integer-vs-double divide widening through `NumericOps` |
| `(a + b) * c` | Parenthesis recursion overrides precedence (D10) |
| `a * (b + c)` | Parenthesis recursion, grouping on the right |
| `ci-column = 'Foo'` (mixed case) | Collation transform in comparison (D11) |
| type-coercing `!=` | NE passes a null session to coercion, EQ the live one (D11) |
| `longCol != 1` (Long column vs Integer literal) | Operand cross-type coercion: `visitVar`/`visitConst` value-type fidelity vs the AST's `left.execute`/`right.execute` (A4) |
| `ci-column = 'foo'` on a schemaless row / absent column | Collate helper returns `null` (guarded) instead of NPE/CCE — parity on the non-entity path (T1/A3) |
| `NOT a = b` (unparenthesized) | `visitUnaryOp(NOT)` over a lowered `SQLNotBlock`; round-tripped via the package-visible `lowerBoolean` (Track 3 contract) |
| a covered method-call fragment (e.g. `name.asInteger()`) | `visitFuncCall` method-call parity via `SQLEngine.getMethod`/`SQLMethod.execute` (R2/A6); if no such fragment is in the S0 subset, `visitFuncCall` becomes a recorded throw |

The matrix is the minimum required set, not exhaustive. Null-propagation outcomes and a
`Date + Long` row are part of parity (`Objects.equals` over the produced values), so they
belong in the suite too even though they pin promotion rather than precedence/collation.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In scope:**
- New `AnalyzedExprEvaluator` in `core/.../query/analyzed/` — implements
  `AnalyzedExprVisitor<Object>`; one `evaluate(AnalyzedExpr, Result, CommandContext)`
  overload (D3).
- The single-property collate-resolution helper (D6-R) and the S1+ `Identifiable`→synthetic-
  `Result` adapter helper (D3/D15).
- The round-trip parity test suite asserting the matrix in § Validation and Acceptance (I1).

**Reuses (existing, not modified):** the six comparison operator classes in
`core/.../sql/parser/` (`SQLEqualsOperator`, `SQLNeOperator`, `SQLNeqOperator`, `SQLLtOperator`,
`SQLLeOperator`, `SQLGtOperator`, `SQLGeOperator`) — the evaluator constructs a fresh instance
of the matching class per IR enum constant (the operators are stateless, so a reconstructed
instance is behaviorally identical to the AST's); `QueryOperatorEquals` / `doCompare` reached
through that operator's `execute`.

**Out of scope:** the IR types (Track 1); the `NumericOps` engine itself (Track 2 — this
track delegates to it but does not author it); the lowering pass (Track 3 — this track
consumes its output); the AST evaluation fast paths (`tryInPlaceComparison`, `AND`/`OR`
short-circuit) — slow-path-only in S0 (D16), the fast-path obligation is an S1+ concern; the
cross-caller `Identifiable`-path collation validation (an S1/S7 obligation, D15).

**Relevant shapes (PSI-confirmed on develop):**
- `SQLBinaryCondition` has both `evaluate(Identifiable, ctx)` and `evaluate(Result, ctx)`
  overloads (plus `evaluateAny`/`evaluateAllFunction`); the `Result` overload is the
  collation-applying parity reference.
- The six S0 comparison operators are present in `sql.parser`: `SQLEqualsOperator`,
  `SQLNeOperator`, `SQLNeqOperator`, `SQLLtOperator`, `SQLLeOperator`, `SQLGtOperator`,
  `SQLGeOperator`.

**Inter-track dependencies:** Track 4 depends on **Track 1** (IR types + visitor interface),
**Track 2** (`NumericOps`), and **Track 3** (lowering — to produce trees to evaluate).

**Sizing justification (argumentation gate).** This track is ~4 files, which is under the
merge floor (the ~12-file threshold below which tracks are normally folded together), but
D13 keeps the evaluator + round-trip suite separate from Track 3's lowering:
the round-trip suite is the Track 4 deliverable that needs both lowering and evaluation
present, and evaluation is a distinct visitor implementation and review surface from the
tree-building lowerer. The stacked-diff series lands it as its own reviewable PR.

## Invariants & Constraints
<!-- Per-track testable constraints and invariants; each a property backed by a test. -->
- **I1 — Round-trip parity.** For every SQL fragment in the S0 covered subset,
  `lower(parse(sql)).evaluate(row, ctx)` is `Objects.equals` to `parse(sql).execute(row,
  ctx)`, including null and type-coercion outcomes — verified by the round-trip parity suite
  (the matrix above). The AST is the oracle; a divergence is a real bug, never a reason to
  relax the test.
- **I3 — Exhaustive visitor dispatch (evaluator side).** `AnalyzedExprEvaluator` implements
  the no-defaults base `AnalyzedExprVisitor<Object>`, so a new IR variant breaks it at
  compile time — verified by the compiler (Track 1 owns the dispatcher-side half).

## Base commit
d55ee6e39095355dcf533f326531a0e5bc6ed46a
