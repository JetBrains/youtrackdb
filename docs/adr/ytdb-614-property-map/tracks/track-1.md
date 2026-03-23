# Track 1: Remove dead EntitySerializerDelta

## Progress
- [x] Review + decomposition
- [x] Step implementation (1/1 complete)
- [ ] Track-level code review

## Base commit
`0cf4c0ff4e`

## Reviews completed
- [x] Technical

## Steps
- [x] Step: Move getFieldType() to RecordSerializerBinaryV1 and delete EntitySerializerDelta
  > **What was done:** Moved `getFieldType(EntityEntry)` from `EntitySerializerDelta`
  > to `RecordSerializerBinaryV1` as a `private static` method (narrowed from
  > `protected static`). Updated the call site in `serializeValues()`. Deleted
  > `EntitySerializerDelta.java` (1,472 lines) and `EntitySerializerDeltaTest.java`.
  > All core tests pass.
  >
  > **Key files:** `RecordSerializerBinaryV1.java` (modified),
  > `EntitySerializerDelta.java` (deleted), `EntitySerializerDeltaTest.java` (deleted)
