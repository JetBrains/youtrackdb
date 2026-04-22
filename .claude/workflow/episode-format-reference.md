# Episode Format Reference

Detailed episode format rules and examples. Referenced from
`conventions-execution.md` §2.2.

---

## Step completion episode

Recorded in the step file under the completed step item:

```markdown
- [x] Step: <description>
  - [x] Context: <safe|info|warning|critical|unavailable>
  > **What was done:** ...
  > **What was discovered:** ... (when applicable)
  > **What changed from the plan:** ... (when applicable)
  > **Key files:** ...
  > **Critical context:** ... (when applicable)
```

### Episode fields

Episodes are produced by the **execution agent** (step implementation phase)
after it commits the code changes and completes the code review cycle.

| Field | Required | Purpose |
|---|---|---|
| **What was done** | Always | Factual summary of the implementation — files created/modified, approach taken |
| **What was discovered** | When applicable | Unexpected findings about the codebase, APIs, or behavior that weren't anticipated by the plan. This is the most important field — it's the mechanism for adapting to new information |
| **What changed from the plan** | When applicable | Any deviations from the planned approach, and which future steps may be affected. If the deviation is significant, flag specific step IDs |
| **Key files** | Always | Files created or modified, with (new) or (modified) annotations |
| **Critical context** | When applicable | Free-form field for anything essential that doesn't fit the structured fields above — e.g., a fundamental architectural insight, a performance characteristic that changes the approach for the whole feature, or a constraint discovered that affects multiple tracks. Use sparingly; most episodes won't need this |

### Rules

- **"What was discovered" must be filled whenever anything unexpected is
  found** — even if it didn't block the current step. Future sessions and
  track reviews depend on this field to adapt.
- **"What changed from the plan" must name affected future steps** when the
  deviation could impact them. The execution agent uses this to
  adapt remaining steps within the track and across tracks.
- Keep each field concise but complete. A reviewer should understand the
  full step outcome from the episode alone, without reading the diff.
- Episodes are immutable once written. If later work reveals an episode
  was wrong, add a correction note to the later step's episode, don't edit
  the original.

### Minimal episode example (nothing unexpected)

```markdown
- [x] Step: Add histogram header to leaf page structure
  - [x] Context: safe
  > **What was done:** Extended `LeafPage` with 16-byte histogram header.
  > Added serialization/deserialization in `LeafPageSerializer`.
  >
  > **Key files:** `LeafPage.java` (modified), `LeafPageSerializer.java`
  > (modified), `LeafPageHistogramTest.java` (new)
```

When there are no discoveries and no plan deviations, those fields are
simply omitted — no need for "N/A" placeholders.

---

## Step failed episode

```markdown
- [!] Step: <description>
  > **What was attempted:** ...
  > **Why it failed:** ...
  > **Impact on remaining steps:** ...
  > **Key files:** ...
```

When a step implementation phase cannot complete its work (tests won't pass,
coverage can't be met, code reviewer finds fundamental issues, wrong API
assumption), it signals failure. The execution agent reverts uncommitted
changes and produces a failed episode.

The execution agent then decides:
- **Retry** with a different approach
- **Split** the step into smaller pieces that can succeed independently
- **Adjust** upcoming steps to work around the discovered constraint
- **Escalate** if the failure undermines the track's approach

If the same step fails twice, stop and present the situation to the user
(see [`step-implementation.md`](step-implementation.md) §Two-Failure Rule).

Failed episodes are recorded in the step file with the `[!]` marker so
future sessions and reviews can see what was attempted and why it didn't
work.

---

## Track episode

Written to the plan file under the completed track's checklist entry.
Contains: what was built, key discoveries, plan deviations with cross-track
impact. Reference to step file with counts.

---

## Episode length rule

Proportional to cross-track impact. A track that went as planned and
produced no surprises needs 1-2 sentences. A track that discovered
architectural issues, changed assumptions, or deviated from the plan
should include enough detail for the execution agent to assess
impact on remaining tracks without reading the step file. There is no
hard line limit — clarity and completeness for downstream decision-making
is the criterion.

The same principle applies to step episodes: a trivial rename needs one
line; a step that uncovered a concurrency bug needs a full explanation.

---

## Code commit and episode ordering

Code changes are committed first (including any code review fix commits).
After all code is committed, the episode is written to the step file on
disk. Episodes are never committed — they are working files that persist
between sessions and are aggregated into the ADR during Phase 4.

---

## Where episodes live

Step-level episodes: **step file** (`docs/adr/<dir-name>/tracks/track-N.md`)

Track-level episodes: **plan file** (`implementation-plan.md`) under the
track's checklist entry — a strategic summary synthesized from step episodes.
