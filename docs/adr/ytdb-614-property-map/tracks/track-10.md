# Track 10: Reduce GC pressure in V2 serialization path

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/4 complete)
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

- [ ] Step 2: Merge intermediate arrays in buildHashTable
  Eliminate the `slotHash8[]` and `slotPropertyIndex[]` intermediate arrays
  by writing directly into the `slotArray[]` during linear probing insertion.
  Also inline `buildHashTable()` into `serializeHashTableMode()` to eliminate
  the `HashTableResult` wrapper object and the separate `propertyKvOffsets[]`
  array.

  **What to do**:
  1. Inline `buildHashTable()` logic into `serializeHashTableMode()`:
     - After collecting `nameBytes[]` and `orderedFields[]`, allocate `slotArray`
       directly as `byte[capacity * SLOT_SIZE]` (filled with empty sentinels).
     - During linear probing insertion, write `hash8` directly into
       `slotArray[slot * SLOT_SIZE]` and store the property index in a
       local `slotPropertyIndex[]` (still needed for backpatch, but merged
       into the same loop instead of a separate method).
     - Remove `HashTableResult` class.
  2. Keep `buildHashTable()` as a package-private static method for unit tests
     but simplify its return: just `slotArray` + `slotPropertyIndex` without
     the wrapper object. Alternatively, test via round-trip serialization tests
     and remove the standalone method. Choose based on test coverage impact.
  3. Remove the redundant `slotHash8[]` array entirely — its values are written
     directly into `slotArray` positions.
  4. Run all existing V2 tests.

  **Key files**: `RecordSerializerBinaryV2.java`, `RecordSerializerBinaryV2Test.java`

- [ ] Step 3: Eliminate double UTF-8 encoding for schema-less properties
  In hash table mode, schema-less property names are UTF-8 encoded twice:
  once at line 317 (`fieldName.getBytes(UTF_8)` for hash computation) and
  again in `serializePropertyEntry()` via `writeString()` → `bytesFromString()`.
  Pass the pre-encoded `nameBytes[i]` into `serializePropertyEntry()` so it
  can write the name bytes directly instead of re-encoding.

  **What to do**:
  1. Add an overloaded `serializePropertyEntry()` that accepts an optional
     `byte[] preEncodedName` parameter (or null for linear mode / schema-aware).
  2. In `serializeHashTableMode()`, pass `nameBytes[i]` when calling
     `serializePropertyEntry()` for each property.
  3. In `serializePropertyEntry()`, when `preEncodedName != null` and the
     property is schema-less (`docEntry.property == null`), write the name
     bytes directly via `VarIntSerializer.write(bytes, preEncodedName.length)`
     + `System.arraycopy` instead of calling `writeString()`.
  4. Verify V1 delegate `serializeValue` only writes forward from
     `bytes.offset` (per R6) — trace through key type handlers.
  5. Run all V2 tests.

  **Key files**: `RecordSerializerBinaryV2.java`

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
