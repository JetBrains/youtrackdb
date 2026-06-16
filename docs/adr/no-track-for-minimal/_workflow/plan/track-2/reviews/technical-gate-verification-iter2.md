<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 2, suggestion: 0}
index:
  - {id: T5, sev: should-fix, loc: "track-2.md D4 inventory (lines 62-68) + Plan-of-Work step 2; track-code-review.md:427-436", anchor: "### T5 ", cert: "Premise P7 / Integration I2", basis: "track-code-review.md carries the same standalone §1.7(b) staged-read block the track re-points elsewhere; left reading plan ### Constraints"}
  - {id: T6, sev: should-fix, loc: "track-2.md D4 tier-line-reader list (lines 62-68) + Plan-of-Work steps 1-2; implementation-review.md:185-193", anchor: "### T6 ", cert: "Premise P8 / Integration I3", basis: "implementation-review.md reads the D18 tier line from the plan that D4 relocates to the ledger; the read is uncovered"}
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
overall: FAIL
flags: [CONTRACT_OK]
-->

# Track 2 technical gate verification — iteration 2

Re-verification of the four iteration-1 findings against the edited track file,
plus a re-grep of every live `.claude/workflow/**` and `.claude/skills/**`
consumer of the removed plan sections, the §1.7 marker, and the tier line to
confirm the widened (now 22-file) inventory is exhaustive. All four prior
findings are VERIFIED. The re-grep surfaced two remaining marker/tier-line
reads that are in scope by filename but uncovered by the Plan-of-Work and the
D4 inventory — the same inventory-incompleteness class as the iteration-1
blocker. Overall **FAIL**: the gate loops until T5 and T6 are closed.

This is a workflow-machinery review. Per the §1.7(l) block in
`technical-review.md`, every named reference is verified as a workflow path or
`§`-anchor via grep + Read (not `findClass`), and the staged copy is read in
preference to live for the four Track-1 files that have one
(`workflow.md`, `conventions.md`, `conventions-execution.md`, `planning.md`,
`create-plan/SKILL.md`); every other consumer Track 2 touches has no staged
copy yet and is read live.

## Evidence base

#### Premise P1: Track 1 ledger fields `s17` and `paused` are bare space-rejecting tokens (T2 substrate)
- **Track claim**: D8 Risks/Caveats — "Track 1's ledger `paused` field is a bare space-rejecting token, so it cannot hold the literal `**PAUSED `."
- **Search performed**: grep + Read of the staged precheck
  `_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh`.
- **Code location**: precheck `:56` (key set `{phase, track, tier, categories, s17, paused}`), `:1581-1582` (`reject_bad_ledger_value "s17" … bare` / `"paused" … bare`), `:1529` (SPACE rejected in any bare-token field), `:1603-1604` (emit `s17=`/`paused=`).
- **Actual behavior**: `s17` and `paused` are validated as `bare`; a space is rejected. The literal `**PAUSED <date> …` carries spaces, so the field cannot hold it.
- **Verdict**: CONFIRMED

#### Premise P2: `determine_state` reads only `phase` and `track`, never `paused`/`s17` (T2 substrate)
- **Track claim**: D8 — "Track 1 writes but never reads `paused` (`determine_state` reads only `phase`/`track`)."
- **Search performed**: grep + Read of the staged precheck.
- **Code location**: precheck `:1755 determine_state_from_ledger`, calling `ledger_tail_value "phase"` (`:1766`) and `ledger_tail_value "track"` (`:1785`); no `ledger_tail_value "paused"` or `"s17"` exists.
- **Actual behavior**: only `phase` and `track` are tail-resolved for state. `paused`/`s17` are written but never read by the precheck.
- **Verdict**: CONFIRMED — substantiates T2's "machine-read by determine_state is not delivered by this track" note.

#### Premise P3: `--append-ledger` subcommand exists with the claimed signature (T2/D11 substrate)
- **Track claim**: D11/step 4 — escalation appends the upgraded tier via "an `--append-ledger` call."
- **Search performed**: grep + Read of the staged precheck.
- **Code location**: precheck `:120` usage, `:139 --append-ledger)` selector, `:1567 append_ledger()`.
- **Actual behavior**: `--append-ledger [--ctx …] [--phase …] [--track …] [--tier …] [--categories …] [--s17 …] [--paused …]` is a real, mutually-exclusive-with-`--mode` subcommand.
- **Verdict**: CONFIRMED

#### Premise P4: `mid-phase-handoff.md` carries the `^\*\*PAUSED ` recovery grep and the secondary `**PAUSED ` marker (T2 substrate)
- **Track claim**: D8 — the `^\*\*PAUSED `-anchored recovery grep matches the literal `**PAUSED ` marker; step 3 extends that grep to scan `paused=`.
- **Search performed**: grep + Read of live `mid-phase-handoff.md`.
- **Code location**: `:167` (`**PAUSED <YYYY-MM-DD> at <phase-state> …`), `:171-173` (`grep -rn '^\*\*PAUSED ' …` recovery), `:159/:161` (the two secondary-marker rows beneath `## Plan Review`/`## Final Artifacts`).
- **Actual behavior**: the marker and the anchored recovery grep both exist as the track describes; the incompatibility T2 records is real.
- **Verdict**: CONFIRMED

#### Premise P5: `inline-replanning.md` reads the tier line and resets `## Plan Review` (T1/D11 substrate)
- **Track claim**: step 2 re-points inline-replanning's descriptive tier-line *read* prose; step 4 re-points its `:153/:212` `## Plan Review` reset.
- **Search performed**: grep + Read of live `inline-replanning.md`.
- **Code location**: `:151` ("read the tier line in `implementation-plan.md`"), `:153` and `:212` (`## Plan Review` reset).
- **Actual behavior**: matches the track's pinned references; the read and the reset both exist.
- **Verdict**: CONFIRMED — substantiates T1 VERIFIED.

#### Premise P6: conventions §1.7(c) read-side still says "the implementer reads `### Constraints`" (T4 substrate)
- **Track claim**: Surprises + step 2 — §1.7(c) read-side is stale and must move to ledger-first.
- **Search performed**: grep + Read of the staged `conventions.md` §1.7(c).
- **Code location**: staged `conventions.md` §1.7(c) `:1056-1063` ("the implementer reads `implementation-plan.md`'s `### Constraints` section and checks for the marker sentence").
- **Actual behavior**: the read-side is plan-`### Constraints`-anchored; the §1.7(b) Marker-home text at `:994-1007` already declares the ledger `s17` as the canonical home and says "Track 2 re-points the consumers." The §1.7(c) read-side is the consumer prose the carve-out must align.
- **Verdict**: CONFIRMED — substantiates T4 VERIFIED.

#### Premise P7: `track-code-review.md` carries the same standalone §1.7(b) staged-read block the track re-points elsewhere (T5)
- **Track claim**: the track lists `track-code-review.md` in scope only for the completion-episode (step 5) and the deferred-write resume signal; it is NOT in the D4 §1.7-marker-reader inventory (lines 62-68) and NOT in step 2's marker re-point list.
- **Search performed**: grep for `carries the canonical` + `## Staged-read precedence` across live `.claude/workflow/**` + `.claude/skills/**`; Read of `track-code-review.md`.
- **Code location**: `track-code-review.md:427-436` (`## Staged-read precedence (workflow-modifying plans)` — "When the plan's `### Constraints` carries the canonical `§1.7(b)` … marker sentence … Without the marker this caveat is inert: read the live path") and `:251-253` (the step-8 staged-delta prep gated on the same plan-`### Constraints` read). The grep returns 9 distinct standalone-block carriers; `track-code-review.md` is one of them.
- **Actual behavior**: this is the identical construct the track explicitly re-points in the two gate-recheck prompts (`dimensional-review-gate-check.md`, `review-gate-verification.md`) and the three §1.7(l) prompts. Staged `conventions.md` §1.7(b) `:1000-1003` and `design.md:373` name `track-code-review` as a staged-read-precedence marker consumer to re-point onto the ledger `s17`. Phase C runs in every tier (`workflow.md:92` + staged `workflow.md` Session-Lifecycle TOC `3C` in all tiers), so on a `minimal` workflow-modifying branch — which has no plan `### Constraints` (the YTDB-1125 problem this branch fixes) — the block reads no marker, goes inert, and the Phase-C reviewer reads live workflow files instead of the staged copies, comparing the branch's edits against develop and reporting the phantom mismatches the block's own comment warns against.
- **Verdict**: WRONG (incomplete) — the file is in scope but its marker read is not re-pointed.

#### Premise P8: `implementation-review.md` reads the D18 tier line from the plan that D4 relocates (T6)
- **Track claim**: step 1 covers `implementation-review.md` only for the D7 write-side (audit summary → `plan-review.md`, review state → ledger); the D4 tier-line-reader inventory (lines 62-68) names `inline-replanning`, `track-review`, `create-final-design`, `consistency-review` — not `implementation-review`.
- **Search performed**: grep for tier-line readers across live `.claude/workflow/**` + `.claude/skills/**`; Read of `implementation-review.md:182-211`.
- **Code location**: `implementation-review.md:184-193` — "Before launching Step 1, read the **D18 tier line** from `implementation-plan.md` … The same line is read on every entry … both read it from the always-present plan." `:199-211` keys the per-tier pass matrix off that read.
- **Actual behavior**: under D4 the tier line "leaves the plan" (staged `conventions.md` `:313-314`, the `## Plan Review` disposition: "The tier line and matched categories also move to the ledger (D4), so the former `**Change tier:**` line leaves the plan"). `implementation-review.md`'s orchestrator-level tier-line read (distinct from the `consistency-review` prompt read the track does re-point) is a plan read of relocated content, uncovered by the Plan-of-Work, and the surrounding "always present … from the always-present plan" prose now contradicts D4.
- **Verdict**: WRONG (incomplete) — the file is in scope but its tier-line read is not re-pointed.

#### Integration I1: removed-section consumers fully inventoried after the scope widened (iter-1 blocker class)
- **Plan claim**: Surprises — "All five [newly-found consumers] are now in scope"; the 22-file scope is exhaustive.
- **Actual entry point**: `grep -rn '## Plan Review' / '## Final Artifacts'` over live `.claude/workflow/**` + `.claude/skills/**`, cross-checked against the staged Track-1 copies.
- **Caller analysis**: every live hit resolves to an in-scope file (`inline-replanning`, `structural-review` orchestration doc, `mid-phase-handoff`, `implementation-review`, `workflow-drift-check`, `plan-slim-rendering`, `execute-tracks`/`review-plan` SKILLs, `workflow.md`) OR to a Track-1 staged file already transformed (`conventions.md:91/298/311/316`, `create-plan/SKILL.md:834-835` mark the sections removed) OR to a non-consumer illustrative mention (`migrate-workflow/SKILL.md:544` — a rename-pattern *example*, not a live reader). The five iter-1 additions are present in the Surprises log and the D4 inventory.
- **Breaking change risk**: the removed-section inventory itself is now exhaustive — the iter-1 blocker is closed.
- **Verdict**: MATCHES

#### Integration I2: §1.7-marker staged-read-block consumers (T5 surface)
- **Plan claim**: D4 — "the reader inventory must be exhaustive"; the marker readers are the enumerated list at lines 62-68.
- **Actual entry point**: `grep -rln 'carries the canonical'` returns nine standalone §1.7(b) staged-read-block carriers.
- **Caller analysis**: eight are covered (`adversarial`/`risk`/`technical`/`structural`/`consistency-review`, `dimensional-review-gate-check`, `review-gate-verification`, `step-implementation`). The ninth, `track-code-review.md`, is in scope by filename but its marker read is unaddressed (see P7).
- **Breaking change risk**: a `minimal` workflow-modifying branch's Phase-C reviewer reads live instead of staged.
- **Verdict**: CALLERS AT RISK

#### Integration I3: tier-line readers (T6 surface)
- **Plan claim**: D4 — tier-line readers re-point to the ledger.
- **Actual entry point**: `grep -rln 'tier line|Change tier'` returns `create-plan/SKILL.md` (Track-1 writer), `conventions.md` (Track-1 definer), `implementation-review.md`, `inline-replanning.md`, `consistency-review.md`, `create-final-design.md`, `track-review.md`.
- **Caller analysis**: all are covered except `implementation-review.md`'s orchestrator-level tier-line read (see P8), which selects the Phase-2 pass matrix before any prompt is spawned and is distinct from the `consistency-review` read the track re-points.
- **Breaking change risk**: Phase-2 pass selection reads a tier line that D4 moved out of the plan.
- **Verdict**: CALLERS AT RISK

## Verdicts on prior findings

- **T1 [should-fix] — VERIFIED.** The track's Plan-of-Work step 2 (`:234`) now
  re-points "`inline-replanning`'s descriptive tier-line *read* prose to the
  ledger," and D4's reader inventory lists `inline-replanning` among the
  tier-line readers. Premise P5 confirms the read exists at
  `inline-replanning.md:151`. Resolved.
- **T2 [should-fix] — VERIFIED.** D8 Risks/Caveats (`:106-117`) and step 3
  (`:251-258`) now take D8's second arm: extend the `mid-phase-handoff` recovery
  grep to scan the ledger for `paused=`, drop the dead `**PAUSED`-prefix arm as
  infeasible, and explicitly scope the `determine_state` read out
  ("`determine_state` is Track 1's precheck, out of scope"). Premises P1, P2, P4
  confirm every load-bearing fact: the bare-token rejection of spaces, that
  `determine_state` reads only phase/track, and that the live recovery grep is
  `^\*\*PAUSED `-anchored. Recoverability now rests on the extended grep plus the
  unchanged handoff file. Resolved.
- **T3 [suggestion] — VERIFIED.** Step 6 (`:280-281`) now states "Much of this is
  deleting stale forward-pointers and stale-checkbox references, not
  re-routing — Track 1 already moved the model," correctly characterizing the
  `workflow.md` work. Resolved.
- **T4 [suggestion] — VERIFIED.** The §1.7(c) read-side carve-out is now in the
  Surprises log (`:40-46`), the Plan-of-Work step 2 (`:239-240`), the
  `## Context and Orientation` consumer list (`:206-210`), the In-scope files
  list (`:374-376`), the Out-of-scope carve-out note (`:382-385`), and a
  Validation line (`:327-329`). Premise P6 confirms the read-side is stale today
  and the §1.7(b) Marker-home text already expects Track 2 to align it.
  Resolved.

## Findings

### T5 [should-fix]
**Certificate**: Premise P7 / Integration I2
**Location**: `track-2.md` D4 §1.7-marker-reader inventory (lines 62-68) and
Plan-of-Work step 2 (the marker re-point list) — `track-code-review.md` is
listed in scope only for the completion episode (step 5) and the deferred-write
resume signal, not for its §1.7(b) staged-read block; source
`track-code-review.md:427-436` (the `## Staged-read precedence` block) and
`:251-253` (the step-8 staged-delta prep gated on the same plan-`### Constraints`
read).
**Issue**: `track-code-review.md` carries the *same standalone §1.7(b)
staged-read block* the track explicitly re-points in the two gate-recheck
prompts and the three §1.7(l) review prompts (the track's own wording, lines
65-66 / 171-173, names this block as the re-point construct). That block — and
the step-8 staged-delta prep that shares its gate — reads the plan's
`### Constraints` for the canonical marker. Under D4 the marker moves to the
ledger `s17`, and on a `minimal` workflow-modifying branch there is no plan
`### Constraints` at all (the YTDB-1125 gap this branch closes). Phase C runs in
every tier (`workflow.md:92`; staged `workflow.md` Session-Lifecycle covers
`3C` in all tiers), so on a `minimal` branch the block finds no marker, goes
inert ("read the live path"), and the Phase-C reviewer compares the branch's
staged edits against develop's live files — the phantom-mismatch failure the
block's own comment warns against, and the step-8 delta prep silently skips.
Staged `conventions.md` §1.7(b) (`:1000-1003`) and `design.md:373` both name
`track-code-review` as a staged-read-precedence marker consumer to re-point onto
the ledger. This is the same inventory-incompleteness class as the iteration-1
blocker, surfaced by the widened scope.
**Proposed fix**: add `track-code-review.md`'s `## Staged-read precedence` block
(and the step-8 staged-delta-prep gate that reads the same marker) to the D4
§1.7-marker-reader inventory (lines 62-68) and to Plan-of-Work step 2's marker
re-point list, with the same ledger-first / plan-`### Constraints`-fallback read
the other staged-read blocks get. Add a Validation line asserting a `minimal`
workflow-modifying branch's Phase-C reviewer applies staged-read precedence from
the ledger `s17`. (The file is already in scope, so this is a description and
decomposition amendment, not a new in-scope file.)

### T6 [should-fix]
**Certificate**: Premise P8 / Integration I3
**Location**: `track-2.md` D4 tier-line-reader inventory (lines 62-68) and
Plan-of-Work steps 1-2 — `implementation-review.md` is covered only for the D7
write-side (audit summary → `plan-review.md`, review state → ledger), not for
its tier-line read; source `implementation-review.md:184-193` and the per-tier
pass matrix at `:199-211`.
**Issue**: `implementation-review.md` reads the D18 tier line directly from
`implementation-plan.md` "on every entry … from the always-present plan"
(`:184-193`) to select the Phase-2 pass matrix, before it spawns the
`consistency-review` / `structural-review` prompts. This orchestrator-level read
is distinct from the `consistency-review` tier-line read the track does
re-point in step 1. Under D4 the tier line "leaves the plan" and moves to the
ledger (staged `conventions.md:313-314`), so this read targets relocated
content and the surrounding "the tier line is always present … from the
always-present plan" prose now contradicts D4. The D4 reader inventory (lines
62-68) lists the tier-line readers but omits `implementation-review`.
**Proposed fix**: add `implementation-review.md`'s tier-line read to the D4
tier-line-reader inventory and to Plan-of-Work step 1 (or step 2), re-pointing
it to the ledger tier line with the same ledger-first / plan-fallback pattern,
and update the "always present … from the always-present plan" prose at
`:189-193` to read from the ledger. (In scope already; description and
decomposition amendment.)

**FAIL**
