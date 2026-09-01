package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.OrderByNullsDefault;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Resolves whether nulls sort before non-nulls for {@code ORDER BY} and Gremlin {@code order()}.
 *
 * <p>Precedence: explicit {@code NULLS FIRST}/{@code NULLS LAST} on an item wins (absolute).
 * Otherwise the {@link GlobalConfiguration#QUERY_ORDER_BY_NULLS_DEFAULT} value applies, read from
 * {@code config} when present so per-storage settings override the runtime global.
 *
 * <p>This class owns every read of that configuration key. Reads are deliberately tolerant: the
 * value can arrive as a lower-case string from a server property or a stored storage property, and
 * a query must never fail because of it. A value that names no constant is reported once and
 * replaced by the runtime global default.
 */
public final class OrderByNullsUtil {

  /**
   * Invalid raw values already reported. A malformed value is read on every sort, so the warning is
   * emitted once per distinct value instead of once per query. The set is bounded by the number of
   * distinct malformed values a deployment configures, which is one in practice.
   */
  private static final Set<String> REPORTED_INVALID_VALUES = ConcurrentHashMap.newKeySet();

  private OrderByNullsUtil() {
  }

  /**
   * Resolves null placement for one sort key, reading the configuration default.
   *
   * <p>Callers that compare more than once must resolve the default once with {@link
   * #resolveDefaultForSort} and use {@link #composeNullsFirst} instead, so a concurrent
   * configuration change cannot alter placement in the middle of a sort.
   *
   * @param nullOrdering explicit {@link SQLOrderByItem#NULLS_FIRST} / {@link
   *     SQLOrderByItem#NULLS_LAST}, or {@code null} when omitted
   * @param ascending {@code true} for ASC / Gremlin {@code Order.asc}
   * @param config session or storage context configuration; {@code null} falls back to the runtime
   *     global only
   */
  public static boolean resolveNullsFirst(
      @Nullable String nullOrdering, boolean ascending, @Nullable ContextConfiguration config) {
    return composeNullsFirst(nullOrdering, ascending, resolveDefault(config));
  }

  /**
   * Composes an already resolved default with one item's explicit clause and direction. Pure
   * arithmetic: no configuration read, so it is safe on the per-comparison path.
   *
   * @param nullsDefault the default resolved once for the whole sort
   */
  public static boolean composeNullsFirst(
      @Nullable String nullOrdering, boolean ascending, OrderByNullsDefault nullsDefault) {
    if (SQLOrderByItem.NULLS_FIRST.equals(nullOrdering)) {
      return true;
    }
    if (SQLOrderByItem.NULLS_LAST.equals(nullOrdering)) {
      return false;
    }
    if (nullsDefault == OrderByNullsDefault.NULLS_LARGEST) {
      return !ascending;
    }
    // NULLS_SMALLEST (default): ASC -> nulls first, DESC -> nulls last
    return ascending;
  }

  /**
   * Resolves the default once for a whole sort or merge, from the session behind {@code ctx}.
   *
   * @param ctx the command context; a missing context, session or configuration falls back to the
   *     runtime global
   */
  public static OrderByNullsDefault resolveDefaultForSort(@Nullable CommandContext ctx) {
    if (ctx == null) {
      return resolveDefault(null);
    }
    var session = ctx.getDatabaseSession();
    return resolveDefault(session == null ? null : session.getConfiguration());
  }

  /**
   * Reads the configured default from {@code config}, falling back to the runtime global. Never
   * throws: an unparseable value is reported and the runtime global default applies.
   */
  public static OrderByNullsDefault resolveDefault(@Nullable ContextConfiguration config) {
    if (config != null) {
      // getValue already falls back to the global value when the context carries no override, so a
      // separate global read is only needed when the context value itself is unusable.
      var parsed = parse(config.getValue(GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT));
      if (parsed != null) {
        return parsed;
      }
    }
    var global = parse(GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.getValue());
    if (global != null) {
      return global;
    }
    // The declared default is the last resort: it is a constant of the enum by construction.
    return (OrderByNullsDefault) GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.getDefValue();
  }

  /**
   * Parses one raw configuration value case-insensitively.
   *
   * @return the matching constant, or {@code null} when the value is absent or names no constant
   */
  @Nullable private static OrderByNullsDefault parse(@Nullable Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof OrderByNullsDefault value) {
      return value;
    }
    var presentation = raw.toString().trim();
    for (var constant : OrderByNullsDefault.values()) {
      if (constant.name().equalsIgnoreCase(presentation)) {
        return constant;
      }
    }
    reportInvalid(presentation);
    return null;
  }

  private static void reportInvalid(String presentation) {
    if (REPORTED_INVALID_VALUES.add(presentation.toUpperCase(Locale.ENGLISH))) {
      // The message is concatenated, not formatted: the varargs form of warn would also match the
      // (requester, dbName, message, args) overload for two string arguments.
      LogManager.instance()
          .warn(
              OrderByNullsUtil.class,
              "Ignored invalid value '"
                  + presentation
                  + "' of '"
                  + GlobalConfiguration.QUERY_ORDER_BY_NULLS_DEFAULT.getKey()
                  + "'; using the default null ordering instead");
    }
  }
}
