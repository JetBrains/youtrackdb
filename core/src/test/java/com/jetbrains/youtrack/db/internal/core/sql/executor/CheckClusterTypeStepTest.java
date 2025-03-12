package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 */
public class CheckClusterTypeStepTest extends TestUtilsFixture {
private static final String CLUSTER_NAME = "ClusterName";

  @Test
  public void shouldCheckClusterType() {
    var clazz = createClassInstance();
    var clusterId = clazz.getClusterIds()[0];
    var clusterName = session.getClusterNameById(clusterId);

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var step =
        new CheckClusterTypeStep(clusterName, clazz.getName(), context, false);

    var result = step.start(context);
    Assert.assertEquals(0, result.stream(context).count());
  }

  @Test(expected = CommandExecutionException.class)
  public void shouldThrowExceptionWhenClusterIsWrong() {
    session.addCluster(CLUSTER_NAME);

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    var step =
        new CheckClusterTypeStep(CLUSTER_NAME, createClassInstance().getName(), context,
            false);

    step.start(context);
  }
}
