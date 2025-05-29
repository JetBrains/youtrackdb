package com.youtrack.db.gremlin.internal.gremlintest.suite;

import org.apache.tinkerpop.gremlin.AbstractGremlinSuite;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalEngine;
import org.apache.tinkerpop.gremlin.structure.io.IoCustomTest;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

public class DebugSuite extends AbstractGremlinSuite {

  private static final Class<?>[] allTests =
      new Class<?>[] {
        IoCustomTest.class,
        //      IoVertexTest.class
        //            GraphTest.class,
        //            TransactionTest.class,
        //            VertexTest.class
        //            TransactionTest.class
      };

  public DebugSuite(final Class<?> klass, final RunnerBuilder builder)
      throws InitializationError {
    super(klass, builder, allTests, null, false, TraversalEngine.Type.STANDARD);
  }
}
