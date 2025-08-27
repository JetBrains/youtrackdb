package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBQueryConfigParam;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;


public class GraphQueryTest extends GraphBaseTest {

  @Test
  public void shouldCountVerticesEdges() {
    initGraph(graph);

    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();
    Assert.assertEquals(4L, count.longValue());

    count = graph.traversal().V().hasLabel("Person").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().V().hasLabel("Animal").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().V().hasLabel("Animal", "Person").count().toList().getFirst();
    Assert.assertEquals(4L, count.longValue());

    // Count on E

    count = graph.traversal().E().count().toList().getFirst();
    Assert.assertEquals(3L, count.longValue());

    count = graph.traversal().E().hasLabel("HasFriend").count().toList().getFirst();
    Assert.assertEquals(1L, count.longValue());

    count = graph.traversal().E().hasLabel("HasAnimal").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().E().hasLabel("HasAnimal", "HasFriend").count().toList().getFirst();
    Assert.assertEquals(3L, count.longValue());

    // Inverted Count

    count = graph.traversal().V().hasLabel("HasFriend").count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());

    count = graph.traversal().E().hasLabel("Person").count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());

    // More Complex Count

    count =
        graph
            .traversal()
            .V()
            .has("Person", "name", "Jon")
            .out("HasFriend", "HasAnimal")
            .count()
            .toList()
            .getFirst();
    Assert.assertEquals(2L, count.longValue());

    // With Polymorphism

    count = graph.traversal().V().has("Person", "name", "Jon").
        out("E").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    // With Base Class V/E

    count = graph.traversal().V().has("name", "Jon").count().toList().getFirst();
    Assert.assertEquals(1L, count.longValue());

    count = graph.traversal().E().has("name", "Jon").count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());

    count = graph.traversal().V().has("name", "Jon").out("E").count().toList().getFirst();
    Assert.assertEquals(2L, count.longValue());

    count = graph.traversal().E().has("marker", 10).count().toList().getFirst();
    Assert.assertEquals(1L, count.longValue());

    count = graph.traversal().V().has("marker", 10).count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());
  }

  @Test
  public void shouldCountVerticesEdgesOnTXRollback() {
    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex("name", "Jon");

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());

    graph.tx().rollback();

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());
  }

  @Test
  public void shouldExecuteTraversalWithSpecialCharacters() {
    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex("identifier", 1);

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());

    graph.tx().commit();

    count = graph.traversal().V().has("~identifier", 1).count().toList().getFirst();
    Assert.assertEquals(0L, count.longValue());
  }

  @Test
  public void shouldNotBlowWithWrongClass() {
    initGraph(graph);

    var count = graph.traversal().V().hasLabel("Wrong").toList().size();

    Assert.assertEquals(0, count);

    // Count on Person + Wrong Class

    count = graph.traversal().V().hasLabel("Person", "Wrong").toList().size();

    Assert.assertEquals(2, count);
  }

  @Test
  public void hasIdWithString() {
    final var labelVertex = "VertexLabel";
    var v1 = graph.addVertex(labelVertex);

    graph.tx().commit();

    Assert.assertEquals(1, graph.traversal().V().hasId(v1.id()).toList().size());
  }

  @Test
  @Ignore
  public void hasIdWithVertex() {
    final var labelVertex = "VertexLabel";
    var v1 = graph.addVertex(labelVertex);

    graph.tx().commit();

    Assert.assertEquals(1, graph.traversal().V().hasId(v1).toList().size());
  }

  @Test
  public void shouldCountVerticesEdgesOnTXCommit() {
    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex("name", "Jon");

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());

    graph.tx().commit();

    count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(1L, count.longValue());
  }

  @Test
  public void shouldWorkWithTwoLabels() {
    session.getSchema().createVertexClass("Person");

    // Count on V
    var count = graph.traversal().V().count().toList().getFirst();

    Assert.assertEquals(0L, count.longValue());

    graph.addVertex(T.label, "Person", "name", "Jon");

    count =
        graph
            .traversal()
            .V()
            .hasLabel("Person")
            .has("name", "Jon")
            .hasLabel("Person")
            .has("name", "Jon")
            .count()
            .toList()
            .getFirst();

    Assert.assertEquals(1L, count.longValue());
  }

  protected void initGraph(Graph graph) {
    var schema = session.getSchema();

    schema.createVertexClass("Person");
    schema.createVertexClass("Animal");
    schema.createEdgeClass("HasFriend");
    session.createEdgeClass("HasAnimal");

    var v1 = graph.addVertex(T.label, "Person", "name", "Jon");
    var v2 = graph.addVertex(T.label, "Person", "name", "Frank");

    v1.addEdge("HasFriend", v2);

    var v3 = graph.addVertex(T.label, "Animal", "name", "Foo");
    var v4 = graph.addVertex(T.label, "Animal", "name", "Bar");

    v1.addEdge("HasAnimal", v3, "marker", 10);
    v2.addEdge("HasAnimal", v4);
  }

  @Test
  public void testPolymorphicLabels() {

    //            animal
    //     /     /      \      \
    // fish    mammal   insect  bird
    //        /     \     \
    //      cat    human   bee

    session.createVertexClass("animal");
    session.createClass("fish", "animal");
    session.createClass("mammal", "animal");
    session.createClass("bird", "animal");
    session.createClass("insect", "animal");
    session.createClass("bee", "insect");
    session.createClass("cat", "mammal");
    session.createClass("human", "mammal");

    session.executeInTx(tx -> {
      tx.newVertex("animal").setProperty("name", "someAnimal");

      tx.newVertex("fish").setProperty("name", "someFish");

      tx.newVertex("cat").setProperty("name", "someCat");

      tx.newVertex("cat").setProperty("name", "otherCat");

      tx.newVertex("insect").setProperty("name", "someInsect");
    });

    assertThat(queryNames("animal", true))
        .containsExactlyInAnyOrder("someAnimal", "someFish", "someCat", "otherCat", "someInsect");

    assertThat(queryNames("animal", false))
        .containsExactlyInAnyOrder("someAnimal");

    assertThat(queryNames("fish", true))
        .containsExactlyInAnyOrder("someFish");

    assertThat(queryNames("fish", false))
        .containsExactlyInAnyOrder("someFish");

    assertThat(queryNames("mammal", true))
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThat(queryNames("mammal", false))
        .isEmpty();

    assertThat(queryNames("insect", true))
        .containsExactlyInAnyOrder("someInsect");

    assertThat(queryNames("insect", false))
        .containsExactlyInAnyOrder("someInsect");

    assertThat(queryNames("bird", true))
        .isEmpty();

    assertThat(queryNames("bird", false))
        .isEmpty();

    assertThat(queryNames("cat", true))
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThat(queryNames("cat", false))
        .containsExactlyInAnyOrder("someCat", "otherCat");

    assertThat(queryNames("human", true))
        .isEmpty();

    assertThat(queryNames("human", false))
        .isEmpty();
  }

  private List<String> queryNames(String clazz, boolean polymorphic) {
    return pool.asGraph().traversal().V()
        .hasLabel(clazz)
        .with(YTDBQueryConfigParam.polymorphicQuery, polymorphic)
        .<String>values("name")
        .toList();
  }
}
