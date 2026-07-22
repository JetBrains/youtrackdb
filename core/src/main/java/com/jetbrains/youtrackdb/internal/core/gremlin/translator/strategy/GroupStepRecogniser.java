package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupStep;

/**
 * Recogniser for {@link GroupStep}: {@code group()} / {@code group().by(key)} / {@code
 * group().by(key).by(value)} → {@code GROUP BY} + MAP projection via {@link
 * GremlinAggregateAssembler}.
 */
final class GroupStepRecogniser implements StepRecogniser {

  static final GroupStepRecogniser INSTANCE = new GroupStepRecogniser();

  private GroupStepRecogniser() {
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof GroupStep<?, ?, ?> group)) {
      return Outcome.DECLINE;
    }
    return GremlinAggregateAssembler.configureGroup(
        ctx, group.getKeyTraversal(), group.getValueTraversal());
  }
}
