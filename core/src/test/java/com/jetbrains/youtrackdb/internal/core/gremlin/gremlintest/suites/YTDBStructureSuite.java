package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites;

import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios.YTDBPropertiesStructureTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios.YTDBVertexPropertyPropertiesStructureTest;
import org.apache.tinkerpop.gremlin.AbstractGremlinSuite;
import org.apache.tinkerpop.gremlin.algorithm.generator.CommunityGeneratorTest;
import org.apache.tinkerpop.gremlin.algorithm.generator.DistributionGeneratorTest;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalEngine.Type;
import org.apache.tinkerpop.gremlin.structure.EdgeTest;
import org.apache.tinkerpop.gremlin.structure.FeatureSupportTest;
import org.apache.tinkerpop.gremlin.structure.GraphConstructionTest;
import org.apache.tinkerpop.gremlin.structure.GraphTest;
import org.apache.tinkerpop.gremlin.structure.PropertyTest;
import org.apache.tinkerpop.gremlin.structure.SerializationTest;
import org.apache.tinkerpop.gremlin.structure.TransactionMultiThreadedTest;
import org.apache.tinkerpop.gremlin.structure.TransactionTest;
import org.apache.tinkerpop.gremlin.structure.VariablesTest;
import org.apache.tinkerpop.gremlin.structure.VertexPropertyTest;
import org.apache.tinkerpop.gremlin.structure.VertexTest;
import org.apache.tinkerpop.gremlin.structure.io.IoCustomTest;
import org.apache.tinkerpop.gremlin.structure.io.IoEdgeTest;
import org.apache.tinkerpop.gremlin.structure.io.IoGraphTest;
import org.apache.tinkerpop.gremlin.structure.io.IoPropertyTest;
import org.apache.tinkerpop.gremlin.structure.io.IoTest;
import org.apache.tinkerpop.gremlin.structure.io.IoVertexTest;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedEdgeTest;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedGraphTest;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedPropertyTest;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertexPropertyTest;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertexTest;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceEdgeTest;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceGraphTest;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertexPropertyTest;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertexTest;
import org.apache.tinkerpop.gremlin.structure.util.star.StarGraphTest;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.RunnerBuilder;

public class YTDBStructureSuite extends AbstractGremlinSuite {

  static final Class<?>[] testsToExecute = new Class<?>[]{

      // standard TinkerPop structure tests, copied from StandardStructureSuite
      // with two overridden scenarios, that check the correct "empty" properties types.
      CommunityGeneratorTest.class,
      DetachedGraphTest.class,
      DetachedEdgeTest.class,
      DetachedVertexPropertyTest.class,
      DetachedPropertyTest.class,
      DetachedVertexTest.class,
      DistributionGeneratorTest.class,
      EdgeTest.class,
      FeatureSupportTest.class,
      IoCustomTest.class,
      IoEdgeTest.class,
      IoGraphTest.class,
      IoVertexTest.class,
      IoPropertyTest.class,
      GraphTest.class,
      GraphConstructionTest.class,
      IoTest.class,

      VertexPropertyTest.BasicVertexProperty.class,
      VertexPropertyTest.VertexPropertyAddition.class,
      VertexPropertyTest.VertexPropertyRemoval.class,
      YTDBVertexPropertyPropertiesStructureTest.class, // overridden

      VariablesTest.class,

      YTDBBasicPropertyStructureTest.class, // overridden
      PropertyTest.PropertyValidationOnAddExceptionConsistencyTest.class,
      PropertyTest.ElementGetValueExceptionConsistencyTest.class,
      PropertyTest.PropertyValidationOnSetExceptionConsistencyTest.class,
      PropertyTest.PropertyFeatureSupportTest.class,

      ReferenceGraphTest.class,
      ReferenceEdgeTest.class,
      ReferenceVertexPropertyTest.class,
      ReferenceVertexTest.class,
      SerializationTest.class,
      StarGraphTest.class,
      TransactionTest.class,
      TransactionMultiThreadedTest.class,
      VertexTest.class,

      // YTDB custom scenarios
      YTDBPropertiesStructureTest.class,
  };

  public YTDBStructureSuite(Class<?> klass, RunnerBuilder builder) throws InitializationError {
    super(klass, builder, testsToExecute, testsToExecute, true, Type.STANDARD);
  }
}
