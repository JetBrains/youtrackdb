# Track 3: Test fixes

## Progress
- [x] Review + decomposition
- [ ] Step implementation (0/3 complete)
- [ ] Track-level code review

## Base commit
`4eb3a2d4d9`

## Reviews completed
- [x] Technical

## Steps

- [ ] Step 1: Fix wrong-case class name lookups and equalsIgnoreCase in tests module
  Fix `SchemaTest.checkSchema()` Java assert statements that use wrong-case
  lookups (`"whiz"`, `"WHIZ"` → `"Whiz"`; `equalsIgnoreCase("Account")` →
  `equals("Account")`). Fix `SQLSelectTest` equalsIgnoreCase calls (7 sites)
  to use `equals("Profile")` / `equals("Animal")`. Investigate why wrong-case
  assert lookups don't fail despite `-ea` being enabled.

- [ ] Step 2: Fix equalsIgnoreCase in core module test files
  Change `HookReadTest.java:29` and `StorageBackupMTStateTest.java:324`
  from `equalsIgnoreCase` to `equals`. Both are cosmetic cleanup — neither
  test currently fails.

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
