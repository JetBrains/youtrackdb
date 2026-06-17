package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static com.jetbrains.youtrackdb.internal.core.sql.executor.cache.CacheTestSupport.onlyEntry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * End-to-end cache-vs-fresh equivalence for the Etap-A single-alias MATCH fold. A
 * single-alias MATCH classifies as RECORD and replays through the record delta path: the entry stores
 * the raw, RID-identifiable bound records and a {@code returnProjector} reproduces the RETURN tuple at
 * the view emit boundary, so the RID-keyed skip-set / sorted-merge stay correct across in-transaction
 * CREATE / UPDATE / DELETE.
 *
 * <p>Each scenario runs twice through the live {@code query()} path — once with the cache off (a fresh
 * uncached MATCH, the source of truth) and once with it on (forcing a populate, then a post-mutation
 * second query served through the delta-merged view) — and the two row sequences are compared
 * position-by-position. Comparing against a parallel uncached run rather than a literal keeps the suite
 * honest as the projector / merge logic evolves.
 *
 * <p>The multi-item {@code RETURN u, u.name} case is the one that exposed the RID-addressability gap:
 * the projected tuple is not identifiable, so the cache must store the raw record (not the tuple) for
 * the skip-set to suppress a deleted/updated row. The matrix asserts a DELETE and a WHERE-breaking
 * UPDATE between two queries suppress the stale tuple, and a value UPDATE re-projects the fresh value.
 *
 * <p>Run with {@code -ea}: the single-alias-row null-RID guard in the populate mapper is a Java {@code
 * assert} that protects tests, not production.
 */
@Category(SequentialTest.class)
public class MatchEtapAEquivalenceTest extends DbTestBase {

  private static final String CLASS_NAME = "MatchRec";
  private static final String NAME = "name";
  private static final String FLAG = "active";

  private boolean previousEnabled;

  @Before
  public void enableSchema() {
    previousEnabled = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    var cls = session.createClass(CLASS_NAME);
    cls.createProperty(NAME, PropertyType.STRING);
    cls.createProperty(FLAG, PropertyType.BOOLEAN);
  }

  @After
  public void restoreFlag() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(previousEnabled);
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private FrontendTransactionImpl tx() {
    return (FrontendTransactionImpl) session.getTransactionInternal();
  }

  /** A single-alias MATCH returning the bound record and one projected property, ordered by it. */
  private static String matchSql() {
    return "match {as:u, class:" + CLASS_NAME + ", where:(" + FLAG + " = true)}"
        + " return u, u." + NAME + " order by u." + NAME;
  }

  /**
   * The same single-alias MATCH with a statement-level {@code LIMIT}. A LIMIT routes the statement to
   * the K0_NONE shape (a paginated top-N prefix is not delta-reconcilable), so it must NOT pick up the
   * Etap-A raw-record mapper / returnProjector: the K0_NONE replay path emits stored rows verbatim and
   * never re-projects, so a K0_NONE entry must store the executor's real RETURN tuples, not raw records.
   */
  private static String matchSqlLimited() {
    return matchSql() + " limit 5";
  }

  /**
   * Clears every committed record of CLASS_NAME in its own tx with the cache forced off, so the
   * flag-off and flag-on halves of a pair start from identical committed state and the clearing DELETE
   * never touches the cache under test.
   */
  private void clearClass() {
    var previous = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    try {
      session.begin();
      session.command("DELETE FROM " + CLASS_NAME);
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(previous);
    }
  }

  /** Commits one matching (active=true) record with the given name, returning its persistent RID. */
  private RID commitMatching(String name) {
    session.begin();
    var e = session.newEntity(CLASS_NAME);
    e.setProperty(NAME, name);
    e.setProperty(FLAG, true);
    var rid = ((RecordAbstract) e).getIdentity();
    session.commit();
    return rid;
  }

  /**
   * Snapshots a result set into a comparable per-row form: each row becomes the list of its property
   * values in sorted-name order, position-sensitive so an ORDER BY mismatch is caught. An entity-valued
   * column (the bound alias {@code u}) is reduced to its stable {@code name} property rather than its
   * RID: the cache-off and cache-on halves re-seed independently and get different RIDs, so comparing
   * the persistent identity would fail on harness re-seeding, not on a real divergence. Reducing to the
   * record's own {@code name} still proves the correct record is bound under {@code u} in the right
   * ORDER BY position, which is the equivalence the fold must preserve.
   */
  private static List<List<String>> snapshot(ResultSet rs) {
    var rows = new ArrayList<List<String>>();
    try (rs) {
      while (rs.hasNext()) {
        Result row = rs.next();
        var names = new ArrayList<>(row.getPropertyNames());
        names.sort(String::compareTo);
        var values = new ArrayList<String>(names.size());
        for (var n : names) {
          Object v = row.getProperty(n);
          // The bound-alias column resolves to the record (an Entity or a bare Identifiable that the
          // Result can resolve to one). RID is run-dependent (independent re-seed per flag state), so
          // reduce it to the record's own name — the stable identity for the equivalence comparison.
          Entity entity = (v instanceof Identifiable) ? row.getEntity(n) : null;
          if (entity != null) {
            values.add("entity(" + entity.getProperty(NAME) + ")");
          } else {
            values.add(String.valueOf(v));
          }
        }
        rows.add(values);
      }
    }
    return rows;
  }

  /**
   * Seeds a fixed committed set of matching records, opens a tx, populates the cache with one MATCH
   * query, applies {@code mutation} after populate, then captures the second query's row sequence.
   * Driven once per flag state; comparing the two captured sequences is the equivalence test.
   */
  private List<List<String>> runScenario(boolean cacheEnabled, String[] seedNames,
      Consumer<FrontendTransactionImpl> mutation) {
    return runScenario(cacheEnabled, seedNames, mutation, matchSql());
  }

  /** As {@link #runScenario(boolean, String[], Consumer)} but driving an explicit MATCH SQL. */
  private List<List<String>> runScenario(boolean cacheEnabled, String[] seedNames,
      Consumer<FrontendTransactionImpl> mutation, String sql) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    for (var n : seedNames) {
      commitMatching(n);
    }
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);

    session.begin();
    snapshot(session.query(sql)); // populate (flag on) or plain execution (flag off)
    mutation.accept(tx());
    var result = snapshot(session.query(sql)); // delta-replayed view (on) or fresh (off)
    session.rollback();
    return result;
  }

  private void assertEquivalent(String[] seedNames, Consumer<FrontendTransactionImpl> mutation) {
    var fresh = runScenario(false, seedNames, mutation);
    var cached = runScenario(true, seedNames, mutation);
    assertEquals(
        "Cached single-alias MATCH replay must equal a fresh uncached execution at the same moment",
        fresh, cached);
  }

  private void assertEquivalent(String[] seedNames, Consumer<FrontendTransactionImpl> mutation,
      String sql) {
    var fresh = runScenario(false, seedNames, mutation, sql);
    var cached = runScenario(true, seedNames, mutation, sql);
    assertEquals(
        "Cached single-alias MATCH replay must equal a fresh uncached execution at the same moment",
        fresh, cached);
  }

  // The mutation patterns, expressed against the single-alias shape. A loaded-record DELETE/UPDATE
  // uses addRecordOperation because session.delete(session.load(rid)) on a record committed before the
  // tx throws "not bound to current session" on the re-query (the addRecordOperation pattern is the
  // proven one from the RECORD suite).

  /** Adds a new matching record after populate (CREATE injected into the merged view). */
  private Consumer<FrontendTransactionImpl> createMatching(String name) {
    return t -> {
      var e = session.newEntity(CLASS_NAME);
      e.setProperty(NAME, name);
      e.setProperty(FLAG, true);
    };
  }

  /** A WHERE-breaking UPDATE (active=false) on a pre-committed matching record: the tuple drops. */
  private Consumer<FrontendTransactionImpl> breakWhere(RID rid) {
    return t -> {
      Entity rec = session.load(rid);
      rec.setProperty(FLAG, false);
      t.addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
    };
  }

  /** A value UPDATE that stays matched: the projected u.name re-projects to the new value. */
  private Consumer<FrontendTransactionImpl> changeName(RID rid, String newName) {
    return t -> {
      Entity rec = session.load(rid);
      rec.setProperty(NAME, newName);
      t.addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
    };
  }

  /** A DELETE of a pre-committed matching record: every tuple holding that RID drops. */
  private Consumer<FrontendTransactionImpl> deleteRecord(RID rid) {
    return t -> {
      Entity rec = session.load(rid);
      t.addRecordOperation((RecordAbstract) rec, RecordOperation.DELETED);
    };
  }

  // ===========================================================================
  // I4 equivalence — CREATE / DELETE / WHERE-break UPDATE / value UPDATE
  // ===========================================================================

  /**
   * A matching CREATE after populate is injected into the merged view at its ORDER BY position; the
   * cached replay matches a fresh MATCH that sees the new row.
   */
  @Test
  public void createAfterPopulate_matchesFresh() {
    assertEquivalent(new String[] {"alice", "carol"}, createMatching("bob"));
  }

  /**
   * A DELETE between the two queries suppresses the deleted record's tuple from the cached view. This
   * is the RID-addressability case: the projected RETURN tuple is not identifiable, so suppression must
   * key on the raw stored record's RID.
   */
  @Test
  public void deleteAfterPopulate_suppressesStaleTuple() {
    var seed = new String[] {"alice", "bob", "carol"};
    // Re-seed deterministically inside the scenario; capture a RID to delete via a fixed name.
    assertEquivalent(seed, deleteFirstMatchingByName("bob"));
  }

  /**
   * A WHERE-breaking UPDATE (active=false) between the two queries drops the now-non-matching tuple
   * from the cached view, matching a fresh MATCH whose WHERE no longer selects it.
   */
  @Test
  public void whereBreakingUpdateAfterPopulate_dropsTuple() {
    var seed = new String[] {"alice", "bob", "carol"};
    assertEquivalent(seed, breakWhereFirstMatchingByName("bob"));
  }

  /**
   * A value UPDATE that keeps the record matched re-projects the changed property: the cached view
   * emits the post-mutation u.name (and re-sorts if the ORDER BY key moved), matching a fresh MATCH.
   */
  @Test
  public void valueUpdateAfterPopulate_reprojectsAndResorts() {
    var seed = new String[] {"alice", "bob", "carol"};
    // Rename "bob" -> "zeta" so its ORDER BY position moves to the end, exercising the projected
    // ORDER BY comparison on both merge heads.
    assertEquivalent(seed, changeNameFirstMatchingByName("bob", "zeta"));
  }

  /**
   * The empty-mutation case: with no post-populate mutation the cached view replays the frozen result
   * verbatim and equals a fresh re-execution. Pins the no-delta projector path.
   */
  @Test
  public void noMutation_replaysFrozenResult() {
    assertEquivalent(new String[] {"alice", "bob", "carol"}, t -> {
    });
  }

  /**
   * Multiple post-populate CREATEs that all sort before the single cached record. The RECORD
   * sorted-merge compares the same cache head against each injected row in turn, so the cache head is
   * re-projected on each losing comparison — this is the path the comparison-time projection memo
   * covers. The cached replay must still equal a fresh execution (the memo must return the identical
   * projected tuple it computed the first time), proving the memoization is transparent.
   */
  @Test
  public void multipleInjectsBeforeOneCacheHead_matchesFresh() {
    // Seed a single matching record whose ORDER BY key ("mike") sorts after the three injected names,
    // so each inject wins the comparison against the same cache head and the merge re-reads it.
    assertEquivalent(new String[] {"mike"}, t -> {
      for (var n : new String[] {"alice", "bob", "carol"}) {
        var e = session.newEntity(CLASS_NAME);
        e.setProperty(NAME, n);
        e.setProperty(FLAG, true);
      }
    });
  }

  // ===========================================================================
  // K0_NONE single-alias MATCH — must replay real RETURN tuples, not raw records
  // ===========================================================================

  /**
   * A single-alias MATCH with a statement-level {@code LIMIT} classifies K0_NONE, not RECORD: the
   * paginated prefix is not delta-reconcilable. Such an entry must NOT install the Etap-A raw-record
   * mapper / returnProjector — the K0_NONE replay path emits stored rows verbatim without re-projecting,
   * so a mis-scoped install would store raw records and emit raw entity rows instead of the {@code u,
   * u.name} RETURN tuple. This test asserts the cached LIMIT replay equals a fresh uncached LIMIT
   * execution, which fails (raw rows vs projected tuples) if the projector install is not gated on
   * shape==RECORD.
   */
  @Test
  public void limitedSingleAliasMatchReplaysProjectedTuples() {
    assertEquivalent(new String[] {"alice", "bob", "carol"}, t -> {
    }, matchSqlLimited());
  }

  /**
   * Same K0_NONE LIMIT shape, now with a post-populate CREATE. The K0_NONE path re-executes (the
   * version gate forces a fresh run once a mutation lands), so the cached replay must still match a
   * fresh uncached LIMIT execution that sees the new row, and must emit projected tuples throughout.
   */
  @Test
  public void limitedSingleAliasMatchWithCreate_matchesFresh() {
    assertEquivalent(new String[] {"alice", "carol"}, createMatching("bob"), matchSqlLimited());
  }

  /**
   * Direct row-shape assertion for the K0_NONE LIMIT path: the cached view's emitted row must carry the
   * same column set a fresh uncached LIMIT execution produces (the RETURN tuple's columns), not the raw
   * record's own properties ({@code name}/{@code active}). The expected column set is read from a fresh
   * run rather than hardcoded so the test does not depend on the exact projected-column naming. Pins the
   * row shape directly: a projector install mis-scoped onto the K0_NONE entry would emit raw entity rows
   * whose property names include {@code active}, failing the equality check below.
   */
  @Test
  public void limitedSingleAliasMatchEmitsReturnTupleColumns() {
    commitMatching("alice");

    // Source of truth: the RETURN tuple's column set from a fresh uncached execution.
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    java.util.Set<String> expectedColumns;
    session.begin();
    try (var rs = session.query(matchSqlLimited())) {
      assertTrue("the LIMIT MATCH must return at least one row", rs.hasNext());
      expectedColumns = new java.util.HashSet<>(rs.next().getPropertyNames());
    } finally {
      session.rollback();
    }
    // A projected RETURN tuple must not expose the raw record's own WHERE-only field.
    assertFalse("the fresh RETURN tuple must not carry the raw record's own '" + FLAG + "' field",
        expectedColumns.contains(FLAG));

    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    session.begin();
    try {
      // First query populates the K0_NONE entry; second is served from it.
      try (var populate = session.query(matchSqlLimited())) {
        while (populate.hasNext()) {
          populate.next();
        }
      }
      try (var rs = session.query(matchSqlLimited())) {
        assertTrue("the cached LIMIT MATCH must return at least one row", rs.hasNext());
        Result row = rs.next();
        assertEquals(
            "the cached K0_NONE view must emit the RETURN tuple columns, not raw record fields",
            expectedColumns, new java.util.HashSet<>(row.getPropertyNames()));
      }
    } finally {
      session.rollback();
    }
  }

  // Name-keyed mutation wrappers: the RID is resolved inside the scenario tx (after re-seed) by a
  // committed-state lookup with the cache forced off, so the same logical mutation runs in both halves.

  private Consumer<FrontendTransactionImpl> deleteFirstMatchingByName(String name) {
    return t -> deleteRecord(ridByName(name)).accept(t);
  }

  private Consumer<FrontendTransactionImpl> breakWhereFirstMatchingByName(String name) {
    return t -> breakWhere(ridByName(name)).accept(t);
  }

  private Consumer<FrontendTransactionImpl> changeNameFirstMatchingByName(String name,
      String newName) {
    return t -> changeName(ridByName(name), newName).accept(t);
  }

  /** Resolves the committed RID of the single matching record with the given name. */
  private RID ridByName(String name) {
    try (var rs = session.query(
        "select from " + CLASS_NAME + " where " + NAME + " = ?", name)) {
      Result row = rs.next();
      return row.getIdentity();
    }
  }

  // ===========================================================================
  // Metadata invariant — non-empty effectiveFromClasses
  // ===========================================================================

  /**
   * The Etap-A entry's {@code effectiveFromClasses} must be non-empty: an empty closure would match no
   * mutation and the entry would replay a stale frozen result. Populates a single-alias MATCH entry and
   * asserts the stored entry carries the alias class in its closure, and that it is RECORD-shaped with a
   * returnProjector installed.
   */
  @Test
  public void etapAEntryHasNonEmptyEffectiveFromClassesAndProjector() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    commitMatching("alice");

    session.begin();
    try {
      snapshot(session.query(matchSql())); // populate one entry
      var cache = tx().getQueryResultCache();
      assertTrue("a single-alias MATCH must populate exactly one cache entry", cache.size() >= 1);
      var entry = onlyEntry(cache);
      assertEquals("single-alias MATCH folds onto the RECORD shape",
          CacheableShape.RECORD, entry.getShape());
      assertFalse("the Etap-A entry's class filter must be non-empty so mutations reconcile",
          entry.getEffectiveFromClasses().isEmpty());
      assertTrue("the alias class must be in the closure",
          entry.getEffectiveFromClasses().contains(CLASS_NAME));
      assertTrue("the Etap-A entry must carry a returnProjector",
          entry.getReturnProjector() != null);
    } finally {
      session.rollback();
    }
  }
}
