# Track 5: Pointers in orchestrator files (Tier-B subset for chat-scale prose)

## Purpose / Big Picture
After this track lands, the top-level orchestrator file, the three user-invocable skill bodies, and the five mid-loop protocol files all name the Tier-B subset of house-style as the rule set for chat-scale prose, with structural rules explicitly exempted.

<!-- Reserved for Move 2. -->

Adds Tier-B subset pointer to `workflow.md` (top-level orchestrator), the three top-level `SKILL.md` files (`create-plan`, `execute-tracks`, `review-plan`), and the five mid-loop protocols (`mid-phase-handoff.md`, `inline-replanning.md`, `review-mode.md`, `review-iteration.md`, `design-decision-escalation.md`). Pointer wording explicitly exempts structural rules (BLUF lead, ≤200-word section cap, document-shape) from chat-scale prose.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-05-20T07:18Z [ctx=safe] Review + decomposition complete
- [x] 2026-05-20T07:27Z [ctx=safe] Step 1 complete (commit c56fe1b0ce)
- [x] 2026-05-20T07:30Z [ctx=safe] Step 2 complete (commit cfb46a162b)
- [x] 2026-05-20T07:30Z [ctx=safe] Step implementation complete (2 steps, 0 failed)

## Surprises & Discoveries
- 2026-05-20T07:27Z Step 1 surfaced: `.githooks/prepare-commit-msg` skips the subject prefix when the branch-derived YTDB ID appears anywhere in the message body. Commits whose body cites the ID literally land with an unprefixed subject. See Episodes §Step 1.
- 2026-05-20 [ctx=safe] Phase C iter-1 surfaced: Step 2 landed the pointer paragraph as plain bold across the 5 mid-loop protocol files, while Step 1 had landed the blockquote form in the 4 entry-point files. The `Concrete Steps` canonical template uses the `> ` prefix; Step 2 read the `>` as plan-document quoting rather than canonical form. Three independent reviewers converged on the asymmetry (WP1, WI1, WC1). Retro-fix landed via this `Review fix:` commit; the Validation grep was tightened from a substring match to a `^> \*\*House style for chat-scale prose\.` anchor so the next re-run catches form drift.

## Decision Log
<!-- Empty at Phase 1. -->

<!-- Reserved for Move 1. -->

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 1 (5 findings, 5 accepted). T1 (should-fix, pointer-template binding) and T4 (suggestion, line-number brittleness) absorbed into `## Context and Orientation` lines 49-52 — the Tier-B-subset reference now binds the four banned-section slug citations to their verbatim Markdown-heading form (`## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`) plus the Markdown-link form for the `conventions.md §1.5` cross-reference, and the brittle line numbers are dropped. T2 (should-fix, colon-terminated lead-in pre-scan) absorbed as a new invariant in the `## Plan of Work` invariants paragraph. T3 (suggestion, `track-skip.md` exclusion rationale) accepted as-is — the file's `**Skipped:** <reason>` prose is short Markdown already covered by the Track 2 PreToolUse Tier-A hook. T5 (suggestion, 2-step vs 1-step decomposition) accepted as-is — the 2-step shape matches the Track 3 and Track 4 cadence (one commit per file-role group) and keeps the per-commit blast radius small.
- [x] Track-level code review iteration 1, 2026-05-20: 5 workflow-review dimensional agents ran (consistency, prompt-design, instruction-completeness, context-budget, writing-style). Three converged on F1 (pointer-form asymmetry between Step 1's blockquote landings and Step 2's plain-bold landings, against the canonical `> **House style...` template at `## Concrete Steps` line 76). F1 fixed via this `Review fix:` commit — all 5 mid-loop protocol files now carry the blockquote-form pointer; the Validation grep was tightened from substring match to `^> \*\*House style for chat-scale prose\.` anchor. One finding deferred to Phase 4 as F-DEF1 (WI2): the `.githooks/prepare-commit-msg` prefix-suppression rule belongs in `CLAUDE.md § Git Conventions § Commit Messages` so future implementers do not re-trip the trap (Step 1 episode discovered it; Step 2 episode confirmed the workaround). See the **Cross-track impact for Phase 4** block below.
- Risk: skipped (Track 5 is Simple, 2 steps; complexity table mandates Technical only).
- Adversarial: skipped (same reason).

**Cross-track impact for Phase 4:** F-DEF1 / WI2 — the `.githooks/prepare-commit-msg` hook treats any case-insensitive `YTDB-NNN` match in the commit body as a 'prefix already present' signal and skips prepending the subject prefix. Candidate landing site: `CLAUDE.md § Git Conventions § Commit Messages`. Proposed rule text: "Do not cite the branch-derived `YTDB-NNN` ID in the commit body — the `.githooks/prepare-commit-msg` hook treats any case-insensitive ID match in the message as a 'prefix already present' signal and skips prepending the subject prefix." Phase 4's `create-final-design.md` should fold this in alongside the Track 4 WI5/WI6 carryovers already queued for the final design's style / git-conventions sections.

## Context and Orientation

The orchestrator's prose surfaces are chat replies — short status updates, inline-replanning summaries, decision-escalation prompts, review-mode loop turns. They are not durable git-tracked artifacts; they land in the conversation context only.

The YTDB-837 description identifies the right rule subset:

> Pointer wording: *"User-facing prose follows the AI-tell subset of house-style — banned vocabulary, no negative parallelism, em-dash discipline. Structural rules (BLUF lead, section length caps) don't apply to chat replies."*

This wording is the template. Each file gets one bullet or paragraph (whichever fits the file's existing prose shape) near the top.

Files in scope:

- `.claude/workflow/workflow.md` — top-level orchestrator file, loaded at every `/execute-tracks` startup. Tier-B pointer near the top, before the per-phase sections.
- `.claude/skills/create-plan/SKILL.md` — Phase 0 + Phase 1 driver. Tier-B pointer in the skill body (after frontmatter).
- `.claude/skills/execute-tracks/SKILL.md` — Phase 3 driver. Tier-B pointer.
- `.claude/skills/review-plan/SKILL.md` — Manual re-run of plan review. Tier-B pointer.
- `.claude/workflow/mid-phase-handoff.md` — Pause-and-resume protocol; the orchestrator writes handoff prose to disk under `_workflow/handoff-*.md`. Tier-B pointer (the handoff body is borderline Tier-A but the protocol fires from chat-scale orchestrator state, so Tier-B is the right register for the prose the orchestrator generates).
- `.claude/workflow/inline-replanning.md` — Mid-loop replanning protocol; orchestrator generates replan summary prose. Tier-B pointer.
- `.claude/workflow/review-mode.md` — Conversational review-mode loop; orchestrator generates per-action prose. Tier-B pointer.
- `.claude/workflow/review-iteration.md` — Review-iteration protocol; orchestrator generates iteration-summary prose. Tier-B pointer.
- `.claude/workflow/design-decision-escalation.md` — Escalation prompts the orchestrator generates for the user. Tier-B pointer.

The Tier-B-subset reference (per D3) names the four source sections explicitly. Each landed pointer cites the four heading slugs verbatim in their full Markdown-heading form (`## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`) so the `conventions.md §1.5` locator-grep recipe enumerates the pointer site by literal slug strings, matching the Track 4 result and the Track 2 `test_16_section_name_guard` contract. The rule source is cited as `.claude/output-styles/house-style.md`; the conventions.md anchor is cited in Markdown-link form (`[conventions.md §1.5 Writing style for Markdown and prose artifacts](...)`) with the substring kept un-wrapped on one line per the Track 4 WI5 carryover.

The pointer explicitly exempts structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, and `§ Document-shape rules (design / ADR-specific)`). These exempt-section names are stable post-YTDB-836 but live outside the rename-gate grep enumeration, so the abbreviated short form is acceptable in the pointer prose. The `Em-dash discipline` slug is the H3 nested under `## Punctuation and typography`; the H3 depth survives a substring-only audit but the verbatim `### Em-dash discipline` form ties the citation to the rename-gate exactly.

## Plan of Work

The track delivers in two steps, by file group:

Step 1 — Add the Tier-B pointer to `workflow.md` and the three `SKILL.md` files. These four files are the user-visible entry points to the workflow; the pointer reaches every session.

Step 2 — Add the Tier-B pointer to the five mid-loop protocol files. These fire during specific situations (pause, replan, review, escalate) and their prose contributions are bounded.

Ordering constraints: Track 1 must complete first (the pointer cites the conventions.md anchor). Tracks 2, 3, 4 are independent.

Invariants to preserve: every file's existing frontmatter and § headings stay intact. The pointer never lands above frontmatter. No file gets more than one pointer. Insertion-anchor pre-scan rule (Track 3 F1 lesson carried through Track 4): before landing a pointer in any file, scan paragraphs around the chosen anchor for a colon-terminated lead-in introducing an enumerated list. If the candidate anchor sits between such a lead-in and the list it introduces, pick a different anchor (typically one paragraph after the list).

## Concrete Steps

The canonical pointer paragraph for every Track 5 site is byte-identical across all 9 files except for the relative path inside the `conventions.md §1.5` Markdown link (which varies by file location, see per-step anchor table):

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `.claude/output-styles/house-style.md`: `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See [conventions.md §1.5 Writing style for Markdown and prose artifacts](<RELATIVE-PATH>) for the workflow-level anchor and tier mapping.

The `<RELATIVE-PATH>` placeholder resolves to `conventions.md` for the five `.claude/workflow/` siblings and to `../../workflow/conventions.md` for the three `.claude/skills/<skill>/SKILL.md` files. `workflow.md` is itself a `.claude/workflow/` sibling, so its link target is `conventions.md`. The visible link text stays the literal substring `conventions.md §1.5 Writing style for Markdown and prose artifacts` un-wrapped on one line per Track 4 WI5.

1. Insert the canonical pointer paragraph into the top-level orchestrator and the three user-invocable skill bodies (4 files). Anchors: `.claude/workflow/workflow.md` between line 26 (end of `## Overview` block) and line 28 (`### Terminology: Phases 0/1/2/3/4 vs Phases A/B/C` heading); `.claude/skills/create-plan/SKILL.md` between line 8 (`Read and follow the workflow for Phase 0 (Research) and Phase 1 (Planning).`) and line 10 (`**Step 1 — Read workflow documents.**`); `.claude/skills/execute-tracks/SKILL.md` between line 8 (`Read and follow the workflow for Phase 3 (Execution).`) and line 10 (`Read these workflow documents in order before starting:`); `.claude/skills/review-plan/SKILL.md` between line 8 (`Read and follow the workflow for Phase 2 (Implementation Review).`) and line 10 (`> **Manual override.**` blockquote start). Before patching each file, scan ±5 lines around the chosen anchor and confirm the insertion does NOT fall between a colon-terminated lead-in and the enumerated list the colon introduces (Track 3 F1 hazard, Track 4 strategy-refresh carryover). For SKILL.md files, the `<RELATIVE-PATH>` placeholder resolves to `../../workflow/conventions.md`; for `workflow.md` it resolves to `conventions.md`. Use native `Edit` per the Track 3 fallback — `steroid_apply_patch` is not exposed in implementer sub-agent spawns. Validation: `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' .claude/workflow/workflow.md .claude/skills/create-plan/SKILL.md .claude/skills/execute-tracks/SKILL.md .claude/skills/review-plan/SKILL.md | wc -l` returns `4`. — risk: low (default: pure documentation insertion; no semantic change)  [x] commit: c56fe1b0ce

2. Insert the canonical pointer paragraph into the five mid-loop protocols (5 files, all under `.claude/workflow/`). Anchors: `mid-phase-handoff.md` between line 7 (intro paragraph ends with `…research already on disk.`) and line 9 (`Loaded on-demand by:` colon-terminated lead-in); `inline-replanning.md` between line 6 (intro paragraph ends with `…notes.`) and line 8 (`## When ESCALATE triggers` heading); `review-mode.md` between line 8 (intro paragraph ends with `…does any side effect run.`) and line 10 (`## What review mode does` heading); `review-iteration.md` between line 8 (intro paragraph ends with `…not needed at session startup.`) and line 10 (`---` separator); `design-decision-escalation.md` between line 6 (intro paragraph ends with `…beyond what the plan specifies.`) and line 8 (`## When to pause and ask the user` heading). Before patching each file, scan ±5 lines around the chosen anchor and confirm the insertion does NOT fall between a colon-terminated lead-in and the enumerated list the colon introduces. The `<RELATIVE-PATH>` placeholder resolves to `conventions.md` for all five files. Use native `Edit` per the Track 3 fallback. Validation: `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' .claude/workflow/mid-phase-handoff.md .claude/workflow/inline-replanning.md .claude/workflow/review-mode.md .claude/workflow/review-iteration.md .claude/workflow/design-decision-escalation.md | wc -l` returns `5`; cumulative `grep -l '…' <all 9 files> | wc -l` returns `9`. — risk: low (default: pure documentation insertion; no semantic change)  [x] commit: cfb46a162b

## Episodes

### Step 1 — commit c56fe1b0ce, 2026-05-20T07:27Z [ctx=safe]
**What was done:** Landed the canonical chat-scale prose pointer paragraph in the top-level orchestrator file `workflow.md` and the three user-invocable skill bodies `create-plan`, `execute-tracks`, and `review-plan` `SKILL.md`. Every pointer cites the four banned-section heading slugs verbatim (`## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`), exempts the three structural-rule sections by name (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`), and links to the `conventions.md §1.5` anchor with the substring un-wrapped on one line. Each anchor was pre-scanned (±5 lines) for the colon-terminated-lead-in trap before patching. Validation grep returns `4/4`.

**What was discovered:** The repo's `.githooks/prepare-commit-msg` hook treats any case-insensitive match on the branch-derived YTDB issue ID anywhere in the message as "prefix already present" and skips prepending the subject prefix. The implementer's commit body opened with the literal `YTDB-837` ID, so the subject landed as `Add house-style chat-prose pointer to top-level entry points` without the `YTDB-837:` prefix. No amend per the rulebook; the commit is on origin.

**Key files:**
- `.claude/workflow/workflow.md` (modified)
- `.claude/skills/create-plan/SKILL.md` (modified)
- `.claude/skills/execute-tracks/SKILL.md` (modified)
- `.claude/skills/review-plan/SKILL.md` (modified)

### Step 2 — commit cfb46a162b, 2026-05-20T07:30Z [ctx=safe]
**What was done:** Landed the canonical chat-scale prose pointer paragraph in the five mid-loop protocol files: `mid-phase-handoff.md` (between intro paragraph and the `Loaded on-demand by:` lead-in), `inline-replanning.md` (between intro paragraph and the `## When ESCALATE triggers` heading), `review-mode.md` (between intro paragraph and the `## What review mode does` heading), `review-iteration.md` (between intro paragraph and the `---` separator), and `design-decision-escalation.md` (between intro paragraph and the `## When to pause and ask the user` heading). Every pointer cites the four banned-section heading slugs verbatim, exempts the three structural-rule sections by name, and links the `conventions.md §1.5` anchor with the substring un-wrapped on one line. Each anchor was pre-scanned (±5 lines) for the colon-terminated-lead-in trap before patching; in `mid-phase-handoff.md` the pre-scan flagged line 9 itself as a lead-in for the bullet list below, and the chosen insertion point keeps the pointer cleanly outside the lead-in→list span. Cumulative validation grep across all 9 in-scope files returns `9`.

**What was discovered:** The commit-body workaround for the prepare-commit-msg hook (Step 1 finding) was confirmed in both directions. Omitting the literal `YTDB-837` token from the body lets the hook prepend the subject prefix normally; citing it suppresses the prepend. Step 2's subject landed as `YTDB-837: Add house-style pointer to mid-loop protocols`. (Retro-fix, 2026-05-20 iter-1 Phase C review: Step 2 initially landed the pointer paragraph as plain bold, not the blockquote form the canonical template renders in the spec line at `## Concrete Steps`; three dimensional reviewers converged on the asymmetry and a `Review fix:` commit aligned all five mid-loop protocol files to the `> **House style...` blockquote form Step 1 had used.)

**Key files:**
- `.claude/workflow/mid-phase-handoff.md` (modified)
- `.claude/workflow/inline-replanning.md` (modified)
- `.claude/workflow/review-mode.md` (modified)
- `.claude/workflow/review-iteration.md` (modified)
- `.claude/workflow/design-decision-escalation.md` (modified)

## Validation and Acceptance

- All 9 in-scope files carry the canonical pointer paragraph in blockquote form: `grep -cE '^> \*\*House style for chat-scale prose\.' .claude/workflow/workflow.md .claude/skills/create-plan/SKILL.md .claude/skills/execute-tracks/SKILL.md .claude/skills/review-plan/SKILL.md .claude/workflow/mid-phase-handoff.md .claude/workflow/inline-replanning.md .claude/workflow/review-mode.md .claude/workflow/review-iteration.md .claude/workflow/design-decision-escalation.md` returns `1` for each of the 9 files. The blockquote-prefix anchor is what makes the pointer a visually-demarcated callout matching the surrounding `> **Manual override.**`-style callouts; the asymmetric form Step 2 landed (since corrected via Review fix) was caught only by the dimensional review fan-out, not by the original substring grep.
- Each landed pointer cites the four banned-section heading slugs verbatim (`## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`) — confirmed by `grep -l '### Em-dash discipline' <9 files>` returning `9` (the rarest of the four slugs across the workflow surface, used as the rename-gate proxy per Track 2 `test_16_section_name_guard`).
- Each landed pointer exempts structural rules (`§ BLUF lead`, `§ Structural rules`, `§ Document-shape rules`) from chat-scale prose — confirmed by per-file inspection that the exempt-rule clause appears verbatim.
- No file picks up more than one pointer (`grep -c 'conventions.md §1.5 Writing style for Markdown and prose artifacts' <each file>` returns `1` for each).
- D3 compliance (reference Tier-B subset sections by name) holds at every site.
- YTDB-837 acceptance bullet 5 holds: "Orchestrator files name the Tier-B subset and explicitly exempt structural rules."

## Idempotence and Recovery

- Each step's `Edit` invocations are file-scoped and idempotent — re-running the canonical-pointer insertion against a file that already carries the pointer reads as a Markdown-link-form duplicate that the `Edit` tool would refuse (no matching `old_string`). The validation grep `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts'` is the steady-state check; a re-run reads `4` for Step 1 and `5` for Step 2 either way.
- Partial step failure (one of Step 1's 4 files committed, the others not): re-run Step 1 to commit the remaining files; the cumulative validation grep returns `4`. The implementer's revert path (`git reset --hard HEAD`) discards any uncommitted insertions.
- Anchor drift on resume (a file's top section was edited between Phase A decomposition and Phase B implementation): the per-step anchor reads as line ranges plus context strings ("between line N (intro paragraph ends with `…`) and line M (heading `…`)"). The implementer pre-scans the chosen anchor in each candidate file before patching, picks a different anchor if the colon-terminated-lead-in trap appears, and records the actual line numbers in the step episode.

## Artifacts and Notes
<!-- Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**

Top-level orchestrator + skills:
- `.claude/workflow/workflow.md`
- `.claude/skills/create-plan/SKILL.md`
- `.claude/skills/execute-tracks/SKILL.md`
- `.claude/skills/review-plan/SKILL.md`

Mid-loop protocols:
- `.claude/workflow/mid-phase-handoff.md`
- `.claude/workflow/inline-replanning.md`
- `.claude/workflow/review-mode.md`
- `.claude/workflow/review-iteration.md`
- `.claude/workflow/design-decision-escalation.md`

**Out-of-scope files:**
- `.claude/workflow/conventions.md`, `conventions-execution.md`, `planning.md`, `research.md`, `implementation-review.md`, `track-review.md`, `track-code-review.md`, `structural-review.md`, `risk-tagging.md`, `step-implementation-recovery.md`, `track-skip.md`, `self-improvement-reflection.md`, `ephemeral-identifier-rule.md`, `branch-divergence-check.md`, `defensive-push-check.md`, `code-review-protocol.md`, `finding-synthesis-recipe.md`, `plan-slim-rendering.md`, `review-agent-selection.md`, `design-document-rules.md` — these are either rule files (Tier-A coverage handled implicitly by their being Markdown editable through the hook), or covered by other tracks, or carry no orchestrator chat-scale prose.

**Inter-track dependencies:**
- **Upstream**: Track 1 (cross-references the new conventions.md section heading).
- **Downstream**: none.

**Compatibility requirements:**
- Existing `SKILL.md` `name:` and `description:` fields stay unchanged (interface contracts read by the skill loader).
- `workflow.md` is loaded at every `/execute-tracks` startup; the pointer is additive context, not a behavior change.

**Library / function signatures relevant to this track:** none — pure documentation edits.

## Base commit

346adff40f21873a45fcd445730969ab740119e2
