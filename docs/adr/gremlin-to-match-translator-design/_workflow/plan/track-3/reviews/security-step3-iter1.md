<!-- MANIFEST
findings: 1   severity: {blocker: 0, should-fix: 0, suggestion: 1}
index:
  - {id: SE1, sev: suggestion, loc: GremlinPredicateAdapter.java:72, anchor: "### SE1 ", cert: C6, basis: "unvalidated $-prefixed has() key resolves as a query context variable, not a property — identifier-position escape; low security impact, correctness divergence owned by bugs dimension"}
evidence_base: {section: "## Evidence base", certs: 6, matches: 1}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
  - {id: C5, verdict: REFUTED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
flags: [CONTRACT_OK]
-->

## Findings

The step is close to security-clean. Its core protection is architectural: the translator builds the MATCH AST in memory and hands it straight to `MatchExecutionPlanner` with no SQL-text serialization and re-parse (D2), so user-controlled traversal content lands in AST leaf nodes as opaque data and cannot escape into query syntax. Edge labels become escaped string literals (`SQLBaseExpression(String)`), `has()` literal values become typed literal nodes (`MatchLiteralBuilder.toLiteral`), and property keys become identifier values. No injection, deserialization, credential, crypto, or file-path surface is touched. One suggestion-level input-validation gap survives on the identifier path.

### SE1 [suggestion] A `$`-prefixed `has()` property key reaches a WHERE identifier that resolves to a query context variable, not a record property

**File:** `core/src/main/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/strategy/GremlinPredicateAdapter.java` (line 72)

**Issue.** `GremlinPredicateAdapter.toFilter` declines a `has()` key that is null, blank, or starts with `~` (the TinkerPop hidden-key prefix), but it lets a key starting with `$` through. That key becomes a bare WHERE identifier via `MatchWhereBuilder.op` → `fieldExpression(key)` → `new SQLExpression(new SQLIdentifier("$..."))`, which the assembler parks on the edge path item's `SQLMatchFilter`. At execution the identifier no longer names a record property: `SQLSuffixIdentifier.execute` special-cases the `$` prefix — `$parent` returns `ctx.getParent()` unconditionally, and any other `$name` returns `ctx.getVariable("$name")` when a context variable by that name exists. So a user-supplied identifier intended as a property reference escapes into a query-context-variable reference.

**Evidence.**
- TAINT TRACE T1: SOURCE = `has("$parent", 5)` edge predicate on `g.V().outE(L).has("$parent", 5).inV()`. `HasContainer.getKey()` returns `"$parent"` → enters `toFilter` (GremlinPredicateAdapter.java:67). The key guard at line 72 passes (`"$parent"` is non-null, non-blank, does not start with `~`); the predicate is `Compare.eq`; the value `5` renders. → `WHERE.op("$parent", =, literal)` (line 413) → `MatchWhereBuilder.op` → `fieldExpression("$parent")` = `new SQLExpression(new SQLIdentifier("$parent"))` (MatchWhereBuilder.java:54-61, 336-338). The clause is wrapped and handed to `appendEdgeAsNode` on the edge path item (EdgeStepRecogniser.java:164-175). VERDICT: UNSANITIZED for the `$` prefix.
- EXPLOIT: attacker input `has("$parent", <literal>)` (or `has("$anyScriptVar", <literal>)`) inside a translatable edge chain. TRACE WITH MALICIOUS INPUT: `$parent` reaches `SQLSuffixIdentifier.execute` (SQLSuffixIdentifier.java:82-86 for the `Identifiable` overload, 146-147 for the `Result` overload) and resolves to the parent command context / a context variable rather than a property named `$parent`. IMPACT: the translated filter diverges from native — native Gremlin `has("$parent", v)` looks for a property literally named `$parent` (absent → no match), while the translated MATCH compares internal query-execution state to the literal. The compared value is used only as a boolean filter and is never projected into the result, so no context content is returned to the client. PREREQUISITES: authenticated query access; a traversal shape this step already translates.
- REACHABILITY CHECK: REACHABLE (authenticated). The predicate adapter is reached from `EdgeStepRecogniser` (via `VertexStepRecogniser` delegation on the `returnsEdge()` branch), which the walker dispatches for any user-supplied `outE(L).has(...).inV()` traversal once `GremlinToMatchStrategy` is registered (Track 2, done). The reserved-`$` pre-flight scan added in Step 1 covers `as(...)` step *labels* only, not `has()` *keys*, so it does not catch this. Reference-accuracy caveat: mcp-steroid is unreachable this session, so the strategy-registration → walker → recogniser → adapter chain was traced from the diff and the Track-2/3 episode records rather than a PSI caller search; the adapter-internal guard and the `SQLSuffixIdentifier` execution facts are read directly from source.

**Risk Level:** Low (mapped to `suggestion`). The actor is an authenticated query author affecting only their own query's filter outcome. No cross-user data exposure (record-level security is enforced independently of the filter expression), no authorization bypass, no RCE (the AST executes directly, never re-parsed). The result *divergence* itself is a correctness concern owned by the bugs/correctness dimension; this finding reports only the security-hardening angle, so that the two dimensions do not double-count it.

**Suggestion.** Decline `$`-prefixed keys in `toFilter`, mirroring the walker's reserved-`$` label discipline and the existing `~` guard. Extend line 72 to `key.startsWith(HIDDEN_KEY_PREFIX) || key.startsWith("$")` (or a shared reserved-prefix check). That keeps user identifiers out of the context-variable namespace and, as a bonus, makes the translated filter match native for these keys (native never resolves a `$`-property to a context variable).

## Evidence base

#### C1 SQL / Gremlin injection through the edge label — REFUTED
Hypothesis: the edge label `edgeStep.getEdgeLabels()[0]` flows to `MatchEdgePathItems.edgeMethodItem` → `new SQLBaseExpression(edgeLabel)`; if that string were later serialized and re-parsed as SQL/MATCH text, a label like `knows') OR true--` could inject query syntax. Checked `SQLBaseExpression(String)` (SQLBaseExpression.java:53-56): the constructor stores `"\"" + StringSerializerHelper.encode(string) + "\""`, a quoted, escape-encoded string literal, and `execute` recovers the original via `StringSerializerHelper.decode` (line 152). The AST is fed to `MatchExecutionPlanner` in memory (D2 — no text round-trip), so the label is used as a literal method-call parameter (the edge class/label filter), never re-parsed. The peer bugs review reached the same conclusion (their C1). Not a security issue.

#### C2 Injection through the property key becoming query syntax — REFUTED
Hypothesis: the `has()` key flows to `new SQLIdentifier(key)`; a key such as `weight = 1 OR 1=1` could break out of the identifier and add clauses. Checked `SQLIdentifier(String)` → `setStringValue` (SQLIdentifier.java:37-40, 99-107): the raw string is stored verbatim as the identifier `value` (only back-ticks are internally escaped). At execution the value is a single property-name lookup against the record (`SQLSuffixIdentifier.execute`, e.g. `rec.getProperty(varName)` at line 107), not a parse of embedded operators. Because the AST is never serialized back to SQL text (D2), the whole key is one opaque field name; a key containing operators or parentheses simply names a property that does not exist and matches nothing. No clause injection. (The one non-opaque behavior — the `$` prefix — is C6/SE1, an escape within the identifier space, not a break-out into arbitrary syntax.)

#### C3 Injection or type-confusion through the `has()` literal value — REFUTED
Hypothesis: the compared value `predicate.getValue()` could carry a crafted object that, when rendered, injects syntax or a dangerous type. Checked `MatchLiteralBuilder.toLiteral` (MatchLiteralBuilder.java:48-82): a `String` becomes an escape-encoded `SQLBaseExpression` literal, a `Number` becomes an `SQLInteger`, a RID becomes a legacy `SQLRid`, `Boolean`/`Date`/collections/`byte[]` map to dedicated typed slots, and any other type throws `IllegalArgumentException`, which `toFilter` catches to decline (GremlinPredicateAdapter.java:408-412). Values are held as typed literal data and compared by value at execution — no re-parse, no arbitrary-type instantiation from attacker input. Not a security issue.

#### C4 Denial of service via unbounded allocation from an oversized label / key / value — REFUTED
Hypothesis: a very long edge label, property key, or literal string could amplify into a large allocation. Traced every sink: the label, key, and string value are each wrapped once into a single AST leaf node (`SQLBaseExpression`, `SQLIdentifier`) with no length-proportional expansion, buffering, or reflection. The one recursive helper on this path, `MatchWhereBuilder.incrementLastCodePoint`, is not reached — this step's adapter emits only scalar comparisons, not `startsWith`. Input size maps linearly to one retained string, the same as the native pipeline holds. No amplification, no DoS. Not a security issue.

#### C5 Sensitive-data or internal-detail exposure via adapter / assembler errors — REFUTED
Hypothesis: an untranslatable predicate or an unrenderable value could surface a stack trace or internal schema detail to the remote client. Checked the decline discipline: `GremlinPredicateAdapter.toFilter` catches `RuntimeException` from the literal builder and returns `null` (GremlinPredicateAdapter.java:408-412), and every recogniser turns a `null`/unhandled shape into a whole-traversal decline to the native pipeline (EdgeStepRecogniser.java:130-132, 147-152) rather than throwing. No credentials, tokens, or keys are handled anywhere on this path, and no user-facing error string is built from the input. Nothing sensitive is logged or returned. Not a security issue.

#### C6 A `$`-prefixed `has()` key reinterpreted as a query context variable — CONFIRMED
Confirmed as SE1: `toFilter`'s key guard (GremlinPredicateAdapter.java:72) blocks `~` but not `$`, so a `$`-prefixed property key reaches a WHERE identifier that `SQLSuffixIdentifier.execute` (lines 82-86, 146-147) resolves as a context variable rather than a record property — an identifier-position escape with low security impact; full trace, impact, and reachability in the SE1 body.
