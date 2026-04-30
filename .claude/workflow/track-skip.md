# Track Skip Protocol

A track can be skipped (`[~]`) in two situations:

1. **Phase A review recommends skip** — a review sub-agent returns a `skip`
   severity finding (e.g., "functionality already exists," "prior track made
   this redundant"). The agent presents the finding to the user.
2. **User requests skip** — the user overrides at session start or during
   strategy refresh (e.g., "skip Track 4, we don't need it anymore").

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
   `conventions-execution.md` §"After strategy refresh" — this process
   step must not diverge from that rule.

   The skip record replaces both the track episode and step file
   reference. It must include enough context for strategy refresh to
   assess downstream impact.

3. **Remove Track N's section from `implementation-backlog.md`**.
   Delete per the "Backlog section body extraction rule" in
   `conventions-execution.md` §2.1 — that rule states the
   header-boundary algorithm (and the line-count-deletion
   prohibition) once as the single authoritative source. Preserve
   the backlog's opening `# <Feature> — Track Details` header.
   No-op if the section is already gone.

   When the last remaining `## Track M:` section is removed, leave
   the backlog file on disk with only its header — same empty-backlog
   final-state rule that Phase A follows. Natural cleanup happens
   when the branch is deleted after PR merge.

   **Backlog deletion is terminal.** Un-skipping a track via inline
   replanning requires re-authoring the plan entry's description
   from scratch; once a track has been skipped, the backlog is no
   longer a recovery source for it.

4. **Delete the step file** (`tracks/track-N.md`) from disk if one
   exists (e.g., Phase A created it before the skip was decided).

5. **Delete review files** (`reviews/track-N-*.md`) from disk if any
   exist.

6. **Strategy refresh** treats `[~]` tracks the same as `[x]` tracks
   for State A detection (see workflow.md §Startup Protocol). A
   skipped track's `**Skipped:**` line serves as its episode — the
   next session's strategy refresh reads it to assess downstream
   impact on remaining tracks. After strategy refresh, a
   `**Strategy refresh:**` line is written under the `[~]` track's
   block, just like for `[x]` tracks. This is required for State B
   detection to work correctly.

---

## When skip happens during Phase A

If the skip is decided during Phase A (review sub-agent recommends it and
user confirms):

- Write the `[~]` marker and skip record to the plan file on disk
- Remove Track N's section from `implementation-backlog.md` (no-op
  if the section is already gone)
- Delete any partially-created step file and review files from disk
- The session continues: if strategy refresh was already done, proceed to
  the next `[ ]` track's Phase A. If no more tracks remain, proceed to
  Phase 4 detection.

---

## When skip happens at session start (user override)

If the user says "skip Track N" at session start:

- Write the `[~]` marker and skip record on disk (user provides the reason)
- Remove Track N's section from `implementation-backlog.md` (no-op
  if the section is already gone)
- Delete any step file and review files for that track from disk
- Continue with normal startup protocol for the next track
