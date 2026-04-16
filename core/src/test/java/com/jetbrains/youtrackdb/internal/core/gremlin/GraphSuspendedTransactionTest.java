package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.ArrayList;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.junit.Test;

public class GraphSuspendedTransactionTest extends DbTestBase {

  @Test
  public void withSuspendedTransaction_innerTransactionIsIndependent() {
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("counter", PropertyType.INTEGER);

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").property("counter", 0).next();
    graph.tx().commit();

    graph.tx().open();
    var v = graph.traversal().V().hasLabel("TestEntity").next();
    v.property("counter", 1);

    graph.withSuspendedTransaction(() -> {
      graph.tx().open();
      var inner = graph.traversal().V().hasLabel("TestEntity").next();
      assertEquals(0, (int) inner.property("counter").value());
      inner.property("counter", 99);
      graph.tx().commit();
      return null;
    });

    // outer transaction still has its modification
    assertEquals(1, (int) v.property("counter").value());
    // outer commit conflicts with inner — that's expected
    try {
      graph.tx().commit();
      fail("Expected ConcurrentModificationException");
    } catch (ConcurrentModificationException expected) {
      // ok
    }
  }

  @Test
  public void withSuspendedTransaction_exceptionInLambda_outerTransactionRestored() {
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("counter", PropertyType.INTEGER);

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").property("counter", 0).next();
    graph.tx().commit();

    graph.tx().open();
    var v = graph.traversal().V().hasLabel("TestEntity").next();
    v.property("counter", 1);

    try {
      graph.withSuspendedTransaction(() -> {
        graph.tx().open();
        graph.traversal().V().hasLabel("TestEntity").next().property("counter", 99);
        // don't commit — throw instead
        throw new RuntimeException("intentional");
      });
      fail("Should have thrown");
    } catch (RuntimeException e) {
      assertEquals("intentional", e.getMessage());
    }

    // outer transaction should still be usable
    assertEquals(1, (int) v.property("counter").value());
    graph.tx().rollback();
  }

  @Test
  public void withSuspendedTransaction_lambdaDoesNotOpenTx() {
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("counter", PropertyType.INTEGER);

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").property("counter", 0).next();
    graph.tx().commit();

    graph.tx().open();
    var v = graph.traversal().V().hasLabel("TestEntity").next();
    v.property("counter", 1);

    graph.withSuspendedTransaction(() -> {
      // no tx operations at all
      return 42;
    });

    assertEquals(1, (int) v.property("counter").value());
    graph.tx().commit();
  }

  @Test
  public void withSuspendedTransaction_leftoverTxIsRolledBack() {
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("counter", PropertyType.INTEGER);

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").property("counter", 0).next();
    graph.tx().commit();

    graph.tx().open();
    var v = graph.traversal().V().hasLabel("TestEntity").next();
    v.property("counter", 1);

    graph.withSuspendedTransaction(() -> {
      graph.tx().open();
      graph.traversal().V().hasLabel("TestEntity").next().property("counter", 99);
      // deliberately don't commit or rollback
      return null;
    });

    // outer still works — inner was rolled back by cleanup
    assertEquals(1, (int) v.property("counter").value());
    graph.tx().commit();

    // verify inner's change was not persisted
    graph.tx().open();
    var result = graph.traversal().V().hasLabel("TestEntity").next();
    assertEquals(1, (int) result.property("counter").value());
    graph.tx().close();
  }

  @Test
  public void withSuspendedTransaction_nestedSuspension() {
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("counter", PropertyType.INTEGER);

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").property("counter", 0).next();
    graph.tx().commit();

    graph.tx().open();
    var v = graph.traversal().V().hasLabel("TestEntity").next();
    v.property("counter", 1);

    graph.withSuspendedTransaction(() -> {
      graph.tx().open();
      graph.traversal().V().hasLabel("TestEntity").next().property("counter", 10);

      graph.withSuspendedTransaction(() -> {
        graph.tx().open();
        var innermost = graph.traversal().V().hasLabel("TestEntity").next();
        // sees committed value (0), not outer (1) or middle (10)
        assertEquals(0, (int) innermost.property("counter").value());
        graph.tx().rollback();
        return null;
      });

      // middle tx still works
      graph.tx().commit();
      return null;
    });

    // outer tx still works
    assertEquals(1, (int) v.property("counter").value());
    // outer conflicts with middle — expected
    try {
      graph.tx().commit();
      fail("Expected ConcurrentModificationException");
    } catch (ConcurrentModificationException expected) {
      // ok
    }
  }

  @Test
  public void withSuspendedTransaction_noOuterTransaction() {
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("counter", PropertyType.INTEGER);

    YTDBGraph graph = pool.asGraph();

    // no tx open — suspension is a no-op
    var result = graph.withSuspendedTransaction(() -> {
      graph.tx().open();
      graph.traversal().addV("TestEntity").property("counter", 42).next();
      graph.tx().commit();
      return "done";
    });

    assertEquals("done", result);

    graph.tx().open();
    var v = graph.traversal().V().hasLabel("TestEntity").next();
    assertEquals(42, (int) v.property("counter").value());
    graph.tx().close();
  }

  @Test
  public void withSuspendedTransaction_divergedSessionsAreBothCleaned() {
    // Trigger the case where activeSession and sessionEmbedded diverge:
    // open a tx (sets both), commit (nulls both via accept(Status)),
    // then call getUnderlyingDatabaseSession() directly (sets only sessionEmbedded).
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("counter", PropertyType.INTEGER);

    var graphImpl = (YTDBGraphImplAbstract) pool.asGraph();

    graphImpl.tx().open();
    graphImpl.traversal().addV("TestEntity").property("counter", 0).next();
    graphImpl.tx().commit();

    graphImpl.tx().open();
    var v = graphImpl.traversal().V().hasLabel("TestEntity").next();
    v.property("counter", 1);

    graphImpl.withSuspendedTransaction(() -> {
      // Open and commit a tx — both activeSession and sessionEmbedded are set then cleared.
      graphImpl.tx().open();
      graphImpl.tx().commit();

      // Now call getUnderlyingDatabaseSession() directly — the side effect sets
      // sessionEmbedded but does NOT set activeSession on the transaction. They diverge.
      // Cleanup should still close the orphaned session.
      graphImpl.getUnderlyingDatabaseSession();
      return null;
    });

    // outer transaction should still be usable
    assertEquals(1, (int) v.property("counter").value());
    graphImpl.tx().rollback();
  }

  @Test
  public void withSuspendedTransaction_leftoverSessionAlreadyClosed_outerStillRestored() {
    // Simulate a session that's already in a bad state when cleanup runs.
    // The lambda opens a tx, gets the session, manually closes it, then leaves
    // the tx/session references dangling. Cleanup should handle this gracefully.
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("counter", PropertyType.INTEGER);

    var graphImpl = (YTDBGraphImplAbstract) pool.asGraph();

    graphImpl.tx().open();
    graphImpl.traversal().addV("TestEntity").property("counter", 0).next();
    graphImpl.tx().commit();

    graphImpl.tx().open();
    var v = graphImpl.traversal().V().hasLabel("TestEntity").next();
    v.property("counter", 1);

    graphImpl.withSuspendedTransaction(() -> {
      graphImpl.tx().open();
      DatabaseSessionEmbedded innerSession = graphImpl.getUnderlyingDatabaseSession();
      // Manually close the session — puts it in a bad state for cleanup
      innerSession.rollback();
      innerSession.close();
      // activeSession and sessionEmbedded still reference the now-closed session
      return null;
    });

    // outer transaction must still be restored despite messy cleanup
    assertEquals(1, (int) v.property("counter").value());
    graphImpl.tx().rollback();
  }

  @Test
  public void withSuspendedTransaction_returnValuePropagated() {
    // Verify the lambda's return value is passed through when an outer tx is active.
    session.createVertexClass("TestEntity");

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").next();

    var result = graph.withSuspendedTransaction(() -> 42);

    assertEquals(42, (int) result);
    graph.tx().commit();
  }

  @Test
  public void withSuspendedTransaction_checkedExceptionPropagated() {
    // Verify that a checked exception thrown inside the lambda propagates through
    // withSuspendedTransaction without being wrapped.
    session.createVertexClass("TestEntity");

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").next();

    try {
      graph.<Void, Exception>withSuspendedTransaction(() -> {
        throw new java.io.IOException("checked");
      });
      fail("Should have thrown IOException");
    } catch (Exception e) {
      assertTrue("Expected IOException but got: " + e.getClass().getName(),
          e instanceof java.io.IOException);
      assertEquals("checked", e.getMessage());
    }

    // outer transaction should still be usable after checked exception
    graph.tx().rollback();
    assertFalse(graph.tx().isOpen());
  }

  @Test
  public void withSuspendedTransaction_innerAndOuterCommitWithoutConflict() {
    // Inner and outer transactions touch different data — both commit successfully.
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("name", PropertyType.STRING);

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").property("name", "outer").next();

    graph.withSuspendedTransaction(() -> {
      graph.tx().open();
      graph.traversal().addV("TestEntity").property("name", "inner").next();
      graph.tx().commit();
      return null;
    });

    // outer commit should succeed — no conflict with inner (different records)
    graph.tx().commit();

    // verify both vertices were persisted
    graph.tx().open();
    var names = graph.traversal().V().hasLabel("TestEntity").values("name").toList();
    assertTrue(names.contains("outer"));
    assertTrue(names.contains("inner"));
    assertEquals(2, names.size());
    graph.tx().close();
  }

  @Test
  public void withSuspendedTransaction_outerIsReadOnly() {
    // Outer transaction only reads — suspension and restore should still work.
    var cls = session.createVertexClass("TestEntity");
    cls.createProperty("counter", PropertyType.INTEGER);

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").property("counter", 0).next();
    graph.tx().commit();

    // open outer as read-only (just read, no writes)
    graph.tx().open();
    var v = graph.traversal().V().hasLabel("TestEntity").next();
    var valueBefore = (int) v.property("counter").value();

    graph.withSuspendedTransaction(() -> {
      graph.tx().open();
      graph.traversal().V().hasLabel("TestEntity").next().property("counter", 99);
      graph.tx().commit();
      return null;
    });

    // outer still sees the snapshot it started with (0), not the inner's commit (99)
    assertEquals(valueBefore, (int) v.property("counter").value());
    // outer has no writes so commit is clean (no CME — there's nothing to conflict)
    graph.tx().commit();

    // verify inner's write was persisted
    graph.tx().open();
    var result = graph.traversal().V().hasLabel("TestEntity").next();
    assertEquals(99, (int) result.property("counter").value());
    graph.tx().close();
  }

  @Test
  public void withSuspendedTransaction_innerListenerDoesNotFireOnOuterCommit() {
    // A listener added inside the lambda must not fire when the outer transaction commits.
    session.createVertexClass("TestEntity");

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").next();
    graph.tx().commit();

    List<Transaction.Status> innerEvents = new ArrayList<>();

    graph.tx().open();
    graph.traversal().V().hasLabel("TestEntity").next();

    graph.withSuspendedTransaction(() -> {
      graph.tx().addTransactionListener(innerEvents::add);
      graph.tx().open();
      graph.tx().commit();
      return null;
    });

    // inner listener should have seen the inner commit
    assertEquals(List.of(Transaction.Status.COMMIT), innerEvents);

    // now commit the outer — inner listener must NOT fire again
    innerEvents.clear();
    graph.tx().commit();
    assertTrue("Inner listener should not fire on outer commit", innerEvents.isEmpty());
  }

  @Test
  public void withSuspendedTransaction_outerListenerDoesNotFireOnInnerCommit() {
    // A listener on the outer transaction must not fire when the inner transaction commits.
    session.createVertexClass("TestEntity");

    YTDBGraph graph = pool.asGraph();

    graph.tx().open();
    graph.traversal().addV("TestEntity").next();
    graph.tx().commit();

    List<Transaction.Status> outerEvents = new ArrayList<>();

    graph.tx().open();
    graph.tx().addTransactionListener(outerEvents::add);
    graph.traversal().V().hasLabel("TestEntity").next();

    graph.withSuspendedTransaction(() -> {
      graph.tx().open();
      graph.tx().commit();
      return null;
    });

    // outer listener must not have fired during the inner commit
    assertTrue("Outer listener should not fire on inner commit", outerEvents.isEmpty());

    // now commit the outer — outer listener should fire
    graph.tx().commit();
    assertEquals(List.of(Transaction.Status.COMMIT), outerEvents);
  }
}
