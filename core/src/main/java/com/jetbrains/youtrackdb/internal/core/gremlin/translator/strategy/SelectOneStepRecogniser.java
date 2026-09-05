package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AliasPropertyPresence;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Recogniser for single-label {@link SelectOneStep} ({@code select("label")} / {@code
 * select("label").by(…)}): same RETURN / {@link BoundaryOutputType#MAP} wiring as {@link
 * SelectStepRecogniser}.
 *
 * <p>Key-side {@code by(key)} returns the entity column only; the plan step reads the property.
 * Presence rides post-plan {@link AliasPropertyPresence} + {@code dropOnAbsent}, never pattern
 * {@code IS DEFINED} — same rationale as {@link SelectStepRecogniser}.
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
    var scopeKeys = selectStep.getScopeKeys();
    if (scopeKeys == null || scopeKeys.size() != 1) {
      return Outcome.DECLINE;
    }
    var modulators = selectStep.getLocalChildren();
    if (modulators.isEmpty()) {
      return GremlinProjectionAssembler.configureSelect(ctx, scopeKeys);
    }
    if (modulators.size() != 1) {
      return Outcome.DECLINE;
    }
    if (!ctx.promotePresenceDropToPatternFilter()) {
      return Outcome.DECLINE;
    }
    var userLabel = scopeKeys.iterator().next();
    var internalAlias = ctx.resolveUserLabel(userLabel);
    if (internalAlias == null) {
      return Outcome.DECLINE;
    }
    if (ctx.returnDistinct()
        && (ctx.boundaryAlias() == null || !ctx.boundaryAlias().equals(internalAlias))) {
      return Outcome.DECLINE;
    }
    var modulator = modulators.getFirst();
    var field = ByModulatorTranslator.translateKeyModulator(internalAlias, modulator);
    if (field.isEmpty()) {
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    ctx.markReturnAliasIfForeign(internalAlias);
    var shaping =
        ResultShaping.NONE
            .withUnwrapSingletonMap(true)
            .withMapEmitColumnOrder(List.of(userLabel));
    var returnDistinct = ctx.returnDistinct();
    var propertyKey = ByModulatorTranslator.keyModulatorPropertyKey(modulator);
    if (propertyKey.isPresent()) {
      var key = propertyKey.get();
      var productive = ctx.byModulatorIsProductive(key);
      var entityCol = ResultShaping.presenceEntityColumnAlias(internalAlias);
      // Same as SelectStepRecogniser: always project the entity column, including post-slice.
      // A following token RETURN column would otherwise drop MATCH bindings that presence
      // resolved from when the entity column was omitted.
      ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(internalAlias), entityCol);
      if (productive && !returnDistinct) {
        ctx.appendReturnColumn(field.get(), userLabel);
      } else {
        var presence = new AliasPropertyPresence(entityCol, key, userLabel);
        if (!productive) {
          shaping = shaping.withDropOnAbsent(true);
        }
        shaping = shaping.withAliasPropertyPresences(List.of(presence));
      }
    } else {
      if (returnDistinct) {
        var entityCol = ResultShaping.presenceEntityColumnAlias(internalAlias);
        ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(internalAlias), entityCol);
      }
      ctx.appendReturnColumn(field.get(), userLabel);
      if (ByModulatorTranslator.keyModulatorIsRecordId(modulator)) {
        shaping = shaping.withRecordIdMapKeys(List.of(userLabel));
      }
    }
    ctx.pinBoundary(ctx.boundaryAlias(), BoundaryOutputType.MAP, Vertex.class);
    ctx.setResultShaping(shaping);
    return Outcome.ACCEPTED;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    if (!(step instanceof SelectOneStep<?, ?> selectStep)) {
      return false;
    }
    encoder.appendToken("pop", String.valueOf(selectStep.getPop()));
    encoder.appendStringSeq("sk", selectStep.getScopeKeys());
    return true;
  }
}
