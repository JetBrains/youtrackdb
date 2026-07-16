<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: TS1, sev: suggestion, loc: StringPredicateCollationTest.java:89, anchor: "### TS1 ", cert: n/a, basis: "four eval-only tests wrap their assertions inside session.begin()/session.commit() with commit as the last statement (not in a finally); the transaction is needed only to create the entity, not for the evaluate() assertions; on assertion failure commit is skipped, leaving an open transaction — cleaned up by the per-method DB drop so it never cascades, but it blurs arrange/act/assert and diverges from the count/insertDoc helper tests that commit before asserting"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [suggestion] Eval-only tests assert inside an uncommitted transaction

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/parser/StringPredicateCollationTest.java:89`
- **Issue**: Four tests run their assertions inside a hand-opened `session.begin()` … `session.commit()` block with `commit()` as the last statement rather than in a `finally`: `containsText_ciCollation_identifiableAndResultPathsAgree` (line 89), `legacyContainsText_isCollationConsistentWithModernPath` (line 155), `endsWith_honorsCollationOnBothEvalPaths` (line 180), and `matchesRegex_usesFindSemanticsAndStaysCaseSensitive` (line 209). The transaction is required only to create the entity via `session.newEntity`; the `evaluate(...)` assertions read the already-built in-memory entity and need no active transaction. The helper-based tests take the cleaner shape — `containsText_anyFunction_honorsPerPropertyCollation` (line 134) and `containsText_allFunction_honorsPerPropertyCollation` create and commit the entity first, then assert through `count(...)`, so their arrange phase is closed before any assertion runs.
- **Failure scenario**: When one of the four assertions fails, `commit()` is skipped and the session is left with an open transaction. Isolation still holds — `DbTestBase.afterTest` closes the session and drops the per-method database, so nothing leaks into or fails another test. The cost is local to the failing test: teardown may surface a secondary close-with-open-transaction error that obscures the real assertion, and the two transaction styles (assert-inside-txn vs commit-then-assert) side by side make the file harder to scan for a maintainer confirming each test's transaction boundaries.
- **Suggestion**: Commit the entity in its own `begin()`/`commit()` (or reuse the existing `insertDoc` helper) before the assertions, matching the `count`-based tests; alternatively move `commit()` into a `finally`. Either keeps each test's arrange phase distinct from its assertions and removes the skipped-commit-on-failure path.

## Evidence base
