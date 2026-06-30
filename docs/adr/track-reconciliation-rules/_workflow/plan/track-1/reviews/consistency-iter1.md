<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 12, matches: 12}
cert_index: []
flags: [CONTRACT_OK]
-->

# Consistency review — Track 1 (Phase-C review iteration keyed to the complexity tag), iteration 1

**Verdict: PASS — 0 findings.** Every current-state anchor the frozen `design.md`
and `track-1.md` cite resolves to the live `.claude/` file exactly as described.
The restate-set grep hit set matches the cited line list with zero drift, the
per-track-complexity-tag reconciliation mechanism (`max(step tags)`, ledger
`reconciled_tag`, A→C boundary) exists and is not invented, and the five
gate-check verdicts and the cap-3 §Limits protocol are present with the described
semantics. No phantom file references, no wrong mechanics, no stale line numbers.

**Scope of this pass.** Single-track change (`tracks=1`), so no
`implementation-plan.md` exists — the PLAN↔CODE plan-content bullets are dropped
and only the track-reference bullet runs against `track-1.md` plus the code. The
design gate is `yes` (`design.md` present, ledger `design_gate=yes`), so the
design half runs: DESIGN↔CODE, DESIGN↔track-description, and the design-coverage
GAPS bullets. Target-state prose in the track's `## Purpose / Big Picture`,
`## Plan of Work`, `## Decision Log`, and `## Interfaces and Dependencies` is the
post-edit rule set and is pre-screened out of findings per the intent axis; only
current-state anchors are verified.

**Tooling.** This is a workflow-machinery (Markdown) change under
`.claude/workflow/**` and `.claude/skills/**`. There are no Java symbols in scope,
so mcp-steroid PSI does not apply (the IDE does not index these files). Every
verification used `grep` / `Read`, which is the correct and complete tool for
Markdown file/section/line references — no reference-accuracy caveat is warranted.

**Staged-read precedence.** Ledger carries `s17=workflow-modifying`, but Phase 3
has not run, so `_workflow/staged-workflow/` is absent (verified by `ls`). Every
`.claude/...` read therefore resolved to the LIVE file at develop state, which is
the correct anchor target for current-state verification at Phase 2.

## Findings

<!-- No findings. Every certificate below returned MATCHES / ENFORCED. -->

## Evidence base

#### Ref: track-code-review.md §Review loop dial site (≈681-693)
- **Document claim**: design.md §Overview + §Scope, track-1.md `## Context and
  Orientation` and Plan-of-Work edit 1: the §Review loop dial site today maps
  `low` → single shallow pass, `medium` → normal cap-3, `high` → iterate to
  convergence within the cap-3 ceiling. Also claims the "dial shortens optional
  iteration depth, never the must-fix gates" framing and the missing-tag →
  treat-as-`medium` safe default exist.
- **Search performed**: `grep -nE '^#+ .*[Rr]eview loop' .claude/workflow/track-code-review.md` (header at 663); `Read` of lines 663-778.
- **Code location**: `.claude/workflow/track-code-review.md:663` (§Review loop), dial mapping at 681-686, framing at 688-690, safe default at 691-693.
- **Actual signature/role**: Lines 681-686 read verbatim "`low` → a single shallow pass… `medium` → the normal cap-3 iteration below. `high` → iterate to convergence within the cap-3 ceiling (run the full three iterations…)." Line 688-690 carries "the dial shortens optional iteration depth, never the must-fix gates"; 691-693 carries "treat the loop as `medium` and run the standard cap-3 — the safe default."
- **Verdict**: MATCHES
- **Detail**: Dial site, framing, and safe default all present exactly as cited (anchor #1).

#### Ref: cap-3-keyed restate set grep over track-code-review.md
- **Document claim**: design.md §The full restate set and track-1.md Plan-of-Work edit 3 cite the grep hit set as lines 491, 527, 685, 724, 765, 832, 837, 848, 875, 1092, 1106, 1256.
- **Search performed**: `grep -nE '3 iterations|N/3|/3|of 3|three iteration' .claude/workflow/track-code-review.md`.
- **Code location**: `.claude/workflow/track-code-review.md` — hits at 491, 527, 685, 724, 765, 832, 837, 848, 875, 1092, 1106, 1256.
- **Actual signature/role**: Live hit set = {491, 527, 685, 724, 765, 832, 837, 848, 875, 1092, 1106, 1256} — identical to the cited list, twelve hits, no extras, no misses.
- **Verdict**: MATCHES
- **Detail**: Exact match, zero line drift. Anchor #2 satisfied. (The documents self-describe these as advisory/re-run-at-implementation; they happen to still be current.)

#### Ref: Restate-site current-state content — 765 (progress format)
- **Document claim**: ≈765 reads `Track-level code review iteration N complete (N/3 iterations)`.
- **Search performed**: `Read` lines 758-772.
- **Code location**: `.claude/workflow/track-code-review.md:765`.
- **Actual signature/role**: `- [x] <ISO> [ctx=<level>] Track-level code review iteration N complete (N/3 iterations)`.
- **Verdict**: MATCHES

#### Ref: Restate-site current-state content — 832-848 (Step-4 resume, pre-spawn split, Step 5)
- **Document claim**: ≈832-833 "Max 3 iterations total across sessions — on resume, read the iteration count … to determine how many remain"; ≈837 "consumes 2 of 3 iterations"; ≈848 "If blockers persist after 3 iterations, … note the unfixed findings."
- **Search performed**: `Read` lines 828-882.
- **Code location**: `.claude/workflow/track-code-review.md:832`, `:837`, `:848`.
- **Actual signature/role**: 832-833 "Max 3 iterations **total across sessions** — on resume, read the iteration count from the Progress section to determine how many remain"; 837-838 "24-finding set split into a 12+12 sequence consumes 2 of 3 iterations"; 848-851 "If blockers persist after 3 iterations, … note the unfixed findings."
- **Verdict**: MATCHES

#### Ref: Restate-site current-state content — 875 (Step-6 commit guard)
- **Document claim**: ≈875 "not when the loop exited with blockers still open after 3 iterations."
- **Search performed**: `Read` lines 868-882.
- **Code location**: `.claude/workflow/track-code-review.md:875`.
- **Actual signature/role**: "Run this commit **only on the all-reviews-pass path**, not when the loop exited with blockers still open after 3 iterations (step 5)."
- **Verdict**: MATCHES

#### Ref: Restate-site current-state content — 1092, 1106 (failure/budget)
- **Document claim**: ≈1092 `FAILED at iteration N/3`; ≈1106 "If blockers persist after 3 iterations, note them."
- **Search performed**: `Read` lines 1088-1109.
- **Code location**: `.claude/workflow/track-code-review.md:1092`, `:1106`.
- **Actual signature/role**: 1092 `- [!] <ISO> [ctx=<level>] Track-level code review FAILED at iteration N/3`; 1106 `the existing "If blockers persist after 3 iterations, note them" branch`.
- **Verdict**: MATCHES

#### Ref: Restate-site current-state content — 1256 (checklist seed)
- **Document claim**: ≈1256 `(1/3 iterations, iteration 1 …)` template.
- **Search performed**: `Read` lines 1253-1258.
- **Code location**: `.claude/workflow/track-code-review.md:1256`.
- **Actual signature/role**: `- [ ] Track-level code review (1/3 iterations, iteration 1 recovered from RESULT_MISSING via commit-as-is)`.
- **Verdict**: MATCHES

#### Ref: Restate-site current-state content — 491, 527, 724 (cost models, budget)
- **Document claim**: ≈491 "reviewers × three iterations = eighteen … spawns" (spelled-out count); ≈527 "× 3 iterations per track"; ≈724 "2 of 3 used."
- **Search performed**: `Read` lines 488-529; `Read` lines 700-726.
- **Code location**: `.claude/workflow/track-code-review.md:491`, `:527`, `:724`.
- **Actual signature/role**: 490-491 "up to six dimensional reviewers × three iterations = eighteen sub-agent spawns"; 526-527 "up to ~10 agents × 3 iterations per track"; 724 "when an iteration count is already tight (2 of 3 used)."
- **Verdict**: MATCHES
- **Detail**: Confirms the design's claim that the `three iteration` spelled-out alternative is needed to catch 491 and 685 (digit-only patterns miss them); both spelled-out hits present.

#### Ref: review-iteration.md §Limits (cap-3 protocol)
- **Document claim**: design.md §Core concepts + track-1.md `## Context and Orientation` and D2.1: §Limits carries "Max 3 iterations per review type" / "If blockers persist after 3 iterations, escalate," and its TOC filter loads it in Phase C.
- **Search performed**: `grep -nE '^#+ ' .claude/workflow/review-iteration.md`; `Read` lines 34-41 and the §Limits TOC row (line 7).
- **Code location**: `.claude/workflow/review-iteration.md:34` (§Limits), bullets at 37-38; TOC row phases `2,3A,3B,3C` at lines 7 and 35.
- **Actual signature/role**: 37 "Max 3 iterations per review type." 38 "If blockers persist after 3 iterations, escalate." Section TOC `phases=2,3A,3B,3C` — loads in Phase C as claimed.
- **Verdict**: MATCHES
- **Detail**: Anchor #3 (§Limits half) satisfied.

#### Ref: review-iteration.md §Gate-check verdict handling (five verdicts)
- **Document claim**: design.md §Core concepts (Gate-check verdict stream) + track-1.md D4.1: §Gate-check verdict handling emits exactly `VERIFIED` / `REJECTED` / `MOOT` / `STILL OPEN` / `REGRESSION`, with VERIFIED/REJECTED/MOOT clearing, STILL OPEN carrying, REGRESSION forcing FAIL.
- **Search performed**: `grep -nE 'VERIFIED|REJECTED|MOOT|STILL OPEN|REGRESSION' .claude/workflow/review-iteration.md`; `Read` lines 134-162.
- **Code location**: `.claude/workflow/review-iteration.md:134` (§Gate-check verdict handling), verdict bullets 139-161.
- **Actual signature/role**: 139 `VERIFIED` clears; 141 `REJECTED` "Clears identically to VERIFIED"; 150 `MOOT` "Clears … identical to VERIFIED"; 153 `STILL OPEN` "carry the finding into the next iteration"; 155-161 `REGRESSION` "escalate as a blocker … forces the iteration FAIL even if every other verdict is VERIFIED." Exactly the five tokens, no more.
- **Verdict**: MATCHES
- **Detail**: Anchor #3 (verdict half) satisfied — clearing/carry/FAIL semantics match.

#### Ref: review-agent-selection.md §"Complexity sets the Phase-C rigor dial, never the set"
- **Document claim**: design.md §The other dial-mapping sites + track-1.md edit 4 and Invariants: the section carries the `low`/`medium`/`high` dial-mapping prose and asserts "complexity never drops a domain-selected reviewer" / "the dial only changes iteration depth."
- **Search performed**: `grep -nE 'Complexity sets the Phase-C rigor dial|never drops|iteration depth|single shallow pass|cap-3|iterate to convergence' .claude/workflow/review-agent-selection.md`; `Read` lines 227-256.
- **Code location**: `.claude/workflow/review-agent-selection.md:227` (section), dial mapping 235-237, assertions 244-251, §Limits cross-ref 239-242.
- **Actual signature/role**: 235-237 "`low` → a single shallow pass. `medium` → the normal cap-3 iteration. `high` → iterate to convergence." 244-245 "The floor plus the domain-matched set is never suppressed by a low complexity. Complexity never drops a reviewer the domain selected." 232-233 "moves only the rigor dial — how hard the … loop iterates." Cross-ref to `review-iteration.md … §Limits` and `track-code-review.md §Review loop` at 239-242.
- **Verdict**: MATCHES
- **Detail**: Anchor #4 satisfied, including the cross-reference the design says "stays."

#### Ref: code-review/SKILL.md standalone-skill dial note (≈225)
- **Document claim**: design.md §The other dial-mapping sites + track-1.md edit 5: a standalone-skill note at ≈225 describes the dial (`low` single shallow pass / `medium` cap-3 / `high` iterate to convergence); the `/code-review` skill takes no complexity input.
- **Search performed**: `grep -nE 'single shallow pass|cap-3|iterate to convergence|complexity' .claude/skills/code-review/SKILL.md`.
- **Code location**: `.claude/skills/code-review/SKILL.md:225`.
- **Actual signature/role**: Line 225 "The per-track complexity tag … moves only the rigor dial — how hard the review-iteration loop iterates: `low` runs a single shallow pass, `medium` the normal cap-3 iteration, `high` iterates to convergence (see `review-iteration.md` § Limits and `track-code-review.md` § Review loop). … The standalone `/code-review` skill takes no complexity input and always runs the domain-selected set once."
- **Verdict**: MATCHES
- **Detail**: Anchor #5 satisfied — note is at line 225 exactly, with the "no complexity input" qualifier the track preserves.

#### Invariant: per-track complexity tag reconciled to max(step tags), read from the ledger at the A→C boundary
- **Document claim**: design.md §Overview/§Core concepts and track-1.md D1: the per-track complexity tag is `max(step tags)`, read track-scoped from the phase ledger's per-track reconciled-tag field written at the A→C boundary. (Anchor #6 — confirm the mechanism exists and the design invents no nonexistent tag source.)
- **Code evidence**: `risk-tagging.md:245-260`, `planning.md:122,172-174`, `track-review.md:494,548,600` (TOC rows 12-13), `conventions.md:84,89,122`, `inline-replanning.md:145-207`, `workflow.md:680-681`, `create-final-design.md:104-109`, and the consuming site `track-code-review.md:676-678`.
- **Mechanism**: `risk-tagging.md:245` "reconciled to `max(step tags)` … and that reconciled tag governs Phase C"; `planning.md:122` ledger per-track `reconciled_tag` "written only at the Phase A → C reconciliation"; `track-review.md` Phase A "reconcile to max(step tags), write the file"; `track-code-review.md:676-678` reads "the per-track reconciled complexity tag — `max(step tags)`, read track-scoped from the phase ledger's per-track reconciled-tag field written at the A→C boundary." The ledger `reconciled_tag` key is the schema field (`conventions.md:89`).
- **Verdict**: ENFORCED
- **Detail**: The mechanism exists across the planning/Phase-A/Phase-C chain exactly as the design describes; no invented tag source. The design and track read it, they do not introduce it.
