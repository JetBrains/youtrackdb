# design-author params — Track 1, round 2

- target: tracks
- output_path: docs/adr/ai-tells-shrinkage/_workflow/plan/
- plan_dir: docs/adr/ai-tells-shrinkage/_workflow/plan/
- research_log_path: docs/adr/ai-tells-shrinkage/_workflow/research-log.md
- round: 2

Edit `docs/adr/ai-tells-shrinkage/_workflow/plan/track-1.md` in place. **Do not
touch line 1** (the workflow-sha stamp) or the Phase-A placeholders. Re-ground
only the passages below; leave the rest of your round-1 prose intact.

## flagged_passages (re-draft these)

From the cold readability auditor:

1. **track-1.md:74-80 (DR2 KEEP set), over-dense** — the ~19-rule kept-list is a
   comma-chain crammed into one parenthetical. Break it into a short grouped
   form: name the families ("the concision- and specificity-forcing sentence
   rules", "the structural and document-shape families") and give at most a few
   representative examples, rather than enumerating all nineteen inline. The
   reader needs to grasp "most of the borderline bucket stays", not memorize the
   list.
2. **track-1.md:142-146 (DR4 risks) + the same terms in Context, should-fix
   glossary** — "stamp base", "stamp-advance", and "drift gate" are used in a
   load-bearing obligation with no gloss. Gloss them once at first use: a
   workflow-sha stamp on line 1 of each `_workflow/` artifact records the
   workflow-format commit it was written against; the drift gate compares that
   stamp to HEAD at session start and routes to migration when they diverge;
   live edits to `.claude/workflow`/`.claude/skills`/`.claude/agents` advance
   HEAD past the stamp, so the gate would fire on the branch's own edits unless
   `/migrate-workflow` re-stamps to HEAD. Keep it to one or two plain sentences.
3. **track-1.md:17-19 (Big Picture), over-dense** — the five-item concealment-only
   catalogue is spliced into one sentence after the colon. Break the five removed
   rules onto their own list lines (one per line) so the reader can scan the
   REMOVE set.
4. **track-1.md:88-91 (DR2 rationale), hard-to-read** — replace the hyphenated
   noun-stack "vocabulary-and-em-dash-only cut" with the plain phrase already
   used in the Alternatives bullet, e.g. "cutting only the vocabulary list and
   the em-dash cap".

## Decision Records to ADD (from the absorption check — load-bearing decisions
## currently present only as scattered prose, not as formal DRs)

Add three new four-bullet Decision Records to `## Decision Log`, after DR6,
seeded from the research log's Gate iteration-1 resolutions (A1, A3, A4). Keep
the substance that is already in Context/Interfaces/Invariants, but give each its
own DR so the Decision Log is the complete canonical carrier:

- **DR7 — the exhaustive consumer-coverage acceptance contract.** Minimal tier
  has no plan-level structural review, so the consumer inventory is the only
  safety net against a phantom cross-reference shipping. The acceptance gate: run
  the §1.5 rename-safety grep (extended for the sycophantic/signposting/copula/
  knowledge-cutoff names) and, for every hit (~47 files: ~21 agents, ~21
  workflow, ~5 skills), either edit it to the post-shrink rule set or record it
  confirm-benign; plus a manual paraphrase scan of the named prose consumers
  (grep cannot catch a reference phrased without the section name). Alternatives:
  rely on per-file judgment with no contract (no safety net on this tier).
  Risks: mcp-steroid unreachable → grep can miss a paraphrase, hence the manual
  scan. Implemented in: this track.
- **DR8 — the post-shrink Tier-B / chat AI-tell subset is four sections.** After
  removal the subset is `§ Orientation`, `§ Plain language`, `§ Banned sentence
  patterns`, `§ Banned analysis patterns` (the last two survive minus their
  removed bullets); `§ Banned vocabulary` and `§ Em-dash discipline` drop. Update
  `conventions.md §1.5`, the ~47 replicated bootstrap-slug lines, and
  `house-conversation.md` in the same edit. The chat-only sycophantic/signposting
  guard is carried as an inline rule in `house-conversation.md`, not a
  cross-reference to a removed bullet. Alternatives: keep the removed sections in
  the code-comment tier only. Risks: the inline chat carrier must be literal, not
  a dangling reference. Implemented in: this track.
- **DR9 — the always-loaded `ai-tells/SKILL.md:3` description is in scope.** Its
  `description` front-matter is loaded into every session's skill list and
  hard-codes removed tells ("delve", "foster", "em dash overuse", "knowledge-
  cutoff disclaimers"). Drop the removed-tell names; keep the kept ones (negative
  parallelism, Title Case). Alternatives: edit only the skill body. Risks: a
  stale always-on surface misadvertises and costs context budget (the
  Workflow-machinery lens). Implemented in: this track.

Cross-reference DR7-DR9 from the Invariants/Interfaces prose where the substance
already lives (a short "see DR7" is fine) so the duplication stays one logical
decision.

## House style

Same as round 1: write to the live `house-style.md` rules, cold-readable for a
mid-level reader who has only the finished track file.
