# Analyzed-expression substrate (YTDB-915 / YTDB-901 S0) — Research Log

<!-- Phase-0/1 decision ledger. Distilled 2026-06-24 from the prior _workflow/
     artifacts (design.md, implementation-plan.md, design-mutations.md,
     plan/track-1..4.md), authored ~2026-05-20 under an earlier workflow
     generation and removed once this log was written. Those artifacts were
     never committed; this log is the single surviving seed, so it carries their
     load-bearing decisions, codebase findings, and open questions forward.
     Code anchors (file:line) are as-of the prior research and must be
     re-verified via PSI on resume — develop has advanced ~100+ commits since.
     Class names and packages cited below were re-confirmed present (or absent)
     on develop at 6dba771b5c, 2026-06-24; only the line numbers are stale. -->

## Initial request

Build slice **S0** of YTDB-901: the minimal *analyzed-expression substrate* that
later slices (S1–S14) build on.

Today YouTrackDB routes every analyzed-form decision through the parse tree. The
`SQL*` classes under `core/.../sql/parser/` are the raw AST, the analyzed form,
the optimizer surface, and the runtime evaluator all at once — optimizer rewrites
live as methods on parse nodes (`SQLBooleanExpression.flatten`, `mergeUsingAnd`,
`splitForAggregation`, `rewriteIndexChainsAsSubqueries`) and executor steps embed
AST fragments directly (`FilterStep(SQLWhereClause, …)`,
`ProjectionCalculationStep(SQLProjection, …)`).

S0 introduces `AnalyzedExpr`: a small sealed-interface IR that lives alongside the
AST, with four deliverables —

1. A sealed `AnalyzedExpr` IR with five record variants (`Var`, `Const`,
   `BinaryOp`, `UnaryOp`, `FuncCall`).
2. A visitor / rewrite-pass framework (`AnalyzedExprVisitor<T>` +
   `AnalyzedExprTransform`) with static `switch` dispatch.
3. A lowering pass `SQLExpression` (AST) → `AnalyzedExpr` over a small covered
   subset.
4. A runtime evaluator matching AST `execute(...)` semantics on that subset.

The substrate ships behind no flag and has **no live executor consumer in S0** —
S1 wires the first consumer; S3+ port optimizer rewrites onto the IR. Acceptance
from the issue: no existing test changes, and round-trip parity (`parse → lower →
evaluate` equals `parse → AST.execute`) on the covered subset.

## Decision Log

<!-- Distilled from the prior design's D1–D9, invariants I1–I3, and the
     scope/non-goal set. Each entry keeps the original decision date and is
     marked as carried forward on 2026-06-24. Re-validate each against current
     develop before re-authoring the design. -->

### D1 — Sealed-interface IR with record variants (2026-05-20, carried 2026-06-24) [ctx=safe]

`AnalyzedExpr` is a sealed Java interface permitting five immutable record
variants: `Var`, `Const`, `BinaryOp`, `UnaryOp`, `FuncCall`. Variants carry data
only — equality, hashing, and accessors come from record defaults; no behavior.

**Why:** Java 21 sealed types let the compiler enforce an exhaustive `switch` over
the variant set, which removes the megamorphic virtual-dispatch cost of an
`accept(visitor)` method on every node — every visitor call site stays
monomorphic after the dispatcher's switch resolves. Records give immutable
value-equality for free, useful for golden tests. The package is greenfield, so
the new idiom disturbs nothing else.

**Alternatives rejected:** abstract class + concrete subclasses (the codebase
default — `SQLBooleanExpression` plus 21 subclasses) keeps the per-node virtual
dispatch; an enum-tagged sum type with one all-fields class loses type safety; a
visitor-only API with no sealed root loses exhaustiveness enforcement.

**Caveat:** first sealed-type usage in the codebase — maintainers need a
one-paragraph orientation. Adding a sixth variant is an intended compile-time
break across the dispatcher and every visitor.

### D2 — Visitor as interface; static `switch` dispatch, no `accept(...)` (2026-05-20, carried 2026-06-24) [ctx=safe]

`AnalyzedExprVisitor<T>` declares one `visitX` method per variant. A single static
helper `AnalyzedExpr.dispatch(expr, visitor)` carries the one `switch` over the
sealed type and calls the right `visitX`. No `accept(visitor)` method on the
variants.

**Why:** one `visitX` per variant makes the IR's shape explicit and
self-documenting. The static dispatcher carries the `switch` once — the JIT lowers
it to a table jump and each `visitor.visitX(x)` is a direct monomorphic call. No
`accept(...)` keeps the nodes pure data.

**Alternatives rejected:** `accept(visitor)` on each node (classic Visitor — a
megamorphic virtual call per dispatch, multiplied across visitor implementations
in a long optimizer pipeline); raw `switch (expr)` at every call site (no
abstraction, repeated everywhere); abstract base class with default-recursing
`visit(...)`.

**Caveat:** the base `AnalyzedExprVisitor<T>` has **no** defaults — direct
implementers (the evaluator; future non-`AnalyzedExpr`-returning passes) must
enumerate every variant, so a new variant breaks them at compile time. The
relaxation for transform passes is scoped to `AnalyzedExprTransform` (see D9).

### D3 — Single `evaluate(Result, CommandContext)` overload (2026-05-20, carried 2026-06-24) [ctx=safe]

The evaluator exposes one `evaluate(expr, row, ctx)` overload over `Result`. The
rare `Identifiable`-only caller arriving in S1+ wraps its input in a synthetic
`Result` via a small adapter helper.

**Why:** the AST carries dual `(Result, …)` + `(Identifiable, …)` overloads for
historical reasons. The analyzed tree is greenfield and serves higher-layer
callers (executor steps, optimizer passes) that already operate on `Result`. A
single overload keeps every visitor from implementing two paths.

**Alternatives rejected:** dual overloads matching the AST's shape; a unified
`AnalyzedExprInput` wrapper abstraction.

**Caveat:** the adapter allocates a synthetic `Result` per `Identifiable` call —
trivial, and the visitor can be extended later without breaking existing code if a
hot path cannot tolerate the wrap.

### D4 — `Cast` variant dropped from S0 scope (2026-05-20, carried 2026-06-24) [ctx=safe]

The issue body lists a `Cast` variant; S0 ships without it.

**Why:** YouTrackDB grammar has **no** `CAST(x AS T)` form. Type coercion is
method-call syntax (`.asInteger()`, `.asDate()`) routed through
`SQLModifier.methodCall`, which is structurally a `FuncCall`. A dedicated `Cast`
variant would force S0 to carry the cast-target type taxonomy with no consumer. A
later slice can add `Cast` when explicit `CAST` grammar (or an optimizer reason)
arrives.

**Alternatives rejected:** keep `Cast` and lower method calls into it; ship `Cast`
as a placeholder with no lowering coverage (dead code).

**Caveat:** the YTDB-915 issue body still lists `Cast` — it needs an edit so the
acceptance criteria match what S0 delivers. (Carried as an open question.)

### D5 — Extract `NumericOps` to `core/.../sql/util/` (2026-05-20, carried 2026-06-24) [ctx=safe]

Move the numeric-promotion hierarchy plus per-operator null, Date+Long, and
String-concat handling out of `SQLMathExpression.Operator` into a neutral
`final class NumericOps` (private constructor, all-static) at
`core/.../sql/util/NumericOps.java`. Both the AST's `SQLMathExpression.Operator
.apply(...)` and the new evaluator delegate to it.

**Why:** if the AST and IR evaluators each held their own promotion logic, they
would drift on edge cases (integer-vs-double divide, null propagation, Date+Long).
A single shared helper makes divergence structurally impossible. `sql/util/` is a
fresh neutral location both layers can depend on.

**Alternatives rejected:** duplicate the promotion logic in the IR evaluator
(guarantees drift); place `NumericOps` inside `query/analyzed/` (forces a backward
dependency from the AST package to a sibling); place it under `sql/method/` (that
package already means typed method dispatch).

**Caveat:** new package, new team convention. No Spotless / `pom.xml` /
`--add-opens` changes — same `core` module. The extraction is a lift-and-shift;
its acceptance gate is "every existing AST math test passes after the delegation."

### D6 — `Var` carries a `List<String>` name path (2026-05-20, carried 2026-06-24) [ctx=safe]

`Var(List<String> path)` holds the unresolved lexical name path — `["name"]` for a
bare identifier, `["p", "name"]` for `p.name`. The lowerer flattens the AST's
`SQLBaseIdentifier.levelZero` + `suffix` chain into the list via an
`identifierToPath` mapper. Range-table resolution is explicitly an S10 non-goal.

**Why:** S10 will replace `Var` with range-table-resolved references. S0 should
hold the lexical shape — clean, self-contained, trivial to inspect during
evaluator debugging — not bake in a resolution model S10 would throw away.

**Alternatives rejected:** hold a reference to the original `SQLBaseIdentifier`
(raw AST interop); Postgres-style `Var(rtable_index, attnum)` with a resolved
schema binding (requires the S10 range-table model); opaque `name: String` with
embedded dot separators.

**Caveat:** when S10 lands, callers reading `Var.path()` migrate. Bounded blast
radius — only the lowerer and evaluator touch `Var` in S0.

### D7 — `UnsupportedAnalyzedNodeException extends CommandExecutionException` (2026-05-20, carried 2026-06-24) [ctx=safe]

The lowerer throws `UnsupportedAnalyzedNodeException(astNode.getClass())` for any
AST shape outside the S0 subset; it extends `CommandExecutionException`.

**Why:** `CommandExecutionException` is the established base for SQL
execution-time errors, and lowering happens in the same logical phase as execution
preparation. Carrying the unsupported AST class name gives an actionable
diagnostic.

**Alternatives rejected:** extend `BaseException` (less specific); extend
`ParseException` (parser-side, wrong layer); a new top-level type.

### D8 — `AnalyzedExprTransform` with structural sharing (2026-05-20, carried 2026-06-24) [ctx=safe]

`AnalyzedExprTransform extends AnalyzedExprVisitor<AnalyzedExpr>` is the
rewrite-pass shape for S3+ optimizer slices. A static `transformChildren(expr, t)`
helper recurses one level into a node's children and returns the **same instance**
when no child changed (reference identity `==`, not value `equals`), constructing
a new parent record only when at least one child changed. `FuncCall` lazily
allocates its new arg list on the first changed element.

**Why:** optimizer passes frequently rewrite one subtree and leave the rest
untouched. Structural sharing avoids rebuilding the whole tree on every pass — a
fold that fires at depth 10 of a 50-node tree allocates only the new node plus the
parent chain back to the root. Centralizing the identity-comparison logic in one
helper keeps individual passes from re-implementing (and subtly mis-implementing)
it.

**Alternatives rejected:** full rebuild on every transform (allocates
O(tree-size) per pass); transform returning `Optional<AnalyzedExpr>` to signal
change (wrapper allocations + every visitor must remember to unwrap).

**Caveat:** equality is by reference identity — a distinct record instance with
equal field values counts as "changed". Rule for transform authors: return the
input reference when no change applies; never reconstruct an `equals`-but-distinct
copy.

### D9 — Default methods on `AnalyzedExprTransform`, none on the base visitor (2026-05-20, carried 2026-06-24) [ctx=safe]

`AnalyzedExprTransform`'s five `visitX` methods carry recurse-into-children
defaults (leaf variants return self; compound variants call `transformChildren`).
The base `AnalyzedExprVisitor<T>` keeps no defaults.

**Why:** recurse-into-children is the right behavior for nearly every transform
pass — a pass that rewrites one variant wants the others to pass through. The
defaults remove four boilerplate overrides per pass while keeping strict
exhaustiveness on `AnalyzedExprVisitor<T>` (which the evaluator implements), so a
new IR variant still breaks the evaluator at compile time. Same shape as Calcite
`RexShuttle`, Spark `TreeNode.transform`, ANTLR `*BaseVisitor`.

**Alternatives rejected:** no defaults anywhere (verbose); defaults on the base
`AnalyzedExprVisitor<T>` (would force the evaluator to inherit no-op behavior and
silently lose exhaustiveness for `Object`-returning visitors); an opt-in
`RecursiveTransform` sub-interface (adds a second transform shape to the API).

**Caveat:** adding a new IR variant requires an **explicit audit** of every
transform pass — a pass needing special handling for the new variant fails
silently (default-recurses) rather than at compile time. Make the audit part of
the variant-addition checklist.

### Invariants to preserve (I1–I3)

- **I1 — Round-trip parity.** For every SQL fragment in the S0 covered subset,
  `lower(parse(sql)).evaluate(row, ctx)` is `Objects.equals` to
  `parse(sql).execute(row, ctx)`, including null and type-coercion outcomes.
  Asserted by a round-trip test class. The AST is the reference; a divergence is a
  real evaluator/`NumericOps` bug, never a reason to relax the test.
- **I2 — No silent fallback.** Lowering an unsupported AST shape throws
  `UnsupportedAnalyzedNodeException`; it never returns a placeholder or partial
  tree. A successful `lower(...)` return means full IR coverage of the input —
  the contract S1+ consumers rely on.
- **I3 — Exhaustive visitor dispatch.** Adding a new `AnalyzedExpr` variant is a
  compile-time break for every `AnalyzedExprVisitor<T>` implementation (sealed
  `switch`, no `default`).

### Scope and non-goals (2026-05-20, carried 2026-06-24) [ctx=safe]

In scope for S0: the substrate types, the visitor/transform framework, the
lowering pass over the covered subset (integer/string literals, column refs,
arithmetic `+ - * /`, comparisons `= != < <= > >=`, function calls, unary `NOT`
and unary minus), the evaluator, and round-trip parity tests.

Out of scope: executor-step migration (S1); identifier resolution against range
tables (S10); optimizer rewrites on the analyzed tree (S3+); DDL, scripts, and
MATCH-specific node shapes; the `Cast` variant (D4); serialization / plan-cache
representation for `AnalyzedExpr` (later slice); bind-parameter lowering (S0
throws — see Open Questions).

## Surprises & Discoveries

<!-- Codebase realities from the prior research. Class names and package
     presence re-confirmed on develop 6dba771b5c (2026-06-24); line numbers are
     stale and flagged for PSI re-verification. -->

- **The AST is union-style.** `SQLExpression` holds a fixed field set with one
  non-null per instance: `mathExpression` (arithmetic / identifier / function-call
  leaf path), `booleanExpression` (comparison, AND/OR — AND/OR out of S0 scope),
  `literalValue`, `booleanValue`, `isNull`. The lowerer field-walks these with
  `instanceof`; the AST has no visitor. *(Prior anchor `SQLExpression.java:25–37`;
  class confirmed present at `core/.../sql/parser/SQLExpression.java`, lines
  unverified.)*
- **Numeric promotion lives entirely in `SQLMathExpression.Operator`** — an enum
  where each operator (`PLUS`/`MINUS`/`STAR`/`SLASH`) overrides typed-pair
  `apply(...)` methods plus a fallback `apply(Object, Object)`; the shared entry
  `apply(Number, Operator, Number)` widens by the right operand's runtime type
  (Integer/Short → Long → Float → Double → BigDecimal). *(Prior anchors
  `SQLMathExpression.java:53–655` and `582–655`; class confirmed present, lines
  unverified.)* This is the body D5 extracts into `NumericOps`.
- **Subtle math semantics that existing AST tests pin** (must be preserved
  exactly across the AST/IR split): integer-divide returns the integer type when
  the remainder is zero, else widens to `Double`; `null + x = x`, `null - x =
  0 - x`, `null * x = null`, `null / x = null`; `Date + Long` and `Long + Date`
  produce a `Date`, `Date - Long` produces a `Date` (`Date - Date` is not handled
  by the AST and is out of scope); `+` does String concatenation when either
  operand is a `String` (`toString()`-ing the other).
- **YouTrackDB grammar has no `CAST`.** Type coercion is method-call syntax
  (`.asInteger()`, `.asDate()`) via `SQLModifier.methodCall`, structurally a
  function call — this drove D4 (drop `Cast` from S0).
- **No sealed types exist in the codebase yet** — D1's IR is the first use; the
  abstract-class-plus-subclasses idiom (e.g. `SQLBooleanExpression` + 21
  subclasses) is the incumbent pattern.
- **Comparison / function / identifier node shapes:** comparisons are concrete
  `SQLBinaryCondition extends SQLBooleanExpression` (`left: SQLExpression`,
  `operator`, `right: SQLExpression`); function calls are `SQLFunctionCall`
  (`name: SQLIdentifier`, `params: List<SQLExpression>`); identifiers are
  `SQLBaseIdentifier` carrying `levelZero` (a `SQLLevelZeroIdentifier`) + a
  `suffix` chain that `identifierToPath` flattens to `List<String>`. *(All classes
  confirmed present on develop; lines unverified.)*
- **`QueryOperatorEquals` is the existing equality routine** the evaluator should
  reuse for `EQ`/`NE` rather than reimplementing — confirmed present at
  `core/.../sql/operator/QueryOperatorEquals.java`. Ordering comparisons reuse the
  AST's compare path (extract a small helper if no clean reuse point exists).
- **The target packages do not exist yet — confirmed absent on develop
  (2026-06-24):** `core/.../query/analyzed/` (the substrate home) and
  `core/.../sql/util/` (the `NumericOps` home). The substrate was never built on
  this branch — it sits exactly at develop's tip with no commits of its own.

## Open Questions

1. **Unary-minus AST node (math side).** The prior research found
   `SQLNegateCondition` for boolean `NOT`, but the math-side unary-minus node was
   left "to be confirmed at Phase A via PSI" — it may be a dedicated class or a
   flag on `SQLBaseExpression`. Resolve via PSI before the lowering design.
2. **Bind parameters (`?`, `:name`).** S0 throws `UnsupportedAnalyzedNodeException`
   at the leaf. Open: does a future slice lower them to a dedicated `Param` variant
   or thread bound values through `CommandContext` at evaluate-time? (Prior design
   leaned toward evaluate-time threading.)
3. **`NumericOps` delegation shape.** Keep the typed `apply(...)` fast-path
   overloads forwarding to `NumericOps`, or collapse to a single delegating path?
   The prior plan deferred this to "Phase A, based on JMH risk."
4. **YTDB-915 issue body lists `Cast`** (D4 drops it) — the issue body needs an
   edit so its acceptance criteria match what S0 delivers.
5. **Code anchors are ~100+ commits stale.** Re-verify the `SQLExpression` union
   field set, the `SQLMathExpression.Operator.apply` location/shape, the
   `SQLBaseIdentifier` `levelZero`+`suffix` shape, and the `QueryOperatorEquals`
   reuse point via PSI before re-authoring the design.
6. **Does the S0 plan still hold on current develop?** Re-validate the four-track
   decomposition (T1 substrate skeleton → T2 `NumericOps` → T3 lowering → T4
   evaluator + round-trip, with the T1→T3/T4 and T2→T4 dependency edges) and the
   slice boundaries against the present SQL layer before committing to a plan.

## Adversarial gate record

<!-- No adversarial gate has run on this log. It was distilled, not authored
     through the live Phase-0 loop. The resumed /create-plan Phase-0→1 gate
     (create-plan §Step 4) writes the first verdict heading here. -->
