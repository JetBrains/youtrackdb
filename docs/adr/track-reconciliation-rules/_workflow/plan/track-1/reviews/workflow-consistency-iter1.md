<!--MANIFEST
dimension: workflow-consistency
iteration: 1
findings: 2
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - { id: WC1, sev: should-fix, anchor: "### WC1", loc: ".claude/workflow/finding-synthesis-recipe.md:79", cert: n/a, basis: "Phase-C-loading synthesis recipe still renders a fixed `iteration {N}/3` denominator on the track-level routing path the change just decoupled from any /3 cap" }
  - { id: WC2, sev: suggestion, anchor: "### WC2", loc: ".claude/workflow/code-review-protocol.md:102", cert: n/a, basis: "Generic §Iteration protocol inlines `max 3 iterations` thrice under a pointer whose phases include 3C; relies on §Limits-carve-out inheritance edit 7 chose not to spell out" }
-->

## Findings

### WC1 [should-fix] Phase-C synthesis recipe still emits `iteration {N}/3` on the uncapped track-level path

- **File:** `.claude/workflow/finding-synthesis-recipe.md` (lines 79, 451; secondary at 414)
- **Axis:** threshold and table sync (cross-file `/3`-cap drift on a Phase-C-loading file)
- **Cost:** a Phase-C orchestrator following the synthesis recipe prints a `/3` denominator the track-level loop no longer has (e.g. `iteration 4/3` on a `high` track's fourth pass), contradicting the new no-cap policy this step established — the same `/3` the step deliberately stripped from `track-code-review.md`.

**Issue.** `finding-synthesis-recipe.md` is loaded on the Phase-C track-level path: every TOC row carries `phases=3B,3C`, and `track-code-review.md` §Synthesis (staged line 620) cites `finding-synthesis-recipe.md:orchestrator:3B,3C` as the routing recipe for the track-level review. The recipe's routed-findings output template hard-codes a `/3` denominator in two places — line 79 (`## Routed findings — iteration {N}/3`, the no-findings case) and line 451 (`## Routed findings — iteration {N}/3`, the Step 5 handoff). A third hit, line 414 (`when iteration counts are already tight (2 of 3 used)`), is pacing guidance loaded on the same 3C path.

This step's whole purpose is to remove the fixed `/3` cap from the Phase-C track-level loop. Edit 3 explicitly drops the `/3` denominator from the Phase-C progress line — `track-code-review.md:765` went from `Track-level code review iteration N complete (N/3 iterations)` to `iteration N complete`, and the checklist seed at `:1256` dropped `(1/3 iterations…)`. The referent here is `finding-synthesis-recipe.md` lines 79 / 451 / 414: a Phase-C-loading file that the change left rendering the exact `/3` denominator the change just declared dead for the track-level loop. The track file is half-aware of this — its tree-wide restate triage (track-1.md lines 380-383) names only `finding-synthesis-recipe.md:414` and instructs "reword only if it reads as a Phase-C bound, otherwise leave it," but never accounts for the two `iteration {N}/3` template headers at lines 79/451, which are unambiguous `/3` denominators emitted onto the Phase-C routing output. The file is also outside the six-file enumerated restate grep (track-1.md lines 369-377), so nothing in the change surface reaches it.

Not a blocker: these are output-template / pacing strings, not control flow. The loop still terminates correctly via no-progress detection regardless of what the header prints. But it is a live Phase-C-loading file contradicting the new policy on its face — the consistency dimension's core target.

**Suggestion.** The template is shared with the still-capped Phase-3B step-level loop, so do not strip `/3` wholesale (that would break the correct step-level use). Make the denominator level-aware: render `iteration {N}/3` only for the capped step-level / Phase-2 / 3A loops and a cap-free `iteration {N}` for the Phase-C track-level loop — or replace `{N}/3` with a `{N}/{cap-or-—}` placeholder the caller fills. For line 414's `(2 of 3 used)` pacing, mirror the wording the step already applied to the `track-code-review.md` pre-spawn-split rationale ("several iterations have already been spent and a no-progress escalation looks near") for the Phase-C path. Add `finding-synthesis-recipe.md` to the tree-wide restate grep file list so the gap cannot recur.

### WC2 [suggestion] `code-review-protocol.md` §Iteration protocol inlines `max 3 iterations` under a 3C pointer

- **File:** `.claude/workflow/code-review-protocol.md` (lines 9, 100, 102)
- **Axis:** cross-file rule restatement
- **Cost:** a Phase-C reader scanning the §Iteration protocol section (TOC summary line 9/100 and prose line 102) sees `max 3 iterations` with no inline carve-out, three lines after the §53 synthesis preamble that *does* carve Phase-C out.

**Issue.** The step restated the standalone cap-3 assertion in the §Synthesis preamble (line 53-58, edit 7) so it explicitly defers per-level — step-level/Phase-2/3A keep `review-iteration.md` §Limits cap-3, the Phase-C track-level loop is uncapped per `track-code-review.md` §Review loop. But the sibling §Iteration protocol section (TOC summary line 9/100 and prose line 102) still inlines `(max 3 iterations, …)` under a pointer whose phase set is `3A,3B,3C`. Edit 7 deliberately left this, reasoning the section "defers to `review-iteration.md` §Limits by pointer, so it inherits edit 6's carve-out." That inheritance argument holds at the file level — a 3C reader following `see review-iteration.md:orchestrator:3A,3B,3C` lands in §Limits, which now carries the carve-out. The referent (`review-iteration.md` §Limits) does resolve and does carry the override, so this is not a broken reference. It parallels §Limits' own retained "Max 3" default line.

The residual is only that the inlined parenthetical, repeated thrice in a 3C-loaded section, reads as a flat cap a Phase-C reader could take at face value without following the pointer — the precise failure mode edit 7 fixed one section earlier. This is a judgment-call minor inconsistency the track file consciously scoped out, not a defect; recorded for completeness.

**Suggestion.** Optional: append "(Phase-C track-level review overrides per `track-code-review.md` §Review loop)" to the §Iteration protocol prose at line 102, or change `max 3 iterations` to `max 3 iterations for step-level / Phase-2 / 3A`, so the section is self-consistent with its §53 sibling without relying on pointer-following. Leave the TOC summary as-is if line 102 carries the qualifier.

## Evidence base
