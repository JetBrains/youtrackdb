<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: TS1, sev: suggestion, loc: RestoreAtomicUnitPageOperationTest.java:382, anchor: "### TS1 ", cert: n/a, basis: "repeated 6-line missing-recoverable-file arrange block across three consult tests; extract a named helper to match the class's existing helper pattern and sharpen AAA"}
evidence_base: {section: "## Evidence base", certs: 0}
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [suggestion] Extract the repeated missing-recoverable-file arrange block into a named helper

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/RestoreAtomicUnitPageOperationTest.java`, methods `testPageOperationConsultsPendingCreateInSameUnit` (line 382), `testUpdatePageRecordConsultsPendingCreateInSameUnit` (line 422), `testLaterUnitSurvivesAfterRecoverableFileCreatingUnit` (line 469).
- **Issue**: The three happy-path consult tests open with the same six-line Arrange block that wires the created file as present-but-unrecoverable and arms the state transition:

  ```java
  when(writeCache.exists(CREATED_EXTERNAL_ID)).thenReturn(false);
  when(writeCache.exists("created.dat")).thenReturn(false);
  when(writeCache.internalFileId(CREATED_EXTERNAL_ID)).thenReturn(CREATED_INTERNAL_ID);
  when(writeCache.externalFileId(CREATED_INTERNAL_ID)).thenReturn(CREATED_EXTERNAL_ID);
  when(writeCache.restoreFileById(CREATED_EXTERNAL_ID)).thenReturn(null);
  wireConsultMaterializes("created.dat", CREATED_EXTERNAL_ID);
  ```

  The block is byte-identical in all three. It is correct and isolation-clean (each test runs against fresh per-`@Before` mocks, so there is no shared-state concern), but the duplication buries the per-test delta — the WAL record list and the verification — under boilerplate that reads the same every time. The class already establishes the "extract reusable mock wiring into a named private helper" pattern with `createCacheEntryWithLsn` and `wireConsultMaterializes`, so this block is the odd one out.
- **Suggestion**: Add one more arrange helper alongside the existing ones, e.g. `private void wireMissingFileRecoverableViaConsult()` that performs the five `writeCache` stubs plus the `wireConsultMaterializes` call on the `CREATED_*` ids. Each of the three tests then opens with a single intent-naming line, and the AAA boundary between fixture and the scenario-specific records becomes obvious at a glance. This is a readability/maintenance improvement only; the tests are already independent and well-documented.

## Evidence base
