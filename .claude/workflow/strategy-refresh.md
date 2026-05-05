# Strategy Refresh

Triggered at the **start of a new session** when the previous session
completed or skipped a track (State A in workflow.md §Startup Protocol).

## Process

1. **Read the full plan** with all track episodes accumulated so far.

2. **Assess remaining tracks** against accumulated discoveries:
   - Do any track episodes contradict assumptions in upcoming tracks?
   - Has the Component Map changed in ways affecting remaining tracks?
   - Are any Decision Records weakened by what was learned?
   - Are there new dependencies between tracks not in the original plan?

3. **Produce a strategy refresh report** (presented to the user, not
   persisted):

   ```markdown
   ### Strategy Refresh — After Track <N>

   **Episodes reviewed**: <count>
   **Discoveries with downstream impact**: <list or "none">

   **Assessment**: CONTINUE | ADJUST | ESCALATE

   **Adjustments** (if any):
   - Track M: description needs to account for <discovery>
   - Track P: constraint X no longer applies because <reason>

   **Rationale**: <brief explanation of why this assessment was chosen>
   ```

4. **Present the report to the user.** Wait for the user's decision:

   - **CONTINUE** — no issues found. Proceed to the next track.

   - **ADJUST** — minor fixes needed. Apply adjustments to the plan file
     (update track descriptions, reorder if needed), then proceed to the
     next track. Adjustments must be small and targeted.

     **ADJUST must NOT modify Decision Records.** Decision Records are
     immutable during normal execution — they can only be revised via
     ESCALATE, which triggers inline replanning and structural review
     of the revised plan (see `inline-replanning.md`). If a discovery
     invalidates a Decision Record, that is an automatic ESCALATE.

   - **ESCALATE** — accumulated discoveries have fundamentally changed the
     picture. Enter inline replanning (see `inline-replanning.md`).

5. **Write the `**Strategy refresh:**` line** to the plan file on disk
   under the completed track's block. For CONTINUE, a one-liner suffices.
   For ADJUST, include a brief summary of what was adjusted. ESCALATE
   does not write a strategy refresh line — it triggers replanning
   which restructures the plan directly.

   The strategy refresh line is appended to the same track's block in
   the plan file, after the step file reference:

   ```markdown
   - [x] Track 2: <title>
     > <intro paragraph>
     >
     > **Track episode:**
     > <strategic summary>
     >
     > **Step file:** `tracks/track-2.md` (4 steps, 0 failed)
     >
     > **Strategy refresh:** CONTINUE — no downstream impact detected.
   ```

   For ADJUST, include a brief summary of what was adjusted:

   ```markdown
     > **Strategy refresh:** ADJUST — Track 4 description updated to account
     > for the new `IndexStatistics` API shape discovered during this track.
   ```

   For skipped tracks (`[~]`), the strategy refresh line follows the skip
   record. The plan entry holds only the intro paragraph (the
   `**What/How/Constraints/Interactions**` detail lived in
   `implementation-backlog.md` and `track-skip` removes that section at
   the same time it marks `[~]` — see
   [`track-skip.md`](track-skip.md)). Skipped tracks never go through
   the Phase C collapse, so nothing further trims the plan entry:

   ```markdown
   - [~] Track 3: <title>
     > <description>
     >
     > **Skipped:** <reason>
     >
     > **Strategy refresh:** CONTINUE — no downstream impact from skipping.
   ```

   For how sub-agents see this, see
   [`plan-slim-rendering.md`](plan-slim-rendering.md) — sub-agents
   strip the implementation-detail subsections at prompt-assembly time
   so reviewers get a compact view without changing what's on disk.

   ESCALATE triggers inline replanning (see
   [`workflow.md`](workflow.md) §Inline Replanning), which restructures
   the plan file directly — no strategy refresh line is written.

6. **Proceed** to Phase A of the next track in the same session.

   **Note:** Strategy refresh + Phase A share a single session — this is
   the only exception to mandatory phase boundaries. Strategy refresh is
   lightweight (no code reading, no implementation) and its output directly
   informs Phase A decomposition. After Phase A completes, end the session
   as usual.
