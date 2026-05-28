<!-- workflow-sha: 367f5f83f1bce0e98eaeb0679973f9728db64b61 -->
# Track 1: Schema definition (`conventions.md §1.8`)

## Purpose / Big Picture

After this track lands, `conventions.md §1.8` is the single source of truth for the role enum, phase enum, per-section annotation idiom, TOC region format, and cross-reference convention.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Lock the role enum, phase enum, per-section annotation idiom, TOC region format, and cross-reference convention in a new `§1.8` of `conventions.md`. This section is the foundation every subsequent track reads from; it must land first.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-05-28T11:57Z [ctx=info] Review + decomposition complete

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

- [x] Technical: PASS at iteration 2 (6 findings, 6 accepted: T1, T4 should-fix; T2, T3, T5, T6 suggestions — glossary gained "Bootstrap block" row, worked example dropped stamp line and uses constructed demo heading, §1.8 self-annotation marked out-of-scope, acceptance bullet 3 clarified worked example vs real TOC region, decomposition collapsed from 3 steps to 2).

## Context and Orientation

`conventions.md` lives at `.claude/workflow/conventions.md`. The file currently carries sections §1.1 through §1.7 plus introductory header. The new section lands as `§1.8` directly after `§1.7 Staging for workflow-modifying branches`, before any future numbered sections.

Schema content already locked during Phase 0 research:

- **Role enum, 15 values** — see `design.md §"Role and phase enums"` for the full list and per-value descriptions.
- **Phase enum, 8 values** — `0`, `1`, `2`, `3A`, `3B`, `3C`, `4`, `any`.
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
- Per-section annotation comments on §1.8 itself and its `### ` sub-sections. The annotation rollout for `conventions.md` is Track 4's territory (the universal rollout covers conventions.md, including §1.8, in a single coordinated pass). Track 1 authors §1.8's rule body but does not pre-annotate §1.8's own headings.

## Plan of Work

The track lands as two steps:

1. **Author §1.8 body (including worked example).** Write the full schema section into the staged `conventions.md`. Includes the enum tables, the annotation idiom + TOC region format with a worked example block, and the cross-reference convention with read-decision flowchart. The worked example uses a constructed demonstration heading (e.g., `## 99.1 Demo section`) so the fenced code block does not collide with a real heading earlier in conventions.md. The example demonstrates the TOC region + annotation idiom only and does NOT include the `<!-- workflow-sha: ... -->` line — the stamp is a `_workflow/**` artifact concern per §1.6 and live workflow files like conventions.md never carry it.
2. **Update §1.1 glossary cross-links.** Add or augment glossary rows for the load-bearing terms: "Section annotation", "TOC region", "Role enum", "Phase enum", "Cross-reference convention", and "Bootstrap block". The first five anchor at §1.8 as the canonical reference; "Bootstrap block" anchors at design.md §"Bootstrap protocol for agent system prompts" (during Phase 1) or `design-final.md` (after the Phase 4 squash-merge), since Track 5 lands the bootstrap rollout but the conceptual definition stays in the design document.

The §1.8 author writes against the LIVE develop state of conventions.md (read from disk), then writes the staged copy with the new section appended. Subsequent track work that reads conventions.md from within the implementer sees the staged copy per §1.7(d) reads precedence.

## Concrete Steps

1. Author §1.8 schema body in staged conventions.md — full role enum (15 values) and phase enum (8 values), annotation idiom, TOC region format, cross-reference convention with read-decision flowchart, and a worked example block (fenced code) using a constructed `## 99.1 Demo section` heading and omitting the `<!-- workflow-sha: ... -->` line per Finding T2 — risk: low (default: docs authoring; no code, no behavior change; routed through `_workflow/staged-workflow/.claude/workflow/conventions.md` per §1.7(e))  [ ]
2. Add §1.1 glossary rows for Section annotation, TOC region, Role enum, Phase enum, Cross-reference convention, Bootstrap block — the first five anchor at §1.8 as the canonical reference; Bootstrap block anchors at design.md §"Bootstrap protocol for agent system prompts" (Phase 1) or `design-final.md` (post-Phase-4) since Track 5 owns the bootstrap rollout but the conceptual definition stays in the design document — risk: low (default: docs authoring; single-section additions in the same staged file)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

After this track lands:

- `conventions.md §1.8` exists and contains the full schema: role enum (15 values), phase enum (8 values), annotation idiom + TOC region format, cross-reference convention with read-decision flowchart.
- `conventions.md §1.1` glossary carries rows for the new load-bearing terms — Section annotation, TOC region, Role enum, Phase enum, Cross-reference convention (anchored at §1.8) and Bootstrap block (anchored at design.md / design-final.md).
- §1.8 includes a worked example block (fenced code, not a real TOC region) showing one fully-annotated heading and its corresponding TOC row, formatted exactly as authors should write them during Track 4's rollout. Conventions.md itself does not yet carry a real TOC region at the top of the file — Track 4's universal rollout introduces TOC regions across every in-scope workflow file in a single coordinated pass.
- A reader new to the schema can author a per-section annotation by reading §1.8 alone, without needing to consult `design.md` or this track file.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Both steps are file-rewrites against `_workflow/staged-workflow/.claude/workflow/conventions.md`. Re-running either step against an already-applied state produces no diff (the §1.8 body and the §1.1 glossary rows already exist verbatim in the staged file). Recovery from any failure reverts via the implementer's standard `git reset --hard HEAD` path; no on-disk artifacts other than the staged conventions.md change. Step 1 is the first touch on the staged path, so §1.7(e) copy-then-edit fires: the implementer copies the live `.claude/workflow/conventions.md` to the staged path verbatim before applying the §1.8 append, preserving develop's state as the staged baseline. Step 2 is a subsequent write to the same staged file and edits it in place per §1.7(e).

## Base commit

82c26e729b5bc7e5e7987d708ea0041fcef9f11f

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
