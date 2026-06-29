<!--
MANIFEST
role: reviewer-technical
phase: 3A
track: "Track 1: Ledger schema, resume routing, and Phase-1 artifact existence"
iteration: 1
verdict: CHANGES_REQUESTED
findings: 5
index:
  - id: T1
    sev: should-fix
    anchor: "T1"
    loc: ".claude/workflow/inline-replanning.md:169 (Track-2-owned) vs precheck --tier drop"
    cert: "Integration: --tier write sites vs the precheck flag drop"
    basis: grep
  - id: T2
    sev: should-fix
    anchor: "T2"
    loc: "track-1.md Plan of Work (3) / Interfaces; workflow.md:660-695, conventions.md:241, create-final-design.md:97-99"
    cert: "Integration: Phase-4 carrier table split across in/out-of-scope files"
    basis: grep+read
  - id: T3
    sev: suggestion
    anchor: "T3"
    loc: "create-plan/SKILL.md:250 (Step-1c minimal re-seed) — second --tier write"
    cert: "Premise: --tier write sites in create-plan"
    basis: grep+read
  - id: T4
    sev: suggestion
    anchor: "T4"
    loc: "track-1.md Plan of Work (1); precheck ledger_tail_value (sh:1774-1806)"
    cert: "Premise: the bare-before-categories emit-order rationale"
    basis: read
  - id: T5
    sev: suggestion
    anchor: "T5"
    loc: ".claude/scripts/tests/test_workflow_startup_precheck_stub.py:269,289,314,329-330,346"
    cert: "Premise: stub-test ledger fixtures hardcode tier=minimal"
    basis: grep+read
evidence_base:
  premises: 8
  edge_cases: 2
  integrations: 3
  confirmed: 9
  not_confirmed: 4
-->

## Findings

### T1 [should-fix]
**Certificate**: Integration — `--tier` write sites vs the precheck flag drop
**Location**: `.claude/workflow/inline-replanning.md:169` (Track-2-owned, out of scope) against the Plan-of-Work step (1) drop of `--tier` from `.claude/scripts/workflow-startup-precheck.sh`; track-1.md `## Interfaces and Dependencies` "Inter-track dependencies" paragraph.
**Issue**: The track drops the `--tier` flag from the precheck's arg parser, the `reject_bad_ledger_value "tier"` call, and the line-builder (`workflow-startup-precheck.sh` lines 171-174 arg case, 1696 validation, 1718 builder). The precheck's arg parser routes any unrecognized flag to the `*)` case at lines 191-195, which prints `Unknown argument` and `exit 2`. There are three live `--tier` write sites, not the single ledger-seed the Plan of Work names:
1. `create-plan/SKILL.md:1239` (the seed) — in scope, named.
2. `create-plan/SKILL.md:250` (the Step-1c `minimal` re-seed, `--phase 0 --tier minimal`) — in scope, see T3.
3. `inline-replanning.md:169` (`--append-ledger --tier <new-tier>`, the execution-time ESCALATE tier-upgrade write) — this is a **live executable bash invocation** (confirmed at lines 164-180: the ESCALATE replan's "one execution-time tier append"), and `inline-replanning.md` is in the track's **out-of-scope (Track 2 owns)** list.
Once Track 1's staged `workflow-startup-precheck.sh` drops `--tier`, the live `inline-replanning.md:169` invocation fails with `exit 2`. Because both files stage under `_workflow/staged-workflow/` and promote together at Phase 4, end-state consistency is recovered only after Track 2 re-keys `inline-replanning.md`. The track's Interfaces section frames the coupling as strictly one-directional ("Inter-track dependencies: none upstream … Track 2 depends on this track"), but the `--tier` removal creates a **reverse forward-dependency**: Track 1's schema deletion *requires* Track 2's `inline-replanning.md` re-key for the staged workflow to be self-consistent. This is the same kind of cross-track split the track already documents for the `adr.md` predicate (track-1.md lines 108-114) but here it is undocumented.
**Proposed fix**: Add a forward-dependency note to track-1.md `## Interfaces and Dependencies` recording that `inline-replanning.md:169`'s `--append-ledger --tier` write is broken by the `--tier` drop and must be re-keyed by Track 2 (to write `design_gate`/the plan-presence signal/the per-track tag as the upgrade requires) before the staged workflow is internally consistent — mirroring the lines-108-114 carrier-table note. Alternatively, sequence the precheck flag-drop so the old `--tier` flag is tolerated (parsed and ignored) until Track 2 lands, but the cleaner option is the explicit cross-track note since both promote together.

### T2 [should-fix]
**Certificate**: Integration — Phase-4 carrier table split across in/out-of-scope files
**Location**: track-1.md Plan of Work (4) and `## Interfaces and Dependencies`; `workflow.md` §"Final Artifacts (Phase 4)" (lines 660-695), `conventions.md` §"Per-tier artifact set" (line 241 "Phase 4 durable carrier" row), `create-final-design.md` Step 3 table (lines 97-99, Track-2-owned).
**Issue**: The Phase-4 durable-carrier selection lives in three tier-keyed places. Track 1 re-keys two of them to the axis-derived form (`workflow.md` and `conventions.md`, both in scope) while the third — `create-final-design.md` Step 3 (lines 89-106), which the design (Part 4, design.md:634-637) names "the load-bearing **hub**" that actually executes the carrier decision — is Track 2's and stays tier-keyed until Track 2 lands. After Track 1, the two descriptive tables will read `adr` iff ∃ track ≥ medium while the executor still reads `lite→adr, minimal→none`. Two concrete coherence hazards the Plan of Work understates:
(a) The `workflow.md` table (lines 660-664) is embedded in heavily tier-keyed prose: line 666 ("Gate 2 (multi-track) is the durable-ADR boundary"), the per-tier-**and-modification-class** commit-shape list (lines 685-695, e.g. "`minimal`, workflow-modifying — two commits … the shed removes the fold's `adr.md` home"), and the final-artifacts-commit prose (lines 718-730) all key on `full`/`lite`/`minimal`. Re-keying only the table row without reconciling this surrounding prose leaves the section internally contradictory (a table that says "adr iff ≥ medium" beside prose that says "Gate 2 is the durable-ADR boundary").
(b) The `adr ⟺ ∃ track ≥ medium` predicate reads the **reconciled** per-track tag, which is not written to the ledger until the Phase-A→C boundary (design.md:631-637, Part 5). Track 1 only *reserves* the per-track-tag ledger home; the value is absent at Phase 1 when these tables are first authored. The tables are descriptive (read at Phase 4), so authoring them is fine, but the track should state that Track 1 authors the predicate **text** while the reconciled-tag the predicate reads is a Track-2 write — the track-file note at lines 108-114 says this for `conventions.md` but not for the `workflow.md` commit-shape prose.
**Proposed fix**: In Plan of Work (4) and the Interfaces note, (1) widen the `workflow.md` re-key scope to include the surrounding tier-keyed prose (the durable-ADR-boundary sentence at line 666 and the commit-shape list at 685-695) so the re-keyed table does not contradict adjacent prose, OR explicitly defer that prose to Track 2 with a note that Track 1 changes only the table row; (2) extend the lines-108-114 cross-track note to cover the `workflow.md` carrier prose, not just `conventions.md`, recording that the executing hub (`create-final-design.md`) stays tier-keyed until Track 2.

### T3 [suggestion]
**Certificate**: Premise — `--tier` write sites in create-plan
**Location**: `create-plan/SKILL.md:250` (Step-1c `minimal`-resume re-seed: "Resume by seeding the ledger (`--phase 0 --tier minimal`, …)").
**Issue**: The Plan-of-Work step (3) says only "the ledger-seed call drops `--tier`", which reads as the single Step-4b/Step-4 seed at line 1239. But Step 1c carries a **second** `--tier` write at line 250 — the resume re-seed for a `minimal` session interrupted between the track-file write and the ledger seed. This is in-scope (Step 1c is named in Plan of Work (2)), but the `--tier minimal` re-seed is a distinct edit site the step does not enumerate; missing it would leave a `--tier minimal` invocation that fails `exit 2` after the flag drop (same mechanism as T1).
**Proposed fix**: Add `create-plan/SKILL.md:250` to the Plan-of-Work step that drops `--tier`, re-keyed to seed `design_gate=no` + the single/no-plan plan-presence signal (the `minimal` shape) instead of `--tier minimal`.

### T4 [suggestion]
**Certificate**: Premise — the bare-before-`categories` emit-order rationale
**Location**: track-1.md Plan of Work (1): "a bare field must precede the quoted `categories` field so the quoted value's embedded spaces do not end the bare-token scan early"; `workflow-startup-precheck.sh` `ledger_tail_value` (lines 1774-1806) and its header comment (lines 1782-1789).
**Issue**: The *conclusion* (emit every reader-consumed bare field before the one quoted `categories` field) is correct and matches the script's own invariant. But the stated *mechanism* is imprecise. `ledger_tail_value` takes the **first** ` $key=` token on a line and stops (line 1790-1803, no re-scan of the remainder); it does not "scan bare tokens until a space ends the scan." The real hazard the emit-order guards against is a **same-named decoy `key=` inside the quoted `categories="…"` span** winning when the real bare key is emitted *after* `categories` — exactly what the script header comment (lines 1782-1789) states ("a key emitted AFTER `categories` would let a same-named decoy inside the quoted value win"). The imprecise mechanism could mislead the implementer into the wrong placement reasoning for `design_gate` (the track correctly places it pre-`categories`, so the outcome is right, but the rationale should match the code).
**Proposed fix**: Reword the Plan-of-Work sentence to match the script's actual mechanism: the bare reader-consumed fields (`design_gate`, the plan-presence signal, the Phase-1-complete marker) must be emitted **before** the quoted `categories` field so the first-match token scan in `ledger_tail_value` / `ledger_tail_value_for_track` cannot resolve a same-named decoy embedded inside the quoted span — citing the script header at lines 1782-1789 / 1817-1824.

### T5 [suggestion]
**Certificate**: Premise — stub-test ledger fixtures hardcode `tier=minimal`
**Location**: `.claude/scripts/tests/test_workflow_startup_precheck_stub.py` lines 269, 289, 314, 329-330, 346 (the `MinimalLedgerFixture.write_ledger(f"[{_TS}] [ctx=safe] phase=… tier=minimal\n")` fixtures).
**Issue**: Plan of Work (1) says to update both test files "to cover the new fields' append + round-trip, the loud-reject …, the last-value-wins read, the track-scoped read …, and the torn-append-leaves-prior-tail behavior" — all *additive*. It does not mention that the stub file's existing `minimal`-resume fixtures **embed `tier=minimal` in the ledger line** and assert resume routing off it. After `tier=` leaves the grammar these lines are stale fixtures (a `tier=minimal` token the reader no longer consumes); the tests still pass because the routing now keys on the plan-presence signal, but the fixtures carry a dead token and no longer exercise the real resume signal. The same hardcoding appears in the main test file's stub-adjacent fixtures.
**Proposed fix**: Note in Plan of Work (1) that the stub file's `tier=minimal` ledger fixtures (and any main-file fixtures that seed `tier=`) must be migrated to the new fields (`design_gate=no` + the single/no-plan plan-presence signal) so the resume-routing tests exercise the live signal, not a dead `tier=` token — this is a *modification* of existing fixtures, beyond the additive coverage the step currently lists.

## Evidence base

#### Premise: the four ledger fields fit the existing accumulator/validation/builder/header structure
- **Track claim**: Plan of Work (1) — drop `tier=`; add `design_gate` (bare yes/no), a plan-presence/track-count signal, a Phase-1-complete marker (bare flag), a per-track reconciled tag (bare low/medium/high); each new bare field gets a `reject_bad_ledger_value … bare` call and one `[ -n "$LEDGER_X" ] && line="$line X=…"` builder line; the per-track tag reads via `ledger_tail_value_for_track`.
- **Search performed**: Read of `workflow-startup-precheck.sh` lines 102-220 (arg accumulators + parser), 1655-1681 (`reject_bad_ledger_value`), 1683-1761 (`append_ledger` validation + builder), 1763-1863 (`ledger_tail_value` / `ledger_tail_value_for_track`).
- **Code location**: arg accumulators 116-128; arg case 159-190; validation block 1693-1700; builder block 1715-1726; track-scoped reader 1828-1863.
- **Actual behavior**: The structure is exactly the additive pattern the track describes. Each existing bare field has (a) a `LEDGER_X=""` accumulator, (b) a `--x) LEDGER_X="$2"; shift 2` arg case, (c) a `reject_bad_ledger_value "x" "$LEDGER_X" bare` line, (d) a `[ -n "$LEDGER_X" ] && line="$line x=$LEDGER_X"` builder line. `substate` (lines 1719-1723) is the precedent for a bare reader-consumed field emitted in the pre-`categories` block. `ledger_tail_value_for_track` (1828-1863) is the exact primitive the per-track tag needs — it already track-scopes `substate` the same way.
- **Verdict**: CONFIRMED
- **Detail**: The schema delta fits the existing structure with no new machinery. `ledger_tail_value_for_track` is the right primitive for the per-track tag (it already serves `substate` track-scoped, lines 1981-1986 in `determine_state_from_ledger`).

#### Premise: `design_gate` placement in the pre-`categories` block is correct
- **Track claim**: Plan of Work (1) — "The `design_gate` field is emitted in the pre-`categories` block (it is reader-consumed and bare)."
- **Search performed**: Read of `append_ledger` builder (lines 1715-1726) and `ledger_tail_value` first-match scan (1790-1804).
- **Code location**: builder 1715-1726; reader 1790-1804; header invariant 1782-1789.
- **Actual behavior**: `substate` is already emitted before `categories` (line 1723) precisely so the first-match scan cannot lose it to a decoy inside the quoted span. `design_gate`, being reader-consumed and bare, belongs in the same pre-`categories` block. Placement claim is correct.
- **Verdict**: CONFIRMED
- **Detail**: Correct outcome; the *stated rationale* is imprecise — see finding T4.

#### Premise: `LEDGER_TIER` parse and the Step-1c tier reads exist as the track describes
- **Track claim**: Context — the Step-1c router parses `tier=` once (`LEDGER_TIER`) then routes by it; `determine_state` defaults the `minimal` active track to 1.
- **Search performed**: Read of `create-plan/SKILL.md` lines 131-293 (Step 1c) and `workflow-startup-precheck.sh` `determine_state_from_ledger` (1934-2010) + `determine_state` (2012-2143).
- **Code location**: `LEDGER_TIER` sed parse at SKILL.md:162-163; tier-keyed branches 144-277; `minimal`-default-track-to-1 at precheck 1963-1967.
- **Actual behavior**: SKILL.md:162 parses `tier=` via `sed -n 's/.* tier=\([a-z]*\).*/\1/p' … | tail -n 1`. The routing branches at 174-277 key on `LEDGER_TIER` (`lite`/`full`/`minimal`) plus disk presence. `determine_state_from_ledger` reads `phase`/`track` and defaults `track="1"` when absent (1967); it does **not** read `tier` itself — the `minimal`-default note in its comment (1963-1964) is the rationale, the actual default is track-absence-driven. So the `determine_state` re-key the track describes ("re-keys onto the plan-presence/track-count signal instead of the removed tier") is really a comment/rationale re-key plus the `LEDGER_TIER` removal in SKILL.md, since the code default already keys on track-absence, not tier.
- **Verdict**: PARTIAL
- **Detail**: The Step-1c `LEDGER_TIER` parse is real and tier-keyed (re-key needed). But `determine_state_from_ledger` does not read `tier` in code — only its explanatory comments mention the `minimal` tier. The track's "re-point `determine_state_from_ledger`" wording slightly overstates the code change there; the substantive code re-key is the Step-1c router. Not a finding (the track's deliverable list is still correct in net effect), recorded for the decomposer's accuracy.

#### Premise: the consistency-review design-presence gate reads the tier and can re-key to `design_gate`
- **Track claim**: Plan of Work (3) — "The consistency-review … prompts re-key their design-presence gate to read `design_gate` instead of the tier."
- **Search performed**: Read of `consistency-review.md` lines 45-104.
- **Code location**: `consistency-review.md` 55-96 (Tier and the design-presence guard; Tier-presence check; Degenerate case).
- **Actual behavior**: The prompt reads the tier ledger-first (`tier` field, fallback to the plan tier line) at 56-61, then applies a per-tier axis selection (`full`/`lite`/`minimal`, 63-76) PLUS a tier-presence check (78-88) and a degenerate "tier unreadable" fallback (90-96). The *axis selection* reduces to a `design.md`-presence test (60-61: "Apply one mechanical test: does `design.md` exist?"), so the design-half re-key to `design_gate` is clean. But the **tier-presence check** (78-88) and the **`minimal` plan-drop axis** (68-76) are not pure design-presence — they distinguish `minimal` (no plan) from `lite` (plan) and require the *tier* itself. Re-keying these to the two axes (`design_gate` + plan-presence) is more than a "design-presence gate" re-key; the plan-presence axis must replace the `minimal`-vs-`lite` distinction.
- **Verdict**: PARTIAL
- **Detail**: The design-half gate re-keys cleanly to `design_gate`. The plan-content-drop axis (`minimal` PLAN↔CODE drop) and the tier-presence finding must re-key to the plan-presence signal, which the track's `implementation-review.md` step (3) captures ("structural-pass skip reads the plan-presence/track-count signal") but the consistency-review wording ("design-presence gate reads `design_gate`") understates. Folded into the decomposer's guidance; covered in net by Plan of Work (3)'s mention of "the structural review's per-tier artifact checks re-key onto the axes." Not raised as a separate finding because the deliverable is correct in aggregate.

#### Premise: `implementation-review.md` §"Tier-driven pass selection" reads `tier` on the two axes the track names
- **Track claim**: Plan of Work (3) — its design-half guard reads `design_gate` and its structural-pass skip reads the plan-presence/track-count signal (no plan ⇒ skip structural).
- **Search performed**: Read of `implementation-review.md` lines 189-248.
- **Code location**: 192-204 (tier read), 210-213 (per-tier pass table), 215-223 (the two independent narrowings), 225-233 (design-presence guard).
- **Actual behavior**: The section keys Step-1-consistency shape and Step-2-structural run/drop on the tier. It already factors into "two independent narrowings" (215): the design-half drop "whenever no `design.md` exists" (216 — maps to `design_gate`) and the plan-content/structural drop "in `minimal` only, because `minimal` has no plan" (217-223 — maps to the plan-presence signal). This is the cleanest re-key target of the three prompts: the section already isolates the two axes the design unbundles.
- **Verdict**: CONFIRMED
- **Detail**: The track's step-(3) re-key maps one-to-one onto the existing two-narrowings structure. The section header, the TOC summary (line 10/190), and the `(D9/D10)` tag still name "tier"/"Tier-driven" and will need text updates, but the logic re-keys directly.

#### Premise: the seven `risk-tagging.md` HIGH triggers are the tag/Gate-1 source
- **Track claim**: Plan of Work (4) / Context — the planner predicts each track's complexity tag referencing the existing `risk-tagging` HIGH triggers; Gate 1 reuses `risk-tagging.md` §"Gate 1 reuse".
- **Search performed**: Read of `risk-tagging.md` lines 97-206; grep for the §"Gate 1 reuse" anchor.
- **Code location**: HIGH-risk triggers 97-180 (seven category subsections); §"Gate 1 reuse (change-level)" anchor at 181.
- **Actual behavior**: Exactly seven HIGH categories (`Concurrency`, `Crash-safety / Durability`, `Public API`, `Security`, `Architecture / cross-component coordination`, `Performance hot path`, `Workflow machinery`), enumerated verbatim at 184-187. The §"Gate 1 reuse (change-level)" anchor exists (181). The design's "seven HIGH triggers" (design.md:284, 319) and the track's reference both resolve.
- **Verdict**: CONFIRMED
- **Detail**: Anchor and count both resolve. `risk-tagging.md` is out of scope (Track 2 owns the tag computation), consistent with the track wiring only the *prediction request* into `planning.md`.

#### Premise: the two Phase-4 carrier tables Track 1 owns exist and are tier-keyed
- **Track claim**: Plan of Work (4) / Interfaces — re-key `workflow.md` §"Final Artifacts (Phase 4)" per-tier durable-carrier table and `conventions.md` per-axis artifact set `adr.md` row to the axis-derived form.
- **Search performed**: Read of `workflow.md` 653-740 and `conventions.md` 226-318; grep for `create-final-design.md` carrier table.
- **Code location**: `workflow.md` table 660-664 + tier-keyed prose 666-730; `conventions.md` "Phase 4 durable carrier" row 241; `create-final-design.md` Step-3 table 97-99.
- **Actual behavior**: Both Track-1-owned tables exist and are tier-keyed. The `create-final-design.md` Step-3 table (97-99) — the executing hub per design.md:634-637 — is Track-2-owned and stays tier-keyed after Track 1.
- **Verdict**: CONFIRMED
- **Detail**: Existence confirmed; the cross-file split is the subject of finding T2.

#### Premise: the precheck and stub test files have the structure the claimed additions fit
- **Track claim**: Plan of Work (1) — update both precheck test files to cover the new fields' append/round-trip, loud-reject, last-value-wins, track-scoped no-leak, torn-append.
- **Search performed**: grep of `test_workflow_startup_precheck.py` (def families) and `..._stub.py` (fixtures).
- **Code location**: main file `test_append_ledger_*` (3433-4008), `test_ledger_substate_track_scoped_*` (4229-4296), `test_torn_append_leaves_prior_tail_intact` (3544); stub `MinimalLedgerFixture` (123) + `tier=minimal` fixtures (269, 289, 314, 329-330, 346).
- **Actual behavior**: Every claimed test family has an existing precedent function to mirror — append/grammar (3433), last-value-wins (3513), torn-append (3544), reject newline/double-quote/space (3870-3917), allows spaces in categories (3938), track-scoped no-leak (4229-4252), categories-decoy (4273). The additive coverage fits cleanly.
- **Verdict**: CONFIRMED
- **Detail**: Additive coverage fits. The *modification* of the stub's `tier=minimal` fixtures is the under-stated piece — see finding T5.

#### Edge case: old ledger with no `design_gate` (pre-scheme branch) resumes safely
- **Trigger**: A branch predating this change has a ledger carrying `tier=` but none of the four new fields; a resume reads `design_gate` (absent).
- **Code path trace**:
  1. `determine_state_from_ledger` @ precheck:1934 — reads `phase` via `ledger_tail_value`; an absent field yields empty `LEDGER_VALUE`.
  2. Step-1c router reads `design_gate` (absent) — under the design (design.md:157-160, Part-5 edge case at 724-726) this routes to "surface the inconsistency to the user", the same posture the live router takes for an absent `tier` (SKILL.md:229-234, 261).
  3. `ledger_tail_value` for an absent key returns "" (precheck:1776-1778) — no crash.
- **Outcome**: Correct handling — absent `design_gate` reads as the pre-scheme/malformed case and routes to the user-surfaced inconsistency arm, never a silent wrong-state guess.
- **Track coverage**: yes — track-1.md D10 Risks/Caveats ("An old ledger with no `design_gate` inherits the existing absent-`tier` posture … never silently re-derived") and design.md edge case 157-160.

#### Edge case: torn append mid-write under the new fields
- **Trigger**: A crash between the temp-file write and the `mv` rename while appending a line carrying the new bare fields.
- **Code path trace**:
  1. `append_ledger` @ precheck:1683 — validates all fields (1693-1700), builds the line (1715-1726), writes `(old)+(new line)` to `$dir/.phase-ledger.$$.tmp` (1732-1756), then `mv -f` over the ledger (1757).
  2. A crash before the `mv` leaves the prior ledger untouched and an orphan temp (reaped by the `RETURN` trap, 1738).
  3. The next resume's `ledger_tail_value` reads the prior ledger tail — the new field's append is lost, the prior fields intact.
- **Outcome**: Correct — the new fields inherit the existing atomic temp-file+rename guarantee verbatim; no new torn-write surface. `test_torn_append_leaves_prior_tail_intact` (3544) already pins this and the track's added field would ride the same path.
- **Track coverage**: yes — track-1.md `## Invariants & Constraints` ("A torn append leaves the prior ledger tail intact … verified by a precheck test") and Validation/Acceptance "Torn-append safety".

#### Integration: `--tier` write sites vs the precheck flag drop
- **Plan claim**: Plan of Work (1) drops `--tier`; Plan of Work (3) "the ledger-seed call drops `--tier`."
- **Actual entry point**: arg parser `--tier)` case at precheck:171-174; unknown-flag `*)` exit-2 at 191-195.
- **Caller analysis**: grep `--append-ledger.*--tier` / `--tier` across `.claude/workflow` + `.claude/skills` found three live write sites: `create-plan/SKILL.md:1239` (seed, in scope), `create-plan/SKILL.md:250` (Step-1c `minimal` re-seed, in scope, T3), `inline-replanning.md:169` (ESCALATE tier-upgrade, **Track-2-owned, out of scope**). All read sites: `consistency-review.md`, `implementation-review.md`, `structural-review.md`, `create-final-design.md`, `design-review.md`, `inline-replanning.md`, `track-review.md`, `review-plan/SKILL.md:107` (descriptive). `review-plan/SKILL.md:103`, `implementation-review.md:646`, `track-code-review.md:1464`, `step-implementation.md`, `mid-phase-handoff.md`, `track-review.md` all invoke `--append-ledger` **without** `--tier` (unaffected).
- **Breaking change risk**: HIGH for the out-of-scope `inline-replanning.md:169` invocation — it fails `exit 2` after the flag drop until Track 2 re-keys it. MEDIUM for the `create-plan:250` in-scope re-seed if the decomposer follows the Plan-of-Work wording literally (only "the ledger-seed call").
- **Verdict**: CALLERS AT RISK
- **Detail**: Produced findings T1 (out-of-scope `inline-replanning.md`) and T3 (in-scope second `create-plan` site).

#### Integration: Phase-4 carrier table split across in/out-of-scope files
- **Plan claim**: Plan of Work (4) re-keys `workflow.md` §"Final Artifacts (Phase 4)" and `conventions.md` per-axis artifact set to the axis-derived carrier form.
- **Actual entry point**: carrier decision authored in three tables — `workflow.md`:660-664 (+prose 666-730), `conventions.md`:241, `create-final-design.md`:97-99 (the executing hub, design.md:634-637).
- **Caller analysis**: `create-final-design.md` Step 3 (89-106) is the runtime executor that decides which artifacts Phase 4 writes; it reads the tier ledger-first (90-92) and is Track-2-owned. `workflow.md`/`conventions.md` tables are descriptive (read by humans / orchestrator orientation).
- **Breaking change risk**: MEDIUM — no runtime break (the executor stays tier-keyed and coherent within itself until Track 2), but a Track-1-only state has two descriptive tables on the axis-derived form contradicting the still-tier-keyed executor and the tier-keyed `workflow.md` commit-shape prose (666, 685-730).
- **Verdict**: MISMATCHES
- **Detail**: Produced finding T2.

#### Integration: the resume-routing collision (design+single vs mid-authoring crash)
- **Plan claim**: Plan of Work (2) — the Phase-1-complete marker disambiguates the design+single steady state from a mid-authoring crash; the existing committed-and-clean check still applies within the crash arm.
- **Actual entry point**: Step-1c `design.md exists, plan does not` branch (SKILL.md:174-218), which today applies the committed+clean check (191-218) and treats the state as `full`-tier mid-authoring only.
- **Caller analysis**: the branch is reached only on resume after drift/handoff gates clear (133-135); the committed+clean git checks (193-196) are the existing sub-discriminator the track keeps inside the crash arm.
- **Breaking change risk**: LOW — the track adds the Phase-1-complete-marker check *before* the committed+clean check (Plan of Work (2): "the marker check runs first"), which is additive; the new design+single steady state (design.md:679, Part-5 table) is a genuinely new on-disk shape the marker resolves. The branch-structure choice (collapse vs separate) is correctly left to Phase B (design.md:709-716).
- **Verdict**: MATCHES
- **Detail**: The routing contract is sound and the marker is the right disambiguator; the live committed+clean check is correctly retained inside the crash arm. No finding.
