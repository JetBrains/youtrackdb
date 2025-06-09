package com.jetbrains.youtrack.db.internal.core.gremlin.executor.transformer;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBAbstractElement;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;

public class YTDBEntityTransformer implements ResultTransformer<YTDBAbstractElement> {
    @Override
    public Result transform(DatabaseSessionInternal session, YTDBAbstractElement element) {
        return new ResultInternal(session, element.getRawEntity());
    }
}
