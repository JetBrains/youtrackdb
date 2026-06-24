<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: CR1, sev: should-fix, loc: "plan/track-2.md:104", anchor: "### CR1 ", cert: R12, basis: "current-state phase-enum gloss omits Done; design, Track 1, and code all write {0, A, C, D, Done}"}
evidence_base: {section: "## Evidence base", certs: 18, matches: 17}
cert_index:
  - {id: R12, verdict: MISMATCHES, anchor: "#### R12 "}
flags: [CONTRACT_OK]
-->

## Findings

### CR1 [should-fix]
**Certificate**: R12 (Ref: phase-enum gloss, Track 2 `## Context and Orientation`)
**Location**: `plan/track-2.md:104` (`## Context and Orientation`). The same enum is written correctly elsewhere: `design.md:53`, `track-1.md:60,110,329`.
**Issue**: Track 2's orientation prose glosses the top-level phase enum as `{0, A, C, D}` with no `B`, omitting the `Done` value. Every other home of this enum — the design, Track 1, and the live code — writes it as `{0, A, C, D, Done}`. The omission is a current-state claim about an existing code structure (the orientation section describes the codebase at the start of the track, so the intent-axis pre-screen classifies it current-state regardless of track status), and the code does enumerate `Done` as a real phase value.
**Evidence**: `workflow-startup-precheck.sh:1797` switches on `0 | A | D | Done` (plus the `C` arm at `:1804`), so `Done` is a live phase value. `design.md:53` writes `(the enum {0, A, C, D, Done}, with no B)`. `track-1.md:110` writes `(the enum {0, A, C, D, Done}, with no B)` and `track-1.md:329` repeats `{0, A, C, D, Done}`. Track 2 alone drops `Done`. The point Track 2 is making (no `B` token; a Phase-B track records as `phase=C`) is unaffected by `Done`, so dropping it is an inadvertent omission, not a deliberate scoping of the enum to the within-track phases.
**Proposed fix**: In `track-2.md:104`, change `the top-level phase enum is \`{0, A, C, D}\` with no \`B\`` to `the top-level phase enum is \`{0, A, C, D, Done}\` with no \`B\``, matching the design and Track 1.
**Classification**: mechanical
**Justification**: Current-state claim (orientation section, current-state by the pre-screen's `## Context and Orientation` carve-out); single unambiguous correct rendering (add `Done`, the value the code and the two sibling docs already use); fix updates only the description and does not change what Track 2 achieves.

## Evidence base

Verification certificates grouped by review criterion. mcp-steroid/PSI does not apply: the referenced "code" is a Bash script, a Python test file, and Markdown resume-protocol docs, not Java symbols. Every reference was verified by grep on bash function definitions / Markdown headings and by Read against the live files, which is exact for these file types. No reference-accuracy caveat is needed.

### Tier and artifact set

Ledger-first tier read: `_workflow/phase-ledger.md` line 1 carries `tier=full` and `s17=workflow-modifying`; `design.md` is present. So the `full`-tier four-artifact comparison runs unchanged, and §1.7(b) staging mode is active. At Phase 2 nothing is implemented, so no `_workflow/staged-workflow/` subtree exists; every `.claude/**` read resolves to the live file (= develop state). Tier-presence check passes (ledger `tier` field present).

### Plan ↔ Code (existing-function references — must already exist)

#### R1 append_ledger
- **Document claim**: `design.md` §"Changes to existing functions" + Track 1 Plan-of-Work items 1-3: `append_ledger` has a validation block and a pre-`categories` append block that the change extends with `substate`.
- **Search performed**: `grep -nE '^[a-zA-Z_]+\(\)' workflow-startup-precheck.sh`; Read 1590-1662.
- **Code location**: `workflow-startup-precheck.sh:1590`.
- **Actual signature/role**: validation block `:1600-1606` (`reject_bad_ledger_value` for ctx/phase/track/tier/s17/paused/categories); pre-`categories` append block `:1622-1627` writes bare `phase`/`track`/`tier` before the quoted `categories` field, then `s17`/`paused` after. Matches the design's described shape (item 3's "before the quoted `categories` field" is precise — `phase`/`track` are at `:1622-1623`, `categories` at `:1625`).
- **Verdict**: MATCHES

#### R2 roster_scan
- **Document claim**: `design.md` §"The wrapped-roster fallback fix" + Track 1 item 6: the current scan matches `[0-9]*". "` for a roster entry, splits at the last `risk:`, and has a `*"risk:"*) … *) continue` arm that skips a column-0 line carrying no `risk:`.
- **Search performed**: Read 1306-1391.
- **Code location**: `workflow-startup-precheck.sh:1306`.
- **Actual signature/role**: column-0 guard `case "$line" in [0-9]*". "*) ;; *) continue ;;` at `:1337-1340`; `risk:` split `case "$line" in *"risk:"*) tail="${line##*risk:}" ;; *) continue ;;` at `:1345-1352`; status checkbox is the first `[...]` after the `risk:` split (`:1364-1374`). The bug mechanism the design's Worked trace describes (a wrapped column-0 line hits the `*) continue` arm at `:1350` and is skipped without counting) is exactly what the code does.
- **Verdict**: MATCHES

#### R3 determine_c_substate
- **Document claim**: `design.md` §"Changes to existing functions": `determine_c_substate` is unchanged in logic, becomes the fallback, calls `roster_scan`, and emits `failed-step` / `section-discrepancy`.
- **Search performed**: Read 1713-1768.
- **Code location**: `workflow-startup-precheck.sh:1713`.
- **Actual signature/role**: calls `roster_scan` at `:1724` and `progress_step_numbers` at `:1725`; emits `section-discrepancy` (`:1735`), `failed-step` (`:1742`), `steps-partial` (`:1747`), `steps-done-review-pending` (`:1766`), `review-done-track-open` (`:1764`). Matches.
- **Verdict**: MATCHES

#### R4 determine_state_from_ledger
- **Document claim**: `design.md` Overview + §"Changes to existing functions" + Track 1 item 5: resolves phase `C` to a track file, defaults the active track to `1`, and calls `determine_c_substate`; the change inserts the track-scoped ledger read before that call.
- **Search performed**: Read 1778-1821.
- **Code location**: `workflow-startup-precheck.sh:1778`.
- **Actual signature/role**: `phase=C` arm at `:1804-1821` reads `ledger_tail_value "track"`, defaults `track="1"` (`:1810`), builds `track_file` (`:1811`), and calls `determine_c_substate "$track_file" "todo"` at `:1813`. Matches the insertion-point description exactly.
- **Verdict**: MATCHES

#### R5 ledger_tail_value
- **Document claim**: `design.md` §"The new track-scoped reader" + Track 1 item 4: the existing `ledger_tail_value <key>` is global (keeps the last value of a key across every line); its emit-order safety invariant (read-keys written before the quoted `categories` field) carries over to the new reader.
- **Search performed**: Read 1664-1707.
- **Code location**: `workflow-startup-precheck.sh:1675`.
- **Actual signature/role**: scans every line, takes the FIRST ` $key=` token, last-value-wins (`:1691-1705`); the comment at `:1685-1690` states the emit-order invariant the design relies on. Matches.
- **Verdict**: MATCHES

#### R6 reject_bad_ledger_value
- **Document claim**: `design.md` §"The `substate` key" + Track 1 item 1: rejects a newline in any field and a space in any bare-token field with exit 3 and a stderr diagnostic; adding `substate` is a one-line addition mirroring the `phase`/`track` lines.
- **Search performed**: Read 1562-1588.
- **Code location**: `workflow-startup-precheck.sh:1562`.
- **Actual signature/role**: rejects newline in any field (`:1567-1572`, exit 3 + stderr) and a space in a `bare` field (`:1573-1579`, exit 3 + stderr); `append_ledger`'s block calls it for each bare key (`:1601-1605`). The proposed `reject_bad_ledger_value "substate" "$LEDGER_SUBSTATE" bare` mirrors those calls. Matches.
- **Verdict**: MATCHES

#### R7 ledger_tail_value_for_track (new — target-state)
- **Document claim**: Track 1 item 4 / `design.md` §"The new track-scoped reader": the change ADDS `ledger_tail_value_for_track <key> <track>`.
- **Search performed**: `grep -nE 'ledger_tail_value_for_track' workflow-startup-precheck.sh` → no match (exit 1).
- **Code location**: NOT FOUND (expected).
- **Actual signature/role**: n/a — this is a NEW function a `[ ]` track creates. Target-state; its absence is expected, not a finding. Reachable from the current code (it composes over the existing per-line scan idiom `ledger_tail_value` already uses).
- **Verdict**: MATCHES (target-state, correctly absent)

#### R8 --substate flag and LEDGER_SUBSTATE (new — target-state)
- **Document claim**: Track 1 items 2-3 / `design.md` §"Changes to existing functions": ADD a `--substate` arg case filling `LEDGER_SUBSTATE`, declared with the other `LEDGER_*` accumulators.
- **Search performed**: `grep -nE 'LEDGER_SUBSTATE|--substate|substate' workflow-startup-precheck.sh` → `substate` appears only in determine_c_substate logic and STATE_JSON comments; no `LEDGER_SUBSTATE`, no `--substate` flag.
- **Code location**: NOT FOUND (expected). Existing `LEDGER_*` accumulators and `--` arg cases live around `:120` and `:161-169`.
- **Actual signature/role**: n/a — NEW, target-state. Reachable.
- **Verdict**: MATCHES (target-state, correctly absent)

#### R9 phase-ledger key set / script-header grammar
- **Document claim**: `design.md` §"The `substate` key" + Track 1 item 7: the fixed key set is `{ phase, track, tier, categories, s17, paused }`; `substate` joins it as the seventh key; the script-header grammar comment lists the key set and the validated bare-token list.
- **Search performed**: `grep -nE 'phase, track, tier|key set|bare-token' workflow-startup-precheck.sh`; Read 51-62.
- **Code location**: `workflow-startup-precheck.sh:51-62`.
- **Actual signature/role**: header line `:56` says "The key set is exactly { phase, track, tier, categories, s17, paused }"; `:61-62` names the bare-token fields `(phase/track/tier/s17/paused/ctx)`. Matches the design's six-key claim exactly.
- **Verdict**: MATCHES

#### R10 determine_state (top-level resolver)
- **Document claim**: `design.md` Overview: `determine_state` prefers `determine_state_from_ledger` and otherwise walks the plan checkboxes, so it still runs when there is no ledger.
- **Search performed**: Read 1832-1870.
- **Code location**: `workflow-startup-precheck.sh:1832`.
- **Actual signature/role**: `if determine_state_from_ledger; then return; fi` at `:1839-1841`, then the legacy plan-checkbox walk (`:1853` onward). Matches.
- **Verdict**: MATCHES

### Plan ↔ Code (Track 2 append-site references — existing boundaries the appends ride)

#### R11 track-review.md A→C append sites
- **Document claim**: Track 2 Plan-of-Work item 1 + Component Map + Interfaces: `track-review.md` step 6 (`:596`) already appends `--phase C --track <N>`, with a recovery-path append at `:1048`.
- **Search performed**: `grep -nE 'append-ledger' track-review.md`; Read context.
- **Code location**: `track-review.md:596-597` (`--append-ledger ... --phase C --track <N>`) and `:1048-1049` (recovery path, same shape).
- **Actual signature/role**: both append sites exist at the claimed lines and already carry `--phase C --track <N>`. Adding `--substate steps-partial` is target-state (a `[ ]` track creates it). Matches the existing boundary the append rides.
- **Verdict**: MATCHES

#### R12 phase-enum gloss in Track 2 orientation
- **Document claim**: `track-2.md:104` (`## Context and Orientation`): "the top-level phase enum is `{0, A, C, D}` with no `B`".
- **Search performed**: `grep -nE '\{0, A, C, D|phase enum'` across the three docs; Read `workflow-startup-precheck.sh:1796-1803`.
- **Code location**: `workflow-startup-precheck.sh:1797` (`0 | A | D | Done)`), `:1804` (`C)`); `design.md:53`; `track-1.md:60,110,329`.
- **Actual signature/role**: the code enumerates `Done` as a real phase value; the design and Track 1 both write the enum as `{0, A, C, D, Done}`. Track 2 alone drops `Done`.
- **Verdict**: MISMATCHES
- **Detail**: current-state gloss omits `Done`. → finding CR1.

#### R13 step-implementation.md §Phase B Completion (no commit / no append today)
- **Document claim**: `design.md` §"Why every append rides a committed boundary" + Track 2 item 2: today §Phase B Completion marks `Step implementation [x]` in `## Progress` and ends the session with no commit and no append; the Phase B→C boundary needs a NEW commit.
- **Search performed**: `grep -nE 'Phase B Completion|Step implementation'`; Read 1070-1107.
- **Code location**: `step-implementation.md:1070` (§Phase B Completion), `:1076` (Mark `Step implementation` as `[x]`).
- **Actual signature/role**: step 1 marks `Step implementation [x]`; steps 2-4 inform the user, run reflection, end the session — no commit and no `--append-ledger` instruction anywhere in the section. Confirms the design's "needs a new commit" premise.
- **Verdict**: MATCHES

#### R14 track-code-review.md pre-approval code-review-complete commit
- **Document claim**: Track 2 item 3 + table: `track-code-review.md` commits a pre-approval code-review-complete Workflow-update commit around `:743` (the Progress entry recording the passed iteration).
- **Search performed**: Read 735-755.
- **Code location**: `track-code-review.md:737-746`.
- **Actual signature/role**: §Sub-step appends `- [x] <ISO> ... Track-level code review iteration N complete (N/3 iterations)` to `## Progress` and commits it as a Workflow update commit (`:743-746`). The pre-approval boundary exists at the claimed location; `--substate review-done-track-open` is the target-state addition.
- **Verdict**: MATCHES

#### R15 track-code-review.md step 5 track-advance / phase-D appends
- **Document claim**: Track 2 item 4 + Component Map + table: `track-code-review.md` step 5 (`:1401`) appends the completion boundary; today `--track <N+1>` (`:1409`) or `--phase D` (`:1411`).
- **Search performed**: `grep -nE 'append-ledger|phase D'`; Read 1395-1415.
- **Code location**: `track-code-review.md:1401` (step 5), `:1409` (`--append-ledger --track <N+1>`), `:1411` (`--append-ledger --phase D`).
- **Actual signature/role**: step 5 records completion in the ledger; the two append commands are at the exact claimed lines. `--substate decomposition-pending` on the `--track <N+1>` append is target-state; the design's note that `--phase D` carries no `substate` matches the absence of a substate need at phase `D`. Matches.
- **Verdict**: MATCHES

#### R16 inline-replanning.md replan-revert / existing phase-0 reset
- **Document claim**: Track 2 item 5 + table: `inline-replanning.md` appends `--phase 0` on the replan PASS path (`:249`); the new `--substate steps-partial` revert is distinct from that reset.
- **Search performed**: `grep -nE 'append-ledger'`; Read 243-258.
- **Code location**: `inline-replanning.md:249` (`--append-ledger --phase 0`).
- **Actual signature/role**: the `--phase 0` reset exists at the claimed line, on the Review-PASS path. The `--substate steps-partial` revert Track 2 adds is target-state. Matches the existing reset the design distinguishes from.
- **Verdict**: MATCHES

### Design ↔ Code

#### R17 workflow.md step 5 sub-state routing slugs (out-of-scope; byte-identical)
- **Document claim**: `design.md` D1 + Track 1/Track 2 Interfaces: the four committed slugs map 1:1 to the slugs `workflow.md` step 5 already routes on; `workflow.md` is out-of-scope (unchanged) because the slugs are byte-identical.
- **Search performed**: `grep -nE 'decomposition-pending|steps-partial|steps-done-review-pending|review-done-track-open' workflow.md`.
- **Code location**: `workflow.md:344` (`decomposition-pending`), `:347` (`steps-partial`), `:348` (`steps-done-review-pending`), `:349` (`review-done-track-open`).
- **Actual signature/role**: all four slugs appear verbatim in step 5's routing table. Confirms the out-of-scope claim and that no `workflow.md` edit is needed.
- **Verdict**: MATCHES

### Design ↔ Plan / Gaps

#### R18 test surface helpers
- **Document claim**: `design.md` §"Test surface" + Track 1 §Validation: the test file already has a `write_ledger` helper and `_substate` / `_track_doc` helpers, plus existing bare-token rejection tests the `--substate` validation test mirrors.
- **Search performed**: `grep -nE 'def write_ledger|def _substate|def _track_doc|reject.*bare'` over `test_workflow_startup_precheck.py`.
- **Code location**: `write_ledger` `:462`, `_track_doc` `:2585`, `_substate` `:2600`; existing rejection tests `test_append_ledger_rejects_newline_in_field` `:3862`, `test_append_ledger_rejects_double_quote_in_categories` `:3886`, `test_append_ledger_rejects_space_in_bare_field` `:3909`.
- **Actual signature/role**: all three helpers exist; the three bare-token/field rejection tests confirm the exit-3-plus-stderr pattern the new `--substate` test mirrors. Five-test-group plan has reusable scaffolding. Matches.
- **Verdict**: MATCHES

### Coverage / gap notes (no findings)

- **Design ↔ Plan alignment.** D1/D2/D3 in `design.md` map to track Decision Logs: Track 1 owns D1 (read side) and D2; Track 2 owns D1 (append side) and D3. The split is internally consistent — each DR's "Implemented in" line and "Read side (Track 1)" / "append side" framing matches the in-scope-files lists. No orphan design section: every `design.md` section maps to a track (grammar/reader/dual-path/wrap-fix → Track 1; lifecycle/append-cadence → Track 2). No orphan codebase construct: the existing ServiceLoader-style SPI patterns are irrelevant here (bash/markdown machinery), and the change reuses the established `--append-ledger` subcommand and `ledger_tail_value` idiom rather than inventing a parallel mechanism.
- **Scope-vs-complexity.** Both tracks declare ~4 files with written sizing justifications (merge-candidate cut at the core→consumer boundary), consistent with the design's two-sided footprint. No suspicious complexity/scope mismatch.
- **Intent-axis pre-screen outcome.** All new functions/flags/keys (`ledger_tail_value_for_track`, `--substate`/`LEDGER_SUBSTATE`, the `substate` key, the four `--substate` append sites, the new Phase-B-complete commit) are target-state for `[ ]` tracks and reachable from the current code; none is a finding. The single emitted finding (CR1) is a current-state claim per the `## Context and Orientation` carve-out.
