# Adversarial review — research log (Phase 0→1), iteration 2

```yaml
review_type: adversarial
scope: research-log (Phase 0→1)
target: docs/adr/reviewers-loose/_workflow/research-log.md
iteration: 2
verdict: PASS
findings: 1
blockers: 0
should_fix: 0
suggestion: 1
matched_categories: [Workflow machinery]
index:
  - id: A1
    prior: true
    verdict: VERIFIED
    sev: blocker
    anchor: "### A1"
    loc: "research-log.md Decision Log 16:20Z (opt-out)"
    cert: "Decision: §1.7(k) opt-out vs staging"
    basis: "conventions.md:1362-1376 opt-out criteria; review-agent-selection.md:145-155; risk-tagging.md:285-309"
  - id: A2
    prior: true
    verdict: VERIFIED
    sev: should-fix
    anchor: "### A2"
    loc: "research-log.md Surprises 16:01Z (self-trap)"
    cert: "Assumption: single step is unavoidably high"
    basis: "risk-tagging.md:285-309; review-agent-selection.md:145-155"
  - id: A3
    prior: true
    verdict: VERIFIED
    sev: should-fix
    anchor: "### A3"
    loc: "research-log.md Decision Log 16:20Z (why staging rejected)"
    cert: "Decision: opt-out self-applies the fix"
    basis: "conventions.md:1348-1360; research-log.md:30-34"
  - id: A4
    prior: true
    verdict: VERIFIED
    sev: should-fix
    anchor: "### A4"
    loc: "research-log.md Open Questions 16:01Z (RESOLVED); Decision Log 16:12Z"
    cert: "Open-question: tier minimal vs lite"
    basis: "research-log.md:24-25,56-57"
  - id: A5
    prior: true
    verdict: VERIFIED
    sev: suggestion
    anchor: "### A5"
    loc: "research-log.md Surprises 16:01Z (two edit obligations)"
    cert: "Assumption: edit surface = two obligations"
    basis: "research-log.md:44-49; review-agent-selection.md:104-106"
  - id: A6
    prior: false
    verdict: NEW
    sev: suggestion
    anchor: "### A6"
    loc: "research-log.md Decision Log 16:20Z eligibility constraint; conventions.md:1362-1372"
    cert: "Assumption: the two skip-gate sections are judgment-layer, not execution-procedure"
    basis: "conventions.md:1362-1376; code-review-protocol.md:55-64; track-code-review.md:102-118"
evidence_base:
  - "Read conventions.md §1.7(k)/(l) (1322-1460): opt-out criteria, marker, stamp-advance, criteria re-point."
  - "Read review-agent-selection.md §Step-level routing (95-171): high `.claude/workflow/*.md` step draws zero step reviewers (145-155); SSOT at 104-106; four workflow reviewers defer (138-143)."
  - "Read code-review-protocol.md §Single-step tracks (55-64) and track-code-review.md §Single-Step Track (102-118): the skip premise."
  - "Read risk-tagging.md §Prose-only cap (282-316): cap fails on any gate/dispatch/schema change; §Gate 1 reuse (181-206)."
  - "Read step-implementation.md sub-step 4a (430-450): inline `hook-safety, prompt-design` + `(see §Step-level vs track-level routing)` pointer; NOT edited under opt-out."
  - "Verified all cited line numbers in the log resolve to the claimed text."
```

## Findings

### A1 [blocker]
**Verdict: VERIFIED.**
**Certificate**: Challenge — §1.7(k) opt-out vs staging
**Target**: Decision Log 16:20Z ("Use the §1.7(k) prose-rule opt-out, not staging")

The iter-1 blocker was that staging leaves the live rules buggy by design, and
its only self-trap escape — a ≥2-step decomposition so Phase C's track pass runs
— is not reliably reachable. The author flipped the load-bearing decision to the
§1.7(k) prose-rule opt-out: edit `.claude/workflow/**` live, so the corrected
rule is active before this branch's own Phase C, and the single-step-high track
is reviewed by the very rule it adds. I scrutinized the new opt-out
adversarially, as instructed, against `conventions.md` §1.7(k) criteria 1 and 2.

**Criterion 1 (no `_workflow/**` artifact schema moves) — PASSES.** The three
edited sections are `review-agent-selection.md` §Step-level vs track-level
routing, `code-review-protocol.md` §Single-step tracks, and `track-code-review.md`
§Single-Step Track. None moves a track-file section, a resume-state field, the
drift-gate format, or the stamp format. The edit adds a precondition to a
review-selection rule and reworks a skip premise; it touches no schema any phase
parses. The log's claim at 16:20Z ("the change moves no `_workflow/**` artifact
schema") holds against the actual files.

**Criterion 2 (every edited file's consumer is judgment-layer) — PASSES for the
edited set.** `review-agent-selection.md` §Step-level routing is the declared
single source of truth for which reviewers fire — "review criteria … reviewer
blocks" in §1.7(k)'s judgment-layer list (`conventions.md:1368-1369`). The two
skip-premise sections are review-protocol prose. The one execution-procedure file
in play — `step-implementation.md` sub-step 4a, the step-implementation
orchestration loop, named verbatim in §1.7(k)'s stay-staged list
(`conventions.md:1371`) — is **not** edited. The log's eligibility constraint at
16:20Z correctly excludes it and routes the operative widening into
`review-agent-selection.md`, relying on sub-step 4a's existing
`(see §Step-level vs track-level routing)` pointer (`step-implementation.md:448`),
which I confirmed is present.

**Is the pointer-reliance robust?** Yes. Sub-step 4a does name
`hook-safety, prompt-design` inline (`step-implementation.md:442-443`), but that
line is a *summary of* the routing note, and the routing note is the SSOT the
dispatch consumes ("not re-evaluated at dispatch time", `review-agent-selection.md:104`).
The new single-step-high widening rule lands in the SSOT, so it governs the
dispatch even though the inline summary is unchanged. The inline summary becomes
a partial restatement, not a contradiction: it lists the *default* step-level
workflow reviewers, while the widening rule is a *single-step-track* override that
the SSOT carries. There is a cosmetic staleness risk (the inline summary now
under-describes the single-step-high case), but it is not a correctness gap,
because the dispatch reads the note, not the summary. I record this residual as a
new `suggestion` (A6) rather than re-opening A1, and note that the implementer
should add a one-clause "(single-step-high tracks widen per the note)" hint to
the inline summary if it costs nothing — but it is not a gate.

**The escape-unreachability premise that justifies the flip — VERIFIED.** The log
argues staging's ≥2-step escape is unreachable because a single coherent HIGH
gate/dispatch change cannot be split. I confirmed this against the code:
`track-review.md:832-844` makes coherence mandatory for `high` steps ("file count
alone never forces a split") and isolates each HIGH change in its own step; and
the prose-only cap that would let a second prose step be `low`
(`risk-tagging.md:285-286`) explicitly fails on a "gate/dispatch/schema change"
(`risk-tagging.md:305-309`). So no `low` second step can be carved off a
gate-editing change. The flip's reasoning is sound on the live code.

**Survival test: YES.** The opt-out genuinely qualifies, the self-trap dissolves,
and the escape-unreachability premise that rejects staging is code-grounded. The
iter-1 blocker is resolved.

### A2 [should-fix]
**Verdict: VERIFIED.**
**Certificate**: Assumption test — "a single step implementing this is unavoidably `risk:high`"
**Target**: Surprises 16:01Z ("Self-trap")

Iter-1's A2 was that the self-trap analysis omitted the prose-only-cap /
gate-dispatch reasoning that makes the step unavoidably `high`. The updated
Surprises entry now states it explicitly: "the prose-only cap that would make a
workflow `.md` edit `low` requires 'no gate/dispatch/schema change'
(`risk-tagging.md:285-309`), and this edit changes a gate/dispatch rule … so the
cap does not apply and the step stays HIGH." I verified this against
`risk-tagging.md:285-309` — the qualifier is exactly "no hook/script/settings
change AND no gate/dispatch/schema change," and line 305-309 confirms that a
control-flow-driving prose edit fails the cap. The cited
`review-agent-selection.md:145-155` ("High step editing only `.claude/workflow/*.md`
… draws zero step-level reviewers") also resolves to the claimed text. The
unavoidably-high chain is now fully grounded. Resolved.

### A3 [should-fix]
**Verdict: VERIFIED.**
**Certificate**: Challenge — opt-out self-applies the fix and dissolves the self-trap
**Target**: Decision Log 16:20Z ("Why staging was rejected")

Iter-1's A3 was that the staging-vs-opt-out comparison under-weighted that the
opt-out self-applies the fix and dissolves the self-trap. The flip to the opt-out
makes this the load-bearing decision rather than a footnote: the Decision Log
16:20Z "Why" paragraph now states "editing live makes the corrected rule active
before this branch's own Phase C runs, so the fix self-applies and the
single-step-high self-trap dissolves at the root," and the "Why staging was
rejected" paragraph carries the A1/A3 escape-unreachability argument. This matches
`conventions.md:1356-1360` ("staging … forfeits self-application, leaving the one
branch that rewrites the rules as the single branch never checked against them …
the opt-out trades that forfeited isolation … for self-application"). The
self-application weight is now central, not under-weighted. Resolved.

### A4 [should-fix]
**Verdict: VERIFIED.**
**Certificate**: Open-question challenge — tier `minimal` vs `lite`
**Target**: Open Questions 16:01Z; Decision Log 16:12Z

Iter-1's A4 was that the tier resolution was half-folded — the footprint
justification was stuck in an Open Question marked pending. The updated log
resolves it both ways: the Open Question 16:01Z entry is now headed
"RESOLVED — Tier `minimal` vs `lite`" and points to the Decision Log, and the
Decision Log 16:12Z entry carries the footprint justification verbatim ("four
workflow `.md` files … one cohesive logical unit, well under the single-track
footprint bound (~12 files), so it is single-track and needs no narrative design
doc"). No load-bearing decision now rests on an unresolved Open Question — both
remaining Open-Question entries are RESOLVED markers. The Gate-1-no footprint
claim is the artifact-shedding decision and it now lives in the Decision Log with
`**Why:**`-equivalent reasoning. Resolved.

### A5 [suggestion]
**Verdict: VERIFIED.**
**Certificate**: Assumption test — edit surface collapses two distinct obligations
**Target**: Surprises 16:01Z ("Edit surface — two distinct edit obligations")

Iter-1's A5 was that the single-source-of-truth framing risked collapsing two
distinct edit obligations (widen the selection vs correct the skip premise). The
log now carries a dedicated Surprises entry "Edit surface — two distinct edit
obligations across the workflow files" that splits them: (1) widen the step-level
selection in `review-agent-selection.md` §Step-level routing (the routing SSOT),
(2) correct the false skip premise in `code-review-protocol.md` §Single-step
tracks and `track-code-review.md` §Single-Step Track (the skip gate). I confirmed
the two sites are genuinely distinct: `review-agent-selection.md:104-106` is the
routing note (which reviewers fire), while `code-review-protocol.md:55-64` and
`track-code-review.md:102-118` are the skip gate (whether Phase C fan-out runs).
Both edits are needed and they are not the same edit. Resolved.

### A6 [suggestion]
**Verdict: NEW.**
**Certificate**: Assumption test — the two skip-gate sections are judgment-layer (criterion 2), and the inline 4a summary stays accurate
**Target**: Decision Log 16:20Z eligibility constraint

This is a new, low-severity observation that surfaced while verifying the opt-out
qualification (A1). It does not re-open A1; it records two residuals the
implementer should keep in view.

**Claim**: §1.7(k) criterion 2 holds for all three edited sections.
**Stress scenario**: `code-review-protocol.md §Single-step tracks` and
`track-code-review.md §Single-Step Track` are both annotated
`roles=orchestrator phases=3C` — the orchestrator reads them *during a running
Phase C* to decide whether to skip the review fan-out. That is the shape of a
file "a running phase reads as executable procedure," which criterion 2
(`conventions.md:1368-1372`) forces to stay staged.
**Code evidence**: §1.7(k)'s stay-staged list is "the implementer rulebook's gate
sequence, the step-implementation orchestration loop, the migrate replay"
(`conventions.md:1371`). The hazard the staging rule guards (`conventions.md:1350-1355`)
is "an artifact schema a running phase reads" changing mid-branch — a *schema*, not
a *decision threshold*. The single-step skip is a one-line judgment ("skip iff the
sole step is `risk:high`"); the edit adds a precondition to that judgment without
changing any field the orchestrator parses. The orchestrator re-reads the section
fresh at each Phase C from the live file, so a mid-branch change to the threshold
is read correctly, not parsed against a stale schema. The section is review-protocol
prose — "review criteria" in the judgment-layer list — so the worse reading
(execution-procedure) does not hold.
**Verdict: HOLDS, but FRAGILE on the boundary.** The classification is defensible
and the log's eligibility constraint is correct. The fragility is interpretive,
not mechanical: a future reader could mistake a `phases=3C orchestrator`-tagged
skip-gate for an execution-procedure file. Recommend the implementer add one clause
to the eligibility constraint making explicit *why* the two skip-gate sections are
judgment-layer (they encode a review-decision threshold, not a schema a phase
parses), so the opt-out qualification is self-evident to a Phase-A reviewer who
re-checks it. Second residual: sub-step 4a's inline `hook-safety, prompt-design`
summary (`step-implementation.md:442-443`) will under-describe the single-step-high
case after the SSOT widens. The dispatch reads the SSOT, so this is cosmetic, not a
correctness gap (see A1) — but a one-clause hint on that inline line would prevent a
future reader treating the summary as the authority. Neither residual gates the
research log; both are forward-looking notes for the implementer.

## Evidence base

#### Challenge: §1.7(k) opt-out vs staging (Decision Log 16:20Z)
- **Chosen approach**: Edit `.claude/workflow/**` live under the §1.7(k) prose-rule
  opt-out; the corrected rule self-applies before this branch's own Phase C.
- **Best rejected alternative**: §1.7(b) staging (the iter-1 decision), keeping live
  workflow at develop while staged edits accumulate.
- **Counterargument trace**:
  1. Under staging, the live single-step-high rules stay buggy by design; the only
     escape is a ≥2-step decomposition so Phase C's track pass runs.
  2. `track-review.md:832-844` makes coherence mandatory for `high` and never lets
     file count force a split; `risk-tagging.md:305-309` blocks a `low` second prose
     step on a gate/dispatch change. So the escape is unreachable.
  3. The opt-out makes the corrected rule live before this branch's Phase C, so the
     single-step-high track is reviewed by its own added rule — the self-trap
     dissolves at the root.
- **Codebase evidence**: `conventions.md:1362-1376` (opt-out criteria, both pass:
  no schema moves; consumers are review criteria / reviewer blocks; the one
  execution-procedure file is not edited); `conventions.md:1356-1360`
  (self-application is the opt-out's purpose).
- **Survival test**: YES. Opt-out qualifies; flip is correct.

#### Assumption test: single step is unavoidably `risk:high` (Surprises 16:01Z)
- **Claim**: A single step implementing this change cannot be tagged below `high`.
- **Stress scenario**: Could the decomposer cap the step at `low` via the prose-only
  cap?
- **Code evidence**: `risk-tagging.md:285-286,305-309` — the cap requires "no
  gate/dispatch/schema change"; a gate/dispatch-changing prose edit fails it.
- **Verdict**: HOLDS — the step is unavoidably `high`.

#### Challenge: opt-out self-applies the fix (Decision Log 16:20Z "Why")
- **Chosen approach**: Live edit so the fix is active for this branch's own Phase C.
- **Best rejected alternative**: Staging (defers self-application to develop merge).
- **Counterargument trace**: Staging forfeits self-application
  (`conventions.md:1356-1360`); the branch that rewrites the rules would be the one
  branch never checked against them. The opt-out trades isolation the prose-only
  branch never needed for self-application.
- **Codebase evidence**: `conventions.md:1348-1360`.
- **Survival test**: YES.

#### Open-question challenge: tier `minimal` vs `lite` (Open Questions 16:01Z)
- **Resolution found**: Open Question now headed RESOLVED; footprint justification
  folded into Decision Log 16:12Z (four `.md` files, one cohesive unit, under ~12).
- **Verdict**: No load-bearing decision rests on an open question.

#### Assumption test: two distinct edit obligations (Surprises 16:01Z)
- **Claim**: Widening the selection and correcting the skip premise are one edit.
- **Code evidence**: `review-agent-selection.md:104-106` (routing SSOT — which
  reviewers fire) vs `code-review-protocol.md:55-64` / `track-code-review.md:102-118`
  (skip gate — whether the fan-out runs). Distinct sites, distinct obligations.
- **Verdict**: BREAKS the collapse — the log correctly splits them.

#### Assumption test: skip-gate sections are judgment-layer; 4a summary stays accurate (new, A6)
- **Claim**: All three edited sections satisfy §1.7(k) criterion 2.
- **Stress scenario**: The two skip-gate sections are `phases=3C orchestrator` —
  read during a running Phase C, which looks like execution procedure.
- **Code evidence**: `conventions.md:1350-1355,1368-1372` — the stay-staged set is
  files whose *schema* a phase parses; the skip gate encodes a review-decision
  threshold, re-read fresh from the live file each Phase C, not a parsed schema.
- **Verdict**: HOLDS, FRAGILE on the interpretive boundary — recommend one
  clarifying clause in the eligibility constraint and a one-clause hint on the
  inline sub-step-4a summary.
