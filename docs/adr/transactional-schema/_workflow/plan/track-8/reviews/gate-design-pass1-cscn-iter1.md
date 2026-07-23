# Track 8 gate verification — pass-1 CS/CN amendments (iteration 1)

**Role:** gate, not re-review. Verifies that the amendments in commit `0a7fc377fa` ("Amend Track 8
design per adversarial pass-1 triage") resolve the durability (CS34–CS44) and concurrency
(CN48–CN53) pass-1 findings AS A DESIGN — i.e., the amended design now correctly specifies each
approved remedy; implementation happens later.
**Artifacts read in full:** the amended
`docs/adr/transactional-schema/_workflow/plan/track-8-design-drafts.md` (811 lines, at HEAD),
`track-8/reviews/adversarial-durability-pass1.md`, `track-8/reviews/adversarial-concurrency-pass1.md`,
and `git show 0a7fc377fa` (the exact amendment diff, 373+/60−, single file).
**HEAD:** `0a7fc377fa`, branch `transactional-schema`. **Mode:** read-only; no Maven; no file
modification outside this report.

---

## 0. Gate criteria (stated before any verdict)

A finding is **VERIFIED** only when all of the following hold:

- **G1 — Traceability.** The amendment names the finding, tags the in-body edits
  (`(pass-1 <ID>)`), and names any superseded sentence, per the design's own amendment protocol.
- **G2 — Remedy specification.** The design now specifies a mechanism or contract that, if
  implemented as written, removes the finding's concrete counterexample (or, for
  observation-class findings, carries the corrected claim). A paper acknowledgment without a
  mechanism fails G2.
- **G3 — Grounding accuracy.** Every code citation the amendment leans on is true at HEAD
  (re-traced, not trusted). The code tree at HEAD `0a7fc377fa` is byte-identical to the drafts'
  grounding commit `d664589d7f` (`git diff --stat d664589d7f..HEAD -- '*.java'` is empty; only
  `docs/` changed), so the drafts' line numbers remain live.
- **G4 — Consistency.** The amendment does not contradict the settled rulings (R1–R4,
  Q-G1–Q-G3, Q-M1–Q-M3) or another part of the amended design, and introduces no new hole. New
  holes are filed in §4 with the domain-continuing IDs (CS45+, CN54+).
- **G5 — Triage fidelity.** The amendment matches the user-approved disposition (adopted /
  folded / resolved-by-ruling / recorded / deferred) as stated in the task and in the design's
  disposition table.

**REJECTED** = the amendment fails G2/G3/G4 for the finding's core counterexample. **STILL
OPEN** = no amendment addresses the finding. **MOOT** = the finding's premise no longer holds.

## 1. Grounding re-verification (code anchors traced at HEAD)

Spot-verified every code claim the amendments rely on. All accurate:

| Amendment claim | Verified at HEAD |
|---|---|
| `storages.put` before `internalCreate`; catch wraps+rethrows, no cleanup; `failIfExists=false` → "already exists, nothing to do" and return | `YouTrackDBInternalEmbedded.createStorage` — put at `:744`, `internalCreate` `:745`, catch `:750-755`, else-branch info-log + return `:763-766` ✓ |
| `getOrCreateSharedContext` maps the context (residue on failure) | `:779-787` ✓ |
| Empty-root breadcrumb the bootstrap payload silences | `SchemaShared.fromStream:887-894` ("Database's schema is empty!") ✓; version gate `:895-904` ✓ |
| `copyForTx` bootstrap assert | `SchemaShared.java:300-301` ✓ |
| `setSchemaRecordId` is a separate atomic op (W4) | `AbstractStorage.java:8088-8100` (`executeInsideAtomicOperation`) ✓ |
| IM root same two-step shape (W5) | `IndexManagerEmbedded.create:608-621` (root `computeInTxInternal` then `setIndexMgrRecordId`) ✓ |
| `clearStorageDirty` inside the create lambda (W2/CS37) | `AbstractStorage.java:1524`, inside the `:1493` atomic op; `doAddCollection(internal)` at `:1510` ✓ |
| Recovery keyed solely on the dirty flag | `recoverIfNeeded:6734-6736` (`if (isDirty())`) ✓ |
| `SecurityShared.load` mutates on open (`OSecurityPolicy` created) | `setupPredicateSecurity:1078-1101` ✓ |
| Import preamble mutates before info | `DatabaseImport.importDatabase` — `removeDefaultNonSecurityClasses()` at `:214`, IM reload + auto-index loop `:215-221`, `importInfo` first reachable in the section loop at `:230` ✓ |
| `removeDefaultCollections` call sites | `importSchema:496-498`, `importCollections:847-849`; `security.create` at `:404` ✓ |
| `exporterVersion = -1` initial; plain-JSON fallback; info reads only `exporter-version` | `DatabaseImport.java:111`, ctor `:136-143`, `importInfo:407-424` ✓ |
| Raw blob-id mapping (A3 context) | `DatabaseImport.java:528-541` (`getCollectionNameById(collection)` on raw dump ids) ✓ |
| Upfront final-name delete | `DatabaseExport.java:85` `prepareForFileCreationOrReplacement(Paths.get(fileName)…)` → `FileUtils.java:283-287` `Files.deleteIfExists` ✓ |
| Fixed truncating `.tmp` | `DatabaseExport.java:87-91` (`fileName + ".tmp"`, plain `FileOutputStream`) ✓ |
| Rename helper has no fsync, silent copy fallback | `FileUtils.atomicMoveWithFallback:306-320` ✓ |
| Exporter writes `info` first, whole export in ONE tx | `DatabaseExport.exportDatabase:134-141` (`executeInTx` → `exportInfo()` first) ✓ |
| `ContextConfiguration.getValue` global mutable fallback | `ContextConfiguration.java:90-96` (`return iConfig.getValue()`) ✓ |
| Unpinned snapshot minting | `MetadataDefault.getImmutableSchemaSnapshot:137-145` (`schema.makeSnapshot()` when unpinned) ✓ |
| Live lazy-creator sites (CN49 correction) | `FunctionLibraryImpl.createFunction:137-139 → init:164-185` (incl. the `prop.getAllIndexes().isEmpty()` repair arm) ✓; `SequenceLibraryImpl.createSequence:103-123 → init` ✓; proxy `create()` (`FunctionLibraryProxy.java:52-55`, `SequenceLibraryProxy.java:70-73`) has zero production callers — repo grep finds only `SequenceLibraryProxyTest.java:207-208` ✓ |
| Genesis blob loop / schema-only readers (CN50 context) | `SharedContext.create:198-204` (count read from `storage.getContextConfiguration()`), `DatabaseSessionEmbedded.getBlobCollectionIds:5028-5031` ✓ |
| §A1 compatibility claim "dev-only exposure" | `CURRENT_VERSION_NUMBER = 6` (`SchemaShared.java:71`) was introduced by branch commit `5118a49903` ("Split schema into per-class records"), which is NOT an ancestor of `origin/develop` — every v6 database is branch-built, so marker-less v6 DBs are dev-only ✓ |
| design.md gzip gotcha exists | `_workflow/design.md:1031-1032` ("exhaustion probes are forbidden … dead decoder buffer") ✓ |

## 2. Verdicts — durability findings (CS34–CS44)

### CS34 (blocker) — genesis-failure containment — **VERIFIED**

**Approved remedy check.** All four required elements are present and specified, not merely
named:
1. *Cleanup-on-exception in `createStorage`* — §A1 mechanism 1: remove from
   `storages`/`sharedContexts`, close the storage, delete on-disk residue (disk profile),
   restoring `exists() == false`; explicitly kills the `create(failIfExists=false)` silent no-op
   (the `:763-766` counterexample).
2. *Open-time genesis-completion check* — §A1 mechanism 2: a storage-config marker written
   immediately after the phase-2 commit; semantics pinned correctly ("genesis ran to
   completion", NOT "users exist" — closing the system-DB / `CREATE_DEFAULT_USERS=false`
   false-positive the naive zero-users heuristic would have); open with internal metadata but no
   marker refuses loudly with the discard-and-recreate message. Compatibility claim verified true
   at HEAD (v6 is branch-only, §1 last-but-two row).
3. *FM-G3 rewritten* — the row now supersedes the false "three coarse states / discarded, not
   reopened [inferred]" claim by name and points to the W0–W9 enumeration (§A1 table, faithful
   to the durability report §2 table).
4. *Pin G.5 #9 rewritten* — (a) exception path asserts propagation AND cleanup including the
   `failIfExists=false` re-create; (b) crash path asserts W6/W7 refusal on
   `open`/`openNoAuthenticate`; (c) completed create opens with marker, both profiles.
5. *Footprint* — G.6 gains `YouTrackDBInternalEmbedded.java` with the exact change named; count
   updated 8-10.

**Counterexample closure traced.** Exception case: injected phase-2 failure → cleanup runs
inside the same `synchronized (this)` block (catch at `:750` is inside the monitor), so no other
open can interleave with the corpse before it is removed — the design's remedy composes with the
concurrency report's P1/P2 without a new window. Crash case: kill -9 between phases →
`exists()` remains true and `create(failIfExists=false)` still no-ops (cleanup cannot run after a
crash), but the very next `open` in the operator's create-if-missing-then-open sequence now
refuses loudly instead of silently minting a session — exactly the belt the finding demanded for
the crash path. FM-G5's "discarded anyway" is re-worded to "enforced, not assumed". G1–G5 all
hold. Two residual design seams found in the *amendment itself* — filed as CS45 (unenumerated
complete-but-unmarked crash state) and CN54 (drop-path composition), both suggestion-severity,
§4.

### CS35 (should-fix) — replacement completeness breadcrumb — **VERIFIED**

G2.a gains the "Completeness signal (pass-1 CS35…)" paragraph: it concedes the regression the
finding proved (G2.a alone silences `fromStream:887-894` and would *worsen* diagnosability) and
specifies the replacement — the §A1 open-time refusal, correctly characterized as "strictly
stronger than the breadcrumb it replaces" (the finding asked for at minimum a WARN; a loud
refusal subsumes it; W6/W7 go from silent-open to refusal per §A1 item 3). Fold disposition
matches triage. G1–G5 hold.

### CS36 (should-fix) — crash-state enumeration — **VERIFIED**

FM-G3 now references the W0–W9 table (10 rows ≥ the demanded ≥6 distinguishable states); the
table separates W4/W5 (pointer-op crash, `SchemaNotCreatedException`/IM-load-fail signature —
`AbstractStorage:8088-8100` and `IndexManagerEmbedded:608-621` verified two-step at HEAD) from
W6/W7 (silent-open signature), which is precisely the distinction the finding said test #9 needs.
Pin G.5 #9 is rewritten "against the §A1 W-states". The optional pointer-fold hardening is
recorded as considered-and-DECLINED (§A1 item 4) — the finding explicitly allowed declining it;
the mandatory part (honest enumeration) is delivered. The one honesty gap the amendment itself
introduces (W9 spans the pre-marker window) is filed as CS45, not held against this verdict since
the finding's own state list ended at the phase-2 commit.

### CS37 (suggestion) — clean-flag window claim accuracy — **VERIFIED**

Disposition = RECORDED, no code action — matching the finding's own framing (pre-existing,
inherited, not a new hole). The corrected mechanism claim is carried verbatim where the finding
asked: FM-G4 now reads "WAL-rolls-back OR fails configuration load, both
unusable-and-discarded", and the §A1 W2 row states "clean-flagged corpse: recovery skipped,
configuration load fails loudly (CS37) … mechanism claim corrected". `clearStorageDirty` inside
the lambda at `:1524` and `recoverIfNeeded:6735` keying re-verified. G1–G5 hold.

### CS38 (blocker) — import pre-flight deferral — **VERIFIED**

The amended M2.b intro ("Pre-flight ordering (pass-1 CS38 — blocker remedy)") defers ALL
enumerated preamble mutations — `:214`, the IM-reload/auto-index block `:216-222`, and
`removeDefaultCollections` via both call sites (`importSchema:497` / `importCollections:848`,
verified) — until after info-parse + the Q-M2 pre-flight matrix. The two supporting claims are
both true at HEAD: (a) every exporter writes `info` first (`exportDatabase:136` — verified,
`exportInfo()` is the first call in the tx), so requiring info-first for `>= 15` is compatible;
(b) deferral is behavior-preserving for `< 15` — traced: nothing between `:214` and the first
section consumes the dropped classes (the only intervening reads are the IM reload, the
auto-index tally — both in the deferred set — and the `:223` schema snapshot, see OBS-1 §5).
FM-M14 row + pin M.5 #15 added; SR1 scopes the structural remainder. This makes every ruled
pre-flight rejection genuinely pre-mutation — the blocker's counterexample (v16 dump drops
O/V/E + function/sequence/scheduler classes before the redirect throws) is closed. G1–G5 hold.

### CS39 (should-fix) — post-mutation structural rejections scoping — **VERIFIED**

Resolved via NEW ruling SR1, exactly the finding's option (a): the Q-M2 "all rejections before
mutation" sentence is scoped to the pre-flight matrix (which CS38's deferral makes genuinely
pre-mutation); structural whole-stream rejections (manifest counts, gzip trailer/consumption,
section presence) are declared inherently post-mutation with the condemn-target doctrine
(operator doc mandates fresh-DB import + discard-on-any-failure). The considered-and-rejected
two-pass alternative is on the record ("NO two-pass import", pointing at the durability report
§CS39) — the finding required either the split or the recorded alternative; SR1 delivers both.
Carried into the body: M2.b-3 "Contract scope (SR1)", M.4 I-migration-isolation's split sentence,
pins M.5 #15 (pre-flight leaves target unmutated) and #16 (structural rejection condemns, test
asserts loud failure and does NOT assert a clean target — the honest test shape). G1–G5 hold.

### CS40 (should-fix) — durable-promote recipe — **VERIFIED**

M2.a-5's recipe is amended with the named supersession and now pins the exact sequence the
finding prescribed: close gzip stream → **reopen a `FileChannel`** on the temp file +
`force(true)` (fixing the closed-file-fsync gap the finding flagged as implicit) →
`ATOMIC_MOVE` + `REPLACE_EXISTING` rename → **fsync the parent directory** (POSIX rename
durability — the E6 half of FM-M4). The fallback arm is specified fail-closed on the v15 path
("no silent copy fallback"), closing the degradation the finding named
(`atomicMoveWithFallback:306-320`'s silent `Files.move` fallback verified at HEAD). FM-M4
rewritten to name both truncation and lost-rename shapes; M.6 `FileUtils.java` row updated; pin
M.5 #17 exercises the recipe. JDK subtlety check (G4): with `ATOMIC_MOVE`, `REPLACE_EXISTING` is
formally redundant-or-ignored and atomic-replace-onto-existing is platform-dependent — but the
design pins the *property* ("REPLACE_EXISTING-capable atomic move") with a fail-closed fallback,
which is the correct design-level formulation; the platform question lands on the implementer
with a loud failure mode, not a silent hole. G1–G5 hold.

### CS41 (should-fix) — upfront final-name delete — **VERIFIED**

M2.a-4: the constructor's upfront delete (`DatabaseExport:85` — verified: it targets the FINAL
name, and `prepareForFileCreationOrReplacement` does `Files.deleteIfExists`) is DROPPED; the
final name is replaced only at a verified promote via the REPLACE_EXISTING-capable atomic move —
the finding's exact remedy including the pin it demanded on the JDK
atomic-move-onto-existing-target question (covered under CS40's recipe + fail-closed fallback).
The wording sweep is complete: M.4 I-migration-isolation and I-migration-failfast both reworded
from "no file at the final name" to "final name untouched / pre-existing dump preserved"; pin
M.5 #1 reworded to assert preservation of a pre-existing dump. G1–G5 hold.

### CS42 (should-fix) — undeclared exporter-version — **VERIFIED**

Resolved via NEW ruling SR2, which is exactly the finding's recommended assignment elevated to
the one-line supplementary ruling the finding said it needed (it touches the abstract lenient
path): a dump never declaring a parseable `exporter-version` is rejected fail-closed. The
rationale is carried (legitimate legacy dumps always declare — every exporter writes `info`
first; the rejected set is corrupt/truncated/hand-damaged, including the streaming crash shapes
the finding traced through `exporterVersion = -1` at `DatabaseImport.java:111`). Propagated
consistently: M2.b dispatch ("undeclared or malformed … rejected fail-closed (SR2)"), M.1 Out
list, §0 R1 refinement note, pin M.5 #14. One precision hole in SR2's trigger wording is filed
as CS46 (§4, suggestion) — it does not undermine the ruling's substance. G1–G5 hold.

### CS43 (should-fix) — gzip validation sequence — **VERIFIED**

M2.b-2 is rewritten with the named supersession ("supersedes the bare arithmetic sentence") and
pins the exact sequence the finding prescribed, element for element: subclass **disables
multi-member continuation** (named as what makes draining safe); (1) post-manifest drain of the
*decompressed* stream to EOF; (2) `inflater.finished()` assert; (3) seekable-only
`headerLen + getBytesRead() + 8 == physicalSize`. Both failure shapes the finding predicted are
explicitly precluded: the false-rejection shape ("skipping the drain leaves `getBytesRead()`
legitimately short … would false-reject valid dumps") and the unarmed-check shape (the forbidden
probe is defined as the raw-stream / JDK next-member probe, NOT the decompressed drain). The
layered-detection table (truncated deflate → EOF/inflate exception; corrupt trailer → subclass
verify; trailing garbage → arithmetic; content corruption → CRC32) is carried verbatim. Stream
ctor scope = (1)-(2), with (3)'s stream-variant scope carried as the WI10a decomposition
obligation. G1–G5 hold.

### CS44 (suggestion) — mid-import crash operator signal — **VERIFIED**

Folded into the WI3 operator migration-procedure document per triage: pin M.5 #18 now names the
operator-visible completion signal the finding demanded — "import completeness = importer
exit 0" — plus the condemn-on-any-failure doctrine; M.6 gains the `docs/` page row "(folds CS44 +
carries the SR1 doctrine)". The finding's optional hardenings (RID-map heuristic /
import-in-progress marker) were offered as optional and not adopted — consistent with the
suggestion severity and the approved disposition. G1–G5 hold.

## 3. Verdicts — concurrency findings (CN48–CN53)

### CN48 (should-fix) — unauthenticated-open-on-corpse composition — **VERIFIED**

The task requires verifying the CS34 remedy addresses this composition *specifically*. Traced
both of CN48's counterexamples against the amended design:

1. **Exception case** (phase-2 injected failure → later `openNoAuthenticate` mints a usable
   session on the phase-1-only DB via P7): closed by cleanup-on-exception. Composition check:
   the catch at `createStorage:750` executes **inside the factory monitor** (`synchronized (this)`
   spans `:706-771` per the concurrency report's P1), and every session-minting path takes the
   same monitor (P2) — so the cleanup completes before any `openNoAuthenticate` can observe the
   maps; post-cleanup, the open fails with database-does-not-exist. No new interleaving window.
2. **Crash case** (restart; `exists()` true; unauthenticated open loads the schema-without-users
   DB): closed by the belt — §A1's refusal is specified for "the open" generically, and pin
   G.5 #9(b) makes the unauthenticated path explicit: "refuses `open`/`openNoAuthenticate`
   loudly". The half-genesis DB is no longer indistinguishable from a healthy users-dropped DB —
   the marker distinguishes it by construction (and its semantics survive the
   `createDefaultUsers=false` case, so the distinguisher is sound, unlike the zero-users
   heuristic CN48's crash paragraph worried about).
3. **Footprint** — `YouTrackDBInternalEmbedded.java` added to G.6 (CN48's item (c)).
4. **State-count tail** — CN48's "four durable intermediate states" undercount correction is
   subsumed by the finer W0–W9 enumeration.

One composition seam the amendment leaves unpinned — `drop()` itself routes through
`openNoAuthenticate` (`YouTrackDBInternalEmbedded.java:814`), so the belt check makes the very
discard the refusal message mandates throw an exception (though the `finally` at `:820-841`
still deletes) — filed as CN54, suggestion. It does not defeat the remedy (discard remains
physically achievable; refusal of session-minting opens is correct), so CN48 stands VERIFIED.

### CN49 (should-fix) — lazy-creator grounding correction — **VERIFIED**

Citation-correction-only disposition, decision unchanged — exactly as approved. The Rulings
§Q-G1 entry is annotated: original citations (`FunctionLibraryProxy.java:54`,
`SequenceLibraryProxy.java:72`) declared test-only dead code; live sites re-anchored to
`FunctionLibraryImpl.createFunction:137-139 → init:164-185` **including the index-repair arm**
and `SequenceLibraryImpl.createSequence:103-123 → init`; the Open Questions Q-G1 text is kept as
historical record with the annotation pointing forward. All three code claims re-verified at
HEAD (§1): the proxies' `create()` methods have zero production callers (repo grep: only
`SequenceLibraryProxyTest.java:207-208`); `createFunction` at `:137-139` calls `init`, whose
body at `:164-185` contains both the `createClass("OFunction")` arm and the
`prop.getAllIndexes().isEmpty()` repair arm; `createSequence` at `:103-123` calls `init`. The
future legacy-path-removal PR now has the correct inventory. G1–G5 hold.

### CN50 (should-fix) — single config read / blob registration — **VERIFIED**

G2.b adopts the finding's option (a) verbatim: the count is read exactly ONCE in `doCreate` from
the create-time `contextConfiguration`; the register loop performs NO second config read and
instead enumerates the storage's **actual** `$blob*` collections by name (the register-only
sentence itself was rewritten from "resolve `getCollectionIdByName("$blob" + i)`" — which would
have needed the count again — to name-enumeration). The hazard citation is accurate:
`ContextConfiguration.getValue:90-96` falls through to the process-global mutable
`GlobalConfiguration` value (verified), which is what the HEAD loop at `SharedContext.create:198`
hits. Enumeration-at-genesis soundness check (G4): at the point the register loop runs, the only
collections in the storage are `internal` and the birth-created `$blob0..N-1` — no user
collection can exist mid-genesis (factory monitor, P1/P2) — so name-enumeration is exact, and
the frozen-at-birth semantic is implemented literally as the design claims. Both drift
directions from the finding's counterexample (bogus `-1` registration / unregistered physical
blobs) are structurally eliminated. G1–G5 hold.

### CN51 (should-fix) — manifest-count provenance — **VERIFIED**

Primary adopted in M2.a-5 ("Count provenance pinned"): exporter increments its own per-section
counters as it writes; the manifest is NEVER re-derived from a fresh snapshot at manifest/close
time — with the exact hazard citation carried (`getImmutableSchemaSnapshot` mints fresh
snapshots when unpinned, `MetadataDefault.java:137-145`, verified) and the consequence named
(re-derivation under concurrent DDL would fail-closed-reject a good dump). Import side pinned
symmetrically in M2.a-5 and M2.b-3: importer verifies against its own consumption tallies, never
target-DB queries — closing the finding's import-side counterexample (concurrent inserts during
import inflating a target-DB count query). New FM row FM-M17 records the un-amended design's
defect; pin M.5 #17's last clause ("an export under concurrent DDL yields a self-consistent
manifest") tests it. Secondary clause (post-mutation honesty of the manifest verify) resolved by
SR1, cross-referenced in M2.b-3's contract-scope paragraph. G1–G5 hold.

### CN52 (should-fix) — concurrent exporters / fixed temp name — **VERIFIED**

M2.a-4 amended: per-export unique temp filename (UUID/pid-suffixed) opened `CREATE_NEW` — the
finding's one-line fix, both halves (uniqueness kills the shared-offsets interleaving;
`CREATE_NEW` kills the delete/truncate races through `DatabaseExport.java:87-91`'s deterministic
truncating open, verified at HEAD). The stated post-condition matches the finding's acceptance
shape: concurrent exporters produce independent temp files and each promote publishes one
*internally consistent* dump (last-rename-wins is inherent to same-target concurrent backups and
was not the defect; the defect was corrupt-bytes-promoted-with-both-reporting-success). FM-M15
row + pin M.5 #17's concurrent-exporters clause added. Interaction with CS41 checked (G4): with
unique `CREATE_NEW` names, the ctor's temp-file `prepareForFileCreationOrReplacement` (`:88`)
becomes vacuous rather than harmful — consistent. G1–G5 hold.

### CN53 (suggestion) — system-DB first-touch race — **VERIFIED** (deferral disposition)

Disposition = DEFERRED as pre-existing, which is precisely what the finding itself recommended
(it self-classified as pre-existing and unchanged by Track 8, and asked only that (a) the
system-DB genesis test avoid parallel first-touch and (b) the one-line fix be allowed to ride
opportunistically). Both asks are recorded in the disposition table entry ("pin G.5 #6 must
avoid parallel first-touch; an opportunistic one-line fix may ride the genesis unit if free").
Nit (non-blocking, see OBS-2 §5): the constraint lives only in the disposition table — pin
G.5 #6's own text was not annotated; decomposition must carry it so the test author sees it.
G1, G3, G5 hold; G2 is satisfied at the record-keeping level appropriate to a deferred
suggestion.

## 4. New findings (gate iteration 1) — ledger continuation

### CS45 — suggestion — the §A1 marker leaves a complete-but-unmarked crash state unenumerated; W9's "benign" is imprecise and pin G.5 #9(c) can be read to conflict

**Design §:** Amendments §A1 (mechanism 2 + W-table W9 row), pin G.5 #9(c).
**Trace.** The marker is "written immediately after the phase-2 commit completes" — a separate
durability event (storage-config write, its own atomic op per the `setSchemaRecordId:8088-8100`
precedent). So the designed sequence has a state the W-table does not carry: **phase-2 commit
durable, marker write not yet durable** (WAL commit records need not be flushed at that instant;
a crash there is reachable). At reopen the DB is genesis-complete but marker-less → the belt
check refuses it. W9 as tabulated ("after the phase-2 commit, before listeners | complete DB;
listeners are runtime-only | benign") lexically spans this window yet declares it benign, and
pin G.5 #9(c) ("a completed create opens with the marker present") is true only for a create
whose marker write reached durability.
**Counterexample.** Power loss after the phase-2 WAL commit record is durable but before the
marker's WAL record is flushed → restart → dirty flag → WAL replay commits phase 2, marker
absent → `open` refused with "genesis incomplete — discard and re-create" on a database that is
in fact complete.
**Why suggestion, not higher.** The failure direction is strictly fail-closed (a false refusal
of a fresh, data-free DB; the message's discard-and-recreate procedure is cheap and correct); no
unsafe state opens. But CS36 made the honest enumeration *mandatory*, and this state is a direct
product of the amendment's own mechanism — the table should gain a W9a row (or annotate W9):
"phase-2 durable, marker not durable → refused at open (fail-closed false refusal, accepted)".
Test #9(c) should be scoped to "a create that RETURNED SUCCESS opens with the marker present"
only if the design also pins that the marker write is made durable before `createStorage`
returns — otherwise reword to tolerate the accepted false-refusal window.

### CS46 — suggestion — SR2's trigger wording is ambiguous about WHEN the undeclared-version rejection fires

**Design §:** Rulings §SR2; M2.b dispatch; pin M.5 #14.
**Trace.** SR2: "A dump that reaches its section loop (or end of stream) without having declared
a parseable `exporter-version` … is rejected." Every import "reaches its section loop" before
the version is known — `importInfo` IS a section-loop case (`importDatabase:226-243`,
`importInfo` at `:230`), so the version is always declared *inside* the loop.
**Counterexample.** An implementer (or the test author of M.5 #14's "undeclared
exporter-version → reject") reads the sentence literally and evaluates the check at loop entry:
`exporterVersion == -1` holds for every dump at that point → every valid v14/v15 dump is
rejected. The obviously intended semantics — reject when a **non-info section is encountered**
(which the CS38 deferral needs anyway, since deferred mutations must not be unlocked by a
schema-before-info dump) **or end of stream is reached** with no declared version — is
recoverable from context but is nowhere stated as the trigger point.
**Remedy.** One clarifying sentence in SR2 or the M2.b dispatch: "the rejection fires at the
first non-`info` section tag, or at end of stream, whichever comes first, if no parseable
`exporter-version` has been declared by then." This also closes the loop with CS38: a dump whose
first tag is not `info` is rejected before any deferred preamble mutation can be unlocked.

### CN54 — suggestion — the open-time completion check composes awkwardly with `drop()`: the mandated discard path throws the very refusal it prescribes

**Design §:** Amendments §A1 mechanism 2 ("refuses the open loudly with a 'genesis incomplete —
discard and re-create' message"); pin G.5 #9(b).
**Code:** `YouTrackDBInternalEmbedded.drop:808-842` — `drop` mints a session via
`openNoAuthenticate(name, user)` at `:814` (to fire `onDrop` lifecycle listeners), then deletes
the storage and cleans the maps in the `finally` at `:820-841`.
**Trace.** Under the belt check, `openNoAuthenticate` on a W6/W7 corpse throws. Java `finally`
semantics mean the deletion block still runs (`exists(name)` true → `sharedContext.close()`,
`storage.delete()`, map removal), after which the refusal exception propagates out of `drop()`.
**Counterexample.** Crash at W7 → restart → operator follows the refusal message and calls
`drop("db")` → `drop` throws "genesis incomplete — discard and re-create" **even though the
discard succeeded** (files deleted, maps clean); an operator script treats drop as failed and
retries → the retry's `openNoAuthenticate` now throws database-does-not-exist → two consecutive
"failures" for a discard that worked. Additionally the `onDrop` listeners never fire for the
corpse (no session could be minted) — acceptable for a half-genesis DB but unstated.
**Why suggestion.** Fail-messy, not fail-unsafe: the discard is physically achievable through
the API and nothing corrupt survives. But the amendment's own operator instruction ("discard and
re-create") deserves a specified happy path. Remedy: pin in §A1 that the completion check gates
*session-minting opens for use*, and either (a) exempt/soften the check on the `drop()` internal
open, or (b) specify that `drop()` tolerates the refusal (catch-and-proceed to deletion) — and
extend pin G.5 #9's crash-path test with "drop() discards the corpse without surfacing the
refusal".

## 5. Observations (non-findings, for decomposition)

- **OBS-1 (CS38 adjacency).** The deferred-mutation list names `:214`, `:216-222`, and
  `removeDefaultCollections`, but not the `beforeImportSchemaSnapshot` read at
  `DatabaseImport.java:223`, which at HEAD is taken *after* the `:214` drop. Traced its
  consumers: `isSystemRecord:1154-1167` / `findRelatedSystemRecord:1115` consult the snapshot
  only for `OUser`/`ORole`/`OSecurityPolicy` by collection id — classes `removeDefault-`
  `NonSecurityClasses` never touches — so a snapshot taken before vs after the deferred drop is
  behavior-equivalent for its actual uses. No defect; decomposition should still move the whole
  preamble block (`:214-:223`) as a unit to preserve HEAD-relative ordering by construction.
- **OBS-2 (CN53).** The "pin G.5 #6 must avoid parallel first-touch" constraint exists only in
  the disposition table; carry it into the test pin's own wording (or track-8.md) at Move 2/3 so
  the test author cannot miss it.
- **OBS-3 (bookkeeping).** The amendment commit predates this gate; the design body, disposition
  table, SR rulings, and §A1–A3 are mutually consistent — no orphaned references to superseded
  sentences were found (the two originally-cited proxy sites survive only in the Open Questions
  historical text, explicitly marked as retained history).

## 6. Alternative-hypothesis log (gate obligations)

| Hypothesis considered | Evidence | Outcome |
|---|---|---|
| CS34's cleanup could race an open between failure and map-removal (remedy unsound as designed) | catch at `createStorage:750` is inside the `synchronized (this)` spanning `:706-771`; all minting paths synchronized (concurrency report P1/P2, re-checked) | rejected — no window; VERIFIED stands |
| §A1's "dev-only exposure" compatibility claim is false (released v6 DBs exist without the marker) | `CURRENT_VERSION_NUMBER = 6` introduced by `5118a49903`, not an ancestor of `origin/develop`; pre-bump v4 and 2.0-M1/M2 v5 DBs are rejected by Track 2's gate regardless | rejected — claim true; VERIFIED stands |
| The marker refusal blocks the operator's discard entirely (would upgrade CN54 to should-fix) | `drop:808-842` deletes in `finally`; deletion runs even when `openNoAuthenticate` throws | rejected — discard achievable, only messy; CN54 stays suggestion |
| CS38's `< 15` deferral silently changes legacy behavior | traced `:214-:230`: intervening reads are the deferred IM/auto-index block and the `:223` snapshot, whose consumers are drop-invariant (OBS-1) | rejected — behavior-preserving claim holds |
| CN50's name-enumeration could mis-register a non-blob collection | at genesis only `internal` + `$blob0..N-1` exist (factory monitor excludes all other sessions) | rejected — enumeration exact at the only call site |
| CS40's `ATOMIC_MOVE + REPLACE_EXISTING` incantation is platform-unsound (silent hole) | design pins the property + fail-closed fallback, not just the incantation | rejected as a defect — loud failure mode specified |
| SR2's wording ambiguity is harmless (context disambiguates) | pin M.5 #14 turns the sentence into test methods; a literal reading rejects all dumps | not fully rejected → filed as CS46 (suggestion) |
| W9's "benign" already covers the pre-marker window (no CS45 needed) | W9's design answer says "complete DB … benign" but the belt refuses a marker-less complete DB — direct textual conflict | not rejected → filed as CS45 (suggestion) |

## 7. Verdict summary

| ID | Severity | Verdict | Evidence gist |
|---|---|---|---|
| CS34 | blocker | VERIFIED | §A1 both mechanisms specified (cleanup + marker with correct not-users-exist semantics); FM-G3→W0–W9; pin #9 rewritten (incl. `failIfExists=false`); footprint += `YouTrackDBInternalEmbedded.java`; all code anchors true at HEAD |
| CS35 | should-fix | VERIFIED | G2.a completeness-signal paragraph; marker refusal replaces `fromStream:887-894` breadcrumb, strictly stronger; §A1 item 3 |
| CS36 | should-fix | VERIFIED | W0–W9 table (≥6 states, W4/W5 vs W6/W7 signatures separated); pointer-fold declined on the record; pin #9 keyed to W-states |
| CS37 | suggestion | VERIFIED | recorded as pre-existing; corrected claim ("rolls back OR fails config load") in FM-G4 + W1/W2 |
| CS38 | blocker | VERIFIED | M2.b pre-flight deferral covers `:214` + `:216-222` + both `removeDefaultCollections` sites; info-first compat verified (`exportDatabase:136`); `<15` deferral behavior-preserving (traced); FM-M14, pin #15 |
| CS39 | should-fix | VERIFIED | SR1 scopes pre-flight vs structural; condemn-target doctrine; two-pass rejected on record; M.4 + pins #15/#16 |
| CS40 | should-fix | VERIFIED | recipe pinned: reopened-channel `force(true)` → ATOMIC_MOVE+REPLACE_EXISTING → parent-dir fsync; fail-closed fallback; FM-M4 both shapes |
| CS41 | should-fix | VERIFIED | upfront final-name delete (`DatabaseExport:85`, verified) DROPPED; promote-time replace only; M.4 + pin #1 reworded "final name untouched" |
| CS42 | should-fix | VERIFIED | SR2 issued: undeclared/malformed exporter-version rejected fail-closed; dispatch, M.1, R1 note, pin #14 all carry it (trigger wording → CS46) |
| CS43 | should-fix | VERIFIED | exact drain→finished()→arithmetic sequence pinned; multi-member disabled; forbidden-probe redefined correctly; layered detection; stream-ctor scope + WI10a |
| CS44 | suggestion | VERIFIED | folded into WI3 doc; pin #18 names "import completeness = importer exit 0" + condemn doctrine |
| CN48 | should-fix | VERIFIED | unauthenticated-open composition closed both arms: exception→in-monitor cleanup (no window); crash→pin #9(b) explicitly refuses `openNoAuthenticate`; footprint added; drop-path seam → CN54 |
| CN49 | should-fix | VERIFIED | Q-G1 ruling annotated; live sites `createFunction:137-139→init:164-185` (repair arm named) + `createSequence:103-123→init` verified; proxies test-only (grep confirmed) |
| CN50 | should-fix | VERIFIED | single read in `doCreate`; register loop enumerates actual `$blob*` by name (option (a)); `ContextConfiguration.getValue:90-96` fallback verified; enumeration exact at genesis |
| CN51 | should-fix | VERIFIED | exporter-tallied / importer-tallied provenance pinned (M2.a-5, M2.b-3); `MetadataDefault:137-145` verified; FM-M17; pin #17; secondary via SR1 |
| CN52 | should-fix | VERIFIED | per-export unique `CREATE_NEW` temp name (M2.a-4); FM-M15; pin #17; fixed-`.tmp` premise verified at `DatabaseExport:87-91` |
| CN53 | suggestion | VERIFIED | correctly DEFERRED as pre-existing per the finding's own recommendation; #6-test constraint + opportunistic-fix option recorded (carry into pin text at decomposition — OBS-2) |
| **CS45** | **suggestion (NEW)** | filed | complete-but-unmarked crash window (phase-2 durable, marker not) unenumerated; W9 "benign" conflicts with belt refusal; fail-closed false refusal; pin #9(c) wording |
| **CS46** | **suggestion (NEW)** | filed | SR2 trigger point ambiguous ("reaches its section loop" is true of every dump before info parses); pin the first-non-info-tag-or-EOF trigger |
| **CN54** | **suggestion (NEW)** | filed | `drop:808-842` mints via `openNoAuthenticate:814` → belt refusal throws out of the mandated discard path although `finally` deletion succeeds; pin the discard route |

**Gate outcome:** all 17 in-scope findings VERIFIED (both blockers CS34/CS38 conclusively; both
NEW rulings SR1/SR2 correctly recorded and propagated). Three new suggestion-severity findings
filed (CS45, CS46, CN54) — none re-opens an amendment; all are precision/composition pins that
can ride the next triage or Move 2/3 decomposition.
