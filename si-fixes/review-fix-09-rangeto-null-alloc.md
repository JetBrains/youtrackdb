# Fix 09: Avoid unnecessary CompositeKey allocation when rangeTo is null

## Problem

In `iterateEntriesBetween()` of both engines, when `rangeFrom != null && rangeTo == null`,
line 431/446 creates `toKey = CompositeKey.asCompositeKey(rangeTo)` with `rangeTo == null`.
This allocates a `new CompositeKey(null)` that is immediately discarded when the
`rangeTo == null` check at line 440/455 diverts to the major-scan path.

## Affected Files

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java`
  - `iterateEntriesBetween()` lines 424-450
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java`
  - `iterateEntriesBetween()` lines 438-467

## Fix Strategy

Reorder the null checks to avoid the unnecessary allocation. Instead of:

```java
// both null → early return
if (rangeFrom == null && rangeTo == null) { ... }

final var toKey = CompositeKey.asCompositeKey(rangeTo);  // ← may waste alloc
if (rangeFrom == null) { ... use toKey ... }

final var fromKey = CompositeKey.asCompositeKey(rangeFrom);
if (rangeTo == null) { ... use fromKey only ... }  // toKey was wasted
```

Use:

```java
// both null → early return
if (rangeFrom == null && rangeTo == null) { ... }

// from null, to non-null → minor scan
if (rangeFrom == null) {
  final var toKey = CompositeKey.asCompositeKey(rangeTo);
  return ...minor scan using toKey...;
}

final var fromKey = CompositeKey.asCompositeKey(rangeFrom);
// to null, from non-null → major scan
if (rangeTo == null) {
  return ...major scan using fromKey...;
}

// both non-null → between scan
final var toKey = CompositeKey.asCompositeKey(rangeTo);
return ...between scan using fromKey and toKey...;
```

This ensures `asCompositeKey(rangeTo)` is only called when `rangeTo` is non-null.

## Implementation Steps

1. Refactor `BTreeSingleValueIndexEngine.iterateEntriesBetween()`.
2. Refactor `BTreeMultiValueIndexEngine.iterateEntriesBetween()`.
3. Run `./mvnw -pl core spotless:apply`
4. Run `./mvnw -pl core clean test`
5. Commit: `YTDB-523: Avoid unnecessary CompositeKey allocation when rangeTo is null`

## Severity

Minor (performance). One small wasted allocation per one-sided range scan.
