package com.jetbrains.youtrack.db.internal.core.gremlin.executor.transformer;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.ScriptTransformer;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.gremlin.YTDBPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.Collection;
import java.util.stream.Collectors;

public class YTDBPropertyTransformer implements ResultTransformer<YTDBPropertyImpl<?>> {
    private final ScriptTransformer transformer;

    public YTDBPropertyTransformer(ScriptTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public Result transform(DatabaseSessionInternal session, YTDBPropertyImpl element) {
      var internal = new ResultInternal(session);

      var value = element.value();
        if (value instanceof Collection<?> collection) {
            internal.setProperty(
                    element.key(),
                    collection
                            .stream().map(e -> this.transformer.toResult(session, e)).collect(Collectors.toList()));
        } else {
            internal.setProperty(element.key(), value);
        }
        return internal;
    }
}
