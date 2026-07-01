# design-author params — phase1-creation, round 1

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/ir-filter-step-expand-step/docs/adr/ir-filter-step-expand-step/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/ir-filter-step-expand-step/docs/adr/ir-filter-step-expand-step/_workflow/research-log.md
- design_mechanics_path: null (single file — no mechanics companion; keep under ~5 top-level sections)
- round: 1 (ground the whole document)

## What this design is

S1 of the YTDB-901 umbrella (YTDB-916): migrate `FilterStep` and `ExpandStep` predicates
to consume the immutable IR `AnalyzedExpr` instead of the parsed AST `SQLWhereClause`.
This is the first live executor consumer of the S0 substrate (YTDB-915, landed as
`87cbfc0b6d`). Design gate = yes; matched HIGH-risk categories = **Architecture /
cross-component coordination** (moving the AST→IR boundary into the executor) and
**Performance hot path** (`FilterStep.filterMap` runs once per candidate record).

The research log is your ground truth for decisions. D1–D5 are LOCKED and were
strengthened after an adversarial gate (see `## Adversarial gate record` = PASS). Do NOT
re-decide them; author the design that realizes them. Ground every mechanism in the live
code via mcp-steroid PSI (project `ir-filter-step-expand-step-tte37ok1` is open and matches
the working tree; run `steroid_list_projects` once first).

## Seed scope (round 1 — write the whole document)

Single file. Follow `design-document-rules.md` structure. Suggested sections (adjust names
to the rules, keep ≤~5 top-level sections; use Core Concepts since there are ≥3 new domain
terms):

- **Overview** — concept-first elevator pitch: what changes structurally (executor consumes
  IR not AST), why (YTDB-901 boundary separation + hot-path parity), and the two load-bearing
  shapes: the planner-split fallback (D3) and the new lowering/evaluator arms (D1/D4).
- **Core Concepts** — gloss the domain terms a reader needs: `AnalyzedExpr` (sealed immutable
  IR record set: Var/Const/BinaryOp/UnaryOp/FuncCall, + the new `Param`), lowering
  (`SQLWhereClause`→`AnalyzedExpr`, throws `UnsupportedAnalyzedNodeException` outside the
  subset), the analyzed evaluator, `filterStepFor` (the D3 factory), the AST fallback path,
  and `matchesFilters(Result)` (the collation-applying overload the migration preserves).
- **Class Design** — Mermaid classDiagram: `FilterStep`/`ExpandStep` with their either-eval
  carrier (`AnalyzedExpr` OR `SQLWhereClause`, D3), the new `Param` sealed variant, the
  `BinaryOperator` enum gaining `AND`/`OR` (D4), the `filterStepFor` factory, and the analyzed
  evaluator with its new arms (lazy AND/OR short-circuit, in-place comparison fast path,
  `Param` resolution). Prose explaining responsibilities + design choices.
- **Workflow** — Mermaid sequence/flowchart(s): (1) `filterStepFor` attempts lowering →
  analyzed step on success / legacy AST step on `UnsupportedAnalyzedNodeException`; (2) the
  evaluator's lazy AND/OR short-circuit (mirror `SQLAndBlock`/`SQLOrBlock`); (3) the D5
  serialize bridge round-trip (serialize source WHERE + analyzed tag → deserialize re-lowers
  via `filterStepFor`). Pair each diagram with prose.
- **Complex-topic section(s)** — the parts a reviewer cannot reconstruct without help. At
  minimum cover: (a) the two-path result-equivalence risk and its **differential parity
  harness** obligation (D3 Risks/Caveats — run a corpus of lowerable WHEREs through both the
  analyzed and AST FilterStep, assert identical row sets, include a `ci`-collated case);
  (b) the **in-place comparison fast path** port (obligation #1a, load-bearing for LDBC JMH
  neutrality — the analyzed evaluator reuses `EntityImpl.isPropertyEqualTo`/`comparePropertyTo`,
  guarded on left=single base identifier / right=early-calculated constant / row=EntityImpl,
  FALLBACK on any collation/coercion risk); (c) **collation parity** (the analyzed evaluator
  applies the same collate as `evaluate(Result)`; S1's collation work is a regression test,
  not a behavior change — the observable case-insensitive shift belongs to S7's `Identifiable`
  callers); (d) **bind-param lowering** (D1/D2 — new `Param` variant, resolves at eval time by
  the raw `inputParam.getValue(...)` lookup, no coercion; parity-test guard).

## Decision records to seed (the absorption check verifies these)

Seed a design Decision record for **each of D1–D5** from the research log (matching D-codes
D1–D5), each capturing the decision, its rejected alternatives, and its rationale, placed in
the relevant section's "Decisions & invariants" footer (or a decisions area per the rules).
The absorption check will flag any load-bearing log decision (D1–D5) missing a seed D-record,
and any seed D-record that invents a decision the log lacks. Stay faithful to the log:
- **D1** — fold bind-parameter lowering into S1 (new `Param` IR variant + lowering arm +
  evaluator arm).
- **D2** — parity fact: the `Param` arm resolves the RHS bind param by the same raw
  `getValue` lookup the AST uses; no coercion. (Parity-test guard: assert `getValue`, not
  `bindFromInputParams`.)
- **D3** — fallback = planner-level split (Option B), single-class `FilterStep` holding
  *either* `AnalyzedExpr` *or* `SQLWhereClause` **for evaluation** (never both drive
  `filterMap`); `filterStepFor` factory; alternatives A/C/D rejected (C's blast radius:
  every visitor/transform grows an escape-hatch arm).
- **D4** — AND/OR as `BinaryOperator` enum constants, left-fold n-ary blocks, **lazy
  short-circuit** (a HARD correctness requirement, not an optimization); reachable
  empty-block rule AndBlock→Const(true)/OrBlock→Const(false), null-subBlocks case unreachable
  from parsed input; dedicated n-ary boolean node rejected.
- **D5** — serialization = minimal source-WHERE bridge (serialize source WHERE + analyzed
  tag, re-lower on deserialize); lean text-only retention scoped to serialize/EXPLAIN, never
  a second eval path (reconciled with D3's evaluation-scoped "never both"); the vestigial
  plan-serialize removal is deferred to YTDB-1185 (filed).

## Reminders

- Write for a reader who has ONLY this document (curse-of-knowledge is the failure to avoid).
- Gloss every domain term in place at first use; explain mechanisms, don't just name them;
  BLUF + house-style register.
- Emit Mermaid for diagrams. Do not exceed the two house-style explanation clauses (no tutorial).
- Return ONLY a thin summary (what/where/open questions) — never the drafted content.
