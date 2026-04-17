package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the vertex-to-vertex graph navigation dispatchers: {@link SQLFunctionOut},
 * {@link SQLFunctionIn} and {@link SQLFunctionBoth}. All three share the same
 * {@link SQLFunctionMove} execute() plumbing — label parsing, Identifiable dispatch, and the
 * SQLEngine.foreachRecord fan-out — and differ only in which {@link com.jetbrains.youtrackdb
 * .internal.core.db.record.record.Direction} they pass to v2v.
 *
 * <p>The tests build a small directed graph (A→B→C) with both a single-arg and labeled-edge
 * overloads, and assert each dispatcher returns the expected adjacent vertices. They also cover:
 * metadata (getName / getMinParams / getMaxParams / getSyntax), the null-iThis contract for
 * foreachRecord, the invalid-argument branch of execute, and the non-supernode code path of
 * {@link SQLFunctionMoveFiltered#execute} when possibleResults is non-null (Out/In only).
 */
public class SQLFunctionOutInBothTest extends DbTestBase {

  private Vertex a;
  private Vertex b;
  private Vertex c;

  @Before
  public void setUpGraph() {
    // Edge classes: one generic "knows" and one labeled "follows" so we can
    // verify the labels[] filter codepath in addition to the unfiltered path.
    session.createEdgeClass("knows");
    session.createEdgeClass("follows");

    session.begin();
    a = session.newVertex();
    a.setProperty("name", "A");
    b = session.newVertex();
    b.setProperty("name", "B");
    c = session.newVertex();
    c.setProperty("name", "C");

    session.newEdge(a, b, "knows");
    session.newEdge(b, c, "knows");
    session.newEdge(a, c, "follows"); // A --follows--> C
    session.commit();
  }

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  private Identifiable reload(Identifiable v) {
    // Refresh the vertex from the transaction so that adjacency is visible to
    // the function (the function calls transaction.loadEntity under the hood).
    session.begin();
    return session.getActiveTransaction().loadEntity(v);
  }

  private static List<Identifiable> collectVertices(Object result) {
    // The functions return either an Iterable<Vertex> (single-record case) or a
    // MultiCollectionIterator (multi-record case). Normalise to a List<Identifiable>.
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
    if (result instanceof java.util.Iterator<?> iter) {
      while (iter.hasNext()) {
        var o = iter.next();
        if (o instanceof Identifiable id) {
          out.add(id);
        }
      }
    }
    return out;
  }

  @Test
  public void outNoLabelsReturnsAllOutgoingNeighbors() {
    // A has two outgoing edges (knows→B, follows→C) — out() with no labels
    // returns both neighbours.
    var fn = new SQLFunctionOut();
    var src = reload(a);
    var result = fn.execute(src, null, null, new Object[0], ctx());
    var ids = collectVertices(result).stream()
        .map(id -> id.getIdentity())
        .toList();
    session.commit();
    assertEquals(2, ids.size());
    assertTrue(ids.contains(b.getIdentity()));
    assertTrue(ids.contains(c.getIdentity()));
  }

  @Test
  public void outLabelFiltersRestrictToMatchingEdgeClass() {
    // A --follows--> C only; labels=["follows"] must return exactly {C}.
    var fn = new SQLFunctionOut();
    var src = reload(a);
    var result = fn.execute(src, null, null, new Object[] {"follows"}, ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(List.of(c.getIdentity()), ids);
  }

  @Test
  public void inNoLabelsReturnsAllIncomingNeighbors() {
    // C has two incoming edges (B--knows-->C and A--follows-->C).
    var fn = new SQLFunctionIn();
    var src = reload(c);
    var result = fn.execute(src, null, null, new Object[0], ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(2, ids.size());
    assertTrue(ids.contains(a.getIdentity()));
    assertTrue(ids.contains(b.getIdentity()));
  }

  @Test
  public void bothNoLabelsReturnsBothDirectionNeighbors() {
    // B has A as incoming (knows) and C as outgoing (knows).
    var fn = new SQLFunctionBoth();
    var src = reload(b);
    var result = fn.execute(src, null, null, new Object[0], ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(2, ids.size());
    assertTrue(ids.contains(a.getIdentity()));
    assertTrue(ids.contains(c.getIdentity()));
  }

  @Test
  public void outOnNullRecordReturnsNull() {
    // SQLEngine.foreachRecord documents a null current returns null up the stack.
    var fn = new SQLFunctionOut();
    assertNull(fn.execute(null, null, null, new Object[0], ctx()));
  }

  @Test
  public void outMetadataMatchesContract() {
    // Pin NAME / min / max / syntax so a copy-paste rename of a dispatcher
    // will surface as a metadata mismatch here rather than silently in SQL
    // lookups.
    var fn = new SQLFunctionOut();
    assertEquals("out", fn.getName(session));
    assertEquals(0, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(session));
    assertTrue(fn.getSyntax(session).contains("out"));
  }

  @Test
  public void inMetadataMatchesContract() {
    var fn = new SQLFunctionIn();
    assertEquals("in", fn.getName(session));
    assertEquals(0, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(session));
  }

  @Test
  public void bothMetadataMatchesContract() {
    var fn = new SQLFunctionBoth();
    assertEquals("both", fn.getName(session));
    assertEquals(0, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(session));
  }

  @Test
  public void outWithInvalidArgumentTypeRaises() {
    // When iThis is neither null, an Identifiable, a Result, nor an Iterable of
    // those, the foreachRecord lambda throws IllegalArgumentException.
    var fn = new SQLFunctionOut();
    try {
      // wrap the invalid value inside an Iterable so foreachRecord reaches the
      // lambda (a bare String would simply be rejected by foreachRecord returning null).
      fn.execute(new Object[] {"not-an-identifiable"}, null, null, new Object[0], ctx());
      fail("Expected IllegalArgumentException for non-Identifiable argument");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().toLowerCase().contains("argument")
          || expected.getMessage().contains("String"));
    }
  }

  @Test
  public void outWithNonExistentRecordReturnsNull() {
    // RecordNotFoundException inside v2v is swallowed and translated to null —
    // this mirrors how the SQL engine handles orphan references.
    var fn = new SQLFunctionOut();
    session.begin();
    try {
      var phantom = new RecordId(999, 999);
      var result = fn.execute(phantom, null, null, new Object[0], ctx());
      // With RecordNotFoundException the return is null.
      assertNull(result);
    } finally {
      session.rollback();
    }
  }

  @Test
  public void outFilteredPossibleResultsEmptyShortCircuits() {
    // SQLFunctionMoveFiltered.execute with an empty possibleResults iterable
    // returns an empty collection before consulting any index.
    var fn = new SQLFunctionOut();
    var src = reload(a);
    var empty = List.<Identifiable>of();
    var result = fn.execute(src, null, null, new Object[0], empty, ctx());
    // When possibleResults is empty, the result should be empty (no neighbors
    // to intersect with). Note: SQLFunctionMoveFiltered first routes through
    // the SQLEngine.foreachRecord path, so the empty check only fires inside
    // the inner move() call.
    var ids = collectVertices(result);
    session.commit();

    assertTrue("Empty possibleResults must short-circuit", ids.isEmpty());
  }

  @Test
  public void outFilteredPossibleResultsFallsBackToV2v() {
    // With a non-empty possibleResults and a non-supernode vertex (edges < 1000),
    // the dispatcher falls through to plain v2v — we should see the same
    // adjacency as the unfiltered call.
    var fn = new SQLFunctionOut();
    var src = reload(a);
    List<Identifiable> hints = List.of(b.getIdentity(), c.getIdentity());
    var result = fn.execute(src, null, null, new Object[0], hints, ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(2, ids.size());
    assertTrue(ids.contains(b.getIdentity()));
    assertTrue(ids.contains(c.getIdentity()));
  }

  @Test
  public void inFilteredPossibleResultsFallsBackToV2v() {
    var fn = new SQLFunctionIn();
    var src = reload(c);
    List<Identifiable> hints = List.of(a.getIdentity(), b.getIdentity());
    var result = fn.execute(src, null, null, new Object[0], hints, ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(2, ids.size());
    assertTrue(ids.contains(a.getIdentity()));
    assertTrue(ids.contains(b.getIdentity()));
  }

  @Test
  public void functionsExtendConfigurableAbstractAndExposeDefaultAggregation() {
    // Dispatchers inherit from SQLFunctionAbstract — they are NOT aggregators
    // and do NOT filter records. Pin this so a future refactor that flips
    // either flag must update tests deliberately.
    SQLFunctionAbstract out = new SQLFunctionOut();
    assertFalse(out.aggregateResults());
    assertFalse(out.filterResult());
    assertNull(out.getResult());
  }
}
