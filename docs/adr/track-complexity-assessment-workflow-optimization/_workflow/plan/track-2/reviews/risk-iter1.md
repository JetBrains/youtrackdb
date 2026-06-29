<!-- MANIFEST
findings: 6   severity: {blocker: 1, should-fix: 3, suggestion: 2}
index:
  - {id: R1, sev: blocker,    loc: ".claude/workflow/inline-replanning.md:164-191", anchor: "### R1 ", cert: E1, basis: "inline-replanning D11/D12 writes `--append-ledger --tier`, a flag the staged precheck no longer accepts; not enumerated in the track's concrete edits"}
  - {id: R2, sev: should-fix, loc: ".claude/skills/execute-tracks/SKILL.md:109; .claude/agents/review-workflow-consistency.md:63; .claude/agents/review-workflow-instruction-completeness.md:24", anchor: "### R2 ", cert: E2, basis: "three live files outside the track's in-scope set carry removed-agent names; the consistency-grep then reports stale references the track cannot fix in scope"}
  - {id: R3, sev: should-fix, loc: ".claude/workflow/risk-tagging.md:68; .claude/workflow/track-review.md:902-916; .claude/workflow/conventions-execution.md:528", anchor: "### R3 ", cert: E3, basis: "roster references inside in-scope files the Plan of Work does not name; risk-tagging step (1) is scoped to D9 tag computation only"}
  - {id: R4, sev: should-fix, loc: ".claude/workflow/prompts/create-final-design.md:38-46,89-99; .claude/workflow/prompts/design-review.md:67-69,235-255", anchor: "### R4 ", cert: E4, basis: "both prompts read the dropped `tier` ledger field directly; the track's step (5)/(6) describe the carrier table + fidelity gate but not the underlying tier-field ledger reads"}
  - {id: R5, sev: suggestion, loc: ".claude/workflow/track-review.md:599-601; staged precheck reconciled-tag flag", anchor: "### R5 ", cert: E5, basis: "the D5 reconciled-tag write must be added to the existing A->C --append-ledger call with --track present; idempotency on resume rests on last-value-wins"}
  - {id: R6, sev: suggestion, loc: "Track 2 acceptance — grep over prefix family and removed-agent names", anchor: "### R6 ", cert: E6, basis: "the stated grep acceptance scopes to the five mirror sites only; a repo-wide grep is the actual safety net"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 6}
cert_index:
  - {id: E1, verdict: CONTRADICTED, anchor: "#### E1 "}
  - {id: E2, verdict: CONTRADICTED, anchor: "#### E2 "}
  - {id: E3, verdict: CONTRADICTED, anchor: "#### E3 "}
  - {id: E4, verdict: CONTRADICTED, anchor: "#### E4 "}
  - {id: E5, verdict: VALIDATED, anchor: "#### E5 "}
  - {id: E6, verdict: VALIDATED, anchor: "#### E6 "}
flags: [CONTRACT_OK]
-->

# Track 2 — Risk review (iteration 1)

Track 2 is workflow-prose only (no Java). It stages under `_workflow/staged-workflow/.claude/`;
its own in-scope files are read at live develop-state. The dominant failure mode for this track
is the one its own Plan of Work names: a dangling reference to a removed agent surviving the
split/merge. The review found that the blast radius is wider than the track's "five selection
mirror sites" frame — removed-agent names and the dropped `tier` ledger field reach in-scope
files the Plan of Work does not enumerate and out-of-scope files the track cannot edit. None of
this is a Java/WAL/crash concern; the five workflow-prose criteria (rule coherence, instruction
completeness, prompt-design soundness, context-budget, breakage of dependent prompts) govern.

## Findings

### R1 [blocker]
**Certificate**: E1 (Exposure — `inline-replanning.md` D11/D12 tier-upgrade writes `--tier`)
**Location**: `.claude/workflow/inline-replanning.md:141-191` (the tier-upgrade-rides-ESCALATE
block, D11/D12); the breakage lands at the Phase-4 staged→live promotion.
**Issue**: `inline-replanning.md` carries a full tier-upgrade machine. Lines 164-171 instruct the
ESCALATE replan to write the new tier by calling
`workflow-startup-precheck.sh --append-ledger --tier <new-tier>`, and the surrounding prose
(141-148, 172-191) explains that every Phase-2/3A/4 selector reads that `tier` field
ledger-first. Track 1 dropped the `tier` field from the ledger and the `--tier` flag from the
precheck — the staged `workflow-startup-precheck.sh` accepts only
`--design-gate / --tracks / --phase1-complete / --reconciled-tag` (verified: the only `tier`
hit in the staged script is the comment at line 2017, "unaffected by the `tier=` removal"; no
`--tier` argument branch survives). After the Phase-4 promotion ships the staged precheck live,
any ESCALATE tier upgrade that runs `--append-ledger --tier <new-tier>` will fail the
flag-parse and the upgrade "would be announced but never take effect downstream" — exactly the
failure mode the block's own closing paragraph warns about, now guaranteed rather than guarded
against. This is the single live `tier`-writer; the four reader-files the Track-1 handoff names
(`inline-replanning.md`, `track-review.md`, `create-final-design.md`, `design-review.md`)
include this one, but the handoff frames `inline-replanning.md` as a *reader*. It is also a
*writer* of a now-rejected flag, which is a harder breakage than a stale read.
The track's Plan of Work step (6) under-describes the work as "`inline-replanning.md` (the
tier-escalation path → complexity)" — a one-clause mention of a multi-paragraph D11/D12
mechanism, and `inline-replanning.md`'s `## Interfaces` line says only "the tier-escalation path
re-keyed to complexity," naming no concrete edit to the `--tier` ledger write, the materialize
step, or the D11/D12 invariants. Likelihood that a decomposer reading step (6) at face value
re-keys the prose label but misses the `--append-ledger --tier` call is high; impact is a hard
runtime failure on the first post-promotion mid-flight escalation, with no obvious recovery
(the precheck rejects the flag and the upgrade silently no-ops downstream).
**Proposed fix**: Before decomposition, expand the track's step (6) and the
`inline-replanning.md` `## Interfaces` entry to name the concrete D11/D12 edits explicitly: (a)
replace the `--append-ledger --tier <new-tier>` write with the complexity-axis equivalent
(`--design-gate` flip on a `→full` materialize, plus whatever the complexity re-key chooses for
the panel-intensity signal), keyed to the same flags Track 1 added; (b) re-key the "phase
ledger's `tier` field (D4)" prose and the "every Phase-2/3A/4 selector reads ledger-first"
claim onto the new fields; (c) re-key the materialize-then-write ordering rationale, which
currently turns on the `tier` field. Add a decomposition step or step-acceptance line that
greps the staged precheck's accepted flags and asserts no surviving `--tier` write in any
promoted file. This is the one place the staged-vs-live split can ship a workflow that calls a
flag the precheck rejects.

### R2 [should-fix]
**Certificate**: E2 (Exposure — removed-agent names in files outside the track's in-scope set)
**Location**: `.claude/skills/execute-tracks/SKILL.md:109`;
`.claude/agents/review-workflow-consistency.md:63`;
`.claude/agents/review-workflow-instruction-completeness.md:24`. None of the three appears in
the track's `## Interfaces and Dependencies` in-scope list.
**Issue**: A repo-wide grep for the three removed agent names finds references in three live
files the track does not plan to edit:
- `execute-tracks/SKILL.md:109` names `review-bugs-concurrency` as "the step-level baseline"
  inside the autonomous-execution narrative. This is a substantive functional reference, not an
  illustration — once `review-bugs-concurrency` is removed and the burial role passes to
  `review-bugs` (+`review-concurrency`), this sentence describes a non-existent agent.
- `review-workflow-consistency.md:63` uses `review-bugs-concurrency` as the worked example of
  the very check it performs ("A skill dispatches sub-agents by name (e.g.,
  `review-bugs-concurrency`) — does each agent file exist under `.claude/agents/`?"). After the
  split this example points at a deleted agent, and the agent that checks for dangling dispatch
  references itself carries one.
- `review-workflow-instruction-completeness.md:24` derives its own identity from
  `review-test-completeness` ("This is the procedural analogue of `review-test-completeness`…").
  After the merge that analog agent no longer exists by that name.
The track's "No dangling roster references" acceptance is scoped to "across the five selection
mirror sites." These three files are outside that frame, so the acceptance grep as written
would pass while three live references dangle. Likelihood the consistency review (Phase 2 of
Track 2's own Phase C, and any future workflow consistency run) flags these is high — the
consistency reviewer greps the whole `.claude/` tree, not just five files; impact is a
should-fix consistency defect that the track scope as drawn cannot close.
**Proposed fix**: Either (a) widen the track's in-scope set to add these three files and re-key
their references during the split/merge (preferred — they are genuine references), or (b) record
an explicit out-of-scope carve-out in the track file stating these three live references are
knowingly deferred and to which later change, so the consistency review does not read them as a
miss. Because the split/merge stages, the references stay valid against the live tree until the
Phase-4 promotion — so the fix can ride either Track 2's promotion commit or a same-commit
sweep, but it must be on someone's list before promotion.

### R3 [should-fix]
**Certificate**: E3 (Exposure — roster references inside in-scope files the Plan of Work does
not name)
**Location**: `.claude/workflow/risk-tagging.md:68` (the `high`-row step-level-review table
cell); `.claude/workflow/track-review.md:902-916` (the `#### Risk tagging` decomposition
subsection); `.claude/workflow/conventions-execution.md:528` (the per-tier baseline-subset
note). All three files are in-scope, but the Plan of Work step that touches each does not name
the roster reference.
**Issue**: Three in-scope files carry `review-bugs-concurrency` references the Plan of Work
silently omits from the step that edits the file:
- `risk-tagging.md:68` — the `| high |` row's "Step-level review" cell names
  `review-bugs-concurrency` explicitly. Plan of Work step (1) scopes `risk-tagging.md` to "the
  rule that the pre-decomposition complexity tag runs the seven HIGH triggers at track
  granularity (D9)" — the tag-computation rule, not the line-68 roster cell. A decomposer
  implementing only D9 leaves line 68 naming the removed agent.
- `track-review.md:902-916` `#### Risk tagging` subsection — describes step-level review and the
  seven HIGH categories; the prose itself doesn't name `review-bugs-concurrency` at 902-916 but
  the same file's `## Risk levels` table (line 68 is in `risk-tagging.md`; track-review's
  equivalent narrative) and the broader file carry roster prose. Plan of Work step (2) scopes
  `track-review.md` to the Phase-A panel re-key and reconciliation — not the roster references
  elsewhere in the file.
- `conventions-execution.md:528` — "the step tier launches a subset (`review-bugs-concurrency`
  only)". Plan of Work step (6) scopes `conventions-execution.md` to "review-file / roster
  references" generically, which does cover this, but the line is easy to miss because the
  surrounding text is schema documentation, not selection logic.
Likelihood a decomposer mechanically following the per-step file→work mapping misses these is
moderate; impact is residual dangling references inside the track's own scope that the
consistency grep then surfaces as a self-inflicted miss.
**Proposed fix**: At decomposition, add an explicit instruction to the roster-split step (3)/(4)
that the BC→{review-bugs, review-concurrency} re-key sweeps *every* in-scope file's roster
references, not only the five selection mirror sites — name `risk-tagging.md:68`,
`track-review.md`'s risk-levels/decomposition prose, and `conventions-execution.md:528` as
in-scope roster sites. A grep over the in-scope file set for the three removed names, asserting
zero hits in the staged copies, belongs in the track acceptance.

### R4 [should-fix]
**Certificate**: E4 (Exposure — `create-final-design.md` and `design-review.md` read the
dropped `tier` ledger field directly)
**Location**: `.claude/workflow/prompts/create-final-design.md:38-46` (the `design.md`-exists
ledger read) and `:89-99` (the carrier table keyed off the confirmed tier, read ledger-first
from the `tier` field); `.claude/workflow/prompts/design-review.md:67-69` (the `tier` input
param) and `:235-255` (the `tier=full` fidelity criterion).
**Issue**: Both prompts read the `tier` field that Track 1 dropped from the ledger:
- `create-final-design.md:41-46,89-92` instruct "read the confirmed tier ledger-first — the
  phase ledger's `tier` field … when no `phase-ledger.md` exists, fall back to the tier line in
  `implementation-plan.md`," then key the artifact carrier table off `full`/`lite`/`minimal`.
  Track 2 step (5) says "re-derive the carrier table from the axes" and re-key the verdict-fold
  predicate — but the *mechanism* by which the prompt learns the tier (the ledger `tier`-field
  read at 41-46, 89-92) is a separate edit the step does not call out. A carrier table re-keyed
  to `design_gate`/`reconciled_tag` while the surrounding read still fetches `tier` from a
  field that no longer exists is internally inconsistent.
- `design-review.md:67-69` takes a `tier` input param; `:235-255` gates the full-tier fidelity
  criterion on `tier=full`. Track 2 step (6) says re-key "its `tier=full` fidelity gate … to
  read `design_gate=yes`" — this names the gate but not the `tier` *input param* at 67-69 that
  feeds it, nor the spawn-site that passes `tier=`. If the param survives while the gate reads
  `design_gate`, the spawn contract drifts.
Likelihood is moderate-to-high: these are exactly the "four live tier-readers" the Track-1
handoff flagged as Track 2's forward obligation, and the handoff is explicit that all four must
re-key together in the Phase-4 promotion or "the live workflow … has no `tier=` but … readers
still expect it." The track DRs (D8b) and Plan of Work steps (5)/(6) describe the *intent*
(carrier from axes, gate from `design_gate`) but not the full set of `tier`-read sites inside
each prompt.
**Proposed fix**: Expand step (5) to state that re-deriving the carrier table includes
re-keying the ledger-read mechanism in `create-final-design.md` (lines 41-46 and 89-92) off the
`tier` field onto `design_gate` (carrier 1) and the reconciled-tag scan (carrier 2), with the
plan-line fallback re-pointed to the axes. Expand step (6) to state the `design-review.md`
re-key covers the `tier` input param (67-69) and its spawn-site, not only the gate condition.
Adding a grep over the in-scope set for a surviving bare `tier` read (excluding intentional
prose history) to the track acceptance closes the gap.

### R5 [suggestion]
**Certificate**: E5 (Exposure — the D5 reconciled-tag ledger write)
**Location**: `.claude/workflow/track-review.md:599-601` (the existing A→C
`--append-ledger --ctx … --phase C --track <N> --substate steps-partial` call) and the staged
precheck's `--reconciled-tag` handling.
**Issue**: D5's reconciliation writes the reconciled tag "via `--append-ledger
--reconciled-tag` at the A→C boundary." The mechanism is feasible — the staged precheck accepts
`--reconciled-tag <low|medium|high>` and the comment at staged precheck line 1763-1768 confirms
`reconciled_tag` rides on the same ledger line as the paired `--track` token. Two correctness
notes for the writer:
- The existing line-599 call already carries `--track <N>`, so adding `--reconciled-tag <max>`
  to that same call pairs the tag with the correct track. The track must edit the *existing*
  append call, not add a second append (a second append-only line would still be last-value-wins
  but splits the boundary record).
- Termination and idempotency: D5 fires "at most once per Phase A," and the ledger is
  last-value-wins, so a resume that re-runs the A→C boundary append re-writes the same
  `reconciled_tag` value — idempotent by construction. This holds only if the reconciled value
  is recomputed deterministically as `max(step tags)` on resume (the step tags are committed in
  `## Concrete Steps` before the boundary append per step 5's commit ordering), which the track
  should state explicitly so a resumed reconciliation does not read a stale or partial tag.
Likelihood of a defect is low given the staged schema support; impact if the writer adds a
separate append or reads a pre-decomposition value is a wrong reconciled tag governing Phase C.
**Proposed fix**: Have the decomposition note for step (2) state: append `--reconciled-tag
<max(step tags)>` to the *existing* A→C `--append-ledger` call (the one at track-review.md:599),
not a new line; and recompute `max(step tags)` from the committed `## Concrete Steps` roster on
every (re)entry so the write is idempotent on resume.

### R6 [suggestion]
**Certificate**: E6 (Testability — the track's grep-based acceptance scope)
**Location**: Track 2 `## Validation and Acceptance`, the "No dangling roster references"
and "Prefixes resolve" lines; `## Invariants & Constraints` last bullet.
**Issue**: Workflow-machinery edits are verified by consistency/structural/grep checks, not unit
tests — the track's stated acceptance is right to lean on grep + the consistency review. But the
acceptance scopes the removed-agent grep to "across the five selection mirror sites" and the
prefix grep to "the `finding-synthesis-recipe` prefix family." R2 and R3 show the real reference
set is wider (three out-of-scope files, three additional in-scope roster sites). A grep scoped
to five files is not the safety net the track needs; a repo-wide grep over `.claude/` (against
the staged copies for in-scope files, the live tree for the rest) is. The prefix-resolution
check is sound — `TB`/`TC` kept verbatim means existing finding references resolve — but the
acceptance should also assert the two *new* prefixes (for `review-bugs` / `review-concurrency`)
are registered in the `review-iteration.md` owner table and that `BC` is retired there, since
the owner table is the canonical prefix home and a missed entry there is invisible to a
synthesis-recipe-only grep.
**Proposed fix**: Restate the acceptance as a repo-wide grep over `.claude/` for the three
removed agent names (zero hits in promoted state) and for the `BC` prefix (retired in the owner
table and synthesis recipe), plus an existence check that `review-iteration.md` §"Finding ID
prefixes" carries rows for both new prefixes and keeps `TB`/`TC`/`TX`. Scope the grep to the
staged subtree for in-scope files so it does not false-positive on the live tree before
promotion.

## Evidence base

#### E1: Exposure — `inline-replanning.md` D11/D12 writes the dropped `--tier` ledger flag
- **Track claim**: Plan of Work step (6): re-key "`inline-replanning.md` (the tier-escalation
  path → complexity)". `## Interfaces`: "the tier-escalation path re-keyed to complexity."
- **Critical path trace**:
  1. ESCALATE replan reaches the tier-upgrade path @ `inline-replanning.md:141` ("Tier upgrade
     rides this same path (D12)").
  2. Materialize dropped artifacts @ `:150-163` (D11), then
  3. "Append the upgraded tier as a ledger event" @ `:164-171`:
     `workflow-startup-precheck.sh --append-ledger --tier <new-tier>`.
  4. Closing rationale @ `:172-191`: "every Phase-2/3A/4 selector reads ledger-first" the
     `tier` field; "without the ledger append … the upgrade would be announced but never take
     effect downstream."
- **Blast radius**: After Phase-4 promotion ships the staged precheck, the `--tier` flag is
  rejected at parse (the staged precheck accepts only
  `--design-gate/--tracks/--phase1-complete/--reconciled-tag`; verified the only `tier`
  occurrence in the staged script is the comment at line 2017). The escalation aborts or
  no-ops; the tier-upgrade announced to the user never propagates. Affects every mid-flight
  ESCALATE-with-tier-upgrade after promotion.
- **Existing safeguards**: None — the staged precheck has no `--tier` branch and no
  compatibility shim. The block's own closing paragraph documents the exact failure as the
  thing the append is supposed to prevent.
- **Evidence search**: grep `--tier` over the staged precheck (one hit, a comment); Read of
  `inline-replanning.md:141-191`; Read of staged precheck argument-parse block (lines 179-237)
  shows `--design-gate/--tracks/--phase1-complete/--reconciled-tag` branches, no `--tier`.
- **Residual risk**: HIGH — a guaranteed hard failure on a real execution path, under-described
  in the track's concrete edits as a one-clause prose re-key.

#### E2: Exposure — removed-agent names in three live files outside the track's in-scope set
- **Track claim**: "No dangling roster references … across the five selection mirror sites";
  in-scope list names 20 files, none of the three below.
- **Critical path trace**: repo-wide `grep -rl` for `review-bugs-concurrency` /
  `review-test-behavior` / `review-test-completeness` over `.claude/` returns 15 files; subtracting
  the 20-file in-scope set leaves three: `execute-tracks/SKILL.md:109` (`review-bugs-concurrency`
  as "step-level baseline"), `review-workflow-consistency.md:63` (`review-bugs-concurrency` as
  the dangling-dispatch-check example), `review-workflow-instruction-completeness.md:24`
  (`review-test-completeness` as the agent's self-identity analog).
- **Blast radius**: three live references to deleted agents after the Phase-4 promotion; the
  consistency review (which greps the whole tree) reports them as defects the track scope cannot
  close.
- **Existing safeguards**: the consistency review will catch them — that is the safeguard, but
  it catches them as a *miss*, not a *fix*. The staging delay keeps them valid until promotion.
- **Evidence search**: Read each line in context; classified each against the track's in-scope
  list with an exact path match.
- **Verdict**: CONTRADICTED — the "five mirror sites" framing under-counts the blast radius.

#### E3: Exposure — roster references inside in-scope files the Plan of Work step does not name
- **Track claim**: step (1) scopes `risk-tagging.md` to D9 tag computation; step (2) scopes
  `track-review.md` to the panel + reconciliation; step (6) scopes `conventions-execution.md`
  to "review-file / roster references" generically.
- **Critical path trace**: `risk-tagging.md:68` — `| high |` row cell names
  `review-bugs-concurrency`. `conventions-execution.md:528` — "the step tier launches a subset
  (`review-bugs-concurrency` only)". `track-review.md` carries roster prose in its risk-levels
  and `#### Risk tagging` (902-916) decomposition narrative.
- **Blast radius**: residual dangling references inside the track's own scope if the decomposer
  edits only the named work per step; surfaced by the in-scope grep as a self-inflicted miss.
- **Existing safeguards**: step (6)'s generic "roster references" clause covers
  `conventions-execution.md:528` and could be read to cover the others, but the per-step file
  mapping invites a narrow read.
- **Evidence search**: per-file grep count of the three removed names (risk-tagging.md: 1,
  conventions-execution.md: 1, plus the dense 19/12/9/7/5 spread across the five mirror sites).
- **Verdict**: CONTRADICTED — not every in-scope roster reference is named by the step that
  edits its file.

#### E4: Exposure — `create-final-design.md` / `design-review.md` read the dropped `tier` field
- **Track claim**: step (5) re-derives the carrier table from the axes and re-keys the
  verdict-fold; step (6) re-keys `design-review.md`'s `tier=full` fidelity gate to
  `design_gate=yes`.
- **Critical path trace**: `create-final-design.md:41-46` reads "the phase ledger's `tier`
  field … fall back to the tier line in `implementation-plan.md`"; `:89-99` keys the artifact
  table on `full`/`lite`/`minimal`. `design-review.md:67-69` declares a `tier` input param;
  `:235-255` gates fidelity on `tier=full`.
- **Blast radius**: a carrier table re-keyed to the axes while the surrounding ledger read still
  fetches `tier` is internally inconsistent; a fidelity gate re-keyed to `design_gate` while the
  `tier` input param and spawn-site survive drifts the spawn contract. Both are among the four
  live tier-readers the Track-1 handoff flagged as Track 2's promote-together obligation.
- **Existing safeguards**: the Track-1 handoff names these two files explicitly, so the
  obligation is documented — but the track DRs/steps describe the carrier/gate intent, not the
  full set of `tier`-read sites.
- **Evidence search**: grep `tier` over both prompts; Read of the cited line ranges.
- **Verdict**: CONTRADICTED — the steps under-specify the tier-read mechanism inside each prompt.

#### E5: Exposure — the D5 reconciled-tag ledger write
- **Track claim**: D5/step (2): write the reconciled tag "via `--append-ledger
  --reconciled-tag` at the A→C boundary," firing at most once per Phase A.
- **Critical path trace**: existing A→C append @ `track-review.md:599-601` already carries
  `--ctx … --phase C --track <N> --substate steps-partial`. Staged precheck accepts
  `--reconciled-tag <low|medium|high>` (parse branch at line 209) and writes `reconciled_tag`
  on the same line as `--track` (comment at lines 1763-1768; field accumulators at 1735-1768).
- **Blast radius**: a separate append line splits the boundary record; a value read before
  decomposition completes writes a stale tag governing Phase C. Both avoidable.
- **Existing safeguards**: ledger is last-value-wins (idempotent on resume if recomputed
  deterministically); step (5)'s commit ordering puts `## Concrete Steps` (the step tags) on
  disk before the boundary append.
- **Evidence search**: Read staged precheck parse/accumulate/compose blocks (179-237,
  1722-1808); Read track-review.md:580-618.
- **Verdict**: VALIDATED — the mechanism is feasible; the residual is a writer-discipline note,
  not a design gap.

#### E6: Testability — the grep-based acceptance scope
- **Coverage target**: not unit-test coverage — workflow-prose acceptance is grep +
  consistency/structural review (the track states this correctly).
- **Difficulty assessment**: the acceptance greps scope to "the five selection mirror sites" and
  "the `finding-synthesis-recipe` prefix family"; E2/E3 show the reference set is wider (three
  out-of-scope files, three more in-scope sites, the `review-iteration.md` owner table).
- **Existing test infrastructure**: the consistency review (`review-workflow-consistency`) and
  structural review run at Phase 2/C and grep the whole tree — the real safety net, broader than
  the stated acceptance grep.
- **Feasibility**: ACHIEVABLE — a repo-wide grep over `.claude/` (staged copies for in-scope
  files, live for the rest) plus an owner-table existence check fully verifies the roster
  invariant.
- **Verdict**: VALIDATED as ACHIEVABLE — the acceptance just needs its grep scope widened to
  match the real blast radius.
