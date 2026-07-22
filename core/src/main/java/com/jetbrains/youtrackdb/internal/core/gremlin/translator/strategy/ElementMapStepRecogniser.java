package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.ElementMapStep;

/**
 * Recogniser for {@link ElementMapStep} ({@code elementMap(…)}): always includes {@code id} and
 * {@code label} token columns plus any requested property keys; pins {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#MAP}.
 */
final class ElementMapStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final ElementMapStepRecogniser INSTANCE = new ElementMapStepRecogniser();

  private ElementMapStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof ElementMapStep<?, ?> mapStep)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    var tokens =
        GremlinProjectionAssembler.ELEMENT_MAP_TOKEN_ID
            | GremlinProjectionAssembler.ELEMENT_MAP_TOKEN_LABEL;
    return GremlinProjectionAssembler.configurePropertyMap(ctx, mapStep.getPropertyKeys(), tokens);
  }
}
