package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;

/**
 * Recogniser for {@link CountGlobalStep}: {@code count()} → {@code RETURN count(*)} with {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#SCALAR}. MATCH
 * short-circuit maps unfiltered patterns to polymorphic {@code CountFromClassStep} and exact
 * {@code @class} filters (non-poly {@code hasLabel}) to leaf-exact counts.
 */
final class CountGlobalStepRecogniser implements StepRecogniser {

  static final CountGlobalStepRecogniser INSTANCE = new CountGlobalStepRecogniser();

  private CountGlobalStepRecogniser() {
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof CountGlobalStep<?>)) {
      return Outcome.DECLINE;
    }
    return GremlinAggregateAssembler.configureCount(ctx);
  }
}
