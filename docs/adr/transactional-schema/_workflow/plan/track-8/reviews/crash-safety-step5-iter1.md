# Crash-Safety / Durability + Fail-Closed Robustness Review — Track 8 Step 5, iteration 1

- **Commit under review:** `5173bccd10` ("Harden import: pre-flight deferral, v15 strictness")
  on branch `transactional-schema`, diffed against parent `8ca99846fc`; HEAD at review time
  `a0fadb4334` (track-file update only — `DatabaseImport.java` is byte-identical between
  `5173bccd10` and HEAD, so all line citations below are HEAD line numbers).
- **Perspective:** crash-safety / durability + fail-closed robustness. Finding IDs CS63+.
- **Binding spec:** `plan/track-8.md` Step 5 spec + Step 5 episode (incl. the WI10a resolution
  and as-built notes (a)-(c)); `plan/track-8-design-drafts.md` M2.b intro + M2.b-1..5
  (drafts:370-436), rulings R1/Q-M2/Q-M3 (drafts:19-26, 627-641), SR1/SR2 (drafts:659-684),
  §A2 CS38/WI11 (drafts:815-830), §A3 WI1 (drafts:832+), CS43 (drafts:399-413), CN51
  (drafts:345-356, 417-419), WI10a/b/c (drafts:413, 418-420, 726), FM-M6..M8/M12..M16
  (drafts:445-456).
- **In-scope files:** `core/.../core/db/tool/DatabaseImport.java` (primary),
  `core/.../core/db/tool/ValidatedGZIPInputStream.java` (unchanged this step; CS43 sequence
  consumer-audit), `core/.../core/db/tool/DatabaseImportHardeningTest.java` (new);
  supporting reads: `DatabaseExport.java` (manifest tally provenance + section write order),
  `JSONReader.java` (reader semantics on malformed/truncated input).
- **Mode:** read-only; no Maven; no production/test file modified; only this report written.

## 0. Review obligations (criteria + premises)

**Criteria (the step's promises, restated as checkable obligations):**

- **O1 (charter #3 — fail-closed v15):** no input shape that is structurally inconsistent,
  truncated, tampered, or self-contradictory under the v15 contract is silently accepted; the
  strictness gate itself (`verifyV15StructuralStrictness`, armed by `exporterVersion >= 15` at
  loop end, `DatabaseImport.java:347-349`) cannot be disarmed by dump content that already
  received strict-armed treatment mid-parse. No behavioral regression for declared `<= 14`
  dumps beyond the ruled SR2 rejection.
- **O2 (charter #5 — CN51):** manifest-vs-tally arithmetic is symmetric (exporter counts what
  it WROTE into the dump; importer counts what it CONSUMED from the dump) and its verdict
  cannot be satisfied vacuously; where a consumed-but-not-applied record can exist, the
  design-contract boundary (consumption provenance, per M2.a-5/M2.b-3) is stated explicitly.
- **O3 (charter #1 — SR1 boundary):** every throw point on the import path is classifiable as
  pre-flight (zero target-storage mutation) or post-mutation (SR1-condemned), and NO code
  that mutates the target runs before `runDeferredImportPreamble()` (:439).
- **O4 (charter #2):** the mid-import failure state is loud, cause-preserving, and within the
  SR1 condemn-target envelope; residual obligations (operator doc) are named.
- **O5 (charter #4):** streams/FDs/session state are closed/restored on the pre-flight
  rejection path, the post-mutation rejection path, and the success path.

**Premises (traced code semantics relied on below):**

- P1: `JSONReader.readNext(...)` (JSONReader.java:196-282) returns SILENTLY (stale `value`,
  no exception) when the underlying reader is not `ready()` (:203-205), and returns the
  partial buffer without error when EOF is hit mid-scan (`nextChar() == -1` → `break`,
  :262-265) as long as the buffer is non-empty. Truncation detection therefore does NOT come
  from the JSON layer; on the v15 gzip path it comes from the decoder (loud `EOFException`/
  `ZipException` during parse or drain), which is exactly the design's layering (CS43).
- P2: `JSONReader.readInteger` → `Integer.parseInt` (JSONReader.java:84-95): a malformed or
  out-of-int-range numeric token throws `NumberFormatException` (unchecked), which escapes
  `importInfo`/`importManifest` and is wrapped loudly by `importDatabase`'s catch (:369-386).
- P3: `exporterVersion` (field, :115) starts `-1`; the ONLY assignment is
  `importInfo` (:693), executed once per `info` tag occurrence — a later `info` section
  OVERWRITES the earlier value (last-write-wins), and a repeated `exporter-version` field
  WITHIN one info object also overwrites (the field loop at :689-706 has no
  already-declared check).
- P4: `ValidatedGZIPInputStream` verifies the trailer only when the DECOMPRESSED stream is
  driven to end (read returns -1 → `readTrailer()`, ValidatedGZIPInputStream.java:86-99,
  247-274). The JSON section loop stops at the dump root's closing `}`; without the post-loop
  drain (:519-537 in DatabaseImport) the trailer bytes are never touched.
- P5: `DatabaseImportException extends BaseException` (NOT `DatabaseException extends
  CoreException`); the `importRecord` swallow arm (:1356-1370) rethrows everything EXCEPT
  `DatabaseException` and its subclasses (`CorruptedRecordException`,
  `NoTxRecordReadException`, `GenesisIncompleteException`, plus 51 production files throwing
  bare `new DatabaseException(...)`).
- P6: the v15 exporter writes sections in the fixed order info → collections → schema →
  records (+ brokenRids array immediately after, inside `exportRecords`) → indexes →
  manifest, with NOTHING after the root's closing brace (DatabaseExport.java:208-216,
  405-412); manifest counts are exporter-tallied at the write sites
  (`manifestClasses++` :632, `manifestIndexes++` :560, `recordExported++` :784,
  `manifestBrokenRids = brokenRids.size()` :412).

## 1. Criterion 3 — fail-closed / silent-acceptance gaps in the v15 path (worked FIRST)

### 1.1 The strictness gate's arming variable (the charter's headline question)

**Decision criterion:** the v15 structural checks must be governed by the SAME declared
version that governed the strict-parse decisions taken during the section loop; if dump
content can make the loop-end gate (`if (exporterVersion >= 15)` :347) evaluate differently
from the version that armed the mid-loop strict behavior, the gate is disarmable.

**Trace.** By P3, `exporterVersion` is last-write-wins and a second `info` section is a legal
tag at ANY loop position once a version is declared: the SR2 guard (:295-300) only fires when
`exporterVersion == -1`, and the WI10c duplicate-`info` rejection lives INSIDE
`verifyV15StructuralStrictness` (:490-505) — i.e., BEHIND the very gate the second `info`
section can disarm.

**Concrete input shape (traced end-to-end, every reader step checked):**

Take any honest v15 dump; before the root's closing brace append
`,"info":{"exporter-version":14}`; re-gzip (single member, valid trailer). Section sequence:
`info(v15)`, `collections`, `schema`, `records`(+inline `brokenRids`), `indexes`,
`manifest`, `info(v14)`.

1. Tag 1 `info`: occurrences info=1; `importInfo` → `exporterVersion = 15`; pre-flight passes
   (gzip-framed, no best-effort); preamble runs. All subsequent sections parse STRICT-armed
   (v14/v15 field names, `manifest` arm armed at :326).
2. `manifest` tag: `exporterVersion == 15` → `importManifest` latches the declared totals.
3. Tag 8 `info`: SR2 guard passes (version != -1); occurrences info=2 — recorded but never
   checked (see step 5); `importInfo` → `exporterVersion = 14` (P3). The trailing
   `readNext(COMMA_SEPARATOR)` (:707) consumes the root `}` and hits EOF; by P1 this returns
   silently (buffer non-empty). `runPreFlightChecks` re-runs and passes on the v14 matrix
   (:414 gzip check is `>= 15`-gated); `runDeferredImportPreamble` no-ops (:440-443).
4. Loop exits (`hasNext()` false at decoder EOF). SR2 EOF arm passes (version declared).
5. **`if (exporterVersion >= 15)` at :347 is FALSE → `verifyV15StructuralStrictness()` is
   SKIPPED IN ITS ENTIRETY**: no duplicate-`info` rejection (the check that would have caught
   this very shape), no section-presence check, no manifest-vs-tally verification (the
   manifest was consumed at step 2 but its totals are never compared), no
   brokenRids-without-marker check, and — decisively for the durability story — **no CS43
   drain / `verifyFullyConsumed` / `verifyPhysicalSize`** (:519-537 unreached).
6. `rebuildIndexes`, `metadata.reload`, `storage.synch`, success exit (:350-366). Import
   returns normally. **Silent acceptance.**

The same disarm works with a repeated `exporter-version` FIELD inside the single info object
(`{"exporter-version":15,"exporter-version":14}`, P3) — in that variant even a future
duplicate-SECTION check would not fire (occurrences info=1), so the fix must latch/reject
re-declaration at the FIELD level, not only deduplicate sections.

**Consequence.** Every check the step exists to add is bypassed at once for a dump that was
PARSED as v15 throughout: trailing garbage beyond the member, count-mismatched manifest,
duplicated sections, brokenRids-without-marker, truncated trailer (if the truncation is
combined with a re-gzip) — all silently tolerated. → **CS63 (should-fix)**.

**Severity calibration (why not blocker).** A tamperer with write access to the dump can
reach an equivalent silent lenient import through a design-SANCTIONED door: declare
`exporter-version: 14` outright and strip the manifest — ruling R1 deliberately trusts the
declared version and keeps `<= 14` lenient, and the operator-doc trust chain (WI3) already
assumes an untampered dump for the legacy path. CS63 therefore grants no materially new
attacker power; its content is a fail-closed CONTRACT defect: a self-contradictory
declaration is "malformed" in SR2's sense and must reject, the WI10c duplicate-info promise
is unreachable in the downgrade direction, and the strictness gate is evaluated against
mutated state. Remedy is small: latch the first parsed `exporter-version` and reject any
re-declaration (second `info` tag or second field) loudly at parse time — that is also the
Q-M2 "the version number is the compatibility contract" letter.

**Alternative-hypothesis check:** (a) could the second `info` fail to parse? No — `info` is a
legal tag, `importInfo` is shape-tolerant (unknown fields skipped :704-705), and the trailing
COMMA_SEPARATOR read is EOF-silent (P1). (b) Could the upgrade direction (14 → 15) also
bypass? No — traced: with 15 latched LAST the gate arms, and duplicate-`info` (occurrences=2)
rejects loudly; a v14-then-v15 dump also rejects earlier if it carries a `manifest` tag while
14 is current (:328-330). The gap is one-directional (downgrade). (c) Does anything after the
loop re-read the version? No — :347 is the only consumer.

### 1.2 Exhaustive shape enumeration along the changed paths (each checked)

| # | Input shape | Outcome (traced) | Verdict |
|---|---|---|---|
| S1 | First tag not `info` (any position of `info` later) | SR2 throw :295-300, pre-mutation | fail-closed ✓ |
| S2 | EOF (or root `}`) before any parseable version | SR2 EOF arm :338-342, pre-mutation (preamble locked) | fail-closed ✓ |
| S3 | `info` with literal `"exporter-version": -1` | indistinguishable from undeclared (:308 `!= -1`); preamble stays locked; next tag/EOF → SR2 | fail-closed ✓ (quirk noted, acceptable) |
| S4 | Malformed version value (`"fifteen"`, `1e2`, out-of-int-range) | `NumberFormatException` (P2) escapes `importInfo` pre-assignment → loud wrap; pre-mutation | fail-closed ✓ (Step 6 owns the ruled message shape) |
| S5 | v15 declared, plain JSON, FILE path | pre-flight Q-M3 throw :414-419, pre-mutation | fail-closed ✓ (test-pinned) |
| S6 | v15 declared, plain JSON, InputStream path | tolerated by the RECORDED WI10a resolution (`physicalSize == -1` keys the check off) — sanctioned, not a gap | null ✓ |
| S7 | v15 gzip, trailer truncated | drain hits `EOFException("Truncated GZIP trailer")` :523-527 / P4 | fail-closed ✓ (red-first pin) |
| S8 | v15 gzip, mid-deflate truncation | decoder throws during section parse | fail-closed ✓ |
| S9 | v15 gzip + raw trailing garbage / second member | `verifyFullyConsumed` (in-window) or `verifyPhysicalSize` (beyond-window, file) | fail-closed ✓ (test-pinned; stream ctor: in-window only — WI10a-sanctioned residual) |
| S10 | v15 missing / duplicated section | presence tracker :490-505 | fail-closed ✓ (test-pinned) — EXCEPT the alias shape S11 and the downgrade shape §1.1 |
| S11 | v15 dump carrying `collections` once AND `clusters` once (both tags dispatch to `importCollections` :315-318) | occurrences are tracked by RAW tag string; the required-list checks only `"collections"` → both pass as 1; the collections section effectively imports TWICE, silently | **gap → CS65 (suggestion)** |
| S12 | v15 dump with `clusters` instead of `collections` | presence check misses `collections` → rejected (over-strict, loud) | fail-closed ✓ |
| S13 | v15 manifest counts disagreeing with content | `verifyManifestCount` :541-549 | fail-closed ✓ (test-pinned) |
| S14 | v15 non-empty `brokenRids` without marker | :512-518 | fail-closed ✓ (test-pinned) |
| S15 | best-effort marker without ack flag | pre-flight :422-427 | fail-closed ✓ (test-pinned) |
| S16 | `best-effort` field with a non-boolean value | `readBoolean` → `Boolean.parseBoolean` → silently `false` — but a tamperer can simply delete the marker, so no new power; honest exporter writes a real boolean | null (noted) |
| S17 | v15 decompressed junk between the root `}` and the gzip trailer | the drain :523-527 CONSUMES and discards it; CRC/ISIZE cover it (it is part of the member) → silently accepted | **letter gap → CS68 (suggestion)** |
| S18 | v15 sections reordered (e.g. `schema` before `collections`, `records` before `schema`) | presence/duplicate/manifest checks are ORDER-blind; import semantics drift (`importSchema(collectionsImported=false)` triggers `removeDefaultCollections` :663-684 and id-less class creation :1009-1016) — silently accepted with a target that need not match the source | **letter gap → CS67 (suggestion)** |
| S19 | v15 dump whose post-records section is named ANYTHING (`"xRids": []`) | `importRecords` merges the `brokenRids` occurrence UNCONDITIONALLY (:1594-1596) and `processBrokenRids` consumes to the next `[` tag-blind (:551-556) → presence satisfied by a mislabeled section | **letter gap → CS74 (suggestion)** |
| S20 | manifest totals > `Integer.MAX_VALUE` (legit huge dump; exporter writes `long`, P6) | `readInteger`/`Integer.parseInt` :465-476 throws → loud FALSE reject of an honest dump | fail-closed but wrong-direction → **CS69 (suggestion)** |
| S21 | duplicate-`info` downgrade (§1.1) and duplicate version FIELD | **silent acceptance** | **CS63 (should-fix)** |

### 1.3 No-regression check for declared `<= 14`

- Preamble deferral: between the parent's preamble site (parent :214-223) and the first
  section dispatch nothing consumed the dropped classes; for a declared-legacy dump the
  preamble now runs at `info`-parse time — same relative order. Behavior-preserving ✓.
- SR2 rejection of version-less/reordered-info legacy dumps: ruled widening (SR2 text:
  "a dump rejected here does not ride the lenient path") ✓ sanctioned.
- Legacy gzip via the validated single-member decoder: only hand-crafted multi-member
  concatenations change behavior — recorded as-built note (c) ✓ sanctioned.
- Legacy trailer verification: the JDK decoder at the parent also verified the trailer only
  when the stream was driven to EOF, which the legacy reader (stopping at root `}`) never
  does; the new decoder is identical in that respect ✓ byte-for-byte.
- `processBrokenRids` quote-strip: unquoted legacy tokens pass through unchanged (:562-570) ✓.
- **One un-ruled widening:** `runPreFlightChecks` now runs for declared-legacy dumps too, and
  the best-effort ack gate (:422-427) is NOT version-gated — a declared-v14 dump hand-carrying
  `"best-effort": true` is now rejected where the parent skipped the unknown field. No legacy
  exporter ever wrote the field, and the direction is fail-closed, but it deviates from R1's
  "lenient fallback byte-for-byte" letter → **CS66 (suggestion)**.

## 2. Criterion 5 — CN51 tally provenance (worked SECOND)

**Decision criteria.** (D1) Symmetry: the exporter tallies what it WROTE into the dump; the
importer tallies what it CONSUMED from the dump — both sides count dump-array membership, not
target-DB state. (D2) Non-vacuity: the importer's counter must not be incrementable without a
real dump entry, and must not miss a real dump entry. (D3) Contract boundary: per M2.a-5 /
M2.b-3 the check verifies DUMP COMPLETENESS (nothing truncated/tampered between exporter and
importer), explicitly "never against target-DB queries" — it does NOT promise records LANDED.

**Premises.**
1. Export tallies (P6): `manifestClasses++` per class written (DatabaseExport:632),
   `manifestIndexes++` per index definition written (:560), `recordExported++` per record
   copy-out (:784, AFTER the whole-or-discarded copy — a best-effort-discarded record is not
   counted and not present, consistent), `manifestBrokenRids = brokenRids.size()` (:412).
2. Import tallies: `parsedSchemaClassCount++` per class object parsed
   (DatabaseImport:869-870), `parsedIndexCount++` per index object parsed (:1621-1623),
   `parsedRecordCount++` per non-empty record entry consumed from the array (:1292-1295),
   `parsedBrokenRidCount++` per non-empty rid token, quote-stripped, the empty-array
   placeholder token excluded (:571-575).
3. `parsedRecordCount++` executes AFTER the array entry is consumed and its emptiness checked,
   but BEFORE `fromStringWithMetadata`/apply (:1295-1297).
4. The `importRecord` catch (:1356-1370) logs and SWALLOWS `DatabaseException` (rolls the
   record tx back, import continues); everything else rethrows → loud (P5). Pre-existing
   behavior, byte-identical at the parent.

**Verdict on the arithmetic itself: symmetric and non-vacuous (null finding).** Each
increment site is 1:1 with a dump entry; the broken-rid empty-token exclusion matches the
exporter's `writeString` shape; a truncated records array under-counts and rejects; an
injected extra entry over-counts and rejects (test-pinned `manifestCountMismatchIsRejected`).
No path increments a counter without consuming the corresponding dump entry, and no dump
entry reaches its per-section parser without incrementing (the class counter sits before any
schema-API call :870; the index counter before the index-field loop :1623; the record counter
before deserialization :1295). Counting consumption BEFORE apply is the CORRECT side of the
boundary for D1/D3 — counting after apply would make an apply-failure look like dump
truncation and mis-blame the dump.

**The swallowed-DatabaseException question (charter's explicit resolution obligation).**
A record that is consumed from the array but whose deserialize/apply throws a
`DatabaseException` subclass (e.g. `CorruptedRecordException`, or any of the ~51 bare
`DatabaseException` throw sites reachable from `fromStringWithMetadata`/`delete`/
`updateFromMap`) is: counted (D1-correct), rolled back, logged at error level, and the import
CONTINUES and exits 0; the manifest cross-check passes because both sides legitimately count
dump membership. Resolution:

- **CN51-as-specified is DISCHARGED** — the contract is consumption-vs-written provenance,
  and the implementation matches its letter and its rationale (FM-M17). This criterion's
  verdict on the Step 5 implementation is a null finding.
- **BUT the swallow is a genuine silent-drop counterexample at the next layer up**: the
  track's D20 promise ("fails loudly rather than silently on any partial result") and Step
  6's planned operator doctrine ("import completeness = importer exit 0", WI3 content list)
  are both falsified by a dump record that trips a `DatabaseException` at apply time — the
  import exits 0 with the record absent from the target and only a log line as witness.
  Pre-existing (the catch predates Track 8), and outside Step 5's structural-skeleton seam —
  but Step 5 is the step that made "exit 0" load-bearing, and the v15-strict path is exactly
  where a rethrow (or an applied-count consistency check at strictness time) would be
  version-gated and regression-free. → **CS64 (should-fix, pre-existing qualifier; may be
  dispositioned to Step 6/follow-up, but must not be silently absorbed into the WI3 doc's
  completeness claim)**.

**Adjacent swallow audited (no additional finding):** `importSchema`'s whole-body
`catch (Exception)` (:1043-1046) also swallows-and-continues (pre-existing). For a v15 dump a
mid-classes-array failure under-counts `parsedSchemaClassCount` → CN51 rejects; a
post-array failure (inheritance rebuild, linked classes) leaves the reader BEFORE the
section's closing reads, so the section loop's next `readString(FIELD_ASSIGNMENT)` produces a
non-quoted garbage tag (e.g. `},"records"`) → unsupported-tag throw (:332-334) → loud
(post-mutation, SR1-condemned). Checked shape-by-shape; no fully-silent v15 path found —
the protection is accidental (reader desync) but real; recorded here so a future reader
refactor knows the swallow is load-bearing on that accident.

**Alternative-hypothesis check:** could a tamperer BALANCE the counters to hide a drop (e.g.
remove a record and decrement the manifest)? Yes trivially — but that is outside CN51's
threat model by ruling (the manifest is unauthenticated dump content; the check targets
truncation/inconsistency, not forgery). Could `parsedBrokenRidCount` be inflated by the
spliced second brokenRids section? Yes, but the duplicate-occurrence check fires first
(:1594-1596 + :502-505) — except via the CS63 downgrade, which disarms both together (folded
into CS63).

## 3. Criterion 1 — SR1 pre-flight vs post-mutation boundary (throw-point enumeration)

**Definitions.** "Target mutation" = any write to the target database's schema, indexes,
collections, or records (SR1's scope). Session-object state (validation flag, user field) and
process-global config are NOT target mutations but are audited under §5. "Pre-flight" = throw
reachable before `runDeferredImportPreamble()` (:439-457) has executed its first statement.

**The single mutation gate.** `runDeferredImportPreamble` is the FIRST target-mutating code
on the import path: `removeDefaultNonSecurityClasses` (:449 → :710-776, drops indexes at
:736 and classes at :760 — the first storage writes), then the index-manager reload +
auto-index snapshot, then the `beforeImportSchemaSnapshot` capture (:456, order-coupled with
the drop, WI11 honored — moved WITH the block into the field consumed by `importRecords`
:322). It is called from exactly ONE site (:311), strictly after `importInfo` succeeded with
a parseable version (:308) and `runPreFlightChecks` returned (:310). The
`preambleExecuted` latch (:440-443) makes re-entry harmless.

**Pre-flight (pre-mutation) throw points — enumerated:**

| # | Throw point | Location | Mutates target first? |
|---|---|---|---|
| PF1 | `validateSessionImpl` | ctor → :220-225 | no |
| PF2 | `Files.size` (missing/unreadable file) | file ctor :174 | no |
| PF3 | `detectFraming` — decoder ctor failure + `reset()` failure (>1024-byte header, see CS73) | :205-218 | no |
| PF4 | `checkSecurity` | :268 | no |
| PF5 | root `BEGIN_OBJECT` read / decoder errors during any pre-info read | :275, loop :288-290 | no |
| PF6 | SR2 first-non-info-tag rejection | :295-300 | no (preamble provably locked: `exporterVersion == -1` ⇒ `importInfo` never ran ⇒ :311 never reached) |
| PF7 | `importInfo` parse failures (P2) | :304-306, :686-707 | no |
| PF8 | Q-M3 non-gzip v15 rejection | :414-419 | no |
| PF9 | best-effort ack rejection | :422-427 | no |
| PF10 | SR2 end-of-stream arm | :338-342 | no (same lock argument as PF6 — reachable only with version undeclared) |

Nothing between the ctor and :311 writes to the target: `importInfo` only reads dump bytes
and sets importer fields; `runPreFlightChecks` is read-only; the SR2 guards are read-only.
The two `removeDefaultCollections` call sites (`importSchema:779-781`,
`importCollections:1147-1149`) are inside section arms, which are structurally reachable only
after PF6's gate — i.e. post-preamble. **O3 holds; charter criterion 1 verdict: null
finding.** (The test file pins PF6/PF8/PF9 with target-unmutated assertions —
`plainJsonV15DumpIsRejectedBeforeAnyMutation`, `bestEffortDumpRequiresExplicitAcknowledgment`,
`undeclaredExporterVersionIsRejectedBeforeMutation`, `emptyDumpIsRejected`.)

**Post-mutation (SR1-condemned) throw points — enumerated:** unsupported tag (:332-334),
manifest-under-legacy tag (:328-330), every section parser's parse/apply errors
(collections :1139+, schema :778+ — modulo the swallowed arm, §2 —, records :1471+ wrapped
loudly at :1581-1585, indexes :1607+), the strictness matrix (:347-349 → :490-538), the CS43
drain/verify errors (:519-537), `rebuildIndexes` (:350-352), `metadata.reload` (:357),
`storage.synch` (:359), `removeExportImportRIDsMap` (:363-365). All route through the
outer catch (:369-386), which preserves the primary as the wrapped cause and rethrows —
loud in every case. One boundary subtlety verified: SR2's arms can NEVER fire post-mutation
(both require `exporterVersion == -1`, which is mutually exclusive with the preamble having
been unlocked), so the SR2 tests' "target unmutated" assertions are structurally guaranteed,
not fixture luck.

## 4. Criterion 2 — mid-import failure-state analysis

**State after a post-preamble throw** (any of the §3 post-mutation points), traced:

- Default non-security classes and their indexes are DROPPED (preamble); whatever sections
  parsed before the throw are partially applied (collections created, classes created,
  records partially imported inside per-record committed txs, `___exportImportRIDMap` class +
  UNIQUE index possibly present :1472-1479); auto-indexes may be dropped-but-not-rebuilt
  (`indexesToRebuild` consumed only at :350-352 / :1225+); leftover pre-import records may or
  may not have been deleted (:1575-1581); link migration may be half-done.
- The failure is LOUD: `DatabaseExportException` (historical misnomer, pre-existing) wrapping
  the primary cause with reader line/column context (:369-386). No secondary can mask the
  primary on this path: the finally block's `setValidationEnabled` cannot throw
  (plain field set), and `close()` (:624-634) swallows its own `IOException` — so the
  primary always propagates. ✓
- `storage.synch()` (:359) is success-path-only, so a failed import leaves no false
  durability signal; nothing marks the target as condemned IN-BAND — a subsequent open of
  the half-imported target succeeds silently. That is exactly SR1's ruled envelope
  ("structural whole-stream rejections are inherently post-mutation... target CONDEMNED,
  never returned to service; NO two-pass import") with the operator procedure (WI3, Step 6)
  as the compensating control. **Verdict: acceptable per SR1 + the Step-6 doc obligation;
  null finding** — with the explicit dependency note that Step 6 MUST land the WI3 page
  (including CS64's caveat on the "exit 0 = completeness" claim) for this acceptance to be
  complete.
- A JVM crash mid-import differs from a thrown rejection only in losing the log breadcrumb;
  the on-disk target is the same condemned shape (per-record txs commit incrementally; no
  import-scoped WAL bracket exists or was promised). Same SR1 envelope. ✓
- Process-GLOBAL residue on one failure path exists and is NOT covered by the condemn-target
  doctrine: `INDEX_IGNORE_NULL_VALUES_DEFAULT` is flipped around `createIndex` and restored
  only on the success line (:1730-1742) — a `createIndex` throw leaves the process-wide
  default mutated for every OTHER database in the JVM. Pre-existing, unchanged by this diff
  → **CS72 (suggestion)**.

## 5. Criterion 4 — resource lifecycle under failure

Audited across the three path classes (pre-flight rejection / post-mutation rejection /
success):

1. **Validated gzip decoder + underlying file FD (gzip arm).** `close()` (:624-634) closes
   `validatedGzipStream`; `InflaterInputStream.close()` closes the chained
   `BufferedInputStream` → `FileInputStream`, and the override ends the self-allocated
   inflater (ValidatedGZIPInputStream:101-109). `importDatabase`'s `finally` (:390-393) calls
   `close()` on ALL exits, including `Error`s; the null-out makes it idempotent; a drain
   failure still reaches it. ✓ (This is an improvement over the parent's empty `close()`.)
2. **Plain-JSON arm (legacy dumps AND the new Q-M3 v15-plain rejection path).** `close()`
   deliberately releases nothing (`validatedGzipStream == null`); the
   `BufferedInputStream`/`FileInputStream` behind `jsonReader` stays open until GC — the
   in-code comment owns this as "the historical lifecycle". Pre-existing for legacy imports,
   but the NEW pre-flight rejection paths (Q-M3, SR2 on a plain file) now also exit with the
   dump file's FD pinned — on Windows the rejected file cannot be deleted/replaced until GC.
   → **CS70 (suggestion)**.
3. **Decoder construction failure inside `detectFraming` (:205-218).** The catch-all
   fallback `reset()` is safe for the dominant shape (non-gzip magic fails after 2 bytes;
   ctor ends its inflater before rethrowing). But a gzip-magic file whose header parse fails
   AFTER >1024 bytes (adversarial/corrupt FNAME/FEXTRA — `mark(1024)` at :206) makes
   `reset()` itself throw from WITHIN the catch; the ctor then propagates the reset
   `IOException` ("Resetting to invalid mark" — masking the real header defect) and the
   `FileInputStream` opened at :176-177 leaks with no owner. Fail-closed (loud) but
   cause-masking + FD-leaking. Same mark-limit shape existed at the parent; the new decoder
   makes the >1024-byte header parse reachable byte-for-byte identically. → **CS73
   (suggestion, pre-existing shape)**.
4. **Session flags.** `setValidationEnabled(preValidation)` is restored in the `finally`
   (:391) on every path ✓. `session.setUser(null)` (:277) is restored on NO path — success
   included; the caller's session continues user-less (auth/audit context lost). Byte-
   identical at the parent (the deferral did not move it) → pre-existing → **CS71
   (suggestion)**. Note the ordering nuance: both flags are set BEFORE pre-flight, so even a
   "target byte-for-byte untouched" pre-flight rejection returns a session whose user was
   nulled — worth folding into whatever Step 6 documents about pre-flight purity (the CS38
   guarantee is about the DATABASE, and that letter holds).
5. **Process-global config.** `INDEX_IGNORE_NULL_VALUES_DEFAULT` set/restore not
   exception-safe (§4) → CS72.
6. **Strictness-path drain buffer** (:522) is heap-local; drain failures propagate and still
   reach `close()` ✓. `verifyPhysicalSize` uses the ctor-time `Files.size` value — a file
   swapped mid-import produces a loud mismatch, never a silent accept ✓.

## 6. Findings (detailed)

### CS63 — should-fix — a trailing duplicate `info` section (or repeated `exporter-version` field) re-declaring `<= 14` disarms the ENTIRE v15 structural strictness matrix after the strict-armed parse already ran
`DatabaseImport.java:295-311` (SR2 guard passes once a version is declared; no re-declaration
latch), `:693` (last-write-wins assignment), `:347-349` (gate reads the FINAL value),
`:490-538` (every check, including the duplicate-`info` check that would catch this shape and
the CS43 drain, lives behind the gate). Full trace + counterexample in §1.1; severity
calibration (equivalent-power sanctioned bypass via a plain v14 declaration) in §1.1. Remedy:
latch the first parsed version and reject re-declaration (field- and section-level) loudly at
parse time — pre-mutation-safe for the first occurrence, condemn-target for a late one.

### CS64 — should-fix (pre-existing) — `importRecord` swallows `DatabaseException`, so a consumed-but-not-landed record passes every Step 5 check and the import exits 0
`DatabaseImport.java:1356-1370` (swallow), `:1295` (consumption tally, correctly BEFORE
apply per CN51 provenance). CN51-as-specified is discharged (§2); the residual is a genuine
silent-partial channel on the migration vehicle: a dump record tripping
`CorruptedRecordException`/bare `DatabaseException` at deserialize/apply is logged, rolled
back, and skipped, with the manifest check structurally blind to it (both sides count dump
membership — correctly). Counterexample gist: v15 dump, one record's body tampered/corrupt in
a way that deserializes into a `DatabaseException` — import exits 0, record absent, manifest
satisfied. Remedy options (orchestrator's choice): rethrow under `exporterVersion >= 15`
(version-gated, legacy-safe); or tally applied-vs-consumed and reject at strictness time; or
at minimum carve the exception out of Step 6's "import completeness = importer exit 0"
operator-doc claim.

### CS65 — suggestion — WI10c duplicate-section check is tag-string-based: `collections` + `clusters` alias pair imports the collections section twice without tripping the tracker
`DatabaseImport.java:301` (raw-tag merge), `:315-318` (both tags dispatch identically),
`:491-505` (required list contains only `collections`). Counterexample: honest v15 dump +
spliced duplicate section under the `clusters` alias → both count 1 → accepted; second
import re-runs `addCollection`/mapping (mostly idempotent, still a tampered shape accepted).
Remedy: normalize aliases before the merge.

### CS66 — suggestion — the best-effort ack gate is not version-gated, widening the declared-legacy path (R1 "byte-for-byte" letter deviation, fail-closed direction)
`DatabaseImport.java:422-427` (`bestEffortDump` checked without an `exporterVersion >= 15`
guard), `:697-701` (marker parsed for any version). A declared-v14 dump hand-carrying
`"best-effort": true` is now rejected where the parent skipped the unknown field. No legacy
exporter writes the field; disposition may be "accept + record", but it should be a recorded
decision, not an accident.

### CS67 — suggestion — v15 structural strictness is section-ORDER-blind; a reordered tampered dump is silently accepted with import-semantics drift
`DatabaseImport.java:490-510` (presence/duplicates only). Counterexample: move `schema`
before `collections` → `removeDefaultCollections` fires (:779-781) and classes are created
without the dump's collection ids (:1009-1016); all checks pass; the target diverges from
the source silently. Combined with CS64, `records` before `schema` could drop every record
silently IF the per-record failures surface as `DatabaseException` (not separately verified
— flagged as the amplifier, not an independent claim). Remedy: pin the exporter's fixed
order (P6) for `>= 15`.

### CS68 — suggestion — decompressed junk between the JSON root's closing brace and the gzip trailer is silently discarded by the CS43 drain
`DatabaseImport.java:519-527` (drain discards), ValidatedGZIPInputStream trailer checks
cover the junk as member content (CRC/ISIZE include it). Counterexample: append `IGNORED`
after `}` inside the member, re-gzip → accepted. No import consequence today; rejecting
non-whitespace post-root bytes during the drain would close the letter gap cheaply.

### CS69 — suggestion — manifest totals are parsed with `readInteger` (int), false-rejecting an honest dump with a section total above `Integer.MAX_VALUE`
`DatabaseImport.java:465-476` vs the exporter's `long` tallies
(`DatabaseExport.java:305-308`, `recordExported` is `long`). >2^31-1 records is a real
long-horizon migration shape; failure mode is a loud `NumberFormatException` (fail-closed,
wrong direction). Remedy: a `readLong` twin.

### CS70 — suggestion — the plain-JSON arm's file stream is never closed, including on the NEW Q-M3/SR2 pre-flight rejection paths
`DatabaseImport.java:624-634` (gzip-only close), `:176-181`/`:213-216` (plain fallback keeps
the raw `FileInputStream` behind the reader). Pre-existing for legacy imports; newly
user-visible on rejection paths (Windows: rejected dump file undeletable until GC). Remedy:
retain and close the buffered stream regardless of arm.

### CS71 — suggestion (pre-existing) — `session.setUser(null)` is never restored on any path
`DatabaseImport.java:277`; `finally` restores only the validation flag (:391). The importing
session loses its user context permanently, success or failure, pre-flight rejection
included. Byte-identical at the parent; recorded because the Step-5 pre-flight purity story
("target byte-for-byte untouched") sits next to a session that is NOT restored.

### CS72 — suggestion (pre-existing) — `INDEX_IGNORE_NULL_VALUES_DEFAULT` global flip is not exception-safe on the index-import path
`DatabaseImport.java:1730-1742`. A `createIndex` throw mid-import leaves the process-wide
default mutated for unrelated databases in the same JVM — outside the SR1 condemn-target
envelope, which covers only the target. Remedy: try/finally around the restore.

### CS73 — suggestion (pre-existing shape) — `detectFraming`'s fallback `reset()` can itself throw (mark limit 1024) on a gzip-magic file with an oversized/corrupt header, masking the real cause and leaking the ctor-opened `FileInputStream`
`DatabaseImport.java:205-218` (+ file ctor :176-181). Loud either way (fail-closed holds);
the defect is diagnosability + FD hygiene on a pathological input. Remedy: widen the mark
limit past the max tolerated header or close the stream on ctor failure.

### CS74 — suggestion — the `brokenRids` presence occurrence is recorded tag-blind at the inline consumption site
`DatabaseImport.java:1594-1596` (unconditional merge for `>= 12`), `:551-556`
(`readNext(BEGIN_COLLECTION)` consumes the intervening tag text without checking it).
Counterexample: v15 dump whose post-records section is `"anythingAtAll": []` → consumed as
brokenRids, presence satisfied, import accepted. A mislabeled (tampered) section passes a
check whose purpose is detecting tampering. Remedy: assert the consumed tag text at the
inline site.

## 7. Null verdicts (checked, no finding)

| Obligation | Verdict | Where traced |
|---|---|---|
| O3 / charter #1 — no target mutation before `runDeferredImportPreamble`; every rejection classified | **holds** — single mutation gate, PF1-PF10 enumerated pre-mutation, SR2 arms provably pre-mutation | §3 |
| O4 / charter #2 — mid-import failure state loud, cause-preserving, within SR1 envelope | **holds** (conditional on Step 6 delivering WI3, incl. the CS64 caveat); CS72 is process-global residue outside the envelope | §4 |
| O2 / charter #5 — CN51 arithmetic symmetric + non-vacuous as specified | **holds** — increment sites 1:1 with dump entries on both sides; consumption-before-apply is the contract-correct side | §2 |
| CS43 sequence wiring (steps 1→2→3, file vs stream scope per WI10a) | **holds** — drain precedes verify; step (3) file-only; decoder framing identical across ctors; sequence-order enforced by the primitive itself | §1.2 S5-S9, P4 |
| SR2 trigger letter (CS46): first non-info tag OR end of stream, whichever first | **holds** — :295-300 + :338-342; the `-1`-literal quirk (S3) converges on the same fail-closed outcome | §1.2 S1-S3 |
| §A3/WI1 blob mapping — no raw-target-id fallback | **holds** — unmapped dump id is warn-and-skip (:820-834), never raw-resolved; FM-M16 closed (test-pinned) | §1.2, test `crossLayoutBlobDumpRegistersBlobsByMappingNotRawId` |
| Declared `<= 14` regression beyond ruled/recorded deviations | **holds** except CS66 (unrecorded fail-closed widening) | §1.3 |
| Preamble block completeness vs WI11 pin (`:214-:223` incl. snapshot + both `removeDefaultCollections` sites) | **holds** — all three preamble members moved together; both drop-call sites structurally post-info | §3 |
| Primary-exception preservation on the import failure path | **holds** — finally is throw-free (`close()` swallows, flag-restore is a field write) | §4 |

## 8. Hypothesis log

| # | Hypothesis | Evidence sought | Outcome |
|---|---|---|---|
| H1 | The strictness gate's version can diverge from the parse-governing version | re-declaration sites, gate consumers, SR2 guard conditions | **confirmed → CS63** (downgrade direction only; upgrade direction fail-closed) |
| H2 | A dump can end mid-token silently on the v15 path | JSONReader EOF semantics (P1) vs decoder-layer detection | refuted for gzip (decoder is loud); plain-stream ctor path tolerates only the root-brace truncation (data-complete) — WI10a-sanctioned |
| H3 | The manifest check can pass while a record failed to land | tally placement + `importRecord` catch set (P5) | **confirmed as a layer-above channel → CS64**; CN51 itself discharged |
| H4 | Duplicate sections can evade the occurrence tracker | alias tags, inline-consumption sites | **confirmed twice → CS65 (alias), CS74 (tag-blind inline site)**; plain duplicates rejected ✓ |
| H5 | Preamble reachable before pre-flight | call-site enumeration of `runDeferredImportPreamble` | refuted — single site :311, double-gated |
| H6 | `removeDefaultCollections` reachable pre-info | its two call sites vs SR2 gate | refuted — both inside section arms |
| H7 | A close-path secondary can mask the import primary | finally-block audit | refuted — `close()` swallows its own IOException |
| H8 | Resource leak on rejection paths | ctor/close pairing per arm | **confirmed → CS70 (plain arm), CS73 (ctor edge)**; gzip arm clean |
| H9 | Session/global state survives failure unrestored | flag/config set-restore pairing | **confirmed → CS71, CS72** (both pre-existing) |
| H10 | Legacy (`<= 14`) behavior regressed beyond rulings | arm-by-arm parent diff walk | refuted except **CS66** (fail-closed widening, unrecorded) |
| H11 | In-member post-root bytes escape all three CS43 steps | drain semantics + trailer coverage | **confirmed → CS68** (accepted-junk letter gap) |
| H12 | Physical-size TOCTOU (ctor `Files.size` vs drain time) | mismatch direction analysis | refuted as a silent-accept vector — any divergence rejects loudly |
| H13 | Manifest totals can overflow the importer's parse | int vs long on the two sides | **confirmed (false-reject direction) → CS69** |
| H14 | `importSchema`'s swallow yields a fully-silent v15 path | shape-by-shape reader-desync walk | refuted — CN51 undercount or unsupported-tag desync catches every shape found; accident-load-bearing, recorded in §2 |

## 9. Verdict

The step's two headline mechanisms are structurally sound where they were aimed: the CS38
pre-flight deferral is genuinely watertight (single mutation gate, provably pre-mutation SR2
arms — the strongest part of the diff), and the CN51/CS43 machinery implements the pinned
contracts faithfully, including the subtle consumption-before-apply provenance side and the
drain-before-verify ordering. The review found **no blocker**, **two should-fix** findings —
CS63, the one input shape that disarms the entire v15 strictness matrix from inside the dump
(the exact self-contradictory shape the charter asked to resolve; bounded in severity by an
equivalent-power sanctioned bypass), and CS64, the pre-existing `DatabaseException` swallow
that Step 5's "exit 0" story newly makes load-bearing — and **nine suggestions** (CS65-CS74,
skipping the two should-fix IDs): letter-level fail-closed gaps (alias duplicates, section
order, post-root junk, tag-blind brokenRids presence), one false-reject boundary (int
manifest totals), an un-recorded lenient-path widening, and pre-existing resource/session/
global-state hygiene items. Nothing found contradicts SR1's condemn-target doctrine or the
recorded WI10a resolution.

## Compact findings block

| ID | severity | location | summary | counterexample gist |
|---|---|---|---|---|
| CS63 | should-fix | DatabaseImport.java:295-311,347,693 | last-write-wins `exporterVersion` + gate-at-loop-end lets a trailing duplicate `info` (or repeated field) re-declaring `<= 14` skip `verifyV15StructuralStrictness` entirely — duplicate/manifest/gzip checks all disarmed after the strict-armed parse ran | append `,"info":{"exporter-version":14}` before the root `}` of a v15 dump, re-gzip → imports silently, manifest+drain never verified |
| CS64 | should-fix (pre-existing) | DatabaseImport.java:1356-1370,1295 | `importRecord` swallows `DatabaseException` → consumed-but-not-landed record passes CN51 (which correctly counts consumption) and import exits 0 — silent partial result on the migration vehicle; CN51-as-specified discharged, channel sits one layer up | v15 dump with one record body tampered to raise `CorruptedRecordException` at apply → exit 0, record absent, manifest satisfied |
| CS65 | suggestion | DatabaseImport.java:301,315-318,491-505 | WI10c tracker counts raw tag strings — `collections`+`clusters` alias pair double-imports the section without tripping the duplicate check | splice a duplicate collections section under the `clusters` tag into a v15 dump → accepted |
| CS66 | suggestion | DatabaseImport.java:422-427,697-701 | best-effort ack gate not version-gated — declared-v14 dump with a hand-added marker now rejected (fail-closed widening; R1 byte-for-byte letter deviation, unrecorded) | v14 dump + `"best-effort":true` → rejected where parent imported |
| CS67 | suggestion | DatabaseImport.java:490-510 | v15 strictness is section-order-blind; reordered tampered dump accepted with import-semantics drift (id-less class creation, `removeDefaultCollections` firing) | swap `schema` before `collections` in a v15 dump → accepted, target diverges from source |
| CS68 | suggestion | DatabaseImport.java:519-527 | CS43 drain silently discards decompressed junk between the JSON root `}` and the gzip trailer (CRC/ISIZE cover it as member content) | insert `IGNORED` after root `}` inside the member, re-gzip → accepted |
| CS69 | suggestion | DatabaseImport.java:465-476 vs DatabaseExport.java:305-308 | manifest totals parsed as `int` while exporter tallies `long` — honest >2^31-1-record dump false-rejects via `NumberFormatException` | manifest `"records": 3000000000` → loud false rejection |
| CS70 | suggestion | DatabaseImport.java:624-634,176-181 | plain-JSON arm's `FileInputStream` never closed — incl. the NEW Q-M3/SR2 rejection paths (file pinned until GC) | plain v15 dump rejected pre-flight on Windows → file undeletable until GC |
| CS71 | suggestion (pre-existing) | DatabaseImport.java:277,391 | `session.setUser(null)` restored on no path (success included); only the validation flag is restored | any import → caller's session permanently user-less |
| CS72 | suggestion (pre-existing) | DatabaseImport.java:1730-1742 | `INDEX_IGNORE_NULL_VALUES_DEFAULT` global flip not exception-safe — a `createIndex` throw poisons the process-wide default beyond the condemned target | index-create failure mid-import → other DBs in the JVM inherit the dump's ignore-null setting |
| CS73 | suggestion (pre-existing shape) | DatabaseImport.java:205-218,176-181 | `detectFraming` fallback `reset()` throws past mark limit 1024 on an oversized/corrupt gzip header — real cause masked, ctor-opened FD leaked (still loud) | gzip magic + 2KB FNAME then garbage → "Resetting to invalid mark" instead of the header defect |
| CS74 | suggestion | DatabaseImport.java:1594-1596,551-556 | brokenRids presence recorded tag-blind at the inline consumption site — any array after `records` satisfies the presence check | rename the section to `"xRids": []` → consumed as brokenRids, accepted |

**Null-verdict notes per charter criterion:** #1 (SR1 boundary) — holds, no finding (§3);
#2 (mid-import failure state) — acceptable per SR1 + Step-6 doc obligation, no finding beyond
the pre-existing CS72 residue (§4); #3 (v15 fail-closed) — CS63 + letter gaps CS65/CS67/
CS68/CS74, legacy-regression check otherwise clean except CS66 (§1); #4 (resource lifecycle)
— gzip arm clean/improved, findings CS70/CS71/CS72/CS73 all pre-existing except CS70's new
rejection-path visibility (§5); #5 (CN51) — arithmetic symmetric and non-vacuous as
specified, null on the implementation, CS64 recorded as the layer-above channel (§2).
