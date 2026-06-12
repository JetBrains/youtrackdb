<!-- workflow-sha: 26f990ed824d113fdb5fcb930361e69378f0f12a -->
# Gate-verification (iter 2) — Track 2: Over-dense prose enforcement

```yaml
kind: verdict-producer
review_type: consolidated-technical-adversarial
phase: 3A
track: "Track 2: Over-dense prose enforcement (cold-read block + dsc-ai-tell regexes)"
iteration: 2
findings: 1
overall: PASS
verdicts:
  - id: A5/T1
    title: "demotable acceptance vacuous → zero-findings-any-severity calibration"
    verdict: VERIFIED
  - id: T2
    title: "X-not-Y false-positive surface understated (~8 contrastive sentences)"
    verdict: VERIFIED
  - id: T3
    title: "WC1 anchors wrong (:36/:47 vs :70/:76)"
    verdict: VERIFIED
  - id: T4
    title: "five Human-reader rules count is sibling-specific, not five→six"
    verdict: VERIFIED
  - id: A1
    title: "Track 1 wrongly claimed to flip the :54 governance grep"
    verdict: VERIFIED
  - id: A2
    title: "design-document-rules.md:284 nine→eleven + add file to scope"
    verdict: VERIFIED
  - id: A3
    title: "cold-read block needs activation pointers, not just applies-to"
    verdict: VERIFIED
  - id: A4
    title: "faux-symmetry collides with house-style.md:341; cite existing §"
    verdict: VERIFIED
  - id: A6
    title: "test step depends on regex step → one-commit ordering note"
    verdict: VERIFIED
  - id: A7
    title: "under-floor split holds (Track 1 over ceiling)"
    verdict: VERIFIED
new_findings:
  - id: T5
    severity: suggestion
    title: "Sizing-justification still says ~4 in-scope after A2 bumped to ~5"
```

## Findings

### T5 Sizing justification line is stale after the A2 scope bump

The A2 fix added `.claude/workflow/design-document-rules.md` to the in-scope roster and updated the header at line 92 to `**In-scope files** (~5)` with five files listed, but the `Sizing justification` paragraph at line 105 still reads `This track is ~4 in-scope files`. The two statements now disagree on the in-scope count (`~4` vs `~5`).

The sizing conclusion is unaffected — both `~4` and `~5` sit well under the `~12` merge-candidate floor, so the under-floor argument and the not-folded-into-Track-1 reasoning still hold. The drift is cosmetic but contradicts the in-scope header a reader sees two paragraphs earlier, so a future reader reconciling the roster against the count would hit a contradiction. Suggestion: change `~4` to `~5` at line 105 to match the header and the file list.

## Verification certificates

#### Verify A5/T1: demotable acceptance was vacuous
- **Original issue**: the "demotable / no blocker-severity false positive" acceptance was vacuous — `dsc-ai-tell` emits at most `should-fix` and `design-mechanical-checks.py` exits 1 only on blockers, so "no blocker false positive" can never fail.
- **Fix applied**: D4b Risks/Caveats now pins both regexes to `should-fix` ("the rule's demotable severity"), states `dsc-ai-tell` has **no blocker path** and that a false positive "pollutes the review with a spurious `should-fix`, it does not halt `edit-design`", and rewrites the calibration target to **zero findings of any severity** on this branch's Phase-4 `design-final.md`. Step 2 (`Severity (A5/T1)`) and the Validation bullet at line 77 carry the same.
- **Re-check**:
  - Track-file location: D4b (line 35), step 2 (line 56), Validation (line 77).
  - Current state: track says `should-fix`, "no blocker path", "zero findings of any severity"; builds no new severity mechanism.
  - Live-file spot-check: `design-mechanical-checks.py` — `main` computes `verdict = "PASS" if blockers == 0 else "NEEDS REVISION"` (line 2266) and `sys.exit(main())` (line 2282); `check_dsc_ai_tell` issues every `make_finding` at `"should-fix"` (lines 1848, 1872, 1883, 1895, 1907, 1951, 1974, 1991) — no `"blocker"` anywhere in the function. The exit-1-only-on-blockers and no-`dsc-ai-tell`-blocker-path claims are both true.
  - Criteria met: factual accuracy; acceptance is now testable.
- **Regression check**: checked D4b, step 2, Validation, and the cross-reference to D5/A9 — severity story is consistent (both ship at the rule's documented demotable `should-fix`, no new mechanism). Clean.
- **Verdict**: VERIFIED

#### Verify T2: "X, not Y" false-positive surface understated
- **Original issue**: the regex false-positive surface was understated — ~8 legitimate contrastive sentences exist in this branch's `design.md`.
- **Fix applied**: D4b Risks/Caveats names the `~8` real contrastive sentences (a `, not <lowercase>` probe, none AI-tells); step 2 constrains the regex to a "rhetorical-elevation shape … distinct from the existing `NEGATIVE_PARALLELISM_RE`"; step 3 and the Validation bullet require the ~8 contrastive constructions as negative guards.
- **Re-check**:
  - Track-file location: D4b (line 34/35), step 2(b) (line 56), step 3 (line 57), Validation (lines 73, 75).
  - Current state: track says ~8 contrastive guards, distinct-from-`NEGATIVE_PARALLELISM_RE`, rhetorical-elevation shape.
  - Live-file spot-check: `NEGATIVE_PARALLELISM_RE` exists in `design-mechanical-checks.py` (line 150, `\bit'?s not\b.*\bit'?s\b`, the "it's not X, it's Y" shape), used in `check_dsc_ai_tell` at line 1988. The track's claim that the new regex must be distinct from it is grounded.
  - Criteria met: false-positive risk is named and guarded.
- **Regression check**: checked step 3 and Validation for the guard set — both name the ~8 contrastive cases and the Overview enumeration guard; the regex is bound to its tests in one commit (A6). Clean.
- **Verdict**: VERIFIED

#### Verify T3: WC1 anchors corrected
- **Original issue**: WC1 anchors were wrong — `:36` was treated as the GAP routing, but `:36` is the rule-author target taxonomy (Procedure step 5); the audit's GAP classification is STEP 4 at `:76`; `:47` is an existing design-review sync bullet to extend, not a new row.
- **Fix applied**: Context (line 49), step 4 (line 58), Validation (line 76), and Interfaces (line 97) now cite `:70` (STEP 1 section list) + `:76` (STEP 4 GAP classification), say "extend the `:47` bullet", and explicitly note "STEP 4 at line 76, not the rule-author target taxonomy at line 36".
- **Re-check**:
  - Track-file location: Context line 49, step 4 line 58, Validation line 76, Interfaces line 97.
  - Live-file spot-check of `readability-feedback/SKILL.md`:
    - `:36` = Procedure step 5, "Draft one rule change per GAP group … general tells go under Banned vocabulary / Banned sentence patterns / …" — the rule-author target taxonomy, as the track now says. Correct.
    - `:47` = the Rule sync map design-review bullet ("`design-review.md` — a `### Human-reader cold-read additions` bullet, the `§ Tone and depth` count, and the TOC-row summary") — an existing bullet to extend. Correct.
    - `:70` = audit sub-agent STEP 1 "Note especially § Banned vocabulary, … § Structural rules … § Document-shape rules" — the section list with no `## Orientation`. Correct.
    - `:76` = audit sub-agent STEP 4 "Classify each finding as `CAUGHT by § <exact section>` or `GAP`" — the CAUGHT/GAP classification. Correct.
  - Criteria met: every cited anchor matches the live file; the GAP-vs-taxonomy distinction is right.
- **Regression check**: the in-scope entry (line 97) and step 4 (line 58) agree the file's `:54` grep is untouched (R1) and only `:47`/`:70`/`:76` are edited. No dangling anchor. Clean.
- **Verdict**: VERIFIED

#### Verify T4: five Human-reader rules count is sibling-specific
- **Original issue**: the `§ Tone and depth` "five Human-reader rules" count is specific to the sibling block, not a five→six bump.
- **Fix applied**: Context (line 46) and step 1 (line 55) now add a **new** reviewer-tone clause for the new block and leave the count at five, with the rationale that line 406's count names the sibling block's five rules specifically; Validation (line 72) and Interfaces (line 93) repeat "count stays five — T4".
- **Re-check**:
  - Track-file location: Context line 46, step 1 line 55, Validation line 72, Interfaces line 93.
  - Live-file spot-check of `design-review.md`: line 406 reads "the five Human-reader rules require evidence (see the Reviewer tone note under § Human-reader cold-read additions)" — the count is explicitly tied to the Human-reader sibling block, confirming a new block needs its own clause rather than a count bump.
  - Criteria met: the track does not bump the count; it adds a separate clause.
- **Regression check**: checked that the new-clause approach does not contradict A3's TOC-row / activation-pointer edits (different sites). Clean.
- **Verdict**: VERIFIED

#### Verify A1: Track 1 did not flip the :54 governance grep
- **Original issue**: the track wrongly claimed Track 1 "flipped" the `readability-feedback/SKILL.md:54` governance grep.
- **Fix applied**: Context (line 49) now states the `:54` grep is a rename-enumeration tool Track 1 kept four-string (R1), that Track 2 does not touch it, and the overlap note (line 103) is rewritten to say Track 1 committed **no** edit to the file ("verified: `git log` shows only the pre-branch `#1125` commit").
- **Re-check**:
  - Track-file location: Context line 49, overlap note line 103, Interfaces line 97.
  - Live-file spot-check:
    - `git log --oneline -- .claude/skills/readability-feedback/SKILL.md` returns exactly one commit: `2498508d6c Add design-doc readability rules and feedback skill (#1125)` — the pre-branch commit, no Track-1 commit. The "committed no edit" claim is true.
    - The `:54` grep is `grep -rn 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md` — exactly four strings (four-string, R1). Confirmed.
  - Criteria met: the corrected claim is factually accurate.
- **Regression check**: overlap note and sizing justification are consistent on "Track 2 is the sole editor". Clean.
- **Verdict**: VERIFIED

#### Verify A2: design-document-rules.md nine→eleven + file added to scope
- **Original issue**: `design-document-rules.md:284` enumerates the `dsc-ai-tell` patterns as "nine" by name; two new regexes make it stale, and the file was missing from in-scope.
- **Fix applied**: `.claude/workflow/design-document-rules.md` added to in-scope (line 96, the `~5` roster); step 2 doc-sync (line 56) says "bump the count to eleven and add the two new patterns"; Validation bullet (line 74) requires "eleven patterns (was nine)".
- **Re-check**:
  - Track-file location: step 2 line 56, Validation line 74, Interfaces line 96.
  - Live-file spot-check: `design-document-rules.md` `dsc-ai-tell` table row reads "Detects nine `house-style.md` patterns whose textual fingerprint is reliable enough to flag mechanically" — currently "nine", as the track claims. (The `check_dsc_ai_tell` docstring at line 1771 also says "Nine patterns fire", a second sync site the implementer should keep in view, though the track's stated edit target is the rules-file row.)
  - Criteria met: the "nine" claim is accurate; the file is now in scope.
- **Regression check**: the doc-sync edit aligns with the regex additions (two new patterns) and the eleven count. Clean. (Note for the implementer, not a finding: the script docstring at `:1771` independently says "Nine patterns fire" and lists nine — keeping it in sync to eleven is in the natural blast radius of step 2, even though the track names only the rules-file row.)
- **Verdict**: VERIFIED

#### Verify A3: cold-read block needs activation pointers
- **Original issue**: the new cold-read block needs activation pointers at each cold-read invocation site (like the sibling block at lines ~126/135/148), not just an applies-to line, or it never runs.
- **Fix applied**: D4 Risks/Caveats and Context (line 46) explain the sibling needs two things (applies-to line + activation pointers); step 1 (line 55) names "an activation pointer at every cold-read invocation site … (near lines 126 / 135 / 148 for the design kinds) plus the `target=tracks` invocation site"; Validation (line 72) and Interfaces (line 93) repeat the activation-pointer requirement for both targets.
- **Re-check**:
  - Track-file location: D4 line 28, Context line 46, step 1 line 55, Validation line 72, Interfaces line 93.
  - Live-file spot-check of `design-review.md`: the sibling `### Human-reader cold-read additions` block is at line 165, and it is pointed-to from the cold-read invocation bodies at lines 126 ("the Human-reader cold-read additions (§ below)."), 135 ("Human-reader cold-read additions (§ below)."), and 148 ("Human-reader cold-read additions (§ below)**, verify:"). The `target=tracks` path is a distinct invocation site (line 185, "applies only when `target=tracks`"). The track's claim that activation pointers exist at ~126/135/148 and are needed for the new block at both targets is accurate.
  - Criteria met: the block is wired to run, not merely declared.
- **Regression check**: the activation-pointer edit set is consistent across D4 / Context / step 1 / Validation / Interfaces. Clean.
- **Verdict**: VERIFIED

#### Verify A4: "faux-symmetry" renamed; cite existing house-style §
- **Original issue**: "faux-symmetry" collides with `house-style.md:341`'s structural ban, and the regexes had no existing house-style section to cite.
- **Fix applied**: D4b (line 34) renames the second regex to "negative-parallelism variant / rhetorical-elevation variant", says explicitly "**not** to be labelled 'faux-symmetry', which `house-style.md:341` already uses for a different structural ban (A4)", and cites `§ Banned sentence patterns` (for "X, not Y") + `§ Banned analysis patterns` (for the inflated-abstraction label). Step 2 (line 56) and the Purpose/intro (line 9) carry the same.
- **Re-check**:
  - Track-file location: Purpose line 9, D4b line 34, step 2 line 56.
  - Live-file spot-check of `house-style.md`: line 341 = "**No faux-symmetry.** Do not invent a third bullet or a fourth section just to balance the structure. If there are two real points, write two." — a structural ban under `## Structural rules`, distinct from a sentence-pattern AI-tell, exactly as the track says. `## Banned sentence patterns` exists at line 116; `## Banned analysis patterns` exists at line 129. Both cited sections are real.
  - Criteria met: no naming collision; both regexes cite an existing section.
- **Regression check**: the citation split (sentence-pattern for "X, not Y", analysis-pattern for inflated label) is consistent with the "no new rule text" out-of-scope note (line 99). Clean.
- **Verdict**: VERIFIED

#### Verify A6: test step bound to regex step in one commit
- **Original issue**: the test step depends on the regex step.
- **Fix applied**: the Ordering note (line 60) binds steps 2+3 into one commit: "the regexes (step 2) and their tests (step 3) are one coupled unit — same commit, since the tests gate the regexes (A6)".
- **Re-check**:
  - Track-file location: Ordering note line 60.
  - Current state: explicit one-commit coupling of steps 2 and 3; steps 1 (cold-read) and 4 (WC1) are stated independent.
  - Criteria met: ordering hazard resolved.
- **Regression check**: the independence statement for steps 1/4 matches the Interfaces in-scope split (cold-read in `design-review.md`, WC1 in SKILL.md — separate files). Clean.
- **Verdict**: VERIFIED

#### Verify A7 (confirmation only): under-floor split holds
- **Original issue**: confirmation that the under-floor split from Track 1 holds — Track 1 is ~54 files, over the ceiling.
- **Fix applied**: none required; confirmation only.
- **Re-check**:
  - Track-file location: Sizing justification line 105.
  - Current state: the justification reads correctly — the track is under the `~12` floor, is not folded into Track 1 because Track 1 is already over the `~20-25` ceiling (the atomic subset flip, ~54 sites per the plan's D1), and is the one independently-mergeable seam. The argument is sound.
  - Criteria met: the split reasoning is intact. (The stale `~4`/`~5` numeral is captured separately as new finding T5; it does not break the argument.)
- **Regression check**: cross-checked against the plan's `### Constraints` D1 (line 23 of the plan) — "~54 sites … move four→five" — confirming Track 1's over-ceiling footprint. Clean.
- **Verdict**: VERIFIED

#### Regression check (internal consistency of the fixed track)
- **Context ↔ Plan of Work ↔ Decision Log ↔ Validation ↔ Interfaces**: the five surfaces tell one story — two regexes (step 2/D4b) at `should-fix` citing existing house-style sections, one cold-read block (step 1/D4) wired at both targets, the WC1 propagation (step 4) at `:70`/`:76` only, the `:54` grep untouched (R1), and `design-document-rules.md` nine→eleven (A2). No contradiction found between sections.
- **Dangling references**: every cited live-file anchor (`design-review.md` 165/126/135/148/406/TOC-22; SKILL.md 36/47/54/70/76; `design-mechanical-checks.py` `NEGATIVE_PARALLELISM_RE`; `design-document-rules.md:284` "nine"; `house-style.md:341` + sections 116/129) resolves to the live file. No dangling reference.
- **In-scope count vs file list**: the in-scope header (line 92) says `~5` and lists exactly five files — they agree. The **sizing justification** (line 105) still says `~4`, which disagrees with the `~5` header — captured as T5 (suggestion). This is the only internal inconsistency found.
- **Verdict**: internally consistent except for the T5 numeral drift, which does not alter any conclusion.

## Summary

PASS. All ten findings under re-check (A5/T1, T2, T3, T4, A1, A2, A3, A4, A6, A7) are VERIFIED: every fix landed in the current track file and every cited live-file fact is accurate. One new low-severity finding (T5, suggestion): the sizing justification at line 105 still says "~4 in-scope files" after the A2 fix bumped the roster to ~5; the count drift is cosmetic and does not change the under-floor sizing conclusion. No blockers, no should-fix, no regressions. The gate passes.
