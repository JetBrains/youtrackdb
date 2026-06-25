# design-author params — phase1-creation, round 2 (fix flagged passages)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/research-log.md
- round: 2

## flagged_passages (round-1 readability auditor; all prose fixes, no decision change)

Edit `design.md` in place. These are word-choice / sentence-shape / gloss fixes
only — do not alter any decision content or restructure sections. Line numbers
are against the current stamped doc; re-locate by the quoted phrase.

1. **Gloss "dual-clean" at first use (Overview, ~line 13).** "the dual-clean
   review loop" is the doc's central object but never glossed. Add a one-clause
   gloss at first use, e.g. "the dual-clean review loop (it iterates until both
   reviewers — the readability auditor and the absorption check — report a clean
   round)". Also add the gloss to the Core Concepts entry if one exists for it.

2. **Drop the "enabling primitive" inflated-abstraction label (~line 31).**
   "The enabling primitive for convergence is the content hash that folds in
   …" → lead with the concrete noun: "Convergence turns on a content hash that
   folds in the section's own text plus the standing anchors the auditor reads".

3. **Split the long partition sentence (~lines 18-22).** "It makes the
   design-path slice partition a deterministic orchestrator obligation — …
   four-item list … — and pairs it with an agent-side guard …" → two sentences:
   one stating the partition rule (the inline list is fine), one stating the
   agent-side guard.

4. **Gloss "the D15 presentation" (~line 58).** "the user veto at the D15
   presentation" names nothing a cold reader can resolve. Gloss the presentation
   point in a clause, keeping D15 as a trailing reference: "the user veto when
   the orchestrator presents the held set for review at the end of the loop
   (D15)".

5. **Split the expected-slice-count sentence (~line 209).** "The orchestrator
   already holds the three inputs … — total line count, the ~200-line window
   size, the ~6 cap — and the partition rule (above) is deterministic, so the
   expected slice count is a pure function of those inputs." → one sentence
   listing the three inputs, then: "Because the partition rule is
   deterministic, the expected slice count is a pure function of those three
   inputs."

6. **Split the four-fact sentence (~line 271).** "`edit-design` Step 6 asserts
   the loop 'moves monotonically …,' but the per-round state … lives in
   orchestrator working memory and the author re-grounds only flagged passages —
   each round's auditor is a fully cold spawn with no do-not-re-flag set." →
   linearize one link per sentence: the Step 6 promise; then per-round state is
   orchestrator-only; then the author re-grounds only flagged passages; then
   each spawn is therefore a cold reader with no do-not-re-flag set, so settled
   prose gets re-litigated.

7. **Gloss "de-warmed" at first use (~line 331).** "the de-warmed comprehension
   gate" — gloss what was removed: "the comprehension gate, run cold with its
   prior warm context stripped". Add to Core Concepts if it carries a term entry.

8. **Split the three-premise prose-hold sentence (~line 331).** "This is the
   only prose-hold backstop, for a specific reason: the de-warmed comprehension
   gate runs no prose AI-tell axis (the S4 one-owner-per-surface rule put that
   axis on the auditor alone), and the only prose owner is the very auditor the
   hold suppresses — so the comprehension gate cannot catch an over-held prose
   finding." → three short sentences: the gate runs no prose axis; S4 makes the
   auditor the sole prose owner; an over-held prose finding therefore has no
   second catcher, so the D15 user veto is its only backstop.

9. **Split the contrasting-causal sentence (~line 282).** "The literal
   passage-level do-not-re-flag list … was rejected because a clean slice …
   leaves no quotes to carry forward, so a passage list cannot suppress the
   clean→dirty oscillation …; the section hash can, because it carries the
   'this section was clean and is unchanged' verdict that a quote list cannot."
   → split at the semicolon into two sentences (why the passage list fails; why
   the section hash succeeds).

10. **Restore the dropped relative pronoun (~line 427).** "§1.7(k) criterion 2
    disqualifies a plan whose edited files a running phase reads as executable
    procedure" garden-paths on "files". Fix: "§1.7(k) criterion 2 disqualifies a
    plan when a running phase reads its edited files as executable procedure",
    then the "explicitly naming 'the step-implementation orchestration loop'"
    clause as its own sentence.

11. **Break the faux-parallel list (~line 380).** "the meta context (D7) settles
    tier `full`, full §1.7 staging, and the consequence that this branch runs
    the unmodified live loop during its own authoring" — the third item is a
    clause, not a noun phrase, and "full … full" stumbles. Fix: "the meta context
    (D7) settles two things: tier `full` and full §1.7 staging. The consequence
    is that this branch runs the unmodified live loop during its own authoring."

After editing, keep the H1 on line 2 and the workflow-sha stamp on line 1
untouched. Return only a thin summary of what changed.