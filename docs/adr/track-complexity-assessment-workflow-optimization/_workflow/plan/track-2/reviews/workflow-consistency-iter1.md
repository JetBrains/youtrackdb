<!--MANIFEST
dimension: workflow-consistency
iter: 1
findings: 2
evidence_base: {certs: 0}
cert_index: []
flags: []
index:
  - {id: WC1, sev: Minor, anchor: "### WC1 ", loc: "conventions-execution.md:525", cert: n/a, basis: "baseline-pool count 4 drifts from the three-always-on + concurrency-on-category framing used everywhere else in Track 2"}
  - {id: WC2, sev: Minor, anchor: "### WC2 ", loc: "create-final-design.md:471", cert: n/a, basis: "section heading retains dropped 'Minimal tier' vocabulary after surrounding prose re-keyed to complexity axes; anchor resolves so only the label is stale"}
-->

## Findings

### WC1 [Minor] conventions-execution.md baseline-pool count drifts from the three-always-on framing

- **File:** `docs/adr/track-complexity-assessment-workflow-optimization/_workflow/staged-workflow/.claude/workflow/conventions-execution.md` (line 524-530)
- **Axis:** cross-file rule restatement
- **Cost:** coarse pool-accounting summary drifts from the canonical baseline framing; a reader reconciling the two files sees "4 baseline" against "three always-on."
- **Issue:** Line 525 still reads "the reviewer pool across both tiers is **4 baseline** + up to 6 conditional + up to 6 workflow-review." The split/merge keeps the baseline *roster* at four members (`review-code-quality`, `review-bugs`, `review-concurrency`, `review-test-quality`), so the literal count 4 is defensible. But every other Track-2 site now frames the baseline as **three always-on** members joined by `review-concurrency` only when the `concurrency` category is present — `review-agent-selection.md` §Baseline ("The first three are always-on; `review-concurrency` joins them whenever the `concurrency` category is present", line 37-39; footnote line 104 "three always-on"), `code-review-protocol.md` ("the full baseline group" with `review-concurrency` parenthetically category-gated, line 30-34), and `track-code-review.md` ("`review-bugs` (always), `review-concurrency` (when the `concurrency` category is present)"). The Referent — the baseline-group definition in `review-agent-selection.md` §Baseline, which `conventions-execution.md` §2.4 points at via `code-review-protocol.md` — now distinguishes always-on from category-gated members, a distinction the bare "4 baseline" count erases. The old "4 baseline" was four always-on agents; under the new roster only three are always-on. This is a leftover whole-roster count, not a broken reference (nothing fails to resolve).
- **Suggestion:** Soften to "4 baseline agents (3 always-on plus `review-concurrency` on the `concurrency` category)" or drop the literal count to match the canonical framing, so the §2.4 pool summary and the §Baseline definition tell one story. Low urgency: the count is a parenthetical accounting note, not a control input.

### WC2 [Minor] create-final-design.md verdict-fold heading retains dropped "Minimal tier" vocabulary

- **File:** `docs/adr/track-complexity-assessment-workflow-optimization/_workflow/staged-workflow/.claude/workflow/prompts/create-final-design.md` (line 471; referrers at 119, 330, 424)
- **Axis:** glossary and term consistency
- **Cost:** stale vocabulary on a heading the re-keyed prose points at; the anchor still resolves, so behavior is unaffected, but a reader meets "Minimal tier" inside a prompt whose carrier model no longer has tiers.
- **Issue:** Step 2 re-derived the whole carrier table and verdict-fold prose off the dropped whole-change `tier` model onto the two complexity axes (`design_gate` + `∃ track ≥ medium`). The body now says the fold runs "whenever no `adr.md` exists (every track reconciled `low`)" and the per-artifact tags read `design_gate=yes` / `∃ track ≥ medium`. But the section heading at line 471 is still `### Minimal tier: PR-description verdict fold`, and the three internal cross-references (lines 119, 330, 424) point at it by its literal title `§"Minimal tier: PR-description verdict fold"`. The Referent resolves — heading and referrers are byte-consistent with each other — so this is not a broken anchor; it is term drift. "Minimal tier" was the old tier-model name for the all-`low` no-ADR case, and it is the one surviving instance of that dropped vocabulary in this prompt's re-keyed prose. (The TOC summary at line 472, by contrast, was already re-keyed to "When no ADR exists …".)
- **Suggestion:** Rename the heading to a complexity-axis term, e.g. `### No-ADR verdict fold: PR description` or `### All-low verdict fold: PR description`, and update the three `§"Minimal tier: …"` cross-references in the same edit so the title stays self-consistent. Keep the rename inside this prompt — no other staged file references this heading by title (grep-confirmed).

## Evidence base
