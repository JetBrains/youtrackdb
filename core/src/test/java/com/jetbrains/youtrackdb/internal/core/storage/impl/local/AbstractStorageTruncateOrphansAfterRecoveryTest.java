/*
 *
 *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.withSettings;

import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.CollectionPositionMapV2;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@code AbstractStorage.truncateOrphansAfterRecovery(AtomicOperation)}.
 *
 * <p>The orchestrator is a pure iterate-and-dispatch fan-out over three private fields on
 * {@link AbstractStorage}: {@code collections}, {@code indexEngines}, and
 * {@code linkCollectionsBTreeManager}. Each per-component / per-engine / manager helper is
 * tested independently in its own suite; this test pins only the orchestrator's contract:
 *
 * <ul>
 *   <li>For every non-null {@link PaginatedCollectionV2} in {@code collections}, dispatch
 *       {@code verifyAndTruncateOrphans} on BOTH the collection itself AND its embedded
 *       {@code collectionPositionMap}.</li>
 *   <li>For every {@link BTreeSingleValueIndexEngine} or {@link BTreeMultiValueIndexEngine}
 *       in {@code indexEngines}, dispatch {@code verifyAndTruncateOrphans} on the engine
 *       (the engine-side wrapper internally handles {@code sbTree} / {@code svTree} /
 *       {@code nullTree}).</li>
 *   <li>Other engine types and {@code null} engine slots are skipped.</li>
 *   <li>The {@code linkCollectionsBTreeManager} receives exactly one
 *       {@code verifyAndTruncateAllOrphans} call regardless of how many SLBBs it manages
 *       (iteration is internal to the manager).</li>
 *   <li>The same {@code (atomicOperation, readCache, writeCache)} triple flows to every
 *       dispatched helper without modification.</li>
 * </ul>
 *
 * <p>The {@code AbstractStorage} mock is constructed with {@code CALLS_REAL_METHODS} so
 * the target method's body executes against the mocked instance; reflection installs the
 * private fields the orchestrator iterates. Reflection-on-private-final-fields is
 * acceptable here because the test couples to a small set of well-named fields and a
 * rename would surface as a compile-time failure inside this test rather than a silent
 * semantic shift.
 */
public class AbstractStorageTruncateOrphansAfterRecoveryTest {

  private AbstractStorage storage;
  private ReadCache readCache;
  private WriteCache writeCache;
  private AtomicOperation atomicOperation;
  private LinkCollectionsBTreeManagerShared manager;
  private List<StorageCollection> collectionsField;
  private List<BaseIndexEngine> indexEnginesField;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    // CALLS_REAL_METHODS lets the orchestrator method body execute on the mock; readCache /
    // writeCache field access still goes through the mock instance, so we install them
    // explicitly via reflection rather than rely on stubbed getters.
    storage = mock(AbstractStorage.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

    readCache = mock(ReadCache.class);
    writeCache = mock(WriteCache.class);
    atomicOperation = mock(AtomicOperation.class);
    manager = mock(LinkCollectionsBTreeManagerShared.class);

    installPrivateField("readCache", readCache);
    installPrivateField("writeCache", writeCache);
    installPrivateField("linkCollectionsBTreeManager", manager);

    // collections is a CopyOnWriteArrayList<StorageCollection> and indexEngines is an
    // ArrayList<BaseIndexEngine>; pulling them by reflection so each test can populate
    // them with the exact mix required for the branch it exercises.
    collectionsField =
        (List<StorageCollection>) readPrivateField("collections");
    indexEnginesField = (List<BaseIndexEngine>) readPrivateField("indexEngines");
    collectionsField.clear();
    indexEnginesField.clear();
  }

  /**
   * With one {@link PaginatedCollectionV2} loaded, the orchestrator must dispatch
   * {@code verifyAndTruncateOrphans} on BOTH the collection AND its position map. The
   * forwarded triple must match the orchestrator's argument exactly.
   */
  @Test
  public void dispatchesOnPaginatedCollectionAndItsPositionMap() throws Exception {
    var pcv2 = mock(PaginatedCollectionV2.class);
    var positionMap = mock(CollectionPositionMapV2.class);
    org.mockito.Mockito.when(pcv2.getCollectionPositionMap()).thenReturn(positionMap);
    collectionsField.add(pcv2);

    storage.truncateOrphansAfterRecovery(atomicOperation);

    verify(pcv2).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(positionMap).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(manager).verifyAndTruncateAllOrphans(atomicOperation, readCache, writeCache);
  }

  /**
   * The {@code collections} list is a sparse array (null slots indicate dropped
   * collections). The orchestrator's {@code instanceof PaginatedCollectionV2} check must
   * silently skip the {@code null} entries; an unfiltered iteration would NPE on the
   * downcast.
   */
  @Test
  public void skipsNullCollectionSlots() throws Exception {
    var pcv2 = mock(PaginatedCollectionV2.class);
    var positionMap = mock(CollectionPositionMapV2.class);
    org.mockito.Mockito.when(pcv2.getCollectionPositionMap()).thenReturn(positionMap);
    collectionsField.add(null);
    collectionsField.add(pcv2);
    collectionsField.add(null);

    storage.truncateOrphansAfterRecovery(atomicOperation);

    verify(pcv2).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(positionMap).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(manager).verifyAndTruncateAllOrphans(atomicOperation, readCache, writeCache);
  }

  /**
   * For a single-value BTree index engine, the orchestrator dispatches
   * {@code verifyAndTruncateOrphans} on the engine itself (the engine's wrapper internally
   * delegates to its inner {@code sbTree}).
   */
  @Test
  public void dispatchesOnBTreeSingleValueIndexEngine() throws Exception {
    var svEngine = mock(BTreeSingleValueIndexEngine.class);
    indexEnginesField.add(svEngine);

    storage.truncateOrphansAfterRecovery(atomicOperation);

    verify(svEngine).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(manager).verifyAndTruncateAllOrphans(atomicOperation, readCache, writeCache);
  }

  /**
   * For a multi-value BTree index engine, the orchestrator dispatches once on the engine
   * (the engine's wrapper internally fans out to both {@code svTree} and {@code nullTree}
   * when the latter is non-null — verified in {@code BTreeIndexEngineVerifyAndTruncateOrphansTest}).
   */
  @Test
  public void dispatchesOnBTreeMultiValueIndexEngine() throws Exception {
    var mvEngine = mock(BTreeMultiValueIndexEngine.class);
    indexEnginesField.add(mvEngine);

    storage.truncateOrphansAfterRecovery(atomicOperation);

    verify(mvEngine).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(manager).verifyAndTruncateAllOrphans(atomicOperation, readCache, writeCache);
  }

  /**
   * Other engine types and {@code null} index-engine slots are skipped. A non-BTree
   * engine like {@code HashIndexEngine} sits on the engine list during normal storage
   * operation but has no orphan-truncation hook — the orchestrator must NOT call
   * {@code verifyAndTruncateOrphans} on it (no such method exists on {@code BaseIndexEngine}).
   */
  @Test
  public void skipsOtherEngineTypesAndNullSlots() throws Exception {
    var otherEngine = mock(BaseIndexEngine.class);
    indexEnginesField.add(null);
    indexEnginesField.add(otherEngine);
    indexEnginesField.add(null);

    storage.truncateOrphansAfterRecovery(atomicOperation);

    verifyNoInteractions(otherEngine);
    verify(manager).verifyAndTruncateAllOrphans(atomicOperation, readCache, writeCache);
  }

  /**
   * A storage with no collections loaded and no index engines must still call into the
   * SLBB manager exactly once. The manager's own delegate handles the empty-map case as a
   * clean no-op.
   */
  @Test
  public void dispatchesManagerEvenWhenNoCollectionsOrEngines() throws Exception {
    storage.truncateOrphansAfterRecovery(atomicOperation);

    verify(manager).verifyAndTruncateAllOrphans(atomicOperation, readCache, writeCache);
  }

  /**
   * Combined scenario: a sparse {@code collections} list, two BTree engines of different
   * shapes plus an unrelated engine, all dispatched in one pass. The test asserts each
   * helper receives exactly the expected dispatch and the manager fires exactly once.
   */
  @Test
  public void dispatchesAllGroupsInOnePass() throws Exception {
    var pcv2 = mock(PaginatedCollectionV2.class);
    var positionMap = mock(CollectionPositionMapV2.class);
    org.mockito.Mockito.when(pcv2.getCollectionPositionMap()).thenReturn(positionMap);
    var svEngine = mock(BTreeSingleValueIndexEngine.class);
    var mvEngine = mock(BTreeMultiValueIndexEngine.class);
    var otherEngine = mock(BaseIndexEngine.class);
    collectionsField.add(null);
    collectionsField.add(pcv2);
    indexEnginesField.add(svEngine);
    indexEnginesField.add(otherEngine);
    indexEnginesField.add(mvEngine);
    indexEnginesField.add(null);

    storage.truncateOrphansAfterRecovery(atomicOperation);

    verify(pcv2).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(positionMap).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(svEngine).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(mvEngine).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verifyNoInteractions(otherEngine);
    verify(manager).verifyAndTruncateAllOrphans(atomicOperation, readCache, writeCache);
  }

  /**
   * If a per-component helper throws an {@link java.io.IOException} (e.g., corrupted EP
   * page surfaced by {@code checksumMode=StoreAndThrow}), the exception propagates and
   * downstream helpers in later groups must NOT be invoked. This is the documented
   * abort-the-open contract.
   */
  @Test
  public void propagatesIOExceptionAndAbortsRemainingGroups() throws Exception {
    var pcv2 = mock(PaginatedCollectionV2.class);
    var positionMap = mock(CollectionPositionMapV2.class);
    org.mockito.Mockito.when(pcv2.getCollectionPositionMap()).thenReturn(positionMap);
    var svEngine = mock(BTreeSingleValueIndexEngine.class);
    org.mockito.Mockito.doThrow(new java.io.IOException("corrupted EP"))
        .when(pcv2)
        .verifyAndTruncateOrphans(any(), any(), any());
    collectionsField.add(pcv2);
    indexEnginesField.add(svEngine);

    try {
      storage.truncateOrphansAfterRecovery(atomicOperation);
      org.junit.Assert.fail("expected IOException to propagate");
    } catch (java.io.IOException expected) {
      // Expected — the orchestrator does not swallow per-component failures.
    }

    // Position map call sits AFTER the pcv2 call inside the same iteration and must NOT
    // fire after the pcv2 helper throws.
    verify(positionMap, never())
        .verifyAndTruncateOrphans(any(), any(), any());
    verify(svEngine, never()).verifyAndTruncateOrphans(any(), any(), any());
    verify(manager, never()).verifyAndTruncateAllOrphans(any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // Reflection helpers
  // -------------------------------------------------------------------------

  private void installPrivateField(String name, Object value) throws Exception {
    Field field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(storage, value);
  }

  private Object readPrivateField(String name) throws Exception {
    Field field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    var value = field.get(storage);
    if (value == null) {
      // The default Mockito mock initialises private fields to null; instantiate a safe
      // mutable stand-in so tests can append entries via the live list reference.
      if (name.equals("collections")) {
        value = new CopyOnWriteArrayList<StorageCollection>();
      } else if (name.equals("indexEngines")) {
        value = new ArrayList<BaseIndexEngine>();
      }
      field.set(storage, value);
    }
    return value;
  }
}
