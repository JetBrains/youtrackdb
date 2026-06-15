<!-- MANIFEST
findings: 1   severity: {Critical: 0, Recommended: 1, Minor: 0}
index:
  - {id: WI1, sev: Recommended, loc: ".claude/workflow/research.md:198-200", anchor: "### WI1 ", cert: C1, basis: "opacity rule's exclusive \"one sanctioned recap\" wording drops D3's second carve-out (Step-4 gate verdict recital), leaving a structured user-facing recital in an undefined allowed/forbidden state"}
evidence_base: {section: "## Evidence base", certs: 1, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
flags: [CONTRACT_OK]
-->

## Findings

### WI1 [Recommended] §Rules opacity bullet names one of D3's two sanctioned recaps; the Step-4 gate verdict recital is left in an undefined state

- **File:** `.claude/workflow/research.md` (staged copy, lines 198-200)
- **Axis:** conditional branch coverage (carve-out completeness)
- **Cost:** the agent has no rule text telling it the Step-4 adversarial-gate verdict recital is sanctioned, so on the iteration-cap path it may suppress the mandated "surface the still-open findings and the decision history" output as a leak — inconsistent behavior on a borderline utterance, not a hard strand.

The new §Rules opacity bullet closes with an exclusive singular: *"The one sanctioned structured recap is the Phase-1 transition findings summary (§Transition to Phase 1), and it too is plain language, not log quotes."* Read adversarially, "**the one** sanctioned structured recap" enumerates exactly one exception to the opacity rule.

Track `## Decision Log` D3 preserves **two** sanctioned user-facing recaps that must survive un-muzzled, and states the rule's purpose is so it "does not muzzle them" (plural):

1. the Phase-1 transition plain-language findings summary (§Transition to Phase 1) — named in the bullet; and
2. the `create-plan` Step-4 adversarial-gate verdict recital plus tier proposal, which "surface findings and blockers, not log structure."

Carve-out #2 is real and structured: `create-plan/SKILL.md` Step 4 part 1 mandates proposing the tier and matched categories to the user (lines 327-334), and Step 4 part 2's iteration-cap path mandates *"surface the still-open findings and the decision history and let the user accept the risk"* (lines 418-423). That is a structured, user-facing recital of blocker / should-fix findings — exactly the shape the opacity rule otherwise governs — and the Step-4 text carries no cross-reference back to the opacity rule marking it exempt. So the canonical rule's singular "one" silently drops D3's second carve-out, leaving the agent with no rule basis to decide whether the Step-4 recital is allowed.

The same singular framing is echoed at §Transition step-1 ("the one sanctioned structured recap per §Rules", lines 162-163). That instance is correct in its own context — the transition summary *is* that recap — but it inherits the §Rules framing, so the §Rules fix should keep §Transition consistent rather than be treated as a second independent defect.

- **Suggestion:** Soften the §Rules bullet's exclusivity to admit both D3 carve-outs. For example: *"The sanctioned structured recaps are the Phase-1 transition findings summary (§Transition to Phase 1) and the Step-4 adversarial-gate verdict-and-tier recital (`create-plan` §Step 4); both surface findings and blockers in plain language, not log quotes."* Keep §Transition step-1's "one sanctioned" wording aligned (e.g., "a sanctioned structured recap"). This states carve-out #2 explicitly rather than leaving the agent to infer it from "findings ≠ log structure."

## Evidence base

#### C1 D3 names two sanctioned recaps; §Rules carve-out names one — CONFIRMED

Complement / carve-out-completeness check. Verified directly against the staged review surface and the track file:

- §Rules opacity bullet (staged `research.md:194-200`) closes with the exclusive singular "The one sanctioned structured recap is the Phase-1 transition findings summary".
- Track `## Decision Log` D3 (track-1.md:40-44) enumerates two sanctioned user-facing recaps and frames the rule's purpose as not muzzling "them" (plural); recap #2 is the Step-4 adversarial-gate verdict recital and tier proposal.
- Carve-out #2 is a live, structured, user-facing instruction in the same staged change set: `create-plan/SKILL.md` Step 4 part 1 (lines 327-334, tier-and-categories proposal) and Step 4 part 2 iteration-cap path (lines 418-423, "surface the still-open findings and the decision history"). Neither cross-references the opacity rule as an exemption.
- Conclusion: the canonical rule's home for the carve-out set is incomplete — one of two exceptions is named, the other is left implicit, producing a borderline-utterance ambiguity. Confirmed gap.
