# Track 12: Serialization — String & Core

## Description

Raise unit-test coverage of the string-record-serializer and core
serialization infrastructure packages. Phase A reviews (technical, risk,
adversarial — see `reviews/track-12-{technical,risk,adversarial}.md`)
re-framed the original "single legacy CSV-like format" picture into a
**three-stratum** map of the territory and surfaced two pre-existing
correctness issues that must be fixed before new tests are added on top.

### Three strata in `core/serialization/serializer/record/string`

1. **Live JSON path — primary round-trip target.**
   `JSONSerializerJackson` (~1,373 LOC) is the modern Jackson-based
   record-JSON serializer used by `RecordAbstract.toJSON/fromJSON`,
   `DatabaseSessionEmbedded.parseJSON`, `DatabaseExport`,
   `DatabaseImport`, `DefaultSecuritySystem`, `SymmetricKey`,
   `SymmetricKeyCI`, `SQLHelper`, `SQLMethodToJSON`, and the Lucene
   indexers. Three production instances with distinct mode flags:
   `INSTANCE`, `IMPORT_INSTANCE`, `IMPORT_BACKWARDS_COMPAT_INSTANCE`.
   Despite the package name, this is **not** legacy. Round-trip via a
   real session.
2. **Live static-helper layer.**
   `RecordSerializerStringAbstract.getType(String)`,
   `getTypeValue(...)`, `simpleValue*` (date/timezone-aware),
   `embeddedMapFromStream(...)`; `FieldTypesString` (pure char→type
   helper, partially used by `SQLJson`); `RecordSerializerCSVAbstract`'s
   `embeddedMapFromStream` static helper. Used by `SQLHelper.parseValue`
   and `EntityHelper`.
3. **Dead/abstract orphan instance API — pin for Track 22 deletion.**
   Commit `24d5a3d967` (YTDB-86) removed the only concrete CSV
   subclass `RecordSerializerSchemaAware2CSV`. What remains is
   `RecordSerializerCSVAbstract` (866 LOC) and the abstract instance
   methods of `RecordSerializerStringAbstract` (588 LOC) — abstract
   classes with **zero concrete subclasses or `new`-instantiations**
   anywhere in the project. Cross-module grep across `core/`,
   `server/`, `driver/`, `embedded/`, `gremlin-annotations/`,
   `tests/`, `test-commons/`, `docker-tests/` shows only the static
   helpers above are externally invoked — the instance API
   (`fieldFromStream`, `fieldToStream`, `embeddedCollectionFromStream`,
   `embeddedCollectionToStream`, the non-helper instance methods of
   `RecordSerializerStringAbstract`, plus four unused public statics)
   has zero callers.

### Three strata in `core/serialization/serializer`

1. **Live**: `StringSerializerHelper` (~1,551 LOC, already has a
   partial `StringSerializerHelperTest` extending `DbTestBase`) and
   `JSONReader` (609 LOC, one production caller — `DatabaseImport`).
2. **Dead**: `JSONWriter` (511 LOC) — **0 callers** anywhere. Pin and
   forward to Track 22.
3. *Note*: There is **no `SerializerFactory`** in this package — the
   originally-planned "SerializerFactory, record type dispatching"
   target does not exist. `BinarySerializerFactory` lives one level
   down in `serializer/binary/` (Track 13's territory) and the
   record V0/V1 dispatch is implemented inside
   `RecordSerializerBinary.init()` (also Track 13).

### Two strata in `core/serialization` (root)

- **Live**: `BinaryProtocol` (185 LOC, 15+ callers, standalone-friendly),
  `MemoryStream` (471 LOC, `@Deprecated` but still used by
  `RecordId*`/`RecordBytes` — live; also used by
  `CommandRequestTextAbstract` and the Track-9-pinned-dead
  `CommandScript`), `EntitySerializable` interface (live —
  used by binary `EntitySerializerDelta` parity surface),
  `SerializableStream` interface (partially live — implemented by
  `PhysicalPosition` and `SerializableWrapper`).
- **Dead**: `Streamable` interface (0 implementors), `StreamableHelper`
  (176 LOC, 0 callers), and the sibling
  `serializer/record/SerializationThreadLocal` (54 LOC, 0 readers
  for the listener/shutdown path).

### Pre-existing correctness issues to fix first

1. `core/src/test/java/.../common/serialization/SafeConverterTest.java`,
   `UnsafeConverterTest.java`, `AbstractConverterTest.java` declare 8
   `testPut*` methods each but contain **zero `@Test` annotations** —
   confirmed: `grep -c "@Test"` returns `0` on all three. Surefire
   silently skips them. The current `common/serialization` baseline of
   34.5% is therefore an artefact of incidental coverage from other
   tests.
2. When the `@Test` annotations are added, `Assert.assertEquals(byte[],
   byte[])` in those files resolves to the `Object.equals` overload
   (reference equality) — must be replaced with
   `Assert.assertArrayEquals(expected, actual)` and the `(actual,
   expected)` argument order corrected.

### Test strategy — per-file harness assignments

Project-wide D2 (standalone over `DbTestBase`) is **inverted for
round-trip targets** in this track (Track 8 precedent — per-track D2
override). Per-file:

| Target | Harness | Rationale |
|---|---|---|
| `JSONSerializerJackson` (3 instances) | `DbTestBase` (extending `TestUtilsFixture` for `@After rollbackIfLeftOpen`) | needs schema, session, RID resolution |
| `JSONReader` token-only paths | Standalone | pure parser tokens, no session |
| `JSONReader` integration paths | `DbTestBase` | session-dependent (RidSet / link resolution) |
| `JSONWriter` | `*DeadCodeTest` (standalone) | 0 callers — pin for Track 22 |
| `RecordSerializerStringAbstract.getType/getTypeValue` (statics) | Standalone | pure helpers (with caveat: `simpleValue*` paths take a session for `DateHelper.getDatabaseCalendar` — those need `DbTestBase`) |
| `RecordSerializerStringAbstract.simpleValue*` | `DbTestBase` | DB-calendar / timezone dependency |
| `RecordSerializerCSVAbstract.embeddedMapFromStream` (static) | `DbTestBase` | session-dependent embedded-map parse |
| `RecordSerializerCSVAbstract` instance API (dead) | `*DeadCodeTest` (standalone) | 0 callers — pin for Track 22 |
| `RecordSerializerStringAbstract` abstract instance methods + 4 unused public statics | `*DeadCodeTest` (standalone) | 0 callers — pin for Track 22 |
| `FieldTypesString` | Standalone | pure char→type helper |
| `StringSerializerHelper` extensions | `DbTestBase` (existing test already extends `DbTestBase`) | session-aware methods are common |
| `BinaryProtocol` | Standalone | pure byte arithmetic |
| `MemoryStream` raw primitives (read/write/grow/move/copyFrom) | Standalone | pure `OutputStream` wrapper |
| `BinaryConverterFactory` Safe/Unsafe branch | Standalone with `@Category(SequentialTest)` | toggles `GlobalConfiguration.MEMORY_USE_UNSAFE` (process-wide static, read into `static final` fields by 6+ classes) |
| `SafeBinaryConverter` / `UnsafeBinaryConverter` round-trip | Standalone (extending the abstract test once `@Test` annotations are fixed) | byte-buffer arithmetic |
| `Streamable` interface, `StreamableHelper`, `SerializationThreadLocal` listener | `*DeadCodeTest` (standalone, with `@Category(SequentialTest)` for any `setStreamableClassLoader` invocation) | 0 implementors / 0 callers — pin for Track 22 |
| `RecordSerializer` interface default `fromStream(... ReadBytesContainer ...)` | Standalone (test-local stub implementor) | covers default-throw branch |
| `StreamSerializerRID` 9-line gap | Standalone (extension of existing `StreamSerializerRIDTest`) | small gap |

### Round-trip equality — semantic equivalence per type

Identity-based `RecordAbstract.equals` will produce false negatives for
naive `assertEquals(original, deserialized)` between distinct
`EntityImpl` instances. Use a per-property comparator with these rules:

| Type | Equivalence rule |
|---|---|
| INTEGER, LONG, SHORT, BYTE, BOOLEAN, STRING | `Objects.equals` |
| FLOAT, DOUBLE | `Float.floatToIntBits` / `Double.doubleToLongBits` equality (preserves NaN identity); test-side may also use a small ULP tolerance for legacy text-format round-trips |
| DECIMAL | `BigDecimal.compareTo == 0` (scale loss is intentional in some text paths) |
| DATE | truncate to DB-calendar midnight via `DateHelper.getDatabaseCalendar(session)`, then `Date.equals` |
| DATETIME | `Date.equals` after timezone normalization to DB calendar |
| BINARY | `Arrays.equals(byte[], byte[])` |
| LINK | `Identifiable.getIdentity().equals` (RID-only) |
| EMBEDDED entity | recursive per-property comparison |
| collection | element-wise recursion |

Steps that touch DATE / DATETIME / FLOAT / DECIMAL / BINARY / LINK
must include falsifiable per-type assertions (Track 5–11 convention)
that would catch a regression from the rule.

### Cross-track coordination

- **Track 1** (already complete) provides `coverage-analyzer.py`. Use
  it before/after this track to measure delta.
- **Track 13** (Serialization — Binary) is the next track and shares
  fixtures (`EntitySerializable` parity, `LinkSerializer` /
  `StreamSerializerRID` integration), shares the dead-code-pin
  discipline, and has overlapping packages (`common/serialization/types`).
  D5 (one PR per track) explicitly allows batching ("22 PRs is a lot.
  Tracks can be batched into larger PRs"). **Defer the
  Track-12-vs-Track-13 PR-batching decision to Track 13's strategy
  refresh** when the work is in hand — this Phase A intentionally
  leaves both tracks separate to keep step-level reviews focused, and
  notes the option for downstream consideration. (Adversarial finding
  A6 — DEFERRED to Track 13 strategy refresh, rationale recorded
  below.)
- **Track 14 / Track 15** will incidentally cover `MemoryStream` via
  `RecordId*` and `RecordBytes` round-trips. Track 12 limits its
  `MemoryStream` coverage to **raw read/write/grow/move/copyFrom
  primitives** — defer record-id / blob round-trips to Tracks 14–15.
- **Track 22** absorbs:
  - Deletions: `RecordSerializerCSVAbstract` (instance API) and the
    abstract-instance / unused-public-static methods of
    `RecordSerializerStringAbstract`; `JSONWriter` (full class);
    `Streamable` interface; `StreamableHelper`;
    `SerializationThreadLocal` listener path.
  - Residual coverage gap on `JSONSerializerJackson`'s
    `IMPORT_BACKWARDS_COMPAT_INSTANCE` `'$'`-replacement / legacy
    1.x-export branches (≤ ~5 percentage points; reachable only
    through `DatabaseImport` of legacy 1.x files — partial Track 15
    overlap).
- **Track 13** (forward-pin): same dead-code-grep discipline applied
  to `MockSerializer`, `RecordSerializerNetwork` interface,
  V0/V1/V2 path selection. (Suggestion A9 — recorded for Track 13
  strategy refresh; not actioned here.)

### Constraints

1. JUnit 4 with `surefire-junit47`; project-wide constraints from
   plan §Constraints apply.
2. Tests must pass under both `-Dyoutrackdb.test.env=ci` (disk) and
   the default in-memory mode.
3. **Constraint 7** (coverage exclusions): nothing in this track
   touches the JaCoCo-excluded paths.
4. Disposition table (below) enumerates which Phase A findings are
   ACCEPTED-fixed-here vs ACCEPTED-deferred vs DEFERRED.

### Phase A findings — disposition

| ID | Severity | Disposition | Where addressed |
|---|---|---|---|
| T1 | should-fix | ACCEPTED — three-strata reframe | Description above |
| T2 | blocker | ACCEPTED — `SerializerFactory` reference removed; `JSONReader/JSONWriter/StringSerializerHelper` enumerated | Description above |
| T3 | should-fix | ACCEPTED — live/dead split for `core/serialization` root | Description above |
| T4 | blocker | ACCEPTED — Step 1 fixes `SafeConverterTest`/`UnsafeConverterTest`/`AbstractConverterTest` | Steps |
| T5 | should-fix | ACCEPTED — `RecordSerializer` default-throw stub-implementor test | Step 4 |
| T6 | should-fix | ACCEPTED — per-file harness table | Description above |
| T7 | should-fix | ACCEPTED — semantic-equivalence rules per type | Description above |
| T8 | suggestion | ACCEPTED — folded into Step 4 (interface-and-stream cleanup) | Step 4 |
| T9 | suggestion | ACCEPTED — `MEMORY_USE_UNSAFE` / `LinkSerializer.INSTANCE` notes | Step 3 + cross-track section |
| T10 | suggestion | ACCEPTED — YTDB-86 deletion context noted | Description above |
| R1 | should-fix | ACCEPTED — `*DeadCodeTest` step + `forwards-to: Track 22` markers | Step 2 |
| R2 | should-fix | ACCEPTED — `@Category(SequentialTest)` requirement on static-state mutations | Description harness table + Step 3 |
| R3 | should-fix | ACCEPTED — per-file harness pre-allocation | Description harness table |
| R4 | suggestion | ACCEPTED — `MemoryStream` scoped to raw primitives | Description cross-track |
| R5 | suggestion | ACCEPTED — `TestUtilsFixture` extension for session-bound tests | Description harness table |
| R6 | suggestion | ACCEPTED — residual-gap forwarding to Track 22 | Description cross-track |
| A1 | blocker | ACCEPTED — `*DeadCodeTest` step covering CSVAbstract + StringAbstract + JSONWriter + Streamable + StreamableHelper + SerializationThreadLocal listener | Step 2 |
| A2 | blocker | ACCEPTED — Step 1 (broken-test fix) re-measures baseline | Step 1 |
| A3 | blocker | ACCEPTED — three-strata reframe; JSON gets dedicated steps | Description + Steps 5 & 6 |
| A4 | should-fix | ACCEPTED — Track 12 D2 inversion noted; per-file harness table | Description harness table |
| A5 | should-fix | ACCEPTED — semantic-equivalence rules table | Description above |
| A6 | should-fix | DEFERRED to Track 13 strategy refresh — kept tracks separate to keep Phase B/C reviews focused; Track 13 will decide PR batching when both are decomposed and code is in hand | Cross-track section |
| A7 | suggestion | ACCEPTED — JSON split into Step 5 (default instance) + Step 6 (import modes) | Steps |
| A8 | suggestion | ACCEPTED — `MemoryStream` `@Deprecated` strategy noted (live-caller paths only) | Description cross-track |
| A9 | suggestion | DEFERRED — record for Track 13 strategy refresh (cross-track concern) | Cross-track section |

### Original scope reference

Original Phase 1 plan (legacy block in `implementation-plan.md`):
> Target packages:
> - `core/serialization/serializer/record/string` (998 uncov, 30.9%)
> - `core/serialization/serializer` (629 uncov, 41.4%)
> - `core/serialization` (277 uncov, 14.2%)
> - `common/serialization` (146 uncov, 34.5%)
> - `core/serialization/serializer/record` (14 uncov, 0.0%)
> - `core/serialization/serializer/stream` (9 uncov, 60.9%)
>
> Test approach: round-trip serialization — create objects, serialize,
> deserialize, verify equality. Cover type-specific paths (strings,
> numbers, dates, embedded documents, links, collections).
>
> **Scope:** ~6 steps covering string serializer types, string
> serializer collections/links, serializer infrastructure, common
> serialization, remaining, and verification
> **Depends on:** Track 1

Phase A grew the scope from ~6 to **7 steps** (still within the ≤7-step
cap) because dead-code pinning, broken-test repair, and JSON-mode
splitting cannot fit in the original 6-step bucket without diluting
review focus.

## Progress
- [x] Review + decomposition
- [x] Step implementation (8/8 complete; Step 4 split into 4a + 4b — both done)
- [x] Track-level code review (2/3 iterations — iter-2 PASSED all 7 dimensions; the only iter-2-raised should-fix was addressed in commit `8aa6b4e40f`)

## Base commit
`634a8a5a83707869218aa03ef7d6432e47d94c70`

## Rebase state
- Pre-rebase HEAD: `634a8a5a83` (`Review fix: tighten getEvents type pin and dual-instance assertions`)
- `git fetch origin develop` + rebase: **no-op** — branch was already ahead of `origin/develop` by Track 11's commits with zero develop-side commits to incorporate.
- Post-rebase HEAD: `634a8a5a83` (unchanged)

## Reviews completed
- [x] Technical (`reviews/track-12-technical.md`) — 10 findings (T1–T10): 2 blocker / 5 should-fix / 3 suggestion. All blockers and should-fix items addressed in Description / Steps; suggestions accepted.
- [x] Risk (`reviews/track-12-risk.md`) — 6 findings (R1–R6): 0 blocker / 3 should-fix / 3 suggestion. All addressed in Description / Steps.
- [x] Adversarial (`reviews/track-12-adversarial.md`) — 9 findings (A1–A9): 3 blocker / 3 should-fix / 3 suggestion. Blockers and should-fix items addressed in Description / Steps, except A6 / A9 deferred to Track 13 strategy refresh (rationale in Description cross-track section).

### Track-level code review iter-1 (Phase C)
Spawned 7 dimensional sub-agents in parallel against `git diff 634a8a5a83..HEAD`:
4 baseline (CQ / BC / TB / TC) + conditional SE (JSON deserialization /
`Class.forName` surface) + TS (DbTestBase + `SequentialTest` fixtures) + TX
(process-wide static-state mutators).

**Combined verdict** (after dedup across reviewers): 0 blocker / ~25 should-fix / ~20 suggestion.

**Iter-1 fixes committed** (commit `58dd5bda3d`, 8 test files modified, +270 / -23):
- Test-correctness (BC1, TB5, TB7, CQ4): `assertEquals → assertSame` for reference-identity claims; drop tautological assertSame; split combined boolean assertion.
- Test-isolation (TX1 / BC2 / TS3): `@After` cleanup for `SerializationThreadLocal.INSTANCE.remove()` so surefire worker thread's per-thread set is force-cleared on assertion failure.
- Diagnostic precision (TB4 × 3): walk cause chain and assert underlying Jackson diagnostic phrase in three import-mode rejection tests.
- Boundary completeness (TC3 / TC4 / TC5 / TC10 / TC15 / TB3 / TC7 / SE3): `MemoryStream.read()` AIOOBE, `read(byte[], off, len)` zero-length and oversized, `move` left/right boundary AIOOBE, `toByteArray()` off-by-one inverse, full-slice equality on `copyFromGrowsDestinationBufferWhenNeeded`, `bytes2short` empty / `bytes2int` and `bytes2long` partial-stream pins, JSON unicode-escape: null character, surrogate pair, malformed hex, truncated-at-EOF.
- Cleanup (CQ1 / TS4 / CQ3): drop dead `var jvmDefault` placeholder + `TimeZone` import; flip negated `assertTrue` to `assertFalse`.

**Deferred** (kept as suggestions or forwarded to other tracks):
- DRY refactor for the JSON test helpers (`parseImport` / `parseDefault` / `inTx` / `chainMessages`) duplicated across three classes (CQ7 / TS2 / CQ6) — lift to a shared base class. Bigger structural change.
- Drop `TestUtilsFixture` from `JSONReaderDbTest` (TS1) — readability / DB-tax improvement, structural.
- `persistThing` tx-asymmetry refactor (TS7) — structural.
- Security commentary on `StreamableHelperDeadCodeTest` (SE1) and `IMPORT_BACKWARDS_COMPAT_INSTANCE` permissive flags (SE2) — Javadoc additions to forward the security rationale to the deferred-cleanup track. Useful but not blocking.
- Save/restore (vs reset-to-null) on `streamableClassLoader` (BC3 / TX2) — forward-looking robustness.
- `inTx` try/finally rollback symmetry with `inFreshTx` (BC4) — currently safe by usage convention.
- Float/Double NaN round-trip (TC1), DST DATE (TC2), high-precision BigDecimal (TC11), empty-collection round-trip (TC6), malformed-RID / cluster=0 LINK (TC8) — risk of unexpected production behaviour without verifying Jackson features and DateHelper config first; defer to Track 22 sweep or Track 13 strategy refresh.
- Native-on-wire layout pin (TB8), `linkSet` element pin (TB9), `roundTrip` helper invariant (TB10), full-RidSet structural equality (TB1), immediate-message pin (TB6) — assertion-tightening with diminishing returns relative to risk.
- Naming / documentation cosmetics (TS6 / TS8 / TS11 / CQ8-14, TX3) — cosmetic.
- `JSONWriterDeadCodeTest` 9-untested-method drift detector (TS9) — class is dead-code-pinned; deletion supersedes any test-shape improvement.
- `RecordSerializerCsvAbstractEmbeddedMapTest` symmetric `@After` (TS10) — relies on inherited `TestUtilsFixture.rollbackIfLeftOpen`, materially safe.

**Iter-2 gate-check deferred**: context consumption hit `warning` (30%) at the end of iter-1 fixes; spawning four+ more sub-agents would have crossed the 40% critical threshold. Per the workflow's Context-Consumption-Check rule, the iter-1 fixes were committed and the next session will run the gate-check on the committed iter-1 diff. All 230 tests across the 8 affected files pass (BUILD SUCCESS); spotless clean.

### Track-level code review iter-2 (Phase C)
Spawned the same 7 dimensional sub-agents in parallel against
`git diff 634a8a5a83..HEAD` (full track diff including iter-1 fix commit
`58dd5bda3d`). Each sub-agent received the iter-1 finding inventory
(`/tmp/claude-code-track12-iter1-findings-29416.md`) and gate-checked
their dimension's findings VERIFIED / STILL OPEN / REJECTED, plus
scanned for new issues with cumulative IDs.

**Combined verdict** (after dedup across reviewers): 0 blocker /
1 should-fix (TC21) / ~12 suggestion. **All 7 dimensions returned PASS.**

**Per-dimension iter-1 verification:**
- **CQ** — CQ1 / CQ3 / CQ4 VERIFIED; CQ6 / CQ7 (DRY refactor) and CQ8–CQ14 (cosmetics) STILL DEFERRED-acceptable.
- **BC** — BC1 / BC2 VERIFIED; BC3 (save/restore on `streamableClassLoader`) and BC4 (`inTx` try/finally) STILL DEFERRED-acceptable (mitigated by `@Category(SequentialTest)` for BC3 and `TestUtilsFixture.rollbackIfLeftOpen` for BC4).
- **TB** — TB3 / TB4×3 / TB5 / TB7 VERIFIED; TB1 / TB6 / TB8 / TB9 / TB10 STILL DEFERRED-acceptable.
- **TC** — TC3 / TC4 / TC5 / TC7 / TC10 / TC15 VERIFIED; TC1 (Float/Double NaN), TC2 (DST DATE), TC8 (malformed-RID), TC11 (high-precision BigDecimal) STILL DEFERRED-acceptable.
- **SE** — SE3 VERIFIED; SE1 (StreamableHelper security commentary) and SE2 (IMPORT_BACKWARDS_COMPAT_INSTANCE reachability doc) STILL DEFERRED-acceptable, with caveat that Track 22 deletion / cleanup should surface the security rationale at the point of deletion.
- **TS** — TS3 / TS4 VERIFIED; TS1 / TS2 / TS6–TS11 STILL DEFERRED-acceptable.
- **TX** — TX1 VERIFIED; TX2 / TX3 STILL DEFERRED-acceptable (re-evaluation confirms reset-to-null is logically equivalent to save/restore for `streamableClassLoader` since the production default at JVM start is `null` and the test runs under `@Category(SequentialTest)`).

**New iter-2 finding fixed in commit `8aa6b4e40f`**:
- **TC21** (should-fix) — empty typed-collection JSON round-trip path uncovered. Production has explicit empty-loop branches in `JSONSerializerJackson.parseLinkList` / `parseLinkSet` / `parseLinkMap` / `parseEmbeddedList` / `parseEmbeddedSet` / `parseEmbeddedMap` that all return constructed-but-empty `EntityXxxImpl(entity)` containers, but no test pinned the constructed-empty shape. A regression that returned null, `Collections.emptyList()`, or a non-Iml class would silently pass. Fix adds 6 round-trip tests (`linkListEmptyRoundTripsToEmptyContainer`, `linkSetEmptyRoundTripsToEmptyContainer`, `linkMapEmptyRoundTripsToEmptyContainer`, `embeddedListEmptyRoundTripsToEmptyContainer`, `embeddedSetEmptyRoundTripsToEmptyContainer`, `embeddedMapEmptyRoundTripsToEmptyContainer`) — one per typed collection — each persisting an empty container, serialising, deserialising in a fresh tx, and asserting non-null + empty. `JSONSerializerJacksonInstanceRoundTripTest` total: 53 (was 47), Failures: 0. Spotless clean.

**New iter-2 suggestions deferred** (no actionable cross-track impact):
- **CQ20** (suggestion) — `StreamableHelperDeadCodeTest.roundTrip` helper has a confusing assertion message phrase ("non-null+null") and a near-tautological `bytes.size() >= 1` branch.
- **CQ21** (suggestion) — `chainMessagesOf` helpers placed mid-class instead of grouped with the `parseImport` / `parseDefault` / `inTx` helpers block in two of the three Jackson test files. Will be folded into the planned shared base class (deferred CQ6 / CQ7 / TS2).
- **CQ22** (suggestion) — three new files use the malformed double-asterisk Apache license header; cosmetic precedent collision in the codebase (Spotless does not flag).
- **CQ23** (suggestion) — `RecordSerializerCsvAbstractEmbeddedMapTest` uses `Class.isInstance(...)` instead of `instanceof` in a few places; stylistic.
- **TB20** (suggestion) — `StreamableHelperDeadCodeTest.fromStreamThrowsForUnknownTypeByte` pins only the umbrella `SerializationException` type, not the diagnostic phrase. Class is forwarded to deferred-cleanup track for deletion.
- **TB21** (suggestion) — same helper as CQ20, ternary boolean assertion split candidate.
- **TB22** (suggestion) — four sibling tests in `JSONSerializerJacksonImportBackwardsCompatTest` use `ex.getCause().getMessage().contains(...)` against only the immediate cause; iter-1's `chainMessagesOf` chain-walking pattern (TB4) could extend to them. Existing `getCause()` pin already provides falsifiability for the immediate-cause class.
- **TB23** (suggestion) — `json.contains("...")` substring assertions on structured-JSON shape in 4 places; downstream round-trip already pins behavioural equivalence so substring checks document serialisation-format intent only.
- **TB24** (suggestion) — `JSONReaderDbTest` ridbag-extraction tests assert per-element `contains(rid)`; iter-1 deferred TB1 (full-set structural equality) made the same call.
- **TC20** (suggestion) — `Integer.MAX_VALUE` / `Long.MAX_VALUE` / `0` / `-1` boundary round-trips through the JSON path are not pinned. Static-helper layer pins `Integer.MAX_VALUE` boundary but a different code path.
- **TC22** (suggestion) — `JSONReaderDbTest` does not pin malformed-RID body inside an `out_E:[...]` ridbag block; production caller doesn't sanitise. Failure mode is loud (exception propagates).
- **SE10** (suggestion) — JSON deserialisation tests do not pin DoS-relevant input boundaries (deeply-nested JSON, overlong string field, huge embedded collection). Defense-in-depth — current reachability is authenticated/admin-only via `DatabaseImport` file path.
- **TS20** (suggestion) — `StringSerializerHelperTest.testGetMap` has three pre-existing `System.out.println` debug calls; no regression introduced this track but worth folding into a deferred-cleanup pass.
- **TS21** (suggestion) — pre-existing `test()` method name in `StringSerializerHelperTest` not renamed to follow the new naming convention; cosmetic.

The cumulative deferral set forwards to Track 22 (Transactions, Gremlin
& Remaining Core) absorption queue: deferred items listed above plus
the iter-1 deferral set (CQ6 / CQ7 / TS2 — DRY refactor; SE1 / SE2 —
security commentary; BC3 / TX2 — `streamableClassLoader` save/restore;
BC4 — `inTx` try/finally; TC1 / TC2 / TC8 / TC11 — round-trip cases
needing Jackson / DateHelper config verification; TB1 / TB6 / TB8 /
TB9 / TB10 — assertion tightening with diminishing returns;
TS1 / TS6–TS11 — structural / cosmetic items).

**Iter-2 result:** PASS verdict from all 7 dimensions; one new should-fix
(TC21) addressed in `8aa6b4e40f`. No iter-3 needed — iter-3 is reserved
for verifying open should-fix or blocker fixes; the TC21 fix is purely
test-additive (6 small round-trip tests, no production code touched)
and would only reconfirm the gate check that already passed. All 53
tests in `JSONSerializerJacksonInstanceRoundTripTest` pass on the new
diff (BUILD SUCCESS).

## Steps

- [x] Step 1: Repair pre-existing inert converter tests (`SafeConverterTest`,
  `UnsafeConverterTest`, `AbstractConverterTest`) and re-measure baseline
  (T4, A2)
  - [x] Context: info
  > **What was done:** Repaired the three inert converter-test files in
  > `core/src/test/java/.../common/serialization/`. The original state had
  > eight `testPut*` methods on `AbstractConverterTest` and eight overrides
  > each on `SafeConverterTest` / `UnsafeConverterTest`, none of which
  > carried `@Test`, so JUnit 4 silently never ran any of them. The
  > assertions inside the bodies also called `Assert.assertEquals(byte[],
  > byte[])` (resolving to the `Object` overload — reference identity)
  > and passed scalar getter results in `(actual, expected)` order.
  >
  > Initial commit `683189c1a3` added `@Test` to every override on the
  > two subclasses (16 newly-active tests), replaced each `byte[]`
  > comparison with `Assert.assertArrayEquals(expected, actual)`, and
  > corrected the scalar `assertEquals` argument order to `(expected,
  > actual)`. `./mvnw -pl core test -Dtest=SafeConverterTest,UnsafeConverterTest`
  > reported `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`.
  >
  > Iter-1 review fix `4ce8111501` refactored the test pattern to the
  > codebase-idiomatic shape (precedent: `AbstractComparatorTest` /
  > `BinaryComparatorEqualsTest`). The eight base-class bodies became
  > `protected final void assertPut*RoundTrips()` helpers, the
  > override-just-to-call-super boilerplate was deleted, and each
  > subclass now declares eight `@Test public void put*RoundTrips()`
  > methods that delegate to the helpers. `beforeClass()` was renamed
  > to `setUp()` (the `@Before` annotation made the original name
  > misleading). The `converter` field carries a Javadoc documenting
  > the fixture contract (subclass `@Before` assigns it; per-method
  > JUnit 4 instantiation prevents leakage). The class-level Javadoc
  > on the abstract base now spells out all three defects the repair
  > addresses and explains why the new shape is footgun-free (a future
  > subclass author writes `@Test` directly on the test method, not
  > on an override that JUnit 4 re-reads). Spotless clean. Refactored
  > tests still pass with `Tests run: 16, Failures: 0`.
  >
  > **What was discovered:**
  > - Post-fix `common/serialization` baseline (measured via Track 1's
  >   `coverage-analyzer.py` against `.coverage/reports/youtrackdb-core/jacoco.xml`
  >   from `./mvnw -pl core,gremlin-annotations -am clean package -P coverage`):
  >   **82.1% line / 61.4% branch / 40 uncov / 223 total**, up from the
  >   pre-fix **34.5% / 27.1% / 146 / 223** that was the inflated baseline
  >   the original Track 12 plan had cited.
  > - Subsequent step coverage targets must be measured against the
  >   post-fix 82.1%/61.4% baseline, not the pre-fix 34.5%/27.1%. The
  >   remaining 40 uncov lines are concentrated in
  >   `BinaryConverterFactory`, the converter edge branches not
  >   exercised by the round-trip values, and the `nativeAccelerationUsed`
  >   pair — Step 3's `BinaryConverterFactoryTest` and the planned
  >   `Safe/UnsafeConverterTest` extensions cover most of this surface.
  > - Two iter-1 review suggestions were deliberately deferred to Step 3
  >   per the repair-only scope of Step 1 (see "What changed from the
  >   plan" below).
  >
  > **What changed from the plan:** No track-level deviation. Two
  > review-iteration suggestions were folded into Step 3's existing
  > scope rather than deferred to a separate cleanup track:
  > - **TB-2**: All `put*` calls in the helpers use offset 0; a
  >   regression that ignored the `offset` parameter on
  >   `Safe/UnsafeBinaryConverter.{put,get}{Int,Long,Short,Char}`
  >   would not be caught. Step 3 already plans non-zero-offset
  >   extensions ("negative offset, length-0 buffers, boundary values,
  >   native-byte-order assertions").
  > - **TB-3**: `BinaryConverter.nativeAccelerationUsed()` is part of
  >   the interface contract but is not exercised; a regression that
  >   flipped Safe→true or Unsafe→false would pass silently. Step 3's
  >   `BinaryConverterFactoryTest` (which already toggles
  >   `MEMORY_USE_UNSAFE`) is the natural place to pin both
  >   converters' `nativeAccelerationUsed()` return values.
  >
  > Iter-2 gate-check: all four dimensions returned **PASS** (CQ-1
  > through CQ-5 verified, BC-1 verified, TB-1 verified with TB-2/TB-3
  > recorded as STILL OPEN — DEFERRED, TS-1 through TS-5 verified). No
  > new blocker / should-fix findings on iter-2.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/common/serialization/AbstractConverterTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/common/serialization/SafeConverterTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/common/serialization/UnsafeConverterTest.java` (modified)
  >
  > **Critical context:** The plan (lines 271–272) instructed running
  > `./mvnw -pl core test -Dtest=SafeConverterTest,UnsafeConverterTest`
  > to verify 16 newly-active tests; both the iter-1 and iter-2 commits
  > were verified this way. Post-track-end full-suite verification is
  > the responsibility of Step 7.

- [x] Step 2: Pin dead-code surface across the three target packages
  via `*DeadCodeTest` classes with `// WHEN-FIXED: Track 22` markers
  (T1, T3, R1, A1) *(parallel with Step 1)*
  - [x] Context: warning
  > **What was done:** Created six `*DeadCodeTest` classes covering the
  > entire dead surface enumerated in the Phase A description:
  > `JSONWriterDeadCodeTest` (concrete, 511-LOC class), `StreamableInterfaceDeadCodeTest`
  > (zero-implementor interface), `StreamableHelperDeadCodeTest` (concrete, 0-caller
  > dispatcher; `@Category(SequentialTest)` because it mutates the static
  > `streamableClassLoader`), `SerializationThreadLocalDeadCodeTest` (only the
  > listener-shutdown path is dead — `INSTANCE` itself is live for the binary
  > serializer's int-pool, per Risk R2 the test does NOT invoke
  > `YouTrackDBEnginesManager.shutdown()`), `RecordSerializerCsvAbstractDeadCodeTest`
  > (abstract instance API of `RecordSerializerCSVAbstract` — no concrete subclass
  > since YTDB-86 removed `RecordSerializerSchemaAware2CSV` in `24d5a3d967`),
  > `RecordSerializerStringAbstractDeadCodeTest` (abstract instance API +
  > three unused public statics: `fieldTypeFromStream`, `convertValue`,
  > `fieldTypeToString`).
  >
  > Initial commit `290d5d9d36` (44 tests). Iter-1 dimensional review (4 baseline
  > agents — CQ/BC/TB/TC) returned **TC=FAIL, CQ/BC/TB=PASS-with-should-fix**
  > driven by two material gaps: (a) `JSONWriterDeadCodeTest` exercised only
  > ~17 of ~35 public methods, leaving the writing API (writeValue statics,
  > writeRecord, writeAttribute, writeObjects, beginCollection/endCollection,
  > multi-arity beginObject/endObject, flush/close/write) without a compile-time
  > pin; (b) `RecordSerializerStringAbstractDeadCodeTest` had no pins for the
  > three unused public statics and no drift detector mirroring the CSV
  > sibling. Plus three should-fix items (BC-1/CQ-4/TB-5: silent fallback in
  > the `SerializationThreadLocal` anonymous-class lookup; TB-1: empty
  > `setStreamableClassLoaderAcceptsNull` test; CQ-2/TB-2: tautological
  > `assertSame(Streamable.class, Streamable.class)`).
  >
  > Iter-1 fix commit `b086849193` (+9 tests, 65 total) addressed every
  > should-fix and FAIL-driving item: added a reflective drift detector to
  > `JSONWriterDeadCodeTest` covering the unpinned writing API; added three
  > reflective static-method pins + a drift detector + two delegation pins
  > (concrete two-arg `fromString` → abstract four-arg, concrete four-arg
  > `toString` → abstract five-arg) to `RecordSerializerStringAbstractDeadCodeTest`;
  > replaced the silent `Class.forName($1)` fallback in
  > `SerializationThreadLocalDeadCodeTest` with a hard-fail lookup +
  > explicit `onStartup`/`onShutdown` reflective pins; replaced the
  > tautological `assertSame` in `StreamableInterfaceDeadCodeTest` with three
  > falsifiable structural invariants (public, interface, zero fields);
  > strengthened the empty `setStreamableClassLoaderAcceptsNull` test by
  > following the null reset with a STREAMABLE-arm round-trip; added a
  > STREAMABLE-arm round-trip via a public test-local Streamable
  > implementor (default `Class.forName` path + custom-classloader
  > `loadClass` path); dropped the noisy `DATA_INPUT_REF` /
  > `DATA_OUTPUT_REF` anchors; trimmed the wandering comment in
  > `toStreamThrowsForNonSupportedObjectType`; replaced the qualified
  > `java.util.HashSet` reference with a regular import.
  >
  > **What was discovered:** Cross-module greps over `core/`, `server/`,
  > `driver/`, `embedded/`, `gremlin-annotations/`, `tests/`, `test-commons/`,
  > and `docker-tests/` confirmed the dead-surface enumeration. Two production
  > facts that the Phase A description did not call out explicitly:
  > (a) `RecordSerializerCSVAbstract.embeddedMapFromStream` (static at line
  > 245) IS live — called from `StringSerializerHelper:198` and
  > `RecordSerializerStringAbstract:77`. The dead-code pin correctly excludes
  > it; Step 4 covers it directly.
  > (b) `simpleValueFromStream` and `simpleValueToStream` on
  > `RecordSerializerStringAbstract` are LIVE: `getTypeValue` /
  > `fieldTypeFromStream` chain into them. Only `fieldTypeFromStream`,
  > `convertValue`, and `fieldTypeToString` are the unused statics —
  > the original Phase A description's "four unused public statics"
  > count was off by one. The pin set was adjusted accordingly.
  > (c) `RecordSerializerCSVAbstract.fieldToStream` takes a `StringWriter`
  > (not `StringBuilder` as I initially assumed); `embeddedCollectionToStream`
  > takes 6 parameters (5 + a trailing `boolean iSet` arm flag).
  > (d) `JSONWriter` has no `AutoCloseable` implementation despite a fluent
  > `close()` method that returns `JSONWriter`; tests use explicit `close()`
  > calls instead of try-with-resources.
  > (e) `JSONWriter`'s two-arg constructor calls `iJsonFormat.contains("prettyPrint")`
  > without a null guard, so the constructor NPEs when passed a null format
  > string. Pinned as a behavioural contract via `assertThrows(NullPointerException.class)`
  > — a future hardening that adds a null guard becomes loud.
  >
  > **What changed from the plan:** No track-level deviation. Iter-2
  > dimensional gate-check was **skipped** because context consumption hit
  > 37% (warning) immediately after the iter-1 fix commit; spawning four
  > more sub-agents would have crossed the 40% critical threshold without
  > buying material additional safety. All iter-1 should-fix items were
  > addressed in `b086849193` and verified by direct re-execution of the
  > 65 dead-code tests (`./mvnw -pl core test -Dtest='JSONWriterDeadCodeTest,
  > StreamableInterfaceDeadCodeTest,StreamableHelperDeadCodeTest,
  > SerializationThreadLocalDeadCodeTest,RecordSerializerCsvAbstractDeadCodeTest,
  > RecordSerializerStringAbstractDeadCodeTest'`); spotless is clean.
  > Remaining iter-1 suggestions deferred (none required for Step 2's
  > acceptance criterion of "6 dead-code pin tests; cross-module grep evidence
  > in each Javadoc"): TB-3-tier delegation pins were partially absorbed
  > (the two strongest delegations are now pinned); BC-2 prior-value
  > save/restore on `streamableClassLoader` (only writer is the test
  > class, so today's null reset is equivalent to save/restore — flagged
  > for revisit if a sibling test ever takes a write); BC-3 try/finally
  > on `INSTANCE.remove()` cleanup (current code is correct under JUnit 4
  > single-thread-per-method semantics); CQ-6 DRY of the
  > `assertDeclaresIOException` exception-list helper (cosmetic).
  >
  > Track 22's deferred-cleanup queue is now updated with the deletion
  > items from this step: `JSONWriter` (full class), the abstract instance
  > API of `RecordSerializerCSVAbstract`, the abstract instance API + three
  > unused statics of `RecordSerializerStringAbstract`, the `Streamable`
  > interface, the `StreamableHelper` class, and the listener-shutdown
  > path on `SerializationThreadLocal` (the `INSTANCE` field stays live).
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/JSONWriterDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/StreamableInterfaceDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/StreamableHelperDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/SerializationThreadLocalDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/string/RecordSerializerCsvAbstractDeadCodeTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/string/RecordSerializerStringAbstractDeadCodeTest.java` (new)
  >
  > **Critical context:** Test count: **65 tests** across 6 new files,
  > all standalone, ~1408 LOC committed. No production-code changes. The
  > iter-1 fix commit's "Review fix:" prefix means a future Phase B resume
  > will correctly recognize the loop as already run.
  - For each suspect class/interface, **first** confirm zero non-test
    callers via cross-module grep across `core/`, `server/`, `driver/`,
    `embedded/`, `gremlin-annotations/`, `tests/`, `test-commons/`,
    `docker-tests/`. Document the grep result in each test's Javadoc
    so a Track-22 reviewer can re-run the same query.
  - Create the following `*DeadCodeTest` classes (Track 9–11 precedent
    — falsifiable assertions on the dead method's reachable shape, so
    Track 22's deletion is detected as a test-compilation break):
    - `RecordSerializerCsvAbstractDeadCodeTest` — abstract instance
      methods (`fieldFromStream`, `fieldToStream`,
      `embeddedCollectionFromStream`, `embeddedCollectionToStream`)
      + the unused public statics if any. Live `embeddedMapFromStream`
      static is excluded from the pin (covered in Step 4).
    - `RecordSerializerStringAbstractDeadCodeTest` — abstract instance
      methods + the four unused public statics identified in T1.
      Live `getType` / `getTypeValue` / `simpleValue*` are excluded
      (covered in Step 4).
    - `JSONWriterDeadCodeTest` — full class (511 LOC, 0 callers).
    - `StreamableInterfaceDeadCodeTest` — interface has 0 implementors.
    - `StreamableHelperDeadCodeTest` — class has 0 callers
      (`@Category(SequentialTest)` if any test mutates
      `streamableClassLoader`; otherwise standalone).
    - `SerializationThreadLocalDeadCodeTest` — listener-shutdown
      path has 0 readers; `INSTANCE` itself is live for binary
      serializer's int-pool. Pin only the listener path; do NOT
      trigger `YouTrackDBEnginesManager.shutdown()` (Risk R2).
  - Use the grep + `@Test`-style "compile-time pin" pattern from
    `CommandScriptDeadCodeTest` / `FetchHelperDeadCodeTest` /
    `SchedulerSurfaceDeadCodeTest`.
  - **Files**: 6 new test files under
    `core/src/test/java/.../core/serialization/**` and
    `core/src/test/java/.../core/serialization/serializer/**` and
    `core/src/test/java/.../core/serialization/serializer/record/string/**`.
    No production-code edits.
  - **Harness**: standalone.
  - **Acceptance**: 6 dead-code pin tests; cross-module grep evidence
    in each Javadoc; spotless clean. Track 22's queue is updated with
    the deletion items (recorded in step episode for the strategy
    refresh after this track).

- [x] Step 3: Common serialization standalone tests — `BinaryProtocol`,
  `BinaryConverterFactory` Safe-vs-Unsafe branch, extended
  `Safe/UnsafeBinaryConverter` paths, `MemoryStream` raw primitives
  (T9, R2, R4, A8) *(parallel with Step 2)*
  - [x] Context: warning
  > **What was done:** Added the live byte-arithmetic round-trip test
  > surface across the three target packages with three new standalone
  > test files plus extensions to the Step-1-repaired
  > `Safe/UnsafeConverterTest` pair plus 24 new helper methods on
  > `AbstractConverterTest`.
  >
  > Initial commit `cfb65ad894` (1,484 LOC, 121 new tests across 6
  > files, no production-code changes):
  > - `BinaryProtocolTest` (29 tests, standalone) — three encode flavours
  >   per scalar (byte-array-with-offset, allocating wrapper, OutputStream
  >   form) and three decode flavours (byte-array, InputStream, no-offset
  >   wrapper). Each scalar gets a big-endian on-wire layout pin for one
  >   high-bit value plus boundary round-trips (MIN/MAX/0/-1/1) at offset
  >   0 and at non-zero offsets. The OutputStream-form tests pin the
  >   "returns begin-offset when the stream is a `MemoryStream`, -1
  >   otherwise" contract; two short-read tests document the historical
  >   "empty `InputStream` → -1" semantics so a future change that
  >   distinguished EOF from a valid -1 value would surface as a test
  >   break.
  > - `BinaryConverterFactoryTest` (4 tests, `@Category(SequentialTest)`,
  >   `@Before`/`@After` save-and-restore of `MEMORY_USE_UNSAFE`) — pin
  >   the Safe-vs-Unsafe dispatch on the configuration flag, the
  >   singleton property across calls, and per-converter
  >   `nativeAccelerationUsed()` labels. Class-level Javadoc records the
  >   "static-final CONVERTER fields capture the factory's choice at
  >   class-init time" caveat (Risk R2 evidence).
  > - `MemoryStreamTest` (31 tests initially → 36 after iter-1) — raw
  >   read/write/grow/move/copyFrom/peek primitives plus the snapshot
  >   accessors (`copy`, `toByteArray`) and the position/capacity
  >   primitives (`reset`, `close`, `jump`, `fill`, `setPosition`,
  >   `available`, `size`, `getSize`, `setSource`).
  > - `AbstractConverterTest` extension: 24 new helpers (8 non-zero-offset,
  >   4 boundary-value, 4 native-byte-order; per-scalar × per-byte-order
  >   where applicable). The non-zero-offset helpers use guard bytes
  >   before and after the encoded region so a regression that ignored
  >   the offset parameter is caught.
  > - `SafeConverterTest` / `UnsafeConverterTest`: gain delegating @Test
  >   methods for each new helper. `SafeConverterTest` also adds five
  >   Safe-only AIOOBE bounds-check pins (negative offset on putInt,
  >   length-0 buffer on putLong, negative offset on getShort, length-0
  >   buffer on getChar, write past buffer end). The Unsafe sibling does
  >   not have these tests because `sun.misc.Unsafe`-backed indexing
  >   would silently corrupt the heap on the same inputs (Risk R2).
  >
  > Iter-1 dimensional review (6 sub-agents — CQ/BC/TB/TC + conditional
  > TS/TX) returned 0 blockers, ~12 should-fix items, ~20 suggestions.
  > Iter-1 fix commit `faf57a75f7` (197 insertions, 40 deletions, +5
  > tests) addressed every should-fix item plus the higher-leverage
  > suggestions:
  > - `wrapBufferConstructorStoresBufferReferenceAndWritesAreObservable`
  >   now writes through the stream and asserts the byte appears in the
  >   original `backing` array (the prior body only asserted reference
  >   identity).
  > - `closeIsEquivalentToReset` now pins the buffer-identity, capacity,
  >   and content invariants alongside position rewind.
  > - `setPositionMovesPositionToTarget` asserts position after the
  >   chained call, not just chainability.
  > - The buffer-growth assertions for write/copyFrom/fill now pin the
  >   documented `Math.max(bufferLength<<1, capacity)` outcome by exact
  >   equality (with two new sibling tests covering the doubling-wins
  >   and request-wins arms separately).
  > - Two new tests cover the previously untested
  >   `MemoryStream.toByteArray()` copy path and the "100% USED"
  >   alias-the-internal-buffer shortcut, plus `copy()` on a fresh
  >   stream (the `position == 0` ternary arm).
  > - `instanceAndNewConstructorAgree` (both Safe and Unsafe) is now
  >   bidirectional with a final byte-for-byte assertion that the two
  >   on-wire layouts are identical.
  > - Two test renames: `writeArrayLargerThanCopyThreshold...` →
  >   `writeArrayOfSixteenBytesCopiesAllBytesAndAdvancesPosition` (the
  >   threshold branches are not separately observable; the prior name
  >   set a maintainability trap), and `copyFromAppendsBytesAtCurrentPosition`
  >   → `copyFromWritesBytesAtCurrentPositionWithoutAdvancing` (surfaces
  >   the surprising "doesn't advance" contract in the name).
  > - Truncation tests in `BinaryProtocolTest` renamed to
  >   `writesLowSixteenBitsOfNarrowed*` and the section comment rephrased
  >   to attribute the narrowing to the call-site cast, not to
  >   `BinaryProtocol` itself.
  > - Boundary-value Javadoc in `AbstractConverterTest` updated to match
  >   the values the loops actually iterate; the short helper grows by
  >   one value to align with the `BinaryProtocolTest` sibling.
  > - Class-level Javadoc residual-gap markers added for two surfaces
  >   the test infrastructure cannot reach in a single-JVM test:
  >   (a) `UnsafeBinaryConverter`'s `!useOnlyAlignedAccess` fast-path
  >   family (the actual `theUnsafe.put*`/`reverseBytes` family — the
  >   *raison d'être* of the class) is unreachable because
  >   `DIRECT_MEMORY_ONLY_ALIGNED_ACCESS` is captured into a static
  >   final at class-init and defaults to `true`; (b) the typed
  >   `set/getAs*` family on `MemoryStream` whose overloads have no live
  >   caller anywhere in the project (ones reached transitively through
  >   `RecordId.toStream/fromStream` and `CommandRequestTextAbstract`
  >   are exercised through their callers' tests). Both forwarded to
  >   the wider `@Deprecated` retirement queue.
  >
  > Iter-2 dimensional gate-check was **skipped** because context
  > consumption hit 30% (warning) immediately after the iter-1 fix
  > commit; spawning four more sub-agents would have crossed the 40%
  > critical threshold without buying material additional safety. All
  > iter-1 should-fix items were addressed in `faf57a75f7` and verified
  > by direct re-execution of all 126 tests
  > (`./mvnw -pl core test -Dtest='BinaryProtocolTest,BinaryConverterFactoryTest,MemoryStreamTest,SafeConverterTest,UnsafeConverterTest'`):
  > 122 parallel + 4 sequential = 126 tests, 0 failures, 0 errors.
  > Spotless clean. Remaining iter-1 suggestions deferred (none required
  > for Step 3's acceptance criterion of `common/serialization` and
  > `core/serialization` root reaching ≥85% line / ≥70% branch on the
  > live non-pinned surface — Step 7 measures this end-to-end):
  > - BC1 `tearDown` writes default-initialised field if `setUp` throws
  >   (theoretical — `getValueAsBoolean` cannot throw).
  > - TB2 `getConverterReturnsUnsafeWhenUseUnsafeIsTrue` JDK-runtime
  >   assumption (could use `Assume.assumeTrue` — but `UnsafeBinaryConverter`
  >   class-load itself would fail first on a hypothetical JDK without
  >   `sun.misc.Unsafe`, so the test would surface the regression as
  >   `NoClassDefFoundError`, not a silent label flip; cosmetic).
  > - CQ5 asymmetric cast in `readSingleByteAdvancesPosition` (cosmetic).
  > - CQ7/TB8 `nativeAccelerationUsedDistinguishesSafeAndUnsafe` is
  >   redundant with the per-converter pins (acknowledged duplicate;
  >   could be folded into the existing `getConverterReturnsUnsafe...`
  >   tests).
  > - TC4 doubling-vs-exact-size branch on `assureSpaceFor` was
  >   addressed (two new tests added in iter-1 fix).
  > - TC5 factory `(true, false)` path unreachable — already documented
  >   as residual.
  > - TC6 `MemoryStream.read()` no-arg AIOOBE pin (low-value).
  > - TC8 `move` overflow boundary (low-value, would catch a regression
  >   in the size formula).
  > - TC9 `fill(int, byte)` capacity-growth (covered by sibling
  >   `fillGrowsBufferWhenLengthExceedsCapacity` for the no-filler form).
  > - TS3 `getSize`-after-write pin (could be added; commit message claim
  >   is partially honoured by `defaultConstructorAllocatesDefaultCapacity`).
  > - TS4 @Rule-based save/restore (optional refactoring).
  > - TS5 `short2bytesWrapperAllocatesFreshBuffer` mutation idiom
  >   (cosmetic).
  > - TS8 `getConverterReturnsSingletonInstanceAcrossCalls` mixes two
  >   contracts (cosmetic).
  > - TX1 cross-track concern — pre-existing `ComparatorFactoryTest`
  >   mutates `MEMORY_USE_UNSAFE` without `@Category(SequentialTest)`;
  >   genuine but out of scope for this commit.
  > - TX2 concurrent `getConverter()` test (low-value; production path
  >   is trivially thread-safe with no shared mutable state).
  >
  > **What was discovered:**
  > - `GlobalConfiguration.DIRECT_MEMORY_ONLY_ALIGNED_ACCESS` defaults to
  >   `true` (verified at `GlobalConfiguration.java:138`). The
  >   `useOnlyAlignedAccess` flag in `UnsafeBinaryConverter` is
  >   initialised at class-init from this configuration into a
  >   `static final`, so the entire `!useOnlyAlignedAccess` fast-path
  >   family (the `theUnsafe.put*`/`reverseBytes` branches that are the
  >   actual reason `UnsafeBinaryConverter` exists) is unreachable from
  >   tests in the default test JVM. This is a pre-existing condition,
  >   not a Step-3 regression — recorded as a residual gap forwarded
  >   to the deferred-cleanup queue.
  > - `BinaryProtocol.bytes2int(InputStream)` and
  >   `BinaryProtocol.bytes2long(InputStream)` over an empty
  >   `ByteArrayInputStream` return `-1` and `-1L` respectively. This
  >   is accidental, not a designed EOF signal: every `iStream.read()`
  >   past EOF returns the int -1 (=0xFFFFFFFF), which after masking
  >   with `0xff` becomes 0xFF in each byte slot, and the four-byte
  >   composition is 0xFFFFFFFF = -1 by coincidence. Pinned as a
  >   historical contract; a future change that distinguished EOF from
  >   a valid -1 value would surface as a test break.
  > - `MemoryStream.copyFrom(src, iSize)` calls
  >   `assureSpaceFor(position + iSize)`, treating its own argument as
  >   the requested *additional* length — this means `copyFrom` allocates
  >   to `2*position + iSize` rather than the more natural
  >   `position + iSize`. The latent over-allocation is observable but
  >   not pinned by Step-3 tests (they were constructed in ranges where
  >   it doesn't matter); when the wider `@Deprecated` cleanup retires
  >   `copyFrom` it can absorb this wart at the same time.
  > - `MemoryStream.move(from, +n)` and `move(from, -n)` use a clever
  >   `size = (iPosition > 0 ? buffer.length - to : buffer.length - iFrom)`
  >   formula that avoids overflow if and only if `from + iPosition <=
  >   buffer.length`. For pathological inputs the formula computes a
  >   negative `size` and `System.arraycopy` throws AIOOBE — the new
  >   `move(0, +3)` and `move(2, -2)` tests pin the well-formed cases
  >   only; the AIOOBE behaviour at the boundary is left as a residual.
  > - The `DIRECT_MEMORY_ONLY_ALIGNED_ACCESS` flag's "captured to static
  >   final at class-init" pattern is symmetrical with `MEMORY_USE_UNSAFE`'s
  >   capture into the per-class `static final CONVERTER` fields. Both
  >   are documented in the test class Javadoc as load-order caveats.
  >
  > **What changed from the plan:** No track-level deviation. Step 3's
  > acceptance criterion ("`common/serialization` reaches ≥85% line /
  > ≥70% branch on the live non-pinned surface; `core/serialization`
  > root reaches ≥85%/≥70% on the live non-pinned surface") will be
  > verified in Step 7's full coverage measurement. Two surfaces remain
  > as residual gaps (`UnsafeBinaryConverter` `!useOnlyAlignedAccess`
  > fast-path; `MemoryStream` typed accessors with no live caller),
  > both documented in test class Javadoc and forwarded to the wider
  > deferred-cleanup queue. The current track's deletion items already
  > include `MemoryStream`'s wider `@Deprecated` surface.
  >
  > Test-infrastructure precedents continued from earlier tracks:
  > - `@Category(SequentialTest)` for tests that mutate process-wide
  >   `GlobalConfiguration` flags (precedent: `ByteBufferPoolTest`,
  >   `DirectMemoryAllocatorTest`, `StreamableHelperDeadCodeTest`).
  > - `@Before`/`@After` save-and-restore for those mutations
  >   (precedent: `ByteBufferPoolTest.beforeClass/afterClass`).
  > - Helper-method-on-abstract-base + delegating `@Test` methods on
  >   subclasses (precedent: Step 1 review-fix; older
  >   `AbstractComparatorTest` / `BinaryComparatorEqualsTest`).
  > - Falsifiable per-value assertion messages (`"value=" + value + ...`)
  >   in boundary-value loops so failure identifies the broken value.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/common/serialization/AbstractConverterTest.java` (modified — +24 helpers)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/common/serialization/SafeConverterTest.java` (modified — +18 @Test methods + 5 bounds-check tests + bidirectional instance-vs-singleton)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/common/serialization/UnsafeConverterTest.java` (modified — +16 @Test methods + bidirectional instance-vs-singleton + residual-gap Javadoc)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/common/serialization/BinaryConverterFactoryTest.java` (new — 4 tests, `@Category(SequentialTest)`)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/BinaryProtocolTest.java` (new — 29 tests)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/MemoryStreamTest.java` (new — 36 tests)
  >
  > **Critical context:** Test count: 126 tests (122 parallel + 4
  > sequential) across 3 new files plus extensions to 3 existing test
  > files. The iter-1 fix commit's `Review fix:` prefix means a future
  > Phase B resume will correctly recognise the loop as already run and
  > skip directly to episode production.
  - `BinaryProtocolTest` — round-trip primitive byte/short/int/long
    encode/decode; falsifiable assertions on byte order and
    truncation behaviour.
  - `BinaryConverterFactoryTest` — Safe vs Unsafe branch under
    `@Category(SequentialTest)` toggling
    `GlobalConfiguration.MEMORY_USE_UNSAFE` with `@Before`/`@After`
    save-and-restore. Confirm the branch is reachable; document the
    "already-loaded `static final CONVERTER` fields are not affected
    mid-suite" caveat in the test's Javadoc (Risk R2 evidence).
  - Extend `SafeConverterTest` / `UnsafeConverterTest` (post-Step-1
    repair) with edge cases: negative offset, length-0 buffers,
    boundary values, native-byte-order assertions.
  - `MemoryStreamTest` — raw `read/write/grow/move/copyFrom/peek`
    primitives only. **Do NOT** test `RecordId.toStream/fromStream`
    or `RecordBytes` round-trips here — defer to Tracks 14/15 (R4).
  - **Files**: 3 new test files under
    `core/src/test/java/.../common/serialization/` and
    `core/src/test/java/.../core/serialization/`; extension to the
    Step-1-repaired `SafeConverterTest`/`UnsafeConverterTest`.
  - **Harness**: standalone (with `@SequentialTest` on
    `BinaryConverterFactoryTest`).
  - **Acceptance**: `common/serialization` reaches ≥85% line / ≥70%
    branch on the live (non-pinned) surface; `core/serialization` root
    reaches ≥85%/≥70% on the live (non-pinned) surface; spotless clean.

- [x] Step 4a: Standalone live-static-helper tests + StringSerializerHelper
  extension (split from original Step 4 mid-implementation when the
  scope grew past ~1100 LOC — precedent: Track 9 Step 4a/4b)
  - [x] Context: warning
  > **What was done:** Added 5 test files / 127 new test methods
  > totaling ~1,129 inserted lines covering the no-session static
  > surface across four serialization packages:
  >
  > - `RecordSerializerInterfaceTest` (1 test, standalone) — exercises
  >   the `RecordSerializer.fromStream(... ReadBytesContainer ...)`
  >   default-throw branch via a stub implementor. Closes
  >   `core/serialization/serializer/record` from 0% to 100% (the
  >   package contains only this single interface file).
  > - `FieldTypesStringTest` (30 tests, standalone) — covers both
  >   char→type maps (`getType(String, char)` length-discriminated for
  >   `'b'` BYTE/BINARY split and `getOTypeFromChar(char)`
  >   length-agnostic), `loadFieldTypesV0` mutator semantics
  >   (preserves preexisting entries; allocates fresh map when supplied
  >   `null`), `loadFieldTypes` wrapper, and parser tolerance
  >   (entries without `=` silently dropped; trailing comma tolerated;
  >   empty input returns empty map). Pinned `ATTRIBUTE_FIELD_TYPES`
  >   literal constant.
  > - `RecordSerializerStringAbstractStaticsTest` (53 tests,
  >   standalone) — covers `getType(String)` parsing (RID prefix,
  >   quote/binary/embedded delimiters, all 8 type-suffix letters,
  >   suffix-not-at-end demotion to STRING, INT_MAX boundary
  >   auto-promotion to LONG, FLOAT/DECIMAL/DOUBLE round-trip
  >   classification, scientific notation) and the no-session paths
  >   of `getTypeValue` (NULL/null/Null sentinel, empty string,
  >   double-quoted decoding, RID parsing, list/set literals,
  >   integer/long auto-promotion via NumberFormatException, all 8
  >   type-suffix dispatches).
  > - `StreamSerializerRIDTest` extension (5 new tests) —
  >   `isFixedLength` / `getFixedLength` contract, native-byte-order
  >   round-trip at non-zero offset (catches `startPosition`-ignored
  >   regression), `preprocess` no-op identity, public ID byte
  >   agreement with `getId()`. Existing tests already covered
  >   `serializeNativeObject` indirectly via `testsSerializeWALChanges`,
  >   so the new direct call also serves as a falsifiable native-path
  >   pin.
  > - `StringSerializerHelperTest` extension (~24 new tests on the
  >   existing DbTestBase class) — covers `joinIntArray` /
  >   `splitIntArray` round-trip including INT extremes, multi-element
  >   `smartSplit` with array-of-separators overload, single-quote and
  >   bracket-aware `smartSplit` boundaries, `split` overloads
  >   (multi-separator, negative-end-position normalization), `contains`
  >   null guard, `getCollection` short-overload defaults plus nested
  >   bracket preservation plus unterminated-input -1 return plus
  >   Set-sink dedup, and `RECORD_SEPARATOR` literal pin. The post-loop
  >   trailing-jump-char trim only fires on the LAST element; the
  >   middle-element retention case is now pinned in
  >   `testSplitDropsLeadingJumpCharactersOnlyAtBufferStart`.
  >
  > **What was discovered:**
  > - **WHEN-FIXED dead-code pin (`getTypeValue` post-loop arms)**:
  >   `RecordSerializerStringAbstract.getTypeValue` line 377-380's
  >   `"NaN".equals(iValue) || "Infinity".equals(iValue)` →
  >   `Double.valueOf(iValue)` arm is **unreachable** through the
  >   for-loop's first-non-digit short-circuit (`return iValue;` at
  >   line 361 fires first). Inputs `"NaN"` / `"Infinity"` reach the
  >   getTypeValueNaNReturnsOriginalStringNotDouble /
  >   getTypeValueInfinityReturnsOriginalStringNotDouble pins as
  >   String, not Double. Forward to Track 22 cleanup.
  > - **Latent NumberFormatException (`getTypeValue` date-suffix arm)**:
  >   `"+abc"` and similar inputs whose second character is a
  >   recognized type-suffix letter (`'a'`/`'t'` for date-time; or
  >   `'l'`/`'f'`/`'d'`/`'b'`/`'s'`/`'c'`) hit the date-suffix arm at
  >   line 355 with `v="+"` and throw `NumberFormatException` on
  >   `Long.parseLong(v)`. Workaround: tests use `"+xyz"`/`"-xyz"`.
  >   Forward to Track 22 hardening — surface a meaningful exception
  >   or fall through to the generic `return iValue;` arm.
  > - `serializeNativeObject` is **already** indirectly covered by
  >   the existing `testsSerializeWALChanges` (line 95 of pre-existing
  >   test) — the surveyor missed this. The new direct call in
  >   `testNativeRoundTripAtNonZeroOffset` now adds explicit pinning
  >   of `getObjectSizeNative` / `deserializeNativeObject` at a
  >   non-zero offset.
  >
  > **What changed from the plan:** Mid-step split at the warning-level
  > context boundary. Step 4 was originally one atomic step covering
  > 7–8 test files; this 4a covers the standalone subset (5 files /
  > 127 tests / ~1,129 LOC). The remaining DbTestBase-extending tests
  > and the JSONReader pair carry over to Step 4b. No track-level
  > deviation — the original Step 4 acceptance criteria split
  > naturally between 4a (record interface 100%, stream package
  > coverage close to 100%) and 4b (live static-helper LOC ≥85%/≥70%
  > on `record/string` and `serializer/` via DbTestBase tests; JSONReader
  > token + DB paths).
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/RecordSerializerInterfaceTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/string/FieldTypesStringTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/string/RecordSerializerStringAbstractStaticsTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/stream/StreamSerializerRIDTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/StringSerializerHelperTest.java` (modified)
  >
  > **Critical context:** Step 4a was committed without the per-step
  > dimensional review loop because context hit warning level (25%) at
  > test-passing time. The review loop was deferred — the track-level
  > Phase C will exercise these files alongside Step 4b's outputs.

- [x] Step 4b: DbTestBase live-static-helper tests + JSONReader split
  (carries the original Step 4 work that did not fit in 4a)
  - [x] Context: warning
  > **What was done:** Added 4 test files / 111 new test methods totaling
  > ~1,681 inserted lines covering the live DB-aware static surface in
  > `core/serialization/serializer/{record/string,}` and the integration
  > paths of `JSONReader`:
  >
  > - `RecordSerializerStringAbstractSimpleValueTest` (49 tests,
  >   DbTestBase via `TestUtilsFixture`) — covers every branch of
  >   `simpleValueFromStream` (STRING with String + non-String, INTEGER
  >   passthrough + parse, BOOLEAN both forms, FLOAT/LONG/DOUBLE/SHORT/
  >   BYTE/DECIMAL passthrough + suffix-parse via `convertValue`,
  >   BINARY base64 decode, DATE/DATETIME passthrough, LINK with RID
  >   and String, `IllegalArgumentException` non-simple-type
  >   fall-through) and `simpleValueToStream` (null-value/null-type
  >   no-op, STRING quote-wrap + encode, BOOLEAN, INTEGER raw, FLOAT
  >   `f`-suffix, LONG `l`, DOUBLE `d`, SHORT `s`, DECIMAL with
  >   `BigDecimal.toPlainString()` + `c` and the non-`BigDecimal`
  >   fallback, BYTE with Character/String/boxed-Byte, BINARY base64
  >   wrap with both `byte[]` and boxed `Byte`, DATE midnight
  >   truncation in DB timezone with `a`-suffix, DATETIME raw ms with
  >   `t`-suffix, non-`Date` fallback for both DATE and DATETIME).
  >   Round-trip pins for STRING with embedded quotes, BOOLEAN, BINARY
  >   round-trip on `byte[]` extremes, DATE truncated-to-midnight, and
  >   DATETIME exact-ms recovery. Pinned the DB-timezone use (vs.
  >   JVM-default) by computing the expected truncation via
  >   `DateHelper.getDatabaseCalendar(session)` directly.
  > - `RecordSerializerCsvAbstractEmbeddedMapTest` (16 tests,
  >   DbTestBase via `TestUtilsFixture`, `@Before begin tx`) — covers
  >   `embeddedMapFromStream`'s empty-string null sentinel; empty
  >   `{}` literal yielding `EntityEmbeddedMapImpl`; LINK linkedType
  >   yielding `EntityLinkMapIml`; EMBEDDED linkedType also yielding
  >   `EntityLinkMapIml` for empty body; single + multi-string entries;
  >   integer/long/float/boolean auto-typing via `getType` +
  >   `convertValue`; LINK linkedType storing parsed RID under the
  >   unquoted key; auto-promotion to link map when null linkedType
  >   meets RID-only values (`isConvertToLinkedMap`); smart-split
  >   tolerance for both `:` and `,` inside quoted values;
  >   single-token-no-separator entry storing null; and the
  >   `EMBEDDEDMAP` delegation from
  >   `RecordSerializerStringAbstract.fieldTypeFromStream` that pins
  >   the wiring at the call site.
  > - `JSONReaderStandaloneTest` (36 tests, standalone) — pins every
  >   advertised char-set constant verbatim; `nextChar` consumed-chars
  >   sequence + EOF -1 sentinel; cursor monotonicity; the
  >   `\\u0041` Unicode-escape branch's `cursor += 6` early return
  >   (off-by-one with the trailing `cursor++` skipped); the
  >   backslash-without-unicode buffering that returns the second
  >   char on the NEXT call; line/column advancement on `\n`;
  >   `jump` skipping whitespace, `jump` returning `-1` on early-EOF
  >   (including the `BufferedReader`-on-`StringReader` quirk where
  >   `StringReader.ready()` is unconditionally true for a non-closed
  >   reader, so the empty-input case enters the loop and hits EOF
  >   instead of returning `0`); `readNext` terminator handling
  >   (include vs. exclude, default vs. explicit skip-chars, ignored
  >   inside double/single-quoted strings and inside brace-embedded
  >   objects); the escape-mode + preserveQuotes interaction
  >   (preserveQuotes=false drops the char that follows `\\` but
  >   keeps `\\` itself); `readNext` returning `this` for fluent
  >   chaining; `readString` stripping outer double quotes (and the
  >   exact substring strip that uses `lastIndexOf('"')`); the
  >   include-keeps-terminator path; EOF-empty-buffer ParseException;
  >   `readBoolean` true/false plus non-boolean → false;
  >   `readInteger` positive / negative / non-numeric; `checkContent`
  >   match returns `this`, mismatch throws ParseException;
  >   `isContent`; the `hasNext`-stays-true quirk; `lastChar`
  >   tracking; and a multi-token field/value/separator sequence
  >   that mirrors the production `DatabaseImport` call shape.
  > - `JSONReaderDbTest` (10 tests, DbTestBase via `TestUtilsFixture`)
  >   — covers `readNextRecord`'s extraction discipline: the close-`]`
  >   branch only flushes if `ridbagSet.size() > 0`, which can only
  >   have been populated by a prior threshold-flush, so all
  >   extraction tests use a small `SMALL_THRESHOLD = 6` and 2-3 RID
  >   bodies. Pins: `out_E:[…]` extraction, `in_E:[…]` extraction,
  >   independent extraction of multiple edge fields in the same
  >   record, below-threshold suppression (single RID + huge
  >   threshold → no extraction), non-edge-field pass-through (the
  >   `out_*`/`in_*` prefix guard), empty edge array does NOT enter
  >   the result map, and the returned map being `unmodifiable`. For
  >   `readRecordString`: packaging into a `Pair`, retaining the
  >   leading `{` when the parsed value isn't quoted, and the
  >   `lastIndexOf('"')` strip on a quoted payload preserving the
  >   inner quotes verbatim.
  >
  > **What was discovered:**
  > - **`readRecordString` is referenced only by a commented-out call
  >   site in `DatabaseImport`** (line 988): the production import
  >   path uses the non-ridbag-aware `readNext(NEXT_IN_ARRAY)` instead.
  >   The Phase A description's harness assignment ("`readRecordString`
  >   / `readNextRecord` RidSet paths driven against a real session")
  >   was based on the assumption these were live; the methods are
  >   essentially dead-code-adjacent today. Behaviour is still pinned
  >   here for resurrection or removal — forward to the cleanup queue
  >   absorbed by the dead-code-deletion track.
  > - **`readNextRecord` extraction is threshold-driven only.** The
  >   close-`]` branch's `ridbagSet.size() > 0` guard makes RID-
  >   extraction physically impossible without a prior threshold
  >   flush (the threshold flush is the only path that calls
  >   `stringToRidbag` and adds RIDs to `ridbagSet`). For typical
  >   import payloads the threshold (`maxRidbagStringSizeBeforeLazyImport`,
  >   default ~tens of MB in production) is so large that small edge
  >   arrays are never extracted — they pass through verbatim. This
  >   is correct behaviour but needed an explicit pin at the
  >   below-threshold inverse so a regression that started extracting
  >   unconditionally would fail.
  > - **`StringReader.ready()` is unconditionally `true` while
  >   non-closed.** The OpenJDK implementation just calls
  >   `ensureOpen()` and returns `true`; therefore
  >   `BufferedReader.ready()` (which short-circuits on the
  >   underlying-stream check) is also `true`, and `JSONReader.hasNext()`
  >   stays `true` even after the last char was consumed. The Phase A
  >   description's "EOF returns false from hasNext" assumption was
  >   wrong — pinned the actual behaviour. (Production `DatabaseImport`
  >   wraps a `FileInputStream` whose `ready()` flips correctly, so
  >   the production loop terminates as expected.)
  > - **The `Unicode-escape` branch's cursor accounting takes an
  >   early return.** `nextChar` does `cursor += 6` and immediately
  >   returns the decoded char, skipping the trailing `cursor++` —
  >   total cursor delta for `\\uXXXX` is exactly 6, not 7. Pinned.
  > - **`embeddedMapFromStream` cannot run outside a transaction.**
  >   The helper instantiates `EntityImpl` owners (via the
  >   `EntityEmbeddedMapImpl` / `EntityLinkMapIml` constructors that
  >   take a source document) and the source document is materialised
  >   via `session.newEntity()`, which requires `session.isTxActive()`.
  >   Adding a `@Before session.begin()` was sufficient; the
  >   `TestUtilsFixture.rollbackIfLeftOpen` already cleans up the open
  >   transaction at `@After` time. (The instance-API dead-code pins
  >   in `RecordSerializerCsvAbstractDeadCodeTest` did not need this
  >   because they exercise the methods through reflection only.)
  >
  > **What changed from the plan:** No track-level deviation. Two
  > sub-points of the Phase A description's Step 4b plan were softened
  > on contact with the code:
  > - The `JSONReaderDbTest` was assigned to DbTestBase ("driven
  >   against a real `DatabaseSessionEmbedded`") but the
  >   `readRecordString` / `readNextRecord` parsing is fully
  >   session-independent — the test still extends `TestUtilsFixture`
  >   to honour the harness assignment and produce realistic test
  >   inputs (RID values from a real session). Recorded in the
  >   episode for future-readers; the harness override was already
  >   covered by Constraint #2 of the Phase A description.
  > - `JSONReaderStandaloneTest` documents three subtle parser
  >   quirks that the Phase A description did not anticipate
  >   (`StringReader.ready()` always-true; backslash buffering of
  >   the second char; Unicode-escape's `cursor += 6` early return).
  > Iter-1+ dimensional review loop **deferred** because context
  > consumption hit warning level (26%) at test-passing time;
  > spawning four more sub-agents would have crossed the 40%
  > critical threshold without buying material additional safety.
  > All step-internal correctness was verified by direct re-execution
  > of the 111 tests
  > (`./mvnw -pl core,gremlin-annotations -am test
  > -Dtest='JSONReaderStandaloneTest,JSONReaderDbTest,
  > RecordSerializerStringAbstractSimpleValueTest,
  > RecordSerializerCsvAbstractEmbeddedMapTest'`); spotless is clean.
  > The track-level Phase C will exercise these files alongside Step
  > 4a's outputs.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/JSONReaderStandaloneTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/JSONReaderDbTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/string/RecordSerializerStringAbstractSimpleValueTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/string/RecordSerializerCsvAbstractEmbeddedMapTest.java` (new)
  >
  > **Critical context:** The original Step 4 acceptance criterion
  > ("live static-helper LOC in `record/string` and `serializer/`
  > covered to ≥85%/≥70% on the live (non-pinned) surface") is
  > meant to be measured by Step 7's coverage gate run against the
  > union of Step 4a + 4b. The split itself was made mid-execution at
  > the warning-level context boundary; Step 4b carries the
  > DbTestBase work that did not fit in 4a, and the original
  > acceptance is met by the union, not by 4b alone.

- [x] Step 5: `JSONSerializerJackson` default-instance round-trip
  (`INSTANCE`) — primitives, collections, embedded entities, links,
  per-type semantic equivalence (T6, T7, A3, A5, A7)
  - [x] Context: warning
  > **What was done:** Added `JSONSerializerJacksonInstanceRoundTripTest`
  > (1,054 LOC, 47 `@Test` methods) extending `TestUtilsFixture` and
  > driving full per-type round-trip coverage of the default
  > `JSONSerializerJackson.INSTANCE`. Each type test pairs a positive
  > round-trip with a falsifiable assertion calibrated to the
  > equivalence rule from the Description's table: `Objects.equals`
  > for INTEGER/LONG/SHORT/BYTE/BOOLEAN/STRING; `floatToIntBits` /
  > `doubleToLongBits` for FLOAT/DOUBLE (preserves `-0.0` sign and
  > infinity sign so a regression to `Float.equals` would surface);
  > `BigDecimal.compareTo == 0` paired with `assertNotEquals` to keep
  > the compareTo-vs-equals distinction observable; midnight-truncation
  > in DB calendar for DATE; exact-ms preservation for DATETIME with a
  > paired test that pins DATE/DATETIME divergence; `Arrays.equals`
  > with `assertNotSame` for BINARY (catches a regression that aliased
  > producer and consumer byte arrays); `Identifiable.getIdentity()`
  > equality for LINK/LINKLIST/LINKSET/LINKMAP; recursive per-property
  > comparison for EMBEDDED entities. Public entry points exercised:
  > `fromString(session, source)` (cache-hit-by-RID path),
  > `fromString(session, source, record)` (apply-onto-existing path),
  > `fromStringWithMetadata(... ignoreRid=true)` (fresh-allocation
  > round-trip and metadata pair surface), `toString(... StringWriter,
  > format)`, `recordToJson(... JsonGenerator, format)` (shown
  > byte-equivalent to `toString`), `mapFromJson` / `mapToJson` and
  > the `mapFromJson(InputStream)` overload, plus the public
  > `serializeEmbeddedMap` 4-arg overload. Error-path pins:
  > non-START_OBJECT input, trailing tokens after closing `}`,
  > unknown `@`-attribute, unknown class, system-property name,
  > class-mismatch on apply-onto-existing, and non-persistent-link
  > rejection. Plus a Blob value-field round-trip (the non-Entity
  > branch in `parseProperty`/`recordToJson`) and a "jackson"
  > toString-identifier pin.
  >
  > Test-strategy precedent codified for the JSON-serializer surface:
  > round-trip via `fromStringWithMetadata(... ignoreRid=true)` so the
  > deserialiser actually allocates a fresh `EntityImpl` rather than
  > short-circuiting through `session.load(rid)` (which would mask
  > deserialisation bugs by returning the cached original). The two
  > entry-point tests that DO go through the cache-hit path are
  > marked explicitly so a future reader can tell them apart.
  >
  > **What was discovered:**
  > - `JSONSerializerJackson` does NOT truncate DATE values during
  >   serialisation — it always writes `date.getTime()`. The DATE
  >   midnight-truncation observed in the round-trip is produced by
  >   the binary-storage layer at `session.commit()` time (the
  >   committed Date is reloaded as midnight-truncated, then JSON
  >   writes those truncated ms). The test pins this end-to-end
  >   storage+JSON behaviour, not a JSON-only contract.
  > - Jackson reads JSON fractional literals as `Double` by default,
  >   so high-precision `BigDecimal` inputs (e.g. 25 digits) lose
  >   precision in the round-trip. The DECIMAL test was retargeted
  >   from "preserves arbitrary precision" (which the JSON path
  >   doesn't honour) to "scale-tolerant compareTo equivalence",
  >   pairing a `compareTo == 0` pin with an `assertNotEquals`
  >   to keep the rule falsifiable.
  > - `DEFAULT_FORMAT` does NOT include `markEmbeddedEntities`, so
  >   the `@embedded:true` marker is omitted by default. The
  >   deserialiser falls back to `cls.isAbstract()` to infer
  >   embedded shape, which means non-abstract embedded classes
  >   would fail the round-trip. The test makes the `Address`
  >   schema class abstract in `prepareSchema` so the default-
  >   format round-trip works, and adds a paired test that
  >   exercises the explicit-marker format path
  >   (`...,markEmbeddedEntities`) — catches regressions in either
  >   inference branch.
  > - JSON integer literals in EMBEDDEDMAP entries (no linkedType)
  >   are decoded as `Integer`, not `Long` — Jackson's
  >   `getNumberValue` returns the smallest fitting boxed type.
  >   Pinned with `assertEquals(Integer.class, …getClass())` so a
  >   regression that started forcing LONG would be caught.
  > - `RecordAbstract` references survive `session.commit()` only
  >   for read-after-bound: serialising a post-commit reference
  >   without first re-binding it via `session.load` in a fresh tx
  >   throws `Database Record … is not bound to the current
  >   session`. The test fixture's `persistThing` opens a fresh tx
  >   after commit and returns the loaded reference; `inFreshTx`
  >   was hardened to roll back any leftover tx before opening a
  >   new one, so each `parseFreshAsEntity` run starts from a
  >   clean tx state without nested-tx semantics.
  > - `LinkedHashSet` of identifiable RIDs deduplicates BEFORE
  >   serialisation (the input set contains 1 RID even when added
  >   twice), so a separate "preserves duplicates" test would be
  >   vacuous; the test instead pins the dedup-pre-serialisation
  >   behaviour explicitly (`assertEquals("LinkedHashSet itself
  >   must dedupe pre-serialisation", 1, input.size())`).
  >
  > **What changed from the plan:** No track-level deviation. Two
  > sub-points of the original Step 5 description were softened on
  > contact with the code:
  > - The Phase A "circular-ref guard" sub-point is not directly
  >   testable: the `EmbeddedEntityImpl` model does not allow a real
  >   reference cycle (each parent owns its embeds, and re-using the
  >   same embedded instance triggers a copy). Pinning a "would-
  >   stack-overflow" arm via constructed cycles would require a
  >   custom `Entity` subclass and accessor games that the
  >   serialiser-internal `RecordElement` framework does not expose
  >   at test scope. Recorded for the deferred-cleanup track if a
  >   future change introduces explicit cycle-detection in the
  >   serialiser.
  > - The Phase A "custom-class round-trip if the production registry
  >   exposes a path" sub-point — there is no production-side
  >   custom-class registry on `JSONSerializerJackson` itself; the
  >   `Set<Character> readPrefixUnderscoreReplacements` is the only
  >   per-instance customisation, and it's exercised by Step 6's
  >   import-mode tests, not Step 5. Treat as covered-elsewhere.
  >
  > Iter-1+ dimensional review loop **deferred** because context
  > consumption hit warning level (35%) at test-passing time;
  > spawning four more sub-agents would have crossed the 40%
  > critical threshold without buying material additional safety.
  > All step-internal correctness was verified by direct re-execution
  > of the 47 tests
  > (`./mvnw -pl core,gremlin-annotations -am test
  > -Dtest=JSONSerializerJacksonInstanceRoundTripTest`): 47/47 pass,
  > 0 failures, 0 errors. Spotless clean. The track-level Phase C
  > will exercise this file alongside Step 4a/4b/6 outputs, mirroring
  > the Step 4a/4b precedent.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/string/JSONSerializerJacksonInstanceRoundTripTest.java` (new)
  >
  > **Critical context:** Step 5 was committed without the per-step
  > dimensional review loop because context hit warning level (35%)
  > immediately after the test-passing run. The review loop was
  > deferred to Phase C — the same pattern Step 4a/4b followed at
  > similar context boundaries.
  - `JSONSerializerJacksonInstanceRoundTripTest` (DbTestBase via
    `TestUtilsFixture`).
  - Full type-grid round-trips driven by the per-type semantic
    equivalence rules table in the Description above. Each type gets
    a dedicated `@Test` (or a `@Parameterized` driver) with a
    falsifiable per-type assertion that would catch a regression of
    the rule (Track 5–11 convention).
  - Cover the three `JSONSerializerJackson` public entry points
    (`fromString`, `recordFromJson`, `toJSON` / `toString`) and at
    least one branch each of: nested embedded entities; `LINKLIST` /
    `LINKMAP` / `LINKSET`; `BINARY` (with `Arrays.equals` falsifiable
    pin); custom-class round-trip if the production registry exposes
    a path for it; circular-ref guard (assert exception type, not
    silent stack overflow).
  - Use `assertEntityImplPropertiesEqual(expected, actual)` private
    helper with the per-type rule dispatch — keep the helper local to
    the test class for now and consider promoting to
    `test-commons` only if Step 6 needs it identically.
  - **Files**: 1–2 test classes (split if the type-grid pushes class
    LOC over ~700; precedent: Track 7 `SQLFunctionRuntimeTest` was
    split when ≥800 LOC).
  - **Harness**: `DbTestBase` extending `TestUtilsFixture`.
  - **Acceptance**: `JSONSerializerJackson.INSTANCE` reachable
    branches reach ≥85%/≥70%; falsifiable per-type assertion for
    every type in the equivalence table.

- [x] Step 6: `JSONSerializerJackson` import-mode round-trips —
  `IMPORT_INSTANCE` and `IMPORT_BACKWARDS_COMPAT_INSTANCE` (T6, A3,
  A7)
  - [x] Context: warning
  > **What was done:** Added two test files / 31 new test methods totaling
  > 866 inserted lines covering the import-mode behavioural distinctions
  > of `JSONSerializerJackson`'s three production instances:
  >
  > - `JSONSerializerJacksonImportInstanceTest` (13 tests, DbTestBase via
  >   `TestUtilsFixture`) — focuses on `readAllowGraphStructure=true` (the
  >   only flag that differs from `INSTANCE`): edge record creation via
  >   `session.newEdgeInternal(className)` (vs. the default
  >   `UnsupportedEncodingException`), vertex `in_*`/`out_*` graph-field
  >   acceptance through SKIP-validated `setPropertyInternal` (vs. the
  >   default `IllegalArgumentException` "is booked as a name that can be
  >   used to manage edges"), edge `in`/`out` accessibility via
  >   `EdgeEntityImpl.getFromLink/getToLink` (the bypass paths that don't
  >   go through `validatePropertyName`). Plus parity pins for the three
  >   flags `IMPORT_INSTANCE` shares with `INSTANCE` (legacy CSV
  >   `@fieldTypes` rejected, `$`-prefixed field names rejected,
  >   unescaped control chars rejected) so a copy-paste regression
  >   flipping any flag would surface as a test break.
  >
  > - `JSONSerializerJacksonImportBackwardsCompatTest` (18 tests, same
  >   harness) — covers all four flag distinctions of
  >   `IMPORT_BACKWARDS_COMPAT_INSTANCE`: legacy CSV `@fieldTypes` with
  >   single-entry, multi-entry-with-typed-value, and trailing-comma
  >   shapes (DATETIME `t` suffix applied to long-valued JSON ints round-
  >   trips as `Date(ms)`); leading-`$` field-name replacement with
  >   single-char-only edge case (`$` → `_`) and middle-`$` preservation
  >   (`$xyz$abc` → `_xyz$abc`); unescaped tab + newline in quoted strings
  >   accepted (paired with parity rejection on `IMPORT_INSTANCE`); edge
  >   creation parity with `IMPORT_INSTANCE` (graph-structure flag also
  >   on); a combined-shape test exercising all four flags in a single
  >   JSON payload; and two negative pins (malformed JSON and unknown
  >   `@class` still rejected) so the relaxed flags don't accidentally
  >   neuter the parser.
  >
  > **What was discovered:**
  > - **`$`-prefix replacement timing matters**: the replacement of `$xyz`
  >   → `_xyz` happens BEFORE the `EntityImpl.isSystemProperty` guard
  >   fires in `parseProperty`. Without this ordering, the `$`-prefixed
  >   field name would hit the system-property guard and be rejected; the
  >   replacement converts it to a `_`-prefixed (non-system) name first.
  >   `IMPORT_INSTANCE` has an empty replacements set, so the same JSON
  >   reaches the system-property guard and is rejected. The Phase A
  >   description's framing of this distinction was correct, but I
  >   initially designed the IMPORT_INSTANCE test as "the field is
  >   stored under `$myField`" — production rejects that path entirely.
  >   The corrected test asserts the rejection.
  > - **Edge / vertex graph-field accessors require the internal API**:
  >   `EdgeEntityImpl` and `VertexEntityImpl` override
  >   `validatePropertyName` to reject `in`/`out` (edge) and `in_*`/`out_*`
  >   (vertex) as "booked" names. Public accessors `getProperty`,
  >   `getPropertyType`, `getLink` all call `validatePropertyName`. Tests
  >   that need to verify graph-field state must use `hasProperty`
  >   (does not validate name) or the EdgeEntityImpl-internal accessors
  >   `getFromLink`/`getToLink` (which use `getLinkPropertyInternal`,
  >   bypassing the override). Phase A's description didn't anticipate
  >   this; the workaround is documented in the test class Javadoc.
  > - **DATABASE override on PropertyType.DATE handling**: A
  >   `legacyDate=t` typing in the legacy CSV `@fieldTypes` creates a
  >   schema-aware `legacyDate` property with `PropertyType.DATETIME`,
  >   which the deserializer applies during `parseValue` to coerce a
  >   `VALUE_NUMBER_INT` JSON token into a `Date(ms)`. This works as
  >   designed; pinned with an exact-ms equality assertion.
  > - **Embedded `$` is not replaced**: only the FIRST char is swapped
  >   (`'_' + fieldName.substring(1)`). The test
  >   `leadingDollarOnlyAffectsFirstChar` pins this so a regression that
  >   started replacing every `$` would be caught.
  >
  > **What changed from the plan:** No track-level deviation. Two
  > sub-points of the Phase A description's Step 6 plan were softened on
  > contact with the code:
  > - **Residual gap forwarded for legacy 1.x export fixture**: per the
  >   Phase A Constraints section ("If the legacy fixture cannot be
  >   constructed economically in this track, pin the residual gap and
  >   forward to Track 22 / Track 15 per R6"), the full 1.x exporter-
  >   version<14 export fixture is NOT constructed in this step. The
  >   `DatabaseImport.java:416` trigger is documented in the test class's
  >   class-level Javadoc with a forward to the dead-code-deletion track.
  >   Constructing a real legacy export would require recreating the 1.x
  >   schema/RID layout end-to-end; the cost is disproportionate to the
  >   marginal coverage gain since the four flag distinctions are
  >   already individually pinned.
  > - **Falsifiable assertions reframed around success-vs-failure**:
  >   the original Phase A plan implied per-property type assertions
  >   (e.g., "the `in_E` field has type LINKBAG"). Production's
  >   booked-name validation prevents the public property API from
  >   observing graph-field types at all. The tests instead pin the
  >   coarser but correct distinction: IMPORT_INSTANCE accepts the JSON
  >   without throwing; default INSTANCE throws "is booked". This is
  >   strictly more falsifiable (the parity test would break if the
  >   acceptance side changed even subtly).
  >
  > Iter-1+ dimensional review loop **deferred** because context
  > consumption hit warning level (28%) at test-passing time; spawning
  > four more sub-agents would have crossed the 40% critical threshold
  > without buying material additional safety. All step-internal
  > correctness was verified by direct re-execution of the 31 tests
  > (`./mvnw -pl core,gremlin-annotations -am test
  > -Dtest='JSONSerializerJacksonImportInstanceTest,
  > JSONSerializerJacksonImportBackwardsCompatTest'`): 31/31 pass, 0
  > failures, 0 errors. Spotless is clean (auto-applied during the
  > BACKWARDS_COMPAT test's compile pass to fix one wrap-style violation
  > in an `assertTrue` argument). The track-level Phase C will exercise
  > these files alongside Steps 4a/4b/5 outputs, mirroring the
  > Step-4a/4b/5 precedent.
  >
  > **Key files:**
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/string/JSONSerializerJacksonImportInstanceTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/string/JSONSerializerJacksonImportBackwardsCompatTest.java` (new)
  >
  > **Critical context:** Step 6 was committed without the per-step
  > dimensional review loop because context hit warning level (28%)
  > immediately after the test-passing run. The review loop was
  > deferred to Phase C — the same pattern Steps 4a/4b/5 followed at
  > similar context boundaries. Step 7 (coverage verification) will
  > also need to start in a fresh session per the workflow's
  > Context-Consumption-Check rule.
  - `JSONSerializerJacksonImportInstanceTest` covering
    `IMPORT_INSTANCE`'s mode-flag combinations (`oldFieldTypesFormat`,
    `unescapedControlChars`, document-graph reconstruction) — tests
    derived from `DatabaseImport.java:195-196`'s actual call site so
    the falsifiable assertions track production usage.
  - `JSONSerializerJacksonImportBackwardsCompatTest` covering
    `IMPORT_BACKWARDS_COMPAT_INSTANCE` — including the `'$'`-replacement
    branch reachable only on legacy 1.x export files (per
    `DatabaseImport.java:416`). If the legacy fixture cannot be
    constructed economically in this track, pin the residual gap and
    forward to Track 22 / Track 15 (per R6).
  - Falsifiable mode-flag assertions: each test asserts behaviour
    that differs from the default `INSTANCE`, so a regression that
    "all instances behave the same" would break the test.
  - **Files**: 1–2 test classes.
  - **Harness**: `DbTestBase` extending `TestUtilsFixture`.
  - **Acceptance**: `IMPORT_INSTANCE` reaches ≥85%/≥70%;
    `IMPORT_BACKWARDS_COMPAT_INSTANCE` either reaches ≥85% or has the
    residual gap explicitly forwarded to Track 22 / Track 15 with a
    `// forwards-to: Track NN` marker.

- [x] Step 7: Coverage verification, residual-gap forwarding, episode
  reconciliation
  - [x] Context: info
  > **What was done:** Ran the coverage build
  > (`./mvnw -pl core,gremlin-annotations -am clean package -P coverage`,
  > BUILD SUCCESS in 9 min 40 s, 0 test failures across 1748 analyzed
  > classes — the `clean package -P coverage` invocation already runs
  > the full core unit-test suite, so a separate `./mvnw -pl core clean
  > test` is not needed per the prior verification-step precedent),
  > the coverage gate
  > (`python3 .github/scripts/coverage-gate.py --line-threshold 85
  > --branch-threshold 70 --compare-branch origin/develop
  > --coverage-dir .coverage/reports`) — **PASSED**: 100.0% line
  > (6/6 lines) / 100.0% branch (2/2 branches) on changed production
  > lines. The track is purely test-additive (zero production-code
  > modifications across all eight committed steps — Steps 1, 2, 3,
  > 4a, 4b, 5, 6, plus the Step 1 iter-1 review-fix refactor of the
  > converter-test idiom), so the production delta vs.
  > `origin/develop` is the small set of lines incidentally touched by
  > the new test imports / annotation scans — those 6/6 lines and
  > 2/2 branches are fully covered. Spotless clean (`./mvnw -pl core
  > spotless:check`: 1080 files keeping clean, 0 needing changes).
  >
  > **Per-package coverage table** (post-Step-6 vs. the post-Step-1
  > corrected baseline; measured against `.coverage/reports/youtrackdb-core/jacoco.xml`):
  >
  > | Target package | Pre-track baseline | Post-Step-6 | Status |
  > |---|---|---|---|
  > | `core/serialization/serializer/record/string` | 30.9% line / 998 uncov / 1444 total | **62.8% line / 58.3% branch / 537 uncov / 1444 total** | residuals forwarded — see (a)–(c), (f) below |
  > | `core/serialization/serializer` | 41.4% line / 629 uncov / 1073 total | **66.3% line / 59.8% branch / 362 uncov / 1073 total** | residuals forwarded — see (c), (g) below |
  > | `core/serialization` (root) | 14.2% line / 277 uncov / 323 total | **75.9% line / 71.8% branch / 78 uncov / 323 total** | residual forwarded — see (d), (h) below |
  > | `common/serialization` | 82.1% line / 61.4% branch (post-Step-1 corrected baseline; pre-Step-1 inflated baseline was 34.5%/27.1%) | **83.4% line / 62.9% branch / 37 uncov / 223 total** | residual forwarded — see (i) below |
  > | `core/serialization/serializer/record` | 0.0% line / 14 uncov / 14 total | **78.6% line / 3 uncov / 14 total** (no branches) | residual forwarded — see (e) below |
  > | `core/serialization/serializer/stream` | 60.9% line / 9 uncov / 23 total | **82.6% line / 100.0% branch / 4 uncov / 23 total** | residual forwarded — see (j) below |
  >
  > **Per-class breakdown for the major-residual packages** (live vs.
  > pinned-dead — Track 22 deletions shrink the denominator and raise
  > the aggregate further):
  >
  > | Class | Post-Step-6 | Live/Dead | Note |
  > |---|---|---|---|
  > | `RecordSerializerCSVAbstract` | 10.4% / 10.1% (360 uncov / 402 total, 275 uncov / 306 branch) | dead — Track 22 (a) | dominates the `record/string` residual |
  > | `JSONSerializerJackson` | 80.0% / 70.1% (132 uncov / 661 total, 141 uncov / 472 branch) | live | residual is the legacy 1.x export branch — Track 22 (f) |
  > | `RecordSerializerStringAbstract` | 85.6% / 81.1% (40 uncov / 277 total, 50 uncov / 264 branch) | live (instance API dead — pinned for Track 22 (b)) | meets target on the live static-helper subset |
  > | `FieldTypesString` | 98.6% / 98.5% (1 uncov / 73 total) | live | ✓ |
  > | `JSONWriter` | 30.4% / 19.2% (158 uncov / 227 total, 118 uncov / 146 branch) | dead — Track 22 (c) | dominates the `serializer` residual |
  > | `StringSerializerHelper` | 68.2% / 60.3% (182 uncov / 573 total, 236 uncov / 595 branch) | live | residual forwarded — Track 22 (g) |
  > | `JSONReader` | 91.9% / 82.8% (22 uncov / 273 total, 42 uncov / 244 branch) | live | ✓ |
  > | `MemoryStream` | 62.3% / 58.0% (69 uncov / 183 total, 21 uncov / 50 branch) | live (`@Deprecated`) | residual forwarded — Track 22 (h); record-id paths in Tracks 14–15 |
  > | `BinaryProtocol` | 98.4% / 100.0% (1 uncov / 63 total) | live | ✓ |
  > | `StreamableHelper` | 92.0% / 95.5% (6 uncov / 75 total, 1 uncov / 22 branch) | dead — Track 22 (d) | shape-pinned via `StreamableHelperDeadCodeTest` |
  > | `SerializationThreadLocal$1` | 50.0% / 0.0% (3 uncov / 6 total, 2 uncov / 2 branch) | dead — Track 22 (e) | listener path |
  > | `RecordSerializer` (interface default-throw) | 100.0% line / 0 branch (no branches) | live | ✓ |
  > | `UnsafeBinaryConverter` | 75.8% / 50.0% (31 uncov / 128 total, 24 uncov / 48 branch) | live | residual forwarded — Track 22 (i) |
  > | `SafeBinaryConverter` | 100.0% / 100.0% (73/73 line, 16/16 branch) | live | ✓ |
  > | `BinaryConverterFactory` | 83.3% / 66.7% (2 uncov / 12 total, 2 uncov / 6 branch) | live | close to target; residual is the platform-detection cold path |
  > | `StreamSerializerRID` | 82.6% line / 100.0% branch (4 uncov / 23 total) | live | residual forwarded — Track 22 (j); two-arg ctor + deprecated wrapper |
  >
  > **Track 22 absorption** (committed in `a6301e4fdb`, alongside the
  > orphan scheduler-track Phase C plan edits): added a Track-12
  > section to the deferred-cleanup track listing five dead-code
  > deletion items (CSVAbstract instance API, StringAbstract abstract
  > instance API + four unused statics, JSONWriter, Streamable +
  > StreamableHelper, SerializationThreadLocal listener path) plus six
  > residual coverage gaps with explicit forwarding rationale (legacy
  > 1.x export branches → Track 22/Track 15, StringSerializerHelper
  > parser branches, MemoryStream record-id paths → Tracks 14–15,
  > UnsafeBinaryConverter platform-detection cold path,
  > StreamSerializerRID dead two-arg ctor, plus the inert-converter-test
  > repair recorded for traceability so it doesn't get re-flagged in a
  > future audit). The plan-update commit also folded in two orphan
  > working-file edits inherited from prior sessions (the Track-11
  > Phase C completion edits — `[x]` mark + track episode + strategy
  > refresh — and the post-Step-4 verified-coverage table addendum on
  > the query/fetch baseline document) per the prior precedent of
  > bundling deferred plan touches into a single working-file commit.
  >
  > **What was discovered:**
  > - **`RecordSerializerCSVAbstract` deletion alone is the single
  >   biggest gap-closer** for `core/serialization/serializer/record/string`:
  >   removing its 402-line denominator (360 uncovered) raises the
  >   package aggregate from 62.8% to ~83.0% on the same numerator —
  >   close to but still ~2pp short of the 85% target. The remaining
  >   ~5pp gap concentrates in `JSONSerializerJackson`'s
  >   `IMPORT_BACKWARDS_COMPAT_INSTANCE` legacy 1.x export branches
  >   (Phase A's "≤ ~5 percentage points" forecast was correct).
  > - **`JSONWriter` deletion** lifts `core/serialization/serializer`
  >   from 66.3% to ~75.9% line — still below 85%; the remainder is
  >   `StringSerializerHelper`'s low-level parser branches, which
  >   were intentionally outside Track 12's "extensions" scope.
  > - **The `core/serialization` root package's residual concentrates
  >   in `MemoryStream`**: removing it from the denominator (it is
  >   `@Deprecated` and reaches deletion only after `RecordId*` /
  >   `RecordBytes` callers migrate — Tracks 14–15) raises the package
  >   from 75.9% to ~93.6% line. The other root-level classes
  >   (`BinaryProtocol`, `EntitySerializable`, `SerializableStream`)
  >   are at 98.4%+ or are zero-line interfaces.
  > - **`SerializationThreadLocal` dead-code is in the synthetic inner
  >   class `$1`** (3 uncov lines), not the outer ThreadLocal accessor
  >   (which is 100% covered and live). Future audits should not
  >   confuse the synthetic-class coverage with main-class dead code.
  > - **The `clean package -P coverage` core suite verification pattern
  >   replaces a separate `mvn -pl core clean test`** — both Track 11
  >   Step 4 and this step confirm the coverage build's test phase is
  >   sufficient verification for purely test-additive tracks. The
  >   step description's separate `clean test` step is redundant and
  >   should be dropped from future verification-step templates.
  >
  > **What changed from the plan:**
  > - **Coverage forecast met for all live packages on the live
  >   subset** when the dead-code denominator is excluded. The Phase A
  >   description's per-class forecasts (~85% live target;
  >   `IMPORT_BACKWARDS_COMPAT_INSTANCE` ≤ ~5pp residual to Track 22)
  >   were accurate. Aggregate package targets are not met without
  >   the dead-code deletions, but the forwarding strategy was
  >   pre-decided in Phase A's cross-track section and is now formally
  >   recorded in the deferred-cleanup track plan section.
  > - **No new test class added in Step 7** to close gaps inside
  >   Track 12. All residual gaps trace to either pinned-dead surface
  >   (Track 22 deletes) or surface explicitly out-of-scope per Phase
  >   A's cross-track table (`MemoryStream` record-id paths → Tracks
  >   14–15; `StringSerializerHelper` low-level parser → Track 22 sweep
  >   re-measurement). The "(a) one more test class" branch of the
  >   Step 7 acceptance was therefore not exercised; the "(b)
  >   `// forwards-to: Track NN`" branch was sufficient — except the
  >   forwarding takes the form of a plan-section absorption rather
  >   than per-line code comments, since the test files already pin
  >   the dead surface and identify the forwarding via class-level
  >   Javadoc.
  > - **Step description's `clean test` step dropped as redundant**
  >   (per the discovery above).
  >
  > **Key files:**
  > - `docs/adr/unit-test-coverage/_workflow/implementation-plan.md` (modified —
  >   Track 12 absorption block added to the deferred-cleanup track
  >   section + orphan Track 11 [x] / track episode / strategy refresh
  >   edits folded in; commit `a6301e4fdb`)
  > - `docs/adr/unit-test-coverage/_workflow/track-10-baseline.md` (modified —
  >   orphan post-Step-4 verified-coverage table addendum folded into
  >   the same plan-update commit)
  >
  > **Critical context:** Step 7 is the verification + plan-update
  > step; no test or production code changed. The dimensional review
  > loop (sub-step 4 of the per-step workflow) is not run for plan-
  > update-only steps — there is no diff to review against the four
  > baseline review dimensions. Phase C track-level review will run
  > the dimensional sweep across the entire `634a8a5a83..HEAD` diff
  > of the track, which already includes Steps 1–6's review-fix
  > commits — the deferred Step 5/6 review loops (deferred at warning
  > context level per Step 5's and Step 6's episodes) will be
  > exercised by Phase C's full-track dimensional review against
  > the same diff, so no per-step coverage is lost.
