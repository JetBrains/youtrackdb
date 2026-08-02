<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 1, suggestion: 1}
index:
  - {id: T17, sev: should-fix, loc: docs/adr/gremlin-to-match-translator-design/_workflow/implementation-plan.md:640, anchor: "### T17 ", cert: C28, basis: "the Scope paragraph now says ~22–30 files; the ESCALATE trigger 22 lines above still reads ~18–26"}
  - {id: T18, sev: suggestion, loc: docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-9.md:117, anchor: "### T18 ", cert: C29, basis: "two sites outside T12's enumerated five still promise the JMH baseline as an in-track deliverable"}
verdicts:
  - {id: T1, verdict: VERIFIED}
  - {id: T2, verdict: VERIFIED}
  - {id: T3, verdict: VERIFIED}
  - {id: T4, verdict: VERIFIED}
  - {id: T5, verdict: VERIFIED}
  - {id: T6, verdict: VERIFIED}
  - {id: T7, verdict: VERIFIED}
  - {id: T8, verdict: VERIFIED}
  - {id: T9, verdict: VERIFIED}
  - {id: T10, verdict: VERIFIED}
  - {id: T11, verdict: VERIFIED}
  - {id: T12, verdict: VERIFIED}
  - {id: T13, verdict: VERIFIED}
  - {id: T14, verdict: VERIFIED}
  - {id: T15, verdict: VERIFIED}
  - {id: T16, verdict: VERIFIED}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 5, matches: 3}
cert_index:
  - {id: C27, verdict: CONFIRMED, anchor: "#### C27 "}
  - {id: C28, verdict: PARTIAL, anchor: "#### C28 "}
  - {id: C29, verdict: PARTIAL, anchor: "#### C29 "}
  - {id: C30, verdict: CONFIRMED, anchor: "#### C30 "}
  - {id: C31, verdict: CONFIRMED, anchor: "#### C31 "}
flags: [CONTRACT_OK]
-->

# Track 9 technical review — gate verification, iteration 3

**PASS.** T1 is closed: the mermaid's `Switch` node is gone, the diagram is valid, and a whole-file sweep of both documents finds no surviving presupposition that `BoundaryOutputType.LIST` or a `projectOrSkip` `LIST` arm gets built. T8 clause (c) is closed on its substance — the Scope estimate now reads ~22–30 files and names the `~20-25` split-candidate bound explicitly. T12 through T16 all landed as proposed. No blocker survives iteration 3, so the escalation condition in `review-iteration.md` §Limits does not fire and the track can enter decomposition.

Two new findings, neither blocking. T17 is the one that matters: the corrected file count did not reach the ESCALATE trigger 22 lines above the Scope paragraph, so one plan file now states two different sizes for the same track and the sizing gate reads the stale one. It is a one-token edit and needs no further review iteration. T18 is wording drift at two sites T12 did not enumerate.

The 2a/2b/3a/3b renumbering is clean. Every internal cross-reference tracks the new numbers, and no external file references a Track 9 sub-item at all — `implementation-plan.md` and `plan/track-10/core-compliance-failure-dispositions.md` cite only `item 1`, `item 1a`, and `item 6`, none of which moved (C30).

**Tooling caveat.** `steroid_execute_code` times out on this repository, so the two code checks below (`ResultShaping.withListShapingOps`, `OrderGlobalStepRecogniser`) are file reads, not PSI. Both are declaration-existence checks, where grep is reliable. Every other check in this pass is a text sweep over the two workflow documents, where grep is exact.

## Verification certificates

#### Verify T1: `BoundaryOutputType.LIST` is the wrong mechanism
- **Original issue**: item 2 pinned `fold()` to a new `BoundaryOutputType.LIST` constant plus a `projectOrSkip` `LIST` arm. Iteration 2 closed the prose and left two sites: the mermaid `Fold --> Switch` edge and line 39's "`LIST` drain".
- **Fix applied**: `Switch` deleted; `Fold["fold → ListShapingOp drain\n(no BoundaryOutputType constant)"] --> Reg` plus a new `Seam` node; line 39 now reads "drain / flat-map / value-transform / ring-buffer `ListShapingOp`s are applied".
- **Re-check**:
  - Sweep (C27): every remaining `BoundaryOutputType` token in `track-9.md` is a negation (lines 33, 35, 66, 90, 114) or the unrelated T10 four-constants correction (line 53). Every `projectOrSkip` token describes it as a per-row projector with four arms (lines 35, 41, 66, 114, 118). No bare `LIST` token survives outside `List`/`listShapingOps`. Plan-side: `:664` records the drop, `:679` repeats it, the table row at `:702` reads "as ordered `ListShapingOp`s".
  - Mermaid validity: six nodes (`Term`, `Reg`, `Boundary`, `Fold`, `Seam`, `Harden`), four edges, every edge endpoint declared. No dangling id. `Harden` is edgeless, as it was before the edit. The `\n` label separator matches the diagram's four pre-existing labels, so the edit introduces no new syntax.
  - Criteria met: prose and diagram now state one mechanism.
- **Regression check**: the `Seam` node names "item 2a", which matches T13's renumbering (C30). `Fold` duplicates part of `Term`'s label, but it carries the T1 negative claim the diagram exists to make. Clean.
- **Verdict**: VERIFIED

#### Verify T8: item 1a's blast radius reaches GQL
- **Original issue**: three clauses. (a) and (b) closed at iteration 2; (c), the file estimate, did not.
- **Fix applied**: item 1a's cost 3–5 → 5–8 files with the GQL witness named; the seam / child gate / `ListShapingOp` / javadocs / `jmh-ldbc` test enumerated; `BoundaryOutputType` dropped from the list; a new "Net: **~22–30 files**, above the ~20-25 split-candidate bound" sentence pointing the adversarial review at the new figure.
- **Re-check**: the arithmetic is stated and coherent — ~15–21 base plus 5–8 for item 1a plus the Phase A additions reaches ~22–30. The `~20-25` bound is real (`conventions.md:353`, `planning.md:683`), so the comparison is against a live threshold rather than an invented one. Clause (c) as written — "revise the file estimate" — is satisfied.
- **Regression check**: the ESCALATE trigger at `:640` still reads "~18–26 files" (C28), so the plan states two sizes for one track. That is the harm iteration 2 named; it is carried forward as T17 rather than held against clause (c), whose named fix range (`:663-671`) was edited correctly. `plan/track-10.md:262` carries the same stale figure, but that is a completed track's file and editing it is user-pause-gated.
- **Verdict**: VERIFIED

#### Verify T12: the track still promises a JMH baseline
- **Fix applied**: "harness" now in the track title, the plan checklist heading `:643`, the checklist body `:649`, and the implementation-state paragraph `:689`; the Purpose clause reads "a Gremlin-on-vs-off JMH harness lands and compiles … The baseline **numbers** are captured out of track on Hetzner".
- **Re-check**: all five enumerated sites corrected, and the plan table row at `:702` (already correct) now agrees with the heading three lines above it.
- **Regression check**: two unenumerated sites still say "JMH baseline" (C29) — `track-9.md:117`, a live claim about what the track validates, and `implementation-plan.md:628`, a strategy-refresh record. Produces T18.
- **Verdict**: VERIFIED

#### Verify T13: the append seam is numbered after its consumer
- **Fix applied**: the seam is now item 2a and the fold recogniser 2b, under a new item 2 lead that states the dependency; old 3b/3c renumbered to 3a/3b. The seam paragraph gained both requested limits and names `ResultShaping.withListShapingOps(@Nonnull List<ListShapingOp>)`.
- **Re-check**: reading order now equals dependency order. The two limits are present verbatim in substance — `setResultShaping` "**remains a full replace** of the whole record including `listShapingOps`", and D3's last-step rule plus `UnionStepRecogniser`'s pre-suffix `setResultShaping(agreedShaping)` named as what keeps them apart. The signature matches `ResultShaping.java:106`.
- **Regression check**: full cross-reference sweep (C30). Internal: line 48 "item 2a", line 62 "2b's … is 2a's", line 77 "(4a)", line 115 "once 2a adds the append path" — all correct. No `3c` token survives. External: no file outside `track-9.md` cites a Track 9 sub-item other than `1a`. Clean.
- **Verdict**: VERIFIED

#### Verify T14: 4a's gate scope is ambiguous
- **Fix applied**: 4a now states the gate is "**deliberately blanket rather than fold/tail-only**", gives the reason (no op-type discriminator on `ListShapingOp`, and adding one is not this track's work), names the cost (`union(__.unfold(), __.unfold())` declines as collateral, no worse than today, a Phase 2 shape), and closes with the disjointness of the child gate and the suffix path.
- **Re-check**: matches the blanket branch of the proposed fix. The acceptance bullet correctly stays fold/tail-only, since the alternative accepting-`unfold` case belongs only to the narrow branch that was not chosen.
- **Regression check**: the disjointness sentence restates C24's trace accurately and does not contradict acceptance line 96. Clean.
- **Verdict**: VERIFIED

#### Verify T15: the doc-sync list names a javadoc that is already current
- **Fix applied**: line 115 now reads "on `RecognitionContext.setResultShaping` and on `WalkerContext.shaping` — whose 'a terminator replaces it through `setResultShaping`' clause becomes actively wrong once 2a adds the append path, while `AbstractMatchPlanStep.shaping` is already current and needs no edit (T15 corrects T10 here)".
- **Re-check**: exactly the swap requested, with the reason. `WalkerContext` was already in "In scope (modified)" for the seam, so no new file enters scope.
- **Regression check**: the list is introduced as "three stale sibling javadocs" while enumerating four edit sites (`UnionStepRecogniser`, `RecognitionContext`, `WalkerContext`, `BoundaryOutputType`). The miscount predates this fix — the pre-edit text enumerated four the same way — and the sites are named individually, so a decomposer reads the list, not the count. Not worth a finding.
- **Verdict**: VERIFIED

#### Verify T16: positional terminators against a multiset invariant
- **Fix applied**: a new `## Validation and Acceptance` bullet at line 94 scoping `tail(n)` and `fold()` — element-for-element against native for ordered inputs, unordered multisets otherwise, positional-on-unordered Cucumber scenarios to item 1's triage bucket, with the downgrade clause if the first run shows them passing.
- **Re-check** (C31): the bullet is the proposed fix. Both citations hold — `implementation-plan.md:365` carries the multiset sentence word for word, and `OrderGlobalStepRecogniser.java` exists, so the "ordered input" escape hatch is real rather than assumed.
- **Regression check**: line 90's unqualified "All match native" now sits above a bullet that scopes it. The scoping bullet names the terminators it governs, so the two read as general rule plus exception rather than as a contradiction. Line 96's union-multiset line is unaffected.
- **Verdict**: VERIFIED

#### Verify T2–T7, T9–T11 (carried from iteration 2)
- **Status**: VERIFIED at iteration 2, re-checked here only for cascade damage from the seven edits above.
- **Re-check**: the T13 renumbering moved the seam (T2) and the two item-3 sub-items (T5, T11) without changing their bodies; the T1 diagram edit touches no claim these findings rest on; the T14 amendment extends 4a (T3) without altering its gate. `ListShapingOp`'s two javadoc edits (T5's `unfold` line, T6's once-per-child clause) remain distinct clauses in one file.
- **Verdict**: VERIFIED, no regression

## Findings

### T17 [should-fix]
**Certificate**: C28 (the two file-count figures in `implementation-plan.md`)
**Location**: `implementation-plan.md:640`

**Issue**: T8's fix revised the Scope estimate to "Net: **~22–30 files**, above the ~20-25 split-candidate bound". The forward-risk paragraph 22 lines above it was not revised and still reads "Track 9 at ~18–26 files with an unsized Cucumber bucket could be the third; if decomposition crosses it, the response is ESCALATE rather than a silently oversized track."

One file, one track, two sizes. The stale one is in the sentence that carries the ESCALATE instruction, and ~18–26 straddles the `~20-25` bound while ~22–30 sits clearly above it — so the two figures imply different answers to the question the sentence asks. The same paragraph's other premise also drifted: "with an unsized Cucumber bucket" predates the enumeration of the bucket's process-compliance half.

**Proposed fix**: At `:640`, change "~18–26 files" to "~22–30 files" and drop or qualify "unsized" so it reads as the Cucumber half only. One line, no further review iteration needed. `plan/track-10.md:262` carries the same stale figure in a completed track's file; leave it, or amend it under the user-pause gate if the orchestrator is touching that file anyway.

### T18 [suggestion]
**Certificate**: C29 (the "JMH baseline" wording sweep)
**Location**: `plan/track-9.md:117`; `implementation-plan.md:628`

**Issue**: T12 enumerated five sites and all five were fixed, but the sweep it was built from missed two.

- `track-9.md:117`, closing `## Interfaces and Dependencies`: "Last Phase 1 track; validates every prior track via the full Cucumber re-run and the JMH baseline." This is a live claim about the track's validation mechanism, and it names as a validator the exact artifact acceptance line 99 says is not a gate.
- `implementation-plan.md:628`, the Track 10 strategy-refresh record: "Track 9's shape holds (terminators, the Cucumber gate, the JMH baseline, item 1a all survive)".

**Proposed fix**: At `:117` replace "the JMH baseline" with "the JMH harness". At `:628` the same swap, or leave it as a dated record of what was true when it was written.

## Evidence base

#### C27 Integration: does any site in either document still presuppose `BoundaryOutputType.LIST`?
- **Amendment claim**: the mermaid `Switch` node is gone and line 39's `LIST` qualifier is dropped, completing T1.
- **Search performed**: `grep -n "BoundaryOutputType\|projectOrSkip\|\bLIST\b"` over `track-9.md`; `grep -n "BoundaryOutputType"` over `implementation-plan.md`; full read of the mermaid block and of `## Context and Orientation`, `## Validation and Acceptance`, `## Interfaces and Dependencies`.
- **Location**: `track-9.md:33, 35, 41, 47, 53, 66, 90, 114, 118`; `implementation-plan.md:664, 679, 702`
- **Actual behavior**: eleven `BoundaryOutputType` / `projectOrSkip` sites across the two files. Every one either negates the constant, describes `projectOrSkip` as a four-arm per-row projector, or discusses the enum's four existing constants under T10. No bare `LIST` token survives. The diagram declares six nodes and four edges with no undeclared endpoint.
- **Verdict**: CONFIRMED
- **Detail**: T1 closes. The `Seam` node's "item 2a" label is consistent with the renumbering, so the diagram edit and the T13 edit agree.

#### C28 Premise: the plan states one file count for Track 9
- **Amendment claim**: `:662-681` — the Scope paragraph's net figure is ~22–30 files, above the `~20-25` split-candidate bound.
- **Search performed**: `grep -rn "18–26\|15–21\|22–30"` over `_workflow/`; read of `:636-684`; `grep -rn "20-25\|split-candidate"` over `.claude/workflow/`.
- **Location**: `implementation-plan.md:640, 662, 679`; `plan/track-10.md:262`; `.claude/workflow/conventions.md:353`, `planning.md:683`
- **Actual behavior**: three figures. `:662` "~15–21 files" is the Phase 1 base and reads correctly as such given the running total that follows. `:679` "Net: ~22–30 files" is the current total. `:640` "~18–26 files" is the superseded intermediate, and it sits inside the ESCALATE-trigger sentence. The `~20-25` bound is a real workflow threshold, quoted accurately.
- **Verdict**: PARTIAL
- **Detail**: clause (c)'s named fix range is correct; one dependent sentence outside it is not. Produces T17.

#### C29 Integration: the "harness" retitle across both documents
- **Amendment claim**: T12's five sites now say "harness".
- **Search performed**: `grep -rn "JMH baseline"` and `grep -n "baseline"` over `_workflow/` excluding `reviews/`.
- **Location**: `track-9.md:2, 5, 117`; `implementation-plan.md:628, 643, 649, 689, 702`
- **Actual behavior**: all five enumerated sites corrected and mutually consistent. Two unenumerated sites remain. Every other `baseline` hit in `track-9.md` (lines 25, 55, 60, 78, 81, 99, 117's "enumerated baseline") is the failure-inventory or Cucumber sense, not the JMH one, and reads correctly.
- **Verdict**: PARTIAL
- **Detail**: produces T18 at suggestion severity — the two residual sites are wording, and acceptance line 99 already states the operative rule.

#### C30 Integration: does the 2a/2b/3a/3b renumbering leave a stale cross-reference?
- **Amendment claim**: T13's fix moved the seam to 2a, the fold recogniser to 2b, and renumbered 3b→3a and 3c→3b.
- **Search performed**: `grep -noE "item [0-9]+[a-z]?|\b[0-9]+[a-c]\b|\([0-9]+[a-c]\)"` over `track-9.md`; `grep -n "item [0-9]\|track-9.md item"` over `implementation-plan.md` and `plan/track-10/core-compliance-failure-dispositions.md`.
- **Location**: `track-9.md:48, 62, 64, 66, 69, 71, 74, 76, 77, 115`; `implementation-plan.md:628-633, 653, 661, 670`; `core-compliance-failure-dispositions.md:41, 48-61, 85`
- **Actual behavior**: ten internal sub-item references, all pointing at the intended sub-item under the new numbering. No `3c` token anywhere. External references are exclusively to `item 1`, `item 1a`, and `item 6` — none renumbered. `track-9.md:56-57` cites "item 4" twice, but both refer to Track 10's withdrawn item 4, not Track 9's.
- **Verdict**: CONFIRMED

#### C31 Premise: the T16 acceptance bullet's two citations resolve
- **Amendment claim**: line 94 cites the plan's multiset-equality standard and `OrderGlobalStepRecogniser` as the ordered-input escape hatch.
- **Search performed**: `grep -n "equal result multisets"` over `implementation-plan.md`; direct file check for `OrderGlobalStepRecogniser.java`. PSI unavailable, so both are declaration-existence reads.
- **Location**: `implementation-plan.md:365`; `.../translator/strategy/OrderGlobalStepRecogniser.java`
- **Actual behavior**: `:365` carries "Translator-on and translator-off produce equal result multisets for every" as quoted. The recogniser class exists at the cited package path.
- **Verdict**: CONFIRMED
- **Detail**: reference-accuracy caveat — the recogniser's *registration* (that it is wired into the walker registry, so `order().by(…)` genuinely translates) is not established by a file-existence check. The bullet's downgrade clause makes this self-correcting at item 1's first run.

## Summary

**PASS** — all sixteen prior findings verified, no blocker open, escalation not triggered.

- T1: closed. The diagram now matches the prose, it is valid Mermaid, and no site in either document presupposes the dropped constant.
- T8: closed on clause (c). The estimate is revised and measured against a real threshold; one dependent sentence carries the old number, raised as T17 rather than held against the finding.
- T12–T16: all landed as proposed.

Two new findings, no blockers: T17 should-fix, T18 suggestion. Both are single-line text edits inside `_workflow/` documents with no code or design consequence, so they do not warrant a fourth review iteration — fix them alongside decomposition. T17 should land before the decomposer reads the sizing gate, since that sentence decides whether Track 9 escalates as oversized.
