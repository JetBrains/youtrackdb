# Track Skip Protocol

A track can be skipped (`[~]`) in two situations:

1. **Phase A review recommends skip** — a review sub-agent returns a `skip`
   severity finding (e.g., "functionality already exists," "prior track made
   this redundant"). The agent presents the finding to the user.
2. **User requests skip** — the user overrides at session start or during
   the Track Pre-Flight gate (e.g., "skip Track 4, we don't need it
   anymore"). When a skip is requested via a Pre-Flight review-mode
   `SKIP_TRACK` item (see [`review-mode.md`](review-mode.md)
   § Action types — `SKIP_TRACK` carries `{track_index, reason}`
   and on Apply runs the full Process below):
   - If the skip would shift which track is "next", re-render
     Panel 2 against the new upcoming track per the gate's
     reordering rule.
   - If the skipped track was the upcoming track summarised in
     Panel 2 (the most common case), the just-skipped track now
     becomes the new look-back anchor and Panel 1 must also re-run
     its strategy assessment against the remaining tracks, treating
     the in-progress skip's `**Skipped:**` reason as the just-
     skipped-track signal. Re-render both panels before re-asking.

   See [`track-review.md`](track-review.md) § Track Pre-Flight for
   the full panel-rendering contract.

---

## Process

1. **Get user confirmation.** A track is never skipped autonomously — the
   agent always presents the rationale and waits for the user to confirm.
   Even when a review sub-agent recommends `skip`, the user decides.

2. **Write a skip record** to the plan file on disk and mark the track
   `[~]`:

   ```markdown
   - [~] Track N: <title>
     > <intro paragraph>
     >
     > **Skipped:** <reason — e.g., "Prior track already implemented the
     > histogram reader; this track's scope is fully covered.">
   ```

   The plan entry holds only the intro paragraph. The track file's
   `## Purpose / Big Picture` (intro paragraph), `## Context and
   Orientation`, `## Plan of Work`, `## Interfaces and Dependencies`,
   and any track-level Mermaid diagram (which lives in `## Context
   and Orientation`) live in the track file (`plan/track-N.md`) and
   are removed in step 3 below.

   The authoritative retention rule for `[~]` entries lives in
   step 4 below — this process step must not diverge from that rule.

   The skip record replaces both the track episode and track file
   reference. It must include enough context for the next session's
   Track Pre-Flight Panel 1 (strategy assessment) to assess
   downstream impact.

3. **Delete the track file** (`plan/track-N.md`) from disk if one
   exists. The track file was created at Phase 1 with its
   `## Purpose / Big Picture` (intro paragraph), `## Context and
   Orientation`, `## Plan of Work`, and `## Interfaces and
   Dependencies` populated, so it is the only on-disk artifact
   carrying the per-track detail; the delete drops both that detail
   and any reviews / decomposition / step episodes that may have
   accumulated during Phase A or B.

   **Track-file deletion is terminal.** Un-skipping a track via inline
   replanning requires re-authoring the track file's `## Purpose /
   Big Picture` (intro paragraph), `## Context and Orientation`,
   `## Plan of Work`, and `## Interfaces and Dependencies` from
   scratch; once a track has been skipped, the track file is no
   longer a recovery source for it.

4. **Track Pre-Flight (Panel 1 strategy assessment)** treats `[~]`
   tracks the same as `[x]` tracks. A skipped track's `**Skipped:**`
   line serves as its episode — the next session's Pre-Flight gate
   reads it as the just-skipped-track signal in Panel 1 to assess
   downstream impact on remaining tracks. After the gate clears, a
   `**Strategy refresh:**` line is written under the `[~]` track's
   block, just like for `[x]` tracks. The line is the audit record
   of the assessment and is preserved by the Pre-Flight gate's
   resume idempotency rule on subsequent re-entries.

   **Retention rule for `[~]` entries.** The plan entry retains the
   intro paragraph, the `**Skipped:**` line, and the
   `**Strategy refresh:**` line indefinitely. It is **never trimmed
   by the Phase C collapse pass**: Phase C collapse only runs against
   `[x]` tracks at track completion (see
   [`track-code-review.md`](track-code-review.md) § Track Completion
   step 4); skipped tracks bypass that pass entirely. The track
   file's `## Purpose / Big Picture` (intro paragraph), `## Context
   and Orientation`, `## Plan of Work`, `## Interfaces and
   Dependencies`, and any track-level Mermaid diagram (which lived
   in `## Context and Orientation`) lived in the track file and were
   removed in step 3 above; they are not recoverable from the plan
   entry once a track has been skipped.

---

## When skip happens during Phase A

If the skip is decided during Phase A (review sub-agent recommends it and
user confirms):

- Write the `[~]` marker and skip record to the plan file on disk
- Delete `plan/track-N.md` from disk (no-op if it has already been
  removed; for tracks reaching this point the track file always exists
  because Phase 1 created it)
- The session continues: if Panel 1 of the Track Pre-Flight gate was
  already cleared in this session, proceed to the next `[ ]` track's
  Phase A. If the skip changed which track is "next", re-render
  Panel 2 of the Pre-Flight gate against the new upcoming track. If
  no more tracks remain, proceed to Phase 4 detection.

---

## When skip happens at session start (user override)

If the user says "skip Track N" at session start:

- Write the `[~]` marker and skip record on disk (user provides the reason)
- Delete `plan/track-N.md` from disk (no-op if it has already been
  removed)
- Continue with normal startup protocol for the next track
