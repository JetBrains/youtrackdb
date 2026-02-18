package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * LRU cache for already parsed GQL statements.
 */
public class GqlStatementCache {

  private final Map<String, GqlStatement> map;
  private final int mapSize;

  public GqlStatementCache(int size) {
    this.mapSize = size;
    this.map = new LinkedHashMap<>(size) {
      @Override
      protected boolean removeEldestEntry(final Map.Entry<String, GqlStatement> eldest) {
        return super.size() > mapSize;
      }
    };
  }

  public static @Nullable GqlStatement get(String statement, DatabaseSessionEmbedded session) {
    if (session == null) {
      return parse(statement);
    }

    var resource = Objects.requireNonNull(session.getSharedContext()).getGqlStatementCache();
    return Objects.requireNonNull(resource).getCached(statement);
  }

  protected static GqlStatement parse(@Nullable String statement) {
    return GqlPlanner.parse(statement);
  }

  @SuppressWarnings("unused")
  public boolean contains(String statement) {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return false;
    }
    synchronized (Objects.requireNonNull(map)) {
      return map.containsKey(statement);
    }
  }

  public GqlStatement getCached(@Nullable String statement) {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return parse(statement);
    }

    GqlStatement result;
    synchronized (map) {
      result = map.remove(statement);
      if (result != null) {
        map.put(statement, result);
      }
    }

    if (result == null) {
      result = parse(statement);
      synchronized (map) {
        map.put(statement, result);
      }
    }
    return result;
  }

  public void clear() {
    synchronized (Objects.requireNonNull(map)) {
      map.clear();
    }
  }
}
