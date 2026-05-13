package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree;

import java.util.AbstractMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link TreeInternal.AccumulativeListener} — the in-memory collector
 * used during range scans over B-tree indexes. Verifies that the listener
 * accumulates entries up to the configured limit, returns {@code false} when
 * the limit is reached (signalling the scan to stop), and exposes the collected
 * results correctly.
 */
public class AccumulativeListenerTest {

  /**
   * A freshly created listener with limit 3 starts empty and returns an empty result list.
   */
  @Test
  public void newListenerResultIsEmpty() {
    var listener = new TreeInternal.AccumulativeListener<String, Integer>(3);
    Assert.assertTrue(listener.getResult().isEmpty());
  }

  /**
   * Adding entries below the limit returns {@code true}, indicating the scan
   * should continue.
   */
  @Test
  public void addResultReturnsTrueWhileBelowLimit() {
    var listener = new TreeInternal.AccumulativeListener<String, Integer>(3);

    boolean continueFirst = listener.addResult(entry("a", 1));
    boolean continueSecond = listener.addResult(entry("b", 2));

    Assert.assertTrue("first entry: scan should continue", continueFirst);
    Assert.assertTrue("second entry: scan should continue", continueSecond);
    Assert.assertEquals(2, listener.getResult().size());
  }

  /**
   * Adding exactly limit entries: the (limit)th call returns {@code false},
   * telling the B-tree scanner that no more results are needed.
   */
  @Test
  public void addResultReturnsFalseWhenLimitReached() {
    var listener = new TreeInternal.AccumulativeListener<String, Integer>(2);

    boolean afterFirst = listener.addResult(entry("x", 10));
    boolean afterSecond = listener.addResult(entry("y", 20));

    Assert.assertTrue("first entry: scan should continue", afterFirst);
    Assert.assertFalse("limit reached: scan should stop", afterSecond);
    Assert.assertEquals(2, listener.getResult().size());
  }

  /**
   * The result list contains entries in insertion order and preserves key-value
   * pairs exactly as passed to {@code addResult}.
   */
  @Test
  public void resultPreservesInsertionOrderAndValues() {
    var listener = new TreeInternal.AccumulativeListener<String, Integer>(5);
    listener.addResult(entry("first", 1));
    listener.addResult(entry("second", 2));
    listener.addResult(entry("third", 3));

    var result = listener.getResult();
    Assert.assertEquals(3, result.size());
    Assert.assertEquals("first", result.get(0).getKey());
    Assert.assertEquals(Integer.valueOf(1), result.get(0).getValue());
    Assert.assertEquals("second", result.get(1).getKey());
    Assert.assertEquals(Integer.valueOf(2), result.get(1).getValue());
    Assert.assertEquals("third", result.get(2).getKey());
    Assert.assertEquals(Integer.valueOf(3), result.get(2).getValue());
  }

  /**
   * A listener with limit 0 returns {@code false} immediately on the first entry,
   * since even before adding the entry the limit ({@code 0}) is already reached.
   * The entry is still accumulated because {@code addResult} adds before checking.
   */
  @Test
  public void zeroLimitReturnsFalseOnFirstAdd() {
    var listener = new TreeInternal.AccumulativeListener<String, Integer>(0);
    // limit=0 means limit > entries.size() is 0 > 1 = false immediately
    boolean continueAfter = listener.addResult(entry("z", 99));
    Assert.assertFalse("limit=0: should stop immediately", continueAfter);
  }

  // ---

  private static <K, V> Map.Entry<K, V> entry(K key, V value) {
    return new AbstractMap.SimpleImmutableEntry<>(key, value);
  }
}
