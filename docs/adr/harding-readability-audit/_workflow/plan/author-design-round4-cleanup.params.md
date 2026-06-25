# design-author params — phase1-creation, final cleanup pass (budget-exhausted, orchestrator-directed)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/research-log.md
- round: 4 (cleanup; the orchestrator directs these specific fixes at budget
  exhaustion — apply exactly these, do not hunt for more)

## flagged_passages (orchestrator-selected cheap, unambiguous fixes only)

Edit `design.md` in place — these are the unambiguous prose fixes; the
remaining round-3 findings are accepted as calibrated holds and must NOT be
touched (do not add inline glosses for `S3 freeze-order gate`,
`log-adversarial entry`, `Phase-0→1 adversarial gate`, `Phase 3C`, or the
`absorption half` — they are defined in the footers and intentionally held).
No decision-content change. Keep line-1 stamp and line-2 H1 intact.

1. **Gloss "the issue" once in the Overview.** First mention of "the issue" →
   "the originating YouTrack issue (YTDB-1158)", so the later "the issue asks
   for" references resolve.

2. **Split the root-cause block paragraph (§Section-keyed settled-state body,
   the "The root cause of the oscillation…" paragraph).** Break into two
   paragraphs at "But the per-round state…" so the "what the loop does today"
   claim and the "why it re-litigates" mechanism are separate units.

3. **Split the over-acceptance block paragraph (§Calibrated holds, the "The risk
   a hold introduces…" paragraph).** Break into two paragraphs: the prose-hold
   backstop chain ending at "only backstop", then the contrasting "comprehension
   gate / iteration budget cover the other axes" sentence.

4. **Fix the exclusion-list sentence (§Section-keyed settled-state, the rejected
   `do_not_reflag` alternative).** Split the two rejection grounds into two
   sentences (or a 2-item list), and replace "busts the shared-prompt cache"
   with "invalidates the shared-prompt cache".

5. **De-negate "it hits the track path" (§Both paths).** "the convergence defect
   is not design-path-only; it hits the track path too, and is arguably more
   exposed there" → positive, literal verb: "the convergence defect applies to
   the track path too, and is arguably worse there, because each round re-rolls
   N track files."

6. **Pull the Phase-0→1 gloss out of the parenthetical (§File relocation, "Why
   the plan-scoped…").** Make "The Phase-0→1 gate is the adversarial review that
   clears the research log before any Phase-1 artifact derives." its own
   sentence placed before the clause that relies on it.

7. **Cut the superficial -ing clause (§File relocation).** "…generalizes §2.5's
   'Third-scope review-file home' from 'the Phase-0→1 gate's files' to 'Phase-1
   plan-scoped review scaffolding,' widening the home to cover the authoring
   loop." → drop ", widening the home to cover the authoring loop" (the
   from→to already says it).

8. **Split the §1.7(k) criterion-2 sentence (§Meta).** Make it two sentences:
   one stating criterion 2's test plainly, one applying it (this change's core
   edits are the dual-clean orchestration loop plus the resume glob — executable
   procedure, so the opt-out fails criterion 2).

9. **Generalize the understandable-design cross-reference (§Meta).** "…the same
   one the understandable-design branch took." → "…the same trade-off staged
   workflow-modifying branches accept: they cannot dogfood their own fixes
   during their own planning."

Return only a thin summary.