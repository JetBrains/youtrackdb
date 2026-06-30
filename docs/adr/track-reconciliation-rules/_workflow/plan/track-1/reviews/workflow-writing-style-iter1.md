<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: WS1, sev: suggestion, loc: .claude/skills/code-review/SKILL.md:225, anchor: "### WS1 ", cert: C1, basis: "single sentence packs the full per-tag termination policy into a dash+colon+parenthetical+trailing-clause chain; plain-language one-idea-per-sentence stumble"}
evidence_base: {section: "## Evidence base", certs: 1, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [suggestion] Over-packed termination-policy sentence in code-review SKILL.md body

- **File:** `.claude/skills/code-review/SKILL.md` (line 225)
- **Axis:** plain language (one idea per sentence) / mechanism traces
- **Cost:** the whole new per-tag termination policy is folded into one sentence the reader has to disassemble
- **Issue:** house-style `§ Plain language` ("keep sentences short, one idea each") and `§ Mechanism traces and inline citations` ("a sentence that chains a sequence of distinct... is a run-on the reader has to disassemble"). The added text reads:

  > moves only the **rigor dial** — what terminates the Phase-C review-iteration loop: blockers loop until clear at every level, while the should-fix depth scales with the tag (`low` should-fix never drives iteration, `medium` up to three iterations, `high` uncapped), with the uncapped loops terminated by no-progress detection rather than a fixed cap (see `review-iteration.md` § Limits and `track-code-review.md` § Review loop).

  One sentence carries a dash clause, a colon clause, a three-item parenthetical, and a trailing `with … rather than` clause. A maintainer skimming the skill body must hold the dash-clause subject ("what terminates this loop") in memory across the entire parenthetical to reach the no-progress qualifier. The parallel passages in the workflow files this skill cites split the same content across BLUF-led bold blocks (`track-code-review.md:679-693`) and a tight bulleted list (`review-agent-selection.md:233-244`), which read in one pass; the skill compresses all of it into the run-on.

- **Severity rationale:** suggestion, not should-fix. This is the SKILL.md body paragraph, not the always-loaded `description:` frontmatter (the frontmatter is unchanged by this diff), so the cost is a one-time maintainer stumble rather than a per-session always-loaded tell. The sentence is also accurate and complete — the defect is parse difficulty, not missing or wrong information.

- **Suggestion:** split into a lead claim plus a short enumeration, mirroring the workflow-file blocks it cites. For example:

  > moves only the **rigor dial** — what terminates the Phase-C review-iteration loop. Blockers loop until clear at every complexity level. The should-fix depth scales with the tag: `low` never lets should-fix drive iteration, `medium` allows up to three iterations, `high` is uncapped. The uncapped loops terminate by no-progress detection rather than a fixed cap (see `review-iteration.md` § Limits and `track-code-review.md` § Review loop).

## Evidence base

#### C1 [CONFIRMED] SKILL.md:225 over-packed sentence — banned-pattern / plain-language check

Banned sentence-pattern sweep: no negative parallelism, throat-clearing, roundabout negation, closing phrase, or trailing hedge in the added line. The "moves only the rigor dial — X, while Y" shape is genuine contrastive control-flow (one path capped, one uncapped), each clause carrying distinct information, so it is not the empty `not X — it's Y` inversion the rule bans. The confirmed defect is the plain-language one-idea-per-sentence stumble: the single sentence chains a dash clause + colon clause + 3-item parenthetical + trailing `with … rather than` clause, which fails the § Mechanism traces read-aloud test (cannot hold its structure to the end in one pass). Confirmed by comparison against the same policy as rendered in the cited workflow files (`track-code-review.md:679-693` bold BLUF blocks; `review-agent-selection.md:233-244` bulleted list), both of which split the content and read cleanly — the skill's version is the only single-sentence rendering. The flagged unit reads harder than its plain rewrite, with no count or score attached, per `§ Plain language`.

Scope-clearance notes (no finding; recorded for coverage):

- `track-code-review.md:671-756` — the rewritten `§Review loop` preamble. Dense workflow control-flow prose under a `##`-level section. Every bold block is BLUF-led ("**No-progress detection — the termination control…**", "**The `medium` single shared counter.**", "**Composition with the per-iteration context pause.**"). No padding pattern present, so the soft-cap length is not a finding per `§ Padding-based finding criterion`. The contrastive constructions ("shortens optional should-fix iteration depth, never the must-fix gates"; "should-fix drives a new iteration is gated; a surviving blocker is not gated") carry distinct technical content and are not banned negative parallelism.
- `review-iteration.md:39-46` — Phase-C exception. BLUF-led, accurate parenthetical summary, not padding.
- `code-review-protocol.md:53-58` — per-level termination statement. Clear two-regime contrast, reads in one pass.
- `design-decision-escalation.md:62-64` and `review-agent-selection.md:228-244` — re-keyed parenthetical and bulleted list. Both clean.
