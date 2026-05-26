# Handoff — YTDB-965+842+975 design.md restructure application

## Session state at handoff

Context level reached `warning` (38%) during the multi-mutation restructure
application; pausing here before continuing into `critical`.

**Branch:** `ytdb-965-dd-decision-log`
**Plan dir:** `docs/adr/ytdb-965-dd-decision-log/_workflow/`
**Last completed mutation:** Mutation 7 (logged in `design-mutations.md`)

## What was decided in this session (before /clear)

The user and I worked through a multi-step restructure of the YTDB-965+842+975
design.md. Major architectural decisions agreed:

1. **Eliminate `feasibility-review.md`.** Re-home its concerns: gate verdicts
   go to `decision-log.md` (as `(Phase 1a gate-verdict)`-tagged Plan-time
   Decisions entries); raw reviewer output goes to per-reviewer files under
   `_workflow/design-reviews/cycle-N-iter-M/`. The Phase 1b auto-resume
   detector scans `decision-log.md` for the most recent `(Phase 1a → 1b)`
   entry instead of reading `feasibility-review.md`.

2. **Per-mutation review fan-out** replaces the "Phase 1a gate" as a
   phase-level mechanism. Reviewers fire on every `edit-design` mutation
   (including the first `phase1-creation`). The "Phase 1a gate" as a distinct
   concept dissolves into `edit-design`'s Step 4 reviewer fan-out.

3. **User-review checkpoint with batching.** Decision-shaped findings queue
   between iterations; user observations and queued findings batch into one
   coordinated set of `edit-design` mutations at user checkpoints. User
   signals "ready for Phase 1b" to exit Phase 1a.

4. **Kind-scoped directory naming.** `_workflow/design-reviews/` for design
   review output; forward-extends to `_workflow/plan-reviews/`,
   `_workflow/track-reviews/`, `_workflow/code-reviews/` for other phases.

5. **Prompt-by-reference spawn protocol** as a new `conventions.md §1.4.X`
   sub-section. Orchestrator passes prompt file paths plus small inputs to
   sub-agents; never loads prompt bodies into its own context. Applies to
   ALL sub-agent spawns workflow-wide.

6. **Domain reviewers stay separate, not collapsed.** Six new design-doc
   prompt files under `.claude/workflow/prompts/`: `feasibility-review.md`,
   `adversarial-design-review.md`, `crash-safety-design.md`,
   `concurrency-design.md`, `performance-design.md`, plus four
   workflow-changes siblings (`workflow-consistency-design.md`,
   `workflow-context-budget-design.md`,
   `workflow-instruction-completeness-design.md`,
   `workflow-prompt-design-design.md`). The four workflow-changes design
   prompts cite the existing code-side `.claude/agents/review-workflow-*`
   for dimensional taxonomy.

7. **Aggregator sub-agent.** New `.claude/workflow/prompts/aggregator.md`
   reads per-reviewer files in `cycle-N-iter-M/`, returns structured findings
   object to orchestrator, writes one-line gate-verdict summary to
   `decision-log.md`. Optional per-iter `summary.md`.

## Completed mutations this session

- **Mutation 6** — `structural-rewrite`: replaced §"Phase 1a feasibility-review
  gate" (~120 lines) with §"Per-mutation design review fan-out" (~145 lines).
  Logged with BLOCKER REMAINS — manual resolution required (6 structural
  contradictions vs the rest of the still-stale doc; these close as
  Mutations 8-13 land).
- **Mutation 7** — `section-add`: added §"Design-doc review directory shape"
  between the fan-out section and §"Phase 1a design-iteration rationale".
  PASS, 0 blockers, 0 should-fix, 5 cold-read suggestions logged for
  next-pass consideration.

## Remaining mutations (in order)

The next session should apply these via `/edit-design` (one mutation per
invocation). Each closes specific blockers from Mutation 6's cold-read.

1. **Mutation 8** — `structural-rewrite` on §"Phase 1b plan derivation and
   ESCALATE back-edge" (currently lines 659-700). Closes Mutation-6 blocker
   F4 + F5 (auto-resume detector pointing at the vanished `feasibility-review.md`
   and the ESCALATE entry shape). Replacement text drafted in the user's
   prior planning message under "section 8".

2. **Mutation 9** — `structural-rewrite` on §"Overview" (currently lines
   4-22). Closes Mutation-6 blocker F1, F2 (the line-12 / line-16 / line-22
   stale phrasing) + should-fix items F7 (`conventions.md §1.4` ref) and F9
   (`_workflow/design-reviews/` in §1.2 enumeration) and F10 (finding-ID
   prefix list update). Replacement text drafted under "section 1".

3. **Mutation 10** — `structural-rewrite` on §"Core Concepts" (currently
   lines 24-46). Closes Mutation-6 blocker F1 + should-fix F7. Adds 4-5
   new Core Concepts entries (per-mutation review fan-out; design-doc-scoped
   reviewer prompts; aggregator; user-review checkpoint; prompt-by-reference
   protocol). Updates Phase 1b + ESCALATE entries to reference decision-log
   instead of feasibility-review. Replacement text drafted under "section 2".

4. **Mutation 11** — `structural-rewrite` on §"Class Design" (currently
   lines 48-154). Diagram + prose rewrite. Drops `feasibility_review_log_md`
   node; adds `design_reviews_dir`, `aggregator_subagent_prompt_md`, three
   domain-design prompt nodes, one workflow-changes-design-prompts collapsed
   node. Re-wires arrows: aggregator writes to decision-log, edit-design
   spawns reviewers. Closes Mutation-6 blocker F3 + should-fix F9.
   Replacement text drafted under "section 3".

5. **Mutation 12** — `structural-rewrite` on the Phase 1a design-iteration
   sequence diagram inside §"Workflow" (currently lines 200-219). Adds the
   reviewer fan-out, aggregator, autonomous chain, user-checkpoint loop.
   Replacement text drafted under "section 4".

6. **Mutation 13** — `structural-rewrite` on the Phase 1a → Phase 1b
   sequence diagram inside §"Workflow" (currently lines 222-282). Replaces
   the gate-PASS flow with the user-signal exit + decision-log auto-resume
   detection. Closes Mutation-6 blocker F2 + F4. Replacement text drafted
   under "section 5".

7. **Mutation 14a-d** — `content-edit`s for minor deltas:
   - §"Phase 1a design-iteration rationale" (integration-point paragraph)
   - §"Decision-log file shape" (annotation list + Lifecycle bullets +
     Edge cases bullet on gate-verdict / ESCALATE entries — closes
     Mutation-6 blocker F5)
   - §"Phase 0 → Phase 1a transition" (post-ack paragraph)
   - Opening section list at line 22 (no — already addressed by
     Mutation 9 Overview rewrite)
   - §"Cross-reference tier mapping" — add `conventions-execution.md`
     row for spawn-protocol cross-reference

8. **Mutation 15** — `conventions.md §1.4.X` addition (NOT via /edit-design
   — direct workflow file edit). New sub-section "1.4.X Sub-agent spawn
   protocol — prompt by reference". Body drafted under "section 9" of the
   prior planning message.

After Mutation 15: run one final whole-doc cold-read to confirm all
Mutation-6 blockers have closed and the document tells one consistent story
end-to-end. Then commit and push.

## Where the replacement text lives

The full replacement text for each remaining mutation is in the long
"draft the full design.md replacement sections" message the assistant sent
before the user said "start applying". That message is in the conversation
history but will be wiped by `/clear`. **Recovery path on resume:** ask the
user to re-paste the prior draft message, or re-derive the replacement
text from the architectural decisions listed in this handoff plus the
current state of design.md (which already has Mutations 6 + 7 applied).

The architectural decisions section above is sufficient for re-deriving
the replacement text section-by-section if the prior draft is unavailable.

## Tasks (still in TaskList)

- #1 [in_progress] Apply new section bodies to design.md — 2 of 3 done
  (Mutations 6, 7 complete; Mutation 8 still pending)
- #2 [pending] Apply front-matter rewrites to design.md — Mutations 9-13
- #3 [pending] Apply minor content-edits across design.md — Mutation 14a-d
- #4 [pending] Add conventions.md §1.4.X spawn protocol — Mutation 15

## Resume protocol

1. Read this handoff file end-to-end.
2. Read `design.md` and `design-mutations.md` for current state.
3. If the prior draft message is available, use the drafted text for each
   remaining mutation. If not, ask the user to re-paste or re-derive from
   the architectural decisions section above.
4. Continue with **Mutation 8** (§"Phase 1b plan derivation" structural-rewrite).
5. After each `/edit-design` invocation completes, log to `design-mutations.md`
   and move to the next mutation.
6. After Mutation 15 lands, run one final whole-doc cold-read.
7. Delete this handoff file as part of the final cleanup commit (covered
   by the Phase 4 `_workflow/` cleanup).
