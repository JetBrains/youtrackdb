# Handoff: Phase 4 — Final artifacts

**Paused:** 2026-05-26
**Phase:** 4
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** eb1d647bd1 "YTDB-958: Mark Track 5 complete"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `docs/adr/null-count-error/_workflow/implementation-plan.md` — full plan, Plan Review `[x]`, Tracks 1-5 `[x]`, Final Artifacts `[>]`. Carries five track-completion episodes that survive into Phase 4 inputs.
- `docs/adr/null-count-error/_workflow/design.md` — original Phase 1 design (frozen — DO NOT edit). Uses pure-delta-for-`clear()` framing on both seams; the Track 5 retrofit landed mixed-mode on `clear()` after design.md froze. `design-final.md` MUST drop the pure-delta-for-`clear()` framing and present mixed-mode-on-both-seams as the as-built shape.
- `docs/adr/null-count-error/_workflow/plan/track-1.md` through `track-5.md` — five track files with full Episodes sections.
- `docs/adr/null-count-error/_workflow/design-mutations.md` — append-only mutation log; next Phase 4 commit appends one entry per `edit-design` invocation.
- No `design-mechanics.md` companion exists; Phase 4 uses `target=design` (no mechanics-final).
- Not a workflow-modifying plan (no `This plan is workflow-modifying:` marker in `### Constraints`); Phase 4 lands two commits (final-artifacts + cleanup), no promote-staged-workflow commit.

## Pending decision

Produce two final artifacts via Phase 4:

1. `docs/adr/null-count-error/design-final.md` — invoked via the `edit-design` skill with `mutation_kind=phase4-creation`, `target=design`, no `--plan-path` / `--plan-dir`. Reflects the as-built architecture (mixed-mode on both `clear()` and `buildInitialHistogram()` seams).
2. `docs/adr/null-count-error/adr.md` — written directly (not through the skill). Aggregates step + track episodes; restates Decision Records with final outcomes.

Commit both in one commit, then run the cleanup commit (`git rm -r docs/adr/null-count-error/_workflow/`), push, run self-improvement reflection, inform user.

## Verbatim re-present text

(none — Phase 4 is autonomous through Step 8; user only sees the final "Phase 4 complete" message)

## PSI-verified line citations (use these verbatim in design-final.md and adr.md)

Captured 2026-05-26 against HEAD `eb1d647bd1` via mcp-steroid PSI. The original `design.md` carries stale citations from the Phase 1 codebase snapshot; do NOT copy them. The values below are the authoritative reference for the new artifacts.

**AbstractStorage** (`core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java`):
- class declaration: line 208
- `commit(FrontendTransactionImpl)` override: line 2150; `commit(FrontendTransactionImpl, boolean)` main impl: line 2179
- `persistIndexCountDeltas(AtomicOperation)` [public]: line 2465
- `applyIndexCountDeltas(AtomicOperation)` [public]: line 2496; engine-mutator calls (Hook B's per-axis sum `getTotalDelta() + getInMemAdjustTotal()` and the null mirror) at lines 2538-2541
- `applyHistogramDeltas(AtomicOperation)` [public]: line 2552
- `setInError(Throwable)` [private]: line 1756; the `AssertionError` entry-point guard sits at the body's first executable lines
- `moveToErrorStateIfNeeded(Throwable)` [public]: line 3665
- `clearIndex(int)` [public]: line 3086
- `lockIndexes(SortedMap, AtomicOperation)` [private static helper]: line 5796 (caller invokes it from inside `commit(...)`)
- `endTxCommit(AtomicOperation)` [private]: line 4573
- `rollback(Throwable, AtomicOperation)` [private]: line 3660
- `logAndPrepareForRethrow(RuntimeException)` [protected]: line 5831 (does NOT call `setInError`)
- `logAndPrepareForRethrow(Error)` [protected final]: line 5851 (calls `setInError`)
- `logAndPrepareForRethrow(Throwable)` [protected final]: line 5870 (calls `setInError`)

**AtomicOperationsManager** (`.../paginated/atomicoperations/AtomicOperationsManager.java`):
- class declaration: line 54
- `endAtomicOperation(AtomicOperation, Throwable)`: line 258 (Hook A and Hook B live inside; Hook A's catch is `RuntimeException | AssertionError` because `persistIndexCountDeltas` declares no `IOException`)
- `executeInsideAtomicOperation(TxConsumer)`: line 157
- `calculateInsideAtomicOperation(TxFunction<T>)`: line 130
- `executeInsideComponentOperation(AtomicOperation, StorageComponent, TxConsumer)`: line 184
- `calculateInsideComponentOperation(AtomicOperation, StorageComponent, TxFunction<T>)`: line 218
- `releaseLocks(AtomicOperation)`: line 452 (the inner-finally call site that closes Hook B's lock-held window)
- `tryApply(Runnable, String)`: line 444 (helper that wraps each apply branch in the cache-only `RuntimeException | AssertionError` log-and-swallow)

**IndexCountDelta** (`core/.../index/engine/IndexCountDelta.java`):
- class declaration: line 34
- four long fields: `totalDelta` at line 37, `nullDelta` at line 40, `inMemAdjustTotal` at line 53, `inMemAdjustNull` at line 60
- four getters: `getTotalDelta` at 62, `getNullDelta` at 66, `getInMemAdjustTotal` at 70, `getInMemAdjustNull` at 74
- three accumulators:
  - `accumulate(AtomicOperation, int engineId, int sign, boolean isNullKey)` [public static]: line 88 — the per-put / per-remove `±1` accumulator
  - `accumulateClearOrRecalibrate(AtomicOperation, int, long, long)` [public static]: line 141 — has zero production callers post-Track-5 retrofit; only Javadoc references it from line 179. Body and the `|nullDelta| <= |totalDelta|` + sign-alignment precondition stay in place pending follow-up cleanup.
  - `accumulateInMemRecalibration(AtomicOperation, int, long, long)` [public static]: line 195 — no precondition (in-mem-only deltas are arbitrarily signed). Sole accumulator on the in-mem side for both `clear()` and `buildInitialHistogram()`.

**BTreeMultiValueIndexEngine** (`core/.../index/engine/v1/BTreeMultiValueIndexEngine.java`):
- class declaration: line 36 (`final` class)
- fields: `svTree` at 44, `nullTree` at 46, `indexesSnapshot` at 48, `nullIndexesSnapshot` at 50, `name` at 52, `id` at 53, `histogramManager` [volatile] at 56, `approximateIndexEntriesCount` [AtomicLong] at 64, `approximateNullCount` [AtomicLong] at 69, `firstUnderflowDumped` [AtomicBoolean] at 78
- `clear(Storage, AtomicOperation)`: line 283 (mixed-mode body inlines `svTree.setApproximateEntriesCount(op, 0L)` + `nullTree.setApproximateEntriesCount(op, 0L)` per tree, then `IndexCountDelta.accumulateInMemRecalibration(op, id, -currentTotal, -currentNull)` at the in-mem-side site)
- `buildInitialHistogram(AtomicOperation)`: line 641
- `addToApproximateEntriesCount(long)` [public]: line 730
- `addToApproximateNullCount(long)` [public]: line 739
- `reportAndClampUnderflow(String, AtomicLong, long, long)` [package-private]: line 767 (relaxed from private for the failed-CAS engine-level regression test)
- `persistCountDelta(AtomicOperation, long, long)`: line 790
- `load(IndexEngineData, AtomicOperation)`: line 216
- `clearSVTree(AtomicOperation)` [private]: line 164
- `doClearTree(CellBTreeSingleValue<CompositeKey>, AtomicOperation)` [private]: line 169

**BTreeSingleValueIndexEngine** (`core/.../index/engine/v1/BTreeSingleValueIndexEngine.java`):
- class declaration: line 37 (`final` class)
- fields: `sbTree` at 43, `indexesSnapshot` at 44, `name` at 45, `id` at 46, `histogramManager` [volatile] at 48, `approximateIndexEntriesCount` [AtomicLong] at 55, `approximateNullCount` [AtomicLong] at 61, `firstUnderflowDumped` [AtomicBoolean] at 70
- `clear(Storage, AtomicOperation)`: line 242 (mixed-mode body inlines `sbTree.setApproximateEntriesCount(op, 0L)` then `IndexCountDelta.accumulateInMemRecalibration(op, id, -currentTotal, -currentNull)`)
- `buildInitialHistogram(AtomicOperation)`: line 656
- `addToApproximateEntriesCount(long)` [public]: line 735
- `addToApproximateNullCount(long)` [public]: line 744
- `reportAndClampUnderflow(String, AtomicLong, long, long)` [package-private]: line 772
- `persistCountDelta(AtomicOperation, long, long)`: line 795 (ignores `nullDelta` because the single tree stores nulls and non-nulls together; the in-mem null counter recalibrates via `countNulls(atomicOperation)` during `load()`)
- `load(IndexEngineData, AtomicOperation)`: line 185
- `doClearTree(AtomicOperation)` [private]: line 142

**Production caller counts (PSI find-usages, scoped to `/src/main/`):**
- `IndexCountDelta.accumulate` (±1 form): 5 callers — two on its own Javadoc at IndexCountDelta.java:102 and 123, three at `VersionedIndexOps.java:74/82/135` (the per-put / per-remove hot path).
- `IndexCountDelta.accumulateClearOrRecalibrate`: 1 caller (Javadoc only at IndexCountDelta.java:179) — **zero production callers**, confirming the Track 5 retrofit moved both `clear()` sites to the new accumulator. Deferred cleanup work: `@Deprecated(forRemoval=true)` + body removal + `IndexCountDeltaHolderTest` fixture migration (12+ sites).
- `IndexCountDelta.accumulateInMemRecalibration`: 7 callers — three in its own Javadoc at IndexCountDelta.java:44/110/118, plus four production sites: `BTreeMultiValueIndexEngine.java:361` (`clear()`) and `:715` (`buildInitialHistogram()`); `BTreeSingleValueIndexEngine.java:320` (`clear()`) and `:720` (`buildInitialHistogram()`).
- Engine mutators `addToApproximate{Entries,Null}Count` on both engines: 0 direct production callers — they are reached via the `BTreeIndexEngine` interface dispatch from `AbstractStorage.applyIndexCountDeltas` (Hook B) at lines 2538-2541.

## What design-final.md must say (vs the frozen design.md)

- **Drop the pure-delta-for-`clear()` framing.** The original design.md presented `clear()` as pure-delta and `buildInitialHistogram()` as the mixed-mode counterpart. Track 5 retrofitted `clear()` to mixed-mode for symmetry, closing the drift-amplification accepted regression that pure-delta-for-`clear()` carried. The as-built shape is mixed-mode on both seams: persisted side gets inline absolute writes (`setApproximateEntriesCount(op, 0L)` for clear, `setApproximateEntriesCount(op, target)` for buildInitialHistogram), in-mem side routes through `IndexCountDelta.accumulateInMemRecalibration` consumed by Hook B post-commit.
- **Single accumulator for both seams.** Invariant 1 (in-mem mutates only after WAL commit succeeds) is enforced by the same accumulator method (`accumulateInMemRecalibration`) at both `clear()` and `buildInitialHistogram()` sites. `accumulateClearOrRecalibrate` survives in the source as a callerless API pending follow-up cleanup (documented as awaiting-cleanup).
- **The WAL invariant target is the in-mem `AtomicLong` write specifically**, which Hook B serializes post-commit on both seams. Any persisted-side write inside the atomic op is WAL-tracked and revertable, so it is allowed by Invariant 1.
- **Drift-healing posture is symmetric across seams.** On every successful `clear()` or `buildInitialHistogram()`, the persisted EP page lands at the absolute target regardless of prior in-mem-vs-persisted drift. The pure-delta-for-`clear()` accepted-drift-amplification regression no longer applies.
- **Sequence diagram stays.** Hook A (persist) before `commitChanges`; Hook B (apply + histogram apply) after `commitChanges` but before the inner-finally `releaseLocks`. Persist failure converts to rollback via the catch in Hook A. The diagram in design.md is structurally correct.
- **Cascade containment (three-layer) stays.** Layer 1: broadened catch at the pre-`endTxCommit` site plus four broadened `AtomicOperationsManager` wrapper catches. Layer 2: `setInError(Throwable)` `AssertionError` entry-point guard. Layer 3: four engine-mutator clamp+error rewrites (`reportAndClampUnderflow` with shared per-engine `firstUnderflowDumped` latch).
- **Alternative analysis section** ("Why pure-delta, not collection-style self-healing") needs revision to "Why mixed-mode, not pure-delta-on-both-sides or collection-style self-healing": cover the drift-amplification reason for rejecting pure-delta on the persisted side at recalibration / clear sites, in addition to the original cost / semantics / split-tree arguments against collection-style self-healing.
- **Bug C remains out of scope.** SV `load()` unconditional null reset stays in YTDB-953.

## What adr.md must aggregate

The eight-section template per `prompts/create-final-design.md`:

1. **Summary** — one-paragraph what / why / what changed.
2. **Goals** — adjusted for actual outcomes. Note that Track 5 retrofitted `clear()` to mixed-mode after the original Phase 1 framing.
3. **Constraints** — note: persisted-side drift no longer amplifies on `clear()` (a tighter constraint than the original design carried, surfaced and closed during Track 5).
4. **Architecture Notes** § Component Map — updated Mermaid + bullet list reflecting actual topology.
5. **Architecture Notes** § Decision Records:
   - D1 (formerly "pure-delta encoding over collection-style self-healing"): rewrite to "mixed-mode encoding over pure-delta-on-both-sides or collection-style self-healing". Restate alternatives + rationale + risks/caveats + where-implemented (use prose / SHA references, not Track-N labels).
   - D2 (single lifecycle gate over manual+hooks coordination): restate; line citations refreshed.
   - D3 (histogram delta gets the same lifecycle gate): restate.
   - D5 (containment lands first, in one track → recast as "containment is the foundation layer"): restate, drop the Track-1 label.
   - D6 (Bug C out of scope): restate.
   - D4 is absent from the plan; do not add.
   - Consider a new D7 capturing the inline-replan that added the `clear()` mixed-mode retrofit after Phase B of the original four-track plan, IF the retrofit's existence as a separate landed change is worth preserving in durable history. Otherwise fold it into D1's "where implemented" prose.
6. **Architecture Notes** § Invariants & Contracts — restate Invariants 1, 2, 3; refresh wording (Invariant 3 carries the bifurcated-lock-posture acknowledgment: main-commit path holds the per-index lock during Hook B; standalone `clearIndex` API and `buildHistogramAfterFill` paths do not, additive `AtomicLong.addAndGet` semantics make ordering harmless on those paths).
7. **Architecture Notes** § Integration Points — restate per the actual surface (the two accumulator methods, Hook A / Hook B in `endAtomicOperation`, deleted manual calls in `AbstractStorage.commit`).
8. **Architecture Notes** § Non-Goals — Bug C, PaginatedCollectionV2 dual-counter, YTDB-952, XD-1272, per-put EP-page I/O migration.
9. **Key Discoveries** — synthesise from all five tracks' Episodes blocks (especially: the `BTreeIndexEngine` interface dispatch making engine mutators reachable only through Hook B; the cross-engine asymmetry between MV's two-tree split and SV's single-tree null-counter recalibration at `load()`; the lock-window analysis that motivated the lifecycle gate; the bifurcated-lock-posture observation between main-commit and standalone paths; the drift-amplification closure via the Track 5 retrofit; the `IndexHistogramManager.cache` and `indexesSnapshot` eager-mutation residue still out of scope).

Apply ephemeral-identifier rule to both files: no `Track N`, no `Step M`, no review-finding IDs (`F1`, `WC4`, `CQ1`, etc.), no iteration counters (`iteration 1`, `round 2`). Cite work by file:line, commit SHA, or DR ID that adr.md itself restates.

## Resume notes

- **Do NOT redo:**
  - PSI verification (already in this handoff — use the line citations directly).
  - Reading the five track files or `design.md` (already absorbed; the salient deviations from Phase 1 design are listed under "What design-final.md must say").
  - Re-deriving caller counts (PSI numbers are recorded above).
- **Do NOT modify** `design.md`, `implementation-plan.md` (except marking `## Final Artifacts` from `[>]` to `[x]` after the cleanup commit), or any `plan/track-*.md` file.
- **Do NOT** call `Write` / `Edit` directly on `design-final.md` — invoke the `edit-design` skill via Skill tool with: `mutation_kind=phase4-creation`, `design_path=docs/adr/null-count-error/design-final.md`, `design_mechanics_path=null`, `target=design`, no `plan_path` / `plan_dir`. The skill writes the file, runs the mechanical script + the cold-read sub-agent, iterates, and appends to `design-mutations.md`.
- **Direct `Write`** is fine for `adr.md` (the skill does not own that artifact).
- **Between artifacts, re-check context level.** If warning or critical after `design-final.md` commits, write a second handoff scoped to "adr.md pending" (note: at that point the durable artifact `design-final.md` already exists, so the second handoff is shorter than this one).
- **Phase 4 commit shape (non-workflow-modifying plan, two commits):**
  1. `Add final design and ADR` — stages `docs/adr/null-count-error/design-final.md` + `docs/adr/null-count-error/adr.md`. Push.
  2. `Remove workflow scaffolding` — `git rm -r docs/adr/null-count-error/_workflow/`. Push.
- **After Step 6 (cleanup) lands:** run `self-improvement-reflection.md`. Phase 4 friction inputs worth reviewing: the warning-level context hit at this pause (potential reflection on whether the create-final-design.md prompt under-budgets context for the read-and-PSI-verify phase); the cross-cutting decision to handoff before drafting design-final.md.
- **Mark `## Final Artifacts` from `[>]` to `[x]`** in the plan file only WHEN the cleanup commit deletes `_workflow/`. Order: edit the plan to flip `[>]` → `[x]`, stage it, AND `git rm -r _workflow/` in the same `Remove workflow scaffolding` commit so the flipped state survives the squash-merge intact (the plan file itself is removed, but the squashed commit message reflects the completed state).
  - Actually, since the plan file is REMOVED by the cleanup commit, the `[>]` → `[x]` flip is moot — the plan file does not survive into `develop`. Leave the plan file as `[>]` if it is more honest, or flip to `[x]` for tidiness inside the branch's draft PR view. Either way the final state on `develop` carries only the durable artifacts.
- **Tell the user** Phase 4 is complete after the cleanup commit and reflection. List any YouTrack issues reflection created. Do NOT run `gh pr ready`.

## See also

- `.claude/workflow/prompts/create-final-design.md` — the eight-step Phase 4 prompt (read once at session resume).
- `.claude/skills/edit-design/SKILL.md` — invoke via Skill tool with the inputs listed above.
- `.claude/output-styles/house-style.md` — applies to both `design-final.md` and `adr.md` per `.claude/workflow/conventions.md` §1.5.
- `.claude/workflow/ephemeral-identifier-rule.md` — read before drafting; both artifacts are durable.
