# Handoff: Phase 0 — S1 FilterStep/ExpandStep → AnalyzedExpr research

**Paused:** 2026-07-01
**Phase:** 0 (research, `/create-plan`)
**Context level at pause:** safe (user-requested pause, not context-triggered)
**Branch:** ir-filter-step-expand-step
**HEAD:** 87cbfc0b6d "[YTDB-915] S0 — Analyzed-expression substrate (#1174)"
**Unpushed:** <no upstream — see workflow.md §What to do before ending a session>

## What I was investigating
The S1 (YTDB-916) design — migrating `FilterStep` + `ExpandStep` predicates to
consume `AnalyzedExpr` instead of `SQLWhereClause`, the first live consumer of
the S0 substrate. Research to settle the design before planning. Four decisions
are locked (D1–D4 in `research-log.md`); two research threads remain.

## Already ruled out
- Clean cutover (AST fully gone from FilterStep) — impossible in S1; the S0
  subset cannot cover real WHERE clauses. Chose the planner-split fallback (D3).
- Fallback options A (dual-carry) and C (escape-hatch IR node) — rejected in D3.
- Option D "widen lowering" as a fallback substitute — LDBC shows the un-lowered
  cases are operand-blocked (bind param / multi-seg / `@rid` / `$var`), not
  condition-type-blocked, so widening barely moves the fallback rate.
- Dedicated n-ary boolean node for AND/OR — rejected for extending
  `BinaryOperator` (D4), consistent with `NOT`-as-`UnaryOperator`.

## Most promising lead
The design is largely settled. `research-log.md` is the authoritative ledger:
D1 (fold bind params in), D2 (bind-param raw-value parity fact), D3 (planner-split
single-class FilterStep fallback), D4 (AND/OR via `BinaryOperator` + lazy
short-circuit). Umbrella gap closed: S16 (YTDB-1183) + S17 (YTDB-1184) filed and
dependency-wired (S12 → S16; S15 → S16, S17). Two YTDB-916 comments posted (scope
amendment + S16/S17 pointer) — do NOT re-post.

## Open questions
1. **Collation-convergence confirmation** (do first, cheap, PSI now connected).
   Verify every S1-migrated site (`FilterStep.filterMap`, `ExpandStep` push-down,
   the planner sites) uses the `Result`-path evaluation, which already applies
   collation — so S1's collation work is a regression test, not a behavior
   change. Load-bearing "no S1-scoped site uses `evaluate(Identifiable)`" claim;
   route through PSI find-usages, not grep.
2. **Serialization / plan-cache round-trip.** Analyzed `FilterStep.serialize`:
   emit the IR, or emit the source WHERE and re-lower on deserialize? How the
   split step tags which form it carries. `Param` must serialize by reference
   (index / name), never by resolved value (plan-cache reuse across executions).

## Raw notes / partial findings
Key grounded facts (all in `research-log.md`, do not re-derive):
- LDBC SF1: 64 WHEREs; top-level (FilterStep path) 9, of which 1 lowerable today,
  ~5/9 with bind params folded in; 55 MATCH-internal (Identifiable path, not
  FilterStep). Measured with the real parser via `steroid_execute_code` (core
  must be compiled — `./mvnw -pl core compile` — for the classloader to see the
  parser; the classifier built a URLClassLoader over module output).
- FilterStep filters the index-*residual* (`IndexSearchDescriptor.getRemainingCondition()`),
  not the source WHERE, so the true fallback rate needs a runtime probe.
- `createWhereFrom(SQLBooleanExpression)` is the factory-centralization seam for
  D3: ~10 `new FilterStep`/`createWhereFrom` sites collapse to one `filterStepFor(...)`.
- `AnalyzedAstAccess.inputParam` read-seam already exists (S0 built it);
  `SQLInputParameter.getValue` returns the raw value on the comparison path
  (`toParsedTree` is `bindFromInputParams`, sub-expression positions only).
- Evaluator `visitBinaryOp` is an exhaustive `switch (op)`; the AND/OR arm must be
  **lazy** (dispatch left, cast Boolean, conditionally dispatch right).
- `SQLAndBlock`/`SQLOrBlock` iterate `subBlocks` in order, short-circuit, plain
  boolean; null-guard → true for both (defensive; parser always non-null).

## Resume notes
- **Do NOT re-explore:** S0 substrate (read `docs/adr/ytdb-915-analyzed-expression/design-final.md`),
  the 9 FilterStep sites, `SQLAndBlock`/`SQLOrBlock` semantics, `SQLInputParameter`
  resolution, the umbrella coverage gap (S16/S17 done), the LDBC lowering-coverage
  measurement (done). Do NOT re-derive D1–D4.
- **Next action on resume:** still in research — take open question 1 (collation
  PSI check), then 2 (serialization). When both settle, research is complete. On
  the user's "create the plan" go-ahead, run Step 4: design-gate classification
  (likely `yes` — architecture-central substrate change — but propose and confirm)
  → adversarial gate on the log → plan. Resume via `/create-plan` (Phase 0 handoff
  drives it before the aim prompt).
