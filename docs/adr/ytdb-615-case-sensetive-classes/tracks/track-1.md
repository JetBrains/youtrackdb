# Track 1: Schema layer — case-sensitive class names + collection counter

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Base commit
`35e88eefab`

## Reviews completed
- [x] Technical
- [x] Risk
- [x] Adversarial

## Steps

- [ ] Step 1: SchemaShared — remove toLowerCase from map key operations + add collectionCounter
  Remove all `toLowerCase(Locale.ENGLISH)` calls used for class-name map key
  normalization in `SchemaShared`. Add `collectionCounter` field with
  persistence via `toStream()`/`fromStream()`.

  **Changes:**
  - `SchemaShared.existsClass()` — remove `toLowerCase` from map key lookup
  - `SchemaShared.getClass(String)` — remove `toLowerCase` from map key lookup
  - `SchemaShared.changeClassName()` — remove `toLowerCase` from `containsKey`,
    `remove`, and `put` operations (lines 466, 471, 474). Change
    `equalsIgnoreCase` guard (line 456) to `equals()`. Note: this allows
    case-only renames (Person → person), which is correct — the collection
    rename degrades to a no-op since both lowercase to the same name.
  - `SchemaShared.fromStream()` — remove `toLowerCase` from class map key
    (line 567) and superclass name lookup (line 571). For the legacy
    `superClass` field (line 556): add a defensive fallback — if exact-match
    lookup fails, iterate `classes.values()` comparing case-insensitively,
    log a warning if fallback matches. This handles any ancient databases
    that might have stored lowercased superclass names.
  - Add `collectionCounter` field (plain `int`, protected by schema write lock).
  - `SchemaShared.toStream()` — persist `collectionCounter` as `"collectionCounter"`
    property on the schema entity.
  - `SchemaShared.fromStream()` — read `"collectionCounter"` from schema entity.
    If absent (pre-migration schema), initialize by scanning existing collection
    names to find the maximum `_N` suffix across all collections, then set to
    `max(classes.size(), maxExistingSuffix + 1)`. This is a one-time cost at
    schema load.
  - Add `nextCollectionIndex()` method that increments and returns the counter.
  - **Must preserve**: No `toLowerCase` calls are removed that relate to
    collection-name derivation — only class-name map key operations.

  **Files:** `SchemaShared.java`
  **Tests:** Unit tests for case-sensitive class lookup, changeClassName with
  case-only rename, collectionCounter persistence round-trip, counter
  initialization from existing collection names.

- [ ] Step 2: SchemaEmbedded — case-sensitive class CRUD + createCollections rewrite
  Remove all `toLowerCase` calls for class-name map key operations in
  `SchemaEmbedded`. Rewrite `createCollections()` to use the global counter.
  Fix `setAbstractInternal()` to use counter-based naming.

  **Changes:**
  - `SchemaEmbedded.createClass()` — remove `toLowerCase` from map key check
  - `SchemaEmbedded.doCreateClass()` (both overloads) — remove `toLowerCase`
    from `existsClass` calls and map operations
  - `SchemaEmbedded.createClassInternal()` — remove `toLowerCase`
  - `SchemaEmbedded.getOrCreateClass()` — remove `toLowerCase`
  - `SchemaEmbedded.dropClass()` / `dropClassInternal()` — remove `toLowerCase`
    from map key operations
  - `SchemaEmbedded.createCollections()` — rewrite to always generate
    `<lowercase_classname>_<counter>` using `nextCollectionIndex()`. The counter
    is called **per individual collection** (not per class). With
    minimumCollections=3, names would be e.g. `person_42`, `person_43`,
    `person_44`. Remove the "try base name first" logic and
    `getNextAvailableCollectionName()` fallback. The `internalClasses` check
    stays (line 332) but the redundant double-toLowerCase is simplified
    (className is already lowercased at line 328). Note: this means existing
    unassigned collections with the base name are no longer reused — acceptable
    behavioral change for legacy edge cases.
  - `SchemaClassEmbedded.setAbstractInternal()` — fix the "make concrete" path
    (lines 564-566) to use counter-based naming instead of raw `name`. Call
    `createCollections()` or replicate the `<lowercase>_<counter>` logic so
    the invariant (all new collection names are lowercase with counter suffix)
    holds.
  - **Must preserve**: `toLowerCase` at line 328 (collection name derivation)
    and the `internalClasses` check.

  **Files:** `SchemaEmbedded.java`, `SchemaClassEmbedded.java`
  **Tests:** Unit tests for createCollections producing counter-suffixed names,
  multi-collection classes (minimumCollections > 1), setAbstract(false) creating
  counter-named collections.

- [ ] Step 3: ImmutableSchema + SchemaProxy — case-sensitive class lookups
  Remove `toLowerCase` from class-name handling in `ImmutableSchema` and
  `SchemaProxy`.

  **Changes:**
  - `ImmutableSchema` constructor — remove `toLowerCase` from class map key
    population (line 75 area). Store keys using original-case name from
    `SchemaClassImpl.getName()`.
  - `ImmutableSchema.existsClass()` — remove `toLowerCase(Locale.ROOT)` (line
    189). Single exact-match lookup.
  - `ImmutableSchema.getClassInternal()` — remove the two-phase lookup
    (try exact, then lowercase fallback). Simplify to single exact-match.
    Note: this fixes a latent locale bug where constructor used `Locale.ENGLISH`
    but lookup used `Locale.ROOT`.
  - `SchemaProxy.getOrCreateClass(String, SchemaClass)` — remove
    `toLowerCase(Locale.ENGLISH)` at line 90. Note: the varargs overload
    (line 102) already passes through as-is, so only this overload needs fixing.

  **Files:** `ImmutableSchema.java`, `SchemaProxy.java`
  **Tests:** Tests for exact-case class lookup through ImmutableSchema,
  SchemaProxy getOrCreateClass with exact name.

- [ ] Step 4: Class hierarchy comparisons — equalsIgnoreCase → equals
  Change all `equalsIgnoreCase` comparisons of class names in the schema
  class hierarchy to `equals()`.

  **Changes:**
  - `SchemaClassImpl.isSubClassOf(String)` — `equalsIgnoreCase` → `equals()`
  - `SchemaImmutableClass.isSubClassOf(String)` — `equalsIgnoreCase` → `equals()`
  - `SchemaClassImpl.matchesType()` — `equalsIgnoreCase` → `equals()` for
    `linkedClass.getName()` comparison. Verify that all code paths setting
    schemaClassName use canonical name from `getName()` (grep for
    `setSchemaClassName` or equivalent).

  **Files:** `SchemaClassImpl.java`, `SchemaImmutableClass.java`
  **Tests:** Tests for isSubClassOf with exact-case names, matchesType with
  exact-case linked class names.

- [ ] Step 5: Non-schema equalsIgnoreCase sites
  Change remaining `equalsIgnoreCase` class-name comparisons outside the
  schema layer to `equals()`.

  **Changes:**
  - `CheckSafeDeleteStep` — comparisons with `"V"` / `"E"` string constants.
    Change to `equals()`.
  - `DatabaseImport` — class drop ordering and `"ORestricted"` check. Change
    `equalsIgnoreCase` to `equals()`.
  - `VertexEntityImpl` — edge class detection (line 443). Change
    `equalsIgnoreCase(EdgeInternal.CLASS_NAME)` to `equals()`. This is a known
    behavioral change: `g.addE("e")` will no longer match `"E"`. Acceptable
    for internal database — users must use exact class names.
  - **Must preserve**: Any `toLowerCase` calls in these files that relate to
    non-class-name operations (if any).

  **Files:** `CheckSafeDeleteStep.java`, `DatabaseImport.java`,
  `VertexEntityImpl.java`
  **Tests:** Tests for safe delete with exact class names, import with exact
  class names, Gremlin edge creation with exact class name.

- [ ] Step 6: Verification — test suite + case-sensitivity integration test
  Run the full core test suite to discover and fix any remaining failures
  caused by case-mismatch lookups. Add a canonical case-sensitivity
  integration test.

  **Changes:**
  - Run `./mvnw -pl core clean test` and fix any test failures caused by
    case-insensitive assumptions (e.g., tests looking up `"whiz"` when class
    was created as `"Whiz"`). Note: known test fixes are in Track 3, but
    any schema-layer test failures discovered here should be fixed in this
    step to keep the build green.
  - Add a new test that creates two classes with the same name in different
    cases (`Person` and `PERSON`), verifies both exist independently, verifies
    they get distinct counter-suffixed collection names, and verifies
    `getClass("Person")` != `getClass("PERSON")`.
  - Run `./mvnw -pl core spotless:apply` to fix formatting.
  - Run coverage check on changed files.

  **Files:** Schema test files (existing + new test), any broken test files.
  **Tests:** Case-sensitivity integration test, all core module unit tests pass.
