# Track 4: Pointers in implementer + commit-convention files (Tier A + Tier B split)

## Purpose / Big Picture
After this track lands, the implementer rulebook, the per-step orchestrator protocol, the commit-message convention, and the episode-format reference all name house-style — Tier-A for the durable log / commit / PR / episode artifacts they produce, Tier-B for any code comments the implementer adds.

<!-- Reserved for Move 2. -->

Adds Tier-A pointer to `implementer-rules.md § Tooling discipline` for log / commit / PR prose, and Tier-B pointer for code-comment prose. Adds matching pointers in `step-implementation.md` (continuous-log + step-episode writes — Tier A), `commit-conventions.md` (message-body discipline — Tier A), and `episode-format-reference.md` (episode prose — Tier A).

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-20T03:32Z [ctx=safe] Review + decomposition complete
- [x] 2026-05-20T05:55Z [ctx=safe] Step 1 complete (commit 4a7b4fd770)
- [x] 2026-05-20T06:08Z [ctx=safe] Step 2 complete (commit 233428524c)

## Surprises & Discoveries
- 2026-05-20T06:08Z — Markdown-link-form pointers must keep the citation string `conventions.md §1.5 Writing style for Markdown and prose artifacts` un-wrapped on a single line. Line-wrapping at the surrounding paragraph's column splits the literal substring across two physical lines and breaks the line-oriented `grep -l` acceptance check without obvious failure feedback. Track 3's bare-backticked form is naturally single-line; Track 4 (and any future pointer track using the link form) must enforce single-line layout explicitly. Relevant to Track 5 if it touches any pointer text. See Episodes §Step 2.

## Decision Log
<!-- Empty at Phase 1. -->

<!-- Reserved for Move 1. -->

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 1 (4 findings, 4 accepted). T1 (should-fix, colon-list anchor trap) and T3 (suggestion, body-discipline anchor specificity) absorbed into the `## Concrete Steps` per-step descriptions — every step names a pre-scan for the colon-terminated-lead-in-before-list shape and the exact insertion anchor in each target file. T2 (should-fix, heading-slug marker form) and T4 (suggestion, sub-step numbering `6` → `7`) applied directly to `## Context and Orientation`.

## Context and Orientation

The implementer's prose surfaces span two distinct tiers, and `implementer-rules.md` is the only file in scope that needs both pointers in one location:

- **Tier-A surfaces** the implementer produces: commit message bodies (long-form `why`), episode-draft fields (`what_was_done`, `what_was_discovered`, `what_changed_from_plan`, `critical_context`), fix-notes (`what_was_fixed`, `what_was_skipped`, `what_was_discovered`), and CROSS_TRACK_HINTS prose. All these surfaces land in durable git-tracked artifacts.
- **Tier-B surfaces** the implementer produces: code comments, Javadoc bodies, test method names and descriptions where prose creeps in.

`implementer-rules.md § Tooling discipline` (lines ~911-936 in the file read in research) is the existing pointer block for cross-references to other discipline files. The new house-style pointer follows the same shape:

> - **House-style for prose**: `.claude/output-styles/house-style.md` is the rule set. Tier A (full house-style: BLUF lead, banned vocabulary, em-dash discipline, ≤200-word section cap, structural rules) applies to commit message bodies, episode-draft / fix-notes prose, CROSS_TRACK_HINTS, and any other durable artifact text. Tier B (AI-tell subset, structural rules do not apply) applies to code comments and Javadoc bodies; the four section slugs to consult are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the workflow-level pointer.

`step-implementation.md` is the per-step orchestration protocol; its prose-producing sub-steps (sub-step 5 cross-track impact check, sub-step 7 episode synthesis) produce track-file episode entries that land in Markdown. Needs a Tier-A pointer.

`commit-conventions.md` is the canonical commit-message discipline file. Its content names the imperative summary, the body's WHY discipline (the `reason:` body-line rule lives inside `## Commit type prefixes`), the Push-every-commit rule, and the `Review fix:` prefix convention. Needs a Tier-A pointer near the body-discipline content inside `## Commit type prefixes`.

`episode-format-reference.md` is the canonical episode-format reference file. Episodes are prose entries the orchestrator writes into track files. Needs a Tier-A pointer.

## Plan of Work

The track delivers in two steps:

Step 1 — Add the Tier-A + Tier-B pointer block to `implementer-rules.md § Tooling discipline`, and add the Tier-A pointer to `episode-format-reference.md`. These two files share the strongest writer-of-episode-prose relationship; the pointer wording is the same in both modulo file-specific scope language.

Step 2 — Add the Tier-A pointer to `step-implementation.md` and `commit-conventions.md`. These cover the orchestrator-side episode synthesis and the commit-body discipline.

Ordering constraints: Track 1 must complete first; pointer text cites the conventions.md anchor by name. Tracks 2, 3, 5 are independent.

Invariants to preserve: every modified file's existing § headings stay intact. The pointer block in `implementer-rules.md § Tooling discipline` reads as one bullet (matching the existing bullets in that section), not as a new section. No file gets more than one pointer block.

## Concrete Steps

1. Insert the canonical house-style pointer paragraph into `.claude/workflow/implementer-rules.md § Tooling discipline` (as a sixth bullet appended to the existing pointers list at the end of the section, immediately before the closing `---` separator that precedes `## Coverage gate command`) AND into `.claude/workflow/episode-format-reference.md` (appended to the intro block immediately before the first `---` separator that precedes `## Write checklist (commit-then-episode)`). The implementer-rules.md bullet carries both Tier-A and Tier-B language per the § Context template; the episode-format-reference.md pointer is Tier-A only. Before patching each file, scan ±5 lines around the chosen anchor and confirm the insertion does NOT fall between a colon-terminated lead-in and the enumerated list the colon introduces (Track 3 F1 hazard). Use native `Edit` per the Track 3 fallback (steroid_apply_patch is not exposed in implementer spawns). Validation: `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' .claude/workflow/implementer-rules.md .claude/workflow/episode-format-reference.md | wc -l` returns `2`; `awk '/^---$/{c++} END{print c}'` on each file returns the pre-change separator count plus zero (insertions do not add `---` lines). — risk: low (default: pure documentation insertion; no semantic change)  [x]  commit: 4a7b4fd770

2. Insert the canonical Tier-A house-style pointer paragraph into `.claude/workflow/step-implementation.md` (appended after the five-bullet "The episode includes:" list ends at line 816, before the paragraph "Write the episode to the track file …") AND into `.claude/workflow/commit-conventions.md` (appended after the `reason:` slug table inside `## Commit type prefixes` ends at line 148, before the "Branch-only commit messages may cite workflow-internal identifiers …" paragraph at line 149). Both pointers are Tier-A only per the § Context guidance — code-comment scale does not apply to per-step orchestration prose or commit-body discipline. Before patching each file, scan ±5 lines around the chosen anchor and confirm the insertion does NOT fall between a colon-terminated lead-in and the enumerated list the colon introduces. Use native `Edit` per the Track 3 fallback. Validation: `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' .claude/workflow/step-implementation.md .claude/workflow/commit-conventions.md | wc -l` returns `2`; `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' .claude/workflow/implementer-rules.md .claude/workflow/step-implementation.md .claude/workflow/commit-conventions.md .claude/workflow/episode-format-reference.md | wc -l` returns `4` (cumulative across both steps). — risk: low (default: pure documentation insertion; no semantic change)  [x]  commit: 233428524c

## Episodes

### Step 1 — commit 4a7b4fd770, 2026-05-20T05:55Z [ctx=safe]
**What was done:** Appended a sixth bullet to `.claude/workflow/implementer-rules.md § Tooling discipline` carrying both Tier-A scope (commit message bodies, episode-draft and fix-notes prose, `CROSS_TRACK_HINTS`, durable artifact text) and Tier-B scope (code comments, Javadoc bodies, test method names and descriptions). Appended a Tier-A-only paragraph to the intro block of `.claude/workflow/episode-format-reference.md` before the first `---` separator. Both pointers reference `conventions.md §1.5` and name the four banned-section heading slugs verbatim (`## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`). Acceptance grep `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts'` over the two files returns `2`.

**What was discovered:** The Step 1 implementer used the form `[conventions.md §1.5 Writing style for Markdown and prose artifacts](conventions.md)` (Markdown link, relative path, no backticks) for the citation in both files. Track 3's 28 pointer sites use the bare-backticked form `` `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` `` (no link, single backticks around the whole reference). Both forms satisfy the literal-substring acceptance grep, so the rename gate stays operational, but Track 4's pointer wording is not byte-identical to Track 3's. The divergence is intentional: relative-path link form fits the surrounding pointer style of `implementer-rules.md § Tooling discipline` (sibling bullets use the same `[\`file\`](file)` shape), and `episode-format-reference.md` lives in the same directory as `conventions.md` so the relative path resolves cleanly. Step 2 of this track will follow the same link form for in-track consistency; Track 3's 28 sites stay as-is.

**What changed from the plan:** none.

**Key files:**
- `.claude/workflow/implementer-rules.md` (modified) — sixth bullet at end of `§ Tooling discipline` before the closing `---`
- `.claude/workflow/episode-format-reference.md` (modified) — paragraph appended to intro block before the first `---` separator

**Critical context:** none.

### Step 2 — commit 233428524c, 2026-05-20T06:08Z [ctx=safe]
**What was done:** Inserted Tier-A-only house-style pointer paragraphs into the two remaining in-scope files. `.claude/workflow/step-implementation.md` gained the pointer after the five-bullet "The episode includes:" list at line 816 and before the "Write the episode to the track file on disk." paragraph; scope wording names the four episode fields the orchestrator finalises from `EPISODE_DRAFT` plus the Progress / Surprises & Discoveries / Decision Log entries emitted during sub-step 7. `.claude/workflow/commit-conventions.md` gained the pointer after the `reason:` slug discussion ends and before the "Branch-only commit messages may cite workflow-internal identifiers" paragraph; scope wording names commit message bodies (the long-form `why` block beneath the imperative subject line) and `reason:` slug body lines. Both pointers cite `.claude/output-styles/house-style.md`, name the four banned-section heading slugs verbatim, and use the link form `[conventions.md §1.5 Writing style for Markdown and prose artifacts](conventions.md)` for in-track consistency with Step 1. Cumulative grep across all four in-scope files returns `4`.

**What was discovered:** The implementer's first `Edit` pair wrapped the link text across a newline boundary inside the paragraph, breaking the literal-substring acceptance grep (matched 0 instead of 2). The fix kept the entire `See [conventions.md §1.5 …](conventions.md) for the workflow-level pointer.` clause on a single line, matching Step 1's layout. This is an operational constraint for any future Markdown-link-form pointer: the `conventions.md §1.5 Writing style for Markdown and prose artifacts` string must stay un-wrapped on one line so the literal-substring acceptance grep stays operational. Promoted to `## Surprises & Discoveries` as a cross-track signal for Track 5.

**What changed from the plan:** none.

**Key files:**
- `.claude/workflow/step-implementation.md` (modified) — paragraph at the end of "Episode Production" subsection, after the five-bullet field list and before the "Write the episode" follow-on
- `.claude/workflow/commit-conventions.md` (modified) — paragraph inside `## Commit type prefixes`, after the `reason:` slug discussion and before the "Branch-only commit messages" paragraph

**Critical context:** none.

## Validation and Acceptance

- `implementer-rules.md § Tooling discipline` carries both the Tier-A and the Tier-B pointer in one bullet (verified by `grep -A2 'house-style' implementer-rules.md` showing both tier references).
- `step-implementation.md`, `commit-conventions.md`, and `episode-format-reference.md` each carry one Tier-A pointer (verified by `grep -l 'house-style'` returning all four files).
- The pointer wording is consistent across the four files (modulo file-specific scope language).
- YTDB-837 acceptance bullet 4 holds: "Implementer files name both tiers correctly: Tier-A for log / commit / PR, Tier-B for comments."

**Per-step acceptance:**

- **Step 1** — `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' .claude/workflow/implementer-rules.md .claude/workflow/episode-format-reference.md | wc -l` returns `2`. The implementer-rules.md bullet contains both the Tier-A and the Tier-B scope language. The episode-format-reference.md pointer is Tier-A only. Neither file's pre-existing structure changes (existing headings and `---` separators preserved).
- **Step 2** — `grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' .claude/workflow/step-implementation.md .claude/workflow/commit-conventions.md | wc -l` returns `2`. The step-implementation.md pointer is Tier-A only. The commit-conventions.md pointer is Tier-A only and lands inside `## Commit type prefixes` near the body-discipline content. Cumulative track-wide grep across all four files returns `4`.

<!-- Reserved for Move 3. -->

## Idempotence and Recovery

Both steps are pure-Markdown insertions with byte-identical pointer wording (modulo Step 1's dual-tier vs Step 2's Tier-A-only language). Each step is one commit; per-file `Edit` calls within a step are independent and can be retried individually if one fails.

**Re-runnable audits** (paste into terminal to verify track state at any time):

```bash
# 1. Cumulative pointer presence across the 4 in-scope files (expect 4 after Step 2 lands; expect 2 after Step 1 lands):
grep -l 'conventions.md §1.5 Writing style for Markdown and prose artifacts' \
  .claude/workflow/implementer-rules.md \
  .claude/workflow/step-implementation.md \
  .claude/workflow/commit-conventions.md \
  .claude/workflow/episode-format-reference.md | wc -l

# 2. Step 1 dual-tier check on implementer-rules.md (expect both tier names to appear in the bullet):
grep -B1 -A4 'conventions.md §1.5' .claude/workflow/implementer-rules.md | grep -c 'Tier A\|Tier B'
# Expect: 2 (one match for "Tier A" and one for "Tier B").
```

**Failure paths:**

- Per-file `Edit` returns non-unique-match → the anchor text drifted between research and execution. Re-read the target file, derive a unique anchor (extend the `old_string` with more surrounding context), and retry the failing `Edit` only. The other file in the same step (which may already have landed) does not need re-running.
- Colon-list pre-scan flags an unsafe anchor → choose the alternative anchor named in the Technical Review T1 fix (e.g., for `commit-conventions.md` the safe insertion point is after the slug table, not between the colon-terminated lead-in and the slug table).
- Commit lands with only one file edited (the other file's `Edit` failed silently) → run audit #1 above before the next step. If the cumulative count is below the expected value for the step's commit, amend the commit with the missing file's edit applied via `Edit`.

The track-3.md re-runnable audit pattern is the precedent.

## Artifacts and Notes
<!-- Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/implementer-rules.md` (add Tier-A + Tier-B pointer block in § Tooling discipline)
- `.claude/workflow/step-implementation.md` (add Tier-A pointer)
- `.claude/workflow/commit-conventions.md` (add Tier-A pointer in body-discipline section)
- `.claude/workflow/episode-format-reference.md` (add Tier-A pointer)

**Out-of-scope files:**
- All other workflow files (covered by Tracks 3, 5 or out of scope per YTDB-837).

**Inter-track dependencies:**
- **Upstream**: Track 1 (cross-references the new conventions.md section heading).
- **Downstream**: none.

**Compatibility requirements:**
- `implementer-rules.md` is read by the implementer sub-agent on every spawn. Adding one bullet under § Tooling discipline does not change the sub-agent contract.
- `commit-conventions.md` is read at every commit time by both the implementer and the orchestrator. The pointer is additive to existing rules.

**Library / function signatures relevant to this track:** none — pure documentation edits.

## Base commit

77df0bd936cec7cc6c8cf8d9a29e06c34f48e685
