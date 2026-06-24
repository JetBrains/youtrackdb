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

### D5-R — `NumericOps` extraction is whole-enum lift-and-shift (2026-06-24, settled with user) [ctx=safe]

Supersedes the extraction-boundary half of D5. All 12 `SQLMathExpression.Operator`
constants' promotion logic — typed-pair `apply(...)` overloads, the shared
`apply(Number, Operator, Number)` widening, per-operator null / Date+Long /
String-concat handling, and the `toLong` helper — moves to `final class NumericOps`
in `core/.../sql/util/`. `SQLMathExpression.Operator.apply(...)` becomes a thin
delegator into `NumericOps`. The `core/.../query/analyzed/` evaluator delegates to
the same `NumericOps` for `+ - * /`.

**Why:** a clean single-home boundary beats a partial extraction whose shared
widening helper still straddles two classes. With all promotion logic in one place,
AST/IR drift is structurally impossible (the original D5 rationale) and there is no
ambiguous "who owns the shared helper" seam. The larger diff is acceptable because
every existing AST math test is the acceptance gate — a lift-and-shift that keeps
all math tests green is self-verifying.

**Alternatives rejected:** narrow extraction of only `+ - * /` (leaves the shared
widening helper split across `NumericOps` and `Operator`, an unclean seam);
deferring the boundary to Phase A (the boundary is now understood, no reason to
defer). Both were on the table at re-validation; the user chose whole-enum.

**Risks/Caveats:** larger blast radius on `SQLMathExpression` than the narrow option
— the regression surface is the full existing math-test suite, which must stay green
after the delegation. The 8 operators outside the S0 IR subset (`REM`, shifts,
bitwise, `NULL_COALESCING`) get extracted but have no S0 IR consumer; that is
intended (they keep working through the AST delegator).

### Covered-subset wording correction (2026-06-24, settled) [ctx=safe]

Drop "unary minus" from the S0 covered subset. No general unary-minus AST node
exists (only a parse-time `sign` flag on numeric literals → negative `Const`), so
the lowering pass never produces `UnaryOp(MINUS)`. `UnaryOp` stays in the IR,
justified by boolean `NOT` (`SQLNotBlock`) alone. The YTDB-915 issue body (which
lists both `Cast` and unary minus) needs an edit to match S0 delivery — carried as
an open item, same as the D4 `Cast` issue-body edit.

### D10 — `SQLParenthesisExpression`: recurse on `expression`, throw on `statement`/CASE (2026-06-24, gate iter1 A1) [ctx=safe]

A parenthesized arithmetic expression `(a + b) * c` is in the S0 covered subset.
`SQLParenthesisExpression` carries two mutually-exclusive payloads
(`SQLParenthesisExpression.java:38-72`): `expression` (a parenthesized
`SQLExpression`, pure grouping — `execute` delegates straight to
`expression.execute`) and `statement` (a subquery). The lowerer lowers the grouping
form by recursing (`lower(expression)`) — parentheses affect nesting, which the IR
tree already expresses, so no `Paren` IR variant is needed. Only `statement != null`
and `CaseExpression` throw `UnsupportedAnalyzedNodeException`.

**Why:** parentheses are the user's precedence-override mechanism; classing every
`ParenthesisExpression` as a throw-case (the original re-validation wording) makes I1
unsatisfiable on `(a + b) * c`, the most common precedence-override input. The
grouping wrapper is transparent at evaluate time, so recursing reproduces the AST
exactly.

**Alternatives rejected:** a dedicated `Paren` IR variant (the IR tree's nesting
already encodes grouping — a paren node would be redundant and the optimizer would
have to strip it); throwing on all parens (breaks I1 — the blocker that surfaced
this).

**Implemented in:** the lowering track. **I1 test obligation:** add `(a + b) * c`
and `a * (b + c)` round-trip cases.

### D11 — IR comparison evaluator replicates `SQLBinaryCondition.evaluate` (collate + parser-operator), not bare statics (2026-06-24, gate iter1 A2/A3) [ctx=safe]

The IR comparison evaluator reproduces `SQLBinaryCondition.evaluate(Result, ctx)`'s
exact sequence: fetch `collate = left.getCollate(...)` (fallback
`right.getCollate(...)`), and when non-null apply `collate.transform(...)` to both
operands; then delegate to the **parser `SQLBinaryCompareOperator` instance's**
`execute(session, l, r)` — the same operator object the AST holds — rather than
calling `QueryOperatorEquals.equals` / `SQLBinaryCompareOperator.doCompare`
statically.

**Why:** two parity gaps in the "comparison parity is structural" claim. (1)
Collation: the AST transforms both operands through the property's `Collate` before
comparing, so `name = 'Foo'` against a `ci`-collated `name` returns `true` in the AST
but `false` for a raw static `equals`. (2) Session threading: `SQLEqualsOperator`
passes the real session to `QueryOperatorEquals.equals` while `SQLNeOperator` passes
`null`, which changes `PropertyTypeInternal.convert` behavior for type-coercing
comparisons. Delegating to the parser operator instance runs the AST's exact code,
so both nuances are reproduced by construction — parity becomes genuinely
structural. Note D3's single-`Result`-overload choice is reinforced here: the
`Identifiable` AST overload deliberately skips collation, so the IR must follow the
`Result` overload (the collation-applying path the executor uses).

**Alternatives rejected:** bare static `QueryOperatorEquals.equals` + `doCompare`
(drops collation and the NE null-session nuance — the blocker/should-fix that
surfaced this); excluding collated columns from the subset (collation is a per-property
attribute, not syntactic, so it cannot be excluded at the expression level).

**Precision note (gate iter2 A10):** collation parity comes from the explicit
`getCollate` + `transform` step, and the parser-operator delegation reproduces the
session threading and the operator's compare semantics — they are two distinct
mechanisms, both required. `SQLBinaryCondition.evaluate(Result, ctx)` also has an
in-place comparison fast path (`tryInPlaceComparison`); it is parity-equivalent
(returns `null` / falls back to the collation-applying slow path), so the **slow
path is the parity reference** the IR evaluator and the I1 tests target.

**Implemented in:** the evaluator track. **I1 test obligation:** add a collated-column
comparison case (`ci` string property, mixed-case literal) and a type-coercing NE case.

### D12 — Precedence fold: lowerer builds nested `BinaryOp` by structural precedence-climbing; value semantics come from shared `NumericOps` (2026-06-24, gate iter1 A5) [ctx=safe]

The lowerer converts the flat `SQLMathExpression` (`childExpressions` + `operators`)
into a nested `BinaryOp` IR tree by a **structural** precedence-and-associativity
fold (standard precedence-climbing keyed on `Operator.getPriority()` with `<=`
left-associative reduction, matching the AST's `iterateOnPriorities`). The AST's
own fold (`calculateWithOpPriority` / `iterateOnPriorities`) is **left untouched**.

**Why:** two folds is the drift surface D5/D5-R eliminated for promotion, so the
gate (A5) rightly asked whether to share one fold. But the share-the-fold options
penalize the live path: the AST fold is the **hot** math-eval path
(`iterateOnPriorities` drives it), while the IR fold is **cold** in S0 (no IR
consumer until S1). Extracting a generic fold parameterized by a combiner
(`apply` for eval, `new BinaryOp` for lowering) would inject a functional-interface
call into the hot AST eval loop — bimorphic across two call sites, likely not
inlined — which runs directly against the codebase's monomorphic-dispatch grain
(the same grain D1/D2 cite). Crucially, the fold the lowerer reimplements is
**purely structural** (it determines nesting only); all *value* semantics — null
sentinel, numeric promotion, Date+Long, String-concat — come from the shared
`NumericOps` (D5-R) at evaluate time. So the duplicated logic is just
precedence+associativity nesting (textbook, low-risk), not the value engine, and
the genuine drift surface (promotion) stays single-homed.

**Alternatives rejected:** generic shared fold with a combiner lambda (hot-path
virtual-call cost on the live AST eval path, contra the perf lens); a flat
`childExpressions`-mirror IR node sharing the fold at evaluate time (defeats the
nested-tree IR the S3+ optimizer rewrites need).

**Implemented in:** the lowering track. **I1 test obligation:** a precedence-mixing
parity matrix — `a+b*c`, `a*b+c`, `a-b-c`, `a-b+c`, `a/b/c`, `a-b*c+d`, plus the
parenthesized D10 cases — asserting the lowered tree evaluates `Objects.equals` to
the AST.

### D13 — Track decomposition resolved: four tracks, lowering track absorbs paren/collate/precedence coverage (2026-06-24, gate iter1 A4 / resolves OQ6) [ctx=safe]

The S0 work decomposes into four tracks, dependency-ordered:

- **T1 — substrate + framework:** `AnalyzedExpr` sealed IR (5 variants), the
  `AnalyzedExprVisitor<T>` + static `dispatch`, `AnalyzedExprTransform` +
  `transformChildren`, and `UnsupportedAnalyzedNodeException`. New package
  `core/.../query/analyzed/`. No dependencies.
- **T2 — `NumericOps` whole-enum extraction (D5-R):** new `core/.../sql/util/NumericOps.java`;
  `SQLMathExpression.Operator.apply` becomes a thin delegator. Acceptance gate = the
  existing math-test suite stays green. Independent of T1 (touches the AST side only).
- **T3 — lowering pass:** `SQLExpression`/`SQLMathExpression`/`SQLBooleanExpression`
  → `AnalyzedExpr`, including the D10 parenthesis recursion and the D12 structural
  precedence fold. Depends on T1 (needs the IR types).
- **T4 — evaluator + round-trip parity:** the `AnalyzedExprVisitor`-based evaluator
  (D11 comparison reuse, `NumericOps` for arithmetic) and the I1 round-trip test
  suite (D10/D11/D12 test obligations). Depends on T1, T2 (NumericOps), and T3
  (lowering, to produce trees to evaluate).

**Why:** the boundaries follow the natural artifact seams (types / shared util /
lowering / evaluation+tests) and keep each track independently reviewable. The
lowering track (T3) is heavier than the prior sketch — it owns the parenthesis
recursion (D10) and the structural precedence fold (D12) — so its scope indicator
must say so; the `NumericOps` track (T2) is the whole-enum lift-and-shift (D5-R),
larger than the prior narrow sketch. Final step-level sizing is deferred to Phase A
as usual, but the track shape and scope are fixed here.

**Alternatives rejected:** folding T2 into T3 (couples an AST-side refactor with the
new IR lowering — separately reviewable, separately mergeable, so keep apart);
folding T4's evaluator into T3 (lowering and evaluation are distinct visitor
implementations with distinct review surfaces; the round-trip suite is the T4
deliverable that needs both).

**Implemented in:** governs the Phase-1 track files.

### D14 — Lowerer field-walk is exhaustive-or-throw; `value` field flagged for Phase-A PSI (2026-06-24, gate iter1 A6) [ctx=safe]

The lowerer's `SQLExpression` field-walk dispatches on the recognized in-subset
fields and throws `UnsupportedAnalyzedNodeException` on **anything else**, so I2
(no silent fallback; a successful `lower` means full coverage) holds by construction
regardless of which fields a future parser change adds. The inherited `SimpleNode.value`
field and the "old executor" fallback chain in `SQLExpression.execute`
(`SQLExpression.java:99-119`, commented "only for old executor (manually replaced
params)") are flagged for **Phase-A PSI verification**: confirm whether `value` is
ever non-null on the modern parser path. If dead on the modern path, the field-walk
may ignore it; if reachable, the exhaustive-or-throw default already makes lowering
throw rather than mis-read.

**Why:** the original field enumeration omitted `value`; asserting field-walk
completeness over an incomplete inventory is unsound. Defaulting to throw-on-unknown
makes I2 robust to the inventory gap, and the explicit Phase-A check closes it.

**Alternatives rejected:** enumerate-and-assume-complete (unsound — the surfaced
`value` gap is the counterexample); handle `value` speculatively now (premature —
verify reachability first).

**Implemented in:** the lowering track + a Phase-A verification note.

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

### Re-validation against develop 6dba771b5c (2026-06-24, PSI/grep pass) [ctx=safe]

Findings from re-verifying the stale anchors before re-authoring the design. The
carried D-decisions D1–D4, D6–D9 and invariants I1–I3 survive; D5's scope and the
covered-subset wording need revision (see Open Questions and the revised entries).

- **`SQLMathExpression.Operator` now has 12 constants, not 4:** `STAR(10)`,
  `SLASH(10)`, `REM(10)`, `PLUS(20)`, `MINUS(20)`, `LSHIFT(30)`, `RSHIFT(30)`,
  `RUNSIGNEDSHIFT(30)`, `BIT_AND(40)`, `XOR(50)`, `BIT_OR(60)`, `NULL_COALESCING(25)`.
  The numeric arg is the precedence priority (lower binds tighter). S0's lowering
  subset is still only `+ - * /`; the other 8 are out of subset (lower → throw).
  This widens D5's `NumericOps` extraction surface — see the revised D5 note.
- **`SQLMathExpression` is an n-ary FLAT list, not a binary tree.** Fields
  `childExpressions: List<SQLMathExpression>` + `operators: List<Operator>`. The
  grammar rule `MathExpression()` (`YouTrackDBSql.jjt:2273`) collects ALL operators
  of mixed precedence into one flat list at one nesting level
  (`FirstLevelExpression() ( <op> FirstLevelExpression() )*`), then
  `unwrapIfNeeded()` collapses a single-child node to its child. **Precedence is
  resolved at execute time**, not parse time, by a shunting-yard fold
  (`calculateWithOpPriority` → `iterateOnPriorities`, using `Operator.getPriority()`
  and `<=` left-assoc reduction). **Lowering implication:** the lowerer must
  replicate this precedence fold to build a correctly-nested `BinaryOp` IR tree, or
  round-trip parity (I1) breaks on any expression mixing precedence levels
  (`a + b * c`). The prior design treated math as already-binary; it is not. This
  is the heaviest re-validation finding and reshapes the lowering track.
- **No general unary-minus AST node exists.** Unary minus is only a parse-time
  `sign = -1` flag on numeric literals (`YouTrackDBSql.jjt:941,956`), folded into
  the `SQLNumber` value. There is no `-expr` node for non-literals. So lowering
  never emits a `UnaryOp(MINUS)`; negative literals lower to a negative `Const`.
  The covered-subset phrase "unary minus" (issue + prior scope) is therefore
  vacuous on the lowering side — `UnaryOp` is justified by boolean `NOT` alone.
  (Resolves Open Question 1.)
- **Boolean NOT node is `SQLNotBlock`, not the stale `SQLNegateCondition`** (which
  does not exist on develop). `SQLNotBlock extends SQLBooleanExpression` carries
  `sub: SQLBooleanExpression` + `negate: boolean`; `negate=false` is a pass-through.
  Lowering maps `SQLNotBlock(negate=true, sub)` → `UnaryOp(NOT, lower(sub))`.
- **`SQLExpression` union is wider than the log recorded.** Current non-null-one-of
  fields: `rid` (SQLRid), `mathExpression`, `arrayConcatExpression` (SQLArrayConcat),
  `json` (SQLJson), `booleanExpression`, `booleanValue`, `literalValue`, `isNull`,
  plus `singleQuotes`/`doubleQuotes` flags. The S0 subset still covers only
  `mathExpression` / `booleanExpression` / `literalValue` / `booleanValue` / `isNull`;
  the lowerer's field-walk must throw `UnsupportedAnalyzedNodeException` on `rid`,
  `arrayConcatExpression`, and `json` (out of subset). Confirms I2 (no silent
  fallback) matters here.
- **`SQLBaseExpression` (a `SQLMathExpression`) leaf shape:** one of `number`
  (SQLNumber → `Const`), `identifier` (SQLBaseIdentifier) + optional `modifier`
  (bare → `Var`; with modifier → method-call `FuncCall`), `inputParam`
  (InputParameter → bind param, S0 throws), `string`/CHARACTER_LITERAL + optional
  modifier (→ `Const`, or method-call `FuncCall`). **`SQLParenthesisExpression`
  has two mutually-exclusive payloads** (`SQLParenthesisExpression.java:38-72`):
  `expression != null` is a transparent arithmetic grouping wrapper whose
  `execute` delegates to `expression.execute(...)` — it is **in-subset** and the
  lowerer must recurse (`lower(expression)`), not throw (see D10); only
  `statement != null` (a subquery) and `CaseExpression` (CASE WHEN) are out of S0
  scope (throw). Function calls proper are `SQLFunctionCall` (`name: SQLIdentifier`,
  `params: List<SQLExpression>`).
- **Comparison shape + reuse point (refines the QueryOperatorEquals note).**
  Comparisons are `SQLBinaryCondition extends SQLBooleanExpression`
  (`left: SQLExpression`, `operator: SQLBinaryCompareOperator`, `right: SQLExpression`).
  `evaluate` calls `operator.execute(session, leftVal, rightVal)`. The six S0
  operators: `SQLEqualsOperator` (=), `SQLNeOperator`/`SQLNeqOperator` (!=, <>),
  `SQLLtOperator` (<), `SQLLeOperator` (<=), `SQLGtOperator` (>), `SQLGeOperator` (>=).
  `SQLEqualsOperator.execute` → `QueryOperatorEquals.equals(session, l, r)`;
  `SQLNeOperator` → the negation **with a `null` session** (`SQLNeOperator.java:23`,
  vs `SQLEqualsOperator.java:27` which passes the real session); the ordering
  operators → `SQLBinaryCompareOperator.doCompare(l, r)` compared to 0.
  **Comparison parity is NOT structural via the bare static methods** — two
  corrections (see D11): (1) `SQLBinaryCondition.evaluate(Result, ctx)`
  (`SQLBinaryCondition.java:99-109`) first fetches `collate = left.getCollate(...)`
  (falling back to `right.getCollate(...)`) and, when non-null, applies
  `collate.transform(...)` to **both** operands before `operator.execute` — so a
  comparison against a collated column (e.g. a case-insensitive `ci` string
  property) diverges unless the IR evaluator replicates the collate transform; and
  (2) EQ and NE thread the session differently (real vs `null`), so calling one
  shared `QueryOperatorEquals.equals(session, ...)` for both drifts from the AST on
  NE. The fix (D11): the IR comparison evaluator replicates the exact
  `SQLBinaryCondition.evaluate` sequence — fetch collate, transform both operands,
  then delegate to the **parser `operator` instance's** `execute(session, l, r)`,
  not the bare static method. This makes comparison parity genuinely structural
  (it runs the same code the AST runs) and subsumes the EQ/NE session nuance and
  the ordering `doCompare`-vs-0 mapping.
- **`SQLBaseIdentifier` shape confirmed:** `levelZero: SQLLevelZeroIdentifier` |
  `suffix: SQLSuffixIdentifier` (one non-null). The `identifierToPath` flattening
  (D6) walks whichever is set; the exact `SQLSuffixIdentifier` suffix-chain shape is
  a lowering-design detail to nail in Phase A.
- **D3 dual-overload premise holds:** `SQLMathExpression`/`SQLExpression` carry both
  `execute(Identifiable, ctx)` and `execute(Result, ctx)`; the IR evaluator's single
  `(Result, ctx)` overload is still the right call.

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

### Re-validation update (2026-06-24) [ctx=safe]

- **OQ1 (unary-minus node) — RESOLVED.** No general unary-minus node; only a
  parse-time `sign` flag on numeric literals. Lowering emits negative `Const`, never
  `UnaryOp(MINUS)`. Drop "unary minus" from the covered-subset wording; keep
  `UnaryOp` for boolean `NOT` (`SQLNotBlock`).
- **OQ4/OQ5 (stale anchors, Cast) — RESOLVED.** AST shapes re-verified (see
  Re-validation block above). `Cast` confirmed absent from grammar; D4 holds; the
  YTDB-915 issue body still lists `Cast` and needs an edit to match S0 delivery.
- **OQ3 (`NumericOps` delegation shape) — REFRAMED + ESCALATED.** The enum now has
  12 operators sharing one widening helper `apply(Number, Operator, Number)`. New
  question: does S0 extract only the `+ - * /` promotion logic (the IR subset) or
  the whole 12-operator surface, and where is the extraction boundary given the
  shared widening helper all 12 use? See revised D5 below. JMH/fast-path question is
  secondary to this boundary question.
- **OQ2 (bind parameters) — still open**, unchanged: S0 throws at the
  `inputParam` leaf; future-slice lowering shape (dedicated `Param` vs evaluate-time
  threading) deferred.
- **OQ6 (track decomposition) — needs re-discussion at planning.** The four-track
  shape is plausible, but the lowering track (T3) is heavier than assumed (it must
  carry the precedence fold), and the `NumericOps` track (T2) scope depends on the
  D5 boundary decision. Revisit track sizing once D5 is settled.

### Revised D5 (proposed 2026-06-24, pending user confirmation) [ctx=safe]

D5 carried "extract the numeric-promotion hierarchy out of
`SQLMathExpression.Operator` into `NumericOps`". The 12-operator reality makes the
extraction boundary a live decision:

- **Option A (narrow):** extract only the `+ - * /` promotion paths the IR evaluator
  needs, plus the shared `apply(Number, Operator, Number)` widening they invoke.
  Smallest surface, smallest parity risk, but the widening helper is shared with the
  other 8 operators, so the extraction boundary is not clean (either move the shared
  helper too and have `SQLMathExpression.Operator` delegate back, or duplicate it).
- **Option B (whole-enum lift-and-shift):** move all 12 operators' promotion logic to
  `NumericOps`; `SQLMathExpression.Operator.apply` becomes a thin delegator. Clean
  boundary, larger diff, every existing math test is the acceptance gate.

**Why this matters:** the only S0 *consumer* is the IR evaluator over `+ - * /`. D5's
original rationale (avoid AST/IR drift) only requires the four operators the IR uses.
Whole-enum extraction is a cleaner boundary but a bigger, riskier change with no S0
consumer for the extra 8 operators. Recommend Option A unless the user wants the full
lift-and-shift now. Decision pending.

### Gate iteration-1 resolutions (2026-06-24) [ctx=safe]

- **OQ3 (NumericOps shape) — SETTLED:** user chose whole-enum lift-and-shift (D5-R).
  The "Recommend Option A" line above is superseded by D5-R.
- **OQ6 (track decomposition) — RESOLVED into D13** (four tracks T1–T4; lowering
  track absorbs the D10 parenthesis + D12 precedence coverage; T2 is the D5-R
  whole-enum extraction). Closes gate finding A4.
- **OQ2 (bind parameters) — out of S0 scope, explicitly:** S0's disposition is
  decided (throw `UnsupportedAnalyzedNodeException` at the `inputParam` leaf,
  consistent with I2). Only the *future-slice* shape is open; it gates no S0
  artifact. Annotated per gate finding A8 so it is not mistaken for a blocking gap.
- **A7/A9 (perf-lens suggestions) — noted:** D5-R adds no hot-path indirection
  (static, JIT-inlinable; the existing `MathExpressionTest` is the acceptance gate);
  the D1/D2 monomorphic-dispatch rationale is a forward bet for the S1+ optimizer
  pipeline, not a measured S0 win (no live IR consumer in S0). Both rationales hold;
  no decision change.

## Adversarial gate record

### Adversarial review of this log (2026-06-24) — NEEDS REVISION: 2 blocker, 4 should-fix, 3 suggestion

Iteration 1. Review file: `_workflow/reviews/research-log-adversarial-iter1.md`.
Lenses: Architecture / cross-component coordination, Performance hot path. Blockers
A1 (parenthesized arithmetic in-subset) and A2 (collation in comparisons) and
should-fix A3–A6 addressed by D10–D14 and the Re-validation-block corrections;
suggestions A7–A9 annotated. Re-challenged at iteration 2.

### Adversarial review of this log (2026-06-24) — PASS

Iteration 2. Review file: `_workflow/reviews/research-log-adversarial-iter2.md`.
All iteration-1 findings A1–A9 VERIFIED (blockers A1/A2 resolved by D10/D11;
should-fix A3–A6 by D11/D13/D12/D14). One new suggestion A10 (the
`tryInPlaceComparison` fast path is parity-equivalent; the slow path is the parity
reference) folded into D11's precision note — no decision change. Gate clears; the
log is frozen-ready as the Phase-1 design seed.
