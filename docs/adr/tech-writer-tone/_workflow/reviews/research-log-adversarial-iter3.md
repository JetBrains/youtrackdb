<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: A13, sev: suggestion, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:21, anchor: "### A13 ", cert: C18, basis: "D1's A10-completion parenthetical misnames the fourth removed pattern in the design-document-rules.md:289 row — the row names the trailing-negation variant, not curly quotes; 'curly' appears nowhere in that file"}
verdicts: {verified: 3, still_open: 0, rejected: 0}
verdict_index:
  - {id: A10, verdict: VERIFIED}
  - {id: A11, verdict: VERIFIED}
  - {id: A12, verdict: VERIFIED}
evidence_base: {section: "## Evidence base", certs: 1, matches: 0}
cert_index:
  - {id: C18, verdict: FAILS, anchor: "#### C18 "}
overall: PASS
flags: [CONTRACT_OK]
-->

## Verdicts

### A10 — VERIFIED
D1's consumer list now carries both missing consumers at the demanded granularity: `design-document-rules.md:289` cited as the dsc-ai-tell catalogue row, and the project `CLAUDE.md` § Writing Style paragraph cited by its "It's not X — it's Y" exemplar (live at `CLAUDE.md:93`). Re-running the stated grep returns 11 files: eight map to D1's consumer list, one is the source `house-style.md`, and two are the known false hits (`workflow-reindex.py:285` — "hyphenated roles" in a parser comment; `ephemeral-identifier-rule.md:157-158` — hyphenated finding-ID shapes). Every real consumer the grep surfaces now appears in the list, so the enumeration and the derivation it cites agree — the falsification C15 traced is gone. Two residuals, neither reopening: the proposed false-positive note was not added (the two false hits are self-evidently non-consumers a re-deriver filters on sight, and the finding's basis was the two missing consumers), and the new row-granularity parenthetical misnames one of the row's patterns — raised as A13, since the routing to the row lands regardless.

### A11 — VERIFIED
The correction is textually accurate where it is load-bearing. `review-workflow-writing-style.md:112` is the vague-adjective-stack rule with the exact "comprehensive, robust, scalable" example D7 now quotes; the keep-criterion citation is real — `house-style.md:84` (§ Plain language) prescribes the "name the quality" move verbatim (`robust` → "tolerant of <X>", `comprehensive` → "covers X, Y, Z"), matching D7's "each vague adjective must become a named quality". Keep-at-consumer-sites coexists cleanly with D1's removals inside the same agent file: the negative-parallelism/Title-Case/roundabout/closing-phrase mentions at `:29`, `:34`, `:71`, `:188` are D1 targets while `:112` stays, and the template's adjective-triads axes at `:191`/`:200` keep their enforcer. One residual imprecision: the second site is still called "the `ai-tells` catalogue" although "adjective triads" lives only in the skill's frontmatter `description:` (line 3), not in a body catalogue entry. The phrase is carried over from the pre-fix text, and under the keep disposition it is inert — no action happens at that site and the frontmatter mention indeed stays — so it is noted, not reopened.

### A12 — VERIFIED
The closure sentence dispositions every remaining section: "every other track-file section — including the author-written `## Validation and Acceptance` and `## Idempotence and Recovery` — stays registry-terse" quantifies universally over the 12 non-trio sections and names exactly the two plan-at-start prose sections C17 flagged. Checked against the live 15-section template (`conventions-execution.md:83-248`): the voice trio, the five named structured surfaces, and the closure now cover all 15 sections with no inference left to the word "only", and "registry-terse" fairly describes the current register of the seven previously-unnamed sections (checkbox log lines, timestamped log entries, acceptance-criteria/EARS lines, named recovery paths, cross-step artifact references, housekeeping). Any consistent closure clears the suggestion; this one is consistent and stronger than the proposed wording.

## Findings

### A13 [suggestion]
**Certificate**: C18 (Assumption test: D1's row-content claim for `design-document-rules.md:289`)
**Target**: Decision D1 (completed per gate A10) — the new parenthetical describing the dsc-ai-tell catalogue row
**Challenge**: The A10 fix describes the row as "naming negative parallelism, Title Case, hyphenated pairs, and curly quotes among its patterns". The live row enumerates seven patterns — negative parallelism, Title Case H2+ headings, persuasive authority tropes, hyphenated-pair clusters at 3+, fragmented-header chains, the trailing-negation "X, not just Y" variant, and inflated-abstraction labels — and `grep -n "curly" .claude/workflow/design-document-rules.md` returns nothing: no curly-quotes mention exists anywhere in that file. The accurate fourth removed-pattern mention in the row is the trailing-negation variant (iter2's C15 described the row correctly). Consequence is bounded: the row is routed for update regardless and the misnamed pattern changes no action — the curly-quotes rule's real consumers live elsewhere in D1's list — but the false clause propagates into the derived design if the parenthetical is copied.
**Evidence**: `design-document-rules.md:289` (the seven-pattern enumeration); grep for `curly` over the file (zero hits).
**Proposed fix**: In D1's parenthetical, replace "curly quotes" with "the trailing-negation variant" (or drop the fourth item).

## Evidence base

**Assumption tests**

#### C18 Assumption test: the dsc-ai-tell catalogue row names curly quotes
- **Claim**: D1 (completed per gate A10) — `design-document-rules.md:289` is "the dsc-ai-tell catalogue row naming negative parallelism, Title Case, hyphenated pairs, and curly quotes among its patterns".
- **Stress scenario**: read the row and grep the file for `curly`.
- **Result**: the row's seven named patterns are negative parallelism, Title Case H2+ headings, persuasive authority tropes, hyphenated-pair clusters at 3+, fragmented-header chains, the trailing-negation intensifier variant, and inflated-abstraction labels. `curly` appears nowhere in `design-document-rules.md`. Three of D1's four claimed names are in the row; "curly quotes" is not.
- **Verdict**: FAILS — one invented pattern name in the row description; routing and action inventory unaffected.
