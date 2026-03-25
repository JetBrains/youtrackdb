# Track 10: Reduce GC pressure in V2 serialization path

## Progress
- [x] Review + decomposition
- [ ] Step implementation (3/4 complete)
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

- [ ] Step 4: Run `-prof gc` benchmark locally, verify allocation reduction
  Run the `RecordSerializerBenchmark` with JMH's GC profiler (`-prof gc`)
  locally to measure allocation rates (bytes/op) for V1 vs V2 before and
  after optimizations. This validates that the changes from Steps 1-3
  actually reduced allocations.

  **What to do**:
  1. Build the benchmark JAR: `./mvnw -pl core clean package -DskipTests`
     (the benchmark is in `core/src/test/java` and compiled into a JMH JAR
     via the maven-shade plugin).
  2. Run locally with `-prof gc`:
     `java -jar core/target/benchmarks.jar RecordSerializerBenchmark -prof gc`
  3. Compare V2 `gc.alloc.rate.norm` (bytes/op) against V1 baseline.
     V2 should not allocate significantly more than V1 per operation.
  4. If allocation is still significantly higher, profile to identify
     remaining sources and add targeted fixes.
  5. Record results in the step episode. CCX33 run deferred to a separate
     task for final throughput validation (per R5/T5: allocation rates are
     hardware-independent).

  **Key files**: `RecordSerializerBenchmark.java`

  **Note**: This step has no code changes if Steps 1-3 are sufficient.
  If additional fixes are needed based on profiling, they are applied
  in this step.
