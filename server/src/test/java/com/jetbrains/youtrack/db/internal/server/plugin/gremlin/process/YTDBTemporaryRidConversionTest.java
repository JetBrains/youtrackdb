package com.jetbrains.youtrack.db.internal.server.plugin.gremlin.process;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import org.apache.tinkerpop.gremlin.AbstractGremlinTest;
import org.apache.tinkerpop.gremlin.FeatureRequirement;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@SuppressWarnings("AbstractClassWithOnlyOneDirectInheritor")
@RunWith(GremlinProcessRunner.class)
public abstract class YTDBTemporaryRidConversionTest extends AbstractGremlinTest {

  public abstract Traversal<?, ?> get_g_addV_repeat128();

  @Test
  @LoadGraphWith(MODERN)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_PROPERTY)
  public void g_V_addV_repeat128() {
    var initialCount = IteratorUtils.count(g.V());
    var traversal = get_g_addV_repeat128();
    var values = traversal.toList();

    for (var v : values) {
      Assert.assertTrue(v instanceof Vertex);
      var vertex = (Vertex) v;
      var rid = (RecordId) vertex.id();
      Assert.assertTrue(rid.isPersistent());
    }

    assertEquals(initialCount + 128, IteratorUtils.count(g.V()));
  }

  @SuppressWarnings("NewClassNamingConvention")
  public static class Traversals extends YTDBTemporaryRidConversionTest {

    @Override
    public Traversal<?, ?> get_g_addV_repeat128() {
      return g.inject((Vertex) null).repeat(__.addV("person")).times(128).emit();
    }
  }
}
