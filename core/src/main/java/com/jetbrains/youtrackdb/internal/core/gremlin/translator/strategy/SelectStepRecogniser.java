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
import org.apache.tinkerpop.gremlin.process.traversal.step.map.ElementMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyMapStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectOneStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.SelectStep;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
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
 *
 * <p>After a captured cardinality clause, a modulated select declines before a single-key {@code
 * values}, any {@code valueMap}, or any {@code elementMap}, and also before a trailing select that
 * overlaps its projected labels. A map producer declines before those element-only projections, or
 * before a select naming one of its property keys. The same containment fires inside a filter child
 * even without a local slice, because the child cannot apply statement-level cardinality and would
 * otherwise treat the shape as a pure-filter existence test. These guards contain shapes newly
 * admitted after {@code limit}, {@code skip}, {@code range}, and {@code dedup}, plus the captured-
 * child escapes. Plain chained selects on the main line remain unfixed.
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
    // Contain newly admitted post-cardinality shapes until map consumers distinguish the projected
    // map from its source element. Filter children arm the same gate without a local slice. A plain
    // chained select on the main line stays outside this containment.
    if (ctx.needsMapElementProjectionContainment()
        && (trailingElementProjection(cursor.peek())
            || trailingSelectOverlaps(cursor.peek(), labels))) {
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
          aliasPresences.add(new AliasPropertyPresence(entityCol, key, userLabel, !productive));
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
      // Enable row dropping when any presence filters. Productive keys after dedup still use
      // AliasPropertyPresence for emission but do not drop the row.
      var anyFiltering = aliasPresences.stream().anyMatch(AliasPropertyPresence::dropOnAbsent);
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

  static boolean trailingElementProjection(Step<?, ?> step) {
    if (step instanceof PropertiesStep<?> propertiesStep) {
      // Filter contexts rewrite values(key) to properties(key) before the translator runs
      // (InlineFilterStrategy). Both forms cast the upstream traverser to Element natively.
      var returnType = propertiesStep.getReturnType();
      return (returnType == PropertyType.VALUE || returnType == PropertyType.PROPERTY)
          && propertiesStep.getPropertyKeys().length == 1;
    }
    return step instanceof PropertyMapStep<?, ?> || step instanceof ElementMapStep<?, ?>;
  }

  static boolean trailingSelectOverlaps(Step<?, ?> step, List<String> emittedLabels) {
    // Both select recognisers refuse other Pop modes. This guard relies on that rule because map
    // scope lookup precedes Pop handling.
    if (step instanceof SelectOneStep<?, ?> selectOne && selectOne.getPop() == Pop.last) {
      var scopeKeys = selectOne.getScopeKeys();
      return scopeKeys != null
          && scopeKeys.size() == 1
          && emittedLabels.contains(scopeKeys.iterator().next());
    }
    if (step instanceof SelectStep<?, ?> selectMany && selectMany.getPop() == Pop.last) {
      var selectedLabels = selectMany.getSelectKeys();
      return selectedLabels != null
          && selectedLabels.stream().anyMatch(emittedLabels::contains);
    }
    return false;
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
