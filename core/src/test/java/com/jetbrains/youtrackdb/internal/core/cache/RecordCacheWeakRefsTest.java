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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Test;

/**
 * DB-backed unit tests for {@link RecordCacheWeakRefs}. The class delegates most
 * of its life-cycle plumbing to {@link AbstractMapCache} (covered separately by
 * {@code AbstractMapCacheTest}); these tests focus on the per-method semantics
 * the subclass adds: enabled-flag short-circuiting on {@code get}/{@code put}/
 * {@code remove}, the two {@code unloadRecords} biconsumer paths (force vs.
 * not-modified), and the {@code clear} override that <em>replaces</em> the
 * underlying {@link WeakValueHashMap} with a fresh instance (in contrast to
 * {@link AbstractMapCache#clear} which empties in place).
 *
 * <p>A {@link DbTestBase} session is required because every value stored in the
 * cache is a {@link com.jetbrains.youtrackdb.internal.core.record.RecordAbstract}
 * — concretely an {@link EntityImpl} — and the constructor and dirty-counter
 * machinery require a live session. Each test creates a transient entity inside
 * {@code session.executeInTx}, takes the cache, and operates on it; no entity
 * is committed to the database, so the test isolation is per-method without
 * any explicit teardown work.
 */
public class RecordCacheWeakRefsTest extends DbTestBase {

  @After
  public void rollbackIfLeftOpen() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
  }

  /**
   * Builds a fresh transient {@link EntityImpl} bound to the active session,
   * inside the caller's existing transaction. Returned entity carries a
   * persistent identity (assigned at the surrounding tx commit if the caller
   * commits; for these tests the cache only ever sees the in-tx identity).
   */
  private EntityImpl newEntityInTx() {
    return (EntityImpl) session.newEntity();
  }

  // ---------------------------------------------------------------------------
  // get/put/remove — happy path against an in-memory weak map
  // ---------------------------------------------------------------------------

  @Test
  public void putThenGetReturnsTheRecordWithTheMatchingIdentity() {
    var cache = new RecordCacheWeakRefs();
    session.executeInTx(tx -> {
      var entity = newEntityInTx();
      assertNull("first put returns the prior mapping (null on miss)", cache.put(entity));
      assertSame("get(rid) returns the cached record", entity, cache.get(entity.getIdentity()));
    });
  }

  @Test
  public void putReplacesPriorMappingAndReturnsTheReplacedRecord() {
    // RecordCacheWeakRefs#put uses the wrapped record's identity as the map key;
    // putting the same RID twice should return the previously-stored record.
    // We can't rely on EntityImpl identity being stable across two
    // session.newEntity() calls, so this test inserts the same entity twice and
    // asserts that the second put returns the first reference.
    var cache = new RecordCacheWeakRefs();
    session.executeInTx(tx -> {
      var entity = newEntityInTx();
      cache.put(entity);
      var prior = cache.put(entity);
      assertSame("re-putting the same record returns the existing mapping",
          entity, prior);
    });
  }

  @Test
  public void removeReturnsTheCachedRecordAndDropsTheMapping() {
    var cache = new RecordCacheWeakRefs();
    session.executeInTx(tx -> {
      var entity = newEntityInTx();
      cache.put(entity);
      assertSame(entity, cache.remove(entity.getIdentity()));
      assertNull("after remove, get must return null", cache.get(entity.getIdentity()));
    });
  }

  @Test
  public void removeReturnsNullForRidNotPreviouslyPut() {
    var cache = new RecordCacheWeakRefs();
    assertNull("remove of an unmapped rid returns null",
        cache.remove(new RecordId(0, 0L)));
  }

  // ---------------------------------------------------------------------------
  // Disabled cache — get/put/remove all return null without touching backing map
  // ---------------------------------------------------------------------------

  @Test
  public void getReturnsNullWhenCacheIsDisabledEvenWithExistingMapping() {
    var cache = new RecordCacheWeakRefs();
    session.executeInTx(tx -> {
      var entity = newEntityInTx();
      cache.put(entity);
      cache.disable();
      assertNull("get must short-circuit when cache is disabled",
          cache.get(entity.getIdentity()));
    });
  }

  @Test
  public void putReturnsNullWhenCacheIsDisabledAndDoesNotMutateBackingMap() throws Exception {
    var cache = new RecordCacheWeakRefs();
    cache.disable();
    session.executeInTx(tx -> {
      var entity = newEntityInTx();
      assertNull("put must short-circuit when cache is disabled", cache.put(entity));
    });
    assertEquals("backing map remains empty when puts short-circuit",
        0, backingMapSize(cache));
  }

  @Test
  public void removeReturnsNullWhenCacheIsDisabled() {
    var cache = new RecordCacheWeakRefs();
    cache.disable();
    assertNull("remove must short-circuit when cache is disabled",
        cache.remove(new RecordId(0, 0L)));
  }

  @Test
  public void enableAfterDisableRestoresGetPutRemove() {
    var cache = new RecordCacheWeakRefs();
    cache.disable();
    cache.enable();
    session.executeInTx(tx -> {
      var entity = newEntityInTx();
      cache.put(entity);
      assertSame("after re-enable, get sees the mapping",
          entity, cache.get(entity.getIdentity()));
    });
  }

  // ---------------------------------------------------------------------------
  // unloadRecords / unloadNotModifiedRecords — pin biconsumer behaviour
  // ---------------------------------------------------------------------------

  @Test
  public void unloadNotModifiedRecordsRunsTheNotDirtyBiConsumerOverEveryEntry()
      throws Exception {
    // Production behaviour (RecordCacheWeakRefs#UNLOAD_NOT_MODIFIED_RECORDS_CONSUMER):
    //   if (!record.isDirty()) record.unload();
    // → dirty records are skipped, clean records transition to STATUS.NOT_LOADED.
    //
    // We pin the per-entry effect (not just the map size): one dirty entity, one clean
    // entity, in the same cache. After the call:
    //   - the dirty entity must remain LOADED (skipped by the consumer);
    //   - the clean entity must be unloaded (consumer's record.unload() ran).
    //
    // RecordAbstract starts every newly-constructed record with dirty=1, and the dirty
    // flag survives commit, so we control the dirty/clean split outside the tx via the
    // unguarded RecordAbstract#unsetDirty() helper. unloadNotModifiedRecords() runs
    // outside the tx so the consumer's record.unload() does not interfere with active
    // tx record-tracking.
    var cache = new RecordCacheWeakRefs();
    var entities = session.computeInTx(tx -> {
      var first = newEntityInTx();
      var second = newEntityInTx();
      return new EntityImpl[] {first, second};
    });
    var dirtyEntity = entities[0];
    var cleanEntity = entities[1];
    // Force one entity into the dirty state by setting the public {@code dirty} field
    // directly, avoiding the session-bound checkForBinding() path on setDirty(long).
    // After commit, entities are LOADED but no longer bound to the active session, so
    // the protected accessors throw — but the field itself is publicly visible.
    dirtyEntity.dirty = 1L;
    // Also force the LOADED status — checkForBinding() (called inside isUnloaded()'s
    // documented contract via record.unload()) treats NOT_LOADED + valid RID as an
    // unbound-session error; we sidestep that by explicitly setting LOADED here.
    forceStatusLoaded(dirtyEntity);
    forceStatusLoaded(cleanEntity);

    cache.put(dirtyEntity);
    cache.put(cleanEntity);

    // Preconditions: the dirty/clean split is what the consumer's branches predicate on.
    assertTrue("precondition: dirtyEntity must be dirty before the call",
        dirtyEntity.isDirty());
    assertFalse("precondition: cleanEntity must not be dirty before the call",
        cleanEntity.isDirty());
    assertFalse("precondition: dirtyEntity must be loaded before the call",
        dirtyEntity.isUnloaded());
    assertFalse("precondition: cleanEntity must be loaded before the call",
        cleanEntity.isUnloaded());

    cache.unloadNotModifiedRecords();

    // Per-entry effect: clean entity unloaded; dirty entity still loaded (skipped).
    // A regression that no-ops the per-entry effect (e.g., consumer becomes
    // (rid, record) -> {}) leaves cleanEntity.isUnloaded()==false and fails this test.
    assertFalse("dirty entity must NOT be unloaded — consumer must skip it",
        dirtyEntity.isUnloaded());
    assertTrue("clean entity must be unloaded — consumer's unload() must have run",
        cleanEntity.isUnloaded());
    // The forEach iteration does not remove map entries: both put entries remain.
    // A regression where unloadNotModifiedRecords started removing entries fails here.
    assertEquals("backing map keeps both entries through unloadNotModifiedRecords",
        2, backingMapSize(cache));
  }

  @Test
  public void unloadRecordsForcesUnsetDirtyAndUnloadOnEveryEntry() throws Exception {
    // Production behaviour (RecordCacheWeakRefs#UNLOAD_RECORDS_CONSUMER):
    //   record.unsetDirty(); record.unload();
    // → unconditional per-entry effect: every record transitions to NOT_LOADED and
    // its dirty counter is cleared. We pin BOTH steps using two entities, one of which
    // starts dirty (so we can verify unsetDirty fired and unload happened).
    //
    // Note: RecordAbstract#unload() itself calls unsetDirty(). The test still pins the
    // per-entry effect because a regression that no-ops the consumer (e.g., becomes
    // (rid, record) -> {}) leaves dirtyEntity.isDirty()==true AND isUnloaded()==false.
    var cache = new RecordCacheWeakRefs();
    var entities = session.computeInTx(tx -> {
      var first = newEntityInTx();
      var second = newEntityInTx();
      return new EntityImpl[] {first, second};
    });
    var dirtyEntity = entities[0];
    var cleanEntity = entities[1];
    // Force one entity into the dirty state by setting the public {@code dirty} field
    // directly (post-commit entities are not session-bound, so setDirty(long) would
    // throw via checkForBinding). The other stays at its post-commit clean state.
    dirtyEntity.dirty = 1L;
    forceStatusLoaded(dirtyEntity);
    forceStatusLoaded(cleanEntity);

    cache.put(dirtyEntity);
    cache.put(cleanEntity);

    // Preconditions.
    assertTrue("precondition: dirtyEntity must be dirty before the call",
        dirtyEntity.isDirty());
    assertFalse("precondition: dirtyEntity must be loaded before the call",
        dirtyEntity.isUnloaded());
    assertFalse("precondition: cleanEntity must be loaded before the call",
        cleanEntity.isUnloaded());

    cache.unloadRecords();

    // Per-entry effect on every record — both unsetDirty AND unload must have run.
    assertFalse("dirtyEntity.isDirty() must be false after unsetDirty()",
        dirtyEntity.isDirty());
    assertTrue("dirtyEntity.isUnloaded() must be true after unload()",
        dirtyEntity.isUnloaded());
    assertTrue("cleanEntity.isUnloaded() must be true after unload()",
        cleanEntity.isUnloaded());
    // The forEach iteration does not remove map entries: both put entries remain.
    // A regression where unloadRecords started removing entries fails here.
    assertEquals("backing map keeps both entries through unloadRecords",
        2, backingMapSize(cache));
  }

  @Test
  public void unloadOnEmptyCacheIsANoOp() {
    var cache = new RecordCacheWeakRefs();
    cache.unloadRecords();
    cache.unloadNotModifiedRecords();
    // Both calls must complete without throwing on an empty cache.
  }

  // ---------------------------------------------------------------------------
  // clear / shutdown — RecordCacheWeakRefs replaces the backing map
  // ---------------------------------------------------------------------------

  @Test
  public void clearReplacesTheBackingMapWithAFreshInstance() throws Exception {
    // RecordCacheWeakRefs#clear differs from AbstractMapCache#clear: it calls
    // cache.clear() AND replaces the backing field with a fresh
    // WeakValueHashMap. A regression that drops the replacement (i.e. forgets to
    // re-allocate) would leave the same instance in place after clear, so pin
    // identity inequality.
    var cache = new RecordCacheWeakRefs();
    var before = backingMap(cache);
    cache.clear();
    var after = backingMap(cache);
    assertNotSame("clear() must allocate a fresh WeakValueHashMap", before, after);
    assertEquals("the new map is empty", 0, backingMapSize(cache));
  }

  @Test
  public void shutdownDelegatesToClearAndReplacesTheBackingMap() throws Exception {
    var cache = new RecordCacheWeakRefs();
    var before = backingMap(cache);
    cache.shutdown();
    var after = backingMap(cache);
    assertNotSame("shutdown() routes through clear() and replaces the map", before, after);
  }

  @Test
  public void clearAfterDisableStillReplacesTheBackingMap() throws Exception {
    // Disable does not gate clear; the backing map must still be replaced.
    var cache = new RecordCacheWeakRefs();
    cache.disable();
    var before = backingMap(cache);
    cache.clear();
    var after = backingMap(cache);
    assertNotSame(before, after);
    assertFalse("clear must not re-enable the cache as a side effect", cache.isEnabled());
  }

  // ---------------------------------------------------------------------------
  // Lifecycle inherited from AbstractMapCache — quick sanity smoke
  // ---------------------------------------------------------------------------

  @Test
  public void startupIsANoOpInheritedFromBase() {
    var cache = new RecordCacheWeakRefs();
    cache.startup();
    // No assertion beyond no-throw — the inherited base method body is empty.
  }

  @Test
  public void freshlyConstructedCacheReportsEnabledTrueAndZeroSize() {
    var cache = new RecordCacheWeakRefs();
    assertTrue(cache.isEnabled());
    assertEquals(0, cache.size());
    assertTrue("keys() on an empty cache returns an empty collection",
        cache.keys().isEmpty());
  }

  // ---------------------------------------------------------------------------
  // Reflection helpers — read the protected `cache` field from AbstractMapCache
  // ---------------------------------------------------------------------------

  /**
   * Returns the current backing {@link WeakValueHashMap} via reflection. Used
   * to assert identity equality before/after {@code clear} calls — the
   * publicly-observable behavior of {@code RecordCacheWeakRefs#clear} is that
   * the backing field is replaced with a fresh instance.
   */
  private static Object backingMap(RecordCacheWeakRefs cache) throws Exception {
    Field f = AbstractMapCache.class.getDeclaredField("cache");
    f.setAccessible(true);
    return f.get(cache);
  }

  private static int backingMapSize(RecordCacheWeakRefs cache) throws Exception {
    return ((java.util.Map<?, ?>) backingMap(cache)).size();
  }

  /**
   * Force the entity's STATUS to LOADED via reflection on the protected {@code status} field on
   * {@link com.jetbrains.youtrackdb.internal.core.record.RecordAbstract}. Required after a tx
   * commit because committed entities may transition to NOT_LOADED, which would cause the
   * production unload() path's checkForBinding() to throw "not bound to current session" — that
   * is unrelated to the BiConsumer per-entry effect this test is pinning, so we sidestep it by
   * pretending the entity is still loaded. The STATUS enum lives on
   * {@link com.jetbrains.youtrackdb.internal.core.db.record.RecordElement}.
   */
  private static void forceStatusLoaded(EntityImpl entity) throws Exception {
    Class<?> recordAbstractClass =
        com.jetbrains.youtrackdb.internal.core.record.RecordAbstract.class;
    Field statusField = recordAbstractClass.getDeclaredField("status");
    statusField.setAccessible(true);
    statusField.set(
        entity,
        com.jetbrains.youtrackdb.internal.core.db.record.RecordElement.STATUS.LOADED);
  }
}
