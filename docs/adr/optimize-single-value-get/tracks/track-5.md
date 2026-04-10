# Track 5: Zero-deserialization `compareInByteBuffer` for `IndexMultiValuKeySerializer`

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/2 complete)
- [ ] Track-level code review

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

- [ ] Step: Add `compareInByteBuffer` + `compareInByteBufferWithWALChanges` to `IndexMultiValuKeySerializer` with unit tests
  > Add both method overrides using an inline type switch per
  > `PropertyTypeInternal`. Non-WAL path delegates to existing serializer
  > `compareInByteBuffer` where the format and comparison semantics match
  > (LONG, DATE, DATETIME, INTEGER, SHORT, STRING, BINARY); inlines for
  > FLOAT, DOUBLE, BOOLEAN, BYTE, LINK, DECIMAL. WAL-aware path inlines
  > all primitive reads via `walChanges` methods; falls through to
  > deserialization for STRING, LINK, BINARY, DECIMAL.
  >
  > Add a private `getKeySizeNative(PropertyTypeInternal, byte[], int,
  > BinarySerializerFactory)` helper for advancing the search key offset
  > (mirrors existing `getKeySizeInByteBuffer` for page buffer side).
  >
  > Unit tests: serialize pairs of `CompositeKey` objects via
  > `serializeNativeObject`, compare via the new methods, verify result
  > matches `CompositeKey.compareTo()`. Cover:
  > - All 12 field types individually (single-field + LONG version)
  > - Null vs non-null, both-null, non-null vs null
  > - Prefix comparison (different key counts)
  > - Equal keys → 0
  > - Multi-field composite keys with mixed types
  > - FLOAT/DOUBLE edge cases: NaN, -0.0, negative values
  > - WAL-aware variant: same cases with `WALPageChangesPortion` overlay
  >
  > Files: `IndexMultiValuKeySerializer.java`, new test class in
  > `core/src/test/java/.../serialization/serializer/binary/impl/index/`

- [ ] Step: Switch `getVisible()` to `find(byte[])` path in `BTree.java`
  > Reverse Track 4a: add `serializeNativeAsWhole()` after existing
  > `preprocess()` in `getVisible()`. Change `getVisibleOptimistic()` and
  > `getVisiblePinned()` to accept both `byte[] serializedKey` (for
  > `bucket.find(byte[], ...)` and `findBucketSerialized()`) and
  > `K searchKey` (for `scanLeafForVisible()`'s `userKeyPrefixMatches()`).
  >
  > All existing `getVisible()` tests (59 tests from Tracks 2-3) and
  > `get()` tests must continue passing — no behavioral change, only the
  > binary search path changes from object-based to byte-based.
  >
  > Files: `BTree.java`
