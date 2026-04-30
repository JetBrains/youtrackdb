# Two-Tier Dimensional Code Review

Load this document when running step-level or track-level code review.
It is **not** needed at session startup.

Code review happens at two levels — step-level and track-level. Both use
review sub-agents selected based on code characteristics (see
[`review-agent-selection.md`](review-agent-selection.md)).

- **Step-level review** runs only on steps tagged `risk: high` per
  [`risk-tagging.md`](risk-tagging.md). For `medium` and `low` steps,
  step-level review is skipped — the step proceeds directly from
  commit to episode, relying on tests plus the always-on track-level
  review for quality assurance.
- **Track-level review** runs at Phase C against the cumulative track
  diff regardless of the per-step risk distribution. The track-level
  reviewers receive the per-step risk tags and treat `medium` and
  `high` step ranges as focal points within the diff.

For both levels:

- **Baseline agents (4)** always run.
- **Conditional agents (up to 6)** are added based on the step/track
  description and changed files.

After all selected agents complete, findings are deduplicated,
severity-assigned (blocker / should-fix / suggestion), and attributed to
source dimension(s). Max 3 iterations per level.

---

## Single-step tracks

**Single-step tracks skip the code review portion of Phase C only when
the single step is `risk: high`** — i.e., step-level dimensional review
already ran against the identical diff. Single-step tracks where the
sole step is `medium` or `low` still run track-level code review at
Phase C, since step-level was skipped under the risk-gating rule above.
Phase C track completion (episode, user approval) runs in both cases.
See [`track-code-review.md`](track-code-review.md) §Single-Step Track.

---

## Where each level is implemented

- **Step-level:** see [`step-implementation.md`](step-implementation.md)
  §Per-Step Workflow (sub-step 4).
- **Track-level:** see [`track-code-review.md`](track-code-review.md)
  (includes track completion).

---

## Iteration protocol

The iteration protocol (max 3 iterations, cumulative finding IDs,
severity levels, gate verification format) is shared with other reviews
— see [`review-iteration.md`](review-iteration.md).
