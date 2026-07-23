# Gate Verification — Pass-1 Completeness/Consistency Amendments (WI/WC), Iteration 1

**Artifact under verification:** `docs/adr/transactional-schema/_workflow/plan/track-8-design-drafts.md`
as amended by commit `0a7fc377fa` ("Amend Track 8 design per adversarial pass-1 triage"),
branch `transactional-schema`, HEAD `0a7fc377fa` confirmed.
**Charter:** GATE, not re-review — per-finding verdicts for the completeness/consistency pass-1
findings (WI1–WI10, WC1–WC5, per the user-approved triage shape), plus a coherence sweep of the
amended document. Read in full: the amended design, `adversarial-completeness-pass1.md`,
`git show 0a7fc377fa`. Code claims introduced or relied on by the amendments were spot-checked at
HEAD (production sources are identical between the grounding SHA `d664589d7f` and HEAD —
`git diff d664589d7f..0a7fc377fa` touches only the four `_workflow` docs).

---

## 1. Decision criteria (stated before verdicts)

A finding is **VERIFIED** iff the amendment (a) exists in the amended document at the location the
disposition table claims, (b) matches the user-approved triage shape for that finding (adopted /
resolved-by-ruling / recorded / deferred), (c) is concrete enough that decomposition does not
re-open the gap the finding named, and (d) any code claim the amendment makes is true at HEAD
(file:line spot-check). **STILL OPEN** = the amendment exists but leaves the named gap
improvisable. **REJECTED** = the amendment contradicts the approved triage or the finding's
substance. **MOOT** = overtaken by another amendment/ruling. Residual defects *introduced by the
amendments* are filed as new findings (WI11+/WC6+) and do not, by themselves, flip a verdict when
the approved remedy shape is honored. Alternative hypotheses are logged (§5).

---

## 2. Per-finding verdicts

### WI1 (blocker) — blob-collection import mapping under R3 — **VERIFIED**

- **Amendment concreteness.** Amendments §A3 names the mechanism ("the blob registration routes
  through `collectionToCollectionMapping` (or `$blob*` name-match — decomposition picks; both
  eliminate the cross-layout misclassification)"), the owner ("Draft M owns the fix (it lands in
  `DatabaseImport.java`, already in M.6 — the two-unit packing premise holds)"), and the
  supersession ("Supersedes: §0 R3's and FM-G6's unqualified name-dynamic claim (both corrected
  in place)").
- **`[verified]` overclaim corrected in place.** §0 R3 now carries: "**[Corrected 2026-07-23,
  pass-1 WI1: one production exception exists — `DatabaseImport.importSchema`'s blob-collection
  mapping resolves raw dump ids in the target id space (`DatabaseImport.java:528-541`); Draft M
  owns the fix, see Amendments §A3.]**". FM-G6 mirrors it: "production lookups dynamic **except**
  the importer's blob-id mapping … the fixture sweep cannot catch that production path".
- **FM row added.** FM-M16: "defect at HEAD under R3 — closed by routing through
  `collectionToCollectionMapping` / `$blob*` name-match (Amendments §A3, pass-1 WI1); pinned by
  M.5 #13".
- **Required pin exists.** M.5 #13: "*Cross-layout blob import (pass-1 WI1)* — a v14-layout dump
  WITH blob content (blobs at the highest source ids) imported into an R3-renumbered target: blob
  records classified as blobs; no class collection registered as a blob collection." Exactly the
  demanded pin (v14 layout, WITH blob content, R3-renumbered target, misclassification asserted
  absent).
- **Code claim re-verified at HEAD.** `DatabaseImport.java:528-541`:
  `var collection = Integer.parseInt(i.trim()); if (!ArrayUtils.contains(session.getBlobCollectionIds(), collection)) { var name = session.getCollectionNameById(collection); session.addBlobCollection(name); }`
  — raw dump id resolved in the target id space; `collectionToCollectionMapping` is populated only
  in `importCollections` (`:915`, name-keyed: `getCollectionIdByName`/`addCollection` at
  `:910-912`) and never consulted here. Both proposed mechanisms are viable at the design level
  (the dump's `collections` section precedes `schema` — `exportDatabase:136-139` writes info →
  collections → schema — so the mapping is populated when `importSchema` parses
  `blob-collections`; the `collectionsImported == false` edge is decomposition's to pin).

### WI2 (should-fix) — absent/malformed exporter-version dispatch — **VERIFIED** (resolved via SR2)

- **New ruling recorded.** Rulings §"Supplementary rulings — adversarial pass-1 triage
  (2026-07-23)": "**SR2 (resolves CS42 + WI2) — a dump with NO declared exporter-version is
  REJECTED fail-closed.** A dump that reaches its section loop (or end of stream) without having
  declared a *parseable* `exporter-version` does not ride the lenient path — it is rejected."
  "Parseable" covers both the absent (E-abs) and malformed (E-mal) cells of the review's matrix,
  and the closing sentence names exactly the circularity the finding identified ("arming
  previously required the very field the missing section carries").
- **Echoed consistently.** §0 R1 refinement note ("SR2 rejects an UNDECLARED exporter-version
  fail-closed"); M.1 Out-clause ("an UNDECLARED exporter-version is no longer lenient — rejected
  fail-closed per SR2"); M2.b intro dispatch ("an undeclared or malformed `exporter-version` is
  rejected fail-closed (SR2)"); pin M.5 #14 ("an undeclared `exporter-version` → reject (SR2)").
- Residual: pin #14 names only the *undeclared* cell, not the *malformed* one — filed as part of
  WI12 below (does not reopen WI2: the ruling and the dispatch narrative both define the cell).

### WI3 (should-fix) — operator migration-procedure document — **VERIFIED** (recorded as owed item, per approved shape)

- **Footprint row.** M.6: "| `docs/` operator migration-procedure page | **(added, pass-1 WI3)**
  export-exit-status gate, fresh out-of-service target, discard-on-any-failure (folds CS44 +
  carries the SR1 doctrine) |".
- **Validation item.** Pin M.5 #18: "the migration-procedure page exists under `docs/` and
  mandates: export-exit-status gate before import; import into a fresh, out-of-service target;
  ANY failure (including post-mutation structural rejections) condemns the target … import
  completeness = importer exit 0."
- **Doc deliberately not yet written — confirmed.** Commit `0a7fc377fa` touches only the design
  doc; `docs/` still contains no migration page. That is the approved shape (owed implementation
  deliverable, not a design-phase deliverable). The doc is additionally load-bearing for SR1
  (condemn-target doctrine) and M.4's I-migration-failfast ("the WI3 operator document pins the
  exit-status gate") — the ownership question the finding raised ("who owns it") is answered:
  Draft M's footprint owns it.

### WI4 (should-fix) — Q-M2 ruled matrix has no pins — **VERIFIED** (cell coverage checked)

Cell-by-cell against the Q-M2 ruling text plus SR1/SR2:

| Ruled cell | Pin |
|---|---|
| Dispatch `<= 14` → lenient unchanged | M.5 #14 ("a declared v14 → lenient path unchanged") + #6/#11 |
| Dispatch `== 15` → strict | M.5 #3-#9 (framing/manifest/ack/dangling — pre-existing pins) |
| Dispatch `>= 16` → reject-with-redirect naming both versions | M.5 #14 |
| Undeclared exporter-version → reject (SR2) | M.5 #14 |
| `schema-version` missing / malformed / out of range → reject, naming declared vs supported | M.5 #14 |
| Mandatory fields enforced | M.5 #15 |
| Unknown extra fields tolerated + logged | M.5 #15 |
| "All rejections throw BEFORE any mutation" (as scoped by SR1 to pre-flight) | M.5 #15 ("a pre-flight rejection leaves the target database unmutated … pins CS38 + SR1's scope boundary") |
| SR1 structural-rejection condemnation scope | M.5 #16 |
| Q-G2 single merged data tx (the finding's Draft-G half) | G.5 #10 ("phase 2 commits exactly once … commit-count instrumentation or the tx listener") |

Two micro-cells remain unpinned — *malformed* exporter-version (named in the amended M2.b
dispatch) and Q-M2 item (3)'s "other known info fields are type-checked if present" — filed as
WI12 (suggestion); they do not reopen WI4, whose substance (the ruled matrix pinned with the same
FM→pin discipline as the rest of the draft) is met.

### WI5 (should-fix) — R3 de-risk sweep not executable — **VERIFIED** (judged executable)

Pin G.5 #8 as rewritten supplies everything the finding demanded:
- **(a) Ordering** — resolves the original self-contradiction: "(1) static sweep before any code
  change … (2) land the R3 `doCreate` blob loop alone; (3) run the named suites against the
  renumbered layout … (4) only then land the two-phase restructure." ("Before the restructure
  lands" in §0 R3 now unambiguously means the *two-phase restructure*, with the storage embed
  landing first — exactly the storage-embed → sweep → restructure order the review asked to pick.)
- **(b) Named suites** — `DbImportExportTest`, `DbImportStreamExportTest`,
  `DatabaseExportImportRoundTripTest`, `StorageBackup*` / `LocalPaginatedStorageRestore*` ITs,
  RID-literal tests in `tests/`. **All verified present at HEAD**:
  `tests/src/test/java/com/jetbrains/youtrackdb/junit/DbImportExportTest.java`,
  `…/DbImportStreamExportTest.java`,
  `core/src/test/java/…/db/tool/DatabaseExportImportRoundTripTest.java`,
  `core/src/test/java/…/paginated/StorageBackup{Test,MTIT,MTRestoreIT,…}.java`,
  `…/LocalPaginatedStorageRestore{FromWALIT,Tx,…}.java`.
- **(c) Static grep** — "grep tests/fixtures/stored dumps for `#\d+:\d+` RID literals and
  hard-coded collection ids".
- **(d) Fixture-regeneration rule** — "regenerable fixtures are re-exported with new binaries;
  stored dumps stay valid by construction (import maps collections by name; the blob row is
  closed by §A3)" — the by-name claim verified at `DatabaseImport.java:910-916`.
- The finding's own caveat ("the sweep cannot catch WI1") is carried verbatim into FM-G6.

Executability verdict: an implementer can execute steps (1)-(4) without guessing.

### WI6 (suggestion) — manifest tag version-gating — **VERIFIED** (adopted)

M2.b-3: "**Version gating (pass-1 WI6):** the new `manifest` tag case in the shared section loop
arms only for `exporterVersion >= 15`; below, the tag remains unsupported and throws as at HEAD —
preserving the `< 15` path byte-for-byte." (The `>= 15` guard is outcome-equivalent to `== 15`
here: a `>= 16` dump short-circuits at dispatch and never reaches the section loop — internally
consistent.) Disposition line present.

### WI7 (suggestion) — FM-M4 had no pin — **VERIFIED** (adopted)

Pin M.5 #17: "the promote path calls the fsync-capable move (file force + rename + parent-dir
fsync); an unflagged `close()` never renames" — exactly the cheap pin the finding proposed. FM-M4
row updated to reference the amended recipe. FM→pin discipline is now complete: every FM-G/FM-M
row maps to a pin or a structural argument.

### WI8 (suggestion) — footprints/manifest shape/Move-2 triad — **VERIFIED** (all four sub-items)

- (a) M.6 gains "| `core/.../api/config/GlobalConfiguration.java` | **(added, pass-1 WI8a)** the
  Q-M1 spill-threshold knob |".
- (b) M.6 gains the `DatabaseImpExpAbstract.java` row ("(noted, pass-1 WI8b) … options-only, so
  the two-unit packing premise survives") — file exists at
  `core/src/main/java/…/db/tool/DatabaseImpExpAbstract.java`.
- (c) Manifest JSON shape pinned in M2.a-5: "section tag `"manifest"`, fields `classes`,
  `indexes`, `records`, `brokenRids` — total counts (per-collection granularity declined for
  v1)".
- (d) The Move-2 ADDED/MODIFIED/REMOVED triad obligation (R3 scope expansion: `AbstractStorage`,
  `SharedContext`; M2.a-7) is recorded in the disposition line and re-stated in the Triage
  verdict ("carrying … the WI8d Move-2 triad obligations").

### WI9 (suggestion) — SharedContext.lock argument — **VERIFIED** (adopted)

G2.c gains the "**Lock interaction (pass-1 WI9)**" paragraph: four-lock order nests inside
`SharedContext.lock`; safe by construction (factory monitor spans the create, no other session
exists); the import-nested call site differs "precisely in NOT holding `SharedContext.lock`".
This is the one-sentence-plus argument the finding asked to be stated, including the import-site
contrast.

### WI10 (suggestion) — residual matrix cells — **VERIFIED** (a carried, b/c adopted)

- (a) M2.b-2 closes with "exact stream-variant scope carried as an explicit decomposition
  obligation (pass-1 WI10a)"; the Triage verdict repeats the hand-off.
- (b) M2.b-3 rejects "non-empty `brokenRids` without the best-effort marker (an honest
  default-mode v15 export aborts before producing brokenRids, so the combination proves
  tampering/inconsistency — pass-1 WI10b)".
- (c) M2.b-3 rejects "a duplicated section (the presence tracker counts occurrences — pass-1
  WI10c)".

### WC1 (should-fix) — frozen EARS bullet-2 supersession unrecorded — **VERIFIED**

The Amendments section carries a dedicated "**Frozen-plan supersession record (pass-1 WC1)**"
naming both superseded clauses **verbatim**: "a complete legacy dump missing any expected section
is refused" and "a legacy dump requires the explicit unverified-import acknowledgment flag" —
checked against `track-8.md:110-111`, which reads "…a complete legacy dump missing any expected
section is refused; a legacy dump requires the explicit unverified-import acknowledgment flag
(I-migration-fail-closed)" — exact match. The record states the resolution direction (R1
postdates and supersedes), the consequence ("declared-legacy (`<= 14`) dumps keep today's lenient
behavior"), and the carry instruction the finding demanded: "Move 2/3 MUST carry this
supersession into track-8.md before the EARS lines are used as test method names." Note the
careful "declared-legacy" phrasing keeps the record consistent with SR2 (a dump missing its
*info* section declares nothing and is rejected — no contradiction with "a missing section is
tolerated" for declared-legacy dumps).

### WC2 (should-fix) — pin G.5 #2 unsatisfiable — **VERIFIED**

Pin #2 rewritten in two arms: "(a) unit-level: the root persisted by `SchemaShared.create` parses
as the bootstrap-valid empty-schema shape (version 6, empty classes, counter 0) *before phase 1
runs*; (b) DB-level: create → close → reopen loads the genesis-POPULATED schema without the
FM-G1 error branch (a completed create can never show an empty schema)." This is precisely the
restatement the finding proposed (harness-level assertion of the G2.a shape + a satisfiable
DB-level reopen assertion); the parenthetical even records why the original pin was
unsatisfiable. Consistent with pins #7-#8 (populated blob registry) and with §A1 (a completed
create carries the completion marker, so the reopen in arm (b) is legal).

### WC3 (suggestion) — dispatch phrasing unharmonized — **VERIFIED**

§0 R1 gains the refinement note ("the strict matrix = v15 exactly, `>= 16` short-circuits to
reject-with-redirect — and SR2 rejects an UNDECLARED exporter-version fail-closed"); the M2.b
intro is rewritten as the four-arm harmonized dispatch. Residual cosmetic echo (FM-M12 still says
"R1's `>= 15` keying") is outcome-invariant and folded into WC6 below.

### WC4 (suggestion) — design.md:504-506 "writes the first schema record" — **VERIFIED**

G2.a gains "**Design-final hand-off (pass-1 WC4)**": the clause is "superseded in letter — the
bootstrap root pre-exists the genesis tx … Reconciled in the Phase-4 `design-final.md`, not
here." Exactly the explicit hand-off the finding asked for.

### WC5 (suggestion) — cross-reference hygiene — **VERIFIED** (a recorded, b/c adopted)

- (a) Disposition line records the stale Tracks-5-7 checklist and assigns "plan-file hygiene at
  Move 2 (this commit touches only this file)" — consistent with the actual commit contents
  (only the design doc modified).
- (b) §0 now spells the path: "`_workflow/track-7-design-drafts.md` §0 — one directory above this
  file, not a `plan/` sibling; pass-1 WC5b" — verified: the file exists at
  `docs/adr/transactional-schema/_workflow/track-7-design-drafts.md`, one directory above the
  design doc's `plan/` location.
- (c) M.4 now reads "no export CLI entry point exists in-repo — the `console` module ships
  packaging/bin scripts but no Java sources (pass-1 WC5c)".

---

## 3. Coherence sweep of the amended document

1. **Disposition table completeness — PASS.** The table carries **32** finding IDs: CS34–CS44
   (11, matching the durability report's index), CN48–CN53 (6, matching the concurrency report),
   WI1–WI10 (10), WC1–WC5 (5), plus one explicitly un-ID'd "CN observations" row. Every ID
   present in the three reports has a row; no orphan dispositions (no table ID absent from the
   reports). *Note for the orchestrator: the gate tasking said "31 IDs"; the actual count is 32.*
   The header's summary ("three blocker-remedy clusters … two supplementary rulings … every
   should-fix adopted, every suggestion dispositioned") is arithmetically consistent with the
   table (blockers CS34/CS38/WI1 → §A1/§A2/§A3; should-fixes all ADOPTED/RESOLVED/FOLDED;
   suggestions all ADOPTED/RECORDED/DEFERRED/carried).
2. **Supersessions name the superseded sentences — PASS.** §A1 names FM-G3's original row (both
   quoted claims), FM-G5's "DB is a discarded half-create anyway", pin G.5 #9's original wording,
   and G.6's original list/count; M2.a-5 names "flush + close → fsync the dump file → rename"
   (CS40); M2.b-2 names "the bare arithmetic sentence" (CS43); §A2 names "the draft's silent
   retention of the HEAD import order"; §A3 names the §0 R3/FM-G6 unqualified claim; the WC1
   record names both frozen clauses verbatim.
3. **SR1/SR2 recorded and echoed consistently — PASS with one annotation nit.** SR1 and SR2 sit
   in the Rulings section under "Supplementary rulings", explicitly framed as scoping/extending
   without re-opening. SR1 is echoed in the M2.b pre-flight paragraph, M2.b-3's contract scope,
   M.4's isolation/failfast clauses, pins #15/#16/#18, and the invariant cross-reference
   ("structural-rejection scope per SR1"). SR2 is echoed in §0 R1's note, M.1's Out clause, the
   M2.b dispatch, and pin #14. The one wrinkle: the Q-M2 ruling bullet itself still carries the
   unscoped sentence "All rejections throw BEFORE any mutation of the target database" with no
   inline pointer to SR1 — unlike §0 R1/R3 and Q-G1, which received inline
   refinement/correction notes. Filed as WC6 (suggestion).
4. **Pins vs narrative — PASS.** Pin #14/#15/#16 ↔ Q-M2+SR1/SR2+CS38; pin #17 ↔
   CS40/CS41/CN52/CN51/FM-M15/FM-M17; pin #13 ↔ §A3/FM-M16; pin #18 ↔ WI3/CS44/SR1; G.5 #2 ↔
   G2.a+§A1; G.5 #8 ↔ R3/WI5/§A3; G.5 #9 ↔ §A1 W-states; G.5 #10 ↔ Q-G2. No pin asserts a state
   the amended narrative forbids (the WC2 class of defect does not recur).
5. **§0 ↔ drafts ↔ amendments contradictions — NONE MATERIAL.** Checked pairs: R1 note vs M2.b
   dispatch (consistent); R3 correction vs FM-G6/§A3 (consistent); M.1 "byte-for-byte" vs SR2
   carve-out (explicitly reconciled in the same sentence) and vs the CS38 `< 15` deferral
   (design argues behavior-preserving; the code between `importDatabase:214` and the section
   loop indeed does not consume the dropped classes — verified: `:215` IM reload, `:217-221`
   auto-index enumeration, `:223` snapshot capture; but see WI11); FM-M7 (v15 missing-section)
   vs WC1 record (declared-legacy tolerated) vs SR2 (undeclared rejected) — the three-way split
   is coherent because the WC1 record says "declared-legacy". Residual cosmetic echoes (FM-M12's
   "`>= 15` keying") folded into WC6.
6. **Amendment code claims spot-checked at HEAD — ALL TRUE.**
   `DatabaseImport.java:111` (`exporterVersion = -1`), `:414` (assigned only from the info
   field), `:136-143` (plain-JSON fallback), `:214/:216-222/:230` (preamble order),
   `importSchema:495-497` / `importCollections:847-849` (`removeDefaultCollections` call sites),
   `:528-541` (raw blob-id mapping), `:910-916` (name-keyed collection mapping);
   `DatabaseExport.java:85` (`prepareForFileCreationOrReplacement` upfront final-name replace),
   `:136` (info written first); `ContextConfiguration.java:90-96` (process-global mutable
   fallback in `getValue`); `MetadataDefault.java:137-145` (fresh `makeSnapshot()` when
   unpinned); `YouTrackDBInternalEmbedded.java:744-755` (`storages.put` before `internalCreate`;
   catch wraps and rethrows), `:757`/`:770` (double `callOnCreateListeners`), `:760-766`
   (`failIfExists=false` silent no-op); `SecurityShared.setupPredicateSecurity:1078-1101`
   (creates OSecurityPolicy on open); `AbstractStorage:1523` (`clearStorageDirty`, design says
   `:1524` — ±1), `:8088-8100` (`setSchemaRecordId` in its own atomic op);
   `FunctionLibraryImpl.createFunction:137-139`/`init:164`;
   `SequenceLibraryImpl.createSequence:103`→`init:110`;
   `CollectionBasedStorageConfiguration.setProperty:1131` exists, so §A1's "storage-config
   property" marker needs no new API surface (footprint plausible as listed). All named G.5 #8
   suites exist (§2/WI5).
7. **Footprint arithmetic — PASS.** G.6 lists 8 firm + 1 conditional production files ("~8-10");
   M.6 lists 5 firm + 1 possible ("~5-6") + the docs page; the disjointness claim holds (no file
   appears in both tables).

---

## 4. New findings (defects introduced or left by the amendments)

### WI11 — suggestion — §A2 / M2.b pre-flight paragraph vs `importDatabase:223`
**The deferral enumeration omits the order-coupled `beforeImportSchemaSnapshot` capture.**
§A2 defers "`removeDefaultNonSecurityClasses` (`importDatabase:214`), the auto-index snapshot
(`:216-222`), and `removeDefaultCollections` (via `importSchema:497` / `importCollections:848`)"
— it names the (non-mutating) auto-index snapshot precisely because it must move with the
mutation it observes, but omits the equally order-coupled
`var beforeImportSchemaSnapshot = session.getMetadata().getImmutableSchemaSnapshot()` at `:223`.
At HEAD that snapshot is captured *after* `removeDefaultNonSecurityClasses` and consumed by
`importRecords`'s system-record classification (`isSystemRecord:1154-1156`,
`findRelatedSystemRecord:1115-1117` via `getClassByCollectionId`). An implementer who defers only
the three enumerated items and leaves `:223` in place hands `importRecords` a snapshot still
containing the to-be-dropped default classes — a semantic change on both the `< 15` and v15
paths. Low risk (lines `:214-:223` are contiguous and would naturally move as one block), but the
decomposition should pin the deferred block boundary as `:214-:223` inclusive.

### WI12 — suggestion — pin M.5 #14/#15 vs the amended M2.b dispatch and Q-M2 item (3)
**Two ruled micro-cells have no pin.** (a) The amended dispatch rejects "an undeclared **or
malformed** `exporter-version`" (SR2's "parseable"); pin #14 names only "an undeclared
`exporter-version`" — a non-integer `exporter-version` value has no pinned expectation (at HEAD
it dies on an accidental `readInteger` parse error; the design upgrades this to a deliberate
fail-closed rejection, which deserves its own fixture). (b) Q-M2 item (3)'s "other known info
fields are type-checked if present but not required" has no pin in #14/#15 (only mandatory-field
enforcement and unknown-field tolerance are pinned). One clause extending pin #14 ("a malformed
`exporter-version` value → reject (SR2)") and one extending #15 ("a known optional info field
with a wrong type → reject") closes both.

### WC6 — suggestion — annotation-discipline drift in the amended rulings/FM text
(a) The Q-M2 ruling bullet retains the unscoped sentence "All rejections throw BEFORE any
mutation of the target database" with no inline SR1 pointer, although the document's own
convention (applied to §0 R1, §0 R3, and Rulings §Q-G1) is to annotate a refined/scoped ruling in
place; a reader landing on Q-M2 via a section link takes the unscoped guarantee at face value.
(b) FM-M12 still says "prevented structurally by R1's `>= 15` keying" — the pre-WC3-harmonization
phrasing (the lenient boundary is unchanged, so this is outcome-invariant, but the harmonized
formula is "declared `<= 14` lenient"). Both are one-line in-place annotations; neither changes
any outcome.

---

## 5. Alternative-hypothesis log

- **H1 (vs WI1 = VERIFIED):** "The §A3 remedy is under-specified because it leaves the mechanism
  choice (mapping vs name-match) to decomposition." Rejected as a verdict-flipper: the finding
  demanded the *outcome* be defined and ownership assigned; both candidate mechanisms are named,
  both provably eliminate the misclassification, and the pin (M.5 #13) is mechanism-agnostic.
  This matches the review's own "Required" clause ("e.g., route … or match by `$blob*` name").
- **H2 (vs WI3 = VERIFIED):** "An owed-doc footprint row without content is a hollow amendment."
  Rejected: the approved triage shape is exactly deliverable-recorded-not-written; pin #18 makes
  the doc's *content obligations* (exit-status gate, fresh target, condemn-on-failure)
  design-level commitments, so decomposition cannot drop them silently.
- **H3 (vs WI4 = VERIFIED, not STILL OPEN):** "Unpinned micro-cells (WI12) mean the matrix is
  still not covered." Considered; rejected: WI4's substance was that the *entire* ruled matrix had
  zero pins and the pin lists predated the rulings. Every ruling clause now has a pin except two
  sub-clauses whose narrative outcome is defined (dispatch text, Q-M2(3)); that is pin-list
  precision, not an undefined cell — an implementer no longer improvises an outcome.
- **H4 (vs WI11):** "The auto-index snapshot citation `:216-222` was meant to include `:223`."
  Rejected by line arithmetic: `:215` is the IM reload, `:217-221` the index loop, `:223` the
  schema snapshot; the cited window excludes it. Second alternative — "leaving `:223` in place is
  harmless" — not provable: `isSystemRecord`/`findRelatedSystemRecord` consult the snapshot by
  collection id, and collection-id reuse after the deferred drop can change classification;
  hence filed (at suggestion, given the natural block-move implementation).
- **H5 (vs coherence-PASS on SR1):** "Q-M2's unannotated absolute sentence is a live
  contradiction with SR1." Rejected as a contradiction (filed as WC6 hygiene instead): the
  supplementary-rulings preamble states explicitly that SR1/SR2 "scope/extend the rulings above",
  and every *operative* restatement of the guarantee (M2.b intro, M.4, pins #15/#16) carries the
  SR1 scope. The unannotated text is a navigation hazard, not an unresolved conflict.

---

## 6. Compact verdict index

| ID | Verdict | Evidence gist |
|---|---|---|
| WI1 | VERIFIED | §A3 names mechanism (mapping / `$blob*` name-match) + owner (Draft M, M.6); §0 R3/FM-G6 corrected in place; FM-M16 added; pin M.5 #13 = v14-layout dump WITH blob content → R3 target; code claim re-verified at `DatabaseImport.java:528-541` |
| WI2 | VERIFIED | SR2 recorded in Rulings ("no parseable exporter-version → rejected"); echoed in §0 R1 note, M.1, M2.b dispatch, pin #14 |
| WI3 | VERIFIED | M.6 docs-page row + pin M.5 #18 (content obligations); doc deliberately unwritten — approved owed-item shape; commit adds no docs page |
| WI4 | VERIFIED | Pins #14/#15/#16 + G.5 #10 cover every Q-M2 clause + SR1/SR2 (cell table in §2); two micro-cells → WI12 |
| WI5 | VERIFIED | Pin G.5 #8: 4-step ordering (embed → sweep → restructure), grep `#\d+:\d+`, fixture rule; all five named suites exist at HEAD |
| WI6 | VERIFIED | M2.b-3 version-gates the `manifest` tag (`>= 15`); below throws as at HEAD |
| WI7 | VERIFIED | Pin M.5 #17: fsync-move on promote; unflagged close never renames; FM→pin map now total |
| WI8 | VERIFIED | (a) GlobalConfiguration in M.6; (b) DatabaseImpExpAbstract noted (file exists); (c) manifest shape pinned in M2.a-5; (d) Move-2 triad obligation recorded |
| WI9 | VERIFIED | G2.c lock-interaction paragraph incl. import-site contrast |
| WI10 | VERIFIED | (a) decomposition obligation in M2.b-2; (b) brokenRids-w/o-marker and (c) duplicate sections rejected in M2.b-3 |
| WC1 | VERIFIED | Frozen-plan supersession record quotes both clauses verbatim (matches track-8.md:110-111); Move 2/3 carry instruction |
| WC2 | VERIFIED | Pin G.5 #2 split: unit-level pre-phase-1 shape + DB-level genesis-POPULATED reopen |
| WC3 | VERIFIED | §0 R1 refinement note + harmonized four-arm M2.b dispatch |
| WC4 | VERIFIED | G2.a design-final hand-off paragraph (Phase-4 reconciliation) |
| WC5 | VERIFIED | (a) plan-file hygiene recorded for Move 2; (b) track-7 path spelled (file verified at `_workflow/`); (c) console wording precise in M.4 |
| **WI11 (new)** | suggestion | §A2 deferral enumeration omits order-coupled `beforeImportSchemaSnapshot` (`importDatabase:223`, consumed by `isSystemRecord:1154`); pin block boundary `:214-:223` at decomposition |
| **WI12 (new)** | suggestion | Unpinned micro-cells: malformed `exporter-version` value (SR2 "parseable") absent from pin #14; Q-M2(3) known-optional-field type-check absent from pin #15 |
| **WC6 (new)** | suggestion | Q-M2 bullet lacks inline SR1 scope note (unlike R1/R3/Q-G1 annotation convention); FM-M12 retains pre-harmonization "`>= 15` keying" phrasing |

**Gate outcome:** all 15 tasked findings VERIFIED; zero REJECTED/STILL OPEN/MOOT; coherence sweep
clean apart from three new suggestion-severity findings (WI11, WI12, WC6) — none blocks
decomposition. Note for the orchestrator: the disposition table carries 32 finding IDs, not the
31 stated in the gate tasking (CS34–CS44 = 11, CN48–CN53 = 6, WI1–WI10 = 10, WC1–WC5 = 5).
