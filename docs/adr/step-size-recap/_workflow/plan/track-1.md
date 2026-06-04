<!-- workflow-sha: 786f441e224ba6c8c4240dde5d9368866fb9b405 -->
# Track 1: Sizing & risk taxonomy

## Purpose / Big Picture
After this track, decomposition sizes steps by coherence and a fill-toward-~12-files directive instead of the old `~3`-file cap, and `.claude/**` edits have a HIGH/MEDIUM/LOW risk taxonomy for the first time.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Replace the `~3`-file cap with the three sizing rules (coherence, high-risk isolation, fill-toward-~12) and add the workflow-machinery risk taxonomy that Track 2's reviewer triage depends on. Edits `track-review.md` (§ Step Decomposition rewrite + § Risk tagging summary sync), `conventions.md §1.1` (the "Step" glossary reword), and `risk-tagging.md` (the `~5` MEDIUM clarifying clause + the new `### Workflow machinery` HIGH/MEDIUM/LOW subsection with prose-only cap).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

All edit targets are workflow `.md` files. This plan is workflow-modifying, so every edit routes to a staged copy under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...` per `conventions.md §1.7`; the live `.claude/**` tree stays at develop's state until the Phase 4 promotion. On first touch of a file, copy the live version into the staged path verbatim, then edit the staged copy (§1.7(e)).

The track touches three files, all verified current as of `cb5eec65` (HEAD has added only the planning handoff since, no `.claude/**` change):

- **`track-review.md` § Step Decomposition** — the cap lives at the line "If a step touches more than ~3 files or does unrelated things, split it." (currently around `:717`, under the `### Step Decomposition` heading at `:698`). The trivial-merge floor ("If a step feels trivial, merge it into a neighbor") sits nearby and must survive. The `#### Risk tagging` summary block (around `:723`) enumerates the categories ("Six HIGH categories ... one MEDIUM band ... a LOW default") and must gain a workflow mention so it does not drift from `risk-tagging.md`.
- **`conventions.md §1.1 Glossary`** — the "Step" row (currently `:70`): "A single atomic change = one commit. Fully tested." The glossary is annotated `roles=any phases=any`, so it is the most authoritative definition site; "atomic" reading as "smallest indivisible" is what fights the fill directive.
- **`risk-tagging.md`** — `## HIGH-risk triggers` heading (`:94`) is where the new `### Workflow machinery` subsection lands; `## MEDIUM-risk triggers` (`:156`) and `## LOW-risk default` (`:172`) take the workflow MEDIUM/LOW lines; the MEDIUM `~5`-file trigger "Logic changes touching more than ~5 files within one module" (`:163`) gets the clarifying clause; the § Tests-only steps section (`:187`) is the structural precedent for the new prose-only cap.

Non-obvious terminology this track introduces or sharpens (full glosses in design.md §"Core Concepts"): **footprint cap** (the `~12` edited-file ceiling, all tiers), **fill-toward-cap directive** (decompose to the largest coherent change, not the smallest), **high-risk isolation** (each HIGH change in its own `high`-tagged step, no file cap), **footprint cap vs risk classification** (the two distinct file-count numbers `~12` and `~5`), **workflow-machinery risk taxonomy** (HIGH/MEDIUM/LOW for `.claude/**`), **prose-only cap** (a prose-only workflow step is at most `low`).

Concrete deliverables: a rewritten § Step Decomposition stating the three rules; a reworded "Step" glossary row; a `### Workflow machinery` taxonomy subsection plus MEDIUM/LOW lines and a prose-only cap in `risk-tagging.md`; a `~5`-vs-`~12` clarifying clause at the MEDIUM trigger; and a workflow mention in the § Risk tagging summary.

## Plan of Work

The work is four coherent edits. They are loosely coupled, so the ordering below is a sensible default rather than a hard constraint; the one cross-track ordering rule that matters is that this track's workflow risk taxonomy (D6) lands before Track 2 consumes it as the workflow-reviewer triage trigger.

1. **Rewrite the `~3`-file cap as the three sizing rules** (D1) in `track-review.md` § Step Decomposition: split a step that does unrelated things (coherence, all tiers); isolate each HIGH change into its own `high`-tagged step sized by the change (no file cap); fill ordinary `low`/`medium` steps toward `~12` edited files and flag `~14+` as overblown. State the fill rule as a directive, not a permission. Keep the trivial-merge floor verbatim.
2. **Reword the "Step" glossary row** (D2) in `conventions.md §1.1` so "atomic" means one coherent, logically continuous change committed together, explicitly not a minimal file count, with a pointer to the footprint guidance. Keep it terse — this is a closed-term, every-phase surface.
3. **Add the workflow-machinery risk taxonomy** (D6) to `risk-tagging.md`: a `### Workflow machinery` subsection under `## HIGH-risk triggers` (executes/load-bearing-gate/schema/always-loaded), workflow lines under MEDIUM (bounded behavioral) and LOW (prose/clarity), and a prose-only cap as the analog of the tests-only cap. Root `CLAUDE.md` is a HIGH trigger.
4. **Add the `~5`-vs-`~12` clarifying clause** (D3) at the MEDIUM `~5`-file trigger, and **sync the § Risk tagging summary** in `track-review.md` to name the new workflow category so `review-workflow-consistency` finds no drift.

Invariants to preserve: the trivial-merge floor stays; the existing "when in doubt, high" decomposer override is unchanged (no workflow-specific override added); the MEDIUM `~5` value is unchanged (only its wording is clarified); coverage gates are untouched.

<!-- Phase A appends a per-step sequencing summary referencing the Concrete Steps roster. -->

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

Track-level behavioral acceptance (Phase A turns these into per-step EARS/Gherkin lines):

- `track-review.md` § Step Decomposition no longer contains the `~3`-file cap; it states the three sizing rules (coherence, high-risk isolation, fill-toward-~12 with `~14+` flagged) and still carries the trivial-merge floor.
- `conventions.md §1.1` "Step" row defines "atomic" as one coherent change committed together, explicitly not a minimal file count, with a footprint pointer; it does not contradict the `track-review.md` rules.
- `risk-tagging.md` carries a `### Workflow machinery` HIGH subsection, workflow MEDIUM and LOW lines, and a prose-only cap; root `CLAUDE.md` is classified HIGH.
- The MEDIUM `~5`-file trigger carries a clause tying it to the `~12` split cap so the two numbers read as complementary, not rival; the `~5` value is unchanged.
- `track-review.md` § Risk tagging summary names the workflow category, matching `risk-tagging.md` (no consistency drift).
- Every edit lives under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...`; the live `.claude/**` tree is byte-unchanged from develop.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files (staged copies under `_workflow/staged-workflow/.claude/...`):**
- `.claude/workflow/track-review.md` — § Step Decomposition (the `~3`-file cap line + trivial-merge floor) and the `#### Risk tagging` summary block.
- `.claude/workflow/conventions.md` — the §1.1 Glossary "Step" row only.
- `.claude/workflow/risk-tagging.md` — `## HIGH-risk triggers` (add `### Workflow machinery`), `## MEDIUM-risk triggers`, `## LOW-risk default`, the MEDIUM `~5`-file trigger clause, and the § Tests-only precedent for the prose-only cap.

**Out-of-scope (owned by Track 2 or deliberately not edited):**
- `review-agent-selection.md`, `step-implementation.md`, `track-code-review.md`, `code-review/SKILL.md`, and the `risk-tagging.md` `high` quick-ref row (`:65`) — all Track 2.
- Verified non-targets: `conventions.md` mcp-steroid refactor `~3`-files rule, `conventions-execution.md` edit-atomicity "atomic", the `step-implementation.md` high-only step-review gate and session-end context gate (cited as load-bearing guardrails, not edited).

**Dependencies:**
- **Downstream:** Track 2's workflow-reviewer triage (D5) keys off this track's `### Workflow machinery` taxonomy (D6); the taxonomy must land before Track 2 consumes it.
- **Cross-track file:** `risk-tagging.md` is also touched by Track 2 (the `high` quick-ref row at `:65`), a disjoint section. Under §1.7 staging the staged copy accumulates both tracks' edits; each track's Phase C review delta-scopes to its own sections.

**Staging contract:** workflow-modifying marker present in `implementation-plan.md` § Constraints; writes route to the staged subtree; the staged-vs-live delta gets the Phase C `§1.7(h)` review, delta-scoped to the live-vs-staged diff (D5 convention), not the whole-file staged copy.
