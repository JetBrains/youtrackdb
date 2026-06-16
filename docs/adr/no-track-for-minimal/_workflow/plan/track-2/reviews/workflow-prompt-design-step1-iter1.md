<!-- workflow-sha: 3e9c22298dfe68d2980646704850c781f8af88d5 -->
# Workflow prompt-design review — Track 2, Step 1 (iteration 1)

- role: reviewer-dim-step
- phase: 3B
- track: "Track 2: Rewire the runtime consumers onto the ledger"
- step: 1 (§1.7 marker + tier control-state reads → ledger; D14 script-gate/promotion half)
- iteration: 1
- dimension: workflow prompt-design (prompts-to-an-LLM quality)
- verdict: PASS
- findings: 0
- blockers: 0
- evidence_base: certs 0 (evidence-trail-exempt: no refutation or certificate phase)

<!--
MANIFEST
evidence_base: certs 0
cert_index: (none)
flags: evidence-trail-exempt (reason: no refutation or certificate phase to persist)

| id | sev | anchor | loc | cert | basis |
|---|---|---|---|---|---|
| (none) | — | — | — | — | new findings: 0 |
-->

## Findings

(no findings — the step is a clean prose re-point with deterministic decision rules)

This step re-points the §1.7(b)/(c)/(l) marker read and the change-tier read off the
plan `### Constraints` / tier line onto the phase ledger's `s17` / `tier` fields,
ledger-first with a develop-era `### Constraints` stable-prefix fallback, across 11
staged workflow files, and extends the D14 script gate + Phase-4 promotion to cover
`.claude/scripts/**`. Judged purely as prompt-design (decision-rule determinism,
clean-context invocation, $ARGUMENTS handling, frontmatter discriminability, sub-agent
delegation annotations, output contracts), every changed surface holds up:

- **Deterministic decision rules.** Every re-pointed read is a two-level mechanical
  rule — "read `s17` from `_workflow/phase-ledger.md` (last value wins); when no
  `phase-ledger.md` exists, fall back to the `### Constraints` stable-prefix scan."
  The fallback predicate (`no phase-ledger.md`) and the match targets (the
  workflow-modifying token; in the fallback, the stable prefix only) are both
  testable, not "consider if" / "as appropriate" phrasings. The consistency-review
  tier-resolution path additionally spells out the tier-presence finding and the
  degenerate "tier unreadable from either source" branch as discrete, deterministic
  outcomes. The §1.7(l) double-token rule (`s17` equals the workflow-modifying token
  **or** the opt-out token) is an explicit OR over two named tokens, not a vague
  disjunction.

- **Clean-context invocation.** The new read target `_workflow/phase-ledger.md` is
  given as the same relative spelling the canonical `conventions.md` §1.1 directory
  layout establishes (sibling of `{plan_path}`, which the review prompts already pass
  as an input). A fresh sub-agent given `{plan_path}` resolves the sibling without
  session memory. No block assumes a prior turn.

- **Cross-consumer uniformity.** The "Staged-read precedence" block is byte-identical
  across the five places it appears (adversarial / risk / technical / consistency /
  dimensional-review-gate-check / review-gate-verification), and the "Workflow-machinery
  criteria" block is byte-identical across adversarial / risk / technical. Identical
  decision rules at every dispatch site means no divergent LLM behavior between
  reviewers, and (per the known prompt-cache sharing behavior) no spurious tail
  busting.

- **D14 promotion coherence.** The `create-final-design.md` Phase-4 promotion now adds
  `.claude/scripts` to both the divergence-check `git log` pathspec and the `git add`,
  and the prose claim ("so a promoted startup script reaches develop") now matches the
  command — the prior `cp -r` + narrow `git add` would have copied the staged scripts
  yet left them uncommitted. The implementer-rules §1.7(e) pre-commit gate pathspec
  matches (`.claude/scripts/` added), with the rationale stated inline.

- **No frontmatter / $ARGUMENTS / sub-agent-spawn surface touched.** No skill or agent
  `description:` changed, no `argument-hint:` handling altered, no new sub-agent spawn
  introduced or re-pointed, so the reference-accuracy PSI-delegation annotation rule
  and the `$ARGUMENTS`-in-Agent-prompt hazard do not apply to this diff.

## Evidence base

(empty — this dimension is evidence-trail-exempt: it runs no refutation or certificate
phase whose reasoning could be externalized)
