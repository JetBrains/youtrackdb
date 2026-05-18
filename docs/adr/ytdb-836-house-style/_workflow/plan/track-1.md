# Track 1: Rename `concise-doc.md` → `house-style.md` and consolidate declarative rules

## Purpose / Big Picture
After this track lands, the project has one declarative source of writing-style rules (`.claude/output-styles/house-style.md`) and every existing reference to the old name has been updated, so the acceptance gate `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md --exclude-dir=_workflow` returns zero matches. The `--exclude-dir=_workflow` flag scopes the gate to live in-scope files; the planning artifacts under `docs/adr/ytdb-836-house-style/_workflow/` legitimately reference the old name and are removed by the Phase 4 cleanup commit before merge.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Move `concise-doc.md` to `house-style.md` via `git mv`, rewrite its content per `design.md § Internal layout of house-style.md` (absorbing ai-tells Tier-3 vocab + extra rules, design-review.md's Human-reader cold-read additions, design-review.md's Structural findings, and the 12 humanizer-gap patterns with inline before/after examples), update the frontmatter `name:` and `description:` to name the broader scope, and find-and-replace the 14 string references to `concise-doc` / `Concise Doc` across `CLAUDE.md`, `.claude/skills/code-review/SKILL.md`, `.claude/agents/review-workflow-consistency.md`, `.claude/agents/review-workflow-writing-style.md`, and the renamed source file's own frontmatter. The acceptance check is `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md --exclude-dir=_workflow` returning zero matches.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [x] Track completion
- [x] 2026-05-18T03:47Z [ctx=safe] Review + decomposition complete
- [x] 2026-05-18T03:54Z [ctx=safe] Step 1 complete (commit 79ae898423)
- [x] 2026-05-18T04:01Z [ctx=safe] Step 2 complete (commit 1980178c1f)
- [x] 2026-05-18T04:05Z [ctx=safe] Step 3 complete (commit e8ed393fa8)
- [x] 2026-05-18T04:05Z [ctx=safe] Step implementation
- [x] 2026-05-18T04:26Z [ctx=safe] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-05-18T04:30Z [ctx=safe] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was discovered" when the finding affects future steps or other tracks. Empty at Phase 1. -->

- 2026-05-18T04:01Z — `house-style.md` H2 section names are now fixed on `origin`. The trailing parenthetical on **"Document-shape rules (design / ADR-specific)"** is part of the canonical name; downstream tracks (2, 3, 4) must cite the exact form. The 10 canonical H2 names are: "What this style governs", "BLUF lead", "Voice and tone", "Banned vocabulary", "Banned sentence patterns", "Banned analysis patterns", "Punctuation and typography", "Structural rules", "Document-shape rules (design / ADR-specific)", "Self-check". A raw `grep -c '^## '` returns 12 because two `## WAL replay` lines appear inside the Fragmented-headers example's fenced block; the 10 real H2 sections match the design. See Episodes §Step 2.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices, scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion summary at Phase C. -->
- [x] Technical: PASS at iteration 2 (4 findings, 2 accepted). T1 (blocker) — acceptance-gate command would not return zero during Phase 3 because `docs/adr/ytdb-836-house-style/_workflow/` carries 54 legitimate references; fixed by adding `--exclude-dir=_workflow` to all 8 gate-command sites across `implementation-plan.md` (3) and `track-1.md` (5). T2 (should-fix) — `## Validation and Acceptance` mis-located 2 of 12 humanizer patterns under `§ Banned analysis patterns`; reworded to match design's three-section ownership (10 / 1 / 1). T3 (suggestion) — design.md:243 table cell formatting; rejected, deferred to Phase 4 / `design-final.md` (design.md frozen Phase 1 end → Phase 4 start). T4 (suggestion) — step 2 sizing; rejected as decomposition-time call. Iter 2 gate verification confirmed both fixes landed cleanly with no regression.

## Context and Orientation

This track touches the project's writing-style infrastructure under `.claude/`. The starting state was surveyed during research (see § Find-and-replace surface in `design.md`):

- **Source file (to be renamed):** `.claude/output-styles/concise-doc.md` (92 lines). Frontmatter `name: Concise Doc`. Carries BLUF + Tier-1/2 vocab + banned sentence patterns + em-dash discipline + structural rules + self-check.
- **Companion files (rules to absorb):**
  - `.claude/skills/ai-tells/SKILL.md` lines 27-156 carry Tier-1/2/3 vocab, structural tells, tone tells, punctuation tells, content tells. Tier-3 promotional vocab, vague-attribution rule, knowledge-cutoff disclaimer ban, Title Case heading rule, inline-header-list rule, curly→straight quotes rule, and excessive-boldface cap are *new to house-style.md* (not in concise-doc.md today).
  - `.claude/workflow/prompts/design-review.md` lines 90-172 carry the Human-reader cold-read additions (audience-fit, glossary-introduction, why-before-what, navigability rules) and lines 228-256 carry the Structural findings (Overview concept-first, References footer shape, Edge cases sub-section, same-shape sibling consolidation).
- **String references to update (14 total across 5 files):** `CLAUDE.md:93,102` (2), `.claude/skills/code-review/SKILL.md:313` (1), `.claude/agents/review-workflow-consistency.md:72` (1), `.claude/agents/review-workflow-writing-style.md:3,7,9,11,13,26,62,102,137` (9), and the source file itself `concise-doc.md:2` (1, frontmatter `name:`). Full table in `design.md § Rename: every reference site across the repo`.
- **Humanizer gap patterns (12 new):** drawn from the gap analysis against [blader/humanizer](https://github.com/blader/humanizer). Each needs an inline before/after example block (~5 lines per pattern, ~60 lines total). The 12: superficial -ing analyses, copula avoidance, passive voice and subjectless fragments, filler phrases (`In order to`, `At this point in time`, `Due to the fact that`), excessive hedging (`could potentially possibly`), generic positive conclusions (`the future looks bright`), persuasive authority tropes (`at its core`, `fundamentally`), signposting (`Let's dive in`, `Here's what you need`), fragmented headers, hyphenated word-pair overuse, elegant variation, false ranges.

Concrete deliverables this track produces:

1. New file `.claude/output-styles/house-style.md` — the consolidated declarative source. ~400-500 lines.
2. Removal of `.claude/output-styles/concise-doc.md` (via the `git mv` operation in step 1).
3. Updated `CLAUDE.md § Writing Style for Design Docs and Issues` block referencing the new filename and slash-command name.
4. Updated string references — 14 occurrences total — across the four companion files listed above plus the renamed source file's frontmatter.
5. Verified acceptance criterion: `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md --exclude-dir=_workflow` returns zero matches.

## Plan of Work

The approach moves from file rename to content rewrite to reference sweep, in that order, because the content rewrite needs the new file path to exist and the reference sweep needs to know which files now reference the new name.

1. **`git mv` + frontmatter.** Rename the file in one commit. Update frontmatter `name: Concise Doc` → `name: House Style` and rewrite the `description:` to name the broader scope (design / plan / track / issue / PR / commit-body / comment / status prose). No other content changes in this step — the rename and frontmatter update is the smallest reviewable unit.
2. **Content rewrite — full consolidated section structure.** Rewrite `house-style.md` to match `design.md § Internal layout of house-style.md`. Insert the absorbed rules from `ai-tells` (Tier-3 vocab, knowledge-cutoff disclaimer ban, inline-header-list rule, curly→straight quotes, excessive-boldface cap), from `design-review.md § Human-reader cold-read additions` (audience-fit, glossary-introduction, why-before-what, navigability), from `design-review.md § Structural findings` (Overview concept-first, References footer shape, Edge cases sub-section, same-shape sibling consolidation), and the 12 humanizer-gap patterns each with an inline before/after example block. Update the self-check to reference the new sections.
3. **Find-and-replace sweep.** Update the remaining 13 string references across `CLAUDE.md` (2), `.claude/skills/code-review/SKILL.md` (1), `.claude/agents/review-workflow-consistency.md` (1), and `.claude/agents/review-workflow-writing-style.md` (9) — the 14th reference (the source file's frontmatter `name:`) is covered by step 1. Each is a single-line edit; use the table in `design.md § Rename: every reference site across the repo` as the authoritative checklist. Where the source text says "concise-doc style" or "**concise-doc**" with markdown emphasis, preserve the emphasis style on the replacement.
4. **End-of-track grep verification.** Run `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md --exclude-dir=_workflow` and confirm zero matches. Any remaining match in a live file is a step-back to the FRR sweep. The `--exclude-dir=_workflow` flag is required because the planning artifacts under `docs/adr/ytdb-836-house-style/_workflow/` legitimately document the rename (they reference the old name) and are removed by the Phase 4 cleanup commit before merge.

Ordering constraints:
- Step 1 must precede step 2 (the file must exist at the new path before content goes in).
- Steps 2 and 3 are independent of each other once step 1 lands; the natural order is step 2 then step 3 so the content is in place before references point at it.
- Step 4 is the acceptance gate; it runs last and blocks track completion until it passes.

Invariants to preserve through every step:
- The `/output-style` slash command reads frontmatter `name:`. Step 1's frontmatter update must keep `name:` valid (matches `house-style` after kebab-case normalization).
- The `description:` frontmatter must enumerate the full surface list per the issue's scope split — design / plan / track / issue / PR / commit-body / comment / status prose.
- Cross-references in Tracks 2, 3, 4 will point at `house-style.md § <Section>` by name. The section headings created in step 2 must use the names enumerated in `design.md § Internal layout of house-style.md`; renames after this track lands would break the readers.

## Concrete Steps

1. `git mv .claude/output-styles/concise-doc.md .claude/output-styles/house-style.md`, then update the renamed file's frontmatter `name: Concise Doc` → `name: House Style` and rewrite `description:` to enumerate the full surface (design / plan / track / issue / PR / commit-body / comment / status prose). No body content changes in this commit — the rename + frontmatter is the smallest reviewable unit and preserves git history of `concise-doc.md`. — risk: low (default: docs file rename + frontmatter edit; no project-behavior change)  [x] commit: 79ae898423

2. Rewrite the body of `.claude/output-styles/house-style.md` to match `design.md § Internal layout of house-style.md` — the 9 top-level sections in order (What this style governs, BLUF lead, Voice and tone, Banned vocabulary with Tier 1-4, Banned sentence patterns, Banned analysis patterns, Punctuation and typography, Structural rules, Document-shape rules, Self-check). Absorb the rule set: Tier-3 promotional vocab + knowledge-cutoff disclaimer ban + inline-header-list rule + curly→straight quotes + excessive-boldface cap from `.claude/skills/ai-tells/SKILL.md`; audience-fit, glossary-introduction, why-before-what, navigability from `prompts/design-review.md § Human-reader cold-read additions`; Overview concept-first, References footer shape, Edge cases sub-section required, same-shape sibling consolidation from `prompts/design-review.md § Structural findings`. Write each of the 12 humanizer-gap patterns with an inline before/after example block (~5 lines per pattern) in its assigned section per the design (10 in § Banned analysis patterns; Hyphenated word-pair overuse in § Punctuation and typography; Fragmented headers in § Structural rules). Update Self-check to reference the new sections. Target ~400-500 lines. — risk: low (default: single-file docs content rewrite; no logic, no project-behavior change)  [x] commit: 1980178c1f

3. Find-and-replace sweep updating the 13 remaining `concise-doc` / `Concise Doc` references across 4 live files using the table at `design.md § Rename: every reference site across the repo` as the authoritative checklist: `CLAUDE.md:93,102` (2 occurrences — body prose at :93, slash-command form `/output-style concise-doc` → `/output-style house-style` at :102), `.claude/skills/code-review/SKILL.md:313` (1), `.claude/agents/review-workflow-consistency.md:72` (1), `.claude/agents/review-workflow-writing-style.md:3,7,9,11,13,26,62,102,137` (9). Preserve markdown emphasis style on every replacement (`**concise-doc**` → `**house-style**`, backtick-wrapped paths → backtick-wrapped new paths). After all edits, run the acceptance gate `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md --exclude-dir=_workflow` — must return zero matches before commit; any remaining match is a step-back, fix in place, re-run. Commit the FRR sweep + verification outcome together (the grep is the gate, not a separate commit). — risk: low (default: multi-file string replacement against an explicit checklist; verification is a single grep)  [x] commit: e8ed393fa8

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step, identified by step number + commit SHA. Empty at Phase 1; Phase A does not populate. -->

### Step 1 — commit 79ae898423, 2026-05-18T03:54Z [ctx=safe]

**What was done:**
Renamed `.claude/output-styles/concise-doc.md` to `.claude/output-styles/house-style.md` via `git mv` (history preserved; 95% similarity index in the diff because frontmatter is the only delta). Updated frontmatter `name: Concise Doc` → `name: House Style` and rewrote `description:` to enumerate the full surface list per `design.md § Internal layout of house-style.md` — design / plan / track / issue / PR / commit-body / comment / status prose. Body content is unchanged; the content rewrite is Step 2.

**Key files:**
- `.claude/output-styles/concise-doc.md` (removed via rename)
- `.claude/output-styles/house-style.md` (new at this path; frontmatter updated)

**Critical context:**
The `/output-style` slash command reads frontmatter `name:`, so the canonical invocation after this commit is `/output-style house-style` (kebab-case normalization of `House Style`). Between this commit and Step 3's FRR-sweep commit, the slash-command name documented in `CLAUDE.md:102` still says `concise-doc` — a brief transient sync gap inside the track that Step 3 closes.

### Step 2 — commit 1980178c1f, 2026-05-18T04:01Z [ctx=safe]

**What was done:**
Rewrote the body of `.claude/output-styles/house-style.md` (92 → 366 lines) to the consolidated 9-section structure specified in `design.md § Internal layout of house-style.md`. Frontmatter unchanged from Step 1. Absorbed rules from `ai-tells/SKILL.md` (Tier 3 promotional vocab, Tier 4 era-specific tells, knowledge-cutoff disclaimer ban, inline-header-list rule, curly→straight quotes, excessive-boldface cap, heading-hierarchy), from `prompts/design-review.md § Human-reader cold-read additions` (audience-fit, glossary-introduction, why-before-what, navigability), and from `prompts/design-review.md § Structural findings` (Overview concept-first, Edge cases sub-section, References footer, same-shape sibling consolidation). The 12 humanizer-gap patterns each ship with an inline before/after fenced example, placed per the design (10 under § Banned analysis patterns, Hyphenated word-pair overuse under § Punctuation and typography, Fragmented headers under § Structural rules).

**What was discovered:**
Final length (366 lines) is below the design's 400-500-line estimate by ~34 lines — gap is room saved by tight writing per the file's own "bias toward less text" rule, not missing content. Raw `grep -c '^## '` returns 12 instead of 10 because two `## WAL replay` lines appear inside the Fragmented-headers example's fenced block; the 10 real H2 sections match the design when fenced blocks are stripped. **Cross-track impact (recorded in Surprises & Discoveries):** The H2 section name "Document-shape rules (design / ADR-specific)" carries a trailing parenthetical that downstream tracks (2, 3, 4) must cite verbatim.

**Key files:**
- `.claude/output-styles/house-style.md` (rewritten body; frontmatter unchanged)

**Critical context:**
Track 4's `dsc-ai-tell` finding descriptions cite `house-style.md § <Section>` by name — the section names listed in Surprises & Discoveries are the authoritative reference. The 11 H3 entries under `## Banned analysis patterns` cover 10 humanizer-gap patterns plus Vague attribution (an ai-tells-sourced absorbed rule sharing the same H2 — not a 12th humanizer pattern). The 12th humanizer pattern (Hyphenated word-pair overuse) lives under § Punctuation and typography per the design.

### Step 3 — commit e8ed393fa8, 2026-05-18T04:05Z [ctx=safe]

**What was done:**
Swept the 13 remaining `concise-doc` / `Concise Doc` string references across 4 live files using `design.md § Rename: every reference site across the repo` as the authoritative checklist. Edits landed at the line numbers cited in the step description: `CLAUDE.md:93` (body prose `**Concise Doc**` → `**House Style**` with matching path swap), `CLAUDE.md:102` (slash-command form `/output-style concise-doc` → `/output-style house-style`), `.claude/skills/code-review/SKILL.md:313` ("concise-doc style:" → "house-style:"), 9 sites in `review-workflow-writing-style.md`, and 1 in `review-workflow-consistency.md`. Markdown emphasis preserved on every replacement. Acceptance gate `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md --exclude-dir=_workflow` returns zero matches both before commit and after the orchestrator's re-verification.

**What was discovered:**
mcp-steroid is reachable on this host, but the `house-style` working tree is **not** among the IDE's open projects (only `new-track-format` and `read-cache-concurrency-bug` are loaded). `steroid_apply_patch` was therefore unavailable for the multi-file sweep, so the implementer fell back to native `Edit` — safe for this task because all 13 edits are single-occurrence literal-text replacements on markdown files with no PSI / symbol-resolution implications. For Tracks 2-4 (also docs-only) this is not a blocker; any future Java refactor on this branch would need the user to open the project in IntelliJ first.

**Key files:**
- `CLAUDE.md` (modified)
- `.claude/skills/code-review/SKILL.md` (modified)
- `.claude/agents/review-workflow-consistency.md` (modified)
- `.claude/agents/review-workflow-writing-style.md` (modified)

**Critical context:**
The rename is now complete in all live files (Invariant 1 satisfied: zero grep matches). Downstream tracks (2, 3, 4) can cite `house-style.md § <Section>` with the canonical H2 names listed in this track's Surprises & Discoveries. The slash-command form is now `/output-style house-style` per `CLAUDE.md:102` — agents that suggest the style toggle should use that exact form.

## Validation and Acceptance

Track-level acceptance: `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md --exclude-dir=_workflow` returns zero matches AND `house-style.md` contains every section listed in `design.md § Internal layout of house-style.md` AND each of the 12 humanizer-gap patterns has an inline before/after example in its assigned section per `design.md § Internal layout of house-style.md` (10 patterns under `§ Banned analysis patterns`, `Hyphenated word-pair overuse` under `§ Punctuation and typography`, and `Fragmented headers` under `§ Structural rules`).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

- **Step 1 — `git mv` + frontmatter.** Completion proof: `git status` is clean, `ls .claude/output-styles/house-style.md` succeeds, `ls .claude/output-styles/concise-doc.md` fails, and `head -5 .claude/output-styles/house-style.md` shows `name: House Style`. Recovery if interrupted before commit: `git status` reveals partial state; resolve by either completing the missing edits and committing, or `git restore --staged . && git restore .` to return to a clean tree and retry the step from scratch. `git mv` itself is idempotent — re-running after the rename has landed is a no-op.

- **Step 2 — content rewrite.** Completion proof: `wc -l .claude/output-styles/house-style.md` shows ~400-500 lines, `grep -c '^## ' .claude/output-styles/house-style.md` shows 10 (9 top-level sections + Self-check), and `grep -E '^### ' .claude/output-styles/house-style.md` enumerates the 12 humanizer-gap patterns. Recovery if interrupted before commit: the file is single-file scope so `git restore .claude/output-styles/house-style.md` discards the partial rewrite and step 2 restarts from the post-step-1 frontmatter-only state. Continuing the partial rewrite is also valid — no atomicity requirement within the file.

- **Step 3 — FRR sweep + grep gate.** Completion proof: `grep -rnE "concise-doc|Concise Doc" .claude/ docs/ CLAUDE.md --exclude-dir=_workflow` returns zero matches. Recovery if interrupted before commit: re-run the gate to see which references remain; the gate output is the exact list of remaining work. Each per-file edit is independent — `git restore <file>` discards that file's edits without affecting siblings. The gate is the completion proof, so re-running it any number of times has no cost; it returns the same result until the next edit lands.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't belong to one specific step. Per-step episode content lives in `## Episodes` above. Often empty. -->

## Base commit

cca6f9c3cd8b1ca13f1923c4d5c328e2da9c7b6e

## Interfaces and Dependencies

**In-scope files:**
- `.claude/output-styles/concise-doc.md` → `.claude/output-styles/house-style.md` (rename + rewrite)
- `CLAUDE.md` (§ Writing Style block — lines 93, 102)
- `.claude/skills/code-review/SKILL.md` (line 313)
- `.claude/agents/review-workflow-consistency.md` (line 72)
- `.claude/agents/review-workflow-writing-style.md` (lines 3, 7, 9, 11, 13, 26, 62, 102, 137)

**Out-of-scope files (deferred to YTDB-837):**
- `.claude/workflow/conventions.md`
- `.claude/workflow/prompts/*.md` other than `design-review.md` (Track 3 handles that one)
- Implementer / orchestrator files under `.claude/workflow/`
- `.claude/agents/review-*.md` other than `review-workflow-consistency.md` and `review-workflow-writing-style.md`
- Any new pointer expansion — only *existing* references are touched here.

**Inter-track dependencies:**
- This track is a prerequisite for Tracks 2, 3, and 4. They all cross-reference `house-style.md § <Section>` by name; the file must exist and its sections must be stable before they land.
- This track has no upstream dependency on other tracks within YTDB-836.

**Library / function signatures:** none. This track is markdown editing.

**Cross-reference contract (downstream):**
- Tracks 2, 3, 4 will cite section names from `house-style.md`. The Phase 1 design fixes those names in `design.md § Internal layout of house-style.md`. Any section rename mid-track invalidates the downstream cross-references; if step 2 needs to deviate from the design's section list, it must propagate the new name to `design.md` first via an `edit-design` mutation.
