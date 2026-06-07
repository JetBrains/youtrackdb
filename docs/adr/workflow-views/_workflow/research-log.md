<!-- workflow-sha: eb984cba63bd557fb3c2b32156d85bf1a72e82b4 -->
# Research Log — Workflow Views

> Anchor (initial user request) plus continuous-log capture of Phase 0
> (research) decisions, discoveries, and open questions. Entries are
> durable across `/clear`, `/compact`, and Phase 0 → Phase 1 handoff.
> Phase 1 (planning) reads this file as the primary input to Decision
> Records and Architecture Notes.

## Initial request

**User's words:**

> Please validate the following idea. Currently we filter links and content in TOC by phases and roles at runtime, my idea is to create Python script that will create separate separate views for each pair of role-phase and stear agent to it's own filtered copy of workflow that will be copied in separate folder. Folder name would be workflow_view/role_phase. So all paragraphs will be filtered out by agent role/phase, TOC will stay but with title and summary (will use it so agent will filter out content of next step at runtime by intention of paragraph), but links both innter and outer will become flat, some of them can be orphaned if they are not needed for current phase and role. Is it possible ?

## Baseline and re-validation

**Fork point with develop:** `92d0a721f874b237a1dc2db950ad8b13b03078fc` ("Bump jackson.verson from 2.21.2 to 2.21.4 (#1119)", 2026-06-06). At the 2026-06-07 research session the branch sat exactly at the develop tip (0 ahead, 0 behind), so this is the merge-base.

**Why it matters:** every finding below is pinned to the workflow-machinery state at that commit — the 38 bootstrap surfaces, the §1.8 annotation/TOC schema, §1.7 staging being present, the `workflow-reindex.py` behavior, the role/phase enums, and the 20-view matrix derived from all of them. develop churns workflow-format changes fast, and the design for this branch is expected to be authored **after a rebase** (a couple of other features land first), so the baseline will move before the design is frozen.

**Re-validation protocol (runs at every session start until `design.md` is frozen):**
1. `git fetch`, then recompute `git merge-base HEAD origin/develop`. If it equals the fork point above, the baseline is unchanged — proceed.
2. If develop has advanced, review `git log <fork-point>..origin/develop`, focusing on commits touching `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`, `.claude/scripts/workflow-reindex.py`, `.claude/scripts/workflow-startup-precheck.sh`, or anything affecting the bootstrap block, §1.8, §1.7, or the role/phase enums.
3. Re-validate the Decision Log entries those commits touch (re-run the surface count, re-derive the view matrix, recheck §1.7's existence, etc.) and correct any that drifted.
4. After rebasing, update the fork-point SHA above to the new merge-base.

## Decision Log

- 2026-06-07T04:04:00Z [ctx=safe] Filter at section granularity, not paragraph granularity.
  - **Why:** the `roles=`/`phases=` annotations are attached to headings, so a script can act on them deterministically; paragraph-level filtering has no backing metadata.
  - **Alternatives rejected:** paragraph-level tags (large, ongoing hand-annotation across ~31 docs); an LLM filtering pass (non-deterministic, defeats the Python-script premise).

- 2026-06-07T04:04:00Z [ctx=safe] Copy `any:any` sections into every view rather than sharing them.
  - **Why:** each view folder stays self-contained, and references to shared sections (glossary, tooling discipline) never dead-end.
  - **Alternatives rejected:** a common folder the views link into (reintroduces the cross-view link resolution and runtime indirection the views are meant to remove).

- 2026-06-07T04:04:00Z [ctx=safe] Route to the view dynamically. **REFINED 2026-06-07T08:34 (F2/F3 resolution):** sub-agents are *passed* `(role, phase)` by the orchestrator in the spawn prompt; top-level skills self-resolve (role fixed per skill, phase from the precheck). The orchestrator applies the role→view merge map when it passes the role (absorbs F9: Phase-4 sub-work gets `orchestrator_4`, not a nonexistent `final-designer_4`; the driver self-selects its merged view).
  - **Why:** the entry-point files take one light routing edit ("read your docs from `workflow_view/<role>_<phase>/`"), not 38 hardcoded paths and not a self-bootstrapping stub.
  - **Alternatives rejected:** hardcoding each file's view path (brittle on every matrix change); a stub that self-computes the path before its first act (F3 — unproven self-bootstrap).

- 2026-06-07T04:17:35Z [ctx=safe] Orphan policy is a fallback route, not pruning or closure: orphaned links stay resolvable (`file + §anchor`), and on a miss the agent reads the target from the global `.claude/workflow/` after writing a justification to a fallback log; the reflection phase classifies each fallback and files an annotation-fix issue for the valid ones.
  - **Why:** keeps each view lean (no closure bloat), preserves correctness (global is always reachable, so nothing the agent needs is permanently denied), and self-tunes the annotations — every valid fallback is a labeled correction that adds the missing `(role, phase)` to a section's annotation.
  - **Alternatives rejected:** prune-and-orphan + report (leaves harmful orphans dead, the report is only information); dependency-closure (drags most sections into most views — the densely cross-linked docs collapse toward a fully connected per-view copy); phase-axis heuristic (still a static guess with no correction loop).
  - **Mechanism:** the generation-time orphan computation is repurposed into in-view markers (`conventions.md §1.3 [orphan]`) so an expected orphan is distinguishable from an unmarked-missing target (a generation bug). The fallback log is a branch-scoped, stamped `_workflow/**` artifact keyed by `(view, target-link, justification, timestamp)`. The reflection classifier requires a concrete justification (the specific rule needed and why), not a vague "might be relevant".

- 2026-06-07T04:17:35Z [ctx=safe] Enforcement is soft (honor-system justification gate) plus a transcript-vs-log telemetry detector, not a blocking hook.
  - **Why:** matches the existing honor-system TOC protocol and avoids a blocking PreToolUse hook; the detector compares actual `.claude/workflow/**` Read accesses in the session transcripts against fallback-log entries and records any mismatch (a global read with no logged justification) in the ADR as a possible compliance or annotation issue.
  - **Alternatives rejected:** hard PreToolUse-hook enforcement (heavier build, intrusive, premature before data shows the gate is skipped). Kept as a fast-follow if the detector shows the gate being bypassed.

- 2026-06-07T04:26:54Z [ctx=safe] ~~This change removes the §1.7 staging convention~~ **REVERSED 2026-06-07T08:34 (resolves F1): KEEP §1.7.** Views are a derived read-optimization layered on top of §1.7's existing freeze, not a replacement for it.
  - **Why the reversal:** the removal rationale ("the only live files read at runtime are fixed stubs") was contradicted (F1) — the ~31 workflow docs do not relocate and are read live on fallback — and F2/F3 showed not everything can become a stub anyway (18 dimensional-agent bodies, the inline implementer prompt stay live). Live mutable workflow files therefore remain regardless, so a freeze mechanism is needed no matter what; that mechanism is §1.7. Keeping it is low-cost (existing, working machinery) and the user judged the simplification not worth the trouble.
  - **Consequences of keeping §1.7:** (1) the committed views are develop-state, generated from live (== develop under I6), regenerated only at Phase 4; a branch's workflow edits go to the staged mirror and do not touch the committed views until promotion. (2) §1.7(f) rebase-precedes-promotion gains a step: regenerate and commit views after the rebase-and-promote. (3) The accepted live-fallback inconsistency risk (below) is **eliminated** — I6 keeps live `.claude/workflow/` at develop-state, so the fallback reads content consistent with the develop-state views by construction; the only window is the controlled Phase-4 promotion, which §1.7(j) handles.
  - **Ripple:** keeping §1.7 removes one of the two motivations for the entry-point stubs (immutability-so-staging-isn't-needed); combined with F2/F3 breaking the stub mechanics, the stub-relocation decision is reconsidered when F2/F3 resolve (likely toward "docs get views, entry points get a light bootstrap rewrite, bodies stay put").

- 2026-06-07T04:26:54Z [ctx=safe] ~~Entry-point files become trivial fixed stubs that read their body from a view~~ **REVERSED 2026-06-07T08:34 (resolves F2/F3): no stubs; views are doc-only.** Agent/skill/prompt bodies stay full and live (frozen by §1.7 during a branch), loaded by the harness exactly as today. Views hold only the ~31 workflow *docs*, keyed by `(role, phase)` — the correct key for docs (the 18 dimensional agents all read the same `reviewer-dim-step_3B` doc-view; their distinct checklists stay in their own bodies). Each entry point gets one light routing line telling it to read its docs from `workflow_view/<role>_<phase>/`, with the role/phase passed by the orchestrator (sub-agents) or self-resolved (skills). Economy scope is correct: a body is necessary instructions the harness loads regardless; the over-read is the docs read on top of it. Residual: full bodies are not phase-filtered, so a large SKILL body is a fixed cost views do not reduce (accepted).
  - **Why:** moves all mutable role/skill/prompt content into the frozen view tree, leaving the live entry points immutable in practice — which is what closes the system-prompt residual to the staging-elimination claim. The role+phase script is mostly the existing `workflow-startup-precheck.sh` (it already emits `state.phase`); the role is fixed per entry point.
  - **Alternatives rejected:** keep full role content in live `.claude/agents/*.md` etc. (those stay live-edited, so they would still need a freeze mechanism).
  - **Open detail:** a spawned reviewer knows its role from the entry point but not its phase (3B vs 3C); the orchestrator passes the phase in the spawn prompt or the stub script reads it from on-disk state. Pin this when designing the stub.

- 2026-06-07T04:26:54Z [ctx=safe] ~~Accepted risk: the fallback reads the live `.claude/workflow/` global folder~~ **MOOT 2026-06-07T08:34 (keep-§1.7 reversal):** with §1.7 kept, I6 holds live `.claude/workflow/` at develop-state for the whole branch, so the fallback reads content consistent with the develop-state views by construction. The window the risk described (live source edited mid-branch) cannot occur — those edits go to the staged mirror, which the fallback does not read. The only residual is the controlled Phase-4 promotion window, handled by §1.7(j). The fallback still reads the live (develop-state) `.claude/workflow/`; no bundled frozen copy is needed.

- 2026-06-07T06:10:15Z [ctx=safe] The view matrix is 20 `(role, phase)` session-views; `any:any` content is injected into all 20 rather than being a view of its own.
  - **Why:** derived from the entry-point bootstrap declarations (38 files) plus the two inline-spawned roles (implementer, decomposer) and the session-merge principle above, not the 75-pair annotation cross-product.
  - **The 20 views:**
    - Driver / authoring sessions (7): `planner_0` (research), `planner_1` (design authoring + plan derivation; absorbs edit-design-as-planner), `orchestrator_2` (Phase 2 driver), `orchestrator_3A` (Phase A driver + decomposer), `orchestrator_3B` (Phase B driver), `orchestrator_3C` (Phase C driver), `orchestrator_4` (Phase 4 driver + final-designer + edit-design-as-final-designer).
    - Spawned sub-agent sessions (11): `implementer_3B`, `implementer_3C` *(confirm: does Phase C spawn a fix-implementer, or does the orchestrator apply review fixes? if the latter, this folds into `orchestrator_3C`)*, `reviewer-plan_2`, `reviewer-technical_3A`, `reviewer-risk_3A`, `reviewer-adversarial_3A`, `reviewer-adversarial_1`, `reviewer-design_1`, `reviewer-design_4`, `reviewer-dim-step_3B`, `reviewer-dim-track_3C`.
    - Outside the phase taxonomy (2): `migrator_any` (migrate-workflow), `pr-reviewer_any` (review-workflow-pr, pr-reviewer, dr-audit).
  - **Coverage:** all 14 non-`any` roles are represented; `decomposer` and `final-designer` have no standalone folder (merged per the session-merge principle).

- 2026-06-07T08:01:41Z [ctx=safe] Hand-edit protection is discipline-only at this stage: a `GENERATED — do not edit` header per view file, no PreToolUse hook or dedicated guard.
  - **Why:** the consistency check (below) already catches a hand-edited view incidentally — a hand-edit makes the view diverge from `regenerate(source)` and the check fails at ready-PR time — so a dedicated guard adds machinery for a violation the existing check already surfaces.
  - **Alternatives rejected:** PreToolUse hook blocking writes to the view tree (premature; revisit if discipline proves insufficient).

- 2026-06-07T08:01:41Z [ctx=safe] The view-consistency check rides the draft/ready PR boundary, so it needs no branch/phase detection.
  - **Source:** the project skips CI on draft PRs; a workflow branch stays draft for its whole life, regenerates views at Phase 4, and the user flips to ready only after Phase 4.
  - **Implication:** an unconditional `generate --check` (committed views equal `regenerate(source)`) on ready, non-draft PRs is automatically phase-correct — draft/mid-branch views are deliberately stale and uncheck'd; ready/post-Phase-4 views are consistent and enforced. No special-casing for workflow-modifying branches.

- 2026-06-07T08:01:41Z [ctx=safe] Generator integration shape: regenerate at Phase 4 only, reuse the reindex parser, relocate entry-point bodies behind stubs.
  - **Why:** the freeze rule fixes the trigger (Phase 4 promotion; local runs use a `--out` scratch dir and never commit mid-branch — committing a regeneration is the one act that breaks the freeze). `workflow-reindex.py` already parses the annotation grammar / TOC / cross-ref validation, so the generator reuses it and runs after `reindex --write` (pipeline: reindex → generate → commit).
  - **Stub relocation:** the harness owns the `.claude/{agents,skills,workflow/prompts}` paths and reads their frontmatter, so a stub = original frontmatter + the "run the role/phase script, read body from the view" block; the editable body relocates into the generator source tree. Workflow docs (`.claude/workflow/*.md`) stay put and double as the fallback target.
  - **Precheck:** drops the §1.7 staging detection (staging is removed), keeps the drift (artifact-shape) check, and must not flag develop-state-vs-source view staleness on a workflow-modifying branch.
  - **Open design-level sub-decisions (non-blocking):** view-tree path (`.claude/workflow_view/` vs repo-root `workflow_view/`); `generate` as a `workflow-reindex.py` subcommand vs sibling script; the exact source location for relocated entry-point bodies.

- 2026-06-07T08:08:24Z [ctx=info] Second, related feature on this branch: a runtime read-efficiency change — reword the TOC protocol's step 3 as a skip-license, and make summaries judgable against an action. Complements the static views (it is the within-view "filter the next action via the TOC summary" layer) and stands on its own value in the pre-view world.
  - **Part 1 (the fix):** §1.8(f)'s flow goes `SECTION_MATCH → JUMP → act` with no node between "role+phase matches" and "read it", so a faithful agent reads every match. Reword so the agent reads only the matched sections whose summary shows they bear on its next action and leaves other role+phase matches unread. Edit sites: the stub bootstrap template (covers all 38 blocks in one pass — the reword bakes into the stub, not 38 manual edits), conventions.md §1.8(f) (add an `ACTION_MATCH` node to the flowchart + the skip-license sentence to the prose), and the `ytdb-1023-workflow-toc` durable block (`design-final.md` + `adr.md`).
  - **Part 2 (the convention):** add a sentence to §1.8(c)'s `summary=` field rules — frame each summary around the topic or action the section serves (what a reader does with it), not a restatement of the heading, so the role + phase + summary triple suffices for the narrowing decision. Go-forward authoring convention, not a schema change.
  - **Couplings:** `workflow-reindex.py` rule 7 validates bootstrap-block presence and `test_workflow_reindex.py` pins the assertion — both move if the reword changes the required block text. Editing another (merged) branch's `ytdb-1023` `design-final.md`/`adr.md` is unusual; decide update-in-place vs superseding the block definition in this branch's `design-final.md`.

- 2026-06-07T08:08:24Z [ctx=info] Accepted/known risk from the skip-license: it introduces a *silent* wrong-skip failure mode that the imperative protocol did not have.
  - **Why it is silent:** the fallback log fires only when the agent follows a link to a missing target, and the read-access telemetry catches only *extra* reads — neither catches a section the agent wrongly judged irrelevant and never opened. A wrong-skip from a poorly-framed legacy summary is invisible to both.
  - **Recovery paths (both require the agent to notice the gap):** a wrong-skipped section still in the view is a cheap local re-read; an orphan is the global fallback. Part 2 is the mitigation; its quality, not just its presence, is what makes the skip-license safe.

- 2026-06-07T08:08:24Z [ctx=info] Legacy summaries get a one-time audit: rewrite the existing ~250 annotated summaries to be action-framed before the skip-license ships, rather than go-forward only.
  - **Why:** the wrong-skip failure mode is silent (caught by neither the fallback log nor the read-access telemetry), so leaving legacy summaries un-audited leaves the skip-license unsafe across the existing corpus until each section is next touched. The corpus is finite and already enumerated by the reindex.
  - **Alternatives rejected:** go-forward only, leaning on the reflection loop (leaves silent wrong-skip risk on every un-touched legacy section indefinitely).
  - **Implication:** the plan needs a summary-audit track covering the ~250 sections; `workflow-reindex.py`'s section enumeration supplies the worklist.

- 2026-06-07T08:20:07Z [ctx=info] Extend the Phase 4 telemetry step with a workflow-read word-economy measure — the feature's own ROI instrument. It is the standing version of the "measure over-read before building" check.
  - **What it reports:** (1) which `.claude/workflow/**` and `workflow_view/**` files the main agent and sub-agents read, from the transcripts; (2) baseline = full source word count per read; (3) actual = words returned per read; (4) total economy (`baseline − actual` + ratio); (5) top-5 winners and top-5 losers by economy.
  - **How:** a sibling of `measure-read-share.py` reusing its transcript walk (already recurses `subagents/`). Source word counts come from disk (frozen at Phase 4); post-views the per-view manifest maps view files to their source for the full baseline. Strip Read's `cat -n` line-number prefixes before counting.
  - **Design choices:** baseline unit is per Read call (symmetric with actual — a thrice-read file is 3× full vs 3 partials, so the ratio is fair); attribute reads per role so the output names the worst view (e.g. `orchestrator_3C`) for tuning; a "loser" includes the net-negative case where a view's reads plus its orphan-fallback reads exceed the full source (ties to the fallback log); consolidate with the fallback-compliance detector into one "workflow-read telemetry" script (same walk, two outputs).
  - **Caveats:** measures economy only on *opened* files — file-level-ref skips (files never opened) are invisible, so the claim is "section/view economy on opened files", not total economy. Pre-views runs measure TOC economy only; real view economy needs views live + a full workflow run under them (two-phase metric rollout). ADR output is percentages-only per `measure-read-share.py`'s public-repo norm (rank winners/losers by percent-of-full; absolute words for local runs only).
  - **Unit:** words (tokenizer-independent, fine for a ratio); tokens are the alternative if context-cost fidelity is wanted later.

- 2026-06-07T08:20:07Z [ctx=info] Token counting uses char-count / 4 by default (the house standard), with `tiktoken o200k_base` as an optional offline backend; no API.
  - **Why:** `measure-read-share.py` already uses `CHARS_PER_TOKEN = 4` (shared with `session-stats.py` and `ccusage`), documenting that the approximation cancels out of a ratio. The economy metric is a ratio, so tokenizer precision is irrelevant to the headline number and to the winners/losers ranking (the same counter scales baseline and actual equally). Reusing char/4 keeps consistency and adds no dependency.
  - **Optional backend:** `tiktoken` with `o200k_base` behind the same interface, defaulting to char/4, for one notch of absolute fidelity on local (unpublished) runs. Embedded BPE, fully offline once the vocab is vendored or `TIKTOKEN_CACHE_DIR`-cached (it otherwise fetches once on first use).
  - **Rejected:** the `count_tokens` API (a network call, excluded by the no-API-calls constraint); the old Anthropic local tokenizer (Claude-2 era, removed from the SDK, not close to Opus 4.x).
  - **Residual:** a real tokenizer marginally sharpens cross-file ranking only when files differ sharply in code-vs-prose mix (different chars/token); within a file the read and unread portions share a content type, so the per-file ratio is robust under char/4. Second-order; the optional backend covers it.

## Surprises & Discoveries

- 2026-06-07T04:04:00Z [ctx=safe] The filtering metadata already exists and is already parsed by shipped tooling.
  - **Source:** conventions.md §1.8 (annotation / TOC region / cross-reference schema); `.claude/scripts/workflow-reindex.py` and its test suite.
  - **Implication:** the generator forks the reindex parser for the annotation grammar; no LLM sits in the generation path.

- 2026-06-07T04:04:00Z [ctx=safe] 47% of cross-references (179/374) dead-end in at least one view; 2,093 total (ref, view) orphan incidents, concentrated in the orchestrator views (`orchestrator_3C` 74, `orchestrator_3B` 65).
  - **Source:** ad-hoc orphan probe over `.claude/workflow/*.md`, computing host-section audience minus link-declared audience per cross-reference.
  - **Implication:** orphan handling is the core generator decision. The 47% is an upper bound — hand-written cross-file suffixes are a subset of the target's real audience, so some counted orphans resolve at generation time.

- 2026-06-07T04:04:00Z [ctx=safe] Agents over-read the full corpus under the current runtime filter.
  - **Source:** user confirmation during research; the project already treats context budget as first-class (the `review-workflow-context-budget` agent, the context-window monitor).
  - **Implication:** static views deliver a measurable token reduction, not only the role-purity safety property.

- 2026-06-07T04:04:00Z [ctx=safe] The raw annotation cross-product is 75 distinct `(role, phase)` pairs over 13 roles × 7 phases.
  - **Source:** orphan-probe role/phase universe enumeration over the annotations.
  - **Implication:** emitting all 75 produces phantom views (e.g. `planner_3C`, which never runs). The generator's real input is the set of pairs that run as agent sessions.

- 2026-06-07T04:17:35Z [ctx=safe] The fallback-compliance detector can reuse the transcript-parsing pattern already shipped in `measure-read-share.py`, and one per-view manifest serves three consumers.
  - **Source:** `.claude/scripts/measure-read-share.py` docstring — walks `~/.claude/projects/<encoded-cwd>/**/*.jsonl` (recursing `subagents/`), worktree-only, percentages-only, feeds the Phase 4 ADR.
  - **Implication:** the detector is a sibling script reusing the transcript walk; the generator emits a per-view section manifest (sections present + expected-orphan links) consumed by the in-view orphan markers, the telemetry detector, and the reflection baseline — not a separately maintained file.

- 2026-06-07T06:10:15Z [ctx=safe] The view-matrix unit is the agent *session*, not the raw role token: execute-tracks-driven phase sessions absorb the phase-procedure role, so two role tokens never get their own folder.
  - **Source:** `track-review.md` Phase A sections co-tagged `orchestrator,decomposer` with no decomposer entry file or spawn (orchestrator wears the decomposer hat); `create-final-design.md` is read by the execute-tracks (orchestrator) Phase 4 session, which spawns only the design-review sub-agent (orchestrator wears the final-designer hat). Implementer and the reviewers (plan/technical/risk/adversarial/design/dimensional) are genuinely spawned (`step-implementation.md` "spawn the implementer"; `structural-review.md` "Spawns a structural review sub-agent"; dimensional agents under `.claude/agents/`).
  - **Implication:** `decomposer:3A` folds into `orchestrator_3A` and `final-designer:4` (plus `edit-design`-as-final-designer) folds into `orchestrator_4`; `edit-design`-as-planner folds into `planner_1`. The phantom cross-product pairs (`planner:3C`, `orchestrator:0`, `reviewer-technical:1`, …) drop out. The real matrix is 20 views, not the 75-pair raw cross-product.

- 2026-06-07T08:34:17Z [ctx=info] Adversarial review of this log (sub-agent, all findings verified against the real files) judged the spine **not design-ready**: two structural cracks and one strategic crack re-open settled decisions. Headline recommendation: ship and measure the cheap, fully-verifiable half (skip-license + action-framed summaries + audit) first, then gate the expensive view system (generator + §1.7 removal + stubs + fallback) on the measured residual over-read.
  - **Source:** adversarial sub-agent (agentId a8619f9c15dd4b97e), 2026-06-07; findings F1–F9 catalogued in Open Questions.
  - **Implication:** the §1.7-removal, stub-relocation, 20-view-matrix, and "reuse the reindex parser" Decision Log entries are challenged — treat them as provisional until F1–F7 resolve.

## Open Questions

### Adversarial findings (2026-06-07T08:34:17Z, sub-agent a8619f9c15dd4b97e) — resolve before design freeze

- **[F1] §1.7 removal rests on a contradicted premise.** BLOCKER → **RESOLVED 2026-06-07T08:34: keep §1.7** (see the reversed Decision Log entry). Views become a derived read-optimization on top of §1.7's existing freeze, not a replacement. This also eliminates the live-fallback inconsistency risk (I6 keeps live at develop-state). No drift-gate / review exposure, because the branch's edits stay in the staged mirror as before.
- **[F2] The 38→20 stub-to-view mapping is many-to-one and not-onto.** BLOCKER → **RESOLVED 2026-06-07T08:34: views are doc-only, no body relocation.** Bodies stay in their own files; `(role,phase)` is the correct key for docs (the 18 dimensional agents share one doc-view, distinct checklists stay in their bodies); the implementer reads `implementer_3B/3C` via the orchestrator-passed role. See the reversed stub Decision Log entry.
- **[F3] Stub feasibility assumes a harness indirection that does not exist.** BLOCKER → **RESOLVED 2026-06-07T08:34: no stubs.** Bodies are loaded in full by the harness as today; the only view interaction is reading docs from `workflow_view/<role>_<phase>/`, a normal instruction in a full body, not a self-bootstrap. Routing role/phase is orchestrator-passed (sub-agents) or self-resolved (skills).
- **[F4] The views' marginal value over the cheap skip-license is never established.** BLOCKER → **REFRAMED 2026-06-07T08:53.** The earlier "skip-license captures the bulk, views add only TOC overhead" ledger had a **hidden premise: that agents follow the runtime read/ref disciplines.** They largely do not (user observation, now partly measured), which inverts the conclusion:
  - The TOC-read protocol and the skip-license are both *runtime* disciplines an agent must apply while focused on another task; under task-focus agents bypass the TOC and read whole files, and the skip-license only grants permission to skip (it does not fight the read-everything default). So the skip-license is **not** a substitute for views — it is the same unreliable discipline class.
  - Views move filtering from honor-system behavior to a **CI-enforced artifact**: they rest on section annotations (reindex blocks a missing one) rather than the TOC-read protocol or the cross-ref suffix, both of which are unenforced and demonstrably decay.
  - **Claim 2 (ref decay) — measured, confirmed.** Refs to annotatable workflow docs: 325 backticked (no filtering suffix) vs 465 suffixed = ~41% bare, worst in core docs (`workflow.md` 81%, `track-review.md` 71%, `conventions-execution.md` 74%). Upper bound (some backticks are prose mentions), but the concentration shows real erosion of the file-level filter. Source: corpus grep, 2026-06-07.
  - **Claim 1 (TOC bypass) — the decisive measurement, pending.** Classify every `.claude/workflow/*.md` Read in existing transcripts as full-file vs `offset/limit` partial (data lives in worktrees that ran full workflows, not this research worktree). A high full-file fraction confirms the read protocol decays → views justified, skip-license demoted to an in-view nicety, and the earlier re-sequencing recommendation reversed.
  - **Resolution:** run the claim-1 transcript measurement before finalizing the views-vs-skip-license decision. This also closes F5's circularity — it estimates the views' upside from real runs without building anything.
  - **Blocking:** whether the whole view system is worth its blast radius; one-branch-vs-two; supersedes the "ship the cheap half first" recommendation pending the claim-1 number.
- **[F5] ROI is circular.** SHOULD-FIX. The telemetry sees economy only on opened files (file-skip savings invisible) and yields its first view-economy number only after the feature ships and a full workflow runs under it. Add a pre-build estimate on the existing corpus (data already exists) instead of deferring the only real number to post-ship.
  - **Blocking:** the justification for building before measuring.
- **[F6] The accepted-risk stack compounds.** SHOULD-FIX. Silent wrong-skip, live-fallback inconsistency, and discipline-only protection each lean on a different safety net, and the nets share a hole — none observes its assigned failure (the silent wrong-skip is caught by neither the fallback log nor the telemetry). Find one observable signal for wrong-skip or downgrade the skip-license to advisory until one exists.
  - **Blocking:** the safety case for the skip-license + accepted risks.
- **[F7] "Reuse the reindex parser" oversells the build.** SHOULD-FIX. Genuine reuse is the annotation/TOC parser only; the orphan engine, per-view filter/emit, in-view markers, manifest, and fallback classifier are net-new (no orphan/view code exists in the scripts; the 47% came from an ad-hoc, uncommitted probe and is an upper bound). Re-scope as new code borrowing the parser; commit the orphan probe and pin the number.
  - **Blocking:** generator effort estimate; the orphan-policy number.
- **[F8] Matrix anchors need correction.** CONSIDER (apply at design). `implementer_3C` is real (Phase C spawns a fresh per-iteration fix implementer — track-code-review.md:594), so it stays in the matrix. The "13 roles × 7 phases = 75" framing is stale: the shipped enum is 15 roles × 8 phases and 75 is an observed-pair probe count, not a cross-product — restate accordingly for the post-rebase re-derivation.
- **[F9] Dynamic routing needs an explicit role→view merge map.** CONSIDER → **ABSORBED 2026-06-07T08:34 by the F2/F3 resolution:** the orchestrator applies the merge map when it passes the role to a sub-agent, and the driver self-selects its merged view. The map (decomposer→orchestrator_3A, final-designer→orchestrator_4, edit-design-as-planner→planner_1) is stated in the refined routing Decision Log entry, to be carried into the design.

<!-- Resolved 2026-06-07T06:10:15Z: view-matrix scope is the 20 session-views enumerated in the Decision Log. One sub-item to confirm during design: whether `implementer_3C` is a real spawned view or folds into `orchestrator_3C`. (F8 resolves it: real.) -->

<!-- Resolved 2026-06-07T08:01:41Z: generator integration shape and hand-edit protection are settled in the Decision Log (regenerate at Phase 4, reuse reindex parser, stub relocation, draft/ready consistency check, discipline-only protection). Three design-level sub-decisions remain, recorded inline on that decision; none blocks planning. -->

<!-- Resolved 2026-06-07T04:26:54Z: File-scope question folded into the stub decision in the Decision Log — workflow docs AND role/skill/prompt bodies move into the per-pair view tree; the 38 entry-point files become fixed stubs. Residual (do prompt bodies phase-filter the same as docs?) folds into the view-matrix and generator-integration questions. -->

