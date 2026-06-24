# Handoff: Phase 1 — re-run readability-auditor SLICED on design.md before freeze

**Paused:** 2026-06-24
**Phase:** 1 (design authoring, Step 4a — just before the design freeze)
**Context level at pause:** warning
**Branch:** ytdb-915-analyzed-expression
**HEAD:** <set by the pause commit this handoff lands in>
**Unpushed:** <no upstream — see workflow.md §What to do before ending a session> (pause commit sets the upstream via `git push -u`)

## What I was doing

`/create-plan` for the S0 analyzed-expression substrate (YTDB-915). Tier = **full**, adversarial lenses = Architecture / cross-component coordination + Performance hot path (user-confirmed). Phase 0→1 adversarial gate PASSED (iter 2). Step 4a authored `design.md` (822 lines) through the `phase1-creation` dual-clean loop; it is gate-clean (mechanical PASS, absorption PASS, comprehension gate PASS).

A `/readability-feedback` run then found that the in-loop `readability-auditor` was run **whole-doc** (one spawn, `range: 1-816`) instead of the contract-prescribed **range-sliced fan-out**, which under-caught a minority of genuine prose nits. Root cause filed as **YTDB-1158** (Bug, dev-workflow). The user asked to re-run the auditor **properly sliced** and fix the genuine subset **before freezing the design**.

## Durable artifacts on disk (all committed in the pause commit)

- `_workflow/research-log.md` — frozen-ready Phase-0/1 seed. Decisions D1–D14 + D5-R + invariants I1–I3. `## Adversarial gate record` latest heading = **PASS** (iter 2). Do NOT re-run the adversarial gate.
- `_workflow/design.md` — 822 lines, stamped `<!-- workflow-sha: 6b81c6b970b0c58300e4c053a5883c2482d3dd25 -->`. Gate-clean but NOT yet auditor-sliced-clean and NOT yet frozen. **Do not treat as frozen** until the sliced-auditor fix below runs.
- `_workflow/reviews/research-log-adversarial-iter1.md` / `iter2.md` — the adversarial gate's review files (iter1 NEEDS REVISION, iter2 PASS).
- `_workflow/plan/*.md` — ephemeral sub-agent params files from the design loop (`author-params-r1..r5`, `auditor-params-r1..r4`, `absorption-params-r1..r3`, `comprehension-params`). Safe to overwrite/ignore.

## Next action on resume (the user-approved plan — option 1)

1. **Re-run the in-loop `readability-auditor` SLICED** over `design.md` — five calibrated `readability-auditor` spawns (subagent_type `readability-auditor`, NOT general-purpose), one per slice, params `target=design`, `target_path=<abs design.md>`, `range` = the five windows on `##` / `# Part` boundaries: `1-218`, `219-398`, `399-557`, `558-721`, `722-822`. (Re-derive boundaries with `grep -nE '^#{1,3} '` if line numbers drifted.) These apply the calibrated reconstructibility bar with full per-slice attention.
2. **Fix the genuine subset** via one `design-author` re-spawn (round 6, target=design, flagged_passages). The subset = the sliced auditor's blocker/should-fix findings PLUS these confirmed `/readability-feedback` style nits the whole-doc pass missed (verify line numbers; they drift):
   - Subjectless/passive fragments in the Overview/Core-Concepts: `~:25` "Four pieces are built to fit the substrate", `~:48` "Replaces the abstract-class-plus-subclasses idiom…", `~:66-70` "Extracted whole from…" — give each a subject/active verb.
   - Clipped head noun: `~:538-542` "Every value semantic — …" → "All value semantics — … come from `NumericOps`."
   - Any broken-grammar nits the sliced auditor confirms (signature-in-subject, split predicate, dropped relative pronoun).
   - **Do NOT** chase the dense-but-followable mechanism traces the calibrated auditor HOLDS (those are expert-audience prose the loop accepts by design — not the subset to fix).
3. **Re-run** `python3 .claude/scripts/design-mechanical-checks.py --design-path <design.md> --target design --scope whole-doc` (expect PASS) and confirm the named subset is cleared. Stop there — do not re-loop the auditor to chase fresh nits (budget is spent; the goal is the genuine subset, not zero).
4. **Freeze + commit the design** per `create-plan` Step 5 full-tier FIRST commit: message `Add initial design` (design.md is already committed in the pause commit, so this commit carries the auditor fixes), then **push** (upstream already set by the pause commit), then **open the draft PR** (Step 5 sub-steps 4–7). Ask the user once for the issue prefix — suggest **YTDB-915** (the branch encodes it). PR `## Status` notes the `_workflow/` scaffolding is removed at Phase 4 cleanup.
5. **Flow into Step 4b** (same invocation): derive the thinned derived-mirror `implementation-plan.md` (Checklist + thin Component Map) and **four track files** `plan/track-1.md … track-4.md` per **D13** — T1 substrate+framework, T2 NumericOps whole-enum extraction, T3 lowering (owns D10 parenthesis + D12 precedence fold), T4 evaluator + round-trip (depends T1/T2/T3). Author via the Step-4b dual-clean loop (`design-author` target=tracks, per-track `readability-auditor` slices, `absorption-check`, then the S3-gated `comprehension-review`). Seed track Decision Logs from the frozen `design.md` D-records. Then Step 5 SECOND commit `Add initial implementation plan` + push (no `-u`, PR exists).
6. **Seed the phase ledger** before the Step-4b commit: `workflow-startup-precheck.sh --append-ledger --phase 0 --tier full --categories "Architecture / cross-component coordination,Performance hot path"`.

## Do NOT redo

- The Phase 0→1 adversarial gate (research-log latest gate-record = PASS).
- The tier classification (full; lenses Arch + Perf — user-confirmed).
- The design's decision content / structure (D1–D14, D5-R; comprehension gate PASSed). Only fix the prose subset in step 2.
- The held-by-design dense mechanism traces (the calibrated auditor accepts them for the SQL-layer expert audience).

## Why this needs a handoff

`design.md` is committed-and-clean after the pause commit, so a missed-handoff fallback to `create-plan` Step 1c would auto-resume into Step 4b (plan derivation) and SKIP the sliced-auditor fix. The handoff is the authoritative signal (Step 1a runs before Step 1c) — resolve it first: run the sliced auditor + fix (steps 1–3) BEFORE the freeze and Step 4b.
