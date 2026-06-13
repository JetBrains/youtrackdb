<!-- MANIFEST
iteration: 3
findings: 1
severity: suggestion
prior_verdicts:
- id: A5
  verdict: VERIFIED
  basis: "Surprises line 43 now names the hook (:256 comment, :262 tier_b_body 5-slug + numeric 'five') and its pin test (TIER_B_HEADINGS) as five→six targets with exact edits spelled out; all four facts confirmed against repo. DR2/DR6 refined precisely — enumeration-sync of an existing pin is not new mechanical enforcement; hook reminder-prose edit is not control-flow logic, tier stays lite."
- id: A6
  verdict: VERIFIED
  basis: "DR3 Tier-B bullet second anchor now reads conventions.md:574-581 (line 18); the dead house-style.md:574-581 anchor is gone."
evidence_base: 3 certificates (prior-finding re-verification scan, DR2/DR6 refinement soundness check, blast-radius count-drift assumption test); reference checks via grep+Read (mcp-steroid unreachable); all load-bearing facts confirmed against repo
cert_index: RV-scan, C-refine, A-count
flags: workflow-machinery-lens; reference-accuracy-caveat (grep-only, no PSI); iteration 3 of 3 (final)
index:
- id: A7
  sev: suggestion
  anchor: "### A7 "
  loc: research-log.md DR6 (~52, line 28) / DR7 (~50, line 32) / Tier-shaping observation (~51, line 45)
  cert: A-count
  basis: assumption
-->

## Findings

### A7 [suggestion]
**Certificate**: A-count (assumption test — the blast-radius total is stated consistently across the log)
**Target**: The running blast-radius figure, now stated as three different approximate totals.
**Challenge**: Successive A1/A5 corrections re-derived the total but left three figures in the log that no longer agree: DR6 says "~52-file footprint" (line 28), DR7's scope note says "the ~50 enumeration set" (line 32), and the older Tier-shaping observation still says "~51-file footprint" (line 45). A reader reconciling them finds no single number, and the drift is the residue of editing the count in two places (DR6, DR7) while the third (the Phase-0 Tier-shaping observation) kept its original estimate. This is a suggestion, not a should-fix: every figure is explicitly approximate, the log defers the exact in-scope list to each track's `## Interfaces and Dependencies` ("enumerated exactly … at Phase 1 … not estimated", line 42), and the tier decision is insensitive to the spread — `lite` holds at 50, 51, or 52, all far above the ~20-25 track ceiling. So no decision rides on the discrepancy, but a single restated figure would stop a future reader from treating the gap as a missed file.
**Evidence**: research-log.md line 28 ("~52-file footprint"), line 32 ("the ~50 enumeration set"), line 45 ("~51-file footprint"); line 42 defers the exact list to per-track Interfaces. Reference-accuracy caveat: these are three string reads in one document, verified by Read; no symbol resolution, so PSI would not change the verdict.
**Proposed fix**: Pick one figure (the DR7 "~50 enumeration set" is the most precise — it excludes `CLAUDE.md` and the two stamp-pathspec-external files by construction) and either align the other two to it or add a one-clause note that the Tier-shaping observation is the pre-correction Phase-0 estimate. Optional — does not gate.

## Evidence base

#### RV-scan: Re-verification of the two iteration-2 findings
- **A5 — VERIFIED.** Surprises line 43 now names both the hook and its pin test as five→six targets and spells out the exact edits: add `§ Plain language` to `tier_b_body`, change "five"→"six" at `:256`/`:262`, add the code-comment carve mirroring Orientation's, and add `## Plain language` to `TIER_B_HEADINGS`. All four facts reconfirmed against the repo: hook `:256` is the comment "names the five Tier-B"; hook `:262` is the `tier_b_body` string enumerating five `§ ` slugs plus "the five sections" plus the Orientation code-comment carve; test `:693-699` is `TIER_B_HEADINGS` (the five `## ` slugs). DR2's refinement is sound — the test guards slug *presence* (`test_16_section_name_guard`, a `re.search` per heading), so appending one entry is anchor-pin maintenance, not a new check; the Tier-B path tests (`test_02`/`test_03`) match on `TIER_B_PREFIX` (the opening sentence, `:60`) and a `TIER_A_PREFIX` leak guard, neither of which the mid-string `§ Plain language` insertion nor the "five"→"six" bump disturbs. DR6's refinement is sound — the hook is a reminder string, not control flow; no new-hook-logic / schema / always-loaded-logic edit fires, so the tier stays `lite`. Both files confirmed outside the `.claude/workflow|skills|agents` stamp pathspec.
- **A6 — VERIFIED.** DR3's Tier-B bullet (line 18) now cites `conventions.md:574-581` for both references; the dead `house-style.md:574-581` anchor (out of range on a 471-line file) is gone.

#### C-refine: Decision challenge — the DR2/DR6 refinements introduced by the A5 fix
- **Chosen approach**: DR2 keeps "judgment-only, no NEW mechanical enforcement" by reclassifying the hook/test touch as enumeration-sync of an existing pin; DR6 keeps tier `lite` by classifying the hook edit as reminder-prose, not control-flow logic.
- **Best rejected alternative**: Treat any hook + test edit as a Workflow-machinery HIGH signal (a `design.md`-needing, or at least staging-requiring, change).
- **Counterargument trace**:
  1. The alternative would fire Gate 1 (needs design) or the workflow-modifying marker on the strength of "it touches a hook and a test."
  2. `risk-tagging.md` §Workflow machinery keys HIGH on control-flow / schema / new-hook-*logic* / always-loaded-*logic*, and its prose-only cap puts a prose-only edit (no gate/dispatch/schema change) at most `low`. The hook edit changes a reminder *string*; the test edit appends to an anchor-pin list. Neither alters hook control flow, a parsed schema, or a gate sequence.
  3. So the alternative over-fires: it would force design / staging on a prose-and-enumeration edit the risk model classifies as low, defeating the §1.7(k) opt-out's whole purpose.
- **Codebase evidence**: hook `:262` is a single-quoted string assignment (`tier_b_body='…'`), not logic; test `:702-728` is a presence guard (`re.search` per slug); the Tier-B path tests use prefix matching (`:231`, `:254`). The refinements track the risk model rather than work around it.
- **Survival test**: YES. The DR2/DR6 refinements are correctly scoped; the reclassification is faithful to how the hook and test actually consume the slug list.

#### A-count (folded into A7)
- **Claim**: After the A1/A5 corrections the log states one consistent blast-radius total.
- **Stress scenario**: Grep every "~5N" figure in the log and compare.
- **Code evidence**: line 28 = ~52, line 32 = ~50, line 45 = ~51; line 42 defers the exact list to per-track Interfaces.
- **Verdict**: FRAGILE (holds but barely). Three approximate figures disagree, but none is load-bearing — the exact list is deferred and the tier is insensitive to the spread. A suggestion, not a gate.
