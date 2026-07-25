# Baseline review — Track 8 Step 5, iteration 1

**Commit under review:** `5173bccd10` ("Harden import: pre-flight deferral, v15 strictness"),
branch `transactional-schema`, HEAD `a0fadb4334`.
**Perspective:** code-baseline (R1 byte-for-byte preservation, structural-strictness
correctness, WI1 mapping, pre-flight deferral completeness, ack gate, test quality).
**Finding ID ranges:** BG from BG20, CQ from CQ23, TQ from TQ22.
**Method:** read-only. Full-file diff of both touched files against parent `8ca99846fc`
(`/tmp` extractions of `DatabaseImport.java` at both revisions), plus supporting reads of
`DatabaseExport.java` (Step-4 state at the same commit), `ValidatedGZIPInputStream.java`,
`JSONReader.java`, `DatabaseSessionEmbedded.java`, `AbstractStorage.java`, and the active
import-test callers. Binding spec: `plan/track-8.md` Step 5 + `plan/track-8-design-drafts.md`
(M2.b as amended: §A2/CS38/WI11, §A3/WI1, SR1/SR2/CS46, Q-M3, WI6, WI10a/b/c, CN51, M2.b-4,
pins M.5 #3/#4/#5/#6/#7/#13/#16 + #11 lenient half). No Maven, no file modification (this
report excepted).

Line references: `DatabaseImport.java` = the file AT `5173bccd10`;
`DatabaseImport.java(parent)` = at `8ca99846fc`. Test = `DatabaseImportHardeningTest.java`
at `5173bccd10`.

---

## 0. Review criteria (semi-formal)

- **C1 (R1 preservation):** for every input a declared-legacy (`<= 14`) dump could present,
  the observable import behavior at `5173bccd10` equals the parent's, EXCEPT deltas the
  amended design explicitly sanctions (SR2 undeclared-version rejection; §A3 blob mapping;
  the recorded as-built notes (b) quote-strip and (c) single-member legacy decoding).
- **C2 (strictness correctness):** the v15 structural matrix rejects every specified corrupt
  shape and accepts every shape the Step-4 exporter can honestly produce (no false positive).
- **C3 (WI1):** dump blob-collection ids are never resolved raw in the target id space, for
  every dump shape (ids present/absent in the collections section, overlapping class ids).
- **C4 (deferral):** the deferred block is exactly parent `:214-:223` inclusive; nothing runs
  early; no code that can execute before the deferral point reads state the block establishes.
- **C5 (ack gate):** best-effort dump without flag → actionable rejection pre-mutation; with
  flag → imports; flag on a non-marked dump → no effect.
- **C6 (tests):** the 15 tests pin the cited design pins, discriminate (matcher-fires), and
  the #13 fixture strategy is sound.

---

## 1. C1 — R1 byte-for-byte preservation of the `<= 14` path

### 1.1 Constructor / framing detection

Premises:

1. Parent file ctor: `mark(1024)` → `new GZIPInputStream(bufferedInputStream, 16384)` →
   on `Exception` reset to plain (`DatabaseImport.java(parent):133-141`). New: identical
   shape via `detectFraming` (`DatabaseImport.java:205-216`), decoder swapped to
   `ValidatedGZIPInputStream`.
2. Both decoders consume header bytes directly from the source, byte-at-a-time, NOT through
   the 16 KB inflater buffer: `ValidatedGZIPInputStream.readHeader` reads via
   `readHeaderUByte` → `in.read()` (`ValidatedGZIPInputStream.java:222-231`); a non-gzip
   input fails at the 2-byte magic check (`:180-182`), a truncated file at the first missing
   byte — both well inside the 1024-byte mark limit, so `reset()` always succeeds and the
   plain fallback is byte-identical to the parent's.
3. For a legacy single-member gzip dump the decoders are read-equivalent: both inflate the
   single deflate stream; both verify the CRC32+ISIZE trailer when (and only when) a read
   reaches the member end (`ValidatedGZIPInputStream.read:86-98`, `readTrailer:246-274` vs
   JDK `GZIPInputStream.readTrailer`); on the legacy path neither `verifyFullyConsumed` nor
   `verifyPhysicalSize` is ever invoked (`DatabaseImport.java:347-349` gates on
   `exporterVersion >= 15`), and trailing garbage after the trailer is tolerated by both
   (JDK: failed next-member probe → EOF; new: `readAheadResidue` recorded but not checked).
4. The single read-observable decoder delta is a MULTI-member source: JDK continues into
   member 2, the new decoder returns `-1` at member 1's verified end (`read:87-89`). No
   exporter (this repo's or the OrientDB-era one) ever produced multi-member dumps; the
   as-built note (c) in track-8.md records exactly this delta. Residual exposure: an
   operator RE-compressing a legacy dump with a multi-member-producing tool (`cat a.gz
   b.gz`, bgzip) — exotic, and it fails LOUDLY (JSON parse failure), not silently. Verdict:
   sanctioned, recorded, fail-loud. No finding.
5. New in the file ctor: `Files.size(Paths.get(fileName))` before `FileInputStream`
   (`DatabaseImport.java:174`). For a missing file the thrown type changes from
   `FileNotFoundException` to `NoSuchFileException` (both `IOException`s; the ctor already
   declared `throws IOException`). Delta is observable to callers catching the narrow type →
   **BG21** (suggestion).
6. The InputStream ctor now sniffs framing and auto-decodes gzip streams
   (`DatabaseImport.java:186-199`); at parent it passed the stream raw. A gzip stream fed to
   the parent ctor produced an immediate JSON-parse failure, so no previously-working caller
   changes behavior; plain streams (all active in-repo callers:
   `DatabaseImportSimpleCompatibilityTest.java:105` with plain `.json` fixtures,
   `DatabaseExportImportRoundTripTest.java:354/439` with plain streaming exports) are
   unaffected — the fallback returns the same buffered stream. The added `throws
   IOException` on the ctor is source-compatible with every in-repo caller (verified by
   grep; the compat test already catches `IOException`). Spec-sanctioned by the WI10a
   resolution. No finding.

### 1.2 Section loop and preamble timing for a legacy dump

Premises:

1. Parent order: `BEGIN_OBJECT` → `setValidationEnabled(false)` → `setUser(null)` →
   preamble block `:214-:223` → loop (`DatabaseImport.java(parent):210-227`). New order:
   same up to the loop; preamble runs inside the `"info"` arm after `importInfo()` +
   `runPreFlightChecks()` (`DatabaseImport.java:304-313`).
2. `importInfo` mutates only importer-local state (`exporterVersion`, `jsonSerializer`,
   `bestEffortDump`; `DatabaseImport.java:685-708`). Nothing between the parent preamble
   site and the first section handler reads the dropped classes, the reloaded index manager,
   `indexesToRebuild`, or the schema snapshot — the consumers are `importCollections`'s
   rebuild loop (`:1227-1232`), `importIndexes`'s `indexesToRebuild.remove` (`:1707`),
   `importRecords`' snapshot parameter (`:298`, field set at `:454`), and the post-loop
   `rebuildIndexes()` (`:350-352`) — all structurally after `info` because SR2 rejects any
   pre-info section tag (`:295-300`) and rejects EOF-without-version (`:337-342`). For a
   well-formed legacy dump (info first — every real exporter, per SR2's ruling premise) the
   preamble therefore runs with identical inputs and in identical internal order
   (`runDeferredImportPreamble`, `:439-455`: drop → reload → auto-index loop → snapshot —
   the parent's exact statement sequence).
3. Sanctioned deltas on hand-damaged legacy inputs: a dump whose first tag is not `info`, or
   that never declares a parseable version, is now rejected (SR2/CS46 — explicitly rules
   these out of the lenient set); a malformed version value throws from
   `readInteger` (`:693`, `Integer.parseInt`) — loud, fail-closed, per SR2's
   malformed-equals-absent extension (WI12a wording lands in Step 6, the outcome is already
   correct).
4. New bookkeeping (`sectionOccurrences.merge` `:301`, `:1589-1596`; parsed counters
   `:574/:870/:1295/:1623`) has zero behavioral effect below v15 — read only inside
   `verifyV15StructuralStrictness` (`:347-349` gate).

### 1.3 brokenRids quote-strip (as-built note (b))

Premises:

1. Parent: `RecordIdInternal.fromString(jsonReader.getValue(), false)` on the raw token
   (`DatabaseImport.java(parent):330`). `JSONReader` keeps quotes in the raw array token
   (confirmed by the recorded red-first signature `For input string: ""#99"` and by the
   reader's buffer semantics, `JSONReader.java:280-298`).
2. Every Jackson-era exporter in this repo's history writes brokenRids QUOTED
   (`jsonGenerator.writeString(rid.toString())` — present already at the pre-Step-4 exporter,
   verified at `2433d684ae^`'s `DatabaseExport.java:261-266`). Consequence: at the parent,
   ANY dump (v14 or v15) with a non-empty brokenRids array failed to import
   (NumberFormatException). The strip therefore cannot change the outcome of any dump that
   previously imported — it only converts a hard parse failure into a successful parse.
3. Unquoted tokens pass through unchanged: the strip is guarded by
   `value.charAt(0) == '"' && value.charAt(value.length()-1) == '"'` with `length() >= 2`
   (`DatabaseImport.java:564-570`); empty tokens (the `[]` shape every real legacy dump has)
   skip both the strip and the tally (`:572-575`) and reach `fromString` exactly as at the
   parent. **C1 holds**; the backward-compat claim is verified. The out-of-order-fix flag is
   fair (this is Step-6-adjacent behavior change riding Step 5), but it was REQUIRED for the
   WI10b fixture to be constructible and is recorded in the episode. The pass-through
   direction is untested → folded into **TQ24** (suggestion).

### 1.4 Blob mapping on the legacy path

The §A3/WI1 rewrite (`DatabaseImport.java:814-841`) applies to all dump versions — a
deliberate, spec-sanctioned exception to C1: FM-M16's victim IS the declared-legacy dump
(pin M.5 #13 uses a v14-layout dump), and the design places the fix on the shared
`importSchema` path. A legacy dump whose blob ids resolve through the collections-section
mapping now registers the same collections it did pre-R3-renumbering (name-keyed target
resolution in `importCollections`, `:1177-1200`); only dumps that previously MISregistered
(the armed FM-M16 window) change behavior. No finding.

### 1.5 Best-effort gate reaches legacy dumps

`runPreFlightChecks`'s ack gate keys on the marker alone (`bestEffortDump &&
!acceptBestEffortDump`, `DatabaseImport.java:422-428`), and `importInfo` reads
`best-effort` for every version (`:697-701`). A declared-v14 dump hand-edited to carry
`"best-effort": true` is now rejected where the parent skipped the unknown field. No real
legacy exporter writes the marker, so no honest legacy dump is affected; R1's own wording
("the ack gate keys off the v15 best-effort marker") supports marker-keying, while the R1
§0 bullet lists the gate inside the `>= 15` strict matrix — a genuine (if microscopic)
letter tension, fail-closed in direction → **BG23** (suggestion: version-gate the check or
record the widening as an as-built note).

### 1.6 `close()` and stream lifecycle

`close()` now releases the validated decoder (`DatabaseImport.java:624-634`), closing the
underlying file stream the parent leaked (parent `close()` empty, "TODO: check unclosed
stream?"). The plain-fallback stream keeps the leaked-parent lifecycle (comment `:625-626`).
Pure improvement on the gzip arm; parity on the plain arm. No finding.

**C1 verdict:** holds. Every delta found on the `<= 14` path is either spec-sanctioned
(SR2, §A3, as-built notes (b)/(c)) or a non-behavioral nit (BG21) / hand-crafted-input
widening (BG23).

---

## 2. C2 — v15 structural strictness correctness

### 2.1 Occurrence tracker — false-positive hunt on honest dumps

Premises:

1. The only producer of a declared-v15 dump is this repo's Step-4+ exporter. Its section
   shape is FIXED and UNCONDITIONAL: `exportInfo(); exportCollections(); exportSchema();
   exportRecords(); exportIndexDefinitions(); exportManifest();`
   (`DatabaseExport.java:206-215` at the commit) — no include/exclude conditional guards any
   section (the legacy `-excludeAll` option no longer suppresses section headers), and
   `brokenRids` is written unconditionally after the records array
   (`DatabaseExport.java:405-411`). So all seven required tags exist in every honest v15
   dump, including the streaming variant (same `exportDatabase` body).
2. The tracker records: six tags through the loop (`DatabaseImport.java:301`) and
   `brokenRids` at the inline consumption site in `importRecords` (`:1589-1596`, gated
   `exporterVersion >= 12` — exactly matching the inline read gate in
   `processBrokenRids(Set)` `:552`). For the honest shape each of the seven keys lands on
   exactly 1. A spliced second `brokenRids` goes through the loop → occurrence 2 → duplicate
   rejection (`:497-502`) — the episode's "surprise (1)" fix, and the trailer-truncation
   test's `assertRejectionMentions("Truncated GZIP trailer")` proves the truncation pin no
   longer passes via a spurious presence rejection.
3. Alias hole (tamper-only): the loop accepts `"clusters"` as a synonym for collections
   (`:314`) but the occurrence key is the RAW tag. A tampered v15 dump carrying BOTH
   `"collections"` and `"clusters"` processes `importCollections` twice with no duplicate
   rejection (occurrences: collections=1, clusters=1; only `collections` is in the required
   list, and `> 1` is never reached). Counterexample: take an honest v15 dump, duplicate its
   collections section under the tag `clusters` — both are consumed, WI10c's "a duplicated
   section [is rejected]" is escaped for this one section. Consequences are bounded
   (idempotent-ish re-mapping; record/class counts still verified) → **BG24** (suggestion:
   normalize `clusters` → `collections` before the merge).
4. An honest v15 dump using `clusters` cannot exist, and a hand-built one that does would be
   rejected for a MISSING `collections` — fail-closed, acceptable.

### 2.2 Manifest count arithmetic (CN51) — off-by-N hunt

Tally-site symmetry, pairwise:

| Entry | Exporter tally site | Importer tally site | Symmetry argument |
|---|---|---|---|
| classes | `manifestClasses++` per class object written (`DatabaseExport.java:632`) | `parsedSchemaClassCount++` per class object parsed (`DatabaseImport.java:870`) | same array, both count every element; import-side failures AFTER the count still reject via mismatch only if elements go unparsed — importSchema's pre-existing catch-swallow (`:1043-1046`) desyncs the reader and ends in a loud unsupported-tag/count rejection for v15 |
| indexes | `manifestIndexes++` per index object, `EXPORT_IMPORT` index SKIPPED before counting (`DatabaseExport.java:552-560`) | `parsedIndexCount++` per index object parsed (`DatabaseImport.java:1623`) | the skipped index is also not WRITTEN, so both sides see the same array; empty array counts 0 on both sides (`NEXT_OBJ_IN_ARRAY` + `break` before the counter, `:1617-1620`) |
| records | `recordExported++` per successful copy-out (`DatabaseExport.java:784`) | `parsedRecordCount++` per non-empty array entry (`DatabaseImport.java:1295`) | best-effort-skipped records are neither written nor counted; internal-collection records are never written (`DatabaseExport.java:330-332`) so never parsed; the importer counts BEFORE deciding the record's fate (delete/merge/system-overwrite), matching write-side semantics |
| brokenRids | `manifestBrokenRids = brokenRids.size()` after writing each rid once (`DatabaseExport.java:405-412`) | `parsedBrokenRidCount++` per non-empty token (`DatabaseImport.java:572-575`); the empty-array placeholder token is excluded | double-count via a spliced second section is pre-empted by the duplicate check, which runs FIRST (`verifyV15StructuralStrictness` order `:491-508`) |

Missing manifest FIELDS fail closed: the `-1` sentinel never equals a `>= 0` consumption
tally (`:541-548`). Missing manifest SECTION fails on the presence loop before any count
check. **No off-by-N found; null verdict** with the premises above.

One real arithmetic defect found at the parse site: `importManifest` reads the totals with
`jsonReader.readInteger(...)` (`DatabaseImport.java:468-472`), which is
`Integer.parseInt` (`JSONReader.java:84-95`), while the exporter writes `long` totals
(`recordExported` is `long`, `writeNumberField(String, long)`,
`DatabaseExport.java:305-308`) and the importer's own tallies and manifest fields are
`long` (`:157-170`). Counterexample: an honest v15 dump of a source with more than
2,147,483,647 records (≈2.1 B rows — large but a real production-database size for a
migration tool) declares `"records": 3000000000` → `Integer.parseInt` throws
`NumberFormatException` → the import of a perfectly honest dump is rejected with a raw
parse error instead of importing (or instead of the CN51 message). Fail-closed in
direction, false-positive in effect, trivial fix (parse long) → **BG20** (should-fix).

### 2.3 Whole-stream gzip validation (CS43 / WI10a)

1. Sequence order is the pinned one: drain (`DatabaseImport.java:522-527`) →
   `verifyFullyConsumed()` (`:530`) → `verifyPhysicalSize(physicalSize)` only when
   `physicalSize >= 0` (`:533-536`). The InputStream ctor leaves `physicalSize = -1`
   (`:132`, never set in that ctor) → steps (1)-(2) only, exactly the WI10a resolution.
   A plain stream on the programmatic path leaves `validatedGzipStream == null` → the block
   is skipped (`:519`) while section/manifest strictness still applies — matching the
   recorded contract and keeping the streaming round-trip green
   (`DatabaseExportImportRoundTripTest.java:354` imports a plain v15 stream).
2. Layered detection verified: truncated deflate → inflate/EOF exception during parse or
   drain; missing/truncated trailer → `EOFException("Truncated GZIP trailer")`
   (`ValidatedGZIPInputStream.java:263`); in-window trailing garbage →
   `readAheadResidue` rejection (`:133-137`); beyond-window garbage → physical-size
   arithmetic (`:148-155`). Each layer has a dedicated test (tests 1, 3, 4, 5).
3. Boundary case: DECOMPRESSED (in-member) content after the JSON root's closing brace is
   drained silently — CRC and sizes are legitimately valid, so `{...dump...}JUNK`
   compressed as one member imports cleanly. This is outside CS43's pinned scope (gzip-level
   completeness) and outside the JSON-level checks (all seven sections present and counted);
   producing it requires deliberate recompression, which defeats any non-cryptographic check
   anyway. Recorded as **BG22** (suggestion / null-adjacent: assert EOF-of-JSON after the
   root brace if a cheap check is wanted).

### 2.4 The `>= 15` vs `== 15` as-built deviation

Sound for the intermediate state, by exhaustion of the alternative: with `== 15`, a
hypothetical v16 dump would skip strictness AND still have its manifest consumed (the
manifest arm is `>= 15`, `DatabaseImport.java:326`) — i.e. it would ride the LENIENT path
silently, strictly worse. With `>= 15` a v16 dump gets the full fail-closed matrix. The
residual difference from end-state (v16 rejected-with-redirect instead of possibly
importing when shape-compatible) is unreachable (no v16 producer exists) and Step 6's
`importInfo` short-circuit lands ahead of these arms in the same track. One cosmetic
residue: the Q-M3 message says "a v15 dump is always gzip-framed" while interpolating the
declared version (`:414-418`) — momentarily odd for a v16 plain dump, pre-empted by Step 6
→ **CQ24** (suggestion, can be folded into Step 6's message work).

**C2 verdict:** correct on all honest shapes; BG20 (int parse) is the one substantive
defect; BG24/BG22 are tamper-only edges.

---

## 3. C3 — WI1 blob mapping, all dump shapes

Premises:

1. Honest dumps (any version): `exportCollections` writes EVERY named collection — ids
   `0..max`, skipping only null names (`DatabaseExport.java:469-503`); blob collections have
   names (`$blob<i>`), so their dump ids always have a collections-section entry.
   `importCollections` maps dump id → target id name-first (`getCollectionIdByName`, create
   if absent; `DatabaseImport.java:1177-1200`, mapping put `:1204`). The blob registration
   resolves through that mapping (`:825`), never the raw id. A dump with MORE blob
   collections than the target creates the extra `$blob<i>` by name and registers it —
   correct.
2. Blob id absent from the collections section (tampered/hand-built only, given premise 1):
   warned and skipped, never resolved raw (`:826-834`) — exactly the episode-recorded WI1
   resolution. Under the v15 strict contract this leniency is arguably off-spirit (an honest
   v15 dump can never hit it, so hitting it proves damage — the same logic as WI10b), and
   the skip leaves blob records in an unregistered collection with only a listener message
   → **CQ23** (suggestion: reject instead of warn when `exporterVersion >= 15`).
3. Blob id equal to a CLASS collection's dump id (a dump that itself declares a class
   collection as a blob): the mapping resolves the dump's OWN claim; this is same-space
   trust, identical to parent semantics, and outside FM-M16's cross-space scope. Null.
4. The FM-M16 counterexample itself (legacy high blob id landing on a target class
   collection) is closed: the raw `session.getCollectionNameById(collection)` resolution of
   the parent (`DatabaseImport.java(parent):560-566`) is gone.

**C3 verdict:** holds; CQ23 is the only (tamper-shape) refinement.

---

## 4. C4 — pre-flight deferral completeness

1. Parent block `:214-:223` = `removeDefaultNonSecurityClasses()` (`:214`), index-manager
   reload (`:215`), auto-index snapshot loop (`:217-221`), `beforeImportSchemaSnapshot`
   capture (`:223`). `runDeferredImportPreamble` (`DatabaseImport.java:439-455`) contains
   exactly these four statements in the same order — nothing more, nothing less; WI11's
   inclusive boundary (the snapshot moves WITH the drop) is honored via the field
   (`:147-148`, set `:454`, consumed `:298`).
2. Nothing left running early: between `BEGIN_OBJECT` and the `info` arm only
   `setValidationEnabled(false)` / `setUser(null)` run (`:271-273`) — session-object state,
   not target mutations, both present at the parent in the same position (validation
   restored in the finally, `:389`).
3. Nothing accidentally deferred that pre-preamble code needs: enumerated consumers of the
   block's effects (`indexesToRebuild`, the snapshot, the dropped-classes state, the
   reloaded index manager) all sit inside section handlers or the post-loop tail, and SR2
   guarantees no section handler other than `info` executes before the preamble
   (`:295-300`); `importRecords` can therefore never see a null snapshot. Degenerate
   `{"info":{v14}}`-only dump: preamble runs, loop ends, `rebuildIndexes()` operates on the
   same preamble-populated set as the parent — byte-for-byte.
4. `removeDefaultCollections`' two call sites (`importSchema:779-781`,
   `importCollections:1148-1150`) are unchanged and structurally post-info. Unlock condition
   is a PARSEABLE version (`:308-312`) — an info section without one leaves the target
   untouched and the next tag/EOF rejects (SR2), the exact CS38 closure.
5. Preamble idempotence guard (`preambleExecuted`, `:440-443`): a second `info` section on
   the lenient path (parent-tolerated) cannot double-drop.

**C4 verdict:** holds exactly. No finding.

---

## 5. C5 — acknowledgment gate

1. Without the flag: rejection at pre-flight, message names the exact remedy
   (`-acceptBestEffortDump=true`, `DatabaseImport.java:424-428`); pre-mutation (before
   `runDeferredImportPreamble`, `:310-311`); pinned with target-clean assertions
   (test `bestEffortDumpRequiresExplicitAcknowledgment`, marker class survives).
2. With the flag: gate passes; both the marker-only and marker+brokenRids shapes import
   (tests 11, 13) — the latter also proving the WI10b legitimate direction AND the
   quote-strip fix (an unstripped quoted token would fail `fromString` and the test).
3. Flag on a non-marked dump: `acceptBestEffortDump` is read only inside the
   `bestEffortDump &&` conjunction (`:423`) — provably inert. Untested (no test passes the
   flag against an unmarked dump) — folded into **TQ24**.
4. Version-keying nuance → BG23 (§1.5).

**C5 verdict:** holds.

---

## 6. C6 — test quality (15 tests)

Pin fidelity (all 15 accounted): #3 ×2 (`truncatedGzipTrailerDumpIsRejected` — red-first,
matcher pins `Truncated GZIP trailer` so the rejecting check is the CS43 drain, not an
accident; `midStreamTruncatedDumpIsRejected`), #4 ×2 (`trailingGarbage…`, `multiMember…` —
both assert the rejection does NOT stem from a `section` complaint, a good
anti-false-positive matcher), #5 ×3 (`missingSection…` names the section;
`duplicatedSection…` splices at TEXT level — correctly bypassing Jackson's duplicate-key
collapse — and pins the occurrence count "2 times"; `manifestCountMismatch…` pins
"manifest declares"), #6 ×2 (`plainJsonV15…` with CS38 target-clean assertions;
`plainJsonDeclaredV14DumpIsAccepted` — the #11 lenient half), #7 ×2 (both gate directions),
WI10b both directions (`brokenRidsWithoutBestEffortMarkerIsRejected` keeps the manifest
consistent so ONLY the marker check can fire — well-isolated;
`acknowledgedBestEffortDumpWithBrokenRidsImports`), #13
(`crossLayoutBlobDumpRegistersBlobsByMappingNotRawId`), SR2 ×2 (`undeclaredExporterVersion…`
pre-mutation; `emptyDumpIsRejected`). #16's condemnation semantics are correctly asserted
inside the post-mutation tests (loud failure asserted; target-clean deliberately not).

Fixture strategy (#13, `max(dump ids)+1`): sound, and self-guarding — the fixture ASSERTS
in-test that the rewritten id lands on a target class collection
(`DatabaseImportHardeningTest.java:203-207`), so a future layout change fails the fixture
loudly instead of silently degrading the pin; the rid rewrite (`"\"#1:" → "\"#<id>:"`)
cannot collide with other rid prefixes (the colon anchors it). The episode also records a
discrimination re-proof after the fixture refactor (revert → fails naming the
misregistered class collection). Verdict: sound, not fragile.

Gaps (none blocking):

1. **TQ22 (should-fix):** the WI10a resolution's gzip arm on the InputStream constructor —
   CS43 steps (1)-(2) applied to a GZIP-FRAMED programmatic stream — has NO test anywhere
   active: this class never uses the stream ctor; the round-trip suite feeds it only PLAIN
   streams (the OutputStream exporter writes plain JSON,
   `DatabaseExport.java:173-187`); `DbImportStreamExportTest` is `@Disabled`. A regression
   that keys the drain on `physicalSize >= 0` (instead of `validatedGzipStream != null`)
   would pass every existing test. One test (export to file → open as stream → import →
   truncated-trailer variant rejected) closes it.
2. **TQ23 (suggestion):** the missing-MANIFEST v15 shape (pin #5's first clause and the R2
   design's flagship "no manifest ⇒ incomplete" signal) is not directly tested — the
   presence loop is exercised via 'indexes' only. Same code path, but the pin letter names
   the manifest, and the `-1`-sentinel count arm is never reached by any test either.
3. **TQ24 (suggestion):** two code-verified-but-untested lenient edges: (a) unquoted legacy
   brokenRids tokens pass the strip untouched (the as-built note's backward-compat claim);
   (b) `-acceptBestEffortDump=true` on a non-marked dump is inert.

Regression power otherwise good: every rejection test discriminates via message-fragment
matchers walking the full cause chain, and `importExpectingRejection` catches ONLY the two
sanctioned exception types, so an unexpected raw exception fails the test.

---

## 7. Alternative-hypothesis check

For each candidate defect I asked what else could explain the observed code shape:

- The `>= 15` keying could be read as spec drift; the alternative (`== 15` today) was
  traced and produces a silently-lenient v16 path — the as-built choice is the fail-closed
  one, and the episode records the reasoning. Accepted.
- The strictness placement AFTER `rebuildIndexes`' gate but BEFORE it executes
  (`:347-352`) could be suspected of ordering weirdness; traced: strictness precedes every
  post-loop tail step, so a condemned import performs no index rebuild/synch/OPEN-status
  work. Correct.
- The inline brokenRids occurrence merge could be suspected of double-counting with the
  loop's merge; traced: the loop merge only fires for a tag that literally appears at top
  level, and the honest dump's brokenRids tag is consumed inside `importRecords` before the
  loop ever sees it — the two sites are disjoint by construction.
- The episode's fixture rationale claims "the internal collection is excluded from the
  dump's collections section"; code reading contradicts this (`exportCollections` skips only
  null names, and `exportRecords`' explicit internal-name skip proves internal resolves by
  id). The fixture's conclusion (max+1 unused in the dump) is independently true, so this is
  a track-file documentation nit (commit `a0fadb4334`, outside this diff) — flagged for the
  orchestrator, no ID.

## 8. Hypothesis log

| # | Hypothesis | Outcome |
|---|---|---|
| H1 | framing detection can overrun the 1024 mark and break the plain fallback | REFUTED — header bytes read directly from source; failure ≤ 3 bytes in (§1.1 p2) |
| H2 | quote-strip alters unquoted legacy tokens | REFUTED — guarded strip (§1.3 p3) |
| H3 | quote-strip changes a previously-IMPORTING dump | REFUTED — quoted brokenRids never imported at parent (§1.3 p2) |
| H4 | occurrence tracker false-positives an honest v15 dump | REFUTED — single honest shape enumerated, all 7 sections unconditional (§2.1) |
| H5 | manifest tallies off-by-N (internal/system/EXPORT_IMPORT) | REFUTED — pairwise symmetry table (§2.2) |
| H6 | some pre-info code consumes preamble state | REFUTED — consumer enumeration (§4 p3) |
| H7 | blob mapping breaks an honest dump shape | REFUTED (§3); tamper edge → CQ23 |
| H8 | `>= 15` unsound before Step 6 | REFUTED — `== 15` alternative strictly worse (§2.4) |
| H9 | legacy multi-member gzip delta unrecorded | CONFIRMED-as-recorded — as-built note (c) accurate; fail-loud (§1.1 p4) |
| H10 | stream-ctor auto-gunzip breaks a legacy caller | REFUTED — all active callers plain; gzip previously failed outright (§1.1 p6) |
| H11 | manifest count parse overflows int | CONFIRMED → BG20 |
| H12 | `{}` dump reaches the preamble | REFUTED — SR2/parse rejection first; test pins target-clean |
| H13 | `clusters` alias escapes WI10c | CONFIRMED → BG24 (tamper-only) |
| H14 | in-member payload garbage after the JSON root accepted | CONFIRMED → BG22 (outside pinned scope) |
| H15 | ack gate reaches legacy dumps | CONFIRMED → BG23 (hand-crafted input only, fail-closed) |

## 9. Findings

### BG20 — should-fix — `DatabaseImport.importManifest` parses long manifest totals as `int`
`DatabaseImport.java:468-472` uses `jsonReader.readInteger` (`Integer.parseInt`,
`JSONReader.java:88-95`) while the exporter writes `long` totals
(`DatabaseExport.java:305-308`, `recordExported` is `long`) and every count field on both
sides is `long`. **Counterexample:** an honest v15 dump of a >2.1 B-record database declares
`"records": 3000000000`; the import throws `NumberFormatException` at the manifest — a
false rejection of a valid dump with a message that names neither the manifest nor the
counts. Fix: parse as long (e.g. `Long.parseLong(readString/ readNumber ...)`).

### BG21 — suggestion — file-ctor exception-type change for a missing dump file
`DatabaseImport.java:174` (`Files.size` before `FileInputStream`) turns the missing-file
failure from `FileNotFoundException` into `NoSuchFileException`. Both are `IOException`;
callers catching the narrow legacy type see a delta. Either order the calls the old way or
accept and move on.

### BG22 — suggestion — in-member payload bytes after the JSON root are drained silently
`DatabaseImport.java:522-527` drains whatever follows the root `}` inside the gzip member;
CRC/ISIZE/physical-size all legitimately pass. `{...valid dump...}JUNK` in one member
imports cleanly. Outside CS43's pinned scope (requires recompression to produce); a cheap
"only whitespace until decompressed EOF" assertion during the drain would close it.

### BG23 — suggestion — best-effort ack gate is marker-keyed, not version-gated
`DatabaseImport.java:422-428` + `:697-701`: a declared-`<= 14` dump hand-edited to carry
`"best-effort": true` is now rejected where the parent ignored the field — a strict-letter
deviation from "declared-legacy keeps the lenient path byte-for-byte" (no honest legacy
dump carries the marker; fail-closed direction). Either add `exporterVersion >= 15` to the
gate or record the widening as an as-built note.

### BG24 — suggestion — `clusters` tag alias escapes the WI10c duplicate check
`DatabaseImport.java:301` keys occurrences on the raw tag; `:314` accepts
`"collections", "clusters"` as one arm. A tampered v15 dump carrying both processes
`importCollections` twice with no duplicate rejection (each key counts 1). Normalize the
occurrence key (`"clusters"` → `"collections"`) before the merge.

### CQ23 — suggestion — unmapped blob id is warn-and-skip even under v15 strictness
`DatabaseImport.java:826-834`: an honest v15 dump always maps its blob ids (§3 p1), so an
unmapped id proves damage — consistent with the WI10b logic it should REJECT for
`exporterVersion >= 15` instead of registering nothing and printing a listener message.
(The recorded WI1 resolution says warn-and-skip, so this is a refinement, not a violation.)

### CQ24 — suggestion — Q-M3 rejection message hardcodes "a v15 dump" under `>= 15` keying
`DatabaseImport.java:414-418`: a (currently unreachable) v16 plain dump would be told "a
v15 dump is always gzip-framed". Step 6's `>= 16` redirect pre-empts; fold the wording fix
into Step 6's message matrix.

### TQ22 — should-fix — WI10a's gzip-framed InputStream-ctor arm has no test
No active test constructs `DatabaseImport(session, <gzip stream>, …)`: this class uses only
the file ctor; the round-trip suite feeds the stream ctor plain JSON (the OutputStream
exporter is plain, `DatabaseExport.java:173-187`); `DbImportStreamExportTest` is
`@Disabled`. CS43 steps (1)-(2) on the stream source — the core of the recorded WI10a
resolution — are unpinned; a regression gating the drain on `physicalSize >= 0` would pass
every existing test. Add: file-export → `FileInputStream`-import (accept) + its
truncated-trailer variant (reject).

### TQ23 — suggestion — missing-manifest v15 shape untested
Pin #5's first clause ("missing … manifest") is covered only structurally (same presence
loop as the tested 'indexes' case); neither the manifest-absent rejection message nor the
`-1` count sentinel path is exercised. One `mutateDump(root -> root.remove("manifest"))`
test closes both.

### TQ24 — suggestion — two code-verified lenient edges untested
(a) unquoted legacy brokenRids tokens pass the strip untouched
(`DatabaseImport.java:564-570`) — the as-built backward-compat claim; (b)
`-acceptBestEffortDump=true` against a non-marked dump is inert (`:423`). Both one-liners
inside existing fixtures.

## 10. Verdict

No blockers. 2 should-fix (BG20 production, TQ22 test), 7 suggestions. The step's four
mandated properties — pre-flight deferral exactness, v15 strictness on honest-vs-damaged
shapes, WI1 mapping, ack gate — all verified against the amended design; the `<= 14` path
is preserved modulo the explicitly sanctioned deltas, each of which is recorded in the
episode's as-built notes. Red-first records for pins #3 and #13 are consistent with the
parent code's actual defect mechanics (skip-only manifest + no drain; raw blob-id
resolution at `DatabaseImport.java(parent):557-566`).
