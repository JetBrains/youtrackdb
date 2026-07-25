# Track 8 Step 6 — Documentation Review, Iteration 1

Reviewer: docs-review worker thread (read-only).
Subject: commit `612340c91d` ("Validate dump info fields and add migration runbook"),
branch `transactional-schema`, HEAD `f2cebaa9c3`.
In-scope prose: `docs/operator-migration-procedure.md`, `docs/README.md` (index entry),
`docs/adr/transactional-schema/_workflow/plan/track-8-design-drafts.md` (SR3 ruling record).

Perspectives / ID prefixes (starting at 60 to avoid collision with design-draft WI1..WI11
work-item IDs): WC = internal consistency & cross-references, WI = instruction
completeness, WS = writing style.

Status: COMPLETE.

---

## Decision criteria

A finding is filed when (numbered premises precede each verdict):

- **C1** — a behavioral statement in the runbook does not match the code at HEAD
  (`DatabaseImport.java` / `DatabaseExport.java` / related), severity scaled by how the
  mismatch would steer an operator during a live migration or incident;
- **C2** — two statements inside the reviewed prose set (runbook, README entry, SR3
  record, inline code comment) cannot both be true, or a runbook statement contradicts a
  recorded ruling (SR1/SR2/SR3, R1, Q-M2/Q-M3);
- **C3** — a naive operator walking a runbook path must guess a command, a flag, an
  observable, or a next action (instruction incompleteness);
- **C4** — the page deviates from the established `docs/` house conventions in a way that
  hurts scannability or trust (style).

Severity: **blocker** = the operator cannot execute the procedure or is steered into a
harmful action; **should-fix** = the operator is materially misled or left guessing on a
recovery path, but a careful reader recovers; **suggestion** = polish.

## Hypothesis log

| # | Hypothesis | Method | Verdict |
|---|---|---|---|
| H1 | `EXPORT DATABASE` / `IMPORT DATABASE` are real, typeable commands | grep grammar (`core/src/main/grammar/YouTrackDBSql.jjt`), all 222 parser classes, console module (main class = `org.apache.tinkerpop.gremlin.console.Console`), server, public API (`api/YouTrackDB.java`, `api/YourTracks.java`), `docs/yql/YQL-Commands.md` | REFUTED — no such command exists anywhere; only non-test caller of the tools is `jmh-ldbc/.../LdbcDatabaseTool.java` → **WI60** |
| H2 | "exit status 0" maps to a real process exit code | grep `main(`/`System.exit` in `core/.../db/tool/*` | REFUTED — no CLI entry point, no exit code; tools signal success by returning without throwing → **WI61** |
| H3 | Step 5's silent-arm #1 scoping "on a legacy-declared dump" is wrong (arm reachable on v15) | code + hierarchy check: swallow catches only `DatabaseException` (DatabaseImport.java:1541–1556); `SerializationException`/`RecordNotFoundException` extend `CoreException`, NOT `DatabaseException`; the `DatabaseException`-capable getters in the try are `exporterVersion <= 13`-gated (DatabaseImport.java:1488–1492); `commit()` sits in the finally outside the catch; matches the recorded gate-F2 audit (track-8.md:1230–1249) | REFUTED — doc scoping is consistent with code and the recorded audit; null verdict |
| H4 | Step 5's "exit 0 = …verified against the manifest" holds for every dump the runbook covers | `verifyV15StructuralStrictness` gated `exporterVersion >= 15` (DatabaseImport.java:380–382); a `manifest` tag in a declared-legacy dump is REJECTED as unsupported (DatabaseImport.java:358–364) — legacy dumps carry no manifest and get no whole-stream check | CONFIRMED overclaim for legacy (≤ 14) dumps → **WC61** |
| H5 | Rejection-table schema-version row applies to all dumps | schema-version arms live inside the `exporterVersion >= 15` block (DatabaseImport.java:454–484); pin `v14DumpWithAlienSchemaVersionStaysLenient` green before AND after (track-8.md Step 6 record) | CONFIRMED v15-only; table row unscoped → **WC62** |
| H6 | Step 2 "failed or interrupted export deletes its temporary file" is universally true | `cleanUpOnFailure` runs only on in-process exception paths (DatabaseExport.java:277–296, invoked from the catch at 253–263); kill -9/crash orphans the temp file — the doc's own crash-residue section says so | CONFIRMED contradiction ("interrupted" arm) → **WC63** |
| H7 | Best-effort ack gate: doc ↔ SR3 ↔ code all agree | export flag `-bestEffort` (DatabaseExport.java:462–463), fail-fast default (763–771), marker `best-effort` written (530–533); import flag `-acceptBestEffortDump` (DatabaseImport.java:278–281), gate marker-keyed OUTSIDE every version arm (514–519), marker parsed for any version (842–847); inline comment cites SR3/BG23/CQ26/CS66 (507–513); links to broken RIDs removed via `migrateLinksInImportedDocuments` (675–682) + `LinkConverter.java:26` | CONSISTENT — null verdict (obligation 3 satisfied) |
| H8 | Version matrix: ≥ 16 redirect / == 15 strict / ≤ 14 lenient / unparseable reject | ≥ 16 reject naming both versions AHEAD of v15 arms (DatabaseImport.java:447–452); v15 strict set (502–511, 583–640); unparseable version fail-closed naming raw value (800–810); undeclared version → SR2 arms (319–325, 371–375); `EXPORTER_VERSION = 15` (DatabaseExport.java:65) | doc table rows match — null verdict (obligation 4 satisfied, modulo WC62 scoping) |
| H9 | Condemn-target claims (openable, no in-DB signal, crash ≡ failure) match code + SR1 | structural rejections only THROW (DatabaseImport.java:583–640); nothing writes a failure marker into the target; SR1 record (design-drafts:658–667); pin #16 documents no-clean-target assertion | CONSISTENT — null verdict (obligation 2 satisfied) |
| H10 | README entry consistent with the page | title/scope match (docs/README.md:10) | CONSISTENT — null verdict |
| H11 | Redirect quote in the intro is verbatim | SchemaShared.java:900–904 | VERBATIM MATCH — null verdict |
| H12 | Genesis-recovery guidance matches the engine's own prescription | create-over-corpse refusal message says "drop it and re-create" (YouTrackDBInternalEmbedded.java:855–859); drop is explicitly exempted for corpses per CN54 (947–960); doc prescribes manual directory deletion instead | PARTIAL MISMATCH → **WI62** |
| H13 | Crash-residue file patterns + "delete at any time" | temp `<final>.<uuid>.tmp` (DatabaseExport.java:140), spill `ytdb-export-record-*.spill` in the dump’s parent dir (DatabaseExport.java:133; SpillableRecordBuffer.java:68) — patterns match; but a LIVE export's temp file is not safe to delete "at any time" | patterns verified; timing overclaim → **WI63** |
| H14 | Page follows docs/ house style | sibling pages use Title Case `##` headings (security.md, object-oriented.md, getting-started.md); runbook uses sentence case; sibling pages annotate code fences (```java etc.), runbook fences are bare | minor deviations → **WS60/WS61** |
| H15 | The accept/reject table covers every operator-reachable rejection | best-effort-without-ack rejection (DatabaseImport.java:514–519) and dangling-info-field rejection (789–796) absent from the table | incompleteness → **WI64** |

Alternative hypotheses considered and rejected are recorded inline per finding below.

---

## Verified-claims trace (doc claim → code evidence)

Every behavioral claim in `docs/operator-migration-procedure.md`, walked top to bottom:

| Doc (line) | Claim | Code evidence | Verdict |
|---|---|---|---|
| 3–8 | old-format open rejected with redirect; quoted message | SchemaShared.java:896–904 (throws `ConfigurationException` with exactly that text when `schemaVersion != CURRENT_VERSION_NUMBER`) | ✓ verbatim |
| 14–16 | `EXPORT DATABASE /backups/mydb.json.gz` | no such command in grammar/parser/console/server/API | ✗ **WI60** |
| 20–24 | failed export deletes temp, promotes nothing; absent dump = incomplete | DatabaseExport.java:277–296 (`cleanUpOnFailure` deletes temp, final name never touched); `promote()` only after the full write (229–234, `completed = true` at 234); CS41 comment 126–127 | ✓ for in-process failures; ✗ for "interrupted" (kill/crash) — **WC63** |
| 26–29 | import deletes and replaces content | `removeDefaultNonSecurityClasses` (DatabaseImport.java:758–777, via deferred preamble 546–563), leftover-record deletion (1758–1765), system-record overwrite (1505–1530) | ✓ |
| 33–35 | `IMPORT DATABASE /backups/mydb.json.gz` | no such command | ✗ **WI60** |
| 37–39 | exit 0 = every dump entry consumed and verified against the manifest; counts cross-checked; stream fully consumed and validated | `verifyManifestCount` ×4 (DatabaseImport.java:600–603, 643–651); CS43 drain + `verifyFullyConsumed` + `verifyPhysicalSize` (618–634); `parsedRecordCount++` per entry (1481) | ✓ for v15 dumps only — legacy overclaim → **WC61**; phrasing mandate itself satisfied (obligation 1) |
| 40–44 | two silent arms: legacy-declared apply-failure swallow; schema/index-manager-marked record deleted silently | swallow: catch `Throwable`, rethrow unless `DatabaseException` (1541–1556); v15-unreachability per gate-F2 audit + hierarchy check (H3); delete arm: `case SCHEMA_MANAGER, INDEX_MANAGER -> record.delete(); rid = null` (1496–1499) with `parsedRecordCount` already counted (1481) so the manifest still passes | ✓ both arms real, correctly characterized |
| 52–55 | any failure incl. crash condemns target; crash ≡ failure | SR1 record (design-drafts:658–667); no recovery/marker code exists | ✓ (obligation 2) |
| 59–65 | condemned target openable, no in-database signal, exit status is the only record | structural rejections throw only (583–640); no failure marker written anywhere in the importer | ✓ (obligation 2) |
| 66–71 | info-section rejections are pre-mutation; later rejections post-mutation | `runPreFlightChecks` before `runDeferredImportPreamble` (331–336, 429–434 javadoc); post-loop strictness comment (376–383) | ✓ matches SR1 scoping |
| 75 (table) | ≤ 14 → lenient path unchanged | no v15 arm reachable; serializer switch `< 14` (823–826) | ✓ (honest dumps; SR3 widening affects only hand-edited ones) |
| 76 | v15 → gzip mandatory, whole-stream, section presence, manifest | 502–511, 583–640 | ✓ |
| 77 | ≥ 16 → redirect naming both versions | 447–452 | ✓ |
| 78 | no/unparseable version → rejected | 319–325, 371–375, 800–810 | ✓ |
| 79 | schema version out of range → rejected naming declared+supported | 454–484 — but only inside the `>= 15` block | ✗ scoping → **WC62** |
| 80 | re-compressed/gunzipped v15 → rejected, no override | 502–511 (file path, `physicalSize >= 0`) | ✓ |
| 81 | tampered v15 (sections/counts/trailing) → rejected loudly, condemned | 585–597 (presence/dupes), 600–603 (counts), 618–634 (trailing/consumption) | ✓ |
| 85–89 | default export fail-fast; `-bestEffort=true` skips + records in `brokenRids`; dump marked | DatabaseExport.java:763–771 (rethrow-by-default), 462–463 (flag), 530–533 (marker), manifest tallies 88–93 | ✓ |
| 89–91 | importer refuses marked dump without `-acceptBestEffortDump=true` | DatabaseImport.java:278–281 (flag), 514–519 (marker-keyed gate); flag `=`-syntax valid per `DatabaseTool.setOptions` (DatabaseTool.java:46–61) | ✓ (obligation 3; matches SR3 + inline comment 507–513) |
| 91–92 | links to broken RIDs removed on import | 675–682 → `migrateLinksInImportedDocuments`; LinkConverter.java:26 | ✓ under default `-migrateLinks=true` (option undocumented; acceptable) |
| 96–101 | crash residue: `<final-name>.<uuid>.tmp`, `ytdb-export-record-*.spill`, in dump's directory | DatabaseExport.java:140 (temp name), 133 (spill dir = dump’s parent), SpillableRecordBuffer.java:68 (prefix/suffix) | ✓ patterns/location; "delete at any time" overbroad → **WI63** |
| 101–102 | never promoted, never mistaken for a dump | promote only on completed export (DatabaseExport.java:229–234, promote() at 265–271); unique non-dump suffixes | ✓ |
| 105–112 | genesis-incomplete: open refuses loudly; no self-heal; OSystem bricks server start | SharedContext.java:50–54, 165–175; GenesisIncompleteException; YouTrackDBInternalEmbedded.java:349–354; CN59 record (track-8.md:1012–1015) | ✓ behavior; recovery prescription diverges from engine message → **WI62** |

---

## Findings

### WI60 — blocker — runbook steps 1 and 4: the quoted commands do not exist in the product

Premises:
1. Step 1 instructs the operator to run `EXPORT DATABASE /backups/mydb.json.gz`
   (operator-migration-procedure.md:14–16); step 4 instructs `IMPORT DATABASE
   /backups/mydb.json.gz` (:33–35).
2. The YQL grammar (`core/src/main/grammar/YouTrackDBSql.jjt`) contains no
   IMPORT/EXPORT token or statement; none of the 222 generated parser classes is an
   import/export statement; `docs/yql/YQL-Commands.md` lists no such command.
3. The `console` module is the Gremlin console (`console/pom.xml` mainClass =
   `org.apache.tinkerpop.gremlin.console.Console`) — it has no `EXPORT DATABASE` command.
4. The server and the public API (`com.jetbrains.youtrackdb.api.YouTrackDB`,
   `YourTracks`) expose no export/import surface; the ONLY non-test caller of
   `DatabaseExport`/`DatabaseImport` in the repo is the LDBC benchmark tool
   (`jmh-ldbc/.../LdbcDatabaseTool.java:57,85`).
5. The tools live in the `internal` package, which AGENTS.md declares non-public.

Verdict: the runbook's happy path is not executable as written. Operator-stuck scenario:
an operator at step 1 types `EXPORT DATABASE …` into the only interactive surfaces that
exist (Gremlin console, `tx.command(…)` YQL) and gets a parse error; the page's only
fallback is "or the equivalent `DatabaseExport` tool invocation" (:18) with no
invocation shown, no class package, no session-acquisition recipe — and step 4 does not
even name `DatabaseImport`. The operator cannot proceed without reverse-engineering
internal classes. Alternative hypothesis — the commands are intentionally
forward-looking for a CLI landing in another track: no such CLI work item is named in
the Track 8 plan or the WI3 content mandate (track-8.md:1328–1340), and a runbook that
documents a nonexistent surface with no "not yet available" marker fails its charter
either way. Fix direction: either show the real programmatic invocation
(open an embedded session, construct the tool, `run()`), or gate the page on the actual
command surface and mark the syntax as such.

### WI61 — should-fix — "exit status 0" gates (steps 2 and 5) have no defined observable

Premises:
1. Steps 2 and 5 gate the whole procedure on "exit status 0"
   (operator-migration-procedure.md:20–21, 37); the condemn section names "the
   importer's own exit status" as the ONLY record of failure (:63–65).
2. Neither `DatabaseExport` nor `DatabaseImport` has a `main()`; no `System.exit` exists
   in `core/.../db/tool/`; there is no CLI wrapper in the repo (H1/H2).
3. The actual success signal in code is "the tool method returned without throwing"
   (`importDatabase()` wraps every failure into a thrown `DatabaseExportException`,
   DatabaseImport.java:401–420; the exporter rethrows its primary,
   DatabaseExport.java:262–283) — the design record itself says "the thrown-primary
   contract *is* the operator surface" (track-8-design-drafts.md:475–477).

Verdict: the doc's load-bearing gate references an observable the product does not emit.
Operator-misled scenario: an operator embedding the tool in a script/JVM that catches or
logs exceptions has NO exit status to gate on and no doc guidance that "no exception
thrown" is the real contract; conversely a wrapper JVM that exits 0 despite a caught
import exception would pass the doc's gate on a condemned target. Note the exit-0
*phrasing* was mandated by the plan (M.5 #18: "import completeness = importer exit 0"),
so the fix is to bind the phrase to the real surface (e.g., "exit 0 of the wrapper
process / no exception thrown from the tool — the tool signals every failure by
throwing"), not to delete it. (Obligation 1's wording requirement itself is met —
see null verdicts.)

### WC61 — should-fix — step 5's exit-0 meaning is stated universally but holds only for v15 dumps

Premises:
1. Step 5 states unconditionally: "exit 0 = every dump entry was consumed and **verified
   against the manifest**" (operator-migration-procedure.md:37–39).
2. The manifest/whole-stream verification runs only for `exporterVersion >= 15`
   (DatabaseImport.java:380–382); a declared-legacy dump carrying a `manifest` tag is
   rejected as an unsupported tag (:358–364) — i.e., legacy dumps HAVE no manifest and
   receive no count cross-check, no section-presence check, no gzip trailer/consumption
   check.
3. The runbook's own accept/reject table says ≤ 14 dumps ride "the legacy lenient path,
   unchanged" (:75) — the two statements cannot both be unconditioned.
4. The page's primary scenario (intro, :3–8) is migrating a database created by an OLD
   release — exactly the case that can produce a legacy (≤ 14) dump riding the lenient
   path.

Verdict: internal contradiction with operator impact. Operator-misled scenario: an
operator migrating from an old release sees exit 0 and, per step 5, believes record/
class/index counts were cross-checked against a manifest — when in fact none of that ran;
the confidence is misplaced exactly where the dump format is weakest. Alternative
hypothesis — "the old release's exporter also writes v15 dumps, so legacy dumps are out
of scope": refuted; the current release IS the first with `EXPORTER_VERSION = 15`
(DatabaseExport.java:65, introduced by this track), so every dump from an older release
declares ≤ 14. Fix: scope the manifest-verification sentence to v15 dumps and state what
exit 0 means for a legacy dump (entries consumed; no structural verification exists).

### WC62 — should-fix — accept/reject table: schema-version rejection row is not scoped to v15

Premises:
1. Table row (operator-migration-procedure.md:79): "Schema version outside the supported
   range | Rejected naming the declared and supported versions…" — unconditional.
2. The schema-version arms (missing/malformed/out-of-range) live INSIDE the
   `exporterVersion >= 15` block (DatabaseImport.java:454–484); the declared-legacy path
   never reaches them (FM-M12), pinned by `v14DumpWithAlienSchemaVersionStaysLenient`
   (green before and after — track-8.md Step 6 record).
3. Neighboring rows that are v15-only DO carry the "v15" qualifier (:80, :81), so a
   reader will take row :79's lack of qualifier as meaningful.

Verdict: doc-vs-code mismatch. Operator-misled scenario: an operator importing a legacy
dump from a very old release expects an out-of-range schema version to be rejected
loudly per the table; the lenient path instead imports whatever it can, and the operator
reads the absence of the rejection as "schema version verified compatible". Fix: qualify
the row ("Schema version outside the supported range (v15 dumps)") or add a note that
legacy dumps skip this check.

### WC63 — should-fix — step 2 contradicts the crash-residue section on temp-file deletion

Premises:
1. Step 2: "A **failed or interrupted** export deletes its temporary file and promotes
   nothing" (operator-migration-procedure.md:21–22).
2. Crash-residue section: "A **killed or crashed** export can orphan two kinds of
   temporary files" including `<final-name>.<uuid>.tmp` (:94–99).
3. Code: temp deletion happens only on in-process exception paths
   (`cleanUpOnFailure`, DatabaseExport.java:277–296, plus the completion-gated
   `close()`); a kill -9 / power loss deletes nothing — FM-M18 records exactly this
   (design-drafts:457).

Verdict: the two doc statements cannot both be true for the kill/crash arm; the residue
section (and FM-M18) is the accurate one. The safety-critical half of step 2 ("promotes
nothing") is consistent everywhere. Operator-confused scenario: after a killed export the
operator, told the temp file is deleted, finds `mydb.json.gz.<uuid>.tmp` on disk and must
decide alone whether it is evidence of a corrupted dump. Fix: in step 2 say "a failed
export deletes its temporary file; a killed/crashed one may orphan it (see Crash residue
below) — in both cases nothing is promoted".

### WI62 — should-fix — no concrete "discard" action for condemned targets / genesis corpses; diverges from the engine's own prescription

Premises:
1. The condemn section commands "discard it and import again into a fresh database"
   (operator-migration-procedure.md:54) and the genesis section commands "discard the
   database's directory and create the database again" (:107–108) — neither names a
   command or API call.
2. The engine's own refusal message prescribes DROP: "If it is the residue of a crashed
   creation, **drop it and re-create**" (YouTrackDBInternalEmbedded.java:855–859).
3. Drop is deliberately exempted from the genesis-completion gate so that a corpse CAN be
   dropped (CN54; YouTrackDBInternalEmbedded.java:947–960 — corpse deleted without
   onDrop listeners).
4. Manual directory deletion under a running server is riskier than the sanctioned drop
   path (registered storage, file locks).

Verdict: instruction incompleteness plus a mild contradiction with the product's own
error text. Operator-stuck scenario: mid-incident, the operator hits the
genesis-incomplete refusal, reads the engine message saying "drop it", then reads this
page saying "discard the database's directory" — two different prescriptions, no command
for either. Fix: name the drop API/command as the sanctioned discard for user databases
(and keep directory deletion for the OSystem case, where no drop surface exists).

### WI63 — suggestion — "an operator may delete them at any time" is unsafe while an export is running

Premises:
1. Crash-residue section: temp/spill files "are never promoted… and an operator may
   delete them **at any time**" (operator-migration-procedure.md:100–102).
2. A RUNNING export's `<final>.<uuid>.tmp` is live (DatabaseExport.java:140–144); deleting
   it mid-export makes the promote fail — the export fails (loudly, fail-safe, but the
   operator caused it).
3. The design record scopes cleanup to crash ORPHANS ("reclaimed by operator cleanup",
   FM-M18 row, design-drafts:457).

Verdict: overbroad claim, fail-safe direction (a killed export fails loudly and promotes
nothing), hence suggestion. Fix: "may delete them whenever no export is in progress".

### WI64 — suggestion — accept/reject table omits two operator-reachable rejections and one next action

Premises:
1. The table's title claims coverage of "What the importer accepts and rejects"
   (operator-migration-procedure.md:73).
2. Missing rows: best-effort-marked dump without `-acceptBestEffortDump=true`
   (DatabaseImport.java:514–519 — documented only in the later best-effort section) and
   the dangling/malformed info-field rejection (:789–796, FM-M10).
3. Row :78 ("No / unparseable exporter version") gives no next action, unlike its
   neighbors (an operator's remedy is: re-export with a supported release / recover the
   original file).

Verdict: incident-scannability gap only — each fact exists elsewhere on the page or in
the error message itself. Fix: add the two rows (the ack-gate row can point at the
best-effort section) and a next action for row :78.

### WS60 — suggestion — heading case deviates from the docs/ house convention

Premises:
1. Sibling pages use Title Case `##` headings ("Core Concepts", "Predefined Roles",
   "Property Types", "Schema Evolution at Runtime" — security.md:14–23,
   object-oriented.md:143–200, getting-started.md:13–43).
2. The runbook uses sentence case throughout ("The procedure", "Any failure condemns the
   target", "Best-effort dumps", "Crash residue from exports").

Verdict: cosmetic inconsistency; the sentence-case headings are otherwise well-chosen and
incident-scannable (the failure heading is findable in seconds). Fix at leisure.

### WS61 — suggestion — bare code fences without language/context annotation

Premises:
1. Sibling pages annotate every fence (```java, ```xml — getting-started.md:17, 27).
2. The runbook's two command fences (:14–16, :33–35) are unannotated and give no hint of
   WHERE the command is entered (shell? console? YQL?) — which also compounds WI60.

Verdict: style-level symptom of WI60; fix jointly (annotate with the real invocation
surface once WI60 is resolved).

---

## Null verdicts (clean charter items)

- **Obligation 1 (exit-0 phrasing)** — the mandated sentence "exit 0 = every dump entry
  was consumed and verified against the manifest" is present verbatim (doc:37–38) and
  nowhere does the page equate exit 0 with bare "complete/successful import"; both known
  silent arms are named with accurate mechanics (swallow: DatabaseImport.java:1541–1556;
  silent delete: 1496–1499 with the tally at 1481 keeping the manifest satisfied). The
  "legacy-declared" scoping of the swallow arm was adversarially checked (H3) and holds:
  the catch swallows only `DatabaseException`; `SerializationException` and
  `RecordNotFoundException` extend `CoreException`, not `DatabaseException` (verified in
  source), and the `DatabaseException`-capable getters inside the try are
  `exporterVersion <= 13`-gated (1488–1492) — consistent with the gate-F2 audit record
  (track-8.md:1230–1249). Residual defects are the observable binding (WI61) and the
  legacy scoping of the manifest half (WC61), not the phrasing mandate.
- **Obligation 2 (condemn-target)** — fully satisfied: openable-with-no-in-database-signal
  stated explicitly (doc:59–65), crash-during-import declared equivalent (doc:54–55),
  same recovery for all failure shapes; consistent with SR1 (design-drafts:658–667) and
  with the code (rejections only throw; no marker written).
- **Obligation 3 (best-effort ack gate)** — flag name exact (`-acceptBestEffortDump=true`,
  code:278–281; `=`-syntax valid per DatabaseTool.setOptions:46–61), trigger exact (the
  `best-effort` marker, marker-keyed, no version qualifier in the doc — matching SR3 and
  the inline comment at code:507–513), consequence exact (pre-flight refusal, 514–519).
  Doc, SR3 record, and inline comment are mutually consistent.
- **Obligation 4 (version matrix)** — table rows for ≤ 14 / 15 / ≥ 16 / unparseable match
  the code exactly (447–452, 454–511, 583–640, 800–810, 319–325, 371–375), including the
  redirect naming both versions and the schema-version range messages naming declared vs
  supported with direction-specific remedies (471–484). Only defect: WC62 scoping.
- **WC cross-references** — the page contains no hyperlinks (nothing to break); the quoted
  redirect message matches SchemaShared.java:900–904 verbatim; the README index entry
  (docs/README.md:10) matches the page's title and scope; the SR3 ruling record matches
  the inline code comment and the Step 6 track-file record; residue file patterns match
  code (DatabaseExport.java:133,140; SpillableRecordBuffer.java:68 — spill dir IS the
  dump's parent on the operator file path). No runbook statement contradicts R1, Q-M2,
  Q-M3, SR1, SR2, or SR3 beyond the scoping issues filed above.
- **WS scannability** — the failure doctrine has its own prominent section with a
  declarative heading; key terms are bolded; the accept/reject matrix is a table; an
  incident responder finds "what do I do on failure" in one scroll. Good.
- **Doc-content pin** — the content test
  (`DatabaseImportInfoMatrixTest.operatorMigrationProcedureDocExistsWithMandatedContent`,
  core/src/test/...:425–456) pins the mandated phrases; none of the fixes proposed above
  would break the pinned strings except rewording step 2 (WC63 — keep "exit status" and
  the mandated exit-0 sentence intact).

---

## Findings summary

| ID | Severity | Location | Summary | Operator impact |
|---|---|---|---|---|
| WI60 | blocker | operator-migration-procedure.md:14–18, 33–35 (steps 1, 4) | `EXPORT DATABASE`/`IMPORT DATABASE` commands do not exist anywhere in the product (grammar/console/server/API all checked) | Operator cannot execute the happy path at all; stuck at step 1 with a parse error and no shown alternative invocation |
| WI61 | should-fix | steps 2, 5 (:20–21, :37); condemn section (:63–65) | "exit status 0" has no observable — no CLI/main/System.exit exists; real contract is "tool returns without throwing" | Gate is unusable as written; a wrapper that swallows the thrown failure passes the doc's gate on a condemned target |
| WC61 | should-fix | step 5 (:37–39) vs table row :75 | "verified against the manifest" stated universally but code runs manifest/whole-stream checks only for v15 dumps; legacy dumps have no manifest | Operator migrating from an old release (the page's primary scenario) gains false confidence in a verification that never ran |
| WC62 | should-fix | table row :79 | Schema-version-range rejection is v15-only in code (`>= 15` block) but the row is unscoped, unlike its v15-qualified neighbors | Operator with a legacy dump reads non-rejection as "schema version verified compatible" |
| WC63 | should-fix | step 2 (:21–22) vs crash-residue (:94–99) | "failed **or interrupted** export deletes its temporary file" contradicts the (correct) crash-residue section; kill/crash orphans the temp | Post-kill, operator finds the `.tmp` the doc said was deleted and must self-diagnose |
| WI62 | should-fix | condemn (:54), genesis (:105–112) | "Discard" never bound to a command; engine's own refusal message prescribes `drop` (CN54-exempted), doc prescribes manual directory deletion | Mid-incident the operator faces two divergent prescriptions and no command for either |
| WI63 | suggestion | crash-residue (:100–102) | "may delete them at any time" unsafe while an export is running (live temp file) | Operator cleanup during a running export kills the export (fail-safe but self-inflicted) |
| WI64 | suggestion | table (:73–81) | Table omits the best-effort-ack rejection and the dangling-info-field rejection; row :78 lacks a next action | Slower incident triage; facts exist elsewhere on the page |
| WS60 | suggestion | all `##` headings | Sentence-case headings vs Title Case house convention in sibling docs | Cosmetic |
| WS61 | suggestion | fences :14–16, :33–35 | Bare code fences with no language/surface annotation (house style annotates) | Compounds WI60's "where do I type this" gap |

Review complete: 1 blocker, 5 should-fix, 4 suggestions. Null verdicts recorded for
obligations 2, 3, 4, the exit-0 phrasing mandate, cross-reference integrity, SR3
consistency, and incident scannability.
