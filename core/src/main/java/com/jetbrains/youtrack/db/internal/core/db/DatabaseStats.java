package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import javax.annotation.Nonnull;

public class DatabaseStats {

  public long loadedRecords;
  public long averageLoadRecordTimeMs;
  public long minLoadRecordTimeMs;
  public long maxLoadRecordTimeMs;

  public long prefetchedRidbagsCount;
  public long ridbagPrefetchTimeMs;
  public long minRidbagPrefetchTimeMs;
  public long maxRidbagPrefetchTimeMs;

  public Result toResult(@Nonnull DatabaseSessionInternal db) {
    var result = new ResultInternal(db);

    result.setProperty("loadedRecords", loadedRecords);
    result.setProperty("averageLoadRecordTimeMs", averageLoadRecordTimeMs);
    result.setProperty("minLoadRecordTimeMs", minLoadRecordTimeMs);
    result.setProperty("maxLoadRecordTimeMs", maxLoadRecordTimeMs);
    result.setProperty("prefetchedRidbagsCount", prefetchedRidbagsCount);
    result.setProperty("ridbagPrefetchTimeMs", ridbagPrefetchTimeMs);
    result.setProperty("minRidbagPrefetchTimeMs", minRidbagPrefetchTimeMs);
    result.setProperty("maxRidbagPrefetchTimeMs", maxRidbagPrefetchTimeMs);

    return result;
  }
}
