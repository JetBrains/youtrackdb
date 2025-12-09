package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;


public class GraphApiTest extends GraphBaseTest {
  @Test
  public void shouldGetEmptyEdges() {
    var vertex = graph.addVertex(T.label, "Person", "name", "Foo");
    var edges = vertex.edges(Direction.OUT, "HasFriend");
    graph.tx().commit();

    var collected = StreamUtils.asStream(edges).toList();
    Assert.assertEquals(0, collected.size());
  }

  @Test
  public void testLinklistProperty() {
    var vertex = graph.addVertex(T.label, "Person", "name", "Foo");
    var vertex2 = graph.addVertex(T.label, "Person", "name", "Bar");
    var vertex3 = graph.addVertex(T.label, "Person", "name", "Baz");
    graph.tx().commit();

    var listProp = new ArrayList<>();
    listProp.add(vertex2.id());
    listProp.add(vertex3.id());

    vertex.property("links", listProp);

    var retrieved = vertex.value("links");
    Assert.assertTrue(retrieved instanceof List);

    @SuppressWarnings("unchecked")
    var resultList = (List<Object>) retrieved;
    for (var o : resultList) {
      Assert.assertTrue(o instanceof RID);
    }
  }

  @Test
  public void testExecuteInTxCommitGraph() {
    graph.executeInTx(
        g -> Assert.assertEquals(0,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));

    graph.executeInTx(g -> g.addV("Person").property("name", "Constantin").next());

    graph.executeInTx(
        g -> Assert.assertEquals(1,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

  @Test
  public void testExecuteInTxRollbackGraph() {
    graph.executeInTx(
        g -> Assert.assertEquals(0,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));

    try {
      graph.executeInTx(g -> g.addV("Person").property("name", "Constantin").fail().next());
      Assert.fail("Should have thrown exception");
    } catch (Exception ignored) {
      //expected
    }

    graph.executeInTx(
        g -> Assert.assertEquals(0,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

  @Test
  public void testExecuteInTxCommitTraversal() {
    var g = graph.traversal();
    g.executeInTx(
        it -> Assert.assertEquals(0,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));

    g.executeInTx(it -> it.addV("Person").property("name", "Constantin").next());

    g.executeInTx(
        it -> Assert.assertEquals(1,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

  @Test
  public void testExecuteInTxRollbackTraversal() {
    var g = graph.traversal();
    g.executeInTx(
        it -> Assert.assertEquals(0,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));

    try {
      g.executeInTx(it -> it.addV("Person").property("name", "Constantin").fail().next());
      Assert.fail("Should have thrown exception");
    } catch (Exception ignored) {
      //expected
    }

    g.executeInTx(
        it -> Assert.assertEquals(0,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));
  }


  @Test
  public void testAutoExecuteInTxCommitGraph() {
    graph.executeInTx(
        g -> Assert.assertEquals(0,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));

    graph.autoExecuteInTx(g -> g.addV("Person").property("name", "Constantin"));

    graph.executeInTx(
        g -> Assert.assertEquals(1,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

  @Test
  public void testAutoExecuteInTxRollbackGraph() {
    graph.executeInTx(
        g -> Assert.assertEquals(0,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));

    try {
      graph.autoExecuteInTx(g -> g.addV("Person").property("name", "Constantin").fail());
      Assert.fail("Should have thrown exception");
    } catch (Exception ignored) {
      //expected
    }

    graph.executeInTx(
        g -> Assert.assertEquals(0,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

  @Test
  public void testAutoExecuteInTxCommitTraversal() {
    var g = graph.traversal();
    g.executeInTx(
        it -> Assert.assertEquals(0,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));

    g.autoExecuteInTx(it -> it.addV("Person").property("name", "Constantin"));

    g.executeInTx(
        it -> Assert.assertEquals(1,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

  @Test
  public void testAutoExecuteInTxRollbackTraversal() {
    var g = graph.traversal();
    g.executeInTx(
        it -> Assert.assertEquals(0,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));

    try {
      g.autoExecuteInTx(it -> it.addV("Person").property("name", "Constantin").fail());
      Assert.fail("Should have thrown exception");
    } catch (Exception ignored) {
      //expected
    }

    g.executeInTx(
        it -> Assert.assertEquals(0,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));
  }


  @Test
  public void testComputeInTxAndCommitGraph() {
    graph.executeInTx(
        g -> Assert.assertEquals(0,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));

    var addedVertices = graph.computeInTx(g ->
        (Long) g.inject(1).project("added_vertex", "count").
            by(__.addV("Person").property("name", "Constantin")).
            by(__.V().has("Person", "name", "Constantin").count()).select("count").next());
    Assert.assertEquals(1, addedVertices.longValue());

    graph.executeInTx(
        g -> Assert.assertEquals(1,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

  @Test
  public void testComputeInTxAndRollbackGraph() {
    graph.executeInTx(
        g -> Assert.assertEquals(0,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));

    try {
      graph.computeInTx(g ->
          (Long) g.inject(1).project("added_vertex", "count").
              by(__.addV("Person").property("name", "Constantin")).
              by(__.V().has("Person", "name", "Constantin").count()).select("count").fail().next());
      Assert.fail("Should have thrown exception");
    } catch (Exception ignored) {
      //expected
    }

    graph.executeInTx(
        g -> Assert.assertEquals(0,
            g.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

  @Test
  public void testComputeInTxAndCommitTraversal() {
    var g = graph.traversal();
    g.executeInTx(
        it -> Assert.assertEquals(0,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));

    var addedVertices = g.computeInTx(it ->
        (Long) it.inject(1).project("added_vertex", "count").
            by(__.addV("Person").property("name", "Constantin")).
            by(__.V().has("Person", "name", "Constantin").count()).select("count").next());
    Assert.assertEquals(1, addedVertices.longValue());

    g.executeInTx(
        it -> Assert.assertEquals(1,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

  @Test
  public void testComputeInTxAndRollbackTraversal() {
    var g = graph.traversal();
    g.executeInTx(
        it -> Assert.assertEquals(0,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));

    try {
      g.computeInTx(it ->
          (Long) it.inject(1).project("added_vertex", "count").
              by(__.addV("Person").property("name", "Constantin")).
              by(__.V().has("Person", "name", "Constantin").count()).select("count").fail().next());
      Assert.fail("Should have thrown exception");
    } catch (Exception ignored) {
      //expected
    }

    g.executeInTx(
        it -> Assert.assertEquals(0,
            it.V().has("Person", "name", "Constantin").count().next().longValue()));
  }

}
