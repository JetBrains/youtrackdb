# Track 14: DB Core & Config

## Description

Write tests for the core database package — database lifecycle,
configuration, and record management. After Phase A reviews
(technical / risk / adversarial — all PSI-grounded), the original
"DB tests require DbTestBase" / "drive db/config builders" framing
has been **reframed** to match Tracks 9–10's dead-code precedent
and Track 12's corrected-baseline lesson.

> **What** (PSI-validated, post-review):
> - `core/db` (~1,268 uncov pre-review, **stale baseline — remeasure
>   in Step 1**) — focus on the *live* surface: `DatabaseSessionEmbedded`
>   `set`/`setCustom` ATTRIBUTES dispatcher (≈26 uncov lines / 25 uncov
>   branches across 14 enum values), `SessionPoolImpl` /
>   `DatabasePoolImpl` / `CachedDatabasePoolFactoryImpl` (the live pool
>   path — 9 ctor refs from `YouTrackDBInternalEmbedded` /
>   `CachedDatabasePoolFactoryImpl`), `StringCache` (close + capacity
>   eviction + concurrent access — existing test is 32-LOC sparse),
>   `CommandTimeoutChecker` (worker-thread isolation),
>   `YouTrackDBConfigBuilderImpl` / `YouTrackDBConfigImpl`,
>   `SystemDatabase` (system-DB isolation, `@Category(SequentialTest)`),
>   plus dead-code pins for `DatabasePoolBase` (0 ctor refs, 0
>   subclasses), `DatabasePoolAbstract` (1 dead subclass +
>   1 test-only subclass), `RecordMultiValueHelper` (16/16 uncov, 0
>   refs), `HookReplacedRecordThreadLocal` (13/13 uncov, 0 refs),
>   `DatabaseLifecycleListenerAbstract` (6/6 uncov, 0 refs),
>   `LiveQueryBatchResultListener` (0/0).
> - `core/db/config` (~130 uncov, **dead-code reframe — Track 9/10
>   precedent**) — every public API in `MulticastConfguration`,
>   `NodeConfiguration`, `UDPUnicastConfiguration` and their three
>   Builders has 0 callers across all 5 modules per PSI all-scope
>   `ReferencesSearch`. Pin shape via `DBConfigDeadCodeTest` and
>   absorb deletion into the deferred-cleanup track. Do NOT drive
>   builder round-trips.
> - `core/db/record` (~404 uncov, 70.5% baseline) — focus on
>   tracked-collection wrappers (`EntityEmbeddedListImpl`,
>   `EntityEmbeddedSetImpl`, `EntityEmbeddedMapImpl`,
>   `EntityLinkListImpl`, `EntityLinkSetImpl`, `EntityLinkMapIml`)
>   and `MultiValueChangeEvent` / `MultiValueChangeTimeLine` (POJO
>   shape — standalone). Hook-abstract base classes
>   (`EntityHookAbstract`, `RecordHookAbstract`) have 0 production
>   subclasses → dead-code pins, augment existing
>   `core/db/hook/*Test` rather than duplicate.
> - `core/db/record/record` (~71 uncov, 57.2% baseline) —
>   `Direction` enum, `RID` parsing edge cases not covered by
>   existing tests, `Vertex` / `Edge` / `Identifiable` default-method
>   branches reachable without a session. Several uncov lines
>   double-count with Track 15's `record/impl` scope — coverage
>   credit accrues to Track 15 when `EntityImpl` exercises the
>   default method.
> - `core/db/record/ridbag` (~23 uncov, 84.7% baseline) — disambiguates
>   from `core/storage/ridbag/` (Track 19 scope). LinkBag
>   embedded↔B-tree conversion focused step (`assertEquals(class,
>   .getClass())` per Track 11 precedent).
>
> **How**:
> - **Test base class is per-class, not per-track.** D2 (standalone
>   over DbTestBase) holds for value/POJO/dead-code classes. DbTestBase
>   is mandatory only when the production class genuinely needs a
>   session (pool lifecycle, collection-wrapper change-tracking that
>   requires an `EntityImpl` parent, ATTRIBUTES dispatcher).
>   Standalone candidates: `Direction` (enum), `MultiValueChangeEvent`
>   / `TimeLine` (POJOs), all `db/config/*` (dead-code pins),
>   `RID` parsing, `Vertex` / `Edge` default methods that don't
>   require a session. Existing `CommandTimeoutCheckerTest` is
>   already standalone — precedent.
> - **Phase B Step 1 remeasures live coverage** for all five target
>   packages and writes `track-14-baseline.md` (precedent: Track 9
>   `track-9-baseline.md`, Track 10 `track-10-baseline.md`). The
>   plan-cited 1,268 / 66.5% figure is post-Track-7 and stale by 6
>   tracks — concrete per-class uncov targets are derived from the
>   remeasured XML. Step 1 also spot-checks 2–3 existing
>   `core/db/*Test.java` classes for inert-test bugs (Track 12
>   lesson — missing `@Test`, identity-based assertions, dead
>   ignored bodies).
> - **Dead-code pin pattern** (Track 9/10 precedent): for each
>   confirmed-dead class, a dedicated `<Class>DeadCodeTest` pins
>   structural shape (modifiers, signatures, dispatcher tables,
>   ctor visibility) so a future refactor either updates the pin in
>   lockstep or fails loudly. Each pin carries
>   `// WHEN-FIXED: <deferred-cleanup target> — delete <class>` and
>   absorbs the deletion item into the Track 22 backlog section.
> - **Coverage-gate framing for `DatabaseSessionEmbedded`**: the
>   class is 4,618 LOC; per the project gate the deliverable is
>   "85% line / 70% branch on changed lines" (i.e. on the lines
>   added or modified by Track 14 tests' production-touch surface),
>   not "lift the whole class to 85%". Phase B Step 5 declares
>   coverage scope explicitly for this class.
> - **`@Category(SequentialTest)`** required when a test touches
>   engines-manager state (`HookReplacedRecordThreadLocal.INSTANCE`,
>   `ExecutionThreadLocal`, `YourTracks` global handle, the
>   hardcoded `SystemDatabase` name). Precedents:
>   `SystemDatabaseDisabledTest.java:14`,
>   `FreezeAndDBRecordInsertAtomicityTest.java:48`. Surefire
>   pom config: `core/pom.xml:304-307` — `parallel: classes`,
>   `threadCountClasses=4`.
> - **Carry forward Tracks 5–13 conventions**: `TestUtilsFixture`
>   + `@After rollbackIfLeftOpen` safety net,
>   falsifiable-regression + `// WHEN-FIXED:` marker convention,
>   `Iterable` detach-after-commit pattern (`Iterable<Vertex>`
>   wrappers detach on `session.commit()` — collect identities to a
>   `List` first), `*DeadCodeTest` shape pin convention,
>   `// forwards-to: <target> — <description>` cross-track bug-pin
>   convention, shared-fixture extraction at iter-2 (Track 13
>   `runInTx` / `field` precedent), `assertEquals(X.class,
>   x.getClass())` over `instanceof` for load-bearing wrapper-type
>   assertions (Track 11), `assertNotSame` for dual-instance
>   invariant pinning (Track 11), counting `CommandContext` wrapper
>   for fallback-branch mutation testing (Track 7).
> - **`MemoryStream` re-measure** (Track 12 deferred item h):
>   exercising `RecordId*` callers via document CRUD in Steps 4–5
>   should naturally drive the residual `MemoryStream` record-id
>   paths. Step 6 (verification) explicitly remeasures
>   `core/serialization/MemoryStream` coverage and forwards any
>   residual gap to Track 22 with concrete file:line.
>
> **Constraints**:
> - **In-scope by test target**, not by collaborator: tests' *target*
>   class must live under `core/db*` (the five listed packages).
>   Collaborator classes from `record/impl` (Track 15),
>   `metadata/schema` (Track 16), `serialization` (Tracks 12–13) are
>   permitted as fixture material — coverage credit accrues to the
>   target package, not the collaborator. Existing precedent:
>   `EntityEmbeddedMapImplTest` already imports `record.impl.EntityImpl`.
> - **Listener / hook ownership rule**: where an interface is
>   declared in `core/db/` but its only implementers live elsewhere
>   (e.g., `MetadataUpdateListener` implementer in
>   `metadata/sequence`, `SessionListener` implementer in
>   `sql/parser`-driven hooks), Track 14 covers only the
>   interface-level default methods + dead-code shape pins, not the
>   foreign implementations.
> - **Network/multicast/UDP config classes are dead** per PSI —
>   pin shape, do NOT drive the network. Builder round-trip tests
>   are forbidden because they would be load-bearing on a contract
>   nobody calls.
> - **Track 14 is test-additive only** (per Tracks 8–13 precedent)
>   — no production code modified. Production-side fixes for any
>   bugs discovered are pinned via `// WHEN-FIXED:` and absorbed
>   into the Track 22 backlog.
>
> **Interactions**:
> - Depends on Track 1 (coverage measurement infrastructure).
> - Provides the post-track baseline that Track 15 measures against
>   (`record/impl` coverage interacts with `core/db/record/*` since
>   collection wrappers escape into `EntityImpl` field state).
> - Adds entries to the Track 22 deferred-cleanup absorption block:
>   (i) `core/db/config` package deletion (5 dead public classes +
>   3 dead Builders); (ii) `DatabasePoolBase` deletion;
>   (iii) `DatabasePoolAbstract` consolidation (one dead subclass +
>   one test subclass — fold abstract into `DatabasePoolImpl` or
>   delete); (iv) `RecordMultiValueHelper`,
>   `HookReplacedRecordThreadLocal`,
>   `DatabaseLifecycleListenerAbstract`,
>   `LiveQueryBatchResultListener`,
>   `EntityHookAbstract` / `RecordHookAbstract` deletions;
>   (v) `MemoryStream` residual gaps re-forwarded after Step 6
>   re-measurement; (vi) any inert-existing-test repairs found in
>   Step 1 spot-check.

## Progress
- [x] Review + decomposition
- [x] Step implementation (6/6 complete)
- [x] Track-level code review (3/3 iterations — iter-3 PASS across all 6 dimensions)

## Base commit
`3a93854edd423b51707c037dd4e7f9ff4bba0c8e`

## Reviews completed
- [x] Technical (iter-1: 2 blockers / 4 should-fix / 3 suggestions; reframe applied to Description above — see `reviews/track-14-technical.md`)
- [x] Risk (iter-1: 2 blockers / 4 should-fix / 3 suggestions; same reframe direction — see `reviews/track-14-risk.md`)
- [x] Adversarial (iter-1: 1 blocker / 4 should-fix / 2 suggestions; consensus on dead-code + boundary reframe — see `reviews/track-14-adversarial.md`)

All three iter-1 review reports converged on the same two blockers (`db/config` and pool-abstract dead-code; `record/impl` boundary unenforceable as stated) and the same set of should-fix items (corrected-baseline rule, narrowed D2 override, DatabaseSessionEmbedded coverage-gate framing, `@SequentialTest` constraints, listener ownership rule). Findings are absorbed directly into the Description above and into Step decomposition below — no iter-2 gate needed because the reframes are plan-level, not code-level. Suggestion-tier items (LinkBag focused step, ridbag naming clarity, testability-tier split) are encoded into individual steps where they fit.

## Steps

- [x] Step 1: Remeasure baseline + spot-check existing tests + `db/config` dead-code pins
  - [x] Context: warning
  > **Risk:** low — default (test-additive only; no production code change; new tests + coverage measurement)
  >
  > **What was done:**
  > - Ran `./mvnw -pl core -am clean package -P coverage` (the
  >   plan-suggested `-pl core,common` is incorrect because `common`
  >   is a package inside the `core` module, not a separate Maven
  >   module). Build succeeded in 9 m 38 s with all tests green.
  > - Wrote `track-14-baseline.md` recording the post-Track-13
  >   per-class live uncov for the five target packages plus the
  >   acceptance-band derivation per remaining step. The plan-cited
  >   `core/db` 1 268 / 66.5 % is confirmed close to current
  >   1 259 / 66.8 % — the stale-figure risk did not materialise.
  >   `common/serialization` is at 83.4 % / 62.9 % — matches the
  >   corrected post-Track-12 baseline exactly.
  > - PSI all-scope `ReferencesSearch` (in IntelliJ via mcp-steroid)
  >   re-confirmed every class in `core/db/config` has zero
  >   references outside the package — the prior all-scope claim
  >   holds at Step 1 implementation time.
  > - Added `DBConfigDeadCodeTest` (21 `@Test` methods, 363 LOC)
  >   under `core/src/test/java/.../db/config/`. Pins behavioural
  >   shape: defaults, four-arg ctor round-trip via builder, fluent
  >   self-return on every setter, fresh-instance guarantee on
  >   `build()` and `builder()`, multicast-arm precedence in
  >   `NodeConfigurationBuilder.build()`, defensive list copy on
  >   `UDPUnicastConfigurationBuilder.build()`, ordered append in
  >   `addAddress`, and `Address` record component equality.
  > - Spot-check (`core/db/*Test.java` inert-test audit) on
  >   `StringCacheTest` (32 LOC, 2 `@Test`s), `DBRecordLazyListTest`
  >   (87 LOC, 1 `@Test`), and `DatabasePoolImplTest` (588 LOC, 11
  >   `@Test`s) — all three are healthy: every `@Test` is annotated,
  >   assertions are real (no `assertEquals(byte[], byte[])`
  >   identity-based traps), no dead ignored bodies. Zero repairs
  >   needed.
  > - Added the `core/db/config` deletion item to the deferred-
  >   cleanup absorption block in `implementation-backlog.md`.
  >
  > **What was discovered:**
  > - Post-Track-13 module aggregate is **75.1 % line / 65.8 %
  >   branch** (up from 63.6 % / 53.3 % at Phase 1 baseline) — the
  >   first 13 tracks contributed +11.5 pp line / +12.5 pp branch
  >   while adding one new package (177→178). The remaining 23 532
  >   uncov lines are the Track 14–22 budget; the 85 / 70 target
  >   needs roughly 9 200 more lines / 1 970 more branches covered.
  > - The plan's `./mvnw -pl core,common` instruction in this step's
  >   sub-deliverables and in the verification step at the tail of
  >   the track is incorrect: `common` is a Java package inside
  >   `core` (per `pom.xml` modules: `test-commons`,
  >   `gremlin-annotations`, `core`, `server`, `tests`, `driver`,
  >   `examples`, `console`, `embedded`, `docker-tests`,
  >   `jmh-ldbc`). The correct invocation is `./mvnw -pl core -am
  >   clean package -P coverage`. Subsequent step descriptions in
  >   this file inherit the same typo — the verification step
  >   re-uses the same incorrect form. No plan-level correction
  >   needed beyond noting it here for the remaining steps.
  > - `DatabaseSessionEmbedded` is 670 uncov lines (54 % of the
  >   `core/db` package gap of 1 259) — the ATTRIBUTES dispatcher
  >   work targets only ~26 of those, so the post-dispatcher
  >   residual in this single class will still dominate the package
  >   acceptance-band calculation. The acceptance band in the
  >   baseline doc reflects this: `core/db` aggregate target is
  >   ≥ 75 % / ≥ 62 % rather than the project-wide 85 % / 70 %.
  > - `DatabasePoolImpl` is essentially complete (98.2 % / 84.6 %,
  >   1 line / 4 branches uncov) — the pool-path step will close
  >   those incidentally rather than as a focal target.
  > - The `EntityFieldWalker` 27-uncov entry in `core/db` actually
  >   walks `EntityImpl` properties — it is owned by the
  >   `record/impl` track (record-implementation work) and should
  >   be recorded as a coverage-credit attribution note for that
  >   track's Phase A remeasure.
  > - PSI re-validated zero references outside `core/db/config` for
  >   all six public classes; total intra-package class-level refs:
  >   MulticastConfguration=11, MulticastConfigurationBuilder=6,
  >   NodeConfiguration=4, NodeConfigurationBuilder=9,
  >   UDPUnicastConfiguration=10, UDPUnicastConfigurationBuilder=5.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/config/DBConfigDeadCodeTest.java` (new)
  > - `docs/adr/unit-test-coverage/_workflow/track-14-baseline.md` (new working file)
  > - `docs/adr/unit-test-coverage/_workflow/implementation-backlog.md` (modified — deferred-cleanup absorption block extended)
  >
  > **Critical context:**
  > - Test commit: `2813a795d4` — "Pin core/db/config dead-code package via behavioural shape tests".
  > - The 130 uncov lines in `core/db/config` are pinned dead — they
  >   stay at 0 % live coverage. This is by design (accept-lower for
  >   dead code) and the verification step's acceptance band
  >   excludes `core/db/config` from the `core/db*` aggregate
  >   target.

- [x] Step 2: Pool / helper / hook-abstract dead-code pins + StringCache hardening
  - [x] Context: warning
  > **Risk:** medium — multi-file logic (~7 dead-code pin classes) + extends shared test infrastructure (StringCacheTest concurrent path)
  >
  > **What was done:**
  > - PSI all-scope `ReferencesSearch` + `ClassInheritorsSearch` (via
  >   mcp-steroid) audited every candidate before pinning. Five are
  >   pure dead (0 production callers, 0 subclasses):
  >   `DatabasePoolBase`, `HookReplacedRecordThreadLocal`,
  >   `LiveQueryBatchResultListener`, `DatabaseLifecycleListenerAbstract`,
  >   `RecordMultiValueHelper`. Three are test-only reachable —
  >   production-side references are limited to a Javadoc tag for
  >   `RecordHookAbstract` and zero for `EntityHookAbstract`, and
  >   `DatabasePoolAbstract` is only subclassed by the (dead)
  >   `DatabasePoolBase` plus the test-only `TestPool` inner class.
  > - Added eight `<Class>DeadCodeTest` files under
  >   `core/src/test/java/.../db/` and `.../db/record(/record)/`.
  >   Pins use a mix of behavioural shape (state-machine transitions
  >   for `RecordMultiValueHelper.updateContentType`, per-`TYPE`
  >   dispatch for the hook abstracts, ThreadLocal contract for
  >   `HookReplacedRecordThreadLocal`) and reflection-only structure
  >   pins where instantiation would dirty engines-manager state
  >   (`DatabasePoolBase`, `DatabasePoolAbstract` — both register
  >   `YouTrackDBListener`s on construction).
  > - `HookReplacedRecordThreadLocalDeadCodeTest` runs under
  >   `@Category(SequentialTest)` to avoid racing the engines-manager
  >   shutdown listener that nulls `INSTANCE`.
  > - Extended `StringCacheTest` from 32 LOC / 2 `@Test`s to 257 LOC /
  >   8 `@Test`s: close-clear-and-reuse contract; idempotent close;
  >   `LRUCache.removeEldestEntry` off-by-one boundary pinned with a
  >   `WHEN-FIXED:` marker (steady-state size caps at `capacity-1` —
  >   re-confirmed Track 2's finding); access-order eviction (touched
  >   entry survives subsequent eviction sweeps); same-key concurrent
  >   convergence on a single interned reference (8 threads × 200
  >   gets); distinct-key concurrent flood within capacity cap.
  > - Appended deferred-cleanup queue entries to
  >   `implementation-backlog.md` for all eight deletions plus the
  >   `LRUCache` off-by-one production fix.
  > - Verification: `./mvnw -pl core spotless:check` clean; full core
  >   unit-test suite green (1 728 tests + 11 MT tests, 0 failures,
  >   13 pre-existing skips); the 9 affected classes alone run 51
  >   passing tests.
  >
  > **What was discovered:**
  > - `EntityHookAbstract` is referenced by 7 test subclasses (mostly
  >   anonymous inner classes inside `CheckHookCallCountTest`,
  >   `HookChangeValidationTest`, `DbListenerTest`) — production
  >   references are zero. Deletion in the deferred-cleanup track
  >   must update those test files in lockstep (delete or retarget at
  >   `RecordHook` directly).
  > - `RecordHookAbstract` has a single production-side reference: a
  >   Javadoc `@see` tag inside `RecordHook.java`. Its only concrete
  >   subclasses live in `tests/src/test/`: `BrokenMapHook` and
  >   `HookTxTest$RecordHook`.
  > - `DatabasePoolAbstract` is reached only through `DatabasePoolBase`
  >   (itself dead) plus the test subclass `TestPool` inside
  >   `DatabasePoolAbstractEvictionTest`. Deletion is therefore a chain
  >   — `DatabasePoolBase` first, then `DatabasePoolAbstract`, with
  >   the eviction-focused test either dropped or merged into
  >   `DatabasePoolImpl`'s eviction path.
  > - `MultiValue.toString` formats a `List<String>` as `[a, b]`
  >   (with space) not `[a,b]` — pin uses the actual format.
  > - The plan suggested `./mvnw -pl core,common` in this step's
  >   recipe, but `common` is a Java package inside `core`, not a
  >   Maven module — already noted in Step 1's episode; remained the
  >   correct usage in this step.
  > - The full-suite run now records **1 728 unit tests** (vs the
  >   pre-Step-1 baseline of 1 728 minus the new tests added in
  >   Step 1's `DBConfigDeadCodeTest` and Step 2's eight new
  >   `*DeadCodeTest` classes plus six new `StringCacheTest`
  >   assertions). Aggregate test count growth is healthy and matches
  >   the additive-only commit shape.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/DatabasePoolBaseDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/DatabasePoolAbstractDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/HookReplacedRecordThreadLocalDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/LiveQueryBatchResultListenerDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseLifecycleListenerAbstractDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/RecordMultiValueHelperDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/record/EntityHookAbstractDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/record/RecordHookAbstractDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/StringCacheTest.java` (modified — extended from 32 LOC to 257 LOC)
  > - `docs/adr/unit-test-coverage/_workflow/implementation-backlog.md` (modified — deferred-cleanup queue extended with 8 deletion items + 1 LRUCache off-by-one cleanup)
  >
  > **Critical context:**
  > - Test commit: `85b839b4f3` — "Pin core/db dead-code surface and
  >   harden StringCacheTest".
  > - Mockito is on the classpath via `test-commons` and is used here
  >   to stub `DBRecord` (for non-Entity hook dispatch) and
  >   `DatabaseSessionEmbedded` (for the lifecycle listener no-op
  >   contract). The Mockito self-attaching warning at runtime is a
  >   future-JDK heads-up, not a current failure.

- [x] Step 3: `db/record/record` interfaces + `db/record` value classes (mostly standalone)
  - [x] Context: info
  > **Risk:** low — default (pure tests; mostly standalone — no DbTestBase, no shared mutable state)
  >
  > **What was done:**
  > - Added 6 new standalone test files under `core/db/record/record/`
  >   and `core/db/record/`:
  >   - `DirectionTest` (9 tests) — pins the enum's value set, ordinals,
  >     names, and the `opposite()` involution (OUT↔IN, BOTH self-mapped),
  >     plus a `valueOf` rejection for lowercase input.
  >   - `RIDTest` (26 tests) — pins both `RID.of(int,long)` and
  >     `RID.of(String)` factories: prefix optionality, whitespace trim,
  >     null/empty/blank-routes-to-`ChangeableRecordId` sentinel,
  >     malformed-input rejection (missing/extra separator, non-numeric
  >     components), boundary checks on `collectionId` (-2..32767), the
  >     persistence/new-position predicates, `compareTo` ordering rules
  >     (collectionId first, position breaks ties), and the public
  >     constants (`PREFIX`, `SEPARATOR`, `COLLECTION_MAX`,
  >     `COLLECTION_ID_INVALID`, `COLLECTION_POS_INVALID`).
  >   - `VertexDefaultMethodsTest` (18 tests) — pins
  >     `Vertex.getEdgeLinkFieldName(Direction, String)` across all
  >     branches (null/BOTH rejection, prefix-only fast path for
  >     null/empty/`Edge.CLASS_NAME`, prefix+name otherwise, case
  >     sensitivity around `"edge"`), the `DIRECTION_*_PREFIX`
  >     constants and `Vertex.CLASS_NAME ↔ SchemaClass.VERTEX_CLASS_NAME`
  >     identity, plus the two `removeEdges(Direction, ...)` defaults
  >     via Mockito stubs (no-edge no-op, every-edge-deleted-once,
  >     labels and direction reach `getEdges` unchanged, BOTH propagates
  >     unchanged).
  >   - `EdgeDefaultMethodsTest` (11 tests) — pins
  >     `Edge.getVertex(Direction)` and `Edge.getVertexLink(Direction)`
  >     defaults: IN→`getTo()`/`getToLink()`, OUT→`getFrom()`/
  >     `getFromLink()`, BOTH rejected with the exact production
  >     message, null pass-through on every accessor; plus the
  >     `Edge.CLASS_NAME`/`DIRECTION_OUT`/`DIRECTION_IN` constants.
  >   - `MultiValueChangeEventTest` (15 tests) — POJO contract:
  >     `ChangeType` enum ordering, both constructor shapes, all four
  >     getters, equals reflexivity / null-rejection / foreign-type
  >     rejection, hashCode determinism on identical components,
  >     individual-field-divergence inequality (4 paths), and the
  >     all-null `hashCode()=0` branch (falsifies any field that
  >     might NPE if not null-guarded).
  >   - `MultiValueChangeTimeLineTest` (9 tests) — empty-on-construction,
  >     append-order over 3 events, null event accepted (observed
  >     shape pin), unmodifiable-view rejection of add/clear/
  >     remove(int), live-view (not snapshot) contract, and
  >     instances-are-independent.
  > - All 88 new test methods pass; the wider sibling-package run
  >   (`db/record/record/* + db/record/MultiValueChange*`) is 99/99
  >   green, including the two pre-existing `*HookAbstractDeadCodeTest`
  >   classes that share the package. Spotless clean.
  > - Identifiable was inspected during the inventory phase — the
  >   interface declares only `getIdentity()` (no default methods), so
  >   no `IdentifiableDefaultMethodsTest` is added. The default
  >   `getIdentity()` lives on `RecordIdInternal` and is exercised
  >   indirectly via `RIDTest.ofIntLongIdentityIsSelf` (pins the
  >   self-identity contract on `RecordId`).
  >
  > **What was discovered:**
  > - **Identifiable has no default methods.** The plan's
  >   `IdentifiableDefaultMethodsTest` becomes a no-op deliverable —
  >   self-identity is covered through `RecordIdInternal.getIdentity()`
  >   (default method on the sealed interface), pinned via the
  >   `RIDTest.ofIntLongIdentityIsSelf` case. This is recorded for
  >   Track 15's strategy refresh: any future record-impl test that
  >   depends on `Identifiable.getIdentity()` should pin against the
  >   `RecordIdInternal` default rather than expecting one on
  >   `Identifiable` itself.
  > - **`RID.of(String)` accepts a missing `#` prefix.** The
  >   `RecordIdInternal.fromString` parser uses a separator-only check
  >   (`StringSerializerHelper.contains(s, ':')`) and not the
  >   `PatternConst.PATTERN_RID` regex. Inputs like `"5:17"` (no `#`)
  >   parse successfully, while `RecordIdInternal.isA(...)` would
  >   classify the same string as not-an-RID. Pinned via
  >   `RIDTest.ofStringAcceptsNoPrefix` as observed shape — there is
  >   already prior art for this asymmetry across the codebase, so we
  >   record it without flagging a regression.
  > - **`RID.of(String)` with negative position returns `RecordId`,
  >   not `ChangeableRecordId`.** `RID.of(s)` calls
  >   `RecordIdInternal.fromString(s, false)` with `changeable=false`,
  >   so a string like `"#5:-1"` produces a `RecordId` record (with a
  >   negative position) rather than a `ChangeableRecordId`. Pinned
  >   via `RIDTest.ofStringWithNegativePositionStillReturnsRecordIdWhenNotChangeable`.
  >   Callers that need a changeable RID for negative positions must
  >   go through the internal `RecordIdInternal.fromString(s, true)`
  >   path directly (which is not exposed on the public `RID`
  >   interface).
  > - **`MultiValueChangeTimeLine` accepts `null` events without a
  >   guard.** `addCollectionChangeEvent(null)` enters the underlying
  >   list and shows up in `getMultiValueChangeEvents()`. Pinned as
  >   observed shape via `nullAppendIsAccepted`. No `WHEN-FIXED`
  >   marker — every production caller currently constructs a
  >   non-null event before invoking the API, so adding a guard would
  >   be a behaviour change rather than a fix.
  > - **`MultiValueChangeEvent.equals()` accepts a foreign-type
  >   right-hand side without throwing**, but the asymmetric `instanceof`
  >   pattern returns `false` cleanly — pinned for falsifiability via
  >   `equalsRejectsForeignType`. The `@SuppressWarnings("ConstantConditions")`
  >   on `equalsRejectsNull` matches the existing pattern in nearby
  >   `*HookAbstractDeadCodeTest` files.
  > - **Coverage attribution.** `db/record/record` coverage credit
  >   from Step 3 is recorded on Track 14, but Track 15's `record/impl`
  >   work (`EntityImpl` exercising `Vertex`/`Edge`/`Identifiable`
  >   default methods) will further raise the same package's numbers.
  >   The Step 6 verification will report the post-Step-5 baseline so
  >   Track 15's Phase A can subtract Track 14's portion.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/record/DirectionTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/record/RIDTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/record/VertexDefaultMethodsTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/record/EdgeDefaultMethodsTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/MultiValueChangeEventTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/MultiValueChangeTimeLineTest.java` (new)
  >
  > **Critical context:**
  > - Test commit: `f14433c41c` — "Pin Direction/RID/Vertex/Edge
  >   interface defaults + MultiValueChange POJOs".
  > - Mockito (already on the test classpath via `test-commons`) is
  >   used with `CALLS_REAL_METHODS` to drive interface-default
  >   methods on `Vertex` and `Edge`. The `mock(Edge.class)` /
  >   `mock(Vertex.class)` calls do not touch a session and run
  >   entirely in-process — no `DbTestBase`, no `@Category(SequentialTest)`
  >   needed. The Mockito self-attaching warning emitted at runtime is
  >   the same future-JDK heads-up already present in the
  >   `*HookAbstractDeadCodeTest` files; it is not a current failure.

- [x] Step 4: `db/record` collection wrappers + `db/record/ridbag` LinkBag conversion
  - [x] Context: warning
  > **Risk:** medium — multi-file tests using DbTestBase + EntityImpl as collaborator (boundary-crossing requires explicit fixture management)
  >
  > **What was done:**
  > - Wrote three new test classes covering `db/record` types that had
  >   no dedicated tests: `EntityEmbeddedListImplTest` (32 `@Test`),
  >   `EntityLinkSetImplTest` (24 `@Test`), `EntityLinkMapImlTest`
  >   (40 `@Test`).
  > - Wrote one new test class for the ridbag package:
  >   `LinkBagConversionTest` (18 `@Test`) under
  >   `core/src/test/java/.../db/record/ridbag/`.
  >   `@Category(SequentialTest)` because `@Before`/`@After` mutate
  >   `GlobalConfiguration.LINK_COLLECTION_*_THRESHOLD` with restore.
  > - Extended `EntityEmbeddedMapImplTest` (+25 `@Test`),
  >   `EmbeddedSetTest` (+18 `@Test`), `LinkListTest` (+30 `@Test`).
  > - All 218 tests pass; full coverage build green.
  >
  > **What was discovered:**
  > - `EntityLinkSetImpl.addInternal(Identifiable)` is a stub no-op
  >   that silently drops its argument. Pinned via
  >   `EntityLinkSetImplTest#addInternalIsNoOpStub` with a
  >   `// WHEN-FIXED:` marker forwarding to deferred-cleanup.
  >   Either implement it to mirror
  >   `EntityEmbeddedSetImpl.addInternal` or document the no-op
  >   contract in production javadoc.
  > - `EntityLinkSetImpl(session, delegate)` ctor has a
  >   `assert ((AbstractLinkBag) delegate).getCounterMaxValue() == 1`
  >   pre-condition; not exercised by current tests (no caller
  >   constructs the wrapper this way at the unit-test layer).
  >   Forwarded.
  > - `LinkBag.checkAndConvert()` / `convertToTree()` /
  >   `convertToEmbedded()` need a live `BTreeCollectionManager` plus
  >   active atomic operations to actually convert. Pinned the
  >   selection-at-construction logic via threshold flip
  >   (≥ 0 → `EmbeddedLinkBag`, < 0 → `BTreeBasedLinkBag`); the
  >   conversion paths themselves remain unexercised at the
  >   unit-test layer and are forwarded to the deferred-cleanup
  >   track for the storage IT layer to absorb.
  > - `LinkBagDeleter.deleteAllRidBags` iterating-loop path requires
  >   `entity.getLinkBagsToDelete()` to be populated, which only
  >   happens via the in-storage delete pipeline — pinned only the
  >   no-pending-bags branch. Forwarded.
  > - The "Track 11 wrapper-type precedent" reference in the plan's
  >   acceptance text was inadvertently copied into one production
  >   javadoc line at first; cleaned up in the follow-up commit.
  >
  > **What changed from the plan:**
  > - The plan suggested the conversion threshold "lives in
  >   `RidBagsConfig` — pin via test, not by GlobalConfiguration
  >   mutation" — but PSI confirms the threshold actually reads from
  >   `GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD`
  >   directly via `session.getConfiguration()`. Followed the
  >   precedent established by `LinkBagAtomicUpdateTest`:
  >   `@Category(SequentialTest)` + before/after restore of the
  >   global value. `RidBagsConfig` does not exist in the codebase
  >   under that name.
  > - The plan's `BTreeBonsaiLocal.class` reference is stale — actual
  >   class is `BTreeBasedLinkBag` (under
  >   `core/storage/ridbag/`). Updated the wrapper-type assertion
  >   accordingly.
  > - The plan asked to apply "Iterable detach-after-commit" pattern
  >   in every test. That pattern is only needed for tests that
  >   return lazy iterables across a `session.commit()` boundary.
  >   Step 4's tests run inside a single transaction that's rolled
  >   back, so the pattern is not load-bearing here.
  >
  > **Coverage delta** (per-package, vs Step-1 baseline):
  > - `core/db/record`: 72.6% → 90.7% line / 61.7% → 79.3% branch
  >   (-248 uncov; from 376 to 128). Acceptance band ≥ 80% / ≥ 70%
  >   met with margin.
  > - `core/db/record/ridbag`: 84.0% → 87.3% line / 68.3% → 78.3%
  >   branch (-5 uncov; from 24 to 19). Acceptance band ≥ 92% /
  >   ≥ 80% NOT fully met — residual gaps are in the conversion
  >   paths (`convertToTree` / `convertToEmbedded` /
  >   `checkAndConvert` non-no-op branches) and the
  >   `LinkBagDeleter` iterating loop, both reachable only with
  >   storage IT fixtures. Forwarded to deferred-cleanup.
  > - `core/db/record/record`: 58.4% → 89.2% / 37.5% → 76.4%
  >   (-51 uncov, attribution shared with Step 3 — most gain came
  >   from Step 3 default-method pins; Step 4 contributed marginal
  >   `Identifiable` traffic via collection wrappers).
  > - `core/db`: 66.8% → 67.6% / 52.6% → 53.0% (-33 uncov; gain is
  >   from Steps 1–4 collectively, Step 4 contributes a small
  >   delta via collection wrapper / record-element default-method
  >   exercise).
  > - Coverage gate vs `origin/develop` PASSED (test-additive diff
  >   contributes 0 changed production lines).
  >
  > **Deferred-cleanup additions** (forwarded into Track 22 backlog):
  > - `EntityLinkSetImpl.addInternal` stub: implement-or-document
  >   contract.
  > - `EntityLinkSetImpl(session, LinkBagDelegate)` ctor's
  >   `counterMaxValue == 1` assertion not exercised at unit-test
  >   layer.
  > - `LinkBag.checkAndConvert` / `convertToTree` /
  >   `convertToEmbedded` conversion paths: storage-IT-level
  >   coverage required.
  > - `LinkBagDeleter.deleteAllRidBags` iterating-loop path:
  >   storage-IT-level coverage required.
  > - `EntityLinkMapIml.addInternal` throws
  >   `UnsupportedOperationException` — contract guard;
  >   alternatively reshape the `LinkTrackedMultiValue` interface
  >   so the unimplemented method does not appear on the LinkMap
  >   contract.
  >
  > **Key files:**
  > - `core/src/test/java/.../core/db/record/EntityEmbeddedListImplTest.java` (new)
  > - `core/src/test/java/.../core/db/record/EntityLinkSetImplTest.java` (new)
  > - `core/src/test/java/.../core/db/record/EntityLinkMapImlTest.java` (new)
  > - `core/src/test/java/.../core/db/record/ridbag/LinkBagConversionTest.java` (new)
  > - `core/src/test/java/.../core/db/record/EntityEmbeddedMapImplTest.java` (modified, +25 `@Test`)
  > - `core/src/test/java/.../core/db/record/EmbeddedSetTest.java` (modified, +18 `@Test`)
  > - `core/src/test/java/.../core/db/record/LinkListTest.java` (modified, +30 `@Test`)

- [x] Step 5: `DatabaseSessionEmbedded.set`/`setCustom` + lifecycle + pool path
  - [x] Context: warning
  > **Risk:** medium — multi-file logic in core test infrastructure (DbTestBase tests for ATTRIBUTES dispatcher + pool acquire/release/idle-timeout); `@Category(SequentialTest)` for engines-manager interaction
  >
  > **What was done:**
  > - Added `DatabaseSessionEmbeddedAttributesTest` (32 `@Test`,
  >   ~330 LOC) extending `DbTestBase`. Pins all six
  >   `ATTRIBUTES` cases (DATEFORMAT, DATE_TIME_FORMAT, TIMEZONE,
  >   LOCALE_COUNTRY, LOCALE_LANGUAGE, CHARSET) — happy path,
  >   null-value rejection (DATEFORMAT / DATE_TIME_FORMAT message
  >   "date format is null" pinned exactly; TIMEZONE message
  >   "Timezone can't be null" pinned exactly), invalid-pattern
  >   rejection via unmatched single quote (portable across SDF
  >   versions), `IOUtils.getStringContent` quote-stripping
  >   contract, the TIMEZONE upper-case → fall-through retry
  >   branch (mixed-case `Europe/Paris` recognised on retry),
  >   silent GMT fallback for unknown TZ ids (observed shape).
  >   Pins `set(ATTRIBUTES_INTERNAL.VALIDATION, value)` for true
  >   /false/null/garbage inputs, plus `setInternal` delegation.
  >   `setCustom` covers `clear`/`Clear`/`CLEAR` keyword paths,
  >   non-clear-with-non-null-value fall-through, empty-value
  >   collapse-to-remove, null-name short-circuit (both with and
  >   without value), Object stringification via `"" + iValue`,
  >   and the **latent NPE** when `setCustom("realname", null)` —
  >   `customValue` is null and `customValue.isEmpty()` evaluates
  >   because the short-circuit only triggers on `name == null`.
  >   Pinned via expected `NullPointerException` with a
  >   forwards-to deferred-cleanup marker.
  > - Added `CachedDatabasePoolFactoryImplTest` (14 `@Test`,
  >   ~340 LOC) extending `DbTestBase`. Pins `getOrCreate`
  >   cache miss/hit/closed-pool replacement, parent-config
  >   wiring (`null` and non-null branches), the matching
  >   `getOrCreateNoAuthentication` triplet (poolOpenNoAuthenticate
  >   live path on the embedded factory), `reset()` returning
  >   the factory and closing every cached pool, idempotent
  >   `close()` setting `closed=true` and rejecting subsequent
  >   `getOrCreate(...)` with the exact production message
  >   `Cached pool factory is closed!`, the `setMaxPoolSize`
  >   fluent contract + default sourced from
  >   `GlobalConfiguration.DB_POOL_MAX`, and the periodic
  >   clean-up sweep that prunes closed pools. The 1 s sleep in
  >   the cleanup sweep test proved sufficient for the 50 ms
  >   eviction interval to fire on slow CI runners during local
  >   verification.
  > - Added `ExecutionThreadLocalTest` (8 `@Test`,
  >   ~210 LOC, `@Category(SequentialTest.class)`) standalone
  >   (no `DbTestBase`). Pins `INSTANCE` non-null contract,
  >   `initialValue()` producing a fresh `ExecutionThreadData`
  >   record with both `onAsyncReplicationOk` /
  >   `onAsyncReplicationError` defaulting to null,
  >   `isInterruptCurrentOperation` SoftThread / non-SoftThread
  >   branches, the instance `setInterruptCurrentOperation(Thread)`
  >   no-op-on-non-Soft + softShutdown-on-Soft branches, and the
  >   static no-arg `setInterruptCurrentOperation()` invoked from
  >   inside a SoftThread's `run()` (so `currentThread()` is the
  >   SoftThread).
  > - Extended `DatabasePoolAbstractEvictionTest` (+10 `@Test`,
  >   from 2 → 12 tests, ~140 LOC delta). Pins the dead-code-
  >   bound abstract class's accessor + listener surface that
  >   the existing eviction-only test missed: `getMaxSize`,
  >   the four `unknownPool → 0/maxSize` getters, the
  >   unmodifiable `getPools()` view, no-op `remove(unknown)`,
  >   no-op `onStorageRegistered`, no-op
  >   `onStorageUnregistered(no-match)`, and `onShutdown`
  >   delegating to `close` without throwing on an empty pool.
  > - Verification: `./mvnw -pl core spotless:check` clean
  >   (1132 files, no changes). All 66 new tests pass plus the
  >   broader `core/db/*Test` aggregate run (152 tests, 0
  >   failures). Total LOC ~1020 — under the 1500 split
  >   threshold so the commit landed as a single Step 5
  >   (no 5a/5b split needed).
  >
  > **What was discovered:**
  > - **Latent NPE in `DatabaseSessionEmbedded.setCustom`**
  >   (lines 552–561): when `iValue == null` AND `name`
  >   is anything other than (case-insensitive) `"clear"`, the
  >   else-branch sets `customValue = null` and the subsequent
  >   `if (name == null || customValue.isEmpty())` short-
  >   circuits only on the first arm. With a non-null `name`,
  >   `customValue.isEmpty()` runs on null and throws NPE.
  >   Production callers that use `setCustom("foo", null)` to
  >   intend a remove will see this. Pinned via
  >   `setCustomNonClearNameNullValueThrowsNpePinningLatentBug`;
  >   forwarded to deferred-cleanup absorption block — fix
  >   options: (a) treat null-value-non-clear-name as remove
  >   (route through `removeCustomInternal(name)`), (b) guard
  >   `customValue==null` before `.isEmpty()`.
  > - **TIMEZONE backward-compat retry effective only for
  >   already-correct mixed-case ids.** Java's `TimeZone.getTimeZone`
  >   is case-sensitive; uppercased "EUROPE/PARIS" is unknown
  >   (returns GMT), then the retry uses the original
  >   `stringValue`. So the retry succeeds when the input
  >   already is the canonical Java TZ id (e.g.
  >   `Europe/Paris`), but the documentation comment "until
  >   2.1.13 YouTrackDB accepted timezones in lowercase as
  >   well" is misleading: a fully-lowercase input
  >   (`europe/paris`) yields GMT both times. Pinned the
  >   working mixed-case retry as the observed shape;
  >   noted the comment lag for forward-cleanup.
  > - **`setCustom` Object value is stringified via `"" + iValue`**
  >   (string concatenation), not `String.valueOf(iValue)`.
  >   For most inputs the two are identical, but the difference
  >   becomes observable for `char[]` (concat → `[C@...`,
  >   `valueOf` → array contents). Pinned via the numeric
  >   value test as the observed shape; no behaviour-change
  >   request — refactor candidate only.
  > - **`setLocaleCountry(null)` reaches storage as null without
  >   any guard.** No exception, no replacement-with-empty-
  >   string normalization. Pinned the no-throw contract
  >   without asserting on the post-condition (different
  >   storage implementations may collapse null differently).
  > - **`Boolean.parseBoolean(null)`** in
  >   `set(ATTRIBUTES_INTERNAL.VALIDATION, null)` returns
  >   false (Java contract); the dispatcher silently passes
  >   null down without rejecting it. Pinned as observed
  >   shape — silent acceptance of null is consistent with
  >   the storage's accept-everything contract.
  > - **`CachedDatabasePoolFactoryImpl.getMaxPoolSize()` default**
  >   is sourced from `GlobalConfiguration.DB_POOL_MAX`, not
  >   from a constructor argument. Tests that rely on a
  >   specific default must read the global rather than
  >   hard-coding 64.
  > - **Original `DatabasePoolAbstractEvictionTest` left an
  >   unused `import java.util.concurrent.TimeUnit;`** — the
  >   editing pass removed it. No production-side impact, but
  >   future imports of TimeUnit must be re-added explicitly.
  >
  > **What changed from the plan:**
  > - The plan suggested extending `SessionPoolTest` and
  >   `DatabasePoolImplTest` for "acquire/release/exhaustion/
  >   idle-timeout paths". `DatabasePoolImplTest` is already
  >   at 98.2% line / 84.6% branch (Step 1 baseline) with 11
  >   comprehensive `@Test` methods covering exactly that
  >   surface, including a 4-thread concurrent close-during-
  >   acquire test. The marginal 1 line / 4 branches uncov
  >   target was deemed not worth the test-effort/risk
  >   trade-off — confirmed via re-read of the existing
  >   tests. `SessionPoolTest` (3 tests, 85 LOC) is a thin
  >   integration-style harness over `SessionPoolImpl` and
  >   `DatabasePoolImpl`; the additional pool-path coverage
  >   the plan asked for is already covered by
  >   `CachedDatabasePoolFactoryImplTest`'s factory tests
  >   plus the existing `DatabasePoolImplTest` body, so no
  >   `SessionPoolTest` extension was added.
  > - The plan listed "all 14 `ATTRIBUTES` enum values" —
  >   `DatabaseSessionEmbedded.ATTRIBUTES` actually has 6
  >   values, plus `ATTRIBUTES_INTERNAL.VALIDATION`. The
  >   "≈26 uncov lines / 25 uncov branches" figure from
  >   technical review T4 was the load-bearing target;
  >   delivery covers the entire dispatcher surface
  >   (6 + 1 + setCustom branches + setInternal delegation).
  > - The plan suggested no commit split necessary;
  >   confirmed: total ~1020 test LOC stayed under the 1500
  >   threshold so a single commit was used.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbeddedAttributesTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/CachedDatabasePoolFactoryImplTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/ExecutionThreadLocalTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/DatabasePoolAbstractEvictionTest.java` (modified, +10 `@Test`)
  >
  > **Critical context:**
  > - Test commit: `46facb1543` — "Pin DatabaseSessionEmbedded ATTRIBUTES dispatcher + pool path".
  > - The `setCustom` NPE pin and the TIMEZONE backward-compat
  >   comment-lag both feed the deferred-cleanup track absorption
  >   block — to be added to the backlog at Step 6 verification
  >   alongside the per-package coverage delta and any
  >   `MemoryStream` residual rediscovered there.

- [x] Step 6: YouTrackDBConfig builder/impl + CommandTimeoutChecker + SystemDatabase + verification
  - [x] Context: warning
  > **Risk:** medium — multi-file tests + final coverage verification + `MemoryStream` re-measurement and forward
  >
  > **What was done:**
  > - Extended `YouTrackDBConfigImplTest` from 4 → 28 `@Test` methods
  >   (24 new, ~480 LOC). Pins the seven `fromApacheConfiguration`
  >   dispatcher arms (six date-format / locale / charset / time-zone
  >   keys plus password-skip plus configuration passthrough), the
  >   six-attribute `toApacheConfiguration` round-trip, listener
  >   wiring with HashSet dedup, the three internal builder methods
  >   not on the public interface (`setSecurityConfig`, `fromContext`,
  >   `addGlobalUser`), the public no-arg ctor surface (default
  >   security config = `DefaultSecurityConfig`, class loader =
  >   own-class loader), and the eight `setParent` branches plus the
  >   protected ctor's null-listener fallback to
  >   `Collections.emptySet()`. Builder fluent contract pinned via
  >   identity-equality on every setter return.
  > - Extended `CommandTimeoutCheckerTest` from 2 → 9 `@Test` methods
  >   (7 new, ~290 LOC). Pins the worker-thread isolation invariant
  >   per the risk review's R7 finding (only registered threads are
  >   interrupted; a bystander worker that never called
  >   `startCommand` runs to completion); disabled-mode CTOR with
  >   `timeout = 0` and `timeout = -1` (`close()` tolerates a null
  >   timer); per-command timeout overriding the constructor default;
  >   `endCommand` unregistration before the periodic sweep fires;
  >   idempotent `endCommand` plus `startCommand` re-registration on
  >   the same thread; `close()` cancels the periodic timer. An
  >   `@After` hook clears the interrupted flag defensively against
  >   surefire worker reuse — every test registers commands from a
  >   freshly-created `Thread`, never from the JUnit harness thread.
  > - Added `SystemDatabaseTest` (13 `@Test` methods, ~370 LOC,
  >   `@Category(SequentialTest)` per the risk review's R8 finding
  >   because `SYSTEM_DB_NAME` is a hardcoded constant). Pins the
  >   lazy-init contract on first access, `getServerId()` stability
  >   across `init()` cycles (the `browseClass` "row already exists"
  >   branch unreachable from `SystemDatabaseDisabledTest`), the
  >   four callback delegators (`execute` / `query` /
  >   `executeInDBScope` / `executeWithDB`) with assertions on
  >   session identity, database name, and active-tx context,
  >   `getSystemDatabase()` returns the same wrapper instance,
  >   late-mutation invariant on the captured-at-ctor enabled flag
  >   (a subsequent global flip to false does not retroactively
  >   disable a live system DB), public constants pin
  >   (`SYSTEM_DB_NAME` / `SERVER_INFO_CLASS` / `SERVER_ID_PROPERTY`),
  >   malformed-SQL recovery pin (system DB stays usable after a
  >   parser exception), and a falsifiable-regression pin for the
  >   latent shape where `openSystemDatabaseSession()` does not
  >   populate `serverId` when the OSystem DB already exists.
  > - Coverage build (`./mvnw -pl core -am clean package -P coverage`)
  >   ran 9 m 56 s, exit 0. Coverage gate vs `origin/develop` PASSED:
  >   100.0% line / 100.0% branch on changed production lines
  >   (trivially, since the track is purely test-additive).
  > - Updated `track-14-baseline.md` with a "Post-track measurement
  >   (Step 6 verification)" section recording aggregate, package,
  >   per-class, `MemoryStream` re-measurement, and the deferred-
  >   cleanup absorption-block updates.
  > - Updated `implementation-backlog.md` with five new deferred-
  >   cleanup entries: `setCustom` latent NPE, TIMEZONE comment lag,
  >   `setCustom` `"" + iValue` stringification, `SystemDatabase`
  >   `serverId`-not-populated-when-DB-exists, plus carried-forward
  >   `MemoryStream` residual from Track 12 deferred item h.
  > - Verification: full Step 6 test classes pass under sequential
  >   test runner (28 + 9 + 13 + 1 existing `SystemDatabaseDisabledTest`
  >   = 51 tests, 0 failures, 0 errors). Spotless clean (`./mvnw -pl
  >   core spotless:check` no changes).
  >
  > **What was discovered:**
  > - **Latent shape in `SystemDatabase`**: `openSystemDatabaseSession()`
  >   only calls `init()` when `!exists()` — so when the OSystem DB
  >   already exists from a previous run (in-process or on disk), a
  >   freshly-constructed `SystemDatabase` wrapper's `serverId` field
  >   stays null until `init()` is explicitly invoked. Production
  >   callers that need `serverId` already call `init()` directly, so
  >   this is queued as a defensive consistency improvement rather
  >   than a live bug. Pinned via
  >   `openSessionDoesNotPopulateServerIdWhenDbAlreadyExists`.
  > - **Storage-side validation rejects DB names starting with
  >   "server"**: the test method `serverIdIsStableAcrossRepeatedOpens`
  >   originally failed at `DbTestBase.beforeTest` because the storage
  >   layer rejects database names beginning with "server". Renamed to
  >   `getServerIdIsStableAcrossRepeatedOpens`; same fix for two other
  >   tests. Recorded as a test-authoring constraint for future
  >   `*ServerId*` test names anywhere in the suite.
  > - **`setParent`'s `parent.listeners != null` guard is unreachable
  >   via the protected ctor** because the ctor coerces a null listener
  >   set to `Collections.emptySet()`. The "skip merge" branch in
  >   `setParent` is therefore dead. Pinned via
  >   `setParentMergesEvenWhenParentBuiltWithNullListeners` which
  >   exercises the always-taken merge branch with a parent built
  >   through that exact null path.
  > - **`MemoryStream` deprecated record-id paths still uncov**: the
  >   Track 12 deferred item h carry-forward expectation that Steps
  >   4–5's CRUD work would drive the `RecordId*` paths did not
  >   materialise. `MemoryStream` post-Step-6 is at 62.3% / 58.0%
  >   (69 of 183 uncov), unchanged in spirit from the Track 12 figure.
  >   The residual concentrates in the deprecated record-id constructor
  >   and the legacy `setSize` / `available` / `readableBytes` helpers
  >   — paired with Track 15's `record/impl` work since `EntityImpl`
  >   exercising `RecordId.fromStream(MemoryStream)` is the remaining
  >   production caller that would drive the residual.
  > - **`core/db` aggregate falls 3.4 pp / 4.9 pp short of the Step 1
  >   acceptance band** because `DatabaseSessionEmbedded` still has
  >   636 uncov lines (Step 5 ATTRIBUTES dispatcher work closed only
  >   34 of the original 670). The remaining residual is out-of-scope-
  >   by-design per the Track 14 description's coverage-gate framing
  >   for this 4 618-LOC class. Forwarded to deferred-cleanup.
  > - **`core/db/config` rose to 95.4% / 100.0% live** vs the plan's
  >   "stays at 0% live" forecast — the Step 1 `DBConfigDeadCodeTest`
  >   shape pin actually drives every public-method branch, so the
  >   "dead code" is now load-bearing under JaCoCo. Net deletion-queue
  >   effect is unchanged: the package is still queued for deletion,
  >   the 124 newly-covered lines simply mean the pre-deletion shape
  >   is now load-bearing.
  >
  > **What changed from the plan:**
  > - The plan's "binary entity-class registration" sub-deliverable for
  >   `YouTrackDBConfigImplTest` does not have a corresponding API
  >   surface in `YouTrackDBConfigBuilderImpl` (no
  >   `addBinaryEntityClass` / `registerEntityClass` method exists in
  >   `core/db/`). Replaced with the equivalent-effort coverage of
  >   internal builder methods (`setSecurityConfig`, `fromContext`,
  >   `addGlobalUser`) which were previously untested.
  > - The plan suggested writing tests for "binary entity-class
  >   registration" — that surface lives in `core/serialization/binary`
  >   (Track 13 scope), not `core/db`. No coverage gap left here for
  >   Track 14.
  > - The plan's `./mvnw -pl core,common -am clean package -P coverage`
  >   recipe is incorrect (`common` is a Java package inside `core`,
  >   not a Maven module). Used the correct
  >   `./mvnw -pl core -am clean package -P coverage` (already noted
  >   in Step 1's episode). Same applies to the verification step.
  > - The plan asked the verification to record "live coverage of all
  >   five target packages (analogous to Track 13's per-package final
  >   report)". Done — see the post-track section in
  >   `track-14-baseline.md`. The acceptance-band assessment is in
  >   that file as well.
  >
  > **Coverage delta** (post-Step-6 vs Step-1 baseline):
  > - `core/db`: 66.8% → **71.6%** line / 52.6% → **57.1%** branch
  >   (1 259 → 1 077 uncov, −182). Acceptance band ≥ 75% / ≥ 62%
  >   **NOT met** by 3.4 pp / 4.9 pp; residual is `DatabaseSessionEmbedded`
  >   (636 uncov, out-of-scope-by-design). Forwarded.
  > - `core/db/config`: 0% → **95.4%** line / n/a → **100.0%** branch
  >   (130 → 6 uncov, −124). Above plan forecast (dead-code pin drove
  >   methods); deletion queue unchanged.
  > - `core/db/record`: 72.6% → **92.0%** line / 61.7% → **80.0%**
  >   branch (376 → 110 uncov, −266). Acceptance band ≥ 80% / ≥ 70%
  >   **exceeded**.
  > - `core/db/record/record`: 58.4% → **89.2%** line / 37.5% →
  >   **76.4%** branch (69 → 18 uncov, −51). Acceptance band ≥ 70% /
  >   ≥ 55% **exceeded**.
  > - `core/db/record/ridbag`: 84.0% → **87.3%** line / 68.3% →
  >   **78.3%** branch (24 → 19 uncov, −5). Acceptance band ≥ 92% /
  >   ≥ 80% **NOT met** — conversion paths require storage-IT-level
  >   fixtures (already forwarded in Step 4 episode).
  >
  > **Aggregate (whole `core` module):** 75.1% → **75.9%** line /
  > 65.8% → **66.4%** branch. Track 14 contribution: +0.8 pp line /
  > +0.6 pp branch. Tracks 1–14 cumulative from Phase 1 baseline:
  > +12.3 pp line / +13.1 pp branch.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/YouTrackDBConfigImplTest.java` (modified, +24 `@Test`)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/CommandTimeoutCheckerTest.java` (modified, +7 `@Test` + `@After clearInterrupt` hook)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/SystemDatabaseTest.java` (new, 13 `@Test`, `@Category(SequentialTest)`)
  > - `docs/adr/unit-test-coverage/_workflow/track-14-baseline.md` (modified — post-track section appended)
  > - `docs/adr/unit-test-coverage/_workflow/implementation-backlog.md` (modified — five Step 5/6 deferred items added)
  >
  > **Critical context:**
  > - Test commit: `860f6eb3da` — "Pin YouTrackDBConfig + CommandTimeoutChecker + SystemDatabase tests".
  > - Total Step 6 test LOC: ~1 140 (under the 1 500 split threshold).
  >   Single commit per the plan-anticipated path.
  > - Coverage build PID 203881 ran 9 m 56 s; surefire JVM (PID 204699)
  >   ran tests across 1 forked process; jacoco.exec at
  >   `core/target/jacoco.exec`; aggregate XML at
  >   `.coverage/reports/youtrackdb-core/jacoco.xml`.
