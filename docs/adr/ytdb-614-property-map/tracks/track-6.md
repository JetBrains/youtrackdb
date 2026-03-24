# Track 6: Integration testing and backward compatibility verification

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed
- [x] Technical (9 findings: 0 blocker, 4 should-fix, 5 suggestion — all accepted)

## Key review decisions
- **Round-trip duplication**: Skip — already covered by Track 4's RecordSerializerBinaryV2RoundTripTest
- **Mixed-version DB-level**: Skip — serializer-layer mixed-version tests from Track 4 are sufficient
- **Gremlin integration**: Covered by TinkerPop Cucumber suite (~1900 scenarios) running against V2
- **Binary comparator**: Test V2.deserializeField() + BinaryComparatorV0 (no V1 comparator exists)
- **New test class**: Don't create — add to existing test classes per CLAUDE.md
- **DbTestBase**: Use for all database-backed tests (supports disk storage in CI)

## Steps

- [ ] Step 1: Schema-aware V2 round-trip and stress tests
  > Add to `RecordSerializerBinaryV2RoundTripTest`:
  > - Schema-aware round-trip: create class with typed properties (5+ properties
  >   including string, integer, double, boolean, datetime), serialize via V2,
  >   deserialize, verify values. Tests V2's schema-aware `writePropertyName()`
  >   and `readNameAndType()` code paths (global property ID encoding).
  > - 100+ properties round-trip: entity with ~100 mixed-type properties,
  >   verify all values survive serialization.
  > - Long property names: properties with names of ~200-500 characters,
  >   verify hash function handles long UTF-8 names correctly.
  > - Schema-aware partial deserialization: add to `RecordSerializerBinaryV2PartialTest`
  >   — hash lookup for schema-aware properties.
  >
  > **Target files:** `RecordSerializerBinaryV2RoundTripTest.java`,
  > `RecordSerializerBinaryV2PartialTest.java`

- [ ] Step 2: Link type V2 round-trip and collection tests
  > Add database-backed tests for link types (require real RIDs from persisted
  > records). Add to `RecordSerializerBinaryV2RoundTripTest` (which extends
  > `DbTestBase`):
  > - Single LINK property: create target entity, link from source, serialize,
  >   deserialize, verify RID.
  > - LINKLIST: entity with a list of links to multiple target entities.
  > - LINKSET: entity with a set of links.
  > - LINKMAP: entity with a map of string→link.
  > - Mixed entity: combine LINK, LINKLIST, and regular properties in one entity.
  > - Add `deserializeField` test for LINK type in V2 to
  >   `RecordSerializerBinaryV2PartialTest`.
  >
  > **Target files:** `RecordSerializerBinaryV2RoundTripTest.java`,
  > `RecordSerializerBinaryV2PartialTest.java`

- [ ] Step 3: Database lifecycle and binary comparator correctness
  > Database lifecycle tests: persist entities to disk, close database, reopen,
  > verify all properties read back correctly. Tests the full V2 path through
  > storage layer. Add to `RecordSerializerBinaryVersionDispatchTest`:
  > - Create entities with various V2 property types, commit, close DB, reopen,
  >   read entities back, verify all values.
  > - Update an entity (modify properties), re-persist, verify re-serialization
  >   in V2 format.
  > - Binary comparator correctness: serialize two entities with V2, call
  >   `deserializeField()` for shared field, use `BinaryComparatorV0.isEqual()`
  >   and `BinaryComparatorV0.compare()` to verify correct comparison results.
  >
  > **Target files:** `RecordSerializerBinaryVersionDispatchTest.java`
