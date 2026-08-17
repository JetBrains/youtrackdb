package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Recogniser for {@link SelectStep}: {@code select(labels…)} projects bound {@code as(...)} labels;
 * {@code select(labels…).by(…)} applies a key-side modulator per label. Pins {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#MAP}.
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
    var labels = selectStep.getSelectKeys();
    if (labels == null || labels.isEmpty()) {
      return Outcome.DECLINE;
    }
    var modulators = selectStep.getLocalChildren();
    if (modulators.isEmpty()) {
      return GremlinProjectionAssembler.configureSelect(ctx, labels);
    }
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
      var field =
          ByModulatorTranslator.translateKeyModulator(internalAlias, modulators.get(i));
      if (field.isEmpty()) {
        return Outcome.DECLINE;
      }
      ctx.appendReturnColumn(field.get(), userLabel);
      // by(key) drops an element that has no such property — see ByModulatorPresence.
      ByModulatorPresence.requireModulatedProperty(ctx, internalAlias, modulators.get(i));
    }
    ctx.pinBoundary(ctx.boundaryAlias(), BoundaryOutputType.MAP, Vertex.class);
    // A single-label select emits the column value directly (native SelectOneStep shape).
    ctx.setResultShaping(ResultShaping.NONE.withUnwrapSingletonMap(labels.size() == 1));
    return Outcome.ACCEPTED;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    if (!(step instanceof SelectStep<?, ?> selectStep)) {
      return false;
    }
    encoder.appendToken("pop", String.valueOf(selectStep.getPop()));
    encoder.appendStringSeq("sk", selectStep.getSelectKeys());
    return true;
  }
}
