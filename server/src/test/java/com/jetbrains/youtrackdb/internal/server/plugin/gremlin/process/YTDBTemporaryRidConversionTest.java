package com.jetbrains.youtrackdb.internal.server.plugin.gremlin.process;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.apache.tinkerpop.gremlin.AbstractGremlinTest;
import org.apache.tinkerpop.gremlin.FeatureRequirement;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@SuppressWarnings("AbstractClassWithOnlyOneDirectInheritor")
@RunWith(GremlinProcessRunner.class)
public abstract class YTDBTemporaryRidConversionTest extends AbstractGremlinTest {

  public abstract Traversal<?, ?> get_g_addV_repeat128_with_fail_in_first_step(int firstSteps);

  public abstract Traversal<?, ?> get_g_addV_repeat128_with_fail_in_second_step(int firstSteps);

  public abstract Traversal<?, ?> get_g_addV_repeat128_in_two_steps(int firstSteps);

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
  }
}
