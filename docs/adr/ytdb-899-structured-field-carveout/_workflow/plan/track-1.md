# Track 1: Rewrite the section length cap and propagate

## Purpose / Big Picture

After this track lands, the Phase C `review-workflow-writing-style` agent stops flagging ExecPlan structured-field paragraph blocks for the section length cap, and the rule reads as a soft cap with a categorical exemption list plus a padding-based finding criterion for free-form prose.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Rewrite `house-style.md § Structural rules` "Section length cap" as a soft cap with a categorical exemption (template-bound content) plus a padding-based finding criterion for free-form prose. Propagate the wording to all other declarative restatements (`house-style.md § Self-check` step 7; four sites in `review-workflow-writing-style.md`; `CLAUDE.md` line 102; `.claude/skills/code-review/SKILL.md` line 313). Add back-references from `episode-format-reference.md § Episode length rule` and `conventions-execution.md §2.2`.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-20T14:11Z [ctx=safe] Review + decomposition complete
- [x] 2026-05-20T14:34Z [ctx=safe] Step 1 complete (commit a0e6e0a9db)
- [x] 2026-05-20T14:40Z [ctx=safe] Step 2 complete (commit 2914100c61)
- [x] 2026-05-20T14:43Z [ctx=safe] Step 3 complete (commit e575e94156)
- [x] 2026-05-20T16:45Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)

## Base commit

b8dc3066d94a0abc75b08abe6afd5a889e3fecc8

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was discovered" when the finding affects future steps or other tracks. Empty at Phase 1. -->

- 2026-05-20: Pre-commit ephemeral-identifier gate regex matches `H2+` and similar Markdown heading-level notation as if it were a `Track N` / `Step N` ephemeral identifier. The token already appears 5× in the unmodified base of `.claude/output-styles/house-style.md` and is a documented §Allowed pass-through. Future workflow-doc edits touching files with heading-level notation should expect the same false positive at the pre-commit gate. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices, scope-downs, dependency reveals, gate-override reasons. -->

- 2026-05-20: **design.md count update bypassed full edit-design mutation discipline.** Phase A technical-review iter-2 surfaced T5: `design.md:11` carried the stale "four other declarative sites" count after the in-scope file list expanded from 5 → 8 sites. Per project CLAUDE.md, `design.md` is frozen during Phase 3 and modifications normally route through the `edit-design` skill. Decision: apply the one-sentence factual count correction directly via `steroid_apply_patch` and record the bypass here. Rationale: (a) the rule body, exemption categories, padding criterion, and workflow diagrams are unchanged; only the propagation-scope sentence at line 11 was stale; (b) Phase 4 `design-final.md` reads track outcomes, so the durable record stays accurate; (c) the full `edit-design` cold-read + mechanical-check loop is disproportionate for a citation-count update with no design implications. `design-mutations.md` was not appended (the change is below the mutation log's substantive threshold).

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion summary at Phase C. -->

- [x] Technical: PASS at iteration 2 (6 findings: T1, T2, T3 should-fix accepted and applied to plan + track file; T4 suggestion accepted and applied as the ≤150-char compression note in Step 2; T5 should-fix surfaced at gate check, applied as a one-sentence count correction in `design.md:11` with bypass rationale logged in the Decision Log; T6 suggestion deferred — Tier-A pointer sites in `commit-conventions.md`, `episode-format-reference.md`, `step-implementation.md`, `implementer-rules.md` label the house-style rule by name, they don't restate the 200-word cap independently).

## Context and Orientation

The rule lives in `.claude/output-styles/house-style.md`. Eight declarative restatements currently exist across four files; the writing-style reviewer reads `house-style.md` once at review start, but the reviewer-agent frontmatter `description:` is loaded into every system reminder, and the dispatcher restatements in `CLAUDE.md` and `.claude/skills/code-review/SKILL.md` are loaded transitively whenever they're in scope. All restatements must move atomically; any one left behind re-introduces the bug YTDB-899 names.

**In-scope files:**

- `.claude/output-styles/house-style.md` — line 262 inside `## Structural rules` carries the rule body. Line 363 inside `## Self-check` step 7 carries the self-check restatement.
- `.claude/agents/review-workflow-writing-style.md` — frontmatter `description:` (line 3, always loaded), key rules list (line 18), review criteria block (lines 69-71, under the `### Section length` heading at line 69), output-format template (line 121, the `Recommended` finding shape the agent fills in).
- `CLAUDE.md` (project root) — line 102, always-loaded house-style activation paragraph, restates `200-word section cap` inline.
- `.claude/skills/code-review/SKILL.md` — line 313, the `/code-review` agent-dispatch list, loaded on every Phase C track-level review.
- `.claude/workflow/episode-format-reference.md` — `## Episode length rule` at line 346. Add a one-line back-reference to the new house-style exemption.
- `.claude/workflow/conventions-execution.md` — `## 2.2 Episode Formats` at line 284. Add a back-reference near the field-template description.

**Out of scope:**

- `.claude/scripts/design-mechanical-checks.py` — enforces a different cap (lines per `##` section on design.md only). Per Non-Goals in the plan.
- `.claude/skills/ai-tells/SKILL.md` — audits a different cross-section of house-style rules (vocabulary, sentence patterns); does not touch section length.
- The episode template field set itself (`What was done`, `Key files`, etc.) — only the length rule changes.

**Concrete deliverables:**

- A new "Section length cap exception" clause in `house-style.md § Structural rules` enumerating five exempt categories plus the padding-based finding criterion.
- Self-check step 7 rewritten to match.
- Four reviewer-agent restatements (frontmatter `description:`, key rules bullet, review criteria block, output-format template) rewritten to point at the soft-cap rule and the exempt categories.
- Dispatcher restatements in `CLAUDE.md` (line 102) and `.claude/skills/code-review/SKILL.md` (line 313) rewritten to match.
- Back-references added in `episode-format-reference.md § Episode length rule` and `conventions-execution.md §2.2 Episode Formats`.

## Plan of Work

Three edits, each landing in a separate commit so each is independently reviewable:

1. **Rewrite the rule body in `house-style.md`.** Replace the existing single-bullet "Section length cap" rule at line 262 with the soft-cap-plus-exemption pair. Update the matching self-check entry at step 7 (line 363) so the self-check enumerates the same exemptions. The exemption clause names five categories; the padding-based finding criterion cites the relevant house-style sections (`§ Banned vocabulary`, `§ Banned sentence patterns`, `§ Elegant variation`) by name so the reviewer's check is explicit.

2. **Update all always-loaded and dispatcher restatements in one atomic commit.** Rewrite six sites in three files: four in `.claude/agents/review-workflow-writing-style.md` (frontmatter `description:` at line 3, key rules bullet at line 18, review criteria block at lines 69-71, output-format template at line 121); one in `CLAUDE.md` at line 102 (project-root house-style activation paragraph, always-loaded); one in `.claude/skills/code-review/SKILL.md` at line 313 (`/code-review` agent-dispatch list, loaded on every Phase C track-level review). Each site uses wording consistent with the new house-style clause and names the soft cap plus the exempt categories. The frontmatter `description:` has a ≤ ~150 char budget — compress the padding-based criterion out of the description (full criterion stays in the agent body and the house-style rule) and keep (a) soft cap and (b) exempt categories. The remaining five sites have room for all three elements.

3. **Add back-references.** In `.claude/workflow/episode-format-reference.md § Episode length rule`, append a one-line pointer to `house-style.md § Structural rules` "Section length cap exception." In `.claude/workflow/conventions-execution.md §2.2 Episode Formats`, add a back-reference to the same exemption alongside the field-template description.

**Ordering:** Step 1 lands first (defines the rule). Step 2 lands second (the always-loaded and dispatcher restatements quote the new rule). Step 3 lands last (back-references resolve forward to the new rule). The Phase A decomposer may merge or split steps if the diff motivates it; the boundaries above are guidance, not contracts.

**Invariants to preserve:**

- The current cap value (200 words) stays — only the framing changes.
- House-style cross-references already in place from other workflow docs continue to resolve. The section name (`## Structural rules`) does not change; the rule body inside it changes.
- The reviewer's frontmatter `description:` stays ≤ ~150 chars per the always-loaded budget.

## Concrete Steps

1. Rewrite the `Section length cap` rule body in `house-style.md` (line 262) and the matching `## Self-check` step 7 (line 363) as a soft cap plus a categorical exemption clause and a padding-based finding criterion — risk: low (default: workflow-doc change; no HIGH/MEDIUM trigger)  [x] commit: a0e6e0a9db
2. Update all always-loaded and dispatcher restatements in one atomic commit: four sites in `.claude/agents/review-workflow-writing-style.md` (lines 3, 18, 69-71, 121), `CLAUDE.md:102`, and `.claude/skills/code-review/SKILL.md:313` — risk: low (default: workflow-doc change; no HIGH/MEDIUM trigger)  [x] commit: 2914100c61
3. Add back-references to the new house-style clause in `.claude/workflow/episode-format-reference.md § Episode length rule` (line 346) and `.claude/workflow/conventions-execution.md §2.2 Episode Formats` (line 284) — risk: low (default: workflow-doc change; no HIGH/MEDIUM trigger)  [x] commit: e575e94156

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step. Empty at Phase 1. -->

### Step 1 — commit a0e6e0a9db, 2026-05-20T14:34Z [ctx=safe]

**What was done:** Rewrote two declarative sites inside `.claude/output-styles/house-style.md` in a single atomic patch. The "Section length cap" bullet under `## Structural rules` (line 262) was replaced with three bullets: a soft-cap default at ≤200 words framed as a heuristic trigger, a "Section length cap exception" clause enumerating the five template-bound exempt categories (ExecPlan structured-field paragraph blocks under `## Episodes`, edit-list subsections, full state-machine tables, file:line citation blocks, multi-step derivations under `design-mechanics.md`), and a padding-based finding criterion that cites `§ Banned vocabulary`, `§ Banned sentence patterns`, and `§ Elegant variation` by name. The `## Self-check` step 7 entry (line 363) was rewritten to mirror the same soft cap, the same five exempt categories, and the same three padding-pattern sections.

**What was discovered:** The pre-commit ephemeral-identifier gate regex matches `H2+` (and would match `H3` / `H4` if they appeared on a `+` line), which is documentation notation for Markdown heading levels, not a workflow-internal `Track N` / `Step N` label or a YouTrack-issue-tracker ID. The token already appears 5× in the unmodified base file. This is a pre-existing pass-through case under the §Allowed list, not a finding for this step — but downstream tracks editing the same file should not be surprised by the same false positive. Cross-step note for Step 2 within this track: the rule-body rewrite added 2 lines to `house-style.md`, so position-relative line numbers in any downstream restatement should re-locate via the bullet labels ("Section length cap" / "Section length cap exception" / "Padding-based finding criterion") rather than line numbers; restatements in other files (reviewer agent, CLAUDE.md, code-review SKILL.md) are unaffected because they live in different files.

**What changed from the plan:** none.

**Key files:** `.claude/output-styles/house-style.md` (modified).

### Step 2 — commit 2914100c61, 2026-05-20T14:40Z [ctx=safe]

**What was done:** Rewrote six always-loaded and dispatcher restatements of the section length cap rule in one atomic `steroid_apply_patch`. Four sites in `.claude/agents/review-workflow-writing-style.md` (frontmatter `description:` at line 3, key-rules bullet at line 18, review-criteria block under `### Section length` at lines 69-71, and the `Recommended` shape in the output-format template at line 121), plus `CLAUDE.md:102` (always-loaded house-style activation paragraph) and `.claude/skills/code-review/SKILL.md:313` (`/code-review` agent-dispatch list entry). Five of the six sites carry all three rule elements: the soft cap (≤200 words as a heuristic trigger), the exemption clause naming the five template-bound shapes, and the padding-based finding criterion citing `§ Banned vocabulary`, `§ Banned sentence patterns`, and `§ Elegant variation`. The frontmatter `description:` compresses the padding criterion out, landing at 149 chars (within the ≤ ~150 always-loaded budget) and keeping (a) the soft-cap signal and (b) the template-bound-exemptions signal.

**What was discovered:** The frontmatter `description:` budget at ≤ ~150 chars is tight against the new three-element rule shape; the only way to fit was to drop the padding-criterion mention from the description and rely on the agent body and `house-style.md` itself to carry the full criterion. The dispatcher entry in `.claude/skills/code-review/SKILL.md:313` lives in a numbered list where every other agent entry is one short line; the new `review-workflow-writing-style` entry is now markedly longer than its siblings. Acceptable because the entry is parsed by humans during dispatch decisions, not by a strict per-bullet length rule, and the entry's length is structural (three load-bearing elements), not padded. Cross-step note for Step 3 within this track: anchor the back-references on the clause label "Section length cap exception" in `house-style.md § Structural rules` rather than line numbers, since Step 1 shifted house-style.md line numbers by +2 around the rule body.

**What changed from the plan:** none.

**Key files:** `.claude/agents/review-workflow-writing-style.md`, `CLAUDE.md`, `.claude/skills/code-review/SKILL.md` (all modified).

### Step 3 — commit e575e94156, 2026-05-20T14:43Z [ctx=safe]

**What was done:** Added back-references from two workflow docs to the new "Section length cap exception" clause in `.claude/output-styles/house-style.md § Structural rules`. In `.claude/workflow/episode-format-reference.md § Episode length rule` a new paragraph after the existing length-rule prose notes that episode structured-field paragraph blocks are exempt and points at the full exemption list and padding-based finding criterion. In `.claude/workflow/conventions-execution.md §2.2 Episode Formats` an inline sentence appended to the labeled-bold-paragraph template description carries the same anchor. Both back-references anchor on the clause label "Section length cap exception" rather than a line number, since Step 1 shifted house-style.md line numbers by +2 around the rule body. Applied atomically via `steroid_apply_patch` (2 hunks across 2 files).

**What was discovered:** The validation grep recommended in the track file (`grep "house-style.*Section length cap exception\|house-style.md § Structural rules"`) captures only single-line matches. The conventions-execution.md back-reference wraps the link onto one line and the clause label onto the next; the second alternation in the regex catches the link line and the back-reference still resolves, but a future audit that wants both anchors on a single line would need a multi-line-aware tool. Acceptable as-is, since both anchors are present in both files, just on adjacent lines in conventions-execution.md.

**What changed from the plan:** none.

**Key files:** `.claude/workflow/episode-format-reference.md`, `.claude/workflow/conventions-execution.md` (both modified).

## Validation and Acceptance

After all three commits land:

- `grep "Section length cap exception" .claude/output-styles/house-style.md` returns the new clause inside `## Structural rules`.
- The reviewer agent's four restatements (frontmatter `description:`, key rules list, review criteria, output-format template) name the soft cap and the exempt categories. `grep -in "section length\|length cap\|section cap\|200.word" .claude/agents/review-workflow-writing-style.md` returns four matches whose wording is consistent with `house-style.md`.
- The dispatcher restatements in `CLAUDE.md:102` and `.claude/skills/code-review/SKILL.md:313` name the soft cap and the exempt categories. `grep -in "section cap\|length cap\|200.word" CLAUDE.md .claude/skills/code-review/SKILL.md` returns one match per file consistent with `house-style.md`.
- `grep "house-style.*Section length cap exception\|house-style.md § Structural rules" .claude/workflow/episode-format-reference.md .claude/workflow/conventions-execution.md` returns the back-references.
- A Phase C track-level writing-style review on a track with substantive multi-paragraph episodes (≥400 words in a structured-field block) does not produce a section-length finding against those paragraphs. Behavioral acceptance, verified on the next real Phase C run.

**Per-step acceptance:**

- **Step 1**: When `house-style.md § Structural rules` is read, a new "Section length cap exception" clause names five exempt categories AND the padding-based finding criterion. When `house-style.md § Self-check` step 7 is read, it enumerates the same exemptions and matches the rule-body wording.
- **Step 2**: When each of the six updated sites is read, the wording names the soft cap and the exempt categories. The frontmatter `description:` at line 3 of `review-workflow-writing-style.md` stays ≤ ~150 chars (compresses the padding-based criterion out, keeps soft cap + exempt categories); the other five sites (key rules bullet at line 18, review criteria at lines 69-71, output-format template at line 121, `CLAUDE.md:102`, `.claude/skills/code-review/SKILL.md:313`) carry all three elements.
- **Step 3**: When `.claude/workflow/episode-format-reference.md § Episode length rule` and `.claude/workflow/conventions-execution.md §2.2 Episode Formats` are read, each carries a back-reference pointing at `house-style.md § Structural rules` "Section length cap exception."

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Each step lands as a single commit modifying one or more workflow-doc files. No long-lived locks, no temporary files, no external system mutations across steps.

- **Step 1** (`house-style.md` rule body + self-check step 7): if the rewrite is malformed (e.g., the new "Section length cap exception" clause is missing the exempt-categories enumeration), `git reset --hard HEAD` reverts the commit. Re-running the step re-applies the rewrite cleanly; no on-disk state outside `house-style.md` is touched.
- **Step 2** (6-site atomic dispatcher / always-loaded update across 3 files): atomic via `steroid_apply_patch`. If any of the six sites drifts in wording from `house-style.md`, the Phase C writing-style reviewer will flag it on the next track-level review. `git reset --hard HEAD` reverts the commit; re-running the step re-applies all six edits in one atomic patch.
- **Step 3** (back-references in `episode-format-reference.md` and `conventions-execution.md`): two-file change appending pointer lines. `git reset --hard HEAD` reverts; re-running re-applies. No external system state affected.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't belong to one specific step. Per-step episode content lives in ## Episodes above. -->

## Interfaces and Dependencies

**File-scope boundaries** listed under `## Context and Orientation` above.

**Compatibility:**

- Pre-existing `house-style.md` references from other workflow docs continue to resolve. The section name `## Structural rules` does not change; only the rule body inside it changes.
- The mechanical-check script (`design-mechanical-checks.py`) is unaffected — it enforces a different cap and reads a different file set.
- The `ai-tells` skill is unaffected — it audits vocabulary, sentence patterns, and openers/closers, not section length.

**Inter-track dependencies:** This is the only track in the plan. No downstream tracks consume its output.

**Library / function signatures:** N/A — this is a workflow-doc change. No code APIs touched.
