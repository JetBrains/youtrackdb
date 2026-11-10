package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.FoldListShapingOp;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ReverseListShapingOp;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.NativeStartEvidence;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanClassifier;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PlainPlanDecision.Reason;

/** Pure boundary analysis. Reads carrier shape, output type and every ResultShaping component. */
final class PlainBoundaryClassifier {

  private PlainBoundaryClassifier() {
  }

  static PlainPlanDecision classify(InternalExecutionPlan plan,
      GremlinToMatchTranslator.TranslationResult translation) {
    return classify(plan, translation, null, null, false);
  }

  static PlainPlanDecision classify(InternalExecutionPlan plan,
      GremlinToMatchTranslator.TranslationResult translation,
      NativeStartEvidence evidence, CommandContext bindings,
      boolean candidate) {
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
    boolean neutral = operations.stream().allMatch(op -> op.getClass()
        == FoldListShapingOp.class
        || op.getClass()
            == ReverseListShapingOp.class)
        && operations.stream().filter(op -> op.getClass() == FoldListShapingOp.class).count() <= 1;
    // Reverse is an exact per-payload native map using the same asList helper. It preserves
    // stream order and multiplicity, including around the single already-audited fold barrier.
    if (!neutral) {
      return PlainPlanDecision.rejected(Reason.BOUNDARY_OPERATION, operations.getFirst());
    }
    return candidate ? PlainPlanClassifier.indexCandidate(plan, translation.inputs())
        : PlainPlanClassifier.classify(plan, translation.inputs(), evidence, bindings);
  }
}
