# design-author params — phase1-creation, round 2 (mechanical-first)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-reconciliation-rules/docs/adr/track-reconciliation-rules/_workflow/research-log.md
- round: 2

## flagged_passages (mechanical blockers to fix — per-section mandatory shape)

The draft is missing the per-section mandatory shape on several `##` sections.
Fix all six; do **not** restructure or rewrite the prose otherwise:

1. **`## Core concepts` (≈line 42)** — add a `**TL;DR.**` block (≤5 lines) as
   the first paragraph after the heading.
2. **`## Core concepts` (≈line 42)** — add a `### Decisions & invariants`
   footer at the end of the section (list the D-records the section rests on;
   `### References` spelling is also accepted).
3. **`## The new Phase-C review loop` (≈line 72)** — add a `**TL;DR.**` block
   as the first paragraph after the heading.
4. **`## No-progress detection` (≈line 156)** — add a `**TL;DR.**` block.
5. **`## Per-level iteration policy` (≈line 222)** — add a `**TL;DR.**` block.
6. **`## Scope and the cap-3-keyed restate sites` (≈line 278)** — add a
   `**TL;DR.**` block.

Note: `## Overview` is the document lead and is TL;DR-exempt — leave it as is.
The line-1 `<!-- workflow-sha: ... -->` stamp is already in place — preserve
it on line 1, do not move or duplicate it. These are structural-shape
additions only; preserve all existing prose, the Mermaid flowchart, edge
cases, and References footers already present.
