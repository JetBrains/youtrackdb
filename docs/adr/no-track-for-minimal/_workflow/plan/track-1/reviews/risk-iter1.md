<!-- MANIFEST
role: reviewer-risk
phase: 3A
track: "Track 1: The phase ledger, the new artifact model, and the authoring surface"
iteration: 1
findings: 5
verdict: changes-requested
evidence_base:
  exposures: 3
  assumptions: 5
  testability: 3
index:
  - id: R1
    sev: should-fix
    anchor: "### R1 "
    loc: "track-1.md Plan of Work step 1 + step 6; workflow-startup-precheck.sh determine_state L1439-1560; workflow.md Startup Protocol step 5 L272-320"
    cert: "Exposure: determine_state resume state machine"
    basis: "minimal-no-plan A/C/D/Done re-derivation underspecified"
  - id: R2
    sev: should-fix
    anchor: "### R2 "
    loc: "track-1.md D3 Risks/Caveats L72-74; design.md L189-191; workflow-startup-precheck.sh no_drift_normalization L407-408"
    cert: "Assumption: existing interrupted-write reconciliation covers torn append"
    basis: "no ledger reconciliation routine exists; mitigation is atomic rename only"
  - id: R3
    sev: should-fix
    anchor: "### R3 "
    loc: "track-1.md Plan of Work step 1 + Contracts-published L298-305; D6 L91-104"
    cert: "Assumption: ledger event grammar is a defined Track-2 contract"
    basis: "grammar deferred to implementation; Track 2 depends on it unspecified"
  - id: R4
    sev: suggestion
    anchor: "### R4 "
    loc: "track-1.md Plan of Work step 3 (1.6f); conventions.md 1.6(f) L754-768"
    cert: "Assumption: 1.6(f) exclusion is the only drift-walk edit needed"
    basis: "exclusion is safe direction but prose argues 3-walk+fixture coupling"
  - id: R5
    sev: suggestion
    anchor: "### R5 "
    loc: "track-1.md Plan of Work step 2; test_workflow_startup_precheck_stub.py whole-file premise"
    cert: "Testability: rework of stub test for no-plan minimal resume"
    basis: "stub file's load-bearing premise (no script change) is inverted"
-->

# Risk review — Track 1 (iteration 1)

Verdict: changes-requested. Five findings, all should-fix or suggestion. No
blocker. The track is feasible and the staging posture (I6) is sound — the new
`workflow-startup-precheck.sh` and its tests live under `staged-workflow/` and
are not wired into any live hook or `settings.json`, so there is no live-test
regression risk this branch can hit. The findings concentrate on the resume
state machine: the `minimal`-no-plan path through `determine_state` is the
critical path here, and the track defines its contract too loosely for an
implementer to land it without re-deriving design intent.

## Evidence base

### Critical path exposure

#### Exposure: determine_state resume state machine (the minimal-no-plan path)
- **Track claim**: Plan of Work step 1 — "Rewrite `determine_state` to read the
  ledger tail (latest value per key) for the top-level phase and active track,
  keeping the track-file `## Progress` read for the within-track sub-state." Step
  6 — "Workflow.md §Startup Protocol. State derivation reads the ledger tail
  rather than the plan checkboxes."
- **Critical path trace**:
  1. Entry: `determine_state()` @ `workflow-startup-precheck.sh:1439`.
  2. `plan_file="$plan_dir/_workflow/implementation-plan.md"` @ L1445 — then
     `if [ ! -f "$plan_file" ]; then STATE_JSON='{"phase":"0",...}'` @ L1452-1455.
     Today an absent plan is an unconditional State 0.
  3. State A/C/D/Done are all derived from plan-file checkbox walks: the
     `## Checklist` first-`[ ]`-track walk @ L1467-1546 (drives A vs C and the
     active track number) and `section_first_checkbox_token "$plan_file" "Final
     Artifacts"` @ L1553 (drives D vs Done).
  4. `workflow.md` Startup Protocol step 5 @ L272-320 pins the same dependence:
     "There is **no `state.track` field** — re-derive the active track by
     walking the plan's `## Checklist`" (L275-276); "`phase == D` — every track
     is `[x]`/`[~]` and `## Final Artifacts` is `[ ]`" (L314-315).
- **Blast radius**: a `minimal` branch has no `implementation-plan.md` (D2), so
  the unchanged L1452 branch strands every `minimal` resume at State 0 forever —
  re-running the autonomous plan review on each `/execute-tracks`. Conversely a
  rewrite that drops the plan walk for ALL tiers regresses the `lite`/`full`
  active-track re-derivation and Phase-D detection that workflow.md step 5 still
  reads from the Checklist. A wrong split here mis-resumes every future session.
- **Existing safeguards**: the two-level rule (ledger owns top-level phase +
  active track, track file `## Progress` owns sub-state) is stated in D3 and the
  plan `### Constraints`. The State A/C/D/Done test battery
  (`test_state_0_*` / `_A_` / `_C_` / `_D_` / `_done_*` @
  `test_workflow_startup_precheck.py:1931-2412`) pins the current plan-driven
  behavior, so a regression in the `lite`/`full` path is caught by an existing
  test — provided those tests are kept and not deleted in the stub rework.
- **Residual risk**: MEDIUM. The track names the right primitive (ledger tail +
  two-level read) but does not specify (a) how `minimal` re-derives the active
  track without a `## Checklist`, (b) how Phase D / Done are distinguished for
  `minimal` without `## Final Artifacts`, or (c) whether the plan-checkbox walk
  is retained as the `lite`/`full` branch or fully replaced. An implementer
  can land a plausible-but-wrong split.

#### Exposure: drift walk and the 1.6(f) ledger exclusion (D13)
- **Track claim**: Plan of Work step 1 — "Add the ledger to the drift logic as
  an unstamped artifact (it is not folded into the §1.6(h) stamp walk)." Step 3
  — "§1.6(f) ledger exclusion (D13)."
- **Critical path trace**:
  1. `detect_drift()` @ `workflow-startup-precheck.sh:474` runs the §1.6(h)
     four-type walk @ L488-498 over `implementation-plan.md`, `design.md`,
     `design-mechanics.md`, `plan/track-*.md`. The ledger is not in that glob.
  2. The same four-type glob recurs at `detect_migrate_range` @ L689-700 and
     `no_drift_normalization` @ L391-398.
  3. `conventions.md` §1.6(f) @ L754-768 documents this triple-site coupling and
     warns that growing the *stamped* set "means editing all three sites and the
     conformance fixture … which §1.7 staging's S1 invariant forbids on this
     branch."
- **Blast radius**: D13 adds the ledger to the §1.6(f) **exclusion** list (not
  stamped, not walked), which is the safe direction — it requires NO change to
  the three drift-walk globs and NO conformance-fixture edit. The risk is only
  the inverse: if an implementer misreads "add the ledger to the drift logic"
  (step-1 wording) as "add it to the walk," they trip exactly the triple-site +
  fixture edit §1.6(f) calls out as S1-forbidden on a staged branch.
- **Existing safeguards**: §1.6(f) prose itself is the safeguard — it spells out
  why the exclusion (not inclusion) is correct and what inclusion would cost. The
  conformance test pinning the §1.6(h) byte-source is referenced in the script
  header comment (L13, L196-198).
- **Residual risk**: LOW. The design intent (exclusion) is sound and zero-edit
  on the hot walk; the only residual is the step-1 phrase "add the ledger to the
  drift logic," which reads as inclusion. Tightening the wording removes it.

#### Exposure: live-machinery wiring of the new script (I6 staging)
- **Track claim**: `## Context and Orientation` — "The new
  `workflow-startup-precheck.sh` and its tests are exercised from the staged
  path, not wired into the live machinery." Plan `### Constraints`: "No live test
  runs against the staged script."
- **Critical path trace**:
  1. `grep -rn 'workflow-startup-precheck' .claude/settings.json .claude/hooks/`
     returns nothing — no hook or settings entry invokes the script.
  2. The live script under `.claude/scripts/` is the develop-state binary this
     branch runs under; the staged copy lands under
     `staged-workflow/.claude/scripts/`.
- **Blast radius**: none on the live workflow this branch executes — the staged
  script and its tests cannot affect the running session, so a bug in the staged
  `determine_state` cannot strand THIS branch's own resume.
- **Existing safeguards**: the §1.7(b) staging invariant (I6) routes every
  `.claude/**` edit to the staged mirror; the test runners shell out to
  `SCRIPT_PATH = REPO_ROOT/.claude/scripts/workflow-startup-precheck.sh`
  (`test_*_precheck.py:99`), so a staged test must be pointed at the staged
  script path, not the live one, to exercise the new behavior.
- **Residual risk**: LOW. No live blast radius. One operational note for Phase B
  carries over to R5: the staged tests must invoke the staged script path or they
  will silently test the unchanged live script and pass vacuously.

### Unknowns and assumptions

#### Assumption: the existing interrupted-write reconciliation covers a torn ledger append
- **Track claim**: D3 Risks/Caveats @ `track-1.md:72-74` — "a torn append must
  not corrupt state — the atomic temp-file-plus-rename append plus the existing
  interrupted-write reconciliation cover it."
- **Evidence search**: grep over `workflow-startup-precheck.sh` for
  `interrupted|torn|reconcil|atomic|mv .*tmp` (IDE reachable; this is bash, so
  text search is the correct lens, not PSI).
- **Code evidence**: the only atomic-write precedent is
  `{ printf ...; tail -n +2 "$f"; } > "$f.tmp" && mv "$f.tmp" "$f"` @ L407-408
  inside `no_drift_normalization` — a temp-file-plus-rename, sound. The two
  "interrupted-write" references @ L1266 and L1364 are the roster-vs-`## Progress`
  `section-discrepancy` machinery (the sub-step-7.1/7.2 gap), a DIFFERENT
  mechanism that reconciles a track-file inconsistency, not a ledger append.
  `design.md:189-191` is more careful: the actual mitigation it claims is the
  atomic rename ("a torn write leaves the prior tail intact"), and "the same way
  `determine_state` already reconciles an interrupted `## Progress` write" is an
  *analogy*, not a claim that the section-discrepancy code runs on the ledger.
- **Verdict**: UNVALIDATED (over-claim, not contradiction).
- **Detail**: the real mitigation — atomic temp+rename so a torn write leaves the
  prior tail intact, with last-value-wins reads — is sound and has in-script
  precedent (L407-408). But the track-file D3 wording ("the existing
  interrupted-write reconciliation cover[s] it") implies a reconciliation routine
  already exists for the ledger; none does. An implementer reading D3 literally
  may look for (or assume) a reconciliation step that is not there.

#### Assumption: the ledger event grammar is a defined contract Track 2 can consume
- **Track claim**: Plan of Work step 1 — "Decide and pin the event vocabulary
  and field grammar (phase, track, tier + matched categories, §1.7 mode, paused)
  — this grammar is the contract Track 2's readers consume." Contracts-published
  @ L298-305 lists "the ledger event grammar `determine_state` greps (line shape,
  key set, last-value-wins read)."
- **Evidence search**: read `design.md` §"The phase ledger" (L144-197) and D6
  (L158-167); grep for a concrete line-format spec.
- **Code evidence**: D6 (plan L91-104) and design L46-49 describe the fields (ISO
  timestamp, `[ctx=…]` marker, phase, optional active track, optional field
  updates) but no example line and no key-name spelling. The grammar is
  explicitly "decide and pin" work inside step 1 — it does not exist on disk yet.
- **Verdict**: UNVALIDATED.
- **Detail**: this is the published inter-track contract Track 2 depends on
  (`track-1.md` Inter-track dependency L307-309 + Contracts L298-305). Track 2
  re-points ~9 readers (per the Phase-2 CR1 escalation) onto this grammar. If the
  grammar is improvised at implementation time without an example line pinned in
  the track or a test, Track 2's readers and the `determine_state` greps can drift
  from the producer (`--append-ledger`) — the exact "missed reader silently reads
  a stale or absent fact" risk plan D4 names. Not a Track-1 blocker (Track 1 owns
  both producer and consumer-grep), but the contract should be made concrete
  before decomposition so Track 2 can be planned against a fixed shape.

#### Assumption: §1.6(f) exclusion is the only drift-walk change (no walk-glob edit)
- **Track claim**: Plan of Work step 3 — "§1.6(f) ledger exclusion (D13)"; step 1
  — "Add the ledger to the drift logic as an unstamped artifact (it is not folded
  into the §1.6(h) stamp walk)."
- **Evidence search**: grep the three walk globs in
  `workflow-startup-precheck.sh` (L488-498, L689-700, L391-398) and read
  §1.6(f) L754-768.
- **Code evidence**: the three globs enumerate four fixed artifact types; none
  includes the ledger today. §1.6(f) L760-768 states adding to the *stamped* set
  forces editing all three globs + the conformance fixture, S1-forbidden here.
- **Verdict**: VALIDATED (exclusion is correct and zero-edit on the walk).
- **Detail**: the design is right — excluding the ledger leaves the three walks
  and the fixture untouched. Only the step-1 phrasing risks a misread.

#### Assumption: minimal resume routing has a non-Checklist active-track signal
- **Track claim**: D10 — "Step 1c reads the ledger (present and tier line
  readable) to route a `minimal` resume to its recorded state; the
  `plan/track-1.md` glob is the secondary signal."
- **Evidence search**: read `create-plan/SKILL.md` Step 1c (L131-230) and
  workflow.md step 5 (L272-320).
- **Code evidence**: today Step 1c routes on `design.md`/`implementation-plan.md`
  presence (SKILL.md L137-212); "Neither file exists — fresh start" @ L203-212 is
  the branch a plan-less `minimal` would wrongly hit. D10 fixes Step 1c to read
  the ledger. workflow.md step 5 L275-276 re-derives the active track from the
  `## Checklist` — which `minimal` lacks.
- **Verdict**: UNVALIDATED for the `determine_state` side.
- **Detail**: D10 covers the `create-plan` Step 1c side (ledger present + tier
  line readable; `plan/track-1.md` as secondary signal). It does NOT state the
  parallel rule for `determine_state` in the script, whose active-track
  re-derivation (workflow.md L275-276) and Phase-D detection (L314-315) read the
  Checklist. For `minimal` the ledger must carry the active track and a
  phase-4/done signal, since there is no Checklist or Final-Artifacts checkbox to
  walk. This is the same gap R1 names from the script side; D10 fixes Step 1c but
  leaves the script-side re-derivation rule for `minimal` unstated.

#### Assumption: backward compatibility for in-flight lite/full plans holds
- **Track claim**: `## Plan of Work` invariant — "existing in-flight `lite`/`full`
  plans that still carry a Checklist must resume without regression — the
  two-level lookup keeps the track-file sub-state walk unchanged." Validation:
  "An existing `lite`/`full` plan with a Checklist resumes unchanged."
- **Evidence search**: read the State A/C/D/Done tests @
  `test_workflow_startup_precheck.py:1931-2412` and the State C sub-state tests
  @ L2417+.
- **Code evidence**: the current tests pin the full plan-driven walk (Checklist
  for A/C, Final Artifacts for D/Done, `## Progress` + roster for the C
  sub-states). They are the regression net for `lite`/`full`.
- **Verdict**: VALIDATED-CONDITIONAL.
- **Detail**: backward compat is achievable IF the rewrite retains the
  plan-checkbox walk as the `lite`/`full` branch and the existing State-test
  battery is preserved. The risk is the stub-test rework (R5) deleting or
  inverting coverage. The invariant is sound; its safety depends on keeping the
  existing tests green, which the track should state explicitly.

### Testability and coverage

#### Testability: the new determine_state ledger path
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: the path needs fixtures for (a) a ledger present
  with a phase/track tail and no plan (the `minimal` resume), (b) a ledger plus a
  `lite`/`full` plan with a Checklist (backward compat), (c) last-value-wins over
  a re-appended tier, and (d) a torn-append leaving the prior tail. The state
  machine has five top-level outcomes (0/A/C/D/Done) plus the C sub-states.
- **Existing test infrastructure**: `GitFixture` @
  `test_workflow_startup_precheck.py:129` with `plan_artifact()` @ L346,
  `commit_plan_artifact()` @ ~L386, `handoff()` @ ~L371, and `run_precheck()`
  shelling out to the script @ L99. State tests @ L1931-2412 are the template;
  the fixture needs only a small `phase_ledger()` writer analogous to
  `plan_artifact()`.
- **Feasibility**: ACHIEVABLE.
- **Detail**: the harness is mature and the additions are mechanical. No
  isolation problem — the script is pure stdin-free stdout JSON. The only caveat
  is R5: the staged tests must point `SCRIPT_PATH` at the STAGED script, or they
  test the unchanged live one and pass vacuously.

#### Testability: the atomic --append-ledger subcommand and torn-append case
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: the atomic append (temp+rename) is straightforward
  to cover (append once, read tail; append twice, read latest). The TORN-append
  case is the hard one — a real torn write mid-rename is not directly reproducible
  in a deterministic unit test.
- **Existing test infrastructure**: the `no_drift_normalization` guard tests @
  L1432+ already exercise temp-file behavior (orphan `.tmp`, guard-2 porcelain),
  so a precedent for asserting on temp-file state exists.
- **Feasibility**: ACHIEVABLE (with a substitution).
- **Detail**: the torn-append invariant ("prior tail intact") is testable as a
  proxy: write a known tail, then simulate an interrupted append by leaving a
  stale `phase-ledger.md.tmp` (or a partially-written temp) on disk WITHOUT the
  rename, and assert `determine_state` resolves the PRIOR state and ignores the
  orphan temp. That covers the recovery contract without needing a real crash.
  The track should name this proxy in decomposition so the Validation line "An
  interrupted (torn) append leaves the prior ledger tail intact" maps to a
  concrete test.

#### Testability: rework of test_workflow_startup_precheck_stub.py
- **Coverage target**: 85% line / 70% branch (the stub file is a behavior pin,
  not a coverage target itself, but the reworked assertions must be meaningful).
- **Difficulty assessment**: the file's entire stated premise is inverted by this
  track. Its docstring (L1-50) asserts the `minimal` tier works "without any
  script change" and that the file "must stay byte-identical (no script/test
  edits is the load-bearing invariant the stub work rests on)." This track DOES
  change the script (drops the plan parse, adds the ledger read) and DOES drop the
  stub plan, so every premise the file documents is now false.
- **Existing test infrastructure**: the file has its own local `run_precheck` @
  L70 and a stub-plan synthesizer keyed on the `create-plan/SKILL.md` stub
  template — which is itself being deleted (Plan of Work step 7).
- **Feasibility**: ACHIEVABLE.
- **Detail**: the rework is a near-rewrite, not an edit: the file must drop the
  "no script change" premise and the stub-plan synthesizer (the template it
  mirrors is gone) and instead assert the no-plan ledger resume (ledger present,
  no `implementation-plan.md`, resumes to the recorded state). The risk is that
  a partial edit leaves stale docstring claims contradicting the new assertions —
  a prose-coherence hazard the house style flags. Decomposition should treat this
  as a full rewrite of the file's premise, not a patch.

## Findings

### R1 [should-fix]
**Certificate**: Exposure — determine_state resume state machine (the
minimal-no-plan path); Assumption — minimal resume routing has a non-Checklist
active-track signal.
**Location**: `track-1.md` Plan of Work step 1 and step 6; the rewrite target
`workflow-startup-precheck.sh:determine_state` L1439-1560; the contract in
`workflow.md` Startup Protocol step 5 L272-320.
**Issue**: The track names the right primitive (ledger tail, two-level read) but
does not specify the three decisions an implementer must make to land
`determine_state` correctly: (a) how a `minimal` resume re-derives the **active
track** without a `## Checklist` (workflow.md L275-276 reads it from the
Checklist); (b) how Phase **D vs Done** is distinguished for `minimal` without a
`## Final Artifacts` checkbox (L314-315 reads it there); and (c) whether the
existing plan-checkbox walk is **retained** as the `lite`/`full` branch or
replaced wholesale. Likelihood of a wrong split: MEDIUM — the obvious literal
read of "read the ledger tail … rather than the plan checkboxes" (step 6) is to
delete the plan walk, which would regress `lite`/`full` resume (the very
backward-compat invariant the plan asserts). Impact: HIGH — a mis-resume strands
every future session of the affected tier at the wrong phase, and the bug is
silent (a parsed JSON blob is the only license to dispatch, per workflow.md
L186-187).
**Proposed fix**: In the Track Pre-Flight amendment (or a decomposition note),
pin three things: (1) `determine_state` keeps the plan-checkbox walk as the
`lite`/`full` branch and takes the ledger branch only when
`implementation-plan.md` is absent (or, cleaner, always reads the ledger for the
top-level phase + active track and uses the plan walk only as the `lite`/`full`
sub-state corroboration); (2) the ledger must carry the active-track field so
`minimal` re-derivation needs no Checklist; (3) the ledger must carry a
phase=4/done signal so `minimal` D-vs-Done needs no Final-Artifacts checkbox.
Add a Validation line for each. Also align `determine_state`'s `minimal` routing
rule with D10's Step-1c rule (D10 covers Step 1c but not the script side).

### R2 [should-fix]
**Certificate**: Assumption — the existing interrupted-write reconciliation
covers a torn ledger append.
**Location**: `track-1.md` D3 Risks/Caveats L72-74; cross-ref `design.md`
L189-191; the only atomic-write precedent at
`workflow-startup-precheck.sh:407-408`.
**Issue**: D3 claims "the atomic temp-file-plus-rename append plus the existing
interrupted-write reconciliation cover it." No ledger reconciliation routine
exists. The script's only "interrupted-write" handling is the roster-vs-Progress
`section-discrepancy` machinery (L1266, L1364), a different mechanism on a
different file. The actual sound mitigation is the atomic temp+rename alone
(precedented at L407-408): a torn write leaves the prior tail intact and
last-value-wins reads recover the prior state. Likelihood an implementer is
misled: MEDIUM — they may search for or assume a reconciliation step that is not
there, or under-test the torn-append case believing existing code handles it.
Impact: LOW-MEDIUM — at worst a missed test or a confused implementation note;
the underlying durability story is fine.
**Proposed fix**: Reword D3 to credit only the real mechanism: "a torn append
leaves the prior tail intact because the append is atomic (temp-file +
`mv`, the same pattern `no_drift_normalization` uses at L407-408); reads take the
last complete value, so a half-written line in an orphaned `.tmp` is never read."
Drop the "existing interrupted-write reconciliation" phrase, or qualify it as an
analogy to the `## Progress` recovery, not a reused routine. Pair with the
torn-append proxy test (see R2 testability cert).

### R3 [should-fix]
**Certificate**: Assumption — the ledger event grammar is a defined contract
Track 2 can consume.
**Location**: `track-1.md` Plan of Work step 1 ("decide and pin the event
vocabulary and field grammar"); Contracts-published L298-305; Inter-track
dependency L307-309.
**Issue**: The ledger event grammar (line shape, key names, value vocabulary) is
the published inter-track contract Track 2's ~9 re-pointed readers and the
`determine_state` greps both consume, yet it is "decide and pin" work deferred to
implementation with no example line or key spelling on disk. Likelihood of
producer/consumer drift: MEDIUM — plan D4 itself names "a missed reader silently
reads a stale or absent fact" as the risk; an improvised grammar makes that more
likely because Track 2 cannot be planned against a fixed shape. Impact: MEDIUM —
a grammar mismatch between `--append-ledger` (producer) and a Track-2 reader
surfaces as a silent wrong-state resume, the same failure class as R1.
**Proposed fix**: Before decomposition closes, pin a concrete example ledger line
and the key set in the track file (a one-line literal, e.g. the fields D6 lists:
ISO timestamp, `[ctx=…]`, `phase=`, `track=`, `tier=`, `categories=`, `mode=`,
`paused`), and add a test that asserts `--append-ledger` emits exactly that shape
and `determine_state` reads it back. Track 1 owns both ends, so this is
in-scope; fixing the contract now de-risks Track 2's planning.

### R4 [suggestion]
**Certificate**: Assumption — §1.6(f) exclusion is the only drift-walk change;
Exposure — drift walk and the 1.6(f) ledger exclusion.
**Location**: `track-1.md` Plan of Work step 1 phrase "Add the ledger to the
drift logic as an unstamped artifact" and step 3 "§1.6(f) ledger exclusion";
`conventions.md` §1.6(f) L754-768.
**Issue**: The design (D13, exclusion) is correct and requires ZERO edits to the
three drift-walk globs (`workflow-startup-precheck.sh:488,689,391`) and the
conformance fixture — §1.6(f) L760-768 explicitly warns that growing the
*stamped* set would force exactly those edits, which S1 staging forbids on this
branch. The only risk is wording: "add the ledger to the drift logic" reads as
"add it to the walk," the S1-forbidden direction. Likelihood: LOW. Impact: LOW
(caught at review if it happened, and §1.6(f) prose is the guardrail).
**Proposed fix**: Reword step 1 to "record the ledger in the §1.6(f) exclusion
list so the drift walk does not enumerate it (no walk-glob or conformance-fixture
edit — those are S1-forbidden on this branch per §1.6(f))." Makes the zero-edit
intent unmissable.

### R5 [suggestion]
**Certificate**: Testability — rework of test_workflow_startup_precheck_stub.py;
Exposure — live-machinery wiring of the new script.
**Location**: `track-1.md` Plan of Work step 2; the whole-file premise of
`test_workflow_startup_precheck_stub.py` (docstring L1-50, local `run_precheck`
L70, `SCRIPT_PATH` L99).
**Issue**: Two adjacent test-hygiene risks. (1) The stub file's entire documented
premise — "no script change," "must stay byte-identical," stub-plan synthesizer
mirroring a `create-plan` template this track deletes — is inverted by Track 1. A
partial edit leaves stale docstring claims contradicting the new assertions (a
prose-coherence hazard). (2) Because the edits are staged (I6), the test runners
point `SCRIPT_PATH` at the live `.claude/scripts/...` path; a staged test that is
not re-pointed at the STAGED script will silently exercise the unchanged live
script and pass vacuously. Likelihood: MEDIUM for the stale-docstring drift, LOW
for the vacuous-pass (but high-cost if it slips — green tests that test nothing).
Impact: MEDIUM — false confidence in the resume rewrite.
**Proposed fix**: Treat the stub file as a full rewrite of its premise, not a
patch: drop the "no script change" docstring and the deleted-template
synthesizer, and rewrite it to assert the no-plan ledger resume (ledger present,
no `implementation-plan.md`, resumes to the recorded state). In decomposition,
add an explicit step note that the staged tests must invoke the staged script
path (or run under a fixture that copies the staged script into place), so they
exercise the new `determine_state` and cannot pass against the unchanged live
binary.
