package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyMapStep;

/**
 * Recogniser for {@link PropertyMapStep} ({@code valueMap(keys…)}): one RETURN column per map entry
 * and {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#MAP}. Bare
 * {@code valueMap()} (all properties) and {@code by(...)} modulators decline; requesting the
 * {@code T.id} / {@code T.label} tokens does not make the step an {@code elementMap}, so the
 * assembler is told which step it is serving rather than guessing from the token bits.
 */
final class PropertyMapStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final PropertyMapStepRecogniser INSTANCE = new PropertyMapStepRecogniser();

  private PropertyMapStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof PropertyMapStep<?, ?> mapStep)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    if (!mapStep.getLocalChildren().isEmpty()) {
      return Outcome.DECLINE;
    }
    if (mapStep.getPropertyTraversal() != null || mapStep.getValueTraversal() != null) {
      return Outcome.DECLINE;
    }
    return GremlinProjectionAssembler.configurePropertyMap(
        ctx, mapStep.getPropertyKeys(), mapStep.getIncludedTokens(), false);
  }
}
