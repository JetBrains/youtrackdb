package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphFactory;
import java.util.Map;

public class YouTrackDBImpl extends YouTrackDBAbstract<Result, DatabaseSession> implements
    YouTrackDB {

  public YouTrackDBImpl(YouTrackDBInternal<DatabaseSession> internal) {
    super(internal);
  }


  @Override
  public ResultSet execute(String script, Map<String, Object> params) {
    return (ResultSet) super.execute(script, params);
  }

  @Override
  public ResultSet execute(String script, Object... params) {
    return (ResultSet) super.execute(script, params);
  }

  @Override
  public void close() {
    YTDBGraphFactory.unregisterYTDBInstance(this, super::close);
  }
}
