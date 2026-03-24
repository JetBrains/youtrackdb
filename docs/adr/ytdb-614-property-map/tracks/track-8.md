# Track 8: Integration testing and write performance verification

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

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

- [ ] Step 1: Tier-boundary DB-lifecycle integration tests
  > Add DB-lifecycle tests covering all 3 tiers through persist→close→reopen
  > on disk storage. Existing `dbLifecycle_persistCloseReopenVerify` covers
  > tier 2 (7 properties, in-memory only). Add:
  >
  > **In `RecordSerializerBinaryVersionDispatchTest`:**
  > - `dbLifecycle_diskStorage_linearTier_persistCloseReopenVerify()` — 2
  >   properties (tier 1), using disk storage via `DatabaseType.DISK`. Verify
  >   properties survive full page flush and reopen.
  > - `dbLifecycle_diskStorage_cuckooTier_persistCloseReopenVerify()` — 15+
  >   properties (tier 3), using disk storage. Verify cuckoo-serialized
  >   records survive persist→close→reopen cycle on actual disk pages.
  > - `dbLifecycle_diskStorage_cuckooTier_partialDeserialization()` — persist
  >   entity with 15+ properties to disk, reopen, access a single property
  >   via `getProperty()` (triggers partial deserialization through cuckoo
  >   2-bucket scan on disk-loaded pages).
  >
  > **In `RecordSerializerBinaryV2PartialTest` (or RoundTripTest):**
  > - `binaryComparator_cuckooMode_deserializeFieldWithComparatorV0()` — 15+
  >   properties, serialize with V2 cuckoo, call `deserializeField()`, verify
  >   `BinaryComparatorV0.isEqual()` and `compare()`. Extends existing 3-property
  >   binary comparator test from Track 6 to cuckoo tier.
  >
  > **Target files:** `RecordSerializerBinaryVersionDispatchTest.java`,
  > `RecordSerializerBinaryV2PartialTest.java` or `RecordSerializerBinaryV2RoundTripTest.java`

- [ ] Step 2: JMH serialization benchmark — V1 vs V2 comparison
  > Create a new JMH benchmark class in `core/src/test/` that measures
  > serialize and deserializePartial throughput for V1 vs V2 at different
  > property counts.
  >
  > **New file:** `RecordSerializerBenchmark.java` in
  > `core/src/test/java/.../serialization/serializer/record/binary/`
  >
  > **Benchmark scenarios (parameterized by property count):**
  > - 5 properties (V2 linear mode, V1 comparison baseline)
  > - 20 properties (V2 cuckoo mode, moderate)
  > - 50 properties (V2 cuckoo mode, large entity)
  >
  > **Benchmark methods:**
  > - `serializeV1(state)` — serialize entity with V1
  > - `serializeV2(state)` — serialize entity with V2
  > - `deserializePartialV1(state)` — V1 partial deserialize (single field)
  > - `deserializePartialV2(state)` — V2 partial deserialize (single field)
  > - `deserializeFullV1(state)` — V1 full deserialize
  > - `deserializeFullV2(state)` — V2 full deserialize
  >
  > **State setup:** Pre-build `EntityImpl` with N string/int/double/boolean
  > properties. Pre-serialize bytes for deserialization benchmarks. Use
  > `BinarySerializerFactory` and `RecordSerializerBinaryV1`/`V2` directly
  > (no DB session needed — pure serializer-level benchmark).
  >
  > **JMH configuration:** Follow existing benchmark patterns in core —
  > `@BenchmarkMode(Mode.AverageTime)`, `@OutputTimeUnit(TimeUnit.NANOSECONDS)`,
  > `@Fork(2)`, `@Warmup(iterations = 3)`, `@Measurement(iterations = 5)`.
  > Include `main()` method for standalone execution.
  >
  > **Expected results:** V2 write path should be comparable to V1 (cuckoo
  > construction is O(n), same as V1's linear serialization). V2 read path
  > (partial deserialization) should be faster than V1 for 20+ properties
  > (O(1) hash lookup vs O(n) linear scan).
  >
  > **Target files:** `RecordSerializerBenchmark.java` (new)
