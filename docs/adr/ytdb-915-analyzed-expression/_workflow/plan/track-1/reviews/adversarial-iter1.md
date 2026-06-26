<!-- MANIFEST
role: reviewer-adversarial
phase: 3A
track: "Track 1: Substrate + framework"
iteration: 1
verdict: PASS
findings: 4
blockers: 0
index:
  - id: A1
    sev: should-fix
    anchor: "### A1 "
    loc: "track-1.md:179-183 (D9 Risks/Caveats); design.md:369-373 (Transform Edge cases)"
    cert: "Violation scenario: I3 does not reach AnalyzedExprTransform — new variant default-recurses silently"
    basis: psi
  - id: A2
    sev: suggestion
    anchor: "### A2 "
    loc: "track-1.md:140-162 (D8); design.md:363-368"
    cert: "Violation scenario: structural-sharing reference-identity invariant under equal-but-rebuilt child"
    basis: psi
  - id: A3
    sev: suggestion
    anchor: "### A3 "
    loc: "track-1.md:295-301 (Sizing justification); implementation-plan.md:61-70"
    cert: "Challenge: D13 — substrate as its own ~10-file track vs folding into the lowerer"
    basis: reasoning
  - id: A4
    sev: suggestion
    anchor: "### A4 "
    loc: "track-1.md:60-79 (D2); design.md:240-248"
    cert: "Challenge: D2 — static switch dispatch vs the codebase's incumbent accept-visitor idiom"
    basis: psi
evidence_base:
  certificates: 4
  psi_used: true
  project: analyzed-expression-5p7llp6k
  notes: "mcp-steroid reachable; project open + matches working tree. PSI confirmed: CommandExecutionException present (extends CoreException); query.analyzed package ABSENT (greenfield); SQLModifier.methodCall present; 19 sealed types already exist (StorageReadResult, SqlCommandExecutionResult, RecordIdInternal, PropertyTypeInternal, RidFilterDescriptor, SemiJoinDescriptor are hand-authored sealed roots — the 'first sealed-type use' claim is FALSE but is a known Phase-4 reconciliation item, not re-raised here)."
-->

# Adversarial review — Track 1: Substrate + framework (iteration 1)

Verdict: PASS (0 blockers). The track's decisions are immutable Decision Records already vetted by the Phase-0→1 research-log gate, so per the D9 narrowing this review challenges only scope/sizing and invariant violation (cross-track-episode dropped — first track). All four challenges survive the chosen design; one (A1) is a should-fix because the *mitigation* of a known, deliberately-accepted gap is prose-only with no backstop. Nothing forces a replan.

## Findings

### A1 [should-fix]
**Certificate**: Violation scenario — "I3 does not reach `AnalyzedExprTransform`; a new variant default-recurses silently."
**Target**: Invariant I3 / Decision D9.
**Challenge**: The track sells I3 as a compile-time guarantee that adding a sixth `AnalyzedExpr` variant breaks every dispatch site. D9 then carves out `AnalyzedExprTransform`, whose five recurse-into-children defaults make a new variant pass through *silently*. The track and design both concede this and propose a mitigation — "make the audit part of the variant-addition checklist" (track-1.md:182-183; design.md:372-373). That mitigation is the weak point: S0 ships no such checklist artifact, and the transform path has neither a compile-time nor a test-time backstop. A future optimizer slice that adds a `Cast` variant (explicitly contemplated in D4) and a transform pass that must special-case it will compile, pass its own tests, and silently mishandle `Cast` by default-recursing — exactly the failure mode I3 was sold to prevent, in the one path I3 does not cover. The decision (silent recursion is the correct *default* for transform passes) survives; the *rationale's mitigation* does not.
**Evidence**: Construction trace, grounded in PSI (`transformChildren` will be the static helper the defaults call; the base `AnalyzedExprVisitor<T>` is confirmed default-free in the plan, and the design pins the asymmetry at design.md:355-360):
1. Start state: S-later adds variant `Cast(AnalyzedExpr operand, PropertyType target)` to the sealed permits list. `AnalyzedExpr.dispatch`'s `switch` (no `default`) and the evaluator (a base-visitor implementer, no defaults) both fail to compile — I3 fires correctly here.
2. A pre-existing transform pass `FoldConstants extends AnalyzedExprTransform` overrode only `visitBinaryOp`. It inherits the four other defaults, and now inherits a fifth — `visitCast` is **not** a method it ever named, so there is no compile error: `AnalyzedExprTransform` either gains a new `visitCast` default (recurse into `operand`) or, if the author forgets to add it, the pass fails to compile for a *different* reason. The dangerous case is the former: the maintainer dutifully adds the `visitCast` default to `AnalyzedExprTransform` (recurse-into-children, matching the pattern), and every existing pass now silently default-recurses through `Cast`.
3. Violation point: `FoldConstants` runs over a tree containing `Cast(BinaryOp(PLUS, Const 1, Const 2), INTEGER)`. It folds the inner `1+2` to `Const 3` via the inherited `transformChildren` recursion, producing `Cast(Const 3, INTEGER)` — but it never reconsiders whether a folded constant under a `Cast` should itself be coerced at fold time. No exception, no compile error, no failing test.
4. Observable consequence: a correctness bug in an optimizer pass that the I3 "exhaustiveness" story implied was impossible. The bug is silent because the defaults make every variant pass-through-able (design.md:370-372 states exactly this).
**Feasibility**: CONSTRUCTIBLE. D4 already names `Cast` as the most likely future addition, and D8/D9 are explicitly the shape "S3+ optimizer slices will use," so a transform pass that must special-case a new variant is the expected case, not a contrived one.
**Proposed fix**: Strengthen D9's rationale with a *concrete* backstop rather than a prose checklist, and state it in the track so the implementer ships it. Cheapest options, any one suffices: (a) a unit test in this track that asserts the count of `AnalyzedExprTransform` `visitX` methods equals the variant count (a reflective or compile-fixture guard that fails when a variant is added without a transform-default audit); (b) a `// VARIANT-ADDITION CHECKLIST` anchor comment co-located on the sealed `AnalyzedExpr` declaration enumerating the non-compile-enforced sites (the transform defaults), so the audit travels with the type rather than living in a design doc that ships and then is deleted at Phase 4; (c) document in D9 that the checklist is an explicit acceptance item of the *first slice that adds a variant* (S1+), not S0 — i.e., name where the obligation lands. Note this is a rationale/mitigation strengthening, not a decision reversal: keep the silent-recurse default.

### A2 [suggestion]
**Certificate**: Violation scenario — "structural-sharing reference-identity invariant under an equal-but-rebuilt child."
**Target**: Decision D8 (structural sharing) / the substrate unit test in Validation.
**Challenge**: D8's invariant is "return the same instance (`==`) for every unchanged node." The track's own Edge-cases note (track-1.md:158-161; design.md:365-367) admits the failure mode — a transform that rebuilds an `equals`-but-distinct copy of an unchanged node defeats sharing — and the Validation section says the test "asserts reference identity (`assertSame`), not value equality, so it catches a transform that rebuilds an `equals`-but-distinct copy" (track-1.md:251-253). The challenge: the *positive* test (a transform that changes nothing returns the original root by `==`) does not catch the rebuild-an-equal-copy bug, because a correct `transformChildren` already returns the input by reference in the no-change path — the bug lives in a *pass's* `visitX`, not in `transformChildren`. To actually catch it, the test must include a deliberately-misbehaving transform whose `visitX` returns `new Const(c.value())` for an unchanged `Const`, and assert that `transformChildren` on the *parent* then sees `!=` and rebuilds — i.e., the test must prove the sharing is *correctly* defeated (the helper does not paper over the pass's mistake by value-comparing). Without that adversarial transform in the suite, the `assertSame` claim is only half-tested.
**Evidence**: PSI confirms `transformChildren` is a new static helper with no existing analogue to lean on; the only guard against the value-vs-reference confusion is the test this track writes. The design's worked example (design.md:336-344) exercises the *correct* path only.
**Feasibility**: THEORETICAL for a data-corruption consequence (S0 has no live transform consumer — D8 Risks/Caveats, track-1.md:160-161 — so the only victim is a future pass's allocation behavior, a performance regression not a correctness one). CONSTRUCTIBLE as a test-coverage gap.
**Proposed fix**: Add to the structural-sharing unit test a "misbehaving transform" case: a transform whose `visitConst` returns a fresh equal `Const`, asserting `transformChildren` on its parent returns a *new* parent (`!=` input) and does **not** collapse the change by value-equality. This pins the reference-identity semantics from both sides and documents the D8 author rule as executable.

### A3 [suggestion]
**Certificate**: Challenge — "D13: substrate as its own ~10-file track vs folding into the lowerer."
**Target**: Decision D13 (track split) / sizing justification (track-1.md:295-301).
**Chosen approach**: Ship the data-only substrate (sealed IR + visitor/transform + exception, ~10 files) as its own track, near the ~12-file merge floor, justified as a clean dependency boundary and a distinct review surface from the AST-reading lowerer.
**Best rejected alternative**: Fold Track 1 into Track 3 (lowering), since Track 3 is the first and only in-track consumer of the IR types and is itself only ~4 files — a combined ~14-file PR still sits well under the ~20-25 split ceiling, and a reviewer of the lowerer needs the IR types in front of them anyway.
**Counterargument trace**:
1. In the stacked-diff series, a reviewer of Track 1 alone reviews value types and a dispatch helper with **no caller** — the substrate has no live consumer in S0 (track-1.md:14-15), so the reviewer cannot see the types exercised against real AST input until Track 3 lands.
2. The folded alternative would let the reviewer see each IR type immediately used by a lowering case, which is arguably a *richer* review surface, not a muddier one.
3. This produces a concrete difference: Track 1 in isolation is reviewable only against its own unit test (dispatch-exhaustiveness + structural-sharing), never against production use.
**Codebase evidence**: PSI confirms Track 1's footprint is genuinely self-contained — the two static helpers reference only the five variants and two visitor interfaces (track-1.md:205-207), and `query.analyzed` is absent on develop, so there is no pre-existing consumer forcing the split. The merge-floor rule (`planning.md` §Track descriptions: a ≤~12-file track that folds into a neighbor is a *merge candidate*) actively points the other way.
**Survival test**: WEAK→survives. The split survives because Track 4 (evaluator) *also* depends on Track 1, so folding Track 1 into Track 3 would force Track 4 to depend on the whole lowering PR for types it could otherwise take from a thin substrate PR — the two-consumer fan-out (T3 and T4 both depend on T1, per implementation-plan.md:89,99) is the real justification, and it is stronger than the "distinct review surface" reason the track actually writes. The decision holds; the *stated* rationale under-sells it.
**Proposed fix**: Replace the sizing justification's lead reason ("would mix two distinct review surfaces") with the two-consumer fan-out: Track 3 and Track 4 both depend on Track 1, so a thin substrate PR lets the evaluator track build on types without inheriting the lowerer's diff. This is the argument that actually defeats the merge-floor pull.

### A4 [suggestion]
**Certificate**: Challenge — "D2: static `switch` dispatch vs the codebase's incumbent accept-visitor idiom."
**Target**: Decision D2 (visitor as interface; static `switch` dispatch, no `accept`).
**Chosen approach**: One `AnalyzedExprVisitor<T>` interface with one `visitX` per variant; a single static `AnalyzedExpr.dispatch(expr, visitor)` carries the one `switch`; nodes carry no `accept(...)`.
**Best rejected alternative**: `accept(visitor)` on each node (the classic Visitor), which the codebase's incumbent node-tree idiom (`SQLBooleanExpression` + 21 subclasses, confirmed 21 inheritors via PSI) already uses pervasively, so maintainers know it.
**Counterargument trace**:
1. The chosen approach justifies itself on a JIT argument: a sealed `switch` lowers to a table jump and keeps each `visitX` monomorphic, whereas a megamorphic `accept` stays an indirect call (design.md:243-248).
2. The rejected alternative is the idiom every other node tree in the codebase uses, so it has zero learning cost; the chosen approach concedes it is "the first sealed-type use" and needs a "one-paragraph orientation" (track-1.md:53).
3. This produces a difference only if the megamorphic-call cost is real *in S0*. It is not: S0 has no optimizer pipeline and no live consumer — the only walker is the evaluator (Track 4) and a unit test. The performance rationale is entirely forward-looking to S3+ passes that do not exist yet.
**Codebase evidence**: PSI confirms 19 sealed types already exist (`StorageReadResult`, `SqlCommandExecutionResult`, `RecordIdInternal`, `PropertyTypeInternal`, `RidFilterDescriptor`, `SemiJoinDescriptor` are hand-authored sealed roots), so the "first sealed-type use" framing is factually wrong (a known Phase-4 reconciliation item per the sibling reviews; not re-raised). The relevant adversarial point is different: those existing sealed types are *result/descriptor* types, none of them is a visitor-dispatched node tree, so none of them provides reusable visitor infrastructure the rejected `accept` alternative could lean on. The incumbent node-tree visitor pattern is the `accept`-style one on `SQLBooleanExpression`.
**Survival test**: YES — survives strongly. The megamorphic-vs-monomorphic argument is sound for the *intended* S3+ use even though it is unmeasurable in S0, and committing to the sealed-`switch` shape now avoids a later API break when passes proliferate. The "first sealed-type use" framing should be corrected (Phase-4 item) but the decision is right.
**Proposed fix**: None to the decision. Optionally, when the "first sealed-type use" line is corrected in Phase 4, reframe D1/D2's novelty claim as "first sealed *node-tree / visitor-dispatched* IR" rather than "first sealed type," which is both true and the actually-load-bearing distinction.

## Evidence base

#### Violation scenario: I3 does not reach AnalyzedExprTransform — new variant default-recurses silently
- **Invariant claim**: Adding a sixth `AnalyzedExpr` variant is a compile-time break across `dispatch`'s sealed `switch` (no `default`) and every base-`AnalyzedExprVisitor<T>` implementer (no defaults) — invariant I3 (track-1.md:305-311).
- **Violation construction**:
  1. Start state: a later slice adds `Cast` to the sealed permits list (D4 explicitly contemplates this).
  2. Action sequence: `dispatch`'s `switch` and the evaluator break at compile time (I3 fires). The maintainer adds a recurse-into-children `visitCast` default to `AnalyzedExprTransform` to match D9's pattern (track-1.md:170-178). Every existing transform pass (which overrode only its own `visitX`) now inherits silent pass-through for `Cast`.
  3. Intermediate state: a pass that *should* special-case `Cast` compiles cleanly and default-recurses.
  4. Violation point: `AnalyzedExprTransform`'s defaults (design.md:355-360, 370-372) — the one place D9 states the I3 guarantee does not reach.
  5. Observable consequence: a silent optimizer-pass correctness bug, no compile error, no failing test.
- **Feasibility**: CONSTRUCTIBLE — `Cast` is the named likely addition (D4); transform passes that special-case a variant are the expected S3+ use (D8/D9).

#### Violation scenario: structural-sharing reference-identity invariant under equal-but-rebuilt child
- **Invariant claim**: `transformChildren` returns the same instance (`==`) for every unchanged node; a no-change transform returns the original root by reference (D8, track-1.md:149-155).
- **Violation construction**:
  1. Start state: a transform pass whose `visitConst` returns `new Const(c.value())` (an `equals`-but-distinct copy) for an unchanged `Const`.
  2. Action sequence: `transformChildren` on the parent compares children by reference identity (`==`, not `equals`), so it sees the rebuilt `Const` as changed and allocates a new parent — sharing is correctly defeated.
  3. Intermediate state: extra allocation on a path that should have shared.
  4. Violation point: the *pass*'s `visitConst`, not `transformChildren`. The substrate's positive no-change test (returns original root by `==`) never exercises this.
  5. Observable consequence: a performance regression (lost sharing), not data corruption — S0 has no live transform consumer (track-1.md:160-161).
- **Feasibility**: THEORETICAL for a correctness consequence; CONSTRUCTIBLE as a test-coverage gap in the substrate unit test.

#### Challenge: D13 — substrate as its own ~10-file track vs folding into the lowerer
- **Chosen approach**: ~10-file standalone substrate track at the merge floor, justified as a clean dependency boundary / distinct review surface.
- **Best rejected alternative**: fold into Track 3 (lowering, ~4 files) — combined ~14 files, under the split ceiling, and the lowerer is the first IR consumer.
- **Counterargument trace**: (1) Track 1 alone is reviewable only against its own unit test — no live consumer in S0 (track-1.md:14-15); (2) folding would show each IR type used by a lowering case; (3) difference: substrate reviewed in isolation vs in-use.
- **Codebase evidence**: PSI confirms the substrate is self-contained (helpers reference only the five variants + two interfaces, track-1.md:205-207) and `query.analyzed` is absent on develop. The merge-floor rule points toward folding.
- **Survival test**: WEAK→survives. The real defense is the two-consumer fan-out (T3 and T4 both depend on T1, implementation-plan.md:89,99), stronger than the "distinct review surface" reason actually written.

#### Challenge: D2 — static switch dispatch vs the codebase's incumbent accept-visitor idiom
- **Chosen approach**: interface `AnalyzedExprVisitor<T>` + one static `dispatch` `switch`, no `accept` on nodes.
- **Best rejected alternative**: `accept(visitor)` on each node — the incumbent idiom (`SQLBooleanExpression` + 21 subclasses, PSI-confirmed 21 inheritors), zero learning cost.
- **Counterargument trace**: (1) the chosen approach rests on a JIT table-jump/monomorphic argument (design.md:243-248); (2) the rejected idiom is what every other node tree uses; (3) the perf difference is unmeasurable in S0 — no pipeline, only the evaluator and a test walk the IR.
- **Codebase evidence**: PSI confirms 19 sealed types exist but none is a visitor-dispatched node tree, so no reusable visitor infra backs the `accept` alternative; the incumbent visitor pattern is `accept`-style on `SQLBooleanExpression`. "First sealed-type use" is factually wrong (known Phase-4 item).
- **Survival test**: YES — the forward-looking perf rationale is sound and committing to the shape now avoids a later API break.
