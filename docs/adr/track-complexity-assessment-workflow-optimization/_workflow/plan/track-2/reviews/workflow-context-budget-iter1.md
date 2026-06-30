<!--MANIFEST
dimension: workflow-context-budget
iter: 1
findings: 0
evidence_base: {certs: 0}
cert_index: []
flags: []
index: []
-->

## Findings

None. No always-loaded surface, load-on-demand discipline, or instant per-operation consumption impact survives review on this track diff.

- **Always-loaded (agent descriptions).** Agent count is net-zero (three removed, three added). `review-bugs-concurrency` (187 chars) split into `review-bugs` (222) + `review-concurrency` (255); `review-test-behavior` (204) + `review-test-completeness` (197) merged into `review-test-quality` (268). Removed 588 chars, added 745 — net +157 chars on the per-turn agent-description surface. Every new description is under the 350-char Recommended flag and far under the 500-char Critical flag; the 268-char `review-test-quality` sits marginally over the ~250 target but carries real discriminative load (it must signal that one reviewer now covers both the behavior and completeness sub-protocols, so the orchestrator does not expect two agents). No CLAUDE.md change, no SessionStart hook change, no duplication of CLAUDE.md content.
- **Load-on-demand discipline.** The new prose — `risk-tagging.md` §Track-level complexity tag (~56 lines), `review-agent-selection.md` §"Complexity sets the Phase-C rigor dial, never the set" (~50 lines), and the `track-review.md` reconciliation block — all lands in TOC-gated load-on-demand workflow files, each with a matching TOC row and per-section annotation comment. Nothing bloats an always-read file.
- **Instant per-operation consumption.** The two new code-reviewer bodies (`review-bugs` 290 lines, `review-concurrency` 283 lines) each sit near the 288-line `review-bugs-concurrency` predecessor they replace, well under the ~500-line per-spawn Recommended floor; both carry the §2.5 manifest-only return contract, so the return surface is bounded. When the `concurrency` category is present both now spawn where one did, but each runs in its own clean sub-agent context, so the orchestrator peak is unchanged. The merged `review-test-quality` body is 446 lines versus the predecessors' 559 combined — the merge cut ~113 lines of test-review body and replaced two spawns with one, a net instant-axis win.

## Evidence base
