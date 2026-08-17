package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Recogniser for single-label {@link SelectOneStep} ({@code select("label")} / {@code
 * select("label").by(…)}): same RETURN / {@link BoundaryOutputType#MAP} wiring as {@link
 * SelectStepRecogniser}.
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
    var userLabel = scopeKeys.iterator().next();
    var internalAlias = ctx.resolveUserLabel(userLabel);
    if (internalAlias == null) {
      return Outcome.DECLINE;
    }
    var field = ByModulatorTranslator.translateKeyModulator(internalAlias, modulators.getFirst());
    if (field.isEmpty()) {
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    ctx.appendReturnColumn(field.get(), userLabel);
    // by(key) drops an element that has no such property — see ByModulatorPresence.
    ByModulatorPresence.requireModulatedProperty(ctx, internalAlias, modulators.getFirst());
    ctx.pinBoundary(ctx.boundaryAlias(), BoundaryOutputType.MAP, Vertex.class);
    // A single-label select emits the column value directly (native SelectOneStep shape).
    ctx.setResultShaping(ResultShaping.NONE.withUnwrapSingletonMap(true));
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
