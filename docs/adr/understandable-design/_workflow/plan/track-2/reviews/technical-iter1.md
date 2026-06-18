<!-- workflow-sha: ed3fe83cda372f371df18d63268aeb8cf6aebeb0 -->
# Technical review — Track 2 (iteration 1)

## Manifest

```yaml
review: technical
track: 2
iteration: 1
verdict: NEEDS REVISION
findings: 3
blockers: 0
index:
  - id: T1
    sev: should-fix
    anchor: "### T1 "
    loc: "track-2.md §Interfaces and Dependencies (In-scope/Out-of-scope); staged edit-design/SKILL.md Step 4 spawn-contract table + Step 6 round step 3"
    cert: "Premise: edit-design Step 4 defines the fidelity-check spawn contract"
    basis: read
  - id: T2
    sev: should-fix
    anchor: "### T2 "
    loc: "track-2.md §Plan of Work concern 2 + §Purpose; live create-final-design.md:219-225"
    cert: "Premise: create-final-design.md performs the absorption→fidelity swap"
    basis: read
  - id: T3
    sev: suggestion
    anchor: "### T3 "
    loc: "track-2.md §Context and Orientation (what-is-there-today, create-plan bullet); live create-plan/SKILL.md Step 4b derivation (588-746)"
    cert: "Premise: create-plan Step 4b derives the track files planner-inline today"
    basis: read
evidence_base:
  premises: 9
  edge_cases: 2
  integrations: 2
  confirmed: 10
  not_confirmed: 3
reference_accuracy: "Workflow-prose track — no production Java symbols named. Every reference verified as a workflow file path or §-anchor via grep + Read per the §1.7 workflow-machinery criteria; PSI not load-bearing. mcp-steroid not consulted (correct for a prose-only diff)."
```

## Findings

### T1 [should-fix]
**Certificate**: Premise: "edit-design Step 4 defines the fidelity-check spawn contract" (NOT FOUND); Integration: "the fidelity-check second check is spawned by the edit-design inner loop" (CALLERS AT RISK).
**Location**: `track-2.md` §Interfaces and Dependencies (the In-scope list lines 195-206 and the Out-of-scope list lines 209-212); the spawn site is staged `edit-design/SKILL.md` Step 4 (spawn-contract table at lines 505-510, absorption-check contract paragraph at lines 680-695) and Step 6 round step 3 (line 778).
**Issue**: Track 2 must add the fidelity-check **agent definition** AND wire it as the `phase4-creation` per-round second check. But the orchestrator that actually spawns the per-round second check is the staged `edit-design/SKILL.md`, not `create-final-design.md`. Staged `edit-design` Step 6 round step 3 reads "the round's second check (the `absorption-check` for `phase1-creation`, the fidelity check for `phase4-creation`)" (line 778), and Step 4's review-spawn section says "the fidelity-check role and its `phase4-creation` wiring are built in the Phase 4 track" (lines 457-458) and "For `phase4-creation` the second check is the fidelity check instead (built in the Phase 4 track) ... no `research_log_path` is passed on the Phase 4 path" (lines 692-695). The staged Step 4 spawn-contract table (lines 505-510) has rows only for `design-author`, `readability-auditor`, `absorption-check`, and `comprehension-review` — there is **no fidelity-check row**, and there is no spawn-contract paragraph for it (the absorption-check has one at 680-695; the fidelity check does not). So a fidelity-check spawn contract — `subagent_type`, the `Read`+PSI allow-list, and the params-file keys (the episodes path, the frozen `design.md` for the residual check, `draft_path=<design-final.md>`, and explicitly no `research_log_path`) — has to be added to staged `edit-design` Step 4 to match the absorption-check paragraph and the Step 6 spawn site. Track 2's In-scope list does not name `edit-design/SKILL.md`, and its Out-of-scope list (lines 209-212) explicitly assigns "the `edit-design` loop" to Track 1. As written, the agent definition would exist but `edit-design` would have no row telling the loop how to spawn it, leaving a dangling forward reference and an inner loop that names a check it cannot launch.
**Proposed fix**: At decomposition, add staged `.claude/skills/edit-design/SKILL.md` to Track 2's In-scope set with a narrow boundary — "Step 4 fidelity-check spawn-contract row + params keys only; the rest of the loop is Track 1's." Either (a) extend the Step 4 spawn-contract table with a fidelity-check row plus a sibling paragraph to the absorption-check one (the cleaner home, since the loop spawns from `edit-design`), or (b) if Track 2 means to keep all `edit-design` edits out, state explicitly where the fidelity-check spawn contract lives and how `edit-design` Step 6 reaches it. Reconcile the Out-of-scope line "the `edit-design` loop ... all Track 1" with whichever choice is made so the two lists do not contradict.

### T2 [should-fix]
**Certificate**: Premise: "create-final-design.md performs the absorption→fidelity swap" (WRONG).
**Location**: `track-2.md` §Purpose (lines 13-16) and §Plan of Work concern 2 (lines 130-138): "Rework `create-final-design.md` so the `phase4-creation` loop ... swaps the per-round second check from absorption to fidelity"; against live `create-final-design.md:219-225` and staged `edit-design/SKILL.md` lines 452-460, 692-695.
**Issue**: The track describes concern 2 as reworking `create-final-design.md` to "swap the per-round second check from absorption to fidelity." That mislocates the swap. The swap lives **inside the staged `edit-design/SKILL.md`**, keyed on `mutation_kind == phase4-creation` (Step 4 lines 452-460: "The second check is the warm `absorption-check` for `phase1-creation` and the **fidelity check** for `phase4-creation`"; Step 6 line 778). `create-final-design.md` does not perform any swap — it routes `phase4-creation` to `edit-design` (live lines 195-202) and `edit-design` selects the second check by kind. Crucially, the **live** `create-final-design.md` still describes the Phase 4 review as a single-agent pass: "the skill runs the standard atomic action — apply, mechanical checks ..., `whole-doc` cold-read on `design-final.md` via the design-review sub-agent" (lines 219-225). That description is stale against the staged `edit-design`, which now routes `phase4-creation` through the dual-clean multi-agent loop (author + per-round auditor + fidelity check + post-loop comprehension gate). So the real `create-final-design.md` work is not a swap — it is (a) refresh the Sub-step B prose so it describes the multi-agent `phase4-creation` loop the staged `edit-design` now runs, and (b) thread the caller-supplied inputs the fidelity check needs (the episodes path and the `output_path` the comprehension gate's `phase4-creation` branch already expects, staged `edit-design` lines 615-623). Left as "swap the second check," decomposition risks adding swap logic to `create-final-design.md` that duplicates what `edit-design` already does, or missing the actual stale-description fix.
**Proposed fix**: Rewrite concern 2 (and the §Purpose sentence) to state that `edit-design` already performs the kind-keyed swap, and that `create-final-design.md`'s job is to (1) refresh its Sub-step B description from the single `whole-doc` cold-read to the multi-agent `phase4-creation` loop, and (2) pass the fidelity check's inputs (episodes path; the `output_path` for the Phase 4 comprehension-gate cold-read). Keep the diagram-to-code verification table at entry as the track already says. This pairs with T1: the swap mechanism's missing half is the `edit-design` spawn-contract row, not a `create-final-design.md` edit.

### T3 [suggestion]
**Certificate**: Premise: "create-plan Step 4b derives the track files planner-inline today" (PARTIAL — accurate, but the §Context summary understates the second cold-read site the rework replaces).
**Location**: `track-2.md` §Context and Orientation, the `create-plan` what-is-there-today bullet (lines 71-78); against live `create-plan/SKILL.md` Step 4b (the "Help the user develop the plan" steps 1-9, lines 618-746).
**Issue**: The track's §Context says Step 4b "derives the plan and track files inline (the planner authors them), then spawns the `design-review.md` cold-read sub-agent with `target=tracks` for the write-time cold-read." That is correct (live lines 711-746 spawn a `general-purpose` agent running the full `design-review.md` content with `target=tracks`, returning inline). What the summary leaves implicit is that this existing Step-4b cold-read is a single post-write `general-purpose` spawn, whereas the rework replaces it with the full dual-clean inner loop (author spawn + per-round `readability-auditor` + per-round `absorption-check` + a post-loop de-warmed `comprehension-review` gate routed onto agent definitions, not `general-purpose`). The existing spawn is `subagent_type: general-purpose` with the whole `design-review.md` body pasted into the prompt (live lines 719-722); the rework moves to the four `subagent_type`-by-basename agent definitions Track 1 built. Decomposition benefits from this being explicit because it is a structural replacement (one general-purpose post-write read → a multi-agent loop), and the existing inline `iteration_budget`/escalation contract (live lines 739-744) is what the new inner loop's bounded-iterate must preserve (S5).
**Proposed fix**: Extend the §Context `create-plan` bullet (or concern 1 in §Plan of Work) to note that the rework replaces both the planner-inline authoring AND the single `general-purpose` `target=tracks` post-write cold-read with the agent-definition-based dual-clean loop, reusing the existing Step-4b `iteration_budget`/escalation contract (live lines 739-744) so the bounded-iterate termination is carried over, not re-invented. This is documentation-completeness, not a feasibility blocker.

## Evidence base

#### Premise: The four Track-1 agent definitions Track 2 reuses exist in the staged subtree with the claimed shapes
- **Track claim**: "Everything here reuses the author, auditor, and absorption roles built in Track 1" (§Purpose); reuses "Track 1's author, readability auditor, and absorption agent definitions" (§Context).
- **Search performed**: `find` over `_workflow/staged-workflow`; `Read` of all four agent files.
- **Code location**: `staged-workflow/.claude/agents/{design-author,readability-auditor,absorption-check,comprehension-review}.md`.
- **Actual behavior**: `design-author.md` (`Read, Write, Edit, Bash, mcp__localhost-6315__*`; `model: opus`; role `author`; phase `1,4`; `target` design|tracks; by-reference return contract). `readability-auditor.md` (`Read, Grep`; role `reviewer-readability`; phase `1,4`; `target=tracks` standing anchors = plan Component Map + each track's `## Purpose / Big Picture`; owns the prose AI-tell axis; never reads the log, S1). `absorption-check.md` (`Read, Grep`; `model: sonnet`; role `reviewer-absorption`; phase `1`; two-way coverage matching; `target` design|tracks). `comprehension-review.md` (`Read, Grep`; role `reviewer-design`; phase `1,4`; runs no prose axis, S4).
- **Verdict**: CONFIRMED
- **Detail**: All four exist with the shapes Track 2 assumes. Note `absorption-check` is phase `1` only (not `1,4`); consistent with Track 2 swapping absorption out for fidelity at Phase 4 — absorption is never spawned at Phase 4.

#### Premise: The auditor's `target=tracks` standing anchors are the plan Component Map + each track's Purpose / Big Picture
- **Track claim**: "Set the auditor's standing anchors to the plan Component Map and each track's Purpose / Big Picture" (§Plan of Work concern 1; D11 Risks/Caveats).
- **Search performed**: `Read` + grep of `staged-workflow/.claude/agents/readability-auditor.md`.
- **Code location**: `readability-auditor.md:64-65`.
- **Actual behavior**: "`target=tracks` — the plan Component Map and each track's `## Purpose / Big Picture`, because a track slice alone lacks the whole-plan vocabulary."
- **Verdict**: CONFIRMED
- **Detail**: The agent definition already carries exactly the anchor set concern 1 names; concern 1 is asserting an existing default, not asking Track 2 to add it. Track 2's wiring just needs to ensure the Step-4b spawn passes `target=tracks`.

#### Premise: By-reference orchestration holds (D15 gate A6 precondition)
- **Track claim**: "by-reference orchestration is a hard requirement (built in Track 1) ... if by-reference cannot hold, the boundary is retained (gate A6)" (D15 Risks/Caveats).
- **Search performed**: grep + `Read` of `track-1.md` `## Episodes` Track-completion block and `design-author.md` By-reference contract.
- **Code location**: `track-1.md:376` (Track-completion episode); `design-author.md:54-56`.
- **Actual behavior**: Track 1 Track-completion episode: "the by-reference orchestration contract (author spawns return a thin summary, never the drafted doc) holds, which Track 2's 4a/4b boundary collapse (D15) depends on." `design-author.md`: "you ... return only a thin summary ... Never return the drafted content in your reply."
- **Verdict**: CONFIRMED
- **Detail**: Gate A6's precondition is satisfied — by-reference is built and confirmed holding, so the D15 collapse may proceed rather than retaining the boundary.

#### Premise: `create-plan/SKILL.md` Step 1c and Step 4b exist and route as the track describes
- **Track claim**: Step 1c "routes a committed-and-clean `design.md` with no plan to Step 4b, and an interrupted Step 4a ... back into the `edit-design` review loop"; "Step 4a today ends the session" (§Context); D15 rewrites Step 1c auto-resume.
- **Search performed**: grep + `Read` of `create-plan/SKILL.md` lines 125-275 (Step 1c), 495-595 (Step 4 part 3 / boundary), 588-746 (Step 4b).
- **Code location**: `create-plan/SKILL.md:174-204` (committed-clean→4b, dirty→resume-4a), `:527-545` ("Step 4a ends the session ... does not flow straight into Step 4b"), `:588-746` (Step 4b derivation).
- **Actual behavior**: Step 1c routes "Committed AND clean → Auto-resume into Step 4b"; "Uncommitted OR dirty → Resume Step 4a." Boundary section: "In `full`, Step 4a ends the session once `design.md` is frozen and committed; it does **not** flow straight into Step 4b."
- **Verdict**: CONFIRMED
- **Detail**: Track 2's description of the current Step 1c routing and the "Step 4a ends the session" rule is accurate. These are exactly the surfaces D15 must rewrite (happy path no longer crosses the boundary; Step 1c becomes crash-recovery-only on dirty/absent plan after committed-clean design). The branch set is large (seven mutually-exclusive arms incl. `minimal`/`lite`/`full`/ledger-absent); decomposition for concern 3 must preserve every non-4a/4b arm.

#### Premise: `create-final-design.md` routes `phase4-creation` through `edit-design`; its build-time check is diagram-to-code, with no absorption check at Phase 4 today
- **Track claim**: "It routes `phase4-creation` through `edit-design` and its build-time check today is a PSI diagram-to-code verification ..., not a fidelity check against episodes (no absorption check runs at Phase 4 today)" (§Context).
- **Search performed**: grep + `Read` of `create-final-design.md` lines 150-250.
- **Code location**: `create-final-design.md:169-202` (Sub-step A verification tables + Sub-step B edit-design invoke); `:219-225` (atomic action description).
- **Actual behavior**: Sub-step A builds PSI diagram-to-code verification tables; Sub-step B invokes `edit-design` `phase4-creation`. The live atomic-action description is "`whole-doc` cold-read on `design-final.md` via the design-review sub-agent" — a single cold-read, with no absorption or fidelity per-round check named.
- **Verdict**: CONFIRMED
- **Detail**: Track 2's claim that no absorption check runs at Phase 4 today is correct. See T2: the live description is single-agent, so the real `create-final-design.md` work is description-sync to the staged multi-agent loop, not an in-`create-final-design.md` swap.

#### Premise: Staged `edit-design/SKILL.md` already routes `phase4-creation` through the dual-clean loop with fidelity as the second check
- **Track claim**: implicit in §Plan of Work concern 2 ("the `phase4-creation` loop keeps the author, auditor, and cold comprehension gate but swaps the per-round second check from absorption to fidelity").
- **Search performed**: `Read` of staged `edit-design/SKILL.md` (full, two pages) + grep for "fidelity".
- **Code location**: staged `edit-design/SKILL.md:52-56, 165-173, 286-297, 452-460, 615-665, 692-695, 708, 778`.
- **Actual behavior**: "On the creation kinds (`phase1-creation`, `phase4-creation`) the skill ... runs the dual-clean inner loop." Step 1: `phase4-creation` author "grounds on the step and track episodes and the live code rather than the research log (the Phase 4 second check is fidelity, not absorption)." Step 4: "The second check is the warm `absorption-check` for `phase1-creation` and the **fidelity check** for `phase4-creation` (the fidelity-check role and its `phase4-creation` wiring are built in the Phase 4 track)." `output_path` injected only for `phase4-creation` (the comprehension gate writes to file + returns a summary).
- **Verdict**: CONFIRMED
- **Detail**: The swap mechanism is already in the staged `edit-design`, keyed on `mutation_kind`. This is the load-bearing fact behind T1 and T2: Track 2 supplies the missing pieces (the agent definition + the `edit-design` spawn-contract row + the `create-final-design.md` description-sync), not a re-wiring of the loop itself.

#### Premise: Staged `edit-design` Step 4 defines a spawn contract for the fidelity check
- **Track claim**: §Signatures/contracts ("the Step 4b and Phase 4 loops spawn the same roles via the `Agent` tool against their agent definitions, target-parameterized"); concern 2 ("Add the fidelity-check agent definition ... and wire the Phase 4 path").
- **Search performed**: grep "fidelity" over staged `edit-design/SKILL.md`; `Read` of the Step 4 spawn-contract table (505-510) and the absorption-check spawn paragraph (680-695).
- **Code location**: staged `edit-design/SKILL.md` spawn-contract table at `:505-510` — **no fidelity-check row**; absorption-check spawn paragraph `:680-695` — **no fidelity counterpart**.
- **Actual behavior**: The Step 4 table lists `design-author`, `readability-auditor`, `absorption-check`, `comprehension-review` only. The fidelity check is named in prose (lines 457-458, 692-695) as "built in the Phase 4 track," with no `subagent_type`/allow-list/params row. The Reference block (lines 1122-1124) lists the four agent definitions, not a fifth.
- **Verdict**: NOT FOUND
- **Detail**: The fidelity-check spawn contract is a forward reference Track 1 left for Track 2. Producing it requires editing staged `edit-design` Step 4 (the loop's spawn site), which Track 2's In-scope list omits and Out-of-scope list assigns to Track 1. Drives T1.

#### Premise: Staged `design-review.md` supports `target=tracks` and the path-conditional `output_path` branch the Step-4b and Phase-4 cold-reads need
- **Track claim**: concern 1 reuses the de-warmed comprehension reviewer at Step 4b; concern 2's comprehension gate persists output via `output_path` at Phase 4.
- **Search performed**: grep over staged `design-review.md` for `target=tracks` / `output_path` / `path-conditional` / fidelity; `Read` of the `phase4-creation` mutation-kind section.
- **Code location**: staged `design-review.md:46-48, 87-95` (Inputs: `target`, `output_path`), `:206-313` (§Track-scoped cold-read, `reviewer-design | 1`), `:140-153` ("the readability auditor owns the prose axis on `phase4-creation` too"), `:330-336` (path-conditional output).
- **Actual behavior**: `target=tracks` is a first-class input with its own §Track-scoped cold-read section (roles `reviewer-design`, phase `1`); `output_path` triggers file-write-plus-summary; the `phase4-creation` section asserts the auditor owns the prose axis (S4) and lists the Phase-4-specific checks (plan-deviation, implementation-grounded diagrams, no leaked working-file IDs).
- **Verdict**: CONFIRMED
- **Detail**: The comprehension reviewer can run the `target=tracks` track-scoped cold-read (concern 1) and the `phase4-creation` whole-doc cold-read with `output_path` (concern 2). Both downstream consumers are supported by the staged prompt.

#### Premise: `planning.md`, `workflow.md`, and `conventions.md` describe the 4a/4b boundary that D15's collapse touches
- **Track claim**: §Interfaces lists `planning.md` (touch only if the Step 4b authoring description references the retired inline flow) and `workflow.md`/`conventions.md` (touch only where the auto-resume contract or §1.7 staging reads describe the 4a/4b boundary).
- **Search performed**: grep over each live file for the boundary terms; `Read` of `workflow.md:58-75`.
- **Code location**: `workflow.md:60-75` (Step 4a/4b "mandatory session boundary" + rationale); `conventions.md:174` ("sanctioned read points (Step 4a/4b ...)"); `planning.md:85-96, 159-165` (design-first two-session model + per-tier session description).
- **Actual behavior**: `workflow.md` states "The Step 4a → Step 4b boundary is a **mandatory session boundary** ... it keeps design-authoring context from biasing plan derivation." `planning.md` repeats the two-session derivation model. `conventions.md` references Step 4a/4b read points.
- **Verdict**: CONFIRMED
- **Detail**: All three files Track 2 lists as boundary touch-ups do carry the boundary prose D15 must reconcile. The `workflow.md` "mandatory session boundary" sentence and its rationale are the load-bearing dependent prose: D15 must rewrite it from "mandatory session boundary" to a logical freeze gate whose isolation is supplied by sub-agent authoring. (Separately observed but NOT a Track 2 finding: `workflow.md:64` "The review runs adversarial first, then cold-read" is stale against the relocated-adversarial-gate D6 — a pre-existing inconsistency outside this track's scope.)

#### Edge case: D15 collapse on a very large design that lengthens the combined session
- **Trigger**: a `full`-tier design large enough that, even with by-reference authoring, the combined (4a-then-4b) `/create-plan` session grows long.
- **Code path trace**:
  1. D15 Risks/Caveats @ `track-2.md:54` — "A very large design can make even the by-reference combined session long; the mid-phase handoff and the context monitor mitigate it as for any long phase."
  2. `create-plan/SKILL.md:131-275` Step 1c — the auto-resume routing must still re-enter cleanly if the combined session is interrupted mid-way; D15 re-specs Step 1c to crash-recovery-only on dirty/absent plan after committed-clean design.
- **Outcome**: handled by design — the freeze-and-commit after design authoring stays as the crash checkpoint (D15 Risks/Caveats line 54), so an interrupted combined session resumes at the committed-clean-design boundary exactly as the staged Step 1c crash-recovery arm specifies.
- **Track coverage**: yes — D15 Risks/Caveats and the §Idempotence/Recovery Phase-A placeholder cover it; decomposition should populate the per-step recovery path so the crash checkpoint is explicit.

#### Edge case: by-reference contract violated at runtime (an author spawn returns the full draft)
- **Trigger**: an author sub-agent returns more than a thin summary during a Step-4b or Phase-4 loop.
- **Code path trace**:
  1. Spawn returns full draft @ staged `edit-design/SKILL.md:1041-1044` — "The author spawn returns the draft inline instead of a thin summary (a by-reference contract violation): the orchestrator's context is at risk, but the draft is on disk at `output_path`, so do not re-spawn. Proceed ... and note the contract violation in the review log."
  2. D15 gate A6 @ `track-2.md:54, 244` — "if by-reference cannot hold, the boundary is retained rather than collapsed."
- **Outcome**: the loop itself tolerates a single violation (proceed with the on-disk draft, log it). The D15 *design-time* gate A6 is the stronger guard: if by-reference structurally cannot hold, the boundary is retained. The two are complementary, not contradictory — A6 is a build-time decision; the edit-design recovery is a runtime tolerance.
- **Track coverage**: yes — gate A6 is explicit in D15 and §Invariants Constraint (by-reference). Decomposition should make the concern-3 step's acceptance record whether A6 passed (collapse applied) or failed (boundary retained, criterion deferred), which §Validation already anticipates (lines 176-181).

#### Integration: create-plan Step 4b → the four Track-1 agent definitions
- **Plan claim**: concern 1 replaces planner-inline derivation with an author spawn + the dual-clean loop (auditor + absorption second check), keeping the de-warmed comprehension reviewer with no prose axis (S4) and the S3 freeze-order gate.
- **Actual entry point**: live `create-plan/SKILL.md:711-746` — the current write-time cold-read (`general-purpose` spawn running the whole `design-review.md`, `target=tracks`, inline return, with an `iteration_budget`/escalation contract).
- **Caller analysis**: Step 4b is the only consumer; the spawn target migrates from `general-purpose` to the four `subagent_type`-by-basename agent definitions. The S3 gate already exists in the staged `edit-design` (lines 547-581) and is mirrored on the track path via §Invariants S3 (line 239).
- **Breaking change risk**: low — the replacement is internal to Step 4b; no other phase consumes Step 4b's output shape. The existing `iteration_budget`/escalation contract (live lines 739-744) must be preserved (S5), per T3.
- **Verdict**: MATCHES

#### Integration: create-final-design.md → staged edit-design phase4-creation loop
- **Plan claim**: concern 2 reworks `create-final-design.md` so the Phase 4 path runs author + auditor + comprehension gate with the fidelity check as the second check, keeping diagram-to-code verification at entry.
- **Actual entry point**: live `create-final-design.md:195-225` (Sub-step B invokes `edit-design` `phase4-creation`; the description still says single `whole-doc` cold-read). Staged `edit-design` already runs the multi-agent `phase4-creation` loop.
- **Caller analysis**: `create-final-design.md` is the sole Phase 4 caller of `phase4-creation`. The diagram-to-code verification table is its own Sub-step A (lines 169-193) and runs at entry, matching D10's "runs once at entry."
- **Breaking change risk**: medium-low — the live `create-final-design.md` description is stale against the staged `edit-design` (single cold-read vs multi-agent loop). The rework must sync the description and pass the fidelity check's inputs; it must NOT re-implement the swap (T2). The fidelity-check spawn contract gap (T1) must be closed in `edit-design` Step 4 for this integration to be launchable.
- **Verdict**: CALLERS AT RISK (resolvable via T1 + T2; the integration is feasible once the spawn contract is added and the description synced).
