# Track 1: Remove dead EntitySerializerDelta

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/1 complete)
- [ ] Track-level code review

## Reviews completed
- [x] Technical

## Steps
- [ ] Step: Move getFieldType() to RecordSerializerBinaryV1 and delete EntitySerializerDelta
  > Move the `getFieldType(EntityEntry)` method from `EntitySerializerDelta` to
  > `RecordSerializerBinaryV1` as a `private static` method. Update the call site
  > at line 417 in `serializeValues()`. Delete `EntitySerializerDelta.java` and
  > `EntitySerializerDeltaTest.java`. Run spotless and verify compilation and tests.
  >
  > **Files to modify:**
  > - `RecordSerializerBinaryV1.java` — add `getFieldType()` as private static,
  >   update call site to use local method, remove import of `EntitySerializerDelta`
  > - `EntitySerializerDelta.java` — delete
  > - `EntitySerializerDeltaTest.java` — delete
