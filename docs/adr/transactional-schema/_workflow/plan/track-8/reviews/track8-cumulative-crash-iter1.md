# Crash-Safety / Durability + Fail-Closed Review — Track 8 CUMULATIVE (whole track), iteration 1

- **Diff under review:** `git diff cced9df1af..19ebbcbb2d` (branch `transactional-schema`),
  code files only. HEAD at review time = `19ebbcbb2d`; all line citations are HEAD line
  numbers of the working tree (verified identical to `19ebbcbb2d`).
- **Perspective:** crash-safety / durability + fail-closed, TRACK-LEVEL CUMULATIVE.
  Finding IDs **CS79+**.
- **Charter:** (1) whole-pipeline failure walk (export source → dump file → import target),
  (2) Step 1 storage changes (R3 de-risk + storage-embedded blob-collection registration),
  (3) end-state fail-closed audit across all six steps composed, (4) known-residual
  coherence (do dispositioned residuals COMBINE into something worse).
- **Prior residuals NOT re-filed (dispositioned per work order):** CS64 (justified-deferred
  `importRecord` DatabaseException swallow), CS67–CS74 (order-blind sections, in-member
  post-root junk, resource/session/global-state hygiene, tag-blind brokenRids, detectFraming
  reset edge), CS76–CS78 (fixed by `46b0446008` or deferred), plus the step 1–4 records
  CS47–CS62 and the gate-step4 O2 observation (post-rename dir-fsync failure leaves the final
  name holding the NEW complete dump on a FAILED export — dispositioned, "no invariant
  violated, prose inaccurate").
- **Mode:** read-only; no Maven; only this report written.

## 0. Review obligations (criteria + premises)

**Criteria (charter restated as checkable obligations):**

- **O1:** every crash/failure point across the composed pipeline leaves a recoverable,
  documented state per SR1 (pre-flight pre-mutation / structural post-mutation condemns
  target), FM-M18 (export crash residue accepted + documented), and the condemn-target
  doctrine; in particular the exporter's write→fsync→rename protocol must make it impossible
  for the importer to see a half-written dump that passes full gzip validation.
- **O2:** Step 1's storage-embedded blob collections + the register-only genesis loop are
  durable across restart/crash and compose with WAL recovery (no state where the completion
  marker is durable but the registration is not).
- **O3:** with all six steps composed, no input shape or failure timing reaches the lenient
  (`<= 14`) path or a silent accept where the composed design says reject.
- **O4:** no pair/tuple of dispositioned residuals composes into an outcome worse than the
  individual dispositions assumed.

**Premises (code semantics all verdicts rely on, traced this review):**

- **P1 (writer sequence):** `DatabaseExport` writes JSON through
  `OutputStreamWriter → GZIPOutputStream(temp, CREATE_NEW)`; `jsonGenerator.close()`
  (`DatabaseExport.java:231`) flushes the writer and finishes the deflate stream, writing the
  8-byte trailer (CRC32+ISIZE over the WHOLE payload) exactly once, after `exportManifest()`
  (:215) has run. The manifest is the last section (:213–215).
- **P2 (promote recipe):** `FileUtils.durableAtomicMove` (:348–376) = source-content fsync
  through a fresh channel → `ATOMIC_MOVE`+`REPLACE_EXISTING` rename → target-parent directory
  fsync; only the directory-channel OPEN failure is tolerated, a `force(true)` failure
  propagates (fail-closed, CS58 fix verified in place).
- **P3 (completion gating):** `completed = true` only after `promote()` (:233–234);
  `close()` (:396) short-circuits on `completed`, otherwise deletes the temp and never
  renames; `cleanUpOnFailure` (:268) deletes the temp and never touches the final name.
- **P4 (import boundary):** pre-flight = `runPreFlightChecks` (:442) runs after `importInfo`
  and BEFORE `runDeferredImportPreamble` (:530, guarded by `preambleExecuted`); the first
  target mutation on any import path is inside the preamble. SR2's first-tag arm (:319–324)
  and end-of-stream arm fire before/without the preamble when no version parses.
- **P5 (v15 strictness):** `verifyV15StructuralStrictness` (:590) = section presence +
  duplicates (tag-keyed occurrences, :325, :1830) → manifest-vs-consumption tallies →
  brokenRids/best-effort consistency → CS43 gzip drain + `verifyFullyConsumed` +
  `verifyPhysicalSize` (file source only).
- **P6 (decoder):** `ValidatedGZIPInputStream` is single-member, no next-member probe;
  trailer read+verified only when the deflate stream finishes; truncated deflate → EOF from
  `InflaterInputStream.fill` (loud); trailing in-window residue and physical-size excess are
  rejected by steps (2)/(3).
- **P7 (reader EOF):** `JSONReader.readNext` returns SILENTLY with stale
  `value`/`lastCharacter` when `!in.ready()` (JSONReader.java:207–210, 213–215); on EOF
  mid-scan it breaks (:268-shape, `read == -1`) and the unconditional separator strip
  (`buffer.setLength(length-1)`) corrupts the last token.
- **P8 (exception dispatch in `importRecord`):** the catch (:1600–1605) rethrows everything
  that is NOT a `DatabaseException`. Verified hierarchy: `StorageException extends
  CoreException` (StorageException.java:22) and `SerializationException extends
  CoreException` (SerializationException.java:24) — NEITHER is a `DatabaseException`
  (DatabaseException.java:24 is a SIBLING under CoreException), so storage-level and
  parse-level record failures are rethrown loudly.
- **P9 (marker write):** `SharedContext.create` writes `genesisCompleted=true` as its last
  act (`SharedContext.java:305`) via `AbstractStorage.setProperty` (:8376) →
  `executeInsideAtomicOperation` → a WAL atomic operation SEQUENCED AFTER the phase-1 schema
  commit (:247–279) and the phase-2 security commit (:287). WAL replay recovers a prefix of
  the op sequence, so a durable marker implies a durable phase-1 registration and phase-2
  data (prefix property); the reverse gap (genesis complete, marker not yet durable) is the
  design-accepted W9a false refusal.

## 1. Criterion 1 — whole-pipeline crash/failure-point enumeration

"Recoverable, documented state" = one of: (a) loud abort, previous artifacts intact;
(b) accepted+documented crash residue (FM-M18 / runbook "Crash residue from exports");
(c) condemned target per SR1 + runbook "Any failure condemns the target";
(d) refused corpse per §A1 marker belt + runbook "genesis incomplete" section.

### 1.1 Export (source DB + dump directory)

| # | Crash/failure point | Resulting state | Verdict |
|---|---|---|---|
| E1 | file-ctor before `CREATE_NEW` (`DatabaseExport.java:127–136`, `createDirectories` only) | nothing created; final name untouched | holds (a) |
| E2 | ctor after `CREATE_NEW` — gzip header/generator failure (:148–168) | in-process: temp deleted by the ctor catch (CS61 fixed); kill: orphan `.tmp` | holds (a)/(b); FM-M18 + runbook residue section |
| E3 | in-process failure in any section write (info…manifest, :206–220) | catch → `cleanUpOnFailure` deletes temp, never renames; loud `DatabaseExportException` | holds (a) |
| E4 | per-record render failure (:686–706) | default: loud abort (whole export); `-bestEffort`: record discarded WHOLE, tallied in brokenRids + marker | holds (a) / documented opt-out |
| E5 | mid-spill (`SpillableRecordBuffer`) | in-process: spill deleted by try-with-resources close (:103–128); kill: orphan `*.spill` | holds (a)/(b) |
| E6 | kill during `writeEndObject`/`close()` (:230–231, trailer flush) | orphan temp WITHOUT a valid trailer (P1: trailer only at clean finish); final untouched | holds (b); temp cannot pass gzip validation |
| E7 | kill AFTER `jsonGenerator.close()` but before `promote()` | orphan temp that IS a complete, valid dump (all sections + manifest + trailer) at the `.tmp` name; final untouched | holds (b); see §1.4 seam note — the residue is complete by construction, so even a mistaken import of it is not a half-written acceptance |
| E8 | `promote()` step 1 (source fsync) fails | loud; temp deleted; final untouched | holds (a) |
| E9 | crash mid-rename | kernel-atomic: final = old XOR new; "new" content fsynced at E8 | holds (a) |
| E10 | dir-fsync `force(true)` fails after successful rename | loud FAILURE; final name already holds the NEW complete fsynced dump | dispositioned (gate-step4 O2); not re-filed |
| E11 | kill after `promote()` before `completed = true` (:233–234) | dump promoted + durable; no exit-0 observed → operator re-runs per runbook step 2 | holds (a); re-run replaces atomically |
| E12 | success-message ordering (:222–223 vs :230–233) | listener sees "Database export completed" BEFORE trailer flush + promote | **CS79** (suggestion) |

### 1.2 Between export and import (the dump-file seam)

| # | Failure point | Resulting state | Verdict |
|---|---|---|---|
| B1 | power loss after export exit-0 | content fsynced before rename, rename fsynced via parent dir (P2) → dump durable at final name | holds |
| B2 | operator transfers/copies the dump partially | truncated gzip member → decoder EOF at parse or drain (P6) → loud; v15 additionally backstopped by missing-section/manifest checks | holds (c) |
| B3 | operator picks up a `.tmp` orphan | E6-class: fails gzip validation; E7-class: file is a COMPLETE dump (see seam note) | holds |
| B4 | dump replaced mid-import by a concurrent re-export | per-export unique temp + atomic rename (CN52): the importer's open FD keeps reading the OLD inode on POSIX — one consistent dump either way | holds |

**Seam note (charter question "can the importer ever see a half-written dump that passes
gzip validation?"):** No. The trailer (CRC32+ISIZE) is written exactly once, by
`GZIPOutputStream.finish()` inside `jsonGenerator.close()` (:231), which runs strictly after
`exportManifest()` (P1). Every earlier crash point leaves a member without a valid trailer;
`ValidatedGZIPInputStream` verifies the trailer against the FULL decompressed payload and
rejects in-window residue and (file source) physical-size excess (P6). Therefore any file
that passes the composed CS43 sequence contains the complete JSON the exporter intended —
including the manifest, which then cross-checks section completeness. The only "complete
dump not at the final name" state is E7, which is complete by construction, not half-written.
The one caveat inside the member — junk between the JSON root `}` and the trailer — is CS68,
dispositioned (covered by CRC, silently drained).

### 1.3 Import (target DB)

| # | Crash/failure point | Resulting state | Verdict |
|---|---|---|---|
| I1 | ctor: `Files.size`/framing detection (:196–202, 224–236) | no target mutation; corrupt-header gzip falls to the plain arm → binary garbage never parses `info` → SR2 first-tag rejection (:319–324), pre-mutation | holds; plain-arm FD leak = CS70 (dispositioned) |
| I2 | pre-flight rejection (matrix :442–521: ≥16 redirect, schema-version arms, type violations, Q-M3 framing, SR3 ack gate) | fires BEFORE `runDeferredImportPreamble` (:329–335) → target byte-for-byte untouched | holds (SR1 pre-flight set); step-6 review verified per-arm, re-confirmed on the composed flow |
| I3 | crash/kill mid-preamble or mid-section | condemned target; runbook: no in-database signal, discard on anything but exit-0 | holds (c) |
| I4 | structural strictness rejection (:590–631) | post-mutation, loud, message names the condemnation | holds (c) |
| I5 | crash between strictness pass and JVM exit | no exit-0 observed → condemn | holds (c) |
| I6 | power loss after exit-0 | exit status is observable only after the wrapper's manager close → storage shutdown flush; `session.getStorage().synch()` (:381) additionally precedes it | holds |
| I7 | failure in `rebuildIndexes`/reload/`removeExportImportRIDsMap` (:376–388) | wrapped, loud → condemn | holds (c) |
| I8 | truncation INSIDE a valid gzip member / plain source, in loops other than info+manifest | stale-read spin arms survive the BG30 fix | **CS80** (suggestion) |

### 1.4 Target genesis (Steps 1–3, the "fresh target" the runbook mandates)

| # | Crash point | Resulting state | Verdict |
|---|---|---|---|
| G1 | inside the storage-create atomic op (incl. the new `$blob<i>` loop, `AbstractStorage.java:1537–1539`) | whole create WAL-reverts together (single atomic op); W1/W2 corpse opens fail loudly; `drop()` works (CS54 fix: `doDelete` skips `makeStorageDirty` for `status == CLOSED`, :1689–1696) | holds (d) |
| G2 | between storage create and phase-1 commit | blobs physically present, unregistered (FM-G5) — inert; marker absent → `SharedContext.load` refuses (:166–173) | holds (d) |
| G3 | mid-phase-1 (one schema tx, `SharedContext.java:247–279`) | tx rolls back at recovery; marker absent → refused | holds (d) |
| G4 | between phase-1 and phase-2 / mid-phase-2 (:287) | W6/W7; marker absent → refused; `create(failIfExists=false)` probes the marker (:855–866) instead of silently adopting | holds (d) |
| G5 | after phase-2 commit, marker not yet durable | W9a accepted FALSE refusal (fail-closed; discard of a data-free DB) | holds (d), design-accepted |
| G6 | in-process genesis failure | `cleanUpFailedCreate` (:883) purges maps + deletes storage → `exists()` false; `Error` arm covered (:833); kill mid-cleanup → residue refused at next open/create | holds (d) |
| G7 | server-configured path (`initCustomStorage`, :1178–1200) | cleanup arm added (CS53 fix) — no silently adopted residue | holds (d) |

**Criterion 1 verdict:** holds at every enumerated point; two suggestions filed (CS79 —
premature completion message; CS80 — residual non-termination arms), everything else lands
in a documented state class (a)–(d).

## 2. Criterion 2 — Step 1 storage changes (R3 de-risk + register-only loop)

- **Creation atomicity:** the `$blob0..N-1` collections are created INSIDE the same WAL
  atomic operation as the `internal` collection (`AbstractStorage.java:1510–1539`), so
  they are atomic with storage birth; the negative-count misconfiguration throws inside the
  op and rolls the whole create back (:1529–1536). Zero is allowed (deliberate blob-less DB).
  No new crash state vs the pre-existing create op — confirmed by mechanism inheritance
  (`doAddCollection` shared with the `internal` create).
- **Registration durability:** the register-only loop (`SharedContext.java:274–278`)
  enumerates the storage's actual `$blob\d+` collections by name (pattern derived from the
  shared `MetadataDefault.BLOB_COLLECTION_NAME_PREFIX`, :43–44 — no config re-read, no
  count/name drift channel) and writes them into the transaction-local schema root, committed
  by the phase-1 schema-carry commit. Durability = phase-1 commit durability.
- **Interaction with recovery (the key composed question):** can restart observe
  *marker present, registration absent*? No — P9's WAL-prefix argument: the marker's atomic
  op is appended strictly after the phase-1 commit; replay recovers a prefix, so a recovered
  marker implies a recovered registration. The reverse (registration durable, marker not) is
  the accepted W9a refusal. Physically-present-but-unregistered blobs exist only in marker-less
  corpses (G2), which are refused and discarded — FM-G5's "discarded anyway" is enforced by
  the belt, not assumed.
- **Reopen path:** registration is restored from the persisted schema root by `load`
  (no re-derivation from the pattern at reopen — the pattern runs only at genesis, when only
  storage-birth collections can exist, so no user-named `$blob7` collision channel exists).
- **Corpse handling composition:** refusal → `unregisterGenesisIncompleteCorpse`
  (`YouTrackDBInternalEmbedded.java:468–491`) closes + unregisters (locks released, discard
  unblocked on locking platforms); `drop()` tolerates the refusal via the cause-chain walk
  (:959) and skips onDrop listeners; `create(failIfExists=false)` probes the marker on the
  pre-existing storage (:855–866) and refuses loudly instead of "nothing to do". A corrupted
  marker VALUE (`"tru"`) parses false → refusal (fail-closed direction).

**Criterion 2 verdict: null** — no defect found; durability rests on WAL ordering +
phase-1 commit, both verified in code; every crash window lands in the design's enumerated
W-states with the documented handling.

## 3. Criterion 3 — end-state fail-closed audit (all six steps composed)

Composition walk: Step 6 matrix (info-field validation) → Step 5 strictness arms (version
latch, section loop, post-loop strictness) → Step 4 exporter invariants (manifest-last,
tallied counts, marker, gzip framing). Shapes traced beyond the per-step reviews:

1. **Version-latch × pre-flight ordering (downgrade/disarm channels):** first-declared
   version latched (:831–838); ≥16 redirect (:447) fires inside `runPreFlightChecks`,
   which runs immediately after the FIRST info section — before any preamble mutation — so
   no ≥16 or unparseable-version dump reaches a ≥15-keyed arm or the lenient path. A second
   info section (v15) cannot disarm anything: `infoFieldTypeViolations`/`unknownInfoFields`
   only grow, `schemaVersionDeclared`/`malformedSchemaVersionRaw` transitions that would
   matter are pre-empted by the FIRST pre-flight run having already rejected, and
   `bestEffortDump=true` in either section is judged at that section's own pre-flight run;
   the WI10c duplicate-`info` check condemns the dump at the end regardless. **Null.**
   (Blemish, not filed: a rejection raised by the SECOND pre-flight run is post-mutation but
   carries pre-flight wording; the runbook's "do not attempt to distinguish the cases —
   on ANY failure, discard" makes this operationally moot.)
2. **Failure-timing to the lenient path:** the only timing-dependent routing is
   `detectFraming`'s fall-to-plain on decoder-ctor failure (:224–236). For a file-based v15
   dump: plain arm + declared v15 → Q-M3 rejection (:502); corrupt-header gzip → binary
   garbage cannot parse an `info` tag → SR2 first-tag rejection pre-mutation. No timing
   reaches lenient without the dump DECLARING ≤14 (the sanctioned, out-of-threat-model
   rewrite of the unauthenticated version field). **Null.**
3. **Exporter-invariant ↔ importer-check pairing:** manifest tallied by the writing loops
   (`manifestClasses`/`manifestIndexes`/`recordExported`/`manifestBrokenRids`) vs importer
   consumption tallies — pairwise consistent for honest dumps incl. best-effort, empty
   arrays, and the `___exportImportRIDMap` skip (skipped on BOTH sides: index skipped at
   export :523, class exported and parsed symmetrically). Long-range totals fixed (BG20,
   `readLong` :94–101 of JSONReader). Marker written only when true; quoted marker fixed
   both directions (CS76 fix: quote-strip for the gate :861 + WI12b violation under v15).
   **Null.**
4. **Legacy envelope regression check:** the composed pipeline's only version-ungated new
   rejections are the recorded ones (dangling/malformed info field name — CS77, recorded;
   scalar-only info values — CS75 fix, recorded rule; EOF-bounded info/manifest loops —
   BG30, acceptance-preserving; SR3 marker-keyed ack gate — ruled). A legacy dump truncated
   at a section boundary after `info` still exits 0 with a gutted target — pre-existing
   byte-for-byte behavior, R1-protected, and now explicitly documented as the runbook's
   legacy no-structural-verification caveat. **Null (documented).**
5. **Post-strictness silent paths:** nothing after `verifyV15StructuralStrictness` can
   un-reject (all remaining steps throw on failure); exit-0 is bound to the wrapper process
   per the runbook. **Null.**

**Criterion 3 verdict: null** — no silent-accept and no lenient-path leak found beyond the
dispositioned residual letter-gaps (CS67/CS68/CS74 acceptance-with-drift family), which
remain within their recorded dispositions.

## 4. Criterion 4 — known-residual coherence (composition audit)

| Combination probed | Trace | Verdict |
|---|---|---|
| **CS67 (order-blind) × CS64 (DatabaseException swallow)** — reordered v15 dump (`records` before `collections`/`schema`) silently drops every record yet satisfies the manifest? | Records referencing not-yet-created collections fail at parse/apply with `SerializationException` or `StorageException` — both `CoreException` SIBLINGS of `DatabaseException` (P8), so `importRecord` RETHROWS → loud abort, condemned target. The step-5 prose "could drop every record" does NOT materialize as a silent exit-0: the swallow is keyed on a type these failures do not carry. Also re-confirms the Step-5 fix-thread's F2-STOPPED rationale (no reproducible DatabaseException family) from a new direction | **refuted — no worse composition** |
| CS63-fix latch × CS65-fix alias rejection × CS74 (tag-blind brokenRids) | the mislabeled post-records section's CONTENT is tallied as brokenRids and must still match the manifest; relabeling gains nothing; duplicate sections condemned | within dispositions |
| CS68 (in-member post-root junk) × section machinery | drained bytes are never parsed — cannot smuggle sections past the occurrence tracker | within disposition |
| CS72 (process-global `INDEX_IGNORE_NULL_VALUES_DEFAULT` flip) × Step 6 runbook | the runbook's wrapper-JVM invocation CONTAINS the global-flip blast radius to a throwaway process — the composition *mitigates* the residual | improved, not worsened |
| CS70/CS73 (FD leaks) × runbook retry loop | leaks accumulate per rejected attempt in a long-lived embedder; runbook wrapper exits per attempt | within disposition |
| CS71 (`setUser(null)`) × runbook | fresh session per wrapper run; no cross-effect | within disposition |
| CS59/FM-M18 (crash orphans) × runbook | residue section documents both artifact families; E7's "complete dump at `.tmp` name" is safe (complete by construction, §1.2) | closed loop |
| CS78/BG30 fix scope × runbook truncation row | the fix bounded ONLY the info+manifest loops; the remaining loops keep stale-spin arms while the NEW runbook row claims universal loud rejection | **CS80 filed** |

**Criterion 4 verdict:** one composition escalated to a finding (CS80 — a doc-promise ×
unfixed-loop composition); the headline worst-case composition (CS67×CS64) is refuted by
the exception-hierarchy check; one composition is mitigating (CS72×runbook).

## 5. Hypothesis log

| # | Hypothesis | Check performed | Outcome |
|---|---|---|---|
| H1 | A half-written dump can pass full gzip validation | writer-sequence trace (P1) + decoder trailer semantics (P6) + crash points E2–E11 | refuted (§1.2 seam note) |
| H2 | Marker durable while phase-1/2 not (blob registration lost under a valid marker) | WAL-op sequencing walk (P9) | refuted (§2) |
| H3 | `create(failIfExists=false)` silently adopts a corpse | probe at `YouTrackDBInternalEmbedded.java:855–866`; corrupted-value parse; W1/W2 open-failure arm | refuted |
| H4 | Second info section can disarm a v15 strictness arm after mutation | field-by-field overwrite walk (`bestEffortDump`, schema-version trio, violation lists) | refuted (§3.1) |
| H5 | A failure timing routes a v15 dump to the lenient path | detectFraming fall-to-plain + SR2/Q-M3 arms | refuted (§3.2) |
| H6 | CS67×CS64 composes into silent total data loss (exit 0) | exception-hierarchy verification (P8: StorageException/SerializationException vs DatabaseException) | **refuted** — loud |
| H7 | The BG30 EOF fix covers all reader loops | loop-by-loop stale-state walk (records :1770, indexes inner :1870, collections :1385, schema :679, customFields :1362) | **confirmed gap → CS80** |
| H8 | The exporter signals completion before the durability point | line-order check :222–223 vs :230–234 | **confirmed → CS79** |
| H9 | importSchema's pre-existing swallow gains a new silent path in composition | re-walked under the composed strictness backstop (step-5 H14 re-check) | refuted — CN51/unsupported-tag backstops unchanged |
| H10 | Genesis register-only loop can mis-enumerate (config re-read, case, user collision) | pattern-derivation + genesis-only-invocation check | refuted |
| H11 | Post-rename dir-fsync failure leaves an undocumented state | gate-step4 O2 disposition re-read | already dispositioned; not re-filed |

## 6. Findings

### CS79 — suggestion — the exporter emits "Database export completed" on the listener channel BEFORE the trailer flush and the durable promote

- **Location:** `DatabaseExport.java:222–223` (message) vs `:230–234`
  (`writeEndObject`/`close`/`promote`/`completed = true`).
- **Defect:** the completion message fires after the sections are written but before the
  gzip trailer exists and before the temp file is promoted. Every failure in the remaining
  four steps (end-object write, generator close = trailer flush, source fsync, atomic
  rename, dir fsync) produces a console transcript that FIRST claims completion and THEN
  reports the failure. The runbook's snippets wire the listener to `System.out`; any
  operator tooling that keys on the transcript (rather than the exit status the runbook
  binds success to) gates on a claim made before the durability point.
- **Counterexample:** disk-full at the trailer flush (`jsonGenerator.close()`): the temp
  file is deleted, the export throws — but the log shows "Database export completed in
  Xms" followed by the error.
- **Why not higher:** the runbook binds success to "returned without throwing" + exit
  status + file-exists, so the procedure as documented is safe; the message is a
  misleading secondary channel only.
- **Ask:** move the message after `completed = true` (or reword to "sections written,
  finalizing…").

### CS80 — suggestion (pre-existing mechanics; newly-composed surface) — the BG30/CS78 EOF-bounding covers only the info and manifest loops; the remaining reader loops keep stale-read non-termination arms that the new runbook row promises are "rejected loudly"

- **Location:** unbounded loops `DatabaseImport.java:1770` (records),
  `:1870` (indexes inner field loop), `:1385` (collections), `:679` (schema classes),
  `:1362` (customFields), `:1989` (collectionsToIndex); reader stale-return
  `JSONReader.java:207–215` + EOF separator strip; fixed loops for contrast `:791`
  (importInfo) and `:557` (importManifest); runbook row
  `docs/operator-migration-procedure.md` accept/reject table: "Dump truncated inside a
  section, damaged/dangling info fields | Rejected loudly".
- **Defect:** the input family BG30 itself identified — a VALID gzip member whose
  decompressed JSON is truncated (externally re-gzipped truncation), or a plain legacy
  dump truncated mid-section — reaches end-of-decompressed-stream mid-loop in sections
  other than info/manifest. There `readNext` returns silently with stale state (P7) and
  the loop guard never sees its terminator. Concrete arms: (i) truncation exactly after a
  record separator → `importRecords` re-parses the SAME stale record token forever
  (per-iteration tx + `parsedRecordCount++` + error logging — non-termination with log
  flood); (ii) truncation inside the indexes section → the `:1870` field loop spins with
  NO reads and NO side effects (silent CPU hang). Most other truncation offsets are
  accidentally loud (`checkContent` mismatch on stale tokens, `SerializationException`
  on a strip-corrupted record token — P7/P8), which is protection by accident, exactly the
  pattern the step-5 review flagged for the importSchema swallow.
- **Composition angle (why filed at track level):** Step 6's runbook row promises loud
  rejection for "dump truncated inside a section" WITHOUT scoping to the two loops the F2
  fix actually bounded; the fix commit's own claim ("the hang becomes a rejection") is
  scoped to importInfo/importManifest, but the composed operator-facing promise is not.
  Never accepts (fail-closed letter holds: no exit 0), but the migration vehicle can hang
  instead of rejecting.
- **Counterexample:** take an honest v15 dump, gunzip, truncate immediately after a
  mid-array `},` in the records section, re-gzip → gzip validates (trailer covers the
  truncated payload); import spins re-importing the last record, never terminating.
- **Ask:** either extend the `hasNext()` bounding to the remaining section loops (the F2
  pattern is mechanical), or scope the runbook row the way F6/F7 scoped the exit-0 and
  schema-version claims.

## 7. Compact findings block

| ID | severity | location (file:line) | one-line summary | counterexample gist |
|---|---|---|---|---|
| CS79 | suggestion | DatabaseExport.java:222-223 vs :230-234 | "Database export completed" listener message fires before trailer flush + promote — transcript claims completion for exports that then fail at any of the four remaining durability steps | disk-full at `jsonGenerator.close()` → temp deleted, export throws, but log already said "completed in Xms" |
| CS80 | suggestion (pre-existing mechanics) | DatabaseImport.java:1770,:1870,:1385,:679,:1362,:1989 + JSONReader.java:207-215; runbook accept/reject table | BG30 EOF-bounding covers only info+manifest loops; valid-gzip-of-truncated-JSON (or plain legacy truncation) in other sections hits stale-read non-termination arms (records: infinite re-import spin; indexes: silent no-read spin) while the new runbook row promises "Rejected loudly" | gunzip honest v15 dump, truncate after a records-array `},`, re-gzip → gzip validates, import spins forever re-parsing the last record |

**Null-verdict notes per charter criterion:**

- **#1 (whole-pipeline walk):** holds at all 27 enumerated points (E1–E12, B1–B4, I1–I8,
  G1–G7); every state lands in a documented class (loud abort / accepted residue /
  condemned target / refused corpse); the promote-then-rename seam guarantees no
  half-written dump can pass full gzip validation (§1.2 seam note); the two blemishes are
  CS79 (message ordering) and CS80 (non-termination arms), neither an acceptance violation.
- **#2 (Step 1 storage changes):** **clean null** — blob collections atomic with storage
  birth, registration durable with the phase-1 commit, WAL-prefix ordering excludes
  "marker durable / registration lost", genesis-only enumeration excludes drift, corpse
  refusal/discard/probe arms all verified (§2).
- **#3 (end-state fail-closed):** **null** — no input shape or failure timing reaches the
  lenient path or a silent accept beyond the dispositioned CS67/CS68/CS74
  acceptance-with-drift letter gaps; all disarm channels into the Step 6 matrix and Step 5
  arms are closed by the first-info immediate judgment + version latch + duplicate-section
  backstop (§3).
- **#4 (residual coherence):** the feared CS67×CS64 silent-total-loss composition is
  **refuted** by the exception-hierarchy check (P8); CS72's blast radius is *reduced* by
  the runbook's wrapper-JVM composition; the only residual composition that worsens the
  record is CS78-fix-scope × runbook truncation row, filed as CS80 (§4).

**Overall:** no blockers, no should-fixes; two suggestions (CS79, CS80). The composed
pipeline's crash story — export promote protocol, genesis marker belt, import pre-flight
deferral, condemn-target doctrine, and their seams — holds as designed and as documented.
