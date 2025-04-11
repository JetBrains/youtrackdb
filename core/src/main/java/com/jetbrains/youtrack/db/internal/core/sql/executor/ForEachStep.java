package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLForEachBlock;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIfStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLReturnStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
import java.util.Iterator;
import java.util.List;

/**
 *
 */
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
      if (stm instanceof SQLForEachBlock && ((SQLForEachBlock) stm).containsReturn()) {
        return true;
      }
      if (stm instanceof SQLIfStatement && ((SQLIfStatement) stm).containsReturn()) {
        return true;
      }
    }
    return false;
  }
}
