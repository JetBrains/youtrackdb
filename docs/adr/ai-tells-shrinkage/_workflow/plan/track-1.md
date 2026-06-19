<!-- workflow-sha: c99af024a00cbe1e4741d4d88e600b6f007c9199 -->
# Track 1: Shrink house-style to comprehension-serving rules

## Purpose / Big Picture

The project's writing rules keep only what helps a human reader; the rules whose
only effect is to hide that an LLM wrote the text come out, along with the
machinery that enforced them.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

The house style (`.claude/output-styles/house-style.md`) currently mixes two
kinds of rule. Some rules make prose clearer for a human: lead with the claim,
use the common word, name the actor, do not stack hedges. Others exist only to
make machine-generated text look hand-written. Six rules fall in the second
kind, the REMOVE set:

- the closed list of words that appear at AI-anomalous frequency (banned
  vocabulary);
- the cap on em dashes per paragraph;
- the ban on sycophantic openers ("Great question!");
- the ban on signposting ("Let's dive in");
- the ban on knowledge-cutoff disclaimers;
- the ban on "serves as" for "is" (copula avoidance).

The second kind costs authoring churn with no payoff to a reader. The motivating
instance (YTDB-1144) drove four rounds of authoring rework over balanced em-dash
pairs a human reads without noticing. This track removes the concealment-only
rules from
`house-style.md`, folds the one comprehension-bearing fragment they carried
(name the precise quality, not a vague adjective) into the rule that keeps it,
and updates every consumer of those rules in lockstep so no file is left
pointing at a section that no longer exists.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-06-19T13:23Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-19T13:45Z [ctx=safe] Step 1 complete (commit c24f222228)
- [x] 2026-06-19T14:10Z [ctx=safe] Step 2 complete (commit f69062032c)
- [x] 2026-06-19T14:10Z [ctx=safe] Step implementation complete (Phase B)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->
- The shrunk rule set is not reflected in deliverables outside this track's
  scope (the `.claude/` + root `CLAUDE.md` invariant). The workflow-book
  deliverable on PR #1151 (`docs/workflow-book/chapters/16-house-style-self-improvement.md`
  and `workflow-book-builder/prompts/copy-editor.md`, whose copy-editor prompt
  still tells agents to enforce the banned-vocabulary list, em-dash discipline,
  and the signposting ban) and several historical ADRs
  (`docs/adr/ytdb-836-house-style/`, `docs/adr/mid-level-english/adr.md`,
  `docs/adr/ytdb-899-structured-field-carveout/design-final.md`) still name the
  removed rules. These are benign per DR7 (they sit outside the in-scope
  invariant — historical records or a separate branch's surface), so this track
  left them untouched. Reflecting the shrunk rules in the teaching book and the
  book-builder machinery is a separate follow-up against the workflow-book
  branch, not this track. See Episodes §Step 2.

## Decision Log
<!-- The track-canonical live decision carrier (D7). Phase 1 seeds the full
inline Decision Records this track owns (full four-bullet form below); the
section then continues as the execution-time continuous log. Seeded from the
research log (minimal tier — no design.md). One block per decision. -->

### DR1 — the decision test for which rules stay

Keep a writing rule if and only if it would change prose a human reader also
finds unclear, imprecise, or hard to skim. Remove it if it fires on a human's
clear prose only because the prose looks AI-ish. The user confirmed this test;
it gives one objective discriminator instead of a per-rule debate.

- **Alternatives considered:** (a) fix only YTDB-1144's em-dash cap, leaving the
  rest of the style guide untouched; (b) keep every rule but lower the severity
  of the concealment-only ones.
- **Rationale:** (a) is too narrow — the same concealment-only class recurs
  across the banned-vocabulary list, the curly-quote rule, and the era-specific
  tells, so fixing one rule leaves the recurring churn in place. (b) leaves both
  the authoring churn and the prose-versus-checker duplication standing. A single
  test applied uniformly is cheaper to reason about and resolves the borderline
  cases without further debate.
- **Risks / caveats:** the test has a judgment edge for dual-purpose rules (a
  rule that both forces concision and reads as an AI tell). DR2 resolves that
  edge by recording the per-rule disposition explicitly rather than leaving it to
  re-derivation.
- **Implemented in:** this track — every keep/remove call below cites this test.

### DR2 — the REMOVE set and the KEEP set

Remove from `house-style.md`: the whole `## Banned vocabulary` section (the
closed Tier 1-4 word list, including the era-specific Tier 4); `### Em-dash
discipline`; the knowledge-cutoff-disclaimer bullet under `§ Banned sentence
patterns`; the sycophantic-openers bullet under `§ Banned sentence patterns`;
the `### Signposting` subsection under `§ Banned analysis patterns`; and the
`### Copula avoidance` subsection under `§ Banned analysis patterns`. Keep
everything else. The kept set is the concision- and specificity-forcing sentence
rules (negative parallelism, roundabout negation, and the hedge, attribution,
and run-on rules — about a dozen, each forcing a reader-facing economy) plus the
structural and document-shape families (section caps, bullet discipline, heading
hierarchy, glossary introduction). The point is that most of the borderline
bucket stays; only the six rules above leave.

- **Alternatives considered:** removing only the vocabulary list and the em-dash
  cap (the punctuation-and-word subset); removing the whole borderline bucket of
  dual-purpose sentence rules.
- **Rationale:** the borderline bucket was classified one rule at a time against
  the DR1 test. Most of it has a real comprehension payoff — each forces concision
  or specificity a human reader also wants — so it stays. Only copula avoidance
  ("serves as" for "is") is a near-pure verb-variation fingerprint with negligible
  comprehension cost, so it goes. Cutting only the vocabulary list and the
  em-dash cap would miss copula avoidance, signposting, sycophantic openers, and
  the knowledge-cutoff disclaimer, all of which are concealment-only by the same
  test.
- **Risks / caveats:** `§ Banned sentence patterns` and `§ Banned analysis
  patterns` survive but lose individual bullets/subsections, so the removal is a
  surgical edit inside two kept sections, not a whole-section delete. The Self-check
  list and the in-file cross-references that name the removed items must be
  re-pointed in the same edit (see Plan of Work).
- **Implemented in:** this track — step that edits `house-style.md`.

### DR3 — sycophantic openers and signposting are chat-only after removal

Sycophantic openers ("Great question!", "I'd be happy to help") and signposting
("Let's dive in", "Here's what you need to know") are removed from the document
style but retained as a chat-only rule in `house-conversation.md`. In a terminal
reply those openers are content-free filler, so cutting them is a concision win
for the reader; in a design document they do not occur, so the doc-side removal
is harmless.

- **Alternatives considered:** global removal (drop the rule from both the
  document style and the chat style); leave both in `house-style.md` and have the
  chat style keep pointing at them.
- **Rationale:** global removal would let "Great question!" / "Let's dive in"
  reappear in terminal replies with no comprehension benefit, so the chat surface
  keeps the guard. Because the two bullets leave `house-style.md`'s prose surface,
  the chat style can no longer point at them by section name; `house-conversation.md`
  carries an inline "chat replies: no sycophantic openers, no signposting" rule
  instead (it already states the no-preamble rule in prose at the Response-shape
  list, which is the carrier).
- **Risks / caveats:** the chat-only guard must be a literal inline rule in
  `house-conversation.md`, not a cross-reference to a `house-style.md` section
  that no longer holds the bullets. A reviewer must confirm the carrier survives.
- **Implemented in:** this track — step that edits `house-conversation.md`.

### DR4 — edit the rules live, not staged (§1.7(k) prose-rule opt-out)

This branch edits `house-style.md`, `conventions.md`, the review agents, the
skills, the checker, and root `CLAUDE.md` live (in place), not in a staged copy.
The basis is the no-invocation fact: no phase this branch runs executes the
deterministic checker. `create-plan/SKILL.md` (the loop this branch runs to author
this track) has zero references to `design-mechanical-checks.py`, and this
minimal-tier branch authors no `design.md`, so `edit-design` Step 3 (the only
phase that runs the checker) never fires here. With nothing executing the checker
mid-branch, editing it live cannot destabilize the running workflow, and the
shrink gets to apply to its own authoring (self-application).

- **Alternatives considered:** full §1.7 staging (edit staged copies under a
  staging prefix, promote at branch end); full tier with a `design.md`.
- **Rationale:** staging buys isolation this change does not need — no running
  phase reads the edited files in a way that could break. Forfeiting
  self-application (writing this track under the old, churning rules) would be a
  pointless cost. The §1.7(k) prose-rule opt-out is the standing mechanism for
  exactly this case and matches the explanation-style branch precedent.
- **Risks / caveats:** the live edits trip the workflow drift gate. Line 1 of
  each `_workflow/` artifact carries a workflow-sha stamp recording the
  workflow-format commit it was written against; the drift gate compares that
  stamp to HEAD at session start and routes to `/migrate-workflow` when they
  diverge. Live edits to `.claude/workflow`, `.claude/skills`, and
  `.claude/agents` advance HEAD past the stamp (the §1.6(b) SHA-computation
  pathspec), so the gate would fire on the branch's own edits. A mandatory
  `/migrate-workflow` run after the branch-final commit re-stamps every artifact
  to HEAD, which clears the gate; the drift gate is suppressed on intervening
  sessions until that run lands.
- **Implemented in:** this track (live edits) plus the `/migrate-workflow`
  stamp-advance recorded under Context and Orientation.

### DR5 — curly quotes stay (tooling hygiene, not concealment)

The `### Curly quotes` rule stays in `house-style.md`. Its rationale is
surrounding-tooling consistency: straight quotes keep grep, code-fence
matching, and diffs working. By the DR1 test it sits outside the
concealment-versus-comprehension axis this track is shrinking — it is a
tooling-hygiene rule, not an AI-authorship fingerprint. Knowledge-cutoff
disclaimers, by contrast, stay in the REMOVE set: they are unambiguously
concealment with no reader or tooling value.

- **Alternatives considered:** remove curly quotes on a "low-value, low-churn"
  basis alongside the concealment rules.
- **Rationale:** removing it would forfeit real diff and grep hygiene with no
  concealment payoff, which misclassifies a tooling rule as a concealment rule.
  Keeping it is honest to the DR1 test.
- **Risks / caveats:** none — the rule is left untouched, so no consumer of it
  changes.
- **Implemented in:** this track — no edit (recorded so the boundary is explicit).

### DR6 — fold the banned-vocabulary precision intent into Plain language

The closed Tier 1-4 list is removed outright; it is the concealment mechanism, an
AI-frequency word set. The one comprehension-bearing fragment it carried —
prefer a precise concrete word over a vague adjective — moves into `§ Plain
language`'s "prefer the precise word" move as worked examples (`robust` →
"tolerant of <X>" naming the X; `comprehensive` → "covers X, Y, Z"). This is a
move, not a copy: the examples leave the deleted Tier-2 list and live only in
`§ Plain language`. The `§ Plain language` "**Reconciliation with § Banned
vocabulary**" subsection is removed, because its only purpose was dividing labour
with the section being deleted.

- **Alternatives considered:** keep a shortened Tier-2-only vocabulary list; drop
  the precision intent entirely with the rest of the list.
- **Rationale:** a shortened list is still a closed AI-frequency list and carries
  the same churn class. Dropping the precision intent loses real comprehension
  value, because "name the specific quality" already overlaps `§ Plain language`'s
  "prefer the common or precise word" move. Folding the intent keeps the
  comprehension value while removing the closed list.
- **Risks / caveats:** the reconciliation subsection and the Self-check item that
  names banned vocabulary must be removed in the same edit, or `§ Plain language`
  will reference a section that no longer exists.
- **Implemented in:** this track — step that edits `house-style.md`.

### DR7 — the exhaustive consumer-coverage acceptance contract

The consumer inventory is the only safety net against a phantom cross-reference
shipping. Minimal tier has no plan-level structural review, so nothing else
catches a file left pointing at a removed section. The acceptance gate: run the
§1.5 rename-safety grep, extended with the sycophantic, signposting, copula, and
knowledge-cutoff names, and for every hit (~47 files: ~21 review agents, ~21
workflow prompts and rule files, ~5 skills) either edit it to the post-shrink
rule set or record it as confirm-benign. Then run a manual paraphrase scan of the
named prose consumers, because grep cannot catch a reference phrased without the
section name.

- **Alternatives considered:** rely on per-file judgment during the edit with no
  written contract.
- **Rationale:** on a tier with no structural-review safety net, an
  un-enumerated inventory ships a phantom reference on the first missed file.
  Naming the grep and the manual scan as the acceptance contract makes the
  completeness check reproducible by a reviewer who did not do the edit.
- **Risks / caveats:** mcp-steroid is unreachable this session, so the inventory
  rests on grep, which can miss a paraphrase reference; the manual scan of the
  named prose consumers is the mitigation.
- **Implemented in:** this track — the §1.5 grep and the named-consumer scan are
  the Plan-of-Work move 7 completeness check.

### DR8 — the post-shrink Tier-B and chat AI-tell subset is four sections

After removal the AI-tell subset names four sections — `§ Orientation`, `§ Plain
language`, `§ Banned sentence patterns`, `§ Banned analysis patterns` (the last
two survive minus their removed bullets). `§ Banned vocabulary` and `§ Em-dash
discipline` drop. Update `conventions.md §1.5`, the ~47 replicated bootstrap-slug
lines, and `house-conversation.md` in the same edit (the rename-safety
discipline). The chat-only sycophantic-openers and signposting guard is carried
as an inline rule in `house-conversation.md`, because the bullets it would point
at leave `house-style.md`'s prose surface (DR3).

- **Alternatives considered:** keep `§ Banned vocabulary` and `§ Em-dash
  discipline` in the code-comment (Tier-B) tier only, dropping them from the
  document tier alone.
- **Rationale:** the two removed rules are concealment-only at every scale by the
  DR1 test, so a code-comment carve-out would keep the churn class alive in
  Java/Kotlin rationale comments for no reader benefit. The subset is the same
  four sections on both the Tier-B and chat surfaces.
- **Risks / caveats:** the inline chat carrier must be literal text in
  `house-conversation.md`, not a cross-reference to a removed bullet, or the chat
  guard dangles.
- **Implemented in:** this track — see the Invariants on the §1.5 subset and the
  chat carrier, and Plan-of-Work moves 2, 3, and 5.

### DR9 — the always-loaded `ai-tells/SKILL.md:3` description is in scope

The `description` front-matter field at `skills/ai-tells/SKILL.md:3` is loaded
into every session's skill list and hard-codes removed tells by name ("delve",
"foster", "em dash overuse", "knowledge-cutoff disclaimers"). Drop those names;
keep the kept ones (negative parallelism "It's not X, it's Y", Title Case
headings).

- **Alternatives considered:** edit only the skill body, leaving the front-matter
  description.
- **Rationale:** the description is an always-on surface. A stale description both
  misadvertises the skill and costs context budget in every session, so it is in
  scope even though it is one line.
- **Risks / caveats:** none beyond keeping the kept-tell names intact so the skill
  still advertises what it does catch.
- **Implemented in:** this track — see the Invariant on the SKILL.md:3 description
  and Plan-of-Work move 6.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 1 (5 findings, 5 accepted). All five were
  Plan-of-Work completeness gaps, not correctness errors — every named workflow
  path, line-anchor, and count the track asserts resolves against the live repo.
  Fixes folded into Moves 1/2/5/6: T1 added the two phantom `§ Banned vocabulary`
  self-references inside kept `house-style.md` sections (`:359`, Self-check item 7
  `:485`); T2 added the stale "six"→"four" count-word edits (`house-conversation.md:21`,
  `conventions.md:624`, hook `:262`); T3 made `design-document-rules.md:287` a
  substantive eleven→survivor lockstep rewrite co-decomposed with Move 4; T4
  corrected Move 1's false "§ Banned sentence patterns note" phrasing; T5 split the
  named consumers into re-point vs confirm-benign. Gate verification VERIFIED all
  five, 0 regressions (`technical-gate-verification-iter1.md`). Minimal tier — Risk
  and Adversarial dropped per the Phase-3A tier table.

## Context and Orientation

The house style lives in one file and is read by many. `house-style.md` (492
lines) is the single declarative source for the project's writing rules: it
states each rule once, with examples. Four kinds of consumer read those rules
without restating them, plus a wider set that hard-codes the rule-section names.
The deliverable of this track is the shrunk rule set together with every consumer
updated in lockstep, with no file left pointing at a removed section.

The terms used below, glossed once:

- **AI-tell subset.** A named subset of `house-style.md`'s rules — the
  AI-authorship-detection rules, as opposed to the structural and
  document-shape rules. `conventions.md §1.5` defines it as a tier that applies
  to code comments (Java/Kotlin) and to chat replies, naming six sections:
  `§ Orientation`, `§ Plain language`, `§ Banned vocabulary`, `§ Banned sentence
  patterns`, `§ Banned analysis patterns`, and `§ Em-dash discipline`. The
  §1.5 Tier-B table sits at `conventions.md:621`; a rename-safety grep that
  enumerates every pointer site before a section rename sits at
  `conventions.md:626`.
- **Bootstrap-slug enumeration.** A one-line sentence, replicated verbatim
  across many files, that names the six AI-tell subset sections so a sub-agent
  loading that file knows which house-style sections to apply. The canonical
  form (from `agents/code-reviewer.md:20`): "the six AI-tell subset section
  slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`,
  `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`, and
  `## Plain language`." A `grep` for `Em-dash discipline` across `.claude/agents`,
  `.claude/workflow`, and `.claude/skills` finds this line in 47 files (~21
  review agents, ~21 workflow prompts and rule files, ~5 skills). Removing the
  `## Banned vocabulary` and `### Em-dash discipline` sections turns every copy
  into a phantom reference unless each is edited in the same change. (Caveat: the
  47-file count comes from `grep` in a session with no mcp-steroid; treat it as
  "every grep hit", per the rename-safety contract below, not a frozen list. A
  paraphrase reference that does not match the grep is caught by the manual scan
  of the named prose consumers.)
- **The deterministic checker.** `design-mechanical-checks.py`, function
  `check_dsc_ai_tell`, detects the regex-expressible subset of the AI-tell rules.
  Its docstring enumerates eleven patterns. Four of the eleven map to removed
  rules: pattern 1 (Tier-1 banned vocabulary), pattern 3 (em-dash density),
  pattern 5 (signposting openers), and pattern 6 (copula avoidance). It is paired
  with `tests/test_dsc_ai_tell.py` and the byte-source fixture
  `tests/fixtures/dsc-ai-tell-fixture.md`.
- **The always-loaded skill description.** `skills/ai-tells/SKILL.md:3` carries a
  `description` field loaded into every session's skill list. It hard-codes
  removed tells by name ("delve", "foster", "em dash overuse", "knowledge-cutoff
  disclaimers") alongside kept ones (negative parallelism "It's not X, it's Y",
  Title Case headings).
- **The hook.** `hooks/house-style-write-reminder.sh` fires a one-time
  per-session reminder; its Tier-B body (line 262) lists the six section names.
- **The chat style.** `house-conversation.md` reuses the AI-tell subset for
  terminal replies. Its AI-tell-subset list names the six sections (lines 23-28);
  its Response-shape list already carries an inline no-preamble rule (line 15)
  that is the chat-only carrier per DR3.
- **The named prose consumers** (scanned for paraphrase references, beyond the grep):
  `design-document-rules.md`, `agents/design-author.md`,
  `agents/readability-auditor.md`, `agents/review-workflow-writing-style.md`, the
  cold-read prompt `prompts/design-review.md`, `skills/readability-feedback/SKILL.md`,
  and root `CLAUDE.md`.

This branch edits the rules live (not staged) under the §1.7(k) prose-rule
opt-out (DR4). The opt-out carries a mandatory obligation recorded here as a
process constraint, not a testable invariant: after the branch-final commit that
touches the §1.6(b) stamp pathspec (`.claude/workflow`, `.claude/skills`,
`.claude/agents`), run `/migrate-workflow` to advance every artifact's stamp to
HEAD and re-arm the drift gate. The drift gate is suppressed on intervening
sessions until that runs.

## Plan of Work

The work is one atomic change in seven moves. The ordering invariant: the
section removal and every enumeration or consumer update must land coherently,
so the repo never sits in an intermediate state where a removed section still
has live references. Splitting that across separately mergeable units would ship
the exact phantom-reference state this change exists to prevent (see the sizing
justification under Interfaces and Dependencies). The moves below name the work;
Phase A decomposes them into steps.

1. **Edit `house-style.md`.** Remove `## Banned vocabulary` (all four tiers),
   `### Em-dash discipline`, the knowledge-cutoff-disclaimer and sycophantic-openers
   bullets under `§ Banned sentence patterns`, and the `### Signposting` and
   `### Copula avoidance` subsections under `§ Banned analysis patterns`. Move the
   Tier-2 precision examples (`robust` → "tolerant of X", `comprehensive` → "covers
   X, Y, Z") into `§ Plain language`'s "prefer the precise word" move (DR6).
   Remove the `§ Plain language` "Reconciliation with § Banned vocabulary"
   subsection. Trim the Self-check list: item 1 (banned vocabulary) and item 2 (em
   dashes) are removed; item 5 drops the copula-avoidance and signposting names;
   item 4 drops the sycophantic-openers and knowledge-cutoff-disclaimer names;
   item 6 keeps curly quotes (DR5) but drops nothing else it does not own; item 7
   (the `**Structure.**` line at `house-style.md:485`) drops the "a banned term
   from § Banned vocabulary" clause from its padding-pattern definition, leaving
   `§ Banned sentence patterns` and `§ Elegant variation` restatement as the
   surviving padding sources. Re-point the two phantom self-references that survive
   the section delete because they live inside KEPT sections: the
   `### Padding-based finding criterion` rule (`house-style.md:359`, under
   `## Structural rules`) and Self-check item 7 (`:485`) both cite "a banned term
   from § Banned vocabulary" in their padding-pattern definition — drop that clause
   from both, so each cites only `§ Banned sentence patterns` and `§ Elegant
   variation`. The em-dash and negative-parallelism cross-references the removal
   would otherwise leave dangling sit inside the removed Tier-4 block
   (`house-style.md:131`, `:123`), so they self-delete with the section and need no
   separate fix. (The `house-style.md:6` framing
   "Every rule below applies to every paragraph" and the `:20` four-consumer list
   stay; only the removed sections drop out.)
2. **Update `conventions.md §1.5`.** Change the Tier-B table row (`:621`) from six
   sections to the four survivors — `§ Orientation`, `§ Plain language`, `§ Banned
   sentence patterns`, `§ Banned analysis patterns` — update the rename-safety
   grep at `:626` to drop the two removed slugs from its alternation, and change
   the count word "six" → "four" at `:624` ("The six Tier-B section names are
   stable headings") so the prose count matches the shrunk list.
3. **Sweep the bootstrap-slug enumeration.** In every file carrying the verbatim
   six-slug line (47 files by the grep above), drop `## Banned vocabulary` and
   `### Em-dash discipline`, leaving the four survivors. This is the same one-line
   edit repeated; it is the bulk of the file count but the lowest review complexity.
4. **Update the checker, tests, and fixture.** In `design-mechanical-checks.py`,
   remove the four patterns that map to removed rules (Tier-1 vocabulary, em-dash
   density, signposting, copula avoidance) and update the docstring count from
   eleven to the survivor count. Update `test_dsc_ai_tell.py` to drop the cases
   for the removed patterns and the byte-source fixture
   `dsc-ai-tell-fixture.md` to drop their fixture lines, so the test suite passes
   against the shrunk checker.
5. **Update `house-conversation.md`.** Drop `## Banned vocabulary` and
   `### Em-dash discipline` from the AI-tell-subset list (lines 23, 26), and the
   copula-avoidance and signposting names from the `## Banned analysis patterns`
   line (line 25) and the sycophantic-opener name from the `## Banned sentence
   patterns` line (line 24). Change the count word "six" → "four" on line 21
   ("Apply these six sections of …"), or the list reads four bullets under a "six
   sections" lead — a live self-contradiction in an always-loaded file. The inline
   chat-only no-preamble rule (line 15) is the chat-only carrier for the
   sycophantic-opener and signposting guard (DR3); it today names the sycophantic
   opener ("Great question") but not signposting ("Let's dive in"), so add the
   signposting guard to line 15 so the chat surface keeps both after the
   `house-style.md` bullets leave.
6. **Update the named prose consumers.** `skills/ai-tells/SKILL.md`: in the
   line-3 `description`, drop the removed-tell names ("delve", "foster", "em dash
   overuse", "knowledge-cutoff disclaimers") while keeping the kept ones (negative
   parallelism, Title Case) — that field is where the removed tells are named
   verbatim. In the skill body's `## Catalogue lookups` list, re-point the
   references to removed sections: the two `§ Banned vocabulary` pointers
   (`SKILL.md:23` and the line-29 "stays distinct from `§ Banned vocabulary`"
   clause) lose their target, and the line-25 tone-fingerprints "(sycophantic
   openers, …)" parenthetical names a removed bullet — drop or re-point each. The
   `§ Punctuation and typography` pointer (`SKILL.md:26`) stays; that section
   survives, only its `### Em-dash discipline` subsection leaves.
   The remaining named consumers fall in two groups. **Group (a) — an actual
   removed-section reference to re-point or rewrite:**
   - `agents/review-workflow-writing-style.md`: the em-dash-cap lens (line 30),
     the banned-vocabulary sweep (lines 29, 70-71, 78), and the knowledge-cutoff
     lens (line 34) name removed rules and are dropped or re-pointed; the
     sycophantic-openers "Great question!" reference (line 73) drops too.
   - `design-document-rules.md:287`: a substantive rewrite, not a one-line pointer
     re-point. The `dsc-ai-tell` row hard-codes "Detects eleven `house-style.md`
     patterns" and spells out all eleven, including the four removed (`§ Tier 1`,
     `§ Em-dash discipline`, `§ Signposting`, `§ Copula avoidance`). Drop those
     four clauses and change "eleven" to the survivor count, matching the Move 4
     checker-docstring edit. Decompose this edit and Move 4 into the same step so
     the pattern count stays coherent across the checker and its design-doc-facing
     description.
   - `hooks/house-style-write-reminder.sh`: drop `## Banned vocabulary` and
     `### Em-dash discipline` from the Tier-B body slug list (line 262) and change
     the count word "six" → "four" on the same line.
   - `agents/design-author.md`, `skills/readability-feedback/SKILL.md`, and root
     `CLAUDE.md`: re-point or drop every reference to a removed section. Root
     `CLAUDE.md` carries paraphrase references (`:93`, `:102`) caught by the DR7
     manual scan rather than the slug grep, so the manual scan resolves its
     disposition.

   **Group (b) — additive or confirm-benign, no removed-section re-point:**
   - `agents/readability-auditor.md` hard-codes no rule list (line 71: "no rule
     list is hard-coded here"); its only `§` citations are `§ Orientation` and
     `§ Plain language`, both kept. Its Move-6 work is purely the *additive* part-3
     ownership note — mechanical, countable house-style rules belong to the
     deterministic checker; the auditor focuses on judgment axes — not a
     removed-section re-point.
   - `prompts/design-review.md` cites only kept document-shape sections, so the
     DR7 manual scan classifies it confirm-benign with no edit.
7. **Run the completeness check.** Run the §1.5 rename-safety grep (extended with
   `-i 'sycophantic|signposting|copula|knowledge.cutoff'`) and, for every hit,
   confirm it either matches the post-shrink rule set or is a benign reference
   (e.g., a kept-section name, or a removed name inside an example block). Then run
   `test_dsc_ai_tell.py`.

## Concrete Steps

The seven moves split into two steps along the prose/script review boundary.
Step 1 isolates the test-churny checker shrink (Move 4 plus the
`design-document-rules.md` mirror from Move 6, co-decomposed per T3) so its test
iteration does not drag the mechanical prose sweep. Step 2 is the atomic
removal-and-rewire (Moves 1, 2, 3, 5, the rest of Move 6, and Move 7's
rename-safety grep): the section removal and every consumer update land in one
commit, so the repo never sits at a boundary where a removed `house-style.md`
section still has a live reference. Step 2 depends on Step 1 so its whole-repo
rename-safety grep is clean (the checker and its design-doc mirror, Step 1's
domain, no longer name a removed pattern). The steps touch disjoint files.

1. Shrink the deterministic checker and its design-doc mirror — remove the four `check_dsc_ai_tell` patterns that map to removed rules (Tier-1 vocabulary, em-dash density, signposting, copula) from `design-mechanical-checks.py` and drop the docstring count from eleven to the seven survivors; drop the matching cases from `tests/test_dsc_ai_tell.py` (including the Tier-1 heading-scan regression case) and the removed-pattern blocks from `tests/fixtures/dsc-ai-tell-fixture.md` (including the H3 Tier-1 block); rewrite `design-document-rules.md:287` to drop the four removed-pattern clauses and change "eleven" to the survivor count so it matches the shrunk checker (Move 4 + the T3 lockstep mirror). Acceptance: `test_dsc_ai_tell.py` passes against the shrunk checker; a document with a Tier-1 word, three em dashes, a "Let's dive in" opener, or a "serves as" copula draws no `check_dsc_ai_tell` finding. — risk: medium (test infrastructure + bounded behavioral workflow edit: the checker is not auto-run — only `edit-design` Step 3 invokes it, which this branch never reaches per DR4 — the change is purely subtractive, and `test_dsc_ai_tell.py` fully covers it) — size: ~4 files; heavy-iteration carve-out (checker test-churn kept isolated from the mechanical sweep) and the only other work is the high-isolation Step 2  [x] commit: c24f222228
2. Remove the six concealment-only rules from `house-style.md` and update every consumer in lockstep — `house-style.md` (Move 1: delete `## Banned vocabulary` all tiers, `### Em-dash discipline`, the sycophantic-openers and knowledge-cutoff bullets, `### Signposting`, `### Copula avoidance`; move the Tier-2 precision examples into `§ Plain language`; drop the Reconciliation subsection; trim Self-check items 1/2/4/5/6/7 and re-point the `:359` padding criterion and `:485` item-7 self-references); `conventions.md §1.5` (Move 2: `:621` row six→four, `:626` grep alternation, `:624` count word); the 47 bootstrap-slug files (Move 3: drop the two removed slugs, leaving four); `house-conversation.md` (Move 5: trim the subset list, `:21` count word, add the signposting guard to the `:15` chat carrier); the named prose consumers (Move 6 minus `design-document-rules.md`: `ai-tells/SKILL.md:3` description + body pointers, `review-workflow-writing-style.md` lenses, `hooks/house-style-write-reminder.sh:262` slug list + count word, `design-author.md`, `readability-feedback/SKILL.md`, root `CLAUDE.md` paraphrase refs, plus the additive ownership note in `readability-auditor.md`). Acceptance: the §1.5 rename-safety grep extended with `-i 'sycophantic|signposting|copula|knowledge.cutoff'` returns only benign hits across the whole repo (DR7). *(Depends on Step 1.)* — risk: high (workflow machinery: edits the auto-running `house-style-write-reminder.sh` hook and the always-loaded root `CLAUDE.md`, and removes the §1.5 AI-tell subset sections all 47 bootstrap-slug consumers and every workflow reviewer key off; must land atomically, so high-isolation applies with no file cap)  [x] commit: f69062032c

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step.
Empty at Phase 1. -->

### Step 1 — commit c24f222228, 2026-06-19T13:45Z [ctx=safe]
**What was done:** Removed the four `check_dsc_ai_tell` patterns that map to
removed rules (Tier-1 banned vocabulary, em-dash density, signposting openers,
copula avoidance) from `design-mechanical-checks.py`, and dropped the docstring
pattern count from eleven to the seven survivors (negative parallelism leading
and trailing, Title Case headings, authority tropes, hyphenated-pair clusters,
fragmented headers, inflated-abstraction labels). Dropped the matching positive
cases and the Tier-1 heading-scan regression case from `test_dsc_ai_tell.py`,
and the removed-pattern blocks from `dsc-ai-tell-fixture.md` (the four positive
blocks, the single-em-dash negative block, the H3 Tier-1 block), renumbering
anchors and negative ranges to the shrunk fixture. Rewrote the `dsc-ai-tell`
row in `design-document-rules.md` to drop the four removed-pattern clauses and
change "eleven" to "seven", matching the shrunk checker.

**What was discovered:** The checker and its tests live under `.claude/scripts/`
(`design-mechanical-checks.py`, `tests/`), not `.github/scripts/`. The runner
is a standalone script (`python3 .claude/scripts/tests/test_dsc_ai_tell.py`),
not a pytest module; the environment has no `pytest`. The suite passes after
the shrink: 8 fixture findings across the 7 survivor patterns, zero on the
negative cases, zero on the 3 calibration ADRs. A document carrying all four
removed triggers (a "Let's dive in" opener, a "serves as" copula, Tier-2 words,
three em dashes in a paragraph) draws no `dsc-ai-tell` finding.

**Key files:**
- `.claude/scripts/design-mechanical-checks.py` (modified)
- `.claude/scripts/tests/test_dsc_ai_tell.py` (modified)
- `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` (modified)
- `.claude/workflow/design-document-rules.md` (modified)

### Step 2 — commit f69062032c, 2026-06-19T14:10Z [ctx=safe]
**What was done:** Removed the six concealment-only rules from `house-style.md`
in one commit: the four-tier `## Banned vocabulary` section, the `### Em-dash
discipline` subsection, the sycophantic-openers and knowledge-cutoff bullets
under `## Banned sentence patterns`, and the `### Signposting` and `### Copula
avoidance` subsections under `## Banned analysis patterns`. Folded the Tier-2
precision examples into `§ Plain language`, dropped the Reconciliation
subsection, and trimmed and renumbered the Self-check list to 10 items,
re-pointing the `:359` padding criterion and the item-7 self-reference off the
deleted `§ Banned vocabulary`. Updated every consumer in lockstep:
`conventions.md §1.5` (Tier-B row six→four, count word, rename-safety grep
alternation); the bootstrap-slug enumeration across 46 review agents and
workflow files (single-line form swept over 38 files, four wrapped multi-line
forms by hand); `house-conversation.md` (four-section subset, count word, plus
the DR3 inline chat-only sycophantic-plus-signposting guard on the no-preamble
rule); the write-reminder hook plus its paired test (`TIER_B_HEADINGS`);
`ai-tells/SKILL.md` description and body; the writing-style review agent; root
`CLAUDE.md`; `design-author.md` and `readability-feedback`; plus the additive
checker-versus-judgment ownership note in `readability-auditor.md`. Step-level
review (`risk: high`, workflow-only diff so the baseline group is skipped) ran
hook-safety and prompt-design: both PASS, 0 blockers; the one Minor finding
(WP1) is deferred.

**What was discovered:** The acceptance grep (the §1.5 rename-safety alternation
plus `-i 'sycophantic|signposting|copula|knowledge.cutoff'`) reads clean across
`.claude/` and root `CLAUDE.md`: zero live removed-section references. The
remaining lexical hits are benign — the grammar word "copula" in the kept
code-identifier rules, "sycophantic" in the chat-register description, and the
intentional DR3 chat guard. Three independent gates re-confirmed green after
the sweep: `workflow-reindex.py --check` (TOC/anchor-drift, RC=0), the hook
test (18/18), and the dsc-ai-tell regression (7 patterns). The bootstrap-slug
line exists in two physical shapes, a single-line form (38 files) and a wrapped
multi-line form in four files (`step-implementation`, `implementer-rules`,
`commit-conventions`, `episode-format-reference`) that also carry a Tier-A
category descriptor naming two removed sections; both shapes were swept. The
"six sections" phrasing in `review-workflow-pr/SKILL.md` is the resume-handoff
schema, unrelated to the AI-tell subset, and was left untouched. Deferred
suggestion WP1 (prompt-design, Minor): optionally restore one concrete
kept-vocabulary anchor in the `ai-tells/SKILL.md:3` analysis-fingerprints clause
so the always-loaded description stays matchable on a concrete pasted-tell
signal; the reviewer rated it not required for correctness. Cross-cutting: the
workflow-book deliverable (PR #1151) and several historical ADRs still name the
removed rules outside this track's `.claude/` + `CLAUDE.md` invariant — see the
Surprises entry.

**What changed from the plan:** One in-scope completeness expansion beyond the
literal Move 6 text: the four wrapped multi-line consumers also carried a
Tier-A "banned vocabulary, em-dash discipline" category descriptor that the DR7
acceptance grep flags; those descriptors were re-pointed in the same commit to
keep the `.claude/`-scoped invariant clean. No move was dropped or reordered.

**Key files:** (54 files; the load-bearing surface is named, the remainder are
the identical bootstrap-slug one-liner)
- `.claude/output-styles/house-style.md` (modified — the six removals, the
  Plain-language fold, the Self-check trim)
- `.claude/output-styles/house-conversation.md` (modified — subset list, count
  word, DR3 chat guard)
- `.claude/workflow/conventions.md` (modified — §1.5 Tier-B row, count word,
  rename-safety grep)
- `.claude/hooks/house-style-write-reminder.sh`,
  `.claude/scripts/tests/test_house_style_hook.py` (modified — slug list plus
  paired test in lockstep)
- `.claude/skills/ai-tells/SKILL.md` (modified — description plus body)
- `.claude/agents/review-workflow-writing-style.md`, `design-author.md`,
  `readability-auditor.md` (modified)
- root `CLAUDE.md` (modified — paraphrase refs)
- ~46 further files under `.claude/agents/**`, `.claude/skills/**`,
  `.claude/workflow/**`, `.claude/workflow/prompts/**` (modified —
  bootstrap-slug six→four sweep)

**Critical context:** The DR4 obligation stands. This commit advanced HEAD past
every `_workflow/` artifact's workflow-sha stamp via the §1.6(b) pathspec, so a
`/migrate-workflow` stamp-advance is required after the branch-final commit to
re-arm the drift gate (the gate is suppressed on intervening sessions until
then).

## Validation and Acceptance

The track is done when all of the following hold, restating the rewritten
YTDB-1144 criteria:

- `house-style.md` no longer states the removed rules: no `## Banned vocabulary`
  section, no `### Em-dash discipline` subsection, no `### Signposting` or
  `### Copula avoidance` subsection, and no sycophantic-openers or
  knowledge-cutoff-disclaimer bullet under `§ Banned sentence patterns`. Its
  Self-check list no longer cites those rules.
- The deterministic checker no longer fires the removed patterns: a document with
  a Tier-1 word, three em dashes in a paragraph, a "Let's dive in" opener, or a
  "serves as" copula draws no finding from `check_dsc_ai_tell`. The docstring
  pattern count matches the survivor set, and `test_dsc_ai_tell.py` passes against
  the shrunk checker.
- Every consumer references only the kept rules: the §1.5 rename-safety grep
  (extended for the four lexical removes) returns only benign hits, and no file
  under `.claude/` or root `CLAUDE.md` points at a removed section as a live
  reference.
- A document or track authored with the kept rules draws no finding for a removed
  pattern: a single balanced appositive aside (`A — clause — B`), a Tier-2 word
  used precisely, "serves as" where the action is genuinely active, do not flag.
  The motivating churn (YTDB-1144's four rounds over balanced em-dash pairs)
  cannot recur, because the rule that drove it is gone from both the prose and the
  checker.
- The comprehension value the banned-vocabulary list carried survives: `§ Plain
  language` now states the "prefer the precise word" move with the moved examples
  (`robust` → "tolerant of X", `comprehensive` → "covers X, Y, Z").

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as
test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Both steps are pure edits with no durable side effects beyond the working tree,
so recovery is the standard `git reset --hard HEAD` then re-run from the step's
clean base. Each edit is idempotent: deleting an already-absent section, dropping
an already-removed slug, or changing an already-"four" count word is a no-op, so a
half-applied step re-runs to the same end state without double-removal.

- **Step 1.** The checker and fixture edits are subtractive; re-running over a
  partially-shrunk checker converges (a pattern already removed stays removed).
  The gate is `test_dsc_ai_tell.py`: a red suite means the fixture or expected
  groupings still carry a removed pattern, so iterate on the test/fixture until
  green. No state outside the three files plus `design-document-rules.md`.
- **Step 2.** The section removal and the consumer sweep are idempotent text
  edits; the recovery path on a partial sweep is to re-run the §1.5 rename-safety
  grep (extended) and edit every remaining hit. Because the grep is the
  completeness oracle, a re-run after a crash re-derives the exact remaining work
  from the current tree rather than from a checklist of which files were touched.
  Step 2 must re-confirm Step 1 landed (clean `test_dsc_ai_tell.py`, no removed
  pattern named in the checker or its design-doc mirror) before its whole-repo
  grep can read clean.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't belong to
one specific step. Often empty. -->

## Interfaces and Dependencies

**In scope:** the canonical source (`house-style.md`, `conventions.md §1.5` at
`:621` and `:626`); the 47 files carrying the bootstrap-slug enumeration; the
checker, tests, and fixture (`design-mechanical-checks.py`,
`test_dsc_ai_tell.py`, `dsc-ai-tell-fixture.md`); the always-loaded skill
description (`skills/ai-tells/SKILL.md:3`); the hook
(`hooks/house-style-write-reminder.sh`); the chat style (`house-conversation.md`);
and the named prose consumers (`design-document-rules.md`,
`agents/design-author.md`, `agents/readability-auditor.md`,
`agents/review-workflow-writing-style.md`, `prompts/design-review.md`,
`skills/readability-feedback/SKILL.md`, root `CLAUDE.md`).

**Out of scope:** wiring the deterministic `dsc-ai-tell` check into the
`create-plan` Step-4b track-authoring loop. That work (YTDB-1148) is orthogonal
mechanical-coverage work; its original em-dash motivation evaporated once the
em-dash rule was removed here.

**Inter-track dependencies:** none — this is a single track.

**Compatibility:** the edits land live (DR4, §1.7(k) prose-rule opt-out), not in
a staged copy. The mandatory `/migrate-workflow` stamp-advance after the
branch-final commit is the only cross-cutting obligation (recorded in Context and
Orientation).

**Sizing justification (the track exceeds the soft footprint ceiling).** The
track touches ~50 files, over the ~20-25 split ceiling. It stays one track
because it is a single atomic change: removing the sections and updating every
reference must land in one coherent change, since any split would ship the
phantom-reference intermediate state — a removed section with live references —
that the change exists to prevent. Review complexity is low for most of the
footprint: the 47 bootstrap-slug files take the identical one-line edit, so the
file count overstates the review surface. The load-bearing review surface is the
small set of hand-edited files (`house-style.md`, the checker and its tests, the
chat style, and the named prose consumers).

## Invariants & Constraints
<!-- Plan-at-start, combined section (D9). Phase 1 writes both the per-track
testable constraints and the testable invariants. A process-only,
non-testable constraint goes to Context and Orientation or Decision Log. -->
- After the change, no file under `.claude/` or root `CLAUDE.md` references a
  removed section or rule except as a confirm-benign reference (a kept-section
  name, or a removed name inside an example or fixture block) — verified by the
  §1.5 rename-safety grep (extended with `-i 'sycophantic|signposting|copula|knowledge.cutoff'`)
  returning only benign hits, plus the Phase-C consistency reviewer. This is the
  acceptance contract (see DR7).
- `design-mechanical-checks.py` no longer fires the four removed patterns (Tier-1
  vocabulary, em-dash density, signposting, copula avoidance), and the
  `check_dsc_ai_tell` docstring pattern count drops accordingly — verified by
  `test_dsc_ai_tell.py` and the `dsc-ai-tell-fixture.md` byte-source fixture.
- The §1.5 Tier-B subset (`conventions.md:621`) and the 47 bootstrap-slug lines
  enumerate exactly the four surviving sections (`§ Orientation`, `§ Plain
  language`, `§ Banned sentence patterns`, `§ Banned analysis patterns`) —
  verified by grep (see DR8).
- `house-conversation.md` retains a chat-only sycophantic-opener and signposting
  guard as an inline rule (not a cross-reference to a removed `house-style.md`
  bullet) — verified by reading (see DR8).
- `skills/ai-tells/SKILL.md:3` description names no removed tell and keeps the
  kept ones (negative parallelism "It's not X, it's Y", Title Case headings) —
  verified by grep (see DR9).
- `§ Plain language` carries the moved precision examples (`robust` → "tolerant
  of X", `comprehensive` → "covers X, Y, Z") and no longer holds a "Reconciliation
  with § Banned vocabulary" subsection — verified by reading.

## Base commit

Phase B base commit: `e68f84f760f852aee1a53ff71fb1e46102642638`
