<!-- REVIEW-FILE v1 (conventions-execution.md §2.5) -->
```yaml
review:
  kind: adversarial
  role: reviewer-adversarial
  phase: 1
  scope: research-log
  target: docs/adr/ir-filter-step-expand-step/_workflow/research-log.md
  iteration: 1
  matched_categories: ["Architecture / cross-component coordination", "Performance hot path"]
  verdict: NEEDS_REVISION
  findings: 7
  blockers: 0
  should_fix: 5
  suggestions: 2
  tooling: mcp-steroid PSI (IDE reachable, project ir-filter-step-expand-step-tte37ok1 matched)
index:
  - id: A1
    sev: should-fix
    anchor: "### A1 "
    target: "D1 — fold bind-parameter lowering into S1"
    cert: "Challenge D1"
    basis: "uncited LDBC fraction figures (37/64, 1/9→5/9) drive the fold-in payoff argument"
  - id: A2
    sev: suggestion
    anchor: "### A2 "
    target: "D2 — AST resolves comparison-RHS bind param to raw bound value"
    cert: "Assumption test D2"
    basis: "PSI-confirmed exact at SQLNamedParameter/SQLPositionalParameter.getValue + SQLBaseExpression.execute:154/186"
  - id: A3
    sev: should-fix
    anchor: "### A3 "
    target: "D3 — planner-level split, single-class FilterStep fallback"
    cert: "Challenge D3"
    basis: "two-evaluation-path result-equivalence obligation under-specified; no differential parity harness named"
  - id: A4
    sev: suggestion
    anchor: "### A4 "
    target: "D3 — alternative C (escape-hatch IR node) rejection"
    cert: "Challenge D3-alt-C"
    basis: "C is the only option that removes AST from FilterStep; rejection holds but rationale leans on I3 cleanliness alone"
  - id: A5
    sev: should-fix
    anchor: "### A5 "
    target: "D4 — AND/OR via BinaryOperator, lazy short-circuit, edge parity"
    cert: "Violation scenario D4-vacuous"
    basis: "empty-vs-null subBlocks distinction: SQLOrBlock null-guard returns true, empty-loop returns false; D4 states only Const(false)"
  - id: A6
    sev: should-fix
    anchor: "### A6 "
    target: "D5 — minimal source-WHERE bridge, source-predicate retention"
    cert: "Challenge D5"
    basis: "retained source predicate widens the display/serialize seam D3 tried to keep off FilterStep; retirement depends on unfiled-in-D5 YTDB-1185"
  - id: A7
    sev: should-fix
    anchor: "### A7 "
    target: "Open Questions — fallback strategy + AND/OR representation still listed open"
    cert: "Open-question challenge OQ"
    basis: "both are resolved into D3/D4 but the section still lists them as open (unmarked), contradicting the RESOLVED siblings"
evidence_base:
  - "Challenge D1 (bind-param fold): parity path verified; payoff figures uncited"
  - "Assumption test D2 (raw-value parity): HOLDS — PSI-exact"
  - "Challenge D3 (planner split): SURVIVES — createWhereFrom seam confirmed (7+2 sites); dual-path parity gap"
  - "Challenge D3-alt-C (escape-hatch): SURVIVES — C re-couples AST-in-IR"
  - "Violation scenario D4-vacuous: THEORETICAL — null-vs-empty semantics nuance"
  - "Challenge D5 (serialize bridge): SURVIVES — 0 serialize call sites (PSI); retention-scope thin"
  - "Open-question challenge OQ: two load-bearing questions resolved in log but section stale"
```

## Findings

### A1 [should-fix]
**Certificate**: Challenge D1 — fold bind-parameter lowering into S1
**Target**: Decision D1
**Challenge**: D1's whole payoff case — the reason to expand YTDB-916's written scope and fold bind-param lowering into S1 rather than defer it to an S1.5 — rests on four numeric claims that appear nowhere else and cannot be reconstructed from the code: "LDBC: 37/64 WHEREs touch a bind param", "4 of 9 top-level FilterStep WHEREs are bind-param-blocked alone", and "moves the lowerable top-level LDBC fraction from ~1/9 to ~5/9". The parity *mechanism* is sound and PSI-confirmed (see A2), so the challenge is not that D1 is wrong — it is that a scope-expanding decision leans entirely on an unshown measurement. If the fraction is actually ~3/9 not ~5/9, the "difference between near-dead and actually-runs" framing weakens and the S1.5-deferral alternative (rejected only on payoff-per-effort grounds) becomes competitive.
**Evidence**: `SQLNamedParameter.getValue` (name-first `containsKey` then `params.get(paramNumber)`) and `SQLPositionalParameter.getValue` (`params.get(paramNumber)`) confirm resolution is raw and cheap; `AnalyzedAstAccess.inputParam` read-seam exists (lowerer line 217 reads it, currently to throw). The mechanism is confirmed; the LDBC counts are not derivable from these files.
**Proposed fix**: Cite the measurement source for the LDBC fractions (query-set inventory, the analysis that produced 37/64 and 4/9) in D1 or a Surprises entry, or restate the payoff qualitatively ("bind params dominate parameterized production WHEREs") so the decision does not rest on an unverifiable exact figure. The decision itself survives.

### A2 [suggestion]
**Certificate**: Assumption test D2 — AST resolves a comparison-RHS bind param to the raw bound value
**Target**: Decision/parity-fact D2
**Challenge**: D2 asserts the `Param` evaluator arm can resolve "by the same raw lookup; no coercion to replicate", claiming `toParsedTree` coercion is confined to `bindFromInputParams` and never taken by a scalar comparison. Adversarially: if a comparison RHS bind param ever routed through `bindFromInputParams` (the coercion path), the D1 `Param` arm would silently diverge from the AST. The assumption HOLDS — but it is load-bearing enough to name the exact guard.
**Evidence**: PSI + code confirm the assumption exactly. `SQLBaseExpression.execute(Result, ctx)` line 186 (and the `Identifiable` overload line 154) calls `inputParam.getValue(ctx.getInputParameters())`, NOT `bindFromInputParams`. `SQLNamedParameter.getValue` (lines 51-62) and `SQLPositionalParameter.getValue` (lines 49-55) return `params.get(...)` raw with no `toParsedTree` call. `bindFromInputParams` (the only `toParsedTree` caller) is a distinct method reached from `toString`/sub-expression-position paths, not the scalar `execute` path a comparison RHS takes. Verdict: HOLDS.
**Proposed fix**: None required for correctness. Optionally pin the divergence surface with a one-line note in D2 that the parity test must assert the `Param` arm calls the `getValue` (raw) path, not `bindFromInputParams`, so a future refactor that reroutes RHS resolution through coercion is caught.

### A3 [should-fix]
**Certificate**: Challenge D3 — fallback = planner-level split (Option B), single-class FilterStep
**Target**: Decision D3
**Challenge**: D3's own Risks/Caveats states "two evaluation paths (analyzed vs AST) must stay result-equivalent on the predicates both can run" and then leaves that obligation unmethodized. This is the load-bearing risk of the whole slice: a single `filterStepFor` factory routes the *same* predicate to either the analyzed evaluator or the AST `matchesFilters(Result)` depending on lowering success, so any evaluator/AST divergence produces different query results for logically identical WHEREs — and the split makes the divergence *silent* (no throw, just a different filter outcome). The analyzed evaluator reconstructs operator instances and re-derives collate independently (`AnalyzedExprEvaluator.evaluateComparison` lines 225-241, `collateFor` lines 253-277), so it is a genuine re-implementation of the AST path, not a shared call. Divergence is constructible, not theoretical.
**Evidence**: `AnalyzedExprEvaluator.evaluateComparison` fetches collate left-then-right and applies `transform` (lines 229-236) mirroring `SQLBinaryCondition.evaluate(Result)` lines 101-108 — but the two are separate code that can drift (e.g. the analyzed `collateFor` only resolves a `Var` operand and returns null otherwise, whereas the AST's `left.getCollate` / `right.getCollate` may resolve collate for shapes the IR represents differently). The `createWhereFrom` seam and the two direct sites (`info.whereClause` ~1992, `outerWhere` ~3470) that `filterStepFor` must cover are confirmed present in `SelectExecutionPlanner`.
**Proposed fix**: D3 (or the S1 plan it carries into) should name the differential parity mechanism that guards the two-path equivalence — e.g. a test harness that runs a corpus of lowerable WHEREs through both the analyzed `FilterStep` and the AST `FilterStep` and asserts identical row sets, not just the per-decision unit tests. The decision survives; the risk mitigation must be concrete before the plan derives.

### A4 [suggestion]
**Certificate**: Challenge D3-alt-C — escape-hatch IR node wrapping an un-lowered SQLBooleanExpression
**Target**: Decision D3 (rejected alternative C)
**Challenge**: Alternative C is the *only* rejected option that literally achieves YTDB-916's written end state ("constructor takes `AnalyzedExpr`, old constructor removed, `matchesFilters` shim removed") — FilterStep would hold a pure `AnalyzedExpr` where an escape-hatch variant carries the un-lowered `SQLBooleanExpression`. D3 rejects C for "re-introducing AST-inside-IR coupling" and denting "S0 sealed-variant / I3 cleanliness". Adversarially, C keeps the coupling *inside one sealed variant* rather than spread across FilterStep's field set (D3's chosen B puts `SQLWhereClause` directly on FilterStep as an alternate field), so it is arguably *less* invasive to the executor layer, and the umbrella's own S18/YTDB-1187 end-state audit ("only parser+lowerer touch `SQL*` nodes") would flag C's wrapper as the single well-marked exception rather than a FilterStep dual-field.
**Evidence**: `FilterStep` currently holds `SQLWhereClause whereClause` as its only predicate field (line 41); D3's B adds an `AnalyzedExpr`-or-`SQLWhereClause` either/or, so B also carries AST state on the step. `AnalyzedExpr` is a sealed interface with 5 permits (lines 26-31); C's escape-hatch would be a 6th variant — an intended compile-time break per the VARIANT-ADDITION note (lines 20-25). The rejection HOLDS: C's wrapper would propagate an AST-typed node through every `AnalyzedExprTransform`/`AnalyzedExprVisitor` implementer (which must then all handle "un-lowered opaque AST"), a wider blast radius than B's step-local field. Verdict: SURVIVES.
**Proposed fix**: None required. Optionally strengthen D3's C-rejection to name the real cost — every visitor/transform implementer would need an escape-hatch arm — rather than the softer "dents I3 cleanliness".

### A5 [should-fix]
**Certificate**: Violation scenario D4-vacuous — empty AND/OR block edge parity
**Target**: Decision D4 (Edge parity clause)
**Challenge**: D4's Edge-parity clause states "empty `AndBlock` → `Const(true)`, empty `OrBlock` → `Const(false)`, matching the AST's vacuous semantics." The AST has *two* vacuous cases with *different* results, and D4 names only one. `SQLOrBlock.evaluate(Result)` returns `true` when `subBlocks == null` (line 53-55) but `false` when `subBlocks` is a non-null empty list (falls through the loop, line 58-59). `SQLAndBlock` returns `true` for both. So an `OrBlock` with a null `subBlocks` field evaluates to `true`, contradicting D4's blanket `Const(false)`. In practice the parser initializes `subBlocks = new ArrayList<>()` (line 27), so the null case is unreachable from parsed input and D4's `Const(false)` is correct for the *reachable* empty case — but D4 does not say this, so an implementer mirroring "the AST's vacuous semantics" literally could pick either branch.
**Evidence**: `SQLOrBlock.java`: line 27 `subBlocks = new ArrayList<>()`; `evaluate(Result)` lines 52-63: `if (subBlocks == null) return true;` then loop returns `false` on fall-through. `SQLAndBlock.java` `evaluate(Result)`: null-guard returns `true`, loop fall-through returns `true`. Feasibility: THEORETICAL for parser-built input (null field unreachable), but the ambiguity is real in the decision text. Note also: AND/OR lowering is not yet wired — `AnalyzedExprLowerer.lowerBoolean` (line 306-330) handles only `SQLBinaryCondition` and `SQLNotBlock` and throws on `SQLAndBlock`/`SQLOrBlock`, and `BinaryOperator` has no AND/OR constant yet (`PLUS…GE` only) — so D4 is genuine new S1 work and the implementer will write this fold from scratch.
**Proposed fix**: D4 should state the vacuous rule precisely: the reachable empty (non-null) case is `AndBlock→true`, `OrBlock→false`; the null-`subBlocks` case (AST returns `true` for both) is unreachable from parsed input and need not be modeled. Optionally note that a single-element block unwraps to its element so the fold rarely produces a vacuous node at all. The decision survives.

### A6 [should-fix]
**Certificate**: Challenge D5 — minimal source-WHERE bridge for serialization
**Target**: Decision D5
**Challenge**: D5's bridge requires the analyzed `FilterStep`/`ExpandStep` to "retain the source predicate (as text or structured form) for serialize + `prettyPrint` rendering only", and D5 insists this is "NOT the D3-rejected dual-carry evaluation coupling". The line is thinner than D5 admits: the retained source WHERE *is* an AST `SQLWhereClause` living on the step (exactly what D3's B already carries in the fallback case, and what D3-alt-A was rejected for). For the *analyzed* step (lowering succeeded), the step now carries BOTH the `AnalyzedExpr` (for eval) AND the source `SQLWhereClause` (for serialize/prettyPrint) — which is structurally dual-carry, differing from the rejected D3-A only in that the AST copy is never *evaluated*. That distinction is real but subtle, and it means the "single-class FilterStep holds *either* AnalyzedExpr *or* SQLWhereClause (never both)" invariant stated in D3 is contradicted by D5's retention for the analyzed case.
**Evidence**: PSI confirms the premise that makes the bridge defensible — `SelectExecutionPlan.serialize` has **0 project call sites** (verified) and `ExecutionStepInternal.serialize`/`deserialize` are test-and-internal-walk only, so serialize is dead production code and the retained-AST-for-serialize cost is paid only in tests. `FilterStep.prettyPrint` (lines 78-90) reads `whereClause.toString()` today, so EXPLAIN rendering does need *some* source form. But D3's stated invariant "`FilterStep` holds *either* an `AnalyzedExpr` *or* a `SQLWhereClause` (never both)" (research-log D3 body) is inconsistent with D5's "analyzed step retains the source predicate".
**Proposed fix**: Reconcile D3 and D5 explicitly: either D3's "never both" invariant is amended to "never both *for evaluation*; the analyzed step additionally retains the source form for serialize/EXPLAIN, evaluated off neither", or D5 chooses text-only retention (a `String` prettyPrint form, not a live `SQLWhereClause`) so the analyzed step carries no AST node at all. Also: D5 says the companion removal is "filed as YTDB-1185" — confirm the filing so the "bridge deleted with the cleanup slice" retirement path is real and not aspirational (same unbacked-retirement failure mode the Surprises log flags for the D3 fallback). Decision survives.

### A7 [should-fix]
**Certificate**: Open-question challenge OQ — two load-bearing questions still listed open
**Target**: Open Questions section
**Challenge**: The `## Open Questions` section lists four entries; two are marked **RESOLVED** (boolean-family ownership, serialization/plan-cache) and two are **not** — "Fallback strategy for un-lowerable WHERE clauses (central design question)" and "AND/OR IR representation". The gate rule requires every open question bearing on a load-bearing decision to be resolved into the Decision Log or explicitly waived before the gate clears. These two are the *most* load-bearing questions in the log — and they ARE in fact resolved: the fallback question is decided by D3 (planner-level split, Option B) and the AND/OR question by D4 (extend BinaryOperator, lazy). So this is not an unresolved-decision gap; it is a stale-bookkeeping contradiction: the log simultaneously presents the fallback strategy as an open "Needs user steer" question AND as decided D3, which a reader deriving the plan must not trip over.
**Evidence**: `## Open Questions` entry "Fallback strategy…(central design question)…Needs user steer" duplicates the choice D3 records as made (Option B, single-class). The AND/OR entry "Extend `BinaryOperator`…or add a dedicated…boolean IR node?" duplicates D4's recorded choice. The other two questions carry an explicit "**RESOLVED**" prefix and point at their Decision Log home; these two do not.
**Proposed fix**: Mark both entries **RESOLVED** with a pointer to their Decision Log home (fallback → D3; AND/OR → D4), mirroring the two already-resolved entries, so the log tells one consistent story before any Phase-1 artifact derives. No re-decision needed — the resolutions already reached the Decision Log.

## Evidence base

#### Challenge: Decision D1 — fold bind-parameter lowering into S1
- **Chosen approach**: Add a `Param` IR variant + one lowering arm + one evaluator arm so S1 lowers `?`/`:name` bind params, expanding YTDB-916's written scope (which inherited S0's throw).
- **Best rejected alternative**: (b) do bind params as a separate S1.5 follow-up.
- **Counterargument trace**:
  1. The fold-in is justified by "moves the lowerable top-level LDBC fraction from ~1/9 to ~5/9", "37/64 WHEREs touch a bind param", "4 of 9 top-level FilterStep WHEREs are bind-param-blocked alone".
  2. None of these figures is derivable from the codebase (they are LDBC query-set measurements), and none is cited to a source in the log.
  3. If the true post-fold fraction is lower, the S1.5-deferral alternative — rejected only on payoff-per-effort — becomes competitive, and the scope expansion is weaker-justified.
- **Codebase evidence**: `AnalyzedExprLowerer.lowerBase` line 217-221 currently throws on `inputParam`; `SQLNamedParameter.getValue`/`SQLPositionalParameter.getValue` confirm the cheap raw resolution the fold relies on. The mechanism is real; the payoff magnitude is unshown.
- **Survival test**: WEAK — decision holds on mechanism and payoff *direction*, but the exact figures driving the scope expansion need a cited source.

#### Assumption test: D2 — AST resolves a comparison-RHS bind param to the raw bound value
- **Claim**: The `Param` evaluator arm resolves by the same raw lookup the AST uses; no `toParsedTree` coercion to replicate.
- **Stress scenario**: A comparison RHS bind param that, if routed through `bindFromInputParams`, would be coerced to a parse-tree node — diverging the `Param` arm from the AST.
- **Code evidence**: `SQLBaseExpression.execute(Result)` line 186 calls `inputParam.getValue(...)` (raw), not `bindFromInputParams`; `SQLNamedParameter.getValue` lines 51-62 / `SQLPositionalParameter.getValue` lines 49-55 return `params.get(...)` with no coercion; `bindFromInputParams` (the sole `toParsedTree` caller) is reached only from `toString`/sub-expression paths.
- **Verdict**: HOLDS.

#### Challenge: Decision D3 — planner-level split, single-class FilterStep
- **Chosen approach**: `filterStepFor(...)` factory attempts lowering, returns analyzed `FilterStep` on success or legacy `SQLWhereClause` step on `UnsupportedAnalyzedNodeException`; FilterStep holds either form.
- **Best rejected alternative**: (C) escape-hatch IR node (handled separately below); the live challenge is the two-path result-equivalence obligation.
- **Counterargument trace**:
  1. The same predicate can run through the analyzed evaluator OR the AST `matchesFilters(Result)` depending on lowering success.
  2. The analyzed evaluator is a re-implementation (reconstructs operators, re-derives collate) — `evaluateComparison` lines 225-241, `collateFor` lines 253-277 — not a shared call into the AST.
  3. Any drift produces silently different row sets for logically identical WHEREs.
- **Codebase evidence**: analyzed collate resolution (`collateFor`, Var-only) vs AST `left.getCollate`/`right.getCollate` are separate code paths that can diverge; `createWhereFrom` seam (7 sites) + `info.whereClause` ~1992 + `outerWhere` ~3470 confirmed in `SelectExecutionPlanner`.
- **Survival test**: SURVIVES — but the result-equivalence risk needs a named differential harness, not just per-decision unit tests.

#### Challenge: Decision D3 alternative C — escape-hatch IR node
- **Chosen approach**: reject C (wrapping an un-lowered `SQLBooleanExpression` in an IR variant).
- **Best rejected alternative**: C itself — the only option reaching YTDB-916's literal end state.
- **Counterargument trace**:
  1. C would let FilterStep hold a pure `AnalyzedExpr`, satisfying "old constructor removed, shim removed".
  2. But C's wrapper is a 6th sealed variant that every `AnalyzedExprVisitor`/`AnalyzedExprTransform` implementer must handle as opaque un-lowered AST.
  3. That blast radius exceeds B's step-local `SQLWhereClause` field.
- **Codebase evidence**: `AnalyzedExpr` sealed with 5 permits (lines 26-31); VARIANT-ADDITION compile-break note (lines 20-25); `FilterStep` predicate field is step-local (line 41).
- **Survival test**: SURVIVES — rejection holds; the real cost (every visitor/transform gains an escape-hatch arm) is stronger than the log's "dents I3 cleanliness".

#### Violation scenario: D4 empty AND/OR block edge parity
- **Invariant claim**: "empty `AndBlock` → `Const(true)`, empty `OrBlock` → `Const(false)`, matching the AST's vacuous semantics."
- **Violation construction**:
  1. Start state: an `SQLOrBlock` whose `subBlocks` field is `null` (not the parser default empty list).
  2. Action sequence: `SQLOrBlock.evaluate(Result)` line 53 `if (subBlocks == null) return true;`.
  3. Intermediate state: returns `true`, not `false`.
  4. Violation point: `SQLOrBlock.java:53-55` — the null case returns `true`, contradicting D4's blanket `Const(false)`.
  5. Observable consequence: an implementer mirroring "the AST's vacuous semantics" literally could lower an empty OrBlock to `Const(true)`.
- **Feasibility**: THEORETICAL — the parser initializes `subBlocks = new ArrayList<>()` (line 27), so the null case is unreachable from parsed input; the reachable empty-list case DOES return `false` (matches D4). The defect is in the decision text's precision, not the reachable behavior. Also: AND/OR lowering + `BinaryOperator.AND/OR` do not exist yet (`lowerBoolean` throws, enum is `PLUS…GE`), so D4 is new work written from this spec.

#### Challenge: Decision D5 — minimal source-WHERE bridge for serialization
- **Chosen approach**: analyzed step serializes the source WHERE (reusing `SQLWhereClause.serialize`) + a tag; deserialize re-lowers via `filterStepFor`. Retains source predicate for serialize + prettyPrint only.
- **Best rejected alternative**: (C) narrow serialize removal from FilterStep+ExpandStep + amend acceptance.
- **Counterargument trace**:
  1. The bridge makes the *analyzed* step carry both an `AnalyzedExpr` (eval) and a source `SQLWhereClause` (serialize/EXPLAIN).
  2. That is structurally dual-carry — the shape D3's "either/or, never both" invariant and D3-alt-A rejection ruled out.
  3. The distinction (AST copy never *evaluated*) is real but contradicts D3's stated invariant text.
- **Codebase evidence**: `SelectExecutionPlan.serialize` = 0 call sites (PSI, confirmed) → serialize is dead prod code, so the retained-AST cost is test-only; `FilterStep.prettyPrint` lines 78-90 reads `whereClause.toString()`, so EXPLAIN needs some source form; D3 body states "holds either … never both".
- **Survival test**: SURVIVES — dead-code premise confirmed; but D3/D5 must reconcile the "never both" invariant, and YTDB-1185's filing (the retirement path) should be confirmed real.

#### Open-question challenge: OQ — two load-bearing questions still listed open
- **Claim challenged**: the `## Open Questions` section presents "Fallback strategy…(central design question)" and "AND/OR IR representation" as open, while D3 and D4 record them as decided.
- **Stress scenario**: a reader deriving the Phase-1 artifact reads the fallback strategy as both "Needs user steer" (open) and Option B (D3, decided).
- **Code/log evidence**: two of four Open-Questions entries carry "**RESOLVED**" + Decision-Log pointers; the fallback and AND/OR entries do not, though D3/D4 decide them.
- **Verdict**: the resolutions DID reach the Decision Log (not a gap); the section is stale and self-contradictory (a should-fix bookkeeping fix, not a re-decision).
