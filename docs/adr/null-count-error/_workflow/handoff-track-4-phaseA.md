# Handoff: Phase A — Track 4 iteration-2 gate-check pending

**Paused:** 2026-05-25
**Phase:** A
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** 1932ba1f73 "Apply iteration-2 amendments to Track 4 for (β) mixed-mode encoding"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `docs/adr/null-count-error/_workflow/plan/track-4.md` — full iteration-2 amendment set applied at commit `1932ba1f73`. H1 title is "mixed-mode encoding"; Purpose / Big Picture, Context and Orientation (arithmetic walkthrough plus `### Clarifications`), Plan of Work (4-step shape: IndexCountDelta plumbing → MV rewrite → SV rewrite → regression test + existing-test rewrite), Validation and Acceptance, and Interfaces and Dependencies all reflect (β).
- `docs/adr/null-count-error/_workflow/implementation-plan.md` — D1 has the "Update after Track 4 Phase A" paragraph; Invariant 3 wording acknowledges the bifurcated lock posture; Goals paragraph reframed as "delta-mediated in-mem writes" with the per-track split; Track 4 checklist entry updated (title, intro paragraph, scope `~4 steps`, depends-on text).

## Pending decision

No user decision pending. The next session has approval to spawn the iteration-2 gate-check sub-agents (Technical + Adversarial in parallel), apply gate-check verdicts, decompose into 4 steps on PASS, and commit the Phase A workflow update.

## What ran this session

- Resolution of the iteration-1 handoff: handoff file, PAUSED marker, and MEMORY.md bullet deleted at commit `0c04bbcb5b`.
- Branch Divergence Check: clean (0 ahead, 0 behind).
- Workflow Drift Check: user chose **Defer** for 1 commit on develop touching `.claude/workflow/**` since fork point `3410c961`. Recorded as TaskCreate todo "Deferred workflow drift: 1 commits since 3410c96".
- MCP Steroid preflight: reachable, project `null-count-error` open, cwd match.
- PSI verification: `BTreeMultiValueIndexEngine`, `BTreeSingleValueIndexEngine`, `IndexAbstract`, `AbstractStorage`, `AtomicOperationsManager`, `AtomicOperation`, `IndexCountDelta`, `IndexCountDeltaHolder`, `HistogramDelta`, `HistogramDeltaHolder`, `IndexHistogramManager` all resolved. `accumulateInMemRecalibration` method confirmed absent; `inMemAdjustTotal` / `inMemAdjustNull` fields confirmed absent; `accumulateClearOrRecalibrate` (Track 3) confirmed present at `IndexCountDelta.java:111–125`.
- Source-file line citations re-verified against current HEAD: MV body 604–654 (recalibration 650–653, comment 643–649, null-tree scan 631–641, bucket reads 613–614); SV body 615–651 (recalibration 647–650, comment 639–646, bucket read 624 + countNulls 625); `AbstractStorage.applyIndexCountDeltas` at method line 2496 with engine-mutator calls at lines 2529–2530.
- Iteration-2 amendments applied to `track-4.md` and `implementation-plan.md` via `steroid_apply_patch` (9 hunks across 2 files atomically); H1 title and Goals paragraph follow-on fixes applied via a second `steroid_apply_patch` (2 hunks).
- Iteration-2 amendments committed at `1932ba1f73` and pushed.
- Context check after the push returned `level=warning` (32%), triggering this handoff.

## Verbatim re-present text — iteration-1 findings to pass to the iteration-2 gate-check

The iteration-1 reviews returned verdict RE-RUN with the findings below. The iteration-2 amendments (commit `1932ba1f73`) implement the resolutions. Pass the findings under `findings` to the gate-check sub-agents per the `prompts/review-gate-verification.md` template; pass `previous_findings` as empty (no earlier finalised iterations).

### Technical Review (iteration 1, verdict RE-RUN)

- **T1 [blocker]** — Q3 (runtime assert relaxation). Original recommendation: option (b) — introduce separate `accumulateRecalibration` overload without preconditions. **Resolution applied:** superseded by (β). The new `accumulateInMemRecalibration` method has no precondition by design; `accumulateClearOrRecalibrate` stays unchanged. Verify: track-4.md `### Clarifications` "Q3 supersession" bullet plus Plan of Work Step 1.
- **T2 [should-fix]** — Existing positive `verify(...setApproximateEntriesCount(...))` assertions in `BTreeEngineHistogramBuildTest.java` (lines 107, 132, 166, 206–207, 240, 279–280, 540–542, 566–568, 600). **Resolution applied:** under (β) the persisted-side `verify(...setApproximateEntriesCount...)` Mockito assertions stay valid (mixed-mode keeps inline absolute writes); only the in-mem-side `assertEquals(target, f.engine.getTotalCount(f.op))` assertions migrate to the holder-inspection pattern. Captured in Plan of Work Step 4 and Interfaces and Dependencies in-scope file list.
- **T3 [should-fix]** — Mockito option (1) (test-side `executeInsideAtomicOperation` lambda with re-throw). Production-side prerequisite (IOException catch in `IndexAbstract.buildHistogramAfterFill:401–408`) does NOT apply (the test invokes AOM directly, bypassing `buildHistogramAfterFill`). **Resolution applied:** captured in `### Clarifications` "Mockito recipe option (1) accepted" bullet and Plan of Work Step 4 (test catches the rewrapped `StorageException` per `AtomicOperationsManager.java:147` / `:174`).
- **T4 [should-fix]** — Lock posture option (a) (accept Q5-style deferred follow-up; rely on narrow call-graph of `buildHistogramAfterFill`). PSI confirms `IndexAbstract.buildHistogramAfterFill:400` is the only production caller. **Resolution applied:** captured in `### Clarifications` "Lock posture option (a) accepted" bullet; Q5 follow-up YouTrack issue inherited (single ticket under `YTDB` with `dev-workflow` tag covers both engines and both seams).
- **T5 [suggestion]** — Add snapshot-invariant assert matching Track 3's `clear()` precedent. **Resolution applied:** both Plan of Work Step 2 (MV) and Step 3 (SV) embed `assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal` with engine `name` + `id` in the message; captured in `### Clarifications` "Snapshot-invariant assert (T5)" bullet.
- **T6 [suggestion]** — Update parallel SV comment block at `BTreeSingleValueIndexEngine.java:639–646` (Plan of Work originally mentioned only the MV equivalent). **Resolution applied:** Plan of Work Step 3 explicitly directs the SV comment block update at lines 639–646; line citation refreshed in `### Clarifications` (the original handoff said 639–645 but the actual block extends to 646).

### Adversarial Review (iteration 1, verdict RE-RUN)

- **A1 [blocker] — RESOLVED via user's (β) Mixed-mode encoding decision.** Pure-delta on both sides would transfer drift to the persisted side instead of healing it. **Resolution applied:** track-4.md keeps inline `setApproximateEntriesCount(op, target)` writes on the persisted side, routes only in-mem `AtomicLong` writes through the new accumulator. Plan of Work Step 1 adds `IndexCountDelta.accumulateInMemRecalibration` + `inMemAdjustTotal` / `inMemAdjustNull` fields + Hook B sum in `AbstractStorage.applyIndexCountDeltas`. D1 update paragraph in implementation-plan.md records the bifurcation.
- **A2 [should-fix]** — Q3 option (b) recommended. **Resolution applied:** superseded by (β) — the new in-mem-only accumulator is structurally distinct from the clear accumulator.
- **A3 [should-fix]** — Histogram CHM cache divergence: structurally-impossible claim true only for count counters. **Resolution applied:** Validation and Acceptance scopes the claim to in-mem count counters; Non-Goals row added covering `IndexHistogramManager.cache.put` at lines 763, 806, 831; captured in `### Clarifications` "Histogram CHM cache divergence (A3) is out of scope" bullet.
- **A4 [should-fix]** — Concurrent surface: `ProductionAllocatorConcurrencyMTTest.java:660–661` already exercises concurrent `buildInitialHistogram`. Under (β), in-mem additive composition plus per-tree-locked persisted writes preserve convergence. **Resolution applied:** captured in `### Clarifications` "Concurrent-surface review (A4)" bullet with the additive composability rationale; Plan of Work Step 4 reviews the test against the new arithmetic before committing.
- **A5 [should-fix]** — Mockito option (1) edge cases: rewrap as `StorageException`, not `IOException`; precondition assert concern evaporates under (β). **Resolution applied:** rewrap note captured in `### Clarifications` "Mockito recipe option (1) accepted" bullet and Plan of Work Step 4.
- **A6 [suggestion]** — Reword Invariant 3 to acknowledge bifurcated lock posture. **Resolution applied:** implementation-plan.md Invariant 3 rewritten to cover the main-commit path (lock held) and the `clearIndex` API / `buildHistogramAfterFill` paths (no lock; additive `addAndGet` semantics make ordering harmless).
- **A7 [suggestion]** — Post-Track-3 line citations PSI-verified accurate. No fix needed. Verify the citations in `### Clarifications` "Line citations refreshed" remain consistent.
- **A8 [suggestion]** — Regression test Javadoc: scope rollback assertion to count counters only; call out CHM cache as out-of-scope. **Resolution applied:** Plan of Work Step 4 includes the Javadoc scoping clause.

## Resume notes

- **Do NOT redo:**
  - Iteration-1 reviews (Technical + Adversarial): findings catalogued above; do not re-spawn the iteration-1 reviewers.
  - A1 design decision: user picked (β); decision is final.
  - Iteration-2 amendments: applied and committed at `1932ba1f73`; do not re-apply via `steroid_apply_patch`.
  - PSI class-name verification: ran this session; results captured above.
  - Source-file line citation re-verification: ran this session; results in the "What ran this session" block.

- **Next actions on resume** (in order):
  1. Verify HEAD is at or descends from `1932ba1f73`; verify `docs/adr/null-count-error/_workflow/plan/track-4.md` and `docs/adr/null-count-error/_workflow/implementation-plan.md` carry the iteration-2 amendments.
  2. Spawn two iteration-2 gate-check sub-agents in parallel using `prompts/review-gate-verification.md`:
     - **Technical**: `findings` = T1–T6 verbatim from above; `previous_findings` = empty; `review_type` = `technical`.
     - **Adversarial**: `findings` = A1–A8 verbatim from above; `previous_findings` = empty; `review_type` = `adversarial`.
     - Shared inputs: `plan_path` = `docs/adr/null-count-error/_workflow/implementation-plan.md`; `step_file_path` = `docs/adr/null-count-error/_workflow/plan/track-4.md`; `track_name` = "Track 4: buildInitialHistogram mixed-mode encoding"; `codebase_path` = repository root; `prior_episodes` = the Track 1, 2, 3 episodes from the plan file.
     - Each sub-agent prompt MUST instruct: "use mcp-steroid PSI find-usages, not grep, for reference-accuracy questions" per `conventions.md` §1.4 sub-agent delegation rule.
  3. On both gate-checks returning PASS:
     - Append two `## Outcomes & Retrospective` entries to `track-4.md`:
       - `- [x] Technical: PASS at iteration 2 (6 findings, all VERIFIED)`
       - `- [x] Adversarial: PASS at iteration 2 (8 findings, all VERIFIED)`
     - Write the `## Concrete Steps` roster (per the Plan of Work 4-step shape) with risk tags: Step 1 medium, Step 2 high, Step 3 high, Step 4 medium. Use the per-step descriptions from the Plan of Work.
     - Read `/tmp/claude-code-context-usage-$PPID.txt` and parse `level=`; append a single `## Progress` entry `- [x] <ISO> [ctx=<level>] Review + decomposition complete`.
     - Commit "Phase A review and decomposition for Track 4" staging `track-4.md` only (implementation-plan.md amendments already landed at `1932ba1f73`). Push.
     - Resolve this handoff: delete `handoff-track-4-phaseA.md`, remove the PAUSED marker in `track-4.md`, remove the MEMORY.md bullets carrying this handoff filename. Commit "Resume and complete Phase A for Track 4". Push.
     - Run self-improvement reflection.
     - End the session per Phase A Completion protocol; instruct the user to `/clear` and re-run `/execute-tracks` to start Phase B.
  4. On gate-check FAIL (any STILL OPEN or REGRESSION verdict): iterate per `review-iteration.md`. This is iteration 2 of the 3-iteration budget; iteration 3 is the final.

- **Open questions for the gate-check sub-agents:** none load-bearing — the (β) decision is final, the amendment set is on disk, the line citations are PSI-verified.

- **Deferred drift residue:** the session-start Workflow Drift Check produced a TaskCreate todo "Deferred workflow drift: 1 commits since 3410c96" (Defer resolution). The next session's startup must re-detect the drift via its own check; the todo only carries the count and short-SHA.
