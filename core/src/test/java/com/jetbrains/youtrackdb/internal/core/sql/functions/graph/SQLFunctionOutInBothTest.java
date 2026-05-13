package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.After;
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

  @Test
  public void outNoLabelsReturnsAllOutgoingNeighbors() {
    // A has two outgoing edges (knows→B, follows→C) — out() with no labels
    // returns both neighbours. Set-equality makes the assertion tight: a
    // regression returning [B, B] would still satisfy contains(B) + contains(C)
    // combined with size==2 ONLY if it also contained C, so we compare sets
    // directly to rule out spurious duplicates.
    var fn = new SQLFunctionOut();
    var src = reload(a);
    var result = fn.execute(src, null, null, new Object[0], ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();
    assertEquals(Set.of(b.getIdentity(), c.getIdentity()), new HashSet<>(ids));
    assertEquals("No duplicate RIDs expected", 2, ids.size());
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

    assertEquals(Set.of(a.getIdentity(), b.getIdentity()), new HashSet<>(ids));
    assertEquals(2, ids.size());
  }

  @Test
  public void bothNoLabelsReturnsBothDirectionNeighbors() {
    // B has A as incoming (knows) and C as outgoing (knows).
    var fn = new SQLFunctionBoth();
    var src = reload(b);
    var result = fn.execute(src, null, null, new Object[0], ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(Set.of(a.getIdentity(), c.getIdentity()), new HashSet<>(ids));
    assertEquals(2, ids.size());
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
    // When foreachRecord delivers a non-Identifiable element to the lambda,
    // SQLFunctionMove.execute throws IllegalArgumentException with a specific
    // "Invalid argument type:" prefix. We wrap the String in an array so that
    // foreachRecord's MultiValue iterator reaches the lambda — a bare String
    // would be silently rejected upstream.
    var fn = new SQLFunctionOut();
    try {
      fn.execute(new Object[] {"not-an-identifiable"}, null, null, new Object[0], ctx());
      fail("Expected IllegalArgumentException for non-Identifiable argument");
    } catch (IllegalArgumentException expected) {
      assertNotNull(expected.getMessage());
      assertTrue(
          "Expected 'Invalid argument type:' prefix. Actual: " + expected.getMessage(),
          expected.getMessage().startsWith("Invalid argument type:"));
      assertTrue(
          "Exception message should name the offending class. Actual: " + expected.getMessage(),
          expected.getMessage().contains("String"));
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

    assertEquals(Set.of(b.getIdentity(), c.getIdentity()), new HashSet<>(ids));
  }

  @Test
  public void inFilteredPossibleResultsFallsBackToV2v() {
    var fn = new SQLFunctionIn();
    var src = reload(c);
    List<Identifiable> hints = List.of(a.getIdentity(), b.getIdentity());
    var result = fn.execute(src, null, null, new Object[0], hints, ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(Set.of(a.getIdentity(), b.getIdentity()), new HashSet<>(ids));
  }

  @Test
  public void functionsExtendAbstractAndExposeDefaultAggregationForAllDispatchers() {
    // Dispatchers inherit from SQLFunctionAbstract — they are NOT aggregators
    // and do NOT filter records. Pin this for all three V2V dispatchers so a
    // refactor that flipped either flag on Out/In/Both must update the test
    // deliberately (the prior single-dispatcher version would miss regressions
    // on In or Both).
    SQLFunctionAbstract[] dispatchers =
        new SQLFunctionAbstract[] {new SQLFunctionOut(), new SQLFunctionIn(),
            new SQLFunctionBoth()};
    for (var fn : dispatchers) {
      assertFalse(fn.getClass().getSimpleName() + ".aggregateResults", fn.aggregateResults());
      assertFalse(fn.getClass().getSimpleName() + ".filterResult", fn.filterResult());
      assertNull(fn.getClass().getSimpleName() + ".getResult", fn.getResult());
    }
  }

  @Test
  public void outWithNullLabelElementIsTreatedAsNoLabels() {
    // SQLFunctionMove.execute has a three-part guard:
    //   iParameters != null && iParameters.length > 0 && iParameters[0] != null
    // Previously only the length==0 and non-null paths were tested; an
    // Object[]{null} exercises the third sub-condition so regressions to the
    // null-element guard get caught.
    var fn = new SQLFunctionOut();
    var src = reload(a);
    var result = fn.execute(src, null, null, new Object[] {null}, ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    // {null} is equivalent to no labels — both neighbours of A are returned.
    assertEquals(Set.of(b.getIdentity(), c.getIdentity()), new HashSet<>(ids));
  }

  @Test
  public void outWithMultipleLabelsCoversFetchFromIndexMultiLabelBranch() {
    // Passing >1 labels exercises SQLFunctionOut.fetchFromIndex's
    // `iEdgeTypes.length > 1 → return null` branch (ineligible-for-index-shortcut
    // path). The visible outcome of the fallback to v2v is the union of
    // neighbours across all matching edge classes.
    var fn = new SQLFunctionOut();
    var src = reload(a);
    var result = fn.execute(src, null, null, new Object[] {"knows", "follows"}, ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    // A-knows->B and A-follows->C: both edge classes match → {B, C}.
    assertEquals(Set.of(b.getIdentity(), c.getIdentity()), new HashSet<>(ids));
  }

  @Test
  public void outOnResultWrappedIdentifiableDispatches() {
    // SQLEngine.foreachRecord has a dedicated branch for Result-typed inputs
    // (result.isEntity() → function.apply(result.asEntity())). The previous
    // tests only passed Identifiable/RecordId/Object[] — this pins the
    // Result-based dispatch path used by real SQL execution pipelines.
    var fn = new SQLFunctionOut();
    var src = reload(a);
    var wrapped = new ResultInternal(session, src);

    var result = fn.execute(wrapped, null, null, new Object[0], ctx());
    var ids = collectVertices(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(Set.of(b.getIdentity(), c.getIdentity()), new HashSet<>(ids));
  }

}
