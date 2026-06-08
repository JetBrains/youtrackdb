<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 1, suggestion: 0}
index:
  - {id: CR1, sev: should-fix, loc: "track-1.md §Interfaces and Dependencies / design.md §Advisory enforcement S2 / implementation-plan.md §Constraints + Invariants S2", anchor: "### CR1 ", cert: R7, basis: "byte-identical paraphrase set understated: SYNC names 5 review prompts + conventions + create-plan, plan names 3 review prompts"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 7}
cert_index:
  - {id: R7, verdict: MISMATCHES, anchor: "#### R7 "}
flags: [CONTRACT_OK]
-->

## Findings

### CR1 [should-fix]
**Certificate**: R7
**Location**: `track-1.md` §Interfaces and Dependencies ("Out-of-scope, must stay byte-identical (S2)") and §Plan of Work step 3; `design.md` §Advisory enforcement Edge cases + S2; `implementation-plan.md` §Constraints + Invariants S2. Live anchor: the SYNC comment at `.claude/workflow/prompts/structural-review.md:56-68`.
**Issue**: The plan, design, and track file all describe the byte-identical sizing-rule paraphrase set as exactly three review prompts — "the three sizing-rule paraphrases (the technical, risk, and adversarial review prompts)". The authoritative SYNC comment that governs which positions must move together names a larger set, and two of the omitted positions are themselves review-prompt paraphrases. The trio framing understates the invariant the S2 diff-check is meant to guard.
**Evidence**: The SYNC comment at `structural-review.md:56-68` enumerates the cross-referencing copies of the sizing rule: the glossary and planning-rule sections of `conventions.md`; the create-plan skill Step 4 sizing rule; the Track terminology bullet in `structural-review.md` itself; and the Track terminology bullet in each of `technical-review.md`, `adversarial-review.md`, `risk-review.md`, and `consistency-review.md` (four named files). That is **five** review-prompt paraphrases (the four named plus this-file/`structural-review.md`), not three. Verified directly: `consistency-review.md:73-75` carries the paraphrase ("the planner maximizes (packs work up to a soft footprint ceiling, related or not) and clamps with a two-sided bound — a merge candidate at ≤~12 in-scope files…"), and `structural-review.md:74-80` carries it in its own Track terminology bullet (distinct from the TRACK SIZING *check* region at line ~193 that the plan edits). The plan/design/track omit both `consistency-review.md` and `structural-review.md` from the paraphrase enumeration. The plan's own diff-verification acceptance line ("the §1.1 glossary, the §1.2 plan summary, and the three review-prompt paraphrases are unchanged — verify by diff") would therefore check a three-file set when the real must-not-change set is five review prompts plus conventions plus create-plan. The S2 intent — leave the sizing rule and all its synchronized copies byte-identical, edit only the structural-review *check* — is sound and unaffected; only the enumeration of which paraphrases exist is wrong.
**Proposed fix**: In all three documents, restate the byte-identical set to match the SYNC comment. Replace "the three sizing-rule paraphrases (the technical, risk, and adversarial review prompts)" with the full SYNC set: the `conventions.md` §1.1 glossary and §1.2 plan-rule summary, the create-plan skill Step 4 sizing rule, and the Track terminology bullet in all five review prompts (`technical-review.md`, `risk-review.md`, `adversarial-review.md`, `consistency-review.md`, and `structural-review.md`'s own bullet). Note explicitly that `structural-review.md` is edited only in its TRACK SIZING *check* region (line ~193), while its Track terminology *paraphrase* bullet (line ~74) stays byte-identical — that distinction keeps S2 true for the edited file. The track file's §Interfaces and Dependencies "Phase A pins the exact filenames before editing" parenthetical should point Phase A at the SYNC comment as the authoritative enumeration.
**Classification**: design-decision
**Justification**: Per §`design-decision` "Multiple plausible fix renderings exist": the truthful set could be stated as the SYNC comment's full list, or the author may have intended a deliberately narrower S2 scope (treating only the trio as "paraphrases" and the other bullets as terminology that happens to coincide). The user wrote the trio framing and holds the rationale for which positions S2 should bind; whether the omission is a mis-count to correct or a scoping choice to keep is a call the orchestrator cannot make. The byte-identical invariant being under-specified also risks a real S2 violation slipping through Phase B and the diff-verification, so the under-escalation cost is asymmetric — escalate.

## Evidence base

#### R1 planning.md §Track descriptions — Track sizing rule, Maximize first, dependency-boundary cut, argumentation gate
- **Document claim**: `track-1.md` §Context and Orientation: "`planning.md` §Track descriptions, 'Track sizing rule' (around lines 432-472). Carries *Maximize first* … 'Prefer a dependency boundary as the cut', the two-sided clamp, and the argumentation gate (an out-of-bounds track without a written justification is a `design-decision` finding at Phase 2)." Plan edit site for D1/D2/D3 + S3 producer half.
- **Search performed**: `grep -nE "Track sizing rule|Maximize first|dependency boundary as the cut|two-sided|Argumentation gate|design-decision" .claude/workflow/planning.md`; Read `planning.md:430-472`.
- **Code location**: `.claude/workflow/planning.md:432` (`**Track sizing rule.**`), `:439` (`*Maximize first.*`), `:442` (`Prefer a dependency boundary as the cut.`), `:451` (`*Then clamp with a two-sided bound.*`), `:457` (`*Argumentation gate.*`), `:462-463` (documented out-of-bounds passes; undocumented is a `design-decision` finding at Phase 2).
- **Actual signature/role**: All four named structures present verbatim. The argumentation gate text matches the claim, including the `design-decision` Phase-2 escalation for an undocumented out-of-bounds track.
- **Verdict**: MATCHES
- **Detail**: Cited line range 432-472 is accurate. Current-state edit site confirmed; the overlap refinements the plan adds are target-state (Track 1 is `[ ]`) and not a finding per the pre-screen.

#### R2 track-review.md §Step Decomposition — Fill ordinary steps toward ~12 + closed two-reason under-fill set
- **Document claim**: `track-1.md` §Context and Orientation: "`track-review.md` §Step Decomposition, 'Fill ordinary steps toward ~12 edited files' (around lines 775-804). Decomposes `low`/`medium` work toward the largest change within ~12 edited files, merging available work related or not, with the closed two-reason under-fill `— size:` set." Plan edit site for D2/D4.
- **Search performed**: `grep -nE "Step Decomposition|Fill ordinary steps|~12 edited files|Under-fill|cold-read" .claude/workflow/track-review.md`; Read `track-review.md:773-840`.
- **Code location**: `.claude/workflow/track-review.md:739` (`### Step Decomposition`), `:775` (`**Fill ordinary steps toward ~12 edited files.**`), `:787-804` (Under-fill justification with the closed set of two reasons: (a) no mergeable `low`/`medium` work fits; (b) heavy-iteration carve-out).
- **Actual signature/role**: Bullet present verbatim; merges `low`/`medium` work related or not toward the largest change within ~12 files; the closed two-reason under-fill `— size:` set is exactly as described, with "unrelated" and "inter-step dependency" both explicitly excluded.
- **Verdict**: MATCHES
- **Detail**: Cited range 775-804 accurate. Current-state edit site confirmed.

#### R3 prompts/structural-review.md TRACK SIZING check + SYNC comment
- **Document claim**: `track-1.md` §Context and Orientation: "`prompts/structural-review.md`, TRACK SIZING (around lines 193-220, with the SYNC comment around lines 56-90). Applies the two-sided footprint bound and routes an undocumented out-of-bounds track to a `design-decision` finding." Plan edit site for D5 + S3 consumer half.
- **Search performed**: `grep -nE "TRACK SIZING|SYNC|out-of-bounds|design-decision|two-sided" .claude/workflow/prompts/structural-review.md`; Read `:54-91` and `:192-213`.
- **Code location**: `.claude/workflow/prompts/structural-review.md:193` (`TRACK SIZING`), `:194-212` (two-sided bound check; documented out-of-bounds passes, undocumented is a `design-decision` finding), SYNC comment at `:56-68`.
- **Actual signature/role**: TRACK SIZING check region present and routes the undocumented out-of-bounds track to `design-decision`, the exact class/severity the plan reuses for the new overlap-split criterion. The SYNC comment opens at line 56 (plan estimate 56-90 is accurate within the line-drift tolerance — it closes at 68).
- **Verdict**: MATCHES
- **Detail**: Cited ranges accurate. The one new criterion bullet is target-state (Track 1 `[ ]`) and not a finding.

#### R4 conventions.md §1.1 glossary Track row + §1.2 plan-rule summary
- **Document claim**: `track-1.md` §Interfaces and Dependencies + `implementation-plan.md` S2: the §1.1 glossary `Track` row and the §1.2 plan-file Planning rule summary paraphrase the sizing rule and must stay byte-identical.
- **Search performed**: `grep -nE "Track sizing|maximizes|two-sided bound|merge candidate|split candidate|footprint ceiling" .claude/workflow/conventions.md`.
- **Code location**: `.claude/workflow/conventions.md:69` (glossary `**Track**` row paraphrasing the sizing rule), `:233-234` and `:302-303` (§1.2 plan-rule summary paraphrase).
- **Actual signature/role**: Both sites carry the sizing-rule paraphrase, consistent with the SYNC comment naming "the glossary and the planning-rule section of the conventions doc (the first two numbered subsections)".
- **Verdict**: MATCHES
- **Detail**: The two conventions.md sites are correctly named in the byte-identical set. No finding for this part — the gap is the review-prompt count (see R7).

#### R5 technical-review.md / risk-review.md / adversarial-review.md — Track terminology paraphrase
- **Document claim**: `implementation-plan.md` S2 + `track-1.md`: "the three sizing-rule paraphrases (the technical, risk, and adversarial review prompts) stay byte-identical".
- **Search performed**: `grep -nE "maximizes \(packs|two-sided bound|merge candidate|split candidate" .claude/workflow/prompts/{technical,risk,adversarial}-review.md`.
- **Code location**: `technical-review.md:48-51`, `risk-review.md:48-51`, `adversarial-review.md:115-118` — each carries the identical paraphrase ("the planner maximizes (packs work up to a soft footprint ceiling, related or not) and clamps with a two-sided bound. A track ≤~12 in-scope files that folds into a neighbor is a merge candidate; a track over ~20-25 in-scope files is a split candidate.").
- **Actual signature/role**: All three named prompts carry the paraphrase as claimed.
- **Verdict**: MATCHES
- **Detail**: The three prompts the plan names do paraphrase the rule. The plan is correct that these three must stay byte-identical — the defect is that the set is larger (R7), not that these three are wrong.

#### R6 create-plan skill Step 4 sizing rule
- **Document claim**: Implicit in the SYNC comment governing the byte-identical set; the plan does not list it. Checked for completeness of the out-of-scope set.
- **Search performed**: `grep -nE "Track sizing rule|maximize|footprint ceiling|merge candidate|split candidate|~20-25" .claude/skills/create-plan/SKILL.md`.
- **Code location**: `.claude/skills/create-plan/SKILL.md:324-329` — Step 4 sizing-rule paraphrase ("size each track by its in-scope file footprint … footprint ceiling (related or not) … a track ≤~12 in-scope files that folds into a neighbor is a merge candidate (flag-only); a track over ~20-25 in-scope files is a split candidate.").
- **Actual signature/role**: The create-plan Step 4 sizing rule is a synchronized copy, as the SYNC comment states. The plan/design/track never enumerate it in the byte-identical set.
- **Verdict**: PARTIAL
- **Detail**: This site belongs to the must-not-change set per the SYNC comment but is absent from the plan's enumeration. Folded into R7 (the same understated-set defect); not a separate finding because the plan's intent is to leave the whole sizing rule untouched, so create-plan is covered by S2's spirit even when not named.

#### R7 Byte-identical paraphrase set — claimed trio vs authoritative SYNC set
- **Document claim**: `track-1.md` §Interfaces and Dependencies, `design.md` §Advisory enforcement Edge cases + S2, `implementation-plan.md` §Constraints + Invariants S2: the byte-identical paraphrase set is "the three sizing-rule paraphrases (the technical, risk, and adversarial review prompts)".
- **Search performed**: Read the SYNC comment at `structural-review.md:56-68`; `grep` for the paraphrase across all five `*review*.md` prompts (R5 + the two below).
- **Code location**: SYNC comment `structural-review.md:56-68`; paraphrase present at `consistency-review.md:73-75` and `structural-review.md:74-80` in addition to the three named prompts (R5).
- **Actual signature/role**: The SYNC comment names the synchronized positions as the conventions glossary + planning-rule section, the create-plan Step 4 rule, the Track terminology bullet in `structural-review.md` itself, and the Track terminology bullet in `technical-review.md`, `adversarial-review.md`, `risk-review.md`, and `consistency-review.md`. That is five review-prompt paraphrases plus conventions plus create-plan. The plan names only three review prompts and omits `consistency-review.md` and `structural-review.md` (and create-plan).
- **Verdict**: MISMATCHES
- **Detail**: Current-state factual error in the out-of-scope enumeration. The S2 intent holds (no paraphrase changes, only the structural-review *check* region gains a bullet), and `structural-review.md` being edited stays consistent with S2 because its paraphrase *bullet* (line ~74) is a different region from the edited *check* (line ~193). But the diff-verification acceptance line guards a three-file set when the true must-not-change set is five review prompts plus two conventions sites plus create-plan. Produces finding CR1.

#### R8 Decision Records D1-D5 internal consistency and S1/S2/S3 cross-document agreement
- **Document claim**: `implementation-plan.md` D1-D5 and S1/S2/S3 match the `design.md` References (D1-D5, S1-S3) and the track-1 narrative; no DR contradicts another.
- **Search performed**: Read `design.md` §References (D1-D5, S1-S3) and `implementation-plan.md` §Architecture Notes (D1-D5, Invariants S1-S3); cross-compared each DR's Alternatives/Rationale/Risks and each invariant's testable statement.
- **Code location**: `design.md:67,69,71,73,93,95,114,116,133,135,137`; `implementation-plan.md:79-194`.
- **Actual signature/role**: Each plan DR mirrors its design counterpart, with the `**Full design**` pointer present on every plan DR (D1/D2 → §"The token model", D3 → §"Track-level packing and cut seams", D4 → §"Step-level overlap-aware fill", D5 → §"Advisory enforcement"). S1 (subordination), S2 (metric/bounds unchanged), S3 (producer/consumer co-ship) are stated consistently in both. No DR contradicts another.
- **Verdict**: MATCHES
- **Detail**: The single mismatch is the byte-identical-set enumeration inside S2's testable clause (R7); the DR rationale and the S1/S3 invariants are internally consistent across all three documents. `design.md` is frozen (`workflow-sha: a91143fb60…` matches the stamp on all three artifacts), so the trio claim is repeated identically in the frozen design and the plan — fixing CR1 in the plan/track is allowed; the frozen design lagging is reconciled at Phase 4 per the frozen-design rule.
