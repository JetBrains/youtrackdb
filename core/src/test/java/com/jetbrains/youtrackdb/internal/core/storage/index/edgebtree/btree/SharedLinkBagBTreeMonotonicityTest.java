package com.jetbrains.youtrackdb.internal.core.storage.index.edgebtree.btree;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EdgeKey;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.LinkBagValue;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree;
import java.io.File;
import org.junit.Test;

/**
 * Tests that version monotonicity violations in {@link SharedLinkBagBTree}
 * are detected and rejected. Each test creates its own database because the
 * {@link StorageException} thrown by the monotonicity guard marks the storage
 * as broken, preventing further operations.
 */
public class SharedLinkBagBTreeMonotonicityTest {

  @Test
  public void testPutWithLowerTsThrows() throws Exception {
    // Insert with ts=10, then attempt update with ts=5 from a different
    // atomic operation. Must throw StorageException for monotonicity violation.
    final String dir = System.getProperty("buildDirectory", "./target") + "/monotonPutTest";
    FileUtils.deleteRecursively(new File(dir));

    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(dir)) {
      ytdb.create("testDb", DatabaseType.DISK, "admin", "admin", "admin");

      try (var session = ytdb.open("testDb", "admin", "admin")) {
        var storage = (AbstractStorage) session.getStorage();
        var atomicOpsManager = storage.getAtomicOperationsManager();

        var bTree = new SharedLinkBagBTree(storage, "monotonPut", ".sbc");
        atomicOpsManager.executeInsideAtomicOperation(bTree::create);

        atomicOpsManager.executeInsideAtomicOperation(atomicOperation -> {
          bTree.put(atomicOperation, new EdgeKey(1L, 10, 100L, 10L),
              new LinkBagValue(1, 0, 0, false));
        });

        assertThatThrownBy(
            () -> atomicOpsManager.executeInsideAtomicOperation(atomicOperation -> {
              bTree.put(atomicOperation, new EdgeKey(1L, 10, 100L, 5L),
                  new LinkBagValue(2, 0, 0, false));
            }))
            .isInstanceOf(StorageException.class)
            .rootCause()
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("monotonicity");
      }
    } finally {
      FileUtils.deleteRecursively(new File(dir));
    }
  }

  @Test
  public void testRemoveWithLowerTsThrows() throws Exception {
    // Insert with ts=10, then attempt remove with ts=5 from a different
    // atomic operation. Must throw StorageException for monotonicity violation.
    final String dir = System.getProperty("buildDirectory", "./target") + "/monotonRmTest";
    FileUtils.deleteRecursively(new File(dir));

    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(dir)) {
      ytdb.create("testDb", DatabaseType.DISK, "admin", "admin", "admin");

      try (var session = ytdb.open("testDb", "admin", "admin")) {
        var storage = (AbstractStorage) session.getStorage();
        var atomicOpsManager = storage.getAtomicOperationsManager();

        var bTree = new SharedLinkBagBTree(storage, "monotonRm", ".sbc");
        atomicOpsManager.executeInsideAtomicOperation(bTree::create);

        atomicOpsManager.executeInsideAtomicOperation(atomicOperation -> {
          bTree.put(atomicOperation, new EdgeKey(1L, 10, 100L, 10L),
              new LinkBagValue(1, 0, 0, false));
        });

        assertThatThrownBy(
            () -> atomicOpsManager.executeInsideAtomicOperation(atomicOperation -> {
              bTree.remove(atomicOperation, new EdgeKey(1L, 10, 100L, 5L));
            }))
            .isInstanceOf(StorageException.class)
            .rootCause()
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("monotonicity");
      }
    } finally {
      FileUtils.deleteRecursively(new File(dir));
    }
  }
}
