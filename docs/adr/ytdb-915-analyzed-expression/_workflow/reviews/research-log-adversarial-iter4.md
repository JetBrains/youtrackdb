<!-- Adversarial research-log review — iteration 4 (re-run). Verdict-producer
     manifest variant per conventions-execution.md §2.5. Scope: the single
     newly-added decision D19 (per-child benchmark-coverage obligation, blanket
     S1–S7), plus a re-verification that D19 does not undermine the previously-
     cleared D1–D18 / D5-R / D6-R (it refines D17). The iter1 (NEEDS REVISION),
     iter2 (PASS), and iter3 (PASS) verdicts on D1–D18 stand and are not
     re-litigated except where D19 touches them. -->

# Research-log adversarial review — iteration 4

```yaml
review_type: adversarial
scope: research-log (Phase 0→1 gate)
iteration: 4
variant: verdict-producer
target: docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
in_scope_decisions: [D19]
refines: [D17]
lenses: [Architecture / cross-component coordination, Performance hot path]
verdict: PASS
findings: 1
prior_verdicts:
  iter1: NEEDS REVISION (resolved)
  iter2: PASS
  iter3: PASS
verdicts:
  A11: VERIFIED
  A12: VERIFIED
  A13: VERIFIED
  A14: VERIFIED
index:
  - id: A15
    sev: should-fix
    anchor: "### A15 "
    title: "D19's blanket-scope rationale records the user's rule-simplicity call but omits the measurement-quality risk it accepts: a per-child JMH that measures nothing new is indistinguishable from a missing one"
    loc: research-log.md §D19
    cert: "Challenge: Decision D19"
    basis: "ldbc-jmh-compare.yml (PR comparison comment, alerter); ~15 @Benchmark microbenchmark classes in core/src/test; D17 deferral basis"
evidence_base:
  - "Challenge: Decision D19 — blanket per-child JMH coverage vs hot-path-conditional"
  - "Assumption test: D19 'infrastructure already exists, incremental per-child work'"
  - "Cross-check: D19 does not undermine D17 (it refines it) or D1–D18/D5-R/D6-R"
  - "Verdict carry-forward: iter3 findings A11–A14 all VERIFIED against live log text"
```

## Verdict

**PASS.** D19 survives adversarial challenge. Its central code-grounded claims hold:
the benchmark infrastructure it leans on is real (`ldbc-jmh-compare.yml` posts and
updates the PR comparison comment; the three named skills exist; the project carries
~15 standalone `@Benchmark` microbenchmark classes as precedent), so "incremental
per-child work, not new plumbing" is true. The blanket-scope choice is a recorded
**user decision** (rule simplicity over CCX33 cost), and the prompt is explicit that
the user's authority to choose blanket is not the target — so the hot-path-conditional
alternative cannot be a blocker. One `should-fix` (A15) asks D19 to record the
*measurement-quality* risk the blanket rule accepts, which the rationale currently
leaves implicit. No re-decision is forced.

D19 **refines** D17 without contradiction: it generalizes D17's S1-specific
deferred-JMH gate into a per-child obligation across the umbrella, and D17's deferral
of *S0's own* measurement to S1 stays intact (D19 adds no S0/T2 gate — it explicitly
keeps S0 on the existing `MathExpressionTest` correctness gate). The previously-cleared
D1–D18 / D5-R / D6-R are not undermined.

The four iter3 findings (A11–A14) are all **VERIFIED** as resolved against the live log
text. The gate clears.

## Evidence base

#### Challenge: Decision D19 — blanket per-child JMH coverage vs hot-path-conditional
- **Chosen approach**: every child issue S1–S7 must (a) add a targeted JMH microbenchmark
  exercising the eval path it touches and (b) keep passing the existing LDBC SF1
  neutrality gate. Scope is **blanket** — applies even to compile/parse-time-only slices
  that touch no runtime hot path. Mechanism is JMH microbenchmark + LDBC-neutrality net,
  not literal SNB query-set additions (SNB stays fixed for run-to-run comparability).
- **Best rejected alternative**: the log itself lists it — scope the obligation to
  hot-path child issues only, letting compile/parse-time-only slices ride the neutrality
  net alone. D19 records the user rejected it "for rule simplicity," accepting that
  "pure-refactor slices run a benchmark that measures nothing new and pay the CCX33 cost."
- **Counterargument trace**:
  1. The hot-path-conditional alternative is genuinely *cheaper and arguably more honest*:
     a pure-refactor slice (e.g. a future `NumericOps`-style lift-and-shift, or a
     spec-only slice) has, by construction, no new runtime eval path to measure — its
     correctness gate is the existing test suite and its perf story is "no new path." A
     blanket rule forces such a slice to author a JMH microbenchmark that either
     re-measures an existing path (redundant with the neutrality net) or measures a path
     the slice did not change (vacuous). Per the log's own framing this "measures nothing
     new" while still paying the Hetzner CCX33 run cost.
  2. *But the user explicitly chose blanket for rule simplicity*, and the prompt forecloses
     challenging that authority. The legitimate adversarial line is therefore the
     **rationale's completeness**, not the choice: the blanket rule trades one failure mode
     (a hot-path slice slips through with no targeted measurement — the "green-but-unmeasured
     blind spot" D19 is built to close) for a *different, unrecorded* failure mode — a
     **measurement-quality** risk. A per-child JMH authored under a blanket mandate, when the
     child has no new path to exercise, will be written to satisfy the rule, not to measure
     a real path. A reviewer or CI gate reading "child has a JMH benchmark: ✓" cannot
     distinguish a benchmark that meaningfully exercises the child's new eval path from one
     that exercises an arbitrary unchanged path to discharge the obligation. So the blanket
     rule does not actually *guarantee* the property D19 wants ("each child's functionality
     is measured") — it guarantees only that *a* benchmark exists. The hot-path-conditional
     alternative, by contrast, makes "no targeted benchmark" a *visible, intended* state for
     a refactor slice rather than a same-shaped-as-vacuous one.
  3. This is the gap a perf reviewer interrogates: the rule's enforceability hinges on
     someone judging benchmark *relevance*, which the blanket framing pushes off-ledger.
     D19 should record (i) that the accepted cost is not only CCX33 minutes but the
     measurement-quality risk that a vacuous benchmark is indistinguishable from a missing
     one, and (ii) the mitigation it relies on — the child issue states which eval path the
     benchmark exercises, so relevance is asserted in the child, not left to inference.
- **Codebase evidence**: the infrastructure is real and the mechanism is precedented, so
  the "incremental, not new plumbing" half of the rationale holds —
  `.github/workflows/ldbc-jmh-compare.yml` posts and updates the per-PR benchmark
  comparison comment (`createComment` at `:183`, `:557`; in-progress-marker cleanup at
  `:595-601`) and `jmh-alerter-tests.yml` / `ldbc-jmh-nightly.yml` exist; the three named
  skills exist (`.claude/skills/run-jmh-benchmarks-hetzner`, `profile-jmh-regressions`,
  `profile-query-bottleneck`); and the repo carries ~15 standalone `@Benchmark`
  microbenchmark classes (`core/src/test/.../RidSetBenchmark.java`,
  `.../nkbtree/normalizers/ComparatorBenchmark.java`,
  `.../sbtree/singlevalue/v3/BTreeGetBenchmark.java`, `KeyNormalizerBenchmark`, etc.), so a
  targeted per-child eval microbenchmark follows an existing pattern. What the evidence does
  **not** supply is any artifact that judges a per-child benchmark's *relevance* — there is
  no gate that fails a benchmark for measuring the wrong path; the PR comparison comment
  reports deltas on whatever benchmarks run. That is the measurement-quality gap A15 asks
  D19 to record.
- **Survival test**: WEAK. The decision (blanket scope, JMH-microbenchmark + neutrality-net
  mechanism, SNB stays fixed) survives — the user chose it, the infrastructure backs it, and
  it is strictly a forward umbrella commitment that leaves S0 untouched. The *rationale*
  needs one more clause: name the measurement-quality risk the blanket rule accepts (a
  vacuous per-child benchmark is indistinguishable from a missing one) and the mitigation it
  leans on (the child issue states which eval path its benchmark exercises). With that, the
  "rule simplicity over CCX33 cost" trade is recorded completely rather than recording only
  the CCX33-minutes half of the cost.

#### Assumption test: D19 "infrastructure already exists, so this is incremental per-child work"
- **Claim**: "Infrastructure already exists (`run-jmh-benchmarks-hetzner`, the PR
  benchmark-comparison comment, `profile-jmh-regressions`, `profile-query-bottleneck`), so
  this is incremental per-child work, not new plumbing."
- **Stress scenario**: a child issue (say S1, YTDB-916) needs a *targeted* eval-path
  microbenchmark, not just the LDBC SNB run. Does the existing infrastructure actually carry
  microbenchmark authoring + comparison, or only the LDBC SNB harness?
- **Code evidence**: both halves are present. The LDBC harness is the `jmh-ldbc/` module
  (LDBC SNB IC/IS benchmark bases) wired to `ldbc-jmh-compare.yml` (the PR comparison
  comment) — that is the neutrality net. The *targeted microbenchmark* mechanism is the
  established `@Benchmark`-class-in-`core/src/test` pattern (~15 existing classes spanning
  RID sets, comparators, B-tree gets, key normalizers). So a child adding a `BinaryOpEvalBenchmark`
  or `CollationCompareBenchmark` alongside its slice has direct precedent. The skills
  (`run-jmh-benchmarks-hetzner`, `profile-jmh-regressions`, `profile-query-bottleneck`) exist
  as named.
- **Verdict**: HOLDS. The infrastructure claim is accurate on both the SNB-neutrality and the
  targeted-microbenchmark sides. The "incremental, not new plumbing" characterization is
  correct.

#### Cross-check: D19 does not undermine D17 (it refines it) or the other cleared decisions
- **D17** (S0's `NumericOps` change perf-neutrality basis recorded; runtime measurement
  deferred to S1's LDBC JMH gate): D19 explicitly **refines** D17, generalizing its
  S1-specific deferred-JMH gate into a per-child obligation. No contradiction: D19 adds **no**
  S0/T2 acceptance gate (it leaves S0 on `MathExpressionTest` correctness, exactly as D17
  decided), and D17's deferral of S0's hot-path measurement to S1 is *preserved* — S1 is
  simply now the first instance of the blanket per-child rule rather than a one-off. The
  two-hop dispatch basis D17 records (`operator.apply` → per-constant `apply(Object,Object)`
  → shared widening `apply(Number,Operator,Number)` → typed `operation.apply`) is
  PSI-confirmed: `SQLMathExpression.Operator` has 12 constants and 7 `apply` overloads
  (`apply(Integer,Integer)`, `(Long,Long)`, `(Float,Float)`, `(Double,Double)`,
  `(BigDecimal,BigDecimal)`, `(Object,Object)`, `(Number,Operator,Number)`), so the
  re-dispatch seam D17 (post-A13) names is real and the basis D19 inherits is sound. Clean.
- **D5-R / D5** (whole-enum `NumericOps` lift-and-shift): D19 touches the *verification owner*
  layer (per-child benchmark coverage) D17 already recorded for the `NumericOps` change; it
  does not alter the extraction boundary. Clean.
- **D15 / D16 / D18** (the other DD-batch decisions): D19 is orthogonal — it adds a
  cross-slice benchmark obligation, not a change to collation convergence (D15), the
  fast-path mirror obligation (D16), or the `levelZero` throw boundary (D18). No tension.
- **D1–D14 / D6-R**: unaffected — D19 is an umbrella process commitment, not an IR-shape,
  lowering, or evaluator decision. Clean.

#### Verdict carry-forward: iter3 findings A11–A14 are resolved in the live log text
- **A11 (D15)**: VERIFIED. D15 now reads "deliberate AST behavioral inconsistency the
  analyzed layer unifies — a correctness convergence, not a defect fix" (replacing the flat
  "bug") and "PSI find-usages returns ~12 production callers including `SQLWhereClause` and
  `SecurityEngine`" (replacing "rare `Identifiable`-only caller"). The polymorphic surface is
  PSI-confirmed: `SQLBinaryCondition.evaluate(Identifiable)` overrides
  `SQLBooleanExpression.evaluate(Identifiable)`, which carries 17 direct project references
  (callers dispatch through the supertype, which is why a direct same-name search on the
  override returns 0) — a live, widely-called WHERE/security overload, consistent with the
  "~12 production callers" framing. Fix landed.
- **A12 (D16)**: VERIFIED. D16 now states the in-place comparison "is also the
  parity-preserving seam — it returns `FALLBACK` whenever collation or coercion could change
  the result and falls through to the slow path." Fix landed.
- **A13 (D17)**: VERIFIED. D17 now records the **two-hop** virtual dispatch left intact, the
  shared widening re-dispatch, "No new virtual indirection is introduced," and "the typed
  `apply(...)` overloads either stay on the enum (with `NumericOps` calling back) or move with
  it — a boundary T2 must state." PSI-confirmed (12 constants, 7 `apply` overloads incl. the
  shared widening). Fix landed.
- **A14 (D18)**: VERIFIED. D18 now carries the symmetry cross-reference: "Symmetric with D6-R
  on the in-subset side: the only single-`SQLBaseIdentifier` shapes S0 lowers are the
  single-segment `suffix` column → `Var` (D6-R) and a `suffix` carrying a method-call modifier
  → `FuncCall`; every `levelZero` payload is excluded." Fix landed.

## Findings

### A15 [should-fix]
**Certificate**: Challenge: Decision D19 — blanket per-child JMH coverage vs hot-path-conditional
**Target**: Decision D19
**Challenge**: The blanket-scope choice is the user's, made for rule simplicity, and is not
the target — the hot-path-conditional alternative cannot be a blocker. But D19's rationale
records only one half of the cost it accepts (CCX33 minutes for pure-refactor slices that
"measure nothing new") and omits the **measurement-quality** risk the blanket rule trades
into existence: a per-child JMH authored to satisfy a blanket mandate, when the child has no
new eval path, will measure an arbitrary unchanged path or re-measure an existing one. There
is no artifact in the repo that judges a benchmark's *relevance* — `ldbc-jmh-compare.yml`
reports deltas on whatever runs, and there is no gate that fails a benchmark for exercising
the wrong path. So a vacuous per-child benchmark is indistinguishable from a missing one, and
the blanket rule guarantees only that *a* benchmark exists, not that each child's
functionality is actually measured — the very property D19 is built to deliver. The decision
survives (user's call, real infrastructure, S0 untouched); the rationale should record the
risk and the mitigation it leans on.
**Evidence**: `.github/workflows/ldbc-jmh-compare.yml` posts/updates the PR comparison comment
(`createComment` :183/:557) but judges no benchmark's relevance; the targeted-microbenchmark
mechanism is the ~15-class `@Benchmark`-in-`core/src/test` precedent (RidSetBenchmark,
ComparatorBenchmark, BTreeGetBenchmark, KeyNormalizerBenchmark, …) — so authoring is
incremental, but relevance is unenforced by any gate. D19 inherits D17's PSI-confirmed
two-hop dispatch basis (12 `Operator` constants, 7 `apply` overloads incl. the shared widening).
**Proposed fix**: Add one clause to D19's "Why" (or its rejected-alternatives note): the
blanket rule accepts not only the CCX33 cost but a measurement-quality risk — a per-child
benchmark that measures nothing new is indistinguishable from a missing one, since no gate
judges benchmark relevance. Record the mitigation the rule relies on: each child issue names
which eval path its benchmark exercises (so relevance is asserted in the child, reviewable
there), turning "a benchmark exists" into "the child's new path is the one measured." No
decision change; this completes the recorded cost of the user's rule-simplicity trade.
