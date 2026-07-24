# Gate verification — Track 8 Step 3 review-fix iteration 1

- **Diff under verification:** `680147b578..9976f90218` on `transactional-schema`
  (`6b61334581` code+tests: 11 files, +373/−78; `9976f90218` records: `track-8.md` +
  `track-8-design-drafts.md` only).
- **Inputs:** the three Step-3 review reports
  (`track-8/reviews/{baseline,crash-safety,concurrency}-step3-iter1.md`), the shipped code at
  HEAD `9976f90218`, the record commits.
- **Mode:** read-only static verification; no Maven, no file modification outside this report.
  All file:line citations are against the worktree at HEAD.

---

## 0. Verification criteria (set before verdicts)

Per finding, the fix passes iff: (1) the shipped change implements the approved remedy (not a
weaker cousin); (2) it does not break anything the original reviews verified as correct
(fix-introduced breakage → RG findings, numbered from RG3); (3) the accompanying record/test
claims are accurate against the code. For CS52 specifically, the hard obligations are:
(a) exhaustive re-trace of every session-mint path against the belt's NEW location, with an
active hunt for a bypass; (b) old-format DBs genuinely reach the version-gate redirect;
(c) crash corpses still refused; (d) the crash reviews' W-state mapping preserved at the new
firing point; (e) no double-fire/ordering hazard inside `SharedContext.load`.

---

## 1. CS52 — belt relocation into `SharedContext.load` — **VERIFIED** (with RG3, record-precision)

**The shipped shape.** `checkGenesisCompleted` is deleted from `getAndOpenStorage`
(replacement comment at `YouTrackDBInternalEmbedded.java:448-451`); the belt now lives in
`SharedContext.load` immediately after `schema.load(database)` and BEFORE
`schema.forceSnapshot`/`indexManager.load`/`security.load`
(`SharedContext.java:153-175`; refusal construction at `:167-173`, same message text as
before). Exactly two `GenesisIncompleteException` construction sites exist in production
(`SharedContext.java:167`, `YouTrackDBInternalEmbedded.java:854` — the BG14 probe), neither
accepts a cause, so `drop()`'s cause-chain walk (`isCausedByGenesisIncomplete:505-512`)
retains its single-source soundness argument from the crash review §4.2.

### (a) Funnel trace — can any path mint a usable session without the belt?

Complete enumeration of `new DatabaseSessionEmbedded(` in production (grep, 4 sites):

| Constructor site | Path to usability | Belt? |
|---|---|---|
| `YouTrackDBInternalEmbedded.newSessionInstance:345` | used by ALL six non-pooled entries — `openNoAuthenticate:322-341`, `openNoAuthorization:366-384`, `open(name,u,p[,cfg]):387-407`, `open(AuthenticationInfo,cfg):410-436` (and `open(name,u,p):318` delegating) — each calls `embedded.init(config, getOrCreateSharedContext(storage))` at `:347` | ✓ `init:450` → `loadMetadata:605-613` → `sharedContext.load(this):611` → belt |
| `newPooledSessionInstance:575-581` (via `DatabaseSessionEmbeddedPooled`) | both `poolOpen:530-548` and `poolOpenNoAuthenticate:551-570` | ✓ same `init` → `loadMetadata` → `load` |
| `newCreateSessionInstance:357-362` (create path, `internalCreate:912`) | `internalCreate` → `SharedContext.create` — full genesis; correctly belt-free | n/a (marker written at `SharedContext.java:283` BEFORE `loaded = true` at `:285`) |
| `RecreateIndexesTask.java:32` + `newDb.init(null, ctx):35` | WAL-recovery rebuild, spawned by `rebuildIndexes` after a successful open | ✓ `init` → `loadMetadata` → `ctx.load` (no-op on the already-loaded context; would REFUSE if handed a corpse context) |

`DatabaseSessionEmbedded.copy():741` requires an existing usable session (context already
loaded). `loadAllDatabases:1021-1034` opens STORAGES only, mints no session — a boot-adopted
marker-less storage is refused at the first mint. `SharedContext.reInit:313-321` calls
`load` → belt. The `initialized` early-return in `init:459` only fires on a session object
that already completed a full `init` (set at `:483`), which implies a loaded context.

**Bypass hunt (alternative hypotheses, all refuted):**

- *H-B1: a context could be `loaded == true` with the marker absent.* Refuted: `loaded`
  flips true only at `SharedContext.create:285` (strictly after the marker write `:283`) or
  at `load:183` (strictly after the belt passed). An in-process marker flip AFTER load (the
  old test fixture's shape) is the only counterexample and is not a crash-reachable state.
- *H-B2: a failed load leaves a context that later skips the belt.* Refuted: the refusal
  propagates before `loaded = true`; additionally `unregisterGenesisIncompleteCorpse` removes
  the context from `sharedContexts`, so the next open builds a fresh context and re-runs
  `load` (the comment's "a refused load re-runs and re-refuses" claim is accurate — and holds
  even without the removal, since `loaded` stays false).
- *H-B3: the restore path yields a usable marker-less DB.* In-process the restored context is
  the genesis one (loaded=true); cross-process the belt reads the BACKUP's marker — exactly
  the CS56 recorded boundary, unchanged by this fix.

**Conclusion:** the new placement is structurally STRONGER than the old one — the old belt
guarded an enumerated set of open entries; the new belt guards the single choke point every
usable session must pass (`loadMetadata` on the first `init` of a context). No bypass found.

### (b) Old-format redirect

`SchemaShared.fromStream:886-905`: `schemaVersion != CURRENT_VERSION_NUMBER` throws
`ConfigurationException` "…export your old database … and reimport…" — reached via
`schema.load:1366-1380` BEFORE the belt at `SharedContext.java:166`. Pinned by
`oldFormatDatabaseGetsMigrationRedirectNotGenesisRefusal`
(`GenesisFailureContainmentTest.java:336-379`): schema root rewritten to legacy version 5,
marker removed, full context close, fresh DISK context → the assert walks the WHOLE chain
asserting no `GenesisIncompleteException` appears AND a `ConfigurationException` containing
"export" does — the exact CS52 counterexample, inverted into a pin. The `schemaVersion ==
null` breadcrumb arm (`:887-894`) returns without throwing → such a legacy corpse falls
through to the belt and is refused by the marker — still fail-closed. ✓

### (c) Crash corpses still refused / (d) W-state mapping at the new firing point

| State | Post-fix open behavior | vs design W-table (design-drafts:759-768) |
|---|---|---|
| W5 (schema pointer durable, IM not) | `schema.load` parses the bootstrap-valid v6 root → belt refuses (`GenesisIncompleteException`) — the belt sits BEFORE `indexManager.load` | table says "IM load fails (loud)"; marker refusal is strictly the stronger signature ✓ |
| W6/W6′/W7 | schema parses → belt refuses | ✓ "condemned by the marker" |
| W9a | complete DB, marker absent → belt refuses (accepted false refusal) | ✓ pinned by `markerlessDatabaseIsRefusedOnOpenAndOpenNoAuthenticate` (now DISK + fresh context) |
| W3/W4 (no/absent schema pointer) | `SchemaShared.load:1370-1375` throws `SchemaNotCreatedException` BEFORE the belt | table's stated signature for W3/W4 is exactly "`SchemaNotCreatedException` (loud)" (design-drafts:761-762) — restored to the letter; but the table's third column ("condemned by the marker") is no longer literally exercised, and `drop()`'s CN54 tolerance no longer covers W3/W4 → **RG3** below |
| W1/W2 | `storage.open` fails; `storages.remove` in `getAndOpenStorage:441-445` | unchanged ✓ |

Fail-closed holds for every state; nothing opens. `drop()` of W3/W4: the non-genesis failure
rethrows (`:906-908`) but the `finally` (`:975-994`) still deletes via the retained OPEN
storage — same contract as W1/W2 ("today's behavior"), deletion preserved.

### (e) Double-fire / ordering inside `load`

The belt is a single idempotent read inside `load`'s one `executeInTx`; on refusal the
read-only load tx unwinds, `loaded` stays false, no state is half-mutated (`fromStream`
mutated only the in-memory `SchemaShared` of a context that is then discarded). The placement
before `security.load` preserves the concurrency review's §1.3 caveat boundary: the
legacy-DB self-mutating `setupPredicateSecurity` DDL can never run on a corpse. No genesis
work moved; `SharedContext.create` is untouched by this commit. ✓

**Verdict: VERIFIED.** The GENESIS_COMPLETED_PROPERTY javadoc (`SharedContext.java:46-56`)
and the `GenesisIncompleteException` javadoc accurately describe the new firing point.

---

## 2. CQ17 — suppression comments — **VERIFIED**

Accuracy checked against code, not just presence:

- `SchemaShared.saveInternal` (`SchemaShared.java:1550-1567`): claims (i) the window works
  only because `FrontendTransactionImpl.deleteRecord` runs before-deletion checks
  synchronously — TRUE (`FrontendTransactionImpl.java:552`, "execute it here because after
  this operation record will be unloaded"; commit-time batch at `:301`); (ii) the ADD side
  still runs tracking-ON at commit and creates bags on legacy-created records — TRUE (the
  window's `finally` restores the flag before `executeInTx` commits; baseline review H1
  mechanics unchanged); (iii) "toStream removes the root link EXPLICITLY" — TRUE
  (`SchemaShared.java:1216-1225`: drop loop `classLinks.remove(rid)` then
  `droppedRecord.delete()`); (iv) the refactor hazard (deferring deleteRecord's callbacks
  disarms the window) is now named. The false "no back-reference maintenance on either path"
  claim is gone.
- `IndexAbstract.delete` (`:897-907`) carries the full PRECISION text; `rebuild` (`:643-651`)
  cross-references it ("The same CQ17 precision as delete() applies") — adequate.
- `SecurityShared.create` (`:602-609`): the dead "joins an already-active transaction" claim
  is replaced by the tx-free PRECONDITION with the provably-tx-free import-site argument —
  matches the baseline review's C1 verification (legacy `dropClass` throws under an active
  tx).

---

## 3. BG14 + CN55 + CS55 — corpse unregistration + loud `failIfExists=false` — **VERIFIED** (one boundary observation → RG4)

**Unregistration completeness.** `unregisterGenesisIncompleteCorpse`
(`YouTrackDBInternalEmbedded.java:456-489`): guarded by `isCausedByGenesisIncomplete` (other
open failures keep today's behavior — stated in the javadoc and true); removes `storages`
entry, `currentStorageIds` id, `sharedContexts` entry (closing the context), then
`storage.shutdown()` — file locks and WAL buffers released. Cleanup failures are attached as
suppressed, never masking the refusal. `synchronized` on the factory monitor — reentrant from
every call site (all inside `synchronized(this)` blocks). Wired at: `newSessionInstance:347-353`
(covers all six non-pooled entries), both pool arms (`:537-544`, `:558-565`), and the create
probe (`:861`). No leak found: the only resources acquired between `getAndOpenStorage` and the
refusal are the shared context (removed+closed) and the open storage (shutdown).
Pinned: `markerlessDatabaseIsRefusedOnOpenAndOpenNoAuthenticate` asserts
`internal.getStorage(dbName) == null` after EACH refusal (`:242-243`, `:250-251`) and that the
corpse remains droppable afterwards.

**Interlock with CS54 (coherence, load-bearing).** The crash review had flagged the OLD
open-storage retention as load-bearing for `drop()` (§4.3). Post-unregistration, `drop()` of a
marker-refused corpse reaches its `finally` with the storage GONE from the map →
`getOrInitStorage` mints a FRESH never-opened storage (status CLOSED, `AbstractStorage:397`)
→ `storage.delete()` — which only works because of the CS54 `doDelete` guard. The two fixes
ship together in one commit and `dropDiscardsCorpseWithoutSurfacingRefusal` (DISK, fresh
context) pins exactly this composition. Consistent.

**Loud create probe.** In the internal create's `failIfExists=false` arm
(`:845-869`): `getAndOpenStorage(name, solveConfig(config))` opens the existing storage
exactly as `open()` would, probes the marker; marker-less → `GenesisIncompleteException`
naming BOTH recovery routes ("drop it and re-create" / "open it directly to get the migration
guidance" — deliberately no unconditional discard advice, correct for the old-format
population) and the probe storage is unregistered before the throw. Healthy (openable, marked)
DB → falls through to the pre-existing "already exists, nothing to do" log; the storage is
left open+registered — the same state an `open()` produces (comment accurate). **No misfire on
healthy openable DBs.** Boundary accuracy: the config-only boolean overload
`YouTrackDBImpl.createIfNotExists(String, DatabaseType, YouTrackDBConfig)` (`:233-239`)
short-circuits on `exists()` and never reaches the probe — exactly as the test javadoc
(`GenesisFailureContainmentTest.java:288-297`) records; the user-credential overloads route
through `doCreate(..., false, ...)` (`:194-197`) into the probe. Pinned by
`createIfNotExistsIsLoudOverGenesisIncompleteCorpse` (`:299-323`, cause-chain + both-routes
message assert + post-drop). One fix-introduced behavior edge on NON-openable existing DBs →
**RG4** below.

---

## 4. CN56 — `catch (Error)` arm — **VERIFIED**

`YouTrackDBInternalEmbedded.java:829-835`: `cleanUpFailedCreate(name, e)` then `throw e`
unwrapped. `cleanUpFailedCreate` widened to `Throwable` (`:887`); its internal suppression
(`addSuppressed` onto the Error) is best-effort as recorded. An `Error` thrown BY the cleanup
itself would replace the primary — consistent with the crash review's recorded Error
discipline (no new finding).

---

## 5. CS53 + CN58 — `initCustomStorage` containment — **VERIFIED**

`:1177-1200`: `catch (RuntimeException | Error e)` around `internalCreate`; purges the
`sharedContexts` entry keyed by `storage.getName()` (the same key `getOrCreateSharedContext`
used), removes the storage id, `storage.delete()` (close + on-disk removal), suppression onto
the primary, rethrow. The comment's claim "the storage was not yet in the storages map" is
TRUE — `storages.put(name, storage)` sits at `:1202`, after the guarded block. Parity with
`cleanUpFailedCreate` achieved on this path.

---

## 6. CS54 — `doDelete` skip for never-opened storage — **VERIFIED (by trace)**, disposition accurate

`AbstractStorage.java:1688-1696`: `makeStorageDirty()` skipped iff `status == STATUS.CLOSED`.
Trace: (i) a never-opened storage is CLOSED (field init `:397`) and has no startup-metadata
channel (opened only by `preCreateSteps`/`open`) — the pre-fix NPE site; (ii) with the skip,
`doShutdownOnDelete` early-returns on CLOSED (`:7295-7298`) and `postDeleteSteps`
(`DiskStorage.java:539-543` → `deleteFilesFromDisc`) operates purely on the directory path —
no channel needed; (iii) all OPEN/in-error delete paths are byte-identical (the guard is a
strict narrowing to a state that previously always threw). Soundness of skipping the dirty
flag: the flag forces recovery on a crashed WRITER; a CLOSED storage has no in-flight writes,
and deletion removes the files wholesale — a crash mid-delete leaves a partial file set that
fails config load loudly (pre-existing envelope). The disposition (no drop-level suite test;
pre-existing open-failure native-buffer leak kills the harness JVM) is recorded in THREE
places consistently: the commit message, the test-file NOTE
(`GenesisFailureContainmentTest.java:381-388`), and the new Surprises bullet
(`track-8.md:143-148`). Note additionally that the guard IS exercised transitively: the
corpse-drop test's `finally`-path delete runs on a fresh never-opened storage (§3 interlock) —
the never-opened-delete mechanics are test-covered, only the W1/W2 open-failure corpse SHAPE
is not.

---

## 7. CN57(a) — down-conversion suppression — **VERIFIED**

`EntityLinkSetImpl.java:344-350`: `&& !isOwnedByMetadataRecord()` added to the
btree→embedded arm with an accurate comment (containment symmetry; the pre-pin-btree-root
residual under non-default bottom threshold correctly REMAINS with the follow-up — extended
Surprises bullet, `track-8.md:138-145`). No misfire: for metadata records the arm is now
never taken; user records unchanged.

---

## 8. BG16 — javadoc disposition — **VERIFIED (defensible)**

`SchemaClassEmbedded.java:47-52`: documents that a non-unsafe in-tx create reaches
`fireDatabaseMigration`, whose batched rewrites degrade to nested no-op commits and join
(grow) the caller's transaction, rollback discarding them. Matches the baseline review's H6
trace exactly. No code guard is a defensible disposition: the semantics are the only ones
compatible with a transaction, the behavior is consistent under rollback, and the cost is the
caller's — the doc names it where the caller's code path starts. The episode record
(`track-8.md`, Dispositions paragraph) says the same.

---

## 9. TQ16 / TQ17 / TQ18(a) — test hardening — **VERIFIED**

- **TQ16** — `createPropertyInsideTransactionPersistsAtCommit`
  (`SchemaClassOperationsTest.java:743-768`): user path (public `Schema` proxy), begin →
  `createProperty` → commit, asserts committed visibility + type, then forces
  `SchemaShared.reload(session)` — which re-parses the PERSISTED records through `fromStream`
  (`SchemaShared.java:727-742`) — and re-asserts. This pins the commit half at the byte level
  (a regression in property/global-property promotion or persistence fails the re-parse
  assert), closing the transitively-only-covered gap the finding named.
- **TQ17** — `createIfNotExistsRecreatesAfterFailedCreate` (`:174-199`): walks the cause
  chain for `IllegalStateException` with message prefix `"injected phase-"` — matching the
  injector's exact messages (`:85`, `:93`); rethrows unrelated failures and additionally
  asserts `injectedSeen`. The environmental-failure false-green path is closed.
- **TQ18(a)** — `createMarkerlessDiskCorpse` (`:203-218`): DISK profile, marker flipped, FULL
  context close, fresh context — the refusal/drop pins now exercise the reopened-config
  (durable) marker read, not a live cache flip. Used by the refusal test (`:233-253`), the
  drop-exemption test (`:272-286`), and both new BG14/probe tests. Fidelity gain is real: the
  old MEMORY/live-context shape could not distinguish a cache read from a disk read and could
  not exercise the fresh-storage drop path at all.

---

## 10. Doc dispositions — **VERIFIED (present + accurate)**

All in `9976f90218`:

- **CS56** — restore-outside-the-belt boundary: Surprises bullet (`track-8.md:149-156`) AND
  design-drafts §A1 as-built note (b) (`track-8-design-drafts.md:787-794`). Content matches
  the crash review's H12 characterization (backup's marker state; mid-restore crash envelope
  pre-existing; optional clear-then-reset hardening deferred).
- **CN59** — threaded into Step 6 WI3's content list (`track-8.md:490-493`: the
  genesis-incomplete operator guidance incl. the crashed-OSystem loud-brick with no
  self-heal). Matches the concurrency review's §6 characterization.
- **BG17/CN57(b)/CS57** — the btree-bag Surprises bullet extended (`track-8.md:138-145`)
  naming all three residuals (ownerless ctors, LinkBag conversion, down-conversion arm) with
  the follow-up ownership statement. Accurate against the reviews.
- **CQ18** — deferral recorded in the episode's Dispositions paragraph (dropProperty in-tx
  throw asymmetry → de-guard follow-up).
- **Native-buffer leak** — new Surprises bullet (`track-8.md:143-148`), marked pre-existing,
  tied to the CS54 test infeasibility. Consistent with the test-file NOTE.
- **TQ18(b)** — reasoned deferral in the episode (the import guard no-op arm is the live
  behavior at real import call sites) — a defensible reading; the delegation stays recorded.
- **CS52 as-built** — design-drafts §A1 note (a) records the new firing point. (See RG3 for
  the one precision gap it does not record.)

---

## 11. Scope check & record coherence

The code commit touches exactly the 11 files the approved remedies require; every hunk maps
to a finding (SharedContext: CS52 + javadoc; YouTrackDBInternalEmbedded: CS52 removal,
BG14/CN55/CS55 ×4 sites, CN56, CS53/CN58; EntityLinkSetImpl: CN57(a);
GenesisIncompleteException: CS52/BG14 javadoc; IndexAbstract/SchemaShared/SecurityShared:
CQ17 comments only — verified no behavioral hunks; SchemaClassEmbedded: BG16 comment only;
AbstractStorage: CS54; the two test files: TQ16/TQ17/TQ18(a) + the CS52/BG14 pins + the CS54
NOTE). Nothing beyond the remedies. The two record commits are cleanly split (code+tests vs
records), mutually consistent, and the episode's claims about mechanisms were each verified
above; the verification numbers (full core run, 513 ITs, coverage 89.5/83.1) are not
re-executable read-only and are taken as recorded.

---

## 12. RG findings (fix-introduced, both suggestion severity — no blockers)

### RG3 — suggestion — W3/W4 refusal-signature narrowing unrecorded
`SharedContext.java:154` (schema.load precedes the belt) + `SchemaShared.java:1370-1375`.
The relocation means a pointer-less corpse (W3/W4: crash before `setSchemaRecordId` is
durable) now fails with `SchemaNotCreatedException` BEFORE the marker is ever read — at the
reviewed commit `4d23111516` these states got the uniform `GenesisIncompleteException`.
Consequences: (i) the design W-table's third column ("condemned by the marker") is no longer
literally exercised for W3/W4 — though the table's SECOND column lists exactly
"`SchemaNotCreatedException` (loud)" as their open behavior (design-drafts:761-762), so the
shipped behavior matches the ruled letter; (ii) `drop()` of a W3/W4 corpse no longer enjoys
the CN54 tolerance — the failure surfaces (deletion still happens in the `finally`, same as
the W1/W2 contract). Fail-closed is fully preserved; nothing opens. The gap is purely one of
record precision: the §A1 as-built note (a) and the review-fix episode do not mention the
narrowing. **Remedy: one sentence in the design-drafts §A1 as-built note (and/or the CN54
scope line) stating that W3/W4 are condemned by the pre-belt `SchemaNotCreatedException` and
sit outside the drop-tolerance, with deletion still guaranteed by the drop finally.**

### RG4 — suggestion — `failIfExists=false` probe surfaces open failures for existing-but-unopenable databases
`YouTrackDBInternalEmbedded.java:853` (`getAndOpenStorage` as the probe). To read the marker
the probe must OPEN the existing storage; if that open FAILS (a W1/W2 residue — aligned with
the intent, loud beats silent no-op — but also a HEALTHY database that cannot open with the
provided create-config, e.g. an encryption/config mismatch), the user-credential
`createIfNotExists` overloads now propagate the open failure where they previously logged
"already exists, nothing to do" and returned. Fail-loud direction, arguably an improvement,
and the config-only boolean overload keeps the old contract — but it is a behavior change on
a healthy-DB path that exceeds the letter of the approved remedy ("probe the marker") and is
not named in the record. **Remedy: a one-line record (episode or test javadoc) that the probe
converts open failures of the existing database into create-time failures for the
user-credential overloads.**

---

## 13. Compact verdict block

| ID | Verdict | Evidence gist |
|---|---|---|
| CS52 | **VERIFIED** | Belt at `SharedContext.load:153-173` after `schema.load`; all 4 session-ctor sites funnel through `init→loadMetadata→load` (structurally stronger than path enumeration); `loaded=true` only post-marker (`create:283→285`) or post-belt; no bypass (H-B1..B3 refuted); redirect pinned by `oldFormatDatabaseGetsMigrationRedirect...` (no GIE in chain + "export"); W5-W9a marker-refused, W3/W4 revert to the W-table's own `SchemaNotCreatedException` signature (→RG3); no double-fire, belt precedes `security.load` |
| CQ17 | **VERIFIED** | All three comments state the real mechanism; `deleteRecord:552` synchronous-callback dependency confirmed; "toStream removes the root link EXPLICITLY" true (`SchemaShared:1216-1225`); SecurityShared dead-path claim replaced with the verified tx-free precondition |
| BG14+CN55+CS55 | **VERIFIED** | `unregisterGenesisIncompleteCorpse:456-489` purges storages/ids/contexts + shutdown, genesis-guarded, suppression-safe, wired at newSessionInstance + both pool arms + probe; unregistration asserted in tests; probe loud with both recovery routes; config-only overload boundary documented accurately (`YouTrackDBImpl:233-239`); CS54 guard is the load-bearing partner for the post-unregistration drop (pinned on DISK); RG4 boundary noted |
| CN56 | **VERIFIED** | `catch (Error)` at `:829-835`, cleanup best-effort via `cleanUpFailedCreate(Throwable)`, Error rethrown unwrapped |
| CS53+CN58 | **VERIFIED** | `initCustomStorage:1177-1200` containment; `storages.put` after the guarded block (claim true); same key for context removal |
| CS54 | **VERIFIED (trace)** | `doDelete:1693-1696` skips dirty flag on CLOSED; `doShutdownOnDelete` early-returns; `postDeleteSteps` channel-free; strict narrowing (OPEN paths byte-identical); disposition recorded in 3 consistent places; mechanics transitively covered by the fresh-storage corpse-drop test |
| CN57(a) | **VERIFIED** | `EntityLinkSetImpl:344-350` guard + accurate comment; residual correctly left with the follow-up record |
| BG16 | **VERIFIED** | Doc-only disposition defensible; comment matches the verified nested-join semantics |
| TQ16 | **VERIFIED** | User-path commit + forced `reload` re-parse of persisted bytes (`SchemaClassOperationsTest:743-768`) |
| TQ17 | **VERIFIED** | Cause-chain walk for `"injected phase-"` (matches injector messages), unrelated failures rethrown |
| TQ18(a) | **VERIFIED** | `createMarkerlessDiskCorpse` (DISK + fresh context) used by refusal/drop/probe tests; genuine reopened-config fidelity + BG14 asserts |
| Doc dispositions | **VERIFIED** | CS56 (Surprises + §A1 note), CN59 (Step 6 WI3 list), BG17/CN57(b)/CS57 (extended bullet), CQ18 (episode deferral), native-buffer-leak bullet — all present and accurate |
| Scope/coherence | **CLEAN** | 11 files ↔ approved remedies, comment-only hunks verified comment-only; record commits split code vs docs, claims verified |
| RG3 | new, suggestion | W3/W4 now fail via pre-belt `SchemaNotCreatedException` (matches W-table's stated signature, but "condemned by the marker" + CN54 tolerance no longer literally cover them; deletion preserved) — record the narrowing |
| RG4 | new, suggestion | `failIfExists=false` probe surfaces open failures of existing-but-unopenable DBs (incl. healthy config-mismatch) where it previously no-op'd silently — fail-loud, but record the boundary |

**Gate outcome: PASS — 0 blockers, 0 rejections, all 12 findings + dispositions verified; 2
new suggestion-level RG findings (RG3, RG4), both record-precision only, no code change
required to stay fail-closed.**
