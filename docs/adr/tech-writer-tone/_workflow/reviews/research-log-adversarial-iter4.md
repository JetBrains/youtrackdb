<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
scope: D10 only (entry appended 2026-07-02T07:55Z; D1-D9 cleared at iterations 1-3)
index:
  - {id: A14, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:58, anchor: "### A14 ", cert: C19, basis: "D10's deferral rejection attributes the site overlap to the D4 rename, but D4 touches no review-workflow-writing-style.md line (zero TL;DR hits) and none of D10's three house-style.md sites; the real co-writers are D1/D7, and 'the Summary block is exactly the lead the rules govern' overstates the rules' scope"}
  - {id: A15, sev: suggestion, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:57, anchor: "### A15 ", cert: C20, basis: "D10's lead rules and D9's connect-backward opener share the section-opening slot under two different enforcement agents with no composition rule — the unranked-pair class D9's precedence clause exists for"}
  - {id: A16, sev: suggestion, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:57, anchor: "### A16 ", cert: C21, basis: "acceptance site 'self-check #9' is an ordinal into a list D1/D7 renumber in this same change (item 1 removed wholesale); anchor by the item's BLUF name"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 2}
cert_index:
  - {id: C19, verdict: WEAK, anchor: "#### C19 "}
  - {id: C20, verdict: CONSTRUCTIBLE, anchor: "#### C20 "}
  - {id: C21, verdict: FRAGILE, anchor: "#### C21 "}
  - {id: C22, verdict: HOLDS, anchor: "#### C22 "}
  - {id: C23, verdict: HOLDS, anchor: "#### C23 "}
overall: NEEDS REVISION
flags: [CONTRACT_OK]
-->

## Findings

### A14 [should-fix]
**Certificate**: C19 (Challenge: Decision D10 — deferral rejection and land-now rationale)
**Target**: Decision D10 — the "Deferring YTDB-1163 to its own branch" rejection and the Why's same-sites echo
**Challenge**: The deferral rejection rests on a site-overlap claim the tree falsifies. "The D4 rename rewrites the same `house-style.md` and reviewer-criteria lines": grep for `TL;DR` over `review-workflow-writing-style.md` returns nothing, so the D4 rename touches no line of that file; within `house-style.md` the rename's targets (:284 exception list, :377 why-before-what exclusion, :381 Navigability, :395 sibling consolidation) are disjoint from all three sites D10 names (§ BLUF lead :22-38, § Orientation :54-76, self-check #9 :413). The overlap that actually funds the second-review-round argument belongs to D1/D7: D1 removes pattern lines at `review-workflow-writing-style.md:29/:34/:71/:188` — beside the :28 BLUF bullet and the :78-81 criteria block — and D7's removals rewrite the very self-check list D10's third site lives in (item 1 deleted wholesale). The Why's companion clause, "the Summary block is exactly the lead the rules govern", overstates the same way: `review-workflow-writing-style.md:79` applies the lead rule to every section of workflow markdown (agent bodies, skill bodies), and `house-style.md:349` applies the BLUF rule alone to issue/PR surfaces — none of those carries a `### Summary` block, so the D4 carrier makes rule (2)'s delete-the-lead test structural only on the design-doc surface. Direction survives on two independent legs the entry already holds: the initial request folds YTDB-1163 explicitly (decomposed ask 6), and at branch granularity both files are rewritten anyway.
**Evidence**: grep outputs in C19; `research-log.md:21` (D1 consumer list), `:33` (D4 site list — no `review-workflow-writing-style.md` entry), `:45` (D7 remove list).
**Proposed fix**: Reattribute the overlap — e.g. "the D1/D7 removals rewrite the same self-check list and the adjacent reviewer-criteria lines, and D4's `### Summary` sub-heading gives rule (2)'s delete-the-lead test a structural boundary on the design-doc surface" — and drop "exactly" (the Summary block is the design-doc instance of the lead the rules govern, not its whole scope).

### A15 [suggestion]
**Certificate**: C20 (Violation scenario: lead-slot thrash between D10's rules and D9's connect-backward opener)
**Target**: Decision D10 — missing composition rule against D9's adapted link rule
**Challenge**: D9 earned its precedence clause because two enforcement agents holding unranked targets thrash the authoring loop (gate A5). D10 adds two lead rules into the same section-opening slot D9's adapted rule occupies ("opener names what the section builds on"), enforced by a different agent: comprehension-review owns navigability and the dip-in links (D6, D9), the readability-auditor owns the house-style lead rules. No A5-grade contradiction exists — a self-contained claim and a backward link compose inside § BLUF lead's 3-5-sentence budget — but no recorded rule says the fused form is the target, and a literal reading of "opener" as "first sentence" against the hardened conclusion-first criterion yields a one-round oscillation per affected section (C20's trace).
**Evidence**: `research-log.md:53` (D9's precedence clause and the loop-thrash scenario it cites); `review-workflow-writing-style.md:79`; D6's axis split at `research-log.md:41`.
**Proposed fix**: One ordering clause in D10 or D9's precedence list: the self-contained claim takes the lead's first sentence; the backward link follows within the lead (or opens the body, naming the neighbor's heading verbatim per D9's hardening); satisfying D9's link rule never licenses a first sentence that fails rule (1).

### A16 [suggestion]
**Certificate**: C21 (Assumption test: "self-check #9" is a stable acceptance anchor)
**Target**: Decision D10 — the third acceptance site, "self-check #9"
**Challenge**: The ordinal is anchored into a list this same change renumbers. D7's remove list deletes both patterns of self-check item 1 ("Negative parallelism and roundabout negation"), so the item disappears wholesale, and items 2/4/5 lose their removed clauses (closing phrases; hyphenated pairs and curly quotes; sentence-case headings). After the D1/D7 edits, today's #9 is no longer the ninth item; a derived design copying "self-check #9" routes the implementer or reviewer to the wrong item (today's #10, the delete-redundant-paragraphs check) or forces stale numbering. Routing recovers because the item is named "BLUF" wherever cited, which caps this at suggestion.
**Evidence**: `house-style.md:405-414` (the ten-item list; #1's two patterns both sit in D7's remove list at `research-log.md:45`); `house-style.md:413` (#9 = BLUF, real today).
**Proposed fix**: Anchor by name — "the BLUF self-check item (#9 pre-change)" — in D10 and in the derived design's acceptance list.

## Evidence base

**Decision challenges**

#### C19 Challenge: Decision D10 — deferral rejection rests on a false site-overlap attribution
- **Chosen approach**: Land YTDB-1163's two BLUF-hardening rules in this change; deferral rejected because "the D4 rename rewrites the same `house-style.md` and reviewer-criteria lines, so a second branch pays a second review round over the same sites".
- **Best rejected alternative**: Deferring YTDB-1163 to its own branch.
- **Counterargument trace**:
  1. `grep -n "TL;DR" .claude/agents/review-workflow-writing-style.md` exits 1 (zero hits): the D4 rename never touches the file carrying the "reviewer-criteria lines" D10 names as a site.
  2. `grep -n "TL;DR" .claude/output-styles/house-style.md` hits :284, :377, :381, :395 — none inside § BLUF lead (:22-38), § Orientation (:54-76), or self-check #9 (:413), the three house-style sites D10 names.
  3. The clause therefore fails at both named targets. The real co-writers of D10's sites are D1/D7: D1's consumer list (`research-log.md:21`) includes `review-workflow-writing-style.md` (pattern removals at :29/:34/:71/:188, beside the :28 BLUF bullet and :78-81 criteria block, per the iter3 A11 verdict), and D7's removals rewrite the self-check list containing #9.
  4. Deferral does cost a second review round over the same two files — via D1/D7, not the D4 rename — and the second rejected alternative independently suffices (the initial request's ask 6 folds YTDB-1163's requirements into scope).
- **Codebase evidence**: grep outputs above; `research-log.md:21,33,45`; D4's site list carries no `review-workflow-writing-style.md` entry.
- **Survival test**: WEAK — the land-now direction survives on the user mandate and the branch-level file overlap; the recorded causal attribution fails against the tree and will seed the design if copied.

**Violation scenarios**

#### C20 Violation scenario: lead-slot oscillation between D10's hardened lead rules and D9's connect-backward opener
- **Invariant claim**: Enforcement agents in the dual-clean loop hold non-contradictory targets for every textual slot (the standard D9's precedence clause established after gate A5).
- **Violation construction**:
  1. Start state: post-change roster — the readability-auditor enforces the house-style lead rules (rule 1 self-contained claim; the conclusion-first criterion at `review-workflow-writing-style.md:79` hardened per D10); comprehension-review owns navigability including D9's adapted links.
  2. The author writes a `### Summary` whose first sentence is the plain claim, no backward link.
  3. A comprehension round flags the missing builds-on link (D9's dip-in navigation rationale); the author prepends "This builds on the commit-window seam (§ …)" as the opener.
  4. The next auditor round flags the lead: the first sentence is now a link, not the conclusion.
  5. Observable consequence: one oscillation per affected section. Bounded — both rules fit inside § BLUF lead's 3-5-sentence budget once fused — but no recorded rule names the fused form as the target.
- **Feasibility**: CONSTRUCTIBLE but bounded — a composition rule exists and one clause states it; suggestion-grade hardening, matching the A9/C12 precedent.

**Assumption tests**

#### C21 Assumption test: "self-check #9" is a stable acceptance anchor
- **Claim**: D10 — the third acceptance site is "self-check #9".
- **Stress scenario**: Apply D7's own remove list to the live self-check list: item 1 ("Negative parallelism and roundabout negation") disappears wholesale; items 2/4/5 lose their removed clauses. The list renumbers, or keeps stale numbering.
- **Code evidence**: `house-style.md:405-414`; item #1's two patterns both appear in D7's remove list (`research-log.md:45`); #9 = BLUF is real today at `house-style.md:413`.
- **Verdict**: FRAGILE — the ordinal will not survive the branch's own edits; the item stays unambiguous by its "BLUF" name, so routing recovers.

#### C22 Assumption test: the recorded YTDB-1163 content and acceptance sites match the live issue and tree
- **Claim**: D10's two rules and acceptance sites are "per the issue", and the sites are real.
- **Stress scenario**: Re-fetch the issue; verify each site anchor against the live tree.
- **Code evidence**: Live YTDB-1163 (fetched this review; Bug/Major, dev-workflow, unresolved, updated 2026-07-01): proposed changes 1 and 2 and the four acceptance bullets match D10 and the 08:05Z record in substance. Sites verified live: § BLUF lead (`house-style.md:22`), § Orientation (`:54`), self-check #9 (`:413`), orientation exemplar (`:68-74`), `review-workflow-writing-style.md` BLUF criteria (`:28`, `:78-81`). Two faithful compressions noted, neither a drift: the issue writes "§ BLUF lead (or § Orientation)" — an either-site disjunction D10 renders as "§ BLUF lead / § Orientation", harmless since the exemplar pair is pinned beside the § Orientation exemplar regardless; and the issue's fallback pointer (§ Why-before-what, `house-style.md:373`) is a design/ADR-only shape rule while § BLUF lead also governs issue/PR surfaces (`house-style.md:349`) — the fallback's substance, "lead with motivation", is register-safe everywhere, so the wrinkle is cosmetic.
- **Verdict**: HOLDS.

#### C23 Assumption test: rule coherence with the D7 keep list, the D4 shape, the D9 precedence clause; context-budget impact
- **Claim**: The two new rules contradict nothing kept, fit the D4 shape, and fit the budget.
- **Stress scenario**: Test each pair. (1) D7's keep list already names "BLUF rules hardened per YTDB-1163 alongside the D4 `### Summary` shape" — D10 is the elaboration D7 anticipated, and no removed rule overlaps the self-containment or anaphor test. (2) Rule (1)'s gloss-in-one-clause move restates kept § Orientation's gloss-at-first-use for the lead position; the motivation fallback matches § BLUF lead's "symptom" option and § Why-before-what. (3) Rule (2)'s delete-the-lead test gains a structural boundary from D4's sub-heading form (deleting a `**TL;DR.**` bold-prefix leaves a fuzzy edge; deleting a `### Summary` block is exact), and D8 already pins "`### Summary` blocks stay plain-claim and self-contained (YTDB-1163 rule doing double duty)" — mutually consistent. (4) D9: no contradiction; the one unranked composition is C20/A15. Budget: additions are two rule clauses, an anaphor failure example, and one exemplar pair (~15 lines) against D1/D7's removals (six rule sections plus self-check trims, ~60 lines) — a net shrink of the read-per-authoring-session surface. The § Orientation placement rides into the always-on subset `house-conversation.md` reuses, but the additions are lead-specific and near-vacuous for terminal replies, and the same change removes far more from that subset.
- **Verdict**: HOLDS — with the C20 residual recorded as A15.
