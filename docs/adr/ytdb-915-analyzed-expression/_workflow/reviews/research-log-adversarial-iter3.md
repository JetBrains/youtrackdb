<!-- Adversarial research-log review — iteration 3 (re-run). Verdict-producer
     manifest variant per conventions-execution.md §2.5. Scope: the four
     DD-review-batch decisions D15–D18 only, plus any way they undermine a
     previously-cleared decision. The iter1 (NEEDS REVISION) and iter2 (PASS)
     verdicts on D1–D14/D5-R/D6-R stand and are not re-litigated. -->

# Research-log adversarial review — iteration 3

```yaml
review_type: adversarial
scope: research-log (Phase 0→1 gate)
iteration: 3
variant: verdict-producer
target: docs/adr/ytdb-915-analyzed-expression/_workflow/research-log.md
in_scope_decisions: [D15, D16, D17, D18]
lenses: [Architecture / cross-component coordination, Performance hot path]
verdict: PASS
findings: 4
prior_verdicts:
  iter1: NEEDS REVISION (resolved)
  iter2: PASS
index:
  - id: A11
    sev: should-fix
    anchor: "### A11 "
    title: "D15 'it is a bug' framing overstates a deliberate fast-path design and understates the live Identifiable-path blast radius"
    loc: research-log.md §D15
    cert: "Challenge: Decision D15"
    basis: "SQLBinaryCondition.java:44-67,79-97; PSI: 12 prod callers of evaluate(Identifiable)"
  - id: A12
    sev: suggestion
    anchor: "### A12 "
    title: "D16 attributes tryInPlaceComparison wholly to perf; the live code shows it is also the collation-correctness fallback seam"
    loc: research-log.md §D16
    cert: "Assumption test: D16 fast-path-is-pure-perf"
    basis: "SQLBinaryCondition.java:79-97,119-149"
  - id: A13
    sev: should-fix
    anchor: "### A13 "
    title: "D17 inlining basis omits the shared-widening re-dispatch seam; the lift-and-shift must keep operation.apply re-dispatch intact"
    loc: research-log.md §D17
    cert: "Challenge: Decision D17"
    basis: "SQLMathExpression.java:568-655,787-829"
  - id: A14
    sev: suggestion
    anchor: "### A14 "
    title: "D18 boundary is complete for levelZero, but the symmetric in-subset claim (single-segment suffix only) deserves one explicit line"
    loc: research-log.md §D18
    cert: "Assumption test: D18 boundary-completeness"
    basis: "SQLLevelZeroIdentifier.java:21-23,88-102; SQLBaseIdentifier.java:24-26,388-390"
evidence_base:
  - "Challenge: Decision D15 — Identifiable collation skip is a bug"
  - "Assumption test: D16 fast-path-is-pure-perf"
  - "Challenge: Decision D17 — defer perf measurement to S1"
  - "Assumption test: D18 boundary-completeness"
  - "Cross-check: D15–D18 do not undermine D3/D6-R/D11/D14 (no finding)"
```

## Verdict

**PASS.** The four DD-review-batch decisions (D15–D18) survive adversarial challenge.
Every code-grounded claim they make is true against the live source. Two
`should-fix` findings ask for rationale-precision tightening (D15's "bug" framing
overstates a deliberate design and understates blast radius; D17's inlining basis
skips a re-dispatch seam the lift-and-shift must preserve); two `suggestion`
findings note small attribution/symmetry gaps. None forces a re-decision, and none
undermines a previously-cleared decision (D3, D6-R, D11, D14 cross-checked clean).
The gate clears.

## Evidence base

#### Challenge: Decision D15 — AST `evaluate(Identifiable)` collation skip is a pre-existing bug
- **Chosen approach**: D15 records the `evaluate(Identifiable, ctx)` collation skip as
  a *bug* (not a context limitation), and commits S1+ to fix it by routing the rare
  `Identifiable` caller through a synthetic `Result` so the IR's single collation-
  applying overload (D3) runs. The divergence from AST `Identifiable`-path behavior is
  recorded against I1.
- **Best rejected alternative**: keep the divergence but frame it as a *deliberate
  behavioral inconsistency in the AST that the IR chooses not to replicate*, rather
  than a "bug" — and quantify the blast radius before committing S1+ to change it.
- **Counterargument trace**:
  1. D15's core code claim is **true**: `SQLBinaryCondition.evaluate(Identifiable, ctx)`
     (`SQLBinaryCondition.java:44-67`) reaches `operator.execute(...)` on both the
     fast-path fallback (`:60-62`) and the no-fast-path branch (`:65-66`) with **no**
     `getCollate`/`transform`, whereas `evaluate(Result, ctx)` applies it (`:99-109`).
     The "schema context is recoverable from a loaded `Identifiable`" claim is also
     true: `SQLSuffixIdentifier.getCollate` needs only `isEntity()`/`asEntity()` →
     `getImmutableSchemaClass` → `getProperty` → `getCollate`
     (`SQLSuffixIdentifier.java:505-521`), and an `EntityImpl` supplies all of it.
  2. But the live code carries **two author comments asserting the skip is
     intentional**, not an oversight: `:45-46` "No collation guard — the existing
     overload never applies collation," and `:79-82` on the `Result` fast path
     "Collation is checked inside `EntityImpl` (both serialized and deserialized paths
     return FALLBACK for non-default collation), so no `getCollate` guard needed here."
     A maintainer reading the log's flat "it's a bug" against these comments sees a
     direct contradiction with no reconciliation. The honest framing is "a deliberate
     fast-path design whose collation semantics the analyzed layer deliberately
     unifies," which is a *divergence decision*, not a defect report.
  3. The "rare `Identifiable`-only caller" characterization (carried from D3 into D15)
     **understates the blast radius**. PSI find-usages on develop (byte-identical SQL
     parser at the branch tip per the log header) returns **12 production references**
     to `evaluate(Identifiable, ctx)` across `SQLWhereClause.java`, `SecurityEngine.java`,
     `SQLNotBlock`/`SQLAndBlock`/`SQLOrBlock`/`SQLCaseExpression`/`SQLParenthesisBlock`/
     `SQLContainsCondition`/`SQLExpression`. The `Identifiable` overload is a live,
     security-and-WHERE-path overload, not a rarity. So when S1+ wraps such a caller in
     a synthetic `Result`, it **changes observable behavior** for collated-property
     comparisons on those paths — a real production change, correctly recorded against
     I1, but larger than "rare."
- **Codebase evidence**: `SQLBinaryCondition.java:44-67` (skip), `:99-109` (apply),
  `:45-46`/`:79-82` (intentional-skip comments); `SQLSuffixIdentifier.java:505-521`
  (single-property collate resolution); PSI: 12 prod callers of `evaluate(Identifiable)`
  vs 18 of `evaluate(Result)`.
- **Survival test**: WEAK. The decision (diverge, record against I1, defer to S1+)
  survives — S0 is genuinely unaffected (its round-trip tests use `Result` rows, which
  apply collation). But the *rationale* needs strengthening: drop the unqualified "bug"
  in favor of "deliberate AST fast-path inconsistency the analyzed layer unifies," and
  replace "rare `Identifiable`-only caller" with the real (12-caller, security/WHERE)
  surface so the S1+ implementer sizes the divergence correctly.

#### Assumption test: D16 fast-path-is-pure-perf
- **Claim**: D16 treats `tryInPlaceComparison` as "an AST-internal *optimization* the
  IR need not mirror" for S0, and records an S1+ obligation to reproduce (a) the
  in-place comparison and (b) AND/OR short-circuit, calling the latter "correctness as
  well as performance."
- **Stress scenario**: a collated-property comparison (`name = 'foo'` against a `ci`
  string property) reaching the `Result` fast path; and a `BinaryOp` whose right operand
  throws, sitting behind a decisive AND/OR operand.
- **Code evidence**:
  1. AND/OR short-circuit is confirmed and the correctness framing is **exactly right**:
     `SQLAndBlock.evaluate` returns `false` on the first false subblock
     (`SQLAndBlock.java:41-46`); `SQLOrBlock.evaluate` returns `true` on the first true
     subblock (`SQLOrBlock.java:43-48`). Eager evaluation of all subblocks could throw
     where the AST stops early — D16's "correctness as well as performance" holds.
  2. The in-place fast path is **not purely an internal perf trick the IR can ignore for
     correctness** even at the slow-path boundary: the live `Result` fast path
     (`SQLBinaryCondition.java:79-97`) returns and falls through to the slow path
     precisely when `tryInPlaceComparison` yields `FALLBACK`, and the comment
     (`:80-82`) states the FALLBACK fires for non-default collation. So the fast path is
     **parity-equivalent because it defers to the collation-applying slow path** — it is
     the seam that *guarantees* the slow path (the D11/A10 parity reference) still runs
     for collated columns. D16 is right that S0 need not mirror it, but the framing
     "AST-internal optimization" undersells that the fast path is wired to be
     parity-preserving by construction (it is not a behavior the IR omits and then has to
     re-derive — the slow path already is the behavior).
- **Verdict**: HOLDS. The S0-only scoping is correct, the perf-vs-correctness split for
  AND/OR is correctly attributed, and the S1+ obligation is the right thing to record.
  The one imprecision (calling the in-place path purely an "optimization" without noting
  it is the parity-preserving FALLBACK seam) is cosmetic and consistent with D11's
  precision note; suggestion-grade.

#### Challenge: Decision D17 — defer perf measurement to S1's LDBC JMH gate
- **Chosen approach**: D17 records a perf-neutrality *basis* for the `NumericOps`
  whole-enum extraction — "the megamorphic `operator.apply` virtual dispatch over the
  enum constants is unchanged, and the added `invokestatic NumericOps.*` inside each
  constant body is a monomorphic static call the JIT inlines" — and defers runtime
  measurement to S1's existing LDBC JMH gate rather than a standalone S0 Hetzner run.
- **Best rejected alternative**: the log itself lists "add a standalone LDBC JMH run as
  an S0/T2 acceptance gate" — rejected as heavyweight. The stronger adversarial line is
  not to add the gate but to **tighten the inlining basis**, because as stated it
  misdescribes the dispatch surface it claims is unchanged.
- **Counterargument trace**:
  1. The hot call site in `iterateOnPriorities` is `operator.apply(left, right)`
     (`SQLMathExpression.java:811,822`) — a virtual call resolving to the per-constant
     `apply(Object, Object)`. That much matches D17.
  2. But the constant bodies' `apply(Object, Object)` delegate to the **shared widening
     helper** `apply(Number, Operator, Number)` (`:568-580` → `:582-655`), and that
     helper **re-dispatches virtually** through its `operation` parameter:
     `operation.apply(a.intValue(), b.intValue())` etc. (`:589,591,...`). So the
     hot-path dispatch is two virtual hops (constant `apply(Object,Object)` → shared
     widening → typed `operation.apply(...)`), not the single "megamorphic
     `operator.apply`" hop D17 names. The whole-enum lift-and-shift moves the shared
     widening helper into `NumericOps`, which means the static `NumericOps` widening
     method must **still call back into the enum's typed `operation.apply(...)`
     overloads** (or those typed overloads move too — a larger surface). D17's
     one-line basis does not address this re-dispatch seam, so it cannot be taken as a
     complete neutrality argument: the question "does the extra `NumericOps` ↔ enum
     round-trip stay inlinable" is exactly the one a perf reviewer would ask, and the
     basis as written skips it.
  3. The conclusion (defer measurement to S1) still survives: `invokestatic` is
     monomorphic and small static delegators inline reliably; S0 has no IR consumer to
     measure; the existing `MathExpressionTest` is the correctness gate; YTDB-901's
     JMH-neutrality mandate is satisfied at S1's gate, the first slice with a live hot
     consumer. The deferral is reasonable.
- **Codebase evidence**: `SQLMathExpression.java:568-580` (per-constant `apply(Object,
  Object)` → shared widening), `:582-655` (shared widening re-dispatches via
  `operation.apply(typed,typed)`), `:787-829` (`iterateOnPriorities` hot loop calling
  `operator.apply`).
- **Survival test**: WEAK. The decision (record a basis, defer measurement to S1)
  survives. The *basis* needs one more clause: the lift-and-shift preserves the existing
  two-hop virtual dispatch (`operator.apply` → typed `operation.apply`) and adds only a
  monomorphic `NumericOps` delegation around the shared widening, so no *new* virtual
  indirection is introduced. State that, and the neutrality claim is defensible rather
  than glossed.

#### Assumption test: D18 boundary-completeness
- **Claim**: D18 pins the `SQLBaseIdentifier.levelZero` form — `functionCall` (incl.
  `any()`/`all()`), `self` (`@this`), `collection` (`[..]`) — as out of the S0 subset
  (throws via the D14 default), and states `FuncCall` comes only from method-call
  modifiers (`SQLModifier.methodCall`).
- **Stress scenario**: could a covered shape be wrongly excluded, or an excluded shape
  silently slip through as a `FuncCall` the comparison evaluator mis-handles (the
  `BinaryOp(EQ, FuncCall(any), …)` parity hole D18 is built to close)?
- **Code evidence**:
  1. The `levelZero` payload enumeration is **complete and exact**:
     `SQLLevelZeroIdentifier` declares exactly `functionCall`, `self`, `collection`
     (`SQLLevelZeroIdentifier.java:21-23`); there is no fourth payload. `any()`/`all()`
     are `functionCall` with an empty param list (`isFunctionAny`/`isFunctionAll`,
     `:88-102`). So D18's "top-level function calls incl. `any()`/`all()`, `@this`,
     collections" exhausts the form.
  2. The throw-by-default is real: `SQLBaseIdentifier` holds `levelZero | suffix`
     (`SQLBaseIdentifier.java:24-26`); a `levelZero` identifier has `suffix == null`, so
     `Var`'s single-segment `suffix` walk does not produce a `Var` for it, and
     `getCollate` returns `null` for it (`:388-390`) — consistent with the lowerer
     hitting the D14 exhaustive-or-throw default. No `levelZero.functionCall` can leak
     into `FuncCall` via the `Var`/method-call path.
  3. The asymmetry: D18 nails the *excluded* side (levelZero throws) but states the
     *included* `FuncCall` source only as "method-call modifiers." The symmetric
     in-subset fact — that `Var` and the method-call `FuncCall` come **only** from the
     single-segment `suffix` column shape (refined by D6-R) — is implied across D6-R +
     D18 but never stated in one place. A reader confirming "is every `FuncCall` source
     accounted for" must stitch D6-R (single-segment suffix) and D18 (levelZero throws)
     together. One sentence in D18 cross-referencing D6-R for the in-subset side would
     make the boundary self-contained.
- **Verdict**: HOLDS. The boundary is correct and complete; the only gap is a
  one-sentence symmetry cross-reference, suggestion-grade.

#### Cross-check: D15–D18 do not undermine the previously-cleared decisions
- **D3** (single `Result` overload): D15 *reinforces* it (the `Result` overload is now
  the collation-correct path, not merely the convenient one). The only tension —
  "rare `Identifiable` caller" — is the imprecision flagged in A11, not a contradiction
  of D3's mechanism. No finding beyond A11.
- **D11** (IR comparison replicates the slow path): D15/D16 are consistent with D11's
  A10 precision note (slow path is the parity reference; the fast path is parity-
  equivalent via FALLBACK). Confirmed against `SQLBinaryCondition.java:79-109`. Clean.
- **D6-R** (single-segment `Var`, single-property collate fetch): D18's levelZero-throw
  and D6-R's multi-segment-throw are the same exhaustive-or-throw spine (D14), mutually
  reinforcing. `SQLBaseExpression.getCollate` (`:359-392`) confirms the multi-segment
  path is a runtime link traversal D6-R rightly defers. Clean.
- **D14** (exhaustive-or-throw field-walk): D18 explicitly leans on it and states the
  boundary the throw-default would otherwise leave implicit — a strengthening, not a
  contradiction. Clean.

## Findings

### A11 [should-fix]
**Certificate**: Challenge: Decision D15 — Identifiable collation skip is a bug
**Target**: Decision D15 (and the "rare `Identifiable` caller" wording carried from D3)
**Challenge**: D15's code claims are all true (the `Identifiable` overload skips
collation; the schema context is recoverable from a loaded `EntityImpl`), so the
*divergence-as-fix* decision is sound and S0 is genuinely unaffected. But two
rationale points overstate/understate: (1) the flat "it is a **bug**" contradicts the
live author comments at `SQLBinaryCondition.java:45-46` and `:79-82`, which assert the
skip is intentional fast-path design — the honest framing is "a deliberate AST
behavioral inconsistency the analyzed layer unifies," a divergence decision, not a
defect report; (2) "rare `Identifiable`-only caller" understates the surface — PSI
find-usages returns **12 production references** to `evaluate(Identifiable)` including
`SQLWhereClause` and `SecurityEngine`, so the S1+ divergence changes observable
collated-comparison behavior on live security/WHERE paths.
**Evidence**: `SQLBinaryCondition.java:44-67` (skip), `:99-109` (apply), `:45-46`/`:79-82`
(intentional-skip comments); `SQLSuffixIdentifier.java:505-521` (recoverable schema
context); PSI: 12 prod callers of `evaluate(Identifiable)` vs 18 of `evaluate(Result)`.
**Proposed fix**: Reword D15: replace the unqualified "bug" with "deliberate AST
fast-path inconsistency the analyzed layer unifies (the AST authors guard it
intentionally — see the inline comments — so the analyzed layer's uniform collation is
a *correctness convergence*, not a defect fix)." Replace "rare `Identifiable`-only
caller" (here and in the D3 cross-reference) with the real surface ("a live overload
with ~12 production callers incl. WHERE and security evaluation"), so the S1+ implementer
sizes the recorded I1 divergence correctly. No decision change; rationale precision only.

### A12 [suggestion]
**Certificate**: Assumption test: D16 fast-path-is-pure-perf
**Target**: Decision D16
**Challenge**: D16's S0-only scoping is correct and its AND/OR "correctness as well as
performance" framing is exactly right (short-circuit confirmed at `SQLAndBlock.java:41-46`
and `SQLOrBlock.java:43-48`; eager evaluation can throw where the AST stops). The one
imprecision: D16 calls `tryInPlaceComparison` "an AST-internal optimization," but the
live `Result` path (`SQLBinaryCondition.java:79-97`) shows the fast path is wired to
return `FALLBACK` and fall through to the collation-applying slow path for collated
properties — it is the parity-preserving seam, not a behavior the IR omits and must
re-derive. This is consistent with D11's A10 precision note but worth one clause.
**Evidence**: `SQLBinaryCondition.java:79-97` (FALLBACK → slow path), `:119-149`
(`tryInPlaceComparison` returns null/FALLBACK for non-handled cases).
**Proposed fix**: In D16, add a half-sentence noting the in-place path is parity-
preserving by deferring to the slow path on FALLBACK (cross-ref D11's A10 note), so the
"need not mirror" claim is grounded in *why* it is safe to skip, not just *that* it is.

### A13 [should-fix]
**Certificate**: Challenge: Decision D17 — defer perf measurement to S1
**Target**: Decision D17
**Challenge**: The deferral decision survives, but the recorded perf-neutrality *basis*
misdescribes the dispatch surface it claims is unchanged. The hot path is **two virtual
hops** — `operator.apply(left, right)` (`SQLMathExpression.java:811,822`) →
per-constant `apply(Object, Object)` (`:568-580`) → shared widening
`apply(Number, Operator, Number)` (`:582-655`) which **re-dispatches virtually** via
`operation.apply(a.intValue(), b.intValue())` (`:589` ff.). The whole-enum lift-and-shift
moves the shared widening into `NumericOps`, so the static helper must keep calling back
into the enum's typed `operation.apply(...)` overloads. D17's one-liner ("megamorphic
`operator.apply` ... unchanged; added `invokestatic` ... inlines") skips this re-dispatch
seam — precisely the seam a perf reviewer interrogates.
**Evidence**: `SQLMathExpression.java:568-580` (constant body → widening), `:582-655`
(widening re-dispatches via `operation.apply(typed,typed)`), `:787-829`
(`iterateOnPriorities` hot loop).
**Proposed fix**: Extend D17's basis with one clause: the lift-and-shift preserves the
existing two-hop virtual dispatch (`operator.apply` → typed `operation.apply`) and adds
only a monomorphic `NumericOps` delegation around the shared widening, introducing **no
new virtual indirection**; the typed `apply(...)` overloads either stay on the enum
(`NumericOps` calls back) or move with it, a boundary T2 must state. Then the deferral to
S1's JMH gate rests on a complete basis.

### A14 [suggestion]
**Certificate**: Assumption test: D18 boundary-completeness
**Target**: Decision D18
**Challenge**: D18's excluded-side boundary is complete and exact — `SQLLevelZeroIdentifier`
has exactly `{functionCall, self, collection}` (`SQLLevelZeroIdentifier.java:21-23`),
`any()`/`all()` are param-less `functionCall` (`:88-102`), and all hit the D14 throw-
default because a `levelZero` identifier has `suffix == null` (`SQLBaseIdentifier.java:24-26`).
The gap is symmetry: D18 states the *excluded* side crisply but leaves the *included*
`FuncCall` source as "method-call modifiers" without restating that `Var` and method-call
`FuncCall` come only from the single-segment `suffix` column shape (D6-R). A reader
verifying "every `FuncCall` source is accounted for" must stitch D6-R and D18 together.
**Evidence**: `SQLLevelZeroIdentifier.java:21-23,88-102`; `SQLBaseIdentifier.java:24-26`
(levelZero|suffix), `:388-390` (`getCollate` null for levelZero).
**Proposed fix**: Add one sentence to D18 cross-referencing D6-R for the in-subset side:
"the in-subset identifier source is the single-segment `suffix` column shape only (D6-R);
the `levelZero` form throws," making the `FuncCall`-source boundary self-contained.
