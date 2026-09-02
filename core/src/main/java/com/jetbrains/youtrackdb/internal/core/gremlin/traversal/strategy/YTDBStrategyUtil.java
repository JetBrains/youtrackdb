package com.jetbrains.youtrackdb.internal.core.gremlin.traversal.strategy;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBTransaction;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.OptionsStrategy;

public final class YTDBStrategyUtil {

  private YTDBStrategyUtil() {
  }

  /// Reads one query option off the traversal the user configured.
  ///
  /// The option is looked up on the ROOT traversal rather than on {@code traversal} itself. A
  /// child traversal never carries the source's [OptionsStrategy] during the strategy pass: an
  /// anonymous child is built with the strategy list of TinkerPop's empty graph, and the parent
  /// list is copied onto it only when the parent locks, which happens after every strategy ran.
  /// Reading the child list would therefore answer `null` for every option on every nested step,
  /// so a documented per-traversal override would silently miss an order inside `union`,
  /// `choose` or any other child scope. {@code GremlinToMatchStrategy} records the same fact for
  /// its own veto marker.
  @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
  public static <T> @Nullable T getConfigValue(
      YTDBQueryConfigParam param, Admin<?, ?> traversal) {
    final var strategy =
        rootTraversal(traversal).getStrategies().getStrategy(OptionsStrategy.class).orElse(null);
    if (strategy == null) {
      return null;
    }
    return (T) strategy.getOptions().get(param.name());
  }

  /// Walks parent links to the outermost traversal. A root traversal reports [EmptyStep] as its
  /// parent, which ends the walk. The step count bound is a cycle guard: a malformed parent chain
  /// then yields the deepest traversal reached rather than hanging the compilation.
  private static Admin<?, ?> rootTraversal(Admin<?, ?> traversal) {
    var current = traversal;
    for (var guard = 0; guard < MAX_PARENT_DEPTH; guard++) {
      final var parent = current.getParent();
      if (parent == null || parent instanceof EmptyStep) {
        return current;
      }
      final var parentTraversal = parent.asStep().getTraversal();
      if (parentTraversal == null || parentTraversal == current) {
        return current;
      }
      current = parentTraversal;
    }
    return current;
  }

  /// Bound on the parent walk in [#rootTraversal]. Nesting deeper than this does not occur in a
  /// hand-written traversal, and the bound keeps a corrupt parent chain from looping forever.
  private static final int MAX_PARENT_DEPTH = 256;

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

  /// Check whether a global-scope `order()` step must keep a record that does not carry the
  /// ordered property. Returns `null` when the traversal has no attached YTDB graph (see
  /// [#resolveYtdbSession]) or its configuration cannot be resolved; otherwise the explicit
  /// `orderIncludesMissingKey` option, or the
  /// `QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY` session default. The option is read first, so one
  /// traversal opts out of the deviation without touching the deployment-wide value.
  @Nullable public static Boolean orderIncludesMissingKey(Admin<?, ?> traversal) {
    final var session = resolveYtdbSession(traversal);
    if (session == null) {
      return null;
    }

    final Boolean value = getConfigValue(YTDBQueryConfigParam.orderIncludesMissingKey, traversal);
    if (value != null) {
      return value;
    }

    final var configuration = session.getConfiguration();
    if (configuration == null) {
      return null;
    }
    return configuration.getValueAsBoolean(
        GlobalConfiguration.QUERY_GREMLIN_ORDER_INCLUDES_MISSING_KEY);
  }
}
