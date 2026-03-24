# Track 9: Replace cuckoo with linear probing

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/4 complete)
- [ ] Track-level code review

## Base commit
`3fa79abf49`

## Reviews completed
- [x] Technical
- [x] Risk

## Review decisions

Findings addressed in step decomposition:

- **R6 (blocker):** Defensive loop bound (`probes < capacity`) added to all linear probing scan methods. Covered in Step 2.
- **T1/T8:** Capacity formula rewrite + max bounds recalibration. `MAX_LOG2_CAPACITY` increased to 11 (2048 slots = ~1280 properties at 0.625 load). Covered in Step 1.
- **T2/R1:** All 4 deserialization methods that compute slot array size are enumerated in Step 2.
- **T3:** Empty slot = terminate search (not skip) — highlighted as key correctness concern in Step 2.
- **T4:** `CuckooTableResult` → `HashTableResult`, naming cleanup. Covered in Step 1.
- **T5/R2/R3:** Hash table test class near-complete rewrite + round-trip binary layout test rewrite. Covered in Step 3.
- **T6:** `LINEAR_MODE_THRESHOLD` stays at 12 — the 48-byte overhead increase at N=13 is negligible and doesn't justify adding a 4th tier or testing boundary changes.
- **T7:** Compound empty-slot check preserved (hash8==0xFF AND offset==0xFFFF).
- **R4:** Max probe length assertion added as test in Step 3.
- **R5:** V2 format from Track 7/8 is invalidated — acceptable since V2 was never released. Tests use fresh databases.
- **R7:** Benchmark Javadoc update covered in Step 4.

## Steps

- [ ] Step 1: Rewrite hash table construction — replace cuckoo with linear probing
  > Replace `buildCuckooTable()` / `cuckooInsert()` / `CuckooTableResult` with
  > linear probing construction. Rewrite `computeLog2NumBuckets()` →
  > `computeLog2Capacity()` with formula `nextPowerOfTwo(ceil(N * 8 / 5))`.
  > Replace `fibonacciBucketIndex()` → `fibonacciSlotIndex()` (same math, renamed).
  > Remove cuckoo constants (`BUCKET_SIZE`, `CUCKOO_XOR_CONSTANT`, `MAX_EVICTIONS`,
  > `MAX_SEED_RETRIES`, `computeH2Seed()`). Rename `MAX_LOG2_NUM_BUCKETS` →
  > `MAX_LOG2_CAPACITY` (value 11 = 2048 slots max). Rename `CuckooTableResult` →
  > `HashTableResult` with `log2Capacity` field. Update `serializeHashTableMode()`
  > to call the new construction. Update class-level Javadoc. Update
  > `validatePropertyCount()` upper bound.
  >
  > **Key files:** `RecordSerializerBinaryV2.java`

- [ ] Step 2: Rewrite all read-path methods — linear probing lookup with defensive bounds
  > Replace cuckoo 2-bucket scan with linear probing in all 6 methods:
  >
  > 1. `deserializeHashTableModeFull()` — update slot array skip:
  >    `numBuckets * BUCKET_SIZE * SLOT_SIZE` → `capacity * SLOT_SIZE`
  > 2. `deserializePartialHashTable()` — remove h2Seed/bucket2, replace with
  >    linear probe from Fibonacci index. Remove `scanBucketForPartialDeserialize()`.
  > 3. `deserializeFieldHashTable()` — same linear probe replacement. Remove
  >    `scanBucketForFieldDeserialize()`.
  > 4. `getFieldNamesHashTable()` — update slot array skip (same as #1)
  > 5. `readAndValidateLog2NumBuckets()` — rename to `readAndValidateLog2Capacity()`,
  >    update validation bound.
  >
  > **Critical correctness:**
  > - Empty slot (hash8==0xFF AND offset==0xFFFF) **terminates** the probe
  >   sequence (key absent). NOT skipped like in cuckoo.
  > - Defensive loop bound: `probes < capacity` on all probe loops to prevent
  >   infinite loops if construction invariant is violated (R6 blocker fix).
  > - Compound empty-slot check preserved (T7).
  >
  > **Key files:** `RecordSerializerBinaryV2.java`

- [ ] Step 3: Rewrite hash table tests + update round-trip/partial tests
  > Near-complete rewrite of `RecordSerializerBinaryV2HashTableTest`:
  > - Remove: `computeH2Seed` tests, cuckoo-math `computeLog2NumBuckets` tests,
  >   `buildCuckooTable` tests, `assertAllCuckooEntriesLocatable` helper
  > - Add: `computeLog2Capacity` tests with linear probing math, linear probing
  >   construction + locatability assertions (hash → Fibonacci index → scan
  >   forward → find match), edge cases (single property at threshold, capacity
  >   boundary, wrap-around), max probe length assertion (50-100 properties,
  >   max probes < 15) (R4)
  >
  > Update `RecordSerializerBinaryV2RoundTripTest`:
  > - Rewrite `serializedBytes_hashTableMode_containsSeedAndBuckets()` to verify
  >   linear probing layout: seed + log2Capacity header, flat slot array, every
  >   property reachable via linear probe, no duplicate slots, all offsets valid
  >
  > Update `RecordSerializerBinaryV2PartialTest`:
  > - Verify existing partial deserialization tests pass (should work as-is since
  >   they test through the public API, not internal format)
  >
  > **Key files:** `RecordSerializerBinaryV2HashTableTest.java`,
  > `RecordSerializerBinaryV2RoundTripTest.java`,
  > `RecordSerializerBinaryV2PartialTest.java`

- [ ] Step 4: Update integration tests and benchmark references
  > Update `RecordSerializerBenchmark.java` Javadoc: "cuckoo" → "linear probing"
  > (R7). Verify all integration tests pass:
  > - `RecordSerializerBinaryVersionDispatchTest` (version dispatch)
  > - `EntitySchemalessBinarySerializationTest` (parameterized across versions)
  > - Integration tests from Track 6 and Track 8 (tier boundary, binary
  >   comparator equivalence, database lifecycle)
  >
  > Run `./mvnw -pl core clean test` to verify full module passes.
  > Run spotless. Check coverage with `coverage-gate.py`.
  >
  > **Key files:** `RecordSerializerBenchmark.java`
