# Track 1 Adversarial Review

## Summary
Track 1's approach is sound. The key decision (D1: exact-match HashMap over
case-insensitive TreeMap) is justified for an internal database. The collection
counter (D2) is the right mechanism but initialization needs fixing. No true
blockers after analysis — the two initially marked as blockers (A2, A9) are
reclassified to should-fix after deeper analysis.

## Findings

### Finding A1 [should-fix]
**Target**: Decision D1 (remove toLowerCase rather than case-insensitive map)
**Challenge**: SQL behavioral break is not properly scoped. Plan says "No changes
needed" for SQL parser but also says "SQL queries must use exact-case class names."
The rejected TreeMap alternative would preserve backward compatibility with
negligible O(log n) cost for small maps.
**Evidence**: SelectExecutionPlanner.java line 1913 calls schema.getClass(className)
directly from SQL parse tree without lowercasing.
**Proposed fix**: The plan already documents the SQL behavioral change in
"Note — SQL behavioral change" section. D1 rationale is sufficient — TreeMap
would preserve a behavior we explicitly want to remove. No change needed.

### Finding A2 [should-fix] (reclassified from blocker)
**Target**: Decision D2 (counter initialization from classes.size())
**Challenge**: Not provably collision-free. With minimumCollections > 1 and class
drops/re-creates, existing _N suffixes could exceed classes.size().
**Evidence**: getNextAvailableCollectionName generates _1, _2, etc. A class with
minimumCollections=8 would have suffixes _1 through _7. Storage layer throws on
duplicate names, preventing corruption but failing class creation.
**Proposed fix**: Scan existing collection names during fromStream() to find max
suffix. Initialize counter to max(classes.size(), maxExistingSuffix + 1). One-time
cost at schema load.

### Finding A3 [suggestion]
**Target**: Decision D3 (keep internalClasses set lowercase)
**Challenge**: The internalClasses mechanism forcing minimumCollections=1 is a
legacy OrientDB optimization. Could be removed entirely.
**Evidence**: createCollections line 332 checks internalClasses. The override
reduces collection count for internal classes.
**Proposed fix**: Out of scope for this track. Note for future simplification.

### Finding A4 [should-fix]
**Target**: Track scope
**Challenge**: Non-schema equalsIgnoreCase sites (CheckSafeDeleteStep,
DatabaseImport, VertexEntityImpl) inflate track scope. These are in different
packages (sql/executor, db/tool, record/impl).
**Evidence**: 4 different packages affected.
**Proposed fix**: Keep in Track 1 as a dedicated step — they are small mechanical
changes and splitting into a separate track adds overhead without benefit.

### Finding A5 [suggestion]
**Target**: Missing canonical test
**Challenge**: No test creates two classes with same name in different cases
(Person vs PERSON) to verify they are distinct.
**Evidence**: Would fail today due to case-insensitive lookup.
**Proposed fix**: Add to verification step in Track 1.

### Finding A6 [should-fix]
**Target**: Invariant (map keys = getName())
**Challenge**: Transient violation during changeClassName — map key is newName
but cls.getName() still returns oldName until setNameInternal completes.
**Evidence**: SchemaClassEmbedded.setNameInternal lines 309-311: changeClassName
called before this.name = name.
**Proposed fix**: Invariant holds after write lock scope completes. No external
observer can see the transient state. Document that invariant is enforced at
write lock boundaries.

### Finding A7 [should-fix]
**Target**: Invariant (collection names lowercase with counter suffix)
**Challenge**: setAbstract(false) creates collection using raw class name
(line 564-566) without lowercasing or counter suffix. Bypasses createCollections().
**Evidence**: SchemaClassEmbedded.setAbstractInternal lines 564-566:
`collectionId = database.addCollection(name)` uses exact-case name.
**Proposed fix**: Fix setAbstract(false) path to use counter-based naming via
createCollections() or equivalent logic. Must be part of Step 2.

### Finding A8 [should-fix]
**Target**: Invariant (counter monotonicity)
**Challenge**: Counter can decrease across crash/recovery since it is not
crash-safe independently of the schema record transaction.
**Evidence**: Counter increments in createCollections before releaseSchemaWriteLock
calls saveInternal/toStream. Crash between increment and save loses the value.
**Proposed fix**: Acceptable. Counter monotonicity is best-effort across crashes.
Collection name uniqueness is additionally guarded by storage layer duplicate check.
Document this reasoning.

### Finding A9 [should-fix] (reclassified from blocker)
**Target**: Assumption (on-disk format preserves original case)
**Challenge**: Plan assumes superclass names in schema records are always
original-case. If historical OrientDB code ever lowercased before persisting,
fromStream() would fail to find the superclass in the case-sensitive map.
**Evidence**: Current code chain: fromStream reads name property (original case),
toStream writes getName() (original case). Round-trip preserves case in current
code. Risk is from historical OrientDB versions.
**Proposed fix**: Add defensive fallback in fromStream() for legacy superClass
field: if exact match fails, try case-insensitive search with warning log.
Low-cost safety net.

### Finding A10 [should-fix]
**Target**: Assumption (no Gremlin changes needed)
**Challenge**: Plan modifies VertexEntityImpl (line 443) which is a Gremlin
integration point, contradicting "no changes needed" claim.
**Evidence**: VertexEntityImpl.java line 443: equalsIgnoreCase for edge class
detection. This is a user-facing API behavior change.
**Proposed fix**: Include in Track 1 as documented behavioral change. Run
Gremlin test suite for verification.

### Finding A11 [suggestion]
**Target**: Simplification — counter vs scanning
**Challenge**: Counter adds persistence complexity. Existing scanning mechanism
(getNextAvailableCollectionName) works on the cold path.
**Evidence**: Collection creation is rare. Scanning cost O(existing_collections)
is negligible.
**Proposed fix**: Counter is still preferred — deterministic, O(1), avoids
scanning race conditions. Fix initialization to address A2.

### Finding A12 [suggestion]
**Target**: Simplification — must-preserve toLowerCase calls
**Challenge**: renameCollection() toLowerCase calls not called out as must-preserve.
**Evidence**: SchemaClassImpl.renameCollection lines 1399-1400 lowercase for
collection naming.
**Proposed fix**: Document in step descriptions.

## Decisions

| Finding | Decision |
|---------|----------|
| A1 | No change — rationale sufficient |
| A2 | Fix initialization (scan existing names) |
| A3 | Out of scope — note for future |
| A4 | Keep in Track 1 as dedicated step |
| A5 | Add to verification step |
| A6 | Document invariant scope — no code change |
| A7 | Fix setAbstract(false) in Step 2 |
| A8 | Document reasoning — acceptable |
| A9 | Add defensive fallback in fromStream |
| A10 | Document as behavioral change |
| A11 | Keep counter, fix initialization |
| A12 | Document in step descriptions |
