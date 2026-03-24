# Track 4: RecordSerializerBinaryV2 — hash map serialization format

## Progress
- [x] Review + decomposition
- [ ] Step implementation (3/4 complete)
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

- [ ] Step 4: V2 registration in RecordSerializerBinary and backward compatibility
  Wire V2 into the version dispatch system and verify backward compatibility:

  **Registration** (RecordSerializerBinary modifications):
  - `init()`: allocate `new EntitySerializer[2]`, register V1 at [0], V2 at [1]
  - `CURRENT_RECORD_VERSION = 1` — new records written as V2
  - `getNumberOfSupportedVersions()` returns 2

  **Comparator stub**:
  - `RecordSerializerBinaryV2.getComparator()` returns `new BinaryComparatorV0()`
  - Track 5 will replace this with BinaryComparatorV1 for O(1) field lookup

  **Tests**:
  - **Backward compatibility**: Serialize entity as V1 (version byte 0), then
    deserialize via RecordSerializerBinary dispatcher — must still work after
    V2 is registered.
  - **Mixed version**: Create V1 record, create V2 record, deserialize both
    in same session. Verify both produce identical entity content.
  - **Parameterized tests**: Track 2's `EntitySchemalessBinarySerializationTest`
    automatically runs against V2 (version 1) since it iterates
    `getNumberOfSupportedVersions()`. All 12 partial deserialization contract
    tests must pass.
  - **Version byte verification**: Serialize with V2, check first byte is 1.
    Serialize with V1, check first byte is 0.
