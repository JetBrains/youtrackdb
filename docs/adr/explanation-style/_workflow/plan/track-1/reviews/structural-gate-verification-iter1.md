<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: S1, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
flags: [CONTRACT_OK]
-->

# Structural gate verification — Track 1, iteration 1

Re-check of the one structural finding under disposition (S1, accepted recorded-only, deferred to Phase 4), plus a regression / completeness re-scan of the plan and both pending track files. No codebase read (plan-internal quality only).

## Verdicts

#### Verify S1: design↔plan count divergence (~50 vs ~54)
- **Original issue**: `design.md` reads `~50` enumeration sites throughout (the `## Subset sync across ~50 sites` heading at ~line 249 and the inventory at ~line 257); the plan and both track files carry the refined `~54` after the consistency review's CR1 corrected the undercount and named the two previously-missing closed-set flip sites (`commit-conventions.md`, `implementer-rules.md`). The frozen design side lags the plan at `~50`.
- **Fix applied**: none on the plan/track side — this is the expected frozen-design-lag state. S1 is recorded-only; the correction edits `design.md`, which is frozen after Phase 1, so it is deferred to the Phase-4 `design-final.md` reconciliation (which writes the as-built inventory at 54). Phase 2 does not mutate `design.md`.
- **Re-check**:
  - Plan / track file location: `implementation-plan.md:77` and `plan/track-1.md:32`.
  - Current state: the only two `~50` strings anywhere in the plan + track files are both inside the `**Full design**: design.md §"Subset sync across ~50 sites"` cross-reference. Each quotes the frozen `design.md` section heading verbatim, so they are correct anchors to the frozen section, not stale counts — an anchor must match the frozen heading exactly to resolve. Every count-bearing reference uses `~54` (plan: lines 22, 50, 59, 69, 71, 73-75, 133, 138, 141; track-1: lines 5, 9, 27-30, 68, 75, 86, 121, 138). The subset arithmetic stays internally consistent at 54: alternative C ("update only the ~10 sites the issues named") is written as leaving "~40 at four-of-five" (54 − ~10 ≈ 40), and the sub-counts (30 blurbs + 11 chat + 3 canonical + hook + test + 2 greps + catalogue + 2 grep-missed = the 54 the governance grep returns) reconcile to the whole.
  - Criteria met: DESIGN↔PLAN consistency — the plan/track side is internally uniform at `~54`; the frozen design.md `~50` is the only remaining stale side and is correctly an anchor target, not a count the plan must match pre-Phase-4. Cross-file consistency — no stray `~50` count survives in any plan/track location. The frozen-design-lag is the sanctioned posture: a frozen design lagging a refined plan is expected, and Phase 4 owns the as-built reconciliation to 54.
- **Regression check**: the deferral applied no plan/track edit, so it could not shift a problem. Re-scanned the two anchor cross-references (plan:77, track-1:32) to confirm they still point at the verbatim frozen heading (they do — required for resolution), and re-scanned every `~54`/`~40`/`~10` count site for an introduced inconsistency. Clean — no count drift, no orphaned anchor, no four-vs-five arithmetic break.
- **Verdict**: VERIFIED

## Regression / completeness re-scan

Independent re-scan of the plan's structural quality in the areas a fix would touch. No new structural blocker found.

- **Track sizing.** Track 1 (~54 in-scope files, over the ~20-25 split-candidate ceiling) carries its written over-ceiling sizing justification (`track-1.md:138`): it cannot split without reopening the four-vs-five window D1 forbids (the subset enumeration must flip atomically) and the `§1.7` opt-out + criteria-switch must land first (D6); the one independently-mergeable seam is Track 2. Track 2 (~4 in-scope files, under the ~12 merge-candidate floor) carries its written under-floor justification (`track-2.md:101`): folding into the already-over-ceiling Track 1 worsens the breach with no reviewability gain, and its review surface (regex false-positive calibration, both-axes cold-read coverage) is orthogonal to Track 1's atomic-flip focus. Both deviations are sanctioned by the argumentation gate — not findings.
- **Ordering / dependencies.** Track 2 `**Depends on:** Track 1` (`implementation-plan.md:146`) is matched by both tracks' Inter-track-dependency and ordering notes (`track-2.md:59,97`; `track-1.md:136`): Track 2's cold-read scans the `## Orientation` rule Track 1 adds, and its live edits rely on Track 1's `§1.7` opt-out. Single linear dependency, no cycle, no missing edge. Track 1's internal step ordering (opt-out + criteria-switch first; rule before any enumeration names it; subset flip atomic relative to Phase C) is stated and consistent (`track-1.md:81,88`).
- **Overlap-split.** The one shared file `readability-feedback/SKILL.md` carries reciprocal overlap notes in both tracks (`track-1.md:134`, `track-2.md:99`) citing `planning.md §"Justify any overlap-split"`: Track 1 owns the governance-grep flip, Track 2 owns the Rule-sync-map design-review row, in different sections of the file; the row cannot move into Track 1 without forward-referencing Track 2's not-yet-existent cold-read block. The split is justified and the two edit regions are disjoint.
- **Decision-Record completeness.** D1-D6 in the plan. Track 1 owns D1/D2/D3/D5/D6; Track 2 owns D4 (plus the D4b sub-record for the two regexes, which cross-references Track 1's D5/A9 for severity rather than re-deciding it). The distribution matches the assigned ownership exactly — no orphaned DR, no double-ownership, no DR named in the plan but absent from its owning track.
- **Bloat.** No superseded / withdrawn / deprecated / obsolete DR retained (zero matches across all three files). Plan 152 lines, track-1 138, track-2 101 — within budget for a full-tier two-track plan; per-section content is load-bearing (no padding-only sections).
- **§1.7 opt-out posture (D6).** `### Constraints` carries the prose opt-out note (`implementation-plan.md:21,26`); there is no `This plan is workflow-modifying:` marker and no `_workflow/staged-workflow/` subtree reference. This is the deliberate, documented posture per the branch-specific briefing — not a structural gap.

## Findings

(No new findings — pure-verdict pass.)

## Evidence base

(No codebase read — plan-internal quality only.)

## Summary

PASS. S1's deferral is sound: the plan and both track files are internally consistent at `~54`, the only `~50` strings are valid verbatim anchors into the frozen `design.md` heading, and leaving the frozen design side for the Phase-4 `design-final.md` reconciliation introduces no plan/track inconsistency. The regression / completeness re-scan found no missed structural blocker. No new findings.
