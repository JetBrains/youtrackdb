# Track 3 Technical Review

## Review scope
Track 3: Test fixes — update tests that rely on case-insensitive class/index
name lookup behavior, plus deferred test scenarios TC2/TC3/TC4 from Track 2.

## Findings

### Finding T1 [suggestion]
**Location**: Track 3 description — all listed tests
**Issue**: Running the full test suite (core: 910 tests, tests: 1300 tests,
embedded: 1931 tests) shows ALL tests pass after Track 1 and Track 2 changes.
No tests are currently failing due to case-sensitivity. The `SchemaTest.checkSchema()`
uses Java `assert` statements (not JUnit assertions) with wrong-case lookups
(`"whiz"`, `"WHIZ"` for a class created as `"Whiz"`). Despite `-ea` being in
the argLine, these tests pass — the root cause needs investigation during
Phase B. Regardless, the wrong-case lookups should be fixed for correctness.
**Proposed fix**: Fix the assert statements to use correct case. Investigate
why wrong-case lookups don't fail during Phase B implementation.

### Finding T2 [should-fix]
**Location**: `tests/src/test/java/.../SQLSelectTest.java`
**Issue**: Contains 7 `equalsIgnoreCase` calls comparing `getSchemaClassName()`
against `"profile"` and `"animal"`. These should use `equals()` with the
correct original case (`"Profile"`, `"Animal"`) for consistency with the
case-sensitive design, even though they happen to work today.
**Proposed fix**: Change to `equals("Profile")` / `equals("Animal")`.

### Finding T3 [should-fix]
**Location**: `tests/src/test/java/.../SchemaTest.java` lines 72, 86
**Issue**: Uses `equalsIgnoreCase("Account")` in assert statements to compare
linked class names. Should use `equals("Account")` for consistency.
**Proposed fix**: Change to `equals("Account")`.

### Finding T4 [suggestion]
**Location**: `core/.../HookReadTest.java:29`, `core/.../StorageBackupMTStateTest.java:324`
**Issue**: Both use `equalsIgnoreCase` for class name comparison. Neither will
cause test failures, but should be updated for consistency:
- `HookReadTest`: Compares `getSchemaClassName()` against
  `SecurityPolicy.class.getSimpleName()` (which is `"SecurityPolicy"`, not
  matching the actual schema class `"OSecurityPolicy"`)
- `StorageBackupMTStateTest`: Compares two generated class names that always
  use the same case prefix
**Proposed fix**: Change both to `equals()`. These are cosmetic cleanup items.

### Finding T5 [should-fix]
**Location**: TC2/TC3/TC4 deferred from Track 2 code review
**Issue**: These are new test scenarios to write, not fixes to existing tests:
- TC2: Security filter wildcard tests for `isAllClasses()` guard
- TC3: `DatabaseImport.importIndexes()` equals check test
- TC4: Index name preservation across session reload
TC2 requires understanding security policy setup API. `isAllClasses()` has
minimal test coverage.
**Proposed fix**: Group as a separate step. Use existing security test patterns
from `SecurityTest.java` and `PredicateSecurityTest.java` as reference.

## Summary
No blockers. All existing tests pass. Track 3 work is correctness cleanup
(wrong-case lookups, equalsIgnoreCase → equals) plus new deferred test
scenarios. Scope is smaller than originally planned.
