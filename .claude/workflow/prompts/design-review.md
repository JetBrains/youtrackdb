# Cold-Read Comprehension Review (Sub-Agent Prompt)

Fresh agent reading a design document cold. Assess whether a human
reviewer could build a working mental model **using only the
document(s) provided**. Invoked by the design-mutation action
(`design-document-rules.md § Mutation discipline`); verdict drives
iterate / warn / pass.

## Inputs

- `design_path` — `design.md`.
- `design_mechanics_path` (optional) — `design-mechanics.md` when
  the design exceeded the length trigger.
- `scope` — `bounded` or `whole-doc`.
- `bounded_scope` (when `scope=bounded`) — changed section name(s)
  + 1-2 surrounding sections.
- `mutation_kind` — one of `content-edit`, `section-add`,
  `section-remove`, `section-rename`, `section-move`,
  `structural-rewrite`, `length-trigger-crossing`, `phase1-creation`,
  `design-sync`, `phase4-creation`. (`mechanics-edit` defers
  cold-read to the next `design-sync`.)
- `plan_path` (optional) — `implementation-plan.md`; read **only**
  for `**Full design**` link resolution.
- `plan_dir` (optional) — directory of `plan/track-N.md` files
  carrying `**Full design**` refs; read **only** for link resolution.

### Mutation-kind specific instructions

- **`phase1-creation`**: `design.md` and `design-mechanics.md`
  were just seeded together. The design.md serves both human
  readers (the user reviewing the plan, the architect during
  structural review, the PR reviewer reading the draft) and
  agent readers (the implementer executing the plan). Verify
  (a) the design.md is internally coherent on its own (a fresh
  reader can navigate it); (b) every
  `Mechanics: design-mechanics.md §"…"` link resolves to a
  matching section in mechanics. **Plus the Human-reader
  cold-read additions (§ below).**
- **`design-sync`**: this sync re-distilled `design.md` from the
  current state of `design-mechanics.md`. **In addition to the
  standard whole-doc cold-read**, verify that every TL;DR and
  mechanism overview in `design.md` accurately summarizes the
  current mechanics file's content for the same-named section. If
  the design.md TL;DR contradicts the mechanics, or names a
  mechanism that mechanics doesn't describe, flag it as a blocker
  — that's exactly what the sync was supposed to fix. **Plus the
  Human-reader cold-read additions (§ below).**
- **`phase4-creation`**: `design-final.md` (and optionally
  `design-mechanics-final.md`) was just produced as a Phase 4
  committed artifact reflecting what was actually built. The
  paths end in `-final.md` but the same shape rules apply.
  **Phase 4 is committed to git** and read by humans (PR
  reviewers, future re-readers, decision auditors), so the bar
  is higher than Phase 1: the artifact must stand on its own as
  documentation — no reliance on working files (plan, step
  files), since those live under `_workflow/` and are removed
  by the Phase 4 cleanup commit before merge.

  **In addition to the standard whole-doc cold-read AND the
  Human-reader cold-read additions (§ below)**, verify:

  (a) **Plan-deviation surfacing** — the Overview names what
      was built and any high-level deviations from the original
      plan (descoped goals, modified decisions, emergent
      structure).

  (b) **Implementation-grounded diagrams** — class / workflow
      diagrams reflect actual implementation (the calling
      prompt should have run a PSI verification pass; flag any
      diagram element that looks aspirational rather than
      implementation-grounded).

  (c) **No leaked working-file identifiers** — no track
      numbers, step numbers, or review-finding IDs in the
      prose.

### Human-reader cold-read additions

Applies to `phase1-creation`, `phase4-creation`, `design-sync`. In
addition to the standard cold-read, verify:

- Verify audience-fit per `.claude/output-styles/house-style.md § Audience-fit`.
- Verify glossary-introduction per `.claude/output-styles/house-style.md § Glossary-introduction`.
- Verify why-before-what per `.claude/output-styles/house-style.md § Why-before-what`.
- Verify navigability per `.claude/output-styles/house-style.md § Navigability`.

**Reviewer tone** for these four rules: § Tone and depth's
"one-sentence answers" is relaxed — quote the prose, list undefined
terms, name the failing audience, and (for navigability) the opaque
section.

## Reading rules

- **Only the files above** — no source code, prior conversation, or other workflow files.
- **Bounded scope**: changed section + 1-2 surrounding + `## Overview` + (when present) `## Core Concepts`; open mechanics only for a specific `Mechanics:` link.
- **Whole-doc scope**: entire `design.md`; mechanics for link targets.
- **Plan / track-file reads**: grep-only for `**Full design**` link resolution.

## Comprehension questions

Answer in order; **cite the paragraph(s) you relied on** (quoted
phrase or section + anchor). Insufficient material is itself a finding.

1. **What is this design replacing or adding?** State in one
   sentence. Use the Overview.
2. **What are the load-bearing concepts a reader needs before
   the Parts?** Use the Core Concepts section if present;
   otherwise note its absence and whether the Overview alone
   gives enough vocabulary. (For docs without Parts and
   <3 new domain terms, this question is N/A — say so.)
3. **What is the load-bearing claim of the section that just
   changed?** Read the changed section's TL;DR; restate in your
   own words.
4. **What constraint or invariant must remain true after this
   change?** Either name an explicit S-code or describe the
   invariant in prose.
5. **What would break if this change were reverted?**
6. **What's the most subtle gotcha called out in the changed
   section?** Quote it.
7. **How would you find the full mechanism detail if you needed
   it?** Use the References footer / `Mechanics:` cross-ref.

## Structural findings (always check)

- Verify **Edge cases / Gotchas** per `.claude/output-styles/house-style.md § Edge cases sub-section required`.
- Verify **References footer** per `.claude/output-styles/house-style.md § References footer shape`.
- Verify **Same-shape sibling consolidation** per `.claude/output-styles/house-style.md § Same-shape sibling consolidation`.
- (**Whole-doc only**) Verify **Overview is concept-first** per `.claude/output-styles/house-style.md § Overview concept-first`.
- **TL;DR present** (`dsc-tldr-shape`); **Mechanism overview ≤300 lines** (`dsc-mechanism-length`, warn 200); **Length budget** ≤2,000 (`dsc-length-budget`).
- **`Mechanics:` link target** exists in `design-mechanics.md` when split applies.
- (**Whole-doc only**) **Core Concepts current and complete** for docs with Parts or ≥3 new domain terms (`dsc-core-concepts-current`); **`**Full design**` refs** in plan and track files resolve to real `design.md` sections.

## Output format

Produce exactly the following Markdown, no preamble:

```markdown
## Comprehension assessment

1. <answer + citation>
2. <answer + citation>
3. <answer + citation>
4. <answer + citation>
5. <answer + citation>
6. <answer + citation>
7. <answer + citation>

## Could a cold reader build a working mental model from this?

[YES / PARTIAL / NO]

<2-3 sentence rationale>

## Structural findings

[None / list of findings]

- [<severity>] <description>
- [<severity>] <description>
...

(For `phase1-creation`, `phase4-creation`, and `design-sync`,
findings under the Human-reader cold-read additions go in the
same list but prefix each with the dimension label and use
multi-line bullets to fit the evidence required by the Tone
exception — e.g. `[blocker] (a) Audience-fit: <quoted prose +
named audience + why it breaks down>`.)

## Verdict

[PASS / NEEDS REVISION]

<If NEEDS REVISION: list which findings are blockers and which
are should-fix.>

## Suggested fixes (when applicable)

<For each finding that has an obvious mechanical fix, propose
the exact edit. The mutation action's iteration step will
attempt the fix automatically.>

- <finding ref>: <suggested edit>
```

## Severity rubric

- **blocker** — comprehension fails, mechanical violation corrupts
  cross-refs, or section lacks TL;DR / References footer / shape.
- **should-fix** — comprehensible but a rule violated. Per
  `design-document-rules.md § Mechanical checks`, length tiers: 201-300 = suggestion, 301-400 = should-fix, >400 = blocker.
- **suggestion** — non-rule-mandated improvement.

## Tone and depth

- One-sentence answers where one suffices. **Exception**: the four Human-reader rules require evidence (see § Reviewer tone).
- Cite, don't paraphrase.
- If unanswerable, say "Insufficient — see finding below" and add the structural finding.
- Don't speculate about intent the doc doesn't state.
