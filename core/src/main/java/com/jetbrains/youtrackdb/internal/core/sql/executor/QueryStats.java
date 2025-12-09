package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class contains statistics about graph structure and query execution.
 *
 * <p>To obtain a copy of this object, use
 */
public class QueryStats {

  public Map<String, Long> stats = new ConcurrentHashMap<>();

  public static QueryStats get(DatabaseSessionInternal db) {
    return db.getSharedContext().getQueryStats();
  }

  public long getIndexStats(
      String indexName,
      int params,
      boolean range,
      boolean additionalRange,
      DatabaseSession database) {
    var key =
        generateKey(
            "INDEX",
            indexName,
            String.valueOf(params),
            String.valueOf(range),
            String.valueOf(additionalRange));
    var val = stats.get(key);
    if (val != null) {
      return val;
    }
    if (database != null && database instanceof DatabaseSessionInternal db) {
      var idx = db.getSharedContext().getIndexManager().getIndex(indexName);
      if (idx != null
          && idx.isUnique()
          && (idx.getDefinition().getProperties().size() == params)
          && !range) {
        return 1;
      }
    }
    return -1;
  }

  public void pushIndexStats(
      String indexName, int params, boolean range, boolean additionalRange, Long value) {
    var key =
        generateKey(
            "INDEX",
            indexName,
            String.valueOf(params),
            String.valueOf(range),
            String.valueOf(additionalRange));
    pushValue(key, value);
  }

  private void pushValue(String key, Long value) {
    if (value == null) {
      return;
    }
    var val = stats.get(key);

    if (val == null) {
      val = value;
    } else {
      // refine this ;-)
      val = ((Double) ((val * .9) + (value * .1))).longValue();
      if (value > 0 && val == 0) {
        val = 1L;
      }
    }
    stats.put(key, val);
  }

  protected String generateKey(String... keys) {
    var result = new StringBuilder();
    for (var s : keys) {
      result.append(".->");
      result.append(s);
    }
    return result.toString();
  }
}
