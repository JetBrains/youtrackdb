# Track 8 cumulative baseline review — iteration 1 (track-level)

**Perspective:** code baseline, TRACK-LEVEL CUMULATIVE — the whole Track 8 diff
`git diff cced9df1af..19ebbcbb2d` (branch `transactional-schema`), scoped to CODE files
(`core/`, `tests/`; the operator doc had its own reviewer and is cited here only where a code
contract depends on it).
**Charter:** (1) cross-step seams the per-step reviews structurally miss; (2) end-state
conformance against the design-draft M2 end-state (`track-8-design-drafts.md` as amended,
rulings R1/Q-M2/SR1–SR3); (3) cumulative code quality of `DatabaseExport.java` /
`DatabaseImport.java` after six steps of accretion; (4) cumulative test architecture.
**Finding IDs:** BG from BG31, CQ from CQ33, TQ from TQ33 (cumulative ledger).
**Ledger honored (not re-filed):** CS64 (justified-deferred), CS67–CS74, CS77, CQ23–CQ32,
TQ23–TQ27, TQ30–TQ32, BG21–BG28, WI63/64, WS60/61 and every other dispositioned/deferred item
in the per-step reports in this directory.

**Method.** Read the full post-diff `DatabaseExport.java` (856 lines), `DatabaseImport.java`
(2093 lines), `ValidatedGZIPInputStream.java`, `SpillableRecordBuffer.java`,
`FileUtils.durableAtomicMove`, the `SharedContext` genesis end-state, and the four migration
test classes; diffed each against the base commit `cced9df1af` to separate in-diff from
pre-existing behavior; cross-checked every claim against the design drafts, `track-8.md`
step/episode records, and the compact findings blocks of all twelve per-step review reports.

---

## 0. Decision criteria (stated before verdicts)

- **DC1 (seam symmetry):** for every artifact the Step-4 exporter emits (section tag, field
  name, value shape, count, framing byte), the Step-5/6 importer must consume it under the
  identical name/type/order assumption, and vice versa: every input the importer REQUIRES on
  the v15 path must be something the v15 exporter unconditionally produces. A defect exists if
  an honest v15 export can be rejected (false reject) or a violated assumption can pass
  (false accept) at a seam BETWEEN steps.
- **DC2 (end-state conformance):** the assembled dispatch must be exactly: undeclared/malformed
  exporter-version → fail-closed reject (SR2/WI12a); declared `<= 14` → lenient path
  byte-for-byte except deviations RECORDED in track-8.md/design drafts (R1 as amended by
  SR3); `== 15` → full strict matrix; `>= 16` → reject-with-redirect firing AHEAD of every
  v15 arm. Any unrecorded as-built deviation is a finding.
- **DC3 (cumulative coherence):** no dead code left by a later step superseding an earlier
  step's stopgap; no contradictory guards; no comments describing pre-fix behavior; no
  duplicated logic that a reviewer of a single step could not see duplicated.
- **DC4 (test architecture):** at least one test crosses ALL migration machinery end-to-end
  (real v15 export → file-ctor import → framing detection → pre-flight matrix → deferral →
  structural strictness → gzip drain → manifest cross-check) on a fresh target; fixture
  provenance should be the real exporter where the seam is the subject; duplication findings
  are suggestion-grade unless a gap is real.

Premises used throughout (verified by reading, citations inline):

1. The exporter writes exactly seven sections in the order `info`, `collections`, `schema`,
   `records` (+ the adjacent `brokenRids` array written inside `exportRecords`), `indexes`,
   `manifest` (`DatabaseExport.java:206-220`, `:314-413`, `:301-311`), unconditionally — no
   include/exclude option exists (`DatabaseImpExpAbstract.java:86-91` parses only
   `-useLineFeedForRecords`; `DatabaseExport.parseSetting:450-461` adds only compression +
   `-bestEffort`).
2. The importer's v15 required-section list is exactly those seven tags
   (`DatabaseImport.java:591-592`).
3. `runPreFlightChecks` runs immediately after `importInfo` and before
   `runDeferredImportPreamble` (`DatabaseImport.java:334-335`), and its first arm is the
   `>= 16` redirect (`:447-453`).
4. `executeInTx` does not retry its lambda (`DatabaseSessionEmbedded.java:5108-5140` — single
   begin/commit, no retry loop), so the export's section writes and tallies cannot double-run.
5. `JSONReader.hasNext()` is `in.ready()` (`JSONReader.java:612-614`) — the pre-existing
   contract of the main section loop (base `DatabaseImport.java:226` at `cced9df1af`), which
   the Step-6 F2 EOF-bounds inherit rather than invent.

---

## 1. Charter 1 — cross-step seams (export→import contract as built, end to end)

Exhaustive seam enumeration. Verdict per seam; every ✓ carries its justification.

| # | Seam | As-built evidence | Verdict |
|---|------|-------------------|---------|
| S1 | **Section tag set + order.** | Exporter tags (premise 1) vs importer required list (premise 2): the seven strings are character-identical (`"info"`, `"collections"`, `"schema"`, `"records"`, `"brokenRids"`, `"indexes"`, `"manifest"`). Order: importer consumes `brokenRids` inline right after `records` (`DatabaseImport.java:1823-1832`), matching the exporter writing it inside `exportRecords` (`DatabaseExport.java:405-412`); `manifest` is written last (`:206-220`) and its import arm is version-gated (`DatabaseImport.java:358-366`). | ✓ symmetric |
| S2 | **Manifest field names + width.** | Exporter writes `classes`/`indexes`/`records`/`brokenRids` as long-valued number fields (`DatabaseExport.java:304-309`, tallies declared `long` at `:104-107` + `recordExported:67`); importer parses all four via `JSONReader.readLong` (`DatabaseImport.java:559-566`, primitive at `JSONReader.java:97-104`) into `long` fields (`:163-170`). | ✓ symmetric (BG20/BG27 fix held end-to-end) |
| S3 | **Count provenance per manifest entry.** | `manifestClasses++` per class object written (`DatabaseExport.java:632`) ↔ `parsedSchemaClassCount++` per class object parsed (`DatabaseImport.java:1105`); `manifestIndexes++` after the `EXPORT_IMPORT` skip (`DatabaseExport.java:556-560`) ↔ `parsedIndexCount++` per dump index object (`DatabaseImport.java:1858`) — the skipped index never reaches the dump, so both sides count the same set; `recordExported++` per copied-out record (`:784`) ↔ `parsedRecordCount++` per non-empty array entry (`:1530`); `manifestBrokenRids = brokenRids.size()` (`:412`, a set of unique rids) ↔ `parsedBrokenRidCount++` per non-empty token (`:674`). The step-5 review audited this term-by-term; re-verified at the CURRENT line positions after the Step-6 accretion — no drift. | ✓ symmetric |
| S4 | **Info field set + types.** | Every field `exportInfo` writes (`DatabaseExport.java:504-538`: `name`, `exporter-version`, `engine-version`, `engine-build?`, `storage-config-version`, `schema-version`, `schemaRecordId`, `indexMgrRecordId`, `best-effort?`) has a matching import arm of the matching type (`DatabaseImport.java:811-880`: dispatch int, range-checked long, five string type-checks, one number type-check, one boolean). No exporter field falls into the unknown-field arm; no import mandatory field (exporter-version, schema-version for v15) is optional on the export side. | ✓ symmetric, complete both directions |
| S5 | **Best-effort marker shape.** | Exporter: `writeBooleanField("best-effort", true)` — unquoted `true` (`DatabaseExport.java:533`); importer: quote-stripped `parseBoolean` (`:861`) + WI12b boolean type check (`:856-858`, judged v15-only at `:487-492`). The unquoted honest shape arms the gate on both paths; the quoted hand-edited shape arms it on legacy (BG29 fix) and is a type violation on v15. | ✓ symmetric incl. the adversarial shape |
| S6 | **Gzip framing.** | File export = one GZIP member (`GZIPOutputStream`, `DatabaseExport.java:146-152`); file import = `ValidatedGZIPInputStream` + drain + `verifyFullyConsumed` + `verifyPhysicalSize` (`DatabaseImport.java:617-637`). Streaming export = PLAIN JSON (`:183`, unchanged from base `cced9df1af:104-113`); streaming import accepts plain as the caller's framing choice (`physicalSize == -1` keying at `:502`, WI10a resolution recorded in track-8.md Step 5). Cross-modal residue: a v15 streaming export SAVED TO A FILE then imported by the file ctor is rejected as non-gzip — this is Q-M3's ruled fail-closed behavior (no override), not a defect; noted for the record. | ✓ per ruling |
| S7 | **brokenRids token shape.** | Exporter writes rids via `writeString` → quoted (`DatabaseExport.java:409-411`); importer strips surrounding quotes before `fromString` (`DatabaseImport.java:663-670`, the Step-5 as-built note (b) fix). Legacy unquoted tokens pass through unchanged. | ✓ symmetric |
| S8 | **Record embedding charset.** | Per-record buffer encodes UTF-8 (`DatabaseExport.java:747`), copy-out decodes UTF-8 (`:821-833`) — lossless char round-trip — then `writeRaw` re-encodes through the shared writer's default charset (`:152`), which is the same default charset the importer's `InputStreamReader` decodes with (`DatabaseImport.java:262`). The buffer hop is charset-neutral; the main-stream charset symmetry is the pre-existing base contract (JEP 400 default UTF-8 on JDK 21). | ✓ no new asymmetry |
| S9 | **`EXPORT_IMPORT` rid-map artifacts.** | Export skips the rid-map INDEX (`DatabaseExport.java:553-557`); a source that itself carries the rid-map CLASS (a prior import target with `-deleteRIDMapping=false`) exports it as an ordinary class + records, and the importer drops/recreates it before records (`DatabaseImport.java:1707-1715`) — counts stay symmetric because both sides count dump content, not target state. | ✓ |
| S10 | **Step 1 blob embedding ↔ Step 5 blob mapping (charter question).** | End-state: a fresh target's genesis registers `$blob0..N-1` at ids `1..N` (`SharedContext.java:271-276` + `AbstractStorage.doCreate`); the dump's `blob-collections` ids are resolved ONLY through `collectionToCollectionMapping` (`DatabaseImport.java:1049-1075`), never raw; unmapped ids warn-and-skip. For a v15 dump the registry ids are always present in the collections section (they are real, named, non-null collections ≤ max id — `DatabaseExport.java:469-496` includes them), so the skip arm is unreachable on honest input. Union semantics on import: source blob collections absent from the target (larger source count, or custom-named blob collections) are created by `importCollections` and then blob-registered (`:1071-1074`); already-registered `$blob*` names are recognized via `getCollectionIdByName` and skipped. Both directions (source count >, <, = target count; custom names) converge to a consistent registry. | ✓ consistent end-state |
| S11 | **`internal` collection.** | Excluded from `records` (`DatabaseExport.java:322-326`) but INCLUDED in the `collections` section (`:469-496` has no internal exclusion; `getPhysicalCollectionNameById(0)` returns it, `AbstractStorage.java:5755-5762`); importer maps `internal→internal` (id 0→0) harmlessly via the name-first resolution (`DatabaseImport.java:1440-1445`). Note: the Step-5 Surprises bullet ("the internal collection is excluded from the dump's collections section") is INACCURATE as a statement about the current exporter — the fixture consequence it derived (`max(dump ids)+1` unused) holds anyway; doc-nit only, no code impact. | ✓ (doc nit noted) |
| S12 | **Empty corners.** | Zero-record source: `records` written as an empty array; `importRecord` returns null on the empty token without counting (`DatabaseImport.java:1524-1530`) — tallies stay 0 == 0. Zero-class source is unreachable (genesis always populates the schema before any export can run). | ✓ |
| S13 | **Version constants.** | `EXPORTER_VERSION = 15` (`DatabaseExport.java:65`) ↔ dispatch pivots 14/15/16 (`DatabaseImport.java:447,454,838`) ↔ `MIN_IMPORTABLE_SCHEMA_VERSION = 6` (`:174`) ↔ `SchemaShared.CURRENT_VERSION_NUMBER = 6` ↔ the schema section's rewritten `version` field (`DatabaseExport.java:612`) and the info `schema-version` (`:529`). One-constant-bump story intact (step-6 review null verdict re-confirmed). | ✓ |

**Seam findings:** none at should-fix or blocker severity. One suggestion-grade behavioral
finding adjacent to S8's buffer (BG31, §5) and one test-side seam gap (TQ33, §4).

**Alternative-hypothesis check (charter 1).** I actively looked for the three classic
cross-step failure shapes: (a) a Step-4 artifact Step-5 never consumes — none: all seven tags,
all four manifest fields, all nine info fields consumed; (b) a Step-5/6 requirement Step 4 can
fail to produce — candidate found and traced: the `manifest` section is required (premise 2)
and written unconditionally (premise 1; no include/exclude options survive in the tool);
`classes` array absent for an empty schema is unreachable (S12); (c) a double-write under tx
retry inflating tallies — refuted by premise 4.

---

## 2. Charter 2 — end-state conformance vs the M2 design end-state

**Dispatch matrix as built (exhaustive, one row per ruled cell):**

| Declared exporter-version | Ruled outcome (Q-M2/SR2 as amended) | As-built | Cite | Verdict |
|---|---|---|---|---|
| undeclared | reject at first non-`info` tag or EOF (SR2/CS46) | identical, both arms | `DatabaseImport.java:319-324`, `:371-375` | ✓ |
| malformed/unparseable | reject fail-closed, same outcome as undeclared (WI12a) | reject at parse naming the raw token | `:815-819` | ✓ |
| `<= 13` | lenient + backwards-compat serializer | identical to base | `:838-840` | ✓ |
| `== 14` | lenient byte-for-byte (R1), except recorded deviations | lenient; recorded deviations audited below | section loop `:343,:359-364` v15-gated | ✓ (one unrecorded micro-deviation → CQ33) |
| `== 15` | full strict matrix | schema-version range (`:454-479`), type violations (`:487-492`), unknown-field logging (`:495-499`), non-gzip file rejection (`:502-508`), ack gate (`:514-519`), section presence/duplicates + manifest cross-check + WI10b + CS43 drain (`:590-639`) | — | ✓ |
| `>= 16` | reject-with-redirect naming both versions, AHEAD of every v15 arm | first arm of `runPreFlightChecks` (`:447-453`), which runs immediately after `importInfo` (`:334`) — no `>= 15`-keyed arm (pre-flight OR section loop OR post-loop strictness) is reachable for a `>= 16` dump, so the surviving `>= 15` keys are effectively `== 15`, exactly the recorded Step-5 end-state note (a) | — | ✓ |

**Reachability proof for the "effectively == 15" claim:** the only `>= 15`-keyed arms are the
`clusters` alias rejection (`:343`), the `manifest` tag gate (`:359`), the post-loop
strictness (`:380`), and the pre-flight arms after the redirect (`:454`, `:502`). All sit
strictly after `runPreFlightChecks`'s throw for `>= 16` (the section loop cannot proceed past
the `info` arm because `importInfo`'s caller invokes pre-flight inline at `:334` before
returning to the loop). Nothing v15-flavored can fire on a `>= 16` dump, and nothing of the
preamble either (`runDeferredImportPreamble` at `:335` is after the throw) — the redirect is
genuinely pre-mutation. Verified against the F4/TQ29 ordering pin
(`v16RedirectFiresAheadOfSchemaVersionArms`, discrimination proven in the Step-6 episode).

**Deviations audit (is every as-built deviation recorded?).** Enumerated every behavior delta
on the declared-legacy path introduced by the cumulative diff:

1. Ungated dangling/malformed info field-name guard (`:798-808`) — RECORDED (Step-6 episode
   as-built note (f); CS77 deferred).
2. Ungated scalar-only info-value guard (`:903-911`) — RECORDED (F3 chosen-remedy record).
3. Ungated EOF bounds on info/manifest loops (`:786-797`, `:557-573`) — RECORDED (F2 record +
   gate note).
4. Marker-keyed (not version-gated) ack gate — RECORDED as ruling SR3.
5. Legacy gzip decoding through the single-member validated decoder (multi-member legacy
   concatenations now rejected) — RECORDED (Step-5 as-built note (c)).
6. `Files.size` probe / `NoSuchFileException` ctor delta — RECORDED (BG28 accepted).
7. **Legacy differing-value exporter-version re-declaration now rejected** (`:831-836`: the
   CS63 latch is version-UNGATED — a declared-`14` dump whose second info section re-declares
   `13` was last-wins-lenient at base, now throws "re-declares its exporter version"). The
   Step-5 F1 record states the SAME-value legacy duplicate stays tolerated but does not record
   the differing-value legacy widening as an R1-letter deviation, unlike its siblings (items
   1–5 above each carry an explicit record or ruling). Fail-closed direction, hand-damaged
   input only — but the SR3 precedent is that exactly this class of widening gets a recorded
   disposition. → **CQ33 (suggestion)**.

**Verdict (DC2):** the end-state is exactly the spec's (v15 strict, `>= 16` redirect,
`<= 14` lenient, SR2 fail-closed); one unrecorded suggestion-grade deviation (CQ33).

---

## 3. Charter 3 — cumulative code quality after six steps of accretion

Checked the four DC3 axes over the final `DatabaseExport.java` / `DatabaseImport.java`:

**Dead code / superseded stopgaps.** Step 4's temporary `skipManifest` arm is GONE — the
`manifest` case dispatches only to the validating `importManifest`
(`DatabaseImport.java:358-366`; `grep skipManifest` over `core/src/main/java` is empty). The
recorded Step-4→Step-5 replacement obligation is discharged in code, not just on paper. The
`gzipFramed` boolean duplicating `validatedGzipStream != null` remains (CQ25, deferred — not
re-filed). `useLineFeedForRecords` and `maxRidbagStringSizeBeforeLazyImport` are dead-ish but
pre-existing at base (`cced9df1af:DatabaseImport.java:123,987-988`) — out of diff scope.

**Contradictory guards.** None found. Specifically audited: (a) the double entry point into
pre-flight for a duplicate legacy info section — `runDeferredImportPreamble` is idempotent via
`preambleExecuted` (`:530-534`) and `runPreFlightChecks` is pure-check + logging, so a re-run
is harmless; (b) the `clusters` alias arm merges the occurrence before throwing (`:325` before
`:343`) — irrelevant post-throw, and the legacy path counts under `clusters` which the
(v15-only) presence check never reads; (c) export-side `completed` ordering — set only AFTER
`promote()` (`DatabaseExport.java:233-234`, the CS62 fix), with `close()` (`:421-436`) and
`cleanUpOnFailure` (`:277-291`) as deliberately redundant belts (both delete the unique temp,
never the final name).

**Stale comments.** Every comment sampled describes the CURRENT mechanism, including the
tricky ones: the CS52 belt-placement comment (`SharedContext.java:155-166`), the CS47 proxy
re-routing comment (`SharedContext.java:257-269`), the CS63 latch comment
(`DatabaseImport.java:824-830`), the WI10a keying comment (`:216-221`), and the Q-M3 arm's
CQ24-resolved-by-ordering comment (`:496-501`). The one inaccurate prose statement found lives
in `track-8.md` (S11's "internal excluded from the collections section"), not in code.

**Duplicated logic.** The export/import contract's string literals are duplicated as bare
literals on both sides of the seam: section tags (`DatabaseExport.java:206-220` writers vs
`DatabaseImport.java:326-368` + `:591-592`), manifest field names (`:304-308` vs `:559-566`),
and the `best-effort` marker (`:533` vs `:857`). Six steps built this up pairwise; no shared
constants exist, so a rename on one side compiles clean and fails only at (round-trip) test
time. Suggestion-grade consolidation → **CQ34**. (Test-side helper duplication is TQ32,
deferred — not re-filed.)

**Cross-cutting behavioral audit (new finding).** The best-effort catch in `exportRecord`
(`DatabaseExport.java:761-777`) classifies EVERY `Exception` out of the rendering — including
an environmental `IOException` from the spill buffer's own file I/O
(`SpillableRecordBuffer.write/ensureCapacity:41-77`, e.g. disk-full in the spill directory) —
as "the record seems corrupted": under `-bestEffort=true` a HEALTHY oversized record is then
discarded into `brokenRids` and the export continues and promotes. Default mode aborts
(correct); the copy-out stays whole-or-fatal in both modes (`:779-782` outside the catch —
step-4 O4 discharge unchanged). This conflation makes FM-M9's "an oversized-but-healthy record
is exported, not shed" silently degradable by environment in the operator's opt-in mode. The
dump remains honest (marker set, rid recorded, manifest consistent), so this is
suggestion-grade → **BG31**. Per-step reviews audited the buffer's lifecycle paths
(baseline-step4 §SRB table) and the catch's scope (crash-safety-step4 O4) separately; the
classification question sits exactly between them.

---

## 4. Charter 4 — cumulative test architecture

**The headline question — does one test cross ALL the machinery?** YES:
`DatabaseImportInfoMatrixTest.endToEndMigrationRehearsalPreservesLogicalContent`
(`DatabaseImportInfoMatrixTest.java:515-583`) drives a REAL populated-source
`DatabaseExport` (typed property, UNIQUE index, plain + linked records, a blob) through the
FILE-ctor import into a FRESH target: that single pass exercises framing detection →
`importInfo` capture → the full Q-M2 pre-flight matrix → the deferred preamble → section
loop with occurrence tracking → blob-id mapping → post-loop structural strictness → CS43
drain + physical-size arithmetic → manifest-vs-tally cross-check, and then asserts logical
equivalence including link topology, the index, and blob bytes. The import succeeding proves
the strict path passed end-to-end (a manifest/count/gzip regression fails this test). Null
verdict on the charter's gap question.

**Fixture provenance at the seams.** The hardening/matrix suites generate dumps with the real
Step-4 exporter and mutate JSON-level (`DatabaseImportHardeningTest.exportDump:40-45`,
`mutateDump:118-126`) — the right provenance: tamper tests stay honest about what the real
exporter emits (Jackson `ObjectNode` preserves field/section order, so mutations do not
silently reorder sections).

**Cross-step round-trip gaps (suggestion-grade).** Two exporter-produced shapes are verified
dump-side but never driven through the importer: (a) a GENUINE `-bestEffort` dump with a real
discarded record (`DatabaseExportHardeningTest.bestEffortDiscardsWholeRecordAndRecordsTheMarker:255-293`
parses the dump but never imports it; the import-side ack tests build the marker/brokenRids by
`mutateDump` instead — `DatabaseImportHardeningTest.java:431-449,478-492`); (b) a SPILLED
oversized record's dump (`oversizedRecordSpillsAndIsExportedWhole:300+` re-reads through the
primitive only). The mutated fixtures are byte-shape-equivalent for the marker
(`writeBooleanField` ↔ `put(...,true)`), so the residual risk is low; a real-best-effort →
ack-import round trip (and/or importing the spilled dump) would close the last unexercised
exporter-shape × importer-path cells → **TQ33 (suggestion)**.

**Overlap.** `DatabaseImportHardeningTest` (structural skeleton, 21 tests) vs
`DatabaseImportInfoMatrixTest` (info matrix, 19) split cleanly along the Step-5/Step-6 seam:
truncation tests target different arms (gzip trailer vs in-section EOF bounds), the two
best-effort pins target gate vs type-check. No test duplicates another's rejecting mechanism
(spot-checked by matcher strings). Helper duplication across the two classes plus the doc
test's CWD assumption is TQ32 (deferred, not re-filed).

**Genesis-side suites** (`StorageEmbeddedBlobCollectionsTest`, `GenesisSchemaBootstrapTest`,
`TwoPhaseGenesisTest`, `GenesisFailureContainmentTest`) were reviewed as-assembled at Step 3
(genesis files untouched by Steps 4–6, so the per-step review already saw the track end-state);
no cumulative gap found beyond the recorded infeasible drop-level W1/W2 test (CS54
disposition).

---

## 5. Findings

### BG31 — suggestion — `DatabaseExport.java:761-777` + `SpillableRecordBuffer.java:41-77`
**Best-effort mode conflates environmental spill-I/O failure with record corruption.**
The render catch (`catch (final Exception t)` at `:761`) wraps the whole
generator-into-buffer pipeline; a spill-file `IOException` (disk-full in the dump's directory,
`ensureCapacity:69-76`) is indistinguishable from a corrupt record. Default mode aborts
(fail-fast holds); under `-bestEffort=true` the healthy record lands in `brokenRids` with a
"record seems corrupted" classification and the export promotes.
**Counterexample:** `-bestEffort=true`, spill threshold 32 MB, a healthy 40 MB record, spill
volume with < 8 MB free → record discarded as "broken", export completes and promotes;
FM-M9's oversized-but-healthy guarantee silently degrades to shed-with-audit-trail.
**Remedy sketch:** let `SpillableRecordBuffer` wrap its own I/O failures in a distinct
exception type and rethrow that type even under best-effort (environmental failure ≠ record
corruption).
**Alternative hypothesis checked:** could the copy-out arm rescue this? No — the failure
happens before `openContent()`; the copy-out's whole-or-fatal contract (`:779-782`) is not
reached for a discarded record.

### CQ33 — suggestion — `DatabaseImport.java:831-836` (+ Step-5 F1 record in `track-8.md`)
**Unrecorded R1-letter deviation: the CS63 exporter-version latch is version-ungated, so a
declared-LEGACY dump re-declaring a DIFFERENT legacy version is now rejected** (base: silent
last-wins). Fail-closed, hand-damaged-input-only — the same doctrinal class as SR3/CS66,
which received a recorded ruling; this one is only implicit in the F1 fix text ("rejects any
re-declaration with a DIFFERING value"), which records the mechanism but not the legacy-path
widening as a deviation.
**Counterexample gist:** v13 dump with a second info section declaring `12` → base imported
via last-wins backwards-compat; now throws "re-declares its exporter version (13 -> 12)".
**Remedy:** one sentence in the track file/design drafts recording the widening (preferred),
or gate the latch's throw on `Math.max(old,new) >= 15` if the R1 letter is to be kept exact.

### CQ34 — suggestion — `DatabaseExport.java:206-220,304-308,533` vs `DatabaseImport.java:326-368,559-566,591-592,857`
**The export↔import wire contract exists only as duplicated string literals** (seven section
tags, four manifest fields, the `best-effort` marker) with no shared constants; six steps of
pairwise accretion mean a one-sided rename compiles and fails only in round-trip tests.
**Counterexample gist:** rename the exporter's `brokenRids` manifest field → importer's
sentinel `-1` mismatch fires as "manifest declares -1 brokenRids" (the CQ27 message) on every
honest dump — caught by tests, but as a puzzling downstream failure.
**Remedy:** a small shared `DumpFormat` constants holder in `core/.../db/tool/`.

### TQ33 — suggestion — `DatabaseExportHardeningTest.java:255-293` / `DatabaseImportHardeningTest.java:431-492`
**No test imports a GENUINE exporter-produced best-effort dump (or a spilled-record dump);
the import-side ack/brokenRids fixtures are `mutateDump` reconstructions.** The
reconstruction is shape-faithful today (S5), but a future exporter change to the marker or
brokenRids emission would keep the import-side suite green against stale shapes while the
real round trip breaks.
**Counterexample gist:** exporter switches the marker to `writeStringField("best-effort",
"true")` — export-side test catches the JSON change only if its assert is strict
(`asBoolean()` would still pass), import-side mutated fixtures keep injecting the OLD boolean
shape and stay green; the real best-effort round trip now trips the v15 type check.
**Remedy:** extend `bestEffortDumpRequiresExplicitAcknowledgment` (or add one test) to feed
`RenderFailureExport`'s real dump through the importer with and without the ack flag; import
the oversized-spill dump once.

---

## 6. Hypothesis log

| # | Hypothesis | Evidence sought | Outcome |
|---|---|---|---|
| H1 | The importer requires a section/field the exporter can omit (option-gated or conditional write) | full `parseSetting` chains + every conditional write in `DatabaseExport` | REFUTED — only `engine-build` and `best-effort` are conditional, both optional on import; the seven sections are unconditional (premise 1) |
| H2 | Manifest counts can drift between exporter tally sites and importer tally sites after Step-6 accretion | re-audit of all eight increment sites at final line positions | REFUTED — S3 term-by-term |
| H3 | A `>= 16` dump can reach a v15-keyed arm or the preamble (spec end-state broken) | reachability walk over every `>= 15` key and the `:334-335` call order | REFUTED — redirect fires first; effectively `== 15` (charter 2 proof) |
| H4 | `executeInTx` retry double-writes export sections / inflates tallies | `executeInTxInternal` body | REFUTED — no retry (premise 4) |
| H5 | The strictness drain double-counts or misses bytes buffered by `InputStreamReader` above the decoder | decoder-position argument; step-5 H2 re-checked against final code | REFUTED — buffered chars were already consumed FROM the decoder; drain is position-agnostic; `read` idempotent post-trailer (`ValidatedGZIPInputStream.java:86-99`) |
| H6 | `importSchema`'s pre-existing catch-swallow (`:1278-1283`) gained a fully-silent v15 path via the new counters | shape walk: mid-array failure → CN51 undercount; post-array failure → reader desync → non-quoted garbage tag → unsupported-tag throw (`JSONReader.readString:104-125` strips quotes only when quote-led, so `},"records"` never resolves to a clean tag) | REFUTED — matches crash-safety-step5's audit; no re-file |
| H7 | Step-1 genesis blob registry and Step-5 import mapping can produce an inconsistent registry (double-register, class-as-blob, unregistered physical blob) | S10 walk over count >, <, =, custom-name cases | REFUTED — union semantics consistent |
| H8 | Best-effort catch classifies environmental I/O as record corruption | catch scope vs `SpillableRecordBuffer` I/O sites | CONFIRMED → BG31 (suggestion) |
| H9 | An as-built legacy-path delta exists that no record/ruling covers | enumeration of all 7 legacy deltas vs records | CONFIRMED for the differing-value version re-declaration → CQ33 |
| H10 | The E2E rehearsal bypasses part of the strict machinery (e.g. stream ctor, or manifest check vacuous) | rehearsal fixture path + `runImport` (file ctor) + strictness reachability | REFUTED — file ctor, v15, full matrix crossed |
| H11 | The charset hop introduced by the record buffer breaks non-ASCII round-trips under a non-UTF-8 default charset | S8 encode/decode chain | REFUTED — buffer hop lossless; main-stream charset symmetric and pre-existing |

---

## 7. Compact findings block

| ID | Severity | Location | Summary | Counterexample gist |
|---|---|---|---|---|
| BG31 | suggestion | DatabaseExport.java:761-777 + SpillableRecordBuffer.java:41-77 | Best-effort mode classifies environmental spill-I/O failure as record corruption — a healthy oversized record is shed into brokenRids and the export promotes | `-bestEffort=true` + disk-full spill dir + healthy 40 MB record → discarded as "corrupted", dump promotes; FM-M9 degraded |
| CQ33 | suggestion | DatabaseImport.java:831-836 | Version-ungated CS63 latch rejects a declared-legacy dump re-declaring a DIFFERENT legacy version (base: last-wins) — an R1-letter widening without a recorded disposition (SR3 precedent) | v13 dump + second info declaring 12 → base imported, now "re-declares its exporter version (13 -> 12)" |
| CQ34 | suggestion | DatabaseExport.java:206-220,304-308,533 ↔ DatabaseImport.java:326-368,559-566,591-592,857 | Export↔import wire contract lives as duplicated bare string literals (7 tags, 4 manifest fields, marker); one-sided rename compiles clean | rename exporter manifest field → every honest dump rejects with "manifest declares -1 …" |
| TQ33 | suggestion | DatabaseExportHardeningTest.java:255-293 / DatabaseImportHardeningTest.java:431-492 | No import of a GENUINE best-effort (or spilled-record) exporter dump — ack-gate fixtures are mutateDump reconstructions that can drift from the real exporter shape | marker emission change → import suite stays green on stale injected shape while the real round trip breaks |

## 8. Null-verdict notes per clean criterion

- **Charter 1 (cross-step seams):** NULL VERDICT on the export→import contract — all 13
  enumerated seams (S1–S13) symmetric as built; the only cross-modal asymmetry (streaming
  export saved to file → file-ctor rejection) is Q-M3's ruled behavior. Step-1 blob embedding
  ↔ Step-5 blob mapping end-state consistent in every count/name permutation (S10).
- **Charter 2 (end-state conformance):** NULL VERDICT on the dispatch itself — every ruled
  cell implemented per spec, redirect provably ahead of every v15 arm, SR2 both arms wired,
  schema-version range one-constant-bump, pre-flight genuinely pre-mutation. Sole residue is
  the unrecorded CQ33 micro-deviation (documentation, not behavior-vs-spec: the spec never
  ruled the differing-value legacy duplicate cell).
- **Charter 3 (cumulative coherence):** NULL VERDICT on dead code (Step-4 stopgap fully
  replaced), contradictory guards (none), and stale comments (none in code; one inaccurate
  prose bullet in track-8.md noted at S11). Residues: CQ34 literal duplication, BG31
  classification conflation.
- **Charter 4 (test architecture):** NULL VERDICT on the headline gap — the E2E rehearsal
  crosses all migration machinery on a fresh target with real-exporter provenance; suite
  split along the step seam is clean with no duplicated mechanism. Residue: TQ33 fixture
  provenance for two exporter shapes.
- **Genesis unit cumulative:** NULL VERDICT — the assembled two-phase create +
  containment + marker end-state (`SharedContext.java:152-306`) matches design §A1/G2 as
  amended (CS52 belt placement, CN54 drop exemption, CQ15 pre-tx root shells, CS47 proxy
  routing, CN50 single config read all verified present in the final tree); Steps 4–6 touched
  none of these files, so the Step-3 review-fix state IS the track end-state.

**Overall verdict: 0 blockers, 0 should-fix, 4 suggestions (BG31, CQ33, CQ34, TQ33).** The
track's cumulative diff is coherent end-to-end; the export→import contract is symmetric as
built; the assembled dispatch matches the ruled M2 end-state.
