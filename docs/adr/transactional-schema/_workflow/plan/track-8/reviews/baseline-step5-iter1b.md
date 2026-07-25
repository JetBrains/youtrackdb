# Baseline code review (second opinion) — Track 8 Step 5, iteration 1b

- **Subject commit:** `5173bccd10` ("Harden import: pre-flight deferral, v15 strictness"), diffed against parent `8ca99846fc`.
- **Perspective:** code baseline — correctness & bugs, code quality, test quality.
- **Reviewer stance:** independent second-opinion pass; the prior baseline report was NOT read. Finding IDs: BG25+, CQ25+, TQ25+.
- **In-scope files:** `DatabaseImport.java` (primary), `ValidatedGZIPInputStream.java` (consumed, unchanged), `DatabaseImportHardeningTest.java` (new, 15 tests). Supporting: `DatabaseExport.java`, `JSONReader.java`.
- **Binding spec:** `track-8.md` Step 5 entry + episode; `track-8-design-drafts.md` M2.b (amended), rulings R1/R2/Q-M2/Q-M3, SR1/SR2, CS38/CS43/CN51/WI1/WI10/WI10a/WI11.

Severity vocabulary: **blocker** / **should-fix** / **suggestion**.

---
## Method and decision criteria

**Definitions used throughout:**
- *Legacy path* = every execution path reachable with a declared `exporter-version <= 14`.
- *Sanctioned legacy deltas* (per the review charter + Step 5 episode as-built notes): SR2 rejection of version-less/ill-ordered dumps, the §A3 blob-mapping fix, brokenRids quote handling, and gzip *decoding* changes (single-member validated decoder replacing the JDK decoder).
- *False positive* = an honest exporter-produced v15 dump rejected; *false negative* = a truncated/tampered/inconsistent v15 dump accepted, within the checks the design mandates (CS43 stream framing, WI10b/c structure, CN51 arithmetic, Q-M3 framing, M2.b-4 gate).
- Verdicts: a defect claim requires a traced counterexample; a correctness claim requires an argument why none exists.

**Evidence base:** full read of `DatabaseImport.java` at `5173bccd10` (cited as `DatabaseImport.java:<line>`, new-file numbering) against the parent copy at `8ca99846fc`; `ValidatedGZIPInputStream.java` (unchanged this commit, consumed); `DatabaseExport.java` (tally provenance); `JSONReader.java` (`hasNext`/`readInteger`/`readBoolean`/`readNext` semantics); the 15-test `DatabaseImportHardeningTest.java`; `DatabaseImpExpAbstract.java` (fileName normalization); `DatabaseExportImportRoundTripTest.java` (stream-ctor coverage).

---

## Criterion 1 — R1 byte-for-byte legacy preservation (`<= 14` path)

**Premises.**
1. Parent preamble block (`8ca99846fc` DatabaseImport.java:214-223): `removeDefaultNonSecurityClasses()` → `indexManager.reload` → auto-index snapshot loop → `beforeImportSchemaSnapshot = getImmutableSchemaSnapshot()`.
2. New `runDeferredImportPreamble()` (`DatabaseImport.java:439-455`) reproduces those four statements verbatim, in the same order; it is unlocked in the `"info"` switch arm (`:305-312`) only when `exporterVersion != -1` and `runPreFlightChecks()` did not throw.
3. Every exporter since the legacy era writes `info` first (design M2.b intro; `DatabaseExport.exportDatabase` order at `DatabaseExport.java:208-215`), so for every honest legacy dump the preamble runs before any section handler that consumes its effects.

**Systematic delta enumeration on the legacy path** (each item checked against the parent):
- (a) Preamble timing: moved from before the section loop to after `importInfo`. Between the two points only `readNext(BEGIN_OBJECT)`, `setValidationEnabled(false)`, `setUser(null)`, and the first tag read execute — none consumes the dropped classes, the reloaded index manager, or the snapshot. Behavior-preserving for honest legacy dumps; the changed behavior for garbage/ill-ordered/version-less inputs is exactly the SR2-sanctioned set (`:295-298`, `:338-342`). **Checked — sanctioned.**
- (b) Gzip decoding: `detectFraming` (`:205-215`) substitutes `ValidatedGZIPInputStream` for `java.util.zip.GZIPInputStream` with the same mark(1024)/reset fallback and buffer size. Construction-time failure sets (bad magic `Not in GZIP format`, unsupported method, truncated header, FHCRC mismatch) match the JDK's → same fallback envelope. Read-path: both verify CRC32+ISIZE at deflate EOF; the JDK then probes for a next member, the validated decoder does not. Since the legacy JSON parse stops at the root's closing `}` and `JSONReader.hasNext()` is a non-consuming `in.ready()` probe (`JSONReader.java:606-608`), deflate EOF is normally never reached on the legacy path; the only reachable delta is hand-concatenated multi-member legacy input — the recorded, sanctioned as-built note (c). **Checked — sanctioned.**
- (c) brokenRids: quote-strip + trim before `RecordIdInternal.fromString` (`:560-580`) — sanctioned as-built note (b); unquoted legacy tokens pass through the `charAt` guards unchanged.
- (d) Blob-collection resolution through `collectionToCollectionMapping` (`:817-840`) — the sanctioned §A3 fix; for a well-formed legacy dump (collections section precedes schema in every exporter's output) the by-name mapping reproduces the parent's effective registration on matching layouts, and fixes it on mismatched ones.
- (e) `manifest` tag arm: still gated `exporterVersion >= 15` (`:326-332`); legacy dumps keep the byte-identical `unsupported tag 'manifest'` rejection.
- (f) `close()` (`:624-635`) now closes the validated decoder (and transitively the file stream) after a gzip-framed import; the parent leaked both. No legacy-observable behavior change (nothing reads the stream after import); strictly an improvement. The plain-JSON fallback stream keeps the historical leak (acknowledged in the comment).
- (g) **Unsanctioned residue (trivial):** the file ctor's new `Files.size(Paths.get(fileName))` probe (`:174`) runs before `new FileInputStream`, so a *missing* dump file now fails with `NoSuchFileException` instead of `FileNotFoundException` (both `IOException` subtypes; the ctor's `throws` clause is unchanged). Additionally the InputStream ctor now declares `throws IOException` (`:184`) — a compile-time signature change for callers (all in-repo callers compile; verified by the recorded green builds). → **BG28 (suggestion)** below.
- (h) The `best-effort` info field is now *parsed* (`:697-700`) and *gated* (`:423-428`) for any declared version, including `<= 14`. `JSONReader.readBoolean` never throws on non-boolean tokens (`Boolean.parseBoolean`, `JSONReader.java:172-174`) and consumes the same token span as the parent's skip arm, so honest legacy dumps (which never carry the field) are byte-for-byte unaffected; only a hand-crafted legacy dump carrying the marker is newly rejected. This is the marker-vs-version keying question — see Criterion 5. → **CQ26 (suggestion)**.

**Alternative-hypothesis check:** I looked for a legacy consumer of preamble state that could run before `info` — `beforeImportSchemaSnapshot` (only `case "records"`, `:319`), `indexesToRebuild` (only `importCollections`' rebuild loop and post-loop `rebuildIndexes()`), `removeDefaultCollections` (only inside `importSchema`/`importCollections` handlers). All are structurally behind the SR2 first-tag guard, which requires `exporterVersion != -1`, which in turn implies the `info` arm already ran pre-flight + preamble (or aborted). No counterexample.

**Verdict:** legacy preservation holds up to the sanctioned deltas; residual unsanctioned deltas are the two trivialities in (g)/(h) — filed as suggestions, no should-fix.

---

## Criterion 2 — v15 structural strictness correctness

**Premises.**
1. Section presence/duplicates: `sectionOccurrences.merge(tag, 1, …)` per loop tag (`:301`), plus the inline `brokenRids` occurrence recorded inside `importRecords` for `>= 12` (`:1588-1596`); post-loop, all seven required sections must have occurrence == 1 (`:490-505`).
2. Manifest arithmetic (CN51): importer tallies at the parse sites — classes `:870`, indexes `:1623`, records `:1295` (after the empty-token array-end guard), brokenRids `:574` (non-empty tokens only); manifest declared totals parsed in `importManifest` (`:462-479`, unknown fields skipped); cross-check `declared != consumed` rejects (`:541-548`), with `-1` sentinel for undeclared fields (fail-closed).
3. Exporter provenance (verified in `DatabaseExport.java`): `manifestClasses++` per class object written (`:632`), `manifestIndexes++` per index object written (`:560`, EXPORT_IMPORT-class index filtered *before* increment), `records` = `recordExported` incremented per record copied out (`:784`), `brokenRids` = `brokenRids.size()` (`:412`). Each equals exactly what the importer's counter counts for an honest dump.
4. CS43: post-loop drain of the decompressed stream (`:518-528`), `verifyFullyConsumed()` (trailer verified + inflater finished + zero read-ahead residue), then `verifyPhysicalSize(physicalSize)` for the file source only (`:534-536`). The drain interacts safely with the `InputStreamReader`'s char buffering: buffered chars were already consumed *from the decoder's perspective*, so the drain merely pushes the decoder to deflate EOF from wherever it stands; if the read-ahead already hit EOF mid-parse, `read` returns `-1` idempotently after `trailerVerified` (`ValidatedGZIPInputStream.java:85-99`).

**False-positive sweep (honest v15 dumps).**
- Section set: the exporter writes exactly `info, collections, schema, records(+inline brokenRids), indexes, manifest` (`DatabaseExport.java:208-215`), each once; `brokenRids` never passes through the loop (consumed inline) and is recorded exactly once. → occurrences all == 1. ✓
- Counts: premise 3 ⇔ premise 2, term by term. Records: the importer's array-end empty token is excluded before the increment (`:1292-1295`); empty `brokenRids` arrays parse as one empty token, excluded (`:571-575`). ✓
- Gzip: an honest single-member dump drains clean, residue 0, header+deflate+8 == file size. ✓ Confirmed empirically by the recorded 46/46 green targeted battery incl. the round-trip suites.
- Theoretical exception: manifest totals are parsed with `JSONReader.readInteger` → `Integer.parseInt` (`:466-470`; `JSONReader.java:84-95`), while both sides tally in `long` — an honest dump with a count above `Integer.MAX_VALUE` would false-reject with a raw `NumberFormatException`. Practically unreachable at current dump scales. → **BG27 (suggestion)**.

**False-negative construction attempts** (each traced):
1. Truncated trailer / mid-deflate truncation / trailing raw garbage / second member — all rejected (drain EOFException; deflate error; read-ahead residue or physical-size arithmetic). Tested. ✓
2. Missing / duplicated (same-tag) / count-inconsistent sections — rejected post-loop. Tested. ✓
3. brokenRids without marker — rejected (`:512-517`). Tested. ✓
4. **CONSTRUCTED — alias-tag bypass of WI10c:** the presence/duplicate tracker keys on the *raw tag string*, but the loop maps two spellings to the collections handler (`case "collections", "clusters"` `:314`). A v15 dump carrying its honest `"collections"` section *plus* a spliced `"clusters":[{"name":"Evil","id":99}],` section passes: occurrences are `collections=1, clusters=1` (no key exceeds 1; `clusters` is not in the required list), `importCollections` runs twice (the second run creates the smuggled collection and re-runs the index-rebuild loop), no manifest field covers collections, and the re-gzipped file is a clean single member. The import completes silently. → **BG25 (should-fix)**. *Mitigating note:* the added adversarial power is bounded — the manifest carries no collections count (WI8c declined per-collection granularity), so editing the existing collections array is equally undetectable by design; the honest-accident duplicate shapes (splice/concat of same-tag sections) *are* caught. Cheap fix: canonicalize the tag before `merge` (`"clusters"` → `"collections"`), or reject the `clusters` spelling outright when `exporterVersion >= 15` (the v15 exporter writes only `collections`, `DatabaseExport.java:472`).
5. **CONSTRUCTED — in-member decompressed content outside the JSON root:** `gunzip dump; cat dump.json junk >> combined; gzip` (single member). The reader stops at the root's `}` (leading junk before `{` is likewise silently scanned over by `readNext(BEGIN_OBJECT)`); the drain consumes the junk; CRC/ISIZE are valid for the recompressed member; residue 0; physical size matches. Import succeeds silently. This shape requires manual decompress+recompress — the operation Q-M3's doctrine condemns but cannot detect once re-gzipped — and the pinned CS43 sequence covers raw-stream framing only, so it is design-letter compliant. A cheap hardening exists: count drained bytes and reject non-whitespace residue after the root. → **BG26 (suggestion)**.
6. **Inline brokenRids occurrence is recorded without verifying the field name:** `processBrokenRids` consumes whatever field follows the records array by scanning to `[` (`readNext(BEGIN_COLLECTION)`, pre-existing laxity), and `importRecords` merges the `brokenRids` occurrence unconditionally for `>= 12` (`:1594-1596`) — so a tampered dump whose post-records section is *renamed* still satisfies the presence check if its tokens parse as rids and the counts agree. Same tamper-equivalence class as (4)/(5); inherited laxity, not introduced. → **CQ28 (suggestion)**.

**Verdict:** correct on honest dumps (one theoretical `int`-parse exception); effective on all accident shapes (truncation, concatenation of gzip files, splices of the exporter's own tags); two constructible tamper shapes slip through, one of which (alias duplicate) violates WI10c's letter → BG25.

---

## Criterion 3 — WI1 blob-collection id mapping

**Premises.**
1. `importCollections` builds `collectionToCollectionMapping` dump-id → target-id by *name* resolution (`getCollectionIdByName`, creating the collection when absent; `:1209-1216`), with `defaultReturnValue(COLLECTION_NOT_FOUND_VALUE)` (`:173`, `:188`).
2. The blob registration (`:817-840`) resolves each dump blob id through that mapping; `COLLECTION_NOT_FOUND_VALUE` → WARN + skip, explicitly never falling back to the raw id; a mapped id registers `session.addBlobCollection(name)` only if not already registered.

**Case enumeration.**
- Cross-layout (v14 high blob id → target class-collection id): mapped by `$blob*` name to the target's blob id — never raw. Fixed; pinned by the cross-layout test with re-proven matcher discrimination (episode record). ✓
- Same-layout round-trip: name-identity mapping; behavior equivalent to the parent's raw resolution on matching layouts (round-trip suites green). ✓
- Missing mapping (blob id absent from the collections section): warn + skip — fail-safe, never the FM-M16 misclassification. For a v15 dump this state is exporter-impossible (the exporter dumps every non-internal collection), so warn-and-skip only fires on tampered/hand-built dumps; a stricter v15 arm could hard-fail here, but the design left the choice open ("mapping *or* name-match"). Untested branch → **TQ26 (suggestion)**.
- Id collisions: the mapping is keyed by dump id; two dump collections sharing an id is a malformed dump (last write wins) — no new hazard vs parent. Source-with-more-blobs-than-target: `$blobN` beyond the target range is *created* by name in `importCollections` and then blob-registered — correct classification.
- `getCollectionNameById(targetCollectionId)` cannot return null for a mapped id (the mapping only contains ids returned by `getCollectionIdByName`/`addCollection`), so the parent's latent NPE-on-raw-miss shape is gone.

**Alternative hypothesis:** could `importSchema` run before `importCollections` and see an empty mapping (all blobs skipped)? Only in a hand-reordered dump — no exporter ever wrote schema before collections; on such input the parent's raw resolution was itself arbitrary. Accepted residue, noted.

**Verdict:** the fix is correct and closes FM-M16; no finding beyond the untested warn+skip arm (TQ26).

---

## Criterion 4 — Pre-flight deferral completeness (§A2/WI11)

**Premises.**
1. WI11 pins the deferred block as parent `:214-:223` inclusive: default-class drop, IM reload, auto-index snapshot, `beforeImportSchemaSnapshot` capture — plus `removeDefaultCollections` via its two call sites.
2. `runDeferredImportPreamble` (`:439-455`) contains exactly those statements in the parent's order (verified side-by-side; the snapshot moved from a local to the `beforeImportSchemaSnapshot` field `:147`).
3. Unlock condition: inside the `info` arm, `runPreFlightChecks()` then `runDeferredImportPreamble()` (`:305-312`), only when `exporterVersion != -1`; `preambleExecuted` guards re-entry (`:440-443`, duplicate-info shape).

**Consumer audit (may any consumer run pre-preamble?).**
- `beforeImportSchemaSnapshot` → only `case "records"` (`:319`). Reaching a `records` tag with `exporterVersion == -1` is impossible (SR2 first-tag throw `:295-298`); `exporterVersion != -1` implies the info arm completed pre-flight + preamble (or threw, aborting the import). Non-null guaranteed. ✓
- `indexesToRebuild` → `importCollections`' rebuild loop and post-loop `rebuildIndexes()` — both behind the same SR2 guard / post-loop checks. ✓
- `removeDefaultNonSecurityClasses` effects → consumed by `importRecords`' leftover classification — behind the guard. ✓
- `removeDefaultCollections` → called only inside `importSchema` (`:780`) and `importCollections` (`<= 4` arm) — structurally post-info. ✓
- `runPreFlightChecks` is not guarded against re-run on a duplicate info section — it is idempotent (pure checks), harmless.
- Session-level pre-info statements (`setValidationEnabled(false)`, `setUser(null)`) are transient, match the parent, and the `finally` restores validation as before.

**Ordering within the unlock:** pre-flight *precedes* the preamble, so a Q-M3/ack-gate rejection leaves the target untouched — pinned by three tests that plant a `PreFlightMarker` class which the preamble's class drop would remove (a genuinely discriminating detector).

**Verdict:** deferral is complete and order-exact; no finding (null verdict).

---

## Criterion 5 — Best-effort ack gate (Q-M2/M2.b-4)

**Premises.**
1. Marker read: `importInfo` sets `bestEffortDump` from the `best-effort` info field (`:697-700`); the exporter writes it only under `-bestEffort` (`DatabaseExport.java:530-534`).
2. Gate: `bestEffortDump && !acceptBestEffortDump` → pre-flight rejection naming the flag (`:423-428`); `-acceptBestEffortDump` parsed via `Boolean.parseBoolean` (`:254-257`) — any non-"true" value keeps the gate closed (fail-closed).

**Both directions.** Refusal without the flag: tested, incl. target-clean (pre-flight). Acceptance with the flag: tested, incl. the WI10b interplay (marked dump *with* brokenRids + matching manifest imports under ack — pinning the quoted-rid parse fix). Unmarked dump with the flag passed: gate inert by construction (`bestEffortDump == false` short-circuits) — trivially correct, untested, acceptable.

**Marker-vs-version keying.** The gate keys on the *marker alone*, not on `exporterVersion >= 15`. The design's letter supports this (M2.b-4: "a dump whose info section carries the best-effort marker is refused unless…"; WC1: "the ack gate keys off the v15 best-effort marker"), and no legacy exporter ever writes the field — but it does mean a hand-crafted declared-v14 dump carrying `"best-effort": true` is newly rejected, a (vanishingly small) widening of the R1 byte-for-byte lenient contract. If the R1 letter is preferred maximal, guard the marker read or the gate with `exporterVersion >= 15`. → **CQ26 (suggestion)**; otherwise null verdict.

**Verdict:** correct in both directions; keying is defensible under the design's letter — one suggestion recorded for the R1-letter tension.

---

## Criterion 6 — Test quality (15 tests, `DatabaseImportHardeningTest`)

**Premises.** For each test I asked: does it pin the mechanism (not an accident)? would it fail if the guarded check were reverted? is the fixture sound?

**Per-test regression audit (all 15).**
1. `truncatedGzipTrailerDumpIsRejected` — discriminating: with the drain removed the parse stops at `}` and never reaches the missing trailer (`hasNext()` = non-consuming `in.ready()`), so the import would succeed → `assertNotNull` fails; the `"Truncated GZIP trailer"` matcher pins the decoder check (matches the recorded red-first signature at parent). ✓
2. `crossLayoutBlobDumpRegistersBlobsByMappingNotRawId` — sound fixture: `max(dump ids)+1` collides with nothing in the dump (the internal collection is excluded from the section, so `size()` undercounts — the episode's flakiness fix); the fixture *self-checks* that the rewritten id lands on a target class collection (an `assertTrue` guard, so drift fails loudly rather than passing vacuously); the rid rewrite `"#1:` → `"#N:` cannot over-match multi-digit collections (the colon anchors it). Discrimination re-proven per episode. Also covers the v14-declared *gzip* import (legacy-gzip-through-validated-decoder leg). ✓
3. `midStreamTruncatedDumpIsRejected` — loud-failure pin (any rejection accepted; that is the contract for this shape). ✓
4. `trailingGarbageAfterDumpIsRejected` — accepts either CS43 arm (residue vs size); the not-"section" matcher excludes structural accidents. Step 4's primitive test discriminates the physical-size arm separately, so the looseness is acceptable. ✓
5. `multiMemberGzipDumpIsRejected` — same structure. ✓
6. `missingSectionDumpIsRejected` — message-pinned to the presence check ("missing its 'indexes' section"), which fires before the count checks. ✓
7. `duplicatedSectionDumpIsRejected` — pins the occurrence *count* ("2 times"), i.e. WI10c's counting semantics, and (per the episode) re-validated after the inline-brokenRids surprise. ✓
8. `manifestCountMismatchIsRejected` — "manifest declares" matcher pins CN51. ✓
9. `plainJsonV15DumpIsRejectedBeforeAnyMutation` — pins Q-M3 *and* CS38: the `PreFlightMarker` class is exactly what the deferred preamble's drop would delete → genuine mutation detector; also discriminating for Q-M3 removal (a plain v15 file would otherwise import cleanly — validatedGzipStream null skips the gzip arm). ✓
10. `plainJsonDeclaredV14DumpIsAccepted` — the #11 lenient half. ✓
11. `bestEffortDumpRequiresExplicitAcknowledgment` — both gate directions + target-clean. ✓
12. `brokenRidsWithoutBestEffortMarkerIsRejected` — manifest kept consistent so *only* WI10b can fire — properly discriminating. ✓
13. `acknowledgedBestEffortDumpWithBrokenRidsImports` — the only pin of the quoted-rid parse fix; would fail on regression (`NumberFormatException` on `"#99`). ✓
14. `undeclaredExporterVersionIsRejectedBeforeMutation` — the "without declaring" matcher discriminates SR2's first-tag arm from the incidental parse failure the same dump would produce without SR2 (version `-1` drives `importCollections` into the `< 9` parse arm → ParseException — rejection non-null either way, but the message differs). Well built. ✓
15. `emptyDumpIsRejected` — passes via SR2's *first-tag* arm, not the EOF arm: for `{}` the tag scan consumes `}` and the first-tag check throws on the junk tag. See TQ28.

**Coverage gaps found.**
- **TQ25 (should-fix):** the InputStream-ctor **gzip** v15 arm — WI10a's actual new behavior (steps (1)+(2) with `physicalSize == -1`) — has no test anywhere active: `DatabaseExportImportRoundTripTest` covers only *plain* v15 streams (the streaming exporter writes plain JSON, `DatabaseExport.java:173-186`), `DbImportStreamExportTest` is `@Disabled`, the lucene module is out of the build. A regression that skips the drain/`verifyFullyConsumed` when `physicalSize < 0` — or nulls `validatedGzipStream` on the stream path — passes the whole suite, silently un-verifying gzip streams. (The plain-stream *acceptance* half of WI10a IS covered by the round-trip test.) Add: gzip-framed v15 stream round-trip + truncated-trailer gzip stream rejection through the InputStream ctor.
- **TQ28 (should-fix):** SR2's **end-of-stream arm** (`:338-342`) is untested. Constructible probe: a dump that is exactly `{"info":{"name":"x"}}` (info section, no version, root closes) — the loop exits at the root `}` with `exporterVersion == -1` and only the post-loop arm rejects. If that arm were deleted, this input would "import" successfully (no preamble, no sections, success message) — the precise SR2 fail-closed violation, and CS46 pins the EOF half of the trigger explicitly. `emptyDumpIsRejected` does NOT cover it (fires on the first-tag arm, traced above). Add the info-only versionless fixture with a "ended without declaring" matcher.
- **TQ26 (suggestion):** the unmapped-blob-id warn+skip branch (`:826-833`) is unexercised.
- **TQ27 (suggestion):** the missing-'manifest'-section rejection is only covered generically (the presence loop is uniform, tested via 'indexes'), and no test covers the alias-duplicate shape of BG25 (add one with the fix).

**Fixture soundness (general):** `mutateDump`'s Jackson gunzip→mutate→re-gzip round-trip preserves root field order (ObjectNode insertion order) and record content sufficiently — evidenced by the accepting tests (10, 13) passing through full imports. The max(dump-ids)+1 strategy is sound *and* self-checking (see #2).

**Verdict:** the 15 tests are well-aimed, discriminating (matchers pin mechanisms, target-clean pins use a preamble-sensitive detector), and each fails on regression of its check; the gaps are the two untested arms above (TQ25, TQ28) plus two minor branches.

---

## Findings

| ID | Severity | Location | Summary |
|---|---|---|---|
| BG25 | should-fix | `DatabaseImport.java:301,314,490-505` | WI10c presence/duplicate tracker keys on the raw tag; the `"clusters"` alias lets a v15 dump carry a second collections section (or split spellings) undetected — spliced `"clusters":[…]` imports silently and creates smuggled collections. Canonicalize the tag before `merge`, or reject the `clusters` spelling for `exporterVersion >= 15`. |
| BG26 | suggestion | `DatabaseImport.java:518-528` | Decompressed in-member bytes outside the JSON root (gunzip→concat→re-gzip accident) drain silently: all CS43 checks pass on the recompressed single member and the first dump imports. Cheap hardening: reject non-whitespace drained residue after the root. Design-letter compliant as-is. |
| BG27 | suggestion | `DatabaseImport.java:466-470` + `JSONReader.java:84-95` | Manifest totals parsed via `Integer.parseInt` while tallies are `long` — counts above 2^31-1 false-reject an honest (colossal) dump with a raw `NumberFormatException`. Parse as long. |
| BG28 | suggestion | `DatabaseImport.java:174,184` | Unsanctioned (trivial) legacy deltas: `Files.size` probe changes the missing-file failure type (`NoSuchFileException` vs `FileNotFoundException`); InputStream ctor now `throws IOException` (compile-time caller change). Record or accept. |
| CQ25 | suggestion | `DatabaseImport.java:129,415` | `gzipFramed` duplicates `validatedGzipStream != null` — two sources of truth for framing state; derive one from the other. |
| CQ26 | suggestion | `DatabaseImport.java:697-700,423-428` | Best-effort marker read + ack gate are version-unkeyed: a hand-crafted declared-legacy dump carrying `"best-effort": true` is newly gated — a micro-widening of R1's byte-for-byte letter (defensible under M2.b-4/WC1's marker-keyed wording). Consider `exporterVersion >= 15` guarding if the R1 letter is preferred. |
| CQ27 | suggestion | `DatabaseImport.java:541-548` | A manifest missing one counted field reports "manifest declares -1 <entry>" — the sentinel leaks into the operator message; say "does not declare" instead. |
| CQ28 | suggestion | `DatabaseImport.java:1588-1596` + `:557` | The inline brokenRids occurrence is recorded without verifying the field name (pre-existing blind `readNext(BEGIN_COLLECTION)` consumption) — a renamed post-records array can satisfy the presence check when its tokens parse as rids and counts agree. |
| TQ25 | should-fix | `DatabaseImportHardeningTest.java` (absence) | No test drives a GZIP-framed v15 stream through the InputStream ctor (WI10a steps (1)+(2), `physicalSize == -1`): a regression skipping the drain/verification on the stream path passes the entire suite. |
| TQ26 | suggestion | `DatabaseImportHardeningTest.java` (absence) | The unmapped-blob-id warn+skip arm (`DatabaseImport.java:826-833`) is untested. |
| TQ27 | suggestion | `DatabaseImportHardeningTest.java` (absence) | Missing-'manifest' presence arm and the BG25 alias-duplicate shape lack direct pins (low risk given the uniform loop; add with the BG25 fix). |
| TQ28 | should-fix | `DatabaseImport.java:338-342` / test absence | SR2's end-of-stream arm is untested and is not reached by `emptyDumpIsRejected` (which trips the first-tag arm); deleting the post-loop check would let `{"info":{"name":"x"}}` "import" successfully — a spec-pinned (CS46) trigger half with zero coverage. |

## Null-verdict notes per criterion

- **C1 (legacy preservation):** no should-fix; only the trivial BG28/CQ26 residues beyond the sanctioned delta set.
- **C2 (v15 strictness):** BG25 (alias bypass); BG26/BG27 as suggestions; honest-dump arithmetic verified sound term-by-term against the exporter.
- **C3 (WI1 blob mapping):** null verdict on correctness — fix verified, edge cases enumerated; TQ26 coverage note only.
- **C4 (pre-flight deferral):** null verdict — statement order verbatim-identical to the parent block; every consumer is provably behind the SR2 unlock.
- **C5 (ack gate):** null verdict on behavior — both directions correct and tested; CQ26 records the marker-vs-version keying tension as a suggestion.
- **C6 (test quality):** TQ25/TQ28 should-fix coverage gaps; the 15 committed tests themselves are discriminating and regression-sensitive.

## Hypothesis log

| # | Hypothesis | Evidence sought | Outcome |
|---|---|---|---|
| H1 | The truncated-trailer test passes for the wrong reason (read-ahead triggers the trailer check during parse even without the drain) | JDK `StreamDecoder`/`BufferedReader` fill semantics + `JSONReader.hasNext` = `in.ready()` + the recorded red-first at parent (JDK decoder, same read-side trailer verify, dump imported silently) | REFUTED — parse never forces deflate EOF; the drain is the rejecting mechanism; test discriminates |
| H2 | Drain double-reads bytes already buffered by `InputStreamReader`, corrupting the CS43 arithmetic | `ValidatedGZIPInputStream.read`/`readTrailer`; buffering is above the decoder | REFUTED — buffered chars are already consumed from the decoder; drain is position-agnostic; `-1` idempotent after `trailerVerified` |
| H3 | Manifest arithmetic can false-reject an honest dump (tally provenance mismatch) | Exporter increment sites vs importer count sites, incl. filters (EXPORT_IMPORT index, empty tokens, internal collection) | REFUTED term-by-term; residual: int-parse ceiling → BG27 |
| H4 | A consumer of deferred preamble state can execute before `runDeferredImportPreamble` | Exhaustive consumer audit behind the SR2 guard | REFUTED — no path |
| H5 | Duplicate/missing section checks can be bypassed | Tag-keyed occurrence map vs the alias arm; inline brokenRids merge | CONFIRMED for the `clusters` alias → BG25; inline blind-merge → CQ28 |
| H6 | v15 whole-stream validation accepts a constructible tampered stream | In-member content outside the JSON root (recompressed single member) | CONFIRMED (design-letter compliant) → BG26 |
| H7 | The ack gate misfires on legacy dumps (marker keying) | `importInfo` field arm + `readBoolean` semantics vs parent skip arm | PARTIALLY CONFIRMED — only hand-crafted legacy dumps affected; design-letter sanctioned → CQ26 |
| H8 | `emptyDumpIsRejected` covers SR2's EOF arm | Token-level trace of `{}` through `readString(FIELD_ASSIGNMENT)` | REFUTED — first-tag arm fires; EOF arm untested → TQ28 |
| H9 | The blob warn+skip arm can still resolve raw or NPE | Mapping construction + `getCollectionNameById` domain | REFUTED — mapped ids always valid; skip arm never touches raw ids |
