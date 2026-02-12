package com.jetbrains.youtrackdb.internal.core.gremlin;

import java.util.HashSet;
import java.util.Set;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GraphTxTest extends GraphBaseTest {

  @Before
  @Override
  public void setupGraphDB() {
    super.setupGraphDB();

    session.command("CREATE CLASS Person EXTENDS V");
    session.command("CREATE CLASS HasFriend EXTENDS E");
    session.command("CREATE SEQUENCE personIdSequence TYPE ORDERED;");
    session.command(
        "CREATE PROPERTY Person.id LONG (MANDATORY TRUE, default \"sequence('personIdSequence').next()\");");
    session.command("CREATE INDEX Person.id ON Person (id) UNIQUE");
  }

  @Test
  public void txSequenceTest() {
    var vertex = graph.addVertex(T.label, "Person", "name", "John");
    for (var i = 0; i < 10; i++) {
      var vertex1 = graph.addVertex(T.label, "Person", "name", "Frank" + i);
      vertex.addEdge("HasFriend", vertex1);
    }
    graph.tx().commit();

    session.begin();
    Assert.assertEquals(11, session.countClass("Person"));
    session.rollback();
  }

  @Test
  public void testSequencesParallel() {
    session.executeSQLScript(
        """
            CREATE CLASS TestSequence EXTENDS V;
            CREATE SEQUENCE TestSequenceIdSequence TYPE CACHED;
            CREATE PROPERTY TestSequence.mm LONG (MANDATORY TRUE, default "sequence('TestSequenceIdSequence').next()");
            """);

    final var recCount = 50;
    final var threadCount = 1;
    try {
      var threads = new Thread[threadCount];
      for (var j = 0; j < threadCount; j++) {
        var thread =
            new Thread(
                () -> {
                  for (var i = 0; i < recCount; i++) {
                    graph.addVertex("TestSequence");
                  }
                  graph.tx().commit();
                });
        threads[j] = thread;
        thread.start();
      }

      for (var thread : threads) {
        try {
          thread.join();
        } catch (InterruptedException exc) {
          exc.printStackTrace();
        }
      }

      var iter = graph.traversal().V().hasLabel("TestSequence");
      var counter = 0;
      Set<Long> vals = new HashSet<>();
      while (iter.hasNext()) {
        var v = iter.next();
        VertexProperty<Long> vp = v.property("mm");
        long a = vp.value();
        Assert.assertFalse(vals.contains(a));
        vals.add(a);
        counter++;
      }
      Assert.assertEquals(threadCount * recCount, counter);
    } finally {
      session.command("DROP CLASS TestSequence unsafe");
    }
  }

  @Test(expected = IllegalStateException.class)
  public void txManualOpenExceptionTest() {
    graph.tx().onReadWrite(Transaction.READ_WRITE_BEHAVIOR.MANUAL);
    graph.addVertex(T.label, "Person", "name", "John");
  }

  @Test
  public void txManualOpen() throws Exception {
    graph.tx().onReadWrite(Transaction.READ_WRITE_BEHAVIOR.MANUAL);

    graph.tx().open();

    graph.addVertex(T.label, "Person", "name", "John");

    graph.close();

    graph = openGraph();

    session.begin();
    Assert.assertEquals(0, session.countClass("Person"));
    session.rollback();
  }

  @Test
  public void txManualOpenCommitOnClose() throws Exception {
    graph.tx().onReadWrite(Transaction.READ_WRITE_BEHAVIOR.MANUAL);
    graph.tx().onClose(Transaction.CLOSE_BEHAVIOR.COMMIT);
    graph.tx().open();
    graph.addVertex(T.label, "Person", "name", "John");
    graph.close();

    graph = openGraph();

    session.begin();
    Assert.assertEquals(1, session.countClass("Person"));
    session.rollback();
  }

  @Test
  public void txCommitOnClose() throws Exception {
    graph.tx().onClose(Transaction.CLOSE_BEHAVIOR.COMMIT);

    graph.addVertex(T.label, "Person", "name", "John");

    graph.close();

    graph = openGraph();

    session.begin();
    Assert.assertEquals(1, session.countClass("Person"));
    session.rollback();
  }

  @Test
  public void txSequenceTestRollback() {
    var vertex = graph.addVertex(T.label, "Person", "name", "John");
    for (var i = 0; i < 10; i++) {
      var vertex1 = graph.addVertex(T.label, "Person", "name", "Frank" + i);
      vertex.addEdge("HasFriend", vertex1);
    }
    graph.tx().rollback();

    session.begin();
    Assert.assertEquals(0, session.countClass("Person"));
    session.rollback();
  }

  @Test
  public void testAutoStartTX() throws Exception {
    Assert.assertFalse(graph.tx().isOpen());

    graph.addVertex("Person");
    Assert.assertTrue(graph.tx().isOpen());

    graph.close();

    graph = openGraph();

    session.begin();
    Assert.assertEquals(0, session.countClass("Person"));
    session.rollback();
  }
}
