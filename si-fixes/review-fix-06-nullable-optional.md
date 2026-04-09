# Fix 06: Replace @Nullable Optional with two overloads

## Problem

`BTreeSingleValueIndexEngine.doPutSingleValue()` takes
`@Nullable Optional<RawPair<CompositeKey, RID>> prefetched`, where:
- `null` means "not prefetched, do your own scan" (from `put()`)
- `Optional.empty()` means "prefetched but no entry found" (from `validatedPut()`)
- `Optional.of(pair)` means "prefetched and found this entry" (from `validatedPut()`)

Using `null` to mean "not prefetched" and `Optional.empty()` for "found nothing"
conflates two distinct missing-value semantics. `@Nullable Optional` is a well-known
Java anti-pattern.

## Affected Files

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java`
  - `doPutSingleValue()` line 371: parameter declaration
  - `put()` line 294: calls with `null`
  - `validatedPut()` line 339: calls with non-null Optional

## Fix Strategy

Split into two overloads:

```java
// Called by put() — always scans
private boolean doPutSingleValue(
    @Nonnull AtomicOperation atomicOperation,
    CompositeKey compositeKey,
    RID value,
    Object key) throws IOException {
  Optional<RawPair<CompositeKey, RID>> existing;
  try (var stream = sbTree.iterateEntriesBetween(...)) {
    existing = stream.findAny();
  }
  return doPutSingleValueCore(atomicOperation, compositeKey, value, key, existing);
}

// Called by validatedPut() — reuses prefetched result
private boolean doPutSingleValue(
    @Nonnull AtomicOperation atomicOperation,
    CompositeKey compositeKey,
    RID value,
    Object key,
    @Nonnull Optional<RawPair<CompositeKey, RID>> prefetched) throws IOException {
  return doPutSingleValueCore(atomicOperation, compositeKey, value, key, prefetched);
}

// Core logic with non-null Optional
private boolean doPutSingleValueCore(
    @Nonnull AtomicOperation atomicOperation,
    CompositeKey compositeKey,
    RID value,
    Object key,
    @Nonnull Optional<RawPair<CompositeKey, RID>> existing) throws IOException {
  // ... existing versioning/snapshot/delta logic
}
```

This makes the API unambiguous: the overload with `Optional` always receives a non-null
value, and the overload without it always scans.

## Implementation Steps

1. Extract core put logic into `doPutSingleValueCore()` with `@Nonnull Optional`.
2. Create two-arg overload (no prefetched) that scans and delegates.
3. Create three-arg overload (with prefetched) that delegates directly.
4. Update `put()` to call the no-prefetched overload.
5. Update `validatedPut()` to call the with-prefetched overload.
6. Run `./mvnw -pl core spotless:apply`
7. Run `./mvnw -pl core clean test`
8. Commit: `YTDB-523: Replace @Nullable Optional with two doPutSingleValue overloads`

## Severity

Suggestion (code quality). The existing code works correctly; this is a readability
improvement.
