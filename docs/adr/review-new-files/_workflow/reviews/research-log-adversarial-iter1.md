<!-- MANIFEST
findings: 4   severity: {blocker: 1, should-fix: 1, suggestion: 2}
index:
  - {id: A1, sev: blocker,    loc: "track-code-review.md:269-303 / step-implementation.md:484-517", anchor: "### A1 ", cert: C1, basis: "D1 fix scope (bash else + context block) leaves the preamble and post-loop prose that also encodes the buggy 'only when live counterpart exists' logic, producing prose/code contradiction"}
  - {id: A2, sev: should-fix, loc: "track-code-review.md:461-463 / step-implementation.md:617-619", anchor: "### A2 ", cert: C2, basis: "NEW marker makes the delta file non-empty; the existing blanket 'when non-empty, scope to the delta / rest is out of scope' sentence must be overridden not appended, or a NEW file is still marked out of scope"}
  - {id: A3, sev: suggestion, loc: "conventions.md:1344-1347", anchor: "### A3 ", cert: C3, basis: "D3 opt-out disables the step-8 staged-delta prep on this very branch, so the fix ships with no in-workflow self-validation; acceptable for prose but warrants a manual coherence trace"}
  - {id: A4, sev: suggestion, loc: "research-log.md:54-63", anchor: "### A4 ", cert: C4, basis: "D2 marker names the staged path only; survives (staged path is the diff locator) but the reviewer must prefix-strip to reach the live rule the new file changes"}
evidence_base: {section: "## Evidence base", certs: 4, matches: 4}
cert_index:
  - {id: C1, verdict: WRONG, anchor: "#### C1 "}
  - {id: C2, verdict: WEAK, anchor: "#### C2 "}
  - {id: C3, verdict: HOLDS, anchor: "#### C3 "}
  - {id: C4, verdict: HOLDS, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [blocker]
**Certificate**: C1 (Challenge: Decision D1 — fix scope is "loop else-branch + context block")
**Target**: Decision D1 (fix both defect sites) — its stated fix *scope*, not its "fix both files" conclusion
**Challenge**: D1 names the fix as "the missing else-branch plus the '…out of scope' instruction; both are duplicated near-verbatim across the two files." That is two artifacts per file: the bash loop and the `## Review-target delta …` context block. It misses a **third** location of the same defect logic in each file — the **preamble prose** and the **post-loop narration** — which will go stale and contradict the new behavior. In `track-code-review.md` the preamble (lines 269-272) reads "Enumerate the new-file adds … and **when the live file exists** write `diff <live> <staged>`" and "**only the delta against the live counterpart is worth review**"; the post-loop narration (lines 293-303) reads "The trigger is precise: it fires only on a new-file add … **that has a live counterpart**" and "The delta file is empty when no freshly-created staged copy is in range." `step-implementation.md` carries the byte-parallel sentences at 484-487 and 508-517. After the fix, the bash `else` emits a review-in-full marker for a no-live-counterpart file, so "only the delta … is worth review" is false for that file, and "fires only … that has a live counterpart" mis-describes the new else branch. Rule coherence / non-contradiction (the #1 workflow-machinery prose criterion) is violated: prose narration contradicts the code it narrates and the reworded context block.
**Evidence**: `track-code-review.md:269-272` (preamble "when the live file exists … only the delta … is worth review"), `track-code-review.md:293-303` (post-loop "fires only on a new-file add … that has a live counterpart"; "empty when no freshly-created staged copy is in range"); byte-parallel at `step-implementation.md:484-487` and `508-517`. `grep -rn 'if \[ -f "\$live" \]'` and the `verbatim-copied` / `no live counterpart` sweeps confirm the defect logic lives only in these two files, but in **three** prose+code locations per file, not the two D1 enumerates.
**Proposed fix**: Widen D1's fix scope from "loop + context block" to "loop + **preamble sentence** + **post-loop narration** + context block," in both files. Concretely: the preamble must stop asserting the delta is written only "when the live file exists" / "only the delta … is worth review" (it must acknowledge the no-live-counterpart review-in-full case); the post-loop "fires only on a new-file add … that has a live counterpart" and "empty when no freshly-created staged copy is in range" must be reworded so they do not deny the else branch's output. Record the widened scope in the Decision Log so the authoring step and the consistency review know all three locations per file are in play.

### A2 [should-fix]
**Certificate**: C2 (Challenge: Decision D2 — marker written into the same delta file; interaction with the "when non-empty" blanket)
**Target**: Decision D2 (NEW-file marker emits the staged path) — its consequence on the context-block's non-empty/empty branch
**Challenge**: The proposed marker is appended to the **same** per-track/per-step delta temp file the `diff <live> <staged>` blocks go into (research-log lines 19-21). The current context block says: "When that file is non-empty, scope your findings to the delta: **the rest of each whole-file add is verbatim-copied … out of scope**. When it is empty … review the diff as usual" (`track-code-review.md:461-465`, `step-implementation.md:617-621`). Once a NEW marker is present the file is non-empty, so the reviewer enters the "non-empty → scope to the delta, rest out of scope" branch — the exact branch that must NOT apply to a NEW-marked file, which has no delta body and must be reviewed in full. The issue's part-2 sentence ("a file under a NEW marker has no already-reviewed live baseline and must be reviewed in full") is the intended cure, but D2's rationale does not lock that the added sentence must **override** the pre-existing blanket "the rest … is out of scope," not merely sit beside it. If it is appended as an additional note while the blanket sentence still reads as an unconditional "the rest is out of scope," a reader hits two contradictory instructions in the same block for a mixed delta file (some entries scope-to-delta, one entry review-in-full). This is a prompt-design-soundness / instruction-completeness gap (every conditional needs its complement, unambiguously).
**Evidence**: `track-code-review.md:461-465` and `step-implementation.md:617-621` (the "when non-empty … the rest … out of scope / when empty … as usual" two-branch instruction, currently written as one blanket over the whole non-empty file); research-log lines 19-24 (marker goes into the same delta file; part-2 sentence added but override-vs-append not specified).
**Proposed fix**: Strengthen D2 (or add a D-entry) to state that the context-block rewrite must make the delta/NEW distinction **per-entry and mutually exclusive** — the "scope to the delta, rest out of scope" clause applies only to `=== delta: … ===` entries, and the "review in full" clause applies only to `=== NEW staged file … ===` entries — rather than layering a new sentence under an unchanged blanket. This closes the mixed-file ambiguity the marker introduces.

### A3 [suggestion]
**Certificate**: C3 (Assumption test: the fix is exercised by its own review machinery)
**Target**: Decision D3 (§1.7(k) prose-rule opt-out) — its self-validation consequence
**Challenge**: D3 correctly notes the opt-out removes any self-referential hazard ("the bug it fixes cannot even trigger on its own review"). The flip side, which D3 does not surface, is that the opt-out **disables the step-8 staged-delta prep on this very branch** (`conventions.md:1344-1347` — the opt-out "disables … the Phase C Startup staged-delta prep in track-code-review.md step 8"). So the edited loop and reworded context blocks never run during this branch's own Phase B/C reviews. The change ships with zero in-workflow execution of the modified machinery; its only validation is prose inspection and a manual trace. For a two-file prose fix this is acceptable and is a direct consequence of the (correct) opt-out choice — hence suggestion, not blocker — but the acceptance criteria (research-log lines 26-31) are prose-only assertions with no executed check.
**Evidence**: `conventions.md:1344-1347` (opt-out disables the step-8 staged-delta prep); research-log lines 65-76 (D3) and 99-110 (baseline: fix edits live files, no self-trigger).
**Proposed fix**: Add a line to D3 (or the baseline section) committing to a manual dry-run trace of the edited loop against a synthetic no-live-counterpart staged path plus a read-through of the reworded three prose locations from A1, since the branch's own review machinery will not exercise them. No behavior change; strengthens the validation story the opt-out otherwise leaves implicit.

### A4 [suggestion]
**Certificate**: C4 (Challenge: Decision D2 — emit staged path vs derived live path)
**Target**: Decision D2 (marker emits the staged path)
**Challenge**: D2 emits the staged path in the marker on the grounds that the diff shows whole-file adds under staged paths, so the staged path is the diff locator. This holds: the "Diff" section (`track-code-review.md:467-474`) confirms the cumulative diff renders new staged copies as whole-file adds under their staged paths, so the staged path is exactly where the reviewer finds the file. The minor residue: the reviewer must mentally prefix-strip the staged path to know **which live rule** the new file will become once promoted, and the NEW marker (unlike the `=== delta: <live> vs <staged> ===` line, which shows both) names only the staged path. The decision survives — the derived live path is a deterministic prefix-strip and adds no locating power in the diff — so this is a suggestion, not a gate.
**Evidence**: `track-code-review.md:467-474` (whole-file adds shown under staged paths); research-log lines 54-63 (D2, staged-path rationale); the `=== delta: %s vs %s ===` format at `track-code-review.md:285` shows both paths, whereas the proposed NEW marker shows one.
**Proposed fix**: Optional — if authoring finds it cheap, the NEW marker could mirror the delta line's two-path shape (`=== NEW staged file (no live counterpart): <staged> [→ <live-on-promote>] ===`). If not, D2's single-path choice stands; record that the reviewer prefix-strips to recover the live target, matching the derivation the loop already does.

## Evidence base

#### C1 [WRONG] Challenge: Decision D1 — fix scope is "loop else-branch + context block"
- **Chosen approach**: D1 fixes the defect at both files by editing two artifacts per file — the bash loop (add `else` marker) and the `## Review-target delta …` context block (add the review-in-full sentence).
- **Best rejected alternative** (not that D1 should be dropped, but that its *scope* is under-drawn): treat the defect as living in **three** locations per file — the preamble prose that introduces the loop, the loop itself, the post-loop narration, and the context block — and reword all prose that asserts "only when a live counterpart exists" / "only the delta is worth review" / "fires only … with a live counterpart" / "empty when no freshly-created staged copy is in range."
- **Counterargument trace**:
  1. After the D1-as-scoped fix, the bash `else` emits `=== NEW staged file … ===` for a no-live-counterpart staged file, and the context block tells reviewers to review it in full.
  2. But the preamble prose still reads (track 269-272 / step 484-487): "Enumerate the new-file adds … and **when the live file exists** write `diff` …" and "**only the delta against the live counterpart is worth review**." The post-loop narration still reads (track 293-303 / step 508-517): "it fires only on a new-file add … **that has a live counterpart**" and "The delta file is empty when no freshly-created staged copy is in range."
  3. Outcome: a reader of the section hits prose that flatly denies the else branch — "only the delta is worth review" is false for a whole new file, and "fires only … with a live counterpart" / "empty when no freshly-created staged copy in range" contradict a non-empty delta file that carries a NEW marker. Rule coherence / non-contradiction fails; a consistency review would flag the section as self-contradictory.
- **Codebase evidence**: `track-code-review.md:269-272`, `:293-303`; `step-implementation.md:484-487`, `:508-517`. `grep -rln 'verbatim-copied'` and `grep -rn 'no live counterpart'` confirm the defect logic is confined to these two files and is duplicated near-verbatim, but per file it spans preamble + loop + post-loop + context block — four locations, of which D1 names two.
- **Survival test**: NO — D1's "fix both files" conclusion survives, but its fix *scope* does not. The scope must widen to the preamble and post-loop prose, or the section ships internally contradictory.

#### C2 [WEAK] Challenge: Decision D2 — marker in the shared delta file vs the "when non-empty" blanket
- **Chosen approach**: D2 appends the NEW marker to the same per-track/per-step delta temp file that holds the `diff <live> <staged>` blocks, and part-2 adds a distinguishing sentence to the context block.
- **Best rejected alternative**: rewrite the context block's non-empty branch as **per-entry** rules (delta entries → scope-to-delta; NEW entries → review-in-full) rather than appending a note beneath the unchanged blanket "the rest … is out of scope."
- **Counterargument trace**:
  1. Start state: a mixed delta file with one `=== delta: … ===` block and one `=== NEW staged file … ===` marker.
  2. Reviewer reads the context block: "When that file is non-empty, scope your findings to the delta: the rest of each whole-file add is verbatim-copied … out of scope" (`track-code-review.md:461-463`).
  3. Taken literally, the blanket puts "the rest of each whole-file add … out of scope" over the NEW-marked file too — the very outcome the fix exists to prevent — unless the added sentence explicitly overrides it per-entry.
  4. Outcome: if the fix appends rather than restructures, the block holds two contradictory instructions for the NEW-marked file. Prompt-design soundness / instruction completeness gap.
- **Codebase evidence**: `track-code-review.md:461-465`, `step-implementation.md:617-621` (blanket non-empty/empty branch); research-log 19-24 (marker in same file, override-vs-append unspecified).
- **Survival test**: WEAK — D2's staged-path/marker choice is sound, but its rationale must be extended to require a per-entry restructure of the non-empty branch, not an appended note.

#### C3 [HOLDS] Assumption test: the fix is exercised by its own review machinery
- **Claim**: The fix's own Phase B/C reviews validate the edited loop and context blocks.
- **Stress scenario**: This branch runs under the §1.7(k) opt-out (D3), which `conventions.md:1344-1347` says "disables … the Phase C Startup staged-delta prep in track-code-review.md step 8."
- **Code evidence**: `conventions.md:1344-1347` — opt-out disables the step-8 staged-delta prep; therefore the edited loop and reworded blocks never execute on this branch's reviews.
- **Verdict**: HOLDS (as a benign consequence, not a defect of D3). The claim that the machinery self-validates is false, but D3 is correct to opt out; the residue is only that validation is prose-inspection + manual trace, which is normal for a workflow-prose change and is why this is a suggestion.

#### C4 [HOLDS] Challenge: Decision D2 — emit staged path vs derived live path
- **Chosen approach**: NEW marker names the staged path.
- **Best rejected alternative**: emit the derived live path, or both.
- **Counterargument trace**:
  1. In the cumulative diff, a fresh staged copy appears as a whole-file add under its staged path (`track-code-review.md:467-474`), so the staged path is the diff locator the reviewer needs — D2's rationale holds.
  2. The derived live path is a deterministic prefix-strip (the loop already computes it via `sed -E 's#^docs/adr/[^/]+/_workflow/staged-workflow/##'`), so emitting it adds no locating power in the diff.
  3. Outcome: no material difference; the reviewer prefix-strips to recover the live target the file will become on promote.
- **Codebase evidence**: `track-code-review.md:281-282` (the prefix-strip already in the loop), `:285` (the delta line shows both paths), `:467-474` (diff shows staged-path whole-file adds).
- **Survival test**: YES — D2 survives; the single-path marker is adequate. Optional cosmetic parity with the two-path delta line only.
