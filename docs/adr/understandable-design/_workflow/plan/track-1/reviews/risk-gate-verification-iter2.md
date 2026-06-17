<!-- MANIFEST
review_type: risk
role: reviewer-risk
phase: 3A
track: track-1
iteration: 2
kind: verdict-producer
overall: PASS
findings: 1
blockers: 0
should_fix: 0
suggestions: 1
verdicts:
  - id: R1
    sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
    note: "Cross-track seam paragraph (Plan of Work step 2, lines 264-276) + sizing justification (lines 419-422) name the no-auditor-owner-until-Track-2 gap and pick the 'Track 2 migrates the target=tracks arm' resolution. Substance resolved; one stale pointer spun off as R5."
  - id: R2
    sev: should-fix
    disposition: ACCEPTED
    verdict: VERIFIED
    note: "Plan of Work step 3 (lines 296-300) states the warm-up is a cost lever not a correctness dependency: tunable delay + measured fallback, byte-identical assumption verified at Phase B against the live Agent-tool assembly, loop must produce correct dual-clean output with warm-up disabled."
  - id: R3
    sev: suggestion
    disposition: ACCEPTED
    verdict: VERIFIED
    note: "Validation and Acceptance (lines 348-354) adds the prose-shaped-not-coverage-gated bullet: static read vs named S-invariant + workflow-reindex.py --check; the three loop-runtime properties go to a worked dry-run. Both anchors confirmed live (script present; readability-feedback SKILL line 61)."
  - id: R4
    sev: suggestion
    disposition: ACCEPTED
    verdict: VERIFIED
    note: "Signatures / contracts (lines 428-429) points the author at the existing output_path + partial-fetch idiom (the design-review.md phase4-creation branch, live lines 91-93 / 378-384), not a new return discipline."
index:
  - id: R5
    sev: suggestion
    anchor: "### R5 [suggestion]"
    loc: "track-1.md §Plan of Work step 2 (line 274)"
    cert: "Stale cross-reference introduced by the R1 fix"
    basis: "Read track-1.md §Invariants & Constraints (lines 436-445) end-to-end; no co-promotion bullet present"
regression_scan:
  checked: "co-promotion claim vs §Invariants & Constraints; staging/S7 coherence; S4 one-owner vs R1 resolution; R2 graceful-degradation vs S5; R4 idiom vs live design-review.md"
  result: "one stale pointer (R5); no correctness regression"
-->

# Risk Gate Verification — Track 1 (iteration 2)

All four iteration-1 risk findings (R1, R2 should-fix; R3, R4 suggestion) were
ACCEPTED and their fixes land in the now-edited track file. Each is VERIFIED on
substance. The R1 fix introduced one stale cross-reference (its prose points at a
co-promotion constraint "recorded in `## Invariants & Constraints`" that the
section does not contain); the chosen R1 resolution makes that constraint
unnecessary, so this is a documentation-pointer inaccuracy, not a correctness
gap. It is spun off as a new suggestion-severity finding R5. No should-fix or
blocker regression. Overall verdict: PASS.

The branch is `§1.7(b)` workflow-modifying (ledger `s17 = workflow-modifying`,
last line wins, phase=A) and nothing is staged yet (`_workflow/staged-workflow/`
absent), so every `.claude/**` re-check resolved to the live develop-state file
per `§1.7(d)`. Track 1 carries no Java symbols; references were verified as
workflow paths and `§`-anchors via grep + Read, no PSI.

#### Verify R1: de-warm of `design-review.md` removes the prose axis from `target=tracks` (a Track 2 surface) too
- **Original issue**: Step 2 de-warms `design-review.md` by dropping the § Prose
  AI-tell additions block, but that block's applies-to set names `target=tracks`
  (spawned only by `create-plan` Step 4b, a Track 2 file). Track 1 landing alone
  would strip the prose axis from the track surface with no auditor owner there
  until Track 2 — the diluted-pass-removal regret on the track surface. R1 asked
  for (1) Step 2 made explicit about both surfaces, and (2) a recorded cross-track
  constraint (co-promote, or keep the `target=tracks` arm until Track 2).
- **Fix applied**: a new "Cross-track `target=tracks` seam (R1 / A1)" paragraph in
  `## Plan of Work` step 2, plus a restatement in the `## Interfaces` sizing
  justification.
- **Re-check**:
  - Track-file location: `## Plan of Work` step 2, lines 264-276; `## Interfaces
    and Dependencies` sizing justification, lines 419-422.
  - Current state: the paragraph states the § Prose AI-tell block's applies-to set
    names `target=tracks` as well as the three `target=design` kinds; that
    `target=tracks` is spawned only by `create-plan` Step 4b (a Track 2 file);
    that removing the block de-warms the track surface with no auditor owner there
    until Track 2; that staging keeps the whole branch non-live until one Phase 4
    promotion so the **live** workflow never sees a no-prose-axis window, the gap
    living only in the staged tree between the two tracks. It picks the resolution
    "Track 1 removes the prose axis from the `target=design` kinds and leaves the
    `create-plan` Step 4b `target=tracks` spawn for Track 2 to migrate alongside
    the track-path auditor (D9)" — one of R1's two sanctioned resolutions. The
    sizing justification names the same seam as the cut's accepted, transient cost.
  - Criteria met: instruction-completeness (the cross-track ordering risk is now
    flagged with an explicit decomposition decision); breakage-of-dependent-prompts
    (the `create-plan` Step 4b dependency is named, owner assigned to Track 2);
    context-budget / rule-coherence unaffected.
- **Regression check**: checked the chosen resolution against S4 (line 440 — every
  prose-judged surface runs the axis on exactly one reviewer, never neither): with
  Track 2 migrating the `target=tracks` arm and staging deferring all edits to one
  Phase 4 promotion, S4 holds on the live workflow at every point (no live window
  with the track surface at zero owners). Checked against S7 staging (line 442):
  consistent. **One new issue**: the seam paragraph (line 274) asserts "The
  co-promotion constraint is recorded in `## Invariants & Constraints`," but that
  section (lines 437-445: S1-S5, S7, and the §1.7(b)/D12/by-reference Constraint
  bullets) contains no co-promotion or must-co-deliver bullet. The pointer is
  stale. Because the chosen resolution (Track 2 migrates the arm under one Phase 4
  promotion) removes any live no-prose-axis window regardless of PR merge order,
  no literal co-promotion constraint is actually required — so this is a dangling
  reference, not a missing safeguard. Spun off as R5 (suggestion).
- **Verdict**: VERIFIED (R1's substance — name the seam, assign the owner, pick a
  resolution — is resolved; the stale pointer is a separate suggestion-level
  finding, not a re-open of R1).

#### Verify R2: fan-out warm-up has no live precedent and rests on unverified timing constants
- **Original issue**: D13/D14's warm-up sequences a fan-out behind a fixed ~1-min
  delay on three unverified runtime assumptions (delay-enough-to-propagate,
  byte-identical-prompt cache sharing, 5-min TTL window), with no live workflow
  precedent for a delay-sequenced fan-out. R2 asked that the warm-up be stated as
  a cost lever with a measured-fallback tunable, the byte-identical requirement be
  verified at implementation, and acceptance require correct output with the
  warm-up disabled — so correctness never depends on the cache lever.
- **Fix applied**: a sentence in `## Plan of Work` step 3 restating the warm-up's
  status and acceptance contract.
- **Re-check**:
  - Track-file location: `## Plan of Work` step 3, lines 296-300.
  - Current state: "The warm-up is a cost lever, not a correctness dependency: its
    delay is a tunable with a measured fallback, the byte-identical-prompt
    assumption is verified against the live `Agent`-tool prompt assembly at Phase
    B, and the loop must produce correct dual-clean output with the warm-up
    disabled (R2)." All three of R2's asks are present: tunable + measured
    fallback; byte-identical verification deferred to Phase B against the live tool
    assembly; the graceful-degradation acceptance contract (correct dual-clean
    output with warm-up disabled).
  - Criteria met: cost-realism is bounded to a tunable; prompt-design soundness
    (the loop is not built to require a warm cache); the failure mode stays
    graceful.
- **Regression check**: checked against S5 (line 441 — dual-clean exit / iteration
  budget): the "correct dual-clean output with the warm-up disabled" clause aligns
  with S5's exit condition and does not weaken it. D13 Risks/Caveats (lines
  121-122) still names the long-first-turn failure mode honestly; no contradiction
  introduced. Clean.
- **Verdict**: VERIFIED.

#### Verify R3: 85/70 code-coverage target is N/A to prose steps
- **Original issue**: the risk-review coverage criterion (85% line / 70% branch)
  is a JaCoCo code metric; every Track 1 step edits `.claude/**` Markdown and
  yields no Java line, so the literal target is not applicable and a decomposition
  silently inheriting it could not satisfy it. R3 asked that acceptance be stated
  as prose-shaped (static invariant read + `workflow-reindex.py --check`), with the
  loop-runtime properties flagged for a worked dry-run.
- **Fix applied**: an "Acceptance is prose-shaped, not coverage-gated" bullet in
  `## Validation and Acceptance`.
- **Re-check**:
  - Track-file location: `## Validation and Acceptance`, lines 348-354.
  - Current state: the bullet states every step edits `.claude/**` markdown so the
    85/70 gate does not apply; each step's acceptance is a static read of the
    staged file against its named S-invariant plus `workflow-reindex.py --check`;
    the three loop-runtime properties (S5 dual-clean exit, S3 freeze-order, the D13
    warm-up) are checked by a worked dry-run. Matches R3's proposed fix.
  - Criteria met: testability — the acceptance shape now fits the artifact type;
    no silent inheritance of an inapplicable numeric gate.
- **Regression check**: confirmed the two cited mechanisms are live —
  `.claude/scripts/workflow-reindex.py` exists (executable) and
  `readability-feedback/SKILL.md:61` documents `--check` for TOC integrity after
  `.claude/workflow/**` edits. The S-invariant names referenced
  (S3/S5) match the `## Invariants & Constraints` section. Clean.
- **Verdict**: VERIFIED.

#### Verify R4: by-reference orchestration is realizable with the existing output_path idiom
- **Original issue**: the track makes by-reference orchestration a hard
  requirement (author spawns return a thin summary, never the drafted content) and
  Track 2's D15 regresses without it. R4 (a VALIDATED finding) suggested pointing
  the author at the existing `output_path` + partial-fetch idiom so the implementer
  reuses the proven pattern rather than inventing a return discipline.
- **Fix applied**: a sentence in `## Signatures / contracts`.
- **Re-check**:
  - Track-file location: `## Signatures / contracts`, lines 428-429.
  - Current state: "The author realizes the by-reference contract with the existing
    `output_path`-plus-partial-fetch idiom the review spawns already use (the same
    one `design-review.md`'s `phase4-creation` branch uses), not a new return
    discipline (R4)."
  - Criteria met: the by-reference assumption is now grounded in a live,
    precedented pattern; de-risks Track 2's D15.
- **Regression check**: confirmed the cited idiom is live in
  `design-review.md` — `output_path` is the optional path written for the
  `phase4-creation` cold-read (lines 91-93), and the path-conditional output block
  (lines 378-384) writes the full Markdown to that path and returns only a short
  summary. The pointer is accurate. Clean.
- **Verdict**: VERIFIED.

## Findings

### R5 [suggestion]
**Certificate**: Consistency — stale cross-reference introduced by the R1 fix
**Location**: `track-1.md` `## Plan of Work` step 2, line 274.
**Issue**: The R1 seam paragraph states "The co-promotion constraint is recorded
in `## Invariants & Constraints`." Reading `## Invariants & Constraints`
end-to-end (lines 437-445) finds S1-S5, S7, and three `Constraint (...)` bullets
(§1.7(b) workflow-modifying, D12 Mermaid-only, by-reference thin-summary) — none
of which is a co-promotion or must-co-deliver cross-track constraint. The pointer
resolves to nothing.
**Why it is only a suggestion**: the R1 resolution chosen in the same paragraph
(Track 1 leaves the `target=tracks` arm for Track 2 to migrate, and staging defers
every edit to one Phase 4 promotion) means there is no live no-prose-axis window
regardless of PR merge order, so a literal co-promotion constraint is not actually
needed for correctness. The defect is a dangling reference, not a missing
safeguard. A workflow-consistency reviewer (or the Phase C consistency pass) would
also flag the phantom anchor.
**Proposed fix** (pick one): (a) replace "is recorded in `## Invariants &
Constraints`" with a self-contained clause, e.g. "is captured here as a known
stacked-diff seam" (drop the section pointer, since staging already removes the
live risk); or (b) add a one-line `Constraint (cross-track seam, R1/A1):` bullet to
`## Invariants & Constraints` stating the `target=tracks` arm stays for Track 2 and
the staged-tree gap closes at the single Phase 4 promotion — then the pointer
resolves. Either closes the dangling reference. No correctness impact.

## Summary
PASS. R1, R2, R3, R4 all VERIFIED. One new suggestion-severity finding (R5): the
R1 fix left a stale `## Invariants & Constraints` pointer; the underlying risk it
points at is already neutralized by the chosen resolution + staging, so it is a
documentation-pointer fix, not a re-open. No blocker or should-fix regression.
