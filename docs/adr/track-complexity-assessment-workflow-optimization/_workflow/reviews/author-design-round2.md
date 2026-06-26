# design-author params — phase1-creation, round 2

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/track-complexity-assessment-workflow-optimization/docs/adr/track-complexity-assessment-workflow-optimization/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/track-complexity-assessment-workflow-optimization/docs/adr/track-complexity-assessment-workflow-optimization/_workflow/research-log.md
- round: 2

Revise only the flagged passages below; leave every other section byte-stable.
Locate each passage by its verbatim quote (line numbers may have drifted). Most
fixes are gloss / reword / add-one-sentence and need no new code read; re-ground
through the live `.claude/**` files only where a fix asks for a worked example
or a mechanism the current prose lacks.

## flagged_passages — readability (prose axis, all should-fix)

1. Overview — "The **enabling primitive** is the **phase ledger** ...". Drop the
   inflated "enabling primitive" label; lead with the plain claim, e.g. "The new
   state lives in the **phase ledger** (`_workflow/phase-ledger.md`, the
   append-only event log the resume state machine reads)."

2. Overview — "Two reviewer-roster changes **ride along**:". Replace the idiom
   with a literal verb, e.g. "This change also makes two reviewer-roster
   changes:".

3. Core Concepts, the `Domain × complexity` entry — it uses "the strategic trio"
   and "the dimensional panel" with no gloss, but these are new names this design
   coins and are only defined two Parts later. Add a one-clause in-place gloss at
   first use: "the strategic trio (technical / risk / adversarial)" and "the
   dimensional panel (one reviewer per dimension)".

4. Part 2 — "Phase C reads `max(step tags)`, floored." The trailing "floored" is
   unresolvable. Spell out the link, e.g. "Phase C reads `max(step tags)` as the
   floor: even when the prediction over-ran, the rigor never drops below the
   step-derived maximum."

5. Part 2 — "the panel already over-ran, which is **banked and safe**". Replace
   the "banked" metaphor with the literal meaning, e.g. "the panel already
   over-ran, so the extra review is preserved and the result is safe." Mirror the
   wording in the Part-2 reconciliation diagram node that currently reads "panel
   banked".

6. Part 3 — "the **substep4 evidence** is that pre-emptive / extra review catches
   almost no production-logic bugs". "substep4" is an unglossed external
   reference. Gloss what it measured in one clause (a prior measurement that
   step-level dimensional review on high steps caught essentially no
   production-logic bugs), or state the empirical claim directly without the bare
   "substep4" label.

7. Part 3 — "The Phase-C specialists are gated on largely the same HIGH triggers
   that make a track `high`, so domain and complexity are correlated ...". The
   correlation premise is asserted, not shown. Add one sentence linking Phase-C
   specialist gating to the HIGH-trigger / category overlap (e.g. a category like
   `configuration` that selects `review-security` is also a HIGH-trigger
   characteristic), so the "sacred floor" justification is followable.

8. Part 3 — "... `prompt-design` / `instruction-completeness` / `hook-safety` /
   `writing-style` selected by **glob**." "selected by glob" is underspecified.
   State what the glob matches (each workflow specialist fires when the diff
   touches files matching its glob — scripts/hooks for `hook-safety`, `*.md` for
   `writing-style`), or cite the rule that defines the globs.

9. Part 3 — the sentence defining the `review-bugs-concurrency` step-level burial
   role and its inheritance ("The combined `review-bugs-concurrency` step-level
   burial role — ... — is inherited by `review-bugs` (always) plus
   `review-concurrency` ..."). Split into two sentences: first state the burial
   role and why (its bug/logic/leak/null findings get buried in the cumulative
   diff, so it must see each step in isolation), then state the inheritance split
   (`review-bugs` always; `review-concurrency` when the `concurrency` category is
   present, since a race is buriable too).

10. Part 3 — "the live rule's **workflow-review-group narrowing** governs,
    roster-adapted; the design reads that paragraph ...". Name the live rule and
    section being deferred to (cite it by name as the burial rule is cited
    elsewhere in this Part), and state in one clause what
    "workflow-review-group narrowing" does, so a reader can locate the governing
    rule.

11. Part 5 — "Removing `tier=` from the ledger and adding the design=yes + single
    cell change what Step 1c reads ...". This is a garden-path sentence. Split it
    and name the subject and verb and the elided noun, e.g. "Two ledger edits
    change what Step 1c reads to route a resumed session: removing `tier=`, and
    adding the `design_gate=yes` + single-track cell."

## flagged_passages — absorption (coverage, load-bearing)

12. **Add a missing D2 seed record.** Research-log `## Decision Log` D2 is
    load-bearing (alternatives rejected: "include Fable for `high` steps now —
    deferred per user") but no D2 record appears in the design. Add a D2 seed
    D-record capturing: the per-track tag drives two consumers here, not three —
    the implementer-model swap (Fable 5 for `high` steps) is deferred and the
    implementer stays Opus for every step; and the scope walkback that follows —
    YTDB-1100 and YTDB-1056 Part 2 are out of scope (not subsumed), only
    YTDB-1056 Part 1 (the `review-test-behavior` + `review-test-completeness` →
    `review-test-quality` merge) is absorbed. Place it where the other D-records
    sit (the relevant Part's `### Decisions & invariants`, e.g. Part 3 reviewer
    selection, or Part 1 where the consumers are introduced — wherever the design
    already discusses the consumer set / roster).

13. **Reframe the A10 and A2 citations in Part 5 (and remove them as decision
    records).** The absorption check flagged Part 5's `### Decisions & invariants`
    block citing "A10" as a decision with no `## Decision Log` basis, and the
    auditor flagged the bare "A2 collision" label resolving nowhere. Both `A2`
    and `A10` are **adversarial-gate finding IDs, not `## Decision Log`
    entries** — the iter2 gate explicitly ruled A10 non-gating ("no log change"),
    and A2 was resolved by D10 (the log's D10 "resolves gate A2/A3/A7"). Fix:
    - In Part 5's `### Decisions & invariants` footer, cite **D10** (the real
      Decision Log entry that owns the ledger schema delta and resume
      disambiguation) as the decision basis, alongside D1 and D8 as relevant.
      Remove "A10" and "A2" from the decisions/`D-records:` list — they are not
      D-records and must not be cited as ones.
    - Describe the collision in the section prose **without the bare "A2" tag**:
      the `design_gate=yes` + single-track steady state is byte-identical on disk
      to a `design`-tier mid-authoring crash (both have `design.md` present, no
      plan), and the **Phase-1-complete marker** (per D10) is what disambiguates
      them. This is the load-bearing case the routing contract must handle.
    - Keep the Step-1c branch-structure deferral as **plain scope prose**, the
      same way the split-agent finding-prefix deferral is handled — state that
      whether Step 1c renders the two single-track resume cases as one collapsed
      `design_gate`-keyed branch or two separate branches is a Phase-B rendering
      choice, both satisfying the D10 contract. Do **not** record this as a
      decision/invariant with an "A10" anchor; it is a non-load-bearing scope
      note grounded in D10, not a new fork.
