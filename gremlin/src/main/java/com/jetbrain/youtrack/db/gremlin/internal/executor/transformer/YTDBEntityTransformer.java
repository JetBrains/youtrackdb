package com.jetbrain.youtrack.db.gremlin.internal.executor.transformer;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBElement;

public class YTDBEntityTransformer implements ResultTransformer<YTDBElement> {
    @Override
    public Result transform(DatabaseSessionInternal session, YTDBElement element) {
        return new ResultInternal(session, element.getRawEntity());
    }
}
