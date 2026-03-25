# Track 2 Technical Review: Index name and IndexManager — case-sensitive names

## Summary

Track 2's scope is well-defined and its component references are accurate. All
identified call sites exist exactly as described. The approach is sound — purely
in-memory map operations with no on-disk format implications. The dependency on
Track 1 (class names already case-sensitive in schema) is satisfied.

## Findings

### Finding T1 [should-fix]
**Location**: Track 2 description, "Additional `equalsIgnoreCase` call sites" —
`Index.java:378` and `IndexManagerEmbedded.java:423`
**Issue**: Both `Index.isLabelSecurityDefined()` and
`IndexManagerEmbedded.checkSecurityConstraintsForIndexCreate()` compare
`SecurityResourceProperty.getClassName()` with class names from the schema
hierarchy. After switching to `equals()`, if a security rule was created with a
different case than the schema class (e.g., `database.class.person.name` instead
of `database.class.Person.name`), the security filter will silently stop
matching. The plan should explicitly note this behavioral change.
**Proposed fix**: Add a note acknowledging that security resource strings must
use exact-case class names. This is an acceptable change for an internal DB.
No code change needed beyond `equalsIgnoreCase` → `equals`.

### Finding T2 [should-fix]
**Location**: Track 2, `ImmutableSchema` constructor and `getIndexes()`
**Issue**: `ImmutableSchema.getIndexes()` returns `indexes.keySet()` where keys
are currently lowercased. After Track 2, keys will be original-case. Any test
asserting lowercased index names from this path will break and need fixing in
Track 3.
**Proposed fix**: Scan test code for assertions comparing index names from
`schema.getIndexes()` against lowercased expected values. Add these to Track 3's
scope.

### Finding T3 [suggestion]
**Location**: `IndexManagerAbstract.getClassIndex()` (lines 159-171)
**Issue**: After removing both `toLowerCase` calls, the comparison becomes
`className.equals(index.getDefinition().getClassName())`. Callers
(`SchemaClassImpl.getClassIndex()`, `SchemaImmutableClass`) pass `this.name`,
which after Track 1 is original-case — matching `indexDefinition.getClassName()`.
**Proposed fix**: None needed. Confirming correctness.

### Finding T4 [suggestion]
**Location**: Scope boundary validation
**Issue**: `CompositeIndexDefinition.java:552` (`toLowerCase` for SQL collation),
`IndexDefinitionFactory.java:100/261` (`equalsIgnoreCase` for SQL syntax),
`Indexes.java:177-178` (index type enum), `RecreateIndexesTask.java:81` (algorithm
name) — all correctly excluded from scope.
**Proposed fix**: None needed.

### Finding T5 [suggestion]
**Location**: `DatabaseImport.java:1386`
**Issue**: `EXPORT_IMPORT_INDEX_NAME` comparison — both sides are code-controlled
constants. Safe to change.
**Proposed fix**: None needed.

## Verdict

**PASS** — Track 2 is ready for execution. No blockers. Two should-fix findings
are documentation/awareness items, not code changes blocking execution. All 13
call sites (7 in IndexManager*, 3 in ImmutableSchema, 2 in security filters,
1 in DatabaseImport) are confirmed at described locations.
