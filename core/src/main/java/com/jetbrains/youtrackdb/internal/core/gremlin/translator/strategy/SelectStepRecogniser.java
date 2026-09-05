package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AliasPropertyPresence;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.apache.tinkerpop.gremlin.process.traversal.Pop;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Recogniser for {@link SelectStep}: {@code select(labels…)} projects bound {@code as(...)} labels;
 * {@code select(labels…).by(…)} applies a key-side modulator per label. Pins {@link
 * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType#MAP}.
 *
 * <p>Key-side {@code by(key)} does not project {@code alias.key} RETURN columns — the plan step
 * loads each alias entity and reads the property (same dual-eval avoidance as {@code valueMap}).
 * Presence always rides post-plan {@link AliasPropertyPresence} + {@code dropOnAbsent}, never
 * pattern {@code IS DEFINED}: a presence-only mid-walk alias would otherwise look falsely selective
 * to the MATCH root estimator ({@code classCount / 2}), and a cardinality clause must not filter
 * before {@code LIMIT}/{@code SKIP}/{@code DISTINCT}.
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
    var aliasPresences = new ArrayList<AliasPropertyPresence>();
    var presenceEntityColumns = new HashSet<String>();
    ctx.clearReturnProjection();
    for (int i = 0; i < labels.size(); i++) {
      var userLabel = labels.get(i);
      var internalAlias = ctx.resolveUserLabel(userLabel);
      if (internalAlias == null) {
        return Outcome.DECLINE;
      }
      var modulator = modulators.get(i);
      var field = ByModulatorTranslator.translateKeyModulator(internalAlias, modulator);
      if (field.isEmpty()) {
        return Outcome.DECLINE;
      }
      ctx.markReturnAliasIfForeign(internalAlias);
      var propertyKey = ByModulatorTranslator.keyModulatorPropertyKey(modulator);
      if (propertyKey.isPresent()) {
        // Entity column + emit mapping; never pattern IS DEFINED (see class Javadoc).
        var presence =
            ByModulatorPresence.aliasPresenceForEmit(ctx, internalAlias, modulator, userLabel);
        if (presence.isEmpty()) {
          // Productive by — project the field expression (null for absent keys).
          ctx.appendReturnColumn(field.get(), userLabel);
          continue;
        }
        if (ctx.cardinalityClauseCaptured()) {
          // Post-slice select: load entities from MATCH row bindings in the plan step.
          // Projecting $g2m_pe_* columns before ORDER BY / LIMIT would force an early
          // ProjectionCalculationStep over every candidate the slice later drops.
          aliasPresences.add(
              new AliasPropertyPresence(internalAlias, propertyKey.get(), userLabel));
        } else {
          var entityCol = presence.get().entityColumnAlias();
          if (presenceEntityColumns.add(entityCol)) {
            ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(internalAlias), entityCol);
          }
          aliasPresences.add(presence.get());
        }
      } else {
        // T.id / T.label / other non-property modulators — keep the projected expression.
        ctx.appendReturnColumn(field.get(), userLabel);
      }
    }
    ctx.pinBoundary(ctx.boundaryAlias(), BoundaryOutputType.MAP, Vertex.class);
    var shaping = ResultShaping.NONE.withUnwrapSingletonMap(labels.size() == 1);
    shaping = shaping.withMapEmitColumnOrder(List.copyOf(labels));
    if (!aliasPresences.isEmpty()) {
      shaping =
          shaping.withDropOnAbsent(true).withAliasPropertyPresences(aliasPresences);
    }
    ctx.setResultShaping(shaping);
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
