<!-- MANIFEST
dimension: code-quality
iteration: 1
findings: 3
evidence_base: { certs: 0 }
index:
  - id: CQ1
    sev: suggestion
    anchor: "CQ1"
    loc: "DatabaseSessionEmbedded.java:905-912; ShapeClassifier.java:1439-1446; AggregateState.java:509-515"
    cert: n/a
    basis: diff + grep
  - id: CQ2
    sev: suggestion
    anchor: "CQ2"
    loc: "AggregateState.java:927-940"
    cert: n/a
    basis: diff
  - id: CQ3
    sev: suggestion
    anchor: "CQ3"
    loc: "AggregateState.java:942-972; getContributingValues/getDistinctBuckets"
    cert: n/a
    basis: diff
flags: []
-->

## Findings

### CQ1 [suggestion] AGGREGATE_* membership enumerated in three places

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbedded.java` (`isAggregateShape`, lines ~905-912), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/ShapeClassifier.java` (`isAggregateKind`, lines ~1439-1446), `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateState.java` (constructor assert, lines ~509-515).

**Issue**: The same six-way disjunction (`shape == AGGREGATE_COUNT || ... || shape == AGGREGATE_COUNT_DISTINCT`) is hand-written three times across three classes. If a future track adds a seventh aggregate shape (or the `COUNT_DISTINCT` member is removed, which Track 3 notes is now production-unreached), each copy must be found and updated independently; missing one silently routes the new shape down the wrong path. This is the classic enumerated-set DRY hazard the `CacheableShape` enum could close.

**Suggestion**: Centralize the predicate once — either a package-visible `CacheableShape.isAggregate()` instance method on the enum, or a single static helper in `ShapeClassifier` that the other two call. The constructor assert in `AggregateState` can then read `assert kind.isAggregate()`. Not blocking: the three copies are currently identical and covered by tests, so there is no live defect — only future-maintenance fragility.

### CQ2 [suggestion] AggregateState.computeAverage silently returns null for unmodeled Number subtypes

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateState.java` (`computeAverage`, lines ~927-940).

**Issue**: The type-dispatch ladder handles `Integer`/`Long`/`Float`/`Double`/`BigDecimal` and falls through to `return null` for anything else. The Javadoc says it reproduces `SQLFunctionAverage.computeAverage` verbatim, and the empty-set case (`sum == null`) legitimately returns null. But a non-null `sum` of an unmodeled subtype (e.g. `Short`, `Byte`, `BigInteger` — whatever `PropertyTypeInternal.increment` can yield from the fold) would also return null, producing a wrong AVG scalar with no signal. Because the value reproduces a private storage method rather than calling it, the two can drift if storage starts producing such a type.

**Suggestion**: If the contract is that `increment` only ever yields the five handled types, make that explicit with an `else throw new IllegalStateException("unexpected AVG accumulator type " + sum.getClass())` on the non-null-but-unmatched branch, so a future divergence fails loudly in a test rather than silently mis-finalizing. Keep the `sum == null -> null` empty-set branch as-is. Low severity: the value aggregates are gated to numeric properties and the equivalence test compares against fresh execution, so a real drift would surface — but only for a type the test happens to exercise.

### CQ3 [suggestion] Package-private test accessors widen the AggregateState surface

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/AggregateState.java` (`getContributingValues`, `getDistinctBuckets`, `getContributingRids`, lines ~942-972).

**Issue**: Three accessors return the live internal collections (`Map<RID,Object>`, `Map<Object,Set<RID>>`, `Set<RID>`) rather than copies or unmodifiable views. They are package-private and the comment marks them test/inspection-only, so this is not an encapsulation defect today — the only callers are tests in the same package. The risk is that a future same-package caller mutates the returned collection and corrupts the seeded state, defeating the `copy()` isolation the rest of the class is careful to preserve.

**Suggestion**: Wrap the three returns in `Collections.unmodifiable*` (the tests only read them, so this costs nothing) to make the read-only intent enforced rather than documented. Minor; the cap-overflow and bucket-lifecycle tests that consume these would be unaffected.

## Evidence base
