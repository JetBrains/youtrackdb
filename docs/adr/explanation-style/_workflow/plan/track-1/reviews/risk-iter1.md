<!--
manifest:
  role: reviewer-risk
  phase: 3A
  track: "Track 1: Conventions opt-out, Orientation rule, and the atomic subset sync"
  iteration: 1
  verdict: changes-requested
  findings: 4
  evidence_base:
    exposure: 2
    assumption: 4
    testability: 1
  index:
    - id: R1
      sev: should-fix
      anchor: "### R1 "
      loc: "track-1.md D1 risks + Validation/Acceptance bullet 1; conventions.md:570; readability-feedback/SKILL.md:54"
      cert: "Assumption: the two governance greps gain Orientation (D1)"
      basis: "grep-term collision: bare 'Orientation' matches '## Context and Orientation' across track files; acceptance asserts 'returns 54' against a grep D1 mutates"
    - id: R2
      sev: should-fix
      anchor: "### R2 "
      loc: "implementation-plan.md D6 + Constraints note; track-1.md D6; conventions.md:899 (the workflow-modifying marker it parallels)"
      cert: "Assumption: a distinct opt-out marker re-points the three criteria-switch blocks"
      basis: "no canonical opt-out marker prefix string is pinned; three blocks + in-plan note + §1.7 amendment must key off one byte-identical prefix or the switch silently dead-ends for future branches"
    - id: R3
      sev: suggestion
      anchor: "### R3 "
      loc: "track-1.md Plan-of-Work step 2 + D3 reconciliation; house-style.md:377/379"
      cert: "Assumption: the line-379 scoping sentence is the D3 reconciliation target"
      basis: "## Document-shape rules heading is at :377; the scoping sentence the edit targets is at :379 — content reference is unambiguous, line number drifts under upstream edits"
    - id: R4
      sev: suggestion
      anchor: "### R4 "
      loc: "track-1.md D5 stamp-advance + ordering constraints; startup-precheck.sh:273 WORKFLOW_PATHSPECS"
      cert: "Exposure: stamp-advance re-arm must follow the last in-pathspec commit"
      basis: "drift pathspec is workflow|skills|agents only; the last in-pathspec commit, not the last branch commit, is the /migrate-workflow trigger point — ordering note should name this"
-->

# Risk review — Track 1, iteration 1

## Evidence base

This track touches no Java/Kotlin. Every reference is a Markdown/shell/python path, a `§`-anchor, a line number, or a unique literal, so grep + Read give full reference accuracy (no PSI caveat applies, per the spawn note). Per the `### Constraints` `§1.7` opt-out note (D6), this plan is treated as workflow-modifying for review-criteria purposes: the five prose criteria (rule coherence, instruction completeness, prompt-design soundness, context-budget impact, dependent-prompt breakage) supersede the Java/storage risk lenses, and every `.claude/**` read resolves against the live tree (no `_workflow/staged-workflow/` subtree exists — that is the documented D6 posture, not a finding).

The track's verifiable foundational facts all hold against branch tip `75db45f2a8`:
- Governance grep returns exactly **54** files; the narrow `banned-section heading slugs` blurb count is **30**; the chat `AI-tell subset of` count is **11** — all three match the plan's stated inventory.
- The three criteria-switch blocks sit at the claimed lines (`technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282`), each structured as a `Staged-read precedence` block immediately followed by a `Workflow-machinery criteria` block, both independently gated on the same `§1.7(b)` canonical-marker check. This confirms D6's central feasibility claim: the OR-extension can target the criteria block alone and leave the staged-read block on the workflow-modifying marker.
- The three grep-missed flip sites verify byte-for-byte: `commit-conventions.md:191-194` (line-wrapped four-name set), `implementer-rules.md:1102-1105` (variant phrasing "the four section slugs that make up the Tier-B AI-tell subset"), `review-workflow-pr/SKILL.md:44-45` (hard-wrapped chat blurb). `test_house_style_hook.py:694-697` pins `TIER_B_HEADINGS` as the four-string list as claimed.
- `house-style.md` structural anchors verify: `### Explanatory register` at :427 under `## Document-shape rules`; `### Why-before-what` at :403 (the design-only finding category the rule currently cites at :431); Self-check item 8 at :444 inside the "design/ADR only" bracket; the line-20 "four readers / four AI-tell sections" count site.

### Exposure: the §1.7 opt-out re-points the Phase-3A reviewer-criteria dispatch (D6)
- **Track claim**: extend the three criteria-switch blocks to fire on the workflow-modifying marker OR a distinct opt-out marker, disabling staging while keeping reviewer-criteria re-pointing on.
- **Critical path trace**:
  1. Each block (`technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282`) opens `**Workflow-machinery criteria (workflow-modifying plans):** When the plan's ### Constraints carries the canonical §1.7(b) workflow-modifying marker sentence, ...`
  2. Immediately above each sits a parallel `**Staged-read precedence ...** When the plan's ### Constraints carries the canonical §1.7(b) workflow-modifying marker sentence, resolve every read ... through §1.7(d) ...` — the two blocks are physically adjacent and separately gated.
  3. The marker the blocks match on is defined once at `conventions.md:891-921` `§1.7(b)`: stable-prefix match on `This plan is workflow-modifying:`, case-sensitive, older spellings recognized identically.
- **Blast radius**: if the OR-extension is mis-scoped to the staged-read block as well, future workflow-modifying branches carrying the opt-out marker would read live where they should read staged — but the opt-out's own posture is "no staging," so that is harmless here; the real hazard is the inverse: a malformed extension that broadens the *workflow-modifying* match (not just adds an OR-arm) could let a non-opt-out plan skip staging. The blocks are well-isolated (two separate gated blocks, one shared marker definition), so the surgical change is low-risk *if* the opt-out marker has a pinned stable prefix to match on (see Assumption R2).
- **Existing safeguards**: `§1.7(b)` stable-prefix matching is case-sensitive and forward-compatible; `§1.7(c)` splits detection into two independent signals (Constraints declaration drives the gate; staged-subtree presence drives the Phase-4 promotion guard), so an absent workflow-modifying marker already defaults every staging consumer to live with no edits — D6's "no bootstrap deadlock" claim is sound and verified.
- **Residual risk**: MEDIUM — the extension is mechanically clean, but the opt-out marker string it matches on is not pinned (R2).

### Exposure: stamp-advance re-arm of the startup drift gate (D5)
- **Track claim**: after the last workflow-editing commit, `/migrate-workflow` runs a no-op replay reducing to a stamp-to-HEAD advance (`§4.8`), re-arming the drift gate.
- **Critical path trace**:
  1. Drift detection lives in `workflow-startup-precheck.sh --mode full`; the workflow pathspec is `WORKFLOW_PATHSPECS=".claude/workflow/ .claude/skills/ .claude/agents/"` (`:273`).
  2. Phase 1 walks `_workflow/**` artifact stamps; Phase 2 folds to `BASE_SHA` and runs `git log $BASE_SHA..HEAD` over those pathspecs (`:544-552`). All `_workflow/**` artifacts on this branch carry `<!-- workflow-sha: 26f990ed824d... -->` (the plan-slimization merge base).
  3. Any commit touching `.claude/workflow|skills|agents` makes the range non-empty → `drift.detected == true`, `kind == "stamped"` → resolution prompt every session.
  4. `/migrate-workflow` `§4.8` "Final stamp-to-HEAD batch ... batch-rewrite every artifact's line-1 stamp to HEAD; already-at-HEAD artifacts are benign no-ops" — unconditional, runs after the replay loop regardless of per-commit classification.
- **Blast radius**: if `/migrate-workflow` is not run, the drift gate fires the resolution prompt at every subsequent session start, forcing a Migrate/Defer/Suppress pick. Suppress is the documented interim. No data loss; pure session-friction. The resolution is sound and reachable.
- **Existing safeguards**: `§4.8` advance is unconditional; the replay loop's `§4.3` classify + `§4.4` manual-review halt is a partial net but the stamp-advance does not depend on every commit classifying as no-op. The "no-op replay" framing is accurate: prose-only commits replay benignly and stamps advance.
- **Residual risk**: LOW — the resolution is genuine. One ordering nuance worth pinning (R4): the trigger point is the last commit *in the drift pathspec*, not the last branch commit.

### Assumption: the two governance greps gain `Orientation` (D1) and acceptance still asserts "returns 54"
- **Track claim**: D1 — "the two governance greps gain `Orientation`"; Validation/Acceptance bullet 1 — "the governance grep ... returns 54 files."
- **Evidence search**: grep — current 4-string grep returns 54; comm against `## Orientation` vs the 4-string match set; comm against bare `Orientation`.
- **Code evidence**: `conventions.md:570` and `readability-feedback/SKILL.md:54` both hold the 4-string grep `'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline'`. The anchored `## Orientation` term collides with nothing today (empty comm). The **bare-word** `Orientation` term collides with `## Context and Orientation` — a section heading across at least 9 `.claude/workflow/**` files plus every track file under `docs/adr/**`.
- **Verdict**: UNVALIDATED
- **Detail**: two tensions. (1) D1 says the grep gains `Orientation` but the acceptance bullet asserts the grep "returns 54" — these describe two different greps (the pre-flip 4-string vs the post-flip 5-term). The post-flip count must be re-derived, not assumed to stay 54. (2) D1's wording "gain `Orientation`" does not pin the exact term. If the implementer adds the bare word `Orientation`, the grep collides massively with `## Context and Orientation` and stops being a subset-pointer enumerator. The correct term is the anchored heading `## Orientation`.

### Assumption: a distinct opt-out marker re-points the three criteria-switch blocks (D6)
- **Track claim**: D6 — "carries a **distinct opt-out marker** in `### Constraints` ... extend the three criteria-switch blocks to fire on the workflow-modifying marker OR the opt-out marker."
- **Evidence search**: grep over the plan and design for a canonical opt-out marker sentence/prefix string, paralleling the workflow-modifying marker's `conventions.md:899` canonical sentence.
- **Code evidence**: `conventions.md:899` pins the workflow-modifying marker as a fixed canonical sentence (`This plan is workflow-modifying: it edits ...`) matched by stable prefix `This plan is workflow-modifying:`. The plan describes the opt-out marker abstractly (implementation-plan.md:64/114, design.md:64) but specifies **no canonical sentence or stable prefix**. The closest candidate is the `### Constraints` note's prose lead "This plan takes the `§1.7` prose-rule self-application opt-out" (implementation-plan.md:27), which is not declared as *the* marker.
- **Verdict**: UNVALIDATED
- **Detail**: the three criteria-switch OR-extensions must match the opt-out marker on a byte-identical stable prefix; the same prefix must appear in the plan's `### Constraints`. With no pinned canonical string, Phase B invents one, and any drift between the prefix the blocks match and the prefix the Constraints note carries silently dead-ends the criteria switch for future opt-out branches (this branch's own Track-1 reviews run before the blocks land, so the gap surfaces only later — exactly the A14 self-application gap). The mechanism is correct; the missing artifact is the canonical marker string.

### Assumption: the D3 reconciliation targets `house-style.md:379`
- **Track claim**: D3 / Plan-of-Work step 2 — "rewrite `house-style.md:379` so `## Orientation` is not excluded from issue/PR/status prose."
- **Evidence search**: grep for the document-shape heading and the scoping sentence.
- **Code evidence**: `## Document-shape rules (design / ADR-specific)` is at `:377`; the scoping sentence the edit targets is at `:379`.
- **Verdict**: VALIDATED (content), with a line-number-drift caveat
- **Detail**: the content reference ("the scoping sentence that excludes the document-shape family from issue/PR/status prose") is unambiguous and resolvable. The bare line number `:379` drifts if any earlier edit in step 2 (e.g. inserting the `## Orientation` section, which the same step does) shifts lines. Step 2 inserts `## Orientation` AND rewrites :379 in the same step — line-number arithmetic must be re-checked after the insertion, or the edit should key off the sentence text.

### Assumption: D1's atomic-flip window can close inside Track 1 before Phase C
- **Track claim**: D1 / Constraints — the four→five flip lands as one commit "or at minimum inside Track 1 with the four-vs-five window closed before Track 1's Phase C," because `review-workflow-consistency` reads cross-file beyond the diff.
- **Evidence search**: grep for the consistency reviewer's scope and the per-step vs track-level review routing.
- **Code evidence**: `review-workflow-consistency` is the cross-file consistency agent; the track's acceptance bullet 5 asserts it "over this track's diff finds no four-vs-five enumeration inconsistency." The relaxation (window may span steps within the track, closed before Phase C) is safe **because** `review-workflow-consistency` runs at Phase C (track level), not per step — so a mid-track step that flips canonical sites at five while blurbs still read four does not trip a step-level reviewer.
- **Verdict**: VALIDATED
- **Detail**: the relaxation is sound provided no *step-level* reviewer in Phase B reads cross-file for the four-vs-five inconsistency. Step-level reviewers (`review-bugs-concurrency` baseline and trigger-matched dimensional reviewers) are diff-scoped and prose-only here; none performs the cross-file subset-uniformity scan that `review-workflow-consistency` does. The window is genuinely safe to close at Phase C.

### Testability: the atomic subset flip and the opt-out criteria-switch
- **Coverage target**: 85% line / 70% branch (not directly applicable — this track ships no Java; the testable surface is `test_house_style_hook.py` and the two governance greps).
- **Difficulty assessment**: the flip's correctness is gated by `test_house_style_hook.py:694-697` (`TIER_B_HEADINGS` must update four→five) and the two governance greps. The criteria-switch OR-extension has **no automated test** — it is prompt prose, exercised only when a future opt-out branch's Phase-3A reviewer reads it. This branch's own Track-1 reviews run before the blocks land (A14), so the extension ships untested on this branch and unexercised until a future branch.
- **Existing test infrastructure**: `test_house_style_hook.py` pins the hook's subset list; `review-workflow-consistency` at Phase C is the cross-file uniformity check; the acceptance greps are runnable assertions.
- **Feasibility**: ACHIEVABLE for the flip (test + greps cover it); DIFFICULT for the criteria-switch (no test surface, deferred-exercise only). Acceptable given the in-plan `### Constraints` note carries the re-pointing for this branch, but it raises the stakes on R2 (a mis-pinned marker string would surface only in a future branch's review, far from this change).

## Findings

### R1 [should-fix]
**Certificate**: Assumption — the two governance greps gain `Orientation` (D1) and acceptance still asserts "returns 54"
**Location**: `track-1.md` D1 risks and `## Validation and Acceptance` bullet 1; `conventions.md:570`; `readability-feedback/SKILL.md:54`
**Issue**: Two coupled gaps. (a) D1 says "the two governance greps gain `Orientation`," but the acceptance bullet asserts the same governance grep "returns 54 files." These are two different greps — the pre-flip 4-string grep (returns 54 today) and the post-flip grep with a fifth term. The acceptance count is asserted against the mutated grep without re-deriving it. (b) The term to add is unspecified. The bare word `Orientation` collides with `## Context and Orientation` section headings across at least 9 `.claude/workflow/**` files plus every track file under `docs/adr/**`, which would both inflate the file count far past 54 and destroy the grep's purpose as a closed-set-pointer enumerator. Likelihood the implementer picks the wrong term: moderate, because D1's wording is loose. Impact: a broken acceptance check and a governance grep that no longer enumerates pointer sites.
**Proposed fix**: Pin the exact added term as the **anchored heading** `## Orientation` (not the bare word) in D1 and in both governance-grep edit sites. Separately, re-derive the post-flip acceptance count: state whether the acceptance assertion uses the *original* 4-string grep (still returns 54, now as a "no site reads four-of-five" negative check) or the *new* 5-term grep (re-count after the flip, since `## Orientation` is newly present in `house-style.md` and the blurb sites). Recommend keeping the acceptance check on the original 4-string grep for the file-set count and adding a separate Orientation-presence check on each closed-set enumeration (which the plan's CR1 auto-fix already half-introduced) — this avoids the count-mutation tension entirely.

### R2 [should-fix]
**Certificate**: Assumption — a distinct opt-out marker re-points the three criteria-switch blocks (D6)
**Location**: `implementation-plan.md` D6 and the `### Constraints` opt-out note (`:26-44`); `track-1.md` D6; parallels the workflow-modifying marker at `conventions.md:899`
**Issue**: D6 hinges on "a distinct opt-out marker in `### Constraints`" that the three criteria-switch OR-extensions match on, but the plan never pins the canonical marker sentence or stable prefix. The workflow-modifying marker it parallels has a fixed canonical sentence (`conventions.md:899`) matched by a declared stable prefix; the opt-out marker has only prose description. The three blocks, the in-plan `### Constraints` note, and the `§1.7` amendment must all key off one byte-identical prefix. Because this branch's own Track-1 reviews run before the blocks land (the A14 self-application gap, correctly acknowledged), any drift between the prefix the blocks match and the prefix the Constraints note carries would not surface on this branch at all — it surfaces only when a future opt-out branch's Phase-3A reviewer fails to switch criteria, far from the change that introduced the drift. Likelihood: moderate (an unpinned string invites Phase-B invention). Impact: the criteria switch silently dead-ends for future opt-out branches — the exact reviewer-criteria-dispatch destabilization this review was warranted to probe.
**Proposed fix**: Add the canonical opt-out marker sentence and its stable-prefix to the plan (D6 and the `§1.7` opt-out clause it lands), mirroring `conventions.md:899`'s shape — e.g. a fixed sentence whose stable prefix the three blocks match case-sensitively. Make the in-plan `### Constraints` note carry that exact prefix verbatim, so the note that re-points criteria for this branch and the prompt blocks that re-point criteria for future branches match the same string. State in D6 that the three OR-extensions match the opt-out prefix in addition to (not replacing) the workflow-modifying prefix, and that the staged-read block is deliberately NOT extended.

### R3 [suggestion]
**Certificate**: Assumption — the D3 reconciliation targets `house-style.md:379`
**Location**: `track-1.md` Plan-of-Work step 2 and D3 reconciliation; `house-style.md:377/379`
**Issue**: Step 2 both inserts the new `## Orientation` section into `house-style.md` and rewrites the line-379 scoping sentence, in the same step. Inserting a section shifts every line below it, so the bare `:379` reference is stale the moment the insertion lands. The content reference is unambiguous, so this is low-impact, but a step that edits a file by absolute line number after inserting content into the same file is a self-invalidating instruction.
**Proposed fix**: Key the reconciliation edit off the scoping sentence's text ("the document-shape family applies to ... BLUF alone") rather than the line number, or sequence the insertion after the three reconciliations so the `:379` reference stays valid until consumed. Either resolves the drift; the text-keyed form is more robust.

### R4 [suggestion]
**Certificate**: Exposure — stamp-advance re-arm of the startup drift gate (D5)
**Location**: `track-1.md` D5 stamp-advance clause and `## Plan of Work` ordering constraints; `workflow-startup-precheck.sh:273`
**Issue**: The drift pathspec is `.claude/workflow/ .claude/skills/ .claude/agents/` only. This branch's edits split: in-pathspec (`conventions.md`, the three prompts, `commit-conventions.md`, `implementer-rules.md`, the three skills) and out-of-pathspec (`house-style.md`, `house-conversation.md` under `output-styles/`, the hook under `hooks/`, the test under `scripts/`). The stamp-advance trigger is therefore the last commit *in the drift pathspec*, not the last branch commit. D5 says "after the last workflow-editing commit" — slightly ambiguous, since the largest edits (`house-style.md`) are out-of-pathspec and do not move the drift range. If `/migrate-workflow` runs after the last out-of-pathspec commit but an in-pathspec commit lands later (e.g. a Phase-C fix touching a prompt), the gate re-arms stale. Low likelihood given the track orders the opt-out + criteria-switch (in-pathspec) first and the flip touches both classes, but worth pinning.
**Proposed fix**: In D5, define "last workflow-editing commit" precisely as "the last commit touching `.claude/workflow/**`, `.claude/skills/**`, or `.claude/agents/**` (the drift pathspec) — out-of-pathspec edits to `output-styles/`, `hooks/`, and `scripts/` do not move the drift range, so they need not precede the stamp-advance." This removes the ambiguity without changing the resolution.
