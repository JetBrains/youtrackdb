# Handoff: Phase B (Track 5) — inline replan after Step 4 dim review (bootstrap-body gaps)

**Paused:** 2026-05-31
**Phase:** B (Track 5, Step 4 — ESCALATE mid-dim-review)
**Context level at pause:** safe (user-requested pause to run the inline replan in a fresh session, not a context-pressure pause)
**Branch:** ytdb-1023-workflow-toc
**HEAD:** 09e2e0c521 "Bootstrap + refs sweep across 20 live agent files"
**Unpushed:** 0 commits

## Why this handoff exists

Step 4's HIGH-risk dimensional review surfaced inherited bootstrap-block design gaps. The user chose **ESCALATE → inline replanning**. The replan was not executed this session — the user asked to run it fresh. This handoff carries the dim-review findings and the chosen fix so the next session resumes the inline-replanning Process at step 3 (Propose) without re-spawning the five review agents or re-deriving the decision.

The next session must follow `.claude/workflow/inline-replanning.md` § Process from step 3, using the scope below. Steps 1 (Stop) and 2 (Assess) are already done and captured here.

## State on disk (verify before acting)

- **Step 4 implementer commit `09e2e0c521`** (20 live `.claude/agents/*.md` files): inserted the canonical bootstrap block after frontmatter + swept refs (only `dr-audit.md` needed ref work). SUCCESS, pushed to origin (confirmed ancestor of `@{u}`).
- **Roster Step 4 is still `[ ]`** in `plan/track-5.md` `## Concrete Steps`. **No Step 4 episode written.** The dim-review loop ran iteration 1 only and was interrupted by ESCALATE before any `FIX_REVIEW_FINDINGS` respawn or gate-check.
- **Phase B base commit:** `b722c11e6ed7766b3e1f3b309a9f64b2139fb64f` (in `## Base commit`; verified ancestor of HEAD this session).
- **`step_base_commit` for Step 4** was `7211e09b28d92e1cd7c10fb0ce77410dae7b734d` (HEAD before the Step 4 spawn).
- Working tree clean. Steps 1-3 complete with paired content+episode commits.

## Durable artifacts on disk

- `docs/adr/ytdb-1023-workflow-toc/_workflow/plan/track-5.md` — Steps 1-3 episodes; Step 4 roster line `[ ]`, no episode.
- `.claude/agents/*.md` (20 files) — carry the bootstrap block (with the gap-carrying body) as committed in `09e2e0c521`.
- Staged Steps 2-3 bootstrap blocks: `_workflow/staged-workflow/.claude/skills/*/SKILL.md` (7) and `_workflow/staged-workflow/.claude/workflow/prompts/*.md` (11) — same gap-carrying body.

## Pending action (already decided: ESCALATE)

Execute the inline replan to fix the bootstrap block body across the whole rollout. The user already chose ESCALATE; on resume, recap the situation, confirm the user still wants it (they may redirect), then run `inline-replanning.md` § Process steps 3-6.

## Dim-review findings (verbatim — do NOT re-spawn the 5 review agents)

Five workflow-review agents ran against `09e2e0c521~1..09e2e0c521` (workflow-only diff → baseline skipped; hook-safety not fired — no hooks/scripts/settings touched). **Gate status: GREEN** — `workflow-reindex.py --check` exits 0 on all 20 live agents (rules 6/7 fire per D17; rules 2/3/4 suppressed; all role/phase tokens valid enum members; bootstrap blocks present; no agent gained a TOC region; writing-style clean).

### Class 2 — ESCALATE scope (inherited design-body gaps, present in all 38 files + frozen `design.md`)

- **WP1 / WI1 (blocker / should-fix) — `any`-wildcard under-match.** The bootstrap block's step-2 match rule ("Match TOC rows where Roles contains your role AND Phases contains your phase") has no clause for a reader whose own role/phase is the wildcard `any`. A reader declaring `Your phase: any.` (dr-audit, pr-reviewer) under-matches: per staged `conventions.md §1.8(e)` the `any` wildcard is one-way (a *target* `any` widens to any citer; a *citer/reader* `any` does not widen). Empirically verified against staged conventions.md: most TOC rows carry specific phases (`3A`, `3B,3C`, `1,2`), only ~13-37 rows carry `phases=any`; and the role token `pr-reviewer` appears in ZERO section annotations — so a `pr-reviewer:any` reader following the block literally matches only rows that are both `roles=any` AND `phases=any` (schema/glossary), skipping every role- or phase-specific section. The block never teaches the reader to expand its own `any`, and §1.8(f)'s read-decision flow carries the same gap. **Chosen fix (user-selected):** step 2 becomes — "Match TOC rows where Roles contains your role (or your role is `any`, or the row's Roles is `any`) AND Phases contains your phase (or your phase is `any`, or the row's Phases is `any`)." Apply to `design.md §"Bootstrap protocol"` body + `conventions.md §1.8(f)` read-decision flow + all 38 files.
- **WP2 (should-fix) — read-window.** Step 1 hardcodes "Read the first ~30 lines (TOC region ...)". `conventions.md`'s TOC region is ~80 lines (75 rows + H1 + delimiters); a reader taking "first ~30 lines" literally truncates the TOC and may miss the §1.8 / §1.5 rows it filters for. The fixed count also conflicts with the delimiter-bounded phrasing in the same sentence (and the design's flowchart says "~20 lines"). **Fix:** drop the fixed count; key the read on the delimiters — e.g. "Read from `<!--Document index start-->` to `<!--Document index end-->` (the TOC region; on large files like `conventions.md` this exceeds 30 lines — read to the closing delimiter)."
- **WI2 (suggestion) — backtick-ref handling.** The closing block line ("Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening") over-asserts: backtick-wrapped non-annotatable refs (e.g. `` `house-style.md` ``, `` `CLAUDE.md` ``) carry no suffix. **Fix:** append a half-sentence — "backtick-wrapped refs carry no suffix; open or skip them at your discretion."

These three are rooted in the canonical body at `design.md §"Bootstrap protocol for agent system prompts"` and are present identically in the 18 staged Step 2-3 files (committed) and the 20 Step 4 agents. The reindex gate does not validate block-body content (rule 7 is presence-only), so they are gate-invisible.

### Class 1 — Step-4-local fixes (apply during the replan's re-propagation step; not design decisions)

- **WC1 (should-fix) — house-style ref uniformity.** `dr-audit.md` now renders the house-style §1.5 ref as the suffixed `conventions.md:pr-reviewer:any` + separate backticked `§1.5`, while the other 19 agents keep the fully-backticked span `` `.claude/workflow/conventions.md §1.5 ...` `` (no suffix). The split is a consequence of differing source forms (dr-audit had a Markdown *link* D13 mandates converting; the 19 had pre-existing backticked spans §1.8(e) excludes). The Track 5 plan entry's literal wording says all 19 take the "whole-file-plus-backticked-`§1.5` form" (suffixed); Track 4's prompts keep it backticked; Track 4's SKILLs use the suffixed form. **Decision needed in the replan:** pick ONE form for the agent class and apply to all 20. Recommended: backtick-wrap `dr-audit`'s §1.5 ref to match the 19 (and Track 4 prompts) — the house-style disclaimer is a meta-mention, not a runtime TOC-Read target, so the filter benefit there is marginal; uniformity is the win. Also correct the Step 4 episode's `what_changed_from_plan` to record that the 19-agent house-style ref took the backticked-span form (a deviation from the plan entry's literal "suffixed" wording).
- **WP3 (suggestion) — phase-any gloss.** The `any`-using SKILLs wrote `Your phase: any (PR review sits outside the phase taxonomy).` / `... (migration sits outside the phase taxonomy).`; the agent blocks wrote bare `Your phase: any.`. Mirror the gloss on `dr-audit.md` and `pr-reviewer.md` for cross-file consistency with the sibling SKILLs.

### Record-only (no code change; fold into the Step 4 episode's "What was discovered")

- **WC2 / WP4 (suggestion) — role-fit note.** `code-reviewer.md` and `test-quality-reviewer.md` are mapped to `reviewer-dim-step,reviewer-dim-track` / `3B,3C` though they are standalone user-invoked agents, not part of the workflow dim-review fan-out (`/code-review` dispatches `review-*` agents by name, never these two). The 15-value role enum has no generic "code reviewer" token, so this is a forced-but-defensible fit; the role's only runtime effect is the agent's own read-filtering. No change required; note it so a future reader does not mistake them for fan-out members.
- **WB1 (suggestion) — inert overhead accepted.** The ~13-line block is per-spawn overhead with no offsetting Read-savings for the baseline code-review agents (they review Java diffs, not workflow rules). Below any flag threshold; the plan deliberately bootstraps the whole agent set uniformly. Accept as-is.

## Proposed replan scope (for the Propose step — refine with fresh context)

The fix spans a completed track's staged output and the frozen design, so it is genuinely ESCALATE-class:

1. **New Decision Record** (e.g. `D19`) documenting the bootstrap-protocol fix: `any`-wildcard expansion (both reader-side and target-side) in the step-2 match rule, the delimiter-bounded read window (WP2), and the backtick-ref handling note (WI2). Alternatives/rationale/risks per the `inline-replanning.md` DR-revision format.
2. **`design.md §"Bootstrap protocol for agent system prompts"`** — revise the canonical body via the **`edit-design` skill** (mutation discipline; do NOT direct-Edit `design.md`). The body is design.md-only here (no `design-mechanics.md` companion exists — confirm), so use direct `content-edit` mutation(s).
3. **Staged `conventions.md §1.8(f)` read-decision flow** (and any §1.8 location that restates the reader-side match rule, e.g. §1.8(d) bootstrap-body example) — apply the same `any`-expansion clause. NOTE: §1.8 is Track 1 (`[x]`) staged output. Editing it is inline-replanning "revising a completed track" (case 4) territory, but everything is staged/pre-merge, and Track 5 already edits staged files — the replan must decide whether to treat this as a Track 5 step or a documented Track 1 re-touch. The glossary "Role enum" row, I5 invariant, and the `Bootstrap block` glossary row may also need wording (the placement wording is already in the Phase-4 deferred-drift basket per M12; the `any`-semantics is new).
4. **Re-propagation step (HIGH-risk)** — apply the corrected bootstrap body to all 38 files: 18 staged (7 SKILL + 11 prompts, Steps 2-3 `[x]`) + 20 live agents (Step 4, commit `09e2e0c521`). Fold in the Class-1 WC1 + WP3 fixes on the agent side. The 18 staged Step 2-3 files cannot be folded into Step 4 (different steps, already `[x]`), so the re-propagation likely needs its own step or a re-scope; the Propose step designs the exact step structure.
5. **Disposition of `09e2e0c521`:** keep it (the insertion mechanics are correct; only the shared body content needs fixing). The re-propagation re-edits the 20 agents to the corrected body. Decide whether Step 4 is marked done (episode it) with a new body-fix step following, or re-scoped to carry the corrected body. The 18 staged files needing the same fix argue for a dedicated re-propagation step over folding into Step 4.
6. **Reset `## Plan Review` to `[ ]`** per `inline-replanning.md` step 6 so the next `/execute-tracks` re-runs State 0 (consistency + structural) against the revised plan. Commit the replan as a single "Inline replan after Track 5 Step 4" Workflow-update commit; push.

Affected DRs to cross-check: **D8** (bootstrap embedded in every system prompt — body content changes, 38-file scope unchanged; add a revision note pointing at the new DR), **D6** (agent files refs-only + bootstrap — unchanged), and the new DR. Run the advisory structural-review preview (step 4) before committing.

## Resume notes

- **Do NOT redo:** the five dim-review agent spawns (findings captured verbatim above); the Step 4 implementer spawn (commit `09e2e0c521` is on disk and pushed); the role/phase enum verification (all tokens valid; `pr-reviewer` absent from annotations is the WP1 root, not a typo).
- **Workflow-modifying branch:** reads of `.claude/workflow/**` and `.claude/skills/**` follow staged-first precedence; the staged `conventions.md` carries §1.8, the live copy is develop-state. Writes to `.claude/workflow/**` and `.claude/skills/**` route to `_workflow/staged-workflow/`; `.claude/agents/**` is written live. When spawning any review/implementer sub-agent, inject the staged-read guidance and the Markdown-not-Java/PSI scoping (the recurring YTDB-1038 friction).
- **`edit-design` for `design.md`:** the bootstrap-body revision MUST go through the `edit-design` skill's mutation discipline (apply → auto-review → iterate → present), not a direct Edit.
- **On resume:** recap the ESCALATE situation to the user, confirm they still want the replan (allow redirect), then execute `inline-replanning.md` § Process steps 3-6.
- **slim-plan snapshot** was at `/tmp/claude-code-plan-slim-862.md` (PID-scoped; regenerate next session).
