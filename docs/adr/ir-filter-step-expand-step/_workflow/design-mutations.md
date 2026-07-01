# Design Mutations Log — ir-filter-step-expand-step (S1)

## Mutation 1 — 2026-07-01 — phase1-creation (design.md)

**Diff summary**: Seeded `design.md` (597 lines, single file, no mechanics companion) for
S1 — migrating `FilterStep` + `ExpandStep` to consume `AnalyzedExpr`. Structure: Overview,
Core Concepts (five domain terms), Class Design (step classes + factory, the IR's sixth `Param`
variant and `AND`/`OR` operators, the evaluator's three new arms), Workflow (factory
lower-or-fall-back flowchart, lazy AND/OR short-circuit sequence + worked divide-by-zero trace,
serialize-bridge round-trip), and a `# Complex topics` Part grouping four deep-dives (two-path
result equivalence + differential parity harness, in-place comparison fast-path port, collation
parity, bind-parameter lowering). D1–D5 seeded as D-records. Code-grounded via mcp-steroid PSI.

**Mechanical checks** (target=design): PASS (0 findings).
**Cold-read** (scope: whole-doc): comprehension gate PASS. Two should-fix structural findings
assessed against design-document-rules and dispositioned: F2 (rename `Decisions & invariants`
footer to `References`) REJECTED — the footer is correctly named per D11 (renamed from
`References` under YTDB-1083); F1 (`# Complex topics` H1 → `## Complex topics`) KEPT AS-IS —
`# Complex topics` is a valid `# Part`-style grouping heading (H1 by rule), and the demote would
break the per-section 4-block shape. Absorption check: PASS all three rounds (D1–D5 seeded, no
invented decisions, coverage complete both ways).

**Findings**: Readability inner loop ran the full `iteration_budget` (3 rounds): 12 → 6 → 9
should-fix prose findings (the oscillating dense-but-acceptable tail; no blockers). On budget
exhaustion the S5 path applied the cheap unambiguous fixes (roundabout-negation rewrites, unglossed
`InPlaceResult`/`PSI`/push-down-level glosses, the four-topic intro → bullet list, a
verbless-fragment fix). One residual left accepted: the D2 parity-fact paragraph (already two
linearized sentences; further splitting judged over-fragmentation).

**Iterations**: 3 of 3 (inner loop did not reach dual-clean on the readability tail; comprehension
gate PASS; S5 cheap fixes applied, mechanical re-verified PASS).
