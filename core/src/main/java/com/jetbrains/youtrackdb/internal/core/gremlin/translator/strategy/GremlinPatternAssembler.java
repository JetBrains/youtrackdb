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
   * Appends a folded bare hop {@code fromAlias --dir(edgeLabels)--> targetAlias} (no edge filter — the
   * folded case cannot carry one), registers the target under the generic {@code V} class, and re-pins
   * the boundary / RETURN to the target. Used by {@link VertexHopRecogniser} and {@link
   * CombinatorFoldedHopRecogniser}.
   */
  static void appendFoldedHop(
      RecognitionContext ctx,
      String fromAlias,
      String targetAlias,
      MatchPatternBuilder.Direction dir,
      String[] edgeLabels) {
    ctx.addEdge(fromAlias, targetAlias, dir, edgeLabels);
    ctx.addNode(targetAlias, WalkerContext.VERTEX_ROOT_CLASS);
    rePinBoundaryToTarget(ctx, targetAlias);
  }

  /**
   * Validates boundary + edge-label arity and appends one folded bare hop for {@code hop}. Shared by
   * {@link VertexHopRecogniser} and {@link CombinatorFoldedHopRecogniser} after each handler has
   * asserted its own step preconditions.
   */
  static Outcome claimFoldedHop(VertexStepContract<?> hop, RecognitionContext ctx) {
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    var arity = resolveEdgeLabel(hop, ctx);
    if (!arity.translatable()) {
      return Outcome.DECLINE;
    }
    var fromAlias = ctx.boundaryAlias();
    var targetAlias = ctx.nextAnonVertexAlias();
    appendFoldedHop(
        ctx, fromAlias, targetAlias, toBuilderDirection(hop.getDirection()), arity.labels());
    if (!ctx.bindStepLabels(hop, targetAlias)) {
      return Outcome.DECLINE;
    }
    return Outcome.ACCEPTED;
  }

  /**
   * Appends the edge-as-node form {@code fromAlias --<edgeDir>E(edgeLabels){as: edgeAlias, where:
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
      String[] edgeLabels,
      MatchPatternBuilder.Direction closingVertexDir,
      SQLWhereClause edgeFilter) {
    ctx.addEdgeAsNode(
        fromAlias, edgeAlias, targetAlias, edgeDir, edgeLabels, closingVertexDir, edgeFilter);
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
   * Resolves the edge-label arity of a hop's {@link VertexStepContract}, applying one rule shared by
   * the bare hop ({@link VertexHopRecogniser}), the combinator fold artifact ({@link
   * CombinatorFoldedHopRecogniser}), and the edge-filter chain ({@link EdgeHopRecogniser}): one or more
   * non-blank named labels translate; a blank single label declines; a label-less hop (all edge types)
   * translates unless the traversal opts into {@code EdgeLabelVerificationStrategy} (read from
   * {@link RecognitionContext#edgeLabelVerificationEnabled()}, resolved once by the walker). A
   * translatable label-less hop yields a {@code null} labels array, which the builders render as the
   * all-types {@code out('E')} / bare {@code outE()} form.
   *
   * <p>The {@code EdgeLabelVerificationStrategy} carve-out preserves transparency: that opt-in strategy
   * exists to reject a label-less hop, so translating one into a boundary step would remove it before
   * the verification runs and silently swallow the error the user asked for. Declining leaves the
   * native {@code VertexStep} for the strategy to reject.
   */
  static EdgeLabelArity resolveEdgeLabel(VertexStepContract<?> step, RecognitionContext ctx) {
    var labels = step.getEdgeLabels();
    if (labels.length >= 1) {
      for (var label : labels) {
        if (label == null || label.isBlank()) {
          // A blank label (out("") or out("knows", "")) is degenerate — decline rather than collapse
          // it to the all-types form or drop the blank slot.
          return EdgeLabelArity.DECLINE;
        }
      }
      return new EdgeLabelArity(true, labels.clone());
    }
    // Label-less (length 0): all edge types. Decline when the traversal opts into
    // EdgeLabelVerificationStrategy — translating the hop away would suppress the label-less error that
    // strategy must raise (see the Javadoc). Otherwise translate: a null labels array the builders
    // render as the all-types out('E') / bare outE() form.
    if (ctx.edgeLabelVerificationEnabled()) {
      return EdgeLabelArity.DECLINE;
    }
    return new EdgeLabelArity(true, null);
  }

  /**
   * Outcome of {@link #resolveEdgeLabel}: whether the hop translates and, if so, its edge labels
   * ({@code null} for a label-less all-types hop; otherwise one or more non-blank labels). A declined
   * result carries a {@code null} labels array that callers must not read — they return their own
   * decline first.
   */
  record EdgeLabelArity(boolean translatable, String[] labels) {

    /** The shared decline result: a blank label slot or an EdgeLabelVerification block. */
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
