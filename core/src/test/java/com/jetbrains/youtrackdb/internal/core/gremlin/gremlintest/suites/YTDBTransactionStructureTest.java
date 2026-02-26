package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest.suites;

import static org.apache.tinkerpop.gremlin.structure.Graph.Features.EdgePropertyFeatures;
import static org.apache.tinkerpop.gremlin.structure.Graph.Features.VertexPropertyFeatures.FEATURE_DOUBLE_VALUES;
import static org.apache.tinkerpop.gremlin.structure.Graph.Features.VertexPropertyFeatures.FEATURE_INTEGER_VALUES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tinkerpop.gremlin.FeatureRequirement;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.TransactionTest;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.junit.Test;

/// Overrides {@link TransactionTest#shouldExecuteWithCompetingThreads()} to replace
/// the bare spin-loop with a bounded {@link CountDownLatch#await} call.
///
/// The upstream implementation uses {@code while (completedThreads.get() < totalThreads) {}}
/// which spins forever if any thread dies from an uncaught exception without incrementing
/// the counter. This causes a 60-minute watchdog timeout and JVM kill on CI.
///
/// The fix uses a {@link CountDownLatch} that is always decremented in a {@code finally}
/// block, so thread failures cannot cause the main thread to hang.
public class YTDBTransactionStructureTest extends TransactionTest {

  @Test
  @Override
  @FeatureRequirement(featureClass = Graph.Features.GraphFeatures.class, feature = Graph.Features.GraphFeatures.FEATURE_TRANSACTIONS)
  @FeatureRequirement(featureClass = Graph.Features.EdgeFeatures.class, feature = Graph.Features.EdgeFeatures.FEATURE_ADD_EDGES)
  @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
  @FeatureRequirement(featureClass = Graph.Features.VertexPropertyFeatures.class, feature = FEATURE_DOUBLE_VALUES)
  @FeatureRequirement(featureClass = Graph.Features.VertexPropertyFeatures.class, feature = FEATURE_INTEGER_VALUES)
  @FeatureRequirement(featureClass = Graph.Features.EdgePropertyFeatures.class, feature = EdgePropertyFeatures.FEATURE_FLOAT_VALUES)
  @FeatureRequirement(featureClass = Graph.Features.EdgePropertyFeatures.class, feature = EdgePropertyFeatures.FEATURE_INTEGER_VALUES)
  public void shouldExecuteWithCompetingThreads() {
    int totalThreads = 250;
    final AtomicInteger vertices = new AtomicInteger(0);
    final AtomicInteger edges = new AtomicInteger(0);
    final CountDownLatch latch = new CountDownLatch(totalThreads);
    for (int i = 0; i < totalThreads; i++) {
      new Thread() {
        @Override
        public void run() {
          try {
            final Random random = new Random();
            if (random.nextBoolean()) {
              final Vertex a = graph.addVertex();
              final Vertex b = graph.addVertex();
              final Edge e = a.addEdge("friend", b);

              vertices.getAndAdd(2);
              a.property(VertexProperty.Cardinality.single, "test", this.getId());
              b.property(VertexProperty.Cardinality.single, "blah", random.nextDouble());
              e.property("bloop", random.nextInt());
              edges.getAndAdd(1);
              graph.tx().commit();
            } else {
              final Vertex a = graph.addVertex();
              final Vertex b = graph.addVertex();
              final Edge e = a.addEdge("friend", b);

              a.property(VertexProperty.Cardinality.single, "test", this.getId());
              b.property(VertexProperty.Cardinality.single, "blah", random.nextDouble());
              e.property("bloop", random.nextInt());

              if (random.nextBoolean()) {
                graph.tx().commit();
                vertices.getAndAdd(2);
                edges.getAndAdd(1);
              } else {
                graph.tx().rollback();
              }
            }
          } finally {
            latch.countDown();
          }
        }
      }.start();
    }

    try {
      if (!latch.await(5, TimeUnit.MINUTES)) {
        fail("Timed out waiting for " + latch.getCount()
            + " of " + totalThreads + " threads to complete");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("Interrupted while waiting for competing threads");
    }

    assertEquals(totalThreads - (int) latch.getCount(), totalThreads);
    assertVertexEdgeCounts(graph, vertices.get(), edges.get());
  }
}
