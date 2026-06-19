<!-- workflow-sha: c99af024a00cbe1e4741d4d88e600b6f007c9199 -->
<!-- MANIFEST
dimension: workflow-prompt-design
track: 1
iteration: 1
verdict: PASS
findings_total: 1
blockers: 0
evidence_base: { certs: 0 }
cert_index: []
flags: []
index:
  - id: WP1
    sev: Minor
    anchor: "### WP1 [Minor] review-workflow-writing-style.md:38 stale Grep sweep instruction"
    loc: ".claude/agents/review-workflow-writing-style.md:38"
    cert: n/a
    basis: "read of the Tooling line against the dropped banned-vocabulary lens and the surviving Process step 2 phrase-pattern sweep at :117"
-->

## Findings

### WP1 [Minor] review-workflow-writing-style.md:38 stale Grep sweep instruction

- File: `.claude/agents/review-workflow-writing-style.md` (line 38), Axis: deterministic decision rules, Cost: a fresh sub-agent is pointed at a sweep mode (single banned-word grep) whose target list no longer exists, so the instruction reads against a removed lens, Issue: the Tooling line still says use `Grep` for "is this banned word in the file" sweeps. The banned-vocabulary lens this phrasing described was dropped from this agent in Step 2 (the `### Banned vocabulary sweep` criterion became `### Banned sentence patterns sweep`, and Process step 2 was rewritten to "Grep the diff for the banned sentence and analysis patterns" at line 117). There is no longer a closed per-word ban list to grep for; the surviving sweep matches phrase patterns ("It's not X — it's Y", "In conclusion"), not individual words. The line-38 "banned word" wording is the last vestige of the removed lens. It does not break invocation — the agent's frontmatter, criteria, and render template are all internally consistent on the four kept lenses — so it is Minor. Suggestion: rewrite to match the kept sweep, e.g. Use `Grep` for "is this banned sentence/analysis pattern in the file" sweeps. — aligning the Tooling note with Process step 2 and the `### Banned sentence patterns sweep` criterion.

## Evidence base

<!-- Evidence-trail-exempt dimension: (a) no refutation or certificate phase to persist. -->
