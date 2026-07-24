> Track 1 Phase A adversarial review — shared `match/builder/` package, GQL adoption,
> and `IS DEFINED` / `IS NOT DEFINED` factories. Full text-predicate translation
> (D-TEXT-OPS) and the plan cache belong to later tracks and are out of scope here.

# Track 1 — Adversarial Review

## Outcome: All findings resolved

**One blocker (A2) resolved by keeping `buildWhereClause` as static delegate.**
Other findings deferred or accepted into step decomposition.

## Findings

### A1 [should-fix] — DEFERRED with rationale strengthened
**Challenge**: D6 rationale weak — GQL exercises only ~10 lines of single-node
construction; locking builder API on this narrow shape is anchor-bias risk.
**Resolution**: User explicitly approved D6 (shared from day 1) during Phase 0.
The actual API surface required by the GQL refactor (`addNode`,
`MatchLiteralBuilder.toLiteral`) is small and stable. Edge-related methods
(`addEdge`) are designed for the translator's needs (Track 3); GQL doesn't
exercise them today, so anchor risk is theoretical. The unified track keeps
the two consumers honest about API design from the start.

### A2 [blocker] — RESOLVED via static delegate strategy
**Challenge**: 17 GQL test callers of `buildWhereClause` would break if symbol
moved to `MatchWhereBuilder`.
**Resolution**: Step 4 keeps `GqlMatchStatement.buildWhereClause` as a
package-private static delegate (T8 + this finding). The body changes to
delegate to `MatchWhereBuilder`; the signature, visibility, and call sites
all stay byte-identical. All 17 tests continue to compile and pass unchanged.

### A3 [should-fix] — RESOLVED (same as T5)
**Challenge**: `toLiteral(null)` NPE bug preserved by "verbatim extraction".
**Resolution**: Step 1 documents the NPE behavior explicitly in Javadoc and
adds a unit test asserting it. Behavior preserved; documentation improved.

### A4 [suggestion] — ACCEPTED — package location moved
**Challenge**: `internal/core/sql/match/builder/` creates a third top-level
"match"-related package, alongside `parser/` (AST) and `executor/match/`
(planner).
**Resolution**: Track package location moved to
**`internal/core/sql/executor/match/builder/`** — same package as
`MatchExecutionPlanner`, `PatternNode`, `PatternEdge`. Builders and their
consumer live next to each other. Plan file's Component Map and design.md
references updated.

### A5 [suggestion] — DEFERRED with explicit step note
**Challenge**: `MatchWhereBuilder` fluent API duplicates what
`SQLAndBlock.getSubBlocks().add(...)` already provides.
**Resolution**: Step 2 implements only the fluent methods that have a clear
use-case justification at the time of writing. Composition helpers like
`and(...)` / `or(...)` matter when callers compose dozens of conditions
(translator's predicate adapter). Direct AST construction stays available
for callers that prefer it. After Track 4 (translator predicate adapter)
implementation, we re-evaluate which fluent methods earn their keep — if
any are unused beyond their own unit tests, they go.

### A6 [should-fix] — APPLIED to plan invariant
**Challenge**: "Byte-identical execution plans" invariant too strong;
construction-order changes through builder may produce semantically equivalent
but not byte-identical plans.
**Resolution**: Plan file invariant reworded from "byte-identical execution
plans" to "structurally equivalent plan tree (same step types in same order,
same alias bindings, equivalent prettyPrint output)". The regression test
specification (T9) operates on `prettyPrint` fragment assertions (`contains`
on alias-scoped blocks), which captures structural equivalence without
full-string golden equality or field-by-field byte comparison.

### A7 [suggestion] — REJECTED with rationale
**Challenge**: Split Track 1 into 1a (greenfield builders) + 1b (GQL
refactor) to isolate test risk.
**Resolution**: With A2 resolved (delegate strategy), the GQL refactor
no longer carries test-call-site risk — it's a body-only change. The
remaining "risk" is plan-equivalence regression, mitigated by the prettyPrint
fragment regression tests in Step 4. Splitting adds workflow overhead without
proportional risk reduction. Track stays unified.
