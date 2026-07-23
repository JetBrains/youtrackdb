# Crash-safety / durability review — Track 8 Step 1, iteration 1

- **Commit under review:** `6611cbf6b2` ("Embed blob collections into storage creation"), branch
  `transactional-schema`, HEAD `550eed6673`.
- **Perspective:** crash-safety / durability. Finding-ID prefix **CS**, starting at **CS47**.
- **Binding spec:** `plan/track-8.md` Step 1; `plan/track-8-design-drafts.md` §0 R3, G2.b
  (incl. the CN50 single-read pin), the amended FM-G3 W-state enumeration (§A1), FM-G4/FM-G5,
  §A3/FM-M16.
- **In-scope files (from `git show 6611cbf6b2 --stat`):**
  - `core/.../db/SharedContext.java` (register-only blob loop)
  - `core/.../storage/impl/local/AbstractStorage.java` (`doCreate` blob loop)
  - `core/.../db/StorageEmbeddedBlobCollectionsTest.java` (new)
  - fixture fixes: `EntityImplTest.java`, `CommandExecutorSQLTruncateTest.java`,
    `tests/.../StringsTest.java`
- **Mode:** read-only; no Maven runs. All claims below are code traces (file:line at HEAD
  `550eed6673`, which is identical to `6611cbf6b2` for the production files) or diffs against the
  parent commit `6611cbf6b2^`.

---

## 0. Decision criteria and premises

**Decision criteria.**

- **blocker** — a crash point exists at which the post-change code leaves an on-disk state that
  recovery/open composes into silent corruption, data loss, or a silently-usable partial database
  that did NOT exist at the same crash point pre-change (HEAD-before = `6611cbf6b2^`).
- **should-fix** — a recorded baseline claim or design-conformance property that a downstream step
  (Step 2/3) explicitly relies on is factually wrong or diverges from the implementation; or a
  spec-pinned durable property lacks its pinned test.
- **suggestion** — residual verification gaps, visibility notes, or design-acknowledged exposures
  worth an explicit record.
- **null verdict** — licensed where every enumerated crash point is shown unchanged-or-strictly-
  better vs HEAD-before, with the trace cited.

**Numbered premises (all traced).**

- **P1.** `doCreate` is shared by both storage profiles: `DiskStorage.doCreate:310-318` mkdirs and
  delegates to `super.doCreate` (`AbstractStorage.doCreate:1442`); `DirectMemoryStorage` does not
  override it (grep: no `doCreate` in `DirectMemoryStorage.java`).
- **P2.** The whole `doCreate` body after `makeStorageDirty()` (`AbstractStorage:1489`) runs inside
  ONE WAL atomic operation (`executeInsideAtomicOperation:1491-1540`): config-component create
  (`:1493-1496`), `internal` collection (`:1510`), **new** `$blob0..N-1` loop (`:1520-1525`),
  config records (`:1527-1535`), instance id (`:1537`), `clearStorageDirty()` (`:1538`).
- **P3.** Each `doAddCollection` (`:7024-7048`) → `doCreateCollection` (`:7078-7105`) writes the
  collection file, its storage-configuration entry (`updateCollection`, `:7100-7101`), and its
  link-collections B-tree component (`:7103`) **as WAL-buffered intent inside the passed atomic
  operation**; the javadoc contract (`:7050-7062`) pins "a rolled-back or crashed-before-commit
  operation leaves no collection file behind". The `internal` collection used this exact mechanism
  pre-change; the blob loop adds N more calls to the same mechanism in the same op.
- **P4.** An exception inside the op routes through `endAtomicOperation(op, error)` → rollback
  (`AtomicOperationsManager:188`, `:310-399`, with the "no physical write on rollback" assert at
  `:384-392`).
- **P5.** The count is read exactly once, inside `doCreate`, from the create-time
  `contextConfiguration` parameter (`AbstractStorage:1520-1522`). Repo-wide grep for
  `STORAGE_BLOB_COLLECTIONS_COUNT` in `core/src/main/java` finds only: the `GlobalConfiguration`
  definition (`:274-276`, default 8), the `doCreate` read, and a comment in `SharedContext`.
  CN50's single-read pin holds.
- **P6.** The register-only loop (`SharedContext.create:214-218`) enumerates
  `storage.getCollectionNames()` (in-memory `collectionMap` keys, lower-cased —
  `AbstractStorage:2323-2352`, lower-casing at `doCreateCollection:7087`) against
  `\$blob\d+` (`SharedContext:38`) and calls `schema.addBlobCollection(session, id)` per match —
  a direct call on the SharedContext's `SchemaShared` instance (field typed `SchemaShared`,
  instantiated `SchemaEmbedded`, which does NOT override `addBlobCollection`).
- **P7.** `SchemaShared.addBlobCollection:1598-1607` = `acquireSchemaWriteLock` → add to the
  in-memory `blobCollections` set → `releaseSchemaWriteLock(session)` [default `iSave=true`,
  `:791-793`] → at `modificationCounter == 1` → `saveInternal(session)` (`:805`).
  `saveInternal:1498-1518`: `txLocal` is false on the committed instance; no tx is active at
  genesis (`SharedContext.create` is called with no wrapping transaction —
  `DatabaseSessionEmbedded.createMetadata:598-603`, design G.0 "[verified]"); so it runs
  `session.executeInTx(transaction -> toStream(session))` — **a synchronous, per-iteration,
  self-committed save of the schema root record**. If a tx WERE active it would throw
  `SchemaException` (`:1508-1513`).
- **P8.** `SharedContext.create` has exactly one production call site:
  `DatabaseSessionEmbedded.createMetadata:602` (create-time only). The reopen path is
  `SharedContext.load:131-152` → `schema.load` → `fromStream`, which reads the **persisted**
  `blobCollections` embedded set off the root record (`SchemaShared:1052-1053`). The register loop
  never runs at reopen.
- **P9.** Blob readers/writers are schema-set-driven only: placement of a new blob picks a random
  member of `getBlobCollectionIds()` (`DatabaseSessionEmbedded:4459-4465`); the id registry is the
  schema root (`:5028`). Grep for `$blob` in `core/src/main/java` finds only the two changed sites
  (plus comments) — no production code assumes ids `1..N`.
- **P10.** Pre-change loop (parent commit, `SharedContext.create:198-204`):
  `session.addCollection("$blob"+i)` → `AbstractStorage.addCollection:1683-1712` —
  `makeStorageDirty()` + **its own** `calculateInsideAtomicOperation(doAddCollection)` per blob —
  followed by the same `schema.addBlobCollection(session, id)` as post-change.

---

## 1. Charter (1) — atomicity of the enlarged create atomic operation

**Question.** With `doCreate` now creating N+1 collections in one op, what does a crash at each
point leave on disk, and does recovery/replay compose (component registration, name-id map, config
records)? Compared against the same crash points pre-change.

### 1.1 Crash-point enumeration (disk profile; process-kill/power-loss model)

Crash points are exhaustive over the `create()` → `doCreate()` → `SharedContext.create()` genesis
sequence as it exists at this step (pre-Step-2/3). "Pre-change" = `6611cbf6b2^`.

| CP | Crash point | Post-change on-disk state → reopen outcome | Pre-change at same point | New exposure? |
|---|---|---|---|---|
| C0 | before `preCreateSteps()` (`AbstractStorage:1488`; dir may already exist via `DiskStorage.doCreate:312-315` mkdir) | empty/partial directory, no configuration → open fails loudly; residue may make `exists()` true (pre-existing CS34 residue, Step 3's cleanup) | identical | **No** (W0/W1 boundary, unchanged) |
| C1 | after `preCreateSteps`/`initWalAndDiskCache` (`:1478`), before `makeStorageDirty` (`:1489`) | WAL/dirty-flag files exist, no config components → open fails loudly | identical | **No** |
| C2 | after durable `makeStorageDirty`, anywhere inside the atomic-op body **before** `clearStorageDirty` (`:1493-1537`) — includes every point inside the NEW blob loop (`:1523-1525`) | dirty flag set → reopen runs recovery → the incomplete op is rolled back; all N+1 collection creates, their config entries, and their SLBB components are WAL-buffered intent (P3) and vanish together; configuration absent → configuration load fails loudly | identical mechanism, minus the N blob creates (which happened later, see C5') | **No** — this is W1 of the amended FM-G3; the blob creates ride the same rollback that already covered the `internal` collection and the config records |
| C3 | after durable `clearStorageDirty` (`:1538`), before the op's WAL commit is durable | clean-flagged corpse: recovery skipped, configuration load fails loudly — the pre-existing W2/CS37 window, carried verbatim in the design ("rolls back OR fails config load") | identical (`clearStorageDirty` position unchanged; blob creates precede it in the same lambda) | **No** — window neither widened nor narrowed in kind; it is a fixed ordering point, not proportional to op size |
| C4 | op commit durable, before the optional `synch()` (`:1434-1440`, `STORAGE_MAKE_FULL_CHECKPOINT_AFTER_CREATE`) | committed create op: pages flush lazily under WAL-before-data; a reopen replays/loads config + all N+1 collections from the config records written in the same op — name-id map (`collectionMap`) is rebuilt from those records at open, so component registration, name-id map, and config records are mutually consistent by construction (single op) | pre-change the blobs did **not yet exist** at this point; each was later created in its own op with its own dirty-flag cycle (P10) | **No** — strictly fewer distinct durable states post-change |
| C5 | during `SharedContext.create` before the register loop (`schema.create` … `security.create` … O/V/E, `SharedContext:194-206`) | physical `$blob0..N-1` exist, unregistered → **inert**: readers consult only the schema set (P9); half-genesis DB, silent-reopen exposure is the pre-existing pre-Step-3 window (W3–W6) | same crash points existed; difference: **no** physical blobs existed yet | **No new unsafe state** — the amended FM-G5 row covers exactly this ("blobs physically present but unregistered → inert; condemned by §A1 containment [Step 3]"); see §4 |
| C5' | *(pre-change only)* mid blob loop: crash between `session.addCollection("$blob"+i)` ops | — | partial physical blob set (i collections durable, each own op) + registration prefix | **Eliminated** by this commit — the design's motivation (FM-G4) |
| C6 | mid register loop (`SharedContext:214-218`) | registration prefix persisted (each iteration self-commits the root, P7); physical set complete; unregistered suffix inert (P9); half-genesis, condemned later by Step 3 | partial physical + partial registration | **No** — strictly better (only the registration dimension remains partial) |
| C7 | after the register loop, before `create` returns / listeners | complete blob set, half-genesis W6/W7-class exposure — pre-existing, Step 3's containment | identical | **No** |

**Exception path (non-crash) at any point inside the op:** rollback via P4 undoes all on-disk
intent; the in-memory publishes done by `registerCollection` (`:6999-7022`, called per collection
inside the op by `doAddCollection:7042-7047`) are NOT undone — N+1 phantom in-memory registrations
instead of pre-change's 1. This is the pre-existing legacy-wrapper behavior explicitly documented
in the `doCreateCollection` javadoc (`:7057-7060` "its existing crash-safe behavior is unchanged");
`create()`'s catch closes the storage (`AbstractStorage:1414-1420`), and the on-disk/registry
residue is the known CS34 window owned by Step 3. Same failure class, same containment plan — no
new exposure.

**Replay composition.** For a *committed* create op, WAL replay and open-time loading are driven
entirely by the config records + component files written inside that op (P3); the blob collections
are indistinguishable in kind from the `internal` collection that was already there. For an
*uncommitted* op, rollback is page-op-generic and size-independent. No id-allocation hazard: the
first-null-slot scan (`doAddCollection:7024-7033`) over an empty registry yields deterministic
`internal`=0, `$blob i`=i+1 — the layout the new test pins.

**Verdict (charter 1): NULL — no new crash state; the enlarged op composes.** Justification: every
crash point C0–C7 is traced to an outcome identical to or strictly contained in the pre-change
outcome; the one state class this commit adds (physical-blobs-present-unregistered, C5/C6) is
enumerated and accepted by the binding spec (FM-G5 amended row, §A1 W-states), and the state class
it removes (partial physical blob set, C5') was strictly worse. The verified-mechanism claim of
FM-G4 ("whole `doCreate` body is one WAL atomic operation") matches the code (P2).

## 2. Charter (2) — the register-only rewrite's durability baseline

**Question.** Did the rewrite move or lose any durable write, and does the persisted-root-payload
test actually pin the blob-set persistence?

**Trace of durable writes, pre vs post:**

| Durable write | Pre-change | Post-change |
|---|---|---|
| Physical `$blob i` collection (file + config entry + SLBB component) | own atomic op per blob, mid-genesis (`storage.addCollection:1683-1712` via `session.addCollection`, P10) | inside the storage-create op (`AbstractStorage:1523-1525`) — moved into a **strictly stronger** atomicity envelope |
| Schema-root `blobCollections` registration | `schema.addBlobCollection(session, id)` per blob → `releaseSchemaWriteLock` → `saveInternal` → `executeInTx(toStream)` — synchronous self-committed root save **per iteration** (P7) | **identical call, identical path** (`SharedContext:216`) — only the id source changed (storage name-lookup instead of `addCollection`'s return value) |

No durable write is lost, and none moved *out* of a durability envelope. The registration write is
byte-for-byte the same mechanism as pre-change.

**Test pinning.** `StorageEmbeddedBlobCollectionsTest.persistedBlobCollectionIds`
(`StorageEmbeddedBlobCollectionsTest.java:81-89`) loads the schema root record and asserts its
persisted `blobCollections` embedded set equals the storage-created ids — asserted on both
profiles (`:117-121`) and in the frozen-count test (`:190-192`). `blobLayoutSurvivesDiskReopen`
(`:200-241`) additionally pins the **load-path** parse (`fromStream:1052-1053`) after a full
context close + disk reopen, plus a blob-record byte round-trip across the reopen. The persisted
payload is pinned directly, as the Episode claims. ✔

**However — the recorded baseline claim is factually wrong (finding CS47).** `track-8.md`
§Surprises ("Q-G3 pre-observation") and the Step-1 Episode record: *"genesis blob-set persistence
at HEAD relies on a schema-root save that happens downstream of `SharedContext.create`'s blob loop
(the loop itself only mutates the in-memory set)"*. The trace (P7) refutes both halves: the loop
does NOT only mutate the in-memory set — **each `addBlobCollection` synchronously self-commits the
root record via `releaseSchemaWriteLock(session)` → `saveInternal`** — and there is NO schema-root
save downstream of the loop inside `SharedContext.create` (after the loop: the geospatial listener
no-op and `loaded = true`, `SharedContext:220-233`; nothing schema-saving follows in
`internalCreate` either). Corroboration by contradiction: if the loop's own path did not persist,
the new test's persisted-root assertion would be red — it is green (Episode: 9/9).

The same trace exposes a **design-conformance divergence with Step-3 teeth**: design G2.b (drafts
`:133-135`) specifies the registration call "routes through `resolveForWrite()`
(`SchemaProxy.java:460-462`), i.e. inside the genesis schema tx it is a pure tx-local root-payload
write". The implementation calls `SchemaShared.addBlobCollection` **directly** on the committed
instance (P6) — never through `SchemaProxy`. At Step 1 the two routes are behaviorally identical
(outside a tx, `resolveForWrite()` returns the committed delegate → same `saveInternal`
self-commit). But Step 3's plan is to wrap "that **unchanged** loop" into the phase-1 transaction
(track-8.md Step 1 seam note; Step 3 "Step 1's loop, now tx-wrapped"): wrapping the
*as-implemented* loop in an active tx makes `saveInternal` **throw**
`SchemaException("Cannot change the schema while a transaction is active…")`
(`SchemaShared:1508-1513`) at the first blob registration — i.e. the loop mechanics MUST change
(route via the proxy / session so the tx-local copy with `txLocal=true` absorbs the write,
`:1500-1506`) contrary to both the recorded baseline ("pure schema write that can be wrapped
unchanged") and the seam declaration ("loop mechanics are this step's [seam], tx-wrapping is
Step 3's"). Failure mode is loud and immediate (a genesis that cannot create any database), so
this is not a durability hazard — it is a false verified-baseline record that Step 2's Q-G3 verify
and Step 3's implementer will inherit. **Should-fix: correct the two track-8.md records (Surprises
bullet + Episode surprise) to the traced mechanism, and note that Step 3's tx-wrap requires
re-routing the registration through the proxy/session path (design G2.b's letter).** Production
code needs no change at this step.

**Verdict (charter 2): no durable write moved/lost (null on the code); the pinned baseline record
is wrong (CS47, should-fix, documentation).**

## 3. Charter (3) — reopen contracts for pre-Track-8 layouts

**Question.** A DB created pre-Track-8 (blobs in the highest slots) reopened under the new code:
does anything re-run the name-enumeration, and does anything assume ids `1..N`?

1. The register-only loop runs **only** at create (single call site, P8). A legacy DB's reopen
   goes through `SharedContext.load` → `SchemaShared.fromStream:1052-1053`, which restores the
   persisted id set (high slots) verbatim. Registration is layout-preserving by construction.
2. No production consumer assumes `1..N` (P9): blob placement picks randomly from the schema set
   (`DatabaseSessionEmbedded:4459-4465`); export reads the set (`DatabaseExport.java:456`); the
   only `$blob`-literal sites in production are the two changed ones. The `1..N` layout is pinned
   only for **fresh** DBs, in the new test.
3. The name pattern's lower-case premise is sound: storage lower-cases every collection name at
   creation (`doCreateCollection:7087`) and `getCollectionNames()` returns those keys
   (`AbstractStorage:2340`), so `\$blob\d+` matches exactly the storage-birth names at genesis
   (fresh storage → no foreign `$blob*` names can pre-exist the loop).
4. **Known armed exposure (not a reopen path):** the importer's blob-collection mapping
   (`DatabaseImport.java:528-541`) resolves the dump's raw blob ids in the **target** id space.
   From this commit until Step 5's §A3 fix, importing a pre-Track-8 dump WITH blob content into a
   fresh (renumbered) target misclassifies: the dump's high blob ids land on class collections
   (or nonexistent ids) in the target — exactly FM-M16, recorded in the binding spec as "defect at
   HEAD under R3 — closed by §A3, pinned by M.5 #13" and sequenced into Step 5. This is a
   design-acknowledged intra-track window, not a Step-1 defect; recorded here for visibility as
   CS48 (suggestion) because the Step-1 Episode does not mention that the window is now live
   in-tree.

**Verdict (charter 3): NULL — legacy layouts register identically (via the persisted root, not the
loop) and nothing in production assumes `1..N`.** The one legacy-layout casualty (import mapping)
is pre-recorded FM-M16 with a scheduled fix; flagged as a visibility suggestion only.

## 4. Charter (4) — crash between storage create and SharedContext registration

**Question.** Does the code match the design's accepted-state claims for the residual window
(pre-Step-3), and does Step 1 introduce any NEW exposure vs HEAD-before at those points?

- The window (C5/C6 above) leaves: storage durable with N+1 collections; schema root possibly
  absent (W3 → `SchemaNotCreatedException`, loud), partially built (W4/W5 → loud load failures),
  or built with zero/partial registration (W6-class → **silent reopen**, pre-existing at
  HEAD-before for the same crash points). Step 1 changes only the *content* of the silent state:
  physical blob collections now pre-exist their registration. They are inert (P9): unreachable by
  the blob API (not in the schema set), un-collidable by later DDL (class collections allocate by
  first-null-slot above them; generated names are `c_<n>`, `SchemaShared.nextCollectionName`,
  no `$blob` shape), and re-registerable only by the genesis loop which never re-runs.
- This exactly matches the amended FM-G5 row ("readers consult only the schema set → blobs inert;
  the half-create is condemned by the §A1 containment" — which is Step 3's deliverable) and the
  §A1 W-state table. Step 1 makes no claim of containment; `track-8.md`'s Step-1 Episode records
  precisely this ("unregistered-blob inertness unchanged pending Step 3's containment").
- New-vs-before check: at HEAD-before the same crash points also reopened silently (worse: with
  partial *physical* blob sets possible, C5'). Post-change the mid-genesis structural-op crash
  states are gone and only the schema-registration dimension can be partial.

**Verdict (charter 4): NULL — the code matches the design's accepted-state claims; no NEW exposure
is introduced; the known pre-existing silent-reopen window is out of scope (Step 3).**

## 5. Alternative-hypothesis check / hypothesis log

| # | Hypothesis | Outcome |
|---|---|---|
| H1 | The enlarged create op (9+ collection creates) could hit an atomic-op size or WAL-segment limit, changing crash behavior | Rejected — no per-op size cap exists; `STORAGE_ATOMIC_OPERATIONS_TABLE_COMPACTION_LIMIT` (`AbstractStorage:1483-1485`) bounds table compaction (op count), not op size; ops span WAL segments generically |
| H2 | Register loop iterates a live keySet view (`Collections.unmodifiableSet(collectionMap.keySet())`, `AbstractStorage:2340`) while each iteration commits a root-save tx → `ConcurrentModificationException` | Rejected — the root save writes a record in the existing `internal` collection and never mutates `collectionMap`; genesis is single-threaded under the factory monitor + `SharedContext.lock` |
| H3 | The `\$blob\d+` pattern could capture a non-genesis collection at create | Rejected — the loop runs once, immediately after storage birth (P8); a fresh storage holds only `internal` + the storage-birth blobs + config-internal components |
| H4 | A hidden second read of `STORAGE_BLOB_COLLECTIONS_COUNT` breaks CN50 | Rejected — repo grep (P5): only the `doCreate` read exists |
| H5 | Legacy reopen re-runs the loop and re-registers by name (wrong ids for high-slot layouts) | Rejected — create-only call site (P8); reopen restores the persisted set (`fromStream:1052-1053`) |
| H6 | The rewrite lost the registration's durable write (per the recorded "in-memory only" baseline) | Rejected in both directions — the write exists, but the recorded baseline mechanism is wrong: it is a per-iteration `saveInternal` self-commit, not a downstream save (P7) → **CS47** |
| H7 | Blob placement or another reader assumes ids `1..N` post-change | Rejected — schema-set-driven (P9) |
| H8 | The renumbering arms the importer's raw blob-id mapping before Step 5's fix | Confirmed and pre-recorded (FM-M16/§A3); design-accepted sequencing → **CS48** (visibility only) |
| H9 | `clearStorageDirty` ordering relative to the new loop changed the W2 window | Rejected — position unchanged (`:1538`), blob creates precede it inside the same lambda |
| H10 | The exception (non-crash) path now leaves more phantom in-memory registrations | Confirmed but same failure class as pre-change (N+1 vs 1), pre-existing CS34 envelope, Step 3's cleanup owns it — no new exposure kind |

## 6. Findings

### CS47 — should-fix — `track-8.md` (§Surprises "Q-G3 pre-observation" + Step-1 Episode) vs `SharedContext.java:216` / design G2.b
**The recorded blob-set persistence baseline is factually wrong, and the implemented registration
route diverges from design G2.b's proxy-routing claim — Step 3 will trip on both.**
Trace: `SchemaShared.addBlobCollection:1598-1607` → `releaseSchemaWriteLock:791-805`
(`iSave=true`, `modificationCounter==1`) → `saveInternal:1498-1518` →
`session.executeInTx(toStream)` — the loop self-commits the root **per iteration**; nothing
downstream of the loop saves the schema. The recorded claim ("the loop itself only mutates the
in-memory set; persistence rides a downstream schema-root save") is refuted; corroborated by the
green persisted-root-payload test, which could not pass if the claim were true.
Counterexample with teeth: Step 3's stated plan wraps "that unchanged loop" into the phase-1
transaction — under an active tx the as-implemented direct `SchemaShared` call throws
`SchemaException` at `SchemaShared:1508-1513` on the FIRST blob registration (design G2.b instead
specifies routing through `resolveForWrite()`, `SchemaProxy.java:460-462`, which under a tx yields
the `txLocal` copy whose `saveInternal` returns early at `:1500-1506`). Failure is loud, so no
durability hazard ships in Step 1; the defect is the false "verified baseline" record that Steps
2/3 explicitly inherit. Remedy: correct the two track-8.md records to the traced mechanism and
annotate Step 3's seam that the tx-wrap requires re-routing the registration through the
proxy/session path. No production-code change needed at this step.

### CS48 — suggestion — `DatabaseImport.java:528-541` (armed by this commit; fix owned by Step 5)
**The FM-M16 intra-track window is now live in-tree and unrecorded in the Step-1 Episode.** From
`6611cbf6b2` until Step 5's §A3 fix lands, importing a pre-Track-8 dump WITH blob content into a
fresh (renumbered) target resolves the dump's high-slot blob ids raw in the target id space —
misregistering class collections as blob collections (or failing on nonexistent ids). This is
design-acknowledged (FM-M16 "defect at HEAD under R3", pinned by M.5 #13, sequenced into Step 5),
so it is NOT a Step-1 defect — but the Step-1 Episode/Surprises do not note that the window is now
armed. Suggest one Surprises line so nobody trusts a blob-bearing legacy-dump import on this
branch mid-track.

### CS49 — suggestion — `StorageEmbeddedBlobCollectionsTest.java` (verification-shape note)
**The enlarged create op's crash atomicity is verified by mechanism-inheritance, not by a
dedicated crash test.** `blobLayoutSurvivesDiskReopen` uses a clean close (page flush on
shutdown), so no Step-1 test exercises WAL replay/rollback of the enlarged op; coverage rests on
the `doCreateCollection` WAL-intent contract (`AbstractStorage:7050-7062`, shared with the
pre-existing `internal` create) plus the generic `LocalPaginatedStorageRestoreFromWAL*` /
`StorageBackup*` ITs (run green per the Episode). This is conformant — the binding spec's Step-1
pins (G.5 #7/#8) demand no crash test, and W1/W2 are pre-existing envelopes — recorded as a
residual so Step 3's containment tests (G.5 #9, which do exercise the W-states) are understood to
be the first direct crash-path verification touching this layout.

## 7. Summary

| Charter item | Verdict |
|---|---|
| (1) enlarged-op atomicity | **Null** — C0–C7 enumerated; no new crash state; replay/rollback composes (single-op config records + components + name-id map); FM-G4 mechanism matches code |
| (2) durability baseline of the rewrite | Code: **null** (no durable write moved/lost; persisted payload + disk reopen pinned). Records: **CS47** — baseline mechanism claim false; G2.b routing divergence with Step-3 impact |
| (3) legacy reopen | **Null** — registration restored from the persisted root; no `1..N` assumption; FM-M16 armed-window visibility → **CS48** |
| (4) create→registration crash window | **Null** — matches design's accepted states (FM-G5/§A1); Step 1 strictly shrinks the mid-genesis state space |

No blockers. One should-fix (documentation/baseline record), two suggestions.
