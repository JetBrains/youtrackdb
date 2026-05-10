# Unit Test Coverage — Core Module — Track Details

<!-- DO NOT DELETE THIS FILE. Its presence on disk signals the new
split-file plan format (see .claude/workflow/conventions.md §1.2).
Deleting it flips subsequent workflow operations into legacy mode.
Natural cleanup happens when the branch is deleted after PR merge. -->

## Track 22c: WHEN-FIXED Issue Creation & Marker Rewrite

> **What**:
> - Open YTDB tracking issues for **production-fix WHEN-FIXED pins**:
>   the 101 non-dead-code markers across 164 test files, collapsed
>   to one issue per logical fix (per Pre-Flight clarification).
>   Multiple markers in a single test file usually pin the same
>   logical fix; granularity guidance is **one issue per logical
>   fix**, not one per marker.
> - Open YTDB tracking issues for the **SPI-deferred dead-code
>   clusters** that 22b classified as "issue-only-defer" per the
>   hybrid policy (Hooks, database-pool, database-tool subset,
>   command-script SPI tops if deferred, serializer-base survivors).
>   Each cluster gets one tracking issue with the full deletion plan
>   + consumer-search notes from 22b's PSI classification.
> - **Rewrite inline `// WHEN-FIXED: Track 22`** (and `// WHEN-FIXED:
>   deferred-cleanup track`) markers across `core/src/test/` to
>   reference the new YTDB-NNNN IDs. The rewrite filter excludes
>   markers in clusters DELETED by 22b (no-issue convention — the
>   `*DeadCodeTest.java` pins were deleted alongside their production
>   classes in 22b).
>
> **Production-fix pin inventory** (from previous Phase A iter-1
> R3/A6 — symmetric to dead-code policy; classify the ~30 inherited
> bugs from Tracks 7–21):
> - **In-22a-fix candidates** (production fix is local + test-additive,
>   handled in 22a, no YTDB issue needed in 22c): `BasicCommandContext.copy()`
>   null-child NPE; `CronExpression` parse-leniency edge cases;
>   `CompositeKeySerializer` Map-flatten preprocess negative branches;
>   `BinarySerializerFactory.create()` singleton harmonization;
>   `BinaryComparatorV0` DATE × LONG / DATETIME × DATE / DECIMAL × BYTE
>   / BOOLEAN × STRING asymmetries (production-side normalization).
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
> **WHEN-FIXED inventory at start of 22c** (from previous Pre-Flight):
> 164 distinct test files contain WHEN-FIXED markers (440 markers
> total across the core test sources). The 440 markers split into
> three buckets: ~63 `*DeadCodeTest.java` lockstep deletion pins (most
> rewritten away by 22b's deletions — 22c covers only the SPI-deferred
> subset of clusters), 101 non-dead-code production-fix / refactor
> pins (22c's primary scope), and ~276 in-file repeated markers /
> sub-pins / cross-references that fold into the per-issue grouping
> (multiple markers in one file usually pin one logical fix per the
> "one issue per logical fix" rule). 22c Phase A re-validates this
> arithmetic before issue creation begins.
>
> **How**:
> - **Issue field defaults for the YTDB project** (from
>   `get_issue_fields_schema` at previous Pre-Flight time): no required
>   custom fields, but Type=Bug for production-fix pins, Type=Task for
>   dead-code-deletion-deferred pins, Priority=Normal default; Subsystem
>   set per package/area as appropriate; State=Submitted on creation.
> - **Issue body shape** (always): (1) the test class file path and
>   method name(s) carrying the marker, (2) verbatim quote of the
>   WHEN-FIXED comment block from the test source so the issue is
>   self-contained, (3) one-line "what to do when the issue is fixed"
>   pointing at the test assertion that needs flipping, (4)
>   originating track reference (e.g., "Surfaced during Track NN").
> - **One commit per issue** (mechanical fix R8 from previous Phase A
>   iter-1): each issue creation + corresponding marker rewrite is its
>   own commit. The commit message names the YTDB ID (e.g., `WHEN-FIXED
>   marker rewrite for YTDB-1234 — TokenSignImpl readKeyFromConfig`).
>   This keeps the rewrite bisectable and makes per-issue revert
>   trivial if an issue is closed/reclassified post-PR.
> - **Mixed-marker file ordering** (mechanical fix T3 from previous
>   Phase A iter-1): when a test file carries WHEN-FIXED markers for
>   multiple logical fixes (e.g., `ScriptManagerTest`'s eight markers
>   mapping to ~5 distinct production issues), the rewrite step
>   processes them in **issue-order**, not file-order — i.e., one
>   commit per logical fix touches all files carrying that fix's
>   markers. The final cluster-disposition list from 22b is consumed
>   FIRST (so deleted-cluster markers are filtered out), THEN the
>   per-issue rewrites land in dependency order.
> - **No issues created during Pre-Flight or 22a** — the actual issue
>   creation and marker rewrite happen in 22c's steps.
> - **Verification step** (optional): final 22c step greps
>   `core/src/test/` for any unrewritten `// WHEN-FIXED: Track 22`
>   or `// WHEN-FIXED: deferred-cleanup track` markers; zero hits is
>   the gate. Any surviving marker means either the cluster was
>   deleted by 22b (and the marker was removed alongside the test
>   file — expected) or the rewrite missed a file (defect — fix in
>   the same step).
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
