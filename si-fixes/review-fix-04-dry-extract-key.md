# Fix 04: Unify duplicated extractKey() methods

## Problem

Both engines have a private `extractKey(CompositeKey)` method that strips trailing
internal elements from a CompositeKey. The logic is identical except for the number
of trailing elements to remove:
- Single-value: strips 1 (version)
- Multi-value: strips 2 (RID + version)

## Affected Files

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java`
  - `extractKey()` lines 509-525
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java`
  - `extractKey()` lines 524-541

## Fix Strategy

Create a shared static method parameterized by the number of trailing elements to strip.

If `VersionedIndexOps` is created in Fix 03, add it there. Otherwise, add it as a
package-private static method on one of the engines and reference from the other, or
create a minimal utility class.

```java
@Nullable static Object extractUserKey(CompositeKey compositeKey, int trailingCount) {
  if (compositeKey == null) {
    return null;
  }
  final var keys = compositeKey.getKeys();
  int userKeyCount = keys.size() - trailingCount;
  if (userKeyCount == 1) {
    return keys.getFirst();
  }
  var result = new CompositeKey(userKeyCount);
  for (int i = 0; i < userKeyCount; i++) {
    result.addKey(keys.get(i));
  }
  return result;
}
```

Then in each engine, create a method reference:
- Single-value: `private static Object extractKey(CompositeKey k) { return extractUserKey(k, 1); }`
- Multi-value: `private static Object extractKey(CompositeKey k) { return extractUserKey(k, 2); }`

Or use lambdas/method references directly where `extractKey` is passed to
`visibilityFilterMapped`.

## Implementation Steps

1. Add `extractUserKey(CompositeKey, int)` to `VersionedIndexOps` (if created) or as
   a package-private static method.
2. Refactor both engines' `extractKey()` to delegate.
3. Run `./mvnw -pl core spotless:apply`
4. Run `./mvnw -pl core clean test`
5. Commit: `YTDB-523: Unify duplicated extractKey() into parameterized helper`

## Severity

Suggestion (DRY). Small duplication, easy to unify.
