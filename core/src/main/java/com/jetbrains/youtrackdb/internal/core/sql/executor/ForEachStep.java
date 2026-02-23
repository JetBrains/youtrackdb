package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLForEachBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIfStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLReturnStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import java.util.Iterator;
import java.util.List;

/** Execution step that iterates over a source collection, binding each element to a loop variable. */
public class ForEachStep extends AbstractExecutionStep {

  private final SQLIdentifier loopVariable;
  private final SQLExpression source;
  public List<SQLStatement> body;

  public ForEachStep(
      SQLIdentifier loopVariable,
      SQLExpression oExpression,
      List<SQLStatement> statements,
      CommandContext ctx,
      boolean enableProfiling) {
    super(ctx, enableProfiling);
    this.loopVariable = loopVariable;
    this.source = oExpression;
    this.body = statements;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;

    var prevStream = prev.start(ctx);
    prevStream.close(ctx);
    var iterator = init(ctx);
    while (iterator.hasNext()) {
      ctx.setVariable(loopVariable.getStringValue(), iterator.next());
      var plan = initPlan(ctx);
      var result = plan.executeFull();
      if (result != null) {
        return result.start(ctx);
      }
    }

    return new EmptyStep(ctx, false).start(ctx);
  }

  protected Iterator<?> init(CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    var val = source.execute(new ResultInternal(db), ctx);
    return MultiValue.getMultiValueIterator(val);
  }

  public ScriptExecutionPlan initPlan(CommandContext ctx) {
    var subCtx1 = new BasicCommandContext();
    subCtx1.setParent(ctx);
    var plan = new ScriptExecutionPlan(subCtx1);
    for (var stm : body) {
      plan.chain(stm.createExecutionPlan(subCtx1, profilingEnabled), profilingEnabled);
    }
    return plan;
  }

  public boolean containsReturn() {
    for (var stm : this.body) {
      if (stm instanceof SQLReturnStatement) {
        return true;
      }
      if (stm instanceof SQLForEachBlock forEachBlock && forEachBlock.containsReturn()) {
        return true;
      }
      if (stm instanceof SQLIfStatement ifStmt && ifStmt.containsReturn()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    List<SQLStatement> bodyCopy = null;
    if (body != null) {
      bodyCopy = body.stream().map(SQLStatement::copy).toList();
    }

    return new ForEachStep(loopVariable, source, bodyCopy, ctx, profilingEnabled);
  }
}
