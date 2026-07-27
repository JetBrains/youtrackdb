package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.MaxGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MeanGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.MinGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SumGlobalStep;

/**
 * Recogniser for property-reducing aggregates ({@code sum}/{@code min}/{@code max}/{@code mean})
 * that re-point at {@link RecognitionContext#lastPropertyProjection()} from a preceding {@code
 * values(key)}.
 */
final class PropertyAggregateStepRecogniser implements StepRecogniser {

  static final PropertyAggregateStepRecogniser INSTANCE = new PropertyAggregateStepRecogniser();

  private PropertyAggregateStepRecogniser() {
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    var function =
        switch (step) {
          case SumGlobalStep<?> ignored -> "sum";
          case MinGlobalStep<?> ignored -> "min";
          case MaxGlobalStep<?> ignored -> "max";
          // Gremlin mean maps to the YTDB SQL aggregate "avg" (there is no "mean" SQL function, so
          // emitting mean(...) would fail plan-build and decline the whole traversal to native).
          case MeanGlobalStep<?, ?> ignored -> "avg";
          default -> null;
        };
    if (function == null) {
      return Outcome.DECLINE;
    }
    return GremlinAggregateAssembler.configurePropertyAggregate(ctx, function);
  }
}
