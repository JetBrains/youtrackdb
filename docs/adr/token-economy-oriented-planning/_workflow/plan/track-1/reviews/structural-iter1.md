<!-- MANIFEST
review: structural
role: reviewer-plan
phase: 2
track: 1
iteration: 1
findings: 1
verdict: pass-with-findings
index:
  - id: S1
    sev: should-fix
    anchor: "### S1 "
    loc: "design.md §\"Advisory enforcement\" → Edge cases / Gotchas (line 129)"
    cert: ""
evidence_base:
  certs: 0
-->

# Structural review — Track 1 (iteration 1)

Single-track, prose-only, workflow-modifying plan (~3 in-scope files under
`.claude/workflow/`). The under-floor footprint is documented: the track file
states "This is the whole change, so no neighboring track exists to fold into"
and invariant S3 (producer/consumer co-ship) forbids splitting the planner rule
from the reviewer criterion — so the under-floor-with-no-neighbor track passes
the two-sided bound rule. Diagrams are Mermaid and prose-paired (Component Map
flowchart in the plan, Workflow flowchart in `design.md`); no `classDiagram` is
needed since the change introduces no new Java classes. Architecture Notes are
complete (Component Map, D1–D5 each with alternatives/rationale/risks/track-ref,
invariants S1–S3 with testable assertions, Integration Points, Non-Goals). All
DRs trace to Track 1. No `- [ ] Step:` items or `(provisional)` markers leak
into the plan checklist. BLOAT line-counts pass (longest DR ~15 lines, plan file
221 lines). One consistency finding: the design document's byte-identical-set
enumeration lags the corrected plan/track enumeration.

## Findings

### S1 [should-fix]
**Location**: `design.md` §"Advisory enforcement" → Edge cases / Gotchas (line 129). The corrected counterparts live in `implementation-plan.md` invariant S2 (lines 166–174) and `plan/track-1.md` (lines 135–142, 206–215), which already name the full SYNC set.
**Issue**: The design's Edge-case bullet enumerates the byte-identical synchronized set as "The three sizing-rule paraphrases (the technical, risk, and adversarial review prompts) and the two summary sites (the §1.1 glossary, the §1.2 plan summary)." That is the *pre-CR1* narrow set. The authoritative SYNC comment in `prompts/structural-review.md` (lines 56–68) names a larger set: §1.1 glossary, §1.2 plan summary, the create-plan Step 4 rule, **and the Track terminology paraphrase in all five review prompts** — technical, risk, adversarial, **consistency**, and structural-review.md's own bullet. The consistency pass (CR1) already broadened invariant S2 in the plan and the track file to this full set, but the same broadening was not mirrored into the frozen `design.md`. The result is an internal inconsistency: the plan/track invariant S2 (the contract the execution and review agents read) names nine sync sites, while the design Edge case names only five, omitting the consistency-review prompt, structural-review.md's own Track bullet, and the create-plan Step 4 rule. A reader reconciling the two documents gets two different "stay byte-identical" lists.
**Proposed fix**: Broaden the `design.md` line-129 enumeration to match the authoritative SYNC set and the corrected plan/track S2 — name §1.1 glossary, §1.2 plan summary, the create-plan Step 4 sizing rule, and the Track terminology paraphrase in all five review prompts (technical, risk, adversarial, consistency, and structural-review.md's own bullet). Since `design.md` is frozen after Phase 1, **this edit defers to Phase 4** (the `design-final.md` author reconciles it); the plan and track halves are already correct and need no change now. Design Overview line 12 ("the sizing paraphrases in the other review prompts") is unenumerated and stays accurate — only the line-129 Edge case carries the stale count.
**Classification**: mechanical
**Justification**: Per §`mechanical` ("Other findings classify as `mechanical` only when the fix is a single unambiguous edit that doesn't change plan intent") — the fix is the same unambiguous enumeration broadening CR1 already applied to S2; the authoritative set is fixed by the SYNC comment, so there is no design call and no plan-intent change.

## Evidence base
No certificates — this review reads no code (plan-internal structural pass only).
