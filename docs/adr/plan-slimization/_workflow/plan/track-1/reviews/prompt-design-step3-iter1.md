<!-- MANIFEST
findings: 2   severity: {Critical: 0, Recommended: 1, Minor: 1}
index:
  - {id: WP1, sev: Recommended, loc: "create-plan/SKILL.md:1054-1059", anchor: "### WP1 ", cert: n/a, basis: "escape-hatch leaves queue-state undefined; LLM may re-process an already-moved finding in the batch"}
  - {id: WP2, sev: Minor, loc: "create-plan/SKILL.md:1056,1068", anchor: "### WP2 ", cert: n/a, basis: "one procedure named two ways within 12 lines; second name carries no parenthetical or anchor"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### WP1 [Recommended]
- File: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (line 1054-1059)
- Axis: deterministic decision rules
- Cost: non-reproducible LLM behavior — an escape-hatched finding may be re-processed in the batch, double-charging the D14 gate premium the batch is meant to amortize
- Issue: the **Escape hatch** paragraph tells the LLM what to *do* with a single blocking finding ("runs the single-decision route ... and ends with a one-line note of what moved") but never states that the escape-hatched finding is **removed from the live in-session queue**. At runtime, after escape-hatching finding X, the agent reaches "When the user declares the review done, run the batch" and finds X still tagged in the queue with no rule saying to skip it. Re-process or drop? The procedure is silent, so two runs of the same prompt can diverge. The cross-session case *is* covered — the handoff queue block in `mid-phase-handoff.md` carries an "Escape-hatch findings already processed this hold" line so the flush session does not re-process — but the in-session SKILL text, which is the runtime prompt for the common single-session case, never carries the matching dedup rule. The reader has to generalize the cross-session mechanism to the in-session case unaided.
- Suggestion: add one clause to the escape-hatch paragraph making the queue mutation explicit, e.g. "...and ends with a one-line note of what moved; the processed finding is then **dropped from the queue** so the batch on review-done does not re-process it (the handoff's `Escape-hatch findings already processed this hold` line is the cross-session form of the same rule)."

### WP2 [Minor]
- File: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (line 1056, 1068)
- Axis: deterministic decision rules
- Cost: borderline reader confusion — the LLM must infer two terms name one procedure with no in-prompt anchor confirming it
- Issue: the **Escape hatch** paragraph names "the **single-decision route** (one gate run, one mutation, one cold-read for the lone finding)" with a defining parenthetical (line 1056); the **Multi-session holds** paragraph then says "a batch of one degenerates to the **single-finding route**" (line 1068) — a different term, 12 lines later, with no parenthetical and no anchor back to the escape-hatch definition. A runtime reader has to infer the two names denote the same procedure. The dual phrasing is inherited verbatim from the design.md spec (Part 3 §"Review-iteration batching" uses "single-decision route" at line 550 and "single-finding route" at line 558), so the SKILL is faithful to its source — but the SKILL is the runtime prompt, and one-name-per-procedure removes the inference step. Low severity because the escape-hatch parenthetical is close enough that an attentive reader resolves it.
- Suggestion: pick one term across both paragraphs (prefer "single-decision route", since that one carries the defining parenthetical) and use it in both places; if the design.md spec is later reconciled, the same single-term choice should land there. This is a candidate to fold into the same Phase-C consistency touch already noted in the Step 2 episode for the §1.2 schematic lag.

## Evidence base
