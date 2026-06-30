# design-author params — Step-4b track authoring, round 1 (target=tracks)

- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/design.md
- round: 1

## Settled track shape (the orchestrator settled this; you write the prose)

This is a **single-track** change (`design_gate=yes`, one track, no
`implementation-plan.md`). Fill the pre-created skeleton at
`plan/track-1.md` — **preserve its line-1 `<!-- workflow-sha: ... -->`
stamp byte-for-byte** and write prose into the sections below. The frozen
`design.md` is the seed; the track's `## Decision Log` carries the full
inline Decision Records (the track-canonical live carrier), seeded from the
design's D-records. This is workflow prose — verify references as workflow
paths/§-anchors via Read/grep (no PSI, no Java).

**Track title**: "Phase-C review iteration keyed to the per-track complexity tag"

**`## Purpose / Big Picture`** — BLUF: after this track, the Phase-C
track-level code review loop terminates per the per-track complexity tag
(uncapped blocker loop at every level; should-fix depth scales with the tag),
with no-progress detection replacing the fixed cap-3 escalation. Restate a
short intro paragraph so the file is self-sufficient.

**`## Decision Log`** — seed the full inline four-bullet Decision Records from
the design's seed records (Alternatives considered / Rationale / Risks-Caveats
/ Implemented-in = this track):
- D1: tag axis = per-track complexity tag (not per-step risk tag).
- D2: scope = Phase-C track code review only (Phase 2/3A/3B keep cap-3); and
  the D2.1 sub-decision — wire the carve-out sentence into
  `review-iteration.md` §Limits, not merely assert it in `track-code-review.md`.
- D3: the per-level iteration policy (low = uncapped blocker loop only;
  medium = + up-to-3 should-fix; high = uncapped on both), stated as the delta
  from today; and D3.1 — the `medium` single shared counter (should-fix gated
  on `iteration ≤ 3`, blockers continue past it; no second counter).
- D4: no-progress detection replaces the cap-3 safety valve; and D4.1 (the
  operational definition on the gate-check verdict stream — identity by
  reviewer `id`, threshold = every carried finding `STILL OPEN` + zero net
  clears + no new fixable, `REGRESSION` escalates immediately) and D4.2 (it
  composes with the existing per-iteration context-consumption pause on a
  separate axis). Each DR may carry an optional `**Full design**: design.md
  §<section>` line into the seed.

**`## Context and Orientation`** — the in-scope workflow files and their
current state; the deliverables (the edited rules). Optionally a small
Mermaid diagram is unnecessary (the design.md already carries the loop
flowchart — do not duplicate it).

**`## Plan of Work`** — prose sequence of edits:
1. `track-code-review.md` §Review loop — replace the low/medium/high rigor-dial
   mapping (≈681-693) with the new per-level termination policy.
2. `track-code-review.md` §Review loop — add the no-progress detection
   definition, the `medium` shared-counter rule, and the context-pause
   composition note.
3. `track-code-review.md` — restate every cap-3-keyed site the uncapping
   touches. The authority is the live grep
   `grep -nE '3 iterations|N/3|/3|of 3|three iteration' .claude/workflow/track-code-review.md`,
   not a frozen line list (re-run it at implementation time); the sites as of
   now are lines ≈491, 527, 685, 724, 765, 832, 837, 848, 875, 1092, 1106,
   1256 (Progress format, the step-4 cross-session cap + resume read, the
   pre-spawn-split rationale, step-5/step-6 exits, the cost models, the
   FAILED/budget mentions, the checklist seed).
4. `review-agent-selection.md` §"Complexity sets the Phase-C rigor dial, never
   the set" — update the low/medium/high dial-mapping prose to the new policy.
5. `code-review/SKILL.md` (≈line 225) — update the standalone-skill note that
   describes the dial so its prose stays in sync.
6. `review-iteration.md` §Limits — add the one-line Phase-C carve-out sentence;
   keep cap-3-then-escalate as the default for Phases 2/3A/3B.

**`## Interfaces and Dependencies`** — in-scope: the four files above
(`track-code-review.md`, `review-agent-selection.md`, `review-iteration.md`
§Limits, `code-review/SKILL.md`). Out-of-scope (keep cap-3 unchanged):
`structural-review.md`, `implementation-review.md`, `track-review.md` (Phase A),
`step-implementation.md` (Phase B), and any other §Limits consumer outside
Phase C. No inter-track dependencies (single track). **Staging mode**: this is
a workflow-modifying, **prose-only** change (no scripts/hooks/settings/Java),
so it takes the §1.7(k) prose-rule self-application opt-out — workflow prose is
edited **live**, not staged under `staged-workflow/`. Note this in the section.

**`## Invariants & Constraints`** (testable for workflow prose — verified by
the Phase-C workflow-review agents + grep, not Java tests):
- Blockers always loop until clear at every complexity level — the dial never
  shortens the must-fix gate. Verified by: the new policy text + a
  consistency review.
- The change is scoped to Phase C; `review-iteration.md` §Limits keeps
  cap-3-then-escalate as the stated default for Phases 2/3A/3B. Verified by:
  reading §Limits after the edit.
- After the change, no cap-3-keyed site in `track-code-review.md` still asserts
  a fixed `/3` cap as live behavior. Verified by: re-running the restate-set
  grep and confirming every hit reads as restated (cost-model/illustrative) or
  carries the no-progress framing.
- `review-iteration.md` §Limits carries the Phase-C carve-out so a Phase-C
  reader landing there is routed to the override. Verified by: reading §Limits.
- The dial only changes iteration depth/termination, never which reviewers run
  (the domain-selected set is unchanged). Verified by: the
  `review-agent-selection.md` text still asserting "complexity never drops a
  domain-selected reviewer".

Leave `## Concrete Steps` and `## Idempotence and Recovery` as the Phase-A
placeholders already in the skeleton. Leave the continuous-log sections
(`## Surprises & Discoveries`, `## Episodes`, `## Outcomes & Retrospective`,
`## Artifacts and Notes`) empty. Write `## Validation and Acceptance` at the
track level (the behavioral acceptance: the loop terminates per the tag with
no-progress escalation; Phase A adds per-step lines later).
