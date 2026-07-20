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
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Unit tests for {@link SubTraversalPredicateAdapter} + the {@link RecognitionContext#walkChild}
 * sub-walk seam — the infrastructure a later track's logical-combinator recognisers (and / or / not /
 * where) drive their child sub-traversals through. Two layers are pinned:
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
   * single sequence. A per-child counter would give both children {@code
   * $g2m_anon_0}, collapsing {@code and(__.out("a"), __.out("b"))} onto one binding; here the second
   * child gets {@code $g2m_anon_1}.
   */
  @Test
  public void siblingChildren_mintDistinctAliasesFromParentSequence() {
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
