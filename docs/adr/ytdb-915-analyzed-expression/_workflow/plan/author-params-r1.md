# design-author params — phase1-creation, round 1

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/analyzed-expression/docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
- round: 1
- flagged_passages: (none — round 1 grounds the whole document)

## Design focus (notes for grounding; the research log is the authority)

This is the S0 analyzed-expression substrate (YTDB-915 / YTDB-901 slice 0): a sealed
`AnalyzedExpr` IR alongside the existing `SQL*` parse-tree AST, a visitor/transform
framework, a lowering pass over a covered subset, and a runtime evaluator validated
by round-trip parity against the AST. No live executor consumer ships in S0.

Cover every load-bearing research-log decision as a seed D-record (the absorption
check enforces this): D1 (sealed-interface IR, 5 record variants), D2 (visitor
interface + static `dispatch` switch, no `accept`), D3 (single `evaluate(Result,
ctx)` overload), D4 (drop `Cast`), **D5-R** (whole-enum `NumericOps` lift-and-shift
to `sql/util/`), D6 (`Var` name-path list), D7 (`UnsupportedAnalyzedNodeException
extends CommandExecutionException`), D8 (`AnalyzedExprTransform` structural
sharing), D9 (transform defaults, none on base visitor), **D10** (parenthesized
arithmetic recurses, only subquery/CASE throw), **D11** (comparison evaluator
replicates `SQLBinaryCondition.evaluate`: collate transform + delegate to the parser
operator instance — NOT bare statics), **D12** (lowerer builds the nested `BinaryOp`
tree by a structural precedence-climbing fold; value semantics from shared
`NumericOps`; AST hot fold untouched), **D13** (four-track decomposition T1–T4),
**D14** (exhaustive-or-throw field-walk; `value` flagged for Phase-A PSI). Plus the
covered-subset correction (unary minus dropped; `UnaryOp` is for boolean `NOT` via
`SQLNotBlock`). Invariants I1 (round-trip parity), I2 (no silent fallback), I3
(exhaustive visitor dispatch) each map to a testable assertion.

Suggested section shape (adapt per design-document-rules): Overview; Class Design
(Mermaid classDiagram of the sealed IR + visitor + transform + NumericOps +
exception); Workflow (Mermaid sequence/flowchart for parse → lower → evaluate and
the round-trip parity check); then complex-topic sections for the lowering pass
(field-walk + D10 parenthesis + D12 precedence fold + D14 exhaustive-or-throw), the
evaluator (D11 collation-faithful comparison reuse + `NumericOps` arithmetic + D3),
the `NumericOps` whole-enum extraction (D5-R), the visitor/transform framework (D2
static dispatch, D8 structural sharing, D9 defaults), and the round-trip parity test
plan (I1 matrix: `a+b*c`, `a*b+c`, `a-b-c`, `a-b+c`, `(a+b)*c`, collated-column
comparison, type-coercing NE).

Ground the code anchors via PSI against current develop — the research log's anchors
were re-verified at gate time but confirm signatures/locations you cite.
