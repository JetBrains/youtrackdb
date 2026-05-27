<!-- workflow-sha: 676179cb82295cf15977823a415d5f5476e42526 -->
# Track 1: Schema definition (`conventions.md §1.8`)

## Purpose / Big Picture

After this track lands, `conventions.md §1.8` is the single source of truth for the role enum, phase enum, per-section annotation idiom, TOC region format, and cross-reference convention.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Lock the role enum, phase enum, per-section annotation idiom, TOC region format, and cross-reference convention in a new `§1.8` of `conventions.md`. This section is the foundation every subsequent track reads from; it must land first.

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

`conventions.md` lives at `.claude/workflow/conventions.md`. The file currently carries sections §1.1 through §1.7 plus introductory header. The new section lands as `§1.8` directly after `§1.7 Staging for workflow-modifying branches`, before any future numbered sections.

Schema content already locked during Phase 0 research:

- **Role enum, 15 values** — see `design.md §"Role and phase enums"` for the full list and per-value descriptions.
- **Phase enum, 10 values** — `0`, `1`, `1a`, `1b`, `2`, `3A`, `3B`, `3C`, `4`, `any`. `1a` and `1b` reserved for YTDB-975.
- **Annotation idiom** — HTML comment on the line after every `##`/`###` heading, carrying `roles=`, `phases=`, `summary="..."`.
- **TOC region format** — Markdown table between `<!--Document index start-->` and `<!--Document index end-->` directly under H1.
- **Cross-reference convention** — `name.md:roles:phases` suffix on inline workflow-doc refs.

The §1.8 prose must include the read-decision flow from `design.md §"Cross-reference convention"` (the Mermaid flowchart) so a reader scanning §1.8 gets both the format definition and the runtime use.

### Files in scope

- `.claude/workflow/conventions.md` — staged under `_workflow/staged-workflow/.claude/workflow/conventions.md` per §1.7 staging rules.

### Files out of scope

- `.claude/workflow/conventions-execution.md` — not modified by this track.
- `.claude/scripts/**` — Track 2 territory.
- `prompts/**` — Track 3 territory.

## Plan of Work

The track lands as three steps:

1. **Author §1.8 body.** Write the full schema section into the staged `conventions.md`. Includes the enum tables, the annotation idiom + TOC format with a worked example, and the cross-reference convention with read-decision flowchart.
2. **Update §1.1 glossary cross-links.** Add new glossary rows (or augment existing ones) for the load-bearing terms: "Section annotation", "TOC region", "Role enum", "Phase enum", "Cross-reference convention". Each row points at §1.8 as the canonical anchor.
3. **Add worked example block.** Include one fully-annotated section example (an annotated heading + its TOC row) so authors writing per-section annotations during Track 4 have a copy-paste-ready template.

The §1.8 author writes against the LIVE develop state of conventions.md (read from disk), then writes the staged copy with the new section appended. Subsequent track work that reads conventions.md from within the implementer sees the staged copy per §1.7(d) reads precedence.

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

After this track lands:

- `conventions.md §1.8` exists and contains the full schema: role enum (15 values), phase enum (10 values with `1a`/`1b` flagged as reserved), annotation idiom + TOC format, cross-reference convention.
- `conventions.md §1.1` glossary has rows for the new load-bearing terms, each pointing at §1.8.
- The §1.8 section itself carries a TOC entry (the file's existing TOC region or one introduced as part of this track's worked example).
- A reader new to the schema can author a per-section annotation by reading §1.8 alone, without needing to consult `design.md` or this track file.

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

### In-scope file set

- `.claude/workflow/conventions.md` (staged copy under `_workflow/staged-workflow/.claude/workflow/`).

### Out-of-scope

- The scripts themselves (`workflow-reindex.py`, `measure-read-share.py`) — Tracks 2 and 3.
- Per-section annotations on any file other than `conventions.md` itself — Track 4.
- Cross-reference suffixes in agent files and SKILL.md startup read-lists — Track 5. (`CLAUDE.md` is out of scope per the plan's Non-Goals.)

### Inter-track dependencies

- **Unblocks Track 2.** Track 2's reindex script reads enum tokens from §1.8; the section must exist before the script can self-bootstrap.
- **Unblocks Track 4.** Per-section annotation authoring follows the format §1.8 locks.
- **Unblocks Track 5.** The cross-reference suffix convention §1.8 defines is what Track 5 applies.

### Library/function signatures touched

None — this track is pure rule authoring in a Markdown file. No code paths change.
