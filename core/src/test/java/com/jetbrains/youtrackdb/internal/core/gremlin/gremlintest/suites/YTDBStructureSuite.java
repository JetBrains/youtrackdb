package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites;

import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios.YTDBPropertiesStructureTest;
import org.apache.tinkerpop.gremlin.AbstractGremlinSuite;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalEngine.Type;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

public class YTDBStructureSuite extends AbstractGremlinSuite {

  static final Class<?>[] testsToExecute = new Class<?>[]{
      YTDBPropertiesStructureTest.class,
  };

  public YTDBStructureSuite(Class<?> klass, RunnerBuilder builder) throws InitializationError {
    super(klass, builder, testsToExecute, testsToExecute, true, Type.STANDARD);
  }
}
