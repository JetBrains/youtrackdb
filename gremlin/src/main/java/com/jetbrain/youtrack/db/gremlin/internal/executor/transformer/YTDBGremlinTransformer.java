package com.jetbrain.youtrack.db.gremlin.internal.executor.transformer;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.ScriptTransformer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.jetbrains.youtrack.db.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.resultset.ResultSetTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrain.youtrack.db.gremlin.internal.YTDBElement;

public class YTDBGremlinTransformer implements ScriptTransformer {
    ScriptTransformer transformer;

    public YTDBGremlinTransformer(ScriptTransformer transformer) {
        this.transformer = transformer;
        this.transformer.registerResultTransformer(HashMap.class, new YTDBGremlinMapTransformer(this));
        this.transformer.registerResultTransformer(
                LinkedHashMap.class, new YTDBGremlinMapTransformer(this));
    }

    @Override
    public ResultSet toResultSet(DatabaseSessionInternal session, Object value) {
        return this.transformer.toResultSet(session, value);
    }

    @Override
    public Result toResult(DatabaseSessionInternal session, Object value) {
        if (value instanceof Iterable<?> iterable) {
            var spliterator = iterable.spliterator();
            var collect =
                    StreamSupport.stream(spliterator, false)
                            .map(
                                    (e) -> {
                                        if (e instanceof YTDBElement) {
                                            return this.transformer.toResult(session, e);
                                        } else {
                                            return e;
                                        }
                                    })
                            .collect(Collectors.toList());

            return this.transformer.toResult(session, collect);
        } else {
            return this.transformer.toResult(session, value);
        }
    }

    @Override
    public boolean doesHandleResult(Object value) {
        return this.transformer.doesHandleResult(value);
    }

    @Override
    public void registerResultTransformer(Class clazz, ResultTransformer resultTransformer) {
        this.transformer.registerResultTransformer(clazz, resultTransformer);
    }

    @Override
    public void registerResultSetTransformer(Class clazz, ResultSetTransformer transformer) {
        this.transformer.registerResultSetTransformer(clazz, transformer);
    }
}
