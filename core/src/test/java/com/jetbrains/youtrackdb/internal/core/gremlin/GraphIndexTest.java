package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.exception.RecordDuplicatedException;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

public class GraphIndexTest extends GraphBaseTest {

  String vertexLabel1 = "SomeVertexLabel1";
  String vertexLabel2 = "SomeVertexLabel2";

  String edgeLabel1 = "SomeEdgeLabel1";
  String edgeLabel2 = "SomeEdgeLabel2";

  String key = "indexedKey";

  @Test
  public void vertexUniqueConstraint() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass(vertexLabel1).createSchemaProperty(key, PropertyType.STRING)
            .createPropertyIndex("vIndex", IndexType.UNIQUE));

    var value = "value1";
    graph.addVertex(T.label, vertexLabel1, key, value);
    graph.addVertex(T.label, vertexLabel2, key, value);

    // no duplicates allowed for vertex with label1
    try {
      graph.addVertex(T.label, vertexLabel1, key, value);
      graph.tx().commit();
      Assert.fail("must throw duplicate key here!");
    } catch (RecordDuplicatedException e) {
      // ok
    }

    // allow duplicate for vertex with label2
    graph.addVertex(T.label, vertexLabel2, key, value);
    graph.tx().commit();
  }

  @Test
  public void edgeUniqueConstraint() {
    graph.autoExecuteInTx(g ->
        g.createStateFullEdgeClass(edgeLabel1).createSchemaProperty(key, PropertyType.STRING)
            .createPropertyIndex("eIndex", IndexType.UNIQUE));

    var value = "value1";

    var v1 = graph.addVertex(T.label, vertexLabel1);
    var v2 = graph.addVertex(T.label, vertexLabel1);
    v1.addEdge(edgeLabel1, v2, key, value);

    // no duplicates allowed for edge with label1
    try {
      v1.addEdge(edgeLabel1, v2, key, value);
      graph.tx().commit();
      Assert.fail("must throw duplicate key here!");
    } catch (RecordDuplicatedException e) {
      // ok
    }

    v1 = graph.addVertex(T.label, vertexLabel1);
    v2 = graph.addVertex(T.label, vertexLabel1);
    // allow duplicate for vertex with label2
    v2.addEdge(edgeLabel2, v1, key, value);
    graph.tx().commit();
  }

  @Test
  public void vertexUniqueIndexLookupWithValue() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass(vertexLabel1).createSchemaProperty(key, PropertyType.STRING)
            .createPropertyIndex("vIndex", IndexType.NOT_UNIQUE));

    var value = "value1";

    var v1 = graph.addVertex(T.label, vertexLabel1, key, value);
    graph.addVertex(T.label, vertexLabel2, key, value);
    graph.tx().commit();

    var traversal =
        graph.traversal().V().has(T.label, P.eq(vertexLabel1)).has(key, P.eq(value));
    Assert.assertEquals(1, usedIndexes(session, traversal));

    var result = traversal.toList();
    Assert.assertEquals(1, result.size());
    Assert.assertEquals(v1.id(), result.getFirst().id());
  }

  @Test
  public void vertexUniqueIndexLookupWithValueInMidTraversal() {
    graph.autoExecuteInTx(
        g -> g.createSchemaClass(vertexLabel1).createSchemaProperty(key, PropertyType.STRING)
            .createPropertyIndex("vIndex1", IndexType.NOT_UNIQUE));

    graph.autoExecuteInTx(g ->
        g.createSchemaClass(vertexLabel2).createSchemaProperty(key, PropertyType.STRING)
            .createPropertyIndex("vIndex2", IndexType.NOT_UNIQUE));

    var value = "value1";

    graph.addVertex(T.label, vertexLabel1, key, value);
    graph.addVertex(T.label, vertexLabel2, key, value);
    graph.tx().commit();

    // looking deep into the internals here - I can't find a nicer way to
    // auto verify that an index is actually used
    var traversal =
        graph
            .traversal()
            .V()
            .has(T.label, P.eq(vertexLabel1))
            .has(key, P.eq(value))
            .as("first")
            .V()
            .has(T.label, P.eq(vertexLabel2))
            .has(key, P.eq(value))
            .as("second")
            .addE(edgeLabel1);

    Assert.assertEquals(2, usedIndexes(session, traversal));

    var result = traversal.toList();
    Assert.assertEquals(1, result.size());

    Assert.assertEquals(edgeLabel1, result.getFirst().label());
  }

  @Test
  public void vertexUniqueIndexLookupWithMultipleLabels() {
    final var label1 = "label1";
    final var label2 = "label2";
    final var label3 = "label3";

    final var value1 = "value1";

    graph.executeInTx(g -> {
      g.createSchemaClass(label1).createSchemaProperty(key, PropertyType.STRING)
          .createPropertyIndex("vIndex", IndexType.NOT_UNIQUE).iterate();
      g.createSchemaClass(label2).createSchemaProperty(key, PropertyType.STRING)
          .createPropertyIndex("vIndex2", IndexType.NOT_UNIQUE).iterate();
      g.createSchemaClass(label3).createSchemaProperty(key, PropertyType.STRING)
          .createPropertyIndex("vIndex3", IndexType.NOT_UNIQUE).iterate();
        }
    );

    graph.addVertex(T.label, label1, key, value1);
    graph.addVertex(T.label, label2, key, value1);
    graph.addVertex(T.label, label3, key, value1);
    graph.tx().commit();

    var traversal =
        graph.traversal().V().hasLabel(label1, label2, label3).has(key, value1);

    Assert.assertEquals(3, usedIndexes(session, traversal));
  }


  // TODO Enable when it's fixed
  //  @Test
  public void vertexUniqueIndexLookupWithMultipleValues() {
    graph.autoExecuteInTx(
        g -> g.createSchemaClass(vertexLabel1).createSchemaProperty(key, PropertyType.STRING).
            createPropertyIndex("vIndex", IndexType.NOT_UNIQUE));

    var value1 = "value1";
    var value2 = "value2";
    var value3 = "value3";

    var v1 = graph.addVertex(T.label, vertexLabel1, key, value1);
    var v2 = graph.addVertex(T.label, vertexLabel1, key, value2);
    graph.addVertex(T.label, vertexLabel1, key, value3);
    graph.tx().commit();

    var traversal =
        graph.traversal().V().has(T.label, P.eq(vertexLabel1)).has(key, P.within(value1, value2));
    Assert.assertEquals(1, usedIndexes(session, traversal));

    var result = traversal.toList();
    Assert.assertEquals(2, result.size());
    Assert.assertEquals(v1.id(), result.get(0).id());
    Assert.assertEquals(v2.id(), result.get(1).id());
  }

  @Test
  public void edgeUniqueIndexLookupWithValue() {
    graph.autoExecuteInTx(
        g -> g.createStateFullEdgeClass(edgeLabel1).
            createSchemaProperty(key, PropertyType.STRING)
            .createPropertyIndex("vIndex", IndexType.UNIQUE));

    var value = "value1";

    var v1 = graph.addVertex(T.label, vertexLabel1);
    var v2 = graph.addVertex(T.label, vertexLabel1);
    v1.addEdge(edgeLabel1, v2, key, value);
    var e2 = v1.addEdge(edgeLabel2, v2, key, value);
    graph.tx().commit();

    {
      // Verify that the traversal hits the index for the edges with label1
      var traversal1 =
          graph.traversal().E().has(T.label, P.eq(edgeLabel1)).has(key, P.eq(value));

      Assert.assertEquals(1, usedIndexes(session, traversal1));

      {
        // Verify that the traversal doesn't try to hit the index for the edges with label2
        var traversal2 =
            graph.traversal().E().has(T.label, P.eq(edgeLabel2)).has(key, P.eq(value));

        Assert.assertEquals(0, usedIndexes(session, traversal2));

        var result2 = traversal2.toList();
        Assert.assertEquals(1, result2.size());
        Assert.assertEquals(e2.id(), result2.getFirst().id());
      }
    }
  }

  @Test
  public void edgeNotUniqueIndexLookupWithValue() {
    graph.autoExecuteInTx(
        g -> g.createStateFullEdgeClass(edgeLabel1).createSchemaProperty(key, PropertyType.STRING)
            .createPropertyIndex("vIndex", IndexType.NOT_UNIQUE));

    var value = "value1";

    var v1 = graph.addVertex(T.label, vertexLabel1);
    var v2 = graph.addVertex(T.label, vertexLabel1);
    v1.addEdge(edgeLabel1, v2, key, value);
    v1.addEdge(edgeLabel1, v2, key, value);
    v1.addEdge(edgeLabel1, v2);
    graph.tx().commit();

    // Verify that the traversal hits the index for the edges with label1
    var traversal1 =
        graph.traversal().E().has(T.label, P.eq(edgeLabel1)).has(key, P.eq(value));
    Assert.assertEquals(1, usedIndexes(session, traversal1));
  }
}
