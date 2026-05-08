---
severity: medium
phase: phase-a
source-session: 2026-05-08 /execute-tracks unit-test-coverage
---

# `VfsUtil.saveText` via `steroid_execute_code` triggers an editor auto-format that 4-space-indents the step file's `## Description` body

## Symptom

After the Phase A orchestrator wrote `track-21.md` via a
`writeAction { VfsUtil.saveText(vf, updated) }` call inside
`steroid_execute_code` — populating the file with `# Track 21:`,
`## Description`, multi-line `**What/How/Constraints/Interactions**`
blockquotes (`>` prefix), a mermaid `flowchart TD` block, `## Progress`,
`## Reviews completed`, and `## Steps` — the IntelliJ editor (or a
linter triggered by the VFS write) **uniformly indented every line of
the `## Description` body by 4 spaces**, turning the description into
an indented code block.

The headings (`# Track 21:`, `## Description`, `## Progress` at line
392) inside the indented block were also indented (still part of the
code block); the bottom-half headings (`## Reviews completed` at line
397, `## Steps` at line 399) stayed at column 0 because they sat after
the multi-line indented region terminated.

The `IDE-auto-format` post-write modification fired the system reminder
`"This change was intentional, so make sure to take it into account as
you proceed (ie. don't revert it unless the user asks you to)"`,
forcing the orchestrator to honour the broken state and brief the
iter-2 review sub-agents on the cosmetic indent before they read the
file.

## Reproduction context

- Phase: phase-a
- Workflow doc(s) involved: `.claude/workflow/track-review.md` §What You
  Do sub-step 2c (atomic step file Write); project root
  `CLAUDE.md` § "MCP Steroid — IntelliJ IDE Control" / "File edits:
  always through MCP Steroid".
- Tool involved: `mcp__localhost-6315__steroid_execute_code` running
  `writeAction { VfsUtil.saveText(vf, multiLineMarkdown) }`.
- ADR directory at the time: `docs/adr/unit-test-coverage/`
- Trigger condition: any Phase A sub-step 2c atomic step-file write
  whose payload is multi-line markdown including blockquotes (`>`),
  fenced code blocks, or `mermaid` blocks. Track 20's step file did
  NOT exhibit this — the difference is likely (a) the size of the
  payload (>>4KB triggers a different IntelliJ formatter pass),
  (b) the mermaid fence content, or (c) some setting that became
  active between the two sessions. The exact root cause is not
  characterized in the workflow docs.

## Why it's a problem

Phase B and Phase C sub-agents read the step file's `## Description`
section as load-bearing markdown — they extract `**What:**`,
`**How:**`, `**Constraints:**`, `**Interactions:**`, the per-package
target table, and the mermaid component diagram. With the body
indented as a code block:

- The blockquote prefix is `    > **What:**` rather than `> **What:**`;
  many markdown parsers treat the indented region as a verbatim
  code block, so `>` ceases to mark a blockquote.
- The mermaid fence (`````mermaid ... `````) sits inside an indented
  region and may render as code instead of as a diagram.
- Iter-2 gate sub-agents had to be **explicitly briefed** on the
  cosmetic 4-space indent in their prompts (`"the body of the ##
  Description section is indented 4 spaces uniformly... Treat the
  4-space-indented blockquote lines as the authoritative description"`).
  Without that briefing, a sub-agent would read incorrect structure.
- Future Phase B / Phase C sessions consuming the file will rely on
  textual matching that is robust to indent (e.g., grep for
  `\*\*What:\*\*`) but visual review by humans on the draft PR will
  see broken markdown.

The session-end `system-reminder` instruction `"don't revert unless
the user asks you to"` blocks the orchestrator from cleaning up
inline, so the broken state persists for the rest of the session and
into the durable PR diff.

## Proposed fix

Two complementary fixes; either or both:

1. **Add a recipe to `.claude/docs/mcp-steroid/recipes.md` (or a new
   sub-section in `track-review.md` §What You Do sub-step 2c)** that
   names this trap and prescribes a safer write path for multi-line
   markdown payloads. Candidate prescriptions:
   - Prefer `mcp__localhost-6315__steroid_apply_patch` for markdown
     payloads (it bypasses kotlinc and likely also bypasses the
     post-VFS reformat trigger). The orchestrator can build the file
     by writing an empty placeholder (one-line `# Track N:`) then
     `apply_patch`-ing the description body.
   - Or: temporarily disable the editor's reformat-on-VFS-write
     setting before the call (if available via `EditorSettingsExternalizable`
     or a registry key) and restore it after.
   - Or: write through a temp-file + atomic rename outside the IDE's
     watched paths to avoid the watcher firing reformat.

2. **Loosen the system-reminder rule for cosmetic post-write linter
   reformats.** Today's reminder is: *"This change was intentional, so
   make sure to take it into account as you proceed (ie. don't revert
   it unless the user asks you to)"*. For markdown step-file writes
   where the orchestrator has direct evidence the post-write change
   broke rendering (4-space indent under a heading triggering
   code-block treatment), allow the orchestrator to revert with a
   one-line note in the `## Reviews completed` audit. Otherwise the
   broken state propagates.

## Acceptance criteria

- A new section in `track-review.md` §What You Do sub-step 2c (or a
  recipe in `.claude/docs/mcp-steroid/recipes.md`) names the
  `VfsUtil.saveText` + multi-line markdown auto-format trap and
  prescribes the safer write path.
- A future Phase A session writing a similarly-shaped step file (multi-
  line `**What/How/Constraints/Interactions**` with embedded mermaid
  block) does not produce a 4-space-indented `## Description` body.
- Regression check: `grep -n '^    >' docs/adr/<dir>/_workflow/tracks/track-N.md`
  returns no matches on a freshly-written step file (the indented
  blockquote pattern is the load-bearing symptom).
- Optional: an entry in `CLAUDE.md` § "MCP Steroid — IntelliJ IDE
  Control" calling out the markdown-payload exception, paired with
  the existing rule "File edits: always through MCP Steroid".
