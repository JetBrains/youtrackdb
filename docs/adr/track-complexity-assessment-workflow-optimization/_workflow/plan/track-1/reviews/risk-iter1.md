<!--MANIFEST
role: reviewer-risk
phase: 3A
track: "Track 1: Ledger schema, resume routing, and Phase-1 artifact existence"
iteration: 1
verdict: PASS
findings: 3
blockers: 0
index:
  - id: R1
    sev: should-fix
    anchor: "### R1 [should-fix]"
    loc: "track-1.md §Plan of Work (1) + §Interfaces (Out of scope); inline-replanning.md:169, track-review.md:625, create-final-design.md:42/90, design-review.md:235"
    cert: "Exposure: tier= removal vs Track-2-owned live ledger readers"
    basis: "grep over .claude/** for live --tier writes / ledger tier reads + conventions.md §1.7(g) I6 invariant"
  - id: R2
    sev: should-fix
    anchor: "### R2 [should-fix]"
    loc: "track-1.md §Plan of Work (1); design.md:143-146; precheck.sh ledger_tail_value:1779-1805"
    cert: "Assumption: design_gate must precede categories because embedded spaces end the bare-token scan early"
    basis: "Read of ledger_tail_value reader logic (first-match token, not a left-to-right space-terminated scan)"
  - id: R3
    sev: suggestion
    anchor: "### R3 [suggestion]"
    loc: "track-1.md §Plan of Work (2); precheck.sh determine_state_from_ledger:1960-2001; create-plan/SKILL.md Step 1c:174-218"
    cert: "Testability: the new design+single resume collision lives in create-plan prose, not the precheck unit suite"
    basis: "Read of determine_state_from_ledger (script, unit-testable) vs Step-1c router (prose, not exercised by the precheck tests)"
evidence_base:
  exposure: 2
  assumption: 2
  testability: 2
-->

# Track 1 — Risk review (iteration 1)

## Findings

### R1 [should-fix]
**Certificate**: Exposure — `tier=` removal vs the Track-2-owned live ledger readers (Evidence base → Exposure 1).
**Location**: `track-1.md` §Plan of Work step (1) and §Interfaces "Out of scope (Track 2 owns these)"; the live readers `inline-replanning.md:169`, `track-review.md:625`, `create-final-design.md:42`/`:90`, `design-review.md:235`.
**Issue**: Track 1 drops `--tier` from the script's `--append-ledger` flag surface and the ledger grammar. Five live runtime sites outside Track 1's in-scope set still write or read that field: `inline-replanning.md:169` invokes `--append-ledger --tier <new-tier>` (a flag the dropped parser arm would reject with the `*) Unknown argument` → `exit 2` path at `precheck.sh:191-195`), and `track-review.md:625`, `create-final-design.md:42/90`, and `design-review.md:235` read the ledger `tier` field for review/artifact selection. The plan assigns all five to Track 2's out-of-scope list, so the *intent* is correct — but the §1.7(g) I6 invariant is what actually neutralizes the blast radius, and the track file never says so. Because both tracks stage their `.claude/**` edits under `_workflow/staged-workflow/` and the live tree stays at develop until one atomic Phase-4 promotion (conventions.md §1.7(g): "Promotion at Phase 4 is the only intra-branch authoring transition"), the live `--tier`-rejecting script and the live `--tier`-using `inline-replanning.md` never coexist in a running workflow — they promote together. The residual risk is real but narrow: the *staged* subtree must be internally consistent at promotion. If Track 2 fails to re-key any of these five sites in the staged copies, the single promotion lands a half-broken live workflow (an ESCALATE tier-upgrade replan would call a removed flag; a Phase-4 final-design run would read an absent `tier` field). Track 1's track file documents the staging discipline (lines 381-387) but carries no cross-track promotion-consistency obligation naming these five sites as Track 2's discharge condition.
**Proposed fix**: Add one sentence to Track 1's §Interfaces "Out of scope" block (or its §Invariants) recording the contract: removing `tier=` from the schema obligates Track 2 to re-key every live ledger `tier` reader/writer in the staged subtree before the Phase-4 promotion — explicitly `inline-replanning.md` (`--tier` append + the two prose reads), `track-review.md`, `create-final-design.md`, and `design-review.md` — so the promoted live tree is never half-applied. No code change in Track 1; this is a documented hand-off so Track 2's completeness is auditable against a named set rather than re-discovered.

### R2 [should-fix]
**Certificate**: Assumption — the stated reason for ordering `design_gate` before `categories` (Evidence base → Assumption 1).
**Location**: `track-1.md` §Plan of Work step (1): *"a bare field must precede the quoted `categories` field so the quoted value's embedded spaces do not end the bare-token scan early."* Compare `precheck.sh` `ledger_tail_value`:1779-1805 and the existing emit-order comment at :1784-1789.
**Issue**: The ordering *conclusion* (emit `design_gate` in the pre-`categories` block) is correct, but the *justification* the track file gives is not how the reader works. `ledger_tail_value` does not scan tokens left-to-right and stop at the first space; it takes the **first** `" $key="` substring match on the line and reads the value up to the next space (bare) or the next `"` (quoted). Embedded spaces inside `categories` do not "end a scan early" — there is no running scan to end. The real hazard the live comment documents (:1784-1789) is different: a reader-consumed key emitted *after* `categories` could match a same-named **decoy substring inside** the quoted value (e.g. a literal ` design_gate=` appearing inside `categories="…"`), letting the decoy win. An implementer who codes to the track file's stated (wrong) reason might (a) place the field correctly but for a reason that does not generalize, or (b) reason that a bare field with no spaces is safe *after* `categories` — which is exactly the unsafe case the true invariant forbids. The same mis-statement could leak into the file-header grammar comment the step rewrites.
**Proposed fix**: Restate the ordering rationale to match the live invariant: every reader-consumed bare key must be emitted *before* the one quoted `categories` field so the first `" key="` match is always the real bare token and never a same-named decoy embedded inside the quoted value (precheck.sh:1784-1789). Apply the corrected wording both in the track-file step text and in the file-header grammar comment the step touches.

### R3 [suggestion]
**Certificate**: Testability — the design+single resume collision lives in create-plan prose, not the precheck unit suite (Evidence base → Testability 1).
**Location**: `track-1.md` §Plan of Work step (2) and §Validation "The collision is resolved"; `precheck.sh` `determine_state_from_ledger`:1960-2001 (script, unit-testable) vs `create-plan/SKILL.md` Step 1c:174-218 (prose, not exercised by the precheck tests).
**Issue**: The load-bearing acceptance item — a `design.md` + no plan + one track file routes to the steady state when the Phase-1-complete marker is set and to crash-recovery when unset — is resolved in the **Step-1c router prose** (`create-plan/SKILL.md`), not in the script. The precheck test suite (`test_workflow_startup_precheck.py`) can round-trip the new ledger fields, assert loud-reject, last-value-wins, the track-scoped read, and torn-append (all via `run_precheck("--append-ledger", …)` and the stub's `write_ledger`), and it can cover `determine_state_from_ledger`'s re-keyed State-C arm. It cannot exercise the prose-level collision resolution, because Step 1c is instructions to an LLM, not code. So the §Validation "collision is resolved" line and the §Invariants "routes every artifact combination … never a dead end" line have no automated backstop for the prose half — they rest on the consistency/structural review and human reading. This is inherent to a prose router, not a defect, but the track file presents the collision acceptance as if it were uniformly test-verified.
**Proposed fix**: When Phase A decomposes, scope the precheck test obligations to what the script actually decides (the schema round-trip, loud-reject, last-value-wins, track-scoped no-leak, torn-append, and the `determine_state_from_ledger` re-key), and mark the Step-1c collision resolution as a prose-review acceptance item (consistency-review / structural-review coverage) rather than a unit-test target — so the decomposition does not write a step that promises a test the precheck suite structurally cannot host.

## Evidence base

### Exposure 1: `tier=` schema removal vs Track-2-owned live ledger readers/writers
- **Track claim**: Step (1) drops `tier=` from the `--append-ledger` accumulators, the validation block, the line builder, and the file-header grammar; §Interfaces lists `inline-replanning.md`, `track-review.md`, `create-final-design.md`, `design-review.md` as Track-2-owned (out of scope).
- **Critical path trace**:
  1. Live writer — `inline-replanning.md:169`: `workflow-startup-precheck.sh --append-ledger --tier <new-tier>` (the ESCALATE tier-upgrade ledger append).
  2. `precheck.sh` arg parser :138-197 — after Track 1 drops the `--tier)` case, `--tier` falls to `*) echo "Unknown argument: $1"; usage; exit 2` (:191-195).
  3. Live readers — `track-review.md:625` (Phase-A tier read), `create-final-design.md:42`/`:90` (Phase-4 carrier selection reads ledger `tier`), `design-review.md:235` (`tier=full` fidelity key). Each resolves an absent field after removal.
- **Blast radius**: in a *running* live workflow, an ESCALATE replan and any Phase-4 final-design selection that read/write `tier`. Scope (verified by `grep -rE` over `.claude/**` excluding `_workflow/` and `.py`): exactly the five sites above plus Track-1's in-scope `create-plan/SKILL.md` and the script itself.
- **Existing safeguards**: conventions.md §1.7(g) I6 invariant (lines 1217-1240) — both tracks stage every `.claude/**` edit under `_workflow/staged-workflow/`; the live tree stays at develop state until one atomic Phase-4 promotion ("no other commit on the branch carries live-path writes when the convention is followed"). Track-1 track file lines 381-387 affirm §1.7 staging and that the executable `.claude/scripts/**` edits block the §1.7(k) opt-out. So Track 1 and Track 2 are tracks of one branch and go live together; the live `--tier`-rejecting script never coexists with a live `--tier` caller.
- **Residual risk**: MEDIUM → the safeguard reduces it to a staged-subtree-consistency obligation: if Track 2 leaves any of the five staged copies un-re-keyed, the single promotion lands a half-applied live workflow. Track 1's file does not name this hand-off, so Track 2's completeness has no auditable target. → R1.
- **Reference-accuracy note**: this is workflow-prose + Bash, no Java; the five-site set was found by grep over workflow paths and confirmed by reading each match, per the branch's prose-criteria lens (no PSI applicable).

### Exposure 2: atomic append + last-value-wins for the new fields
- **Track claim**: each new bare field gets a `reject_bad_ledger_value … bare` call and a `[ -n "$LEDGER_X" ] && line="$line X=…"` builder line; the per-track tag is read with `ledger_tail_value_for_track`; torn-append leaves the prior tail intact.
- **Critical path trace**:
  1. `append_ledger`:1683-1761 — validates all fields (:1693-1700), builds the line, writes to `$dir/.phase-ledger.$$.tmp`, `mv -f`'s over the ledger (same-dir rename = atomic on POSIX), RETURN trap reaps the temp.
  2. `ledger_tail_value`:1774-1806 — whole-file last-value-wins, first `" $key="` match per line.
  3. `ledger_tail_value_for_track`:1828-1863 — same, scoped to lines whose `track=` equals the active track.
- **Blast radius**: a torn write or a leaked prior-track tag would mis-resolve resume state; but the existing primitives already give atomic rename + track-scoping, and the new bare fields slot into the identical mechanism the existing `phase`/`track`/`substate` bare fields use.
- **Existing safeguards**: the temp-file+rename, the RETURN-trap reaper, the loud-reject validator, and the track-scoped reader are all already present and exercised by `test_torn_append_leaves_prior_tail_intact` and the stub `write_ledger` round-trip tests.
- **Residual risk**: LOW — the new fields reuse a proven primitive verbatim; the only field-specific care is emit order (Assumption 1 / R2) and the per-track-tag using the track-scoped reader (already an invariant in the track file).

### Assumption 1: `design_gate` must precede `categories` "so embedded spaces do not end the bare-token scan early"
- **Track claim**: §Plan of Work (1): *"a bare field must precede the quoted `categories` field so the quoted value's embedded spaces do not end the bare-token scan early."*
- **Evidence search**: Read of `ledger_tail_value`:1779-1805 and `ledger_tail_value_for_track`:1833-1862 (the only two ledger readers), plus the live emit-order comment :1784-1789. Grep + Read, no PSI (Bash, not Java).
- **Code evidence**: the reader takes `case " $line" in *" $key="*)` — the **first** match — then `rest="${line#*" $key="}"` and `val="${rest%% *}"` (bare) or `val="${rest%%\"*}"` (quoted). There is no left-to-right token walk that a space could terminate; embedded spaces in `categories` are irrelevant to where the value for an *earlier* bare key is read. The live comment states the real invariant explicitly: a key emitted *after* `categories` "would let a same-named decoy inside the quoted value win, so keep every reader-consumed key ahead of it" (:1787-1789).
- **Verdict**: CONTRADICTED (the stated reason); the conclusion (place it before `categories`) is correct under the *true* reason.
- **Detail**: the hazard is a same-named substring decoy inside the quoted value, not a premature scan termination. An implementer coding to the wrong reason risks placing a future reader-consumed key after `categories` on the belief that a space-free bare value is safe there. → R2.

### Assumption 2: an old ledger with no `design_gate` routes safely (backward-compat)
- **Track claim**: D10 Risks/Caveats and design.md:157-160 — an absent `design_gate` on an old ledger "inherits the existing absent-`tier` posture: routed to the normal both-files resume with the missing field surfaced to the user, never silently re-derived."
- **Evidence search**: Read of `determine_state_from_ledger`:1934-2010 (empty-phase → State 0 default; the State-C arm defaults track to 1 on an empty `track`; an empty `substate` falls back to the roster) and Step 1c:229-234/256-271 (the `full`/absent-tier arm surfaces the inconsistency; the fresh-start arm fires on an absent/empty/unreadable signal). Grep + Read, no PSI.
- **Code evidence**: the script's resume reads already treat an absent value as a defined posture (empty `phase` → State 0; empty `track` → track 1; empty `substate` → roster fallback). The Step-1c prose already has a "surface the inconsistency" arm for an absent/unreadable tier and a fresh-start catch-all. Re-keying these onto `design_gate` / the track-count signal / the marker preserves the same absent-value defaults.
- **Verdict**: VALIDATED — the backward-compat posture the design asserts is consistent with the existing absent-value handling; the re-key inherits it as long as the new arms keep an explicit absent-value branch (the track file's "never a dead end" invariant requires this).
- **Detail**: no finding; recorded as evidence that the backward-compat claim is grounded.

### Testability 1: the design+single resume collision is prose, not script
- **Coverage target**: 85% line / 70% branch (script + test files).
- **Difficulty assessment**: the schema round-trip, loud-reject, last-value-wins, track-scoped no-leak, torn-append, and the `determine_state_from_ledger` re-key are all in the Bash script and are exercised by the Python suite via `run_precheck("--append-ledger", …)` and the stub's `write_ledger`. But the load-bearing collision resolution — marker-set ⇒ steady state vs marker-unset ⇒ crash-recovery — lives in `create-plan/SKILL.md` Step 1c **prose** (instructions to an LLM), which the precheck unit suite cannot execute.
- **Existing test infrastructure**: `test_workflow_startup_precheck.py` (`run_precheck` subprocess harness, `test_torn_append_leaves_prior_tail_intact`, the State-C substate tests :2620-3035) and `..._stub.py` (`write_ledger` synthesizes ledger lines verbatim; the plan-less `minimal` resume tests :261-322). Both already cover the script's ledger surface and extend naturally to the new fields.
- **Feasibility**: ACHIEVABLE for the script half; the prose half is INFEASIBLE to unit-test by construction and must rest on the consistency/structural reviews.
- **Detail**: the gap is inherent to a prose router, not a coverage shortfall in code. → R3 (scope the decomposition's test obligations to the script; route the prose collision to review acceptance).

### Testability 2: the per-field validation and round-trip slot into the existing test pattern
- **Coverage target**: 85% line / 70% branch.
- **Difficulty assessment**: low. Each new bare field is appended and validated through the same `reject_bad_ledger_value … bare` + builder-line path the existing `phase`/`track`/`substate` fields use; the round-trip and loud-reject assertions copy the shape of `test_append_ledger_writes_pinned_grammar` and `test_append_ledger_last_value_wins_on_read`.
- **Existing test infrastructure**: `run_precheck(*args)` passes arbitrary flags through to the subprocess (:101-119), so the new `--design_gate` / track-count / marker / per-track-tag flags need no harness change; the stub `write_ledger` synthesizes lines for read-path tests.
- **Feasibility**: ACHIEVABLE.
- **Detail**: no finding; recorded as evidence that the schema-delta steps are straightforwardly testable.
