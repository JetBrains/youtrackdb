<!--MANIFEST
dimension: workflow-context-budget
prefix: WB
findings: 0
evidence_base:
  certs: 0
cert_index: []
flags:
  evidence_trail_exempt: true
  exempt_reason: "(a) no refutation or certificate phase to persist"
index: []
-->

## Findings

No budget impact on any of the three axes; the workflow-reindex script ran clean.

This track flips the always-on AI-tell subset from five sections to six. The
flip touches one always-loaded surface, the load-on-demand rule docs, and one
PreToolUse hook body. None of the three crosses a budget threshold.

**Axis 1 (always-loaded surface).** Only `CLAUDE.md` is always-loaded among the
changed files. Its sole edit is the D6 de-enumeration at line 104: a four-item
inline list ("banned vocabulary, banned sentence patterns, banned analysis
patterns, em-dash discipline") becomes a pointer to the canonical homes
(`house-conversation.md`, `conventions.md §1.5`). The line goes from 729 to 737
characters, a net +8 chars on one always-loaded line. The change reduces future
budget cost rather than adding to it: D6 removes `CLAUDE.md` from the per-flip
blast radius, so the next subset flip no longer edits an always-loaded file. The
pointer resolves; both named homes exist and `conventions.md §1.5` is a real
anchor at line 547. No skill or agent `description:` field changed.

The `house-style-write-reminder.sh` hook is wired as a **PreToolUse** hook
(matcher `Write|Edit|mcp__.+__steroid_apply_patch` in `settings.json:33`), not a
SessionStart hook. Its `tier_b_body` output does not enter the per-turn
always-loaded baseline; it materializes only inside a single Write/Edit
operation. The body grew to 491 chars (SD1), under the 500-char
`PER_BODY_CHAR_CAP`, and the concatenation stays at 857 chars under the 1500-char
`CONCAT_CHAR_CAP`. Per-operation cost is small and capped.

**Axis 2 (load-on-demand discipline).** The largest single addition is the
`## Plain language` section in `house-style.md` (~20 lines), well under the
>100-line structural-drift gate. It is a rule section in the canonical rule file
— its correct home — not inline recipes or examples leaking toward always-loaded
territory. The other load-on-demand edits are one-slug enumeration syncs and
count flips across the workflow docs. No known `CLAUDE.md` pointer to a changed
file broke. Axis 2 not triggered.

**Axis 3 (instant per-operation consumption).** The diff introduces no new
orchestrator-side reads, no new sub-agent dispatches, no inlined 50+ line
recipes, and no multi-phase re-reads of the same content. The one growing
per-operation surface is the capped hook body covered under Axis 1. Axis 3 not
triggered.

**Workflow-reindex script.** `python3 .claude/scripts/workflow-reindex.py --check
--files <11 in-scope workflow-machinery .md files>` exited 0 with no stdout
findings and no stderr. The deterministic §1.8 schema half of the review is
clean; the diff-filtered finding set is empty.

## Evidence base
