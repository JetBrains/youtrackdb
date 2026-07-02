<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 7, matches: 7}
cert_index:
  - {id: C1, verdict: MATCHES, anchor: "#### C1 "}
  - {id: C2, verdict: MATCHES, anchor: "#### C2 "}
  - {id: C3, verdict: MATCHES, anchor: "#### C3 "}
  - {id: C4, verdict: MATCHES, anchor: "#### C4 "}
  - {id: C5, verdict: MATCHES, anchor: "#### C5 "}
  - {id: C6, verdict: MATCHES, anchor: "#### C6 "}
  - {id: C7, verdict: MATCHES, anchor: "#### C7 "}
flags: [CONTRACT_OK]
-->

## Findings

No procedural-completeness gaps. The change closes a decision-branch hole
(the `if [ -f "$live" ]` guard now has its `else`) and rewrites the
consuming context block so the loop's two markers plus the empty-file case
are covered mutually-exclusively, with no residual scope-to-nothing gate.
Both phase docs land identical routing modulo temp-path and indentation.

## Evidence base

#### C1 — Conditional complement (loop else branch) — MATCHES
`if [ -f "$live" ]; then … else … fi` — the else now emits `=== NEW staged file (no live counterpart): %s ===`; both branches write a marker, so no add falls through undocumented (track-code-review.md:286-295, step-implementation.md:502-511).

#### C2 — Producer/consumer marker handshake — MATCHES
Loop emits exactly two markers (`=== delta: %s vs %s ===`, `=== NEW staged file (no live counterpart): %s ===`); the context block consumes exactly those two by byte-identical text (track-code-review.md:471/476, step-implementation.md:625/630). Marker enumeration in the consumer is complete relative to the producer — no third marker exists.

#### C3 — No residual scope-to-nothing gate — MATCHES
The old file-level "when that file is non-empty, scope your findings to the delta" gate is gone; grep for `non-empty|out of scope|scope to` across both files returns only the per-`=== delta ===`-marker justification and unrelated Phase-C synthesis prose. A NEW-only (non-empty, zero-delta) file now routes via the NEW marker to "Review it in full", not into scoping-to-nothing.

#### C4 — Three-way routing completeness and mutual exclusivity — MATCHES
Context block covers delta-marker ⇒ scope to delta, NEW-marker ⇒ review in full, empty file ⇒ review as usual (track-code-review.md:469-481, step-implementation.md:623-635). The three cases partition every reachable delta-file state, including the mixed delta+NEW file that the removed file-level gate could not route per-entry.

#### C5 — Preamble and post-loop narration describe both outcomes — MATCHES
Preamble names both the `diff <live> <staged>` and NEW-marker outcomes (track-code-review.md:271-275, step-implementation.md:486-491); post-loop narration drops the "has a live counterpart" restriction and describes both (track-code-review.md:299-303, step-implementation.md:515-519). No branch of the loop is left undocumented.

#### C6 — Cross-file identity of the decision logic — MATCHES
The added else branch and the rewritten context block are identical across the two files except for the temp-file path (`track-{N}-delta` vs `step-{N}-{M}-delta`) and indentation, so a reviewer launched in either Phase B or Phase C receives a complete, equivalent instruction.

#### C7 — Inv 5 coverage and idempotency — MATCHES
Loop marker, block heading, and NEW marker live in exactly the two named files (no third stale copy). The loop truncates its temp file with `: >` on each run, so re-running the setup is a no-op rebuild — no gate, loop, or resume path is introduced that would need a recovery rule.
