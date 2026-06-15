<!-- MANIFEST
findings: 4   severity: {blocker: 1, should-fix: 2, suggestion: 1}
index:
  - {id: A1, sev: blocker,    loc: "track-1.md:155-157,287-288 / conventions.md:875-877", anchor: "### A1 ", cert: C1, basis: "track stages .claude/scripts/** but the staging convention covers only workflow/skills/agents; script edits land LIVE on the precheck this branch runs, breaking the stated I6 rationale"}
  - {id: A2, sev: should-fix, loc: "track-1.md:71-73 / plan:147-149", anchor: "### A2 ", cert: V1, basis: "D3/D6 cite an existing interrupted-write reconciliation for torn ledger appends; that mechanism reads track-file roster-vs-Progress only and does not apply to the ledger"}
  - {id: A3, sev: should-fix, loc: "track-1.md (whole) / plan:309-311", anchor: "### A3 ", cert: S1, basis: "~8-file track is below the ~12 merge floor with no written sub-floor justification; planning Argumentation gate requires one"}
  - {id: A4, sev: suggestion, loc: "track-1.md:203", anchor: "### A4 ", cert: C2, basis: "Plan-of-Work step 1 'add the ledger to the drift logic ... (not folded into the stamp walk)' is self-contradictory on the contract Track 2 consumes"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 4}
cert_index:
  - {id: C1, verdict: WRONG, anchor: "#### C1 "}
  - {id: V1, verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: S1, verdict: WRONG, anchor: "#### S1 "}
  - {id: C2, verdict: WEAK, anchor: "#### C2 "}
  - {id: A1c, verdict: HOLDS, anchor: "#### A1c "}
flags: [CONTRACT_OK]
-->

# Track 1 adversarial review — iteration 1

Scope: track realization (track-review.md §Track-scoped adversarial review, D9).
Inline design decisions (D1/D2/D3/D5/D6/D9/D10/D13) were vetted at the Phase-0→1
research-log gate and are not re-litigated here. Cross-track-episode reality is
dropped — Track 1 is the first track, no prior episode exists. Reference checks
are workflow file paths and `§`-anchors (grep + Read on the live `.claude/**`;
no staged copy exists yet), per the workflow-machinery criteria. IDE confirmed
open on `no-track-for-minimal` and matching the working tree.

## Findings

### A1 [blocker]
**Certificate**: C1 (Scope/Invariant — staging applies to the script files)
**Target**: Invariant I6 (live workflow stays at develop state) + plan
`### Constraints` D12 claim "Every `.claude/**` edit routes to staged" and "No
live test runs against the staged script"; track `## Context and Orientation`
and `## Interfaces and Dependencies`.
**Challenge**: The track's two load-bearing files —
`.claude/scripts/workflow-startup-precheck.sh` and its two
`.claude/scripts/tests/test_*.py` — are under `.claude/scripts/`, which is
**not a stageable prefix**. `conventions.md §1.7(a)` enumerates exactly three
stageable prefixes (`.claude/workflow/`, `.claude/skills/`, `.claude/agents/`)
and states verbatim: "No other prefixes participate: workflow files outside
`.claude/workflow/`, `.claude/skills/`, and `.claude/agents/` are not stageable
under this convention." The implementer write-routing gate
(`implementer-rules.md` §"Pre-commit gate, live-workflow-path check", lines
388-389) and the read-precedence rule (`§1.7(d)`) are scoped to the same three
prefixes. So the implementer will route the `conventions.md` / `planning.md` /
`workflow.md` / `create-plan/SKILL.md` edits to the staged subtree but will
write the `.sh` script and the `.py` tests **LIVE**. The track asserts the
opposite twice ("All edits route to the staged mirror under
`_workflow/staged-workflow/.claude/**`"; "the new `workflow-startup-precheck.sh`
and its tests are exercised from the staged path, not wired into the live
machinery"). Those statements are mechanically false under the cited convention,
and the plan's `### Constraints` rests an entire bullet ("No live test runs
against the staged script") on the false premise.
**Evidence**: `conventions.md §1.7(a)` "No other prefixes participate"; the
§1.7(b) marker sentence names only the three prefixes; `implementer-rules.md`
lines 257-258, 388-389 route only the three prefixes to staging; no occurrence
of `.claude/scripts/` as a stageable path anywhere in `conventions.md`. History:
the original precheck work (`08810ff0bd`, `059207633f`, `4f7370ea88`) was
authored on live paths and no `staged-workflow/.claude/scripts/` path has ever
existed in any branch (`git log --all -- 'docs/adr/*/_workflow/staged-workflow/.claude/scripts/*'`
returns empty). The live script is invoked at this branch's own session start
(`workflow.md:170` and `execute-tracks/SKILL.md:74` run
`workflow-startup-precheck.sh --mode full`), so editing it live is exactly the
self-destabilization the I6 invariant exists to prevent: this branch's resume
would run the half-rewritten `determine_state` against a ledger that does not
exist for its own (develop-format, plan-carrying) artifacts.
**Proposed fix**: ESCALATE. Resolve the gap before implementation, one of:
(a) extend `§1.7(a)`/(b)/(d)/(e) and `implementer-rules.md` to make
`.claude/scripts/**` a stageable prefix — but that is itself a workflow-prose
change that belongs in the track's in-scope set and must be staged first, and it
widens the §1.6(h) staged-walk-exclusion surface, so it needs its own decision;
or (b) accept that the script + tests land live and rewrite the track's
`## Context and Orientation`, `## Interfaces and Dependencies`, and the plan's
D12 `### Constraints` bullets to state that the script/tests are edited live
while only the four prose docs stage — and add an explicit argument for why
editing the live precheck mid-branch does not break this branch's own `--mode
full` resume (e.g. the new `determine_state` ledger path stays backward-
compatible with a plan-carrying branch that has no ledger, which is the
backward-compat invariant but must be guaranteed for the branch editing itself,
not only for future branches). Either way the contradiction must be removed; an
implementer cannot satisfy both "stage every `.claude/**` edit" and the §1.7(a)
prefix scope.

### A2 [should-fix]
**Certificate**: V1 (Invariant — torn ledger append does not corrupt state)
**Target**: Decision D3 risk ("a torn append must not corrupt state — the atomic
temp-file-plus-rename append plus the existing interrupted-write reconciliation
cover it") and D6 (append-only event log).
**Challenge**: D3 names two mechanisms that "cover" a torn append. The first —
the atomic temp-file-plus-rename — is real and sufficient for a single append
(`mv` is atomic on the same filesystem, so a reader sees either the old tail or
the new tail, never a half-line). The second — "the existing interrupted-write
reconciliation" — does not exist for the ledger. The only interrupted-write
reconciliation in the live script is the `section-discrepancy` State-C sub-state
(`workflow-startup-precheck.sh` lines ~1266, ~1359-1372): it compares a track
file's `## Concrete Steps` roster `[x]` flips against `## Progress` `Step N`
entries and reconciles from the `## Episodes` block. It reads two **track-file**
sections; it has no knowledge of the ledger and cannot detect or repair a torn
ledger append. Citing it as coverage for the ledger is a category error in the
rationale.
**Evidence**: `grep -nE 'interrupted|reconcil'` over the script finds only the
roster-vs-Progress reconciliation; no generic torn-append / last-partial-line
recovery exists (`grep -nE 'tail -1|tac|head -n -1|truncat|partial.?line'`
returns nothing). The atomic append alone is what actually holds the invariant.
**Survival of the decision**: the *decision* (append-only ledger) survives — the
atomic temp-file-plus-rename is genuinely sufficient — so this is should-fix, not
blocker. But the *rationale* points at a mechanism that does not apply, which
will mislead the implementer when they write step 1's append and step 2's
torn-append test ("reuse the existing reconciliation" has nothing to reuse).
**Proposed fix**: Correct the D3 risk line (and the matching plan line 147-149):
drop "the existing interrupted-write reconciliation" and state the actual
guarantee — the atomic temp-file-plus-rename makes each append all-or-nothing, so
a torn append leaves the prior tail intact and `determine_state`'s
last-value-wins read resolves the prior state. The track's Validation bullet
already tests this ("An interrupted (torn) append leaves the prior ledger tail
intact"); the rationale should describe the mechanism that test actually
exercises.

### A3 [should-fix]
**Certificate**: S1 (Scope — sub-floor track lacks the required justification)
**Target**: Track sizing (planning.md §Track descriptions, Argumentation gate).
**Challenge**: The track is ~8 in-scope files — below the ~12 merge floor — and
Track 2 depends on it, so it is a merge candidate under the two-sided bound.
`planning.md` §"Argumentation gate" requires: "A track must carry a written
justification in its track file when it is out of bounds on either side: under
the floor (≤~12 in-scope files that folds into a neighbor)... A documented
out-of-bounds track passes planning autonomously; an undocumented one is a
`design-decision` finding at Phase 2 review and escalates." No such justification
exists. A grep over both the track file and the plan for any fold / not-merged /
stand-alone / sub-floor rationale finds nothing — only the bare `> **Scope:** ~8
files` note in the Checklist. The spawn brief and the branch memory both assert
"a written sub-floor justification" exists; it does not.
**Evidence**: `planning.md` lines 604-614 (the Argumentation gate);
`grep -niE 'fold|not.*merg|separate.*track|stand.?alone|sub-floor|merge floor'`
over `track-1.md` and `implementation-plan.md` returns no folding/sub-floor
rationale. The Plan-of-Work tail invariant and the `## Interfaces and
Dependencies` "Inter-track dependency" line state *that* Track 2 depends on
Track 1, but never *why* the two are not one PR.
**Survival**: the split is sound on the merits — Track 1 = the model
definition + producer (script, conventions, planning, create-plan), Track 2 =
the consumer rewire (the prompts and the runtime readers), a clean
producer/consumer dependency boundary at ~8 + ~13 files. So the decision
survives; only the *written record* the gate demands is missing. should-fix.
**Proposed fix**: Add one sentence to the track file (the §Argumentation gate
says the justification lives in the track file, not the plan) stating why Track 1
is not folded into Track 2 — e.g. "Track 1 stops at ~8 files rather than folding
into Track 2 because it publishes the ledger grammar, the per-tier artifact set,
and the 15th-section template that Track 2's ~13 consumer files all read; merging
the producer and every consumer into one PR would push the combined footprint to
~21 files, over the ceiling, and lose the independently-reviewable
producer/consumer boundary." A natural home is the `## Plan of Work` tail
alongside the existing resume invariant, or a `## Invariants & Constraints`
note once that section exists.

### A4 [suggestion]
**Certificate**: C2 (Simplification — the step-1 contract wording is
self-contradictory)
**Target**: Plan-of-Work step 1, and the contract Track 2 consumes (D13 /
§1.6(f)).
**Challenge**: Step 1 reads "Add the ledger to the drift logic as an unstamped
artifact (it is not folded into the §1.6(h) stamp walk)." "Add to the drift
logic" and "not folded into the stamp walk" pull in opposite directions on the
one decision Track 2 and the conformance fixture both depend on. The actual
mechanic, verified against the live code, is the second clause: D13 / §1.6(f)
put the ledger on the **exclusion** list, so none of the three four-type stamp
walks (`workflow-startup-precheck.sh` lines 391, 488, 689) change, the §1.6(h)
byte-source block is untouched, and the conformance fixture's `EXPECTED_GLOB_TAILS`
(`test_workflow_startup_precheck.py` ~line 2947) stays at exactly four globs and
green. The ledger needs **no** affirmative drift-logic edit; it just stays out of
the walk. The "add to the drift logic" phrasing risks the implementer adding a
fifth glob or a ledger branch to a walk, which would trip the conformance fixture
and (per §1.6(f)) require editing all three walk sites plus the fixture — the
exact change the §1.7 S1 staging invariant forbids on this branch.
**Evidence**: `conventions.md §1.6(f)` exclusion rationale (the research-log
precedent it copies says growing the stamped set means editing all three walks
+ the fixture, "which §1.7 staging's S1 invariant forbids on this branch");
the conformance fixture pins `EXPECTED_GLOB_TAILS` to four entries.
**Survival**: the decision (ledger unstamped, excluded from the walk) survives
and is correctly threaded by the design — this is a wording/realization risk on
the implementer-facing step text, not a design defect. suggestion.
**Proposed fix**: Reword step 1's clause to "Record the ledger on the §1.6(f)
exclusion list so the §1.6(h) stamp walk does not enumerate it (no walk-glob
edit, no conformance-fixture change); the `track-1.md` anchor keeps drift
detection intact." This removes the apparent instruction to touch a walk.

## Evidence base

#### C1 Challenge: staging scope vs the track's `.claude/scripts/**` footprint — MATCHES
- **Chosen approach**: The track routes every edit — including
  `workflow-startup-precheck.sh` and its two `.py` tests under
  `.claude/scripts/` — to `_workflow/staged-workflow/.claude/**`, and the plan's
  D12 `### Constraints` asserts "Every `.claude/**` edit routes to staged" and
  "No live test runs against the staged script."
- **Best rejected alternative**: edit the script + tests live and stage only the
  four workflow-prose docs (the only stageable prefixes).
- **Counterargument trace**:
  1. The implementer write-routing gate keys on the §1.7(b) marker and routes
     only `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**` writes
     to staging (`implementer-rules.md` lines 388-389; the same three-prefix list
     across §1.7).
  2. `.claude/scripts/` matches none of those prefixes; `§1.7(a)` says verbatim
     "No other prefixes participate... not stageable under this convention."
  3. So the implementer writes the `.sh` and `.py` files to **live** paths. This
     branch's own session-start `--mode full` (`workflow.md:170`,
     `execute-tracks/SKILL.md:74`) then runs the half-rewritten `determine_state`
     against its own develop-format, plan-carrying, ledger-less artifacts — the
     destabilization I6 exists to prevent.
- **Codebase evidence**: `conventions.md` §1.7(a) "No other prefixes
  participate"; §1.7(b) marker names only three prefixes; `implementer-rules.md`
  257-258 / 388-389; `git log --all -- 'docs/adr/*/_workflow/staged-workflow/.claude/scripts/*'`
  empty; original precheck commits authored live (`08810ff0bd` et al.).
- **Survival test**: NO — the chosen approach as written is mechanically
  unrealizable; the contradiction must be resolved before implementation.

#### V1 Violation scenario: a torn ledger append corrupts resume state
- **Invariant claim**: "An interrupted (torn) append leaves the prior ledger tail
  intact and `determine_state` resolves the prior state" (Validation bullet),
  rationale "the atomic temp-file-plus-rename append plus the existing
  interrupted-write reconciliation cover it" (D3).
- **Violation construction**:
  1. Start state: ledger with a valid tail; orchestrator calls `--append-ledger`.
  2. Action sequence: the append crashes mid-write. With the atomic
     temp-file-plus-rename, the live file is untouched (the partial write is in
     `.tmp`); the invariant holds via mechanism one.
  3. Intermediate state: an orphan `.tmp` may exist; the live ledger tail is the
     prior valid value.
  4. Violation point: the *rationale's second mechanism* — "the existing
     interrupted-write reconciliation" — is not invoked and does not exist for
     the ledger (`workflow-startup-precheck.sh` ~1266/~1359 reconciliation reads
     `## Concrete Steps` vs `## Progress` of a track file, not the ledger).
  5. Observable consequence: none on state correctness (mechanism one carries it),
     but the rationale misdirects the implementer toward a non-existent reuse and
     the orphan `.tmp` is not cleaned by any ledger-specific path.
- **Feasibility**: CONSTRUCTIBLE for the rationale defect; the state-corruption
  itself is INFEASIBLE precisely because the atomic rename (not the cited
  reconciliation) prevents it — which is the point: the decision survives on a
  mechanism the rationale does not name, and names one that does not apply.

#### S1 Challenge: sub-floor track without the gate-required justification — MATCHES
- **Chosen approach**: a ~8-file Track 1 that Track 2 depends on, with no written
  fold/sub-floor justification.
- **Best rejected alternative**: either fold Track 1 into Track 2 (one ~21-file
  PR), or keep the split and write the justification the gate demands.
- **Counterargument trace**:
  1. `planning.md` Argumentation gate: a ≤~12-file track that folds into a
     neighbor must carry a written justification in its track file.
  2. The track file carries none (grep finds only the bare `~8 files` scope note).
  3. Per the gate, an undocumented out-of-bounds track is a `design-decision`
     finding that escalates — this Phase-3A pass surfaces it now.
- **Codebase evidence**: `planning.md` 604-614; absence confirmed by
  `grep -niE 'fold|not.*merg|stand.?alone|sub-floor|merge floor'` over both files.
- **Survival test**: WEAK — the split is correct on producer/consumer +
  ceiling-arithmetic grounds, but the written record the gate requires is missing,
  so the rationale needs to be added rather than the decision changed.

#### C2 Challenge: "add the ledger to the drift logic" vs "not folded into the walk" — WEAK
- **Chosen approach**: step 1 says both "Add the ledger to the drift logic as an
  unstamped artifact" and "(it is not folded into the §1.6(h) stamp walk)."
- **Best rejected alternative**: state only the exclusion (the ledger stays off
  the walk; no affirmative walk edit).
- **Counterargument trace**:
  1. D13 / §1.6(f) place the ledger on the exclusion list, so the three stamp
     walks and the §1.6(h) byte-source are unchanged.
  2. The conformance fixture pins `EXPECTED_GLOB_TAILS` to four globs; any walk
     edit fails it and (per §1.6(f)) would force edits to all three walks + the
     fixture — forbidden by the §1.7 S1 staging invariant on this branch.
  3. "Add to the drift logic" reads as an instruction to touch a walk; the
     correct realization touches nothing in the walk.
- **Codebase evidence**: `test_workflow_startup_precheck.py` `EXPECTED_GLOB_TAILS`
  (~2947), four entries; §1.6(f) exclusion rationale.
- **Survival test**: WEAK — decision is correct and the design threads it right;
  only the step's implementer-facing wording invites a wrong realization.

#### A1c Assumption test: backward-compat resume holds for existing lite/full plans — HOLDS
- **Claim**: the new `determine_state` ledger path does not regress resume for an
  existing in-flight `lite`/`full` plan that still carries a `## Checklist`.
- **Stress scenario**: an in-flight branch with a stamped `implementation-plan.md`
  carrying `## Plan Review [x]` and a `## Checklist`, no ledger, resumes after the
  script is updated.
- **Code evidence**: today `determine_state` (lines 1439-1559) drives State 0 from
  `## Plan Review`, walks the `## Checklist` for the first `[ ]` track, and
  computes the State-C sub-state from the track file's `## Progress` + `##
  Concrete Steps` (the two-level lookup the plan's backward-compat invariant
  pledges to keep). The rewrite must preserve this branch when no ledger is
  present. D3/D10 keep the two-level rule and route `lite`/`full` on plan
  presence, so a ledger-less existing plan stays on the current path.
- **Verdict**: HOLDS — *provided* the rewrite keeps the no-ledger fallback to the
  current Checklist walk. This is the load-bearing implementation contract for
  A1's fix option (b): the same fallback must protect this branch's own resume,
  since its artifacts are develop-format and ledger-less. Recorded as the
  assumption A1 leans on; no separate finding.
