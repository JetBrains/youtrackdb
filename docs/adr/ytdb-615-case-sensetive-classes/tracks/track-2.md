# Track 2: Index name and IndexManager — case-sensitive names

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/2 complete)
- [ ] Track-level code review

## Base commit
_(to be filled at Phase B start)_

## Reviews completed
- [x] Technical

## Steps

- [ ] Step 1: Remove toLowerCase() from ImmutableSchema index map and IndexManager classPropertyIndex
  Remove all `toLowerCase(Locale.ROOT)` calls for index-name and class-name
  map keys:
  - **ImmutableSchema**: constructor (line 111) — store index name as-is;
    `indexExists()` (line 241) — remove toLowerCase; `getIndexDefinition()`
    (line 246) — remove toLowerCase.
  - **IndexManagerAbstract**: `getIndexOnProperty()` (line 102) — remove
    toLowerCase from className key; `getClassIndex()` (lines 161, 167) —
    remove both toLowerCase calls (parameter and comparison);
    `addIndexInternalNoLock()` (line 261) — remove toLowerCase from put key.
  - **IndexManagerEmbedded**: `removeClassPropertyIndexInternal()` (lines
    573, 603, 605) — remove toLowerCase from get/remove/put on
    classPropertyIndex.
  - Add tests to `CaseSensitiveClassNameTest` (or new index-focused test)
    verifying: (a) index created on a class is retrievable by exact original-
    case index name, (b) classPropertyIndex lookup works with original-case
    class name, (c) index removal works correctly.
  Files: `ImmutableSchema.java`, `IndexManagerAbstract.java`,
  `IndexManagerEmbedded.java`, test file(s)

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
