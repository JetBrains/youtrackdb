<!-- workflow-sha: 367f5f83f1bce0e98eaeb0679973f9728db64b61 -->
# Track 5: Bootstrap block + agent files refs sweep + migration verification

## Purpose / Big Picture

After this track lands, the reindex script validates the 20 live agent files (D17), 38 system-prompt files carry the bootstrap protocol block at their TOC anchor, every outgoing workflow-doc reference in the 20 agent files and in the 7 SKILL.md startup read-lists carries the `:roles:phases` suffix, and the post-merge two-branch `/migrate-workflow` acceptance procedure is documented with its in-branch premise check and smokes green.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

First land a HIGH-risk `workflow-reindex.py` agent-scope fix (D17) so rules 6 and 7 validate the 20 live agent files (the script's discovery scope omitted live `.claude/agents/**` — Phase A reviews found rules 6/7 never fired on agents). Then insert the bootstrap protocol block (~30 lines, body sourced from `design.md §"Bootstrap protocol for agent system prompts"`) at the TOC anchor of 38 system prompts (7 SKILL.md, 11 `.claude/workflow/prompts/*.md`, 20 `.claude/agents/*.md`), and apply the `name:roles:phases` cross-file suffix to outgoing workflow-doc refs in the 20 agent files (Track 4's D13 already converted the 7 SKILL.md read-lists — Track 5 verifies and gap-fills). The real two-branch `/migrate-workflow` replay is a post-merge acceptance procedure (D18): it can only run after this plan reaches develop, so Track 5's in-branch verification is a static D7-premise check plus local `--check` and telemetry smokes, and the two-branch replay is documented for the user to run post-merge.

`CLAUDE.md` is intentionally out of scope. CLAUDE.md is a general-purpose project guide, not workflow-specific; the file-level cross-ref filter does not apply. See the plan's Non-Goals entry.

## Progress
- [x] 2026-05-31T12:48Z [ctx=info] Review + decomposition complete
- [x] Step implementation
- [x] 2026-05-31T16:09Z [ctx=info] Step 1 complete (commit 58af04dfd3)
- [x] 2026-05-31T16:17Z [ctx=info] Step 2 complete (commit 9c6947ad62)
- [x] 2026-05-31T16:25Z [ctx=warning] Step 3 complete (commit e2d2853354)
- [ ] 2026-05-31T16:25Z [ctx=warning] Paused before Step 4 (HIGH-risk fan-out) for context refresh — Steps 1-3 done, Steps 4-5 remain
- [ ] 2026-05-31T17:08Z [ctx=warning] Step 4 implementer committed (09e2e0c521); HIGH-risk dim review (5 agents) gate-green but surfaced inherited bootstrap-body gaps (WP1/WP2/WI2); user chose ESCALATE → inline replan (deferred to fresh session). Step 4 roster stays `[ ]` — loop interrupted, no episode.
- [x] 2026-05-31T17:44Z [ctx=warning] Step 4 complete (commit 09e2e0c521) — roster flipped `[x]` and episode written during the inline replan; the dim-review body defect (WP1/WP2/WI2) is deferred to the new Step 5
- [ ] 2026-05-31T17:44Z [ctx=warning] Inline replan (D19) plan/track edits applied (D19 + D8 note + Track 5 note + roster Step 5 added + verification → Step 6 + Plan Review reset); PAUSED for context refresh before the `edit-design` design.md mutation + structural preview + final replan commit
- [x] 2026-05-31T18:10Z [ctx=info] Inline replan (D19) complete: design.md bootstrap body corrected via edit-design (Mutation 12: mechanical PASS after fixing 2 pre-existing dangling-ref blockers, cold-read PASS, 1 pre-existing should-fix carried as known debt); advisory structural-review preview PASS; handoff resolved. Next: State 0 re-validation, then Phase B Step 5.
- [x] 2026-05-31T19:24Z [ctx=warning] Step 5 complete (commit a7b7575e04, Review fix 21c3c5fe91)
- [x] 2026-05-31T19:47Z [ctx=info] Step 6 complete (verification — no code commit)
- [x] 2026-05-31T20:23Z [ctx=warning] Track-level code review iteration 1 fixes applied (Review fix 16ed654728); gate-check fan-out pending — paused for context refresh
- [ ] Track-level code review
- [ ] Track completion

**PAUSED 2026-05-31 at Phase C iteration-1 fixes-applied pending gate-check fan-out + track completion**
- Handoff: docs/adr/ytdb-1023-workflow-toc/_workflow/handoff-track-5-phaseC.md

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- Step 1 live smoke set the agent `--check` baseline that Steps 4/5 drive to green: every agent is rule_6 (un-swept refs) plus rule_7 (missing bootstrap) RED, and the per-rule gate bounds findings to rule_6×3 plus rule_7×20 (no rule-4 blast radius). `dr-audit.md` is the one non-uniform agent — its Markdown-link ref needs restructuring to the bare suffixed form (not a plain suffix), and its `implementation-plan.md` ref needs backtick-wrapping. See Episodes §Step 1.
- Step 5 dim review surfaced a next-layer bootstrap-body gap below D19 (file-level `any` under-match; missing no-TOC read-window complement), fixed in-step across the 38 files plus staged `conventions.md §1.8(e)/(f)` with the frozen `design.md §"Bootstrap protocol"` deferred to Phase 4 (joins the S1 deferred-drift basket). Cross-cutting lesson for future bootstrap-body edits: a bare cross-file `§X.Y(z)` anchor in the block trips reindex rule_8 on staged SKILL/prompt copies but passes on live agents (rule_8 is agent-exempt), so backtick-wrap any such anchor and run the scoped `--check` over the staged copies, not only agents. See Episodes §Step 5.
- Step 6 reconciled the three rule_1 residue counts in the track text to one Phase-4-residue class at different scopes: 49 = the full 49-file staged-workflow tree, 18 = the mechanically-derived in-scope set (7 staged SKILL + 11 staged prompts; the 20 live agents are rule_1-exempt by the D17 gate). The Step 5 episode's "19 staged rule_1" was an off-by-one against 18. Phase 4's `adr.md` residue note should cite the "49 full-tree / 18 in-scope-set" pair. See Episodes §Step 6.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

- Phase A decomposition (2026-05-31): collapsed the planned agent-bootstrap step and agent-refs-sweep step into one HIGH-risk per-file step (now Step 4). The split would force `--no-verify` on the bootstrap-only commit — once Step 1 (D17) scopes agents into rule 6, `dr-audit.md`'s bare refs go rule-6-RED, so a bootstrap-only agent commit cannot pass the pre-commit `--check` — and it edits all 20 agents twice. Collapsing touches each agent once, keeps every commit gate-green, and the combined step's HIGH tag gives both halves step-level dimensional review. Within Phase A decomposition latitude; accepted from the adversarial review.
- Phase A decomposition (2026-05-31): kept D17's rules-6/7-only agent scope. The technical review proposed scoping rule 8 onto agents too, as a CI backstop for the un-backticked combined sub-section-pin form (the dominant agent ref). Declined: it contradicts D17's explicit "never rules 1/2/3/4/5/8", reopening a just-revalidated immutable Decision Record needs ESCALATE, and the risk and adversarial reviews both back rules-6/7-only as the correct minimal scope. The gap is mitigated — Step 4 is HIGH-risk, so its dimensional review hand-verifies every sub-section pin. Enforcing rule 8 on agents later is an ESCALATE, not an in-track edit.
- Inline replan after Step 4 (2026-05-31, D19): Step 4's HIGH-risk dim review came back gate-green but surfaced an inherited bootstrap-body defect in all 38 files and the frozen `design.md` — the reader-side match rule never teaches a reader whose own role or phase is `any` to expand it (`pr-reviewer`/`dr-audit` under-match), the "first ~30 lines" read window truncates `conventions.md`'s ~80-line TOC, and the closing line over-asserts the suffix on every inline ref. The defect spans a completed track's staged output and the frozen design, so it is ESCALATE-class; user chose ESCALATE → inline replan. Resolution: new DR D19; `design.md §"Bootstrap protocol for agent system prompts"` corrected via `edit-design`; Step 4 marked `[x]` (commit `09e2e0c521` — insertion + refs mechanics stand) with the shared body superseded by a new HIGH-risk Step 5 (re-propagate across all 38 files + align staged `conventions.md §1.8(e)/(f)` + WC1 + WP3); old verification renumbered to Step 6; Plan Review reset for State 0 re-validation. WC1 (user-confirmed): the house-style `§1.5` ref takes the uniform whole-file-suffix + backticked-`§1.5` form on all 20 agents (validated by rule 6), not the fully-backticked span — backtick-wrapping would skip an annotatable target. The staged `§1.8(e)/(f)` edit is continued staged authoring within Track 5 (pre-merge, not a post-merge completed-track revision).
- Step 5 dim-review resolution (2026-05-31): the HIGH-risk dim review came back gate-green on rules 2-8 but surfaced a next-layer bootstrap-body gap below D19: file-level `any` under-match (M1) and the missing no-TOC read-window complement (M2), plus three suggestions (M3 `§1.8(e)` row-`any` half, M4 step-1 rationale trim, M5 em-dash-to-colon). The fix spans the frozen `design.md` body, so it was ESCALATE-class by the Step-4 precedent; user chose the in-step fix instead: land M1-M5 in the 38 files plus staged `conventions.md §1.8(e)/(f)` (`Review fix: 21c3c5fe91`) and defer the frozen `design.md §"Bootstrap protocol"` body to the Phase-4 `design-final.md` deferred-drift basket (alongside S1). `design.md` now diverges from the on-disk body in two ways: the next-layer content, and the backtick-wrapped `§1.8(d)` anchor the rule_8 fix introduced. WC1 (the 11 prompts' fully-backticked `§1.5` form vs the agents' suffix form) stays a separate recorded Phase-4 deferred-drift item, not fixed here.

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 1 (3 findings — 1 should-fix, 2 suggestions; all accepted as decomposition refinements). D17's premise re-confirmed empirically: live `.claude/agents/**` is absent from `IN_SCOPE_GLOBS`, so rules 6/7 never visit agent files (`--check` on a live agent exits 0 with zero findings). The fix is necessary, not cosmetic — adding agents to discovery without a per-rule gate makes rules 2 and 4 misfire (reproduced end-to-end).
- [x] Risk: PASS at iteration 1 (5 findings — 4 should-fix, 1 suggestion; all accepted). No blocker: the script surface is small and well-tested, and every step is one revertible commit. The dominant risk is implementation precision in the per-rule suppression gate (rule-4 blast radius is 360 false findings against rule-2's 20) and the gate-load-bearing backtick discipline (the script's cross-file out-of-scope set is `CLAUDE.md`-only).
- [x] Adversarial: PASS at iteration 1 (7 findings — 4 should-fix, 3 suggestions; all accepted). D17 and D18 both survive the strongest rejected-alternative challenges: the simpler single-glob D17 fix over-fires rules 5 and 8, and an in-branch migration replay exercises nothing because the drift gate never fetches develop and §1.8 reaches develop only at Phase 4. Every finding is a wording or decomposition refinement, not a decision reversal.

## Context and Orientation

The 38 system-prompt files split into three categories.

**Workflow-referencing SKILL.md (7 files):**
- `.claude/skills/create-plan/SKILL.md` (17 workflow-doc refs)
- `.claude/skills/execute-tracks/SKILL.md` (17 refs)
- `.claude/skills/edit-design/SKILL.md` (14 refs)
- `.claude/skills/migrate-workflow/SKILL.md` (18 refs)
- `.claude/skills/review-workflow-pr/SKILL.md` (8 refs)
- `.claude/skills/review-plan/SKILL.md` (7 refs)
- `.claude/skills/code-review/SKILL.md` (3 refs; borderline)

These carry the bootstrap block AND the `:roles:phases` suffix on every workflow-doc ref in their startup read-list and body. They are also annotated by Track 4 (TOC + per-section annotations); Track 5 leaves the TOC and annotations alone and only adds the bootstrap + cross-ref suffix.

**Workflow internal prompts (11 files under `.claude/workflow/prompts/`):**
adversarial-review.md, consistency-gate-verification.md, consistency-review.md, create-final-design.md, design-review.md, dimensional-review-gate-check.md, review-gate-verification.md, risk-review.md, structural-gate-verification.md, structural-review.md, technical-review.md.

These prompts are spawned as sub-agent system prompts during Phase 2 (consistency + structural reviews), Phase A (technical / risk / adversarial / design reviews), Phase B (dimensional reviews), Phase C (track-level dimensional reviews), and Phase 4 (create-final-design). Each spawned sub-agent is fresh — no shared context with the parent — so the bootstrap block must live in the prompt content itself. Track 4 already adds TOC + annotations to these files; Track 5 adds the bootstrap block.

**Agent files (20 files under `.claude/agents/`):**
- 6 workflow-review agents: `review-workflow-consistency.md` (8 refs), `review-workflow-context-budget.md` (4), `review-workflow-hook-safety.md` (1), `review-workflow-instruction-completeness.md` (3), `review-workflow-prompt-design.md` (3), `review-workflow-writing-style.md` (1).
- 10 dim agents: `review-bugs-concurrency.md`, `review-code-quality.md`, `review-crash-safety.md`, `review-performance.md`, `review-security.md`, `review-test-behavior.md`, `review-test-completeness.md`, `review-test-concurrency.md`, `review-test-crash-safety.md`, `review-test-structure.md` (1 ref each, mostly boilerplate).
- 4 misc: `code-reviewer.md` (1), `dr-audit.md` (7), `pr-reviewer.md` (1), `test-quality-reviewer.md` (1).

These carry the bootstrap block AND the `:roles:phases` suffix on every outgoing workflow-doc ref. They are not annotated by Track 4 (D6: refs-only, no per-section annotations). Phase A review found the reindex script's discovery scope omitted live `.claude/agents/**` (it carried only a dead staged-agents glob), so rules 6 and 7 never fired on agent files; D17's HIGH-risk script-fix step scopes live agents into rules 6 and 7 before the agent sweep runs.

Migration verification per `design.md §"Migration replay semantics"` and D18: the real two-branch `/migrate-workflow` replay can run only after this plan squash-merges to develop. The drift gate ranges `git log` over a candidate branch's own HEAD and never fetches develop, and this plan's §1.8 plus workflow-sha bump reaches develop only at Phase 4 — so a candidate rebased onto current develop would see nothing from this plan. The two-branch replay is therefore a **post-merge acceptance procedure** the user runs in a fresh session on candidate branches rebased onto post-plan develop (candidates per memory: `ytdb-612-rollback-log`, `read-cache-concurrency-bug`, `ytdb-614-property-map`, `failed-wal-recovery`; two picks resolved post-merge, recorded in the Track 5 completion episode and Phase 4 `adr.md`). Track 5's in-branch verification is limited to runnable substitutes: a static D7-premise check (the plan's commits touch `.claude/...` only, never the branch's own `_workflow/**`), an optional local `/migrate-workflow` machinery smoke, the staged-scoped plus live-agent `workflow-reindex.py --check` green, and the `measure-read-share.py` smoke.

### Files in scope

- 7 SKILL.md files — staged copies under `_workflow/staged-workflow/.claude/skills/`.
- 11 prompts — staged copies under `_workflow/staged-workflow/.claude/workflow/prompts/`.
- 20 agent files — modified live (agents are not staged per §1.7).

### Files out of scope

- `CLAUDE.md` — general-purpose project guide, not workflow-specific. The cross-ref convention and bootstrap block do not apply.
- Non-workflow skill files (e.g., `ai-tells`, `run-jmh-benchmarks-hetzner`) — those skills do not Read files under `.claude/workflow/` or `.claude/skills/` at runtime.
- Per-section annotations on agent files — D6 decided refs-only.
- Workflow-doc content — Track 4 territory.
- Scripts — Tracks 2, 3.
- Schema — Track 1.

## Plan of Work

**Phase-1 sketch (six items) — superseded by the six-step roster in the §"Phase A sequencing" subsection below and `## Concrete Steps`; retained here for its per-step authoring detail. Where the numbering differs, the roster wins.** The sketch split agent bootstrap (item 4) from the agent refs sweep (item 5); Phase A collapsed those into one HIGH-risk step (roster Step 4). The Step 4 dim review later escalated an inherited bootstrap-body defect (D19), so the inline replan added a HIGH-risk bootstrap-body-correction step (roster Step 5) and moved verification to roster Step 6. The reindex agent-scope fix still leads because the agent work depends on it.

1. **Reindex agent-scope fix (D17) — HIGH-risk, leads the track.** Scope live `.claude/agents/**/*.md` into `workflow-reindex.py` rules 6 (cross-file ref suffix) and 7 (bootstrap presence) only — never rules 1/2/3/4/5/8, since D6 keeps agents refs-only (no TOC, no per-section annotations, no `_workflow/**` stamp). Add the live-agents discovery path and a per-rule applicability gate so rules 2/3/4 do not demand a TOC on agent files; remove or leave inert the dead staged-agents glob. Regression tests assert a missing bootstrap heading and a non-subset / missing-suffix agent ref each produce findings, and that an agent file is never flagged for a missing TOC. The script edit lands on the live `.claude/scripts/` path (scripts are not staged per the plan Constraints). This is a step-level dimensional-review step.
2. **Bootstrap insertion — SKILL.md (7 files, staged) + read-list suffix verify/gap-fill.** Insert the canonical bootstrap block (body from `design.md §"Bootstrap protocol for agent system prompts"`) at each SKILL's TOC anchor per the three anchor shapes in `conventions.md §1.8(d)` (under-H1 / after-frontmatter / top-of-file — pick by the file's actual shape, not the two-shape "between frontmatter and H1" wording the glossary/I5 still carry). Then verify the startup read-list `:roles:phases` suffixes (Track 4's D13 conversion already swept these) and gap-fill any residual. Routed through `_workflow/staged-workflow/.claude/skills/` per §1.7.
3. **Bootstrap insertion — workflow prompts (11 files, staged).** Insert the bootstrap block at each prompt's TOC anchor. Ten of the eleven prompts are prose-first (no frontmatter, no real H1) → top-of-file anchor per §1.8(d); `design-review.md` is the one H1-bearing prompt → under-H1. Each prompt's role is fixed (e.g., `reviewer-technical` for `technical-review.md`), so the role/phase substitution is mechanical. Routed through `_workflow/staged-workflow/.claude/workflow/prompts/`.
4. **Bootstrap insertion — agent files (20 files, live).** Insert the bootstrap block at each agent file's anchor (frontmatter-then-block, or top-of-file). Agent files are modified live (not staged) per §1.7. Bootstrap only — the refs sweep is Step 5.
5. **Agent refs sweep (20 files, live) — HIGH-risk, depends on Step 1.** Apply the `:roles:phases` suffix to every outgoing workflow-doc ref in each agent file. The role tag matches the agent's calling role (e.g., `reviewer-dim-step` for dim-step agents); the phase tag matches when the agent runs (e.g., `phases=3B`). Authoring rules from Phase A review: use whole-file `name.md:roles:phases` plus a separate backticked `§X.Y` for any sub-section pin (the combined cross-file-with-subsection form fails rule 8 in live prose — the dominant case is the house-style `conventions.md §1.5` header in 19 of 20 agents); do not claim an H4-only token in a whole-file suffix (the rule 6 union is `##`/`###` only); backtick-wrap — not suffix — refs to non-annotatable or out-of-in-scope targets (`.claude/docs/*` such as `architecture.md` / `testing-details.md` / `mcp-steroid/skills.md`, `house-style.md`, `design*.md`, the Phase 4 final artifacts, `CLAUDE.md`, `MEMORY.md`, agent-file-as-target). Run the staged-scoped plus live-agent `workflow-reindex.py --check` (rules 2/3/4/5/6/7/8 green; rule_1 missing-stamp on staged copies is the documented Phase-4 promotion residue, not a defect). This is a step-level dimensional-review step.
6. **In-branch verification + final validation.** Static D7-premise check (the plan's commits touch `.claude/...` only, never the branch's own `_workflow/**`); optional local `/migrate-workflow` machinery smoke; staged-scoped plus live-agent `workflow-reindex.py --check` green over the full in-scope set; `measure-read-share.py` smoke from this worktree. Document the post-merge two-branch acceptance procedure (D18) in the track-completion episode for the user to run after this plan reaches develop.

Phase A may split this track if step count grows past 7 — natural split points are the three bootstrap-insertion categories (SKILL / prompts / agents) versus the script-fix and sweep steps. The real two-branch migration verification is no longer an in-track step (D18: post-merge acceptance procedure).

### Phase A sequencing (authoritative — supersedes the six-step sketch above)

Decomposed into **six steps** (see `## Concrete Steps`). The planned agent-bootstrap and agent-refs-sweep steps are collapsed into one HIGH-risk per-file step (Step 4) — rationale in `## Decision Log`. The Step 4 dim review escalated an inherited bootstrap-body defect (D19), so the inline replan added a HIGH-risk bootstrap-body-correction step (Step 5) after Step 4 and moved verification to Step 6. Dependencies:

- **Step 1 (high)** leads: Step 4's `--check` cannot validate the suffixes it writes until the D17 agent-scope fix lands.
- **Steps 2 and 3 (medium)** are independent of Step 1 and of each other — they edit staged SKILL and prompt files, not the live script or agents — and may run in any order. Both depend only on Track 4 (complete).
- **Step 4 (high)** depends on Step 1.
- **Step 5 (high)** depends on Step 1: the body re-propagation re-runs the live-agent `--check`, which needs the D17 agent-scope fix. It re-edits the Step 2-4 output (staged SKILL and prompts, live agents) plus the staged `conventions.md §1.8(e)/(f)`.
- **Step 6 (low)** verifies the full in-scope set, so it runs last.

## Concrete Steps

1. Reindex agent-scope fix (D17): scope live `.claude/agents/**/*.md` into `workflow-reindex.py` rules 6 (cross-file ref suffix subset) and 7 (bootstrap presence) only, via a **separate rules-6/7-only citing scope** (NOT by adding agents to `IN_SCOPE_GLOBS`, which would route them through all eight rules); add the per-rule applicability gate; remove or leave inert the dead staged-agents glob; regression tests. — risk: high (architecture: changes the workflow-reindex CI/pre-commit gate's discovery scope + per-rule applicability)  [x] commit: 58af04dfd3
2. Bootstrap insertion — 7 staged SKILL.md + read-list suffix verify/gap-fill: insert the canonical block at each file's anchor (under-H1 / after-frontmatter / top-of-file per §1.8(d); use the script's fence-aware shape detection, not naive `grep ^#` — `create-plan` and `execute-tracks` are TOC-less so their anchor is after-frontmatter); verify Track 4's D13 read-list suffixes and gap-fill any residual. — risk: medium (multi-file: bootstrap insertion across 7 staged SKILL files + read-list verify)  [x] commit: 9c6947ad62
3. Bootstrap insertion — 11 staged prompts: insert the block at each prompt's anchor (10 prose-first → top-of-file; `design-review.md` → under-H1); mechanical role/phase substitution per prompt; keep the block's `conventions.md §1.8` reference backticked. — risk: medium (multi-file: bootstrap insertion across 11 staged prompts)  [x] commit: e2d2853354
4. Agent bootstrap + refs sweep — 20 live agent files (depends on Step 1): insert the bootstrap block AND apply the `:roles:phases` suffix to every outgoing in-scope workflow-doc ref, one pass per file. Sub-section pins → whole-file `name.md:roles:phases` + separate backticked `§X.Y`; never claim an H4-only token (rule 6 union is `##`/`###` only); restructure `dr-audit.md`'s Markdown link to the bare suffixed form; leave `path/to/file.md` template placeholders backticked; backtick-wrap (never suffix) non-annotatable / out-of-in-scope targets. — risk: high (architecture + cross-file ref correctness: gate-green depends on per-ref suffix accuracy; no rule-8 backstop on agent sub-section pins per technical review — dim review hand-verifies)  [x] commit: 09e2e0c521
5. Bootstrap-body correction (D19, inline replan) — re-propagate the corrected bootstrap block body (reader-side `any`-axis match-rule expansion, delimiter-bounded read window, backtick-ref note) across all 38 files (7 staged SKILL + 11 staged prompts + 20 live agents) sourced from the corrected `design.md §"Bootstrap protocol for agent system prompts"`; align staged `conventions.md §1.8(f)` read-decision flow and add the `§1.8(e)` reader-`any`-vs-citer-`any` distinction; apply WC1 (the house-style `§1.5` ref takes the whole-file-suffix + backticked-`§1.5` form on all 20 agents) and WP3 (mirror the phase-`any` gloss on `dr-audit.md` / `pr-reviewer.md`); re-run the staged + live-agent `workflow-reindex.py --check` green (rules 2/3/4/5/6/7/8; the 49 staged rule_1 missing-stamp findings stay documented Phase-4 residue). — risk: high (cross-file body + schema correctness across 38 files plus a staged `§1.8` edit; rule 7 is presence-only so the body fix is gate-invisible — dim review hand-verifies)  [x] commit: a7b7575e04
6. In-branch verification + final validation: mechanically-derived `--check` over staged SKILL/prompts ∪ live agents (`git ls-files '.claude/agents/*.md'`) — rules 2/3/4/5/6/7/8 green, with the live-SKILL/prompt rule_7 findings and the 49 staged rule_1 missing-stamp findings as documented Phase-4 promotion residue; static check that this plan alters no `_workflow/**` artifact *format*; `measure-read-share.py` smoke from this worktree; document the post-merge two-branch `/migrate-workflow` acceptance procedure (D18) for the completion episode. — risk: low (default: verification + documentation; no production-logic change)  [x]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 58af04dfd3, 2026-05-31T16:09Z [ctx=info]
**What was done:** Landed the D17 reindex agent-scope fix in `workflow-reindex.py`. A new `discover_agent_citing_files` helper adds the 20 live `.claude/agents/*.md` files to a separate rules-6/7-only citing scope (it mirrors the agent slice of `discover_bootstrap_scope`, which rule 7 already consults). The `validate` loop now parses agent files and runs only rule 6 (cross-file ref suffix subset) and rule 7 (bootstrap presence) on them; a per-rule applicability gate keeps rules 1/2/3/4/5/8 structurally unreachable for agents. Agents stay out of `IN_SCOPE_GLOBS` by design, since that set drives the full eight-rule pass and `compute_write_plan`. The dead staged-agents glob in `IN_SCOPE_GLOBS` was left inert with a marking comment (removing it would break an existing staged-discovery test without changing behavior). Seven regression tests cover the five required assertions plus a present-bootstrap positive and a non-subset rule-6 case; the full suite is 124/124 green. The step-level dimensional review (consistency, hook-safety, context-budget; baseline group skipped on the workflow-only diff) passed. `Review fix: 38f3e815f8` applied two consistency suggestions: WC1 softened the script's overstated "§1.7(e) forbids staging agents" comment at three sites to "agent files fall outside §1.7(e)'s stageable-path scope, so they are modified live", and WC2 added a §1.6(f) anchor to the rule-1 agent-gating rationale. The consistency gate-check verified both at iteration 2.

**What was discovered:** The live smoke confirms the intended flip. `--check --files .claude/agents/dr-audit.md` now exits 1 with rule_7 (missing bootstrap) plus rule_6 findings, where it exited 0 with zero findings before the fix. Full-tree agent findings are bounded to rule_6×3 plus rule_7×20; the per-rule gate prevents the roughly 360-finding rule-4 blast radius the `IN_SCOPE_GLOBS` alternative would have produced. `--write` leaves all 20 agent files byte-identical (TOC-inert). For Step 4 within this track, `dr-audit.md` is the one non-uniform agent. Its `[conventions.md §1.5 ...](../workflow/conventions.md)` Markdown link fires rule_6 twice (the bare link-text `conventions.md` plus the unresolvable `workflow/conventions.md` target), so Step 4 must restructure it to the bare suffixed form rather than merely suffix it; its `implementation-plan.md` reference targets a non-in-scope artifact and must be backtick-wrapped. The `review-workflow-*` agents' `path/to/file.md` placeholders stay backticked, since suffixing them creates phantom refs.

**What changed from the plan:** none.

**Key files:**
- `.claude/scripts/workflow-reindex.py` (modified)
- `.claude/scripts/tests/test_workflow_reindex.py` (modified)

**Critical context:** A test-helper name collision surfaced during implementation. A new fixture initially redefined the module-level `_annotated_target_body()` helper, shadowing the existing one and regressing a pre-existing test; the fix reuses the shared helper. The reindex test runner is a flat single module, so future top-level helper additions must check for name clashes.

### Step 2 — commit 9c6947ad62, 2026-05-31T16:17Z [ctx=info]
**What was done:** Inserted the canonical bootstrap block (literal heading `## Reading workflow files (TOC protocol)`, body sourced verbatim from `design.md §"Bootstrap protocol for agent system prompts"`) at the TOC anchor of all 7 staged SKILL files. The two TOC-less skills (`create-plan`, `execute-tracks`) anchor the block after the frontmatter; the five TOC-bearing skills (`edit-design`, `migrate-workflow`, `review-workflow-pr`, `review-plan`, `code-review`) place it after the frontmatter and before the document-index delimiter, per the three anchor shapes in staged `conventions.md §1.8(d)`. Each block carries the skill's own role token and a phase line, and keeps its `conventions.md §1.8` reference backticked so it does not fire rule 6. The startup read-list `:roles:phases` suffixes were already complete from Track 4's D13 sweep, so no gap-fill was needed. The commit used `--no-verify` (the documented staged-workflow interim on this branch, since the live-inclusive reindex pre-commit hook stays RED until Phase 4 promotion).

**What was discovered:** `workflow-reindex.py --write` produced zero TOC churn beyond the bootstrap insertions, confirming the bootstrap heading is exempt from the rule 3/4 density checks and the TOC regions stay in sync. `--check` on the 7 staged files is green on rules 2/3/4/5/6/7/8; the only residue is rule_1 (missing line-1 workflow-sha stamp on staged copies), the documented Phase-4 promotion residue. Per-file role/phase tokens were derived from each file's existing TOC annotations and house-style refs.

**What changed from the plan:** none.

**Key files:**
- `staged-workflow/.claude/skills/{create-plan,execute-tracks,edit-design,migrate-workflow,review-workflow-pr,review-plan,code-review}/SKILL.md` (all modified)

**Critical context:** The bootstrap block body (role line plus phase line) is now fixed; Steps 3 and 4 reuse the same body with role/phase substitution and the same backticked `conventions.md §1.8` discipline. Anchor detection differs for Step 3 (10 prompts are prose-first / top-of-file; `design-review.md` is under-H1).

### Step 3 — commit e2d2853354, 2026-05-31T16:25Z [ctx=warning]
**What was done:** Inserted the canonical bootstrap block (literal heading `## Reading workflow files (TOC protocol)`, the same body Step 2 used, sourced from `design.md §"Bootstrap protocol for agent system prompts"`) into all 11 staged prompts under `staged-workflow/.claude/workflow/prompts/`, with per-prompt role/phase tokens drawn from the staged `conventions.md §1.8` enums. Placement follows §1.8(d): the 10 prose-first prompts take the top-of-file anchor (block before the existing TOC region, or at the literal top of `create-final-design.md`, which has no TOC region), and `design-review.md` takes the under-H1 anchor. The block keeps its `conventions.md §1.8` reference backticked. `--check` over the 11 staged paths is green on rules 2/3/4/5/6/7, with only the rule_1 missing-stamp residue. Committed `--no-verify` per the staged-workflow interim.

**What was discovered:** `create-final-design.md` carries no TOC region and no real `##` headings (its only heading-like lines sit inside the fenced `adr.md` template, which the fence exclusion skips), so it is the one prose-first prompt where the bootstrap block sits at the literal top of file with the prose body directly below. A negative-control check confirmed the rule-5 enum source is the staged `conventions.md` (an injected out-of-enum token fires rule_5d and the message names the staged path), so the D14/D15 staged-aware lookup is active for prompt validation. The bootstrap block's own role/phase lines are not enum-validated by the script; they were hand-checked against §1.8.

**What changed from the plan:** none.

**Key files:**
- `staged-workflow/.claude/workflow/prompts/{adversarial-review,consistency-gate-verification,consistency-review,create-final-design,design-review,dimensional-review-gate-check,review-gate-verification,risk-review,structural-gate-verification,structural-review,technical-review}.md` (all modified)

**Critical context:** Step 4 (next session) reuses the same block body but with the agent anchor shape (after frontmatter, no TOC region — agents carry no per-section annotations per D6) and adds the refs-only `:roles:phases` suffix sweep that prompts did not need (Track 4 handled prompt cross-file refs). If a live agent maps to the Phase A review-gate verifier, reuse the multi-role token set `orchestrator,reviewer-technical,reviewer-risk,reviewer-adversarial` used on `review-gate-verification.md`.

### Step 4 — commit 09e2e0c521, 2026-05-31T17:08Z [ctx=warning]
**What was done:** Inserted the canonical bootstrap block after the YAML frontmatter in all 20 live `.claude/agents/*.md` files and swept their outgoing in-scope workflow-doc refs to the `:roles:phases` form, one pass per file. Only `dr-audit.md` needed ref restructuring (a Markdown link → bare suffixed form); the other 19 carried pre-existing backticked spans. Per-agent role/phase tokens drawn from the §1.8 enums. The implementer spawn returned SUCCESS; the commit landed and pushed.

**What was discovered:** The HIGH-risk dimensional review (5 workflow-review agents: WC/WP/WI/WB/WS; workflow-only diff → baseline group skipped, hook-safety not fired) came back gate-GREEN (`workflow-reindex.py --check` exits 0 on all 20 agents — rules 6/7 fire per D17, role/phase tokens valid, bootstrap blocks present) but surfaced an inherited bootstrap-body defect present in all 38 files and the frozen `design.md`: the step-2 match rule never teaches a reader whose own role or phase is `any` to expand it (WP1/WI1 — blocker), the "first ~30 lines" read window truncates `conventions.md`'s ~80-line TOC (WP2), and the closing line over-asserts the suffix on every inline ref (WI2). Rule 7 is presence-only, so the body defect is gate-invisible. Record-only findings (no code change): the role enum has no generic "code reviewer" token, so `code-reviewer.md` / `test-quality-reviewer.md` are a forced-but-defensible `reviewer-dim-*` fit (WC2/WP4); the ~13-line block is inert per-spawn overhead on the baseline code-review agents with no offsetting Read-savings, accepted as the uniform-bootstrap design intent (WB1).

**What changed from the plan:** The body defect spans Steps 2-4's output and the frozen design, so it is ESCALATE-class. User chose ESCALATE → inline replan. The shared bootstrap body is corrected by D19 and re-propagated across all 38 files by the new HIGH-risk Step 5; this step's insertion + refs-sweep mechanics stand. The 19 agents' house-style `§1.5` ref took the fully-backticked span here (a deviation from this track's plan wording, which prescribes the whole-file-suffix + backticked-`§1.5` form); WC1's user-confirmed resolution suffixes all 20 in Step 5. The phase-`any` gloss the SKILLs carry was not mirrored on `dr-audit.md` / `pr-reviewer.md` (WP3) — mirrored in Step 5.

**Key files:**
- `.claude/agents/*.md` (20 files, modified — bootstrap block + refs sweep)

**Critical context:** Step 5 re-edits these 20 agents (corrected body + WC1 `§1.5` suffix + WP3 gloss), the 18 staged Step 2-3 files (corrected body), and staged `conventions.md §1.8(e)/(f)`. The dim-review loop ran iteration 1 only; no `FIX_REVIEW_FINDINGS` respawn or gate-check fired before ESCALATE. The body fix is the same template across all 38 files, sourced from the corrected `design.md §"Bootstrap protocol for agent system prompts"`.

### Step 5 — commit a7b7575e04, 2026-05-31T19:24Z [ctx=warning]
**What was done:** Re-propagated the D19-corrected bootstrap block body byte-identically across all 38 system-prompt files (7 staged SKILL, 11 staged prompts, 20 live agents), sourced from the corrected `design.md §"Bootstrap protocol for agent system prompts"`: step-1 keys the read window on the document-index delimiters, step-2 expands a reader's own `any` on either axis, the closing line gains the backtick-ref note. Aligned staged `conventions.md §1.8(f)` (delimiter-bounded READ_TOC, reader-side `any` SECTION_MATCH) and added the `§1.8(e)` reader-`any`-vs-citer-`any` distinction. Applied WC1 (the house-style `§1.5` ref takes the whole-file-suffix plus backticked-`§1.5` form on all 20 agents: 18 converted from the fully-backticked span Step 4 left, `dr-audit` already correct, `review-workflow-writing-style` carries no `§1.5` ref) and WP3 (mirrored the phase-`any` gloss onto `dr-audit` and `pr-reviewer`). Implementer commit a7b7575e04.

**What was discovered:** The HIGH-risk step-level dim review (5 workflow-review agents WC/WP/WI/WB/WS; hook-safety not fired on the workflow-only diff) found a next-layer body gap one granularity below D19: the file-level open/skip decision (the body's closing line and `§1.8(f)`'s file-level `MATCH` node) carried no `any`-expansion, so an `any`-phase reader still under-matched at file level (M1, should-fix); and D19's delimiter-bounded step-1 had no complement for a file with no TOC region, which `§1.8(d)` explicitly contemplates (M2, should-fix). Three suggestions followed: `§1.8(e)` stated only the reader-own-`any` half (M3), step-1 carried rationale padding (M4), step-1 used an em dash where a colon reads cleaner (M5). The `Review fix: 21c3c5fe91` closed all five; its propagation surfaced a self-introduced rule_8 trip (a bare `§1.8(d)` anchor in step-1 reads as a same-file ref on the 18 staged copies, while live agents are rule_8-exempt and would have masked it), fixed by backtick-wrapping `§1.8(d)` uniformly across all 38 files. The gate-check fan-out (WP/WI/WB/WS) returned all-VERIFIED, PASS at iteration 2. The dropped WC1 (the 11 staged prompts keep the fully-backticked `§1.5` span vs the agents' suffix form) stays a recorded Phase-4 deferred-drift item, not an in-track fix.

**What changed from the plan:** The user chose the in-step fix over an ESCALATE (the strict frozen-design precedent from Step 4): M1-M5 land in the 38 files plus staged `conventions.md §1.8(e)/(f)`, and the frozen `design.md §"Bootstrap protocol"` is not back-ported. So `design.md §"Bootstrap protocol"` now diverges from the on-disk body in two ways (the next-layer body content and the backtick-wrapped `§1.8(d)` anchor), both joining the Phase-4 `design-final.md` deferred-drift basket alongside the S1 §"Cross-reference convention" diagram. Step 6 (verification) is unaffected.

**Key files:**
- `.claude/agents/*.md` (20 files, live — corrected body, WC1 `§1.5` suffix, WP3 gloss)
- `staged-workflow/.claude/skills/*/SKILL.md` (7) and `staged-workflow/.claude/workflow/prompts/*.md` (11) — corrected body
- `staged-workflow/.claude/workflow/conventions.md` — `§1.8(e)/(f)` alignment

**Critical context:** For Step 6 and Phase 4: the scoped `workflow-reindex.py --check` (staged SKILL plus staged prompts ∪ `git ls-files '.claude/agents/*.md'`) is green on rules 2/3/4/5/6/7/8; the residue is 19 staged `rule_1` missing-stamp findings (documented Phase-4 promotion residue) and zero live-agent leak. Any future edit to the shared bootstrap body must run the scoped `--check` over the staged copies, not only agents (rule_8 is gated off for those files), and must backtick-wrap any cross-file `§X.Y(z)` anchor it introduces.

### Step 6 — verification (no code commit), 2026-05-31T19:47Z [ctx=info]
**What was done:** Ran the four read-only final-validation checks. No tracked file changed and no commit landed, the expected outcome per `## Idempotence and Recovery` ("Step 6: read-only checks, re-runnable with no side effects").

(1) Scoped `workflow-reindex.py --check --files` over the mechanically-derived in-scope set (7 staged SKILL copies, 11 staged prompt copies, unioned with `git ls-files '.claude/agents/*.md'` for the 20 live agents; 38 files) returns rules 2/3/4/5/6/7/8 green. The only findings are 18 rule_1 missing-stamp findings on the staged SKILL and prompt copies, the documented Phase-4 promotion residue that clears when staged copies gain their line-1 workflow-sha stamp. Zero live-agent leak: no finding on any `.claude/agents/` path, confirming the D17 per-rule applicability gate keeps rules 1/2/3/4/5/8 unreachable for agents.

(2) Static D7-premise check over `b722c11e6e..HEAD` (16 commits): every touched path sits under `.claude/` (live agents plus the reindex script) or this plan's own `docs/adr/ytdb-1023-workflow-toc/` dir. No commit touches another plan's `_workflow/**`, and none alters the `_workflow/**` artifact format (section names, mandatory artifacts, step-file schema). So `/migrate-workflow` replay is a content no-op for this plan.

(3) `measure-read-share.py` smoke from this worktree runs cleanly (exit 0) and emits a percentages section, not a skip notice. I4 holds: shares only, no absolute token counts, no absolute paths.

(4) Documented the post-merge two-branch `/migrate-workflow` acceptance procedure (D18) for the track-completion episode: after this plan squash-merges to develop, the user runs `/migrate-workflow` in a fresh session on two candidate branches rebased onto post-plan develop, confirming clean completion (a stamp-rewrite-only normalization or a silent skip). Candidate pool: `ytdb-612-rollback-log`, `read-cache-concurrency-bug`, `ytdb-614-property-map`, `failed-wal-recovery`; the two picks and the outcome resolve post-merge and land in the Track 5 completion episode and Phase 4 `adr.md`.

**What was discovered:** The three rule_1 counts in the track text denote one Phase-4-residue class at different scopes: 49 is the full 49-file staged-workflow tree (every staged `.md` lacks the line-1 stamp pre-promotion), 18 is the mechanically-derived in-scope set (7 staged SKILL plus 11 staged prompts; the 20 live agents are rule_1-exempt by the D17 gate). The Step 5 episode's "19 staged rule_1" (Episodes §Step 5) was an off-by-one against the precise in-scope count of 18; per episode immutability it stands, corrected here. Phase 4's `adr.md` residue note should cite the "49 full-tree / 18 in-scope-set" pair.

**Key files:** none (read-only verification; no file created or modified).

**Critical context:** For Phase 4 promotion, an unscoped full-tree `--check` clears the 49 staged rule_1 missing-stamp findings; the live SKILL and prompt rule_7 findings (live copies stay develop-state until promotion) are the other half of that transition. The reusable in-branch verification invocation is the scoped `--check --files <staged SKILL ∪ staged prompts ∪ git ls-files '.claude/agents/*.md'>`; an unscoped full-tree `--check` stays RED until Phase 4 by design.

## Validation and Acceptance

After this track lands (in-branch):

- The reindex agent-scope fix (D17) is in `workflow-reindex.py`: rules 6 and 7 fire on live `.claude/agents/**/*.md`, and no agent file is flagged for a missing TOC (rule 2) or a missing annotation (rule 4) — rules 1/2/3/4/5/8 do not apply to agents. Regression tests cover a missing bootstrap heading (rule 7 fires), a non-subset or missing-suffix agent ref (rule 6 fires), an agent's in-prose `§X.Y` (no rule-8 finding), an agent with un-annotated `##` headings (no rule-2 and no rule-4 finding — the 360-vs-20 blast-radius case), and `--write` leaving agent files TOC-inert (no TOC region injected).
- Every in-scope system-prompt file (38 total: 7 SKILL.md, 11 prompts, 20 agents) carries the bootstrap block at its anchor (under-H1 / after-frontmatter / top-of-file per §1.8(d), by the file's actual shape), identifiable by the literal heading `## Reading workflow files (TOC protocol)`. Rule 7 is presence-only (literal-heading match); block position and body correctness are hand-authored, not validated.
- The bootstrap block body across all 38 files carries the D19-corrected reader-side match rule (a reader expands its own `any` on either axis), the delimiter-bounded read window (keyed on the `<!--Document index start-->`/`<!--Document index end-->` delimiters, not a fixed line count), and the backtick-ref note. The staged `conventions.md §1.8(f)` read-decision flow carries the same `any`-expansion, and `§1.8(e)` carries the reader-`any`-vs-citer-`any` distinction. Rule 7 validates presence only, so body correctness is hand-verified by the Step 5 dimensional review.
- Every outgoing workflow-doc reference in each of the 20 agent files carries the `:roles:phases` suffix (sub-section pins use a whole-file suffix plus a separate backticked `§X.Y`; non-annotatable targets are backtick-wrapped). The house-style `§1.5` reference uses the uniform whole-file-suffix + backticked-`§1.5` form across all 20 agents (WC1).
- Every workflow-doc reference in the 7 SKILL.md startup read-lists carries the `:roles:phases` suffix (verified — Track 4's D13 converted most; Track 5 gap-fills).
- The `workflow-reindex.py --check` is green on rules 2/3/4/5/6/7/8 over a **mechanically-derived** in-scope set — the staged SKILL and staged prompts under `_workflow/staged-workflow/` together with `git ls-files '.claude/agents/*.md'`, not a hand-listed file set. Documented residue, not defects: the 49 rule_1 missing-stamp findings on staged copies (cleared at Phase-4 promotion) and the rule_7 findings on the **live** SKILL and prompt files (Track 5 bootstraps the staged copies; the live copies stay develop-state until Phase 4). An unscoped full-tree `--check` stays RED until Phase 4 by design.
- The static D7-premise check passes: no commit in this plan alters `_workflow/**` artifact *format* (the migration-replay surface — section names, mandatory artifacts, step-file schema). Edits to this plan's own `_workflow/` planning documents are expected and out of migration scope. So `/migrate-workflow` replay is a content no-op for this plan.
- `python3 .claude/scripts/measure-read-share.py` runs cleanly from this worktree as a smoke test (output-format inspection; Phase 4 commits the real output).

Post-merge acceptance (D18 — documented procedure, not an in-track commit):

- After this plan squash-merges to develop, the user runs `/migrate-workflow` in a fresh session on two candidate branches rebased onto post-plan develop, confirming clean completion (a stamp-rewrite-only normalization or a silent skip). The two branches and the outcome are recorded in the Track 5 completion episode and Phase 4 `adr.md`.

<!-- Phase A note: per-step acceptance is captured in the bullets above plus
Step 1's named regression tests. No EARS/Gherkin test-method-name lines apply —
this track's acceptance is `workflow-reindex.py --check` green and the script's
stdlib regression tests, not Java test methods. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Every step is one commit; rollback is `git revert <commit>` (or `git reset --hard HEAD` before the commit lands). There is no durable or runtime state — each step is a Python script edit (Step 1), Markdown edits (Steps 2-5), or read-only verification (Step 6).

- **Step 1 (script):** re-runnable. The discovery-scope and per-rule-gate edit is idempotent (re-applying it is a no-op). Revert restores the prior `IN_SCOPE_GLOBS` and `validate` loop. Forward-safe: on develop and non-workflow-modifying branches no staged copy exists, and post-promotion agents already carry their suffixes plus bootstrap, so the new scope finds them green.
- **Steps 2-5 (bootstrap, refs, body correction):** the bootstrap block is keyed by its literal heading, so re-insertion and body re-propagation are detectable and idempotent (rule 7 matches the existing heading; the corrected body is a fixed template applied uniformly). The `:roles:phases` sweep is idempotent text substitution. If a step's commit fails the pre-commit `--check`, fix the flagged ref or anchor and re-commit — no partial state persists between attempts.
- **Steps 4 and 5 depend on Step 1:** if Step 1's gate is wrong, the agent suffixes (Step 4) and the body re-propagation's `--check` (Step 5) cannot be validated. Recovery: fix Step 1 first (the intra-track dependency is declared in Interfaces). Do not land Step 4 or Step 5 against a broken gate.
- **Step 6 (verification):** read-only checks, re-runnable with no side effects. A non-residue RED in `--check` is fixed in the owning step (1-5), not in Step 6.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

### In-scope file set

- `.claude/scripts/workflow-reindex.py` — the D17 agent-scope fix (live `.claude/agents/**` into rules 6/7 only). Live path (scripts are not staged).
- 7 SKILL.md files under `.claude/skills/` — bootstrap + startup-read-list suffix verify/gap-fill (staged).
- 11 prompts under `.claude/workflow/prompts/` — bootstrap only (staged).
- 20 agent files under `.claude/agents/` — bootstrap insertion + refs sweep (Step 4), then bootstrap-body correction + WC1 `§1.5` suffix + WP3 gloss (Step 5), both live.

### Cross-file ref target classes (Step 4 authoring; §1.5 form corrected in Step 5 per WC1)

- **Suffix** (`:roles:phases`): in-scope `.claude/workflow/**` docs and prompts, and `<dir>/SKILL.md` targets.
- **Backtick-wrap, never suffix** (non-annotatable or out-of-in-scope): `.claude/docs/*` (`architecture.md`, `testing-details.md`, `mcp-steroid/skills.md`), `house-style.md`, `design*.md`, the Phase 4 final artifacts, `CLAUDE.md`, `MEMORY.md`, and any agent-file-as-target. This discipline is gate-load-bearing, not stylistic: the script's cross-file out-of-scope allowlist is `CLAUDE.md`-only, so any *other* target left bare fires rule 6 twice (missing-suffix, then target-not-in-scope). Keep the inserted bootstrap block's `conventions.md §1.8` reference backticked for the same reason.
- **Sub-section pins**: whole-file `name.md:roles:phases` plus a separate backticked `§X.Y` (the combined cross-file-with-subsection form fails rule 8 in live prose); never claim an H4-only token (rule 6 union is `##`/`###` only). The whole-file suffix subset-validates against the file-wide union, not the pinned section, so author the slice to reflect the citer's intent honestly — the validator will not catch a too-broad-for-the-section claim.
- **Non-uniform Step-4 cases**: `dr-audit.md` carries a Markdown link `[conventions.md §1.5 ...](../workflow/conventions.md)`; the bare scanner matches both the link text and the `workflow/conventions.md` target (which does not resolve in the file lookup), so it must be *restructured* to the bare suffixed form, not merely suffixed. The `review-workflow-*` agents carry `` `path/to/file.md` `` template placeholders — backticked, and they must stay backticked (suffixing them creates phantom refs).

### Out-of-scope

- `CLAUDE.md` — general-purpose, not workflow-specific.
- Non-workflow skill files — no Reads of workflow files at runtime.
- Per-section annotations on any file — Track 4 covered all annotated files.
- Workflow-doc content — Track 4 / Track 1.
- Scripts — Tracks 2, 3, **except** the D17 agent-scope fix to `workflow-reindex.py` that this track lands in Step 1.

### Inter-track dependencies

- **Depends on Track 1.** Bootstrap block content names role/phase tokens drawn from the §1.8 locked enums.
- **Depends on Track 2.** The reindex script's rule 7 (bootstrap presence check) and rule 6 (cross-file ref suffix on SKILL.md startup read-lists and agent files, hand-written) enforce this track's output. For agent files these rules fire only after this track's own D17 fix (Step 1) lands — Track 2's script shipped with the live-agents path out of scope. Rule 8 (in-file ref auto-stamp) also fires on this track's edits but is mechanical: any in-file refs the bootstrap insertion or refs sweep introduces get auto-stamped by `workflow-reindex.py --write` before commit.
- **Depends on Track 4.** Per-section TOC and annotations on the 7 SKILL.md and 11 prompts must exist before the bootstrap block points at the TOC-aware reading protocol. The agent-side `:roles:phases` suffix also points at annotated targets.
- **Intra-track: Steps 4 and 5 depend on Step 1.** The agent refs sweep (Step 4) and the body-correction `--check` (Step 5) need the D17 agent-scope fix (Step 1) so `--check` validates the suffixes they write.
- **Sets up the post-merge acceptance procedure.** The real two-branch migration verification is the final acceptance gate from the issue, but it runs post-merge (D18) — this track documents the procedure and verifies the D7 no-op premise in-branch; it does not close the gate in-branch.

### Library/function signatures touched

- `workflow-reindex.py` discovery/validation internals (D17, Step 1): a **separate rules-6/7-only citing scope** for live `.claude/agents/**` (mirroring the existing `discover_bootstrap_scope` set that rule 7 already consults) plus a per-rule applicability gate in the `validate` loop — NOT an addition to `IN_SCOPE_GLOBS`, which would route agents through all eight rules and over-fire rules 2/3/4/5/8. The exact function names are an implementation detail of Step 1; no public signature changes.
- Otherwise this track is bootstrap-block insertion and text substitution in Markdown files plus the in-branch verification smokes and the documented post-merge `/migrate-workflow` procedure.

## Base commit

b722c11e6ed7766b3e1f3b309a9f64b2139fb64f
