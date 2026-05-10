/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jetbrains.youtrackdb.internal.core.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.Test;

/**
 * Pure unit tests for the abstract base class {@link AbstractMapCache} —
 * the lifecycle plumbing shared by every {@link RecordCache} implementation
 * backed by a {@link Map}. The base class itself is exercised through a
 * minimal local concrete subclass that uses a plain {@link HashMap} as
 * backing storage; that lets us pin every method on the base
 * ({@code startup}, {@code shutdown}, {@code clear}, {@code size},
 * {@code keys}, {@code isEnabled}, {@code disable}, {@code enable}) without
 * pulling in the weak-ref machinery of {@link RecordCacheWeakRefs} or a real
 * database session.
 */
public class AbstractMapCacheTest {

  /**
   * Concrete subclass that uses an unmodified {@link HashMap} as backing
   * storage. The three abstract {@link RecordCache} methods that the base
   * class does NOT implement ({@code get}, {@code put}, {@code remove}) are
   * stubbed minimally so the whole base-class surface is reachable.
   */
  private static final class MapBackedCache
      extends AbstractMapCache<HashMap<RID, RecordAbstract>> {

    MapBackedCache() {
      super(new HashMap<>());
    }

    @Override
    public RecordAbstract get(RID rid) {
      return cache.get(rid);
    }

    @Override
    public RecordAbstract put(RecordAbstract record) {
      return cache.put(record.getIdentity(), record);
    }

    @Override
    public RecordAbstract remove(RID rid) {
      return cache.remove(rid);
    }

    @Override
    public void unloadRecords() {
      // No-op for the test stub — coverage of unloadRecords lives on
      // RecordCacheWeakRefs, where the biconsumer behaviour is the focus.
    }

    @Override
    public void unloadNotModifiedRecords() {
      // No-op for the test stub — see comment on unloadRecords.
    }

    /**
     * Exposes the protected {@code cache} field for assertions; the base class
     * type-parameterises it, so the test can read the underlying {@link Map}
     * size and content without reflection.
     */
    Map<RID, RecordAbstract> backing() {
      return cache;
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle — startup is a no-op, shutdown clears the backing map
  // ---------------------------------------------------------------------------

  @Test
  public void startupIsANoOpAndDoesNotMutateTheBackingMap() {
    var c = new MapBackedCache();
    c.backing().put(new RecordId(0, 0L), null);
    c.startup();
    assertEquals("startup() must not clear or otherwise touch the cache",
        1, c.backing().size());
  }

  @Test
  public void shutdownEmptiesTheBackingMapWithoutReplacingIt() {
    // The base class's shutdown calls cache.clear() on the same instance.
    // (RecordCacheWeakRefs overrides this to swap in a fresh map.) Pin the
    // base behavior: same identity, zero entries.
    var c = new MapBackedCache();
    var backingBefore = c.backing();
    backingBefore.put(new RecordId(0, 0L), null);
    c.shutdown();
    assertEquals("shutdown() must empty the map", 0, c.backing().size());
    // Use assertSame for identity rather than identityHashCode — collisions are
    // legal per the Object contract, so identityHashCode equality is too weak to
    // pin the "same instance" claim.
    assertSame("shutdown() must keep the same map instance (no replace)",
        backingBefore, c.backing());
  }

  @Test
  public void clearEmptiesTheBackingMapInPlace() {
    var c = new MapBackedCache();
    c.backing().put(new RecordId(0, 0L), null);
    c.backing().put(new RecordId(0, 1L), null);
    c.clear();
    assertEquals(0, c.backing().size());
  }

  // ---------------------------------------------------------------------------
  // size() and keys() — pure delegations to the backing map
  // ---------------------------------------------------------------------------

  @Test
  public void sizeReportsTheBackingMapEntryCount() {
    var c = new MapBackedCache();
    assertEquals(0, c.size());
    c.backing().put(new RecordId(0, 0L), null);
    c.backing().put(new RecordId(0, 1L), null);
    assertEquals(2, c.size());
  }

  @Test
  public void keysReturnsADefensiveCopyOfTheBackingKeySet() {
    var c = new MapBackedCache();
    var rid1 = new RecordId(0, 0L);
    var rid2 = new RecordId(0, 1L);
    c.backing().put(rid1, null);
    c.backing().put(rid2, null);

    var snapshot = c.keys();
    assertEquals("keys() snapshot reflects the backing key set",
        new HashSet<>(c.backing().keySet()), new HashSet<>(snapshot));

    // Pin defensive-copy semantics: mutating the snapshot must not touch the
    // backing map, and adding to the backing map after taking the snapshot
    // must not show up retroactively.
    snapshot.clear();
    assertEquals("keys() snapshot must be detached from the backing map",
        2, c.backing().size());

    c.backing().put(new RecordId(0, 2L), null);
    var snapshot2 = c.keys();
    assertNotSame("each keys() call returns a fresh ArrayList",
        snapshot, snapshot2);
    assertEquals(3, snapshot2.size());
  }

  // ---------------------------------------------------------------------------
  // enabled flag — default true, toggle via disable/enable, observed via isEnabled
  // ---------------------------------------------------------------------------

  @Test
  public void isEnabledDefaultsToTrueOnAFreshlyConstructedCache() {
    assertTrue(new MapBackedCache().isEnabled());
  }

  @Test
  public void disableFlipsTheFlagAndReturnsFalse() {
    var c = new MapBackedCache();
    var afterDisable = c.disable();
    assertFalse("disable() must return the new value (false)", afterDisable);
    assertFalse("isEnabled() reflects the disabled state", c.isEnabled());
  }

  @Test
  public void enableFlipsTheFlagBackAndReturnsTrue() {
    var c = new MapBackedCache();
    c.disable();
    var afterEnable = c.enable();
    assertTrue("enable() must return the new value (true)", afterEnable);
    assertTrue("isEnabled() reflects the re-enabled state", c.isEnabled());
  }

  @Test
  public void enableAndDisableAreIdempotentWhenCalledRepeatedly() {
    var c = new MapBackedCache();
    c.enable();
    c.enable();
    assertTrue(c.isEnabled());
    c.disable();
    c.disable();
    assertFalse(c.isEnabled());
  }
}
