package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedExpandSliceListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.OrderedHopStage;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStepContract;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Shared accept path for ordered-expand list-shaping: MATCH keeps sorted sources, and the native
 * source slice, hop, optional neighbour {@code has} and {@code values} run in order in {@link
 * OrderedExpandSliceListShapingOp}.
 */
final class OrderedExpandAccept {

  private OrderedExpandAccept() {
    // Static helper — no instances.
  }

  /**
   * Walker / hop context for {@code order().limit|skip|range} then expand: the source slice is
   * recorded for transfer into the op, so the hop must not join into MATCH.
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
   * Appends source slice, projection, expand, filter and barrier stages in native order.
   * Optional trailing {@code has(...)} and {@code values(key)} are consumed here.
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
    var sourceProjection = sourceProjection(ctx, fromAlias);
    if (sourceProjection == SourceProjection.UNSUPPORTED) {
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
    var stages = new ArrayList<OrderedHopStage>();
    if (!(ctx instanceof WalkerContext walker)) {
      return Outcome.DECLINE;
    }
    stages.add(walker.orderedSourceMerge(fromAlias));
    var sourceSlice = walker.takeOrderedSourceSlice();
    if (sourceSlice == null) {
      return Outcome.DECLINE;
    }
    // MATCH cannot cut unmerged source rows. Range runs before select().by(), including
    // nonproductive select rows, so projection stays lazy until after the slice. Barriers
    // skipped after select belong after both the slice and the projection.
    stages.add(sourceSlice);
    stages.add(new OrderedHopStage.Project(walker.orderedProjectionSplits()));
    stages.addAll(walker.takeOrderedSourceBarriers());
    stages.add(new OrderedHopStage.Expand(hop.getDirection(), arity.labels()));
    String effectiveClass = WalkerContext.VERTEX_ROOT_CLASS;
    while (true) {
      var next = cursor.peek();
      stages.addAll(takeBarrierStages(cursor, ctx));
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
      // Same YTDB-1369 guard as recognizePendingOrderedHopHas: a folded single+multi hasLabel
      // step must not become one NeighbourFilter that ORs the AND conditions.
      if (HasStepRecogniser.conflictingLabelContainers(collected)) {
        return Outcome.DECLINE;
      }
      var contribution = HasStepRecogniser.prepareDeferred(
          ctx, collected, effectiveClass, 0);
      if (contribution == null) {
        return Outcome.DECLINE;
      }
      if (!contribution.cacheSafe()) {
        ctx.markRidBearing();
      }
      cursor.take();
      ctx.recordHasBinding(contribution.bindingContext(), contribution.slots());
      effectiveClass = contribution.effectiveClass();
      stages.add(new OrderedHopStage.Filter(contribution.nativeContainers(), ctx.polymorphic()));
    }
    String propertyKey = takeValuesKey(cursor);
    stages.addAll(takeBarrierStages(cursor, ctx));
    if (propertyKey != null) {
      stages.add(new OrderedHopStage.Values(propertyKey));
    }
    cursor.peek();
    stages.addAll(takeBarrierStages(cursor, ctx));
    // Bare select unwraps to a Vertex. Other selects must retain their projected scalar or map:
    // the expand stage performs VertexStep's cast after any nonproductive rows have been dropped.
    restoreSourceProjection(ctx, fromAlias, sourceProjection);
    installSourceCarrier(ctx, fromAlias);
    ctx.setSkip(null);
    ctx.setLimit(null);
    ctx.appendListShapingOp(new OrderedExpandSliceListShapingOp(stages));
    return Outcome.ACCEPTED;
  }

  /** Add hidden element columns after restoring the source projection; they never enter its map. */
  static void installSourceCarrier(RecognitionContext ctx, String sourceAlias) {
    if (!(ctx instanceof WalkerContext walker)) {
      return;
    }
    var columns = new ArrayList<String>();
    int index = 0;
    for (String alias : walker.orderedSourceAliases(sourceAlias)) {
      String column = "$g2m_pe_os_" + index++;
      ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(alias), column);
      columns.add(column);
    }
    ctx.setResultShaping(walker.shaping().withOrderedSourceKeyColumns(columns));
  }

  static List<OrderedHopStage.Barrier> takeBarrierStages(
      StepCursor cursor, RecognitionContext ctx) {
    var stages = new ArrayList<OrderedHopStage.Barrier>();
    for (var skipped : cursor.drainSkippedTransparent()) {
      if (skipped instanceof NoOpBarrierStep<?> barrier) {
        stages.add(ctx instanceof WalkerContext walker
            ? walker.orderedBarrierStage(barrier)
            : new OrderedHopStage.Barrier(barrier.getMaxBarrierSize(),
                ctx.orderedSourceMergeKey(ctx.boundaryAlias())));
      }
    }
    return stages;
  }

  enum SourceProjection {
    ELEMENT, SELECT_PAYLOAD, UNSUPPORTED
  }

  /** Classify the payload before the pending hop re-pins the boundary to its synthetic target. */
  static SourceProjection sourceProjection(RecognitionContext ctx, String alias) {
    if (sourceIsElement(ctx, alias)) {
      return SourceProjection.ELEMENT;
    }
    if (ctx instanceof WalkerContext walker
        && ctx.boundaryOutputType() == BoundaryOutputType.MAP
        && !walker.shaping().mapEmitColumnOrder().isEmpty()
        && walker.shaping().mapEmitColumnOrder().stream()
            .allMatch(label -> ctx.resolveUserLabel(label) != null)) {
      // Modulated select and multi-label select both produce a non-Vertex payload. Keep their
      // projection and presence checks intact so a missing by(key) drops before the hop cast.
      return SourceProjection.SELECT_PAYLOAD;
    }
    return SourceProjection.UNSUPPORTED;
  }

  static boolean sourceIsElement(RecognitionContext ctx, String alias) {
    if (ctx.boundaryOutputType() == BoundaryOutputType.ELEMENT) {
      return true;
    }
    if (ctx instanceof WalkerContext walker) {
      var shaping = walker.shaping();
      if (ctx.boundaryOutputType() != BoundaryOutputType.MAP
          || !shaping.unwrapSingletonMap()
          || shaping.dropOnAbsent()
          || !shaping.aliasPropertyPresences().isEmpty()
          || !shaping.mapEmitColumnOrder().isEmpty()
          || walker.returnItems.size() != 1
          || !walker.returnItems.getFirst().toString()
              .equals(MatchProjectionBuilder.aliasColumn(alias).toString())) {
        return false;
      }
      // The sole projected cell must name a path label bound to this sorted source.
      return walker.userLabelToAlias.entrySet().stream()
          .anyMatch(entry -> alias.equals(entry.getValue())
              && walker.returnAliases.getFirst().toString()
                  .equals(MatchProjectionBuilder.columnAlias(entry.getKey()).toString()));
    }
    return false;
  }

  static void restoreSourceProjection(
      RecognitionContext ctx, String alias, SourceProjection projection) {
    if (projection == SourceProjection.SELECT_PAYLOAD) {
      ctx.pinBoundary(alias, BoundaryOutputType.MAP, Vertex.class);
      return;
    }
    repinSourceElement(ctx, alias);
  }

  static void repinSourceElement(RecognitionContext ctx, String alias) {
    ctx.setSingleReturnColumn(alias);
    if (ctx instanceof WalkerContext walker) {
      ctx.setResultShaping(
          ResultShaping.NONE.withListShapingOps(walker.shaping().listShapingOps()));
    }
    ctx.pinBoundary(alias, BoundaryOutputType.ELEMENT, Vertex.class);
  }

  /** Consume only a single ordinary values(key); leave all other projections for the walker gate. */
  static @Nullable String takeValuesKey(StepCursor cursor) {
    if (cursor.peek() instanceof PropertiesStep<?> properties
        && properties.getReturnType() == PropertyType.VALUE) {
      var keys = properties.getPropertyKeys();
      if (keys.length == 1 && keys[0] != null && !keys[0].isBlank()
          && !WalkerContext.isReservedHasKey(keys[0])) {
        cursor.take();
        return keys[0];
      }
    }
    return null;
  }
}
