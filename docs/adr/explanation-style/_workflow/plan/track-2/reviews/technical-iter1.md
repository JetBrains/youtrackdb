<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 2, suggestion: 2}
index:
  - {id: T1, sev: should-fix, loc: ".claude/scripts/design-mechanical-checks.py:1762", anchor: "### T1 ", cert: "Premise: dsc-ai-tell demotable severity", basis: "No blocker path or demote mechanism in check_dsc_ai_tell; every finding hardcoded should-fix, so the exit-1 hazard D4b/D5 cite is unreachable and there is nothing to demote"}
  - {id: T2, sev: should-fix, loc: ".claude/scripts/design-mechanical-checks.py:150", anchor: "### T2 ", cert: "Edge case: X-not-Y false positives on contrastive prose", basis: "grep ', not [a-z]' on this branch's own design.md hit 8 legitimate contrastive constructions; the X,not-Y regex must be far narrower than the existing NEGATIVE_PARALLELISM_RE shape it extends"}
  - {id: T3, sev: suggestion, loc: ".claude/skills/readability-feedback/SKILL.md:36", anchor: "### T3 ", cert: "Premise: readability-feedback :36 GAP-routing anchor", basis: ":36 is Procedure step 5 (rule-author target taxonomy), not the audit sub-agent prompt; the audit prompt's STEP 4 is the natural Orientation-routing site"}
  - {id: T4, sev: suggestion, loc: ".claude/workflow/prompts/design-review.md:406", anchor: "### T4 ", cert: "Premise: Tone-and-depth five-Human-reader-rules count", basis: "The count names the 5 bullets inside the sibling Human-reader block; the new Prose AI-tell block is a separate sibling, so 'sync the count' is mechanically ambiguous"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 5}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: PARTIAL,   anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: CONFIRMED, anchor: "#### P5 "}
  - {id: P6, verdict: PARTIAL,   anchor: "#### P6 "}
  - {id: E1, verdict: "X-not-Y false-positive surface large", anchor: "#### E1 "}
  - {id: I1, verdict: MATCHES,   anchor: "#### I1 "}
  - {id: I2, verdict: MATCHES,   anchor: "#### I2 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [should-fix]
**Certificate**: `#### P3` (Premise: the new dsc-ai-tell regexes "ship demotable" and a "blocker-severity false positive would exit 1 and block the loop")
**Location**: track-2.md D4b (`:34-35`) and Validation acceptance line `:76`; plan D5 risk + track-1.md:64-65; against `.claude/scripts/design-mechanical-checks.py` `check_dsc_ai_tell` (`:1762-2064`) and `edit-design/SKILL.md:397`.
**Issue**: The track's D4b risk and the plan's D5/A9 narrative (echoed in track-1.md:64 "a blocker-severity false positive during Phase-4 self-application would exit 1 and block the loop" and track-1.md:65 "the regex deliverable and its **demotable severity-application surface** in Track 2") presuppose two things the code does not provide:
  1. **No blocker path exists in `dsc-ai-tell`.** Every one of the nine existing patterns emits `make_finding("should-fix", "dsc-ai-tell", …)` — there is no `"blocker"` severity anywhere in `check_dsc_ai_tell`. Only `blockers > 0` sets `verdict = "NEEDS REVISION"` and exit 1 (`:2263-2278`). A `should-fix` from this rule never exits 1. So the "blocker-severity false positive ⇒ exit 1 ⇒ block the loop" hazard is **unreachable** for this rule as coded; the two new regexes, added at `should-fix` like their siblings, cannot block the Phase-4 loop regardless of false-positive count.
  2. **No demote mechanism exists.** "Demotable severity" has no code surface. The module comment at `:99-106` describes demote-to-`suggestion` as a *documented manual fallback* ("the documented fallback if real usage shows the qualifications matter"), not an automatic downgrade. There is no severity-config the implementer can wire a new regex into "at demotable severity"; the only choice is the literal severity string in the `make_finding` call.
The net effect: the deliverable is just "add two regexes at `should-fix`, matching the eight siblings." That is implementable and fine. But the surrounding "demotable severity-application surface" framing implies a mechanism to build, and the acceptance criterion at `:76` ("produces no blocker-severity false positive from the two new regexes") tests a condition that is vacuously true (the rule emits no blockers at all), so it is not a meaningful gate.
**Proposed fix**: In decomposition, pin the regex step to: emit both new regexes at `"should-fix"` (the rule's uniform severity), introduce **no** new blocker path and **no** severity-config. Reword the D4b/track-1.md "demotable severity-application surface" and the `:76` acceptance line so "demotable" reads as "ships at the rule's existing `should-fix` severity (never blocker), consistent with the `:99-106` documented-fallback discipline" — and make the acceptance criterion testable as "the two new regexes produce zero *findings of any severity* on this branch's frozen `design.md`/Phase-4 `design-final.md`" (the real false-positive contract, mirroring the calibration-ADR zero-finding contract `test_dsc_ai_tell.py` already enforces), rather than the unreachable "no blocker-severity false positive."

### T2 [should-fix]
**Certificate**: `#### E1` (Edge case: the "X, not Y" faux-symmetry regex fires on legitimate contrastive "A, not B" prose)
**Location**: track-2.md D4b (`:34-35`), Plan of Work step 2 (`:56`), Validation line `:73`; against `.claude/scripts/design-mechanical-checks.py` `NEGATIVE_PARALLELISM_RE` (`:150-153`) and the new sibling regex the step adds.
**Issue**: D4b correctly flags that "the 'X, not Y' pattern risks firing on legitimate contrastive 'A, not B' prose," but the magnitude is understated and the calibration target makes it acute. A literal `, not <word>` scan over **this branch's own** `design.md` (the Phase-4 `design-final.md` analog named as the calibration point at `:76`) matched **8 legitimate contrastive constructions** that are not AI-tells:
  - `:187` "bans out-of-file assumptions, not in-file terseness"
  - `:214` "held by the always-on subset wiring on the writers, not by this block"
  - `:238` "the subject-slot inflated label, not the Overview's sanctioned enumeration element"
  - `:252` "the count bump is semantic, not numeric"
  - `:265` "`## Orientation` is a positive floor, not a ban"
  - `:300` "Why live-edit, not staging"
  - `:310` "the legitimacy comes from amending `§1.7`, not from claiming an opt-out"
  - `:338` "acknowledged deviation, not a phantom reference"
These are exactly the prose this branch produces in volume. The canonical AI-tell is the rhetorical *elevation* shape ("It's not X, it's Y" / "not just X but Y" / "X. Not Y."), which the existing `NEGATIVE_PARALLELISM_RE` (`\bit'?s not\b.*\bit'?s\b`) already half-covers for the "it's not … it's" variant. A broad `X, not Y` regex extending it would over-fire across ordinary contrastive negation and, even at `should-fix`, would flood every cold-read of a prose-heavy design with noise.
**Proposed fix**: In decomposition, constrain the "X, not Y" regex to the *faux-symmetry elevation* shape, not bare contrastive negation. Candidate constraints: require a paired/parallel structure (e.g. "not (just|merely|only) X (but|—) Y", or a sentence-initial / post-period "Not X." standing as its own clause), and explicitly exclude the mid-sentence comma-contrast `…, not <noun phrase>`. Add the eight constructions above (or representative reductions) to `test_dsc_ai_tell.py` as **negative-case guards**, alongside the D4b-named "A, not B" guard, so the regression suite pins the no-false-positive contract against this branch's actual register. This is the second false-positive guard the track already commits to (`:48`, `:57`, `:73`); the finding is that its scope must be sized to the 8-hit reality, and the regex shape must be distinct from `NEGATIVE_PARALLELISM_RE` rather than a loosening of it.

### T3 [suggestion]
**Certificate**: `#### P5` (Premise: the WC1 step targets readability-feedback `:36` "GAP-routing target-section guidance" and `:70` "STEP 1 house-style section list")
**Location**: track-2.md Context (`:49`), Plan of Work step 4 (`:58`), Validation line `:75`, Interfaces (`:95`); against `.claude/skills/readability-feedback/SKILL.md` `:36` and `:70`.
**Issue**: The `:70` STEP 1 anchor is accurate — it is the audit sub-agent prompt's "Note especially § …" house-style section list, and adding `§ Orientation` there is correct. The `:36` anchor is mislabeled. Line 36 is **Procedure step 5** ("Draft one rule change per GAP group. Name the rule, the target section (general tells go under Banned vocabulary / … / Structural rules; design-doc-shape tells go under Document-shape rules)…"), which is the *rule-author's* target-section taxonomy for where a NEW rule lands — not the audit sub-agent's GAP-routing. The track frames `:36` as "the audit sub-agent prompt's … GAP-routing target-section guidance," but the audit sub-agent prompt is the fenced block at `:67-92`; its classification step is **STEP 4** (`:76`, "Classify each finding as `CAUGHT by § <exact section>` or `GAP`"). A too-terse passage misrouting as GAP happens at STEP 4 (the sub-agent has no `§ Orientation` in its section list to classify against), not at Procedure step 5. Editing `:36` to name `§ Orientation` would change where a future Orientation-style *rule* is filed, which is not the WC1 defect; the defect is that the **audit sub-agent** does not know `§ Orientation` exists. STEP 1 (`:70`) already fixes that by putting `§ Orientation` in the section list the agent reads; STEP 4 could optionally gain an explicit "too-terse / one-line-assertion passages route to `§ Orientation`" hint. Separately, the Context-line claim (`:49`) that the design-review Rule sync map entry "today lists only the Human-reader bullets" slightly mischaracterizes line 47: that line is a single sync-instruction bullet ("a `### Human-reader cold-read additions` bullet, the `§ Tone and depth` count, and the TOC-row summary"), not a per-rule row list; the amendment should extend that bullet to also name the `### Prose AI-tell additions` block, not "add a row."
**Proposed fix**: In decomposition, retarget the WC1 step's second edit from `:36` (Procedure step 5) to the audit sub-agent prompt's **STEP 4** (`:76`) — add a clause routing too-terse / disconnected-one-line-assertion passages to `§ Orientation` at classification time — while keeping the STEP 1 (`:70`) section-list addition. Update Validation line `:75` and Interfaces `:95` to cite `:76` instead of `:36`. Reword Context `:49` so the design-review Rule-sync-map edit reads as "extend the existing design-review sync bullet (`:47`) to name the `### Prose AI-tell additions` block," not "add a row."

### T4 [suggestion]
**Certificate**: `#### P6` (Premise: the new cold-read block "syncs the `§ Tone and depth` count")
**Location**: track-2.md D4 (`:28`, "the `§ Tone and depth` count, line 406"), Plan of Work step 1 (`:55`), Validation line `:72`; plan D4 edge-case (design.md `:230-231`); against `.claude/workflow/prompts/design-review.md:177` and `:406`.
**Issue**: The "count" the track says it must sync is the literal word "five" in "the **five** Human-reader rules require evidence" at `:406` (and `:177`). That count names the **five bullets inside the sibling `### Human-reader cold-read additions` block** (audience-fit, glossary-introduction, why-before-what, navigability, explanatory register — verified five at `:171-175`), and the tone exception relaxes the one-sentence rule for *those five judgment rules*. The new `### Prose AI-tell additions` block is a **separate sibling block**, not a sixth Human-reader rule. So "sync the count" is mechanically ambiguous: (a) if the new block's reviewer also needs the quote-evidence tone relaxation (it scans `§ Banned analysis patterns`, `§ Mechanism traces`, lists-as-sentences, inflated labels, and `§ Orientation` — all judgment calls that benefit from quoting the offending prose), the count at `:406`/`:177` is no longer "five" and the wording must change (to "six," or to a phrasing that covers both blocks); (b) if the new block is excluded from that exception, no count edit is needed and the track's "sync the count" instruction is a no-op that an implementer would chase. The track asserts a sync without specifying which, and `:406` literally hardcodes "five."
**Proposed fix**: In decomposition, decide and pin one of two outcomes: either (i) the new block carries its **own** Reviewer-tone note (parallel to `:177`, scoped to its own scan dimensions) and the `:406`/`:177` "five Human-reader rules" wording stays unchanged — making the "sync the `§ Tone and depth` count" instruction a no-op to drop; or (ii) the new block's dimensions fold under the same tone exception and the count moves to "six" at both `:177` and `:406`. Update D4 (`:28`), step 1 (`:55`), Validation (`:72`), and the design.md edge-case (`:230-231`) to state the chosen mechanism, so the implementer does not guess.

## Evidence base

#### P1 Premise: design-review.md has a `### Human-reader cold-read additions` block near line 165 with a design-kinds-only applies-to line
- **Track claim**: track-2.md `:28`/`:46` — "`### Human-reader cold-read additions` (`design-review.md:165`)"; the new block "cannot copy its applies-to line" because the sibling "applies only to the three design-target mutation kinds."
- **Search performed**: grep + Read of `.claude/workflow/prompts/design-review.md:165-180`.
- **Code location**: `.claude/workflow/prompts/design-review.md:165` (heading), `:168` (applies-to line).
- **Actual behavior**: `:165` `### Human-reader cold-read additions`; `:168` "Applies to `phase1-creation`, `phase4-creation`, `design-sync`." The `target=tracks` cold-read is a separate `## Track-scoped cold-read (Step 4b)` section (`:182`) that does not reference the Human-reader additions. The five Human-reader bullets are `:171-175`.
- **Verdict**: CONFIRMED
- **Detail**: Anchor 165 is exact. D4's core claim — the sibling block's applies-to line covers the three design kinds only and omits `target=tracks`, so the new block needs its own line spanning `target=design` (three kinds) AND `target=tracks` — is correct and load-bearing. No finding from this premise.

#### P2 Premise: the cold-read block's scanned-against house-style sections all exist
- **Track claim**: track-2.md `:27` / design.md `:204-207` — the block scans `§ Banned analysis patterns`, `§ Mechanism traces and inline citations`, lists-disguised-as-sentences, inflated-abstraction labels, and the `## Orientation` too-terse direction.
- **Search performed**: grep over `.claude/output-styles/house-style.md`.
- **Code location**: house-style.md `:54` `## Orientation`, `:129` `## Banned analysis patterns`, `:384` `### Mechanism traces and inline citations`, `:451` `### Explanatory register`.
- **Actual behavior**: All four named sections present; `## Orientation` (Track 1's deliverable) is fully landed with floor, anti-padding clause, register distinction, and finding category (`:54-76`). The block's too-terse scan is grounded.
- **Verdict**: CONFIRMED
- **Detail**: Inter-track dependency on Track 1 (`## Orientation` must exist) is satisfied on the current branch tip. No finding.

#### P3 Premise: the two new dsc-ai-tell regexes "ship demotable" with a reachable blocker/exit-1 hazard
- **Track claim**: track-2.md D4b (`:34-35`), Validation `:76`; plan D5 + track-1.md:64-65 — "ship demotable," "a blocker-severity false positive … would exit 1 and block the loop," "demotable severity-application surface in Track 2."
- **Search performed**: grep `"blocker"` across `design-mechanical-checks.py`; Read of `check_dsc_ai_tell` (`:1762-2064`), the driver/verdict (`:2263-2278`), the constants comment (`:99-106`), and `edit-design/SKILL.md:397`.
- **Code location**: `.claude/scripts/design-mechanical-checks.py:1762-2064` (every `dsc-ai-tell` finding hardcoded `"should-fix"`); `:2263-2266` (`blockers` count drives verdict); `:99-106` (demote = documented manual fallback, no code).
- **Actual behavior**: `check_dsc_ai_tell` emits only `should-fix`. No `blocker` is reachable, so exit 1 from this rule is impossible; the verdict stays PASS. No automatic demote-to-suggestion exists — the only severity lever is the literal string in `make_finding`.
- **Verdict**: PARTIAL
- **Detail**: The deliverable (add two regexes at `should-fix`) is implementable, but the "demotable severity-application surface" / exit-1 framing describes a mechanism that does not exist and an acceptance criterion (`:76`) that is vacuously satisfiable. → **T1**.

#### P4 Premise: Phase-4 `design-final.md` is scanned by dsc-ai-tell (the calibration target is real)
- **Track claim**: track-2.md `:35`/`:76` — calibration against "this branch's own Phase-4 `design-final.md` authoring (where the live regex self-applies through `edit-design`)."
- **Search performed**: grep over `.claude/skills/edit-design/SKILL.md`.
- **Code location**: `edit-design/SKILL.md:136` (phase4-creation row: `--target design`, `whole-doc` on `design-final.md`); `:359-365` invocation; `:594-595` `design_path = design-final.md`.
- **Actual behavior**: `phase4-creation` runs `design-mechanical-checks.py --design-path design-final.md --target design --scope whole-doc`, which calls `check_dsc_ai_tell` on `design-final.md`. The new regexes will run on the Phase-4 artifact.
- **Verdict**: CONFIRMED
- **Detail**: The calibration point is real; the regexes self-apply in Phase 4. This makes T2's false-positive surface concrete (the Phase-4 artifact is the same prose register as the current `design.md`). No standalone finding.

#### P5 Premise: WC1 step anchors in readability-feedback/SKILL.md — `## Rule sync map` @41, STEP 1 @70, GAP-routing @36, governance grep @54
- **Track claim**: track-2.md `:49`/`:55`/`:58`/`:75`/`:95` — STEP 1 list `:70`, GAP-routing `:36`, Rule sync map `:41`, governance grep Track 1's at `:54`.
- **Search performed**: grep + Read of `.claude/skills/readability-feedback/SKILL.md`.
- **Code location**: `:41` `## Rule sync map`; `:47` design-review sync bullet; `:54` governance grep (four strings); `:70` STEP 1 "Note especially § …"; `:36` Procedure step 5; `:76` STEP 4 classification; audit prompt block `:67-92`.
- **Actual behavior**: `:41`, `:54`, `:70` exact. `:36` is Procedure step 5 (rule-author target taxonomy), **outside** the audit sub-agent prompt block (`:67-92`); the audit prompt's classification step is STEP 4 (`:76`). The design-review entry at `:47` is one sync-instruction bullet, not a per-rule row list.
- **Verdict**: PARTIAL
- **Detail**: Three of four anchors accurate; `:36` is mislabeled as audit-prompt GAP-routing when it is Procedure-step rule-filing taxonomy, and the natural Orientation-routing site for the audit sub-agent is STEP 4. → **T3**.

#### P6 Premise: the new cold-read block "syncs the `§ Tone and depth` count" at line 406
- **Track claim**: track-2.md `:28`/`:46`/`:55`/`:72`; design.md `:230-231` — sync "the `§ Tone and depth` 'five Human-reader rules' count."
- **Search performed**: grep + Read of `design-review.md:171-180`, `:403-409`.
- **Code location**: `design-review.md:406` "the **five** Human-reader rules require evidence"; `:177` same count; `:171-175` the five bullets.
- **Actual behavior**: "five" names the five bullets in the sibling Human-reader block. The new `### Prose AI-tell additions` block is a separate sibling, not a sixth Human-reader rule. Whether the count must move (to six) or stay (and the new block gets its own tone note) is unspecified.
- **Verdict**: PARTIAL
- **Detail**: The "sync the count" instruction is mechanically ambiguous; `:406` hardcodes "five" tied to a specific five-bullet block. → **T4**.

#### E1 Edge case: the "X, not Y" regex fires on legitimate contrastive "A, not B" prose on the calibration target
- **Trigger**: a contrastive `…, not <word>` sentence that is not the rhetorical faux-symmetry elevation tell.
- **Code path trace**:
  1. Phase-4 `edit-design` → `design-mechanical-checks.py --design-path design-final.md --target design --scope whole-doc` @ `edit-design/SKILL.md:136,359`.
  2. `check_dsc_ai_tell` per-paragraph scan @ `design-mechanical-checks.py:1917` — a new `X, not Y` regex (D4b step 2) would run alongside `NEGATIVE_PARALLELISM_RE` (`:150-153`).
  3. Probe: `grep -nE ', not [a-z]'` over this branch's `docs/adr/explanation-style/_workflow/design.md` (the `design-final.md` analog) → 8 matches (`:187`, `:214`, `:238`, `:252`, `:265`, `:300`, `:310`, `:338`), all legitimate contrastive negation.
- **Outcome**: a broad `X, not Y` regex would emit up to 8 `should-fix` false positives on the calibration artifact alone — noise, not a blocker (per P3), but it defeats the rule's calibrated zero-false-positive contract and floods cold-reads of prose-heavy designs.
- **Track coverage**: partial — D4b names the contrastive-prose risk and the step commits to a false-positive guard, but understates the magnitude and does not distinguish the new shape from the existing `NEGATIVE_PARALLELISM_RE`. → **T2**.

#### I1 Integration: design-review.md cold-read sibling-block insertion
- **Plan claim**: insert `### Prose AI-tell additions` sibling to `### Human-reader cold-read additions` with its own applies-to line covering `target=design` (three kinds) + `target=tracks`; sync TOC row (near `:22`), `§ Tone and depth` count (`:406`), readability Rule sync map row.
- **Actual entry point**: `design-review.md:165` (sibling block), `:18-31` (TOC region; insert a row near `:22`), `:182` (`## Track-scoped cold-read` — where the `target=tracks` direction is wired).
- **Caller analysis**: the cold-read prompt is invoked by `edit-design` (design mutations, `target=design`) and `create-plan` Step 4b (`target=tracks`) per `:36-38`. Both consume the new block via its applies-to line; no Java callers.
- **Breaking change risk**: low for the block insertion itself; the TOC reindex must keep regions resolving (`workflow-reindex.py --check`, per readability-feedback `:59`). The `§ Tone and depth` count edit is the one fragile sync (T4).
- **Verdict**: MATCHES

#### I2 Integration: dsc-ai-tell regex extension + test wiring
- **Plan claim**: add two regex constants to `dsc-ai-tell` and exercise them in `test_dsc_ai_tell.py` (positive + false-positive guards).
- **Actual entry point**: constants region `design-mechanical-checks.py:91-207`; per-line/per-paragraph scans in `check_dsc_ai_tell:1822-2064`; test harness `test_dsc_ai_tell.py` (`PATTERN_SIGNATURES:67-86`, `NEGATIVE_RANGES:96-99`, `ANCHORED_REGRESSION_CASES:113-119`, `assert_calibration_adrs:224-253`).
- **Caller analysis**: `check_dsc_ai_tell` is called at `:2230` (design) and `:2239` (mechanics). The test shells out to the script against a fixture + three calibration ADRs and enforces a zero-finding contract on the latter — the existing pattern the new false-positive guards should follow.
- **Breaking change risk**: the two new patterns add `PATTERN_SIGNATURES` rows (positive coverage) and need negative-case fixtures/ranges; the inflated-abstraction regex must not fire on the Overview-template "the enabling primitive(s)" (design-document-rules.md:506) sanctioned enumeration, and the X-not-Y regex must clear the 8 contrastive constructions (T2). Calibration-ADR zero-finding contract must still hold.
- **Verdict**: MATCHES
