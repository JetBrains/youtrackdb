<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
scope: D10 fix deltas only (verdict pass on the iter4 A14/A15/A16 applications; D1-D9 cleared at iterations 1-3, D10 substance cleared at iteration 4)
verdicts: {A14: VERIFIED, A15: VERIFIED, A16: VERIFIED}
index:
  - {id: A17, sev: suggestion, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:57, anchor: "### A17 ", cert: C26, basis: "the A15 composition clause names (writing-style reviewer, readability auditor) as the two enforcement agents reading the ranked rule, but both hold the lead-rule half of the slot; the D9 link half — the demand that drives C20's oscillation — belongs to comprehension-review (the navigability owner), which the parenthetical leaves outside the reads-one-ranked-rule set"}
evidence_base: {section: "## Evidence base", certs: 4, matches: 3}
cert_index:
  - {id: C24, verdict: HOLDS, anchor: "#### C24 "}
  - {id: C25, verdict: HOLDS, anchor: "#### C25 "}
  - {id: C26, verdict: WEAK, anchor: "#### C26 "}
  - {id: C27, verdict: HOLDS, anchor: "#### C27 "}
overall: PASS
flags: [CONTRACT_OK]
-->

## Verdicts

### A14 — VERIFIED
The reattribution lands and the live tree backs every leg. (1) D4 touches no `review-workflow-writing-style.md` line: `grep -n "TL;DR"` over that file exits 1. (2) D4's `house-style.md` hits sit at :284, :377, :381, :395 — all outside D10's three sites (§ BLUF lead :22-38, § Orientation :54-76, the self-check BLUF item :413), so "connects structurally through the Summary carrier but touches none of D10's three sites at the line level" is exact. (3) The D1/D7 side holds: three of the six removal targets live in § Banned sentence patterns — negative parallelism (`house-style.md:100`), roundabout negation (:101), closing phrases (:103); D7's remove list deletes self-check item 1 wholesale (:405, both patterns) and trims items 2/4/5, renumbering the list that carries the BLUF item (:413); D1's reviewer-file removals (:29 banned-patterns bullet, :34 Title Case, :71 formulaic phrasings, :188 Recommended block) interleave the BLUF criteria (:28, :78-85). (4) "Exactly" is gone; the new Scope clause states the carrier-not-whole-scope relation the iter4 fix asked for. One non-blocking residual, noted without a finding: the appositive "the same regions D10's two rules and exemplar pair land in" binds three rewrite claims, and the § Banned sentence patterns one is a same-file overlap only — D10 lands nothing in that section. The rationale's conclusion survives on the two exact legs (self-check, reviewer criteria), and a rejection rationale seeds no acceptance site, so this stays a note.

### A15 — VERIFIED
The ordering clause lands with the substance the finding asked for: the self-contained plain claim comes first, the backward link rides as a subordinate clause or the following sentence, and the link never substitutes for the claim. That is the fused-form target C20 said no recorded rule named, and it composes cleanly with D9's verbatim-heading link hardening (a subordinate or following-sentence link can still name the neighbor's heading verbatim). The clause's closing parenthetical, however, names the wrong agent pair for the slot it ranks — raised as A17 (suggestion), the only defect a fix delta introduced.

### A16 — VERIFIED
The third acceptance site is now anchored by name: "the self-check item that requires the first paragraph to state the decision or symptom directly (the BLUF item ...)" — the quoted requirement matches the live item verbatim (`house-style.md:413`: "**BLUF.** The first paragraph states the decision or symptom directly."), and the entry records why the ordinal was dropped (D1/D7 renumber the list). A derived design copying this anchor routes correctly after the renumbering.

## Findings

### A17 [suggestion]
**Certificate**: C26 (Fix-delta verification: the A15 clause's enforcement-pair parenthetical vs the agent roster)
**Target**: Decision D10 — the composition clause's closing parenthetical, "so the two enforcement agents (writing-style reviewer, readability auditor) read one ranked rule"
**Challenge**: Both named agents hold the same half of the slot. The readability auditor (in-loop, via its house-style `§`-citation obligation per D6) and the writing-style reviewer (Phase C, BLUF criteria at `review-workflow-writing-style.md:28,78-85`) both enforce the D10 lead rules. The agent holding the other half — D9's "opener names what the section builds on" link demand, whose literal first-sentence reading drives C20's oscillation — is comprehension-review, the navigability and dip-in owner (`comprehension-review.md:35`: navigability is a whole-doc property "so they are yours"; C20 step 3 attributes the link flag to a comprehension round). As recorded, a design copying the parenthetical wires the ranked rule into the two house-style enforcers and leaves the one agent whose demand creates the conflict outside the reads-one-ranked-rule set. Bounded the same way A15 was: the ranked rule also binds the author, who applies the fused form regardless of which reviewer flags what, so routing recovers at the cost of the one extra round the composition rule exists to avoid.
**Evidence**: `research-log.md:57` (the parenthetical); `comprehension-review.md:35` (navigability ownership); `readability-auditor.md` (zero case-insensitive hits for "lead"/"BLUF" today — its lead-rule enforcement arrives via the house-style citation channel, not a named criterion); iter4 C20 steps 1-4.
**Proposed fix**: Swap the parenthetical to the pair that actually shares the slot — "(readability auditor, comprehension-review; the writing-style reviewer on Phase-C surfaces)" — or reuse D9's own formula, "any enforcement agent reads the ranked rule."

## Evidence base

**Fix-delta verifications**

#### C24 Fix-delta verification: A14's reattribution against the live tree
- **Claim under test**: The revised rejection's three overlap legs and the D4 exclusion clause.
- **Verification trace**:
  1. `grep -n "TL;DR" .claude/agents/review-workflow-writing-style.md` → exit 1 (zero hits): D4 touches no line of that file.
  2. `grep -n "TL;DR" .claude/output-styles/house-style.md` → :284, :377, :381, :395. Section map: § BLUF lead :22, § Orientation :54, § Banned sentence patterns :96, self-check list :405-414. All four D4 hits fall outside D10's three sites.
  3. § Banned sentence patterns contains negative parallelism (:100), roundabout negation (:101), closing phrases (:103) — D1/D7 remove targets; the D1/D7 edits rewrite that section.
  4. Self-check item 1 (:405) is "Negative parallelism and roundabout negation" — deleted wholesale by D7's remove list; items 2/4/5 lose removed clauses; the BLUF item sits at :413 today, so the list renumbers under this change's own edits.
  5. Reviewer-file interleave: removal targets at :29/:34/:71/:188 sit beside the BLUF bullet (:28) and the § BLUF lead criteria block (:78-85).
- **Verdict**: HOLDS — with the appositive residual noted in the A14 verdict (the § Banned sentence patterns leg is same-file, not same-region; the conclusion stands on the two exact legs).

#### C25 Fix-delta verification: A15's ordering clause content
- **Claim under test**: The composition clause states the fused form as the target and removes the unranked-pair gap C20 constructed.
- **Verification trace**: The clause fixes an order (claim first), a position for the link (subordinate clause of the claim, or the following sentence), and a prohibition (the link never substitutes for the claim). Replaying C20: at step 3 the author now has a recorded rule licensing the link in second position, so step 4's auditor flag never fires — the oscillation closes. The clause is compatible with D9's link-staleness hardening: a second-position link still names the neighbor's heading verbatim.
- **Verdict**: HOLDS.

#### C26 Fix-delta verification: the enforcement-pair parenthetical vs the agent roster
- **Claim under test**: "the two enforcement agents (writing-style reviewer, readability auditor) read one ranked rule" names the agents that share the section-opening slot.
- **Verification trace**:
  1. `comprehension-review.md:35`: navigability is a whole-doc check assigned to comprehension-review — the home of D9's dip-in link demand, per D6's recast and C20's construction.
  2. Case-insensitive grep for "lead" / "BLUF" over `readability-auditor.md` → zero hits: the auditor's lead-rule enforcement is via the house-style citation channel (D6's `§`-citation obligation), not a named criterion, but it is the in-loop lead enforcer.
  3. `review-workflow-writing-style.md:28,78-85`: the Phase-C BLUF criteria — the second lead-rule enforcer.
  4. Both parenthetical members therefore hold the lead-rule half; no named member holds the D9 link half. The pair that can thrash is (readability auditor, comprehension-review).
- **Verdict**: WEAK — the ordering rule itself is sound (C25), but the recorded pair misdirects the design's enforcement wiring; sourced as A17.

#### C27 Fix-delta verification: A16's name anchor against the live self-check item
- **Claim under test**: D10's third site, "the self-check item that requires the first paragraph to state the decision or symptom directly (the BLUF item ...)", identifies a real, uniquely-named item.
- **Verification trace**: `house-style.md:413` — "9. **BLUF.** The first paragraph states the decision or symptom directly. Section openers don't restate the section heading." The name "BLUF" appears on exactly one self-check item; the quoted requirement matches its first sentence. The entry's stated reason for dropping the ordinal (D1/D7 renumber the list) is C24 trace step 4.
- **Verdict**: HOLDS.
