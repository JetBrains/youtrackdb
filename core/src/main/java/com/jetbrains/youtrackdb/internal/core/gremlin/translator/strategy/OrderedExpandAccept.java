package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedExpandSliceListShapingOp;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Shared accept path for ordered-expand list-shaping: MATCH keeps sorted sources (with optional
 * statement {@code LIMIT}/{@code SKIP}), and the hop (+ optional neighbour {@code has} / {@code
 * values}) runs as {@link OrderedExpandSliceListShapingOp}.
 */
final class OrderedExpandAccept {

  private OrderedExpandAccept() {
    // Static helper — no instances.
  }

  /**
   * Walker / hop context for {@code order().limit|skip|range} then expand: statement top-N already
   * cuts sources; the hop must not join into MATCH.
   */
  static boolean hasOrderedSourceSliceForExpand(RecognitionContext ctx) {
    if (ctx.orderBy() == null || !ctx.orderAllowsSliceOnCurrentBoundary()) {
      return false;
    }
    if (ctx.pendingOrderedHop() != null) {
      return false;
    }
    if (ctx.limit() == null && ctx.skip() == null) {
      return false;
    }
    return ctx.supportsListShaping();
  }

  /**
   * Whether the walker's cardinality gate may admit a vertex hop after an ordered source slice.
   * Registry dispatch uses {@link VertexStepRecogniser}, which routes to {@link
   * VertexHopRecogniser}.
   */
  static boolean isOrderedSourceSliceThenHop(
      RecognitionContext ctx, StepRecogniser recogniser, Object head) {
    if (recogniser != VertexStepRecogniser.INSTANCE) {
      return false;
    }
    if (!(head instanceof VertexStepContract<?> hop) || hop.returnsEdge()) {
      return false;
    }
    return hasOrderedSourceSliceForExpand(ctx);
  }

  /**
   * Appends an expand-only ordered-expand stage after a statement source slice. Optional trailing
   * {@code has(...)} and {@code values(key)} are consumed here (list-shaping drain latch).
   */
  static Outcome acceptExpandAfterSourceSlice(
      StepCursor cursor, VertexStepContract<?> hop, RecognitionContext ctx) {
    if (ctx.groupBy() != null || !ctx.supportsListShaping()) {
      return Outcome.DECLINE;
    }
    var fromAlias = ctx.boundaryAlias();
    if (fromAlias == null) {
      return Outcome.DECLINE;
    }
    var arity = GremlinPatternAssembler.resolveEdgeLabel(hop, ctx);
    if (!arity.translatable()) {
      return Outcome.DECLINE;
    }
    // Neighbours are not MATCH aliases — a labelled hop after source slice has no select target.
    if (!hop.getLabels().isEmpty()) {
      return Outcome.DECLINE;
    }
    var hasContainers = new ArrayList<HasContainer>();
    while (true) {
      var next = cursor.peek();
      if (!(next instanceof HasStep<?> hasStep)) {
        break;
      }
      if (!hasStep.getLabels().isEmpty()) {
        return Outcome.DECLINE;
      }
      var collected = HasStepRecogniser.collectDeferredHasContainers(hasStep, ctx);
      if (collected == null) {
        return Outcome.DECLINE;
      }
      cursor.take();
      hasContainers.addAll(collected);
    }
    String propertyKey = null;
    var next = cursor.peek();
    if (next instanceof PropertiesStep<?> properties
        && properties.getReturnType() == PropertyType.VALUE) {
      var keys = properties.getPropertyKeys();
      if (keys.length == 1 && keys[0] != null && !keys[0].isBlank()
          && !WalkerContext.isReservedHasKey(keys[0])) {
        cursor.take();
        propertyKey = keys[0];
      }
    }
    // RETURN already sources; re-pin boundary for ELEMENT projection into the expand stage.
    ctx.pinBoundary(fromAlias, BoundaryOutputType.ELEMENT, Vertex.class);
    // skip/limit stay on the statement (source top-N); the op only expands.
    ctx.appendListShapingOp(
        new OrderedExpandSliceListShapingOp(
            hop.getDirection(),
            arity.label(),
            /* skip= */ 0,
            /* limit= */ -1,
            propertyKey,
            List.copyOf(hasContainers)));
    return Outcome.ACCEPTED;
  }
}
