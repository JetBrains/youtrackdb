<!-- workflow-sha: 367f5f83f1bce0e98eaeb0679973f9728db64b61 -->
# Track 3: Telemetry script + Phase 4 prompt integration

## Purpose / Big Picture

After this track lands, every future Phase 4 ADR carries a percentages-only "Token usage telemetry" section populated by `measure-read-share.py`, computed against the worktree's transcript-folder lifetime.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Build `.claude/scripts/measure-read-share.py` — worktree-scoped, lifetime window, percentages-only output, skips when run from the main checkout. Update `prompts/create-final-design.md` to invoke the script and embed its output as a standard "Token usage telemetry" section in `adr.md`. Tests under `.claude/scripts/tests/`.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-05-28T17:39Z [ctx=info] Review + decomposition complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 1 (8 findings, 8 absorbed into plan-of-work / context-and-orientation / validation; 0 deferred). Findings T1-T8 covered path-PII normalisation, recursive sub-agent walk, `uuid` dedup for tool_result records, the `tool_use_id` → tool_use index requirement, sum-to-100 rounding, empty-walk skip branch, stand-alone runner convention, and the Phase 4 invocation protocol.
- [x] Adversarial: PASS at iteration 1 (15 findings, 10 absorbed; 1 design-blocker resolved by user choice; 4 logged as known-debt for Phase 4). A1 (template-anchor drift in design.md §"Phase 4 ADR template extension") was the load-bearing blocker — user picked "append after `## Key Discoveries`"; design.md §"Section placement in adr.md" rewritten to match the actual `prompts/create-final-design.md:219-252` template. A2 (YTDB-1023 acceptance criterion 6 portfolio measurement) resolved as accept-gap-plus-follow-up: this track ships the per-ADR snapshot, [YTDB-1034](https://youtrack.jetbrains.com/issue/YTDB-1034) tracks the portfolio-aggregate measurement separately. A6-A8 + A10-A11 absorbed into plan-of-work + design.md. A12 (4 steps vs 2 — `session-stats.py` precedent) absorbed during decomposition: two steps, not four.

**Known-debt for Phase 4 design synthesis.** The following adversarial findings strengthen rationale rather than change the implementation; they are noted here so design-final.md / adr.md can address them at Phase 4 rather than block Phase B:

- A3 — I4's "plus session and file count" exemption needs an explicit motivation in design.md (the counts are scalar metadata about how much was measured, not per-bucket content metrics that could be reconstructed into raw totals).
- A4 — D4's lifetime-of-worktree window is non-comparable across ADRs of different durations; either accept the non-comparability and document it, or add a `--window=30d|lifetime` flag with `lifetime` as the default.
- A5 — `session-stats.py` already has the walker / dedup / cache idioms the telemetry script needs; the chosen path is duplicate-then-diverge. A shared `session_stats_core.py` helper or a `session-stats.py read-share` subcommand would consolidate, at the cost of one more refactor surface.
- A9 — design.md §"Standing infrastructure properties" claims "every future ADR carries it"; the historical record shows only ~28% of ADRs reference token-economic concepts. The rationale survives in spirit (the telemetry doubles as an audit trail of session shape) but the framing in §"Standing infrastructure properties" overcommits.
- A13 — D4's "open-source repo, privacy posture" framing does not survive scrutiny (YTDB-1023 itself publishes absolute token counts on public YouTrack). The real cost of absolute counts is non-comparability across worktrees, not privacy. Re-cast D4's rationale at Phase 4.
- A15 — `char-count / 4` diverges from API-reported `usage.input_tokens` by ~10-20%. Either document the divergence in the rendered footer, or adopt the API-reported method `session-stats.py` already implements. Cite as methodology note in design-final.md.

## Context and Orientation

`session-stats.py` is the closest existing precedent. It walks `~/.claude/projects/*/transcripts/*.jsonl`, aggregates input/output/read tokens for billing, and emits a statusline-friendly summary. Its `aggregate_file()` shape and per-file cache layout (`_cache_path_for` / `_load_cache` / `_store_cache`) are reusable; its `session_totals()` recursive walk into `<transcript-stem>/subagents/` matches what the telemetry script needs. The dedup key is **not** reusable: `session-stats.py` keys on `(message.id, requestId)` against assistant records only, but the telemetry script must tally `tool_result` blocks on `user` records, which carry `uuid` instead. The new script's dedup key is `uuid` per record.

The telemetry script's measurement methodology (per `design.md §"Telemetry script"`):

- Scope: this worktree's transcript folder, walked recursively — `~/.claude/projects/<encoded-cwd>/**/*.jsonl`. Sub-agent transcripts live under `<transcript-stem>/subagents/` and account for the majority of jsonl files on most worktrees; a non-recursive glob silently under-counts.
- Encoded path: cwd canonicalised via `Path.cwd().resolve()` then `/` → `-`, with fallback to the raw `Path.cwd()` encoding if the resolved-path folder is missing (the codebase has symlinks like `~/.claude/projects/-home-user-Projects-X -> -workspace-X` that this fallback handles).
- Window: lifetime of the worktree's folder (no rolling window).
- Worktree detection: `.git` file-vs-directory shape, not `git worktree list` ordering. `(Path.cwd() / '.git').is_file()` → linked worktree (proceed); `.is_dir()` → main checkout (skip); missing → not in a checkout (skip).
- Classification: build a `tool_use_id → (tool_name, tool_input.file_path)` index from assistant `tool_use` blocks; look up every `tool_result` block by `tool_use_id` to recover the tool name. The bucket table in `design.md §"Telemetry script" > Measurement methodology` lists each real record + block shape (assistant.text / assistant.thinking / user.tool_result / attachment / etc.) and the bucket it maps to.
- Output: percentages-only Markdown section ready for inclusion in `adr.md`. Percentages render to one decimal with largest-remainder rounding so each column sums to 100.0%. File paths in the top-files table are repo-relative (normalised via `Path.relative_to(Path.cwd())`); the script never publishes absolute paths.

Phase 4 today: `prompts/create-final-design.md` orchestrates the final-artifacts commit (and optional promote-staged-workflow commit on workflow-modifying plans). Step 3 writes the two final artifacts; `adr.md` is composed in Step 3 §"Artifact 2: ADR". This track extends Step 3's adr.md composition to invoke the telemetry script and embed its output inline as a new `## Token usage telemetry` H2 appended after `## Key Discoveries`. The placement matches `design.md §"Phase 4 ADR template extension" > Section placement in adr.md`; the template H2s it references are the ones the live `prompts/create-final-design.md:204-261` template actually defines.

### Files in scope

- `.claude/scripts/measure-read-share.py` — new file, live path.
- `.claude/scripts/tests/test_measure_read_share.py` — new test file, live path.
- `.claude/workflow/prompts/create-final-design.md` — modified, staged path per §1.7 (it's under `.claude/workflow/prompts/`).

### Files out of scope

- The reindex script — Track 2 territory.
- Per-section annotation rollout — Track 4 territory.
- Schema definition — Track 1 territory.

## Plan of Work

The track lands as a small step set. Decomposition lives in `## Concrete Steps`; the prose below names the work surfaces and the order they need to land in.

1. **Script — worktree detection + jsonl walk + bucket tally + rendering, plus its tests.** One commit covers `.claude/scripts/measure-read-share.py` end-to-end so no intermediate state has the script partially functional. Surfaces inside the commit:
   - Worktree-vs-main detection via `(Path.cwd() / '.git').is_file()` (linked) vs `.is_dir()` (main) vs missing (not in a checkout). No `git worktree list --porcelain` ordering heuristic.
   - Encoded-cwd path: `Path.cwd().resolve()` first, fallback to raw `Path.cwd()` when the resolved-path folder is missing under `~/.claude/projects/`.
   - Recursive jsonl walk (`**/*.jsonl`) so sub-agent transcripts under `<transcript-stem>/subagents/` are included.
   - Two-pass-or-one-pass-with-index build of `tool_use_id → (tool_name, tool_input.file_path)` from `assistant.tool_use` blocks; lookup for `user.tool_result` blocks against the index.
   - Dedup by `uuid` per record (not `(message.id, requestId)` — those are assistant-only and don't exist on `tool_result` records).
   - Bucket tally per the record-and-block table in `design.md §"Telemetry script" > Measurement methodology`. Skip records whose record-type is `mode`, `last-prompt`, `pr-link`, `file-history-snapshot`, or `system`.
   - Output rendering: tool-mix table (6 rows, 1-decimal largest-remainder rounding so the column sums to 100.0%), top-N file table (default 10, configurable via `--top=N`) with repo-relative path normalisation (`Path.relative_to(Path.cwd())`), generated-by footer.
   - Two skip-notice templates, one per cause: main-checkout vs no-transcripts.
   - Atomic render discipline: buffer the full section in memory, print on success; on parse failure mid-walk emit a skip notice with the per-file error and exit 0 so the ADR commit still succeeds.
   - Tests under `.claude/scripts/tests/test_measure_read_share.py`, stand-alone runner convention (no pytest; exit code 0 = pass, 1 = fail) per the precedent in `test_workflow_reindex.py:1-29`. Test fixtures cover: cwd = main checkout, cwd = linked worktree (`.git` file shape), cwd not in a checkout (`.git` missing), empty transcript folder (no-transcripts skip), symlinked cwd (resolve-then-fallback), recursive walk that includes a `subagents/` fixture, tool_use → tool_result lookup including out-of-order lines, mixed string-vs-list tool_result content, attachment records, rounding sum-to-100 invariant, repo-relative path normalisation (no absolute path in any rendered row).
2. **Phase 4 prompt integration.** Update `prompts/create-final-design.md` Step 3 §"Artifact 2: ADR" template to insert a `## Token usage telemetry` H2 at the end of the `adr.md` template, after `## Key Discoveries`. Add the canonical invocation line in the prompt body: the agent runs `python3 .claude/scripts/measure-read-share.py` after writing the rest of `adr.md`, captures stdout, and appends the captured Markdown as the final section. The skip-notice paths emit the same `## Token usage telemetry` H2 with the explanatory body, so the prompt does not branch on the script's exit code.

Step 2 modifies a file under `.claude/workflow/prompts/`, which routes through the §1.7 staging path. Step 1 modifies files under `.claude/scripts/`, which go to live paths.

## Concrete Steps

1. Build `measure-read-share.py` end-to-end with tests — worktree detection (`.git` file-vs-directory), recursive `**/*.jsonl` walk under the canonicalised `~/.claude/projects/<encoded-cwd>/`, tool_use_id index, bucket tally per the design.md record-and-block table, `uuid` dedup, repo-relative path normalisation, 1-decimal largest-remainder rounding, atomic buffer-then-print render, two distinct skip notices, stand-alone runner test file under `.claude/scripts/tests/test_measure_read_share.py` — risk: medium (new production tooling whose output ships in every future ADR; the publication-safety constraint I4 plus the path-PII normalisation are tests-can-catch-but-need-explicit-review-attention surfaces; no HIGH triggers)  [ ]
2. Update `prompts/create-final-design.md` Step 3 §"Artifact 2: ADR" to append a `## Token usage telemetry` H2 after `## Key Discoveries`, with the canonical bash invocation (`python3 .claude/scripts/measure-read-share.py`) — the agent runs the script after writing the rest of `adr.md`, captures stdout, appends the captured Markdown as the final section; skip-notice paths emit the same H2 with the explanatory body so the prompt does not branch on the script's exit code — risk: low (single-file additive prompt edit; behaviour change is mechanical "run script, paste output"; failure mode is a missing section in `adr.md`, non-load-bearing)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

After this track lands:

- `python3 .claude/scripts/measure-read-share.py` runs from a worktree and emits the Markdown section with real percentages from the worktree's transcript folder.
- The same script run from the main checkout emits the main-checkout skip notice and exits 0. A worktree whose transcript folder is empty emits the no-transcripts skip notice and exits 0. The two notices are distinct strings.
- The script never emits absolute token counts (verified by output inspection in tests). The only absolute values the output carries are the session count and the transcript-file count, both metadata about how much was measured.
- The output never contains absolute file paths under `/home/`, `/workspace/`, or similar — every top-files row is a repo-relative path (verified by a test that greps the rendered output for `^/`).
- The tool-mix table column sums to exactly 100.0% (verified by a test that parses the rendered table and asserts the sum).
- The recursive jsonl walk includes sub-agent transcripts under `<transcript-stem>/subagents/` (verified by a fixture test with at least one subagent jsonl).
- `prompts/create-final-design.md` Step 3 (Artifact 2: ADR) invokes the script and appends its output to `adr.md` as the `## Token usage telemetry` H2, placed after `## Key Discoveries`.
- Tests under `.claude/scripts/tests/test_measure_read_share.py` follow the stand-alone runner convention (no pytest; exit code 0 = pass) and cover the worktree detection matrix (`.git` file / `.git` directory / missing / symlinked cwd), the bucket tally against fixture jsonl (assistant.text / assistant.thinking / user.tool_result with index lookup / user.tool_result with string content / user.tool_result with list content / attachment / unknown record type), and the output rendering (rounding invariant, path normalisation, two skip notices).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

### In-scope file set

- `.claude/scripts/measure-read-share.py` (new, live path)
- `.claude/scripts/tests/test_measure_read_share.py` (new, live path)
- `.claude/workflow/prompts/create-final-design.md` (modified, staged path)

### Out-of-scope

- Reindex script — Track 2.
- Per-section annotations — Track 4.
- Schema definition — Track 1.

### Inter-track dependencies

- **Independent of Tracks 1 and 2** structurally. Lands before Track 4 in execution order so Track 4's annotation rollout picks up `create-final-design.md` with the telemetry-invocation prose in place.
- **Unblocks Phase 4 of this very plan.** This plan's own Phase 4 invokes the script and embeds the output in `adr.md` as the standing-infrastructure example.

### Library/function signatures touched

- `measure-read-share.py` exports CLI: no arguments needed for default behavior (auto-detects worktree, walks transcript folder, emits Markdown to stdout). Optional `--top=N` flag overrides top-files cap (default 10).
- `prompts/create-final-design.md` Step 3 (Artifact 2: ADR): bash block added to invoke the script and capture output while composing `adr.md`.

## Base commit

058c65b53a5abfd93a2e956496e45c0ac54dd889
