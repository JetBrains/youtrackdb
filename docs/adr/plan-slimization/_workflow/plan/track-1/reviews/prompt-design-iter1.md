<!-- MANIFEST
dimension: prompt-design
target: track-1 (Phase 0/1 authoring pipeline)
range: 2775833bc33bab3d8acc1f3dd34a219e8ebe5ea7..HEAD
findings: 1
evidence_base: { certs: 0 }
cert_index: []
flags: [evidence-trail-exempt]
index:
  - id: WP1
    sev: should-fix
    anchor: "### WP1 [should-fix] design-review.md phase1-creation block contradicts the D6 relocation"
    loc: "docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/prompts/design-review.md:105-119"
    cert: n/a
    basis: "Stale mutation-kind block, byte-identical to develop (0 hits in delta), now contradicts the same prompt's header (L53-58) and the edit-design phase1-creation spawn (SKILL L414-431, L476-495)."
-->

## Findings

### WP1 [should-fix] design-review.md phase1-creation block contradicts the D6 relocation

- **File:** `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/prompts/design-review.md` (lines 105-119)
- **Axis:** clean-context invocation / deterministic decision rules
- **Cost:** the cold-read sub-agent spawned for `phase1-creation` reads a false mental model of its own pipeline and is given no instruction to run the absorption cross-check the spawn provisioned it for
- **Issue:** This is the cumulative cross-step defect the track-level pass is meant to surface. The branch's D6 decision relocates the design's decision/assumption challenge off `edit-design` (Step 3.5 removed) and onto the research log at the Phase 0 → 1 gate; the `phase1-creation` cold-read is now **cold-read only**, gated behind the log-adversarial gate, and additionally runs the absorption-completeness cross-check (log → `design.md` seed D-records). The branch updated this correctly in three places a reviewer reaches: the prompt header (L53-58), `## Inputs`'s `research_log_path` bullet (L65-68), and the edit-design spawn (`edit-design/SKILL.md` L414-431 S3-gate, L476-495 `research_log_path` injection). But the `### Mutation-kind specific instructions` → `phase1-creation` block at L105-119 — the section a reviewer matches by its own `mutation_kind` — was left byte-identical to develop (0 hits in the delta). It still asserts:

  > **You run after the adversarial pass.** The `phase1-creation` loop runs adversarial review first (`prompts/adversarial-review.md` § Design-scoped review (Phase 1)), then this cold-read — the design you are reading has already survived a decision/assumption challenge…

  Under D6 there is no local `edit-design` adversarial pass; the challenge ran on the research log, not on the design. The block's instruction list (verify (a) internal coherence, (b) `Mechanics:` link resolution) also omits the absorption-completeness cross-check that L53-58 and the edit-design spawn both say this exact invocation must run. A `phase1-creation` reviewer reading its matched block is told a local pass ran that did not, and is not pointed at §Track-scoped cold-read (L222-227) where the design-direction absorption check lives. Step-level review did not catch it because the edit-design Step 3.5 removal (Step 4, commit `1dd16f09ef`) and the design-review.md write-time-target edit (Step 5, commit `6d9dbafc7f`) landed in separate steps, and this block sits between the two edits untouched by either.
- **Suggestion:** Rewrite the `phase1-creation` mutation-kind block (L105-119) to match D6: replace "You run after the adversarial pass / the loop runs adversarial review first" with the relocated framing — the decision/assumption challenge ran once on the research log at the Phase 0 → 1 gate, the cold-read is gated behind that gate clearing (S3), so the design's decisions have already survived challenge *on the log*. Add the absorption-completeness cross-check to the block's verify-list (or an explicit "Plus the absorption-completeness cross-check — see §Track-scoped cold-read" signpost) so the matched block names every check this invocation owns. The header at L53-58 already states the intent; the block just needs to stop contradicting it.

## Evidence base

(Evidence-trail-exempt: this dimension runs no refutation or certificate phase.)
