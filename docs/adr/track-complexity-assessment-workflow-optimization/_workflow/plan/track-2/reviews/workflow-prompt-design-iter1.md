<!-- MANIFEST
dimension: workflow-prompt-design
level: medium
findings: 2
evidence_base: {certs: 0}
index:
  - {id: WP1, sev: Minor, anchor: "### WP1 ", loc: ".claude/agents/review-test-quality.md:3", cert: n/a, basis: "description names the role split but not its launch condition; mild discriminability gap vs review-test-structure / review-test-concurrency"}
  - {id: WP2, sev: Minor, anchor: "### WP2 ", loc: ".claude/skills/code-review/SKILL.md:392", cert: n/a, basis: "synthesis severity-map preamble still says 'three of the older code-review agents use legacy scales' after the split made it four scale-bearing agents"}
cert_index: []
flags: []
-->

## Findings

### WP1 [Minor] review-test-quality description states the role merge but not the launch condition
- **File**: `.claude/agents/review-test-quality.md` (line 3)
- **Axis**: description discriminability
- **Cost**: a mild discriminability gap — the always-loaded picker reads "behavior + completeness" but no launch trigger, unlike the precise `review-bugs` ("Always-on") and `review-concurrency` ("Fires on the concurrency category") siblings.
- **Issue**: The merged agent's `description:` correctly tells the picker *what* it covers ("behavior-driven quality and completeness … assertion depth, exception testing … corner cases, boundary conditions, and test data quality"), which discriminates it well from `review-test-structure` (isolation/setup-teardown) and `review-test-concurrency` (multi-thread test verification). But it omits the *when*. Its two sibling new specs both encode the launch condition in the description itself — `review-bugs` says "Always-on." and `review-concurrency` says "Fires on the concurrency category." The asymmetry is harmless because the SKILL.md selection table (`Always launched unless docs-only/build-config are the ONLY categories`) and the agent-selection prose carry the authoritative trigger, so no mis-route results. It is purely a consistency-of-form polish: the merged agent reads as less self-describing than the two it sits beside.
- **Suggestion**: Append the launch condition to match the sibling form, e.g. end the description with "Always launched on test changes. Dispatched by /code-review." so all three new specs encode their trigger uniformly. Not load-bearing — the SKILL table is the source of truth — so leave-as-is is acceptable.

### WP2 [Minor] Synthesis severity-map preamble undercounts the legacy-scale agents after the split
- **File**: `.claude/skills/code-review/SKILL.md` (line 392)
- **Axis**: output contract for sub-agents
- **Cost**: a stale count in load-bearing synthesis guidance — the orchestrator reads "three of the older code-review agents use legacy scales" while the very next lines now enumerate four scale-bearing agents (`review-bugs`, `review-concurrency`, `review-crash-safety`, `review-security`), a minor internal inconsistency that could momentarily mislead the orchestrator about how many non-standard scales it must translate.
- **Issue**: Step 7's severity-mapping block opens with "Most sub-agents emit findings under `Critical / Recommended / Minor`, but **three** of the older code-review agents use legacy scales." The split turned the single `review-bugs-concurrency` (`Likely Issues` / `Potential Concerns`) into two agents that both keep that legacy scale, so the `Likely Issues` / `Potential Concerns` rows now name `review-bugs` AND `review-concurrency` (correctly updated in the delta at lines 396-397). The mapping rows themselves are complete and correct — `Critical → blocker (all agents)` covers the new agents' top severity, and both new names appear on the `Likely Issues`/`Potential Concerns` rows — so synthesis still works. Only the introductory count ("three") is now wrong: the legacy-scale agents are `review-bugs` + `review-concurrency` (sharing one scale) + `review-crash-safety` + `review-security`. The delta did not touch this preamble line.
- **Suggestion**: Update the count to match the rows below it — e.g. "but several older code-review agents use legacy scales" (count-free, robust to future roster churn) or "but four older code-review agents use legacy scales." Cosmetic; the verbatim mapping rows are authoritative and correct.

## Evidence base
