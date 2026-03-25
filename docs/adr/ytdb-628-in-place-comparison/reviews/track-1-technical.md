# Track 1 Technical Review: InPlaceComparator and EntityImpl comparison methods

## Finding T1 [blocker]
**Location**: Track 1 — EntityImpl deserialized path, using `Objects.equals(entry.value, value)` for equality
**Issue**: The plan proposes `Objects.equals(entry.value, value)` for the deserialized equality path. This does not match SQL engine comparison semantics. The existing SQL path uses `PropertyTypeInternal.castComparableNumber()` for cross-type numeric comparisons (e.g., `Integer(5).equals(Long(5L))` returns `false` in Java, but the SQL engine casts both to LONG and returns `true`). Using `Objects.equals` will produce wrong results for cross-type numeric comparisons on the deserialized path.
**Proposed fix**: For the deserialized path, perform type-aware comparison: use the same type conversion as InPlaceComparator's `convertToType()` on the deserialized Java values, ensuring both values are the same type before comparing. Alternatively use `QueryOperatorEquals.equals(session, entry.value, value)` or replicate the numeric casting logic.

## Finding T2 [blocker]
**Location**: Track 1 — EntityImpl dispatch methods, `properties` field null-safety
**Issue**: The `properties` field in EntityImpl is initialized to `null` (line 110). When an entity has been loaded from storage but no properties have been accessed yet, `properties` is `null`. Calling `properties.get(name)` would throw `NullPointerException`.
**Proposed fix**: Check `properties != null` before calling `properties.get(name)`. When `properties` is null, proceed directly to the serialized comparison path (check `source != null`).

## Finding T3 [should-fix]
**Location**: Track 1 — EntityImpl dispatch methods, missing `checkForBinding()` call
**Issue**: Every public method in EntityImpl calls `checkForBinding()` as its first action, ensuring the entity is in valid state. The new methods would be the only public methods that skip this check. While in SQL evaluation the entity is always LOADED, these are public methods that could be called from user code.
**Proposed fix**: Call `checkForBinding()` at the start of both methods. This is cheap and maintains the EntityImpl contract. The key optimization of not calling `checkForProperties()` is preserved.

## Finding T4 [should-fix]
**Location**: Track 1 — InPlaceComparator, DECIMAL comparison
**Issue**: `DecimalSerializer.staticDeserialize()` allocates a `BigDecimal` from bytes — cannot be truly zero-allocation. Also, `staticDeserialize` takes `(byte[], offset)` not `BytesContainer`, so the offset needs to be passed as `bytes.offset`.
**Proposed fix**: Acknowledge DECIMAL allocates a `BigDecimal`. Use `DecimalSerializer.staticDeserialize(bytes.bytes, bytes.offset)`.

## Finding T5 [should-fix]
**Location**: Track 1 — EntityImpl, `propertyEncryption` null handling
**Issue**: `propertyEncryption` is initialized to `null` and only set in `initPropertyAccess()`. The `encryption` parameter in `deserializeField()` is currently a dead parameter (never used in the method body), but passing null is fragile.
**Proposed fix**: Defensively handle null by falling back to `PropertyEncryptionNone.instance()`.

## Finding T6 [should-fix]
**Location**: Track 1 — Design, `deserializeField` returns null for non-binary-comparable types
**Issue**: `deserializeField()` already returns null when `!getComparator().isBinaryComparable(type)`. The plan's separate "Type binary-comparable?" check is redundant.
**Proposed fix**: Remove the separate binary-comparability check from the flowchart/implementation since `deserializeField()` already handles this.

## Finding T7 [suggestion]
**Location**: Track 1 — BinaryField `collate` field for collation-aware fallback
**Issue**: `deserializeField()` returns a `BinaryField` with non-null `collate` when the property has a collation. EntityImpl-level methods should check this for defense-in-depth, not rely solely on Track 2's SQLBinaryCondition check.
**Proposed fix**: After `deserializeField()`, check `binaryField.collate != null && !(binaryField.collate instanceof DefaultCollate)` and return empty (fallback).

## Finding T8 [suggestion]
**Location**: Track 1 — FLOAT/DOUBLE comparison with NaN and special values
**Issue**: `Float.compare`/`Double.compare` handle NaN and -0.0 specially, differing from IEEE 754 `==`. The plan should confirm these are the intended comparison methods.
**Proposed fix**: Use `Float.compare()`/`Double.compare()` to match `Float.compareTo()`/`Double.compareTo()` semantics.

## Finding T9 [suggestion]
**Location**: Track 1 — `deserializeField` with null `iClass` parameter
**Issue**: For schema-less entities, `getImmutableSchemaClass(session)` can be null. Passing null is safe for inline-name fields but fragile if a schema-less entity has a global property reference.
**Proposed fix**: Add a null guard or document the assumption.

## Summary

| ID | Severity | Core Issue |
|----|----------|------------|
| T1 | blocker | Deserialized path needs type-aware comparison, not Objects.equals |
| T2 | blocker | `properties` field can be null — NPE risk |
| T3 | should-fix | Missing `checkForBinding()` in new public methods |
| T4 | should-fix | DECIMAL allocates BigDecimal; API takes (byte[], offset) not BytesContainer |
| T5 | should-fix | `propertyEncryption` can be null — defensive handling needed |
| T6 | should-fix | Binary-comparability check redundant with deserializeField() |
| T7 | suggestion | Check BinaryField.collate at EntityImpl level for defense-in-depth |
| T8 | suggestion | Confirm Float.compare/Double.compare for NaN handling |
| T9 | suggestion | Null guard for schema-less entities in deserializeField |
