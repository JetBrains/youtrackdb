package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy;

public final class YTDBStrategyUtil {

  private YTDBStrategyUtil() {
  }

  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
  public static <T> @Nullable T getConfigValue(
      YTDBQueryConfigParam param, Admin<?, ?> traversal) {
    final var strategy = traversal.getStrategies().getStrategy(OptionsStrategy.class).orElse(null);
    if (strategy == null) {
      return null;
    }
    return (T) strategy.getOptions().get(param.name());
  }

  /// Resolves the YouTrackDB session backing {@code traversal}, or {@code null} when the traversal
  /// is not attached to a YTDB graph. Null-safe on non-YTDB graphs and TinkerPop's {@code
  /// EmptyGraph}: the {@code instanceof} gates decline before {@code tx()} is ever called, so a
  /// graph that does not support transactions never reaches the throwing call. Opens the
  /// transaction ({@code readWrite}) so callers can read session-scoped state.
  @Nullable public static DatabaseSessionEmbedded resolveYtdbSession(Admin<?, ?> traversal) {
    // The Graph and the Transaction from graph.tx() are borrowed from the traversal's long-lived
    // database graph, not opened here; closing them would tear the caller's graph down
    // mid-compilation, so the resource inspection is suppressed.
    @SuppressWarnings("resource")
    final var graph = traversal.getGraph().orElse(null);
    if (!(graph instanceof YTDBGraph)) {
      return null;
    }
    if (!(graph.tx() instanceof YTDBTransaction tx)) {
      return null;
    }
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  /// Check whether the traversal should run as a polymorphic query. Returns {@code null} when the
  /// traversal has no attached YTDB graph (see {@link #resolveYtdbSession}) or its configuration
  /// cannot be resolved; otherwise the explicit {@code polymorphicQuery} option, or the {@code
  /// QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT} session default.
  @Nullable public static Boolean isPolymorphic(Admin<?, ?> traversal) {
    final var session = resolveYtdbSession(traversal);
    if (session == null) {
      return null;
    }

    final Boolean value = getConfigValue(YTDBQueryConfigParam.polymorphicQuery, traversal);
    if (value != null) {
      return value;
    }

    final var configuration = session.getConfiguration();
    if (configuration == null) {
      return null;
    }
    return configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
  }
}
