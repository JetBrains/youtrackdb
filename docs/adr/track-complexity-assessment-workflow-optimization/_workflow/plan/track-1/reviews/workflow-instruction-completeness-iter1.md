<!--MANIFEST
review: workflow-instruction-completeness
track: 1
iteration: 1
findings: 1
evidence_base: 1
cert_index: 1
flags: []
index:
  - id: WI1
    sev: Recommended
    anchor: "### WI1 "
    loc: ".claude/workflow/planning.md (staged) line 173"
    cert: C1
    basis: judgment
-->

# Workflow instruction-completeness review — Track 1, iteration 1

## Findings

### WI1 [Recommended] — `planning.md` promises a Phase-1 ledger write of `reconciled_tag` that no producer performs

- **File:** `.claude/workflow/planning.md` (staged copy
  `…/_workflow/staged-workflow/.claude/workflow/planning.md`), line 173; the
  paired glossary/table claim at `…/conventions.md`-cited
  `planning.md §Tier classification` table, staged line 122.
- **Axis:** phase output → next-phase input.
- **Cost:** an emitted-key promise with no producer — a planner following
  `planning.md` literally looks for a Phase-1 `--reconciled-tag` seed step that
  does not exist (or invents one), diverging from the frozen design, which
  assigns `reconciled_tag`'s sole writer to Phase A.
- **Issue:** Step 4's `planning.md` edit adds the per-track complexity-tag
  prediction instruction. Its body is self-contradicting across two adjacent
  sentences. Lines 165–172 state correctly that Phase A reconciles the tag and
  is "*writing the reconciled value to the ledger at the Phase A → C
  boundary*"; line 173 then adds "*The prediction seeds the ledger's per-track
  `reconciled_tag` field at Phase 1 (the reconciliation overwrites it).*" The
  §Tier-classification axis table (line 122) likewise lists the tag as
  "*predicted per track at Phase 1*" persisted as "*ledger per-track
  `reconciled_tag`*". No producer writes `reconciled_tag` at Phase 1: the
  Step-4 create-plan ledger-seed call
  (`create-plan/SKILL.md` staged lines 1282–1288) emits `--design-gate`,
  `--tracks`, `--phase1-complete`, `--categories` and no `--reconciled-tag`,
  and that seed is a single change-level append with `--phase 0` and **no
  `--track`** — so a per-track tag (which must ride its own `track=` line for
  the track-scoped `ledger_tail_value_for_track` read) cannot attach to it.
  The frozen `design.md` (ledger Data-model table, line 120) states the
  per-track reconciled tag is "*Written at the A→C boundary*", which is Track
  2's reconciliation, the field's single producer. The "seeds at Phase 1"
  claim is therefore both unbacked by any write site and in direct conflict
  with the design contract this track implements.
- **Suggestion:** drop the line-173 "*seeds the ledger's per-track
  `reconciled_tag` field at Phase 1*" sentence and re-key the line-122 table
  cell so the tag is described as *predicted at Phase 1 and recorded in the
  track file, then written to the ledger (per-track, track-scoped) only at the
  Phase A → C reconciliation* — matching design.md line 120 and the prediction
  paragraph's own lines 165–172. The Phase-1 prediction stays in the track
  file (its declared Phase-A consumer is Track 2 scope); only the spurious
  Phase-1 *ledger*-write promise is removed. No code change is needed in this
  track — the create-plan seed call is already correct in omitting
  `--reconciled-tag`.

## Evidence base

#### C1 — `reconciled_tag` has no Phase-1 producer; the only writer is Phase A (refuted: the planning.md claim does not hold)

The completeness check traced every producer of the `reconciled_tag` key
against the consumer-facing claim that it is "seeded at Phase 1".

- Producer search, staged tree: `grep -rn -- '--reconciled-tag'` over
  `_workflow/staged-workflow/.claude` returns only (a) the script's own flag
  definition and usage-text lines in `workflow-startup-precheck.sh` (lines 21,
  160, 209) and (b) test fixtures in `test_workflow_startup_precheck.py`. No
  workflow prompt or skill emits `--reconciled-tag` from a Phase-1 step.
- The Phase-1 producer that *would* carry it — the create-plan Step-4 ledger
  seed (`create-plan/SKILL.md` staged lines 1282–1288) — emits
  `--design-gate`, `--tracks`, `--phase1-complete`, `--categories` only, with
  `--phase 0` and no `--track`. A per-track field cannot ride a change-level,
  track-less append, since the track-scoped reader
  (`ledger_tail_value_for_track <key> <track>`) keys on a matching `track=`
  token on the same line.
- The frozen `design.md` (ledger Data-model table, line 120) is explicit:
  per-track reconciled tag "*Written at the A→C boundary; read by Phase C for
  rigor and by Phase 4 for the `adr.md` predicate.*" — single producer, Phase
  A, which is Track 2's scope.
- Conclusion: the `planning.md` line-173 "seeds at Phase 1" sentence (and the
  line-122 table phrasing) describe a Phase-1 ledger write with no producer
  and contradict the design's single-writer-at-A→C contract. The defect is in
  the staged `planning.md` prose, not in the (correct) create-plan seed call.
  This is a phase-output→phase-input orphan: a promised emitted key with no
  emitter at the named phase.

Cross-checks that passed (compressed, one line each — no finding):

- Resume routes every artifact combination — the design.md-present branch fans
  out on `phase1_complete` (set ⇒ steady state, lines 798–802; unset ⇒ crash
  arm, lines 803–843, whose committed-clean→Step 4b / dirty→Step 4a sub-arms
  are both written), plan-present and plan-less-resume and both-files and
  fresh-start arms all terminate at a defined destination (delta lines
  844–934); the marker-unset complement is present, not just the marker-set
  case.
- `determine_state_from_ledger` empty-`track`→1 default keys on an empty
  `track=` token with no tier guard (staged precheck lines 2015–2019), so it
  serves the design+single and no-design-single shapes identically and stays
  correct after the `tier=` removal.
- `determine_state_from_ledger` consumes only `phase`/`track`, never the four
  new fields — matching Step 1's schema-only scope; the new fields' consumers
  (`design_gate`/`phase1_complete`/`tracks` in create-plan Step 1c and the
  Phase-2 prompts) are all within this track's deliverables; `reconciled_tag`
  is the one declared cross-track interface (produced here, consumed by Track
  2), not an orphan.
- Forward-obligation enumeration is complete: a live-tree grep for every
  `--tier` writer and ledger-`tier` reader finds exactly the four named in
  §Interfaces and Dependencies — `inline-replanning.md` (writer, live line
  169), `track-review.md` §Tier-driven review selection (reader, live lines
  484/624), `create-final-design.md` (reader, live lines 39–42/89–90),
  `design-review.md` (`tier=full`, live line 235). `review-plan/SKILL.md`,
  `mid-phase-handoff.md`, `track-code-review.md`, `step-implementation.md`
  append the ledger without `--tier` and carry no ledger-`tier` read;
  `implementation-review.md` is in-scope and already re-keyed in the staged
  copy. No fifth live reader/writer the removal breaks sits outside Track 2's
  list — the cross-track discharge is correctly not a finding.
- A stray `--tier` invocation after the flag drop hits the precheck `*)
  Unknown argument` arm and `exit 2`, and an old ledger with no `design_gate`
  inherits the absent-field-surfaced-to-user posture (track file §Decision Log
  D10, create-plan Step-1c "Both files exist" arm + degenerate fallback) —
  both error paths defined.

## Evidence sources

- Delta: `/tmp/claude-code-track-1-delta-6989.txt` (all 2488 lines paged).
- Staged tree:
  `…/_workflow/staged-workflow/.claude/{scripts,skills,workflow}` (grep + Read).
- Live tree: `.claude/{scripts,skills,workflow}` (grep, for the
  forward-obligation enumeration — the post-promotion target surface).
- Frozen `…/_workflow/design.md` ledger Data-model table (lines 110–135).
- Track file `…/_workflow/plan/track-1.md` §Validation and Acceptance,
  §Invariants & Constraints, §Interfaces and Dependencies.
