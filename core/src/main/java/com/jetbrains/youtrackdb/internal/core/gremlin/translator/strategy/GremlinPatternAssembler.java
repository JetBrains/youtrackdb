package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Factors the pattern-assembly a vertex-hop recogniser performs after it has validated a step and
 * minted its aliases: append the edge + target node to the pattern builder, then re-pin the
 * boundary and the single RETURN column to the new target. Both the folded bare hop ({@code
 * out(L)}) and the non-adjacent edge-as-node form ({@code outE(L){filter}.inV()}) share this tail,
 * so it lives in one place rather than being duplicated across the recognisers.
 *
 * <h2>Bare hop targets root at {@code V} — no {@code @class} narrowing</h2>
 *
 * Every hop target is registered with the generic vertex class {@code V} and no {@code @class}
 * filter, regardless of {@link WalkerContext#polymorphic}. Native Gremlin never class-filters a hop
 * target, so narrowing one — even under {@code polymorphic=false} — would drop subclass instances
 * the native pipeline keeps (a subclass undercount). {@code @class} narrowing is reserved for an
 * explicit user-named class (the folded {@code hasLabel}, a later track) via {@link
 * MatchClassFilters}, never here. This mirrors {@link StartStepRecogniser}'s treatment of the start
 * node.
 *
 * <h2>Boundary / RETURN re-pin</h2>
 *
 * A chain hop makes the <em>target</em> the traversal's result, so the assembler replaces the single
 * RETURN column (and re-pins {@link WalkerContext#boundaryAlias}) with the new target alias, leaving
 * exactly one column keyed on the last hop's target. The three parallel return lists (item / alias /
 * nested projection) stay in lock-step. The output stays an {@code ELEMENT} / {@code Vertex} because
 * every hop yields vertices.
 */
final class GremlinPatternAssembler {

  /**
   * Class attached to every hop target: the abstract vertex root. A hop target roots at the generic
   * {@code V} class polymorphically (subclasses included) with no {@code @class} filter — see the
   * class Javadoc "Bare hop targets root at {@code V}".
   */
  private static final String VERTEX_ROOT_CLASS = "V";

  private GremlinPatternAssembler() {
    // Static helper — no instances.
  }

  /**
   * Appends a folded bare hop {@code fromAlias --dir(edgeLabel)--> targetAlias} (no edge filter — the
   * folded case cannot carry one), registers the target under the generic {@code V} class, and
   * re-pins the boundary / RETURN to the target. Used by {@link VertexStepRecogniser}.
   */
  static void appendFoldedHop(
      WalkerContext ctx,
      String fromAlias,
      String targetAlias,
      MatchPatternBuilder.Direction dir,
      String edgeLabel) {
    ctx.patternBuilder.addEdge(fromAlias, targetAlias, dir, edgeLabel, null, null, null);
    ctx.patternBuilder.addNode(targetAlias, VERTEX_ROOT_CLASS, null, false);
    rePinBoundaryToTarget(ctx, targetAlias);
  }

  /**
   * Appends the edge-as-node form {@code fromAlias --<edgeDir>E(edgeLabel){as: edgeAlias, where:
   * edgeFilter}--> edgeAlias --<closingVertexDir>V(){as: targetAlias}--> targetAlias}, registers the
   * target under the generic {@code V} class, and re-pins the boundary / RETURN to the target. Used
   * by {@link EdgeStepRecogniser}. The edge filter (if any) travels on the edge path item, so the
   * predicate filters the edge rather than the target vertex.
   */
  static void appendEdgeAsNode(
      WalkerContext ctx,
      String fromAlias,
      String edgeAlias,
      String targetAlias,
      MatchPatternBuilder.Direction edgeDir,
      String edgeLabel,
      MatchPatternBuilder.Direction closingVertexDir,
      SQLWhereClause edgeFilter) {
    ctx.patternBuilder.addEdgeAsNode(
        fromAlias, edgeAlias, targetAlias, edgeDir, edgeLabel, closingVertexDir, edgeFilter);
    ctx.patternBuilder.addNode(targetAlias, VERTEX_ROOT_CLASS, null, false);
    rePinBoundaryToTarget(ctx, targetAlias);
  }

  /**
   * Maps a TinkerPop {@link Direction} onto the pattern builder's edge direction. A vertex/edge hop
   * only ever carries the three proper directions {@code OUT} / {@code IN} / {@code BOTH} ({@code
   * Direction.from} / {@code Direction.to} are aliases for {@code OUT} / {@code IN}, not separate
   * constants), so the switch is exhaustive with no default. Should the fork ever add a direction
   * constant, this stops compiling — a loud, correct signal — rather than silently mistranslating.
   * Shared by {@link VertexStepRecogniser} (hop direction) and {@link EdgeStepRecogniser} (edge and
   * closing-vertex directions).
   */
  static MatchPatternBuilder.Direction toBuilderDirection(Direction direction) {
    return switch (direction) {
      case OUT -> MatchPatternBuilder.Direction.OUT;
      case IN -> MatchPatternBuilder.Direction.IN;
      case BOTH -> MatchPatternBuilder.Direction.BOTH;
    };
  }

  /**
   * Re-pins the boundary metadata and replaces the single RETURN column so the result is the new
   * {@code targetAlias} vertex. Clears the three parallel return lists first so a naive append cannot
   * leave a stale column keyed on the previous boundary.
   */
  private static void rePinBoundaryToTarget(WalkerContext ctx, String targetAlias) {
    ctx.boundaryAlias = targetAlias;
    ctx.outputType = BoundaryOutputType.ELEMENT;
    ctx.returnClass = Vertex.class;

    ctx.returnItems.clear();
    ctx.returnAliases.clear();
    ctx.returnNestedProjections.clear();
    ctx.returnItems.add(new SQLExpression(new SQLIdentifier(targetAlias)));
    ctx.returnAliases.add(new SQLIdentifier(targetAlias));
    ctx.returnNestedProjections.add(null);
  }
}
