# Two-Tier Dimensional Code Review

Load this document when running step-level or track-level code review.
It is **not** needed at session startup.

Code review happens at two levels — step-level and track-level — using
review sub-agents selected based on code characteristics (see
[`review-agent-selection.md`](review-agent-selection.md)).

- **Baseline agents (4)** always run.
- **Conditional agents (up to 6)** are added based on the step/track
  description and changed files.

After all selected agents complete, findings are deduplicated,
severity-assigned (blocker / should-fix / suggestion), and attributed to
source dimension(s). Max 3 iterations per level.

---

## Single-step tracks

**Single-step tracks skip the code review portion of Phase C** — the
step-level review already covered the identical diff. Phase C still runs
for track completion (episode, user approval). See
[`track-code-review.md`](track-code-review.md) §Single-Step Track.

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
