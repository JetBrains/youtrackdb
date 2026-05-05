# Track 16 — Technical Review (iter-1)

**Verdict:** PASS with caveats — 0 blocker / 3 should-fix / 4 suggestion / 0 skip.

Track 16 is a straightforward additive test-only track over `core/metadata/{schema,function,sequence,schema/clusterselection}`. The approach (DbTestBase-backed schema mutations + standalone tests for stateless utilities) is sound and consistent with carry-forward conventions from Tracks 5–15. The track's What/How/Constraints/Interactions are largely accurate, but the **What** subsection's enumeration of target classes is incomplete in three places, the explicit listed packages omit two non-empty sibling packages (`schema/schema`, `schema/validation`), and there is exactly one clear *DeadCodeTest pin candidate (`IndexConfigProperty`) that the plan does not surface.

---

## Part 1: Evidence Certificates

### Premise: Cited uncov figures are accurate (within drift tolerance)

- **Track claim**: `core/metadata/schema` 1,278 uncov / 70.7%; `core/metadata/function` 74 / 72.2%; `core/metadata/sequence` 75 / 84.3%; `core/metadata/schema/clusterselection` 18 / 63.3%.
- **Search performed**: Re-derived from fresh `.coverage/reports/youtrackdb-core/jacoco.xml` (mtime 2026-05-05 13:32) using a Python summarizer over the JaCoCo XML.
- **Code location**: `.coverage/reports/youtrackdb-core/jacoco.xml`
- **Actual behavior**:
  - `metadata.schema` 1,232 uncov / 71.7% (drift -46 / +1.0 pp)
  - `metadata.schema.clusterselection` 18 uncov / 63.3% (exact match)
  - `metadata.schema.schema` 1 uncov / 98.3% (NOT in track scope list)
  - `metadata.schema.validation` 0 uncov / 100.0% (NOT in track scope list)
  - `metadata.function` 71 uncov / 73.3% (drift -3 / +1.1 pp)
  - `metadata.sequence` 70 uncov / 85.4% (drift -5 / +1.1 pp)
- **Verdict**: PARTIAL — primary numbers are accurate within expected drift; baseline remeasurement at Step 1 (Track 14 precedent) will refine. Two non-empty sibling packages (`schema/schema`, `schema/validation`) are omitted from the What — both are already at ~99-100% so this is a documentation issue, not a coverage gap, but the strict in-scope/out-of-scope reading creates ambiguity.

### Premise: All four cluster-selection-strategy implementers live in the listed package

- **Track claim**: cluster selection strategies in `core/metadata/schema/clusterselection`.
- **Search performed**: PSI `ClassInheritorsSearch.search(CollectionSelectionStrategy, allScope, deep=true)` and SPI registration check.
- **Code location**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/clusterselection/{Round,Default,Balanced}*.java`; SPI registration at `core/src/main/resources/META-INF/services/com.jetbrains.youtrackdb.internal.core.metadata.schema.CollectionSelectionStrategy`.
- **Actual behavior**: Exactly 3 production implementers (RoundRobin, Default, Balanced), all in the listed package. SPI file lists all 3.
- **Verdict**: CONFIRMED — no implementers outside the listed package; SPI registration coherent.

### Premise: SchemaShared / SchemaClassImpl / SchemaPropertyImpl are abstract bases with concrete `*Embedded` subclasses

- **Track claim**: implicit — track expects schema mutation tests to exercise these classes via the public API.
- **Search performed**: PSI `findClass()` + `superClass`/`isInterface`/`hasModifierProperty(ABSTRACT)`.
- **Code location**: `core/src/main/java/.../metadata/schema/{SchemaShared,SchemaClassImpl,SchemaPropertyImpl,SchemaEmbedded,SchemaClassEmbedded,SchemaPropertyEmbedded}.java`.
- **Actual behavior**:
  - `SchemaShared` (abstract) ← `SchemaEmbedded` (1 prod subclass, in-scope)
  - `SchemaClassImpl` (abstract) ← `SchemaClassEmbedded` (1 prod subclass, in-scope)
  - `SchemaPropertyImpl` (abstract) ← `SchemaPropertyEmbedded` (1 prod subclass, in-scope)
- **Verdict**: CONFIRMED — no dead-base situation (all bases have exactly 1 live concrete subclass). Tests via the public API will exercise both the abstract base methods and the concrete `*Embedded` subclasses.

### Premise: SchemaShared synchronization uses ReentrantLock, not synchronized blocks

- **Track claim**: "Avoid coupling tests to internal SchemaShared synchronization."
- **Search performed**: `grep -n "synchronized\|StampedLock\|ReentrantLock"` in `SchemaShared.java`.
- **Code location**: `SchemaShared.java:92` — `private final ReentrantLock snapshotLock = new ReentrantLock();`
- **Actual behavior**: `ReentrantLock` instance, used internally; no `synchronized` block usage.
- **Verdict**: CONFIRMED — guidance is accurate; tests should not poke `snapshotLock` directly.

### Premise: Schema*Proxy methods appear "0 prod callers" but are live via interface dispatch

- **Track claim**: implicit — "schema proxies" are listed as in-scope.
- **Search performed**: PSI `MethodReferencesSearch.search(method, allScope, deep=true)` against each Proxy method, then re-checked via super-method resolution (`findSuperMethods()`).
- **Code location**: `core/src/main/java/.../metadata/schema/{Schema,SchemaClass,SchemaProperty}Proxy.java`.
- **Actual behavior**: Direct PSI ref-search on Proxy methods returns 0 production callers because all callers go through the interface (`Schema`, `SchemaClass`, `SchemaProperty`). `SchemaClass.getName()` itself has 161 production callers. The Proxy methods ARE live; PSI's deep flag does not propagate calls upward to the interface declaration.
- **Verdict**: CONFIRMED LIVE — but a Phase B implementer who runs naive PSI find-usages on a Proxy method may erroneously conclude the method is dead. This is a methodology trap worth calling out for the implementer.

### Premise: SchemaProxy is bound to MetadataDefault

- **Track claim**: implicit — proxies are tested via the public API exposed by `session.getMetadata().getSchema()`.
- **Search performed**: PSI `ReferencesSearch.search(SchemaProxy, allScope)`.
- **Code location**: `MetadataDefault.java:28,43,69,124` — only callers of `SchemaProxy` constructor and field. The proxy is wrapped in `MetadataDefault`, returned via `getSchema()` as a `SchemaInternal` interface.
- **Actual behavior**: 4 prod refs total, all in `MetadataDefault`. Tests reach the proxy through `session.getMetadata().getSchema()` which returns the proxy as a `Schema`/`SchemaInternal` reference.
- **Verdict**: CONFIRMED — testing through the public API is the correct route.

### Premise: Function class extends IdentityWrapper; DatabaseFunction implements SQLFunction

- **Track claim**: implicit — `FunctionLibraryImpl, DatabaseFunction` listed.
- **Search performed**: PSI `findClass().superClass / interfaces`.
- **Code location**: `core/src/main/java/.../metadata/function/{Function,DatabaseFunction}.java`.
- **Actual behavior**:
  - `Function extends IdentityWrapper` (entity-backed function persistent record)
  - `DatabaseFunction implements SQLFunction` and is registered as an SPI in `META-INF/services/com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionFactory`. SPI invocation is via `DatabaseFunctionFactory`, which is also registered as an SPI.
- **Verdict**: CONFIRMED — `DatabaseFunction.execute()` is reached via SQLFunction interface dispatch (606 prod+test refs on `SQLFunction.execute()`), so testing via SQL function calls (e.g., `SELECT myFn(...)`) will exercise both classes.

### Edge case: IndexConfigProperty has 0 inbound callers (constructor refs are self-recursive only)

- **Trigger**: scanning low-coverage classes in `metadata.schema`.
- **Code path trace**:
  1. `core/.../metadata/schema/IndexConfigProperty.java:6` — class declaration, 13 lines, 0% line coverage.
  2. PSI `ReferencesSearch.search(IndexConfigProperty, allScope)` returns 2 refs — both at `IndexConfigProperty.java:44,45`, inside the class's own `copy()` method which constructs a new instance of itself.
  3. PSI `MethodReferencesSearch` per-method: `IndexConfigProperty()` ctor has 1 prod ref (the same self-recursion in `copy()`); all 5 getters and `copy()` have 0 prod and 0 test refs.
  4. No callers anywhere in `core/src/main/java`, `core/src/test/java`, `server/`, `embedded/`, `driver/`, or `tests/`.
- **Outcome**: Class is fully unreferenced from production. It is a pure dead-code candidate (`IndexConfigPropertyDeadCodeTest` pin per the carry-forward `*DeadCodeTest` convention).
- **Track coverage**: The track's **What** does not surface this. The plan would naturally aim to "raise coverage" of this class, which would be wrong — it should be pinned and forwarded to Track 22's deferred-cleanup queue.

### Edge case: Track scope omits `core/metadata/schema/schema` and `core/metadata/schema/validation`

- **Trigger**: enumerating subdirectories under `core/metadata/schema`.
- **Code path trace**:
  1. `core/src/main/java/.../metadata/schema/schema/` — 7 interfaces (`Schema`, `SchemaClass`, `SchemaProperty`, `Collate`, `GlobalProperty`, `IndexDefinition`, `PropertyType`). Coverage: 57/58 lines = 98.3%.
  2. `core/src/main/java/.../metadata/schema/validation/` — 5 small Comparable wrappers (`Validation*Comparable`). Coverage: 20/20 = 100%.
  3. The track's **What** lists only `core/metadata/schema` (parent), `core/metadata/schema/clusterselection`, `core/metadata/function`, `core/metadata/sequence`. The two intermediate packages are not addressed.
  4. The **Constraints** section says "In-scope: only the listed `core/metadata*` packages."
- **Outcome**: Strict reading of Constraints excludes these two packages, even though they sit under the same `metadata.schema.*` namespace and are already at full coverage. This creates ambiguity but no coverage loss.
- **Track coverage**: Plan does not address. Recommend a one-line clarification in the **What** or **Constraints** that these two are intentionally out of scope (already at ~100%).

### Edge case: Track plan's named class list misses the largest uncov class in two of three sub-packages

- **Trigger**: cross-checking the track's "(FunctionLibraryImpl, DatabaseFunction)" and "(SequenceLibraryImpl, SequenceCached)" enumerations against the per-class uncov breakdown.
- **Code path trace**:
  1. `metadata.function` per-class uncov: `Function` 24 (largest), `FunctionLibraryImpl` 21, `DatabaseFunction` 14, `FunctionLibraryProxy` 6, `FunctionUtilWrapper` 6.
  2. `metadata.sequence` per-class uncov: `DBSequence` 31 (largest), `SequenceCached` 11, `SequenceLibraryImpl` 10, `SequenceLibraryProxy` 6.
  3. The track names `FunctionLibraryImpl, DatabaseFunction` for function and `SequenceLibraryImpl, SequenceCached` for sequence — omitting `Function` (the entity-backed class) and `DBSequence` (the abstract sequence base).
- **Outcome**: A Phase B implementer reading the track strictly may skip these classes. Both are well-covered already (74.7% and 78.2%) but together they account for ~55 uncov out of ~141 total uncov in their packages — about 40% of the gap.
- **Track coverage**: Partial. The package-level uncov totals are correct, but the named-class enumeration is non-exhaustive. Could be clarified by adding "et al." or naming all >5-uncov classes.

### Edge case: Existing DBSequenceTest is large (26 @Test methods, 967 lines) but DBSequence still at 78.2%

- **Trigger**: checking pre-existing test surface to gauge how much Track 16 work is genuinely new.
- **Code path trace**:
  1. `core/src/test/java/.../metadata/sequence/DBSequenceTest.java` — 26 @Test, 967 lines.
  2. `DBSequence` line coverage: 111/142 = 78.2% (31 uncov).
  3. Despite the large existing surface, 31 lines remain uncovered. Likely candidates: error/exception paths, retry-on-NeedRetryException logic in the cached/ordered subclasses, edge cases in the `next()`/`current()` arithmetic.
- **Outcome**: Step decomposition for sequence work should treat DBSequenceTest as **extension** rather than new authoring, focusing on the residual uncovered branches. Same likely applies to schema tests (existing `AlterClassTest`, `AlterSchemaPropertyTest`, etc.).
- **Track coverage**: Implicit in "carry forward Tracks 5–15 conventions" — but the track does not flag that significant test surface already exists and Step 1 baseline remeasurement should also catalog existing test classes by target.

### Integration: Schema fixture pattern (claim: reusable by Tracks 17, 18, 22)

- **Plan claim**: "Schema fixtures established here may be reused by Tracks 17, 18, and 22."
- **Actual entry point**: No package-level fixture class exists today in `core/src/test/java/.../metadata/schema/`. Existing schema tests use `DbTestBase` or `BaseMemoryInternalDatabase` directly without a dedicated `SchemaTestFixtures` helper.
- **Caller analysis (PSI `findClass`)**:
  - `SchemaTestBase` — NOT FOUND
  - `AbstractSchemaTest` — NOT FOUND
  - `DbTestBase` — exists at `core/src/test/java/.../DbTestBase.java`
  - `BaseMemoryInternalDatabase` — exists at `core/src/test/java/.../BaseMemoryInternalDatabase.java`
  - `SchedulerTestFixtures` — exists at `core/src/test/java/.../schedule/SchedulerTestFixtures.java` (Track 11 precedent: package-private fixture utility class shared by 3 tests in the same package; imports `metadata.function.Function`).
- **Breaking change risk**: None — additive only. But the "may be reused by Tracks 17, 18, 22" framing is aspirational without a concrete shape: Track 17 (security/auth) typically uses built-in OUser/ORole classes that don't need new schema; Track 18 (index) does need schema but typically inlines `schema.createClass(...)` per-test; Track 22 is a sweep with diverse needs.
- **Verdict**: MATCHES with the SchedulerTestFixtures precedent — a package-private utility class (e.g., `SchemaTestFixtures` in the schema test package) is precedented and reasonable. But the cross-track reuse claim is **soft**: there is no concrete reuse plan documented for Tracks 17/18/22. This is acceptable as a strategic signal (per scope-indicator semantics) but should not be treated as a binding deliverable for Track 16.

### Integration: DatabaseFunctionFactory SPI hookup

- **Plan claim**: implicit — testing `DatabaseFunction` and the function library.
- **Actual entry point**: `core/src/main/resources/META-INF/services/com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionFactory:21` registers `DatabaseFunctionFactory` as an SQLFunctionFactory implementer. `DatabaseFunctionFactory.create(...)` returns a `DatabaseFunction` per session. Polymorphic dispatch through `SQLFunction.execute()` (606 total refs) reaches `DatabaseFunction.execute()` at runtime.
- **Caller analysis (PSI)**: Direct PSI ref-search on `DatabaseFunction.execute()` returns 0, but this is the same interface-dispatch trap as Schema*Proxy: callers go through `SQLFunction.execute()`, not via the concrete class. The `SQLFunctionFactory` interface's lookup is done by the SQL parser/runtime in `core/sql/functions/`. Track 6 (per the Track 6 episode) already touched `CustomSQLFunctionFactory` and `SQLFunctionFactoryTemplate` testing.
- **Breaking change risk**: None — additive.
- **Verdict**: MATCHES — the route to test `DatabaseFunction` is to `createFunction(...)` via `FunctionLibrary` and execute it through `session.command("SELECT myFn(...)")` or equivalent. Tests can also exercise `DatabaseFunction` directly via constructor + execute.

---

## Part 2: Findings

### Finding T1 [should-fix]

**Certificate**: Edge case: IndexConfigProperty has 0 inbound callers.
**Location**: Track 16 **What** subsection, `core/metadata/schema` enumeration. Source: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/IndexConfigProperty.java`.
**Issue**: `IndexConfigProperty` is a 13-line, 0%-covered DTO whose only PSI references are its own self-recursive `copy()` call. No production or test code constructs, reads, or holds it anywhere in the repository (verified via PSI all-scope `ReferencesSearch` and per-method `MethodReferencesSearch`). Per the carry-forward dead-code reframe convention (Tracks 9–15), driving "live coverage" of this class would be wrong; the right action is a `IndexConfigPropertyDeadCodeTest` pin and a forward to Track 22's deferred-cleanup queue. The track's current scope language ("schema operations... schema proxies") sets up the implementer to inadvertently fabricate test coverage of dead code.
**Proposed fix**: In Track 16's **What** subsection (or in the Step 1 baseline note), explicitly call out `IndexConfigProperty` as a dead-code pin candidate. Add to the track a single step (or fold into the schema step) that:
1. Verifies dead status with all-scope PSI ReferencesSearch (record output in baseline file).
2. Adds `IndexConfigPropertyDeadCodeTest` per the convention.
3. Forwards to Track 22's deferred-cleanup queue with the dead-code lockstep group format used in Tracks 14/15.

### Finding T2 [should-fix]

**Certificate**: Edge case: Track plan's named class list misses the largest uncov class in two of three sub-packages.
**Location**: Track 16 **What** subsection — function and sequence enumerations. Source: per-class uncov breakdown from JaCoCo.
**Issue**: For `metadata.function`, the largest uncov class is `Function` (24 uncov; class-as-entity, extends `IdentityWrapper`), but the **What** lists only `FunctionLibraryImpl` and `DatabaseFunction`. For `metadata.sequence`, the largest uncov class is `DBSequence` (31 uncov; abstract sequence base extended by `SequenceCached` and `SequenceOrdered`), but the **What** lists only `SequenceLibraryImpl` and `SequenceCached`. A Phase B implementer reading the **What** strictly may skip these and miss ~55 uncov of the ~141 total package gap (~40%). Together with the existing `DBSequenceTest` having 26 @Test methods at 967 lines (yet still 78.2% coverage), the plan should also note that Track 16 is largely **extension** of existing tests rather than new authoring.
**Proposed fix**:
1. Update the **What** subsection to add `Function` to the function bullet and `DBSequence` to the sequence bullet (or use "et al." after the listed examples).
2. In **How**, add a sentence: "A significant body of tests already exists (`DBSequenceTest` 26 @Test/967 lines; `SchemaClassImplTest`, `AlterClassTest`, `AlterSchemaPropertyTest`, etc.). Step 1 baseline should catalog existing test classes per target so subsequent steps prefer extending existing classes when scope fits, per the carry-forward 'extend existing test classes' convention from CLAUDE.md."

### Finding T3 [should-fix]

**Certificate**: Premise: Schema*Proxy methods appear "0 prod callers" but are live via interface dispatch.
**Location**: Track 16 **What** mentions "schema proxies" as an in-scope target. Source: `Schema{,Class,Property}Proxy.java`, `MetadataDefault.java`, `Schema{,Class,Property}Internal` interfaces.
**Issue**: A naive PSI find-usages on `SchemaClassProxy.getName()` (and ~60 sibling methods) returns 0 production callers — but the class is fully live, dispatched through the `SchemaClass` interface. The same trap applies to `SchemaProxy`, `SchemaPropertyProxy`, `FunctionLibraryProxy`, `SequenceLibraryProxy`. Without an explicit warning, a Phase B implementer applying the carry-forward dead-code reframe convention (Tracks 9–15) may incorrectly classify these proxy methods as dead and pin them via `*DeadCodeTest`, which would be wrong. The Track 14 episode noted abstract-base traps (e.g., `EntityHookAbstract`) but did not codify the "interface-dispatched proxy" trap.
**Proposed fix**: Add a sentence to **How**: "Note: `Schema*Proxy` and `*LibraryProxy` methods appear with 0 direct PSI callers because dispatch goes through the `Schema`/`SchemaClass`/`SchemaProperty`/`FunctionLibrary` interface. Tests should call them via the public API (`session.getMetadata().getSchema()`, `getFunctionLibrary()`, `getSequenceLibrary()`) — not via direct concrete-class references. Do not pin proxy methods as `*DeadCodeTest` based on direct PSI references alone; check super-method callers via `findSuperMethods()` first."

### Finding T4 [suggestion]

**Certificate**: Edge case: Track scope omits `core/metadata/schema/schema` and `core/metadata/schema/validation`.
**Location**: Track 16 **What** and **Constraints** subsections.
**Issue**: The two packages `core/metadata/schema/schema` (interfaces, 98.3%) and `core/metadata/schema/validation` (5 Validation*Comparable wrappers, 100%) are not listed in the **What**, but the **Constraints** says "In-scope: only the listed `core/metadata*` packages." Both are already at near-perfect coverage so this is documentation polish, not a coverage gap. But strict reading produces ambiguity for the Phase B implementer ("what if the validation classes regress?").
**Proposed fix**: Add a short bullet to **What** or a clarifying line to **Constraints**: "`core/metadata/schema/schema` (98.3%) and `core/metadata/schema/validation` (100%) are intentionally out of scope — already at near-full coverage; any regressions are caught by other tracks via their interface usage."

### Finding T5 [suggestion]

**Certificate**: Premise: Cited uncov figures are accurate (within drift tolerance).
**Location**: Track 16 **What** subsection per-package uncov numbers.
**Issue**: All four cited numbers have drifted by 3–46 uncov lines since the plan was written. Per the carry-forward Step 1 baseline-remeasurement convention (Tracks 9, 10, 14 precedent), the track should explicitly call out that Step 1 will write `track-16-baseline.md` from a fresh JaCoCo run. Currently the **How** subsection does not mention baseline remeasurement.
**Proposed fix**: Add to **How**: "Step 1 remeasures per-package coverage from a fresh JaCoCo run and writes `track-16-baseline.md` (carry-forward from Tracks 9/10/14). The plan-cited figures (1,278/74/75/18 uncov) are stale and should be refreshed before scope is finalized."

### Finding T6 [suggestion]

**Certificate**: Integration: Schema fixture pattern.
**Location**: Track 16 **Interactions** subsection.
**Issue**: The claim "Schema fixtures established here may be reused by Tracks 17, 18, and 22" is aspirational. Track 17 (security) typically uses built-in `OUser`/`ORole` classes that ship with the database — no schema fixtures needed. Track 18 (index) does benefit from a schema fixture but typically inlines `schema.createClass(...)` per-test for clarity. Track 22 is a heterogeneous sweep. The `SchedulerTestFixtures` precedent (Track 11) is package-private and not cross-track reused. Without a concrete reuse plan, this **Interactions** claim is soft and risks distorting Track 16's design decisions toward over-generalization.
**Proposed fix**: Either (a) downgrade the claim — change "may be reused" to "may be referenced by analogy" and note that fixtures are package-scoped per the SchedulerTestFixtures precedent — or (b) cite the specific fixture(s) Tracks 17/18/22 plan to consume. Option (a) is preferred unless Tracks 17/18/22 owners commit to specific fixture consumption.

### Finding T7 [suggestion]

**Certificate**: Premise: Function class extends IdentityWrapper; DatabaseFunction implements SQLFunction.
**Location**: Track 16 **What** function bullet.
**Issue**: The track describes `core/metadata/function` as "function library" but the package contains two distinct concepts: (1) the persistent function record (`Function extends IdentityWrapper`, the `OFunction`-class entity backing custom user functions in the database), and (2) the SQL function dispatcher (`DatabaseFunction implements SQLFunction`, `DatabaseFunctionFactory implements SQLFunctionFactory`, registered as SPI). Testing strategies differ: (1) is a record-shape test (`Function.execute(args)` runs a script); (2) is an SPI-loader + SQL-dispatch test that overlaps with Track 6 (SQL functions, `CustomSQLFunctionFactory`). The track's **How** doesn't clarify which path each test should take.
**Proposed fix**: Add to **How**: "The function library mixes two concepts: (a) persistent custom-function records via `Function`/`FunctionLibraryImpl` (test via `library.createFunction()` + record round-trip + `Function.execute()`), and (b) SPI dispatch via `DatabaseFunction`/`DatabaseFunctionFactory` (test via `SELECT myFn(...)` after registering through `library.createFunction()`). The latter overlaps with Track 6's `CustomSQLFunctionFactory` work — coordinate with Track 6's deferred items if relevant."

---

## Carry-forward consistency check

Track 16 is purely additive test work; production code is not changed (per Tracks 14/15 carry-forward). The track description correctly:
- inherits the D2 standalone-vs-DbTestBase convention ("Carry forward Tracks 5–15 conventions");
- uses the Track 14 baseline pattern (implicit, but Finding T5 notes it should be made explicit);
- preserves the public-API-over-internals constraint (avoids `SchemaShared` synchronization probing);
- routes dead-code findings to Track 22's deferred-cleanup queue (implicit; Finding T1 makes this concrete).

No conflict with the Track 22 sweep scope. No conflict with the Tracks 17/18 dependencies (they depend on Track 1, not Track 16). No production-code changes — no need for the WHEN-FIXED marker convention here unless Step-1 audits surface new bugs.

---

## Verdict summary

- **0 blocker** — track is executable as-is.
- **3 should-fix** — T1 (IndexConfigProperty dead-code pin), T2 (named-class list completeness + existing-test cataloging), T3 (proxy-interface-dispatch trap).
- **4 suggestion** — T4 (out-of-scope clarification), T5 (explicit baseline remeasurement), T6 (downgrade Interactions reuse claim), T7 (function-package dual-concept clarification).
- **0 skip** — track is needed; no functionality has been made redundant by prior tracks.
