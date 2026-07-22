package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;

/**
 * Recogniser for {@link DedupGlobalStep}: {@code dedup()} sets {@code RETURN DISTINCT}; {@code
 * dedup(labels...)} projects the named {@code as(...)} labels then sets distinct. Declines when a
 * named label is not bound ({@link RecognitionContext#resolveUserLabel}) or when a {@code by(...)}
 * modulator child is present (deferred to the {@code ByModulatorTranslator} track).
 */
final class DedupGlobalStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final DedupGlobalStepRecogniser INSTANCE = new DedupGlobalStepRecogniser();

  private DedupGlobalStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof DedupGlobalStep<?> dedup)) {
      return Outcome.DECLINE;
    }
    // Property projections via modulateBy are out of scope until ByModulatorTranslator lands.
    if (!dedup.getLocalChildren().isEmpty()) {
      return Outcome.DECLINE;
    }

    var scopeKeys = dedup.getScopeKeys();
    if (scopeKeys == null || scopeKeys.isEmpty()) {
      ctx.setReturnDistinct(true);
      return Outcome.ACCEPTED;
    }

    for (String userLabel : scopeKeys) {
      if (ctx.resolveUserLabel(userLabel) == null) {
        return Outcome.DECLINE;
      }
    }
    ctx.setNamedDedupReturnProjection(scopeKeys);
    ctx.setReturnDistinct(true);
    return Outcome.ACCEPTED;
  }
}
