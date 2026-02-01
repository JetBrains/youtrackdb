package com.jetbrains.youtrackdb.internal.core.metadata.security;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This class is an LRU cache for already parsed SQL statement executors. It stores itself in the
 * storage as a resource. It also acts an an entry point for the SQL parser.
 */
public class PredicateCache {

  private final Map<String, SQLOrBlock> map;
  private final int mapSize;

  /**
   * @param size the size of the cache
   */
  public PredicateCache(int size) {
    this.mapSize = size;
    map =
        new LinkedHashMap<String, SQLOrBlock>(size) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<String, SQLOrBlock> eldest) {
            return super.size() > mapSize;
          }
        };
  }

  /**
   * @param statement an SQL statement
   * @return true if the corresponding executor is present in the cache
   */
  public boolean contains(String statement) {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return false;
    }

    synchronized (map) {
      return map.containsKey(statement);
    }
  }

  /**
   * @param statement an SQL statement
   * @return the corresponding executor, taking it from the internal cache, if it exists
   */
  public SQLOrBlock get(String statement) {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return parse(statement);
    }

    SQLOrBlock result;
    synchronized (map) {
      // LRU
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
    return result.copy();
  }

  protected static SQLOrBlock parse(String statement) throws CommandSQLParsingException {
    return SQLEngine.parsePredicate(statement);
  }

  public void clear() {
    if (GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger() == 0) {
      return;
    }

    synchronized (map) {
      map.clear();
    }
  }
}
