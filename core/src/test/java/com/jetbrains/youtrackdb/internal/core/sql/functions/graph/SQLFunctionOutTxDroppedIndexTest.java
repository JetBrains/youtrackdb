package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

/**
 * Regression coverage for the tx-dropped-index visibility gap in the committed-only
 * involved-indexes lookup (OBS-7): an index dropped inside an open transaction keeps its live
 * engine and its committed-registry entry until commit, but {@code ClassIndexManager} stops
 * maintaining it the moment the drop is recorded (the overlay hides it from the per-class raw
 * index set). A traversal that accelerates through {@code getInvolvedIndexesInternal} — the
 * {@code out()}/{@code in()} supernode shortcut, which MATCH's filtered edge traversal shares —
 * would therefore read the stale engine and miss the transaction's own post-drop writes, while a
 * plain SELECT (overlay-aware planner) returns them correctly. The fix hides overlay-tx-dropped
 * names from {@code getClassInvolvedIndexes}/{@code areIndexed}, forcing the traversal onto the
 * correct edge-list fallback.
 */
public class SQLFunctionOutTxDroppedIndexTest extends DbTestBase {

  private static final String VERTEX_CLASS = "SuperNodeV";
  private static final String EDGE_CLASS = "SuperNodeE";
  private static final String INDEX_NAME = "SuperNodeE_out_in";

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  private static List<Identifiable> collectVertices(Object result) {
    // Normalise the function's Iterable/Iterator result shapes to a list of identities.
    var out = new ArrayList<Identifiable>();
    if (result == null) {
      return out;
    }
    if (result instanceof Iterable<?> it) {
      for (var o : it) {
        if (o instanceof Identifiable id) {
          out.add(id);
        }
      }
      return out;
    }
    if (result instanceof Iterator<?> iter) {
      while (iter.hasNext()) {
        var o = iter.next();
        if (o instanceof Identifiable id) {
          out.add(id);
        }
      }
    }
    return out;
  }

  /** Guarantee the session is never left inside an open transaction between tests. */
  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  /**
   * Builds the committed fixture: an indexed edge class over {@code (out, in)}, a hub vertex
   * {@code a} with committed edges to {@code b1} and {@code b2} (both present in the committed
   * index engine). Returns the vertices as {@code [a, b1, b2]}.
   */
  private Vertex[] committedHubWithTwoEdges() {
    var schema = session.getMetadata().getSchema();
    schema.createClass(VERTEX_CLASS, schema.getClass("V"));
    var edgeClass = schema.createClass(EDGE_CLASS, schema.getClass("E"));
    edgeClass.createProperty("out", PropertyType.LINK);
    edgeClass.createProperty("in", PropertyType.LINK);
    edgeClass.createIndex(INDEX_NAME, SchemaClass.INDEX_TYPE.NOTUNIQUE, "out", "in");

    session.begin();
    var a = session.newVertex(VERTEX_CLASS);
    a.setProperty("name", "a");
    var b1 = session.newVertex(VERTEX_CLASS);
    b1.setProperty("name", "b1");
    var b2 = session.newVertex(VERTEX_CLASS);
    b2.setProperty("name", "b2");
    session.newEdge(a, b1, EDGE_CLASS);
    session.newEdge(a, b2, EDGE_CLASS);
    session.commit();
    return new Vertex[] {a, b1, b2};
  }

  /**
   * The traced wrong-read (red before the OBS-7 fix): inside a transaction that drops the edge
   * index and then adds one more edge, the filtered {@code out()} supernode shortcut accelerated
   * through the dropped-but-live index — which no longer tracks the post-drop edge — and returned
   * only the committed neighbours, silently missing the transaction's own write. With the fix the
   * involved-indexes lookup hides the tx-dropped name, the shortcut is skipped, and the edge-list
   * fallback returns all three neighbours. The supernode threshold is lowered so the shortcut
   * fires with a three-edge hub; restored in {@code finally}.
   */
  @Test
  public void outFilteredSupernodeTraversalSeesPostDropWritesAfterSameTxIndexDrop() {
    var vertices = committedHubWithTwoEdges();
    var a = vertices[0];
    var b1 = vertices[1];
    var b2 = vertices[2];

    var oldThreshold = SQLFunctionMoveFiltered.supernodeThreshold;
    SQLFunctionMoveFiltered.supernodeThreshold = 1;
    try {
      session.begin();
      // Drop the edge index inside the transaction: the engine stays live until commit, but the
      // overlay hides it from the per-class raw set, so ClassIndexManager stops maintaining it.
      session.getSharedContext().getIndexManager().dropIndex(session, INDEX_NAME);
      // The post-drop write: a third edge that never reaches the dropped index's engine.
      var b3 = session.newVertex(VERTEX_CLASS);
      b3.setProperty("name", "b3");
      var hub = (Vertex) session.getActiveTransaction().loadEntity(a);
      session.newEdge(hub, b3, EDGE_CLASS);

      var fn = new SQLFunctionOut();
      List<Identifiable> hints =
          List.of(b1.getIdentity(), b2.getIdentity(), b3.getIdentity());
      var result =
          fn.execute(session.getActiveTransaction().loadEntity(a), null, null,
              new Object[] {EDGE_CLASS}, hints, ctx());
      var ids = new HashSet<Identifiable>();
      for (var id : collectVertices(result)) {
        ids.add(id.getIdentity());
      }
      assertEquals(
          "the filtered out() traversal must see the transaction's own post-drop edge instead of"
              + " accelerating through the dropped-but-stale index",
          Set.of(b1.getIdentity(), b2.getIdentity(), b3.getIdentity()), ids);
      session.rollback();
    } finally {
      SQLFunctionMoveFiltered.supernodeThreshold = oldThreshold;
    }
  }

  /**
   * The MATCH-side companion: a MATCH traversal over the same mid-tx post-drop state must return
   * all three neighbours. MATCH's filtered edge traversal shares the {@code out()} shortcut probed
   * above (its other involved-indexes uses are selectivity estimation only), so this pins the
   * user-visible query surface end-to-end with the same lowered threshold.
   */
  @Test
  public void matchTraversalSeesPostDropWritesAfterSameTxIndexDrop() {
    committedHubWithTwoEdges();

    var oldThreshold = SQLFunctionMoveFiltered.supernodeThreshold;
    SQLFunctionMoveFiltered.supernodeThreshold = 1;
    try {
      session.begin();
      session.getSharedContext().getIndexManager().dropIndex(session, INDEX_NAME);
      var b3 = session.newVertex(VERTEX_CLASS);
      b3.setProperty("name", "b3");
      var hub =
          session.query("SELECT FROM " + VERTEX_CLASS + " WHERE name = 'a'").stream()
              .findFirst().orElseThrow().asVertex();
      session.newEdge(hub, b3, EDGE_CLASS);

      var names = new HashSet<String>();
      try (var rs = session.query(
          "MATCH {class:" + VERTEX_CLASS + ", where:(name = 'a'), as:x}"
              + ".out('" + EDGE_CLASS + "'){as:y} RETURN y.name as name")) {
        rs.stream().forEach(r -> names.add(r.getProperty("name")));
      }
      assertEquals("a MATCH traversal must see the transaction's own post-drop edge",
          Set.of("b1", "b2", "b3"), names);
      session.rollback();
    } finally {
      SQLFunctionMoveFiltered.supernodeThreshold = oldThreshold;
    }
  }

  /**
   * The SELECT baseline: the planner's per-class index resolution was already overlay-aware (it
   * hides the tx-dropped index and falls back to a scan), so a plain SELECT over the same mid-tx
   * post-drop state returns the post-drop row both before and after the OBS-7 fix. Pinned so the
   * involved-indexes fix is measured against a known-correct sibling read path.
   */
  @Test
  public void selectSeesPostDropWritesAfterSameTxIndexDrop() {
    committedHubWithTwoEdges();

    session.begin();
    session.getSharedContext().getIndexManager().dropIndex(session, INDEX_NAME);
    var b3 = session.newVertex(VERTEX_CLASS);
    b3.setProperty("name", "b3");

    var names = new HashSet<String>();
    try (var rs = session.query("SELECT name FROM " + VERTEX_CLASS)) {
      rs.stream().forEach(r -> names.add(r.getProperty("name")));
    }
    assertEquals("a plain SELECT must see the transaction's own post-drop row",
        Set.of("a", "b1", "b2", "b3"), names);
    session.rollback();
  }
}
