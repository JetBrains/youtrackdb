# Track 1 Risk Review

## Summary
Track 1 is feasible and well-scoped. Changes are mechanical with blast radius
limited to schema operations. Critical paths (storage, WAL, indexes, cache) are
not directly touched. The collection counter persistence is the most complex new
piece. Key risks: counter initialization heuristic, legacy superclass name
resolution, and setAbstract(false) bypassing counter-based naming.

## Findings

### Finding R1 [should-fix]
**Location**: SchemaShared.changeClassName (line 456)
**Issue**: Case-only rename (Person → person) now allowed. Collection rename
degrades to no-op (same lowercased name). Low risk — renameCollection checks
for existing name and returns early. Needs explicit test coverage.
**Proposed fix**: Add test for case-only rename. Verify collection mapping
remains intact after rename.

### Finding R2 [should-fix]
**Location**: SchemaShared.fromStream() — superclass name resolution (line 571)
**Issue**: Legacy `superClass` property (old single-superclass field) might
contain names in different case than the `name` property of the parent class
in ancient databases. Very low probability (same code path wrote both), but
high impact (class hierarchy broken on load).
**Proposed fix**: Add defensive fallback for legacy `superClass` field only:
if exact match fails, iterate classes.values() and compare case-insensitively.
Log a warning when fallback is used.

### Finding R3 [should-fix]
**Location**: collectionCounter initialization to classes.size()
**Issue**: If classes were dropped and re-created, classes.size() could be smaller
than existing _N suffixes. Storage layer throws ConfigurationException on collision,
preventing corruption, but class creation would fail unexpectedly.
**Proposed fix**: Initialize counter to max(classes.size(), maxExistingSuffix + 1)
by scanning existing collection names during fromStream().

### Finding R4 [should-fix]
**Location**: createCollections when minimumCollections > 1
**Issue**: Counter must increment per individual collection, not per class.
With minimumCollections=3, names should be person_42, person_43, person_44.
**Proposed fix**: Call nextCollectionIndex() inside the loop for each collection.

### Finding R5 [suggestion]
**Location**: ImmutableSchema locale inconsistency
**Issue**: Constructor uses Locale.ENGLISH, existsClass uses Locale.ROOT —
latent bug fixed by removing all toLowerCase calls. Additional motivation for
the change.
**Proposed fix**: Document in design rationale.

### Finding R6 [should-fix]
**Location**: SchemaClassImpl.matchesType (line 1381)
**Issue**: After changing to equals(), type validation requires exact case match
between linkedClass.getName() and entity's schemaClassName. Need to verify all
code paths setting schemaClassName use canonical name from getName().
**Proposed fix**: Grep for setSchemaClassName or equivalent to verify.

### Finding R7 [suggestion]
**Location**: SchemaProxy getOrCreateClass overloads
**Issue**: Verify both overloads behave identically after toLowerCase removal.
**Proposed fix**: Test coverage in verification step.

### Finding R8 [suggestion]
**Location**: collectionCounter crash recovery
**Issue**: If crash occurs between counter increment and schema save, counter
reverts on recovery. Collection name from crashed operation also lost, so no
uniqueness violation. Monotonicity guarantee is best-effort across crashes.
**Proposed fix**: Document reasoning. Counter is protected by write lock +
schema save on lock release. WAL replay handles atomicity.

### Finding R9 [should-fix]
**Location**: VertexEntityImpl line 443 — Gremlin edge class detection
**Issue**: equalsIgnoreCase → equals() changes behavior for Gremlin users
passing lowercase "e" as edge label. Behavioral change consistent with the
overall case-sensitivity goal.
**Proposed fix**: Document as known behavioral change. Test with Gremlin suite.

## Risk Summary (priority order)
1. **R3**: Counter initialization — most likely to cause real failures
2. **R1**: Case-only rename — needs explicit testing
3. **R4**: Counter per-collection — ambiguity that could cause collisions
4. **R2**: Legacy superclass names — very low probability, high impact
5. **R9**: Gremlin behavioral change — user-facing but acceptable

## Decisions

| Finding | Decision |
|---------|----------|
| R1 | Add test case in verification step |
| R2 | Add defensive fallback with warning in fromStream step |
| R3 | Fix initialization by scanning existing names |
| R4 | Clarify in step decomposition |
| R5 | Note in documentation |
| R6 | Verify during implementation |
| R7 | Cover in tests |
| R8 | Document reasoning |
| R9 | Document as behavioral change |
