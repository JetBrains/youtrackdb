# Track 1 Risk Review: InPlaceComparator and EntityImpl comparison methods

## Finding R1 [blocker]
**Location**: EntityImpl dispatch logic (plan's `isPropertyEqualTo`/`comparePropertyTo` → `properties.get(name)` check), EntityImpl.java `deserializeProperties`
**Issue**: The plan states that the new methods should check `properties.get(name)` directly — **not** `checkForProperties()` — to avoid triggering deserialization. However, `properties.get(name) == null` conflates "key absent" with "key present but null-valued." After partial deserialization, `properties` may contain some keys but not others. The entry `null` vs "key absent" distinction matters: a property that has been lazily deserialized with a null value would have an `EntityEntry` with `exists() == true` and `value == null`.
**Proposed fix**: The dispatch logic must be:
1. If `properties != null` and `properties.containsKey(name)`: use the deserialized `EntityEntry` (checking `entry.exists()` and `entry.value`).
2. Else if `source != null`: use the serialized comparison via `deserializeField()`.
3. Else: return `Optional.empty()` (fallback — property not found and no source).

## Finding R2 [should-fix]
**Location**: EntityImpl dispatch logic → `status` check, EntityImpl.java
**Issue**: The plan does not mention checking `status` before accessing `source`. During `STATUS.UNMARSHALLING` (recursive deserialization of embedded entities), `source` bytes are being actively consumed — reading `source` concurrently via `deserializeField()` would be incorrect because the `BytesContainer` is stateful.
**Proposed fix**: Add a precondition check: if `status != STATUS.LOADED`, return `Optional.empty()` (fallback). This is cheap and ensures the optimization only fires on fully-loaded records whose `source` is stable.

## Finding R3 [should-fix]
**Location**: InPlaceComparator — `BytesContainer` mutability
**Issue**: `deserializeField()` mutates the `BytesContainer.offset` field as it scans the header. The plan creates a `new BytesContainer(source, 1)` which wraps the `source` byte array. After `deserializeField()` returns a `BinaryField`, the `BinaryField.bytes` is the **same** `BytesContainer` instance with `offset` repositioned to the value data. This is safe for single-property comparison but must be documented as an invariant.
**Proposed fix**: Add a comment/invariant in InPlaceComparator that each comparison reads exactly one serialized value starting at `field.bytes.offset` and does not reuse the `BytesContainer` afterward.

## Finding R4 [should-fix]
**Location**: InPlaceComparator — DECIMAL type comparison
**Issue**: DECIMAL comparison allocates a `BigDecimal` from bytes (effectively full deserialization). More importantly, `BigDecimal.compareTo()` has different semantics than `BigDecimal.equals()` (`new BigDecimal("1.0").equals(new BigDecimal("1"))` is `false` but `compareTo` returns 0).
**Proposed fix**: (a) Always use `BigDecimal.compareTo()` for equality checks. (b) For passed-in value conversion: use `BigDecimal.valueOf(double)` (not `new BigDecimal(double)`) to avoid floating-point representation artifacts.

## Finding R5 [should-fix]
**Location**: InPlaceComparator — FLOAT/DOUBLE comparison semantics
**Issue**: NaN handling: `Float.NaN != Float.NaN` by IEEE 754, but `Float.compare(Float.NaN, Float.NaN) == 0`. The plan must specify whether to use `==` or `Float.compare()`.
**Proposed fix**: Use `Float.compare()` and `Double.compare()` for all float/double comparisons, including equality. This matches Java `Comparable` semantics and handles NaN and negative zero correctly.

## Finding R6 [should-fix]
**Location**: EntityImpl dispatch — `propertyEncryption` check
**Issue**: The plan mentions falling back when `propertyAccess` restricts the property, but does not mention encrypted properties. If a property is encrypted, `deserializeField()` returns ciphertext — comparing ciphertext against a plaintext Java value would produce wrong results.
**Proposed fix**: Before calling `deserializeField()`, check `propertyEncryption != null && !(propertyEncryption instanceof PropertyEncryptionNone) && propertyEncryption.isEncrypted(name)`. If encrypted, return `Optional.empty()` (fallback).

## Finding R7 [suggestion]
**Location**: EntityImpl dispatch — `className` not yet resolved
**Issue**: `deserializeField()` requires a `SchemaClass` parameter. If `getImmutableSchemaClass()` returns null (schema-less records), `deserializeField()` could encounter issues.
**Proposed fix**: If `getImmutableSchemaClass(session)` returns null, fall back to the deserialization path.

## Finding R8 [should-fix]
**Location**: Track 1 — numeric overflow detection for narrowing conversions
**Issue**: The plan says "narrowing conversions that would lose precision must fall back" but doesn't specify exact range checks or handle float-to-integer conversions. Converting `3.14f` to `int` would be `3`, wrong for `=` comparison.
**Proposed fix**: (a) For floating-point to integer conversions: always fall back. (b) For integer narrowing: check `value >= Type.MIN_VALUE && value <= Type.MAX_VALUE`. (c) Document all conversion rules in a table.

## Finding R9 [suggestion]
**Location**: InPlaceComparator — collation check
**Issue**: `deserializeField()` may return a `BinaryField` with non-null `collate` field. Track 2 checks `getCollate()` at the SQL level, but Track 1 should also guard at EntityImpl level for defense-in-depth.
**Proposed fix**: After `deserializeField()`, check `if (field.collate != null && !(field.collate instanceof DefaultCollate)) return Optional.empty()`.

## Finding R10 [suggestion]
**Location**: InPlaceComparator — LINK comparison
**Issue**: LINK ordering semantics (`<`/`>`) are not well-defined for binary comparison.
**Proposed fix**: For LINK, only support `isPropertyEqualTo` (equality). For `comparePropertyTo`, return `OptionalInt.empty()` (fallback).

## Finding R11 [suggestion]
**Location**: InPlaceComparator — DATE type and timezone handling
**Issue**: DATE values are serialized as days-since-epoch and require timezone conversion via `convertDayToTimezone()`. The plan doesn't mention timezone handling.
**Proposed fix**: Replicate `convertDayToTimezone()` logic in the DATE comparison path, or convert the passed-in `Date` to days-since-epoch using the same timezone logic.

## Finding R12 [suggestion]
**Location**: Unit test coverage — testing with actual serialized records
**Issue**: Testing EntityImpl methods requires actual serialized records with `source != null`.
**Proposed fix**: Use pattern: create entity → save to DB → reload → immediately call `isPropertyEqualTo` before any `getProperty()`. Also test partially deserialized scenario.

## Summary

| ID | Severity | Core Issue |
|----|----------|------------|
| R1 | blocker | `properties.get(name) == null` conflates "absent" with "null-valued"; must use `containsKey` |
| R2 | should-fix | Missing `status` check — could read `source` during UNMARSHALLING |
| R3 | should-fix | `BytesContainer` mutability needs explicit invariant documentation |
| R4 | should-fix | DECIMAL comparison allocates `BigDecimal`; must use `compareTo`, not `equals` |
| R5 | should-fix | Float/double NaN and negative-zero handling — must use `Float.compare()` |
| R6 | should-fix | Encrypted properties would compare ciphertext vs plaintext |
| R7 | suggestion | `getImmutableSchemaClass()` returning null could cause NPE |
| R8 | should-fix | Numeric narrowing and float-to-int conversions need explicit rules |
| R9 | suggestion | `BinaryField.collate` should be checked at EntityImpl level too |
| R10 | suggestion | LINK ordering semantics undefined; limit to equality only |
| R11 | suggestion | DATE timezone conversion must match `convertDayToTimezone()` |
| R12 | suggestion | Test setup must preserve `source` for realistic EntityImpl testing |
