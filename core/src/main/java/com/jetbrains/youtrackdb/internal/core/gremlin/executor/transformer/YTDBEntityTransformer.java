package com.jetbrains.youtrackdb.internal.core.gremlin.executor.transformer;

import com.jetbrains.youtrackdb.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;

public class YTDBEntityTransformer implements ResultTransformer<YTDBElementImpl> {

  @Override
  public Result transform(DatabaseSessionInternal session, YTDBElementImpl element) {
    return new ResultInternal(session, element.getRawEntity());
  }
}
