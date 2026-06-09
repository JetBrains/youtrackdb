# Research Log — Workflow Economic Model

> Anchor (initial user request) plus continuous-log capture of Phase 0
> (research) decisions, discoveries, and open questions. Entries are
> durable across `/clear`, `/compact`, and Phase 0 → Phase 1 handoff.
> Phase 1 (planning) reads this file as the primary input to Decision
> Records and Architecture Notes.

## Initial request
<!-- Written once by `create-plan` Step 2, immediately after the user
provides the aim. Plan-at-start section, not a continuous log; no
timestamp or `[ctx=<level>]` field. Captures the user's framing of
the goal in the user's own words. Phase 1 reads this as the
authoritative aim, replacing the "ask the user for the aim" step that
would otherwise repeat across a session boundary. Format:

**User's words:** <verbatim from the user's first message after the
Step 2 prompt; quoted exactly to preserve the user's framing>
-->

**User's words:** "Let us create workflow economical model at the next session."

**Context that produced the aim (not the user's words, kept for the next session).** The request lands at the end of a completed cost-anatomy study of the development workflow: 12 open-speedup sessions ($322 total, $151.89 orchestrator), decomposed per phase and per content type, with durable reproducible scripts under `tools/cost-analysis/`. The "economic model" is the next step up from that measurement — turn the per-phase, per-content-type breakdown into a model that explains and predicts what a workflow run costs and which lever moves it. The study is the empirical foundation and the validation set; this branch builds the model on top of it.

## Decision Log
<!-- One entry per decision made during research. Format:
- <ISO timestamp> [ctx=<level>] <one-line decision>
  - **Why:** <rationale in one sentence>
  - **Alternatives rejected:** <X (reason); Y (reason)>
-->

- 2026-06-09T12:10Z [ctx=safe] Ground the model on the measured 12-session study, not on a first-principles token count.
  - **Why:** the empirical breakdown already reproduces session and per-phase actuals to the dollar (d6fb4ed8 orchestrator $29.17, session $84.75 reproduced exactly), and it surfaces effects a first-principles model would miss — retained-thinking re-read and cold-rewrite.
  - **Alternatives rejected:** pure first-principles token model (ignores the measured retained-thinking and TTL-rewrite terms); per-session $ regression alone (loses the content-type causal structure that tells you which lever to pull).

- 2026-06-09T12:10Z [ctx=safe] The model is descriptive first; predictive and prescriptive layers are stretch goals gated on the descriptive fit.
  - **Why:** a descriptive model (decompose a known run into its cost terms) is directly validatable against the 12-session set; prediction and lever-ROI ranking are only trustworthy once the descriptive form reproduces actuals.
  - **Alternatives rejected:** jump straight to a predictive plan-time forecaster (no validated cost terms to forecast with yet); a single global cost-per-turn average (hides the 4×-between-phase variation — migrate floor 53% vs B+C 28%).

## Surprises & Discoveries
<!-- Code-research and external-research findings that shape the plan.
Format:
- <ISO timestamp> [ctx=<level>] <one-line finding>
  - **Source:** <PSI find-usages of Foo#bar | paper title | library docs URL>
  - **Implication:** <how this affects the plan>
-->

- 2026-06-09T12:10Z [ctx=safe] Half of all model-reasoning cost is re-reading retained thinking, not generating it: the tail (write+read of retained signed thinking blocks) is $31.25 vs $31.83 of fresh generation across the 12 sessions.
  - **Source:** `tools/cost-analysis/wf-content-type-cost.py` ALL(12) — Model reasoning row write 13.77 / read 17.48 / output 31.83.
  - **Implication:** the model must carry retained-thinking re-read as a first-class cost term, distinct from generation output; it cannot be folded into "output." It is ~21% of total orchestrator spend and currently has no issue filed.

- 2026-06-09T12:10Z [ctx=safe] The retained-thinking tail is phase-dependent: 57% of reasoning cost in exec-tracks B+C, down to 36% in impl.
  - **Source:** per-phase tail-vs-generation split (B+C 17.47/13.33; impl 2.74/4.77; small 3.76/4.29; create-plan 5.29/6.74; migrate 2.01/2.69).
  - **Implication:** the tail-term coefficient scales with average session length (turns); long phases (B+C) are tail-dominated, short phases (impl) are generation-dominated. The model needs a per-phase or turn-count-driven tail coefficient, not a constant.

- 2026-06-09T12:10Z [ctx=safe] Floor cost is NOT constant per session — it scales with turn count. B+C pays ~$5.27/session of floor vs migrate ~$3.04/session, though floor SIZE is constant (~77-82K).
  - **Source:** floor$/session computed from per-phase floor totals (B+C 21.06/4 sess; migrate 6.07/2 sess).
  - **Implication:** the floor term is `floor_size × turns × read_rate + cold_rewrites × write_rate`, not a per-session constant. Corrects the earlier handoff framing. Floor SHARE falls in long sessions (everything else grows faster), but floor DOLLARS rise.

- 2026-06-09T12:10Z [ctx=safe] Only impl is write-bound (whole-phase write:read 1.72); B+C is balanced (0.95); small/create-plan/migrate are read-bound (~0.56-0.67).
  - **Source:** per-phase TOTAL row write$ vs read$ (impl 8.41/4.88; B+C 29.96/31.50; small 6.57/9.78; create-plan 7.05/12.43; migrate 3.12/5.54).
  - **Implication:** cold-rewrite (YTDB-1097, TTL-expiry re-write) exposure is phase-specific and peaks in impl — short sessions, large per-step writes, TTL gaps between steps, little warm-read to amortize. The model's cold-rewrite term is largest for impl, near-zero for read-bound phases.

- 2026-06-09T12:10Z [ctx=safe] Workflow-doc share is phase-stable at ~11% across all three exec-tracks phases; lower for create-plan (7%, it authors docs rather than re-reading rulebooks) and migrate (2%, artifacts move through git/Bash not Read).
  - **Source:** per-phase Workflow-process-docs row (B+C/impl/small all 11%; create-plan 7%; migrate 2%).
  - **Implication:** in exec-tracks phases the doc term can be modeled as a fixed fraction of phase cost; create-plan and migrate are structurally different and need their own coefficients.

- 2026-06-09T12:10Z [ctx=safe] Model reasoning is ~95% hidden extended thinking, billed twice (generated as output, then retained in the cached prefix and re-read every turn); driven by a ~2K-token/turn baseline across ~620 turns (a tool call on ~95% of turns).
  - **Source:** [[workflow-doc-cost-findings]] correction (d6fb4ed8: 159K of 165K output tokens were thinking; 66 signed thinking blocks); [[workflow-orch-content-cost-handoff]].
  - **Implication:** turn count is the master driver of the whole model. The mechanism is interleaved-thinking-with-tools forcing retention of signed thinking blocks. The single lever the model should expose is fewer/coarser turns.

- 2026-06-09T12:10Z [ctx=safe] A validated, reproducible dataset + analyzers already exist and survive `/clear`.
  - **Source:** `tools/cost-analysis/{wf-content-type-cost,wf-doc-cost-analyzer,wf-orch-distribution}.py` + README; pricing/dedup via `session-stats.py` ((message.id, requestId) dedup, live Opus 4.8 rates 0.50/6.25/25 per MTok).
  - **Implication:** the model has a ready acceptance test — fit the cost terms, then confirm the model reproduces the per-phase actuals ($75.22 / $18.24 / $20.72 / $26.31 / $11.40) within tolerance.

## Open Questions
<!-- Items flagged during research but not yet resolved. Carry into
Phase 1 as Decision Records to write or as Architecture Notes to fill.
Format:
- <ISO timestamp> [ctx=<level>] <one-line question>
  - **Blocking:** <what plan element this blocks>
-->

- 2026-06-09T12:10Z [ctx=safe] What is the functional form, and does it have a superlinear (≈quadratic-in-turns) read term?
  - **Blocking:** the core model equation. Content added at turn k is re-read on every remaining T−k turns, so for a prefix that grows with turns the cumulative read cost is structurally O(T²). The descriptive form must decide whether to model this explicitly (prefix-growth × turns) or approximate it per phase. This is what predicts long-session blowup (why B+C is half the spend).

- 2026-06-09T12:10Z [ctx=safe] What is the modeling unit and driving variable — per-phase with turn count (or session length) as x?
  - **Blocking:** how the cost terms are parameterized. Data is per-session/per-phase; per-track is too granular for the current dataset.

- 2026-06-09T12:10Z [ctx=safe] Which model inputs are knowable at plan time, for the predictive layer?
  - **Blocking:** any forecasting use. Can turn count, fan-out width, and phase mix be predicted from a change's complexity tier (the plan-slimization `full`/`lite`/`minimal` tiers)? If not, the model stays descriptive.

- 2026-06-09T12:10Z [ctx=safe] What does the model output, and who consumes it?
  - **Blocking:** the deliverable shape. Candidates: a per-phase $ forecast for a planned change; a lever-ROI ranking (which of floor-trim / bound-thinking / cold-rewrite-fix / doc-views pays most for a given run); a budget-setting input. Pick one primary consumer.

- 2026-06-09T12:10Z [ctx=safe] What is the acceptance criterion — within what tolerance must the model reproduce the 12-session per-phase actuals?
  - **Blocking:** the validation gate for Phase 1 → implementation. The residual bucket is ~6% (tool-output undercount), so sub-6% per-phase error is likely the floor of achievable accuracy.

- 2026-06-09T12:10Z [ctx=safe] How are the named cost levers connected to the model as parameters, so the model can score them?
  - **Blocking:** the prescriptive layer. Levers: floor/tool-schema trim (YTDB-1094), bound/manage retained thinking (no issue), cold-rewrite re-warm (YTDB-1097), sub-agent output routing (YTDB-883), per-(role,phase) doc views ([[workflow-views-branch]]). Each should map to a coefficient the model can vary.
