# design-author params — create-plan Step 4b (track authoring), round 1

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/plan
- research_log_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/design.md
- round: 1

## Notes for this spawn — the settled track shape

- **Single track.** The whole change is one track. A skeleton already exists at
  `plan/track-1.md` with the workflow-sha stamp on line 1 — fill the
  placeholder sections in place and do NOT touch line 1 or the section
  headings/order. There is no `implementation-plan.md` (single-track changes
  have no plan); the track file is the whole Phase-1 record, so make it
  self-sufficient.
- **Seed source: the FROZEN `design.md`** (committed, `Add initial design`).
  Derive every section from it; do not re-author or contradict it. The
  research log is provenance — the absorption check will two-way match its
  D1-D10 against your `## Decision Log` records.
- **Sections you fill:** `## Purpose / Big Picture` (one-line BLUF + intro
  paragraph + the `> **Scope:**` line + the `> **Complexity (predicted):**`
  line), `## Context and Orientation`, `## Plan of Work`,
  `## Interfaces and Dependencies`, `## Decision Log` (all ten DRs, full
  four-bullet form each: Alternatives considered / Rationale / Risks-Caveats /
  Implemented in: this track, plus a `**Full design**: design.md §<section>`
  line per record pointing at the seed section), `## Invariants & Constraints`
  (testable only), and `## Validation and Acceptance` (track-level prose;
  per-step EARS/Gherkin stays a Phase A placeholder). Leave the Phase-A
  placeholders and continuous-log sections as the skeleton has them.
- **Decision set to absorb (numbering kept from the log/design):** D1 remove
  six disguise-only rules at source + all mirrored consumers; D2 hybrid
  narrative+findings on the whole-doc gate, persona-voiced structured findings
  only on the sliced auditor; D3 Sonnet reader pins + Opus author pin (never
  Fable) + three false-clean mitigations; D4 TL;DR → `### Summary` sub-heading,
  both spellings accepted, complete rename-site list incl.
  `MANDATORY_OR_FORM_SUBHEADINGS`; D5 technical-writer voice on design/track
  prose only, exhaustive track-file prose-trio carve; D6 recast the two
  existing readers, no new spawns, persona governs stance not checklist (S4
  axis ownership survives); D7 per-rule remove/keep disposition + closure
  default + adjective-triads stays keep; D8 dual-register design doc, frozen
  design stays the track seed, skeleton registry-terse; D9 eight book
  voice-rule transfers + two precedence rankings + link-staleness re-read
  reminder; D10 YTDB-1163 BLUF hardening — self-contained lead,
  body-stands-without-lead, three acceptance sites, plain-claim-first
  composition with D9's backward link.
- **In-scope file set (staged copies)** — the implementation edits land under
  `docs/adr/tech-writer-tone/_workflow/staged-workflow/.claude/**` mirroring
  these live paths (copy-then-edit on first touch, §1.7(e)); list them in
  `## Interfaces and Dependencies` with per-file what-changes annotations:
  1. `.claude/output-styles/house-style.md` (D1/D7 removals; D5 §Voice and
     tone persona swap; D4 §Navigability; D10 §BLUF lead/§Orientation +
     exemplar pair + self-check item, renumber fallout)
  2. `.claude/output-styles/house-conversation.md` (removed-rule mirror
     lines; chat reader senior → mid-level)
  3. `.claude/agents/readability-auditor.md` (Sonnet pin; target-reader
     persona; persona-voiced structured findings)
  4. `.claude/agents/comprehension-review.md` (Sonnet pin; time-constrained
     reviewer persona; hybrid narrative+findings output; its TL;DR site)
  5. `.claude/agents/design-author.md` (Opus pin note, never Fable;
     technical-writer voice mandate)
  6. `.claude/agents/review-workflow-writing-style.md` (drop removed-rule
     criteria; D10 BLUF criteria; adjective-triads rule stays)
  7. `.claude/scripts/design-mechanical-checks.py` (delete
     `NEGATIVE_PARALLELISM_RE`, `NEGATIVE_PARALLELISM_TRAILING_RE`,
     `HYPHENATED_PAIR_CLUSTER_RE`, the Title-Case check; `section_has_tldr`
     gains `### Summary`; `SHAPE_EXEMPT_SECTION_NAMES` +
     `MANDATORY_OR_FORM_SUBHEADINGS` gain "summary")
  8. `.claude/scripts/tests/test_dsc_ai_tell.py` (remove assertions of the
     deleted regexes; regex+test+fixture are one unit)
  9. `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` (remove the
     matching fixture lines)
  10. dsc shape tests: both-spellings acceptance cases for the Summary rename,
      following the `test_design_mechanical_checks_d11.py` both-spellings
      precedent (new file or extension — Phase A decides placement)
  11. `.claude/skills/ai-tells/SKILL.md` (always-loaded `description:`
      frontmatter names three removed rules; catalogue entries)
  12. `.claude/workflow/design-document-rules.md` (Summary shapes/templates;
      D9 book-rule transfer + precedence; D8 dual-register; the dsc catalogue
      row)
  13. `.claude/workflow/prompts/design-review.md` (Summary structural finding
      + TOC row; D2 hybrid output contract for the comprehension gate)
  14. `.claude/skills/edit-design/SKILL.md` (~5 TL;DR sites; D9
      link-staleness re-read reminder on section-move/remove/rename)
  15. `.claude/workflow/planning.md` (its TL;DR site)
  16. `.claude/workflow/prompts/create-final-design.md` (its TL;DR site)
  17. `.claude/skills/review-workflow-pr/SKILL.md` (its TL;DR site)
  18. `.claude/workflow/conventions.md` (§1.5 tier pointers; its TL;DR site)
  19. `CLAUDE.md` (§ Writing Style negative-parallelism parenthetical)
- **Out of scope:** legacy committed designs under `docs/adr/**` (no
  backfill); `absorption-check.md` / `fidelity-check.md` (unchanged,
  personless); the live `.claude/**` tree until Phase-4 promotion; this
  branch's own `_workflow/` authoring artifacts (written under current live
  rules).
- **Sizing:** ~19 in-scope files — within the ~20-25 ceiling, above the ~12
  floor, and the change is complete (no further autonomous unit exists to
  pack), so the argumentation gate is satisfied by "this is the whole change".
  State this in `## Interfaces and Dependencies`.
- **Complexity prediction:** `high` — the Workflow machinery HIGH trigger
  fires centrally on the entire planned work (style/agent/script machinery).
  Record it on the `> **Complexity (predicted):**` line.
- **Invariants & Constraints (testable; refine wording as needed):** removal
  completeness (no removed-pattern regex, fixture line, or assertion remains —
  dsc test suite green after the triple deletion); both-spellings acceptance
  (`**TL;DR.**` / `### TL;DR` and `### Summary` all pass the shape regexes —
  pinned by new shape tests); `MANDATORY_OR_FORM_SUBHEADINGS` contains
  "summary" (same-shape sibling check does not false-positive on a well-formed
  `### Summary` design — pinned by test); S4 single prose-axis owner (the
  target reader) verified by grep over the staged agent definitions; §1.7/I6
  live-tree isolation (branch diff touches no live `.claude/**` path —
  verified by `git diff --name-only` scope check); D10 rules present at the
  three named acceptance sites (greppable). Process-only constraints (staged
  mode, authored-under-live-rules bootstrap) go to `## Context and
  Orientation` prose, not this section.
- **Register:** the track file itself is authored under the CURRENT live
  house-style rules (the new voice/shapes are staged, not live). The prose
  trio (`Purpose`, `Context and Orientation`, `Plan of Work`) is prose; the
  structured sections (Decision Log records, Invariants bullets, Interfaces
  lists) stay registry-terse.
- **§1.7 staged mode** (behavior-bearing script + agent changes; no §1.7(k)
  opt-out): state in `## Context and Orientation` that all implementation
  edits accumulate under `_workflow/staged-workflow/.claude/**` and promote
  only at Phase 4, and that the new rules are not live while the branch is
  open.
- No PSI needed — this is a prose/config/script change; Read/Grep suffice.
  Verify every `file:line`/symbol you cite against the live tree before
  writing it.
- Return a thin summary only (sections written, DR count, open questions);
  never the drafted track file.
