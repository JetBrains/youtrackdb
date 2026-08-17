package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNeOperator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeOtherVertexStep;
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
 * VertexStepRecogniser} owns {@code VertexStep.class} and delegates here when the head is
 * edge-returning and followed by one or more {@link HasStep}s. This recogniser is never in the walker
 * registry.
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
 *   <li>the head is not an edge-returning {@code VertexStep} or carries more than one edge label
 *       (multi-label is out of scope);
 *   <li>the closing step is neither an {@link EdgeVertexStep} nor an {@link EdgeOtherVertexStep} — an
 *       interposed non-{@code has} step and an edge-returning terminal both leave no closing hop, so
 *       they decline too;
 *   <li>a user {@code as(...)} label on the edge or closing step collides with an existing bind;
 *   <li>a {@code has(...)} predicate is one {@link GremlinPredicateAdapter} cannot translate.
 * </ul>
 *
 * <p>A {@code bothE(L).has(...).otherV()} chain closes on {@link EdgeOtherVertexStep} rather than
 * {@link EdgeVertexStep}. MATCH has no {@code otherV} method, so the recogniser maps {@code otherV}
 * to {@code inV}/{@code outV} on the edge-as-node form — {@code inV} for {@code outE}/{@code bothE},
 * {@code outV} for {@code inE} — which returns the endpoint on the far side of the directed edge
 * record when the walk entered from the near side.
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
    // Resolve the edge-label arity — one rule shared with VertexHopRecogniser (see
    // GremlinPatternAssembler.resolveEdgeLabel): a single named label or a label-less all-types edge
    // translates; a multi-label or blank single label declines. A null edgeLabel (label-less) flows to
    // appendEdgeAsNode, which the builder renders as the all-types bare outE(){...} form.
    var arity = GremlinPatternAssembler.resolveEdgeLabel(edgeStep, ctx);
    if (!arity.translatable()) {
      return Outcome.DECLINE;
    }
    var edgeLabel = arity.label();
    var edgeDirection = GremlinPatternAssembler.toBuilderDirection(edgeStep.getDirection());

    // Consume the has(...) run (barriers interleaved in it are skipped by the cursor), then the closing
    // vertex hop — inV/outV via EdgeVertexStep, otherV via EdgeOtherVertexStep.
    var hasSteps = cursor.takeWhile(HasStep.class);
    Step closingStep;
    MatchPatternBuilder.Direction closingVertexDir;
    var closingVertex = cursor.takeIf(EdgeVertexStep.class);
    if (closingVertex != null) {
      closingStep = closingVertex;
      closingVertexDir = GremlinPatternAssembler.toBuilderDirection(closingVertex.getDirection());
    } else {
      var closingOther = cursor.takeIf(EdgeOtherVertexStep.class);
      if (closingOther == null) {
        return Outcome.DECLINE;
      }
      closingStep = closingOther;
      if (edgeDirection == MatchPatternBuilder.Direction.OUT) {
        closingVertexDir = MatchPatternBuilder.Direction.IN;
      } else if (edgeDirection == MatchPatternBuilder.Direction.IN) {
        closingVertexDir = MatchPatternBuilder.Direction.OUT;
      } else {
        // bothE: bothV() then drop the walk source on the far alias.
        closingVertexDir = MatchPatternBuilder.Direction.BOTH;
      }
    }

    // Translate every has() container into an edge WHERE; a predicate the adapter cannot
    // translate declines the whole traversal — no half-applied edge filter that would under- or
    // over-match.
    //
    // Important: defer minting edge/target aliases (and thus the per-context alias counters)
    // until after the whole has(...) run translates successfully, so a decline leaves the
    // context counters intact (see EdgeHopRecogniserTest).
    var fromAlias = ctx.boundaryAlias();

    // The type gate keys on the edge class (the resolved label) so a startingWith on a declared-
    // String edge property uses the index-aware prefix range and every other case the strict
    // full-scan form. A label-less edge (null label) has no known class, so all its keys route
    // to strict.
    GremlinPredicateAdapter.PropertyTypeGate typeGate =
        key -> ctx.isDeclaredStringProperty(edgeLabel, key);
    ParamSink paramSink = ctx::bindParam;
    var edgeFilters = new ArrayList<SQLBooleanExpression>();
    for (HasStep<?> has : hasSteps) {
      for (HasContainer container : has.getHasContainers()) {
        // An edge property filter is never folded into YTDBGraphStep — the fold only reaches the
        // HasSteps that directly follow the traversal's own GraphStep, and an edge hop's outE()
        // always sits between. So the range comparisons here always take the per-record type guard.
        var filter = GremlinPredicateAdapter.INSTANCE.toFilter(
            container, typeGate, paramSink, /* rangeTypeGuard= */ true);
        if (filter == null) {
          return Outcome.DECLINE;
        }
        edgeFilters.add(filter);
      }
    }

    // Contribute: all has(...) predicates validated and translated, so now it is safe to mint
    // aliases and bind user labels. If binding fails, it declines the whole traversal without
    // leaving partially contributed alias counters.
    var edgeAlias = ctx.nextEdgeAlias();
    var targetAlias = ctx.nextAnonVertexAlias();

    if (!ctx.bindStepLabels(edgeStep, edgeAlias)) {
      return Outcome.DECLINE;
    }
    for (HasStep<?> has : hasSteps) {
      if (!ctx.bindStepLabels(has, edgeAlias)) {
        return Outcome.DECLINE;
      }
    }
    if (!ctx.bindStepLabels(closingStep, targetAlias)) {
      return Outcome.DECLINE;
    }

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

    if (closingStep instanceof EdgeOtherVertexStep
        && edgeDirection == MatchPatternBuilder.Direction.BOTH) {
      var targetRid = WHERE.boundaryRidExpression();
      var sourceRid = WHERE.matchedAccess(fromAlias, "@rid");
      var excludeSource =
          WHERE.compareExpressions(targetRid, new SQLNeOperator(-1), sourceRid);
      ctx.putAliasFilter(targetAlias, WHERE.wrap(excludeSource));
    }

    GremlinPatternAssembler.appendEdgeAsNode(
        ctx,
        fromAlias,
        edgeAlias,
        targetAlias,
        edgeDirection,
        edgeLabel,
        closingVertexDir,
        edgeWhere);
    return Outcome.ACCEPTED;
  }
}
