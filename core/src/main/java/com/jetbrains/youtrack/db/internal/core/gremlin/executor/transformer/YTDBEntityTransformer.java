package com.jetbrains.youtrack.db.internal.core.gremlin.executor.transformer;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBElementImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;

public class YTDBEntityTransformer implements ResultTransformer<YTDBElementImpl> {

  @Override
  public Result transform(DatabaseSessionInternal session, YTDBElementImpl element) {
    return new ResultInternal(session, element.getRawEntity());
  }
}
