# Track 9 Risk Review

## Finding R1 [should-fix]
**Location**: 4 deserialization methods in `RecordSerializerBinaryV2.java`
**Issue**: Slot array size formula (`numBuckets * BUCKET_SIZE * SLOT_SIZE`) appears in `deserializeHashTableModeFull`, `deserializePartialHashTable`, `deserializeFieldHashTable`, and `getFieldNamesHashTable`. Missing even one causes silent data corruption (reads from wrong offset). Likelihood: medium; Impact: critical.
**Proposed fix**: Enumerate all 4 methods in track steps. Round-trip tests will catch offset miscalculation.

## Finding R2 [should-fix]
**Location**: `RecordSerializerBinaryV2HashTableTest.java` (510 lines, 18 tests)
**Issue**: Entire test class is cuckoo-specific (106 references to cuckoo symbols). Will fail to compile after Track 9. Scope is a complete rewrite, not an update.
**Proposed fix**: Plan test rewrite as its own step (~200 lines of new test code).

## Finding R3 [should-fix]
**Location**: `RecordSerializerBinaryV2RoundTripTest.java` lines 440-520
**Issue**: `serializedBytes_hashTableMode_containsSeedAndBuckets()` test directly inspects binary format using cuckoo-specific helpers (`computeH2Seed`, `fibonacciBucketIndex` for two buckets, `findInBucket`). Must be rewritten with linear probing verification. Risk of tautological test if it mirrors production code too closely.
**Proposed fix**: Rewrite to assert structural properties: no duplicate slots, all offsets valid, every property reachable via linear probe from Fibonacci index.

## Finding R4 [suggestion]
**Location**: Track 9 construction
**Issue**: Actual load factor varies due to power-of-two rounding (40.6% to 62.5%). At exact 62.5% with unlucky clustering, max probe length could reach 10-12.
**Proposed fix**: Add test that inserts 50-100 properties and asserts max probe length < 15 as hash quality regression guard.

## Finding R5 [should-fix]
**Location**: Binary format backward compatibility
**Issue**: Header byte semantics change from `log2NumBuckets` (total slots = numBuckets * 4) to `log2Capacity` (total slots = capacity). V2 data from Track 7/8 development becomes unreadable. Only affects development databases (V2 never released).
**Proposed fix**: Note format invalidation. Ensure tests create fresh databases. Acceptable since V2 was never released.

## Finding R6 [blocker]
**Location**: Linear probing read path (`scanBucketForPartialDeserialize`, `scanBucketForFieldDeserialize`)
**Issue**: Cuckoo has bounded search (max 8 slots). Linear probing scans until empty slot — if construction bug causes 100% load factor, read path enters infinite loop. No safeguard mentioned in track plan.
**Proposed fix**: Add defensive loop bound: `for (int probes = 0; probes < capacity; probes++)`. Zero cost in normal case, prevents infinite loops if invariant violated. Standard practice for linear probing.

## Finding R7 [suggestion]
**Location**: `RecordSerializerBenchmark.java` Javadoc
**Issue**: Benchmark Javadoc references "cuckoo mode" — outdated after Track 9.
**Proposed fix**: Update to "linear probing hash table mode".
