<!-- workflow-sha: d2dfcc2d44fabd3ac76c5fd7620f1e6013675ad9 -->
# Track 1: Shared MATCH IR builders + GQL adoption + `IS DEFINED` / `IS NOT DEFINED` builder factories

## Purpose / Big Picture
After this track, MATCH IR construction lives in one shared package that both the GQL front-end and the upcoming Gremlin translator call, and the translator has presence-operator factories (`IS DEFINED` / `IS NOT DEFINED`) ready to wire.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Foundation track: creates the shared `match/builder/` package consumed by both GQL and the upcoming Gremlin translator, and exposes `MatchWhereBuilder.isDefined` / `isNotDefined` factories wrapping the pre-existing `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` AST nodes (D-IS-DEFINED) — wiring only, no grammar / parser / evaluator changes. It ships independently of the translator because GQL adopts the builders on its own, so the track is an independently reviewable, independently mergeable PR even though it is below the ~12-file track floor.

## Progress

> Executed through the full Phase A → B → C workflow. Phase A ran the
> technical and adversarial reviews (`reviews/track-1-technical.md`,
> `reviews/track-1-adversarial.md`); Phase B delivered the shared builder
> package, the behavior-preserving GQL refactor, and the presence-operator
> factories, verified green by the Track 1 test suite (187 tests). Track 1 is
> the only executed track on this branch — Tracks 2–6 remain not started.

- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [x] Track-level code review
- [x] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

- **scope-down** — `MatchWhereBuilder.endsWith` / `matchesRegex` deferred to
  Track 4. Their AST backing (`SQLEndsWithCondition`, `SQLMatchesCondition`
  find-mode) is created by Track 4's D-TEXT-OPS work and does not exist at the
  baseline this track builds on; constructing the methods here would forward-
  reference a later track. The baseline-backed text predicates `containsText`
  (`SQLContainsTextCondition`) and `startsWith` (half-open `>= AND <` range)
  ship in this track. See Episodes §Step 2.
- **dependency-reveal** — the `SQLInCondition` operator field had no public
  setter at decomposition time; Track 1 initially used reflection via
  `SqlInOperatorBinding`, then replaced it with public `get/setOperator`
  accessors on `SQLInCondition` (same pattern as `SQLIsDefinedCondition`).
  See Episodes §Step 2.

## Outcomes & Retrospective
- Phase A technical review: PASS — 6 should-fix + 4 suggestions, all resolved
  in step decomposition (`reviews/track-1-technical.md`).
- Phase A adversarial review: PASS — 1 blocker (A2, `buildWhereClause`
  call-site breakage) resolved via the package-private static-delegate
  strategy; remaining should-fix / suggestion findings resolved or deferred
  with rationale (`reviews/track-1-adversarial.md`).
- Phase C track-level review: PASS. The track is below the high-risk
  dimensional-review threshold (foundation builders, no concurrency / crash /
  security surface), so the always-on track-level review plus the builder and
  GQL test suites were the gate.
- Completion summary: the shared `match/builder/` package and the
  behavior-preserving GQL refactor landed green — 91 `GqlMatchStatementTest`
  cases pass and four `GqlMatchStatementPlanPrettyPrintTest` cases pin plan
  shape via fragment assertions. The presence-operator factories (`isDefined` /
  `isNotDefined`) are ready for Track 4's `has(key)` / `hasNot(key)` mapping.

## Context and Orientation
The MATCH IR classes (`Pattern`, `PatternNode`, `PatternEdge`, `SQLMatchExpression`, `SQLMatchPathItem`, `SQLMatchFilter`, the `SQLBooleanExpression` hierarchy) already exist and are constructed inline today in two places: the SQL `MATCH` parser path and `GqlMatchStatement`. `GqlMatchStatement.buildPlan` builds a `PatternNode` per `SQLMatchFilter`, populates `aliasClasses` / `aliasFilters`, and uses two private helpers — `buildWhereClause(Map<String,Object>)` (AND-block of equality conditions) and `toLiteral(Object)` (Java value → `SQLExpression`).

The presence operators already exist in the grammar: `core/src/main/grammar/YouTrackDBSql.jjt` has `IsDefinedCondition` / `IsNotDefinedCondition` productions (≈ lines 2897-2913), with AST classes `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` under `internal/core/sql/parser/`. Their `evaluate` routes call `expression.isDefinedFor(...)`, the entity-presence primitive that separates *absent* from *present-with-null*. `isIndexAware()` is already `false`. Track 1 adds no grammar, AST, or evaluator code — only builder factories that construct these existing nodes.

New package: `internal/core/sql/executor/match/builder/`.

## Plan of Work
1. Create the shared builder package with three pure-helper classes over the existing IR:
   - `MatchPatternBuilder` — `addNode(alias, className, where, optional)`, `addEdge(from, to, dir, label, edgeAlias, edgeFilter, while_, maxDepth)`, `build()` returning a small `PatternIR` value (pattern + `aliasClasses` + `aliasFilters`) so callers don't assemble from separate getters.
   - `MatchWhereBuilder` — `eq` / `op` / `in` / `between` / `containsText` / `startsWith` / `endsWith` / `matchesRegex` / `and` / `or` / `not` / `wrap`, plus `isDefined(field)` / `isNotDefined(field)` (this track's presence factories).
   - `MatchLiteralBuilder` — `toLiteral(Object)`.
2. Add `isDefined` / `isNotDefined` factories: each constructs the existing `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition`, wires the `SQLExpression` child to point at `field` on the boundary alias, and returns it as a `SQLBooleanExpression`.
3. Refactor `GqlMatchStatement` onto the builders, behavior-preserving (D6): the `for` loop calls `MatchPatternBuilder.addNode(...)`; `buildWhereClause` becomes a chain of `MatchWhereBuilder.eq(field, MatchLiteralBuilder.toLiteral(value))` + `.and(...).wrap()`; `toLiteral` becomes a one-line delegate. Public API and existing GQL test assertions unchanged.
4. Builder unit tests: round-trip pins for the presence factories (`isDefined("foo")` renders `"foo is defined"`, `isNotDefined("foo")` renders `"foo is not defined"`); prettyPrint fragment tests for the GQL refactor. The existing parser/evaluator are already SQL-tested, so this track tests only the builder wrappers and plan shape.

Ordering: builders first, then the GQL refactor consumes them, then the presence factories (independent of GQL). The GQL refactor must keep GQL's tests green at each step.

## Concrete Steps

1. Create `MatchLiteralBuilder` (verbatim extraction of `GqlMatchStatement.toLiteral`) — `risk: low`  [x]
2. Create `MatchWhereBuilder` + presence factories `isDefined` / `isNotDefined` — `risk: medium`  [x]
3. Create `MatchPatternBuilder` (node / edge accumulation, `PatternIR` snapshot) — `risk: low`  [x]
4. Refactor `GqlMatchStatement` onto the builders + prettyPrint plan regression tests — `risk: medium`  [x]

## Episodes

### Step 1 — commit d347090823, 2026-06-25 [ctx=safe]
**What was done:** Created
`core/.../sql/executor/match/builder/MatchLiteralBuilder.java` as a final
class with a private constructor and one `public static SQLExpression
toLiteral(Object value)` method, the body extracted verbatim from
`GqlMatchStatement.toLiteral`. The class Javadoc documents the per-type AST
routing (String, Number, Boolean, Date, List, Set, Map, byte[],
`RecordIdInternal`) and the deliberate NPE-on-null contract.
`MatchLiteralBuilderTest` (15 tests) covers every branch including the empty
string, each numeric subtype, the RID path, the collection / map paths, the
unsupported-type `IllegalArgumentException`, and the documented null NPE.

**What was discovered:**
- `RecordIdInternal` is a sealed interface, so tests use the concrete
  `RecordId` record for the RID path.
- `SQLBaseExpression(String)` encodes its input as a quoted, escaped literal,
  so the stored `string` field never equals the raw input — tests assert on
  the decoded value, not raw equality.

**What changed from the plan:** none.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchLiteralBuilder.java` (new)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchLiteralBuilderTest.java` (new)

**Cross-track impact:** none — the class has no call sites until Step 4 and later tracks.

### Step 2 — commit 64871ba7c5, 2026-06-25 [ctx=safe]
**What was done:** Created the stateless fluent `MatchWhereBuilder` with
`eq`, `op`, `in`, `notIn`, `between`, `containsText`, `startsWith`,
`and`/`or`/`andOptional`, `not`, the three `isNull` forms, `wrap`, and the
two presence factories `isDefined` / `isNotDefined`. Each method returns the
parser-emitted AST shape, so a built tree is interchangeable with one parsed
from SQL text. `startsWith` compiles to a half-open `field >= prefix AND
field < prefix⁺` range (last-code-point increment, surrogate-pair and
overflow-carry safe) rather than a `LIKE`, keeping the predicate index-aware.
`in` wires the `SQLInCondition` operator via `setOperator(new SQLInOperator(-1))`
after adding public `get/setOperator` accessors on `SQLInCondition` (same
pattern as the presence nodes). The presence factories construct the existing
`SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` and wire their child
`SQLExpression` via newly added `get/setExpression` accessors.
`MatchWhereBuilderTest` (41 tests) pins each AST shape and a rendered-SQL sample.

**What was discovered:**
- `SQLOrBlock` exposes only `addSubBlock`, while `SQLAndBlock` also has
  `setSubBlocks`; the builder uses each accordingly to match the parser emit.
- `SQLNotBlock.negate` defaults to `false` — a silent pass-through — so
  `not(...)` sets `negate=true` explicitly (technical finding T1).
- `SQLNotInCondition` has no reachable public setters, so `notIn` composes as
  `not(in(...))` rather than constructing the dedicated node (T2 / A6).

**What changed from the plan:** `endsWith` and `matchesRegex` were deferred to
Track 4. Their AST backing (`SQLEndsWithCondition`, `SQLMatchesCondition`
find-mode) is introduced by Track 4's D-TEXT-OPS work and is absent at this
track's baseline, so building them here would forward-reference a later track.
The two presence AST nodes gained `get/setExpression` accessors — a minimal
parser-package edit (no grammar / evaluator change) the factories need to wire
their child. `SQLInCondition` gained matching `get/setOperator` accessors so
`in(...)` can populate the operator without reflection. See Decision Log.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilder.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLInCondition.java` (modified — accessors)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLIsDefinedCondition.java` (modified — accessors)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/parser/SQLIsNotDefinedCondition.java` (modified — accessors)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchWhereBuilderTest.java` (new)

**Cross-track impact:** Track 4 consumes the full predicate surface and adds
`endsWith` / `matchesRegex` once D-TEXT-OPS lands the AST nodes. The `notIn`
composition is observable as `NOT(IN ...)`; evaluation equivalence holds, the
index hot-path difference is deferred per the plan's index-awareness note.

### Step 3 — commit 7e15914862, 2026-06-25 [ctx=safe]
**What was done:** Created the stateful `MatchPatternBuilder` with
`addNode(alias, className, where, optional)`, `addEdge(fromAlias, toAlias,
direction, edgeLabel, edgeFilter, whileCondition, maxDepth)`, and `build()`
returning an immutable `PatternIR(Pattern, aliasClasses, aliasFilters)`.
`addNode` is idempotent on alias; `addEdge` builds the `SQLMatchExpression`
and delegates to `Pattern.addExpression(...)` so the well-tested
`getOrCreateNode` path performs implicit endpoint creation. `build()`
defensively copies the alias maps so a returned snapshot is not mutated by
later calls. `MatchPatternBuilderTest` (28 tests) covers single-node /
multi-hop / each direction / optional / implicit-creation / null-alias NPE /
unsupported-feature throw / snapshot semantics.

**What was discovered:**
- `whileCondition` / `maxDepth` have no public setters on `SQLMatchFilter`, so
  the builder rejects non-null arguments with `UnsupportedOperationException`,
  keeping the gap loud for the later track that needs variable-depth traversal.
- `addEdge` does not populate `aliasClasses` / `aliasFilters` for the target
  alias — that path-item filter is target-vertex metadata, distinct from the
  planner's alias-level inference maps. Documented in the `addEdge` Javadoc.

**What changed from the plan:** none.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilder.java` (new)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/match/builder/MatchPatternBuilderTest.java` (new)

**Cross-track impact:** the translator's variable-depth needs (a later track)
must add the missing parser setters or use reflection; flagged via the
`UnsupportedOperationException`.

### Step 4 — commit 9811b3cb9d, 2026-06-25 [ctx=safe]
**What was done:** Refactored `GqlMatchStatement.buildPlan` to delegate IR
assembly to package-private `GqlMatchPatternAssembler` (GQL-specific `$c<N>`
alias minting and `"V"` label default) backed by `MatchPatternBuilder`.
`buildWhereClause` keeps its package-private static signature (all 17 visitor
call sites stay byte-identical, resolving adversarial blocker A2) but now drives
`MatchWhereBuilder.eq` + `MatchLiteralBuilder.toLiteral`. Added
`GqlMatchPatternAssemblerTest` (8 tests) for assembler defaults and where-clause
propagation. Added `GqlMatchStatementPlanPrettyPrintTest` (4 tests) pinning
`plan.prettyPrint` via fragment assertions (same style as
`ExpandStepPrettyPrintTest`); the cartesian-product case slices each alias
prefetch block because sibling prefetch order is not fixed. Extended
`GqlMatchStatementTest` with four end-to-end builder-predicate cases
(`isDefined` / `isNull` / `isNotDefined` / `in`) that assert matched vertex
identity via a `scenario` tag, not just row count.

**What was discovered:**
- `MatchWhereBuilder.and(c)` returns the lone operand unwrapped (parser
  parity), which would change `buildWhereClause`'s historically always-wrapped
  `SQLAndBlock` shape; the refactored helper builds the block explicitly to
  preserve plan shape. Documented in the method Javadoc.

**What changed from the plan:** the private `toLiteral` delegate was removed
once `buildWhereClause` no longer needed it. GQL-specific alias/type defaults
moved out of `GqlMatchStatement` into `GqlMatchPatternAssembler` so
`MatchPatternBuilder` stays front-end-neutral for Track 2.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchPatternAssembler.java` (new)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchStatement.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchPatternAssemblerTest.java` (new)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchStatementPlanPrettyPrintTest.java` (new)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gql/parser/GqlMatchStatementTest.java` (modified — builder predicates)

**Cross-track impact:** none — all 91 `GqlMatchStatementTest` cases pass and
the four prettyPrint tests pass; the eq-path AST is byte-identical to the
pre-refactor output.

## Validation and Acceptance
- The shared builders produce IR identical to what `GqlMatchStatement` built inline before the refactor — verified by GQL's existing test suite (91 `GqlMatchStatementTest` cases + 4 prettyPrint fragment tests + 8 assembler tests).
- `MatchWhereBuilder.isDefined(field)` / `isNotDefined(field)` construct the existing presence AST nodes and render to `"<field> is defined"` / `"<field> is not defined"`.
- No grammar or evaluator logic changes. The only parser-package edits are public accessors on `SQLInCondition` (`get/setOperator`) and on `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` (`get/setExpression`) so the builder factories can wire their children.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references. Often empty. -->

## Interfaces and Dependencies
**In scope (new):** `internal/core/sql/executor/match/builder/MatchPatternBuilder.java` (carries the nested `PatternIR` record), `MatchWhereBuilder.java`, `MatchLiteralBuilder.java` + their unit tests.
**In scope (modified):** `GqlMatchStatement.java` (refactor `buildPlan` / `buildWhereClause` onto the builders); `GqlMatchPatternAssembler.java` (new — GQL-specific IR assembly); `SQLInCondition.java` (public `get/setOperator` accessors only — no grammar / evaluator change); `SQLIsDefinedCondition.java` / `SQLIsNotDefinedCondition.java` (public `get/setExpression` accessors only — no grammar / evaluator change).
**Out of scope:** the grammar (`YouTrackDBSql.jjt`), `MatchExecutionPlanner`, every execution step, and the text-suffix / regex AST (`SQLEndsWithCondition`, `SQLMatchesCondition` find-mode) that Track 4 introduces for `endsWith` / `matchesRegex`.
**Inter-track dependencies:** supplies the builder package + presence factories to Track 2 (strategy skeleton uses `MatchPatternBuilder`), Track 4 (`isDefined` / `isNotDefined` for `has(key)` / `hasNot(key)`), and Track 5 (`hasProperty`-based presence check shares the same entity-presence primitive).
**Signatures:** `SQLIsDefinedCondition` / `SQLIsNotDefinedCondition` constructors and `isDefinedFor(...)` are the existing primitives the factories wrap.

## Invariants & Constraints
<!-- Combined per-track invariants + constraints (conventions-execution.md §2.1 §14).
Added by workflow migration (#1145). Strategic invariants/constraints for this track remain
in implementation-plan.md § High-level plan (Architecture Notes) and this track's ## Decision
Log — the conservative migration retained the plan Architecture Notes rather than folding them here. -->

## Base commit
<!-- Phase B records the HEAD SHA here at session start; Phase C reads it to compute the
cumulative track diff (conventions-execution.md §2.1 §15). Added by workflow migration (#1145). -->
