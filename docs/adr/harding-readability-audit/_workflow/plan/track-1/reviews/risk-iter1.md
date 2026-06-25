<!-- MANIFEST
role: reviewer-risk
track: "Track 1: Harden readability-auditor slicing and convergence"
iteration: 1
verdict: PASS
findings: 4
blockers: 0
index:
  - id: R1
    sev: should-fix
    anchor: "### R1 "
    loc: ".claude/skills/edit-design/SKILL.md:832-837"
    cert: "Exposure: Step 6 convergence-claim prose vs the D8 never-clean tail"
    basis: trace
  - id: R2
    sev: should-fix
    anchor: "### R2 "
    loc: ".claude/agents/readability-auditor.md:75-83"
    cert: "Assumption: the auditor params block must gain slice_count/total_lines and the guard, or the new params are inert"
    basis: grep+read
  - id: R3
    sev: suggestion
    anchor: "### R3 "
    loc: "design.md D1/D2; edit-design Step 4 self-check"
    cert: "Exposure: the deterministic-partition obligation between the orchestrator self-check and the agent guard"
    basis: trace
  - id: R4
    sev: suggestion
    anchor: "### R4 "
    loc: ".claude/skills/create-plan/SKILL.md:810-826"
    cert: "Assumption: the track-path partition the D5 cross-reference re-points to already exists and is per-file deterministic"
    basis: read
evidence_base:
  exposures: 3
  assumptions: 3
  testability: 1
-->

# Risk review — Track 1: Harden readability-auditor slicing and convergence (iteration 1)

This is a workflow-prose change. There is no Java symbol surface, so every reference is verified as a workflow path / `§`-anchor via grep + Read (mcp-steroid PSI is not applicable), and the five prose criteria replace the Java-oriented (WAL / crash / storage / hot-path) criteria. The "critical path" is the dual-clean review loop the change modifies; the "blast radius" of a bug is the loop misbehaving — not converging, suppressing a real finding, or eroding the cold read. The staging routing (I6) is verified through the live develop-state files, because the staged tree does not exist yet at Phase A (ledger `s17 = workflow-modifying`, no `_workflow/staged-workflow/`).

Verdict: PASS. No blocker. Two should-fix items are prose-coherence couplings the implementer must carry as part of the edit (an adjacent contradiction in Step 6, and the agent-file params block the two new fields depend on); two suggestions note low-probability risks.

## Findings

### R1 [should-fix]
**Certificate**: Exposure — "Step 6 convergence-claim prose vs the D8 never-clean tail"
**Location**: Track `## Plan of Work` step 2 + `edit-design/SKILL.md:832-837` (the live convergence claim)
**Issue**: The live `edit-design` Step 6 closes with "the loop moves monotonically toward dual-clean — typically one or two rounds. The budget plus escalation is the backstop for a pathological case, not the expected path." (lines 832-837). The track's step 2 writes the canonical convergence mechanism into this same Step 6, and D8 introduces an explicit *never-clean tail*: a section that is irreducibly dense but acceptable never becomes settled and re-audits every round, so the loop rides `iteration_budget` + S5 to exit. If the implementer adds the settled-state mechanism without reconciling the existing "moves monotonically … pathological case, not the expected path" paragraph, the section will carry two adjacent claims that contradict: one says budget exhaustion is pathological, the other says the budget-plus-S5 tail is the *designed* exit for an acceptable-density doc. Likelihood: high (the paragraph is in the exact section the edit lands in). Impact: a reader of the converged loop cannot tell whether hitting the budget is a failure or the intended terminal state — a rule-coherence / non-contradiction defect, the prose-criteria analogue of a logic bug.
**Proposed fix**: Make step 2 of the Plan of Work explicit that it *reconciles* the existing line 832-837 convergence paragraph, not merely appends beside it — restate "monotonic toward dual-clean" as "monotonic on the settled sections; the never-clean dense-but-acceptable tail is bounded by `iteration_budget` + S5, which is a designed exit, not a pathology." Decomposition should tag the Step-6 step with this as an explicit in-scope edit so a step-level reviewer checks the reconciliation.

### R2 [should-fix]
**Certificate**: Assumption — "the auditor params block must gain slice_count/total_lines and the guard, or the new params are inert"
**Location**: Track D2 / Plan of Work step 3 + `agents/readability-auditor.md:75-83` (the `## Inputs` block) and `:47` ("You are range-sliced")
**Issue**: The agent's `## Inputs (read from the params file first)` block (lines 75-83) enumerates exactly `target`, `target_path`, and `range`, and closes "The params file names no research-log path." D2 adds two new params fields (`slice_count`, `total_lines`) that the orchestrator passes and the whole-doc guard consumes. The guard is only computable if this `## Inputs` block is amended to (a) list the two new fields and (b) state the guard rule (`slice_count == 1 AND total_lines > ~300` → wiring error). The track's D2 and Plan-of-Work step 3 name "turn 'Range-sliced fan-out' into a hard requirement, add the whole-doc guard" — but the `## Interfaces and Dependencies` table row for `readability-auditor.md` lists the touched sections as `"Range-sliced fan-out"; cold-read guarantee`, and does **not** name the `## Inputs` block. If the implementer edits only the "Range-sliced fan-out" prose (line 25, in `## Who you are`) and the cold-read note, but leaves the `## Inputs` block at three fields, the orchestrator passes two params the agent contract never reads and the guard has no documented inputs — the new params are inert and the secondary detector silently does nothing. Likelihood: medium (the section table under-specifies the touched anchors). Impact: the agent-side guard — the entire backstop for the orchestrator-collapse case — is non-functional, defeating the issue's "detectable, not silent" requirement, while inspection of the prose-only edit would look complete.
**Proposed fix**: Add `## Inputs` to the `readability-auditor.md` row of the track's `## Interfaces and Dependencies` table (and to the Plan-of-Work step 3 enumeration), explicitly calling out that the two new fields and the guard condition land in the params `## Inputs` block, not only in the "Range-sliced fan-out" descriptive prose. This makes the params↔guard coupling an inspectable obligation for the Phase C `instruction-completeness` reviewer.

### R3 [suggestion]
**Certificate**: Exposure — "the deterministic-partition obligation between the orchestrator self-check and the agent guard"
**Location**: Track D1 / D2; `design.md §"Verifiable spawn count without a script"` and `§"The agent-side whole-doc guard"`
**Issue**: The two enforcement layers cover *different* collapse shapes, and the track's own framing ("deliberate redundant double-check") could read as if they are coextensive. The orchestrator self-check (`slices_spawned == expected_slice_count`) catches *any* count mismatch — including a partial collapse, e.g. spawning 2 slices when the partition demands 5. The agent-side guard fires only on the *total* collapse `slice_count == 1 AND total_lines > ~300`; a partial collapse (2 slices on a doc that should have 5) does **not** fire the guard. So the redundancy is asymmetric: the guard is a strict subset of what the self-check covers, and the self-check is the *sole* catcher of a partial collapse. Since the self-check is the orchestrator's own assertion in working memory (no script, not visible to the auditor — design.md:213), a partial-collapse bug survives if the orchestrator both mis-partitions and mis-self-checks. Likelihood: low (the self-check is a pure function of inputs the orchestrator holds). Impact: the "detectable, not silent" guarantee is full only for the total-collapse case; a partial collapse is caught only by the orchestrator's honesty about its own count.
**Proposed fix**: No structural change required — the design's lightness call (prose obligation over a script) was made deliberately at D1 and accepted by the user. Note in D1's Risks/Caveats (or the Step-4 prose) that the agent guard covers only the total-collapse case and the self-check is the sole catcher of a partial-count mismatch, so a reader does not over-trust the "double-check" as covering every fan-out error. This keeps the residual visible rather than implied-away.

### R4 [suggestion]
**Certificate**: Assumption — "the track-path partition the D5 cross-reference re-points to already exists and is per-file deterministic"
**Location**: Track D5 / Plan of Work step 5; `create-plan/SKILL.md:810-826`
**Issue**: D5 and Plan-of-Work step 5 have `create-plan` Step 4b item 9 *cross-reference* the canonical convergence mechanism and the slicing principle rather than restate them. Verified against the live file: Step 4b item 9 already carries a deterministic per-file slice rule ("one `readability-auditor` spawn per `plan/track-N.md` … this per-file rule is the deterministic partition", lines 812-821) and already names the track-path anchors (Component Map + each track's Purpose, lines 822-826). So the track path is already partly hardened on slicing — the cross-reference must *point at the design-path canonical statement without re-opening or contradicting* the existing per-file rule (the two partitions differ by design: per-window on the design path, per-file on the track path). There is a latent drift risk: if step 5's cross-reference is written as "applies the same partition," it would contradict the existing, correct per-file rule. Likelihood: low (the track text in D2 already distinguishes "per-file unit on the track path"). Impact: a sloppy cross-reference could make the two slicing rules read as conflicting, a rule-coherence defect on a file that is currently self-consistent.
**Proposed fix**: When writing step 5's `create-plan` cross-reference, preserve the existing per-file partition rule verbatim and frame the cross-reference as "the convergence *mechanism* (settled-state + anchor-folded hash) is shared and parameterized; the *slicing unit* differs (per-window design / per-file track) and stays as already stated here." Decomposition should note that the existing lines 810-826 are not to be replaced, only referenced.

## Evidence base

#### Exposure: cold-read-guarantee exposure of the two new spawn params (S1)
- **Track claim**: `slice_count` and `total_lines` are slicing metadata constant across a round's fan-out, so passing them cannot nudge any auditor toward a finding and does not erode the cold read (D2 Rationale; design.md:232, 241).
- **Critical path trace**:
  1. Orchestrator computes the partition and `expected_slice_count` from `total_lines` @ `edit-design/SKILL.md` Step 4 (the operative home D1 adds) and `design.md:118`.
  2. Orchestrator writes one params file per spawn under `_workflow/plan/` (→ `_workflow/reviews/` post-D6) carrying `target`, `target_path`, `range`, + new `slice_count`, `total_lines` @ `edit-design/SKILL.md:513-525`.
  3. Auditor reads its params file first @ `readability-auditor.md:75-83`; reads only `house-style.md`, its slice, and the standing anchors @ `:27-29, :62-69`; the params file names no log path @ `:83`.
  4. Guard evaluates `slice_count == 1 AND total_lines > ~300` @ `readability-auditor.md` (the addition D2 makes) → wiring error, else proceeds.
- **Blast radius**: if a *per-round-varying* value were passed, it would bust the shared-prompt cache (edit-design:526) and could prime the reader. The two new fields are constant across the fan-out (design.md:241, 98), so neither effect occurs. The settled-state is held entirely orchestrator-side (D3) and never enters a params file, so no conclusion leaks to the auditor.
- **Existing safeguards**: the S1 invariant is hard-coded in `readability-auditor.md:27-29` ("Your tool allow-list is `Read` plus `Grep` … the only paths you read are `house-style.md`, your document slice, and the standing anchors") and `:83` (no research-log path; "if you find one, that is a wiring error"). The track's S1 invariant in `## Invariants & Constraints` restates this and ties verification to inspection of the spawn-params definitions.
- **Residual risk**: LOW. The cold-read guarantee holds for the metadata fields. The one residual is documentation completeness, not leakage — see R2 (the `## Inputs` block must list the two fields for the guard to function at all).

#### Exposure: Step 6 convergence-claim prose vs the D8 never-clean tail
- **Track claim**: step 2 writes the canonical convergence mechanism into `edit-design` Step 6, with the never-clean tail bounded by `iteration_budget` + S5 (D8; design.md:317-348).
- **Critical path trace**:
  1. Live Step 6 dual-clean loop @ `edit-design/SKILL.md:764-846`.
  2. Loop bound `iteration_budget` default 3, exit-to-user on exhaustion @ `:760, :818-819`.
  3. Existing closing claim "moves monotonically toward dual-clean — typically one or two rounds. The budget plus escalation is the backstop for a pathological case, not the expected path." @ `:832-837`.
  4. D8 never-clean tail: a dense-but-acceptable section re-audits every round and exits via budget + S5 as a *designed* path @ `design.md:325-338`.
- **Blast radius**: an unreconciled edit leaves two adjacent contradicting claims in the converged loop's terminal-state description.
- **Existing safeguards**: none in prose — this is exactly the rule-coherence axis the Phase C `writing-style` / `instruction-completeness` reviewers catch, but only if the edit touches both paragraphs.
- **Residual risk**: MEDIUM → see R1. The contradiction is in the exact section the edit lands in, so the implementer must reconcile rather than append.

#### Exposure: deterministic-partition robustness — self-check vs guard coverage
- **Track claim**: the self-check and the agent guard "enforce the floor independently; this is a deliberate redundant double-check, not a redundancy bug" (D1/D2 Risks).
- **Critical path trace**:
  1. Self-check `slices_spawned == expected_slice_count`, surfaces any count mismatch @ `design.md:205, 213`.
  2. Agent guard fires only on `slice_count == 1 AND total_lines > ~300` @ `design.md:234`.
- **Blast radius**: a partial collapse (e.g., 2 slices where 5 are expected) is caught by the self-check only, never by the guard. If the orchestrator both mis-partitions and mis-self-checks, a partial collapse is undetected.
- **Existing safeguards**: the self-check is a pure function of `total_lines`, the ~200-line window, and the ~6 cap (design.md:205), so a mis-self-check requires an arithmetic error in working memory — low probability.
- **Residual risk**: LOW → see R3. Acceptable given the deliberate prose-over-script lightness call (D1), but worth stating so the redundancy is not over-trusted.

#### Assumption: the auditor params block must gain the two fields and the guard
- **Track claim**: D2 makes the guard computable via two new params and turns "Range-sliced fan-out" into a hard requirement.
- **Evidence search**: Read `readability-auditor.md:25, 47, 75-83`; grep for the params `## Inputs` enumeration.
- **Code evidence**: `## Inputs` block (lines 75-83) lists exactly `target`/`target_path`/`range`; the `## Interfaces and Dependencies` track table names the touched sections as `"Range-sliced fan-out"; cold-read guarantee` and omits `## Inputs`.
- **Verdict**: UNVALIDATED (incomplete coverage in the track's interface table).
- **Detail**: the two new fields and the guard condition must land in the params `## Inputs` block for the guard to function; the track text names the change but the interface table under-specifies the touched anchor. See R2.

#### Assumption: the track-path partition the D5 cross-reference re-points to already exists
- **Track claim**: D5 cross-references the canonical mechanism from `create-plan` Step 4b item 9 with track-path parameters; D2 notes the track-path unit is "per file, not per window."
- **Evidence search**: Read `create-plan/SKILL.md:764-883`.
- **Code evidence**: Step 4b item 9 already carries "one `readability-auditor` spawn per `plan/track-N.md` … this per-file rule is the deterministic partition" (lines 812-821) and names the track-path anchors (Component Map + per-track Purpose, lines 822-826); `iteration_budget` default 3 + S5 exit (lines 847-851).
- **Verdict**: VALIDATED (the partition exists and is already per-file deterministic).
- **Detail**: the cross-reference must point at the convergence *mechanism* without re-opening the already-correct per-file slicing rule; the two partitions differ by design. Latent drift risk only — see R4.

#### Assumption: I6 staging gives a clean, reversible rollback
- **Track claim**: every edit lands under `_workflow/staged-workflow/.claude/...`; the live tree is untouched until a single Phase 4 promotion commit; revert the promotion commit to roll back (I6; D7).
- **Evidence search**: Read `conventions.md §1.7(a)/(f)/(k)`, the phase ledger; grep for `staged-workflow` and the promotion guard.
- **Code evidence**: staged-subtree layout @ `conventions.md:955-974`; "a single Phase 4 promotion commit copies the staged tree live" @ `:933`; the promotion guard keys on the `staged-workflow/` subdirectory presence @ `:1072-1077`. §1.7(k) criterion 2 explicitly names "the step-implementation orchestration loop" as a disqualifier for the prose-rule opt-out @ `conventions.md:1370-1372`, so D7's choice of full staging over the opt-out is correct: the edited dual-clean orchestration loop is executable procedure.
- **Verdict**: VALIDATED.
- **Detail**: rollback is reverting one promotion commit; no irreversible state change. The live workflow stays at develop-state for the branch's lifetime, so no running phase reads a half-modified loop (I6). The accepted trade-off — the branch cannot dogfood its own fixes — is a non-regression, correctly stated in D7.

#### Testability: spec-inspection-only coverage for a prose-machinery change
- **Coverage target**: 85% line / 70% branch — not applicable; there is no Java symbol surface and no unit test.
- **Difficulty assessment**: correctness is established by spec inspection and the Phase C workflow reviewers (`writing-style`, `instruction-completeness`, `consistency`, `prompt-design`, `context-budget`), per the track's `## Validation and Acceptance` and `## Invariants & Constraints`.
- **Existing test infrastructure**: none for prose; the analogue is the Phase C workflow-reviewer fan-out and the `workflow-reindex.py --check` TOC validator (readability-feedback Validation, conventions §1.8).
- **Feasibility**: ACHIEVABLE.
- **Detail**: every invariant in the track (I6, S1, S4, whole-doc floor, deterministic-partition, verifiable-count) maps to an inspectable assertion. The two should-fix items (R1, R2) are precisely the inspection points a Phase C reviewer must hit; tagging them in decomposition makes them visible. No invariant is un-inspectable. The acceptance criteria are behavioral statements verifiable by reading the promoted files, which is the correct coverage story for a prose change.
