# Track 1: InPlaceComparator and EntityImpl comparison methods

## Progress
- [x] Review + decomposition
- [ ] Step implementation
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

- [ ] Step 1: InPlaceComparator — numeric types with type conversion
  > Create new `InPlaceComparator` utility class with static methods for
  > binary comparison of numeric property types: INTEGER, LONG, SHORT, BYTE,
  > FLOAT, DOUBLE. Implement the type conversion infrastructure that converts
  > a passed-in Java value to the property's serialized type before same-type
  > comparison.
  >
  > Key design points:
  > - Main entry: `static OptionalInt compare(BinaryField field, Object value)`
  >   dispatches by `field.type`
  > - Main entry: `static InPlaceResult isEqual(BinaryField field, Object value)`
  >   derives from compare (can optimize STRING later)
  > - Use `Float.compare()` / `Double.compare()` for NaN and -0.0 correctness
  > - Float↔Double: always widen to double precision (compare at double when
  >   either operand is double)
  > - Integer narrowing: range check `value >= Type.MIN_VALUE && value <= Type.MAX_VALUE`
  > - Integer→Float precision: safe only if `|value| <= (1 << 24)` (2^24)
  > - Long→Double precision: safe only if `|value| <= (1L << 53)` (2^53)
  > - Float→integer, Double→integer: always fall back
  > - Conversion failure → return `OptionalInt.empty()`
  > - Read serialized values using `HelperClasses` / `VarIntSerializer` methods
  >
  > Unit tests: all 6 numeric types, cross-type conversion (Integer→Long,
  > Long→Short with overflow, Double→Float precision loss, Float→Integer
  > fallback), edge cases (NaN, -0.0, MAX_VALUE, MIN_VALUE, precision
  > boundaries at 2^24 and 2^53).
  >
  > **Files**: `InPlaceComparator.java` (new), `InPlaceComparatorNumericTest.java` (new)

- [ ] Step 2: InPlaceComparator — non-numeric types (STRING, BOOLEAN, DATETIME, DATE, DECIMAL, BINARY, LINK)
  > Extend InPlaceComparator with comparison methods for the remaining 7
  > binary-comparable types.
  >
  > Key design points:
  > - STRING: read length + UTF-8 bytes via `HelperClasses.readString()`;
  >   compare with `String.compareTo()`. No collation support (falls back
  >   at EntityImpl level).
  > - BOOLEAN: read single byte; compare as boolean values
  > - DATETIME: read long millis via `VarIntSerializer.readAsLong()`;
  >   compare passed-in `Date.getTime()` as long
  > - DATE: read days-since-epoch via `VarIntSerializer.readAsLong()`;
  >   apply `HelperClasses.convertDayToTimezone()` then compare against
  >   passed-in `Date.getTime()` converted back to millis
  > - DECIMAL: `DecimalSerializer.staticDeserialize(bytes.bytes, bytes.offset)`;
  >   allocates BigDecimal (acknowledged perf gap). Use `BigDecimal.compareTo()`
  >   (not `equals()`). Convert passed-in value via `BigDecimal.valueOf()`
  >   for doubles.
  > - BINARY: compare byte arrays with `Arrays.compare()`
  > - LINK: read clusterId + clusterPosition via `readOptimizedLink()`;
  >   for `compare()`, return `OptionalInt.empty()` (ordering undefined).
  >   For `isEqual()`, compare both components.
  >
  > Excluded types (always fall back): EMBEDDED, EMBEDDEDLIST, EMBEDDEDSET,
  > EMBEDDEDMAP, LINKLIST, LINKSET, LINKMAP, LINKBAG.
  >
  > Unit tests: all 7 types, edge cases (empty string perf gap documented,
  > DATE timezone, DECIMAL scale differences, LINK equality, null values).
  >
  > **Files**: `InPlaceComparator.java` (extend), `InPlaceComparatorNonNumericTest.java` (new)

- [ ] Step 3: InPlaceResult enum + EntityImpl comparison methods
  > Add the `InPlaceResult` enum and two new public methods to EntityImpl:
  > `isPropertyEqualTo(String, Object)` and `comparePropertyTo(String, Object)`.
  >
  > Key design points:
  > - `InPlaceResult { TRUE, FALSE, FALLBACK }` — replaces `Optional<Boolean>`
  >   per review finding A3
  > - `isPropertyEqualTo` returns `InPlaceResult`
  > - `comparePropertyTo` returns `OptionalInt` (empty = fallback)
  > - Both methods share the same dispatch logic (see state matrix above)
  > - Call `checkForBinding()` first (T3)
  > - Check `status == STATUS.LOADED` (R2)
  > - Check `propertyAccess` (D6)
  > - Properties map path: `containsKey` (not `get`), navigate EntityEntry
  >   (`exists()`, null value, null type → fallback) (R1, A4)
  > - Deserialized comparison: type-aware, same conversion as InPlaceComparator,
  >   NOT `Objects.equals` (T1)
  > - Source path: check encryption (R6), null iClass (R7), collation on
  >   BinaryField (T7), delegate to InPlaceComparator
  > - `deserializeField()` returning null → fallback (covers zero-length fields
  >   and non-comparable types) (A7, T6)
  >
  > Unit tests using save→reload pattern to preserve `source`:
  > - All dispatch paths (properties null, entry present/absent/deleted,
  >   source null, status checks)
  > - Guard paths (encryption, collation, propertyAccess)
  > - Partially deserialized entity (one property read, another compared in-place)
  > - Schema-less entity
  >
  > **Files**: `InPlaceResult.java` (new), `EntityImpl.java` (modify),
  > `EntityImplInPlaceComparisonTest.java` (new)

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
