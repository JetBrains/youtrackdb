package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import java.util.LinkedHashMap;
import java.util.Map;

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

  public static GqlStatement get(String statement, DatabaseSessionInternal session) {
    if (session == null) {
      return parse(statement);
    }

    var resource = session.getSharedContext().getGqlStatementCache();
    return resource.getCached(statement);
  }

  protected static GqlStatement parse(String statement) {
    return GqlPlanner.parse(statement);
  }

  public boolean contains(String statement) {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return false;
    }
    synchronized (map) {
      return map.containsKey(statement);
    }
  }

  public GqlStatement getCached(String statement) {
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
    synchronized (map) {
      map.clear();
    }
  }
}
