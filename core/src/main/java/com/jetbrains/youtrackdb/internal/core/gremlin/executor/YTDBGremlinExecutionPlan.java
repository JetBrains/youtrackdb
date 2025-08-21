package com.jetbrains.youtrackdb.internal.core.gremlin.executor;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.ExecutionPlan;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalExplanation;

public class YTDBGremlinExecutionPlan implements ExecutionPlan {
    TraversalExplanation explanation;

    public YTDBGremlinExecutionPlan(TraversalExplanation explanation) {
        this.explanation = explanation;
    }

    @Override
    @Nonnull
    public List<ExecutionStep> getSteps() {
        return new ArrayList<>();
    }

    @Override
    @Nonnull
    public String prettyPrint(int depth, int indent) {
        return explanation.prettyPrint();
    }

    @Override
    @Nonnull
    public Result toResult(DatabaseSession session) {
      var result = new ResultInternal((DatabaseSessionInternal) session);
        result.setProperty("type", "GremlinExecutionPlan");
        result.setProperty("javaType", getClass().getName());
        result.setProperty("stmText", null);
        result.setProperty("cost", null);
        result.setProperty("prettyPrint", prettyPrint(0, 2));

        return result;
    }
}
