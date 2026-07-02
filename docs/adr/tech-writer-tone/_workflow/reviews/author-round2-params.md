# design-author params — phase1-creation, round 2

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/tech-writer-tone/docs/adr/tech-writer-tone/_workflow/research-log.md
- round: 2
- flagged_passages: (14 auditor findings below — all density/word-choice/gloss fixes; none needs code re-grounding; the one factual fix is FP9's counts. Edit ONLY these passages; do not rewrite unflagged sections. Preserve the line-1 workflow-sha stamp byte-for-byte.)

## Flagged passages

1. design.md:28-31 — over-dense. "The enabling primitive is `house-style.md`'s single-source-of-truth contract: four consumers (…) already cite it by section and restate no rule, so a removal at the source and those consumers propagates everywhere." Drop the "enabling primitive" label; lead with the plain claim ("`house-style.md` is the single source of truth"); split the causal chain into its own sentence; fix the ambiguous "a removal at the source and those consumers propagates".
2. design.md:19-26 — over-dense. Five changes semicolon-chained into one sentence in the Overview. Break onto separate lines, one change per line (numbered or bulleted).
3. design.md:32-35 — over-dense. Seven artifacts comma-chained AND deferred to §"Class Design" in the same sentence. Either defer entirely ("…touches seven further artifacts; §Class Design lists them") or bullet them — not both.
4. design.md:26 — hard-to-read. "BLUF" unexpanded anywhere in the document. Expand at true first use in the Overview: "bottom-line-up-front (BLUF)" with a one-clause gloss; then the short form everywhere, including §"Hardening the section BLUF lead" (also flagged, FP14).
5. design.md:148 — glossary. "de-warmed comprehension gate" used at :148 but defined only at :184-189. Gloss inline at first use ("a de-warmed comprehension gate — it reads only the document, never the research log — runs once at the end") or move the definition up.
6. design.md:220-221 — hard-to-read. "the `X`-contrast the `not X, but Y` form carries often has to be re-stated in a positive rewrite" — dropped relative pronoun, nested subjects. Rewrite: "the `not X, but Y` form carries an X-contrast that a positive rewrite often has to re-state."
7. design.md:216-219 — hard-to-read. Keep-verdict sentence ("…so its ban pays in review time and the rule stays") sits inside the Remove discussion with no contrast marker. Add the contrast connective: "By contrast, deleting throat-clearing…".
8. design.md:341-342 — too-terse/factual. "five verbatim or adapted, one already present" sums to six, not eight; the table below says 5 verbatim + 2 adapted + 1 already present. State the split that sums to eight.
9. design.md:496 — hard-to-read. "depth-allergy" is a coined metaphor. Replace with the literal trait ("its impatience with over-deep prose" / "its skepticism toward shallow depth-dressing").
10. design.md:498-503 — over-dense. Four rejected alternatives with parenthetical rationales in one semicolon-chained sentence. Convert to a bulleted list, one alternative + rationale per line.
11. design.md:542-543 — hard-to-read. "…reports failure-to-follow ("I had to re-read this") rather than assessing subtle prose quality, which is introspectively easier" — the trailing clause attaches to the wrong noun. Split: "Reporting a stumble is introspectively easier than judging subtle prose quality…".
12. design.md:543-544 — glossary. "answers-plus-citations" conflicts with the earlier "reading narrative plus structured findings" description of the same gate output. Reconcile: the gate answers comprehension questions with citations AND returns the narrative + findings — say how the two relate at first mention.
13. design.md:556-560 — over-dense. Three rejected alternatives chained in one sentence, middle one a folded causal chain. Bullet list, one per line; linearize the middle rationale.
14. design.md:643-665 — glossary. "BLUF" unexpanded in §"Hardening the section BLUF lead" (heading + 5 uses). Fixed by FP4's first-use expansion in the Overview; keep the short form here once the Overview glosses it.
