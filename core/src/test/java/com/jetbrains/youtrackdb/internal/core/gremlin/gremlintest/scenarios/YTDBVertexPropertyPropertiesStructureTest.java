package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.scenarios;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import org.apache.tinkerpop.gremlin.FeatureRequirement;
import org.apache.tinkerpop.gremlin.FeatureRequirementSet;
import org.apache.tinkerpop.gremlin.FeatureRequirementSet.Package;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality;
import org.apache.tinkerpop.gremlin.structure.VertexPropertyTest;
import org.junit.Assert;
import org.junit.Test;

/// VertexPropertyProperties test with corrected "empty" properties logic.
public class YTDBVertexPropertyPropertiesStructureTest
    extends VertexPropertyTest.VertexPropertyProperties {

  @Test
  @FeatureRequirementSet(Package.VERTICES_ONLY)
  @FeatureRequirement(
      featureClass = Graph.Features.VertexFeatures.class,
      feature = "MetaProperties"
  )
  @Override
  public void shouldReturnEmptyIfNoMetaProperties() {
    Vertex v = this.graph.addVertex(new Object[0]);
    VertexProperty<String> vp = v.property(Cardinality.single, "name", "marko", new Object[0]);
    // using YTDBProperty.empty() instead of Property.empty()
    Assert.assertEquals(YTDBProperty.empty(), vp.property("name"));
  }
}
