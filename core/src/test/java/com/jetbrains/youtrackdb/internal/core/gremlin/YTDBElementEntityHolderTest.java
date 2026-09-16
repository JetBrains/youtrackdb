package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

public class YTDBElementEntityHolderTest extends GraphBaseTest {

  @Test
  public void projectionEntitiesAreCollectibleWhileWorkerThreadRemainsAlive() throws Exception {
    createProjectionFixture();
    var workerReady = new CountDownLatch(1);
    var releaseWorker = new CountDownLatch(1);
    var references = new AtomicReference<List<WeakReference<Entity>>>();
    var workerFailure = new AtomicReference<Throwable>();

    var worker = new Thread(() -> {
      try {
        references.set(loadProjectionEntitiesWithoutKeepingWrappers());
      } catch (Throwable failure) {
        workerFailure.set(failure);
        return;
      } finally {
        workerReady.countDown();
      }
      await(releaseWorker);
    });
    worker.start();

    try {
      assertTrue(workerReady.await(10, TimeUnit.SECONDS));
      assertNull(workerFailure.get());
      awaitCollection(references.get());
    } finally {
      stopWorker(worker, releaseWorker);
    }
  }

  @Test
  public void liveProjectionWrapperRetainsAndReusesItsValidEntity() {
    createVertexFixture();
    var wrapper = projectedVertex();
    var entity = ((YTDBElementImpl) wrapper).getRawEntity();
    var entityReference = new WeakReference<>(entity);
    entity = null;

    var collectionWitness = collectionWitness();
    awaitCollection(List.of(collectionWitness));

    assertNotNull(entityReference.get());
    assertSame(entityReference.get(), ((YTDBElementImpl) wrapper).getRawEntity());
    graph.tx().rollback();
  }

  @Test
  public void sharedProjectionWrapperUsesDifferentEntitiesOnDifferentThreads() throws Exception {
    createVertexFixture();
    var wrapper = projectedVertex();
    graph.tx().rollback();

    var workersReady = new CountDownLatch(2);
    var releaseWorkers = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(
          () -> loadEntityAfterRelease(wrapper, workersReady, releaseWorkers));
      var second = executor.submit(
          () -> loadEntityAfterRelease(wrapper, workersReady, releaseWorkers));

      assertTrue(workersReady.await(10, TimeUnit.SECONDS));
      releaseWorkers.countDown();
      assertNotSame(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertEquals(2, entityHolders((YTDBElementImpl) wrapper).size());
    } finally {
      releaseWorkers.countDown();
    }
  }

  @Test
  public void projectionWrapperReloadsAfterCommitAndRollback() {
    createVertexFixture();
    var wrapper = projectedVertex();

    var beforeCommit = ((YTDBElementImpl) wrapper).getRawEntity();
    graph.tx().commit();
    wrapper.label();
    var afterCommit = ((YTDBElementImpl) wrapper).getRawEntity();
    assertNotSame(beforeCommit, afterCommit);

    graph.tx().rollback();
    wrapper.label();
    var afterRollback = ((YTDBElementImpl) wrapper).getRawEntity();
    assertNotSame(afterCommit, afterRollback);
    graph.tx().rollback();
  }

  @Test
  public void projectionWrapperPreservesMutationAcrossRepeatedAccess() {
    createVertexFixture();
    var wrapper = projectedVertex();

    wrapper.property("value", "changed");
    assertEquals("changed", wrapper.value("value"));
    assertSame(
        ((YTDBElementImpl) wrapper).getRawEntity(),
        ((YTDBElementImpl) wrapper).getRawEntity());
    graph.tx().commit();

    assertEquals("changed", wrapper.value("value"));
    graph.tx().rollback();
  }

  @Test
  public void projectionWrapperReloadsForSuspendedTransaction() {
    createVertexFixture();
    var wrapper = projectedVertex();
    var outerEntity = ((YTDBElementImpl) wrapper).getRawEntity();

    var innerEntity = graph.withSuspendedTransaction(() -> {
      wrapper.label();
      var entity = ((YTDBElementImpl) wrapper).getRawEntity();
      graph.tx().rollback();
      return entity;
    });

    assertNotSame(outerEntity, innerEntity);
    assertSame(outerEntity, ((YTDBElementImpl) wrapper).getRawEntity());
    graph.tx().rollback();
  }

  @Test
  public void fullRecordWrapperKeepsDirectEntityPath() throws Exception {
    createVertexFixture();
    var wrapper = (YTDBElementImpl) graph.traversal().yql("SELECT FROM HolderVertex").next();
    var fastPathEntity = fastPathEntityField().get(wrapper);

    assertNotNull(fastPathEntity);
    assertSame(fastPathEntity, wrapper.getRawEntity());
    assertNull(holderInfrastructureField().get(wrapper));
    graph.tx().rollback();
  }

  @Test
  public void collectedThreadCleanupIsIncrementalAndBounded() throws Exception {
    createVertexFixture();
    var wrapper = (YTDBElementImpl) projectedVertex();
    wrapper.getRawEntity();
    var holders = entityHolders(wrapper);
    var holder = holders.values().iterator().next();
    var threadReferenceConstructor = threadReferenceConstructor();
    var queue = collectedThreads(wrapper);
    var collectedReferences = new ArrayList<Reference<Thread>>();

    for (var i = 0; i < 40; i++) {
      var reference = (Reference<Thread>) threadReferenceConstructor.newInstance(
          new Thread(), queue);
      holders.put(reference, holder);
      assertTrue(reference.enqueue());
      collectedReferences.add(reference);
    }

    var initialSize = holders.size();
    wrapper.getRawEntity();
    assertEquals(initialSize - 16, holders.size());
    wrapper.getRawEntity();
    assertEquals(initialSize - 32, holders.size());
    assertEquals(40, collectedReferences.size());
    graph.tx().rollback();
  }

  private List<WeakReference<Entity>> loadProjectionEntitiesWithoutKeepingWrappers() {
    var vertex = projectedVertex();
    var edge = projectedEdge();
    assertEquals("HolderVertex", vertex.label());
    assertEquals("HolderEdge", edge.label());

    var references = List.of(
        new WeakReference<>(((YTDBElementImpl) vertex).getRawEntity()),
        new WeakReference<>(((YTDBElementImpl) edge).getRawEntity()));
    graph.tx().rollback();
    return references;
  }

  private Entity loadEntityAfterRelease(
      Vertex wrapper, CountDownLatch workersReady, CountDownLatch releaseWorkers) {
    workersReady.countDown();
    await(releaseWorkers);
    wrapper.label();
    var entity = ((YTDBElementImpl) wrapper).getRawEntity();
    graph.tx().rollback();
    return entity;
  }

  private void createProjectionFixture() {
    session.getSchema().createVertexClass("HolderVertex");
    session.getSchema().createEdgeClass("HolderEdge");
    var first = graph.addVertex(T.label, "HolderVertex", "value", "first");
    var second = graph.addVertex(T.label, "HolderVertex", "value", "second");
    first.addEdge("HolderEdge", second);
    graph.tx().commit();
  }

  private void createVertexFixture() {
    session.getSchema().createVertexClass("HolderVertex");
    graph.addVertex(T.label, "HolderVertex", "value", "original");
    graph.tx().commit();
  }

  private Vertex projectedVertex() {
    return (Vertex) singleProjection("SELECT @rid AS rid FROM HolderVertex").get("rid");
  }

  private Edge projectedEdge() {
    return (Edge) singleProjection("SELECT @rid AS rid FROM HolderEdge").get("rid");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> singleProjection(String query) {
    return (Map<String, Object>) graph.traversal().yql(query).next();
  }

  private static WeakReference<Object> collectionWitness() {
    return new WeakReference<>(new Object());
  }

  private static void awaitCollection(List<? extends WeakReference<?>> references) {
    for (var attempt = 0; attempt < 100; attempt++) {
      if (references.stream().allMatch(reference -> reference.get() == null)) {
        return;
      }

      System.gc();
      var pressure = new byte[1024 * 1024];
      pressure[0] = 1;
    }

    assertTrue(
        "The garbage collector did not collect all observed objects",
        references.stream().allMatch(reference -> reference.get() == null));
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(20, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static void stopWorker(Thread worker, CountDownLatch releaseWorker) {
    releaseWorker.countDown();
    var interrupted = joinWorker(worker);
    if (worker.isAlive()) {
      worker.interrupt();
      interrupted |= joinWorker(worker);
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    assertFalse(worker.isAlive());
  }

  private static boolean joinWorker(Thread worker) {
    try {
      worker.join(10_000);
      return false;
    } catch (InterruptedException interrupted) {
      return true;
    }
  }

  private static Field fastPathEntityField() throws Exception {
    var field = YTDBElementImpl.class.getDeclaredField("fastPathEntity");
    field.setAccessible(true);
    return field;
  }

  private static Field holderInfrastructureField() throws Exception {
    var field = YTDBElementImpl.class.getDeclaredField("holderInfrastructure");
    field.setAccessible(true);
    return field;
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, Object> entityHolders(YTDBElementImpl wrapper) throws Exception {
    var infrastructure = holderInfrastructureField().get(wrapper);
    var field = infrastructure.getClass().getDeclaredField("entityHolders");
    field.setAccessible(true);
    return (Map<Object, Object>) field.get(infrastructure);
  }

  private static Object collectedThreads(YTDBElementImpl wrapper) throws Exception {
    var infrastructure = holderInfrastructureField().get(wrapper);
    var field = infrastructure.getClass().getDeclaredField("collectedThreads");
    field.setAccessible(true);
    return field.get(infrastructure);
  }

  private static Constructor<?> threadReferenceConstructor() throws Exception {
    var type = Class.forName(YTDBElementImpl.class.getName() + "$ThreadReference");
    var constructor = type.getDeclaredConstructor(Thread.class, java.lang.ref.ReferenceQueue.class);
    constructor.setAccessible(true);
    return constructor;
  }
}
