# Concurrency review — Track 8 Step 3 (two-phase genesis + failure containment) — iteration 1

- **Commit under review:** `4d23111516` ("Restructure genesis into two phases with failure
  containment"), branch `transactional-schema`, HEAD `680147b578` (differs from the reviewed
  commit only by the track-8.md episode record — verified via `git diff 4d23111516 680147b578
  --stat`).
- **Perspective:** concurrency. Finding IDs CN55+.
- **Binding spec:** track-8.md Step 3; track-8-design-drafts.md §G2.c, §G.4 (I-U4), §A1
  (W-table, mechanisms 1–5, CS45/CN54), WI9 lock-interaction argument, CN53/OBS-2 constraint.
- **Read-only review:** no build, no Maven, no file modification outside this report.
- All file:line citations are against the working tree at HEAD `680147b578` (production files
  identical to `4d23111516`).

---

## 0. Semi-formal obligations

| # | Criterion | Premises |
|---|---|---|
| O1 | I-U4: the phase-1 genesis tx engages the MetadataWriteMutex exactly once, holds it across the schema-carry commit only, and phase 2 never engages it. | Mutex engagement has a single funnel (`ensureTxSchemaState`); release fires at the outermost tx frame close; phase 2 contains no schema/index-DDL write. |
| O2 | Genesis visibility: no session-minting path can observe the between-phases state (schema committed, no security) or a marker-less mid-genesis storage. | The factory monitor (`synchronized (this)` on `YouTrackDBInternalEmbedded`) spans storage create + phase 1 + phase 2 + marker write; every session-minting path acquires the same monitor and runs `checkGenesisCompleted` before minting; cross-factory access to the same directory is excluded (disk-storage exclusivity, pre-existing premise, out of scope). |
| O3 | Drop-path exemption (CN54): `drop()` of a marker-less corpse deletes it without surfacing the refusal, and every drop/open/create interleaving over the corpse stays fail-closed. | The refusal is reachable in the drop's open only as a `GenesisIncompleteException` somewhere in the cause chain of a `RuntimeException`. |
| O4 | The `saveInternal` tracker suppression and the `IndexAbstract.delete`/`rebuild` explicit unlink remove no synchronization and no cross-session consistency the legacy tracked path provided. | The bidirectional-link tracker is a same-session validation/maintenance mechanism (session-confined `ensureLinkConsistency` flag), not a lock; index records have no referrer other than the index-manager root's `CONFIG_INDEXES` set. |
| O5 | The EntityLinkSetImpl embedded pin makes the embedded→btree conversion impossible mid-commit-window for internal-metadata-collection records, and the recorded residual-race characterization is accurate. | `internal` is collection 0 on every profile; metadata link sets are created through owner-carrying constructors; serialization-time conversion is triggered only by `checkAndConvert`. |
| O6 | The system-DB creation path composes with the restructured `SharedContext.create` without NEW concurrent first-touch exposure (CN53 known/deferred). | `SystemDatabase` is untouched by the commit; its `exists`/`create`/open calls all route through the factory monitor. |

---

## 1. O1 — I-U4 as implemented

### 1.1 Engagement trace (exactly one, phase 1 only)

- Phase 1 is ONE `session.executeInTx(...)` — `SharedContext.java:225-262` — opened on the
  brand-new genesis session (`newCreateSessionInstance`,
  `YouTrackDBInternalEmbedded.java:352-357` → `DatabaseSessionEmbedded.internalCreate:572-586`
  → `createMetadata:598-603` → `SharedContext.create:207`). No outer transaction can exist on
  that session (it is constructed inside `internalCreate`; the Step-2 CQ15 entry assert in
  `SchemaShared.create` would trip otherwise).
- Every DDL inside phase 1 routes through the session's schema proxy:
  `security.createSecuritySchema` (`SecurityShared.java:612-631`) uses
  `session.getMetadata().getSchema().createAbstractClass/getClass/...`;
  `FunctionLibraryImpl.create → init` (`FunctionLibraryImpl.java:57-59`, init body verified —
  proxy `createClass`/`createProperty`/`createIndex` only); `SequenceLibraryImpl.create → init`
  (proxy `createClass`); `SchedulerImpl.create` (`SchedulerImpl.java:159-171`, proxy
  `createClass`/`createProperty`); the O/V/E classes and the blob registration use
  `sessionSchema.createClass(...)` / `sessionSchema.addBlobCollection(...)`
  (`SharedContext.java:231-234`, `:257-261`; `SchemaProxy.java:462` routes
  `addBlobCollection` through `resolveForWrite()` — the CS47 re-route is real).
- Tier-3 write resolution is the single mutex funnel: `SchemaProxedResource.resolveForWrite`
  seeds via `DatabaseSessionEmbedded.ensureTxSchemaState` (`DatabaseSessionEmbedded.java:3616-3663`),
  which engages the mutex ONLY when no `TxSchemaState` exists for the transaction
  (`:3622-3626` early return; engage at `:3631` → `engageMetadataWriteMutex:3856-3896` →
  `MetadataWriteMutex.engage:123-185`). Every subsequent write in the same tx finds the seeded
  state and returns without touching the permit. The comment at `:3628-3631` pins this as the
  single seam ("engaging here covers every write path with one placement").
- **Release before phase 2:** the permit is released in the outermost transaction frame's close
  — `FrontendTransactionImpl.closeInternal`'s finally
  (`FrontendTransactionImpl.java:1065-1076`, "Outermost transaction frame is now closed …
  release the permit now"). `executeInTx` (`DatabaseSessionEmbedded.java:5108-5151`) commits
  and closes at the end of the phase-1 lambda, i.e. strictly BEFORE
  `security.insertDefaultSecurity(session)` runs (`SharedContext.java:265`). So the mutex is
  held for the phase-1 body + schema-carry commit only.
- **Held across the commit:** `commitSchemaCarry` (`AbstractStorage.java:3293-3348`) documents
  and relies on "the mutex is already engaged (first schema write)" (`:3307`) and takes the
  remaining lock ladder (committed `SchemaShared.lock` → IM commit lock →
  `stateLock.writeLock`) inside it. The commit runs inside the tx, before the close that
  releases. ✓

### 1.2 Phase 2 never engages the mutex

- Phase 2 is `SecurityShared.insertDefaultSecurity` (`SecurityShared.java:634-667`): ONE
  `session.computeInTx` (`:646-659`) inserting role/user/policy RECORDS
  (`createDefaultAdminRole/ReaderRole/WriterRole`, `createUser`), plus
  `initPredicateSecurityOptimizations` (`:664` → `:1220-1237`) — a read-only query tx.
- Schema-touching calls inside phase 2 were traced individually:
  `setSecurityPolicyWithBitmask → setSecurityPolicy` (`SecurityShared.java:456-500`) reads the
  IMMUTABLE snapshot (`validatePolicyWithIndexes:503-537`) and saves role/policy records;
  `updateAllFilteredProperties` (`:1756-1772`) and `calculateAllFilteredProperties`
  (`:1793-1820`) are query-only. None reaches `resolveForWrite` on the schema or the
  index-manager DDL seam; index maintenance for the UNIQUE inserts flows through
  `transaction.addIndexEntry` (`IndexAbstract.java:864-876`), which records key changes in the
  tx's index-change map and never seeds `TxSchemaState`.
- The pure-data commit branch is taken (`AbstractStorage.java:2565-2566`: `txSchemaState ==
  null` → `schemaCarry == false` → read-lock fast path `:2597-2604`), so no mutex, no
  freezer probe, no metadata write locks.
- **Empirical pin:** `TwoPhaseGenesisTest.mutexEngagedInPhaseOneOnlyAndPhaseTwoCommitsOnce`
  (`TwoPhaseGenesisTest.java:92-131`) observes, through the config-registered
  `SessionListener`, exactly ONE mutex-engaged commit and exactly ONE record-carrying commit
  after it, with `mutexEngaged == false` — using `MetadataWriteMutex.isEngagedBy`
  (`MetadataWriteMutex.java:229-236`, test-observability probe as designed).

**Verdict O1: DISCHARGED.** One engagement (phase-1 first write), held for the schema-carry
commit, released at the phase-1 tx close, none in phase 2.

### 1.3 Phase-1 schema-carry commit on a virgin storage vs steady state

Checked interactions, exhaustively against the Track 7 machinery:

| Track-7 element | Genesis behavior | Differs from steady state? |
|---|---|---|
| Freezer-gate checkpoint (1), entry probe (`AbstractStorage.java:2574-2576`) | probed; `isOperatorFreezeActive()` cannot be true — an operator freeze requires a session on this storage, and no session other than the genesis session can be minted (O2 monitor argument, §2) | no — same code path, trivially passes |
| Freezer-gate checkpoint (2), `exclusiveLockWithAbort` (`AbstractStorage.java:3324-3326`) | uncontended write-lock acquisition (no readers exist) | no |
| Mid-commit operator freeze / backup | impossible: `freeze()`/backup entry points need a minted session; every mint parks on the factory monitor the creator holds (see §2.1). The only code that could freeze mid-genesis is a config-registered `SessionListener` running ON the creating thread — self-inflicted, aborts the commit, contained by cleanup | no new interaction |
| Four-lock ladder (`commitSchemaCarry`, `AbstractStorage.java:3307-3348`) | nested entirely inside `SharedContext.lock` (held by `SharedContext.create:208`) — the WI9-designed genesis-only extra edge | yes — designed; see lock-order analysis below |
| Fresh-committed-read scopes (`computeWithFreshCommittedReads`, `DatabaseSessionEmbedded.java:3730+`; consumers at `AbstractStorage.java:2908-2916`, `:2938-2945`) | reads the Step-2 bootstrap-valid root committed by `schema.create`'s own pre-tx `computeInTx` — a genuinely committed record, exactly what the scope's premise needs | no |
| Link-tracker suppression window (`AbstractStorage.java:2885-2947`) | identical | no |
| Commit window / lock-free reads (`enterCommitWindow`, `AbstractStorage.java:3333-3340`) | identical | no |
| Trailing `forceSnapshot` + `MetadataUpdateListener` fan-out | fires into the two plan caches registered at `SharedContext.init` (`SharedContext.java:130-137`); no other consumer exists yet | no |

**Lock-order (WI9) as-built.** The genesis-only edge `factory monitor → SharedContext.lock →
mutex → SchemaShared.lock → IM lock → stateLock.write` is acyclic because every OTHER
thread's first blocking acquisition against this storage is the factory monitor itself
(`open`/`openNoAuthenticate`/`openNoAuthorization`/`poolOpen*`/`create`/`drop`/`exists`/
`getStorage`/`getStorages`/`forceDatabaseClose`/`loadAllDatabases`/`checkAndCloseStorages` are
all `synchronized (this)` — `YouTrackDBInternalEmbedded.java:327, 362, 384, 407, 518, 533,
751, 890/921, 1034` and the method-level `synchronized` declarations), so no second thread can
hold any inner lock of the ladder while the creator holds the monitor. Steady state adds no
reverse edge: no mutex-holding path takes `SharedContext.lock` (searched all
`SharedContext.lock` acquirers — `create`/`load`/`close`/`reload`/`reInit`,
`SharedContext.java:147, 172, 190, 207, 293`; none runs under an engaged mutex).
*Pre-existing caveat (logged, not a Step-3 finding):* `SharedContext.load` runs
`security.load` inside an `executeInTx` while holding `SharedContext.lock`
(`SharedContext.java:152-166`); for a legacy database missing `OSecurityPolicy`,
`setupPredicateSecurity` (`SecurityShared.java:1089-1112`) performs DDL inside that active tx,
which would engage the mutex under `SharedContext.lock` — the same edge outside genesis. Not
reachable for any database this branch creates (genesis always creates the class), untouched
by this commit, no completing cycle found. Out of scope.

**Verdict:** no interaction differing from the steady-state Track 7 path beyond the designed
WI9 nesting; no freeze/backup interleaving is reachable mid-genesis.

---

## 2. O2 — Genesis visibility

### 2.1 Factory-monitor span, re-verified against the as-built `createStorage`

`createStorage` (`YouTrackDBInternalEmbedded.java:745-817`): the `synchronized (this)` block
at `:751` encloses `exists(name)` (`:752`), storage construction (`:770-783`),
`storages.put(name, storage)` (`:784`), `internalCreate` (`:785` → `storage.create` +
`newCreateSessionInstance` → `SharedContext.create` — BOTH phases AND the marker write
`SharedContext.java:283`), `createOps.accept` (`:788` — the restore path), the containment
catch (`:790-800`, `cleanUpFailedCreate:796` runs INSIDE the monitor), and the first
`callOnCreateListeners` (`:803`). Only the second, duplicate `callOnCreateListeners`
(`:816`, the pre-existing double-call CN observation) sits outside — by then the marker is
durable and the create complete. **The cleanup-on-exception restructuring moved no genesis
work outside the monitor.** ✓

### 2.2 Session-minting path enumeration (marker check coverage)

Every path that constructs a `DatabaseSessionEmbedded` for use:

| Path | Monitor? | Marker check? |
|---|---|---|
| `open(name,user,pwd[,cfg])` (`:362-376`) | `synchronized (this)` around `getAndOpenStorage` | ✓ `getAndOpenStorage:446` |
| `open(AuthenticationInfo,cfg)` (`:399-421`) | ✓ | ✓ |
| `openNoAuthenticate` (`:322-341`) | ✓ | ✓ |
| `openNoAuthorization` (`:379-397`) | ✓ | ✓ |
| `poolOpen` / `poolOpenNoAuthenticate` (`:516-546`) | ✓ | ✓ |
| `execute`/`executeNoAuthorization*` (`:1147+`) | delegate to the above | ✓ |
| create path (`newCreateSessionInstance:352`) | inside the creator's own monitor | correctly UNCHECKED (marker not yet written) |
| `initCustomStorage` (`:1113-1130`) | method + inner `synchronized` | mints a session only on the CREATE arm (marker written by its genesis); the adopt arm mints nothing — later opens route through `getAndOpenStorage` ✓ — but see CN58 |
| `loadAllDatabases` (`:530-543`) | ✓ | opens STORAGES only, mints no session — a marker-less storage loaded here is still refused at first session mint ✓ |

No bypass found. **Null verdict justified:** the check sits on the single storage-resolution
funnel (`getAndOpenStorage:430-452`) that all seven mint paths share.

### 2.3 Marker write/read race

- Write: `storage.setProperty(GENESIS_COMPLETED_PROPERTY, "true")` (`SharedContext.java:283`)
  — inside `SharedContext.lock` AND the factory monitor; its own atomic operation
  (`AbstractStorage.setProperty:8369-8391`), matching the design's "own durability event"
  (W9a).
- Read: `checkGenesisCompleted` (`YouTrackDBInternalEmbedded.java:464-472`) via
  `AbstractStorage.getProperty:8393-8411` under `stateLock.readLock` — but every reader first
  acquires the factory monitor, which the creator holds until after the marker write. The
  monitor supplies both mutual exclusion and the happens-before edge; the storage `stateLock`
  is a second belt. **A concurrent open can never read the marker mid-genesis; it reads it
  strictly after the create's monitor release, i.e. marker present (success) or storage absent
  (cleaned-up failure).**
- Between-phases observation (schema committed, no security): requires minting a session or
  reading `sharedContexts`/`storages` between phase 1 and phase 2 — all such reads are
  monitor-gated (§2.1 table + `getOrCreateSharedContext:857-865` synchronized). `loaded`
  (volatile, `SharedContext.java:73`) flips true only after the marker write. **Null verdict:
  no interleaving exists within one factory.** Cross-factory/cross-process access to the same
  directory is the pre-existing storage-exclusivity premise, out of scope.

### 2.4 Refusal aftermath (found: CN55, suggestion)

`getAndOpenStorage` removes the storage from `storages` when `storage.open` throws
(`:435-439`) but NOT when `checkGenesisCompleted` throws (`:446`): a refused marker-less
corpse stays REGISTERED and OPEN in `storages`. Consequences (traced):

- Every later open re-refuses (fail-closed preserved; `storage.open` no-ops on an open
  storage).
- `exists(name)` → `storages.get(name).exists()` → true → `create(name, failIfExists=false)`
  logs "already exists, nothing to do" (`:806-809`) and silently no-ops **over a crash
  corpse**. Concrete interleaving: crash in W6 → restart → T1 `open()` refused (storage now
  open+mapped) → T2 `create(failIfExists=false)` no-op → T2's later `open()` refused. No
  unsafe state ever opens, and the design scopes the re-create guarantee to the EXCEPTION path
  (§A1 mechanism 1; the W6/W7 remedy is the open-time refusal + drop), so this is
  design-consistent — but the track Goal's phrasing ("can neither be silently reopened nor
  silently no-op'd over by create-retry") reads broader than the implementation delivers for
  the crash path, and the refused-open storage additionally pins OS resources (file handles,
  disk-storage exclusivity) until `drop()`/factory close. Suggest either removing/closing the
  storage on refusal (symmetric with the `storage.open` failure arm) or recording the
  asymmetry explicitly.

---

## 3. O3 — drop() vs concurrent open/create of the same corpse (CN54)

### 3.1 Cause-chain catch correctness

- The refusal thrown at `getAndOpenStorage:446` propagates out of `openNoAuthenticate`'s
  synchronized block and is wrapped ONCE:
  `BaseException.wrapException(new DatabaseException("Cannot open database…"), e, …)`
  (`YouTrackDBInternalEmbedded.java:336-339`). `wrapException`
  (`BaseException.java:39-63`) attaches the cause via `initCause` (GenesisIncompleteException
  implements no `HighLevelException` — verified `GenesisIncompleteException` →
  `DatabaseException` → `CoreException` → `BaseException extends RuntimeException`, none
  implements it), so the chain is `DatabaseException → GenesisIncompleteException`.
- `drop()` catches `RuntimeException` (`:898`) and walks the chain including the failure
  itself (`isCausedByGenesisIncomplete:479-486`, loop starts at `failure`), so both the
  wrapped and any unwrapped/cloned shape (the copy constructor at
  `GenesisIncompleteException.java:23-25` preserves the type for `logAndPrepareForRethrow`
  cloning) are recognized. Non-genesis open failures rethrow (`:904-906`) — pre-change
  behavior — and the finally still deletes, as before. ✓

### 3.2 Interleavings over the corpse (enumerated)

drop() is three monitor sections: (a) `checkOpen` (`:890-892`), (b) the internal
`openNoAuthenticate` (its own `synchronized`), (c) the finally's delete block (`:921-939`).
Between (b) and (c) the monitor is free.

| Interleaving | Outcome | Verdict |
|---|---|---|
| T1 drop(corpse) ∥ T2 drop(corpse) | both refused at (b), serialized; first (c) deletes + purges maps, second (c) sees `exists()==false` → skips | consistent, no double-free (`currentStorageIds.remove` keyed by the captured `storageId:928`) |
| T1 drop(corpse) between (b) and (c) ∥ T2 open(corpse) | T2 refused (same GenesisIncompleteException) — or, if T2 lands after (c), "does not exist" | fail-closed either way |
| T1 drop(corpse) between (b) and (c) ∥ T2 create(failIfExists=false) | T2 no-ops on the still-existing corpse (CN55 shape); T1 then deletes → T2's presumed DB vanishes | pre-existing drop-vs-create race: at HEAD~ the same interleaving existed (the W6/W7 corpse OPENED silently at HEAD~, so drop's (b) succeeded and (c) deleted identically). No NEW exposure |
| T1 drop(corpse) between (b) and (c) ∥ T2 drop+T3 create(fresh healthy DB, same name) completing first | T1's (c) `exists()` → true → deletes the NEW healthy DB | pre-existing drop-by-name semantics (the finally always deleted whatever exists — unchanged by this commit). Out of scope |
| T1 create(name) mid-genesis ∥ T2 drop(name) | T2 parks on the monitor in (b); after T1 succeeds → normal drop; after T1 FAILS → cleanup ran, T2's open throws "Cannot open…does not exist"-shaped failure → NOT genesis-caused → rethrown, finally `exists()==false` → skip | consistent; matches HEAD~ shape for dropping a nonexistent DB |

**Verdict O3: DISCHARGED** — the exemption is correctly scoped (only the genesis refusal is
tolerated; `onDrop` skipped only then, `db.close()` guarded by `db != null`, `:911-917`), and
no new corpse-related interleaving was found. Pinned by
`GenesisFailureContainmentTest.dropDiscardsCorpseWithoutSurfacingRefusal` (both the refusal
shape and the deletion) and `markerlessDatabaseIsRefusedOnOpenAndOpenNoAuthenticate`
(cause-chain asserted through `assertGenesisRefusal`, `GenesisFailureContainmentTest.java:230-241`).

### 3.3 Cleanup-on-exception concurrency (`cleanUpFailedCreate`, `:828-853`)

Runs entirely inside the creator's monitor (the catch at `:790` is inside `synchronized
(this)`), so `exists()`/`getStorage()` observers see the pre-create or the fully-cleaned
state, never an intermediate. The genesis session's mutex cannot be stranded by an injected
phase failure: the failing `executeInTx`/`computeInTx` closes its transaction, whose
`closeInternal` finally releases the permit (`FrontendTransactionImpl.java:1076`) before the
exception reaches `createStorage`'s catch; a seed-time failure self-releases at
`DatabaseSessionEmbedded.java:3649-3658`. `sharedContext.close()` re-acquires
`SharedContext.lock` (free by then — `create`'s finally released it) on the same thread.
One gap: the catch is `catch (Exception e)` (`:790`) — an `Error` (OOM/StackOverflow)
mid-genesis skips cleanup, leaving `exists()==true` in-process (CN56, suggestion; the marker
belt still condemns the residue at every open, and a restart clears the maps — same residue
shape as HEAD~).

---

## 4. O4 — saveInternal tracker suppression + unlinkIndexRecord under concurrency

### 4.1 What the tracker is (premise verification)

The bidirectional-link tracker is keyed off the session-confined, non-volatile flag
`ensureLinkConsistency` (`DatabaseSessionEmbedded.java:6109-6125`) — sessions are
thread-confined by contract (`assertIfNotActive`), so the flag provides **no cross-session
synchronization**; it is same-session link-edit maintenance/validation. Suppressing it removes
validation and auto-maintenance for records written by THIS session's operation, nothing else.
Concurrent sessions' flags are independent — no interference interleaving exists.

### 4.2 saveInternal (`SchemaShared.java:1533-1573`)

- Scope: the suppression covers exactly the `toStream(session)` call of the legacy top-level
  save (`:1561-1569`), inside the save's own `executeInTx`, under the schema write lock
  (`releaseSchemaWriteLock(iSave=true) → saveInternal`, `SchemaShared.java:795-813` — the
  modification counter guarantees the lock is still held). The pre-existing exclusion
  (SchemaShared.lock serializes legacy schema saves) is untouched.
- Symmetry with the commit window: the schema-carry commit serializes with the identical
  suppression (`AbstractStorage.java:2885-2947`), whose in-code justification is the honest
  one — mixed-tracking asymmetry (commit-created records are bag-less; legacy-created records
  DO carry bags), so a TRACKED legacy drop of a commit-created per-class record throws
  `LinksConsistencyException`. Suppressing both halves keeps every structural link edit
  untracked on both paths.
- Dangling-link check: the schema root is the referrer (its `classes` link set,
  `SchemaShared.java:1168/1217` via `getOrCreateLinkSet`); a dropped class's per-class record
  is deleted inside the same `toStream` tx and its back-bag (if any) dies with it. No
  referrer link survives unmaintained. Capture-and-restore (`:1561`, `:1566-1568`) preserves
  an outer disabled window (the import, `DatabaseImport.java:980/1076`).
- **Concurrency conclusion:** the legacy path's serialization (SchemaShared.lock + the
  optimistic record versioning at commit) is unchanged; only same-session validation was
  removed, and it was removed symmetrically with the path that creates the records. Null
  verdict — no consistency the legacy path provided UNDER CONCURRENCY is lost.

### 4.3 IndexAbstract.delete (`IndexAbstract.java:887-921`) / rebuild (`:634-660`) + `unlinkIndexRecord` (`IndexManagerAbstract.java:261-264`)

- Write-set equivalence: pre-change, the TRACKED delete of a bag-carrying (legacy-created)
  index record auto-cleaned the IM root's `CONFIG_INDEXES` entry — i.e. it ALSO wrote the IM
  root record in the same tx. The new explicit
  `unlinkIndexRecord(transaction, identity)` writes the same record through the same
  transaction enrollment (`transaction.loadEntity(indexManagerIdentity).getOrCreateLinkSet
  (CONFIG_INDEXES).remove(...)`). Racing writers of the IM root (two legacy index deletes on
  different sessions; a legacy delete vs a schema-carry commit's
  `enrollReconciledIndexRecords`, `IndexManagerEmbedded.java:1186-1187`) collide exactly as
  before: optimistic version conflict at commit → loud failure, no silent loss. The legacy
  path's mutex bypass is the honored-not-owned top-level-DDL gap (design §0) — pre-existing,
  not widened.
- No double-removal: the tracker is suppressed around the explicit unlink at BOTH call sites
  (`:647-659`, `:900-912`), so the auto-cleanup arm cannot also fire for bag-carrying records.
- Referrer completeness: index records are linked only from `CONFIG_INDEXES` (the IM root);
  per-class records reference indexes by name, not by link — the explicit unlink covers the
  full referrer set. The deleted record's own bag dies with the record (`doDelete:924-943`,
  `entity.delete()`).
- Rebuild's two-tx window (`:641-660` delete tx, then `:674-679` save+relink tx): between the
  two commits a concurrent reader/reloader observes `CONFIG_INDEXES` without this index. That
  window is PRE-EXISTING (HEAD~ had the same two `executeInTxInternal` calls); the change only
  moves the CONFIG_INDEXES edit from the tracker (or from nowhere — the dangling-link defect)
  into tx 1, making the intermediate state consistent instead of dangling. `identity` is read
  under the index's exclusive lock at both sites (`acquireExclusiveLock`, `:637/:888`) —
  unchanged discipline.
- **Verdict O4: DISCHARGED** — no synchronization removed; the replaced auto-maintenance is
  reproduced explicitly with an equivalent write/conflict surface, and the previously-thrown
  or previously-dangling arms are now correct.

---

## 5. O5 — EntityLinkSetImpl embedded pin

### 5.1 Containment reasoning (verified)

- The conversion trigger is `checkAndConvert`, invoked mid-serialization by the binary
  serializer (`RecordSerializerBinaryV1.writeLinkSet:759`) — i.e. inside the commit's apply
  phase, after `computeCommitWorkingSet` and `lockCollections` ran (the `CommitWorkingSet`
  javadoc, `AbstractStorage.java:2670-2686`, and the schema-carry order: toStream/enroll →
  working set → apply). The defect mechanics as recorded (converted bag's btree content
  missing the gathered working set) are consistent with the code.
- The pin closes BOTH creation arms for metadata records: (a) construction —
  `EntityLinkSetImpl(RecordElement)` (`EntityLinkSetImpl.java:63-75`) forces
  `EmbeddedLinkBag` when the owner is a collection-0 record, INCLUDING the `topThreshold < 0`
  ("always btree") configuration that `init()` (`:127-130`) would otherwise honor — this is
  what makes the threshold=-1 reproduction deterministic-fixed; (b) conversion —
  `checkAndConvert` (`:338-346`) adds `!isOwnedByMetadataRecord()` to the embedded→btree arm,
  covering sets DESERIALIZED as embedded from bytes (`readLinkSet` →
  `EntityLinkSetImpl(session, delegate)` ctor, `:110-116`, owner attached afterwards).
- Classification soundness: `isMetadataRecord` keys on
  `getCollectionId() == MetadataDefault.COLLECTION_INTERNAL_ID` (`:87-90`; the `internal`
  collection is created first inside the storage-create atomic op on both profiles —
  `MetadataDefault.java:42-48`, Step-1 layout pin), and provisional RIDs of new internal
  records already carry collection id 0 (negative position only), so mid-genesis/new-root
  records classify correctly. The schema root's `classes` set and the IM root's
  `CONFIG_INDEXES` set are created via `entity.getOrCreateLinkSet`
  (`SchemaShared.java:1168/1217`, `IndexManagerEmbedded.java:1187` →
  `EntityImpl.getOrCreateLinkSet:1475-1484` → owner-carrying ctor) — the pinned path.
- Thread-confinement: all pin reads (`delegate.getOwner()`) happen on the owning session's
  thread during its own serialization — no cross-thread access introduced.
- **Conversion is no longer possible mid-commit-window for metadata records: CONFIRMED** for
  the embedded→btree direction, which is the data-loss direction.

### 5.2 Residual-race characterization (verified, with two precision gaps → CN57)

The recorded residual (track-8.md Surprises; commit message) — (i) general user records'
link sets still convert mid-commit-window (the underlying commit-machinery defect stands for
user data), (ii) already-btree-backed metadata roots (pre-pin DBs, >threshold classes) keep
their form and their schema-carry commits remain exposed — is ACCURATE as far as it goes.
Two additions belong in the follow-up record:

1. The pin guards only the embedded→btree arm. The btree→embedded arm (`:344-346`,
   `bottomThreshold >= 0 && !isEmbedded() && size <= bottomThreshold`) can still fire for a
   pre-pin btree-backed metadata root mid-commit-window when
   `LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD` is set ≥ 0 (default −1 → dormant;
   `GlobalConfiguration.java:567-573`). The content moves INLINE (loss shape likely benign)
   but `oldDelegate.requestDelete(transaction)` (`convertToEmbedded:371`) is the same class of
   post-working-set structural mutation — untested, unrecorded.
2. Link sets built without an owner — `DatabaseSessionEmbedded.newLinkSet()` (`:5506-5522`)
   and the delta-deserializer (`EntitySerializerDelta.java:1313`) — bypass the constructor
   half of the pin; if such a set were assigned onto a metadata record while `topThreshold <
   0`, its delegate would be btree from birth (no conversion involved, so the
   `checkAndConvert` guard never applies). No internal metadata code path does this today
   (all verified creators use `getOrCreateLinkSet`), so this is a latent composition, not a
   live defect.

---

## 6. O6 — System-DB creation on the restructured `SharedContext.create`

- `SystemDatabase` is untouched by the commit. Its first-touch sequence
  (`openSystemDatabaseSession:57-64` → `init:90-111` → `context.create(...)` →
  `openNoAuthorization`) rides the restructured genesis: phase 1 identical; phase 2's
  role/user tx is skipped entirely for `OSystem` (`SecurityShared.java:638-660`,
  the system-DB guard is OUTSIDE the `computeInTx`, so no phase-2 tx at all);
  `initPredicateSecurityOptimizations` still runs (read-only); the marker IS written
  (`SharedContext.java:283` — correct per §A1 "sequence ran to completion, not users exist").
- **CN53 (known/deferred) unchanged:** the `exists()`/`init()` check-then-act in
  `openSystemDatabaseSession:59-61` and `init:92` is still unsynchronized; the race loser
  still hits `create(..., failIfExists=true)`'s "already exists" throw
  (`YouTrackDBInternalEmbedded.java:583-586` via `create:557-560`). Both `exists` and
  `create` are individually monitor-gated, so no NEW interleaving granularity appeared. The
  cleanup-on-exception actually NARROWS the race's failure residue (a loser observing a
  cleaned-up failed create retries into a clean create).
- **New exposure check (flag-only mandate):** one new COMPOSITION, signal-level not
  hazard-level — a process crash mid-`OSystem`-genesis now produces a marker-less corpse that
  `SystemDatabase.init` cannot self-heal: `exists()==true` skips re-create, and the
  subsequent `openNoAuthorization` throws the refusal → server startup fails loudly,
  prescribing a discard the system-DB path has no automated flow for (manual directory
  removal). At HEAD~ the same corpse silently opened (strictly worse). Recorded as CN59
  (observation/suggestion — candidate operator-doc line for Step 6). No new concurrent
  first-touch composition found: null verdict on the charter question proper.
- Pin G.5 #6 (`systemDatabaseGenesisCreatesSchemaWithoutDefaultUsers`,
  `TwoPhaseGenesisTest.java:225-253`) honors the OBS-2 constraint (strictly sequential
  touch) and does not pin listener counts. ✓

---

## 7. Carried reviewer obligations (from track-8.md Step 3 spec)

- **TQ12 re-grep:** `STORAGE_BLOB_COLLECTIONS_COUNT` has exactly ONE production read —
  `AbstractStorage.java:1522`; the `:1534` hit is the guard message's `.getKey()`;
  `SharedContext.java:244` is a comment. ✓ (matches the episode record).
- **CQ14:** the register loop's `List.copyOf` defensive snapshot survives the tx-wrap
  (`SharedContext.java:257`) with the updated comment (now a pure tx-local write). ✓
- **CQ15:** `schema.create` (`SharedContext.java:214`) and `indexManager.create` (`:215`)
  remain strictly BEFORE the phase-1 `executeInTx` (`:225`); the Step-2 entry assert belt in
  `SchemaShared.create` is intact. ✓

---

## 8. Hypothesis log (alternative-hypothesis check)

| # | Hypothesis | Method | Outcome |
|---|---|---|---|
| H1 | Phase 2 engages the mutex via the index-manager seam (UNIQUE-index key inserts) | traced `addIndexEntry` → tx index-change map, never `ensureTxSchemaState`; empirical pin | REJECTED (null) |
| H2 | Phase 1 engages the mutex more than once (per-creator engagement) | single funnel `ensureTxSchemaState:3616-3663` with seeded-state early return; single top-level `executeInTx`; nested creators join it; empirical pin | REJECTED (null) |
| H3 | The mutex is still held during phase 2 (released only at session close) | release at outermost tx frame close, `FrontendTransactionImpl.java:1065-1076`, before `insertDefaultSecurity` runs | REJECTED (null) |
| H4 | Cleanup restructuring moved genesis work outside the factory monitor | re-read as-built `createStorage:745-817`; everything incl. marker + first listeners inside `synchronized (this)` | REJECTED (null) |
| H5 | A session-minting path bypasses the marker check | exhaustive enumeration §2.2 | REJECTED (null); adopt-arm of `initCustomStorage` covered transitively |
| H6 | Concurrent open reads the marker mid-write | monitor + `stateLock` double belt, §2.3 | REJECTED (null) |
| H7 | drop()'s cause-chain walk misses a wrapped/cloned/suppressed refusal | `wrapException` semantics (`initCause`; `HighLevelException` short-circuit N/A); walk starts at the failure itself; copy-ctor preserves type | REJECTED (null) |
| H8 | Corpse drop/open/create interleavings produce NEW unsafe outcomes | table §3.2 | REJECTED for NEW exposure; two pre-existing races documented (drop-by-name delete; drop-vs-create) |
| H9 | Refused open leaves map state enabling silent corpse USE | traced §2.4 — every use-open re-refuses | REJECTED for use; residual `create(failIfExists=false)` no-op + resource pin → CN55 |
| H10 | Tracker suppression removes cross-session consistency/synchronization | flag is session-confined (`:6109-6125`); write-set equivalence §4.3; SchemaShared.lock unchanged | REJECTED (null) |
| H11 | Explicit unlink + suppressed tracker double-remove or dangle for bag-carrying legacy records | suppression covers the unlink at both sites; referrer set = CONFIG_INDEXES only | REJECTED (null) |
| H12 | Pin misclassifies (non-zero internal collection; provisional RIDs; user records in collection 0) | `internal` = 0 by construction on both profiles; provisional RIDs carry real collection id; collection 0 hosts only bounded metadata records | REJECTED (null) |
| H13 | Pin has uncovered conversion/creation arms | found two: btree→embedded arm; ownerless ctors — dormant by default | CONFIRMED as precision gaps → CN57 |
| H14 | Operator freeze/backup interleaves with the genesis commit | freeze/backup require a minted session; monitor excludes; listener-driven self-freeze aborts + contained | REJECTED (null) |
| H15 | WI9 lock-order edge (SharedContext.lock → mutex) completes a cycle | searched all `SharedContext.lock` takers and all mutex-holding paths; genesis-only by monitor; pre-existing legacy-DB `load`-path edge logged, unreachable for this branch's DBs | REJECTED (null); caveat recorded §1.3 |
| H16 | `cleanUpFailedCreate` leaves partial map state visible to a racer | runs wholly inside the monitor; `exists()` synchronized | REJECTED (null) |
| H17 | An `Error` mid-genesis defeats the containment | `catch (Exception)` only — residue stays in-process, belt condemns at open | CONFIRMED, bounded → CN56 |
| H18 | Restore-path (createOps) reintroduces a marker-less usable DB | genesis writes the marker pre-restore; restored config carries the source's marker (restore ITs green); pre-marker-source backups refused = accepted dev-only exposure | REJECTED (null) |
| H19 | System-DB path gained new concurrent first-touch composition | §6; CN53 shape identical; cleanup narrows the failure residue | REJECTED (null); new SIGNAL composition → CN59 |
| H20 | `skipRoleHasPredicateSecurityForClassUpdate` (plain instance field, now toggled in two methods) races across sessions | genesis + import call sites are single-threaded (monitor / import session); same field discipline as HEAD~; no new concurrent caller | REJECTED (null) — pre-existing pattern, unchanged reachability |

---

## 9. Findings

### CN55 — should-not-block: refused marker-less open leaves the storage OPEN and registered; `create(failIfExists=false)` silently no-ops over a crash corpse
- **Severity:** suggestion
- **Location:** `YouTrackDBInternalEmbedded.java:430-452` (`getAndOpenStorage` — the
  `storages.remove` at `:436` covers only the `storage.open` failure arm, not the `:446`
  refusal), `:806-809` (the failIfExists=false no-op arm).
- **Detail:** §2.4. Fail-closed for USE is preserved (every open re-refuses); the design's
  re-create guarantee is exception-path-scoped so this is arguably in-contract — but the
  refusal-path asymmetry (open storage pinned in the maps, holding the disk-storage
  exclusivity until drop/close) and the crash-corpse `create(failIfExists=false)` silent no-op
  sit uncomfortably next to the track Goal's blanket phrasing. Either close/remove the storage
  on refusal or record the accepted asymmetry.
- **Counterexample gist:** W6 crash → restart → `open()` refused (storage stays open+mapped)
  → `create(failIfExists=false)` logs "already exists, nothing to do" → caller believes the
  DB usable → next open refused. No unsafe open; surprising create semantics + resource pin.

### CN56 — Error-path escape from cleanup-on-exception
- **Severity:** suggestion
- **Location:** `YouTrackDBInternalEmbedded.java:790` (`catch (Exception e)`).
- **Detail:** an `Error` (OOM/SOE) thrown mid-genesis bypasses `cleanUpFailedCreate`: the
  half-created storage stays in `storages`/`sharedContexts` with `exists()==true` for the
  process lifetime. The marker belt condemns it at every open and a restart clears the maps,
  so containment degrades to HEAD~'s residue shape rather than failing open. If intentional
  (Errors are process-fatal by policy), record it; otherwise `catch (Throwable)` with rethrow
  discipline.
- **Counterexample gist:** OOM inside phase-1 commit → no cleanup → same-process
  `create(failIfExists=false)` no-ops on the corpse; opens are refused (belt holds).

### CN57 — residual-race record precision: unguarded btree→embedded arm + ownerless-constructor bypass
- **Severity:** suggestion
- **Location:** `EntityLinkSetImpl.java:344-346` (`convertToEmbedded` arm has no
  `isOwnedByMetadataRecord` guard), `:371` (`requestDelete` mid-window),
  `DatabaseSessionEmbedded.java:5506-5522` / `EntitySerializerDelta.java:1313` (ownerless
  constructions).
- **Detail:** §5.2. Both are dormant under default config and current internal callers, but
  the follow-up record for the commit-window/btree-bag root fix should name them so the
  commit-machinery owners see the full exposure surface, not just the embedded→btree arm.
- **Counterexample gist:** pre-pin btree-backed schema root + operator sets
  `LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD=64` → next schema-carry commit converts the
  root's set btree→embedded mid-apply, issuing a structural btree delete after the working
  set was gathered.

### CN58 — `initCustomStorage` create arm lacks the §A1 exception containment
- **Severity:** suggestion
- **Location:** `YouTrackDBInternalEmbedded.java:1113-1130`.
- **Detail:** the server-side custom-storage path mints a genesis via `internalCreate` with no
  cleanup-on-exception (and `storages.put` after the create, so a failure leaves an unmapped
  on-disk residue). The open-time belt condemns the residue loudly, so this is contained, but
  it is the one create entry point outside the hardened `createStorage` funnel. Out of the
  frozen in-scope list — flag for a follow-up or an explicit accepted-gap record.
- **Counterexample gist:** custom-storage create fails mid-genesis → on-disk residue,
  `exists()` true, no cleanup; later opens refused (belt), never silently adopted — but no
  retry-friendly self-heal either.

### CN59 — system-DB corpse now bricks startup loudly with no self-heal path (new signal composition)
- **Severity:** suggestion (observation; operator-doc candidate for Step 6)
- **Location:** `SystemDatabase.java:57-64/90-111` composed with
  `YouTrackDBInternalEmbedded.java:446`.
- **Detail:** §6. A crash mid-`OSystem` genesis leaves a marker-less corpse;
  `SystemDatabase.init`'s `exists()` guard skips re-create and the subsequent open throws
  `GenesisIncompleteException` → server start fails loudly prescribing a discard the
  system-DB path cannot perform itself. Strictly better than HEAD~'s silent half-genesis
  system DB; CN53 itself is unchanged. Suggest an operator note (and/or a future
  auto-discard-and-recreate for the data-free system DB, which the CS45 argument already
  blesses as cheap and correct).
- **Counterexample gist:** kill -9 during first server start (mid-OSystem-genesis) → every
  subsequent start throws "creation did not run to completion… Discard and re-create" until
  the operator deletes the OSystem directory.

### Null verdicts (justified in-body)
- **N-O1:** exactly one mutex engagement, phase-1-scoped (§1.1–1.2; empirically pinned).
- **N-O2:** no session can observe the between-phases state or a mid-genesis marker; the
  monitor span survives the cleanup restructuring; the marker check covers all seven mint
  paths (§2.1–2.3).
- **N-O3:** no NEW drop/open/create interleaving over a corpse; the two adjacent races found
  are pre-existing and unchanged (§3.2).
- **N-O4:** the tracker suppression and explicit unlink remove no cross-session
  synchronization or consistency; write/conflict surface is equivalent (§4).
- **N-O5(core):** metadata-record embedded→btree conversion mid-commit-window is structurally
  impossible post-pin (§5.1).
- **N-O6:** no new concurrent first-touch composition on the system-DB path (§6).
- **N-Track7:** the phase-1 schema-carry commit on a virgin storage exercises the steady-state
  Track 7 path with no divergent interaction beyond the designed WI9 nesting (§1.3).

---

## Compact findings block

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| CN55 | suggestion | YouTrackDBInternalEmbedded.java:430-452, :806-809 | Marker refusal leaves the corpse storage OPEN + registered in `storages` (asymmetric with the `storage.open` failure arm); crash-corpse `create(failIfExists=false)` silently no-ops; resources pinned until drop/close | W6 crash → open() refused (storage stays mapped/open) → create(failIfExists=false) "already exists, nothing to do" → later open refused; no unsafe open, surprising create + resource pin |
| CN56 | suggestion | YouTrackDBInternalEmbedded.java:790 | `catch (Exception)` — an Error mid-genesis skips `cleanUpFailedCreate`; in-process residue with `exists()==true` (belt still condemns at open) | OOM in phase-1 commit → no cleanup → same-process createIfNotExists no-ops on the corpse |
| CN57 | suggestion | EntityLinkSetImpl.java:344-346, :371; DatabaseSessionEmbedded.java:5506-5522 | Residual-record precision: pin guards only the embedded→btree arm (btree→embedded still fires mid-commit-window for pre-pin btree roots under non-default bottom threshold); ownerless ctors bypass the constructor half | pre-pin btree root + `BTREE_TO_EMBEDDED_THRESHOLD>=0` → convertToEmbedded + requestDelete mid-apply, after working-set gathering |
| CN58 | suggestion | YouTrackDBInternalEmbedded.java:1113-1130 | `initCustomStorage` create arm has no cleanup-on-exception; failure leaves on-disk residue contained only by the open-time belt | custom-storage genesis failure → residue, exists()==true, opens refused, no self-heal |
| CN59 | suggestion | SystemDatabase.java:57-111 + YouTrackDBInternalEmbedded.java:446 | New signal composition: a crashed OSystem genesis now refuses server startup loudly with no automated discard path (CN53 itself unchanged; previously the corpse opened silently) | kill -9 during first boot → every restart throws GenesisIncompleteException for OSystem until manual directory removal |

**Blockers: 0. Should-fix: 0. Suggestions: 5.** I-U4 (O1), genesis visibility (O2), the CN54
drop exemption (O3), the tracker-suppression/unlink symmetry (O4), the metadata-pin
containment (O5) and the system-DB composition (O6) are all verified as implemented.
