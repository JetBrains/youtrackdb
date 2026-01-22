package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class ConvertToUpdatableResultStepTest extends TestUtilsFixture {

  private static final String STRING_PROPERTY = "stringPropertyName";
  private static final String INTEGER_PROPERTY = "integerPropertyName";
  private final List<EntityImpl> documents = new ArrayList<>();

  @Test
  public void shouldConvertUpdatableResult() {
    CommandContext context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var step = new ConvertToUpdatableResultStep(context, false);
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
                var document = (EntityImpl) session.newEntity();
                document.setProperty(STRING_PROPERTY, RandomStringUtils.randomAlphanumeric(10));
                document.setProperty(INTEGER_PROPERTY, new Random().nextInt());
                documents.add(document);
                result.add(new ResultInternal(ctx.getDatabaseSession(), document));
              }
              done = true;
            }
            return ExecutionStream.resultIterator(result.iterator());
          }
        };

    step.setPrevious(previous);
    session.begin();
    var result = step.start(context);

    var counter = 0;
    while (result.hasNext(context)) {
      var currentItem = result.next(context);
      if (!(currentItem.getClass().equals(UpdatableResult.class))) {
        Assert.fail("There is an item in result set that is not an instance of UpdatableResult");
      }
      if (!currentItem
          .asEntity()
          .getProperty(STRING_PROPERTY)
          .equals(documents.get(counter).getProperty(STRING_PROPERTY))) {
        Assert.fail("String EntityImpl property inside Result instance is not preserved");
      }
      if (!currentItem
          .asEntity()
          .getProperty(INTEGER_PROPERTY)
          .equals(documents.get(counter).getProperty(INTEGER_PROPERTY))) {
        Assert.fail("Integer EntityImpl property inside Result instance is not preserved");
      }
      counter++;
    }
    session.commit();
  }
}
