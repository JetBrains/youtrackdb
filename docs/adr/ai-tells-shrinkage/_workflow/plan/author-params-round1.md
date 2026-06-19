# design-author params — Track 1, round 1

- target: tracks
- output_path: docs/adr/ai-tells-shrinkage/_workflow/plan/
- plan_dir: docs/adr/ai-tells-shrinkage/_workflow/plan/
- research_log_path: docs/adr/ai-tells-shrinkage/_workflow/research-log.md
- round: 1
- (no design_path — minimal tier, no design.md)

## What to write

Fill the prose into the existing skeleton at
`docs/adr/ai-tells-shrinkage/_workflow/plan/track-1.md`. **Edit that file in
place — do NOT rewrite line 1** (the `<!-- workflow-sha: ... -->` stamp is
owned by the orchestrator and must stay byte-identical on line 1). Fill only
the Phase-1 track-level sections; leave the Phase-A placeholders
(`## Concrete Steps`, `## Episodes`, `## Idempotence and Recovery`, the per-step
EARS/Gherkin and Move-2/Move-3 reserved blocks) as the skeleton leaves them.

Ground every claim in the research log (read it in full) and the live files it
names. This session has no mcp-steroid; use Read/Grep and carry the
reference-accuracy caveat where a claim rests on grep.

## Settled shape (the orchestrator owns this; you write the prose)

This is a **single minimal-tier track** carrying the whole change: shrink
`.claude/output-styles/house-style.md` and its consumers to comprehension-serving
rules, removing rules whose only effect is to hide LLM authorship.

**`## Purpose / Big Picture`** — one-line BLUF (the user-visible gain: the
writing rules keep only what helps a human reader; the concealment-only rules
and their enforcement come out), then the intro paragraph.

**`## Decision Log`** — inline the full four-bullet Decision Records (Alternatives
considered / Rationale / Risks-Caveats / Implemented-in: this track) for the
load-bearing decisions in the research log's Decision Log. The set to carry,
each seeded from the matching research-log decision:

- **DR1 — the decision test** (keep a rule iff it changes prose a human also
  finds unclear/imprecise/hard-to-skim; remove iff it fires on a human's clear
  prose only for looking AI-ish).
- **DR2 — the REMOVE set**: banned vocabulary Tier 1-4 (the whole `## Banned
  vocabulary` section, incl. era-specific Tier 4); `### Em-dash discipline`;
  knowledge-cutoff disclaimers; the sycophantic-openers bullet (doc surface);
  the `### Signposting` rule (doc surface); `### Copula avoidance`. And the KEEP
  set — everything else, including the concision/specificity sentence rules.
- **DR3 — chat-only retention**: sycophantic openers and signposting are removed
  from the document style but retained as an inline chat-only guard in
  `house-conversation.md` (filler-cutting for terminal replies, not
  concealment).
- **DR4 — §1.7(k) prose-rule opt-out**: edit the rules live (self-application),
  not staged; the basis is the no-invocation fact (the `create-plan` loop this
  branch runs never invokes `design-mechanical-checks.py`, and this minimal
  branch authors no `design.md` so `edit-design` Step 3 never fires). Carries a
  mandatory `/migrate-workflow` stamp-advance at branch end.
- **DR5 — curly quotes kept** (tooling/grep-and-fence hygiene, not concealment);
  knowledge-cutoff stays removed.
- **DR6 — banned-vocab fold**: the Tier-2 precision intent (`robust → "tolerant
  of X"`, `comprehensive → "covers X, Y, Z"`) MOVES into `§ Plain language`'s
  "prefer the precise word" move (not copied); the `§ Plain language`
  "Reconciliation with § Banned vocabulary" subsection at house-style.md:92 is
  removed.

**`## Context and Orientation`** — the live state: `house-style.md` is the
declarative source (492 lines); `conventions.md §1.5` defines the Tier-B
code-comment AI-tell subset (the six section names at conventions.md:621) and
carries a rename-safety grep at :626; the "six AI-tell subset section slugs"
enumeration is replicated verbatim across ~40 files (~20 `.claude/agents/*.md`
review agents + ~20 `.claude/workflow/**` prompts/skills); the deterministic
checker `design-mechanical-checks.py` (`check_dsc_ai_tell`, 11 patterns) plus
`test_dsc_ai_tell.py` + `dsc-ai-tell-fixture.md`; the always-loaded
`ai-tells/SKILL.md:3` description; the `house-style-write-reminder.sh` hook;
`house-conversation.md`. Name the concrete deliverable: the rule set and every
consumer reference it in lockstep, with no phantom cross-references left.

**`## Plan of Work`** — the prose sequence: (1) remove the sections/bullets from
`house-style.md` and trim its Self-check list (items naming banned vocab, em
dashes, copula); reword the Plain-language reconciliation per DR6. (2) Update
`conventions.md §1.5` Tier-B table to the four surviving sections (`§
Orientation`, `§ Plain language`, `§ Banned sentence patterns`, `§ Banned
analysis patterns`) and its rename-safety grep. (3) Sweep the ~40 replicated
bootstrap-slug lines to drop the two removed slugs. (4) Update the checker +
tests + fixture to drop the removed patterns. (5) Update `house-conversation.md`
(drop the two removed slugs from its list; add the inline chat-only
sycophantic/signposting guard). (6) Update `ai-tells/SKILL.md` body + the line-3
description, `readability-auditor.md`, `prompts/design-review.md`,
`design-document-rules.md`, `design-author.md`, `readability-feedback/SKILL.md`,
the hook, and root `CLAUDE.md`. (7) Run the rename-safety grep as the
completeness check. Note the ordering invariant: the section removal and every
enumeration/consumer update must land coherently (no intermediate phantom-ref
state).

**`## Interfaces and Dependencies`** — in-scope: the files above. Out-of-scope:
the `create-plan` Step-4b checker wiring (that is YTDB-1148). No inter-track
dependencies (single track). Note the §1.7(k) opt-out edits live.

**`## Invariants & Constraints`** — write these as testable lines:
- After the change, no file under `.claude/` or root `CLAUDE.md` references a
  removed section or rule except as confirm-benign — verified by the §1.5
  rename-safety grep returning only benign hits + the Phase-C consistency
  reviewer.
- `design-mechanical-checks.py` no longer fires the removed patterns; the docstring
  pattern count drops accordingly — verified by `test_dsc_ai_tell.py` + the
  fixture.
- The §1.5 Tier-B subset and the ~40 replicated bootstrap-slug lines enumerate
  exactly the four surviving sections — verified by grep.
- `house-conversation.md` retains a chat-only sycophantic/signposting guard —
  verified by reading.
- `ai-tells/SKILL.md:3` description names no removed tell and keeps the kept
  ones (negative parallelism, Title Case) — verified by grep.
- Constraint (process, non-testable, record in Decision Log not here): §1.7(k)
  opt-out — edit live; run `/migrate-workflow` after the branch-final commit
  touching `.claude/workflow`/`.claude/skills`/`.claude/agents`.

**`## Validation and Acceptance`** — track-level acceptance (from the rewritten
YTDB-1144): house-style.md no longer states the removed rules and its Self-check
drops their entries; the checker no longer fires the removed patterns and its
tests/fixtures match; every consumer references only the kept rules; a document
or track authored with the kept rules draws no finding for a removed pattern (a
balanced appositive aside, or a Tier-2 word used precisely, no longer flags).

**Sizing justification (required — the track exceeds the soft footprint
ceiling):** ~40+ in-scope files, over the ~20-25 split ceiling. It stays one
track because it is a single atomic change — removing the sections and updating
every reference must land coherently; splitting would ship the phantom-reference
intermediate state the change exists to prevent. Most of the ~40 files are the
identical one-line bootstrap-slug edit, so review complexity is low. State this
justification in `## Interfaces and Dependencies` (or `## Context and
Orientation`).

## House style

Write to the live `house-style.md` rules (the removed rules are still in force
until this branch lands). Cold-readable for a mid-level reader who has only the
finished track file. The track file is the durable human-facing carrier (minimal
tier — no design.md buffers the reader), so favor orientation: lead with the
claim, gloss each project-specific entity at first use, linearize causal chains.
