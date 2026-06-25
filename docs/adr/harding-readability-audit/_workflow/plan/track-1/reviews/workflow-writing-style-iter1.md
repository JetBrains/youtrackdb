<!-- MANIFEST
findings: 3   severity: {should-fix: 2, suggestion: 1}
index:
  - {id: WS1, sev: should-fix, loc: edit-design/SKILL.md:897, anchor: "### WS1 ", cert: C1, basis: "trailing negation restates 'not a pathology' six lines up; closing-phrase padding in named in-scope monotonic-convergence paragraph"}
  - {id: WS2, sev: should-fix, loc: edit-design/SKILL.md:948-957, anchor: "### WS2 ", cert: C2, basis: "620-word subsection (3x soft cap) whose closing paragraph re-derives the settled-state-filter mechanism already stated in the bullets"}
  - {id: WS3, sev: suggestion, loc: readability-auditor.md:18, anchor: "### WS3 ", cert: C3, basis: "informal idiom 'none of your concern' in agent-instruction prose; plain-language nit, claim survives"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: SURVIVED, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [should-fix]

- File: `docs/adr/harding-readability-audit/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 897)
- Axis: banned sentence patterns
- Cost: trailing-negation closing phrase that restates a claim already made six lines up, inside the named in-scope monotonic-convergence paragraph
- Issue: The paragraph already states "That tail is a **designed terminal exit, not a pathology**" at line 890-891. The closing clause at line 897 — "...alongside the early dual-clean exit for a doc with no such tail — **not a fallback for a malfunction**." — re-asserts the same point ("pathology" ≈ "malfunction") with no new content. Per `house-style.md § Banned sentence patterns` (trailing hedges / roundabout negation: "State what IS true") and the § Self-check item 10 ("Paragraphs that don't add information beyond the previous one. Delete."), the second negation is padding. The two adjacent claims the spawn asked me to check — monotonic-on-settled-sections vs. never-clean-tail-as-designed-exit — do read cleanly and do not contradict; the defect is only the redundant closing negation, not a logic clash.
- Suggestion: Drop the trailing clause and keep the prior sentence's positive statement. Replace the final sentence — "The budget-plus-S5 tail is the expected terminal path for a dense-but-acceptable doc, alongside the early dual-clean exit for a doc with no such tail — not a fallback for a malfunction." — with: "The budget-plus-S5 tail is the expected terminal path for a dense-but-acceptable doc; the early dual-clean exit is the expected path for a doc with no such tail." The "not a pathology" framing at line 890-891 already carries the rejected reading.

### WS2 [should-fix]

- File: `docs/adr/harding-readability-audit/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 948-957, within the `#### The canonical convergence mechanism` subsection at lines 899-957)
- Axis: section length
- Cost: subsection at ~620 words, roughly 3x the 200-word soft cap, with a closing paragraph that re-derives the settled-state-filter mechanism the bullets already stated
- Issue: The `#### The canonical convergence mechanism` subsection is the smallest labeled block and runs ~620 words. It is not in any of the five template-bound exempt categories (`house-style.md § Structural rules` "Section length cap exception") — it is free-form mechanism prose under a skill `####` heading, not an ExecPlan structured-field block, edit-list, state-machine table, file:line citation block, or `design-mechanics.md` derivation. Per the "Padding-based finding criterion", length over the soft cap is a finding only with a padding pattern present; the padding here is restatement (`§ Elegant variation` / § Self-check item 10). The closing paragraph (948-957) re-derives "the settled-state filter kills the clean→dirty oscillation (a section that returned clean and is unchanged has its re-flags dropped)" — the identical point already made by the `- **The per-section round decision.**` bullet ("A section that is settled and unchanged ... has all its findings dropped") and by `- **Settled = returned-clean only.**`. The "the hash suppresses what a literal passage-level do-not-re-flag list cannot" sentence at 953-955 is the one new claim; the surrounding restatement is the padding.
- Suggestion: Compress the closing paragraph (948-957) to the single non-redundant claim. Replace it with: "A literal passage-level do-not-re-flag list cannot suppress the clean→dirty swing: a clean slice leaves no quotes to carry forward. The section hash carries the 'this section was clean and is unchanged' verdict instead, which is why it kills the oscillation a stateless cold spawn would otherwise produce on byte-identical prose. A decision-shaped finding is never a prose-density case — it re-opens the S3 freeze-order gate above — so only prose-density should-fix findings ride the budget-plus-S5 tail." That keeps the one new point and the S3-scope clause and drops the re-statement of the filter behavior.

### WS3 [suggestion]

- File: `docs/adr/harding-readability-audit/_workflow/staged-workflow/.claude/agents/readability-auditor.md` (line 18, the new "**You receive no settled-state.**" paragraph)
- Axis: plain language
- Cost: informal idiom in agent-instruction prose where the literal phrasing is shorter and clearer
- Issue: "The orchestrator decides which sections to re-spawn and filters your returned findings against its own settled-state; that filtering is none of your concern." Per `house-style.md § Plain language` ("Avoid idioms ... Use the literal verb"), "none of your concern" is an idiom standing in for the literal instruction. The surrounding paragraph is otherwise clear, so the load-bearing claim survives — this is a polish-only nit.
- Suggestion: Replace "that filtering is none of your concern" with "you do not perform that filtering" or "you never see or act on its settled-state."

## Evidence base

#### C1 CONFIRMED

Banned sentence patterns sweep result. Line 890-891 states "**designed terminal exit, not a pathology**"; line 897 states "**not a fallback for a malfunction**." Both are negation-of-the-rejected-reading clauses about the same dense-but-acceptable tail in the same paragraph; "malfunction" carries no content beyond "pathology." Matches `house-style.md § Banned sentence patterns` trailing-negation rule and § Self-check item 10 (delete a paragraph/clause adding no information beyond a prior one). The codebase's pervasive "X, not Y" appositive idiom (lines 689, 716, 724, 749, 933, and ~30 others, all out of scope) is the informative contrastive form and is not flagged; this finding is the redundant *second* occurrence within one in-scope paragraph, not the form itself.

#### C2 CONFIRMED

Section-length three-step decision. (1) Size: `#### The canonical convergence mechanism` subsection (lines 899-957) measures ~620 words by `wc -w`, over the 200-word soft cap. (2) Exempt-category check: not one of the five `house-style.md § Structural rules` exempt shapes — it is free-form skill mechanism prose, not an ExecPlan `## Episodes` block, `design-mechanics.md` edit-list, state-machine table, file:line citation block, or multi-step derivation. (3) Padding-pattern check: the closing paragraph (948-957) restates the settled-state-filter behavior already given by the `- **The per-section round decision.**` and `- **Settled = returned-clean only.**` bullets — restatement under `§ Elegant variation` / § Self-check item 10. All three steps pass, so the over-cap unit is a finding.

#### C3 SURVIVED

Plain-language judgment. "none of your concern" reads as an idiom where the literal "you do not perform that filtering" is shorter and unambiguous, matching the `§ Plain language` no-idioms move. The flagged unit reads slightly harder than its plain rewrite; the load-bearing instruction (the auditor never sees settled-state) survives, so this is a suggestion, not a should-fix. No count or score attached, per the `### Plain language` criterion.
