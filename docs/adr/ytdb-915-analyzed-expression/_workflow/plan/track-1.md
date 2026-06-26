<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->
# Track 1: Substrate + framework

## Purpose / Big Picture
After this track lands, the codebase has the `AnalyzedExpr` sealed-interface IR and
its visitor/transform framework — the data types and dispatch machinery the lowering
pass (Track 3) and the evaluator (Track 4) build on. Nothing reads the IR yet; this
track ships the substrate alone.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

`AnalyzedExpr` is the analyzed-expression intermediate representation (IR) — a
data-only tree the optimizer and evaluator will read instead of the SQL parse tree
(AST). This is slice 0 (S0) of the YTDB-901 umbrella, so it ships behind no flag with
no live consumer: the substrate exists, but nothing produces or reads IR trees yet.
The IR's value is structural — a small closed set of node shapes the compiler can
reason about exhaustively.

Track 1 delivers that closed type set and its dispatch machinery: a Java 21 sealed
interface permitting five immutable record variants (`Var`, `Const`, `BinaryOp`,
`UnaryOp`, `FuncCall`), the static-dispatch visitor and structural-sharing transform
framework over it, and the `UnsupportedAnalyzedNodeException` lowering-failure type.
It has no dependency on the rest of S0.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Full inline Decision Records this track owns (four-bullet form). One block per decision: -->

#### D1: Sealed-interface IR with five record variants
- **Alternatives considered**: abstract class plus concrete subclasses (the codebase
  default — `SQLBooleanExpression` plus 21 subclasses), which keeps a per-node virtual
  dispatch; an enum-tagged sum type with one all-fields class, which loses type safety;
  a visitor-only API with no sealed root, which loses exhaustiveness enforcement.
- **Rationale**: a Java 21 sealed interface permitting exactly five immutable record
  variants (`Var`, `Const`, `BinaryOp`, `UnaryOp`, `FuncCall`) lets the compiler enforce
  an exhaustive `switch` over the variant set. The alternative `accept(visitor)` method on
  every node is megamorphic: there is one `accept` call site in a recursive walk, but the
  receiver is any of the variant types, so the JVM's call site sees many distinct concrete
  classes and cannot bind a single target. Resolving the variant first — a sealed `switch`
  in one static dispatcher — leaves each downstream `visitX` call with a single receiver
  type, so it stays monomorphic and inlinable. Records give immutable value-equality,
  accessors, and hashing for free, useful for golden tests (tests that assert a node
  tree equals a recorded reference tree); the variants carry data only,
  no behavior. The package is greenfield, so the new idiom disturbs nothing else.
- **Risks/Caveats**: this is the first sealed-type use in the codebase, so maintainers
  need a one-paragraph orientation. Adding a sixth variant is an intended compile-time
  break across the dispatcher and every base-visitor implementer (invariant I3), not a
  regression.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"Sealed IR and exhaustive dispatch" -->

#### D2: Visitor as interface; static `switch` dispatch, no `accept(...)`
- **Alternatives considered**: `accept(visitor)` on each node (classic Visitor — a
  megamorphic virtual call per dispatch, multiplied across visitor implementations in a
  long optimizer pipeline); a raw `switch (expr)` at every call site (no abstraction,
  repeated everywhere); an abstract base class with a default-recursing `visit(...)`.
- **Rationale**: `AnalyzedExprVisitor<T>` declares one `visitX` method per variant, and a
  single static helper `AnalyzedExpr.dispatch(expr, visitor)` carries the one `switch`
  over the sealed type and calls the right `visitX`. One `visitX` per variant makes the
  IR's shape explicit and self-documenting. The static dispatcher carries the `switch`
  once — the sealed permits-list is a closed, known set of types, which lets the JIT
  emit a dense dispatch table (a table jump) rather than a chain of type tests; each
  `visitor.visitX(x)` after it is a direct monomorphic call. No `accept(...)` keeps the
  nodes pure data.
- **Risks/Caveats**: the base `AnalyzedExprVisitor<T>` has **no** default methods, so a
  direct implementer (the evaluator; any future non-`AnalyzedExpr`-returning pass) must
  enumerate every variant — a new variant breaks it at compile time. The relaxation for
  rewrite passes (recurse-into-children defaults) is scoped to `AnalyzedExprTransform`
  (D9) and never touches the base visitor.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"Sealed IR and exhaustive dispatch" -->

#### D4: `Cast` variant dropped from S0 scope
- **Alternatives considered**: keep `Cast` and lower the method-call coercions
  (`.asInteger()`, `.asDate()`) into it; ship `Cast` as a placeholder with no lowering
  coverage.
- **Rationale**: the YTDB-915 issue body lists a sixth variant, `Cast`; S0 ships without
  it. YouTrackDB grammar has no `CAST(x AS T)` form (the grammar file `YouTrackDBSql.jjt`
  has no `CAST` production), so type coercion is written as method-call syntax carried on
  `SQLModifier.methodCall` and is structurally a function call — it already lowers through
  `FuncCall`. A dedicated `Cast` variant would have to carry a target-type tag (the type a
  `CAST` would name) that no S0 lowering or evaluator path reads — a variant with no
  consumer. Keeping it and lowering method calls into it invents a cast taxonomy to model
  what is already a function call; a placeholder with no lowering is dead code. A later
  slice can add `Cast` if explicit `CAST` grammar or an optimizer rewrite ever needs it.
- **Risks/Caveats**: the YTDB-915 issue body still lists `Cast` and needs an edit so its
  acceptance criteria match what S0 delivers (carried as an open item, paired with the
  unary-minus wording correction below).
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"Sealed IR and exhaustive dispatch" -->

#### D6: `Var` carries a `List<String>` name path
- **Alternatives considered**: hold a reference to the original `SQLBaseIdentifier` (raw
  AST interop); a Postgres-style `Var(range-table index, column number)` with a resolved schema
  binding (requires the range-table model from S10, the later identifier-resolution slice
  that binds names to their `FROM`-clause source); an opaque `name: String` with embedded
  dot separators.
- **Rationale**: `Var(List<String> path)` holds the unresolved lexical name path —
  `["name"]` for a bare identifier. The `List<String>` shape is clean, self-contained, and
  trivial to inspect during evaluator debugging. S10 will replace `Var` with
  range-table-resolved references, so S0 should hold the lexical shape and not bake in a
  resolution model S10 would throw away.
- **Risks/Caveats**: when S10 lands, callers reading `Var.path()` migrate, but the blast
  radius is bounded — only the lowerer and evaluator touch `Var` in S0. (D6-R narrows S0's
  *lowering* to single-segment paths; see Track 3 / Track 4. The `Var` type itself still
  carries a `List<String>` for the S10 shape.)
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"Sealed IR and exhaustive dispatch" -->

#### D7: `UnsupportedAnalyzedNodeException extends CommandExecutionException`
- **Alternatives considered**: extend `BaseException` (less specific); extend
  `ParseException` (parser-side — the wrong layer for a lowering failure); a new top-level
  exception type.
- **Rationale**: the lowerer throws `UnsupportedAnalyzedNodeException(astNode.getClass())`
  for any AST shape outside the S0 covered subset. Lowering happens in the same logical
  phase as execution preparation, and `CommandExecutionException`
  (`com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException`,
  confirmed present) is the established base for SQL execution-time errors, so an
  unsupported-node failure surfaces the same way other execution-time failures do.
  Carrying the unsupported AST class (not its rendered text) gives an actionable
  diagnostic that names the unsupported *shape* (e.g. `SQLJson`) rather than echoing user
  SQL. This type is the mechanism behind invariant I2 (owned and stated in Track 3) —
  lowering an unsupported AST shape throws rather than returning a partial or placeholder
  tree, so a successful `lower(...)`
  return means full IR coverage of the input. (Track 3 owns the throw sites and states I2
  as a track invariant; this track ships the exception type the throw uses.)
- **Risks/Caveats**: the type is defined in this track but its throw sites live in Track 3
  (the lowerer); this track ships the exception class only.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"Lowering failures: UnsupportedAnalyzedNodeException" -->

#### D8: `AnalyzedExprTransform` with structural sharing
- **Alternatives considered**: a full rebuild on every transform (allocates
  `O(tree-size)` per pass); a transform returning `Optional<AnalyzedExpr>` to signal
  change (wrapper allocations, and every visitor must remember to unwrap).
- **Rationale**: `AnalyzedExprTransform extends AnalyzedExprVisitor<AnalyzedExpr>` is the
  rewrite-pass shape for S3+ optimizer slices. The static `transformChildren(expr, t)`
  helper enforces a per-node rule. It recurses one level into a node's children. If every
  child comes back as the same instance it received, it returns the input node unchanged;
  it builds a new parent record only when at least one child changed. "Same instance" here
  is reference identity (`==`), not value equality. Optimizer passes typically rewrite one
  subtree and leave the rest untouched, so this rule shares the untouched part by reference.

  Walk a fold that fires at depth 10 of a 50-node tree, one ancestor at a time. The
  rewritten leaf at depth 10 is a new node. Its parent at depth 9 recurses into its
  children, sees that one child (the depth-10 node) is a different instance, and so rebuilds
  itself around the new child while returning its other children by reference. That rebuilt
  depth-9 node is itself a new instance, so its parent at depth 8 sees one changed child and
  rebuilds the same way — and so on up the chain to the root at depth 0. Every node *off*
  that path sees all of its children come back unchanged and returns itself by reference. So
  a single deep rewrite allocates exactly the ten new nodes on the path from the rewritten
  leaf to the root, and shares all ~40 off-path subtrees untouched, instead of rebuilding
  all 50. `FuncCall` lazily allocates its new argument list on the first changed element, so
  an unchanged argument list is never copied. Centralizing the identity-comparison logic in
  one helper keeps individual passes from re-implementing (and subtly mis-implementing) it.
  The shared shape is a visitor that returns a rewritten node and shares unchanged subtrees
  by reference.
- **Risks/Caveats**: equality is by reference identity, so a transform that reconstructs
  an `equals`-but-distinct copy of an unchanged node counts as "changed" and defeats the
  sharing. The rule for transform authors: return the input reference when no change
  applies; never rebuild an equal copy. There is no live consumer of the transform
  framework in S0 (it is the shape S3+ passes will use), so this track verifies it only by
  the structural-sharing unit test, not against a real pass.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"Transform passes and structural sharing" -->

#### D9: Default methods on `AnalyzedExprTransform`, none on the base visitor
- **Alternatives considered**: no defaults anywhere (verbose); defaults on the base
  `AnalyzedExprVisitor<T>` (would force the evaluator to inherit no-op behavior and
  silently lose exhaustiveness for `Object`-returning visitors); an opt-in
  `RecursiveTransform` sub-interface (adds a second transform shape to the API).
- **Rationale**: `AnalyzedExprTransform`'s five `visitX` methods carry
  recurse-into-children defaults — leaf variants (`Var`, `Const`) return self, compound
  variants (`BinaryOp`, `UnaryOp`, `FuncCall`) call `transformChildren` — while the base
  `AnalyzedExprVisitor<T>` keeps none. Recurse-into-children is the right behavior for
  nearly every transform pass: a pass that rewrites one variant wants the others to pass
  through, so it overrides only that one `visitX` and inherits pass-through for the rest.
  The defaults remove four boilerplate overrides per pass while keeping the base visitor
  strict, so the evaluator (which implements the base visitor) still breaks at compile
  time on a new variant.
- **Risks/Caveats**: this is the one place the I3 compile-time guarantee does not reach.
  Adding a new IR variant requires an explicit audit of every transform pass — a pass
  needing special handling for the new variant fails *silently* (it default-recurses)
  rather than at compile time, because the defaults make every variant pass-through-able.
  The audit must be part of the variant-addition checklist.
- **Implemented in**: this track (step references added during execution)
<!-- **Full design**: design.md §"Transform passes and structural sharing" -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation
The target package `core/.../query/analyzed/`
(`com.jetbrains.youtrackdb.internal.core.query.analyzed`) does not exist on develop —
confirmed absent via PSI; the substrate has no commits of its own and sits at develop's
tip. This track is therefore pure greenfield: it adds new files only and modifies no
existing class.

This is the first use of Java 21 sealed types in the codebase. The incumbent idiom for a
node tree is an abstract base class with concrete subclasses — for example
`SQLBooleanExpression` plus 21 subclasses in `core/.../sql/parser/`, where each node
carries its own `evaluate`/`execute` behavior and dispatch is a virtual method call. The
`AnalyzedExpr` substrate deliberately departs from that idiom: the variants carry data
only, and dispatch is a single static `switch`.

The substrate has no dependency on the lowering pass (Track 3), the evaluator (Track 4),
or the `NumericOps` extraction (Track 2). The two static helpers on `AnalyzedExpr`
(`dispatch`, `transformChildren`) are self-contained — they reference only the five
variants and the two visitor interfaces this track defines.

## Plan of Work
The work is one greenfield package, addable in any order since nothing outside it depends
on a partial state. A natural build order:

1. Define the sealed root `AnalyzedExpr` and its five permitted record variants — `Var`,
   `Const`, `BinaryOp`, `UnaryOp`, `FuncCall`. `BinaryOp`/`UnaryOp` carry the IR's own
   operator tags, so introduce the IR `BinaryOperator` enum (`+ - * /` plus the six
   comparisons `= != < <= > >=`) and `UnaryOperator` enum (`NOT` only) alongside them.
   These are the IR's own small enums, distinct from the AST's
   `SQLMathExpression.Operator`.
2. Define `AnalyzedExprVisitor<T>` (one `visitX` per variant, **no** default methods) and
   the static `AnalyzedExpr.dispatch(expr, visitor)` carrying the one sealed `switch`.
3. Define `AnalyzedExprTransform extends AnalyzedExprVisitor<AnalyzedExpr>` with the five
   recurse-into-children default `visitX` methods, and the static
   `AnalyzedExpr.transformChildren(expr, t)` carrying the recurse-and-rebuild logic with
   reference-identity sharing.
4. Define `UnsupportedAnalyzedNodeException(Class)` extending `CommandExecutionException`.
5. Add the substrate unit test (see Validation and Acceptance).

Invariants to preserve while building: the base `AnalyzedExprVisitor<T>` carries no
defaults (D9 — the strictness that backs I3); `transformChildren` compares children by
reference identity, not value equality (D8); the `dispatch` `switch` has no `default`
clause (I3).

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the numbered roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
S0 ships with no live consumer, so this track's acceptance is its own substrate unit
test plus the compiler:

- **Dispatch exhaustiveness.** A test visitor implementing `AnalyzedExprVisitor<T>` over
  all five variants, dispatched through `AnalyzedExpr.dispatch`, returns the variant-specific
  result for each variant — confirming `dispatch` routes each variant to the correct
  `visitX`. The compile-time half of I3 (a sealed `switch` with no `default`, a base
  visitor with no defaults) is enforced by the compiler, not a runtime assertion: adding a
  sixth variant would fail to compile.
- **Structural sharing by reference identity.** A transform that changes one subtree
  returns the *same instance* (`==`) for every unchanged node and a new parent only on the
  changed path; a transform that changes nothing returns the original root by reference.
  The test asserts reference identity (`assertSame`), not value equality, so it catches a
  transform that rebuilds an `equals`-but-distinct copy.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies
**In scope (new files under `core/.../query/analyzed/`):**
- `AnalyzedExpr` — sealed interface; the two static helpers `dispatch(AnalyzedExpr,
  AnalyzedExprVisitor<T>) -> T` and `transformChildren(AnalyzedExpr, AnalyzedExprTransform)
  -> AnalyzedExpr`.
- The five record variants: `Var(List<String> path)`, `Const(Object value)`,
  `BinaryOp(BinaryOperator op, AnalyzedExpr left, AnalyzedExpr right)`,
  `UnaryOp(UnaryOperator op, AnalyzedExpr operand)`,
  `FuncCall(String name, List<AnalyzedExpr> args)`.
- The IR's own `BinaryOperator` enum (`+ - * /` and the six comparisons) and
  `UnaryOperator` enum (`NOT`).
- `AnalyzedExprVisitor<T>` (one `visitX` per variant, no defaults) and
  `AnalyzedExprTransform extends AnalyzedExprVisitor<AnalyzedExpr>` (recurse-into-children
  defaults).
- `UnsupportedAnalyzedNodeException(Class)`.
- The substrate unit test.

**Out of scope (other tracks / later slices):** the lowering pass (Track 3) and the
evaluator (Track 4) consume these types but live in their own tracks; `NumericOps`
(Track 2) is unrelated to the substrate; identifier resolution against range tables (S10),
serialization / plan-cache representation (later slice), and a `Cast` variant (D4) are all
out of scope.

**Existing types depended on:** `CommandExecutionException`
(`com.jetbrains.youtrackdb.internal.core.exception`) — the parent of
`UnsupportedAnalyzedNodeException`. No existing class is modified.

**Inter-track dependencies:** Track 1 depends on nothing. Track 3 (lowering) and Track 4
(evaluator) depend on Track 1 for the IR types and the visitor interface.

**Sizing justification (argumentation gate).** This track is ~10 files, which sits near
the merge floor, but D13 keeps it a separate track: it is the greenfield IR-types-and-
framework PR that the lowering pass (Track 3) and evaluator (Track 4) both build on — a
clean dependency boundary. Bundling the data-only substrate with the AST-reading lowerer
would mix two distinct review surfaces (new value types and dispatch machinery vs.
AST-walking conversion logic) in one diff. The stacked-diff series lands the substrate as
its own reviewable PR.

## Invariants & Constraints
<!-- Per-track testable constraints and invariants; each a property backed by a test. -->
- **I3 — Exhaustive visitor dispatch.** Adding a new `AnalyzedExpr` variant is a
  compile-time break across `AnalyzedExpr.dispatch`'s sealed `switch` (no `default`) and
  every base-`AnalyzedExprVisitor<T>` implementer (no default methods) — verified by the
  substrate unit test's dispatch-exhaustiveness coverage plus the compiler (a sixth variant
  fails to compile). This is the one guarantee D9 deliberately does not extend to
  `AnalyzedExprTransform`, whose recurse-into-children defaults make a new variant
  pass-through silently.
