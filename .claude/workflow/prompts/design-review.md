# Cold-Read Comprehension Review (Sub-Agent Prompt)

You're a fresh agent reading a design document for the first
time. Your task: assess whether a human reviewer encountering
this document cold could build a working mental model from it,
**using only what's in the document(s) provided**.

This prompt is invoked by the design-mutation action defined in
`.claude/workflow/design-document-rules.md` § Mutation
discipline. The mutation action runs you after applying an edit
to `design.md` (and optionally `design-mechanics.md`), and it
consumes your verdict to decide whether to iterate, present a
warning, or pass.

## Inputs

- `design_path` — absolute path to `design.md`.
- `design_mechanics_path` (optional) — absolute path to
  `design-mechanics.md` if the design exceeded the length
  trigger.
- `scope` — `bounded` or `whole-doc`.
- `bounded_scope` (when `scope=bounded`) — the changed section
  name(s) plus one or two surrounding section names.
- `mutation_kind` — one of `content-edit`, `section-add`,
  `section-remove`, `section-rename`, `section-move`,
  `structural-rewrite`, `length-trigger-crossing`,
  `phase1-creation`, `design-sync`, `phase4-creation`.
  (`mechanics-edit` does not invoke this prompt — cold-read is
  deferred to the next `design-sync`.)
- `plan_path` (optional) — absolute path to
  `implementation-plan.md`. Read **only** to verify
  `**Full design**` link resolution, never for context.
- `backlog_path` (optional) — same: read only for link
  resolution.

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
  documentation — no reliance on working files (plan, backlog,
  tracks), since those live under `_workflow/` and are removed
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

Applies to mutation kinds `phase1-creation`, `phase4-creation`,
and `design-sync`. All three produce a freshly-written
`design.md` (or `design-final.md`) Overview that humans will
read.

These checks address human-readability gaps that the standard
comprehension questions miss when the reviewer agent shares
training-derived vocabulary with the doc author. The documents
produced by these mutation kinds are read by humans (PR
reviewers, the user, the architect, future re-readers, decision
auditors) in addition to agents — so the cold-read must assess
prose against a human reader, not against the reviewer's own
training.

`design-sync` is included because mechanics evolves freely
between syncs while `design.md` stays frozen; when sync
re-distills the Overview, it can introduce undefined domain
terms, lose an audience anchor, or shift to mechanism-first
ordering without any single working-mode edit visibly causing
the drift. The TL;DR-vs-mechanics instruction in the
`design-sync` block above catches alignment drift between the
two files; these checks catch human-readability drift in the
freshly-rewritten Overview itself.

**In addition to the standard whole-doc cold-read**, verify:

(a) **Audience-fit** — the Overview names (or strongly implies
    through concrete framing) the intended reader. Assess the
    prose against *that* reader, not against your own training
    as a coding-aware agent. If the doc doesn't name or imply
    an audience, flag it as a blocker and request the doc
    author add an audience anchor in the Overview's first
    paragraph.

(b) **Glossary-introduction** — walk through the Overview and
    list every internal API, type, or domain concept used in
    load-bearing prose (used to make the Overview's argument,
    not merely mentioned in passing). For each, determine
    whether it is (i) defined inline at first use, (ii) defined
    in a `## Core Concepts` section that the Overview points
    to, or (iii) explicitly listed as assumed knowledge in the
    audience anchor or a prerequisites bullet (e.g., *"Audience:
    storage engineers familiar with WAL and page caches"* — the
    listed prerequisites count as defined-by-reference for that
    audience). Anything load-bearing that fails all three is a
    finding. Severity: **blocker** if a reader without the term
    cannot follow the Overview's main argument; **should-fix**
    if the term appears in a supporting clause but the main
    argument survives without it. Apply the same check to the
    opening of each `##` section's mechanism overview, scoped
    to terms newly introduced in that section.

(c) **Why-before-what** — the Overview and the opening
    paragraphs of each `##` section open with motivation
    before mechanism. **Excluded** from this check: shape-exempt
    reference sections (Core Concepts, Class Design, Workflow,
    Part-level TL;DR per design-document-rules.md § Mechanical
    checks) — these are intentionally mechanism-first and
    naming a motivation paragraph in them adds noise rather
    than clarity. For everything else, quote the opening 1-2
    paragraphs and assess: does the reader learn *why this
    section exists / why this mechanism matters* before
    encountering the mechanism itself? A section that opens
    with "this design replaces X with Y" without first
    establishing why X needed replacing is a should-fix.

(d) **Navigability** — section headers communicate purpose,
    not just a mechanism name; each section's opening sentence
    or TL;DR lets a skimming reader decide whether to drill
    in; cross-references to deeper detail (Mechanics links,
    references to sibling Parts) are present where a reader
    would need them. Severity: **should-fix** — these are
    quality concerns, not comprehension blockers (a reader can
    still follow an opaquely-named section by reading its
    body, but the doc is harder to scan).

**Reviewer tone**: the "one sentence answers / don't pad"
guidance under § Tone and depth is relaxed for findings under
(a), (b), (c), and (d). Quote the prose, list undefined terms,
name the section whose header reads opaquely or whose
cross-references are missing, and explain *for which target
audience* the prose breaks down. Compressed reviewer feedback
under-reports these failure modes — a one-sentence "the
Overview is hard to follow" finding is not actionable; a
finding that lists six undefined APIs and identifies the
absent audience anchor is.

## Reading rules

- **Read only the files at the paths above.** No source code.
  No prior conversation context. No other workflow files.
- **Bounded scope**: read the changed section + 1-2 surrounding
  sections + the `## Overview` section + (when present) the
  `## Core Concepts` section. Do not read the rest of `design.md`
  and do not open `design-mechanics.md` unless verifying a
  specific `Mechanics:` link.
- **Whole-doc scope**: read the entire `design.md`. Read
  `design-mechanics.md` only when verifying that a
  `Mechanics: design-mechanics.md §"…"` link resolves.
- **Plan / backlog reads** are restricted to grepping for
  `**Full design**` lines and verifying the targets exist.
  Do not read plan or backlog content for context.

## Comprehension questions

Answer each in order. For each, **cite the specific paragraph(s)
or sentence(s) you relied on** (quote a phrase or name the
section + a 1-sentence anchor). If the document doesn't give you
enough to answer, that itself is a finding.

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

- **TL;DR present** in the changed section, ≤5 lines, no
  parenthetical D/S asides like `(per D27)` or `(see S14)`.
- **Mechanism overview prose** ≤300 lines (warn at 200).
- **Edge cases / Gotchas** sub-section present (or N/A
  justified in prose).
- **References footer** present with D-records, Invariants,
  `Mechanics:` link.
- **`Mechanics:` link target** exists in `design-mechanics.md`
  (when split applies). Open mechanics file, verify the section
  heading is present.
- **Same-shape sibling check**: are there 3+ sibling sections
  with similar internal heading sequences that should be
  consolidated under the consolidation form (TL;DR + comparison
  table + per-instance short bodies)?
- **Length budget**: did this change push `design.md` over
  2,000 lines without splitting into `design-mechanics.md`?
- (**Whole-doc scope only**) **Overview is concept-first** —
  starts with the baseline being replaced + the change, no
  meta-navigation block (audience listing, journey table) ahead
  of the concept. Closes with companion-file pointer (when
  applicable) and a one-sentence document-structure roadmap.
- (**Whole-doc scope only**) **Core Concepts is current and
  complete** (when the doc has Parts or ≥3 new domain terms) —
  every load-bearing concept the Parts use without re-definition
  appears in Core Concepts; each entry has a `→ Part X §"…"`
  pointer; no concept entries name removed Parts.
- (**Whole-doc scope only**) **`**Full design**` refs in plan
  and backlog all resolve** to real `design.md` sections.

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

- **blocker** — the doc fails the comprehension check, or a
  mechanical rule is violated in a way that would corrupt cross-
  references, or the section has no TL;DR / no References
  footer / no shape compliance at all. The mutation cannot
  stand.
- **should-fix** — the doc is comprehensible but a rule was
  violated (D/S parenthetical aside, mechanism prose grew past
  300 lines, missing edge-case bullets, etc.). The mutation can
  stand if the budget exhausts, but the finding is logged.
  (Per design-document-rules.md § Mechanical checks, the
  per-section length tiers are: 201-300 lines = suggestion,
  301-400 = should-fix, >400 = blocker. The mechanical script
  surfaces the precise tier; this rubric matches the script's
  should-fix threshold.)
- **suggestion** — improvement opportunity that isn't
  rule-mandated (e.g., "the TL;DR could be one sentence
  tighter").

## Tone and depth

- One sentence answers where one suffices. Don't pad.
  - **Exception**: findings under the Human-reader cold-read
    additions (§ above, applies to `phase1-creation`,
    `phase4-creation`, and `design-sync`) require evidence —
    quote the prose, list undefined terms, name the target
    audience the prose fails, and (for navigability findings)
    name the section whose header reads opaquely or whose
    cross-references are missing.
- Cite, don't paraphrase the whole section.
- If a question can't be answered from the document, say
  "Insufficient — see finding below" and add the corresponding
  structural finding.
- Don't speculate about what the design might mean if the doc
  doesn't say. The point of cold-read is to surface where the
  doc fails to convey intent.
