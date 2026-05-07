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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the uncovered branches in {@link IndexNotUnique} and its parent
 * {@link IndexMultiValues}: collated key compare-and-update branches in the TX stash,
 * RID-set merge in transactional accumulation, {@code getRids} with and without TX changes,
 * {@code streamEntries} cleared/non-cleared paths, and size computation when TX changes
 * are present.
 *
 * <p>Schema setup is performed outside of any transaction (before {@code session.begin()}).
 * Records are inserted in a committed transaction and then read in a subsequent transaction
 * to exercise TX-aware code paths.
 */
public class IndexNotUniqueTest extends DbTestBase {

  private static final String CLASS_NAME = "NotUniqueTestClass";
  private static final String IDX_NAME = CLASS_NAME + ".name";

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();

    // Schema setup outside any active transaction.
    var cls = session.createClass(CLASS_NAME);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex(IDX_NAME, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Seed committed data: two records with "alpha", one with "beta".
    session.begin();
    var e1 = session.newEntity(CLASS_NAME);
    e1.setProperty("name", "alpha");
    var e2 = session.newEntity(CLASS_NAME);
    e2.setProperty("name", "alpha");
    var e3 = session.newEntity(CLASS_NAME);
    e3.setProperty("name", "beta");
    session.commit();
  }

  // -----------------------------------------------------------------------
  //  getRids — no TX changes (null indexChanges path)
  // -----------------------------------------------------------------------

  /**
   * When no transaction changes exist for the index, {@code getRids()} returns the
   * committed values directly. For the key "alpha" (2 committed records), the result
   * stream must contain exactly 2 RIDs.
   */
  @Test
  public void getRids_noTxChanges_returnsCommittedValues() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> result = (Collection<RID>) index.get(session, "alpha");
    session.rollback();

    assertNotNull("result must not be null", result);
    assertEquals("must return 2 RIDs for 'alpha'", 2, result.size());
  }

  /**
   * When a key does not exist in the index and there are no TX changes, {@code getRids()}
   * returns an empty collection.
   */
  @Test
  public void getRids_noTxChanges_missingKey_returnsEmpty() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> result = (Collection<RID>) index.get(session, "nonexistent");
    session.rollback();

    assertNotNull("result must not be null for missing key", result);
    assertTrue("empty result expected for missing key", result.isEmpty());
  }

  // -----------------------------------------------------------------------
  //  getRids — with TX changes (merge path)
  // -----------------------------------------------------------------------

  /**
   * When a TX adds a new entry for an existing key, {@code getRids()} must return both
   * the committed entries and the TX-added entry. This exercises the merge path in
   * {@code IndexMultiValues.getRids()}.
   *
   * <p>We use SQL UPDATE to rename "beta" to "alpha" — this produces a PUT(alpha) in the
   * TX changes for the NOTUNIQUE index so that the key now has 3 total RIDs.
   */
  @Test
  public void getRids_withTxPut_mergesCommittedAndTxEntries() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='alpha' WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> result = (Collection<RID>) index.get(session, "alpha");
    session.rollback();

    // 2 committed "alpha" + 1 from the TX rename of "beta" → "alpha" = 3.
    assertNotNull(result);
    assertEquals("merged result must contain 3 RIDs for 'alpha' after TX put", 3, result.size());
  }

  /**
   * When a TX removes all entries for a key (all matching records renamed away),
   * {@code getRids()} must return an empty result for that key. This exercises the
   * REMOVE branch in {@code calculateTxValue} where all entries for a key are removed.
   *
   * <p>We UPDATE all "beta" records to "gamma" (there is only 1 "beta"), which inserts a
   * REMOVE(beta) + PUT(gamma) in the TX changes. After the rename, "beta" must be gone.
   */
  @Test
  public void getRids_withTxRemove_excludesRemovedEntry() {
    session.begin();
    // Rename the single "beta" record to "gamma" → removes the beta entry from the index.
    session.execute("UPDATE " + CLASS_NAME + " SET name='gamma' WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> betaResult = (Collection<RID>) index.get(session, "beta");
    session.rollback();

    // "beta" must have 0 entries after the TX rename.
    assertNotNull(betaResult);
    assertTrue("'beta' must be gone after TX rename to 'gamma'", betaResult.isEmpty());
  }

  // -----------------------------------------------------------------------
  //  streamEntries — cleared vs non-cleared TX changes
  // -----------------------------------------------------------------------

  /**
   * {@code streamEntries} with no TX changes returns the committed entries for the
   * requested keys in ascending order.
   */
  @Test
  public void streamEntries_noTxChanges_returnsCommittedEntries() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.streamEntries(session,
        List.of("alpha", "beta"), true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    // 2 "alpha" + 1 "beta" = 3 entries.
    assertEquals("must return 3 entries", 3, keys.size());
  }

  /**
   * {@code streamEntries} with a TX rename produces the TX-modified state. After renaming
   * "beta" to "alpha", streamEntries for ["alpha", "beta"] must return 3 "alpha" entries
   * and 0 "beta" entries (through the TX-aware merge path).
   */
  @Test
  public void streamEntries_withTxRename_reflectsTxState() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='alpha' WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    long alphaCount;
    long betaCount;
    try (Stream<RawPair<Object, RID>> s = index.streamEntries(session,
        List.of("alpha"), true)) {
      alphaCount = s.count();
    }
    try (Stream<RawPair<Object, RID>> s = index.streamEntries(session,
        List.of("beta"), true)) {
      betaCount = s.count();
    }
    session.rollback();

    assertEquals("alpha must have 3 entries after TX rename of beta→alpha", 3, alphaCount);
    assertEquals("beta must have 0 entries after TX rename", 0, betaCount);
  }

  /**
   * {@code streamEntries} with descendingOrder=false returns the entries in descending key
   * order. For keys ["alpha", "beta"], "beta" entries must come before "alpha" entries.
   */
  @Test
  public void streamEntries_descendingOrder_returnsEntriesDescending() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.streamEntries(session,
        List.of("alpha", "beta"), false)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals("must return 3 entries", 3, keys.size());
    // Verify descending: "beta" >= "alpha", so "beta" must come first (or equal).
    assertTrue("first entry must be 'beta' in descending order", keys.get(0).equals("beta"));
    assertFalse("last entries must be 'alpha'", keys.get(keys.size() - 1).equals("beta"));
  }

  // -----------------------------------------------------------------------
  //  streamEntriesBetween / Major / Minor TX paths
  // -----------------------------------------------------------------------

  /**
   * {@code streamEntriesBetween} with TX changes reflects the renamed key. After renaming
   * "beta" to "amm" (which falls inside [alpha, beta]), the TX-only key "amm" must appear
   * in the range scan.
   */
  @Test
  public void streamEntriesBetween_withTxRename_includesTxOnlyKey() {
    session.begin();
    // Rename "beta" to "amm" which is inside [alpha, beta].
    session.execute("UPDATE " + CLASS_NAME + " SET name='amm' WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.streamEntriesBetween(session,
        "alpha", true, "beta", true, true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertTrue("TX-only key 'amm' must appear in range [alpha, beta]", keys.contains("amm"));
    assertTrue("committed 'alpha' must appear", keys.contains("alpha"));
    assertFalse("'beta' must be absent after rename", keys.contains("beta"));
  }

  /**
   * {@code streamEntriesMajor} with ascending order and TX changes. After renaming "alpha"
   * records to "zzz", the TX-only key "zzz" must appear in the "major beta" stream.
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

    assertTrue("TX-only key 'zzz' must appear in the major scan", keys.contains("zzz"));
    assertTrue("'beta' must appear", keys.contains("beta"));
  }

  /**
   * {@code streamEntriesMinor} with ascending order and TX changes. After renaming "beta"
   * to "aaa", the TX-only key "aaa" must appear in the "minor alpha" (≤ alpha) stream.
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

    assertTrue("TX-only key 'aaa' must appear in the minor scan", keys.contains("aaa"));
    assertTrue("committed 'alpha' must appear", keys.contains("alpha"));
    assertFalse("'beta' must be absent after rename to 'aaa'", keys.contains("beta"));
  }

  // -----------------------------------------------------------------------
  //  size — with TX changes
  // -----------------------------------------------------------------------

  /**
   * {@code size} on a NOTUNIQUE index when TX changes are present computes the total count
   * by materializing the stream. After adding a new entry via TX rename, size must reflect
   * the change.
   */
  @Test
  public void size_withTxChanges_reflectsTxState() {
    // Before any TX: 3 committed entries.
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    long beforeTx = index.size(session);
    session.rollback();

    assertEquals("initial size must be 3", 3, beforeTx);

    // Add a new entry via TX (rename "beta" to "newkey").
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='newkey' WHERE name='beta'").close();
    index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    long duringTx = index.size(session);
    session.rollback();

    // TX rename produces: remove "beta" + add "newkey" → net effect is still 3 entries.
    assertEquals("size during TX must still be 3 (rename is 1-for-1)", 3, duringTx);
  }

  /**
   * {@code canBeUsedInEqualityOperators} must return true for IndexNotUnique, as it
   * supports equality lookups via {@code getRids}.
   */
  @Test
  public void canBeUsedInEqualityOperators_returnsTrue() {
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    assertTrue("IndexNotUnique must support equality operators",
        index.canBeUsedInEqualityOperators());
  }

  // -----------------------------------------------------------------------
  //  interpretTxKeyChanges — NonUnique interpretation
  // -----------------------------------------------------------------------

  /**
   * Exercises the {@code NonUnique} interpretation path in
   * {@code IndexNotUnique.interpretTxKeyChanges}: multiple PUT entries for the same key
   * in the same TX are all preserved (no de-duplication like in Unique mode).
   *
   * <p>Two separate UPDATE statements rename two different "alpha" records to "zeta",
   * producing two PUT(zeta) TX entries. The stream must contain both.
   */
  @Test
  public void interpretTxKeyChanges_multiplePutsSameKey_allPreserved() {
    session.begin();
    // Rename both "alpha" records to "zeta" — 2 PUT(zeta) entries in the TX changes.
    session.execute("UPDATE " + CLASS_NAME + " SET name='zeta' WHERE name='alpha'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    @SuppressWarnings("unchecked")
    Collection<RID> result = (Collection<RID>) index.get(session, "zeta");
    session.rollback();

    assertNotNull(result);
    assertEquals("all 2 TX-only PUT entries for 'zeta' must be present", 2, result.size());
  }
}
