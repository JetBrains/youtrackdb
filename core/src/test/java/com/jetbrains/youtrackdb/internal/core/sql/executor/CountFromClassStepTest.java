package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import org.junit.Assert;
import org.junit.Test;

/** Tests for the CountFromClassStep query execution step. */
public class CountFromClassStepTest extends TestUtilsFixture {

  private static final String ALIAS = "size";

  @Test
  public void shouldCountRecordsOfClass() {
    var schemaClass = createClassInstance();
    for (var i = 0; i < 20; i++) {
      session.begin();
      session.newEntity(schemaClass);
      session.commit();
    }

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var step = new CountFromClassStep(schemaClass, ALIAS, context, false);

    var result = step.start(context);
    Assert.assertEquals(20, (long) result.next(context).getProperty(ALIAS));
    Assert.assertFalse(result.hasNext(context));
  }
}
