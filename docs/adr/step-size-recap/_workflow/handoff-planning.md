# Handoff: Phase 1 (planning) — derive the plan from the reviewed design

**Paused:** 2026-06-04
**Phase:** 1
**Context level at pause:** safe (planned design→plan split, not a context-pressure pause)
**Branch:** step-size-recap
**HEAD:** cb5eec651bde0f7059f76e8be20f744691c089d0 "Add design for step sizing and review routing"
**Unpushed:** <no upstream at write time — pushed with `-u` at session end; see Resume notes>

## What I was investigating

The aim is YTDB-1062 (raise the per-step footprint cap and isolate high-risk changes instead of sizing steps by risk) plus two extensions agreed during Phase 0 research: a step-vs-track triage for the six workflow-machinery reviewers, and a workflow-machinery risk taxonomy (absent today) that gives that triage its trigger. All targets are workflow machinery, so the plan is workflow-modifying.

The design document is complete and reviewed. This handoff exists only to carry the research and design decisions into a separate `/create-plan` session that derives `implementation-plan.md` and the track files — it is not a context-pressure pause.

## Already ruled out (settled decisions — do not re-litigate)

- **Per-tier file cap** rejected in favor of fill-toward-~12 for ordinary steps + high-risk isolation. Evidence: 33% of realized steps already exceed ~3 cleanly; iteration drives context (Pearson r 0.81) not footprint (r 0.37); no implementer touching ≤13 edited files reached the 400K warning band.
- **Staged ~8 intermediate cap** rejected — pin ~12 soft / ~14+ overblown now (issue "Decided" note).
- **Bumping the MEDIUM ~5-file threshold toward ~12** rejected — keep ~5, only clarify its relationship to the ~12 split cap (would otherwise drop 6-11-file logic changes to `low` and lose the Phase C focal-point signal).
- **`review-workflow-instruction-completeness` at step level** rejected → defers to track (its gate/resume-path checks span steps; it is the only reviewer matching bare `.claude/workflow/*.md`, so deferring it means a workflow-`.md`-only high step draws no step-level reviewer, consistent with the prose-only cap).
- **Root `CLAUDE.md` as MEDIUM** rejected → HIGH (always-loaded → every-session blast radius). Confirmed by the user.

## Most promising lead (the spec)

`docs/adr/step-size-recap/_workflow/design.md` (committed `cb5eec65`) is the frozen design spec. Derive the plan from it directly. Review log at `_workflow/design-mutations.md` (Mutation 1, phase1-creation, mechanical PASS + cold-read PASS).

## Open questions

None blocking. D1–D7 are forward-referenced in the design's References footers and must be formalized as the plan's Architecture Notes Decision Records (numbers and content already fixed by the design — see Raw notes).

## Raw notes / partial findings (material the plan session needs)

**Workflow-modifying marker (add to `### Constraints` verbatim, §1.7(b)):**
```
This plan is workflow-modifying: it edits .claude/workflow/** or .claude/skills/**.
```
All `.claude/...` edits stage under `docs/adr/step-size-recap/_workflow/staged-workflow/`; the staged-vs-live delta gets the Phase C §1.7(h) review.

**Decision Records to formalize (content fixed by the design):**
- D1: raise the per-step footprint cap to ~12 soft / ~14+ overblown, fill-toward-cap directive, coherence + high-risk isolation. (Track 1)
- D2: reword `conventions.md` §1.1 Glossary "Step" so "atomic" means coherent, not minimal files. (Track 1)
- D3: keep ~5 as the MEDIUM classification threshold, distinct from the ~12 split cap. (Track 1)
- D4: baseline triage — `review-bugs-concurrency` at the step, the other three baselines at Phase C track. (Track 2)
- D5: workflow-reviewer triage — `hook-safety` + `prompt-design` at the step; `consistency`, `context-budget`, `writing-style`, `instruction-completeness` at track. (Track 2)
- D6: add the workflow-machinery risk taxonomy (HIGH/MEDIUM/LOW + prose-only cap). (Track 1)
- D7: `review-bugs-concurrency` mandatory at three review paths, excluded from workflow changes. (Track 2)

**Two-track decomposition (from the design's Constraints section):**

- **Track 1 — Sizing & risk taxonomy.** Edits: `track-review.md` § Step Decomposition (rewrite the ~3-file cap as the three rules, keep the trivial-merge floor); `conventions.md` §1.1 Glossary "Step" reword; `risk-tagging.md` (the `:163` MEDIUM ~5-file clarifying clause AND the new `### Workflow machinery` HIGH subsection + MEDIUM/LOW lines + prose-only cap + TOC rows + per-section annotations); `track-review.md` § Risk tagging summary sync (name the workflow category). DRs D1, D2, D3, D6.
- **Track 2 — Review routing** (`> Depends on: Track 1`). Edits: `review-agent-selection.md` (baseline step-vs-track carve-out at the §Baseline intro + the `:24-28` note, plus a NEW non-mirrored note carrying the workflow-reviewer step-vs-track triage and the bugs-concurrency-excluded-from-workflow rule); `step-implementation.md` sub-step 4a dispatch (baseline routing + step-level workflow-reviewer dispatch); `track-code-review.md` (track-level workflow-reviewer dispatch); `risk-tagging.md:65` (the `high` quick-ref row step-level cell); `.claude/skills/code-review/SKILL.md:191` (promote `review-bugs-concurrency` to "Always launched (unless docs-only or build-config is the ONLY category)"). DRs D4, D5, D7.

**Cross-track file note:** `risk-tagging.md` is touched by both tracks in disjoint sections (Track 1: HIGH/MEDIUM/LOW criteria + `:163`; Track 2: the `:65` quick-ref row). Under §1.7 staging the staged copy accumulates both tracks' edits; each track's Phase C review delta-scopes to its own sections.

**Wiring constraint (load-bearing):** the workflow-reviewer step-vs-track timing must NOT live in the §Maintenance-mirrored sections of `review-agent-selection.md` (`§Workflow-review agents`, `§Per-agent file-pattern triggers`, `§Workflow-machinery override`) — those mirror `SKILL.md` verbatim and `SKILL.md` has no step/track notion. It goes in a new, non-mirrored note. The `SKILL.md` `review-bugs-concurrency` promotion needs no sync-stamp bump (the baseline/conditional tables are not in the mirror set).

**Verified non-targets (do not edit):** `conventions.md:407` (mcp-steroid refactor rule, not a ~3-files match); `conventions-execution.md` "atomic" at ~:235 (edit atomicity); `step-implementation.md` high-only step-review gate and session-end context gate (load-bearing guardrails to cite, not edit).

**Self-application limit (note in the plan, do not try to fix):** this branch's diffs are workflow-only, so the baseline-skip override removes the entire baseline group at the step — `review-bugs-concurrency` included. The branch exercises the step sizing rules, the workflow risk taxonomy, the workflow-reviewer triage, and the §1.7(h) staged-vs-live review, but not the bugs-concurrency-at-Java-step routing.

## Resume notes

- **Do NOT re-explore.** The seven original edit sites and the three extension sites are verified on this branch (line numbers in the Raw notes are current as of `cb5eec65`). The design is frozen; do not re-run Phase 0 research.
- **Next action on resume:** transition straight to Phase 1 planning. Read `planning.md` and `design-document-rules.md`, then derive `implementation-plan.md` (Goals, Constraints WITH the §1.7(b) marker, Architecture Notes with Component Map + D1–D7, the two-track checklist) and `plan/track-1.md` + `plan/track-2.md` from `design.md`. Formalize D1–D7 with the content fixed above. Annotate `> Depends on: Track 1` on Track 2.
- The branch is pushed (`-u`) at this session's end, so the next session has an upstream. The plan session reaches `/create-plan` Step 5, which opens the draft PR after the plan and tracks exist (deferred from this session — only the design is committed so far).
- design.md is the source of truth; this handoff is a derivation aid, not a second spec. On any conflict, design.md wins.
