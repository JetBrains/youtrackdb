package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationBinaryTracking.PageApplyHook;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@Category(SequentialTest.class)
@RunWith(Parameterized.class)
public class PreparedPublicationTest {

  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> engines() {
    return List.of(new Object[] {DatabaseType.DISK}, new Object[] {DatabaseType.MEMORY});
  }

  @Parameterized.Parameter
  public DatabaseType engine;

  private YouTrackDBImpl db;
  private AbstractStorage storage;
  private AtomicOperationsManager manager;
  private StorageComponent domain;
  private ApplyPhaseEpoch epoch;
  private Path directory;

  @Before
  public void setUp() throws Exception {
    directory = Files.createTempDirectory("prepared-publication-");
    db = (YouTrackDBImpl) YourTracks.instance(directory.toString());
    db.create("publication", engine, "admin", "admin", "admin");
    try (var session = db.open("publication", "admin", "admin")) {
      storage = session.getStorage();
    }
    manager = storage.getAtomicOperationsManager();
    epoch = spy(manager.getApplyPhaseEpoch("publication"));
    domain = mock(StorageComponent.class);
    when(domain.getLockName()).thenReturn("publication");
    when(domain.getApplyPhaseEpoch()).thenReturn(epoch);
  }

  @After
  public void tearDown() throws Exception {
    db.drop("publication");
    db.close();
    FileUtils.deleteRecursively(directory.toFile());
  }

  @Test
  public void copyOnlyCommitPublishesFinalValueOnceInsideEpochAndInvalidatesEmptyRead()
      throws Exception {
    var published = spy(new AtomicReference<>("old"));
    doAnswer(call -> {
      assertEquals(epoch.exitSeq() + 1, epoch.enterSeq());
      return call.callRealMethod();
    }).when(published).set(any());
    var attempt = new OptimisticReadScope(epoch);
    attempt.reset();
    var exits = new AtomicInteger();
    doAnswer(call -> {
      assertEquals("final", published.get());
      assertEquals(epoch.exitSeq() + 1, epoch.enterSeq());
      exits.incrementAndGet();
      return call.callRealMethod();
    }).when(epoch).exitApplyPhase();
    manager.executeInsideAtomicOperation(op -> {
      op.preparePublication(published, "intermediate", domain, domain);
      op.preparePublication(published, "final", domain);
      assertEquals("old", published.get());
    });
    assertThrows(OptimisticReadFailedException.class, attempt::validateOrThrow);
    assertEquals(1, exits.get());
    verify(published, times(1)).set(any());
    verify(published).set("final");
    verify(published, never()).set("intermediate");
    attempt.reset();
    assertEquals("final", published.get());
    attempt.validateOrThrow();
  }

  @Test
  public void publicationWaitsForEveryPageAndInvalidatesOverlappingStateOnlyAttempt()
      throws Exception {
    var published = new AtomicReference<>("old");
    var paused = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var applied = new AtomicInteger();
    var executor = Executors.newSingleThreadExecutor();
    try {
      var writer = executor.submit(() -> {
        manager.executeInsideAtomicOperation(op -> {
          op.preparePublication(published, "created", domain);
          long file = op.addFile("publication-pages", true);
          for (int i = 0; i < 2; i++) {
            var page = op.allocatePageForWrite(file, i);
            page.getChanges().setByteValue(null, (byte) 42, 100);
            op.releasePageFromWrite(page);
          }
          ((AtomicOperationBinaryTracking) op).setPageApplyHook(new PageApplyHook() {
            @Override
            public void beforePageApply(long fileId, long pageIndex) {
              assertEquals("old", published.get());
              if (applied.incrementAndGet() == 2) {
                paused.countDown();
                try {
                  assertTrue(resume.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  throw new AssertionError(e);
                }
              }
            }
          });
        });
        return null;
      });
      assertTrue(paused.await(30, TimeUnit.SECONDS));
      var overlap = new OptimisticReadScope(epoch);
      overlap.reset();
      assertEquals("old", published.get());
      assertThrows(OptimisticReadFailedException.class, overlap::validateOrThrow);
      resume.countDown();
      writer.get(30, TimeUnit.SECONDS);
      assertEquals(2, applied.get());
      overlap.reset();
      assertEquals("created", published.get());
      long file = storage.getWriteCache().fileIdByName("publication-pages");
      for (int i = 0; i < 2; i++) {
        var page = storage.getReadCache().loadForRead(file, i, storage.getWriteCache(), true);
        try {
          assertEquals(42, page.getCachePointer().getBuffer().get(100));
        } finally {
          storage.getReadCache().releaseFromRead(page);
        }
      }
      overlap.validateOrThrow();
    } finally {
      resume.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  public void rollbackAndDeactivationDiscardPreparedStores() throws Exception {
    var published = new AtomicReference<>("old");
    var op = manager.startAtomicOperation();
    manager.startToApplyOperations(op);
    op.preparePublication(published, "discarded", domain);
    manager.endAtomicOperation(op, new IllegalStateException("rollback"));
    assertEquals("old", published.get());
    assertEquals(0, epoch.enterSeq());
    assertThrows(RuntimeException.class,
        () -> op.preparePublication(published, "late", domain));
    var inactive = manager.startAtomicOperation();
    inactive.preparePublication(published, "inactive", domain);
    inactive.deactivate();
    assertThrows(RuntimeException.class,
        () -> inactive.preparePublication(published, "late", domain));
    assertEquals("old", published.get());
  }

  @Test
  public void registeredAndLockedDomainsEnterInNameOrderWithoutDuplicateEntries() throws Exception {
    var earlier = mock(StorageComponent.class);
    when(earlier.getLockName()).thenReturn("earlier");
    var earlierEpoch = spy(manager.getApplyPhaseEpoch("earlier"));
    when(earlier.getApplyPhaseEpoch()).thenReturn(earlierEpoch);
    doAnswer(call -> {
      assertEquals(1, earlierEpoch.enterSeq());
      return call.callRealMethod();
    }).when(epoch).enterApplyPhase();
    var lockedAttempt = new OptimisticReadScope(epoch);
    lockedAttempt.reset();
    var first = spy(new AtomicReference<>("old"));
    doAnswer(call -> {
      assertEquals(earlierEpoch.exitSeq() + 1, earlierEpoch.enterSeq());
      assertEquals(epoch.exitSeq() + 1, epoch.enterSeq());
      return call.callRealMethod();
    }).when(first).set(any());
    var second = new AtomicReference<>("old");
    manager.executeInsideAtomicOperation(op -> {
      // The locked-only domain must join the registered-only domain even without page changes.
      op.addLockedComponent(domain);
      op.preparePublication(first, "first", earlier, earlier);
      op.preparePublication(second, "second", earlier);
    });
    assertThrows(OptimisticReadFailedException.class, lockedAttempt::validateOrThrow);
    assertEquals("first", first.get());
    assertEquals("second", second.get());
    assertEquals(1, earlierEpoch.enterSeq());
    assertEquals(1, earlierEpoch.exitSeq());
    assertEquals(1, epoch.enterSeq());
    assertEquals(1, epoch.exitSeq());
  }

  @Test
  public void commitPlanResolutionFailureDiscardsPublicationBeforeWalEndRecord() throws Exception {
    var published = spy(new AtomicReference<>("old"));
    var brokenDomain = mock(StorageComponent.class);
    when(brokenDomain.getLockName()).thenReturn("broken");
    var failure = new IllegalStateException("commit-plan resolution failed");
    when(brokenDomain.getApplyPhaseEpoch()).thenThrow(failure);
    var op = manager.startAtomicOperation();
    manager.startToApplyOperations(op);
    op.preparePublication(published, "discarded", domain);
    op.addLockedComponent(brokenDomain);
    // A durable file creation forces a WAL end record if resolution moves past the WAL phase.
    op.addFile("preparation-failure", false);
    var wal = storage.getWALInstance();
    var before = wal.end();
    assertSame(failure, assertThrows(IllegalStateException.class,
        () -> manager.endAtomicOperation(op, null)));
    verify(brokenDomain).getApplyPhaseEpoch();
    verify(published, never()).set(any());
    assertEquals("old", published.get());
    assertEquals(0, epoch.enterSeq());
    assertThrows(RuntimeException.class,
        () -> op.preparePublication(published, "late", domain));
    if (engine == DatabaseType.DISK) {
      // An unchanged WAL tail excludes every new record, including the atomic-unit end record.
      assertEquals(before, wal.end());
    }
  }

  @Test
  public void invalidPreparationFailsBeforeCommitAndKeepsPriorValue() throws Exception {
    var published = new AtomicReference<>("old");
    var op = manager.startAtomicOperation();
    manager.startToApplyOperations(op);
    var failure = assertThrows(IllegalArgumentException.class,
        () -> op.preparePublication(published, "invalid"));
    manager.endAtomicOperation(op, failure);
    assertEquals("old", published.get());
    assertEquals(0, epoch.enterSeq());
  }
}
