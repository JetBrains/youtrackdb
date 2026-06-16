<!--
MANIFEST
role: reviewer-adversarial
phase: 3A
track: "Track 2: Rewire the runtime consumers onto the ledger"
iteration: 1
verdict: changes-requested
findings: 6
blockers: 1
should_fix: 3
suggestions: 2
evidence_base:
  challenges: 3
  violation_scenarios: 2
  assumption_tests: 3
tooling: "grep + Read over staged (_workflow/staged-workflow/.claude/**) then live; workflow-machinery prose lens — no PSI/findClass (no Java in scope)"
index:
  - id: A1
    sev: blocker
    anchor: "### A1 "
    loc: ".claude/workflow/plan-slim-rendering.md:162 (live; not staged, not in Track 2's 16-file scope)"
    cert: "Violation scenario: every consumer of a removed plan section is re-pointed"
    basis: scope
  - id: A2
    sev: should-fix
    anchor: "### A2 "
    loc: "staged workflow-startup-precheck.sh determine_state_from_ledger:1755-1807 vs plan D8"
    cert: "Assumption test: D8 — the ledger `paused` event is machine-read by determine_state on resume"
    basis: cross-track-episode
  - id: A3
    sev: should-fix
    anchor: "### A3 "
    loc: "staged precheck reject_bad_ledger_value:1582 (paused = bare token) vs mid-phase-handoff.md:167 PAUSED line"
    cert: "Violation scenario: D8 pause recovery — the greppable **PAUSED line survives the move to the ledger"
    basis: invariant
  - id: A4
    sev: should-fix
    anchor: "### A4 "
    loc: "track-2.md Plan-of-Work step 2 vs staged conventions §1.7(b):1004-1008 (in-flight lite/full fallback)"
    cert: "Assumption test: re-pointing the §1.7(c)/(l) marker read to the ledger preserves in-flight lite/full resume"
    basis: invariant
  - id: A5
    sev: suggestion
    anchor: "### A5 "
    loc: "track-2.md ## Context and Orientation:149-155 + Plan-of-Work step 6:193-203"
    cert: "Challenge: D-none — the track's count of the workflow.md residue Track 1 left"
    basis: scope
  - id: A6
    sev: suggestion
    anchor: "### A6 "
    loc: "track-2.md D11 / inline-replanning.md:150-162 (tier-line rewrite assumes a plan exists)"
    cert: "Challenge: D11 — escalation as the writer side; minimal has no plan to rewrite the tier line in"
    basis: cross-track-episode
-->

# Track 2 adversarial review — iteration 1

Verdict: changes-requested. One blocker (A1: a Phase-3B/3C consumer of the
removed `## Final Artifacts` section sits outside the track's 16-file scope and
is named in no Decision Record). Three should-fix (A2/A3 expose that D8's
"machine-read pause event" mechanism is not realized by the staged Track 1
primitive on either axis — neither `determine_state` reading `paused` nor a
ledger line carrying the greppable `**PAUSED` recovery prefix; A4: the in-flight
`lite`/`full` fallback the staged conventions promise is not in the track's
re-point instruction). Two suggestions (A5: a self-count drift; A6: a D11
ordering nicety). The track's core scope and 6-step plan are otherwise sound and
its D14/D7 cross-track claims hold against the staged files.

This is a workflow-prose track (16 `.claude/workflow/**` files); the
workflow-machinery prose lens applies — references verified as workflow
paths/§-anchors via grep + Read over the staged copy then live, no PSI.

## Findings

### A1 [blocker]
**Certificate**: Violation scenario — every consumer of a removed plan section is re-pointed.
**Target**: Decision D5/D7 (Old plan sections disposed; `## Final Artifacts` and `## Plan Review` removed from the thinned plan) + the plan invariant "the plan holds no fact a track does not already own."
**Challenge**: `plan-slim-rendering.md` is a live Phase-3B/3C consumer of the removed `## Final Artifacts` section, and it is in neither Track 2's 16-file in-scope list nor any Decision Record's reader inventory. D4's risk note makes the reader inventory load-bearing ("a missed reader silently reads a stale or absent fact, so the reader inventory must be exhaustive") and then enumerates the readers — `plan-slim-rendering.md` is not among them. The staged conventions §1.2 confirms the section is gone (`docs/adr/.../staged-workflow/.claude/workflow/conventions.md:298,316` — "`## Plan Review`, or `## Final Artifacts` (D5/D7)" … "`## Final Artifacts` is removed"). After Track 2 lands, `plan-slim-rendering.md:162` still instructs the orchestrator: **"Keep the `## Final Artifacts` section verbatim."** That is a render step pointed at a section that no longer exists in the thinned plan.
**Evidence**:
- `.claude/workflow/plan-slim-rendering.md:162` (live): `4. **Keep the `## Final Artifacts` section verbatim.**` — the plan-collapse rendering rule run at every track-completion (Phase 3B/3C).
- `ls docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/plan-slim-rendering.md` → No such file: Track 1 did NOT stage it, so it is untouched develop-state and Track 2 owns any edit.
- `grep -niE 'plan-slim-rendering' track-2.md implementation-plan.md` → empty: the file appears in no track section, no D4 reader inventory, no D5 disposal list.
- Construction: a `full`/`lite` branch reaches Phase C, the orchestrator runs the plan-slim collapse, follows step 4, and tries to "keep verbatim" a `## Final Artifacts` heading the staged conventions removed — a contradiction between the rendering rule (keep it) and the plan shape (it's gone). The orchestrator either renders a stale section back in or hits an instruction with no referent.
- Feasibility: CONSTRUCTIBLE — fires on the first multi-track `lite`/`full` plan authored after merge, on every track collapse.
**Proposed fix**: Add `.claude/workflow/plan-slim-rendering.md` to Track 2's `## Interfaces and Dependencies` in-scope list and to the D5/D4 reader inventory, and add a Plan-of-Work edit: drop the step-4 "keep `## Final Artifacts` verbatim" rule (the section is gone) and re-point its Checklist-rendering prose to the ledger-mirrored checkbox model. The 17th file keeps the track inside the soft footprint bound. Alternatively, if `plan-slim-rendering.md` is judged Track-1 format territory, ESCALATE to amend Track 1's scope — but the file is unstaged, so under the current split it is Track 2's to fix.

### A2 [should-fix]
**Certificate**: Assumption test — D8 claims the ledger `paused` event "is machine-read by `determine_state` on resume, not just a human cue."
**Target**: Decision D8 (Pause boundaries recorded as ledger events) — the rationale's "strictly stronger" justification.
**Challenge**: The staged Track 1 primitive does not implement the machine-read D8 leans on. `determine_state_from_ledger` reads exactly two keys — `phase` (line 1766) and `track` (line 1785). It never calls `ledger_tail_value "paused"`. The `paused` field is validated on append (`reject_bad_ledger_value "paused" … bare`, line 1582) and written (line 1604), but no read path consumes it. So a `paused` ledger event changes resume behavior by nothing: `determine_state` resolves the same `{phase, substate}` whether or not a pause event was appended. D8's claimed advantage over the status-quo plan-anchored `**PAUSED` marker ("strictly stronger — machine-read, not just a human cue") is therefore not realized by what Track 1 shipped. The track depends on a contract its predecessor did not build.
**Evidence**:
- `staged-workflow/.claude/scripts/workflow-startup-precheck.sh:1766` reads `phase`, `:1785` reads `track`; `grep -niE 'paused' …` over the script shows `paused` only in the append/validate path (1582, 1604) and the header grammar — never in `determine_state_from_ledger` (1755-1807) or `ledger_tail_value` callers.
- Verdict: BREAKS — the assumption "the pause event is machine-read on resume" does not hold against the staged determine_state.
**Proposed fix**: Either (a) weaken D8's rationale to drop the "machine-read by determine_state" claim and keep the ledger pause event as a uniform, greppable record (still a real improvement over two plan-anchored markers), or (b) add a Plan-of-Work note that Track 2's D8 work includes teaching `determine_state` (or the Phase-4 resume row in `workflow.md:347-348`, which already says "on a resume (a Phase-4 pause event in the ledger)") to actually read the `paused` key — and confirm that read exists before relying on it. Today `workflow.md:347` asserts a resume distinction ("on a resume (a Phase-4 pause event in the ledger)") that no code reads; flag whether that distinction is meant to be machine-driven or human-driven.

### A3 [should-fix]
**Certificate**: Violation scenario — D8 pause recovery: the greppable `**PAUSED` line survives the move to the ledger.
**Target**: Invariant implied by D8's risk note: "the recovery grep (`grep -rn '^\*\*PAUSED '`) must cover the ledger, or the ledger paused event must keep the greppable `**PAUSED` prefix."
**Challenge**: Neither escape in D8's own risk note works against the staged grammar. The recovery grep is `grep -rn '^\*\*PAUSED ' docs/adr/<dir>/_workflow/` (mid-phase-handoff.md:173) — it keys on a line that STARTS with `**PAUSED `. The ledger line shape is `[<ISO>] [ctx=…] phase=… … paused=<v>` (precheck header:51), so a ledger line can never start with `**PAUSED`; the grep over `_workflow/` will scan the ledger file but match nothing in it. The second escape — "the ledger paused event keeps the greppable `**PAUSED` prefix" — is impossible: the `paused` value is a BARE metacharacter-free token, and a space in it is rejected with exit 3 (`reject_bad_ledger_value "paused" … bare`, line 1582; header:62 "a space in a bare-token field … is rejected"). The marker the grep recovers is `**PAUSED <YYYY-MM-DD> at <phase-state> pending <decision-or-action>` (mid-phase-handoff.md:167) — multi-word, with a sub-bullet. It cannot be encoded as `paused=<single-bare-token>`.
**Evidence**:
- `.claude/workflow/mid-phase-handoff.md:167` marker format (multi-word + sub-bullet); `:173` recovery grep `^\*\*PAUSED `.
- staged precheck header `:51` line grammar (`paused=<v>` mid-line), `:62` bare-field space-rejection, `:1582` `paused` validated as `bare`.
- Construction: orchestrator pauses at State 0 (today: writes `**PAUSED …` beneath `## Plan Review` in the plan). Under D8 it appends `… paused=<token>` to the ledger and removes the plan marker (the host section is gone anyway). A later resume-protocol regression misses the `ls handoff-*.md` check; the operator runs the documented `grep -rn '^\*\*PAUSED ' _workflow/` recovery — it finds nothing, because the ledger line does not start with `**PAUSED` and the bare token could not hold the human line. The defense-in-depth pointer is silently lost.
- Feasibility: CONSTRUCTIBLE — the grep is the documented last-resort recovery and it now returns empty for State-0/Phase-4 pauses.
**Proposed fix**: Decide the encoding in the Plan-of-Work step 3 explicitly. Option 1: keep a `**PAUSED …` line as a literal comment line appended to the ledger ALONGSIDE the structured event line (the ledger is append-only Markdown; a `**PAUSED` comment line is harmless to last-value-wins key reads and the recovery grep finds it). Option 2: extend the recovery grep itself to also match the ledger's structured `paused=` token (`grep -rnE '\*\*PAUSED |paused=' …`) and update mid-phase-handoff.md:171-174 accordingly. The track currently states the constraint but defers the choice; pick one before implementation so the recovery path is testable in `## Validation and Acceptance` (which already asserts "the recovery grep still finds it").

### A4 [should-fix]
**Certificate**: Assumption test — re-pointing the §1.7(c)/(l) marker read from the plan `### Constraints` to the ledger `s17` field preserves resume/detection for an in-flight `lite`/`full` workflow-modifying branch that has the plan marker but no `phase-ledger.md`.
**Target**: Invariant in the track's Plan-of-Work tail ("only the *location* the consumers read the marker from moves … so a workflow-modifying branch is still detected identically") + the plan's backward-compatibility constraint.
**Challenge**: The staged conventions §1.7(b) promises the in-flight fallback explicitly — "a plan whose `### Constraints` still names it is recognized identically (the stable-prefix property)" and "the develop-era in-plan form a plan carries until those readers are re-pointed" (staged conventions:1004-1008). But Track 2's Plan-of-Work step 2 phrases the change one-directionally: "Re-point the marker read … from the plan's `### Constraints` to the ledger." A literal one-directional move — read `s17`, stop — breaks an in-flight `full`/`lite` branch created under develop: it has the `### Constraints` marker and NO ledger (the ledger is this branch's own new artifact). Its implementer §1.7(c) gate would read an absent `s17`, conclude "not workflow-modifying," and route workflow edits to LIVE paths — exactly the failure §1.7 exists to prevent. The track's own backward-compat constraint and the staged conventions both require the reader to be ledger-OR-`### Constraints`, not ledger-only.
**Evidence**:
- staged conventions §1.7(b):1004-1008 — the dual-recognition guarantee for develop-era in-plan markers.
- `.claude/workflow/implementer-rules.md:262-275` (live) — the §1.7(c) gate today reads the plan `### Constraints` and matches the stable prefix; this is the reader Track 2 re-points.
- track-2.md Plan-of-Work step 2:159-165 — phrased as a move "from … to the ledger," with no "fall back to `### Constraints` when no ledger" clause.
- Verdict: FRAGILE — the intent is recoverable from the staged conventions, but the track's re-point instruction as written would drop the fallback. An implementer following step 2 literally produces a ledger-only read.
**Proposed fix**: Amend Plan-of-Work step 2 (and the D4 risk note) to state the read as "prefer the ledger `s17`; fall back to the plan `### Constraints` stable-prefix match when no ledger exists," mirroring how `determine_state` itself falls back to the plan-checkbox walk for in-flight plans (staged precheck:1810-1818). Add a `## Validation and Acceptance` line: an in-flight `lite`/`full` branch with a `### Constraints` marker and no ledger is still detected workflow-modifying.

### A5 [suggestion]
**Certificate**: Challenge — the track's count of the `workflow.md` residue Track 1 left for step 6.
**Target**: Track text accuracy (Context and Orientation + Plan-of-Work step 6) — not a decision.
**Challenge**: The track says Track 1 "left three references … each marked 'Track 2 re-points this'" (Context and Orientation:149-155) and step 6 says "Re-point all three" (193-203). The staged `workflow.md` carries only TWO lines tagged "Track 2 re-points the" (lines 350 and 743, both the Phase-4 start/resume + completion-episode writer), plus TWO untagged stale `## Plan Review` references step 6 also names (the When-to-end-a-session State-0 bullet at :417 and the implementation-review loader note at :768). Track 1's own episode states it accurately: "left two 'Track 2 re-points this' notes … and deliberately skipped two consumer-layer plan-checkbox references." So the residue is four touch points (2 tagged + 2 untagged), not "three references each marked 'Track 2 re-points this'." The work is fully enumerated; only the count and the "each marked" claim are wrong, which could mislead the implementer into searching for a third tagged note that does not exist or stopping at three when there are four edits.
**Evidence**:
- `grep -noE 'Track 2 re-points [a-z]+' staged workflow.md` → exactly 2 hits (350, 743).
- staged workflow.md stale `## Plan Review` refs at :417 (When-to-end bullet) and :768 (loader note) — untagged.
- Track 1 episode (prior_episodes): "two 'Track 2 re-points this' notes … skipped two consumer-layer plan-checkbox references."
**Proposed fix**: Correct the track prose to "four touch points: two tagged 'Track 2 re-points the' (Phase-4 start/resume + completion-episode writer) and two untagged stale `## Plan Review` references (the When-to-end-a-session State-0 bullet at workflow.md:417, the implementation-review loader note at :768)." Decomposition can enumerate all four as step-6 sub-edits.

### A6 [suggestion]
**Certificate**: Challenge — D11 as the tier-line writer; the live `inline-replanning.md` rewrites the tier line IN `implementation-plan.md`, which `minimal` does not have.
**Target**: Decision D11 (Minimal→lite/full escalation materializes the dropped plan and design).
**Challenge**: D11 correctly identifies that a `minimal`→`lite`/`full` escalation must materialize `implementation-plan.md`. But the live `inline-replanning.md:150-162` rewrites the tier line as its FIRST upgrade artifact ("The first artifact an upgrade lands is the D18 tier line rewrite … the upgrade rewrites that line in `implementation-plan.md`") and `git add`s `implementation-plan.md` (:224). Under `minimal` there is no plan yet, so the ordering matters: the escalation must materialize `implementation-plan.md` BEFORE the tier-line write, and the tier value now lives in the ledger `s17`/tier field (D4), not the plan line. D11's rationale and risk note do not state the ordering or that the tier write is now a ledger append, not a plan-line edit — leaving the implementer to infer it. This survives (D11's intent is right and the materialize-then-write order is the only coherent reading), but the rationale is thin where the live file's assumption (plan always exists, tier lives in the plan line) directly contradicts the new model.
**Evidence**:
- `.claude/workflow/inline-replanning.md:150-162` (live) — tier line rewritten in `implementation-plan.md` as the first upgrade artifact; `:224` `git add … implementation-plan.md`.
- track-2.md D11 / plan D11:250-260 — names materializing the plan but not the ordering vs. the tier write, nor that the tier value moves to the ledger (D4).
- Survival test: YES (intent holds), WEAK rationale.
**Proposed fix**: Strengthen D11 (or Plan-of-Work step 4) with the ordering: on a `minimal` upgrade, (1) append the upgraded tier to the ledger, (2) materialize `implementation-plan.md` (and `design.md` for `full`), (3) then any plan-internal rewrite. Note explicitly that the develop-era "D18 tier line rewrite in the plan" becomes a ledger tier-field append under D4, so the implementer does not write a `**Change tier:**` line back into the freshly materialized plan.

## Evidence base

#### Challenge: Decision D-none — the track's count of the workflow.md residue (A5)
- **Chosen approach**: Track text says "three references, each marked 'Track 2 re-points this'"; step 6 says "re-point all three."
- **Best rejected alternative**: state the residue as Track 1's episode does — two tagged notes + two untagged skipped references = four touch points.
- **Counterargument trace**:
  1. `grep -noE 'Track 2 re-points [a-z]+'` over staged `workflow.md` returns exactly two hits (350, 743).
  2. The When-to-end State-0 bullet (:417) and loader note (:768) are stale `## Plan Review` refs that step 6 names but that carry no "Track 2 re-points this" tag.
  3. Track 1's episode: "two notes … skipped two consumer-layer references." So the residue is 2+2, not 3-each-tagged.
- **Codebase evidence**: staged workflow.md:350,743 (tagged), :417,:768 (untagged stale).
- **Survival test**: WEAK — the work is fully named, but the count/"each marked" claim is wrong.

#### Challenge: Decision D11 — escalation as writer; minimal has no plan to rewrite (A6)
- **Chosen approach**: D11 says the upgrade carrier writes the tier as a ledger event and materializes the plan/design.
- **Best rejected alternative**: spell out the order (ledger tier append → materialize plan → plan-internal edits) and that the develop-era in-plan tier-line rewrite becomes a ledger append.
- **Counterargument trace**:
  1. Live `inline-replanning.md:150-162` rewrites the tier line in `implementation-plan.md` as the first upgrade artifact and `git add`s the plan (:224).
  2. Under `minimal` the plan does not exist, and the tier value now lives in the ledger (D4), so writing a `**Change tier:**` line into the plan would re-introduce the duplicate D4 removed.
  3. D11 names "materialize the plan" but not the ordering or the ledger-vs-plan-line tier home.
- **Codebase evidence**: inline-replanning.md:150-162, :224; plan D4 (tier → ledger).
- **Survival test**: YES (intent right), WEAK (rationale silent on the contradiction with the live file's assumption).

#### Challenge: Decision D8 — "machine-read by determine_state" (used by A2)
- **Chosen approach**: pause boundaries recorded as ledger `paused` events, "machine-read by `determine_state` on resume, not just a human cue."
- **Best rejected alternative**: keep the ledger pause event as a uniform, greppable human record without claiming a machine-read advantage; or actually implement the `paused` read.
- **Counterargument trace**:
  1. `determine_state_from_ledger` (staged precheck:1755-1807) reads only `phase` (1766) and `track` (1785).
  2. `paused` is validated (1582) and written (1604) but never read; `ledger_tail_value "paused"` is never called.
  3. So appending a `paused` event changes resume behavior by nothing — the "strictly stronger, machine-read" claim is unrealized.
- **Codebase evidence**: staged precheck:1766, 1785 (the only ledger reads); 1582, 1604 (paused write/validate only).

#### Violation scenario: every consumer of a removed plan section is re-pointed (A1)
- **Invariant claim**: D5/D7 remove `## Final Artifacts` and `## Plan Review` from the thinned plan; the plan holds no fact a track does not already own, and every reader of a removed fact is re-pointed (D4's exhaustive-inventory risk note).
- **Violation construction**:
  1. Start state: a multi-track `lite`/`full` plan authored after this branch merges; `## Final Artifacts` is gone (staged conventions:298,316).
  2. Action sequence: orchestrator finishes a track (Phase 3C) and runs the plan-slim collapse per `plan-slim-rendering.md` (live, unstaged, out of Track 2 scope).
  3. Intermediate state: it reaches step 4 (`plan-slim-rendering.md:162`), "Keep the `## Final Artifacts` section verbatim."
  4. Violation point: `plan-slim-rendering.md:162` — the rendering rule references a section the plan no longer has; the file is in no D4/D5 reader inventory and no Track 2 in-scope list (`grep -niE 'plan-slim-rendering' track-2.md implementation-plan.md` → empty).
  5. Observable consequence: the orchestrator either renders a stale `## Final Artifacts` heading back into the plan or follows an instruction with no referent — a stale-fact read the migration was meant to eliminate.
- **Feasibility**: CONSTRUCTIBLE — fires on the first track collapse of any post-merge `lite`/`full` plan.

#### Violation scenario: the greppable **PAUSED recovery line survives the move to the ledger (A3)
- **Invariant claim**: D8's risk note — the recovery grep `^\*\*PAUSED ` must cover the ledger, OR the ledger event keeps the greppable `**PAUSED` prefix.
- **Violation construction**:
  1. Start state: orchestrator pauses at State 0; under D8 it appends `… paused=<token>` to the ledger and drops the plan-anchored marker (host section removed).
  2. Action sequence: a resume-protocol regression misses `ls handoff-*.md`; operator runs documented recovery `grep -rn '^\*\*PAUSED ' _workflow/` (mid-phase-handoff.md:173).
  3. Intermediate state: the ledger line is `[<ISO>] … paused=<token>` (header:51) — it does not start with `**PAUSED`; the bare `paused` token cannot hold the multi-word `**PAUSED <date> at <state> pending <action>` line (space-rejected, :62/:1582).
  4. Violation point: the recovery grep matches nothing in the ledger.
  5. Observable consequence: the defense-in-depth pause pointer is silently lost for State-0/Phase-4 pauses.
- **Feasibility**: CONSTRUCTIBLE — the grep is the documented last-resort recovery; `## Validation and Acceptance` already asserts it "still finds it," so the gap is testable and currently failing.

#### Assumption test: D8 paused-event machine-read (A2)
- **Claim**: a ledger `paused` event is machine-read by `determine_state` on resume.
- **Stress scenario**: a Phase-4 pause appends `paused=phase4` to the ledger; the next session runs `determine_state`.
- **Code evidence**: staged precheck `determine_state_from_ledger:1755-1807` reads `phase` and `track` only; no `ledger_tail_value "paused"` call anywhere.
- **Verdict**: BREAKS.

#### Assumption test: in-flight lite/full marker fallback (A4)
- **Claim**: re-pointing the §1.7 marker read to the ledger keeps a workflow-modifying branch "detected identically."
- **Stress scenario**: an in-flight `full` branch created under develop has the plan `### Constraints` marker and no `phase-ledger.md`; its implementer §1.7(c) gate runs after Track 2's re-point.
- **Code evidence**: staged conventions §1.7(b):1004-1008 promises dual recognition; track-2.md step 2:159-165 phrases a one-directional move with no fallback clause; live implementer-rules.md:262-275 is the reader being re-pointed.
- **Verdict**: FRAGILE — intent is recoverable from conventions, but the track's instruction as written drops the fallback.

#### Assumption test: D7 / D14 cross-track claims hold against the staged files (supports the no-blocker verdict on D7/D14)
- **Claim**: Track 1 published the contracts Track 2's D7 and D14 work consume.
- **Stress scenario**: verify the live create-final-design promotion gap D14(ii) names, and the `--append-ledger` grammar D7 relies on.
- **Code evidence**: live `create-final-design.md:450` divergence check and `:457` `git add` both omit `.claude/scripts` (D14(ii)'s gap is real and correctly scoped); `:456` `cp -r "$STAGED_DIR/.claude/."` does copy scripts, matching the track's "copied yet never committed" description. Staged precheck header:51-67 defines the `--append-ledger` grammar with `s17` as the §1.7 field and `categories` quoted, as the track assumes.
- **Verdict**: HOLDS — D7 and D14 are grounded; no finding raised against them.
