<!-- workflow-sha: f74ef47e943f3bf1900f1f5ab42740d63fe3e588 -->
# Track 3: Propagate the slug to the review agents

## Purpose / Big Picture
After this track lands, every review agent that names the AI-tell subset lists the new `## Plain language` slug, and the workflow-markdown writing-style reviewer actively checks for it.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track adds the sixth section slug to the 19 `.claude/agents/*.md` enumerations, and adds a Plain-language enforcement check to `review-workflow-writing-style.md`. That agent reviews prose actively (it checks banned vocabulary, the em-dash cap, BLUF, section length), so it needs a real lens, not just a slug in a citation.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Track-canonical live decisions (D7). This track executes plan D3/D4; it owns one content-edit decision. -->

#### D3-1: The writing-style reviewer gains a real Plain-language check, not just a slug add
- **Alternatives considered**: leave `review-workflow-writing-style.md` unchanged (it does not enumerate the five slugs today, so the slug flip skips it); add a Plain-language enforcement check to its "Key rules to enforce" list and its checks section (chosen).
- **Rationale**: this agent is the writing-style review of changed workflow markdown. It actively enforces house-style rules (Banned vocabulary sweep, Em-dash overuse, section cap, BLUF). A new always-on prose rule is dead unless this reviewer checks it. The 19 other agents only cite the subset in a house-style preamble, so for them the change is a slug add; this one needs a lens.
- **Risks/Caveats**: keep the new check short and judgment-shaped (plan D2 — no count, no regex). State that plain-language quality is reported as a finding, not scored, matching the agent's existing Banned-vocabulary lens.
- **Implemented in**: this track (`review-workflow-writing-style.md`).

The other 19 agents are mechanical slug additions to the identical house-style preamble line ("the five AI-tell subset section slugs to apply are …"), executing plan D3 (natural reach) and plan D4 (§1.7(k) opt-out edits them live). They carry no track-local decision; the rationale is the plan's D3/D4.

## Outcomes & Retrospective
<!-- Continuous-log. -->

## Context and Orientation
The 20 review agents under `.claude/agents/` split two ways. 19 carry the same house-style preamble naming the five subset slugs (line ~20: "the five AI-tell subset section slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, and `## Orientation`"). `dr-audit.md` phrases the same five-slug list slightly differently (`:22`) but is one of the 19. The 20th, `review-workflow-writing-style.md`, does not enumerate the five slugs — it is the active writing-style reviewer, with a "Key rules to enforce" list (`:26`–`:39`) and a checks section (`:69`+) covering Banned vocabulary, Em-dash, section cap, BLUF. It needs a content edit, not a slug add (D3-1).

This track depends on Track 1: the `## Plain language` section must exist before these agents name it or check it.

## Plan of Work
1. Add `## Plain language` to the five-slug preamble enumeration in each of the 19 enumerating agents (after `## Orientation`, matching slug order). Flip any numeric "five" in the same line to "six".
2. In `review-workflow-writing-style.md`, add a Plain-language enforcement check to the "Key rules to enforce" list and a matching check in the checks section, parallel to the existing Banned-vocabulary and Em-dash checks; report findings, no score (D3-1).

Invariant to preserve: every agent preamble this track touches ends at exactly six slugs in the canonical order; `review-workflow-writing-style.md` checks plain language alongside its existing lenses.

## Concrete Steps
<!-- Phase A placeholder. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance
- All 19 enumerating agents list `## Plain language` in the preamble; any numeric count reads "six".
- `review-workflow-writing-style.md` carries a Plain-language check in its enforce list and checks section.
- A `grep -rL 'Plain language' .claude/agents/*.md` returns no file that should carry the rule (full coverage check).
- All edited prose reads in plain language (self-application).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines. -->

## Idempotence and Recovery
<!-- Phase A placeholder. -->

## Artifacts and Notes
<!-- Continuous-log (rare). -->

## Interfaces and Dependencies

**In scope (this track):**
- 19 enumerating agents: `.claude/agents/{code-reviewer,dr-audit,pr-reviewer,review-bugs-concurrency,review-code-quality,review-crash-safety,review-performance,review-security,review-test-behavior,review-test-completeness,review-test-concurrency,review-test-crash-safety,review-test-structure,review-workflow-consistency,review-workflow-context-budget,review-workflow-hook-safety,review-workflow-instruction-completeness,review-workflow-prompt-design,test-quality-reviewer}.md` — slug add.
- `.claude/agents/review-workflow-writing-style.md` — Plain-language enforcement check (D3-1).

**Out of scope (other tracks):** `house-style.md`, `house-conversation.md`, `conventions.md`, the core workflow docs, the hook, the test, `CLAUDE.md` (Track 1); the 11 prompts and 6 skills (Track 2).

**Dependencies:** depends on Track 1 (the `## Plain language` section must exist first). Independent of Track 2.

**Exact in-scope set is derived by `grep -rln 'Banned analysis patterns' .claude/agents/` (the 19 enumerating agents) plus `review-workflow-writing-style.md` at Phase A and reconciled against this list (lite-tier requirement; figure ~20 is approximate).**
