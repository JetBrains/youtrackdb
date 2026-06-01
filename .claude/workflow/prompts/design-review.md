# Cold-Read Comprehension Review (Sub-Agent Prompt)

## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: reviewer-design.
Your phase: 1,4.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Inputs | reviewer-design | 1,4 | The design paths, scope, mutation kind, and optional plan/track paths passed to the cold-read reviewer. |
| §Mutation-kind specific instructions | reviewer-design | 1,4 | Extra checks per mutation kind: phase1-creation, design-sync, and the higher bar for committed phase4 artifacts. |
| §Human-reader cold-read additions | reviewer-design | 1,4 | Audience-fit, glossary-introduction, why-before-what, and navigability checks; reviewer tone relaxes to quote evidence. |
| §Reading rules | reviewer-design | 1,4 | Read only the provided design files; bounded vs whole-doc scope; grep-only plan reads; fetch house-style on demand. |
| §Comprehension questions | reviewer-design | 1,4 | Seven ordered questions a cold reader answers with citations; insufficient material is itself a finding. |
| §Structural findings (always check) | reviewer-design | 1,4 | Always-on checks: edge-cases sub-section, References footer, sibling consolidation, TL;DR, length budgets, Mechanics. |
| §Output format | reviewer-design | 1,4 | The exact comprehension-assessment, mental-model verdict, structural-findings, and suggested-fixes Markdown to emit. |
| §Severity rubric | reviewer-design | 1,4 | Blocker, should-fix, and suggestion definitions, with the length-tier thresholds that set should-fix vs blocker. |
| §Tone and depth | reviewer-design | 1,4 | One-sentence answers, cite don't paraphrase, flag insufficiency, no intent-speculation; human-reader rules excepted. |

<!--Document index end-->

Fresh agent reading a design document cold. Assess whether a human
reviewer could build a working mental model **using only the
document(s) provided**. Invoked by the design-mutation action
(`design-document-rules.md § Mutation discipline`); verdict drives
iterate / warn / pass.

## Inputs
<!-- roles=reviewer-design phases=1,4 summary="The design paths, scope, mutation kind, and optional plan/track paths passed to the cold-read reviewer." -->

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
<!-- roles=reviewer-design phases=1,4 summary="Extra checks per mutation kind: phase1-creation, design-sync, and the higher bar for committed phase4 artifacts." -->

- **`phase1-creation`**: `design.md` and `design-mechanics.md`
  were just seeded together. The `design.md` serves both human
  readers (the user reviewing the plan, the architect during
  structural review, the PR reviewer reading the draft) and
  agent readers (the implementer executing the plan). Verify
  (a) the `design.md` is internally coherent on its own (a fresh
  reader can navigate it); (b) every
  `Mechanics: design-mechanics.md §"…"` link resolves to a
  matching section in mechanics. **Plus the Human-reader
  cold-read additions (§ below).**
- **`design-sync`**: this sync re-distilled `design.md` from the
  current state of `design-mechanics.md`. **In addition to the
  standard whole-doc cold-read**, verify that every TL;DR and
  mechanism overview in `design.md` accurately summarizes the
  current mechanics file's content for the same-named section. If
  the `design.md` TL;DR contradicts the mechanics, or names a
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
<!-- roles=reviewer-design phases=1,4 summary="Audience-fit, glossary-introduction, why-before-what, and navigability checks; reviewer tone relaxes to quote evidence." -->

Applies to `phase1-creation`, `phase4-creation`, `design-sync`. In
addition to the standard cold-read, verify:

- Verify audience-fit per `.claude/output-styles/house-style.md § Audience-fit`.
- Verify glossary-introduction per `.claude/output-styles/house-style.md § Glossary-introduction`.
- Verify why-before-what per `.claude/output-styles/house-style.md § Why-before-what`.
- Verify navigability per `.claude/output-styles/house-style.md § Navigability`.

**Reviewer tone** for these four rules relaxes the "one-sentence answers"
rule in § Tone and depth: quote the prose, list undefined terms, name the
audience the prose fails, and (for navigability) the opaque section.

## Reading rules
<!-- roles=reviewer-design phases=1,4 summary="Read only the provided design files; bounded vs whole-doc scope; grep-only plan reads; fetch house-style on demand." -->

- **Only the files above** — no source code, prior conversation, or other workflow files.
- **Bounded scope**: changed section + 1-2 surrounding + `## Overview` + (when present) `## Core Concepts`; open mechanics only for a specific `Mechanics:` link.
- **Whole-doc scope**: entire `design.md`; mechanics for link targets.
- **Plan / track-file reads**: grep-only for `**Full design**` link resolution.
- **`house-style.md` reads**: read only the cited `§ <heading>` section using grep + targeted Read (offset/limit). Never load the file whole and never pre-load all cited sections; fetch a section only when a finding is forming.

## Comprehension questions
<!-- roles=reviewer-design phases=1,4 summary="Seven ordered questions a cold reader answers with citations; insufficient material is itself a finding." -->

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
<!-- roles=reviewer-design phases=1,4 summary="Always-on checks: edge-cases sub-section, References footer, sibling consolidation, TL;DR, length budgets, Mechanics." -->

- Verify **Edge cases / Gotchas** per `.claude/output-styles/house-style.md § Edge cases sub-section required`.
- Verify **References footer** per `.claude/output-styles/house-style.md § References footer shape`.
- Verify **Same-shape sibling consolidation** per `.claude/output-styles/house-style.md § Same-shape sibling consolidation`.
- (**Whole-doc only**) Verify **Overview is concept-first** per `.claude/output-styles/house-style.md § Overview concept-first`.
- **TL;DR present**; **Mechanism overview ≤300 lines** (warn 200); **Length budget** ≤2,000.
- **`Mechanics:` link target** exists in `design-mechanics.md` when split applies.
- (**Whole-doc only**) **Core Concepts current and complete** for docs with Parts or ≥3 new domain terms; **`**Full design**` refs** in plan and track files resolve to real `design.md` sections.
- Mechanical checks live in `.claude/scripts/design-mechanical-checks.py`; see `design-document-rules.md § Mechanical checks` for the rule contracts.

## Output format
<!-- roles=reviewer-design phases=1,4 summary="The exact comprehension-assessment, mental-model verdict, structural-findings, and suggested-fixes Markdown to emit." -->

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
exception — e.g. `[blocker] audience-fit: <quoted prose +
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
<!-- roles=reviewer-design phases=1,4 summary="Blocker, should-fix, and suggestion definitions, with the length-tier thresholds that set should-fix vs blocker." -->

- **blocker** — comprehension fails, mechanical violation corrupts
  cross-refs, or section lacks TL;DR / References footer / shape.
- **should-fix** — comprehensible but a rule violated. Per
  `design-document-rules.md § Mechanical checks`, length tiers: 201-300 = suggestion, 301-400 = should-fix, >400 = blocker.
- **suggestion** — non-rule-mandated improvement.

## Tone and depth
<!-- roles=reviewer-design phases=1,4 summary="One-sentence answers, cite don't paraphrase, flag insufficiency, no intent-speculation; human-reader rules excepted." -->

- One-sentence answers where one suffices. **Exception**: the four Human-reader rules require evidence (see the Reviewer tone note under § Human-reader cold-read additions).
- Cite, don't paraphrase.
- If unanswerable, say "Insufficient — see finding below" and add the structural finding.
- Don't speculate about intent the doc doesn't state.
