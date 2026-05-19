# Design Mutations Log

## Mutation 1 — 2026-05-18 — phase1-creation (design.md)

**Diff summary**: Initial seed of `design.md` for the YTDB-837 work (activate house-style across the workflow). Single-file design with no `design-mechanics.md` companion (six sections, all within budget). Covers Overview, Workflow (Mermaid hook decision-flow diagram), and four mechanism sections — Tier mapping, Hook input parsing, Rate-limit semantics, Path blacklist — each following the per-section mandatory shape (TL;DR + mechanism + Edge cases + References footer).

**Mechanical checks** (target=design): PASS — 0 findings on iteration 2 (4 should-fix findings cleared from iteration 1).

**Cold-read** (scope: whole-doc): PASS — 0 findings on iteration 2 (1 should-fix + 2 suggestions cleared from iteration 1).

**Findings (iteration 1)**:
- should-fix (mechanical): Tier-1 banned vocabulary "delve" inside quoted YTDB-837 acceptance-bullet example at line 74. Resolved by rephrasing the bullet to describe the acceptance criterion without quoting trigger words.
- should-fix (mechanical): Negative parallelism "It's not X, it's" inside the same quoted example at line 74. Resolved by the same rephrase.
- should-fix (mechanical): Fragmented header — "Tier mapping to house-style.md sections" heading shared 4/5 content words with its TL;DR. Resolved by rewriting the TL;DR to use different anchor terms ("full rule set covers Markdown; a four-fragment subset covers Java and Kotlin").
- should-fix (mechanical): Fragmented header — "Hook input parsing across three tool shapes" heading shared 4/7 content words with its TL;DR. Resolved by rewriting the TL;DR to skip the heading anchor words ("`Write` and `Edit` pass the target path directly; `mcp__localhost-6315__steroid_apply_patch` passes a patch string…").
- should-fix (cold-read): Em-dash discipline violation at line 74 (two em dashes in one Edge-cases bullet under Tier mapping). Resolved by replacing both em dashes with parentheses.
- suggestion (cold-read): Overview roadmap labels at line 13 did not match the actual H2 section titles. Resolved by rewriting the roadmap sentence to use the full H2 titles, separated by semicolons.
- suggestion (cold-read): References footers used free-prose invariant lines instead of the `Invariant N: <label>` form. Resolved by relabeling the three invariants (Invariant 1 — hook latency; Invariant 2 — per-session per-tier reminder; Invariant 3 — rule-source files never trigger their own reminder).

**Findings (iteration 2)**: none — all issues resolved.

**Iterations**: 2 of 3 (PASS)

## Mutation 2 — 2026-05-18 — content-edit (design.md)

**Diff summary**: Replaced the hardcoded literal tool name `mcp__localhost-6315__steroid_apply_patch` with the regex pattern `mcp__.+__steroid_apply_patch` everywhere it appeared, since the server-name segment is the user-global `~/.claude.json` `mcpServers` registry key and varies across teammates and installs. Three substitution sites: Overview (line 7), Hook input parsing TL;DR (line 84), jq pipeline code block (line 91). Added a short follow-up prose paragraph after the jq block (line 96) calling out that the settings.json matcher mirrors the same regex and explaining the `.+` vs `[^_]+` trade-off.

**Mechanical checks** (target=design): PASS — 0 findings on iteration 2 (1 should-fix on iteration 1).

**Cold-read** (scope: whole-doc): PASS — substitutions confirmed clear and coherent, no new structural findings, regex agreement between dispatch site and hook internals explicitly surfaced.

**Findings (iteration 1)**:
- should-fix (mechanical): Em-dash density at line 96 — initial draft of the follow-up paragraph used two em dashes as parenthetical brackets around the matcher value. Resolved by rewriting with parentheses (`shape (...), so...`).

**Findings (iteration 2)**: none — all issues resolved.

**Iterations**: 2 of 3 (PASS)

## Mutation 3 — 2026-05-18 — content-edit (design.md)

**Diff summary**: Rewrote the Rate-limit semantics section (and the matching state-file label in the Workflow mermaid diagram) to key the state file by `session_id` instead of by Claude pid. User flagged that `/clear` doesn't change the Claude pid, so a post-`/clear` "session" would inherit the prior tier-fired marks and silently suppress fresh reminders. `session_id` is the top-level field of every PreToolUse hook input JSON; Claude Code regenerates it on `/clear` and on every fresh conversation, so the throttle window resets exactly at the logical session boundary the reminder cares about.

Touched: TL;DR (new wording around `session_id`), the 6-step state-file lifecycle list (renumbered to include the `/clear` reset path), the trailing rationale paragraph (now contrasts session_id keying with the pid-tree-walk pattern in `mcp-steroid-grep-reminder.sh` and names why pid keying would have been wrong), the `/clear` edge-case bullet (now states the new behavior — the reset is by design), and the Workflow diagram's state-file node label (line 28: `"State file<br/>per session_id"`).

**Mechanical checks** (target=design): PASS — 0 findings on iteration 2 (1 should-fix on iteration 1).

**Cold-read** (scope: whole-doc): PASS on iteration 2 (1 should-fix on iteration 1: Workflow diagram node label still said "per Claude pid", contradicting the new prose).

**Findings (iteration 1)**:
- should-fix (mechanical): Fragmented header — heading "Rate-limit semantics" shared "rate-limit" with the rewritten TL;DR (50% overlap at threshold). Resolved by replacing "rate-limit window" with "throttle window" in the TL;DR.
- should-fix (cold-read): Workflow diagram state-file node at line 28 still labeled "per Claude pid", contradicting the new TL;DR's `session_id` keying. Resolved by updating the node label to `"State file<br/>per session_id"`.

**Findings (iteration 2)**: none — all issues resolved.

**Iterations**: 2 of 3 (PASS)

## Mutation 4 — 2026-05-19 — content-edit (design.md)

**Diff summary**: Applied two mechanical fixes from the autonomous Phase 2 consistency review (findings CR1 blocker and CR4 should-fix). CR1: rewrote § "Hook input parsing across three tool shapes" so it describes the real `steroid_apply_patch` tool surface — `tool_input.hunks` array of `{file_path, old_string, new_string}` objects — instead of the earlier wrong working assumption of a `tool_input.patch` unified-diff string with `+++ b/<path>` lines. Touched five places in that section (TL;DR, jq pipeline `elif` body, Python-fallback paragraph, summary paragraph, Edge-cases first bullet) and one mirror reference in the prose paragraph following the Workflow mermaid diagram at line 50 (caught by the iteration-1 cold-read). The only remaining `tool_input.patch` / `+++` reference is the deliberate explanatory negation at line 100 framing why the assumption was wrong. CR4: updated the tier-mapping table to spell out the actual house-style.md heading text — Tier-A row ends with `§ Document-shape rules (design / ADR-specific)`, Tier-B row ends with `§ Em-dash discipline (H3 nested in § Punctuation and typography)` — and appended one clarifying sentence to the paragraph after the table noting that the four Tier-B headings live at different depths and pointers anchor on the stable substring.

**Mechanical checks** (target=design): PASS — 0 findings on iteration 1 and iteration 2 (no mechanical regressions either iteration).

**Cold-read** (scope: whole-doc): PASS on iteration 2 (1 blocker + 1 suggestion on iteration 1; blocker fixed, suggestion judged non-blocking).

**Findings (iteration 1)**:
- blocker (cold-read): Residual unified-diff contradiction at line 50 in `## Workflow` — the prose paragraph still read "`+++ b/<path>` lines from the patch text for `steroid_apply_patch`", contradicting the rewritten Hook-input-parsing section's `hunks` array. Resolved by rewriting the parenthetical to "per-hunk `file_path` entries from the `tool_input.hunks` array for `steroid_apply_patch`".
- suggestion (cold-read): Trailing sentence in the paragraph at line 66 (heading-depth note) could be its own paragraph for navigability. Kept as a trailing sentence on the iteration-2 re-review; placement keeps the explanation adjacent to its motivating table row at line 63.

**Findings (iteration 2)**: 1 suggestion (the line-66 paragraph split, non-blocking; prior judgment confirmed).

**Iterations**: 2 of 3 (PASS)
