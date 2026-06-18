<!-- MANIFEST
findings: 3   severity: {Critical: 0, Recommended: 1, Minor: 2}
index:
  - {id: WP1, sev: Recommended, loc: "create-plan/SKILL.md:559-564", anchor: "### WP1 ", cert: n/a, basis: "runtime by-reference-fails fallback names no testable trigger in the SKILL; collapsed-path executor cannot reproduce the retain-boundary decision"}
  - {id: WP2, sev: Minor, loc: "create-plan/SKILL.md:566-583", anchor: "### WP2 ", cert: n/a, basis: "Design->plan flow block restates the Step 1c routing nearly verbatim; two parallel statements of the same rule can drift"}
  - {id: WP3, sev: Minor, loc: "create-plan/SKILL.md:1339-1341", anchor: "### WP3 ", cert: n/a, basis: "two-commits bullet header omits the lite/minimal contrast that the sibling bullet carries, mild scan-time ambiguity"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### WP1 [Recommended] Runtime "by-reference cannot hold" fallback names no testable trigger inside the SKILL
- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 559-564, and the parallel sentence at 562-564)
- **Axis:** deterministic decision rules
- **Cost:** an LLM executing the collapsed happy path has a conditional ("If by-reference cannot hold, the boundary is retained instead") it cannot evaluate at the point it reads, so behavior on the borderline case is non-reproducible.
- **Issue:** The Design→plan flow block tells the executor the collapse "depends on by-reference orchestration" and that "If by-reference cannot hold, the boundary is retained instead (see the gate-A6 clause where the collapse is applied)." For a reader running a live `full`-tier `/create-plan`, this is a decision rule with no testable predicate: nothing in the SKILL says how the executor determines, at Step 4a→4b time, that by-reference "cannot hold." The pointer ("see the gate-A6 clause where the collapse is applied") refers to the plan/track-file gate that was checked once at *authoring* time, not to anything the running skill can re-test. The track file (track-2.md, Validation and Acceptance) is explicit that the live check is a *static, authoring-time* read and the live-harness confirmation is a deferred Phase-4 item — which is the correct decision — but the SKILL prose reads as if the executor still owns a runtime branch. The result is a dangling conditional: the happy path is unconditionally the collapsed flow (Step 4a flows into 4b), yet the prose hedges with a retain-the-boundary alternative the executor has no rule to trigger.
- **Suggestion:** Make the conditional's tense match where the decision actually lives. Either (a) state that by-reference is a *design-time invariant already confirmed for this staged change* and that the running skill always takes the collapsed path (move the "if it cannot hold, retain the boundary" sentence into a design-rationale aside clearly marked as a Track-1/Phase-4 gate, not a runtime branch the executor evaluates), or (b) if a runtime guard is genuinely intended, give the one testable signal the executor checks (e.g., "if a `design-author` spawn returns the drafted document rather than a thin summary, stop and treat the boundary as retained"). As written the reader cannot tell which, so the safest fix is (a): demote the runtime-sounding conditional to a stated invariant.

### WP2 [Minor] Step 4 "Design→plan flow" block restates the Step 1c routing nearly verbatim, inviting drift
- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 566-583)
- **Axis:** deterministic decision rules
- **Cost:** two near-identical statements of the same crash-recovery-only routing rule can diverge under a future edit, so a later reader may follow a stale copy.
- **Issue:** The "The startup protocol's auto-resume into Step 4b is now crash-recovery-only..." paragraph (566-583) restates the same condition and disposition Step 1c already specifies authoritatively (lines 174-218, 279-293): committed-and-clean test, after drift/handoff, before the aim prompt, never-a-dead-end. The duplication is benign today (the two copies agree), but it is a second source of truth for the same routing decision. A prompt is more reproducible when a load-bearing rule has one home and the other site links to it.
- **Suggestion:** Trim the Step 4 restatement to a one-line pointer ("Step 1c spells out the exact `git log`/`git status` check, branch order, and the resume-Step-4a fallback") and let Step 1c remain the single decision-rule home, rather than re-deriving the routing here. The first half of the paragraph (566-572, the crash-recovery framing) is useful orientation and can stay; the re-enumeration of the neither/both cases (577-583) is the redundant part.

### WP3 [Minor] Step 5 "two commits in one session" bullet header drops the tier contrast the sibling bullet carries
- **File:** `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 1339-1341)
- **Axis:** deterministic decision rules
- **Cost:** at scan time the reader must read into the body to confirm this bullet is the `full`-tier arm, a small discriminability cost when the sibling bullet leads with its tier.
- **Issue:** The bullet header reads "**`full`, two commits in one session (Step 4a then Step 4b)**" while the sibling reads "**`lite` / `minimal`, single Phase-1 session**." The `full` header leads with the tier correctly, so the discriminator is present — but the post-collapse phrasing "two commits in one session" no longer contrasts cleanly with "single Phase-1 session" now that both arms run in one session; the distinguishing axis is now commit *count* (two vs one), not session count. A reader skimming the two headers could momentarily read "single session" as the discriminator and mis-route.
- **Suggestion:** Make the contrast the commit count, e.g. "**`full`, two session-end commits (Step 4a then Step 4b), one session**" vs "**`lite` / `minimal`, one session-end commit, one session**" so the two headers differ on the axis that actually routes (number of commits), not on a session-count distinction the collapse erased.

## Evidence base
