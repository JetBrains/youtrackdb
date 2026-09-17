package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Verifies link-bag counter recovery through write-ahead log replay. */
@Category(SequentialTest.class)
public class LinkCollectionsBTreeManagerSharedAllocatorCrashRecoveryIT {

  private String directory;
  private YouTrackDBImpl youTrackDB;

  @Before
  public void setUp() {
    directory = DbTestBase.getBaseDirectoryPathStr(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directory);
  }

  @After
  public void tearDown() throws Exception {
    if (youTrackDB != null) {
      youTrackDB.close();
    }
    FileUtils.deleteDirectory(new File(directory));
  }

  @Test
  public void committedEmptyBagAllocationIsReplayedFromWal() throws Exception {
    var sourceName = "linkBagCounterSource";
    var recoveredName = "linkBagCounterRecovered";
    youTrackDB.create(
        sourceName,
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    int collectionId;
    try (var session = (DatabaseSessionEmbedded) youTrackDB.open(sourceName, "admin", "admin")) {
      collectionId = session.getMetadata().getSchema().createClass("CrashOwner")
          .getCollectionIds()[0];
    }

    // Persist the entry-point page with counter zero before the allocation under test.
    youTrackDB.close();
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directory);

    try (var session = (DatabaseSessionEmbedded) youTrackDB.open(sourceName, "admin", "admin")) {
      var sourcePath = Path.of(session.getURL().substring("disk:".length()));
      var recoveredPath = Path.of(directory).resolve(recoveredName);
      var storage = (DiskStorage) session.getStorage();

      WalTestUtils.withWalProtection(session, () -> {
        // The paused page flusher keeps the copied entry point at counter zero.
        var pointer = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            operation -> session.getBTreeCollectionManager()
                .createBTree(collectionId, operation, session));
        assertEquals(-1L, pointer.linkBagId());
        storage.getWALInstance().flush();
        copyDirtyStorage(sourcePath, recoveredPath);
      });
    }

    try (var session =
        (DatabaseSessionEmbedded) youTrackDB.open(recoveredName, "admin", "admin")) {
      var storage = (AbstractStorage) session.getStorage();
      assertTrue("The copied dirty storage must replay its write-ahead log",
          storage.wereDataRestoredAfterOpen());

      var pointer = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
          operation -> session.getBTreeCollectionManager()
              .createBTree(collectionId, operation, session));
      assertEquals(-2L, pointer.linkBagId());
    }
  }

  private static void copyDirtyStorage(Path source, Path target) throws IOException {
    FileUtils.deleteDirectory(target.toFile());
    Files.createDirectory(target);

    try (var files = Files.list(source)) {
      for (var sourceFile : files.toList()) {
        var fileName = sourceFile.getFileName().toString();
        if (fileName.equals("dirty.fl") || fileName.equals("dirty.flb")) {
          continue;
        }
        try {
          Files.copy(sourceFile, target.resolve(fileName));
        } catch (NoSuchFileException missingFile) {
          if (!fileName.endsWith(".dwl")) {
            throw missingFile;
          }
        }
      }
    }
  }
}
