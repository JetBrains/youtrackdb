# readability-auditor params — Track 1, round 1

- target: tracks
- target_path: docs/adr/ai-tells-shrinkage/_workflow/plan/track-1.md
- range: 1-441 (whole file — single track, no fan-out)

## Standing anchors

Minimal tier has no plan and no plan Component Map, so the only standing anchor
is this track's own `## Purpose / Big Picture`. A term glossed there is not a
finding when the slice uses it.

## Note on use-mention (do not false-flag)

This track is *about removing* certain writing rules, so it necessarily **quotes
the names of removed rules and tells** to describe what it removes — for example
"delve", "foster", "Let's dive in", "serves as", "It's not X, it's Y". These are
mentions (naming the rule being discussed), not uses of the tell in the track's
own prose. Do not flag a quoted removed-rule name as an AI-tell. Flag only where
the track's own explanatory prose is genuinely over-dense, too terse to
reconstruct, hard to read, mis-registered, why-after-what, or uses an unglossed
project entity.
