package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;

/**
 * Recogniser for single-label {@link SelectOneStep} ({@code select("label")}): same RETURN / {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#MAP} wiring as
 * {@link SelectStepRecogniser}, which handles the multi-label {@code SelectStep} form.
 */
final class SelectOneStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final SelectOneStepRecogniser INSTANCE = new SelectOneStepRecogniser();

  private SelectOneStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof SelectOneStep<?, ?> selectStep)) {
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
    var scopeKeys = selectStep.getScopeKeys();
    if (scopeKeys == null || scopeKeys.size() != 1) {
      return Outcome.DECLINE;
    }
    return GremlinProjectionAssembler.configureSelect(ctx, scopeKeys);
  }
}
