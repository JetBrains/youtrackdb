# Track 6 Technical Review

## Summary

No blockers found. Track is feasible but significantly overlaps with existing
test coverage from Tracks 2 and 4. Scope should be narrowed to genuine gaps.

## Findings

### Finding T1 [suggestion]
**Location**: Track 6 — "Round-trip: serialize V2 → deserialize V2 for all property types"
**Issue**: Already comprehensively covered by `RecordSerializerBinaryV2RoundTripTest`
(all 13+ property types, empty/single/many properties, null values, collections,
embedded entities). Adding duplicate tests would not increase coverage.
**Proposed fix**: Skip round-trip duplication. Focus on property types NOT yet covered:
LINK, LINKBAG, LINKLIST, LINKSET, LINKMAP — these require persisted records with
real RIDs and are genuinely integration-level.

### Finding T2 [suggestion]
**Location**: Track 6 — "Mixed-version: V1 records coexist with V2 records"
**Issue**: `RecordSerializerBinaryVersionDispatchTest` already tests mixed V1/V2
at the serializer level. True database-level mixed version test is impractical
since `CURRENT_RECORD_VERSION` is now 1 — no API exists to force V1 writes.
**Proposed fix**: Acknowledge serializer-layer mixed-version tests are sufficient.
Skip database-level mixed version testing.

### Finding T3 [should-fix]
**Location**: Track 6 — "Schema-aware and schema-less properties in the same entity"
**Issue**: V2-specific tests (RoundTrip, Partial) only use schema-less entities.
Schema-aware encoding uses global property IDs (negative varints) — a different
code path. While parameterized `EntitySchemalessBinarySerializationTest` covers
this, a dedicated V2 schema-aware test would provide additional confidence.
**Proposed fix**: Add at least one test with a schema class + typed properties
that exercises V2's schema-aware branch of `writePropertyName()`/`readNameAndType()`.

### Finding T4 [suggestion]
**Location**: Track 6 — "Binary comparator equivalence: V0 and V1"
**Issue**: Track 5 was skipped — no `BinaryComparatorV1` exists. V2's
`getComparator()` returns `BinaryComparatorV0`. The "V0 vs V1" comparison is moot.
**Proposed fix**: Test binary comparator correctness with V2-serialized records:
`V2.deserializeField()` + `BinaryComparatorV0.isEqual()`.

### Finding T5 [should-fix]
**Location**: Track 6 — "query via Gremlin"
**Issue**: TinkerPop Cucumber feature tests (~1900 scenarios) already run against
V2 as the default serializer, covering Gremlin integration comprehensively.
**Proposed fix**: Acknowledge Cucumber suite covers Gremlin. Only add V2-specific
Gremlin tests if they cover behaviors Cucumber doesn't (e.g., 50+ property entities).

### Finding T6 [should-fix]
**Location**: Track 6 — "max properties (~100+), very long property names"
**Issue**: Existing tests go up to 50 properties (round-trip) and 100 (seed search
only). No full round-trip for 100+ properties. No test for very long property names.
**Proposed fix**: Add round-trip test with ~100 properties and test with property
names of ~200-500 characters.

### Finding T7 [should-fix]
**Location**: Track 6 — "Must pass with `-Dyoutrackdb.test.env=ci`"
**Issue**: Track 2's persist-reload test uses `DatabaseType.MEMORY` explicitly.
New lifecycle tests should use `DbTestBase` infrastructure for CI disk support.
**Proposed fix**: Ensure new lifecycle tests extend `DbTestBase`.

### Finding T8 [suggestion]
**Location**: Track 6 — "Create a dedicated `RecordSerializerBinaryV2Test`"
**Issue**: Four V2 test classes already exist. Creating another would be redundant.
**Proposed fix**: Add tests to existing classes per CLAUDE.md: "Prefer adding tests
to existing test classes when the change fits their scope."

### Finding T9 [suggestion]
**Location**: Track 6 overall scope
**Issue**: Extensive coverage from Tracks 2 and 4 reduces genuine gaps to:
1. Schema-aware V2 round-trip
2. Link types (LINK, LINKLIST, LINKSET, LINKMAP, LINKBAG) with real RIDs
3. 100+ properties round-trip
4. Very long property names
5. Binary comparator correctness with V2 `deserializeField()`
6. Database lifecycle (persist, close, reopen, update, delete)
**Proposed fix**: Narrow scope to ~2-3 steps covering these gaps.

## Decisions

- **T1 (suggestion)**: ACCEPTED — skip duplicate round-trip tests, focus on link types
- **T2 (suggestion)**: ACCEPTED — serializer-level mixed-version tests are sufficient
- **T3 (should-fix)**: ACCEPTED — add schema-aware V2 test
- **T4 (suggestion)**: ACCEPTED — test binary comparison with V2 fields, not V0 vs V1
- **T5 (should-fix)**: ACCEPTED — rely on Cucumber suite, add targeted V2 Gremlin test only if needed
- **T6 (should-fix)**: ACCEPTED — add 100+ property and long name tests
- **T7 (should-fix)**: ACCEPTED — use DbTestBase for lifecycle tests
- **T8 (suggestion)**: ACCEPTED — add to existing test classes
- **T9 (suggestion)**: ACCEPTED — narrow to ~2-3 steps

## Gate: PASS
