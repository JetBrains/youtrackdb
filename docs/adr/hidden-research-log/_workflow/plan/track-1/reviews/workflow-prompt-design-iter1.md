<!--MANIFEST
dimension: workflow-prompt-design
agent: review-workflow-prompt-design
target: track-1
iteration: 1
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, reason: "(a) no refutation or certificate phase to persist" }
index:
  - { id: WP1, sev: Recommended, anchor: "### WP1 [Recommended]", loc: ".claude/workflow/research.md:198-200", cert: n/a, basis: judgment }
  - { id: WP2, sev: Minor, anchor: "### WP2 [Minor]", loc: ".claude/skills/create-plan/SKILL.md:254,279", cert: n/a, basis: judgment }
-->

## Findings

### WP1 [Recommended]

- File: `.claude/workflow/research.md` (line 198-200), Axis: deterministic decision rules, Cost: the "one … recap" exclusivity phrase reads stronger than the operative banned list, risking suppression of the D3-sanctioned Step-4 gate verdict recital on a strict reading. Issue: the new §Rules opacity bullet asserts "The one sanctioned structured recap is the Phase-1 transition findings summary (§Transition to Phase 1)". D3 names two sanctioned user-facing recaps that must survive: the §Transition findings summary AND the Step-4 adversarial-gate verdict recital plus tier proposal (`create-plan/SKILL.md` Step 4). The gate recital is a PASS / NEEDS-REVISION verdict and a tier proposal surfaced to the user — arguably "structured" and a "recap" in the loose sense. An agent reading the exclusivity claim "the one … structured recap" could hesitate to recite the gate verdict, fearing it violates opacity. The contradiction is not hard: the bullet's operative banned list (write narration, section names, D-numbers, quoted `**Why:**`/`**Alternatives rejected:**` fields) is concrete, and the gate verdict surfaces none of those — a careful agent applying the banned list, not the loose phrase, would not suppress it. The exclusivity wording is also self-consistent within `research.md` alone, since the gate recital lives in `create-plan/SKILL.md` and is never called a "structured recap" there. But the bullet under-specifies against D3's two-carve-out intent: it names only one of the two sanctioned surfaces and leans on cross-document scoping that is left implicit. Suggestion: scope the exclusivity phrase to the research conversation explicitly, e.g. "The one sanctioned structured recap *of the log's findings during research* is the Phase-1 transition findings summary"; or add a half-clause acknowledging the Step-4 gate verdict recital as a separate sanctioned user-facing surface (a verdict, not a log recap). Either edit removes the residual ambiguity at zero cost to the rule's force.

### WP2 [Minor]

- File: `.claude/skills/create-plan/SKILL.md` (line 254,279), Axis: sub-agent delegation, Cost: a descriptive cross-reference that does not match the target's verbatim bullet title costs a reader one resolution hop, not a broken reference. Issue: both new SKILL.md clauses cite "(`research.md` §Rules, the keep-the-log-agent-internal rule)". The actual §Rules bullet is titled "**Keep the research log agent-internal.**" — the descriptive phrase "the keep-the-log-agent-internal rule" drops the word "research" and reshapes the title into a hyphenated handle. It resolves (a reader landing on §Rules finds the right bullet), and the `research.md` self-references use the same descriptive handle ("the keep-the-log-agent-internal rule" / "per the rule above"), so the two files are internally consistent with each other. The mismatch is only against the bullet's own verbatim heading. Suggestion: align the handle with the bullet title — "the *Keep the research log agent-internal* rule" — so the cross-reference names its target exactly; low priority since the current form is still unambiguous to a reader.

## Evidence base
