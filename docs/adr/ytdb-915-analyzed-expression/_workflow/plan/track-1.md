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
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review (skipped — single-step track, full track-pass selection already ran at the step in Phase B)
- [x] Track completion

- [x] 2026-06-26T12:46Z [ctx=info] Review + decomposition complete
- [x] 2026-06-26T15:28Z [ctx=safe] Step 1 complete (commit b6fa587564)
- [x] 2026-06-26T15:38Z [ctx=safe] Track-level code review skipped (single-step high), track complete

## Surprises & Discoveries
- **Phase A review (2026-06-26): "first sealed-type use" is false.** D1's Risks/Caveats,
  `## Context and Orientation`, and `design.md:248` all call the `AnalyzedExpr` substrate
  the first Java 21 sealed-type use in the codebase. Phase A technical (T1) and risk (R1)
  reviews PSI-confirmed otherwise: ~19 sealed types already exist, two of them
  sealed-interface-permitting-record analogs of this exact idiom —
  `com.jetbrains.youtrackdb.internal.core.storage.StorageReadResult` and
  `com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand.SqlCommandExecutionResult`
  (both PSI-verified `sealed interface`). The claim is wrong; the design is unaffected and
  arguably strengthened, because the idiom already compiles on the project's Java 21 level,
  which de-risks the I3 exhaustive-`switch` mechanism. D1 and `design.md` are frozen, so the
  wording correction is **deferred to the Phase-4 `design-final.md` reconciliation**
  ("first sealed-type use in the SQL/query layer", or "follows the established
  `StorageReadResult` / `SqlCommandExecutionResult` idiom"). The implementation step points
  the implementer at those precedents for naming and style instead of treating the idiom as
  unprecedented.
- **Phase A review (2026-06-26): I3 has no backstop on the transform path (A1).** D9
  deliberately and correctly exempts `AnalyzedExprTransform` from I3 — its
  recurse-into-children defaults make a new variant pass through silently, which is the right
  transform default. The design's only mitigation is prose ("make the audit part of the
  variant-addition checklist"), and S0 ships no checklist artifact and no compile- or
  test-time backstop. The implementation step folds in the S0-feasible backstop: a
  `VARIANT-ADDITION` anchor comment on the sealed `AnalyzedExpr` root and on
  `AnalyzedExprTransform`, stating that adding a variant obliges an audit of every transform
  pass. The mechanical backstop — a reflective visitX/variant-count test — is an **S1+
  obligation**: S0 has no transform pass to test it against.
- **Phase B (2026-06-26): `FuncCall.args()` is read-only by caller convention, not a record
  guarantee.** The transform rebuild path returns `Collections.unmodifiableList(...)` while
  the unchanged path returns the caller's list by reference, so the lowering pass (Track 3)
  and evaluator (Track 4) must treat `FuncCall.args()` as read-only rather than rely on a
  defensive copy. The as-built IR API those tracks consume — `BinaryOperator` constants
  `PLUS/MINUS/STAR/SLASH/EQ/NE/LT/LE/GT/GE`, `UnaryOperator` is `NOT` only, visitor methods
  `visitVar/visitConst/visitBinaryOp/visitUnaryOp/visitFuncCall` (no defaults, so the
  evaluator must implement all five), and `UnsupportedAnalyzedNodeException(Class)` rendering
  the class name into its own message — is recorded in the step episode. See Episodes §Step 1.

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
  helper enforces a per-node rule: recurse one level into a node's children; if every child
  comes back as the same instance it received, return the input node unchanged; build a new
  parent record only when at least one child changed. "Same instance" is reference identity
  (`==`), not value equality. Optimizer passes typically rewrite one subtree and leave the
  rest untouched, so a single deep rewrite allocates only the new nodes on the path from the
  rewritten leaf to the root and shares every off-path subtree by reference, instead of
  rebuilding the whole tree (`FuncCall` lazily copies its argument list only on the first
  changed element). Centralizing the identity-comparison logic in one helper keeps individual
  passes from re-implementing (and subtly mis-implementing) it. The full depth-10 worked
  example is in `design.md §"Transform passes and structural sharing"`.
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
- [x] Technical: PASS at iteration 1 (2 findings, 1 should-fix accepted). T1 (should-fix) —
  "first sealed-type use" false → Phase-4 reconciliation + Surprises log + implementer
  oriented to the `StorageReadResult` precedent. T2 (suggestion) — the `(Class)` constructor
  must build its own message and call `super(String)` (the parent has no `(Class)` ctor) →
  folded into the step.
- [x] Risk: PASS at iteration 1 (3 findings, 1 should-fix accepted). R1 (should-fix) = T1
  (same finding). R2 (suggestion) = T2 (same finding). R3 (suggestion) — `transformChildren`
  branch coverage concentrates on one method → explicit branch-row enumeration folded into
  Validation.
- [x] Adversarial: PASS at iteration 1 (4 findings, 1 should-fix accepted; narrowed per D9,
  cross-track-episode challenge dropped on the first track). A1 (should-fix) — the I3/D9
  transform backstop gap → anchor comment now, reflective backstop deferred to S1+ (see
  Surprises). A2 (suggestion) — D8 test does not exercise the equal-but-rebuilt-copy path →
  negative test folded into Validation. A3 (suggestion) — D13 sizing rationale under-sells
  the two-consumer fan-out → Phase-4 polish note. A4 (suggestion) — D2 static dispatch
  confirmed sound, no action.
- 0 blockers across all three reviews; 0 design decisions escalated. Every decision
  (D1/D2/D4/D6/D7/D8/D9) survives. The should-fix items are documentation accuracy (T1/R1),
  a mitigation backstop (A1), and test enumeration (R3/A2) — none changes a decision.

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

**Phase A decomposition notes (review findings folded in):**

- *Follow the existing sealed idiom (T1/R1).* This is not the first sealed type in the
  codebase. Mirror the established sealed-interface + record-variant + centralized
  static-dispatch idiom of
  `com.jetbrains.youtrackdb.internal.core.storage.StorageReadResult` and
  `com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand.SqlCommandExecutionResult`
  for naming and style. (The "first sealed-type use" wording in D1 / C&O / `design.md` is
  frozen; its correction is a Phase-4 item — see `## Surprises & Discoveries`.)
- *Exception constructor (T2/R2).* `CommandExecutionException` has no `(Class)` constructor
  (only `(String)`, `(String, String)`, `(DatabaseSessionEmbedded, String)`, and the
  self-type copy constructor). `UnsupportedAnalyzedNodeException(Class)` therefore renders
  the class name into the message itself and calls `super(String)` — e.g.
  `super("unsupported analyzed node: " + astNodeClass.getName())`. Optionally add a
  self-type copy constructor to match the `CoreException`-subclass house convention.
- *I3 transform backstop (A1).* Add a `VARIANT-ADDITION:` anchor comment on the sealed
  `AnalyzedExpr` root and on `AnalyzedExprTransform`, stating that adding a sixth variant
  obliges auditing every transform pass — the one place D9 lets I3's compile-time guarantee
  not reach. The mechanical reflective backstop is an S1+ obligation (S0 has no transform
  pass to test).

## Concrete Steps
The substrate is one coherent greenfield abstraction layer (~11 files) and one
HIGH-category change (architecture: a new abstraction layer), so per the high-isolation
decomposition rule it stays a single high-tagged step — the whole layer lands in one diff
so its step-level dimensional review sees dispatch and the transform framework together.
The five-stage build order is in `## Plan of Work`; the folded review findings are in the
Phase A decomposition notes there.

1. Build the greenfield `AnalyzedExpr` substrate in `core/.../query/analyzed/` — the sealed
   `AnalyzedExpr` interface with its two static helpers (`dispatch`, `transformChildren`),
   the five record variants (`Var`, `Const`, `BinaryOp`, `UnaryOp`, `FuncCall`), the IR
   `BinaryOperator` / `UnaryOperator` enums, `AnalyzedExprVisitor<T>` (no defaults),
   `AnalyzedExprTransform` (recurse-into-children defaults),
   `UnsupportedAnalyzedNodeException(Class)`, the `VARIANT-ADDITION` anchor comments, and the
   substrate unit test — per `## Plan of Work`, `## Validation and Acceptance`, and
   `## Interfaces and Dependencies`, in one atomic greenfield commit. — risk: high
   (architecture: introduces a new abstraction layer — the analyzed-expression IR and its
   dispatch/transform framework, the foundation Tracks 3 and 4 build on)  [x] commit: b6fa587564

## Episodes

### Step 1 — commit b6fa587564, 2026-06-26T15:28Z [ctx=safe]
**What was done:** Built the greenfield `AnalyzedExpr` substrate under
`core/.../query/analyzed/`: a sealed `AnalyzedExpr` interface with five nested record
variants (`Var`, `Const`, `BinaryOp`, `UnaryOp`, `FuncCall`); the two static helpers
`dispatch` (one default-free `switch`) and `transformChildren` (recurse-and-rebuild with
reference-identity structural sharing); the IR's own `BinaryOperator` (`+ - * /` plus the
six comparisons) and `UnaryOperator` (`NOT`) enums; `AnalyzedExprVisitor<T>` (no defaults)
and `AnalyzedExprTransform` (recurse-into-children defaults); and
`UnsupportedAnalyzedNodeException(Class)` extending `CommandExecutionException`.
`VARIANT-ADDITION` anchor comments sit on the sealed root and on `AnalyzedExprTransform`,
the transform-path backstop for the one place D9 leaves I3's compile-time guarantee unreached.
The step-level dimensional review ran the full track-pass-equivalent selection (code-quality,
bugs-concurrency, test-behavior, test-completeness, performance, test-structure) because this
is the track's sole high step, so Phase C's review portion will skip. It found 0 blockers,
1 should-fix, 10 suggestions; all in-scope items were fixed in commit b6fa587564 and
gate-verified PASS across all five re-checked dimensions. The substrate test is 15/15 green;
the new package holds 100% line / 100% branch coverage.

**What was discovered:** The as-built API that the lowering pass (Track 3) and evaluator
(Track 4) consume — `BinaryOperator` constants `PLUS/MINUS/STAR/SLASH/EQ/NE/LT/LE/GT/GE`,
`UnaryOperator` is `NOT` only, visitor methods
`visitVar/visitConst/visitBinaryOp/visitUnaryOp/visitFuncCall` with no defaults (so the
Track 4 evaluator must implement all five), and `UnsupportedAnalyzedNodeException(Class)`
renders the class name into its own message because the parent has no `(Class)` constructor.
`FuncCall.args()` is read-only by caller convention; the record does not defensively copy it.
The rebuilt-arg path returns an unmodifiable list and the unchanged path returns the caller's
list by reference, so Tracks 3 and 4 must treat `args()` as read-only. The PF1 fix collapsed
the changed-arg rebuild to a single allocation, matching D8's lazy-single-copy contract.

**What changed from the plan:** The five record variants ship as nested records inside
`AnalyzedExpr.java` (following the established `StorageReadResult` sealed idiom the Phase A
review pointed at), so the track is 7 files rather than the ~11 the sizing estimate named.
No type and no decision changed. The one declined review suggestion was TC2 (a `Const(null)`
leaf test), which the reviewer marked correct-by-construction since `Const`'s value is opaque.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExpr.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprVisitor.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTransform.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/BinaryOperator.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/UnaryOperator.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/UnsupportedAnalyzedNodeException.java` (new)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/analyzed/AnalyzedExprTest.java` (new)

### Track completion — 2026-06-26T15:38Z [ctx=safe]
Track 1 landed the greenfield `AnalyzedExpr` substrate (`core/.../query/analyzed/`, 7 files,
660 lines): a sealed `AnalyzedExpr` interface with five nested record variants (`Var`, `Const`,
`BinaryOp`, `UnaryOp`, `FuncCall`); two static helpers — `dispatch` (one default-free `switch`)
and `transformChildren` (recurse-and-rebuild with reference-identity structural sharing); the
IR's own `BinaryOperator` (`+ - * /` plus the six comparisons) and `UnaryOperator` (`NOT`) enums;
`AnalyzedExprVisitor<T>` (no defaults) and `AnalyzedExprTransform` (recurse-into-children
defaults); and `UnsupportedAnalyzedNodeException(Class)`. The substrate test is 15/15 green at
100% line / 100% branch coverage. S0 ships no live consumer, so the substrate test plus the
compiler are the whole acceptance gate.

The cross-track contract Tracks 3 (lowering) and 4 (evaluator) build on: `BinaryOperator`
constants `PLUS/MINUS/STAR/SLASH/EQ/NE/LT/LE/GT/GE`; `UnaryOperator` is `NOT` only; visitor
methods `visitVar/visitConst/visitBinaryOp/visitUnaryOp/visitFuncCall` with no defaults, so the
Track 4 evaluator must implement all five. `FuncCall.args()` is read-only by caller convention —
the record does not defensively copy; the rebuilt-arg path returns an unmodifiable list and the
unchanged path returns the caller's list by reference, so Tracks 3 and 4 must treat `args()` as
read-only (the contract is convention, not record-enforced). I3 (exhaustive visitor dispatch) is
a compile-time guarantee everywhere except `AnalyzedExprTransform`, whose recurse-into-children
defaults let a new variant pass through silently; the `VARIANT-ADDITION` anchor on the transform
is the backstop.

Plan deviation: the five record variants ship as nested records inside `AnalyzedExpr.java` (the
established `StorageReadResult` sealed idiom), so the track is 7 files rather than the ~11 the
sizing estimate named. No type and no decision changed. The step-level review ran the full
track-pass-equivalent selection (this is the sole high step, so Phase C's review portion was
skipped) and found 0 blockers, 1 should-fix, 10 suggestions; all in-scope items were fixed in
`Review fix: harden analyzed-expr substrate tests and FuncCall rebuild` and gate-verified PASS.
The one declined suggestion was a `Const(null)` leaf test, marked correct-by-construction.

1 step, 0 failed.

## Validation and Acceptance
S0 ships with no live consumer, so this track's acceptance is its own substrate unit
test plus the compiler:

- **Dispatch exhaustiveness.** A test visitor implementing `AnalyzedExprVisitor<T>` over
  all five variants, dispatched through `AnalyzedExpr.dispatch`, returns the variant-specific
  result for each variant — confirming `dispatch` routes each variant to the correct
  `visitX`. The compile-time half of I3 (a sealed `switch` with no `default`, a base
  visitor with no defaults) is enforced by the compiler, not a runtime assertion: adding a
  sixth variant would fail to compile.
- **Structural sharing by reference identity (`transformChildren`).** The branch-coverage
  target lands almost entirely on `transformChildren`, so the test enumerates its branches
  as explicit rows rather than one summary assertion (R3): (a) a leaf variant (`Var`,
  `Const`) is returned by reference; (b) a compound variant with no child changed returns the
  parent by reference (`assertSame`); (c) a compound variant with one child changed returns a
  new parent while the unchanged sibling is shared by reference; (d) a `FuncCall` with no
  argument changed returns the same argument list and the same node; (e) a `FuncCall` with a
  middle argument changed returns a new list while the leading arguments are shared by
  reference. Identity checks use `assertSame`. **Negative case (A2):** a transform that
  rebuilds an `equals`-but-distinct copy of an unchanged node is counted as "changed" and
  defeats the sharing — the test asserts this, so the reference-identity rule (not value
  equality) is exercised, not just the happy path.

Per-step acceptance (Step 1):
- WHEN `AnalyzedExpr.dispatch(expr, visitor)` is called for each of the five variants, THE
  substrate SHALL invoke the matching `visitX` and return its result (dispatch
  exhaustiveness).
- WHEN a `transformChildren` pass leaves every child unchanged, THE helper SHALL return the
  input node by reference; WHEN exactly one child changes, THE helper SHALL return a new
  parent and share every unchanged subtree by reference.
- THE substrate unit test SHALL meet the 85% line / 70% branch coverage gate on the new
  dispatch and transform machinery.
- THE `core` module SHALL compile and `./mvnw -pl core clean test` SHALL pass for the new
  test.

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery
The step is pure-additive greenfield: it creates new files under
`core/.../query/analyzed/` and modifies no existing class, so it is idempotent in the
trivial sense — re-running it produces the same files. Recovery from a failed attempt is
`git reset --hard HEAD` (the implementer's standard revert), which removes the new files and
leaves the tree at the pre-step commit; there is no on-disk state, schema, or external side
effect to unwind. S0 has no live consumer of the substrate, so a partial or reverted attempt
cannot leave any other component in a broken state.

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

## Base commit
bfb97b2f7c07ebb7cf60d11ed2eb8252f15efde6
