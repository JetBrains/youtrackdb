# Track 22b: In-Track Dead-Code Deletion Lockstep

## Base commit

`88b3526f40545cc6119c1dc03191c736d17fdb43` — recorded at Phase B startup.

## Description

Atomic per-cluster commits removing dead production code together
with its `*DeadCodeTest.java` shape pin (and any live-named test
that references the deleted surface — see "How" below), classified
via `mcp-steroid://ide/safe-delete` (SPI-safe clusters deleted in-
track; SPI-risky / abstract-base / live-consumer clusters deferred
to 22c). Each in-track-deletion cluster is one bisectable commit.
Coverage is recomputed only at the final verification step (per
T10) — per-commit JaCoCo rebuilds are unnecessary since each cluster
commit is bisectable on its own.

**Scope:** ~9–11 cluster commits + 1 license-header normalization
commit + 1 final verification + cluster-disposition artifact = ~11–13
commits total (controlled exception per D5; widened from "~9" to
accommodate Phase A iter-1 corrections that split `sbtree` into two
disposition buckets, surfaced the SqlRoot scaffold cluster, and
moved several clusters to 22c-defer). License-header normalization
is split off as a separate end-of-track commit (per R7/A10), not
folded into per-cluster commits, so bisectability is preserved.

**Depends on:** Track 1, Track 22a (consumes 22a's PSI safe-delete
confirmations, the post-22a coverage baseline, and the Phase C
forwarded items below).

> **Phase A iter-1 corrections (PSI-driven cluster re-baselining):**
> Iter-1 review (technical + risk + adversarial) re-confirmed
> classifications via PSI find-usages and surfaced ~24 findings
> including 5 blockers and 12 should-fix items. The cluster
> inventory below reflects the corrected classifications. The full
> iter-1 finding tally is in `## Reviews completed` below; the
> evidence chains live in the orchestrator's iteration context and
> are not persisted as a separate file (per
> `track-review.md` §What You Do step 3 — the durable trace is the
> resulting step-file edits). Headline corrections:
> - `BasicCommandContext.copy()` trim narrowed: only `copy()` is
>   dead (NOT `setChild`/`getChild` — 8 live callers in
>   `BasicCommandContextTest`); commit must also delete
>   `CommandContext.copy()` from the (internal) interface declaration.
> - `RecordSerializerCSVAbstract` and `RecordSerializerStringAbstract`
>   moved to 22c-defer (abstract bases with live cross-package
>   production consumers).
> - `core/query/live/` whole-package deletion downgraded to
>   "specific dead static methods + orphan listener interfaces"
>   (`SharedContext.java` references the inner `LiveQueryOp` /
>   `LiveQueryOps` types).
> - `core/fetch/` whole-package deletion downgraded to partial-class-
>   trim (`DepthFetchPlanTest` is live and exercises live branches).
> - `sbtree` split: `singlevalue/v1` in-track (clean), `local/v1`
>   deferred to 22c (live `SBTree*V1Test` tests exist).
> - `ScriptInterceptor` / `ScriptInjection` reclassified from
>   "partial-trim of SPIs" to full interface deletion (zero
>   implementers); `ScriptManager` register/unregister methods
>   become a separate consumer partial-trim.
> - ScriptExecutorRegister cluster widened to include
>   `ScriptManager.java:145` lookup loop, `DatabaseScriptManager`,
>   `CommandExecutorUtility` (intra-cluster dead consumers).
> - `MockSerializer` + `BinarySerializerFactory` id `-10` moved to
>   22c-defer (`BinarySerializerFactory.java:111` comment "used for
>   spatial indexes" — slot reserved for the lucene module).
> - `CollectionSelectionStrategy` cluster (3 pins) added to 22c-defer
>   (SPI-registered with live sibling `RoundRobinCollectionSelectionStrategy`).
> - `SqlRoot` scaffold cluster (`SqlRootDeadCodeTest` pins
>   `CommandExecutorSQLAbstract`, `CommandExecutorSQL{Default,}Factory`,
>   `DynamicSQLElementFactory`, `ReturnHandler` family) added to
>   in-track inventory as a multi-target partial+full cluster.
> - Exception-type pins (`InternalErrorException`,
>   `LiveQueryInterruptedException`, `ManualIndexesAreProhibited`,
>   `RetryQueryException`) classified per PSI: `InternalErrorException`
>   is **alive** (6 prod refs in `AbstractStorage.java`) → pin-
>   maintenance bucket; the other three → 22c-defer per the policy
>   rule "exception type that may be caught by external code".
> - A5 CHANGELOG hook dropped: `BinaryToken` is not api-reachable
>   per PSI (it lives in `internal.core.metadata.security.binary`,
>   no api/ class references it); `CHANGELOG.md` does not exist at
>   the repo root. Reactivate A5 if 22c (or a later track) targets
>   an api/-reachable surface.
> - Cluster-commit shape rule broadened: each commit deletes ALL
>   test files referencing the deleted production surface, not just
>   `*DeadCodeTest.java` files (see "How" below).
> - 71 `*DeadCodeTest.java` pin files on disk; iter-1 surfaced
>   ~25–30 unclassified pins. Phase B's first step (Step 0) MUST
>   produce a complete pin-to-disposition table covering all 71
>   pins before any deletion commit lands.

**What** (deletion clusters — corrected classifications subject to
per-cluster PSI safe-delete confirmation by the Phase B implementer):

- `sbtree/singlevalue/v1` (legacy V1 single-value B-tree). Only
  `*DeadCodeTest.java` consumers (clean per PSI). Pins:
  `CellBTreeBucketSingleValueV1DeadCodeTest`,
  `CellBTreeSingleValueEntryPointV1DeadCodeTest`.
- `DecimalKeyNormalizer` dead helpers.
- Binary Token / JWT cluster (8 classes — Track 17 absorption
  block): the binary-token quintet (`BinaryToken`,
  `BinaryTokenSerializer`, `BinaryTokenPayloadImpl`,
  `BinaryTokenPayloadDeserializer`, `DistributedBinaryTokenPayload`)
  plus the JWT trio (`JsonWebToken`, `JwtPayload`,
  `YouTrackDBJwtHeader`). PSI iter-1: 0 cross-module refs, 0 IT.java
  refs, intra-cluster only. NOT api-reachable (lives in
  `internal.core.metadata.security.{binary, jwt}`).
- Kerberos credential / Krb5 login module dead code.
- `ZIPCompressionUtil` (mechanical fix R2 from previous Phase A
  iter-1: PSI find-usages reports zero callers across `core/`,
  `server/`, `driver/`, `embedded/`, `tests/`).
- Narrow singletons (corrected per iter-1):
  `IndexConfigPropertyDeadCodeTest` → `IndexConfigProperty`;
  `RecordBytesTestOnlyOverloadTest` → test-only overload;
  `IndexCursorClusterDeadCodeTest`; `EntityLinkSetImplTest` partial
  dead methods only (note: pin lives in regular `*Test.java`, not a
  `*DeadCodeTest`; Phase B implementer enumerates dead method names
  via PSI and cross-checks against the 9 referencing test files
  before deletion).
  - **Removed from this bullet:** `MockSerializerDeadCodeTest` /
    `MockSerializer` (moved to 22c-defer per R6 — see Defer list).
  - **Reclassified:** `CronExpressionDeadCodeTest` /
    `CronExpression.getTimeZone()` is a partial-method-body trim,
    not a method deletion: the method has 7 production self-refs and
    1 live `CronExpressionTest` caller; only the lazy-fallback `if`
    branch inside the method is dead. Phase B implementer enumerates
    the file:line of the dead branch via PSI before editing. The
    `setTimeZone(TimeZone)` setter stays live regardless.
- Iter-1 T2 reclassifications (previous Phase A iter-1 finding T2
  reclassified these from "Defer" to "In-track"):
  `LiveQueryBatchResultListener`,
  `DatabaseLifecycleListenerAbstract`, `DatabaseRepair`,
  `BonsaiTreeRepair`, `HookReplacedRecordThreadLocal`. PSI iter-1
  spot-checked: all 5 have zero non-test, non-self callers.
- Track-9 forwarded command-script clusters (widened per R2/T8):
  `CommandExecutorScript` (719 LOC), `CommandScript` (114 LOC),
  `CommandManager`'s class-based legacy dispatch cluster
  (`commandReqExecMap` + `configCallbacks` +
  `registerExecutor(Class,Class,...)` + `unregisterExecutor(Class)`
  + `getExecutor(CommandRequestInternal)`),
  `ScriptExecutorRegister` SPI **plus its consumer references in
  `ScriptManager.java` (line 29 import + line 145
  `ClassLoaderHelper.lookupProviderWithYouTrackDBClassLoader(...)`
  loop)**, `DatabaseScriptManager` (full deletion — dead intra-
  cluster consumer), `CommandExecutorUtility` (full deletion — dead
  intra-cluster consumer), deprecated `ScriptManager.bind(...)` /
  `bindLegacyDatabaseAndUtil` + `ScriptDocumentDatabaseWrapper` +
  `ScriptYouTrackDbWrapper`, `SQLScriptEngine.eval(Reader, Bindings)`.
  - **Reclassified per T2:** `ScriptInterceptor` and
    `ScriptInjection` are **fully dead interfaces** (zero impls per
    PSI; zero `META-INF/services` registrations). Delete the
    interfaces outright + the test-only inner-class impls
    (`ScriptManagerTest.CountingInjection`,
    `SPIWiringSmokeTest.RecordingInjection`). Separately, partial-
    class-trim `ScriptManager.{registerInterceptor,
    unregisterInterceptor, registerInjection, unregisterInjection}`
    (the consumer's dead register loops). Both edits land in the
    same Track-9 cluster commit (they share PSI-confirmed dead-
    surface coupling).
- Track-10 forwarded clusters (corrected per A1/A4/R3):
  - **`core/query/live/` — partial cluster, NOT whole package.** Delete
    the dead static methods on `LiveQueryHookV2`, the
    `LiveQueryHookStaticApi` reference (verify name with PSI — A8
    flagged this as phantom; likely the public-static method group
    inside `LiveQueryHookV2` itself), and three orphan listener
    interfaces in `core/query/`. **Keep:** `LiveQueryHookV2.unboxRidbags`
    (live caller in `CopyRecordContentBeforeUpdateStep.java:52`),
    `LiveQueryHookV2.LiveQueryOp` and `LiveQueryHookV2.LiveQueryOps`
    inner types (referenced by `LiveQueryListenerV2`,
    `LiveQueryQueueThreadV2`, `core/db/SharedContext.java`),
    `LiveQueryListenerV2`, `LiveQueryQueueThreadV2`. Phase B
    implementer runs PSI per-method safe-delete to enumerate the
    dead-method set before drafting the commit.
  - **`core/fetch/` — partial-class-trim, NOT whole package.** Keep
    `FetchHelper`, `FetchPlan`, `FetchListener`, `FetchContext`,
    `RemoteFetchContext`, `RemoteFetchListener` (live class:
    `DepthFetchPlanTest` exercises `FetchHelper.fetch(7-param)`,
    `FetchHelper.buildFetchPlan(1-param)`, `FetchHelper.DEFAULT`,
    plus imports `FetchContext`, `RemoteFetchContext`,
    `RemoteFetchListener`). Delete only PSI-confirmed-dead methods
    inside these classes plus any helper classes in `core/fetch/`
    with zero callers. Phase B implementer enumerates the dead-method
    set per PSI.
- Track-11 forwarded clusters: `CronExpression.getTimeZone()` lazy-
  fallback BRANCH only (see narrow-singletons reclassification
  above); deprecated `Scheduler.{load, close, create}` interface
  methods + their three `SchedulerProxy` overrides.
- Track-12 forwarded deletion clusters (corrected per A3/R4/T4 —
  surfaces (a) and (b) MOVED to 22c-defer):
  - `(c)` `JSONWriter` (511 LOC, zero callers — PSI iter-1: 43 refs
    total, all self-ref or `JSONWriterDeadCodeTest`).
  - `(d)` `Streamable` interface + `StreamableHelper` (176 LOC, zero
    production implementors per PSI iter-1).
  - `(e)` `SerializationThreadLocal` listener / shutdown path
    (54 LOC).
  - `(a)` and `(b)` `RecordSerializerCSVAbstract` /
    `RecordSerializerStringAbstract` are **moved to 22c-defer**
    (abstract bases with live production consumers — see Defer list
    below for the evidence chain).
- Track-13 forwarded deletion clusters: `SerializableWrapper`,
  `RecordSerializationDebug`, `RecordSerializationDebugProperty`.
  (`MockSerializer` removed — moved to 22c-defer per R6.)
- **NEW — SQL root scaffold cluster (T5).** `SqlRootDeadCodeTest`
  pins a multi-target cluster: full deletion of
  `CommandExecutorSQLFactory` + `DefaultCommandExecutorSQLFactory`
  (factory pair with hardcoded-empty map) and the `ReturnHandler`
  family (`OriginalRecordsReturnHandler`,
  `UpdatedRecordsReturnHandler`, `RecordCountHandler`,
  `RecordsReturnHandler`). Partial-class-trim of
  `CommandExecutorSQLAbstract` (preserve the static prefix
  constants consumed by `SQLTarget`, drop the dead scaffold) and
  `DynamicSQLElementFactory` (drop instance methods, keep the
  static maps wired into `SQLEngine`). The cluster's PSI safe-
  delete pass classifies each member.
- Track-22a-Phase-C forwarded deletion candidate (corrected per
  A2/R1/T1): `BasicCommandContext.copy()` only — narrowed.
  - PSI evidence (Track 22a Phase C + iter-1 confirmation):
    `copy()` has 4 refs total, all in `BasicCommandContextStandaloneTest`.
    Deletion safe within an atomic commit IF and only if the
    interface declaration is also removed.
  - **Cluster commit deletes:** `BasicCommandContext.copy()` body +
    `CommandContext.copy()` interface declaration (interface lives
    in `internal.core.command`, not in `api/` — internal interface)
    + the `BasicCommandContextStandaloneTest.copy*` test methods
    (the F9 pin from 22a Step 7). **Delete only the `copy*` test
    methods within `BasicCommandContextStandaloneTest.java`, NOT
    the whole file** — the file's `setChild` / `getChild`
    assertions are live shape pins for the surviving
    `setChild` / `getChild` semantics and stay in place.
  - **Cluster commit DOES NOT delete:** `setChild` / `getChild`.
    PSI confirms 8 live callers in `BasicCommandContextTest.java`
    (test methods including `testGetVariableWithDotPathParent`,
    `testSetVariableExistingInParent`,
    `testRootDotPathVariableResolution`,
    `testParentDotPathEntityHelperReturnsNullForUnknownField`).
    These tests exercise live `$PARENT.<var>` resolution semantics
    backed by `setChild`/`getChild` + `getVariable`. Both methods
    are LIVE.
  - Subclass impact: `BasicServerCommandContext` and `TraverseContext`
    extend `BasicCommandContext` and inherit `copy()` unchanged;
    PSI confirms neither overrides `copy()` independently. Removing
    the inherited method is safe.

**What** (Defer to 22c — SPI / abstract-base / live-consumer / wire-
contract risk; YTDB issue created and marker rewritten in 22c,
production source untouched in this branch):

- Hooks cluster: `RecordHookAbstract`, `EntityHookAbstract`
  (PSI: 9 inheritors including 7 anonymous), `LiveQueryHookV2`
  whole-class deletion (kept partially in-track per A4 — see
  Track-10 forwarded above), plus any T2-reclassified items where
  Phase B's PSI re-confirmation shows SPI risk.
- Database-pool cluster: `DatabasePoolAbstract`, `DatabasePoolBase`.
- Database-tool cluster: `DatabaseCompare`, `GraphRepair`,
  `CheckIndexTool` (the others — `DatabaseRepair`, `BonsaiTreeRepair`
  — were T2-reclassified to in-track-22b above).
- Command-script SPI partial-trim residue: `ScriptInterceptor` /
  `ScriptInjection` (now classified as **in-track full deletion**
  per T2 — see Track-9 forwarded above; this defer entry retired).
- Serializer-base abstract classes (corrected per A3/R4/T4):
  `RecordSerializerStringAbstract` (155 PSI refs, 4 cross-package
  production consumers: `EntityHelper`, `CommandRequestTextAbstract`,
  `SQLHelper`, `StringSerializerHelper`), `RecordSerializerCSVAbstract`
  (33 PSI refs, 2 cross-package consumers: `SQLHelper`,
  `StringSerializerHelper`). Live test consumers
  (`EntitySchemalessSerializationTest`, `SQLHelperParseValueScalarTest`)
  also reference them. Specific dead methods within these abstracts
  may be partial-class-trimmed in 22c after the wider review pass.
- **NEW — `sbtree/local/v1` (legacy V1 local B-tree)** (per A5).
  Live tests `SBTreeLeafBucketV1Test`, `SBTreeNonLeafBucketV1Test`,
  `SBTreeNullBucketV1Test` exist (no DeadCode/WHEN-FIXED/Track 22
  markers; actively exercise the production classes). Defer the
  entire package; alternative considered (delete live tests in
  same commit) was rejected because tests assert real bucket
  semantics that production code paths still exercise via the V1
  SPI.
- **NEW — `MockSerializer` + `BinarySerializerFactory` id `-10`
  registration** (per R6). `BinarySerializerFactory.java:111`
  comment "used for spatial indexes". The lucene module is build-
  excluded but kept as reference code (CLAUDE.md tip 8). The slot
  is an intentional API contract for spatial-index extensibility.
  Defer to 22c with a YTDB issue noting the lucene-reactivation
  question.
- **NEW — `CollectionSelectionStrategy` cluster** (per T4). Three
  pins on disk: `BalancedCollectionSelectionStrategyDeadCodeTest`,
  `DefaultCollectionSelectionStrategyDeadCodeTest`,
  `CollectionSelectionFactoryDeadCodeTest`. The two strategy
  classes are registered in
  `core/src/main/resources/META-INF/services/com.jetbrains.youtrackdb.internal.core.metadata.schema.CollectionSelectionStrategy`
  alongside live sibling `RoundRobinCollectionSelectionStrategy`.
  External consumers may use `ServiceLoader` to enumerate
  strategies. Defer.
- **NEW — Exception-type pins** (per T6/T7). PSI policy ("exception
  type that may be caught by external code") routes these to 22c:
  - `LiveQueryInterruptedExceptionDeadCodeTest`,
    `ManualIndexesAreProhibitedDeadCodeTest`,
    `RetryQueryExceptionDeadCodeTest`. Defer.
  - `InternalErrorExceptionDeadCodeTest` — the production class is
    **alive** (6 production refs in
    `core/src/main/java/.../storage/impl/local/AbstractStorage.java`).
    This is **pin-maintenance**, not deletion — see Pin-maintenance
    bucket below.

### Pin-maintenance bucket (per T6)

`*DeadCodeTest.java` pins where PSI shows the production target is
ALIVE despite the pin's name. Action: rename the pin to drop the
`DeadCode` suffix (becoming a regular shape pin), or delete the
pin if its assertions are stale. Phase B implementer enumerates
the full list during the pin-disposition table (Step 0) — iter-1
identified `InternalErrorExceptionDeadCodeTest`; Phase B's PSI
sweep across the unclassified-pin set (per T7) likely surfaces a
few siblings.

**How** (corrected per iter-1):
- **Step 0 — Phase B's first step: complete pin-to-disposition
  table.** Enumerate all 71 `*DeadCodeTest.java` files on disk;
  for each, run PSI find-usages on the pinned production target
  and assign one of three dispositions: (a) `delete-in-22b
  cluster: <name>`, (b) `defer-to-22c`, (c) `pin-maintenance`.
  Write the table to `docs/adr/unit-test-coverage/_workflow/cluster-disposition.md`.
  This artifact also serves as the 22b → 22c handoff filter (per
  R9) — 22c reads the table to determine which WHEN-FIXED markers
  need YTDB issues. No deletion commit lands until the table is
  complete.
- **PSI safe-delete classification per cluster.** For each cluster,
  run the `mcp-steroid://ide/safe-delete` recipe against the
  production class/method/package the cluster pins. Grep is NOT
  acceptable for this classification — a missed external consumer
  (especially in abstract base classes or SPIs) would corrupt the
  deletion claim.
- **Hybrid policy** (full text under "Dead-code deletion policy"
  below, ported from previous Phase A iter-1 clarifications):
  Cluster meets ALL of: zero production callers (PSI), not part of
  `com.jetbrains.youtrackdb.api`, not an abstract base / SPI service /
  exception type, deletion does not require coordinated `server`/
  `tests`/`embedded` changes → **delete in 22b**. Otherwise → **defer
  to 22c**.
- **PSI reclassification escalation rule** (per A9): if Phase B's
  PSI safe-delete pass moves more than 2 additional clusters from
  in-track to 22c-defer beyond the iter-1 corrections above, OR if
  the in-track cluster count exceeds 12 commits, escalate to
  inline-replan to widen 22c's step budget. Do NOT pack-and-proceed
  past these thresholds.
- **Strong-Candidate `*IT.java` reference check** (mechanical fix A8
  from previous Phase A iter-1): in addition to `core/src/test/`,
  PSI find-usages MUST also cover `*IT.java` files in `core/`,
  `server/`, and the `tests/` module — integration-test references
  to dead code do exist in this codebase and must be confirmed
  absent before deletion.
- **Partial-class-trim tier** (mechanical fix R4): when a class has
  both live and dead methods (e.g., `EntityLinkSetImpl` partial dead
  methods, the abstract serializer bases per Phase B's secondary
  pass, `BasicCommandContext.copy()` + `CommandContext.copy()` from
  the interface), delete only the dead methods + their pins; keep
  the class. The atomic commit deletes the dead methods + the
  `*DeadCodeTest.java` test methods that pinned them + any live-
  named test methods that exercised them; live methods and their
  tests remain unchanged.
- **Per-cluster commit shape** (broadened per A7/T11): one step =
  one cluster = one commit. The commit deletes:
  - the production class/method(s);
  - the `*DeadCodeTest.java` shape pin (or its method subset for
    partial-class-trim);
  - **ALL test files that reference the deleted production
    surface, regardless of filename suffix** (illustrative in-track
    cases: `LiveQueryHookStaticApiTest` if static-method group
    inside `LiveQueryHookV2` is trimmed; `DepthFetchPlanTest` if
    its consumed dead-method subset of `FetchHelper` is targeted;
    `BasicCommandContextStandaloneTest` F9 pin from 22a — for
    the BasicCommandContext cluster, **only the `copy*` test
    methods**, not the whole file; `CronExpressionTest:39` Javadoc
    `{@link}` if the cluster touches the linked surface;
    `BasicCommandContextTest` is **excluded** from cluster scope
    per the iter-1 correction). Note: serializer-base consumer
    tests (`RecordSerializerStringAbstractSimpleValueTest`,
    `RecordSerializerCsvAbstractEmbeddedMapTest`) become relevant
    only if the abstract-base partial-trim work moves back from
    22c-defer to in-track in a future iteration; today they are
    not in 22b's commit scope. Phase B implementer enumerates the
    full consumer-test set via PSI find-usages per cluster — NOT
    by filename glob;
  - any `META-INF/services` entries the cluster registered (T1);
  - any Javadoc `{@link}` references to deleted methods (T9 — e.g.,
    `CronExpressionTest.java:39` references
    `CronExpression#getTimeZone()` if that branch trim affects the
    method signature).
  Each commit is independently bisectable.
- **CHANGELOG / release-note convention (A5).** Disabled for 22b
  per iter-1 (T3/R8): the post-corrections cluster inventory has
  no api/-reachable deletion target, and `CHANGELOG.md` does not
  exist at the repo root. Reactivate A5 in 22c (or a later track)
  if a deletion alters a wire format documented for external
  clients, removes a class wired into a public registry, or changes
  a `GlobalConfiguration` key the user can set.
- **Coverage-gate recompute (final-only per T10).** The verification
  step at the end of 22b runs `./mvnw clean package -P coverage`
  once and `coverage-analyzer.py` once to refresh per-package
  baselines and update `coverage-baseline.md`. Per-cluster commits
  do NOT trigger coverage rebuilds — bisectability holds without
  them, and per-commit rebuilds (~3–5 min × 9–11 commits = ~30–55
  min) are wasteful.
- **License-header normalization — separate end-of-track commit**
  (per R7/A10). One mechanical commit at the end of 22b normalizes
  the canonical 13-line license header across the surviving
  CQ7/TS6 file inventory (~10 test files in `cache/`, `collate/`,
  `conflict/`, `id/`, `type/`, `dictionary/`, `compression/`, plus
  tx/Gremlin tests). Files deleted by 22b cluster commits are
  skipped (already gone). This preserves "one cluster, one commit"
  bisectability for the deletion commits.
- **22b → 22c handoff artifact** (per R9). The `cluster-
  disposition.md` table written in Step 0 (and updated by each
  cluster commit to mark the cluster as `deleted` once the commit
  lands) is committed at the end of 22b as the final cluster-
  disposition list. 22c reads this artifact to filter which
  WHEN-FIXED markers still need YTDB issues.
- **22a anchor pre-flight** (per R11). Phase B implementer's first
  read on `BinarySerializerFactory.java` confirms the line ~111
  `MockSerializer.INSTANCE` registration anchor still holds after
  Track 22a's commit `03f75c3ffa` (NullSerializer.INSTANCE swap).
  Since MockSerializer is now 22c-defer, this anchor only matters
  for Phase B's pin-disposition Step 0; no commit-level
  coordination needed.
- **No-issue convention for in-track deletions** (carried from
  previous Pre-Flight clarifications): when a cluster is deleted in
  this branch, no YTDB issue is created — the deletion itself is the
  resolution. Avoid issue churn for resolved-in-this-PR deletions.

**Constraints**:
- In-scope: only the production classes/methods + their
  `*DeadCodeTest.java` shape pins + their `META-INF/services` entries
  + non-`*DeadCodeTest` consumer tests confined to the deleted
  surface (per the broadened cluster-commit shape rule). No new
  tests added in 22b (test-additive coverage is 22a's scope); the
  cluster's surviving live methods stay covered by 22a's tests.
- Out-of-scope: any cluster whose PSI safe-delete classification
  returns "Defer" — those flow to 22c.
- **TinkerPop fork-shadowing forbid in new test files** (mechanical
  fix R7): inherited from 22a — applies to any test additions that
  slip into 22b (should be zero, but if a partial-class-trim case
  needs a small test surface adjustment, the constraint applies).
- **Per-test UUID DB name** (mechanical fix T5): inherited from 22a
  — applies to any test surface adjustments.

**Interactions**:
- Depends on Track 1 (coverage measurement infrastructure).
- Depends on Track 22a (consumes 22a's PSI safe-delete confirmations
  from Phase A adversarial review and the post-22a coverage baseline
  that 22b's per-cluster verification compares against).
- Feeds 22c the final cluster-disposition list via
  `cluster-disposition.md` (which clusters are in-track-deleted vs
  deferred vs pin-maintenance) — explicit artifact per R9.

### Dead-code deletion policy (full text)

**Hybrid (cluster-by-cluster).** 22b's Phase B PSI safe-delete pass
classifies each dead-code cluster (the ~71 `*DeadCodeTest.java`
files mapping to ~15–20 logical clusters) into one of three
dispositions, using **PSI find-usages via `mcp-steroid` and the
`mcp-steroid://ide/safe-delete` recipe** against the production
class/method/package the cluster pins — grep is not acceptable for
this classification because a missed external consumer (especially
in abstract base classes or SPIs) would corrupt the deletion claim.

**Deletion-in-22b (cluster meets ALL of these):**
- PSI find-usages reports zero production callers (across this
  repo, including `*IT.java` files per A8).
- The class/method is not part of `com.jetbrains.youtrackdb.api`
  (the public-API surface) — no `internal/api` package boundary
  crossed.
- The class is not an abstract base class designed for
  subclassing, an SPI service interface (`META-INF/services`
  registered), or an exception type that may be caught by
  external code.
- Deletion does not require coordinated changes in the `server`,
  `tests`, or `embedded` modules beyond the `core` test source.

**Issue-only-defer-to-22c (cluster fails ANY of the above):**
- YTDB issue created with full deletion plan and consumer-search
  notes (in 22c).
- WHEN-FIXED marker rewritten from `Track 22` to `YTDB-NNNN`
  (in 22c).
- Production source untouched in this branch; deletion happens in
  a dedicated follow-up PR with wider review.

**Pin-maintenance (PSI shows production target is ALIVE despite
pin name)** — added per T6:
- Rename the pin to drop the `DeadCode` suffix (becoming a regular
  shape pin) or delete the pin if its assertions are stale.
- No production code change; no YTDB issue.
- Identified by 22b's pin-disposition table (Step 0).

**Partial-class-trim** (mechanical fix R4): when a class has both
live and dead methods, delete only the dead methods + their pins;
keep the class. Treated as a deletion-in-22b case for the dead
subset; the live subset stays covered by 22a's tests.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

- [x] Technical: PASS at iteration 2 (12 findings — 1 blocker [T2], 7 should-fix [T1, T3–T8], 4 suggestions [T9–T12]; 12 accepted, 0 rejected, 0 deferred; iter-2 gate VERIFIED 12/12 with PSI re-checks against the corrected step file)
- [x] Risk: PASS at iteration 2 (11 findings — 1 blocker [R1], 6 should-fix [R2–R7], 4 suggestions [R8–R11]; 11 accepted, 0 rejected, 0 deferred; iter-2 gate VERIFIED 11/11; 6 PSI spot-checks reproduced exact iter-1 ref counts)
- [x] Adversarial: PASS at iteration 2 (11 findings — 5 blockers [A1–A5], 4 should-fix [A6–A9], 2 suggestions [A10, A11]; 11 accepted, 0 rejected, 0 deferred; iter-2 gate VERIFIED 11/11 plus 2 new suggestion-level findings [NF1: stale commit-shape examples, NF2: Standalone test scope clarification] absorbed into step file)

The iter-1 findings drove a major cluster re-baselining captured
throughout the corrected `## Description`. Convergent blockers
across the three reviewers — `BasicCommandContext.copy()` partial-
trim narrowing (R1/A2/T1), serializer-abstract-base defer
(A3/R4/T4), `core/query/live/` and `core/fetch/` partial-scope
(A1/A4/R3), `sbtree` split (A5), and `ScriptInterceptor`/
`ScriptInjection` full-deletion reclassification (T2) — all carried
PSI evidence chains that translated directly into mechanical step-
file edits. No findings escalated to inline-replan. Two non-blocking
new findings (NF1, NF2) absorbed in the iter-2 fix pass.

## Steps

- [ ] Step 0: Pin-disposition table + 22a anchor pre-flight
  > **Risk:** medium — multi-file PSI gating work (no production
  > code change, but downstream cluster commits depend on this
  > artifact's correctness). Phase B's first step. Enumerate all 71
  > `*DeadCodeTest.java` files on disk; for each, run PSI find-usages
  > on the pinned production target via `mcp-steroid://ide/safe-delete`
  > + `mcp-steroid://ide/find-usages` and assign one of three
  > dispositions: `delete-in-22b cluster: <name>`, `defer-to-22c`,
  > or `pin-maintenance`. Cross-cover `*IT.java` files in `core/`,
  > `server/`, and `tests/` per A8. Write the table to
  > `docs/adr/unit-test-coverage/_workflow/cluster-disposition.md`.
  > Also confirm `core/.../BinarySerializerFactory.java:111`
  > `MockSerializer.INSTANCE` registration anchor still holds after
  > Track 22a's commit `03f75c3ffa` (per R11). No deletion commit
  > lands until this artifact is complete and committed. Re-run
  > the iter-1 escalation thresholds: if PSI surfaces &gt;2 additional
  > clusters needing 22c-defer beyond the iter-1 corrections, OR
  > the in-track cluster count exceeds 12, escalate to inline-replan.

- [ ] Step 1: Delete Binary Token / JWT cluster (8 classes)
  > **Risk:** medium — override from HIGH-security category.
  > Justification: PSI iter-1 confirms zero cross-module refs and
  > zero `*IT.java` refs across `core/`, `server/`, `embedded/`,
  > `driver/`, `tests/`; cluster lives in
  > `internal.core.metadata.security.{binary, jwt}` and is NOT
  > api-reachable; classes are inert per Track 17 episode. Cluster
  > commit deletes the 8 production classes (`BinaryToken`,
  > `BinaryTokenSerializer`, `BinaryTokenPayloadImpl`,
  > `BinaryTokenPayloadDeserializer`, `DistributedBinaryTokenPayload`,
  > `JsonWebToken`, `JwtPayload`, `YouTrackDBJwtHeader`) plus their
  > 8 `*DeadCodeTest.java` shape pins.

- [ ] Step 2: Delete `sbtree/singlevalue/v1` cluster
  > **Risk:** medium — override from HIGH-storage category.
  > Justification: PSI iter-1 confirms only `*DeadCodeTest.java`
  > consumers; no live storage path touches `CellBTreeBucketSingleValueV1`
  > or `CellBTreeSingleValueEntryPointV1`. Cluster commit deletes
  > the 2 production classes plus their 2 shape pins
  > (`CellBTreeBucketSingleValueV1DeadCodeTest`,
  > `CellBTreeSingleValueEntryPointV1DeadCodeTest`).

- [ ] Step 3: Delete misc small dead helpers (ZIPCompressionUtil + DecimalKeyNormalizer + Kerberos/Krb5)
  > **Risk:** medium — override from HIGH-security category for
  > Kerberos; pack rationale: 3 narrow unrelated clusters
  > (controlled exception extension of "two narrow" packing rule
  > in backlog). Each cluster <100 LOC, independently safe-
  > deletable per PSI iter-1, bisectability preserved (unrelated
  > deletions don't depend on each other). `ZIPCompressionUtil`:
  > zero callers across all modules. `DecimalKeyNormalizer` dead
  > helpers: zero callers per Track 18 absorption episode. Kerberos
  > / Krb5 (`KerberosCredentialInterceptor`,
  > `Krb5ClientLoginModuleConfig`): zero live security path per PSI;
  > `META-INF/services` registrations (if any) included in commit.

- [ ] Step 4: Delete narrow singletons batch (5 sub-targets)
  > **Risk:** medium — mixed nature: partial-method-body trim for
  > `CronExpression.getTimeZone()` lazy-fallback `if` BRANCH
  > requires PSI per-line precision (Phase B implementer enumerates
  > the dead branch's file:line via `mcp-steroid://ide/find-usages`
  > before editing); partial-class-trim of `EntityLinkSetImpl`
  > requires PSI cross-check against 9 referencing test files
  > before deletion (the pin lives in regular `EntityLinkSetImplTest.java`,
  > not a `*DeadCodeTest`). Targets: `IndexConfigProperty` (full
  > delete + `IndexConfigPropertyDeadCodeTest` pin), `RecordBytes`
  > test-only overload (single-method delete + `RecordBytesTestOnlyOverloadTest`),
  > `IndexCursorCluster` (full delete + `IndexCursorClusterDeadCodeTest`),
  > `EntityLinkSetImpl` partial dead methods (subset of methods +
  > subset of `EntityLinkSetImplTest` test methods),
  > `CronExpression.getTimeZone()` lazy-fallback BRANCH only (NOT
  > the method itself — method has 7 production self-refs and 1
  > live `CronExpressionTest` caller; method signature retained;
  > `setTimeZone(TimeZone)` setter stays live; strip the
  > `CronExpressionTest.java:39` Javadoc `{@link}` if branch trim
  > affects the method signature per T9). The
  > `CronExpressionDeadCodeTest` pin's branch-asserting test methods
  > are removed in lockstep.

- [ ] Step 5: Delete T2 reclassifications batch (5 small classes)
  > **Risk:** low — 5 simple full-class deletions, each PSI-
  > confirmed zero non-test refs. Targets: `LiveQueryBatchResultListener`,
  > `DatabaseLifecycleListenerAbstract` (zero implementers per PSI;
  > only test-only `NoOpListener` inner class consumed by the pin),
  > `DatabaseRepair`, `BonsaiTreeRepair`, `HookReplacedRecordThreadLocal`.
  > Cluster commit deletes the 5 production classes plus their 5
  > `*DeadCodeTest.java` shape pins (`LiveQueryBatchResultListenerDeadCodeTest`,
  > `DatabaseLifecycleListenerAbstractDeadCodeTest`,
  > `DatabaseRepairDeadCodeTest`, `BonsaiTreeRepairDeadCodeTest`,
  > `HookReplacedRecordThreadLocalDeadCodeTest`).

- [ ] Step 6: Delete Track-9 forwarded command-script cluster
  > **Risk:** medium — override from HIGH-architecture category
  > (modifies SPI loading via `ClassLoaderHelper.lookupProviderWithYouTrackDBClassLoader`
  > removal). Justification: the SPI being unloaded
  > (`ScriptExecutorRegister`) has zero `META-INF/services`
  > registrations and zero consumers per PSI; the consumer trim
  > affects only the dead loop in `ScriptManager.java:145` — no
  > live SPI behavior change. Large surface (~9 production files,
  > intra-cluster coupling). Cluster commit deletes:
  > `CommandExecutorScript` (719 LOC), `CommandScript` (114 LOC),
  > `CommandManager`'s class-based dispatch cluster
  > (`commandReqExecMap` + `configCallbacks` +
  > `registerExecutor(Class,Class,...)` + `unregisterExecutor(Class)`
  > + `getExecutor(CommandRequestInternal)`),
  > `ScriptExecutorRegister` SPI interface,
  > `ScriptManager.java:29` import + line 145 lookup loop,
  > `DatabaseScriptManager` (full deletion — dead intra-cluster
  > consumer), `CommandExecutorUtility` (full deletion — dead
  > intra-cluster consumer), deprecated `ScriptManager.bind(...)`
  > / `bindLegacyDatabaseAndUtil` + `ScriptDocumentDatabaseWrapper`
  > + `ScriptYouTrackDbWrapper`, `SQLScriptEngine.eval(Reader,
  > Bindings)`. **Plus** `ScriptInterceptor` and `ScriptInjection`
  > **fully-dead interface deletion** (zero impls + zero services
  > registrations per PSI; the test-only `ScriptManagerTest.CountingInjection`
  > and `SPIWiringSmokeTest.RecordingInjection` inner classes
  > deleted in lockstep). **Plus** `ScriptManager.{registerInjection,
  > unregisterInjection, getInjections}` consumer partial-trim
  > (the `ScriptInterceptor` register/unregister consumer methods
  > may live on `ScriptExecutor` / `AbstractScriptExecutor` /
  > `CommandManagerTest` per Phase A iter-2 implementer note —
  > Phase B's PSI safe-delete pass on the dead interfaces enumerates
  > the exact consumer-trim sites). All `*DeadCodeTest.java` pins
  > (`CommandScriptDeadCodeTest` and any siblings) deleted in
  > lockstep.

- [ ] Step 7: Delete Track-10 forwarded `core/query/live/` partial cluster
  > **Risk:** medium — partial-scope deletion across a package with
  > preserved live consumers (`SharedContext.java`,
  > `LiveQueryListenerV2`, `LiveQueryQueueThreadV2`,
  > `CopyRecordContentBeforeUpdateStep.java`). Phase B implementer
  > runs `mcp-steroid://ide/safe-delete` per-method on
  > `LiveQueryHookV2` to enumerate the dead static-method set
  > (likely the `LiveQueryHookStaticApi`-named group per A8
  > naming-verification). Keep: `LiveQueryHookV2.unboxRidbags`,
  > `LiveQueryHookV2.LiveQueryOp`, `LiveQueryHookV2.LiveQueryOps`,
  > `LiveQueryListenerV2`, `LiveQueryQueueThreadV2`. Delete: dead
  > static methods on `LiveQueryHookV2` (Phase B enumerates) plus
  > the three orphan listener interfaces in `core/query/` (Phase B
  > enumerates via PSI safe-delete on `core/query/` package
  > contents). Pin removals: `LiveQueryDeadCodeTest`'s method
  > subset corresponding to the deleted statics; `LiveQueryHookStaticApiTest`
  > if it pins solely the deleted statics.

- [ ] Step 8: Delete Track-10 forwarded `core/fetch/` partial cluster
  > **Risk:** medium — partial-scope deletion across a package with
  > preserved live consumer (`DepthFetchPlanTest` exercises live
  > branches of `FetchHelper.fetch(7-param)`,
  > `FetchHelper.buildFetchPlan(1-param)`, `FetchHelper.DEFAULT`,
  > and consumes `FetchContext`, `RemoteFetchContext`,
  > `RemoteFetchListener`). Phase B implementer runs
  > `mcp-steroid://ide/safe-delete` per-method on `FetchHelper` and
  > each `core/fetch/` helper class to enumerate the dead-method
  > set. Keep: `FetchHelper`, `FetchPlan`, `FetchListener`,
  > `FetchContext`, `RemoteFetchContext`, `RemoteFetchListener`.
  > Delete: PSI-confirmed-dead methods inside these classes plus
  > any `core/fetch/` helper classes with zero callers. Pin
  > removal: `FetchHelperDeadCodeTest`'s method subset corresponding
  > to deleted methods.

- [ ] Step 9: Delete Tracks 11+12+13 packed serialization-and-scheduler hygiene (7 small surfaces)
  > **Risk:** medium — override from HIGH-public-API category for
  > `Scheduler.{load,close,create}` interface-method removal.
  > Justification: `Scheduler` is in `internal.core.schedule`, NOT
  > `api/`; the 3 `SchedulerProxy` overrides are themselves deletion
  > targets. Pack rationale: 7 narrow surfaces (≤200 LOC each)
  > across 4 packages — `core/schedule/`, `core/serialization/serializer/`,
  > `core/serialization/serializer/record/`,
  > `core/serialization/serializer/record/binary/` — all PSI-
  > confirmed dead, bisectability preserved (deletions independent
  > of each other). Targets: Scheduler.{load,close,create} interface
  > methods + 3 SchedulerProxy overrides; `JSONWriter` (511 LOC, 43
  > refs all self-ref or DeadCodeTest); `Streamable` interface
  > (zero production implementors per PSI) + `StreamableHelper`
  > (176 LOC); `SerializationThreadLocal` listener / shutdown path
  > (54 LOC); `SerializableWrapper`; `RecordSerializationDebug`;
  > `RecordSerializationDebugProperty`. Pin removals:
  > `JSONWriterDeadCodeTest`, `StreamableInterfaceDeadCodeTest`,
  > `StreamableHelperDeadCodeTest`, `SerializationThreadLocalDeadCodeTest`,
  > `SerializableWrapperDeadCodeTest`,
  > `RecordSerializationDebugDeadCodeTest`,
  > `RecordSerializationDebugPropertyDeadCodeTest`,
  > `SchedulerSurfaceDeadCodeTest` method subset.

- [ ] Step 10: Delete SQL root scaffold cluster
  > **Risk:** medium — multi-class partial+full mix; partial-trim
  > preserves load-bearing static constants/maps consumed by live
  > production code. Full delete: `CommandExecutorSQLFactory`,
  > `DefaultCommandExecutorSQLFactory` (factory pair with hardcoded-
  > empty map), and the `ReturnHandler` family
  > (`OriginalRecordsReturnHandler`, `UpdatedRecordsReturnHandler`,
  > `RecordCountHandler`, `RecordsReturnHandler`). Partial-trim:
  > `CommandExecutorSQLAbstract` (preserve the static prefix
  > constants consumed by `SQLTarget`, drop the dead scaffold
  > methods); `DynamicSQLElementFactory` (drop instance methods,
  > keep the static maps wired into `SQLEngine`). Phase B
  > implementer runs PSI safe-delete on each member to confirm the
  > preserved subset is genuinely live. Pin removal:
  > `SqlRootDeadCodeTest`.

- [ ] Step 11: Delete `BasicCommandContext.copy()` partial-class-trim with `CommandContext.copy()` interface declaration
  > **Risk:** medium — interface-implementation atomicity required
  > across 4 files: `BasicCommandContext.java` (delete
  > `copy()` body), `CommandContext.java` (delete the interface-
  > method declaration), `BasicServerCommandContext.java` and
  > `TraverseContext.java` (subclass impact: PSI confirms neither
  > overrides `copy()` independently; inherited method removal is
  > safe but Phase B implementer re-confirms via PSI find-
  > implementations on `CommandContext.copy()` before drafting the
  > commit). The interface lives in `internal.core.command`, NOT
  > `api/` — internal-only deletion. Cluster commit DOES NOT touch
  > `setChild` / `getChild` (8 live callers in
  > `BasicCommandContextTest.java` per PSI iter-1; live shape pins).
  > Pin removal: only the `copy*` test methods within
  > `BasicCommandContextStandaloneTest.java` (the F9 pin from 22a
  > Step 7) — NOT the whole file (its `setChild`/`getChild`
  > assertions remain live).

- [ ] Step 12: License-header normalization end-of-track commit
  > **Risk:** low — mechanical formatting only; no production code
  > change. Normalize the canonical 13-line license header across
  > the surviving CQ7/TS6 inventory: `core/src/test/.../cache/AbstractMapCacheTest.java`,
  > `core/src/test/.../cache/RecordCacheWeakRefsTest.java`,
  > `core/src/test/.../collate/DefaultCollateFactoryTest.java`,
  > `core/src/test/.../conflict/RecordConflictStrategyFactoryTest.java`,
  > `core/src/test/.../id/RecordIdTest.java`,
  > `core/src/test/.../type/IdentityWrapperTest.java`,
  > `core/src/test/.../dictionary/DictionaryDeadCodeTest.java`,
  > `core/src/test/.../compression/CompressionInterfaceDeadCodeTest.java`,
  > plus tx/Gremlin tests with no header. Files deleted by Steps
  > 1-11 cluster commits are skipped automatically. Run
  > `./mvnw -pl core spotless:apply` after edits.

- [ ] Step 13: Final verification + cluster-disposition.md commit
  > **Risk:** low — mechanical end-of-track housekeeping. Runs
  > `./mvnw -pl core -am clean package -P coverage`, then
  > `python3 .github/scripts/coverage-analyzer.py` (or the equivalent
  > flow used by Track 1) to refresh per-package baselines. Update
  > `docs/adr/unit-test-coverage/_workflow/coverage-baseline.md`
  > with the post-22b numbers (target after corrections: ~82–83%
  > line / ~70–71% branch). Finalize
  > `docs/adr/unit-test-coverage/_workflow/cluster-disposition.md`
  > with each cluster's commit SHA and final disposition (`deleted`
  > / `deferred-to-22c` / `pin-maintenance`); 22c reads this as
  > its filter input. Run `./mvnw -pl core clean test` (and
  > `./mvnw -pl core clean verify -P ci-integration-tests` if any
  > deletion touches integration-test-covered surfaces — Phase B
  > judges per the affected packages) to confirm the suite is green.
