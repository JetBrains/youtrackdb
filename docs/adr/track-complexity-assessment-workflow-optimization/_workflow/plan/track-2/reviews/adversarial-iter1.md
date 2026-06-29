<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 3, suggestion: 2}
index:
  - {id: A1, sev: should-fix, loc: "execute-tracks/SKILL.md:109, review-workflow-consistency.md:63, review-workflow-instruction-completeness.md:24, dimensional-review-gate-check.md:43", anchor: "### A1 ", cert: "Scope-1", basis: "four out-of-scope live files carry removed-agent / retired-BC references the track's five-mirror-site grep invariant does not cover"}
  - {id: A2, sev: should-fix, loc: "inline-replanning.md:141-195, :169", anchor: "### A2 ", cert: "Scope-2", basis: "the whole D11/D12 tier-upgrade mechanism is built on a single-tier model the unbundling dissolves; the literal --append-ledger --tier write now exit-2s — Plan of Work step (6) names neither"}
  - {id: A3, sev: should-fix, loc: "track-2.md Invariants & Constraints (no-dangling-roster bullet)", anchor: "### A3 ", cert: "Violation-1", basis: "the no-dangling-reference invariant is scoped to the five selection mirror sites, so a dangling ref in an out-of-scope file is INFEASIBLE to catch by the stated verification (grep + consistency review over five sites)"}
  - {id: A4, sev: suggestion, loc: "track-2.md D9 / risk-tagging.md:181 Gate 1 reuse", anchor: "### A4 ", cert: "Assumption-1", basis: "D9 introduces a THIRD granularity (track-level) for the same seven triggers already read at change-level (Gate 1) and step-level (risk tag); the prediction quality assumption holds but the triple-granularity coherence deserves a one-line rationale"}
  - {id: A5, sev: suggestion, loc: "track-2.md Plan of Work step (2), track-review.md:599-601 A->C append", anchor: "### A5 ", cert: "Violation-2", basis: "reconciliation must write --reconciled-tag onto the SAME ledger line as --track at the A->C boundary; the schema supports it but Plan of Work step (2) does not pin co-emission, risking a track-unscoped reconciled_tag"}
evidence_base: {section: "## Evidence base", certs: 7, matches: 5}
cert_index:
  - {id: "Scope-1", verdict: WEAK, anchor: "#### Scope-1 "}
  - {id: "Scope-2", verdict: WEAK, anchor: "#### Scope-2 "}
  - {id: "Decision-D5", verdict: SURVIVES, anchor: "#### Decision-D5 "}
  - {id: "Decision-D6", verdict: SURVIVES, anchor: "#### Decision-D6 "}
  - {id: "Violation-1", verdict: CONSTRUCTIBLE, anchor: "#### Violation-1 "}
  - {id: "Violation-2", verdict: THEORETICAL, anchor: "#### Violation-2 "}
  - {id: "Assumption-1", verdict: HOLDS, anchor: "#### Assumption-1 "}
flags: [CONTRACT_OK]
-->

# Track 2 adversarial review — iteration 1 (track-realization scope, D9)

Verdict: PASS with three should-fix findings, no blockers. The cross-track-episode
contract holds end to end — Track 1 delivered every field, flag, and predicate
Track 2 assumes (the `--reconciled-tag` flag, the track-scoped `reconciled_tag`
read, the `adr ⟺ ∃ track ≥ medium` row, the Phase-1 prediction hook in
`planning.md`). The findings are scope-boundary issues: the realized blast radius
of the agent rename and the `tier` removal reaches files the Plan of Work does not
enumerate, and the track's own no-dangling-reference verification is scoped too
narrowly to catch them.

This pass is narrowed to track realization per `track-review.md` §"Track-scoped
adversarial review" — the inline design decisions (D2/D3/D5/D6/D7/D8b/D9) were
vetted at the Phase-0→1 research-log gate and are not re-challenged here except
where realization exposes a gap. Reference accuracy was established by grep + Read
over the live develop-state tree; these are workflow-prose path/anchor references
(`.claude/**` markdown + agent files), not Java symbols, so grep is the correct
tool under the workflow-machinery criteria (no `findClass`, no PSI caveat). The
IDE was reachable and the project matched the working tree.

## Findings

### A1 [should-fix]
**Certificate**: Scope-1 (the realized blast radius is wider than the enumerated in-scope set)
**Target**: Scope — the track's `## Interfaces and Dependencies` in-scope file list and `## Plan of Work` step (4)
**Challenge**: The agent split/merge leaves dangling references to the three
removed agents (and the retired `BC` prefix) in **four live files outside Track
2's enumerated scope**. After promotion the live tree would name agents that no
longer exist:

- `.claude/skills/execute-tracks/SKILL.md:109` — names `review-bugs-concurrency`
  as "the step-level baseline". This is load-bearing: the user-facing execution
  skill describes the actual step-level routing, and after the split the baseline
  becomes `review-bugs` (always-on) + `review-concurrency` (concurrency-gated).
  A stale name here is both a dangling reference and a *wrong* description of the
  routing the skill orchestrates.
- `.claude/agents/review-workflow-consistency.md:63` — names
  `review-bugs-concurrency` as the worked example of "a skill dispatches
  sub-agents by name — does each agent file exist?". After removal the example
  names a non-existent agent (the irony: the consistency reviewer's own example
  fails its own check).
- `.claude/agents/review-workflow-instruction-completeness.md:24` — names
  `review-test-completeness` as a self-analogy ("the procedural analogue of
  `review-test-completeness`"). Dangling after the merge.
- `.claude/workflow/prompts/dimensional-review-gate-check.md:43` — uses `BC3` as
  a finding-prefix example alongside `CQ7`/`TC4`. After `BC` retires, a stale
  example prefix in a gate-check prompt.

`execute-tracks/SKILL.md:109` is the genuinely load-bearing one; the other three
are illustrative but still leave the promoted workflow internally inconsistent.
**Evidence**: Live-tree grep classifies these four files as OUT of Track 2's
in-scope list (Scope-1 certificate). The two completed sibling reviews already
flagged the blast radius is wider than the "five selection mirror sites"; this
finding names the specific out-of-scope survivors.
**Proposed fix**: Add `execute-tracks/SKILL.md` to the in-scope list (re-key its
step-level baseline reference to `review-bugs` + the concurrency-gated
`review-concurrency`). For the three agent-file / gate-check examples, either add
them to scope or have the Phase-C consistency reviewer's grep run repo-wide (all
`.claude/**`), not only the five mirror sites — and state in `## Plan of Work`
step (4) that the no-dangling-reference sweep is repo-wide. At minimum, record
these four as a forward obligation the way Track 1 recorded its four tier-readers.

### A2 [should-fix]
**Certificate**: Scope-2 (the `inline-replanning.md` re-key is far larger than one line of Plan of Work admits)
**Target**: Scope — `## Plan of Work` step (6) and the `inline-replanning.md` in-scope entry
**Challenge**: Plan of Work step (6) describes the `inline-replanning.md` edit as
"the tier-escalation path → complexity" — one clause. The live file carries a
whole `D11`/`D12` **tier-upgrade mechanism** (lines 141-195: "Tier upgrade rides
this same path", "Materialize first, then write the upgraded tier", the
ledger-append step, the rollback step) built entirely on the premise that change
size is *one* `tier` value that can be "upgraded" (`minimal`→`lite`→`full`). The
unbundling dissolves that premise: there is no single `tier` to upgrade — there
are now three independent axes (`design_gate`, `tracks`, the per-track tag). What
does "tier upgrade" mean when a `minimal` change that needs a `design.md`
mid-flight is now a `design_gate` flip, not a tier bump? The re-key is a
conceptual rewrite of the escalation model, not a vocabulary swap.

Concretely, line 169 is a hard failure: `workflow-startup-precheck.sh
--append-ledger --tier <new-tier>`. Track 1 dropped `--tier`, so after promotion
this exact invocation hits the script's `*) Unknown argument` arm and `exit 2`s
mid-escalation. Track 1's own forward-obligation note (track-1.md §Interfaces)
names this site explicitly as one of the four it relies on Track 2 to re-key.
**Evidence**: Scope-2 certificate — live `inline-replanning.md:141-195` is
tier-upgrade machinery; `:169` is the `--append-ledger --tier` write that `exit
2`s post-promotion; grep confirms it is the *only* such write site. Plan of Work
step (6) names neither the mechanism rewrite nor the hard failure.
**Proposed fix**: Expand step (6)'s `inline-replanning.md` clause to call out (a)
the literal `--append-ledger --tier` write that must drop `--tier` and emit the
new axis fields (`--design-gate` / `--reconciled-tag`) instead, and (b) that the
"tier upgrade" escalation model must be re-expressed in axis terms (a
design-gate flip and/or a per-track tag raise), not mechanically search-replaced.
This is the highest-risk single edit in the track because it is behavioral and
currently understated.

### A3 [should-fix]
**Certificate**: Violation-1 (a dangling reference survives the stated verification)
**Target**: Invariant — "No dangling reference to a removed agent
(`review-bugs-concurrency`, `review-test-behavior`, `review-test-completeness`)
survives across the five selection mirror sites — verified by the consistency
review and a grep."
**Challenge**: The invariant's verification clause is scoped to "the five
selection mirror sites." A dangling reference in an out-of-scope file (A1's
`execute-tracks/SKILL.md:109`, `review-workflow-consistency.md:63`, etc.) sits
*outside* those five sites, so the stated grep — run over the five mirrors — is
constructively unable to catch it. The invariant is true as written yet
unenforced where it matters: the verification's scope is narrower than the
hazard's scope.
**Evidence**: Violation-1 certificate constructs the scenario — start state: post-
implementation, all five mirrors clean; action: the consistency review greps the
five mirror sites per the invariant; violation point: the grep never reads
`execute-tracks/SKILL.md`, so `review-bugs-concurrency` survives there;
observable consequence: the promoted live tree dispatches against / documents a
deleted agent. CONSTRUCTIBLE.
**Proposed fix**: Broaden the invariant's verification to "a repo-wide grep over
all `.claude/**`, not only the five selection mirror sites" (and align the
`## Validation and Acceptance` "No dangling roster references" bullet). This is
the verification-side complement of A1's scope-side fix; fixing one without the
other leaves either the hazard or the check mis-scoped.

### A4 [suggestion]
**Certificate**: Assumption-1 (the same seven triggers now read at three granularities)
**Target**: Decision D9 (the per-track tag is computed over planned work)
**Challenge**: D9 runs the seven `risk-tagging.md` HIGH triggers at **track**
granularity. Those same seven triggers are already read at **change** level
(`risk-tagging.md` §"Gate 1 reuse (change-level)", the design-gate source) and at
**step** level (the per-step `risk:` tag). The track-level run is a third reading
of one trigger set. The prediction-quality assumption (a track's planned-work
prose is rich enough to evaluate verb-on-change content predicates) holds — the
planner has authored the `## Plan of Work` by end of Phase 1, and D5 reconciles
the prediction against content-based step tags as the safety net. But three
co-existing granularities of one taxonomy is a coherence load the track file does
not address: a reader must hold "central to the whole change" (Gate 1) vs
"central to this track's planned work" (D9) vs "this step introduces it" (risk
tag) simultaneously.
**Evidence**: Assumption-1 certificate — `risk-tagging.md:181-208` confirms the
change-level Gate 1 reuse with "central, not merely touched" semantics; D9 reuses
the same headings at track scope. The assumption HOLDS (reconciliation backstops
a weak prediction), so this is a suggestion, not a blocker.
**Proposed fix**: Add one sentence to D9 (or the `risk-tagging.md` track-
granularity rule step (1) authors) distinguishing the three granularities and
their "central to X" scoping, so the track-level read is not mistaken for the
change-level Gate 1 run.

### A5 [suggestion]
**Certificate**: Violation-2 (the reconciled tag must co-emit with `--track` to stay track-scoped)
**Target**: Invariant — "The per-track reconciled tag is read track-scoped, so a
completed prior track's tag cannot leak" (inherited from Track 1; the write side
is Track 2's)
**Challenge**: The reconciled tag is read via `ledger_tail_value_for_track`,
which matches the value on a line whose `track=` equals the target track. For
that read to resolve correctly, the reconciliation **write** must place
`--reconciled-tag <tag>` on the *same* `--append-ledger` invocation that carries
`--track <N>`. The live A→C boundary append (`track-review.md:599-601`) emits
`--track <N> --substate steps-partial` as one line — the natural host for the
reconciled tag. Plan of Work step (2) says "Write the reconciled tag … via
`--append-ledger`" but does not pin co-emission with `--track` on that one line.
A separate `--append-ledger --reconciled-tag <tag>` line lacking a `track=` token
would write a tag the track-scoped reader cannot find (it scans for `track=<N>`
lines).
**Evidence**: Violation-2 certificate — start state: Phase A computes `max(step
tags)=medium` for track 2; action: orchestrator appends `--reconciled-tag medium`
on a line without `--track 2`; violation point:
`ledger_tail_value_for_track reconciled_tag 2` finds no `track=2` line carrying
it; consequence: Phase C / Phase 4 read no reconciled tag for track 2 (or fall
back wrongly). THEORETICAL — the existing A→C append already carries `--track
<N>`, so folding `--reconciled-tag` into it is the obvious implementation, but the
plan does not state the constraint, so a future editor could split it.
**Proposed fix**: Plan of Work step (2) should state the reconciled-tag append
co-emits with `--track <N>` on the existing A→C boundary line (not a separate
append), so the track-scoped read resolves. Cheap, prevents a silent
track-scoping miss.

## Evidence base

#### Scope-1 — removed-agent / retired-BC references outside Track 2's enumerated scope
- **Method**: live-tree grep for `review-bugs-concurrency`, `review-test-behavior`,
  `review-test-completeness`, and `BC<digit>` / backtick-`BC`, then classified each
  hit by full relative path against Track 2's `## Interfaces and Dependencies`
  in-scope list.
- **Out-of-scope survivors found** (4):
  1. `.claude/skills/execute-tracks/SKILL.md:109` — `review-bugs-concurrency` named
     as "the step-level baseline" in the live execution skill's routing prose
     (load-bearing: wrong agent after the split).
  2. `.claude/agents/review-workflow-consistency.md:63` — `review-bugs-concurrency`
     as the example agent name in the "does each agent file exist?" check.
  3. `.claude/agents/review-workflow-instruction-completeness.md:24` —
     `review-test-completeness` as a self-analogy.
  4. `.claude/workflow/prompts/dimensional-review-gate-check.md:43` — `BC3` as a
     finding-prefix example.
- **False positive ruled out**: `implementer-rules.md` (the broad pattern matched
  no actual `BC` finding line on re-grep); `profile-query-bottleneck/SKILL.md` (no
  `BC` finding reference); `self-improvement-reflection.md` "tier" is a
  *documentation-cost* tier, unrelated to the change tier.
- **In-scope references** (covered by the track): all of `code-review/SKILL.md`,
  `fix-ci-failure/SKILL.md`, `code-review-protocol.md`, `conventions-execution.md`
  (incl. the §2.5 schema `BC1`/`BC2` examples at :559-560 and the step-level
  baseline note at :528), `finding-synthesis-recipe.md`, `review-agent-selection.md`,
  `review-iteration.md`, `risk-tagging.md` (incl. the model→reviewer table row at
  :68 coupling `review-bugs-concurrency`), `step-implementation.md`,
  `track-code-review.md`, and the six agent files.
- **Survival test**: WEAK — the agent rename is mechanically correct within the
  five mirror sites + agent files, but the realized blast radius reaches four
  out-of-scope files. Rationale survives only if the scope (or the verification
  grep) widens to repo-wide.

#### Scope-2 — `inline-replanning.md` re-key is a mechanism rewrite plus a hard-failure, not a one-line vocabulary swap
- **Live evidence**: `inline-replanning.md:141-195` is the D11/D12 tier-upgrade
  mechanism — "Tier upgrade rides this same path", "Materialize first, then write
  the upgraded tier", the ledger append, the rollback. All premised on a single
  `tier` value that escalates `minimal`→`lite`→`full`.
- `inline-replanning.md:169` is the literal write
  `workflow-startup-precheck.sh --append-ledger --tier <new-tier>`. Track 1
  dropped `--tier` from the staged precheck (verified: arg case at staged
  `:197-235` has no `--tier`, and the `*)` arm `exit 2`s). So this invocation is a
  post-promotion hard failure.
- `inline-replanning.md` is NOT yet staged (Track 2 has not started), so the live
  file is the develop-state hazard — correctly read live per the staged-read
  precedence rule.
- grep confirms `:169` is the only `--append-ledger --tier` write site in the tree.
- Track 1's forward obligation (track-1.md §Interfaces and Dependencies, "Forward
  obligation on Track 2") names `inline-replanning.md` (the ESCALATE
  `--append-ledger --tier` write — "would hit `exit 2` after the flag drop")
  explicitly as one of the four it relies on Track 2 to discharge.
- **Survival test**: WEAK — `inline-replanning.md` is in scope (good), but Plan of
  Work step (6) describes the edit in one clause that captures neither the
  mechanism rewrite nor the hard-failure write. Rationale survives once step (6)
  is expanded.

#### Decision-D5 — Reconciliation at Phase A on upward divergence (cross-track-episode reality)
- **Chosen approach**: compute `max(step tags)` after sub-step 4, compare to the
  predicted tag, run the missed strategic reviewers on any upward miss, fire at
  most once, write the reconciled tag to Track 1's ledger field at the A→C
  boundary.
- **Cross-track-episode check**: does Track 1's frozen schema actually provide the
  per-track `reconciled_tag` write Track 2 assumes?
  1. Staged `workflow-startup-precheck.sh:209-212` defines the `--reconciled-tag`
     flag → `LEDGER_RECONCILED_TAG`; `:74-77` documents it as a bare
     `low`/`medium`/`high` "written PER TRACK (paired with a `track=` token on the
     same line) and read track-scoped via `ledger_tail_value_for_track`."
  2. The flag→key map in track-1.md §Episodes Step 1 confirms
     `--reconciled-tag`→`reconciled_tag`, emitted on the `track=` line.
  3. The live A→C boundary append (`track-review.md:599-601`) already carries
     `--track <N>` on one line — the host the reconciled tag co-emits on.
- **Outcome**: the contract Track 2 builds on EXISTS exactly as assumed. The only
  realization gap is the co-emission constraint, surfaced separately as A5
  (suggestion). The termination bound (fires at most once because the ceiling is
  `high`) is internally consistent.
- **Survival test**: SURVIVES — Track 1 delivered the writable per-track field on
  the `track=` line; the decision holds.

#### Decision-D6 — Domain × complexity: complexity sets count at Phase A, rigor at Phase C
- **Chosen approach**: Phase A complexity sets *how many* of the strategic trio
  run (`low`→Technical; `medium`→+Adversarial; `high`→+Risk+Adversarial); Phase C
  domain alone selects the dimensional set, complexity moves only the rigor dial,
  the floor + domain-matched set is never suppressed.
- **Rejected alternative searched**: "let complexity gate *which* Phase-C
  specialists run." The track rejects it because Phase-C selection is deterministic
  on category presence and suppressing a domain-selected specialist subtracts
  review in the dangerous direction (a `low` track touching `configuration` would
  lose `review-security`).
- **Realization check**: the live `track-review.md` §"Tier-driven review selection"
  (`:620-664`) reads the whole-change `tier` ledger-first as the panel knob and
  runs Adversarial in *every* `lite`/`full` track. D6 re-keys this to the per-track
  tag and *drops* Adversarial on `low`. The risk: a genuinely-`low` pure-refactor
  track that is architecture-central. The track's own D6 Risks/Caveats already
  handles this: such a track hits the Architecture HIGH trigger over its planned
  work (D9) and tags `high`, earning Risk+Adversarial; the risk-tag override is the
  backstop.
- **Survival test**: SURVIVES — the architecture-central-but-`low` gap is closed by
  D9's trigger evaluation. No change.

#### Violation-1 — dangling reference survives the five-mirror-site grep
- **Invariant claim**: no reference to a removed agent survives across the five
  selection mirror sites (verified by consistency review + grep).
- **Violation construction**:
  1. Start state: post-implementation; all five mirror sites
     (`code-review/SKILL.md`, `review-agent-selection.md`, `track-code-review.md`,
     `step-implementation.md`, `fix-ci-failure/SKILL.md`) re-keyed clean.
  2. Action: the Phase-C consistency reviewer runs the invariant's grep over the
     five mirror sites.
  3. Intermediate state: the grep reads only those five files.
  4. Violation point: `execute-tracks/SKILL.md:109` (not a mirror site) still names
     `review-bugs-concurrency`; the grep never reads it.
  5. Observable consequence: the promoted live tree documents/dispatches a deleted
     agent; a future `/execute-tracks` step-level routing description is wrong.
- **Feasibility**: CONSTRUCTIBLE — the file is outside the grep's scope by
  construction, and A1 confirms the reference is live today.

#### Violation-2 — reconciled tag written off the `track=` line
- **Invariant claim**: the per-track reconciled tag is read track-scoped so a prior
  track's value cannot leak.
- **Violation construction**:
  1. Start state: Phase A for track 2 computes `max(step tags)=medium`.
  2. Action: orchestrator appends `--append-ledger --reconciled-tag medium` on a
     line that omits `--track 2`.
  3. Intermediate state: the ledger has a `reconciled_tag=medium` token on a
     `track=`-less line.
  4. Violation point: `ledger_tail_value_for_track reconciled_tag 2` scans for a
     line with `track=2` carrying the key; finds none.
  5. Observable consequence: Phase C reads no reconciled tag for track 2; the
     `adr ⟺ ∃ track ≥ medium` predicate undercounts.
- **Feasibility**: THEORETICAL — the existing A→C append already carries `--track
  <N>`, so the obvious implementation co-emits; the violation needs a future editor
  to split the append. Pinning the constraint in Plan of Work step (2) removes it.

#### Assumption-1 — the seven triggers read at three granularities stay coherent
- **Claim**: running the seven HIGH triggers at track granularity (D9) yields a
  usable prediction without colliding with the change-level (Gate 1) and step-level
  (risk tag) reads of the same triggers.
- **Stress scenario**: a track whose planned work touches a HIGH category that is
  *not* central to the whole change (so Gate 1 says no design needed) but *is*
  central to this track. D9 must tag it `high` at track scope even though Gate 1
  said `design_gate=no`.
- **Code evidence**: `risk-tagging.md:181-208` (Gate 1 reuse) scopes the
  change-level read as "central to the whole change"; D9 scopes the track-level read
  as "central to this track's planned work" — the two are genuinely different
  questions over the same trigger set, and the staged `planning.md:164-176` already
  separates the prediction request from the computation (which D9 owns). The
  reconciliation (D5) backstops a weak prediction.
- **Verdict**: HOLDS — the three granularities answer three "central to X" questions
  and never feed one signal; coherence is real but under-documented (A4 suggestion).
