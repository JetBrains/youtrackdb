# Gate verification — Track 8 Step 4 review fixes, iteration 1

- **Diff under gate:** `1f662e0146..feab05691e` on branch `transactional-schema`
  (`35c461d726` code+tests, `feab05691e` records). Working tree at gate time == HEAD
  `feab05691e` (git status: untracked review/followup files only), so all file:line
  citations below are valid against both HEAD and the fix commit.
- **Inputs:** review reports `track-8/reviews/baseline-step4-iter1.md` and
  `track-8/reviews/crash-safety-step4-iter1.md` (read in full); fix-commit diff (read hunk
  by hunk); current file states of every touched file (read in full for the tool classes).
- **Method:** read-only; no Maven; no file modification (this report excepted). All
  behavioral claims are code-trace based; discrimination claims that depend on a test *run*
  (the two revert/neuter proofs) are verified structurally — the episode's recorded run is
  cross-checked against the committed artifact's actual assertion messages and control flow.

## 0. Verification criteria (stated before verdicts)

For each finding, VERIFIED requires all of:

- **K1** — the committed change implements the approved remedy (not a look-alike), traced at
  the current file state, not just the diff;
- **K2** — no alternative path re-opens the closed defect (alternative-hypothesis check);
- **K3** — the fix introduces no new breakage on adjacent paths (else an RG finding);
- **K4** — the episode record's claims about the fix are reproducible from the committed
  artifacts (signatures, counts, dispositions).

Additional gate obligations: scope check (every changed hunk maps to a finding; nothing
unrelated rides along), record-commit coherence (code commit carries no docs, record commit
carries no code; numbers add up), and the special-attention audit of `DatabaseExport.java`
against the checkout-mishap risk.

---

## 1. BG18 + CS58 (should-fix) — durableAtomicMove catch narrowed — **VERIFIED**

**Criterion:** only the `FileChannel.open(parent, READ)` failure may be tolerated (Windows
carve-out); a `force(true)` failure after a successful open must propagate; no swallowing
reintroduced via suppressed close exceptions; javadoc updated incl. the CS60 same-directory
contract.

**Trace** (`FileUtils.java:347-374`, current state):

1. The restructure genuinely separates the two failure classes. `directoryChannel` is
   declared `null` (`:355`); the `catch (IOException e)` at `:358` wraps **only** the
   `FileChannel.open` call (`:357`) — the warn text now says "Cannot open the parent
   directory … for fsync" (no longer unconditionally asserting the force failed for
   platform reasons). `force(true)` runs in a *separate* statement group guarded by
   `if (directoryChannel != null)` (`:367-373`) with **no catch** — an IOException from it
   propagates out of `durableAtomicMove` → `promote()` (`DatabaseExport.java:265-271`) →
   `exportDatabase`'s catch → `cleanUpOnFailure` → loud `DatabaseExportException`.
2. **The `try (var openedDirectoryChannel = directoryChannel)` workaround** (`:370`): the
   alias exists because `directoryChannel` is not effectively final (assigned `null` then
   reassigned), so the Java 9 `try (directoryChannel)` form would not compile. Semantics
   audit of the twr shape:
   - `force(true)` throws → twr closes the channel; a close failure is **suppressed onto
     force's exception** (twr semantics) — primary preserved, propagates. No swallow.
   - `force(true)` succeeds, `close()` throws → the close IOException **propagates**
     (previously the broad catch swallowed it). This is a *stricter*, fail-closed change in
     an exotic corner (close-after-successful-force failing), consistent with the remedy's
     stance — recorded as observation O1, not a breakage.
   - No leak window: between the open's success and the twr, only the null-check executes.
3. **Javadoc** (`:334-346`): the carve-out is documented as NARROW ("only the
   directory-channel OPEN failure is tolerated … an I/O failure from `force(true)` on a
   successfully opened directory channel is a GENUINE fsync failure and propagates"), and
   the CS60 contract note is present ("only the TARGET's parent directory is fsynced … a
   future CROSS-directory caller would additionally need the SOURCE's parent fsynced").

**Alternative hypothesis (K2):** could an open-time *non*-IOException (e.g. a runtime
exception) be mis-swallowed? No — the catch is `IOException` only; anything else propagates.
Could the fix have silently changed the happy path? No — open→force→close order identical;
`FileUtilsDurableAtomicMoveTest` (2 tests, unmodified) still pins the observable move
semantics and is unaffected (Linux: open+force succeed).

**K3 note (BG18×CS62 interaction, checked):** a propagating post-open `force` failure
occurs *after* the rename (`:352`), so the final name already holds the NEW dump when the
export reports failure; `cleanUpOnFailure` deletes only `tempFileName`
(`DatabaseExport.java:290-296`) — a no-op, since the temp was renamed away — and the finally
`close()` is likewise temp-only. No wrong state: the final name holds a complete,
content-fsynced dump (old-XOR-new under power loss, both complete). The crash-safety
*review's* remedy prose ("previous dump intact") is inaccurate on this sub-path, but no
**committed** artifact (javadoc `:340-342`, episode track-8.md:844-847) makes that claim —
observation O2, no RG.

## 2. TQ19 (should-fix) — scan-arm discrimination test + episode correction — **VERIFIED**

**Criterion (a):** the new injection point sits INSIDE the always-rethrow arm's try; the
test's only pass-path routes through a loud abort; reverting the arm to log-and-continue
flips the test red. **Criterion (b):** the episode supersedes the vacuous original red-first
signature with the true FM-M5-masking signature and records the matcher explanation.

**Trace (a):**

- Seam: `browseCollectionRecords(String)` at `DatabaseExport.java:800-802`, production body
  exactly `session.browseCollection(collectionName)` (return type
  `RecordIteratorCollection<REC> implements Iterator<REC>`,
  `RecordIteratorCollection.java:37-38` — the `Iterator<RecordAbstract>` narrowing is
  type-correct; the scan loop uses only `hasNext()`/`next()`, `DatabaseExport.java:353-360`).
- Injection point: the test's override (`DatabaseExportHardeningTest.java:113-157`) throws
  `IllegalStateException` from the wrapper's `next()` on the 3rd record. The call site
  `rec = it.next()` (`:356`) is inside the scan `try` (`:352`) — **inside** the arm — and
  *outside* `exportRecord`'s render catch (which wraps only `renderRecord` inside the
  per-record buffer twr, `:749-778`). The `IllegalStateException` is not a
  `DatabaseExportException`, so it bypasses the rethrow-as-is arm (`:363-365`) and lands in
  the generic always-rethrow arm (`:366-380`), which wraps it as
  `DatabaseExportException(…, t)` and throws.
- Pass-path exclusivity: the test passes only if (i) `exportDatabase()` throws a
  `DatabaseExportException` whose `getCause()` chain contains the injected instance **by
  identity** (`:167-175`), (ii) the sentinel at the final name is byte-identical (`:177`),
  (iii) zero residue (`:179-182`). The wrap in the scan arm puts `injected` directly in the
  chain; `executeInTx` rethrows (baseline P7, re-confirmed unchanged); the outer catch does
  not re-wrap an existing `DatabaseExportException` (`:236-246`); the failure-path `close()`
  is warn-only (`:421-445`) and cannot mask.
- Revert-flip (structural, since Maven is off-limits): reverting `:366-380` to the
  pre-hardening log-and-continue makes every wrapped iterator's failure swallowed
  per-collection → the export completes → promotes over the sentinel → `thrown == null` →
  `assertNotNull("a mid-iteration scan failure must abort the export loudly")` at `:161`
  fires — exactly the RED signature the episode records (track-8.md:854-855). The claimed
  proof signature and the committed assertion message match verbatim. **Discriminating.**
- Residual (accepted, matches the pinned property): deleting the generic catch *entirely*
  (raw propagation, no wrap) would keep the test green — but that shape still aborts loudly
  with the cause in the chain, i.e. it is not the FM-M1 swallow; the pin targets swallowing,
  which the test now excludes.

**Trace (b):** the review-fix episode (track-8.md:863-873) records the corrected red-first
signature `AssertionError: the injected scan failure must be the export failure's primary
cause` — which matches the committed M.5 #1 test's actual assertion message
(`DatabaseExportHardeningTest.java:94-95`) and the baseline review's P6/P7 predicted branch
(FM-M5 masking: the old finally-close secondary replacing the primary). The vacuous-original
explanation is recorded verbatim: the earlier revision's matcher used
`- Collection 'ScanFail'` (the CLASS name) which never matched the auto-generated collection
name, so the injection never fired. The original Step-4 episode paragraph (track-8.md:763-771)
is left in place and explicitly superseded ("The Step-4 episode's red-first paragraph is
superseded by this record"), with the red-first *discipline* correctly preserved as standing.
**Both halves verified.**

## 3. TQ20 (should-fix) — step-(3)-only rejection test — **VERIFIED**

**Criterion:** the fixture's garbage must be structurally unreachable by the step-(2)
residue check, so the physicalSize arithmetic is the sole rejecting check.

**Trace** (`ValidatedGZIPInputStreamTest.java:160-183`;
`ValidatedGZIPInputStream.java:246-283`):

- `new ValidatedGZIPInputStream(in, 1)` → `buf.length == 1`, so at any moment
  `inf.getRemaining() <= 1`. In `readTrailer()`,
  `fromReadAhead = Math.min(remaining, 8) == remaining`, hence
  `readAheadResidue = remaining - fromReadAhead == 0` **always** (`:266`) — the step-(2)
  check `readAheadResidue > 0` (`:129`) *cannot* fire, for any input, with a 1-byte buffer.
  Structural proof, no run needed.
- The 64 garbage bytes are appended AFTER the complete member (trailer included), so the
  trailer completion loop (`:258-264`) reads the 8 genuine trailer bytes from `in` and stops;
  after `trailerVerified`, `read()` returns `-1` without touching the source (`:88-90`) —
  the garbage is never read, never buffered. Steps (1)+(2) pass, which the test *asserts*
  (drain equality `:170`, `verifyFullyConsumed()` `:172`), and it additionally pins the
  arithmetic's exactness (`getCompressedBytesConsumed() == compressed.length`, `:173-174`).
- The only rejection left is `verifyPhysicalSize(garbage.length)`:
  `consumed (= headerLength + getBytesRead() + 8 = compressed.length) != physicalSize
  (= compressed.length + 64)` → `ZipException("…does not span the whole source…")`
  (`:142-150`), matched by the test's message assertion (`:180`). Neutering the comparison
  makes `verifyPhysicalSize` return normally → `fail("the physical-size arithmetic must
  reject beyond-window trailing garbage")` (`:178`) throws `AssertionError`, which the
  `catch (ZipException)` does not intercept → red. The episode's recorded neuter-proof
  signature (track-8.md:859-861) matches this `fail` message verbatim. **Discriminating,
  and the step-2-cannot-catch property holds structurally.**

## 4. BG19 (suggestion) — manifest arm version-gated — **VERIFIED**

`DatabaseImport.java:239-253`: the `manifest` arm now consumes only when
`exporterVersion >= 15`; otherwise it throws
`"Invalid format. Found unsupported tag 'manifest'"` — byte-identical to the string the
default arm produces for `tag == "manifest"`, so the lenient ≤14 path is preserved
byte-for-byte as claimed. Edge checks: `exporterVersion` defaults to `-1` (`:111`) and is
set only by the info section (`:440`), so a hand-crafted dump carrying `manifest` *before*
`info` is also rejected (gate reads `-1 < 15`); the real exporter writes
`EXPORTER_VERSION = 15` (`DatabaseExport.java:65, 511-512`) in the info section, which is
always first, so genuine v15 dumps pass the gate — no round-trip regression (episode's
round-trip run green, consistent). The comment records the Step-5 obligation handoff.

## 5. CQ19 (suggestion) — Inflater.end() on ctor failure — **VERIFIED**

`ValidatedGZIPInputStream.java:70-80`: the ctor tail wraps `readHeader()` in
`catch (IOException | RuntimeException e) { inf.end(); throw e; }`. `close()` (`:100-108`)
still ends the inflater on the success path — no double-end hazard (`Inflater.end()` is
idempotent, and a failed ctor never yields an object to close). Residual: an `Error` during
`readHeader` bypasses the catch (JDK-parity, matches the remedy's scope) — observation O3.

## 6. CQ20 (suggestion) — long header arithmetic — **VERIFIED**

`headerLength` is `long` (`:54`), `readHeader()` returns `long` with `count` a `long`
(`:174-176`), `readHeaderZeroTerminated` returns `long` (`:236-244`) — the FNAME/FCOMMENT
wrap vector is closed; FEXTRA stays `int` but is RFC-bounded ≤ 65535 (`:198-204`), safe.
`getCompressedBytesConsumed()` (`:156-162`) and `getHeaderLength()` (`:165`) return `long`;
grep confirms no other caller of `getHeaderLength` exists to break on the widening.

## 7. CQ21 (suggestion) — finally{close()} restored — **VERIFIED**

`DatabaseExport.java:250-255`: `finally { close(); }` after the catch. Safety trace across
all paths: success → `completed == true` → `close()` short-circuits (`:422-424`), no-op.
Exception path → catch already ran `cleanUpOnFailure` (generator nulled, temp deleted) →
`close()` re-runs the temp `deleteIfExists` (no-op) with warn-only error handling
(`:426-444`) — cannot mask the primary. Error path (OOM/AssertionError) → catch skipped →
`close()` aborts: closes generator, deletes temp, never renames — the CQ21 window closed.

## 8. CQ22 + CS61 (suggestion) — ctor cleanup after CREATE_NEW — **VERIFIED**

`DatabaseExport.java:145-171`: the ctor tail after the `CREATE_NEW` open is wrapped;
`catch (IOException | RuntimeException e)` closes `tempOut` and deletes the temp, attaching
both secondaries as suppressed, then rethrows. The re-wrapped gzip/generator construction is
byte-identical to the Step-4 original (checked against `2433d684ae` — nothing lost in the
re-application). The streaming-variant ctor is untouched (no temp to clean).

## 9. CS62 (suggestion) — completed=true moved AFTER promote() — **VERIFIED**

`DatabaseExport.java:226-234` (`promote(); completed = true;`) with the adjacent comment
updated to the new ordering. Close-gating contract sweep (the charter's specific worry):

| State | close() behavior | Verdict |
|---|---|---|
| Success (file variant): temp renamed, flag set | short-circuit no-op (`:422-424`) | unchanged, correct |
| Success (streaming): `tempFileName == null`, promote no-op (`:266-268`), flag set | no-op | unchanged |
| Promote fails **before** rename (source fsync / move) | flag unset → catch → cleanup deletes temp → finally close() retries delete (no-op) | the CS62 remedy exactly: retry now possible |
| Promote fails **after** rename (dir-fsync propagation, new with BG18) | flag unset; temp already renamed away — both cleanup deletes are no-ops on a nonexistent temp; final name = new complete dump; never re-touched (`close()` deletes temp only, never renames `:433-443`) | safe; see observation O2 |
| Failure before promote | as before + close() retry | correct |
| Error mid-export | finally close() aborts (CQ21) | correct |
| External `close()` after completed export | no-op | contract preserved |

No state exists where a COMPLETED export's close misbehaves (the flag is set only after a
successful promote, at which point the temp is gone and close() is gated off), and no failed
promote leaves a wrong state. The unflagged-close pin (`unflaggedCloseNeverRenames`,
DEHTest:352-370) still holds against the reordered flag (the flag is never set in that test).

## 10. CS60 (suggestion) — javadoc contract — **VERIFIED**

`FileUtils.java:343-346` — the same-directory contract note, verbatim per the ask (target
parent only; cross-directory callers need a source-parent fsync). Covered under §1.

## 11. TQ21 (suggestion) — reasoned disposition, no code — **VERIFIED**

track-8.md:886-888: "TQ21 — accepted: `promote()`'s routing through `durableAtomicMove` is a
single reviewed line; the fsync legs are black-box unobservable and no clean structural seam
exists without weakening encapsulation — review-pinned." The single line is
`DatabaseExport.java:270`; no code change, as approved. Recorded.

## 12. CS59 (suggestion) — FM-M18 row + Step 6 WI3 threading — **VERIFIED**

- FM-M18 row present in the drafts' failure-mode table
  (`track-8-design-drafts.md:457`): names both orphan classes (`<final>.<uuid>.tmp`,
  `ytdb-export-record-*.spill`), the ACCEPTED+DOCUMENTED disposition, the fail-safe argument
  (never promoted, never mistaken for a dump, unique non-dump suffixes), and the WI3 runbook
  pointer. Accurate against the code (temp name `DatabaseExport.java:141`; spill prefix in
  `SpillableRecordBuffer`; spills land beside the dump for the file variant and in
  `java.io.tmpdir` for streaming — the row's generic phrasing covers both).
- Step 6 WI3 plan item (`track-8.md:498-500`): "plus, per Step-4 review CS59 (FM-M18):
  crash-orphaned export temp files … and record spill files … are fail-safe residue an
  operator may delete at any time" — threaded exactly where the ask pointed (alongside CN59).

## 13. Special attention — DatabaseExport.java re-application audit — **CLEAN**

The checkout mishap could only have damaged the *fix* edits (the Step-4 baseline
`2433d684ae` is committed and immutable). Audit of `git diff 2433d684ae..35c461d726` on
`DatabaseExport.java`, hunk by hunk, against the approved remedy list:

| Expected fix hunk | Present? | Half-restoration check |
|---|---|---|
| `import java.util.Iterator` | ✓ | used only by the seam |
| ctor try/catch cleanup (CQ22/CS61) | ✓ | re-wrapped gzip/generator block byte-identical to 2433d684ae's |
| flag-after-promote + comment rewrite (CS62) | ✓ | old `completed = true;` removed, new one after `promote()`; comment matches new order |
| `finally { close(); }` (CQ21) | ✓ | with the belt comment |
| `browseCollectionRecords` call + seam (TQ19) | ✓ | seam body == old inline call; scan loop otherwise untouched |

No other hunks exist — nothing unrelated rode in, nothing was dropped. Survival of the
ORIGINAL Step-4 content re-verified in the current file: CS41 no-upfront-delete ctor
(`:128-133`), unique CREATE_NEW temp (`:141-143`), manifest tallies adjacent to write sites
(`manifestIndexes++` `:560`, `manifestClasses++` `:632`, `recordExported++` `:784` after
`copyRawValue`, `manifestBrokenRids` from the written set), bestEffort parsing + fail-fast
default + whole-record discard (`:462-463`, `:749-778`), `renderRecord` seam (`:809`),
`SpillableRecordBuffer` twr (`:749`), schema `version` field (`:608`), completion-gated
warn-only `close()` (`:421-445`), manifest-last ordering (`:210-216`), fail-closed
`promote()` via `durableAtomicMove` (`:265-271`). All present; nothing half-restored.

## 14. Scope check + record-commit coherence — **CLEAN**

- `35c461d726` touches exactly 6 code files; every hunk maps to a finding (FileUtils →
  BG18/CS58/CS60; DatabaseExport → TQ19a/CQ21/CQ22/CS61/CS62; DatabaseImport → BG19; VGZ →
  CQ19/CQ20; DEHTest → TQ19a; VGZTest → TQ20). No production file lacking a finding was
  touched (GlobalConfiguration, SpillableRecordBuffer, FileUtilsDurableAtomicMoveTest
  untouched — correct: no findings against them). No doc changes in the code commit.
- `feab05691e` touches exactly the 2 plan docs; no code. Checklist entry added
  (track-8.md:69-71) consistent with the episode.
- Episode numbers cross-check: DEHTest now has 8 `@Test` methods, VGZTest 10, matching
  "hardening 8, primitive 10"; test-count arithmetic 17490 (Step 4) + 2 post-battery
  FileUtils + 2 new discrimination tests = 17494 as recorded; both discrimination-proof RED
  signatures match the committed assertion/fail messages verbatim; coverage/IT-waiver
  reasoning recorded ("failure-path handling only") is consistent with the diff content
  (all six code changes are failure-path or type-widening; the seam refactor is
  behavior-identical in production).

## 15. Observations (no RG findings)

- **O1** — `FileUtils.java:370-372`: a directory-channel `close()` failure after a
  *successful* `force(true)` now propagates (previously swallowed by the broad catch).
  Stricter/fail-closed in an exotic corner; consistent with the remedy's intent; not a
  breakage.
- **O2** — BG18×CS62 sub-path: a propagating post-rename dir-fsync failure reports the
  export failed while the final name already holds the new complete, content-fsynced dump
  (old-XOR-new under power loss). Fail-safe direction (false negative; a retry converges).
  The crash-safety review's remedy prose "previous dump intact" does not hold here, but no
  committed artifact claims it — no record correction required.
- **O3** — `ValidatedGZIPInputStream.java:70-80`: an `Error` during `readHeader` bypasses
  the CQ19 catch and leaks the inflater to the Cleaner — JDK-parity, matches the approved
  remedy's scope.
- **O4** — BG19 tag-order edge verified safe: `manifest` before `info` is rejected
  (`exporterVersion` default `-1`).

## Hypothesis log

| # | Hypothesis | Outcome |
|---|---|---|
| H1 | The twr alias reintroduces swallowing (close suppressing force) | rejected — twr suppresses close ONTO force's exception; force failure always propagates |
| H2 | TQ19's injection could be caught by the per-record render arm | rejected — `next()` fires at `:356`, outside `exportRecord`'s render try (`:749-778`) |
| H3 | TQ19's test passes via a path other than a loud abort | rejected — pass requires identity of `injected` in the thrown chain + intact sentinel; swallow-revert yields success+promote → assertNotNull red |
| H4 | Step (2) could catch the TQ20 fixture (voiding "arithmetic alone") | rejected — `readAheadResidue == remaining - min(remaining,8) == 0` for `remaining ≤ 1`; garbage never read post-trailer |
| H5 | BG19 gate breaks importing the exporter's own dumps | rejected — `EXPORTER_VERSION = 15`, info written first |
| H6 | `Iterator<RecordAbstract>` seam narrows away a needed API | rejected — loop uses hasNext/next only; `RecordIteratorCollection implements Iterator<REC>` |
| H7 | CS62 reorder lets a completed export's close() misbehave | rejected — flag set only post-promote; close gated; sweep in §9 |
| H8 | finally-close masks the primary on the failure path | rejected — close() is warn-only (`:426-444`); catch throws before finally can replace anything |
| H9 | The re-application lost a Step-4 hunk in DatabaseExport | rejected — §13 feature-by-feature survival audit |
| H10 | Episode's corrected red signature doesn't match committed artifacts | rejected — matches DEHTest:94-95 message verbatim; matcher explanation consistent with the committed test's collection-name (not class-name) matcher |

## Compact verdict block

| ID | Verdict | Evidence gist |
|---|---|---|
| BG18+CS58 | VERIFIED | catch spans open only (FileUtils.java:355-365); force in uncaught twr (:367-373); twr suppression preserves force as primary; javadoc narrow carve-out + CS60 contract (:334-346) |
| TQ19 (a) | VERIFIED | seam :800-802; injection at `it.next()` :356 inside the scan try, lands in the always-rethrow arm :366-380; swallow-revert structurally flips assertNotNull (DEHTest:161); recorded RED signature == committed message |
| TQ19 (b) | VERIFIED | track-8.md:863-873 supersedes the Step-4 paragraph with the FM-M5 primary-cause-masking signature (== DEHTest:94-95) + the class-name-matcher/never-fired explanation |
| TQ20 | VERIFIED | bufferSize 1 ⇒ readAheadResidue ≡ 0 (VGZ:266, :129) — step (2) structurally cannot fire; only :142-150 arithmetic rejects; neuter flips `fail` at VGZTest:178 |
| BG19 | VERIFIED | gate `exporterVersion >= 15` (DatabaseImport.java:246); ≤14/pre-info dumps get byte-identical unsupported-tag rejection; v15 self-dumps pass (EXPORTER_VERSION=15) |
| CQ19 | VERIFIED | ctor catch → `inf.end()` → rethrow (VGZ:70-80) |
| CQ20 | VERIFIED | long headerLength/count/zero-terminated (VGZ:54, :174-176, :236-244); no external getHeaderLength caller broken |
| CQ21 | VERIFIED | `finally { close(); }` (DatabaseExport.java:250-255); close completion-gated + warn-only, cannot promote or mask |
| CQ22+CS61 | VERIFIED | ctor tail catch closes tempOut + deletes temp, suppressed secondaries (:145-171); re-wrapped block byte-identical to Step-4 original |
| CS62 | VERIFIED | `promote(); completed = true;` (:233-234); full close/promote state sweep (§9) — no completed-close misbehavior, failed promote retryable |
| CS60 | VERIFIED | contract note in javadoc (FileUtils.java:343-346) |
| TQ21 | VERIFIED | disposition recorded, track-8.md:886-888; no code, as approved |
| CS59 | VERIFIED | FM-M18 row (design-drafts:457) + WI3 threading (track-8.md:498-500), both accurate |

**RG findings: none.** Scope clean, record-commit separation clean, episode numbers and
proof signatures coherent, DatabaseExport re-application audit clean. Gate outcome: **all 13
findings VERIFIED; nothing STILL OPEN, nothing REJECTED, nothing MOOT.**
