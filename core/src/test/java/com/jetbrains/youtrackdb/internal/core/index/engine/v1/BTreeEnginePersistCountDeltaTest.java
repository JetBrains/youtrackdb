package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.Test;

/**
 * Tests for {@link BTreeIndexEngine#persistCountDelta(AtomicOperation, long, long)}.
 *
 * <p>Verifies that single-value engine forwards the full totalDelta to its
 * single tree, and multi-value engine correctly splits the delta across svTree
 * (non-null entries) and nullTree (null entries).
 */
public class BTreeEnginePersistCountDeltaTest {

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Single-value engine applies the full totalDelta to sbTree. The nullDelta
   * is ignored because null and non-null entries share the same BTree.
   */
  @Test
  public void singleValue_persistCountDelta_forwardsFullDeltaToSbTree() {
    var f = new SingleValueFixture();

    f.engine.persistCountDelta(f.op, 10, 1);

    // Full totalDelta (10) forwarded to sbTree, nullDelta (1) is ignored
    verify(f.sbTree).addToApproximateEntriesCount(f.op, 10);
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Single-value engine correctly handles negative totalDelta (removals
   * exceeding additions in a transaction).
   */
  @Test
  public void singleValue_persistCountDelta_handlesNegativeDelta() {
    var f = new SingleValueFixture();

    // nullDelta (2) should be ignored — sbTree still gets -3, not -5
    f.engine.persistCountDelta(f.op, -3, 2);

    verify(f.sbTree).addToApproximateEntriesCount(f.op, -3);
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Single-value engine skips page write when totalDelta is zero (no net
   * change in a transaction, e.g. equal additions and removals).
   */
  @Test
  public void singleValue_persistCountDelta_skipsZeroDelta() {
    var f = new SingleValueFixture();

    // totalDelta=0 → no page write; nullDelta=3 is also ignored
    f.engine.persistCountDelta(f.op, 0, 3);

    verifyNoMoreInteractions(f.sbTree);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value engine
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Multi-value engine splits delta: svTree gets (totalDelta - nullDelta) for
   * non-null entries, nullTree gets nullDelta for null entries.
   */
  @Test
  public void multiValue_persistCountDelta_splitsDeltaAcrossTrees() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 10, 3);

    // svTree: 10 - 3 = 7 non-null entries
    verify(f.svTree).addToApproximateEntriesCount(f.op, 7);
    // nullTree: 3 null entries
    verify(f.nullTree).addToApproximateEntriesCount(f.op, 3);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine handles all-null delta (totalDelta equals nullDelta):
   * svTree page write skipped (nonNullDelta=0), nullTree gets the full delta.
   */
  @Test
  public void multiValue_persistCountDelta_allNullEntries() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 5, 5);

    // nonNullDelta = 5 - 5 = 0 → svTree page write skipped
    verify(f.nullTree).addToApproximateEntriesCount(f.op, 5);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine handles no-null delta (nullDelta is 0): svTree gets
   * the full totalDelta, nullTree page write skipped.
   */
  @Test
  public void multiValue_persistCountDelta_noNullEntries() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 8, 0);

    verify(f.svTree).addToApproximateEntriesCount(f.op, 8);
    // nullDelta = 0 → nullTree page write skipped
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine handles negative deltas (removals in a transaction).
   */
  @Test
  public void multiValue_persistCountDelta_handlesNegativeDeltas() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, -4, -1);

    // svTree: -4 - (-1) = -3
    verify(f.svTree).addToApproximateEntriesCount(f.op, -3);
    // nullTree: -1
    verify(f.nullTree).addToApproximateEntriesCount(f.op, -1);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine skips page writes when both deltas are zero.
   */
  @Test
  public void multiValue_persistCountDelta_skipsZeroDeltas() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 0, 0);

    // Both nonNullDelta (0-0=0) and nullDelta (0) are zero → no page writes
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  /**
   * Multi-value engine: nullDelta exceeds totalDelta, resulting in a negative
   * non-null delta to svTree. This occurs when a transaction removes more
   * non-null entries than it adds while also adding null entries.
   */
  @Test
  public void multiValue_persistCountDelta_nullDeltaExceedsTotalDelta() {
    var f = new MultiValueFixture();

    f.engine.persistCountDelta(f.op, 2, 5);

    // svTree: 2 - 5 = -3 (non-null entries decreased)
    verify(f.svTree).addToApproximateEntriesCount(f.op, -3);
    // nullTree: 5
    verify(f.nullTree).addToApproximateEntriesCount(f.op, 5);
    verifyNoMoreInteractions(f.svTree, f.nullTree);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixtures — delegated to BTreeEngineTestFixtures
  // ═══════════════════════════════════════════════════════════════════════

  private static class SingleValueFixture
      extends BTreeEngineTestFixtures.SingleValueFixture {
  }

  private static class MultiValueFixture
      extends BTreeEngineTestFixtures.MultiValueFixture {
  }
}
