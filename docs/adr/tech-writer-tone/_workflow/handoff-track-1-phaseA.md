# Handoff: Phase A — Track 1 review found a staging-surface blocker

**Paused:** 2026-07-02
**Phase:** A (Track 1 review + decomposition)
**Context level at pause:** safe (~18%) — this is a **decision pause**, not a context pause. The user was AFK on both the Pre-Flight approval and the blocker-resolution question; the blocker needs a user call before decomposition can proceed.
**Branch:** tech-writer-tone
**HEAD:** bab73c84f5 "Plan review autonomous fixes for tech-writer-tone"
**Unpushed:** 0 commits (before the pause commit that carries this handoff)

## Durable artifacts on disk
- `plan/track-1/reviews/technical-iter1.md` — Technical review, iter 1. 2 findings (1 should-fix, 1 suggestion). S4 count validated (2 = 2).
- `plan/track-1/reviews/risk-iter1.md` — Risk review, iter 1. 7 findings (5 should-fix, 2 suggestion). S4 validated (7 = 7).
- `plan/track-1/reviews/adversarial-iter1.md` — Adversarial review (Fable 5, D14 pin held), iter 1, narrowed to track realization (scope/sizing + invariant-violation; cross-track-episode dropped on track 1). 7 findings (**1 blocker**, 4 should-fix, 2 suggestion). S4 validated (7 = 7).
- `plan/track-1.md` — the track file under review, unchanged this session (no fixes applied — see below).

The Pre-Flight gate was a no-op (Panel 1 skipped — first track; user AFK → **Approve** taken; no amendments, no clarifications, no strategy-refresh line — so no pre-flight commit per §Track Pre-Flight step 6).

## Pending decision
Iteration-1 reviews surfaced a **blocker (A1) plus A2** that invalidate the frozen `design.md` § Staging premise. The fix is a mechanism/design decision the user must make; I did **not** apply it autonomously.

**A1 [blocker] + A2 [should-fix] — three in-scope files cannot be staged under §1.7 as it stands on develop.** `design.md:725` § Staging assumes every edit stages under "`_workflow/staged-workflow/.claude/**`", but §1.7(a) (`conventions.md:989`) is explicit: only `.claude/workflow/`, `.claude/skills/`, `.claude/agents/`, `.claude/scripts/` are stageable — *"No other prefixes participate."* Outside that set:
- `.claude/output-styles/house-style.md` — the **source of truth** (track item 1)
- `.claude/output-styles/house-conversation.md` (item 2)
- root `CLAUDE.md` (item 19) — not under `.claude/` at all

Verified consequence in the Phase-4 promotion (`create-final-design.md:547-548`): `cp -r "$STAGED_DIR/.claude/." .claude/` copies staged output-styles into the working tree, but `git add .claude/workflow .claude/skills .claude/agents .claude/scripts` stages only the four prefixes — a staged `house-style.md` edit is **copied but never committed** (silently dropped from the PR), and the divergence guard never watches output-styles. Root `CLAUDE.md` is never even cp'd. State 0's consistency review checked file existence, not stageability, so it missed this.

Options presented to the user (all three went unanswered — AFK):
1. **ESCALATE — extend §1.7** *(my recommendation)*. Route to inline replanning. Extend the staging mechanism to cover `.claude/output-styles/**` and root `CLAUDE.md` — §1.7(a)/(b)/(d), the implementer enforcement gate (`implementer-rules.md`), and the `create-final-design.md` promotion (`git add` + divergence + `cp` for the new paths). Preserves the branch's core invariant (old rules stay live during the branch; no cross-branch contamination). Adds Decision Records + scope; corrects frozen `design.md` § Staging. Note the live-gate bootstrap wrinkle: during THIS branch the live gate still knows only four prefixes, so the output-styles/`CLAUDE.md` edits must be **manually** staged (write to the mirror path) and the promotion handled with the extended paths — this needs to be designed in the replan.
2. **Edit those 3 files live** (hybrid). Stage the behavior-bearing files (scripts/agents/workflow/skills); edit prose-only `output-styles/**` + `CLAUDE.md` live. Cheapest, but `house-style.md` goes live mid-branch → this branch's own later authoring runs under the NEW rules, and the other active branches (transactional-schema, ir-filter-step) pick up the new `house-style.md` live → cross-branch contamination. Breaks the design's central staging premise. **Advise against.**
3. **ESCALATE — full redesign.** Re-open the frozen design's staging approach holistically: §1.7 surface (A1/A2) + the staged-test `REPO_ROOT` problem (R1/A3) + the D2 consumer-side gap (A4) together.

## Verbatim re-present text
> Phase A review of Track 1 found a **blocker** that questions the frozen design. The high panel ran (Technical / Risk / Adversarial-on-Fable), 16 findings, 1 blocker.
>
> **The staging surface can't hold this track's central files.** Frozen `design.md:725` says everything stages under `_workflow/staged-workflow/.claude/**`, but §1.7(a) only stages `.claude/{workflow,skills,agents,scripts}/**`. So `house-style.md` (the source of truth), `house-conversation.md` (both under `.claude/output-styles/`), and root `CLAUDE.md` cannot be staged — and the Phase-4 promotion `git add`s only the four prefixes, so a staged `house-style.md` edit is copied but never committed (silently dropped). State 0 checked existence, not stageability.
>
> How should this resolve? (1) ESCALATE — extend §1.7 to cover output-styles + root CLAUDE.md [recommended, preserves the no-contamination invariant]; (2) edit those 3 files live [cheap but contaminates concurrent branches]; (3) ESCALATE — full redesign of the staging approach (also folds in R1/A3 + A4).

## Other iter-1 findings (durable in the review files; fold into whatever resolution runs — do NOT re-derive)
- **R1/A3 [should-fix]** — staged `dsc` Python tests resolve `REPO_ROOT = Path(__file__).resolve().parents[3]`, which from the staged path points at `staged-workflow/`, so the `docs/adr/**` calibration corpus (test lines 65-67) dangles — the suite can't run in place, so "tests green" isn't verifiable while staged. Verified against `test_dsc_ai_tell.py:59`.
- **T1/R2/A5 [should-fix]** — track item 7 adds one lowercase `"summary"` literal to both `SHAPE_EXEMPT_SECTION_NAMES` and `MANDATORY_OR_FORM_SUBHEADINGS`, but `SHAPE_EXEMPT_SECTION_NAMES` compares raw display-case section titles, so a Part-level `## Summary` would not be exempted. The two sets need case-correct entries (or the checker must lowercase before comparing). `design-mechanical-checks.py:49`.
- **T2 [suggestion]** — "three regex removals" / singular "the regex" understates that negative parallelism is two regex objects (`NEGATIVE_PARALLELISM_RE` at `:103` + `NEGATIVE_PARALLELISM_TRAILING_RE` at `:127`); the trailing test/fixture could be left behind. Name both in item 7 / Plan of Work.
- **R3 [should-fix]** — the shared line-anchored fixture: deleting the three removed patterns' positive blocks renumbers surviving line-anchored assertions (ANCHORED 79/130, NEGATIVE_RANGES 115-126) → build-time failure on survivors unless renumbered. `dsc-ai-tell-fixture.md`.
- **R4 [should-fix]** — the removed rules recur at 5 sites within `review-workflow-writing-style.md` (28/29/34/71/188); a partial edit leaves the Phase-C reviewer enforcing a rule house-style no longer bans → rule contradiction. Item 6 must name "all sites".
- **R5 [should-fix]** — the D2/D6 hybrid narrative added to `comprehension-review` is an unguarded channel for prose-AI-tell judgment (a "stumble" report is a prose-quality verdict), duplicating the target reader's sole-owned S4 axis. The persona contract must scope the comprehension gate's narrative to mental-model/comprehension, not prose AI-tells.
- **R6 [suggestion]** — the S4 grep acceptance ("exactly one owner") is ambiguous: "prose AI-tell axis" appears 4× across 2 files; the grep must be scoped + ownership-distinguishing.
- **R7 [suggestion]** — `ai-tells/SKILL.md` is a general user-facing de-AI skill; trimming its catalogue (item 11) changes user-facing behavior beyond the design-doc scope. Confirm intent.
- **A4 [should-fix]** — D2 hybrid output has producer edits planned (`design-review.md` comprehension-gate output contract, item 13) but no consumer-side edit: `create-plan` Step-4b pins a bounded inline gate return the hybrid contradicts. The consumer contract needs a matching edit (scope addition).
- **A6/A7 [suggestion]** — single-track sizing survives challenge (A6); S4 single-owner + D1 consumer enumeration survive (A7); both note the acceptance asserts claims, not behavior.

## Resume notes
- **Do NOT redo:** the three iter-1 reviews (technical/risk/adversarial) — they are on disk and S4-validated. No review has been marked `[x]` in `## Outcomes & Retrospective` (no PASS — the blocker is open), so a naive resume would re-fire the gate and re-run reviews; **this handoff is authoritative** — read it first.
- **On user decision:**
  - Option 1 or 3 → load `inline-replanning.md` and run inline replanning (the trigger is "user requests escalation during / after track pre-flight"). The replan corrects `design.md` § Staging, extends §1.7 (+ enforcement gate + promotion) as chosen, and folds in the other should-fixes. After replanning re-derives the track, re-run the Phase A panel from iteration 1 (the iter-1 findings are superseded by the replanned plan).
  - Option 2 → apply the hybrid live-edit decision to the track file's `## Plan of Work` / `## Interfaces and Dependencies` / Decision Log + the frozen-design reconciliation note, apply the mechanical should-fixes above, then re-run the Phase A panel (gate-verify) to PASS and continue to decomposition.
- The mechanical should-fixes (T1/R2/A5, T2, R3, R4, R5, R6, A4) are track-file edits worth applying under any option; they were left unapplied so the resolution can address them coherently rather than piecemeal over a plan that may be rewritten.
- Adversarial ran on **Fable 5** this session (the D14 `design_gate=yes` pin held — earlier branches recorded Fable unavailable; it was available here).
