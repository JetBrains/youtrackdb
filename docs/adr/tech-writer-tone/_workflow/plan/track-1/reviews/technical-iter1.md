<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: T1, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:178", anchor: "### T1 ", cert: C4, basis: "track collapses one lowercase \"summary\" literal onto both sets; SHAPE_EXEMPT_SECTION_NAMES compares raw display-case titles, so a Part-level `## Summary` would not be exempted"}
  - {id: T2, sev: suggestion, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:124", anchor: "### T2 ", cert: C1, basis: "\"three regex removals\" / singular \"the regex\" understates that negative parallelism is two regex objects (leading + trailing); trailing test/fixture could be left behind"}
evidence_base: {section: "## Evidence base", certs: 14, matches: 13}
cert_index:
  - {id: C4, verdict: PARTIAL, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

Reference-accuracy caveat: this track's in-scope surface is Markdown + Python under
`.claude/**` plus `CLAUDE.md`; there are no Java production classes. mcp-steroid PSI
`findClass` is Java-only and returns null for Python identifiers and Markdown
anchors, so every named reference below was verified as a file path, a `§`-anchor, or
a Python symbol via `grep`/`sed`/`Read` against the LIVE tree (the staged subtree
`_workflow/staged-workflow/` does not exist yet, so every `.claude/**` read resolves
to the live file per §1.7(d)). A grep-based verification can in principle miss a
reference hidden by unusual formatting; none of the certificates below depends on a
polymorphic-dispatch or name-collision case where that risk is material.

Workflow-machinery criteria applied (branch is §1.7(b) staged, ledger `s17=staged`):
the five prose criteria supersede the Java WAL/crash/migration/hot-caller lens for
all workflow prose. The Python script and its tests are behavior-bearing, so they
carry both lenses; findings on them are stated against the code as read.

## Findings

### T1 [should-fix]
**Certificate**: C4 (Premise — `SHAPE_EXEMPT_SECTION_NAMES` gains `"summary"`).
**Location**: track-1.md:178 (in-scope file item 7: "`SHAPE_EXEMPT_SECTION_NAMES` and `MANDATORY_OR_FORM_SUBHEADINGS` gain `"summary"` (D4)"); design.md:624; `.claude/scripts/design-mechanical-checks.py:49-55`, `:737`, `:62-73`, `:1335`.
**Issue**: The track item names one lowercase literal, `"summary"`, and applies it to both sets in the same clause. The two sets compare at different case. `is_shape_exempt` (`:737`) tests `section["title"] in SHAPE_EXEMPT_SECTION_NAMES` against the raw heading title, and the existing entries are display-case (`"Overview"`, `"Core Concepts"`, `"TL;DR"`). The sibling-similarity filter (`:1335`) tests `sub.lower() not in MANDATORY_OR_FORM_SUBHEADINGS`, and those entries are lowercase (`"tl;dr"`). A literal `"summary"` (lowercase) added to `SHAPE_EXEMPT_SECTION_NAMES` therefore does not match a Part-level section whose title is `Summary` — the whole-section shape exemption silently fails, and a well-formed Part-level `## Summary` (itself just a short summary blurb under a `# Part N`) would trip the per-section `tldr`/footer shape checks as a false blocker. This is the exact backward-compat break D4 exists to prevent, mirrored from the Part-level `"TL;DR"` entry the design cites at `:54`. None of the track's named test invariants covers it: "Both-spellings acceptance" and the `MANDATORY_OR_FORM_SUBHEADINGS` invariant both target the per-section shape regexes and the sibling check, not the `SHAPE_EXEMPT` Part-level path, so the case bug would slip through green tests.
**Proposed fix**: Split the literal by set in item 7 and in D4: add `"Summary"` (display case, alongside the retained `"TL;DR"`) to `SHAPE_EXEMPT_SECTION_NAMES`, and `"summary"` (lowercase, alongside `"tl;dr"`) to `MANDATORY_OR_FORM_SUBHEADINGS`. Add one shape test that feeds a Part-level `## Summary` section under a `# Part N` heading and asserts no per-section-shape finding, so the exemption is pinned the way the D11 precedent pins the footer rename.

### T2 [suggestion]
**Certificate**: C1 (Premise — the named dsc regexes and their coupling) and C7 (the regex/test/fixture one-unit coupling).
**Location**: track-1.md:124 (Plan of Work: "Three of the six removals ... have a `dsc-ai-tell` regex, so each of those deletes the regex ... in one change"), track-1.md:147 (acceptance: "green after the three regex removals"), track-1.md:160 (Idempotence). Item 7 (`:178`) enumerates all four objects correctly.
**Issue**: The count is loose. Negative parallelism is two distinct regex objects — `NEGATIVE_PARALLELISM_RE` (`:103`, leading-negation copula) and `NEGATIVE_PARALLELISM_TRAILING_RE` (`:127`, trailing `X, not just Y` frame) — each with its own fixture block and assertion (fixture `### Trailing negative parallelism` at `:67-74`, test id `negative-parallelism-trailing` at `test_dsc_ai_tell.py:90-91`). The Plan of Work's singular "the regex" for negative parallelism and the "three regex removals" phrasing in Validation/Idempotence could lead an implementer reading only those sections to delete `NEGATIVE_PARALLELISM_RE` and leave the trailing regex — or its fixture/assertion — in place. The Removal-completeness invariant is behavior-based ("no removed-pattern regex, fixture line, or assertion remains") and would catch a leftover, and item 7 lists both objects, so this is a wording tightening, not a correctness gap.
**Proposed fix**: In Plan of Work and Validation, say "three removed rules / four regex objects" and make the negative-parallelism = leading + trailing pair explicit (as item 7 already does), so no single section under-counts the deletion.

## Evidence base

#### C1 Premise: the four named dsc-ai-tell regexes/checks exist at the claimed lines
- **Track claim**: item 7 (`:178`) and design.md:256-261 name `NEGATIVE_PARALLELISM_RE` (`:103`), `NEGATIVE_PARALLELISM_TRAILING_RE` (`:127`), `HYPHENATED_PAIR_CLUSTER_RE` (`:191`), and the Title-Case check `_title_case_violation` (`:1734`) for deletion.
- **Search performed**: `grep -nE` over `design-mechanical-checks.py` for each symbol; `Read` of the definitions.
- **Code location**: `NEGATIVE_PARALLELISM_RE` @ `:103`; `NEGATIVE_PARALLELISM_TRAILING_RE` @ `:127`; `HYPHENATED_PAIR_CLUSTER_RE` @ `:191`; `_title_case_violation` @ `:1734`. Firing sites: `:1874` (title), `:1942` (hyphenated), `:1962` (neg-par), `:1980` (neg-par trailing).
- **Actual behavior**: All four resolve at exactly the cited lines. `NEGATIVE_PARALLELISM_RE = re.compile(r"\bit'?s not\b.*\bit'?s\b", ...)`; `NEGATIVE_PARALLELISM_TRAILING_RE = re.compile(r",\s+not\s+(?:just|merely|simply)\s+[a-z]", ...)`.
- **Verdict**: CONFIRMED
- **Detail**: Line citations exact. Negative parallelism is two regex objects, not one (feeds T2).

#### C2 Premise: MANDATORY_OR_FORM_SUBHEADINGS holds "tl;dr" and drives the same-shape sibling check (D4 concern)
- **Track claim**: D4 / design.md:632-639 — if `### Summary` sections appear but `"summary"` is not added to `MANDATORY_OR_FORM_SUBHEADINGS`, the shared heading counts toward the similarity score and false-positives on every well-formed design.
- **Search performed**: `Read` of `:62-73` (definition) and `:1310-1339` (`check_same_shape_siblings`).
- **Code location**: `MANDATORY_OR_FORM_SUBHEADINGS` @ `:62`, contains `"tl;dr"` @ `:63`; used @ `:1335` inside the `custom_subs` construction.
- **Actual behavior**: `custom_subs = frozenset(sub.lower() for sub in s["sub_headings"] if sub.lower() not in MANDATORY_OR_FORM_SUBHEADINGS)`; the set feeds the Jaccard union-find at `:1341+`. A sub-heading not in the set contributes to every section's similarity.
- **Verdict**: CONFIRMED
- **Detail**: D4's mechanism is exact — without `"summary"` in the set, a `### Summary` heading in every section inflates pairwise Jaccard and clusters 3+ siblings. Concern well-founded.

#### C3 Premise: section_has_tldr recognises only TL;DR spellings today (D4 needs Summary)
- **Track claim**: item 7 — `section_has_tldr` gains `### Summary`; both spellings stay accepted.
- **Search performed**: `Read` of `:708-716`.
- **Code location**: `section_has_tldr` @ `:708`.
- **Actual behavior**: Matches `(^|\n)\*\*TL;DR\.\*\*` and `(^|\n)### TL;DR\b` only; no `Summary` branch. The per-section shape check at `:778` calls it and emits a `per-section-shape:tldr` blocker when absent.
- **Verdict**: CONFIRMED
- **Detail**: The rename must add a `### Summary` (and `**Summary.**`) branch here, keeping the two TL;DR branches for backward-compat. Matches the track's both-spellings requirement.

#### C4 Premise: SHAPE_EXEMPT_SECTION_NAMES gains "summary" — case sensitivity
- **Track claim**: item 7 (`:178`) — `SHAPE_EXEMPT_SECTION_NAMES` and `MANDATORY_OR_FORM_SUBHEADINGS` both gain `"summary"`.
- **Search performed**: `Read` of `:49-55` (set), `:735-737` (`is_shape_exempt`).
- **Code location**: `SHAPE_EXEMPT_SECTION_NAMES` @ `:49`; `"TL;DR"` entry @ `:54`; comparison @ `:737`.
- **Actual behavior**: `is_shape_exempt` returns `section["title"] in SHAPE_EXEMPT_SECTION_NAMES` — a case-sensitive comparison against the raw heading title. Entries are display-case (`"Overview"`, `"TL;DR"`). `MANDATORY_OR_FORM_SUBHEADINGS` instead compares `sub.lower()`, so its entries are lowercase.
- **Verdict**: PARTIAL
- **Detail**: The codebase mechanism is exactly as described, but the two sets require different case for the added literal: `SHAPE_EXEMPT` needs `"Summary"` (display case) and `MANDATORY_OR_FORM_SUBHEADINGS` needs `"summary"` (lowercase). The track collapses both onto one lowercase literal → T1.

#### C5 Premise: the five review agents' current model pins match the track's "before" states
- **Track claim**: design.md:139-145 roster — `design-author` Opus, `readability-auditor` Opus→Sonnet, `comprehension-review` Opus→Sonnet, `absorption-check` Sonnet, `fidelity-check` Sonnet.
- **Search performed**: `sed -n '1,12p'` of each `.claude/agents/*.md` frontmatter; `grep` for `model:`.
- **Code location**: `design-author.md:5` `model: opus`; `readability-auditor.md:5` `model: opus`; `comprehension-review.md:5` `model: opus`; `absorption-check.md:5` `model: sonnet`; `fidelity-check.md:5` `model: sonnet`.
- **Actual behavior**: All five pins match the roster's "before" column exactly.
- **Verdict**: CONFIRMED
- **Detail**: The three agents the track re-pins are all currently Opus; the two unchanged agents are already Sonnet.

#### C6 Premise: S4 (single prose-AI-tell-axis owner) holds live and the recast preserves it
- **Track claim**: D6 / design.md:147-149 — the target reader keeps sole ownership of the prose AI-tell axis (invariant S4); the recast must preserve exactly one owner.
- **Search performed**: `grep -rniE 'own.*prose AI-tell|prose AI-tell axis'` over `.claude/agents/*.md`.
- **Code location**: `readability-auditor.md:68` (and description `:3`); `comprehension-review.md:39` (and description `:3`); `fidelity-check.md:25`.
- **Actual behavior**: `readability-auditor.md:68` — "You own the prose AI-tell axis on every surface ... one-owner-per-surface invariant (S4)". `comprehension-review.md:39` — "you run it nowhere. This is the one-owner-per-surface invariant (S4)". `fidelity-check.md:25` — "The auditor owns the prose axis". `absorption-check.md` makes no claim.
- **Verdict**: CONFIRMED
- **Detail**: Exactly one agent claims the axis today. S4 is realizable and the "preserve S4" acceptance criterion is checkable by the grep the track names.

#### C7 Premise: the dsc-ai-tell regex/test/fixture "one unit" coupling is real
- **Track claim**: design.md:263-270, items 8-9 — a regex, its assertion, and its fixture line are one unit; deleting the regex alone breaks the build.
- **Search performed**: `ls`/`grep` over `test_dsc_ai_tell.py` and `dsc-ai-tell-fixture.md`.
- **Code location**: fixture (5924 B): negative parallelism @ `:41`, Title Case demo @ `:46-48`, hyphenated cluster @ `:60-64`, trailing negation @ `:67-74`. test (14747 B): Title Case assertions @ `:79`/`:237-240`, trailing-negation id @ `:90-91`.
- **Actual behavior**: Every removed regex has both a positive fixture block and a test assertion that the fixture line produces a finding. Removing the regex leaves the assertion expecting a finding that never appears → build-time failure.
- **Verdict**: CONFIRMED
- **Detail**: Coupling holds for all four objects, including the trailing variant (feeds T2's precision point).

#### C8 Premise: the disguise-rule mirror sites exist where the track enumerates them
- **Track claim**: D1 / design.md:254-261 / items 6,11,12,19 — the removed rules are mirrored in `ai-tells/SKILL.md` description + catalogue, `house-conversation.md`, `review-workflow-writing-style.md`, `design-document-rules.md`, and `CLAUDE.md § Writing Style`.
- **Search performed**: `sed`/`grep` over each file.
- **Code location**: `ai-tells/SKILL.md:3` (description names negative parallelism, Title Case headings, closing phrases; keeps adjective triads), `:24` (catalogue); `house-conversation.md:7` (senior-engineer reader), `:23` (banned list: negative parallelism, roundabout negation, closing connectives); `review-workflow-writing-style.md:29,34,71` (removed-rule criteria), `:111` (`### Adjective triads`, kept), `:28/78` (BLUF); `design-document-rules.md:289` (dsc catalogue row, "seven patterns"); `CLAUDE.md:93` (negative-parallelism parenthetical).
- **Actual behavior**: Every named mirror site resolves. `ai-tells/SKILL.md` description names exactly the three removed rules the track validates and keeps "adjective triads". `review-workflow-writing-style.md` carries both removed-rule criteria (to drop) and BLUF criteria (to extend for D10) and the kept adjective-triads rule.
- **Verdict**: CONFIRMED
- **Detail**: The `design-document-rules.md:289` row currently says "seven patterns"; after removing four regex objects it must read "three" (persuasive authority tropes, fragmented-header chains, inflated-abstraction labels remain) — captured by item 12.

#### C9 Premise: the TL;DR rename sites exist at the claimed locations and counts
- **Track claim**: D4 / design.md:618-630 / items 12-18 — TL;DR is hard-coded in `planning.md:774`, `create-final-design.md:187`, `review-workflow-pr/SKILL.md:114`, `conventions.md:149`, `edit-design/SKILL.md` (~5 sites), `design-review.md` (structural finding + TOC row), `comprehension-review.md:34`, and `house-style.md § Navigability`.
- **Search performed**: `sed -n` at each cited line; `grep -niE 'TL;DR'` over `edit-design/SKILL.md`, `design-review.md`, `house-style.md`.
- **Code location**: `planning.md:774`, `create-final-design.md:187`, `review-workflow-pr/SKILL.md:114`, `conventions.md:149` — all carry TL;DR at the exact cited line. `edit-design/SKILL.md`: `:223,306,372,634,1239` (five sites). `design-review.md`: TOC rows `:26`/`:308`, structural sites `:131,134,296,323,407`. `comprehension-review.md:34`. `house-style.md § Navigability`: `:377,381,395` (+ `:284`).
- **Actual behavior**: All exact-line citations resolve; `edit-design` has exactly five TL;DR sites, matching "~5".
- **Verdict**: CONFIRMED
- **Detail**: No stale line citation found among the TL;DR sites.

#### C10 Premise: the D11 both-spellings precedent test exists (model for the D4 shape tests)
- **Track claim**: items 10, and Validation — new shape tests modeled on `test_design_mechanical_checks_d11.py`.
- **Search performed**: `ls`/`find` under `.claude/scripts/tests/`.
- **Code location**: `.claude/scripts/tests/test_design_mechanical_checks_d11.py` (9790 B); fixtures `d11-footer-legacy-pass.md`, `d11-footer-newname-pass.md`, `d11-footer-nested-code-pass.md`, `d11-decision-bare-fail.md`.
- **Actual behavior**: The D11 footer-rename test with legacy + new-name pass fixtures is exactly the both-spellings precedent D4 cites (References → Decisions & invariants).
- **Verdict**: CONFIRMED
- **Detail**: The precedent is a viable model; placement (new file vs extension) is a Phase A decision the track defers, which is fine.

#### C11 Premise: BOOK_BRIEF.md carries eight voice rules matching D9's transfer split
- **Track claim**: D9 / design.md:363-372 — five transfer verbatim, two adapted, one already present.
- **Search performed**: `find -iname BOOK_BRIEF.md`; `grep` the "Voice and pacing rules" list.
- **Code location**: `workflow-book-builder/BOOK_BRIEF.md:29-38` (§ Voice and pacing rules, non-negotiable).
- **Actual behavior**: Exactly eight numbered rules: (1) Narrative not reference, (2) Concrete before abstract, (3) One concept per section, (4) Earn every name, (5) Connect forward and backward, (6) Source citations stay precise, (7) Diagrams must teach, (8) No bullet-point fact dumps. D9's split maps cleanly: verbatim = #2,#3,#4,#7,#8; adapted = #1,#5; already-present = #6.
- **Verdict**: CONFIRMED
- **Detail**: The eight-rule source and its 5/2/1 disposition are accurate.

#### C12 Premise: edit-design mutation kinds section-move/remove/rename exist (D9 reminder hook)
- **Track claim**: D9 / item 14 — add the link-staleness re-read reminder to the `edit-design` mutation discipline on section-move/remove/rename.
- **Search performed**: `grep -niE` over `edit-design/SKILL.md`.
- **Code location**: mutation-kind table @ `:136` (`section-remove`), `:137` (`section-rename`), `:138` (`section-move`); also enumerated at `:81-82`, `:196-197`.
- **Actual behavior**: All three are first-class mutation kinds with defined design/mechanics + scope columns. No neighbor-re-read reminder is present yet — that is the new addition D9 makes (planned by this track).
- **Verdict**: CONFIRMED
- **Detail**: The hook point exists; the reminder is a net-new addition, correctly framed as this track's work.

#### C13 Premise: D1/D7 removals renumber the self-check list (D10 anchor-by-name rationale)
- **Track claim**: D10 / design.md:701 / item 1 — the self-check BLUF item is anchored by name because the removals renumber the list.
- **Search performed**: `Read` of `house-style.md § Self-check` (`:401-440`).
- **Code location**: `house-style.md:401` (§ Self-check), items 1-10.
- **Actual behavior**: Item 1 = "Negative parallelism and roundabout negation" (both removed) → the item is deleted, shifting items 2-10 up by one. Item 2 loses "closing phrases"; item 4 loses hyphenated clusters + curly quotes; item 5 loses "sentence case on H2+". The BLUF item is currently #9 and shifts to #8.
- **Verdict**: CONFIRMED
- **Detail**: The renumber is real and non-trivial (a whole item deleted, three items edited), so anchoring the BLUF self-check item by name rather than ordinal is sound.

#### C14 Premise: house-style.md line citations and D10 acceptance-site headings resolve
- **Track claim**: design.md:16, :256-261, :696 / item 1 — `:6` reader floor, `:42` writer persona, `:70-74` orientation exemplar, `:100/:101/:103` banned patterns, `:262/:273/:304` removed-rule sections; D10 sites § BLUF lead, § Orientation, § Self-check.
- **Search performed**: `sed -n` at each line; `grep -nE '^#{2,3} '` for headings.
- **Code location**: `:6` (mid-level reader floor), `:42` ("senior engineer writing to peers"), `:70-74` (before/after orientation exemplar), `:100` (Negative parallelism), `:101` (Roundabout negation), `:103` (Closing phrases), `:262` (§ Hyphenated word-pair overuse), `:273` (§ Curly quotes), `:304` (§ Title Case headings forbidden). Headings: § BLUF lead `:22`, § Orientation `:54`, § Self-check `:401`.
- **Actual behavior**: Every cited line and heading resolves exactly as the design/track states.
- **Verdict**: CONFIRMED
- **Detail**: No stale citation. The D10 exemplar-pair site (§ Orientation, beside the `:70-74` worked exemplar) exists as claimed.
