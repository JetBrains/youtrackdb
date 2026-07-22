package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupCountStep;

/**
 * Recogniser for {@link GroupCountStep}: {@code groupCount()} / {@code groupCount().by(key)} →
 * {@code GROUP BY} + {@code count(*)} MAP projection.
 */
final class GroupCountStepRecogniser implements StepRecogniser {

  static final GroupCountStepRecogniser INSTANCE = new GroupCountStepRecogniser();

  private GroupCountStepRecogniser() {
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof GroupCountStep<?, ?> groupCount)) {
      return Outcome.DECLINE;
    }
    var children = groupCount.getLocalChildren();
    var keyTraversal = children.isEmpty() ? null : children.getFirst();
    return GremlinAggregateAssembler.configureGroupCount(ctx, keyTraversal);
  }
}
