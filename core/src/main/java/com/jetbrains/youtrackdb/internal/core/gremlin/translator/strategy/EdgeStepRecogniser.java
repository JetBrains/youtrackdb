package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.structure.Direction;

/**
 * Recogniser for the non-adjacent edge-filter chain {@code outE(L).has(edgeProp).inV()} (and the
 * {@code inE(L).has(...).outV()} analogue). A {@code has(...)} between the edge step and its closing
 * vertex hop stops {@code IncidentToAdjacentStrategy} from folding the chain to a bare {@code
 * out(L)}, so it arrives as three separate steps: an edge-returning {@link VertexStep} ({@code
 * returnsEdge() == true}), one or more {@link HasStep}s, and a closing {@link EdgeVertexStep}. This
 * shape is common — LDBC IC2 filters {@code knows} edges by creation date.
 *
 * <h2>Reached by delegation, not registered directly</h2>
 *
 * The edge step is a {@link VertexStep}, the same registry class as a bare hop. The registry keys one
 * recogniser per class, so {@link VertexStepRecogniser} owns {@code VertexStep.class} and delegates
 * here on its {@code returnsEdge() == true} branch. This recogniser is never in the walker registry.
 *
 * <h2>Edge-as-node — the only IR form that can filter an edge</h2>
 *
 * {@code MatchPatternBuilder.addEdge}'s {@code edgeFilter} lands on the hop's <em>target-vertex</em>
 * filter, so a single {@code out(L)} path item cannot filter edge properties. The MATCH IR expresses
 * an edge filter only by node-izing the edge: the two-path-item {@code outE(L){as: $g2m_edge_N,
 * where: <edge WHERE>}.inV(){as: $g2m_anon_M}} form, which the executor already runs (see {@code
 * MatchEdgeMethod*Test}) and {@link GremlinPatternAssembler#appendEdgeAsNode} now emits.
 *
 * <h2>Peek-ahead and the all-or-nothing decline</h2>
 *
 * From the edge step, the recogniser peeks forward: it AND-merges successive {@link HasStep}
 * predicates into a single edge {@code WHERE} through {@link GremlinPredicateAdapter}, skips any
 * interleaved {@link NoOpBarrierStep} (belt-and-suspenders — {@code LazyBarrierStrategy}'s {@code
 * returnsEdge()} carve-out keeps a barrier out of this window in practice), and closes on an {@link
 * EdgeVertexStep}. It declines the whole traversal — mutating nothing, minting no alias, advancing no
 * cursor — when:
 *
 * <ul>
 *   <li>the edge step is not a single-label edge-returning {@code VertexStep}, or carries a user
 *       {@code as(...)} label (a named edge is out of scope);
 *   <li>a {@code has(...)} predicate is one {@link GremlinPredicateAdapter} cannot translate;
 *   <li>a step other than {@code HasStep} / {@code NoOpBarrierStep} sits between the edge and its
 *       close;
 *   <li>the closing hop is an {@code EdgeOtherVertexStep} ({@code otherV}) — the MATCH executor has
 *       no {@code otherV} method, so a {@code bothE(L).has(...).otherV()} chain cannot be expressed
 *       and stays native (plain {@code both(L)} without an edge filter still translates via {@link
 *       VertexStepRecogniser});
 *   <li>there is no closing hop at all (an edge-returning terminal — out of scope).
 * </ul>
 *
 * <h2>No-mutation-on-decline</h2>
 *
 * Every validation and the whole peek-ahead run before any context mutation: aliases are minted, the
 * pattern is assembled, {@link WalkerContext#edgeFilters} is populated, and {@link
 * WalkerContext#stepIndex} is advanced only once the closing hop is confirmed. A decline therefore
 * leaves {@code ctx} exactly as it was found, as the {@link StepRecogniser} contract requires.
 */
final class EdgeStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final EdgeStepRecogniser INSTANCE = new EdgeStepRecogniser();

  /** Stateless builder for the AND-merge and where-clause wrap; construction is trivial. */
  private static final MatchWhereBuilder WHERE = new MatchWhereBuilder();

  private EdgeStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public int recognize(Step<?, ?> step, WalkerContext ctx) {
    // The delegating VertexStepRecogniser only routes an edge-returning VertexStep here, but
    // re-assert both facts so a direct call declines cleanly rather than mis-translating.
    if (!(step instanceof VertexStep<?> edgeStep) || !edgeStep.returnsEdge()) {
      return 0;
    }
    // A hop with no boundary to hang off cannot be translated: the "from" endpoint is the current
    // terminator's alias. A null here would mean an edge step reached the walker before any node was
    // pinned — decline defensively rather than build a dangling edge.
    if (ctx.boundaryAlias == null) {
      return 0;
    }
    // A user as(...) label on the edge step would expose the edge as a named result — out of scope.
    // Decline. (The walker's reserved-prefix pre-flight already declined any $-prefixed label.)
    if (!edgeStep.getLabels().isEmpty()) {
      return 0;
    }
    // Only a single-label edge is in scope, mirroring the folded bare-hop rule. A label-less edge
    // (outE(), all types) and a multi-label edge (outE("a", "b")) both decline.
    var edgeLabels = edgeStep.getEdgeLabels();
    if (edgeLabels.length != 1) {
      return 0;
    }
    var edgeLabel = edgeLabels[0];
    if (edgeLabel == null || edgeLabel.isBlank()) {
      return 0;
    }

    // Peek forward from the step after the edge: AND-merge has() predicates, skip barriers, and stop
    // at the closing vertex hop. Nothing here mutates ctx — a decline at any point leaves it clean.
    var steps = ctx.traversal.getSteps();
    var edgeFilters = new ArrayList<SQLBooleanExpression>();
    Direction closingDirection = null;
    var startIndex = ctx.stepIndex;
    var probe = startIndex + 1;
    while (probe < steps.size()) {
      var next = steps.get(probe);
      if (next instanceof NoOpBarrierStep<?>) {
        // Belt-and-suspenders: skip an interleaved barrier without consuming it as a filter.
        probe++;
        continue;
      }
      if (next instanceof HasStep<?> hasStep) {
        for (var container : hasStep.getHasContainers()) {
          var filter = GremlinPredicateAdapter.INSTANCE.toFilter(container);
          if (filter == null) {
            // A predicate the adapter cannot translate declines the whole traversal — no half-applied
            // edge filter that would silently under- or over-match versus native.
            return 0;
          }
          edgeFilters.add(filter);
        }
        probe++;
        continue;
      }
      if (next instanceof EdgeVertexStep closing) {
        // Closing hop for inV()/outV()/bothV() — capture its direction and stop.
        closingDirection = closing.getDirection();
        probe++;
        break;
      }
      // Any other step between the edge and its close declines. This includes EdgeOtherVertexStep
      // (otherV): the MATCH executor has no otherV method, so a bothE(L).has(...).otherV() chain
      // cannot be expressed and must stay on the native pipeline.
      return 0;
    }
    if (closingDirection == null) {
      // Ran off the end with no closing vertex hop — an edge-returning terminal, out of scope.
      return 0;
    }

    // Validation and peek-ahead done; commit mutations. Mint the edge alias then the target alias so
    // a multi-hop chain gets distinct intermediate names.
    var fromAlias = ctx.boundaryAlias;
    var edgeAlias = ctx.nextEdgeAlias();
    var targetAlias = ctx.nextAnonVertexAlias();

    // AND-merge the accumulated predicates into one edge WHERE (null when the edge is unfiltered, e.g.
    // an outE(L).barrier().inV() chain that never folded). Record it under the edge alias so the
    // accumulation is observable, and hand the same clause to the assembler, which puts it on the
    // edge path item so it filters the edge rather than the target vertex.
    SQLWhereClause edgeWhere = null;
    var merged = WHERE.andOptional(edgeFilters.toArray(new SQLBooleanExpression[0]));
    if (merged != null) {
      edgeWhere = WHERE.wrap(merged);
      ctx.edgeFilters.put(edgeAlias, edgeWhere);
    }

    var edgeDirection = GremlinPatternAssembler.toBuilderDirection(edgeStep.getDirection());
    var closingVertexDirection = GremlinPatternAssembler.toBuilderDirection(closingDirection);
    GremlinPatternAssembler.appendEdgeAsNode(
        ctx, fromAlias, edgeAlias, targetAlias, edgeDirection, edgeLabel, closingVertexDirection,
        edgeWhere);

    // Report every step this chain consumed: the edge step, the interleaved has()/barrier steps,
    // and the closing vertex hop. `probe` sits one past the closing hop, so the count is that span
    // from the edge step's index; the walker advances the cursor by it.
    return probe - startIndex;
  }
}
