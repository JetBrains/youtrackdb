package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.ProjectionExpressionFactories;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization.InlineFilterStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link SubTraversalPredicateAdapter} + the {@link RecognitionContext#walkChild}
 * sub-walk seam — the infrastructure a later track's logical-combinator recognisers (and / or / not /
 * where) drive their child sub-traversals through. Three layers are pinned:
 *
 * <ul>
 *   <li><b>The delegating capture contract</b> — reads and alias minting delegate to the parent;
 *       {@code pinBoundary} / {@code setSingleReturnColumn} are swallowed; filter and pattern
 *       contributions are captured in the adapter, never committed to the parent. These are driven
 *       against a mocked parent so each delegate / swallow / capture is isolated.
 *   <li><b>The sub-walk seam</b> — {@code walkChild} drives a child's step list against the same
 *       registry the top-level walk uses, classifies the child as pure-filter or edge-bearing, and —
 *       the capture boundary — leaves the parent's committed state untouched when a child
 *       declines. These are driven against a real registry-bearing {@link WalkerContext} parent with
 *       fixture recognisers and the production {@link VertexHopRecogniser}.
 *   <li><b>The list-shaping decline channel</b> — {@code supportsListShaping()} answers {@code false}
 *       without delegating the parent's {@code true}, {@code listShapingOps()} answers empty without
 *       delegating a parent that does carry a stage, and {@code appendListShapingOp}
 *       throws instead of swallowing. These are driven against a real {@link WalkerContext} parent
 *       used as a positive control: Mockito answers {@code false} to every unstubbed {@code boolean}
 *       and an empty list to every unstubbed collection, so a mocked parent would make each pair
 *       agree for the wrong reason. No sub-walk runs here, so the parent needs no registry.
 * </ul>
 */
public class SubTraversalPredicateAdapterTest {

  private static final String BOUNDARY_ALIAS = "$g2m_v0";
  private static final String FIRST_ANON_ALIAS = "$g2m_anon_0";
  private static final String SECOND_ANON_ALIAS = "$g2m_anon_1";

  // ---------------------------------------------------------------------------
  // Delegating capture contract — driven against a mocked parent so each read
  // delegates, each swallow is a no-op, and each contribution is captured
  // without touching the parent.
  // ---------------------------------------------------------------------------

  /**
   * Every read the child needs — resolved flags, the current boundary, schema gating — and both alias
   * mints delegate straight to the parent. Alias delegation is the load-bearing one: a per-child
   * counter would mint duplicate aliases and silently over-constrain, so the adapter must forward to
   * the parent's single sequence.
   */
  @Test
  public void reads_and_aliasMinting_delegateToParent() {
    var parent = mock(RecognitionContext.class);
    when(parent.polymorphic()).thenReturn(true);
    when(parent.edgeLabelVerificationEnabled()).thenReturn(true);
    when(parent.boundaryAlias()).thenReturn(BOUNDARY_ALIAS);
    when(parent.isDeclaredStringProperty("Person", "name")).thenReturn(true);
    when(parent.isVertexClass("Person")).thenReturn(true);
    when(parent.nextAnonVertexAlias()).thenReturn(FIRST_ANON_ALIAS);
    when(parent.nextEdgeAlias()).thenReturn("$g2m_edge_0");
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());

    assertThat(adapter.polymorphic()).isTrue();
    assertThat(adapter.edgeLabelVerificationEnabled()).isTrue();
    assertThat(adapter.boundaryAlias()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(adapter.isDeclaredStringProperty("Person", "name")).isTrue();
    assertThat(adapter.isVertexClass("Person")).isTrue();
    assertThat(adapter.nextAnonVertexAlias()).isEqualTo(FIRST_ANON_ALIAS);
    assertThat(adapter.nextEdgeAlias()).isEqualTo("$g2m_edge_0");
  }

  /**
   * Order capture and the ordered-slice gate are swallowed on a sub-walk: a child's {@code order()}
   * must not let a slice inside a combinator ride a sort the parent captured, and the adapter never
   * forwards the parent's affirmative answer.
   */
  @Test
  public void orderCaptureAndSliceGate_areSwallowedAndAlwaysFalse() {
    var parent = new WalkerContext(true, false);
    parent.addNode(BOUNDARY_ALIAS, "V");
    parent.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    parent.setSingleReturnColumn(BOUNDARY_ALIAS);
    parent.setOrderBy(
        MatchProjectionBuilder.orderBy(
            List.of(ProjectionExpressionFactories.orderByProperty(BOUNDARY_ALIAS, "name", true))));
    parent.recordOrderByCapture(BOUNDARY_ALIAS, true);
    assertThat(parent.orderAllowsSliceOnCurrentBoundary())
        .as("fixture premise: parent would allow a slice on its captured order")
        .isTrue();

    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());
    adapter.recordOrderByCapture(FIRST_ANON_ALIAS, false);
    adapter.markReturnReadsForeignAlias();

    assertThat(adapter.orderAllowsSliceOnCurrentBoundary())
        .as("sub-walk must never license an ordered slice")
        .isFalse();
    assertThat(parent.orderAllowsSliceOnCurrentBoundary())
        .as("child calls must not mutate the parent's capture")
        .isTrue();
  }

  /**
   * {@code pinBoundary} and {@code setSingleReturnColumn} are swallowed: a child changes the parent's
   * filter, never its result shape, so a hop child's boundary / RETURN re-pin must not reach the
   * parent. Verified by proving the parent's two methods are never called.
   */
  @Test
  public void pinBoundaryAndSingleReturnColumn_areSwallowed() {
    var parent = mock(RecognitionContext.class);
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());

    adapter.pinBoundary(FIRST_ANON_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    adapter.setSingleReturnColumn(FIRST_ANON_ALIAS);

    verify(parent, never()).pinBoundary(any(), any(), any());
    verify(parent, never()).setSingleReturnColumn(any());
  }

  /**
   * {@code putAliasFilter} captures into the adapter's own buffer and AND-composes a second same-alias
   * contribution (within one child the filter steps are conjunctive), never committing to the parent.
   * The connective's own composition (AND across AND children, OR across OR children) is applied later
   * to this one conjoined clause per alias by the combinator recogniser.
   */
  @Test
  public void putAliasFilter_capturesAndAndComposesWithoutCommitting() {
    var parent = mock(RecognitionContext.class);
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());

    adapter.putAliasFilter(BOUNDARY_ALIAS, whereClause("age"));
    adapter.putAliasFilter(BOUNDARY_ALIAS, whereClause("city"));

    assertThat(adapter.capturedAliasFilters()).containsOnlyKeys(BOUNDARY_ALIAS);
    assertThat(adapter.capturedAliasFilters().get(BOUNDARY_ALIAS).toString())
        .as("the two same-alias contributions AND-compose into one clause")
        .contains("age")
        .contains("city");
    verify(parent, never()).putAliasFilter(any(), any());
  }

  /**
   * Pattern contributions ({@code addNode} / {@code addEdge} / {@code addEdgeAsNode}) are captured in
   * the adapter's own pattern builder, never reaching the parent's. Only an edge/hop ({@code addEdge}
   * / {@code addEdgeAsNode}) flips {@link SubTraversalPredicateAdapter#hasEdges()} — a bare {@code
   * addNode} (a boundary-node re-type) is classification-neutral, so the flag is driven by hops alone.
   * Edge filters are captured for observability.
   */
  @Test
  public void patternAndEdgeFilterContributions_captureAndFlagHasEdges() {
    var parent = mock(RecognitionContext.class);
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());
    assertThat(adapter.hasEdges()).as("a fresh adapter is pure-filter until a fragment lands")
        .isFalse();

    adapter.addNode(FIRST_ANON_ALIAS, "V");
    assertThat(adapter.hasEdges()).as("a bare addNode is a re-type, not a hop — still pure-filter")
        .isFalse();

    adapter.addEdge(BOUNDARY_ALIAS, FIRST_ANON_ALIAS, MatchPatternBuilder.Direction.OUT, "knows");
    assertThat(adapter.hasEdges()).as("an addEdge is a hop — the child is now edge-bearing")
        .isTrue();
    adapter.addEdgeAsNode(
        BOUNDARY_ALIAS,
        "$g2m_edge_0",
        SECOND_ANON_ALIAS,
        MatchPatternBuilder.Direction.OUT,
        "knows",
        MatchPatternBuilder.Direction.IN,
        null);
    adapter.putEdgeFilter("$g2m_edge_0", whereClause("since"));

    assertThat(adapter.hasEdges()).as("an edge/hop makes the child edge-bearing").isTrue();
    assertThat(adapter.capturedPattern().hasAlias(FIRST_ANON_ALIAS)).isTrue();
    assertThat(adapter.capturedPattern().hasAlias(SECOND_ANON_ALIAS)).isTrue();
    assertThat(adapter.capturedPattern().hasAlias("$g2m_edge_0")).isTrue();
    assertThat(adapter.capturedEdgeFilters()).containsOnlyKeys("$g2m_edge_0");
    verify(parent, never()).addNode(any(), any());
    verify(parent, never()).addEdge(any(), any(), any(), any());
    verify(parent, never()).addEdgeAsNode(any(), any(), any(), any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------
  // Sub-walk seam — driven against a real registry-bearing WalkerContext parent.
  // ---------------------------------------------------------------------------

  /**
   * The capture-boundary invariant: a child whose recognised prefix contributes to the
   * sub-context and then hits an unrecognised step declines the whole child, and the parent's
   * committed state is left exactly as it was. The fixture recogniser (registered for {@code
   * VertexStep}) contributes an alias filter and a pattern node into the sub-context; the trailing
   * {@code count()} has no recogniser, so the child declines. The returned adapter still shows the
   * partial contribution reached the sub-context (proving the test is not vacuous), while the parent's
   * alias filters, pattern, boundary, and RETURN column are untouched.
   */
  @Test
  public void decline_doesNotCommitPartialStateToOuterContext() {
    StepRecogniser contributing =
        (cursor, ctx) -> {
          cursor.take();
          ctx.putAliasFilter(ctx.boundaryAlias(), whereClause("age"));
          ctx.addNode(ctx.nextAnonVertexAlias(), "V");
          return Outcome.ACCEPTED;
        };
    var parent = parentWithBoundary(Map.of(VertexStep.class, contributing));

    // out("a") is claimed by the contributing fixture; the trailing count() has no recogniser, so the
    // child declines after a partial contribution.
    var sub = parent.walkChild(__.out("a").count().asAdmin());

    assertThat(sub.outcome()).as("an unrecognised child step declines the whole child")
        .isEqualTo(Outcome.DECLINE);
    assertThat(sub.capturedAliasFilters())
        .as("the partial contribution did reach the sub-context (the test is not vacuous)")
        .containsKey(BOUNDARY_ALIAS);

    // The capture boundary: the parent's committed state is untouched by the declined child.
    assertThat(parent.aliasFilters).as("declined child commits no alias filter to the parent")
        .isEmpty();
    assertThat(parent.patternBuilder.hasAlias(FIRST_ANON_ALIAS))
        .as("declined child adds no node to the parent's pattern")
        .isFalse();
    assertThat(parent.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(parent.returnAliases).hasSize(1);
    assertThat(parent.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
  }

  /**
   * An edge-bearing child (a real {@code out("knows")} hop through {@link VertexHopRecogniser})
   * classifies as edge-bearing and captures its hop fragment in the adapter, while the hop's boundary
   * / RETURN re-pin is swallowed so the parent's result shape is unchanged. This exercises the swallow
   * through the production hop-assembly path, not just a direct method call.
   */
  @Test
  public void edgeBearingChild_capturesHopAndSwallowsRePin() {
    var parent = parentWithBoundary(Map.of(VertexStep.class, VertexHopRecogniser.INSTANCE));

    var sub = parent.walkChild(__.out("knows").asAdmin());

    assertThat(sub.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(sub.hasEdges()).as("a hop child is edge-bearing").isTrue();
    assertThat(sub.capturedPattern().hasAlias(FIRST_ANON_ALIAS))
        .as("the hop target is captured in the adapter")
        .isTrue();
    // The re-pin is swallowed: the parent's boundary and single RETURN column stay on the outer
    // boundary alias, not the hop target.
    assertThat(parent.boundaryAlias).isEqualTo(BOUNDARY_ALIAS);
    assertThat(parent.returnAliases).hasSize(1);
    assertThat(parent.returnAliases.getFirst().getStringValue()).isEqualTo(BOUNDARY_ALIAS);
    assertThat(parent.patternBuilder.hasAlias(FIRST_ANON_ALIAS))
        .as("the hop target is not added to the parent's pattern")
        .isFalse();
  }

  /**
   * A {@code not(hop)} inside a combinator child leaves its detached anti-join in the child's own
   * buffer and writes nothing to the parent, so the enclosing connective is the one that decides
   * whether a conjunctive plan-level NOT is the right reading. Forwarding it directly was the shape
   * that answered {@code or(not(out(a)).has(...), has(...))} as a conjunction and dropped rows: the
   * expression reached the plan sink while the OR arm read back only its own boundary filter.
   *
   * <p>The child stays classified pure-filter — the hop lives in the grandchild adapter, not in this
   * child's positive pattern — which is exactly why the OR path cannot detect the arm by
   * {@code hasEdges} alone and has to read the buffer.
   */
  @Test
  public void notHopChild_capturesAntiJoinWithoutWritingToParent() {
    var parent =
        parentWithBoundary(
            Map.of(
                VertexStep.class, VertexHopRecogniser.INSTANCE,
                NotStep.class, NotStepRecogniser.INSTANCE));

    var sub = parent.walkChild(__.not(__.out("a")).asAdmin());

    assertThat(sub.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(sub.capturedNotExpressions())
        .as("the anti-join stays in the child's buffer")
        .hasSize(1);
    assertThat(parent.notMatchExpressions)
        .as("nothing reaches the parent's plan-level NOT sink before a connective commits it")
        .isEmpty();
    assertThat(sub.hasEdges())
        .as("a not(hop) child is pure-filter — the hop is inside the grandchild adapter")
        .isFalse();
  }

  /**
   * {@link ConnectiveStepSupport#commitPureFilterChild} is the conjunctive commit path, so it hands
   * the captured anti-join on to the parent's plan-level sink. AND arms and positive
   * {@code where} / {@code filter} children both have to hold for a row to pass, which is the same
   * reading the planner gives the sink.
   */
  @Test
  public void commitPureFilterChild_forwardsCapturedAntiJoinToParent() {
    var parent =
        parentWithBoundary(
            Map.of(
                VertexStep.class, VertexHopRecogniser.INSTANCE,
                NotStep.class, NotStepRecogniser.INSTANCE));
    var sub = parent.walkChild(__.not(__.out("a")).asAdmin());

    ConnectiveStepSupport.commitPureFilterChild(parent, sub, BOUNDARY_ALIAS);

    assertThat(parent.notMatchExpressions)
        .as("the conjunctive commit path forwards the anti-join")
        .hasSize(1);
  }

  /**
   * {@link ConnectiveStepSupport#commitPureFilterChild} applies a captured boundary re-type and
   * alias filters onto the parent context.
   */
  @Test
  public void commitPureFilterChild_appliesReTypeAndFilters() {
    var parent = parentWithBoundary(Map.of());
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());
    adapter.addNode(BOUNDARY_ALIAS, "Person");
    adapter.putAliasFilter(BOUNDARY_ALIAS, whereClause("age"));

    ConnectiveStepSupport.commitPureFilterChild(parent, adapter, BOUNDARY_ALIAS);

    assertThat(parent.patternBuilder.registeredAliasClasses().get(BOUNDARY_ALIAS))
        .isEqualTo("Person");
    assertThat(parent.aliasFilters).containsKey(BOUNDARY_ALIAS);
  }

  /**
   * {@link ConnectiveStepSupport#commitPositiveFilterChild} declines an edge-bearing child and
   * writes nothing to the parent — neither the hop fragment nor the target filter the child
   * captured. Committing the hop would translate the existence test as a join, so the whole filter
   * has to withdraw and leave the parent context exactly as it found it.
   */
  @Test
  public void commitPositiveFilterChild_edgeBearingChild_declinesWithoutMutatingParent() {
    var parent = parentWithBoundary(Map.of());
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());
    adapter.addEdge(
        BOUNDARY_ALIAS, FIRST_ANON_ALIAS, MatchPatternBuilder.Direction.OUT, "knows");
    adapter.addNode(FIRST_ANON_ALIAS, "V");
    adapter.putAliasFilter(FIRST_ANON_ALIAS, whereClause("age"));
    adapter.markOutcome(Outcome.ACCEPTED);

    var outcome = ConnectiveStepSupport.commitPositiveFilterChild(parent, adapter);

    assertThat(outcome).isEqualTo(Outcome.DECLINE);
    assertThat(parent.patternBuilder.hasAlias(FIRST_ANON_ALIAS)).isFalse();
    assertThat(parent.aliasFilters).isEmpty();
  }

  /**
   * A child that only re-types the boundary node — the shape a folded {@code hasLabel(L)} produces, a
   * {@code ctx.addNode(boundaryAlias, L)} that narrows the existing boundary's class plus a {@code
   * @class} alias filter — is <b>pure-filter</b>, not edge-bearing: it adds no hop, so {@code
   * hasEdges()} stays {@code false}. The re-type still lands in the captured pattern (a class
   * narrowing), but classification keys on the edge/hop contribution, not on any {@code addNode}. This
   * is the counterpart to {@link #edgeBearingChild_capturesHopAndSwallowsRePin}: it guards against
   * mistaking a {@code hasLabel}-bearing pure-filter child for an edge-bearing one, which would make a
   * later {@code or(hasLabel, hasLabel)} wrongly decline and a {@code not(hasLabel)} route to the
   * edge-bearing anti-join path.
   */
  @Test
  public void reTypeOnlyChild_isPureFilter() {
    StepRecogniser labelReType =
        (cursor, ctx) -> {
          cursor.take();
          // Mirrors HasStepRecogniser's folded hasLabel(L) contribution: re-type the boundary node's
          // class through addNode, then add the leaf-exact @class filter on the same alias.
          ctx.addNode(ctx.boundaryAlias(), "Person");
          ctx.putAliasFilter(ctx.boundaryAlias(), whereClause("@class"));
          return Outcome.ACCEPTED;
        };
    var parent = parentWithBoundary(Map.of(VertexStep.class, labelReType));

    var sub = parent.walkChild(__.out("a").asAdmin());

    assertThat(sub.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(sub.hasEdges()).as("a boundary re-type adds no hop — the child is pure-filter")
        .isFalse();
    assertThat(sub.capturedAliasFilters()).containsKey(BOUNDARY_ALIAS);
    assertThat(sub.capturedPattern().hasAlias(BOUNDARY_ALIAS))
        .as("the re-type still lands in the captured pattern")
        .isTrue();
    assertThat(parent.aliasFilters).as("the captured filter is not committed to the parent")
        .isEmpty();
  }

  /**
   * A pure-filter child (only an alias-filter contribution, no pattern fragment) classifies as
   * pure-filter ({@code hasEdges() == false}) and its filter is captured, not committed to the parent.
   */
  @Test
  public void pureFilterChild_capturesFilterAsNonEdgeBearing() {
    StepRecogniser pureFilter =
        (cursor, ctx) -> {
          cursor.take();
          ctx.putAliasFilter(ctx.boundaryAlias(), whereClause("age"));
          return Outcome.ACCEPTED;
        };
    var parent = parentWithBoundary(Map.of(VertexStep.class, pureFilter));

    var sub = parent.walkChild(__.out("a").asAdmin());

    assertThat(sub.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(sub.hasEdges()).as("a filter-only child is pure-filter").isFalse();
    assertThat(sub.capturedAliasFilters()).containsKey(BOUNDARY_ALIAS);
    assertThat(parent.aliasFilters).as("the captured filter is not committed to the parent")
        .isEmpty();
  }

  /**
   * Two sibling children mint distinct anonymous aliases because minting delegates to the parent's
   * single sequence. A per-child counter would give both children {@code $g2m_anon_0}, and in MATCH
   * one alias is one binding, so the two hops would silently collapse onto "both edges reach the
   * same vertex". The live shape that depends on this is a connective whose arms each hold a hop
   * inside a {@code not}, with a barrier keeping the connective intact:
   * {@code and(__.not(__.out("a")).barrier(), __.not(__.out("b")).barrier())} still translates,
   * while a bare {@code and(__.out("a"), __.out("b"))} declines on the edge-bearing gate before the
   * second alias is minted. Here the second child gets {@code $g2m_anon_1}.
   *
   * <p>The barrier is not decoration and the first assertion below observes it. Without one,
   * {@code InlineFilterStrategy} unwraps the connective into two top-level {@code NotStep}s, which
   * mint from the top-level context directly and never drive a captured child at all — the shape
   * this rationale names would not be a shape that reaches the code under test.
   */
  @Test
  public void siblingChildren_mintDistinctAliasesFromParentSequence() {
    var cited = __.and(__.not(__.out("a")).barrier(), __.not(__.out("b")).barrier()).asAdmin();
    TraversalHelper.applyTraversalRecursively(InlineFilterStrategy.instance()::apply, cited);
    assertThat(cited.getSteps().stream().anyMatch(AndStep.class::isInstance))
        .as("the shape this test's rationale names must survive InlineFilterStrategy as a "
            + "connective, or its arms are never captured children and the rationale names nothing")
        .isTrue();

    var parent = parentWithBoundary(Map.of(VertexStep.class, VertexHopRecogniser.INSTANCE));

    var first = parent.walkChild(__.out("a").asAdmin());
    var second = parent.walkChild(__.out("b").asAdmin());

    assertThat(first.capturedPattern().hasAlias(FIRST_ANON_ALIAS))
        .as("the first child mints the first anonymous alias")
        .isTrue();
    assertThat(second.capturedPattern().hasAlias(SECOND_ANON_ALIAS))
        .as("the second child mints the next alias, not a duplicate of the first")
        .isTrue();
    assertThat(second.capturedPattern().hasAlias(FIRST_ANON_ALIAS))
        .as("the second child does not reuse the first child's alias")
        .isFalse();
  }

  /**
   * A nested combinator child ({@code and(and(...), ...)}) drives its grandchild through the adapter's
   * own {@code walkChild}, against the same registry, with alias minting still bottoming out at the
   * top-level context. Pins that the seam composes recursively.
   */
  @Test
  public void nestedWalkChild_drivesGrandchildAgainstRegistry() {
    Map<Class<?>, StepRecogniser> registry = Map.of(VertexStep.class, VertexHopRecogniser.INSTANCE);
    var parent = parentWithBoundary(registry);
    var adapter = new SubTraversalPredicateAdapter(parent, registry);

    var grandchild = adapter.walkChild(__.out("knows").asAdmin());

    assertThat(grandchild.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(grandchild.hasEdges()).isTrue();
    assertThat(grandchild.capturedPattern().hasAlias(FIRST_ANON_ALIAS))
        .as("the grandchild mints from the top-level sequence through the adapter chain")
        .isTrue();
  }

  /**
   * An empty child sub-traversal declines up front, mirroring the top-level walk's empty-traversal
   * gate — a combinator child with no steps expresses no filter.
   */
  @Test
  public void emptyChild_declines() {
    var parent = parentWithBoundary(Map.of(VertexStep.class, VertexHopRecogniser.INSTANCE));
    @SuppressWarnings("unchecked")
    Traversal.Admin<Object, Object> emptyChild = mock(Traversal.Admin.class);
    when(emptyChild.getSteps()).thenReturn(List.of());

    var sub = parent.walkChild(emptyChild);

    assertThat(sub.outcome()).as("an empty child declines").isEqualTo(Outcome.DECLINE);
  }

  /**
   * A registry-less {@link WalkerContext} (the test constructors that never drive a sub-walk) fails
   * loud on {@code walkChild} rather than silently declining, so a wiring bug that forgot to thread
   * the registry surfaces as an error. The production walk always supplies a registry.
   */
  @Test
  public void walkChild_onRegistrylessContext_throws() {
    var ctx = new WalkerContext(true, false);

    assertThatThrownBy(() -> ctx.walkChild(__.out("a").asAdmin()))
        .as("a context built without a registry cannot drive a sub-walk")
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------------------------------------------------------------------------
  // List-shaping decline channel — driven against a real WalkerContext parent
  // used as a positive control, because a mocked parent answers false to every
  // unstubbed boolean and empty to every unstubbed collection. No test in this
  // block drives a sub-walk, so no registry is needed.
  // ---------------------------------------------------------------------------

  /**
   * The list-shaping decline channel, pinned as a discriminating pair on one fixture rather than as
   * a bare {@code isFalse()}. The real parent {@link WalkerContext} answers {@code true} — the
   * positive control proving the assertion below reads the sub-walk's own answer and not a fixture
   * that answers {@code false} to everything — while the adapter wrapping that same parent answers
   * {@code false} instead of delegating. The pair is what a list-shaping recogniser reads to accept
   * at top level and decline inside {@code and} / {@code or} / {@code not} / {@code where} /
   * {@code filter}. A mocked parent would make the pair vacuous: Mockito answers {@code false} for
   * any unstubbed {@code boolean}, so both arms would agree for the wrong reason.
   */
  @Test
  public void supportsListShaping_falseOnSubWalk_trueOnTheParentItWraps() {
    var parent = new WalkerContext(true, false);
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());

    assertThat(parent.supportsListShaping())
        .as("the top-level walk carries the shaping the boundary base reads")
        .isTrue();
    assertThat(adapter.supportsListShaping())
        .as("a sub-walk declines instead of carrying an op, and never delegates the parent's true")
        .isFalse();
  }

  /**
   * A combinator child cannot promote a drop-on-absent into a pattern conjunct. The parent's
   * {@code true} (nothing to promote, no drop pinned) is the discriminating half: Mockito would
   * answer {@code false} for an unstubbed parent and make both arms agree for the wrong reason.
   */
  @Test
  public void promotePresenceDrop_falseOnSubWalk_trueOnAParentWithNothingToPromote() {
    var parent = new WalkerContext(true, false);
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());

    assertThat(parent.promotePresenceDropToPatternFilter())
        .as("a top-level context with no drop has nothing to promote")
        .isTrue();
    assertThat(adapter.promotePresenceDropToPatternFilter())
        .as("a sub-walk declines instead of promoting, and never delegates the parent's true")
        .isFalse();
  }

  /**
   * The gate-side query answers empty on a sub-walk without delegating a parent that does carry a
   * stage — the discriminating half being the parent's non-empty answer, which a bare
   * {@code isEmpty()} on a fresh parent could not tell from a delegated one. The walker reads this
   * twice per dispatch loop, so delegating would gate a combinator child's steps on a stage the
   * child's payloads never reach and compare the parent's list around the child's own accepts; a step
   * behind a captured stage is refused at the parent's own level instead, which is why no sub-walk
   * ever runs behind one.
   */
  @Test
  public void listShapingOps_emptyOnSubWalk_evenWhenTheParentCarriesOne() {
    var parent = new WalkerContext(true, false);
    parent.appendListShapingOp(upstream -> upstream);
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());

    assertThat(parent.listShapingOps())
        .as("fixture premise: the parent really carries a stage")
        .isNotEmpty();
    assertThat(adapter.listShapingOps())
        .as("a sub-walk can never carry one, and does not delegate the parent's answer")
        .isEmpty();
  }

  /**
   * A recogniser that appends without first reading
   * {@link RecognitionContext#supportsListShaping()} hits a throw, and the parent's shaping stays
   * untouched — nothing of the failed append leaks outward. The throw is a guard for the recogniser
   * that forgets rather than the decline channel itself; {@link
   * RecognitionContext#supportsListShaping()} carries the argument for why neither a throw nor a
   * swallow can serve as that channel.
   */
  @Test
  public void appendListShapingOp_onSubWalk_throwsAndLeavesTheParentShapingClean() {
    var parent = new WalkerContext(true, false);
    var adapter = new SubTraversalPredicateAdapter(parent, Map.of());

    assertThatThrownBy(() -> adapter.appendListShapingOp(upstream -> upstream))
        .as("a sub-walk cannot carry a list-shaping op")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(parent.shaping().listShapingOps())
        .as("and nothing leaked onto the parent's shaping")
        .isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  /**
   * Builds a registry-bearing {@link WalkerContext} pre-seeded as the start step would leave it: a
   * pinned {@code $g2m_v0} boundary with one RETURN column keyed on that alias. A sub-walk reads this
   * boundary through the adapter; the capture-boundary assertions check it is unchanged after a
   * declined child.
   */
  private static WalkerContext parentWithBoundary(Map<Class<?>, StepRecogniser> registry) {
    var ctx = new WalkerContext(true, false, null, registry);
    ctx.addNode(BOUNDARY_ALIAS, "V");
    ctx.pinBoundary(BOUNDARY_ALIAS, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(BOUNDARY_ALIAS);
    return ctx;
  }

  /** A trivial {@code field IS DEFINED} WHERE clause, used as a stand-in filter contribution. */
  private static SQLWhereClause whereClause(String field) {
    var builder = new MatchWhereBuilder();
    return builder.wrap(builder.isDefined(field));
  }
}
