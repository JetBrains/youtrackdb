package com.jetbrains.youtrackdb.internal.core.gremlin.sqlcommand;

import org.junit.Assert;
import org.junit.Test;

public class SqlCommandExecutionResultTest {

  @Test(expected = IllegalArgumentException.class)
  public void shouldRejectNullIterator() {
    SqlCommandExecutionResult.results(null);
  }

  @Test
  public void shouldReturnSingletonUnit() {
    Assert.assertSame(SqlCommandExecutionResult.unit(), SqlCommandExecutionResult.unit());
  }
}
