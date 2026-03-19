# Track 1: EdgeKey timestamp and tombstone value model

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/3 complete)
- [ ] Track-level code review

## Base commit
`eccd0de9b3`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Fix EdgeKeySerializer.doGetObjectSize offset bug and add `ts` to EdgeKey
  > Fix the pre-existing offset tracking bug in `EdgeKeySerializer.doGetObjectSize(byte[], int,
  > BinarySerializerFactory)` where the second field reads from `startPosition` instead of
  > `startPosition + size`. Then add `long ts` as the 4th field to `EdgeKey` — update
  > constructor, `compareTo` (order: ridBagId → targetCollection → targetPosition → ts),
  > `equals`, `hashCode`, and `toString`. Update `EdgeKeySerializer` to serialize/deserialize
  > `ts` using `LongSerializer` in all 7 code paths (byte[] serialize/deserialize/size,
  > ByteBuffer position-based/offset-based, WALChanges, object-based size). Update all
  > production call sites: `IsolatedLinkBagBTreeImpl` (~15 sites, use `0L` for ts where no
  > real timestamp exists yet; use `Long.MIN_VALUE`/`Long.MAX_VALUE` for boundary keys),
  > `LinkCollectionsBTreeManagerShared` (2 boundary sites), `EdgeKeySerializer` deserialization
  > returns (4 sites). Update test call sites: `EdgeKeySerializerTest` (4 sites), `BTreeTestIT`
  > (~20 sites, use `0L` for ts). Add new `EdgeKeySerializerTest` cases: round-trip with
  > varying value sizes (small, large, mixed, boundaries), regression test for the offset bug
  > fix.
  >
  > **Files**: `EdgeKey.java`, `EdgeKeySerializer.java`, `IsolatedLinkBagBTreeImpl.java`,
  > `LinkCollectionsBTreeManagerShared.java`, `EdgeKeySerializerTest.java`, `BTreeTestIT.java`
  >
  > **What was done:** Added `long ts` as the 4th field to `EdgeKey` with correct comparison,
  > equality, and hashing. Updated `EdgeKeySerializer` across all 7 code paths to
  > serialize/deserialize `ts` using `LongSerializer`. Fixed the pre-existing offset bug in
  > `doGetObjectSize` where `IntSerializer` size was read from `startPosition` instead of
  > `startPosition + size`. Updated ~40 call sites across production and test code. Added 6
  > new serializer test cases including boundary values and a regression test that verifies
  > size consistency across all 5 size computation paths.
  >
  > **What was discovered:** Code review caught that `spliteratorEntriesBetween` and
  > `loadEntriesMajor` needed `Long.MIN_VALUE`/`Long.MAX_VALUE` for ts bounds (not `0L`)
  > to correctly capture all timestamps in range queries. Fixed in review.
  >
  > **Key files:** `EdgeKey.java` (modified), `EdgeKeySerializer.java` (modified),
  > `IsolatedLinkBagBTreeImpl.java` (modified), `LinkCollectionsBTreeManagerShared.java`
  > (modified), `EdgeKeySerializerTest.java` (modified), `BTreeTestIT.java` (modified)

- [ ] Step 2: Add `tombstone` field to LinkBagValue and update LinkBagValueSerializer
  > Add `boolean tombstone` field to the `LinkBagValue` record as the 4th component. Update
  > the compact constructor validation (no assertion needed for boolean — it's always valid).
  > Update `LinkBagValueSerializer` to serialize the tombstone as a single byte (0/1) appended
  > after `secondaryPosition` in all code paths (byte[] serialize/deserialize/size, ByteBuffer
  > position-based/offset-based, WALChanges, object-based size). Update all production call
  > sites: `LinkBagUpdateSerializationOperation` (1 site, pass `false`),
  > `LinkBagValueSerializer` deserialization returns (4 sites). Update test call sites:
  > `LinkBagValueTest` (~10 sites, pass `false`), `BTreeTestIT` (~10 sites, pass `false`).
  > Add new `LinkBagValueTest` cases: round-trip with `tombstone=true` and `tombstone=false`,
  > verify serialized size includes the tombstone byte, test all serializer code paths.
  >
  > **Files**: `LinkBagValue.java`, `LinkBagValueSerializer.java`,
  > `LinkBagUpdateSerializationOperation.java`, `LinkBagValueTest.java`, `BTreeTestIT.java`

- [ ] Step 3: Add comprehensive EdgeKey ordering and LinkBagValue tombstone tests
  > Add focused unit tests validating the new EdgeKey comparison semantics and tombstone
  > edge cases that go beyond simple serialization round-trips. For EdgeKey: test that
  > `compareTo` orders by `ts` last (two keys with same 3-tuple but different `ts` compare
  > correctly), test that `equals`/`hashCode` distinguish keys with different `ts`, test
  > natural ordering with `TreeMap`/`Collections.sort` to verify B-tree-compatible ordering.
  > For LinkBagValue: test that tombstone entries with identical data fields but
  > `tombstone=true` vs `tombstone=false` are distinguished by `equals`, verify `toString`
  > includes tombstone status. Add to existing test classes (`EdgeKeySerializerTest` or a
  > new `EdgeKeyTest`, and `LinkBagValueTest`).
  >
  > **Files**: `EdgeKeySerializerTest.java` or new `EdgeKeyTest.java`,
  > `LinkBagValueTest.java`
