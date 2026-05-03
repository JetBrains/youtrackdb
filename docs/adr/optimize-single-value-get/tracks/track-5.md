# Track 5: Zero-deserialization `compareInByteBuffer` for `IndexMultiValuKeySerializer`

## Progress
- [x] Review + decomposition
- [x] Step implementation (2/2 complete)
- [x] Track-level code review (1/3 iterations)

## Base commit
`9ba1a0cc98`

## Reviews completed
- [x] Technical
- [x] Risk

## Key review findings

**T1/R1 (delegation vs. inline) — REJECTED after deeper investigation.**
Both reviewers suggested delegating to per-field serializer `compareInByteBuffer`
overrides via `serializerFactory.getObjectSerializer(PropertyTypeInternal)`. This
does NOT work because the factory returns different serializers than what
`IndexMultiValuKeySerializer` actually uses:
- STRING: factory → `StringSerializer`, actual → `UTF8Serializer`
- LINK: factory → `LinkSerializer`, actual → `CompactedLinkSerializer`
- DATE: factory → `DateSerializer`, actual → inline `putLong(date.getTime())`
- FLOAT: factory → `FloatSerializer`, actual → inline `putInt(floatToIntBits())`
- DOUBLE: factory → `DoubleSerializer`, actual → inline `putLong(doubleToLongBits())`

Additionally, FLOAT/DOUBLE are stored as raw int/long bits — delegating to
IntegerSerializer/LongSerializer would give wrong ordering for negative values.

**Correct approach: inline type switch** (as originally planned). For each
`PropertyTypeInternal`, use inline comparison that matches the exact serialization
format used by `IndexMultiValuKeySerializer`. Delegation is only safe for types
where the on-disk format exactly matches a serializer with a zero-alloc override
AND the comparison semantics are correct:
- LONG, DATE, DATETIME: `LongSerializer.INSTANCE.compareInByteBuffer()` ✓
- INTEGER: `IntegerSerializer.INSTANCE.compareInByteBuffer()` ✓
- SHORT: `ShortSerializer.INSTANCE.compareInByteBuffer()` ✓
- STRING: `UTF8Serializer.INSTANCE.compareInByteBuffer()` ✓
  (not StringSerializer — must use direct reference, not factory)
- BINARY: `BinaryTypeSerializer.INSTANCE.compareInByteBuffer()` ✓

Types requiring inline:
- FLOAT: `Float.compare(intBitsToFloat(page), intBitsToFloat(search))`
- DOUBLE: `Double.compare(longBitsToDouble(page), longBitsToDouble(search))`
- BOOLEAN, BYTE: `Byte.compare(page, search)`
- LINK: inline clusterId short + clusterPosition long reconstruction
- DECIMAL: fallback to BigDecimal deserialization

**R2 (ID space difference) — noted.** Nulls detected via `typeId < 0`, not
`NullSerializer.ID`. Serializer resolution via direct reference per type, not
factory lookup.

**WAL-aware variant**: Same structure, but LongSerializer/IntegerSerializer/
ShortSerializer/UTF8Serializer do NOT have `compareInByteBufferWithWALChanges`
overrides — they fall through to the default (deserialize). For the WAL path,
use inline reads via `walChanges.getLongValue/getIntValue/getShortValue/
getByteValue` for primitives. For STRING, fall through to deserialization
(rare path). For field size advancement in the WAL path, use WAL-aware size
methods.

## Steps

- [x] Step: Add `compareInByteBuffer` + `compareInByteBufferWithWALChanges` to `IndexMultiValuKeySerializer` with unit tests
  - [x] Context: unavailable
  > **What was done:** Added `compareInByteBuffer()` and
  > `compareInByteBufferWithWALChanges()` overrides to
  > `IndexMultiValuKeySerializer` with field-by-field zero-deserialization
  > comparison. Non-WAL path delegates to LongSerializer/IntegerSerializer/
  > ShortSerializer/UTF8Serializer/BinaryTypeSerializer for matching types;
  > inlines FLOAT/DOUBLE (intBitsToFloat/longBitsToDouble), BOOLEAN/BYTE
  > (Byte.compare), LINK (inline clusterId + compacted position comparison),
  > DECIMAL (BigDecimal fallback). WAL path inlines all primitive reads via
  > walChanges methods; falls back to deserialization for STRING/LINK/BINARY/
  > DECIMAL. Added helpers: `compareField`, `compareFieldWAL`,
  > `compareLinkInline`, `readCompactedPosition`/`readCompactedPositionNative`,
  > `compareDecimalFallback`, `compareFieldWALFallback`, `getKeySizeNative`,
  > `getKeySizeInByteBufferWithWAL`. Code review added type mismatch
  > assertions and LINK edge case tests (position 0, asymmetric numberSize,
  > non-zero buffer offsets). 37 tests total.
  >
  > **Key files:** `IndexMultiValuKeySerializer.java` (modified),
  > `IndexMultiValuKeySerializerCompareTest.java` (new)

- [x] Step: Switch `getVisible()` to `find(byte[])` path in `BTree.java`
  - [x] Context: unavailable
  > **What was done:** Added `serializeNativeAsWhole()` after `buildSearchKey()`
  > in `getVisible()`. Both `getVisibleOptimistic()` and `getVisiblePinned()`
  > now receive `byte[] serializedKey` for `bucket.find(byte[], ...)` and
  > `findBucketSerialized()`, plus `K searchKey` for `scanLeafForVisible()`'s
  > `userKeyPrefixMatches()`. All 96 targeted tests pass (27 BTreeGetVisibleTest
  > + 37 IndexMultiValuKeySerializerCompareTest + 11 SnapshotIsolationIndexesGetTest
  > + 21 SnapshotIsolationIndexesUniqueTest).
  >
  > **Key files:** `BTree.java` (modified)
