<!--
MANIFEST
dimension: workflow-context-budget
target: track-2 (87f40db9afc95a8bec478d05eabf20d317f03526..HEAD)
iteration: 1
evidence_base: certs: 0
cert_index: []
flags: evidence-trail-exempt (reason: (a) no refutation or certificate phase to persist)
index:
  - id: WB1
    sev: Recommended
    anchor: "### WB1 [Recommended] fidelity-check description over the 350-char budget"
    loc: ".claude/agents/fidelity-check.md:3 (post-promotion live path)"
    cert: n/a
    basis: "description char count 406 vs 350 Recommended threshold; sibling cluster 303-346"
-->

## Findings

### WB1 [Recommended] fidelity-check description over the 350-char budget

- File: `.claude/agents/fidelity-check.md` (line 3) — staged at `docs/adr/understandable-design/_workflow/staged-workflow/.claude/agents/fidelity-check.md`; assessed at its post-promotion live path.
- Axis: always-loaded.
- Cost: 406 characters in the `description:` field, loaded into every session's agent-description block on every turn once promoted. The four sibling roles Track 1 staged cluster at 303-346 chars (`design-author` 303, `absorption-check` 334, `readability-auditor` 337, `comprehension-review` 346); `fidelity-check` is the only one of the five over the 350-char Recommended threshold, 60 chars above the next-highest sibling.
- Issue: the description packs body-resident detail beyond the discriminative core. The when-to-invoke / when-to-skip signal is "Phase 4 per-round fidelity check for `design-final.md`, spawned by `edit-design` on `phase4-creation`." The PSI-residual enumeration ("the diagram, signature, and no-episode-trace residual"), the "Reads no research log" S2 invariant, and "Runs every round of the phase4-creation dual-clean inner loop in place of the absorption check" all restate content already carried in the agent body (the S2 invariant at lines 27-29, the residual breakdown at lines 31-42). Description tokens cost on every turn forever; the body costs only on dispatch.
- Suggestion: trim to the discriminative core and let the body carry the residual list and the S2 invariant. A form near the sibling cluster, for example: `"Phase 4 per-round fidelity check for design-final.md: matches the final design against the step and track episodes, with a PSI residual for diagram/signature claims and no-episode-trace claims. Runs in the phase4-creation dual-clean loop, spawned by edit-design."` keeps the invocation discriminability while dropping ~70 chars to land inside the sibling band.

## Evidence base
