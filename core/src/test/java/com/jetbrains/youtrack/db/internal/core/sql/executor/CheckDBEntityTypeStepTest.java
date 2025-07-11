package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CheckDBEntityTypeStepTest extends TestUtilsFixture {

  @Test
  public void shouldCheckRecordsOfOneType() {
    CommandContext context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var className = createClassInstance().getName();

    session.begin();
    var step = new CheckRecordTypeStep(context, className, false);
    var previous =
        new AbstractExecutionStep(context, false) {
          @Override
          public ExecutionStep copy(CommandContext ctx) {
            throw new UnsupportedOperationException("Not supported yet.");
          }

          boolean done = false;

          @Override
          public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
            List<Result> result = new ArrayList<>();
            if (!done) {
              for (var i = 0; i < 10; i++) {
                result.add(
                    new ResultInternal(ctx.getDatabaseSession(), session.newEntity(className)));
              }
              done = true;
            }
            return ExecutionStream.resultIterator(result.iterator());
          }
        };

    step.setPrevious(previous);
    var result = step.start(context);
    Assert.assertEquals(10, result.stream(context).count());
    Assert.assertFalse(result.hasNext(context));
    session.commit();
  }

  @Test
  public void shouldCheckRecordsOfSubclasses() {
    CommandContext context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var parentClass = createClassInstance();
    var childClass = createChildClassInstance(parentClass);

    session.begin();
    var step = new CheckRecordTypeStep(context, parentClass.getName(), false);
    var previous =
        new AbstractExecutionStep(context, false) {
          @Override
          public ExecutionStep copy(CommandContext ctx) {
            throw new UnsupportedOperationException("Not supported yet.");
          }

          boolean done = false;

          @Override
          public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
            List<Result> result = new ArrayList<>();
            if (!done) {
              for (var i = 0; i < 10; i++) {
                result.add(
                    new ResultInternal(ctx.getDatabaseSession(),
                        session.newEntity(i % 2 == 0 ? parentClass : childClass)));
              }
              done = true;
            }
            return ExecutionStream.resultIterator(result.iterator());
          }
        };

    step.setPrevious(previous);
    var result = step.start(context);
    Assert.assertEquals(10, result.stream(context).count());
    Assert.assertFalse(result.hasNext(context));
    session.commit();
  }

  @Test(expected = CommandExecutionException.class)
  public void shouldThrowExceptionWhenTypeIsDifferent() {

    CommandContext context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var firstClassName = createClassInstance().getName();
    var secondClassName = createClassInstance().getName();

    session.executeInTx(transaction -> {
      var step = new CheckRecordTypeStep(context, firstClassName, false);
      var previous =
          new AbstractExecutionStep(context, false) {
            @Override
            public ExecutionStep copy(CommandContext ctx) {
              throw new UnsupportedOperationException("Not supported yet.");
            }

            boolean done = false;

            @Override
            public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
              List<Result> result = new ArrayList<>();
              if (!done) {
                for (var i = 0; i < 10; i++) {
                  result.add(
                      new ResultInternal(ctx.getDatabaseSession(),
                          session.newEntity(i % 2 == 0 ? firstClassName : secondClassName)));
                }
                done = true;
              }
              return ExecutionStream.resultIterator(result.iterator());
            }
          };

      step.setPrevious(previous);
      var result = step.start(context);
      while (result.hasNext(context)) {
        result.next(context);
      }
    });
  }
}
