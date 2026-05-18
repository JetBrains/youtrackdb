/*
 *
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

package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link LinkCollectionsBTreeManagerShared#verifyAndTruncateAllOrphans}.
 *
 * <p>The delegate is a pure iterate-and-dispatch fan-out over the manager's internal
 * {@code fileIdBTreeMap} — one {@link SharedLinkBagBTree#verifyAndTruncateOrphans} call
 * per loaded SLBB instance, with the supplied {@code (op, readCache, writeCache)} triple
 * forwarded unmodified. These tests use Mockito to stand in for SLBB instances and
 * reflection to install them into the manager's private map (the manager exposes no
 * public iteration API by design).
 */
public class LinkCollectionsBTreeManagerSharedVerifyAndTruncateAllOrphansTest {

  private LinkCollectionsBTreeManagerShared manager;
  private ConcurrentHashMap<Integer, SharedLinkBagBTree> fileIdBTreeMap;

  private AtomicOperation atomicOperation;
  private ReadCache readCache;
  private WriteCache writeCache;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    // The manager only needs a non-null storage reference for its ctor; the delegate
    // body does not call any storage method, so a bare mock is sufficient.
    var storage = mock(AbstractStorage.class);
    manager = new LinkCollectionsBTreeManagerShared(storage);

    // Manager exposes no public mutator for the SLBB map outside of its own
    // load/create paths; reach into the private field for direct fixture install.
    Field field = LinkCollectionsBTreeManagerShared.class.getDeclaredField("fileIdBTreeMap");
    field.setAccessible(true);
    fileIdBTreeMap = (ConcurrentHashMap<Integer, SharedLinkBagBTree>) field.get(manager);

    atomicOperation = mock(AtomicOperation.class);
    readCache = mock(ReadCache.class);
    writeCache = mock(WriteCache.class);
  }

  /**
   * With three SLBB instances installed in the map, the delegate must invoke
   * {@code verifyAndTruncateOrphans} exactly once on each, forwarding the supplied
   * {@code (op, readCache, writeCache)} triple unchanged. The verify(...) call uses the
   * exact same references, so it catches both missed invocations and accidental argument
   * substitution.
   */
  @Test
  public void testVerifyAndTruncateAllOrphansDispatchesToEachInstalledBTree() throws Exception {
    var bTree1 = mock(SharedLinkBagBTree.class);
    var bTree2 = mock(SharedLinkBagBTree.class);
    var bTree3 = mock(SharedLinkBagBTree.class);

    fileIdBTreeMap.put(101, bTree1);
    fileIdBTreeMap.put(102, bTree2);
    fileIdBTreeMap.put(103, bTree3);

    manager.verifyAndTruncateAllOrphans(atomicOperation, readCache, writeCache);

    verify(bTree1).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(bTree2).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);
    verify(bTree3).verifyAndTruncateOrphans(atomicOperation, readCache, writeCache);

    // Belt-and-braces: each SLBB receives exactly one call, no other interactions.
    verify(bTree1, never()).load(atomicOperation);
    verify(bTree2, never()).load(atomicOperation);
    verify(bTree3, never()).load(atomicOperation);
  }

  /**
   * The empty-map case (no collections loaded yet — typical at storage open before any
   * ridbag has been created) must be a clean no-op: no NPE from iterating an empty map,
   * no interaction with any cache, and the call returns normally.
   */
  @Test
  public void testVerifyAndTruncateAllOrphansEmptyMapIsNoop() throws Exception {
    // Sanity check: map is empty at the start of the test (manager ctor leaves it empty).
    org.junit.Assert.assertTrue(fileIdBTreeMap.isEmpty());

    manager.verifyAndTruncateAllOrphans(atomicOperation, readCache, writeCache);

    // No SLBB calls means no readCache / writeCache calls either — the delegate must
    // not touch the caches itself; it only forwards them to per-SLBB helpers.
    verifyNoInteractions(readCache);
    verifyNoInteractions(writeCache);
  }
}
