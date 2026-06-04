# Two-Tier Dimensional Code Review

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Single-step tracks | orchestrator | 3C | When a single-step track skips Phase C code review (only if the sole step is risk:high). |
| §Where each level is implemented | orchestrator | 3B,3C | Pointers to the step-level and track-level review implementations and their fix-delegation paths. |
| §Iteration protocol | orchestrator | 3A,3B,3C | The shared review iteration protocol (max 3 iterations, finding IDs, gate verification). |

<!--Document index end-->

Load this document when running step-level or track-level code review.
It is **not** needed at session startup.

Code review happens at two levels — step-level and track-level. Both use
review sub-agents selected based on code characteristics (see
review-agent-selection.md:orchestrator:3A,3B,3C).

- **Step-level review** runs only on steps tagged `risk: high` per
  risk-tagging.md:decomposer,orchestrator:3A,3B. For `medium` and `low` steps,
  step-level review is skipped — the step proceeds directly from
  commit to episode, relying on tests plus the always-on track-level
  review for quality assurance.
- **Track-level review** runs at Phase C against the cumulative track
  diff regardless of the per-step risk distribution. The track-level
  reviewers receive the per-step risk tags and treat `medium` and
  `high` step ranges as focal points within the diff.

The baseline agents differ by level. At a **step**, only
`review-bugs-concurrency` runs from the baseline group; the other three
baselines defer to the track pass. At a **track**, all four baselines
run. Both selections sit under the baseline-skip override: a
workflow-only or `docs-only`+workflow diff skips the whole baseline
group at either level (see the override in
`review-agent-selection.md` §Workflow-review agents, and
§Step-level vs track-level routing for the step/track split).

For both levels:

- **Conditional agents (up to 6)** are added based on the step/track
  description and changed files.
- **Workflow-review agents (up to 6)** are added when changed files
  include workflow-machinery (`.claude/`, root `CLAUDE.md`, or
  `docs/adr/<dir>/`); see `review-agent-selection.md` §Workflow-review
  agents.

After all selected agents complete, findings are synthesised per
finding-synthesis-recipe.md:orchestrator:3B,3C:
deduplicated, severity-assigned (blocker / should-fix / suggestion),
and attributed to source dimension(s). Max 3 iterations per level.

---

## Single-step tracks
<!-- roles=orchestrator phases=3C summary="When a single-step track skips Phase C code review (only if the sole step is risk:high)." -->

**Single-step tracks skip the code review portion of Phase C only when
the single step is `risk: high`** — i.e., step-level dimensional review
already ran against the identical diff. Single-step tracks where the
sole step is `medium` or `low` still run track-level code review at
Phase C, since step-level was skipped under the risk-gating rule above.
Phase C track completion (episode, user approval) runs in both cases.
See track-code-review.md:orchestrator:3C §Single-Step Track.

---

## Where each level is implemented
<!-- roles=orchestrator phases=3B,3C summary="Pointers to the step-level and track-level review implementations and their fix-delegation paths." -->

- **Step-level:** see step-implementation.md:orchestrator:3B
  §Per-Step Orchestration Loop sub-step 4 — the dimensional review
  loop, run by the orchestrator on the implementer's commit. Fixes
  for findings are delegated to a respawn of the per-step
  implementer at `level=step, mode=FIX_REVIEW_FINDINGS`.
- **Track-level:** see track-code-review.md:orchestrator:3C
  (includes track completion). Fixes for findings are delegated to a
  fresh per-iteration implementer at
  `level=track, mode=FIX_REVIEW_FINDINGS`; the orchestrator never
  edits source files itself in Phase C.

---

## Iteration protocol
<!-- roles=orchestrator phases=3A,3B,3C summary="The shared review iteration protocol (max 3 iterations, finding IDs, gate verification)." -->

The iteration protocol (max 3 iterations, cumulative finding IDs,
severity levels, gate verification format) is shared with other reviews
— see review-iteration.md:orchestrator:3A,3B,3C.
