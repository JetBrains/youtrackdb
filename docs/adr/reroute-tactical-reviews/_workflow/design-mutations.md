# Design mutation log — reroute-tactical-reviews (YTDB-883)

## Mutation 1 — 2026-06-06 — phase1-creation (design.md)

**Diff summary**: Completed the design-first Step 4a resume. Folded the transient `decision-log.md` rationale, alternatives, and risks into the matching `design.md` complex-topic narratives as "why this choice over X" prose — D1 router-model versus orchestrator partial-fetch, D2 persisted file versus inline structured return, D4 the tighten-prompts and pull-body-on-doubt alternatives, D6 the update-the-standalone-skills alternative, D7 the path-conditional-alone-without-staging alternative — then deleted `decision-log.md` (the formal `#### D<n>` / `S<n>` records derive from the now-self-sufficient design in Step 4b). Ran the `phase1-creation` review: adversarial first, then cold-read.

**Mechanical checks** (target=design): PASS (0 findings)
**Adversarial** (design-scoped, Phase 1): PASS after 1 fix iteration — 2 blockers + 6 should-fix resolved; 1 suggestion rejected, 1 deferred, 1 resolved
**Cold-read** (scope: whole-doc): PASS after 1 fix iteration — 2 should-fix resolved, 1 suggestion deferred

**Findings**:
- blocker (A1, RESOLVED): "Per-dimension addressing" framed per-dimension `### BC<n>` IDs as already present in reviewer output, but today reviewers emit un-numbered `#### Critical`-style buckets and IDs are minted by the orchestrator at synthesis (`finding-synthesis-recipe.md`); the reviewer-side self-assign-IDs rule was missing. Fix: stated that ID assignment moves from the merge step to the reviewer, which self-assigns `<PREFIX><n>` and writes one anchored body per item, continuing from a per-dimension high-water-mark the orchestrator hands back (the gate-check's existing `{findings_under_recheck}` mechanism, applied at the initial review too).
- blocker (A2, RESOLVED): the validation grep was the bare `^### `, which over-counts non-finding `### ` prose headers and misfires `CONTRACT_VIOLATION` (S4) on compliant files. Fix: ID-anchored regex `grep -cE '^### [A-Z]{2,}[0-9]+ '`, citing the workflow-sha anchored-literal precedent.
- should-fix (A3, RESOLVED): added the reserved-namespace rule — under `## Findings` the `### <ID> ` shape is reserved for finding anchors; reasoning prose lives in `## Evidence base` or the finding body.
- should-fix (A4, RESOLVED): softened "no separate dedup pass is needed"; the implementer reconciles cross-dimension framings (same concern → one edit, distinct concerns at one line → separate edits), and an added Routing edge case handles per-finding severity routing plus the accepted emergent-severity blind spot.
- should-fix (A5, RESOLVED): scoped the resume payoff to the completed-review boundary and stated it does not override `track-review.md`'s re-run-from-iteration-1 rule for an interrupted Phase A iteration.
- should-fix (A6, RESOLVED): Severity no longer claims dropping the downgrade OVERRIDE is free; it names the over-severe-blocker budget cost and explains the routing change removes the orchestrator-side pressure at its root.
- should-fix (A7, RESOLVED): S1 restated with the steady-state/transient qualifier so the contested-finding drill-down is the one bounded exception, not a contradiction.
- should-fix (A8, RESOLVED): corrected the path-supply site to the dispatch sites (`track-code-review.md`, `step-implementation.md`), not `review-agent-selection.md`; stated the no-path branch stays byte-for-byte today's inline format.
- should-fix (cold-read audience-fit, RESOLVED): the Overview named no reader; added a prerequisite sentence naming the workflow engineer and the assumed-familiarity floor.
- should-fix (cold-read glossary-introduction, RESOLVED): same sentence declares Phase A/B/C, the synthesis recipe, §1.7, I6, and the warning gate as prerequisites and inline-glosses the synthesis recipe and I6.
- suggestion (A9, REJECTED): the claim that §1.7(h) forward-only forces this branch's own agent edits to land live was a misread; §1.7(h) is a one-time observation about the original §1.7-introducing plan, not a branch generalizing two prefixes to three. The branch self-stages later-track agent edits via §1.7(d) reads-precedence once Track 1 lands the 3-prefix rule. Reviewer withdrew the challenge on re-review.
- suggestion (A10, DEFERRED to Phase A): `plan/track-N/reviews/` versus `plan/track-N-reviews/` and the `plan/*` glob caution. The chosen shape works (consumers glob `plan/track-*.md`); recorded for the planner/implementer to revisit.
- suggestion (A11, RESOLVED): softened the "~120-180K cache-create tokens" teardown figure to an estimate.
- suggestion (cold-read References-footer labels, DEFERRED to Step 4b): footers cite bare `D2`/`S4` codes; the formal records derive in Step 4b and will own their short labels.

**Iterations**: 2 of 3 (PASS — all blocker and should-fix findings cleared; 3 suggestions remain as recorded debt, 1 rejected)

## Mutation 2 — 2026-06-06 — structural-rewrite (design.md)

**Diff summary**: Two changes, both prompted by user review. (1) Fixed the two Workflow-section Mermaid diagrams: removed the parentheses from the sequence-diagram participant alias (`Reviewer (×N dims)` → `Reviewer xN dims`, the parser-breaking construct), and dropped the `##` markdown-heading syntax from the flowchart node (`partial-fetch ## Findings` → `partial-fetch Findings`). (2) Replaced every complex-topic section's bare-code `### References` footer (`- D-records: … / - Invariants: …`) with inline decision records — `**D<n>. <title>.** Alternatives: …; Rationale: …; Risk: …` — plus inline invariant statements `**S<n>.** <statement>`, per the user's "per-section inline records, no table" choice (resolves the concern that the deleted `decision-log.md` left the footer D/S codes unresolvable on disk). The `### References` heading is kept because `design-mechanical-checks.py` mandates it; each D record lives in exactly one section; the cross-cutting invariants S1 and S5 are restated in each section that implements them.

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (scope: whole-doc): PASS (0 blockers, 0 should-fix)

**Findings**:
- Mermaid: both diagrams parse after the fixes; no other participant alias or node label carries parser-breaking characters.
- Accuracy/coherence: all nine D-records and the S-restatements faithfully match their sections' prose; doc reads as a working whole.
- Redundancy (body "why this over X" prose vs footer record, five sections): KEEP-BOTH — the body is the connected explanatory walk required by house-style § Explanatory register; the footer record is the scannable canonical summary; complementary, not padding. Severity/D4 has the tightest overlap (the only defensible future-trim candidate) but is not a finding.
- Repeated invariants (S1 in 3 footers, S5 in 4): acceptable per-section self-containment, not a same-shape-sibling problem (that rule targets identical whole-section shapes, not a cross-cutting invariant one-liner).
- suggestion (DEFERRED to Step 4b): unify S1's three renderings (one full, two parenthetical short forms).
- suggestion (DEFERRED to Step 4b): D-record numbering is non-contiguous in document order (D-numbers follow decision order; Step 4b owns the formal records and can renumber if it wants document-order monotonicity).

**Iterations**: 1 of 3 (PASS — no blocker or should-fix findings; 2 suggestions recorded as Step-4b debt)

**Note — footer heading**: kept `### References` because the mechanical checker hard-codes it. A rename to `### Decisions & invariants` (to match the inline-records content) is recommended as workflow-level work under YTDB-1083, not as a one-off here — it would require editing `design-mechanical-checks.py` plus `house-style.md` / `design-document-rules.md` for repo-wide consistency. **Resolved: user agreed to defer the rename to YTDB-1083; this branch keeps `### References`. YTDB-1083's description was updated with the inline-records-as-canonical fix plus the footer rename.**

## Mutation 3 — 2026-06-06 — content-edit (design.md)

**Diff summary**: De-duplicated the repeated cross-cutting invariants that Mutation 2's cold-read flagged, at user request. Each invariant's full statement now lives once at its definitional home; the other footers carry a gist-plus-pointer reference instead of restating it. S1's full statement stays in the Routing by consumer footer; the Severity and Lifecycle footers reference it (`The no-bodies invariant, stated in full under Routing by consumer`). S5's full statement stays in the Coverage and exemptions footer; the Path-conditional, evidence-trail, and Staging footers reference it (`The coverage rule, stated in full under Coverage and exemptions`). D-records are unchanged (each already lived in exactly one section). Grep confirms the full S1 and S5 sentences now appear once each, with five references.

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (targeted confirm): PASS (0 blockers, 0 should-fix)

**Findings**:
- Reference resolution: both gist-plus-pointer forms read cleanly and resolve unambiguously to the home sections ("Routing by consumer…" leading-clause match; "Coverage and exemptions" verbatim), no heading collision.
- Self-containment: no citing section is too thin — each carries its own inline `exempt because…` / gist context and operates only on the invariant's name, not its full text.
- No new structural finding. The Mutation-2 "unify S1 wording" suggestion is resolved as a side effect (the two divergent short forms became pointers).

**Iterations**: 1 of 3 (PASS)

## Mutation 4 — 2026-06-06 — content-edit (design.md)

**Diff summary**: Fixed a Mermaid parse error in the Workflow section's `sequenceDiagram` (design.md line 117). The final message label `O->>O: route on flags; re-spawn implementer for STILL OPEN / REGRESSION` failed to parse because Mermaid's sequence-diagram grammar treats `;` as a statement separator: everything after the semicolon (`re-spawn implementer for STILL OPEN / REGRESSION`) was read as a new statement, parsed as an actor with no following arrow, and raised a parse error. Replaced the `; ` with `, ` so the clause stays one message label. This is the diagram Mutation 2 claimed to fix; Mutation 2 corrected the participant-alias parens and the flowchart heading but missed the semicolon. Verified against the real parser (mermaid@11.15.0, `mermaid.parse` under jsdom, no rendering): before the fix the sequenceDiagram FAILED with `Parse error on line 19 … got 'NEWLINE'` while the classDiagram and flowchart PASSED; after the fix all three blocks PASS. No other diagram label carries an unescaped `;`.

**Mechanical checks** (target=design): PASS (0 findings)
**Cold-read** (scope: bounded — Workflow + Class Design + Overview + Part 1 file-schema): PASS (0 blockers, 0 should-fix; mental model YES)

**Findings**:
- Mermaid: all three blocks parse under Mermaid 11 after the fix. The `;` was the only statement-separator break; the pipe `|` on the `SUCCESS` line and the slashes in the verdict-flag list parse cleanly as sequence message text.
- Cold-read: `## Workflow` and `## Class Design` carry no TL;DR / Edge cases / References footer, which is correct (both are shape-exempt reference sections per house-style § Why-before-what), not a violation. `Mechanics:` and `**Full design**` ref checks are N/A (single-file design, no mechanics companion, no plan/track files yet).
- No new structural finding.

**Iterations**: 1 of 3 (PASS)
