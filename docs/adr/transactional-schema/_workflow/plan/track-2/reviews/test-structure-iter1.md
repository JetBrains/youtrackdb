<!--MANIFEST
dimension: test-structure
prefix: TS
iteration: 1
verdict: pass
counts: { blocker: 0, should_fix: 0, suggestion: 3 }
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - id: TS1
    sev: suggestion
    anchor: "TS1"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/PerClassSchemaRecordTest.java:assertVersionRejected (line 436)"
    cert: n/a
    basis: diff
  - id: TS2
    sev: suggestion
    anchor: "TS2"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/PerClassSchemaRecordTest.java:rootNonLinkPayloadSurvivesReopen (lines 372-397)"
    cert: n/a
    basis: diff
  - id: TS3
    sev: suggestion
    anchor: "TS3"
    loc: "core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/PerClassSchemaRecordTest.java:droppedClassDeletesRecordAndUnlinks (line 355)"
    cert: n/a
    basis: diff
-->

## Findings

### TS1 [suggestion] Version-gate rejection assertion couples to a free-text substring of the exception message

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/PerClassSchemaRecordTest.java`, method `assertVersionRejected` (line 436)

**Issue**: The helper asserts the rejection by matching a substring of the exception text:

```java
} catch (ConfigurationException expected) {
  assertTrue("rejection must redirect to export/import",
      expected.getMessage().contains("export"));
}
```

This is the shared assertion for both version-gate tests
(`versionFourDatabaseIsRejectedAndRedirected`,
`legacyVersionFiveDatabaseIsRejectedAndRedirected`). The catch already proves the
correct exception *type* fired; the extra `contains("export")` check binds the test
to the wording of the redirect message in `SchemaShared.fromStream`. A later reword
of that message (it is operator-facing prose, not a stable contract) breaks two
tests for no behavioral reason. The brittleness is low-stakes — the type catch is the
load-bearing assertion — which is why this is a suggestion, not a should-fix.

**Suggestion**: Either drop the substring check (the `catch (ConfigurationException
expected)` plus the `fail(...)` on the no-throw path already pins the behavior), or,
if the redirect-to-export/import intent is worth pinning, assert against a stable
signal rather than free text — for example a dedicated exception subtype or an error
code if one exists. If the substring is kept deliberately as documentation of intent,
a one-line comment saying so ("the message must steer the operator to export/import")
would stop a future reader from treating it as load-bearing.

### TS2 [suggestion] `rootNonLinkPayloadSurvivesReopen` Javadoc claims three payload elements but verifies two

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/PerClassSchemaRecordTest.java`, method `rootNonLinkPayloadSurvivesReopen` (lines 372-397)

**Issue**: The method Javadoc states the scenario as:

> The root's non-link payload — the global-property table, the collection counter, and
> the blob-collections set — survives a reopen.

The body asserts on the collection counter (`counterBefore`/`counterAfter`), the
global-property table size (`globalsBefore` vs reopened size), and the surviving class
property. It never reads or asserts on the blob-collections set. A reader takes a test
Javadoc as the statement of what the test proves; listing blob-collections among the
"survives a reopen" payload over-claims the method's actual coverage. The track's
`## Validation and Acceptance` round-trip criterion also names blob-collections as part
of the preserved root payload, so the gap is visible against the acceptance spec, not
just internally.

**Suggestion**: Bring the Javadoc and the body into agreement — either add an assertion
that the blob-collections set survives the reopen (closing the gap against the
acceptance criterion), or, if blob-collections coverage is intentionally out of scope
for this step, drop it from the Javadoc's enumerated list so the doc states only what
the test checks. The first option is the better fit given the track lists
blob-collections as a preserved-payload element.

### TS3 [suggestion] `droppedClassDeletesRecordAndUnlinks` pre-drop assertion has a bare message-less `assertNotNull`

**File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/PerClassSchemaRecordTest.java`, method `droppedClassDeletesRecordAndUnlinks` (line 355)

**Issue**: Every other assertion in the new test class carries a descriptive message
(e.g. `assertNotNull("a persisted class must carry its own record RID", ridBefore)`),
which is what makes a failure self-documenting. The drop test's setup assertion is the
lone exception:

```java
var droppedRid = schemaShared().getClass("ToDrop").getRecordId();
assertNotNull(droppedRid);
```

If this line fails, the failure report says only "expected not null" with no hint that
the freshly-created `ToDrop` class was expected to carry a bound record RID before the
drop. It is a consistency nit within an otherwise uniformly well-messaged class.

**Suggestion**: Add a message matching the surrounding style, e.g.
`assertNotNull("a created class must carry a bound record RID before it is dropped",
droppedRid);`.

## Evidence base
