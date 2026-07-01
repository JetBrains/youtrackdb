# Handoff: Phase 0 → 1 — S1 research complete, ready for plan creation

**Paused:** 2026-07-01
**Phase:** 0 (research complete; next is the Step 4 planning transition, `/create-plan`)
**Context level at pause:** safe (user-requested pause, not context-triggered)
**Branch:** ir-filter-step-expand-step
**HEAD:** 872dbecd76 "Log IR-only end-state gap (S18/YTDB-1187 filed)"
**Unpushed:** 0 commits

## What I was investigating
The S1 (YTDB-916) design — migrating `FilterStep` + `ExpandStep` predicates to
consume `AnalyzedExpr` instead of `SQLWhereClause`, the first live consumer of the
S0 substrate. Research is **COMPLETE**: five decisions locked (D1–D5), both open
threads resolved, umbrella follow-on gaps filed. The next step is the Step 4
planning transition — **not** more research.

## Already ruled out
- Clean cutover (AST fully gone from `FilterStep` in S1) — impossible; the S0 subset
  cannot cover real WHERE clauses. Chose the planner-split fallback (D3).
- Fallback options A (dual-carry) and C (escape-hatch IR node) — rejected (D3).
- Dedicated n-ary boolean node for AND/OR — rejected; extend `BinaryOperator` (D4).
- Full IR wire format for serialize (Option A) and narrow FilterStep/ExpandStep
  serialize removal (Option C) — rejected; chose the minimal source-WHERE bridge
  (D5) because the plan serialize/deserialize round-trip is dead production code.

## Most promising lead
The design is fully settled; `research-log.md` is the authoritative ledger. D1 (fold
bind params in), D2 (raw-value parity fact), D3 (planner-split single-class
`FilterStep` fallback via `filterStepFor`), D4 (AND/OR via `BinaryOperator` + lazy
short-circuit), D5 (serialize = minimal source-WHERE bridge). Both open threads
closed this session:
- **Collation = parity** (regression test, not a behavior change). PSI-confirmed no
  S1-scoped site uses the collation-skipping `evaluate(Identifiable)`;
  `FilterStep.filterMap`/`ExpandStep` push-down enter via `matchesFilters(Result)`
  and the S0 analyzed evaluator applies the same collate.
- **Serialize/deserialize is dead prod code** (EXPLAIN uses `toResult`/`prettyPrint`,
  plan cache reuses live objects via `copy(ctx)`, interface defaults throw, no
  remote plan shipping) → S1 uses the D5 bridge.

## Open questions
- None remaining for S1 — both prior threads resolved.

## Raw notes / partial findings
Umbrella follow-ons filed this session (do NOT re-file; do NOT re-post the 2
YTDB-916 comments — they are already posted):
- **YTDB-1185** — remove vestigial (dead) execution-plan serialize/deserialize
  repo-wide (relates YTDB-901 / 916 / 930).
- **YTDB-1186** — exploit immutable IR for share-not-copy plan caching (depends
  YTDB-901, relates YTDB-916).
- **YTDB-1187 / S18** — enforce IR-only pipeline: final audit + ArchUnit-style
  guard (subtask YTDB-901, depends YTDB-930).
- Earlier: S16 (YTDB-1183) + S17 (YTDB-1184) boolean/self-ref lowering.

All grounded facts and file:line anchors live in `research-log.md`
(`## Decision Log`, `## Surprises & Discoveries`) — do not re-derive them.

## Resume notes
- **Do NOT re-explore / re-derive:** the collation PSI audit, the serialize
  dead-code audit, the plan-cache live-object model, D1–D5, or the umbrella gaps
  (all filed). `research-log.md` is authoritative.
- **Do NOT re-ask the aim** (captured in `research-log.md` `## Initial request`) and
  do NOT restart research.
- **Next action on resume — research is COMPLETE, run Step 4 of `create-plan`
  directly:**
  1. Design-gate classification: propose `design_gate=yes` (S1 is an
     architecture-central substrate consumer — new IR variants, lowering +
     evaluator arms, planner-split fallback, executor-site migration) and confirm
     with the user; record the matched HIGH-risk categories.
  2. Run the adversarial gate on `research-log.md` (write iter files under
     `_workflow/reviews/`; append the verdict to the log's
     `## Adversarial gate record`).
  3. On gate clear, author `design.md` via `edit-design` (`phase1-creation`), then
     derive the plan + track files (Step 4a → 4b).
  Resume via `/create-plan` — this handoff drives it before the aim prompt.
