<!-- MANIFEST
findings: 1   severity: {suggestion: 1}
index:
  - {id: WS1, sev: suggestion, loc: ".claude/workflow/step-implementation.md:877-880", anchor: "### WS1 ", cert: C1, basis: "trailing 'not X' negation appended to an already-complete positive claim; § Banned sentence patterns (negative parallelism / roundabout negation); mirrored verbatim across both files"}
evidence_base: {section: "## Evidence base", certs: 1, matches: 1}
cert_index:
  - {id: C1, verdict: MATCHES, anchor: "#### C1 "}
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [suggestion] Negative-parallelism tail in the burden-measure prose

- **File:** `.claude/workflow/step-implementation.md` (line 877-880) and `.claude/workflow/track-code-review.md` (line 348-353) — one authoring pattern replicated verbatim-parallel across the two mirror files (Inv 3).
- **Axis:** banned sentence patterns.
- **Cost:** mild trailing negation appended to a complete positive claim; low-severity AI-tell in workflow-body prose (not an always-loaded description).
- **Issue:** The added burden-measure sentences close on a `not X` tail — "…so its line count is genuine burden, **not inflation**." (`step-implementation.md`) and "…so its line count is genuine burden, **not noise**." (`track-code-review.md`). This is the roundabout-negation / negative-parallelism shape in `house-style.md § Banned sentence patterns` ("State what IS true"). The positive clause "its line count is genuine burden" already carries the full claim, and the distinction from the copy-of-live case is already stated by "its whole-file content is the real review surface"; the "not inflation" / "not noise" tail only echoes the corrected term ("inflates" / "noise") and adds no information the sentence does not already hold.
- **Suggestion:** Drop the trailing negation and let the positive stand.
  - `step-implementation.md`: "A NEW staged file with no live counterpart is the exception: its whole-file content is the real review surface, so its whole line count is genuine review burden."
  - `track-code-review.md`: "A NEW staged file with no live counterpart has no such delta; its whole-file content is the real review surface, so its whole line count is genuine review burden."

## Evidence base

#### C1 [WS1] MATCHES

Banned sentence patterns sweep — "genuine burden, not inflation" / "genuine burden, not noise" is a `not X` tail on a complete positive clause, matching `house-style.md § Banned sentence patterns` → negative parallelism / roundabout negation. The corrected term is already named earlier in each sentence, so the negation adds no information. Confirmed; low severity because it is a single mild slip in workflow-body prose. (The pre-existing "order-of-magnitude signal, not a precise bound" on `step-implementation.md:876` carries the same shape but is untouched by the diff, so it is out of scope.)
