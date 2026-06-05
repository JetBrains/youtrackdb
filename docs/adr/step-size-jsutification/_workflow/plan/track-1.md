<!-- workflow-sha: d185cbaf8b26cd7c1424e3b93a25a5a365b8b909 -->
# Track 1: Relax low/medium coherence and add the under-fill justification

## Purpose / Big Picture
After this track lands, the Phase A decomposer may merge low/medium steps (related or not) toward the ~12 fill target, keeps every HIGH change isolated, tags a merged step by re-applying the risk criteria, and must justify any low/medium step still below the fill target with an inline roster clause.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Edits five files so the Phase A decomposer may merge low/medium steps (related or not) toward the ~12 fill target, keeps high steps isolated, tags a merged step by re-applying the risk criteria, and requires an inline size-justification clause on any low/medium roster line still below the fill target.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-06-05T09:11Z [ctx=safe] Review + decomposition complete

## Base commit
4edab3ce9a120f471d67dd288c07cb4bbf3db3e9

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

- [x] Technical: PASS at iteration 1 (2 findings, 2 accepted). 0 blockers. All six named workflow-file anchors verified against the live tree; S1 high-isolation routing confirmed load-bearing (`review-agent-selection.md:25`, `code-review-protocol.md:20`, `risk-tagging.md` quick-ref); D4 `>~5-files` MEDIUM trigger and D5 closed reason set confirmed coherent. T1 (D1 misattributed `~14` to `conventions.md §1.2`; `~14` is in `track-review.md`) fixed in plan Constraints + D1 rationale. T2 (roster-format `size:` clause leaves `step-implementation.md` / `track-code-review.md` non-exhaustive) resolved by documenting the 5-file scoping decision in D6 Risks/Caveats + this track's out-of-scope bullet.

## Context and Orientation
The change touches five files: four under `.claude/workflow/` that the Phase A decomposer reads, plus the create-plan seed template under `.claude/skills/`:

- `.claude/workflow/track-review.md` §Step Decomposition → `#### Decomposition rules` (lines ~712-738 today). Carries the **Coherence (all tiers)** bullet ("the only mandatory split rule; file count alone never forces a split"), the **High-risk isolation** bullet, and the **Fill ordinary steps toward ~12 edited files** bullet, plus the roster-line format example at line ~763 (`N. <description> — risk: <level> (<category, "default", or "override: <reason>">)  [ ]`).
- `.claude/workflow/conventions.md` §1.1 glossary (line ~70). The "Step" row defines a step as "one coherent, logically continuous change committed together — not a minimal file count" and points to `track-review.md` §Step Decomposition for sizing.
- `.claude/workflow/risk-tagging.md` (lines ~180-218). HIGH/MEDIUM/LOW trigger lists, the override mechanism, and the ~5/~12 interplay note (lines ~187-196) explaining that `~5` raises a logic step to medium while `~12` bounds growth.
- `.claude/workflow/conventions-execution.md` §2.1 (item 8, lines ~114-127; lifecycle table row ~224). Canonical `## Concrete Steps` roster format and example.
- `.claude/skills/create-plan/SKILL.md` (track-N.md seed template, `## Concrete Steps` placeholder at lines ~412-418). The comment copied into every new track file at Phase 1; already enumerates the optional `commit:` annotation.

Terminology used below is defined in design.md §Core Concepts: **fill target** (~12 edited files), **coherence boundary**, **high-isolation**, **size-justification clause**, and **max-of-constituents** risk rounding.

Deliverables: the five files edited so the decomposition rules, glossary, risk-tagging, roster format, and seed-template placeholder tell one consistent story per design.md.

This is a workflow-modifying track. Per `conventions.md §1.7`, edits during Phase B/C are staged under `docs/adr/step-size-jsutification/_workflow/staged-workflow/.claude/…` and promoted to the live tree by the Phase 4 promotion commit; the live files stay at develop state until then.

## Plan of Work
The edits, in dependency order:

1. **`conventions.md §1.1` glossary "Step".** Soften "one coherent, logically continuous change" so coherence is mandatory for `high` steps and a preference for `low`/`medium`. Keep the existing pointer to `track-review.md` §Step Decomposition for the sizing detail.
2. **`track-review.md` §Step Decomposition → `#### Decomposition rules`.** The core edit:
   - Rewrite the **Coherence** bullet: scope mandatory coherence to `high` steps; for `low`/`medium`, coherence is preferred but the decomposer may merge unrelated changes toward the fill target. State that high-isolation and the overblown ~14 rule remain the mandatory split rules.
   - Extend the **Fill ordinary steps toward ~12** bullet: low/medium may merge unrelated coherent changes to reach ~12; add the under-fill justification requirement with the two-entry closed reason set (no mergeable low/medium work fits; heavy-iteration carve-out) and the explicit exclusion of "unrelated" and inter-step dependency.
   - Update the roster-line format example to show the optional `— size: ~N files; <reason>` clause.
3. **`risk-tagging.md`.** Add a short rule near the ~5/~12 interplay note: a merged low/medium step is tagged by re-applying these criteria to the combined content (= max of constituents); the >~5-files-of-logic MEDIUM trigger can raise a `low+low` logic merge to medium; HIGH is never merged.
4. **`conventions-execution.md §2.1`.** Document the optional inline `size:` clause in item 8 and the roster example; touch the lifecycle-table Phase A writer cell if it needs to name the clause.
5. **`.claude/skills/create-plan/SKILL.md`.** Add the optional `size:` clause to the `## Concrete Steps` placeholder comment in the track-N.md seed template (the "what decomposition writes" sentence), parallel to the existing `commit:` mention, so the seed stays consistent with the canonical roster format.

Ordering constraint: edits 1-2 establish the rule; edits 3-5 align the risk-tag, roster-format, and seed-template sites with it. Invariants to preserve: high-isolation (S1), no new per-step threshold (D1), the `~5` trigger value and `~20-25` ceiling unchanged (Non-Goals).

Per-step sequencing is deferred to Phase A decomposition. Note the reflexive case: this very change governs how the decomposer sizes steps, so the Phase A decomposition of this track may itself merge low/medium edits across the five files.

## Concrete Steps
1. Relax `low`/`medium` coherence and add the under-fill justification, editing all five files in one commit so the glossary, the decomposition rules, the risk-tag rule, the roster format, and the seed template stay one non-contradicting story (S3). Edit order per `## Plan of Work`: (1) `conventions.md §1.1` glossary "Step": coherence mandatory for `high`, preferred for `low`/`medium`; (2) `track-review.md` §Step Decomposition: scope the Coherence bullet to `high`, extend Fill-toward-~12 with the merge allowance plus the under-fill justification (closed reason set; "unrelated" and inter-step dependency excluded), add the optional `size:` clause to the roster-line example; (3) `risk-tagging.md`: merged-step tag is the re-applied criteria (max of constituents), HIGH never merged; (4) `conventions-execution.md §2.1`: optional inline `size:` clause in the roster format plus the lifecycle-table row; (5) `create-plan/SKILL.md`: `size:` clause in the track-N.md seed-template placeholder. — risk: high (workflow machinery)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed
step, identified by step number + commit SHA. Empty at Phase 1; Phase A
does not populate. -->

## Validation and Acceptance
Track-level acceptance:

- The decomposition rules in `track-review.md` permit a `low`/`medium` step to merge unrelated changes toward ~12, and require an inline size-justification clause on any `low`/`medium` step below the fill target, with "unrelated" excluded from the valid reasons.
- `high`-step isolation and step-level-review routing are unchanged (S1).
- A merged step's risk tag follows the re-applied criteria, equal to the max of its constituents' tags (S2).
- The glossary "Step" definition, the Coherence rule, the Fill/merge rule, the roster format, and the seed-template placeholder do not contradict each other across the five files (S3), verified by the Phase 2 consistency review.
- No new per-step threshold is introduced; the trigger is the existing `~12` (D1).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim
as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths
once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in `## Episodes`
above. Often empty. -->

## Interfaces and Dependencies
In scope:
- `.claude/workflow/track-review.md` — §Step Decomposition rules + roster-line format example.
- `.claude/workflow/conventions.md` — §1.1 glossary "Step" definition.
- `.claude/workflow/risk-tagging.md` — merged-step tag rule near the ~5/~12 interplay note.
- `.claude/workflow/conventions-execution.md` — §2.1 item 8 roster format + example + lifecycle row.
- `.claude/skills/create-plan/SKILL.md` — `## Concrete Steps` placeholder comment in the track-N.md seed template, kept consistent with the canonical roster format.

Out of scope:
- `.claude/workflow/track-code-review.md` — no Phase C verification gate (D6).
- `.claude/workflow/prompts/**`, `.claude/workflow/step-implementation*.md` — the sizing-rule change does not alter their behavior. Their roster-format descriptions enumerate only the `commit:` annotation and stay that way by design: both serve roster→episode joining, which the optional `size:` clause does not affect (see D6 Risks/Caveats). The canonical optional-annotation set lives in `conventions-execution.md §2.1`.

Inter-track dependencies: none — single-track plan.

Staging: workflow-modifying; edits route to `_workflow/staged-workflow/.claude/…` per `conventions.md §1.7`, promoted at Phase 4.
