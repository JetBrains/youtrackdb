# Code-baseline review — Track 8 Step 4, iteration 1

- **Commit under review:** `2433d684ae` ("Harden database export and add validated-gzip
  primitive") on branch `transactional-schema`; repo HEAD at review time `1f662e0146` (differs
  from the review commit only by the plan-doc episode entry — verified via
  `git diff 2433d684ae HEAD --stat`: `track-8.md` only, so all file:line citations below are
  valid against both the commit and the working tree).
- **Binding spec:** `track-8.md` Step 4 (plan lines 394-443) + `track-8-design-drafts.md` M2.a
  as amended (CS40/CS41/CS43/CN51/CN52, ruling Q-M1, SR1/SR2 context), test pins M.5
  #1/#2/#8/#17 + the CS43 primitive suite.
- **Perspective:** code baseline. Finding IDs: BG from BG18, CQ from CQ19, TQ from TQ19.
- **Method:** read-only; no Maven, no file modification. All behavioral claims are code-trace
  based; where a claim depends on JDK/Jackson library semantics it is marked
  **[library semantics]** and the exact library behavior relied on is stated so it can be
  falsified cheaply.

## Review criteria (charter) and premises

Criteria:

- **C1** — ValidatedGZIPInputStream: every malformed-input class is rejected/handled per the
  CS43 contract; no infinite loop, no OOM amplification, no silent acceptance; both read paths
  and the verify methods behave at edges.
- **C2** — SpillableRecordBuffer: the spill file is deleted on every path; the
  AUTO_CLOSE_TARGET arrangement is correct and complete.
- **C3** — Export flow: bestEffort = whole-record discard per the design's opt-out ruling;
  manifest counts = what was actually written (tally-site vs write-site trace, DDL
  consistency); completion flag set strictly after manifest + stream close; schema `version`
  field change has no consumer fallout; the skipManifest deviation is minimal and
  non-weakening.
- **C4** — Test quality: the 22 new tests pin the properties and fail on regression;
  red-first fidelity of pin M.5 #1.

Numbered premises (each verified in the codebase unless marked):

- **P1** `ValidatedGZIPInputStream` extends `InflaterInputStream` with `new Inflater(true)`
  (raw deflate) and parses the RFC 1952 framing itself
  (`ValidatedGZIPInputStream.java:69-73, 162-220`).
- **P2** `InflaterInputStream.read()` (no-arg) and `skip(long)` both delegate to the virtual
  `read(byte[], int, int)` **[library semantics — JDK 21
  `InflaterInputStream.read()`/`skip()` implementations]**, so the single override at
  `ValidatedGZIPInputStream.java:75-89` covers all decompressed read paths (CRC updated,
  trailer handling uniform).
- **P3** `InflaterInputStream.fill()` throws `EOFException("Unexpected end of ZLIB input
  stream")` on raw EOF, and `read` throws `ZipException` on `DataFormatException`
  **[library semantics]** — truncated/corrupt deflate is loud during the drain.
- **P4** After `inf.finished()`, the unconsumed read-ahead bytes sit at
  `buf[len - inf.getRemaining() .. len)` (the last `fill()` did `inf.setInput(buf, 0, len)`)
  — the trailer extraction at `ValidatedGZIPInputStream.java:237-252` mirrors the JDK
  `GZIPInputStream.readTrailer` layout arithmetic **[library semantics]**.
- **P5** `JsonGenerator.close()` with `AUTO_CLOSE_TARGET=false` and `FLUSH_PASSED_TO_STREAM`
  (default on) flushes but does not close the underlying Writer; `OutputStreamWriter.flush()`
  drains the encoder's buffered bytes **[library semantics]** — the code comment at
  `DatabaseExport.java:727-732` states exactly this contract.
- **P6** Jackson's `writeEndObject()` throws `JsonGenerationException` (an `IOException`
  subtype) when the current context is an array **[library semantics — used only in the
  red-first fidelity analysis, TQ19]**.
- **P7** `DatabaseSessionEmbedded.executeInTx` re-throws the lambda's exception (rollback via
  `finally { finishTx(ok) }`, no swallow) — `DatabaseSessionEmbedded.java:5135-5151`.
- **P8** `JSONReader.NEXT_IN_OBJECT = {',', '}'}` (`JSONReader.java:47`), so
  `skipManifest()`'s trailing `readNext(NEXT_IN_OBJECT)` accepts the root `}` after the last
  section.
- **P9** `fileName` is an inherited mutable field (`DatabaseImpExpAbstract.java:32`), so the
  ctor's `fileName += ".gz"` (`DatabaseExport.java:122-124`) is visible to `promote()`
  (`DatabaseExport.java:240-246`).
- **P10** `FileChannel.open(dir, READ)` succeeds on POSIX and `force(true)` maps to
  fsync-on-directory-fd; on Windows the open throws (`AccessDeniedException`)
  **[library semantics — the standard Java dir-fsync idiom]**.

---

## 1. ValidatedGZIPInputStream — adversarial input sweep (C1)

Hand-rolled RFC 1952 parsing, so the sweep is enumerated exhaustively. "Loud" = IOException
subtype before any caller can consume trusted output past the corruption.

| # | Adversarial input | Code path | Verdict | Evidence |
|---|---|---|---|---|
| 1 | Empty stream (0 bytes) | ctor → `readHeaderUByte` → `in.read() == -1` | loud `EOFException("Truncated GZIP header")` at construction | `ValidatedGZIPInputStream.java:207-210` |
| 2 | Truncation mid-magic / mid-fixed-header (bytes 1..9) | same | loud EOF at construction | :207-210 |
| 3 | Wrong magic | `!= GZIP_MAGIC` | loud `ZipException("Not in GZIP format")` | :166-168; tested `VGZTest.java:187-197` |
| 4 | CM ≠ 8 | method check | loud `ZipException("Unsupported compression method")` | :170-172 (no test — see TQ notes) |
| 5 | FEXTRA: truncated length or payload | byte loop, `extraLength ≤ 65535` | loud EOF; bounded (no buffering, no OOM) | :181-187 |
| 6 | FNAME/FCOMMENT unterminated | `readHeaderZeroTerminated` loops until `0` or EOF | loud EOF; **no infinite loop** (every iteration consumes a source byte, EOF throws); **no OOM** (bytes counted, never stored) | :222-230 |
| 7 | FNAME/FCOMMENT adversarially > 2 GiB | `length`/`count` are `int` | wraps — see **CQ20** (no loop/OOM; only the step-(3) arithmetic corrupted, and only after the source actually streams > 2 GiB of header) | :162-201, 222-230 |
| 8 | FHCRC mismatch | CRC16 = low 16 bits of CRC32 over all prior header bytes (CRC16 field itself excluded via the `null` sink) — RFC-correct | loud `ZipException("header CRC mismatch")` | :195-201 (no test — minor) |
| 9 | Reserved flag bits set | not validated | tolerated — **JDK `GZIPInputStream` parity**; null verdict (not a weakening vs the design's baseline) | :175 |
| 10 | Truncation mid-deflate | `fill()` EOF | loud EOF during drain (P3) | tested `VGZTest.java:67-80` |
| 11 | Corrupt deflate bytes | `DataFormatException` → `ZipException` | loud during drain (P3) | inherited |
| 12 | Truncation mid-trailer | `in.read` < 0 in trailer loop | loud `EOFException("Truncated GZIP trailer")` | :243-249; tested `VGZTest.java:83-97` |
| 13 | Trailer CRC32 mismatch | `declaredCrc != crc.getValue()` | loud `ZipException("CRC32 mismatch")` | :255-258; tested :153-167 |
| 14 | Trailer ISIZE mismatch | compared against `inf.getBytesWritten() & 0xffffffffL` — correct mod-2³² per RFC 1952 | loud `ZipException("uncompressed-size mismatch")` | :259-262; tested :170-184 |
| 15 | ISIZE "correct mod 2³²" for a ≥ 4 GiB payload | inherent RFC limitation, JDK parity; CRC32 still guards content | null verdict (not reachable as *silent corruption acceptance* — CRC must also collide) | :259-262 |
| 16 | Trailing garbage inside the read-ahead window | `readAheadResidue > 0` | loud at `verifyFullyConsumed()` step (2) | :121-125, 252; tested :103-123 |
| 17 | Trailing garbage beyond the read-ahead window, pure stream | **not detectable** by steps (1)+(2) | documented limitation — WI10a is Step 5's explicit obligation (`track-8.md:826-828`); step (3) covers seekable sources | :134-142 |
| 18 | Second member present (multi-member) | first trailer verified, `read` stays `-1` (no next-member probe by construction) | loud at step (2) (in-window) or step (3) (beyond window) | :75-78, 121-125; tested :126-151 — **but see TQ20**: the step-(3)-only case is never exercised |
| 19 | Second member exactly at window boundary | same as 17/18 — beyond-window residue is step (3)'s job | consistent | — |
| 20 | Zero-length member (empty payload) | first `read` → `-1` → trailer verify with CRC(∅)=0, ISIZE=0 | accepted (correct); untested — minor | :75-89 |
| 21 | read() single-byte path | delegates to the override (P2) | CRC maintained, trailer handled | :75-89 |
| 22 | `read` after trailer verified | returns `-1` immediately; **no re-probe, no loop** | correct | :76-78 |
| 23 | `read` retry after a trailer-verify exception | `trailerVerified` stays false → `readTrailer` re-runs against already-advanced source → garbage trailer or EOF → loud again | no silent path | :237-263 |
| 24 | `verifyFullyConsumed` before any read / before EOF | loud `ZipException(...draining...)` — sequence order enforced | :111-116; tested :203-213 |
| 25 | `verifyPhysicalSize` before drain | delegates to `verifyFullyConsumed` → loud | :134-136 |
| 26 | `verifyPhysicalSize` with wrong size on a valid stream | `consumed != physicalSize` → loud | :136-141 (negative case untested — folded into TQ20) |
| 27 | `inf.needsDictionary()` `-1` path (raw-deflate-with-dictionary, not producible by gzip) | `readTrailer` runs unfinished → trailer bytes misattributed → CRC/EOF failure | loud either way, never silent | :80-88 |
| 28 | Exactly-boundary sizes (`physicalSize == headerLen + deflate + 8`) | equality accepted | correct; positive case tested (`VGZTest.java:55-64` asserts `getCompressedBytesConsumed() == compressed.length`) | :136-141 |
| 29 | OOM amplification | fixed 16 KiB buffer + 8-byte trailer; header bytes never buffered | none | :45, 238 |
| 30 | Pathological `InputStream` returning 0 from `read(byte[])` | inherited `fill()` loop behavior | JDK-inherited, not introduced by this class — null verdict | — |
| 31 | Inflater native-memory lifecycle | `close()` ends the self-allocated inflater; ctor-failure path does **not** | see **CQ19** | :91-99 vs :69-73 |

**Verdict on C1:** the primitive implements the pinned CS43 sequence correctly; the trailer
extraction arithmetic (P4) matches the JDK reference layout; no malformed input in the sweep
is silently accepted within the contract's declared scope (row 17 is the design-documented
WI10a stream-scope limitation, not a defect). Residual findings: CQ19, CQ20 (both
suggestions), TQ20 (test gap on step (3)'s negative arm).

## 2. SpillableRecordBuffer — lifecycle (C2)

Path enumeration (spill file must never survive the buffer):

| # | Path | Trace | Verdict |
|---|---|---|---|
| 1 | Normal, in-memory (≤ threshold) | no spill file ever created (`ensureCapacity` strict `>` at `SpillableRecordBuffer.java:66-74`); `close()` no-ops on files | clean; tested `SRBTest.java:54-65` |
| 2 | Normal, spilled + copy-out | `openContent()` closes `spillOut` (:91-96); caller's try-with-resources → `close()` deletes (:101-128) | clean; tested :69-80 |
| 3 | Exception mid-write (render failure) | caller's twr closes → `spillOut.close()` + `deleteIfExists` (:104-120) | clean; discard path tested :102-111 |
| 4 | Exception mid-copy-out | reader twr in `copyRawValue` closes the content stream (`DatabaseExport.java:787-796`); buffer twr then deletes; NIO streams open with `FILE_SHARE_DELETE` on Windows so the delete succeeds even if racing an open handle **[library semantics]** | clean |
| 5 | Double-close | fields nulled inside `close()` (:109, :121) → second close no-op | clean |
| 6 | Spill-file **creation** failure | `createTempFile` throws before `spillFile` is assigned → nothing to delete; `newOutputStream` throws after → `spillFile` set, `spillOut` null → `close()` still deletes (:113-120) | clean (untested — minor, noted in §4) |
| 7 | `memory.writeTo(spillOut)` failure mid-migration | `spillFile`+`spillOut` set → `close()` closes + deletes; `memory` not yet nulled but caller aborts (whole-or-fatal) | clean |
| 8 | `spillOut.close()` failure inside `close()` | captured as `failure`, delete still attempted, secondary suppressed (:103-126) | clean, primary-preserving |
| 9 | Crash/kill mid-export | file remains until process death — same class as the `.tmp` dump residue; design accepts (no promote, unique names) | out of scope (crash residue ≠ lifecycle leak) |

Boundary semantics: `size + incoming > spillThreshold` — exactly-at-threshold stays in memory,
+1 spills (`:66-67`), matching the Q-M1 pin and tested at both the array (`SRBTest.java:54-80`)
and single-byte (`:84-96`) granularity. `size` is `long`, so no overflow at the comparison.

**AUTO_CLOSE_TARGET (charter question).** Disabling it is *correct*: without it,
`recordGenerator.close()` (the twr at `DatabaseExport.java:733-735`) would close the
`OutputStreamWriter` → close the buffer → **delete the spill file before the copy-out**,
breaking path 2. It is *complete*:

1. The flush guarantee holds via P5 (buffer holds the complete rendering after the generator
   twr exits).
2. The only other generator↔buffer coupling is `AUTO_CLOSE_JSON_CONTENT` (default on):
   on a render failure the generator's close auto-completes open JSON *into the buffer*, which
   is then discarded whole — harmless by construction.
3. The orphaned `OutputStreamWriter` (never closed) wraps only the in-memory/spill buffer that
   the twr does close — no OS resource is held by the writer itself.
4. The main dump generator keeps `AUTO_CLOSE_TARGET` default-on deliberately — its close at
   `DatabaseExport.java:211` must cascade into the gzip trailer + file close before
   `completed = true`; verified below (C3).

**Verdict on C2:** delete-on-every-path holds on all enumerated paths; the AUTO_CLOSE_TARGET
arrangement is correct and complete. Minor test gaps only (§4).

## 3. Export flow correctness (C3)

### 3a. bestEffort semantics vs the opt-out ruling

- Default per-record arm: render failure → `DatabaseExportException` naming
  `-bestEffort=true` (`DatabaseExport.java:739-751`) — fail-fast default per M2.a-2. ✓
- Opt-out: record discarded WHOLE (the buffer, never the shared stream, held the partial
  JSON — `:712-737`), RID into `brokenRids` (`:752`), diagnostic log hardened against a
  second `toStream()` failure (`:800-822`). ✓
- Marker: `"best-effort": true` written iff the mode is on (`:505-509`) — records the
  *choice*, as the design words it ("the choice is recorded as a scalar marker"), i.e. even a
  zero-skip best-effort run carries it. Consistent with the ack-gate design (M2.b-4). ✓
- Scope: the opt-out affects the per-record arm ONLY. The collection-scan arm always rethrows
  (`:338-360`), and the copy-out is whole-or-fatal in both modes (`copyRawValue` at `:753-757`
  sits outside the render-failure catch). Matches "restores skip-and-continue for the
  per-record arm only" + "A copy-out I/O failure is whole-or-fatal". ✓
- Option plumbing: `-bestEffort` parsed in `DatabaseExport.parseSetting` (`:432-439`);
  `DatabaseImpExpAbstract` untouched — packing premise preserved (recorded deviation (d)). ✓

### 3b. Manifest counts = what was actually written (CN51 trace)

Tally-site vs write-site, exhaustively:

| Field | Write site | Tally site | Coupled? |
|---|---|---|---|
| `classes` | class object `writeStartObject` in `exportSchema` loop (`DatabaseExport.java:606`) | `manifestClasses++` immediately adjacent (`:607`) | ✓ same loop iteration; snapshot-scoped (`:578`), never re-derived |
| `indexes` | index object `writeStartObject` (`:534`) after the `EXPORT_IMPORT_CLASS_NAME` skip (`continue` precedes the tally) | `manifestIndexes++` (`:535`) | ✓ skipped indexes not counted |
| `records` | `copyRawValue` success (`:757`) | `recordExported++` (`:759`) — strictly after the copy-out | ✓ discarded/broken records never counted |
| `brokenRids` | the `brokenRids` array write loop (`:381-386`) | `manifestBrokenRids = brokenRids.size()` from the very set just written (`:387`) | ✓ |

A mid-write abort between a tally and the manifest is irrelevant: the manifest is only
reached if every section completed (`exportManifest` is last, `:194-197`), otherwise nothing
promotes. Under concurrent DDL the counts stay dump-relative because no count is re-derived
from a fresh snapshot at manifest time (`exportManifest` reads only the tallies,
`:276-288`) — pinned by the DDL test (`DEHTest.java:328-352`), which *is* discriminating:
the injected DDL fires after `exportSchema`, so a re-derived count would exceed the dump's
`schema.classes` content and fail `assertManifestMatchesContent`.

### 3c. Completion flag and promote gating

- `completed = true` at `DatabaseExport.java:213` — strictly after `writeEndObject()` (:210,
  closing brace after the manifest) and `jsonGenerator.close()` (:211, which cascades through
  the gzip trailer flush and the CREATE_NEW stream close via AUTO_CLOSE_TARGET default-on).
  The durability fsync correctly comes *after* (inside `durableAtomicMove`, per the CS40
  recipe: reopened channel `force(true)` → `ATOMIC_MOVE`+`REPLACE_EXISTING` → parent fsync;
  `FileUtils.java:338-353`). ✓
- Failure anywhere earlier routes through the single catch (`:215-230`): primary preserved
  (already-wrapped `DatabaseExportException` not re-wrapped, `:221-227`), cleanup secondaries
  suppressed (`cleanUpOnFailure`, `:252-268`), temp deleted, final name never touched. ✓
- `promote()` failure after `completed = true`: caught by the same catch; temp deleted;
  `close()` later no-ops on `completed` — consistent, nothing dangles. ✓
- `close()` without completion: closes the generator (abort-tolerant warn), deletes the unique
  temp, never renames (`:396-419`). ✓ Streaming variant: `tempFileName == null` → `promote()`
  no-op (`:241-243`); manifest-as-completion-marker per FM-M13. ✓
- Constructor: no upfront final-name delete (both `prepareForFileCreationOrReplacement`
  calls removed; `Files.createDirectories(parent)` preserves the old dir-creation side
  effect, `:127-131`); temp = `<final>.<uuid>.tmp` in the same directory (same volume — an
  `ATOMIC_MOVE` precondition) opened `CREATE_NEW` (`:139-141`). ✓ CS41 + CN52 as specified.
- Residual windows (both below should-fix threshold, recorded as CQ21/CQ22): an `Error`
  bypasses the `catch (Exception)` and there is no `finally` — cleanup then depends on the
  caller invoking `close()`; and a ctor failure after CREATE_NEW (gzip-header write) leaves
  an orphan temp no caller can clean.

### 3d. Schema `version` field blast radius

`exportSchema` now writes `SchemaShared.CURRENT_VERSION_NUMBER` (`DatabaseExport.java:580-583`).
Readers of that field: exactly one in-repo — `DatabaseImport.importSchema` reads it into an
`@SuppressWarnings("unused")` local and discards it (`DatabaseImport.java:520-525`). Grep for
other consumers of the schema-section `"version"` and of `schema.getVersion()` in main source:
none. `DatabaseCompare` compares live databases, not dumps. The design's compatibility claim
("import never consumed the old value") is verified. **Null verdict — no fallout.**

### 3e. skipManifest deviation — minimal and non-weakening?

- Minimal: yes — one switch arm (`DatabaseImport.java:236-244`) + a 10-line consumer
  (`:301-311`) using the same `while lastChar != '}'` / `NEXT_IN_OBJECT` idiom as
  `importInfo` (`:424-441`), P8 confirms the trailing token handling; a malformed manifest
  fails through the existing `ParseException` machinery (loud). No preamble, dispatch, or
  deferral surface touched — Step 5's seam is intact.
- Non-weakening: **not strictly.** At HEAD, tag `manifest` threw "unsupported tag" for every
  dump; now it is consumed silently for ALL declared versions, including `<= 14`, where the
  design demands byte-for-byte preservation of the lenient path. The affected population is
  only hand-crafted legacy dumps carrying a tag no v14 exporter ever wrote, and Step 5's WI6
  obligation (version-gate the arm to `>= 15`) closes it — but the gate is currently only
  implied by the deviation note, and a silent-consumption arm is exactly the kind of residue
  that survives if Step 5's scope slips. Recorded as **BG19** (suggestion) so it is threaded
  as an explicit Step-5 obligation rather than prose.

### 3f. durableAtomicMove (CS40 recipe)

Recipe order verified (`FileUtils.java:338-353`): file `force(true)` through a fresh channel →
`ATOMIC_MOVE`+`REPLACE_EXISTING` → parent-dir fsync; **no copy fallback** (fail-closed — an
`AtomicMoveNotSupportedException` propagates). One deviation: the parent-directory fsync catch
(`:346-352`) treats *every* `IOException` — including a genuine `force()` failure (EIO) on a
platform where the directory channel opened fine — as "the platform does not support directory
channels" and continues. CS40's amendment scopes best-effort to "where the platform cannot
open a directory channel" (P10: on POSIX the open succeeds and `force` is the real fsync).
**BG18, should-fix.**

## 4. Test quality (C4)

22 new tests (7 + 9 + 4 + 2, matching the episode's inventory). Discriminating-power check
per pin:

- **M.5 #1** (`DEHTest.java:52-103`): pins loud abort + primary cause + sentinel preservation
  + zero residue. Discriminating against: restored upfront delete (sentinel assertion fails),
  restored unconditional promote (sentinel fails), primary masking by close secondaries
  (cause-chain assertion fails). **Not discriminating against the FM-M1 swallow itself** —
  see TQ19: the injection seam (listener `onMessage` at `DatabaseExport.java:317-322`,
  filter `DEHTest.java:71`) sits *outside* the per-collection try (`:327`), so the thrown
  exception never traverses the always-rethrow arm (`:341-360`). No test in the suite drives
  an exception through that arm (the render-failure tests ride the dedicated
  `catch (DatabaseExportException) → rethrow` arm at `:338-340`).
- **M.5 #2 default** (`:141-177`): discriminating — reverting to skip-and-continue makes
  `exportDatabase` succeed → `assertNotNull(thrown)` fails; also pins the opt-out-naming
  message and no-promote/no-residue.
- **M.5 #2 best-effort** (`:180-223`): discriminating — pins whole-record absence, brokenRids
  content, the info marker, and manifest-vs-content equality in one dump.
- **M.5 #8** (`:225-265`): pins whole-record presence at a forced 1 KiB threshold + zero
  residue; spill *mechanics* are pinned separately in `SRBTest` (boundary both sides,
  single-byte crossing, round-trip, discard-path deletion) — adequate split.
- **M.5 #17** (`:267-352`): unflagged close (discriminating — the old promoting `close()`
  fails the sentinel assertion); CN52 (discriminating — a fixed temp name makes the second
  ctor's CREATE_NEW throw `FileAlreadyExistsException`); DDL manifest self-consistency
  (discriminating per §3b). The exporters run sequentially — acceptable: the CN52 mechanism
  under test is ctor-time name uniqueness, which byte-interleaving cannot survive; noted as a
  conscious shape, not a gap. The "promote path calls the fsync-capable move" clause of pin
  #17 is NOT pinned by any test (fsync unobservable; acknowledged at
  `FileUtilsDurableAtomicMoveTest.java:14-18`) — **TQ21** (suggestion).
- **CS43 primitive suite** (9 tests): covers the pinned sequence's positive case, both
  truncations, in-window trailing garbage, multi-member, both trailer corruptions, non-gzip
  construction, sequence-order enforcement. Gap: step (3)'s size-mismatch rejection is never
  the *failing* check in any test (**TQ20**); untested minor arms: CM≠8, FHCRC, empty stream,
  zero-length member, spill-file-creation failure (SRB path 6) — all suggestion-grade.
- Every promoted dump in `DEHTest` is re-read through the primitive with the full CS43
  sequence (`parseDump`, `DEHTest.java:129-136`: drain → `verifyPhysicalSize` which folds
  `verifyFullyConsumed`) — good structural reuse that continuously cross-validates exporter
  output against the Step-5 validator.

### Red-first fidelity of M.5 #1 (charter question)

The episode (`track-8.md:757-764`) records the red signature as
`AssertionError: a mid-scan failure must abort the export loudly` with the mechanism "the
listener-injected mid-records-phase failure was swallowed into a success exit". Code-tracing
the *committed* test against the parent commit `bac3747535`'s `DatabaseExport`:

1. The injection fires at the `- Collection '<name>'` listener message — old file `:191-197`,
   which is *outside* the per-collection try (`:201`) and its swallow arm (`:221-239`).
2. By P7 the exception propagates out of `executeInTx`, is wrapped and re-thrown by the outer
   catch (old `:151-157`).
3. The `finally { close() }` (old `:158-159`) then calls `writeEndObject()` inside the open
   `records` array → by P6 a `JsonGenerationException` → old `close()` wraps and throws it
   (`:276-289`) **from the finally, replacing the primary** (FM-M5, not FM-M1).
4. The test therefore catches a non-null `DatabaseExportException` whose cause chain lacks
   the injected exception → the red assertion would be *"the injected scan failure must be
   the export failure's primary cause"* (or, if P6 were wrong and close promoted, the
   sentinel assertion) — in either branch **not** the recorded "abort loudly" signature, and
   in neither branch does the exception ever reach the swallow arm the pin targets.

So the committed test was necessarily red at `bac3747535` (the red-first *discipline* is not
in doubt), but the recorded signature/mechanism could not have been produced by the committed
artifact, and — the substantive half — the committed test does not exercise FM-M1's swallow
arm at all. Folded into **TQ19** (should-fix) with the alternative hypothesis noted: the red
run may have used an earlier test revision with a different injection seam; only re-running
the red experiment can distinguish record-slip from test-drift, and that re-run is cheap.

### Coverage claim

Episode: coverage gate PASSED, 89.5 % line / 82.4 % branch on changed code. Consistent with
the one uncovered production arm this review found (the generic scan-wrap arm,
`DatabaseExport.java:341-360`); not re-runnable here (no Maven).

---

## Findings

### BG18 — should-fix — `FileUtils.java:344-352`
`durableAtomicMove`'s parent-directory-fsync catch swallows *every* `IOException` under the
"platform does not support directory channels" warning, including a genuine `force()` failure
on a platform where the directory channel opened fine. CS40 (as amended and as restated in
the episode, `track-8.md:783-786`) scopes best-effort to "where the platform cannot OPEN a
directory channel". **Counterexample:** Linux, dump promote succeeds, `fsync(dirfd)` returns
EIO → warning logged, exporter returns success → power loss can drop the rename's directory
entry — the exact second half of FM-M4 the recipe exists to close, behind an exit-0.
**Fix:** nest the try — tolerate (and log accurately) only the `FileChannel.open` failure;
propagate a `force()` failure (fail-closed, matching the recipe's no-fallback stance).

### BG19 — suggestion — `DatabaseImport.java:236-244, 301-311`
The `manifest` switch arm is not version-gated at this step: a declared-`<= 14` dump carrying
a `manifest` tag is now silently consumed where HEAD threw "unsupported tag" — a (small,
temporary, documented) weakening of the lenient path the design pins byte-for-byte.
**Counterexample:** hand-crafted v14 dump + `"manifest":{...}` section → HEAD rejects, this
commit imports silently. **Fix:** carry "WI6 gate restores `>= 15`-only arming AND the `< 15`
unsupported-tag rejection" as an explicit Step-5 obligation (one sentence in the Step 5 plan
item), so the residue cannot outlive the skeleton swap.

### TQ19 — should-fix — `DatabaseExportHardeningTest.java:52-103` / `DatabaseExport.java:341-360` / `track-8.md:757-764`
(a) The always-rethrow collection-scan arm — the direct remedy for FM-M1, the step's headline
defect — has no discriminating test: the M.5 #1 injection rides the listener seam *outside*
the arm, and render failures ride the dedicated `DatabaseExportException` rethrow arm.
**Counterexample:** revert `DatabaseExport.java:341-360` to the old log-and-continue swallow;
all 22 new tests and the step's named suites stay green, while a real `it.next()` failure
again yields a promoted, manifest-bearing, silently-incomplete dump (FM-M1 reintroduced with
a valid manifest — *worse* than HEAD for Step 5's verifier, which will trust manifests).
**Fix:** add a test that makes the scan itself throw (e.g., a listener-independent failing
iterator via a spy session, or a record whose `it.next()` load fails) and assert the wrap +
abort + no-promote. (b) The episode's red-first signature ("swallowed into a success exit",
`AssertionError: ... abort ... loudly`) cannot be produced by the committed test at
`bac3747535` (premises P6/P7; §4 trace) — correct the episode record, or note the earlier
test revision that produced it.

### TQ20 — should-fix — `ValidatedGZIPInputStreamTest.java:126-151` / `ValidatedGZIPInputStream.java:134-142`
CS43 step (3)'s rejection is never the failing check in any test: `multiMemberStreamIsRejected`
reaches `verifyPhysicalSize` only after `verifyFullyConsumed` already throws (16 KiB buffer →
the second member is always in-window read-ahead), and the only other `verifyPhysicalSize`
calls are positive. **Counterexample:** weaken `consumed != physicalSize` to `false` (or
`consumed > physicalSize`) — all 9 primitive tests plus the hardening suite stay green,
silently dropping the seekable-source trailing-garbage belt Step 5 will rely on.
**Fix:** one fixture with a small `bufferSize` (e.g., `new ValidatedGZIPInputStream(in, 1)`)
and garbage appended after the trailer: drain succeeds, `verifyFullyConsumed()` passes
(residue never buffered), `verifyPhysicalSize(totalLen)` must throw the size mismatch.

### CQ19 — suggestion — `ValidatedGZIPInputStream.java:69-73`
On a ctor header-validation failure the self-allocated `Inflater` is never `end()`ed (the
`close()` that would end it is unreachable — construction failed); native memory is reclaimed
only by the Cleaner at GC. The JDK's `GZIPInputStream` shares the flaw, but this class is new,
its consumer is a rejection-heavy validation path, and this codebase audits native memory
(`directMemory.trackMode`). **Fix:** wrap the ctor tail: catch, `inf.end()`, rethrow.

### CQ20 — suggestion — `ValidatedGZIPInputStream.java:162-230`
Header-length accounting is `int`: an adversarial > 2 GiB FNAME/FCOMMENT wraps
`length`/`count`, corrupting `headerLength` and hence the step-(3) arithmetic (a crafted
multi-GiB header could in principle make `consumed == physicalSize` hold with trailing
garbage). No loop/OOM risk (row 6/7 of the sweep), and CRC32+deflate must still validate, so
exploitability is remote. **Fix:** cap name/comment/extra length (e.g., 64 KiB total header)
or account in `long` with an explicit bound.

### CQ21 — suggestion — `DatabaseExport.java:181-231`
`exportDatabase` catches `Exception` only and no longer has a `finally`; a `Throwable` that
is not an `Exception` (OOM, `AssertionError`) skips `cleanUpOnFailure`, leaving the temp file
and open gzip stream to the caller's discipline. Since the new `close()` is completion-gated
(a post-success `close()` no-ops, `:396-399`), restoring `finally { close(); }` is now safe
and would close the Error window for free.

### CQ22 — suggestion — `DatabaseExport.java:139-152`
A ctor failure after the `CREATE_NEW` open (gzip-header write on a full disk,
`createGenerator`, `writeStartObject`) leaks the temp file and stream: the exception leaves
the caller without an object to `close()`. Narrow window; wrap the ctor tail and
delete-on-failure.

### TQ21 — suggestion — `FileUtilsDurableAtomicMoveTest.java:14-18` / `DatabaseExport.java:240-246`
No test pins that `promote()` routes through `durableAtomicMove` (pin #17's first clause):
swapping it for `atomicMoveWithFallback` keeps every test green. The unobservability of the
fsync legs is honestly documented; if a cheap seam exists (e.g., verifying the move helper is
the only `Files.move` reachable from promote via a package-private hook), consider it —
otherwise accept and keep the contract review-pinned.

## Null verdicts (justified)

1. **Gzip sweep rows 9, 15, 20, 30** — reserved-flag tolerance, mod-2³² ISIZE, zero-length
   member, zero-read `fill()` behavior: JDK-parity or RFC-inherent; the design's baseline is
   the JDK decoder, so these are not weakenings.
2. **Schema `version` field** — single in-repo reader discards the value
   (`DatabaseImport.java:520-525`); no other consumer found by grep; change is
   consumer-invisible exactly as M2.a-7 claims.
3. **Manifest tallies** — all four write/tally site pairs verified adjacent and
   snapshot-consistent (§3b); no re-derivation site exists.
4. **Completion-flag placement** — strictly after end-object + generator close (which flushes
   the gzip trailer); fsync deliberately later inside the promote recipe (§3c).
5. **bestEffort semantics** — per-record-arm-only opt-out, whole-record discard, copy-out
   fatal in both modes, marker rides the info section: all per design (§3a).
6. **SpillableRecordBuffer `openContent()` re-invocation NPE** — internal package-private
   class with a single call site (`copyRawValue`) honoring the single-call contract; not
   worth hardening now.
7. **Sequential (not threaded) CN52 test** — the mechanism under test is ctor-time CREATE_NEW
   uniqueness, which the sequential shape pins deterministically (§4).
8. **`concurrent exporters share one session`** — exports run sequentially in the test, so no
   session-safety question arises.
9. **Main-generator default-charset `OutputStreamWriter`** (`DatabaseExport.java:151`) —
   pre-existing; JDK 18+ default charset is UTF-8 and the project floor is JDK 21; the new
   per-record buffer is explicitly UTF-8 both ways, so the chunked char copy is lossless.
10. **Old collection-loop shape** (`for (i; exportedCollections <= maxCollectionId; ++i)`) —
    pre-existing, unmodified by this commit except the catch arms; out of scope.
11. **Dead `-compressionLevel`/`-compressionBuffer` options** (fields consumed in the ctor,
    options parsed after) — pre-existing oddity, unchanged.
12. **`skipManifest` termination** — same reader idiom as `importInfo`; EOF/malformed input
    lands in `ParseException` → loud importer failure.
13. **Temp-file residue after a crash/kill** — accepted by the design (unique names, never
    promoted); not a lifecycle defect.

## Out-of-scope (checked, not reviewed in depth)

- Step 5/6 surfaces: manifest *verification*, ack gate, pre-flight deferral, WI10a stream-ctor
  scope (explicitly deferred obligations; confirmed still recorded at `track-8.md:826-828`).
- `DatabaseImport` beyond the manifest arm; `DatabaseImpExpAbstract` (untouched, verified).
- Concurrency of two exporters over one live session (concurrency perspective's charter).
- Crash-window analysis of the promote recipe beyond the code-level recipe-order check
  (crash-safety perspective's charter; BG18 is reported here because it is a concrete code
  deviation from the pinned spec text, not a new crash model).

## Hypothesis log

| # | Hypothesis | Method | Outcome |
|---|---|---|---|
| H1 | `executeInTx` swallows lambda exceptions (would validate the episode's "success exit") | read `DatabaseSessionEmbedded.java:5135-5151` | rejected — rethrows (P7) |
| H2 | The `- Collection` listener message sits inside the old swallow arm | line-trace old `DatabaseExport.java:191-239` | rejected — outside; grounds TQ19 |
| H3 | Old `close()` could return normally mid-array (making the red signature the sentinel assertion) | Jackson `writeEndObject` context check (P6) | rejected — throws; either way signature ≠ episode's |
| H4 | Single-byte `read()`/`skip()` bypass the CRC/trailer override | JDK `InflaterInputStream` source (P2) | rejected — both delegate |
| H5 | Trailer extraction offset `buf[len - remaining]` misaligns after multiple fills | `fill()` semantics: earlier buffers fully consumed before refill (P4) | rejected — correct |
| H6 | `needsDictionary` gives a silent `-1` acceptance path | traced `readTrailer` on unfinished inflater | rejected — CRC/EOF failure, loud |
| H7 | ISIZE check uses full `long` compare (would false-reject ≥ 4 GiB payloads) | read `:259-262` | rejected — masked mod 2³², RFC-correct |
| H8 | `FileChannel.open(dir, READ)` fails on Linux (making BG18 moot) | P10, standard dir-fsync idiom | rejected — open succeeds on POSIX; BG18 stands |
| H9 | Spill-file delete fails on Windows while the copy-out reader raced close | NIO `FILE_SHARE_DELETE` default (path 4, §2) | rejected — delete succeeds; residual risk nil |
| H10 | The DDL test can't distinguish tallied vs re-derived manifests | timing trace: DDL fires post-`exportSchema` (§3b) | rejected — it discriminates |
| H11 | `AUTO_CLOSE_JSON_CONTENT` could append to the buffer on the success path | Jackson close semantics on complete content | rejected — contexts already closed |
| H12 | The generic scan-wrap arm is covered by some existing suite (voiding TQ19's gap) | grep of new tests + step suites for scan-failure injection | not found — TQ19 stands (coverage 89.5 % line leaves room) |

## Compact findings block

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| BG18 | should-fix | FileUtils.java:344-352 | dir-fsync `force()` failure swallowed as "platform lacks directory channels" — deviates from CS40's open-failure-only best-effort scope | Linux promote + `fsync(dirfd)` EIO → exit-0 export whose rename may vanish on power loss (FM-M4 half-reopened) |
| BG19 | suggestion | DatabaseImport.java:236-244 | `manifest` arm not version-gated this step — declared-≤14 dumps with the tag consumed where HEAD rejected | crafted v14 dump + manifest section imports silently; thread the WI6 re-gating as an explicit Step-5 obligation |
| TQ19 | should-fix | DatabaseExportHardeningTest.java:52-103; DatabaseExport.java:341-360; track-8.md:757-764 | M.5 #1 injects outside the scan arm → the always-rethrow arm has no discriminating test; episode's red signature unreproducible from committed artifacts | revert :341-360 to log-and-continue → whole suite green while FM-M1 returns (now with a *valid manifest*) |
| TQ20 | should-fix | ValidatedGZIPInputStreamTest.java:126-151; ValidatedGZIPInputStream.java:134-142 | CS43 step (3) size-mismatch rejection never the failing check in any test | neuter `consumed != physicalSize` → all 9 primitive tests green; fix = small-buffer fixture with beyond-window garbage |
| CQ19 | suggestion | ValidatedGZIPInputStream.java:69-73 | ctor failure leaks the Inflater's native buffer (no `end()`; Cleaner-only) | repeated malformed-dump rejections defer native frees; catch-end-rethrow in ctor |
| CQ20 | suggestion | ValidatedGZIPInputStream.java:162-230 | `int` header-length arithmetic wraps on >2 GiB FNAME/FCOMMENT, corrupting step-(3) arithmetic | multi-GiB crafted header defeats the physical-size belt (CRC still guards); cap header-field length |
| CQ21 | suggestion | DatabaseExport.java:181-231 | `Error` bypasses `catch(Exception)` cleanup; no `finally` — temp+FD residue unless caller closes | OOM mid-export leaves `.tmp`; `finally { close(); }` is now safe (completion-gated) and free |
| CQ22 | suggestion | DatabaseExport.java:139-152 | ctor failure after CREATE_NEW leaves an uncleanable temp file/stream | disk-full during gzip-header write; delete-on-ctor-failure |
| TQ21 | suggestion | FileUtilsDurableAtomicMoveTest.java:14-18; DatabaseExport.java:240-246 | promote()'s routing through `durableAtomicMove` unpinned by tests (fsync unobservable, acknowledged) | swap in `atomicMoveWithFallback` → suite stays green |

**Blockers: none.** The production logic implements the pinned M2.a design faithfully; the
should-fixes are one durability-edge deviation from the CS40 wording (BG18) and two
test-discrimination gaps on pinned properties (TQ19, TQ20), plus an episode-record fidelity
correction folded into TQ19.
