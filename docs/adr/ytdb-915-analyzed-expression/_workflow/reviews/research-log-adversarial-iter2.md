<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: A10, sev: suggestion, loc: SQLBinaryCondition.java (evaluate Result-overload, tryInPlaceComparison), anchor: "### A10 ", cert: AT3, basis: "evaluate(Result) has an in-place comparison fast path D11 does not mention; verified parity-equivalent (returns null/FALLBACK to the collation-applying slow path D11 replicates), so I1 holds — record so the evaluator/test author knows the slow path is the parity reference"}
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: A7, verdict: VERIFIED}
  - {id: A8, verdict: VERIFIED}
  - {id: A9, verdict: VERIFIED}
overall: PASS
flags: [CONTRACT_OK]
-->

# Research-log adversarial gate — iteration 2 (re-challenge)

Verdict: **PASS** (0 blocker, 0 should-fix, 1 suggestion)

Target: `docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md`. Emphasis lenses: Architecture / cross-component coordination, Performance hot path. IDE (mcp-steroid) reachable; `analyzed-expression` project open and matched. All symbol/source facts below are PSI-grounded (`JavaPsiFacade.findClass` + method/field text read inside `smartReadAction`) against develop's tip in this worktree.

BLUF: the revision resolves every iteration-1 finding. The two blockers are fixed by Decision Log entries that I re-verified against the real source, not just against the orchestrator's summary. D11's collation+session-threading prescription matches the actual `SQLBinaryCondition.evaluate(Result, ctx)` code (collation is applied **inside** `evaluate`, before `operator.execute`, so D11's "replicate getCollate+transform, then delegate to the parser operator instance" is both correct and necessary). D12's "the lowering fold is purely structural; value semantics stay in `NumericOps`" is confirmed by reading `calculateWithOpPriority`/`iterateOnPriorities` — the reduction order is driven entirely by `getPriority()` and stack discipline, independent of the values, so the lowerer can run the identical discipline substituting `new BinaryOp(...)` for `op.apply(...)`. D13 resolves the load-bearing track-shape open question (OQ6). D14 makes the field-walk exhaustive-or-throw and correctly flags the inherited `SimpleNode.value` field for Phase-A PSI rather than asserting completeness over it. The three suggestions are annotated and hold. One new observation (A10, suggestion): the `evaluate(Result)` fast path D11 does not mention is parity-equivalent — recorded for the evaluator/test author, does not affect the gate.

## Findings

### A10 [suggestion]
**Certificate**: AT3 (Assumption test — D11's slow-path replication is sufficient for I1 despite the in-place fast path)
**Target**: D11 (IR comparison evaluator replicates `SQLBinaryCondition.evaluate`)
**Challenge**: D11 prescribes that the IR comparison evaluator replicate `SQLBinaryCondition.evaluate(Result, ctx)`'s sequence — fetch `collate`, `collate.transform` both operands, then delegate to the parser operator instance's `execute(session, l, r)`. The actual `evaluate(Result, ctx)` on develop has an **in-place comparison fast path** ahead of that sequence (`tryInPlaceComparison` via `EntityImpl.isPropertyEqualTo` / `comparePropertyTo`) that D11 does not name. If that fast path could return a boolean the slow path would not, an IR evaluator replicating only the slow path would still drift from the AST on the fast-path inputs. Examined against the code, the fast path is parity-safe: `tryInPlaceComparison` returns `Boolean` only when `EntityImpl` reports a definitive in-place answer and returns `null` (FALLBACK) for any non-default-collation case, falling through to the collation-applying slow path; for default-collation in-subset comparisons the fast and slow paths compute the same result. So D11's slow-path replication reproduces the AST's observable result for every in-subset comparison — the fast path is an optimization, not a separate semantics. No change to D11 is required; this is a note so the evaluator author and the I1 test author treat the slow path (collate + parser-operator delegation) as the parity reference and do not feel obliged to port the `EntityImpl` in-place machinery.
**Evidence**: `SQLBinaryCondition.evaluate(Result, ctx)` applies `getCollate` + `transform` then `operator.execute` only on the fall-through path; the fast path's `tryInPlaceComparison` returns `null` for any case requiring collation (comment: "Collation is checked inside EntityImpl … both serialized and deserialized paths return FALLBACK for non-default collation"). `SQLEqualsOperator.execute` → `QueryOperatorEquals.equals(session, …)`; `SQLNeOperator.execute` → `QueryOperatorEquals.equals(null, …)`. All PSI-read this iteration.
**Proposed fix**: Optionally add a one-line note to D11 (or the T4 lowering/evaluator scope) that the AST's in-place fast path is parity-equivalent to the slow path D11 replicates, so the IR evaluator deliberately mirrors only the slow path. No decision change.

## Evidence base

#### AT3 Assumption test: D11's slow-path replication is sufficient for I1 despite the AST in-place fast path
- **Claim**: An IR comparison evaluator that replicates only `SQLBinaryCondition.evaluate`'s collation+parser-operator slow path matches the AST on every in-subset comparison (I1), even though the AST has an in-place fast path D11 does not mention.
- **Stress scenario**: A `property <op> constant` comparison that the AST resolves via `tryInPlaceComparison` (the fast path) rather than the slow path — including a collated property, and an EQ vs NE pair.
- **Code evidence (PSI, this iteration)**: `tryInPlaceComparison` dispatches to `EntityImpl.isPropertyEqualTo` (EQ/NE) or `comparePropertyTo` (ordering) and returns `null` whenever `EntityImpl` reports `InPlaceResult.FALLBACK`; the `evaluate(Result)` comment states the in-place path returns FALLBACK for non-default collation, so collated comparisons fall through to the slow path. The slow path computes `collate = left.getCollate(...)` (fallback `right.getCollate(...)`), transforms both operands when non-null, then calls `operator.execute(session, …)` on the parser operator instance. The fast path is therefore value-equivalent to the slow path for in-subset inputs (it never returns a boolean the slow path would contradict).
- **Verdict**: HOLDS (D11's slow-path replication is sufficient; the fast path is a parity-equivalent optimization the IR evaluator need not port).

## Prior-finding re-verification

Each iteration-1 finding (A1–A9) re-checked against the revised log and, where reference accuracy matters, against the live source via PSI.

#### A1 (blocker) → D10 — VERIFIED
The revision adds D10: parenthesized arithmetic `(a + b) * c` is in-subset; the lowerer recurses on `expression` and throws only on `statement`/CASE. PSI-confirmed against `SQLParenthesisExpression.execute(Result, ctx)` and `execute(Identifiable, ctx)`: both return `expression.execute(...)` when `expression != null` (transparent grouping) and handle `statement != null` separately (the subquery path). The covered-subset wording is corrected in the Re-validation block, and the I1 test obligation adds `(a + b) * c` and `a * (b + c)`. The V1 violation scenario is closed by construction — the lowerer no longer throws on the grouping form. Resolved.

#### A2 (blocker) → D11 — VERIFIED
D11 replaces the "comparison parity is structural" claim with a sequence that replicates `SQLBinaryCondition.evaluate`: fetch `collate` (left then right), transform both operands when non-null, then delegate to the parser operator instance's `execute`. PSI-confirmed the critical fact the iter-1 review hinged on: collation is applied **inside `evaluate(Result, ctx)`** (after computing `leftVal`/`rightVal`, before `operator.execute`), **not** inside `operator.execute`. So a bare static `QueryOperatorEquals.equals` would indeed drop collation, and D11's prescription to replicate the `getCollate`+`transform` step is correct and necessary, not redundant. The collated-column I1 test obligation is added. The V2 violation scenario is closed. Resolved. (Wording nuance: D11's headline "delegating to the parser operator instance reproduces collation + session threading" is loose — the operator instance reproduces only session threading; collation is reproduced by the explicitly-listed `getCollate`+`transform` step that precedes the delegation. D11's body lists that step, so the prescription is complete; the headline overstates what the delegation alone does. Not a should-fix — the body is correct and the I1 collated-column test guards it.)

#### A3 (should-fix) → D11 — VERIFIED
The NE null-session nuance is folded into D11. PSI-confirmed: `SQLEqualsOperator.execute` passes the real `session` to `QueryOperatorEquals.equals`; `SQLNeOperator.execute` passes `null`. Delegating to the parser operator instance (rather than the bare static) reproduces this by construction, and the type-coercing NE I1 test obligation is added. Resolved.

#### A4 (should-fix) → D13 — VERIFIED
OQ6 is resolved into D13: four dependency-ordered tracks (T1 substrate+framework, T2 `NumericOps` whole-enum, T3 lowering [absorbs D10+D12], T4 evaluator+round-trip), with the heavier-lowering-track and whole-enum-extraction scope shifts written into the track scope. The load-bearing track-shape decision is now made in the Decision Log, not left as an open question. Resolved.

#### A5 (should-fix) → D12 — VERIFIED
D12 chooses option (a)-with-rationale: the lowerer reproduces a **structural** precedence-climbing fold (nesting only) and leaves the AST's hot fold untouched; all value semantics come from shared `NumericOps`. PSI-confirmed against `calculateWithOpPriority`/`iterateOnPriorities`: the reduction order is driven solely by `getPriority()` comparisons and stack discipline, independent of the operand values — so the structural part is genuinely separable from `apply` (the value engine). The `NULL_VALUE` sentinel is a value-domain device (it only feeds `apply`), so the lowerer does not need to reproduce it, consistent with D12's "value semantics deferred to NumericOps." The perf-lens rationale (a shared generic fold would inject a bimorphic combiner call into the hot AST eval loop, against the codebase's monomorphic grain) holds — the AST fold inlines `apply(left, right)` directly. The precedence-mixing parity matrix is added as an I1 test obligation. Rationale strengthened; resolved.

#### A6 (should-fix) → D14 — VERIFIED
D14 makes the field-walk exhaustive-or-throw, so I2 holds regardless of inventory gaps, and flags the inherited `SimpleNode.value` field for Phase-A PSI rather than asserting completeness. PSI-confirmed: `SQLExpression.allFields` carries `value` (declared in `SimpleNode`), and `SQLExpression.execute` ends with the "old executor (manually replaced params)" fallback chain reading `value` as `SQLNumber`/`SQLRid`/`SQLMathExpression`/`SQLArrayConcatExpression`/`SQLJson`/`String`/`Number` then a bare `return value`. D14's exhaustive-or-throw default means an unrecognized `value` makes lowering throw (never mis-read), and the Phase-A reachability check is correctly deferred. Resolved.

#### A7 (suggestion) → annotated — VERIFIED
The gate-iteration-1-resolutions block notes D5-R adds no hot-path indirection (static, JIT-inlinable; `MathExpressionTest` is the acceptance gate). Rationale held, no decision change. Resolved.

#### A8 (suggestion) → OQ2 annotated — VERIFIED
OQ2 is annotated as out-of-S0-scope (S0 throws at the `inputParam` leaf; only the future-slice shape is open). No artifact derived now depends on it. Resolved.

#### A9 (suggestion) → annotated — VERIFIED
The D1/D2 monomorphic-dispatch rationale is annotated as a forward bet for the S1+ optimizer pipeline rather than a measured S0 win (no live IR consumer in S0). Rationale held, no decision change. Resolved.
