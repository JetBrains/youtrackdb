# Crash-safety / durability review — Track 8 Step 3, iteration 1

- **Commit under review:** `4d23111516` ("Restructure genesis into two phases with failure
  containment")
- **HEAD at review time:** `680147b578` (branch `transactional-schema`)
- **Perspective:** crash-safety / durability. Finding IDs continue from **CS52**.
- **Binding spec:** `plan/track-8.md` Step 3 (track-8.md:214-283, episode :650-731);
  `plan/track-8-design-drafts.md` §A1 in full (W-state table, mechanisms 1-5, CS35 fold, CS45
  W9a acceptance, CN54 drop exemption — design-drafts:733-810), G2.c (design-drafts:141-175);
  `crash-safety-step2-iter1.md` K-table (the K4/W6 row is the state the marker must refuse).
- **Mode:** read-only; no Maven; no file modification outside this report.

## 0. Diff footprint (scope check)

`git show 4d23111516 --stat`: 14 production files + 6 test files (+1035/−94). Production:
`SharedContext.java` (two-phase `create` + marker), `YouTrackDBInternalEmbedded.java`
(cleanup / marker check / drop exemption), new `GenesisIncompleteException.java`,
`Storage.java`/`AbstractStorage.java` (`getProperty` accessor only — `doCreate` untouched,
Step 1's seam respected), `SecurityShared.java`/`SecurityInternal.java`/`SymmetricKeySecurity.java`
(DDL/data split), `SchemaShared.java` (`saveInternal` tracker suppression),
`SchemaClassEmbedded.java` (tx-local `addProperty` de-guard), `IndexAbstract.java`/
`IndexManagerAbstract.java` (explicit `CONFIG_INDEXES` unlink), `MetadataDefault.java`
(`COLLECTION_INTERNAL_ID`), `EntityLinkSetImpl.java` (metadata embedded pin). Matches Step 3's
declared seam ownership (track-8.md:271-276: OWNS `SharedContext.create` tx-wrapping,
`SecurityShared`, `YouTrackDBInternalEmbedded`; does not touch `AbstractStorage.doCreate` or
`DatabaseExport/Import`) plus the four recorded enablers (episode :693-707).

## 1. Criteria and premises

**Criteria.**

- C1 (charter 1): the marker write must be atomic and recovery-safe — a crash at ANY point
  during it must resolve to exactly one of {marker durable → W9 opens; marker absent → W9a
  refused}; and the open-time refusal set must equal the §A1 W-table: refuse W3–W7 + W9a, open
  W9, with W0–W2 failing loudly upstream of the check.
- C2 (charter 2): every resource `createStorage` registers must be released by
  `cleanUpFailedCreate`; a crash or failure DURING cleanup must not defeat the refusal belt.
- C3 (charter 3): `drop()` must discard every corpse class the design promises (marker-refused
  W3–W7); the cause-chain tolerance must fire ONLY when the marker refusal genuinely occurred;
  corpse deletion must be durable (files gone).
- C4 (charter 4): no session-minting path may open a marker-less DB (the CS50/W6 armed window
  must be closed); the behavior on healthy pre-marker databases must match the design's recorded
  compatibility decision.
- C5 (charter 5): the `EntityLinkSetImpl` pin must suppress the mid-commit conversion for
  metadata-owned link sets on every live construction/mutation path, and the recorded residual
  exposure (already-btree roots) must be characterized accurately.
- C6 (charter 6): no durable intermediate state of the two-phase genesis (schema durable, no
  security; or security durable, no marker) may open.

**Premises (each verified against HEAD source).**

- P1. Genesis sequence: `SharedContext.create` (SharedContext.java:207-291) = `schema.create`
  (:214, pre-tx) → `indexManager.create` (:215, pre-tx) → ONE phase-1 `session.executeInTx`
  (:225-262: `security.createSecuritySchema`, the three creators, O/V/E via the session schema
  proxy, blob registration via the proxy) → ONE phase-2 `security.insertDefaultSecurity` (:265)
  → geospatial no-op try (:269-280, catches `IndexException` only) →
  `storage.setProperty(GENESIS_COMPLETED_PROPERTY, "true")` (:283) → `loaded = true` (:285).
- P2. Phase 2 is one tx: `SecurityShared.insertDefaultSecurity` (SecurityShared.java:634-666)
  wraps roles AND users in a single `computeInTx`; the system-DB skip and
  `CREATE_DEFAULT_USERS` handling are inside; `initPredicateSecurityOptimizations` (:664) is
  in-memory only. W8 (between roles and users txs) is eliminated by construction.
- P3. One frontend tx = one WAL atomic operation, dirty-flagged before apply (carried premise,
  crash-safety-step2-iter1.md P4/P12: `FrontendTransactionImpl` begin → single atomic op;
  `applyCommitOperations` calls `makeStorageDirty` before writing; recovery replays a durable
  WAL prefix and rolls back a torn trailing op when the dirty flag is set).
- P4. Marker write mechanics: `AbstractStorage.setProperty` (AbstractStorage.java:8369-8391)
  takes `stateLock.readLock`, `checkOpennessAndMigration`, `makeStorageDirty()`, then
  `executeInsideAtomicOperation(op -> storageConfiguration.setProperty(op, property, value))`.
  `CollectionBasedStorageConfiguration.setProperty` → `doSetProperty`
  (CollectionBasedStorageConfiguration.java:1131-1157) writes ONE string property
  (`updateStringProperty` into the config btree + collection record, all inside the given
  atomic op) and updates the in-memory `cache` map. `getProperty` (:1183-1194) reads the cache,
  preloaded at storage open by `preloadConfigurationProperties` (:1215-…) from committed
  content.
- P5. Marker read at open: `getAndOpenStorage` (YouTrackDBInternalEmbedded.java:430-453) =
  `getOrInitStorage` → `storage.open` (catch RuntimeException → `storages.remove` → rethrow)
  → `checkGenesisCompleted(storage)` (:446). `checkGenesisCompleted` (:464-472) refuses when
  `Boolean.parseBoolean(storage.getProperty(GENESIS_COMPLETED_PROPERTY))` is not `true` —
  null, "false", or any corrupted value all refuse (fail-closed string compare).
- P6. Every session-minting entry point routes through `getAndOpenStorage`: `open(name,u,p)`
  (:318), `openNoAuthenticate` (:322-341), `openNoAuthorization` (:358-376), `open(...,config)`
  (:378-401), `open(AuthenticationInfo,...)` (:403-428), `poolOpen` (:515-528),
  `poolOpenNoAuthenticate` (:530-545). The only other `DatabaseSessionEmbedded` constructors in
  production are `DatabaseSessionEmbedded.copy()` (DatabaseSessionEmbedded.java:741 — requires
  an existing session, i.e., an already-checked open) and `RecreateIndexesTask`
  (RecreateIndexesTask.java:32 — spawned from `rebuildIndexes`, which every open path calls
  AFTER `getAndOpenStorage` returned). The server's `YTDBInternalProxy`
  (YouTrackDBServer.java:619-…) delegates every open to the embedded instance.
- P7. Containment arm: `createStorage` (YouTrackDBInternalEmbedded.java:745-817) — inside
  `synchronized(this)` and `if (!exists(name))`: storage built → `storages.put` (:784) →
  `internalCreate` (:785 — `storage.create`, `getOrCreateSharedContext` puts the
  `sharedContexts` entry, genesis runs) → `createOps` → `catch (Exception e)` →
  `cleanUpFailedCreate(name, e)` (:796) → wrap-and-rethrow. `cleanUpFailedCreate` (:828-853):
  `sharedContexts.remove` + close (RuntimeException suppressed), `storages.remove`,
  `currentStorageIds.remove(storage.getId())`, `storage.delete()` (RuntimeException logged +
  suppressed).
- P8. `AbstractStorage.delete` (AbstractStorage.java:1669-1694) = `makeStorageDirty()` →
  `doShutdownOnDelete()` (early-returns on `STATUS.CLOSED`, :7288-7291; proper shutdown on
  OPEN/in-error incl. WAL delete) → `postDeleteSteps()` (DiskStorage.java:538-542 →
  `deleteFilesFromDisc` with retries; no-op base for memory profile beyond in-memory teardown).
  `DiskStorage.makeStorageDirty` (:611-613) = `startupMetadata.makeDirty` → `update(serialize())`
  → `channel.truncate(0)` (StorageStartupMetadata.java:112-126) — **NPEs when the metadata
  channel was never opened** (fresh, never-opened storage object). The channel is opened by
  `preCreateSteps` (DiskStorage.java:520-522), called at AbstractStorage.doCreate:1488, i.e.,
  before `makeStorageDirty`:1489 and the create atomic op:1491.
- P9. Drop path (YouTrackDBInternalEmbedded.java:889-940): try `openNoAuthenticate`; on
  `RuntimeException`, tolerate iff `isCausedByGenesisIncomplete` (:479-486 — walks
  `getCause()` chain for `instanceof GenesisIncompleteException`), else rethrow; `finally`:
  if `exists(name)` → `getOrInitStorage` → close sharedContext → `storage.delete()` with maps
  removed in an inner `finally`. `GenesisIncompleteException` is constructed ONLY by
  `checkGenesisCompleted` (grep: sole production `new` site) and never wraps a cause.
- P10. Schema-version gate (Track 2): `SchemaShared.fromStream:887-903` — `schemaVersion == null`
  → error-log breadcrumb (legacy corpses); `schemaVersion != 6` → `ConfigurationException`
  with the export/reimport REDIRECT. It runs during `SharedContext.load` → session init —
  strictly AFTER `checkGenesisCompleted` on every open path (P5, P6).
- P11. `EntityLinkSetImpl`: owner ctor (EntityLinkSetImpl.java:63-73) pins
  `EmbeddedLinkBag` when `isMetadataRecord(sourceRecord)` (:87-90 — owner is a `DBRecord`
  whose `collectionId == MetadataDefault.COLLECTION_INTERNAL_ID` (0, MetadataDefault.java:42-48;
  the `internal` collection is created first inside the storage-create atomic op on both
  profiles)); otherwise `init()` (:125-128 — `topThreshold >= 0 ? embedded : btree`).
  `checkAndConvert` (:338-346): up-conversion additionally guarded by
  `!isOwnedByMetadataRecord()` (:97-99); the down-conversion arm (`bottomThreshold >= 0 &&
  !isEmbedded() && size <= bottomThreshold`) is NOT guarded. `checkAndConvert` is invoked
  mid-serialization (RecordSerializerBinaryV1.writeLinkSet:756-759). Deserialization takes the
  delegate the bytes declare (RecordSerializerBinaryV1.readLinkSet:859-870).
  `newInternalInstance` (DatabaseSessionEmbedded.java:2109-2126) sets the provisional
  `ChangeableRecordId`'s collectionId to the internal collection id BEFORE tx enrollment, so
  in-tx (not-yet-committed) metadata records are recognized by the pin.
- P12. Thresholds: `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD` default 40 (−1 = always
  btree); `LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD` default −1 (down-conversion disabled)
  (GlobalConfiguration.java:559-573).

## 2. Charter (1) — the marker mechanism's own durability

### 2.1 Atomicity of the marker write

**Verdict: atomic and recovery-safe — null defect.** The whole marker mutation (config-btree
key + property-value record + cache) is ONE storage atomic operation (P4), preceded by
`makeStorageDirty`. Crash-point enumeration through `setProperty`:

| # | Crash point | Durable state at reopen | Classification |
|---|---|---|---|
| M1 | before the dirty flag write is durable | no page/WAL content of this op can be durable (dirty-flag-before-mutation discipline, P3/P4) → marker absent | W9a — refused |
| M2 | dirty flag durable, WAL end-record of the op NOT durable | recovery rolls the op back → marker absent | W9a — refused |
| M3 | WAL end-record durable, pages not flushed | recovery replays the op → marker present | W9 — opens (genesis WAS complete; correct) |
| M4 | everything durable | marker present | W9 — opens |

There is no third outcome: page-level tearing is owned by the WAL + double-write-log machinery
(carried premise P3/P12 of the Step-2 review — the same premise every commit rests on), and the
value comparison is an exact string match (`"true"`), so even a hypothetically corrupted value
fails CLOSED (P5). In-process, `doSetProperty` updates the read cache only after the btree
write succeeds inside the op; a failed marker write throws out of `SharedContext.create` →
`createStorage`'s catch → cleanup deletes the DB — no live session can observe a cache-true /
disk-absent split.

**Durability timing note (positive property, not a defect).** The marker's WAL end-record is not
necessarily fsynced when `create()` returns — that is exactly the design's "the marker write is
its own durability event" (§A1 mechanism 2, CS45), and the resulting false refusal is the
accepted W9a. Two bounds keep the acceptance honest: (a) the refusal is fail-closed (nothing
opens); (b) **no data-bearing database can ever be falsely refused** — the WAL is sequential,
so if any LATER user commit is durable, the marker's earlier op is durable too (prefix
durability). The W9a window therefore spans only [phase-2 durable … marker durable] on a
database that has never yet committed durable user data — precisely the "cheap and correct
discard" population CS45 describes.

### 2.2 Refusal-set enumeration vs the §A1 W-table

Durable-unit sequence (disk profile): U0 storage create (`doCreate`, one WAL op, Step 1's
envelope) → U1 schema-root tx → U2 `setSchemaRecordId` op → U3 IM-root tx → U4
`setIndexMgrRecordId` op → U5 phase-1 schema tx (ONE op, P1/P3) → U6 phase-2 data tx (ONE op,
P2) → U7 marker op (P4). Every state:

| State | Crash window | Open behavior at this commit | W-table match |
|---|---|---|---|
| W0 | before `preCreateSteps` | no recognizable residue (`DiskStorage.exists` keys on `database.ocf`/`config*.bd`/`dirty.fl*`, DiskStorage.java:798-816) → `storage.open` fails loudly ("does not exist" class) | ✓ benign |
| W1 | inside U0, dirty flag set | WAL rolls the create op back → configuration absent → `storage.open` fails loudly; `storages.remove` in the catch (P5); marker check never reached | ✓ "open fails loudly" — pre-marker |
| W2 | after `clearStorageDirty` (doCreate:1524), op's WAL commit not durable | clean-flag corpse: recovery skipped, config load fails loudly (CS37, Step 1's W2) | ✓ same as W1 — pre-marker |
| W3 | U0 durable, U1 not | storage opens; marker absent → **`GenesisIncompleteException`** (:446, before session init — the pre-commit `SchemaNotCreatedException` signature is subsumed, still loud) | ✓ "condemned by the marker" |
| W4 | U1 durable, U2 not | same refusal | ✓ |
| W5 | U2 durable, U4 not | same refusal (previously the IM-load `DatabaseException`) | ✓ |
| W6 | U4 durable, U5 not — **the crash-safety-step2 K4 row** | **refused loudly** — the CS50 armed window is CLOSED (see §5) | ✓ |
| W6′ | mid-U5 (torn phase-1 commit) | dirty flag set at apply (P3) → rolled back → identical to W6 → refused | ✓ single-tx all-or-nothing (Q-G1) |
| W7 | U5 durable, U6 not | refused | ✓ |
| W8 | between roles/users txs | **unreachable** — one merged phase-2 tx (P2) | ✓ eliminated |
| W9a | U6 durable, U7 not | refused (accepted false refusal, CS45; pinned by `markerlessDatabaseIsRefusedOnOpenAndOpenNoAuthenticate`, GenesisFailureContainmentTest.java:191-224) | ✓ |
| W9 | U7 durable | marker `"true"` → opens; listeners runtime-only | ✓ |

Extra members of the refusal set beyond the table: (i) databases created by pre-marker builds
of this branch — the design's recorded, accepted dev-only exposure (§A1 mechanism 2); (ii)
**old-format (schema v4/v5) production databases — refused with the WRONG message**, masking
Track 2's export/reimport redirect → finding **CS52** (the only refusal-set deviation with a
consequence); (iii) a marker property holding any non-`"true"` string — fail-closed, correct.

The refusal set otherwise matches the W-table exactly. Note a robustness property the belt
adds: even if U5's commit internally comprised multiple durability events (engine builds,
component files), any crash before U7 is condemned by the marker — the intra-genesis durable
microstructure is no longer load-bearing for open-safety.

## 3. Charter (2) — cleanup-on-exception completeness

**Verdict: complete for the designed path (`createStorage`); one legacy create path lacks the
arm (CS53); the refusal belt survives every cleanup failure — null defect on the belt.**

### 3.1 Resource ledger

Resources registered by `createStorage` in order, vs cleanup (P7):

| Resource | Registered at | Released by cleanup? |
|---|---|---|
| storage id in `currentStorageIds` | `generateStorageId()` (argument evaluation, :767-782) | ✓ `currentStorageIds.remove(storage.getId())` (:842) — except when the engine's `createStorage` ctor itself throws AFTER id generation: the id leaks in a process-local set of ints (no storage object to map it). Benign, memory-only, unreachable-in-practice window; recorded, no finding |
| `storages` map entry | :784, BEFORE `internalCreate` — so every genesis failure has the entry | ✓ `storages.remove` (:840) |
| `sharedContexts` entry | `getOrCreateSharedContext` inside `internalCreate` (:854-858) | ✓ `sharedContexts.remove` + `close()` (:829-836) |
| on-disk files | `storage.create` (doCreate) | ✓ `storage.delete()` (:846) — `exists()` false afterwards (postDeleteSteps removes `database.ocf`/config/dirty-flag files, the exact set `DiskStorage.exists` keys on) |
| genesis session `embedded` | :785 | not closed — pre-existing (CN observations row: the genesis session is never closed by `createStorage`); memory-only, storage closed under it by `delete()` |

**Early-failure tolerance of `delete()`:** the comment claims it "tolerates a storage whose
create failed early". Verified with one bounded exception: failures in `doCreate` at or after
`preCreateSteps` (:1488) have the startup-metadata channel open → `makeStorageDirty` works →
full delete. Failures BEFORE `preCreateSteps` (e.g., inside `initWalAndDiskCache`, :1476) leave
the channel null → `delete()` NPEs at `startupMetadata.update` (P8) → caught as
RuntimeException, logged + suppressed (:847-852) — and in exactly that window none of the
`exists()`-recognized files (`database.ocf`, `config*.bd`, `dirty.fl*`) exist yet, so
`exists()` is false anyway and the containment promise holds despite the failed delete. No
reachable counterexample to "retries re-create instead of adopting a corpse" on this path.

### 3.2 Partial-cleanup crash states

Process death DURING `cleanUpFailedCreate` (maps are process-local; only disk matters):

| Crash point | Disk state at restart | Outcome |
|---|---|---|
| before `storage.delete()` starts | full corpse (some Wi state) | `exists()` true → `create(failIfExists=false)` silently no-ops on the corpse (the pre-§A1 hazard returns for this crash-shape) — but every OPEN refuses via the marker (W3–W7) or fails at config load (W1/W2); `drop()` discards (W3–W7). Fail-closed preserved |
| mid-`deleteFilesFromDisc` | partial file set | either `exists()` false (marker files already gone → clean re-create) or `exists()` true with config unreadable/marker absent → loud failure / refusal |
| after delete | clean | re-create ✓ |

**Does cleanup failure leave the refusal belt working?** Yes, structurally: the belt is the
ON-DISK marker absence, and no cleanup path (success, failure, or crash) can mint a marker.
Cleanup failure degrades only the `exists()`/silent-no-op ergonomics, and the suppressed
exception plus the warn log (:847-851) record it. The primary genesis failure always
propagates (cleanup RuntimeExceptions suppressed; a cleanup `Error` would replace it — bounded,
consistent with the codebase's Error discipline; recorded, no finding).

### 3.3 The unguarded sibling path

`initCustomStorage` (:1113-1130) is a second production create path (server-configured
storages, YouTrackDBServer.java:504) running `internalCreate` with NO cleanup arm → finding
**CS53** (belt still refuses use; the §A1 primary-arm promise doesn't hold there).

Tests: `failedPhaseOneCleansUpAndRetrySucceeds` / `failedPhaseTwoCleansUpAndRetrySucceeds`
(both profiles, REAL injected commit failures through the listener seam) and
`createIfNotExistsRecreatesAfterFailedCreate` pin exactly the C2 criteria
(GenesisFailureContainmentTest.java:100-187). The injection seam (`onBeforeTxCommit` throw) is
a genuine mid-genesis production failure path, not a mock.

## 4. Charter (3) — CN54 drop-path exemption

### 4.1 Drop outcome per corpse class

| Corpse | `openNoAuthenticate` inside `drop()` | Tolerated? | Deletion outcome |
|---|---|---|---|
| W3–W7, W9a (marker-refused) | storage opens; `checkGenesisCompleted` throws; wrapped into `DatabaseException` (:336-340); cause chain carries `GenesisIncompleteException` | ✓ tolerated (:897-909); onDrop skipped, info-logged | storage is still in `storages` (the refusal does NOT remove it — see §4.3) and OPEN → `finally`: `storage.delete()` on an OPEN storage → proper shutdown + `postDeleteSteps` → **files durably gone**. Pinned by `dropDiscardsCorpseWithoutSurfacingRefusal` (:236-255) |
| W1/W2 (config-load failure) | `storage.open` throws a non-genesis failure; `getAndOpenStorage` removed it from the map | ✗ rethrown (by design: "any other open failure keeps today's behavior") | `finally` still runs: `exists()` true → `getOrInitStorage` mints a FRESH, never-opened storage → `storage.delete()` → `makeStorageDirty` NPE (P8) → **files remain**; the NPE (thrown from the finally) propagates. Pre-existing behavior, byte-identical at HEAD~1 → finding **CS54** |
| W0 | open fails; `finally`: `exists()` false → nothing to delete | n/a | vacuous ✓ |

The design's W-table assigns W1/W2 to "residue removed by cleanup (exception path) or manual
discard (crash)" — so drop() not discarding W1/W2 crash corpses is design-consistent; only the
§A1/commit-message phrasing "the prescribed discard always works" over-claims (the refusal
message's prescribed discard DOES always work for every state the marker itself refuses —
which is the CN54 scope).

### 4.2 Can the cause-chain catch mask a non-genesis failure?

**No — null defect.** Three sub-arguments: (a) `GenesisIncompleteException` has exactly one
production construction site, `checkGenesisCompleted` (P9), and neither constructor accepts a
cause — it can appear in a chain only as the refusal itself, never wrapping something else;
(b) the walk inspects `getCause()` only (not suppressed exceptions), so a genuine failure with
the refusal merely ATTACHED as suppressed would still rethrow — conservative direction;
(c) if the refusal IS in the chain, the marker check genuinely fired during this drop's open,
meaning the database is marker-less — and proceeding to deletion is the correct outcome for a
marker-less database regardless of what else wrapped the refusal. Conversely a non-genesis
failure (security, I/O, version gate) can never satisfy the predicate because nothing else
constructs or wraps-as-cause that type.

### 4.3 Durability of corpse deletion

For marker-refused corpses the deletion runs on the retained OPEN storage (the asymmetry in
`getAndOpenStorage` — remove-from-map only on `storage.open` failure, NOT on the refusal — is
load-bearing here: the drop's finally finds the open storage and `doShutdownOnDelete` +
`postDeleteSteps` remove the WAL and all `exists()`-recognized files with retries;
DiskStorage.java:538-608). After `drop()` returns, `exists()` is false (pinned, :253-254). A
crash DURING the drop's delete leaves a partial corpse that remains marker-less → still
refused/droppable at the next attempt — idempotent discard. The retention has a side cost
(open file locks until drop/close) → finding **CS55**.

## 5. Charter (4) — CS50/W6 closure and pre-marker databases

### 5.1 Is the armed window genuinely closed?

**Yes — verified by exhaustive path enumeration (P6).** The K4/W6 state (schema + IM root
shells and pointers durable, phase-1 not committed) now hits `checkGenesisCompleted` on EVERY
session-minting path: all seven public open entries funnel through `getAndOpenStorage`
(:318-545), and the only session constructors outside them require an already-opened database
(`copy()`) or run downstream of a checked open (`RecreateIndexesTask` via `rebuildIndexes`,
which every open path calls after the check). Non-session storage paths (`loadAllDatabases`
:966-978 opens storages at server boot; `getStorage`; `getStorages`) mint no sessions — a W6
corpse adopted into the map at boot still refuses every subsequent session-minting open.
`create(failIfExists=false)` on a W6 crash corpse silently no-ops (`exists()` true) — but the
subsequent open refuses loudly, which is the design's answer for crash-path residues (the
exception-path residues are cleaned so the no-op cannot happen there; pinned by
`createIfNotExistsRecreatesAfterFailedCreate`). The Surprises bullet "CLOSED by Step 3"
(track-8.md:96-100) is accurate.

Residual bypass check — the SecurityShared self-mutating load (`setupPredicateSecurity`
creating `OSecurityPolicy` on open, the Step-2 review's K4 concern): unreachable for corpses —
it runs in `SharedContext.load` during session init, and the refusal fires before any session
init. ✓

### 5.2 Pre-marker databases — the discriminator question

**There is NO discriminator — by recorded design decision, with one message-level defect.**
The check reads only the marker property (P5). Populations:

1. **Healthy schema-v6 DBs created by pre-Step-3 builds of THIS branch** — refused. This is
   §A1 mechanism 2's explicitly recorded, accepted dev-only exposure ("databases created by
   pre-Track-8 builds of this branch lack the marker — dev-only exposure, accepted and
   recorded"; echoed in the `checkGenesisCompleted` javadoc :455-463). Design-conformant;
   null finding. The discard prescription is correct for them only in the sense that they are
   dev artifacts; the decision was the user's to make and is on the record (CS45-adjacent).
2. **Old-format (schema v4/v5) databases** — e.g., any 2.0-M1/M2-era production database whose
   storage config loads fine: `storage.open` succeeds, the marker is absent (their genesis
   predates it), so `checkGenesisCompleted` refuses with "**Discard and re-create the
   database**" BEFORE `SchemaShared.fromStream:895-903` can issue Track 2's
   reject-and-redirect ("export … and reimport"). The D20 redirect — the flagship entry point
   of this very track's migration unit (pin M.5 #10: "Old-format DB open → rejected with the
   redirect message") — is masked by wrong, data-destroying operator advice. Fail-closed
   either way (nothing opens), but the message contract is violated → finding **CS52**
   (should-fix).
3. **Databases whose config cannot load at all** (pre-fork/foreign) — fail at `storage.open`,
   marker moot. ✓

## 6. Charter (5) — the EntityLinkSetImpl embedded pin

### 6.1 Does forced-embedded eliminate the mid-commit conversion on all live paths?

**Yes for every live construction/mutation path — null defect**, by two complementary
mechanisms (P11):

- **Construction:** every metadata-code path that creates a link set uses the owner-carrying
  ctor — `entity.getOrCreateLinkSet` (EntityImpl.java:1475-1483; used by
  `SchemaShared.toStream`:1168/1217 for `classes` and by
  `IndexManagerAbstract`:263/270 + `IndexManagerEmbedded`:1187 for `CONFIG_INDEXES`) → the
  ctor pins `EmbeddedLinkBag` for internal-collection owners EVEN when `topThreshold == -1`
  (the reproducer config, where plain `init()` would mint a btree bag at birth). The pin
  recognizes in-tx provisional records too: `newInternalInstance` sets the provisional rid's
  collectionId to 0 before enrollment (P11), so commit-created per-class/per-index records are
  covered while still unpersisted.
- **Mutation:** a set DESERIALIZED from embedded bytes takes the embedded delegate
  (RecordSerializerBinaryV1.readLinkSet:866-868) and the threshold conversion at the ONLY
  conversion point (`checkAndConvert`, invoked mid-serialization at writeLinkSet:759) is
  suppressed by `!isOwnedByMetadataRecord()` — so a healthy root that grows past the threshold
  stays embedded on every later save/commit, including reopen-then-mutate cycles.

**Escape-path audit (all `new EntityLinkSetImpl(` sites):** the session-only ctor (no owner →
threshold-based delegate) is reachable from `DatabaseSessionEmbedded.newLinkSet` (:5511/:5519 —
public API, not used by schema/IM code) and `EntitySerializerDelta.readLinkSet` (:1313 —
production-dead: the class's only production reference is the static `getFieldType` helper,
RecordSerializerBinaryV1:483). All other sites (EntityImpl:1478/1488/1495/3042,
JSONSerializerJackson:1252, RecordSerializerCSVAbstract:444/845, SQLCreateLinkStatement:201)
pass the owning record. Boundary note: `isMetadataRecord` requires the owner to be a `DBRecord`
— a link set nested inside an EMBEDDED element of a metadata record would not be pinned; no
such structure exists today (schema/IM roots carry link sets directly on the record;
globalProperties/property entities hold scalars). Recorded as a boundary, not a defect.

Crash-safety corollary (the pin's clause (2)): with the sets embedded, the whole genesis root
diff and every schema-carry commit's class-link content ride INSIDE the record bytes of the
commit's single atomic op — the schema parses at open with no dependency on link-bag btree
components. This strengthens the W-state story rather than merely containing the race.

### 6.2 Residual-exposure characterization (already-btree roots)

**Accurate, with one addendum.** A root whose `classes` set is durably btree-backed (created
pre-pin with > threshold classes): deserialization takes the btree delegate the bytes declare
(readLinkSet:870); at default config no branch of `checkAndConvert` fires (up-conversion:
`isEmbedded()` false; down-conversion: `bottomThreshold` default −1, P12) → the form is kept
and every schema-carry commit on it remains exposed to the underlying commit-window/btree-bag
interaction — exactly as the Surprises bullet records for the follow-up. **Addendum:** under a
NON-default `bottomThreshold >= 0`, the UNGUARDED down-conversion arm fires mid-serialization
on such a root (`convertToEmbedded`, :349-372: iterates the btree mid-commit and
`requestDelete(transaction)`s it). The content lands in the record bytes being serialized
(loss-safe direction), but the mid-commit btree read + delete belong to the same unowned
commit-window interaction — the follow-up record should name this arm too → finding **CS57**.

## 7. Charter (6) — phase-1/phase-2 crash states

**Verdict: no intermediate state opens — null defect.** Covered by §2.2's table: the only
durable intermediates the two-phase shape can produce are W6′→W6 (torn/uncommitted phase 1 —
rolled back to shells-only), W7 (schema durable, zero security records — the charter's "schema
durable but no security"), and W9a (all durable, marker not) — all refused by the marker before
any session exists; the pre-marker states W3–W5 likewise. Phase 2's single-tx shape eliminates
W8 (P2). The geospatial listener between U6 and U7 is a no-op (lucene excluded; catches
`IndexException` only — an unexpected throw there fails the create and rides the
exception-path cleanup, not a crash state). In-process failure (no crash) at ANY point of
P1's sequence lands in `createStorage`'s catch → cleanup → `exists()` false (§3). The
import-nested `security.create` call site keeps the tx-free guard first
(SecurityShared.java:594-609) — a repeat call cannot re-enter genesis phases on a populated DB.

## 8. Alternative-hypothesis check & hypothesis log

| # | Hypothesis | Outcome |
|---|---|---|
| H1 | The marker could ride the phase-2 tx's atomic op (not "its own durability event"), collapsing W9a | **Rejected.** `setProperty` runs its own `executeInsideAtomicOperation` (P4) after `insertDefaultSecurity` returned; SharedContext.java:283 comment and CS45 match the code |
| H2 | A torn marker write could leave an openable half-state (e.g., btree key without value record) | **Rejected.** Single atomic op + dirty-flag + recovery → binary present/absent (§2.1); value corruption fails closed on the exact-string compare |
| H3 | The in-memory config cache could serve `true` for a marker that never became durable, opening a session in-process whose DB is refused after restart | **Rejected as harmless and expected.** In-process cache-true implies the op committed in-memory; the only divergence is WAL-flush lag = the accepted W9a envelope; a failed op throws → create fails → cleanup deletes |
| H4 | Drop's tolerance could swallow a genuine non-genesis failure | **Rejected** (§4.2: single construction site, no cause-wrapping, getCause-only walk, and deletion is correct for any marker-less DB) |
| H5 | Some session-minting path bypasses `checkGenesisCompleted` | **Rejected.** All seven entries + the two non-entry constructors traced (P6); server proxy delegates |
| H6 | `cleanUpFailedCreate` could delete a PRE-EXISTING healthy database | **Rejected.** Reachable only inside `if (!exists(name))` under the instance monitor; the deleted files are the ones this create just made |
| H7 | Cleanup failure could mint a state that OPENS | **Rejected.** No cleanup path writes the marker; belt is on-disk absence (§3.2) |
| H8 | A W6/W7 corpse could be silently adopted by `create(failIfExists=false)` AND then used | **Rejected for use** — adoption (silent no-op) is possible for crash-path corpses, but every open refuses; exception-path corpses are cleaned so even the no-op is prevented (pinned) |
| H9 | The system database mid-genesis crash could brick server startup UN-loudly | **Rejected.** The system-DB corpse is refused like any other (marker written for system DB too — SharedContext.create is shared; `insertDefaultSecurity` skips only roles/users); startup fails loudly with the discard message naming the DB |
| H10 | Metadata link sets could still btree-convert via a construction path the pin misses | **Rejected for live paths** (§6.1 audit); session-ctor sites are public-API or production-dead; nested-embedded boundary recorded |
| H11 | The pin could mis-fire on user records (collection 0 collision) or unassigned rids | **Rejected.** Collection 0 is exclusively `internal` (created first inside the storage-create op on both profiles, MetadataDefault.java:42-48); a fresh `EntityImpl` without a target collection has collectionId ≠ 0 |
| H12 | `restore()` could interact with the marker to open a half-restored DB | **Not a regression — recorded as CS56.** The genesis marker is written before `restoreFromBackup` replaces content; the post-restore marker state comes from the backup's config; a mid-restore crash may leave a marker-bearing half-restored DB that opens — the pre-existing restore crash envelope, which the marker was never specified to own |
| H13 | The first `callOnCreateListeners` (:803) throwing could leave a marked DB while create() reports failure, with no cleanup | **Confirmed but pre-existing and safe.** Outside the try at HEAD~1 too; the DB is complete + marked (W9); a retry gets "already exists"; no unsafe open. Recorded, no finding |

## 9. Findings

### CS52 — should-fix — `YouTrackDBInternalEmbedded.checkGenesisCompleted` (:446/:464-472) vs `SchemaShared.fromStream:895-903`

**The marker check fires before Track 2's schema-version gate and masks the export/reimport
redirect for old-format databases with data-destroying advice.** `checkGenesisCompleted` runs
in `getAndOpenStorage`, before any schema load; the version gate (the D20
reject-and-redirect, FM-M11, "this track is its redirect target") runs in
`SchemaShared.fromStream` during session init. Any pre-marker database whose storage config
loads — including every v4/v5-schema production database (the populations `fromStream`'s
comment explicitly anticipates: "a pre-bump version-4 database and the legacy version-5
(2.0-M1/M2) form") — now gets `GenesisIncompleteException`: "…did not run to completion …
**Discard and re-create the database**" instead of "Please export your old database … and
reimport it". An operator following the message destroys migratable data. This contradicts
track-8.md's D20 rationale (:117-121), the frozen Validation bullet ("opening an old-format
database … is rejected … with a redirect"), and pin M.5 #10 (Step 6) — which can no longer be
satisfied for genuine legacy databases (a test that fabricates the old version on an in-branch
DB would carry the marker and pass, hiding the masking). §A1's compatibility clause ("every
database these binaries can open passed Track 2's schema-v6 gate") is falsified in the
letter: the gate never RUNS for them anymore. Fail-closed is preserved (nothing opens), which
is why this is not a blocker. **Remedy directions (owner's choice):** discriminate before
refusing (e.g., only refuse marker-less DBs whose schema pointer parses to the CURRENT format,
letting `fromStream`'s gate own the rest), or run the marker check after schema load, or fold
the export/reimport redirect into the refusal message for the old-format case.

*Counterexample gist:* place a healthy v5 (2.0-M1/M2) database directory under `basePath`;
`open()` → `GenesisIncompleteException` "Discard and re-create" — the D20 redirect never
fires; following the message loses the data that export/import would migrate.

### CS53 — suggestion — `YouTrackDBInternalEmbedded.initCustomStorage:1113-1130`

**The §A1 cleanup-on-exception arm does not cover the second production create path.**
`initCustomStorage` (live via server-configured storages, YouTrackDBServer.java:504) calls
`internalCreate` with no catch: a genesis failure there leaves the on-disk residue
(`DiskStorage.exists` true), a leaked `sharedContexts` entry (put inside
`getOrCreateSharedContext`), and — on the next server start — the corpse is silently ADOPTED
into `storages` (`exists` → true → `internalCreate` skipped). The marker belt still refuses
every session-minting open, so nothing unsafe opens; but the §A1 exception-path promise
("a failed create propagates AND cleans up… exists() false") does not hold on this path. The
design footprint scoped the remedy to `createStorage` only, so this is a parity gap, not a
spec violation. Suggested: extend `cleanUpFailedCreate` to this path (or record the exclusion).

*Counterexample gist:* server XML storage entry, kill/inject a failure during its first-boot
genesis → restart: storage adopted, `exists()` true, every open refused with the discard
message, but no create-retry is possible under the same name until manual/drop discard.

### CS54 — suggestion (pre-existing) — `AbstractStorage.delete:1669-1694` / `DiskStorage.makeStorageDirty:611-613` on never-opened storages

**`drop()` cannot discard W1/W2 corpses: the finally's delete NPEs before any file removal.**
For a corpse whose `storage.open` fails (config-load class), the drop finally mints a FRESH
never-opened `DiskStorage`; `delete()` → `makeStorageDirty` → `startupMetadata.update` →
`channel.truncate(0)` NPE (channel opened only by `preCreateSteps`/`open`) → `postDeleteSteps`
never runs, files remain, and the NPE (from the finally) supersedes the open failure.
Byte-identical at HEAD~1 — NOT introduced by this commit — and design-consistent (the W-table
prescribes "manual discard" for crash-path W1/W2). Flagged because the §A1/commit narrative
("the prescribed discard always works") reads broader than the CN54 scope (marker-refused
states only). Suggested hardening: make `doDelete` tolerate a never-opened metadata channel
(skip `makeStorageDirty` on CLOSED status) so `drop()` becomes the universal discard.

*Counterexample gist:* kill the process inside `doCreate`'s atomic op (W1); restart;
`drop(name)` → NPE out of the finally, `dirty.fl`/WAL files still on disk; only manual
directory deletion clears it.

### CS55 — suggestion — `getAndOpenStorage:430-453` refusal leaves the storage OPEN in the map

**A refused corpse's storage stays open (file locks, WAL direct-memory buffers) until
`drop()` or context close.** The remove-on-failure in `getAndOpenStorage` covers only
`storage.open` failures; the marker refusal (thrown after) retains the opened storage. This is
load-bearing for the drop path (§4.3) and consistent with how pre-commit W6 silent opens
behaved, but it means the refusal message's "discard" via MANUAL file deletion is blocked on
mandatory-locking platforms while the process lives, and each refused corpse pins its WAL
buffers for the process lifetime. Suggested: name `drop()` in the refusal message as the
prescribed discard mechanism (cheap, message-only), or close-and-remove the storage on refusal
if drop is taught to re-init it.

*Counterexample gist:* refused open of a W6 corpse on Windows → operator deletes the DB
directory per the message → deletion fails on locked WAL files until the embedding process
exits (drop() works; the message doesn't say so).

### CS56 — suggestion (docs/record) — `restore(...)` × marker interaction (:715-743)

**Restore is outside the marker's containment, and the record should say so.** The restore
paths run full genesis (marker written) and then `restoreFromBackup` replaces the content —
the surviving marker state is the BACKUP's. Consequences: (a) a mid-restore crash can leave a
marker-bearing, half-restored database that OPENS silently — the pre-existing restore crash
envelope, unchanged by this commit (the marker was never specified to gate restores);
(b) restoring a backup taken from a pre-marker branch DB yields a refused database after a
SUCCESSFUL restore (dev-only class, same acceptance as §A1's compatibility note; a backup of
an old-format DB additionally lands in CS52's message). Suggested: record the restore
exclusion in the track file (CS48/CS50-style bullet); optional hardening for a follow-up —
clear the marker before `restoreFromBackup` and re-set it after the reloads, which would give
restores the same fail-closed belt genesis got.

*Counterexample gist:* `restore(name, path, config)`, kill the process mid-backup-copy →
reopen: config already carries the backup's `genesisCompleted=true` → half-restored DB opens
with zero signal.

### CS57 — suggestion (pre-existing, config-dependent) — `EntityLinkSetImpl.checkAndConvert:338-346` down-conversion arm

**The metadata pin guards only the embedded→btree arm; the btree→embedded arm still runs
mid-serialization on pre-pin btree-backed metadata roots** when `bottomThreshold >= 0`
(non-default). `convertToEmbedded` (:349-372) iterates the btree and `requestDelete`s it
inside the same commit window the up-conversion was banned from. The direction is
loss-safe (content lands in the record bytes being committed), but the mid-commit btree
read/delete belongs to the same unowned commit-window/btree-bag interaction as the residual
exposure the Surprises bullet already assigns to the commit-machinery owners — the follow-up
record should name this arm so the root fix covers both. No default-config impact; no action
needed in this track beyond the record.

*Counterexample gist:* pre-pin DB with a btree-backed root (created with >40 classes), reopen
under `btreeToEmbeddedToThreshold=100`, run a schema tx → the root's set down-converts inside
the schema-carry commit's serialization — same window, opposite direction, unguarded.

## 10. Verdict summary per charter item

| Charter | Verdict |
|---|---|
| (1) marker durability + refusal set | **Null defect on the mechanism.** One atomic op, dirty-flagged, binary present/absent under recovery (M1–M4); WAL prefix ordering guarantees no data-bearing DB is ever falsely refused; refusal set = W3–W7 + W9a exactly, W9 opens, W0–W2 loud upstream — plus the recorded dev-only pre-marker refusal and the CS52 message masking for old-format DBs (the only deviation with a consequence) |
| (2) cleanup completeness | **Null defect on the designed path.** Full resource ledger released; early-create delete-NPE window coincides with `exists()==false`; partial-cleanup crash states all stay fail-closed (belt is on-disk marker absence, unmintable by cleanup); CS53 parity gap on `initCustomStorage` |
| (3) CN54 | **Null defect in the CN54 scope.** All marker-refused corpses (W3–W7, W9a) drop cleanly and durably; the tolerance predicate cannot mask non-genesis failures (single construction site, cause-only walk); W1/W2 remain manual-discard as the W-table prescribes (CS54 pre-existing NPE recorded) |
| (4) CS50/W6 closure + legacy | **CLOSED — verified.** All seven session-minting entries + both non-entry session constructors funnel through/behind the check; no bypass. No pre-marker discriminator exists BY DESIGN (accepted dev-only exposure); old-format DBs are refused with the wrong message → CS52 |
| (5) EntityLinkSetImpl pin | **Containment holds on every live path** (ctor pin incl. threshold=-1 and in-tx provisional rids; conversion-point guard for deserialized embedded sets; escape-path audit clean); residual-exposure characterization for already-btree roots is accurate, with the CS57 down-conversion addendum for the follow-up |
| (6) phase-1/phase-2 states | **Null defect.** Single-tx phases (W8 eliminated, torn commits roll back to the previous W state); every intermediate (W3–W7, W9a) refused pre-session; exception paths ride the cleanup |

**Overall: 0 blockers, 1 should-fix (CS52), 5 suggestions (CS53–CS57).**

## 11. Compact findings block

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| CS52 | should-fix | `YouTrackDBInternalEmbedded.java:446/464-472` vs `SchemaShared.java:895-903` | Marker check pre-empts Track 2's schema-version gate: old-format (v4/v5) DBs get "Discard and re-create" instead of the D20 export/reimport redirect — data-destroying operator advice; pin M.5 #10 unsatisfiable for real legacy DBs | healthy 2.0-M1/M2 database under basePath; `open()` → `GenesisIncompleteException` discard message; redirect never fires |
| CS53 | suggestion | `YouTrackDBInternalEmbedded.java:1113-1130` | `initCustomStorage` (server-configured storages) runs genesis with NO §A1 cleanup arm — failure leaves residue that the next boot silently adopts into the map (belt still refuses all opens) | inject genesis failure on a server custom storage's first boot → restart adopts corpse; `exists()` true; opens refused but no clean re-create |
| CS54 | suggestion (pre-existing) | `AbstractStorage.java:1688-1693` + `DiskStorage.java:611-613` | `drop()` cannot discard W1/W2 corpses: delete on a never-opened storage NPEs in `makeStorageDirty` before file removal; design says manual discard, but "the prescribed discard always works" over-claims | kill inside `doCreate` op; restart; `drop()` → NPE from the finally, files remain |
| CS55 | suggestion | `YouTrackDBInternalEmbedded.java:430-453` | Marker refusal leaves the corpse's storage OPEN in `storages` (locks/WAL buffers held; manual file deletion blocked on locking platforms; retention is load-bearing for drop) — message should name `drop()` | refused W6 corpse on Windows: directory deletion fails on locked files while process lives |
| CS56 | suggestion (docs) | `YouTrackDBInternalEmbedded.java:715-743` (restore paths) | Restore is outside the marker belt: genesis marker overwritten by the backup's config; mid-restore crash can leave a marker-bearing half-restored DB that opens silently (pre-existing envelope); record the exclusion | kill mid-`restoreFromBackup` → reopen: backup's `genesisCompleted=true` opens half-restored content with zero signal |
| CS57 | suggestion (pre-existing) | `EntityLinkSetImpl.java:338-372` | Metadata pin guards only embedded→btree; the btree→embedded arm (non-default `bottomThreshold>=0`) still runs mid-serialization on pre-pin btree roots — loss-safe direction but same unowned commit window; fold into the follow-up root-fix record | pre-pin btree root + `btreeToEmbeddedToThreshold=100` + schema tx → down-conversion inside the schema-carry commit |
