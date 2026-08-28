package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import javax.annotation.Nullable;

/**
 * Resolves whether nulls sort before non-nulls for {@code ORDER BY} and Gremlin {@code order()}.
 *
 * <p>Precedence: explicit {@code NULLS FIRST}/{@code NULLS LAST} on an item wins (absolute).
 * Otherwise the {@link GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT} value applies, read from
 * {@code config} when present so per-storage settings override the runtime global.
 */
public final class OrderByNullsUtil {

  private OrderByNullsUtil() {
  }

  /**
   * Resolves null placement for one sort key.
   *
   * @param nullOrdering explicit {@link SQLOrderByItem#NULLS_FIRST} / {@link
   *     SQLOrderByItem#NULLS_LAST}, or {@code null} when omitted
   * @param ascending {@code true} for ASC / Gremlin {@code Order.asc}
   * @param config session or storage context configuration; {@code null} falls back to the runtime
   *     global only
   */
  public static boolean resolveNullsFirst(
      @Nullable String nullOrdering, boolean ascending, @Nullable ContextConfiguration config) {
    if (SQLOrderByItem.NULLS_FIRST.equals(nullOrdering)) {
      return true;
    }
    if (SQLOrderByItem.NULLS_LAST.equals(nullOrdering)) {
      return false;
    }
    var def = resolveDefault(config);
    if (def == OrderByNullsDefault.NULLS_LARGEST) {
      return !ascending;
    }
    // NULLS_SMALLEST (default): ASC -> nulls first, DESC -> nulls last
    return ascending;
  }

  private static OrderByNullsDefault resolveDefault(@Nullable ContextConfiguration config) {
    if (config != null) {
      var fromContext =
          config.getValueAsEnum(
              GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT, OrderByNullsDefault.class);
      if (fromContext != null) {
        return fromContext;
      }
    }
    return GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.<OrderByNullsDefault>getValue();
  }
}
