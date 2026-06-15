<!--MANIFEST
producer: reviewer-plan
review_kind: consistency
phase: 2
tier: full
plan_path: docs/adr/no-track-for-minimal/_workflow/implementation-plan.md
design_path: docs/adr/no-track-for-minimal/_workflow/design.md
tracks_reviewed: [track-1, track-2]
iteration: 1
findings: 1
prefix: CR
verdict: changes-requested
index:
  - id: CR1
    sev: should-fix
    anchor: "### CR1 [should-fix]"
    loc: "implementation-plan.md Component Map + track-2.md D4 reader inventory + Interfaces and Dependencies"
    cert: "Ref: §1.7(b)-marker readers (staged-read precedence block)"
    basis: design-decision
    axis: design-plan
evidence_base:
  refs: 11
  flows: 1
  invariants: 2
  verdicts: { matches: 13, mismatches: 0, partial: 1, not_found: 0 }
tooling: "grep + Read against live .claude/** files; no staged mirror exists (ls of _workflow/staged-workflow returns nothing), so all reads resolve to live files. No Java/Kotlin symbols in scope — PSI not applicable. grep is exact for bash function defs and markdown headings, so no reference-accuracy caveat attaches."
-->

## Findings

### CR1 [should-fix]
**Certificate**: Ref: §1.7(b)-marker readers (staged-read precedence block)
**Location**: `implementation-plan.md` Component Map ("the §1.7(c)/(l) marker readers … land in **Track 2**") and `plan/track-2.md` D4 reader inventory + `## Interfaces and Dependencies` in-scope file list. Code: `.claude/workflow/prompts/dimensional-review-gate-check.md:40` and `.claude/workflow/prompts/review-gate-verification.md:41`.
**Issue**: Two active workflow consumers read the canonical §1.7(b) workflow-modifying marker from the plan's `### Constraints` and are absent from Track 2's reader inventory and from both tracks' in-scope file lists. D4 moves the §1.7 marker to the ledger; D5 removes `### Constraints` from the thinned `lite`/`full` plan entirely. After the change, these two prompts still instruct their reader to look for the marker in `### Constraints`, where it will no longer live — the exact failure D4 names as its central risk ("a missed reader silently reads a stale or absent fact"). The reader inventory is therefore not exhaustive.
**Evidence**: Track 2's D4 (`track-2.md:44-48`) enumerates the marker readers as `inline-replanning`, `track-review`, `create-final-design`, `consistency-review`, the three §1.7(l) review prompts (`technical`/`risk`/`adversarial`), and the implementer §1.7(c) gate (`step-implementation`, `implementer-rules`). A live grep for the canonical §1.7(b)-marker-read sentence ("When the plan's `### Constraints` carries the canonical `§1.7(b)` workflow-modifying marker sentence …") across `.claude/workflow/`, `.claude/skills/`, `.claude/agents/` returns nine files: seven review prompts plus `track-code-review.md`. Six of the seven prompts (`adversarial`, `consistency`, `risk`, `structural`, `technical`, plus `track-code-review`) are in Track 2's in-scope list. The two that are not — `dimensional-review-gate-check.md` (Phase 3B/3C gate-check, roles `reviewer-dim-step,reviewer-dim-track`; staged-read block at line 40) and `review-gate-verification.md` (Phase 3A re-check, roles `orchestrator,reviewer-technical,reviewer-risk,reviewer-adversarial`; staged-read block at line 41) — appear nowhere in the plan, the design, or either track file (confirmed: a grep for both names across all four artifacts returns no reference). The five **tier-line** readers, by contrast, are exhaustively covered (implementation-review, consistency-review, create-final-design, track-review, inline-replanning — all in Track 2); the gap is specific to the §1.7(b)-marker (staged-read precedence) readers. The covered prompts carry both a §1.7(l) "Workflow-machinery criteria" block (named in D4) and the §1.7(b) staged-read block (technical-review.md:111 + :113), so re-pointing those files plausibly sweeps both reads; but the two missed files carry only the standalone staged-read block, with no in-scope file to ride along on.
**Proposed fix**: Add `.claude/workflow/prompts/dimensional-review-gate-check.md` and `.claude/workflow/prompts/review-gate-verification.md` to Track 2's `## Interfaces and Dependencies` in-scope list, and name them in D4's reader inventory (`track-2.md:44-48`) and in the Plan-of-Work step 2 marker-read re-point. The design.md half — the Component Map node "§1.7 marker readers" and design §"The phase ledger" D4 prose — is frozen; record the as-built completion there in Phase 4 (`design-final.md`), since design.md is not mutated during execution.
**Classification**: design-decision
**Justification**: A reader the plan missed that reads the §1.7 marker but is not in Track 2's inventory is a missed consumer; per the §Classification rules, a "missed consumer/reader" and a scope expansion the user should ratify route to `design-decision` ("when in doubt, choose design-decision"). The correct fix touches D4's inventory and Track 2's scope, not a single unambiguous text rename.

## Evidence base

### Plan ↔ Code

#### Ref: `determine_state` plan-checkbox parse and two-level lookup
- **Document claim**: `track-1.md` §Context (lines 161-169) — `determine_state` (around line 1439) reads `implementation-plan.md`, parses the `## Plan Review` first-checkbox token via `section_first_checkbox_token`, walks `## Checklist` for the first `[ ]` track, and reads the track file's `## Progress` for within-track sub-state (two-level lookup, plan D3/D6, design §"Resume routing").
- **Search performed**: grep `determine_state`/`section_first_checkbox_token` + Read `workflow-startup-precheck.sh:1439-1560`.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:1439` (def), `:1456` (`section_first_checkbox_token "$plan_file" "Plan Review"`), `:1467-1528` (`## Checklist` walk for first `[ ]` track), `:1535-1537` (`determine_c_substate "$track_file"` reads `## Progress`).
- **Actual signature/role**: `determine_state()` at line 1439; absent plan → State 0; first-checkbox-token parse of "Plan Review"; Checklist walk records first `[ ]` `Track <N>:`; State C sub-state from the track file. The two-level lookup is exactly as described.
- **Verdict**: MATCHES
- **Detail**: Line number "around 1439" is exact. design.md:212 "three-checkbox parse" (Plan Review + Checklist track + Final Artifacts) is also accurate (`section_first_checkbox_token` for "Plan Review" at :1456 and "Final Artifacts" at :1553, Checklist walk between).

#### Ref: CLI `--mode` case and `--bootstrap-sha`/`--exclude-sha`
- **Document claim**: `track-1.md`:166-168 — CLI surface is a `--mode {full,divergence-only,migrate-range}` `case` near line 60 with `--bootstrap-sha` and repeatable `--exclude-sha`.
- **Search performed**: grep `--mode`/`divergence-only`/`migrate-range`/`--bootstrap-sha`/`--exclude-sha` + Read `:55-92`.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:59-80` (arg `case` with `--mode`, `--bootstrap-sha`, repeatable `--exclude-sha` accumulator), `:85-86` (mode validation `full | divergence-only | migrate-range`).
- **Actual signature/role**: arg-parse `case` starts at line 60; mode-validation `case` at 85. `--exclude-sha` accumulates into a space-delimited list (line 71). Matches.
- **Verdict**: MATCHES
- **Detail**: "near line 60" is exact for the arg `case`.

#### Ref: `detect_drift` folds `_workflow/**` stamps
- **Document claim**: `track-1.md`:169 — `detect_drift` (around line 474) folds the stamp of `_workflow/**` artifacts.
- **Search performed**: grep `detect_drift` + Read `:474-596`.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:474` (def), `:488-491` (Phase-1 walk over `implementation-plan.md`, `design.md`, `design-mechanics.md`, `plan/track-*.md`), `:524` (`fold_stamps_to_base`).
- **Actual signature/role**: `detect_drift()` at 474; byte-copied §1.6(h) walk gathers stamps from the four artifact globs and folds via merge-base. Matches.
- **Verdict**: MATCHES
- **Detail**: "around line 474" is exact. The walk does not include `research-log.md` (consistent with D13: the ledger and research-log are unstamped, not folded).

#### Ref: conventions.md §1.6(f) stamped-artifact exclusions name `research-log.md`
- **Document claim**: D13 (`track-1.md`:135-145, plan:268-276) — the ledger joins the §1.6(f) exclusion list alongside `research-log.md`; §1.6(f) is the stamped-artifact-types-and-exclusions list.
- **Search performed**: grep §1.6(f)/research-log + Read `conventions.md:735-768`.
- **Code location**: `.claude/workflow/conventions.md:735` (`### (f) Stamped artifact types and exclusions`), `:754` (`_workflow/research-log.md` in the "Explicitly NOT stamped" list).
- **Actual signature/role**: §1.6(f) positively enumerates stamped artifacts (impl-plan, design, design-mechanics, track-*.md) and lists the exclusions (final artifacts, design-mutations.md, research-log.md). The research-log precedent D13 cites is present.
- **Verdict**: MATCHES
- **Detail**: D13's "research-log precedent" and §1.6(f) target are both accurate current-state.

#### Ref: conventions.md §1.6(h) Phase-1 stamp walk
- **Document claim**: `track-1.md`:176, plan D13 — §1.6(h) is the Phase-1 stamp walk; the ledger is not folded into the §1.6(h) walk.
- **Search performed**: grep §1.6(h)/"Phase 1 walk" + Read `conventions.md:787-823`.
- **Code location**: `.claude/workflow/conventions.md:787` (`### (h) Phase 1 walk bash block`), `:812-815` (the four-glob walk: impl-plan, design, design-mechanics, track-*.md).
- **Actual signature/role**: §1.6(h) is the declared single source for the Phase-1 walk; the script's `detect_drift`, migrate-range, and no-drift recompute all conform to it. Matches.
- **Verdict**: MATCHES
- **Detail**: D13's claim that adding the ledger to the walk would require editing all three script sites + the conformance fixture is consistent with §1.6(f):761-765.

#### Ref: §1.7(k) criterion 1 text
- **Document claim**: D12 (`track-1.md` D-set; plan:256-266; design:360-371) — §1.7(k) criterion 1 disqualifies any plan that moves a resume-state field or adds a track-file section.
- **Search performed**: grep §1.7(k)/criterion + Read `conventions.md:1204-1262`.
- **Code location**: `.claude/workflow/conventions.md:1231-1241` — "Opt-out criteria … (1) It changes **no `_workflow/**` artifact schema** — no track-file section, resume-state field, drift-gate format, or stamp format moves."
- **Actual signature/role**: criterion 1 fails for any plan that moves a track-file section or resume-state field. D3/D6 move the resume-state field (plan checkboxes → ledger); D9 adds a track-file section. Each independently fails criterion 1, so the branch stages under §1.7(b). The plan's paraphrase is faithful.
- **Verdict**: MATCHES
- **Detail**: D12's reading is the literal criterion-1 text. The canonical §1.7(b) workflow-modifying marker (conventions.md:1247-1253 is the §1.7(k) opt-out marker; the §1.7(b) marker is the distinct one this plan's `### Constraints` carries) is correctly NOT used.

#### Ref: create-plan Step 1c resume routing on plan/design presence
- **Document claim**: D10 (`track-1.md`:121-133, plan:231-242, design:227-235) — today Step 1c routes on whether `design.md`/`implementation-plan.md` exist; a `minimal` resume with neither hits the "Neither file exists — fresh start" branch and re-runs research/tier/gate.
- **Search performed**: grep "Step 1c"/"Neither file"/"fresh start" + Read `create-plan/SKILL.md:131-208`.
- **Code location**: `.claude/skills/create-plan/SKILL.md:131` (`Step 1c — Tier-aware resume check`), `:137` (the `ls design.md implementation-plan.md` probe), `:203-208` ("Neither file exists — fresh start … Step 4's classifier re-runs").
- **Actual signature/role**: Step 1c branches on the presence pair; the neither-exists branch is a fresh start that re-runs the aim/classifier/gate. D10's rewire premise is accurate current-state.
- **Verdict**: MATCHES
- **Detail**: D10's edge case (Step 4 gate cleared but no ledger entry → reads as fresh start) is internally consistent with this branch.

#### Ref: mid-phase-handoff secondary `**PAUSED` markers under `## Plan Review` / `## Final Artifacts`
- **Document claim**: D8 (`track-2.md`:69-83, plan:204-215, design:174-179) — the Phase-2/State-0 and Phase-4 secondary `**PAUSED` markers sit beneath `## Plan Review` and `## Final Artifacts` today; A/B/C pauses stay in track `## Progress`; Phase 0/1 and ad-hoc stay "none"; the recovery grep is `grep -rn '^\*\*PAUSED '`.
- **Search performed**: grep PAUSED/Plan Review/Final Artifacts + Read `mid-phase-handoff.md:150-174`.
- **Code location**: `.claude/workflow/mid-phase-handoff.md:159` (`2 (State 0) | beneath ## Plan Review heading`), `:161` (`4 | beneath ## Final Artifacts heading`), `:160` (`A / B / C | track file Progress section`), `:158`/`:162` ("0 / 1" and "Ad-hoc research" → none), `:173` (recovery grep `^\*\*PAUSED `).
- **Actual signature/role**: the secondary-marker table is exactly as D8 describes, including the per-phase row assignments and the greppable prefix. Matches.
- **Verdict**: MATCHES
- **Detail**: D8's risk note (recovery grep must cover the ledger or keep the `**PAUSED` prefix) is consistent with line 173.

#### Ref: tier-line reader inventory (the "five")
- **Document claim**: design.md:27,100,172,339,373 — "the five tier-line readers"; plan Component Map + Track 2 cover them.
- **Search performed**: grep "Change tier"/tier-line across `.claude/workflow/`, `.claude/skills/`, `.claude/agents/`.
- **Code location**: tier-line reads in `implementation-review.md`, `consistency-review.md`, `create-final-design.md`, `track-review.md`, `inline-replanning.md` (the runtime reader prompts), plus the spec docs `conventions.md`/`planning.md` and producer `create-plan/SKILL.md` (not "readers").
- **Actual signature/role**: exactly five runtime reader prompts, all in Track 2's in-scope list. The count "five" is accurate and the inventory exhaustive.
- **Verdict**: MATCHES
- **Detail**: No tier-line reader is missed; the gap (CR1) is confined to the §1.7(b)-marker staged-read readers, a different consumer class.

#### Ref: implementer §1.7(c) gate readers (`step-implementation`, `implementer-rules`)
- **Document claim**: D4 (`track-2.md`:44-48) names the implementer §1.7(c) gate (`step-implementation`, `implementer-rules`) as marker readers to re-point.
- **Search performed**: grep §1.7(c)/`### Constraints`/staged + Read excerpts.
- **Code location**: `.claude/workflow/step-implementation.md:312-327` (reads the §1.7(b) marker from `### Constraints`, routes writes to staged paths), `.claude/workflow/implementer-rules.md:258-295` (matches the stable marker prefix in `### Constraints`, copy-then-edit to staged path).
- **Actual signature/role**: both files genuinely read the marker from the plan's `### Constraints`. The named inventory members are accurate.
- **Verdict**: MATCHES
- **Detail**: confirms the named-inventory members read the marker (the inventory is correct for the files it names; CR1 is about the files it omits).

#### Ref: §1.7(b)-marker readers — staged-read precedence block (the missed consumers)
- **Document claim**: Track 2 D4 reader inventory + in-scope file list are exhaustive for §1.7 marker readers.
- **Search performed**: grep for the canonical §1.7(b)-marker-read sentence across `.claude/workflow/`, `.claude/skills/`, `.claude/agents/`; cross-check names against both track files + plan + design.
- **Code location**: the sentence appears in seven prompts + `track-code-review.md`. Two carriers are absent from the plan: `.claude/workflow/prompts/dimensional-review-gate-check.md:40` and `.claude/workflow/prompts/review-gate-verification.md:41`. A grep for both names across all four artifacts returns nothing.
- **Actual signature/role**: both are active consumers (dimensional-review-gate-check is the Phase 3B/3C gate-check; review-gate-verification is the Phase 3A re-check) reading the §1.7(b) marker from `### Constraints`, the location D4 vacates and D5 removes.
- **Verdict**: PARTIAL
- **Detail**: produces CR1. The inventory covers the tier-line readers and the named §1.7(c)/(l) prompts exhaustively but misses these two §1.7(b) staged-read readers.

### Design ↔ Plan

#### Ref: DR `**Full design**` cross-references resolve to design.md headings
- **Document claim**: each track DR carries a `**Full design**` line pointing at a design.md `## ` section.
- **Search performed**: grep `^## ` in design.md vs grep `Full design` in both track files.
- **Code location**: design.md headings — Overview, Core Concepts, Class Design, Workflow, The phase ledger, Resume routing, The thinned plan and the plan-review document, Track-file dispositions, Mid-flight tier upgrade, This branch's §1.7 staging mode. Track refs cite: "The thinned plan and the plan-review document", "The phase ledger", "Resume routing", "Track-file dispositions", "Mid-flight tier upgrade" — all present.
- **Actual signature/role**: every `**Full design**` link target exists as a real heading. No broken cross-reference.
- **Verdict**: MATCHES
- **Detail**: none.

#### Ref: Component Map / Decision Record coverage (D1–D13)
- **Document claim**: plan Architecture Notes carry D1–D13; the Component Map assigns each to Track 1 or Track 2; tracks carry the inline DRs.
- **Search performed**: cross-read plan D-set, track-1 Decision Log, track-2 Decision Log.
- **Code location**: Track 1 carries D1, D2, D3, D5, D6, D9, D10, D13; Track 2 carries D4, D7, D8, D11; D12 is the plan's own `### Constraints` marker decision (not a track DR). Twelve track DRs + D12 = all thirteen plan DRs accounted for.
- **Actual signature/role**: no orphan DR (every design/plan DR lands in a track or is the branch's own staging decision); no track DR lacks a design counterpart. The Component Map track assignments match the DRs' `**Implemented in**` lines.
- **Verdict**: MATCHES
- **Detail**: Component Map nodes (phase-ledger.md, workflow-startup-precheck.sh, plan/track-N.md, implementation-plan.md, plan-review.md, branch-state consumers) align between plan:64-116 and design:78-112; design's "§1.7 marker readers" node is the abstraction CR1 finds under-populated, but the node itself is consistent with the plan.

### Design ↔ Code

#### Flow: phase-boundary ledger write + next-startup read (design.md Workflow sequenceDiagram)
- **Document claim**: design.md:119-142 — on a phase boundary the orchestrator calls `--append-ledger`; on next startup `--mode full` reads the ledger tail then the track `## Progress`.
- **Trace**:
  1. `--append-ledger` subcommand — TARGET-STATE: not yet in the script (D6/Track 1 builds it). The CLI `case` (`:60-80`) currently has only `--mode`/`--bootstrap-sha`/`--exclude-sha`.
  2. `--mode full` reading the ledger tail — TARGET-STATE: `determine_state` currently parses plan checkboxes (`:1456-1528`), not a ledger tail; D3 rewires it.
  3. track `## Progress` read for within-track sub-state — CURRENT-STATE, present (`determine_c_substate`, `:1537`); design says this stays "exactly as it does today" — accurate.
- **Divergence point**: steps 1–2 are target-state (a `[ ]`-track creation), so not findings per the intent-axis pre-screen; step 3's current-state claim matches.
- **Verdict**: MATCHES
- **Detail**: the only current-state assertion in the flow (the unchanged `## Progress` sub-state read) is accurate; the rest is target-state and correctly silenced.

### Invariants

#### Invariant: the drift gate keeps a stamped anchor (`track-1.md`) when the plan is absent
- **Document claim**: plan `### Constraints`:53-55, D13, design:192-193 — `track-1.md` is always present and stamped, so the §1.6(h) stamp fold still has an anchor after the `minimal` plan is dropped; the ledger is unstamped.
- **Code evidence**: `workflow-startup-precheck.sh:488-491` (walk includes `plan/track-*.md`); `conventions.md` §1.6(h):812-815 (same glob); §1.6(f):743 ("Every `_workflow/plan/track-*.md`" is stamped).
- **Mechanism**: the §1.6(h) walk enumerates `track-*.md` and stamps each; a dropped `implementation-plan.md` removes one glob entry but `track-1.md` remains, so the fold still gathers ≥1 stamp. The ledger is added to the §1.6(f) exclusion list (target-state, D13).
- **Verdict**: ENFORCED
- **Detail**: the current code already stamps track files, so the anchor the design relies on exists today; the ledger-exclusion half is target-state (Track 1) and reachable.

#### Invariant: backward-compatible resume for in-flight `lite`/`full` plans (two-level lookup unchanged)
- **Document claim**: plan `### Constraints`:48-52, `track-1.md`:233-235 — existing in-flight `lite`/`full` plans with a Checklist resume without regression; the two-level lookup keeps the track-file sub-state walk unchanged.
- **Code evidence**: `workflow-startup-precheck.sh:1467-1528` (Checklist walk) + `:1535-1537` (track `## Progress` sub-state).
- **Mechanism**: the within-track sub-state read is untouched (Non-Goal, plan:289-290); only the top-level phase source moves to the ledger (target-state). The current Checklist walk still serves an in-flight plan that retains its Checklist.
- **Verdict**: ASPIRATIONAL
- **Detail**: the regression-free resume is delivered by Track 1's `determine_state` rewrite (D3) — a target-state guarantee, reachable from the current two-level structure. No implementing-track gap (Track 1 owns it), so no design-decision escalation; recorded as the tracked target.
