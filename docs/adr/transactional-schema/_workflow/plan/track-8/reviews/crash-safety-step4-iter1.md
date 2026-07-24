# Crash-Safety / Durability Review — Track 8 Step 4, iteration 1

- **Commit under review:** `2433d684ae` ("Harden database export and add validated-gzip
  primitive") on branch `transactional-schema`; HEAD at review time `1f662e0146`.
- **Perspective:** crash-safety / durability. Finding IDs CS58+.
- **Binding spec:** `plan/track-8.md` Step 4 (lines 402-449) + Step 4 completion note
  (lines 755-825); `plan/track-8-design-drafts.md` M2.a (lines 316-372), FM-M rows
  (lines 440-456), pins M.5 #1/#2/#8/#17 (lines 480-518, esp. reworded #1); SR1 scoping
  (drafts lines 423-428 — import-side condemnation doctrine, only boundary-relevant here);
  pass-1 durability review E-table + CS40/CS41 (reviews/adversarial-durability-pass1.md
  lines 195-258).
- **In-scope files (from `git show 2433d684ae --stat`):**
  `core/.../api/config/GlobalConfiguration.java`, `core/.../common/io/FileUtils.java`,
  `core/.../core/db/tool/DatabaseExport.java`, `core/.../core/db/tool/DatabaseImport.java`,
  `core/.../core/db/tool/SpillableRecordBuffer.java`,
  `core/.../core/db/tool/ValidatedGZIPInputStream.java`, plus 4 new test classes.
- **Mode:** read-only; no Maven; no production-file modification.

## 0. Review obligations (criteria + premises)

**Criteria (the design's promises, restated as checkable obligations):**

- **O1 (CS41 / I-migration-atomic-artifact):** a failed or crashed export NEVER corrupts or
  removes the last good dump at the final name; the final name is touched by exactly one
  operation — a verified, completed promote.
- **O2 (M2.a-4):** a promote can only happen after ALL content — every section, the trailing
  manifest, the JSON closing brace, and the gzip trailer — is written and flushed; an
  unflagged `close()` never renames.
- **O3 (CS40 / FM-M4):** the promote recipe makes the promoted dump complete AND durable:
  fsync source content via a reopened channel → `ATOMIC_MOVE`+`REPLACE_EXISTING` →
  parent-directory fsync; fail-closed, no copy fallback.
- **O4 (M2.a-3 / Q-M1):** spill files are collision-free and deleted on every (in-process)
  path; copy-out is whole-or-fatal.
- **O5 (CN52):** two concurrent exporters of the same final path cannot interleave bytes;
  every promote publishes one internally consistent, complete dump (last-wins replace).
- **O6 (CN51 / FM-M17):** manifest counts are exporter-tallied at write time, never
  re-derived from a fresh schema snapshot.
- **O7 (CS43, primitive only):** the gzip validator enforces the pinned sequence
  (single-member by construction → drain → trailer CRC32+ISIZE → full-consumption →
  physical-size arithmetic) with no silent acceptance path.

**Premises (verified JDK/OS semantics relied on below):**

- P1: POSIX `fsync(fd)` flushes all dirty pages of the *inode*, regardless of which fd wrote
  them — so a reopened `FileChannel.force(true)` on a closed-and-flushed file durably
  persists what the closed stream wrote. `force(true)` also covers size metadata. Opening
  with `WRITE` is the portable choice (Windows `FlushFileBuffers` needs write access).
- P2: `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)`: on POSIX this is `rename(2)`, which
  atomically replaces an existing target; on Windows the JDK's `ATOMIC_MOVE` uses
  `MoveFileExW(MOVEFILE_REPLACE_EXISTING)`. Where an atomic move is impossible (e.g.
  cross-device), `AtomicMoveNotSupportedException` (an `IOException`) is thrown — never a
  silent copy.
- P3: POSIX rename durability requires an fsync of the containing directory; until then a
  crash may revert the directory entry to the pre-rename state. Journal replay on
  crash-consistent filesystems yields old-XOR-new for a same-directory rename, never a torn
  file.
- P4: `FileChannel.open(directory, READ)` + `force(true)` is the standard JVM
  directory-fsync idiom; it works on Linux/macOS and throws (typically
  `AccessDeniedException`) at open on Windows.
- P5: Jackson `JsonGenerator.close()` with default `AUTO_CLOSE_TARGET` flushes and closes
  the underlying `Writer` → `GZIPOutputStream.close()` finishes the deflate stream, writes
  the 8-byte trailer, flushes, and closes the file stream.
- P6: `kill -9` / power loss cannot run any in-process cleanup; only on-disk state at the
  crash instant (as constrained by P1-P3) survives.

## 1. Crash-point sweep of the export lifecycle

Reference points in `DatabaseExport.java`: temp creation `:139-141` (unique
`<final>.<uuid>.tmp`, `CREATE_NEW`); section writes inside `session.executeInTx`
`:190-203` (manifest last, `:197`); stream finish `:210-211` (`writeEndObject` +
`jsonGenerator.close()`); flag `:213` (`completed = true`); promote `:214`/`:240-245`
(→ `FileUtils.durableAtomicMove:333-356`); failure cleanup `:252-268`; unflagged close
`:395-418`; spill file `SpillableRecordBuffer.java:70-75` (create) / `:102-128` (delete).

| CP | Crash/kill point | Surviving on-disk state | O1/O2/O3 hold? |
|----|------------------|-------------------------|----------------|
| CP1 | ctor before `CREATE_NEW` (`:139`) | nothing new; final name untouched (ctor only does `createDirectories(parent)` `:130` — no delete; both HEAD~ `prepareForFileCreationOrReplacement` calls verified gone) | **holds** |
| CP2 | ctor after `CREATE_NEW`, incl. ctor failure at `createGenerator`/`writeStartObject` (`:144-152`) | empty/near-empty orphan `.tmp`; final untouched | holds; orphan → CS59, in-process ctor-leak → CS61 |
| CP3 | mid-section write (info/collections/schema/records/indexes, `:191-196`) | partial `.tmp` (gzip unterminated, no manifest, no flag); final untouched | **holds** — never promotable; even a manually renamed residue fails Step 5's truncated-gzip check (FM-M6) |
| CP4 | mid-spill-file use (`SpillableRecordBuffer:70-75`) | CP3 state + orphan `ytdb-export-record-*.spill` in the dump directory | holds; orphan → CS59 |
| CP5 | during manifest write (`:276-285`) | same as CP3 (manifest absent/partial) | **holds** |
| CP6 | during `jsonGenerator.close()` (`:211`, gzip trailer flush, P5) | `.tmp` possibly missing the gzip trailer; flag never set; final untouched | **holds** |
| CP7 | between `completed = true` (`:213`) and the source fsync | complete-but-possibly-undurable `.tmp`; final = old dump | **holds**; orphan temp |
| CP8 | during `force(true)` on the reopened source channel (`FileUtils:340-342`) | as CP7 | **holds** |
| CP9 | mid-rename (`FileUtils:343`) | kernel-atomic (P2/P3): final name = old XOR new; the "new" content was fsynced at CP8, so either state is a complete dump | **holds** |
| CP10a | after rename, before parent-dir fsync — **process kill only** | rename already in the kernel; OS writeback makes it durable eventually | **holds** |
| CP10b | after rename, before/at parent-dir fsync — **power loss** | final may revert to the OLD complete dump (P3); the process never emitted its success signal, so the operator's exit-status gate correctly reads "failed" | **holds** (fail-safe direction; exactly pass-1 E6 with the success signal absent) |
| CP11 | parent-dir fsync **fails with a real I/O error** (EIO) but the process continues → exit 0 → later power loss | final may revert to the old dump although the tool reported success | **VIOLATED in the rare-EIO case → CS58** (the catch at `FileUtils:348-355` swallows it) |
| CP12 | during `cleanUpOnFailure` (`:252-268`) or unflagged `close()` (`:395-418`) | temp may survive the kill; final untouched (neither path can rename — `close()` short-circuits on `completed` and otherwise only deletes `tempFileName`) | holds; orphan → CS59 |
| CP13 | any of the above with TWO concurrent exporters of the same final path | each exporter independently in one of CP1-CP12; final name always holds nothing, the old dump, or one exporter's complete fsynced dump | **holds** (see §5) |

**Out of scope (justified):** machine-crash states of the *import* side (Step 5 owns the
strict matrix; the minimal `skipManifest` arm added here performs no target mutation and no
validation — recorded as-built deviation (b) in the step note); the storage engine's WAL
(untouched by this commit); durability of the caller-owned `OutputStream` in the streaming
variant (no rename to gate — promote is a verified no-op at `:241-243`; the manifest is the
completion marker per FM-M13).

**Promise check.** "A promoted dump at the final name is always complete + durable": every
observable final-name state in CP1-CP13 is either the untouched previous dump or a dump
whose *content* was fsynced before its rename — no torn state is reachable (P2/P3). The one
residual is the *name-binding* durability under CP11 (CS58). "A failed/crashed export never
corrupts or removes the last good dump": holds at every CP — the only delete in the file
targets `tempFileName` (`:263`, `:413`), and the only write to the final name is the atomic
replace.

## 2. `durableAtomicMove` recipe audit (`FileUtils.java:333-356`)

1. **Source fsync via reopened channel — sufficient?** Yes. The call site orders
   `jsonGenerator.close()` (`DatabaseExport:211`, flushes writer → gzip finish + trailer →
   closes the file stream, P5) strictly before `promote()` (`:214`). By P1 the reopened
   `WRITE` channel's `force(true)` flushes every page the closed stream dirtied, plus size
   metadata. No buffered layer survives the close. **Checked.**
2. **`ATOMIC_MOVE`+`REPLACE_EXISTING` vs fail-closed no-fallback.** By P2 the replace is
   atomic on POSIX and Windows; passing `REPLACE_EXISTING` alongside `ATOMIC_MOVE` is
   harmless (ignored where `ATOMIC_MOVE` governs) and pins the intent the pass-1 CS41 remedy
   demanded ("plain `ATOMIC_MOVE` onto an existing target is implementation-specific — must
   be pinned"). On a filesystem without atomic move, `AtomicMoveNotSupportedException`
   propagates — there is no catch and no copy arm in `durableAtomicMove` (contrast
   `atomicMoveWithFallback:306-320` directly above, which Step 4's exporter no longer
   calls). The error surfaces through `exportDatabase`'s catch → `cleanUpOnFailure` deletes
   the temp → loud `DatabaseExportException`, final untouched. **Fail-closed verified.**
3. **Parent-dir fsync — right directory?** `target.toAbsolutePath().getParent()`
   (`:344`) — the final name's parent. For the export call site, source and target are
   same-directory *by construction* (`tempFileName = fileName + "." + UUID + ".tmp"`,
   `DatabaseExport:139`; promote at `:244` re-derives both from the same `fileName`), so one
   directory fsync covers both the temp entry's removal and the final entry's (re)binding.
   **They cannot be in different directories for this caller.** The helper as a general
   `FileUtils` API, however, doesn't document the same-directory assumption and never fsyncs
   the *source* parent — a future cross-directory caller would get a rename whose
   source-side entry removal is not durable → CS60 (suggestion).
4. **The catch around the directory fsync is broader than the recorded carve-out.** The
   step-completion note (track-8.md:786-788) pins: fail-closed, "the dir-fsync leg is
   best-effort only where the platform **cannot open a directory channel**, e.g. Windows".
   The code catches `IOException` around BOTH the open (platform incapability, P4) and
   `force(true)` (a genuine POSIX fsync failure) and continues with a warning whose text
   unconditionally claims "the platform does not support directory channels"
   (`FileUtils:348-355`). On Linux the open succeeds (P4), so any exception caught there is
   in fact a real fsync failure being mislabeled and swallowed → **CS58** (should-fix),
   concrete counterexample in the findings section.
5. **CWD-relativity note (no finding).** `promote()` resolves `Paths.get(tempFileName)` /
   `Paths.get(fileName)` at promote time; if the process CWD changed mid-export and
   `fileName` was relative, the move fails with `NoSuchFileException` → fail-closed abort,
   final untouched. Fail-safe direction; not worth an ID.

## 3. Temp-file hygiene under crash

- **In-process paths: fully closed.** Export temp: deleted by `cleanUpOnFailure:263` (with
  suppressed-attachment per M2.a-6) and by unflagged `close():413`; consumed by the rename on
  success. Spill file: `SpillableRecordBuffer.close()` (`:102-128`) deletes on copy-out,
  discard, and abort alike — the caller holds it in try-with-resources
  (`DatabaseExport:724`); `openContent()` closes the write handle first (`:93-99`), and
  `copyRawValue`'s reader is closed (try-with-resources, `:788-796`) *before*
  `buffer.close()` deletes — so the delete never races an open read handle (Windows-safe).
  Pinned by `SpillableRecordBufferTest` (copy-out AND discard paths) and by the residue
  assertions in every `DatabaseExportHardeningTest` case. **O4 checked.**
- **kill -9 / power loss: orphans accumulate, and the gap is unrecorded.** Neither the
  UUID-named `.tmp` (up to a near-full dump in size) nor the `ytdb-export-record-*.spill`
  (up to one record's JSON) can be reclaimed by any later run: names are unique per
  export/record, and there is no startup or pre-export sweep. Each crashed export leaks up
  to one of each into the *dump directory* (spills go to `java.io.tmpdir` only for the
  streaming variant, `DatabaseExport:161`). The design pins "deleted on every path"
  (M2.a-3), which is honestly satisfiable only for in-process paths; pass-1's E-table
  (durability pass-1 lines 200-209) enumerates `.tmp` residues at E2-E5 without imposing a
  cleanup obligation, and neither the drafts' FM-M table nor the step-completion note
  mentions crash-time residue. This is a **doc/design gap, not a spec violation** → CS59
  (suggestion): record it (operator runbook line: stale `*.tmp`/`*.spill` beside a dump are
  crash residue, safe to delete) or adopt a crash-proof mechanism for spills
  (unlink-after-open is POSIX-only; `DELETE_ON_CLOSE` doesn't survive kill -9 portably —
  hence "record it" is the realistic ask). Residue can never masquerade as a dump: it is
  never at the final name and never promoted.

## 4. Completion-flag placement + gzip trailer ordering

- The flag is set at `:213`, strictly after: manifest written (`:197`, last section inside
  the tx), tx completed, `writeEndObject()` (`:210`), and `jsonGenerator.close()` (`:211`)
  — which by P5 flushes the JSON content, finishes the deflate stream, **writes the gzip
  trailer**, and closes the OS handle. So "flag ⇒ all content including the gzip trailer is
  in the page cache and the file is closed" holds; the subsequent promote makes it durable
  before the rename. **O2 checked.**
- `completed = true` precedes `promote()` (`:213-214`). This deviates from the design's
  letter ("`close()` promotes only when the flag is set") in shape — as-built,
  `exportDatabase` promotes directly and `close()` is a completed-no-op — but not in
  substance: no path exists where `close()` promotes an unpromoted temp (it short-circuits
  on `completed` to a bare `return`, `:396-398`, before any file operation). If `promote()`
  throws, control reaches the same catch → `cleanUpOnFailure` deletes the temp in-line, so
  the no-op `close()` afterwards is harmless. Residual micro-gap: if that in-line delete
  *also* fails (suppressed secondary), the later `close()` cannot retry the delete because
  `completed` short-circuits it → one orphan temp with a loud exception already raised →
  CS62 (suggestion). No promote is possible in that state.
- `Error`s (e.g., OOM) bypass `catch (Exception e)` (`:216`): flag unset, nothing promoted,
  temp possibly orphaned until the caller's `close()` — fail-safe. **Checked.**

## 5. Concurrent exporters (CN52) + manifest provenance (CN51)

- **CN52.** Temp names embed a fresh UUID and open `CREATE_NEW` (`:139-141`): byte
  interleaving in one temp is impossible (distinct inodes), and a pathological UUID
  collision fails the second constructor loudly (`FileAlreadyExistsException`) rather than
  sharing the file. Both promotes: each is fsync-content → atomic rename; the kernel
  serializes the two renames; the final name transitions old → loser's complete dump →
  winner's complete dump (or the reverse order — last wins). At no instant does the final
  name hold a torn file, because every renamed source was complete and fsynced *before* its
  rename (CP9 logic per-exporter). The loser's dump is complete while it is at the final
  name; its inode is unlinked by the winner's replace — both temps are consumed, zero
  residue. Pinned by `concurrentExportersUseUniqueTempFilesAndPromoteConsistentDumps`
  (`DatabaseExportHardeningTest:293-330`), which also re-reads the survivor through the full
  CS43 sequence (`parseDump` → `verifyPhysicalSize`). **O5 checked.**
- **CN51.** The manifest is written exclusively from fields tallied by the writing loops:
  `manifestClasses++` inside the class-write loop (`:607`), `manifestIndexes++` inside the
  index loop (`:535`), `recordExported++` after each successful copy-out (`:759`),
  `manifestBrokenRids = brokenRids.size()` immediately after the brokenRids array is
  written from that same set (`:387`). `exportManifest()` (`:276-285`) touches no schema
  snapshot. Pinned by `manifestStaysSelfConsistentUnderConcurrentDdl` +
  `assertManifestMatchesContent` (manifest vs actual dump content, all four counters).
  **O6 checked.** *Observation (null verdict):* `exportIndexDefinitions` calls
  `indexManager.reload(session)` (`:519`), so under concurrent DDL the indexes section can
  name a class the earlier schema-snapshot section lacks — a cross-section coherence
  question that CN51/FM-M17 deliberately do not promise (they promise
  manifest-vs-content self-consistency only) and that predates this commit; Step 5's
  importer-tallied verify is unaffected. No finding.

## 6. ValidatedGZIPInputStream (CS43 primitive) — durability-relevant audit

- **Single-member by construction:** the class extends `InflaterInputStream` with a raw
  inflater and parses RFC 1952 framing itself; after `readTrailer()` sets
  `trailerVerified` (`:262`), `read()` permanently returns -1 (`:80-82`) — no next-member
  probe exists to consume trailing residue (the forbidden exhaustion probe). The as-built
  base-class deviation is recorded in the step note (deviation (a)) and implements the
  contract exactly.
- **Sequence enforcement:** `verifyFullyConsumed` (`:112-127`) throws unless the drain
  reached the verified trailer (order enforced — pinned by
  `verificationBeforeDrainIsRejected`); `verifyPhysicalSize` (`:135-144`) composes on top
  of it. In-window trailing garbage is caught via `readAheadResidue` (`:252`, rejected at
  `:121-125`); out-of-window garbage is caught by the size arithmetic
  (`headerLength + inf.getBytesRead() + 8`, `:151-158`) on seekable sources — the pure-stream
  residual is exactly the WI10a obligation carried to Step 5. **Correct per spec.**
- **Trailer mechanics:** the unconsumed read-ahead sits at `buf[len - remaining, len)`
  (InflaterInputStream fill contract), matching the `System.arraycopy` at `:242-245`; a
  trailer spanning the buffer boundary is completed from `in` with an EOF-loud loop
  (`:246-251`); CRC32 and ISIZE both verified (`:254-261`); header CRC16 computed over all
  header bytes excluding the CRC16 itself per RFC 1952 (`:196-203`). Truncated
  deflate → loud `EOFException` from the JDK inflater path. `close()` ends the
  self-allocated inflater (`:94-98`, no native leak). Nine-fixture unit suite covers
  valid/truncated×2/garbage/multi-member/corrupt-trailer×2/non-gzip/order. **O7 checked; no
  findings.**

## 7. Findings

### CS58 — should-fix — the parent-directory-fsync leg of `durableAtomicMove` swallows genuine POSIX fsync failures, not just platform incapability, so the CS40 recipe's durability leg is fail-open on the EIO path

- **Location:** `core/src/main/java/com/jetbrains/youtrackdb/internal/common/io/FileUtils.java:346-355`;
  contract text: track-8.md:786-788 (best-effort "only where the platform cannot open a
  directory channel"); design drafts M2.a-5 (recipe step "fsync the parent directory —
  POSIX rename durability"); FM-M4 ("closed by the amended M2.a-5 recipe").
- **Defect:** the `catch (IOException e)` spans both `FileChannel.open(parent, READ)` (the
  Windows-incapability case the carve-out names) and `directoryChannel.force(true)` (a real
  I/O failure on POSIX). On Linux the open succeeds (P4), so anything caught there is a
  genuine fsync failure — logged with a message that unconditionally asserts "the platform
  does not support directory channels" and then discarded. The promote returns, and
  `exportDatabase` reports success.
- **Concrete crash-state counterexample (CP11):** Linux; dump directory on a device
  developing I/O errors. Sections+manifest written, temp fsynced, `rename(2)` lands in the
  page cache; `force(true)` on the directory returns EIO → warn, continue → the exporter
  exits 0, which the operator runbook (WI3, "export-exit-status gate") treats as the
  migration go-signal. Power loss before writeback → journal replay reverts the directory
  entry: the final name holds YESTERDAY's dump. The operator, told "success", migrates from
  a stale dump (or, for a fresh target name, finds nothing and gets a false failure signal
  for a "succeeded" export — pass-1 E6 verbatim, which CS40 was raised to close). No torn
  file is ever produced (content fsync precedes the rename), so the damage is
  wrong-provenance/lost-rename, not corruption — hence should-fix, not blocker.
- **Alternative hypothesis (checked):** "the design itself calls the dir-fsync best-effort,
  so the code matches." Rejected: M2.a-5's fail-closed sentence attaches to the fallback
  arm, but the *step-completion note's own carve-out* is explicitly narrower ("only where
  the platform cannot open a directory channel"), and FM-M4 claims the recipe closes the
  lost-rename state. Either the catch narrows (let a post-open `force` failure propagate —
  it lands in the existing catch → `cleanUpOnFailure` → loud abort, previous dump intact,
  exactly the fail-closed shape used everywhere else), or the design/FM-M4/step note must
  record the EIO residual explicitly. The log text should stop asserting the Windows cause
  unconditionally in either case.

### CS59 — suggestion — crash orphans (`<final>.<uuid>.tmp`, `ytdb-export-record-*.spill`) accumulate in the dump directory with no recorded cleanup story

- **Location:** `DatabaseExport.java:139-141` (unique temp, no sweep),
  `SpillableRecordBuffer.java:70-75` (named spill in the dump directory / `java.io.tmpdir`);
  design text M2.a-3 ("deleted on every path"), M2.a-4; step note "zero temp residue"
  (in-process only).
- **Gap:** after `kill -9`/power loss (CP2/CP4/CP7/CP12, P6) both files survive; UUID
  naming means no later export reclaims them; there is no startup/pre-export sweep. A
  crashed-export loop (e.g., nightly cron against a failing disk) accumulates
  near-dump-sized `.tmp` files unboundedly. The design pins deletion only on code paths and
  pass-1's E-table lists the residues without a cleanup obligation — so this is an
  *unrecorded* gap, not a violated promise. Residue is fail-safe (never at the final name,
  never promoted, never mistaken for a dump by the importer).
- **Ask:** record the gap where it belongs — the WI3 operator migration-procedure page
  (Step 6 deliverable): stale `*.tmp`/`*.spill` beside a dump are crash residue, safe to
  delete; optionally note why in-process deletion is the strongest portable guarantee
  (unlink-after-open is POSIX-only; `DELETE_ON_CLOSE` does not survive kill -9).

### CS60 — suggestion — `durableAtomicMove`'s durability contract silently assumes same-directory source/target; the javadoc does not state it and no source-parent fsync exists

- **Location:** `FileUtils.java:322-356` (javadoc + implementation).
- **Gap:** only the *target's* parent is fsynced. For the sole current caller this is
  complete (temp and final are same-directory by construction, `DatabaseExport:139`, so one
  fsync covers both entries — verified, the charter's "can they be in different dirs?"
  answer is *no* for the export path). But the method is a public `FileUtils` primitive: a
  future same-filesystem cross-directory caller gets a rename whose source-entry removal is
  not durable — after power loss the file can be visible under BOTH names on some
  filesystems, and the "durable" name in the method's contract quietly excludes the source
  side. Cross-filesystem callers are safe (fail-closed via
  `AtomicMoveNotSupportedException`, P2).
- **Ask:** one javadoc sentence pinning the contract ("full durability guaranteed for
  same-directory moves; cross-directory callers additionally need a source-parent fsync"),
  or fsync both parents when they differ.

### CS61 — suggestion — constructor failure after `CREATE_NEW` leaks the temp file and the open stream (no in-process cleanup path exists for a half-constructed exporter)

- **Location:** `DatabaseExport.java:140-152`.
- **Gap:** if `jsonFactory.createGenerator` or `writeStartObject()` throws after the temp
  was created and the gzip stream opened, the constructor propagates with no object for the
  caller to `close()` → orphan temp + fd held until GC finalization. Low probability
  (first-write failures — e.g., disk full at byte 0 — are the realistic trigger);
  consequence is CS59-class residue only, never a promote. A try/catch-cleanup around the
  ctor tail (delete temp, close stream, rethrow) closes it.

### CS62 — suggestion — `completed = true` before `promote()` makes a later `close()` unable to retry temp deletion after a failed promote whose in-line cleanup also failed

- **Location:** `DatabaseExport.java:213-214` (flag before promote), `:396-398` (`close()`
  short-circuit), `:252-268` (in-line cleanup).
- **Gap:** promote throws AND `cleanUpOnFailure`'s `deleteIfExists` throws (both suppressed
  onto the loud primary — correct per M2.a-6): the temp orphan persists, and the caller's
  `close()` no-ops because `completed` is already true. No promote is reachable in this
  state (the temp path is the only rename source and `close()` never renames), so this is
  residue-only. Setting the flag *after* a successful promote — or resetting it in the
  catch — restores `close()` as a cleanup retry and makes the flag's meaning ("promoted")
  match the design prose ("close() promotes only when flagged"). Behavior today is safe;
  the shape is the nit.

## 8. Null verdicts (checked, no finding)

| Obligation | Verdict | Evidence |
|---|---|---|
| CS41 — no upfront final-name delete | discharged | both HEAD~ `prepareForFileCreationOrReplacement` calls gone (HEAD~ `:85`,`:88` vs new ctor `:122-141`); the only `deleteIfExists` targets are `tempFileName` (`:263`,`:413`); pin M.5 #1 red-first evidence recorded (track-8.md:757-764) |
| O2 — flag strictly after all content + gzip trailer | discharged | §4; P5 ordering `:210-213` |
| O3 — fsync-source sufficiency; ATOMIC_MOVE semantics; no fallback | discharged (except CS58 leg) | §2 items 1-2; P1/P2 |
| O4 — spill lifecycle in-process; copy-out whole-or-fatal | discharged | §3; a copy-out `IOException` escapes `exportRecord`'s render-only catch (`:735-753`) into the always-rethrow scan arm (`:349-359`) even under `-bestEffort` — abort, no promote |
| O5 — CN52 | discharged | §5 |
| O6 — CN51 | discharged | §5 |
| O7 — CS43 primitive | discharged | §6 |
| FM-M5 — close-path masking | discharged | catch wraps once (`:222-227`), cleanup attaches suppressed (`:252-268`); failure-path `close()` warns instead of throwing (`:400-417`) so it can never mask |
| FM-M13 — streaming variant | discharged | `promote()` no-op for `tempFileName == null` (`:241-243`); manifest as completion marker |
| Minimal `skipManifest` import arm | no crash-safety surface | consumes tokens only, no target mutation, no validation claimed; recorded as-built deviation (b); Step 5 owns the strict arm (SR1 scoping untouched) |

## 9. Hypothesis log

| # | Hypothesis | Outcome |
|---|---|---|
| H1 | An upfront final-name delete is still reachable somewhere | refuted (grep: only temp deletes) |
| H2 | `close()` can promote after a failure | refuted (`completed` gate; failure path deletes the temp first) |
| H3 | The gzip trailer could be written after the rename | refuted (P5; `:211` before `:213-214`) |
| H4 | Reopened-channel fsync misses stream-buffered data | refuted (P1; streams closed first) |
| H5 | `ATOMIC_MOVE` onto an existing target fails on some mainstream platform / silently copies | refuted (P2; `REPLACE_EXISTING` pinned; exotic FS → loud `AtomicMoveNotSupportedException`, no fallback arm) |
| H6 | A real POSIX dir-fsync failure can be silently swallowed | **confirmed → CS58** |
| H7 | Crash orphans accumulate with no recorded story | **confirmed (doc gap) → CS59** |
| H8 | Concurrent exporters can interleave or promote a torn dump | refuted (unique `CREATE_NEW`; per-exporter fsync-then-atomic-rename) |
| H9 | Manifest counts re-derived from a snapshot | refuted (field tallies only, `:276-285`) |
| H10 | Copy-out failure tolerated in best-effort mode | refuted (escapes to the always-rethrow scan arm) |
| H11 | Spill file deleted while open for read (Windows sharing violation) | refuted (reader closed before `buffer.close()`) |
| H12 | Trailer spanning the read-ahead boundary mis-parsed | refuted (`:246-251` completion loop, EOF-loud) |
| H13 | Temp and final can land in different directories | refuted for the export path (string-concatenated name); helper-contract gap → CS60 |
| H14 | CWD change mid-export breaks the promote | possible but fail-closed (NoSuchFile → abort, final untouched); no finding |
| H15 | Ctor failure after `CREATE_NEW` leaves residue | **confirmed (in-process leak) → CS61** |
| H16 | Flag-before-promote opens a promote-after-failure path | refuted (close short-circuit is delete-free); residue-retry nit → CS62 |

## 10. Verdict

No blockers. The M2.a crash-safety core — never-touch-the-final-name-except-atomic-replace,
completion-flag gating, manifest-last, fsync-before-rename, fail-closed no-fallback move,
unique CREATE_NEW temps — is implemented faithfully and the crash-point sweep confirms the
two headline promises at every enumerated point, with one should-fix (CS58: the
parent-dir-fsync leg's catch is broader than the recorded carve-out, reopening pass-1 E6 on
the rare-EIO path) and four suggestions (CS59-CS62: unrecorded crash-orphan story, helper
contract documentation, ctor-leak, flag-placement nit).

## Compact findings block

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| CS58 | should-fix | FileUtils.java:346-355 | dir-fsync catch swallows real POSIX `force()` EIO, not just Windows open-incapability; log text mislabels the cause; FM-M4's lost-rename state reopened on the EIO path | Linux EIO on dir fsync → warn, exit 0 → power loss → final name reverts to yesterday's dump though the operator's exit-status gate said success |
| CS59 | suggestion | DatabaseExport.java:139-141; SpillableRecordBuffer.java:70-75 | kill -9 orphans (`<final>.<uuid>.tmp`, `*.spill`) accumulate unboundedly; gap unrecorded in design/runbook | nightly export cron against a failing DB leaves N near-dump-sized `.tmp` files; no sweep, UUID names never reclaimed |
| CS60 | suggestion | FileUtils.java:322-356 | `durableAtomicMove` durability contract assumes same-dir source/target (true for export) but doesn't say so; no source-parent fsync | future cross-dir caller: crash after rename → file visible under both names; "durable" contract silently excludes source side |
| CS61 | suggestion | DatabaseExport.java:140-152 | ctor failure after CREATE_NEW leaks temp + open stream (no object to close) | disk-full at `createGenerator` first write → orphan temp, fd till GC |
| CS62 | suggestion | DatabaseExport.java:213-214, :396-398 | `completed=true` before `promote()`: after a failed promote whose in-line temp delete also failed, `close()` can't retry cleanup | promote EIO + delete EACCES → loud abort (correct) but permanent orphan; flag-after-promote would let close() retry |
