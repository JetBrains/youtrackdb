# design-author params — phase1-creation, round 1

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/research-log.md
- round: 1
- flagged_passages: (none — round 1, ground the whole document)

## Design-shape note (this change)

This is a **workflow-prose change**, not Java/storage code. The aim: change
the Phase-C track-level code review loop's iteration policy so it is keyed to
the per-track complexity tag, replacing the cap-3-then-escalate ceiling on the
uncapped paths with no-progress detection. Ground on the research log's
`## Decision Log` (D1–D4 and their D2.1/D3.1/D4.1/D4.2 revisions) and the
actual workflow files — especially `review-iteration.md` §Limits +
§Gate-check verdict handling, `track-code-review.md` §Review loop,
`review-agent-selection.md` §"Complexity sets the Phase-C rigor dial, never
the set", and `code-review/SKILL.md`. There are no Java symbols; verify every
reference as a workflow path/§-anchor via Read/grep (PSI not needed — note the
caveat in your summary).

Adapt the design.md shape to this domain:
- `## Overview` — concept-first: what the dial is today, what changes, why.
- In place of a class diagram, a **Mermaid flowchart of the new Phase-C review
  loop**: entry → read reconciled complexity tag → the per-level branches
  (low / medium / high) → the severity gates (blocker loop, should-fix loop)
  → the no-progress / context-pause exits.
- A `## No-progress detection` section: the operational definition built on
  the existing gate-check verdict stream (identity by reviewer `id`; threshold
  = every carried finding `STILL OPEN`, zero net clears, no new fixable
  finding; `REGRESSION` escalates immediately; gates each uncapped loop), and
  its composition with the existing per-iteration context-consumption pause
  (orthogonal axes — per-session burn vs convergence).
- A `## Per-level iteration policy` section: the low/medium/high mapping
  stated as the delta from today's behavior, including medium's single shared
  counter (should-fix gated on `iteration ≤ 3`, blockers continue past it).
- An edge-cases / scope subsection: the §Limits carve-out wiring, suggestions
  never driving iteration, and the cap-3-keyed sites the change must restate.

Single file only — no `design-mechanics.md` companion (the design is small).
