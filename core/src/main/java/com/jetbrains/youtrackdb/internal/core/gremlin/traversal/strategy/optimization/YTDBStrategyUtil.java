package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy.optimization;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy;

public final class YTDBStrategyUtil {

  private YTDBStrategyUtil() {
  }

  /// Check if traversal should be executed as a polymorphic query. Returns null if there is no
  /// underlying graph object.
  @Nullable
  static Boolean isPolymorphic(Admin<?, ?> traversal) {
    final var graph = traversal.getGraph().orElse(null);
    if (graph == null) {
      return null;
    }

    final var tx = (YTDBTransaction) graph.tx();
    tx.readWrite();

    final Boolean value = YTDBQueryConfigParam.polymorphicQuery.getValue(traversal);
    if (value != null) {
      return value;
    }

    return tx.getDatabaseSession()
        .getConfiguration()
        .getValueAsBoolean(GlobalConfiguration.QUERY_GREMLIN_POLYMORPHIC_BY_DEFAULT);
  }
}
