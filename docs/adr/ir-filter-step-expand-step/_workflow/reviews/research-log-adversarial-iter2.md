<!-- REVIEW-FILE v1 (conventions-execution.md §2.5) -->
```yaml
review:
  kind: adversarial
  role: reviewer-adversarial
  phase: 1
  scope: research-log
  target: docs/adr/ir-filter-step-expand-step/_workflow/research-log.md
  iteration: 2
  variant: verdict-producer
  matched_categories: ["Architecture / cross-component coordination", "Performance hot path"]
  verdict: PASS
  findings: 7
  blockers: 0
  should_fix: 0
  suggestions: 0
  still_open: 0
  tooling: mcp-steroid PSI (iter-1 facts reused; A5 code fact spot-checked on SQLOrBlock.java)
index:
  - id: A1
    sev: should-fix
    anchor: "### A1 "
    target: "D1 — fold bind-parameter lowering into S1"
    cert: "Verdict D1"
    disposition: VERIFIED
    basis: "load-bearing claim reframed to qualitative dominance; 37/64, 4/9, 1/9→5/9 now attributed to a research-time LDBC SF1 query-set survey as corroborating, not sole support"
  - id: A2
    sev: suggestion
    anchor: "### A2 "
    target: "D2 — AST resolves comparison-RHS bind param to raw bound value"
    cert: "Verdict D2"
    disposition: VERIFIED
    basis: "parity-test guard note added: the Param arm must resolve via raw inputParam.getValue(...), not bindFromInputParams"
  - id: A3
    sev: should-fix
    anchor: "### A3 "
    target: "D3 — planner-level split, single-class FilterStep fallback"
    cert: "Verdict D3"
    disposition: VERIFIED
    basis: "differential parity harness named as a plan obligation: dual-run lowerable-WHERE corpus through analyzed + AST FilterStep, assert identical row sets, includes a ci-collated case"
  - id: A4
    sev: suggestion
    anchor: "### A4 "
    target: "D3 — alternative C (escape-hatch IR node) rejection"
    cert: "Verdict D3-alt-C"
    disposition: VERIFIED
    basis: "C-rejection strengthened to the concrete blast radius: every visitor/transform implementer grows an escape-hatch arm vs B's step-local field"
  - id: A5
    sev: should-fix
    anchor: "### A5 "
    target: "D4 — empty-vs-null vacuous-block edge parity"
    cert: "Verdict D4-vacuous"
    disposition: VERIFIED
    basis: "reachable non-null-empty rule stated (AndBlock→true, OrBlock→false); null-subBlocks case marked unreachable from parsed input, need not be modeled; code-confirmed on SQLOrBlock.java:27/:53"
  - id: A6
    sev: should-fix
    anchor: "### A6 "
    target: "D5 — source-predicate retention vs D3 never-both invariant"
    cert: "Verdict D5"
    disposition: VERIFIED
    basis: "D3 invariant amended to evaluation-scoped; D5 retention reconciled as never a second eval path, leans text-only; YTDB-1185 filing confirmed"
  - id: A7
    sev: should-fix
    anchor: "### A7 "
    target: "Open Questions — two resolved questions still listed open"
    cert: "Verdict OQ"
    disposition: VERIFIED
    basis: "both entries now carry RESOLVED with D3/D4 pointers, matching the two already-resolved siblings"
evidence_base:
  - "Verdict D1: VERIFIED — payoff reframed qualitative + survey-attributed"
  - "Verdict D2: VERIFIED — parity-test guard pinned"
  - "Verdict D3: VERIFIED — differential parity harness now a named plan obligation"
  - "Verdict D3-alt-C: VERIFIED — rejection cost concretized"
  - "Verdict D4-vacuous: VERIFIED — reachable vs null rule split, code-confirmed"
  - "Verdict D5: VERIFIED — never-both scoped to eval, retention reconciled, YTDB-1185 confirmed"
  - "Verdict OQ: VERIFIED — both entries marked RESOLVED"
  - "New-finding sweep: no revision overreached or introduced a fresh inconsistency"
```

## Findings

### A1 [should-fix]
**Certificate**: Verdict D1 — fold bind-parameter lowering into S1
**Target**: Decision D1
**Disposition**: VERIFIED
**Iter-1 challenge**: D1's scope-expansion payoff rested on four numeric LDBC claims (37/64 WHEREs touch a bind param; 4/9 top-level FilterStep WHEREs bind-param-blocked alone; lowerable top-level fraction ~1/9 → ~5/9) that appeared nowhere else and were not attributed to a source; if the true fraction were lower, the S1.5-deferral alternative became competitive.
**How the log resolved it**: D1's `**Why:**` (research-log.md:70-88) now makes the **load-bearing** claim explicitly qualitative — "production WHEREs are overwhelmingly parameterized … so a lowering subset that throws on bind params runs the AST fallback on nearly every real query" — and demotes the four figures to `**magnitude**` estimates "from a research-time survey of the LDBC SF1 query set (the production-shaped corpus this project benchmarks against; see the LDBC JMH acceptance gate)." It states outright that these "are estimates from that survey, not derivable from the code; the decision rests on the qualitative dominance, for which the exact fraction is corroborating evidence, not the sole support — even at a lower true fraction, bind params remain the highest-payoff-per-effort lowering item."
**Assessment**: This is exactly the fix A1 proposed — cite the measurement source and restate the load-bearing claim qualitatively so the decision does not rest on an unverifiable exact figure. The survey is an internally-generated research-time artifact rather than an externally-checkable citation, but the log no longer *leans* on the figure: it explicitly severs the decision from the exact fraction and grounds it on the qualitative dominance the code-confirmed cheap-resolution mechanism (A2) supports. The S1.5-deferral competitiveness objection is closed because the payoff argument no longer depends on 5/9 vs 3/9. Resolved.

### A2 [suggestion]
**Certificate**: Verdict D2 — AST resolves a comparison-RHS bind param to the raw bound value
**Target**: Decision/parity-fact D2
**Disposition**: VERIFIED
**Iter-1 challenge**: A suggestion to pin the exact divergence guard — the D1 parity test should assert the `Param` arm resolves through the raw `inputParam.getValue(...)` path and not `bindFromInputParams`, so a future refactor rerouting RHS resolution through coercion is caught rather than silently diverging the analyzed arm from the AST.
**How the log resolved it**: D2 (research-log.md:112-116) now carries a `**Parity-test guard:**` clause verbatim to that effect: "because this is load-bearing, the D1 parity test should assert the `Param` arm resolves through the raw `inputParam.getValue(...)` path and *not* `bindFromInputParams`, so a future refactor that reroutes RHS resolution through the coercion path is caught by a red test rather than silently diverging the analyzed arm from the AST."
**Assessment**: The suggestion is folded verbatim. The underlying parity fact (raw resolution, no `toParsedTree` coercion on the scalar comparison path) was PSI-confirmed in iter-1 and the revision does not change the claim, so no re-audit is needed. Resolved.

### A3 [should-fix]
**Certificate**: Verdict D3 — fallback = planner-level split (Option B), single-class FilterStep
**Target**: Decision D3
**Disposition**: VERIFIED
**Iter-1 challenge**: D3's own Risks/Caveats named the load-bearing risk — "two evaluation paths (analyzed vs AST) must stay result-equivalent" — but left it unmethodized. Because the analyzed evaluator is a genuine re-implementation (reconstructs operators, re-derives collate independently), drift produces *silently* different row sets for logically identical WHEREs, and per-decision unit tests do not guard the two-path equivalence.
**How the log resolved it**: D3's Risks/Caveats (research-log.md:159-176) now states the risk as "**the load-bearing risk of the slice**," explains why (`filterStepFor` routes the same predicate to either evaluator; the analyzed evaluator is "a genuine re-implementation … not a shared call into the AST"), and adds an explicit `**Mitigation — a differential parity harness (plan obligation, not just per-decision unit tests):**` — "run a corpus of lowerable WHEREs (single-segment comparisons, arithmetic, `NOT`, method calls, bind params, AND/OR combinations) through *both* the analyzed `FilterStep` and the AST `FilterStep` over the same rows and assert identical result sets; include a `ci`-collated case so the two collate derivations are compared directly. The plan must carry this as a named test invariant."
**Assessment**: This is the differential parity mechanism A3 asked for — a named plan obligation, corpus-driven, dual-path, row-set-equivalence, with the ci-collated case that directly compares the two collate derivations the challenge flagged as the most likely drift point. The obligation is bound to the plan ("The plan must carry this as a named test invariant"), so it will not evaporate at decomposition. Resolved.

### A4 [suggestion]
**Certificate**: Verdict D3 alternative C — escape-hatch IR node
**Target**: Decision D3 (rejected alternative C)
**Disposition**: VERIFIED
**Iter-1 challenge**: A suggestion to strengthen the C-rejection with the real cost — every `AnalyzedExprVisitor`/`AnalyzedExprTransform` implementer would gain an escape-hatch arm handling an opaque un-lowered AST node — rather than the softer "dents I3 cleanliness."
**How the log resolved it**: D3's Alternatives-rejected (research-log.md:143-151) now spells out the blast radius: C "re-introduces AST-inside-IR coupling … with a **wider blast radius than B**: a 6th sealed `AnalyzedExpr` variant carrying an opaque AST node forces *every* `AnalyzedExprVisitor` / `AnalyzedExprTransform` implementer to grow an escape-hatch arm handling 'un-lowered opaque AST' (an evaluator arm, a lowerer arm, and an arm in each later optimizer pass), whereas B confines the AST to a step-local `SQLWhereClause` field touched only by `filterMap`'s branch. C also propagates the coupling into the framework the S18/YTDB-1187 end-state audit is meant to keep AST-free."
**Assessment**: The concrete cost is named exactly as the suggestion proposed, and the addition ties C's coupling to the S18/YTDB-1187 end-state audit, sharpening why C is worse than B rather than merely "unclean." Resolved.

### A5 [should-fix]
**Certificate**: Verdict D4 — empty AND/OR block edge parity
**Target**: Decision D4 (Edge-parity clause)
**Disposition**: VERIFIED
**Iter-1 challenge**: D4's Edge-parity clause named only one of the AST's two vacuous cases. `SQLOrBlock.evaluate` returns `true` when `subBlocks == null` but `false` when `subBlocks` is a non-null empty list; D4's blanket "empty `OrBlock` → `Const(false)`" was imprecise, and an implementer mirroring "the AST's vacuous semantics" literally could pick either branch.
**How the log resolved it**: D4's Edge-parity clause (research-log.md:201-213) now distinguishes the two cases precisely: it models "only the **reachable** shape: a non-null empty `subBlocks` list → `AndBlock` = `Const(true)`, `OrBlock` = `Const(false)`," and separately notes the null-`subBlocks` guard where "*both* blocks return `true` (`SQLOrBlock.evaluate:53` `if (subBlocks == null) return true`)" is "**unreachable from parsed input** — the parser initializes `subBlocks = new ArrayList<>()` (`SQLOrBlock.java:27`)," concluding "The lowerer need not model the null case."
**Assessment**: Code spot-check confirms the cited facts: `SQLOrBlock.java:27` initializes `subBlocks = new ArrayList<>()`; `:53-55` returns `true` on the null guard; the loop falls through to `return false` for a non-null empty list. The revision states the reachable rule and correctly marks the null case out of scope, resolving the ambiguity the challenge identified. The revision also usefully front-loads the "1-element block unwraps to its single element (no wrapper), so the fold rarely produces a vacuous node at all" observation the suggestion mentioned. Resolved.

### A6 [should-fix]
**Certificate**: Verdict D5 — source-predicate retention vs D3 "never both"
**Target**: Decision D5
**Disposition**: VERIFIED
**Iter-1 challenge**: D5's bridge makes the *analyzed* step carry both an `AnalyzedExpr` (eval) and a source `SQLWhereClause` (serialize/EXPLAIN) — structurally dual-carry — contradicting D3's stated invariant that FilterStep "holds *either* an `AnalyzedExpr` *or* a `SQLWhereClause` (never both)." The reconciliation and the retirement path (YTDB-1185) needed confirming.
**How the log resolved it**: Both sides now reconcile explicitly. D3 (research-log.md:123-128) amends the invariant to be evaluation-scoped: "`FilterStep` holds *either* an `AnalyzedExpr` *or* a `SQLWhereClause` **for evaluation** (never both drive `filterMap` …). This 'never both' invariant is scoped to the **evaluation** carrier; it is not contradicted by D5's serialize bridge, which additionally retains a display/serialize-only source form on the analyzed step — that retained form is evaluated off neither path." D5's Risks/Caveats (research-log.md:256-274) mirrors this ("that invariant is scoped to the *evaluation* carrier (amended in D3), so the analyzed step legitimately carries the `AnalyzedExpr` (eval) plus a source form (serialize/EXPLAIN) without violating it — the source form is never a second eval path"), leans text-only retention ("**Retention form — lean text-only.** … the plan should prefer a **`String`** source form"), and confirms the retirement path: "**Retirement path is real, not aspirational:** the companion removal is **filed as YTDB-1185**."
**Assessment**: All three sub-asks are met — the D3 invariant is amended to evaluation scope, D5 reconciles against it and states the retained form is never a second eval path, the text-only lean is adopted, and YTDB-1185 is confirmed filed (also cross-referenced in the RESOLVED Open-Questions entry at research-log.md:509-517). The two decisions now tell one consistent story. The dead-code premise underpinning the whole bridge (`SelectExecutionPlan.serialize` = 0 project call sites) was PSI-confirmed in iter-1 and is unchanged. Resolved.

### A7 [should-fix]
**Certificate**: Verdict OQ — two load-bearing questions still listed open
**Target**: Open Questions section
**Disposition**: VERIFIED
**Iter-1 challenge**: The `## Open Questions` section presented the fallback-strategy and AND/OR-representation questions as open ("Needs user steer") while D3 and D4 recorded them as decided — a stale-bookkeeping contradiction a reader deriving the plan would trip over.
**How the log resolved it**: Both entries now carry a `**RESOLVED**` prefix with a Decision-Log pointer, matching the two already-resolved siblings. research-log.md:493-502: "**RESOLVED (D3) — Fallback strategy for un-lowerable WHERE clauses …** **Decided in D3: Option B** …"; research-log.md:503-508: "**RESOLVED (D4) — AND/OR IR representation.** … **Decided in D4: extend `BinaryOperator` with `AND`/`OR`, lazy short-circuit** …"
**Assessment**: All four Open-Questions entries are now uniformly marked RESOLVED with Decision-Log pointers; the self-contradiction is gone and the section tells one story. Resolved.

## New-finding sweep

Re-read the five Decision Log entries, the amended invariant reconciliation (D3↔D5), and the Open-Questions section as revised, checking whether any strengthening overreached or introduced a fresh inconsistency.

- **D1 survey attribution** does not overreach: the log is candid that the figures are internally-generated estimates "not derivable from the code," so it makes no verifiability claim it cannot support, and the decision is explicitly severed from the exact fraction. No new finding.
- **D3↔D5 reconciliation** is internally consistent across both entries: D3's amended text (evaluation-scoped never-both) and D5's mirror ("never a second eval path") say the same thing from both sides, and the D3 body's own restatement at research-log.md:123-128 does not contradict its Alternatives-rejected block. The text-only retention lean in D5 is consistent with D3-alt-A's rejection (A retained a *live evaluated* AST; D5's text form is evaluated off neither). No new finding.
- **D4 vacuous-rule split** matches the code (spot-checked) and does not contradict the lazy-short-circuit HARD-requirement clause above it. No new finding.
- **A3 differential-parity-harness corpus** lists "AND/OR combinations" among the lowerable shapes to run through both paths — consistent with D4 making AND/OR genuine S1 lowering work, so the harness scope and the D4 scope agree rather than conflict. No new finding.
- No revision reopened a decision or created a dangling reference; YTDB-1185 is cited consistently in D5, the Companion-action block, and the Open-Questions RESOLVED entry.

No new findings.

## Evidence base

#### Verdict: Decision D1 — fold bind-parameter lowering into S1
- **Iter-1 finding**: should-fix — LDBC payoff figures (37/64, 4/9, 1/9→5/9) uncited; scope expansion leaned on an unshown measurement.
- **Log revision**: `**Why:**` reframed — load-bearing claim now qualitative (production WHEREs overwhelmingly parameterized), figures demoted to survey-attributed corroborating estimates explicitly severed from the decision ("even at a lower true fraction, bind params remain the highest-payoff-per-effort lowering item").
- **Disposition**: VERIFIED. The proposed fix (cite source + restate qualitatively) is implemented; the S1.5-deferral competitiveness objection is closed.

#### Verdict: Decision D2 — raw-value bind-param parity
- **Iter-1 finding**: suggestion — pin the `getValue`-not-`bindFromInputParams` parity-test guard.
- **Log revision**: `**Parity-test guard:**` clause added, folding the suggestion verbatim.
- **Disposition**: VERIFIED. Underlying PSI-confirmed parity fact unchanged; no re-audit needed.

#### Verdict: Decision D3 — planner-level split, single-class FilterStep
- **Iter-1 finding**: should-fix — two-path result-equivalence obligation unmethodized.
- **Log revision**: Risks/Caveats now names a `**Mitigation — a differential parity harness (plan obligation …)**`: dual-run lowerable-WHERE corpus through analyzed + AST FilterStep, assert identical row sets, include a ci-collated case; bound as a named plan test invariant.
- **Disposition**: VERIFIED. The named differential harness A3 required is present and plan-bound.

#### Verdict: Decision D3 alternative C — escape-hatch IR node rejection
- **Iter-1 finding**: suggestion — strengthen the C-rejection with real cost.
- **Log revision**: Alternatives-rejected now names the blast radius (every visitor/transform/optimizer-pass implementer gains an escape-hatch arm) vs B's step-local field, and ties C to the S18/YTDB-1187 end-state audit.
- **Disposition**: VERIFIED. Concrete cost named as proposed.

#### Verdict: Decision D4 — empty AND/OR vacuous parity
- **Iter-1 finding**: should-fix — empty-vs-null vacuous-block imprecision.
- **Log revision**: Edge-parity clause models only the reachable non-null-empty rule (`AndBlock→true`, `OrBlock→false`); null-`subBlocks` case marked unreachable from parsed input (`SQLOrBlock.java:27` parser init), need not be modeled.
- **Code spot-check**: `SQLOrBlock.java:27` `subBlocks = new ArrayList<>()`; `:53-55` null-guard returns `true`; loop fall-through returns `false`. Cited facts confirmed.
- **Disposition**: VERIFIED. Ambiguity resolved with precise, code-accurate rule.

#### Verdict: Decision D5 — source-predicate retention reconciliation
- **Iter-1 finding**: should-fix — retained source predicate contradicts D3 "never both"; YTDB-1185 filing unconfirmed.
- **Log revision**: D3 invariant amended to evaluation-scoped; D5 reconciles (retained form never a second eval path), leans text-only `String` retention, confirms YTDB-1185 filed and cross-references the RESOLVED Open-Questions entry.
- **Disposition**: VERIFIED. All three sub-asks (invariant scope, reconciliation, retirement-path confirmation) met; dead-serialize premise unchanged from iter-1 PSI.

#### Verdict: Open Questions — two resolved questions still listed open
- **Iter-1 finding**: should-fix — fallback-strategy and AND/OR entries stale (open while D3/D4 decide them).
- **Log revision**: both entries prefixed `**RESOLVED (D3)**` / `**RESOLVED (D4)**` with Decision-Log pointers, matching the two already-resolved siblings.
- **Disposition**: VERIFIED. Section now internally consistent.

#### New-finding sweep
- Re-read D1's survey attribution, the D3↔D5 reconciliation, the D4 vacuous split, the A3 corpus scope, and the Open-Questions revisions for overreach or fresh inconsistency. None found: no decision reopened, no dangling reference, YTDB-1185 cited consistently across three sites.
