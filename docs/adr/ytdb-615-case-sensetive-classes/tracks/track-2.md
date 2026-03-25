# Track 2: Index name and IndexManager — case-sensitive names

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/2 complete)
- [ ] Track-level code review

## Base commit
`0eed0e98`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Remove toLowerCase() from ImmutableSchema index map and IndexManager classPropertyIndex
  - [x] Context: safe
  > **What was done:** Removed all `toLowerCase(Locale.ROOT)` calls from
  > index-name map keys in ImmutableSchema (constructor, indexExists,
  > getIndexDefinition) and from class-name keys in classPropertyIndex
  > (IndexManagerAbstract: getIndexOnProperty, getClassIndex,
  > addIndexInternalNoLock; IndexManagerEmbedded:
  > removeClassPropertyIndexInternal — get/remove/put). Removed unused
  > Locale imports from ImmutableSchema and IndexManagerAbstract. Added 8
  > tests to CaseSensitiveClassNameTest covering: exact-case index lookup
  > via ImmutableSchema, classPropertyIndex lookup, index removal cleanup,
  > getClassIndex, areIndexed, getClassIndexes, composite (multi-property)
  > index prefix lookup, and case-variant class index collision prevention.
  > **Key files:** ImmutableSchema.java (modified), IndexManagerAbstract.java
  > (modified), IndexManagerEmbedded.java (modified),
  > CaseSensitiveClassNameTest.java (modified)

- [ ] Step 2: Switch equalsIgnoreCase → equals in index-layer security and import code
  Change remaining `equalsIgnoreCase()` calls to `equals()`:
  - **Index.java**: `isLabelSecurityDefined()` (line 378) — security filter
    className comparison.
  - **IndexManagerEmbedded.java**: `checkSecurityConstraintsForIndexCreate()`
    (line 423) — security filter className comparison.
  - **DatabaseImport.java**: line 1386 — index name comparison with
    `EXPORT_IMPORT_INDEX_NAME` constant.
  - Add test verifying security-filtered index property check uses exact-case
    class name matching.
  Files: `Index.java`, `IndexManagerEmbedded.java`, `DatabaseImport.java`,
  test file(s)
