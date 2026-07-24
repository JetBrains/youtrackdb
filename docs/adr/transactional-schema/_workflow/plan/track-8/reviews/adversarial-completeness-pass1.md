# Adversarial Pre-Implementation Design Review — Pass 1: Completeness & Consistency

**Artifact under review:** `docs/adr/transactional-schema/_workflow/plan/track-8-design-drafts.md`
**Repo state:** HEAD `6913cd321d`, branch `transactional-schema`. The draft grounds itself at
`d664589d7f`; `git diff d664589d7f..HEAD -- core/src/main/java` is empty for every cited file, so
citations verified below hold at both SHAs identically.
**Charter:** design completeness & consistency — spec coverage, internal consistency, assumption
grounding (citation spot-check), migration compat-matrix completeness, blast-radius honesty (R3),
executability. Finding prefixes: WI (completeness), WC (consistency/cross-reference).
**Rulings discipline honored:** §0 R1–R4 and the 2026-07-23 Rulings (Q-G1..Q-G3, Q-M1..Q-M3,
Admin) are treated as settled inputs. No finding below re-litigates a ruling; findings target what
the design misses or contradicts *within* them.

---

## 1. Decision criteria and premises

**Decision criteria.** A finding is registered iff at least one of: (C1) a spec obligation
(implementation-plan D18/D20, I-U4, I-migration-*; design.md §Genesis / Part 4; track-8.md frozen
Plan of Work + Validation bullets) has no mapped design element; (C2) two statements inside the
draft-plus-rulings corpus, or between the draft and a frozen spec artifact, cannot both be true
without an unrecorded supersession; (C3) a file:line citation is wrong or materially stale at
HEAD; (C4) a cell of the migration input space has no defined outcome, so an implementer would
improvise; (C5) a de-risking or decomposition step cannot be executed as written without guessing.
Severity: **blocker** = implementation cannot proceed on this point without improvising a
correctness-relevant outcome, or a `[verified]` grounding claim is falsified; **should-fix** = a
concrete gap/contradiction an amendment must close before decomposition; **suggestion** =
hygiene/precision.

**Numbered premises** (each verified by reading, citations in §3):

- P1. The draft's two-way strictness keying is R1: `< 15` lenient (byte-for-byte preserved),
  `>= 15` strict (draft §0 R1, M.1, M2.b intro, FM-M12).
- P2. The Q-M2 ruling refines the dispatch three ways: `<= 14` lenient, `== 15` strict, `>= 16`
  reject-with-redirect (draft Rulings §Q-M2).
- P3. At HEAD, `DatabaseImport.exporterVersion` defaults to `-1` and is assigned only when the
  info section contains an `exporter-version` field (`DatabaseImport.java:111`, `:414`).
- P4. At HEAD, `DatabaseImport.importSchema` maps the dump's `blob-collections` numeric ids into
  the **target's** id space via `session.getCollectionNameById(collection)` on the **raw dump id**,
  not through `collectionToCollectionMapping` (`DatabaseImport.java:528-541`).
- P5. R3 renumbers fresh-database collection ids: blobs move from the highest slots to `1..N`,
  class collections shift up by N (draft §0 R3, G2.b).
- P6. Genesis phase 1 (Q-G1 ruling: ONE schema tx) creates OUser/ORole/OFunction/OSequence/
  OSchedule/O/V/E classes and registers blob collections before it commits (draft G2.b/G2.c), so a
  fully created database has a non-empty class set, a non-zero collection counter, and a populated
  `blobCollections` set.
- P7. The frozen track-8.md Validation bullet 2 demands: "a complete legacy dump missing any
  expected section is refused; a legacy dump requires the explicit unverified-import
  acknowledgment flag" (track-8.md §Validation and Acceptance).
- P8. design.md Part 4 states "The documented procedure keeps the target out of service until
  verification passes" and the gotcha "the operator verifies export exit status before importing"
  (design.md:993-995, :1029-1031); `docs/` contains no export/import operator document
  (`ls docs/` → adr, getting-started.md, object-oriented.md, README.md, security.md, yql*).
- P9. The word "renumber" appears in the draft only in §0/Draft G (lines 35, 85, 126, 172,
  198-199); Draft M contains zero mention of collection-id renumbering.
- P10. Tracks 5–7 are complete at HEAD (commits "Track 06 complete", "Track 07 complete";
  `TxSchemaState.java` / `MetadataWriteMutex.java` exist), even though the
  implementation-plan.md checklist still shows Tracks 5–7 unchecked.

---

## 2. Spec coverage (charter item 1)

### 2.1 Obligation → design-element map (spec → draft)

| Obligation (source) | Draft element | Verdict |
|---|---|---|
| D18 two-phase genesis (impl-plan D18; design.md §Genesis; track-8.md PoW) | G2.c, Q-G1/Q-G2 rulings | covered |
| Genesis schema tx "seeds the tx-local copy from the empty committed schema" (design.md:504-506) | G2.a (G1 enabler; FM-G1) | covered — with WC4 wording nuance |
| Genesis schema tx engages mutex; data tx does not (I-U4; design.md:507-508; validation bullet 1) | G2.c, G.4, pins 3-4 | covered |
| "default users created through real engine lookups, not scan fallbacks" (validation bullet 1) | pin 4 (engine-before-insert, `getIndexId() >= 0`); draft flags the plan's "direct index lookup" wording as stale with code proof (`getUserInternal:1103-1117` is a planner query) | covered |
| Genesis = end-to-end smoke test (design.md gotcha :512-514) | FM-G8 | covered |
| D20 export/import migration, no in-place migrator (impl-plan D20) | M.1 scope ("Out: any in-place migrator") | covered |
| Open-time reject-and-redirect gate confirmed as redirect target (track-8.md PoW; impl-plan Integration Points) | FM-M11, pin 10, M.0 row "Open-time gate" | covered |
| `EXPORTER_VERSION` 14→15 (D20; track-8.md) | M2.a-1 | covered |
| Rethrow-by-default + best-effort opt-out recorded in info section (D20; track-8.md) | M2.a-2 | covered |
| Bounded buffer + spill-to-temp, whole-or-discarded, whole-or-fatal copy-out (D20; design.md:998-1008; validation bullet 3) | M2.a-3, FM-M2/M9, pins 2/8 | covered |
| Completion-flag-gated promote (design.md:1004-1006; track-8.md ordering constraints) | M2.a-4, FM-M3, pin 1 | covered |
| Manifest strictly last + atomic/durable promote (D20; R2; track-8.md signatures "temp+fsync+rename discipline") | M2.a-5 (R2 transfer explicit), FM-M4 | covered (test-pin gap → WI7) |
| Primary-exception preservation, "exit ≠ 0" (validation bullet 4) | M2.a-6, M.4 I-migration-failfast mapping ("no CLI in-repo — the console module is empty"); verified: `console/src` has zero `.java` files, no non-tool caller of `DatabaseExport/Import` in core/server main | covered (mapping justified) |
| Manifest verify + section presence (D20; validation bullet 2) | M2.b-3, FM-M7, pin 5 | covered for v15; **contradicted for legacy dumps → WC1** |
| Whole-stream gzip validation, single member, inflater arithmetic, exhaustion probes forbidden (D20; design.md gotcha :1032-1034; track-8.md signatures) | M2.b-2, pins 3-4 | covered |
| Non-gzip rejection on migration path (design.md gotcha :1035-1036; track-8.md PoW) | M2.b-1, Q-M3 ruling, pin 6 | covered |
| Best-effort ack gate (D20; validation bullets 2/4) | M2.a-2/M2.b-4, FM-M8, pin 7 | covered for v15; **legacy-ack clause contradicted → WC1** |
| Dangling-field parse rejection (design.md:1017-1020; validation bullet 4) | M2.b-5, FM-M10, pin 9 | covered |
| R4 info-field validation (§0 R4; Q-M2 ruling) | M2.b-5 + Rulings Q-M2 | covered in narrative; **no test pins → WI4** |
| I-U4 | G.4 + pins 3-4 | discharged |
| I-migration-fail-closed / -isolation / -failfast | M.4, each clause mapped to mechanism + FM | discharged (WC1 caveat on the legacy clauses) |
| Track 2 carry-forward "schema-version field must agree on 6" (impl-plan Track 2 episode) | M2.a-7 + Q-M2 ruling (`MIN_IMPORTABLE (=6) <= declared <= CURRENT (=6)`) | covered |
| "The documented procedure keeps the target out of service until verification passes" (design.md:993-995, :1029-1031) | **no element** — no docs deliverable in M.1/M.6/M.5 | **orphaned obligation → WI3** |
| Migration behavior for the dump's blob-collection registry under the R3-renumbered target layout (implied by R3 + D20 jointly) | **no element** in Draft M | **orphaned obligation → WI1** |

### 2.2 Draft-element → spec traceability (draft → spec)

- **G1 bootstrap payload (G2.a):** not in any frozen artifact; traceable as a discovered enabler of
  D18's seed premise, with the contradiction at HEAD proven (`SchemaShared.create:1387-1398` writes
  an empty entity; `copyForTx` assert `:300-301`). Properly grounded; not an orphan.
- **R3 blob embedding (G2.b, AbstractStorage in G.6):** traceable to ruling R3 only. The frozen
  track-8.md In-scope list and file sketch name neither `AbstractStorage` nor `SharedContext`'s
  blob loop; the Move-2 ADDED/MODIFIED/REMOVED triad must record this scope expansion → folded
  into WI8/WC5 hygiene.
- **M2.a-7 (schema-section `version` field rewrite):** not in any frozen artifact; grounded in the
  M.0 observation that the field now carries a meaningless generation token (Track 5 BC1), and
  correctly argued compatible (import never consumed it — verified, `importInfo` reads only
  `exporter-version`). Acceptable addition; record in the Move-2 triad (WI8).
- **Q-M1 spill knob (GlobalConfiguration):** ruled; but the file it must touch
  (`core/.../api/config/GlobalConfiguration.java`) is absent from M.6 → WI8.
- Everything else in both drafts traces to D18/D20/track-8.md/rulings directly.

---

## 3. Assumption grounding — citation spot-check (charter item 3)

25+ citations checked against HEAD `6913cd321d` (= `d664589d7f` for all cited production files).

| # | Draft citation | HEAD reality | Verdict |
|---|---|---|---|
| 1 | `YouTrackDBInternalEmbedded.internalCreate:773` | method at :773-776, calls `storage.create` | OK |
| 2 | `DatabaseSessionEmbedded.internalCreate:572`, `createMetadata:598-603` | `internalCreate` at :572; `createMetadata` at :598-603 calls `shared.create(this)` | OK |
| 3 | `SharedContext.create:184-221`, blob loop `:198-204` | create body matches; blob-count read at :199, loop `session.addCollection("$blob"+i)` + `schema.addBlobCollection` at :201-204 | OK |
| 4 | `SchemaShared.create:1387-1398` — empty entity, `setSchemaRecordId`, never `toStream` | exact match | OK |
| 5 | `SchemaShared.copyForTx:278-320`, assert `:300-301` "bootstrapped committed schema carrying global properties" | assert text matches at :300-301 | OK |
| 6 | `SchemaShared.fromStream:887-894` empty-root branch; `:895-904` version gate | `schemaVersion == null` error branch :887-894; `!= CURRENT_VERSION_NUMBER` reject-and-redirect :895-904 | OK |
| 7 | `SchemaShared` root payload: `blobCollections` EMBEDDEDSET `:1243-1244`, root-diff `:1309` | `setProperty("blobCollections", …, EMBEDDEDSET)` :1243-1244; `rootPayloadDiffersFrom` blob comparison :1309 | OK |
| 8 | `IndexManagerEmbedded.create:608-621` empty internal entity + `setIndexMgrRecordId` | exact match | OK |
| 9 | `IndexManagerEmbedded.java:787-791` mutex-bypass documentation | legacy-bypass comment block at :785-793 | OK |
| 10 | `SchemaProxedResource.resolveForWrite:108-117` legacy top-level outside a tx | returns `delegate` when no active tx, at :108-117 | OK |
| 11 | `SecurityShared.create:594-626`, guard `:595`, system-DB skip `:614`, `OUser.name` UNIQUE `:899`, `createDefaultRoles:628`, `createDefaultUsers:637`, `:628-656` | all match (`@Nullable`, `getClasses().isEmpty()` guard at :595; `SYSTEM_DB_NAME` skip at :614; `createIndex("OUser.name", UNIQUE…)` at :899) | OK |
| 12 | `SecurityShared.getUserInternal:1103-1117`, `getUserRID:1177-1190` — planner queries | `select from OUser where name = ?` at both sites | OK — the draft's "[verified; plan wording stale]" correction is itself correct |
| 13 | `DatabaseSessionEmbedded.addCollection:3023`, `getBlobCollectionIds:5028` | :3023 and :5028 | OK |
| 14 | `ResultInternal.java:780` blob check via schema snapshot | `schemaSnapshot.getBlobCollections().contains(…)` at :779-781 | OK |
| 15 | `AbstractStorage.doCreate:1442-1527`, `executeInsideAtomicOperation:1493`, `internal` collection `:1510` | doCreate header :1442; atomic-op call ~:1493; `doAddCollection(atomicOperation, COLLECTION_INTERNAL_NAME)` at ~:1510 | OK |
| 16 | `AbstractStorage.addCollection:1668` direct structural op | at :1666-1668 | OK |
| 17 | `AbstractStorage:7040-7044` `doCreateCollection` WAL-revert javadoc contract | "buffered as WAL-reverted intent… leaves no collection file behind" at :7038-7046 | OK |
| 18 | `SchemaProxy.addBlobCollection:460-462` routes through `resolveForWrite()` | exact match | OK |
| 19 | `DatabaseExport.java:59` `EXPORTER_VERSION = 14` | exact | OK |
| 20 | `exportDatabase:157-160` `close()` in `finally`; `close():270-301`; `atomicMoveWithFallback:291` | finally at :158-160; close() :270-301; move at :291 | OK |
| 21 | `DatabaseExport.java:212-239` generic-Exception swallow, only `YTIOException` rethrows | catch YTIOException rethrow / catch Exception log-and-continue at :212-239 | OK |
| 22 | `exportRecord:584-590` renders into shared `jsonGenerator`; catch arm `:592-615` | `recordToJson(session, rec, jsonGenerator, …)` at :586-587; catch adds to `brokenRids` :597-615 | OK |
| 23 | `exportInfo:360-386`, schema-version 6 at `:380` | `writeNumberField("schema-version", SchemaShared.CURRENT_VERSION_NUMBER)` at :380 (`CURRENT_VERSION_NUMBER = 6` at SchemaShared:71) | OK |
| 24 | `exportSchema:449-580`, `schema.getVersion()` at `:454`, blob readers `DatabaseExport.java:456` | `writeNumberField("version", schema.getVersion())` :454; `session.getBlobCollectionIds()` :456-457 | OK |
| 25 | `DatabaseImport` ctor `:136-143` silent plain-JSON fallback | `catch (Exception ignore) → reset → plain stream` at :138-143 | OK |
| 26 | `importInfo:406-424` (§0 says `:409-421`) reads only `exporter-version` | method :406-424; only `exporter-version` consumed | OK (both windows valid) |
| 27 | `importDatabase:226-242` unknown tag throws; missing section silently tolerated | switch with `default -> throw` :229-240; no presence check after loop | OK |
| 28 | `removeDefaultCollections:386-404`, `security.create` at `:404` | method :386-404, create call at :404; callers at :497 (importSchema) and :848 | OK |
| 29 | `FileUtils.atomicMoveWithFallback:306-320` rename only, no fsync | exact | OK |
| 30 | `FunctionLibraryProxy.java:54`, `SequenceLibraryProxy.java:72` standalone create sites | both call the shared `*Impl.create(session)`; no analogous Scheduler proxy-create exists (draft's omission of Scheduler is correct) | OK |
| 31 | `track-4.md:378` link-consistency save/restore for Track-8 nesting | the bullet sits at track-4.md ~:379 ("saves and restores the link-consistency flag … matters for the Track 8 import/genesis work") | OK (±1 line) |
| 32 | "`track-7-design-drafts.md` §0" honored-not-owned | file exists at `_workflow/track-7-design-drafts.md` (NOT in `plan/` beside this draft); §0 carries "The top-level-DDL mutex gap is OUT OF SCOPE for Track 7" at :39-40 | content OK; path ambiguous → WC5 nit |
| 33 | "no CLI exists in-repo — the `console` module is empty" | `console/src` contains zero `.java` files (packaging, bin scripts, assembly only); no non-tool production caller of `DatabaseExport`/`DatabaseImport` | substantively OK; "empty" is loose |

**Stale/wrong-citation verdict: NULL** — no materially stale or wrong citation found in the
sample. Two precision nits (32, 33) folded into WC5. **One `[verified]` *claim* (not citation) is
falsified by code — see WI1.**

---

## 4. Migration compat-matrix completeness (charter item 4)

Axes: **E** = declared exporter-version {E-abs = field absent, E-mal = malformed value,
E≤13, E=14, E=15, E≥16}; **S** = declared schema-version {absent, malformed, <6, =6, >6};
**F** = framing {F-ok = single-member gzip fully consumed, F-trunc = truncated/corrupt gzip,
F-multi = multi-member or trailing garbage, F-plain = plain JSON}; **M** = manifest
{M-ok = present-consistent, M-mis = count mismatch, M-bad = unparsable, M-abs = absent};
**B** = best-effort marker {B0 = absent, B1 = present w/o ack, B2 = present w/ ack}.
Cells collapse by dominance (the legacy arm ignores S/M/B by construction; the strict arm
short-circuits on the first failing check).

| Cell (collapsed) | Outcome | Status |
|---|---|---|
| E≤13 or E=14 × any S × any F × M-abs × any B | today's lenient behavior, byte-for-byte (incl. plain-JSON fallback, missing sections tolerated, partial-import-then-error possible) | **defined** (R1 + M.1 "preserved byte-for-byte"; consequence recorded) |
| E≤13 or E=14 × any S/F/B × M-ok/M-mis/M-bad (legacy dump *containing* a manifest section — hand-crafted only) | at HEAD the shared section loop throws "unsupported tag"; if the implementation adds a `manifest` case un-gated, it silently becomes accepted — the draft does not say whether the new tag case is version-gated | **undefined → WI6** |
| E=15 × S=6 × F-ok × M-ok × B0 | accept | defined (M2.b) |
| E=15 × S=6 × F-ok × M-ok × B1 / B2 | reject / accept | defined (M2.b-4, pin 7) |
| E=15 × S∈{absent, malformed, <6, >6} × any | reject before mutation | defined (Q-M2 ruling (2); rejection-before-mutation clause explicit) |
| E=15 × F-plain | reject ("a v15 dump is gzip-framed") | defined (M2.b-1 + Q-M3: no override) |
| E=15 × F-trunc / F-multi | reject (full-consumption / single-member validation) | defined (M2.b-2, pins 3-4) |
| E=15 × M-mis / M-bad / M-abs | reject | defined (M2.b-3, pin 5) |
| E=15 × InputStream ctor (no physical size) × F-multi/F-trunc tail cases | "validates what it can (single-member + clean trailer) — exact stream-variant scope pinned at decomposition" | **partially defined / deferred → WI10** |
| E=15 × dump carries non-empty `brokenRids` but no best-effort marker (tampered/inconsistent dump) | manifest carries a brokenRids count; whether marker↔brokenRids consistency is checked is unstated | **undefined (minor) → WI10** |
| E=15 × duplicate or reordered sections (e.g., two `info` sections, manifest not last) | count-verify after the loop catches most; duplicate-section behavior unstated | **undefined (minor) → WI10** |
| E≥16 × everything | reject-with-redirect naming both versions | defined (Q-M2 ruling (1)); check precedence vs framing checks unstated but outcome-invariant |
| **E-abs (no `exporter-version` field) × everything** | at HEAD `exporterVersion` stays `-1` (`DatabaseImport.java:111`) → the `< 15` lenient arm silently applies. Neither R1 ("the dump's *declared* exporter version"), nor Q-M2's three-way dispatch (`<= 14` / `== 15` / `>= 16`), nor M2.b defines this cell. A v15-produced dump corrupted in exactly its info section degrades to full leniency | **undefined → WI2** |
| **E-mal (non-integer value) × everything** | at HEAD `readInteger` throws a parse error (accidental reject); no design statement | **undefined → WI2** |
| Blob-collection id list in the dump's schema section × R3-renumbered target | `DatabaseImport.java:528-541` resolves the raw dump id in the target id space | **undefined/unanalyzed → WI1 (blocker)** |

Every other reachable combination reduces to one of the rows above. Conclusion: the ruled matrix
is near-complete for well-formed inputs; the undefined cells are E-abs/E-mal dispatch (WI2), the
legacy-dump-with-manifest tag gating (WI6), two tampered-input minor cells + the deferred
stream-ctor scope (WI10), and the blob-id mapping row (WI1).

---

## 5. Findings

### WI1 — blocker — Draft M §M.0/M.2 (omission) vs §0 R3 / FM-G6; `DatabaseImport.java:528-541`
**R3's blob renumbering breaks the importer's raw-id blob-collection mapping on the primary
migration path, and the draft's `[verified]` claim that "All production lookups are
name/schema-dynamic" is falsified by that code.**

- Evidence (code, HEAD): `DatabaseImport.importSchema` handles the dump's `blob-collections`
  array as raw numeric ids interpreted in the **target's** id space:
  `var collection = Integer.parseInt(i.trim()); if (!ArrayUtils.contains(session.getBlobCollectionIds(), collection)) { var name = session.getCollectionNameById(collection); session.addBlobCollection(name); }`
  (`DatabaseImport.java:537-540`). It does **not** route through `collectionToCollectionMapping`
  (which `importCollections` populates at :916).
- Evidence (draft): §0 R3 claims "All production lookups are name/schema-dynamic **[verified —
  t60.e2]**, but test fixtures, stored dumps, or tests pinning fresh-DB collection ids may break";
  FM-G6 repeats "production lookups verified dynamic (t60.e2)". `DatabaseImport.java:528-541` is a
  production lookup that is id-static with respect to an externally-fixed id.
- Consequence: the flagship migration scenario — a v14 dump from an old-layout database (blobs in
  the highest slots) imported into a fresh new-binaries database (blobs at `1..N` per R3, class
  collections shifted up) — feeds old-layout blob ids into `getCollectionNameById` on the
  renumbered target. Those ids now name **class** collections, so `addBlobCollection` registers a
  class collection as a blob collection (or NPEs on a null name). Blob classification is
  schema-registry-driven everywhere (`ResultInternal.java:780`, `getBlobCollectionIds:5028`), so
  the corruption is **silent** — against the spirit of I-migration-fail-closed.
- Completeness gap: Draft M never mentions renumbering (grep: "renumber" appears only in §0/G
  sections — lines 35, 85, 126, 172, 198-199). The R3 de-risk item is framed as a *test-fixture*
  sweep; this is production import logic. Neither M.5-11 (v14 round-trip — only red if the fixture
  dump contains blob content and predates the renumbering) nor M.5-12 (end-to-end rehearsal —
  same-binaries both sides, layouts coincide, cannot catch it) pins this.
- Required: define the outcome for the blob-id row of the matrix (e.g., route the dump's blob ids
  through `collectionToCollectionMapping`, or match by `$blob*` name), state which draft owns the
  change (it sits in `DatabaseImport`, i.e., Draft M's footprint — which also dents "the two units
  share no files… disjoint" if Draft G were to own it), and add a cross-layout blob-content test
  pin. Also correct the `[verified]` overclaim in §0 R3/FM-G6.

### WC1 — should-fix — track-8.md §Validation bullet 2 vs draft §0 R1 / M.1 / FM-M12
**Two clauses of the frozen EARS validation bullets are contradicted by R1-as-elaborated, and the
supersession is nowhere recorded.**

- Frozen plan: "a complete legacy dump missing any expected section is refused; a legacy dump
  requires the explicit unverified-import acknowledgment flag (I-migration-fail-closed)"
  (track-8.md, Validation and Acceptance, bullet 2).
- Draft: "`exporter-version < 15` keeps today's lenient general-import behavior" (§0 R1); "the
  general (<15) import path's leniency… is preserved byte-for-byte" (M.1); "v14 and older dumps
  regress under the new strictness — prevented structurally by R1's `>= 15` keying — the lenient
  path is untouched" (FM-M3 table, FM-M12).
- Both cannot hold: under the draft, a legacy dump missing a section is **accepted** (lenient) and
  **no** ack flag exists for legacy dumps (the ack gate keys off the v15 best-effort marker,
  M2.b-4). R1 is a settled ruling and postdates the frozen bullets, so the resolution direction is
  clear — but the draft never names the two superseded clauses, and Move 3 is specified to use the
  EARS lines "verbatim as test method names" (track-8.md). Unamended, decomposition would generate
  test names that contradict the design. Record the supersession explicitly (Amendments section +
  track-file edit at Move 2/3).

### WC2 — should-fix — Draft G test pin G.5-2 vs G2.b/G2.c (and pins 7-8)
**The bootstrap-root round-trip pin asserts a state that cannot exist after a completed create.**

- Pin 2: "*Bootstrap root round-trips* — create → close → reopen; assert schema loads with
  version 6, empty classes, counter 0 (pins the G2.a payload shape on both profiles)."
- G2.c (Q-G1 ruling): phase 1 creates Identity, OSecurityPolicy, ORole, OUser, OFunction,
  OSequence, OSchedule, O/V/E **and registers the blob collections** before genesis completes; a
  reopened created database therefore has a non-empty `classes` link set, `collectionCounter > 0`,
  and a populated `blobCollections` set — pins 7-8 themselves assert the populated blob registry.
- As written, pin 2 is unsatisfiable at the DB level; the G2.a payload shape is observable only
  *between* `SchemaShared.create` and the phase-1 commit. Restate the pin (e.g., a harness that
  invokes `SchemaShared.create` in isolation, or an assertion hook before phase 1), or re-scope it
  to "reopen sees a schema loadable without the FM-G1 error branch". The G1 payload narrative
  itself is consistent with `toStream` (verified: `schemaVersion` :1233, `classes` :1137/:1168,
  `globalProperties`/`collectionCounter`/`blobCollections` :1240-1244); only the pin contradicts.

### WI2 — should-fix — Rulings §Q-M2 (1) / M2.b intro; `DatabaseImport.java:111`, `:414`
**The dispatch has no arm for an absent or malformed `exporter-version` field.**

- Q-M2 ruling (1) enumerates `<= 14` / `== 15` / `>= 16` — an exhaustive partition of declared
  integers, but not of inputs: at HEAD `exporterVersion` is initialized to `-1`
  (`DatabaseImport.java:111`) and assigned only if the field is present (`:414`), so a dump with
  no `exporter-version` silently rides the lenient arm; a malformed value aborts on an accidental
  parse error. Q-M2 (3) makes `exporter-version` "mandatory" — but mandatoriness enforcement is
  part of the strict path, which only arms *after* reading the field: circular for the absent
  case. A v15-produced dump corrupted precisely in its info section would degrade to full
  leniency — a fail-closed hole on the strict contract's own artifacts. Define the cell (e.g.,
  "gzip-framed input with no parseable `exporter-version` → reject", or an explicit
  accepted-risk note that absent-version dumps are lenient by construction).

### WI3 — should-fix — Draft M scope/footprint vs design.md:993-995, :1029-1031
**The operator-facing migration procedure document is presupposed by the spec but absent from the
design.**

- design.md Part 4: "The documented procedure keeps the target out of service until verification
  passes" and "the operator verifies export exit status before importing" — the fail-closed story
  *leans* on this procedure (it is what makes "a dump file at the final name proves nothing"
  survivable for the old-binaries export leg). `docs/` contains no export/import/migration
  document (P8), and Draft M's M.1 scope, M.5 pins, and M.6 footprint contain no documentation
  deliverable, nor does track-8.md. Either add the procedure doc to the footprint (docs-sync duty)
  or record explicitly where it lives and who owns it.

### WI4 — should-fix — M.5 pin list vs Rulings §Q-M2 (and G.5 vs Q-G2)
**The rulings landed after the test-pin lists were drafted and the lists were never extended; the
entire R4/Q-M2 validation matrix has no pin.**

- No M.5 pin covers: `schema-version` out of range / missing / malformed → reject; `>= 16` →
  reject-with-redirect naming both versions; mandatory-field enforcement; unknown-info-fields
  tolerated-and-logged; "all rejections throw BEFORE any mutation of the target database". Pins
  1-12 cover framing, manifest, ack, dangling-field, round-trips — none of Q-M2. Draft G
  similarly: Q-G2's "ONE merged data transaction" single-commit shape has no pin (pins 3-6 test
  mutex state and engine build, not tx count). Since the draft's discipline is FM→pin mapping,
  the ruled matrix deserves the same treatment before decomposition.

### WI5 — should-fix — §0 R3 de-risk requirement + G.5 pin 8 (charter item 5, blast-radius honesty)
**The R3 de-risking step is not executable as written.**

- Pin 8: "run the suites most likely to pin fresh-DB collection ids (import/export,
  backup/restore, RID-literal tests) against the renumbered layout **before** the restructure
  lands" — but the renumbered layout only exists once the R3 storage change is implemented; §0 R3
  likewise says the sweep is "a mandatory first-step activity of the genesis unit, before the
  restructure lands". The intended ordering (land the `doCreate` blob loop first, sweep, then the
  two-phase restructure? or a static fixture grep before any code change?) is left to guessing.
- Missing concreteness: no named suites (candidates visible in the repo/history:
  `DbImport*`/`DbImportStreamExportTest`, `StorageBackup*`, `LocalPaginatedStorageRestore*`,
  `DatabaseExportImportRoundTripTest`, RID-literal tests in `tests/`), no static-sweep component
  (e.g., grep patterns for `#\d+:\d+` RID literals and hard-coded collection ids in fixtures/
  stored dumps), and no statement of what "fix fixtures found" means for stored binary/JSON dump
  fixtures whose ids cannot be regenerated. Specify: (a) step ordering (storage-embed → sweep →
  restructure), (b) the suite list, (c) the static grep, (d) the fixture-regeneration rule. Note
  the sweep as scoped cannot catch WI1 (production code, not a fixture).

### WI6 — suggestion — M.1 "preserved byte-for-byte" vs M2.b-3 manifest section (shared section loop)
**Whether the new `manifest` tag case in `importDatabase`'s shared switch is version-gated is
unstated.** At HEAD an unknown tag throws (`DatabaseImport.java:239-240`). If the implementation
adds `case "manifest"` un-gated, a hand-crafted legacy dump containing a manifest flips from
reject (today) to silently processed — violating "byte-for-byte". One sentence pins it (e.g.,
"the manifest case arms only when `exporterVersion >= 15`; below, the tag remains unsupported").

### WI7 — suggestion — FM-M4 / M2.a-5
**FM-M4 is the only failure mode in either draft with no test pin.** All of FM-M1..M13 map to
pins except the fsync-before-rename discipline (crash-after-rename durability). Full crash-fault
injection is out of proportion, but a pin asserting the promote path calls the new fsync-capable
move variant (and that `close()`-without-flag never calls it) is cheap and keeps the FM→pin
discipline intact.

### WI8 — suggestion — M.6 / G.6 footprints and unpinned surfaces
**Footprint omissions and unpinned shapes an implementer must guess:**
(a) `core/.../api/config/GlobalConfiguration.java` — the Q-M1 spill-threshold knob lands there;
not in M.6. (b) The best-effort export `-option` and the import ack flag surface likely touch the
shared `DatabaseImpExpAbstract` options plumbing — not in M.6 (also worth a sentence against the
"two units share no files" packing premise, which currently survives). (c) The manifest JSON
shape is unpinned: section tag name, field names, count granularity (total records vs
per-collection), brokenRids-count field — importers and the pin-5 fixtures need the exact shape.
(d) The Move-2 ADDED/MODIFIED/REMOVED triad must record the R3 scope expansion
(`AbstractStorage`, `SharedContext`) and M2.a-7 relative to the frozen track-8.md In-scope list.

### WI9 — suggestion — G2.c (omission)
**No lock-interaction statement for the phase-1 commit running under `SharedContext.lock`.**
`SharedContext.create:184-221` holds `SharedContext.lock` for the whole body; the phase-1 schema
tx will engage the `MetadataWriteMutex` and commit (four-lock order:
mutex → `SchemaShared.lock` → IM lock → `stateLock.writeLock`) *inside* that lock. Track 3's
I-C2 covers the shared metadata locks, not `SharedContext.lock`. "No contention at genesis" (plan
premise, honored) makes this safe by construction, but the draft should say so in one sentence —
including why the import-nested call site (no `SharedContext.lock` held) differs — so a reviewer
of the implementation does not have to re-derive it.

### WI10 — suggestion — M2.b-2 / M2.b-3 residual matrix cells
Three minor cells left to decomposition or undefined (see §4 table): (a) the InputStream-ctor
validation scope — the draft itself defers it ("pinned at decomposition"); carry it as an explicit
decomposition obligation so it is not dropped; (b) a v15 dump with non-empty `brokenRids` but no
best-effort marker (tampered/inconsistent) — say whether marker↔brokenRids consistency is
checked; (c) duplicate sections in a v15 dump — say whether the section-presence check also
rejects duplicates.

### WC3 — suggestion — §0 R1 / M2.b intro vs Rulings §Q-M2 (1)
**Unharmonized dispatch phrasing.** §0 R1: "a dump declaring `>= 15` gets the full strict matrix";
M2.b intro: "If >= 15, the strict matrix arms". Q-M2 ruling: `== 15` → strict; `>= 16` →
reject-with-redirect. The pre-ruling two-way phrasing was never amended (the Amendments section is
reserved for this pass). Risk: an implementer runs the full strict pipeline on a v16 dump before
rejecting, or misses the v16 reject entirely. One-line amendment: "strict matrix = v15 exactly;
`>= 16` short-circuits to reject-with-redirect (Q-M2)".

### WC4 — suggestion — design.md:504-506 vs G2.a
**"The schema transaction … writes the first schema record" is superseded in letter by G1.**
Under G2.a the first schema record (the bootstrap-valid root) is written by `SchemaShared.create`'s
own `computeInTx` *before* the genesis schema tx; the genesis tx then writes the first *per-class*
records and rewrites the root. The draft proves the frozen premise "contradicted at HEAD" for the
seed half but does not note that the "writes the first schema record" clause also needs
reconciling in `design-final.md`/the track file. Cosmetic, but it is exactly the kind of drift the
Phase-4 reconciliation should be handed explicitly.

### WC5 — suggestion — cross-reference hygiene
(a) implementation-plan.md's checklist still shows Tracks 5-7 unchecked while the draft's G.0
premises them landed ("Two-phase enablers … all landed"); the draft is right (P10: "Track 06/07
complete" commits; `TxSchemaState`/`MetadataWriteMutex` exist at HEAD) — the plan file is stale
and will confuse implementers who take the checklist at face value. (b) The §0 citation
"`track-7-design-drafts.md` §0" resolves to `_workflow/track-7-design-drafts.md`, not to a file
beside this draft in `plan/` — spell the path. (c) "the `console` module is empty" — precise form:
"contains no Java sources / no export CLI entry point" (the module ships packaging and bin
scripts).

---

## 6. Null verdicts (licensed, with justification)

- **N1 — Citation staleness: NULL.** 25+ citations sampled across both drafts (§3); all accurate
  at HEAD. No production file cited differs between the grounding SHA and HEAD (verified by
  `git diff d664589d7f..HEAD`).
- **N2 — I-U4 discharge: NULL (no gap).** Both directions pinned (mutex engaged phase 1 / not
  phase 2 — pin 3 against the existing `MetadataWriteMutexTest` machinery, which exists; engine
  built before first insert — pin 4). The draft's replacement of the counterfactual
  "unified-tx-throws" test with a positive assertion is well-argued and flagged `[inferred]`
  where due.
- **N3 — R2 manifest-discipline transfer: NULL.** The frozen "temp + fsync + rename" sidecar
  discipline is explicitly re-derived for the in-dump section (§0 R2, M2.a-5), and the new
  fsync-capable move closes the genuine `FileUtils` gap (verified: no fsync at :306-320).
  Directory-fsync omission is fail-safe (a lost rename leaves *no* file at the final name —
  fail-closed), so no finding.
- **N4 — Streaming variants: NULL beyond WI10(a).** FM-M13 + pin 11 cover the stream round-trip;
  `DbImportStreamExportTest` exists; the promote gate is correctly argued a no-op for the stream
  ctor (verified: `close()` renames only when `tempFileName != null`).
- **N5 — Packing premise: NULL (holds today).** Draft G and Draft M file footprints are disjoint
  as listed; only WI1's likely fix (in `DatabaseImport`) and WI8(b) could perturb it — flagged
  there, not an independent defect.
- **N6 — "Direct index lookup" plan wording: NULL (already handled).** The draft itself corrects
  the stale plan wording with code proof (`getUserInternal`/`getUserRID` are planner queries);
  validation bullet 1's intent is preserved via pin 4.
- **N7 — §0 scope boundary (honored-not-owned → MOOT admin resolution): NULL.** Internally
  consistent: import DDL stays legacy, standalone creator sites stay legacy per Q-G1, both keyed
  to the upcoming-PR removal; `IndexManagerEmbedded.java:787-791` documents the gap as claimed.
- **N8 — Q-M3 elaboration: NULL.** "Always rejected, no override" is consistently carried in
  M2.b-1, FM-M6, pin 6; no residual override surface anywhere in the draft.

## 7. Alternative-hypothesis log

- **H1 (vs WI1):** "The collections-section mapping neutralizes the raw-id issue." Rejected:
  `blob-collections` parsing at `DatabaseImport.java:537-540` uses the raw dump id, never
  `collectionToCollectionMapping`; the guard `!contains(getBlobCollectionIds(), id)` compares
  source-space ids against target-space ids, which R3 makes disjoint for old-layout sources.
  "t60.e2 scoped 'production lookups' to blob readers only" — even so, the import path *is* a
  production consumer of blob-collection ids, so the [verified] generalization in §0/FM-G6 is
  falsified in the form the draft states it.
- **H2 (vs WC1):** "'Legacy dump' in bullet 2 means 'best-effort dump'." Rejected: bullet 4
  separately covers the best-effort ack ("a best-effort dump imported without the ack flag is
  refused"), so bullet 2's legacy clauses must mean pre-v15 dumps — exactly what R1 makes lenient.
- **H3 (vs WC2):** "'create' in pin 2 means a storage-level/SchemaShared-only fixture, not DB
  create." Possible, but the pin's own words ("create → close → reopen; assert schema loads")
  describe a DB lifecycle; at minimum the pin is ambiguous enough to mislead the implementer into
  an unsatisfiable test. Finding retained at should-fix (restate, don't redesign).
- **H4 (vs WI2):** "Q-M2's 'missing or malformed → reject' covers exporter-version." Rejected:
  that clause sits in ruling item (2), which is explicitly about `schema-version`; item (3) makes
  `exporter-version` mandatory *in v15 dumps*, which cannot be enforced when the field that
  identifies a dump as v15 is itself absent — the circularity is the gap.
- **H5 (vs WI6):** "The implementer will obviously version-gate the manifest tag." Possibly — but
  "preserved byte-for-byte" vs a shared section loop is precisely where an un-gated case slips in;
  the design's own standard (R1 keying everywhere) argues for stating it.
- **H6 (vs WI9):** "SharedContext.lock is irrelevant since genesis is single-session." Accepted as
  the likely truth — which is why WI9 is a suggestion to *state* the argument, not a defect claim.

## 8. Executability summary (charter item 6)

Decomposable without guessing **except**: the R3 sweep ordering/content (WI5), the blob-id mapping
outcome (WI1), the E-abs/E-mal dispatch (WI2), the option-flag surfaces + manifest shape + knob
file (WI8), the stream-ctor scope (WI10a), and the legacy-manifest tag gating (WI6). Draft G's
implicit step order (Q-G3 verify + sweep → G1 → R3 embed → two-phase restructure → tests) is
recoverable but worth writing down at decomposition. Acceptance properties are otherwise concrete
(pins name observable assertions; FM→pin mapping is complete except FM-M4, WI7).

---

## Compact findings index

| ID | Severity | Location | Summary |
|---|---|---|---|
| WI1 | blocker | Draft M omission; §0 R3 / FM-G6 vs `DatabaseImport.java:528-541` | Blob-id import mapping undefined under R3 renumbering; `[verified]` name-dynamic claim falsified; silent corruption on the primary v14→v15 migration path |
| WC1 | should-fix | track-8.md Validation bullet 2 vs §0 R1 / M.1 / FM-M12 | Frozen EARS clauses (legacy section-refusal + legacy ack flag) contradicted by R1; supersession unrecorded |
| WC2 | should-fix | G.5 pin 2 vs G2.b/G2.c | Bootstrap round-trip pin asserts empty classes/counter 0 after full create — unsatisfiable post-genesis |
| WI2 | should-fix | Rulings Q-M2(1) vs `DatabaseImport.java:111,414` | Absent/malformed `exporter-version` dispatch cell undefined; corrupt-info v15 dump degrades silently to lenient |
| WI3 | should-fix | Draft M scope vs design.md:993-995,1029-1031 | Operator migration-procedure document presupposed by spec, absent from footprint/pins/track file |
| WI4 | should-fix | M.5 / G.5 vs 2026-07-23 Rulings | Q-M2 validation matrix (and Q-G2 single-tx shape) has no test pins; pin lists not amended post-rulings |
| WI5 | should-fix | §0 R3 + G.5 pin 8 | De-risk sweep not executable: ordering self-contradiction, no named suites, no static grep, no fixture-regeneration rule |
| WI6 | suggestion | M.1 vs M2.b-3 | Version-gating of the new `manifest` tag case in the shared section loop unstated vs "byte-for-byte" |
| WI7 | suggestion | FM-M4 / M2.a-5 | Only failure mode with no test pin (fsync-before-rename promote discipline) |
| WI8 | suggestion | M.6 / G.6 | Footprint omits GlobalConfiguration knob + option-flag surface; manifest JSON shape unpinned; Move-2 triad must record R3/M2.a-7 scope expansion |
| WI9 | suggestion | G2.c | Phase-1 commit under `SharedContext.lock` — safety argument absent |
| WI10 | suggestion | M2.b-2/3 | Residual matrix cells: stream-ctor scope deferred; brokenRids-without-marker; duplicate sections |
| WC3 | suggestion | §0 R1 / M2.b intro vs Q-M2 ruling | "≥15 strict" phrasing unharmonized with "==15 strict / ≥16 reject" |
| WC4 | suggestion | design.md:504-506 vs G2.a | "Schema tx writes the first schema record" superseded by pre-tx bootstrap root; hand to design-final reconciliation |
| WC5 | suggestion | impl-plan checklist; §0 citation; M.4 wording | Tracks 5-7 shown unchecked though landed; track-7-drafts path ambiguous; "console module is empty" imprecise |
