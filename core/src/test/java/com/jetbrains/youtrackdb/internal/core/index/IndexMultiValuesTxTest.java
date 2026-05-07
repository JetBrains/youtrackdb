package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the uncovered TX-aware paths in {@link IndexMultiValues}: the {@code getRids} merge
 * path (TX changes present, both cleared and non-cleared), {@code descStream} with TX changes,
 * {@code streamEntries} TX accumulator merge, {@code size} when TX changes are active, and the
 * {@code calculateTxValue} null-return path.
 *
 * <p>Uses a NOTUNIQUE index because {@link IndexNotUnique} extends {@link IndexMultiValues}
 * and exercises all its multi-value container paths.
 *
 * <p>Schema setup is performed outside any active transaction (DbTestBase constraint). SQL
 * UPDATE is used inside a TX to trigger index change accumulation in
 * {@code FrontendTransactionIndexChanges}.
 *
 * <p>Important invariant: descending TX streams for the non-cleared merge case use an
 * internally ascending comparator; tests assert key presence rather than strict ordering.
 */
public class IndexMultiValuesTxTest extends DbTestBase {

  private static final String CLASS_NAME = "MultiValuesTxTestClass";
  private static final String IDX_NAME = CLASS_NAME + ".name";

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();

    // Schema setup outside any transaction.
    var cls = session.createClass(CLASS_NAME);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex(IDX_NAME, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Seed committed baseline: two "alpha", one "beta", two "gamma".
    session.begin();
    for (var key : List.of("alpha", "alpha", "beta", "gamma", "gamma")) {
      var e = session.newEntity(CLASS_NAME);
      e.setProperty("name", key);
    }
    session.commit();
  }

  // -----------------------------------------------------------------------
  //  getRids — no TX changes (null indexChanges path)
  // -----------------------------------------------------------------------

  /**
   * Without TX changes, {@code getRids()} delegates directly to the committed storage.
   * "alpha" has 2 committed entries; the result must contain exactly 2 RIDs.
   */
  @Test
  public void getRids_noTxChanges_returnsCommittedValues() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> result = (Collection<RID>) index.get(session, "alpha");
    session.rollback();

    assertNotNull(result);
    assertEquals("2 committed 'alpha' entries must be returned", 2, result.size());
  }

  /**
   * Without TX changes, querying a key that has no entries returns an empty collection.
   * This exercises the empty-collection (null {@code txChanges}) path.
   */
  @Test
  public void getRids_noTxChanges_missingKey_returnsEmpty() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> result = (Collection<RID>) index.get(session, "missing");
    session.rollback();

    assertNotNull(result);
    assertTrue("missing key must return empty collection", result.isEmpty());
  }

  // -----------------------------------------------------------------------
  //  getRids — with TX changes (merge path)
  // -----------------------------------------------------------------------

  /**
   * {@code getRids()} with TX changes present: renaming the single "beta" to "zeta" adds a
   * REMOVE(beta) + PUT(zeta) in the TX changes. The "beta" collection must become empty and
   * "zeta" must appear with 1 entry. This exercises the TX merge path in
   * {@code IndexMultiValues.getRids()}.
   */
  @Test
  public void getRids_withTxRename_mergesCommittedAndTxState() {
    session.begin();
    // Rename the single "beta" to "zeta" to produce a remove from "beta" and add to "zeta".
    session.execute("UPDATE " + CLASS_NAME + " SET name='zeta' WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> betaResult = (Collection<RID>) index.get(session, "beta");
    @SuppressWarnings("unchecked")
    Collection<RID> zetaResult = (Collection<RID>) index.get(session, "zeta");
    session.rollback();

    assertNotNull(betaResult);
    assertTrue("'beta' must be empty after TX rename to 'zeta'", betaResult.isEmpty());
    assertNotNull(zetaResult);
    assertEquals("TX-added 'zeta' must have 1 entry", 1, zetaResult.size());
  }

  /**
   * When all entries for a key are removed in the TX (REMOVE with null value removes all),
   * {@code calculateTxValue} returns null and the result collection is empty.
   */
  @Test
  public void getRids_withTxRemoveAll_returnsEmpty() {
    session.begin();
    // Delete all "beta" records → a REMOVE(null) for "beta" in the TX changes.
    session.execute("DELETE FROM " + CLASS_NAME + " WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> result = (Collection<RID>) index.get(session, "beta");
    session.rollback();

    assertNotNull(result);
    assertTrue("all-removed key must return empty collection during TX", result.isEmpty());
  }

  // -----------------------------------------------------------------------
  //  descStream — with TX changes
  // -----------------------------------------------------------------------

  /**
   * {@code descStream()} inside a TX where index changes are present. After renaming both
   * "alpha" records to "zzz" (produces 2 TX entries for "zzz"), the descending stream must
   * contain "zzz" entries and must NOT contain "alpha". Key presence only is asserted
   * (not strict order) for the non-cleared merge path.
   */
  @Test
  public void descStream_withTxRename_includesTxOnlyKey() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='zzz' WHERE name='alpha'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.descStream(session)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertTrue("TX-only 'zzz' must appear in descStream", keys.contains("zzz"));
    assertFalse("renamed-away 'alpha' must NOT appear in descStream", keys.contains("alpha"));
    assertTrue("committed 'beta' must appear", keys.contains("beta"));
    assertTrue("committed 'gamma' must appear", keys.contains("gamma"));
  }

  /**
   * {@code descStream()} without TX changes returns all committed entries. 5 committed
   * entries must be returned.
   */
  @Test
  public void descStream_noTxChanges_returnsAllCommittedEntries() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    long count;
    try (Stream<RawPair<Object, RID>> s = index.descStream(session)) {
      count = s.count();
    }
    session.rollback();

    assertEquals("all 5 committed entries must appear in descStream", 5, count);
  }

  // -----------------------------------------------------------------------
  //  streamEntries — TX accumulator merge
  // -----------------------------------------------------------------------

  /**
   * {@code streamEntries} with TX changes on a NOTUNIQUE index: renaming both "gamma"
   * entries to "theta" produces two TX entries. Querying "theta" must yield 2 entries
   * and "gamma" must yield 0.
   */
  @Test
  public void streamEntries_withTxRename_reflectsTxState() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='theta' WHERE name='gamma'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    long thetaCount;
    long gammaCount;
    try (Stream<RawPair<Object, RID>> s = index.streamEntries(session, List.of("theta"), true)) {
      thetaCount = s.count();
    }
    try (Stream<RawPair<Object, RID>> s = index.streamEntries(session, List.of("gamma"), true)) {
      gammaCount = s.count();
    }
    session.rollback();

    assertEquals("TX-added 'theta' must have 2 entries", 2, thetaCount);
    assertEquals("renamed-away 'gamma' must have 0 entries", 0, gammaCount);
  }

  /**
   * {@code streamEntries} in descending order with TX changes. The TX-added key must be
   * present in the descending result set.
   */
  @Test
  public void streamEntries_descendingWithTxRename_includesTxOnlyKey() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='zzz' WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.streamEntries(session,
        List.of("zzz", "beta", "alpha"), false)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertTrue("TX-only 'zzz' must appear in descending streamEntries", keys.contains("zzz"));
    assertFalse("renamed-away 'beta' must NOT appear", keys.contains("beta"));
  }

  // -----------------------------------------------------------------------
  //  streamEntriesBetween / Major / Minor TX paths
  // -----------------------------------------------------------------------

  /**
   * {@code streamEntriesBetween} with TX changes on a NOTUNIQUE index. Renaming "beta" to
   * "amm" (inside [alpha, beta]) makes "amm" appear in the range scan and "beta" disappear.
   */
  @Test
  public void streamEntriesBetween_withTxRename_includesTxOnlyKey() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='amm' WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.streamEntriesBetween(session,
        "alpha", true, "beta", true, true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertTrue("TX-only 'amm' must appear in range [alpha, beta]", keys.contains("amm"));
    assertTrue("committed 'alpha' must appear", keys.contains("alpha"));
    assertFalse("renamed-away 'beta' must NOT appear", keys.contains("beta"));
  }

  /**
   * {@code streamEntriesMajor} with TX changes: renaming all "alpha" entries to "zzz"
   * makes "zzz" appear in the "major beta" (>= beta) stream.
   */
  @Test
  public void streamEntriesMajor_withTxRename_includesTxOnlyKey() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='zzz' WHERE name='alpha'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.streamEntriesMajor(session, "beta", true, true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertTrue("TX-only 'zzz' must appear in major scan", keys.contains("zzz"));
    assertTrue("committed 'beta' must appear", keys.contains("beta"));
    assertTrue("committed 'gamma' must appear", keys.contains("gamma"));
  }

  /**
   * {@code streamEntriesMinor} with TX changes: renaming "beta" to "aaa" (which falls
   * before "alpha") makes "aaa" appear in the "minor alpha" (≤ alpha) stream.
   */
  @Test
  public void streamEntriesMinor_withTxRename_includesTxOnlyKey() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='aaa' WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.streamEntriesMinor(session, "alpha", true, true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertTrue("TX-only 'aaa' must appear in minor scan", keys.contains("aaa"));
    assertTrue("committed 'alpha' must appear", keys.contains("alpha"));
    assertFalse("renamed-away 'beta' must NOT appear", keys.contains("beta"));
  }

  // -----------------------------------------------------------------------
  //  size — with and without TX changes
  // -----------------------------------------------------------------------

  /**
   * {@code size} on a NOTUNIQUE index when TX changes are present computes the total by
   * materializing the stream. A TX rename preserves the total count (1-for-1 replacement),
   * so size must remain 5.
   */
  @Test
  public void size_withTxRename_remainsSameCount() {
    // Baseline size check (no TX changes active).
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    long noTxSize = index.size(session);
    session.rollback();
    assertEquals("baseline size must be 5", 5, noTxSize);

    // During TX rename: the total count must stay 5.
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='zzz' WHERE name='beta'").close();
    index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    long txSize = index.size(session);
    session.rollback();

    assertEquals("size during TX rename must remain 5 (1-for-1)", 5, txSize);
  }

  // -----------------------------------------------------------------------
  //  calculateTxValue — null return when no changes for key
  // -----------------------------------------------------------------------

  /**
   * {@code calculateTxValue} returns null when the TX changes map has no entry for the
   * requested key. This is observable via {@code getRids}: in a TX where only "beta" has
   * changes, querying "alpha" must still return the 2 committed RIDs (not null/empty).
   */
  @Test
  public void calculateTxValue_keyWithNoTxChanges_fallsBackToCommittedValue() {
    session.begin();
    // Only "beta" has TX changes; "alpha" does not.
    session.execute("UPDATE " + CLASS_NAME + " SET name='zeta' WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> alphaResult = (Collection<RID>) index.get(session, "alpha");
    session.rollback();

    assertNotNull(alphaResult);
    assertEquals("'alpha' with no TX changes must return 2 committed RIDs", 2, alphaResult.size());
  }

  // -----------------------------------------------------------------------
  //  cleared-TX branch — IndexMultiValues.streamEntriesBetween must drop committed entries
  //  and yield ONLY the TX stream when indexChanges.cleared == true.
  // -----------------------------------------------------------------------

  /**
   * When the TX index changes have {@code cleared == true} (set by an OPERATION.CLEAR via
   * the public {@code FrontendTransaction.addIndexEntry} API), {@code IndexMultiValues
   * .streamEntriesBetween}'s {@code if (indexChanges.cleared)} branch returns ONLY the TX
   * stream — committed multi-value entries (alpha×2, beta, gamma×2) must NOT appear, and the
   * freshly-PUT TX-only key "delta" must.
   */
  @Test
  public void streamEntriesBetween_clearedTxChanges_returnsOnlyTxAddedKeys() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var tx = session.getTransactionInternal();

    tx.addIndexEntry(index, IDX_NAME, OPERATION.CLEAR, null, null);

    var placeholderRid =
        com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal.fromString("#-1:-1", false);
    tx.addIndexEntry(index, IDX_NAME, OPERATION.PUT, "delta", placeholderRid);

    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s =
        index.streamEntriesBetween(session, "alpha", true, "zzz", true, true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals(
        "cleared TX must drop committed keys and yield only TX-added 'delta'",
        List.of("delta"), keys);
  }
}
