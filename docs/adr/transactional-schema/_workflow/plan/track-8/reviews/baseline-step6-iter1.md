# Baseline review — Track 8 Step 6, iteration 1

- **Subject:** commit `612340c91d` ("importInfo field-validation matrix, >= 16 redirect, WI3
  operator doc") vs parent `fb9867e751`; branch `transactional-schema`, HEAD `f2cebaa9c3`
  (track-file update only on top — code identical at HEAD).
- **Perspective:** code baseline — correctness & bugs (BG), code quality (CQ), test quality (TQ).
- **Finding-ID starts:** BG29, CQ29, TQ29.
- **In-scope code:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/tool/DatabaseImport.java`
  (+207/-23), `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/tool/DatabaseImportInfoMatrixTest.java`
  (new, 12 tests). Supporting read: `JSONReader.java`, `DatabaseImportHardeningTest.java`,
  `SchemaShared.java`, `DatabaseExport.java`. The operator doc's prose is out of scope (separate
  reviewer); only code/doc-pin consistency checked.
- **Spec context read:** track-8.md Step 6 bullet (:549-577), Step 6 Episodes entry (:1274+),
  design-drafts §0 R1/R4 (:20-49), M2.b-5 (:431-435), Q-M2 ruling (:627-641), SR1/SR2/SR3
  (:660-691), WC6 (:892), FM-M10/M11/M12 (:455-457), M.5 pins #9-#18 (:496-524).

## Method

For each criterion: decision criteria and numbered premises first, then a trace-backed verdict.
Behavioral claims cite `file:line` at HEAD (identical to the reviewed commit for code files) or
diff hunks. Alternative hypotheses logged per criterion before the verdict.

Key reader semantics established up front (used throughout; JSONReader.java):

- P-R1. `readString(FIELD_ASSIGNMENT)` (JSONReader.java:106,118-127) accumulates chars until an
  unquoted, non-brace-embedded `:`; if the token starts with `"` it returns
  `substring(1, lastIndexOf('"'))` — so a dangling name that swallowed structural text keeps
  embedded `"`/`}`/`,` characters in the returned name.
- P-R2. `readNext(NEXT_IN_OBJECT)` terminates at the first `,` or `}` that is outside a string
  and outside `{...}` nesting; `[`/`]` are NOT nesting-tracked (JSONReader.java:224-259) — an
  array-valued field splits at its first element comma.
- P-R3. `getValue()` returns the raw token with string quotes preserved
  (`preserveQuotes=true` on the default path, JSONReader.java:196-202); this is what makes
  `checkKnownInfoFieldIsString`'s quote heuristic (DatabaseImport.java:872-877) workable.
- P-R4. At parent, `readBoolean` (JSONReader.java:179-181) STRIPPED quotes before
  `Boolean.parseBoolean` — `"best-effort": "true"` (quoted string) parsed as `true`.
- P-R5. On clean EOF, `readNext` returns with STALE `value`/`lastCharacter`
  (JSONReader.java:209-216: `if (!in.ready()) return this;` before any buffer reset).

## Criterion 1 — Matrix completeness & correctness (Q-M2 + Step 6 bullet)

**Decision criteria:** every Q-M2 row (four ruled items) and every Step 6 bullet clause has an
implemented outcome matching the spec; no spec row missing; no row invented beyond spec; the
collect-at-parse / judge-at-pre-flight split honors R1.

**Premises:**
1. Q-M2(1) dispatch (drafts:627-631): `<= 14` lenient, `== 15` strict, `>= 16` redirect naming
   both versions; undeclared/malformed exporter-version rejected fail-closed (SR2, WI12a).
2. Q-M2(2) (drafts:631-634): schema-version mandatory in v15 dumps, `6 <= declared <= 6` as a
   range; missing or malformed → reject.
3. Q-M2(3) (drafts:634-636): mandatory fields = exporter-version + schema-version; other known
   info fields type-checked if present, not required.
4. Q-M2(4) (drafts:636-638): unknown extra fields tolerated, logged.
5. Step 6 bullet adds: all rejection messages name declared vs supported values; dangling-field
   parse rejection (FM-M10); R1 = declared `<= 14` untouched.

**Row-by-row trace (all checked):**

| Spec row | Implementation | Verdict |
|---|---|---|
| `>= 16` redirect naming both versions | DatabaseImport.java:447-453 — names declared version and `DatabaseExport.EXPORTER_VERSION` (=15, DatabaseExport.java:65) | ✓ |
| `== 15` strict arming | :454 `>= 15` block, effectively `== 15` after the :447 redirect (see Criterion 2) | ✓ |
| `<= 14` lenient | no new arm fires below 15: judgments at :454 gated; captures at :828-858 side-effect-free on legacy (see Criterion 3, one exception = BG29) | ✓ except BG29 |
| undeclared exporter-version → reject (SR2) | pre-existing Step-5 arms (:319-324 first-non-info tag, :371-375 EOF) — unchanged, matrix relies on them | ✓ |
| malformed exporter-version → reject naming raw (WI12a) | :798-810 — `Integer.parseInt(raw)` failure throws naming the raw token; same fail-closed outcome as undeclared | ✓ (message nit CQ30) |
| schema-version missing (v15) → reject | captured :828-838 (`schemaVersionDeclared` latch), judged :466-470 | ✓ |
| schema-version malformed → reject naming raw | `malformedSchemaVersionRaw` kept :835-836, judged FIRST :460-465 (so a malformed-then-valid duplicate still rejects — fail-closed) | ✓ |
| schema-version out of range → reject naming declared vs supported | :471-481, range `6..6` built from both constants :458-459; message adds direction-specific guidance (redirect for above-range, re-export for below) | ✓ |
| known optional fields type-checked if present | strings :850-851 (name, engine-version, engine-build, schemaRecordId, indexMgrRecordId), number :852-853 (storage-config-version), boolean :839-845 (best-effort); exactly the v15 exporter's field set (DatabaseExport.java:504-534) | ✓ |
| type violations judged v15-only (R1) | collected into `infoFieldTypeViolations` at parse, judged inside the `>= 15` block :483-488 | ✓ code; semantic exception for best-effort = BG29 |
| unknown fields tolerated + logged | collected :856-859 (value consumed via the same `readNext(NEXT_IN_OBJECT)` as at parent), logged :490-493 under v15 | ✓ (legacy path never logged at HEAD either — no R1 delta) |
| dangling/malformed field name → parse rejection (FM-M10) | :792-796, ungated — the ONE sanctioned ungated delta (Criterion 3) | ✓ |
| messages name declared vs supported | all arms do except the WI12a message (names raw only, not the supported version — CQ30) | ✓ minus CQ30 |

**No invented rows:** the only other change inside `importInfo` is the CS63 re-declaration latch
(:817-823), which is verbatim-carried from Step 5 (parent fb9867e751 importInfo :703-717 — diff
confirms it moved into the switch unmodified). The `MIN_IMPORTABLE_SCHEMA_VERSION` constant
(:174) is Q-M2(2)'s own "(= 6)".

**Deferred-judgment design vs R1:** the split (capture at parse for all versions; judge under
`>= 15`) is exactly what keeps the legacy path lenient while letting the version — which is
itself only known mid-parse — gate the outcome. Value-consumption on the legacy path is
call-for-call identical to parent (`readInfoFieldRawValue` :868-870 wraps the same
`readNext(NEXT_IN_OBJECT)` the parent's else-branch used), so no legacy dump parses differently.
One semantic exception traced: the best-effort marker's parse changed (BG29, Criterion 3).

**Alternative hypotheses checked:** (a) could a v15 dump with a duplicate info section be judged
twice inconsistently? Second `importInfo` re-runs `runPreFlightChecks` (:329-336); state
accumulates monotonically (violations/unknowns only grow, schema-version latch only tightens via
malformed-first ordering), so a second pass can only reject more, never less; the duplicate
itself is caught post-loop by WI10c. No fail-open. (b) Could Q-M2(3)'s "mandatory fields
enforced" mean more fields than exporter-version + schema-version? No — the ruling names exactly
those two ("the fields import decision logic consumes", drafts:634-635).

**Verdict:** matrix complete and correct against the spec; no missing row, no invented row. The
best-effort quoted-marker semantic drift is filed under Criterion 3 (BG29); the WI12a
message-letter nit is CQ30.

## Criterion 2 — `>= 16` reject-with-redirect

**Decision criteria:** (a) the redirect precedes every v15-keyed arm so those arms only ever see
`== 15`; (b) the message names both versions with no hardcoded "v15" residue reachable for
`>= 16` (CQ24 resolution); (c) boundary behavior at 15, 16, large, and negative values is sane.

**Premises:**
1. `runPreFlightChecks` is invoked once per parsed info section (:333-336), before
   `runDeferredImportPreamble` and before any other section can be processed (SR2's
   first-non-info-tag trigger :319-324 rejects any dump whose sections precede `info`).
2. The redirect is the first statement of `runPreFlightChecks` (:447-453).

**Ordering trace (exhaustive over v15-keyed arms):**
- Pre-flight arms: schema-version block :454-481, type violations :483-488, unknown-field log
  :490-493, gzip-framing :502-507 (message "a v15 dump is always gzip-framed"), best-effort ack
  gate :514-519 — ALL after :447. ✓
- Section-loop arms: `clusters` alias rejection :359-364 ("a v15 dump names its collections
  section 'collections'"), version-gated `manifest` tag :380 — reachable only after `info`
  parsed (SR2 trigger), hence after the redirect fired for `>= 16`. A later info section
  re-declaring 16 over a latched 15 is stopped by the CS63 latch (:817-823) before the section
  loop's arms can see 16. ✓
- Post-loop `verifyV15StructuralStrictness` (:380-382) — only reachable if pre-flight passed,
  i.e. version == 15. ✓
- `importInfo`-internal version use: only `< 14` serializer switch (:824-826) — not v15-keyed. ✓

Conclusion: every `>= 15`-keyed arm is effectively `== 15`; every "v15" message literal is exact
where reachable. CQ24 is genuinely resolved by ordering.

**Message:** ":447-453 names the declared version twice and the supported maximum via
`DatabaseExport.EXPORTER_VERSION` — no hardcoded literal; a future EXPORTER_VERSION bump keeps
the message honest (though the `>= 16` literal itself would then need bumping — it is spec'd as
the ruled dispatch constant, and the adjacent comment says so; acceptable).

**Boundary enumeration (each traced):**
- **15:** redirect does not fire; strict arms run. Proven accepting by
  `unknownInfoFieldIsToleratedAndLogged` and the rehearsal (both import v15 dumps end-to-end).
- **16:** redirect fires (test `v16DumpIsRejectedWithRedirectNamingBothVersions`).
- **17 / Integer.MAX_VALUE:** `>= 16` fires, message names the value — traced, untested (TQ30).
- **Beyond int range (e.g. 99999999999):** `Integer.parseInt` throws → WI12a "unparseable
  exporter version '99999999999'" (:801-810) — fail-closed, names the raw value; not the
  redirect wording but an acceptable outcome (the value cannot be dispatched). Untested (TQ30).
- **Negative:** `-5` parses, `<= 14` → lenient path (spec-literal: Q-M2(1) says `<= 14` →
  lenient; no honest dump declares a negative version). `-1` collides with the undeclared
  sentinel (:113, :823) — pre-flight is skipped (:333) and the SR2 arms reject with the
  "without declaring an exporter version" wording. Fail-closed but message-inaccurate;
  pre-existing Step-5 shape (parent :718 identical assignment), not a Step 6 delta — noted as
  CQ32 (suggestion).

**Test discrimination gap:** no test pins the ORDERING. Counterexample: move the :447 redirect
below the schema-version block (:454-481); all 12 matrix tests and all 21 hardening tests stay
green (the v16 fixture carries a valid schema-version 6, is gzip-framed, and is not
best-effort), yet a v16 dump missing its schema-version would now be rejected with "a v15 dump
always carries one" — the exact hardcoded-wording defect CQ24 was about, resurrected silently.
Filed as TQ29 (should-fix).

**Alternative hypothesis checked:** could any code path read `exporterVersion >= 15` BEFORE
`runPreFlightChecks` ever runs (disarming the ordering claim)? Swept all `exporterVersion`
uses (grep :319-:382, :502, :645, :675, :1058, :1220+): the pre-loop ones are the SR2 trigger
(version-agnostic) and the constructor's gzip probe (version-agnostic); the record/schema-path
uses are only reachable after pre-flight. No counterexample.

**Verdict:** implementation correct and fully traced; the ordering property itself is untested
(TQ29) and the WI12a message omits the supported version (CQ30).

## Criterion 3 — R1 legacy preservation on `<= 14`

**Decision criteria:** (a) the sanctioned ungated delta — the dangling/malformed-field-name
parse guard — must be provably unable to reject any honest dump of any version; (b) every other
behavioral change in the diff must be v15+-gated (or acceptance-equivalent to parent on the
legacy path).

**Delta enumeration (exhaustive over the importInfo/pre-flight diff hunks):**

1. **Dangling/malformed field-name guard (:792-796) — ungated, sanctioned.** Fires on an empty
   name or a name containing any of `"{}[],:`. Honest info field names across all exporter
   generations are fixed literals: the current v15 set (DatabaseExport.java:508-533: `name`,
   `exporter-version`, `engine-version`, `engine-build`, `storage-config-version`,
   `schema-version`, `schemaRecordId`, `indexMgrRecordId`, `best-effort`) plus the legacy-era
   `default-cluster-id`; none is empty or contains a structural character. A quoted honest name
   has its quotes stripped by P-R1 before the guard sees it. The `:` member of the guard set is
   unreachable for a plain name (the read terminates at the first unquoted `:`) and reachable
   only via a quoted name containing `:` — also not an honest shape. **No counterexample
   exists: the guard cannot reject an honest dump of any version.** ✓
2. **exporter-version read rework (:798-826).** Parent used `readInteger` =
   `Integer.parseInt(value.trim())` (JSONReader.java:85-96); new code is
   `Integer.parseInt(raw)` on the same trimmed raw token — parse-for-parse identical. The only
   change is that a `NumberFormatException` becomes a named `DatabaseImportException`
   (:804-809): both reject, on every version (parent propagated the bare NFE out of
   `importDatabase`). Acceptance-equivalent; message-only change, recorded as such (WI12a). ✓
3. **schema-version capture (:828-838).** Pure capture; consumption identical to parent's
   else-branch `readNext(NEXT_IN_OBJECT)`; judgment gated at :454. No legacy delta. ✓
4. **Known string/number field checks (:850-853).** Capture-only; judged at :483 under v15.
   Legacy consumption unchanged. ✓
5. **Unknown-field collection (:856-859).** Same `readNext(NEXT_IN_OBJECT)` as parent; the
   list is only read under v15 (:490). Legacy delta: none (bounded memory aside — see BG30). ✓
6. **best-effort rework (:839-845) — UNGATED SEMANTIC DELTA → BG29.** Parent:
   `bestEffortDump = jsonReader.readBoolean(...)` — quote-STRIPPING per P-R4, so a hand-edited
   legacy dump carrying `"best-effort": "true"` (string-typed) parsed as `true`, armed the
   marker-keyed ack gate (:514-519, present since Step 5), and was REFUSED absent
   `-acceptBestEffortDump=true`. New: `Boolean.parseBoolean(raw)` on the quote-PRESERVING raw
   token — `parseBoolean("\"true\"")` is `false`, so the gate silently DISARMS; the type
   violation recorded by `checkKnownInfoFieldIsBoolean` (:842, :888-893) is judged only under
   v15 (:483), so on a declared-`<= 14` dump the shape is now silently ACCEPTED where the
   parent commit refused it. Concrete counterexample: take any Step-5-era hardening fixture,
   set `exporter-version` to 14 and `best-effort` to the string `"true"` — parent: rejected
   ("exported in best-effort mode..."); this commit: imports silently. R1's letter is not
   violated (R1 protects honest legacy dumps' ACCEPTANCE, and no honest legacy exporter writes
   the marker at all), but SR3's letter is: a dump "whose info section carries the best-effort
   marker is refused ... REGARDLESS of its declared exporter version" (drafts:683-691) — an
   operator hand-editing the marker as a quoted string plausibly believes the claim is
   declared, and the direction of the drift is fail-OPEN on exactly the hand-edited inputs SR3
   rules must fail closed. It is also an unrecorded ungated behavior change — the same class
   the Step-5 reviews flagged (BG23/CQ26). Remedy sketch: judge the boolean violation ungated
   (marker-keyed, like the gate it guards), or treat any non-`false` best-effort token as
   arming the gate. **should-fix.**

**Alternative hypothesis for BG29:** is the quoted-marker shape perhaps unreachable because
type-violation rejection catches it first? Only under v15 (:454 gate) — under a declared 14 the
violation list is never judged; traced above. Is `Boolean.parseBoolean` case-insensitivity a
second drift? No: bare `True`/`TRUE` still parse `true` on both sides (parent stripped no
quotes there either) — only the QUOTED variants flipped.

**Verdict:** the sanctioned guard is honest-dump-safe (justified above); one unsanctioned
ungated delta found (BG29, should-fix); everything else v15-gated or acceptance-equivalent.

## Criterion 4 — `MIN_IMPORTABLE_SCHEMA_VERSION = 6`

**Decision criteria:** the constant's value matches actual schema-version history; the range
check's logic and typing are sound; the "one-constant bump" story holds.

**Premises and trace:**
1. `SchemaShared.CURRENT_VERSION_NUMBER = 6` (SchemaShared.java:71); the class comment
   (:66-70) records that version 6 IS the per-class-record format this track migrates to, and
   versions 4/5 (the embedded-set forms, :72-74) are reject-and-redirect at open time
   (`fromStream` :895-904) — they cannot even be opened, only exported by OLD binaries, whose
   dumps declare `exporter-version <= 14` and therefore never reach the range check.
2. The only honest producer of a v15 dump is this codebase's exporter, which writes
   `schema-version = SchemaShared.CURRENT_VERSION_NUMBER` (DatabaseExport.java:525) = 6. So
   the only honest (exporter, schema) pair is (15, 6), and `MIN = CURRENT = 6` accepts exactly
   it: the range rejects nothing honest and accepts nothing the importer cannot parse.
3. Future bump: when CURRENT becomes 7, `6..7` needs only the upper constant to move (it is
   read live from SchemaShared :459, :472) — MIN moves independently only when 6-dumps become
   unimportable. The "one-constant bump" claim (:167-171 javadoc) is accurate.
4. Typing: `declaredSchemaVersion` is a `long` (:176) parsed via `Long.parseLong` (:833) —
   comparisons against the int constants promote to long (:471-472); a declared value beyond
   long range falls to `malformedSchemaVersionRaw` (:836) and is rejected as unparseable. No
   overflow path exists.

**Alternative hypothesis:** should MIN be 4 or 5 (importing dumps OF v4/v5 databases)? No — a
v4/v5 database can only be exported by pre-bump binaries producing `<= 14` dumps, which bypass
the check entirely (the lenient migration vehicle, R1). A v15 dump claiming schema-version 4
would be dishonest by construction; rejecting it is correct.

**Verdict:** NULL — no finding. Constant, range logic, and bump story all verified correct.

## Criterion 5 — importInfo rework quality

**Decision criteria:** readability, duplication, error handling, null safety of the new state
fields and collection logic; hidden liveness/resource hazards along changed paths.

**Positives (checked):** the switch-shaped `importInfo` is a clear improvement over the parent's
if/else chain; the capture/judge split is documented at both ends with ruling citations; the
three type-check helpers (:872-893) are small and single-purpose; `supportedRange` is built once
(:458); `malformedSchemaVersionRaw`-before-`schemaVersionDeclared` ordering (:460-470) makes
duplicate-field ambiguity fail-closed; state fields carry javadoc including the
"meaningful only when declared" caveat (:175-185); raw-token quote preservation is explained
where it matters (:846-849).

**Findings:**

1. **BG30 (should-fix) — truncated-JSON EOF turns the importInfo loop into an unbounded-memory
   spin.** The loop `while (jsonReader.lastChar() != '}')` (:782) has no EOF guard. Per P-R5,
   on clean EOF every `readString`/`readNext` returns immediately with STALE `value` and
   `lastCharacter`. Counterexample, traced: a dump that is a VALID gzip of TRUNCATED JSON —
   `{"info":{"name":"x",` then EOF (producible by gzipping a truncated export; also reachable
   as a plain-JSON legacy dump truncated mid-info, and via the InputStream ctor). After the
   `"x"` value read, `lastChar` is `,`; the next `readString(FIELD_ASSIGNMENT)` hits
   `!in.ready()` and returns the stale token, which strips to fieldName `x`; the guard passes,
   the `default` arm runs `unknownInfoFields.add("x")` (:857) and a stale `readNext`; nothing
   ever changes `lastChar` — the loop spins forever, and NEW in this commit, the
   `unknownInfoFields` list grows without bound until OOM. At parent the same input pure-spun
   (fieldName `x` fell into the value-skipping else branch), so the HANG is pre-existing
   Step-5/legacy behavior — but the unbounded allocation is this diff's addition, and the
   track's contract is "fails loudly rather than silently": a hang/OOM on damaged input is
   neither. Remedy sketch: make the loop condition also require `jsonReader.hasNext()` and
   treat EOF-inside-info as a dangling-field rejection (consistent with FM-M10). Filed BG30.
2. **CQ29 (suggestion) — duplicate `schema-version` field is last-wins, unlike the CS63
   exporter-version latch.** `"schema-version":5,"schema-version":6` inside one info section
   silently judges only the 6 (:832-834 overwrites). Unexploitable today (only 6 is in range,
   so any accepted pair is 6,6) and the malformed-first ordering already catches the
   malformed-then-valid shape; but when the range widens the asymmetry becomes a small
   tamper-tolerance. A latch mirroring :817-823 would be two lines.
3. **CQ31 (suggestion) — array-valued unknown info field is rejected with the misleading
   "dangling or malformed field" message instead of being tolerated.** Per P-R2 the reader
   splits `"future": [1,2]` at the array's inner comma; the next field-name read returns
   `2],"x` (structural chars) and the :792 guard fires. Q-M2(4)'s letter says unknown fields
   are tolerated; this value SHAPE is not. At parent the same input desynced into parse chaos,
   so the commit strictly improves it (clean, pre-mutation, fail-closed) and no honest
   exporter of any version writes array-valued info fields — acceptance unchanged. Worth a
   comment on the guard (it also catches value-shape desyncs) or a message tweak; not a bug.
4. **CQ32 (suggestion) — declared `exporter-version: -1` collides with the undeclared
   sentinel** (:113): pre-flight is skipped (:333) and the dump is rejected by the SR2 arms
   with the factually wrong "without declaring an exporter version" wording. Fail-closed and
   dishonest-input-only, and the assignment shape predates this commit (parent :718), so
   suggestion-grade: reject negative declared versions at parse alongside WI12a.

**Null-safety sweep:** `raw` cannot be null (`getValue()` is non-null after the BEGIN_OBJECT
read; the stale-EOF case returns the previous non-null token — the BG30 path); the new lists
are final-initialized (:181-185); `malformedSchemaVersionRaw` is null-checked before use
(:460). No NPE path found.

**Verdict:** quality good overall; BG30 should-fix; CQ29/CQ31/CQ32 suggestions.

## Criterion 6 — Test quality (12 new tests)

**Decision criteria:** (a) each test discriminates — a plausible regression of its target arm
turns it red; (b) the two non-red-first tests are identified and their status justified; (c) the
rehearsal meaningfully pins migration fidelity, with the DatabaseCompare gap characterized;
(d) fixtures are sound; (e) no overlap/contradiction with the 21 `DatabaseImportHardeningTest`
tests.

**(a) Discrimination, test-by-test (all 12 checked):**

| Test | Kills which regression | Verdict |
|---|---|---|
| `v16DumpIsRejectedWithRedirectNamingBothVersions` | redirect arm deleted → v16 imports silently → assertNotNull fails; message drift → fragment asserts fail | ✓ existence; ✗ ordering (TQ29) |
| `schemaVersionMissingIsRejected` | :466 arm deleted → silent import | ✓ |
| `schemaVersionMalformedIsRejected` | :460 arm or the malformed capture deleted | ✓ |
| `schemaVersionBelowRangeIsRejected` / `AboveRange` | :471 bounds each direction (5 and 7 against 6..6) | ✓ |
| `malformedExporterVersionIsRejectedFailClosed` | :804 named rejection reverted to bare NFE → fragment assert fails | ✓ |
| `knownOptionalInfoFieldWrongTypeIsRejected` | string check or :483 judgment deleted (`name: 42`) | ✓ |
| `unknownInfoFieldIsToleratedAndLogged` | tolerance broken → import fails; logging (:490) deleted → listener assert fails; ALSO pins the 15-boundary accept path | ✓ |
| `danglingInfoFieldIsRejected` | guard (:792) deleted → at best post-preamble desync → `assertTargetUnmutated` fails (PreFlightMarker dropped by the preamble), at worst silent import → assertNotNull fails | ✓ |
| `v14DumpWithAlienSchemaVersionStaysLenient` | v15 gate (:454) removed → 99 out-of-range rejects a declared-14 dump → test fails; the differential with `AboveRange` proves the gating | ✓ |
| `endToEndMigrationRehearsalPreservesLogicalContent` | silent record/link/blob/index/schema loss | ✓ (gaps below) |
| `operatorMigrationProcedureDocExistsWithMandatedContent` | page or mandated phrase or README index removed | ✓ |

Every rejection test also asserts `assertTargetUnmutated` (PreFlightMarker survives, Matrix
absent) — pinning the CS38/SR1 pre-mutation contract per pin #15. ✓

**(b) The two non-red-first tests** (Episodes: "ten red-first ... Green-at-HEAD pins"):
`v14DumpWithAlienSchemaVersionStaysLenient` and the rehearsal. Both are
acceptance-PRESERVATION pins — by construction they cannot be red at HEAD (they assert
behavior that already held); their value is against future over-gating/fidelity regressions,
and the v14 pin's red-capability is demonstrated indirectly by its rejected v15 twin
(`schemaVersionAboveRangeIsRejected`). This matches the plan's red-first policy, which binds
defect pins, not preservation pins. Acceptable; correctly recorded in the Episodes entry.
(Two further pins — dangling, malformed exporter-version — were loud-at-HEAD but red on the
unmutated/message half respectively; the episode records this accurately.)

**(c) Rehearsal vs DatabaseCompare-level equivalence (pin #12 substitution):** the substitution
rationale is sound (the literal comparison is id-keyed; `DbImportExportTest` is `@Disabled`
for the same structural reason) and is honestly STOP-reported as a blocked letter. What the
logical-equivalence rehearsal would MISS that DatabaseCompare would catch:
- **Property metadata:** only `getProperty("name") != null` is asserted — a type regression
  (STRING dropped to ANY on import) passes. Cheap to add.
- **Index CONTENT:** only `getIndex("Person.name") != null` — an index recreated but empty or
  unpopulated passes; DatabaseCompare compares entries. A `select from Person where name =
  'alice'` (index-served) or an index-size assert would close most of this.
- **Field-complete record equality:** Note's fields and the two links are checked; Person
  records are only reached THROUGH the links (name field) — an extra/lost sibling field on
  Person, or content drift in untouched classes (security records), passes.
- **Whole-database sweep:** unlisted classes/records outside the fixture are uncompared.
Filed as TQ31 (suggestion): add the property-type and index-content asserts; the rest is
inherent to the blocked letter and correctly escalated to the orchestrator.

**(d) Fixture soundness:** `mutateDump` round-trips through Jackson `ObjectNode`
(field-order-preserving LinkedHashMap; fixture values are ints/strings so re-serialization is
lossless) and re-gzips with a standard single-member `GZIPOutputStream` — passes the Step-5
whole-stream validation; manifest totals untouched by info-only mutations, so counts still
match. The v14 fixture removes the manifest — necessary, since a declared-14 dump with a
`manifest` tag hits the version-gated unsupported-tag rejection (:380-385); an explanatory
comment would help (folded into TQ32). The dangling splice targets `indexMgrRecordId` — the
last info field of a non-best-effort dump, so the splice lands exactly at the mid-write crash
position; the applied-splice guard assert protects against exporter field renames silently
vacating the test. Per-method dump directories (`dumpDirectory()`) prevent cross-test clashes.
The doc test resolves `Path.of("..", "docs")` — correct under surefire (CWD = `core/`), fails
(with a clear message) when run from the repo root, e.g. some IDE configs — TQ32 nit.

**(e) Interaction with the 21 hardening tests:** no name or scenario overlap — the hardening
class owns structural/framing/manifest/SR2/ack-gate arms, the matrix class owns info-field
cells; the two SR2 arms cited by the matrix spec live in the hardening class
(`undeclaredExporterVersionIsRejectedBeforeMutation`, `versionlessInfoOnlyDumpIsRejectedAtEndOfStream`)
and are not duplicated. Contradiction sweep against the new matrix: hardening fixtures write
`best-effort` as a bare boolean (:433, :481 — unaffected by the boolean type check), the
versionless info-only dump's `name` is a valid string (passes the new string check before the
SR2 EOF rejection), v14 fixtures (:198, :413) bypass all judgments, and the Step-5 v15
fixtures come from the real exporter, so the now-mandatory schema-version is present.
Episode-recorded 146-green battery is consistent with this trace. One quality note: the matrix
class re-implements `gunzip`/`gzipTo`/`mutateDump`/`importExpectingRejection`/`dumpDirectory`
verbatim from the hardening class — extractable to a shared test fixture helper (folded into
TQ32).

**Missing-cell sweep (test side):** large/beyond-int/negative exporter versions untested;
legacy-path gating of TYPE violations untested (a v14 dump with `name: 42` — the R1 gate of
:483 — is only inferred from the schema-version cell); unknown-field NON-logging under v14
untested. Filed TQ30 (suggestion).

**Verdict:** strong, discriminating suite; TQ29 should-fix (ordering unpinned), TQ30/TQ31/TQ32
suggestions.

## Findings ledger

| ID | Severity | Location | Summary |
|---|---|---|---|
| BG29 | should-fix | DatabaseImport.java:839-845 + :483/:514 | Quoted best-effort marker (`"true"`) on a declared-`<= 14` dump now silently disarms the SR3 marker-keyed ack gate (parent refused it); type violation judged v15-only, so fail-open on the legacy path |
| BG30 | should-fix | DatabaseImport.java:782,:857 | importInfo loop has no EOF guard: valid-gzip-of-truncated-JSON (or truncated plain-JSON legacy dump) spins forever on stale reader state; new in this diff, `unknownInfoFields.add` per spin → unbounded memory/OOM (hang itself pre-existing) |
| CQ29 | suggestion | DatabaseImport.java:828-838 | Duplicate `schema-version` field is last-wins, unlike the CS63 exporter-version latch; benign at 6..6, tamper-tolerant once the range widens |
| CQ30 | suggestion | DatabaseImport.java:804-809 | WI12a unparseable-exporter-version message names the raw value but not the supported version(s); Q-M2 tail asks messages to name declared vs supported |
| CQ31 | suggestion | DatabaseImport.java:792-796 | Array-valued unknown info field is rejected as "dangling or malformed field" (reader splits arrays at commas) — Q-M2(4) tolerance letter unmet for that value shape, message misleading; strictly better than the parent's desync, acceptance unchanged |
| CQ32 | suggestion | DatabaseImport.java:823/:113 | Declared `exporter-version: -1` collides with the undeclared sentinel → rejected with the wrong "without declaring" wording (fail-closed; pre-existing Step-5 shape) |
| TQ29 | should-fix | DatabaseImportInfoMatrixTest.java (v16 test) | Redirect ORDERING unpinned: reordering the redirect below the v15 arms keeps all 33 tests green while resurrecting CQ24's wrong "v15" wording for v16 dumps — add a v16+missing-schema-version fixture asserting the redirect message |
| TQ30 | suggestion | DatabaseImportInfoMatrixTest.java | Untested cells: exporter version 17/MAX_VALUE redirect, beyond-int "unparseable", negative versions; legacy gating of type violations (v14 + `name: 42` accepted); v14 unknown-field non-logging |
| TQ31 | suggestion | DatabaseImportInfoMatrixTest.java (rehearsal) | Rehearsal misses property TYPE and index CONTENT (existence-only asserts) plus field-complete equality outside the fixture trio — the two cheap asserts are worth adding; the rest is inherent to the blocked pin-#12 letter |
| TQ32 | suggestion | DatabaseImportInfoMatrixTest.java | Helper duplication with DatabaseImportHardeningTest (gunzip/gzipTo/mutateDump/importExpectingRejection); doc test's `../docs` CWD assumption breaks non-surefire runs; v14 fixture's manifest removal uncommented |

**Null verdicts:** Criterion 4 (`MIN_IMPORTABLE_SCHEMA_VERSION = 6`) — no finding: constant,
range logic, long typing, and the one-constant-bump story all verified against
SchemaShared.java:66-74/:895-904 and DatabaseExport.java:525. Criterion 1 carries no finding of
its own (matrix complete, no missing/invented row) — its two deviations are filed under
Criteria 3 (BG29) and 2 (CQ30).
