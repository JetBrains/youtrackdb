package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Factors the pattern-assembly a vertex-hop recogniser performs after it has validated a step and
 * minted its aliases: append the edge + target node to the pattern, then re-pin the boundary and the
 * single RETURN column to the new target. Both the folded bare hop ({@code out(L)}) and the
 * non-adjacent edge-as-node form ({@code outE(L){filter}.inV()}) share this tail, so it lives in one
 * place rather than being duplicated across the recognisers. Every contribution goes through the
 * narrow {@link RecognitionContext}, so the assembler cannot reach the pattern builder or the
 * traversal directly.
 *
 * <h2>Bare hop targets root at {@code V} — no {@code @class} narrowing</h2>
 *
 * Every hop target is registered with the generic vertex class {@code V} and no {@code @class} filter,
 * regardless of {@link RecognitionContext#polymorphic()}. Native Gremlin never class-filters a hop
 * target, so narrowing one — even under {@code polymorphic=false} — would drop subclass instances the
 * native pipeline keeps. {@code @class} narrowing is reserved for an explicit user-named class (the
 * folded {@code hasLabel}, a later track) via {@code MatchWhereBuilder.classEquals}, never here. This
 * mirrors {@link StartStepRecogniser}'s treatment of the start node.
 *
 * <h2>Boundary / RETURN re-pin</h2>
 *
 * A chain hop makes the <em>target</em> the traversal's result, so the assembler replaces the single
 * RETURN column (and re-pins {@link RecognitionContext#boundaryAlias()}) with the new target alias,
 * leaving exactly one column keyed on the last hop's target. The output stays an {@code ELEMENT} /
 * {@code Vertex} because every hop yields vertices.
 */
final class GremlinPatternAssembler {

  private GremlinPatternAssembler() {
    // Static helper — no instances.
  }

  /**
   * Appends a folded bare hop {@code fromAlias --dir(edgeLabel)--> targetAlias} (no edge filter — the
   * folded case cannot carry one), registers the target under the generic {@code V} class, and re-pins
   * the boundary / RETURN to the target. Used by {@link VertexHopRecogniser}.
   */
  static void appendFoldedHop(
      RecognitionContext ctx,
      String fromAlias,
      String targetAlias,
      MatchPatternBuilder.Direction dir,
      String edgeLabel) {
    ctx.addEdge(fromAlias, targetAlias, dir, edgeLabel);
    ctx.addNode(targetAlias, WalkerContext.VERTEX_ROOT_CLASS);
    rePinBoundaryToTarget(ctx, targetAlias);
  }

  /**
   * Appends the edge-as-node form {@code fromAlias --<edgeDir>E(edgeLabel){as: edgeAlias, where:
   * edgeFilter}--> edgeAlias --<closingVertexDir>V(){as: targetAlias}--> targetAlias}, registers the
   * target under the generic {@code V} class, and re-pins the boundary / RETURN to the target. Used by
   * {@link EdgeHopRecogniser}. The edge filter (if any) travels on the edge path item, so the predicate
   * filters the edge rather than the target vertex.
   */
  static void appendEdgeAsNode(
      RecognitionContext ctx,
      String fromAlias,
      String edgeAlias,
      String targetAlias,
      MatchPatternBuilder.Direction edgeDir,
      String edgeLabel,
      MatchPatternBuilder.Direction closingVertexDir,
      SQLWhereClause edgeFilter) {
    ctx.addEdgeAsNode(
        fromAlias, edgeAlias, targetAlias, edgeDir, edgeLabel, closingVertexDir, edgeFilter);
    ctx.addNode(targetAlias, WalkerContext.VERTEX_ROOT_CLASS);
    rePinBoundaryToTarget(ctx, targetAlias);
  }

  /**
   * Maps a TinkerPop {@link Direction} onto the pattern builder's edge direction. A vertex/edge hop
   * only ever carries the three proper directions {@code OUT} / {@code IN} / {@code BOTH} ({@code
   * Direction.from} / {@code Direction.to} are aliases for {@code OUT} / {@code IN}, not separate
   * constants), so the switch is exhaustive with no default. Should the fork ever add a direction
   * constant, this stops compiling — a loud, correct signal — rather than silently mistranslating.
   * Shared by {@link VertexHopRecogniser} (hop direction) and {@link EdgeHopRecogniser} (edge and
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
   * Resolves the Phase 1 edge-label arity of a hop's {@link VertexStepContract}, applying one rule
   * shared by the bare hop ({@link VertexHopRecogniser}) and the edge-filter chain ({@link
   * EdgeHopRecogniser}): a single named label translates; a multi-label hop or a blank single label
   * declines; a label-less hop (all edge types) translates unless the traversal opts into {@code
   * EdgeLabelVerificationStrategy} (read from {@link RecognitionContext#edgeLabelVerificationEnabled()},
   * resolved once by the walker). Centralising the rule keeps the two hop kinds from drifting. A
   * translatable label-less hop yields a {@code null} label, which the builders render as the all-types
   * {@code out('E')} / bare {@code outE()} form.
   *
   * <p>The {@code EdgeLabelVerificationStrategy} carve-out preserves transparency: that opt-in strategy
   * exists to reject a label-less hop, so translating one into a boundary step would remove it before
   * the verification runs and silently swallow the error the user asked for. Declining leaves the
   * native {@code VertexStep} for the strategy to reject.
   */
  static EdgeLabelArity resolveEdgeLabel(VertexStepContract<?> step, RecognitionContext ctx) {
    var labels = step.getEdgeLabels();
    if (labels.length > 1) {
      // Multi-label edge traversal is out of scope for Phase 1: addEdge / the edge-as-node builder
      // carry a single edge label, with no multi-label / IN-list slot.
      return EdgeLabelArity.DECLINE;
    }
    if (labels.length == 1) {
      var label = labels[0];
      if (label == null || label.isBlank()) {
        // A single blank label (out("")) is degenerate — decline rather than collapse it to the
        // all-types form.
        return EdgeLabelArity.DECLINE;
      }
      return new EdgeLabelArity(true, label);
    }
    // Label-less (length 0): all edge types. Decline when the traversal opts into
    // EdgeLabelVerificationStrategy — translating the hop away would suppress the label-less error that
    // strategy must raise (see the Javadoc). Otherwise translate: a null label the builders render as
    // the all-types out('E') / bare outE() form.
    if (ctx.edgeLabelVerificationEnabled()) {
      return EdgeLabelArity.DECLINE;
    }
    return new EdgeLabelArity(true, null);
  }

  /**
   * Outcome of {@link #resolveEdgeLabel}: whether the hop translates and, if so, its single edge label
   * ({@code null} for a label-less all-types hop). A declined result carries a {@code null} label that
   * callers must not read — they return their own decline first.
   */
  record EdgeLabelArity(boolean translatable, String label) {

    /** The shared decline result: a multi-label hop or a blank single label. */
    static final EdgeLabelArity DECLINE = new EdgeLabelArity(false, null);
  }

  /**
   * Re-pins the boundary metadata and replaces the single RETURN column so the result is the new
   * {@code targetAlias} vertex.
   */
  private static void rePinBoundaryToTarget(RecognitionContext ctx, String targetAlias) {
    ctx.pinBoundary(targetAlias, BoundaryOutputType.ELEMENT, Vertex.class);
    ctx.setSingleReturnColumn(targetAlias);
  }
}
