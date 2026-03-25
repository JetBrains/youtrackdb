# Track 1 Technical Review

## Summary
Track 1 is well-scoped and accurately maps the codebase. No true blockers found.
The component relationships are correct. Key issues are documentation gaps and
edge case handling for the collection counter.

## Findings

### Finding T1 [should-fix]
**Location**: SchemaClassImpl.renameCollection (line 1398-1400), SchemaClassEmbedded.tryDropCollection (line 590)
**Issue**: Two `toLowerCase()` call sites not mentioned in the plan that must be
*preserved* (they lowercase for collection-name derivation, not class-name map keys).
An implementer sweeping all `toLowerCase` sites might accidentally remove them.
**Proposed fix**: Document as explicit "no-change" items alongside `createCollections()`.

### Finding T2 [should-fix]
**Location**: SchemaShared.changeClassName (line 456)
**Issue**: Changing `equalsIgnoreCase` to `equals()` allows case-only renames
(Person → person). The subsequent `renameCollection()` call degrades to a no-op
(both old/new lowercase to same collection name). Harmless but should be documented.
**Proposed fix**: Note in step description that case-only rename is now valid and
collection rename is a graceful no-op. Add a test case.

### Finding T3 [should-fix]
**Location**: createCollections when minimumCollections > 1
**Issue**: Plan says "each call to createCollections() increments the counter" but
doesn't address multiple collections per class. `nextCollectionIndex()` must be
called per individual collection allocation, not once per class.
**Proposed fix**: Clarify in step decomposition.

### Finding T4 [suggestion]
**Location**: ImmutableSchema — Locale inconsistency
**Issue**: Constructor uses `Locale.ENGLISH` (line 75), `existsClass` uses
`Locale.ROOT` (line 189). Removing all `toLowerCase` calls fixes this latent bug.
**Proposed fix**: Note as bonus fix in commit message.

### Finding T5 [should-fix]
**Location**: fromStream() counter initialization to classes.size()
**Issue**: If old databases had collision-resolved collections (e.g., `person_3`)
and classes.size() is small, counter could collide. Storage layer's duplicate name
check catches this, but class creation would fail unexpectedly.
**Proposed fix**: Initialize counter to max(classes.size(), highest existing _N suffix + 1)
or use total collection count.

### Finding T6 [should-fix]
**Location**: createCollections — counter-based naming vs orphaned collection reuse
**Issue**: Current code reuses existing unassigned collections with the base name.
Counter-based approach always creates new names, leaving orphaned collections.
**Proposed fix**: Document as acceptable behavioral change. Orphaned collections
are a legacy edge case.

### Finding T7 [suggestion]
**Location**: VertexEntityImpl line 443
**Issue**: `equalsIgnoreCase(EdgeInternal.CLASS_NAME)` changing to `equals()` means
`g.addE("e")` would no longer match `"E"`. Behavioral change for Gremlin users.
**Proposed fix**: Document as known behavioral change. Acceptable for internal DB.

### Finding T8 [should-fix]
**Location**: SchemaProxy — three getOrCreateClass overloads
**Issue**: Overload 3 (varargs) already passes name as-is with no toLowerCase.
Existing inconsistency confirms the plan is correct — only overload 2 needs fixing.
**Proposed fix**: Note in step description that overload 3 is already correct.

### Finding T9 [suggestion]
**Location**: collectionCounter field synchronization
**Issue**: Counter must be a plain `int` protected by the schema write lock,
not an `AtomicInteger`. The write lock already provides needed synchronization.
**Proposed fix**: Note in implementation.

## Decisions

| Finding | Decision |
|---------|----------|
| T1 | Address in step decomposition — document as must-preserve |
| T2 | Add test case for case-only rename in verification step |
| T3 | Clarify counter-per-collection in step 2 description |
| T4 | Note in commit message |
| T5 | Fix initialization: scan existing collection names for max suffix |
| T6 | Document as acceptable change |
| T7 | Document as known behavioral change |
| T8 | Note in step description |
| T9 | Use plain int |
