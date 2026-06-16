<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: ".claude/workflow/inline-replanning.md:151,162", anchor: "### T1 ", cert: I-INLINE-TIER, basis: "inline-replanning tier-line READ stays plan-anchored; D2 minimal upgrade has no plan to read the tier from; step 2/4 cover the writer not the read prose"}
  - {id: T2, sev: should-fix, loc: ".claude/workflow/mid-phase-handoff.md:167-174", anchor: "### T2 ", cert: E-PAUSED-GREP, basis: "both D8 recovery-grep escape hatches mechanically blocked: paused bare-token rejects spaces, mid-line paused= can't match ^**PAUSED, determine_state never reads paused"}
  - {id: T3, sev: suggestion, loc: "staged workflow.md:350-351,743-745", anchor: "### T3 ", cert: I-PHASE-D, basis: "phase==D routing already reads the ledger (Track 1); step 6's (a) work is deleting stale forward-pointers, not re-routing"}
  - {id: T4, sev: suggestion, loc: "staged conventions.md:1056-1063 (§1.7(c))", anchor: "### T4 ", cert: I-S17C-PROSE, basis: "§1.7(c) read-side prose still names plan ### Constraints read; that consumer is a Track-1-owned file out of Track 2 scope — cross-track seam"}
evidence_base: {section: "## Evidence base", certs: 14, matches: 9}
cert_index:
  - {id: P-FILES, verdict: CONFIRMED, anchor: "#### P-FILES "}
  - {id: P-LEDGER-GRAMMAR, verdict: CONFIRMED, anchor: "#### P-LEDGER-GRAMMAR "}
  - {id: P-PHASE-VOCAB, verdict: CONFIRMED, anchor: "#### P-PHASE-VOCAB "}
  - {id: P-S17, verdict: CONFIRMED, anchor: "#### P-S17 "}
  - {id: P-MARKER-READERS, verdict: CONFIRMED, anchor: "#### P-MARKER-READERS "}
  - {id: P-D14-GATE, verdict: CONFIRMED, anchor: "#### P-D14-GATE "}
  - {id: P-D14-PROMOTE, verdict: CONFIRMED, anchor: "#### P-D14-PROMOTE "}
  - {id: P-WF-REFS, verdict: CONFIRMED, anchor: "#### P-WF-REFS "}
  - {id: P-TIER-READERS, verdict: PARTIAL, anchor: "#### P-TIER-READERS "}
  - {id: P-IMPLREVIEW, verdict: CONFIRMED, anchor: "#### P-IMPLREVIEW "}
  - {id: I-INLINE-TIER, verdict: MISMATCHES, anchor: "#### I-INLINE-TIER "}
  - {id: E-PAUSED-GREP, verdict: WRONG, anchor: "#### E-PAUSED-GREP "}
  - {id: I-PHASE-D, verdict: PARTIAL, anchor: "#### I-PHASE-D "}
  - {id: I-S17C-PROSE, verdict: MISMATCHES, anchor: "#### I-S17C-PROSE "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: I-INLINE-TIER (Integration), P-TIER-READERS (Premise)
**Location**: Track 2 `## Plan of Work` steps 2 and 4 (D11); `.claude/workflow/inline-replanning.md:151,162`
**Issue**: `inline-replanning.md` is both a tier-line writer (D11, step 4) and a tier-line reader, but the track's re-point plan only addresses the writer/materialize half. Line 151 states "The Phase-2/3A/4 selectors all read the tier line in `implementation-plan.md`" and line 162 describes a `lite`→`full` upgrade writing "the new design seed alongside the tier-line rewrite." Step 2 enumerates the tier-line READS it re-points — `track-review` and `create-final-design` — and step 1 covers `consistency-review`/`structural-review`; `inline-replanning` is named only in step 4 (D11), which is scoped to the writer side ("writes the upgraded tier as a ledger event and materializes `implementation-plan.md`"). The descriptive tier-line READ prose at line 151 and the "tier-line rewrite" framing at 162 still assert a plan-anchored tier line. Under D2 a `minimal` branch has no `implementation-plan.md`, so once the other selectors read the ledger, this descriptive sentence is stale (it tells the reader the selectors read a file that no longer holds the tier, and a `minimal`→`full` ESCALATE rewrites a tier line in a file that does not yet exist at upgrade entry). The track is not wrong about the mechanism — D11 already says the upgrade materializes the plan — but no step instructs the implementer to update inline-replanning's tier-READ prose to the ledger, so the file can land internally inconsistent (writer re-pointed, descriptive read text not).
**Proposed fix**: In step 2, add `inline-replanning.md` to the tier-line-read re-point list (the line-151 selector-read sentence becomes "read the tier line from the ledger `tier` field"), and in step 4 (D11) state explicitly that the `lite`→`full` design-seed write and the tier write both target the ledger event plus the materialized artifacts, so line 162's "tier-line rewrite" framing is re-expressed as a ledger `tier` event that materializes (not rewrites) the plan's tier on upgrade. Decomposition should make the inline-replanning edit cover both the writer (step 4) and the descriptive read (step 2) in one consistent pass.

### T2 [should-fix]
**Certificate**: E-PAUSED-GREP (Edge case)
**Location**: Track 2 `## Plan of Work` step 3 + D8 risk note; `.claude/workflow/mid-phase-handoff.md:167-174`; staged `workflow-startup-precheck.sh:169-170,1582,1603` (the `paused` field is bare-token)
**Issue**: D8's risk note (track file line 82-83; plan line 218-220) names two escape hatches: "the recovery grep (`grep -rn '^\*\*PAUSED '`) must cover the ledger, **or** the ledger paused event must keep the greppable `**PAUSED` prefix." Both are mechanically blocked as Track 1 built the ledger:
1. **The `paused` field is a bare token that rejects spaces** (`reject_bad_ledger_value "paused" ... bare` at precheck line 1582; the bare check rejects `*" "*`). The develop marker format is `**PAUSED <YYYY-MM-DD> at <phase-state> pending <decision-or-action>**` (mid-phase-handoff.md:167) — it contains spaces, so it cannot be stored verbatim in `paused=<event>`. The "keep the greppable `**PAUSED` prefix" hatch is therefore unavailable for the full marker; at most a spaceless token survives.
2. **The recovery grep is line-start anchored**: `grep -rn '^\*\*PAUSED '` (mid-phase-handoff.md:173). A ledger line is `[<ISO>] [ctx=…] phase=… … paused=<event>` — the `paused=` token appears mid-line, never at column 0, so `^\*\*PAUSED` cannot match a ledger pause event even if the value were `**PAUSED`. The "extend the recovery grep to the ledger" hatch needs a *different* pattern (a `paused=` token match), which the track does not specify.

Separately, D8's rationale claims the ledger paused event is "machine-read by `determine_state` on resume." It is not: `determine_state_from_ledger` reads only `phase` and `track` via `ledger_tail_value` (precheck.sh:1766,1785); there is no `ledger_tail_value "paused"` call anywhere. The pause event would be written but never consumed by the resume state machine, weakening D8's "strictly stronger than a human cue" justification to "written but inert."
**Proposed fix**: Decomposition must pick one concrete resolution and record it: (a) keep the human-facing `**PAUSED ...**` marker in the handoff file / track `## Progress` (where line-start anchoring still works) and have the ledger `paused` event carry only a spaceless state token (e.g. `paused=state0` / `paused=phase4`), and extend the recovery grep with a second pattern that matches `paused=` on a ledger line; or (b) explicitly scope D8 down so the ledger `paused` event is a recorded fact only and the recovery grep continues to find the human marker in its existing host. Either way, correct the D8 rationale sentence "machine-read by `determine_state`" unless step 3 also adds a `paused` read to `determine_state` (out of Track 2 scope — that is precheck code Track 1 owns), so the truthful claim is that the resume protocol prose reads it, not the script.

### T3 [suggestion]
**Certificate**: I-PHASE-D (Integration)
**Location**: Track 2 `## Plan of Work` step 6 (the (a) half); staged `workflow.md:344-351` (`phase == "D"` row) and `workflow.md:739-745` (`## Final Artifacts` block)
**Issue**: Step 6 describes re-pointing "the Phase-4 start/resume signal and the track-completion-episode writer off the plan `## Final Artifacts` + track checkbox onto the ledger." But Track 1 already wired the routing to the ledger: the `phase == "D"` Startup-Protocol row (344-348) resolves Phase 4 from the ledger tail ("the phase comes from the ledger"), and the Final-Artifacts progress note (739-742) says "Phase-4 progress is tracked by the phase ledger." What remains on those two blocks is the **stale forward-pointer prose** — "Track 2 re-points the Phase-4 start/resume signal off the plan checkbox onto the ledger" (350-351) and "Track 2 re-points the Phase-4 start/resume signal and the track-completion-episode writer off the plan checkbox onto the ledger" (743-745). The routing is done; the sentences read as if it is pending. If decomposition treats step 6 (a) as "change the routing," the implementer will find nothing routing-shaped to change and may either no-op or introduce a redundant edit. The actual work for (a) is to delete the two self-referential "Track 2 re-points this" forward-pointers now that the re-point they announce is complete.
**Proposed fix**: Reword step 6 so its (a) sub-task is "remove the two stale `Track 2 re-points this` forward-pointers in `workflow.md` (the `phase == "D"` row and the `## Final Artifacts` block) now that Track 1 already routes Phase-4 resume on the ledger phase," and reserve the genuine routing changes for the (b)/(c) halves — the When-to-end-a-session State-0 bullet (417, keys on `## Plan Review` `[x]`) and the implementation-review loader note (768, keys on `## Plan Review` `[ ]`), which do still read removed plan sections.

### T4 [suggestion]
**Certificate**: I-S17C-PROSE (Integration)
**Location**: Track 2 `## Plan of Work` step 2 + `## Interfaces and Dependencies`; staged `conventions.md:1056-1063` (§1.7(c) "Detection rule")
**Issue**: The §1.7(c) "Detection rule" read-side prose in the staged `conventions.md` still describes the implementer reading the marker from the plan: "the implementer reads `implementation-plan.md`'s `### Constraints` section and checks for the marker sentence in (b)" (1057-1059). This is a consumer-description Track 2 re-points (the implementer per-spawn gate reads the ledger `s17` field). But `conventions.md` is a **Track-1-owned** file (it is staged and listed under Track 2's "Out-of-scope (Track 1)"), so Track 2 cannot edit it without crossing the track boundary. Track 1 deliberately left the develop-era in-plan marker spelling in §1.7(b) "until the readers are re-pointed" (1004-1007) and named the `s17` home, but the §1.7(c) *read-side* sentence at 1056-1063 was not flagged the way §1.7(b) was. After Track 2 re-points `step-implementation.md` / `implementer-rules.md` to read `s17`, the §1.7(c) prose still says the implementer reads `### Constraints`, leaving the canonical detection-rule definition contradicting its own re-pointed consumers. This is a cross-track seam, not a Track-2-internal defect — flagging so the orchestrator can decide whether the §1.7(c) read-side edit belongs to a Track 1 amendment (Pre-Flight) or a deliberate Track 2 scope widening into `conventions.md`.
**Proposed fix**: Surface to the orchestrator at decomposition: either (a) amend §1.7(c)'s "Constraints declaration drives the implementer enforcement gate" bullet to read "the implementer reads the ledger `s17` field" as part of Track 1's authoring (via a Pre-Flight strategy adjustment, since conventions.md is staged by Track 1), or (b) widen Track 2's scope to include the §1.7(c) read-side sentence in `conventions.md`, mirroring how §1.7(b)/(k) explicitly say "Track 2 re-points the readers." Leaving it unaddressed lands a canonical-doc contradiction. No blocker — the implementer gate functionally reads `s17` once step 2 lands; this is doc-coherence cleanup.

## Evidence base

#### P-FILES: Every in-scope file named in the track exists (live or staged)
- **Track claim**: `## Interfaces and Dependencies` lists 16 in-scope files under `.claude/workflow/**`; all edits route to the staged mirror.
- **Search performed**: `find` + per-file existence test over the 16 paths (live tree) and `find` over `_workflow/staged-workflow/`. Workflow-prose lens (path verification), not `findClass`.
- **Code location**: all 16 live files EXIST; `workflow.md` additionally present staged at `_workflow/staged-workflow/.claude/workflow/workflow.md`.
- **Actual behavior**: 16/16 in-scope files exist live. Staged tree carries only Track-1 files (precheck.sh + 2 tests, create-plan SKILL, conventions.md, conventions-execution.md, planning.md, workflow.md). The other 15 Track-2 consumers are NOT staged → read live, per the spawn's staged-read instruction.
- **Verdict**: CONFIRMED
- **Detail**: `workflow.md` must be read STAGED (Track 1 already migrated it). The other 15 read live. The spawn's staged-read list matches the on-disk staged tree exactly.

#### P-LEDGER-GRAMMAR: The `--append-ledger` subcommand + event grammar Track 2 consumes
- **Track claim**: `## Interfaces and Dependencies` — "The ledger event grammar and the `--append-ledger` subcommand signature ... key set `{ phase, track, tier, categories, s17, paused }`."
- **Search performed**: grep + Read of the STAGED `workflow-startup-precheck.sh` (the live develop precheck has no ledger; validated against staged per spawn instruction).
- **Code location**: staged precheck.sh:139 (`--append-ledger)` selector), :51,56 (grammar + key set), :1567-1636 (`append_ledger` composer + atomic temp-file-plus-rename), :1652-1684 (`ledger_tail_value` last-value-wins reader).
- **Actual behavior**: emitted line `[<ISO>] [ctx=<level>] phase=<v> track=<v> tier=<v> categories="<v>" s17=<v> paused=<v>`; key set is "exactly { phase, track, tier, categories, s17, paused }"; `categories` is the one quoted field; bare fields reject spaces/newlines (exit 3); `ledger_tail_value` returns the last value of any key.
- **Verdict**: CONFIRMED
- **Detail**: The contract Track 2 step 2 assumes (read the §1.7 marker from a ledger field) is backed by a real `s17` bare-token field and a real last-value-wins reader.

#### P-PHASE-VOCAB: The ledger `phase` vocabulary is a closed exit-3-on-mismatch contract
- **Track claim**: step 6 "Consumes Track 1's ledger `phase` contract (the `phase == "D"`/`"Done"` tail values)."
- **Search performed**: Read of staged precheck.sh `determine_state_from_ledger` (1755-1807).
- **Code location**: staged precheck.sh:1773-1806 — `case "$phase" in 0 | A | D | Done) ... C) ... *) parse_error "phase-ledger.md" ...`.
- **Actual behavior**: phase ∈ {0, A, C, D, Done}; `D`/`Done` emit `{phase, substate:null}`; `C` reads `track` and computes the within-track substate; any other token routes to `parse_error` → exit 3 before JSON. Matches the create-plan SKILL header pin "phase vocabulary is exactly {0, A, C, D, Done}".
- **Verdict**: CONFIRMED
- **Detail**: step 6's `phase == "D"` / `phase == "Done"` Startup-Protocol rows have a real, closed tail-value contract to key on.

#### P-S17: The `s17` field is a defined-vocabulary bare-token marker home
- **Track claim**: D4 / step 2 — "re-point the §1.7(c)/(l) marker readers onto the ledger `s17` field."
- **Search performed**: grep + Read of staged conventions.md §1.7(b) (994-1048) and §1.7(k) (1327-1337); staged create-plan SKILL seeding block (1031-1053).
- **Code location**: staged conventions.md:996 (`s17` home, D4), :1331-1332 (`s17 = workflow-modifying` | `s17 = opt-out`, mutually exclusive), :1004 ("Track 2 re-points the consumers ... onto the ledger `s17` value"); staged create-plan SKILL:1036-1038 (`--s17` seeded only when workflow-modifying or §1.7(k) opt-out).
- **Actual behavior**: `s17` is a bare token with two values — `workflow-modifying` (staging) and `opt-out` (live opt-out) — or absent (neither). The staging consumers ((c) gate) match `workflow-modifying`; the (l) criteria-switch fires on either token. The marker is no longer a sentence with stable-prefix matching; it is a single-token field equality.
- **Verdict**: CONFIRMED
- **Detail**: The semantic translation Track 2 must perform is well-defined: "marker present in `### Constraints` (stable-prefix match)" → "ledger `s17` == `workflow-modifying`" for the (c) gate; "workflow-modifying OR opt-out" → "`s17` ∈ {workflow-modifying, opt-out}" for the (l) switch. Track 1 pinned both the home and the value vocabulary, so no reader is left guessing.

#### P-MARKER-READERS: The §1.7-marker reader inventory (D4) is exhaustive against the live tree
- **Track claim**: D4 risk note enumerates the marker readers; "a missed reader silently reads a stale or absent fact, so the reader inventory must be exhaustive."
- **Search performed**: `grep -rln` for `workflow-modifying|### Constraints|§1.7(b)|staged-read` across live `.claude/workflow/**` and `.claude/skills/**`; then per-file read of every hit to separate marker-DETECTION readers from generic mentions.
- **Code location**: detection-readers found: technical/risk/adversarial-review.md (staged-read block + (l) criteria block), dimensional-review-gate-check.md:40, review-gate-verification.md:41, step-implementation.md:312-313,327,468,583-584, implementer-rules.md:262-263,395-396, consistency-review.md (tier), structural-review.md, create-final-design.md, track-review.md, inline-replanning.md, mid-phase-handoff.md, track-code-review.md, workflow.md. Non-readers that merely mention the concept: code-review/SKILL.md:240 (staged-path NORMALIZATION, keys on diff path not marker), migrate-workflow/SKILL.md:121,153, design-review.md:271, research.md:78, review-agent-selection.md:238,349.
- **Actual behavior**: Every file that DETECTS a workflow-modifying branch by reading the plan `### Constraints` marker is in Track 2's in-scope list. The five files outside the list (code-review, migrate-workflow, design-review, research, review-agent-selection) reference the concept but do not detect it from `### Constraints` — they key on diff-path prefixes or describe the concept generically.
- **Verdict**: CONFIRMED
- **Detail**: No missed reader → no blocker under D4's "missed reader is a blocker" criterion. The Phase-2 consistency-review CR1 escalation already caught and added the two gate-recheck prompts (`dimensional-review-gate-check`, `review-gate-verification`); both are present in the inventory and in-scope.

#### P-D14-GATE: The implementer-rules §1.7(e) pre-commit gate omits `.claude/scripts` (the gap Track 2 closes)
- **Track claim**: step 2(i) / D14 — "extend the implementer-rules §1.7(e) pre-commit gate to refuse a live `.claude/scripts/**` edit ... (matching the existing three-prefix exception)."
- **Search performed**: grep + Read of LIVE `implementer-rules.md` § Pre-commit gate (387-418).
- **Code location**: implementer-rules.md:403 — `git diff --cached --name-only -- .claude/workflow/ .claude/skills/ .claude/agents/`.
- **Actual behavior**: the live gate lists exactly three prefixes (`.claude/workflow/`, `.claude/skills/`, `.claude/agents/`) and OMITS `.claude/scripts/`. The allow-clause is the Phase-4 promotion commit signature (409-418). The gate is marker-gated (395-396, reads `### Constraints`).
- **Verdict**: CONFIRMED
- **Detail**: As-is state matches the track's premise. Track 2 must add `.claude/scripts/` to line 403's path list AND re-point the marker detection (395-396) to the ledger `s17` field in the same edit.

#### P-D14-PROMOTE: The create-final-design Phase-4 `git add` + divergence check omit `.claude/scripts` (the gap Track 2 closes)
- **Track claim**: step 2(ii) / D14 — "the `cp -r .claude/.` already copies the staged scripts onto live, but without the wider `git add` they are copied yet never committed, so they never reach develop."
- **Search performed**: grep + Read of LIVE `create-final-design.md` Phase-4 promotion block (444-461).
- **Code location**: create-final-design.md:450 (divergence `git log ... -- .claude/workflow .claude/skills .claude/agents`), :456 (`cp -r "$STAGED_DIR/.claude/." .claude/`), :457 (`git add .claude/workflow .claude/skills .claude/agents`).
- **Actual behavior**: line 456 `cp -r` copies the ENTIRE staged `.claude/` (including `.claude/scripts/`) to live, but line 457 `git add` lists only three prefixes (omits scripts), and line 450 divergence check lists the same three. So a promoted staged script is copied to the live tree but never staged for the promotion commit → never reaches develop. Premise holds exactly as stated.
- **Verdict**: CONFIRMED
- **Detail**: Track 2 must add `.claude/scripts` to both line 450 (divergence) and line 457 (`git add`). The `cp -r` at 456 already copies it, so no change there.

#### P-WF-REFS: The three step-6 `workflow.md` references exist in the staged copy
- **Track claim**: step 6 / `## Context and Orientation` — three references keyed on removed plan sections: the Phase-4 start/resume signal + completion-episode writer (`phase == "D"` row + `## Final Artifacts`), the When-to-end-a-session State-0 bullet (`## Plan Review`), the implementation-review loader note (`## Plan Review`).
- **Search performed**: grep + Read of STAGED `workflow.md` (the file is staged; read per staged-read precedence).
- **Code location**: staged workflow.md:344-351 (`phase == "D"` row, carries "Track 2 re-points this"), :739-745 (`## Final Artifacts` block, carries "Track 2 re-points this"), :414-419 (When-to-end "After State 0" bullet — "`## Plan Review` is marked `[x]` with the audit summary"), :768 (implementation-review loader note — "routes there when `## Plan Review` is `[ ]`").
- **Actual behavior**: all three references are present in the staged copy. The State-0 detection in the Startup Protocol `phase == "0"` row (304-310) is ALREADY migrated by Track 1 ("recorded in `plan-review.md` and a ledger phase entry, not a `## Plan Review` plan checkbox") — so it is NOT a fourth missed reference; it is done.
- **Verdict**: CONFIRMED
- **Detail**: The "three references" inventory is accurate. See I-PHASE-D for the nuance that (a) is already routing-correct and only needs forward-pointer cleanup (T3).

#### P-TIER-READERS: Tier-line read inventory across in-scope files
- **Track claim**: step 1 (consistency/structural read the ledger tier line), step 2 ("re-point `track-review` and `create-final-design` tier-line reads to the ledger").
- **Search performed**: grep `tier line in|read the tier|Change tier` across the five tier-reading in-scope files (live).
- **Code location**: consistency-review.md:56 (read tier line; degenerate-unreadable branch at 83-87); structural-review.md (minimal pass-skip 262-266, tier-keyed bloat checks); create-final-design.md:41,87 (read D18 tier line in `implementation-plan.md`); track-review.md:484,601 (read tier line in `implementation-plan.md`); inline-replanning.md:151 ("selectors all read the tier line in `implementation-plan.md`"),162 ("tier-line rewrite").
- **Actual behavior**: consistency (step 1), structural (step 1), create-final-design (step 2), track-review (step 2) tier reads are all assigned to a re-point step. `inline-replanning`'s tier READ (151) and "tier-line rewrite" (162) are NOT in step 2's read list and step 4 (D11) addresses only the writer/materialize.
- **Verdict**: PARTIAL
- **Detail**: One tier-line read site (inline-replanning:151,162) is not explicitly re-pointed → T1.

#### P-IMPLREVIEW: implementation-review.md writes the audit to the plan `## Plan Review` today (D7 source)
- **Track claim**: step 1 / D7 — "`implementation-review.md` writes the audit summary to the new document and records review *state* in the ledger."
- **Search performed**: grep + Read of LIVE `implementation-review.md`.
- **Code location**: implementation-review.md:29 (§"The `## Plan Review` section in the plan file"), :65-66 ("marks `## Plan Review` as `[x]` ... with a brief audit summary"), :384,506,610.
- **Actual behavior**: the file's entire review-recording mechanism writes to the plan's `## Plan Review` section today. D7 moves the audit summary to `plan-review.md` and the review state to the ledger. The premise holds.
- **Verdict**: CONFIRMED
- **Detail**: Substantial — `implementation-review.md` has a dedicated `## Plan Review`-writing section (§1, line 610) Track 2 must rewrite, not just a one-line reference. The decomposition should budget for this being the heaviest single-file edit in step 1.

#### I-INLINE-TIER: inline-replanning tier-line read integration
- **Plan claim**: D11 / step 4 — inline-replanning writes the upgraded tier as a ledger event and materializes the dropped plan/design.
- **Actual entry point**: inline-replanning.md:150-162 (the D18 tier-line rewrite that "the Phase-2/3A/4 selectors all read ... in `implementation-plan.md`").
- **Caller analysis**: workflow-prose lens — the prose asserts the tier line lives in `implementation-plan.md` and is read by every selector. Under D2 a `minimal` branch has no plan; once Track 2 re-points the other selectors to the ledger (steps 1-2), this sentence describes a read surface that no longer holds the tier.
- **Breaking change risk**: an implementer following step 4 alone re-points the writer but leaves the descriptive read prose (151) and "tier-line rewrite" framing (162) plan-anchored → internally inconsistent file.
- **Verdict**: MISMATCHES
- **Detail**: → T1.

#### E-PAUSED-GREP: ledger pause-event recovery and machine-read
- **Trigger**: a Phase-2/State-0 or Phase-4 pause recorded as a ledger `paused` event (D8), then (a) a resume that must detect it and (b) a recovery `grep -rn '^\*\*PAUSED '`.
- **Code path trace**:
  1. write: `append_ledger` → `reject_bad_ledger_value "paused" "$LEDGER_PAUSED" bare` @ staged precheck.sh:1582 — a value containing a space exits 3; the develop marker `**PAUSED <date> at <state> ...` has spaces → cannot be stored verbatim.
  2. line shape: `paused=<event>` is appended mid-line after `phase=… track=… …` @ precheck.sh:1603 — never at column 0.
  3. recovery: `grep -rn '^\*\*PAUSED '` (mid-phase-handoff.md:173) is `^`-anchored → cannot match a mid-line `paused=` token.
  4. resume: `determine_state_from_ledger` calls `ledger_tail_value` for `phase` (1766) and `track` (1785) only; no `paused` read anywhere.
- **Outcome**: both D8 escape hatches are blocked (spaces rejected; `^`-anchor can't match mid-line); the resume state machine never reads `paused`. The pause event is written but inert, and the recovery grep cannot find it on a ledger line.
- **Track coverage**: step 3 says "keeping the greppable `**PAUSED` prefix (or extending the recovery grep to the ledger)" — neither sub-clause is mechanically achievable as the ledger is built without a concrete spaceless-token + new-grep-pattern design. → T2.

#### I-PHASE-D: Phase-4 resume signal already on the ledger
- **Plan claim**: step 6 (a) — re-point the Phase-4 start/resume signal off the plan checkbox onto the ledger.
- **Actual entry point**: staged workflow.md:344-351 (`phase == "D"` row), :739-745 (`## Final Artifacts` block).
- **Caller analysis**: the `phase == "D"` row already resolves Phase 4 from the ledger tail ("the phase comes from the ledger"; "Phase-4 progress is tracked by the phase ledger"). The plan `## Final Artifacts` checkbox is already declared gone.
- **Breaking change risk**: low — routing is correct. The risk is a no-op or redundant edit if the implementer reads step 6 (a) as "change routing" rather than "remove the stale forward-pointer prose."
- **Verdict**: PARTIAL
- **Detail**: → T3. The genuine routing work in step 6 is the (b)/(c) halves (When-to-end State-0 bullet and loader note still key on `## Plan Review`); (a) is forward-pointer cleanup.

#### I-S17C-PROSE: §1.7(c) read-side prose is a Track-1-owned consumer description
- **Plan claim**: step 2 / D4 — re-point the §1.7(c)/(l) marker readers onto `s17`.
- **Actual entry point**: staged conventions.md:1056-1063 (§1.7(c) "Constraints declaration drives the implementer enforcement gate ... reads `implementation-plan.md`'s `### Constraints` section").
- **Caller analysis**: this is the canonical detection-rule definition; it describes the implementer gate Track 2 re-points. But `conventions.md` is staged by Track 1 and listed in Track 2 "Out-of-scope (Track 1)."
- **Breaking change risk**: doc-coherence only — after Track 2 re-points `step-implementation`/`implementer-rules` to read `s17`, §1.7(c) still says the implementer reads `### Constraints`, leaving the canonical doc contradicting its consumers.
- **Verdict**: MISMATCHES
- **Detail**: → T4. Cross-track seam; orchestrator decides Track-1-amend vs Track-2-scope-widen.
