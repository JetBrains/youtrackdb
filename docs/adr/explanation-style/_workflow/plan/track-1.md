<!-- workflow-sha: 26f990ed824d113fdb5fcb930361e69378f0f12a -->
# Track 1: Conventions opt-out, Orientation rule, and the atomic subset sync

## Purpose / Big Picture
After this track lands, the house style carries an always-on `## Orientation` floor against too-terse prose, that rule is the fifth member of the AI-tell subset at every one of the ~50 sites that name the subset, and this branch is sanctioned to edit the workflow rules live so they self-apply.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Lands the `§1.7` prose-rule opt-out and the three Phase-3A criteria-switch extensions first (so the branch's own live edits are sanctioned), adds the always-on `## Orientation` rule to `house-style.md` and generalizes `### Explanatory register`, then flips the AI-tell subset four→five across the ~50 sites that name it as a closed set. The subset flip is atomic — the four-vs-five window closes before this track's Phase C (D1).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- The track-canonical live decision carrier (D7). Full inline Decision
Records this track owns, seeded from the frozen design.md. The track file
is the live authority; the design.md seed is historical provenance. -->

#### D1: Faithful full sync of the ~50-site subset enumeration
- **Alternatives considered**: (A) faithful full sync — every site that names the AI-tell subset as a closed set becomes five (chosen); (B) centralize-then-add — replace the ~50 duplicated enumerations with one canonical list plus a pointer; (C) issue-literal — update only the ~10 sites the two issues named.
- **Rationale**: matches the project's "the canonical subset must move together" sync discipline. The drift risk that made the issue defer is gone — plan-slimization merged at `26f990ed82` and this branch sits on top with nothing in flight, so a large diff carries low rebase-conflict cost. The count bump is **semantic, not numeric**: the 30-site blurb says "the four banned-section heading slugs," but `## Orientation` is a positive floor, not a ban, so "five banned-section slugs" would be false. The blurb is reworded once, canonically, and pasted byte-identically. (B) is scope expansion beyond the two issues; the ~50 inline copies exist for **per-spawn self-containedness** (a sub-agent reads its blurb without opening another file), so centralizing trades that for a per-spawn file read — a real boot-cost across the ~30 agent/prompt files. (C) leaves ~40 blurbs at four-of-five, which `review-workflow-consistency` and the governance greps flag as drift.
- **Risks/Caveats**: ~50 hand edits, no generator (`workflow-reindex.py` only rebuilds TOC and stamps; only the hook holds blurb text). The atomic-sync constraint (A4): the flip lands as one commit, or at minimum inside this track with the four-vs-five window closed before Phase C — under live-edit (D5), any split that commits the canonical sites at five while blurbs still read four leaves a window `review-workflow-consistency` flags. One chat-blurb site (`review-workflow-pr/SKILL.md:44-45`) hard-wraps the find string across a line break and needs a hand-edit. `test_house_style_hook.py:694-697` pins the section strings and gates the sync's correctness for the hook.
- **Implemented in**: this track (atomic flip; step references added during execution)
- **Full design**: design.md §"Subset sync across ~50 sites" (the canonical reworded blurb, the find/replace pair for the chat blurb, the modulo-line-wrap note)

#### D2: Orientation joins both subset tiers (chat + code-comment)
- **Alternatives considered**: chat-only membership; chat + `*.java`/`*.kt` code-comment membership (chosen).
- **Rationale**: the issues scope Orientation to "chat and every prose surface." `conventions.md §1.5` has no chat-scale table row — its two tiers are full-style Markdown and the `*.java`/`*.kt` AI-tell subset; the chat subset lives in the per-file blurbs and `house-conversation.md`. So Orientation joins the chat carriers (the 11 chat blurbs + `house-conversation.md`) and the code-comment carriers (the `§1.5` Tier-B row + the hook `tier_b_body`). A Javadoc reader has the code open by definition, so YTDB-1106's literal "too terse to follow without opening the code" test does not transfer — the code-comment surface gets a **restated criterion**: rationale comments must not assume context *outside the file* (distant call-site behavior, issue history, reviewer-thread knowledge) and must gloss the project-specific entity the rationale turns on.
- **Risks/Caveats**: the restatement must not read as "add tutorial comments" — it bans out-of-file assumptions, not in-file terseness. A deliberate tier difference recorded once in the `§1.5` table is a documented scope split, not the four-vs-five enumeration drift D1 forbids (D1 binds sites enumerating the *same* subset).
- **Implemented in**: this track (`§1.5` Tier-B row + the hook `tier_b_body`)
- **Full design**: design.md §"The Orientation rule" (two-tier membership, the code-comment restatement)

#### D3: Generalize § Explanatory register into ## Orientation
- **Alternatives considered**: leave both (the duplication the issue flags); cross-link only without generalizing (still two full statements); one top-level always-on rule plus a design-specific specialization that links up (chosen).
- **Rationale**: one general rule plus one specialization that points at it is maintainable; two parallel statements of the same principle drift. `## Orientation` becomes the single always-on statement; `### Explanatory register` (today under `## Document-shape rules`, design/ADR only) reduces to a design-doc specialization that cross-links up, keeping only its mechanism-overview nuance and mid-level-reader completeness bar.
- **Risks/Caveats**: generalizing leaves the file self-contradictory unless three reconciliations land together — (1) rewrite `house-style.md:379` so `## Orientation` is not excluded from issue/PR/status prose (today it scopes the document-shape family to "BLUF alone"); (2) give `## Orientation` its own finding category (the current rule cites the design-only `§ Why-before-what`); (3) move the Self-check entry out of item 8's "design/ADR only" bracket into an always-on item. The anti-padding clause is load-bearing — without it the rule is abusable as license to pad, which `§ Voice and tone` forbids.
- **Implemented in**: this track (`house-style.md`)
- **Full design**: design.md §"The Orientation rule" (the three-edit reconciliation set)

#### D5: No staging — live-edit all surfaces
- **Alternatives considered**: full staging (defers all self-application to post-merge, so the rule-adding branch is the one branch never checked against its own rules); hybrid stage-covered-only (neither clean isolation nor clean self-application); user-waiver without amending `§1.7` (a one-off exception, not a reusable rule); live-edit sanctioned by a `§1.7` amendment (chosen, via D6).
- **Rationale**: the change alters prose rules, prompt text, one judgment-layer reviewer block, and one contained regex set — it changes **no `_workflow/**` artifact schema**, so the destabilize-the-branch's-own-machinery hazard `§1.7` guards against does not exist. The largest surfaces (`house-style.md`, `house-conversation.md`, `design-mechanical-checks.py`) sit outside `§1.7`'s covered prefixes already, so staging only the workflow/skills/agents blurbs buys neither isolation nor self-application. Self-application is the goal: the branch's own `design.md`, track files, and chat are held to the new rules during the branch.
- **Risks/Caveats**: committing live `.claude/workflow|skills|agents` edits advances HEAD past the artifacts' stamp base, so the startup drift gate fires every subsequent session. The principled resolution is **stamp-advance** (A2): after the last workflow-editing commit, run `/migrate-workflow` (a no-op replay over prose-only commits that reduces to advancing every artifact stamp to HEAD, `§4.8`), re-arming the gate for real develop drift; Suppress is the interim answer until then. The two new `dsc-ai-tell` regexes ship **demotable** (A9) — a blocker-severity false positive during Phase-4 self-application would exit 1 and block the loop.
- **Implemented in**: this track (lands with the `§1.7` opt-out); the regex deliverable and its demotable severity-application surface in Track 2
- **Full design**: design.md §"The §1.7 opt-out" (live-edit substance, stamp-advance, demotable regex)

#### D6: Amend §1.7 with a prose-rule self-application opt-out
- **Alternatives considered**: shape (i) keep-marker-plus-rider (needs a bootstrap fix and edits to execution-procedure files — `implementer-rules.md`, `step-implementation.md` — that criterion (2) says must stay staged, so it is self-defeating); user-waiver-only (one-off, not reusable); leaving `§1.7` unchanged and omitting the marker (the A1 convention violation, and it silently disables the reviewer-criteria switch); shape (ii) distinct opt-out marker (chosen, lowest surface).
- **Rationale**: the `§1.7(b)` workflow-modifying marker switches on **two** roles — the staging mechanism (write-routing, the pre-commit gate, staged-delta prep, the Phase-4 promotion guard) **and** the reviewer-criteria re-pointing (the three Phase-3A "Workflow-machinery criteria" blocks). The opt-out must disable the first and keep the second. Shape (ii) carries a **distinct opt-out marker** in `### Constraints`, not the workflow-modifying marker: with the workflow-modifying marker absent, every staging consumer already defaults to live (write live, no staged delta, promotion guard finds no staged subtree and skips), so the staging half needs **no** consumer edits and there is **no bootstrap deadlock**. The only rewiring is extending the **three** criteria-switch blocks (`technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282`) to fire on the workflow-modifying marker **OR** the opt-out marker. Opt-out criteria are **consumer class, not intent** (A12): (1) no `_workflow/**` schema change, AND (2) every edited file's in-branch consumer is judgment-layer (style rules, review criteria, prompt blurbs, reviewer blocks). This branch's edits all qualify.
- **Risks/Caveats**: this track's own Phase-A review trio runs **before** the criteria-switch extensions land, so for the branch's own largest prose track the live review prompts still gate criteria on the absent workflow-modifying marker (A14). The fix is the in-plan `### Constraints` opt-out note, which every reviewer reads: it acknowledges the staging deviation (so a reviewer reading unamended `§1.7` sees an acknowledged deviation, not a phantom reference) and re-points the review criteria in-plan. The prompt-file extensions then serve future opt-out branches. Note `track-code-review.md:250-260` is staging-delta **prep** (inert without the marker), not a criteria switch — Phase-C dimensional prose coverage is diff-keyed via `review-agent-selection.md`, so it needs no marker (A15).
- **Implemented in**: this track, ordered first (`§1.5` sync + `§1.7` opt-out + the three criteria-switch extensions land together; the opt-out + criteria-switch in the branch's first workflow-editing commit)
- **Full design**: design.md §"The §1.7 opt-out" (the marker shape, consumer-class criterion, landing order, in-plan re-pointing)

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

## Context and Orientation

The house style is one declarative rule source — `.claude/output-styles/house-style.md` — read by four consumers and restated inline by ~50 files. Today the **AI-tell subset** (the set of house-style sections that apply not only to full Markdown but also at chat scale and code-comment scale) is four sections: `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`. This track makes it five by adding `## Orientation`.

State of the surfaces this track touches (verified on the branch tip `26f990ed82`):

- **`house-style.md`** — `## Banned analysis patterns` at line 105, `### Mechanism traces and inline citations` at 360, `## Document-shape rules` at 377 with the scoping sentence at 379, `### Why-before-what` at 403, `### Explanatory register` at 427, `## Self-check` at 433. Line ~20 carries the "Four readers consume these rules" paragraph and the "four AI-tell sections" count.
- **`conventions.md`** — `§1.5` (line 545) has two tiers: full-style Markdown and the `*.java`/`*.kt` AI-tell subset (the Tier-B table row at line 565); the governance grep at line 570 enumerates the four section names. There is **no** chat-scale row in `§1.5`. `§1.7` (line 832) is the staging convention; D6 amends it.
- **The three criteria-switch prompts** — each carries a "Workflow-machinery criteria (workflow-modifying plans)" block (`technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282`) gated on the `§1.7(b)` workflow-modifying marker, plus a separate "Staged-read precedence" block. Only the **criteria** block gets the opt-out-marker OR-extension; the staged-read block stays gated on the workflow-modifying marker (under the opt-out there is no staging, so reading live is already the default).
- **The ~50-site inventory** (pinned in research-log S1, re-verified this session — `grep -rln` counts hold at 50/30/11): 30 files carry the "four banned-section heading slugs" blurb; 11 carry the chat "follows the AI-tell subset of" blurb; the remainder are the three canonical sites (`house-style.md`, `house-conversation.md`, `conventions.md §1.5`), the hook (`house-style-write-reminder.sh` `tier_b_body`), `test_house_style_hook.py`, the two governance greps (`readability-feedback/SKILL.md:54` and `conventions.md:570`), and the `ai-tells/SKILL.md` catalogue table.

Non-obvious terminology: **orientation register** (a surface a human reads cold carries its orientation in the text — gloss each entity, linearize each causal chain) vs **registry-terse** (a decision/research log stays terse and pushes recurring entities into a shared vocabulary block). The `## Orientation` rule describes this relationship; without it the rule reads as "write more" and contradicts `§ Voice and tone`'s bias toward less text.

## Plan of Work

Sequence the edits so the opt-out and the rule exist before anything depends on them, and so the subset flip is atomic.

1. **Opt-out and criteria-switch first (D6, the branch's first workflow-editing commit).** Add the `§1.7` opt-out clause and the distinct opt-out marker definition to `conventions.md §1.7`. Extend the three "Workflow-machinery criteria" blocks (`technical-review.md`, `risk-review.md`, `adversarial-review.md`) to fire on the workflow-modifying marker OR the opt-out marker. This sanctions every subsequent live edit on the branch and lands before the subset flip touches those same three prompt files.
2. **The Orientation rule + generalization (D3).** Add the top-level `## Orientation` section to `house-style.md` (reader = the `§ Voice and tone` reader; three moves; the anti-padding clause; the YTDB-1106 F84 exemplar as positive model). Reduce `### Explanatory register` to a design-specific specialization that cross-links up. Apply the three reconciliations together: rewrite line 379, give `## Orientation` its own finding category, move the Self-check entry to an always-on item. The rule text must exist before any enumeration names it.
3. **Code-comment-tier membership (D2).** Add `## Orientation` to the `§1.5` Tier-B row and the hook `tier_b_body` with the code-comment restatement (no out-of-file assumptions; gloss the project-specific entity).
4. **The atomic subset flip (D1).** Flip four→five across all ~50 sites in one commit (or with the window closed before Phase C): the 30 "banned-section heading slugs" blurbs take the canonical reworded sentence byte-identically; the 11 chat blurbs take the find/replace pair (and `house-conversation.md`'s "four → five" count bumps); the two governance greps gain `Orientation`; the `ai-tells` catalogue gains an Orientation row; `test_house_style_hook.py` updates its pinned section list. Hand-edit the one hard-wrapped chat-blurb site (`review-workflow-pr/SKILL.md:44-45`).

Ordering constraints: step 1 before everything else (sanctions live-edit, lands criteria-switch before the flip rewrites those files). Step 2 before step 4 (the rule must exist before enumerations name it). Step 4 atomic relative to Phase C. Invariants to preserve: no site reads four-of-five after Phase C; the opt-out disables staging only (reviewer-criteria stays on).

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed
step. Empty at Phase 1. -->

## Validation and Acceptance

Track-level behavioral acceptance:

- After this track, `grep -rln 'banned-section heading slugs' .claude/ CLAUDE.md` returns 30 files and **every** one names `## Orientation` in the reworded blurb; the two governance greps (`readability-feedback/SKILL.md`, `conventions.md:570`) enumerate `Orientation`; no site enumerates the subset as four-of-five.
- `house-style.md` carries a top-level `## Orientation` section with the three moves and the anti-padding clause; `### Explanatory register` cross-links up to it; line 379, the finding category, and the Self-check entry are reconciled so the file is internally consistent.
- `conventions.md §1.7` carries the opt-out clause and the distinct opt-out marker; the three Phase-3A criteria-switch blocks fire on the opt-out marker as well as the workflow-modifying marker.
- `test_house_style_hook.py` passes against the hook's updated five-name subset list.
- `review-workflow-consistency` over this track's diff finds no four-vs-five enumeration inconsistency.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim
as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths
once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In-scope files** (~50; substantive-edit files named with anchors, plus the enumeration-flip set):
- `.claude/output-styles/house-style.md` — `## Orientation` rule, generalize `### Explanatory register`, three reconciliation edits (lines 379, finding category, Self-check), line-~20 count + Self-check subset entry.
- `.claude/output-styles/house-conversation.md` — chat register four→five + the "four → five" count.
- `.claude/workflow/conventions.md` — `§1.5` Tier-B row + code-comment restatement + the line-570 governance grep; `§1.7` opt-out clause + marker.
- `.claude/workflow/prompts/technical-review.md` (`:113`), `risk-review.md` (`:110`), `adversarial-review.md` (`:282`) — criteria-switch OR-extension; these three are also subset-blurb sites (flip four→five).
- `.claude/hooks/house-style-write-reminder.sh` — `tier_b_body` four→five + code-comment restatement.
- `.claude/scripts/tests/test_house_style_hook.py` — update the pinned subset section list (`:694-697`).
- `.claude/skills/ai-tells/SKILL.md` — catalogue row (`:19-27`); `.claude/skills/readability-feedback/SKILL.md` — governance grep (`:54`).
- The remaining 30 "banned-section heading slugs" blurb files and 11 chat-blurb files (`grep -rln` rosters in research-log S1), including the hard-wrapped `review-workflow-pr/SKILL.md:44-45`.

**Out-of-scope** (Track 2): `design-review.md` (the `### Prose AI-tell additions` cold-read block), `design-mechanical-checks.py` (the two regexes), `test_dsc_ai_tell.py`. **Out-of-scope** (any track): every `_workflow/**` artifact schema (track-file sections, resume-state fields, drift-gate format, stamp format) — the opt-out's criterion (1) forbids touching it.

**Overlap note (planning.md §"Justify any overlap-split").** `readability-feedback/SKILL.md` is touched by this track (the governance grep, part of the atomic flip) and by Track 2 (the Rule sync map design-review row for the new cold-read block). The grep edit belongs with the atomic flip; the Rule-sync-map row references Track 2's block and would forward-reference a non-existent block if pulled forward. Track 2 is ordered adjacent to this track to minimize rebase distance on the shared file.

**Inter-track dependency**: Track 2 depends on this track — its cold-read block scans the too-terse direction (the `## Orientation` rule must exist) and its live edits rely on the `§1.7` opt-out.

**Sizing justification (argumentation gate, over-ceiling).** This track is ~50 in-scope files, over the ~20-25 split-candidate ceiling. It cannot split further without reopening the four-vs-five window D1 forbids: the subset enumeration must flip atomically, and the `§1.7` opt-out + criteria-switch must land first (D6). The one independently-mergeable seam — the YTDB-1084 over-dense enforcement, which names no subset enumeration — is split off as Track 2.
