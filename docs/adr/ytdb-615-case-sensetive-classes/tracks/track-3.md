# Track 3: Test fixes

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/3 complete)
- [ ] Track-level code review

## Base commit
`4eb3a2d4d9`

## Reviews completed
- [x] Technical

## Steps

- [x] Step 1: Fix wrong-case class name lookups and equalsIgnoreCase in tests module
  - [x] Context: safe
  > **What was done:** Fixed SchemaTest.checkSchema() — changed 8 `getClass("whiz")`
  > / `getClass("WHIZ")` calls to `getClass("Whiz")` and 2 `equalsIgnoreCase("Account")`
  > to `equals("Account")`. Fixed SQLSelectTest — changed 9 `equalsIgnoreCase` calls
  > to `equals()` with correct-case class names ("Profile", "Animal").
  >
  > **What was discovered:** EntityTreeTest line 67 uses `select from profile`
  > (lowercase) in SQL but still passes — the SQL execution layer resolves class
  > names independently of the schema map key lookup, so SQL queries with
  > wrong-case class names don't fail. This means the plan's assumption that
  > "SQL queries must use exact-case class names" may not hold for the FROM clause.
  > Not a blocker for this track (test-only changes), but worth investigating
  > if SQL case behavior matters later. Also confirmed: the old wrong-case
  > `assert` lookups didn't fail because the schema layer lowercased inputs before
  > the case-sensitive change, not because `-ea` was disabled.
  >
  > **Key files:** SchemaTest.java (modified), SQLSelectTest.java (modified)

- [x] Step 2: Fix equalsIgnoreCase in core module test files
  - [x] Context: safe
  > **What was done:** Changed `equalsIgnoreCase` to `equals` in
  > HookReadTest.java (line 29, schema class name comparison) and
  > StorageBackupMTStateTest.java (line 324, generated class name comparison).
  > Both were cosmetic — neither test relied on case-insensitive behavior.
  >
  > **Key files:** HookReadTest.java (modified), StorageBackupMTStateTest.java (modified)

- [ ] Step 3: Write deferred test scenarios TC2/TC3/TC4 from Track 2 code review
  - TC2: Add tests for `isAllClasses()` guard in `Index.isLabelSecurityDefined`
    and `IndexManagerEmbedded.checkSecurityConstraintsForIndexCreate` — verify
    wildcard column-security rules work with the new `equals()` filter.
  - TC3: Add test for `DatabaseImport.importIndexes()` `equals` check on
    `EXPORT_IMPORT_INDEX_NAME`.
  - TC4: Add index name preservation test across session reload (create →
    persist → reload → case-sensitive lookup).
  Add tests to `CaseSensitiveClassNameTest` or appropriate existing test
  classes in the core module.
