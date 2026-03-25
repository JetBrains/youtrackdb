# Track 10: Reduce GC pressure in V2 serialization path

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review

## Base commit
`9bff614bf9`

## Reviews completed
- [x] Technical
- [x] Risk

## Steps

- [x] Step 1: Eliminate per-property BytesContainer allocation in serializePropertyEntry
  > **What was done:** Added `BytesContainer.reset()` method that zeroes the used
  > region and resets offset. Modified `serializeEntity()` to create one scratch
  > `BytesContainer tempBuffer` and pass it through `serializeLinearMode()`,
  > `serializeHashTableMode()`, and `serializePropertyEntry()`. Each property
  > call does `tempBuffer.reset()` instead of `new BytesContainer()`. Added
  > `testReset_keepsBackingArrayResetsOffsetAndZeros` and
  > `testReset_multipleReuseCycles_noStaleDataLeaks` to BytesContainerTest.
  >
  > **What was discovered:** V1 delegate serializers (via `serializeValue`)
  > allocate space in BytesContainer without filling every byte, relying on
  > zero-initialized memory. A naive `reset()` that only sets `offset = 0`
  > causes "Invalid version of link map" corruption during database creation
  > because stale bytes from a previous property's serialization leak through.
  > Fix: `reset()` must zero `[0, offset)` before resetting.
  >
  > **Key files:** `BytesContainer.java` (modified), `RecordSerializerBinaryV2.java`
  > (modified), `BytesContainerTest.java` (modified)

- [x] Step 2: Merge intermediate arrays in buildHashTable
  > **What was done:** Eliminated `slotHash8[]` intermediate array by writing
  > hash8 directly into `slotArray[slot * SLOT_SIZE]` during linear probing.
  > Sentinel initialization simplified to `Arrays.fill(slotArray, (byte) 0xFF)`
  > since all sentinel bytes (EMPTY_HASH8=0xFF, EMPTY_OFFSET=0xFFFF) are 0xFF.
  > Kept `buildHashTable()` and `HashTableResult` for testability (20+ unit
  > tests depend on the public API). `propertyKvOffsets[]` retained — needed
  > for slot offset backpatching.
  >
  > **What changed from the plan:** Did not inline `buildHashTable()` into
  > `serializeHashTableMode()` or remove `HashTableResult` — preserving 20+
  > existing unit tests outweighs the allocation cost of one small wrapper
  > object per entity. Focus shifted to eliminating `slotHash8[]` array.
  >
  > **Key files:** `RecordSerializerBinaryV2.java` (modified)

- [x] Step 3: Eliminate double UTF-8 encoding for schema-less properties
  > **What was done:** Added `@Nullable byte[] preEncodedName` parameter to
  > `serializePropertyEntry()`. In hash table mode, passes `nameBytes[i]`
  > (already computed for hash table construction). When non-null and property
  > is schema-less, writes name bytes directly via VarIntSerializer + arraycopy,
  > skipping the second `getBytes(UTF_8)` call. Linear mode passes null.
  > Added `roundTrip_hashTableMode_unicodePropertyNames` test with 13 multi-byte
  > UTF-8 property names (CJK, Cyrillic, accented Latin, Greek).
  >
  > **Key files:** `RecordSerializerBinaryV2.java` (modified),
  > `RecordSerializerBinaryV2RoundTripTest.java` (modified)

- [x] Step 4: Run `-prof gc` benchmark locally, verify allocation reduction
  > **What was done:** Ran `RecordSerializerBenchmark` with `-prof gc` locally
  > (JMH 1-warmup, 2-measurement, 1-fork) at property counts 5, 13, and 50.
  >
  > **Results — gc.alloc.rate.norm (B/op), serialize path:**
  >
  > | Properties | V1 | V2 | Ratio |
  > |---|---|---|---|
  > | 5 (linear) | 592 | 536 | 0.91× (V2 wins) |
  > | 13 (hash table) | 1,232 | 2,048 | 1.66× |
  > | 50 (hash table) | 3,960 | 6,944 | 1.75× |
  >
  > At 5 properties (linear mode), V2 allocates less than V1 thanks to
  > tempBuffer reuse. At 13+ (hash table mode), the extra ~1.7× is inherent
  > hash table construction overhead (nameBytes[], slotArray, slotPropertyIndex,
  > propertyKvOffsets, HashTableResult). No further code changes — remaining
  > gap is structural.
  >
  > **Key files:** `RecordSerializerBenchmark.java` (unchanged)
