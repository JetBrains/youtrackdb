# design-author params — phase1-creation, round 3

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/track-complexity-assessment-workflow-optimization/docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-complexity-assessment-workflow-optimization/docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
- round: 3

Revise only the flagged passages below; leave every other section byte-stable.
Locate each by its verbatim quote (line numbers may have drifted). All are
gloss/reword/restructure fixes needing no new code read. This is the final
budgeted round — make these fixes clean and self-contained.

## flagged_passages — round 3 (all should-fix)

1. **Overview length (mechanical, cap 40, currently 41 lines).** Tighten the
   Overview by one line without dropping content — condense one sentence (e.g.
   the closing roadmap sentence, or one of the conflation-explanation clauses)
   so `## Overview` is ≤40 lines.

2. **Data model — the unbundling-diagram prose** (the sentence after the first
   `flowchart` that reads "the one `tier` value splits into the three questions
   it conflated (design presence → `design_gate`, multi-track → track-count, the
   resume-collision fix the unbundling creates → Phase-1-complete marker) plus
   one net-new field ..."). A three-item arrow-mapping is crammed into a
   parenthetical that restates the diagram directly above it. Break the three
   mappings onto separate lines (a short bullet trio) or state only the
   conclusion in prose and let the diagram carry the per-field mapping; keep the
   net-new-field "why" (the per-track reconciled tag exists only because
   complexity is now per-track state) as its own short sentence.

3. **Part 2 — the reconciliation TL;DR** (the sentence "When decomposition
   produces `max(step tags)` above the predicted track tag — any upward miss,
   including one level — the orchestrator, still within Phase A and before the
   A→C commit, runs ... feeds ... and re-runs to PASS through the existing cap-3
   loop."). One sentence stacks a dash-interrupted condition, a second mid-
   sentence interruption, and a three-part action chain. Split into a condition
   sentence and an action sentence, folding the timing into the lead, e.g.:
   "When decomposition produces `max(step tags)` above the predicted tag — any
   upward miss — the orchestrator reconciles before the A→C commit. Still within
   Phase A, it runs the strategic reviewers the predicted panel skipped, feeds
   their findings back into decomposition, and re-runs to PASS through the
   existing cap-3 loop."

4. **Part 3 — "not subsumed, not closed"** (the clause stating YTDB-1100 /
   YTDB-1056 Part 2 are out of scope "— not subsumed, not closed."). Roundabout
   negation in negative-parallelism shape. State it positively, e.g. "stay open
   and untouched here."

5. **Part 3 — the "cranks" metaphor** (two sites: "how hard each selected
   reviewer cranks" and "how hard the crank turns"). Replace the figurative verb
   with the literal phrasing the document already establishes (rigor dial =
   iteration depth), e.g. "how many iterations each selected reviewer runs" and
   "how deep the iteration goes."

6. **Part 3 — the workflow-machinery floor glob enumeration** (the sentence
   listing `prompt-design` / `instruction-completeness` / `hook-safety` /
   `writing-style` "each firing when the diff touches files matching its glob —
   `hook-safety` on ...; `writing-style` on ...; `prompt-design` and
   `instruction-completeness` on ..."). Four reviewer→glob mappings spliced into
   one sentence. Render the four file-pattern triggers as a short bullet list
   (one reviewer→glob per line), keeping the lead sentence ("The
   workflow-machinery analog is ...") as orientation.

7. **Part 3 — "It governs roster-adapted"** (the sentence on the
   workflow-machinery high-step rule: "It governs roster-adapted (the split/merge
   changes only which agents the globs name); the design reads that paragraph for
   the exact step / track split and invents no new rule."). The appositive
   "roster-adapted" dangles with no subject. Split into two plain sentences and
   repair the grammar, e.g.: "That rule governs here unchanged in logic; only the
   roster adapts — the split/merge changes which agents the globs name, not the
   step/track split itself. The design defers to that paragraph and invents no
   new rule."

8. **Part 4 — the artifact-derivation TL;DR `⟺` glyphs** (the TL;DR sentence
   "Each artifact ties to the axis that justifies it: `design.md` ⟺ design gate =
   yes; `implementation-plan.md` ⟺ track count > 1; `adr.md` ⟺ the change has at
   least one track of medium-or-high reconciled complexity."). Replace the three
   `⟺` glyphs in the TL;DR with the spelled-out "iff" the document already uses
   in this Part's prose, e.g. "`design.md` exists iff the design gate is yes;
   `implementation-plan.md` iff track count > 1; `adr.md` iff at least one track
   is medium-or-high." Leave the compact `∃` / `≥` form in the artifact-axis
   table cell, where terse formula notation is appropriate.
