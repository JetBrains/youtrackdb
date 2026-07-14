package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;

/**
 * Recogniser for the non-adjacent edge-filter chain {@code outE(L).has(edgeProp).inV()} (and the
 * {@code inE(L).has(...).outV()} analogue). A {@code has(...)} between the edge step and its closing
 * vertex hop stops {@code IncidentToAdjacentStrategy} from folding the chain to a bare {@code
 * out(L)}, so it arrives as an edge-returning {@link VertexStep} ({@code returnsEdge() == true}), one
 * or more {@link HasStep}s, and a closing {@link EdgeVertexStep}. This shape is common — LDBC IC2
 * filters {@code knows} edges by creation date.
 *
 * <h2>Reached by delegation, not registered directly</h2>
 *
 * The edge step is a {@link VertexStep}, the same registry class as a bare hop. {@link
 * VertexStepRecogniser} owns {@code VertexStep.class} and delegates here on its {@code returnsEdge() ==
 * true} branch. This recogniser is never in the walker registry.
 *
 * <h2>Edge-as-node — the only IR form that can filter an edge</h2>
 *
 * {@code MatchPatternBuilder.addEdge}'s {@code edgeFilter} lands on the hop's <em>target-vertex</em>
 * filter, so a single {@code out(L)} path item cannot filter edge properties. The MATCH IR expresses
 * an edge filter only by node-izing the edge: the two-path-item {@code outE(L){as: $g2m_edge_N, where:
 * <edge WHERE>}.inV(){as: $g2m_anon_M}} form, which {@link
 * GremlinPatternAssembler#appendEdgeAsNode} emits.
 *
 * <h2>Reading the chain through the cursor</h2>
 *
 * The recogniser takes the edge head, consumes the {@code has(...)} run with {@link
 * StepCursor#takeWhile} (barriers skipped by the cursor), and requires the closing vertex hop with
 * {@link StepCursor#takeIf}. It declines the whole traversal when:
 *
 * <ul>
 *   <li>the head is not an edge-returning {@code VertexStep}, carries more than one edge label
 *       (multi-label is out of scope), or carries a user {@code as(...)} label (a named edge is out of
 *       scope);
 *   <li>the closing step is not exactly an {@link EdgeVertexStep} — {@code otherV} is the distinct
 *       {@code EdgeOtherVertexStep} class, and exact-class matching rejects it, so a {@code
 *       bothE(L).has(...).otherV()} chain stays native (plain {@code both(L)} without an edge filter
 *       still translates via {@link VertexHopRecogniser}); an interposed non-{@code has} step and an
 *       edge-returning terminal both leave no {@code EdgeVertexStep} at the head, so they decline too;
 *   <li>a {@code has(...)} predicate is one {@link GremlinPredicateAdapter} cannot translate.
 * </ul>
 *
 * <p>A decline discards the whole walk, so the recogniser contributes only after the shape and every
 * payload validate; the exact order is otherwise free.
 */
final class EdgeHopRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final EdgeHopRecogniser INSTANCE = new EdgeHopRecogniser();

  /** Stateless builder for the AND-merge and where-clause wrap; construction is trivial. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private EdgeHopRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    // Take the head the router dispatched. Defence in depth: re-assert an edge-returning VertexStep so
    // a direct mis-call declines cleanly rather than mis-translating.
    var head = cursor.take();
    if (!(head instanceof VertexStep<?> edgeStep) || !edgeStep.returnsEdge()) {
      return Outcome.DECLINE;
    }
    // A hop with no boundary to hang off cannot be translated: the "from" endpoint is the current
    // terminator's alias. A null here would mean an edge step reached the walker before any node was
    // pinned — decline rather than build a dangling edge.
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    // A user as(...) label on the edge step would expose the edge as a named result — out of scope.
    // (The walker's reserved-prefix pre-flight already declined any $-prefixed label.)
    if (!edgeStep.getLabels().isEmpty()) {
      return Outcome.DECLINE;
    }
    // Resolve the edge-label arity — one rule shared with VertexHopRecogniser (see
    // GremlinPatternAssembler.resolveEdgeLabel): a single named label or a label-less all-types edge
    // translates; a multi-label or blank single label declines. A null edgeLabel (label-less) flows to
    // appendEdgeAsNode, which the builder renders as the all-types bare outE(){...} form.
    var arity = GremlinPatternAssembler.resolveEdgeLabel(edgeStep, ctx);
    if (!arity.translatable()) {
      return Outcome.DECLINE;
    }
    var edgeLabel = arity.label();

    // Consume the has(...) run (barriers interleaved in it are skipped by the cursor), then require the
    // closing vertex hop. A null closing means the next step is not exactly an EdgeVertexStep — otherV
    // (EdgeOtherVertexStep), an interposed non-has step, or an edge-returning terminal — all decline.
    var hasSteps = cursor.takeWhile(HasStep.class);
    var closing = cursor.takeIf(EdgeVertexStep.class);
    if (closing == null) {
      return Outcome.DECLINE;
    }
    var closingDirection = closing.getDirection();

    // Translate every has() container into an edge WHERE; a predicate the adapter cannot translate
    // declines the whole traversal — no half-applied edge filter that would under- or over-match.
    var edgeFilters = new ArrayList<SQLBooleanExpression>();
    for (HasStep<?> has : hasSteps) {
      for (HasContainer container : has.getHasContainers()) {
        var filter = GremlinPredicateAdapter.INSTANCE.toFilter(container);
        if (filter == null) {
          return Outcome.DECLINE;
        }
        edgeFilters.add(filter);
      }
    }

    // Contribute. Mint the edge alias then the target alias so a multi-hop chain gets distinct names.
    var fromAlias = ctx.boundaryAlias();
    var edgeAlias = ctx.nextEdgeAlias();
    var targetAlias = ctx.nextAnonVertexAlias();

    // AND-merge the accumulated predicates into one edge WHERE (null when the edge is unfiltered, e.g.
    // an outE(L).barrier().inV() chain that never folded). Record it under the edge alias so the
    // accumulation is observable, and hand the same clause to the assembler, which puts it on the edge
    // path item so it filters the edge rather than the target vertex.
    SQLWhereClause edgeWhere = null;
    var merged = WHERE.andOptional(edgeFilters.toArray(new SQLBooleanExpression[0]));
    if (merged != null) {
      edgeWhere = WHERE.wrap(merged);
      ctx.putEdgeFilter(edgeAlias, edgeWhere);
    }

    var edgeDirection = GremlinPatternAssembler.toBuilderDirection(edgeStep.getDirection());
    var closingVertexDirection = GremlinPatternAssembler.toBuilderDirection(closingDirection);
    GremlinPatternAssembler.appendEdgeAsNode(
        ctx,
        fromAlias,
        edgeAlias,
        targetAlias,
        edgeDirection,
        edgeLabel,
        closingVertexDirection,
        edgeWhere);
    return Outcome.ACCEPTED;
  }
}
