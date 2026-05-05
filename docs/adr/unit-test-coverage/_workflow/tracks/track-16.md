# Track 16: Metadata Schema & Functions

## Description

Write tests for schema management and function/sequence libraries.
After Phase A reviews (technical / risk / adversarial — all
PSI-grounded) the original "drive every named class via DbTestBase"
framing has been **partially reframed**: the schema/function core is
overwhelmingly live (PSI all-scope `ReferencesSearch` shows
`SchemaClassImpl` 127 prod refs, `SchemaImmutableClass` 64,
`SchemaShared` 28, `SchemaClassInternal` 132, etc. — no class in
scope has 0 production references), but a small dead-code subset
needs `*DeadCodeTest` pinning per the Tracks 9–15 carry-forward
convention. Sequence is already above the project gate
(85.4% line / 73.4% branch — fresh measurement) and collapses to a
half-step gap-fill; the largest single uncov surface is the schema
package's `PropertyTypeInternal` enum body (~371 uncov across the
outer enum + the anonymous enum-instance subclasses; iter-2 PSI
re-check: 21 anonymous instance bodies, of which the JaCoCo
`$10`–`$21` rows carry the bulk of the residual uncov) which the
original plan did not name explicitly.

> **What** (PSI-validated, post-review):
> - `core/metadata/schema` (~1,278 uncov pre-review, **stale baseline
>   — remeasure in Step 1**) — focus on the *live* surface:
>   - **Live drives**: `SchemaShared` (84.0% line / 66.5% branch),
>     `SchemaPropertyImpl` / `SchemaPropertyEmbedded` /
>     `ImmutableSchemaProperty`, `SchemaClassImpl` /
>     `SchemaClassEmbedded` / `SchemaImmutableClass`,
>     `SchemaEmbedded` / `ImmutableSchema`, `GlobalPropertyImpl`,
>     `Schema*Proxy` / `*LibraryProxy` (drive via the public
>     `Schema` / `SchemaClass` / `SchemaProperty` /
>     `FunctionLibrary` / `SequenceLibrary` interfaces — see
>     **How** below for the super-method-dispatch trap).
>   - **`PropertyTypeInternal` parameterized step** (~176 uncov on
>     the outer enum + ~195 uncov across `$10`–`$21` anonymous
>     enum-instance subclasses, baseline 66.0% / 62.7%) — the single
>     largest uncov class in the package. Drive `convert(Object,
>     PropertyTypeInternal, SchemaClass, DatabaseSessionEmbedded)`
>     via `@RunWith(Parameterized.class)` tables grouped by logical
>     family (numeric, collection, link, embedded, datetime/binary)
>     to keep step granularity reasonable. Existing
>     `SchemaPropertyTypeConvertTest` (456 LOC, 54 `@Test`) and
>     `TestSchemaPropertyTypeDetection` (270 LOC, 43 `@Test`) cover
>     ~66% of these paths — Track 16 extends them rather than
>     duplicating.
>   - **Dead-code pins** (PSI all-scope `ReferencesSearch` — confirmed
>     0 prod refs, 0 test refs):
>     - `IndexConfigProperty` (48 LOC / 13 uncov, 0% line) — fully
>       orphaned class. Pin via `IndexConfigPropertyDeadCodeTest`,
>       lockstep delete forwarded to Track 22.
>     - `BalancedCollectionSelectionStrategy` (12 uncov, 25.0% line /
>       0.0% branch) and `DefaultCollectionSelectionStrategy` (~6
>       uncov, 50.0% line) — sole prod ref is the `META-INF/services/
>       .../CollectionSelectionStrategy` SPI registration; no public
>       API on `SchemaClass` switches strategies (`SchemaClassImpl:85`
>       hard-codes `new RoundRobinCollectionSelectionStrategy()`).
>       Pin shape via `BalancedCollectionSelectionStrategyDeadCodeTest`
>       + `DefaultCollectionSelectionStrategyDeadCodeTest` and forward
>       deletion to Track 22 (lockstep with the SPI service file).
>     - `CollectionSelectionFactory.getStrategy(String)` and the
>       SPI-loop wiring (`registerStrategy()`, `newInstance()`) have
>       0 callers — pin via
>       `CollectionSelectionFactoryDeadCodeTest`, lockstep with the
>       strategy classes.
>   - **Live cluster-selection coverage**: `RoundRobinCollectionSelectionStrategy`
>     (currently 100% line / 100% branch via `SchemaClassImpl` direct
>     instantiation) — confirm shape pin in Step 1 and pre-empt any
>     regression. The strategy's `getName()` registration is
>     test-only-reachable via the SPI service file's third entry.
>   - **`PropertyTypeInternal` should-be-final / boundary pins**: per
>     Track 14/15 precedent, sweep for mutable public-static fields
>     (PSI `MethodReferencesSearch` for writes); pin any 0-write
>     mutable-public-static fields as test-only and forward
>     `final`-fix to Track 22 with a WHEN-FIXED marker.
>   - **Out-of-scope-by-design**:
>     - `core/metadata/schema/schema` (interface package — 98.3% line)
>       and `core/metadata/schema/validation` (5
>       `Validation*Comparable` wrappers — 100% line) are intentionally
>       NOT targeted; already at near-full coverage and exercised
>       transitively via `Schema*` consumer code.
> - `core/metadata/function` (~74 uncov, **stale baseline —
>   remeasure in Step 1**) — split into two distinct concepts:
>   - `FunctionLibraryImpl` / `FunctionLibraryProxy` /
>     `FunctionLibrary` interface — persistent custom-function
>     registry. Drive via
>     `session.getMetadata().getFunctionLibrary().createFunction(name)`
>     + lookup + drop + reload.
>   - `Function` (24 uncov, biggest in package — extends
>     `IdentityWrapper`, the persistent function record) — record
>     round-trip + `Function.execute(args)` via stored function
>     dispatch.
>   - `DatabaseFunction` (14 uncov, 36.4% line / 16.7% branch) +
>     `DatabaseFunctionFactory` — SPI dispatch path. The factory
>     class is reachable only through `META-INF/services/SQLFunctionFactory:21`
>     and `DatabaseFunction` is created only inside
>     `DatabaseFunctionFactory.createFunction()`. To exercise
>     `DatabaseFunction.execute()` live, register a stored function
>     via `library.createFunction(...)` and invoke it as
>     `SELECT myFn(args)` — direct constructor calls would be
>     test-only and bypass the SPI loop. The SPI dispatch overlaps
>     Track 6 (`CustomSQLFunctionFactory`) — coordinate via Track 22
>     deferred-cleanup queue if any duplicate coverage surfaces.
>   - `FunctionDuplicatedException`, `FunctionUtilWrapper` — POJO /
>     exception shapes, standalone tests acceptable.
> - `core/metadata/sequence` (~75 uncov pre-review; **fresh
>   measurement: 85.4% line / 73.4% branch — already above the
>   project 85%/70% gate**). Collapses to a half-step alongside
>   function library: gap-filler targeting `DBSequence` (31 uncov,
>   78.2% line — biggest in package), `SequenceLibraryProxy` (6
>   uncov, 60.0% line), and the residual edges in `SequenceCached`
>   (11 uncov), `SequenceLibraryImpl` (10 uncov). Carry-forward
>   `DBSequenceTest` already has 26 `@Test` methods at 967 LOC —
>   Track 16 extends it rather than authoring new test classes.
> - `core/metadata/schema/clusterselection` (~18 uncov, **stale
>   baseline — remeasure in Step 1**) — see the dead-code-pin block
>   under `core/metadata/schema` above; the entire 18-line gap
>   collapses into the cluster-selection dead-pin step.
>
> **How**:
> - Schema tests that mutate metadata need a database session
>   (`DbTestBase`); pure POJO/exception/comparator tests are
>   standalone (D2 — see design.md). Decision per-class.
> - Focus on property type validation, schema evolution
>   (add/drop class, add/drop property, rename), and the per-arm
>   `PropertyTypeInternal.convert(...)` paths via parameterized
>   tests. Index creation tests must `session.getMetadata().getSchema().reload()`
>   before asserting visibility — disk-mode CI runs the
>   `SchemaShared.releaseSchemaWriteLock` → `saveInternal` path
>   while memory-mode runs `reload`; the explicit `reload()` call
>   is the portable assertion path (R8 working note).
> - **Schema proxy super-method-dispatch trap** (T3): naive PSI
>   find-usages on `SchemaClassProxy.getName()` and the ~60
>   sibling proxy methods returns 0 direct production callers
>   because dispatch flows through the `SchemaClass` /
>   `SchemaProperty` / `Schema` / `FunctionLibrary` /
>   `SequenceLibrary` interface (`SchemaClass.getName()` alone has
>   161 prod callers via the interface). Tests MUST drive proxy
>   methods via the public-API interface
>   (`session.getMetadata().getSchema()...`) — not via direct
>   concrete-class references — and Phase B implementers MUST
>   check `findSuperMethods()` before pinning any proxy method as
>   `*DeadCodeTest`. Track 14's abstract-base trap codified the
>   inverse case (0 production subclasses); this codifies the
>   interface-dispatched-proxy variant.
> - Function/Sequence library tests cover registration, lookup,
>   removal, and persistence across session reload. The
>   `DatabaseFunction.execute()` path is reachable only via
>   `SELECT myFn(args)` after the function is registered through
>   `library.createFunction(...)`; direct construction yields
>   test-only coverage that does not exercise the SPI loop.
> - **`SchemaShared` lock discipline** (R7 working note): the
>   `acquireSchemaReadLock` / `releaseSchemaReadLock` /
>   `acquireSchemaWriteLock(session)` / `releaseSchemaWriteLock(session)`
>   / `releaseSchemaWriteLock(session, save)` methods ARE part of
>   the public lock API and are fair game for direct testing. The
>   underlying `ReentrantReadWriteLock` field is private and must
>   not be probed via reflection. Tests should NOT hold a
>   `SchemaShared` reference past the end of an `executeInTx`
>   callback (flakes under `-Dyoutrackdb.test.env=ci`); re-fetch
>   `session.getMetadata().getSchema()` after schema mutations
>   rather than caching the reference.
> - **Phase B Step 1 remeasures live coverage** for all three
>   target packages and writes `track-16-baseline.md` (precedent:
>   Tracks 9, 10, 14 baselines). The plan-cited `1,278 / 74 / 75 /
>   18` uncov figures are stale — concrete per-class uncov targets
>   are derived from the remeasured XML. Step 1 also spot-checks
>   2–3 existing `core/metadata/*Test.java` classes for inert-test
>   bugs (Track 12 lesson — missing `@Test`, identity-based
>   assertions, dead ignored bodies). Adversarial review iter-1
>   already spot-checked `SchemaClassImplTest`,
>   `CaseSensitiveClassNameTest`, `SchemaPropertyTypeConvertTest`,
>   `DBSequenceTest`, `FunctionLibraryTest` — all clean (A9).
> - **Dead-code pin pattern** (Track 9/10/14/15 precedent): for
>   each confirmed-dead class, a dedicated `<Class>DeadCodeTest`
>   pins structural shape (modifiers, signatures, dispatcher
>   tables, factory-key registrations) so partial deletion stays
>   valid. The cluster-selection trio
>   (`Balanced` + `Default` + `CollectionSelectionFactory.getStrategy`
>   + the SPI service file) deletes lockstep; `IndexConfigProperty`
>   deletes solo (no dependent live surface). All deletion
>   contingencies forward to Track 22's deferred-cleanup queue
>   with `// WHEN-FIXED: Track 22 — delete X` markers.
> - Carry forward Tracks 5–15 conventions: D2 standalone-vs-DbTestBase
>   per-class; PSI all-scope `ReferencesSearch` before driving live
>   coverage; per-method dead-code pinning so partial deletion
>   stays valid; `*DeadCodeTest` vs `*TestOnlyOverloadTest` naming
>   distinction; broader ephemeral-identifier sweep regex
>   (`Track[ -]?[0-9]+|Step[ -]?[0-9]+|\bPhase [A-Z]\b`); tracked-
>   `spawn()` helper for any worker-thread tests with `@After` join
>   discipline.
>
> **Constraints**:
> - In-scope: only the listed `core/metadata*` packages. Security
>   metadata (`core/metadata/security*`) is owned by Track 17.
> - The `SchemaShared` public lock-API methods are fair game; the
>   private `ReentrantReadWriteLock` field is not (no reflection
>   probes). Tests prefer `session.executeInTx(...)` for schema
>   mutations whose snapshot must be visible to a subsequent
>   assertion.
>
> **Interactions**:
> - Depends on Track 1 (coverage measurement infrastructure).
> - **No fixture extraction is committed at Track 16 time.** The
>   pre-review claim "Schema fixtures established here may be
>   reused by Tracks 17, 18, and 22" is dropped per A5/R4: there
>   is no anchoring step deliverable, no concrete `SchemaTestFixtures`
>   class today, and Tracks 17/18/22 owners have not committed to
>   specific fixture consumption. Track 16 may discover patterns
>   that later tracks adopt by analogy, but any DRY hoist for
>   cross-track reuse goes to Track 22's deferred-cleanup queue.

### Phase A preflight

- **Pre-rebase HEAD:** `e37b786054` (tip of `unit-test-coverage` after
  Track 15 was marked complete)
- **Post-rebase HEAD:** `5c23365dda` (after rebasing onto `origin/develop`
  tip `c01f00be5d`)
- **Rebase content:** 2 incoming commits (`c01f00be5d`,
  `d8db58614b`) — both pure GitHub Actions version bumps to
  `.github/workflows/weekly-beta-release.yml` only. Zero impact on
  Java sources, test sources, build files, or formatter
  configuration. Per plan constraint #11 step 3, the full `core`
  unit-test suite would normally run after rebase; this rebase has
  null risk surface for tests, so the suite run is skipped and the
  rationale is recorded here for audit.
- **Working tree status post-rebase:** clean.
- **mcp-steroid preflight:** reachable; `unit-test-coverage` project
  is open at `/home/andrii0lomakin/Projects/ytdb/unit-test-coverage`
  (matches cwd). PSI find-usages will be used for all symbol audits
  in Phase A.

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/7 complete)
- [ ] Track-level code review

## Base commit
`7ccf28f311d8d11e0d944c3f6d6163efe9574a0d`

## Reviews completed
- [x] Technical (iter-1: 0 blockers / 3 should-fix / 4 suggestions
      — see `reviews/track-16-technical.md`. iter-2 gate: PASS, 7
      VERIFIED / 0 STILL OPEN / 0 REGRESSION — see
      `reviews/track-16-technical-iter2-gate.md`.)
- [x] Risk (iter-1: 0 blockers / 4 should-fix / 5 suggestions — see
      `reviews/track-16-risk.md`. iter-2 gate: PASS, 9 VERIFIED / 0
      STILL OPEN / 0 REGRESSION — see
      `reviews/track-16-risk-iter2-gate.md`. Risk-tag recommendation
      for Phase B: `risk: high` on the cluster-selection dead-code
      step and the `PropertyTypeInternal` parameterized step.)
- [x] Adversarial (iter-1: 1 blocker / 4 should-fix / 4 suggestions
      — see `reviews/track-16-adversarial.md`. iter-2 gate: PASS, 9
      VERIFIED / 0 STILL OPEN / 0 REGRESSION; partial-reframe
      sufficient, no escalation — see
      `reviews/track-16-adversarial-iter2-gate.md`.)

## Steps

- [x] Step 1: Remeasure baseline + dead-code pins (`IndexConfigProperty` + cluster-selection trio + SPI service file)
  - [x] Context: safe
  > **Risk:** medium — multi-file dead-code pins across 4 new test
  > classes (Track 15 Step 1 precedent: medium)
  >
  > **What was done:** Ran `./mvnw -pl core -am clean package -P coverage`
  > and wrote `docs/adr/unit-test-coverage/_workflow/track-16-baseline.md`
  > capturing per-class uncov for the four target packages
  > (`core/metadata/schema*`, `core/metadata/function`,
  > `core/metadata/sequence`, `core/metadata/schema/clusterselection`)
  > plus per-class disposition tagging (live-drive / dead-code-pin /
  > out-of-scope-by-design) and the per-step uncov budget for
  > Steps 2–7. Authored four `*DeadCodeTest` classes pinning
  > structural shape with `// WHEN-FIXED: Track 22` forwarding
  > markers: `IndexConfigPropertyDeadCodeTest` (4 tests — ctor + five
  > getters + self-recursive `copy()` identity contract);
  > `BalancedCollectionSelectionStrategyDeadCodeTest` (5 tests —
  > `NAME == "balanced"`, length-1 short-circuit, multi-cluster
  > `min(approxCount)` selection via Mockito stubs, two-arg
  > delegation); `DefaultCollectionSelectionStrategyDeadCodeTest` (4
  > tests — `NAME == "default"`, two-arg always-first-cluster
  > contract, four-arg ignore-of-`selection` parameter pinned);
  > `CollectionSelectionFactoryDeadCodeTest` (10 tests — `registerStrategy()`,
  > `getStrategy(String)`, `newInstance(String)` plus SPI service
  > file existence + 3-entry membership assertion). Re-verified
  > Phase A iter-1's A9 inert-test spot-check on `SchemaClassImplTest`,
  > `CaseSensitiveClassNameTest`, `SchemaPropertyTypeConvertTest`,
  > `DBSequenceTest`, `FunctionLibraryTest` — all clean. Coverage
  > gate vs `origin/develop`: 100% line / 100% branch on the changed
  > production lines (commit is purely test-additive). Spotless
  > clean; 23/23 tests pass.
  >
  > **What was discovered:** Plan-cited uncov figures are stale
  > by 15 tracks. Fresh per-package totals: `core/metadata/schema`
  > 1 231 (plan said ~1 278), `function` 71 (plan said ~74),
  > `sequence` 70 (plan said ~75) — closed by incidental traffic
  > from Tracks 14–15. `core/metadata/schema/clusterselection` is
  > unchanged at 18.
  >
  > `CollectionSelectionFactory` is partially live, not fully dead:
  > the **class itself** is instantiated by `SchemaShared`'s
  > `collectionSelectionFactory` field initializer, so the
  > constructor is reachable. Only the `getStrategy(String)` +
  > `registerStrategy()` + `newInstance(String)` triple plus the
  > SPI loop is dead. The pin scopes accordingly to method-level
  > rather than class-level — finer split than the plan's wording
  > suggested.
  >
  > The plan's SPI-service-file ordering claim ("first line matches
  > Balanced; second line matches Default; third line matches
  > RoundRobin") disagrees with the on-disk file order, which is
  > RoundRobin / Default / Balanced. The pin asserts set
  > membership of all three FQCNs (with `assertEquals(3, entries.size())`
  > + explicit `entries.contains(...)` triplet) rather than line
  > ordering — strictly more falsifiable for the Track 22 deletion
  > goal (any deletion of "balanced" or "default" entries is caught).
  >
  > **Cross-track impact:** Track 22's deferred-cleanup queue
  > should record cluster-selection deletion at method-level
  > granularity, not class-level. Lockstep group: Balanced + Default
  > strategies + the three method/loop deletions inside
  > `CollectionSelectionFactory` + the `balanced`/`default` SPI
  > entries (RoundRobin entry stays). A wider follow-up sweep in
  > Step 6 may flag the entire factory field+getter
  > (`SchemaShared.collectionSelectionFactory` +
  > `Schema.getCollectionSelectionFactory()`) as deletable — in
  > which case the lockstep expands to whole-class deletion of
  > `CollectionSelectionFactory`. Recorded as a minor refinement;
  > no ADJUST or ESCALATE needed.
  >
  > **What changed from the plan:**
  > - SPI-service-file pin uses set-membership assertion (3 FQCNs)
  >   rather than line-order assertion. No effect on Steps 2–7.
  > - Cluster-selection deletion target narrowed to method-level
  >   (`getStrategy(String)` + `registerStrategy()` + SPI loop +
  >   two SPI entries) — `CollectionSelectionFactory` class itself
  >   is live via `SchemaShared` field init. Recorded in the four
  >   `*DeadCodeTest` Javadocs and in `track-16-baseline.md`'s
  >   cluster-selection disposition row; Track 22 backlog absorbs
  >   the refinement at the deferred-cleanup absorption step.
  >
  > **Key files:**
  > - `core/src/test/java/.../core/metadata/schema/IndexConfigPropertyDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/metadata/schema/clusterselection/BalancedCollectionSelectionStrategyDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/metadata/schema/clusterselection/DefaultCollectionSelectionStrategyDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/metadata/schema/clusterselection/CollectionSelectionFactoryDeadCodeTest.java` (new)
  > - `docs/adr/unit-test-coverage/_workflow/track-16-baseline.md` (new — workflow file)

- [ ] Step 2: Schema property operations — `SchemaPropertyImpl` / `SchemaPropertyEmbedded` / `ImmutableSchemaProperty` / `GlobalPropertyImpl`
  > **Risk:** medium — multi-file tests via `DbTestBase` against
  > schema mutation API (Track 15 Step-N precedent: medium)
  > **What:** Extend `AlterSchemaPropertyTest` and add new test
  > classes targeting the live property surface — property add /
  > drop / rename / collation / linked-class / linked-type / read-only
  > / not-null / mandatory / regex / min / max / default-value
  > flags; `getOriginalDefinition()`, `convertToType()`, `index()`
  > on the property, custom attributes, `compareTo`, `equals`,
  > `hashCode`. Index-creation assertions MUST call
  > `session.getMetadata().getSchema().reload()` before reading the
  > index back (R8 working note — disk-mode CI runs the
  > `releaseSchemaWriteLock` → `saveInternal` path; memory-mode
  > differs). Pin `ImmutableSchemaProperty` immutability shape via
  > standalone tests (no DbTestBase — pure POJO assertions). Pin
  > `GlobalPropertyImpl` round-trip via standalone tests. All schema
  > mutations follow the R7 working note: prefer
  > `session.executeInTx(...)`; do not cache `SchemaShared`
  > references past a callback boundary.

- [ ] Step 3: Schema class operations — `SchemaClassImpl` / `SchemaClassEmbedded` / `SchemaImmutableClass` + `Internal` / `Proxy` boundary cases
  > **Risk:** medium — multi-file tests via `DbTestBase` against
  > class-level schema mutation API
  > **What:** Extend `SchemaClassImplTest`, `AlterClassTest`,
  > `AlterSuperclassTest`, `TestMultiSuperClasses` and add new test
  > classes targeting the live class surface — class add / drop /
  > rename / set-superclass / set-superclasses / multi-inheritance
  > diamond cases; abstract / cluster-id / strict-mode / encryption
  > attributes; `getCustom()` / `setCustom()` / `removeCustom()`;
  > `truncateClass`, `truncateCluster`, `addClusterId`,
  > `removeClusterId`, polymorphic class iteration; `compareTo`,
  > `equals`, `hashCode`. Pin `SchemaImmutableClass` immutability
  > shape via standalone tests. Cover `SchemaClassInternal` /
  > `SchemaPropertyInternal` boundary methods reachable through the
  > internal API. `SchemaClassProxy` and `SchemaPropertyProxy`
  > coverage rides on the public-API interface dispatch (T3 trap
  > note — do NOT pin proxy methods as `*DeadCodeTest`; check
  > `findSuperMethods()` first).

- [ ] Step 4: `PropertyTypeInternal` parameterized convert — numeric + datetime/binary families
  > **Risk:** medium — multi-shape parameterized tests over 8+
  > enum constants × ~5 input shapes per arm (R6 pre-emptive
  > structure)
  > **What:** Add `PropertyTypeInternalNumericConvertTest` and
  > `PropertyTypeInternalDateTimeBinaryConvertTest` — both
  > `@RunWith(Parameterized.class)` with rows keyed by
  > `(PropertyTypeInternal constant, input value, expected output,
  > expected exception)`. Numeric class covers Boolean / Integer /
  > Short / Long / Float / Double / Decimal / Byte arms with
  > positive / negative / zero / overflow / underflow / null /
  > wrong-type input shapes. DateTime/Binary class covers Date /
  > DateTime / Custom / Binary arms with valid / out-of-range /
  > null / mismatched-type input. Drives `PropertyTypeInternal.<X>.
  > convert(Object, PropertyTypeInternal, SchemaClass,
  > DatabaseSessionEmbedded)` and the static `convert(Object, Class,
  > SchemaClass, DatabaseSessionEmbedded)` switch. Standalone tests
  > where the convert path doesn't need a session; `DbTestBase`
  > only when an entity-typed input requires record context.
  > Existing `SchemaPropertyTypeConvertTest` (54 `@Test`, 456 LOC)
  > and `TestSchemaPropertyTypeDetection` (43 `@Test`, 270 LOC) are
  > extended where the existing scaffolding already provides
  > parameterized infrastructure; new test classes only when
  > existing classes are inappropriate.

- [ ] Step 5: `PropertyTypeInternal` parameterized convert — collection + link + embedded families *(parallel with Step 4)*
  > **Risk:** medium — multi-shape parameterized tests over 12+
  > enum constants × ~5 input shapes per arm
  > **What:** Add `PropertyTypeInternalCollectionConvertTest`,
  > `PropertyTypeInternalLinkConvertTest`, and
  > `PropertyTypeInternalEmbeddedConvertTest` — all
  > `@RunWith(Parameterized.class)`. Collection class covers
  > List / Set / Map arms with empty / single-element /
  > multi-element / null-element / mismatched-element-type input.
  > Link class covers Link / LinkList / LinkSet / LinkMap /
  > LinkBag arms with valid-RID / wrong-class / null /
  > mismatched-type input. Embedded class covers Embedded /
  > EmbeddedList / EmbeddedSet / EmbeddedMap arms with
  > entity-of-correct-class / entity-of-wrong-class / non-entity /
  > null input. `DbTestBase` required for link/embedded paths
  > (need RID + record context). Same convert(...) target as
  > Step 4. Parallel with Step 4 because the test classes are
  > disjoint in name and source files; both edit only their own
  > new files (existing test classes are extended only by Step 4
  > or Step 5, not both).

- [ ] Step 6: `SchemaShared` + `ImmutableSchema` + cluster-selection live coverage + Schema proxy boundary cases
  > **Risk:** medium — multi-file tests via `DbTestBase` exercising
  > `SchemaShared` public lock API + interface-dispatched proxies
  > **What:** New `SchemaSharedLockApiTest` covering the 5
  > public lock-API methods (`acquireSchemaReadLock`,
  > `releaseSchemaReadLock`, `acquireSchemaWriteLock(session)`,
  > `releaseSchemaWriteLock(session)`, `releaseSchemaWriteLock(session,
  > save)`) — direct invocation is allowed per A7 (the public
  > methods are part of the contract); the private
  > `ReentrantReadWriteLock` field stays untouched. Tests use a
  > worker-thread `spawn()` helper with `@After` join discipline
  > (Track 14 carry-forward) to verify read-write lock semantics
  > (multiple readers concurrent; writer exclusive) and the
  > save-on-release path. Add `ImmutableSchemaShapeTest` (standalone)
  > pinning the immutable schema's class-list / global-property-list
  > / version round-trip. Add `RoundRobinCollectionSelectionStrategyTest`
  > driving the live strategy via `SchemaClassImpl`'s hardwired
  > instantiation: create a class, observe round-robin cluster
  > selection across N inserts. The factory dispatch path
  > (`CollectionSelectionFactory.getStrategy(name)`) is dead-pinned in
  > Step 1 — this step does NOT exercise it. Add proxy boundary
  > tests via the public `Schema` / `SchemaClass` / `SchemaProperty`
  > / `FunctionLibrary` / `SequenceLibrary` interface (T3 trap
  > note): inactive-session rejection (`session.assertIfNotActive`
  > assertion fires), `SchemaClassProxy.createProperty(...)` returns
  > a proxy that re-wraps a fresh `SchemaPropertyImpl`, and
  > non-isomorphic delegation cases (e.g.,
  > `SchemaPropertyProxy.compareTo` falling through to the underlying
  > `SchemaPropertyImpl`'s implementation).

- [ ] Step 7: Function library + DBSequence half-step + final verification
  > **Risk:** medium — multi-file test additions + final coverage
  > verification (Track 14/15 final-step precedent: medium)
  > **What:** Three subtasks land in a single commit:
  > 1. **Function library**: extend `FunctionLibraryTest` and add
  >    `DatabaseFunctionRoundTripTest` — register a stored function
  >    via `session.getMetadata().getFunctionLibrary().createFunction(name)`,
  >    set its body via the `Function` record API (round-trip the
  >    `Function extends IdentityWrapper` shape), invoke as
  >    `SELECT myFn(args)` to drive `DatabaseFunctionFactory.
  >    createFunction(name)` → `new DatabaseFunction(...)` →
  >    `execute(...)` (A4 reframe). Cover lookup / drop / rename /
  >    duplicate-name (`FunctionDuplicatedException`) /
  >    persistence-across-reload paths. Standalone tests for
  >    `FunctionUtilWrapper` and `FunctionDuplicatedException` POJO
  >    shapes. `FunctionLibraryProxy` rides on
  >    `FunctionLibrary`-interface dispatch (T3 trap note).
  > 2. **DBSequence half-step (R3 collapse)**: extend `DBSequenceTest`
  >    (already 967 LOC / 26 `@Test`) with the residual
  >    `DBSequence` paths (31 uncov: tx-error retry, ordered/cached
  >    boundary, `next() == start + N*increment` invariant) and
  >    add `SequenceLibraryProxyTest` covering the 6-uncov proxy
  >    boundary cases. Cover `SequenceLibraryImpl` 10-uncov edges
  >    (registration, reload, drop) and `SequenceCached` 11-uncov
  >    edges (cache exhaustion / refill).
  > 3. **Final verification**: re-run
  >    `./mvnw -pl core -am clean package -P coverage`, regenerate
  >    per-package totals via `coverage-analyzer.py`, and write the
  >    delta against `track-16-baseline.md` into the step episode.
  >    Target: live-drive surfaces meet 85% line / 70% branch on
  >    each touched live class; dead-code residue (~25 lines across
  >    the 4 dead-pin classes) is pinned for lockstep deletion.
  >    Forward to Track 22's deferred-cleanup queue: 4 dead-code
  >    lockstep groups (`IndexConfigProperty`; cluster-selection
  >    trio + SPI service file; any new groups surfaced by Step 1's
  >    fresh PSI run; any production-bug WHEN-FIXED markers from
  >    `should-be-final` sweeps).

