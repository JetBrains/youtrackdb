<!--MANIFEST
dimension: workflow-prompt-design
target: Track 1 (ledger schema, resume routing, Phase-1 artifact existence)
commit_range: 08995c85cf8e98cc1db5029f9dd12e94b0ecc639..HEAD
findings: 2
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WP1
    sev: should-fix
    anchor: "### WP1 "
    loc: ".claude/skills/create-plan/SKILL.md:548-564 (staged copy)"
    cert: n/a
    basis: judgment
  - id: WP2
    sev: suggestion
    anchor: "### WP2 "
    loc: ".claude/skills/create-plan/SKILL.md:160-170 (staged copy)"
    cert: n/a
    basis: judgment
-->

## Findings

### WP1 [should-fix] Step 4 part 3 routes on a "confirmed tier" the re-keyed part-1 classifier no longer produces

- **File:** `docs/adr/track-complexity-assessment-workflow-optimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 548-564; same residual at 619-635, 552-577)
- **Axis:** deterministic decision rules
- **Cost:** non-reproducible routing at the classifier → transition boundary; the runtime LLM must silently invent a design_gate→tier mapping where it previously read a confirmed variable.

**Issue.** Track 1 Step 2 re-keyed **Step 4 part 1** into a pure *design-gate* classifier: it proposes and confirms only `design_gate=yes/no`, and states explicitly (lines 408-413) that Gate 2's multi-vs-single answer is *not* decided up front — the track count is unknowable until the planner decomposes, so plan presence is deferred to the end of Step 4b. But **Step 4 part 3** (line 548, "Per-tier transition to Phase 1") still opens with "After the gate clears, **branch on the confirmed tier**:" and then lists three branches — `full`, `lite`, `minimal` (lines 552-564). No tier is confirmed anywhere upstream after the re-key. The `lite`-vs-`minimal` distinction is precisely the multi-vs-single track count that part 1 says is not yet known at this point, so the routing instruction asks the LLM to branch on a decision variable that (a) was never set and (b) is by the prompt's own admission undecided here.

An LLM reading this cold has to reverse-engineer that `design_gate=yes → full path`, `design_gate=no → {lite|minimal} path`, and that the `lite`/`minimal` split collapses (both run Step 4b only and differ solely by the end-of-4b plan-presence decision). That inference happens to be recoverable — Step 4a is reachable only on `design_gate=yes` (line 629 still gates 4a on "`full` tier only"), and Step 4b treats both no-design shapes identically until the plan-presence decision — but the prompt forces an undocumented mapping in a load-bearing routing position instead of stating it. This is the "branch on a variable the upstream step no longer sets" gap.

The Step 2 episode classifies the file's residual `full`/`lite`/`minimal` vocabulary as "conceptual planning vocabulary that neither reads nor writes the ledger ... left untouched per the step boundaries" and names "Steps 3 and 4" as the re-key home. That classification is fair for descriptive labels (the Step-4a/4b *headers*, the thinned-plan *template comments*), but it does not hold for the part-3 *routing rule*: a decision rule keyed on a now-undefined variable is not "correct under the schema removal." And Steps 3 and 4 re-keyed `conventions.md`, `workflow.md`, and seven other workflow files — they did not touch create-plan/SKILL.md — so the named deferral target never picks this up. Within Track 1's completed work the create-plan classifier→transition handoff is left internally inconsistent.

**Suggestion.** Re-key the part-3 transition to branch on the confirmed `design_gate`, not a tier, mirroring the part-1/4b vocabulary the rest of the re-key uses. Minimal rewrite:

> **Step 4 part 3 — Transition to Phase 1.** After the gate clears, branch on the confirmed design gate:
> - **`design_gate=yes`** — design-first: Step 4a (author + review + freeze `design.md`) then Step 4b, within one `/create-plan` invocation.
> - **`design_gate=no`** — no `design.md`: skip Step 4a, go straight to Step 4b and author the track files (and, per the end-of-4b plan-presence decision, the thinned plan iff more than one track) directly from the research log.

This removes the dependency on the undecided multi/single split at part 3 (it is settled later, at end of 4b, exactly where the plan-presence decision already lives) and keeps the transition rule reading the one variable part 1 actually confirms. If the broader file-wide tier-vocabulary scrub is genuinely deferred to Track 2, the deferral note in the Step 2 episode should name Track 2 (not the already-completed Steps 3/4) and call out that this one part-3 *routing* sentence is the load-bearing exception that cannot wait.

### WP2 [suggestion] Step 1c marker-fan-out: "marker set" arm should name the implied `design_gate` rather than only `phase1_complete`

- **File:** `docs/adr/track-complexity-assessment-workflow-optimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 160-170, 194-205)
- **Axis:** deterministic decision rules
- **Cost:** marginal; a defense-in-depth tightening on the load-bearing collision branch, not a correctness break.

**Issue.** The Step 1c collision branch ("`design.md` exists, `implementation-plan.md` does not") fans out purely on `LEDGER_PHASE1_COMPLETE`: set ⇒ steady state, unset ⇒ crash arm. The disambiguation is correct and deterministic as written. But the branch is *entered* on file presence alone (`design.md` present, no plan), and the prose asserts the steady state is `design_gate=yes, tracks=1` (line 199) without the rule actually checking `LEDGER_DESIGN_GATE` or `LEDGER_TRACKS` on entry. Both fields are parsed at the top of the step (lines 180-182) and then go unused in this branch. The marker alone carries the whole decision.

That is fine when the ledger is well-formed (file presence and the fields agree by construction). The thin edge: a `design.md` on disk with `LEDGER_PHASE1_COMPLETE=yes` but a *blank or `no`* `LEDGER_DESIGN_GATE` (a torn/partial seed, or a stray editor write that created `design.md` outside the workflow) routes to "steady state, do not re-author" on the strength of the marker alone, with no cross-check that the ledger agrees the design was supposed to exist. The branch reads three fields into locals but spends only one.

**Suggestion.** Either (a) add a one-clause cross-check on the marker-set arm — "with `LEDGER_DESIGN_GATE=yes` confirming the ledger agrees a design was authored; a marker-set arm with a blank/`no` design gate is a malformed ledger — surface it to the user rather than treating the stray `design.md` as a steady state" — or (b) state explicitly that the marker is *sufficient* by construction (a clean Phase-1 seed always co-writes `phase1_complete=yes` and `design_gate=yes` on the same line, so the marker implies the gate) and that the parsed `LEDGER_DESIGN_GATE`/`LEDGER_TRACKS` locals are read only by the lower no-design branches. Option (b) is the lighter fix and matches the existing design intent; it just makes the "marker is enough" reasoning explicit so a future editor does not read the unused locals as a missing check.

## Evidence base
