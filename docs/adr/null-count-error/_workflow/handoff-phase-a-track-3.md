# Handoff — Track 3 Phase A (pre-decomposition pause)

**Paused at:** 2026-05-25T02:46Z, ctx=warning (32%).
**Phase:** A — Review + Decomposition.
**Track:** 3 — `clear()` pure-delta encoding.
**Branch:** YTDB-958-null-count-error.
**Plan dir:** `docs/adr/null-count-error/_workflow/`.

## State as committed

- **Track Pre-Flight gate completed** (commit `cdbeb0c088`): citation refresh applied in track-3.md + track-4.md; Strategy refresh ADJUST line under Track 2's block.
- **Reviews complete + iter-1 fixes applied** (uncommitted in current working tree): track-3.md carries the iter-1 fixes for all four user-resolved design decisions (Q1–Q4) plus the auto-applied A3/R4/A6 BLUF narrowing + staleness extension and the T4/T5 minor polish.
- **Iter-2 gate-checks PASS** on all three review types (Technical T1-T5, Risk R1-R6, Adversarial A1-A7). Decision Log + Outcomes & Retrospective rows written to track-3.md.
- **Two N1/N2 polish suggestions from Adversarial gate-check applied** (line-8 intro scope + soft-dependency wording on line ~124).

## What remains for the next session

**Single operation: decomposition + commit.** No new reviews; no new design decisions. The track file's `## Plan of Work` already names four logical edits; lift them into the `## Concrete Steps` roster with risk tags + the canonical `[ctx=<level>] Review + decomposition complete` Progress entry, then run the Phase A workflow update commit.

### Step decomposition (4 steps)

```markdown
## Concrete Steps

1. Add `IndexCountDelta.accumulateClearOrRecalibrate(AtomicOperation, int, long totalDelta, long nullDelta)` overload at `IndexCountDelta.java` — additive semantics (`+= totalDelta; += nullDelta`); runtime assert `|nullDelta| <= |totalDelta| && sign-aligned`; Javadoc names the four production callers (MV/SV `clear()`, MV/SV `buildInitialHistogram()`) and forbids per-put/per-remove use; Track 4 preflight: skip if it already exists. Add unit tests covering the additive composition, the assert trigger paths, and the four sign/magnitude bands — risk: medium (default: new overload + runtime assert on the durability-adjacent IndexCountDelta holder; no behavioral change to production paths until Steps 2/3 wire it)  [ ]
2. Rewrite `BTreeMultiValueIndexEngine.clear()` (lines 282–326) per Plan of Work Step 2: `clearSVTree(atomicOperation)` first → two emptiness asserts → snapshot reads `currentTotal`/`currentNull` under the per-tree lock → `indexesSnapshot.clear()` + `nullIndexesSnapshot.clear()` → `accumulateClearOrRecalibrate(op, id, -currentTotal, -currentNull)` → keep histogram `resetOnClear` block unchanged → drop the four direct writes → replace the obsolete rollback-hazard comment at lines 298–310 with the two-line post-Track-3 comment — risk: high (crash-safety: WAL-relevant clear() rewrite + concurrency: snapshot-read placement under per-tree lock is load-bearing for race-freedom against concurrent Hook B apply on the clearIndex API path)  [ ]
3. Rewrite `BTreeSingleValueIndexEngine.clear()` (lines 241–282) with the structurally identical change for the single-tree case per Plan of Work Step 3. Keep the method-level `try/catch (IOException)` wrap. SV's `persistCountDelta` ignores `nullDelta` (single tree stores nulls and non-nulls together) — the `nullDelta` half drives in-memory `approximateNullCount` apply only, persisted side moves by `totalDelta` alone — risk: high (crash-safety: same shape as Step 2 on SV; concurrency: same lock contract holds) *(parallel with Step 2)*  [ ]
4. Add `BTreeMultiValueIndexEngineClearRollbackTest` + `BTreeSingleValueIndexEngineClearRollbackTest` under `core/src/test/.../engine/v1/` (commit-path rollback via `RecordSerializationOperation` push pattern matching Track 2's `MainCommitCounterSyncTest:186-214`); activate `ClearIndexApiRollbackTest` (Track 2 Step 4 staged) with the four edits enumerated in Plan of Work Step 4 — remove `@Ignore` at lines 50-53, remove `fail()` tripwire at lines 131-135, wire `clearIndex(indexId)` call, wire reflective `histogramManager.resetOnClear` IOException stub. Assertions pin the post-rollback `(svTree, nullTree, in-mem total, in-mem null)` quad against the pre-clear values — risk: medium (test infrastructure: novel reflective histogramManager stub seam is local-to-test but acts as the validation gate for Steps 2/3 — failure here means Steps 2/3 silently shipped broken)  [ ]
```

### Ordering constraints

- Step 1 before Steps 2 and 3 (the rewrites need the overload).
- Step 2 and Step 3 are independent and may land in either order (different engines, different files); annotated `*(parallel with Step 2)*` on Step 3.
- Step 4 last (tests need Steps 2/3 to assert against).

### Cross-track signals for Track 4 Phase A

- The `accumulateClearOrRecalibrate` runtime assert `|nullDelta| <= |totalDelta|` holds structurally for clear (since `currentNull <= currentTotal`). Track 4 recalibration computes `nullDelta = exactNullCount - currentNull` and `totalDelta = (scannedNonNull + exactNullCount) - currentTotal` (MV) / `exactTotal - currentTotal` (SV). In pathological drift scenarios where the in-memory non-null and null counts drifted in **opposite directions** and the null drift dominates, the assert can fail. Track 4 Phase A should either (a) weaken the assert by dropping the magnitude clause at Track 4 land time, or (b) split the recalibration into two narrower calls, or (c) compose `(Δtotal, min(Δnull, |Δtotal| * sign(Δtotal)))`.

### Progress entry (write at decomposition-complete time)

After Concrete Steps land, append to `## Progress`:

```markdown
- [x] <ISO> [ctx=<level>] Review + decomposition complete
```

(Capture `<ISO>` via `date -u +%Y-%m-%dT%H:%MZ`; read `<level>` from `cat /tmp/claude-code-context-usage-$PPID.txt`.)

Replace the existing `- [>] 2026-05-25T02:46Z [ctx=warning] Phase A paused …` resume marker with the canonical decomposition-complete entry above (or keep the [>] line as a historical breadcrumb — choose based on the project's convention; the canonical D12 entry is the gate marker either way).

### Phase A workflow update commit (after decomposition + Progress entry land)

```bash
git add docs/adr/null-count-error/_workflow/plan/track-3.md
git rm docs/adr/null-count-error/_workflow/handoff-phase-a-track-3.md
git commit -m "Phase A review and decomposition for Track 3"
git push
```

Stage explicit paths only. The handoff file is deleted in the same commit (per `mid-phase-handoff.md` §Resume protocol — handoff cleanup belongs in the resuming commit).

## Files to read on resume

1. **`docs/adr/null-count-error/_workflow/plan/track-3.md`** — full track file. The 4 Phase-1 sections (Purpose, Context and Orientation, Plan of Work, Interfaces and Dependencies) carry the iter-1 fixes. Decision Log + Outcomes & Retrospective rows record the gate outcomes. Plan of Work Step 1-4 already names the work to be lifted into Concrete Steps.
2. **This file** for the decomposition shape + risk tags + ordering.
3. **`.claude/workflow/track-review.md` § Step Decomposition + § What You Do sub-steps 4–6** for the writing format and the Phase A commit shape.
4. **`.claude/workflow/risk-tagging.md`** — only if you want to second-guess the risk tag assignments above.

## What NOT to do on resume

- Do NOT re-spawn the reviews (Technical / Risk / Adversarial). Iter-2 gate-checks PASSED; their outcomes are durable in track-3.md `## Outcomes & Retrospective`.
- Do NOT re-ask the four design decisions (Q1–Q4). User resolutions are durable in `## Decision Log`.
- Do NOT re-run Track Pre-Flight. The gate fired on session start (commit `cdbeb0c088`); per the `**Strategy refresh:** ADJUST` line under Track 2's block in `implementation-plan.md`, the resume idempotency rule skips it.
- Do NOT touch implementation-plan.md or track-4.md. Track 3 decomposition is track-3-local.
