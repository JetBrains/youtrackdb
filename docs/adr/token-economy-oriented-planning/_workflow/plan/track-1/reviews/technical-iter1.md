<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: T1, sev: suggestion, loc: "track-1.md:81-84,128", anchor: "### T1 ", cert: P3, basis: "SYNC line-range hint cites 56-90; comment body is 56-68 — advisory anchor only, correctly-described content"}
evidence_base: {section: "## Evidence base", certs: 9, matches: 9}
cert_index:
  - {id: P1, verdict: CONFIRMED, anchor: "#### P1 "}
  - {id: P2, verdict: CONFIRMED, anchor: "#### P2 "}
  - {id: P3, verdict: PARTIAL,   anchor: "#### P3 "}
  - {id: P4, verdict: CONFIRMED, anchor: "#### P4 "}
  - {id: P5, verdict: CONFIRMED, anchor: "#### P5 "}
  - {id: P6, verdict: CONFIRMED, anchor: "#### P6 "}
  - {id: P7, verdict: CONFIRMED, anchor: "#### P7 "}
  - {id: I1, verdict: MATCHES,   anchor: "#### I1 "}
  - {id: I2, verdict: MATCHES,   anchor: "#### I2 "}
flags: [CONTRACT_OK]
-->

## Findings

### T1 [suggestion]
**Certificate**: Premise P3 (SYNC comment contents and location)
**Location**: track-1.md `## Context and Orientation` lines 81-84 and `## Plan of Work` step 3 line 128 — both cite the SYNC comment as "around lines 56-90".
**Issue**: The SYNC comment body in `prompts/structural-review.md` spans lines 56-68; the range 56-90 the track cites extends into the SYNC-governed `**Track**:` terminology bullet (lines 71-80) that follows. The cited content is described correctly — the track's enumeration of the synchronized set (conventions §1.1 + §1.2, create-plan Step 4, the Track terminology paraphrase in all five review prompts) matches the SYNC comment's named positions exactly — so this is only a loose line-range hint, not a content error, and "around" already softens it. It does not affect implementability: a Phase B implementer reading "around lines 56-90" still lands on the SYNC comment.
**Proposed fix**: Optional. Tighten both citations to "around lines 56-68" (the comment body) during decomposition, or leave as-is — the "around" qualifier and the accurate prose description make this immaterial. Not worth a step on its own.

## Evidence base

#### P1 planning.md §Track descriptions carries the text the track edits
- **Track claim**: track-1.md C&O lines 68-75 — "`planning.md` §Track descriptions, 'Track sizing rule' (around lines 432-472). Carries *Maximize first* ... 'Prefer a dependency boundary as the cut', the two-sided clamp, and the argumentation gate (an out-of-bounds track without a written justification is a `design-decision` finding at Phase 2)."
- **Search performed**: Read `.claude/workflow/planning.md` lines 400-520; grep for `**Track sizing rule.**`
- **Code location**: planning.md:432 (`**Track sizing rule.**`), :439 (`*Maximize first.*`), :442 ("Prefer a dependency boundary as the cut"), :451 (`*Then clamp with a two-sided bound.*`), :457 (`*Argumentation gate.*`), :463 ("an undocumented one is a `design-decision` finding at Phase 2 review")
- **Actual behavior**: The "Track sizing rule" section runs 432-472. *Maximize first* (439-449) packs autonomous units to the ceiling related-or-not and carries "Prefer a dependency boundary as the cut" verbatim at 442. The two-sided clamp (451-455) and the Argumentation gate (457-467) routing an undocumented out-of-bounds track to a `design-decision` Phase-2 finding are present exactly as the track describes. These are the four producer-side landing spots (packing-order preference, least-shared cut-seam, adjacent ordering, overlap-split justification).
- **Verdict**: CONFIRMED
- **Detail**: Edit site exists with the claimed text. Line range 432-472 accurate.

#### P2 track-review.md §Step Decomposition Fill bullet carries the text the track edits
- **Track claim**: track-1.md C&O lines 76-80 — "`track-review.md` §Step Decomposition, 'Fill ordinary steps toward ~12 edited files' (around lines 775-804). Decomposes `low`/`medium` work toward the largest change within ~12 edited files, merging available work related or not, with the closed two-reason under-fill `— size:` set."
- **Search performed**: Read `.claude/workflow/track-review.md` lines 755-830; grep for the Fill-bullet heading and the maximized-step closing sentence
- **Code location**: track-review.md:775 (`**Fill ordinary steps toward ~12 edited files.**`), :778 ("merging available `low`/`medium` work (related or not)"), :787-804 (the `Under-fill justification` sub-bullet with the closed two-reason set), :803 ("A step at or near ~12 is maximized and carries no clause")
- **Actual behavior**: The Fill bullet runs 775-804 exactly. It directs decomposing toward the largest change within ~12 edited files, merging available `low`/`medium` work related or not (778). The closed two-reason under-fill set — (a) no mergeable `low`/`medium` work fits, (b) heavy-iteration carve-out — is at 791-796, with "unrelated to the rest of the track" and "inter-step dependency" both explicitly excluded as reasons (797-803). This is the step-level landing spot for the overlap-aware merge ordering and the adjacency caveat.
- **Verdict**: CONFIRMED
- **Detail**: Edit site exists with the claimed text. Line range 775-804 exact.

#### P3 structural-review.md TRACK SIZING region and SYNC comment
- **Track claim**: track-1.md C&O lines 81-84 — "`prompts/structural-review.md`, TRACK SIZING (around lines 193-220, with the SYNC comment around lines 56-90). Applies the two-sided footprint bound and routes an undocumented out-of-bounds track to a `design-decision` finding."
- **Search performed**: Read `.claude/workflow/prompts/structural-review.md` lines 40-150 and 180-234; grep for `^TRACK SIZING`, `^<!-- SYNC`
- **Code location**: structural-review.md:56-68 (SYNC comment body), :71-80 (the SYNC-governed `**Track**:` terminology bullet), :193 (`TRACK SIZING`), :206-212 (the existing out-of-bounds → `design-decision` criterion)
- **Actual behavior**: TRACK SIZING starts at 193; the existing two-sided footprint criterion routing an undocumented out-of-bounds track to a `design-decision` finding is at 206-212 — the slot the new overlap-split criterion lands alongside. The SYNC comment body is 56-68; it is followed by, not coextensive with, the `**Track**:` paraphrase bullet at 71-80. The track's cited range "56-90" overruns the comment body into the terminology bullet.
- **Verdict**: PARTIAL
- **Detail**: TRACK SIZING region and the consumer hook are exactly as claimed (193 onward, criterion at 206-212). The SYNC comment exists and its named positions match the track's enumeration; only the line-range hint ("56-90" vs actual 56-68) is loose. Drives finding T1 (suggestion). Not a content error: the prose description of the SYNC contents is accurate.

#### P4 The SYNC comment enumerates exactly the byte-identical set the track names (S2)
- **Track claim**: track-1.md `## Interfaces and Dependencies` lines 206-215 and `## Plan of Work` invariants 133-142 — the out-of-scope byte-identical set is "`conventions.md` §1.1 glossary + §1.2 plan summary, the create-plan Step 4 sizing rule, and the Track terminology paraphrase in all five review prompts (technical, risk, adversarial, consistency, and structural-review.md's own bullet)."
- **Search performed**: Read SYNC comment at structural-review.md:56-68; cross-checked each named position by grep (see P5-P7)
- **Code location**: structural-review.md:56-68
- **Actual behavior**: The SYNC comment names: "the glossary and the planning-rule section of the conventions doc (the first two numbered subsections)" = §1.1 + §1.2; "the Step 4 sizing rule of the create-plan skill"; "the Track terminology bullet in this file" (structural-review.md itself); and "the Track terminology bullet in each of the technical-review, adversarial-review, risk-review, and consistency-review prompt files (four separate files)." That is conventions §1.1+§1.2, create-plan Step 4, and the Track bullet in five review prompts (the four named + structural-review.md's own). This is exactly the set the track enumerates.
- **Verdict**: CONFIRMED
- **Detail**: Track's S2 set is byte-for-byte the SYNC comment's set. The post-CR1 broadening recorded in the plan's Plan Review section is faithfully carried into both the plan §Constraints and the track's three S2 sites (Plan of Work invariants, Validation and Acceptance, Interfaces out-of-scope list). The "five review prompts = four named + structural-review.md's own" arithmetic is correct.

#### P5 conventions.md §1.1 Glossary and §1.2 carry the synchronized sizing-rule paraphrase
- **Track claim**: S2 names "`conventions.md` §1.1 glossary row and §1.2 plan-file Planning rule summary" as byte-identical-protected copies.
- **Search performed**: grep for `## 1.1`, `## 1.2` in conventions.md; Read conventions.md:64-92
- **Code location**: conventions.md:64 (`## 1.1 Glossary`), :69 (the `**Track**` glossary row carrying the maximize/clamp/floor/ceiling paraphrase), :92 (`## 1.2 Plan File Structure`)
- **Actual behavior**: §1.1 Glossary exists; its `**Track**` row (line 69) paraphrases the sizing rule ("Sized by file footprint, not step count: the planner *maximizes* ... clamps with a floor below (≤~12 in-scope files...) and a ceiling above (split candidate at >~20-25)"). §1.2 Plan File Structure exists at line 92. Both anchors resolve as workflow-doc sections, not Java symbols.
- **Verdict**: CONFIRMED
- **Detail**: Both SYNC-named conventions positions exist and carry the protected paraphrase.

#### P6 create-plan SKILL.md Step 4 carries the sizing rule
- **Track claim**: S2 names "the create-plan skill Step 4 sizing rule" as byte-identical-protected.
- **Search performed**: grep for `footprint`/`merge candidate`/`split candidate` in create-plan SKILL.md; Read SKILL.md:248-339
- **Code location**: create-plan/SKILL.md:324-333 ("Track sizing rule: size each track by its in-scope file footprint ... merge candidate ... split candidate ... an out-of-bounds track passes when its track file carries a written justification"), inside "Step 4b — Derive the plan from the frozen design" (heading at :273), list item "4. Decompose the work into tracks" (:304)
- **Actual behavior**: The sizing-rule paraphrase is at 324-333, nested under Step 4b → list item 4. The reference "create-plan Step 4 sizing rule" resolves correctly.
- **Verdict**: CONFIRMED
- **Detail**: SYNC-named create-plan position exists and carries the paraphrase.

#### P7 All five review prompts carry a Track terminology bullet
- **Track claim**: S2 names "the Track terminology paraphrase in all five review prompts: technical-review.md, risk-review.md, adversarial-review.md, consistency-review.md, and structural-review.md's own Track terminology bullet."
- **Search performed**: grep `^- \*\*Track\*\*:` across the five `prompts/*-review.md` files
- **Code location**: technical-review.md:44, risk-review.md:44, adversarial-review.md:111, consistency-review.md:70, structural-review.md:71
- **Actual behavior**: Every one of the five review prompts carries a `**Track**:` terminology bullet. structural-review.md's own bullet (71-80) is distinct from its TRACK SIZING check region (193 onward), so editing TRACK SIZING cannot disturb the protected paraphrase — exactly as the track asserts at Interfaces lines 213-215 and Plan of Work line 142.
- **Verdict**: CONFIRMED
- **Detail**: All five SYNC-named prompt paraphrases exist. The in-file separation (terminology bullet vs criteria region) in structural-review.md is real, so the track's S2-holds-without-touching-the-paraphrase argument is sound.

#### I1 S3 consumer hook — the existing argumentation gate / `design-decision` Track-sizing trigger
- **Plan claim**: plan §Integration Points lines 181-185 and track Plan of Work step 3 — the new criterion "plugs into the existing argumentation gate: an undocumented non-adjacent overlap-split becomes a `design-decision` finding, the same class and severity as the existing undocumented out-of-bounds track. No new finding class, no new escalation path."
- **Actual entry point**: structural-review.md:206-212 (TRACK SIZING criterion: undocumented out-of-bounds → `design-decision`) backed by the `design-decision` triage subsection at :416-454, whose **Track sizing** trigger (:425-431) fires on "a track out of bounds on the two-sided file-footprint rule ... that carries no written justification."
- **Caller analysis**: The consumer is reviewer-plan at Phase 2, which already reads every pending track's `## Interfaces and Dependencies` (confirmed by structural-review.md:129-142 "Where track descriptions live" + the cross-file annotations on the sizing criteria). The existing gate fires on footprint-count out-of-bounds, never on file overlap — so without the new criterion the directive would have no backstop, exactly as D5 (plan lines 142-157) argues. The new bullet adds an overlap-split trigger in the same `design-decision` class.
- **Breaking change risk**: None. Adding one criterion bullet alongside an existing same-class check introduces no new finding class and no new escalation path; the `design-decision` triage already enumerates "Track sizing" as a trigger family.
- **Verdict**: MATCHES

#### I2 Step-level adjacency caveat premises hold against existing workflow text
- **Plan claim**: track Plan of Work step 2 / D4 — "two distinct steps each spawn their own implementer and Phase C reads the whole track diff regardless of step order, so step adjacency without a merge removes no implementer invocation."
- **Actual entry point**: conventions.md:78 (`**Implementer**`: "A fresh sub-agent spawned per step in Phase B"); track-review.md:816 ("Phase C always runs against the cumulative track diff regardless of the per-step distribution")
- **Caller analysis**: Both premises are stated facts of the current workflow. The per-step implementer spawn is the conventions.md glossary definition; the cumulative-diff Phase C read sits two lines below the very Fill bullet this track edits (track-review.md risk-tagging block, :810-817). The caveat reinforces an already-true property rather than inventing one, which is its stated anti-drift purpose (D4).
- **Breaking change risk**: None. The caveat is a clarifying statement on an existing rule; it contradicts no neighboring rule. The "make steps adjacent" anti-pattern it guards against is not asserted anywhere in the current text, so the caveat closes a future-drift gap without conflicting with present rules.
- **Verdict**: MATCHES
