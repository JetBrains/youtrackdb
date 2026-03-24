# Track 5 Technical Review

## Review scope
Track 5: BinaryComparatorV1 — hash-based field lookup for binary comparison

## Findings

### Finding T1 [skip]
**Location**: Track 5 description — "New `BinaryComparatorV1` class implementing
`BinaryComparator`. Uses the hash table directory to locate a field's serialized
bytes in O(1)."
**Issue**: The `BinaryComparator` interface does not perform field location. Its
three methods are:
- `isEqual(db, BinaryField, BinaryField)` — compares two already-located fields
- `compare(db, BinaryField, BinaryField)` — compares two already-located fields
- `isBinaryComparable(PropertyTypeInternal)` — type-level check

Field location is performed by `EntitySerializer.deserializeField()`, which V2
already implements with O(1) hash table lookup (see
`RecordSerializerBinaryV2.deserializeFieldHashTable()`). The call chain is:

1. SQL operators call `serializer.deserializeField(fieldName)` → returns `BinaryField`
2. SQL operators call `serializer.getComparator().isEqual(bf1, bf2)` or `.compare(bf1, bf2)`

Step 1 is already O(1) in V2. Step 2 is type-level byte comparison (1000+ lines
of type conversion logic in `BinaryComparatorV0`) that is identical regardless
of how the field was located.

Creating `BinaryComparatorV1` would duplicate all of `BinaryComparatorV0`'s type
comparison logic with zero behavioral difference. V2 already correctly returns
`BinaryComparatorV0` from `getComparator()`.

**Evidence**:
- `BinaryComparatorV0.java` contains no field location methods (no references to
  `deserializeField`, `findField`, `locateField`, or similar)
- `RecordSerializerBinaryV2.deserializeFieldHashTable()` already performs O(1)
  hash-based field lookup
- Track 4's review decision (T6/R9) already noted: "Comparator: V2 returns
  BinaryComparatorV0 as stub; Track 5 replaces" — but this replacement is
  unnecessary since the comparator's role is value comparison, not field location

**Proposed fix**: Skip Track 5. The O(1) binary comparison path is already
complete: V2's `deserializeField()` locates fields in O(1), and
`BinaryComparatorV0` compares the located values. No new comparator class is
needed.

If Track 6 (integration testing) includes binary comparator equivalence tests,
those can verify that V2's `deserializeField()` + `BinaryComparatorV0` produces
correct results without needing a new `BinaryComparatorV1` class.

## Summary
**Recommendation: SKIP** — Track 5's stated goal (O(1) field lookup for binary
comparison) is already achieved by V2's `deserializeField()` implementation from
Track 4. Creating a new `BinaryComparatorV1` would be a no-op wrapper around
identical logic.
