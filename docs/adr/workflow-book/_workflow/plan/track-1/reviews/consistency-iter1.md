<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 14, matches: 14}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

No findings. Every current-state claim verified MATCHES; all `[ ]`-track
target-state claims (the `workflow-book-builder/` and `docs/workflow-book/`
layouts this track stamps out) are pre-screened as target-state and not
treated as discrepancies. This is a `minimal`-tier plan with no `design.md`,
so the DESIGN ↔ CODE, DESIGN ↔ PLAN, and the design half of GAPS axes were
skipped, and PLAN ↔ CODE ran only its track-vs-code bullet plus the
tier-line-presence check.

## Evidence base

Tooling note: this plan carries no Java symbols — every reference is a
filesystem path, a file/directory count, sibling-repository content, a git
SHA, or a CLI-tool presence. Verification used `Read`, `Glob`, and `Bash`
(`ls`, `find`, `du`, `grep`, `git rev-parse`, `command -v`). These are exact
checks, so no reference-accuracy caveat applies (mcp-steroid PSI does not
apply to a non-Java-symbol review).

### Plan ↔ Code (tier-line-presence check — runs in every tier)

#### Cert PC-TIER: D18 tier line present in `implementation-plan.md`
- **Document claim**: `implementation-plan.md` carries the D18 change-tier
  line; the plan declares `minimal` tier.
- **Search performed**: `Read` of
  `docs/adr/workflow-book/_workflow/implementation-plan.md`, line 5.
- **Code location**: `implementation-plan.md:5`.
- **Actual signature/role**: `**Change tier:** minimal — matched categories: none`.
- **Verdict**: MATCHES
- **Detail**: The required tier line is present and reads `minimal`. The
  design-presence test (no `design.md` on disk) is consistent with the
  declared tier, so the `minimal` axis set applies: design-half axes
  skipped, PLAN ↔ CODE limited to the track-vs-code bullet.

### Plan ↔ Code (track-vs-code bullet — current-state claims only)

#### Cert PC-1: Sibling model book exists at the claimed path with a BOOK_BRIEF.md
- **Document claim**: `track-1.md` `## Context and Orientation`: "The model
  the machinery imitates is the YouTrackDB internals book, which lives at
  `../docs-ytdb-internals-book/docs/ytdb-internals-book/` (a sibling
  repository) and is described in its `BOOK_BRIEF.md`."
- **Search performed**: `Bash` —
  `ls -ld /home/andrii0lomakin/Projects/ytdb/docs-ytdb-internals-book/docs/ytdb-internals-book`
  and `ls -l .../BOOK_BRIEF.md`.
- **Code location**: directory present; `BOOK_BRIEF.md` present (5587 bytes).
- **Actual signature/role**: The sibling book directory exists and holds
  `BOOK_BRIEF.md`, `MAINTENANCE_PROMPT.md`, `README.md`, `TOC.md`,
  `chapters/`, `reviews/`, `beta-feedback/`, `maintenance/`, `team/`.
- **Verdict**: MATCHES

#### Cert PC-2: Workflow-doc count — "31 workflow docs"
- **Document claim**: `track-1.md` `## Context and Orientation`: "31
  workflow docs (about 1.3 MB)".
- **Search performed**: `Bash` —
  `ls -1 .claude/workflow/*.md | wc -l`.
- **Code location**: 31 top-level `*.md` files directly under
  `.claude/workflow/`.
- **Actual signature/role**: count = 31.
- **Verdict**: MATCHES

#### Cert PC-3: Workflow-corpus size — "about 1.3 MB"
- **Document claim**: `track-1.md` `## Context and Orientation`: the 31
  workflow docs total "about 1.3 MB".
- **Search performed**: `Bash` — `du -ch .claude/workflow/*.md | tail -1`
  (top-level docs) and `du -sh .claude/workflow/` (whole tree).
- **Code location**: top-level 31 `*.md` = 1.1 MB; whole `.claude/workflow/`
  tree (incl. `prompts/`) = 1.3 MB.
- **Actual signature/role**: The "1.3 MB" figure matches the whole workflow
  tree exactly; the 31 top-level docs alone are 1.1 MB.
- **Verdict**: MATCHES
- **Detail**: The 1.1-vs-1.3 gap sits inside the claim's own "about"
  qualifier and matches the recursive tree exactly, so it is within the
  rounding tolerance the spawn instruction marked acceptable. Not a finding.

#### Cert PC-4: Prompt count — "11 prompts"
- **Document claim**: `track-1.md` `## Context and Orientation`: "plus 11
  prompts".
- **Search performed**: `Bash` —
  `ls -1 .claude/workflow/prompts/*.md | wc -l`.
- **Code location**: 11 `*.md` files under `.claude/workflow/prompts/`.
- **Actual signature/role**: count = 11.
- **Verdict**: MATCHES

#### Cert PC-5: Agent count — "20 agents"
- **Document claim**: `track-1.md` `## Context and Orientation`: "20 agents".
- **Search performed**: `Bash` — `ls -1 .claude/agents/*.md | wc -l`.
- **Code location**: 20 `*.md` files under `.claude/agents/`.
- **Actual signature/role**: count = 20.
- **Verdict**: MATCHES

#### Cert PC-6: Skill count — "16 skills"
- **Document claim**: `track-1.md` `## Context and Orientation`: "16 skills".
- **Search performed**: `Bash` — `ls -1d .claude/skills/*/ | wc -l`.
- **Code location**: 16 skill directories under `.claude/skills/`.
- **Actual signature/role**: count = 16.
- **Verdict**: MATCHES

#### Cert PC-7: Baseline workflow-SHA resolves to a real commit
- **Document claim**: `track-1.md` `## Context and Orientation`: "The
  current baseline is `3e9c22298d`." Same SHA is the `<!-- workflow-sha: … -->`
  stamp on both plan and track files.
- **Search performed**: `Bash` — `git rev-parse 3e9c22298d`.
- **Code location**: resolves to `3e9c22298dfe68d2980646704850c781f8af88d5`.
- **Actual signature/role**: A real commit in the repo; matches the
  full SHA in both file headers.
- **Verdict**: MATCHES

#### Cert PC-8: Render tooling presence (D5/D6 current-state claim)
- **Document claim**: D5+D6 and `## Interfaces and Dependencies`: "neither
  render tool ships in the current environment — `d2` is not installed,
  `mmdc` is not installed, only mermaid's `node`/`npx` prerequisite is
  present".
- **Search performed**: `Bash` — `command -v d2`, `command -v mmdc`,
  `command -v node`, `command -v npx`.
- **Code location**: `d2` NOT FOUND on PATH; `mmdc` NOT FOUND on PATH;
  `node` at `/usr/bin/node`; `npx` at `/usr/bin/npx`.
- **Actual signature/role**: Exactly as the plan states — both render tools
  absent, both Node prerequisites present.
- **Verdict**: MATCHES

#### Cert PC-9: Model book "ships inline mermaid in all 17 chapters and zero rendered assets" (D5 rationale)
- **Document claim**: D5+D6: the internals book "ships inline mermaid in all
  17 chapters and zero rendered assets".
- **Search performed**: `Bash` — `find .../chapters -name '*.md' | wc -l`
  (chapter count); `find <book> -type f \( -iname '*.svg' -o -iname '*.png'
  -o -iname '*.d2' \) | wc -l` (rendered assets); `grep -rl 'mermaid'
  .../chapters` (mermaid coverage).
- **Code location**: `chapters/` holds 17 `.md` files
  (`01-…` through `17-reference.md`); rendered-asset count = 0; all 17
  chapters contain a `mermaid` fence.
- **Actual signature/role**: 17 chapters, every one carries mermaid, no SVG/
  PNG/.d2 asset anywhere in the book tree.
- **Verdict**: MATCHES

#### Cert PC-10: Model uses "two separate hand-driven entry points" — drift-refresh prompt + author-wave cycle (D8 rationale)
- **Document claim**: D8+D10: the model keeps "two separate hand-driven
  entry points — a lightweight drift-refresh prompt and a heavyweight
  author-wave cycle".
- **Search performed**: `Bash` — `grep -nE '^#{1,4} '` over
  `MAINTENANCE_PROMPT.md` and `README.md`; `grep -rniE 'cycle 1|cycle
  2|author.?wave'` across the book tree.
- **Code location**: `MAINTENANCE_PROMPT.md` (top level) is the lightweight
  drift-refresh prompt (`# Maintenance Prompt — Refreshing the Book Against
  a Newer Source Tree`, Phase 0 drift window → Phase 5 baseline bump). The
  heavyweight author-wave cycle is `README.md` `## Production record` →
  `### Cycle 1 — initial draft` (TOC+briefs → author wave → technical review
  → copy edit → beta read → revision) and `### Cycle 2`. `MAINTENANCE_PROMPT.md`
  rule 5 explicitly defers new content to "start a new cycle like cycle 1 /
  cycle 2".
- **Actual signature/role**: Two distinct hand-driven paths exist — the
  standing reusable refresh prompt and the cycle-1/cycle-2 production
  process. The claim's framing matches.
- **Verdict**: MATCHES
- **Detail**: The author-wave cycle is recorded as a production *process* in
  README, not as a separate standalone copy-paste prompt file (no
  `PRODUCTION_PROMPT.md` exists). D8 calls it "a heavyweight author-wave
  cycle" (not a prompt file), so the wording is accurate. The D11 alternative
  "Keep separate `PRODUCTION_PROMPT.md` and `MAINTENANCE_PROMPT.md`" is a
  considered design alternative (target-state reasoning), not a current-state
  claim about the model, so it is not subject to factual verification here.

#### Cert PC-11: Model's MAINTENANCE_PROMPT.md is "a single file whose first section is the copy-paste block" (D12 rationale)
- **Document claim**: D12: "the internals book's `MAINTENANCE_PROMPT.md` is a
  single file whose first section is the copy-paste block and whose
  remainder is operator context".
- **Search performed**: `Bash` — `head -60 MAINTENANCE_PROMPT.md` and
  `grep -nE '^#{1,4} ' MAINTENANCE_PROMPT.md`.
- **Code location**: `MAINTENANCE_PROMPT.md` heading order: `# Maintenance
  Prompt …` (title + a short framing intro at lines 1-8), then `## Prompt
  (copy-paste this block into a new Claude Code session)` (the first `##`
  section), then `## Context for the human operator`.
- **Actual signature/role**: One file; its first content section is the
  copy-paste block; the remainder is operator context.
- **Verdict**: MATCHES

#### Cert PC-12: Four role names match the model book (Validation acceptance + D8)
- **Document claim**: `track-1.md` `## Validation and Acceptance` and D8: the
  pipeline drives the four roles author / technical-reviewer / copy-editor /
  beta-reader, "the same set initial production uses", with no fifth role.
- **Search performed**: `Bash` — `grep -niE 'author|technical|copy.?edit|
  beta.?read'` over the model book's `BOOK_BRIEF.md`.
- **Code location**: `BOOK_BRIEF.md` lists the model's roles: Authors,
  Technical reviewers, Copy editors, Beta readers, plus a Revision pass.
- **Actual signature/role**: The four named roles match the model exactly;
  the model's separate "Revision pass" is a phase, not a fifth review role,
  consistent with the plan's four-role claim.
- **Verdict**: MATCHES

### Gaps (orphan codebase-construct bullet — runs in every tier)

#### Cert GAP-1: No orphaned current-state construct the plan should reference but does not
- **Document claim**: n/a — this is the orphan-construct sweep, checking
  whether any existing construct the `minimal` plan ought to name is absent.
- **Search performed**: `Bash`/`Glob` — confirmed the two target trees
  (`workflow-book-builder/`, `docs/workflow-book/`) do not exist yet (correct
  for a `[ ]` track that creates them); confirmed the sibling model book and
  the `.claude/workflow/**` corpus the plan already names are the only
  current-state constructs in scope.
- **Code location**: target trees absent (`ls` → "No such file or
  directory"), consistent with target-state.
- **Actual signature/role**: The plan already references every current-state
  construct it depends on: the sibling model book, its `BOOK_BRIEF.md` /
  `MAINTENANCE_PROMPT.md` / `TOC.md` model, the `.claude/workflow/**` corpus,
  the baseline workflow-SHA, and the `d2`/`mmdc`/`node`/`npx` tooling state.
  No existing construct is silently relied on without being named.
- **Verdict**: MATCHES
