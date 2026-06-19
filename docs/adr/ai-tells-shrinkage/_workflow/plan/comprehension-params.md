# comprehension-review params — Track 1 (Step-4b cold-read gate)

- target: tracks
- scope: whole-doc
- plan_dir: docs/adr/ai-tells-shrinkage/_workflow/plan/
- plan_path: docs/adr/ai-tells-shrinkage/_workflow/plan/track-1.md
- (no design_path — minimal tier, no design.md)
- (no research_log_path — you read only the document, never the log)
- (no output_path — return your verdict and structural findings inline)

## What to assess

Read only `plan/track-1.md`. Run the comprehension questions: can a fresh
mid-level reader, with only this document, build a working mental model of what
the track does and why? Then the structural findings (section homes, ordering,
navigability). You do **not** run the prose AI-tell axis (the readability auditor
owns that, S4) and you do **not** run the absorption cross-check against the
research log (the absorption-check spawn owns that). Return a bounded
comprehension verdict plus a summary-shaped `## Structural findings` list.

## Note on use-mention

This track is *about removing* writing rules, so it necessarily quotes the names
of removed rules and tells ("delve", "Let's dive in", "serves as", "It's not X,
it's Y") to describe what it removes. Those are mentions, not the track using the
tell. Do not treat a quoted removed-rule name as a comprehension or structural
defect.
