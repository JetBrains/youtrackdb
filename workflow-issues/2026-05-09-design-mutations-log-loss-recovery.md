---
severity: low
phase: state-0
source-session: 2026-05-09 /execute-tracks unit-test-coverage
---

# `edit-design` skill has no recovery rule for a lost mutation log

## Symptom

State 0's consistency review fixes (CR3 + CR4 + CR5) had to route
through the `edit-design` skill (mutation discipline) because they
touched `design.md`. The skill's Step 7 ("Append to the review
log") instructs:

> Use `Read` to find the highest existing mutation number and
> increment by one. The first mutation is `## Mutation 1 — ...`.

But `design-mutations.md` did not exist on disk: it was lost in
the 2026-05-04 `git clean -fd` incident along with the `reviews/`
directory and other ephemeral working files (per the now-deleted
plan §Operational Notes block). The design.md itself has a long
edit history pre-dating the loss (Phase 1 creation + many
iterations during Tracks 1–21 absorption updates), so the missing
log doesn't reflect a fresh design.

I had to invent a recovery posture mid-session: I created a new
`design-mutations.md` file with a 9-line preamble explaining that
the log was lost, the design has prior history, and this session's
edit is "Mutation 1 in the post-recovery log." That preserves
audit trail integrity but isn't authorized by the skill doc.

## Reproduction context

- Phase: state-0 (the trigger was State 0's design-side
  consistency fixes, but any phase that calls `edit-design` after
  a log-loss event hits the same gap).
- Workflow doc(s) involved:
  - `.claude/skills/edit-design/SKILL.md` § Step 7 (Append to the
    review log)
  - `.claude/workflow/design-document-rules.md` § Mutation
    discipline § Review log
- Tool / sub-agent involved: `edit-design` skill
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any session that runs `edit-design` against
  a design with prior edit history but with no `design-mutations.md`
  file on disk (recovery from cleanup, branch transfer between
  machines, manual deletion, etc.).

## Why it's a problem

Without a documented recovery rule the skill produces inconsistent
audit trails across agents. Three plausible behaviours:

- **Restart silently**: agent creates Mutation 1, no preamble. A
  reader of the log later sees a single-entry log on a 336-line
  design and incorrectly concludes the design is fresh.
- **Stall**: agent reports the missing file as a precondition
  failure and surfaces it to the user, blocking the mutation.
- **Document the loss in a preamble** (what I did): preserves
  audit-trail honesty but the skill doc neither authorizes the
  preamble shape nor names the convention.

In a long-lived plan the audit trail is the only durable record of
why a design.md changed across mutations. A single-line silent
restart breaks the chain. A user reading the post-recovery log
should know at-a-glance that the recovery happened.

## Proposed fix

Edit `.claude/skills/edit-design/SKILL.md` § Step 7 (Append to the
review log) to add an explicit recovery clause:

> If `design-mutations.md` does not exist on disk but
> `design.md` already has prior edit history (e.g., the file was
> lost in a working-tree cleanup or branch transfer), do **not**
> silently restart numbering at 1. Create the file with a brief
> recovery preamble (3–5 lines) at the top, naming the
> recovery date and reason, then number the first post-recovery
> entry as `Mutation 1 — <date> — <kind>`. Subsequent entries
> increment normally. The preamble preserves audit-trail honesty
> for a future reader who cannot otherwise know the log started
> fresh.

Pair the rule with a one-paragraph example showing the preamble
shape (3–5 lines naming the recovery date, the reason, and a
pointer to where the pre-recovery edits were captured if any —
e.g., the now-deleted plan §Operational Notes block, an external
incident write-up, an agent transcript).

Optionally: add a parallel rule in
`.claude/workflow/design-document-rules.md` § Mutation discipline
§ Review log so the convention lives in both the rules doc and
the skill doc.

## Acceptance criteria

- `.claude/skills/edit-design/SKILL.md` § Step 7 names the recovery
  case explicitly and prescribes the preamble shape.
- A regression check: starting from a state with a non-trivial
  `design.md` and no `design-mutations.md` on disk, the skill
  produces a log file that opens with a recovery preamble and
  starts numbering from Mutation 1.
- `design-document-rules.md` § Review log either incorporates the
  same rule or cross-references the skill doc as the authoritative
  source.
