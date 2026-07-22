package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Recogniser for {@link ProjectStep}: {@code project(keys…).by(…)} builds one RETURN column per key
 * via {@link ByModulatorTranslator} and pins {@link BoundaryOutputType#MAP}.
 */
final class ProjectStepRecogniser implements StepRecogniser {

  /** Singleton — the recogniser is stateless and cheap to share across walker instances. */
  static final ProjectStepRecogniser INSTANCE = new ProjectStepRecogniser();

  private ProjectStepRecogniser() {
    // Singleton — instantiate via INSTANCE.
  }

  @Override
  public Outcome recognize(StepCursor cursor, RecognitionContext ctx) {
    var step = cursor.take();
    if (!(step instanceof ProjectStep<?, ?> project)) {
      return Outcome.DECLINE;
    }
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var keys = project.getProjectKeys();
    var modulators = project.getTraversalRing().getTraversals();
    if (!ByModulatorTranslator.exactModulatorCount(keys.size(), modulators.size())) {
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    for (int i = 0; i < keys.size(); i++) {
      var field =
          ByModulatorTranslator.translateKeyModulator(boundary, modulators.get(i));
      if (field.isEmpty()) {
        return Outcome.DECLINE;
      }
      ctx.appendReturnColumn(field.get(), keys.get(i));
    }
    ctx.pinBoundary(boundary, BoundaryOutputType.MAP, Vertex.class);
    return Outcome.ACCEPTED;
  }
}
