<!-- MANIFEST
review: consistency
iter: 1
phase: 2
role: reviewer-plan
verdict: NEEDS REVISION
findings: 3
blockers: 0
should_fix: 3
suggestions: 0
prefix: CR
tier: full
design_present: true
staged_subtree: absent
tooling: read-grep (workflow-machinery branch; .claude/** Markdown, no Java symbols, PSI N/A)
index:
  - id: CR1
    sev: should-fix
    anchor: "### CR1"
    loc: "plan/track-1.md §Context and Orientation; .claude/agents/*.md"
    cert: "Ref: existing agent tools: frontmatter"
    basis: current-state
    class: mechanical
  - id: CR2
    sev: should-fix
    anchor: "### CR2"
    loc: "plan/track-2.md §Context and Orientation; prompts/create-final-design.md + prompts/design-review.md"
    cert: "Ref: Phase 4 second check today"
    basis: current-state
    class: mechanical
  - id: CR3
    sev: should-fix
    anchor: "### CR3"
    loc: "plan/track-1.md §Context and Orientation + D18; design.md Part 4; research.md / conventions.md / design-document-rules.md"
    cert: "Ref: S2 canonical wording location"
    basis: current-state
    class: design-decision
evidence_base:
  refs_checked: 11
  matches: 8
  mismatches: 3
  flows_checked: 2
  invariants_checked: 7
  notes: "D1-D19 and S1-S7 homing verified coherent across both tracks and design; D11 dual-faceted by design; no orphan/contradiction. All MATCHES recorded in Evidence base."
-->

# Consistency Review — iteration 1

Branch `understandable-design` (YTDB-1130), `full` tier, `§1.7(b)`
workflow-modifying. This is a workflow-machinery plan: the "code" under
review is `.claude/**` Markdown / SKILL / prompt / agent files, not Java, so
every reference is verified by `Read` / `Grep` over the live develop-state
files. There is no Java in scope, so PSI is not applicable and no
reference-accuracy caveat about polymorphic dispatch applies. The phase ledger
reads `phase=0 tier=full ... s17=workflow-modifying`, and
`_workflow/staged-workflow/` does not exist yet (pre-execution plan review), so
every `.claude/**` read resolves to the live file per the staged-read
precedence rule.

Both tracks are `[ ]` pending. The intent-axis pre-screen was applied: the four
new agent roles, the reworked `edit-design`/`create-plan`/`create-final-design`
wiring, the de-warmed `design-review.md`, the `conventions.md` S2 extension, and
all forward-looking Decision Records are target-state and generated no findings.
The three findings below all fall in the `## Context and Orientation`
carve-out (always current-state) or in design prose describing today's
behavior.

## Findings

### CR1 [should-fix]
**Certificate**: Ref: existing agent `tools:` frontmatter
**Location**: `plan/track-1.md` §"Context and Orientation", "What is there today" bullet on agent definitions (the line reading "`.claude/agents/*.md` are the existing agent definitions … with `tools:` and `model:` frontmatter and minimal allow-lists"); ground truth in `.claude/agents/*.md`.
**Issue**: The current-state claim that existing agent definitions carry a `tools:` frontmatter key is wrong. None do.
**Evidence**: `grep -lE '^tools:' .claude/agents/*.md` matches 0 of 20 files. A frontmatter-key inventory across all 20 agents yields exactly `name`, `description`, `model` (20 each), and no `tools` key. The `Agent` tool *supports* a `tools:` allow-list — that harness capability is real and is what D13/D14's target-state plan relies on — but no existing agent file *carries* one today. The two sampled files (`review-workflow-consistency.md`, `review-code-quality.md`) confirm: frontmatter is `name` / `description` / `model` only.
**Proposed fix**: In the Track 1 "What is there today" bullet, change "with `tools:` and `model:` frontmatter and minimal allow-lists" to "with `model:` frontmatter; none currently carries a `tools:` allow-list, though the `Agent` tool supports one (the lever D13/D14 add)." Keeps the plan's intent intact — the new agents still add `tools:` allow-lists; only the description of today's state is corrected.
**Classification**: mechanical
**Justification**: current-state claim in the `## Context and Orientation` carve-out, single unambiguous correct rendering (the key is present or absent — it is absent), fix preserves plan intent (§`mechanical` rules 1-3).

### CR2 [should-fix]
**Certificate**: Ref: Phase 4 second check today
**Location**: `plan/track-2.md` §"Context and Orientation", "What is there today" bullet on `create-final-design.md` (the clause "its second check today is the absorption-style comparison rather than a fidelity check against episodes"); ground truth in `.claude/workflow/prompts/create-final-design.md` and `.claude/workflow/prompts/design-review.md`.
**Issue**: The current-state claim that today's Phase 4 second check is an "absorption-style comparison" is wrong. No absorption runs at Phase 4 today. This also contradicts the design's own Core Concepts, which correctly describes what the fidelity check replaces.
**Evidence**: `create-final-design.md` Sub-step A runs a PSI diagram-to-code verification (the verification tables), and Sub-step B routes `phase4-creation` through `edit-design`; the `phase4-creation` cold-read in `design-review.md` is **not** passed `research_log_path` ("Absent for the interactive mutation kinds and for Phase 4", design-review.md:66-70) and runs none of the absorption-completeness cross-check (which fires only for `phase1-creation` and `target=tracks`). So today's Phase 4 verification is PSI-against-code plus the cold-read's plan-deviation / implementation-grounded-diagram / no-leaked-identifier checks — not absorption. The design Core Concepts (design.md:88) correctly states the fidelity check "Replaces a PSI-only comparison against code." Track 2's orientation and the frozen design disagree about the same current state, and the track's version is the inaccurate one.
**Proposed fix**: In the Track 2 "What is there today" bullet, change "its second check today is the absorption-style comparison rather than a fidelity check against episodes" to "today the second check is a PSI diagram-to-code verification against the as-built code, not a fidelity check against episodes" — aligning the track orientation with the design's Core Concepts wording.
**Classification**: mechanical
**Justification**: current-state claim in the `## Context and Orientation` carve-out, one unambiguous correct rendering (the design already states the correct form, "a PSI-only comparison against code"), fix preserves plan intent (§`mechanical` rules 1-3). The design side already reads correctly, so no `design.md` edit is needed.

### CR3 [should-fix]
**Certificate**: Ref: S2 canonical wording location
**Location**: `plan/track-1.md` §"Context and Orientation" S2 bullet ("`conventions.md` S1/S2/S3 are the read-scope invariants. S2 today reads …") and Track 1 D18 ("the `conventions.md` S2 wording edit"); design.md Part 4 §"The S2 and S3 read-scope invariants" (repeated "S2 today reads", "the wording update in `conventions.md`"); ground truth in `.claude/workflow/research.md`, `.claude/workflow/conventions.md`, `.claude/workflow/design-document-rules.md`.
**Issue**: The plan and design attribute the canonical S2 invariant prose to `conventions.md`, but the canonical statement lives in `research.md`. `conventions.md` carries only a brief descriptive cross-reference, and `design-document-rules.md` carries a third restatement. D18's deliverable ("update `conventions.md` S2") therefore targets a file that does not hold the literal S2 sentence the track quotes, and may miss the site(s) a later reader takes literally.
**Evidence**: The quoted phrasing — "the log is read for decision content in exactly two places: at Step 4a/4b artifact authoring … and by the Phase 2 consistency review" — matches `research.md:116-118` verbatim ("**Read-scope discipline (S2).** … the log is read for decision *content* in exactly two places: at Step 4a/4b artifact authoring (to seed the carriers) and by the Phase-2 consistency review (as a cross-check)"). `conventions.md` does **not** contain that sentence; it has a file-layout-tree description (conventions.md:166-177, "Consumed by the later artifacts … at the two sanctioned read points (Step 4a/4b authoring, the Phase-2 consistency cross-check)") and a research-log row at conventions.md:86 with the same descriptive shape. `design-document-rules.md:103-104` carries a parenthetical restatement ("S2: the log is read for decision content only at Step 4a/4b authoring and the Phase-2 consistency cross-check"). So the S2 rule is stated in three files, and the one the track names (`conventions.md`) is the *least* canonical of the three. If D18's intent is to name the absorption agent as a sanctioned reader so a literal-S2 reader (or the Phase 2 consistency review) does not flag a third log reader, the edit needs to land where the literal "exactly two places" statement lives — `research.md` — and likely the `design-document-rules.md` restatement too, not only `conventions.md`.
**Proposed fix**: Two coordinated edits. (1) Plan/track side (actionable now): correct the Track 1 orientation S2 bullet to attribute the canonical S2 text to `research.md` §"Read-scope discipline (S2)" (with `conventions.md` and `design-document-rules.md` as restatement sites), and broaden D18's "Implemented in" / deliverable to "update the S2 statement at its canonical home in `research.md` and the restatements in `conventions.md` and `design-document-rules.md`" so the absorption agent is named wherever S2 is stated. (2) Design side: the same attribution appears in design.md Part 4 (frozen) — record the correction here, but its design-side half is deferred to the Phase 4 `design-final.md`; the actionable part is the plan/track-side D18 deliverable scope above.
**Classification**: design-decision
**Justification**: the fix changes which file(s) a deliverable (D18) targets — that is a scope choice with more than one plausible rendering (edit only `research.md`; edit all three; or keep `conventions.md` and add a forward-pointer), and getting it wrong leaves S2 literally violated by an un-named third log reader after implementation. Escalate per §`design-decision` ("multiple plausible fix renderings" and "the discrepancy reveals a … deliverable that may not reach its target"). The design half also touches frozen `design.md`, so its design-side correction defers to Phase 4 while the D18-scope half is the actionable plan-side fix.

## Evidence base

### Plan ↔ Code

#### Ref: edit-design apply/review/iterate cycle
- **Document claim**: Track 1 §Context and Orientation — `edit-design/SKILL.md` "runs an apply → auto-review → bounded iterate → present cycle … the apply step is the main agent writing the doc inline; the review step is one cold-read sub-agent (the `design-review.md` prompt run via a general-purpose spawn); Step 6 is the bounded iterate loop; Step 6 escalates to the user on budget exhaustion."
- **Search performed**: Read `.claude/skills/edit-design/SKILL.md` in full.
- **Code location**: `.claude/skills/edit-design/SKILL.md` — §Workflow line 48-51 (apply → auto-review → bounded iterate → present); Step 1 (line 167, apply via Edit/Write, inline); Step 4 (line 401-450, cold-read spawn `subagent_type: general-purpose` at line 449 running the `design-review.md` content); Step 6 (line 536-579, iterate loop, escalates to user on budget exhaustion at line 571-575).
- **Actual signature/role**: Matches on all four facets — apply step is inline `Edit`/`Write`, review step spawns `design-review.md` via `general-purpose`, Step 6 is the bounded iterate, Step 6 escalates to the user when budget is exhausted with blockers remaining.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: existing agent `tools:` frontmatter
- **Document claim**: Track 1 §Context and Orientation — existing `.claude/agents/*.md` carry "`tools:` and `model:` frontmatter and minimal allow-lists"; Class Design diagram (design.md:99-152) and D14 assume the `tools:` allow-list is the lever that cuts the tool surface.
- **Search performed**: `grep -lE '^tools:' .claude/agents/*.md`; frontmatter-key inventory via `awk` over the YAML block of every agent.
- **Code location**: `.claude/agents/*.md` (20 files).
- **Actual signature/role**: 0 of 20 carry `tools:`. Key inventory: `description` ×20, `model` ×20, `name` ×20, `tools` ×0. Sampled files confirm `name`/`description`/`model` only.
- **Verdict**: MISMATCHES
- **Detail**: The `tools:` key is absent from every existing agent; the current-state claim that they carry it is false. → CR1. (The `Agent` tool's support for `tools:` is a real harness capability — that part of D13/D14's target-state is reachable.)

#### Ref: readability-feedback audit contract
- **Document claim**: Track 1 §Context and Orientation — `readability-feedback/SKILL.md` "already encodes the audit contract the auditor reuses (range-sliced fan-out, enumerate-every-finding), but for rule-hardening from a finished doc, not for in-loop creation."
- **Search performed**: Read `.claude/skills/readability-feedback/SKILL.md` in full.
- **Code location**: `.claude/skills/readability-feedback/SKILL.md` — Procedure step 2 (line 33, "~200-line ranges … Cap at ~6 sub-agents"), step 3 (line 34, "one `general-purpose` sub-agent per range, in parallel … classifies every obscure passage"), audit sub-agent prompt (line 80-94, returns a `## Findings` list enumerating F1, F2, …); skill purpose (line 8, "The output is rule changes, not a rewrite of the audited doc").
- **Actual signature/role**: Range-sliced fan-out + enumerate-every-finding contract present; the skill is for rule-hardening from a finished doc, not in-loop creation.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: design-review.md is the single multi-axis reviewer
- **Document claim**: Track 1 §Context and Orientation — `design-review.md` "is the single multi-axis reviewer: it runs the comprehension questions, the structural findings, the absorption cross-check (which makes it read the research log), and a § Prose AI-tell additions block."
- **Search performed**: Read `.claude/workflow/prompts/design-review.md` in full.
- **Code location**: `.claude/workflow/prompts/design-review.md` — §Comprehension questions (line 326-353), §Structural findings (line 355-373), absorption-completeness cross-check reading the log (line 253-284 + `research_log_path` input line 66-70, applied to `target=design` at line 279-284), §Prose AI-tell additions (line 186-224).
- **Actual signature/role**: All four facets present in one reviewer. The absorption cross-check makes it read `research_log_path` for `phase1-creation`.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: create-plan Step 4b inline derivation + target=tracks cold-read
- **Document claim**: Track 2 §Context and Orientation — `create-plan/SKILL.md` Step 4b "derives the plan and track files inline (the planner authors them), then spawns the `design-review.md` cold-read sub-agent with `target=tracks` for the write-time cold-read."
- **Search performed**: Read `.claude/skills/create-plan/SKILL.md` Step 4b (offset 588, limit 130).
- **Code location**: `.claude/skills/create-plan/SKILL.md` Step 4b — inline planner authoring (lines 588-710, steps 1-8 "derive the plan and track files", "Help the user develop the plan"); step 9 (lines 711-716) "spawn the cold-read sub-agent via the `Agent` tool … with `target=tracks`".
- **Actual signature/role**: Planner authors plan + track files inline (no author sub-agent today), then step 9 spawns the `target=tracks` cold-read.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: create-plan Step 1c tier-aware resume + Step 4a session boundary
- **Document claim**: Track 2 §Context and Orientation — "Step 1c is the tier-aware resume check; its `full`-tier branch routes a committed-and-clean `design.md` with no plan to Step 4b, and an interrupted Step 4a (dirty or uncommitted `design.md`) back into the `edit-design` review loop. Step 4a today ends the session once `design.md` is frozen; the user re-invokes `/create-plan` and the startup protocol auto-resumes into Step 4b."
- **Search performed**: Read `.claude/skills/create-plan/SKILL.md` Step 1c (offset 131, limit 90); grep for "Step 4a … ends the session" (lines 527-545).
- **Code location**: `.claude/skills/create-plan/SKILL.md` — Step 1c (line 131); the `design.md`-exists-plan-absent branch (lines 174-204): committed-and-clean → auto-resume Step 4b (lines 192-197), uncommitted-or-dirty → resume Step 4a / re-enter `edit-design` review loop (lines 198-204); the session-boundary rule (lines 527-545, "Step 4a ends the session once `design.md` is frozen and committed; it does not flow straight into Step 4b. The user re-invokes `/create-plan`, and the startup protocol auto-resumes into Step 4b").
- **Actual signature/role**: Both the Step 1c routing and the Step 4a session-boundary description match exactly.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: create-final-design routes phase4-creation through edit-design + PSI verification
- **Document claim**: Track 2 §Context and Orientation — `create-final-design.md` "routes `phase4-creation` through `edit-design` and runs a diagram-to-code verification; its second check today is the absorption-style comparison rather than a fidelity check against episodes." Design Core Concepts (design.md:88) — fidelity "Replaces a PSI-only comparison against code."
- **Search performed**: Read `.claude/workflow/prompts/create-final-design.md` in full; grep `create-final-design.md` for absorption/episode/research-log/fidelity; grep `design-review.md` for phase4/research_log_path/absorption.
- **Code location**: `create-final-design.md` Sub-step A PSI verification tables (lines 169-193), Sub-step B route through `edit-design` `phase4-creation` (lines 195-217); `design-review.md` `phase4-creation` cold-read carries no `research_log_path` (line 66-70: "Absent for … Phase 4") and runs no absorption (absorption is `phase1-creation` / `target=tracks` only).
- **Actual signature/role**: PARTIAL — the "routes phase4-creation through edit-design" and "runs a diagram-to-code verification" halves MATCH; the "second check today is the absorption-style comparison" half MISMATCHES (today's Phase 4 second check is PSI-against-code, not absorption — the design's own Core Concepts says so correctly).
- **Verdict**: PARTIAL
- **Detail**: The absorption-style-comparison clause is wrong and contradicts the design's correct "PSI-only comparison against code". → CR2.

#### Ref: step + track episodes as as-built records
- **Document claim**: Track 2 §Context and Orientation — "the step and track **episodes** (per-step and per-track as-built records under the track files' `## Episodes` sections) carry what was built and why it diverged from the plan — the fidelity check's primary source."
- **Search performed**: Read track-1.md / track-2.md `## Episodes` placeholders; cross-checked `create-final-design.md` episode-aggregation language.
- **Code location**: track-1.md:279-280 and track-2.md:157-158 (`## Episodes` "Continuous-log. Phase B sub-step 7 appends one block per completed step"); `create-final-design.md` lines 46-55, 264-270, 300-305 (step episodes are ground truth, track episodes add strategic framing, both aggregated for Phase 4).
- **Actual signature/role**: Episodes are the per-step/per-track as-built records and the Phase 4 ground-truth source; matches.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: S2 canonical wording location
- **Document claim**: Track 1 §Context and Orientation + D18, design.md Part 4 — "`conventions.md` S1/S2/S3 are the read-scope invariants. S2 today reads 'the log is read for decision content in exactly two places: at Step 4a/4b artifact authoring … and by the Phase 2 consistency review'"; D18 deliverable is "the `conventions.md` S2 wording edit".
- **Search performed**: `grep -rnE` for the S2 statement across `.claude/workflow/*.md`; Read `research.md:110-127`, `conventions.md:86`, `conventions.md:160-189`, `design-document-rules.md:98-109`.
- **Code location**: Canonical statement at `research.md:116-118`; descriptive cross-references at `conventions.md:86` and `conventions.md:166-177`; restatement at `design-document-rules.md:103-104`.
- **Actual signature/role**: The literal "exactly two places" S2 sentence is in `research.md`, not `conventions.md`. The phrasing the track quotes matches `research.md` verbatim. `conventions.md` holds only the descriptive "two sanctioned read points" mention.
- **Verdict**: MISMATCHES
- **Detail**: D18 targets `conventions.md` for the S2 edit, but the canonical S2 prose (and the restatement) live in `research.md` and `design-document-rules.md`. → CR3.

### Design ↔ Plan

#### Flow: design-authoring dual-clean loop (Workflow sequenceDiagram)
- **Document claim**: design.md §Workflow sequenceDiagram (lines 171-197) — edit-design spawns the author (round 1 grounds whole doc), then per round runs ReadabilityAuditor (cold) and AbsorptionCheck (warm) in parallel, exits when both clean or budget (3) exhausted, then runs ComprehensionReview as the outer gate, then freeze+commit. Mirrored in Track 1 Plan of Work steps 1-4 and the Track 1 §Context and Orientation flowchart.
- **Trace**:
  1. design.md Workflow diagram (target-state) ↔ Track 1 Plan of Work concern 3 (lines 247-257): "Step 1 spawns the author … Step 4 spawns the per-round auditor-plus-absorption pair, then the cold comprehension gate … Step 6 is the dual-clean inner loop … exits only when both checks are clean or the iteration budget (default 3) is spent." — aligned.
  2. design.md Class Design diagram spawn arrows (lines 140-152): EditDesign → Author/ReadabilityAuditor/AbsorptionCheck/ComprehensionReview ↔ Track 1 flowchart (lines 199-216) ED → AU/RA/AB/CR — aligned.
  3. Phase-4 variant: design.md §Workflow note (lines 205-209) and §"The phase4 fidelity check" ↔ Track 2 flowchart (lines 91-108) CFD → AU/RA/FC — aligned (second check swaps to fidelity).
- **Divergence point**: none (target-state flow; consistent across design and both tracks).
- **Verdict**: MATCHES
- **Detail**: The loop shape is target-state and consistently described in design.md and both track files; not a current-state finding.

### Invariants

#### Invariant: D-record homing across the two tracks
- **Document claim**: Every D-record D1-D19 is homed in a track `## Decision Log`; the Component Map in implementation-plan.md attributes D1-9, D11-edit-design, D12-14, D16-19 to Track 1 and D10, D11-create-plan, D15 to Track 2.
- **Code evidence**: track-1.md declares D1-D9, D11, D12-D14, D16-D19 (17 records); track-2.md declares D10, D11, D15 (3 records). D11 appears in both, explicitly faceted ("D11 (edit-design facet)" in T1 line 103; "D11 (create-plan facet)" in T2 line 44), each cross-referencing the other. design.md per-section "D-records:" annotations cover D1-D19 (D10 at design.md:459, D15 at design.md:572, etc.).
- **Mechanism**: Two-track split at the core→downstream dependency boundary; D11 is one logical decision split by authoring point with documented cross-propagation duty.
- **Verdict**: ENFORCED
- **Detail**: No D-record is orphaned, missing, or contradictorily double-homed. D11's dual home is intentional and consistently documented in both directions.

#### Invariant: S-invariant homing across the two tracks
- **Document claim**: S1-S7 each map to a track `## Invariants & Constraints` entry with a verification approach.
- **Code evidence**: track-1.md homes S1, S2, S3, S4, S5, S7 (lines 363-369); track-2.md homes S3, S4, S6, S7 (lines 238-241). S3/S4/S7 appear in both (by design: S3/S4 cover both the design surface in T1 and the track surface in T2; S7 staging applies to every track). S5 is T1-only (the inner-loop exit condition), S6 is T2-only (Phase 4). design.md per-section "Invariants:" annotations cover S1-S7 (S5 at design.md:365, S6 at design.md:460, S7 at design.md:662/709).
- **Mechanism**: Each invariant carries a "verified by" clause naming the artifact/check that enforces it.
- **Verdict**: ASPIRATIONAL
- **Detail**: All seven invariants are homed and carry verification approaches; they are ASPIRATIONAL by construction (the tracks implement them), which is the expected state for a `[ ]` pending plan — not a finding (the pre-screen routes ASPIRATIONAL-with-implementing-track as no-finding; every S-invariant has a home track).

#### Invariant: S7 staging — no live .claude/** edits on this branch
- **Document claim**: S7 (both tracks) — the new routine stays staged under `_workflow/staged-workflow/` and the live workflow stays at develop state until the Phase 4 promotion.
- **Code evidence**: phase-ledger.md reads `s17=workflow-modifying`; `_workflow/staged-workflow/` does not exist yet (pre-execution); all "What is there today" reads resolved to live develop-state files.
- **Mechanism**: §1.7(b) staging; the implementer pre-commit live-path gate (cited in Track 1 S7 verification, line 368) and the Phase 4 promotion commit in `create-final-design.md` Step 4 (lines 421-480) enforce it.
- **Verdict**: ASPIRATIONAL
- **Detail**: Staging is target-state for the implementation; consistent with the absent staged subtree at plan-review time. Not a finding.
