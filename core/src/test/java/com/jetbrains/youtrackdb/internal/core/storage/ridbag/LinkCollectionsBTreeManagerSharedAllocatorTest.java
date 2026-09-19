package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Tests durable global link-bag identifier allocation across rollback and storage reopen. */
public class LinkCollectionsBTreeManagerSharedAllocatorTest {

  @Test
  public void emptyCommittedBagSurvivesReopenWhileRolledBackAllocationIsReusable()
      throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-allocator";
    var databaseName = "allocatorState";
    FileUtils.deleteRecursively(new File(directory));
    YouTrackDBImpl tracks = null;
    try {
      tracks = (YouTrackDBImpl) YourTracks.instance(directory);
      tracks.create(
          databaseName,
          DatabaseType.DISK,
          new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

      int collectionId;
      LinkBagPointer committed;
      try (var session = tracks.open(databaseName, "admin", "admin")) {
        collectionId = session.getMetadata().getSchema().createClass("AllocatorOwner")
            .getCollectionIds()[0];
        var storage = (AbstractStorage) session.getStorage();
        var manager = session.getBTreeCollectionManager();
        committed = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            operation -> manager.createBTree(collectionId, operation, session));

        try {
          storage.getAtomicOperationsManager().executeInsideAtomicOperation(operation -> {
            manager.createBTree(collectionId, operation, session);
            throw new ExpectedRollbackException();
          });
          fail("The allocation operation must roll back");
        } catch (RuntimeException expected) {
          var cause = expected;
          while (cause.getCause() instanceof RuntimeException runtimeCause) {
            cause = runtimeCause;
          }
          assertEquals(ExpectedRollbackException.class, cause.getClass());
        }
      }

      tracks.close();
      tracks = (YouTrackDBImpl) YourTracks.instance(directory);
      try (var session = tracks.open(databaseName, "admin", "admin")) {
        var storage = (AbstractStorage) session.getStorage();
        var reopened = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            operation -> session.getBTreeCollectionManager()
                .createBTree(collectionId, operation, session));

        assertEquals(-1L, committed.linkBagId());
        assertEquals(-2L, reopened.linkBagId());
        assertNotEquals(committed, reopened);
      }
    } finally {
      if (tracks != null) {
        tracks.close();
      }
      FileUtils.deleteRecursively(new File(directory));
    }
  }

  @Test
  public void allocationRejectsCounterExhaustionWithoutMutation() throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-exhaustion";
    var databaseName = "allocatorExhaustion";
    FileUtils.deleteRecursively(new File(directory));
    YouTrackDBImpl tracks = null;
    try {
      tracks = (YouTrackDBImpl) YourTracks.instance(directory);
      tracks.create(
          databaseName,
          DatabaseType.DISK,
          new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
      try (var session = tracks.open(databaseName, "admin", "admin")) {
        var collectionId = session.getMetadata().getSchema().createClass("ExhaustionOwner")
            .getCollectionIds()[0];
        var manager = (LinkCollectionsBTreeManagerShared) session.getBTreeCollectionManager();
        var counter = LinkCollectionsBTreeManagerShared.class
            .getDeclaredField("ridBagIdCounter");
        counter.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) counter.get(manager)).set(Long.MAX_VALUE);

        var storage = (AbstractStorage) session.getStorage();
        var failure = org.junit.Assert.assertThrows(
            com.jetbrains.youtrackdb.internal.core.exception.StorageException.class,
            () -> storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
                operation -> manager.createBTree(collectionId, operation, session)));
        assertTrue(failure.getCause().getMessage()
            .contains("Link-bag identifier allocation is exhausted"));
        assertEquals(Long.MAX_VALUE,
            ((java.util.concurrent.atomic.AtomicLong) counter.get(manager)).get());
      }
    } finally {
      if (tracks != null) {
        tracks.close();
      }
      FileUtils.deleteRecursively(new File(directory));
    }
  }

  @Test
  public void reverseCommitOrderRestoresTheLargestGlobalIdentifier() throws Exception {
    var directory = DbTestBase.getBaseDirectoryPathStr(getClass()) + "-reverse";
    var databaseName = "reverseCommit";
    FileUtils.deleteRecursively(new File(directory));
    YouTrackDBImpl tracks = null;
    try {
      tracks = (YouTrackDBImpl) YourTracks.instance(directory);
      tracks.create(
          databaseName,
          DatabaseType.DISK,
          new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

      int firstCollectionId;
      int secondCollectionId;
      try (var session = tracks.open(databaseName, "admin", "admin")) {
        firstCollectionId = session.getMetadata().getSchema().createClass("FirstOwner")
            .getCollectionIds()[0];
        secondCollectionId = session.getMetadata().getSchema().createClass("SecondOwner")
            .getCollectionIds()[0];
      }

      var firstAllocated = new CountDownLatch(1);
      var secondCommitted = new CountDownLatch(1);
      var firstPointer = new AtomicReference<LinkBagPointer>();
      var threadFailure = new AtomicReference<Throwable>();
      var activeTracks = tracks;
      var firstThread = new Thread(() -> {
        try (var session = activeTracks.open(databaseName, "admin", "admin")) {
          var storage = (AbstractStorage) session.getStorage();
          storage.getAtomicOperationsManager().calculateInsideAtomicOperation(operation -> {
            firstPointer.set(session.getBTreeCollectionManager()
                .createBTree(firstCollectionId, operation, session));
            firstAllocated.countDown();
            try {
              secondCommitted.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new AssertionError("Reverse-commit wait was interrupted", interrupted);
            }
            return null;
          });
        } catch (Throwable failure) {
          threadFailure.set(failure);
          firstAllocated.countDown();
        }
      });
      firstThread.start();
      firstAllocated.await();
      if (threadFailure.get() != null) {
        throw new AssertionError("First allocation failed", threadFailure.get());
      }

      LinkBagPointer secondPointer;
      try (var session = tracks.open(databaseName, "admin", "admin")) {
        var storage = (AbstractStorage) session.getStorage();
        secondPointer = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            operation -> session.getBTreeCollectionManager()
                .createBTree(secondCollectionId, operation, session));
      } finally {
        secondCommitted.countDown();
      }
      firstThread.join();
      if (threadFailure.get() != null) {
        throw new AssertionError("First allocation failed", threadFailure.get());
      }

      assertEquals(-1L, firstPointer.get().linkBagId());
      assertEquals(-2L, secondPointer.linkBagId());

      tracks.close();
      tracks = (YouTrackDBImpl) YourTracks.instance(directory);
      try (var session = tracks.open(databaseName, "admin", "admin")) {
        var storage = (AbstractStorage) session.getStorage();
        var next = storage.getAtomicOperationsManager().calculateInsideAtomicOperation(
            operation -> session.getBTreeCollectionManager()
                .createBTree(firstCollectionId, operation, session));
        assertEquals(-3L, next.linkBagId());
      }
    } finally {
      if (tracks != null) {
        tracks.close();
      }
      FileUtils.deleteRecursively(new File(directory));
    }
  }

  private static final class ExpectedRollbackException extends RuntimeException {
  }
}
