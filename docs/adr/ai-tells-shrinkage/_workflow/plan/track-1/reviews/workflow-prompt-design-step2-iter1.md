<!-- workflow-sha: c99af024a00cbe1e4741d4d88e600b6f007c9199 -->
<!-- MANIFEST
dimension: workflow-prompt-design
step: 2
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
    anchor: "### WP1 [Minor] ai-tells/SKILL.md:3 description discriminability"
    loc: ".claude/skills/ai-tells/SKILL.md:3"
    cert: n/a
    basis: "read of SKILL.md:3 description against DR9 keep-set and the body catalogue lines 23-28"
-->

## Findings

### WP1 [Minor] ai-tells/SKILL.md:3 description discriminability

- File: `.claude/skills/ai-tells/SKILL.md` (line 3), Axis: description discriminability, Cost: a still-correct but slightly thinner always-loaded trigger surface, Issue: the rewritten `description` drops the vocabulary-fingerprint clause ("delve, tapestry, leverage, robust, multifaceted, navigate, foster") and the punctuation clause ("em dash overuse, knowledge-cutoff disclaimers") per DR9, which is the correct call — those tells left `house-style.md`. The surviving advertisement (structural / analysis / tone fingerprints) is internally consistent with the body catalogue at lines 23-28 (every named category still resolves to a kept section), and the SKIP-versus-TRIGGER surface (the "humanize this / de-AI / clean this up" phrase list) is untouched, so the orchestrator's invocation decision is unchanged. The only residual is that the description now leads with three abstract fingerprint *families* and one concrete tell ("It's not X, it's Y"); the pre-edit version carried a denser set of concrete trigger words. This does not impair discriminability against the neighbouring `review-workflow-writing-style` agent (that one is `/code-review`-dispatched, not user-invocable, and the two trigger surfaces do not overlap), so it stays Minor. Suggestion: optionally restore one concrete kept-vocabulary anchor inside the analysis-fingerprints clause — e.g., "(superficial -ing clauses, passive voice, vague attribution, hedge stacking, elegant variation)" — to keep the description matchable on a concrete pasted-tell signal, not only on the abstract family name. No change required for correctness.

## Evidence base
