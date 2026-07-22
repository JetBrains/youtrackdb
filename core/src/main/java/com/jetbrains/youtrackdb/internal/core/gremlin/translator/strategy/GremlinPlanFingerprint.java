package com.jetbrains.youtrackdb.internal.core.gremlin.translator.strategy;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchPlanInputs;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.PatternNode;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Synthesises a value-independent fingerprint from post-walk {@link MatchPlanInputs} for the
 * {@link GremlinPlanCache}. The key enumerates the positive pattern topology, alias classes (verbatim),
 * alias filters, detached NOT expressions, return projection, and result-shaping clauses ({@code
 * GROUP BY} / {@code ORDER BY} / {@code LIMIT} / {@code SKIP} / {@code DISTINCT}) — never {@link
 * com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement#toGenericStatement()}, which
 * omits {@code notMatchExpressions}. Positional parameters render as {@code ?}; structural tokens
 * (class names, {@code ~label}, RIDs) stay verbatim so distinct labels and NOT shapes do not collide.
 * Limit / skip literals stay in the key (they are not positional slots), so {@code limit(2)} and
 * {@code limit(5)} cannot share a cached plan.
 */
final class GremlinPlanFingerprint {

  private static final Map<Object, Object> NO_PARAMS = Collections.emptyMap();

  private GremlinPlanFingerprint() {
    // Static utility — no instances.
  }

  /**
   * Builds the cache key for {@code inputs}. Callers must pass the pre-plan {@link MatchPlanInputs}
   * snapshot while {@code aliasFilters} is still insertion-ordered.
   */
  static String fingerprint(@Nonnull MatchPlanInputs inputs) {
    var sb = new StringBuilder(256);
    appendPattern(sb, inputs);
    appendAliasFilters(sb, inputs.aliasFilters());
    appendNotExpressions(sb, inputs.notMatchExpressions());
    appendReturnProjection(sb, inputs);
    appendResultShaping(sb, inputs);
    return sb.toString();
  }

  private static void appendPattern(StringBuilder sb, MatchPlanInputs inputs) {
    sb.append("P:");
    var pattern = inputs.pattern();
    var aliasClasses = inputs.aliasClasses();
    for (var entry : pattern.aliasToNode.entrySet()) {
      sb.append('[').append(entry.getKey());
      var cls = aliasClasses.get(entry.getKey());
      if (cls != null && !cls.isBlank()) {
        sb.append(':').append(cls);
      }
      sb.append(']');
    }
    sb.append(";E:");
    for (PatternNode node : pattern.aliasToNode.values()) {
      for (var edge : node.out) {
        sb.append('[').append(edge.out.alias).append("->").append(edge.in.alias).append(':');
        appendPathItemStructural(sb, edge.item);
        sb.append(']');
      }
    }
  }

  /** Renders a path item with edge labels and direction verbatim (not collapsed to {@code ?}). */
  private static void appendPathItemStructural(StringBuilder sb, SQLMatchPathItem item) {
    item.toString(NO_PARAMS, sb);
  }

  private static void appendMatchExpressionStructural(StringBuilder sb, SQLMatchExpression expr) {
    if (expr.getOrigin() != null) {
      expr.getOrigin().toString(NO_PARAMS, sb);
    }
    for (var item : expr.getItems()) {
      appendPathItemStructural(sb, item);
    }
  }

  private static void appendAliasFilters(StringBuilder sb,
      Map<String, SQLWhereClause> aliasFilters) {
    sb.append(";F:");
    for (var entry : aliasFilters.entrySet()) {
      sb.append('[').append(entry.getKey()).append(':');
      entry.getValue().toGenericStatement(sb);
      sb.append(']');
    }
  }

  private static void appendNotExpressions(StringBuilder sb,
      java.util.List<SQLMatchExpression> notExprs) {
    sb.append(";N:");
    for (var notExpr : notExprs) {
      sb.append('[');
      appendMatchExpressionStructural(sb, notExpr);
      sb.append(']');
    }
  }

  private static void appendReturnProjection(StringBuilder sb, MatchPlanInputs inputs) {
    sb.append(";R:");
    var items = inputs.returnItems();
    var aliases = inputs.returnAliases();
    for (int i = 0; i < items.size(); i++) {
      sb.append('[');
      items.get(i).toGenericStatement(sb);
      var alias = aliases.get(i);
      if (alias != null) {
        sb.append(" AS ");
        alias.toGenericStatement(sb);
      }
      sb.append(']');
    }
  }

  /**
   * Appends Track 6 result-shaping clauses. Limit / skip use {@code toString} (not
   * {@code toGenericStatement}): {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNumber}
   * collapses every integer to {@code ?}, which would let {@code limit(2)} and {@code limit(5)}
   * collide. Group / order keep {@code toGenericStatement} — their discriminators are property
   * names and directions, not rebound literals.
   */
  private static void appendResultShaping(StringBuilder sb, MatchPlanInputs inputs) {
    sb.append(";G:");
    if (inputs.groupBy() != null) {
      inputs.groupBy().toGenericStatement(sb);
    }
    sb.append(";O:");
    if (inputs.orderBy() != null) {
      inputs.orderBy().toGenericStatement(sb);
    }
    sb.append(";L:");
    if (inputs.limit() != null) {
      inputs.limit().toString(NO_PARAMS, sb);
    }
    sb.append(";S:");
    if (inputs.skip() != null) {
      inputs.skip().toString(NO_PARAMS, sb);
    }
    sb.append(";D:").append(inputs.returnDistinct());
  }
}
