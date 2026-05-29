# Handoff: Track 4 Phase B — rule_6 / cross-ref-convention ADJUST (ESCALATE)

**Paused:** 2026-05-29
**Phase:** B (step implementation), after Step 2, before Step 3
**Context level at pause:** warning
**Branch:** ytdb-1023-workflow-toc
**HEAD:** (the episode commit recorded below) "Record episode for Track 4 Step 2 + pause handoff"
**Unpushed:** 0 commits (pushed at session end)

## Durable artifacts on disk
- `.claude/scripts/workflow-reindex.py` — Step 1 parser fence-exclusion + three-anchor rule_2 (commits `8bcb1792f3` initial, `39d517c5c8` review-fix). Step 1 complete `[x]`, dim review PASS iter-2.
- `docs/adr/.../_workflow/staged-workflow/.claude/workflow/conventions.md` — §1.8(d)/(e) schema edits from Step 1.
- Step 2 batch-1 annotations (commit `868f1f3d97`): 9 staged workflow-root files annotated (conventions.md, workflow.md, implementation-review.md, review-mode.md, design-document-rules.md, track-code-review.md, step-implementation-recovery.md, self-improvement-reflection.md, risk-tagging.md). Rules 2/3/4/5/8 clean on those 9; rule_6 RED (the finding driving this handoff); rule_1 RED on staged copies (structural, expected).
- `## Concrete Steps` roster: Step 1 `[x]`, Step 2 `[x]`, Steps 3-6 `[ ]`.
- `## Base commit`: `5aa5917da7d8a57c78bcf2fe530c58686a07c3e0`.

## Pending decision
RESOLVED by the user during the Step 2 session. The decision below is the input to the next session's inline replanning — it is NOT re-open for a fresh choice unless the user reverses it.

**The problem:** `workflow-reindex.py` rule_6 flags every non-backticked, non-fenced `name.md` token as a cross-file ref "missing the :roles:phases suffix" — including Markdown link targets (`[`x.md`](x.md)` → the `(x.md)`), link text, and bare prose mentions (`MEMORY.md`). A link target cannot carry a suffix without breaking the link. Workflow docs are saturated with such links, so the plan's "rule 6 RED clears at Step 6 once targets are annotated" model (Idempotence section) is wrong, and the Step 6 full-set green `--check` acceptance criterion is unreachable as written. Evidence: 30 rule_6 findings on staged `workflow.md` are link targets / bare prose (`L47 [`implementer-rules.md`](implementer-rules.md)`, `L165`, `L601`, `L407 MEMORY.md`).

**User's chosen direction (confirmed twice):** convert workflow-doc/prompt Markdown links into bare cross-file refs that carry the `:roles:phases` filter suffix — e.g. `[workflow.md](workflow.md)` → `workflow.md:<roles>:<phases>`, and `[conventions.md §1.6](conventions.md)` → `conventions.md§1.6:<roles>:<phases>`. Rationale: the suffix is the load-on-demand filter (a reader sees the target's roles/phases and decides whether to open it), which is the entire point of YTDB-1023. This is "bare ref PLUS suffix," not mere delinking.

Consequences the user accepted:
- The cross-file `:roles:phases` suffix convention widens from agent-files-only (D6/D8/Track 5/Non-Goals) to ALL workflow docs and prompts. **Reopens Track 1's §1.8** cross-reference convention.
- rule_6's current broad flagging becomes the intended contract — NO narrow rescope of rule_6.
- Step 2's already-annotated 9 files need a link-conversion pass.

## Open sub-question for replanning (not yet user-confirmed)
Non-annotatable cross-ref targets cannot carry a valid suffix (rule_6: "target not in in-scope file set"): `CLAUDE.md` (explicitly out of scope), `MEMORY.md`, `design.md`, `design-mechanics.md`, `output-styles/house-style.md`, and any other non-workflow-doc `.md`. **Proposed handling: backtick-wrap these to exempt them** (rule_6 excludes inline-backtick spans per §1.8(e)). Confirm during replanning, and confirm whether the in-scope target set the script validates against needs widening to include any of these.

## Resume notes — next session action
This is an **ESCALATE**. On resume (`/execute-tracks`, fresh context):
1. Resolve this handoff per `mid-phase-handoff.md` §Resume protocol (present the decision above; the user has already approved the direction — confirm the open sub-question on non-annotatable targets).
2. Load `inline-replanning.md` and run inline replanning to formalize:
   - **§1.8 widening (Track 1 reopen, staged conventions.md):** the cross-file `:roles:phases` suffix applies to cross-file references in all workflow docs and prompts, not only agent files / SKILL read-lists. State the backtick-wrap exemption for non-annotatable targets. Reconcile I7 / D6 / D8 / D9 / D10 wording with the widened scope (these were written agent-files-centric).
   - **Step re-decomposition:** fold a link-conversion pass into the annotation procedure for Steps 3-5; add a link-conversion pass for Step 2's 9 already-annotated files (new step or extend Step 6); confirm rule_6 stays as-is (no narrow rescope). Decide whether the `workflow-reindex.py` in-scope-target set or backtick-exemption needs a small script touch (would reopen Track 2 again, as Step 1 did) — likely only if non-annotatable-target handling needs script support beyond backtick-wrap.
   - Update the plan's Idempotence model (the "rule 6 RED clears at Step 6" wording is now wrong) and the Step 6 acceptance criterion.
3. Inline replanning re-presents the revised plan for user approval, then the session ends (per ESCALATE). A later fresh session executes the revised Phase B from Step 3 (or the new step ordering).

## Do NOT redo
- Step 1 (committed `39d517c5c8`, dim review PASS) — done.
- Step 2's section annotations on the 9 files (committed `868f1f3d97`) — the SECTION annotations stand; only a link-conversion pass is added on top, not a re-annotation.
- The rule_6 investigation — the finding is confirmed (link targets / prose mentions); do not re-derive.
