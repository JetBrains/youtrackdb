package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CheckSafeDeleteStepTest extends TestUtilsFixture {

  private static final String VERTEX_CLASS_NAME = "VertexTestClass";
  private static final String EDGE_CLASS_NAME = "EdgeTestClass";

  private final String className;

  public CheckSafeDeleteStepTest(String className) {
    this.className = className;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> documentTypes() {
    return Arrays.asList(
        new Object[][]{
            {VERTEX_CLASS_NAME}, {EDGE_CLASS_NAME},
        });
  }

  @Test(expected = CommandExecutionException.class)
  public void shouldNotDeleteVertexAndEdge() {
    switch (className) {
      case VERTEX_CLASS_NAME:
        session.createVertexClass(VERTEX_CLASS_NAME);
        break;
      case EDGE_CLASS_NAME:
        session.createEdgeClass(EDGE_CLASS_NAME);
        break;
    }

    var simpleClassName = createClassInstance().getName();
    session.executeInTx(transaction -> {
      CommandContext context = new BasicCommandContext();
      context.setDatabaseSession(session);

      var step = new CheckSafeDeleteStep(context, false);
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
              var db = ctx.getDatabaseSession();

              if (!done) {
                for (var i = 0; i < 10; i++) {
                  if (i % 2 == 0) {
                    result.add(new ResultInternal(db, db.newEntity(simpleClassName)));
                  } else {
                    if (className.equals(VERTEX_CLASS_NAME)) {
                      result.add(new ResultInternal(db, db.newVertex(className)));
                    } else if (className.equals(EDGE_CLASS_NAME)) {
                      var from = db.newVertex();
                      var to = db.newVertex();

                      var edge = db.newStatefulEdge(from, to, className);
                      result.add(new ResultInternal(db, (Identifiable) edge));
                    }
                  }
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

  @Test
  public void shouldSafelyDeleteRecord() {
    var className = createClassInstance().getName();

    session.executeInTx(transaction -> {
      CommandContext context = new BasicCommandContext();
      context.setDatabaseSession(session);
      var step = new CheckSafeDeleteStep(context, false);
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
                      new ResultInternal(session, session.newEntity(className)));
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
    });
  }
}
