# Track 7: Redesign V2 hash table — bucketized cuckoo with 3-tier routing

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [ ] Track-level code review (1/3 iterations)

## Base commit
`4220e2356e`

## Reviews completed
- [x] Technical (10 findings: 0 blocker, 5 should-fix, 5 suggestion — all accepted)
- [x] Risk (10 findings: 0 blocker, 7 should-fix, 3 suggestion — all accepted)
- [x] Adversarial (10 findings: 0 blocker, 3 should-fix, 7 suggestion — all accepted)

## Key review decisions
- **T1-T4 blockers downgraded**: Describe implementation work already covered by plan, not plan flaws
- **R3 blocker downgraded**: Current deserialization comments assume perfect hashing — this is exactly what Track 7 rewrites
- **A3 blocker downgraded**: MurmurHash3 with different seeds produces independent outputs; XOR changes seed input. Same approach as DPDK. Add collision pattern tests.
- **A9 blocker downgraded**: Power-of-two rounding acknowledged in plan. Absolute space cost small (192 bytes max).
- **Wire format incompatibility**: Accept same version byte (1). No perfect hash V2 records exist in production.
- **Threshold 12**: Accept as reasonable default. Track 8 validates with JMH benchmarks.
- **Deterministic construction**: No RNG needed. "Random walk" refers to displacement pattern, not actual randomness. Eviction picks first occupied slot in bucket.
- **Atomic format swap**: Serialize + all deserialize paths change in one step to avoid broken intermediate state.

## Steps

- [x] Step 1: Cuckoo hash table construction algorithm and utilities
  > Add new cuckoo construction methods alongside existing perfect hash code
  > (additive only — no existing code modified, all tests continue passing).
  >
  > **New methods in `RecordSerializerBinaryV2`:**
  > - `computeLog2NumBuckets(int propertyCount)` — bucket-count formula:
  >   `nextPowerOfTwo(ceil(N / (BUCKET_SIZE * 0.85)))`, min 1
  > - `buildCuckooTable(byte[][] propertyNameBytes, int log2NumBuckets)` —
  >   greedy placement + displacement chains + seed retry. Returns seed + bucket
  >   array bytes. Displacement max=500, seed retry max=10, capacity doubling
  >   if all seeds fail.
  > - Helper: `computeH2Seed(int seed)` — returns `seed ^ 0x85ebca6b`
  >
  > **New constants:**
  > - `BUCKET_SIZE = 4` (slots per bucket)
  > - `CUCKOO_XOR_CONSTANT = 0x85ebca6b` (h2 seed derivation)
  > - `MAX_EVICTIONS = 500` (displacement chain limit)
  > - `MAX_SEED_RETRIES = 10`
  >
  > **Unit tests in `RecordSerializerBinaryV2HashTableTest`:**
  > - `computeLog2NumBuckets_*()` — verify bucket count for various property
  >   counts (13, 20, 30, 50, 100)
  > - `buildCuckooTable_*()` — verify construction succeeds, all entries
  >   locatable via 2-bucket scan, determinism (same input = same output)
  > - `buildCuckooTable_similarPrefixNames()` — adversarial property names
  > - `buildCuckooTable_allCountsFrom13To60()` — comprehensive sweep
  >
  > **Target files:** `RecordSerializerBinaryV2.java`,
  > `RecordSerializerBinaryV2HashTableTest.java`
  >
  > **What was done:** Added bucketized cuckoo hashing construction utilities alongside
  > existing perfect hash code (additive only — no existing code modified). New methods:
  > `computeLog2NumBuckets()` (bucket-count formula targeting 85% load), `buildCuckooTable()`
  > (greedy placement + displacement chains + seed retry + capacity doubling),
  > `fibonacciBucketIndex()` (Fibonacci hashing at bucket granularity), `computeH2Seed()`
  > (h2 seed derivation via XOR with MurmurHash3 constant). Added `CuckooTableResult` value
  > class. 32 test methods covering construction correctness, adversarial inputs, boundary
  > transitions, capacity doubling, unicode names, sentinel verification, and overflow.
  >
  > **What was discovered:** Review found that deterministic slot-0 eviction can cause
  > ping-pong displacement cycles when items share bucket1. Fixed with round-robin eviction
  > (`evictions % BUCKET_SIZE`). Also found integer overflow risk in `computeLog2NumBuckets`
  > for huge property counts — fixed with long arithmetic.
  >
  > **Key files:** `RecordSerializerBinaryV2.java` (modified),
  > `RecordSerializerBinaryV2HashTableTest.java` (modified)

- [x] Step 2: Atomic format swap — serialization + all deserialization paths
  > Wire cuckoo construction into serialization. Update ALL deserialization
  > paths for bucket-based format. Change threshold. Remove old perfect hash
  > code. This must be atomic — serialize + deserialize change together so
  > all round-trip tests pass.
  >
  > **Serialization changes (`serializeHashTableMode()`):**
  > - Call `buildCuckooTable()` instead of `findPerfectHashSeed()`
  > - Write bucket array: `numBuckets * BUCKET_SIZE * SLOT_SIZE` bytes
  > - Slot backpatching uses bucket-aware addressing:
  >   `slotPos = slotArrayStart + (bucket * BUCKET_SIZE + slotInBucket) * SLOT_SIZE`
  >
  > **Deserialization changes (all 4 paths):**
  > - `deserialize()` (full): update KV region skip:
  >   `bytes.skip(numBuckets * BUCKET_SIZE * SLOT_SIZE)` (trivial)
  > - `deserializePartialHashTable()`: 2-bucket scanning with hash8 fast-reject.
  >   Compute h1, scan bucket1 (4 slots). On miss, compute h2, scan bucket2.
  > - `deserializeFieldHashTable()`: Same 2-bucket scanning logic.
  > - `getFieldNamesHashTable()`: Skip correct byte count for bucket array.
  >
  > **Other changes:**
  > - `LINEAR_MODE_THRESHOLD` from 2 to 12
  > - Remove `findPerfectHashSeed()` and `computeLog2Capacity()`
  > - Rename variables: `log2Capacity` → `log2NumBuckets`, `capacity` → computed
  >   from `numBuckets * BUCKET_SIZE`
  > - Update comments: remove "perfect hash" references, document cuckoo semantics
  >
  > **Wire format note:** Cuckoo format is wire-incompatible with perfect hash
  > V2 from Tracks 4-6. Safe because no V2 records exist in production. Any
  > test databases from Tracks 4-6 must be deleted before running Track 7 tests.
  >
  > **Target files:** `RecordSerializerBinaryV2.java`
  >
  > **What was done:** Atomic format swap replacing perfect hash with bucketized cuckoo in all
  > serialize/deserialize paths. Serialization now uses buildCuckooTable() with offset backpatching
  > via slotPropertyIndex. Four deserialization paths updated: full (skip bucket array), partial
  > (2-bucket scan with hash8 fast-reject), field (2-bucket scan), getFieldNames (skip bucket
  > array). LINEAR_MODE_THRESHOLD raised from 2 to 12 (3-tier routing). Removed dead code:
  > fibonacciIndex(), computeLog2Capacity(), findPerfectHashSeed(), and associated constants.
  > Updated readAndValidateLog2NumBuckets to accept log2=0. Updated tests for new threshold.
  > Net reduction of 255 lines.
  >
  > **What was discovered:** Old perfect hash tests and round-trip tests that used 3 properties
  > needed updating to 13+ properties to trigger hash table mode with the new threshold. The
  > test updates were necessarily bundled with the production code changes since they reference
  > removed methods (computeLog2Capacity, fibonacciIndex, findPerfectHashSeed). This absorbed
  > most of Step 3's test deletion work.
  >
  > **What changed from the plan:** Step 3 (hash table test suite updates) was partially absorbed
  > into this step because removing production methods requires removing their test references
  > in the same commit for compilation. Step 3 still has remaining work: threshold boundary tests,
  > cuckoo-specific edge cases.
  >
  > **Key files:** `RecordSerializerBinaryV2.java` (modified),
  > `RecordSerializerBinaryV2HashTableTest.java` (modified),
  > `RecordSerializerBinaryV2RoundTripTest.java` (modified)

- [x] Step 3: Hash table test suite updates
  > Replace perfect hash test methods with cuckoo equivalents. Update capacity
  > computation expectations for new bucket-count formula.
  >
  > **Replace in `RecordSerializerBinaryV2HashTableTest`:**
  > - Delete all `findPerfectHashSeed_*()` test methods (9 methods)
  > - Update all `computeLog2Capacity_*()` tests → `computeLog2NumBuckets_*()`:
  >   new expected values per bucket-count formula
  > - Add threshold boundary tests:
  >   - `serializeDeserialize_twelveProperties_usesLinearMode()`
  >   - `serializeDeserialize_thirteenProperties_usesCuckooMode()`
  >
  > **Add cuckoo-specific edge cases:**
  > - Displacement chain scenario: property names that collide in h1 bucket
  > - Seed retry scenario: adversarial property set that fails with seed 0
  > - Verify determinism: same entity serialized twice produces identical bytes
  >
  > **Target files:** `RecordSerializerBinaryV2HashTableTest.java`
  >
  > **What was done:** Added threshold boundary tests (12-property linear mode, 13-property
  > cuckoo mode) and serialization determinism test. Updated round-trip test class Javadoc for
  > new threshold. Most of the originally planned Step 3 work (deleting perfect hash tests,
  > updating capacity computation tests) was already absorbed by Step 2's atomic format swap.
  >
  > **Key files:** `RecordSerializerBinaryV2RoundTripTest.java` (modified)

- [x] Step 4: Cuckoo integration tests and coverage verification
  > Add tests verifying cuckoo-specific behavior in round-trip and partial
  > deserialization paths. Verify coverage targets.
  >
  > **2-bucket lookup verification:**
  > - Test with 15+ properties where some land in h2 bucket — verify
  >   `deserializePartial()` and `deserializeField()` find all properties
  > - Hash8 fast-reject: verify properties with different hash8 prefixes are
  >   correctly rejected without following offset
  >
  > **Mixed-tier embedded entity test:**
  > - Small parent (2 properties, linear) with large embedded child (20+
  >   properties, cuckoo) — verify round-trip preserves embedded entity
  >
  > **Large entity stress tests:**
  > - 50-property cuckoo round-trip
  > - 100-property cuckoo round-trip
  > - Verify load factor stays within expected range
  >
  > **Collision pattern tests (A3 mitigation):**
  > - Properties with common prefixes ("property_1" through "property_50")
  > - Properties with identical lengths
  > - Verify dual hash independence: no displacement chain > 10 steps
  >
  > **Coverage verification:**
  > - Run coverage gate for changed code
  > - Fix any gaps to meet 85% line / 70% branch
  >
  > **Target files:** `RecordSerializerBinaryV2RoundTripTest.java`,
  > `RecordSerializerBinaryV2PartialTest.java`,
  > `RecordSerializerBinaryV2HashTableTest.java`
  >
  > **What was done:** Added 7 cuckoo-mode tests for partial deserialization, field lookup, and
  > field names with 13-50 properties exercising the 2-bucket scan path. Added mixed-tier embedded
  > entity test (2-property linear parent + 15-property cuckoo child). Renamed 10 test methods
  > from hashMode → linearMode to match the new threshold (12). Fixed stale boundary test (was
  > 3-property, now 13-property). Ran coverage gate: 91.9% line / 85.4% branch — passes targets.
  >
  > **What was discovered:** Many existing tests that claimed "hashMode" with 3-5 properties were
  > actually testing linear mode after the threshold change. All were functionally valid (they test
  > the EntitySerializer contract, not hash table internals) but names were misleading.
  >
  > **Key files:** `RecordSerializerBinaryV2PartialTest.java` (modified)
