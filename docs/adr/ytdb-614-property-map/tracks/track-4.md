# Track 4: RecordSerializerBinaryV2 — hash map serialization format

## Progress
- [x] Review + decomposition
- [x] Step implementation (4/4 complete)
- [ ] Track-level code review

## Base commit
`3dfc5c533c`

## Reviews completed
- [x] Technical (9 findings: 4 blocker, 3 should-fix, 2 suggestion — all resolved)
- [x] Risk (12 findings: 2 blocker, 7 should-fix, 3 suggestion — all resolved)
- [x] Adversarial (10 findings: 3 blocker, 4 should-fix, 3 suggestion — all resolved)

## Key review decisions
- **Empty slot sentinel**: Use 0xFF/0xFFFF instead of 0x00/0x0000 (T3/R2/A2)
- **Hash formula**: Fibonacci hashing consistently everywhere (A1)
- **Comparator**: V2 returns BinaryComparatorV0 as stub; Track 5 replaces (T6/R9)
- **Seed search**: Iterative loop, max capacity 1024, throw if exceeded (A3/R1)
- **64 KB overflow**: Throw exception if KV region exceeds 64 KB (T2/R10)
- **Corruption detection**: Offset bounds checking in deserialize (A9)
- **Serialization order**: KV entries in entity iteration order (A7)
- **Hash prefix (D3)**: Keep 1-byte hash8 — low cost, corruption detection (A5)
- **Linear mode (D4)**: Keep for ≤2 properties — space savings justify it (A6)

## Steps

- [x] Step 1: Hash table core utilities — Fibonacci hash, capacity computation, seed search
  > **What was done:** Created `RecordSerializerBinaryV2` class implementing
  > `EntitySerializer` with static utility methods: `fibonacciIndex()`,
  > `computeLog2Capacity()`, `findPerfectHashSeed()`, `computeHash8()`, and
  > sentinel constants. All interface methods are stubs (UnsupportedOperationException)
  > except `getComparator()` which returns `BinaryComparatorV0` per review decision.
  > 31 tests in `RecordSerializerBinaryV2HashTableTest`.
  >
  > **What was discovered:** The initial `computeHash8` implementation had an
  > incorrect sentinel collision guard checking offset==0 instead of offset==0xFFFF.
  > Code review caught this — simplified to a single-arg method since the 0xFFFF
  > sentinel offset is reserved and never assigned to real entries, making collision
  > impossible. Also simplified seed search cleanup from per-element re-hashing to
  > `Arrays.fill()`.
  >
  > **Key files:** `RecordSerializerBinaryV2.java` (new),
  > `RecordSerializerBinaryV2HashTableTest.java` (new)

- [x] Step 2: V2 serialization and full deserialization (round-trip)
  > **What was done:** Implemented serialize/deserialize/serializeWithClassName/
  > deserializeWithClassName in RecordSerializerBinaryV2. Linear mode (<=2 props)
  > writes entries sequentially. Hash table mode (>2) builds perfect hash table
  > with slot backpatching. Full deserialization skips hash table and reads KV
  > entries linearly. serializeValue/deserializeValue handle EMBEDDED, EMBEDDEDSET,
  > EMBEDDEDLIST, EMBEDDEDMAP directly (recursive V2 format); all other types
  > delegate to V1. 29 round-trip tests.
  >
  > **What was discovered:** V1's value serialization methods for EMBEDDED and
  > collection types recursively call `this.serializeValue()`, meaning delegation
  > to a V1 instance would serialize nested entities in V1 format instead of V2.
  > Had to override serializeValue/deserializeValue for all recursive types
  > (EMBEDDED, EMBEDDEDSET, EMBEDDEDLIST, EMBEDDEDMAP) in V2, with collection
  > read/write methods that call `this.serializeValue()` for correct recursion.
  >
  > **Key files:** `RecordSerializerBinaryV2.java` (modified),
  > `RecordSerializerBinaryV2RoundTripTest.java` (new)

- [x] Step 3: V2 partial deserialization, field lookup, and field names
  > **What was done:** Implemented deserializePartial (O(1) per field via hash
  > table), deserializeField (returns BinaryField for binary comparison), and
  > getFieldNames (linear scan of KV entries). All three handle the linear/hash
  > mode threshold. Code review added hash8 fast-reject for corruption detection,
  > extracted readFieldName() helper to eliminate duplication, and fixed bounds
  > assertion. 18 tests covering single/multi-field partial, non-existent fields,
  > null values, all binary-comparable types for deserializeField, and field name
  > extraction for various entity sizes.
  >
  > **Key files:** `RecordSerializerBinaryV2.java` (modified),
  > `RecordSerializerBinaryV2PartialTest.java` (new)

- [x] Step 4: V2 registration in RecordSerializerBinary and backward compatibility
  > **What was done:** Registered V2 in RecordSerializerBinary: init() creates
  > EntitySerializer[2] with V1 at [0] and V2 at [1], CURRENT_RECORD_VERSION=1.
  > 16 tests in RecordSerializerBinaryVersionDispatchTest covering registration,
  > version bytes, backward compatibility, mixed-version round-trips (all common
  > types with concrete value assertions), partial deserialization with absent-field
  > checks, and a rawContainsProperty guard regression test.
  > EntitySchemalessBinarySerializationTest now runs 68 tests (34 per version).
  >
  > **What was discovered:** V2's full deserialization was unconditionally
  > overwriting properties already present in the entity, causing 40+ test failures.
  > Root cause: when an entity is partially deserialized (a field is loaded), the
  > field is modified in memory, and then full deserialization is triggered (via
  > EntityImpl.setDirty() → checkForProperties()), V2 was overwriting the in-memory
  > modification with stale serialized data. V1 has a `rawContainsProperty()` guard
  > that skips already-present properties — V2 was missing this. Added the same guard.
  >
  > **Key files:** `RecordSerializerBinary.java` (modified),
  > `RecordSerializerBinaryV2.java` (modified),
  > `RecordSerializerBinaryVersionDispatchTest.java` (new)
