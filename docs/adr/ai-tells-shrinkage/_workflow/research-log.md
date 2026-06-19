# Research Log — Shrink house-style to comprehension-serving rules (ai-tells-shrinkage)

## Initial request

Aim (user framing, 2026-06-19): the project's writing-style focus is partly
wrong. It does not matter to the project whether a document carries signs of
being written by an LLM; what matters is that the text is clear for a human
reader. So remove the parts of the house style that bring no value to a human's
understanding of the text and only serve to hide that an LLM wrote it. Keep the
rules that genuinely aid comprehension.

YTDB-1144 (the em-dash cap reconciliation) is the **motivating instance**, not
the whole scope: the em-dash-per-paragraph cap is a concealment-only rule that
drove four rounds of authoring churn over balanced appositive pairs with no
comprehension payoff. The broadened aim generalizes that observation to the
whole style guide. The YTDB-1144 detail is retained below as motivating
context.

---

Motivating instance — YTDB-1144: "Reconcile the em-dash cap between house-style
and the dsc-ai-tell checker, and run the deterministic check in the authoring
loop" (Bug, Major, dev-workflow,
https://youtrack.jetbrains.com/issue/YTDB-1144).

Verbatim aim from the issue:

> **BLUF:** The em-dash-per-paragraph cap has two definitions that disagree, and
> the deterministic checker holding the looser one does not run in the
> design/track authoring loop. So the LLM `readability-auditor` enforces the
> strict prose version by judgment, samples a few violations per pass, and
> drives multi-round authoring churn over balanced appositive pairs the
> project's own checker would pass.
>
> **Source:** session 2026-06-18, branch `state-ledger-fix`, `/create-plan`
> Step-4b track authoring for YTDB-1140. The track file converged 8 → 3 → 2 → 0
> readability findings across four author rounds; the residue at every round was
> the em-dash cap, dominated by balanced two-dash appositive pairs. Decision
> content was clean throughout (absorption matched 3/3 every round), so all four
> rounds went to one cosmetic punctuation rule.
>
> **The disagreement.** House-style prose (`house-style.md:330,334`, summary at
> `:480`): "At most one em dash per paragraph ... more than one is a finding."
> The deterministic checker (`design-mechanical-checks.py`, `check_dsc_ai_tell`,
> ~lines 212-226): fires on three or more em dashes per paragraph, or on two
> when they are unbalanced (a sentence boundary sits between them); tolerates a
> single balanced parenthetical aside `A — clause — B`. The two rules disagree
> on the two-dash balanced case.
>
> **Why it causes churn.** The `readability-auditor` reads house-style and
> enforces the strict prose rule, flagging balanced two-dash pairs the
> deterministic checker would pass. Em-dash discipline is not a first-class
> mandated auditor axis (`readability-auditor.md:49-56`); it is checked
> opportunistically under "hard-to-read", and an LLM samples salient instances
> rather than counting every paragraph, so violations surface a few per round.
> `design-mechanical-checks.py` is `design.md`-shaped and does not run on track
> files in the Step-4b loop.
>
> **Proposed fix.**
> 1. Reconcile the rule. Relax `house-style.md` § Em-dash discipline (lines 330,
>    334, 480) to match `check_dsc_ai_tell`: a single balanced parenthetical
>    aside is allowed; fire on three or more per paragraph, or on two unbalanced.
>    State the rule once; a byte-source fixture or test pins them in sync.
> 2. Wire the deterministic check into the authoring loops. Run the
>    `dsc-ai-tell` em-dash check on the artifact before the LLM
>    `readability-auditor` in `edit-design` (phase1-creation / phase4-creation)
>    and `create-plan` Step-4b, including on track files (today the checker does
>    not run on them).
> 3. Optional, clarify ownership. Note in `readability-auditor.md` that
>    mechanical, countable house-style rules (em-dash density, line width,
>    banned-vocabulary closed set) belong to the deterministic checker, and the
>    auditor focuses on judgment axes.
>
> **Acceptance criteria.**
> - `house-style.md` and `design-mechanical-checks.py` state the same em-dash
>   rule, pinned in sync by a test or the byte-source fixture.
> - The deterministic em-dash check runs on track files in the Step-4b loop and
>   on `design.md` in `edit-design`, before the `readability-auditor`.
> - A track or design authored with at most one balanced appositive aside per
>   paragraph draws no `readability-auditor` em-dash finding.
>
> Related: YTDB-1140 (the fix whose plan surfaced this).

## Decision Log

- 2026-06-19T09:35:03Z [ctx=safe] Scope reframe: the branch shrinks
  `house-style.md` (and its consumers) to comprehension-serving rules; rules
  whose only effect is to hide LLM authorship are removed. **Why:** the project
  does not value AI-authorship concealment for its own sake; it values clear
  text for human readers, and concealment-only rules cost authoring churn
  (YTDB-1144) with no reader benefit. **Alternatives rejected:** (a) fix only
  YTDB-1144's em-dash cap — too narrow; the same concealment-only class recurs
  across banned-vocabulary, curly-quotes, era-specific tells; (b) keep all rules
  but downgrade their severity — leaves the churn and the prose/checker
  duplication in place.

- 2026-06-19T09:41:24Z [ctx=safe] Decision test confirmed by the user: keep a
  rule iff it would change prose a human reader also finds unclear, imprecise,
  or hard to skim; remove it iff it fires on a human's clear prose purely for
  looking AI-ish. **Why:** gives a single objective discriminator instead of
  per-rule debate. **Alternatives rejected:** keep-all-but-downgrade-severity
  (leaves churn); vocabulary/punctuation-only cut (too narrow — misses copula
  avoidance, era-specific tells).
- 2026-06-19T09:41:24Z [ctx=safe] Borderline-bucket disposition (user delegated
  to the default "keep concision/specificity-forcing rules, drop pure
  fingerprints"). DROP: copula avoidance only (near-pure verb-variation
  fingerprint, negligible comprehension cost). KEEP: negative parallelism,
  roundabout negation, throat-clearing, closing phrases, trailing hedges,
  prompt-restating, filler hedges, generic positive conclusions, persuasive
  authority tropes, superficial -ing, hyphenated-pair overuse, inline-header
  lists — each forces concision or specificity a human reader also wants.
  **Why:** applying the confirmed test, most of the bucket has a real
  comprehension payoff; only copula avoidance is cosmetic.
- 2026-06-19T09:41:24Z [ctx=safe] Final REMOVE set: Banned vocabulary Tier 1-4
  (closed list, incl. era-specific Tier 4); Em-dash discipline;
  Knowledge-cutoff disclaimers; sycophantic openers; Signposting; Copula
  avoidance. Everything else KEEPS. (Curly quotes moved to KEEP — see the
  10:32 gate-A6 resolution below.)
- 2026-06-19T10:04:09Z [ctx=safe] Scope split (user): this branch does the
  shrink (remove the concealment-only rules + update all seven consumers to
  match) **plus** part 3 of the old YTDB-1144 (the ownership note in
  `readability-auditor.md` that mechanical countable rules belong to the
  checker). Part 2 (wire the deterministic `dsc-ai-tell` check into
  `create-plan` Step-4b on track files) is split to a **separate issue,
  YTDB-1148** (Feature, dev-workflow, depends on YTDB-1144). **Why:** part 2 is
  orthogonal mechanical-coverage work whose original em-dash motivation
  evaporated with the em-dash removal; part 3 is a one-paragraph natural fit
  with the shrink. **Alternatives rejected:** pulling part 2 into this branch
  (widens scope without a shared mechanism).
- 2026-06-19T10:04:09Z [ctx=safe] Per-consumer scope resolved (user): sycophantic
  openers and signposting are removed from the **document** style
  (`house-style.md`) but **retained as a chat-only rule** in
  `house-conversation.md`. **Why:** in a terminal reply those openers are
  content-free filler, so the guard is a concision win for the reader, not
  concealment; in a design doc they do not occur, so the doc-side removal is
  harmless. **Alternatives rejected:** global removal (would allow "Great
  question!" / "Let's dive in" to reappear in chat with no comprehension
  benefit). Em-dash, curly quotes, banned-vocabulary list, knowledge-cutoff
  disclaimers, and copula avoidance remain **global** removes (no surface keeps
  them).
- 2026-06-19T09:41:24Z [ctx=safe] YTDB-1144 is superseded by this branch's
  broadened scope (user direction): the em-dash rule is removed outright, so the
  reconcile-and-sync-test work collapses to "delete from prose + checker." Issue
  title and description to be rewritten to the shrink-house-style scope.

- 2026-06-19T10:04:09Z [ctx=safe] Tier + staging confirmed (user, Step 4
  part 1). **Tier = minimal**: Gate 1 = no (settled rule-removal refactor, no
  architecture to diagram, design.md format a poor fit), Gate 2 = single (one
  coherent atomically-mergeable change; splitting would create the prose-vs-
  consumer mismatch being fixed). No design.md, no plan — one self-contained
  track file + phase ledger; Phase-4 verdict folds into the PR description.
  **Staging = §1.7(k) prose-rule opt-out** (edit live, self-application; the
  `create-plan` loop this branch runs does not invoke
  `design-mechanical-checks.py`, so no self-destabilization; matches the
  explanation-style precedent). The opt-out's §1.7(k)-criterion-2 basis is the
  **no-invocation fact**, not a "judgment-layer" reclassification of executable
  code (see the 10:32 gate-A2 resolution): no phase this branch runs executes
  `design-mechanical-checks.py` (`create-plan/SKILL.md` has zero references to
  it; `edit-design` Step 3 runs it but this minimal-tier branch authors no
  `design.md`). **Mandatory
  `/migrate-workflow` stamp-advance at branch end** (live edits to
  `.claude/agents/**` + `.claude/workflow/**` move the §1.6(b) stamp base).
  Adversarial-gate lens (D16): **Workflow machinery**. Ledger `s17` =
  opt-out token. **Why:** a prose-rule branch gains nothing from staging and
  forfeits self-application. **Alternatives rejected:** full staging
  (isolation this change does not need); full tier (degenerate design.md).

- 2026-06-19T10:22:16Z [ctx=safe] Banned-vocabulary removal preserves the
  genuine precision intent. Remove the closed Tier 1-4 list outright (it is the
  concealment mechanism — an AI-frequency word set), but fold the
  comprehension-bearing intent of a few Tier-2 entries into the kept Plain-language
  section as "prefer the precise concrete word" guidance with examples
  (`robust` → "tolerant of <X>" naming the X; `comprehensive` → "covers X, Y,
  Z"). **Why:** the closed list is what hides LLM authorship and drives churn;
  the "name the specific quality" intent is real comprehension and already
  overlaps Plain-language's "prefer the common/precise word." **Alternatives
  rejected:** keep a shortened Tier-2-only list (still a closed AI-frequency
  list, same churn class); drop the precision intent entirely (loses real
  comprehension value). Resolves the earlier open question on whether dropping
  the list loses value.

### Gate iteration-1 resolutions (2026-06-19T10:32:17Z [ctx=info])

- **A1 — exhaustive consumer inventory is the track acceptance contract.**
  Minimal tier has no plan-level structural review, so the consumer inventory
  must be load-bearing here, not a Surprise. The blast radius is far larger than
  the seven first listed: the **"six AI-tell subset section slugs" enumeration
  is replicated verbatim across ~40 files** (the live grep counts ~20
  `.claude/agents/*.md` review agents + ~20 `.claude/workflow/**`
  prompts/skills; treat the count as "every grep hit", not a frozen list —
  gate-A7), each hard-coding
  `## Banned vocabulary` … `### Em-dash discipline`. Removing those two sections
  makes every copy a phantom reference. The **acceptance contract** the single
  track is checked against: run the §1.5 rename-safety grep
  (`grep -rnE 'Banned vocabulary|Banned sentence patterns|Banned analysis patterns|Em-dash discipline|## Orientation|## Plain language|§ Orientation|§ Plain language' .claude/ CLAUDE.md`,
  extended with `-i 'sycophantic|signposting|copula|knowledge.cutoff'`) and, for
  every hit, either edit it to match the post-shrink rule set or record it as
  confirm-benign. The load-bearing inventory: (1) **canonical source** —
  `house-style.md` (remove the sections), `conventions.md §1.5` Tier-B table at
  `:621` + the rename-safety grep at `:626`; (2) **30 replicated bootstrap-slug
  files** (drop the two removed slugs from the enumeration); (3) **checker +
  tests + fixture** — `design-mechanical-checks.py`, `test_dsc_ai_tell.py`,
  `dsc-ai-tell-fixture.md`; (4) **always-loaded skill description** —
  `ai-tells/SKILL.md:3` (A4); (5) **hook** — `house-style-write-reminder.sh`;
  (6) **chat style** — `house-conversation.md`; (7) **named prose consumers** —
  `design-document-rules.md`, `design-author.md`, `readability-auditor.md`,
  `review-workflow-writing-style.md`, the cold-read prompt
  `prompts/design-review.md`, `readability-feedback/SKILL.md`, root `CLAUDE.md`.
  **Why:** a single missed reference ships a phantom cross-reference on a tier
  with no structural-review safety net. mcp-steroid unreachable → the grep can
  miss a paraphrase reference, so the contract names the grep AND a manual
  paraphrase scan of the seven named consumers.
- **A3 — what the Tier-B (code-comment) + chat AI-tell subset becomes.** The
  §1.5 Tier-B subset (`conventions.md:621`) and the chat subset
  (`house-conversation.md`) both enumerate the six sections. After removal the
  subset is **four sections** — `§ Orientation`, `§ Plain language`,
  `§ Banned sentence patterns`, `§ Banned analysis patterns` (the latter two
  survive, minus their removed bullets) — dropping `§ Banned vocabulary` and
  `§ Em-dash discipline`. Update `conventions.md:621`, the 30 replicated slug
  lines, and `house-conversation.md` in the same commit (the rename-safety
  discipline). **Chat-only retention mechanism:** since the sycophantic-openers
  and signposting bullets leave `house-style.md`'s doc surface, the chat-only
  guard cannot point at them; `house-conversation.md` carries an **inline**
  "chat replies: no sycophantic openers, no signposting" rule instead. **Why:**
  the doc-removed/chat-retained split (10:04) needs a carrier once the bullets
  leave the referenced sections.
- **A4 — the always-loaded `ai-tells/SKILL.md:3` description is in scope.** Its
  `description` field is loaded into every session's skill list and hard-codes
  removed tells ("delve", "foster", "em dash overuse", "knowledge-cutoff
  disclaimers"). Drop the removed-tell names from the description; keep the kept
  ones (negative parallelism "It's not X, it's Y", Title Case headings). **Why:**
  a stale always-on surface both misadvertises and costs context budget (the
  Workflow-machinery lens).
- **A5 — the banned-vocab fold is a move, not a copy.** The Tier-2 precision
  examples (`robust → "tolerant of X"`, `comprehensive → "covers X, Y, Z"`) at
  `house-style.md:110` are *moved* into `§ Plain language`'s "prefer the precise
  word" move, not copied (Tier 2 is deleted with the rest of Banned
  vocabulary). The `§ Plain language` "**Reconciliation with § Banned
  vocabulary**" subsection at `:92` is **removed** (its only purpose was
  dividing labour with the deleted section), not reworded.
- **A6 — curly quotes moved from REMOVE to KEEP.** Its rationale
  (`house-style.md:348-349`) is ecosystem/tooling consistency — straight quotes
  keep grep and code-fence matching working — not AI-authorship concealment. By
  the confirmed decision test it sits outside the concealment-vs-comprehension
  axis the branch is shrinking (it is a tooling-hygiene rule), so the shrink
  leaves it untouched. **Why:** removing it would forfeit real diff/grep hygiene
  with no concealment payoff; keeping it is honest to the decision test.
  **Alternatives rejected:** remove it on a "low-value/low-churn" basis (would
  misclassify a tooling rule as concealment). Knowledge-cutoff disclaimers stay
  REMOVE (unambiguously concealment, no reader or tooling value).

## Surprises & Discoveries

- 2026-06-19T09:35:03Z [ctx=safe] Full house-style.md map (492 lines, 5 `##`
  rule families + Self-check). Proposed classification by the decision test
  "would this rule fire on a human's clear prose purely for looking AI-ish?":
  - KEEP (comprehension): BLUF lead; Voice and tone; Orientation; Plain
    language; Passive voice; Nominalization/placeholder; Broken grammar around
    identifiers; Hedge stacking; Vague attribution; False ranges; Elegant
    variation; Mechanism traces and inline citations; Excessive boldface; and
    the Structural + Document-shape families (section cap, bullet discipline,
    heading hierarchy, fragmented headers, Overview concept-first, Audience-fit,
    Glossary-introduction, Why-before-what, Navigability, Edge-cases,
    References-footer, sibling consolidation, Explanatory register).
  - REMOVE (concealment-only): Banned vocabulary closed list Tier 1-4
    (incl. era-specific Tier 4); Em-dash discipline; Curly quotes;
    Knowledge-cutoff disclaimers; sycophantic openers; Signposting fingerprints.
  - BORDERLINE (dual-purpose — needs user call): negative parallelism,
    roundabout negation, throat-clearing, closing phrases, trailing hedges,
    prompt-restating, copula avoidance, generic positive conclusions, persuasive
    authority tropes, superficial -ing, filler hedges, hyphenated-pair overuse,
    inline-header lists. Each has both a concision/comprehension justification
    and an AI-tell justification.
- 2026-06-19T09:35:03Z [ctx=safe] Blast radius (consumers that must change in
  lockstep — house-style.md:20 names four, plus three more): (1) `ai-tells`
  skill; (2) cold-read prompt `prompts/design-review.md`; (3) `dsc-ai-tell`
  regex set in `design-mechanical-checks.py` (`check_dsc_ai_tell` implements 11
  patterns — most of the REMOVE/BORDERLINE bucket: Tier-1 vocab, negative
  parallelism, signposting, copula, authority tropes, hyphenated-pair, em-dash;
  removing rules shrinks the checker + its tests/fixtures); (4)
  `house-conversation.md` (the "six AI-tell sections"); (5)
  `readability-auditor.md` (prose AI-tell axis); (6) `conventions.md §1.5` tier
  mapping; (7) CLAUDE.md references + the house-style Self-check list itself.
- 2026-06-19T09:35:03Z [ctx=safe] house-style.md:393-399 has a duplicate
  `## WAL replay` heading pair inside the Fragmented-headers example (one is the
  Before, one the After). It is example content, not a real duplicate section,
  but worth confirming during edits so a section-removal pass does not trip on
  it.
- 2026-06-19T10:22:16Z [ctx=safe] In-file interdependencies the removal must
  fix (Phase-A edit-coordination, not decisions): (a) Plain-language has a
  "**Reconciliation with § Banned vocabulary**" subsection (house-style.md:92)
  that references the removed section — must be reworded once Banned vocabulary
  goes, keeping the precision intent per the Decision Log. (b) The Self-check
  list (house-style.md:479-484) cites removed rules: item 1 Banned vocabulary,
  item 2 em dashes, item 6 curly quotes, and the analysis-pattern item names
  copula avoidance — these entries are removed/renumbered. (c) Tier-4
  era-specific cross-references "§ Punctuation and typography" (em dashes) and
  "§ Banned sentence patterns"; the whole Banned-vocabulary section (incl. Tier
  4) is removed. (d) `house-style.md:20` enumerates the four consumers and
  `house-style.md:6` says "Every rule below applies to every paragraph" — the
  consumer list and framing stay, but the removed sections drop out. (e) The
  `check_dsc_ai_tell` docstring (design-mechanical-checks.py:1837-1854)
  enumerates "Eleven patterns"; removing em-dash, Tier-1 vocab, signposting,
  copula, and (if regex-backed) others shrinks that count and the per-pattern
  scan code + `test_dsc_ai_tell.py` cases + the fixture.

- 2026-06-19T09:28:56Z [ctx=safe] The disagreement is confirmed exactly as the
  issue states. `house-style.md:330` "At most one em dash per paragraph.",
  `:334` "more than one is a finding.", `:480` self-check "Count per paragraph.
  More than one is a finding." vs `check_dsc_ai_tell` (lines 2053-2061): fires
  on `em_dash_count > 2`, or `== 2` only when the middle segment after splitting
  on `—` contains `.`/`!`/`?`. A balanced two-dash aside passes the checker,
  fails the prose. The checker already holds the lenient rule the fix wants — so
  part 1's *script-side* em-dash logic likely needs no change, only the prose
  and a sync mechanism.
- 2026-06-19T09:28:56Z [ctx=safe] `edit-design/SKILL.md` already runs
  `design-mechanical-checks.py` as its Step 3, before the cold-read /
  readability-auditor (SKILL.md:400-404, "Run mechanical checks ... before the
  cold read"). So for `design.md`, the deterministic em-dash check is already
  wired in front of the auditor. `create-plan/SKILL.md` has **zero** references
  to `design-mechanical-checks` — the Step-4b track loop never runs it. The real
  wiring gap in part 2 is the Step-4b loop + track-file support, not edit-design.
- 2026-06-19T09:28:56Z [ctx=safe] The checker is `design.md`-shaped but already
  partly track-aware: `check_dsc_ai_tell` accepts `sections=None` (track files
  pass no Overview section, so the inflated-abstraction Overview skip stays off),
  and the per-paragraph em-dash scan is section-agnostic. So running it on track
  files looks feasible without deep checker surgery; the open piece is how
  Step-4b drives it per `plan/track-N.md`.
- 2026-06-19T09:28:56Z [ctx=safe] No test pins the prose against the checker
  today. `test_dsc_ai_tell.py` exercises the checker behavior; nothing asserts
  `house-style.md`'s stated rule matches it. Acceptance criterion 1's "pinned in
  sync" mechanism does not exist yet.

## Open Questions

No load-bearing Phase-0/1 decisions remain open; all are resolved into the
Decision Log. Prior entries (all now resolved or moot) and their disposition:

- (resolved) Decision test — confirmed by the user; see Decision Log.
- (resolved) BORDERLINE-bucket disposition — confirmed (drop copula avoidance,
  keep the rest); see Decision Log.
- (resolved) Per-consumer scope — sycophantic openers + signposting are
  doc-removed, chat-retained in `house-conversation.md`; all other removes
  global. See Decision Log.
- (resolved) Subsume YTDB-1144 — yes, superseded; issue rewritten. See Decision
  Log.
- (resolved) Plain-language coverage of the dropped banned-vocab subset —
  fold the Tier-2 precision intent into Plain-language; see Decision Log.
- (resolved) §1.7 staging vs opt-out — §1.7(k) prose-rule opt-out; see Decision
  Log.
- (moot) "Pin prose and regex in sync" — superseded: the em-dash rule is removed
  from both prose and checker, so there is no reconcile-and-sync target left.
- (moot, split to YTDB-1148) Where the deterministic check slots into the
  Step-4b loop, and the edit-design per-kind check-set scope — the Step-4b
  wiring is YTDB-1148, out of scope here.
- (resolved) Part 3 (ownership note in `readability-auditor.md`) — in scope for
  this branch; see Decision Log scope-split entry.

Decomposition-time edit-coordination items (not decisions — implementation
detail for Phase A, recorded so the track author has them): see the in-file
interdependencies entry under Surprises & Discoveries.

## Baseline and re-validation

This is a **workflow-modifying** branch (it edits `.claude/output-styles/`,
`.claude/scripts/`, `.claude/skills/`, `.claude/agents/`, `.claude/workflow/`,
and root `CLAUDE.md`). Staging decision: **§1.7(k) prose-rule opt-out** (edit
live; the in-branch consumers are judgment-layer). Re-validation anchor on
rebase: the develop tip the branch forked from; fill the fork-point SHA below.
**Mandatory stamp-advance:** after the branch-final commit touching the §1.6(b)
SHA-computation pathspec (`.claude/workflow`, `.claude/skills`,
`.claude/agents`), run `/migrate-workflow` to advance every artifact's stamp to
HEAD and re-arm the drift gate (`§1.7(k)` Mandatory stamp-advance). Suppress the
drift gate on intervening sessions until that lands.

## Adversarial gate record

### Adversarial review of this log (2026-06-19T10:28:00Z) — NEEDS REVISION: 0 blocker, 4 should-fix, 2 suggestion
Review file: `_workflow/reviews/research-log-adversarial-iter1.md`. Findings
A1-A4 (should-fix) on consumer-inventory completeness, the opt-out basis, the
Tier-B/chat subset, and the always-loaded `ai-tells` description; A5-A6
(suggestion) on the banned-vocab fold and the curly-quotes classification.

### Adversarial review of this log (2026-06-19T10:36:06Z) — PASS
Review file: `_workflow/reviews/research-log-adversarial-iter2.md`. All six
iteration-1 findings VERIFIED (resolved). One new suggestion A7 (the replicated-
slug count understated ~40; the edit-or-confirm-every-grep-hit contract already
covers it) — applied: the count is now "every grep hit (~40 files)". No blockers,
no open should-fix; gate clears.
