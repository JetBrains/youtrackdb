# Track 22c: WHEN-FIXED Issue Creation & Marker Rewrite

## Description

Open YTDB tracking issues for production-fix WHEN-FIXED pins
(149 surviving rewrite-target marker occurrences across 65 test
files in `core/src/test/`, in both line-comment and Javadoc /
string-literal forms — collapsed to one issue per logical fix,
~30 production-fix issues) plus the SPI-risky dead-code clusters
deferred from 22b (clustered to 13 logical issues per the
**Defer-to-22c cluster grouping** subsection below) plus the 5
(+1 conditional) pin-maintenance renames. Rewrite both forms of
the `// WHEN-FIXED: Track 22` / `// WHEN-FIXED: deferred-cleanup
track` markers (line-comment AND Javadoc `<p>WHEN-FIXED:` /
`* WHEN-FIXED:` / embedded-in-assertion-message string-literal)
in the corresponding test sources to reference the new YTDB-NNNN
IDs; clusters deleted by 22b are skipped (full deferred-cluster
list in `cluster-disposition.md`).

**Scope:** ~5–7 steps — issue creation and marker rewrites
**clustered by category** (security, scheduler, serializer,
sql/legacy, dead-code-SPI clusters, pin-maintenance renames),
where each step lands as one batched commit covering its
category (creates the issues in that category and rewrites all
their markers in lockstep). Final step is a **mandatory**
verification grep across `core/src/test/`. No coverage delta
expected; this is tracker hygiene.

**Depends on:** Track 1, Track 22a, Track 22b (consumes 22b's
final cluster-disposition list as the filter — only markers in
clusters NOT deleted by 22b need YTDB issues).

> **What**:
> - Open YTDB tracking issues for **production-fix WHEN-FIXED pins**:
>   the 149 surviving rewrite-target marker occurrences across 65
>   test files in `core/src/test/` (line-comment + Javadoc /
>   string-literal forms; see the **Post-22b reality check** table
>   below for the per-form breakdown), collapsed to one issue per
>   logical fix (per Pre-Flight clarification — ~30 production-fix
>   issues estimated). Multiple markers in a single test file
>   usually pin the same logical fix; granularity guidance is
>   **one issue per logical fix**, not one per marker.
> - Open YTDB tracking issues for the **SPI-deferred dead-code
>   clusters** that 22b classified as "issue-only-defer" per the
>   hybrid policy. The 19 pins in `cluster-disposition.md`'s
>   explicit defer-to-22c table plus the additional ~10 pins
>   surfaced in its "Pins not classified above" sanity-check
>   section (RecordListener, RecordStringable, DefaultCI,
>   SymmetricKeyCI / SymmetricKeySecurity / UserSymmetricKeyConfig
>   / SymmetricKeyDeadMethods, Compression, DBConfig multi-target,
>   SqlQuery legacy ResultSet) cluster into **13 logical issues**.
>   Final cluster list is enumerated in the **Defer-to-22c cluster
>   grouping** subsection of `**How**` below. Each logical issue
>   carries the full deletion plan + consumer-search notes from
>   22b's PSI classification.
> - **Open YTDB tracking issues for the pin-maintenance renames**:
>   the 5 entries (`InternalErrorException`, `EntityHelper`,
>   `RecordVersionHelper`, `EntityComparator`, `SBTreeValue`
>   cross-version) plus the conditional 6th (`SqlExecutorDeadCodeTest`)
>   from `cluster-disposition.md`. Each becomes one tracking issue
>   describing the rename (drop `DeadCode` suffix; live production
>   target).
> - **Rewrite `WHEN-FIXED: Track 22` / `WHEN-FIXED: deferred-cleanup
>   track` markers in both forms** (Form A — `// WHEN-FIXED: …` line
>   comments; Form B — Javadoc `<p>WHEN-FIXED:` / `* WHEN-FIXED:`
>   inside `/** */` blocks and embedded-in-assertion-message string
>   literals) across `core/src/test/` to reference the new YTDB-NNNN
>   IDs. Both forms describe the same logical fix and both must be
>   rewritten; only the 2 `{@code // WHEN-FIXED: …}` Javadoc
>   meta-references are exempt (they describe the marker convention
>   itself, not the pinned bug). The rewrite filter excludes markers
>   in clusters DELETED by 22b (no-issue convention — the
>   `*DeadCodeTest.java` pins were deleted alongside their production
>   classes in 22b).
>
> **Production-fix pin inventory** (from previous Phase A iter-1
> R3/A6 — symmetric to dead-code policy; classify the ~30 inherited
> bugs from Tracks 7–21):
> - **In-22a-fix candidates** (production fix is local + test-additive,
>   handled in 22a, no YTDB issue needed in 22c): `CronExpression`
>   parse-leniency edge cases; `CompositeKeySerializer` Map-flatten
>   preprocess negative branches; `BinarySerializerFactory.create()`
>   singleton harmonization; `BinaryComparatorV0` DATE × LONG /
>   DATETIME × DATE / DECIMAL × BYTE / BOOLEAN × STRING asymmetries
>   (production-side normalization).
> - **Resolved during 22b cluster deletions** (no YTDB issue needed
>   — the production code was deleted alongside its test pin):
>   `BasicCommandContext.copy()` null-child NPE (was 22a-patched
>   with a null-guard at commit `03f75c3ffa`, then fully deleted
>   in 22b Cluster K commit `24e89238b9` per `cluster-disposition.md`'s
>   Cluster K row).
> - **22c-defer (issue + marker rewrite)**: `LiveQueryHookV2.calculateProjections`
>   always-empty bug (DB-engine-wide projection plumbing); V1 `break`
>   vs V2 `continue` `InterruptedException` handling; `ScheduledEvent`
>   ctor swallows `ParseException`; `executeEventFunction` retry-loop
>   bug; `SchedulerImpl.onEventDropped` NPE; `BytesContainer`
>   zero-capacity infinite-loop hang; `RecordSerializerBinaryV1.deserializeEmbeddedAsDocument`
>   insecure deserialization; `EntitySerializerDelta.deserializeValue`
>   insecure deserialization; `EntitySerializerDelta` unbounded
>   item-count loops; `BinaryComparatorV0` DATE × STRING NFE crash;
>   `BytesContainer.deserializeValue` length-prefix validation gap;
>   `RecordSerializerBinary.fromStream(byte[])` AIOOBE + Base64-of-input
>   log-injection; `HelperClasses.readLinkCollection` NULL_RECORD_ID
>   dead branch; `RecordSerializationDebug*` `faildToRead` typo;
>   `CompactedLinkSerializer`/`LinkSerializer` `(short)` cluster-id
>   truncation; the seven Track-17 latent issues (the most recent
>   addition: `TokenSignImpl.readKeyFromConfig` unreachable inner
>   branch — tokens cannot be verified across server restarts because
>   configured `NETWORK_TOKEN_SECRETKEY` is silently ignored);
>   **MemoryAndLocalPaginatedEnginesInitializer.initialize() non-volatile
>   flag race** (surfaced via Track 22a Phase C BC-22a-3) — the
>   `private boolean initialized` field is read+written without volatile
>   or synchronization; two threads racing on `initialize()` can both
>   pass the `if (!initialized)` check and run `configureDefaults()`
>   twice, re-mutating shared `GlobalConfiguration.DISK_CACHE_SIZE` /
>   `WAL_RESTORE_BATCH_SIZE`. Pre-existing race; not introduced by 22a;
>   the 22a `MemoryAndLocalPaginatedEnginesInitializerTest` is marked
>   `@Category(SequentialTest)` to avoid surfacing it. Fix: declare
>   the field `volatile`, or convert to `AtomicBoolean`, or wrap the
>   init in `synchronized`. Issue body should pin the WHEN-FIXED
>   marker location once the test gains a regression-stress assertion.
>   **`RecordAbstract.dirty` public-field encapsulation** (surfaced
>   via Track 22a Phase C CQ19 — RecordCacheWeakRefsTest's F19 fix
>   needed direct field-write because `setDirty(long)` is gated by
>   `checkForBinding` and post-commit unbound entities cannot mutate
>   dirty via the public setter). The field is `public long` — a
>   leaked-implementation seam. Refactor options: (a) demote to
>   package-private + provide a package-internal `markDirty()` test
>   helper; (b) provide an unguarded `RecordAbstract#testOnlyForceDirty()`
>   package-private helper. Either closes the public-field smell
>   without losing the seam. Issue body must reference the
>   RecordCacheWeakRefsTest sites that need the helper migration.
>
> **Pre-22b WHEN-FIXED inventory** (historical, from a previous
> Pre-Flight): 164 distinct test files contained WHEN-FIXED markers
> (440 markers total). Three buckets: ~63 `*DeadCodeTest.java`
> lockstep deletion pins (most rewritten away by 22b's deletions),
> 101 non-dead-code production-fix / refactor pins, ~276 in-file
> repeated markers / sub-pins.
>
> **Post-22b reality check** (taken at this Phase A iteration, after
> 22b's 47 in-track deletions landed; refined at iter-3 per Findings
> T1/T9). Marker forms in `core/src/test/`:
>
> | Form | Pattern (regex) | Files | Lines |
> |---|---|---:|---:|
> | (A) Line-comment | `^[[:space:]]*//[[:space:]]*WHEN-FIXED:[[:space:]]+(Track 22\|deferred-cleanup track)` | 15 | 66 |
> | (B) Javadoc + string-literal | inside `/** */` blocks (`<p>WHEN-FIXED:` or `* WHEN-FIXED:`) and inside assertion-message string literals containing `— WHEN-FIXED: Track 22` | 50 | 83 |
> | **(A) + (B) total rewrite targets** | union of the above (any non-`{@code}` occurrence of `WHEN-FIXED: (Track 22\|deferred-cleanup track)`) | **65** | **149** |
> | `{@code // WHEN-FIXED: …}` Javadoc descriptors (NOT rewrite targets) | inside `{@code }` references in Javadoc | 2 | 2 |
> | All `WHEN-FIXED` mentions (broadest grep) | `WHEN-FIXED` anywhere | 130 | 329 |
>
> The load-bearing rewrite-target count is **65 files / 149 marker
> occurrences** spanning both forms — both reference the same logical
> fixes and must point at the same `YTDB-NNNN` IDs once issues are
> created. The 2 `{@code // WHEN-FIXED: …}` Javadoc descriptors are
> meta-references about the marker convention and must NOT be
> rewritten. The remaining gap from 149 → 329 is Javadoc descriptors,
> continuation lines, in-source explanatory text, and markers already
> rewritten to `YTDB-NNNN` by earlier tracks. Phase B Step 0 re-runs
> both greps (A and B) at session start, builds the manifest from
> their union (minus the `{@code}` carve-out), and aborts if the
> counts drift from the values in this table.
>
> **How**:
> - **Issue field defaults for the YTDB project** (from
>   `get_issue_fields_schema` at previous Pre-Flight time): no required
>   custom fields, but Type=Bug for production-fix pins, Type=Task for
>   dead-code-deletion-deferred pins and pin-maintenance renames,
>   Priority=Normal default; Subsystem set per package/area as
>   appropriate; State=Submitted on creation.
> - **Security-Type override** (per Phase A iter-1 Finding T3):
>   production-fix pins flagged as `insecure deserialization`, `log
>   injection`, `crypto / signing bypass`, or `gadget chain` get
>   `Type=Security Problem` (not `Type=Bug`) plus a `Security Severity`
>   value when YTDB schema offers it (Medium for log-injection, High
>   for insecure deserialization, High for cross-restart token
>   bypass). Affected items: `RecordSerializerBinaryV1.deserializeEmbeddedAsDocument`;
>   `EntitySerializerDelta.deserializeValue` + the related unbounded
>   item-count loops; `RecordSerializerBinary.fromStream(byte[])`
>   AIOOBE + Base64-of-input log-injection; `TokenSignImpl.readKeyFromConfig`
>   cross-restart token-verification failure (configured
>   `NETWORK_TOKEN_SECRETKEY` silently ignored).
> - **Issue body shape** (always): (1) the test class file path and
>   method name(s) carrying the marker, (2) verbatim quote of the
>   WHEN-FIXED **comment block** from the test source so the issue is
>   self-contained, (3) one-line "what to do when the issue is fixed"
>   pointing at the test assertion that needs flipping, (4)
>   originating track reference (e.g., "Surfaced during Track NN").
> - **"Comment block" definition** (per Phase A iter-1 Finding T5):
>   the "WHEN-FIXED comment block" quoted in the issue body is **all
>   contiguous comment lines (Java `//` or `/* */`)** immediately
>   preceding and following the line carrying `WHEN-FIXED:`, treating
>   blank lines and non-comment lines as block boundaries. For
>   Javadoc, the entire `*` block containing the marker. Three
>   observed shapes:
>   - **Multi-line Javadoc block** (e.g., `TokenSignImplTest:255-267`,
>     ~13 lines): quote the whole `/** … */`.
>   - **Multi-line `//` block** (e.g., `SchedulerImplTest:621` plus
>     its ~10 surrounding context lines): quote the contiguous `//`
>     comment run.
>   - **Single-line inline fragment** (e.g.,
>     `BinaryComparatorV0DateSourceTest:193,197,427,436,459`): quote
>     the WHEN-FIXED line plus the 2–5 surrounding test-method lines
>     (assertion + test name) so the bug context is preserved.
> - **Cluster-into-~6 commit policy** (per Phase A iter-1 Finding T2,
>   user-chosen option): the inherited R8 "one commit per issue"
>   rule is replaced by **one batched commit per logical category**.
>   Each step lands as a single commit covering one category — both
>   the issue creations (`mcp__youtrack__create_issue` calls) and the
>   marker rewrites for that category, in lockstep. Categories
>   (final list emerges from Phase B Step 0 manifest): security
>   (Type=Security Problem subset); scheduler + cron; serializer
>   (Binary / Delta / debug); sql / executor / legacy ResultSet;
>   dead-code-SPI clusters (the 19 defer-to-22c pins → ~6–8 logical
>   issues); pin-maintenance renames (5 + conditional 6th). The
>   commit message names the category and the YTDB IDs landed
>   (e.g., `Create YTDB-NNNN..NNNN serializer-area issues + rewrite
>   markers`). Per-category revert remains trivial because each
>   step is one commit; per-issue revert is degraded but accepted
>   given the zero-functional-risk nature of tracker hygiene.
>   **Per-step coverage-gate is skipped on this track** (no
>   production-source change → JaCoCo cannot measure a delta);
>   Phase B `coverage-gate.py` invocation is omitted.
> - **Mixed-marker file ordering** (mechanical fix T3 from previous
>   Phase A iter-1, still applies): when a test file carries
>   WHEN-FIXED markers for multiple logical fixes (e.g.,
>   `ScriptManagerTest`'s eight markers mapping to ~5 distinct
>   production issues), the rewrites cross commits — the file is
>   touched by multiple category commits, each rewriting only the
>   markers in its category. The final cluster-disposition list
>   from 22b is consumed FIRST in Step 0 (so deleted-cluster
>   markers are filtered out), THEN the per-category rewrites land
>   in dependency order (security → scheduler → serializer → sql →
>   dead-code-SPI → pin-maintenance is one reasonable order; Phase
>   B may reorder if the manifest reveals dependencies).
> - **Defer-to-22c cluster grouping** (per Phase A iter-1 Finding T7
>   and iter-2 Finding T10): in Phase B Step 0 the implementer
>   groups the defer-to-22c pins from `cluster-disposition.md`
>   (the explicit 19-row table plus the "Pins not classified
>   above" sanity-check entries) into the following **13 logical
>   clusters** before opening issues:
>   1. **Hooks SPI** — `EntityHookAbstract`, `RecordHookAbstract`.
>   2. **Database-pool surface** — `DatabasePoolAbstract`, `DatabasePoolBase`.
>   3. **Database-tool surface** — `CheckIndexTool`, `DatabaseCompare`,
>      `GraphRepair`, `DatabaseTool`.
>   4. **Exception types** — `LiveQueryInterruptedException`,
>      `ManualIndexesAreProhibited`, `RetryQueryException`.
>   5. **Collection-selection-strategy SPI** —
>      `BalancedCollectionSelectionStrategy`,
>      `DefaultCollectionSelectionStrategy`,
>      `CollectionSelectionFactory`.
>   6. **Spatial-index slot** — `MockSerializer` +
>      `BinarySerializerFactory` id `-10`.
>   7. **Serializer abstract bases** — `RecordSerializerCSVAbstract`,
>      `RecordSerializerStringAbstract`.
>   8. **sbtree-local-v1 pair** — `SBTreeBucketV1`,
>      `SBTreeNullBucketV1`.
>   9. **Listener / marker SPI** — `RecordListener`,
>      `RecordStringable`.
>   10. **Symmetric-key / credential-interceptor security SPI** —
>       `DefaultCI`, `SymmetricKeyCI`, `SymmetricKeySecurity`,
>       `UserSymmetricKeyConfig`, `SymmetricKeyDeadMethods`.
>   11. **Compression SPI** — `Compression` interface.
>   12. **DBConfig** — `MulticastConfguration`,
>       `UDPUnicastConfiguration`, `Address` (config-surface
>       multi-target).
>   13. **SqlQuery legacy ResultSet** — `ConcurrentLegacyResultSet`.
>
>   Phase B may merge or split clusters when the manifest is
>   drafted (e.g., cluster 10 may carry sub-issues for the
>   credential-interceptor surface vs the symmetric-key crypto
>   surface), but the count is anchored at "**~10–13 issues**" for
>   the dead-code-SPI bucket — not the prior "~6–8" estimate.
> - **No issues created during Pre-Flight or 22a** — the actual
>   issue creation and marker rewrite happen in 22c's steps.
> - **MANDATORY verification step** (per Phase A iter-1 Finding T8
>   and iter-3 reconciliation): the final step of Track 22c MUST run
>   a verification grep across `core/src/test/` and produce **zero
>   hits** for both forms before the track is marked complete. Two
>   patterns are run, mirroring the inventory split in the
>   **Post-22b reality check** table:
>   - **Form A (line-comment, anchored)**:
>     `'^[[:space:]]*//[[:space:]]*WHEN-FIXED:[[:space:]]+(Track 22|deferred-cleanup track)'`
>     — expected zero hits after the rewrite.
>   - **Form B (Javadoc + string-literal, any non-`{@code //}`
>     occurrence)**: `'WHEN-FIXED:[[:space:]]+(Track 22|deferred-cleanup track)'`
>     filtered to exclude lines containing the **meta-reference shape
>     `{@code //`** specifically — expected zero hits after the
>     rewrite.
>
>   The combined zero-hit gate is the only automated check that the
>   rewrite is complete (line-comment-only would miss ~83 Javadoc /
>   string-literal occurrences). The exclusion for `{@code //
>   WHEN-FIXED: …}` Javadoc meta-references must anchor on `{@code //`
>   (the meta-reference pattern) rather than bare `{@code` (per
>   Phase A iter-3 Finding T11 — otherwise `{@code null}` substrings
>   in `PolyglotScriptExecutorTest`'s legitimate-rewrite-target lines
>   would be falsely skipped). Concrete filter: `grep -v '{@code //'`.
>   The manifest in Step 0 enumerates the 2 known meta-reference
>   sites (`SqlExecutorDeadCodeTest`, `LiveQueryDeadCodeTest`) so the
>   verifying grep can audit the carve-out is correct.
>
>   Any non-zero hit means either (a) the cluster was deleted by
>   22b and the marker was removed alongside its test file (does
>   not occur — those pins are gone from disk), or (b) the rewrite
>   missed a file or form (defect → fix in the same step before
>   committing the verification). **The verification step is the
>   only automated gate on Track 22c (zero coverage delta, no
>   functional tests), so it is not optional.**
>
> **Constraints**:
> - In-scope: YTDB issue creation + marker rewrites in
>   `core/src/test/`. No production code changes.
> - Out-of-scope: in-22a-fix candidates (those landed in 22a as
>   test-additive production fixes); deleted clusters from 22b
>   (no-issue convention); markers in modules other than `core/`
>   (none expected — the inventory at start of 22c is `core/src/test/`
>   only).
> - **Issue body must include the verbatim WHEN-FIXED comment block**
>   so the issue is self-contained even if the test source later
>   evolves.
> - **No marker may reference a YTDB ID that was not created in this
>   branch** — strict invariant from previous Pre-Flight clarification.
>
> **Interactions**:
> - Depends on Track 1, Track 22a, Track 22b. 22c's marker-rewrite
>   filter consumes 22b's final cluster-disposition list as input.
> - Closes the deferred-cleanup queue's tracker-hygiene tail.
> - No coverage delta expected; this is tracker hygiene.

### WHEN-FIXED clarification (full text — carried from previous Pre-Flight)

> Track 22 must include a dedicated step (and/or sub-step within each
> coverage step) that creates a YouTrack `YTDB`-project issue for each
> WHEN-FIXED test currently pinned in `core/src/test/`, then rewrites
> the inline `// WHEN-FIXED: Track 22` (and `// WHEN-FIXED: deferred-
> cleanup track`) marker to reference the issue ID — e.g.,
> `// WHEN-FIXED: YTDB-NNNN`. The marker rewrite and the issue
> creation must be in lockstep: every issue created must have its ID
> threaded back into the corresponding test source within Track 22c,
> and no rewritten marker may reference a YTDB ID that was not created
> in this branch.
>
> **Cross-step interaction:** the WHEN-FIXED rewrite step must run
> **before** any dead-code lockstep deletion in 22b — except that the
> split has reversed the ordering: 22b runs first (deletes the
> SPI-safe clusters and their `*DeadCodeTest.java` pins), then 22c's
> rewrite consumes 22b's final cluster-disposition list as the
> filter. The deletion-by-22b clusters skip issue creation entirely
> (the deletion itself is the resolution); only the deferred-by-22b
> clusters get YTDB issues. This is option (a) of the previous
> Pre-Flight's two-option choice — chosen because it avoids issue
> churn for resolved-in-this-PR deletions.

## Progress
- [x] Review + decomposition
- [ ] Step implementation (1/7 complete)
- [ ] Track-level code review

## Base commit
`c9528a91e608c213a40972cd02065ebc115fec53`

## Reviews completed
- [x] Technical: PASS at iteration 3 (11 findings — 0 blockers, 4 should-fix [T1–T4], 7 suggestions [T5–T11]; 11 accepted, 0 rejected, 0 deferred. Iter-1 surfaced T1–T8; iter-2 gate VERIFIED T2–T8 and re-opened T1 with new findings T9/T10; iter-3 gate VERIFIED T1/T9/T10 with new suggestion-tier T11 auto-applied as a mechanical filter tightening. User-chosen disposition on T2: cluster-into-~6 commit policy in place of the inherited R8 "one commit per issue" rule.)

## Steps

- [x] Step 1: Pre-flight manifest — re-run inventory greps, classify rewrite-target markers by logical fix, draft the per-issue manifest
  - [x] Context: safe
  > **Risk:** low — default (no production code changes; no test source edits beyond a `_workflow/`-scoped manifest markdown file).
  >
  > **What was done:** Re-ran the Form A (line-comment, anchored) and
  > Form B (any non-`{@code //` occurrence) inventory greps across
  > `core/src/test/`. Form A returned 15 files / 66 lines exactly per
  > the step-file table; A+B union returned 63 files / 149 lines (line
  > count matches; file count is 63 not 65 — see discovery below);
  > broad `WHEN-FIXED` grep returned 130 files / 329 lines exactly.
  > Read `cluster-disposition.md` to enumerate defer-to-22c clusters
  > and pin-maintenance entries. Drafted `_workflow/wfx-22c-manifest.md`
  > (798 lines) organising surviving markers into six commit-clustering
  > categories — security, scheduler, serializer, sql, dead-code-SPI,
  > pin-maintenance — with ~55 logical issues enumerated for
  > Steps 2–6 lockstep issue creation + marker rewrite. Single
  > workflow-only commit (`a85c0bf558`); no test source touched; no
  > YTDB issues created yet.
  >
  > **What was discovered:** (1) A+B union file count is 63 not 65
  > as anticipated in the step-file table — the 2 `{@code //`
  > meta-reference sites (`SqlExecutorDeadCodeTest`,
  > `LiveQueryDeadCodeTest`) carry BOTH meta-references AND real
  > rewrite-target line comments, so they stay in the post-filter
  > set. Line counts (149) match exactly. Step 7's verification grep
  > is the load-bearing gate regardless. (2) Total logical-issue
  > count widens from the step-file's ~48 estimate to ~55 once each
  > bucket is fully enumerated. (3) Several listed defer-to-22c
  > pins (DBConfig multi-target, RecordSerializerCsvAbstract /
  > RecordSerializerStringAbstract, SBTree-v1 pair, exception types
  > `LiveQueryInterruptedException` / `ManualIndexesAreProhibited` /
  > `RetryQueryException`, `DatabaseToolDeadCodeTest`) do NOT appear
  > in the surviving rewrite-target inventory — their markers may
  > have been rewritten by earlier tracks or never matched the
  > Form-A/B regex. The manifest flags each as "open issue only if
  > the Step 5 implementer re-greps and confirms a surviving marker".
  >
  > **What changed from the plan:** No plan changes. The manifest's
  > commit-clustering envelope (Step 2: 5; Step 3: 16; Step 4: 8;
  > Step 5: 21; Step 6: 5–6 logical issues) sits inside the step
  > file's "~6 batched commits" expectation. The
  > `SqlExecutorDeadCodeTest` routing question (Step 5 dead-code-SPI
  > vs Step 6 pin-maintenance) is parked for Step 5's manifest
  > re-read to resolve.
  >
  > **Key files:** `docs/adr/unit-test-coverage/_workflow/wfx-22c-manifest.md` (new, 798 lines).
  >
  > **Critical context:** Step 7's verification filter must be
  > `grep -v '{@code //'` (not bare `{@code`) per the step file's
  > Phase A iter-3 Finding T11 — otherwise legitimate-rewrite-target
  > lines in `PolyglotScriptExecutorTest` containing `{@code null}`
  > substrings would be falsely skipped. Manifest §4 lists the 2
  > meta-reference sites for the Step 7 audit.

- [ ] Step 2: Security category — create Type=Security Problem issues, rewrite markers
  > **Risk:** low — default (test-source comment edits only; no production code, no test-logic change).
  >
  > **What:** For each manifest entry tagged `category=security`: call `mcp__youtrack__create_issue` with `Type=Security Problem`, the appropriate `Security Severity` (High for insecure deserialization / token-bypass; Medium for log-injection), and the issue body shape (test path + method names, verbatim comment block, "what to flip" line, originating-track reference). Then rewrite every marker (both Form A and Form B) for that issue to `// WHEN-FIXED: YTDB-NNNN` / `<p>WHEN-FIXED: YTDB-NNNN — …` / `… — WHEN-FIXED: YTDB-NNNN`. Expected affected items: `RecordSerializerBinaryV1.deserializeEmbeddedAsDocument`; `EntitySerializerDelta.deserializeValue` + unbounded item-count loops; `RecordSerializerBinary.fromStream(byte[])` AIOOBE + Base64-of-input log-injection; `TokenSignImpl.readKeyFromConfig` cross-restart token-verification failure; any remaining symmetric-key SPI items that surface as security-typed during manifest classification.
  >
  > Commit message: `Track 22c Step 2: Create YTDB-NNNN..NNNN security issues + rewrite markers`. Single batched commit.
  >
  > **Files touched:** the test source files carrying security-bucket markers; manifest file gets the YTDB-NNNN IDs written back.

- [ ] Step 3: Scheduler / cron / live-query / hooks category — create issues, rewrite markers
  > **Risk:** low — default (test-source comment edits only).
  >
  > **What:** For each manifest entry in this category: create the YTDB issue (`Type=Bug` for production-fix items, `Type=Task` for hook-SPI dead-code clusters) and rewrite markers. Expected items: `LiveQueryHookV2.calculateProjections` always-empty bug; V1 `break` vs V2 `continue` `InterruptedException` handling; `ScheduledEvent` ctor swallows `ParseException`; `executeEventFunction` retry-loop bug; `SchedulerImpl.onEventDropped` NPE; `CronExpression` parse-leniency remaining items (those NOT handled in 22a); hooks SPI dead-code cluster (`EntityHookAbstract` + `RecordHookAbstract`).
  >
  > Commit message: `Track 22c Step 3: Create YTDB-NNNN..NNNN scheduler/live-query/hooks issues + rewrite markers`. Single batched commit.
  >
  > **Files touched:** the test source files carrying scheduler/cron/live-query/hooks-bucket markers; manifest updated.

- [ ] Step 4: Serializer / binary / debug category — create issues, rewrite markers
  > **Risk:** low — default (test-source comment edits only).
  >
  > **What:** Production-fix items: `BinaryComparatorV0` DATE × STRING NFE crash; `BytesContainer` zero-capacity infinite-loop hang; `BytesContainer.deserializeValue` length-prefix validation gap; `HelperClasses.readLinkCollection` NULL_RECORD_ID dead branch; `RecordSerializationDebug*` `faildToRead` typo; `CompactedLinkSerializer` / `LinkSerializer` `(short)` cluster-id truncation. Plus SPI-deferred dead-code clusters in this area: serializer abstract bases (`RecordSerializerCSVAbstract`, `RecordSerializerStringAbstract`); spatial-index slot (`MockSerializer` + `BinarySerializerFactory` id `-10`); sbtree-local-v1 pair (`SBTreeBucketV1`, `SBTreeNullBucketV1`); listener / marker SPI (`RecordListener`, `RecordStringable`); compression SPI (`Compression`).
  >
  > Commit message: `Track 22c Step 4: Create YTDB-NNNN..NNNN serializer/binary/debug issues + rewrite markers`. Single batched commit.
  >
  > **Files touched:** the test source files carrying serializer/binary/debug-bucket markers; manifest updated.

- [ ] Step 5: SQL / executor / legacy / database-pool / database-tool / config category — create issues, rewrite markers
  > **Risk:** low — default (test-source comment edits only).
  >
  > **What:** Production-fix items: the seven Track-17 latent issues (excluding the `TokenSignImpl` one already in security); `MemoryAndLocalPaginatedEnginesInitializer.initialized` non-volatile race; `RecordAbstract.dirty` public-field encapsulation. Plus SPI-deferred dead-code clusters: exception types (`LiveQueryInterruptedException`, `ManualIndexesAreProhibited`, `RetryQueryException`); collection-selection-strategy SPI (`Balanced*`, `Default*`, `CollectionSelectionFactory`); database-pool surface (`DatabasePoolAbstract`, `DatabasePoolBase`); database-tool surface (`CheckIndexTool`, `DatabaseCompare`, `GraphRepair`, `DatabaseTool`); DBConfig (`MulticastConfguration`, `UDPUnicastConfiguration`, `Address`); SqlQuery legacy ResultSet (`ConcurrentLegacyResultSet`); symmetric-key / credential-interceptor SPI carve-out if any items remain after Step 2 routed the security-typed subset elsewhere.
  >
  > Commit message: `Track 22c Step 5: Create YTDB-NNNN..NNNN sql/legacy/pool/tool/config issues + rewrite markers`. Single batched commit.
  >
  > **Files touched:** the test source files carrying this bucket's markers; manifest updated.

- [ ] Step 6: Pin-maintenance renames — create issues, rewrite markers, rename `*DeadCodeTest.java` files
  > **Risk:** low — default (test-class renames are mechanical; PSI find-usages already confirmed the production targets are ALIVE in `cluster-disposition.md`'s pin-maintenance section).
  >
  > **What:** Create one YTDB issue per pin-maintenance entry — `InternalErrorException`, `EntityHelper`, `RecordVersionHelper`, `EntityComparator`, `SBTreeValue` cross-version (+ conditional `SqlExecutorDeadCodeTest` if Phase B Step 1 manifest confirms). Each issue describes the rename / suffix-drop and references the live production target. Then perform the pin file renames (`*DeadCodeTest.java` → `*Test.java` or equivalent per the cluster-disposition column) and rewrite any markers carried by those files to the new YTDB-NNNN IDs.
  >
  > Commit message: `Track 22c Step 6: Create YTDB-NNNN..NNNN pin-maintenance issues + rename pin files`. Single batched commit.
  >
  > **Files touched:** the 5 (+1) pin-maintenance test files (renamed via `git mv` and content updates); manifest updated.

- [ ] Step 7: Mandatory verification grep + manifest closure
  > **Risk:** low — default (verification step; no source edits unless a defect is uncovered).
  >
  > **What:** Run the two verification greps (Form A anchored line-comment + Form B any-form with `{@code //` carve-out) across `core/src/test/`. Both MUST produce **zero hits**. If non-zero: fix the missed file(s) in this same step (re-run `mcp__youtrack__create_issue` only if a logical fix was missed in the manifest classification; otherwise just rewrite the marker). Mark the manifest file with `## Closed at Step 7 — verification passed` and the final YTDB-NNNN range. This is the only automated gate on the track and cannot be skipped.
  >
  > Commit message: `Track 22c Step 7: Verify WHEN-FIXED rewrite + close manifest`. Single commit (manifest closure + any defect fixes if surfaced).
  >
  > **Files touched:** any test source files where the verification grep surfaced misses; `_workflow/wfx-22c-manifest.md` (closure line added).
