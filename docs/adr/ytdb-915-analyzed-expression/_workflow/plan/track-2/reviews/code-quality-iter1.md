<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: CQ1, sev: suggestion, loc: NumericOps.java:257, anchor: "### CQ1 ", cert: n/a, basis: "Short+BigDecimal widening path silently changed semantics (latent ClassCastException fixed); untested either way, so the 'semantics preserved exactly' invariant is unpinned on this branch"}
  - {id: CQ2, sev: suggestion, loc: NumericOps.java:36, anchor: "### CQ2 ", cert: n/a, basis: "Two signature lines exceed the documented 100-col house-style limit; enforced Spotless gate tolerates them, so cosmetic only"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

## Findings

### CQ1 [suggestion] `Short + BigDecimal` widening path changed semantics (latent CCE fixed), and no test pins it either way

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java` (line 247-257), against the removed enum body at the old `SQLMathExpression.java` widening entry.

The track's controlling invariant is "math semantics preserved exactly" (track-2.md `## Invariants & Constraints`). The whole-enum lift is faithful on every path I checked except one, where the ErrorProne modernization quietly changed behavior:

The widening entry's first branch matches `a instanceof Integer || a instanceof Short`. On `develop` the `b instanceof BigDecimal` arm of that branch read:

```java
return operation.apply(new BigDecimal((Integer) a), (BigDecimal) b);
```

When `a` is genuinely a `Short` (the branch admits it via `a instanceof Short`), `(Integer) a` throws `ClassCastException` — a latent bug. The lifted form (line 257) is:

```java
return apply(operation, new BigDecimal(a.intValue()), bigDecimal);
```

`a.intValue()` works for both `Integer` and `Short`, so `Short + BigDecimal` now returns a correct result instead of throwing. The change is strictly an improvement and is the kind of cleanup the pattern-matching `instanceof` modernization is supposed to produce, so this is not a defect.

The reason it is worth surfacing: the deviation is invisible to the acceptance gate. `testTypes` exercises `(short) 1` only against non-`BigDecimal` right operands, and the new characterization tests do not touch `Short + BigDecimal`, so neither the old crash nor the new correct result is pinned. A reviewer reading the track's "semantics identical" claim should know the lift is not byte-for-byte on this one path.

**Suggestion**: add one assertion to an existing characterization test (or `testTypes`) covering `op.apply((short) 1, BigDecimal.ONE)` so the now-working path is pinned and the deviation is documented in the test rather than only in this review. If the team prefers to keep the lift literally faithful instead, that is the wrong call here (it would re-introduce the CCE) — pin the improved behavior.

### CQ2 [suggestion] Two signature lines exceed the documented 100-column limit (Spotless tolerates them)

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/util/NumericOps.java` (line 36 = 106 cols; line 125 = 102 cols).

```java
@Nullable public static Object applyObject(Operator op, @Nullable Object left, @Nullable Object right) {   // 106
@Nullable public static Object nullCoalescingObject(@Nullable Object left, @Nullable Object right) {        // 102
```

CLAUDE.md states a hard 100-column line width. These two lines exceed it. I verified the enforced gate does not catch them: `./mvnw -pl core spotless:check` passes and `spotless:apply` leaves `NumericOps.java` byte-identical, so the Eclipse formatter configured for this project does not reflow them. The inline `@Nullable public static …` placement is also an established codebase convention (57 core files use it), so this is not a consistency problem — only a raw overage against the documented limit, with no build consequence.

**Suggestion**: optional. If the team treats the 100-col rule as absolute regardless of formatter tolerance, wrap the two signatures (e.g. move `@Nullable` to its own line, which the rest of the codebase also does in 435 `sql/` sites, or wrap the parameter list). Otherwise leave as-is; the enforced gate is green and the style is internally consistent.

## Evidence base
