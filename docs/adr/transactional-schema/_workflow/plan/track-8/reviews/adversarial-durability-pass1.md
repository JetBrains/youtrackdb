# Track 8 adversarial pre-implementation review — pass 1: crash-safety / durability

**Perspective:** crash/durability composition of the agreed design (Draft G + Draft M) before
implementation. **Finding prefix:** CS, numbering from CS34. **Reviewed artifact:**
`docs/adr/transactional-schema/_workflow/plan/track-8-design-drafts.md` at HEAD `6913cd321d`
(drafts grounded at `d664589d7f`; `git diff d664589d7f..HEAD` touches only the draft file itself,
so all code citations were re-verified against the identical tree). Read-only review; no Maven, no
product-code changes. The §0 and 2026-07-23 rulings (R1–R4, Q-G1–Q-G3, Q-M1–Q-M3) are treated as
settled inputs and are not re-litigated.

## 0. Decision criteria

A design statement is a **defect** here when one of the following holds, backed by a concrete
crash point or code trace:

1. **C1 — false or unenforced crash claim.** The design asserts a post-crash/post-failure state
   ("discarded", "reverted", "benign") that the cited code neither produces nor detects.
2. **C2 — open crash window.** A crash point along the designed sequence leaves an on-disk state
   the design neither prevents, detects, nor documents.
3. **C3 — ruling violation.** The design as drafted cannot satisfy a binding ruling's letter
   (notably Q-M2's "All rejections throw BEFORE any mutation of the target database").
4. **C4 — durability recipe incomplete.** A proposed fsync/rename/flag recipe misses a sync point
   required for the property it claims (file content durability AND rename durability).
5. **C5 — under-specified correctness mechanism.** A proposed validation is described in a way an
   implementer can follow faithfully and still produce a check that is unsound (misses
   corruption) or unsafe (rejects valid input).

Null verdicts are recorded with the reason no counterexample exists (§5).

## 1. Citation re-verification (draft vs code at HEAD)

Every behavioral citation used by the drafts was re-read. Verdict: **the draft's HEAD-state
citations are accurate** — no stale line numbers found. Key confirmations:

| Draft claim | Code | Verdict |
|---|---|---|
| Genesis chain, no wrapping tx | `YouTrackDBInternalEmbedded.createStorage:706-771` → `internalCreate:773-777` → `DatabaseSessionEmbedded.internalCreate:572-586` → `createMetadata:598-603` → `SharedContext.create:182-221` | ✓ |
| Schema root shell = empty entity | `SchemaShared.create:1387-1398` (`computeInTx(newInternalInstance)`, then `setSchemaRecordId`) | ✓ |
| `copyForTx` bootstrap assert | `SchemaShared.java:300-301` (`globalProperties != null`) | ✓ |
| Empty-root parse branch | `SchemaShared.fromStream:886-894` (error-log "Database's schema is empty!" and return); version gate `:895-904` (Track 2 redirect) | ✓ |
| IM root shell | `IndexManagerEmbedded.create:608-621` | ✓ |
| Security create guard/skip/txes | `SecurityShared.create:594-626` (guard `:595-597`, system-DB skip `:614`), `createDefaultRoles:628-636` (own `executeInTx`), `createDefaultUsers:637-655` (own `computeInTx`, honors `CREATE_DEFAULT_USERS`); `OUser.name` UNIQUE `:899` | ✓ |
| `internal` collection inside create atomic op | `AbstractStorage.doCreate:1442-1526`, `executeInsideAtomicOperation:1493`, `doAddCollection:1510` | ✓ |
| `doCreateCollection` WAL-revert contract | `AbstractStorage.java:7035-7062` (javadoc: "Everything this method touches is buffered as WAL-reverted intent") | ✓ |
| Blob loop + readers | `SharedContext.create:198-204`; `DatabaseSessionEmbedded.addCollection:3023`, `storage.addCollection:1668`; `getBlobCollectionIds:5028` (schema-only); `SchemaProxy.addBlobCollection:460-462`; root payload `SchemaShared.toStream:1243-1244`; root-diff `rootPayloadDiffersFrom:1307-1315` (includes `blobCollections`) | ✓ |
| `EXPORTER_VERSION = 14` | `DatabaseExport.java:59` | ✓ |
| Promote-on-failure | `exportDatabase:126-161` (`close()` in `finally` at `:158`); `close():270-301` renames unconditionally at `:291` | ✓ |
| Scan-failure swallow | `DatabaseExport.java:212-239` (only `YTIOException` rethrows at `:212-220`; generic `Exception` logged at `:221-239` and loop continues) | ✓ |
| Partial JSON on mid-render throw | `exportRecord:582-631` (`recordToJson` into the shared `jsonGenerator` at `:587`; catch swallows, export continues) | ✓ |
| No fsync in rename helper | `FileUtils.atomicMoveWithFallback:306-320` (`Files.move` only) | ✓ |
| Import plain-JSON fallback | `DatabaseImport` ctor `:136-143` (`catch (Exception) → reset → plain stream`) | ✓ |
| `importInfo` reads only `exporter-version` | `DatabaseImport.importInfo:406-424` | ✓ |
| Section loop tolerates missing sections | `importDatabase:226-243` | ✓ |
| `removeDefaultCollections` → `security.create` mid-import | `DatabaseImport:386-404` (call sites `importSchema:497` and `importCollections:848`) | ✓ |
| Streaming variants | `DatabaseExport` OutputStream ctor `:103-113` (`tempFileName == null`; `close():287` skips rename); `DatabaseImport` InputStream ctor `:148-156` | ✓ |

One draft citation is imprecise but immaterial: "`schema.addBlobCollection`
(`SchemaShared.java:1243-1244`)" — `:1243-1244` is the root-payload *serialization* of
`blobCollections` in `toStream`; the mutator itself is `SchemaShared.addBlobCollection:1598` /
`SchemaProxy:460-462`. The draft's semantic claim (root-payload EMBEDDEDSET picked up by the
root-diff) is correct.

## 2. Draft G — crash-point enumeration of the designed genesis sequence

Designed sequence (per G2.a/G2.b/G2.c + Q-G1/Q-G2 rulings):
storage `doCreate` (one WAL atomic op, now including the `$blob*` loop) → session
`internalCreate` (serializer + strict-SQL storage-config writes) → `SchemaShared.create`
(bootstrap-valid root via `computeInTx`, then `setSchemaRecordId`) → `IndexManagerEmbedded.create`
(IM root tx, then `setIndexMgrRecordId`) → **phase 1** (ONE schema tx: security DDL, sibling
creators, O/V/E, blob registration) → **phase 2** (ONE data tx: default roles + users).

Enumerated crash points (process kill / power loss), each traced:

| # | Crash point | On-disk state at reopen | Detected? | Verdict |
|---|---|---|---|---|
| W0 | before `preCreateSteps` (`DiskStorage:520`) | directory without `dirty.fl`/config markers | `DiskStorage.exists:798-816` → false → clean re-create | benign — checked |
| W1 | inside the create atomic op, before `clearStorageDirty` (`:1524`) | `dirty.fl` dirty → reopen replays WAL (`recoverIfNeeded:6734-6760`) → create op rolled back → configuration absent → open fails loudly | loud open failure, but `exists()` = true blocks re-create; **no discard** | CS34 |
| W2 | after `clearStorageDirty` (durable — `StorageStartupMetadata` writes via `SYNC` channel, `:88-95`, `clearDirty:335-350`) but before the op's `commitChanges` WAL write is durable (`AtomicOperationsManager.endAtomicOperation:310-406` runs *after* the lambda) | dirty flag **clean**, WAL commit record possibly absent, data pages unflushed (default `synch()` at `AbstractStorage:1430-1435` runs later still) | reopen **skips recovery** (`recoverIfNeeded:6735` keys on `isDirty()`); open fails on missing/torn configuration — loud but un-recovered, "clean-flagged corpse" | CS37 (claim accuracy), CS34 (no discard) |
| W3 | after storage create durable, before schema-root tx | storage opens; `getSchemaRecordId` unset → `SchemaShared.load:1370-1375` throws `SchemaNotCreatedException` | loud | checked; CS34 (no discard) |
| W4 | after schema-root tx commit, before `setSchemaRecordId`'s own atomic op (`SchemaShared:1391-1393`; `AbstractStorage.setSchemaRecordId:8088-8100` is a **separate** atomic op) | orphan root record; pointer unset | same as W3 — loud | checked; feeds CS36 |
| W5 | after schema root + pointer, before IM root / pointer (`IndexManagerEmbedded:608-621` — same two-step shape) | IM pointer unset → IM load fails | loud [inferred — symmetric to W3] | checked; feeds CS36 |
| W6 | after both root shells, before phase-1 commit | **DB opens cleanly** post-G2.a: bootstrap-valid schema parses silently (the `fromStream:886-894` breadcrumb no longer fires), `SecurityShared.load:1044-1076` skips everything (`OUser` class null), `setupPredicateSecurity:1078-1101` even **creates `OSecurityPolicy` on open**; zero classes, zero users | **silent** — authenticated open later fails with a credentials-shaped error; `openNoAuthorization` succeeds | CS34 + CS35 |
| W7 | after phase-1 commit, before phase-2 commit | DB opens cleanly with full internal schema and **zero users**; `security.create` guard (`:595`) would no-op if ever called | **silent** — same misleading failure shape | CS34 + CS35 |
| W8 | between roles tx and users tx | eliminated by Q-G2's single merged data tx | closed by design — checked |
| W9 | after phase-2 commit, before `callOnCreateListeners` | complete DB; listeners are runtime-only effects | benign — checked |

Phase-1 *abort* (exception, not crash) additionally leaves the failed storage object registered in
the `storages` map (`createStorage:744` puts before `internalCreate:745`; the `catch` at `:750-755`
only wraps and rethrows) with `status = OPEN` (set inside the create lambda at `:1506`) — the
half-created DB is live in-process, on disk, and `create(name, failIfExists=false)` subsequently
logs "**Database '%s' already exists, nothing to do**" (`:760-766`) and returns success.

### Findings — Draft G

---

**CS34 — blocker — FM-G3/FM-G5's "half-created genesis DB is discarded" is neither enforced nor
detectable; create-retry silently no-ops.**
Design §G.3 FM-G3 ("A failed create propagates and the DB is discarded, not reopened
*[inferred]*"), FM-G5 ("DB is a discarded half-create anyway; benign"), test-pin G.5 #9.
Code: `YouTrackDBInternalEmbedded.createStorage:706-771`, `exists:798-816` (via
`DiskStorage.exists:798` keyed on `dirty.fl`, created by `preCreateSteps` at `DiskStorage:520`
*before* the atomic op), `SecurityShared.load:1044-1076`, `SchemaShared.load:1366-1381`.
The inference the design carries is **false at HEAD**: (a) the exception path performs no cleanup
— the storage stays on disk and in the `storages` map; (b) every post-`preCreateSteps` crash state
makes `exists()` return true, so `create(failIfExists=true)` throws "already exists" and
`create(failIfExists=false)` **silently succeeds without doing anything** (`:763-766`); (c) reopen
of the W6/W7 states succeeds with zero/partial metadata and no signal (see CS35). Counterexample:
kill -9 between phase-1 and phase-2 commits; operator's app runs the standard
create-if-missing-then-open sequence → create no-ops, open succeeds unauthenticated or fails with
a credentials-shaped error; nothing anywhere says "genesis incomplete, discard me". The design
must either (i) specify an enforcement mechanism — cleanup-on-exception in `createStorage` plus an
open-time genesis-completion check for the crash path (e.g., a completion marker written by
phase 2, or "internal metadata present but zero `OUser` rows on a non-system DB ⇒ refuse open with
a discard-and-recreate message") — or (ii) explicitly downgrade FM-G3/FM-G5 to "manual-discard
protocol, silent-acceptance windows W6/W7 documented", and re-scope test #9 to pin the true
behavior (including the `failIfExists=false` silent no-op). As drafted, decomposition would build
tests against a protocol that does not exist.

---

**CS35 — should-fix — G2.a deletes the only open-time breadcrumb for a pre-phase-1 root and adds
no replacement completeness signal (new-in-design detectability regression).**
Design §G2.a; code `SchemaShared.fromStream:886-894`.
Today an empty root shell at reopen at least error-logs "Database's schema is empty!…double check
the integrity of the database". After G2.a the bootstrap-valid payload (`schemaVersion` 6, empty
sets) parses **silently** — states W6/W7 reopen with zero log output; `SecurityShared.load` even
mutates the corpse (creates `OSecurityPolicy` via `setupPredicateSecurity:1078-1101`).
Counterexample: crash at W6; reopen; no log line distinguishes this DB from a healthy empty one.
The design should pair the bootstrap payload with an explicit signal — at minimum a WARN when a
non-system DB opens with a bootstrap-empty schema (no classes) or with classes but no users;
ideally the CS34 completion marker. Otherwise G2.a strictly worsens post-crash diagnosability
relative to HEAD.

---

**CS36 — should-fix — FM-G3's "three coarse states" undercounts the designed sequence's crash
states; test #9's enumeration will be built on the wrong state list.**
Design §G.3 FM-G3; code: `DatabaseSessionEmbedded.internalCreate:573-574` (serializer +
strict-SQL storage-config writes, each its own durability event), `SchemaShared.create:1388-1393`
(root tx **then** separate `setSchemaRecordId` atomic op, `AbstractStorage:8088-8100`),
`IndexManagerEmbedded.create:608-621` (same two-step shape).
The restructured genesis has at least **six** distinguishable post-crash states
(W1/W2 · W3 · W4 · W5 · W6 · W7), not three: the root-shell creations are independent commits that
the design explicitly keeps ("the write stays inside `create`'s existing `computeInTx`"), and each
root has a separate config-pointer atomic op. This does not create new unsafety (each pre-phase-1
state fails or opens per the table above), but the design text and test-pin #9 should enumerate
the real states — especially W4/W5 (root record committed, pointer not), which have a different
failure signature (`SchemaNotCreatedException`) than W6/W7 (silent open). Optional hardening worth
recording as considered: fold `setSchemaRecordId`/`setIndexMgrRecordId` next to the storage-create
atomic op or make the root-shell + pointer a single unit — but that is scope the design may
legitimately decline; the mandatory part is the honest enumeration.

---

**CS37 — suggestion — FM-G4's "[verified mechanism] — WAL-reverted on a crashed create" is
overbroad: `clearStorageDirty()` runs inside the create lambda, durably, before the atomic op's
WAL commit exists.**
Design §G2.b/FM-G4; code: `AbstractStorage.doCreate:1524` (`clearStorageDirty` inside the lambda),
`DiskStorage.clearStorageDirty:616-620` → `StorageStartupMetadata.clearDirty:335-350` over a
`SYNC`-opened channel (`:88-95`) — the clean flag is durable the moment it is written;
`AtomicOperationsManager.executeInsideAtomicOperation:192-217` — `endAtomicOperation` (WAL
`commitChanges`, `:370-400`) runs only after the lambda returns; `recoverIfNeeded:6735` keys
recovery solely on `isDirty()`.
Window W2: crash after the durable flag-clear but before the WAL commit record (and before the
follow-up `synch()` at `:1430-1435`, default-on via `STORAGE_MAKE_FULL_CHECKPOINT_AFTER_CREATE`,
`GlobalConfiguration:209-213`, but configurable off) leaves a clean-flagged storage whose create
op is neither committed nor **revertible** — reopen skips WAL replay entirely. The outcome is
still a loud open failure on the missing configuration (fail-safe in effect), so this is not a new
hole, and the blob loop inherits exactly the internal-collection envelope — but the design's
mechanism claim should be corrected to: "any crash before `create()` returns leaves a storage that
either WAL-rolls-back (dirty window) or fails configuration load (clean-flag window); in both
cases unusable and requiring discard". Pre-existing; not widened by R3.

### Draft G — null verdicts

- **N-G1 (charter q1: WAL replay composes with the blob loop): no counterexample.** The blob
  creations ride the same atomic op as the `internal` collection under the
  `doCreateCollection:7035-7062` buffered-intent contract; recovery treats them identically. A
  crash that leaves partial blob files also leaves the storage unable to pass configuration load
  (config is created in the same op), so no reachable state exposes a partial blob set to a
  reader; and readers resolve blob ids only through the schema registry
  (`getBlobCollectionIds:5028`, `rootPayloadDiffersFrom:1307-1315` carries the set in the phase-1
  root diff), which commits atomically with phase 1. FM-G5's *benign* verdict is confirmed
  (modulo CS34's framing of "discarded").
- **N-G2 (register-only rewrite loses registrations): no counterexample.** Registration is pure
  root payload; a phase-1 crash loses the registration together with the entire schema tx — there
  is no state with a registered-but-uncommitted or committed-but-unregistered blob id, because the
  physical collections pre-exist from storage birth and the set rides the single root record.
- **N-G3 (import call-site nesting, FM-G7): nothing new found.** `security.create` at
  `DatabaseImport:404` is guard-first at HEAD (`SecurityShared:595-597`); Track 4's
  link-consistency save/restore is on the record (track-4.md, iteration 1, third bullet). The
  design's guard-first + nesting-compat requirement matches the code.

## 3. Draft M — export failure-path ordering

Designed export order (M2.a): sections → manifest last → completion flag → `close()` promotes only
when flagged → fsync dump file → rename via new fsync-capable move. Crash points:

| # | Crash point | Resulting state | Verdict |
|---|---|---|---|
| E1 | at/after ctor, before first section | `.tmp` partial; **final-name file already deleted by the ctor** (`DatabaseExport:85`) | CS41 |
| E2 | mid-section / mid-record | `.tmp` partial, no manifest, no flag → never promoted | closed by M2.a-4 — checked |
| E3 | during manifest write | same as E2 | checked |
| E4 | after flag set, before fsync completes | `.tmp` complete-but-possibly-torn; not promoted | fail-safe — checked |
| E5 | after fsync, before rename | durable `.tmp`, nothing at final name | fail-safe — checked |
| E6 | after rename, before the **directory entry** is durable | file may revert to `.tmp` name after power loss although the tool already exited 0 | CS40 |
| E7 | exception paths (abort, close-secondary) | flag unset → no promote; suppressed-attachment per M2.a-6 | closed — checked |

The cited HEAD defects were all re-verified as real (FM-M1 `:212-239` + `:158`; FM-M2 `:587`;
FM-M3 `:291`; FM-M4 `FileUtils:306-320`; FM-M5 Java `finally` semantics at `:158` with `close()`
throwing at `:274-284`/`:292-299`). The completion-flag + manifest-last recipe genuinely closes
FM-M1/M2/M3 for both variants and FM-M4's truncation half — with the two gaps below.

---

**CS40 — should-fix — the durable-promote recipe omits the directory fsync after rename (and does
not say how a closed file gets fsynced), so FM-M4 is only half-closed.**
Design §M2.a-5 ("flush + close → fsync the dump file → rename"); §M.6 (`FileUtils` fsync-capable
variant); code `FileUtils.atomicMoveWithFallback:306-320`.
POSIX durability of a rename requires fsyncing the **containing directory** after the rename; the
draft's recipe ends at the rename. Counterexample (E6): export completes, tool reports success
(exit 0 — the operator's documented gate per design.md §Edge cases), power loss before the
directory entry is durable → after reboot the dump exists only at `<name>.gz.tmp` or, per
design.md's operator procedure, "no file at the final name proves the export failed" — a **false
failure signal for a succeeded export** (fail-safe direction, but it breaks the exit-status
contract the design itself leans on). The fsync-capable move must be specified as: fsync source
file (via a reopened `FileChannel.force(true)` — the gzip stream is already closed by then, which
the draft also leaves implicit) → `ATOMIC_MOVE` rename → fsync parent directory. The fallback arm
(non-atomic `Files.move`) should be specified as fail-closed for the migration path rather than
silently degrading (a copy-fallback reintroduces the torn-final-name state FM-M4 closes).

---

**CS41 — should-fix — the constructor deletes the previous final-name dump before exporting
(`DatabaseExport:85` `prepareForFileCreationOrReplacement` → `Files.deleteIfExists`), so a failed
v15 export destroys the operator's last good dump; the design keeps this and its test wording
would enshrine it.**
Design §M2.a-4/5, §M.4 I-migration-failfast ("no file at the final name"), test-pin M.5 #1.
The whole point of tmp+rename is that the old artifact survives until the new one is complete; the
HEAD ctor defeats it up front, and the draft's promote rewire never mentions removing the upfront
delete. Counterexample: operator has yesterday's good `dump.gz`; today's export hits an injected
scan failure (FM-M1); under the new design nothing is promoted — but `dump.gz` was already deleted
at construction, so the failure leaves the operator with **no dump at all**, which the migration
runbook (export old binaries → import new) turns into a lost migration vehicle if the source DB is
subsequently damaged. Fix in design: drop the final-name upfront delete, use a REPLACE_EXISTING-
capable atomic move at promote time (plain `ATOMIC_MOVE` onto an existing target is
implementation-specific in the JDK — must be pinned), and reword I-migration-failfast/test #1 from
"no file at the final name" to "final name untouched (pre-existing dump preserved); nothing
promoted".

---

**CS-null (export): M2.a-6 primary-exception preservation, spill-file lifecycle, and the streaming
promote no-op need no findings** — the mechanisms as drafted match the code shapes they must
replace (`close():287` already skips rename for `tempFileName == null`; Jackson's
`AUTO_CLOSE_JSON_CONTENT` default means a failure-path `close()` can emit well-formed JSON, which
is exactly why the manifest-absence check is the right streaming completion marker — see CS42 for
the one residual streaming hole).

## 4. Draft M — import fail-closed composition

Real flow at HEAD (`importDatabase:202-262`): security check → `readNext(BEGIN_OBJECT)` →
`setValidationEnabled(false)` + `setUser(null)` (`:211-212`) → **`removeDefaultNonSecurityClasses()`
(`:214`)** → index-manager reload + auto-index collection (`:215-222`) → section loop (`:226-243`,
`importInfo` at `:230` is the first place `exporter-version` can be learned) → rebuild/reload/synch
(`:244-253`) → RID-map removal (`:256-258`).

`removeDefaultNonSecurityClasses` (`:427-470`) **drops every non-security class and its indexes**
in the target — committed legacy DDL — before the importer knows the dump's version. On a fresh
target this still drops O/V/E, `OFunction`, `OSequence`, `OSchedule`.

---

**CS38 — blocker — the import preamble mutates the target before any v15 rejection can fire,
violating the Q-M2 ruling's letter ("All rejections throw BEFORE any mutation of the target
database"); the design specifies no reordering.**
Design §M2.b (all five arms), Rulings §Q-M2; code `DatabaseImport.importDatabase:214` vs `:230`,
`removeDefaultNonSecurityClasses:427-470`.
Every strict-matrix rejection the rulings enumerate — `>= 16` reject-with-redirect,
`schema-version` range check, mandatory-field/malformed-info rejection, plain-JSON-fallback
rejection ("necessarily post-info-parse", M2.b-1), best-effort ack gate — is evaluated at or after
`importInfo`, i.e. **after** `:214` has already dropped classes and indexes in the target.
Counterexample: operator points a v16 dump at a prepared target; import drops O/V/E +
function/sequence/scheduler classes and their indexes, *then* throws the redirect. The rejected
import has mutated the target, in direct contradiction of the ruled invariant mapping
(I-migration-isolation / I-migration-failfast). The design must specify the mechanism: defer the
preamble mutations (and the auto-index snapshot at `:216-222`) until after the info section is
parsed and the strict matrix has passed — including defining behavior when the first tag is not
`info` (v14 and v15 exporters both write `info` first, `DatabaseExport.exportDatabase:136`, so
requiring info-first for `>= 15` is compatible; the `< 15` path can keep today's order or defer
identically — deferral is behavior-preserving because nothing between `:214` and the first section
consumes the dropped classes). Without this, M2.b implements the checks but cannot satisfy the
ruling.

---

**CS39 — should-fix — the post-loop rejections (manifest verify, section presence, gzip
full-consumption) are structurally post-mutation; the design must scope the "rejections before
mutation" guarantee so the ruled property is not claimed falsely.**
Design §M2.b-2/3 ("After the section loop, a v15 import hard-fails…"), §M.4, Rulings §Q-M2 final
sentence; code `importDatabase:226-243` (sections import as they stream — records are in the
target before the manifest is ever reachable).
No reordering can fix this class: count verification and stream-consumption verification are
by nature end-of-stream. A v15 import rejected on manifest mismatch or trailing garbage has fully
mutated the target. This is acceptable **only** under design.md's posture ("the documented
procedure keeps the target out of service until verification passes") — but the draft and the
Q-M2 ruling sentence, read together, over-promise. The design should split the contract
explicitly: (a) *pre-flight rejections* (info matrix, framing, ack gate) precede all mutation
(after CS38's reordering); (b) *structural verification failures* (manifest, section presence,
consumption, mid-stream parse/inflate errors) fail loudly and **condemn the target** — the target
must be discarded, never returned to service. Alternatively record the considered-and-rejected
option: a two-pass import (pass 1 streams and validates the entire dump — framing, sections,
manifest, consumption — with zero writes; pass 2 imports), which would make even structural
failures pre-mutation at the cost of a second decompression pass. Either resolution is fine; the
current ambiguity is not, because test authors will otherwise assert the ruled sentence literally
and either write an unsatisfiable test or weaken it silently.

---

**CS42 — should-fix — the R1/Q-M2 version dispatch has no branch for "exporter-version never
declared", and the strict matrix's data-driven arming therefore has a silent-acceptance hole
(concretely reachable via the streaming variant).**
Design §0 R1, §M2.b intro, Rulings §Q-M2(1) (`<= 14` / `== 15` / `>= 16` — nothing else); code
`DatabaseImport:112` (`exporterVersion = -1` initial), `importInfo:406-424` (set only if the field
is seen), `importDatabase:226-243` (a dump with no `info` section, or none reaching it, imports on
the lenient path with `exporterVersion == -1`).
A dump that never declares a version falls into the `<= 14` lenient bucket **implicitly** — the
strict section-presence check (which would catch the missing info section!) never arms, because
arming requires the version the missing section carries. Bootstrapping hole. Crash-reachable
instance: a v15 **streaming** export (no promote gate — M2.a-4 says the manifest is the streaming
completion marker) that dies before/inside `exportInfo`; the failure-path `close()` still
finalizes the JSON (`writeEndObject` + Jackson auto-close), so the consumer can receive a
well-formed, section-less or info-less document that the importer accepts leniently — in the
empty-`{}` shape plausibly as a **silent no-op "successful" import** [inferred — exact JSONReader
behavior on `{}` needs the red test; the dangling-field shapes fail loudly on parse regardless of
version]. File-path dumps are protected by the promote gate, so the exposure is the streaming
variant plus hand-damaged dumps. Within R1 (no separate migration flag), the design must still
*assign* the no-version case explicitly. Recommended assignment: "a dump that reaches end of
stream without having declared `exporter-version` is rejected" — this does not key on v15 and
does not change behavior for any real legacy dump (every exporter since the legacy era writes
`info` first), but it does alter the abstract lenient path, so it needs its own one-line ruling
rather than silent adoption; the fallback is documenting the hole as accepted residual risk with
the streaming-variant caveat attached to FM-M13.

---

**CS43 — should-fix — the gzip full-consumption check is under-specified: the inflater arithmetic
is sound only after the decompressed stream has been driven to inflater-finished, and the draft's
"exhaustion probes are forbidden" phrasing forbids the wrong thing if read literally.**
Design §M2.b-2, design.md §"Schema-format migration" gotcha, track-8.md §Signatures; JDK
mechanics: `InflaterInputStream` buffers raw input ahead of consumption; `Inflater.getBytesRead()`
counts only deflate bytes actually consumed; `GZIPInputStream` verifies CRC32+ISIZE only on the
read that reaches deflate-finish, and *then* probes for a concatenated member (the residue-eating
behavior the gotcha rightly bans).
Two failure shapes if implemented as literally drafted: (a) the JSON layer
(`JSONReader`/`InputStreamReader`) may stop reading after the closing brace without having
consumed the final deflate block — `getBytesRead()` is then short of the deflate stream, the
arithmetic mismatches, and a **valid dump is rejected** (breaking the migration vehicle itself);
(b) an implementer who takes "probes are forbidden" to mean "never read after the parse" ships a
check that never confirms `finished()`/trailer at all. The design should pin the sequence: the
subclass **disables multi-member continuation** (this is what makes draining safe); after the
manifest parses, the importer performs a controlled drain of the *decompressed* stream to EOF
(reads return -1 after the subclass verifies the trailer without probing for a next member),
asserts `inflater.finished()`, and only then applies `headerLen + getBytesRead() + 8 ==
physicalSize` for seekable sources. The forbidden thing is the raw-stream exhaustion probe /
JDK's member-concatenation probe, not the decompressed-side drain. With that pinned, truncation
detection is layered and sound: truncated deflate → EOF/inflate exception during parse; truncated
or corrupt trailer → subclass trailer verification; trailing garbage → arithmetic; content
corruption → CRC32.

---

**CS44 — suggestion — a crash mid-import leaves an openable, half-imported target with no in-DB
marker; the design relies wholly on operator procedure and never names the operator-visible
signal.**
Design §M.3 (no FM row for mid-import crash), design.md ("a crash mid-export or mid-import always
surfaces as a loud verification failure that blocks the target from returning to service" — the
*import*-side half of that sentence has no in-DB mechanism behind it); code: sections import as
committed legacy ops (`importDatabase:226-243`), `removeExportImportRIDsMap:344-353` runs last, so
the `___exportImportRIDMap` class's presence is an *incidental* incompleteness marker that only
exists once records have begun importing.
Not a defect against the rulings (D20's posture is operator-driven), but the design should name
the signal the "documented procedure" checks — e.g., "an import is complete only if the importer
process exited 0; a target whose import did not exit 0 is condemned", optionally hardened by the
RID-map-class heuristic or an explicit import-in-progress marker written first and cleared last.
Otherwise the design.md sentence quoted above is an unbacked claim on the import side.

### Draft M — null verdicts

- **N-M1 (completion-flag gate, file variant): no counterexample.** Every crash point E1–E5
  leaves the flag unset or the rename unexecuted; the failure path never renames. The gate as
  designed closes FM-M1/M3 for the file ctor.
- **N-M2 (manifest-as-streaming-marker): no counterexample within v15-declared streams.** Any
  streamed dump that carries `exporter-version >= 15` and is missing the manifest (including the
  auto-closed failure-path shapes) is refused by M2.b-3. The residual hole is exactly the
  never-declared case — carved out as CS42, not a second finding.
- **N-M3 (v14 regression risk, FM-M12): no counterexample.** Strictness arms only on declared
  `>= 15` (with CS42's caveat that "undeclared" must be assigned); the `< 15` code path is
  untouched by the design's own scope statement.
- **N-M4 (schema `version` slot rewrite, M2.a-7): no counterexample.** Import skips the field
  (`importSchema:503-508` reads and discards), so writing `CURRENT_VERSION_NUMBER` instead of the
  generation token is consumer-invisible, as claimed.

## 5. Hypothesis log

| Hypothesis | Evidence | Outcome |
|---|---|---|
| H1: failed/crashed genesis is discarded automatically somewhere above `createStorage` | `createStorage:750-755` wraps+rethrows only; no `drop`/`deleteFilesFromDisc` on any create-failure path; `exists()` true from `preCreateSteps` onward | **refuted** → CS34 |
| H2: reopen detects a userless/classless DB | `SecurityShared.load:1044-1076` (no user check; DDL-on-open instead), `SchemaShared.fromStream` post-G2.a parses silently | **refuted** → CS34/CS35 |
| H3: the create atomic op is WAL-revertible across its whole extent | `clearStorageDirty` at `:1524` inside the lambda, durable via SYNC channel; `endAtomicOperation` after lambda; recovery keyed on `isDirty()` at `:6735` | **partially refuted** (clean-flag window) → CS37 |
| H4: blob loop adds a new crash window vs the `internal` collection | same atomic op, same `doCreateCollection` contract (`:7035-7062`) | **refuted** → N-G1 |
| H5: all v15 import rejections can precede target mutation as ruled | `removeDefaultNonSecurityClasses` at `:214` before `importInfo` at `:230`; manifest/consumption checks post-loop by nature | **refuted twice** → CS38 (fixable by reordering), CS39 (inherent, needs contract scoping) |
| H6: fsync(file)+rename suffices for the exit-0 durability contract | POSIX rename durability requires parent-dir fsync; draft recipe ends at rename | **refuted** → CS40 |
| H7: the promote gate preserves the operator's previous dump on failure | ctor deletes final name at `DatabaseExport:85` | **refuted** → CS41 |
| H8: strict matrix arms for every v15-produced dump | arming requires the declared version; undeclared-version dumps (streaming crash shapes) fall to lenient | **refuted** → CS42 |
| H9: inflater arithmetic as drafted detects truncation and only truncation | sound only after drain-to-finished; literal reading risks false rejection or unarmed check | **partially refuted** → CS43 |
| H10: draft's HEAD-defect citations (FM-M1..M5) might be stale/wrong | all re-verified line-by-line (§1) | **refuted** (citations correct) |

**Alternative-hypothesis check before finalizing:** for CS34/CS38 (the two blockers) I looked for
any layer that could rescue the design as written — a server-side create wrapper with cleanup, a
`SystemDatabase`-style recreate-on-open, an import "merge=false" precondition that empties the
target *before* the tool runs (there is none: the tool itself is the emptier, which is exactly the
problem), and a possible reading of Q-M2's "All rejections" as scoped to info-field rejections
only. The last is textually arguable, but the ruling sentence cites I-migration-isolation and
I-migration-failfast in full, and CS39 shows even the narrow reading needs the preamble reordering
for the info-field rejections themselves — so the findings stand under either reading.

## 6. Summary for triage

Two blockers, both with narrow, design-level remedies: CS34 (state the real genesis
discard/detection protocol and mechanism — the drafted one does not exist) and CS38 (specify the
import-preamble deferral that makes the ruled rejections-before-mutation true). Six should-fixes
tighten claims or complete recipes without changing the agreed architecture (CS35, CS36, CS39,
CS40, CS41, CS42, CS43 — CS43 arguably the most implementation-consequential since a wrong reading
breaks valid-dump import). Two suggestions (CS37, CS44) are claim-accuracy/documentation items.
No ruling is re-litigated; every remedy proposed is compatible with R1–R4 and the 2026-07-23
rulings, except that CS42's recommended fix (reject undeclared-version dumps) needs a one-line
supplementary ruling because it touches the abstract lenient path.

---

## Findings index

| ID | Severity | Design § | Code anchor | Summary |
|---|---|---|---|---|
| CS34 | blocker | G.3 FM-G3/FM-G5, G.5 #9 | `YouTrackDBInternalEmbedded.java:744-766`, `DiskStorage.java:520,798`, `SecurityShared.java:1044` | "half-created genesis DB is discarded" is unenforced and undetectable; create-retry silently no-ops |
| CS35 | should-fix | G2.a | `SchemaShared.java:886-894` | bootstrap payload silences the only open-time empty-schema breadcrumb; no completeness signal replaces it |
| CS36 | should-fix | G.3 FM-G3 | `SchemaShared.java:1388-1393`, `AbstractStorage.java:8088-8100`, `IndexManagerEmbedded.java:608-621`, `DatabaseSessionEmbedded.java:573-574` | "three coarse states" undercounts (≥6); test #9 needs the true enumeration |
| CS37 | suggestion | G2.b FM-G4 | `AbstractStorage.java:1524`, `StorageStartupMetadata.java:88-95,335-350`, `AtomicOperationsManager.java:192-217` | clean-flag-before-WAL-commit window makes "WAL-reverted on crashed create" overbroad (pre-existing, inherited) |
| CS38 | blocker | M2.b, Rulings Q-M2 | `DatabaseImport.java:214` vs `:230`, `:427-470` | preamble drops classes/indexes before info validation; every ruled rejection fires post-mutation; no reordering specified |
| CS39 | should-fix | M2.b-2/3, M.4 | `DatabaseImport.java:226-243` | manifest/section/consumption rejections are inherently post-mutation; scope the ruled guarantee or adopt two-pass |
| CS40 | should-fix | M2.a-5, M.6 | `FileUtils.java:306-320` | durable-promote recipe missing parent-directory fsync (and closed-file fsync mechanism); exit-0 dump can vanish |
| CS41 | should-fix | M2.a-4/5, M.5 #1 | `DatabaseExport.java:85` | ctor deletes the previous final-name dump upfront; failed export destroys last good dump; needs REPLACE_EXISTING promote |
| CS42 | should-fix | §0 R1, Rulings Q-M2(1) | `DatabaseImport.java:112,406-424` | no dispatch branch for undeclared exporter-version; strict matrix never arms; streaming crash shapes import leniently/silently |
| CS43 | should-fix | M2.b-2 | JDK `GZIPInputStream`/`Inflater` semantics; `DatabaseImport.java:136-143` | consumption check needs drain-to-finished + finished() assert; literal "no probes" reading yields false rejections or unarmed check |
| CS44 | suggestion | M.3 (absent row), design.md Part 4 | `DatabaseImport.java:226-243,344-353` | mid-import crash leaves unmarked half-imported target; name the operator-visible completion signal |
