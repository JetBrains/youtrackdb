# Gate report — Track 7 closeout hygiene fixes (iteration 1)

- **Commit under review**: `f5c162225b` ("Strip ephemeral workflow IDs and pin freeze-arm premise
  in Track 7 code"), branch `transactional-schema`, HEAD at review time.
- **Scope**: read-only verification of the four reviewer-prescribed fixes — BG9 (baseline track
  review), CN46 (concurrency track review), CQ11, CQ12 (baseline track review). No Maven run
  (comments/javadoc + plan-file diff only; behavior-neutrality was established by the full core
  unit suite + coverage gate run in the fixing episode).
- **Verdict summary**: 4/4 VERIFIED. No new findings. One cosmetic observation (non-gating).

---

## Item 1 — BG9 (strip ephemeral workflow-ID labels): VERIFIED

**Prescription**: production comments in `core/src/main` must not cite ephemeral workflow labels
(Q-A*/Q-B*/FM-A*/BG*/CN*/CS*/V1/V2/V8) that resolve only inside `_workflow/**` (pruned at ADR
merge). Each rewrite must be self-contained and technically faithful. Exclusions: `Dekker`
(algorithm name), `_workflow/**`, test code.

**Evidence — completeness (current tree grep)**:
- `grep -rE '\b(Q-A[0-9]|Q-B[0-9]|FM-A[0-9]+b?|BG[0-9]+|CN[0-9]+|CS[0-9]+|CQ[0-9]+|Q-[A-Z][0-9]*|FM-[A-Z][0-9]*|OBS[0-9]+)\b' core/src/main/` → **zero matches** (parser-generated code excluded; none there either).
- `V1/V2/V8` token sweep of `core/src/main` → only pre-existing legitimate uses, all unrelated to
  Track 7 labels: `RawPair.java:23` (generic type params `V1`,`V2`), `LiveQueryHook.java:30` /
  `LiveQueryHookV2.java:38` (live-query dispatcher versions), `EdgeTraversal.java:348-349`
  (thread V1/V2 in a race narrative predating this track), `PaginatedCollectionStateV2.java:49`
  (collection format version). No Track 7 file retains a V-ordering label.
- `Dekker` retained (17 occurrences) — legitimate algorithm name per the exclusion list.

**Evidence — fidelity (pre-image vs post-image, per `git show f5c162225b`)**: all 20 rewritten
sites checked; every label was either dropped with its inline rationale already self-contained,
or expanded to the prose contract it named. Spot list:

| Site | Rewrite | Faithful? |
|---|---|---|
| `DatabaseSessionEmbedded.java:280` | "Q-A2 pool-teardown skip detection" → "pool-teardown skip detection" | yes |
| `DatabaseSessionEmbedded.java:318-321` | "the Q-A2 skip condition" → "the pool-teardown skip condition" | yes |
| `DatabaseSessionEmbedded.java:3424-3429` | "The Q-A2 skip path" → "The pool-teardown skip path" | yes |
| `DatabaseSessionEmbedded.java:3876` | "V2 mandatory ordering (the Dekker pair's formal soundness depends on it)" → "Mandatory ordering (…)" — rationale kept, label dropped | yes |
| `DatabaseSessionEmbedded.java:3972`, `:4007` | "Q-A2" dropped from owner-as-completer / skip-detection javadoc, contract prose intact | yes |
| `DatabaseSessionEmbeddedPooled.java:79` | "The Q-A2 skip" → "The pool-teardown skip" | yes |
| `MetadataWriteMutex.java:124` | "FM-A7 / Q-A5:" dropped; stranded-acquisition/self-deadlock rationale intact | yes |
| `MetadataWriteMutex.java:162` | "Q-A3 pin (3):" dropped; interrupt-restore/killable-waiter rationale intact | yes |
| `FrontendTransactionImpl.java:84`, `:165`, `:184`, `:1032` | "Q-A2" dropped at all four volatile/skip/completer sites; contracts intact | yes |
| `OperationsFreezer.java` (9 sites) | "V1" → "entrant ordering"/"arm ordering", "V8" → "retract ordering", "Q-B4" → "bounded thundering herd" (already named), "BG8" → "user-ruled 2026-07-23" with the contract stated inline, "V1/V8 orderings" → "arm/retract orderings" | yes |
| `AbstractStorage.java:6711`, `:6721` | "Q-B3 shared-helper pin" → "one shared helper"; "(Q-B5)" dropped, message-contract prose intact | yes |

No technical content was lost or mangled in any rewrite: each pre-image already carried the full
rationale alongside the label, and only the label was removed/replaced.

**Verdict: VERIFIED.**

---

## Item 2 — CN46 (freeze-arm load-bearing-premise pin): VERIFIED

**Prescription**: pin at `AbstractStorage.freeze()` stating (a) it is the only production
operator-freeze registration site, (b) it registers under `stateLock.readLock`, (c) this is the
belt making the in-freezer gates/checkpoints 3-4 production-reachable only via this path and
making the three unarmed failure-path undo helpers safe; plus cross-referencing notes at the
three helpers describing the unarmed entry under mutex + schema-WL + IM-lock +
`stateLock.writeLock`.

**Evidence — pin present and accurate**:
- Pin text at `AbstractStorage.java:5822-5834` ("LOAD-BEARING PREMISE: this is the ONLY
  production site that registers an operator freeze, and it does so while holding
  stateLock.readLock … A future operator-freeze registration added on a path that does NOT hold
  stateLock.readLock would break both belts"). It names both belts — (a) the in-freezer loop-top
  and park-decision gates + operator-arm wake, (b) the three undo helpers by name — and warns
  against future non-readLock registrations.
- **Premise checked against code — holds**:
  - `freeze()` (`AbstractStorage.java:5804`) takes `stateLock.readLock().lock()` at :5806, and
    both `FreezeKind.OPERATOR` registrations (:5835-5840) execute inside that try block, before
    the matching unlock.
  - Exhaustive caller sweep of `freezeWriteOperations` in `core/src/main`: the only
    `FreezeKind.OPERATOR` call sites are `AbstractStorage.java:5835/5840` (inside `freeze()`).
    All other production callers pass `TRANSIENT_QUIESCE` (`DiskStorage.java:361`, `:1256`;
    `AbstractStorage.java:5645`). The "ONLY production site" claim is exact.
- **Helper notes present and accurate** (`AbstractStorage.java:3630-3634` in
  `revertCreatedCollectionStructure`, `:3894-3896` in `restoreReconciledDroppedIndexEngines`,
  `:3968-3970` in `revertCreatedIndexEngineStructure`): each states the UNARMED freezer entry,
  the held-lock set, the readLock-exclusion safety argument, and cross-references `freeze()`.
- **Lock-set claim checked against code — holds**: the three helpers are reached only via
  `undoSchemaCarryRegistryPublication` (:3093, :3148 — the commit finally's failure branch and
  the `endTxCommit` failure catch inside `applyCommitOperations`), which runs inside
  `commitSchemaCarry`'s four-lock window: mutex engaged from the first schema write (per the
  comment at :3278-3279), `acquireSchemaWriteLock` :3280, `acquireExclusiveLockForCommit` :3282,
  `stateLock.exclusiveLockWithAbort` (write bit) :3294, with `stateLock.writeLock().unlock()` in
  the enclosing finally — i.e. the helpers really run with mutex + schema-WL + IM-lock +
  stateLock write lock held.
- **Unarmed claim checked against code — holds**: all three helpers call
  `atomicOperationsManager.executeInsideAtomicOperation(...)` (:3636, :3920, :3986), which routes
  through the one-arg `startToApplyOperations` (`AtomicOperationsManager.java:128-130`) →
  `startToApplyOperations(op, false, null)` → `writeOperationsFreezer.startOperation(false,
  null)` — schema-unarmed, no gate supplier.

**Verdict: VERIFIED.**

---

## Item 3 — CQ11 (`isEngagedBy` javadoc): VERIFIED

**Prescription**: the javadoc must stop claiming a nonexistent production consumer ("the
engage-order assertion") and accurately state the accessor is test-only.

**Evidence**:
- New javadoc at `MetadataWriteMutex.java:224-227`: "Test-observability only — it has no
  production callers and is not part of the engage or release protocol; the release path keys on
  the {@code (session, ordinal)} CAS, not on this probe."
- Caller sweep of `isEngagedBy` across `core/src`: 23 call sites, **all** in
  `core/src/test/.../MetadataWriteMutexTest.java`; zero production callers. The javadoc claim is
  factually correct, and the added release-path clarification matches the release implementation
  (the `(session, ordinal)` CAS claim in `MetadataWriteMutex`).

**Verdict: VERIFIED.**

---

## Item 4 — CQ12 (track-7.md bookkeeping): VERIFIED

**Prescription**: tick the "Step implementation" Progress checkbox; add the missing CS13
protection-boundary line to the track file.

**Evidence** (`docs/adr/transactional-schema/_workflow/plan/track-7.md`):
- Progress checkbox ticked: line 21 `- [x] Step implementation` (was `- [ ]` before
  `f5c162225b`, per the commit diff).
- CS13 line present: lines 138-147 under "Outcomes & Retrospective" — the pool-teardown skip
  protection boundary, explicitly labeled as "the pass-14 CS13 record, carried here as the
  durable track-file line the note asked for", enumerating the teardown whitelist (mark + log
  only), the forbidden mutations, the owner-as-completer rule, and the I-C3 invariant it
  preserves. (Workflow labels inside `_workflow/**` are in the BG9 exclusion zone by design.)

**Verdict: VERIFIED.**

---

## Non-gating observation

- **OBS (cosmetic)**: the BG9 rewrite at `DatabaseSessionEmbedded.java:3427-3429` left an
  awkward short-line wrap ("… so this pass can / never release / a live foreign commit's
  permit."). Spotless passed on the fixing commit (Eclipse formatter does not rewrap comment
  prose), so this is purely cosmetic; fold into any future edit of that comment.

## Verdict table

| Item | Prescription source | Verdict |
|---|---|---|
| BG9 — strip ephemeral workflow IDs | baseline-track-iter1 (t58.e1) | VERIFIED |
| CN46 — freeze-arm premise pin + 3 helper notes | concurrency-track-iter1 (t56.e2) | VERIFIED |
| CQ11 — `isEngagedBy` javadoc | baseline-track-iter1 (t58.e1) | VERIFIED |
| CQ12 — track-7.md checkbox + CS13 line | baseline-track-iter1 (t58.e1) | VERIFIED |

Gate result: **PASS** — all four closeout fixes landed as prescribed in `f5c162225b`; no
follow-up required.
