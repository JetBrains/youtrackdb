<!-- MANIFEST
role: reviewer-plan
phase: 2
review: consistency
iter: 1
track: 1
tier: minimal
verdict: PASS
findings: 1
evidence_base: 9 Ref certificates (8 MATCHES, 1 PARTIAL); axes run = track-vs-code (PLAN↔CODE lightened) + orphan-codebase GAPS; DESIGN↔CODE, DESIGN↔PLAN, design-half GAPS dropped (minimal, no design.md); tier-presence check PASS (ledger tier=minimal present).
index:
  - id: CR1
    sev: should-fix
    anchor: "### CR1 [should-fix]"
    loc: "track-1.md §Decision Log D1 (line 45-47)"
    cert: "Ref: track-review.md step-6 ctx-read attribution"
    basis: current-state-attribution-error
    class: mechanical
-->

# Consistency review — Track 1, iter 1

Tier `minimal`, confirmed from the phase ledger (`tier=minimal`, last value
wins). No `design.md` and no `implementation-plan.md` exist, so the
DESIGN↔CODE axis, the DESIGN↔PLAN axis, and the design half of GAPS are
dropped per the tier/design-presence guard; the PLAN↔CODE axis lightens to a
track-vs-code check (only the track-reference bullet runs). The
tier-presence check ran and passed: the ledger carries `tier=minimal`.

The branch is `s17=workflow-modifying` (§1.7(b) staging), but no
`staged-workflow/` subtree exists yet (Phase A/B have not run), so every
read of a `.claude/**` file resolved to the **live** file. Every reference
in this track points at workflow text (Markdown under `.claude/workflow/`,
the bash script `workflow-startup-precheck.sh`, the Python test
`test_workflow_startup_precheck.py`) — not Java symbols. mcp-steroid PSI does
not apply; all verification used Grep/Read with line-anchored citations. No
reference-accuracy caveat is needed (PSI is irrelevant for non-Java text).

Verdict: **PASS** — 1 should-fix, 0 blockers. Eight of nine current-state
references match the live machinery exactly; one (CR1) is a current-state
attribution error in a Decision Record (the ctx read lives in step 5, not
step 6) that is mechanical to fix and does not change the track's intent.

## Findings

### CR1 [should-fix]
**Certificate**: Ref: `track-review.md` step-6 ctx-read attribution
**Location**: `track-1.md` §Decision Log **D1** (line 45-47) — "Reuse the
`<level>` value **step 6** already read for its `## Progress` write to feed
`--ctx`." Live code: `.claude/workflow/track-review.md`.
**Issue**: D1 attributes the existing statusline context-level read to
**step 6**. In the live `track-review.md`, the read lives at the end of
**step 5** ("Write decomposed steps", heading at line 547; the
read-then-write block is lines 560-571). Step 6 ("Commit and push the Phase A
workflow updates", heading at line 581) performs only `git add` / `git
commit` / `git push` (lines 588-592) — it does not read the statusline. So
the literal phrase "step 6 already read" names the wrong step for the
pre-existing read.
**Evidence**: From the certificate — step 5 ends with the D12 read-then-write
order (read `level=` from `/tmp/claude-code-context-usage-$PPID.txt`, fall
back to `unknown`, append a `## Progress` entry tagged `[ctx=<level>]`) at
`track-review.md:560-571`; step 6 at `track-review.md:581-598` is commit and
push only. The track itself describes the read correctly everywhere else: the
§Context and Orientation section says "step 5 already performs a statusline
context-level read … Just before this [step 6]" (track-1.md:174-176), and the
§Plan of Work / §Interfaces sections say "step 5/6" (track-1.md:217, 285).
Only D1's bare "step 6 already read" is the mismatch. This is a current-state
claim (it describes the read that exists in the live file today, not a read
the `[ ]` track will create), so the intent-axis pre-screen passes it through
as a finding rather than suppressing it as target-state.
**Proposed fix**: In D1 (track-1.md:45-47), change "Reuse the `<level>` value
step 6 already read for its `## Progress` write" to "Reuse the `<level>` value
step 5 already read for its `## Progress` write" (or "step 5/6", matching the
§Plan of Work / §Interfaces phrasing). The append call itself still belongs in
step 6 immediately before the commit — only the attribution of the *existing*
read moves to step 5, which is where it lives.
**Classification**: mechanical
**Justification**: Current-state claim (the read exists in the live file
today), exactly one unambiguous correct rendering ("step 6" → "step 5", the
single step that performs the read), and the fix preserves D1's intent — the
append still lands in step 6 and reuses the level read in step 5. Per
§`mechanical` rule: current-state claim, one unambiguous correct rendering,
fix does not change what the plan is trying to achieve.

## Evidence base

All certificates are Ref certificates (no Flow or Invariant certificates —
this is a workflow-text change with no Java call flow to trace; the
acceptance "flows" are grep/state-read assertions verified as Refs below).
Grouped under PLAN↔CODE (track-vs-code) and GAPS (orphan-codebase) — the only
axes that run under `minimal`.

### PLAN ↔ CODE (track-vs-code check)

#### Ref: track-review.md §What You Do step 6 (single Phase A commit point) [Claim 1]
- **Document claim**: track-1.md D1 + §Context-and-Orientation + §Plan-of-Work
  step 1 — step 6 is the single Phase A commit point: `git add`s
  `track-<N>.md`, commits "Phase A review and decomposition for <track>", and
  pushes.
- **Search performed**: Grep `^[0-9]+\. \*\*` over track-review.md for step
  anchors; Read lines 581-598.
- **Code location**: `.claude/workflow/track-review.md:581` (step-6 heading),
  588-592 (the `git add docs/adr/<dir-name>/_workflow/plan/track-<N>.md` /
  `git commit -m "Phase A review and decomposition for <track>"` / `git push`
  block).
- **Actual signature/role**: Step 6 = "Commit and push the Phase A workflow
  updates"; the fenced bash block is exactly the three claimed commands, the
  commit message matches verbatim, and the §Phase A Completion step 2
  recovery clause (line 1014) confirms the tip is `Phase A review and
  decomposition for <track>`.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: track-review.md step-5 statusline ctx read [Claim 2]
- **Document claim**: A statusline context-level read already exists (reads
  `level=` from `/tmp/claude-code-context-usage-$PPID.txt`, falls back to
  `unknown`, writes a `## Progress` entry tagged `[ctx=<level>]`); the same
  `<level>` is reusable to feed the ledger append's `--ctx`. Track attributes
  it variously to "step 6" (D1), "step 5" (§Context-and-Orientation), and
  "step 5/6" (§Plan-of-Work, §Interfaces).
- **Search performed**: Grep `level=|claude-code-context-usage|unknown|\[ctx=`
  over track-review.md; Read lines 560-598.
- **Code location**: `.claude/workflow/track-review.md:560-571` (the read +
  `## Progress` write, nested under step 5 whose heading is at line 547).
- **Actual signature/role**: Step 5 ("Write decomposed steps", line 547) ends
  with the D12 read-then-write order: "Read `/tmp/claude-code-context-usage-
  $PPID.txt` and parse the `level=` value. If the file is missing or the parse
  fails, use `unknown`" → append `- [x] <ISO> [ctx=<level>] Review +
  decomposition complete` to `## Progress`. The read mechanism and reusable
  `<level>` are exactly as claimed. The read lives in **step 5**, not step 6.
- **Verdict**: PARTIAL
- **Detail**: The read's existence, mechanism, `unknown` fallback, and
  `[ctx=<level>]` Progress tag all MATCH. The step *attribution* mismatches in
  D1 ("step 6 already read") — the read is in step 5. §Context-and-Orientation
  ("step 5") is correct; §Plan-of-Work / §Interfaces ("step 5/6") are
  acceptable (step 6 immediately follows and is where the reuse + append
  happen). Drives finding CR1 (the D1 attribution only).

#### Ref: track-review.md §Phase A Completion step 2 (session-boundary gate) [Claim 3]
- **Document claim**: §Phase A Completion step 2 is the session-boundary gate:
  runs `git status --porcelain` (clean-tree) and `git log -1 --oneline` (tip
  is the Phase A commit), with a missing-commit recovery branch that re-runs
  step 6.
- **Search performed**: Read track-review.md:1008-1018.
- **Code location**: `.claude/workflow/track-review.md:1012-1018`.
- **Actual signature/role**: Step 2 = "Verify the Phase A commit landed. Run
  `git status --porcelain`; the working tree must be clean. Run `git log -1
  --oneline` and confirm the tip is `Phase A review and decomposition for
  <track>`. If the commit is missing (e.g., the session was interrupted
  between step 5 and step 6 of §What You Do), run step 6 now." Clean-tree
  check, tip check, and the missing-commit→"run step 6" recovery branch all
  present exactly as claimed.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: workflow-startup-precheck.sh --append-ledger --phase C --track N support [Claim 4]
- **Document claim**: The script's arg parser accepts `--phase` (~line 149),
  and the `C)` resume case in `determine_state_from_ledger` (~line 1781) reads
  the active track and emits `{phase:"C", substate:<track-driven>}`.
- **Search performed**: Grep `--phase` and `determine_state_from_ledger|C)`
  over workflow-startup-precheck.sh; Read lines 1781-1799.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:149`
  (`--phase)` arg-parser case); `:1755` (`determine_state_from_ledger()`);
  `:1781` (`C)` case).
- **Actual signature/role**: Line 149 = `--phase)` arm of the arg parser.
  Lines 1781-1799 = the `C)` case: `ledger_tail_value "track"` →
  `track="$LEDGER_VALUE"`, defaults to `"1"` when empty (minimal single-track
  per D10), resolves `track_file="$plan_dir/_workflow/plan/track-${track}.md"`,
  and when present emits `jq -nc '{phase:"C", substate:$s}'` from
  `determine_c_substate`. Matches "reads the active track and emits
  {phase:'C', substate:<track-driven>}".
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: workflow-startup-precheck.sh --ctx defaults to safe [Claim 5]
- **Document claim**: `--ctx` defaults to `safe` when omitted (claimed lines
  105-106, 1576: `ctx="${LEDGER_CTX:-safe}"`).
- **Search performed**: Grep `LEDGER_CTX:-safe|LEDGER_CTX` over
  workflow-startup-precheck.sh; Read lines 103-108 and 1574-1578.
- **Code location**: `.claude/scripts/workflow-startup-precheck.sh:1576`
  (`ctx="${LEDGER_CTX:-safe}"`); comment at `:105-107`.
- **Actual signature/role**: Line 1576 is exactly `ctx="${LEDGER_CTX:-safe}"`.
  The explanatory comment "LEDGER_CTX defaults to 'safe' so an entry always
  carries a `[ctx=…]` marker even when the caller forgets to pass one" spans
  lines 105-107 (`LEDGER_CTX=""` default-init at 108).
- **Verdict**: MATCHES
- **Detail**: The cited comment range "105-106" is off by one line on the
  trailing side (the sentence completes on line 107), and the bare
  `LEDGER_CTX=""` init is at 108. Sub-line cosmetic drift on a comment anchor,
  not a behavioral mismatch — the load-bearing default expression at 1576 is
  exact. Not a finding (the line is approximate "~line"-style and the
  substance holds).

#### Ref: workflow.md §Startup Protocol step 5 phase routing [Claim 6]
- **Document claim**: §Startup Protocol step 5 maps `phase == "C"` to the
  mid-track Phase B/C resume with Track Pre-Flight skipped, and `phase == "A"`
  to the pre-Phase-A path (Track Pre-Flight + Phase A re-run).
- **Search performed**: Grep `phase == "C"|phase == "A"|Pre-Flight` over
  workflow.md; Read lines 318-357.
- **Code location**: `.claude/workflow/workflow.md:325-338` (`phase == "A"`);
  `:339-354` (`phase == "C"`).
- **Actual signature/role**: `phase == "A"` → "the active track has no track
  file yet (pre-Phase-A) … Run the Track Pre-Flight gate … then Phase A in
  the same session." `phase == "C"` → "mid-track resume … Route on
  `state.substate`" with the substate table, closing "The Track Pre-Flight
  gate is **skipped** on State C resume." Both mappings match.
- **Verdict**: MATCHES
- **Detail**: The "step 5" framing is accurate — these are the per-`phase`
  routing cases on the precheck's emitted `state.phase` within the Startup
  Protocol's branch step.

#### Ref: sibling append sites — implementation-review.md:646, track-code-review.md:1403/1405 [Claim 7]
- **Document claim**: `implementation-review.md:646` is the `--phase A`
  append; `track-code-review.md:1403/1405` are the `--track N+1` / `--phase D`
  appends.
- **Search performed**: Grep `--append-ledger` over implementation-review.md;
  Grep `--append-ledger|--phase D|--track` over track-code-review.md.
- **Code location**: `implementation-review.md:646`
  (`...workflow-startup-precheck.sh --append-ledger --phase A`);
  `track-code-review.md:1403` (`...--append-ledger --track <N+1>`); `:1405`
  (`...--append-ledger --phase D`).
- **Actual signature/role**: All three lines match the claimed file:line and
  flag shape exactly.
- **Verdict**: MATCHES
- **Detail**: —

#### Ref: existing script tests test_workflow_startup_precheck.py:3426/3447 [Claim 8]
- **Document claim**: Tests at `:3426` exercise `--append-ledger --phase C
  --track 1` (the append leaves a `phase=C` line) and `:3447` exercise the
  resulting read (`phase=C` / `track=1`).
- **Search performed**: Read test_workflow_startup_precheck.py:3420-3455.
- **Code location**: `.claude/scripts/tests/test_workflow_startup_precheck.py:3426`
  (`run_precheck("--append-ledger", "--phase", "C", "--track", "1", ...)`
  inside the append test, asserting `"phase=C" in lines[1]`);
  `:3447` (`test_append_ledger_last_value_wins_on_read`, which runs the same
  `--phase C --track 1` append and asserts the ledger-driven read resolves
  `phase=C`/`track=1`).
- **Actual signature/role**: Line 3426 runs the `--phase C --track 1` append
  and asserts a two-line ledger with `phase=A` on line 0 and `phase=C` on
  line 1. The read test starting at line 3433 (docstring) / 3447 (the
  function's body / `--phase C --track 1` second append) asserts last-value-
  wins resolves to `phase=C`/`track=1`. Both behaviors match the claim.
- **Verdict**: MATCHES
- **Detail**: The `:3447` anchor lands inside the read test
  (`test_append_ledger_last_value_wins_on_read`) — the docstring opens at line
  3433 and the `--phase C` append it exercises is around 3447. The substance
  (the read resolves `phase=C`/`track=1`) is exact.

#### Ref: no --phase C append site exists today (the bug baseline) [Claim 9]
- **Document claim**: `grep -rn -- '--phase C' .claude/workflow/
  .claude/scripts/` returns NOTHING today — no A→C append site exists; this is
  acceptance criterion #3's baseline (the bug the fix removes).
- **Search performed**: `grep -rn -- '--phase C' .claude/workflow/
  .claude/scripts/`.
- **Code location**: NOT FOUND (grep exit 1, zero matches).
- **Actual signature/role**: The grep returns no lines and exits 1. No
  `--phase C` token exists anywhere under `.claude/workflow/` or
  `.claude/scripts/` on the live (develop-state) tree — confirming the bug:
  no A→C append site exists today.
- **Verdict**: MATCHES
- **Detail**: Confirms the bug's central claim. The fix (step-6 append in
  track-review.md) is what will make this grep non-empty; that target-state is
  not a finding (it is what the `[ ]` track creates).

### GAPS (orphan codebase constructs — runs in every tier)

#### Ref: orphan-codebase-construct sweep [GAPS]
- **Document claim**: (implicit) The track references all the machinery
  surfaces the fix touches and depends on.
- **Search performed**: Cross-checked the track's In-scope / Out-of-scope
  lists (track-1.md:277-306) against the verified surfaces (Claims 1-9).
- **Code location**: n/a (negative sweep).
- **Actual signature/role**: The track explicitly accounts for the script
  (`--phase C` support, out-of-scope, no change), the router
  (`workflow.md §Startup Protocol`, out-of-scope, no change), the two sibling
  append sites (out-of-scope, with the intentional `--ctx` divergence called
  out), the test module (in-scope, the regression guard), and the other ledger
  boundaries (State 0→A, completion, Phase D, Done — out-of-scope). No
  existing construct the fix should reference is omitted.
- **Verdict**: MATCHES
- **Detail**: No orphan finding. The §Interfaces and Dependencies section is
  complete for the change's footprint.
