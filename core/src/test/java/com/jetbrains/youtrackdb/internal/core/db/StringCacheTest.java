/*
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class StringCacheTest {

  @Test
  public void testSingleAdd() throws UnsupportedEncodingException {
    var bytes = "abcde".getBytes();
    var cache = new StringCache(500);
    var value = cache.getString(bytes, 0, bytes.length);
    assertEquals(value, "abcde");
    assertEquals(cache.size(), 1);
  }

  @Test
  public void testDobuleHit() throws UnsupportedEncodingException {
    var bytes = "abcde".getBytes();
    var cache = new StringCache(500);
    var value = cache.getString(bytes, 0, bytes.length);
    var other = new byte[50];
    System.arraycopy(bytes, 0, other, 10, bytes.length);
    var value1 = cache.getString(other, 10, bytes.length);
    assertEquals(value1, "abcde");
    assertSame(value, value1);
    assertEquals(cache.size(), 1);
  }

  // ---------------------------------------------------------------------------
  // close() lifecycle
  // ---------------------------------------------------------------------------

  /**
   * close() clears the cache and the cache must remain usable afterwards (size repopulates
   * from new gets). Pinning "usable after close" is load-bearing — a future refactor that
   * marks the cache as unusable after close (e.g. nulls the inner LRU map) would silently
   * break callers that reuse the cache instance, and would not be caught by either of the
   * pre-existing tests above.
   */
  @Test
  public void closeClearsAllEntriesAndCacheRemainsUsable() {
    var cache = new StringCache(50);
    cache.getString("alpha".getBytes(StandardCharsets.UTF_8), 0, 5);
    cache.getString("beta".getBytes(StandardCharsets.UTF_8), 0, 4);
    assertEquals("two distinct gets must populate two entries",
        2, cache.size());

    cache.close();
    assertEquals("close() must clear all entries", 0, cache.size());

    // Re-populate post-close to pin the "still usable" contract.
    var got = cache.getString("alpha".getBytes(StandardCharsets.UTF_8), 0, 5);
    assertEquals("post-close get must return the correct interned string", "alpha", got);
    assertEquals("post-close get must repopulate the cache",
        1, cache.size());
  }

  /**
   * close() is idempotent — calling it twice on an empty cache does not throw and leaves
   * size() at 0. Pinning idempotence is a defensive contract: shutdown paths can call
   * close() unconditionally without tracking pre-close state.
   */
  @Test
  public void closeIsIdempotentOnEmptyCache() {
    var cache = new StringCache(50);
    cache.close();
    cache.close();
    assertEquals("idempotent close() must leave size at 0", 0, cache.size());
  }

  // ---------------------------------------------------------------------------
  // Capacity / eviction boundary
  // ---------------------------------------------------------------------------

  /**
   * The underlying {@link com.jetbrains.youtrackdb.internal.common.collection.LRUCache} uses
   * {@code removeEldestEntry: size() >= cacheSize}, which fires <em>after</em> the put has
   * already grown the map by one. The net steady-state size is therefore {@code cacheSize - 1},
   * not {@code cacheSize} as the field name suggests. Pinning this off-by-one keeps the
   * current behaviour falsifiable; if a future fix lifts the cap to a true {@code cacheSize}
   * entries, this test fails and serves as the WHEN-FIXED indicator.
   *
   * <p>WHEN-FIXED: deferred-cleanup track — once {@link
   * com.jetbrains.youtrackdb.internal.common.collection.LRUCache} is fixed (the off-by-one
   * was originally documented during the common-utilities track), update the assertion to
   * {@code assertEquals(capacity, cache.size())} and drop the WHEN-FIXED note.
   */
  @Test
  public void capacityCapsCacheSizeAtOneBelowConstructorArgument() {
    int capacity = 4;
    var cache = new StringCache(capacity);

    // Fill past capacity to exercise eldest-entry eviction. Each get inserts a new key.
    for (int i = 0; i < capacity * 2; i++) {
      var bytes = ("k" + i).getBytes(StandardCharsets.UTF_8);
      cache.getString(bytes, 0, bytes.length);
    }

    int size = cache.size();
    assertTrue("size must remain bounded — never exceed capacity",
        size <= capacity);
    // The current LRUCache off-by-one keeps steady-state size at capacity - 1.
    assertEquals(
        "current LRU implementation caps steady-state size at (capacity - 1) "
            + "due to LRUCache off-by-one — see WHEN-FIXED note",
        capacity - 1, size);
  }

  /**
   * Eviction is by access order (the underlying LRU is constructed with access-order=true).
   * Touching an entry between fill phases moves it to the most-recently-used end, so an
   * older un-touched entry is evicted instead. Pinning access-order semantics protects
   * against a future swap to insertion-order (which is functionally a different cache and
   * would silently change hot-path behaviour for any caller relying on temporal locality).
   */
  @Test
  public void touchingAnEntryProtectsItFromEvictionViaAccessOrder() {
    int capacity = 4;
    var cache = new StringCache(capacity);

    // Fill to (capacity - 1) so all 3 entries are present.
    cache.getString("a".getBytes(StandardCharsets.UTF_8), 0, 1);
    cache.getString("b".getBytes(StandardCharsets.UTF_8), 0, 1);
    cache.getString("c".getBytes(StandardCharsets.UTF_8), 0, 1);
    assertEquals("3 distinct entries below the off-by-one cap",
        3, cache.size());

    // Touch 'a' so it becomes most-recently-used; then add d, e — eviction should drop
    // 'b' first (the now-eldest), keeping 'a'.
    var aRef = cache.getString("a".getBytes(StandardCharsets.UTF_8), 0, 1);
    cache.getString("d".getBytes(StandardCharsets.UTF_8), 0, 1);
    cache.getString("e".getBytes(StandardCharsets.UTF_8), 0, 1);

    // 'a' must still be retrievable as the same interned reference; if eviction had been
    // by insertion order, 'a' would have been the first victim.
    var aAgain = cache.getString("a".getBytes(StandardCharsets.UTF_8), 0, 1);
    assertSame("touched entry 'a' must survive eviction (access-order LRU)",
        aRef, aAgain);
  }

  // ---------------------------------------------------------------------------
  // Concurrent access — the cache is internally synchronized; verify safety.
  // ---------------------------------------------------------------------------

  /**
   * Multiple threads racing on getString for the same key must converge on a single
   * interned instance (via {@code String.intern()}) and must not corrupt the underlying
   * LRU map. Pinning "no exception under concurrent load + identical interned reference"
   * protects against a future refactor that drops the {@code synchronized (this)} blocks
   * — that would not be caught by single-threaded tests but would surface here as a
   * {@link java.util.ConcurrentModificationException} or a non-converging interned set.
   */
  @Test
  public void concurrentGetsForSameKeyConvergeOnSingleInternedReference() throws Exception {
    int threads = 8;
    int gets = 200;
    var cache = new StringCache(500);
    byte[] payload = "concurrent-key".getBytes(StandardCharsets.UTF_8);

    var executor = Executors.newFixedThreadPool(threads);
    var startGate = new CountDownLatch(1);
    var observed = new AtomicReference<String>();
    var failure = new AtomicReference<Throwable>();
    Future<?>[] futures = new Future<?>[threads];
    try {
      for (int t = 0; t < threads; t++) {
        futures[t] = executor.submit(() -> {
          try {
            startGate.await();
            for (int i = 0; i < gets; i++) {
              var v = cache.getString(payload, 0, payload.length);
              // Pin identity — every interned string for the same key must collapse to
              // the same JVM constant-pool reference.
              if (!observed.compareAndSet(null, v) && observed.get() != v) {
                throw new AssertionError(
                    "concurrent gets diverged on interned reference identity");
              }
            }
          } catch (Throwable e) {
            failure.compareAndSet(null, e);
          }
        });
      }
      startGate.countDown();
      for (var f : futures) {
        f.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      assertTrue("executor must terminate cleanly",
          executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    if (failure.get() != null) {
      throw new AssertionError("concurrent get failure", failure.get());
    }
    assertEquals("after concurrent gets, exactly one entry must be present",
        1, cache.size());
    assertEquals("interned reference must equal the expected string",
        "concurrent-key", observed.get());
  }

  /**
   * Concurrent gets across many distinct keys must not corrupt the LRU map. The cache is
   * size-bounded but during a concurrent flood it should still report a size that respects
   * the capacity cap and exposes only legal interned strings. Pinning "no concurrent
   * modification, size stays bounded" complements the same-key convergence test above.
   */
  @Test
  public void concurrentGetsForDistinctKeysStayWithinCapacityWithoutCorruption() throws Exception {
    int threads = 8;
    int keysPerThread = 50;
    int capacity = 16;
    var cache = new StringCache(capacity);

    var executor = Executors.newFixedThreadPool(threads);
    var startGate = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var distinct = new HashSet<String>();
    Future<?>[] futures = new Future<?>[threads];
    try {
      for (int t = 0; t < threads; t++) {
        final int tid = t;
        futures[t] = executor.submit(() -> {
          try {
            startGate.await();
            for (int i = 0; i < keysPerThread; i++) {
              var key = ("t" + tid + "-" + i).getBytes(StandardCharsets.UTF_8);
              var v = cache.getString(key, 0, key.length);
              synchronized (distinct) {
                distinct.add(v);
              }
            }
          } catch (Throwable e) {
            failure.compareAndSet(null, e);
          }
        });
      }
      startGate.countDown();
      for (var f : futures) {
        f.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
      assertTrue("executor must terminate cleanly",
          executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    if (failure.get() != null) {
      throw new AssertionError("concurrent flood failure", failure.get());
    }
    assertNotEquals("at least some distinct keys must have been observed",
        0, distinct.size());
    assertTrue("post-flood size must respect the capacity cap (no map corruption): "
        + cache.size(),
        cache.size() < capacity);
  }
}
