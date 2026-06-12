<!-- workflow-sha: 26f990ed824d113fdb5fcb930361e69378f0f12a -->
# Adversarial review — Track 1, iteration 1

**Role:** reviewer-adversarial · **Phase:** 3A · **Verdict:** CHANGES (2 should-fix, 2 suggestion)

**Scope note (§1.7 opt-out, D6).** This plan carries no `§1.7(b)` workflow-modifying marker; it uses the prose-rule self-application opt-out per the `### Constraints` note. I treated the plan as workflow-modifying for criteria purposes, applied the five prose criteria (rule coherence, instruction completeness, prompt-design soundness, context-budget, dependent-prompt breakage), and resolved every `.claude/**` read against the live tree (no `_workflow/staged-workflow/`). The absent staging marker is the acknowledged D6 posture, not a finding. Track touches no Java/Kotlin, so grep + Read give full reference accuracy — no PSI caveat.

## Findings

### A1 [should-fix]
**Certificate**: Assumption test — "the 30 narrow-grep sites take the canonical reworded sentence byte-identically"
**Target**: Decision D1 (faithful full sync) / Plan of Work step 4
**Challenge**: D1 and Plan-of-Work step 4 partition the flip sites into "the 30 'banned-section heading slugs' blurbs take the canonical reworded sentence **byte-identically**" versus "the three sites the narrow grep misses, hand-edited and adapted." That partition is wrong: 2 of the 30 narrow-grep *hits* are line-wrapped, so a single-line byte-identical find/replace will not match them either. `episode-format-reference.md:44-46` wraps the literal as `... structural rules. The four\nbanned-section heading slugs to apply are `## Banned vocabulary`,\n...`, and `step-implementation.md:1038-1039` wraps it as `The four banned-section heading slugs to apply are\n`## Banned vocabulary`, ...`. Both are inside the count of 30 (the narrow phrase `banned-section heading slugs` is itself unwrapped, so `grep -rln` catches them), but the canonical single-line paste/replace step the track prescribes for "the 30" silently skips them. An implementer mechanically applying one find/replace across the 30 leaves these 2 at four-of-five — exactly the window D1 forbids, which `review-workflow-consistency` flags at Phase C (rework). The track flags exactly one of the three line-wrapped sites (`review-workflow-pr/SKILL.md`, in the chat bucket) and misses these two.
**Evidence**: Verbatim-canonical-sentence count = 28/30; the 2 non-verbatim are `episode-format-reference.md` and `step-implementation.md`, both confirmed line-wrapped (grep over the live tree). The track's "three sites the narrow grep misses" (`review-workflow-pr/SKILL.md:44-45`, `commit-conventions.md:191-194`, `implementer-rules.md:1102-1105`) is a different set — none of those three is one of these two.
**Survival test**: WEAK. The behavioral acceptance criterion (governance grep + Orientation-presence on every closed-set enumeration; "No site enumerates the subset as four-of-five") *does* catch the residual at Phase C, so the track cannot pass acceptance with the miss. But the execution recipe is wrong for 2 sites, so an implementer reading only the track file produces an incomplete flip that only Phase C surfaces — avoidable rework.
**Proposed fix**: In D1 risks and Plan-of-Work step 4, reclassify by *line-wrap*, not by *grep-bucket*: the byte-identical paste applies only to the single-line sites (28 of the 30 slug sites + 10 of the 11 chat sites); the line-wrapped sites needing a wrap-aware or hand edit are **five**, not three — add `episode-format-reference.md:44-46` and `step-implementation.md:1038-1039` to the named hand-edit list alongside `review-workflow-pr/SKILL.md`, `commit-conventions.md`, `implementer-rules.md`. Update the acceptance note's parenthetical from "silently misses the two line-wrapped / variant-phrased sites" (which counts only the grep-miss set) to name the wrapped narrow-grep-hit sites too.

### A2 [should-fix]
**Certificate**: Violation scenario — "house-style.md / conventions.md §1.5 internally consistent after the flip"
**Target**: Invariant ("Subset enumeration is uniform after Track 1") / Plan of Work step 3 + in-scope `conventions.md` note
**Challenge**: `conventions.md:568` carries a four-count *narrative* sentence inside §1.5, between the Tier-B table row and the governance grep: "The four Tier-B section names are stable headings after YTDB-836; a future rename in `house-style.md` requires updating every pointer in the same commit." Plan-of-Work step 3 names "the `§1.5` Tier-B row" and the in-scope note names "`§1.5` Tier-B row + code-comment restatement + the line-570 governance grep" — neither names line 568. After the flip the §1.5 table row lists five sections but the very next sentence still reads "The four Tier-B section names," leaving §1.5 self-contradictory. This is the same class of count-drift the track correctly captures for `house-style.md:20` ("line-~20 count") but omits for its sibling in `conventions.md`. The stated acceptance check is scoped to "every closed-set **enumeration**" naming Orientation; line 568 is a bare count phrase ("four Tier-B section names"), not a slug enumeration, so a residual "four" there is not guaranteed to be caught by the acceptance grep.
**Evidence**: `grep -n 'four Tier-B'` over the live tree returns exactly `conventions.md:568`; the track file has no mention of line 568, "Tier-B section names," "stable headings," or "YTDB-836" (confirmed by grep over `track-1.md`).
**Survival test**: WEAK. The invariant is sound but the plan-of-work and in-scope inventory under-specify the edit, and the acceptance criterion as written does not reliably catch the residual count.
**Proposed fix**: Name `conventions.md:568` ("five Tier-B section names") in Plan-of-Work step 3 and in the `conventions.md` in-scope note, alongside the Tier-B row and the line-570 grep. Optionally widen the acceptance check to also assert no residual "four … section/slug" count phrase survives in `house-style.md` and `conventions.md §1.5` (it already covers `house-style.md:20` implicitly via the in-scope note, but a count-phrase grep would make the §1.5 case explicit).

### A3 [suggestion]
**Certificate**: Assumption test — "the 11-chat-blurb instruction and the hand-edit-three instruction do not overlap"
**Target**: Plan of Work step 4 / Interfaces and Dependencies roster
**Challenge**: `review-workflow-pr/SKILL.md` is listed twice with two different treatments. The Interfaces roster (line 130) folds it into "the … 11 chat-blurb files … including the hard-wrapped `review-workflow-pr/SKILL.md:44-45`," and step 4 first says "the 11 chat blurbs take the find/replace pair" then separately says "Hand-edit the three sites the narrow grep misses: the hard-wrapped chat-blurb site (`review-workflow-pr/SKILL.md:44-45`)." An implementer could read the find/replace-pair instruction as applying to all 11 (including this one) and then also hand-edit it, or could apply neither cleanly. The file is genuinely a chat-bucket member (caught by the `AI-tell subset of` grep) that *also* needs a hand edit because it is wrapped — the two statements are reconcilable but not stated as mutually exclusive.
**Evidence**: `grep -l 'AI-tell subset of'` includes `review-workflow-pr/SKILL.md` (it is one of the 11); its list literal is split across lines 44-45 (confirmed: the single-line list literal does not match it).
**Survival test**: YES. The decision holds — the file does belong in the chat bucket and does need a hand edit. The ambiguity is presentational.
**Proposed fix**: State the exclusion once: "10 of the 11 chat blurbs take the byte-identical find/replace pair; `review-workflow-pr/SKILL.md:44-45` is the 11th, hand-edited because its list literal is line-wrapped." Pairs naturally with the A1 reclassification (line-wrap, not grep-bucket).

### A4 [suggestion]
**Certificate**: Assumption test — "the `ai-tells/SKILL.md` catalogue has a four-name closed-set row that gains an Orientation row"
**Target**: Decision D1 / Plan of Work step 4 ("the `ai-tells` catalogue gains an Orientation row")
**Challenge**: D1's inventory and step 4 treat `ai-tells/SKILL.md` as a flip site that "gains an Orientation row." But the `## Catalogue lookups` section maps *AI-tell fingerprint categories* (vocabulary, structural, tone, punctuation, content/analysis) to house-style sections — it is not a four-name closed-set enumeration of the subset, and it carries no four-count phrase. `## Orientation` is the *too-terse* floor, which is not an AI-tell fingerprint the `ai-tells` skill audits (the skill flags over-dense / promotional tells, not under-explained prose). Adding an "Orientation row" to a fingerprint-to-section map is at best a non-obvious fit and at worst injects a category the skill's audit passes do not act on. The file matches the governance grep only because the catalogue cites the individual section names; by the track's own logic (the three `§`-citing files `review-workflow-writing-style.md`, `design-mechanical-checks.py`, `design-document-rules.md` are "not flip sites"), `ai-tells/SKILL.md` is closer to that category than to a closed-set-enumeration site.
**Evidence**: `ai-tells/SKILL.md` `## Catalogue lookups` is a category→`§`-section map (5 bullets, fingerprint-keyed); `grep 'four\|Four'` over the file returns nothing — no count to bump, no four-name closed set.
**Survival test**: YES (weakly). Adding an Orientation pointer to the skill is defensible if the goal is "the skill points the reader at every always-on subset section," but the "gains an Orientation row" instruction is under-specified about *where* and *as what* (a fingerprint category? a sixth catalogue bullet?). The decision survives; the instruction needs a one-line clarification or an explicit "not a flip site, optional pointer" note.
**Proposed fix**: Clarify in step 4 / D1 whether `ai-tells/SKILL.md` gets a genuine Orientation catalogue entry (and if so, framed as "too-terse / under-orientation → `house-style.md § Orientation`," a new fingerprint category) or whether it is, like the three `§`-citing files, not a closed-set flip site. State the intended row's anchor and framing so the byte-level edit is unambiguous.

## Evidence base

#### Assumption test: the 30 narrow-grep sites take the canonical sentence byte-identically (→ A1)
- **Claim**: D1 / step 4 — "the 30 'banned-section heading slugs' blurbs take the canonical reworded sentence byte-identically"; only the 3 grep-miss sites need adapted hand-edits.
- **Stress scenario**: implementer applies one single-line find/replace of the canonical sentence across all 30 narrow-grep hits.
- **Code evidence**: live-tree grep — 28/30 carry the canonical sentence verbatim on one line; `episode-format-reference.md:44-46` and `step-implementation.md:1038-1039` carry it line-wrapped, so the single-line replace skips them. Both are inside the 30 (narrow phrase is unwrapped). The track's named hand-edit set is a disjoint trio (`review-workflow-pr`, `commit-conventions`, `implementer-rules`).
- **Verdict**: FRAGILE — holds for 28, breaks for 2; the partition criterion (grep-bucket) is the wrong axis (line-wrap is the right one).

#### Violation scenario: §1.5 internally consistent after the flip (→ A2)
- **Invariant claim**: "Subset enumeration is uniform after Track 1" / house-style.md and §1.5 self-consistent.
- **Violation construction**: (1) Start: §1.5 Tier-B row + `conventions.md:568` "The four Tier-B section names …" + governance grep at 570. (2) Step 3 flips the Tier-B row to five and step 4 adds Orientation to the line-570 grep. (3) Line 568 is named in no step and no in-scope note. (4) Violation point: `conventions.md:568` still reads "four Tier-B section names" while the row above it lists five. (5) Consequence: §1.5 self-contradictory; a count-phrase residual not guaranteed caught by the enumeration-scoped acceptance check.
- **Feasibility**: CONSTRUCTIBLE — line 568 is the only `four Tier-B` hit and is unmentioned in `track-1.md`.

#### Assumption test: chat-bucket and hand-edit instructions are disjoint (→ A3)
- **Claim**: 11 chat blurbs take the find/replace pair; separately, 3 grep-miss sites are hand-edited.
- **Stress scenario**: `review-workflow-pr/SKILL.md` appears in both instructions.
- **Code evidence**: it is one of the 11 (`AI-tell subset of` grep) and is line-wrapped (single-line list literal does not match). Reconcilable but not stated as exclusive.
- **Verdict**: HOLDS (presentational ambiguity only).

#### Assumption test: ai-tells catalogue is a four-name closed-set flip site (→ A4)
- **Claim**: "the `ai-tells` catalogue gains an Orientation row."
- **Stress scenario**: locate the four-name closed set / four-count in the catalogue to flip.
- **Code evidence**: `## Catalogue lookups` is a fingerprint-category→`§`-section map; no four-count phrase, no closed-set enumeration; `## Orientation` (too-terse) is not a fingerprint the skill audits.
- **Verdict**: FRAGILE — the file is closer to the "cites individual §-sections, not a flip site" class; the "gains a row" instruction is under-specified.

## Survival summary

- D1 (faithful full sync): survives — A1/A3 strengthen the *partition recipe* (line-wrap vs grep-bucket) and A4 the inventory edge, none overturns the decision.
- D2 (two-tier membership): survives — A2 is a count-drift in the §1.5 surface D2 edits, not a challenge to the tier split itself.
- D3 (generalize Explanatory register): the three reconciliations (line 379, own finding category, Self-check item-8 placement) are each concrete and verified against the live `house-style.md` (379 scoping sentence present; item 8 is "design/ADR only" and lists `explanatory register`; current rule cites `§ Why-before-what`). No finding.
- D5 / D6 (no-staging opt-out, criteria-switch): the three criteria-switch blocks are confirmed at `technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282`, all gated on the workflow-modifying marker; the staged-read block is correctly left alone. Landing-order invariant (step 1 before all; step 2 before step 4) is internally consistent. No finding.
- Acceptance: the broadened governance-grep + Orientation-presence check is sound for closed-set enumerations and is what catches the A1 residual at Phase C; A2 exposes that a bare *count phrase* (line 568) is outside its scope.
