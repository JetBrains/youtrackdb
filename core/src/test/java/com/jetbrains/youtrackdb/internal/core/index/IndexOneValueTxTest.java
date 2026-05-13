package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the uncovered TX-aware paths in {@link IndexOneValue}: the {@code getRids} merge path
 * (TX index changes present, {@code cleared=false} vs {@code cleared=true}), the {@code get}
 * no-result branch, {@code descStream} within a TX, and {@code streamEntries} with a TX
 * accumulator.
 *
 * <p>Schema setup is outside any transaction. Records are inserted in a committed baseline
 * transaction, then exercised via SQL UPDATE inside a new transaction to populate
 * {@code FrontendTransactionIndexChanges}.
 *
 * <p>Important invariants from prior steps:
 * <ul>
 *   <li>{@code Index.get(session, key)} returns {@code Object} (a RID or null) for UNIQUE
 *       indexes — do NOT call {@code .iterator().hasNext()} on the result.</li>
 *   <li>Descending TX streams for the non-cleared merge case use an internally ascending
 *       comparator; assert key presence rather than strict ordering.</li>
 * </ul>
 */
public class IndexOneValueTxTest extends DbTestBase {

  private static final String CLASS_NAME = "OneValueTxTestClass";
  private static final String IDX_NAME = CLASS_NAME + ".name";

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();

    // Schema setup outside any transaction.
    var cls = session.createClass(CLASS_NAME);
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex(IDX_NAME, SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Seed committed baseline: five distinct string keys.
    session.begin();
    for (var key : List.of("alpha", "beta", "delta", "epsilon", "gamma")) {
      var e = session.newEntity(CLASS_NAME);
      e.setProperty("name", key);
    }
    session.commit();
  }

  // -----------------------------------------------------------------------
  //  get() — UNIQUE index return type is Object (RID or null)
  // -----------------------------------------------------------------------

  /**
   * {@code get()} on a UNIQUE index returns the RID when the key exists.
   * This is the "found" branch in {@code IndexOneValue.get()}.
   */
  @Test
  public void get_existingKey_returnsRid() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    Object result = index.get(session, "alpha");
    session.rollback();

    assertNotNull("must return a RID for an existing key", result);
    assertTrue("result must be a RID instance", result instanceof RID);
  }

  /**
   * {@code get()} on a UNIQUE index returns null when the key does not exist.
   * This is the "no-result" branch of {@code IndexOneValue.get()}.
   */
  @Test
  public void get_missingKey_returnsNull() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    Object result = index.get(session, "nonexistent");
    session.rollback();

    assertNull("UNIQUE index get() must return null for a missing key", result);
  }

  // -----------------------------------------------------------------------
  //  getRids — with TX changes (merge path)
  // -----------------------------------------------------------------------

  /**
   * {@code getRids()} when TX changes are present (non-cleared case): the TX-added key
   * "zzz" (via renaming "alpha" → "zzz") must be found, and "alpha" must be gone.
   * This exercises the {@code indexChanges != null} and {@code !cleared} branch in
   * {@code IndexOneValue.getRids()}.
   */
  @Test
  public void getRids_withTxRename_returnsUpdatedValue() {
    // Capture the RID of the committed "alpha" record before any TX, so we can verify that
    // the post-rename "zzz" lookup returns the SAME RID — pinning identity, not just
    // existence.
    Object alphaRidBeforeRename;
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    alphaRidBeforeRename = index.get(session, "alpha");
    session.rollback();
    assertNotNull("baseline lookup of 'alpha' must yield a RID", alphaRidBeforeRename);

    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='zzz' WHERE name='alpha'").close();

    index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    // After rename: "alpha" should not be found, "zzz" should be found and must point at
    // the same RID we captured before.
    Object alphaResult = index.get(session, "alpha");
    Object zzzResult = index.get(session, "zzz");
    session.rollback();

    assertNull("'alpha' must be null after TX rename", alphaResult);
    assertEquals("'zzz' must resolve to the renamed entity's RID",
        alphaRidBeforeRename, zzzResult);
  }

  /**
   * {@code getRids()} when TX changes are present and the change is a REMOVE
   * (key deleted but not re-added): the lookup must return empty stream (null result).
   * This exercises the REMOVE-only path in {@code calculateTxIndexEntry}.
   */
  @Test
  public void getRids_withTxRemoveOnly_returnsNull() {
    session.begin();
    // Delete one entity with name="beta" — generates REMOVE(beta) in the TX changes.
    session.execute("DELETE FROM " + CLASS_NAME + " WHERE name='beta'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    Object result = index.get(session, "beta");
    session.rollback();

    assertNull("deleted key 'beta' must return null during the TX", result);
  }

  // -----------------------------------------------------------------------
  //  descStream — with TX changes
  // -----------------------------------------------------------------------

  /**
   * {@code descStream()} inside a TX where index changes are present. After renaming
   * "alpha" to "zzz", the descending stream must contain "zzz" (TX-only) and must NOT
   * contain "alpha" (renamed away). Key presence only is asserted (not strict order) for
   * the non-cleared merge path.
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

    assertTrue("'zzz' (TX-only rename) must appear in descStream", keys.contains("zzz"));
    assertFalse("'alpha' (renamed away) must NOT appear in descStream", keys.contains("alpha"));
    assertTrue("committed 'beta' must appear", keys.contains("beta"));
  }

  /**
   * {@code descStream()} outside any TX (no index changes) returns all committed entries
   * in descending order.
   */
  @Test
  public void descStream_noTxChanges_returnsAllEntriesDescending() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.descStream(session)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals("must return all 5 entries", 5, keys.size());
    for (int i = 1; i < keys.size(); i++) {
      assertTrue("descending order violated at index " + i,
          keys.get(i - 1).compareTo(keys.get(i)) >= 0);
    }
  }

  // -----------------------------------------------------------------------
  //  streamEntries — TX accumulator merge (IndexOneValue-specific path)
  // -----------------------------------------------------------------------

  /**
   * {@code streamEntries} with TX changes for a UNIQUE index: the TX-added key
   * "czz" (rename of "gamma") must appear when querying keys that include "czz",
   * and "gamma" must be absent.
   */
  @Test
  public void streamEntries_withTxRename_reflectsTxState() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='czz' WHERE name='gamma'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    // Query for the TX-added key "czz" and the original "gamma".
    try (Stream<RawPair<Object, RID>> s = index.streamEntries(session,
        List.of("czz", "gamma"), true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertTrue("TX-only 'czz' must appear in streamEntries", keys.contains("czz"));
    assertFalse("renamed-away 'gamma' must NOT appear", keys.contains("gamma"));
  }

  /**
   * {@code streamEntries} in descending order with TX changes returns TX-modified state
   * in a consistent (key-presence) manner. The TX-added key "zzz" must be present.
   */
  @Test
  public void streamEntries_descendingWithTxRename_includesTxOnlyKey() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='zzz' WHERE name='alpha'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.streamEntries(session,
        List.of("zzz", "alpha", "beta"), false)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertTrue("TX-only 'zzz' must appear in descending streamEntries", keys.contains("zzz"));
    assertFalse("renamed-away 'alpha' must NOT appear", keys.contains("alpha"));
  }

  // -----------------------------------------------------------------------
  //  TX commit and rollback observable behavior
  // -----------------------------------------------------------------------

  /**
   * After a transaction that renames a key commits successfully, the new key is visible in
   * subsequent queries and the old key is gone. This verifies the TX commit path for
   * UNIQUE indexes.
   */
  @Test
  public void txCommit_renameKey_newKeyVisibleOldGone() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='newname' WHERE name='beta'").close();
    session.commit();

    // After commit: "newname" must exist, "beta" must be gone.
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    Object newResult = index.get(session, "newname");
    Object oldResult = index.get(session, "beta");
    session.rollback();

    assertNotNull("committed new key 'newname' must be findable", newResult);
    assertNull("old key 'beta' must be gone after commit", oldResult);
  }

  /**
   * After a transaction that renames a key is rolled back, the original key remains
   * unchanged and the new key does not appear. This verifies the TX rollback path.
   */
  @Test
  public void txRollback_renameKey_originalKeyRestoredNewKeyAbsent() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='rolledback' WHERE name='delta'").close();
    session.rollback();

    // After rollback: "delta" must still exist, "rolledback" must not.
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    Object originalResult = index.get(session, "delta");
    Object newResult = index.get(session, "rolledback");
    session.rollback();

    assertNotNull("original key 'delta' must be restored after rollback", originalResult);
    assertNull("rolled-back key 'rolledback' must not exist", newResult);
  }

  // -----------------------------------------------------------------------
  //  getRidsIgnoreTx — reads storage directly, bypassing TX changes
  // -----------------------------------------------------------------------

  /**
   * {@code getRidsIgnoreTx()} reads the committed storage state and bypasses any active TX
   * changes. Even after renaming "epsilon" to "zzz" inside a TX, {@code getRidsIgnoreTx()}
   * must still report "epsilon" (the committed value) as found.
   */
  @Test
  public void getRidsIgnoreTx_duringTxRename_returnsCommittedValue() {
    session.begin();
    session.execute("UPDATE " + CLASS_NAME + " SET name='zzz' WHERE name='epsilon'").close();

    var index = (IndexOneValue) session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    long epsilonCount;
    try (Stream<RID> s = index.getRidsIgnoreTx(session, "epsilon")) {
      epsilonCount = s.count();
    }
    session.rollback();

    assertEquals("getRidsIgnoreTx must see the committed 'epsilon' entry", 1, epsilonCount);
  }

  // -----------------------------------------------------------------------
  //  calculateTxIndexEntry — PUT after REMOVE re-establishes the entry
  // -----------------------------------------------------------------------

  /**
   * Within a TX, deleting then re-inserting the same key (via UPDATE to same name) results
   * in the key being present. This exercises the PUT-after-REMOVE logic in
   * {@code calculateTxIndexEntry}.
   */
  @Test
  public void calculateTxIndexEntry_putAfterRemove_keyIsPresent() {
    session.begin();
    // Rename "gamma" away then rename it back: generates REMOVE(gamma) + PUT(gamma).
    session.execute("UPDATE " + CLASS_NAME + " SET name='gamma_tmp' WHERE name='gamma'").close();
    session.execute("UPDATE " + CLASS_NAME + " SET name='gamma' WHERE name='gamma_tmp'").close();

    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    Object result = index.get(session, "gamma");
    session.rollback();

    assertNotNull("'gamma' must be present after TX remove+put round-trip", result);
  }

  // -----------------------------------------------------------------------
  //  cleared-TX branch — IndexOneValue.streamEntriesBetween must drop committed entries
  //  and yield ONLY the TX stream when indexChanges.cleared == true.
  // -----------------------------------------------------------------------

  /**
   * Captures the RID of an existing committed entry under the given key. The cleared-TX
   * tests below reuse this RID for their TX-side OPERATION.PUT so the value points at a
   * real, committed record — future RID-validation tightening (e.g. rejecting the
   * {@code #-1:-1} sentinel) will not silently invalidate these tests.
   */
  private RID capturedRidForCommittedKey(String committedKey) {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var captured = (RID) index.get(session, committedKey);
    session.rollback();
    assertNotNull("baseline lookup of '" + committedKey + "' must yield a RID", captured);
    return captured;
  }

  /**
   * When the TX index changes have {@code cleared == true} (the production
   * {@code FrontendTransactionImpl} sets this when an OPERATION.CLEAR is recorded), the
   * {@code IndexOneValue.streamEntriesBetween} branch at the {@code if (indexChanges.cleared)}
   * guard returns ONLY the TX stream — the committed alpha/beta/gamma/delta/epsilon entries
   * must NOT appear, and the freshly-PUT TX-only key must.
   *
   * <p>The cleared flag is set by issuing an OPERATION.CLEAR via the public
   * {@code FrontendTransaction.addIndexEntry} API; a subsequent OPERATION.PUT for "delta"
   * leaves {@code cleared == true} but populates {@code changesPerKey} so the TX stream is
   * non-empty. The PUT uses a real committed RID (captured before the CLEAR) so the test
   * survives any future RID-validation tightening that would reject {@code #-1:-1}.
   */
  @Test
  public void streamEntriesBetween_clearedTxChanges_returnsOnlyTxAddedKeys() {
    var capturedRid = capturedRidForCommittedKey("alpha");

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var tx = session.getTransactionInternal();

    // Mark the TX index changes as "cleared" without going through SQL TRUNCATE
    // (which would also flush committed storage). The CLEAR op flips the flag; the PUT
    // afterwards populates the changesPerKey map so the TX stream emits one entry.
    tx.addIndexEntry(index, IDX_NAME, OPERATION.CLEAR, null, null);
    tx.addIndexEntry(index, IDX_NAME, OPERATION.PUT, "delta", capturedRid);

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

  /**
   * {@code IndexOneValue.streamEntries(keys, asc=true)} cleared-TX branch: when
   * {@code indexChanges.cleared == true}, the ascending key-list query must drop
   * committed entries and yield only the TX-added key. Asks for both "delta" (TX-PUT) and
   * "alpha" (committed-only); only "delta" must appear.
   */
  @Test
  public void streamEntries_clearedTxChanges_returnsOnlyTxAddedKeys() {
    var capturedRid = capturedRidForCommittedKey("alpha");

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var tx = session.getTransactionInternal();

    tx.addIndexEntry(index, IDX_NAME, OPERATION.CLEAR, null, null);
    tx.addIndexEntry(index, IDX_NAME, OPERATION.PUT, "delta", capturedRid);

    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s =
        index.streamEntries(session, List.of("delta", "alpha"), true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals(
        "cleared TX streamEntries must drop committed keys and yield only TX-added 'delta'",
        List.of("delta"), keys);
  }

  /**
   * {@code IndexOneValue.streamEntriesMajor} cleared-TX branch: when
   * {@code indexChanges.cleared == true}, the major (>= fromKey) ascending stream must drop
   * committed entries and yield only the TX-added key.
   */
  @Test
  public void streamEntriesMajor_clearedTxChanges_returnsOnlyTxAddedKeys() {
    var capturedRid = capturedRidForCommittedKey("alpha");

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var tx = session.getTransactionInternal();

    tx.addIndexEntry(index, IDX_NAME, OPERATION.CLEAR, null, null);
    tx.addIndexEntry(index, IDX_NAME, OPERATION.PUT, "delta", capturedRid);

    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s =
        index.streamEntriesMajor(session, "alpha", true, true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals(
        "cleared TX streamEntriesMajor must drop committed keys and yield only TX-added 'delta'",
        List.of("delta"), keys);
  }

  /**
   * {@code IndexOneValue.streamEntriesMinor} cleared-TX branch: when
   * {@code indexChanges.cleared == true}, the minor (<= toKey) ascending stream must drop
   * committed entries and yield only the TX-added key.
   */
  @Test
  public void streamEntriesMinor_clearedTxChanges_returnsOnlyTxAddedKeys() {
    var capturedRid = capturedRidForCommittedKey("alpha");

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var tx = session.getTransactionInternal();

    tx.addIndexEntry(index, IDX_NAME, OPERATION.CLEAR, null, null);
    tx.addIndexEntry(index, IDX_NAME, OPERATION.PUT, "delta", capturedRid);

    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s =
        index.streamEntriesMinor(session, "zzz", true, true)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals(
        "cleared TX streamEntriesMinor must drop committed keys and yield only TX-added 'delta'",
        List.of("delta"), keys);
  }
}
