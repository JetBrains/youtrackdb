# Track Skip Protocol

A track can be skipped (`[~]`) in two situations:

1. **Phase A review recommends skip** — a review sub-agent returns a `skip`
   severity finding (e.g., "functionality already exists," "prior track made
   this redundant"). The agent presents the finding to the user.
2. **User requests skip** — the user overrides at session start or during
   the Track Pre-Flight gate (e.g., "skip Track 4, we don't need it
   anymore"). When a skip is requested in the Pre-Flight gate's `Adjust`
   loop and the request would shift which track is "next", re-render
   Panel 2 against the new upcoming track per the gate's reordering
   rule (see [`track-review.md`](track-review.md) § Track Pre-Flight).

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

   The plan entry holds only the intro paragraph; the
   `**What/How/Constraints/Interactions**` subsections and any
   track-level diagram lived in `implementation-backlog.md` and are
   removed in step 3 below.

   The authoritative retention rule for `[~]` entries lives in
   step 5 below — this process step must not diverge from that rule.

   The skip record replaces both the track episode and step file
   reference. It must include enough context for the next session's
   Track Pre-Flight Panel 1 (strategy assessment) to assess
   downstream impact.

3. **Remove Track N's section from `implementation-backlog.md`**.
   Delete per the "Backlog section body extraction rule" in
   `conventions-execution.md` §2.1 — that rule states the
   header-boundary algorithm (and the line-count-deletion
   prohibition) once as the single authoritative source. Preserve
   the backlog's opening `# <Feature> — Track Details` header.
   No-op if the section is already gone.

   When the last remaining `## Track M:` section is removed, leave
   the backlog file on disk with only its header — same empty-backlog
   final-state rule that Phase A follows. The whole `_workflow/`
   directory is removed by the Phase 4 cleanup commit before merge.

   **Backlog deletion is terminal.** Un-skipping a track via inline
   replanning requires re-authoring the plan entry's description
   from scratch; once a track has been skipped, the backlog is no
   longer a recovery source for it.

4. **Delete the step file** (`tracks/track-N.md`) from disk if one
   exists (e.g., Phase A created it before the skip was decided).

5. **Track Pre-Flight (Panel 1 strategy assessment)** treats `[~]`
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
   by the Phase C collapse pass** — Phase C collapse only runs against
   `[x]` tracks at track completion (see
   [`track-code-review.md`](track-code-review.md) § Track Completion
   step 4); skipped tracks bypass that pass entirely. The
   `**What/How/Constraints/Interactions**` subsections and any
   track-level diagram lived in `implementation-backlog.md` and were
   removed in step 3 above; they are not recoverable from the plan
   entry once a track has been skipped.

---

## When skip happens during Phase A

If the skip is decided during Phase A (review sub-agent recommends it and
user confirms):

- Write the `[~]` marker and skip record to the plan file on disk
- Remove Track N's section from `implementation-backlog.md` (no-op
  if the section is already gone)
- Delete any partially-created step file from disk
- The session continues: if Panel 1 of the Track Pre-Flight gate was
  already cleared in this session, proceed to the next `[ ]` track's
  Phase A. If the skip changed which track is "next", re-render
  Panel 2 of the Pre-Flight gate against the new upcoming track. If
  no more tracks remain, proceed to Phase 4 detection.

---

## When skip happens at session start (user override)

If the user says "skip Track N" at session start:

- Write the `[~]` marker and skip record on disk (user provides the reason)
- Remove Track N's section from `implementation-backlog.md` (no-op
  if the section is already gone)
- Delete any step file for that track from disk
- Continue with normal startup protocol for the next track
