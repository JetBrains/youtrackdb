# Track 1: Schema layer — case-sensitive class names + collection counter

## Progress
- [x] Review + decomposition
- [x] Step implementation (3/3 complete)
- [ ] Track-level code review

## Base commit
`35e88eefab`

## Reviews completed
- [x] Technical
- [x] Risk
- [x] Adversarial

## Steps

- [x] Step 1: Schema layer — case-sensitive class names + collection counter (combined Steps 1-3)
  - [x] Context: info
  > **What was done:** Removed all `toLowerCase()` calls used for class-name
  > map key normalization across the entire schema layer: SchemaShared
  > (existsClass, getClass, changeClassName, fromStream), SchemaEmbedded
  > (all class CRUD methods, createCollections rewrite), ImmutableSchema
  > (constructor + lookup), SchemaProxy (getOrCreateClass),
  > SchemaClassEmbedded (setAbstractInternal). Added `collectionCounter`
  > field to SchemaShared with persistence via toStream/fromStream, and
  > `nextCollectionIndex()` method. New collections use `<lowercase>_<counter>`
  > format. Legacy superclass name fallback added to fromStream with
  > case-insensitive scan + warning log. Added `CaseSensitiveClassNameTest`
  > with 7 tests covering case-sensitive lookup, exists, rename, case-only
  > rename, counter persistence, getOrCreateClass.
  >
  > **What changed from the plan:** Steps 1-3 were combined into a single
  > commit because they cannot be tested independently — the public Schema
  > API goes through SchemaProxy → SchemaEmbedded → SchemaShared, so fixing
  > only one layer still fails through the public API. This is a tactical
  > deviation; the decomposition was incorrect.
  >
  > **Key files:** `SchemaShared.java` (modified), `SchemaEmbedded.java`
  > (modified), `ImmutableSchema.java` (modified), `SchemaProxy.java`
  > (modified), `SchemaClassEmbedded.java` (modified),
  > `CaseSensitiveClassNameTest.java` (new)

- [x] Step 2: equalsIgnoreCase → equals for class name comparisons (combined Steps 4-5)
  - [x] Context: info
  > **What was done:** Changed all `equalsIgnoreCase()` class-name
  > comparisons to `equals()` in: SchemaClassImpl.isSubClassOf,
  > SchemaClassImpl.matchesType, SchemaImmutableClass.isSubClassOf,
  > CheckSafeDeleteStep ("V"/"E"), DatabaseImport (class drop ordering,
  > "ORestricted" check), VertexEntityImpl (edge class detection).
  >
  > **Key files:** `SchemaClassImpl.java` (modified),
  > `SchemaImmutableClass.java` (modified), `CheckSafeDeleteStep.java`
  > (modified), `DatabaseImport.java` (modified), `VertexEntityImpl.java`
  > (modified)

- [x] Step 3: Test fixes + renameCollection fix (Step 6)
  - [x] Context: info
  > **What was done:** Fixed 15 test files that relied on case-insensitive
  > class name lookup. Main patterns: SQL queries using lowercase class
  > names (v→V, e1→E1, orole→ORole, oschedule→OSchedule), test assertions
  > using hardcoded collection names (now use dynamic lookup via
  > getCollectionNameById). Fixed SchemaClassImpl.renameCollection() to
  > handle counter-based collection names — old logic tried to find
  > collections by lowercased class name which no longer works with
  > counter-suffixed names. New logic iterates the class's collection IDs
  > and renames each by replacing the old prefix with the new one.
  >
  > **What was discovered:** The renameCollection() method had a hidden
  > dependency on the old naming convention where collection name = lowercase
  > class name. This was not identified in the plan — the plan only mentioned
  > test fixes for this step but the renameCollection fix is a production code
  > change. Track 3 test fixes may have reduced scope since most core module
  > test fixes are done here.
  >
  > **Key files:** `SchemaClassImpl.java` (modified), 15 test files (modified)
