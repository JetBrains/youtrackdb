<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: T1, sev: should-fix, loc: "track-1.md:103 (Context and Orientation)", anchor: "### T1 ", cert: P2, basis: "corpus size 'about 1.3 MB' overstates the 31 docs' real 0.99 MB by ~30%; counts exact, size rounded high"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 10}
cert_index:
  - {id: P2, verdict: PARTIAL, anchor: "#### P2 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: P2 (Premise: corpus counts and size)
**Location**: `track-1.md:103` (`## Context and Orientation`); the same figure rides in D8+D10 reasoning indirectly via "that corpus is large".
**Issue**: The four item counts are exact — 31 top-level docs under `.claude/workflow/` (prompts excluded), 11 prompts under `.claude/workflow/prompts/`, 20 agents under `.claude/agents/`, 16 skills under `.claude/skills/*/SKILL.md` — all verified against the filesystem. The size figure is not. The track says "31 workflow docs (about 1.3 MB)". The 31 docs total 1,037,906 bytes = 0.99 MB. The full `.claude/workflow` tree including the 11 prompts is 1,267,915 bytes = 1.21 MB. So "about 1.3 MB" overstates the figure it is attached to (the 31 docs) by ~30%, and even the whole tree is 1.21 MB, not 1.3. This is orientation prose, not a binding contract, and "about" softens it, so it cannot break decomposition — but a teaching-machinery brief that a later run reads to set the source corpus's size should not carry a number a reader can check and find wrong.
**Proposed fix**: Change "about 1.3 MB" to "about 1.0 MB" (the 31 docs alone, which is what the sentence counts) or "about 1.2 MB" if the intent is the whole `.claude/workflow` tree. Stating which denominator the figure covers removes the ambiguity. No step or Decision Record changes; this is a one-number edit in the track's `## Context and Orientation`.

## Evidence base

#### P1: Sibling model book exists with the described structure
- **Track claim**: "The model the machinery imitates is the YouTrackDB internals book, which lives at `../docs-ytdb-internals-book/docs/ytdb-internals-book/`" with a `BOOK_BRIEF.md`, a maintenance prompt, a `TOC.md` with a cross-reference matrix, inline mermaid in chapters, and zero rendered assets.
- **Search performed**: `ls -la ../docs-ytdb-internals-book/docs/ytdb-internals-book/`; `grep -in 'cross-reference matrix' TOC.md`; `grep -n '^#' BOOK_BRIEF.md`; `find . -name '*.svg' -o -name '*.png'`; `grep -rl '```mermaid' chapters/`.
- **Code location**: `../docs-ytdb-internals-book/docs/ytdb-internals-book/` — `BOOK_BRIEF.md` (5587 B), `MAINTENANCE_PROMPT.md` (8459 B), `README.md`, `TOC.md`, `chapters/` (17 files), `reviews/`, `beta-feedback/`. `TOC.md:138` has `## Cross-reference matrix`. `BOOK_BRIEF.md` carries Audience / Voice / Conventions / Source mapping / Production pipeline headings.
- **Actual behavior**: Directory exists with the exact shape described. 17 chapters (`01-…` through `17-reference.md`), all 17 contain inline ` ```mermaid` blocks, and `find` for `*.svg`/`*.png` returns zero — confirms "inline mermaid in all chapters and zero rendered assets" (the D5+D6 model-departure premise).
- **Verdict**: CONFIRMED

#### P2: Source corpus counts and size
- **Track claim**: "31 workflow docs (about 1.3 MB) plus 11 prompts, 20 agents, and 16 skills" under `.claude/`.
- **Search performed**: `find .claude/workflow -name '*.md' -not -path '*/prompts/*' | wc -l` and `-printf '%s'` sum; `ls .claude/workflow/prompts/*.md | wc -l`; `ls .claude/agents/*.md | wc -l`; `ls .claude/skills/*/SKILL.md | wc -l`.
- **Code location**: `.claude/workflow/` (31 top-level `.md`, only subdir is `prompts/`), `.claude/workflow/prompts/` (11), `.claude/agents/` (20), `.claude/skills/*/SKILL.md` (16).
- **Actual behavior**: 31 / 11 / 20 / 16 all match exactly. Size: the 31 docs = 1,037,906 B = 0.99 MB; the whole `.claude/workflow` tree (with prompts) = 1,267,915 B = 1.21 MB. Neither equals "about 1.3 MB".
- **Verdict**: PARTIAL
- **Detail**: All four counts exact; the size figure overstates. See finding T1.

#### P3: Render tooling reality (d2 absent, mmdc absent, node/npx present)
- **Track claim**: D5+D6 and `## Interfaces and Dependencies` — "`d2` not installed, `mmdc` not installed, only `node`/`npx` present", which is why `render-diagrams.sh` must guard on the `d2` binary and print the install command on a miss.
- **Search performed**: `command -v d2`, `command -v mmdc`, `command -v node`, `command -v npx`.
- **Code location**: shell PATH.
- **Actual behavior**: `d2` not found; `mmdc` not found; `node` present at `/usr/bin/node` (v22.22.3); `npx` present at `/usr/bin/npx`. Matches the track exactly.
- **Verdict**: CONFIRMED

#### P4: Pinned baseline workflow-SHA is a real commit
- **Track claim**: "The current baseline is `3e9c22298d`" (`## Context and Orientation`); also the workflow-sha stamp at the top of the track file and plan file.
- **Search performed**: `git cat-file -t 3e9c22298d`; `git log --oneline -1 3e9c22298d`.
- **Code location**: git object DB.
- **Actual behavior**: Resolves to a real commit object: `3e9c22298d [YTDB-1124] Keep the research log opaque to the user during Phase 0 (#1144)`.
- **Verdict**: CONFIRMED

#### P5: D12 model claim — model's MAINTENANCE_PROMPT is one file whose first section is the copy-paste block
- **Track claim**: D12 — "the internals book's `MAINTENANCE_PROMPT.md` is a single file whose first section is the copy-paste block and whose remainder is operator context."
- **Search performed**: `head -30 ../docs-ytdb-internals-book/docs/ytdb-internals-book/MAINTENANCE_PROMPT.md`.
- **Code location**: `MAINTENANCE_PROMPT.md:1-30`.
- **Actual behavior**: The file opens with a one-paragraph framing ("You can paste the 'Prompt' section below into a fresh Claude Code session. The rest of this file is context — the prompt is self-contained…"), then `## Prompt (copy-paste this block into a new Claude Code session)` as its first real section, followed by the operator-context remainder. Exactly the structure D12 claims to mirror.
- **Verdict**: CONFIRMED

#### P6: Greenfield — target directories do not already exist
- **Track claim**: The track creates `workflow-book-builder/` and `docs/workflow-book/` from scratch (D1, D2, D3; "stamps out empty").
- **Search performed**: `ls -la workflow-book-builder/`; `ls -la docs/workflow-book/`.
- **Code location**: repository root.
- **Actual behavior**: Both paths return "No such file or directory". Greenfield, as the plan assumes; no backward-compat / migration concern (see P11).
- **Verdict**: CONFIRMED

#### P7: D11 file-list internal consistency and role-set self-consistency
- **Track claim**: D11 + Validation/Acceptance — the machinery holds `BOOK_BRIEF.md`, `PIPELINE.md`, `DIAGRAMS.md`, four `prompts/*.md` (author, technical-reviewer, copy-editor, beta-reader), `scripts/render-diagrams.sh`, empty `reviews/` and `beta-feedback/`; the book target holds `README.md`, `TOC.md`, empty `chapters/` and `assets/diagrams/`. The role definitions in `BOOK_BRIEF.md`, the roles `PIPELINE.md` orchestrates, and the four `prompts/*.md` name the same four roles with no fifth and none missing.
- **Search performed**: Cross-read of the track's D8+D10 record (lines 64-74, names the four waves), D11 (lines 82-86), the `## Context and Orientation` tree diagram (lines 107-130), Plan of Work (lines 132-137), and Validation/Acceptance (lines 150-152); `grep -oiE 'author|technical-reviewer|copy-editor|beta-reader' track-1.md | sort | uniq -c`.
- **Code location**: `track-1.md` D8+D10, D11, the layout tree, Plan of Work, Validation/Acceptance.
- **Actual behavior**: The four-role set (author, technical-reviewer, copy-editor, beta-reader) is named identically in the D8+D10 wave list, the D11 file list, the layout tree, and all three acceptance criteria. No fifth role appears anywhere; none is missing in any of those four enumerations. The `~13` count holds: 9 machinery files/dirs (BOOK_BRIEF, PIPELINE, DIAGRAMS, 4 prompts, render-diagrams.sh, 2 empty dirs) + 4 book-target (README, TOC, 2 empty dirs) = 13 with `.gitkeep` placeholders. Plausible single-`minimal`-track footprint; the planning track-size bounds (merge ≤~12, split >~20-25 in-scope files) put 13 comfortably mid-range.
- **Verdict**: CONFIRMED

#### P8: D10 empty-vs-non-empty-baseline branch is specified at plan altitude, not under-specified
- **Track claim**: D8+D10 risk (line 73) — "'One code path' means one document and one role set, not identical control flow: the pipeline doc must branch explicitly on baseline-empty versus non-empty"; Plan of Work (line 137) restates the from-scratch vs incremental split; acceptance criterion (line 151) makes "`PIPELINE.md` branches explicitly on an empty versus non-empty baseline" a checkable output.
- **Search performed**: Read of D8+D10 (the 5-step run description, lines 66-72), the D8+D10 risk note (line 73), Plan of Work (line 137), Validation/Acceptance (line 151).
- **Code location**: `track-1.md:64-74`, `track-1.md:137`, `track-1.md:151`.
- **Actual behavior**: The branch is specified concretely enough for decomposition: from-scratch path = empty baseline → drift window is everything → TOC from scratch → every chapter through full waves; incremental path = drift window over the source paths → clean/sweep/rewrite/new-or-restructure triage → a few touched chapters. The risk note names the under-specification hazard and routes the fix into `PIPELINE.md` as an acceptance check. A step that authors `PIPELINE.md` has a clear, testable target. Not under-specified for the planner; the actual control-flow text is content the authoring step writes, which is in-scope work, not a planning gap.
- **Verdict**: CONFIRMED

#### P9: D12 preserves the single-paste-entry-point property after the D10 two-into-one fold
- **Track claim**: D12 — folding the model's two prompts into one `PIPELINE.md` (D10) lost the pasteable-entry-point property, and D12 restores it by opening `PIPELINE.md` with a self-contained "paste this into a fresh session" block followed by operator context; the four `prompts/*.md` are spawned by the orchestrating session, not pasted by hand, so there is one human entry point.
- **Search performed**: Read of D12 (lines 88-92) against the verified model structure in P5; cross-check that the layout (lines 110-117) keeps the four role prompts under `prompts/` (spawned, not pasted) and `PIPELINE.md` as the single operator-facing file.
- **Code location**: `track-1.md:88-92`, `track-1.md:110-117`, model `MAINTENANCE_PROMPT.md:1-12` (P5).
- **Actual behavior**: The design is coherent and matches the proven model: one operator-facing file whose lead section is the copy-paste block, role prompts spawned by the orchestrating session. The D12 risk note (line 91) correctly requires the start block to be self-contained (re-reads BOOK_BRIEF, DIAGRAMS, role prompts, TOC, source tree) and to carry the D10 empty-vs-non-empty branch — so the single paste covers both initial production and evolution. Feasible; no contradiction with D10.
- **Verdict**: CONFIRMED

#### P10: render-diagrams.sh contract is feasible and its non-execution here is consistent with D1
- **Track claim**: `## Interfaces and Dependencies` — the script's contract is `.d2` source in (sidecars under `docs/workflow-book/assets/diagrams/`), committed `fig-N.svg` out; it guards on the `d2` binary and prints the install command on a miss; it is authored here but not run here (D1).
- **Search performed**: Read of D5+D6 risk (lines 54-55), `## Interfaces and Dependencies` (line 178), the layout tree (lines 119, 128-129); tooling check in P3.
- **Code location**: `track-1.md:54-55`, `track-1.md:178`, `track-1.md:119`.
- **Actual behavior**: Internally consistent. D1 scopes the deliverable to the builder with no chapters authored, so the renderer first runs in a later cycle; the missing-`d2` case is therefore an operator-doc / guard requirement, not an execution blocker — which is exactly how the track frames it. The sidecar-source-in / committed-SVG-out contract is a plain shell-script spec, no codebase API dependency. The acceptance criterion (line 154) correctly says the script "is not expected to run successfully in the current environment".
- **Verdict**: CONFIRMED

#### P11: Backward-compatibility / migration — N/A (greenfield prose)
- **Track claim**: Implicit — the track adds two new directory trees and touches no existing data, format, or `.claude/**` file (D1, D2; `## Plan of Work` invariants, lines 135-137).
- **Search performed**: P6 (target dirs absent); read of the two Plan-of-Work invariants (line 135: "the branch touches no `.claude/**` file … if a step finds itself editing under `.claude/`, the design has drifted and the step stops"; book-target ships empty).
- **Code location**: `track-1.md:135-137`, `track-1.md:157` (acceptance: "No file under `.claude/**` is added or modified").
- **Actual behavior**: No existing file is modified — only new files under `workflow-book-builder/` and `docs/workflow-book/`. No data format, no serialized state, no public API. No migration is needed and none is claimed. The non-workflow-modifying invariant is self-enforcing (the in-scope file boundary excludes `.claude/**`), so §1.7 staging correctly does not apply. Checked explicitly per the prompt's BACKWARD COMPATIBILITY criterion: not applicable here.
- **Verdict**: CONFIRMED

#### P12: No production Java symbols named — NAMED-REFERENCES and PSI premises are empty by construction
- **Track claim**: The track is prose/markdown machinery; it names no production Java class anywhere in `## Purpose / Big Picture`, `## Context and Orientation`, `## Plan of Work`, `## Decision Log`, or `## Interfaces and Dependencies`.
- **Search performed**: Full read of the track file's five authoritative sections; the only code-like names are file paths (`PIPELINE.md`, `render-diagrams.sh`), directory names, the `d2`/`mmdc`/`node`/`npx` tool names, the `§1.7` convention anchor, and the baseline SHA — none is a Java FQN.
- **Code location**: `track-1.md` (whole file).
- **Actual behavior**: Zero Java symbols, so the NAMED-REFERENCES-IN-STEP-FILE `findClass` checks produce no entries and no mcp-steroid PSI audit is warranted. The `§1.7` anchor and file paths are prose references checked under the path/anchor lens (all resolve: P3 tooling, P1 model paths, P4 SHA). Recorded as a negative-result premise per the certificate rules.
- **Verdict**: CONFIRMED
