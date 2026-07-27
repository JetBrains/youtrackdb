package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.BoundaryOutputType;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.ByModulatorTranslator;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Vertex;

/**
 * Builds RETURN-clause {@link SQLExpression}s for Gremlin projection terminators ({@code select},
 * {@code values}, {@code valueMap}, {@code elementMap}) and pins {@link BoundaryOutputType} on the
 * walk. Entity-layer absent-vs-null classification ({@code EntityImpl.hasProperty}) lands in
 * {@link com.jetbrains.youtrackdb.internal.core.gremlin.translator.step.YTDBMatchPlanStep} (Track 6
 * Step 7); this assembler wires MATCH RETURN items (including the boundary entity for presence
 * checks) and recogniser-side flags ({@code dropOnAbsent}, presence keys, valueMap list wrapping).
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
      ctx.appendReturnColumn(new SQLExpression(new SQLIdentifier(internalAlias)), userLabel);
    }
    ctx.setUnwrapSingletonMap(userLabels.size() == 1);
    ctx.setElementMapTokens(false);
    ctx.setWrapMapValuesInLists(false);
    ctx.setAccumulateMap(false);
    ctx.setPresencePropertyKeys(List.of());
    repinMap(ctx, boundary);
    return Outcome.ACCEPTED;
  }

  /**
   * Configures single-key {@code values(key)}: boundary entity column (for {@code hasProperty}) plus
   * one field-access RETURN column, {@link BoundaryOutputType#SINGLE_VALUE}, {@code dropOnAbsent},
   * and {@code lastPropertyProjection} for a following aggregate ({@code values("age").mean()}).
   */
  static Outcome configureSingleKeyValues(RecognitionContext ctx, String propertyKey) {
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
    // Entity column first — Step 7 dropOnAbsent reads EntityImpl.hasProperty from this alias.
    ctx.appendReturnColumn(new SQLExpression(new SQLIdentifier(boundary)), boundary);
    ctx.appendReturnColumn(expr, null);
    ctx.setLastPropertyProjection(expr);
    ctx.setDropOnAbsent(true);
    ctx.setPresencePropertyKeys(List.of(propertyKey));
    ctx.setWrapMapValuesInLists(false);
    ctx.setAccumulateMap(false);
    ctx.setUnwrapSingletonMap(false);
    ctx.setElementMapTokens(false);
    ctx.pinBoundary(boundary, BoundaryOutputType.SINGLE_VALUE, Vertex.class);
    return Outcome.ACCEPTED;
  }

  /**
   * Configures {@code valueMap(keys…)} / {@code elementMap(…)}: boundary entity for presence checks,
   * one RETURN column per map entry ({@code id}, {@code label}, then property keys), and {@link
   * BoundaryOutputType#MAP}. {@code valueMap} wraps property values in singleton lists; {@code
   * elementMap} leaves them unwrapped.
   */
  static Outcome configurePropertyMap(
      RecognitionContext ctx, String[] propertyKeys, int elementMapTokens) {
    var boundary = ctx.boundaryAlias();
    if (boundary == null) {
      return Outcome.DECLINE;
    }
    var isElementMap = elementMapTokens != 0;
    if (!isElementMap && (propertyKeys == null || propertyKeys.length == 0)) {
      // Bare valueMap() enumerates every property at iteration time — decline until schema-driven
      // all-property projection lands.
      return Outcome.DECLINE;
    }
    ctx.clearReturnProjection();
    // Entity column — omitted from the emitted MAP; used only for hasProperty classification.
    ctx.appendReturnColumn(new SQLExpression(new SQLIdentifier(boundary)), boundary);
    if (isElementMap) {
      if ((elementMapTokens & ELEMENT_MAP_TOKEN_ID) != 0) {
        ctx.appendReturnColumn(aliasRecordAttribute(boundary, "@rid"), ELEMENT_MAP_KEY_ID);
      }
      if ((elementMapTokens & ELEMENT_MAP_TOKEN_LABEL) != 0) {
        ctx.appendReturnColumn(aliasRecordAttribute(boundary, "@class"), ELEMENT_MAP_KEY_LABEL);
      }
    }
    var presenceKeys = new ArrayList<String>();
    if (propertyKeys != null) {
      for (String key : propertyKeys) {
        if (key == null || key.isBlank() || WalkerContext.isReservedHasKey(key)) {
          return Outcome.DECLINE;
        }
        ctx.appendReturnColumn(aliasProperty(boundary, key), key);
        presenceKeys.add(key);
      }
    }
    if (ctx.returnItems().size() <= 1) {
      // Only the entity column — nothing to project.
      return Outcome.DECLINE;
    }
    ctx.setPresencePropertyKeys(presenceKeys);
    ctx.setWrapMapValuesInLists(!isElementMap);
    ctx.setAccumulateMap(false);
    ctx.setDropOnAbsent(false);
    ctx.setUnwrapSingletonMap(false);
    ctx.setElementMapTokens(isElementMap);
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
