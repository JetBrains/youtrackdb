# Track 8: Integration testing and write performance verification

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review (1/3 iterations)

## Base commit
`4c37b4af7c`

## Reviews completed
- [x] Technical (7 findings: 0 blocker, 3 should-fix, 4 suggestion — all accepted)
- [x] Risk (5 findings: 0 blocker, 2 should-fix, 3 suggestion — all accepted)

## Key review decisions
- **Scope reduction**: ~80% of planned integration tests already covered by Tracks 6-7. Both reviews converge on reducing to 2 steps: targeted gap-filling integration tests + JMH benchmark.
- **No "update" of existing tests**: Track 7's atomic format swap means existing Track 6 tests already run against cuckoo format.
- **JMH placement**: In `core/src/test/` alongside existing benchmarks (not `jmh-ldbc`). JMH already a test dependency in core.
- **Benchmark comparison**: V1 vs V2-cuckoo (not vs removed perfect hash). 3 property-count scenarios: linear (5), cuckoo (20), cuckoo (50).
- **Disk storage lifecycle**: Existing lifecycle test uses `DatabaseType.MEMORY`. Add disk-storage variant or update to respect `youtrackdb.test.env=ci`.

## Steps

- [x] Step 1: Tier-boundary DB-lifecycle integration tests
  > **What was done:** Added 5 integration tests across 2 test files:
  > - 3 disk-storage DB lifecycle tests in `RecordSerializerBinaryVersionDispatchTest`:
  >   linear tier (2 props), cuckoo tier (15 props with mixed types), and cuckoo
  >   partial deserialization (15 props, getProperty triggers 2-bucket scan).
  > - 2 cuckoo-mode binary comparator tests in `RecordSerializerBinaryV2PartialTest`:
  >   double field and string field comparison with 15 properties each.
  >
  > **What was discovered:** Dimensional review (10 agents) found 5 actionable items:
  > disk DB cleanup needed finally blocks, hasSize() assertions lacked name verification,
  > cuckoo disk test used homogeneous string types, string comparator test lacked null
  > guards, partial deser test should verify non-existent property returns null. All fixed.
  >
  > **Key files:** `RecordSerializerBinaryVersionDispatchTest.java` (modified),
  > `RecordSerializerBinaryV2PartialTest.java` (modified)

- [x] Step 2: JMH serialization benchmark — V1 vs V2 comparison
  > **What was done:** Created `RecordSerializerBenchmark.java` with 6 benchmark
  > methods (serializeV1/V2, deserializeFullV1/V2, deserializePartialV1/V2)
  > parameterized across 4 property counts (5 linear, 13 cuckoo boundary, 20
  > cuckoo moderate, 50 cuckoo large). Uses mixed property types and targets
  > worst-case V1 scan for partial deserialization.
  >
  > **What was discovered:** Dimensional review (10 agents) found 3 actionable items:
  > open transaction causing unbounded entity accumulation across iterations (fixed
  > with @Setup(Level.Iteration) reset), per-iteration String[] allocation noise
  > (pre-allocated), and missing tier-boundary property count 13 (added to @Param).
  >
  > **Key files:** `RecordSerializerBenchmark.java` (new)
