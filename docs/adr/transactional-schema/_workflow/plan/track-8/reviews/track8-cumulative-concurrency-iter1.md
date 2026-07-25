# Track 8 — Cumulative code review, concurrency perspective (iteration 1)

**Findings prefix:** CN, numbering from CN60 (design phase used up to CN59).
**Object under review:** the Track 8 cumulative diff `git diff cced9df1af..19ebbcbb2d`
(branch `transactional-schema`), scoped per the charter to interactions of the (single-threaded)
export/import tools and the genesis/blob changes with a LIVE concurrent system.
**Grounding:** HEAD `19ebbcbb2d`; every behavioral claim below re-verified by reading the cited
code at this HEAD. Binding context: `track-8.md`, `track-8-design-drafts.md` (Draft G/M + rulings),
the pass-1 adversarial concurrency review (CN48–CN53), and `concurrency-step3-iter1.md`
(CN55–CN59 + null verdicts N-O1..N-O6).
**Mode:** read-only; no Maven; only this report file written.

---

## 0. Decision criteria and method

**Severity vocabulary:** *blocker* — an interleaving reachable under the shipped code produces an
unsafe outcome (corruption, silent wrong result) in the change's core scope, with no documented
acceptance; *should-fix* — a real interleaving gap, or a pre-existing one that the change's new
trust contract (exit-0 gates, manifest verification, runbook claims) newly leans on; *suggestion*
— pre-existing/adjacent hazard, precision gap, or a documentation obligation.

**Decision procedure per charter item:** (1) reconstruct what the design ruled (freeze/consistency
rulings grepped from both plan docs); (2) trace the as-built serialization structure (locks,
monitors, tx machinery, snapshot semantics); (3) enumerate interleavings as explicit thread-A /
thread-B step sequences, each **checked** (counterexample or exclusion proof) or **out-of-scope**
(pre-existing AND unchanged AND not newly leaned on); (4) alternative-hypothesis check per verdict;
(5) hypothesis log at the end.

**Dedup discipline.** Known items NOT re-filed (checked against every compact block in `reviews/`):
CS71 (`setUser(null)` never restored), CS72 (`INDEX_IGNORE_NULL_VALUES_DEFAULT` flip not
exception-safe — charter asks only whether Step 6 widened it; see §4.3), CS64 (importRecord
swallows `DatabaseException`), CN55/CS55 (marker-refusal storage pin — **verified FIXED at HEAD**,
see §5.2), CN56–CN59, CS52–CS57, CS58–CS62, CS63–CS74, CS75–CS78, CQ13–CQ32, CN48–CN53
(design-phase; gate verified their remedies — this review re-checks the concurrency-relevant
remedies as-built: CN50 §2, CN51 §3.3/§4.2, CN52 §3.5).

---

## 1. Design rulings re freeze / live-export consistency (what the plan actually promises)

Re-read of `track-8-design-drafts.md` and `track-8.md` for every freeze/consistency ruling:

| Ruling | Content | Source |
|---|---|---|
| R-1 (E1, pass-1) | Concurrent record commits during the export tx are "isolated by the export's single tx snapshot (P8)" | adversarial-concurrency-pass1 §3.6 E1 |
| R-2 (FM-M17/CN51) | Manifest counts are exporter-tallied / importer-tallied — NEVER re-derived from a fresh snapshot or target queries; "an export under concurrent DDL yields a self-consistent manifest" | drafts M2.a-5, :519, FM-M17 |
| R-3 (FM-M15/CN52) | Two concurrent exporters of one target must each produce an internally consistent dump (unique `CREATE_NEW` temp) | drafts M2.a-4, FM-M15 |
| R-4 (E4, pass-1) | Export vs operator freeze/backup: pre-existing, unchanged, out-of-scope | pass-1 §3.6 E4 |
| R-5 (runbook) | Migration export runs "keeping the database otherwise idle"; import target "out of service — no application traffic, no other sessions" | operator-migration-procedure.md steps 1, 3 |
| R-6 (I7, pass-1) | Import DDL racing other sessions' schema txs: out-of-scope by declared design (legacy path, honored gap) | pass-1 §3.4 I7 |

**Key observation:** NO ruling anywhere requires or takes a freeze for export. The design's entire
live-export consistency promise is R-2 (manifest ↔ dump-content self-consistency). Point-in-time
consistency ACROSS dump sections is neither promised nor explicitly disclaimed — §3.2 and CN61
below examine what the as-built code actually delivers there.

---

## 2. Charter item 1 — Step 1 blob-collection embedding + R3 sweep vs concurrent DDL/DML

**As-built trace.**
- Creator side: `AbstractStorage.doCreate` reads `STORAGE_BLOB_COLLECTIONS_COUNT` exactly ONCE
  from the create-time `contextConfiguration` (`AbstractStorage.java:1520-1522`), rejects
  negatives loudly (`:1530-1536`), and creates `$blob0..N-1` via `doAddCollection` INSIDE the same
  WAL atomic operation as the `internal` collection (`:1538`). Single-threaded by construction:
  `doCreate` runs on the creating thread inside the factory monitor
  (`YouTrackDBInternalEmbedded.createStorage`, `synchronized (this)` — pass-1 P1, re-verified by
  concurrency-step3 N-O1/N-O2), and inside the storage-create atomic operation no session exists.
- Register side: `SharedContext.create` phase-1 tx enumerates the storage's actual collections BY
  NAME (`SharedContext.java:274-277`, `List.copyOf(storage.getCollectionNames())`, matched against
  the `BLOB_COLLECTION_NAME_PATTERN` derived from the shared
  `MetadataDefault.BLOB_COLLECTION_NAME_PREFIX` constant, `SharedContext.java:146-153`) and
  registers ids via the session schema proxy (tx-local root-payload write). The count is never
  re-read — **CN50's remedy is implemented as specified** (option (a), name enumeration).

**Interleaving enumeration.**

| # | Thread A / Thread B sequence | Verdict |
|---|---|---|
| B1 | A: mid-genesis between `doCreate`'s blob loop and the register loop. B: any session-minting path (open/create/pool/exists/drop) on the same DB | **checked — excluded**: every such path takes the factory monitor A holds for the whole `createStorage` span (pass-1 P2; genesis-side files last touched at Step 3, the exact state concurrency-step3 verified — `git log` confirms steps 4–6 never touched them). Null. |
| B2 | A: register loop iterating `storage.getCollectionNames()`. B: concurrent `addCollection` mutating `collectionMap` | **checked — unreachable at genesis** (B1 exclusion: no other session exists; the only collections are `internal` + `$blob*`). The `List.copyOf` defensive snapshot (CQ14) additionally shields a future in-tx write path. Note the SAME iteration shape IS reachable in export where sessions do exist — that is CN60 (§3.2), not a genesis defect. Null here. |
| B3 | A: `GlobalConfiguration.STORAGE_BLOB_COLLECTIONS_COUNT.setValue(x)` from any thread mid-genesis (the CN50 attack). B: genesis | **checked — closed**: the global is read once at `:1520` from the create-time context configuration; the register loop reads storage state, not config. A post-read `setValue` cannot desynchronize creation from registration. Null (CN50 discharged as-built). |
| B4 | R3 de-risk sweep introduces a concurrency surface | **checked — vacuous**: the sweep touched TEST files only (`SchemaClassOperationsTest`, `EntityImplTest`, `CommandExecutorSQLTruncateTest`, `SharedLinkBagBTreeReadMethodsTest`, `BTreeGetVisibleTest`, `PostponedEngineStartTest`, `StringsTest`), plus the production id lookups already dynamic. No new production shared state. Null. |
| B5 | Post-genesis: user DDL (`session.addBlobCollection`) racing schema txs | routed through the schema proxy / tx-local copy → metadata-write mutex serialization (Track 7 machinery, unchanged). Out-of-scope (not a Track 8 delta) and covered by the step-3 review's O4. |

**Charter item 1 verdict: clean (null).** Locks/atomicity as designed: WAL-atomic creation,
monitor-excluded registration, single config read.

---

## 3. Charter item 2 — Export against a live database

### 3.1 What `DatabaseExport` actually holds (as-built)

Traced at HEAD:
- **One session, one transaction.** All six sections run inside a single
  `session.executeInTx(...)` (`DatabaseExport.java:206-222`). Stream close + `promote()` +
  `completed = true` happen AFTER the tx (`:230-234`), and touch no DB state.
- **No freeze.** No call to `freeze()`/`release()` anywhere in the diff or the file; the design
  never asked for one (§1, R-4).
- **No storage lock held across sections.** Every storage read
  (`getCollectionNames`, `getCollectionIdByName`, `getCollectionNameById`,
  `getApproximateCollectionCount`) acquires and RELEASES `stateLock.readLock` internally per call
  (`AbstractStorage.java:2344-2371`, `:2373-2410`, `:1846`).
- **No schema-snapshot pin across sections.** A plain read tx never pins the thread-local
  snapshot: the only production pinners are `executeReadRecord` (pin/unpin per single record read,
  `DatabaseSessionEmbedded.java:2226/:2403`), `EntityImpl:4178`, and the schema-carry commit
  (`AbstractStorage:2585`). So at `exportSchema`'s
  `session.getMetadata().getImmutableSchemaSnapshot()` (`DatabaseExport.java:604`) the pin count is
  0 and `MetadataDefault.getImmutableSchemaSnapshot` (`MetadataDefault.java:155-163`) mints a
  **fresh** snapshot of the shared committed schema *at that instant*
  (`SchemaShared.makeSnapshot:402-424` returns the shared cache, which concurrent schema commits
  refresh).
- **Record reads are tx-begin-snapshot isolated.** `browseCollection` record loads route through
  `executeReadRecord` → `storage.readRecord(rid, getEffectiveReadAtomicOperation())`
  (`DatabaseSessionEmbedded.java:2297`, `:3756-3761`): inside the export tx the read atomic
  operation is the transaction's own, i.e. the begin-time MVCC view (corroborated by the
  fresh-committed-read machinery's own comments, `:2249-2256` — "read at the transaction's
  begin-time snapshot"). This confirms the pass-1 P8/E1 premise **for the records section**.
- **Index section reads live state**: `exportIndexDefinitions` calls
  `indexManager.reload(session)` (`DatabaseExport.java:546`) — a re-load of the SHARED index
  manager, internally serialized by its exclusive lock (`IndexManagerEmbedded.reload:129-140`);
  pre-existing at the baseline (`cced9df1af` line 396), unchanged by Track 8.

### 3.2 Interleaving enumeration — concurrent writers vs dump internal consistency

Sections are written in order: info(t₀) → collections(t₁) → schema(t₂) → records(t₃, reads at
begin-snapshot S₀) → indexes(t₄) → brokenRids → manifest. Concurrent sessions can commit DML and
DDL at any point (nothing excludes them — §3.1).

| # | Thread A (export) / Thread B (writer) sequence | Verdict |
|---|---|---|
| E10 | B commits record inserts/updates/deletes at any tᵢ | **checked — isolated**: record reads resolve at S₀ (§3.1); each record is read whole (single-version storage read); manifest `records` counts what was copied (`recordExported++`, `:784`). Records section is point-in-time at S₀. Null. |
| E11 | B commits DDL between t₂ and manifest; manifest counts drift | **checked — closed as designed (CN51/FM-M17)**: `manifestClasses++` increments per class object actually written (`:632`), `manifestIndexes++` per index object (`:560`), `manifestBrokenRids` from the written set (`:412`); nothing is re-derived at manifest time. Manifest ↔ dump-content consistency holds under ANY interleaving. Null — design honored. |
| E12 | B's DDL commit (add/drop/rename collection via class DDL) lands while A iterates the LIVE `collectionMap` keySet view inside `getMaxCollectionId` (`:446-454`, called at `:326` and `:476`) | **checked — DEFECT (CN60)**: the view escapes the read lock (`AbstractStorage.java:2358` returns `Collections.unmodifiableSet(collectionMap.keySet())`, lock released on return; `DatabaseSessionEmbedded.getCollectionNames:4273-4279` passes it through); B's commit mutates the backing `HashMap` (`:7034` put / `:3559` remove under `stateLock.writeLock`) while A iterates with NO lock → JMM-undefined. Loud arm (CME) aborts fail-closed (good — at the baseline it would have PROMOTED a partial dump via close-in-finally; Track 8 improved that). Silent arm: a missed key under resize/put yields a too-small max id → whole collections omitted from the dump with **exit 0 and a self-consistent manifest**. See CN60. |
| E13 | B drops class X (and its collection) between S₀ and t₃ | **checked — degrades to fuzzy-but-consistent**: `getCollectionNameById(i)` is a live locked read → returns null for the dropped id → collection skipped; X's records never enter the dump; manifest consistent. The collections section (t₁) may still list the dropped collection → import creates it empty. Importable. Null (fuzzy envelope, CN61 records it). |
| E14 | B creates class X + commits records between t₁ and t₃ | **checked — fuzzy but importable**: schema section (fresh snapshot at t₂) contains X; records at S₀ exclude X's records; X's `collection-ids` are absent from the collections section → import's mapping filter drops them silently (`DatabaseImport.java:1124-1129`, `filter(cid != COLLECTION_NOT_FOUND_VALUE)`) → class created on fresh collections, zero records. No false strictness rejection (manifest tallied). Null for correctness; part of the CN61 envelope. |
| E15 | B RENAMES class X between t₂ and a record render in t₃ | **checked — silent-loss composition (CN61)**: each record render pins a FRESH schema snapshot (`executeReadRecord:2226` pin per read) → records rendered after the rename carry the NEW class name; the schema section (t₂) carries the OLD name. On import, applying a record whose class does not exist raises a `DatabaseException` → the pre-existing swallow arm (`DatabaseImport.java:1592-1604`, CS64) consumes-but-does-not-land it → parsedRecordCount matches the manifest → **exit 0, records silently missing**. Not re-filing CS64; the live-export skew that ARMS it on an honest dump is CN61's subject. |
| E16 | B commits `addBlobCollection` while A materializes `session.getBlobCollectionIds()` (`DatabaseExport.java:610`) | **checked — same escape shape as E12, second instance**: `SchemaShared.getBlobCollections:1690-1698` returns `IntSets.unmodifiable(blobCollections)` (live set) with the schema read lock released; `toIntArray()` (`DatabaseSessionEmbedded.java:5028-5031`) iterates it unlocked. Mutation frequency is near-zero (blob registration DDL only) — folded into CN60's fix surface. |
| E17 | B holds an operator freeze / triggers a WAL-roll quiesce during the export tx | **checked — pre-existing, unchanged** (R-4): export is reads + file I/O; freezes park commits, not reads. Out-of-scope. |
| E18 | Two exporters race the same target path | **checked — closed as designed (CN52/FM-M15)**: per-export UUID temp opened `CREATE_NEW` (`DatabaseExport.java:140-143`); each promotes its OWN whole file via `durableAtomicMove` (`:269`, `FileUtils.java:346-375` — `ATOMIC_MOVE + REPLACE_EXISTING`); renames serialize at the FS; last promote wins with an internally consistent dump. `Files.createDirectories` races are JDK-tolerated. Spill files use `Files.createTempFile` unique names (`SpillableRecordBuffer.java:196`). Null — design honored. |
| E19 | Export promotes while a concurrent import reads the SAME final path | **checked — benign/loud**: POSIX — the import's `FileInputStream` (opened at ctor) pins the old inode; the atomic rename swaps the directory entry; the import reads a consistent old dump. Windows — the promote may fail loudly (fail-closed, no fallback). One residual TOCTOU inside the import ctor → CN62. |

### 3.3 Design rulings vs as-built (charter question answered)

- **Freeze:** none ruled, none taken — consistent.
- **R-2 (manifest self-consistency under concurrent DDL): implemented exactly as ruled** (E11).
- **R-1 (single-tx snapshot): true for records only.** The pass-1 E1 wording ("the sections run
  inside ONE tx … isolated by the export's single tx snapshot") over-generalizes: the schema,
  collections, blob and index sections read live shared state at their own instants (§3.1). The
  pass-1 review itself knew the schema snapshot is fresh (that fact is CN51's own premise), so
  this is a wording gap, not a missed analysis — but the resulting cross-section envelope was
  never written down anywhere binding. That is CN61.
- **R-5 (idle mandate): covers the migration procedure only.** The design contemplates
  live/concurrent exports elsewhere (FM-M15's cron-overlap counterexample, FM-M17's concurrent
  DDL), so the live-export envelope cannot be dismissed as operator error.

---

## 4. Charter item 3 — Import into a live target

### 4.1 Preamble mutation + deferral (CS38 as-built) vs concurrent sessions

As-built: `runDeferredImportPreamble` (`DatabaseImport.java:530-546`) runs
`removeDefaultNonSecurityClasses()` + shared-IM `reload` + auto-index snapshot +
`beforeImportSchemaSnapshot` capture, unlocked only after `importInfo` parsed a version and
`runPreFlightChecks` (`:442-528`) passed; SR2 rejects any first-tag-not-info dump BEFORE the
preamble (`:311-317`).

| # | Interleaving | Verdict |
|---|---|---|
| I10 | B (another session) commits DDL/DML between import start and the (now later) preamble | **checked — no new exposure**: the deferral moves the SAME mutations later; nothing new runs in between (only stream parsing). A pre-flight rejection now fires with the target UNTOUCHED — strictly narrower mutation exposure than the baseline (which mutated first, read later). Null. |
| I11 | B holds the metadata-write mutex when the preamble's legacy DDL (dropClass/dropIndex) or `removeDefaultCollections`' `security.create` phase-1 tx engages it | **checked — parks, no cycle**: import holds no lock a mutex-holder needs (pass-1 I1, re-verified: the call sites are tx-free — `SecurityShared.create:595-612` guard-first + the CQ17 comment's verified precondition). Null. |
| I12 | The phase-1→phase-2 window at the import call site (B inserts an `admin` user between the phases) | **checked — pre-existing, narrower than the baseline's dozens of self-commits** (pass-1 I4). Out-of-scope (honored gap, R-6). |
| I13 | `skipRoleHasPredicateSecurityForClassUpdate` (shared per-context plain field, now flipped in TWO methods — `SecurityShared.java:618/:632/:639/:665`, read at `:1224`) vs B's concurrent role-class update at the import site | **checked — no widening**: the baseline flipped it once around the whole create; the two-phase split covers the same total span MINUS a mid-gap where it is correctly false. Exposure shrank. (Step-3 review H20 verdict re-confirmed on the cumulative diff.) Null. |
| I14 | Import's shared-IM `reload(session)` (`:537`, `:693`) vs B's concurrent index reads | **checked — internally serialized** (`IndexManagerEmbedded.reload:129-140`, exclusive lock inside its own tx); pre-existing call, only MOVED later (`:537`) — window narrowed. Null. |

### 4.2 v15 strictness state vs concurrent sessions (CN51 as-built)

All verification state is importer-instance-confined: `sectionOccurrences`,
`parsedSchemaClassCount/parsedIndexCount/parsedRecordCount/parsedBrokenRidCount`
(`DatabaseImport.java:152-166`, incremented at `:1109`, `:1856`, `:1528`, `:715`), manifest
declarations (`:168-172`). `verifyV15StructuralStrictness` (`:590-641`) and `verifyManifestCount`
(`:644-651`) compare importer tallies against dump declarations and drain the importer's own
stream — **zero target-database queries**, exactly as CN51/FM-M17 ruled. A concurrent session
inserting 100 records into an imported class cannot flip any check. **Null — design honored.**

### 4.3 `session.setUser(null)`, validation flag, `INDEX_IGNORE_NULL_VALUES_DEFAULT`

- `session.setValidationEnabled(false)` (`:300`) / restore in `finally` (`:424`): session-confined
  state; restored on every path including all NEW rejection paths. No cross-session edge. Null.
- `session.setUser(null)` (`:301`): CS71 (not re-filed). **Widening check:** position unchanged
  from the baseline (fires before any parse, so the NEW pre-flight rejections inherit the
  already-filed envelope — a pre-flight-rejected import still leaves the caller's session
  user-less, but so did every baseline failure). The Q-M2 "target byte-for-byte untouched" claim
  is about the TARGET database and remains true. **Not widened.** Null.
- `INDEX_IGNORE_NULL_VALUES_DEFAULT` global flip (`:1965-1977`): CS72 (not re-filed). **Widening
  check (the charter's explicit question):** the flip block is byte-identical to the baseline
  (`cced9df1af:DatabaseImport.java:1417-1429` — absent from the cumulative diff entirely); the
  window still contains exactly one call (`indexManager.createIndex`). Step 5 added
  `parsedIndexCount++` OUTSIDE the window (`:1856`, at section parse); Step 6 touched only info
  parsing, which runs long before `importIndexes`. No new throw sources inside the window, no
  new code between the two `setValue` calls, no additional flips added anywhere in the diff
  (repo grep). **Step 6 (and Steps 4–5) did NOT widen CS72's exposure window.** Null.
- `disableLinkConsistencyCheck()/enableLinkConsistencyCheck()` (import `:1590/:1613`;
  `IndexAbstract.java:646-663/:893-916` capture-and-restore): session-confined flags; the
  IndexAbstract restore preserves an outer disabled window (import). Cross-session index-manager
  record mutation rides the transaction and the step-3 review's O4 verdict (tracker
  suppression/unlink symmetry) — re-confirmed unchanged since Step 3. Null.

### 4.4 Two concurrent imports into one target

Both flip the CS72 global (that finding's territory), both run preambles and legacy DDL —
pre-existing shape, unchanged by the diff, and R-5 mandates an out-of-service target. Out-of-scope
(noted for completeness; no new mechanism arms it).

---

## 5. Charter item 4 — shared static state introduced across the six steps

### 5.1 Sweep result

Diff-wide grep for added `static` declarations in production code:

| Addition | Mutable? | Verdict |
|---|---|---|
| `SharedContext.BLOB_COLLECTION_NAME_PATTERN` (`:146-153`) | No (`Pattern` is immutable/thread-safe) | clean |
| `SharedContext.GENESIS_COMPLETED_PROPERTY` (`:160`) | No (String constant) | clean |
| `MetadataDefault.COLLECTION_INTERNAL_ID` / `BLOB_COLLECTION_NAME_PREFIX` (`:46/:61`) | No | clean |
| `GlobalConfiguration.EXPORT_RECORD_SPILL_THRESHOLD` (`GlobalConfiguration.java:278-285`) | Mutable like every global config, but production code only READS it (once per exporter ctor, via the session config); no production writer in the diff | clean |
| `DatabaseExport.EXPORTER_VERSION`, `PRIORITY_EXPORT_CLASSES` | No (int constant; `Set.of`) | clean |
| `DatabaseImport.MIN_IMPORTABLE_SCHEMA_VERSION`, `logger` | No | clean |
| `ValidatedGZIPInputStream` statics (`GZIP_MAGIC`, flag masks, `TRAILER_LENGTH`, …) | No | clean |
| `JSONReader` `public static final char[]` tokens | Pre-existing (diff is whitespace-only reformatting); technically mutable arrays, nobody writes them — unchanged exposure | out-of-scope |
| `FileUtils.durableAtomicMove` | Stateless static method | clean |

**No new mutable static/shared state was introduced by Track 8.** The only process-global
mutable state the tools TOUCH remains the pre-existing `INDEX_IGNORE_NULL_VALUES_DEFAULT` flip
(CS72, §4.3 — not widened). **Null verdict.**

### 5.2 Post-step-3 fix code (new concurrency surface not covered by concurrency-step3-iter1)

`unregisterGenesisIncompleteCorpse` (`YouTrackDBInternalEmbedded.java:468-492`) — added by the
step-3 fix commit to close CN55/CS55 (the javadoc cites BG14/CN55/CS55; the
`GenesisIncompleteException` javadoc's "storage is closed and unregistered" now matches the code).
Checked as new code:

| # | Interleaving | Verdict |
|---|---|---|
| G1 | A: open → refusal → helper removes maps + `sharedContext.close()` + `storage.shutdown()`. B: concurrent open of the same name | **checked — excluded**: the helper is `synchronized` on the factory, and every caller already holds the monitor (open paths `:328-352/:370-383/:394-406/:421-434`, pool paths `:534-545/:555-566`, create-adopt arm `:851-861`) — reentrant, no unlock window between refusal and cleanup. B blocks, then `getOrInitStorage` re-initializes from disk and re-refuses. Null. |
| G2 | A usable session exists on the context when the helper shuts the storage down | **checked — impossible**: the refusal fires from `SharedContext.load` before `loaded = true` (`SharedContext.java:147-186`, `loaded` volatile at `:73`); every session mint runs load-first under the monitor, so a marker-less context never produced a usable session. Null. |
| G3 | Cleanup failure masks the refusal | **checked — suppressed-attach only** (`:481-490`). Null. |

---

## 6. Findings

### CN60 — should-fix (pre-existing UB, newly load-bearing) — export iterates LIVE storage/schema collection views with no lock: under concurrent DDL the dump can silently omit whole collections while exit-0 + manifest verification report success

- **Locations:** `DatabaseExport.getMaxCollectionId` (`DatabaseExport.java:446-454`; call sites
  `:326` records, `:476` collections) iterating the escaped live keySet view from
  `AbstractStorage.getCollectionNames` (`AbstractStorage.java:2358` — `unmodifiableSet(keySet)`
  returned AFTER `stateLock.readLock` is released; passthrough at
  `DatabaseSessionEmbedded.java:4273-4279`); second instance:
  `DatabaseExport.java:610` → `DatabaseSessionEmbedded.getBlobCollectionIds:5028-5031` →
  `SchemaShared.getBlobCollections:1690-1698` (live `IntSet` behind an unmodifiable wrapper,
  schema read lock released before `toIntArray()` iterates).
- **Premises.** (1) The export holds no lock and no freeze across sections (§3.1). (2) A
  concurrent session's DDL commit mutates the backing `HashMap` under `stateLock.writeLock`
  (`:7034` put, `:3559` remove, `:1240`, `:7170` rename) — a lock the iterating exporter does not
  hold. (3) `HashMap` iteration concurrent with structural modification is JMM-undefined:
  best-effort `ConcurrentModificationException` OR silent missed/duplicated keys (the modCount
  read is not synchronized). (4) `getMaxCollectionId`'s result bounds BOTH the collections section
  and the records loop (`for (i = 0; exportedCollections <= maxCollectionId; ++i)`, `:326-334`) —
  an under-read max truncates the export.
- **Counterexample interleaving (silent arm).** T_writer: `createClass("Audit2026")` commit begins
  → `stateLock.writeLock` → `collectionMap.put` triggers a HashMap resize. T_export
  (simultaneously, inside its tx at `:476` or `:326`): iterates the escaped keySet with no lock →
  the resize transfer causes the iterator to skip the bucket holding the highest-id collection
  name, without tripping modCount → `maxCollectionId` comes back smaller than the true max →
  collections with higher ids — **including ones that existed before the export began and still
  exist after it** — are never scanned. Every per-section tally counts only what was written
  (E11), so the manifest is self-consistent; the export promotes; the wrapper exits 0; a later
  import verifies perfectly. The operator's backup silently lacks whole collections. (Loud arm:
  the same race throws CME → `DatabaseExportException` → no promote — fail-closed, acceptable.)
- **Why should-fix, not suggestion:** the iteration shape is pre-existing, but Track 8 (a) newly
  contemplates concurrent-DDL exports as a supported scenario (FM-M15/FM-M17 counterexamples,
  drafts `:519`), and (b) newly instructs operators to TRUST exit-0 + manifest verification
  (runbook step 2/5) — the silent arm defeats exactly that contract, and R-5's idle mandate covers
  only the migration procedure, not backups. Not a blocker: the window needs a DDL commit racing a
  two-call iteration, and the corruption is bounded to dump completeness (target DB unharmed).
- **Fix shape:** copy inside the lock — `getCollectionNames` returns `Set.copyOf(...)` (or
  `List.copyOf`) BEFORE releasing `stateLock.readLock` (one line; callers already treat it as
  read-only); same for `getBlobCollections` (`IntArraySet` copy under the schema read lock).
  `SharedContext.create:274`'s `List.copyOf` outside the lock is then redundant-but-harmless
  (genesis is monitor-excluded).
- **Alternative hypotheses checked:** (i) "the export tx blocks DDL commits" — no: MVCC commits
  proceed; export holds neither the metadata-write mutex nor `stateLock`; (ii) "the view is a
  copy" — refuted by code (`:2358`) and by the repo's own CQ14 comment ("returns a live view of
  the storage's collection map", `SharedContext.java:269-273`); (iii) "CME is guaranteed" — no:
  best-effort only; silent anomalies are permitted by the spec.

### CN61 — suggestion — the live-export cross-section consistency envelope is nowhere recorded: only the manifest is point-in-time-free; records are snapshot-S₀ while schema/collections/blob/index sections are live reads at later instants

- **Locations:** `DatabaseExport.java:206` (single tx), `:604` (fresh schema snapshot — no pin
  held, `MetadataDefault.java:155-163`), `:610` (live blob set), `:476/:326` (live collection
  listings at two different instants), `:546` (live IM reload); per-record fresh schema pin
  `DatabaseSessionEmbedded.java:2226`. Design anchors: drafts M2.a-5/:519 (manifest-only promise),
  pass-1 E1 wording ("single tx snapshot"), runbook step 1 ("otherwise idle" — migration only).
- **Detail.** §3.2 E13–E15: under concurrent DDL the dump's sections can mutually disagree —
  schema newer than records (benign), collections listing dropped collections (benign),
  records carrying class names the schema section lacks (rename interleaving E15). The E15 shape
  composes with the pre-existing CS64 swallow into an exit-0 import that silently drops those
  records — an honest exporter + honest importer + live source = silent loss, with every Track 8
  verification green (manifest counts CONSUMPTION, and consumption succeeded). Everything here is
  behavior the baseline also had; what is missing is the RECORD: neither the design drafts nor the
  runbook states that a dump taken under concurrent DDL is per-section point-in-time only, and the
  pass-1 E1 null verdict reads stronger than the as-built truth.
- **Counterexample gist (E15).** t₂: schema section written, class `Person` present. t₂+ε: B
  renames `Person`→`Party`, commits. t₃: export renders `Person`-collection records — each render
  pins a FRESH snapshot → records serialized with `"class":"Party"`. Import: schema creates
  `Person`; record apply for `Party` fails with a `DatabaseException` → swallowed (`:1592-1604`)
  → tally counts it consumed → manifest verify passes → exit 0; `Party` records absent.
- **Ask:** one paragraph in drafts M2.a (or the runbook's step-1/step-2 notes): "a dump taken
  while DDL runs concurrently is guaranteed manifest-consistent but NOT cross-section
  point-in-time consistent; export idle (or freeze) for full-fidelity backups"; optionally
  correct the pass-1 E1 wording in the next design errata sweep. No code change demanded (a
  cross-section-consistent export would need a schema-snapshot pin for the tx duration plus
  collection-set capture at S₀ — a design change beyond this track's scope).

### CN62 — suggestion — import ctor's `Files.size`→`open` TOCTOU: a concurrent re-export promoting between the two calls yields a physical-size false rejection AFTER full import (condemned target from a pure filesystem race)

- **Location:** `DatabaseImport.java:198-201` (`physicalSize = Files.size(path)` at `:198`, the
  pinning `new FileInputStream(fileName)` at `:201`); consumed by
  `verifyV15StructuralStrictness`'s step-(3) arithmetic (`:633-639`,
  `ValidatedGZIPInputStream.verifyPhysicalSize`).
- **Interleaving.** T_import: `Files.size("dump.gz")` reads the OLD dump's size. T_export
  (scheduled backup on another host process, same directory): `durableAtomicMove` atomically
  replaces `dump.gz` with a NEW dump. T_import: `new FileInputStream("dump.gz")` opens the NEW
  inode → the whole import runs against the new (valid!) dump → step (3) compares the new dump's
  consumed bytes against the OLD dump's captured size → mismatch → "trailing data"-style rejection
  — post-mutation, so the target is condemned (SR1) despite both artifacts being healthy.
- **Why only suggestion:** millisecond-scale window, requires an exporter racing an importer on
  the same path (the runbook's procedure never does this), and the failure is loud + fail-closed
  (a re-run succeeds). But the fix is one line: capture the size from the OPENED stream
  (`FileChannel.size()` on the `FileInputStream`'s channel, or reorder open-then-size on the same
  descriptor), making size and bytes provably refer to one inode.
- **Alternative hypothesis checked:** "the gzip trailer/CRC would catch it first" — no: both dumps
  are internally valid; only the size arithmetic cross-references the stale stat. "POSIX inode
  pinning protects the read" — it protects the STREAM (yes), which is exactly why only the
  size/stream cross-reference desynchronizes.

---

## 7. Null verdicts per clean scope item

- **N-C1 (Step 1 blob registration):** creation is WAL-atomic inside `doCreate` on the monitored
  creating thread; registration enumerates storage state by name inside the monitor-excluded
  phase-1 tx; the config is read exactly once (CN50 remedy verified as-built at
  `AbstractStorage.java:1520` + `SharedContext.java:274-277`); no interleaving with concurrent
  DDL/DML is reachable (§2 B1–B3).
- **N-C2 (R3 sweep):** test-only; no production concurrency surface (§2 B4).
- **N-C3 (export manifest provenance):** exporter-tallied on every section (§3.2 E11); importer
  verifies against its own tallies with zero target queries (§4.2). CN51/FM-M17 discharged
  as-built in both directions.
- **N-C4 (concurrent exporters / promote):** CN52 remedy verified as-built (unique `CREATE_NEW`
  temp `:140-143`, whole-file promote `FileUtils.java:346-375`, collision-free spill files);
  last-promote-wins with internally consistent dumps (§3.2 E18–E19).
- **N-C5 (records isolation):** in-tx record reads resolve at the tx-begin MVCC view
  (`getEffectiveReadAtomicOperation`, §3.1) — pass-1 P8/E1 confirmed for the records section.
- **N-C6 (import preamble deferral):** same mutations, strictly later, pre-flight rejections now
  genuinely pre-mutation; no new interleaving class, mutex parking deadlock-free (§4.1 I10–I11).
- **N-C7 (CS72 widening — the charter's explicit question):** the flip block is byte-identical to
  the baseline and absent from the diff; Steps 4–6 added no code inside the window and no new
  flips anywhere. NOT widened (§4.3).
- **N-C8 (`setUser(null)` / validation / link-consistency flags):** session-confined; restored
  (validation, link-consistency) or already-filed-unrestored (user, CS71) with an unchanged
  envelope; no cross-session edge (§4.3).
- **N-C9 (shared static state):** no new mutable static state in the diff; new statics are
  constants/immutables; `EXPORT_RECORD_SPILL_THRESHOLD` is read-only in production (§5.1).
- **N-C10 (post-step-3 refusal-cleanup helper):** monitor-held on every path, no usable session
  can exist on a refused context, suppressed-attach cleanup — CN55/CS55 fix introduces no new
  race (§5.2 G1–G3).
- **N-C11 (shared-IM reload during export/import):** internally serialized, pre-existing call
  sites, windows unchanged or narrowed (§3.1, §4.1 I14).
- **N-C12 (`skipRoleHasPredicateSecurityForClassUpdate`):** two-phase flip covers a strictly
  smaller span than the baseline's single flip; no new concurrent caller (§4.1 I13).

## 8. Hypothesis log

| # | Hypothesis | Test performed | Outcome |
|---|---|---|---|
| H1 | The genesis register loop can race post-genesis DDL (charter item 1) | traced all callers of `SharedContext.create` (genesis only, monitor-held); steps 4–6 never touched the genesis files (`git log` per file) | REJECTED (null) — §2 |
| H2 | Export holds a freeze or pin that serializes DDL against it | grepped freeze/pin call sites; only per-read pins (`:2226`) and the commit-path pin (`:2585`); no freeze call in the diff or file | REJECTED — export holds nothing; consequences analyzed instead (§3.1) |
| H3 | The export tx's snapshot covers the schema section (pass-1 E1 literal reading) | traced `getImmutableSchemaSnapshot` → pin count 0 in a read tx → fresh `makeSnapshot` of the shared cache | REJECTED — records-only isolation; → CN61 |
| H4 | `getCollectionNames` returns a copy (no iteration race) | read `AbstractStorage.java:2344-2371`; unmodifiable VIEW of the live keySet, lock released; corroborated by the repo's own CQ14 comment | REJECTED → CN60 |
| H5 | A concurrent DDL commit is blocked from mutating `collectionMap` while export iterates | commit path takes `stateLock.writeLock` (`:7034`), exporter iterates lock-free after release; no mutex/tx edge connects them | REJECTED → CN60 |
| H6 | HashMap CME is guaranteed (race always loud) | JDK spec: best-effort modCount, unsynchronized read; silent skip on racy resize is permitted | REJECTED → CN60 silent arm |
| H7 | Manifest tallies re-derive anything at manifest/close time (CN51 regression) | read every `manifest*` assignment (`:412/:560/:632/:784`); all increment-at-write | REJECTED (null) — N-C3 |
| H8 | Step 5/6 widened the CS72 flip window | diff hunk inspection: flip block absent from diff, byte-identical at baseline `:1417-1429`; `parsedIndexCount++` outside the window | REJECTED (null) — N-C7 |
| H9 | Importer strictness reads target-DB state (CN51 import side) | read `verifyV15StructuralStrictness`/`verifyManifestCount`; instance tallies only | REJECTED (null) — N-C3 |
| H10 | New mutable static state exists somewhere in the diff | diff-wide `grep '^\+.*static'` + per-hit classification | REJECTED (null) — N-C9 |
| H11 | The CN55-fix helper opens a refusal↔open race window | monitor reentrancy on all six caller paths; `loaded` volatile; no usable session pre-marker | REJECTED (null) — N-C10 |
| H12 | The import ctor's size capture and stream open are atomic w.r.t. concurrent promote | `Files.size(path)` at `:198` precedes `FileInputStream` at `:201` — distinct path resolutions | CONFIRMED gap → CN62 |
| H13 | Live-export section skew always yields loud import failures (no silent arm) | traced E15: unknown-class record apply → `DatabaseException` → `:1592-1604` swallow → tally satisfied | REJECTED → CN61 (composition with CS64) |
| H14 | The blob-set read in exportSchema is lock-covered end-to-end | `SchemaShared:1690-1698` releases the lock before the caller's `toIntArray()` iteration | REJECTED — folded into CN60 |

---

## Compact findings block

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| CN60 | should-fix (pre-existing UB, newly load-bearing) | DatabaseExport.java:446-454 (:326,:476,:610) + AbstractStorage.java:2358 + SchemaShared.java:1690-1698 | Export iterates LIVE `collectionMap` keySet / blob `IntSet` views AFTER the guarding lock is released; concurrent DDL commit mutates the backing HashMap during iteration — JMM-undefined: loud CME aborts fail-closed (fine), silent missed key truncates `getMaxCollectionId` → whole collections omitted from an exit-0, manifest-verified dump. Fix: copy under the lock in `getCollectionNames`/`getBlobCollections` | writer's `createClass` commit resizes `collectionMap` while export iterates the escaped view → max id under-read → high-id collections (pre-existing ones included) never scanned → manifest self-consistent, promote, exit 0 — silent backup gap |
| CN61 | suggestion | DatabaseExport.java:206,:604,:610,:476,:326,:546; drafts M2.a-5/:519; runbook step 1 | Live-export cross-section envelope unrecorded: records = tx-begin snapshot; schema/collections/blob/index sections = live reads at later instants (no freeze, no pin). Manifest-only consistency is all the design promises — but nothing records that, and pass-1 E1's "single tx snapshot" overstates. Rename-mid-export composes with CS64 into exit-0 silent record loss | rename `Person`→`Party` between schema section and record render → records carry `Party`, schema carries `Person` → import: apply fails, swallowed, tallies match, exit 0, records absent |
| CN62 | suggestion | DatabaseImport.java:198-201 + :633-639 | `Files.size(path)` then `new FileInputStream(path)`: a concurrent export promoting between the calls makes step-(3) physical-size arithmetic compare the NEW dump's bytes against the OLD dump's size → loud false rejection AFTER full import (target condemned per SR1) from a pure FS race. Capture size from the opened descriptor instead | cron re-export atomically replaces `dump.gz` in the microseconds between the importer's stat and open → healthy dump imports fully, then rejects on size arithmetic → fresh target discarded, re-run needed |

**Blockers: 0. Should-fix: 1 (CN60). Suggestions: 2 (CN61, CN62).**

Clean-scope confirmations: Step-1 blob registration and the R3 sweep are interleaving-free as
designed (N-C1/N-C2); manifest provenance (CN51) and concurrent-exporter isolation (CN52) are
implemented exactly as ruled on both tool sides (N-C3/N-C4); the import preamble deferral adds no
new interleaving class (N-C6); CS72's window was NOT widened by Step 6 or any other step (N-C7);
no new mutable shared static state exists in the cumulative diff (N-C9); the post-step-3
genesis-refusal cleanup helper is race-free under the factory monitor (N-C10).
