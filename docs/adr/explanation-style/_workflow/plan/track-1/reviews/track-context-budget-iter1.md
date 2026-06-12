<!-- MANIFEST
dimension: workflow-context-budget
agent: review-workflow-context-budget
iteration: 1
review_target: "Track 1 (a743adad35..HEAD): §1.7 opt-out, ## Orientation rule, AI-tell subset four→five atomic flip"
findings: 0   severity: {Critical: 0, Recommended: 0, Minor: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 0}
cert_index: []
flags: [CONTRACT_OK, NO_BUDGET_IMPACT]
-->

## Findings

None. No always-loaded surface, load-on-demand discipline, or instant per-operation consumption impact on any axis, and the workflow-reindex script ran clean.

The track is all-prose workflow-only (§1.7(k) opt-out, live edits, no Java/Kotlin). Three checks decide the result:

- **Axis 1 — always-loaded surface: unchanged.** No skill or agent `description:` frontmatter field changed (verified by diffing the frontmatter region across all 19 agent files and 5 SKILL files — zero `+description:` lines). CLAUDE.md is not touched. The 19 agent-body and 5 skill-body edits, the workflow-prose edits, `house-style.md`, and `house-conversation.md` are all load-on-demand bodies; their `description`-field always-loaded surface is untouched. `house-style-write-reminder.sh` is a **PreToolUse** hook (matcher `Write|Edit|mcp__.+__steroid_apply_patch` in `.claude/settings.json:49`), not a SessionStart hook, so its output is per-write additionalContext, not session-baseline context.

- **Axis 2 — load-on-demand discipline: not affected.** The largest single addition is `conventions.md` +131 lines (the new §1.7(k)/(l) rule sub-sections). Neither structural-drift check fires: the growth lands in a load-on-demand workflow rule file that is *meant* to hold rule prose (it is not inline recipes/examples leaking into an always-loaded surface), and no CLAUDE.md pointer broke (5 references to `house-style.md` / `conventions.md` remain valid; no path moved). `house-style.md § Orientation` is 25 lines, under the ~80-line load-on-demand-split warrant, in a file that is load-on-demand regardless.

- **Axis 3 — instant per-operation consumption: not inflated.** No new orchestrator-side reads, no new sub-agent dispatches, no inlined 50+ line recipes, no multi-phase re-reads. The step-3 hook fix is respected and not regressed: `tier_a_body` = 366 chars, `tier_b_body` = 441 chars, concatenated = 807 chars — all under the documented 500-char per-body and 1500-char concatenated caps, now guarded by `test_18_reminder_body_length_budget`. The four→five AI-tell subset flip at ~54 sites lengthened already-present one-line blurbs in load-on-demand bodies by one slug name (`## Orientation`) each; it created no new always-loaded surface and no new per-operation peak. The D1 faithful-inline-sync trade-off holds for the added fifth member: the sites already carried the four-member blurb, so the added member is a per-blurb word-level delta, not a centralization-vs-inline decision reopened.

- **Deterministic half — workflow-reindex script: clean.** Ran `python3 .claude/scripts/workflow-reindex.py --check` (full-repo walk, chosen because 45 changed workflow-machinery files exceed the 25-file `--files` threshold). Exit 0, zero findings on stdout, empty stderr. The §1.8 schema (new TOC rows for §1.7(k)/(l), per-section annotations, cross-file refs, in-file §X.Y(z) stamps, bootstrap blocks) validates mechanically. The diff-filtered finding set is empty.

## Evidence base
