<!-- workflow-sha: f74ef47e943f3bf1900f1f5ab42740d63fe3e588 -->
# Track 3: Propagate the slug to the review agents

## Purpose / Big Picture
After this track lands, every review agent that names the AI-tell subset lists the new `## Plain language` slug, and the workflow-markdown writing-style reviewer actively checks for it.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

This track adds the sixth section slug to the 19 `.claude/agents/*.md` enumerations, and adds a Plain-language enforcement check to `review-workflow-writing-style.md`. That agent reviews prose actively (it checks banned vocabulary, the em-dash cap, BLUF, section length), so it needs a real lens, not just a slug in a citation.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-14T11:16Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-14T11:37Z [ctx=safe] Step 1 complete (commit 766e7d8419)
- [x] 2026-06-14T11:43Z [ctx=safe] Step 2 complete (commit c12f25d3f2)

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->
- Phase A reviews confirmed the exact scope split: 18 of the 19 enumerating agents carry the uniform "the five AI-tell subset section slugs to apply are …, and `## Orientation`" sentence; `dr-audit.md:22` is the lone odd shape — a blockquote slug list with no count word and a trailing "Structural rules (…)" clause. Its sixth slug must land before the sentence period, not appended after the trailing clause (technical T2, adversarial A1). Folded into Step 2's description.
- Technical T1 corrected a citation in `## Context and Orientation`: the writing-style reviewer's "Key rules to enforce" list is `:26`–`:35`, not `:26`–`:39` (the wider range overshot into the `## Tooling` section at `:37`). Fixed in place.

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
- [x] Technical: PASS at iteration 1 (2 findings, both suggestions, both accepted as decomposition aids — T1 citation correction applied to `## Context and Orientation`; T2 `dr-audit.md` mid-sentence insertion folded into Step 2). No blocker or should-fix, so no fix iteration.
- [x] Adversarial: PASS at iteration 1 (1 finding, suggestion, accepted — A1 is the same `dr-audit.md` Oxford-comma point as T2, folded into Step 2). Three narrowed challenges all held: scope/sizing (20-of-20 in-scope set complete and correct), cross-track-episode reality (the `## Plain language` section exists at `house-style.md:78`; all 19 agents still carry the five-slug preamble; `review-workflow-writing-style.md` carries no enumeration but actively enforces house-style lenses, so it needs a content edit), invariant violation (INFEASIBLE).
- Risk: not run — lite tier with no warranting characteristic (no critical path, no performance constraint, no major architectural decision), per the Phase-3A tier-driven review selection.

## Context and Orientation
The 20 review agents under `.claude/agents/` split two ways. 19 carry the same house-style preamble naming the five subset slugs (line ~20: "the five AI-tell subset section slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, and `## Orientation`"). `dr-audit.md` phrases the same five-slug list slightly differently (`:22`) but is one of the 19. The 20th, `review-workflow-writing-style.md`, does not enumerate the five slugs — it is the active writing-style reviewer, with a "Key rules to enforce" list (`:26`–`:35`) and a checks section (`:67`+, first subsection `### Banned vocabulary sweep` at `:69`) covering Banned vocabulary, Em-dash, section cap, BLUF. It needs a content edit, not a slug add (D3-1).

This track depends on Track 1: the `## Plain language` section must exist before these agents name it or check it.

## Plan of Work
1. Add `## Plain language` to the five-slug preamble enumeration in each of the 19 enumerating agents (after `## Orientation`, matching slug order). Flip any numeric "five" in the same line to "six".
2. In `review-workflow-writing-style.md`, add a Plain-language enforcement check to the "Key rules to enforce" list and a matching check in the checks section, parallel to the existing Banned-vocabulary and Em-dash checks; report findings, no score (D3-1).

Invariant to preserve: every agent preamble this track touches ends at exactly six slugs in the canonical order; `review-workflow-writing-style.md` checks plain language alongside its existing lenses.

## Concrete Steps

1. Add the sixth AI-tell subset slug `## Plain language` after `## Orientation` in the house-style preamble of 12 enumerating agents: `review-bugs-concurrency`, `review-code-quality`, `review-crash-safety`, `review-performance`, `review-security`, `review-test-behavior`, `review-test-completeness`, `review-test-concurrency`, `review-test-crash-safety`, `review-test-structure`, `review-workflow-consistency`, `review-workflow-context-budget`. Each carries the uniform sentence "the five AI-tell subset section slugs to apply are …, and `## Orientation`"; insert the slug before the period (so the tail reads "…, `## Orientation`, and `## Plain language`") and change the count word "five"→"six". No other prose changes. — risk: low (default: prose-only workflow edit — meaning-preserving enumeration sync; no hook/gate/schema change)  [x] commit: 766e7d8419
2. Add `## Plain language` to the remaining 7 enumerating agents and add the Plain-language enforcement lens to `review-workflow-writing-style.md` (D3-1). The 6 uniform agents — `review-workflow-hook-safety`, `review-workflow-instruction-completeness`, `review-workflow-prompt-design`, `code-reviewer`, `pr-reviewer`, `test-quality-reviewer` — take the same slug insertion and "five"→"six" flip as Step 1. `dr-audit.md` (`:22`) is the odd shape: a blockquote slug list with no count word and a trailing "Structural rules (…)" clause, so insert the slug mid-sentence before the period — turn "…, and `## Orientation`." into "…, `## Orientation`, and `## Plain language`." — and do not append it after the trailing clause (T2 / A1). In `review-workflow-writing-style.md`, add a Plain-language bullet to the "Key rules to enforce" list (`:26`–`:35`) and a `### Plain language` subsection in `## Review criteria` (after `:67`, parallel to `### Banned vocabulary sweep` at `:69`); keep it judgment-shaped and reported as a finding with no score (D2, D3-1). Write the new check prose in plain language (self-application). — risk: medium (workflow machinery: the writing-style Plain-language lens is a bounded behavioral edit that changes agent-observable review behavior; the 7 slug flips are prose-only) — size: ~8 files; reason (a): end of track — the only other low/medium work is Step 1's 12 files, and merging it would total 20 and trip the ~14 overblown line  [x] commit: c12f25d3f2

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

### Step 1 — commit 766e7d84194c371f1125d56eb2e55e5552d8cba4, 2026-06-14T11:37Z [ctx=safe]
**What was done:** Added `## Plain language` after `## Orientation` in the AI-tell subset preamble of the 12 enumerating review agents, and flipped that sentence's count word from "five" to "six". The slug tail now reads "…, `## Orientation`, and `## Plain language`." in canonical order. No other prose changed.

**What was discovered:** All 12 files carried the byte-identical preamble sentence, so one guarded literal-replace covered every file with no per-file variation. The diff is exactly 12 files at one line each.

**Key files:**
- `.claude/agents/review-bugs-concurrency.md` (modified)
- `.claude/agents/review-code-quality.md` (modified)
- `.claude/agents/review-crash-safety.md` (modified)
- `.claude/agents/review-performance.md` (modified)
- `.claude/agents/review-security.md` (modified)
- `.claude/agents/review-test-behavior.md` (modified)
- `.claude/agents/review-test-completeness.md` (modified)
- `.claude/agents/review-test-concurrency.md` (modified)
- `.claude/agents/review-test-crash-safety.md` (modified)
- `.claude/agents/review-test-structure.md` (modified)
- `.claude/agents/review-workflow-consistency.md` (modified)
- `.claude/agents/review-workflow-context-budget.md` (modified)

### Step 2 — commit c12f25d3f2028a955cf811d2af774940f1f5fa03, 2026-06-14T11:43Z [ctx=safe]
**What was done:** Part A synced the sixth slug `## Plain language` into the 7 remaining enumerating agents. The 6 uniform agents (`review-workflow-hook-safety`, `review-workflow-instruction-completeness`, `review-workflow-prompt-design`, `code-reviewer`, `pr-reviewer`, `test-quality-reviewer`) took the same edit as Step 1: slug after `## Orientation`, count word "five"→"six". `dr-audit.md` is the odd shape — a blockquote slug list with no count word and a trailing "Structural rules (…)" clause; the new slug went mid-sentence before the slug-list period, leaving the trailing clause untouched (T2/A1). Part B added the Plain-language lens to `review-workflow-writing-style.md`: a bullet in "Key rules to enforce" and a `### Plain language` subsection in `## Review criteria` parallel to `### Banned vocabulary sweep`, reported as a finding with no count, regex, or score (D2), scoped to general English only with guards against simplifying technical content, re-teaching the mid-level Java/database floor, or re-banning a Banned-vocabulary tier word (D3-1).

**What was discovered:** `review-workflow-writing-style.md` has no `<!--Document index start-->` TOC region, so the new subsection heading needs no reindexing; `workflow-reindex.py --check` returned exit 0. With this step's 8 files, all 20 agents now carry `## Plain language` — the full-coverage invariant holds.

**Key files:**
- `.claude/agents/review-workflow-hook-safety.md` (modified)
- `.claude/agents/review-workflow-instruction-completeness.md` (modified)
- `.claude/agents/review-workflow-prompt-design.md` (modified)
- `.claude/agents/code-reviewer.md` (modified)
- `.claude/agents/pr-reviewer.md` (modified)
- `.claude/agents/test-quality-reviewer.md` (modified)
- `.claude/agents/dr-audit.md` (modified)
- `.claude/agents/review-workflow-writing-style.md` (modified — Plain-language lens, D3-1)

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

## Base commit
156e5aaec54037ca0c317b998da84b09dd65e575
