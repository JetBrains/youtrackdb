# Design mutations log

## Mutation 1 — 2026-06-24 — content-edit (design.md)

**Diff summary**: Narrow S0's `Var` scope to single-segment and pin the comparison
collate-fetch mechanism, per D6-R (settled with the user in post-freeze design
discussion). S0 now lowers only single-segment `Var`s (`["name"]`); a multi-segment
path such as `p.name` throws `UnsupportedAnalyzedNodeException` and is deferred to S1+.
The Comparison section reframes the collate fetch as a resolution *through* the
`Result` (not a field on it) and adds an explicit paragraph that the IR re-implements
the single-property resolution `result.asEntity()` → `getImmutableSchemaClass(session)`
→ `getProperty(name)` → `property.getCollate()` (null for non-`Var` operands), with the
parity rationale: collation is non-syntactic so it cannot gate the carve-out, but path
length is syntactic so a multi-segment `Var` is a clean lowering throw that preserves I1
by construction. Touched the Core Concepts `Var` bullet, the Field-walk leaf rule + its
suffix-chain note, the Comparison TL;DR / step 2 / detail, and the D6-R reference in the
Sealed-IR, Field-walk, and Comparison `### Decisions & invariants` footers.

**Mechanical checks** (target=design): PASS (0 blockers / 0 should-fix / 0 suggestions)
**Cold-read** (scope: whole-doc): PASS — 2 suggestions, both applied

**Findings**:
- suggestion (applied): the AST-side collate chain elided `getImmutableSchemaClass(session)`
  while the IR re-implementation chain included it — rendered both at the same granularity
  so the re-implementation visibly mirrors the AST path.
- suggestion (applied): the IR re-implementation paragraph described only the per-operand
  resolution primitive — added a clause noting the left-operand-first-then-right fallback
  still wraps it.

**Iterations**: 1 of 3 (PASS)
