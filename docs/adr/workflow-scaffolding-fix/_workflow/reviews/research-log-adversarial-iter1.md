<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: A1, sev: should-fix, loc: "research-log.md:123", anchor: "### A1 ", cert: C1, basis: "load-bearing Open Question left unresolved; gate requires it folded into the Decision Log"}
  - {id: A2, sev: suggestion, loc: "research-log.md:44",  anchor: "### A2 ", cert: C2, basis: "staged-mode choice means the fix cannot self-apply on this branch; rationale verified sound but relies on an unstated invariant"}
  - {id: A3, sev: suggestion, loc: "research-log.md:89",  anchor: "### A3 ", cert: C3, basis: "prose-correction site list is complete; recording the verified-negative on the test fixture strengthens the completeness claim"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 3}
cert_index:
  - {id: C1, verdict: BREAKS, anchor: "#### C1 "}
  - {id: C2, verdict: HOLDS,  anchor: "#### C2 "}
  - {id: C3, verdict: HOLDS,  anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

# Research-log adversarial review — workflow-scaffolding-fix (iter 1)

Gate scope: the research log's `## Decision Log`, `## Surprises & Discoveries`,
and `## Open Questions`. Lens-free run (`matched_categories = (none)`,
`design_gate=no` with no user lens). This is a **gate**: `blocker` sends a
decision back to research; `should-fix` gates until the rationale is folded
into the Decision Log; `suggestion` is recorded. No `skip`.

**Verdict: PASS-WITH-FINDINGS** — 0 blockers, 1 should-fix, 2 suggestions. The
three logged decisions survive challenge and every cited command site and
prose claim was verified live. The one gating finding is procedural: the
load-bearing Open Question (whether develop already carries a partial fix) is
still open in the log, and the gate contract treats an unresolved
load-bearing question as a not-yet-made decision. It resolves cheaply — the
verification is already done in this review (the bug is present unchanged) —
so folding the answer into the Decision Log clears it.

## Findings

### A1 [should-fix]
**Certificate**: C1 (Open-question test — "Does current develop already carry a partial fix?")
**Target**: Open Question (`research-log.md:123`) / Surprises entry "Bug confirmed live" (`research-log.md:89`)
**Challenge**: The entire fix presupposes the bug is live and unfixed at both
operative sites. The log's `## Open Questions` still carries this as an open
item ("need to read the live Step 6 / Final Artifacts text to confirm the bug
is still present"), while the `## Surprises` section separately asserts "Bug
is present unchanged on this branch's HEAD." That is an internal contradiction:
the question the log calls open is answered elsewhere in the same log, but the
answer never lands in the Decision Log. Per the research-log gate contract, an
unresolved question that bears on a load-bearing decision is a not-yet-made
decision — deriving the fix over it is a gap until it is resolved into the
`## Decision Log` (or explicitly waived).
**Evidence**: Verified live this session. `prompts/create-final-design.md:609`
is `git rm -r docs/adr/<dir-name>/_workflow/` (bare, no `-f`);
`workflow.md:764` is the mirror `git rm -r docs/adr/<dir-name>/_workflow/`.
The "sweeps automatically" prose is present unchanged at
`create-final-design.md:617` and `workflow.md:769`. An empirical git test
(modified tracked file + untracked sibling under a dir) reproduced the exact
failure: `git rm -r sub/` exits 1 with "the following file has local
modifications", and `git rm -rf sub/` succeeds but leaves the untracked file
on disk — so the three-part diagnosis (`-rf` for the modified file, follow-up
`rm -rf` for untracked remnants) is correct. The bug is live; the open
question is answerable now.
**Proposed fix**: Close the Open Question by adding a dated `## Decision Log`
entry (or converting the "Bug confirmed live" surprise into a decision):
"Verified against live `create-final-design.md:609/617` and
`workflow.md:764/769` — bug present unchanged, no partial fix on develop; fix
proceeds." Remove or mark-resolved the `## Open Questions` entry so the log no
longer carries a contradiction between the two sections.

### A2 [suggestion]
**Certificate**: C2 (Decision test — §1.7 mode = stage vs opt-out)
**Target**: Decision Log entry "§1.7 mode = stage" (`research-log.md:44`) and
Surprises entry "§1.7 staging vs opt-out is a real tension here" (`:111`)
**Challenge**: The staged choice has a self-defeating shape the log names but
under-weights: staging keeps the live (buggy) `create-final-design.md` §Step 6
in force for *this* branch's own Phase 4, so the fix cannot self-apply. The
log dismisses this as "minor" on the ground that a minimal branch runs no
`edit-design phase4-creation` and therefore produces no `design-mutations.md`
append to trip the live command. That ground is correct but rests on an
unstated invariant — that a minimal (`design_gate=no`) Phase 4 also produces
none of the *other* remnants the same bug hits (untracked params files, the
plan `[>]`→`[x]` marker flip on the tracked plan file). If a minimal Phase 4
does flip a tracked plan marker or leave any untracked `_workflow/` file, the
branch's own cleanup would still abort under the live `git rm -r`.
**Evidence**: Verified the dismissal holds. `edit-design` SKILL.md §Step 7 is
`phases=1,4`, but the phase-4 append only fires under `phase4-creation`, which
authors `design-final.md` and therefore only runs on `design_gate=yes`
(SKILL.md:1037-1047). A minimal branch has no `design.md`, no
`design-mutations.md`, and no phase4-creation loop, so no tracked-file
modification and no params files are produced. The plan marker `[>]` is
Phase-4-only (`conventions.md:403`) and applies to a *plan* file, which a
minimal (no-plan) branch does not have. So the self-application risk really is
absent *for this branch* — the decision HOLDS. The finding is that the "minor"
rationale should state the invariant it depends on, so a future non-minimal
staged branch does not read "self-application risk is minor" as general.
**Proposed fix**: Strengthen the Decision Log rationale to say *why* the risk
is absent here specifically: "minimal branch has no plan file (no marker flip)
and no phase4-creation (no design-mutations append or params files), so its own
Phase 4 leaves nothing under `_workflow/` for the live `git rm -r` to choke
on." Optionally note that the general staged-mode self-application gap remains
open for design_gate=yes staged branches (a separate, pre-existing property,
not this fix's regression).

### A3 [suggestion]
**Certificate**: C3 (Assumption test — the descriptive-site enumeration is complete)
**Target**: Surprises entry "Bug confirmed live; full site enumeration" (`research-log.md:89`)
**Challenge**: The log claims "full site enumeration" of `git rm` mentions and
lists two operative sites plus five descriptive sites. A sixth match exists
that the log does not mention: `.claude/scripts/tests/fixtures/
review-file-valid-strategic.md:33` — "The blanket recursive `git rm -r
_workflow/` already sweeps review files." If that were a live procedural or
descriptive claim, omitting it would leave a contradiction after the fix.
**Evidence**: Verified this is a *correct* omission, not a gap. The line is the
body of a fake finding (`### T2`) inside a **test fixture** whose sole purpose
is exercising the count-validation regex `grep -cE '^### [A-Z]+[0-9]+ '`
(fixture header, lines 13-25). It is illustrative example text, not a live
cleanup instruction or a descriptive claim about the real command, so the fix
must not touch it. The assumption "enumeration of *load-bearing* sites is
complete" HOLDS; the six-site grep total is fully accounted for (2 operative +
4 descriptive-to-reconcile + 1 non-load-bearing fixture).
**Proposed fix**: Add one line to the enumeration surprise recording the
verified-negative — "the sixth `git rm -r` match, in the
`review-file-valid-strategic.md` test fixture, is illustrative finding-body
text and is deliberately out of scope" — so a later reader (or a Phase-C
workflow-consistency reviewer that greps `git rm`) does not re-flag it as a
missed site.

## Evidence base

#### C1 — Assumption/Open-question test: the bug is live and unfixed at both operative sites (drives A1)
- **Claim**: The fix presupposes `git rm -r docs/adr/<dir>/_workflow/` is the
  live command at both sites and aborts on the Phase-4 working-tree state; the
  log leaves this open in `## Open Questions` while asserting it resolved in
  `## Surprises`.
- **Stress scenario**: A later develop branch already migrated the command to
  `git rm -rf` + `rm -rf`, or already corrected the "sweeps automatically"
  prose — in which case the fix is partly or wholly redundant and the log's
  scope is wrong.
- **Code evidence**: `prompts/create-final-design.md:609` = `git rm -r
  docs/adr/<dir-name>/_workflow/` (bare); `workflow.md:764` = mirror bare
  `git rm -r`; "the recursive `git rm -r` sweeps the `reviews/` directories
  automatically" at `create-final-design.md:617`; "The blanket recursive
  `git rm` sweeps the review-file directories automatically" at
  `workflow.md:769`; blanket-`git rm -r` descriptive prose at
  `conventions-execution.md:372` and `:747`; cleanup-table row at
  `commit-conventions.md:153`. Empirical git repro: `git rm -r <dir>/` on a dir
  with one modified tracked file exits 1 ("the following file has local
  modifications"); `git rm -rf <dir>/` succeeds but the untracked sibling
  survives on disk. Confirms bug live + three-part fix correct.
- **Verdict**: BREAKS — the log carries the open question and the resolved
  assertion in two different sections without folding the resolution into the
  Decision Log; the gate requires the answer land as a decision.

#### C2 — Decision test: §1.7 stage vs opt-out; self-application risk (drives A2)
- **Chosen approach**: Workflow-modifying, staged mode (§1.7 staging); edits
  accumulate under `_workflow/staged-workflow/`, promoted in Phase 4. Live
  (buggy) command stays in force for this branch's own Phase 4.
- **Best rejected alternative**: §1.7(k) prose-rule opt-out (edit live), which
  *would* let the fix self-apply on this branch's own cleanup.
- **Counterargument trace**:
  1. Under staging, this branch runs the live bare `git rm -r` at its own
     Phase 4 (`create-final-design.md:609`), so the fix does not protect its
     own cleanup.
  2. The opt-out would edit the live file, self-applying the fix — but
     `create-final-design.md` §Step 6 and `workflow.md` §Final Artifacts are
     executable procedure a running Phase 4 reads, so §1.7(k) criterion 2
     (all consumers judgment-layer) is not met; the opt-out is unavailable by
     the literal criterion.
  3. The residual worry is whether this branch's own minimal Phase 4 leaves
     any `_workflow/` state the live bare `git rm -r` would choke on.
- **Codebase evidence**: `edit-design` SKILL.md:1037-1047 — the Phase-4
  `design-mutations.md` append only fires under `phase4-creation`
  (`design_gate=yes`); a minimal branch has none. Plan marker `[>]` is
  Phase-4-only and lives on a plan file (`conventions.md:403`); a minimal
  branch has no plan. So a minimal Phase 4 leaves no modified tracked file and
  no untracked params under `_workflow/`.
- **Survival test**: HOLDS — the staged choice is forced (opt-out criterion
  not met) and the self-application gap is genuinely absent for this minimal
  branch. Rationale is sound; A2 only asks the log to state the invariant its
  "minor" claim depends on, so the reasoning is not read as general.

#### C3 — Assumption test: the site enumeration is complete (drives A3)
- **Claim**: "Full site enumeration" — the log's list of `git rm` sites covers
  every mention that the fix must touch or reconcile.
- **Stress scenario**: A `git rm` mention exists outside the log's list that,
  if a live claim, would contradict the fix after it lands.
- **Code evidence**: `grep -rn "git rm" .claude/` returns 11 lines; the log
  accounts for 2 operative + 4 descriptive workflow-prose sites
  (`create-final-design.md:617`, `workflow.md:769`,
  `conventions-execution.md:372/747`) + the `commit-conventions.md:153` table
  row + the `workflow.md:695/757` verdict-fold references. The one match not
  named is `.claude/scripts/tests/fixtures/review-file-valid-strategic.md:33`,
  which the fixture header (lines 13-25) shows is fake finding-body text for
  the count-validation regex, not a live cleanup claim.
- **Verdict**: HOLDS — enumeration of load-bearing sites is complete; the
  unnamed match is correctly out of scope. A3 only asks that the
  verified-negative be recorded so a later grep-based reviewer does not
  re-flag it.
