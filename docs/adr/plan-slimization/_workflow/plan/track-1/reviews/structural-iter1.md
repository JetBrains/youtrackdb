<!-- MANIFEST
findings: 2   severity: {blocker: 1, should-fix: 0, suggestion: 1}
index:
  - {id: S1, sev: blocker,    loc: "implementation-plan.md §Invariants (plan invariant S2) + plan/track-1.md §Validation and Acceptance + plan/track-2.md §Plan of Work step 7", anchor: "### S1 ", cert: "", basis: "plan invariant S2's two-read-site rule contradicts the Phase-4 verdict fold that D10/D16/D17 and Track 2 step 7 mandate"}
  - {id: S2, sev: suggestion, loc: "implementation-plan.md §Component Map", anchor: "### S2 ", cert: "", basis: "two Track-1 in-scope files absent from the Component Map and the mechanical-check script node carries no intent bullet"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### S1 [blocker]
**Location**: `implementation-plan.md` `#### Invariants` (plan invariant S2); `plan/track-1.md` `## Validation and Acceptance` (the "S2 read points" bullet); `plan/track-2.md` `## Plan of Work` step 7 and its closing invariants line
**Issue**: Plan invariant S2 enumerates exactly two log read sites ("read only at Step 4a/4b authoring and by the Phase-2 consistency cross-check"), and Track 1's acceptance bullet hardens that into a checked assertion: "the staged docs name exactly two log read sites ... and no staged text adds a third." Track 2 step 7 then mandates staged text in `prompts/create-final-design.md` that reads the log's resolved gate records for the Phase-4 verdict fold — a third read site that D10, D16, and the design (Part 3, D17: "the log's gate records remain the verdict carrier the Phase-4 consumers read"; Part 7's fold step) all require. As written, the Track 2 implementer cannot satisfy both the step and the invariant: landing the fold falsifies the "exactly two sites" claim Track 1 ships in the staged docs, while dropping it loses the audit trail when the cleanup deletes the log. Track 2's own invariants line papers over the gap with an "execution-side read point" qualifier that the plan-level S2 does not carry, so the three statements of the invariant disagree on scope.
**Proposed fix**: Reword plan invariant S2 to scope the two-site rule to decision-content reads and name the Phase-4 fold as a sanctioned verdict-only read, e.g. "read for decision seeding only at Step 4a/4b authoring and the Phase-2 consistency cross-check; the Phase-4 fold reads the log's resolved gate verdicts, never decision content." Mirror the carve-out in Track 1's acceptance bullet ("no staged text adds a third decision-read site") and align Track 2's invariants-line restatement with the chosen wording. The matching `design.md` Part 2 sentence ("The log may be read in exactly two places") is frozen; defer that half to Phase 4 reconciliation.
**Classification**: design-decision
**Justification**: §Classification rules — "Track contradictions — Track 1 assumes X, Track 3 assumes not-X. Which is right is a design call"; the fix rewords a load-bearing invariant, so the user picks the resolution.

### S2 [suggestion]
**Location**: `implementation-plan.md` `#### Component Map` (Mermaid T1 subgraph and the annotated bullet list)
**Issue**: Two Track-1 in-scope files are absent from the Component Map entirely: `mid-phase-handoff.md` (D15's multi-session queue block; track-1 in-scope item 6) and `risk-tagging.md` (D4's shared-source note; item 7). Every other in-scope file maps to a node or a grouped bullet, so a reader cross-checking the map against the track's `## Interfaces and Dependencies` list finds these two unaccounted for. Separately, the MECH node (`design-mechanical-checks.py`) appears in the diagram but has no intent bullet in the annotated list — the only diagram component without a "what changes and why" annotation (the D11 edit is described only in `### Constraints` and the DR).
**Proposed fix**: Add the two files to the T1 subgraph or fold them into an existing node label, and give each a one-line bullet: `mid-phase-handoff.md` gains the D15 queue block for multi-session holds; `risk-tagging.md` gains the D4 note naming the HIGH-category list as Gate 1's shared source. Add a one-line MECH bullet citing D11's backward-compatible footer-spelling edit plus the new stub-plan fixture.
**Classification**: mechanical
**Justification**: §Classification rules — a fix that is "a single unambiguous edit that doesn't change plan intent"; the bullet content is dictated by D4, D15, and D11.

## Evidence base

No certificates: this review reads no codebase, per the structural-review
contract. Findings cite plan, track-file, and design text directly.
