<!-- manifest
role: reviewer-technical
phase: 3A
track: "Track 1: Cover genuinely-new staged files in Phase B/C review scope"
iteration: 1
verdict: PASS
findings: 2
evidence_base: 9 certificates (5 premise, 2 edge-case, 2 integration); all four defect locations confirmed present in both live files at the cited ranges; the loop and context block are byte-identical between the two files after indentation-normalization except for the temp-file path (and, in the loop only, the git-diff range); conventions.md §1.7(k) line 1346 holds a name-only pointer to step 8 with no third copy; the loop already enumerates the new-file add via --diff-filter=A and falls through the guard with no else; ordinary (non-workflow-modifying) plans stay inert (empty delta file).
index:
  - id: T1
    sev: suggestion
    anchor: "### T1 "
    loc: "track-1.md D2 Consistency / Plan of Work (b) / Inv 3; step-implementation.md loop ~490-505 vs track-code-review.md loop ~275-290"
    cert: "Premise: loop near-verbatim copy"
    basis: "the two loops differ in the git-diff range ({base_commit}..HEAD vs {commit}~1..{commit}) in addition to the temp-file path and indentation; the track characterizes the divergence as temp-path + indentation only"
  - id: T2
    sev: suggestion
    anchor: "### T2 "
    loc: "track-1.md Inv 5 / D1 Scope confirmed closed; track-code-review.md:1589, step-implementation.md:795"
    cert: "Integration: no third copy of loop/context block"
    basis: "two additional occurrences of the delta temp-path token (rm -f cleanup lines) exist that the scope-closed grep summary does not mention; they are inert to the fix but the Inv 5 grep as worded would surface them"
-->

# Technical review — Track 1 (iteration 1)

Verdict: **PASS**. Two `suggestion`-level accuracy notes; no blocker, no should-fix. Every premise the track rests on verified against the live files: all four defect locations exist per file at (or within a few lines of) the cited ranges, the loop and context block are byte-identical copies between the two files modulo the temp-file path, `conventions.md §1.7(k)` holds only a name pointer with no third copy, the loop already enumerates the new-file add so an else branch is the complete loop-side fix, and the ordinary-plan path stays inert. The fix approach (else-branch NEW marker + per-entry marker-keyed context-block rewrite) is coherent and breaks no other consumer.

## Findings

### T1 [suggestion]
**Certificate**: Premise — the two loops are near-verbatim copies differing only in the temp-file path and indentation
**Location**: `track-1.md` — D2 `Consistency`, Plan of Work (b) ("identical except for the temp-file path … and two extra levels of indentation"), and Inv 3; against `track-code-review.md` loop lines ~275-290 and `step-implementation.md` loop lines ~490-505
**Issue**: The two loop bodies differ in **two** dimensions the fix does not touch, not one. Beyond the temp-file path (`track-{N}-delta` vs `step-{N}-{M}-delta`) and indentation, they also differ in the `git diff` range: Phase C uses the cumulative range `{base_commit}..HEAD` (track-code-review.md:276) while Phase B uses the per-step range `{commit}~1..{commit}` (step-implementation.md:491). The indentation-normalized diff confirms three changed lines: the two temp-path lines and the git-diff-range line. The track's Plan of Work (b) and D2 `Consistency` say the copy is "identical except for the temp-file path … and indentation" — the git-diff range is an additional pre-existing divergence. This is a characterization imprecision, not a fix hazard: the else branch the fix adds is byte-identical in both files (it neither reads nor changes the git-diff range), and the git-diff-range line sits outside the edited region. Inv 3's post-edit consistency check ("diverge only in the temp-file path and indentation") would technically fail against the live text as written, since the range line already diverges before any edit.
**Proposed fix**: Amend Plan of Work (b), D2 `Consistency`, and Inv 3 to name the divergence as "the temp-file path, the git-diff range (`{base_commit}..HEAD` in Phase C vs `{commit}~1..{commit}` in Phase B), and indentation." Alternatively, scope Inv 3's consistency check to the **added else branch** ("the else branch is byte-identical modulo temp-path and indentation") rather than the whole loop region, so the pre-existing range divergence does not read as a violation. No change to the fix itself is implied.

### T2 [suggestion]
**Certificate**: Integration — `§1.7(k)` holds only a pointer; no third copy of the loop or context block
**Location**: `track-1.md` — Inv 5 (`grep .claude/** for the delta temp-path and the "freshly-created staged" prose`) and D1 `Scope confirmed closed`; against `track-code-review.md:1589` and `step-implementation.md:795`
**Issue**: The scope-closed claim is correct in substance — the loop and the context block live in exactly the two named files, and `conventions.md §1.7(k)` (line 1346) references step 8 by name only with no embedded copy. But the delta temp-path token has **two** further occurrences the track's scope summary does not mention: `track-code-review.md:1589` and `step-implementation.md:795`, both `rm -f …-delta-$PPID.txt` cleanup lines in the temp-file teardown steps. They are inert to this fix (they delete whatever the delta file holds regardless of its content, so a NEW-marker entry needs no teardown change), but Inv 5's check as worded ("grep `.claude/**` for the delta temp-path") returns four files' worth of hits, not two, and a Phase C reviewer running that exact grep will surface the two cleanup lines and have to re-derive that they are not third copies. The "freshly-created staged" prose half of the grep does resolve cleanly to the two named files.
**Proposed fix**: Tighten Inv 5's check to grep for the loop marker (`'delta: %s vs %s'`) or the context-block heading (`'Review-target delta for freshly-created staged copies'`) rather than the bare temp-path token, which also matches the `rm -f` teardown lines. Optionally add a one-clause note to D1 `Scope confirmed closed` that the temp-path also appears in per-file teardown `rm -f` lines that the fix leaves untouched. No fix-code change implied.

## Evidence base

#### Premise: all four defect locations exist in track-code-review.md at the cited ranges
- **Track claim**: "preamble prose ends '…and when the live file exists write `diff <live> <staged>`' (~271); the bash loop guards the diff with `if [ -f "$live" ]` and has no else branch (~283-289); the post-loop narration says the trigger 'fires only on a new-file add … that has a live counterpart' (~293); the … context block (~454-465)."
- **Search performed**: `Read` of `track-code-review.md` lines 255-310 and 445-474 (workflow prose; path/anchor lens, not findClass).
- **Code location**: preamble `.claude/workflow/track-code-review.md:270-272` ("when the live file exists write `diff <live> <staged>` to a per-track delta temp file:"); loop guard 283-289 (`if [ -f "$live" ]; then … fi`, no else); post-loop 293-295 ("it fires only on a new-file add (`--diff-filter=A`) under the anchored staged prefix that has a live counterpart"); context block heading at 454, out-of-scope sentence at 461-463.
- **Actual behavior**: All four passages present. Line numbers are within a few lines of the cited ~ranges (preamble ends 270-272 not exactly 271; loop 283-289 exact; post-loop 293-295; context block 454-465 exact).
- **Verdict**: CONFIRMED
- **Detail**: Cited ranges are approximate (`~`) and land on the correct passages. No discrepancy.

#### Premise: all four defect locations exist in step-implementation.md at the cited ranges
- **Track claim**: "The same preamble (~486), the same loop else-gap (~498-504), the same post-loop narration (~508), and the byte-identical context block (~610-621)."
- **Search performed**: `Read` of `step-implementation.md` lines 470-524 and 600-629.
- **Code location**: preamble `.claude/workflow/step-implementation.md:486-487`; loop guard 498-504 (`if [ -f "$live" ]; then … fi`, no else); post-loop 508-510; context block heading 610, out-of-scope sentence 617-619.
- **Actual behavior**: All four passages present at the cited ranges.
- **Verdict**: CONFIRMED

#### Premise: the two loops are near-verbatim copies differing only in the temp-file path and indentation
- **Track claim**: Plan of Work (b) — "The `step-implementation.md` copy is identical except for the temp-file path (`step-{N}-{M}-delta`) and two extra levels of indentation."
- **Search performed**: `diff` of the two loop regions (track 275-290 vs step 490-505) after `sed 's/^ *//'` indentation normalization.
- **Code location**: `track-code-review.md:275-290` vs `step-implementation.md:490-505`.
- **Actual behavior**: Normalized diff shows three changed lines — the two temp-path lines AND the git-diff range line (`{base_commit}..HEAD` vs `{commit}~1..{commit}`). The `if [ -f "$live" ]` guard body and the `=== delta: … ===` printf are identical.
- **Verdict**: PARTIAL
- **Detail**: The temp-path + indentation characterization omits the git-diff-range divergence. Fix-relevant portion (the else branch to be added) is identical in both. Produces T1.

#### Premise: the two context blocks are byte-identical except for the temp-file path
- **Track claim**: Plan of Work / Context — "the byte-identical context block (~610-621), differing only in the temp-file path … and indentation."
- **Search performed**: `diff` of track 454-465 vs step 610-621 after indentation normalization.
- **Code location**: `track-code-review.md:454-465` vs `step-implementation.md:610-621`.
- **Actual behavior**: Normalized diff shows exactly one changed line — the temp-file path (`/tmp/claude-code-track-{N}-delta-{PPID}.txt` vs `/tmp/claude-code-step-{N}-{M}-delta-{PPID}.txt`). Everything else identical, including the "verbatim-copied … out of scope" sentence.
- **Verdict**: CONFIRMED

#### Premise: conventions.md §1.7(k) holds only a pointer to step 8, no third copy of the loop or context block
- **Track claim**: D1 `Scope confirmed closed` / Inv 5 — "`conventions.md §1.7(k)` only references the concept (it points at 'the Phase C Startup staged-delta prep in track-code-review.md step 8'); it holds no third copy of the loop."
- **Search performed**: `grep -rn 'staged-delta prep' .claude/`; `Read` of `conventions.md` §(k) 1336-1369; cross-`.claude/**` grep for `'delta: %s vs %s'`, `'freshly-created staged'`, `'verbatim-copied'`, `'already-reviewed content'`.
- **Code location**: `.claude/workflow/conventions.md:1346` ("the Phase C Startup staged-delta prep in track-code-review.md:orchestrator,reviewer-dim-track:3C step 8").
- **Actual behavior**: §1.7(k) at 1346 names step 8 in a parenthetical list of things the opt-out disables. No `diff <live> <staged>` loop, no `=== delta: … ===` marker, no context-block prose. The loop marker and context-block heading grep to exactly the two named files.
- **Verdict**: CONFIRMED
- **Detail**: The pointer references step 8 by name, not by quoting its content, so rewriting the step-8 context block does not stale the pointer. The pointer would only need attention if the fix renamed or removed step 8 (it does neither).

#### Edge case: a genuinely-new staged .claude/** file flows through the loop
- **Trigger**: A staged add `docs/adr/<dir>/_workflow/staged-workflow/.claude/agents/foo.md` whose derived live path `.claude/agents/foo.md` does not exist on `develop`.
- **Code path trace**:
  1. Entry: `git diff {range} --diff-filter=A --name-only -- 'docs/adr/*/_workflow/staged-workflow/.claude/*'` @ track-code-review.md:276-277 — enumerates ALL adds under the staged prefix, so the new file IS listed (a whole-file add is an add).
  2. `live=$(… sed -E 's#^docs/adr/[^/]+/_workflow/staged-workflow/##')` @ 281-282 — derives `.claude/agents/foo.md`.
  3. `if [ -f "$live" ]` @ 283 — false (no live counterpart on develop).
  4. No else branch — the file produces no delta entry and drops out silently.
- **Outcome**: New file absent from the delta file; the context block then marks the whole-file add "out of scope"; the file ships unreviewed. This is exactly the defect the track fixes; the enumeration already covers the file, so an else branch is the complete loop-side fix (no `--diff-filter` change).
- **Track coverage**: yes — track Context and Orientation ("The loop already enumerates the new file … so the else branch is the complete loop-side fix"), Plan of Work (b), and Inv 1. CONFIRMED against the live loop.

#### Edge case: ordinary (non-workflow-modifying) plan stays inert
- **Trigger**: A plan with no `§1.7(b)` staging marker and no staged adds in range.
- **Code path trace**:
  1. `: > /tmp/…-delta-$PPID.txt` @ 275 — truncates the delta file to empty.
  2. `git diff … --diff-filter=A … -- 'docs/adr/*/_workflow/staged-workflow/.claude/*'` @ 276-277 — returns zero rows (no staged adds).
  3. `while … read -r staged` @ 278 — iterates zero times; no delta entry, no NEW marker.
  4. Context block "When it is empty (no freshly-created staged copy in range, or an ordinary plan), review the diff as usual." @ 463-465 — stays inert.
- **Outcome**: Empty delta file, context block inert — identical to pre-fix behavior. The else branch is only reachable when the loop iterates, which requires a staged add, which requires staging mode. So the fix cannot perturb the ordinary path.
- **Track coverage**: yes — Plan of Work `Consistency check`, Inv 4, Validation `Ordinary plans unaffected`. CONFIRMED.

#### Integration: no other consumer depends on the context block's "out of scope" wording that the rewrite would break
- **Plan claim**: D1 — the context block is a rewrite from a blanket "out of scope" sentence to a per-entry marker-keyed distinction; the fix's step-8 wording should stay consistent with how §1.7(k) names step 8.
- **Actual entry point**: The "verbatim-copied … out of scope" sentence and the "Review-target delta for freshly-created staged copies" heading exist only in `track-code-review.md:454-465` and `step-implementation.md:610-621` (grep for `'verbatim-copied'`, `'already-reviewed content'`, and the heading).
- **Caller analysis**: No `.claude/**` file cites or quotes the context-block text. The only external reference to the region is `conventions.md §1.7(k):1346`, which names "the Phase C Startup staged-delta prep in track-code-review.md step 8" — a name pointer, not a content quote — and so is unaffected by rewriting the block's prose.
- **Breaking change risk**: none — the rewrite is self-contained to the two blocks; no dependent prompt or agent reads the specific "out of scope" phrasing.
- **Verdict**: MATCHES

#### Integration: the delta temp-path token has cleanup-only references outside the two setup regions
- **Plan claim**: Inv 5 — "grep `.claude/**` for the delta temp-path and the 'freshly-created staged' prose (D1 scope-closed)" should confirm no third copy.
- **Actual entry point**: `grep -rn '\-delta\-' .claude/` returns eight lines across the two files: the setup regions (loop + context block) PLUS two `rm -f …-delta-$PPID.txt` teardown lines at `track-code-review.md:1589` (Track Completion temp-file cleanup) and `step-implementation.md:795` (per-step temp-file cleanup).
- **Caller analysis**: The two teardown lines delete the delta file wholesale; they neither read nor re-emit its content, so a NEW-marker entry needs no teardown change. They are inert to the fix.
- **Breaking change risk**: none to the fix — but Inv 5's grep-for-temp-path check surfaces four files' worth of hits (two of them the harmless teardown lines), so a reviewer running that exact grep must re-derive that the cleanup lines are not third copies.
- **Verdict**: MATCHES (with the T2 tightening note)
