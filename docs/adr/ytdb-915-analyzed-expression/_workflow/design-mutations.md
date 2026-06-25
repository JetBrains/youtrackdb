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

## Mutation 2 — 2026-06-25 — content-edit (design.md)

**Diff summary**: Apply the DD-review batch (research-log decisions D15–D18, Phase 0→1
adversarial gate iter3 = PASS) to the frozen design across five passages. (D15) The
Comparison section's collation justification is reworded: the AST `evaluate(Identifiable)`
overload skips collation as a deliberate AST behavioral inconsistency (intentional per
inline author comments), not a missing-context limitation — an `Identifiable` is a RID
loadable to an `EntityImpl` whose schema-class→property→collate chain is recoverable; the
analyzed layer unifies the inconsistency through the one collation-applying `Result`
overload. (D15) The Evaluator-interface section reframes the "rare `Identifiable`-only
caller" as ~12 production callers incl. `SQLWhereClause` and `SecurityEngine`: the
synthetic-`Result` adapter applying collation is a deliberate, observable behavior change
(case-insensitive matching on `ci`-collated WHERE/security predicates) that S1 (YTDB-916)
and S7 (YTDB-922) must validate; S0's I1 parity exercises only the `Result` overload and is
unaffected. (D16) The fast-path paragraph keeps "the IR *structure* need not encode the
fast path" but scopes the slow-path-only evaluator to S0 (no live consumer) and records the
S1+ obligation to reproduce the in-place comparison and AND/OR short-circuit fast paths.
(D17) The `NumericOps` gotcha extends the perf-neutrality basis with the two-hop
`operator.apply`→typed `operation.apply` re-dispatch seam and the T2 typed-`apply`
boundary, deferring runtime measurement to S1's LDBC JMH gate. (D18) The lowering section
states explicitly that the `SQLBaseIdentifier.levelZero` form (`functionCall` incl.
`any()`/`all()`, `self`/`@this`, `collection`) is out of the S0 subset and throws;
`FuncCall` comes only from method-call modifiers. Touched the Comparison collation paragraph
and fast-path paragraph + edge case, the NumericOps edge case, the lowering field-walk
paragraph, and the Evaluator-interface TL;DR / body / edge case.

**Mechanical checks** (target=design): PASS (0 blockers / 0 should-fix / 0 suggestions)
**Cold-read** (scope: whole-doc): PASS — 2 suggestions, not applied

**Findings**:
- suggestion (not applied): References-footer naming — the design uses `### Decisions &
  invariants` (uniform across all 11 sections) rather than the house-style `### References`
  shape; the footer's function (D-records + invariants resolvable at each section's foot) is
  fully served, and the variant is consistent on a frozen 858-line design, so not changed.
- suggestion (not applied): navigability — no top-of-file TOC; each section opens with a
  `**TL;DR.**` so a skimmer can still navigate. Below should-fix on a frozen design.

**Iterations**: 1 of 3 (PASS)

**Gate note**: D15 review-hold batch mutation. The four decisions cleared the Phase 0→1
adversarial gate (iter3 PASS; A11/A13 should-fix tightenings applied to the log, A12/A14
suggestions folded) before this mutation applied them, per the create-plan Step 4 batch
(gate → mutation → cold-read).
