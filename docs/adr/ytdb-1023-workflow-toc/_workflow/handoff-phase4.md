# Phase 4 handoff — Final Artifacts (paused at context warning, pre-authoring)

**Why paused:** Context hit `warning` (35%) after reading all Phase 4 inputs
(conventions.md, design-document-rules.md, workflow.md §Final Artifacts,
edit-design/SKILL.md, design.md, all 5 track files) and verifying the on-disk
implemented state. `design-final.md` authoring is the next unit of work; the
workflow forbids starting it at warning. **Nothing is authored yet** — neither
`design-final.md` nor `adr.md` exists on disk, no Phase 4 commit has landed.

**State:** `## Final Artifacts` is `[>]` (Phase 4 in progress). All 5 tracks
`[x]`, `## Plan Review` `[x]`. Workflow-modifying plan → **three** Phase 4
commits. Startup checks already passed this session: branch divergence clean
(0/0), workflow drift none (skip #3), no other handoffs.

## Resume — what the next session does

Re-enter Phase 4 via `prompts/create-final-design.md` from Step 3 (artifact
production). This handoff digests Step 1 + Step 2, so re-read only `design.md`
(the structural template `design-final.md` mirrors) and any track episode you
want to quote precisely; skip re-deriving the reconciliation plan below.

Mutation-discipline invocation for `design-final.md`:
- `mutation_kind`: `phase4-creation`
- `design_path`: `docs/adr/ytdb-1023-workflow-toc/design-final.md`
- `design_mechanics_path`: `null` (original `design.md` has **no** mechanics companion)
- `target`: `design`
- Omit `plan_path` / `plan_dir` (cross-file ref check naturally skipped)
- `intended_edit`: full file content
- Phase 4 final artifacts are **not** stamped (no line-1 workflow-sha).

PSI is **N/A** for this plan — it is Python + Markdown + YAML, no Java symbols.
Verification is against file content directly (done below); note the caveat
inline in the verification tables.

## design.md structure to mirror (625 lines, 12 H2 sections, no Parts)

Overview → Core Concepts (8 concepts) → Files and surfaces out of scope →
Annotation idiom and TOC region → Role and phase enums → Cross-reference
convention → Bootstrap protocol for agent system prompts → Reindex script →
Telemetry script → CI gate semantics → Migration replay semantics → Phase 4
ADR template extension. (The `# Conventions`, `# <Plan name> — ADR`,
`## Token usage telemetry`, `## Reading workflow files (TOC protocol)` lines in
design.md are inside fenced examples, not real headings.)

`design-final.md` reflects **what was built**, so it must fold in the
deferred-drift basket below rather than copy design.md's frozen Phase-1 prose.

## Verified on-disk implemented state (ground truth for diagrams + DRs)

- `.claude/scripts/workflow-reindex.py` — 2734 lines, stdlib-only. Functions
  confirmed: `compute_fenced_lines`, `parse_in_scope_files`,
  `load_bootstrap_enums`, `discover_bootstrap_scope`,
  `discover_agent_citing_files` (D17), `union_annotation_sets` (##/### only,
  H4 excluded), `build_file_lookup` (staged-aware D14 + bare-basename
  collision D15 + `<skill-dir>/SKILL.md` key D16), `validate`,
  `compute_write_plan`, `apply_write_plan`. All 8 rules present (`rule_1`..`rule_8`).
- `.claude/scripts/measure-read-share.py` — 587 lines. Tests:
  `test_workflow_reindex.py` 5210 lines, `test_measure_read_share.py` 710 lines.
  Reindex suite was 124/124 at Track 5 (117/117 at end of Track 4 + Track 5's 7 new).
- `.githooks/pre-commit` — two named functions `run_java_checks` +
  `run_workflow_reindex_check`, both invoked unconditionally (Java gate fires
  only when Java staged). `set -euo pipefail`, `--diff-filter=ACMR`, regex
  matches live + staged paths. Shebang `#!/usr/bin/env bash`.
- `.github/workflows/workflow-toc-check.yml` — exists. `name: Workflow TOC
  check`; `pull_request` types include `ready_for_review`;
  `if: github.event.pull_request.draft == false` (draft-PR skip per Track 2
  Completion-gate Review fix); single step runs `workflow-reindex.py --check`.
- 6 `review-workflow-*` agents carry the canonical finding-prefix family
  `WC / WP / WI / WH / WB / WS` (consistency / prompt-design /
  instruction-completeness / hook-safety / context-budget / writing-style),
  matching `review-iteration.md` + `review-agent-selection.md`. (The Track-2
  initial `CN/HS/PD/IC` letters were renamed to the canonical family in
  `Review fix: fc2d829421`.)
- Bootstrap block (`## Reading workflow files (TOC protocol)`) is in all 38
  system prompts: 20 live `.claude/agents/*.md`, plus 7 staged SKILL + 11
  staged prompts under `_workflow/staged-workflow/`.
- Staged tree: 49 annotated files (31 workflow-root + 11 prompts + 7 skills)
  under `_workflow/staged-workflow/.claude/`, plus the bootstrap blocks.

## design-final.md deferred-drift reconciliation basket (author these as built)

The frozen `design.md` predates these; `design-final.md` carries the
as-built version:

- **WC1 (bootstrap body).** The on-disk bootstrap body (byte-identical across
  38 files) diverges from frozen design.md §"Bootstrap protocol for agent
  system prompts" in several ways — port the **as-built** body:
  (1) step-1 read window is **delimiter-bounded** (`<!--Document index
  start-->`/`end-->`), not "first ~30 lines" (D19);
  (2) step-2 match rule expands the reader's own `any` on **either** axis, and
  is **any-of/OR** for multi-hat readers (Phase-C M1);
  (3) the no-TOC trigger **exempts the bootstrap heading** (Phase-C M1/WI1) and
  has a no-TOC read-window complement (Step-5 M2);
  (4) a **zero-rows-match terminal** plus the §1.8(f) flowchart loop-back
  (`SKIP_SECTION → SECTION_MATCH`) and all-skipped `→ DONE` terminal edges
  (Phase-C M2/M3 = WI2/WI3);
  (5) the closing line notes backtick-wrapped refs carry **no** suffix;
  (6) the cross-file `§1.8(d)` anchor is **backtick-wrapped**.
- **WC2 (anchor-count prose).** design.md §"Bootstrap protocol → Block
  placement" + glossary "Bootstrap block" row + plan I5 say "between frontmatter
  and H1" (two-anchor); the as-built schema is **three** anchors (under-H1 /
  after-frontmatter / top-of-file, §1.8(d)). Reconcile to three.
- **S1 / §"Cross-reference convention" Read-decision-flow diagram.** The
  Mermaid `READ_TOC` node still shows "first ~20 lines" and `MATCH`/
  `SECTION_MATCH` show the no-`any`-wildcard rule — update to delimiter-bounded
  + `any`-expansion (the auto-fixed S1 from `## Plan Review`).
- **§"Discovery mechanism".** Reflect the 6 shipped globs (3 live + 3 staged
  subtree per D12), not the 3 in design.md; reflect D14 staged-first lookup,
  D15 bare-basename collision → workflow-root, D16 `<skill-dir>/SKILL.md` key,
  D17 live-agent rules-6/7-only scope.
- **§"Files and surfaces out of scope" exclusion 1 + §"Cross-reference
  convention".** D13 **widened** the cross-file `:roles:phases` suffix from
  agent-files-only to **every** in-scope workflow doc + prompt; non-annotatable
  targets are backtick-wrapped. design.md still scopes it to agent files —
  widen it.
- **§"Migration replay semantics".** D18: the two-branch replay is a
  **post-merge** acceptance procedure, not in-track. design.md §"Verification on
  two branches" already frames "post-plan develop"; align the framing.
- **§"Telemetry script" / methodology.** Fold the Track-3 known-debt
  (recorded in track-3.md Outcomes): A3 (counts-as-metadata motivation),
  A4 (lifetime-window non-comparability across ADRs), A5 (`session-stats.py`
  duplicate-then-diverge), A9 ("every future ADR carries it" overcommit vs
  ~28% historical), A13 (re-cast the privacy framing → non-comparability is the
  real cost of absolute counts), A15 (`char/4` vs API `usage.input_tokens`
  ~10-20% divergence — methodology note). Track 3's headline fix: top-files
  `repo_root` resolution for symlinked worktrees (commit d00cd91f25).
- **§"Telemetry → Output format".** 4 skip-notice templates shipped
  (main-checkout / no-transcripts / no-checkout / mid-walk-parse-error), not 2.
- **§"Phase 4 ADR template extension".** Placement is **locked** "after
  `## Key Discoveries`" (not "configurable per prompt").

## adr.md notes

- Decision Records: restate D1, D2, D4–D19 with **actual outcomes** (D3 was
  dropped in Phase 1 — see design-mutations.md Mutation 2; do not resurrect).
  Strip ephemeral identifiers (no "Track N"/"Step M"/finding-IDs) — use prose,
  file/class refs, or commit SHAs. Re-scan with the §2.3 pre-commit gate regex
  before committing.
- **rule_1 residue note:** the missing-line-1-stamp residue is **49** over the
  full staged-workflow tree and **18** over the mechanically-derived in-scope
  set (7 staged SKILL + 11 staged prompts; the 20 live agents are rule_1-exempt
  by the D17 gate). An unscoped full-tree `--check` clears the 49 at Phase 4
  promotion; the live SKILL/prompt rule_7 findings are the other half of that
  transition.
- **D18 post-merge acceptance procedure** (document in adr.md, not run
  in-branch): after squash-merge, the user runs `/migrate-workflow` in a fresh
  session on **two** candidate branches rebased onto post-plan develop,
  confirming clean completion (stamp-rewrite-only normalization or silent skip).
  Candidate pool: `ytdb-612-rollback-log`, `read-cache-concurrency-bug`,
  `ytdb-614-property-map`, `failed-wal-recovery`. The two picks + outcome
  resolve post-merge and land in the Track 5 completion episode (re-edit) + adr.md.
- adr.md `## Token usage telemetry` section is produced by running
  `python3 .claude/scripts/measure-read-share.py` from this worktree (Step 3
  Artifact-2 of create-final-design.md) and pasting stdout after
  `## Key Discoveries`. Telemetry baseline cited in episodes: ~72.6% Read share
  measured from this worktree (Track 5 WB1 note).

## Phase 4 three-commit shape (workflow-modifying)

1. **Promote** (Step 4 of create-final-design.md): `STAGED_DIR` guard
   `[ -d .../staged-workflow/.claude ]` true → pre-promotion divergence check
   (`git fetch origin develop`; diff `$(git merge-base origin/develop HEAD)..origin/develop`
   on `.claude/workflow .claude/skills`; **halt** if non-empty per §1.7(f)) →
   `cp -r staged .claude/. .claude/` → `git add .claude/workflow .claude/skills`
   → commit message **exactly** `Promote workflow changes from
   docs/adr/ytdb-1023-workflow-toc/_workflow/staged-workflow` (the implementer
   live-workflow-path gate keys off this prefix verbatim) → push.
2. **Final artifacts** (Step 5): stage **only** top-level `design-final.md` +
   `adr.md` (nothing under `_workflow/`); commit `Add final design and ADR`; push.
3. **Cleanup** (Step 6): `git rm -r docs/adr/ytdb-1023-workflow-toc/_workflow/`;
   commit `Remove workflow scaffolding`; push.

Then Step 7 self-improvement reflection (YouTrack YTDB / dev-workflow, no
commit), Step 8 inform user (Claude does **not** run `gh pr ready`).

## Phase B base commit (for reference)

Track 5 Phase B base `b722c11e6e`. HEAD at handoff: `74463d5e79` (0 unpushed
before this handoff commit).
