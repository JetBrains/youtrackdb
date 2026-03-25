# Track 1: InPlaceComparator and EntityImpl comparison methods

## Progress
- [x] Review + decomposition
- [ ] Step implementation (3/4 complete)
- [ ] Track-level code review

## Base commit
`44955206a4`

## Reviews completed
- [x] Technical
- [x] Risk
- [x] Adversarial

## Review findings summary

### Blockers addressed in decomposition
- **Properties null + containsKey semantics** (R1, T2, A11): Full state matrix
  implemented in Step 3. Uses `containsKey` + `entry.exists()` + null guards.
- **Deserialized path needs type-aware comparison** (T1): Step 3 uses the same
  type conversion as InPlaceComparator, not `Objects.equals`.
- **deserializeField returns null for zero-length fields** (A7): Known performance
  gap in v1. Zero-length fields (empty strings, defaults) fall back to
  deserialization. Documented here.
- **BinaryField BytesContainer is ephemeral** (A2, R3): Each call creates a fresh
  `BytesContainer`. InPlaceComparator consumes it once. Documented as invariant.

### Should-fix items incorporated
- Status check (R2): `status != STATUS.LOADED` → fallback (Step 3)
- Float/Double use `Float.compare`/`Double.compare` (R5, T8) (Steps 1-2)
- Float↔Double: widen to double precision (A5) (Step 1)
- Integer→float precision: safe if |value| ≤ 2^24 (float), 2^53 (double) (A9, R8) (Step 1)
- DECIMAL: `BigDecimal.compareTo()`, not `equals()` (R4, T4) (Step 2)
- Encrypted properties → fallback (R6, T5) (Step 3)
- `InPlaceResult` enum replaces `Optional<Boolean>` (A3) (Step 3)
- `checkForBinding()` in new public methods (T3) (Step 3)
- `entry.type == null` → fallback to Java comparison (A4) (Step 3)
- BinaryField.collate check for defense-in-depth (R9, T7) (Step 3)
- LINK: equality only for `comparePropertyTo`; ordering returns empty (R10) (Step 2)
- DATE timezone via `convertDayToTimezone` (R11) (Step 2)
- Null iClass → fallback (R7, T9) (Step 3)
- Binary-comparability check redundant — rely on `deserializeField()` (T6) (Step 3)

### Suggestions incorporated
- Explicit excluded types: EMBEDDED, EMBEDDEDLIST, EMBEDDEDSET, EMBEDDEDMAP,
  LINKLIST, LINKSET, LINKMAP, LINKBAG (A8)
- Step sequencing: InPlaceComparator standalone first, then EntityImpl (A6)

### Out of scope
- BinaryComparatorV0 reconciliation (A10) — follow-up after Track 2

## EntityImpl dispatch state matrix

Used by Step 3. Covers all combinations of `properties` and `source` state.

```
isPropertyEqualTo(name, value) / comparePropertyTo(name, value):

1. checkForBinding()
2. if (status != STATUS.LOADED) → FALLBACK
3. if (propertyAccess != null && !propertyAccess.isReadable(name)) → FALLBACK

4. if (properties != null && properties.containsKey(name)):
   a. entry = properties.get(name)
   b. if (!entry.exists()) → FALLBACK  // property deleted
   c. entryValue = entry.value
   d. if (entryValue == null || value == null) → FALLBACK  // null handling
   e. if (entry.type == null) → FALLBACK  // schema-less, type unknown
   f. Convert value to entry.type using same conversion as InPlaceComparator
   g. If conversion fails → FALLBACK
   h. Compare using type-appropriate Java comparison (Float.compare, etc.)
   i. Return result

5. else if (source != null):
   a. session = getSessionIfAlive()
   b. if (session == null) → FALLBACK
   c. schemaClass = getImmutableSchemaClass(session)
   d. if (value == null) → FALLBACK
   e. encryption = propertyEncryption
   f. if (encryption != null && !(encryption instanceof PropertyEncryptionNone)
         && encryption.isEncrypted(name)) → FALLBACK
   g. serializer = RecordSerializerBinary.INSTANCE.getSerializer(source[0])
   h. bytes = new BytesContainer(source, 1)
   i. field = serializer.deserializeField(session, bytes, schemaClass, name,
         isEmbedded(), session.getMetadata().getImmutableSchemaSnapshot(),
         encryption)
   j. if (field == null) → FALLBACK  // not found or zero-length or non-comparable
   k. if (field.collate != null && !(field.collate instanceof DefaultCollate))
         → FALLBACK
   l. return InPlaceComparator.compare(field, value) / .isEqual(field, value)

6. else → FALLBACK  // no properties, no source
```

## Steps

- [x] Step 1: InPlaceComparator — numeric types with type conversion
  - [x] Context: safe
  > **What was done:** Created `InPlaceComparator` utility class with `compare(BinaryField, Object)` → `OptionalInt` and `isEqual(BinaryField, Object)` → `OptionalInt` (1/0/empty). Supports INTEGER, LONG, SHORT, BYTE, FLOAT, DOUBLE with type conversion infrastructure. Precision boundaries: 2^24 for int→float, 2^53 for long→double. Float/Double→integer always falls back. 90+ unit tests covering all 6 types, all cross-type combinations, precision boundaries, NaN/-0.0/infinity, BigDecimal/BigInteger fallback.
  >
  > **What was discovered:** Review caught a blocker: `convertToFloat` and `convertToDouble` had a silent truncation bug for `BigDecimal`/`BigInteger` values that fell through to the integer catch-all path via `longValue()`. Fixed by adding explicit guards for standard integer boxed types only.
  >
  > **Key files:** `InPlaceComparator.java` (new), `InPlaceComparatorNumericTest.java` (new)

- [x] Step 2: InPlaceComparator — non-numeric types (STRING, BOOLEAN, DATETIME, DATE, DECIMAL, BINARY, LINK)
  - [x] Context: info
  > **What was done:** Extended InPlaceComparator with 7 non-numeric types. Added `compare(BinaryField, Object, TimeZone)` overload for DATE timezone support. STRING via `readString()`+`compareTo()`, BOOLEAN via byte read+`Boolean.compare()`, DATETIME via VarInt millis, DATE via days*MILLISEC_PER_DAY+`convertDayToTimezone()`, DECIMAL via `staticDeserialize()`+`BigDecimal.compareTo()`, BINARY via `readBinary()`+`Arrays.compare()`, LINK equality only (ordering returns empty). 60+ tests including non-GMT timezone, Identifiable path, Float-to-DECIMAL precision.
  >
  > **What was discovered:** Review caught DECIMAL bug — `bytes.offset` not advanced after `staticDeserialize()` (unlike all other types that advance offset via read methods). Fixed by adding `bytes.skip(staticGetObjectSize(...))`. Also: DATE requires `TimeZone` parameter, so added overloaded `compare`/`isEqual` that accept optional timezone.
  >
  > **What changed from the plan:** Added `TimeZone` parameter to `compare`/`isEqual` signatures. Step 3 (EntityImpl) will need to pass the database timezone when calling these methods for DATE fields.
  >
  > **Key files:** `InPlaceComparator.java` (modified), `InPlaceComparatorNonNumericTest.java` (new)

- [x] Step 3: InPlaceResult enum + EntityImpl comparison methods
  - [x] Context: warning
  > **What was done:** Added `InPlaceResult` enum (TRUE/FALSE/FALLBACK) and two new methods on EntityImpl: `isPropertyEqualTo` and `comparePropertyTo`. Both check properties map first (without triggering deserialization), then fall back to serialized source bytes via InPlaceComparator. Handles all guards: status, propertyAccess, encryption, collation, null values. Shared `deserializeFieldForComparison` helper eliminates DRY violations. Single `compareJavaValuesOrdering` method handles all type-aware comparisons.
  >
  > **What was discovered:** Three review-caught bugs: (1) `serializeAndReload` via `ser.fromStream` fully deserializes and clears `source`, so tests never exercised the serialized path — fixed with `fromStream(byte[])`. (2) Float/Double truncation via `longValue()` in INTEGER/LONG/SHORT/BYTE comparison — fixed by rejecting Float/Double values. (3) BINARY deserialized path used `byte[].equals()` (reference equality) — fixed with `Arrays.compare` case.
  >
  > **Key files:** `InPlaceResult.java` (new), `EntityImpl.java` (modified), `EntityImplInPlaceComparisonTest.java` (new)

- [ ] Step 4: Cross-path equivalence tests
  > Comprehensive test suite verifying that in-place comparison produces
  > identical results to the standard deserialization + Java comparison path
  > for all supported types and edge cases.
  >
  > Test matrix:
  > - All 13 binary-comparable types with matching values (→ equal)
  > - All 13 types with non-matching values (→ not equal, correct ordering)
  > - Cross-type numeric: Integer property vs Long value, Short vs Integer,
  >   Float vs Double, Integer vs Double, etc.
  > - Precision boundary values: 2^24 ± 1 for int→float, 2^53 ± 1 for long→double
  > - Float/Double edge cases: NaN, -0.0, +Infinity, -Infinity
  > - DECIMAL: different scales (`1.0` vs `1`), double conversion artifacts
  > - DATE: different timezones (verify convertDayToTimezone correctness)
  > - Null property value, null comparison value
  > - Empty string (verifies graceful fallback for zero-length field)
  > - Partially deserialized entities (mix of map and source lookups)
  > - Property types that fall back (EMBEDDED, LINKLIST, etc.)
  >
  > Pattern: for each test case, create entity with property, save, reload,
  > then assert `isPropertyEqualTo` / `comparePropertyTo` matches
  > `getProperty()` + Java comparison.
  >
  > **Files**: `InPlaceComparisonEquivalenceTest.java` (new)
