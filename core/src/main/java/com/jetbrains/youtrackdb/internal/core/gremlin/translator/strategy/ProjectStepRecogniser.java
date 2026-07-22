package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep;

/**
 * Recogniser for {@link ProjectStep}: declines until {@code ByModulatorTranslator} (Track 6 Step 4)
 * can resolve {@code project(keys…).by(…)} modulator slots.
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
    if (!(step instanceof ProjectStep<?, ?>)) {
      return Outcome.DECLINE;
    }
    if (ctx.boundaryAlias() == null) {
      return Outcome.DECLINE;
    }
    // project().by(...) modulators land in Step 4; incomplete or modulated shapes decline here.
    return Outcome.DECLINE;
  }
}
