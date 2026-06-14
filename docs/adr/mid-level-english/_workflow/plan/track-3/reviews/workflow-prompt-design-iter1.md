<!--MANIFEST
dimension: workflow-prompt-design
iteration: 1
target: track-3
range: 156e5aaec54037ca0c317b998da84b09dd65e575..HEAD
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - { id: WP1, sev: Minor, anchor: "WP1", loc: ".claude/agents/review-workflow-writing-style.md:205", cert: n/a, basis: judgment }
-->

## Findings

### WP1 [Minor] Output-format `Axis:` enum has no token for the new Plain-language lens

- File: `.claude/agents/review-workflow-writing-style.md` (line 205, with the producing lens at line 75-78)
- Axis: output contract
- Cost: a Plain-language finding has no enumerated `Axis:` value, so the reviewer LLM improvises a token and two runs diverge on the field label.
- Issue: Step 2 added a `### Plain language` review-criteria subsection (line 75-78) that produces findings, and a matching "Key rules to enforce" bullet (line 31). But the output-format finding bullet at line 205 still pins `Axis:` to a closed enum — `banned vocabulary | em-dash overuse | BLUF lead | section length | heading style | repo-anchored voice | knowledge-cutoff disclaimer | bullet-vs-prose | conciseness | adjective triads` — with no `plain language` member. The agent now has a lens whose output has no slot in the mandated finding shape, so a plain-language finding gets tagged with whatever Axis the LLM picks (most likely `banned vocabulary`, which the lens's own scope guard at line 78 explicitly says it is not). The closed-enum output contract and the new open lens are out of sync. (Note: this is the prompt-design facet — the new lens emits findings whose mandated output field has no value to carry them. The broader cross-file enum-drift framing is `review-workflow-consistency`'s territory; flagged here only because it degrades the reproducibility of the finding shape this very file's new lens produces.)
- Suggestion: add `plain language` to the `Axis:` enum at line 205, between `banned vocabulary` and `em-dash overuse` to mirror the `### Plain language` subsection's placement right after `### Banned vocabulary sweep`. Concretely: `Axis: <banned vocabulary | plain language | em-dash overuse | BLUF lead | …>`.

## Evidence base
