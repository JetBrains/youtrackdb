# design-author params — phase1-creation, round 4 (S5 terminal cleanup)

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/track-complexity-assessment-workflow-optimization/docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-complexity-assessment-workflow-optimization/docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
- round: 4

This is the terminal cleanup pass: the dual-clean inner loop reached its budget
with only borderline should-fix prose nits open (the designed never-clean tail).
Apply the cheap, unambiguous fixes below and stop. Revise only these passages;
leave every other section byte-stable. Locate each by its verbatim quote. None
touch the `## Overview` or `## Core Concepts` anchors. No new code read needed
except the citation in fix 6 (already in the research log).

## flagged_passages — terminal cleanup (all should-fix, all cheap)

1. **`∃` symbol in prose** (the resume-routing/reconciled-tag-home prose that
   reads "... reads it for the \"∃ track ≥ medium\" test"). Spell out the
   quantifier in the prose, e.g. "... reads it for the 'any track tagged medium
   or higher' test." Leave the compact `∃` / `≥` form where it appears in the
   artifact-axis **table cell** and in the **D8 decision-record line** (terse
   formula notation is fine there) — change only the prose occurrence.

2. **"verbs-on-change" coinage** (Part 1 §"Computing the tag": "The HIGH triggers
   are verbs-on-change — \"introduces synchronization\", \"modifies WAL
   recovery\", ..."). Replace the coined hyphen-compound with a plain clause,
   e.g. "The HIGH triggers test what the change *does* — \"introduces
   synchronization\", \"modifies WAL recovery\", \"adds an abstraction layer /
   SPI registration\" — not which files it touches."

3. **Phase-A selection diagram node labels** (the reconciliation/selection
   `flowchart` whose nodes fold predicted and reached tags into one token, e.g.
   "low+medium -> Adversarial" / "low/medium+high -> Risk (+Adversarial)"). Spell
   the two inputs out in the node labels so the diagram stands alone, e.g.
   "predicted low, steps reach medium -> Adversarial" and "predicted low/medium,
   steps reach high -> Risk (+Adversarial if not yet run)".

4. **"subtract cautiously" idiom** (the downward-divergence prose: "A light flag
   goes to the decomposer to confirm the steps were not under-tagged (\"subtract
   cautiously\"); no re-review."). Drop the quoted idiom and state the action
   plainly, e.g. "A light flag asks the decomposer to re-check that no step was
   tagged below the work it actually does before the lower reconciled tag is
   trusted; no re-review." (The floor's "subtract cautiously" minimum phrasing
   elsewhere may keep the slogan if it is glossed; here, in the action sentence,
   state it plainly.)

5. **"sacred" metaphor** (two sites in Part 3: "the floor + domain-matched set is
   sacred ..." / "The floor + domain-matched set is sacred at Phase C for a
   concrete reason."). Replace with the literal property the prose states anyway,
   e.g. "the floor plus the domain-matched set is never suppressed: complexity
   never drops a selected specialist."

6. **Vague attribution "a prior measurement found that ..."** (Part 3, the
   justification for `high` adding no Phase-C adversarial finding-verification).
   Name the source the research log D6 cites instead of an unattributed "a prior
   measurement": cite the step-level dimensional-review catch-rate study behind
   YTDB-1100 (the substep-4 catch-rate measurement) — e.g. "because the
   step-level dimensional-review catch-rate study behind YTDB-1100 found that
   step-level review on high steps caught essentially no production-logic bugs,
   so the extra verification would be unearned cost." Ground the citation in the
   research log's D6 rationale.

7. **"primes lenses" metaphor** (Part 3, two sites: "domain only primes lenses" /
   "domain only primes the Risk / Adversarial lenses"). State the literal effect
   at first use, e.g. "domain only biases which concerns the Risk and Adversarial
   reviewers emphasize; it does not change which of the three run" — then the
   shorthand may follow if reused.
