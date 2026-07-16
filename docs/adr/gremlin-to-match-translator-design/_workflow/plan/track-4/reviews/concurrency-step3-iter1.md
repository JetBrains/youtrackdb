<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
evidence_base: {section: "## Evidence base", certs: 4, matches: 0}
cert_index:
  - {id: C1, verdict: REFUTED, anchor: "#### C1 "}
  - {id: C2, verdict: REFUTED, anchor: "#### C2 "}
  - {id: C3, verdict: REFUTED, anchor: "#### C3 "}
  - {id: C4, verdict: REFUTED, anchor: "#### C4 "}
flags: [CONTRACT_OK]
-->

## Findings

No concurrency defects found. Every candidate defect refuted under the refutation
check; see `## Evidence base` for the four hypotheses and why each is safe.

## Evidence base

Step 3 is clean on all four concurrency axes the spawn called out: the two new
recognisers are stateless, the per-walk schema snapshot is confined to the
`WalkerContext`, the static registry is an immutable safely-published map, and no
shared mutable collection is written during a walk. The design carries this
intent explicitly ("stateless ... a shared instance is fine" on each shared
static), and the code matches. All four hypotheses below are candidate defects
that refuted, rendered in full.

#### C1 — Shared `MatchWhereBuilder` static instances under concurrency — REFUTED

Hypothesis: Step 3 adds a shared `static final MatchWhereBuilder WHERE` to
`GremlinStepWalker`, `HasStepRecogniser`, `TraversalFilterStepRecogniser`, and
`WalkerContext` (alongside the pre-existing one in `GremlinPredicateAdapter`).
These singletons are invoked by every query-translation thread. If
`MatchWhereBuilder` held mutable per-call state, concurrent `and` / `wrap` /
`classEquals` / `isDefined` / `containsText` calls from different walks would
race on that state.

Refutation: read `MatchWhereBuilder.java` (core/.../match/builder/) in full. The
class declares zero instance fields. Every public method constructs and returns a
fresh AST node (`new SQLBinaryCondition(-1)`, `new SQLAndBlock(-1)`, `new
SQLWhereClause(-1)`, and so on); the internal helpers (`combine`,
`fieldExpression`, `stringExpression`, `incrementLastCodePoint`,
`literalCollectionExpression`) are `static` or operate only on locals and
parameters. The class Javadoc states the builder is stateless and never mutates
`this`. The only cross-thread state read is immutable operator singletons
(`SQLEqualsOperator.INSTANCE`, `SQLLtOperator.INSTANCE`), which are referenced,
never mutated. A shared instance is therefore genuinely safe for concurrent use.
Verdict: no shared mutable state — refuted.

#### C2 — New recogniser instance state — REFUTED

Hypothesis: `HasStepRecogniser.INSTANCE` and
`TraversalFilterStepRecogniser.INSTANCE` are registered in the shared
`PRODUCTION_RECOGNISERS` map and dispatched by concurrent walks. A mutable
instance field on either would race across walks.

Refutation: both classes declare only `static final` members — `INSTANCE`, the
stateless `WHERE` builder, and (in `HasStepRecogniser`) the immutable String
constants `LABEL_KEY` / `ID_KEY`. Neither has an instance field. `recognize(...)`
reads only method locals, the passed-in per-walk `RecognitionContext`, and the
shared stateless builder. The `typeGate` lambda in `HasStepRecogniser` captures a
method-local `typeClass` and the per-walk `ctx`, both thread-confined to the
calling walk. All per-walk state (accumulated `whereExprs`, the resolved
`labelClass`, the boundary alias) lives in method locals or is written through the
per-call context, never onto the recogniser. Verdict: recognisers are stateless —
refuted.

#### C3 — Static registry publication and mutation — REFUTED

Hypothesis: `PRODUCTION_RECOGNISERS` is read concurrently by every walk dispatch.
If it were a mutable map mutated after initialisation, a concurrent reader could
observe a torn or partially-populated map (unsafe publication / post-init
mutation).

Refutation: the map is built with `Map.of(GraphStep.class, ..., VertexStep.class,
..., HasStep.class, ..., TraversalFilterStep.class, ...)` — an immutable map — and
held in a `static final` field, so it is safely published by JVM class-init
semantics and never mutable to begin with. The two new Step 3 entries are added
inside the `Map.of(...)` literal, not through a post-init `put`. `PRODUCTION_INSTANCE`
is itself `static final` and stores the same immutable map in a `final` instance
field (`recognisers`), and the walker holds no mutable state beyond it. Verdict:
immutable, safely published, no post-init mutation — refuted.

#### C4 — Per-walk schema snapshot confinement and compound writes — REFUTED

Hypothesis (a): the schema snapshot resolved once in `GremlinStepWalker`
(`session.getSchema()`) could leak into shared or static state, so concurrent
walks would observe each other's schema. Hypothesis (b): the read-modify-write
added to `WalkerContext.putAliasFilter` (`aliasFilters.get(alias)` then
`aliasFilters.put(...)`) and the AND-merge loop in `buildResult` are non-atomic
compound operations that could race.

Refutation (a): the schema is stored in `@Nullable private final Schema schema` —
a per-walk instance field of `WalkerContext`, assigned once in the constructor and
never written to any static field. The only static field Step 3 adds to
`WalkerContext` is the stateless `WHERE` builder (see C1). Each `translate()` call
constructs a fresh `WalkerContext` with its own schema reference resolved from its
own session, so no walk sees another's snapshot.

Refutation (b): `WalkerContext` is constructed fresh per `translate()` call and is
thread-confined for the entire walk — nothing in this step spawns a thread, forks
a parallel stream, or shares a context instance across threads (the sub-walker of
Track 5 is not present here, and would run on the same thread regardless). The
get-then-put on the instance-level `aliasFilters` `LinkedHashMap`, and the
`buildResult` merge into a freshly-allocated local `LinkedHashMap`, both execute on
a single thread with no interleaving possible. The instance `aliasFilters` map and
the per-walk `patternBuilder` are never exposed to another thread.

Reads of the shared `Schema` metadata object (`getClass`, `getProperty`,
`isVertexType`) are an existing engine property — schema is read concurrently
across sessions throughout the storage and query layers, and the owning
`DatabaseSession` is thread-confined. Step 3 introduces no new mutation of schema
and no new sharing pattern for it, so it raises no new concurrency concern here.

Verdict: schema confined to per-walk state; compound writes are thread-confined —
refuted.
