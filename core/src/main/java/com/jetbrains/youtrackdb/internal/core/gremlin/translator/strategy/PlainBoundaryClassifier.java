package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.FoldListShapingOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanClassifier;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.Reason;

/** Pure boundary analysis. Reads carrier shape, output type and every ResultShaping component. */
final class PlainBoundaryClassifier {

  private PlainBoundaryClassifier() {
  }

  static PlainPlanDecision classify(InternalExecutionPlan plan,
      GremlinToMatchTranslator.TranslationResult translation) {
    if (!translation.childPlans().isEmpty() || !translation.postConcatOps().isEmpty()) {
      return PlainPlanDecision.rejected(Reason.MULTI_PLAN, null);
    }
    var shape = translation.shaping();
    if (translation.outputType() == BoundaryOutputType.SCALAR || shape.accumulateMap()
        || shape.emitGroupEntries() || shape.emptyBarrier() || shape.rowDedupAlias() != null
        || shape.propertyGroupCount() != null || !shape.orderedSourceKeyColumns().isEmpty()
        || !shape.aliasPropertyPresences().isEmpty() || !shape.edgeMapKeys().isEmpty()
        || shape.propertyMapBulkingCapacity() < 0) {
      return PlainPlanDecision.rejected(Reason.BOUNDARY_SHAPE, null);
    }
    // Presence reads, map/token flags, emit order and RID keys only shape the current row.
    // Nonnegative propertyMapBulkingCapacity is the audited G6 native bulking window.
    var operations = shape.listShapingOps();
    if (!operations.isEmpty()
        && (operations.size() != 1
            || operations.getFirst().getClass() != FoldListShapingOp.class)) {
      return PlainPlanDecision.rejected(Reason.BOUNDARY_OPERATION, operations.getFirst());
    }
    return PlainPlanClassifier.classify(plan, translation.inputs());
  }
}
