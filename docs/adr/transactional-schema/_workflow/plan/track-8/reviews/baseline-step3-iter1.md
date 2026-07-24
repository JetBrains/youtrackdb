# Baseline code review — Track 8 Step 3, iteration 1

- **Commit under review:** `4d23111516` ("Restructure genesis into two phases with failure
  containment"), branch `transactional-schema`, HEAD `680147b578` (differs from the review
  commit only in `track-8.md`; all production/test code cited below is identical at HEAD and
  was read from the worktree at HEAD).
- **Perspective:** code baseline — correctness of the restructure and the three unplanned
  enablers OUTSIDE genesis, the EntityLinkSetImpl embedded pin under huge metadata, inherited
  obligations (CS47, CQ15, TQ12, CQ14), and test quality of the 11 new tests + 4 fixture
  repairs.
- **Binding spec:** `plan/track-8.md` Step 3; `plan/track-8-design-drafts.md` G2.c, §A1
  (W-table, CS45, CN54), pins G.5 #2b/#3/#4/#5/#6/#9a–c/#10.
- **Finding ID ranges:** BG from BG14, CQ from CQ17, TQ from TQ16.
- **Method:** static reading only (no build, no test execution, no file modification outside
  this report). Every claim carries file:line at HEAD.

---

## 0. Review criteria and premises

Criteria:

1. **C1 — restructure correctness:** the two-phase shape implements D18/Q-G1/Q-G2/I-U4 and
   §A1 as ruled, with no behavioral drift on the preserved paths (import call site, system DB,
   `CREATE_DEFAULT_USERS`, guard no-op).
2. **C2 — enabler (a), addProperty de-guard:** every non-genesis caller of the de-guarded
   `SchemaClassEmbedded.addProperty` enumerated; no production or test consumer relied on the
   old throw-on-in-tx contract; the tx-local path is complete (global-property table, class
   write-set recording, commit promotion).
3. **C3 — enabler (b), tracker suppression + explicit unlink:** the suppression windows
   actually cover the checks they claim to suppress; the explicit `unlinkIndexRecord` replaces
   everything the tracker's auto-cleanup did on the legacy delete/rebuild paths; no index
   lifecycle break for user indexes.
4. **C4 — enabler (c), EntityLinkSetImpl pin:** the pin catches every construction/mutation
   path of a metadata-owned link set (fresh, deserialized, provisional-identity owners); the
   forced-embedded form is correct and bounded for large metadata.
5. **C5 — inherited obligations:** CS47 (blob registration via proxy under the tx), CQ15
   (shells pre-tx, entry assert intact), TQ12 (single `STORAGE_BLOB_COLLECTIONS_COUNT` read),
   CQ14 (`List.copyOf` snapshot survives).
6. **C6 — test quality:** the 11 new tests pin G.5 #2b/#3/#4/#5/#6/#9a–c/#10 and would fail
   on regression; the 4 fixture repairs preserve assertion strength.

Premises (verified by reading; cited inline below):

- P1. `SchemaProxedResource.resolveForWrite()` (`SchemaProxedResource.java:108-126`) is the
  single choke point: inside a tx it seeds the tx-local schema copy, rebinds by name, records
  the write target, and rebuilds the snapshot chain; outside a tx it returns the committed
  delegate (legacy path).
- P2. `SchemaShared.saveInternal` early-returns for tx-local copies
  (`SchemaShared.java:1535-1541`) and throws under an active tx on the committed instance
  (`SchemaShared.java:1543-1548`).
- P3. Link-consistency checks are evaluated in the record before-callbacks
  (`DatabaseSessionEmbedded.beforeCreateOperations:2563`, `beforeUpdateOperations:2610`,
  `beforeDeleteOperations:2658` → `ensureLinksConsistencyBeforeModification:5720` /
  `ensureLinksConsistencyBeforeDeletion:5614`), which run when
  `preProcessRecordsAndExecuteCallCallbacks` fires — at the outermost commit
  (`FrontendTransactionImpl.commitInternalImpl:301`) AND synchronously on every record delete
  (`FrontendTransactionImpl.deleteRecord:542-552` — "execute it here because after this
  operation record will be unloaded") and on every `deleteInternal`
  (`DatabaseSessionEmbedded.deleteInternal:4168`).
- P4. `executeInTx`/`computeInTx` join an active tx as nested (begin nests,
  `DatabaseSessionEmbedded.begin():2497-2508`; nested commit is a countdown, no real commit,
  `DatabaseSessionEmbedded.commitImpl:4803-4806`); a fresh top-level `executeInTx` commits
  AFTER the lambda returns (`executeInTxInternal:5135-5152`).
- P5. The threshold conversion fires mid-serialization:
  `RecordSerializerBinaryV1.writeLinkSet:759` calls `linkSet.checkAndConvert(...)` inside the
  record write — the exact mechanism of the pre-existing class-loss defect the pin fixes.
- P6. New internal records carry collection id 0 from birth
  (`DatabaseSessionEmbedded.newInternalInstance:2109-2124` sets the provisional
  `ChangeableRecordId`'s collection id to the `internal` collection's id), and the `internal`
  collection is id 0 by construction (`MetadataDefault.COLLECTION_INTERNAL_ID`,
  `MetadataDefault.java:42-48`; Step-1 pinned layout).
- P7. Deserialized link sets get their owner bound at property assignment
  (`EntityImpl.preprocessAssignedValue:1931-1933` → `storageBackedMultiValue.setOwner(this)`;
  `EntityLinkSetImpl.setOwner:155-157` delegates), so `isOwnedByMetadataRecord()`
  (`EntityLinkSetImpl.java:98-100`) is meaningful for sets read from bytes.

---

## 1. C1 — restructure correctness (verdict: PASS, no defects)

**Shape.** `SharedContext.create` (`SharedContext.java:207-290`): root shells pre-tx
(`:214-215`), phase 1 = one `session.executeInTx` (`:225-257`) spanning
`security.createSecuritySchema`, the three creators, O/V/E via the session proxy
(`:231-234`), and the blob registration via the proxy (`:254`); phase 2 =
`security.insertDefaultSecurity` (`:265`); geospatial listener outside (`:269-277`); marker
write last (`:283`).

**Path enumeration for the split `SecurityShared`:**

- `SecurityShared.create:594-608` — guard first and tx-free (`:597-599`), then
  `executeInTx(createSecuritySchema)` (`:606`), then `insertDefaultSecurity` (`:608`). Live
  callers: `DatabaseImport.removeDefaultCollections:404` (checked: cannot run under an active
  tx — the preceding legacy `schema.dropClass` calls at `DatabaseImport.java:392-402` throw
  under an active tx via `SchemaEmbedded.dropClass:426-428`, so the two `executeInTx`s there
  are real, sequential top-level transactions and I-U4 holds on the import path), and tests.
  `SymmetricKeySecurity` delegates both new methods (`SymmetricKeySecurity.java:143-151`).
- `insertDefaultSecurity:634-667`: ONE `computeInTx` creating roles then (conditionally)
  users — preserves the old semantics exactly: roles always for non-system DBs, users gated
  on `CREATE_DEFAULT_USERS`, system DB skips both, `initPredicateSecurityOptimizations` runs
  unconditionally at the end (as the old `create` tail did).
  `skipRoleHasPredicateSecurityForClassUpdate` windows: phase 2's whole commit runs inside
  the flag window (the `computeInTx` commit completes before the `finally` at `:661-662`),
  matching the old shape; phase 1's flag window (`:615-629`) closes before the phase-1
  commit, but `incrementVersion` (the only guarded trigger) fires only on OUser/ORole/
  OSecurityPolicy RECORD creates/updates (`DatabaseSessionEmbedded.afterCreateOperations:
  2588-2594`, `afterUpdateOperations:2641-2643`) and phase 1 writes no such records — no
  drift.
- Ordering change (users now created after Function/Sequence/Scheduler/O-V-E instead of
  before): checked both directions — no creator reads users; user creation reads only ORole
  (`createUser` role lookup), committed by phase 1. No dependency broken.

**§A1 containment.** `createStorage` catch → `cleanUpFailedCreate(name, e)`
(`YouTrackDBInternalEmbedded.java:791-800`, `:828-859`): shared context and storage removed
from both maps, `currentStorageIds` cleared, `storage.delete()` closes + removes on-disk
content, cleanup failures suppressed onto the primary. Open-time check: every session-minting
open routes through `getAndOpenStorage` (`:329, :364, :387, :414, :520, :535` — `open`×3,
`openNoAuthenticate`, `openNoAuthorization`, both pool opens; enumerated exhaustively, the
only session mint that bypasses it is `newCreateSessionInstance:857`, correctly, since the
marker is written at the end of create) → `checkGenesisCompleted:446, :464-472`
(`Boolean.parseBoolean(getProperty(...))` — absent property ⇒ null ⇒ refusal, so a real W6/W7
corpse with no property at all is refused, not only the test's `"false"` shape). CN54:
`drop:895-916` walks the cause chain (`isCausedByGenesisIncomplete:479-486` — necessary,
because `open`/`openNoAuthenticate` wrap everything in `DatabaseException`), skips `onDrop`,
and the `finally` deletion runs for both tolerated and rethrown failures — same deletion
behavior as before for non-genesis open failures. `Storage.getProperty` added symmetric with
`setProperty` (`Storage.java:262-268`, `AbstractStorage.java:8393-8410`); the marker write is
its own atomic operation (`AbstractStorage.setProperty:8369-8392` —
`executeInsideAtomicOperation`), consistent with the accepted W9a window.

**Null verdict justification:** no counterexample found on any preserved path. The one
design-visible gap (crash-corpse `createIfNotExists` silent no-op + refused-open resource
retention) is filed as BG14 (suggestion) — it does not violate the ruled §A1 letter, which
prescribes cleanup for the exception path and manual discard for the crash path.

---

## 2. C2 — enabler (a): `addProperty` de-guard (verdict: PASS with suggestions)

**The change.** `SchemaClassEmbedded.addProperty:48` — `!owner.txLocal &&` prepended to the
active-tx throw, the exact `dropClass`/`createIndex` de-guard shape
(`SchemaEmbedded.java:426, :474`). Outside a tx: unchanged. Inside a tx on the committed
instance (reachable only by holding a raw impl, not via proxies): still throws. Inside a tx
via the proxy: `resolveForWrite` (P1) rebinds to the tx-local class, `owner.txLocal` is true,
the property lands in the private copy; `releaseSchemaWriteLock` → `saveInternal` early
return (P2); `findOrCreateGlobalProperty` appends to the tx-local table
(`SchemaShared.java:1512-1531`), picked up by the commit's root diff
(`rootPayloadDiffersFrom:1305-1326` compares table size and slot signatures); the class
proxy's `recordWriteTarget` puts the class into the changed-class set so the per-class record
is rewritten at commit (`SchemaProxedResource.java:127+`).

**Exhaustive non-genesis caller enumeration** (`createProperty` → `addProperty`; grep of
`createProperty(` across `core/src/main/java`, generated parser excluded):

| Caller | Context | Effect of the de-guard |
|---|---|---|
| `SchemaClassProxy.createProperty` overloads (`SchemaClassProxy.java:88-106, :418-446`) | public API, user code | in-tx call now succeeds tx-locally instead of throwing — THE contract change; consistent with the already-shipped in-tx createClass/createIndex/dropClass contract |
| `SQLCreatePropertyStatement.java:98-104` | SQL `CREATE PROPERTY` | routes through `getSchema().getClass(...)` = `SchemaClassProxy` (`SchemaProxy.getClassInternal:448-453` wraps in a proxy) → same contract change; aligned with SQL CREATE CLASS/INDEX in-tx behavior |
| `YTDBSchemaClassImpl.java:394-424` (gremlin) | Gremlin schema API | same via proxy |
| `DatabaseSessionEmbedded.createEdgeClass:6301-6308` | user API | in-tx call previously created the class tx-locally (already de-guarded) then THREW on `createProperty` — a half-mutated tx-local state + exception; now succeeds wholly. Strict improvement |
| `DatabaseImport.java:787, :1176-1177` | import (legacy, tx-free — see C1) | unchanged path |
| `Role.generateSchema:225-253` | open-path legacy repair, tx-free | unchanged |
| `FunctionLibraryImpl.init:164-185`, `DBSequence:388-409`, `SchedulerImpl:169-193`, `SecurityShared:890-1000` | genesis (in-tx, via proxies) + lazy standalone sites (tx-free per Q-G1) | genesis is the intended first consumer; the lazy sites additionally stop half-failing when a user calls `createFunction`/`createSequence` inside a tx (previously: class created tx-locally, property threw) |
| `SchemaPropertyEmbedded.java:38` (`fireDatabaseMigration` on type change) | property setter path | guard-independent, unchanged |

**Reliance-on-throw hunt:** grep for `"Cannot create property"` and
`createProperty must throw` across `core/src`, `tests/src`, `server/src` → the only consumer
was `SchemaClassOperationsTest.createPropertyInsideTransactionIsRejected`, updated in this
commit to the new contract. No production code catches `SchemaException` around
`createProperty` as a control-flow guard. **Null verdict: no reliance found.**

**Completeness of the tx-local path:** `addPropertyInternal:351-405` on the tx-local copy —
duplicate check against the tx-local `properties`, tx-local global-property append, linked
type/class set via `setLinkedTypeInternal`/`setLinkedClassInternal` (tx-local prop impl,
impl-typed args re-resolved by the proxy per `SchemaProxedResource`'s contract). One
behavioral surface worth recording: `fireDatabaseMigration` (`:400-402`, non-unsafe creates
only; `SchemaClassImpl.fireDatabaseMigration:1238-1268`) is now reachable INSIDE a user tx —
its `computeInTx` + `executeInTxBatches` degrade to nested joins (P4), so type-migration
updates of existing records enroll into the USER's transaction with no intermediate commits
(unbounded tx growth on a large mismatched class; rollback discards them consistently). Not a
defect — it is the only semantics compatible with a tx — but untested and undocumented →
**BG16 (suggestion)**.

**Residual asymmetry:** `dropProperty` keeps its unguarded in-tx throw
(`SchemaClassEmbedded.java:468-470, :488-490`) even when routed through the proxy to the
tx-local copy — in-tx createProperty succeeds, in-tx dropProperty of the same property
throws. Pre-existing, unchanged by this commit, but the de-guard makes the surface visibly
inconsistent → **CQ18 (suggestion)**.

---

## 3. C3 — enabler (b): tracker suppression + explicit unlink (verdict: PASS with a should-fix on the comments)

**Where the checks actually run (the load-bearing subtlety).** The three new suppression
windows (`SchemaShared.saveInternal:1550-1571`, `IndexAbstract.delete:892-912`,
`IndexAbstract.rebuild:642-660`) span only the mutation lambdas — the enclosing
`executeInTx(Internal)` commits AFTER each lambda's `finally` restored the flag (P4). The
suppression is nevertheless effective for exactly the arms that used to fail, because record
DELETES trigger the before-callbacks synchronously inside the window:
`FrontendTransactionImpl.deleteRecord:542-552` runs
`preProcessRecordsAndExecuteCallCallbacks()` immediately, and
`DatabaseSessionEmbedded.deleteInternal:4168` runs it just before enrolling the delete — so
both the deletion-arm check on the deleted structural record
(`ensureLinksConsistencyBeforeDeletion:5614` — the arm that throws
`LinksConsistencyException` when a back-bag entry cannot be removed from the referrer,
`:5661-5668`, or when the bag-less record's referrer container misses the link) and the
pending manager/root UPDATE check are processed with the flag off. The UPDATE arms that
still run at commit with the flag restored are safe: `updateOppositeLinks:5781-5798` skips
targets deleted in the same tx (`isDeletedInTx` → `continue` for removals, `:5788-5794`).

Consequence #1 (correct): dropping a commit-created bag-less schema/index record on the
legacy path no longer throws and no longer dangles — `IndexManagerAbstract.load:231-246`
would have failed loudly on a dangling `CONFIG_INDEXES` link (`transaction.loadEntity` of a
deleted rid), which the explicit `unlinkIndexRecord` (`IndexManagerAbstract.java:262-265`)
prevents; the commit-time drop half performs the same explicit unlink
(`IndexManagerEmbedded.enrollReconciledIndexRecords:1184-1230`), so both halves of the
mechanism are now symmetric.

Consequence #2 (also correct, verified against the alternative hypothesis): a LEGACY-created
index record — which DOES carry a tracker back-bag, because `addIndexInternalNoLock`'s
`CONFIG_INDEXES.add` (`IndexManagerAbstract.java:266-271`) is processed at its commit with
tracking on and `updateOppositeLinks:5807-5830` creates the back-bag on the target — is
deleted through the new path without throwing: the deletion-arm check that would have
double-removed the already-unlinked rid (and thrown at `:5661-5668`) runs inside the
suppressed window via `deleteRecord:552`. I initially hypothesized the opposite (window
closes before commit ⇒ check escapes ⇒ throw); the hypothesis is REFUTED by
`deleteRecord:552` — recorded in the hypothesis log (§7).

**Comment defect (should-fix).** The `saveInternal` comment (`SchemaShared.java:1551-1560`,
duplicated in the commit message) claims "Suppressing both halves keeps the pairing
symmetric: schema records carry no back-reference maintenance on either path." That is not
what the code achieves: the ADD-side maintenance still runs tracking-ON at the commit-time
callback pass (P3/P4) — a class created by a pure-create legacy save still gets a
`#classes` back-bag on its per-class record, and rebuild's re-registration
(`IndexAbstract.java:676-680`) still puts a back-bag on the fresh index record. The system
is consistent anyway (every delete path now tolerates both bag-carrying and bag-less
records), but the comment misstates the invariant, and the whole suppression scheme silently
depends on `deleteRecord`'s synchronous callback execution — if that immediate
`preProcessRecordsAndExecuteCallCallbacks` (`FrontendTransactionImpl:552`) were ever
deferred, all three windows would stop covering the deletion arm with no test naming the
dependency. → **CQ17 (should-fix, comments/documented-invariant only — no behavioral bug)**.
The sibling inaccuracy: `SecurityShared.create:603-605`'s "executeInTx joins an
already-active transaction (the import-nested call site)" describes a dead path — the import
call site is provably tx-free (C1) — fold into CQ17.

**Exhaustive caller enumeration for the changed index paths:**

- `Index.delete(FrontendTransaction)`: `IndexManagerEmbedded.dropIndex:1071-1085` (legacy
  top-level drop, own tx); `RecreateIndexesTask:133, :184` (WAL-recovery rebuild). Deliberately
  NOT called inside the commit window (documented at
  `IndexManagerEmbedded.java:1206-1210`), so the unlink never races the reconciliation's own
  unlink. Checked: `identity == null` guarded at both sites (`IndexAbstract.java:903, :650`);
  `getOrCreateLinkSet(...).remove` of an absent rid is a no-op.
- `rebuild(...)`: `SQLRebuildIndexStatement:41, :71`; `DatabaseImport:933`;
  `RecreateIndexesTask:151`; `IndexAbstract.rebuild(session):606-608`. Lifecycle verified:
  tx1 unlink+delete old record; engine re-add; tx2 `save` + `addIndexInternal(…, true)`
  re-links the fresh record (`IndexAbstract.java:661-680`) — registry map put is idempotent.
  Crash between tx1 and tx2 loses the index record but no longer leaves a dangling link
  (before this commit, the bag-less case left the OLD rid dangling and the reopen failed on
  it — strictly better).
- Import outer window: both new windows capture-and-restore
  (`isLinkConsistencyEnabled` read first — `IndexAbstract.java:647, :900`;
  `SchemaShared.java:1561`), preserving `DatabaseImport.java:980/:1076`'s outer disable.
  Verified against `DatabaseSessionEmbedded.java:6109-6125`'s documented nesting contract.

**Null verdict:** no consumer of schema/index record back-bags exists outside the tracker
itself (grep for `OPPOSITE_LINK_CONTAINER_PREFIX` consumers); no index-lifecycle break found
on any enumerated path.

---

## 4. C4 — EntityLinkSetImpl embedded pin (verdict: PASS with recorded residuals)

**Mechanism.** Owner ctor forces `EmbeddedLinkBag` for metadata-collection owners
(`EntityLinkSetImpl.java:63-74`, `isMetadataRecord:87-90` — `DBRecord` owner with collection
id `COLLECTION_INTERNAL_ID` = 0); `checkAndConvert:338-342` suppresses the embedded→btree
arm via `isOwnedByMetadataRecord():94-100`. The defect it fixes is real and mid-serialization
(P5: `RecordSerializerBinaryV1.writeLinkSet:759` converts inside the record write, inside the
commit window). With the DEFAULT threshold of 40
(`GlobalConfiguration.LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD`,
`GlobalConfiguration.java:559-565`) the pre-existing bug was reachable by any schema-carry
commit on a database whose root `classes` set crossed 40 entries — this pin is not merely a
genesis enabler, it closes a default-configuration data-loss path.

**Construction-path enumeration** (every way a metadata record's link set comes to exist):

1. Fresh via `getOrCreateLinkSet`/`newLinkSet` (`EntityImpl.java:1475-1496, :3042`) — owner
   ctor → pin applies. Covers `toStream`'s root `classes` set and the IM `CONFIG_INDEXES`
   set.
2. Fresh on a PROVISIONAL root (genesis, before commit) — provisional internal rids carry
   collection id 0 from birth (P6) → pin applies. Checked because a `-1` placeholder
   collection id would have silently disabled the pin exactly where it matters most.
3. Deserialized embedded (`readLinkSet`, `RecordSerializerBinaryV1.java:859-875`) — takes the
   declared embedded delegate; owner bound at property assignment (P7) → later mutations are
   conversion-suppressed. This is the critical non-genesis case (existing DB crossing the
   40-class threshold by later DDL) — verified covered.
4. Deserialized btree (pre-pin database with >threshold classes) — keeps its form, as the
   Surprises entry records; its commit-window exposure is the documented residual owned by
   the commit-machinery follow-up.
5. Ownerless ctor + later attachment (`EntityLinkSetImpl(session)`, e.g.
   `EntitySerializerDelta.java:1313`, `session.newLinkSet:5507-5521`) — `init()` picks the
   delegate by threshold with NO owner knowledge (`:125-128`); with `threshold=-1` this is
   btree-from-birth even if later attached to a metadata record. No live path attaches a
   delta/session-created set to an internal-collection record today (metadata records
   serialize via binary V1/toStream), so this is a latent bypass, not a defect → recorded in
   **BG17 (suggestion)**.

**Huge-metadata correctness (charter §2).** Forced-embedded means the root's `classes` set
(and the IM root's `indexes` set) serialize inline: varint size + link entries
(`writeLinkSet:756-781`, `HelperClasses.writeLinkCollection:383`), no cardinality cap; a
10k-class schema ⇒ ~100 KB root record — multi-page records are supported by the paginated
storage, and the write-amplification (whole root rewritten per DDL) is the same order as the
pre-existing sub-threshold behavior. Justification (3) in the code comment ("cardinality
bounded by the class/index count") is accurate; the correct comparison point is that the
btree alternative was silently LOSING data. Verdict: acceptable, no size-limit defect found.
Residuals recorded in **BG17**: (a) `LinkBag`'s own conversion
(`writeLinkBag`/`LinkBag.checkAndConvert`, `RecordSerializerBinaryV1.java:783-801`) has no
metadata suppression — today the only metadata-owned `LinkBag`s are tracker back-bags of
cardinality 1 (see C3), so not live; (b) the btree→embedded arm (`checkAndConvert:343-345`)
is not suppressed — with a non-default `bottomThreshold >= 0` a pre-pin btree root could
convert INSIDE the commit window (content flows into the in-working-set record so no loss is
expected, but the mid-window btree-component deletion is untested); default `-1`
(`GlobalConfiguration.java:567-573`) keeps this dormant.

---

## 5. C5 — inherited obligations (verdict: all HONORED)

- **CS47:** the blob registration inside phase 1 calls
  `sessionSchema.addBlobCollection(...)` (`SharedContext.java:254`) where `sessionSchema` is
  the session's `SchemaProxy` (`:231`); `SchemaProxy.addBlobCollection` routes
  `resolveForWrite()` (`SchemaProxy.java:460-463`) → tx-local copy →
  `SchemaShared.addBlobCollection:1653-1662` mutates the tx-local `blobCollections`, whose
  promotion is guaranteed by the root diff (`rootPayloadDiffersFrom:1309-1311` compares
  `blobCollections` explicitly). The direct-call self-commit hazard the seam annotation
  warned about is structurally avoided.
- **CQ15:** `schema.create(session)` and `indexManager.create(session)` sit strictly before
  the phase-1 `executeInTx` (`SharedContext.java:214-215` vs `:225`), with the rationale
  comment (`:210-213`); the entry assert is intact and unweakened
  (`SchemaShared.create:1394-1397`).
- **TQ12:** `STORAGE_BLOB_COLLECTIONS_COUNT` has exactly ONE production read after the
  tx-wrap — `AbstractStorage.java:1522`; the `:1534` occurrence is the guard message's
  `.getKey()`.
- **CQ14:** the `List.copyOf` snapshot survives the tx-wrap (`SharedContext.java:252`), with
  the comment correctly updated to "harmless today … future-proofing" (`:246-251`).

---

## 6. C6 — test quality (verdict: PASS with suggestions)

**The 11 new tests → pins → regression sensitivity:**

| Test | Pin | Would it fail on regression? |
|---|---|---|
| `mutexEngagedInPhaseOneOnlyAndPhaseTwoCommitsOnce` (TwoPhaseGenesisTest) | #3 + #10 | Yes — asserts EXACTLY ONE mutex-engaged commit (splitting phase 1 per-creator, or phase 2 engaging the mutex, fails), and EXACTLY ONE record-carrying commit after it (re-splitting roles/users into two txs fails). Observation seam is production (`SessionListener.onBeforeTxCommit` via config; `MetadataWriteMutex.isEngagedBy:229`; `FrontendTransactionImpl.getRecordOperationsCount:2025`). Robust: the trailing predicate-security tx is read-only (0 ops) so it cannot false-positive. |
| `oUserNameEngineIsBuiltBeforeFirstUserInsert` | #4 / I-U4 | Yes — asserts the engine is BUILT (`getIndexId() >= 0`) at the phase-2 commit's start AND the post-create indexed lookup finds exactly one admin. |
| `reopenShowsGenesisPopulatedSchema` | #2b (+TQ14 deferral) | Yes — full context close + DISK reopen; 9 genesis classes, the index, and the admin query. Doubles as the disk-side marker-read pin: a broken disk persistence of the marker would make this reopen throw `GenesisIncompleteException`. |
| `systemDatabaseGenesisCreatesSchemaWithoutDefaultUsers` | #6 | Yes — strictly sequential (CN53/OBS-2 honored in the comment and structure), zero OUser rows, marker present; no listener-count pin (CN observation honored). |
| `repeatSecurityCreateIsTxFreeNoOp` | #5 (guard half) | Yes for the guard half (null return + no open tx). The import-nested half is DELEGATED to the existing import suites — real coverage (every import through `importSchema:497`/`importCollections:848` exercises `removeDefaultCollections:404`), but no named test asserts "users recreated by the import-nested create" → TQ18 note. |
| `failedPhaseOneCleansUpAndRetrySucceeds` / `failedPhaseTwoCleansUpAndRetrySucceeds` (GenesisFailureContainmentTest) | #9a | Yes — REAL failures through the production listener seam, both profiles, cause-chain-verified injection, `exists()` false, storage purged, retry create succeeds (on disk, retry success transitively proves on-disk residue removal — `create` would refuse an existing directory). |
| `createIfNotExistsRecreatesAfterFailedCreate` | #9a (failIfExists=false half) | Mostly — asserts re-create returns true + marker present; but the failure stage swallows ANY `RuntimeException` without verifying the injected cause (→ TQ17). |
| `markerlessDatabaseIsRefusedOnOpenAndOpenNoAuthenticate` | #9b + CS45/W9a | Yes — refusal type + message pinned on both open entries; the constructed state (complete DB, marker flipped) IS the W9a shape, so CS45's accepted false refusal is pinned by construction. `parseBoolean` treats the test's `"false"` and a real corpse's absent property identically (`:464-472`). |
| `dropDiscardsCorpseWithoutSurfacingRefusal` | #9b/CN54 | Yes — drop must not throw; `exists()` false after. |
| `markerPresentAfterSuccessfulCreateOnBothProfiles` | #9c | Yes — both profiles, marker + normal reopen. |

**The 4 fixture repairs:**

- `SchemaClassOperationsTest.createPropertyInsideTransactionIsTxLocal` — contract update
  matching the deliberate de-guard; strength PRESERVED AND EXTENDED (tx-local visibility,
  rollback discard, legacy path). The commit-persistence half of the new contract (in-tx
  createProperty COMMITS and survives reopen on a user path) has no direct pin — genesis
  covers it only transitively → **TQ16 (suggestion)**.
- `BTreeGetVisibleTest` / `SharedLinkBagBTreeReadMethodsTest` — setup-only horizon
  advancement (100 empty atomic ops) with a precise explanatory comment; every assertion
  untouched. Not papering over a signal: the "signal" was an implicit dependence on the
  operation-id budget the legacy genesis burned, correctly diagnosed and made explicit; the
  visibility-filtering semantics under test are unchanged. Assertion-strength: preserved.
- `PostponedEngineStartTest` — mechanical stub completion for the new interface method
  (`getProperty` → null); compile-enforced, no assertion touched.

---

## 7. Hypothesis log

| # | Hypothesis | Method | Outcome |
|---|---|---|---|
| H1 | The three tracker-suppression windows are ineffective because link-consistency checks run at commit, after the `finally` restores the flag | traced `preProcessRecordsAndExecuteCallCallbacks` call sites | PARTIALLY REFUTED: deletion-arm checks run synchronously inside the window (`FrontendTransactionImpl.deleteRecord:552`, `deleteInternal:4168`); update-arm checks DO run at commit with tracking on, but tolerate deleted-in-tx targets (`updateOppositeLinks:5788-5794`). Net behavior correct; comment inaccurate → CQ17 |
| H2 | Deleting a LEGACY-created (back-bag-carrying) index record via the new unlink-first path throws `LinksConsistencyException` (double-remove) at commit | same trace as H1 | REFUTED — the deletion-arm check executes inside the suppressed window (H1); at commit the record op is not reprocessed (callback dirty-counter synced) |
| H3 | The pin misses deserialized root sets (owner never bound ⇒ existing DBs crossing the 40-class threshold still convert) | traced `readLinkSet` → `preprocessAssignedValue:1931-1933` → `EntityLinkSetImpl.setOwner:155` | REFUTED — owner bound at assignment; pin effective for deserialized embedded sets |
| H4 | The pin misses PROVISIONAL owners (genesis root pre-commit has no collection id) | read `newInternalInstance:2109-2124` | REFUTED — provisional internal rids carry collection id 0 from birth |
| H5 | The import call site now nests both phases into one outer tx (I-U4 broken on import) | read `DatabaseImport.removeDefaultCollections:386-405` + `SchemaEmbedded.dropClass:426-428` | REFUTED — the preceding legacy dropClass throws under an active tx, so the call site is provably tx-free; both `executeInTx`s are real sequential commits. (A hypothetical future caller invoking `security.create` under an active tx WOULD collapse the phases — latent, no live caller; noted in CQ17's comment fix) |
| H6 | `fireDatabaseMigration` inside a user tx performs real intermediate commits (breaking tx atomicity) | read `executeInTxBatchesInternal:5240-5263`, `commitImpl:4803-4806` | REFUTED — nested commits are countdowns; everything joins the outer tx (behavioral note → BG16) |
| H7 | Some open path mints a session without passing `checkGenesisCompleted` | enumerated `getAndOpenStorage`/`newSessionInstance` callers | REFUTED — all six open entries route through `:446`; only the create-path session (correctly) bypasses |
| H8 | A W6/W7 crash corpse can be silently adopted by `create(failIfExists=false)` | read `createStorage:751-817` | CONFIRMED for the crash path (exists() true ⇒ "already exists, nothing to do" log + return) — but consistent with the ruled §A1 (cleanup is the exception-path arm; the crash path prescribes manual discard, and every subsequent OPEN still refuses). Fail-closed is preserved; UX/hygiene residue → BG14 |

---

## 8. Findings

### BG14 — suggestion — `YouTrackDBInternalEmbedded.java:430-452, :764-772`
A genesis-refused open leaves the marker-less storage OPEN and registered in `storages`:
`checkGenesisCompleted(:446)` runs after the `storage.open` try/catch, so unlike a plain open
failure (`:437-441`, which removes the storage from the map) the refusal retains an open
storage holding disk file locks until `drop()`/context close. Relatedly, on the CRASH path
(W6/W7 — process died, no cleanup ran) `createIfNotExists` still silently no-ops on the
corpse (`createStorage:804-810`: `exists()` true → "already exists, nothing to do"), so the
"re-create instead of adopting a corpse" property holds only for the exception path.
Counterexample gist: kill -9 mid-phase-1 on a disk DB → reopen refused (correct) →
`createIfNotExists(name)` returns false and logs "nothing to do" → the operator must know to
call `drop()` first; meanwhile a prior refused `open()` attempt keeps the corpse's files
locked. Fail-closed is never violated (every open refuses), so: suggestion — either remove
the storage from the map on refusal (mirroring `:437-441`) and/or extend the
`createIfNotExists` no-op arm to detect a marker-less residue.

### CQ17 — should-fix — `SchemaShared.java:1551-1560`; `SecurityShared.java:603-605`; (same text in `IndexAbstract.java:893-899`)
The suppression comments misstate the mechanism in two ways. (1) "Suppressing both halves …
schema records carry no back-reference maintenance on either path" is false: the disable
windows close when each lambda returns, BEFORE `executeInTx`'s commit runs the batched
before-callbacks (`FrontendTransactionImpl.commitInternalImpl:301`), so ADD-side tracker
maintenance still executes tracking-ON and still creates back-reference bags on
legacy-created per-class/index records (`updateOppositeLinks:5807-5830`); the windows are
effective only because record DELETES process their checks synchronously
(`FrontendTransactionImpl.deleteRecord:552` — the actual load-bearing line, cited nowhere).
A maintainer trusting the comment could (a) assume legacy-created schema records are
bag-less (they are not), or (b) refactor `deleteRecord`'s immediate callback execution and
silently disarm all three windows. Fix: correct the comments to name the real suppression
boundary and the `deleteRecord:552` dependency (a targeted regression test for
"legacy drop of a commit-created class/index does not throw and does not dangle" already
exists only transitively via the import suites — naming one would also discharge (b)).
(2) `SecurityShared.create`'s "executeInTx joins an already-active transaction (the
import-nested call site)" describes a dead path — the import call site is provably tx-free
(the preceding legacy `dropClass` at `DatabaseImport.java:392-402` throws under an active tx,
`SchemaEmbedded.java:426-428`); if a tx-active caller ever appeared, both phases would
collapse into the outer tx and I-U4 would silently not hold there, so the comment should
state the tx-free precondition instead of implying nesting support. No behavioral bug —
comments/documented-invariant only.

### BG16 — suggestion — `SchemaClassEmbedded.java:400-402` + `SchemaClassImpl.fireDatabaseMigration:1238-1268`
The de-guard makes `fireDatabaseMigration` reachable inside a user transaction (any
non-`unsafe` `createProperty` on a class with type-mismatched existing records): the
migration's `executeInTxBatches` batch commits degrade to nested no-op countdowns
(`commitImpl:4803-4806`), so ALL migration updates accumulate in the user's transaction —
unbounded tx growth where the legacy path committed per batch, and the user's tx silently
carries mass rewrites it did not enroll. Semantically consistent (rollback discards
everything), but untested and undocumented. Counterexample gist: class with 1M records
holding string values under property `p`; in-tx `createProperty("p", INTEGER)` enrolls up to
1M updates into the open tx. Suggestion: document the semantics on `createProperty`, and/or
add a test pinning in-tx migration behavior.

### CQ18 — suggestion — `SchemaClassEmbedded.java:468-470, :488-490`
De-guard asymmetry: in-tx `createProperty` now succeeds tx-locally, but `dropProperty` keeps
its unconditional in-tx throw even on the proxy-routed tx-local path (no `!owner.txLocal`
exemption). Pre-existing and out of Step 3's minimal scope, but the surface is now visibly
inconsistent (a tx can create but not drop a property; dropClass CAN drop the whole class).
Record for the de-guard follow-up track.

### BG17 — suggestion — `EntityLinkSetImpl.java:57-61, :125-128, :338-345`; `RecordSerializerBinaryV1.java:783-801`
Three recorded residuals of the metadata-embedded pin (none currently live): (a) the
ownerless ctor (`EntityLinkSetImpl(session)` — e.g. `EntitySerializerDelta.java:1313`,
`DatabaseSessionEmbedded.newLinkSet:5507-5521`) picks the delegate by threshold with no owner
knowledge, so under `threshold=-1` a set later attached to a metadata record would be
btree-from-birth, bypassing the pin — no live attachment path today; (b) `LinkBag`'s
conversion (`writeLinkBag:783-801`) has no metadata suppression — safe today only because
metadata-owned LinkBags are tracker back-bags of cardinality 1; (c) the btree→embedded arm
(`checkAndConvert:343-345`) is unsuppressed — with a non-default `bottomThreshold >= 0` a
pre-pin btree root converts inside the commit window (content lands in the in-working-set
record, so no loss expected, but the mid-window component deletion is untested). Suggest
folding all three into the recorded commit-machinery follow-up so the root fix owns them.

### TQ16 — suggestion — `SchemaClassOperationsTest.java:717-740`
The updated `createPropertyInsideTransactionIsTxLocal` pins tx-local visibility + rollback
discard + legacy path, but no test directly pins the COMMIT half of the new contract on a
user path: in-tx `createProperty` → commit → property (and its global-property slot) visible
after reopen. Genesis pins it only transitively (phase 1 commits properties; a user-path
regression in, e.g., property-only write-target recording would surface as a genesis failure
only if it broke genesis too). One test: begin; `cls.createProperty("p", STRING)`; commit;
close/reopen; assert property + type survive.

### TQ17 — suggestion — `GenesisFailureContainmentTest.java:168-188`
`createIfNotExistsRecreatesAfterFailedCreate` swallows ANY `RuntimeException` from the
failing create (`catch (RuntimeException expected)`) without walking the cause chain for the
injected marker (its siblings do, `:137-146`): an unrelated environmental create failure
would pass stage 1, and the subsequent `createIfNotExists` would re-create trivially — green
without exercising the intended cleaned-residue path. Reuse the cause-chain walk.

### TQ18 — suggestion — `GenesisFailureContainmentTest.java:196-259`; `TwoPhaseGenesisTest.java:262-276`
Two fidelity notes, both within the pins' letter: (a) the refusal and drop-exemption tests
run on MEMORY only and against the same live context (pin #9(b) does not demand both
profiles — "both profiles" attaches to #9(c) — and the disk marker-READ path is covered
transitively by `reopenShowsGenesisPopulatedSchema`); a disk-profile, fresh-context refusal
variant would pin the reopened-config read of an absent (not `"false"`) property. (b) pin
#5's import-nested half ("recreates the security schema and users") is delegated to the
existing import suites without a named assertion; a one-line user-count assertion in an
existing import round-trip test would make the delegation explicit.

---

## 9. Verdict

No blockers. One should-fix (CQ17 — comment/documented-invariant accuracy on the
tracker-suppression mechanism; docs-only change). Seven suggestions (BG14, BG16, BG17, CQ18,
TQ16, TQ17, TQ18). The two-phase restructure, the §A1 containment, and all three enablers
are correct on every enumerated non-genesis path; CS47/CQ15/TQ12/CQ14 obligations are all
honored; the 11 new tests genuinely pin G.5 #2b/#3/#4/#5(guard)/#6/#9a-c/#10 through
production seams and would fail on the regressions they name; the 4 fixture repairs are
assertion-strength-preserving.
