# Track 3: Test fixes

## Progress
- [x] Review + decomposition
- [x] Step implementation (3/3 complete)
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

- [x] Step 3: Write deferred test scenarios TC2/TC3/TC4 from Track 2 code review
  - [x] Context: info
  > **What was done:** Added 5 new test methods to CaseSensitiveClassNameTest:
  > TC4 — index name round-trip across session reload (exact case survives,
  > wrong case not found, IndexDefinition preserves class name). TC2 — three
  > tests: wildcard security rule (`database.class.*.filtered`) blocks composite
  > index for any class; wrong-case specific rule (`secexact` vs `SecExact`)
  > does NOT block; exact-case specific rule (`SecBlock`) DOES block. TC3 —
  > export/import round-trip preserves index name and class name case in
  > IndexDefinition. Review fixes: strengthened exception message assertions
  > (TC2), added IndexDefinition verification after import (TC3), added
  > try/finally for safe import DB cleanup, closed export/import resources.
  >
  > **Key files:** CaseSensitiveClassNameTest.java (modified)
