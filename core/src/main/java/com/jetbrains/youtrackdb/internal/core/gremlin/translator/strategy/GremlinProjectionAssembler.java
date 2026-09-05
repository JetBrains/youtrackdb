package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.ResultShaping;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchProjectionBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Builds RETURN-clause {@link SQLExpression}s for Gremlin projection terminators ({@code select},
 * {@code values}, {@code valueMap}, {@code elementMap}) and pins {@link BoundaryOutputType} on the
 * walk. Entity-layer absent-vs-null classification ({@code EntityImpl.hasProperty}) lands in
 * {@link com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep}; this
 * assembler wires MATCH RETURN items (including the boundary entity for presence checks) and
 * recogniser-side flags ({@code dropOnAbsent}, presence keys, valueMap list wrapping).
 */
final class GremlinProjectionAssembler {

  /** TinkerPop {@code elementMap} token bit for {@code T.id}. */
  static final int ELEMENT_MAP_TOKEN_ID = 1;

  /** TinkerPop {@code elementMap} token bit for {@code T.label}. */
  static final int ELEMENT_MAP_TOKEN_LABEL = 2;

  /** Native {@code elementMap} map key for the element id token. */
  static final String ELEMENT_MAP_KEY_ID = "id";

  /** Native {@code elementMap} map key for the element label token. */
  static final String ELEMENT_MAP_KEY_LABEL = "label";

  private GremlinProjectionAssembler() {
    // Static helper — no instances.
  }

  /**
   * Configures a {@code select(labels…)} terminator: one RETURN column per bound user label (internal
   * alias surfaced under the Gremlin label name) and {@link BoundaryOutputType#MAP}.
   */
  static Outcome configureSelect(RecognitionContext ctx, Collection<String> userLabels) {
    var boundary = ctx.boundaryAlias();
    if (boundary == null || userLabels.isEmpty()) {
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    for (String userLabel : userLabels) {
      var internalAlias = ctx.resolveUserLabel(userLabel);
      if (internalAlias == null) {
        return Outcome.DECLINE;
      }
      ctx.markReturnAliasIfForeign(internalAlias);
      ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(internalAlias), userLabel);
    }
    // A single-label select emits the column value directly (native SelectOneStep shape).
    var shaping = ResultShaping.NONE.withUnwrapSingletonMap(userLabels.size() == 1);
    if (userLabels.size() > 1) {
      // Result content is a HashMap — pin select-label order for LinkedHashMap emission.
      shaping = shaping.withMapEmitColumnOrder(List.copyOf(userLabels));
    }
    ctx.setResultShaping(shaping);
    repinMap(ctx, boundary);
    return Outcome.ACCEPTED;
  }

  /**
   * Configures single-key {@code values(key)}: {@link BoundaryOutputType#SINGLE_VALUE},
   * {@code lastPropertyProjection} for a following aggregate ({@code values("age").mean()}), and a
   * RETURN list that matches what the plan step actually reads.
   *
   * <p>With {@code dropOnAbsent}, {@code AbstractMatchPlanStep} loads the boundary entity and reads
   * the key via {@code hasProperty} / {@code getProperty}, so RETURN is only the entity column — a
   * parallel {@code alias.key} projection would be unused work in {@code CALCULATE PROJECTIONS}.
   * When {@link RecognitionContext#hasPresenceConjunct} already records {@code key IS DEFINED},
   * shaping stays {@link ResultShaping#NONE} and RETURN keeps both the entity (so {@code ORDER BY
   * alias.property} remains a projected alias and projections can defer) and the field column
   * ({@code primaryProjectedValue} emits it).
   *
   * <p>An element without the property produces no traverser. On the main line that drop travels as
   * {@code dropOnAbsent} unless a presence conjunct is already recorded. In a captured child
   * ({@code and(values(a), values(b))}) shaping cannot travel: {@link
   * SubTraversalPredicateAdapter} swallows every result-shape write. There the drop travels as a
   * pattern conjunct via {@link ByModulatorPresence#requireProjectedProperty} — see {@code
   * contributePresenceConjunct}.
   *
   * <p>Keeping the conjunct off the main-line arm when no prior by-modulator wrote it matters:
   * {@code IS DEFINED} has no estimator in the MATCH root-selection cost model (see {@link
   * ByModulatorPresence}'s {@code @implNote}), and the main line already expresses the drop through
   * {@code dropOnAbsent}. A following slice promotes the conjunct on demand through {@link
   * RecognitionContext#promotePresenceDropToPatternFilter}.
   *
   * @param contributePresenceConjunct whether the captured child still emits nothing for an element
   *     without the property once its remaining steps have run. Read only on the captured-child path;
   *     the main line expresses the same drop through shaping and ignores it. {@link
   *     PropertiesStepRecogniser} decides it by a termination test over what remains of the child: a
   *     projection that ends the child turns the flag on, a {@code count()} that ends the child turns
   *     it off (it emits {@code 0} for an empty stream), and every other remaining chain declines the
   *     walk outright.
   */
  static Outcome configureSingleKeyValues(
      RecognitionContext ctx, String propertyKey, boolean contributePresenceConjunct) {
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    if (propertyKey == null || propertyKey.isBlank()
        || WalkerContext.isReservedHasKey(propertyKey)) {
      return Outcome.DECLINE;
    }
    var expr = aliasProperty(boundary, propertyKey);
    ctx.clearReturnProjection();
    ctx.setLastPropertyProjection(
        new RecognitionContext.PropertyProjection(boundary, propertyKey, expr));
    if (ctx.projectsReturnedPayload()) {
      if (ctx.hasPresenceConjunct(boundary, propertyKey)) {
        // Pattern already drops absent keys — no dropOnAbsent. Keep the entity column in
        // RETURN so ORDER BY alias.property stays on a projected alias and projections can
        // defer past ORDER BY / SKIP / LIMIT; the field column is what primaryProjectedValue
        // emits.
        ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(boundary), boundary);
        ctx.appendReturnColumn(expr, null);
        ctx.setResultShaping(ResultShaping.NONE);
      } else {
        // dropOnAbsent reads the entity; do not also project alias.key.
        ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(boundary), boundary);
        ctx.setResultShaping(
            ResultShaping.NONE.withDropOnAbsent(true)
                .withPresencePropertyKeys(List.of(propertyKey)));
        // After setResultShaping, which clears any previous alias. The slice recogniser promotes
        // this alias into a pattern conjunct when a real SKIP / LIMIT arrives.
        ctx.setPresenceDropAlias(boundary);
      }
    } else if (contributePresenceConjunct) {
      // A captured child's shaping is swallowed, so the same drop travels as a pattern conjunct.
      ByModulatorPresence.requireProjectedProperty(ctx, boundary, propertyKey);
    }
    ctx.pinBoundary(boundary, BoundaryOutputType.SINGLE_VALUE, Vertex.class);
    return Outcome.ACCEPTED;
  }

  /**
   * Configures {@code valueMap(keys…)} / {@code elementMap(keys…)}: boundary entity for presence
   * reads, optional token columns ({@code id} / {@code label}), and {@link BoundaryOutputType#MAP}.
   * Property keys are <em>not</em> RETURN columns — {@link
   * com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.AbstractMatchPlanStep} already
   * loads the entity for {@code hasProperty} and reads values from it, so projecting {@code
   * alias.key} would only duplicate that work in {@code CALCULATE PROJECTIONS}. Emit order is pinned
   * via {@link ResultShaping#mapEmitColumnOrder()} (tokens then keys). {@code valueMap} wraps
   * property values in singleton lists; {@code elementMap} leaves them unwrapped. An empty key list
   * declines — see the body.
   *
   * @param tokens bit set of the {@code T.id} / {@code T.label} token columns to emit, from
   *     {@code valueMap(true)} / {@code with(WithOptions.tokens)} or from {@code elementMap}, which
   *     always emits both
   * @param isElementMap whether the step is {@code elementMap} rather than {@code valueMap} — the
   *     two differ in list wrapping, which is independent of whether tokens were requested
   */
  static Outcome configurePropertyMap(
      RecognitionContext ctx, String[] propertyKeys, int tokens, boolean isElementMap) {
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    if (propertyKeys == null || propertyKeys.length == 0) {
      // No key list means every property, enumerated at iteration time — decline until
      // schema-driven all-property projection lands. This covers valueMap(), elementMap(),
      // valueMap(true) and valueMap().with(WithOptions.tokens) alike: requesting the id / label
      // tokens says nothing about which properties to project, and a plan built from the token
      // columns alone returns {id, label} per element and silently loses every property.
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    // Entity column — omitted from the emitted MAP; used for hasProperty and property values.
    ctx.appendReturnColumn(MatchProjectionBuilder.aliasColumn(boundary), boundary);
    var emitOrder = new ArrayList<String>();
    if (tokens != 0) {
      if ((tokens & ELEMENT_MAP_TOKEN_ID) != 0) {
        ctx.appendReturnColumn(aliasRecordAttribute(boundary, "@rid"), ELEMENT_MAP_KEY_ID);
        emitOrder.add(ELEMENT_MAP_KEY_ID);
      }
      if ((tokens & ELEMENT_MAP_TOKEN_LABEL) != 0) {
        ctx.appendReturnColumn(aliasRecordAttribute(boundary, "@class"), ELEMENT_MAP_KEY_LABEL);
        emitOrder.add(ELEMENT_MAP_KEY_LABEL);
      }
    }
    var presenceKeys = new ArrayList<String>();
    for (String key : propertyKeys) {
      if (key == null || key.isBlank() || WalkerContext.isReservedHasKey(key)) {
        return Outcome.DECLINE;
      }
      // Keys stay off RETURN: the plan step reads them from the entity after hasProperty.
      presenceKeys.add(key);
      emitOrder.add(key);
    }
    // List wrapping follows the step, not the tokens: valueMap wraps property values in singleton
    // lists whether or not it was asked for tokens, and elementMap never does. Deriving it from the
    // token bits instead made valueMap(true, "name") emit name=josh where native emits name=[josh].
    // The token-key flag does follow the tokens — id / label go under T.id / T.label whenever they
    // are emitted, from either step. mapEmitColumnOrder drives projectMap so presence keys appear
    // even though they are not RETURN columns.
    ctx.setResultShaping(
        ResultShaping.NONE
            .withPresencePropertyKeys(presenceKeys)
            .withMapEmitColumnOrder(emitOrder)
            .withWrapMapValuesInLists(!isElementMap)
            .withElementMapTokens(tokens != 0));
    repinMap(ctx, boundary);
    return Outcome.ACCEPTED;
  }

  /** {@code alias.propertyKey} built as AST (no SQL-text round-trip) — delegates to {@link
   *  ByModulatorTranslator#aliasProperty}. */
  static SQLExpression aliasProperty(String alias, String propertyKey) {
    return ByModulatorTranslator.aliasProperty(alias, propertyKey);
  }

  /** {@code alias.@rid} / {@code alias.@class} for {@code elementMap} token columns. */
  static SQLExpression aliasRecordAttribute(String alias, String attribute) {
    return ByModulatorTranslator.aliasRecordAttribute(alias, attribute);
  }

  private static void repinMap(RecognitionContext ctx, String boundary) {
    ctx.pinBoundary(boundary, BoundaryOutputType.MAP, Vertex.class);
  }
}
