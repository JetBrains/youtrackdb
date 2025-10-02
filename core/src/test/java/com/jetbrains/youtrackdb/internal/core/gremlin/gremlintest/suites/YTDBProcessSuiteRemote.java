package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites;

import org.apache.tinkerpop.gremlin.AbstractGremlinSuite;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalEngine.Type;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

public class YTDBProcessSuiteRemote extends AbstractGremlinSuite {

  public YTDBProcessSuiteRemote(Class<?> klass, RunnerBuilder builder) throws InitializationError {
    super(klass, builder, YTDBGremlinProcessTests.remoteTests, null, true, Type.STANDARD);
  }
}
