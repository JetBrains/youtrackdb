---
severity: medium
phase: state-0
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# Inline replanning lacks a design-sync trigger for shared facts

## Symptom

The 2026-05-09 inline replan (commit `6f60846e6f`, "Inline replan
after Track 22") amended the headline coverage Goal in
`implementation-plan.md` (from `85% line / 70% branch` to `~82–83%
line / ~70–71% branch`) and split a single Track 22 into three
sub-tracks (22a / 22b / 22c). The replan correctly updated the plan
checklist, the §Goals headline, D1's "Implemented in" footer, D5's
inline-replan note, the Non-Goals bullet (in a follow-up State 0
fix — see CR2 below), and the D5 risk caveat (CR6).

But the replan did **not** propagate the matching numeric / label
updates to `design.md`, even though the design asserted the same
facts in five different places:

- Overview ¶1: stale `85% line / 70% branch` target (CR3).
- Overview ¶2: stale `22 tracks total` count (CR4).
- Class Design "Used by" annotations: three `Track 22 (...)`
  references where 22a is the only sub-track that authors test
  classes (CR5).
- Workflow → Coverage Measurement mermaid loop predicate:
  hard-coded `Aggregate ≥ 85% / 70%?` (CR3).

State 0's autonomous consistency review correctly caught all five
mismatches as `mechanical` findings, applied them through the
`edit-design` skill (Mutation 1 in `design-mutations.md`), and the
gate verification VERIFIED them. So the State 0 backstop *worked* —
but only because the user re-set `## Plan Review` to `[ ]` after
the inline replan and re-invoked `/execute-tracks`, which is a
manual step. Inline-replan commits land **without** State 0 re-
validation by default; without that manual prompt, the design /
plan inconsistency would have shipped.

## Reproduction context

- Phase: state-0 (consistency-review surface) — but the root cause
  is in inline replanning's contract, which sits in
  `.claude/workflow/inline-replanning.md`.
- Workflow doc(s) involved:
  - `.claude/workflow/inline-replanning.md` (the protocol that ran
    in the source commit `6f60846e6f`)
  - `.claude/workflow/edit-design/SKILL.md` (the discipline the
    inline-replan should have invoked)
  - `.claude/workflow/conventions-execution.md` §2.1 Description
    lifecycle (defines plan/backlog handoff but not plan/design
    sync)
- Tool / sub-agent involved: inline-replanning orchestration
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any inline replan that amends a shared fact
  (number, label, track count, decision-record footer) the design
  document also asserts. The current convention re-validates the
  plan via the optional `/review-plan` invocation — but that's
  user-initiated.

## Why it's a problem

The plan and the design tell one story to the human reviewer and
to the execution agent. If they disagree, downstream tracks (Phase
A/B/C) read whichever doc resolves first and silently absorb the
stale fact. In this session State 0 caught it, but the catch was
gated on the user manually re-setting `## Plan Review` to `[ ]`
after the inline replan. If the user had skipped that step, every
subsequent Phase A/B/C of Tracks 22a/22b/22c would have read a
design with the original 85%/70% target and a 22-track count —
producing wrong test-target acceptance criteria, wrong class-
diagram annotations, wrong scope estimates.

The same gap will fire on any inline replan that touches:
- Coverage targets, performance budgets, or any other quantitative
  goal mentioned in §Goals or design Overview.
- Decision Record "Implemented in" footers.
- Component Map cluster names.
- Track count / structure (split, merge, reorder of an entire
  track).

## Proposed fix

Edit `.claude/workflow/inline-replanning.md` to add a
**Design-sync trigger** subsection before the "Final commit" step.
The subsection prescribes:

1. **Detection:** before committing the replan, the orchestrator
   diffs `implementation-plan.md` against the pre-replan state and
   identifies any changed line that names a fact also asserted in
   `design.md`. Concrete predicates:
   - Any line containing a coverage / performance / size target.
   - Any line in Architecture Notes (`#### Component Map`,
     `#### D<N>:`, `#### Integration Points`, `#### Non-Goals`).
   - Any change to a track-level identifier (track count, split,
     merge, reorder) the design's class diagrams or workflow
     diagrams reference.

2. **Sync:** for each detection hit, invoke the `edit-design`
   skill with `mutation_kind=content-edit` (or
   `structural-rewrite` if multiple sections are touched) to bring
   `design.md` in line. The mutation discipline's mechanical
   checks + cold-read sub-agent gate the change.

3. **Plan-Review reset:** after the inline-replan + design-sync
   land, the orchestrator resets `## Plan Review` to `[ ]` (no
   manual user step required). The next `/execute-tracks` enters
   State 0 automatically and re-validates the full plan / design
   / backlog set.

Alternatively — and more conservatively — add only step (3) and
keep the design-sync as a State 0 catch. That preserves the
"State 0 is the backstop" model but removes the manual reset
dependency, so the State 0 gate fires on every inline replan
without user intervention.

The conservative fix is cheaper and safer; the full fix is more
disciplined.

## Acceptance criteria

- `.claude/workflow/inline-replanning.md` has a `## Design-sync
  trigger` (or `## Plan-Review reset`) subsection with the rules
  above.
- An inline replan that amends a shared fact either (a) syncs
  `design.md` in-line via the `edit-design` skill, or (b)
  automatically resets `## Plan Review` to `[ ]` so the next
  `/execute-tracks` runs State 0.
- Regression check: simulate an inline replan that changes a
  Goals number; verify either the design.md amendment lands in
  the same commit OR `## Plan Review` is `[ ]` after the replan.
- The five CR3/CR4/CR5 mismatches that this State 0 caught would
  not exist on a future inline replan that follows the new
  protocol.
