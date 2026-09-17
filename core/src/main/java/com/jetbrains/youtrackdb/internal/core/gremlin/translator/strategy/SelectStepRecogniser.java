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
 *
 * <p>After {@code RETURN DISTINCT} ({@code dedup()}), only labels that resolve to the current
 * boundary are accepted — MATCH DISTINCT keys the whole RETURN row, so a foreign hop label would
 * not match Gremlin's "dedup the current traverser, then select another path label" contract.
 * DISTINCT keys the entity column; the modulator value is emitted from that entity so duplicate
 * property values across distinct vertices survive.
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
    // Same promote as bare select — keep a preceding values(key) drop.
    if (!ctx.promotePresenceDropToPatternFilter()) {
      return Outcome.DECLINE;
    }
    var aliasPresences = new ArrayList<AliasPropertyPresence>();
    var presenceEntityColumns = new HashSet<String>();
    var recordIdKeys = new ArrayList<String>();
    var returnDistinct = ctx.returnDistinct();
    ctx.clearReturnProjection();
    for (int i = 0; i < labels.size(); i++) {
      var userLabel = labels.get(i);
      var internalAlias = ctx.resolveUserLabel(userLabel);
      if (internalAlias == null) {
        return Outcome.DECLINE;
      }
      if (returnDistinct
          && (ctx.boundaryAlias() == null || !ctx.boundaryAlias().equals(internalAlias))) {
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
        var key = propertyKey.get();
        var productive = ctx.byModulatorIsProductive(key);
        var entityCol = ResultShaping.presenceEntityColumnAlias(internalAlias);
        // Always project the entity column. Post LIMIT/SKIP used to skip it and rely on
        // MATCH pass-through bindings, but a sibling token/productive RETURN column makes
        // RETURN non-empty and drops those bindings — presence then resolved null and wiped
        // every row (selectMixingPropertyAndTokenModulators_returnsRowsBeforeAndAfterACut).
        if (presenceEntityColumns.add(entityCol)) {
          ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(internalAlias), entityCol);
        }
        if (productive && !returnDistinct) {
          ctx.appendReturnColumn(field.get(), userLabel);
        } else {
          aliasPresences.add(new AliasPropertyPresence(entityCol, key, userLabel));
        }
      } else {
        if (returnDistinct) {
          // DISTINCT on entity identity; emit @rid/@class from the projected expression.
          var entityCol = ResultShaping.presenceEntityColumnAlias(internalAlias);
          if (presenceEntityColumns.add(entityCol)) {
            ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(internalAlias), entityCol);
          }
        }
        ctx.appendReturnColumn(field.get(), userLabel);
        if (ByModulatorTranslator.keyModulatorIsRecordId(modulator)) {
          recordIdKeys.add(userLabel);
        }
      }
    }
    ctx.pinBoundary(ctx.boundaryAlias(), BoundaryOutputType.MAP, Vertex.class);
    var shaping = ResultShaping.NONE.withUnwrapSingletonMap(labels.size() == 1);
    shaping = shaping.withMapEmitColumnOrder(List.copyOf(labels));
    if (!aliasPresences.isEmpty()) {
      // dropOnAbsent only when every presence is a filtering by(key); productive keys after
      // dedup still use AliasPropertyPresence for emit but must not drop the row.
      var anyFiltering = aliasPresences.stream()
          .anyMatch(p -> !ctx.byModulatorIsProductive(p.propertyKey()));
      if (anyFiltering) {
        shaping = shaping.withDropOnAbsent(true);
      }
      shaping = shaping.withAliasPropertyPresences(aliasPresences);
    }
    if (!recordIdKeys.isEmpty()) {
      shaping = shaping.withRecordIdMapKeys(List.copyOf(recordIdKeys));
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
