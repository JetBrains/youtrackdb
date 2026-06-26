<!--
MANIFEST
review_type: consistency
phase: 2
iteration: 1
role: reviewer-plan
tier: full
plan_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/implementation-plan.md
design_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md
plan_dir: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan
tracks_reviewed: [track-1, track-2]
verdict: CHANGES_REQUESTED
findings: 5
blockers: 1
prefix: CR
index:
  - {id: CR1, sev: blocker, anchor: "### CR1 ", loc: "design.md Part 4/Part 6 + plan (gap); implementation-review.md §Tier-driven pass selection (D9/D10)", cert: "Ref: implementation-review.md tier-driven pass selection", basis: "Phase-2 orchestrator-side pass selector reads the removed tier; no track re-keys it"}
  - {id: CR2, sev: should-fix, anchor: "### CR2 ", loc: "track-1.md §Interfaces (workflow.md scope) + track-2.md §Interfaces; workflow.md §Final Artifacts (Phase 4)", cert: "Ref: workflow.md Final Artifacts Phase-4 carrier table"}
  - {id: CR3, sev: should-fix, anchor: "### CR3 ", loc: "track-2.md §Interfaces In-scope list + §Plan of Work step 4; review-iteration.md §Finding ID prefixes", cert: "Ref: review-iteration.md Finding ID prefixes owner"}
  - {id: CR4, sev: should-fix, anchor: "### CR4 ", loc: "track-1.md D8a + §Plan of Work step 4 (conventions.md) vs track-2.md D8b; conventions.md §Per-tier artifact set", cert: "Ref: conventions.md Per-tier artifact set durable-carrier row"}
  - {id: CR5, sev: should-fix, anchor: "### CR5 ", loc: "track-2.md §Interfaces (design-review.md scope) + §Plan of Work step 6; design-review.md §Inputs tier param", cert: "Ref: design-review.md tier=full fidelity gate"}
evidence_base:
  refs_checked: 41
  matches: 36
  mismatches: 0
  not_found: 0
  partial: 0
  gaps: 5
-->

# Consistency Review — iteration 1

## Findings

### CR1 [blocker]
**Certificate**: Ref: implementation-review.md tier-driven pass selection
**Location**: GAP — `implementation-review.md` §"Tier-driven pass selection (D9/D10)" is named by no track; design Part 4 (artifact derivation) and Part 5 (resume routing) cover the prompt-side and ledger-side re-keys but not this orchestrator-side selector. Plan + both track files.
**Issue**: `implementation-review.md` is the orchestrator-side authority that decides *which* Phase-2 passes run and in what shape. Its §"Tier-driven pass selection (D9/D10)" reads the **confirmed tier** ledger-first (`phase-ledger.md` `tier` field) with the D18 `implementation-plan.md` tier line as fallback, then keys the per-tier pass matrix off it: `minimal` drops Step 2 structural, `lite`/`minimal` drop the consistency design half, `minimal` additionally drops the plan-content cross-check. Track 1's schema delta **removes `tier=` from the ledger** and re-keys the consistency-review and structural-review *prompts* to read `design_gate`. But this file — which actually drives prompt selection and reads the tier to do it — is in **neither track's in-scope list** and is mentioned nowhere in the design, plan, or track files. After Track 1 lands, this file reads a removed ledger field and a removed plan tier line, leaving the Phase-2 pass selector keyed on a concept that no longer exists.
**Evidence**: `implementation-review.md:189-204` — "Which Phase-2 passes run … is keyed off the **confirmed tier** (D9) … read the tier **ledger-first**: the phase ledger's `tier` field … fall back to the **D18 tier line** in `implementation-plan.md`". `:210-213` — per-tier pass matrix (`minimal` drops structural, design-half drop in `lite`/`minimal`). `:194` reads `tier`, removed by Track 1 (`workflow-startup-precheck.sh:1718` `tier=$LEDGER_TIER` builder line and `:1696` validator, both dropped per track-1.md §Plan-of-Work step 1). Grep of `_workflow/**` for `implementation-review`: no hits. Both Track 1 (resume/ledger/prompt-side) and Track 2 (review-selection mirrors) scope lists omit it. The design's Part 5 routing re-keys `determine_state`/Step-1c and Part 4 re-derives the artifact set, but the Phase-2 orchestrator pass selector is a third tier-reading site neither addresses.
**Proposed fix**: Add `.claude/workflow/implementation-review.md` to Track 1's in-scope file list and Plan-of-Work step (3) (Phase-1 artifact gates / design-presence re-keying), re-keying §"Tier-driven pass selection" so structural-pass-skip and the design-half guard read `design_gate` and the plan-presence / track-count signal instead of `tier`. The user must confirm the re-mapping: `minimal`-drops-structural becomes "drops structural when no plan exists" (track-count signal), and the design-half guard becomes `design_gate=no` — both are mechanical under the design's axes, but assigning the file to a track and confirming the no-plan structural-skip mapping is a planning decision.
**Classification**: design-decision
**Justification**: per §`design-decision` rules — a current-state construct the documents should reference but don't (orphan-codebase-construct gap, §GAPS bullet 3), and the fix requires picking which track owns the file plus confirming the tier→axes re-mapping of the pass-skip rule; "when in doubt … choose `design-decision`".

### CR2 [should-fix]
**Certificate**: Ref: workflow.md Final Artifacts Phase-4 carrier table
**Location**: `track-1.md` §"Interfaces and Dependencies" (workflow.md scope, lines 345-346) and `track-2.md` §"Interfaces and Dependencies" (workflow.md listed out-of-scope, lines 489-490); live `workflow.md` §"Final Artifacts (Phase 4)".
**Issue**: `workflow.md` §"Final Artifacts (Phase 4)" carries a per-tier durable-artifact carrier table — the same `adr.md`-by-tier mapping (`full` → design-final + adr; `lite` → adr; `minimal` → none/PR) that Track 2's D8b re-derives from the axes (`adr ⟺ ∃ track ≥ medium`). Track 1 owns `workflow.md` but scopes it only to "`determine_state`, single-track resume, and the startup-protocol ledger reads" — not the Phase-4 carrier table. Track 2 owns the adr predicate (D8b) but lists `workflow.md` as out of scope. So this carrier table is re-derived in `create-final-design.md` (Track 2) but left tier-keyed and stale in `workflow.md`, contradicting the new predicate (it still says `minimal` → no adr and `lite` → adr unconditionally).
**Evidence**: `workflow.md:656-664` — "Which artifacts Phase 4 produces is keyed off the confirmed tier (D16)" with the three-row tier table; `:666` "Gate 2 (multi-track) is the durable-ADR boundary". This duplicates `create-final-design.md:97-99` (Track 2 D8b scope). track-1.md:345-346 limits workflow.md scope to resume reads; track-2.md:489-490 lists workflow.md under "Out of scope (Track 1 owns these)". Design Part 4 re-derives the carrier "in `create-final-design.md`" only (design.md:636-637) and does not name the `workflow.md` duplicate.
**Proposed fix**: Either extend Track 1's `workflow.md` scope line to include re-keying §"Final Artifacts (Phase 4)" to the axis-derived carrier (design exists → design-final; ∃ track ≥ medium → adr), or move that re-key into Track 2 (adding `workflow.md`'s Phase-4 section to Track 2's scope as the adr-predicate owner). The user picks which track carries it.
**Classification**: design-decision
**Justification**: per §`design-decision` — the fix has two plausible track homes (Track 1 owns the file, Track 2 owns the predicate semantics), so the orchestrator cannot pick a single correct assignment without a planning choice; also a missing cross-track coordination for a duplicated carrier the design only re-derives at one of its sites.

### CR3 [should-fix]
**Certificate**: Ref: review-iteration.md Finding ID prefixes owner
**Location**: `track-2.md` §"Interfaces and Dependencies" In-scope list (lines 454-487) and "Key contracts in scope" (lines 517-522); §"Plan of Work" steps 3-4 (lines 362-384); live `review-iteration.md` §"Finding ID prefixes".
**Issue**: Track 2 names `review-iteration.md` §"Finding ID prefixes" as the **owner** of the finding-prefix family and plans to retire `BC` and add two new prefixes for `review-bugs`/`review-concurrency`. But `review-iteration.md` is **absent** from Track 2's in-scope file list — only `finding-synthesis-recipe.md` (which *references* the family) is in scope. The owner table itself, with its `BC` row, would be left unedited: stale `BC` row, no rows for the two new prefixes, after the agent it names is removed.
**Evidence**: `track-2.md:520-522` "Owned by `review-iteration.md` §"Finding ID prefixes" and referenced by `finding-synthesis-recipe.md`". Live `review-iteration.md:42-63` — the `## Finding ID prefixes` table with rows `BC` (Bugs & concurrency), `TB`, `TC`, `TX`. Track 2 in-scope list (`:454-487`) includes `finding-synthesis-recipe.md` (`:469-470`) and the six agent files but **not** `review-iteration.md`. Plan-of-Work step 4 (`:382-383`) edits `finding-synthesis-recipe.md` for the prefix change but never names the owner table.
**Proposed fix**: Add `.claude/workflow/review-iteration.md` to Track 2's in-scope file list and to Plan-of-Work step (3) or (4): retire the `BC` row and add the two new `review-bugs` / `review-concurrency` prefix rows in the §"Finding ID prefixes" table, keeping `TB`/`TC`/`TX`.
**Classification**: design-decision
**Justification**: per §`design-decision` — an orphan-codebase-construct the plan should reference but doesn't (the named owner of a contract the track changes is missing from its scope); the omission is a planning gap, not a single-rendering text fix.

### CR4 [should-fix]
**Certificate**: Ref: conventions.md Per-tier artifact set durable-carrier row
**Location**: `track-1.md` D8a (lines 98-127) + §"Plan of Work" step 4 (conventions.md, lines 269-271) vs `track-2.md` D8b (lines 195-228); live `conventions.md` §"Per-tier artifact set".
**Issue**: `conventions.md` §"Per-tier artifact set" is a single table whose **"Phase 4 durable carrier"** row carries the `adr.md`-by-tier mapping. Track 1 owns `conventions.md` and re-keys "the per-axis artifact set" (Plan-of-Work step 4); Track 1's D8a note explicitly states the adr-predicate half "is owned by Track 2" (track-1.md:125-127), and Track 2 lists `conventions.md` as out of scope (track-2.md:489-490). So the adr row of a Track-1-owned table must reflect Track 2's D8b predicate (`adr ⟺ ∃ track ≥ medium`), but neither track names this specific cross-track coupling — Track 1's "per-axis artifact set" wording does not say the adr row encodes a Track-2 decision, and Track 2 cannot edit the file.
**Evidence**: `conventions.md:233-241` — the per-tier artifact table with row `:241` "Phase 4 durable carrier | `design-final.md` + `adr.md` | `adr.md` | PR-description verdict summary". track-1.md:269-271 / `:347-348` scope conventions.md to "the ledger schema / glossary … and the per-axis artifact set" (the design.md/plan half, D8a). track-1.md:125-127 (D8a note) — "the Phase-4 adr-predicate half of design D8 … is owned by Track 2". track-2.md:489-490 lists conventions.md out of scope. The design's Part 4 artifact-to-axis table (design.md:599-605) is the target shape, but does not resolve which track edits the `conventions.md` adr row.
**Proposed fix**: State the coupling explicitly in one track. Either (a) Track 1's Plan-of-Work step 4 records that it re-keys the `conventions.md` per-tier artifact table's durable-carrier row to the axis-derived form (`adr` ⟺ ∃ track ≥ medium) mechanically per Track 2's D8b, landing it as Track 2's predicate even though Track 1 authors it; or (b) add the adr-row re-key of `conventions.md` to Track 2's scope as a narrow exception. Confirm sequencing — Track 1 lands first, so the table's adr row must already encode the medium-or-higher predicate at Track 1 time.
**Classification**: design-decision
**Justification**: per §`design-decision` — a cross-track coordination the plan leaves implicit (which track encodes the D8b predicate into a file the other track owns); the fix is a planning/ownership call, not a single-rendering correction.

### CR5 [should-fix]
**Certificate**: Ref: design-review.md tier=full fidelity gate
**Location**: `track-2.md` §"Interfaces and Dependencies" (design-review.md scope, line 487) + §"Plan of Work" step 6 (line 394); live `design-review.md` §"Inputs".
**Issue**: `design-review.md` takes a `tier` input whose sole use is a **design-presence proxy** — `tier=full` ⟺ a `design.md` exists ⟺ run the seed↔track fidelity check. After the unbundling that gate should read `design_gate=yes`. Design-presence re-keying is Track 1's domain everywhere else (it re-keys the consistency/structural prompts and design-document-rules.md for "when a `design.md` exists"). But `design-review.md` is in **Track 2**'s scope with the re-key duty "roster / tag / review references" (track-2.md:394, 487), which does not clearly cover re-keying the `tier`/`tier=full` fidelity gate to `design_gate`. The design-presence re-key of this file is either unowned by its stated duty or mis-assigned across the Track-1/Track-2 design-presence boundary.
**Evidence**: `design-review.md:67-69` — "`tier` (optional) — `full` / `lite` / `minimal`. Supplied with `target=tracks` so the reviewer knows whether the full-tier fidelity criterion applies"; `:235` "Full-tier fidelity criterion (`tier=full`, `target=tracks`)"; `:70-72` design_path "also passed with `target=tracks` in `full`". track-2.md:394 (step 6) scopes design-review.md to "roster / tag / review references"; `:487` "design-review.md — roster / tag / review references". Track 1's design-presence re-key duty (track-1.md:200-203, 358-361) covers the consistency/structural prompts and design-document-rules.md but not design-review.md.
**Proposed fix**: Extend Track 2's design-review.md re-key duty (step 6 + scope line) to include re-keying the `tier`/`tier=full` fidelity gate to `design_gate=yes` (the design-presence axis), OR move design-review.md's design-presence re-key to Track 1 alongside the other design-presence prompts. Confirm which track owns this single design-presence read inside an otherwise Track-2-owned file.
**Classification**: design-decision
**Justification**: per §`design-decision` — the re-key crosses the Track-1 design-presence / Track-2 roster-and-tag boundary, so assigning it requires a planning choice; the current scope wording ("roster / tag / review references") does not unambiguously cover a design-presence gate.

## Evidence base

Verification certificates grouped by consistency axis. MATCHES verdicts are
recorded but do not generate findings. The "code" here is the live
(develop-state) workflow machinery under `.claude/workflow/**`,
`.claude/scripts/**`, `.claude/skills/**`, `.claude/agents/**`; every
reference is verified by exact `Read`/`Grep` text match (authoritative for
Markdown / shell / Python — no reference-accuracy caveat applies, per the
spawn instructions). The plan/tracks describe *target-state* changes to these
live files; per the intent-axis pre-screen, a target-state mismatch with the
live file is expected and is NOT a finding. Findings fire only on wrong
current-state claims, phantom live references, unreachable targets, or
cross-track / coverage gaps.

### Plan ↔ Code

#### Ref: file existence (all 28 referenced workflow files)
- **Document claim**: Track 1 + Track 2 in-scope lists and `## Context` name 28 distinct live workflow files (precheck.sh, its 2 tests, create-plan/SKILL.md, workflow.md, conventions.md, planning.md, research.md, plan-slim-rendering.md, design-document-rules.md, consistency-review.md, structural-review.md, risk-tagging.md, track-review.md, review-agent-selection.md, code-review/SKILL.md, step-implementation.md, track-code-review.md, fix-ci-failure/SKILL.md, finding-synthesis-recipe.md, code-review-protocol.md, conventions-execution.md, inline-replanning.md, review-iteration.md, create-final-design.md, design-review.md, and the 4 removed/merge-source agent files + review-test-concurrency.md).
- **Search performed**: `for f in …; do [ -f "$f" ]; done` over all 28.
- **Code location**: all present (no MISSING).
- **Actual signature/role**: every path resolves to a live file at develop state.
- **Verdict**: MATCHES
- **Detail**: no phantom file references in either track.

#### Ref: ledger `--append-ledger` flag surface (Track 1 §Interfaces "Key signatures")
- **Document claim**: today's flag surface is `[--ctx][--phase][--track][--tier][--categories][--s17][--paused][--substate]`; Track 1 drops `--tier` and adds four flags.
- **Search performed**: `grep -nE '--append-ledger|--tier|--phase…' workflow-startup-precheck.sh`.
- **Code location**: `workflow-startup-precheck.sh:18-21`, `:134`.
- **Actual signature/role**: usage line `:19-20` lists exactly those eight flags; `--tier` parse at `:171-173` sets `LEDGER_TIER`.
- **Verdict**: MATCHES
- **Detail**: current-state flag surface accurate to the live header.

#### Ref: ledger key set + grammar (Track 1 §Context, design Data model)
- **Document claim**: current key set is `{ phase, track, tier, substate, categories, s17, paused }`; last-value-wins per key; loud-reject (exit 3) on a newline / bare-token space / quoted double-quote; atomic temp-file + rename.
- **Search performed**: `grep` for the key list comment, `reject_bad_ledger_value`, `append_ledger`.
- **Code location**: `:52`, `:58` (key set comment), `:1655` (`reject_bad_ledger_value`), `:1683` (`append_ledger`), `:1693-1700` (per-field validation), `:1696` `tier` validated bare.
- **Actual signature/role**: key set comment `:58` reads `{ phase, track, tier, substate, categories, s17, paused }` verbatim; validation + atomic rename present.
- **Verdict**: MATCHES
- **Detail**: every current-state grammar claim in Track 1 §Context (`:148-167`) and design Data model (`:108-112`) confirmed.

#### Ref: ledger line-builder field ordering (Track 1 step 1)
- **Document claim**: bare reader-consumed fields precede the quoted `categories` field; `design_gate` must be emitted in the pre-`categories` block (mirrors the existing `substate`-before-`categories` ordering).
- **Search performed**: `sed` read of the `append_ledger` builder block.
- **Code location**: `:1715-1726`.
- **Actual signature/role**: `:1721` `substate` emitted before `:1722` `categories="…"`, with the explicit comment `:1719-1721` explaining bare-before-quoted; `s17`/`paused` follow categories.
- **Verdict**: MATCHES
- **Detail**: the ordering rationale Track 1 relies on is exactly the live `substate` pattern.

#### Ref: ledger reader functions (Track 1 §Interfaces)
- **Document claim**: `reject_bad_ledger_value`, `append_ledger`, `ledger_tail_value <key>` (global last-value-wins), `ledger_tail_value_for_track <key> <track>` (track-scoped), `determine_state_from_ledger` (sets `STATE_JSON`, returns 1 with no ledger) all exist and are the only ledger I/O.
- **Search performed**: `grep` for each function name.
- **Code location**: `:1655`, `:1683`, `:1774`, `:1828`, `:1934`.
- **Actual signature/role**: all five present; `ledger_tail_value_for_track` `:1828` is the track-scoped variant (`:1809-1810` comment); `determine_state_from_ledger` `:1934` sets STATE_JSON, `:2019` caller falls back to checkbox walk on return 1.
- **Verdict**: MATCHES

#### Ref: determine_state minimal-default-track-to-1 (Track 1 §Context, design Part 5)
- **Document claim**: `determine_state` defaults the active track to 1 for the single-track `minimal` tier whose ledger names no track; this is the only resume signal a plan-less branch has, and it re-keys onto the plan-presence/track-count signal.
- **Search performed**: `sed` read of the Phase-C resume arm.
- **Code location**: `:1962-1966`.
- **Actual signature/role**: `:1964` comment "Default the active track to 1 for the single-track `minimal` tier whose ledger names no track (D10)"; `:1966` `[ -n "$track" ] || track="1"`.
- **Verdict**: MATCHES
- **Detail**: current-state behavior accurate. (Note: `determine_state_from_ledger` already reads a track-scoped `substate` at `:1981` with comments referencing the prior `no-track-for-minimal` D1/D10 — exactly the stale cross-branch comment references Track 1 §Context:178-181 flags as part of the re-keying cleanup. Confirmed current-state.)

#### Ref: Step-1c resume router (Track 1 §Context, design Part 5)
- **Document claim**: Step 1c parses the ledger `tier=` once (`LEDGER_TIER`), routes by what's on disk + tier; `minimal` resumes off ledger + `plan/track-1.md` glob; `lite`/`full` off `implementation-plan.md` presence; a `design.md` with no plan is a `full`-tier mid-authoring crash with a committed-and-clean check.
- **Search performed**: `grep`/`Read` of `create-plan/SKILL.md` Step 1c.
- **Code location**: `create-plan/SKILL.md:131` (Step 1c heading), `:162-163` (`LEDGER_TIER` sed parse), `:174-218` (design.md-no-plan = full mid-authoring crash arm + committed-and-clean `git log`/`git status`), `:235-243` (`tier=minimal` resume arm).
- **Actual signature/role**: matches each claim. The `design.md`-no-plan arm is "crash-recovery-only after the 4a/4b collapse" (`:176`) and a `design.md` present with no plan currently means exactly one thing (full mid-authoring) — precisely the single-meaning state the design's Part 5 says D8 adds a second meaning to.
- **Verdict**: MATCHES
- **Detail**: Track 1 §Context:173-181 current-state description accurate to the live router.

#### Ref: create-plan Step 4 two-gate tier classifier (Track 1 §Context, design Part 1)
- **Document claim**: Step 4 part 1 is a two-gate classifier — Gate 1 "does the change need a `design.md`?" (reuses risk-tagging Gate 1), Gate 2 "multiple tracks?" — collapsing to `full`/`lite`/`minimal`.
- **Search performed**: `sed` read of Step 4 + `grep` of risk-tagging Gate 1 reuse.
- **Code location**: `create-plan/SKILL.md:380-389`; `risk-tagging.md:181-203` (§"Gate 1 reuse (change-level)").
- **Actual signature/role**: Gate table `:380-381` (Gate 1 design?, Gate 2 multi?), collapse-to-three `:383-385`; risk-tagging `:184-187` lists the seven HIGH categories as Gate 1's source.
- **Verdict**: MATCHES
- **Detail**: the two old tiers map onto axes exactly as design Part 1:261-263 claims (`full`=design+multi, `lite`=no-design+multi, `minimal`=no-design+single).

#### Ref: seven risk-tagging HIGH triggers (design Part 1, Track 2 §Context)
- **Document claim**: seven HIGH triggers — `Concurrency`, `Crash-safety / Durability`, `Public API`, `Security`, `Architecture / cross-component coordination`, `Performance hot path`, `Workflow machinery` — drive the complexity tag.
- **Search performed**: `grep` of risk-tagging section headings.
- **Code location**: `risk-tagging.md:10-16` (TOC), `:102/114/126/135/145/152/159` (the seven `### ` headings), `:184-187` (the enumerated list).
- **Actual signature/role**: all seven headings present verbatim, exactly the names both tracks quote.
- **Verdict**: MATCHES

#### Ref: per-step risk tag low/medium/high + max(step tags) basis (design Part 2, Track 2 §Plan-of-Work)
- **Document claim**: each step gets a `low`/`medium`/`high` tag; `max(step tags)` is the reconciliation input; the §"Workflow machinery" step HIGH trigger exists.
- **Search performed**: `grep` of risk-tagging step-tag definitions.
- **Code location**: `risk-tagging.md:31-34` (step tag enum), `:100` (step `high` if ANY), `:161` (workflow step `high`), `:211` (step `medium`).
- **Actual signature/role**: per-step `low/medium/high` confirmed; `max(step tags)` is well-founded over an existing scale.
- **Verdict**: MATCHES

#### Ref: Phase-A panel "Tier-driven review selection" (Track 2 §Context, design Part 3)
- **Document claim**: `track-review.md` §"Tier-driven review selection" reads the whole-change tier today: `minimal` → Technical only; `lite`/`full` → Technical always + Risk track-characteristic-gated + Adversarial narrowed. The panel already runs per-track.
- **Search performed**: `grep`/`Read` of track-review.md.
- **Code location**: `track-review.md:620-664`.
- **Actual signature/role**: table `:641-643` (`minimal` Technical-only; `lite`/`full` Technical + Risk gated + Adversarial narrowed); `:651-654` Risk-gating characteristics; `:660-664` Adversarial runs in every lite/full.
- **Verdict**: MATCHES
- **Detail**: Track 2 §Context:296-299 current-state description accurate; re-keying the knob from tier to per-track tag is the target-state delta (not a finding).

#### Ref: five reviewer-selection mirror sites (Track 2 §Context, design Part 3 edge case)
- **Document claim**: category-driven selection lives in `code-review/SKILL.md` Step 5 and is mirrored across `review-agent-selection.md`, `track-code-review.md`, `step-implementation.md`, `fix-ci-failure/SKILL.md` — a drift vector.
- **Search performed**: `grep` of each site for the roster + selection.
- **Code location**: `code-review/SKILL.md:35/181-191` (Step 5/5b map), `review-agent-selection.md:33-58/240-261`, `track-code-review.md:534-550`, `step-implementation.md:431-449`, `fix-ci-failure/SKILL.md:170-201`.
- **Actual signature/role**: all five reference the baseline roster (`review-bugs-concurrency`, `review-test-behavior`, `review-test-completeness`) and category-driven selection.
- **Verdict**: MATCHES

#### Ref: review-agent-selection anchors (design Part 3, Track 2 D3)
- **Document claim**: §"Step-level vs track-level routing" (localized-versus-buried rule, single-step-high override, "Workflow-review group" narrowing) and §"Per-agent file-pattern triggers" exist.
- **Search performed**: `grep` for the section headings.
- **Code location**: `review-agent-selection.md:94` (Step-level vs track-level routing), `:115/126` (single-step-high override), `:139` (Workflow-review group), `:240` (Per-agent file-pattern triggers).
- **Actual signature/role**: all anchors present; the floor reviewers (`review-workflow-consistency` + `review-workflow-context-budget` always-launch, plus four glob-gated) at `:254-261` match design Part 3 §"The floor" (design.md:511-522).
- **Verdict**: MATCHES

#### Ref: current reviewer roster + finding prefixes (design Data model, Track 2 §Context)
- **Document claim**: today `review-bugs-concurrency` (prefix `BC`) is one agent; `review-test-behavior` (`TB`) + `review-test-completeness` (`TC`) are separate; `review-test-concurrency` (`TX`) is the existing production-split template.
- **Search performed**: `grep` of review-iteration.md prefix table + agent file existence.
- **Code location**: `review-iteration.md:56` (`BC`), `:60` (`TB`), `:61` (`TC`), `:63` (`TX`); agent files `review-bugs-concurrency.md`, `review-test-behavior.md`, `review-test-completeness.md`, `review-test-concurrency.md` all present.
- **Actual signature/role**: prefix family and roster exactly as described.
- **Verdict**: MATCHES

#### Ref: create-final-design.md tier carrier table (Track 2 §Context, D8b)
- **Document claim**: the Phase-4 carrier selection in `create-final-design.md` is keyed off the tier today (full → design-final + adr; lite → adr; minimal → PR summary) — the load-bearing hub.
- **Search performed**: `grep` of create-final-design.md Step 3.
- **Code location**: `create-final-design.md:87-105`, table `:97-99`.
- **Actual signature/role**: Step 3 "keyed off the confirmed tier (D16)" with the three-row table; verdict-fold destinations per tier.
- **Verdict**: MATCHES
- **Detail**: current-state accurate; re-deriving from the axes is the D8b target-state delta (not a finding). (See CR2/CR4 for the *other* duplicate carrier sites in `workflow.md` and `conventions.md` that the tracks do not address.)

#### Ref: inline-replanning tier-escalation path (Track 2 §Plan-of-Work step 6)
- **Document claim**: `inline-replanning.md` has a tier-escalation path (the tier-upgrade rides the ESCALATE replan) keyed off tier.
- **Search performed**: `grep` of inline-replanning.md tier references.
- **Code location**: `inline-replanning.md:141-165`.
- **Actual signature/role**: §"Tier upgrade rides this same path (D12)" `:141`; materialize-then-write-tier `:150-165`; tier home is the ledger `tier` field (D4) `:164-165`.
- **Verdict**: MATCHES
- **Detail**: current-state accurate; in Track 2 scope; re-key to complexity is target-state.

#### Ref: code-review-protocol single-step-high + roster (Track 2 §Plan-of-Work step 4)
- **Document claim**: `code-review-protocol.md` carries roster references and the single-step-high Phase-C-skip rule.
- **Search performed**: `grep` of code-review-protocol.md.
- **Code location**: `code-review-protocol.md:7/55-67` (§Single-step tracks), `:31` (`review-bugs-concurrency` baseline reference).
- **Actual signature/role**: single-step-high skip rule and roster reference present; in Track 2 scope.
- **Verdict**: MATCHES

#### Ref: conventions-execution.md roster / per-tier refs (Track 2 §Plan-of-Work step 6)
- **Document claim**: `conventions-execution.md` carries review-file / roster references and per-track-tag track-file references to re-key.
- **Search performed**: `grep` of tier/roster references.
- **Code location**: `conventions-execution.md:118` (decision-log seeding by tier), `:286` (decision-log per-tier), `:527` (per-tier baseline selection), `:747` (adr/minimal PR-summary fold).
- **Actual signature/role**: roster/per-tier references present; in Track 2 scope.
- **Verdict**: MATCHES

#### Ref: research.md / planning.md / plan-slim-rendering.md / design-document-rules.md tier prose (Track 1 §Plan-of-Work step 4)
- **Document claim**: these four carry tier glossary / classification / rendering prose naming the tier directly, to be re-keyed to the design gate + track-count.
- **Search performed**: `grep` of tier references in each.
- **Code location**: `research.md:53-57` (Phase 0→1 classification, tier "not yet chosen during Phase 0"), `planning.md` (§Tier classification, referenced from create-plan:376), `plan-slim-rendering.md:187-188` (per-tier artifact carrier), `design-document-rules.md:67/95-101` (`design.md` "in `full`-tier plans only", "in `lite`/`minimal` there is no `design.md`").
- **Actual signature/role**: each holds the tier-naming prose Track 1 step 4 enumerates; all four in Track 1 scope.
- **Verdict**: MATCHES

### Plan ↔ Code (invariants)

#### Invariant: last-value-wins per key
- **Document claim**: the ledger is last-value-wins per key; the new keys preserve it (Track 1 §Invariants).
- **Code evidence**: `workflow-startup-precheck.sh:1763-1806` (`ledger_tail_value` scans every line, keeps latest per key).
- **Mechanism**: enforced in the reader; the new bare fields join the same scan.
- **Verdict**: ENFORCED
- **Detail**: current-state property holds; the target-state extension to new keys is reachable (the reader is key-generic).

#### Invariant: loud-reject append grammar (exit 3)
- **Document claim**: a newline / bare-token space / quoted double-quote exits 3 with a stderr diagnostic (Track 1 §Invariants).
- **Code evidence**: `:1655-1682` (`reject_bad_ledger_value`), `:1693-1700` (per-field calls).
- **Mechanism**: validation runs before the line is built; each new bare field gets a `bare` call (target-state, reachable).
- **Verdict**: ENFORCED

#### Invariant: atomic temp-file + rename append
- **Document claim**: a torn append leaves the prior ledger tail intact (Track 1 §Invariants).
- **Code evidence**: `:1726-1740`-region temp-file build + `mv` rename (RETURN trap reaps temp).
- **Mechanism**: same-directory temp + rename = atomic publish.
- **Verdict**: ENFORCED

#### Invariant: track-scoped read prevents cross-track leak
- **Document claim**: the per-track reconciled tag is read track-scoped so a prior track's tag cannot leak (Track 1 §Invariants).
- **Code evidence**: `:1828-1862` (`ledger_tail_value_for_track`), already used for `substate` at `:1981`.
- **Mechanism**: scopes the last-value-wins read to lines whose `track=` matches; the new per-track tag reuses this exact reader (target-state, reachable).
- **Verdict**: ENFORCED (mechanism present; the new field's use is ASPIRATIONAL but reachable — implemented by Track 1, no gap)

#### Invariant: never-a-dead-end resume (every artifact combination routes)
- **Document claim**: Step 1c / `determine_state` route every on-disk combination to a defined path; the Phase-1-complete marker separates the design+single steady state from a mid-authoring crash (Track 1 §Invariants, design Part 5).
- **Code evidence**: `create-plan/SKILL.md:166-290` (every arm has a defined resume; `:279` "defined resume path for every artifact combination").
- **Mechanism**: today the design+single steady state does not exist, so the design.md-no-plan signature has a single meaning; D8 adds the second meaning and the Phase-1-complete marker is the new disambiguator. Reachable — the marker is added by Track 1's own schema delta.
- **Verdict**: ASPIRATIONAL (implemented by this track; reachable from current code — no finding)

### Design ↔ Plan

#### Ref: design D-records ↔ track Decision Logs (D1/D8a/D10 → Track 1; D2/D3/D5/D6/D7/D8b/D9 → Track 2)
- **Document claim**: design.md's footer decisions split across the two tracks per the Component Map / design "Decisions & invariants" footers; D8 splits into D8a (Track 1, design.md/plan half) and D8b (Track 2, adr predicate).
- **Search performed**: cross-read of design.md footers vs track `## Decision Log` records.
- **Code location**: design.md:213-218/276-281/337-342/415-421/572-581/649-654/803-808; track-1.md:38-127 (D1/D10/D8a); track-2.md:38-255 (D2/D3/D5/D6/D7/D8b/D9).
- **Actual signature/role**: every design D-record has a matching track DR; the D8 split (D8a/D8b) is consistently cross-noted (track-1.md:125-127 ↔ track-2.md:226-228). No DR contradicts the frozen design (no inline replan has occurred — iteration 1).
- **Verdict**: MATCHES
- **Detail**: design↔plan DR coverage complete; the only design↔plan issues are the carrier-table ownership couplings in CR2/CR4 (which the design re-derives at one site but the plan splits across files).

#### Ref: Component Map dependency (Track 2 depends on Track 1)
- **Document claim**: implementation-plan.md Component Map shows T1 → T2 (Track 2 depends on Track 1's ledger schema).
- **Search performed**: read of the Component Map mermaid + both tracks' §Inter-track dependencies.
- **Code location**: implementation-plan.md:33 (`T1 -.->|Track 2 depends on Track 1| T2`); track-1.md:372-375; track-2.md:495-498.
- **Actual signature/role**: dependency consistent across plan + both tracks; Track 2 reads `design_gate` + the per-track reconciled-tag field Track 1 defines.
- **Verdict**: MATCHES

### Gaps

#### Ref: implementation-review.md Phase-2 pass selector — see CR1
- **Verdict**: NOT FOUND (in plan/tracks) → orphan-codebase-construct gap. The live file `implementation-review.md:189-213` reads the tier Track 1 removes; named by no track.

#### Ref: workflow.md §Final Artifacts Phase-4 carrier — see CR2
- **Verdict**: PARTIAL → the carrier table is re-derived in `create-final-design.md` (Track 2) but its `workflow.md` duplicate (`:656-664`) is in no track's re-key duty.

#### Ref: review-iteration.md §Finding ID prefixes owner — see CR3
- **Verdict**: NOT FOUND (in Track 2 scope) → the named prefix-family owner is missing from the in-scope list while the track changes the family.

#### Ref: conventions.md per-tier-artifact-set adr row — see CR4
- **Verdict**: PARTIAL → file in Track 1 scope, but the adr-row predicate (Track 2 D8b) cross-track coupling is unstated.

#### Ref: design-review.md tier=full fidelity gate — see CR5
- **Verdict**: PARTIAL → file in Track 2 scope, but the design-presence re-key of its `tier` parameter is not covered by the stated "roster / tag / review references" duty.

#### Gap check: orphan design parts no track covers
- **Document claim**: every design Part/Core-Concept maps to a track.
- **Search performed**: cross-read of design Parts 1-6 + Core Concepts vs track Plan-of-Work steps.
- **Code location**: Part 1 (axes/tag) → T1 steps 3-4 + T2 step 1; Part 2 (reconciliation) → T2 step 2; Part 3 (selection) → T2 steps 2-4; Part 4 (artifacts) → T1 D8a step 3 + T2 D8b step 5; Part 5 (resume) → T1 step 2; Part 6 (roster ownership) → T2 step 3.
- **Verdict**: MATCHES — every design Part has a covering track, except the orchestrator-side Phase-2 selector (CR1) and the duplicate carrier tables (CR2/CR4), which are codebase constructs the design does not enumerate, not design parts left uncovered.
