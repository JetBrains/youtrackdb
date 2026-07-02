<!-- workflow-sha: 3c57672e9b12b504d5feb5134ca96be891b3ffbc -->
# Track 1: Style-machinery rework — disguise-rule removal, technical-writer voice, persona readers, Summary rename, BLUF hardening

## Purpose / Big Picture
After this track, YouTrackDB's design and plan documents are authored and reviewed under a readability-first style regime — staged now under `_workflow/` and promoted to the live tree only at Phase 4. The regime bundles five moves:

- the six disguise-only style rules are removed;
- the design and track writer adopts the technical-writer voice of the YouTrackDB internals book — the reader-praised teaching book whose voice rules this change borrows;
- the two cold review agents run on Sonnet as named reader personas;
- the per-section `TL;DR` block becomes a `### Summary` sub-heading;
- the opening sentence of each section (its BLUF lead) must stand as a self-contained plain claim, per YTDB-1163.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

The workflow's authored documents are governed by one declarative style file, `.claude/output-styles/house-style.md`, plus a handful of surfaces that name or mirror individual rules. Today that machinery carries a class of rules whose only job is to make text read as human-authored; those rules fight model training and add no comprehension value, yet the plan documents stay hard to read and expensive to review. This track stops fighting training and spends the freed effort on readability: it deletes the disguise-only rules at `house-style.md` and at every surface that names or mirrors them, and reworks the author and the two review readers around teaching and reading each document rather than disguising it. Every edit is staged under `_workflow/staged-workflow/.claude/**` and promotes to the live tree only at Phase 4, so the new rules do not take effect while the branch is open.

> **Scope:** ~19 files covering the `house-style.md` source of truth and its consumer surfaces, the five-agent review roster, the `dsc-ai-tell` mechanical checker with its test and fixture, and the workflow prose that hard-codes the `TL;DR` shape
> **Complexity (predicted):** high — the Workflow-machinery HIGH trigger fires centrally on the whole planned work (house-style rules, review-agent model pins and personas, and the `dsc` mechanical checker with its tests)

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

**PAUSED 2026-07-02 at Phase A (iter-1 reviews done) pending user decision on the §1.7 staging-surface blocker (A1/A2)**
- Handoff: `handoff-track-1-phaseA.md`

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- The track-canonical live decision carrier (D7). Phase 1 seeds the full
inline Decision Records this track owns; the section then continues as the
execution-time continuous log. Seeded from the frozen design.md D-records. -->

#### D1: Remove the six disguise-only style rules at the source plus every mirrored consumer
- **Alternatives considered:** Chat-register-only removal — rejected because the review cost lives in the durable documents, so leaving the disguise rules in `house-style.md` keeps fighting training on the surface that matters most.
- **Rationale:** The disguise-only rules make text look human-authored while adding no comprehension value and carry enforcement cost. Removal happens at the source (`house-style.md`): the design-review cold-read prompt cites `house-style.md` by section and inherits the deletion for free, while every other surface that names or mirrors a removed rule takes an explicit same-change edit. The six removed rules are the negative-parallelism ban, the roundabout-negation ban, the closing-phrases ban, the hyphenated-word-pair rule, the curly-quotes rule, and the sentence-case heading mandate (Title Case allowed again). The mirrored consumers touched in the same change, enumerated by grep over the removed pattern names: the `dsc-ai-tell` regexes in `design-mechanical-checks.py` with their test and fixture, the always-loaded `ai-tells/SKILL.md` `description:` frontmatter plus its catalogue, `review-workflow-writing-style.md`, `house-conversation.md`, the `design-document-rules.md` `dsc-ai-tell` catalogue row, and the `CLAUDE.md` § Writing Style parenthetical.
- **Risks / Caveats:** A removed regex, its assertion, and its fixture line are one unit — deleting the regex alone breaks the build at test time. The `ai-tells/SKILL.md` description is loaded into every session, so three of the six removals keep costing context until it is edited. Removal is not a backfill: legacy committed designs under `docs/adr/**` were authored under the removed rules and are not re-reviewed.
- **Implemented in:** this track.
- **Full design**: design.md §"Removing the disguise-only style rules".

#### D2: Hybrid narrative-plus-findings on the whole-doc gate; persona-voiced structured findings only on the sliced auditor
- **Alternatives considered:** Pure narrative plus a synthesis step (the book model verbatim) — larger loop rework, and the orchestrator must parse prose into revision items. Manifest-only with a persona identity — loses the experiential signal on the whole-doc surface where it is real. Uniform hybrid on both roles — the auditor-path narrative has no consumer and only costs context.
- **Rationale:** The reading-experience narrative is a whole-read property, so it attaches only to the whole-doc comprehension gate (the time-constrained reviewer), whose narrative is meaningful because it reads the full document in order and whose consumer is real — the orchestrator's gate verdict plus the author's rework seed. The range-sliced target reader returns persona-voiced structured findings only: at a ~200-line slice a "stumble" is exactly what a structured finding already records (verbatim quote, location, why it breaks), and a per-slice narrative would flow into the orchestrator with no consumer, multiplied by up to six slices across three rounds.
- **Risks / Caveats:** The structured findings keep the dual-clean convergence keyed on discrete findings in both roles.
- **Implemented in:** this track.
- **Full design**: design.md §"Persona readers: target reader and time-constrained reviewer".

#### D3: Move both reader agents to Sonnet as a mid-level proxy; pin the author to Opus, never Fable
- **Alternatives considered:** Keep Opus readers — defeats the reader-proxy calibration and costs more. Keep one Opus backstop on the outer gate only — the gate's cited comprehension answers already make its failures visible, so the backstop buys nothing there, and splitting the pins re-introduces the calibration mismatch on the gate that most needs the proxy. Fable for the author — unnecessary given the resolved-log input.
- **Rationale:** The reader roles judge reading experience, not design quality, so a mid-level proxy is the right model: if Sonnet can follow the document, a mid-level developer can. The author stays Opus because it drafts from an already-resolved research log, so Fable adds nothing. Three mitigations target the false-clean risk (a weaker reader glossing over what it failed to follow): the persona contract targets that failure mode directly ("you will not fill in gaps"), Opus readers carry the opposite error at similar cost (following prose a mid-level human cannot), and the `dsc-ai-tell` pre-flight plus the Phase-C style pass remain as backstops.
- **Risks / Caveats:** The proxy claim is one-directional (it covers "if Sonnet follows it, a human will"); the load-bearing direction is whether Sonnet reports what it could not follow. Residual risk is named and accepted: if dual-clean documents keep drawing human readability complaints, the model pin is the first suspect to revisit.
- **Implemented in:** this track.
- **Full design**: design.md §"Reader-proxy model pins and the false-clean risk".

#### D4: Rename `TL;DR` to a `### Summary` sub-heading with details under their own sub-headings; keep legacy spellings accepted
- **Alternatives considered:** A `**Summary.**` bold-prefix rename alone — the summary still runs into the details, which is the separation failure at issue. A hard rename without legacy acceptance — breaks every committed legacy design under `docs/adr/**` on re-review.
- **Rationale:** A sub-heading gives structural separation of summary from detail — skimmable end-to-end and regex-enforceable — where "Summary" is also self-explanatory to a reader for whom "TL;DR" is jargon. Backward compatibility follows the D11 References→Decisions & invariants precedent: both spellings are accepted in the shape regexes. The complete rename-site list is enumerated by grep over `.claude/`; the subtle one is `MANDATORY_OR_FORM_SUBHEADINGS`, which must gain `"summary"` alongside `"tl;dr"` or the same-shape sibling similarity check counts the shared `### Summary` heading in every section and false-positives on every well-formed design.
- **Risks / Caveats:** This design is authored under the current live rules, so it still uses `**TL;DR.**`; the `### Summary` form applies to designs authored after promotion.
- **Implemented in:** this track.
- **Full design**: design.md §"Renaming TL;DR to Summary".

#### D5: Technical-writer voice governs design and track prose only; issue, PR, and commit bodies stay terse BLUF
- **Alternatives considered:** Apply the voice everywhere — worsens the act-oriented surfaces (a narrative issue body adds length without comprehension gain). Apply it over whole track files without the carve-out — an author agent could then legitimately narrativize rosters and episode fields, inflating every implementer read.
- **Rationale:** The teaching-narrative voice suits documents read to build a mental model. In a track file the voice governs the prose sections only — `## Purpose / Big Picture`, `## Context and Orientation`, and `## Plan of Work`. The structured ExecPlan surfaces stay registry-terse: the `## Concrete Steps` roster, `## Episodes` fields, `## Decision Log` records, `## Invariants & Constraints` bullets, and `## Interfaces and Dependencies` boundary lists. The prose trio is exhaustive.
- **Risks / Caveats:** `## Validation and Acceptance` and `## Idempotence and Recovery` are author-written but stay registry-terse — they are not part of the prose trio.
- **Implemented in:** this track.
- **Full design**: design.md §"The technical-writer voice".

#### D6: Recast the two existing cold readers as personas; add no new spawns; mechanical checks stay personless
- **Alternatives considered:** A third veteran spawn — extra per-round cost for a signal the comprehension verdict already carries. New persona agents alongside the old roles — duplicates the review surface. A persona without the checklist — silently drops the judgment half of the axis, leaving the kept rules with no per-round enforcer.
- **Rationale:** `readability-auditor` becomes the target reader (a mid-level developer who "will not fill in gaps", persona-voiced structured findings). `comprehension-review` becomes the time-constrained reviewer (thirty minutes to build a working mental model, hybrid output); the skeptical veteran's depth-skepticism folds into its mental-model verdict rather than a third spawn. The persona governs register and stance, not the checklist: the target reader keeps sole ownership of the prose AI-tell axis (invariant S4) and retains the house-style `§`-citation obligation for the kept judgment rules the regexes cannot reach.
- **Risks / Caveats:** `absorption-check` and `fidelity-check` stay personless coverage cross-checks; coverage matching needs no reader stance.
- **Implemented in:** this track.
- **Full design**: design.md §"Persona readers: target reader and time-constrained reviewer".

#### D7: Per-rule remove/keep disposition under one criterion, with a closure default
- **Alternatives considered:** Keeping the heading-case rule — marginal navigation value, and textbook fighting-training for cosmetics (flagged to the user, not pulled back). Removing padding rules as tells — they cut review time, so they contribute to the aim and stay.
- **Rationale:** One criterion, applied rule by rule. Remove when the rule's only benefit is disguise and it carries enforcement cost; keep when deleting the flagged text shortens the document or when the rule forces the writer to supply substance. Six rules are removed (listed in D1). Orientation and Plain language stay wholesale; passive voice, nominalization, broken grammar around identifiers, elegant variation, and the boldface cap stay; throat-clearing, prompt-restating, and superficial `-ing` clauses stay as additive padding; hedge stacking, filler hedges, trailing hedges, vague attribution, false ranges, generic positive conclusions, and persuasive authority tropes stay because each forces a claim to carry its number, source, or named trade-off. The consumer-only "adjective triads" rule stays under the same criterion (it forces each vague adjective to become a named quality) and gains no new house-style section. Closure default: any rule not on the remove list keeps its current disposition.
- **Risks / Caveats:** The line between remove and keep is illustrated by negative parallelism (removed) versus throat-clearing (kept): throat-clearing is additive filler whose deletion shortens the text, whereas negative parallelism reframes real content at near-zero length cost, so banning it buys only disguise, and its trailing-negation variant is the most false-positive-prone regex in the checker.
- **Implemented in:** this track.
- **Full design**: design.md §"Removing the disguise-only style rules".

#### D8: Dual-register design document — the frozen `design.md` stays the full-tier track seed; the machine-facing skeleton is exempt from the narrative voice
- **Alternatives considered:** The research log as the full-tier track seed — forces mechanism re-derivation from code, risks divergence from the frozen reviewed design, and loses the integration pass. Applying the narrative voice uniformly, including records and summaries — dissolves operative content into the story arc, which is the extraction hazard the user's doubt named.
- **Rationale:** The research log is an append-only ledger with superseded entries and no integrated mechanism, so it cannot supply the mechanism content that tracks need; the frozen design is reviewed, integrated, and user-accepted. Its seed consumer, the track-derivation spawn, is itself a cold agent, and narrative orientation helps that reader. The guard is a register boundary inside the document: the technical-writer voice governs the mechanism prose, while decision records keep the four-bullet form, `### Summary` blocks stay plain-claim and self-contained, decisions-and-invariants footers stay lists, and diagrams stay.
- **Risks / Caveats:** When `design_gate=yes`, the frozen design is the seed, not the research log; the log is a Phase-2 cross-check input, never a Step-4b seeding input in that case.
- **Implemented in:** this track.
- **Full design**: design.md §"Dual-register design document".

#### D9: Transfer the eight internals-book voice rules; rank the two colliding rules; add the link-staleness re-read reminder
- **Alternatives considered:** A whole-document story arc — design docs are consulted, not read cover to cover. Dropping the forward/backward links entirely — the book's dip-in readers showed the links carry navigation. Leaving the two rule sets unranked — two enforcement agents with contradictory targets thrash the authoring loop.
- **Rationale:** Of the eight `BOOK_BRIEF.md` voice rules, five transfer verbatim (concrete before abstract, one concept per section, earn every name, diagrams must teach, no bullet-point fact dumps), two are adapted from chapter scope to section scope (narrative not reference; connect forward and backward as light prose links), and one is already present (precise `file:line` citations). Two precedence rankings resolve collisions with kept house-style rules: a worked-example opener beats the section-length heuristics (the anti-padding clause still applies inside the example), and one-concept-per-section beats same-shape sibling consolidation when the siblings teach distinct concepts. The adapted links cite the neighbor's heading verbatim so they are greppable.
- **Risks / Caveats:** A section rename that skips the neighbor re-read leaves a dangling prose link no mechanical check catches — the `edit-design` mutation-discipline reminder (re-read neighbor openers and closers on section-move/remove/rename) is the guard.
- **Implemented in:** this track.
- **Full design**: design.md §"Transferring the internals-book voice rules".

#### D10: Adopt YTDB-1163's two BLUF-hardening rules; compose the plain claim ahead of the D9 backward-link opener; three acceptance sites
- **Alternatives considered:** Deferring YTDB-1163 to its own branch — the site overlap is with D1 and D7 (the removals rewrite `house-style.md` § Banned sentence patterns and renumber the self-check, and the disposition rework rewrites `review-workflow-writing-style.md`), so a second branch pays a second review round over the same regions. Treating the issue as advisory input only — the initial request explicitly folds YTDB-1163 into scope.
- **Rationale:** Today's section-level BLUF rule permits an opaque forward-reference lead and a body that reads only as its continuation. Two rules close the gap: the lead must be a self-contained plain claim parseable before the body (gloss any domain term, or fall back to why-before-what), and the body must stand as complete prose with the lead deleted (an anaphor opener resolving into the lead is a finding). The two rules share the section-opening slot with D9's connect-backward opener; the plain claim comes first and the backward link rides as a subordinate clause or the following sentence, so every enforcement agent reads one ranked rule. Acceptance names three sites: `house-style.md` § BLUF lead and § Orientation with an exemplar pair beside the existing orientation exemplar, the `review-workflow-writing-style.md` BLUF criteria, and the by-name house-style self-check BLUF item. The two rules bind every section lead across the workflow-markdown surfaces and the issue/PR BLUF, including surfaces with no `### Summary` block.
- **Risks / Caveats:** The self-check item is anchored by name rather than ordinal because this change's D1 and D7 removals renumber the self-check list.
- **Implemented in:** this track.
- **Full design**: design.md §"Hardening the section BLUF lead".

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation
The workflow authors and reviews its durable documents under one declarative style file, `.claude/output-styles/house-style.md`, which is the single source of truth for authored-document style. Four other surfaces are named as its consumers, but only one inherits a rule deletion for free: the cold-read prompt in `.claude/workflow/prompts/design-review.md` cites `house-style.md` by section heading and restates no rule, so deleting a rule at the source removes it from that prompt's reach at once. The other three name or mirror specific rules and take an explicit same-change edit: the `dsc-ai-tell` regex checker inside `.claude/scripts/design-mechanical-checks.py` mirrors a subset of the rules as its own hard-coded regexes; the `ai-tells` skill (`.claude/skills/ai-tells/SKILL.md`) names three of the removed rules in its always-loaded `description:` frontmatter and its body catalogue; and the chat-register file `.claude/output-styles/house-conversation.md` lists the banned patterns by name in its AI-tell subset. Three further surfaces hold their own copies and must be edited directly: `.claude/agents/review-workflow-writing-style.md` (the Phase-C style reviewer, which carries its own enforce-these-rules checklist), `.claude/workflow/design-document-rules.md` (section shapes plus the `dsc-ai-tell` catalogue row), and the project `CLAUDE.md` § Writing Style.

A few terms recur. A **disguise-only rule** is a style rule whose only benefit is making text read as human-authored, with no gain in comprehension and no reduction in length; these are what D1/D7 remove. **`dsc-ai-tell`** is the mechanical regex checker in `design-mechanical-checks.py` that flags a subset of the house-style patterns whose textual fingerprint is reliable enough to match; its exact fire/no-fire behavior is pinned by `.claude/scripts/tests/test_dsc_ai_tell.py` against `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md`, so a regex, its assertion, and its fixture line form one unit. The **ExecPlan** is the fifteen-section per-track execution-plan template (defined in `conventions-execution.md §2.1`) that this very file follows. A **persona reader** is a cold review agent given a named reader identity and stance instead of a neutral checklist.

The review side today is a roster of five agents. `design-author` (Opus), `readability-auditor` (Opus), and `comprehension-review` (Opus) are the three that change here; `absorption-check` and `fidelity-check` (both already Sonnet) are unchanged and listed only so the loop's full membership is visible. `readability-auditor` and `comprehension-review` are the two review readers driven by the `phase1-creation` dual-clean authoring-review loop in `.claude/skills/edit-design/SKILL.md`. A reader's warmth says how much authoring context it carries. A warm agent reads the research log and the planning history, so it checks the draft against what the author already knew. The two review readers instead judge the finished document on its own, the way the eventual reader meets it, and differ only in why they lack that context: a cold reader never carried the authoring context, while a de-warmed reader would normally run warm but has that context deliberately withheld for the read. Each round the author drafts; a range-sliced auditor (cold) and a warm coverage-matcher run in parallel until both return clean, and a de-warmed comprehension gate reads the finished document once at the end.

The concrete deliverables of this track are the staged edits to the ~19 files listed under `## Interfaces and Dependencies`; each file's entry there names what changes in it and which decision records drive the change.

Two process constraints govern how those edits land. First, the branch runs in `conventions.md` §1.7 staged mode — not the §1.7(k) prose-only opt-out — because the `dsc-ai-tell` regexes and the two agent frontmatters are behavior-bearing, and behavior-bearing changes disqualify the opt-out. Every implementation edit therefore accumulates under `_workflow/staged-workflow/.claude/**`, mirroring the live path it will replace (copy-then-edit on first touch), and the live `.claude/**` tree is untouched until the Phase-4 promotion commit. Second, because the new rules are staged, they are not live while the branch is open: this track file and the frozen `design.md` are both authored and reviewed under the current live rules — bold-prefix `**TL;DR.**` summaries, sentence-case headings, and the current banned-pattern set — and the new machinery reviews only documents authored after promotion.

## Plan of Work
The work is sequenced so that each edit is complete before its dependents rely on it, and so the build never sees a half-removed rule.

Start with the removal (D1/D7), because the Summary rename, the BLUF hardening, and the book-rule transfer all rewrite regions of the same files and want a stable base. Remove each of the six rules at its `house-style.md` site, then propagate to the mirrored consumers. Three of the six removals (negative parallelism, hyphenated pairs, Title Case) have a `dsc-ai-tell` regex, so each of those deletes the regex in `design-mechanical-checks.py`, its assertions in `test_dsc_ai_tell.py`, and its fixture lines in `dsc-ai-tell-fixture.md` in one change — deleting the regex while leaving the fixture and assertion breaks the test suite at build time. The other three (roundabout negation, closing phrases, curly quotes) touch prose consumers only. Edit the always-loaded `ai-tells/SKILL.md` `description:` frontmatter in the same pass, since it hard-codes three of the removals by name and costs context every session until it changes.

Rename `TL;DR` to a `### Summary` sub-heading across every site that hard-codes the spelling (D4). Add `"summary"` to `MANDATORY_OR_FORM_SUBHEADINGS` in the same change as the shape regex, or the same-shape sibling check false-positives on every well-formed `### Summary` design. Keep both spellings accepted in the shape regexes, following the D11 both-spellings precedent, and pin the acceptance with new shape tests modeled on `test_design_mechanical_checks_d11.py`.

Edit the agent roster (D3/D6): move `readability-auditor` and `comprehension-review` to Sonnet and give each its persona (target reader; time-constrained reviewer with hybrid output), add the Opus-pin note and the technical-writer voice mandate to `design-author`, and drop the removed-rule criteria from `review-workflow-writing-style.md` while adding its D10 BLUF criteria. The persona recast must preserve S4: exactly one prose-AI-tell-axis owner (the target reader).

Fold the book-rule transfer and the dual-register guard into `design-document-rules.md` (D8/D9): the eight voice rules, the two precedence rankings, and the register boundary that keeps records, summaries, footers, and diagrams registry-terse. Add the link-staleness re-read reminder to the `edit-design` mutation discipline. Harden the section BLUF lead (D10) at its three named sites, placing the exemplar before/after pair beside the existing § Orientation worked exemplar, and anchor the self-check BLUF item by name because the removals renumber the list.

Throughout, every edit lands under `_workflow/staged-workflow/.claude/**`; the branch diff must touch no live `.claude/**` path. The six invariants to preserve are listed under `## Invariants & Constraints`.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster
here: one entry per step with description, `risk:` tag, an optional
`size:` clause, and a `[ ]` status checkbox. Immutable after Phase A
except the status checkbox flip and the optional `commit:` annotation. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed
step, identified by step number + commit SHA. Empty at Phase 1. -->

## Validation and Acceptance
Track-level behavioral acceptance criteria:

- The `dsc-ai-tell` test suite (`test_dsc_ai_tell.py` against its fixture) is green after the three regex removals, with no assertion or fixture line still referencing a deleted regex.
- New shape tests pin that `**TL;DR.**`, `### TL;DR`, and `### Summary` all pass the section-shape regexes, and that a well-formed `### Summary` design does not trip the same-shape sibling similarity check (the `MANDATORY_OR_FORM_SUBHEADINGS` guard).
- Grep over the staged agent definitions shows exactly one owner of the prose AI-tell axis (the target reader / `readability-auditor`); no other agent claims it.
- The three removed rules named in the always-loaded `ai-tells/SKILL.md` `description:` (negative parallelism, Title Case headings, closing phrases) are gone, while "adjective triads" remains.
- The two D10 BLUF-hardening rules are present at their three acceptance sites (staged `house-style.md` § BLUF lead / § Orientation with the exemplar pair, `review-workflow-writing-style.md` BLUF criteria, and the by-name self-check BLUF item).
- `git diff --name-only` against the branch point shows no live `.claude/**` path changed; every edit is under `_workflow/staged-workflow/.claude/**`.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Often empty. -->

## Interfaces and Dependencies
This is a single-track change: the whole planned work is Track 1, so there are no inter-track dependencies. All implementation edits land as staged copies under `_workflow/staged-workflow/.claude/**` mirroring the live paths below (copy-then-edit on first touch, §1.7(e)).

**In-scope files, with what changes in each:**

1. `.claude/output-styles/house-style.md` — remove the six disguise-only rules (D1/D7); swap the § Voice and tone writer persona to a technical writer (D5); rename the § Navigability `TL;DR` shape to `### Summary` (D4); add the § BLUF lead / § Orientation hardening rules plus the exemplar pair and the self-check BLUF item (D10); renumber the self-check list after the removals.
2. `.claude/output-styles/house-conversation.md` — remove the mirrored disguise-rule lines (D1); change the chat reader from senior engineer to mid-level developer.
3. `.claude/agents/readability-auditor.md` — Sonnet pin; target-reader persona; persona-voiced structured findings (D3/D6/D2).
4. `.claude/agents/comprehension-review.md` — Sonnet pin; time-constrained-reviewer persona; hybrid narrative-plus-findings output; its `TL;DR` structural-finding site (D3/D6/D2/D4).
5. `.claude/agents/design-author.md` — Opus pin note (never Fable); technical-writer voice mandate (D3/D5).
6. `.claude/agents/review-workflow-writing-style.md` — drop the removed-rule criteria (D1/D7); add the D10 BLUF criteria; the adjective-triads rule stays.
7. `.claude/scripts/design-mechanical-checks.py` — delete `NEGATIVE_PARALLELISM_RE`, `NEGATIVE_PARALLELISM_TRAILING_RE`, `HYPHENATED_PAIR_CLUSTER_RE`, and the Title-Case check (D1); `section_has_tldr` gains `### Summary`; `SHAPE_EXEMPT_SECTION_NAMES` and `MANDATORY_OR_FORM_SUBHEADINGS` gain `"summary"` (D4).
8. `.claude/scripts/tests/test_dsc_ai_tell.py` — remove the assertions of the deleted regexes (D1); the regex, test, and fixture are one unit.
9. `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md` — remove the fixture lines that fed the deleted regexes (D1).
10. dsc shape tests — add both-spellings acceptance cases for the Summary rename, following the `test_design_mechanical_checks_d11.py` both-spellings precedent (new file or an extension; Phase A decides placement) (D4).
11. `.claude/skills/ai-tells/SKILL.md` — edit the always-loaded `description:` frontmatter that names three removed rules; update the body catalogue entries (D1).
12. `.claude/workflow/design-document-rules.md` — `### Summary` shapes and templates (D4); the book-rule transfer and the two precedence rankings (D9); the dual-register boundary (D8); the `dsc-ai-tell` catalogue row (D1).
13. `.claude/workflow/prompts/design-review.md` — the Summary structural finding and TOC row (D4); the hybrid output contract for the comprehension gate (D2).
14. `.claude/skills/edit-design/SKILL.md` — the roughly five `TL;DR` sites (D4); the D9 link-staleness re-read reminder on section-move/remove/rename.
15. `.claude/workflow/planning.md` — its `TL;DR` site (D4).
16. `.claude/workflow/prompts/create-final-design.md` — its `TL;DR` site (D4).
17. `.claude/skills/review-workflow-pr/SKILL.md` — its `TL;DR` site (D4).
18. `.claude/workflow/conventions.md` — the §1.5 tier pointers and its `TL;DR` site (D4).
19. `CLAUDE.md` — the § Writing Style negative-parallelism parenthetical (D1).

**Out of scope:** legacy committed designs under `docs/adr/**` (no backfill); `absorption-check.md` and `fidelity-check.md` (unchanged, personless); the live `.claude/**` tree until Phase-4 promotion; this branch's own `_workflow/` authoring artifacts (written under the current live rules).

**Sizing:** ~19 in-scope files — within the ~20–25 ceiling and above the ~12 floor. The change is complete: no further autonomous unit exists to pack, so the single-track argumentation gate is satisfied by "this is the whole change".

## Invariants & Constraints
<!-- Plan-at-start, combined section (D9): per-track testable constraints
and testable invariants; each invariant becomes a test assertion in the
relevant step. Process-only constraints go to Context and Orientation or
the Decision Log instead. -->

- **Removal completeness.** After the triple deletion, no removed-pattern regex, fixture line, or assertion remains in the `dsc-ai-tell` surfaces, and `test_dsc_ai_tell.py` is green. (Assert: run the test suite; grep the three files for the removed regex names returns nothing.)
- **Both-spellings acceptance.** `**TL;DR.**`, `### TL;DR`, and `### Summary` all pass the section-shape regexes. (Assert: new shape tests, modeled on `test_design_mechanical_checks_d11.py`.)
- **`MANDATORY_OR_FORM_SUBHEADINGS` contains `"summary"`.** A well-formed `### Summary` design does not false-positive on the same-shape sibling similarity check. (Assert: a shape test that feeds a multi-`### Summary` design and expects no consolidation finding.)
- **Single prose-axis owner (S4).** Exactly one staged agent definition claims the prose AI-tell axis (the target reader / `readability-auditor`). (Assert: grep over the staged agent definitions.)
- **Live-tree isolation (§1.7 / I6).** The branch diff touches no live `.claude/**` path; every edit is under `_workflow/staged-workflow/.claude/**`. (Assert: `git diff --name-only` scope check.)
- **D10 rules present at three sites.** The self-contained-lead and body-stands-without-the-lead rules are greppable in the staged `house-style.md`, `review-workflow-writing-style.md`, and the self-check BLUF item.
