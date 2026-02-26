package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;

import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Random;
import java.util.TreeMap;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class FreeSpaceMapTestIT {

  private FreeSpaceMap freeSpaceMap;

  private static YouTrackDBImpl youTrackDB;
  private static String dbName;
  private static AbstractStorage storage;
  private static AtomicOperationsManager atomicOperationsManager;

  @BeforeClass
  public static void beforeClass() throws IOException {
    var buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null || buildDirectory.isEmpty()) {
      buildDirectory = ".";
    }

    buildDirectory += File.separator + FreeSpaceMapTestIT.class.getSimpleName();
    FileUtils.deleteRecursively(new File(buildDirectory));

    dbName = "freeSpaceMapTest";

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(buildDirectory);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    final var databaseDocumentTx =
        youTrackDB.open(dbName, "admin", "admin");

    storage = databaseDocumentTx.getStorage();
    atomicOperationsManager = storage.getAtomicOperationsManager();
    databaseDocumentTx.close();
  }

  @AfterClass
  public static void afterClass() {
    youTrackDB.drop(dbName);
    youTrackDB.close();
  }

  @Before
  public void before() throws IOException {
    freeSpaceMap = new FreeSpaceMap(storage, "freeSpaceMap", ".fsm", "freeSpaceMap");

    atomicOperationsManager.executeInsideAtomicOperation(
        atomicOperation -> freeSpaceMap.create(atomicOperation));
  }

  // Verifies that after registering a single page (index 3) with 512 bytes of free space,
  // findFreePage returns that page's index when at least 259 bytes are requested.
  @Test
  public void findSinglePage() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> {
          freeSpaceMap.updatePageFreeSpace(operation, 3, 512);
          Assert.assertEquals(
              "page 3 should be found for 259 bytes",
              3, freeSpaceMap.findFreePage(259, operation));
        });
  }

  // Verifies that the two-level FSM tree correctly handles a large page index (128956),
  // which forces expansion across multiple second-level pages.
  @Test
  public void findSinglePageHighIndex() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> {
          freeSpaceMap.updatePageFreeSpace(operation, 128956, 512);
          Assert.assertEquals(
              "high-index page 128956 should be found for 259 bytes",
              128956, freeSpaceMap.findFreePage(259, operation));
        });
  }

  // Verifies that when multiple pages have different free-space amounts (1024, 2029, 3029),
  // requesting 1024 bytes returns the first page whose free space meets the requirement
  // (page 4 with 2029 bytes), not the one with just barely enough (page 3 with 1024 bytes).
  // This is because normalized space is checked as requiredSize/INTERVAL + 1, so 1024
  // requires a normalized value of 33, which exceeds page 3's normalized value of 32.
  @Test
  public void findSinglePageLowerSpaceOne() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> {
          freeSpaceMap.updatePageFreeSpace(operation, 3, 1024);
          freeSpaceMap.updatePageFreeSpace(operation, 4, 2029);
          freeSpaceMap.updatePageFreeSpace(operation, 5, 3029);

          Assert.assertEquals(
              "page 4 should be the first match for 1024 bytes",
              4, freeSpaceMap.findFreePage(1024, operation));
        });
  }

  // Verifies that when multiple pages have different free-space amounts (1024, 2029, 3029),
  // requesting 2050 bytes returns page 5 (3029 bytes) because page 4 (2029 bytes) is too small.
  @Test
  public void findSinglePageLowerSpaceTwo() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> {
          freeSpaceMap.updatePageFreeSpace(operation, 3, 1024);
          freeSpaceMap.updatePageFreeSpace(operation, 4, 2029);
          freeSpaceMap.updatePageFreeSpace(operation, 5, 3029);

          Assert.assertEquals(
              "page 5 should be the first match for 2050 bytes",
              5, freeSpaceMap.findFreePage(2050, operation));
        });
  }

  // Populates 1000 pages with random free-space values, then verifies 1000 random lookups:
  // each lookup must return a page with at least the requested free space, or -1 if no
  // page qualifies. The seed is logged for reproducibility on failure.
  // Note: the test normalizes as freeSpace/INTERVAL (floor), while findFreePage internally
  // uses requiredSize/INTERVAL + 1 (ceiling). This means the boundary check
  // "freeSpaceIndex < maxFreeSpaceIndex" is conservative — when the two are equal,
  // findFreePage's +1 exceeds the stored maximum, correctly returning -1.
  @Test
  public void randomPages() throws IOException {
    final var pages = 1_000;
    final var checks = 1_000;

    final var pageSpaceMap = new HashMap<Integer, Integer>();
    final var seed = System.nanoTime();
    System.out.println("randomPages seed - " + seed);
    final var random = new Random(seed);

    var maxFreeSpaceIndex = new int[]{-1};
    for (var i = 0; i < pages; i++) {
      final var pageIndex = i;

      atomicOperationsManager.executeInsideAtomicOperation(
          operation -> {
            final var freeSpace = random.nextInt(DurablePage.MAX_PAGE_SIZE_BYTES);
            final var freeSpaceIndex = freeSpace / FreeSpaceMap.NORMALIZATION_INTERVAL;
            if (maxFreeSpaceIndex[0] < freeSpaceIndex) {
              maxFreeSpaceIndex[0] = freeSpaceIndex;
            }

            pageSpaceMap.put(pageIndex, freeSpace);
            freeSpaceMap.updatePageFreeSpace(operation, pageIndex, freeSpace);
          });
    }

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      for (var i = 0; i < checks; i++) {
        final var freeSpace = random.nextInt(DurablePage.MAX_PAGE_SIZE_BYTES);
        final var pageIndex = freeSpaceMap.findFreePage(freeSpace, atomicOperation);
        final var freeSpaceIndex = freeSpace / FreeSpaceMap.NORMALIZATION_INTERVAL;
        if (freeSpaceIndex < maxFreeSpaceIndex[0]) {
          final var actualSpace = pageSpaceMap.get(pageIndex);
          Assert.assertNotNull(
              "seed=" + seed + ": findFreePage returned unknown page " + pageIndex,
              actualSpace);
          Assert.assertTrue(
              "seed=" + seed + ": page " + pageIndex
                  + " has " + actualSpace
                  + " bytes but " + freeSpace + " was requested",
              actualSpace >= freeSpace);
        } else {
          Assert.assertEquals(
              "seed=" + seed + ": no page should satisfy " + freeSpace + " bytes",
              -1, pageIndex);
        }
      }
    });
  }

  // Populates 1000 pages, updates all of them with new random free-space values, then
  // verifies 1000 random lookups against the updated state. Ensures that updatePageFreeSpace
  // correctly replaces previous values in the two-level tree.
  @Test
  public void randomPagesUpdate() throws IOException {
    final var pages = 1_000;
    final var checks = 1_000;

    final var pageFreeSpaceMap = new HashMap<Integer, Integer>();
    final var inMemoryFreeSpaceMap = new TreeMap<Integer, Integer>();

    final var seed = System.nanoTime();
    System.out.println("randomPagesUpdate seed - " + seed);

    final var random = new Random(seed);

    for (var i = 0; i < pages; i++) {
      final var pageIndex = i;

      atomicOperationsManager.executeInsideAtomicOperation(
          operation -> {
            final var freeSpace = random.nextInt(DurablePage.MAX_PAGE_SIZE_BYTES);
            pageFreeSpaceMap.put(pageIndex, freeSpace);
            inMemoryFreeSpaceMap.compute(
                freeSpace,
                (k, v) -> {
                  if (v == null) {
                    return 1;
                  }

                  return v + 1;
                });

            freeSpaceMap.updatePageFreeSpace(operation, pageIndex, freeSpace);
          });
    }

    for (var i = 0; i < pages; i++) {
      final var pageIndex = i;

      atomicOperationsManager.executeInsideAtomicOperation(
          operation -> {
            final var freeSpace = random.nextInt(DurablePage.MAX_PAGE_SIZE_BYTES);
            final int oldFreeSpace = pageFreeSpaceMap.get(pageIndex);

            pageFreeSpaceMap.put(pageIndex, freeSpace);
            inMemoryFreeSpaceMap.compute(
                freeSpace,
                (k, v) -> {
                  if (v == null) {
                    return 1;
                  }

                  return v + 1;
                });

            inMemoryFreeSpaceMap.compute(
                oldFreeSpace,
                (k, v) -> {
                  //noinspection ConstantConditions
                  if (v == 1) {
                    return null;
                  }

                  return v - 1;
                });

            freeSpaceMap.updatePageFreeSpace(operation, pageIndex, freeSpace);
          });
    }

    atomicOperationsManager.executeInsideAtomicOperation(atomicOperation -> {
      final var maxFreeSpaceIndex =
          inMemoryFreeSpaceMap.lastKey() / FreeSpaceMap.NORMALIZATION_INTERVAL;
      for (var i = 0; i < checks; i++) {
        final var freeSpace = random.nextInt(DurablePage.MAX_PAGE_SIZE_BYTES);
        final var pageIndex = freeSpaceMap.findFreePage(freeSpace, atomicOperation);
        final var freeSpaceIndex = freeSpace / FreeSpaceMap.NORMALIZATION_INTERVAL;

        if (freeSpaceIndex < maxFreeSpaceIndex) {
          final var actualSpace = pageFreeSpaceMap.get(pageIndex);
          Assert.assertNotNull(
              "seed=" + seed + ": findFreePage returned unknown page " + pageIndex,
              actualSpace);
          Assert.assertTrue(
              "seed=" + seed + ": page " + pageIndex
                  + " has " + actualSpace
                  + " bytes but " + freeSpace + " was requested",
              actualSpace >= freeSpace);
        } else {
          Assert.assertEquals(
              "seed=" + seed + ": no page should satisfy " + freeSpace + " bytes",
              -1, pageIndex);
        }
      }
    });
  }

  // Verifies that exists() returns true for a map that was just created.
  // The FSM file is created in @Before, so it must be detectable.
  @Test
  public void existsReturnsTrueAfterCreation() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> Assert.assertTrue(
            "FSM should exist immediately after creation",
            freeSpaceMap.exists(operation)));
  }

  // Verifies that a previously created and populated FreeSpaceMap can be opened
  // by a new instance, and that the stored free-space data survives the reopen.
  // Uses two pages at different thresholds so each can be identified uniquely:
  // page 7 has 2048 bytes, page 12 has 4096 bytes.
  @Test
  public void openPreservesPageData() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> {
          freeSpaceMap.updatePageFreeSpace(operation, 7, 2048);
          freeSpaceMap.updatePageFreeSpace(operation, 12, 4096);
        });

    final var reopened = new FreeSpaceMap(storage, "freeSpaceMap", ".fsm", "freeSpaceMap");
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> {
          reopened.open(operation);

          // Requesting 3000 bytes — only page 12 (4096) qualifies
          Assert.assertEquals(
              "only page 12 has enough space for 3000 bytes",
              12, reopened.findFreePage(3000, operation));

          // Requesting 1500 bytes — page 7 (2048) is the first match in the tree
          Assert.assertEquals(
              "page 7 is the first page with enough space for 1500 bytes",
              7, reopened.findFreePage(1500, operation));

          // A request exceeding the maximum stored free space should return -1
          Assert.assertEquals(
              "no page should satisfy a request near MAX_PAGE_SIZE_BYTES",
              -1, reopened.findFreePage(
                  DurablePage.MAX_PAGE_SIZE_BYTES - 1, operation));
        });
  }

  // Verifies that delete() removes the underlying file so the map no longer exists.
  @Test
  public void deleteRemovesFile() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> {
          Assert.assertTrue(
              "map should exist before deletion", freeSpaceMap.exists(operation));
          freeSpaceMap.delete(operation);
          Assert.assertFalse(
              "map should not exist after deletion", freeSpaceMap.exists(operation));
        });

    // Recreate so @After cleanup succeeds
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> freeSpaceMap.create(operation));
  }

  // Verifies that rename() changes the underlying file name while preserving
  // stored data, so lookups still work through the renamed map.
  @Test
  public void renamePreservesDataUnderNewName() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> freeSpaceMap.updatePageFreeSpace(operation, 10, 1500));

    freeSpaceMap.rename("renamedFreeSpaceMap");
    Assert.assertEquals(
        "getFullName() should reflect the renamed file",
        "renamedFreeSpaceMap.fsm", freeSpaceMap.getFullName());

    // Data should still be accessible after rename
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> {
          final var pageIndex = freeSpaceMap.findFreePage(1024, operation);
          Assert.assertEquals(
              "page 10 should still be found after rename", 10, pageIndex);
        });

    // Rename back so @After cleanup finds the file under its original name
    freeSpaceMap.rename("freeSpaceMap");
  }

  // Verifies that findFreePage returns -1 when the map is freshly created
  // and no pages have been registered, confirming correct empty-map behavior.
  @Test
  public void findFreePageReturnsMinusOneWhenEmpty() throws IOException {
    atomicOperationsManager.executeInsideAtomicOperation(
        operation -> Assert.assertEquals(
            "empty map should return -1 for any space request",
            -1, freeSpaceMap.findFreePage(100, operation)));
  }

  @After
  public void after() throws IOException {
    final var writeCache = storage.getWriteCache();

    // Try current name first; if the test renamed the file and then failed before
    // renaming back, fall back to the known alternative name.
    var id = writeCache.fileIdByName(freeSpaceMap.getFullName());
    if (id < 0) {
      id = writeCache.fileIdByName("renamedFreeSpaceMap.fsm");
    }
    if (id >= 0) {
      storage.getReadCache().deleteFile(id, writeCache);
    }
  }
}
