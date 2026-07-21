<!-- MANIFEST
findings: 3   severity: {blocker: 0, should-fix: 1, suggestion: 2}
index:
  - {id: BG1, sev: should-fix, loc: "MatchWhereBuilder.java:417-425; GremlinPredicateAdapter.java:434,455", anchor: "### BG1 ", cert: "C-dotted-label", basis: "dotted Gremlin labels produce ambiguous $matched paths via naive string concat — silent wrong predicate, not decline"}
  - {id: BG2, sev: suggestion, loc: "GremlinPredicateAdapter.java:437-458", anchor: "### BG2 ", basis: "PropertyTypeGate threaded but unused in matched-label translation"}
  - {id: BG3, sev: suggestion, loc: "MatchWhereBuilder.java:432-449", anchor: "### BG3 ", basis: "parse failures throw IllegalArgumentException instead of adapter null-decline"}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 4, matches: 4}
cert_index:
  - {id: C-dotted-label, verdict: CONFIRMED, anchor: "#### C-dotted-label "}
  - {id: C-where-traversal, verdict: MATCHES, anchor: "#### C-where-traversal "}
  - {id: C-matched-parse, verdict: MATCHES, anchor: "#### C-matched-parse "}
  - {id: C-subwalk, verdict: MATCHES, anchor: "#### C-subwalk "}
flags: [CONTRACT_OK, MCP_STEROID_UNAVAILABLE]
-->

GATE VERDICT: PASS

Step-4 bugs review (`ff6b7c8309..62182d15eb`). mcp-steroid unreachable; symbol audits used diff read + direct source inspection `(grep-only)`.

## Findings

### BG1 [should-fix]
**Certificate**: C-dotted-label
**Location**: `MatchWhereBuilder.matchedAccess` (MatchWhereBuilder.java:417-425); `GremlinPredicateAdapter.leftMatchedOperand` / `translateMatchedLabelPredicate` (GremlinPredicateAdapter.java:434,455).
**Issue**: Path construction uses naive `"$matched." + alias + ".@rid"` concatenation. A Gremlin label `"a.b"` parses as alias `a` + segment `b`, not alias `"a.b"`. Translation ACCEPTED with wrong predicate — silent multiset skew.
**Refutation considered**: Blank/`$`-prefixed labels decline; parse failures fall through GremlinToMatchStrategy to native. Dotted labels are valid Gremlin strings and are neither validated nor declined.
**Suggestion**: Decline labels containing `.` (safest for Phase 1) or build `$matched` AST without string concatenation. Deferred to Track 6 alias-propagation work unless a regression appears sooner.

### BG2 [suggestion]
**Location**: `GremlinPredicateAdapter.translateMatchedLabelPredicate` — `typeGate` unused while Phase 1 only emits `@rid` compares; `.by(...)` already declined at WherePredicateStepRecogniser.java:36-38.

### BG3 [suggestion]
**Location**: `MatchWhereBuilder.parseMatchedRhsExpression` throws on parse failure; GremlinToMatchStrategy catches → native decline. Prefer returning `null` from `toMatchedLabelFilter` for recogniser-level decline parity.

## Evidence base

#### C-dotted-label: dotted Gremlin labels → wrong $matched path (BG1)
- CONFIRMED-as-latent: see BG1 body. Unit tests cover single-segment aliases only.

#### C-where-traversal: where(traversal) via TraversalFilterStep + WhereTraversalStep
- **Hypothesis**: YTDB fork desugars `where(traversal)` to `TraversalFilterStep`, not `WhereTraversalStep`; edge-bearing where might never translate.
- **Refutation**: `TraversalFilterStepRecogniser.recognizeWhereTraversal` sub-walks via `commitPositiveFilterChild`; `WhereTraversalStepRecogniser` kept for upstream shapes. Equivalence `whereOutKnows_matchesNative` and unit tests green `(test run)`. **Verdict: MATCHES.**

#### C-matched-parse: unwrapBinaryCondition peels SQLOrBlock → SQLAndBlock → SQLNotBlock → SQLBinaryCondition
- **Hypothesis**: Parser wrapper chain leaves `parseMatchedRhsExpression` returning null for single-letter aliases.
- **Refutation**: Probe showed SQLOrBlock → SQLAndBlock → SQLNotBlock(negate=false) → SQLBinaryCondition; unwrap handles chain. `MatchWhereBuilderMatchedAccessTest` green. **Verdict: MATCHES.**

#### C-subwalk: GraphStep strip + positive-filter commit symmetric with AND/NOT
- **Hypothesis**: Sub-walk leaves leading GraphStep or mis-routes pure-filter vs edge-bearing commits.
- **Refutation**: `GremlinStepWalker.subWalk` strips leading `GraphStep`; `ConnectiveStepSupport.commitPositiveFilterChild` mirrors AND commit split. **Verdict: MATCHES.**
