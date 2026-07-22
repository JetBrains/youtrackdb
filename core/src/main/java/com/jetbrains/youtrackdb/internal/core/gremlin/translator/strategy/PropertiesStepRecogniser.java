package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.structure.PropertyType;

/**
 * Recogniser for terminal {@link PropertiesStep} ({@code values(key)} / optimised {@code
 * properties(key)}): maps to a single field-access RETURN column with {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#SINGLE_VALUE}
 * and {@code dropOnAbsent}. Multi-key {@code values(...)} and property-map shapes decline.
 */
final class PropertiesStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final PropertiesStepRecogniser INSTANCE = new PropertiesStepRecogniser();

  private PropertiesStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof PropertiesStep<?> propertiesStep)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    var returnType = propertiesStep.getReturnType();
    if (returnType != PropertyType.VALUE && returnType != PropertyType.PROPERTY) {
      return Outcome.DECLINE;
    }
    var keys = propertiesStep.getPropertyKeys();
    if (keys.length != 1) {
      // Multi-key values() flatMaps — no MATCH boundary equivalent in Phase 1.
      return Outcome.DECLINE;
    }
    return GremlinProjectionAssembler.configureSingleKeyValues(ctx, keys[0]);
  }
}
