package com.jetbrains.youtrackdb.internal.server.plugin.gremlin.process;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.stream.IntStream;
import org.apache.tinkerpop.gremlin.AbstractGremlinTest;
import org.apache.tinkerpop.gremlin.FeatureRequirement;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Graph.Features.VertexFeatures;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@SuppressWarnings("AbstractClassWithOnlyOneDirectInheritor")
@RunWith(GremlinProcessRunner.class)
public abstract class YTDBTemporaryRidConversionTest extends AbstractGremlinTest {

  public abstract Traversal<?, ?> get_g_addV_repeat128_with_fail_in_first_step(int firstSteps);

  public abstract Traversal<?, ?> get_g_addV_repeat128_with_fail_in_second_step(int firstSteps);

  public abstract Traversal<?, ?> get_g_addV_repeat128_in_two_steps(int firstSteps);

  public abstract Traversal<?, Vertex> get_g_createGraph_v();

  public abstract Traversal<?, Edge> get_g_createGraph_e();

  public abstract Traversal<?, ? extends Property<?>> get_g_createGraph_p();

  @Test
  @LoadGraphWith(MODERN)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
  public void g_V_addV_repeat128_in_tx() {
    for (var i = 1; i < 128; i++) {
      long initialCount = g.V().count().next();
      var traversal = get_g_addV_repeat128_in_two_steps(i);
      var values = traversal.toList();

      Assert.assertEquals(128, values.size());
      for (var v : values) {
        Assert.assertTrue(v instanceof Vertex);
        var vertex = (Vertex) v;
        var rid = (RecordId) vertex.id();
        Assert.assertTrue(rid.isPersistent());
      }
      assertEquals(initialCount + 128, g.V().count().next().longValue());
      initialCount += 128;

      traversal = get_g_addV_repeat128_with_fail_in_first_step(i);
      try {
        traversal.iterate();
        Assert.fail("Should fail");
      } catch (Exception e) {
        Assert.assertTrue(e.getMessage().contains("exception during vertex creation"));
      }

      assertEquals(initialCount, g.V().count().next().longValue());

      traversal = get_g_addV_repeat128_with_fail_in_second_step(i);
      try {
        traversal.iterate();
        Assert.fail("Should fail");
      } catch (Exception e) {
        Assert.assertTrue(e.getMessage().contains("exception during vertex creation"));
      }

      assertEquals(initialCount, g.V().count().next().longValue());
    }
  }

  @Test
  @LoadGraphWith(MODERN)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = VertexFeatures.FEATURE_ADD_PROPERTY)
  @FeatureRequirement(featureClass = Graph.Features.EdgeFeatures.class, feature = Graph.Features.EdgeFeatures.FEATURE_ADD_EDGES)
  public void g_createGraph_v() {
    var traversal = get_g_createGraph_v();
    var vertices = traversal.toList();

    for (var v : vertices) {
      var rid = (RID) v.id();
      Assert.assertTrue(rid.isPersistent());
    }
  }

  @Test
  @LoadGraphWith(MODERN)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = VertexFeatures.FEATURE_ADD_PROPERTY)
  @FeatureRequirement(featureClass = Graph.Features.EdgeFeatures.class, feature = Graph.Features.EdgeFeatures.FEATURE_ADD_EDGES)
  public void g_createGraph_e() {
    var traversal = get_g_createGraph_e();
    var edges = traversal.toList();

    for (var edge : edges) {
      var rid = (RID) edge.id();
      Assert.assertTrue(rid.isPersistent());
    }
  }

  @Test
  @LoadGraphWith(MODERN)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = VertexFeatures.FEATURE_ADD_PROPERTY)
  @FeatureRequirement(featureClass = Graph.Features.EdgeFeatures.class, feature = Graph.Features.EdgeFeatures.FEATURE_ADD_EDGES)
  public void g_createGraph_p() {
    var traversal = get_g_createGraph_p();
    var properties = traversal.toList();

    for (var property : properties) {
      var vertexProperty = (VertexProperty<?>) property;
      var propertyId = (YTDBVertexPropertyId) vertexProperty.id();
      var rid = (RecordId) propertyId.rid();
      Assert.assertTrue(rid.isPersistent());
    }
  }

  @SuppressWarnings("NewClassNamingConvention")
  public static class Traversals extends YTDBTemporaryRidConversionTest {

    @Override
    public Traversal<?, ?> get_g_addV_repeat128_with_fail_in_first_step(int firstSteps) {
      //noinspection unchecked
      return g.inject((Vertex) null).union(
          __.repeat(__.addV("person")).times(firstSteps).fail("exception during vertex creation"),
          __.repeat(__.addV("person")).times(128 - firstSteps)
      );
    }

    @Override
    public Traversal<?, ?> get_g_addV_repeat128_with_fail_in_second_step(int firstSteps) {
      //noinspection unchecked
      return g.inject((Vertex) null).union(
          __.repeat(__.addV("person")).times(firstSteps),
          __.repeat(__.addV("person")).times(128 - firstSteps)
              .fail("exception during vertex creation")
      );
    }

    @Override
    public Traversal<?, ?> get_g_addV_repeat128_in_two_steps(int firstSteps) {
      //noinspection unchecked
      return g.inject((Vertex) null).union(
          __.repeat(__.addV("person")).times(firstSteps).emit(),
          __.repeat(__.addV("person")).times(128 - firstSteps).emit()
      );
    }

    @Override
    public Traversal<?, Vertex> get_g_createGraph_v() {
      //noinspection unchecked
      return createGraph().union(__.select("from"), __.select("to"));
    }

    @Override
    public Traversal<?, Edge> get_g_createGraph_e() {
      return createGraph().select("e");
    }

    @Override
    public Traversal<?, ? extends Property<?>> get_g_createGraph_p() {
      //noinspection unchecked
      return createGraph().union(__.select("from"), __.select("to")).properties("property");
    }

    private GraphTraversal<?, ?> createGraph() {
      return g.inject(IntStream.rangeClosed(1, 128).toArray()).unfold().
          addV("person").property("property", "value").as("from").
          addV("person").property("property", "value").as("to").
          addE("knows").from("from").to("to").as("e");
    }
  }
}
