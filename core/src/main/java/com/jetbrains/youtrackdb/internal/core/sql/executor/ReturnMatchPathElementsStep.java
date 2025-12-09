package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


public class ReturnMatchPathElementsStep extends AbstractUnrollStep {
  public ReturnMatchPathElementsStep(CommandContext context, boolean profilingEnabled) {
    super(context, profilingEnabled);
  }

  @Override
  protected Collection<Result> unroll(Result res, CommandContext iContext) {
    List<Result> result = new ArrayList<>();
    for (var s : res.getPropertyNames()) {
      var elem = res.getProperty(s);
      if (elem instanceof Identifiable) {
        var newelem = new ResultInternal(iContext.getDatabaseSession(),
            (Identifiable) elem);
        elem = newelem;
      }
      if (elem instanceof Result) {
        result.add((Result) elem);
      }
      // else...? TODO
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ UNROLL $pathElements";
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ReturnMatchPathElementsStep(ctx, profilingEnabled);
  }
}
