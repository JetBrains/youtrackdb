<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: SE1, sev: suggestion, loc: GremlinPredicateAdapter.java:88, anchor: "### SE1 ", cert: C1, basis: "has()-key reserved-namespace guard declines $ and ~ but not @; a @-prefixed edge-filter key resolves as a record attribute/metadata, diverging from native — correctness, not privilege escalation"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### SE1 [suggestion] has()-key reserved-namespace guard declines `$` and `~` but not `@`

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (line 85-90)

The edge-filter key guard declines a null, blank, `~`-prefixed, or `$`-prefixed
`has(...)` key. It admits a `@`-prefixed key. `outE("knows").has("@class", P.eq("Knows")).inV()`
therefore reaches `MatchWhereBuilder.op` → `fieldExpression` →
`new SQLIdentifier("@class")` on the edge `WHERE`. At runtime the shared
identifier resolver treats the record-attribute namespace specially: a bare
identifier whose value is `@class` / `@rid` / `@version` is resolved against the
record's own metadata, and the `Identifiable` overload of
`SQLSuffixIdentifier.execute` additionally falls back to `ctx.getVariable(name)`
when a plain property is absent (`SQLSuffixIdentifier.java:107-110`). Native
Gremlin treats `@class` as an ordinary property name the edge does not carry, so
the native filter matches nothing; the translated filter matches by class
attribute. The two pipelines return different multisets for the same traversal.

**Security impact — none directly; this is a correctness divergence.** The key
selects metadata of edges the caller can already traverse, so nothing crosses a
trust boundary: no privilege escalation, no read of another principal's data, no
injection (the AST is built node-by-node, never re-parsed from text — see C3).
The finding is filed as a hardening suggestion, not a vulnerability, because the
review scope asks for any escape analogous to the fixed `$` case on other input
surfaces and this is the one residual namespace the guard misses.

**Why raise it now.** This adapter is the `has()`→WHERE chokepoint Track 4
widens from the edge-filter minimum to the full node-side `P` / `Text` / `TextP`
algebra. Every predicate key Track 4 adds inherits this guard. Closing the `@`
gap here — one more `startsWith` clause mirroring the walker's label scan — keeps
the reserved-namespace guard symmetric across the two identifier surfaces
(`as(...)` labels in the walker, `has(...)` keys here) before the surface grows.

**Suggestion.** Add `|| key.startsWith("@")` to the decline block at line 87-89
(the record-attribute namespace), so a `@`-prefixed key declines the whole
traversal to native exactly as `$` and `~` do. Separately, record the non-`$`
`ctx.getVariable` fallback in the class Javadoc so Track 4 stays conservative
about which keys it lets translate rather than decline.

**Reference-accuracy caveat (grep-only).** mcp-steroid was not reachable, so the
runtime resolution path was traced by reading the `SQLSuffixIdentifier.execute`
overloads directly, not via a PSI call hierarchy. Which `execute` overload runs
for an edge-as-node `WHERE` at execution is inferred from the evaluator shapes,
not confirmed by find-usages; the `@class` resolution and the non-`$` variable
fallback are both present in the resolver source regardless of which overload the
edge filter dispatches through.

## Evidence base

#### C1 [CONFIRMED] `@`-prefixed has()-key reaches an identifier the resolver treats as metadata
Reachable by any caller that can submit a Gremlin traversal:
`outE(L).has("@class", P.eq(...)).inV()` passes the adapter guard (not null /
blank / `~` / `$`) and translates, landing `@class` as a bare `SQLIdentifier` on
the edge `WHERE`; `SQLSuffixIdentifier` resolves `@`-attributes and falls back to
`ctx.getVariable` on absent properties, diverging from native. Survives as a
suggestion-level correctness/hardening finding (SE1).

#### C2 [CONFIRMED] Step-level `$`-guard fix (commit 6b5740cb6a) holds
`GremlinPredicateAdapter` declines a `$`-prefixed key (`RESERVED_ALIAS_PREFIX`,
line 68 + 89) and the walker declines any `$`-prefixed `as(...)` label
(`hasReservedPrefixLabel`, `GremlinStepWalker.java:148,230-238`, null-guarded).
`SQLSuffixIdentifier.execute` confirms the escape the fix prevents — a
`$`-prefixed identifier resolves as a context variable (`$parent` at line 82/143,
any `$name` at line 85/146), not a record property. Both reserved-`$` surfaces
are guarded; the fix is intact.

#### C3 [REFUTED] Literal-position injection through edge labels, has() values, and `@class` names
Considered whether a user string escapes a literal position into executable
query structure. It cannot. Every value sink builds an AST node directly and the
IR is consumed by the planner without a SQL-text round-trip, so there is no
string to break out of. Edge labels reach `MatchEdgePathItems.edgeMethodItem` as
a method parameter built with `new SQLBaseExpression(String)`, which stores
`"\"" + StringSerializerHelper.encode(value) + "\""` — an escaped, quoted
literal (`SQLBaseExpression.java:53-56`). `has()` values reach
`MatchLiteralBuilder.toLiteral`, which routes String → the same encoded
`SQLBaseExpression`, Number → `SQLInteger`, Boolean/RID/Date/collection → typed
literal fields, and throws `IllegalArgumentException` for any other type — caught
by the adapter's `catch (RuntimeException)` and declined
(`GremlinPredicateAdapter.java:427-431`). `MatchClassFilters.classEquals`
(no production caller in this track) puts the class name on the right of
`@class = 'name'` as the same encoded literal. No user string reaches a class,
alias, or method-name token: all aliases are minted (`$g2m_*`), the root class is
the hardcoded constant `"V"`, and edge/vertex method names are a fixed switch.
Not an issue.

#### C4 [REFUTED] Alias injection through `as(...)` labels
Considered whether a user `as(...)` label escapes into a minted-alias position.
It cannot in this track. As-label propagation is Track 5; Track 3 never routes a
user label into an alias, and the walker's pre-flight scan declines the whole
traversal when any step label starts with `$` (the minted `$g2m_` namespace),
running before any recogniser dispatch and before session-dependent resolution
(`GremlinStepWalker.java:148`). The scan null-guards the label set, so
`as((String) null)` declines rather than throwing. Not an issue.

#### C5 [REFUTED] Denial of service via unbounded step count
Considered whether removing `MAX_RECOGNISED_STEPS` opens an allocation/CPU DoS.
It does not add a surface. Translation is O(N) over the step list with no
exponential blow-up, N is bounded by the query the caller already submitted, and
a long chain is at least as expensive on the native pipeline the traversal would
otherwise run. The walker's cursor-advance assertion plus the defensive decline
bound a mis-counting recogniser to a native fallback rather than an infinite
loop (`GremlinStepWalker.java:610-622`). Not an issue.
