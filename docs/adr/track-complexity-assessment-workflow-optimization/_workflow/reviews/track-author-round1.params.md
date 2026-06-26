# design-author params — Step-4b track authoring, round 1

- target: tracks
- output_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan
- plan_dir: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/plan
- research_log_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
- design_path: docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md
- round: 1

## Settled decomposition (honor it — do NOT re-partition)

The orchestrator has already settled the track decomposition. Two skeleton
track files exist under `plan_dir`: `track-1.md` and `track-2.md`. Each carries
the settled facts you must keep as given:

- The **track titles**, the **`## Purpose / Big Picture`** section (BLUF +
  intro paragraph), the **in-scope / out-of-scope file lists** and the
  **inter-track dependency** in `## Interfaces and Dependencies`, the **DR
  titles / ownership / `**Full design**` pointers** in `## Decision Log`, the
  **§1.7 staging note**, and the **`## Invariants & Constraints`** statements
  are SETTLED. Do not re-partition the tracks, move a file between tracks,
  rename a DR, change a Purpose section, or alter the plan's Component Map
  (`../implementation-plan.md`). Those are standing anchors.

## What to write (fill the placeholders, ground in the seed and live code)

Fill every `<AUTHOR ...>` placeholder and every section body marked
`<!-- AUTHOR: ... -->`:

1. **`## Decision Log`** — fill the four bullets (Alternatives considered,
   Rationale, Risks/Caveats, Implemented in) of each seeded DR, deriving from
   the matching frozen `design.md` D-record named in the `**Full design**`
   pointer and the research log. Keep them cold-readable: a mid-level developer
   should rebuild the decision from the track file alone. Track 1 owns D1, D10,
   D8a; Track 2 owns D2, D3, D5, D6, D7, D8b, D9. Stay faithful to the frozen
   seed — this is `full`-tier, so the seed↔track fidelity criterion applies.
2. **`## Context and Orientation`** — the codebase state at track start.
3. **`## Plan of Work`** — the prose sequence of edits (the bracketed guidance
   in the skeleton names the edits; turn it into grounded prose).
4. **`## Interfaces and Dependencies`** — keep the file lists and dependency as
   given; add any relevant library/function signatures and an optional ≤10-node
   Mermaid diagram only where it earns its place.
5. **`## Validation and Acceptance`** — track-level behavioral acceptance.

Leave the Phase-A placeholder sections (`## Concrete Steps`,
`## Idempotence and Recovery`, the Move-2/Move-3 reserved comments) and the
empty continuous-log sections exactly as they are.

## Grounding

Ground in the frozen `design.md` seed (the authoritative source — it is frozen,
Rule 15, do not propose changes to it), the research log, and the live workflow
machinery via PSI/Read. This is a workflow-machinery change: the in-scope files
are `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`, and
`.claude/scripts/**` markdown / bash / python — read them to ground the Context
and Plan-of-Work prose accurately. mcp-steroid PSI is for Java symbols and will
not help on these markdown/bash files; use `Read`/`Grep` and note no
reference-accuracy caveat is needed (no Java symbols are touched).

Return only a thin summary (what you drafted, where, any open question). Never
return the drafted track content in your reply.
