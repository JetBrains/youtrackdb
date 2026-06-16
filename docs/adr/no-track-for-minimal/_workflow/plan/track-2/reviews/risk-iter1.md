<!--MANIFEST
role: reviewer-risk
phase: 3A
track: track-2
iteration: 1
verdict: CHANGES_REQUESTED
findings: 7
blockers: 2
evidence_base: 9 certificates — 2 exposure, 5 assumption, 2 testability; the load-bearing work is the independent reader inventory (grep over live .claude/workflow|skills for plan ## Plan Review / ## Final Artifacts / §1.7-marker / **PAUSED consumers) cross-checked against Track 2's D4 inventory + 16-file in-scope list. Workflow-prose track: prose-lens (path/§-anchor resolution), no Java symbol audit. Staged-read precedence applied — Track 1 staged docs + precheck.sh read from staged-workflow/; the 16 consumer files read live.
-->

## Index

| id | sev | anchor | loc | cert | basis |
|---|---|---|---|---|---|
| R1 | blocker | `### R1 ` | reader inventory (D4 / Plan-of-Work 1,5,6) | Assumption: reader inventory exhaustive | 5 out-of-scope consumers of removed plan sections, 2 load-bearing |
| R2 | blocker | `### R2 ` | Plan `### Constraints` + Plan-of-Work 2; D4 | Assumption: ledger fallback covers marker read | in-flight lite/full plan has marker but no `s17`; no marker-read fallback |
| R3 | should-fix | `### R3 ` | Plan-of-Work 3; D8 | Exposure: PAUSED recovery grep | ledger `paused` is a bare token; cannot carry `**PAUSED ` prefix |
| R4 | should-fix | `### R4 ` | track invariant line 205-208; D4 | Assumption: stable-prefix match unchanged | bare `s17` token is not a sentence-prefix match; mechanism changes |
| R5 | should-fix | `### R5 ` | conventions.md §1.7(c) (out of scope) | Assumption: marker read fully re-pointed | §1.7(c) still says read plan `### Constraints`; Track-1 file |
| R6 | should-fix | `### R6 ` | Plan-of-Work 6; workflow.md | Assumption: workflow.md re-points pinned | 2 flags but ≥3 `## Plan Review` spots (lines 310/417/768) |
| R7 | suggestion | `### R7 ` | Plan-of-Work 4,5; D11/D5 | Testability: escalation + completion re-points | step-6 reset + deferred-write reconciliation unnamed in steps |

## Evidence base

#### Exposure: PAUSED recovery grep over the ledger (D8)
- **Track claim**: Plan-of-Work step 3 routes the mid-phase-handoff Phase-2/State-0 and Phase-4 secondary `**PAUSED` markers to a ledger `paused` event, "keeping the greppable `**PAUSED` prefix (or extending the recovery grep to the ledger)."
- **Critical path trace**:
  1. Resume recovery: `grep -rn '^\*\*PAUSED ' docs/adr/<dir-name>/_workflow/` @ `mid-phase-handoff.md:173` — anchors on a line *starting* with literal `**PAUSED ` (two asterisks, "PAUSED", trailing space).
  2. New write site: `--append-ledger --paused <event>` @ staged `workflow-startup-precheck.sh:169-170`; the ledger line is `[<ISO>] [ctx=<level>] … paused=<v>` @ staged precheck format header `:51`.
  3. `paused` is a **bare metacharacter-free token** @ staged precheck `:56,62,1529,1582` — a SPACE in any bare-token field is loud-rejected on append (`reject_bad_ledger_value "paused" … bare`).
- **Blast radius**: a paused handoff at State 0 or Phase 4 is invisible to the recovery grep, so a session that misses the `ls handoff-*.md` check loses the handoff pointer on resume — the exact loss D8's mitigation exists to prevent.
- **Existing safeguards**: `determine_state` reads the ledger `paused` event directly (machine-read, the "strictly stronger" path D8 cites), so primary resume detection still works; the grep is the secondary defense-in-depth path.
- **Residual risk**: MEDIUM — primary detection holds; the documented fallback is what breaks.

#### Exposure: D14 live-script-slip window (scripts touched only for enforcement/promotion)
- **Track claim**: Plan-of-Work step 2(i)/(ii) extends the implementer-rules §1.7(e) gate to refuse a live `.claude/scripts/**` edit and the create-final-design Phase-4 `git add` + divergence check to include `.claude/scripts`.
- **Critical path trace**:
  1. Live `create-final-design.md:450` divergence check scans `.claude/workflow .claude/skills .claude/agents` — not `.claude/scripts`.
  2. Live `create-final-design.md:456` `cp -r "$STAGED_DIR/.claude/." .claude/` copies staged scripts onto live.
  3. Live `create-final-design.md:457` `git add .claude/workflow .claude/skills .claude/agents` — scripts copied but never staged for commit, so they never reach develop.
- **Blast radius**: without step 2(ii) the promoted precheck script silently never lands on develop. Track 2's fix targets exactly the three lines above. Track 2 edits NO `.claude/scripts/**` file itself (its 16 in-scope files are all `.claude/workflow/**`, confirmed track-2.md:255-271), so it introduces no new live-script-slip; it closes the existing window.
- **Existing safeguards**: the develop-era §1.7 gate does not guard scripts (acknowledged in plan `### Constraints` line 44-46); Track 1 staged the script by manual copy-then-edit.
- **Residual risk**: LOW — Track 2's scope is correct and the fix is precisely located.

#### Assumption: the reader inventory is exhaustive (D4 caveat: "a missed reader silently reads a stale or absent fact")
- **Track claim**: D4 + `## Interfaces and Dependencies` enumerate every consumer of the plan §1.7 marker, tier line, `## Plan Review`, and `## Final Artifacts`; the 16-file in-scope list covers them.
- **Evidence search**: grep over live `.claude/workflow/**` and `.claude/skills/**` for `### Constraints` marker reads, `Change tier`/`tier line`, `## Plan Review`, `## Final Artifacts`, and `**PAUSED`; each hit classified against the 16-file list (tool: grep — prose track, no symbol audit; reference-accuracy caveat does not apply to a textual section-name scan).
- **Code evidence**:
  - Marker readers (FROM plan `### Constraints`): exactly `conventions.md` + `create-plan/SKILL.md` (Track 1) and the 10 Track-2 files. **Complete.**
  - Tier-line readers: `implementation-review`, `inline-replanning`, `consistency-review`, `create-final-design`, `track-review` — all in Track 2's list. **Complete.**
  - `## Plan Review` / `## Final Artifacts` state/write references OUTSIDE Track 2's scope: `skills/execute-tracks/SKILL.md:89` (end-session read), `skills/review-plan/SKILL.md:35,97` (state read + write), `workflow/structural-review.md:167` (write-target reference), `workflow/plan-slim-rendering.md:162` (render instruction), `workflow/workflow-drift-check.md:216` (Phase-4 state read). **Five missed.**
- **Verdict**: CONTRADICTED
- **Detail**: the marker and tier-line inventories are complete, but the `## Plan Review` / `## Final Artifacts` inventory misses five consumers, two of them load-bearing state machines (drift-check Phase-4 skip; execute-tracks end-session). See R1.

#### Assumption: `determine_state`'s ledger fallback covers the marker read for in-flight lite/full plans
- **Track claim**: plan `### Constraints` line 52-55 — the ledger replaces the checkbox parse "without regressing resume for existing in-flight `lite`/`full` plans"; `determine_state` stays a two-level lookup with a missing-ledger fallback to the old plan walk.
- **Evidence search**: read staged `workflow-startup-precheck.sh` `determine_state` (`:1809-1840`), `determine_state_from_ledger` (`:1755-1807`), and the §1.7(c) detection rule in staged `conventions.md:1050-1083`.
- **Code evidence**: `determine_state` emits `STATE_JSON = {phase, substate}` only (`:1769,1778,1791,1796,1833`) — it never emits `tier` or `s17`. The missing-ledger fallback (`:1816-1840`) produces the same `{phase, substate}` via the legacy plan-checkbox walk. The §1.7 marker readers do NOT consume `determine_state` output; they read the ledger `s17=` field directly (staged conventions §1.7(b) `:994-1007`).
- **Verdict**: CONTRADICTED
- **Detail**: the two-level fallback is the phase/track resume path only. The §1.7 marker-read path has no fallback — an in-flight `lite`/`full` plan with a plan `### Constraints` marker but no `phase-ledger.md` (so no `s17=` line) reads "no marker" from the ledger and drops into live-edit mode. See R2.

#### Assumption: the §1.7(b)/(k) stable-prefix match semantics are unchanged (only the location moves)
- **Track claim**: track-2.md:205-208 — "only the *location* the consumers read the marker from moves from the plan to the ledger… a workflow-modifying branch is still detected identically."
- **Evidence search**: read the develop-era match spec in live `implementer-rules.md:256-296` and the staged marker-home spec in `conventions.md:991-1037`.
- **Code evidence**: develop-era match = literal stable-prefix substring `This plan is workflow-modifying:` of a sentence in plan `### Constraints` (`implementer-rules.md:262-275`; staged `conventions.md:1017-1028`), deliberately tolerant so a growing prefix-list never deactivates the gate. The ledger home stores the mode as a bare token `s17=<token>` (staged precheck `:51,56`).
- **Verdict**: UNVALIDATED
- **Detail**: a bare-token equality/presence test against `s17` is a different matching mechanism than a sentence stable-prefix substring match. The invariant as worded is not literally achievable; "detected identically" must be restated as a defined ledger comparison. See R4.

#### Assumption: the marker read is fully re-pointed off the plan `### Constraints` scan
- **Track claim**: D4 + staged conventions §1.7(b) — "Track 2 re-points the consumers that read it… from the plan `### Constraints` scan onto the ledger `s17` value."
- **Evidence search**: read staged `conventions.md` §1.7(b) `:994-1048` and §1.7(c) `:1050-1083`.
- **Code evidence**: §1.7(b) `:994` declares the ledger the canonical home. §1.7(c) `:1056-1063` ("Constraints declaration drives the implementer enforcement gate. Per-spawn, the implementer reads `implementation-plan.md`'s `### Constraints` section and checks for the marker sentence") still procedurally specifies the plan read with no ledger-`s17` alternative. `conventions.md` is Track 1's file and is explicitly OUT of Track 2's scope (track-2.md:276-279).
- **Verdict**: CONTRADICTED
- **Detail**: the normative procedural spec (§1.7(c)) the re-pointed consumer files cite will keep describing the plan read while the consumers describe the ledger read — a split-brain spec Track 2 cannot fix within its own scope. See R5.

#### Assumption: the workflow.md re-points are pinned for the implementer (Plan-of-Work step 6)
- **Track claim**: step 6 / `## Context and Orientation` lines 149-155 — three workflow.md re-points tagged "Track 2 re-points this": the Phase-4 start/resume signal + track-completion-episode writer, the When-to-end-a-session State-0 bullet, the implementation-review loader note.
- **Evidence search**: grep staged `workflow.md` for "Track 2 re-points" and for plan `## Plan Review` / `## Final Artifacts` state references.
- **Code evidence**: staged `workflow.md` carries only 2 "Track 2 re-points the" flags (`:350,743`, both the Phase-4 pair). The When-to-end-a-session bullet (`:417`), the implementation-review loader note (`:768`), and a third State-0 routing reference (`:310`) all reference plan `## Plan Review` but carry NO inline flag.
- **Verdict**: UNVALIDATED
- **Detail**: the track prose names all three categories, but the implementer who greps the flag finds 2 hits for ≥3 spots — under-coverage risk. See R6.

#### Testability: the escalation (D11) and completion-episode (D5) re-points cover the full plan-anchored surface
- **Coverage target**: 85% line / 70% branch (prose track — read as "every named re-point is exercised by the Validation and Acceptance scenarios").
- **Difficulty assessment**: `inline-replanning.md:150-157` rewrites the plan tier line AND `:153` resets `## Plan Review`; `track-code-review.md:1310-1378` writes the episode to the plan Checklist and marks plan `[x]`, with deferred-write reconciliation keyed on plan-file `[x]` (`:1434-1440`). Plan-of-Work steps 4/5 name the tier-line→ledger write, plan/design materialization, and episode→track-file move, but do not name the `## Plan Review` reset re-point or the deferred-write reconciliation re-point.
- **Existing test infrastructure**: Validation and Acceptance (track-2.md:219-238) covers the escalation-materializes and episode-in-track-file scenarios; no scenario exercises the `## Plan Review` reset on upgrade or the `minimal` completion signal (which has no plan `[x]` to key on).
- **Feasibility**: ACHIEVABLE
- **Detail**: both files are in scope; the gap is naming, not reach. See R7.

#### Testability: reader re-point coverage is verifiable per consumer
- **Coverage target**: 85% line / 70% branch (prose — each re-pointed consumer demonstrably reads the ledger, not the plan).
- **Difficulty assessment**: each marker/tier/section re-point is a localized text edit verifiable by re-grepping the consumer for the old plan-section name after the edit.
- **Existing test infrastructure**: the staged precheck's test suite (`test_workflow_startup_precheck.py`) exercises the ledger format; consumer re-points are prose and verified by inspection + the gate-recheck prompts.
- **Feasibility**: ACHIEVABLE
- **Detail**: no infeasibility; the risk is completeness (R1/R6), not testability.

## Findings

### R1 [blocker]
**Certificate**: Assumption — "the reader inventory is exhaustive (D4)"
**Location**: D4 reader inventory (track-2.md:46-52) + `## Interfaces and Dependencies` 16-file list (track-2.md:255-271); Plan-of-Work steps 1, 5, 6.
**Issue**: D4's own caveat is "a missed reader silently reads a stale or absent fact." An independent grep of live `.claude/workflow/**` and `.claude/skills/**` for the removed plan sections finds **five consumers of `## Plan Review` / `## Final Artifacts` outside Track 2's 16-file scope**, two of them load-bearing state machines:
  - `workflow/workflow-drift-check.md:216` — the Phase-4 migration-skip (#2) reads `$PLAN_DIR/_workflow/implementation-plan.md` for "all tracks `[x]`/`[~]` **and** the `## Final Artifacts` entry `[>]`/`[x]`." Under D2 a `minimal` plan does not exist (missing-plan → falls through), and under D5/D7 `## Final Artifacts` is removed from the thinned `lite`/`full` plan too (staged `conventions.md:316`). The Phase-4 skip-condition can no longer be satisfied in any tier, so the drift gate may prompt a migration of a `_workflow/` subtree about to be deleted. **High impact** (mis-routes a Phase-4 gate), **high likelihood** (fires every Phase-4 run of a new-model plan).
  - `skills/execute-tracks/SKILL.md:89` — "State 0 → end session after `## Plan Review` is `[x]`." A resume/end-session signal on a removed plan checkbox. Track 2 step 6 fixes the *parallel copy in workflow.md*, but the SKILL's own copy is a separate file and is missed. **High impact** (session-boundary control), **high likelihood**.
  - `skills/review-plan/SKILL.md:35,97` — step 5 "overwrite the plan file's `## Plan Review` section with the audit summary." This is the **write side** of D7, which states "`/review-plan` re-runs append their verdict there [`plan-review.md`]." The track names `/review-plan` in D7 (track-2.md:63) yet omits `review-plan/SKILL.md` from its in-scope set. If Track 2 re-points `implementation-review.md` to write `plan-review.md` but leaves this SKILL writing the plan `## Plan Review`, the two contradict.
  - `workflow/structural-review.md:167` — the orchestration doc (distinct from the in-scope `prompts/structural-review.md`) asserts the durable trace is "the audit-summary entry in the plan file's `## Plan Review` section." Stale once D7 moves the audit to `plan-review.md`.
  - `workflow/plan-slim-rendering.md:162` — step 4 "Keep the `## Final Artifacts` section verbatim." A live render instruction (invoked from `step-implementation.md`, `track-code-review.md`, `execute-tracks/SKILL.md`) naming a section that no longer exists; its pre-Checklist content list (`:138-140`) also names plan Goals/Non-Goals that D5 relocates.
**Proposed fix**: Add all five files to Track 2's `## Interfaces and Dependencies` in-scope list, the D4 reader inventory, and the relevant Plan-of-Work step (drift-check + plan-slim under step 6's "no bullet declares a section gone while another routes on it" mandate; execute-tracks + review-plan SKILLs under steps 1/6; structural-review.md orchestration doc under step 1). For `workflow-drift-check.md` specifically, re-base the Phase-4 skip on the ledger `phase == "D"`/`"Done"` tail value (Track 1's published contract), matching how workflow.md's Startup Protocol already routes. Re-run the consistency reviewer's marker-inventory check after the additions.

### R2 [blocker]
**Certificate**: Assumption — "`determine_state`'s ledger fallback covers the marker read for in-flight lite/full plans"
**Location**: plan `### Constraints` backward-compat clause (implementation-plan.md:52-55); Plan-of-Work step 2; D4.
**Issue**: the plan claims the ledger migration does not regress resume for in-flight `lite`/`full` plans because `determine_state` falls back to the old plan walk when the ledger is missing. That fallback is the **phase/track resume path only** — `determine_state` emits `{phase, substate}` and never `s17`/`tier` (staged precheck `:1769-1840`). The re-pointed §1.7(c) implementer gate and §1.7(l) review prompts read the ledger `s17=` field directly (staged conventions §1.7(b) `:994-1007`). An existing in-flight `lite`/`full` branch created before this lands carries the plan `### Constraints` marker but has **no `phase-ledger.md`** and therefore no `s17=` line. A re-pointed reader that only consults the ledger sees no marker, concludes "not workflow-modifying," and routes every `.claude/**` write to **live paths** — a silent staging-bypass that corrupts the live workflow tree mid-branch. **High impact** (silent live-tree corruption), **likelihood depends on whether any pre-ledger workflow-modifying branch is still in flight at merge** — non-zero and unguarded.
**Proposed fix**: specify the re-pointed marker read as ledger-first with a plan-`### Constraints`-scan fallback (mirroring `determine_state`'s own two-level pattern): read `s17` from the ledger; if absent, fall back to the develop-era `### Constraints` stable-prefix scan. Add a Validation and Acceptance scenario for "in-flight plan with `### Constraints` marker and no ledger still detects workflow-modifying." This fallback belongs in the consumer re-points (Track 2) and should be cross-referenced in the §1.7(c) spec amendment (see R5).

### R3 [should-fix]
**Certificate**: Exposure — "PAUSED recovery grep over the ledger (D8)"
**Location**: Plan-of-Work step 3 (track-2.md:183-186); D8 Risks/Caveats (track-2.md:81-83).
**Issue**: D8 offers a disjunctive mitigation — "keeping the greppable `**PAUSED` prefix **or** extending the recovery grep to the ledger." The first arm is **infeasible**: the ledger `paused` field is a bare metacharacter-free token that loud-rejects a space on append (staged precheck `:56,1529,1582`), so it cannot hold the literal `**PAUSED ` (asterisks + "PAUSED" + trailing space) the recovery grep `^\*\*PAUSED ` anchors on (`mid-phase-handoff.md:173`). A ledger line starts with `[<ISO>]`, never `**PAUSED`. So the recovery grep silently never matches a ledger paused event, and the handoff-path linkage the old in-plan `**PAUSED Handoff: <path>` block carried has no equivalent in a single `paused=<token>` field.
**Proposed fix**: commit to the second arm explicitly — extend the recovery grep in `mid-phase-handoff.md` to scan the ledger for `paused=` events, and specify how the ledger paused event preserves (or reconstructs) the handoff-file pointer the recovery path needs (e.g., the paused token encodes the phase and the handoff file is found by the existing `ls handoff-*.md` glob, which the ledger event triggers re-checking). Remove the dead "keep the `**PAUSED` prefix" arm from D8.

### R4 [should-fix]
**Certificate**: Assumption — "the §1.7(b)/(k) stable-prefix match semantics are unchanged"
**Location**: track invariant (track-2.md:205-208); D4.
**Issue**: the invariant states "only the *location* moves… detected identically." The develop-era detection is a literal stable-prefix substring match of the sentence `This plan is workflow-modifying:` (live `implementer-rules.md:262-275`), deliberately tolerant of a growing path-prefix list. The ledger home stores the mode as a bare token `s17=<token>` (staged precheck `:51,56`). A token presence/equality test is a different mechanism; the invariant as worded is not literally achievable and leaves the new comparison undefined. Low impact (the consumers can be made correct), but the unstated comparison is a gap an implementer could resolve inconsistently across the ten readers.
**Proposed fix**: restate the invariant as the *outcome* ("a workflow-modifying branch is detected identically — true/false unchanged") and define the ledger comparison the consumers apply (presence of `s17` with the staging value vs absent). Note that the bare-token form drops the develop-era forward-compat property (prefix-list growth) but is unaffected by it, since the token carries no path list.

### R5 [should-fix]
**Certificate**: Assumption — "the marker read is fully re-pointed off the plan `### Constraints` scan"
**Location**: staged `conventions.md` §1.7(c) (`:1050-1083`) — a Track-1 file, out of Track 2's scope (track-2.md:276-279).
**Issue**: staged §1.7(b) (`:994`) declares the ledger the canonical marker home and says "Track 2 re-points the consumers that read it," but §1.7(c) (`:1056-1063`) still procedurally instructs "the implementer reads `implementation-plan.md`'s `### Constraints` section and checks for the marker sentence," with no ledger-`s17` alternative. §1.7(c) is the normative spec the re-pointed `implementer-rules.md` / `step-implementation.md` cite. Re-pointing the consumers while §1.7(c) keeps describing the plan read leaves the spec and its consumers contradicting each other — and conventions.md is out of Track 2's scope, so Track 2 cannot fix it.
**Proposed fix**: surface this as a coordination dependency. Either (a) expand Track 2's scope to amend conventions.md §1.7(c) to read "the implementer reads the ledger `s17` field (or, on a pre-ledger plan, the plan `### Constraints` scan — see R2)," or (b) record it as a Track-1 residual to fold in a follow-up §1.7(c) edit. Note it in the track's `## Surprises & Discoveries` so Phase B does not leave the spec stale.

### R6 [should-fix]
**Certificate**: Assumption — "the workflow.md re-points are pinned for the implementer"
**Location**: Plan-of-Work step 6 (track-2.md:193-203); `## Context and Orientation` (track-2.md:149-155).
**Issue**: the track names three workflow.md re-point categories, but staged `workflow.md` carries only **2** inline "Track 2 re-points the" flags (`:350,743`, both the Phase-4 pair). The When-to-end-a-session State-0 bullet (`:417`), the implementation-review loader note (`:768`), and a third State-0 routing reference to `## Plan Review` (`:310`) carry no inline flag. An implementer who locates the work by grepping the flag finds 2 hits for ≥3 distinct spots and under-covers.
**Proposed fix**: pin the workflow.md re-points in Plan-of-Work step 6 by line/anchor (`:310`, `:417`, `:768`, plus the two flagged Phase-4 spots `:350`/`:743`), not by the "Track 2 re-points this" flag count. State that the flag count (2) is fewer than the spot count, so the implementer must work the prose enumeration, not the flag grep.

### R7 [suggestion]
**Certificate**: Testability — "the escalation (D11) and completion-episode (D5) re-points cover the full plan-anchored surface"
**Location**: Plan-of-Work steps 4 (track-2.md:187-189) and 5 (track-2.md:190-192); D11; D5.
**Issue**: both `inline-replanning.md` and `track-code-review.md` are in scope, but two plan-anchored sub-behaviours are unnamed in the steps and have no Validation and Acceptance scenario: (a) `inline-replanning.md:153` step-6 "resets `## Plan Review`" on a tier upgrade — under D7 review state moves to the ledger / `plan-review.md`, so the reset target moves too; (b) `track-code-review.md:1434-1440` deferred-write reconciliation keys the "approved vs not" resume signal on plan-file `[x]`, which a `minimal` completion (no plan) cannot carry — the completion signal must move to the ledger `phase=Done`. Low probability of being missed (the files are in scope) but worth pinning so the steps cover the full re-point.
**Proposed fix**: add to step 4 "re-point the `## Plan Review` reset to the ledger review-state / `plan-review.md`," and to step 5 "re-point the deferred-write reconciliation's resume signal from plan-file `[x]` to the ledger `phase` tail for the `minimal` (no-plan) case." Add matching Validation and Acceptance lines.
