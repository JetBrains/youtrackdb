# Plan Review

- Plan review (consistency + structural) — passed. Consistency at iteration 3
  (full review found CR1–CR3; gate iter-1 surfaced one fix-shifted regression
  CR4; gate iter-2 PASS, 0 new). Structural at iteration 1 (0 findings).

**Auto-fixed (mechanical)**:
- CR1 — Track 1 "what is there today" overstated existing agent frontmatter.
  No `.claude/agents/*.md` carries a `tools:` key today (0 of 20; only
  `name` / `description` / `model`); corrected, with the note that the `Agent`
  tool supports a `tools:` allow-list (the lever D13/D14 add).
- CR2 — Track 2 mislabeled today's Phase 4 build check as an "absorption-style
  comparison". Corrected to the PSI diagram-to-code verification against the
  as-built code, matching design.md's own Core Concepts ("Replaces a PSI-only
  comparison against code").
- CR4 — after CR3, six plan/track summary mentions still named a
  `conventions.md` S2-wording deliverable (the consistency gate caught this
  fix-shift at iter-1). Retargeted all six to `research.md` +
  `design-document-rules.md`.

**Escalated (design decisions)**:
- CR3 — the canonical S2 read-scope statement ("the log is read for decision
  content in exactly two places…") lives at `research.md` §"Read-scope
  discipline (S2)", not `conventions.md` (which carries no `S2` label, only
  descriptive cross-refs to "the two sanctioned read points");
  `design-document-rules.md:103` restates it. D18's bound deliverable ("edit
  `conventions.md` S2") therefore targeted a file holding no canonical S2
  statement, which would have left research.md's "exactly two places"
  unchanged and re-introduced the third-unnamed-reader inconsistency D18 exists
  to prevent. **User resolution: D18 targets `research.md` (canonical) +
  `design-document-rules.md` (restatement); the conventions.md cross-refs are
  left alone (they name the two sites, not the readers, and the absorption
  agent folds under the existing authoring site, so the count stays two).**
  Applied across the five Track 1 sites (Context/Orientation S2 bullet, D18,
  Plan of Work step 4, Interfaces in-scope list, the S2 invariant).

**Deferred to Phase 4**: the frozen `design.md` D18 / Overview / §"The S2 and S3
read-scope invariants" carry the same `conventions.md` misattribution.
`design.md` is frozen after Phase 1, so its design-side correction defers to the
Phase 4 `design-final.md` reconciliation against the as-built code. The track
files — the live decision carriers under D7 — are corrected.
