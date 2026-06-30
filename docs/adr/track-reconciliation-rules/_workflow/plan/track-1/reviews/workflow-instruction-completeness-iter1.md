<!--
MANIFEST
dimension: workflow-instruction-completeness
level: high
output_path: docs/adr/track-reconciliation-rules/_workflow/plan/track-1/reviews/workflow-instruction-completeness-iter1.md
findings_under_recheck: 0
evidence_base: "## Evidence base"
cert_index:
  - id: WI1
    cert: C1
  - id: WI2
    cert: C2
flags: []
index:
  - id: WI1
    sev: should-fix
    anchor: "### WI1 [should-fix] §Iteration flow diagram asserts a live cap-3 terminal escalation for Phase C"
    loc: ".claude/workflow/review-iteration.md:95 (staged copy)"
    cert: C1
    basis: judgment
  - id: WI2
    sev: suggestion
    anchor: "### WI2 [suggestion] §Limits TOC summary still advertises a flat cap-3 to a Phase-C reader"
    loc: ".claude/workflow/review-iteration.md:7,35 (staged copy)"
    cert: C2
    basis: judgment
-->

## Findings

### WI1 [should-fix] §Iteration flow diagram asserts a live cap-3 terminal escalation for Phase C

- **File:** `.claude/workflow/review-iteration.md:95` (staged copy under `docs/adr/track-reconciliation-rules/_workflow/staged-workflow/.claude/workflow/review-iteration.md`)
- **Axis:** conditional branch coverage (a removed cap whose complement survives on an adjacent unchanged section)
- **Cost:** a Phase-C orchestrator that reads the §Iteration flow diagram follows a fixed cap-3 terminal escalation the change was supposed to remove for Phase C — escalating at iteration 3 instead of looping uncapped on blockers, contradicting the new §Review loop policy.

The change uncaps the Phase-C loop and adds the carve-out at `review-iteration.md` §Limits (staged lines 39-46): "Phase-C track code review overrides this … terminates by no-progress detection rather than **the fixed cap above**." "The fixed cap above" refers to §Limits' own two bullets (lines 37-38). Three sections later in the same file, §Iteration flow (staged lines 89-96) restates the same cap-3 escalation as a flow diagram:

```
Iteration 3: Gate check -> if still blockers, escalate
```

Its TOC row (staged line 10) is `roles=orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track phases=2,3A,3B,3C` — a Phase-C orchestrator matches it and loads the section. The diagram carries no Phase-C carve-out and no pointer to the override, so a Phase-C reader who lands here (via the TOC match, or reading the canonical iteration-protocol file top-to-bottom) reads "escalate at iteration 3" as the live flow for the loop they are running. This is the contradicting cap-3 instruction the review target asked to confirm absent.

The site escaped the plan's tree-wide restate grep because the pattern (`'3 iterations|N/3|/3|of 3|three iteration|Max 3|up to 3'`, Plan of Work edit 3 / restate authority) does not match the diagram's `Iteration 3:` token (no "3 iterations", no "/3"). This is structurally the same class of miss the Phase-A risk/adversarial reviews already caught for `code-review-protocol.md:53` and `design-decision-escalation.md:62` (track-1.md §Surprises) — a Phase-C-loading cap-3 assertion a digit-pattern grep cannot see.

**Suggestion:** extend the §Limits carve-out to reach the diagram, or annotate §Iteration flow directly. Either add a fourth diagram line / footnote — "(Phase-C track code review uncaps this terminal escalation per `track-code-review.md` §Review loop; it escalates on no-progress, not at iteration 3.)" — or widen the §Limits exception's scope phrase from "the fixed cap above" to "the fixed cap stated in this file (the bullets above and the §Iteration flow diagram below)" so a Phase-C reader of either section is routed to the override.

### WI2 [suggestion] §Limits TOC summary still advertises a flat cap-3 to a Phase-C reader

- **File:** `.claude/workflow/review-iteration.md:7,35` (staged copy)
- **Axis:** state marker / routing-hint completeness (TOC summary not updated to reflect the new branch)
- **Cost:** a Phase-C reader using the TOC protocol reads the §Limits row summary as the section's gist before deciding to open it; the summary omits the Phase-C exception, so the routing hint understates that a Phase-C-specific override exists in the body.

The §Limits TOC row (staged line 7) and the mirrored body comment (staged line 35) both read `summary="Max 3 iterations per review type; escalate if blockers persist."` for `phases=2,3A,3B,3C`. The new Phase-C exception lives only in the section body (lines 39-46). Per the project's TOC protocol a matched reader does open and read the section, so the body carve-out is reachable — which is why this is a suggestion, not a should-fix — but the summary now misdescribes the section for the one phase (3C) that no longer follows the flat cap.

**Suggestion:** append the exception to both the TOC row and the body comment summary, e.g. `summary="Max 3 iterations per review type (escalate if blockers persist); Phase-C track review overrides — no fixed cap, no-progress termination."` so the routing hint matches the section content for Phase-C readers.

## Evidence base

#### C1 — §Iteration flow is Phase-C-loaded and asserts a live cap-3 escalation the override does not reach

CONFIRMED. Verified the three legs of the gap:

1. **Phase-C reader loads it.** Staged `review-iteration.md` TOC row 10 and body comment line 90 both carry `roles=orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track phases=2,3A,3B,3C` — a Phase-C orchestrator matches `orchestrator`/`3C` and reads the section.
2. **It asserts the cap as live flow.** Staged body lines 92-95 render `Iteration 3: Gate check -> if still blockers, escalate` with no Phase-C qualifier and no override pointer.
3. **The override does not cover it.** The §Limits carve-out (staged lines 39-46) scopes itself to "the fixed cap above" — §Limits' own bullets (37-38) — not the §Iteration flow diagram. The plan's restate-authority grep pattern (`'3 iterations|N/3|/3|of 3|three iteration|Max 3|up to 3'`, track-1.md Plan of Work edit 3) does not match `Iteration 3:`, so neither the original nor the tree-wide grep flagged it. Same miss-class as the Phase-A-surfaced `code-review-protocol.md:53` / `design-decision-escalation.md:62` hits in track-1.md §Surprises. The primary override path (§Review loop policy + §Limits bullets + code-review-protocol synthesis preamble) is complete and governs, so this is a contradicting-instruction gap rather than a stranded-LLM gap → should-fix.

#### C2 — §Limits TOC summary omits the Phase-C exception

CONFIRMED. Staged `review-iteration.md` line 7 (TOC) and line 35 (body comment) both read `Max 3 iterations per review type; escalate if blockers persist` for `phases=…,3C`, while the new exception is only in the body (lines 39-46). Lower impact than C1 because the TOC protocol has a Phase-C reader open and read the section (where the carve-out is reachable); the defect is a stale routing hint, not a followable contradicting instruction → suggestion.

Coverage note: the in-file restate inside `track-code-review.md` is complete — every residual cap-3 token (staged lines 681, 700, 706, 725, 731, 737, 898) is either historical framing ("the prior cap-3 dial", "the same escalation shape cap-3 exhaustion produced") or the intentionally-retained `medium` should-fix cap; none asserts the blocker loop or `high` should-fix as live-capped. The Step 4 resume reader (staged 895-901) was correctly restated to drop the remaining-cap arithmetic ("there is no remaining-cap count to compute"). The `medium` post-three re-surfacing branch is fully specified (staged 740-743). `mid-phase-handoff.md` (not in the changed set) reads the iteration count generically (line 381) and its "iteration 3" mention (line 67) is an illustrative pause example, not a terminal-state assertion — no remaining-cap reader survives there.
