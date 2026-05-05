# Track 15: Record Implementation & DB Tool

## Description

Write tests for the record implementation layer and database tool
utilities. After Phase A reviews (technical / risk / adversarial — all
PSI-grounded), the original "drive EntityImpl + DB-tool round-trip"
framing has been **reframed** to match Track 14's dead-code precedent
and Track 12's corrected-baseline lesson: a substantial fraction of
the listed `core/db/tool` and `core/record*` LOC is either fully dead
or test-only-reachable, and the live MemoryStream gap (item h) is
implementable only as deletion (Track 22 owns it), not as caller
migration.

> **What** (PSI-validated, post-review):
> - `core/record/impl` (~1,383 uncov, **stale baseline — remeasure in
>   Step 1**) — focus on the *live* surface:
>   - **Live drives**: `EntityImpl` (4,776 LOC, ~70.3% live; ~1,300+
>     uncov lines concentrated in 6 method-cluster seams — typed-property
>     get/set, dirty-tracking, serialization, equals/hashCode/toMap,
>     embedded semantics, link-bag conversion); `EmbeddedEntityImpl`,
>     `EdgeEntityImpl`, `VertexEntityImpl` (delegating subclasses);
>     `EntityEntry` (POJO shape — standalone); `SimpleMultiValueTracker`
>     (POJO shape — standalone); `InPlaceResult` (POJO shape —
>     standalone); `BidirectionalLinkToEntityIterator`,
>     `BidirectionalLinksIterable`, `PreFilterableLinkBagIterable`,
>     `EdgeFromLinkBagIterator`/`Iterable`, `VertexFromLinkBagIterator`/
>     `Iterable`, `EdgeIterator`/`Iterable` (iterator shapes — most
>     already have tests; close residual branches).
>   - **Dead-code reframe** (PSI all-scope `ReferencesSearch`, all 5
>     modules): `EntityHelper` — 12 of 19 public methods have **0
>     refs** (chain-dead `sort`, `getFieldValue` overload variants,
>     decoder helpers); `EntityComparator` (0 line / 46 branch coverage
>     — chain-dead via dead `EntityHelper.sort`). Pin via
>     `EntityHelperDeadCodeTest` + `EntityComparatorDeadCodeTest`.
>   - **Should-be-final shape pin**: `EntityImpl.OPPOSITE_LINK_CONTAINER_PREFIX`
>     is the only mutable-public-static field — 0 writes anywhere.
>     Pin in extended `EntityImplTest` so a refactor to non-final OR
>     a stray write fails the build.
>   - **`RecordBytes` MemoryStream methods** (linked to backlog item h)
>     — PSI shows 0 non-override callers of `fromInputStream(*)`,
>     `toStream(MemoryStream)`. Pin as dead via `RecordBytesDeadCodeTest`
>     and forward MemoryStream-overload deletion to Track 22 (the
>     migration framing in the original plan was unimplementable —
>     there are no callers to migrate).
>   - **Out-of-scope-by-design**: storage-coupled cold paths in
>     `EntityImpl` (lazy-load-from-page, page-frame zero-copy paths
>     beyond what existing `EntityImplPageFrameTest` already covers)
>     belong to Tracks 19–21.
> - `core/record` (~90 uncov, **stale baseline — remeasure in Step 1**) —
>   `RecordAbstract`, `RecordFactoryManager` (live; close residual
>   branches via shape pins). **Dead-code reframe**: `RecordVersionHelper`
>   (11 dead public methods, 0 refs), `RecordStringable` (0 implementers
>   per `ClassInheritorsSearch`), `RecordListener` (0 implementers).
>   Pin via dedicated `*DeadCodeTest` files and forward to Track 22.
> - `core/db/tool` (~889 uncov, **stale baseline — remeasure in Step 1**)
>   — **Dead-code reframe** dominates: `DatabaseRepair` (171 LOC, 0/0/0
>   main/test/self refs — fully dead), `BonsaiTreeRepair` (124 LOC,
>   0/0/0 — fully dead). **Test-only-reachable** (Track 14
>   `EntityHookAbstract` precedent): `DatabaseCompare` (0 main /
>   36 test refs), `GraphRepair` (0 main / 3 test refs — only
>   `GraphRecoveringTest`), `CheckIndexTool` (0 main / 2 test refs —
>   only `CheckIndexToolTest`). Pin shape via `*DeadCodeTest` and
>   forward deletion contingency (test-subclass retargeting) to Track 22.
>   **Live drives**: `DatabaseExport` + `DatabaseImport` round-trip
>   (live — 9 existing tests use this pattern); `DatabaseImpExpAbstract`
>   common base (close residual branches); `DatabaseRecordWalker` (close
>   residual branches via existing test extension); `DatabaseTool`
>   abstract base (shape pin only — interface-level).
> - `core/db/tool/importer` (~73 uncov, **stale baseline — remeasure
>   in Step 1**) — 12 converter classes are **live** via
>   `ImportConvertersFactory` registration but currently 0% coverage:
>   `EmbeddedListConverter`, `EmbeddedSetConverter`,
>   `EmbeddedMapConverter`, `LinkBagConverter`, `LinkConverter`,
>   `LinkListConverter`, `LinkMapConverter`, `LinkSetConverter`,
>   `LinksRewriter`, `ValuesConverter`, `AbstractCollectionConverter`,
>   `ImportConvertersFactory`. Real coverage opportunity — standalone
>   tests with mocked `ConverterData`.
>
> **How**:
> - **Test base class is per-class, not per-track.** D2 (standalone
>   over `DbTestBase`) holds for value/POJO/dead-code/converter classes.
>   `DbTestBase` is mandatory only for production classes that genuinely
>   require a session (`EntityImpl` typed-property + dirty-tracking +
>   serialization paths; `DatabaseExport`/`DatabaseImport` round-trip;
>   `EmbeddedEntityImpl` schema-bound paths). Standalone candidates:
>   `EntityEntry`, `SimpleMultiValueTracker`, `InPlaceResult`, all 12
>   importer converters, all `*DeadCodeTest` shape pins, `RecordAbstract`
>   shape, `RecordFactoryManager` registration.
> - **Phase B Step 1 remeasures live coverage** for all four target
>   packages and writes `track-15-baseline.md` (precedents:
>   `track-9-baseline.md`, `track-14-baseline.md`). The plan-cited
>   1,412 + 90 + 891 + 73 = 2,466 figure is post-Track-7 and stale by
>   7 tracks — concrete per-class uncov targets are derived from the
>   remeasured XML. Step 1 also spot-checks 2–3 existing tests in
>   `record/impl/` for inert-test bugs (Track 12 lesson).
> - **Dead-code pin pattern** (Tracks 9/10/14 precedent): for each
>   confirmed-dead class, a dedicated `<Class>DeadCodeTest` pins
>   structural shape (modifiers, signatures, dispatcher tables, ctor
>   visibility, field-stays-null reflective probes) so a future
>   refactor either updates the pin in lockstep or fails loudly. Each
>   pin carries a comment indicating which class is queued for
>   deletion in the deferred-cleanup track. Test-only-reachable
>   classes (`DatabaseCompare`, `GraphRepair`, `CheckIndexTool`,
>   `EntityComparator`) get the same pin shape PLUS a documented
>   contingency note about retargeting their existing test subclasses.
> - **DB-tool round-trip uses MEMORY storage and `ByteArrayInput/
>   OutputStream`** (NOT disk) for in-process round-trip fidelity;
>   the existing `DatabaseImportTest` uses disk and is the wall-clock
>   outlier — new tests follow the in-memory precedent of
>   `DatabaseImportSimpleCompatibilityTest`. Per-test budget: <1 s for
>   round-trip with ~5–10 entities.
> - **Round-trip fidelity assertion uses `EntityHelper.hasSameContentOf`**
>   (3 main / 2 test refs — live) entity-by-entity, NOT
>   `DatabaseCompare` (test-only-reachable; entrenching it as a
>   harness blocks Track 22 deletion). Restrict round-trip content to
>   unambiguous types (avoid numeric extremes that exercise the legacy
>   `IMPORT_BACKWARDS_COMPAT_INSTANCE` path — backlog item f, owned
>   by Track 22).
> - **Schema lookup is allowed as collaborator.** EntityImpl typed-property
>   paths inherently reach into `OClass`/`OProperty`. The plan's
>   "Do NOT touch `metadata/schema`" constraint means "do not test
>   schema mutations", not "do not exercise schema lookup". This is
>   the analogue of Track 14's "in-scope by test target, not by
>   collaborator" rule (Track 14 `EntityEmbeddedMapImplTest` already
>   imports `EntityImpl`).
> - **`@Category(SequentialTest)`** required when a test touches
>   `YourTracks` global state (DB tool round-trips run an Embedded
>   YouTrackDB instance) or `OPPOSITE_LINK_CONTAINER_PREFIX` mutable
>   static. Precedents: `SystemDatabaseDisabledTest.java:14`,
>   Track 14 Step 6 `SystemDatabaseTest`. Surefire pom config:
>   `core/pom.xml:304-307` — `parallel: classes`, `threadCountClasses=4`.
> - **Carry forward Tracks 5–14 conventions**: `TestUtilsFixture` +
>   `@After rollbackIfLeftOpen` safety net, falsifiable-regression +
>   `// WHEN-FIXED:` marker convention, `Iterable` detach-after-commit
>   pattern, `*DeadCodeTest` shape pin convention, `// forwards-to:`
>   cross-track bug-pin convention, `assertEquals(X.class, x.getClass())`
>   over `instanceof` for load-bearing wrapper-type assertions
>   (Track 11), reflective field-stays-null pin pattern for
>   dead-decoration probes (Track 14), observed-shape `Map.of(...)`/
>   `List.of(...).toString()` exact-equality pins (Track 14), tracked
>   `spawn()` helper for worker-thread tests with `@After` join
>   discipline (Track 14 Step 6). Per-step explicit naming below.
> - **Coverage-gate framing for `EntityImpl`**: the class is 4,776 LOC;
>   per the project gate the deliverable is "85% line / 70% branch on
>   changed lines" (lines added or modified by Track 15 tests'
>   production-touch surface), not "lift the whole class to 85%".
>   Phase B Step 3 declares coverage scope explicitly.
>
> **Constraints**:
> - **In-scope by test target**, not by collaborator: tests' *target*
>   class must live under `core/record*` or `core/db/tool*`.
>   Collaborator classes from `metadata/schema` (Track 16),
>   `serialization` (Tracks 12–13), or `core/db` (Track 14) are
>   permitted as fixture material — coverage credit accrues to the
>   target package, not the collaborator. The `core/db/EntityFieldWalker`
>   class lives in `core/db` (Track 14 scope) and remains there;
>   Track 15 may exercise it as a collaborator only.
> - **Track 13 binary-serializer fixtures** (`EntitySerializerDelta`,
>   `RecordSerializerBinary*`) are reusable for EntityImpl
>   serialization paths — `Depends on:` is updated below.
> - **DB-tool round-trip is MEMORY-only** (per How above). Do not add
>   tests that consume disk for round-trip fidelity.
> - **Track 15 is test-additive only** (per Tracks 8–14 precedent) —
>   no production code modified. Production-side fixes for any bugs
>   discovered are pinned via `// WHEN-FIXED:` and absorbed into the
>   deferred-cleanup track.
> - **Out-of-scope-by-design** for `EntityImpl`: storage-coupled lazy
>   deserialization paths (page-frame slot reads, foreign-memory
>   buffer reads beyond existing `EntityImplPageFrameTest` coverage),
>   B-tree-backed LinkBag conversion paths requiring real disk
>   storage. Mark in Phase B Step 3 episode with explicit forwarding
>   to Tracks 19–21.
>
> **Interactions**:
> - **Depends on Track 1** (coverage measurement infrastructure) and
>   **Track 13** (binary-serializer test fixtures — `EntitySerializerDelta`
>   has 149 PSI test refs from `record/impl` tests, confirming reuse).
> - Benefits from Track 14 (db core baseline; collection wrappers in
>   `core/db/record/*` interact with `EntityImpl` field state).
> - **Drops the original "Closes Track 12 MemoryStream residual gap"
>   claim**: the gap is closed by *deletion* (forwarded to Track 22),
>   not by caller migration. PSI confirms 0 non-override callers of
>   `RecordIdInternal.toStream(MemoryStream)` and 0 callers of
>   `RecordBytes.fromInputStream(*)`. The 26 prod refs to
>   `MemoryStream` across 9 files (BinaryProtocol, 2 Command*,
>   `RecordId*`, `RecordBytes`) are out-of-scope for Track 15 and
>   either dead-pin in Track 15 (the `RecordBytes`/`RecordId*` overloads)
>   or forward to Track 22 (the BinaryProtocol/Command path callers).
> - **Adds entries to the deferred-cleanup absorption block**:
>   (i) `DatabaseRepair`, `BonsaiTreeRepair` deletions
>   (fully dead);
>   (ii) `DatabaseCompare`, `GraphRepair`, `CheckIndexTool`,
>   `EntityComparator` deletions
>   (test-only-reachable; contingent on retargeting tests at parents
>   or deleting test files);
>   (iii) `EntityHelper` 12 dead public methods (fine-grained — pin
>   each method individually so partial deletion stays valid);
>   (iv) `RecordVersionHelper`, `RecordStringable`, `RecordListener`
>   deletions
>   (fully dead);
>   (v) `EntityImpl.OPPOSITE_LINK_CONTAINER_PREFIX`
>   should-be-final tightening;
>   (vi) `RecordBytes.fromInputStream(*)` + `toStream(MemoryStream)`
>   overload deletions (the implementable
>   form of Track 12 backlog item h);
>   (vii) any inert-existing-test repairs found in Step 1 spot-check.

## Progress
- [x] Review + decomposition
- [ ] Step implementation (4/6 complete)
- [ ] Track-level code review

## Base commit
`587dfae4e68736f59527fb421c59b6362d35a24d`

## Reviews completed
- [x] Technical (iter-1: 2 blockers / 5 should-fix / 4 suggestions; reframe applied to Description above — see `reviews/track-15-technical.md`)
- [x] Risk (iter-1: 0 blockers / 5 should-fix / 4 suggestions; same reframe direction — see `reviews/track-15-risk.md`)
- [x] Adversarial (iter-1: 1 blocker / 6 should-fix / 3 suggestions; consensus on dead-code reframe — see `reviews/track-15-adversarial.md`)

All three iter-1 review reports converged on the same blocker shape (T1 / R1+R2 / A1: dead-code reframe for `core/db/tool` orphans + `core/record*` chain-dead helpers + RecordBytes `MemoryStream` overloads — Track 14 precedent applies). T2 / R3 / A10 converged on the secondary blocker (Track 12 MemoryStream "migrate callers" claim is unimplementable — close via deletion, owned by Track 22). Should-fix items absorbed into the Description: per-class disposition table (T7), explicit out-of-scope-by-design for storage-coupled cold paths (R8), MEMORY+ByteArrayStream mandate for DB-tool tests (T10/R5), `EntityHelper.hasSameContentOf` over `DatabaseCompare` for fidelity (A6), Track 13 dependency added (A3), schema-collaborator clarification (A4), `OPPOSITE_LINK_CONTAINER_PREFIX` shape pin (R4), per-step carry-forward convention assignment (R9), `EntityFieldWalker` ownership note (T4). Suggestion-tier items (boundary-completeness paragraph T9, extending existing test classes T8, importer-converter shape T6, `record` root remeasure T5, `@SequentialTest` for YourTracks-touching tests T10, focused `DatabaseCompare`-pin step T11, deterministic-export A9, scope-split A8) are encoded into individual steps below where they fit. No iter-2 gate needed because the reframes are plan-level, not code-level — Track 14 precedent.

## Steps

- [x] Step 1: Remeasure baseline + spot-check existing tests + `core/db/tool` dead-code pins
  - [x] Context: warning
  > **Risk:** medium — multi-file dead-code pins across 5 classes
  > (`DatabaseRepairDeadCodeTest`, `BonsaiTreeRepairDeadCodeTest`,
  > `DatabaseCompareDeadCodeTest`, `GraphRepairDeadCodeTest`,
  > `CheckIndexToolDeadCodeTest`) plus baseline-measurement file —
  > test-additive only; no production code change.
  >
  > **What was done:** Ran `./mvnw -pl core -am clean package -P coverage`
  > and wrote `docs/adr/unit-test-coverage/_workflow/track-15-baseline.md` capturing
  > aggregate (76.0% line / 66.5% branch / 179 packages) and per-class
  > coverage for the four target packages with explicit per-class
  > disposition tagging (live-drive / dead-code-pin / test-only-reachable
  > / out-of-scope-by-design). PSI all-scope `ReferencesSearch` (via
  > mcp-steroid IDE) confirmed Phase A's dead-code claims for all 5
  > `core/db/tool` classes — `DatabaseRepair` (0 main / 0 test refs —
  > fully dead), `BonsaiTreeRepair` (0/0 — fully dead), `DatabaseCompare`
  > (0 main / 36 test refs across 11 caller files —
  > test-only-reachable), `GraphRepair` (0 main / 3 test refs in
  > `GraphRecoveringTest` — test-only-reachable),
  > `CheckIndexTool` (0 main / 2 test refs in `CheckIndexToolTest` —
  > test-only-reachable). Wrote 5 reflection-only `*DeadCodeTest`
  > classes pinning declared-method set, ctor signatures, field
  > shape, and modifier flags (40 new tests, all pass). Spot-checked
  > six existing tests in `core/record/impl/` for inert-test bugs
  > (Track 12 precedent) — none found; every public test method
  > carries `@Test`, no suspicious `assertEquals(byte[]…)` patterns,
  > no `@Ignore` / "TODO test" placeholders. Updated the
  > deferred-cleanup absorption block in `implementation-backlog.md`
  > with the per-class deletion contingency (retargeting plan for the
  > test-only-reachable trio; lockstep deletion for the fully-dead
  > pair).
  >
  > **What was discovered:** Phase A's PSI claims held without any
  > revision required. The Track 15 baseline (2 435 total uncov across
  > the four packages — 1 383 + 90 + 889 + 73) tracks the plan-cited
  > 2 466 figure within 31 lines, suggesting intervening tracks
  > touched only fixture-level surface in these packages. The
  > `DatabaseCompare` declared-method audit added one method to the
  > pin set (`readRecordWithRetry`, a package-private helper missed
  > in the initial draft); `GraphRepair.getInverseConnectionFieldName`
  > takes `(String, boolean)` rather than `(String, Direction)` —
  > both caught at first test-run. The aggregate `core` module
  > coverage drifted +0.1pp line / +0.1pp branch from Track 14's
  > recorded 75.9%/66.4% baseline, plus one new package — both
  > consistent with adding 40 reflection-only tests.
  >
  > **Key files:**
  > - `core/src/test/java/.../core/db/tool/DatabaseRepairDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/db/tool/BonsaiTreeRepairDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/db/tool/DatabaseCompareDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/db/tool/GraphRepairDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/db/tool/CheckIndexToolDeadCodeTest.java` (new)
  > - `docs/adr/unit-test-coverage/_workflow/track-15-baseline.md` (new — workflow file, not committed)
  > - `docs/adr/unit-test-coverage/_workflow/implementation-backlog.md` (updated — workflow file, not committed)
  >
  > **Critical context:** Commit `d933f8b876` is purely test-additive.
  > 530 of the 889 `core/db/tool` uncov lines are in classes whose
  > WHEN-FIXED disposition is **delete** (DatabaseRepair 71 + BonsaiTreeRepair
  > 63 + DatabaseCompare 290 + GraphRepair 106 = 530); they are not
  > expected to enter the "covered" column during Track 15. The
  > residual live-drive scope for Step 6 is `DatabaseImport` (248) +
  > `DatabaseExport` (77) + `DatabaseImpExpAbstract` (11) +
  > `DatabaseRecordWalker` (4) ≈ 340 uncov, plus the 73 importer
  > package targeted in Step 5.

- [x] Step 2: `core/record` root + `core/record/impl` chain-dead helper pins
  - [x] Context: info
  > **Risk:** medium — multi-file dead-code pins across 5 classes
  > spanning two packages — test-additive only.
  >
  > **What was done:** Wrote 5 reflection-only `*DeadCodeTest`
  > classes (commit `61304da6d8`, +1 089 LOC across 5 new test
  > files) pinning the structural shape of the dead and chain-dead
  > surfaces in `core/record` root and `core/record/impl`.
  > `RecordVersionHelperDeadCodeTest` pins all 9 public static methods
  > (each in its own `@Test` so partial deletion stays valid), the
  > `SERIALIZED_SIZE` constant, the protected no-arg ctor, and the
  > field-set invariant. `RecordStringableDeadCodeTest` pins both
  > `value()` overloads individually plus the public-interface shape
  > (zero implementers confirmed via PSI `ClassInheritorsSearch`).
  > `RecordListenerDeadCodeTest` pins `onEvent`'s signature, the
  > `@Deprecated` annotation, and the six-constant `EVENT` enum (zero
  > implementers confirmed). `EntityHelperDeadCodeTest` pins each of
  > the 12 chain-dead public methods individually (`sort`,
  > `getMapEntry`, `getResultEntry`, `evaluateFunction`,
  > `hasSameContentItem`, both `hasSameContentOf` overloads,
  > `compareMaps`/`compareCollections`/`compareSets`/`compareBags`,
  > `isEntity(byte)`), the inner `RIDMapper` functional interface, and
  > a sanity-pin that the 5 live methods (`getReservedAttributes`,
  > both `getFieldValue` overloads, `getIdentifiableValue`,
  > `getRecordAttribute`) remain on the surface.
  > `EntityComparatorDeadCodeTest` pins the
  > `Comparator<Identifiable>` contract, the single public ctor
  > signature, the `compare`/`factor` method shape, and the three
  > private final instance fields, with a documentation-as-assertion
  > test that names the deletion order (delete `EntityHelper.sort`
  > first, then `EntityComparator`, then rewrite or drop
  > `tests/CRUDDocumentValidationTest`'s comparator-stability
  > assertion). All 45 new tests pass; the combined
  > `*DeadCodeTest` sweep (423 tests across the core module) is green
  > with no cross-contamination. Spotless clean.
  >
  > **What was discovered:**
  > - Phase A's count of "11 dead public methods" on
  >   `RecordVersionHelper` over-counted slightly: PSI shows 9 public
  >   static methods (all dead) plus the public `SERIALIZED_SIZE`
  >   constant and the protected ctor — the "11" was apparently
  >   treating constants and ctors as methods. Pin set adapts: 9
  >   individual `@Test` methods plus a shared method-name-set
  >   assertion plus a `SERIALIZED_SIZE` field pin and a ctor pin.
  > - Phase A's count of "12 of 19 public methods" on `EntityHelper`
  >   over-counted by two: the class actually exposes 17 public
  >   methods (not 19) plus 15 public attribute-name constants and one
  >   nested public interface (`RIDMapper`). The "12 of 17 dead" claim
  >   still holds, with 5 live methods unchanged. The 12 dead include
  >   10 with prod=0 PSI refs plus 2 chain-dead methods (5-arg
  >   `hasSameContentOf` and `isEntity(byte)`) whose only
  >   non-test source-code refs lead into `DatabaseCompare`
  >   (test-only-reachable per the prior step's pin) and
  >   `EntityImpl.hasSameContentOf` (also test-only-reachable, with a
  >   single `CRUDDocumentPhysicalTest` call).
  > - `EntityComparator` is not strictly chain-dead-via-
  >   `EntityHelper.sort` alone; it also has a single
  >   test-only-reachable reference in
  >   `tests/CRUDDocumentValidationTest` (a sort-stability assertion
  >   that constructs the comparator directly). Deletion plan
  >   therefore has three landing sites: drop `EntityHelper.sort`,
  >   drop `EntityComparator`, rewrite or remove the `tests/`
  >   assertion. The `EntityComparatorDeadCodeTest` Javadoc names all
  >   three explicitly.
  > - `EntityImpl.hasSameContentOf(EntityImpl)` is the sole non-
  >   `DatabaseCompare` production-source caller of the dead
  >   `EntityHelper.hasSameContentOf` (5-arg) helper, and itself has a
  >   single test-only-reachable caller in
  >   `tests/CRUDDocumentPhysicalTest`. Co-deletion of all three
  >   (`EntityImpl` method, the `EntityHelper` 5+6-arg helpers, and
  >   the `tests/` caller's `hasSameContentOf` assertion) is the
  >   clean lockstep removal — added to the deferred-cleanup queue
  >   and forwarded for absorption into the final-sweep track.
  > - Cross-track impact: minor. New deletion item
  >   (`EntityImpl.hasSameContentOf` lockstep) joins the deferred-
  >   cleanup queue alongside `RecordVersionHelper`,
  >   `RecordStringable`, `RecordListener`, the 12 dead `EntityHelper`
  >   methods + `RIDMapper`, and `EntityComparator` (with the
  >   `tests/CRUDDocumentValidationTest` retargeting subnote). No
  >   Component Map or Decision Record changes.
  >
  > **What changed from the plan:** Plan-text "11 dead public
  > methods" on `RecordVersionHelper` rephrases as "9 public static
  > methods + 1 dead constant + 1 protected ctor". Plan-text "12 of
  > 19 public methods dead" on `EntityHelper` rephrases as "12 of 17
  > public methods" (the 19 was counting fields). Both arithmetical
  > drifts are documentation-only — the pin set is correct and the
  > intent ("each method individually so partial deletion stays
  > valid") is honoured. `EntityComparator`'s status sharpens from
  > "chain-dead" to "chain-dead from production AND
  > test-only-reachable from one `tests/` caller" — affects the
  > deferred-cleanup deletion plan (one extra retargeting site).
  >
  > **Key files:**
  > - `core/src/test/java/.../core/record/RecordVersionHelperDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/record/RecordStringableDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/record/RecordListenerDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/EntityHelperDeadCodeTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/EntityComparatorDeadCodeTest.java` (new)
  >
  > **Critical context:** The five live `EntityHelper` methods are
  > deliberately unpinned in `EntityHelperDeadCodeTest`. A future
  > `EntityHelper`-focused track that wants to entrench their shape
  > must do so in a separate `EntityHelperLiveSurfaceTest`, not by
  > extending this dead-pin file — mixing live and dead pins in one
  > place would obscure which methods can be deleted in lockstep with
  > the deferred-cleanup track. The
  > `liveSurfaceRetainsItsFiveExpectedMethods` sanity-pin in this file
  > already guards against accidental deletion of the live five.

- [x] Step 3: `EntityImpl` + `Embedded`/`Edge`/`VertexEntityImpl` live-surface coverage + `OPPOSITE_LINK_CONTAINER_PREFIX` shape pin
  - [x] Context: info
  > **Risk:** medium — multi-file tests using `DbTestBase` against
  > the central document model; bounded by the changed-lines coverage
  > gate (declared explicitly in episode); test-additive only; uses
  > `EntitySerializerDelta` collaborator from Track 13 fixtures.
  >
  > **What was done:** Extended five existing test classes under
  > `core/src/test/java/.../core/record/impl/` (commit `02c77dc228`,
  > +933/-15 LOC) to cover `EntityImpl`'s six method-cluster seams
  > (typed-property get/set, dirty-tracking, `EntitySerializerDelta`
  > round-trip, equals/hashCode/toMap/toJSON, embedded semantics,
  > link-bag conversion at the
  > `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD` boundary), the
  > `EmbeddedEntityImpl`-specific paths (no-rid invariant,
  > unload-unsupported, link-rejection via `checkPropertyValue`,
  > schema-bound class-name carry-through), an
  > **all-21-`PropertyType` boundary-completeness fixture** in
  > `DocumentValidationTest` plus an enum-surface guard
  > (`PropertyType.values().length == 21`), a string-to-scalar
  > conversion sweep in `DocumentFieldConversionTest`, a
  > cyclic-embedded recursion-termination test, and
  > delegating-subclass shape pins for `EdgeEntityImpl` /
  > `VertexEntityImpl` in `VertexAndEdgeTest`. The
  > `OPPOSITE_LINK_CONTAINER_PREFIX` field is pinned via reflection
  > in its observed (non-final) shape with a `WHEN-FIXED:`
  > forwarding marker — the deferred-cleanup track must flip
  > `assertFalse(Modifier.isFinal(...))` to `assertTrue` in lockstep
  > when it tightens the field. A forwarding test
  > (`testStorageCoupledLazyPathsAreOutOfScopeForwardedToPageFrameSuite`)
  > pins `EntityImplPageFrameTest` as the authoritative reference
  > for storage-coupled lazy paths (Tracks 19–21). All **81 new
  > tests pass** across the 5 files (26 + 7 + 13 + 11 + 24) and the
  > coverage gate reports **100% line / 100% branch on changed
  > lines**. Spotless clean. Test-additive only — no production
  > code modified.
  >
  > **What was discovered:**
  > - `PropertyType` enum currently exposes 21 public constants; the
  >   step description listed 24 (`TRANSIENT`, `CUSTOM`, `ANY`) but
  >   those are not in this codebase. The all-types fixture covers
  >   the actual 21 (`BOOLEAN`, `BYTE`, `SHORT`, `INTEGER`, `LONG`,
  >   `FLOAT`, `DOUBLE`, `STRING`, `DECIMAL`, `DATETIME`, `DATE`,
  >   `BINARY`, `EMBEDDED`, `EMBEDDEDLIST`, `EMBEDDEDSET`,
  >   `EMBEDDEDMAP`, `LINK`, `LINKLIST`, `LINKSET`, `LINKMAP`,
  >   `LINKBAG`) and includes a `values().length == 21` guard so a
  >   future enum addition fires.
  > - A freshly-created `EntityImpl` reports `isDirty()=true` by
  >   construction (record is "new and unsaved") rather than false;
  >   the dirty-tracking test was reshaped to baseline via
  >   `clearTrackData() + unsetDirty()` before the
  >   idempotent-write assertion, matching the existing
  >   `testNoDirtySameBytes` pattern.
  > - JUnit 4's `@Test(timeout = ...)` runs on a separate thread, so
  >   the `DbTestBase`-managed session is not active there and the
  >   cyclic-embedded test failed with `SessionNotActivated`.
  >   Dropped the timeout — a regression in the recursion guard
  >   would still surface via `StackOverflowError` or hung Surefire
  >   run.
  > - The JSON serializer rejects non-persistent rid references on a
  >   transient entity, so the cyclic-embedded test's `toJSON` probe
  >   was removed (the rid-rejection is unrelated to the recursion
  >   guard the test pins). The `toJSON` contract is covered
  >   separately by a persisted-entity test in `EntityImplTest`
  >   (`testToJsonContainsPropertyNames`).
  > - PSI all-scope `ReferencesSearch` on
  >   `OPPOSITE_LINK_CONTAINER_PREFIX` confirmed Phase A's "0 writes
  >   anywhere" claim — exactly 2 references, both reads (in
  >   `EntityImpl` and `DatabaseSessionEmbedded`). The
  >   should-be-final pin is sound; `WHEN-FIXED:` forwarding is
  >   correct.
  > - Cross-track impact: minor. The
  >   `OPPOSITE_LINK_CONTAINER_PREFIX` should-be-final tightening
  >   item already lives in the deferred-cleanup queue (per the
  >   step-file Description); the new falsifiable pin gives that
  >   item a tracking anchor. The `PropertyType` enum-surface guard
  >   is self-defending — a future track adding a new constant will
  >   fire it and force an explicit fixture extension. The
  >   `EntityImplPageFrameTest` rename/move guard is similarly
  >   self-defending. No Component Map or Decision Record changes.
  >
  > **What changed from the plan:**
  > - Sub-task (c) "all-30+-field-types" / "~24 types" framing was
  >   reduced to **all-21-types** because `PropertyType` in this
  >   codebase has 21 constants (`TRANSIENT`/`CUSTOM`/`ANY` are not
  >   present). Boundary completeness is preserved; the
  >   enum-surface guard catches a future re-addition.
  > - Sub-task (d) cyclic-embedded test reduced from "true cycle"
  >   (A.embedded → B, B.peer → A) to a **deeply-nested embedded
  >   chain** (A → B → C → deep) because `EmbeddedEntityImpl`'s
  >   `checkPropertyValue` rejects link assignments to non-embedded
  >   parents — constructing an actual rid back-reference is
  >   guarded against. The deeply-nested form still exercises the
  >   recursion guard heavily; if it regresses the test surfaces it
  >   via `StackOverflowError`. If a future track introduces a
  >   sanctioned way to construct a true embedded cycle
  >   (`EmbeddedEntity` → `EmbeddedEntity` self-link), this test
  >   should be extended to cover it directly rather than via the
  >   deeply-nested fallback.
  > - Coverage build was attempted with `-pl core,common -am` first;
  >   the second module is actually named `test-commons`, so the
  >   command was reissued as `-pl core -am`. Build completed in
  >   ~9.5 min.
  >
  > **Key files:**
  > - `core/src/test/java/.../core/record/impl/EntityImplTest.java` (modified — +588 LOC)
  > - `core/src/test/java/.../core/record/impl/EmbeddedEntityImplTest.java` (modified — +144 LOC)
  > - `core/src/test/java/.../core/record/impl/DocumentValidationTest.java` (modified — +122 LOC)
  > - `core/src/test/java/.../core/record/impl/DocumentFieldConversionTest.java` (modified — +39 LOC)
  > - `core/src/test/java/.../core/record/impl/VertexAndEdgeTest.java` (modified — +55 LOC)
  >
  > **Critical context:** The
  > `OPPOSITE_LINK_CONTAINER_PREFIX` should-be-final pin uses
  > `assertFalse` on `Modifier.isFinal` with an explicit
  > `WHEN-FIXED:` comment naming the flip. When the
  > deferred-cleanup track tightens the field to final, the test
  > must be flipped to `assertTrue` in lockstep — the `WHEN-FIXED:`
  > text in `EntityImplTest` is the canonical instruction. The
  > `LinkBag` boundary test reads
  > `GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD`
  > from the live session config (default 40) rather than
  > hard-coding the threshold; a config-default change automatically
  > tracks. Storage-coupled lazy-deserialization paths (page-frame
  > slot reads, foreign-memory buffer reads beyond
  > `EntityImplPageFrameTest`'s existing scope) remain explicitly
  > out-of-scope for this track and are owned by Tracks 19–21.

- [x] Step 4: `core/record/impl` standalone helpers + iterators + `RecordBytes` test-only-overload pin
  - [x] Context: info
  > **Risk:** medium — multi-file standalone tests + a single
  > test-only-reachable overload pin across 10 classes; test-additive
  > only. Reframed mid-step from "dead-code pins" to
  > "test-only-reachable pin" per the user-approved Alternative B
  > resolution of the prior `DESIGN_DECISION_NEEDED` escalation
  > (see Resume note below).
  >
  > **What was done:** Added 9 new test files plus an extension of
  > `DBRecordBytesTest` under
  > `core/src/test/java/.../core/record/impl/` (commit
  > `740155271d`). Standalone POJO-shape tests cover `EntityEntry`
  > (value-tracking flag semantics, clone/clear/undo/transactionClear
  > arms), `SimpleMultiValueTracker` (enable/disable gate,
  > add/update/remove + `*NoDirty` arms, GC-survival short-circuit,
  > `sourceFrom` slot copy), and `InPlaceResult` (tri-state enum
  > constants, ordinals, `valueOf` round-trip). Iterator-shape
  > coverage was added for the residual link/edge iterators that
  > lacked direct tests:
  > `BidirectionalLinkToEntityIterator` (BOTH-direction rejection,
  > IN/OUT branch dispatch),
  > `BidirectionalLinksIterable` (four `size()` arms,
  > `isSizeable()` mirror),
  > `EdgeIterator` (the five `loadEdge` arms — null /
  > already-edge / RecordNotFound / vertex-throws / non-edge — plus
  > five `size()` arms, `reset`/`isResetable`, `getMultiValue`),
  > `EdgeIterable` (size and isSizeable arms), and
  > `VertexFromLinkBagIterable` (the missing `Iterable` half;
  > `VertexFromLinkBagIterator` was already covered).
  > `DBRecordBytesTest` was extended with live-`RecordBytes`
  > Blob-interface shape tests: 1-arg `fromInputStream(InputStream)`
  > round-trip (the LIVE overload, with one production caller in
  > `JSONSerializerJackson.java:623`), `toOutputStream`, type-flag
  > checks, `as*`-cast guards, `setOwner`-throws contract, and a
  > persist-and-reload round-trip. The new
  > `RecordBytesTestOnlyOverloadTest` pins only the 2-arg
  > `Blob.fromInputStream(InputStream, int)` overload as
  > test-only-reachable (Track 14 `EntityHookAbstract` precedent —
  > class is real, but the specific overload has zero non-test
  > callers). The pin uses a falsifiable count-sentinel
  > (`EXPECTED_TWO_ARG_CALL_SITES = 7`, scanning the
  > comment-stripped `DBRecordBytesTest` source for 2-arg call
  > shapes), and the WHEN-FIXED Javadoc names the seven specific
  > line numbers in `DBRecordBytesTest` that need rewriting or
  > removal when the overload is deleted (post-spotless:
  > L76, L87, L101, L115, L148, L164, L181). All 98 new tests
  > pass; the full `core` suite (1780 tests) is green; spotless
  > clean; coverage gate reports 100% line / 100% branch on
  > changed lines.
  >
  > **What was discovered:**
  > - Spotless reformat shifted the `DBRecordBytesTest` 2-arg
  >   call-site line numbers from the guidance-echoed
  >   L77/88/102/116/149/165/182 down by 1 line each to
  >   L76/87/101/115/148/164/181 (one extra blank line was
  >   removed above the 2-arg-call-site region). The line numbers
  >   in `RecordBytesTestOnlyOverloadTest`'s WHEN-FIXED Javadoc
  >   were updated to match the post-spotless reality. The COUNT
  >   of 7 call sites — the load-bearing assertion in the
  >   sentinel — is unchanged.
  > - `EdgeIterator.loadEdge` calls
  >   `transaction.loadEntity(Identifiable)` — the
  >   `Identifiable`-typed overload, NOT the `RID`-typed one.
  >   `FrontendTransactionImpl` exposes both overloads; mock stubs
  >   for `EdgeIteratorTest` and `EdgeIterableTest` needed
  >   explicit `(Identifiable) rid` casts to bind to the right
  >   overload. The pre-existing `EdgeFromLinkBagIteratorTest`
  >   doesn't hit this trap because `EdgeFromLinkBagIterator`
  >   iterates `RidPair` and calls `loadEntity(RID)`.
  > - ASM is not on the test classpath; the initial sentinel
  >   implementation that walked bytecode `INVOKE` instructions
  >   was rewritten as a string scan over the comment-stripped
  >   `DBRecordBytesTest` source. The text-scan is precise enough
  >   for the simple call shapes the test uses (no nested-method-
  >   call commas inside the 2-arg parameter lists).
  > - **Cross-track impact (minor — apply at Phase C):** Track 22's
  >   deferred-cleanup absorption block needs three updates. (1)
  >   RETRACT the prior "RecordBytes `fromInputStream(*)` +
  >   `toStream(MemoryStream)` overload deletions" line item —
  >   both claims were wrong (1-arg is live; `toStream(MemoryStream)`
  >   doesn't exist on `RecordBytes`). (2) REPLACE WITH:
  >   "RecordBytes `Blob.fromInputStream(InputStream, int)` —
  >   2-arg overload, test-only-reachable; deletion contingent on
  >   rewriting/removing the seven `DBRecordBytesTest` 2-arg call
  >   sites at L76/87/101/115/148/164/181, pinned by
  >   `RecordBytesTestOnlyOverloadTest` with a count-sentinel."
  >   (3) ADD a separate follow-up: "RecordBytes
  >   `fromInputStream(InputStream)` (1-arg, LIVE; 1 production
  >   caller at `JSONSerializerJackson.java:623`) — body uses
  >   `MemoryStream` as a scratch buffer; rewrite to
  >   `ByteArrayOutputStream` directly. This is the implementable
  >   form of Track 12's MemoryStream backlog item h for the
  >   Blob/RecordBytes path; the `RecordIdInternal`/`Command` path's
  >   MemoryStream-taking overloads remain on Track 22's existing
  >   scope per the step-file's MemoryStream forwarding note."
  > - **mcp-steroid tooling caveat:** the SessionStart hook
  >   reported `mcp-steroid: reachable`, but the steroid tools did
  >   not surface in the implementer spawn's deferred-tool list.
  >   Reference-accuracy claims in this step echo the prior
  >   `DESIGN_DECISION_NEEDED`-spawn's PSI exploration_notes
  >   (which were PSI-validated when generated) plus a grep
  >   drift-check against the current tree to rule out changes
  >   since. Phase C track-level review may want to re-verify
  >   the three load-bearing claims via PSI when the IDE control
  >   surface is reachable: (1) 1-arg
  >   `Blob.fromInputStream(InputStream)` has exactly one
  >   production caller (`JSONSerializerJackson.java:623`), (2)
  >   2-arg `Blob.fromInputStream(InputStream, int)` has zero
  >   production callers, (3) the seven `DBRecordBytesTest` 2-arg
  >   call sites at L76/87/101/115/148/164/181 are the complete
  >   set.
  >
  > **What changed from the plan:** Sub-task (f) was rewritten per
  > Alternative B (approved during the 2026-05-04
  > `DESIGN_DECISION_NEEDED` resolution): pin only the 2-arg
  > overload in a NEW class `RecordBytesTestOnlyOverloadTest`
  > (NOT `RecordBytesDeadCodeTest`), drop the
  > `toStream(MemoryStream)` clause entirely (overload doesn't
  > exist on `RecordBytes`), and cover the live 1-arg overload
  > via `DBRecordBytesTest` extension under sub-task (e) rather
  > than as a dead pin. Sub-task (g) was also revised: the
  > original wording asked the implementer to extend the
  > deferred-cleanup absorption block in
  > `implementation-backlog.md`, but the rulebook forbids the
  > implementer from modifying that file. The orchestrator
  > applies those edits at Phase C; the implementer recorded the
  > intended absorption updates in this episode (see "What was
  > discovered" above). Steps 5 (importer converters) and 6
  > (`DatabaseImport`/`Export` round-trip) are unaffected.
  >
  > **Key files:**
  > - `core/src/test/java/.../core/record/impl/EntityEntryTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/SimpleMultiValueTrackerTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/InPlaceResultTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/BidirectionalLinkToEntityIteratorTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/BidirectionalLinksIterableTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/EdgeIteratorTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/EdgeIterableTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/VertexFromLinkBagIterableTest.java` (new)
  > - `core/src/test/java/.../core/record/impl/DBRecordBytesTest.java` (modified — live `RecordBytes` Blob-interface shape coverage)
  > - `core/src/test/java/.../core/record/impl/RecordBytesTestOnlyOverloadTest.java` (new)
  >
  > **Critical context:** The new pin file's WHEN-FIXED markers
  > reference the POST-spotless line numbers
  > (L76/87/101/115/148/164/181) of the seven 2-arg call sites in
  > `DBRecordBytesTest`. If a future formatting change or
  > test-content change shifts those line numbers again, the
  > WHEN-FIXED Javadoc and the assertion-message text in
  > `RecordBytesTestOnlyOverloadTest.testTwoArgOverloadCallSitesStillExistInDbRecordBytesTest`
  > must be updated in lockstep — the SENTINEL TEST itself only
  > checks the COUNT of 7 (immune to line-number drift), but the
  > deferred-cleanup track reads the line-number list from the
  > Javadoc to know which call sites to retarget. The
  > `EXPECTED_TWO_ARG_CALL_SITES = 7` constant is the canonical
  > signal — if a future track adds or removes a 2-arg call site
  > without updating this constant the sentinel fires, forcing
  > an explicit decision about whether the overload is still
  > test-only-reachable or whether the WHEN-FIXED forwarding is
  > now actionable.

- [ ] Step 5: `core/db/tool/importer` converter coverage (live)
  > **Risk:** medium — multi-file standalone tests for 12 converter
  > classes via `ImportConvertersFactory`; test-additive only.
  >
  > Sub-tasks: standalone tests with mocked or minimal `ConverterData`
  > for each live converter:
  > (a) `EmbeddedListConverterTest`, `EmbeddedSetConverterTest`,
  > `EmbeddedMapConverterTest` (collection-shape conversion +
  > nested-collection recursion);
  > (b) `LinkBagConverterTest`, `LinkConverterTest`,
  > `LinkListConverterTest`, `LinkMapConverterTest`,
  > `LinkSetConverterTest` (RID rewrite under `LinksRewriter`
  > collaboration);
  > (c) `LinksRewriterTest` (RID-mapping table apply / pass-through
  > on miss);
  > (d) `ValuesConverterTest` interface contract pin;
  > (e) `AbstractCollectionConverterTest` (default
  > `convert(...)` arms);
  > (f) `ImportConvertersFactoryTest` (registration shape, lookup by
  > type, factory-singleton convention).

- [ ] Step 6: `DatabaseExport` + `DatabaseImport` live round-trip + verification + backlog update
  > **Risk:** medium — `DbTestBase` round-trip touches `YourTracks`
  > global state (requires `@Category(SequentialTest)`); test-additive
  > only; uses `EntityHelper.hasSameContentOf` for fidelity (NOT
  > `DatabaseCompare`).
  >
  > Sub-tasks: (a) extend `DatabaseImportTest` (or new `DatabaseExportImportRoundTripTest`)
  > with MEMORY-storage in-process round-trip using
  > `ByteArrayInput/OutputStream` (per `DatabaseImportSimpleCompatibilityTest`
  > precedent — sub-1-second per test); cover small fixture (~5–10
  > entities with unambiguous types: STRING, INTEGER, EMBEDDED,
  > LINKLIST, LINKMAP, LINKBAG); fidelity via
  > `EntityHelper.hasSameContentOf` entity-by-entity;
  > `@Category(SequentialTest)`; (b) cover `DatabaseImpExpAbstract`
  > common-base residual branches (option-flag dispatch, listener
  > callbacks); (c) cover `DatabaseRecordWalker` residual branches
  > via existing-test extension; (d) pin `DatabaseTool` interface
  > shape (abstract base — interface-level only); (e) explicit
  > out-of-scope for `DatabaseImport.java:416` legacy-version branch
  > (Track 22 backlog item f); (f) run `coverage-gate.py` against the
  > accumulated track-15 changes (compare-branch
  > `origin/develop`, line-threshold 85, branch-threshold 70); record
  > final per-package live coverage in episode; (g) extend
  > deferred-cleanup absorption block in `implementation-backlog.md`
  > with all Track-15 deletion items + WHEN-FIXED markers; (h)
  > re-measure `core/serialization/MemoryStream` coverage and forward
  > residual gap to Track 22 (Track 12 backlog item h closure
  > documentation, NOT migration).

## Resume note (paused 2026-05-04, resolved 2026-05-05)

**Status:** RESOLVED. PR #1022 merged on `origin/develop` 2026-05-05
(commit `17faefced2`); the unit-test-coverage branch's merge-base
already equals that SHA so no rebase action was needed. Step 4 was
respawned via Alternative B (single test-only-overload pin in
`RecordBytesTestOnlyOverloadTest`) and committed at
`740155271d`. The episode above captures what was done, what was
discovered, and the cross-track absorption updates Phase C will
apply to `implementation-backlog.md`. The remainder of this note
is preserved as historical context for the deferred-cleanup
track's audit trail.

**Pause reason.** Phase B Step 4 was paused before respawning the
implementer because the implementer rulebook contains two bugs that
caused a `git clean -fd` data-loss incident during Step 4's first
attempt. The bugs are fixed in
[PR #1022](https://github.com/JetBrains/youtrackdb/pull/1022)
on `origin/develop` (forbid `ScheduleWakeup` in the implementer +
replace `git clean -fd` with a snapshot-and-diff revert sequence).
**Wait for PR #1022 to merge before respawning.** Once it merges,
Track 15's mandatory rebase pulls the fixed rulebook in and Step 4
can run safely.

**Step 4 escalation summary** (from the first attempt's
`DESIGN_DECISION_NEEDED` return — full transcript in agent
`a741f8f92696fe657`'s sibling `ac11ffb1a21e13086`). PSI all-scope
`ReferencesSearch` re-confirmation contradicted Phase A's
"`RecordBytes.fromInputStream(*)` and `toStream(MemoryStream)` are
0-caller dead" claim:

1. `Blob.fromInputStream(InputStream)` (1-arg) HAS a live production
   caller: `JSONSerializerJackson.java:623`
   (`blob.fromInputStream(new ByteArrayInputStream(iBuffer))`) inside
   the JSON-deserialization Blob branch. `RecordBytes` is the sole
   `Blob` implementer (verified via `ClassInheritorsSearch`-style
   reasoning), so this call resolves at runtime to
   `RecordBytes.fromInputStream(InputStream)`. **This overload is
   LIVE, not dead.**
2. `Blob.fromInputStream(InputStream, int)` (2-arg) has 0 production
   callers — only `DBRecordBytesTest` uses it (lines 77, 88, 102, 116,
   149, 165, 182). **Test-only-reachable** (Track 14
   `EntityHookAbstract` precedent).
3. `RecordBytes.toStream(MemoryStream)` **does not exist**. Walking
   the class chain: `RecordBytes` declares only `toStream()`
   (zero-arg, returns `byte[]`); `RecordAbstract` (parent) has the
   same zero-arg method. The `MemoryStream`-taking `toStream`
   overloads live on `RecordIdInternal` / `RecordId` /
   `ContextualRecordId` / `ChangeableRecordId` /
   `CommandRequestTextAbstract` — all already in Track 22's scope per
   the step file's MemoryStream forwarding note.

**Approved alternative for resume:** **Alternative B** — pin only
the 2-arg `fromInputStream(InputStream, int)` overload as
test-only-reachable (matching the Track 14 `EntityHookAbstract`
precedent shape). Use a `RecordBytesTestOnlyOverloadTest` name (NOT
`RecordBytesDeadCodeTest`) to avoid mislabelling. The
`WHEN-FIXED:` forwarding marker should explicitly name the 7
`DBRecordBytesTest` line numbers that need rewriting/dropping (L77,
L88, L102, L116, L149, L165, L182) so the deferred-cleanup track
has an actionable retargeting plan.

Drop the `toStream(MemoryStream)` clause from sub-task (f) entirely
— that overload doesn't exist on `RecordBytes`. The other 6
sub-tasks (a, b, c, d, e, g) are unaffected and proceed as
originally specified.

**Deferred-cleanup absorption updates needed** (the orchestrator
applies these to `implementation-backlog.md` at Track 15 Phase C):

- **RETRACT** the prior "RecordBytes `fromInputStream` +
  `toStream(MemoryStream)` overload deletions" line item — the claim
  was wrong on both counts.
- **REPLACE WITH** "RecordBytes `fromInputStream(InputStream, int)` —
  test-only-reachable, deletion contingent on rewriting/removing 7
  `DBRecordBytesTest` 2-arg call sites (L77/L88/L102/L116/L149/L165/L182)".
- Track 12's framing of MemoryStream backlog item h ("close via
  deletion, not migration") remains valid — but only for the
  `RecordIdInternal` / `Command` path, NOT for `RecordBytes`. The
  `RecordBytes.fromInputStream(InputStream)` body's internal use of
  `MemoryStream` as a scratch buffer is a Track 22 concern (rewrite
  the body to use `ByteArrayOutputStream` directly).

**Resume drill** (when PR #1022 has merged):

1. Verify on `develop`: PR #1022 merged
   (`gh pr view 1022 --json state,mergedAt`).
2. Rebase the unit-test-coverage branch onto `origin/develop`:
   `git fetch origin && git rebase origin/develop`. Resolve any
   conflicts; record pre/post-rebase SHAs in this section.
3. Re-run the full `core` unit-test suite to confirm no regressions
   from the rebase: `./mvnw -pl core clean test`.
4. Re-run `./mvnw -pl core spotless:apply`.
5. Re-run `/execute-tracks` from a fresh session.
6. Auto-resume should land on Track 15 Phase B Step 4 (3/6
   complete, Step 4 next). Spawn the implementer with
   `mode=WITH_GUIDANCE`, `Guidance:` set to the **Alternative B**
   summary above, and `exploration_notes_echo:` set to the
   exploration_notes from the prior `DESIGN_DECISION_NEEDED` return
   (in transcript `ac11ffb1a21e13086`).
7. Step 4's expected commit shape: ~6 sub-tasks → 1 commit, ~600-900
   LOC across `EntityEntryTest`, `SimpleMultiValueTrackerTest`,
   `InPlaceResultTest`, iterator-test extensions,
   `DBRecordBytesTest` extension (live shape), and
   `RecordBytesTestOnlyOverloadTest`.
8. Continue with Steps 5 and 6 normally.

**Resume note ends here. Below this line is empty until Step 4
resumes.**
