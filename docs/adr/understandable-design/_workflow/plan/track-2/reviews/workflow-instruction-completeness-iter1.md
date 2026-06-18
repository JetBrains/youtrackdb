<!--
MANIFEST
dimension: workflow-instruction-completeness
target: Track 2 (87f40db9afc95a8bec478d05eabf20d317f03526..HEAD)
findings_count: 4
high_water_mark: 4
flags: CONTRACT_OK
evidence_base: present
cert_index: present
index:
  - id: WI1
    sev: Recommended
    anchor: "#wi1-recommended--fidelity-check-design_path-frozen-design-seed-has-no-producer-on-the-phase-4-path"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/workflow/prompts/create-final-design.md:229-247"
    cert: C1
    basis: judgment
  - id: WI2
    sev: Recommended
    anchor: "#wi2-recommended--create-plan-step-4b-comprehension-gate-on-full-passes-no-output_path-yet-names-the-seedtrack-fidelity-criterion"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md:862-872"
    cert: C2
    basis: judgment
  - id: WI3
    sev: Minor
    anchor: "#wi3-minor--edit-design-step-4-fidelity-row-reuses-the-design_path-token-for-two-different-files-in-adjacent-lines"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md:704-707"
    cert: C3
    basis: judgment
  - id: WI4
    sev: Minor
    anchor: "#wi4-minor--create-plan-step-4b-absorption-check-design_path-routing-condition-when-the-orchestrator-routes-the-seedtrack-fidelity-check-here-is-undefined"
    loc: "docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md:826-837"
    cert: C4
    basis: judgment
-->

## Findings

### WI1 [Recommended] — fidelity-check `design_path` (frozen-design seed) has no producer on the Phase 4 path

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/workflow/prompts/create-final-design.md` (lines 229-247)
- **Axis:** phase output → next-phase input
- **Cost:** the fidelity check's third required param is never threaded from the Phase 4 orchestrator, so the agent runs its precision/residual reference against the wrong file (or none) — silently weakening the does-this-match-the-code check the track exists to add.

The fidelity-check agent definition declares three params it reads from the params file: `episodes_path`, `draft_path`, and `design_path` (the frozen `design.md` seed, "for the residual reference only" — `fidelity-check.md` lines 60-63). The `edit-design` Step 4 spawn contract correspondingly writes `design_path=<frozen design.md>` into the fidelity check's params file (`edit-design/SKILL.md` line 707).

But on the Phase 4 path the skill's own `design_path` **argument** is `design-final.md`, not the frozen `design.md` (`create-final-design.md` line 211: "`design_path`: `docs/adr/<dir-name>/design-final.md`"; corroborated by `edit-design/SKILL.md` lines 900-901, which fix `design_path = docs/adr/<dir>/design-final.md` for `phase4-creation`). The orchestrator therefore cannot derive the frozen `design.md` path from its `design_path` argument — that argument points at the draft being checked, which the contract already maps to `draft_path`.

Sub-step B's input-threading block names exactly the inputs the fidelity check needs beyond the standard skill args, and it threads **only two**: "The fidelity check needs two more inputs threaded through to it … the **episodes path** … and the **`output_path`** …" (lines 229-247). The frozen `design.md` path is read by this prompt in Step 2 (line 37, `docs/adr/<dir-name>/_workflow/design.md`), so the value is available — it is simply never passed on as the fidelity check's `design_path`. The agent's own fallback ("Read the frozen `design.md` only … **when the params file names it**", `fidelity-check.md` line 48) means the residual reference is silently dropped rather than erroring, so this is a should-fix, not a blocker.

- **Suggestion:** add a third bullet to the Sub-step B threading block: the **frozen design path** — `docs/adr/<dir-name>/_workflow/design.md` (the same file read in Step 2) — passed as the fidelity check's `design_path`, distinct from the skill's `design_path` argument which is `design-final.md`. Or, in `edit-design` Step 4, derive the fidelity check's `design_path` explicitly from the `_workflow/design.md` location rather than from the skill's `design_path` argument, and state that derivation.

### WI2 [Recommended] — create-plan Step 4b comprehension gate on `full` passes no `output_path` yet names the seed↔track fidelity criterion

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 862-872)
- **Axis:** sub-agent handshake
- **Cost:** in `full`, the Step-4b comprehension gate reads N track files plus the frozen `design.md` for the seed↔track fidelity criterion and returns its verdict **inline** (no `output_path`); on a large multi-track plan that inline return lands the gate's full structural-findings detail in the orchestrator's context, the exact accumulation the by-reference contract this track depends on (D15) is meant to prevent.

The Step-4b comprehension-gate spawn names "`target=tracks`, `scope=whole-doc`, `plan_dir`, and `plan_path`, plus `design_path` in `full` … and names **no** `research_log_path` and **no** `output_path`: … with no `output_path` it returns its verdict inline" (lines 865-872). On the `edit-design` design path the parallel comprehension gate at `phase1-creation` likewise returns inline — but that gate reads one `design.md`, whereas the Step-4b `full` gate fans across every `plan/track-N.md` **and** the frozen `design.md` to run the seed↔track fidelity criterion. The same prompt (`prompts/design-review.md § Output format`) supports the `output_path` file-write branch that `phase4-creation` uses precisely to keep a whole-doc cold-read's detail out of the orchestrator's context. Step 4b is the one cold-read in this track that runs on the widest surface (N tracks + design) and is the only creation-kind gate here that does not offer the file-write option.

This is not a dead-end (the inline path is fully defined and works), so it is a should-fix on context-completeness grounds rather than a blocker: the spec gives the loop no `output_path` escape on its widest surface, where the cumulative-diff weight is largest.

- **Suggestion:** either state explicitly why the Step-4b comprehension gate is exempt from the `output_path` file-write branch the `phase4-creation` gate uses (e.g., "the track verdict is summary-shaped, so inline return is bounded"), or thread an `output_path` for the `full` Step-4b comprehension gate the same way `create-final-design.md` threads one for Phase 4, and partial-fetch its `## Structural findings`.

### WI3 [Minor] — `edit-design` Step 4 fidelity row reuses the `design_path` token for two different files in adjacent lines

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (lines 704-707)
- **Axis:** sub-agent handshake
- **Cost:** the orchestrator parsing this paragraph sees `draft_path=<design_path>` and `design_path=<frozen design.md>` one line apart, where `<design_path>` resolves to `design-final.md` (the skill arg) in the first and to the frozen `design.md` in the second — an easy mis-wire that sends the draft into the seed slot or vice-versa.

The fidelity paragraph reads: "`draft_path=<design_path>` (the `design-final.md` it is checking), and `design_path=<frozen design.md>` for the residual reference only" (lines 706-707). The first `<design_path>` is the skill's `design_path` **argument** (= `design-final.md` for `phase4-creation`, lines 900-901); the second `design_path` is the fidelity check's params **key** whose value is a *different* file (the frozen `_workflow/design.md`). The parenthetical glosses disambiguate for a careful reader, but the token `design_path` carries two referents two lines apart with no marker that the value source differs. This compounds WI1: the contract names the right key but never says where the frozen-design value comes from on the Phase 4 path.

- **Suggestion:** disambiguate the value source in the row, e.g. `draft_path=<the skill's design_path arg, = design-final.md>` and `design_path=<the frozen _workflow/design.md, NOT the skill's design_path arg>`, so the orchestrator cannot collapse the two onto the same file.

### WI4 [Minor] — create-plan Step 4b absorption-check `design_path` routing condition ("when the orchestrator routes the seed↔track fidelity check here") is undefined

- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 826-837)
- **Axis:** conditional branch coverage
- **Cost:** the absorption-check params include `design_path` "in `full` when the orchestrator routes the seed↔track fidelity check here" — but no rule says when that routing happens, so the orchestrator has no deterministic test for whether to pass `design_path`, and the `full`-tier branch is left to improvise.

The Step-4b absorption-check contract says its params file carries "`target=tracks`, `research_log_path` … and `draft_path` … (plus `design_path` in `full` when the orchestrator routes the seed↔track fidelity check here)" (lines 829-831). The conditional "when the orchestrator routes the seed↔track fidelity check here" has no antecedent in this Step or elsewhere in the Step-4b loop — there is no rule that decides whether the seed↔track fidelity criterion runs on the absorption check (lines 826-837) or on the comprehension gate (line 867 says the comprehension gate carries `design_path` "in `full` for the full-tier seed↔track fidelity criterion"). Both spawns are told they may carry `design_path` for the same criterion, but nothing picks one. In `full` the orchestrator cannot tell from the spec whether to pass `design_path` to the absorption check, the comprehension gate, or both.

- **Suggestion:** state which spawn owns the `full`-tier seed↔track fidelity criterion (one owner, mirroring the S4 one-owner-per-surface discipline this track applies to the prose axis), and make the other spawn's `design_path` unconditional-or-omitted accordingly. If both legitimately need the seed, say so and drop the "when the orchestrator routes … here" hedge.

## Evidence base

#### C1 — WI1: fidelity `design_path` has no Phase 4 producer
Confirmed by reading three sources and tracing the param flow. (1) `fidelity-check.md` lines 60-63 list `episodes_path` / `draft_path` / `design_path` as the agent's params, with `design_path` = "the frozen `design.md` seed, for the residual reference only." (2) `create-final-design.md` line 211 fixes the skill's `design_path` argument to `docs/adr/<dir-name>/design-final.md`, and lines 229-247 ("two more inputs") thread only the episodes path and `output_path`. (3) `edit-design/SKILL.md` lines 900-901 confirm `design_path = design-final.md` for `phase4-creation`, and line 707 requires `design_path=<frozen design.md>` for the fidelity check. The frozen design value is read at `create-final-design.md` line 37 but never re-threaded. No downstream step supplies it ⇒ missing-producer for a declared input. Refutation attempt (agent reads it itself): the agent reads it only "when the params file names it" (line 48), so the un-threaded case silently drops the residual reference rather than erroring — confirms should-fix severity, not blocker.

#### C2 — WI2: Step-4b `full` comprehension gate has no `output_path` escape on its widest surface
Confirmed against the `output_path` machinery the same prompt offers elsewhere. `create-plan/SKILL.md` lines 868-872 state the Step-4b comprehension gate carries no `output_path` and returns inline, on a surface that is N track files plus the frozen `design.md` (the `full` seed↔track criterion, line 867). `edit-design/SKILL.md` lines 617-628 and `create-final-design.md` lines 240-247 show the `phase4-creation` gate uses the `output_path` file-write branch precisely to keep a whole-doc cold-read out of the orchestrator's context. The Step-4b `full` gate is the widest cold-read in this track yet is the only creation-kind whole-doc gate offered no file-write branch. Survives as a context-completeness should-fix (the inline path is defined and terminates; the gap is the absent escape on the largest surface).

#### C3 — WI3: `design_path` token reused for two files two lines apart
Direct read of `edit-design/SKILL.md` lines 706-707: `draft_path=<design_path>` (value = `design-final.md`) immediately followed by `design_path=<frozen design.md>` (value = a different file). One token, two referents, disambiguated only by parenthetical gloss. Minor because a careful reader resolves it; flagged because it is the mechanical handshake an orchestrator parses and compounds the WI1 missing-producer gap.

#### C4 — WI4: undefined routing condition for the absorption-check `design_path`
Direct read of two adjacent contract sites in `create-plan/SKILL.md`: line 831 conditions the absorption check's `design_path` on "when the orchestrator routes the seed↔track fidelity check here," and line 867 conditions the comprehension gate's `design_path` on "in `full` for the full-tier seed↔track fidelity criterion." Both spawns claim the same criterion; no rule in the Step-4b loop (lines 762-887) selects an owner. Grep for "seed↔track" / "seed↔track fidelity" across the staged file finds only these two sites and no selector. The conditional has no decidable antecedent ⇒ unhandled branch for the `full` tier. Minor: in `lite`/`minimal` no `design.md` exists so the branch never fires; the gap is `full`-only and the worst case is a redundant or omitted seed reference, not a strand.
