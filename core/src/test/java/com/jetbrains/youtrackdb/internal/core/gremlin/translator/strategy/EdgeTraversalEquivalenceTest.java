package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep;
import java.util.List;
import java.util.function.Supplier;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Assert;
import org.junit.Test;

/**
 * Translator-on / translator-off equivalence fixture for the folded vertex-hop shapes ({@code
 * out(L)} / {@code in(L)} / {@code both(L)} and the adjacent {@code outE(L).inV()} /
 * {@code bothE(L).otherV()} chains TinkerPop folds to the same {@code VertexStep}). This is the
 * fixture the design's headline invariant names: for every {@code RECOGNIZED} shape, the
 * translated MATCH plan and the native Gremlin pipeline must return the same elements the same
 * number of times (order is <em>not</em> pinned — MATCH's planner reorders). Tracks 4–6 extend it
 * with predicates, projections, and advanced patterns.
 *
 * <p>Each case runs the <em>same</em> traversal shape twice — once with the strategy enabled, once
 * disabled — and asserts two things (per the track's Validation and Acceptance): (a) boundary-step
 * engagement (a {@code RECOGNIZED} shape has exactly one {@link YTDBMatchPlanStep} with the
 * translator on and none off; a {@code DECLINED} shape has none either way), and (b) result-multiset
 * equality between the two runs. Multiset equality is checked on sorted RID strings, which preserves
 * multiplicity (a vertex reached twice appears twice), so parallel edges, self-loops, and {@code
 * both()} multiplicity are all covered by the same comparison.
 *
 * <p>The traversal is supplied as a {@link Supplier} because a {@link GraphTraversal} is single-use
 * — {@code applyStrategies()} locks the step list and {@code toList()} drains it — so each run
 * builds a fresh instance.
 */
public class EdgeTraversalEquivalenceTest extends GraphBaseTest {

  /** Whether the translated shape must engage the boundary step or decline to the native pipeline. */
  private enum Recognition {
    RECOGNIZED, DECLINED
  }

  // ---------------------------------------------------------------------------
  // Folded bare hops — out(L) / in(L) / both(L).
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().out("knows")} translates and returns the same vertex multiset as native: from
   * every vertex, follow each outgoing {@code knows} edge to its target. Seed is a two-edge chain
   * (Alice→Bob→Carol), so {@code out} yields {Bob, Carol}.
   */
  @Test
  public void foldedOutHop_returnsSameMultisetAsNative() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows"));
  }

  /**
   * {@code g.V().in("knows")} translates and returns the same vertex multiset as native: from every
   * vertex, follow each incoming {@code knows} edge back to its source. Over Alice→Bob→Carol,
   * {@code in} yields {Alice, Bob}.
   */
  @Test
  public void foldedInHop_returnsSameMultisetAsNative() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().in(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().in("knows"));
  }

  /**
   * {@code g.V().both("knows")} translates and returns the same vertex multiset as native: each
   * {@code knows} edge is traversed from both endpoints, so an A→B edge contributes B (from A) and
   * A (from B).
   */
  @Test
  public void foldedBothHop_returnsSameMultisetAsNative() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().both(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().both("knows"));
  }

  /**
   * {@code g.V().out()} (label-less, all edge types) translates and returns the same vertex multiset
   * as native. The label-less hop maps to the IR's null edge label, which renders as the all-edges
   * {@code out('E')} form ({@code E} is the base edge class, traversed polymorphically). Seeded with
   * both a {@code knows} and a {@code likes} edge from Alice so the hop must gather across edge
   * types: this pins that {@code out('E')} picks up every edge type, matching native {@code out()}
   * (were {@code out('E')} not equivalent to a bare all-edges traversal, the multisets would differ
   * and this test would fail).
   */
  @Test
  public void labelLessHop_returnsSameMultisetAsNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    alice.addEdge("likes", carol);
    graph.tx().commit();

    assertEquivalent(
        "g.V().out() (label-less, all edge types)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out());
  }

  // A multi-hop chain (g.V().out(L).out(L)) is exercised end-to-end below by
  // multiHopChain_recognizedViaTransparentBarrier: LazyBarrierStrategy injects a NoOpBarrierStep
  // between the hops, which the step cursor skips as a transparent step, so the whole chain is
  // RECOGNIZED. The unit test twoSequentialHops_chainOffPreviousTarget additionally pins the
  // recogniser's chaining logic directly, without the barrier.

  // ---------------------------------------------------------------------------
  // Adjacent folded edge chains — outE(L).inV() / bothE(L).otherV(). These fold
  // to the same VertexStep as the bare hop, so they translate identically.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().outE("knows").inV()} is folded by {@code IncidentToAdjacentStrategy} to the same
   * {@code VertexStep} as {@code out("knows")}, so it must translate and return the same multiset as
   * native {@code out("knows")}.
   */
  @Test
  public void adjacentOutEInV_foldsAndReturnsSameAsNative() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().outE(knows).inV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().outE("knows").inV());
  }

  /**
   * {@code g.V().bothE("knows").otherV()} folds to the same {@code VertexStep} as
   * {@code both("knows")}, so it must translate and return the same multiset as native.
   */
  @Test
  public void adjacentBothEOtherV_foldsAndReturnsSameAsNative() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().bothE(knows).otherV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().bothE("knows").otherV());
  }

  // ---------------------------------------------------------------------------
  // Multi-hop chains — RECOGNIZED because the step cursor skips the NoOpBarrierStep
  // LazyBarrierStrategy wedges between chained hops as a transparent step.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().out("knows").out("knows")} translates end-to-end: {@code LazyBarrierStrategy} wedges
   * a {@code NoOpBarrierStep} between the two hops, which the step cursor skips as a transparent step,
   * so the whole two-hop chain is recognised. Over Alice→Bob→Carol, two {@code out} hops yield
   * {Carol}.
   */
  @Test
  public void multiHopChain_recognizedViaTransparentBarrier() {
    seedKnowsChain();
    assertEquivalent(
        "g.V().out(knows).out(knows) (multi-hop, interleaved barrier)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().out("knows").out("knows"));
  }

  // ---------------------------------------------------------------------------
  // Non-adjacent edge filtering — outE(L).has(edgeProp).inV(). A has() between
  // the edge step and its close blocks IncidentToAdjacentStrategy's fold, so the
  // chain arrives unfolded and translates via the edge-as-node MATCH form (the
  // edge filter on the edge's own path item, not the target vertex).
  // ---------------------------------------------------------------------------

  /**
   * LDBC-IC2-style edge-date filter: {@code g.V().outE("knows").has("since", P.lt(2015)).inV()}
   * translates via the edge-as-node form and returns the same vertex multiset as native. Alice knows
   * Bob (since 2010) and Carol (since 2020); the edge filter {@code since < 2015} keeps only the Bob
   * edge, so both runs yield {Bob}. This is the headline correctness case: the filter must apply to
   * the edge, not the target vertex.
   */
  @Test
  public void nonAdjacentOutEdgeFilter_returnsSameMultisetAsNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010);
    alice.addEdge("knows", carol, "since", 2020);
    graph.tx().commit();

    assertEquivalent(
        "g.V().outE(knows).has(since, lt 2015).inV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().outE("knows").has("since", P.lt(2015)).inV());
  }

  /**
   * A label-less edge filter {@code g.V().outE().has("since", P.gt(2015)).inV()} (all edge types)
   * translates via the edge-as-node form and returns the same vertex multiset as native. The
   * label-less edge maps to the null-label edge-as-node builder, rendered as the all-types bare
   * {@code outE(){where: ...}} form. Alice has a {@code knows} edge (since 2010) to Bob and a {@code
   * likes} edge (since 2020) to Carol; the filter {@code since > 2015} keeps only the likes edge, so
   * both runs yield {Carol}. Seeding two edge types pins that the label-less {@code outE()} gathers
   * across every type before the filter — were it not all-types, the likes edge would be missed and
   * the multisets would differ.
   */
  @Test
  public void labelLessEdgeFilter_returnsSameMultisetAsNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010);
    alice.addEdge("likes", carol, "since", 2020);
    graph.tx().commit();

    assertEquivalent(
        "g.V().outE().has(since, gt 2015).inV() (label-less, all edge types)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().outE().has("since", P.gt(2015)).inV());
  }

  /**
   * The {@code inE(L).has(...).outV()} analogue translates and matches native, exercising the {@code
   * IN} edge direction with an {@code outV} close. Bob and Carol are known-by Alice (edges carry
   * {@code since}); filtering {@code since >= 2015} from the Forum/target side keeps only Carol's
   * edge, so reading back the source (Alice) via {@code outV} yields {Alice} once.
   */
  @Test
  public void nonAdjacentInEdgeFilter_returnsSameMultisetAsNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010);
    alice.addEdge("knows", carol, "since", 2020);
    graph.tx().commit();

    assertEquivalent(
        "g.V().inE(knows).has(since, gte 2015).outV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().inE("knows").has("since", P.gte(2015)).outV());
  }

  /**
   * An edge-property equality filter with two chained {@code has(...)} steps AND-merges into one edge
   * {@code WHERE}: {@code outE("knows").has("since", 2010).has("weight", 5).inV()}. Only the
   * Alice→Bob edge carries both {@code since=2010} and {@code weight=5}, so both runs yield {Bob}. The
   * Alice→Carol edge (different values) is excluded, pinning that both predicates apply to the edge.
   */
  @Test
  public void nonAdjacentEdgeFilter_andMergesMultipleHasSteps() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010, "weight", 5);
    alice.addEdge("knows", carol, "since", 2010, "weight", 9);
    graph.tx().commit();

    assertEquivalent(
        "g.V().outE(knows).has(since,2010).has(weight,5).inV()",
        Recognition.RECOGNIZED,
        () -> graph
            .traversal()
            .V()
            .outE("knows")
            .has("since", 2010)
            .has("weight", 5)
            .inV());
  }

  /**
   * Parallel filtered edges preserve multiplicity in the edge-as-node form: two Alice→Bob {@code
   * knows} edges both satisfy {@code since < 2015}, so native emits Bob twice and the translated plan
   * must too (each edge is a distinct pattern match). A plan that collapsed the two edges would
   * return Bob once and break the multiset contract.
   */
  @Test
  public void nonAdjacentEdgeFilter_parallelEdgesPreserveMultiplicity() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    alice.addEdge("knows", bob, "since", 2011); // parallel edge, also matches since < 2015
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "parallel filtered edges g.V(alice).outE(knows).has(since, lt 2015).inV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).outE("knows").has("since", P.lt(2015)).inV());
  }

  /**
   * A bare hop followed by a filtered edge chain translates as a whole:
   * {@code g.V().out("knows").outE("knows").has("since", P.lt(3000)).inV()} combines the folded-hop
   * recogniser, the barrier recogniser, and the edge-filter recogniser in one traversal, exercising
   * an interleaved {@code NoOpBarrierStep} at top level between the recognised segments. Over
   * Alice→Bob→Carol (all edges {@code since < 3000}), the result matches native.
   */
  @Test
  public void hopThenFilteredEdgeChain_recognizedWithInterleavedBarrier() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010);
    bob.addEdge("knows", carol, "since", 2020);
    graph.tx().commit();

    assertEquivalent(
        "g.V().out(knows).outE(knows).has(since, lt 3000).inV()",
        Recognition.RECOGNIZED,
        () -> graph
            .traversal()
            .V()
            .out("knows")
            .outE("knows")
            .has("since", P.lt(3000))
            .inV());
  }

  /**
   * The edge-as-node form respects the edge label: {@code g.V(alice).outE("knows").has("since",
   * P.lt(3000)).inV()} must exclude a same-property {@code likes} edge, matching native. Alice has a
   * {@code knows} edge to Bob and a {@code likes} edge to Carol, both carrying {@code since} values
   * the filter keeps; only the {@code knows} edge sits on the {@code outE("knows")} label, so native
   * yields {Bob}. Every other non-adjacent filter case seeds a single edge label, so an edge-as-node
   * translation that dropped the label (matching all edge types) would pass them yet return
   * {Bob, Carol} here — this is the only end-to-end pin of the two-path-item form's label
   * discrimination. (The AST label itself is unit-pinned by {@code
   * MatchPatternBuilderTest.addEdgeAsNode_rendersOutEThenInVMethodCalls}.)
   */
  @Test
  public void nonAdjacentEdgeFilter_excludesDifferentLabelEdge() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010); // knows edge -> kept
    alice.addEdge("likes", carol, "since", 2011); // likes edge -> excluded by outE("knows")
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "g.V(alice).outE(knows).has(since, lt 3000).inV() excludes the likes edge",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).outE("knows").has("since", P.lt(3000)).inV());
  }

  /**
   * The {@code both} edge-filter chain declines: {@code bothE(L).has(...).otherV()} closes on an
   * {@code EdgeOtherVertexStep} ({@code otherV}), and the MATCH executor has no {@code otherV}
   * method, so the chain cannot be expressed and must stay on the native pipeline. With the
   * translator on it carries no boundary step, and the declined shape still returns the native
   * multiset. (Plain {@code both(L)} without an edge filter still translates — that fold is a bare
   * VertexStep.)
   */
  @Test
  public void nonAdjacentBothEdgeFilter_declinesToNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();

    assertEquivalent(
        "g.V().bothE(knows).has(since, lt 2015).otherV() (otherV unsupported)",
        Recognition.DECLINED,
        () -> graph.traversal().V().bothE("knows").has("since", P.lt(2015)).otherV());
  }

  /**
   * A {@code $}-prefixed edge-property key declines the whole chain to native rather than
   * translating: {@code g.V().outE("knows").has("$parent", 5).inV()}. Were the key translated it
   * would become a bare WHERE identifier that the MATCH executor resolves as a query context
   * variable ({@code $parent}) — silently comparing internal execution state to the literal and
   * diverging from native. Native instead rejects a {@code $}-prefixed property name outright
   * (YouTrackDB property names must start with a letter or underscore), throwing a {@link
   * DatabaseException}. The regression guard is therefore twofold: with the translator on the shape
   * must carry no boundary step (the predicate adapter declined the reserved key), and executing it
   * must throw the same {@link DatabaseException} as the native run — proving the translator fell
   * back to native rather than resolving the key against the context-variable namespace. Before the
   * fix the translated run would have swallowed the {@code $parent} key as a context variable and
   * returned a divergent (non-throwing) result. This guards the reserved-{@code $} namespace on the
   * {@code has()}-key surface, the analogue of the walker's reserved-{@code $} label pre-flight on
   * the {@code as(...)} label surface.
   */
  @Test
  public void nonAdjacentEdgeFilter_reservedDollarKeyDeclinesToNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();

    var original =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      // Translator ON: the reserved $-key must decline the whole chain (no boundary step), then run
      // on the native pipeline, which rejects the reserved property name.
      setTranslatorEnabled(true);
      var onAdmin = graph.traversal().V().outE("knows").has("$parent", 5).inV().asAdmin();
      onAdmin.applyStrategies();
      assertThat(countBoundarySteps(onAdmin.getSteps()))
          .as("a $-prefixed has() key must decline the chain to native — no boundary step")
          .isEqualTo(0);
      assertThatThrownBy(onAdmin::toList)
          .as("the declined chain runs natively, which rejects the reserved $ property name")
          .isInstanceOf(DatabaseException.class);

      // Translator OFF: the native pipeline rejects the same reserved property name identically,
      // proving the translator-on run fell back rather than resolving $parent as a context variable.
      setTranslatorEnabled(false);
      assertThatThrownBy(
          () -> graph.traversal().V().outE("knows").has("$parent", 5).inV().toList())
          .as("native rejects the reserved $ property name")
          .isInstanceOf(DatabaseException.class);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  // ---------------------------------------------------------------------------
  // A bare hop target roots at V polymorphically, never @class-narrowed, even
  // under polymorphic=false. Proves no subclass undercount.
  // ---------------------------------------------------------------------------

  /**
   * No-subclass-undercount pin: under {@code polymorphic=false} — the mode where an explicit-class
   * recogniser <em>would</em> narrow by {@code @class} — a bare {@code out("knows")} hop still returns
   * its subclass targets. The
   * targets here are {@code Person} instances (a subclass of the vertex root {@code V}), so if the
   * recogniser wrongly emitted {@code @class = 'V'} on the hop target, MATCH would return zero rows
   * (no vertex has the leaf class {@code V}) — an undercount. The equivalence assertion catches that,
   * and the extra label assertion proves the run actually returned {@code Person} subclass instances
   * rather than passing vacuously on two empty results.
   */
  @Test
  public void nonPolymorphicBareHop_doesNotUndercountSubclassTargets() {
    session.createVertexClass("Person");
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    withNonPolymorphicDefault(
        () -> assertEquivalent(
            "polymorphic=false g.V().out(knows) over Person subclass",
            Recognition.RECOGNIZED,
            () -> graph.traversal().V().out("knows")));

    // Sharpen the pin: prove the traversal returned the Person subclass target, so the equivalence
    // above was not vacuously true over two empty results. This runs under the restored default —
    // assertEquivalent's finally put the translator flag back to the value read on entry, and
    // QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED defaults to true — so it exercises the translated
    // path, not the native one. The assertion holds either way (out("knows") returns the Person
    // subclass whether translated or native); the target's leaf class must be Person, which an
    // @class='V' narrow would have excluded.
    var labels =
        graph.traversal().V().out("knows").toList().stream()
            .map(Vertex::label)
            .toList();
    assertThat(labels)
        .as("the bare-hop target must be the Person subclass instance, not undercounted")
        .containsExactly("Person");
  }

  // ---------------------------------------------------------------------------
  // Edge-subclass label polymorphism — a knows-labelled hop must span subclass
  // edges the same way native out() does (the edge-side analogue of the
  // vertex-subclass no-undercount pin above).
  // ---------------------------------------------------------------------------

  /**
   * Edge-subclass label polymorphism: {@code g.V(alice).out("knows")} must include edges whose class
   * is a subclass of {@code knows} the same number of times translator-on as native. The fixture
   * derives a {@code CloseFriend} edge class from {@code knows} and connects Alice to Carol through
   * it, plus a plain {@code knows} edge to Bob. Native {@code out("knows")} follows the {@code
   * CloseFriend} edge polymorphically, so the translated plan must too; if the translation matched
   * the {@code knows} label non-polymorphically it would drop the {@code CloseFriend} edge (an
   * undercount), and the reverse divergence (an overcount) would fail the same equivalence assertion.
   * This is the edge-side analogue of the vertex-subclass undercount that {@code
   * nonPolymorphicBareHop_doesNotUndercountSubclassTargets} pins; the plain {@code knows} edge keeps
   * the non-empty guard in {@code assertEquivalent} satisfied so the comparison is never vacuous.
   */
  @Test
  public void edgeSubclassLabel_behavesAsNativeOut() {
    // Derive CloseFriend from the knows edge class so a CloseFriend edge IS-A knows edge; the in/out
    // link properties are inherited from knows (createEdgeClass added them), as edge classes require.
    var knows = session.createEdgeClass("knows");
    session.getSchema().createClass("CloseFriend", knows);

    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob); // plain knows edge
    alice.addEdge("CloseFriend", carol); // subclass-of-knows edge
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "g.V(alice).out(knows) spanning a CloseFriend subclass edge",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).out("knows"));
  }

  /**
   * Edge-as-node analogue of {@code edgeSubclassLabel_behavesAsNativeOut}: the filtered {@code
   * outE("knows").has(...).inV()} chain must span a {@code knows} subclass edge the same way native
   * does. The fixture derives {@code CloseFriend} from {@code knows} and links Alice→Carol through it
   * (carrying {@code since}), plus a plain {@code knows} edge Alice→Bob; the filter {@code since <
   * 3000} keeps both. Native {@code outE("knows")} follows the {@code CloseFriend} edge
   * polymorphically, so the edge-as-node translation must too — yielding {Bob, Carol}. A translation
   * that matched the {@code knows} label non-polymorphically would drop the {@code CloseFriend} edge
   * (an undercount the multiset equality catches). This pins edge-label polymorphism on the
   * two-path-item edge-as-node path, which {@code edgeSubclassLabel_behavesAsNativeOut} covers only
   * for the folded bare hop.
   */
  @Test
  public void nonAdjacentEdgeFilter_spansSubclassEdgeLikeNative() {
    // Derive CloseFriend from the knows edge class so a CloseFriend edge IS-A knows edge; the in/out
    // link properties are inherited from knows (createEdgeClass added them), as edge classes require.
    var knows = session.createEdgeClass("knows");
    session.getSchema().createClass("CloseFriend", knows);

    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010); // plain knows edge, kept
    alice.addEdge("CloseFriend", carol, "since", 2020); // subclass-of-knows edge, kept
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "g.V(alice).outE(knows).has(since, lt 3000).inV() spanning a CloseFriend subclass edge",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).outE("knows").has("since", P.lt(3000)).inV());
  }

  // ---------------------------------------------------------------------------
  // Multiplicity — parallel edges, self-loops, both().
  // ---------------------------------------------------------------------------

  /**
   * Parallel edges preserve multiplicity: two {@code knows} edges from Alice to Bob make native
   * {@code g.V(alice).out("knows")} emit Bob twice, so the translated plan must emit Bob twice too.
   * A plan that deduplicated neighbours would return Bob once and break the multiset contract.
   */
  @Test
  public void parallelEdges_preserveMultiplicity() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    alice.addEdge("knows", bob); // deliberate parallel edge
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "parallel edges g.V(alice).out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).out("knows"));
  }

  /**
   * A self-loop preserves {@code both()} multiplicity: an Alice→Alice {@code knows} edge is both an
   * outgoing and an incoming edge of Alice, so native {@code both("knows")} traverses it twice and
   * emits Alice twice. The translated plan must match.
   */
  @Test
  public void selfLoop_bothPreservesMultiplicity() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    alice.addEdge("knows", alice); // self-loop
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "self-loop g.V(alice).both(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).both("knows"));
  }

  /**
   * A self-loop is emitted once by {@code out} (one outgoing edge) and once by {@code in} (one
   * incoming edge) — not twice each. Pins that the directional hops count the self-loop edge from a
   * single direction, matching native, so the {@code both()} double-count above is specific to
   * {@code both}.
   */
  @Test
  public void selfLoop_outAndInReturnSelfOnce() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    alice.addEdge("knows", alice); // self-loop
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "self-loop g.V(alice).out(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).out("knows"));
    assertEquivalent(
        "self-loop g.V(alice).in(knows)",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).in("knows"));
  }

  // ---------------------------------------------------------------------------
  // Decline case — a multi-label hop falls back to native.
  // ---------------------------------------------------------------------------

  /**
   * A multi-label hop {@code g.V().out("knows", "likes")} declines — multi-label edge traversal is
   * out of scope for Phase 1. Seeded with both a {@code knows} and a {@code likes} edge so the native
   * fallback returns a non-trivial two-label result the equivalence check pins.
   */
  @Test
  public void multiLabelHop_declinesToNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    alice.addEdge("likes", carol);
    graph.tx().commit();

    assertEquivalent(
        "g.V().out(knows, likes) (multi-label)",
        Recognition.DECLINED,
        () -> graph.traversal().V().out("knows", "likes"));
  }

  // ---------------------------------------------------------------------------
  // Absent-property edge filters — the neq presence guard and its companions.
  // ---------------------------------------------------------------------------

  /**
   * The negation presence guard end-to-end: {@code outE("knows").has("weight", P.neq(5)).inV()} must
   * exclude edges that lack the {@code weight} property, matching native. Alice has three knows
   * edges — weight 3 (kept: present, != 5), weight 5 (dropped: == 5), and no weight at all (dropped:
   * native excludes an absent property because HasContainer.test is false when the property is
   * missing). Native yields {Bob}. Before the presence guard the translated {@code weight <> 5}
   * WHERE evaluated a null (absent) operand to true and wrongly INCLUDED the no-weight edge,
   * returning {Bob, Dave} — a superset the multiset equality now catches. Passes only when neq is
   * translated as {@code weight IS DEFINED AND weight <> 5}.
   */
  @Test
  public void nonAdjacentEdgeFilter_neqExcludesAbsentProperty() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    var dave = graph.addVertex(T.label, "Person", "name", "Dave");
    alice.addEdge("knows", bob, "weight", 3); // present, != 5 -> kept
    alice.addEdge("knows", carol, "weight", 5); // present, == 5 -> dropped
    alice.addEdge("knows", dave); // no weight -> dropped (native excludes an absent property)
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "g.V(alice).outE(knows).has(weight, neq 5).inV() excludes the no-weight edge",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).outE("knows").has("weight", P.neq(5)).inV());
  }

  /**
   * Companion to the neq case: a positive comparison ({@code gt}) needs NO presence guard because an
   * absent property already excludes the edge on both sides. Alice has a weight-10 edge to Bob (kept
   * by {@code > 0}) and a no-weight edge to Carol (excluded: native's HasContainer.test is false for
   * the absent property, and the translated {@code weight > 0} WHERE evaluates a null operand to
   * false). Both runs yield {Bob}, pinning that only neq required the fix.
   */
  @Test
  public void nonAdjacentEdgeFilter_positiveComparisonExcludesAbsentProperty() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "weight", 10); // present, > 0 -> kept
    alice.addEdge("knows", carol); // no weight -> excluded on both sides
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "g.V(alice).outE(knows).has(weight, gt 0).inV() excludes the no-weight edge",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).outE("knows").has("weight", P.gt(0)).inV());
  }

  /**
   * A {@code @}-prefixed edge-property key declines the whole chain to native:
   * {@code outE("knows").has("@class", "knows").inV()}. YouTrackDB resolves a bare {@code @class}
   * identifier as record metadata, not a property, so translating it would diverge from native
   * Gremlin (which treats {@code @class} as an ordinary property the edge does not carry). The
   * predicate adapter declines the {@code @}-namespace key, so with the translator on the shape
   * carries no boundary step — the end-to-end analogue of the {@code $}-key decline above, for the
   * {@code @}-record-attribute branch of {@code WalkerContext.isReservedHasKey}. Native execution
   * semantics of {@code @class} are out of scope here; the whole-chain decline is what this guards.
   */
  @Test
  public void nonAdjacentEdgeFilter_reservedAtKeyDeclinesToNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob, "since", 2010);
    graph.tx().commit();

    var original =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      setTranslatorEnabled(true);
      var onAdmin = graph.traversal().V().outE("knows").has("@class", "knows").inV().asAdmin();
      onAdmin.applyStrategies();
      assertThat(countBoundarySteps(onAdmin.getSteps()))
          .as("a @-prefixed has() key must decline the whole chain to native — no boundary step")
          .isEqualTo(0);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  /**
   * A self-loop through the filtered edge-as-node form returns the self vertex like native:
   * {@code g.V(alice).outE("knows").has("since", P.lt(2015)).inV()} over an Alice→Alice knows edge.
   * The edge-as-node form emits two path items with distinct aliases ({@code $g2m_edge_N} and
   * {@code $g2m_anon_M}); on a self-loop both the edge's endpoints resolve to Alice, so the target
   * alias must bind to the same vertex the boundary alias does. Native yields {Alice}; a MATCH plan
   * that implicitly required distinct bindings would return empty (an undercount the non-empty guard
   * and multiset equality both catch). This is the two-path-item analogue of the bare-hop self-loop
   * cases, which never exercise the edge-as-node pattern.
   */
  @Test
  public void selfLoop_filteredEdgeChain_returnsSelfLikeNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    alice.addEdge("knows", alice, "since", 2010); // self-loop carrying an edge property
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "self-loop g.V(alice).outE(knows).has(since, lt 2015).inV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).outE("knows").has("since", P.lt(2015)).inV());
  }

  // ---------------------------------------------------------------------------
  // Connective AND over edge filters — alias-isolation trap.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().and(__.out("a"), __.out("b"))} over a source whose {@code a} and {@code b} targets
   * differ must match native. A per-child alias counter would mint the same anonymous alias twice and
   * silently require both edges to reach the same vertex, dropping every source whose targets differ.
   */
  @Test
  public void andTwoOutHops_differingTargets_matchesNative() {
    seedDualLabeledOutEdges();
    assertEquivalent(
        "g.V().and(out(a), out(b)) with differing targets",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().and(__.out("a"), __.out("b")));
  }

  /**
   * Nested {@code g.V().and(__.and(__.out("a"), __.out("b")), __.has("age", 30))} must keep both hops
   * (and the age filter) so the translated multiset matches native. A regression that left the middle
   * adapter classified pure-filter would drop the hops and over-accept vertices.
   */
  @Test
  public void nestedAndOfOutHops_thenHas_matchesNative() {
    seedDualLabeledOutEdgesWithAge();
    assertEquivalent(
        "g.V().and(and(out(a), out(b)), has(age,30)) nested connective",
        Recognition.RECOGNIZED,
        () -> graph
            .traversal()
            .V()
            .and(__.and(__.out("a"), __.out("b")), __.has("age", P.eq(30))));
  }

  // ---------------------------------------------------------------------------
  // NOT over edge-bearing sub-traversals.
  // ---------------------------------------------------------------------------

  /**
   * {@code g.V().has("age", 30).not(__.out("knows"))} translates and matches native: the positive
   * alias WHERE and the detached NOT anti-join compose the same multiset as native Gremlin.
   */
  @Test
  public void hasAge_notOutKnows_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice", "age", 30);
    var bob = graph.addVertex(T.label, "Person", "name", "Bob", "age", 30);
    var carol = graph.addVertex(T.label, "Person", "name", "Carol", "age", 30);
    alice.addEdge("knows", bob);
    carol.addEdge("likes", graph.addVertex(T.label, "Person", "name", "Dave", "age", 30));
    graph.tx().commit();

    assertEquivalent(
        "g.V().has(age,30).not(out(knows))",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V().has("age", 30).not(__.out("knows")));
  }

  /**
   * A foreign step between the edge and its close declines the whole chain to native:
   * {@code g.V().outE("knows").dedup().inV()}. {@code dedup()} is neither a {@code HasStep} nor a
   * {@code NoOpBarrierStep}, so {@code EdgeHopRecogniser} declines (its peek-ahead window spans only
   * has/barrier between the edge and the closing hop). The whole traversal runs native — including
   * the semantically-active dedup — so no boundary step engages and the result matches pure native.
   */
  @Test
  public void nonAdjacentEdgeFilter_foreignStepInWindowDeclinesToNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    alice.addEdge("knows", bob);
    graph.tx().commit();

    assertEquivalent(
        "g.V().outE(knows).dedup().inV() (foreign step in window declines)",
        Recognition.DECLINED,
        () -> graph.traversal().V().outE("knows").dedup().inV());
  }

  /**
   * {@code bothE(L).has(...).bothV()} closes on an {@code EdgeVertexStep} with BOTH direction — not
   * the {@code EdgeOtherVertexStep} that {@code otherV} produces — so, unlike otherV, it is accepted
   * and translated to the edge-as-node {@code bothV()} form. Native {@code bothV()} yields both
   * endpoints of each matched edge. Alice has one outgoing and one incoming knows edge; over
   * {@code bothE(knows).has(since < 3000).bothV()} native gathers both edges and emits both endpoints
   * of each ({Alice, Bob} and {Carol, Alice}). This pins that the translated multiset equals native.
   * If the edge-as-node bothV form ever diverges, decline bothV closing hops the way otherV is.
   */
  @Test
  public void nonAdjacentBothEdgeFilter_bothVClose_matchesNative() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob, "since", 2010); // alice out, bob in
    carol.addEdge("knows", alice, "since", 2011); // alice in, carol out
    graph.tx().commit();
    var aliceId = alice.id();

    assertEquivalent(
        "g.V(alice).bothE(knows).has(since, lt 3000).bothV()",
        Recognition.RECOGNIZED,
        () -> graph.traversal().V(aliceId).bothE("knows").has("since", P.lt(3000)).bothV());
  }

  // ---------------------------------------------------------------------------
  // Fixture helpers.
  // ---------------------------------------------------------------------------

  /** Seeds the two-edge chain Alice -knows-> Bob -knows-> Carol used by the bare-hop cases. */
  private void seedKnowsChain() {
    var alice = graph.addVertex(T.label, "Person", "name", "Alice");
    var bob = graph.addVertex(T.label, "Person", "name", "Bob");
    var carol = graph.addVertex(T.label, "Person", "name", "Carol");
    alice.addEdge("knows", bob);
    bob.addEdge("knows", carol);
    graph.tx().commit();
  }

  /**
   * Seeds a hub vertex with {@code a} and {@code b} edges to <em>different</em> targets plus a leaf
   * with only one of the labels — the fixture for {@link #andTwoOutHops_differingTargets_matchesNative}.
   */
  private void seedDualLabeledOutEdges() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub");
    var targetA = graph.addVertex(T.label, "Person", "name", "TargetA");
    var targetB = graph.addVertex(T.label, "Person", "name", "TargetB");
    var onlyA = graph.addVertex(T.label, "Person", "name", "OnlyA");
    hub.addEdge("a", targetA);
    hub.addEdge("b", targetB);
    onlyA.addEdge("a", targetA);
    graph.tx().commit();
  }

  /**
   * Same topology as {@link #seedDualLabeledOutEdges} plus an {@code age} property so a nested
   * {@code and(..., has(age))} filter selects the hub and excludes the leaf.
   */
  private void seedDualLabeledOutEdgesWithAge() {
    var hub = graph.addVertex(T.label, "Person", "name", "Hub", "age", 30);
    var targetA = graph.addVertex(T.label, "Person", "name", "TargetA", "age", 1);
    var targetB = graph.addVertex(T.label, "Person", "name", "TargetB", "age", 1);
    var onlyA = graph.addVertex(T.label, "Person", "name", "OnlyA", "age", 30);
    hub.addEdge("a", targetA);
    hub.addEdge("b", targetB);
    onlyA.addEdge("a", targetA);
    graph.tx().commit();
  }

  /**
   * Runs {@code traversalSupplier}'s shape with the translator enabled and again disabled, then
   * asserts boundary-step engagement (per {@code expected}) and result-multiset equality between the
   * two runs. The translator flag is restored afterwards so a polymorphism wrapper or a later test
   * sees the original setting.
   */
  private void assertEquivalent(
      String scenario,
      Recognition expected,
      Supplier<GraphTraversal<?, ?>> traversalSupplier) {
    var original =
        session
            .getConfiguration()
            .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED);
    try {
      // Translator ON: apply strategies (reads the flag fresh), count boundary steps, drain.
      setTranslatorEnabled(true);
      var onAdmin = traversalSupplier.get().asAdmin();
      onAdmin.applyStrategies();
      var boundaryOn = countBoundarySteps(onAdmin.getSteps());
      var onIds = sortedIds(onAdmin.toList());

      // Translator OFF: the native pipeline, never a boundary step.
      setTranslatorEnabled(false);
      var offAdmin = traversalSupplier.get().asAdmin();
      offAdmin.applyStrategies();
      var boundaryOff = countBoundarySteps(offAdmin.getSteps());
      var offIds = sortedIds(offAdmin.toList());

      if (expected == Recognition.RECOGNIZED) {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must engage exactly one boundary step")
            .isEqualTo(1);
        // Every RECOGNIZED case seeds matching data, so both runs must return a non-empty multiset.
        // Without this guard a seed regression that persisted nothing (createVertexClass/schema
        // change, a silently no-op commit, a base-class rename) would make both runs return [], the
        // multiset equality below would hold vacuously over two empty lists, and the case would go
        // green while verifying nothing — the false-green this headline fixture must not admit.
        assertThat(onIds)
            .as(scenario + ": a RECOGNIZED fixture must return a non-empty result "
                + "(else the multiset equality below is vacuous)")
            .isNotEmpty();
      } else {
        assertThat(boundaryOn)
            .as(scenario + " (translator on) must decline to native — no boundary step")
            .isEqualTo(0);
      }
      assertThat(boundaryOff)
          .as(scenario + " (translator off) must never engage a boundary step")
          .isEqualTo(0);
      assertThat(onIds)
          .as(scenario + ": translator-on and translator-off result multisets must match")
          .isEqualTo(offIds);
    } finally {
      setTranslatorEnabled(original);
    }
  }

  private void setTranslatorEnabled(boolean enabled) {
    session
        .getConfiguration()
        .setValue(GlobalConfiguration.QUERY_GREMLIN_TO_MATCH_TRANSLATOR_ENABLED, enabled);
  }

  /**
   * Runs {@code body} with {@code QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT} forced to false, restoring
   * the previous value in a finally block, so the no-undercount case exercises the non-polymorphic
   * mode where an explicit-class recogniser would narrow.
   */
  private void withNonPolymorphicDefault(Runnable body) {
    var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();
    var config = tx.getDatabaseSession().getConfiguration();
    Assert.assertNotNull(config);
    var previous =
        config.getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
    config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, false);
    try {
      body.run();
    } finally {
      config.setValue(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT, previous);
    }
  }

  /** Sorted RID strings of the matched vertices; sorting preserves multiplicity for the multiset
   *  comparison (a vertex reached N times appears N times). */
  private static List<String> sortedIds(List<?> results) {
    return results.stream().map(v -> ((Vertex) v).id().toString()).sorted().toList();
  }

  /** Counts {@link YTDBMatchPlanStep} occurrences across a step list (raw {@code List<Step>}). */
  private static int countBoundarySteps(List<?> steps) {
    var count = 0;
    for (var step : steps) {
      if (step instanceof YTDBMatchPlanStep<?, ?>) {
        count++;
      }
    }
    return count;
  }
}
