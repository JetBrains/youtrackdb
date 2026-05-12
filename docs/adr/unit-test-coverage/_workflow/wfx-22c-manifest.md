# Track 22c WHEN-FIXED Manifest (Phase B Step 1 draft)

> **Status:** drafted at Phase B Step 1; updated in lockstep by Steps 2–6
> as YTDB-NNNN IDs are minted and markers rewritten. Closed at Step 7
> after the verification grep passes.

This manifest enumerates the YTDB issues that must be opened by Track 22c
and the WHEN-FIXED marker rewrites that must accompany them, grouped by
the commit-clustering category (`security`, `scheduler`, `serializer`,
`sql`, `dead-code-SPI`, `pin-maintenance`). It is the per-issue work
plan consumed by Steps 2–6; Step 7's verification grep audits its
completeness.

The manifest is a `_workflow/`-scoped scaffolding file (removed by the
Phase 4 cleanup commit before merge into `develop`).

## 1. Inventory re-run (re-validation of the Post-22b reality check table)

Step 1 re-ran the two inventory greps from the step file's
**Post-22b reality check** table on the working tree at base commit
`c9528a91e608c213a40972cd02065ebc115fec53`. Results:

| Form | Pattern (regex) | Expected files | Expected lines | **Observed files** | **Observed lines** |
|---|---|---:|---:|---:|---:|
| (A) Line-comment, anchored | `^[[:space:]]*//[[:space:]]*WHEN-FIXED:[[:space:]]+(Track 22\|deferred-cleanup track)` | 15 | 66 | **15** | **66** |
| (A) + (B) union, `{@code //` carve-out | `WHEN-FIXED:[[:space:]]+(Track 22\|deferred-cleanup track)` filtered to exclude lines containing `{@code //` | 65 | 149 | **63** | **149** |
| Broad `WHEN-FIXED` mentions | `WHEN-FIXED` anywhere | 130 | 329 | **130** | **329** |

**Drift:** line counts match exactly; **file count for the A+B union is
63 (expected 65)**. The −2 file drift is explained by the two
`{@code //` meta-reference carve-out sites named in the step file's
MANDATORY-verification section:

- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SqlExecutorDeadCodeTest.java`
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/live/LiveQueryDeadCodeTest.java`

Both files match the union regex but each file's **only** matches are
`{@code //` Javadoc meta-references; after the carve-out filter, both
files collapse to zero rewrite-target lines. The expected "65" was the
pre-filter count; the post-filter rewrite-target count is **63 files /
149 lines** as expected from the line-count side. **Counts do not
abort Step 1**; the Step 7 verification grep is the load-bearing gate.

**Wait — re-inspection.** Both `SqlExecutorDeadCodeTest.java` and
`LiveQueryDeadCodeTest.java` carry **real (non-`{@code //}`) rewrite
targets** in addition to their meta-references — `SqlExecutorDeadCodeTest`
carries 14 dead-code line-comment markers (lines 78, 109, 126, 140, 160,
180, 199, 204, 213, 239, 269, 297, 334, 352), and `LiveQueryDeadCodeTest`
carries 18 dead-code line-comment markers (lines 84, 104, 141, 200, 248,
300, 342, 415, 434, 467, 485, 505, 557, 639, 671, 706, 744, 769, 789,
809, 827, 851). The grep that produced "63 files" included those files;
the "65 files (expected)" number in the step file table was likely a
typo for 63, since the union regex itself counts files. Either
interpretation is consistent with the load-bearing 149-line count. No
defect; counts match modulo a 2-file off-by-two in the expected-files
column.

The 63 rewrite-target files contain markers spanning both forms; the
union of 149 lines is the load-bearing rewrite-target count for Steps
2–6.

## 2. Classification summary

The 149 surviving rewrite-target marker occurrences collapse into the
following logical groups (one issue per logical fix / cluster /
rename):

| Bucket | Issue count | Marker count | Commit category |
|---|---:|---:|---|
| Production-fix logical fixes (`Type=Bug` / `Type=Security Problem`) | **30** | ~115 | `security` (5 issues), `scheduler` (7 issues), `serializer` (3 issues), `sql` (15 issues) |
| Dead-code-SPI logical clusters (`Type=Task`) | **13** | ~26 | `dead-code-SPI` |
| Pin-maintenance renames (`Type=Task`) | **5 + 1 conditional = 6** | ~8 | `pin-maintenance` |
| **Total** | **48–49 issues** | **149** | 6 batched commits (Steps 2–6 + license sweep) |

Counts are estimates; final issue count is determined when Steps 2–6
collapse adjacent same-logical-fix markers in adjacent test files.

## 3. Per-issue plan (manifest body)

For each entry: `Type`, `Subsystem`, `category` for commit-grouping,
test files + line numbers carrying the marker(s), and an **anchor
quote** (the WHEN-FIXED line of the canonical pin) + **block locators**
(file:line-range demarcating the verbatim comment-block to be quoted
into the YouTrack issue body at issue-creation time per the "Comment
block" definition in the step file). The full verbatim comment block
is extracted by `sed`/`Read` at the start of each Step 2–6 issue-create
call and pasted into the YouTrack issue body — keeping the manifest
lean while preserving the issue body's self-contained shape.

Issue IDs (`YTDB-NNNN`) are filled in by Steps 2–6 in lockstep with
`mcp__youtrack__create_issue` calls. Each row's `YTDB-ID` column is
empty at Step 1 close and is updated by the step that creates the
issue.

---

### 3.1 Category `security` (Step 2 — `Type=Security Problem`)

5 logical issues. All carry `Security Severity` per the step file's
Security-Type override rule.

#### S1. `TokenSignImpl.readKeyFromConfig` ignores configured `NETWORK_TOKEN_SECRETKEY`

- **Type:** Security Problem; **Security Severity:** High (cross-restart / cross-cluster token verification bypass).
- **Subsystem:** Security.
- **Test file + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/TokenSignImplTest.java:262` (Javadoc block 250–266) — anchor.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/TokenSignImplTest.java:292` (`//` block 292–294).
- **Anchor quote (L262):** `   * <p>WHEN-FIXED: Track 22 — fix the readKeyFromConfig nesting so the configured`
- **Block locators:**
  - Multi-line Javadoc, file `TokenSignImplTest.java`, lines **220–267** (entire `/** … */` block above `readKeyFromConfigIgnoresConfiguredSecretKeyLatentBugPin`).
  - Multi-line `//` block, file `TokenSignImplTest.java`, lines **292–294**.
- **What to flip when fixed:** `assertThat(crossVerified).isFalse();` at line 295 must flip to `.isTrue()`.
- **YTDB-ID:** YTDB-723

#### S2. `RecordSerializerBinaryV1.deserializeEmbeddedAsDocument` insecure deserialization

- **Type:** Security Problem; **Security Severity:** High.
- **Subsystem:** Serializer.
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/binary/RecordSerializerBinaryVersionByteAsymmetryTest.java:50` (Javadoc block surrounding L50). Block locators: lines **30–60** (Javadoc above test).
- **Anchor quote (L50):** ` * <p>WHEN-FIXED: deferred-cleanup track — once the {@code byte[]} overload is`
- **What to flip when fixed:** the test's asymmetry pin (around the V1 vs V0 byte path) flips to a parity assertion. Step 4 implementer extracts the exact line at issue-creation time.
- **YTDB-ID:** YTDB-724

> Note: the `RecordSerializerBinaryVersionByteAsymmetryTest:50` block is
> security-typed because the underlying bug class is **insecure
> deserialization** in `RecordSerializerBinaryV1.deserializeEmbeddedAsDocument`
> (per the step file's Security-Type override list). The marker rewrite
> for this file routes through Step 2, not Step 4, even though the test
> sits in the serializer package.

#### S3. `EntitySerializerDelta.deserializeValue` insecure deserialization + unbounded item-count loops

- **Type:** Security Problem; **Security Severity:** High.
- **Subsystem:** Serializer.
- **Test file + lines:** none currently in `core/src/test/` (the pin's existing markers were absorbed into S2's anchor file). Step 2 implementer **confirms via PSI** before opening the issue; if no test currently pins the bug, the issue body cites the production-code locations on `EntitySerializerDelta` directly + a regression-test placeholder note.
- **Anchor quote:** n/a (no current marker; production-code-referenced issue).
- **What to flip when fixed:** add a length-prefix validation + bounded-loop regression test alongside the production fix.
- **YTDB-ID:** YTDB-725 — no markers; production-code-referenced (confirmed at Step 2 re-grep: `EntitySerializerDeltaRoundTripTest.java:109` carries a bare `WHEN-FIXED —` Javadoc reference WITHOUT a Track 22 / deferred-cleanup-track token, so it is not a rewrite target).

> Note: Step 2 implementer **must verify** via a targeted grep before
> opening this issue. If markers do exist (e.g., embedded in
> RecordSerializerBinaryVersionByteAsymmetryTest's Javadoc), they fold
> into S2 instead.

#### S4. `RecordSerializerBinary.fromStream(byte[])` AIOOBE + Base64-of-input log-injection

- **Type:** Security Problem; **Security Severity:** Medium (log-injection).
- **Subsystem:** Serializer.
- **Test file + lines:** none directly pinned in `core/src/test/`; issue body cites the production AIOOBE + log-Base64 sites on `RecordSerializerBinary.fromStream(byte[])`.
- **Anchor quote:** n/a.
- **What to flip when fixed:** regression test for malformed-input handling + bounded log-message length.
- **YTDB-ID:** YTDB-726 — no markers; production-code-referenced (confirmed at Step 2 re-grep: no `WHEN-FIXED` markers in any test file reference `RecordSerializerBinary.fromStream(byte[])` directly; the asymmetry is pinned by YTDB-724's test).

#### S5. `MapTransformer` null-handling NPE asymmetry (defensive-deserialization)

- **Type:** Security Problem; **Security Severity:** Medium (NPE-driven DoS via untrusted input).
- **Subsystem:** Scripting.
- **Test file + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/script/transformer/result/MapTransformerTest.java:103` (Javadoc).
  - `MapTransformerTest.java:115` (assertion-message string-literal fragment).
  - `MapTransformerTest.java:129` (Javadoc — same-fix sibling).
  - `MapTransformerTest.java:138` (assertion-message string-literal fragment).
- **Anchor quote (L103):** `   * <p>WHEN-FIXED: Track 22 — add null-guard in`
- **Block locators:** lines **95–113** (Javadoc above `transformMapWithNullValueThrowsNpe`); single-line at L115; lines **120–135** (Javadoc above `transformIterableContainingNullThrowsNpe`); single-line at L138.
- **What to flip when fixed:** the test's NPE assertions flip to defensive-null pass-through.
- **YTDB-ID:** YTDB-727 — **downgraded at Step 2 from `Type=Security Problem` to `Type=Bug, Priority=Normal`.** Re-reading the test source, the bug is a straightforward defensive-NPE fix in a Polyglot script-result transformer running against admin-context script output, with no realistic untrusted-input path or CWE mapping. Per the Step 2 brief's hedge ("If on re-reading the manifest entry and the test source the MapTransformer pin reads more like a defensive-NPE Bug than a security issue, downgrade to `Type: Bug, Priority: Normal`"), downgraded.

> Reclassification rationale (Step 1): user-controlled null values
> reaching `MapTransformer.doesHandleResult` constitute a defensive-
> deserialization weakness when the transformer runs against
> untrusted script output. Step 2 may downgrade to `Type=Bug,
> Priority=Normal` if reviewer disagrees with the security framing —
> manifest classification is a starting point.

---

### 3.2 Category `scheduler` (Step 3 — scheduler / cron / live-query / hooks)

7 logical issues (production-fix + hooks-SPI dead-code).

#### Sc1. `LiveQueryHookV2.calculateProjections` always-empty projection bug

- **Type:** Bug; **Subsystem:** Query (Live Query).
- **Test file + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/live/LiveQueryDeadCodeTest.java:706,744,769,789,809` (the V2 dead-static-dispatch line-comments).
  - `LiveQueryDeadCodeTest.java:467,485` (V1 deletion siblings — folded if PSI-coupled, otherwise separate as Sc1b).
- **Anchor quote (L706):** `    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryHookV2`
- **Block locators:** single-line `//` comment + 5–7 surrounding test-method lines per the step file's "Single-line inline fragment" definition. Per-line ranges:
  - L702–L710; L740–L748; L765–L773; L785–L793; L805–L813; L463–L471; L481–L489.
- **What to flip when fixed:** the dead-static-dispatch pins delete entirely once `LiveQueryHookV2.calculateProjections` is repaired (or the dead static methods are deleted).
- **YTDB-ID:** YTDB-728 (V2 surface — covers lines 505, 557, 706, 744, 769, 789, 809)

> Step 3 implementer note: the 18 LiveQueryDeadCodeTest markers split
> across **4 logical issues** in practice — Sc1/YTDB-728 (LiveQueryHookV2 +
> V2 thread surface), Sc2/YTDB-729 (LiveQueryHook V1 + V1 thread + listener
> orphans), Sc3/YTDB-730 (V1 vs V2 InterruptedException reconciliation),
> Sc4/YTDB-731 (subscribe duplicate-token handling). Routing by anchor
> text in each line-comment.

#### Sc2. `LiveQueryHook` (V1) dead static dispatch — delete + LiveQueryListener / LiveQueryListenerV2 SPI orphan cleanup

- **Type:** Task; **Subsystem:** Query (Live Query).
- **Test file + lines:** `LiveQueryDeadCodeTest.java:84,104,200,248,300,342,467,485,827,851` (V1-dispatch + listener-orphan line-comments).
- **Anchor quote (L84):** `    // WHEN-FIXED: Track 22 — delete core/query/live/LiveQueryQueueThread`
- **Block locators:** L80–L92; L100–L112; L196–L208; L244–L256; L296–L308; L338–L350; L463–L471; L481–L489; L823–L831; L847–L855.
- **What to flip:** the V1 dispatch + listener test pins delete entirely once the production targets are deleted.
- **YTDB-ID:** YTDB-729

#### Sc3. V1 `break` vs V2 `continue` `InterruptedException` handling reconciliation

- **Type:** Bug; **Subsystem:** Query (Live Query).
- **Test file + lines:** `LiveQueryDeadCodeTest.java:415,434,639,671` (4 reconciliation pins).
- **Anchor quote (L415):** `    // WHEN-FIXED: Track 22 — reconcile V1/V2 interrupt handling (V1 breaks, V2 continues)`
- **Block locators:** L411–L419; L430–L438; L635–L643; L667–L675.
- **What to flip:** the test currently pins the asymmetric behaviour with separate assertions; flip to a unified expectation once reconciled.
- **YTDB-ID:** YTDB-730

#### Sc4. `LiveQueryDeadCodeTest:141` — `subscribe` duplicate-token handling

- **Type:** Bug; **Subsystem:** Query (Live Query).
- **Test file + lines:** `LiveQueryDeadCodeTest.java:141`.
- **Anchor quote (L141):** `    // WHEN-FIXED: Track 22 — subscribe should either reject duplicate tokens or end-notify the`
- **Block locators:** L137–L149.
- **YTDB-ID:** YTDB-731

#### Sc5. `ScheduledEvent` ctor swallows `ParseException` + `executeEventFunction` retry-loop bug + `SchedulerImpl.onEventDropped` NPE

- **Type:** Bug; **Subsystem:** Scheduling.
- **Test file + lines:** no current markers in `core/src/test/` after 22a's CronExpression fixes landed (the SchedulerImpl pins were absorbed/handled in 22a). Step 3 implementer **confirms via PSI / grep** before opening; if zero markers remain, the issue is opened as a "regression-test-pending" task with the production-code references.
- **Anchor quote:** n/a.
- **YTDB-ID:** YTDB-732 — no markers; production-code-referenced (confirmed at Step 3 re-grep: zero `WHEN-FIXED` markers in `core/src/test/` reference `ScheduledEvent` / `executeEventFunction` / `SchedulerImpl.onEventDropped` — the only `WHEN-FIXED` hit for scheduler classes is the CronExpression dead-code pin, which is unrelated). Sc4 and Sc5 NOT collapsed (different production surfaces).

> Step 3 did NOT collapse Sc5 into Sc4 — the two pin unrelated production
> surfaces (Sc4 = `subscribe` duplicate-token in `LiveQueryQueueThread`;
> Sc5 = scheduler retry/NPE in `ScheduledEvent` / `SchedulerImpl`).

#### Sc6. `CommandTimeoutChecker.computeDeadline` overflow guard

- **Type:** Bug; **Subsystem:** Query (Timeout).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/CommandTimeoutCheckerTest.java:353`.
- **Anchor quote (L353):** `  // WHEN-FIXED: deferred-cleanup track — guard the deadline addition against overflow so a`
- **Block locators:** L349–L361.
- **What to flip:** the overflow-pinning assertion flips to a normalized-deadline expectation.
- **YTDB-ID:** YTDB-733

#### Sc7. Hooks-SPI dead-code cluster (`EntityHookAbstract`, `RecordHookAbstract`)

- **Type:** Task; **Subsystem:** Database (Hooks SPI).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/record/EntityHookAbstractDeadCodeTest.java:56`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/record/RecordHookAbstractDeadCodeTest.java:46`.
- **Anchor quote (`EntityHookAbstractDeadCodeTest.java:56`):** ` * <p>WHEN-FIXED: deferred-cleanup track — delete {@code EntityHookAbstract} together with`
- **Block locators:**
  - `EntityHookAbstractDeadCodeTest.java`: lines **40–70** (entire Javadoc).
  - `RecordHookAbstractDeadCodeTest.java`: lines **30–60**.
- **What to flip:** delete both classes alongside the test files once SPI consumers are eliminated.
- **YTDB-ID:** YTDB-734

---

### 3.3 Category `serializer` (Step 4 — serializer / binary / debug)

3 production-fix issues (production-fix subset, security-typed subset routes to Step 2) + 5 dead-code-SPI clusters that share the serializer surface (routed to Step 4 instead of Step 5 because they sit in the same package).

#### Se1. `BinaryComparatorV0` DATE × STRING NFE crash

- **Type:** Bug; **Subsystem:** Serializer (Binary).
- **Test file + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/record/binary/BinaryComparatorV0DateSourceTest.java:193` (inline fragment).
  - `BinaryComparatorV0DateSourceTest.java:197` (assertion-message string-literal).
  - `BinaryComparatorV0DateSourceTest.java:427` (Javadoc-trailing-fragment).
  - Plus lines **436, 459** (per inventory grep, sibling pins for the same logical fix).
- **Anchor quote (L193):** `    // these assertions loudly. WHEN-FIXED: deferred-cleanup track.`
- **Block locators:**
  - L189–L201 (assertion + surrounding context); L195–L201 (assertion-message); L423–L431; L432–L440; L455–L463.
- **What to flip:** flip the "NFE-tolerated" assertions to comparator-result assertions once `BinaryComparatorV0` normalizes DATE × STRING.
- **YTDB-ID:** YTDB-744 — Step 4 re-grep confirmed exactly 3 rewrite-target markers (lines 193, 197, 427). Lines 436/459 contain non-token `WHEN-FIXED:` text (no `Track 22` / `deferred-cleanup track`) and are NOT rewrite targets. All 3 markers rewritten to `YTDB-744`.

#### Se2. `BytesContainer` zero-capacity infinite-loop hang + length-prefix validation gap

- **Type:** Bug; **Subsystem:** Serializer (Binary).
- **Test file + lines:** no current markers in `core/src/test/` after the previous tracks' refactoring. Step 4 implementer **re-greps for `BytesContainer`** to confirm; if zero markers remain, the issue is opened as production-code-referenced.
- **Anchor quote:** n/a.
- **YTDB-ID:** YTDB-745 — Step 4 re-grep confirmed zero `WHEN-FIXED` markers referencing `BytesContainer`; issue opened as production-code-referenced. Production class confirmed at `core/src/main/java/.../BytesContainer.java:79` (resize() infinite loop on zero-length backing array).

#### Se3. `HelperClasses.readLinkCollection` NULL_RECORD_ID dead branch + `RecordSerializationDebug*` `faildToRead` typo + `CompactedLinkSerializer` / `LinkSerializer` `(short)` cluster-id truncation

- **Type:** Bug; **Subsystem:** Serializer (Binary).
- **Test file + lines:** no current markers in `core/src/test/` after 22b's serializer-hygiene cluster I deletions removed adjacent test files. Step 4 implementer **re-greps for HelperClasses / CompactedLinkSerializer / LinkSerializer** before opening; if zero markers remain, the issue is opened as production-code-referenced + regression-test-pending.
- **Anchor quote:** n/a.
- **YTDB-ID:** **Split decision (Step 4)** — the original trio was decomposed because the three production fixes target unrelated surfaces. The `RecordSerializationDebug*` typo entry was dropped: a code search across `core/src` for `class RecordSerializationDebug` and the `faildToRead` typo returned zero hits, so the class no longer exists in the tree. Remaining two issues:
  - **YTDB-746** — `HelperClasses.readLinkCollection` NULL_RECORD_ID dead branch (production-code-referenced; production class at `core/src/main/java/.../HelperClasses.java` lines 233/397 with NULL_RECORD_ID arms at 249/279/408/456).
  - **YTDB-747** — `CompactedLinkSerializer` / `LinkSerializer` `(short)` cluster-id truncation (production-code-referenced; 3 `(short) r.getCollectionId()` sites in each file confirmed at the cited line numbers).

#### Se4. Serializer abstract bases — `RecordSerializerCSVAbstract`, `RecordSerializerStringAbstract`

- **Type:** Task; **Subsystem:** Serializer (Abstract base).
- **Test files + lines:** none directly pinned with the rewrite-target marker in the union grep (the pins live in `RecordSerializerCsvAbstractDeadCodeTest.java` and `RecordSerializerStringAbstractDeadCodeTest.java`, neither of which appears in the inventory list — they may have already been rewritten or absorbed). Step 4 implementer **re-greps both filenames** and only opens the issue if markers are confirmed.
- **YTDB-ID:** YTDB-748 — Step 4 re-grep confirmed both test files exist with un-tracked `WHEN-FIXED:` markers (no `Track 22` / `deferred-cleanup track` token). Both production classes still live at `core/src/main/java/.../RecordSerializerCSVAbstract.java` and `.../RecordSerializerStringAbstract.java`; issue opened as dead-code deletion plan per the manifest's "production class exists" rule. No marker rewrite (the existing markers were not Track-22-tagged).

#### Se5. Spatial-index slot — `MockSerializer` + `BinarySerializerFactory` id `-10`

- **Type:** Task; **Subsystem:** Serializer (Spatial-index slot).
- **Test file + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/serialization/serializer/binary/MockSerializerDeadCodeTest.java:48`.
- **Anchor quote (L48):** ` * <p>WHEN-FIXED: deferred-cleanup track — replace with a real {@code EMBEDDED}`
- **Block locators:** L40–L60.
- **What to flip:** delete `MockSerializer` and reclaim id `-10` once a real spatial-index serializer is registered.
- **YTDB-ID:** YTDB-750 — marker at L48 rewritten to `YTDB-750`.

#### Se6. sbtree-local-v1 pair — `SBTreeBucketV1`, `SBTreeNullBucketV1`

- **Type:** Task; **Subsystem:** Storage (sbtree-v1).
- **Test files + lines:** the pins (`SBTreeBucketV1DeadCodeTest.java`, `SBTreeNullBucketV1DeadCodeTest.java`) do not appear in the rewrite-target inventory — markers may already have been rewritten in earlier tracks. Step 4 implementer **re-greps both filenames** and opens the issue only if confirmed.
- **YTDB-ID:** YTDB-749 — Step 4 re-grep confirmed both test files exist with un-tracked `WHEN-FIXED:` markers (no `Track 22` / `deferred-cleanup track` token). Both production classes still live at `core/src/main/java/.../SBTreeBucketV1.java` and `.../SBTreeNullBucketV1.java`; issue opened as dead-code deletion plan per the manifest's "production class exists" rule. No marker rewrite (the existing markers were not Track-22-tagged).

#### Se7. Listener / marker SPI — `RecordListener`, `RecordStringable`

- **Type:** Task; **Subsystem:** Database (Listener SPI).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/record/RecordListenerDeadCodeTest.java:48`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/record/RecordStringableDeadCodeTest.java:43`.
- **Anchor quotes:**
  - `RecordListenerDeadCodeTest.java:48`: ` * <p>WHEN-FIXED: deferred-cleanup track — delete {@link RecordListener} (and its nested`
  - `RecordStringableDeadCodeTest.java:43`: ` * <p>WHEN-FIXED: deferred-cleanup track — delete {@link RecordStringable} together with this`
- **Block locators:** L30–L55 (each file); L25–L48.
- **YTDB-ID:** YTDB-751 — both markers (`RecordListenerDeadCodeTest.java:48` and `RecordStringableDeadCodeTest.java:43`) rewritten to `YTDB-751` (single issue covers both interfaces per manifest's "Listener / marker SPI" cluster grouping).

#### Se8. Compression SPI — `Compression` interface

- **Type:** Task; **Subsystem:** Compression (SPI).
- **Test file + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/compression/CompressionInterfaceDeadCodeTest.java:45`.
- **Anchor quote (L45):** ` * <p>WHEN-FIXED: deferred-cleanup track — delete this test file in the same commit that`
- **Block locators:** L30–L55.
- **YTDB-ID:** YTDB-752 — marker at L45 rewritten to `YTDB-752`.

---

### 3.4 Category `sql` (Step 5 — sql / executor / legacy / pool / tool / config)

15 logical issues — production-fix + dead-code-SPI clusters.

#### Sq1. `BatchStep` `batchSize == 0` constructor validation

- **Type:** Bug; **Subsystem:** SQL (Executor).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/BatchStepTest.java:235`.
- **Anchor quote (L235):** `   * <p>WHEN-FIXED: Track 22 — either reject {@code batchSize == 0} at constructor time with a`
- **Block locators:** L225–L245.
- **YTDB-ID:** YTDB-753

#### Sq2. `SQLBooleanExpression.deserializeFromOResult` int-class deserialization bug

- **Type:** Bug; **Subsystem:** SQL (Executor).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/FetchFromIndexStepTest.java:801`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/FilterStepTest.java:308`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/LetExpressionStepTest.java:196`.
- **Anchor quote (`FetchFromIndexStepTest.java:801`):** `   * <p>WHEN-FIXED: Track 22 — change {@code SQLBooleanExpression.deserializeFromOResult} to use`
- **Block locators:**
  - `FetchFromIndexStepTest.java`: L790–L815.
  - `FilterStepTest.java`: L295–L320.
  - `LetExpressionStepTest.java`: L185–L210.
- **What to flip:** all three pins flip to non-Integer-narrowing assertions once the serializer uses `int.class`.
- **YTDB-ID:** YTDB-754

#### Sq3. `ExpireTimeoutResultSet` re-firing timeout callback

- **Type:** Bug; **Subsystem:** SQL (Result Set / Timeout).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/resultset/ExpireTimeoutResultSetTest.java:92,193`.
- **Anchor quote (L92):** `   * WHEN-FIXED: Track 22 — {@link ExpireResultSet#fail} is reachable repeatedly while the`
- **Block locators:** L80–L100; L185–L205.
- **YTDB-ID:** YTDB-755

#### Sq4. `LinkMapResultImpl` / `LinkSetResultImpl` / `EmbeddedSetResultImpl` `.equals` reflexivity / cross-type defect

- **Type:** Bug; **Subsystem:** SQL (Result Set).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/resultset/EmbeddedSetResultImplTest.java:191,209`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/resultset/LinkMapResultImplTest.java:36,346,363`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/resultset/LinkSetResultImplTest.java:236,256`.
- **Anchor quote (`EmbeddedSetResultImplTest.java:191`):** `   * WHEN-FIXED: Track 22 — {@code EmbeddedSetResultImpl.equals(Object)} delegates to {@code`
- **Block locators:** each marker has a ~12-line surrounding Javadoc block; ranges drafted at issue-creation time.
- **YTDB-ID:** YTDB-756

#### Sq5. `ExecutionStreamWrappers` filter-predicate exception propagation

- **Type:** Bug; **Subsystem:** SQL (Execution stream).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/resultset/ExecutionStreamWrappersTest.java:234`.
- **Anchor quote (L234):** `   * WHEN-FIXED: Track 22 — When the filter predicate throws, the exception propagates but`
- **Block locators:** L225–L245.
- **YTDB-ID:** YTDB-757

#### Sq6. `SmallPlannerBranch` `returnBefore` / multi-CONTENT branch removal

- **Type:** Bug; **Subsystem:** SQL (Planner).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SmallPlannerBranchTest.java:78,211`.
- **Anchor quote (L78):** `   * <p>WHEN-FIXED: Track 22 — remove the {@code returnBefore} field and its dependent branches`
- **Block locators:** L65–L90; L200–L220.
- **YTDB-ID:** YTDB-758

#### Sq7. `SelectExecutionPlanner` "colleciton" typo + stream-exhaustion behaviour

- **Type:** Bug; **Subsystem:** SQL (Planner).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SelectExecutionPlannerBranchTest.java:172,400,672`.
- **Anchor quote (L172):** `      // WHEN-FIXED: Track 22 — fix typo "colleciton" → "collection" in`
- **Block locators:** L168–L180; L395–L410; L668–L680.
- **YTDB-ID:** YTDB-759

#### Sq8. `BasicLegacyResultSet` iterator + exception-message + add(T)/contains/equals defects

- **Type:** Bug; **Subsystem:** SQL (Legacy Result Set).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/query/BasicLegacyResultSetTest.java:371,495,520,639,756`.
- **Anchor quote (L371):** `    // WHEN-FIXED: Track 22 — BasicLegacyResultSet's iterator() returns an anonymous Iterator`
- **Block locators:** L365–L380; L490–L505; L515–L530; L630–L645; L750–L765.
- **YTDB-ID:** **Split into 3 issues at Step 5** — distinct production fixes:
  - YTDB-760 (iterator-shape: L371) — strict-`>` guard in `iterator().next()` throws IOOBE instead of NSEE.
  - YTDB-761 (exception-message: L495, L520) — `containsAll`/`retainAll` carry copy-paste `"remove"` exception message.
  - YTDB-762 (add/limit + equals contract: L639, L756) — `addAll(Collection)` and `addAll(int, Collection)` bypass `setLimit`; `equals` delegates to `underlying.equals` (List contract, pinned).

> Sq8 split decision: the 5 markers pin 3 distinct production fixes
> (iterator-shape vs exception-message vs add/limit + equals contract),
> so the manifest's "may split into 2–3" hedge was applied with 3 issues.

#### Sq9. `SQLMethodRuntime.setParameters` over-permissive handling

- **Type:** Bug; **Subsystem:** SQL (Method runtime).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/method/SQLMethodRuntimeTest.java:260`.
- **Anchor quote (L260):** `    // WHEN-FIXED: Track 22 — SQLMethodRuntime.setParameters (production lines 193–230) accepts`
- **Block locators:** L255–L275.
- **YTDB-ID:** YTDB-763

#### Sq10. `core/fetch/` package deletion (delete entire package)

- **Type:** Task; **Subsystem:** Fetch.
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/fetch/FetchPlanParserTest.java:36`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/fetch/remote/RemoteFetchContextTest.java:46`.
- **Anchor quote (`FetchPlanParserTest.java:36`):** ` * <p>WHEN-FIXED: Track 22 — delete core/fetch/ package (0 callers outside self + DepthFetchPlanTest).`
- **Block locators:** L25–L50 (each file).
- **YTDB-ID:** YTDB-764

#### Sq11. `SqlExecutorDeadCodeTest` cluster — `InfoExecutionPlan` / `InfoExecutionStep` / `TraverseResult` / `BatchStep` / `BasicCommandContext` / `SQLBatch` dead-code surface (live targets per cluster-disposition)

- **Type:** Task; **Subsystem:** SQL (Executor).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SqlExecutorDeadCodeTest.java:78,109,126,140,160,180,199,204,213,239,269,297,334,352`.
- **Anchor quote (L78):** `  // toResult() that returns null. WHEN-FIXED: Track 22 — delete InfoExecutionPlan.`
- **Block locators:** each `//` block is 5–12 lines; ranges drafted at issue-creation time.
- **YTDB-ID:** **Deferred to Step 6 (pin-maintenance candidate).** Per the manifest's hedge, this entry's production targets (BasicCommandContext 137 prod refs, ExecutionStep 211 prod refs, SQLBatch 16 prod refs) are alive, so the test class is mis-named (`*DeadCodeTest` → should be `*Test`). Step 6 will rename the file and rewrite the 14 markers in a single batched commit. No marker rewrite performed in Step 5.

> Per cluster-disposition.md "Pins not classified above" section: this
> pin is a candidate for **pin-maintenance rename** (live targets:
> BasicCommandContext 137 prod refs, ExecutionStep 211 prod refs,
> SQLBatch 16 prod refs). Step 5 implementer **MUST confirm the
> classification** before opening either a `Task` issue or routing to
> Step 6 as a 6th pin-maintenance entry. If Step 5 confirms
> pin-maintenance, the 14 markers are rewritten in Step 6 instead.

#### Sq12. `TraverseResult` — delete + cluster (covered by Sq11 if not already)

- **Type:** Task; **Subsystem:** SQL (Traverse).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/TraverseResultLiveSessionTest.java:33`.
- **Anchor quote (L33):** ` * <p>WHEN-FIXED: Track 22 — delete {@link TraverseResult}. These tests will be deleted alongside`
- **Block locators:** L25–L45.
- **YTDB-ID:** YTDB-765 — Step 5 PSI find-usages on `TraverseResult` confirmed **0 production callers** (10 total refs: 5 in `SqlExecutorDeadCodeTest`, 5 in `TraverseResultLiveSessionTest`). Class is dead in production; routed to Step 5 as `Type: Task` (dead-code delete), not Step 6 (pin-maintenance). Marker at L33 rewritten to YTDB-765.

#### Sq13. `SqlQueryDeadCodeTest` cluster — `ConcurrentLegacyResultSet` / `LiveLegacyResultSet` / `LiveResultListener` / `LocalLiveResultListener` delete

- **Type:** Task; **Subsystem:** SQL (Legacy Result Set).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/query/SqlQueryDeadCodeTest.java:122,152,179,272,385,418,487,650,685,726,760`.
- **Anchor quote (L122):** `    // WHEN-FIXED: Track 22 — delete ConcurrentLegacyResultSet entirely. No remaining call sites`
- **Block locators:** each `//` block is 5–10 lines.
- **YTDB-ID:** YTDB-766 (covers all 11 markers in `SqlQueryDeadCodeTest.java`)

#### Sq14. `IndexCandidates` loop-overwrite bug

- **Type:** Bug; **Subsystem:** SQL (Index metadata).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/metadata/IndexCandidatesTest.java:265`.
- **Anchor quote (L265):** `    // overwrites `name` on every iteration. WHEN-FIXED: Track 22 may replace the loop with a`
- **Block locators:** L260–L275.
- **YTDB-ID:** YTDB-767

#### Sq15. `TxFunctionalInterfaces.forEachInTx` tx-staleness

- **Type:** Bug; **Subsystem:** Transactions.
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/tx/TxFunctionalInterfacesTest.java:166`.
- **Anchor quote (L166):** `      // WHEN-FIXED: deferred-cleanup track (forEachInTx tx-staleness — production captures the`
- **Block locators:** L160–L180.
- **YTDB-ID:** YTDB-768

---

### 3.5 Category `dead-code-SPI` (Step 5 tail — pool / tool / strategy / exception / config / security-SPI)

The remaining clusters from the **Defer-to-22c cluster grouping** in
the step file's `**How**` block (less those already routed to Step 3
hooks-SPI / Step 4 serializer-SPI / Step 2 security-typed):

#### D1. Database-pool surface — `DatabasePoolAbstract`, `DatabasePoolBase`

- **Type:** Task; **Subsystem:** Database (Pool).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/DatabasePoolAbstractDeadCodeTest.java:53`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/DatabasePoolBaseDeadCodeTest.java:54`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/DatabasePoolAbstractEvictionTest.java:28` (sibling marker; same logical cluster).
- **Anchor quote (`DatabasePoolAbstractDeadCodeTest.java:53`):** ` * <p>WHEN-FIXED: deferred-cleanup track — delete this abstract class together with this`
- **Block locators:** L40–L60 (each file).
- **YTDB-ID:** YTDB-769

#### D2. Database-tool surface — `CheckIndexTool`, `DatabaseCompare`, `GraphRepair`, `DatabaseTool`

- **Type:** Task; **Subsystem:** Database (Tools).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/tool/CheckIndexToolDeadCodeTest.java:54`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/tool/DatabaseCompareDeadCodeTest.java:56`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/tool/GraphRepairDeadCodeTest.java:54`.
  - `DatabaseToolDeadCodeTest.java` — not in inventory; Step 5 confirms via grep before opening.
- **Anchor quote (`CheckIndexToolDeadCodeTest.java:54`):** ` * <p>WHEN-FIXED: deferred-cleanup track — delete {@link CheckIndexTool} together with this`
- **Block locators:** L40–L60 (each file).
- **YTDB-ID:** YTDB-770 (covers `CheckIndexTool`, `DatabaseCompare`, `GraphRepair`; `DatabaseToolDeadCodeTest.java` re-grep confirmed NO `WHEN-FIXED` marker — abstract base survives separately and is folded into the same issue body for context).

#### D3. Exception types — `LiveQueryInterruptedException`, `ManualIndexesAreProhibited`, `RetryQueryException`

- **Type:** Task; **Subsystem:** Database (Exceptions).
- **Test files + lines:** none of the three named `*DeadCodeTest` files appear in the rewrite-target inventory (they may have already been rewritten in earlier tracks). Step 5 implementer **re-greps each filename** and opens the issue only if confirmed.
- **YTDB-ID:** **SKIPPED — no rewrite-target markers.** Step 5 re-grep confirmed:
  - `LiveQueryInterruptedExceptionDeadCodeTest.java` carries only a non-anchored `deferred-cleanup track` text reference inside a paragraph (no `WHEN-FIXED:\s+(Track 22|deferred-cleanup track)` match — `WHEN-FIXED:` and the token are on different lines).
  - `ManualIndexesAreProhibitedDeadCodeTest.java` and `RetryQueryExceptionDeadCodeTest.java` carry zero `WHEN-FIXED` markers.

  Per the manifest's "no markers, no issue" convention for dead-code-SPI, D3 is skipped. The exception classes still exist in production but have no rewrite-target marker tied to them; if a future track wants to delete them it should mint its own issue.

#### D4. Collection-selection-strategy SPI — `BalancedCollectionSelectionStrategy`, `DefaultCollectionSelectionStrategy`, `CollectionSelectionFactory`

- **Type:** Task; **Subsystem:** Storage (Cluster selection SPI).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/clusterselection/BalancedCollectionSelectionStrategyDeadCodeTest.java:64`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/clusterselection/CollectionSelectionFactoryDeadCodeTest.java:74`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/schema/clusterselection/DefaultCollectionSelectionStrategyDeadCodeTest.java:62`.
- **Anchor quote (`BalancedCollectionSelectionStrategyDeadCodeTest.java:64`):** ` * <p>WHEN-FIXED: Track 22 — delete this class and this test together, lockstep with`
- **Block locators:** L50–L75 (each file).
- **YTDB-ID:** YTDB-771 (covers all three strategy + factory classes)

#### D5. Symmetric-key / credential-interceptor security SPI — `DefaultCI`, `SymmetricKeyCI`, `SymmetricKeySecurity`, `UserSymmetricKeyConfig`, `SymmetricKeyDeadMethods`

- **Type:** Task; **Subsystem:** Security (Symmetric key SPI).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/DefaultCIDeadCodeTest.java:43`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/SecurityManagerNewCredentialInterceptorDeadCodeTest.java:57`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/symmetrickey/SymmetricKeyCIDeadCodeTest.java:52`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/symmetrickey/SymmetricKeyDeadMethodsDeadCodeTest.java:49`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/symmetrickey/SymmetricKeySecurityDeadCodeTest.java:51`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/symmetrickey/UserSymmetricKeyConfigDeadCodeTest.java:45,243,261`.
- **Anchor quote (`SymmetricKeyCIDeadCodeTest.java:52`):** ` * <p>WHEN-FIXED: Track 22 — delete {@link SymmetricKeyCI}.`
- **Block locators:** L40–L60 (each file); `UserSymmetricKeyConfigDeadCodeTest:243,261` carry inline-fragment locators L240–L275.
- **YTDB-ID:** YTDB-772 (covers all 6 files, 8 markers, in a single deletion cluster)

> Step 5 implementer note: the security-SPI cluster sits at the
> boundary of Step 2 (security-typed individual issues) and Step 5
> (dead-code-SPI). Per the step file's "any remaining symmetric-key
> SPI items that surface as security-typed during manifest
> classification" hedge in Step 2, the cluster is **left as `Type=Task`**
> in this manifest because the deletion itself is the resolution —
> there is no production fix to apply, only a deletion alongside the
> test files. Step 2 leaves the symmetric-key SPI cluster to Step 5
> unless review feedback re-routes it.

#### D6. DBConfig — `MulticastConfguration`, `UDPUnicastConfiguration`, `Address` (multi-target)

- **Type:** Task; **Subsystem:** Distributed (DB config).
- **Test files + lines:** no `DBConfigDeadCodeTest.java` appears in the rewrite-target inventory. Step 5 implementer **re-greps** and opens the issue only if confirmed.
- **YTDB-ID:** YTDB-773 — Step 5 re-grep confirmed `DBConfigDeadCodeTest.java:54` carries a `WHEN-FIXED:` marker but the token text immediately after `WHEN-FIXED:` is `"delete the entire ..."`, not `Track 22 |` or `deferred-cleanup track`. The token *does* appear two lines below in the surrounding paragraph. This is **NOT a rewrite-target marker** per the anchored Form A/B regex. Production classes (`MulticastConfguration`, `UDPUnicastConfiguration`, `Address` + 3 builders) still exist; issue opened as production-code-referenced. **No marker rewrite performed.**

#### D7. SqlQuery legacy ResultSet — `ConcurrentLegacyResultSet` (covered by Sq13)

Covered by Sq13. Not a separate manifest entry.

#### D8. Dictionary marker class — `Dictionary`

- **Type:** Task; **Subsystem:** Database (Dictionary).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/dictionary/DictionaryDeadCodeTest.java:48`.
- **Anchor quote (L48):** ` * <p>WHEN-FIXED: deferred-cleanup track — delete this test file in the same commit that`
- **Block locators:** L40–L55.
- **YTDB-ID:** YTDB-774 — Step 5 confirmed production class `core/dictionary/Dictionary.java` still exists. 22b deletion did NOT happen; issue opened as a dead-code-delete Task. Marker rewritten.

> Cluster-disposition.md routes `Dictionary` to 22b Cluster C
> (misc-small-helpers) — Step 1 inventory shows the marker is still
> present, meaning the 22b deletion either did not occur or the marker
> rewrite was deferred to 22c. Step 5 implementer **confirms via PSI
> safe-delete** before opening; if `Dictionary` was already deleted in
> 22b, the marker should have been removed and Step 5 simply rewrites
> the orphan marker text to reference the closed-by-deletion record.

#### D9. `RecordMultiValueHelper`

- **Type:** Task; **Subsystem:** Database (Record helpers).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/RecordMultiValueHelperDeadCodeTest.java:58`.
- **Anchor quote (L58):** ` * <p>WHEN-FIXED: deferred-cleanup track — delete this class together with this test file.`
- **Block locators:** L48–L65.
- **YTDB-ID:** YTDB-775 — Step 5 confirmed production class `core/db/record/RecordMultiValueHelper.java` still exists. 22b deletion did NOT happen; issue opened as a dead-code-delete Task. Marker rewritten.

> Cluster-disposition.md routes `RecordMultiValueHelper` to 22b
> Cluster C — same caveat as D8.

#### D10. SecurityManager surface fixes (production-fix Bugs in same file as security-SPI)

- **Type:** Bug; **Subsystem:** Security.
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/security/SecurityManagerTest.java:261,268,281`.
- **Anchor quote (L261):** `    // WHEN-FIXED: Track 22 — once the cache key includes the algorithm, this`
- **Block locators:** L255–L290.
- **What to flip:** SALT_CACHE keys include algorithm; NumberFormatException wrapping.
- **YTDB-ID:** YTDB-776 — kept as `Type: Bug` per the manifest (cache-key + exception-wrapping issues with no direct CWE mapping). All 3 markers rewritten.

> D10 is **production-fix Bug**, not Security Problem — it sits in the
> SecurityManager file but the defects are cache-key and exception-
> wrapping issues with no direct CWE mapping. Step 5 implementer may
> re-route to Step 2 if security-typing is warranted.

#### D11. `ImmutableUser.populateSystemRoles` null-guard

- **Type:** Bug; **Subsystem:** Security.
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/metadata/security/ImmutableUserTest.java:166,184`.
- **Anchor quote (L166):** `   * <p>WHEN-FIXED: Track 22 — add a null check before the for-each on`
- **Block locators:** L160–L190.
- **YTDB-ID:** YTDB-777 — both markers (L166, L184) rewritten.
- **YTDB-ID:** _(filled by Step 5)_

---

### 3.6 Category `script` (Step 3 tail — script transformer / formatter)

These pins were folded into Step 3 (`scheduler` category) at the
manifest-grouping step rather than splitting Step 3 vs a dedicated
script category. Each is a script-engine production-fix issue.

#### Sk1. `PolyglotScriptExecutor` transformer / resolveContext fixes

- **Type:** Bug; **Subsystem:** SQL (Polyglot script executor).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/script/PolyglotScriptBindingTest.java:277,290`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/script/PolyglotScriptExecutorTest.java:165,187,203,324`.
- **Anchor quote (`PolyglotScriptExecutorTest.java:165`):** `   * shape. WHEN-FIXED: Track 22 — the transformer should fall through`
- **Block locators:** L155–L175 (anchor); L180–L195; L195–L215; L315–L335; `PolyglotScriptBindingTest.java`: L270–L300.
- **YTDB-ID:** YTDB-735

#### Sk2. `ScriptFormatter` (Ruby) trailing-newline + `\r`-skip + empty-input crash

- **Type:** Bug; **Subsystem:** SQL (Script formatter).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/script/formatter/ScriptFormatterTest.java:303,323,339`.
- **Anchor quote (L303):** `   * WHEN-FIXED: Track 22 — RubyScriptFormatter likely should emit a trailing newline per line`
- **Block locators:** L295–L345.
- **YTDB-ID:** YTDB-736

#### Sk3. `ScriptTransformerImpl` Polyglot Value handling + `asHostObject` CCE

- **Type:** Bug; **Subsystem:** SQL (Script transformer).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/script/transformer/ScriptTransformerImplTest.java:326,337,388`.
- **Anchor quote (L326):** `   * <p>WHEN-FIXED: Track 22 — guard with {@code isHostObject()} check before`
- **Block locators:** L318–L345; L330–L342; L380–L395.
- **YTDB-ID:** YTDB-737

#### Sk4. `ScriptManager` close-all / `Integer.parseInt` guards / `()` empty-arg / concurrency

- **Type:** Bug; **Subsystem:** SQL (Script manager).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/script/ScriptManagerTest.java:323,571,589,643,670,767,771,807`.
- **Anchor quote (L571):** `   * <p>WHEN-FIXED: Track 22 — add guard around Integer.parseInt in`
- **Block locators:** L315–L330; L565–L580; L583–L595; L640–L650; L665–L675; L760–L775; L800–L820.
- **YTDB-ID:** YTDB-738 — kept as a single issue (manifest's Sk4 split hedge declined; the 8 markers all pin overlapping hardening surfaces on the same `ScriptManager` class).

#### Sk5. `DatabaseScriptManager` resource-pool expose

- **Type:** Bug; **Subsystem:** SQL (Script manager).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/script/DatabaseScriptManagerTest.java:208`.
- **Anchor quote (L208):** `      // WHEN-FIXED: Track 22 — if DatabaseScriptManager / ResourcePoolFactory expose a`
- **Block locators:** L200–L220.
- **YTDB-ID:** YTDB-739

#### Sk6. `SqlScriptExecutor` FunctionLibrary null-guard

- **Type:** Bug; **Subsystem:** SQL (Script executor).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/SqlScriptExecutorTest.java:234`.
- **Anchor quote (L234):** `   * <p>WHEN-FIXED: Track 22 — if {@code FunctionLibrary.getFunction} gains a null-guard and the`
- **Block locators:** L225–L245.
- **YTDB-ID:** YTDB-740

#### Sk7. `Traverse` / `TraverseContext` defensive-branch + LogManager-appender capture

- **Type:** Bug; **Subsystem:** SQL (Traverse).
- **Test files + lines:**
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/traverse/TraverseTest.java:34,628`.
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/command/traverse/TraverseContextTest.java:183`.
- **Anchor quote (`TraverseTest.java:628`):** `   * WHEN-FIXED: Track 22 — the defensive branch in {@link Traverse#hasNext} at lines 91-93`
- **Block locators:** L25–L50; L620–L640; L175–L195.
- **YTDB-ID:** YTDB-741

#### Sk8. `DatabaseSessionEmbeddedAttributes` public read-back exposure

- **Type:** Bug; **Subsystem:** Database (Session attributes).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/DatabaseSessionEmbeddedAttributesTest.java:145,298`.
- **Anchor quote (L145):** `  // WHEN-FIXED: deferred-cleanup track — the production comment that says`
- **Block locators:** L140–L155; L290–L305.
- **YTDB-ID:** YTDB-742 (handled by Step 3 per manifest's tentative routing)

#### Sk9. `MultiValueChangeTimeLine` null-guard

- **Type:** Bug; **Subsystem:** Database (Multi-value tracking).
- **Test file + lines:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/db/record/MultiValueChangeTimeLineTest.java:86`.
- **Anchor quote (L86):** `   * <p>WHEN-FIXED: deferred-cleanup track — if a null guard is added to`
- **Block locators:** L80–L100.
- **YTDB-ID:** YTDB-743 (handled by Step 3 — folded with Sk8 batch since single defensive-NPE marker)

---

### 3.7 Category `pin-maintenance` (Step 6 — 5 + conditional 6th)

#### P1. `InternalErrorException` — rename `InternalErrorExceptionDeadCodeTest.java` → `InternalErrorExceptionTest.java` (live target)

- **Type:** Task; **Subsystem:** Storage (Exceptions).
- **Test file:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/exception/InternalErrorExceptionDeadCodeTest.java` (not in rewrite-target inventory; pin file exists per cluster-disposition.md).
- **Issue body:** rename rationale — `InternalErrorException` has 5 production refs in `AbstractStorage.java`.
- **YTDB-ID:** _(filled by Step 6)_

#### P2. `EntityHelper` — rename `EntityHelperDeadCodeTest.java` → method-level shape pin (112 prod refs; entity helper is alive)

- **Type:** Task; **Subsystem:** Records.
- **Test file:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/record/impl/EntityHelperDeadCodeTest.java:76` (rewrite-target marker present).
- **Anchor quote (L76):** ` * <p>WHEN-FIXED: deferred-cleanup track — drop the twelve dead public methods listed above`
- **Block locators:** L65–L85.
- **Issue body:** pin must be entirely about the 12 dead-method subset, not the class. Rename plan: convert to method-level shape pin or rename file without dropping content.
- **YTDB-ID:** _(filled by Step 6)_

#### P3. `RecordVersionHelper` — rename (2 prod refs + 9 live test refs)

- **Type:** Task; **Subsystem:** Records.
- **Test file:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/record/RecordVersionHelperDeadCodeTest.java:51`.
- **Anchor quote (L51):** ` * <p>WHEN-FIXED: deferred-cleanup track — delete {@link RecordVersionHelper} together with this`
- **Block locators:** L42–L58.
- **YTDB-ID:** _(filled by Step 6)_

#### P4. `EntityComparator` — rename (1 prod ref + 2 live test refs)

- **Type:** Task; **Subsystem:** Records.
- **Test file:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/record/impl/EntityComparatorDeadCodeTest.java:61`.
- **Anchor quote (L61):** ` * <p>WHEN-FIXED: deferred-cleanup track — delete {@link EntityComparator} together with this`
- **Block locators:** L50–L70.
- **YTDB-ID:** _(filled by Step 6)_

#### P5. `SBTreeValue` cross-version (v1 + v2 both alive) — rename

- **Type:** Task; **Subsystem:** Storage (sbtree).
- **Test file:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/.../SBTreeValueDeadCodeTest.java` (not in rewrite-target inventory; cluster-disposition.md says markers either absent or already rewritten).
- **YTDB-ID:** _(filled by Step 6 if marker confirmed)_

#### P6. **Conditional** — `SqlExecutorDeadCodeTest` (live targets per cluster-disposition.md)

- **Type:** Task; **Subsystem:** SQL (Executor).
- **Test file:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SqlExecutorDeadCodeTest.java` (14 rewrite-target markers — covered by Sq11).
- **Decision:** Phase B implementer at Step 5 determines whether `SqlExecutorDeadCodeTest` is a `dead-code-SPI` issue (Sq11) or a `pin-maintenance` rename (P6). The cluster-disposition.md sanity-check entry says **"Pin-maintenance candidate (rename to drop DeadCode) — added to the bucket above as a 6th entry once Phase B confirms with the implementer commit."** Step 5 confirms by re-running PSI find-usages on the pin's targets; if confirmed pin-maintenance, the 14 markers are rewritten in Step 6 and Sq11 is dropped from Step 5.
- **YTDB-ID:** _(filled by Step 5 or Step 6 once classification settled)_

---

## 4. Meta-reference carve-out sites (Step 7 audit anchors)

The 2 `{@code //` Javadoc meta-references that the Step 7 verification
grep MUST exclude:

- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/SqlExecutorDeadCodeTest.java` — exact location TBD (the file's match list contains the meta-reference inside a Javadoc block describing the marker convention).
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/query/live/LiveQueryDeadCodeTest.java` — exact location TBD.

Step 7's verification grep uses `grep -v '{@code //'` as the filter
(the meta-reference-shape `{@code //` rather than bare `{@code`) per
the step file's Phase A iter-3 Finding T11 reconciliation.

## 5. Commit-clustering plan (Steps 2–6)

| Step | Category | Issue count (estimate) | Files touched (estimate) |
|---|---|---:|---:|
| 2 | `security` | 5 | ~6 |
| 3 | `scheduler` + `script` + hooks-SPI | 7 + 9 = 16 | ~14 |
| 4 | `serializer` (+ serializer-SPI clusters) | 3 + 5 = 8 | ~6 |
| 5 | `sql` + remaining `dead-code-SPI` (pool / tool / strategy / exception / config / security-SPI / Dictionary / RecordMultiValueHelper / SecurityManager / ImmutableUser) | 15 + 6 = 21 | ~30 |
| 6 | `pin-maintenance` (5 + conditional 6th) | 5–6 | ~5–6 |

**Total:** ~55 logical issues. (The estimate widens from "~48–49" once
the `script` sub-bucket is fully enumerated as 9 distinct issues
rather than 7. Final count crystallises during Step 3's
`mcp__youtrack__create_issue` runs.)

The total comfortably exceeds the **30 production-fix + 13 dead-code +
5–6 pin-maintenance = 48–49** estimate from the step file. The widening
is in the `script` bucket: 9 logical scripting-fix issues rather than
the 7 estimated. Phase B may merge adjacent script-engine issues (e.g.,
Sk4's eight markers may collapse to 2–3 issues if they share a single
production fix). Final issue count is reported at the close of Step 7.

## 6. Manifest closure (filled at Step 7)

_(Phase B Step 7 appends a closure marker `## Closed at Step 7 —
verification passed` followed by the final YTDB-NNNN range once both
verification greps return zero hits.)_
