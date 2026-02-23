package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the CheckClassTypeStep query execution step. */
public class CheckClassTypeStepTest extends TestUtilsFixture {

  @Test
  public void shouldCheckSubclasses() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var parentClass = createClassInstance();
    var childClass = createChildClassInstance(parentClass);
    var step =
        new CheckClassTypeStep(childClass.getName(), parentClass.getName(), context,
            false);

    var result = step.start(context);
    Assert.assertEquals(0, result.stream(context).count());
  }

  @Test
  public void shouldCheckOneType() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var className = createClassInstance().getName();
    var step = new CheckClassTypeStep(className, className, context, false);

    var result = step.start(context);
    Assert.assertEquals(0, result.stream(context).count());
  }

  @Test(expected = CommandExecutionException.class)
  public void shouldThrowExceptionWhenClassIsNotParent() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var step =
        new CheckClassTypeStep(
            createClassInstance().getName(), createClassInstance().getName(), context,
            false);

    step.start(context);
  }
}
