# Track 9 Technical Review

## Finding T1 [should-fix]
**Location**: Track 9 "Capacity" bullet; `RecordSerializerBinaryV2.computeLog2NumBuckets()` line 129
**Issue**: The capacity formula changes fundamentally: from `nextPowerOfTwo(ceil(N/3.4))` (bucket count at 85% load) to `nextPowerOfTwo(ceil(N/0.625))` (slot count at 62.5% load). Example: N=50, cuckoo → log2=4 (16 buckets = 64 slots); linear probing → log2=7 (128 slots). The `MAX_LOG2_NUM_BUCKETS` constant (10) needs recalibration — with linear probing, log2=10 means 1024 slots (not 4096).
**Proposed fix**: Explicit step for rewriting `computeLog2NumBuckets` → `computeLog2Capacity` with formula `nextPowerOfTwo(ceil(N * 8 / 5))`. Recalculate max bounds.

## Finding T2 [should-fix]
**Location**: Track 9 "Lookup" bullet; `deserializeHashTableModeFull()` line 592-606, `getFieldNamesHashTable()` line 950-969
**Issue**: Track lists 3 methods for lookup rewrite but omits `deserializeHashTableModeFull()` and `getFieldNamesHashTable()`, both of which compute `numBuckets * BUCKET_SIZE * SLOT_SIZE` for slot array skipping.
**Proposed fix**: Add both methods to the change list. Change is `numBuckets * BUCKET_SIZE * SLOT_SIZE` → `capacity * SLOT_SIZE`.

## Finding T3 [should-fix]
**Location**: Track 9 "Lookup" bullet; `scanBucketForPartialDeserialize()` line 752
**Issue**: In cuckoo, empty slots within a bucket are skipped (`continue`). In linear probing, an empty slot terminates the search — this is the fundamental semantic change. If the implementer preserves the `continue` on empty slots, lookups for non-existent keys wrap around the entire table.
**Proposed fix**: Explicitly state: "Empty slot = key absent → return immediately (not found). Opposite of cuckoo where empty slots are skipped."

## Finding T4 [should-fix]
**Location**: `CuckooTableResult` line 309-321, `serializeHashTableMode()` line 401-472
**Issue**: `CuckooTableResult`, field names (`log2NumBuckets`), local variables (`cuckoo`, `numBuckets`), and class-level Javadoc all reference cuckoo-specific concepts. Leaving cuckoo naming creates maintenance confusion.
**Proposed fix**: Rename `CuckooTableResult` → `HashTableResult`, `log2NumBuckets` → `log2Capacity`, update Javadoc.

## Finding T5 [should-fix]
**Location**: `RecordSerializerBinaryV2HashTableTest.java` (510 lines, ~20 tests)
**Issue**: Entire test class is cuckoo-specific: `computeH2Seed` tests, `computeLog2NumBuckets` with cuckoo math, `buildCuckooTable` tests, `assertAllCuckooEntriesLocatable` helper. All ~20 tests need complete rewriting, not updating.
**Proposed fix**: Call out explicitly as a near-complete test rewrite. New tests: `computeLog2Capacity`, `fibonacciSlotIndex`, linear probing construction + locatability assertions.

## Finding T6 [suggestion]
**Location**: Track 9 "Capacity" bullet; D4 decision record
**Issue**: At N=13, linear probing uses 32 slots (96 bytes) vs cuckoo's 16 slots (48 bytes) — 2× slot overhead at threshold boundary. Consider bumping `LINEAR_MODE_THRESHOLD` to 16.
**Proposed fix**: Document decision to keep threshold at 12. At 16 properties the KV region (~480 bytes, ~7-8 cache lines) is borderline for linear scan.

## Finding T7 [suggestion]
**Location**: Empty slot check in cuckoo lookup line 752
**Issue**: Current compound check (`hash8==0xFF AND offset==0xFFFF`) is correct and must be preserved in linear probing since hash8=0xFF is valid (1/256 chance).
**Proposed fix**: No change needed, just note compound check is load-bearing for correctness.

## Finding T8 [should-fix]
**Location**: `validatePropertyCount()` line 1273-1285
**Issue**: Validation uses `1 << MAX_LOG2_NUM_BUCKETS` (=1024) as upper bound. With cuckoo that supports ~870 properties. With linear probing at 0.625 load, max drops to ~640 (1024 * 0.625). Need to increase `MAX_LOG2_CAPACITY` or document reduced max.
**Proposed fix**: Decide whether to increase max log2 to 11 (2048 slots = ~1280 properties) or document reduced max. Update `validatePropertyCount` accordingly.
