package com.jetbrain.youtrack.db.gremlin.internal.executor.transformer;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.script.transformer.result.ResultTransformer;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalMetrics;
import org.apache.tinkerpop.gremlin.process.traversal.util.ImmutableMetrics;

public class YTDBTraversalMetricTransformer implements ResultTransformer<DefaultTraversalMetrics> {
    @Override
    public Result transform(DatabaseSessionInternal session, DefaultTraversalMetrics value) {

        var result = new ResultInternal(session);
        result.setProperty("time (ms)", value.getDuration(TimeUnit.MILLISECONDS));

        List<ResultInternal> steps =
                value.getMetrics().stream().map(item -> mapMetric(session, item)).collect(Collectors.toList());

        result.setProperty("steps", steps);
        return result;
    }

    private ResultInternal mapMetric(DatabaseSessionInternal session, ImmutableMetrics m) {
        ResultInternal internal = new ResultInternal(session);
        internal.setProperty("id", m.getId());
        internal.setProperty("time (ms)", m.getDuration(TimeUnit.MILLISECONDS));
        internal.setProperty("name", m.getName());
        internal.setProperty("counts", m.getCounts());
        internal.setProperty(
                "nested", m.getNested().stream().map(item -> mapMetric(session, item)).collect(Collectors.toList()));
        return internal;
    }
}
