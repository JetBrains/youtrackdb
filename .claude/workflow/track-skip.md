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

2. **Write a skip record** to the plan file and mark the track `[~]`
   (single commit):

   ```markdown
   - [~] Track N: <title>
     > <description>
     >
     > **Skipped:** <reason — e.g., "Prior track already implemented the
     > histogram reader; this track's scope is fully covered.">
   ```

   The skip record replaces both the track episode and step file reference.
   It must include enough context for strategy refresh to assess downstream
   impact.

3. **Delete the step file** (`tracks/track-N.md`) if one exists (e.g.,
   Phase A created it before the skip was decided). Include the deletion
   in the same commit.

4. **Delete review files** (`reviews/track-N-*.md`) if any exist. Include
   in the same commit.

5. **Strategy refresh** treats `[~]` tracks the same as `[x]` tracks when
   checking for a pending strategy refresh (State A detection). A skipped
   track's `**Skipped:**` line serves as its episode — the next session's
   strategy refresh reads it to assess downstream impact on remaining tracks.

---

## When skip happens during Phase A

If the skip is decided during Phase A (review sub-agent recommends it and
user confirms):

- Write the `[~]` marker and skip record to the plan file
- Delete any partially-created step file and review files
- Commit everything together
- The session continues: if strategy refresh was already done, proceed to
  the next `[ ]` track's Phase A. If no more tracks remain, proceed to
  Phase 4 detection.

---

## When skip happens at session start (user override)

If the user says "skip Track N" at session start:

- Write the `[~]` marker and skip record (user provides the reason)
- Delete any step file and review files for that track
- Commit
- Continue with normal startup protocol for the next track
