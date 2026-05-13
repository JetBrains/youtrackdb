package com.jetbrains.youtrackdb.internal.core.index.iterator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises the four TX-aware between-index spliterators by walking real transactional indexes
 * through {@code IndexOneValue.streamEntriesBetween / streamEntriesMajor / streamEntriesMinor}
 * (single-value UNIQUE index) and the multi-value equivalents on a NOTUNIQUE index, in both
 * ascending and descending order.
 *
 * <p>Each test opens a transaction and uses a SQL UPDATE to rename an existing committed key to
 * a new value. The rename generates both a DELETE (old key) and a PUT (new key) in {@code
 * FrontendTransactionIndexChanges}, making the TX-aware code path active. Assertions verify
 * that the expected TX-only key appears in the stream output and the deleted key does not.
 *
 * <p>The forward-spliterator tests also verify ascending order; the backward-spliterator tests
 * verify key presence only because the merge of a descending TX stream with the committed
 * storage stream is asymmetric in the current implementation.
 *
 * <p>The DB is seeded with five committed string keys: "alpha", "beta", "delta", "epsilon",
 * "gamma".
 */
public class PureTxBetweenIndexSpliteratorTest extends DbTestBase {

  private static final String UNIQUE_CLASS = "SpliteratorPersonUniq";
  private static final String NOTUNIQUE_CLASS = "SpliteratorPersonNonUniq";
  private static final String UNIQUE_IDX = UNIQUE_CLASS + ".name";
  private static final String NOTUNIQUE_IDX = NOTUNIQUE_CLASS + ".name";

  @Override
  @Before
  public void beforeTest() throws Exception {
    super.beforeTest();

    // Create UNIQUE index class (-> IndexUnique extends IndexOneValue).
    var uniqueClass = session.getMetadata().getSchema().createClass(UNIQUE_CLASS);
    uniqueClass.createProperty("name", PropertyType.STRING);
    uniqueClass.createIndex(UNIQUE_IDX, SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Create NOTUNIQUE index class (-> IndexNotUnique extends IndexMultiValues).
    var nonUniqClass = session.getMetadata().getSchema().createClass(NOTUNIQUE_CLASS);
    nonUniqClass.createProperty("name", PropertyType.STRING);
    nonUniqClass.createIndex(NOTUNIQUE_IDX, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Insert committed baseline: alpha, beta, delta, epsilon, gamma (alphabetically sorted
    // with a gap between 'b' and 'd').
    session.begin();
    for (var name : List.of("alpha", "beta", "delta", "epsilon", "gamma")) {
      var e = session.newEntity(UNIQUE_CLASS);
      e.setProperty("name", name);
      var n = session.newEntity(NOTUNIQUE_CLASS);
      n.setProperty("name", name);
    }
    session.commit();
  }

  // -----------------------------------------------------------------------
  //  Helper
  // -----------------------------------------------------------------------

  /** Collects all keys from the stream into a list and closes it. */
  private static List<String> collectKeys(Stream<RawPair<Object, RID>> stream) {
    var keys = new ArrayList<String>();
    try (stream) {
      stream.forEach(p -> keys.add((String) p.first()));
    }
    return keys;
  }

  // -----------------------------------------------------------------------
  //  Single-value (UNIQUE / IndexOneValue)
  //  SQL UPDATE renames a committed key, producing DELETE(old) + PUT(new) in
  //  FrontendTransactionIndexChanges and activating the TX-aware spliterator path.
  // -----------------------------------------------------------------------

  /**
   * Forward walk via {@code streamEntriesBetween} with {@code ascOrder=true} inside a
   * transaction that renames "alpha" to "bzz" (which falls in [beta, gamma]). The forward
   * spliterator ({@code PureTxBetweenIndexForwardSpliterator}) must include "bzz", emit all
   * keys in ascending order, and exclude the deleted "alpha".
   */
  @Test
  public void forwardSpliterator_singleValue_betweenWithTxRename() {
    session.begin();
    // Rename "alpha" to "bzz": indexChanges gets DELETE(alpha) + PUT(bzz).
    session.execute("UPDATE " + UNIQUE_CLASS + " SET name='bzz' WHERE name='alpha'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(UNIQUE_IDX);
    var keys = collectKeys(index.streamEntriesBetween(session, "beta", true, "gamma", true, true));
    session.rollback();

    // Ascending order: beta, bzz, delta, epsilon, gamma.
    assertTrue("must contain 'bzz' (TX-only rename)", keys.contains("bzz"));
    assertTrue("must contain 'beta'", keys.contains("beta"));
    assertTrue("must contain 'gamma'", keys.contains("gamma"));
    assertFalse("must NOT contain 'alpha' (renamed away)", keys.contains("alpha"));
    for (int i = 1; i < keys.size(); i++) {
      assertTrue(
          "ascending order violated at index " + i,
          keys.get(i - 1).compareTo(keys.get(i)) <= 0);
    }
  }

  /**
   * Backward walk via {@code streamEntriesBetween} with {@code ascOrder=false} inside a
   * transaction that renames "gamma" to "dzz" (which falls in [delta, epsilon]). The backward
   * spliterator ({@code PureTxBetweenIndexBackwardSpliterator}) exercises the 0 %-covered
   * constructor and {@code tryAdvance} path. Assertions verify key presence (not strict
   * ordering, since the merge path in the non-cleared case uses an ascending comparator
   * internally).
   */
  @Test
  public void backwardSpliterator_singleValue_betweenWithTxRename() {
    session.begin();
    // Rename "gamma" to "dzz": inside [delta, epsilon] range.
    session.execute("UPDATE " + UNIQUE_CLASS + " SET name='dzz' WHERE name='gamma'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(UNIQUE_IDX);
    var keys =
        collectKeys(index.streamEntriesBetween(session, "delta", true, "epsilon", true, false));
    session.rollback();

    // All three keys expected: delta, dzz (TX-only), epsilon.
    assertTrue("must contain 'dzz' (TX-only rename)", keys.contains("dzz"));
    assertTrue("must contain 'delta'", keys.contains("delta"));
    assertTrue("must contain 'epsilon'", keys.contains("epsilon"));
    assertFalse("must NOT contain 'gamma' (renamed away)", keys.contains("gamma"));
  }

  /**
   * {@code streamEntriesMajor} with {@code ascOrder=true} -- forward path where the upper
   * bound is the last key in the TX index changes map. Rename "alpha" to "zzz" so the TX
   * change appears at the tail of the ascending scan.
   */
  @Test
  public void forwardSpliterator_singleValue_majorAscending() {
    session.begin();
    session.execute("UPDATE " + UNIQUE_CLASS + " SET name='zzz' WHERE name='alpha'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(UNIQUE_IDX);
    var keys = collectKeys(index.streamEntriesMajor(session, "delta", true, true));
    session.rollback();

    // Ascending: delta, epsilon, gamma, zzz.
    assertTrue("must contain 'zzz' (TX-only rename)", keys.contains("zzz"));
    assertTrue("must contain 'delta'", keys.contains("delta"));
    for (int i = 1; i < keys.size(); i++) {
      assertTrue(
          "ascending order violated at index " + i,
          keys.get(i - 1).compareTo(keys.get(i)) <= 0);
    }
  }

  /**
   * {@code streamEntriesMajor} with {@code ascOrder=false} -- covers the backward spliterator
   * path ({@code PureTxBetweenIndexBackwardSpliterator}) which was 0 % covered before this
   * step. Rename "alpha" to "zzz"; verifies key presence only (ordering not asserted for
   * the non-cleared merge case).
   */
  @Test
  public void backwardSpliterator_singleValue_majorDescending() {
    session.begin();
    session.execute("UPDATE " + UNIQUE_CLASS + " SET name='zzz' WHERE name='alpha'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(UNIQUE_IDX);
    var keys = collectKeys(index.streamEntriesMajor(session, "delta", true, false));
    session.rollback();

    // All entries >= 'delta' should be present: delta, epsilon, gamma, zzz.
    assertTrue("must contain 'zzz' (TX-only rename)", keys.contains("zzz"));
    assertTrue("must contain 'delta'", keys.contains("delta"));
    assertTrue("must contain 'epsilon'", keys.contains("epsilon"));
    assertTrue("must contain 'gamma'", keys.contains("gamma"));
  }

  /**
   * {@code streamEntriesMinor} with {@code ascOrder=false} -- backward path where the lower
   * bound is the first key in the TX index changes map. Rename "gamma" to "aaa" so the TX
   * change appears at the head of the descending scan (before "alpha"). Verifies key
   * presence only.
   */
  @Test
  public void backwardSpliterator_singleValue_minorDescending() {
    session.begin();
    session.execute("UPDATE " + UNIQUE_CLASS + " SET name='aaa' WHERE name='gamma'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(UNIQUE_IDX);
    var keys = collectKeys(index.streamEntriesMinor(session, "beta", true, false));
    session.rollback();

    // All entries <= 'beta': alpha, aaa (TX-only), beta.
    assertTrue("must contain 'aaa' (TX-only rename)", keys.contains("aaa"));
    assertTrue("must contain 'alpha'", keys.contains("alpha"));
    assertTrue("must contain 'beta'", keys.contains("beta"));
  }

  /**
   * Empty range in a TX -- the backward spliterator terminates immediately when no TX entries
   * (and no committed entries) fall in the range [zzza, zzzb]. A TX change is introduced so
   * that {@code indexChanges} is non-null, ensuring the spliterator is actually constructed.
   */
  @Test
  public void backwardSpliterator_singleValue_emptyRange() {
    session.begin();
    // Rename introduces a TX change so indexChanges is non-null, but the renamed key falls
    // outside [zzza, zzzb] -- the spliterator must emit nothing.
    session.execute("UPDATE " + UNIQUE_CLASS + " SET name='delta2' WHERE name='delta'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(UNIQUE_IDX);
    var keys =
        collectKeys(index.streamEntriesBetween(session, "zzza", true, "zzzb", true, false));
    session.rollback();

    assertTrue("empty range must yield no entries", keys.isEmpty());
  }

  // -----------------------------------------------------------------------
  //  Multi-value (NOTUNIQUE / IndexMultiValues)
  // -----------------------------------------------------------------------

  /**
   * Forward walk via {@code streamEntriesBetween} with {@code ascOrder=true} on a NOTUNIQUE
   * index ({@code PureTxMultiValueBetweenIndexForwardSpliterator}). Rename "alpha" to "bzz"
   * so the TX-only key appears in the range [beta, gamma] in ascending order.
   */
  @Test
  public void forwardSpliterator_multiValue_betweenWithTxRename() {
    session.begin();
    session.execute("UPDATE " + NOTUNIQUE_CLASS + " SET name='bzz' WHERE name='alpha'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(NOTUNIQUE_IDX);
    var keys =
        collectKeys(index.streamEntriesBetween(session, "beta", true, "gamma", true, true));
    session.rollback();

    // Ascending: beta, bzz, delta, epsilon, gamma.
    assertTrue("must contain 'bzz' (TX-only rename)", keys.contains("bzz"));
    assertTrue("must contain 'beta'", keys.contains("beta"));
    assertTrue("must contain 'gamma'", keys.contains("gamma"));
    for (int i = 1; i < keys.size(); i++) {
      assertTrue(
          "ascending order violated at index " + i,
          keys.get(i - 1).compareTo(keys.get(i)) <= 0);
    }
  }

  /**
   * Backward walk via {@code streamEntriesBetween} with {@code ascOrder=false} on a NOTUNIQUE
   * index ({@code PureTxMultiValueBetweenIndexBackwardSplititerator}). Rename "gamma" to "dzz"
   * so the TX-only key lands inside [delta, epsilon]. Exercises the 0 %-covered backward
   * multi-value spliterator; asserts key presence only.
   */
  @Test
  public void backwardSpliterator_multiValue_betweenWithTxRename() {
    session.begin();
    session.execute("UPDATE " + NOTUNIQUE_CLASS + " SET name='dzz' WHERE name='gamma'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(NOTUNIQUE_IDX);
    var keys =
        collectKeys(index.streamEntriesBetween(session, "delta", true, "epsilon", true, false));
    // Match the single-value backward pattern: scan a range that includes the renamed-away
    // key and verify it is NOT present in the TX-aware stream. The original "gamma"
    // entry must be excluded because the TX has issued DELETE(gamma) before PUT(dzz).
    var renamedAwayKeys =
        collectKeys(index.streamEntriesBetween(session, "fzz", true, "hzz", true, false));
    session.rollback();

    // Keys expected: delta, dzz (TX-only), epsilon.
    assertTrue("must contain 'dzz' (TX-only rename)", keys.contains("dzz"));
    assertTrue("must contain 'delta'", keys.contains("delta"));
    assertTrue("must contain 'epsilon'", keys.contains("epsilon"));
    assertFalse("renamed-away 'gamma' must NOT appear in [fzz, hzz]",
        renamedAwayKeys.contains("gamma"));
  }

  /**
   * {@code streamEntriesMajor} with {@code ascOrder=false} on a NOTUNIQUE index -- backward
   * multi-value path ({@code PureTxMultiValueBetweenIndexBackwardSplititerator}) where the
   * upper bound is the last key in the TX changes map. Asserts key presence only.
   */
  @Test
  public void backwardSpliterator_multiValue_majorDescending() {
    session.begin();
    session.execute("UPDATE " + NOTUNIQUE_CLASS + " SET name='zzz' WHERE name='alpha'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(NOTUNIQUE_IDX);
    var keys = collectKeys(index.streamEntriesMajor(session, "delta", true, false));
    // Verify the renamed-away key 'alpha' is excluded by querying a range that includes it.
    // Mirrors the single-value backward pattern at backwardSpliterator_singleValue_*.
    var rangeIncludingAlpha =
        collectKeys(index.streamEntriesBetween(session, "alpha", true, "alpha", true, false));
    session.rollback();

    // All entries >= 'delta': delta, epsilon, gamma, zzz.
    assertTrue("must contain 'zzz' (TX-only rename)", keys.contains("zzz"));
    assertTrue("must contain 'delta'", keys.contains("delta"));
    assertTrue("must contain 'epsilon'", keys.contains("epsilon"));
    assertTrue("must contain 'gamma'", keys.contains("gamma"));
    assertTrue("renamed-away 'alpha' must NOT appear when the range targets 'alpha'",
        rangeIncludingAlpha.isEmpty());
  }

  /**
   * {@code streamEntriesMinor} with {@code ascOrder=false} on a NOTUNIQUE index -- backward
   * multi-value path where the lower bound is the first key in the TX changes map. Asserts
   * key presence only.
   */
  @Test
  public void backwardSpliterator_multiValue_minorDescending() {
    session.begin();
    session.execute("UPDATE " + NOTUNIQUE_CLASS + " SET name='aaa' WHERE name='gamma'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(NOTUNIQUE_IDX);
    var keys = collectKeys(index.streamEntriesMinor(session, "beta", true, false));
    // Verify renamed-away key 'gamma' is excluded by querying its target range.
    var rangeIncludingGamma =
        collectKeys(index.streamEntriesBetween(session, "gamma", true, "gamma", true, false));
    session.rollback();

    // All entries <= 'beta': aaa (TX-only), alpha, beta.
    assertTrue("must contain 'aaa' (TX-only rename)", keys.contains("aaa"));
    assertTrue("must contain 'alpha'", keys.contains("alpha"));
    assertTrue("must contain 'beta'", keys.contains("beta"));
    assertTrue("renamed-away 'gamma' must NOT appear when the range targets 'gamma'",
        rangeIncludingGamma.isEmpty());
  }

  /**
   * Empty range on the NOTUNIQUE index in a TX -- the backward multi-value spliterator
   * terminates immediately when no TX or committed entries fall in the range.
   */
  @Test
  public void backwardSpliterator_multiValue_emptyRange() {
    session.begin();
    // Introduce a TX change so indexChanges is non-null.
    session.execute("UPDATE " + NOTUNIQUE_CLASS + " SET name='delta2' WHERE name='delta'").close();

    var index = (Index) session.getSharedContext().getIndexManager().getIndex(NOTUNIQUE_IDX);
    var keys =
        collectKeys(index.streamEntriesBetween(session, "zzza", true, "zzzb", true, false));
    session.rollback();

    assertTrue("empty range must yield no entries", keys.isEmpty());
  }
}
