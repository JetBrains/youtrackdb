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

- 2026-06-10T05:26Z [ctx=safe] The primary consumer is the lever-ROI ranking, but it is gated on more functional-form research before the build starts (user: "research more before we start lever-ROI ranking").
  - **Why:** the ranking only ranks correctly once each lever maps to the cost term it actually moves, and the read term's exponent decides which levers compound. Resolving open Q#1 (below) is the prerequisite.
  - **Alternatives rejected:** per-phase $ forecast as primary (needs plan-time-knowable inputs that open Q#3 has not settled); budget-setting input (shallower than ranking, and ranking subsumes it); descriptive-only stop (forgoes the actionable payoff the user wants).

- 2026-06-10T05:26Z [ctx=safe] Model the read term with the explicit two-term integral `READ·(a·T + ½·b·T²)`, with a LIVE, uncapped quadratic component; do not collapse it to a fixed `T^1.4` power.
  - **Why:** the studied sessions never compact (verified: zero `real_prefix` sustained drops; the prefix peaks at the last turn of every session), so the quadratic term is uncapped. The cross-session fit reads `T^1.41` only because at the observed lengths (T=29–89) the linear base `a·T` (base prefix `a`≈87–170K) is still comparable to `½·b·T²`; the local exponent rises toward 2 as T grows. A fixed `T^1.4` power would mis-extrapolate longer sessions. See the Surprises entry below.
  - **Alternatives rejected:** fixed `T^1.4` power (a regime-specific local slope, not the structural form; under-predicts long sessions); flat per-turn read constant (contradicts the within-session prefix slope of 1900–8500 tok/turn and growth_share 57–74%); a compaction-cap term (zero compaction in the data — the relief valve never opens because the user stops first).

- 2026-06-10T06:10Z [ctx=safe] Map each lever to the cost coefficient it moves, and rank by addressable $/session × growth-share (compounding-with-T), not by a single global number.
  - **Why:** the per-bucket base/growth decomposition (`wf-lever-map.py`, validated to reproduce every per-phase actual to the cent) shows the levers target structurally different terms: bound-thinking and sub-agent routing are pure growth `b` (ROI rises with T), floor-trim is pure base `a` (ROI linear in T), doc-views is mixed, cold-rewrite is write-side. A flat ranking would mis-order them for a given session length.
  - **Alternatives rejected:** rank by raw bucket $ alone (ignores that growth-bucket savings compound in long sessions while base-bucket savings do not); one global ranking across all phases (migrate's order differs — floor-trim is #1 there, bound-thinking #1 in B+C).

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

- 2026-06-10T05:26Z [ctx=safe] No compaction fires in the studied sessions — the prefix grows monotonically to the last turn — so the read integral is a genuine two-term `READ·(a·T + ½·b·T²)` with the quadratic LIVE and uncapped. `read$ ∝ T^1.41` is a local slope, not a structural ceiling; total cost is `T^1.07`.
  - **Source:** new analyzer `tools/cost-analysis/wf-prefix-growth.py` over the 12 sessions. Compaction check: every one of the 12 has `final/peak real_prefix = 1.00` and zero `real_prefix` sustained drops >15% — the prefix peaks at the last turn; the user stops before compaction. The 1–7 per-session events the first cut mislabeled as "compaction" are 5m-TTL cache re-warms (verified: each is a `cache_read` dip with a coincident `cache_write` spike), i.e. the cold-rewrite cost (YTDB-1097), which re-caches content without reducing it. Within-session: linear fit of per-turn `cache_read` against turn index has positive slope everywhere (1900–8500 tok/turn, R² 0.56–0.86); the fitted-integral growth term carries 57–74% of read in exec-tracks/create-plan, 28–34% in migrate; last-decile prefix is 3.8–7.7× the first-decile. Cross-session: `read ∝ T^1.41`, `mean_prefix ∝ T^0.41` (identity `read = T·mean_prefix·READ` ⇒ exponents sum to 1.41), `total cost ∝ T^1.07`. Validation: reproduces orchestrator $151.89 total, $29.17 for d6fb4ed8, output $31.83 — all exact.
  - **Implication:** resolves open Q#1. The model carries the explicit `READ·(a·T + ½·b·T²)`; the `T^1.41` exponent is regime-specific (linear base still comparable to the quadratic at T<90) and would climb toward 2 in longer sessions, so the structural two-term form must not be replaced by a fixed power. There is no compaction relief valve to model — the cost curve bends upward without bound until the 1M wall or a manual stop. The earlier framing that the quadratic read term is *why B+C is half the spend* is only partly right: B+C dominates mostly because it has the most sessions (4), the longest runs (66 turns avg), and the largest mean prefix (239K) for content reasons; the super-linear amplifier is real but secondary, since read is 42% of the bill and write (36%) + output (21%) scale roughly linearly with T. The marginal cost of turn k is `(a + b·k)·READ + output`, so the LAST turns of a session are the most expensive — trimming turns off the tail saves the most.

- 2026-06-10T05:26Z [ctx=safe] The orchestrator bill splits 42% cache-read / 36% cache-write / 21% output / 0.5% uncached input; read+write (the resident prefix, re-cached and re-read) is 79%.
  - **Source:** `wf-prefix-growth.py` aggregate over 12 sessions ($151.89): read $64.14, write $55.12, output $31.83, input $0.81.
  - **Implication:** this is the lever-target map the ranking sits on. Floor-trim (YTDB-1094) and doc-views attack the read+write 79% as a fixed-size cut applied every turn (savings linear in T). Bound-thinking attacks the per-turn prefix growth slope `b` AND the 21% output — the only lever hitting both the super-linear read tail and a linear term. Cold-rewrite (YTDB-1097) attacks the write side after TTL resets, concentrated in write-bound impl and long-wait B+C. Coarser/fewer turns cut `T` itself and so hit read (`T^1.4`), write, and output at once.

- 2026-06-10T06:10Z [ctx=safe] Per-bucket base/growth decomposition resolves open Q#6: the lever ranking by addressable $/session (B+C, the dominant phase) is bound-thinking $7.70 > floor-trim $5.27 > cold-rewrite $5.12 > doc-views $2.71 > sub-agent routing $1.70. Each maps to a distinct coefficient.
  - **Source:** new analyzer `tools/cost-analysis/wf-lever-map.py`, which fits each content bucket's resident-vs-turn intercept `a` (base, re-read ~T times) and slope `b` (growth, the quadratic source) and splits its read bill accordingly. Validated: bucket sums + uncached input reproduce all five per-phase actuals to the cent (74.79→75.22, 18.07→18.24, 20.65→20.72, 26.22→26.31, 11.36→11.40). Lever→coefficient: floor-trim(1094)→`a` (FLOOR, 0% growth, $5.27/sess B+C, flat across phases since the floor is constant-size and re-read every turn); bound-thinking→`b`+output (model_gen, 100% growth on read, the largest single bucket at $30.79/phase incl. $13.33 output); doc-views→`a`+`b` (wf_proc/wf_art, 54–100% growth); sub-agent routing(883)→`b` (subagent/task appear mid-session ⇒ ~100% growth); cold-rewrite(1097)→write-side TTL-rewarm overhead.
  - **Implication:** (1) bound-thinking is #1 in every phase and is the only lever on the compounding `b·T²` term *and* on output — its ROI rises with session length, so it dominates long B+C runs. It still has no issue filed; this is the strongest case to file one. (2) Cold-rewrite (1097) is a surprise #3 at $5.12/sess B+C: 68% of B+C cache-write is TTL-rewarm overhead (sub-agent waits >5min expire the cache, re-caching the full large prefix at 12.5× the read rate); near-zero in migrate (no long waits). (3) Floor-trim is pure base (0% growth) — the most-re-read content per token, and the *top* lever for migrate ($3.03), which has little thinking or doc content to trim. So the ranking is phase-dependent: growth levers win in long sessions, floor-trim wins in short/lean ones.

## Open Questions
<!-- Items flagged during research but not yet resolved. Carry into
Phase 1 as Decision Records to write or as Architecture Notes to fill.
Format:
- <ISO timestamp> [ctx=<level>] <one-line question>
  - **Blocking:** <what plan element this blocks>
-->

- 2026-06-09T12:10Z [ctx=safe] ~~What is the functional form, and does it have a superlinear (≈quadratic-in-turns) read term?~~ **RESOLVED 2026-06-10** (see Surprises & Discoveries, 2026-06-10T05:26Z, and the matching Decision Log entry).
  - **Answer:** yes, a live uncapped quadratic — read integral is `READ·(a·T + ½·b·T²)` because the prefix grows monotonically (zero compaction; it peaks at the last turn). The `T^1.41` cross-session fit is a local slope at T<90 where the linear base `a·T` is still comparable to `½·b·T²`; it rises toward 2 in longer sessions. Total cost `∝ T^1.07` (read is 42% of the bill). Model the explicit two-term form, not a fixed power. B+C dominance is mostly turn count + content-driven mean prefix; the quadratic amplifier is secondary at current lengths.

- 2026-06-09T12:10Z [ctx=safe] What is the modeling unit and driving variable — per-phase with turn count (or session length) as x?
  - **Blocking:** how the cost terms are parameterized. Data is per-session/per-phase; per-track is too granular for the current dataset.

- 2026-06-09T12:10Z [ctx=safe] Which model inputs are knowable at plan time, for the predictive layer?
  - **Blocking:** any forecasting use. Can turn count, fan-out width, and phase mix be predicted from a change's complexity tier (the plan-slimization `full`/`lite`/`minimal` tiers)? If not, the model stays descriptive.

- 2026-06-09T12:10Z [ctx=safe] What does the model output, and who consumes it?
  - **Blocking:** the deliverable shape. Candidates: a per-phase $ forecast for a planned change; a lever-ROI ranking (which of floor-trim / bound-thinking / cold-rewrite-fix / doc-views pays most for a given run); a budget-setting input. Pick one primary consumer.

- 2026-06-09T12:10Z [ctx=safe] What is the acceptance criterion — within what tolerance must the model reproduce the 12-session per-phase actuals?
  - **Blocking:** the validation gate for Phase 1 → implementation. The residual bucket is ~6% (tool-output undercount), so sub-6% per-phase error is likely the floor of achievable accuracy.

- 2026-06-09T12:10Z [ctx=safe] ~~How are the named cost levers connected to the model as parameters, so the model can score them?~~ **RESOLVED 2026-06-10** (see Surprises & Discoveries, 2026-06-10T06:10Z, and the matching Decision Log entry).
  - **Answer:** each lever maps to a measured coefficient via `wf-lever-map.py`. floor-trim(1094)→`a` (base, 0% growth); bound-thinking→`b`+output (100% growth); doc-views→`a`+`b` (wf_proc/wf_art, mixed); sub-agent routing(883)→`b` (mid-session ⇒ ~100% growth); cold-rewrite(1097)→write-side TTL-rewarm overhead. Ranked by $/session in B+C: bound-thinking 7.70 > floor-trim 5.27 > cold-rewrite 5.12 > doc-views 2.71 > sub-agent 1.70, with the order shifting by phase (floor-trim #1 in migrate). Growth-share decides compounding: growth levers win long sessions, base levers (floor-trim) win short/lean ones.
