package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import java.util.ArrayList;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;

/**
 * Recogniser for {@link DedupGlobalStep}: {@code dedup()} sets {@code RETURN DISTINCT}; {@code
 * dedup(labels...)} projects the named {@code as(...)} labels then sets distinct; {@code by(...)}
 * modulators resolve dedup keys via {@link ByModulatorTranslator}.
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
    var modulators = dedup.getLocalChildren();
    if (!modulators.isEmpty()) {
      return recognizeModulatedDedup(ctx, dedup, modulators);
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

  private static Outcome recognizeModulatedDedup(
      RecognitionContext ctx,
      DedupGlobalStep<?> dedup,
      java.util.List<? extends Traversal<?, ?>> modulators) {
    var scopeKeys = dedup.getScopeKeys();
    if (scopeKeys == null || scopeKeys.isEmpty()) {
      if (modulators.size() != 1) {
        return Outcome.DECLINE;
      }
      var boundary = ctx.boundaryAlias();
      if (boundary == null) {
        return Outcome.DECLINE;
      }
      var field = translateModulator(boundary, modulators.getFirst());
      if (field.isEmpty()) {
        return Outcome.DECLINE;
      }
      ctx.clearReturnProjection();
      ctx.appendReturnColumn(field.get(), null);
      ctx.setReturnDistinct(true);
      return Outcome.ACCEPTED;
    }
    var labels = new ArrayList<>(scopeKeys);
    if (!ByModulatorTranslator.exactModulatorCount(labels.size(), modulators.size())) {
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    for (int i = 0; i < labels.size(); i++) {
      var userLabel = labels.get(i);
      var internalAlias = ctx.resolveUserLabel(userLabel);
      if (internalAlias == null) {
        return Outcome.DECLINE;
      }
      var field = translateModulator(internalAlias, modulators.get(i));
      if (field.isEmpty()) {
        return Outcome.DECLINE;
      }
      ctx.appendReturnColumn(field.get(), userLabel);
    }
    ctx.setReturnDistinct(true);
    return Outcome.ACCEPTED;
  }

  private static java.util.Optional<
      com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression>
      translateModulator(String alias, Traversal<?, ?> modulator) {
    return ByModulatorTranslator.translateKeyModulator(alias, modulator.asAdmin());
  }
}
