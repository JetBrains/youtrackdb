# Review Iteration Protocol

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Limits | orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track | 2,3A,3B,3C | Max 3 iterations per review type; escalate if blockers persist (Phase-C track review overrides). |
| §Finding ID prefixes | orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track | 2,3A,3B,3C | The cumulative finding-ID prefix family across plan, technical, code, test, and workflow review types. |
| §Severity levels | orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track | 2,3A,3B,3C | The blocker/should-fix/suggestion/skip severities; skip is valid only in track reviews. |
| §Iteration flow | orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track | 2,3A,3B,3C | Full review then gate-check then escalate; re-run the full review if fixes restructure the plan. |
| §Finding format | reviewer-plan,reviewer-dim-step,reviewer-dim-track | 2,3A,3B,3C | The per-finding output shape: ID, severity, Location, Issue, Proposed fix. |
| §Gate verification output | orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track | 2,3A,3B,3C | Per-finding verdict (VERIFIED/STILL OPEN/REJECTED) plus a PASS/FAIL summary at gate-check time. |
| §Dimensional-review gate-check budget | orchestrator,reviewer-dim-step,reviewer-dim-track | 3B,3C | The ≤60-line gate-check budget enforced by the dimensional-review gate-check prompt template. |
| §Gate-check verdict handling | orchestrator | 3B,3C | How to route each gate-check verdict: VERIFIED/REJECTED/MOOT clear, STILL OPEN carries forward, REGRESSION fails. |
| §Gate-check synthesis routing | orchestrator | 3B,3C | Re-run the synthesis recipe over all gate-check returns before composing the next iteration's implementer input. |
| §Gate-check budget enforcement is best-effort | orchestrator | 3B,3C | The ≤60-line cap is a steering signal, not a hard gate; accept over-budget reports and continue. |
| §Forbidden sections at gate-check time | reviewer-dim-step,reviewer-dim-track | 3B,3C | Sections forbidden in a gate-check report: reviewer notes, methodology, multi-paragraph summary, verbose subsections. |
| §Why the gate-check budget matters (YTDB-696) | orchestrator | 3B,3C | Why the cap exists: dense gate-check reports dominate context burn; stripping verbose sections recovers most of it. |
| §Out of scope: structural / Phase A review-gate / consistency gates | orchestrator | 2,3A | The structural, Phase A review-gate, and consistency gate flows keep their own terse prompt formats. |

<!--Document index end-->

Shared by structural review (structural-review.md:reviewer-plan:2),
track pre-execution reviews (track-review.md:orchestrator,reviewer-technical,reviewer-risk,reviewer-adversarial:3A), and
track-level code review (track-code-review.md:orchestrator,reviewer-dim-track:3C).

Load this document when running any of those review loops. It is **not**
needed at session startup.

> **House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `house-style.md`: `## Banned sentence patterns`, `## Banned analysis patterns`, `## Orientation`, and `## Plain language`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See conventions.md:any:any `§1.5` for the workflow-level anchor and tier mapping.

---

## Limits
<!-- roles=orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track phases=2,3A,3B,3C summary="Max 3 iterations per review type; escalate if blockers persist (Phase-C track review overrides)." -->

- Max 3 iterations per review type.
- If blockers persist after 3 iterations, escalate.
- **Phase-C exception.** Phase-C track code review overrides this per
  track-code-review.md:orchestrator:3C §Review loop: its iteration depth is
  keyed to the per-track complexity tag with no fixed cap (blockers loop
  until clear at every level; should-fix scales with the tag — `low` none,
  `medium` up to three, `high` uncapped), and it terminates by no-progress
  detection rather than the fixed cap above. The cap-3-then-escalate default
  stated here stays in force for Phase-2 plan reviews, Phase-3A track
  reviews, and Phase-3B step reviews.

---

## Finding ID prefixes
<!-- roles=orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track phases=2,3A,3B,3C summary="The cumulative finding-ID prefix family across plan, technical, code, test, and workflow review types." -->

Finding IDs are cumulative across iterations (e.g., `T1`, `T2`, ... — not
reset between iterations).

| Prefix | Review type |
|---|---|
| `CR` | Consistency review |
| `S` | Structural review |
| `T` | Technical review |
| `R` | Risk review |
| `A` | Adversarial review |
| `CQ` | Code quality review |
| `BG` | Bugs review |
| `CN` | Concurrency review |
| `CS` | Crash safety review |
| `SE` | Security review |
| `PF` | Performance review |
| `TB` | Test behavior review |
| `TC` | Test completeness review |
| `TS` | Test structure review |
| `TX` | Test concurrency review |
| `TY` | Test crash safety review |
| `WC` | Workflow consistency review |
| `WP` | Workflow prompt design review |
| `WI` | Workflow instruction completeness review |
| `WH` | Workflow hook safety review |
| `WB` | Workflow context budget review |
| `WS` | Workflow writing style review |

## Severity levels
<!-- roles=orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track phases=2,3A,3B,3C summary="The blocker/should-fix/suggestion/skip severities; skip is valid only in track reviews." -->

**blocker** / **should-fix** / **suggestion** / **skip** (the `skip` severity
is only valid in track reviews — recommends skipping the entire track).

---

## Iteration flow
<!-- roles=orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track phases=2,3A,3B,3C summary="Full review then gate-check then escalate; re-run the full review if fixes restructure the plan." -->

```
Iteration 1: Full review -> findings -> decisions -> apply fixes
Iteration 2: Gate check -> verify fixes + catch regressions -> if blockers, loop
Iteration 3: Gate check -> if still blockers, escalate
```

The cap-3 escalation in the diagram above is the default for Phase-2 plan
reviews, Phase-3A track reviews, and Phase-3B step reviews. Phase-C track
code review overrides it per track-code-review.md:orchestrator:3C §Review
loop — no fixed iteration cap, terminated by no-progress detection (see the
§Limits Phase-C exception above).

If structural fixes significantly restructure the plan (tracks reordered,
scope indicators changed substantially), re-run the full review instead of
the gate check to catch cascading issues.

---

## Finding format
<!-- roles=reviewer-plan,reviewer-dim-step,reviewer-dim-track phases=2,3A,3B,3C summary="The per-finding output shape: ID, severity, Location, Issue, Proposed fix." -->

```markdown
### Finding <PREFIX><N> [blocker|should-fix|suggestion|skip]
**Location**: <where in the plan or codebase>
**Issue**: <what's wrong>
**Proposed fix**: <concrete change>
```

## Gate verification output
<!-- roles=orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track phases=2,3A,3B,3C summary="Per-finding verdict (VERIFIED/STILL OPEN/REJECTED) plus a PASS/FAIL summary at gate-check time." -->

For each previous finding: **VERIFIED**, **STILL OPEN** (with explanation),
or **REJECTED** (no action needed). New findings (if any) with cumulative
numbering. Summary: **PASS** or **FAIL**.

### Dimensional-review gate-check budget
<!-- roles=orchestrator,reviewer-dim-step,reviewer-dim-track phases=3B,3C summary="The ≤60-line gate-check budget enforced by the dimensional-review gate-check prompt template." -->

Dimensional code-review gate-checks (the re-runs spawned at
`step-implementation.md` §Per-Step Orchestration Loop sub-step 4(d) and
`track-code-review.md` §Review loop step 3, for every dimensional
review agent listed in
review-agent-selection.md:orchestrator:3A,3B,3C) **MUST** be
spawned with the prompt template at
prompts/dimensional-review-gate-check.md:reviewer-dim-step,reviewer-dim-track:3B,3C.
That template enforces:

- **Total budget: ≤ 60 lines** of output per re-spawned agent.
- One verdict line per open finding (`VERIFIED` / `REJECTED` /
  `MOOT` / `STILL OPEN` / `REGRESSION`), each ≤ 1 line of
  justification.
- **≤ 3 new findings**, each ≤ 5 lines total. Surface regressions or
  obvious misses directly tied to the listed open findings; do not
  re-survey the diff.
- One-line `PASS` / `FAIL` summary.

### Gate-check verdict handling
<!-- roles=orchestrator phases=3B,3C summary="How to route each gate-check verdict: VERIFIED/REJECTED/MOOT clear, STILL OPEN carries forward, REGRESSION fails." -->

The orchestrator processes each gate-check verdict as follows:

- `VERIFIED` — finding clears for this iteration; do not pass it to
  the next implementer spawn.
- `REJECTED` — the reviewer on re-read concluded the original
  finding was not a real issue (misread, false positive). Clears
  identically to `VERIFIED`; do not pass to the next implementer. The
  per-dimension gate-check verdict is itself the record of the recant
  (see finding-synthesis-recipe.md:orchestrator:3B,3C
  §Verdict-to-action mapping), so the next reviewer instance keeps its
  own `id` numbering and does not re-raise the same finding. There is
  no separate audit-trail entry to mint — the `M<n>` merge layer that
  once carried a `REJECTED-VERDICT` entry is gone.
- `MOOT` — finding is no longer reachable (file deleted, code moved,
  approach changed). Clears for loop-termination purposes, identical
  to `VERIFIED`; do not pass to the next implementer.
- `STILL OPEN` — carry the finding into the next iteration's
  implementer input verbatim, with the same finding ID.
- `REGRESSION` — escalate as a blocker. Pass the regression's
  `file:line` plus the original finding ID to the next implementer
  with explicit `revert-or-repair` instructions: either revert the
  change that caused the regression and find a different approach to
  the original finding, or fix the regression in place if the new
  approach is sound. A `REGRESSION` forces the iteration `FAIL` even
  if every other verdict is `VERIFIED`.

### Gate-check synthesis routing
<!-- roles=orchestrator phases=3B,3C summary="Re-run the synthesis recipe over all gate-check returns before composing the next iteration's implementer input." -->

After collecting gate-check returns from all re-spawned agents,
re-run the canonical routing procedure in
finding-synthesis-recipe.md:orchestrator:3B,3C before
composing the next iteration's implementer input. The recipe's
§"Gate-check routing" section maps the five verdicts
(`VERIFIED` / `REJECTED` / `MOOT` / `STILL OPEN` / `REGRESSION`) to
forward / drop actions by reviewer `id`, then re-runs the aggregated
`New findings` blocks through the `loc`-collapse, the upgrade-only
`basis` backstop, and bucketing as for any initial routing. The
`loc`-collapse groups co-located findings across dimensions and the
pre-spawn budget guard (~15 findings) bounds the in-scope set, so the
per-agent ≤ 3 new-findings cap × N agents cannot silently inflate the
total. The routing applies identically at both review levels —
track-level re-enters from track-code-review.md:orchestrator:3C
§Review loop, step-level from
step-implementation.md:orchestrator:3B §Per-Step
Orchestration Loop sub-step 4(d) (gate-check collection), which
re-enters sub-step 4(b) for routing.

### Gate-check budget enforcement is best-effort
<!-- roles=orchestrator phases=3B,3C summary="The ≤60-line cap is a steering signal, not a hard gate; accept over-budget reports and continue." -->

The ≤ 60-line cap is asked of the sub-agent but **not enforced** by
the orchestrator. If a returned report exceeds the budget, accept
the over-budget output and continue; do not retry, re-spawn, or
block. The cap is a steering signal, not a hard gate. Recurring
over-budget reports for a specific dimension across multiple
sessions are feedback to tune that agent file's default Output
Format, not a blocker for the current track.

### Forbidden sections at gate-check time
<!-- roles=reviewer-dim-step,reviewer-dim-track phases=3B,3C summary="Sections forbidden in a gate-check report: reviewer notes, methodology, multi-paragraph summary, verbose subsections." -->

The following sections, present in every dimensional review agent's
default Output Format, are **forbidden** at gate-check time:

- `Reviewer notes`
- `Files of interest`, `Scope`, `What I read`
- `Reference-Accuracy Audit` (the PSI-vs-grep caveat rides on the
  affected verdict line as `(grep-only)`)
- `Methodology`, `Process`, `Hypothesis tracking`, `Observations`
- Per-finding `Evidence:` / `Refutation considered:` / `Suggested fix:`
  subsections beyond the single-line shapes in the template
- Multi-paragraph `Summary` prose

### Why the gate-check budget matters (YTDB-696)
<!-- roles=orchestrator phases=3B,3C summary="Why the cap exists: dense gate-check reports dominate context burn; stripping verbose sections recovers most of it." -->

A Phase C session that runs 8 dimensional reviewers plus a 5-agent
gate-check fan-out routinely pushes the orchestrator's context past
the 30 % `warning` threshold before iter-2 begins, forcing a
mid-Phase-C session pause. The dense 100–300-line gate-check reports
are the dominant burn; the verbose sections listed above carry
almost no signal at gate-check time because the orchestrator already
has the original finding text. Stripping them recovers ~70 % of the
gate-check token budget.

### Out of scope: structural / Phase A review-gate / consistency gates
<!-- roles=orchestrator phases=2,3A summary="The structural, Phase A review-gate, and consistency gate flows keep their own terse prompt formats." -->

The other gate-verification flows are out of scope for this budget:
structural review
(prompts/structural-gate-verification.md:reviewer-plan:2),
Phase A review gate
(prompts/review-gate-verification.md:orchestrator:3A),
and consistency gate
(prompts/consistency-gate-verification.md:reviewer-plan:2).
Each has its own dedicated prompt file with a per-finding
verification certificate format that is already terse; do not
retrofit the dimensional-review template onto them.
