<!-- workflow-sha: 676179cb82295cf15977823a415d5f5476e42526 -->
# Track 3: Telemetry script + Phase 4 prompt integration

## Purpose / Big Picture

After this track lands, every future Phase 4 ADR carries a percentages-only "Token usage telemetry" section populated by `measure-read-share.py`, computed against the worktree's transcript-folder lifetime.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Build `.claude/scripts/measure-read-share.py` — worktree-scoped, lifetime window, percentages-only output, skips when run from the main checkout. Update `prompts/create-final-design.md` to invoke the script and embed its output as a standard "Token usage telemetry" section in `adr.md`. Tests under `.claude/scripts/tests/`.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

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

## Context and Orientation

`session-stats.py` is the closest existing precedent. It walks `~/.claude/projects/*/transcripts/*.jsonl`, aggregates input/output/read tokens for billing, and emits a statusline-friendly summary. Its `aggregate_file()`, record dedup (`message.id`, `requestId`), and per-file caching idioms are reusable in the new script.

The telemetry script's measurement methodology (per `design.md §"Telemetry script"`):

- Scope: this worktree's transcript folder only — `~/.claude/projects/<encoded-cwd>/*.jsonl` where `<encoded-cwd>` is the cwd's absolute path with `/` replaced by `-`.
- Window: lifetime of the worktree's folder (no rolling window).
- Detection: `git worktree list --porcelain` first entry = main worktree; skip if cwd matches; proceed otherwise.
- Output: percentages-only Markdown section ready for inclusion in `adr.md`.

Phase 4 today: `prompts/create-final-design.md` orchestrates the final-artifacts commit (and optional promote-staged-workflow commit on workflow-modifying plans). Step 3 writes the two final artifacts; `adr.md` is composed in Step 3 §"Artifact 2: ADR". This track extends Step 3's adr.md composition to invoke the telemetry script and embed its output inline as the `## Token usage telemetry` section.

### Files in scope

- `.claude/scripts/measure-read-share.py` — new file, live path.
- `.claude/scripts/tests/test_measure_read_share.py` — new test file, live path.
- `.claude/workflow/prompts/create-final-design.md` — modified, staged path per §1.7 (it's under `.claude/workflow/prompts/`).

### Files out of scope

- The reindex script — Track 2 territory.
- Per-section annotation rollout — Track 4 territory.
- Schema definition — Track 1 territory.

## Plan of Work

The track lands in four steps:

1. **Worktree-vs-main detection.** Implement the `git worktree list --porcelain` parsing logic plus the cwd-comparison check. Returns a tuple `(is_worktree: bool, encoded_path: str)`. Tests cover: cwd = main checkout, cwd = linked worktree, cwd outside any worktree, missing git (graceful skip).
2. **jsonl walk and bucket tally.** Implement the per-file walker: read each line as JSON, classify content blocks (`tool_result:Read` by file path, other tool results by name, non-tool content as "Prompts and output"), sum approximate token counts (char count / 4 per existing convention). Aggregate per worktree's folder. Tests use fixture jsonl files.
3. **Output rendering.** Format the tool-mix table and top-10 file table into Markdown per `design.md §"Telemetry script" > Output format`. Skip notice for non-worktree case. Tests verify rendering against fixture inputs.
4. **Phase 4 prompt integration.** Update `prompts/create-final-design.md` Step 3 §"Artifact 2: ADR" to invoke `python3 .claude/scripts/measure-read-share.py` while composing `adr.md`, capture the output, and embed it as the `## Token usage telemetry` section. The section's position in `adr.md` follows the template in `design.md §"Phase 4 ADR template extension"`. Step 3 is the adr.md-write step; Step 5 (the final-artifacts commit) only stages files already written to disk.

Step 4 modifies a file under `.claude/workflow/prompts/`, which routes through the §1.7 staging path. Steps 1–3 modify files under `.claude/scripts/`, which go to live paths.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

## Validation and Acceptance

After this track lands:

- `python3 .claude/scripts/measure-read-share.py` runs from a worktree and emits the Markdown section with real percentages from the worktree's transcript folder.
- The same script run from the main checkout emits the skip notice and exits 0.
- The script never emits absolute token counts (verified by output inspection in tests).
- `prompts/create-final-design.md` Step 3 (Artifact 2: ADR) invokes the script and embeds its output in `adr.md` as `## Token usage telemetry`.
- Tests under `.claude/scripts/tests/test_measure_read_share.py` cover the worktree detection matrix, the bucket tally against fixture jsonl, and the output rendering.

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
