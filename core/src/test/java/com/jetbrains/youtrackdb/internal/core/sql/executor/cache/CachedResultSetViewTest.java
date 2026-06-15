package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies {@link CachedResultSetView} — the consumer-facing {@code ResultSet} that reconstructs the
 * rows a fresh uncached execution would return (I10) by sorted-merging a {@link CachedEntry}'s frozen
 * output with a {@link TxDeltaCursor}, and by pinning the entry against eviction while it iterates
 * (I9). The view is unit-tested directly against synthetic entries and cursors (the session wiring
 * lands in a later step), so each test stages a known {@code (cache rows, skipSet, injectList)} shape
 * and asserts the merged emission order, the pin refcount, and the idempotent close.
 *
 * <p>Records are created in a live in-memory database so RIDs and ORDER BY comparisons are genuine, but
 * no query runs: the "cached" rows are wrapped {@link ResultInternal} instances seeded directly into
 * the entry, and the "stream tail" is a synthetic {@link ExecutionStream} the test controls.
 */
public class CachedResultSetViewTest {

  private static final String CLASS_NAME = "ViewRec";
  private static final String FIELD = "n";

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb(getClass().getSimpleName(), DatabaseType.MEMORY,
        getClass());
    db = youTrackDB.open(getClass().getSimpleName(), "admin", DbTestBase.ADMIN_PASSWORD);
    var cls = db.createClass(CLASS_NAME);
    cls.createProperty(FIELD, PropertyType.INTEGER);
    db.begin();
  }

  @After
  public void after() {
    if (db.getTransactionInternal().isActive()) {
      db.rollback();
    }
    db.close();
    youTrackDB.drop(getClass().getSimpleName());
    youTrackDB.close();
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  /** Creates a record of CLASS_NAME with FIELD=value and returns it as an Entity. */
  private Entity newRec(int value) {
    var e = db.newEntity(CLASS_NAME);
    e.setProperty(FIELD, value);
    return e;
  }

  private static RID ridOf(Entity e) {
    return ((RecordAbstract) e).getIdentity();
  }

  private Result resultOf(Entity e) {
    return new ResultInternal(db, e);
  }

  private CommandContext ctx() {
    return new BasicCommandContext(db);
  }

  /**
   * The live transaction backing {@code db}. The view enters and exits the transaction's cache-code
   * re-entrancy guard around each {@code computeNext()} (not for its whole iteration lifetime), so
   * iterating a view here leaves the depth balanced after every row; these unit tests run on the owning
   * thread, so the per-row enter/exit is balanced and does not perturb the pin-count assertions below.
   */
  private FrontendTransactionImpl tx() {
    return (FrontendTransactionImpl) db.getTransactionInternal();
  }

  private static SQLOrderBy parseOrderBy(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      return ((SQLSelectStatement) parser.parse()).getOrderBy();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse: " + selectSql, e);
    }
  }

  /**
   * Builds a RECORD entry whose frozen result is exactly {@code cachedRows} (seeded into
   * {@code results} and {@code cachedRids}), with the entry already exhausted (no live stream). Used by
   * the cases that exercise the merge without a stream tail.
   */
  private CachedEntry recordEntry(SQLOrderBy orderBy, List<Entity> cachedRows) {
    var entry = new CachedEntry(
        CacheableShape.RECORD, Set.of(CLASS_NAME), null, orderBy, null, null, null, 0L);
    for (var e : cachedRows) {
      entry.getResults().add(resultOf(e));
      entry.getCachedRids().add(ridOf(e));
    }
    entry.setExhausted(true);
    return entry;
  }

  /**
   * Builds a RECORD entry with NO pre-cached rows and a live synthetic stream over {@code streamRows},
   * so the view must lazy-pull every row from the stream. Mirrors a cache-miss whose populate has not
   * yet pulled anything.
   */
  private CachedEntry streamEntry(SQLOrderBy orderBy, List<Entity> streamRows) {
    var rows = new ArrayList<Result>();
    for (var e : streamRows) {
      rows.add(resultOf(e));
    }
    var entry = new CachedEntry(
        CacheableShape.RECORD, Set.of(CLASS_NAME), null, orderBy,
        new ListExecutionStream(rows), null, ctx(), 0L);
    return entry;
  }

  private TxDeltaCursor cursor(Set<RID> skipSet, List<Result> injectList) {
    return new TxDeltaCursor(skipSet, injectList);
  }

  private static List<Integer> drainValues(CachedResultSetView view) {
    var out = new ArrayList<Integer>();
    while (view.hasNext()) {
      out.add(view.next().getProperty(FIELD));
    }
    return out;
  }

  /** A minimal {@link ExecutionStream} that replays a fixed list and counts its closes. */
  private static final class ListExecutionStream implements ExecutionStream {

    private final List<Result> rows;
    private int pos;
    int closeCount;

    ListExecutionStream(List<Result> rows) {
      this.rows = rows;
    }

    @Override
    public boolean hasNext(CommandContext ctx) {
      return pos < rows.size();
    }

    @Override
    public Result next(CommandContext ctx) {
      return rows.get(pos++);
    }

    @Override
    public void close(CommandContext ctx) {
      closeCount++;
    }
  }

  // ===========================================================================
  // RECORD sorted-merge
  // ===========================================================================

  /**
   * A post-populate CREATE that matches is delivered as an inject row with no skip; the merged view
   * must emit it sorted into place among the cached rows. Cached {10, 30}, inject {20} under ORDER BY
   * ASC must come back {10, 20, 30}.
   */
  @Test
  public void createInjectIsSortedIntoCachedRows() {
    var orderBy = parseOrderBy("SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC");
    var cached = List.of(newRec(10), newRec(30));
    var entry = recordEntry(orderBy, cached);
    var inject = List.<Result>of(resultOf(newRec(20)));
    var view = new CachedResultSetView(entry, cursor(Set.of(), inject), db, tx(), null, ctx());

    assertEquals(List.of(10, 20, 30), drainValues(view));
  }

  /**
   * A post-populate DELETE skips its cached row: the RID is in the skip-set and there is no inject, so
   * the view must drop it. Cached {10, 20, 30} with 20 skipped must come back {10, 30}.
   */
  @Test
  public void deleteSkipDropsCachedRow() {
    var orderBy = parseOrderBy("SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC");
    var ten = newRec(10);
    var twenty = newRec(20);
    var thirty = newRec(30);
    var entry = recordEntry(orderBy, List.of(ten, twenty, thirty));
    var view = new CachedResultSetView(
        entry, cursor(Set.of(ridOf(twenty)), List.of()), db, tx(), null, ctx());

    assertEquals(List.of(10, 30), drainValues(view));
  }

  /**
   * A post-populate UPDATE that moves a row's ORDER BY key re-positions it: the cached copy is skipped
   * and the post-mutation copy is injected at its new sort position. Cached {10, 20, 30}; row 10 is
   * updated to 25 (skip the old RID, inject a row with value 25). The view must come back {20, 25, 30}.
   */
  @Test
  public void updateRepositionsRowViaSkipPlusInject() {
    var orderBy = parseOrderBy("SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC");
    var moving = newRec(10);
    var entry = recordEntry(orderBy, List.of(moving, newRec(20), newRec(30)));

    // The post-mutation copy carries the new value; it re-uses the same RID, but the skip-set drops the
    // stale cached copy so only the injected copy survives.
    moving.setProperty(FIELD, 25);
    var inject = List.<Result>of(resultOf(moving));
    var view = new CachedResultSetView(
        entry, cursor(Set.of(ridOf(moving)), inject), db, tx(), null, ctx());

    assertEquals(List.of(20, 25, 30), drainValues(view));
  }

  /**
   * With no ORDER BY the inject list keeps mutation-iteration order and the merge drains injects ahead
   * of equally-unordered cached rows (the {@code orderBy == null} branch treats every inject as
   * sorting at-or-before the cache head). Cached {1, 2}, inject {9} must yield all three rows with no
   * loss or duplication; the unordered contract only fixes the set, not a total order.
   */
  @Test
  public void noOrderByEmitsAllRowsWithoutLoss() {
    var entry = recordEntry(null, List.of(newRec(1), newRec(2)));
    var inject = List.<Result>of(resultOf(newRec(9)));
    var view = new CachedResultSetView(entry, cursor(Set.of(), inject), db, tx(), null, ctx());

    var values = drainValues(view);
    assertEquals("All cached and injected rows must be emitted exactly once", 3, values.size());
    assertTrue(values.containsAll(List.of(1, 2, 9)));
  }

  /**
   * When a cached row and an inject row carry the EQUAL ORDER BY key, the both-heads merge arm hits
   * the {@code cmp == 0} tie branch ({@code orderBy.compare(...) == 0}), which favours the inject side
   * ({@code cmp <= 0} drains the inject first) and must then still emit the cached row — both exactly
   * once, neither dropped nor duplicated. Cached {10, 20}, inject {10} under ORDER BY ASC must come
   * back {10, 10, 20}. Every other RECORD-merge test uses distinct keys, so this is the only case that
   * drives the tie branch: a regression flipping the comparison to {@code cmp < 0} would drop one of
   * the two equal-key rows and pass every other test.
   */
  @Test
  public void injectWithEqualOrderByKeyEmitsBothExactlyOnce() {
    var orderBy = parseOrderBy("SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC");
    var entry = recordEntry(orderBy, List.of(newRec(10), newRec(20)));
    // A distinct record carrying the same ORDER BY key as the cached 10, so the comparator ties.
    var inject = List.<Result>of(resultOf(newRec(10)));
    var view = new CachedResultSetView(entry, cursor(Set.of(), inject), db, tx(), null, ctx());

    assertEquals("a tie on the ORDER BY key must emit both rows exactly once",
        List.of(10, 10, 20), drainValues(view));
  }

  // ===========================================================================
  // Stream-pull-with-skip-set unification
  // ===========================================================================

  /**
   * When the entry has no pre-cached rows the view must lazy-pull the full result from the stream,
   * append each pulled row to the shared {@code entry.results} / {@code cachedRids}, and emit them in
   * order. After draining, the entry is exhausted and its rows are visible to a later view.
   */
  @Test
  public void streamPullMaterializesAndAppendsRows() {
    var orderBy = parseOrderBy("SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC");
    var entry = streamEntry(orderBy, List.of(newRec(1), newRec(2), newRec(3)));
    var view = new CachedResultSetView(entry, cursor(Set.of(), List.of()), db, tx(), null, ctx());

    assertEquals(List.of(1, 2, 3), drainValues(view));
    assertTrue("Stream drain must flip the entry to exhausted", entry.isExhausted());
    assertEquals("Every pulled row must be appended to the shared cache", 3,
        entry.getResults().size());
    assertEquals(3, entry.getCachedRids().size());
  }

  /**
   * A RID in the skip-set must be suppressed even when it surfaces from the stream pull (not just from
   * the pre-cached prefix), closing the lazy-pull gap. The stream yields {1, 2, 3}; row 2 is skipped
   * (a post-populate delete of a record beyond the cached prefix), so the view emits {1, 3} while still
   * appending all three to the shared cache for later views.
   */
  @Test
  public void streamPulledRowInSkipSetIsSuppressed() {
    var orderBy = parseOrderBy("SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC");
    var one = newRec(1);
    var two = newRec(2);
    var three = newRec(3);
    var entry = streamEntry(orderBy, List.of(one, two, three));
    var view = new CachedResultSetView(
        entry, cursor(Set.of(ridOf(two)), List.of()), db, tx(), null, ctx());

    assertEquals("Skipped stream row must not be emitted", List.of(1, 3), drainValues(view));
    assertEquals("All pulled rows are still appended to the shared cache", 3,
        entry.getResults().size());
  }

  /**
   * The sorted-merge must materialize the next storage row before consulting the delta head, so a
   * delta inject is never emitted ahead of a not-yet-pulled storage row that sorts earlier. Stream
   * yields {10, 30}, inject {20}: even though the inject is available immediately, the view must pull
   * 10 first and emit {10, 20, 30}, not {20, 10, 30}.
   */
  @Test
  public void injectNeverPrecedesEarlierUnpulledStreamRow() {
    var orderBy = parseOrderBy("SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC");
    var entry = streamEntry(orderBy, List.of(newRec(10), newRec(30)));
    var inject = List.<Result>of(resultOf(newRec(20)));
    var view = new CachedResultSetView(entry, cursor(Set.of(), inject), db, tx(), null, ctx());

    assertEquals(List.of(10, 20, 30), drainValues(view));
  }

  // ===========================================================================
  // K0_NONE direct replay
  // ===========================================================================

  /**
   * A K0_NONE view carries a null delta cursor and must replay the cached rows verbatim with no merge,
   * lazy-pulling the stream tail. The version gate already guaranteed no post-populate mutation, so the
   * cached output equals a fresh deterministic re-run.
   */
  @Test
  public void k0NoneReplaysStreamDirectlyWithoutDelta() {
    var entry = new CachedEntry(
        CacheableShape.K0_NONE, Set.of(CLASS_NAME), null, null,
        new ListExecutionStream(List.of(resultOf(newRec(7)), resultOf(newRec(8)))),
        null, ctx(), 0L);
    var view = new CachedResultSetView(entry, null, db, tx(), null, ctx());

    assertEquals(List.of(7, 8), drainValues(view));
    assertTrue(entry.isExhausted());
  }

  /** A K0_NONE view over pre-cached rows (no live stream) replays them in order. */
  @Test
  public void k0NoneReplaysPreCachedRows() {
    var entry = new CachedEntry(
        CacheableShape.K0_NONE, Set.of(CLASS_NAME), null, null, null, null, null, 0L);
    entry.getResults().add(resultOf(newRec(1)));
    entry.getResults().add(resultOf(newRec(2)));
    entry.setExhausted(true);
    var view = new CachedResultSetView(entry, null, db, tx(), null, ctx());

    assertEquals(List.of(1, 2), drainValues(view));
  }

  // ===========================================================================
  // View pinning (I9) and idempotent close (I6)
  // ===========================================================================

  /** Constructing a view pins its entry (liveViewCount becomes 1) so LRU eviction skips it. */
  @Test
  public void constructionPinsEntry() {
    var entry = recordEntry(null, List.of(newRec(1)));
    assertEquals(0, entry.getLiveViewCount());
    var view = new CachedResultSetView(entry, cursor(Set.of(), List.of()), db, tx(), null, ctx());
    assertEquals("View construction must pin the entry", 1, entry.getLiveViewCount());
    view.close();
  }

  /** Explicit close releases the pin exactly once. */
  @Test
  public void closeReleasesPin() {
    var entry = recordEntry(null, List.of(newRec(1)));
    var view = new CachedResultSetView(entry, cursor(Set.of(), List.of()), db, tx(), null, ctx());
    view.close();
    assertEquals("Close must release the pin", 0, entry.getLiveViewCount());
    assertTrue(view.isClosed());
  }

  /** Natural exhaustion releases the pin without an explicit close. */
  @Test
  public void exhaustionReleasesPin() {
    var entry = recordEntry(null, List.of(newRec(1)));
    var view = new CachedResultSetView(entry, cursor(Set.of(), List.of()), db, tx(), null, ctx());
    drainValues(view);
    assertFalse(view.hasNext());
    assertEquals("Draining to exhaustion must release the pin", 0, entry.getLiveViewCount());
  }

  /**
   * Close after natural exhaustion must not double-release the pin: exhaustion already decremented the
   * refcount, and a following close must be a no-op for the pin (releases exactly once).
   */
  @Test
  public void exhaustionThenCloseReleasesPinOnce() {
    var entry = recordEntry(null, List.of(newRec(1)));
    // Pin twice so a buggy double-release would visibly drop the count below the second pin.
    entry.incrementLiveViewCount();
    var view = new CachedResultSetView(entry, cursor(Set.of(), List.of()), db, tx(), null, ctx());
    assertEquals(2, entry.getLiveViewCount());
    drainValues(view);
    assertEquals("Exhaustion releases the view's own pin once", 1, entry.getLiveViewCount());
    view.close();
    assertEquals("Close after exhaustion must not release a second time", 1,
        entry.getLiveViewCount());
  }

  /** A second close is a no-op: the view stays closed and the pin is not released twice. */
  @Test
  public void doubleCloseIsIdempotent() {
    var entry = recordEntry(null, List.of(newRec(1)));
    entry.incrementLiveViewCount(); // baseline pin so a double-release would underflow below 1
    var view = new CachedResultSetView(entry, cursor(Set.of(), List.of()), db, tx(), null, ctx());
    assertEquals(2, entry.getLiveViewCount());
    view.close();
    view.close();
    assertEquals("Double close must release the pin exactly once", 1, entry.getLiveViewCount());
    assertTrue(view.isClosed());
  }

  /** A closed view reports no more rows and throws on next(), per the ResultSet contract. */
  @Test
  public void closedViewHasNoNext() {
    var entry = recordEntry(null, List.of(newRec(1)));
    var view = new CachedResultSetView(entry, cursor(Set.of(), List.of()), db, tx(), null, ctx());
    view.close();
    assertFalse(view.hasNext());
    assertThrows(NoSuchElementException.class, view::next);
  }

  /**
   * Two views built on the same entry pin it independently: both increment, and the entry stays pinned
   * until the last view releases. This is the multi-view pinning the LRU guard relies on (I9).
   */
  @Test
  public void twoViewsPinIndependently() {
    var entry = recordEntry(null, List.of(newRec(1)));
    var skip = Collections.<RID>unmodifiableSet(new HashSet<>());
    var a = new CachedResultSetView(entry, cursor(skip, List.of()), db, tx(), null, ctx());
    var b = new CachedResultSetView(entry, cursor(skip, List.of()), db, tx(), null, ctx());
    assertEquals(2, entry.getLiveViewCount());
    a.close();
    assertEquals("Entry stays pinned while the second view iterates", 1, entry.getLiveViewCount());
    b.close();
    assertEquals(0, entry.getLiveViewCount());
  }
}
