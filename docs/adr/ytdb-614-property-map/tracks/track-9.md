# Track 9: Replace cuckoo with linear probing

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [x] Track-level code review (1/3 iterations)

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

- [x] Steps 1-4: Replace cuckoo with linear probing (merged — tightly coupled)
  > **What was done:** Replaced bucketized cuckoo hashing with plain linear probing
  > in `RecordSerializerBinaryV2`. Removed all cuckoo infrastructure: `BUCKET_SIZE`,
  > `CUCKOO_XOR_CONSTANT`, `MAX_EVICTIONS`, `MAX_SEED_RETRIES`, `computeH2Seed()`,
  > `buildCuckooTable()`, `cuckooInsert()`, `fibonacciBucketIndex()`,
  > `scanBucketForPartialDeserialize()`, `scanBucketForFieldDeserialize()`,
  > `CuckooTableResult`. Added: `computeLog2Capacity()`, `buildHashTable()`,
  > `fibonacciSlotIndex()`, `HashTableResult`. Construction is now a single O(n)
  > pass with one hash function and sequential probing. All 6 read-path methods
  > rewritten for linear probe with defensive `probes < capacity` bounds. All
  > tests rewritten: hash table test class (32→34 tests), round-trip binary layout
  > test, partial deserialization comments, version dispatch comments, benchmark
  > Javadoc. Net: -204 lines (500 added, 704 removed).
  >
  > **What was discovered:** Steps 1-4 could not be implemented independently —
  > removing cuckoo constants from construction made the read paths and tests
  > uncompilable. The decomposition assumed construction and read-path could be
  > separated, but they share constants (`BUCKET_SIZE`, `computeH2Seed`,
  > `fibonacciBucketIndex`).
  >
  > **What changed from the plan:** 4 steps merged into 1 commit. All planned
  > work was completed — no scope was dropped.
  >
  > **Key files:**
  > - `RecordSerializerBinaryV2.java` (modified)
  > - `RecordSerializerBinaryV2HashTableTest.java` (modified — near-complete rewrite)
  > - `RecordSerializerBinaryV2RoundTripTest.java` (modified)
  > - `RecordSerializerBinaryV2PartialTest.java` (modified — comments)
  > - `RecordSerializerBinaryVersionDispatchTest.java` (modified — comments)
  > - `RecordSerializerBenchmark.java` (modified — Javadoc)
  >
  > **Review fixes applied:**
  > - `readAndValidateLog2Capacity` now rejects `log2Capacity < 1` (5 agents flagged)
  > - `validatePropertyCount` tightened to 2047 (guarantees empty slot for probe termination)
  > - Added `n < capacity` and `slotArray.length` assertions to `buildHashTable`
  > - Added test for `buildHashTable` overflow (AssertionError)
  > - Added test for `log2Capacity=0` corruption detection
  > - Added test for 1280-property max capacity boundary
  > - Fixed remaining stale cuckoo references in comments
  >
  > **Coverage:** 90.7% line / 82.8% branch (passing 85%/70% thresholds)
