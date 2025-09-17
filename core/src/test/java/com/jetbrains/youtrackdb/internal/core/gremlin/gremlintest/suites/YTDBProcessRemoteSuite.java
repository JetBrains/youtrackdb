package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites;

import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios.YTDBTemporaryRidConversionTest;
import org.apache.tinkerpop.gremlin.AbstractGremlinSuite;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalEngine.Type;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

public class YTDBProcessRemoteSuite extends AbstractGremlinSuite {

  private static final Class<?>[] onlyRemoteTests = new Class<?>[]{
      YTDBTemporaryRidConversionTest.Traversals.class,
  };

  private static final Class<?>[] testsToExecute;

  static {

    testsToExecute = new Class<?>[onlyRemoteTests.length
        + YTDBProcessEmbeddedSuite.testsToExecute.length];
    System.arraycopy(onlyRemoteTests, 0, testsToExecute, 0, onlyRemoteTests.length);
    System.arraycopy(YTDBProcessEmbeddedSuite.testsToExecute, 0, testsToExecute,
        onlyRemoteTests.length, YTDBProcessEmbeddedSuite.testsToExecute.length);
  }


  public YTDBProcessRemoteSuite(Class<?> klass, RunnerBuilder builder) throws InitializationError {
    super(klass, builder, testsToExecute, null, true, Type.STANDARD);
  }
}
