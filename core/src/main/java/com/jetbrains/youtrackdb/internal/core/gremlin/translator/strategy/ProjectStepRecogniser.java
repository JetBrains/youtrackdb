package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AliasPropertyPresence;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ProjectStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Recogniser for {@link ProjectStep}: {@code project(keys…).by(…)} builds one emit column per key
 * via {@link ByModulatorTranslator} and pins {@link BoundaryOutputType#MAP}.
 *
 * <p>Property modulators follow the default {@code ProductiveByStrategy} contract: a nonproductive
 * absent key is omitted from the emitted map (the row stays). Productive keys emit {@code null}.
 * That matches native {@code project} and differs from {@code select().by}, which drops the
 * traverser when a nonproductive {@code by(key)} produces nothing.
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
    var aliasPresences = new ArrayList<AliasPropertyPresence>();
    var presenceEntityColumns = new HashSet<String>();
    var emitOrder = new ArrayList<String>(keys.size());
    for (int i = 0; i < keys.size(); i++) {
      var projectKey = keys.get(i);
      var modulator = modulators.get(i);
      var field = ByModulatorTranslator.translateKeyModulator(boundary, modulator);
      if (field.isEmpty()) {
        return Outcome.DECLINE;
      }
      emitOrder.add(projectKey);
      var propertyKey = ByModulatorTranslator.keyModulatorPropertyKey(modulator);
      if (propertyKey.isPresent()) {
        var key = propertyKey.get();
        if (ctx.byModulatorIsProductive(key)) {
          // Productive: SQL null for absent — keep the map entry.
          ctx.appendReturnColumn(field.get(), projectKey);
        } else {
          // Nonproductive: omit absent keys. Entity column feeds hasProperty; value is read there.
          var entityCol = ResultShaping.presenceEntityColumnAlias(boundary);
          if (presenceEntityColumns.add(entityCol)) {
            ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(boundary), entityCol);
          }
          aliasPresences.add(AliasPropertyPresence.omitWhenAbsent(entityCol, key, projectKey));
        }
      } else {
        // by(T.id) / by(T.label) / identity — always present on the element.
        ctx.appendReturnColumn(field.get(), projectKey);
      }
    }
    ctx.pinBoundary(boundary, BoundaryOutputType.MAP, Vertex.class);
    var shaping = ResultShaping.NONE.withMapEmitColumnOrder(List.copyOf(emitOrder));
    if (!aliasPresences.isEmpty()) {
      shaping = shaping.withAliasPropertyPresences(aliasPresences);
    }
    ctx.setResultShaping(shaping);
    return Outcome.ACCEPTED;
  }

  @Override
  public boolean contributeShape(Step<?, ?> step, GremlinShapeEncoder encoder) {
    if (!(step instanceof ProjectStep<?, ?> project)) {
      return false;
    }
    encoder.appendStringSeq("pj", project.getProjectKeys());
    return true;
  }
}
