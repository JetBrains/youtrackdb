<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: TS1, sev: should-fix, loc: EdgeTraversalEquivalenceTest.java:94, anchor: "### TS1 ", cert: n/a, basis: "block comment says multi-hop out().out() is not exercised end-to-end and only unit-tested, but multiHopChain_recognizedViaBarrierRecogniser (line 146) exercises it as RECOGNIZED — contradicts a test 50 lines below and misstates coverage"}
  - {id: TS2, sev: suggestion, loc: EdgeStepRecogniserTest.java:305, anchor: "### TS2 ", cert: n/a, basis: "contextWithStartBoundary/assertContextUnmutated/stepAt duplicated across three recogniser test classes; a seeding-convention change must be mirrored 3x and a drift makes the classes test different baselines"}
  - {id: TS3, sev: suggestion, loc: EdgeTraversalEquivalenceTest.java:383, anchor: "### TS3 ", cert: n/a, basis: "comment says the final label check runs natively, but the translator defaults to true so after restore it runs translated; the test still passes, only the comment is wrong"}
evidence_base: {section: "## Evidence base", certs: 0, matches: 0}
cert_index: []
flags: [CONTRACT_OK]
-->

## Findings

### TS1 [should-fix] Stale comment claims multi-hop chains are not exercised, but a test exercises them

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeTraversalEquivalenceTest.java:94`
- **Issue**: The block comment at lines 94-99 says the multi-hop chain `g.V().out(L).out(L)` "is intentionally not exercised here" because no recogniser claims the injected `NoOpBarrierStep`, and points the reader to the `twoSequentialHops_chainOffPreviousTarget` unit test "without the barrier" as the coverage. Step 3 added `multiHopChain_recognizedViaBarrierRecogniser` (line 146), which runs exactly `g.V().out("knows").out("knows")` end-to-end and asserts it engages the boundary step (RECOGNIZED). The comment now contradicts a test 50 lines below it and misstates the coverage. CLAUDE.md's comment rule is explicit: stale or contradictory comments are worse than no comments.
- **Failure scenario**: A maintainer reads line 94, concludes multi-hop chains have no end-to-end coverage, and either adds a redundant equivalence case or — believing `multiHopChain_recognizedViaBarrierRecogniser` violates the documented decline behaviour — "corrects" it to expect DECLINED, reintroducing a coverage gap or a false expectation the barrier recogniser is supposed to remove.
- **Suggestion**: Delete the lines-94-99 comment or rewrite it to point at `multiHopChain_recognizedViaBarrierRecogniser` and note the barrier recogniser now makes the two-hop chain RECOGNIZED.

### TS2 [suggestion] Recogniser-test fixture helpers duplicated across three classes

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeStepRecogniserTest.java:305`
- **Issue**: `contextWithStartBoundary` is copied verbatim into three test classes — `EdgeStepRecogniserTest` (line 305), `VertexStepRecogniserTest` (line 279), and `NoOpBarrierRecogniserTest` (line 73). `assertContextUnmutated` (`EdgeStepRecogniserTest:323`, `VertexStepRecogniserTest:298`) and `stepAt` (`EdgeStepRecogniserTest:342`, `VertexStepRecogniserTest:312`) are duplicated across two of them. All encode one convention: seed a `$g2m_v0` boundary plus one RETURN column keyed on it plus a cursor at index 1, and verify a decline leaves that baseline untouched.
- **Failure scenario**: A change to how a recogniser expects the start boundary to be seeded (say, the RETURN-list shape) must be mirrored in three files. If one copy drifts, the classes silently test against different baselines, and an `assertContextUnmutated` in one class no longer means what it means in another — a no-mutation-on-decline regression can pass in one file while the intended baseline changed in the copy the author edited.
- **Suggestion**: Extract the shared fixture (`contextWithStartBoundary`, `assertContextUnmutated`, `stepAt`) into one package-private helper in the strategy test package and have the three recogniser tests call it.

### TS3 [suggestion] "Run natively" comment is wrong — the traversal runs translated

- **File**: `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/EdgeTraversalEquivalenceTest.java:383`
- **Issue**: In `nonPolymorphicBareHop_doesNotUndercountSubclassTargets`, the comment at lines 382-384 says the final label assertion runs the traversal "natively (translator state is restored by the helper)". `assertEquivalent`'s finally restores the flag to the value read on entry, which is the default, and `QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED` defaults to `true` (`GlobalConfiguration.java:1009`). So the final `graph.traversal().V().out("knows").toList()` runs with the translator enabled, not natively. The assertion holds either way — `out("knows")` returns the Person subclass whether translated or not — so the test is correct; only the comment is inaccurate.
- **Failure scenario**: A reader trusts the comment and treats the subclass-return assertion as a native-pipeline guarantee. When they later adjust the harness or the config default, they misjudge which execution mode the assertion actually protects.
- **Suggestion**: Reword the comment to say the traversal runs under the restored default (translator enabled), or set the translator off explicitly before the label check if a native run is what the pin intends.

## Evidence base
