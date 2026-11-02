package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLBooleanExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIsDefinedCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** One structural estimate-only filter per alias, independent of later alias-class inference. */
final class PresenceEstimateFilters {

  private PresenceEstimateFilters() {
  }

  static Map<String, SQLWhereClause> build(Map<String, SQLWhereClause> filters,
      Map<String, Set<String>> promotedKeys) {
    var result = new FilterMap(promotedKeys.keySet());
    filters.forEach((alias, filter) -> {
      var keys = promotedKeys.getOrDefault(alias, Set.of());
      var expression = keys.isEmpty() ? filter.getBaseExpression()
          : strip(filter.getBaseExpression(), keys);
      if (expression != null) {
        // A fresh WHERE owns its flatten cache. Executable filters never share that mutable state.
        var estimate = new SQLWhereClause(-1);
        estimate.setBaseExpression(expression.copy());
        result.put(alias, estimate);
      } else {
        // Retain the alias so detached path-item readers can distinguish removed from absent.
        result.put(alias, null);
      }
    });
    return result;
  }

  /** Detached filters have their own scope unless this plan explicitly promotes their alias. */
  @Nullable static SQLWhereClause pathFilter(Map<String, SQLWhereClause> filters,
      SQLMatchFilter path) {
    return filters instanceof FilterMap view && path.getAlias() != null
        && view.promotedAliases.contains(path.getAlias())
            ? view.get(path.getAlias()) : path.getFilter();
  }

  private static final class FilterMap extends HashMap<String, SQLWhereClause> {
    private final Set<String> promotedAliases;

    private FilterMap(Set<String> aliases) {
      promotedAliases = Set.copyOf(aliases);
    }
  }

  @Nullable private static SQLBooleanExpression strip(SQLBooleanExpression expression,
      Set<String> keys) {
    if (expression instanceof SQLIsDefinedCondition defined) {
      var builder = new MatchWhereBuilder();
      for (var key : keys) {
        if (defined.equals(builder.isDefined(key))) {
          return null;
        }
      }
    }
    // Only an AND spine represents promoted top-level conjuncts. OR and NOT remain intact.
    if (!(expression instanceof SQLAndBlock and)) {
      return expression;
    }
    var kept = new ArrayList<SQLBooleanExpression>();
    for (var child : and.getSubBlocks()) {
      var stripped = strip(child, keys);
      if (stripped != null) {
        kept.add(stripped);
      }
    }
    if (kept.isEmpty()) {
      return null;
    }
    if (kept.size() == 1) {
      return kept.getFirst();
    }
    var result = new SQLAndBlock(-1);
    result.setSubBlocks(kept);
    return result;
  }
}
