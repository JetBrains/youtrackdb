package com.jetbrains.youtrack.db.internal.core.gremlin.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.ExecutionPlan;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
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
