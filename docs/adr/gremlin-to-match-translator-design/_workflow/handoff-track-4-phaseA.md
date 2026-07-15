# Handoff: Phase A (Track 4) — execute the user-approved track split (inline replan)

**Paused:** 2026-07-15
**Phase:** A (Track 4 review + decomposition)
**Context level at pause:** info (~37%) — deferring a large restructure (author a track, renumber two, rewrite the checklist) that would run precise renumbering work well into the warning band; fresh context is strictly better.
**Branch:** gremlin-to-match-translator-design
**HEAD:** 5956b138fd "YTDB-558: Apply pre-flight amendments before Track 4" (the review-hardening + this handoff land in the next commit)
**Unpushed:** 2 commits (pre-flight amendments + this handoff commit) — push held per the user's standing "ask before push" preference.

## Durable artifacts on disk
- `plan/track-4.md` — hardened by the Phase A Technical (T1–T8) and Risk (R1–R6) reviews. `## Outcomes & Retrospective` records both as PASS, plus the Adversarial run and the split trigger. `## Decision Log` / `## Surprises & Discoveries` carry the RID-inline and polymorphic-`hasLabel` decisions + two `design.md` Phase-4 reconciliation flags.
- `plan/track-4/reviews/technical-iter1.md`, `technical-gate-verification-iter2.md`, `risk-iter1.md`, `risk-gate-verification-iter2.md`, `adversarial-iter1.md` — full review audit (findings + evidence base). **Read `adversarial-iter1.md` `## Findings` A1–A7 before restructuring.**
- `implementation-plan.md` — pre-flight ADJUST strategy-refresh line under Track 3 + the T8 scrub of the stale `HasLabelStep`/`HasIdStep` triad.

## Pending decision
**Already resolved — no user prompt needed.** The user approved (2026-07-15) splitting Track 4 on adversarial finding A1 (realized footprint ~29–38 files, over the ~25 split ceiling, with a clean seam). The pending *action* is to execute the inline replan. Re-confirm in one line, then proceed.

## Verbatim re-present text
> Track 4 Phase A ran all three reviews. Technical PASSed (2 blockers fixed: `has`/`hasLabel`/`hasId` collapse to one `HasStepRecogniser`) and Risk PASSed (6 findings). The adversarial review found the track is ~29–38 files — over the ~25 split ceiling — with a clean seam, and you approved splitting it. This session deferred the restructure to fresh context. I'll now execute the split as an inline replan.

## The split to execute (inline-replanning.md §Process from step 3)
- **Track 4 → predicate surface** (keeps `track-4.md`): full `GremlinPredicateAdapter` (the `P`/`Contains`/composite set, NULL/absent/singleton rules, `between`/`inside`/`outside`), the single `HasStepRecogniser` (has/hasLabel/hasId via `HasContainer` key), `has(key)` presence via `TraversalFilterStepRecogniser` (→ `IS DEFINED`), and `Text`/`TextP` (D-TEXT-OPS: `SQLEndsWithCondition`, `SQLMatchesCondition` find-mode, `SQLContainsTextCondition` collate). Trim out steps 5–7 and their Interfaces/Validation. **Case 3** (mid-execution track) per §Updating.
- **New Track 5 → logical filters + D5** (new `track-5.md`, **case 1**): `AndStepRecogniser`, `OrStepRecogniser`, a **single `NotStepRecogniser`** (see A2 seam), `WhereTraversalStepRecogniser`, `WherePredicateStepRecogniser`, `SubTraversalPredicateAdapter` + the sub-walker, and `GremlinPlanCache` (D5). **Depends on Track 4.**
- **Renumber:** old Track 5 (Result shaping) → **Track 6**; old Track 6 (Advanced patterns) → **Track 7**. `git mv track-5.md track-6.md` and `track-6.md track-7.md`; update each file's `# Track N:` heading, self-references, and `**Depends on:**` lines; rewrite the plan `## Checklist` and the `## Implementation state` table/prose. New dependency chain: 4 → 5(new) → 6(old 5) → 7(old 6). Old Track 5's `Depends on: Track 4` becomes `Depends on: Track 4 + new Track 5` (it needs the predicate algebra AND, where it reuses logical filters, Track 5).

### A2 seam resolution (critical — do not put a NotStep recogniser in Track 4)
`hasNot(key)` desugars to `NotStep(__.values(key))` and logical `not(...)` is also a `NotStep`; the fork's `NotStep` is `final`, so exact-class dispatch can't separate them and two registered recognisers = a `Map.of` duplicate-key `Error` at class-init (uncaught → every Gremlin compile fails). Resolution: **all `NotStep` handling lives in Track 5** as one recogniser registered under `NotStep.class` that branches values-child (presence `hasNot(key)` → `isNotDefined`) FIRST, then `hasEdgeHops` (logical not). So Track 4 keeps only `has(key)` presence (`TraversalFilterStep`, a distinct class); `hasNot(key)` moves to Track 5. Note the values-child-first ordering (`PropertiesStep` has no recogniser until the result-shaping track).

### Adversarial findings to distribute into the split (from adversarial-iter1.md)
- **Track 5** (logical/D5): A2 (single `NotStep` recogniser, above), A3 (D5 key = post-walk generic statement; `eq(null)`→`IS NULL` must not share an entry with `eq(v)`→`?`), A4 (sub-context contract: alias minting delegates to the parent `AliasSequence`; `pinBoundary`/`setSingleReturnColumn` swallowed; `putAliasFilter` captured per-combinator; pattern contributions forward only for AND edge-bearing children, captured for NOT — add an `and(out("a"),out("b"))` acceptance case), A5 (edge-bearing NOT needs a `MatchPatternBuilder`/assembler extension producing a detached `SQLMatchExpression`, or direct AST assembly with an explicit exemption — add `MatchPatternBuilder` to In-scope), A6 (**correct the over-broad NOT decline**: `manageNotPatterns`'s second throw fires on `exp.getOrigin().getFilter() != null` — the NOT expression's own origin inline filter, translator-controlled and leave-able-empty — NOT on `aliasFilters`; decline only when the NOT origin alias is absent from the positive pattern, so `g.V().has("age",30).not(out("knows"))` still translates), A7 (no-mutation-on-decline: HEAD discards the whole context on DECLINE, so rescope step 6's test to the sub-walk capture boundary, and flag the plan invariant for Phase 4). Also R3 (inline RID + cache-bypass for RID-bearing shapes), R4 (NOT throw → native decline via the eager-build net), R6 (D5 determinism tests).
- **Track 4** (predicate): the Technical (T1–T8) + Risk (R1, R2, R5) fixes already applied to track-4.md carry over unchanged.

### Inline-replan mechanics
1. Follow `inline-replanning.md` §Process step 3 (Propose) → §Updating plan and track files (case 1 for new Track 5, case 3 for Track 4, case 2 for renumbered 6/7). PSI-verify any new class name (IDE was mid-index this session — PSI timing out; `steroid_list_projects` first, else grep + caveat).
2. Step 4: run the structural-review preview sub-agent on the revised 7-track plan (fail-fast, cap 3).
3. Step 6 on PASS: append `--phase 0 --substate steps-partial` to the ledger (State-0 reset), then ONE commit `Inline replan: split Track 4 into predicate + logical/D5 tracks` staging the ledger + plan + all touched `track-*.md`, then push (ask first per user pref), then END the session. Next `/execute-tracks` enters State 0 and re-validates the 7-track plan.

## Resume notes
- **Do NOT redo:** the Technical + Risk reviews (both PASSed, marked in Outcomes) and the Adversarial review (ran; findings in `adversarial-iter1.md`). Do NOT re-run Phase A reviews for the whole Track 4 — the split supersedes them, and the State-0 + per-track Phase-A re-review after the replan re-validates the trimmed tracks.
- **On resume:** re-confirm the split in one line, then execute the inline replan above. Do NOT decompose Track 4 as-is.
- **Deferred workflow drift** (task #1): 5 workflow-format commits since stamp base d2dfcc2 remain un-migrated (`/migrate-workflow`). Independent of this handoff.
