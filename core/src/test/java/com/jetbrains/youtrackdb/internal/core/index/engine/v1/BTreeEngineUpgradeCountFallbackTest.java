package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import org.junit.Test;

/**
 * Tests for the upgrade fallback in {@code load()}: when the persisted
 * APPROXIMATE_ENTRIES_COUNT is 0 (field absent in prior format), the engine
 * falls back to TREE_SIZE so the optimizer does not see an empty index.
 */
public class BTreeEngineUpgradeCountFallbackTest {

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value engine
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * When persisted approximate count is 0 but TREE_SIZE is positive
   * (upgrade from prior format), load() should use TREE_SIZE as fallback.
   */
  @Test
  public void singleValue_load_fallsBackToTreeSizeWhenApproxCountIsZero() {
    var f = new SingleValueFixture();
    when(f.sbTree.getApproximateEntriesCount(f.op)).thenReturn(0L);
    when(f.sbTree.size(f.op)).thenReturn(42L);

    f.engine.load(createIndexEngineData("test-sv", 1), f.op);

    assertEquals("Should fall back to TREE_SIZE", 42L,
        f.engine.size(null, null, f.op));
  }

  /**
   * When persisted approximate count is already positive (normal path),
   * load() should use it directly without consulting TREE_SIZE.
   */
  @Test
  public void singleValue_load_usesPersistedCountWhenNonZero() {
    var f = new SingleValueFixture();
    when(f.sbTree.getApproximateEntriesCount(f.op)).thenReturn(100L);

    f.engine.load(createIndexEngineData("test-sv", 1), f.op);

    assertEquals("Should use persisted approximate count", 100L,
        f.engine.size(null, null, f.op));
  }

  /**
   * When both approximate count and TREE_SIZE are 0 (genuinely empty index),
   * load() should leave the count at 0.
   */
  @Test
  public void singleValue_load_remainsZeroWhenTreeIsEmpty() {
    var f = new SingleValueFixture();
    when(f.sbTree.getApproximateEntriesCount(f.op)).thenReturn(0L);
    when(f.sbTree.size(f.op)).thenReturn(0L);

    f.engine.load(createIndexEngineData("test-sv", 1), f.op);

    assertEquals("Genuinely empty index stays at 0", 0L,
        f.engine.size(null, null, f.op));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value engine
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * When both trees have persisted approximate count of 0 but positive
   * TREE_SIZE (upgrade from prior format), load() should fall back to
   * TREE_SIZE for both svTree and nullTree.
   */
  @Test
  public void multiValue_load_fallsBackToTreeSizeForBothTrees() {
    var f = new MultiValueFixture();
    when(f.svTree.getApproximateEntriesCount(f.op)).thenReturn(0L);
    when(f.svTree.size(f.op)).thenReturn(30L);
    when(f.nullTree.getApproximateEntriesCount(f.op)).thenReturn(0L);
    when(f.nullTree.size(f.op)).thenReturn(12L);

    f.engine.load(createIndexEngineData("test-mv", 1), f.op);

    // Total = svTree(30) + nullTree(12) = 42
    assertEquals("Total should be sum of both TREE_SIZEs", 42L,
        f.engine.size(null, null, f.op));
  }

  /**
   * When only svTree has zero approximate count (upgrade), nullTree has a
   * persisted count. Fallback applies only to svTree.
   */
  @Test
  public void multiValue_load_fallsBackOnlyForTreeWithZeroApproxCount() {
    var f = new MultiValueFixture();
    when(f.svTree.getApproximateEntriesCount(f.op)).thenReturn(0L);
    when(f.svTree.size(f.op)).thenReturn(50L);
    when(f.nullTree.getApproximateEntriesCount(f.op)).thenReturn(5L);

    f.engine.load(createIndexEngineData("test-mv", 1), f.op);

    // Total = svTree fallback(50) + nullTree persisted(5) = 55
    assertEquals("Mixed fallback: svTree from TREE_SIZE, nullTree persisted",
        55L, f.engine.size(null, null, f.op));
  }

  /**
   * When both trees have positive persisted approximate counts (normal path),
   * TREE_SIZE is not consulted.
   */
  @Test
  public void multiValue_load_usesPersistedCountsWhenNonZero() {
    var f = new MultiValueFixture();
    when(f.svTree.getApproximateEntriesCount(f.op)).thenReturn(80L);
    when(f.nullTree.getApproximateEntriesCount(f.op)).thenReturn(20L);

    f.engine.load(createIndexEngineData("test-mv", 1), f.op);

    assertEquals("Should use persisted approximate counts", 100L,
        f.engine.size(null, null, f.op));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Helpers and fixtures
  // ═══════════════════════════════════════════════════════════════════════

  private static IndexEngineData createIndexEngineData(String name, int keySize) {
    return new IndexEngineData(
        0, name, "CELL_BTREE", "UNIQUE", true, 4, 1, false,
        (byte) 0, (byte) 0, false,
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER},
        true, keySize, null, null, null);
  }

  private static class SingleValueFixture
      extends BTreeEngineTestFixtures.SingleValueFixture {
  }

  private static class MultiValueFixture
      extends BTreeEngineTestFixtures.MultiValueFixture {
  }
}
