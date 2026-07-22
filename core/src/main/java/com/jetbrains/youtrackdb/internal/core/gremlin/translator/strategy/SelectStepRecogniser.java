package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep;

/**
 * Recogniser for {@link SelectStep}: {@code select(labels…)} projects bound {@code as(...)} labels
 * into RETURN columns and pins {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#MAP}. Declines
 * unbound labels, non-{@link Pop#last} scopes, and {@code by(...)} modulators (Step 4).
 */
final class SelectStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final SelectStepRecogniser INSTANCE = new SelectStepRecogniser();

  private SelectStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof SelectStep<?, ?> selectStep)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    if (selectStep.getPop() != Pop.last) {
      return Outcome.DECLINE;
    }
    if (!selectStep.getLocalChildren().isEmpty()) {
      return Outcome.DECLINE;
    }
    var labels = selectStep.getSelectKeys();
    if (labels == null || labels.isEmpty()) {
      return Outcome.DECLINE;
    }
    return GremlinProjectionAssembler.configureSelect(ctx, labels);
  }
}
