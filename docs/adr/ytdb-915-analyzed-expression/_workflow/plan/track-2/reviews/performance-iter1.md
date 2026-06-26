<!--MANIFEST
review: performance
track: 2
step: 1
iter: 1
range: 09389fdc16ab40554392ef5e431d176accdda3e7~1..09389fdc16ab40554392ef5e431d176accdda3e7
verdict: PASS
blocker_count: 0
finding_count: 0
findings_under_recheck: 0
evidence_base: present
cert_index: [C1, C2, C3, C4, C5, C6]
flags: []
index: []
-->

# Performance Review — Track 2, Step 1 (iter 1)

## Findings

No performance findings. The lift-and-shift meets D17's perf-neutrality contract and, on the
one dimension where the dispatch shape genuinely changed, moves it in the favorable direction.

`Operator.apply(Object, Object)` is on the live AST arithmetic hot path — PSI-confirmed
callers are `SQLMathExpression.execute` (the two-child fast path, `:537`/`:558`),
`calculateWithOpPriority`, and `iterateOnPriorities`, the per-evaluation precedence walk that
folds the flat operator list once per arithmetic evaluation (C1). The extraction preserves
the entry dispatch exactly: the hot-path call site is still
`operator.apply(left, right)` / `operatorsStack.poll().apply(left, right)` — one virtual
dispatch on the enum constant, unchanged. Inside the per-constant delegator, every call now
resolves to a `static` method on `NumericOps`; PSI confirms `NumericOps` is `final` with zero
inheritors, a private constructor, and all 13 methods static, so each delegation is a static
monomorphic target the JIT inlines — no new virtual indirection enters the path (C2, C3).

The one place the dispatch shape changed is favorable, not neutral. Before the lift, the
widening entry `apply(Number, Operator, Number)` re-dispatched to the typed overloads with a
*virtual* call on the enum constant (`operation.apply(a.intValue(), b.intValue())` — one of
12 constants, each with five typed overrides: a potentially megamorphic site). After the lift
that becomes a `static` call into `NumericOps.apply(Operator, Integer, Integer)` with an
internal `switch (op)` — PSI confirms all five typed-pair calls inside the widening entry now
resolve to `static NumericOps.apply(Operator, …)` overloads (C3). A static call plus a
`tableswitch` replaces a virtual megamorphic dispatch: same or fewer cycles, more inlineable
(C4).

No new per-evaluation allocation enters the common path. The typed-pair `switch` arms return
the same primitive arithmetic results the old typed overrides did, autoboxed identically into
the `Number` return; `NULL_COALESCING` returns an already-boxed operand. The only allocations
are the `BigDecimal` conversions in the mixed-`BigDecimal` widening arms, which existed
verbatim before the lift (`new BigDecimal(a.intValue())` is allocation-equivalent to the old
`new BigDecimal((Integer) a)`) and fire only when one operand is already a `BigDecimal` — off
the common Integer/Long/Double path (C5). The object-level `+`/`-`/`^`/`|` bodies
(`plusObject`, `minusObject`, `xorObject`, `bitOrObject`) are byte-for-byte the old enum
bodies relocated, with the same `String.valueOf(left) + right` concatenation and the same
`new Date(...)` allocation on the Date branch — no behavioral or allocation change (C6).

Lock-contention, cache-efficiency, direct-memory, I/O, index-operation, and batch categories
have no target in this slice: it is a pure refactor of in-VM arithmetic promotion with no
shared mutable state, no I/O, and no storage interaction. They are omitted.

## Evidence base

Phase-4 scale-validation roster. A claim whose verdict survived the refutation check
(CONFIRMED-as-sound for a contract the slice meets, or CONFIRMED for a premise) is compressed
to one line; a refuted or negligible-verdict claim appears in full. No claim was refuted, and
no claim produced a MATTERS-NOW / MATTERS-AT-SCALE finding, so the review carries zero
findings.

#### C1: `Operator.apply(Object, Object)` is on the per-evaluation hot path (call-frequency premise)
- **Verdict:** CONFIRMED — PSI find-usages of the base `Operator.apply(Object, Object)` reports the production callers `SQLMathExpression.execute` (twice — the two-child fast path), `calculateWithOpPriority` (twice), and `iterateOnPriorities` (twice); `iterateOnPriorities`/`calculateWithOpPriority` are the precedence walk run once per AST arithmetic evaluation. The remaining callers are `MathExpressionTest`. Hot-path classification holds; the perf contract is load-bearing.

#### C2: Entry dispatch shape preserved; `NumericOps` is a monomorphic static target
- **Verdict:** CONFIRMED-as-sound — the hot-path call site (`operators.getFirst().apply(left, right)` at `:537`/`:558`, `operatorsStack.poll().apply(left, right)` at `:582`) is unchanged: one virtual dispatch on the enum constant. PSI confirms `NumericOps` is `final`, 0 inheritors, private constructor, all 13 methods `static`, so every delegation from a constant body into `NumericOps` is a statically resolved monomorphic call (JIT-inlinable). No new virtual indirection on the path — the D17 invariant ("No new virtual indirection on the hot path") holds.

#### C3: Every enum-constant delegator calls only static `NumericOps` methods
- **Verdict:** CONFIRMED-as-sound — PSI walk of all 12 enum-constant initializing bodies found zero non-`NumericOps` method calls in the delegators; the former `super.apply(...)` callbacks (9 constants) now resolve to `NumericOps.applyObject(this, …)` and the object-level bodies to `plusObject`/`minusObject`/`xorObject`/`bitOrObject`/`nullCoalescingObject` — all static. The base `Operator.apply(Object, Object)` (`:489-492`) is a one-line `return NumericOps.applyObject(this, left, right)`. The lifted `apply(Number, Operator, Number)` widening entry and `toLong` are gone from the enum (PSI: both absent), so there is no `super` superclass call left to dispatch.

#### C4: Typed-overload re-dispatch changed from virtual to static (favorable, not a regression)
- **Verdict:** CONFIRMED-as-sound — inside `NumericOps.apply(Number, Operator, Number)`, PSI resolves all five typed-pair calls to `static NumericOps.apply(Operator, Integer/Long/Float/Double/BigDecimal, …)` overloads. The pre-lift form called the typed overloads *virtually* on the enum constant (`operation.apply(int, int)`), a site that could go megamorphic across the 12 constants × 5 overrides. Replacing a virtual megamorphic dispatch with a static call + `switch (op)` tableswitch is same-or-better cost and more inlineable. COST TRACE: per arithmetic op, the engine now performs one virtual entry dispatch (unchanged) + N static calls (was: + N virtual calls) + one tableswitch in the widening + one tableswitch in the typed-pair method. SCALE CHECK: at production query rates the change can only reduce per-op cost (one fewer virtual dispatch); VERDICT = no finding — neutral-to-favorable.

#### C5: No new allocation on the common Integer/Long/Float/Double path
- **Verdict:** CONFIRMED-as-sound — the typed-pair `switch` arms (`NumericOps.apply(Operator, Integer, Integer)` etc.) return the identical primitive results of the old typed overrides, autoboxed the same way into the `Number` return type; `NULL_COALESCING` returns an already-boxed operand (no allocation). The only allocations are the `BigDecimal` conversions in the mixed-`BigDecimal` widening arms — `new BigDecimal(a.intValue())` / `BigDecimal.valueOf(a.doubleValue())` — which are allocation-equivalent to the pre-lift `new BigDecimal((Integer) a)` / `BigDecimal.valueOf((Double) a)` and fire only when an operand is already a `BigDecimal`, off the common path. No autoboxing, temporary array, or BigDecimal creation was added to the Integer/Long/Float/Double path. SCALE CHECK at 1M+ ops/s: zero added young-gen pressure on the common path; VERDICT = no finding.

#### C6: Object-level `+`/`-`/`^`/`|`/`??` bodies relocated verbatim
- **Verdict:** CONFIRMED-as-sound — `plusObject`/`minusObject`/`xorObject`/`bitOrObject`/`nullCoalescingObject` are line-for-line the former enum `apply(Object, Object)` bodies (same `null` short-circuits, same `String.valueOf(left) + right` concatenation in `plusObject`, same `new Date(result.longValue())` on the Date branch, same `0`-boxing in `bitOrObject`/`xorObject` null branches). No allocation or branch was added or removed; the only structural change is `super.apply(...)` → static `applyObject(Operator, …)`. Date and String paths allocate exactly as before and are not on the numeric hot path. VERDICT = no finding.
