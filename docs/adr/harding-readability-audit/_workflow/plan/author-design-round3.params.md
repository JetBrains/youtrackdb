# design-author params — phase1-creation, round 3 (fix flagged passages)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/research-log.md
- round: 3

## flagged_passages (round-2 readability auditor; all prose fixes, no decision change)

Edit `design.md` in place — word-choice / sentence-shape / gloss only, no
decision content changes. Re-locate by the quoted phrase (line numbers drift).

1. **Split the Overview defect sentence (~lines 12-18).** "Second, the auditor
   is a stateless cold spawn every round, so the dual-clean review loop (it
   iterates …) re-rolls already-settled prose through a fresh reader and
   oscillates instead of converging (the cited evidence: 13 → 8 → 3 → 8 …)."
   → three sentences: (1) the defect (stateless cold spawn every round);
   (2) the consequence (re-rolls settled prose, oscillates instead of
   converging); (3) the evidence. Keep the dual-clean gloss but move it so it
   doesn't nest inside the main clause.

2. **Fix the Calibrated-hold sentence (Core Concepts, ~line 61).** "Calibrated
   holds are individual decisions, not a bulk dismiss, and the user veto … is
   the only backstop …" → drop the negative-parallelism "not a bulk dismiss"
   (state positively), and split so "the user veto" sits next to its verb:
   "Each calibrated hold is an individual decision. The only backstop against
   over-accepting prose holds is the user veto: the orchestrator presents the
   held set for review at the end of the loop (D15)."

3. **Gloss "conclusion-priming" / state positively (~line 105).** "they are
   constant across a round's fan-out and are not conclusion-priming" → replace
   the unglossed negated term with the plain mechanism: "they are the same for
   every slice in a round, so they cannot nudge any auditor toward a particular
   finding".

4. **Gloss "log-adversarial entry" + split the 5-idea sentence (~line 334).**
   "The comprehension gate and the iteration-budget escalation remain backstops
   only for the comprehension / structural and decision-shaped axes: a
   decision-shaped hold re-opens the S3 freeze-order gate (the gate that blocks
   the comprehension review while an unresolved log-adversarial entry is open),
   because a held decision is a decision the loop has not actually settled." →
   split into 2-3 sentences and gloss "log-adversarial entry" in one clause
   (the entry the Phase-0→1 adversarial gate leaves open on the research log
   when a decision is still contested).

5. **Split the relocation sentence + drop the redundant list (~line 397).**
   The long em-dash sentence "Concern C moves all of them — every per-spawn
   params file (author, readability-auditor, absorption / fidelity,
   comprehension) and every review output file — into … `_workflow/reviews/` …"
   restates the TL;DR's four-item list. → split into two short sentences and
   drop the inline parenthetical list (the TL;DR already enumerates them).

6. **Gloss "Phase-0→1 gate" (~line 399).** "`conventions-execution.md` §2.5
   already defines exactly that directory for the Phase-0→1 gate's files" → add
   a one-clause gloss: "the Phase-0→1 gate (the adversarial review that clears
   the research log before any Phase-1 artifact derives)".

7. **Inline the I6 invariant content (~line 430).** "— the exact destabilization
   staging prevents (the I6 invariant)" → state what I6 says inline:
   "— the exact destabilization staging prevents: a running phase must never
   read a half-modified workflow (I6)".

8. **State §1.7(k) criterion 2's test before applying it (~line 430).** The
   argument cites "§1.7(k) criterion 2" and quotes one example without stating
   the test. → add the test in one clause: "§1.7(k)'s criterion 2 (a plan does
   not qualify for the prose-rule opt-out if a running phase reads its edited
   files as executable procedure, not merely as prose)", then apply it.

9. **Add why-before-what for the resume glob (~line 401).** Before "the
   `edit-design` Step 6 dual-clean loop has a mid-loop resume round-count glob
   that re-derives the round …", add one motivating sentence: the Step 6 loop
   can resume after an interruption and recovers which round it was on by
   counting the per-round params files on disk — then state that relocating
   those files updates the glob's path.

After editing, keep the workflow-sha stamp on line 1 and the H1 on line 2
untouched. Return only a thin summary of what changed per passage.