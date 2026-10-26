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
 * <p>Descending TX streams merge pending and stored entries in descending key order.
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

  /** Remove followed by re-add must not make the committed RID appear twice on any read path. */
  @Test
  public void removeAndReadd_hasSingleOwnerAndMatchesCommit() {
    assertOverlayChanges(List.of(OPERATION.REMOVE, OPERATION.PUT), false, 2);
  }

  /** A PUT alone also transfers ownership of an already committed RID to the TX stream. */
  @Test
  public void putCommittedRid_hasSingleOwnerAndMatchesCommit() {
    assertOverlayChanges(List.of(OPERATION.PUT), false, 2);
  }

  /** Repeated PUTs and a partial cancellation must still emit each RID exactly once. */
  @Test
  public void repeatedPutsWithPartialCancellation_matchCommit() {
    assertOverlayChanges(List.of(OPERATION.PUT, OPERATION.PUT, OPERATION.REMOVE), false, 2);
  }

  /** Removing a committed RID without re-adding it leaves only its peer under the key. */
  @Test
  public void removeOnly_hidesCommittedRidAndMatchesCommit() {
    assertOverlayChanges(List.of(OPERATION.REMOVE), false, 1);
  }

  /** A RID committed under another key must be visible once when added to this key. */
  @Test
  public void putNewRid_preservesCommittedPeersAndMatchesCommit() {
    assertOverlayChanges(List.of(OPERATION.PUT), true, 3);
  }

  /** Whole-key removal discards both peers, then a PUT restores only the chosen RID. */
  @Test
  public void removeWholeKeyThenPut_returnsOnlyReaddedRid() {
    assertOverlayChanges(List.of(OPERATION.REMOVE, OPERATION.PUT), false, 1, true);
  }

  private void assertOverlayChanges(List<OPERATION> operations, boolean newRid, int count) {
    assertOverlayChanges(operations, newRid, count, false);
  }

  private void assertOverlayChanges(List<OPERATION> operations, boolean newRid, int count,
      boolean removeWholeKey) {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    List<RID> original;
    try (var stream = index.getRids(session, "alpha")) {
      original = stream.sorted().toList();
    }
    RID changed;
    try (var stream = index.getRids(session, newRid ? "beta" : "alpha")) {
      changed = stream.sorted().toList().get(newRid ? 0 : 1);
    }
    session.rollback();

    var expected = new ArrayList<>(original);
    if (removeWholeKey) {
      expected.clear();
    } else if (operations.equals(List.of(OPERATION.REMOVE))) {
      expected.remove(changed);
    }
    if (!operations.equals(List.of(OPERATION.REMOVE)) && !expected.contains(changed)) {
      expected.add(changed);
    }
    assertEquals(count, expected.size());

    // Run the same changes twice to check rollback isolation and then commit equivalence.
    // The overlay accepts a synthetic whole-key REMOVE, but the multi-value storage engine
    // requires a RID on removal. Exercise that read state without sending it to commit.
    for (boolean commit : removeWholeKey ? List.of(false) : List.of(false, true)) {
      session.begin();
      for (var operation : operations) {
        session.getTransactionInternal().addIndexEntry(index, IDX_NAME, operation, "alpha",
            removeWholeKey && operation == OPERATION.REMOVE ? null : changed);
      }
      assertAlphaReadPaths(index, expected);
      if (commit) {
        session.commit();
      } else {
        session.rollback();
      }
      session.begin();
      assertAlphaReadPaths(index, commit ? expected : original);
      session.rollback();
    }
  }

  private void assertAlphaReadPaths(Index index, List<RID> expected) {
    try (var stream = index.getRids(session, "alpha")) {
      assertSameRids(expected, stream);
    }
    for (boolean asc : List.of(true, false)) {
      try (var stream = asc ? index.stream(session) : index.descStream(session)) {
        assertSameRids(expected, stream.filter(p -> "alpha".equals(p.first()))
            .map(RawPair::second));
      }
      try (var stream = index.streamEntries(session, List.of("alpha"), asc)) {
        assertSameRids(expected, stream.map(RawPair::second));
      }
      try (var stream = index.streamEntriesBetween(session, "alpha", true, "alpha", true, asc)) {
        assertSameRids(expected, stream.map(RawPair::second));
      }
      try (var stream = index.streamEntriesMajor(session, "alpha", true, asc)) {
        assertSameRids(expected, stream.filter(p -> "alpha".equals(p.first()))
            .map(RawPair::second));
      }
      try (var stream = index.streamEntriesMinor(session, "alpha", true, asc)) {
        assertSameRids(expected, stream.map(RawPair::second));
      }
    }
    assertEquals("size must count each pair once", 3 + expected.size(), index.size(session));
  }

  private void assertSameRids(List<RID> expected, Stream<RID> actual) {
    // Lists retain multiplicity. A set comparison would hide the regression.
    assertEquals(expected.stream().sorted().toList(), actual.sorted().toList());
  }

  /**
   * Only-from and only-to ranges must include re-added and TX-only keys on the requested
   * side, exclude keys on the other side, and honor inclusivity in both scan directions.
   */
  @Test
  public void oneBoundRanges_selectCorrectChangedKeysAndMatchCommit() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    List<RID> alpha;
    RID beta;
    List<RID> gamma;
    try (var stream = index.getRids(session, "alpha")) {
      alpha = stream.sorted().toList();
    }
    try (var stream = index.getRids(session, "beta")) {
      beta = stream.toList().getFirst();
    }
    try (var stream = index.getRids(session, "gamma")) {
      gamma = stream.sorted().toList();
    }
    for (var pair : List.of(new RawPair<>("alpha", alpha.getLast()),
        new RawPair<>("gamma", gamma.getLast()))) {
      index.remove(session.getTransactionInternal(), pair.first(), pair.second());
      index.put(session.getTransactionInternal(), pair.first(), pair.second());
    }
    index.put(session.getTransactionInternal(), "aardvark", beta);
    index.put(session.getTransactionInternal(), "zeta", beta);
    var txRows = rangeSnapshots(index, alpha, beta, gamma);
    session.commit();
    session.begin();
    assertEquals(txRows, rangeSnapshots(index, alpha, beta, gamma));
    session.rollback();
  }

  private List<List<String>> rangeSnapshots(Index index, List<RID> alpha, RID beta,
      List<RID> gamma) {
    var snapshots = new ArrayList<List<String>>();
    for (boolean asc : List.of(true, false)) {
      for (boolean inclusive : List.of(true, false)) {
        for (boolean onlyFrom : List.of(true, false)) {
          var expected = new ArrayList<RID>(onlyFrom ? gamma : alpha);
          expected.add(beta); // TX-only zeta or aardvark.
          if (inclusive) {
            expected.add(beta); // Committed beta exactly at the bound.
          }
          try (var stream = index.streamEntriesBetween(session,
              onlyFrom ? "beta" : null, inclusive, onlyFrom ? null : "beta", inclusive, asc)) {
            var rows = stream.toList();
            assertSameRids(expected, rows.stream().map(RawPair::second));
            var keys = rows.stream().map(p -> (String) p.first()).toList();
            var ordered = keys.stream().sorted(asc ? java.util.Comparator.naturalOrder()
                : java.util.Comparator.reverseOrder()).toList();
            assertEquals("range key order", ordered, keys);
            snapshots.add(rows.stream().map(p -> p.first() + ":" + p.second()).sorted().toList());
          }
        }
      }
    }
    return snapshots;
  }

  /** Null-key lookup and requested-key streams must not duplicate a re-added committed RID. */
  @Test
  public void nullKeyReadd_matchesCommit() {
    assertSpecialKeyReadd(false);
  }

  /** Composite-key full and bounded scans must not duplicate a re-added committed RID. */
  @Test
  public void compositeKeyReadd_matchesCommit() {
    assertSpecialKeyReadd(true);
  }

  private void assertSpecialKeyReadd(boolean composite) {
    var cls = session.createClass("SpecialOverlay");
    cls.createProperty("first", PropertyType.STRING);
    cls.createProperty("second", PropertyType.STRING);
    String name = "SpecialOverlay.key";
    cls.createIndex(name, SchemaClass.INDEX_TYPE.NOTUNIQUE,
        composite ? new String[] {"first", "second"} : new String[] {"first"});
    session.begin();
    for (int i = 0; i < 2; i++) {
      var entity = session.newEntity("SpecialOverlay");
      if (composite) {
        entity.setProperty("first", "alpha");
        entity.setProperty("second", "beta");
      }
    }
    session.commit();
    Object key = composite ? new CompositeKey(List.of("alpha", "beta")) : null;
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(name);
    List<RID> expected;
    try (var stream = index.getRids(session, key)) {
      expected = stream.sorted().toList();
    }
    assertEquals(2, expected.size());
    var rid = expected.getLast();
    index.remove(session.getTransactionInternal(), key, rid);
    index.put(session.getTransactionInternal(), key, rid);
    assertSpecialKeyReadPaths(index, key, expected);
    session.commit();
    session.begin();
    assertSpecialKeyReadPaths(index, key, expected);
    session.rollback();
  }

  private void assertSpecialKeyReadPaths(Index index, Object key, List<RID> expected) {
    try (var stream = index.getRids(session, key)) {
      assertSameRids(expected, stream);
    }
    for (boolean asc : List.of(true, false)) {
      try (var stream =
          index.streamEntries(session, java.util.Collections.singletonList(key), asc)) {
        assertSameRids(expected, stream.map(RawPair::second));
      }
      if (key != null) {
        try (var stream = index.streamEntriesBetween(session, key, true, key, true, asc)) {
          assertSameRids(expected, stream.map(RawPair::second));
        }
        try (var stream = asc ? index.stream(session) : index.descStream(session)) {
          assertSameRids(expected, stream.map(RawPair::second));
        }
      }
    }
  }

  /** A whole-key barrier prevents cancellation of the earlier PUT across all TX read paths. */
  @Test
  public void wholeKeyRemovalThenPutAndRemoveHidesRidUntilRollback() {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    RID rid;
    try (var rids = index.getRids(session, "beta")) {
      rid = rids.findFirst().orElseThrow();
    }
    var tx = session.getActiveTransaction();
    index.put(tx, "beta", rid);
    index.remove(tx, "beta");
    index.put(tx, "beta", rid);
    index.remove(tx, "beta", rid);

    assertWholeKeyRemovalReads(index, List.of());
    assertEquals(List.of(), new ArrayList<>((Collection<?>) index.get(session, "beta")));
    // Storage does not support committing whole-key removals on multi-value indexes.
    session.rollback();

    session.begin();
    assertWholeKeyRemovalReads(index, List.of(rid));
    session.rollback();
  }

  private void assertWholeKeyRemovalReads(Index index, List<RID> expected) {
    try (var rids = index.getRids(session, "beta")) {
      assertEquals(expected, rids.toList());
    }
    for (var ascending : List.of(true, false)) {
      try (var entries = index.streamEntries(session, List.of("beta"), ascending)) {
        assertEquals(expected, entries.map(RawPair::second).toList());
      }
      try (var entries =
          index.streamEntriesBetween(session, "beta", true, "beta", true, ascending)) {
        assertEquals(expected, entries.map(RawPair::second).toList());
      }
    }
    try (var entries = index.stream(session)) {
      assertEquals(expected, entries.filter(entry -> "beta".equals(entry.first()))
          .map(RawPair::second).toList());
    }
    try (var entries = index.descStream(session)) {
      assertEquals(expected, entries.filter(entry -> "beta".equals(entry.first()))
          .map(RawPair::second).toList());
    }
  }

  /** A stale NOTUNIQUE engine identifier recovers only through the descriptor owner. */
  @Test
  public void getRids_staleEngineIdentifier_recoversByDescriptorOwner() {
    session.begin();
    var index = (IndexMultiValues) session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var expectedIdentifier = index.getIndexId();
    index.setHandleStateForTest(
        expectedIdentifier | 1_000_000, index.getIdentity());

    @SuppressWarnings("unchecked")
    Collection<RID> result = (Collection<RID>) index.get(session, "alpha");
    session.rollback();

    assertEquals("owner-bound recovery must preserve both committed values", 2, result.size());
    assertEquals("owner-bound recovery must install the packed identifier",
        expectedIdentifier, index.getIndexId());
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
   * Captures the RID of the first existing committed entry under the given key. The
   * cleared-TX tests below reuse this RID for their TX-side OPERATION.PUT so the value
   * points at a real, committed record — future RID-validation tightening (e.g. rejecting
   * the {@code #-1:-1} sentinel) will not silently invalidate these tests.
   */
  @SuppressWarnings("unchecked")
  private RID capturedRidForCommittedKey(String committedKey) {
    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var collection = (Collection<RID>) index.get(session, committedKey);
    session.rollback();
    assertNotNull("baseline lookup of '" + committedKey + "' must yield a collection",
        collection);
    assertTrue("baseline collection for '" + committedKey + "' must be non-empty",
        !collection.isEmpty());
    return collection.iterator().next();
  }

  /**
   * When the TX index changes have {@code cleared == true} (set by an OPERATION.CLEAR via
   * the public {@code FrontendTransaction.addIndexEntry} API), {@code IndexMultiValues
   * .streamEntriesBetween}'s {@code if (indexChanges.cleared)} branch returns ONLY the TX
   * stream — committed multi-value entries (alpha×2, beta, gamma×2) must NOT appear, and the
   * freshly-PUT TX-only key "delta" must. The PUT uses a real committed RID (captured
   * before the CLEAR) so the test survives any future RID-validation tightening that would
   * reject {@code #-1:-1}.
   */
  @Test
  public void streamEntriesBetween_clearedTxChanges_returnsOnlyTxAddedKeys() {
    var capturedRid = capturedRidForCommittedKey("alpha");

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var tx = session.getTransactionInternal();

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
   * {@code IndexMultiValues.streamEntries(keys, asc=true)} cleared-TX branch: when
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
   * {@code IndexMultiValues.streamEntriesMajor} cleared-TX branch: when
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
   * {@code IndexMultiValues.streamEntriesMinor} cleared-TX branch: when
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

  /**
   * {@code IndexMultiValues.descStream} cleared-TX branch: when
   * {@code indexChanges.cleared == true}, the descending whole-index stream must drop
   * committed entries (alpha×2, beta, gamma×2) and yield only the TX-added key.
   */
  @Test
  public void descStream_clearedTxChanges_returnsOnlyTxAddedKeys() {
    var capturedRid = capturedRidForCommittedKey("alpha");

    session.begin();
    var index = session.getSharedContext().getIndexManager().getIndex(IDX_NAME);
    var tx = session.getTransactionInternal();

    tx.addIndexEntry(index, IDX_NAME, OPERATION.CLEAR, null, null);
    tx.addIndexEntry(index, IDX_NAME, OPERATION.PUT, "delta", capturedRid);

    var keys = new ArrayList<String>();
    try (Stream<RawPair<Object, RID>> s = index.descStream(session)) {
      s.forEach(p -> keys.add((String) p.first()));
    }
    session.rollback();

    assertEquals(
        "cleared TX descStream must drop committed keys and yield only TX-added 'delta'",
        List.of("delta"), keys);
  }
}
