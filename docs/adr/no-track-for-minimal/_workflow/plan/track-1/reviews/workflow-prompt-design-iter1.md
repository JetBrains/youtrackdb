<!-- MANIFEST
dimension: workflow-prompt-design
target: Track 1 — the phase ledger, the new artifact model, the authoring surface
commit_range: 6c2e0b5f68b12599aacbcce8b608f5c1489a3159..HEAD
iteration: 1
high_water_mark: 0
findings: 2
evidence_base: { certs: 0 }
cert_index: []
index:
  - { id: WP1, sev: Recommended, anchor: "### WP1", loc: ".claude/skills/create-plan/SKILL.md:224-233", cert: n/a, basis: judgment }
  - { id: WP2, sev: Minor, anchor: "### WP2", loc: ".claude/skills/create-plan/SKILL.md:166-239", cert: n/a, basis: judgment }
flags: { evidence_trail_exempt: true, reason: "(a) no refutation or certificate phase to persist" }
-->

## Findings

### WP1 [Recommended] Branch-D resume qualifier is not parseable into a clean boolean

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 224-233; delta lines 1748-1757)
- **Axis:** deterministic decision rules
- **Cost:** non-reproducible resume routing on an interrupted `minimal` session that has a ledger but no track file yet — the LLM may misroute the C/D boundary.

**Issue.** The fresh-start branch is gated on:

> **Neither `implementation-plan.md` nor `design.md` exists, and the ledger is absent (or present with no `tier`/`minimal` track file yet)** — fresh start.

The parenthetical `present with no `tier`/`minimal` track file yet` is a slash-compounded phrase that does not decode into a testable condition. Reading it against the design (D10 risk note) and the script's `determine_state_from_ledger`, it is trying to express three OR-ed sub-cases:

1. ledger absent; OR
2. ledger present but carries no `tier` field; OR
3. ledger present with `tier=minimal` but `plan/track-1.md` not yet written.

An LLM reading `no `tier`/`minimal` track file yet` cannot reliably reconstruct those three arms. This matters because the entire boundary between this branch and the immediately-preceding `minimal` resume branch (which requires `tier=minimal` **and** `plan/track-1.md` present, lines 215-223) rests on this phrase: the only thing separating "resume off the ledger" from "restart fresh" in the no-plan/no-design state is whether the track file is on disk, and that distinction is buried in an ambiguous parenthetical. A misread routes a recoverable interrupted `minimal` session into a full fresh-start (re-run research + tier classification), the exact failure D10 exists to prevent.

The mitigating fact (why this is Recommended, not Critical): the ledger is seeded **after** the track files are written (lines 986-987, "After the track files ... are written ... seed the phase ledger"), so sub-case 3 (ledger present, `tier=minimal`, no track file) is nearly unreachable in a successful Phase-1 run. But the prose deliberately tries to cover it, and the qualifier governing the load-bearing C/D split should still be unambiguous.

**Suggestion.** Replace the parenthetical with an explicit enumerated condition, e.g.:

> **Neither `implementation-plan.md` nor `design.md` exists, and no `minimal` resume signal is present** — i.e. the ledger is absent, OR the ledger is present but its `tier` field is empty/unreadable, OR the ledger reads `tier=minimal` but `plan/track-1.md` has not been written yet. Fresh start.

This makes each arm a single testable check the LLM can evaluate against the same `LEDGER_TIER` value it already parsed at lines 156-164, with no compound `/` phrase.

### WP2 [Minor] Step 1c branch list omits an explicit first-match-wins ordering rule

- **File:** `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 166-239; delta lines 1696-1757)
- **Axis:** deterministic decision rules
- **Cost:** an LLM could evaluate the branches out of order and match the wrong arm on the overlapping no-plan/no-design state.

**Issue.** The routing is introduced with "Route on what exists:" (line 166) followed by five bulleted branches, but the prose never states that the branches are evaluated top-to-bottom, first-match-wins. The `design.md`-keyed branches (A: design-only; E: both files) are mutually exclusive with the no-design branches by file presence, so they are safe. The risk is the `minimal` resume branch (C, lines 215-223) and the fresh-start branch (D, lines 224-233): both describe the no-plan/no-design state and are distinguished only by `tier=minimal` + `plan/track-1.md` presence. With no stated precedence, an LLM that reaches the no-plan/no-design state has no instruction telling it to prefer the more-specific C arm before falling through to D. The wrap-up sentence (lines 241-250) asserts "a defined resume path for every artifact combination" but does not supply the evaluation-order rule that would make that true for the C/D overlap.

**Suggestion.** Add one clause after "Route on what exists:" — e.g. "Evaluate the branches in order; the first whose condition holds wins." Pairing this with the WP1 rewrite removes both the ambiguity and the ordering gap, so the C/D pair becomes deterministic: C fires only on `tier=minimal` + track file present, and D is the catch-all fresh start reached only when no earlier branch matched.

## Evidence base
