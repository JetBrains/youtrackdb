<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: PF1, sev: suggestion, loc: ShapeClassifier.java:286, anchor: "### PF1 ", cert: C1, basis: "Cross-alias WHERE check runs an O(n^2) re-walk (per-node getMatchPatternInvolvedAliases over an outer pre-order); negligible at realistic WHERE size, cleanup hoists three walks to fewer and removes the quadratic term"}
evidence_base: {section: "## Evidence base", certs: 3, matches: 1}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
flags: [CONTRACT_OK]
-->

## Findings

### PF1 [suggestion] Cross-alias WHERE check is an O(n^2) re-walk; collapse to a single call

**File**: `core/src/main/java/com/jetbrains/youtrackdb/internal/core/sql/executor/cache/ShapeClassifier.java` (lines 286, 350-370)

**Issue.** `vertexNodeForcesK0None` runs three independent full pre-order traversals over the same pattern-WHERE subtree per vertex node: `subtreeHasSubquery` (281), `subtreeReferencesOtherAlias` (286 via `whereReferencesOtherAlias`), and `subtreeHasLinkPathDeref` (291). The middle one nests a quadratic walk. `subtreeReferencesOtherAlias` (355-370) visits every node pre-order, and at each `SQLBooleanExpression` node it calls `getMatchPatternInvolvedAliases()`, which itself recurses through that node's entire subtree and allocates a fresh `ArrayList` at each composite node (`SQLBinaryCondition.java:422`, `SQLAndBlock.java:247`). Each WHERE subtree is therefore re-walked once per ancestor boolean node — O(n^2) node visits plus O(n) immediately-discarded `ArrayList` allocations, where the walk only inspects `isEmpty()`.

The redundancy is avoidable: `getMatchPatternInvolvedAliases()` already recurses through the whole boolean tree on its own, so the manual outer pre-order walk in `subtreeReferencesOtherAlias` re-does the descent the method already performs. Calling `where.getMatchPatternInvolvedAliases()` once on the WHERE clause's root boolean expression returns the full alias set in a single descent — same answer, no outer walk, no quadratic term, no per-node throwaway lists.

**Evidence.** See `#### C1` (confirmed structure / cost trace) and `#### C2`, `#### C3` (the two alternative concerns I checked and refuted) in the Evidence base.

**Impact.** None measurable at production scale, and I want to be explicit about that: this is on a cache-miss-only path (see C1), the data is a pattern-WHERE boolean tree bounded by query text (single-digit predicate count in any real DNQ/Hub MATCH), and the whole `classifyMatch` call is amortized to roughly once per unique MATCH shape per transaction. At n = 1-5 predicates the O(n^2) term and the discarded allocations are sub-microsecond and lost in parse cost. The value of the change is twofold and modest: (1) it removes a latent O(n^2) that would only surface under adversarial query text (a deeply nested hand-written WHERE), and (2) it collapses the per-node-WHERE analysis from three tree walks to two and deletes ~18 lines (`subtreeReferencesOtherAlias` plus its `whereReferencesOtherAlias` wrapper). Treat it as a cleanliness improvement with a perf rationale, not a latency fix — do not prioritize it over correctness work in later steps.

**Suggestion.** Replace `whereReferencesOtherAlias(where)` / `subtreeReferencesOtherAlias` (350-370) with a direct `where.getMatchPatternInvolvedAliases()` non-empty check on the WHERE root. Confirm `SQLWhereClause.getMatchPatternInvolvedAliases` delegates to its root boolean expression (it is one of the implementors listed in the parser package) so the single call covers the whole boolean tree; the existing `matchCrossAliasStateWhereClassifiesAsK0None` test pins the behavior and should pass unchanged. If a direct call is not wired on `SQLWhereClause`, keep the outer walk but have it call `getMatchPatternInvolvedAliases()` only at the WHERE root rather than at every descended boolean node, which removes the quadratic re-descent.

## Evidence base

#### C1 classifyMatch is miss-path-only; the multi-walk WHERE analysis is O(n^2) but on a tiny bounded tree — CONFIRMED

Survived the refutation check; recorded as the basis for PF1 (one-line per the survived-claim rendering):
`classify`/`classifyMatch` runs only on a MATCH cache **miss** (`DatabaseSessionEmbedded.java:798`, after the `hit != null` early return at 786-792, confirmed by reading the call site); cache hits never re-classify (the entry carries its shape), so the cost is per-unique-MATCH-shape-per-tx, not per-query — and the WHERE boolean tree it walks three times (with one quadratic re-descent via `getMatchPatternInvolvedAliases`) is bounded by query text, so the O(n^2) term is negligible at realistic n and the finding is a structural-cleanup suggestion, not a latency finding.

#### C2 The per-vertex-node STATIC_LABEL regex match and label `toString()` renders are a hot-path allocation problem — REFUTED

Claim under test: the new code compiles `STATIC_LABEL` once (static final, line 165 — correct, no per-call compile) but calls `STATIC_LABEL.matcher(...)` per vertex node and per edge-label parameter, and `isStaticLabel`/`baseExpressionDerefsForeignHead` call `SQLExpression.toString()` / `SQLBaseExpression.toString()` which render the AST node to a String; on a hot path these allocations (a `Matcher` per call, a rendered `String` per label/base-expression) would add up.

Refutation: the path is miss-only and per-unique-shape (C1), and the counts are bounded by pattern size — a MATCH pattern has a handful of aliases and path items, each with at most a few labels. So the per-`classifyMatch` allocation total is a small constant number of `Matcher` objects and rendered label/expression strings, paid once per unique MATCH shape per transaction, against a backdrop where the parser already rendered and allocated the entire AST for this same statement. There is no per-record, per-edge, or per-result-row multiplier anywhere in `classifyMatch`; nothing scales with database size or result cardinality. Scale check: AT 1M+ records the cost is identical to AT 100 records because the work is a function of query text only. Verdict NEGLIGIBLE — not a finding. (The `toString()`-based head parsing in `baseExpressionDerefsForeignHead` is a correctness/robustness surface rather than a performance one, and correctness is out of scope for this dimension.)

#### C3 The pre-order subtree walks (subtreeHasSubquery / subtreeHasLinkPathDeref) duplicate work that could be fused into one traversal for a meaningful speedup — REFUTED

Claim under test: `vertexNodeForcesK0None` runs `subtreeHasSubquery`, the cross-alias check, and `subtreeHasLinkPathDeref` as three separate full traversals of the same WHERE subtree; fusing them into a single visitor that tests all three predicates per node would cut traversal cost ~3x.

Refutation: the ~3x is real in walk count but the absolute cost is negligible (C1 — bounded tiny tree, miss-only). A fused single-pass visitor would add real complexity (one walk testing three unrelated predicate families with early-out semantics that differ per predicate) for no measurable gain, which trades readability against a sub-microsecond saving — exactly the micro-optimization the review guidelines say to decline. The one part of this cluster worth changing is the genuinely quadratic re-descent inside the cross-alias check, which is captured separately as PF1 and is fixable by deleting a walk rather than fusing walks. Verdict NEGLIGIBLE as a fusion proposal; the quadratic sub-claim is promoted to PF1.
