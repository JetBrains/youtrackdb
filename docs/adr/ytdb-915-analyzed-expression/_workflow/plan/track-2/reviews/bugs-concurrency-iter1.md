<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: BC1, sev: should-fix, loc: "NumericOps.java:247-257", anchor: "### BC1 ", cert: C1, basis: "Short op BigDecimal: old threw ClassCastException, new returns a value — reachable via SHORT+DECIMAL fields; violates the exact-preservation invariant and is untested"}
  - {id: BC2, sev: suggestion, loc: "NumericOps.java:67,88", anchor: "### BC2 ", cert: C2, basis: "Date +/- non-numeric-non-Date now throws IllegalArgumentException where the old typed-overload path threw NullPointerException — exception-type drift on an error path"}
evidence_base: {section: "## Evidence base", certs: 2, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
flags: [CONTRACT_OK]
-->

## Findings

### BC1 [should-fix] `Short op BigDecimal` changes from `ClassCastException` to a computed result

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java` (lines 247-257)
- **Issue**: The `new BigDecimal((Integer) a)` → `new BigDecimal(a.intValue())` rewrite the
  implementer flagged is value-identical only when `a` is an `Integer`. The enclosing branch is
  `if (a instanceof Integer || a instanceof Short)`, so `a` can also be a `Short`. On develop, when
  `a` is a `Short` and `b` is a `BigDecimal`, the old code reached
  `operation.apply(new BigDecimal((Integer) a), (BigDecimal) b)` and the `(Integer) a` cast threw a
  `ClassCastException` (`java.lang.Short` is not a `java.lang.Integer`). The new code at line 257
  computes `new BigDecimal(a.intValue())` and returns a valid promoted result. This is an observable
  behavior change on the live AST arithmetic path, on the very `BigDecimal`-widening line the lift was
  meant to copy faithfully.
- **Evidence**: See `## Evidence base` → C1. The old throwing code is confirmed in the parent commit
  at `SQLMathExpression.java:587-597` (`if (a instanceof Integer || a instanceof Short) { ... if (b
  instanceof BigDecimal) return operation.apply(new BigDecimal((Integer) a), (BigDecimal) b); }`). The
  path is reachable: `PropertyType.SHORT` maps to `Short.class` and SHORT field values deserialize to
  `java.lang.Short` (`PropertyTypeInternal:136`, `RecordSerializerStringAbstract:358`), nothing in
  `SQLMathExpression.execute` / `calculateWithOpPriority` normalises a child value's runtime type, and
  the only production caller of `Operator.apply(Object, Object)` is that evaluation path (PSI: callers
  are `operators.getFirst().apply` / `operatorsStack.poll().apply` in `SQLMathExpression.java` plus
  tests). So an expression such as `shortField * decimalField` reaches the widening entry with `a`
  being a `Short`. The trace is: `Operator.STAR.apply(Object,Object)` (null-check) →
  `NumericOps.applyObject(STAR, Short, BigDecimal)` → `apply(Short, STAR, BigDecimal)` → line 257.
- **Refutation considered**: I checked whether `Short` is screened out or coerced before the widening
  entry — it is not: `applyObject` passes the raw `Number` through, and the precedence fold pushes
  un-normalised `child.execute()` results. I checked whether `Short` is even producible — it is, as a
  first-class stored `PropertyType.SHORT`. I checked the *symmetric* `BigDecimal op Short` case
  (`a instanceof BigDecimal`, `b instanceof Short`, line 292-293): there `new BigDecimal((Short) b)`
  unboxed `short` → widened to `int` → `BigDecimal(int)`, identical to the new `new
  BigDecimal(b.intValue())`, so that direction is genuinely equivalent. Only the `Short`-as-left-operand
  + `BigDecimal`-as-right-operand combination diverges. The change is plausibly a latent-bug fix
  (the `|| a instanceof Short` clearly intended to handle Short), but it still breaks the track's stated
  "math semantics preserved EXACTLY" invariant on a reachable path, and no test pins either the old or
  new behavior.
- **Suggestion**: Decide explicitly whether `Short + BigDecimal` should throw (preserve old behavior)
  or compute (the new behavior). The track invariant says preserve exactly; if the fix is intended,
  record it as a deliberate divergence in the track's `## Surprises & Discoveries` and add a
  characterization test pinning the chosen behavior (the existing `MathExpressionTest` additions cover
  only Integer/Long/Date/String, never a `Short` operand against `BigDecimal`). If preserve-exact is
  required, this line and the value path are the place to keep the throw.

### BC2 [suggestion] `Date +/- (non-numeric, non-Date)` operand changes the thrown exception type

- **File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java`
  (lines 67 and 88)
- **Issue**: In `plusObject` (line 67) and `minusObject` (line 88) the Date branch now calls the
  three-arg widening entry `apply(toLong(left), Operator.PLUS, toLong(right))`. On develop the enum's
  PLUS/MINUS `apply(Object,Object)` Date branch called the two-arg *typed* overload
  `apply(toLong(left), toLong(right))` (binding to `apply(Long, Long)`). When one operand is a `Date`
  and the other is neither a `Number` nor a `Date` (e.g. `dateField + 'x'`), `toLong` returns `null`
  for that operand. Old path: `apply(Long, null)` → typed `left + right` → unboxing `null` → 
  `NullPointerException`. New path: the widening entry's leading
  `if (a == null || b == null) throw new IllegalArgumentException("Cannot increment a null value")`
  throws `IllegalArgumentException` instead. The normal `Date + Long` / `Long + Date` / `Date - Long`
  results are identical (both produce the same `Date`); only the error case changes exception class.
- **Evidence**: See `## Evidence base` → C2. The two-arg call sites are confirmed in the parent commit
  at `SQLMathExpression.java:199` (PLUS) and `:256` (MINUS); the new three-arg widening call is at
  `NumericOps.java:67`/`:88`, and the widening entry's null guard is at `NumericOps.java:243-245`.
- **Refutation considered**: Both forms throw on this input, so no path that previously succeeded now
  fails or vice versa — the only difference is the exception type. No test pins the exception class for
  `Date + String`, and the orientation notes scope `Date` arithmetic to `Date ± Long`. The impact is a
  changed unchecked-exception type on an already-failing path, hence suggestion rather than should-fix.
- **Suggestion**: If exact preservation matters here, route the Date branch through the typed
  `apply(Operator, Long, Long)` (which preserves the NPE-on-null shape) instead of the widening entry,
  or simply accept the `IllegalArgumentException` as the cleaner error and note it. Either way it is
  worth a one-line acknowledgment so a future reader does not treat the NPE→IAE shift as accidental.

## Evidence base

#### C1 `Short op BigDecimal` divergence — CONFIRMED

CONFIRMED-as-issue (survived refutation). Old `SQLMathExpression.java:587-597` (parent commit
`09389fdc16~1`) throws `ClassCastException` on `(Integer) a` when `a` is a `Short` and `b` is a
`BigDecimal`; new `NumericOps.java:257` computes `new BigDecimal(a.intValue())`. Reachability proven:
`PropertyType.SHORT` → `Short.class` (`PropertyTypeInternal:136`), SHORT deserializes to
`java.lang.Short` (`RecordSerializerStringAbstract:358`), no type normalisation in the
`SQLMathExpression.execute` → `applyObject` → widening trace, PSI confirms the only production caller of
`Operator.apply(Object,Object)` is that evaluation path. `BigDecimal(int)` and `BigDecimal(long)`
constructors confirmed to exist (PSI), so for an `Integer`/`Long` `a` the rewrite is value-identical;
the divergence is specific to `Short`-as-left + `BigDecimal`-as-right.

#### C2 `Date ± non-numeric-non-Date` exception-type drift — CONFIRMED

CONFIRMED-as-issue (survived refutation). Old PLUS/MINUS Date branches (`SQLMathExpression.java:199`,
`:256`, parent commit) used the two-arg typed `apply(Long, Long)`, which NPEs when `toLong` yields
`null` for a non-numeric, non-Date operand; new `plusObject`/`minusObject` (`NumericOps.java:67`/`:88`)
use the three-arg widening entry, whose null guard at `:243-245` throws `IllegalArgumentException`.
Both throw on the same input, so this is an exception-class change on an error path, not a
success-vs-failure change; normal `Date ± Long` results are identical in both.
