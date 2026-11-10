package com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DoubleWriteLogPrimitivesTest {

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  private Path directory() {
    return folder.getRoot().toPath();
  }

  private Path segment(long id) {
    return directory().resolve("test_" + id + ".dwl");
  }

  private DoubleWriteLog.AppendResult append(DoubleWriteLog log, int page) throws IOException {
    return log.write(new ArrayList<>(List.of(ByteBuffer.allocate(256))),
        IntArrayList.of(7), IntArrayList.of(page));
  }

  private void open(DoubleWriteLog log) throws IOException {
    log.open("primitives", "test", directory(), 256);
  }

  /** Real files prove cleanup is selective, never deletes active files, and obeys restore mode. */
  @Test
  public void selectiveCleanupKeepsOnlyRetainedOldAndActiveSegments() throws Exception {
    var log = new DoubleWriteLogGL(512, 512);
    open(log);
    try {
      for (int i = 0; i < 4; i++) {
        assertEquals(Long.valueOf(i), append(log, i).segment());
      }
      log.restoreModeOn();
      log.truncate(Set.of(0L, 2L));
      for (int i = 0; i < 4; i++) {
        assertTrue(Files.exists(segment(i)));
      }
      log.restoreModeOff();
      log.truncate(Set.of(0L, 2L, 3L));
      assertTrue(Files.exists(segment(0)));
      assertFalse(Files.exists(segment(1)));
      assertTrue(Files.exists(segment(2)));
      assertTrue(Files.exists(segment(3)));
      log.truncate(Set.of());
      assertFalse(Files.exists(segment(0)));
      assertFalse(Files.exists(segment(2)));
      assertTrue(Files.exists(segment(3)));
    } finally {
      log.close();
    }
  }

  /** Retained tails do not trigger repeated sync. Only successful covered sync clears a request. */
  @Test
  public void synchronizationRetryAndConcurrentRotationCoverage() throws Exception {
    var log = new DoubleWriteLogGL(Long.MAX_VALUE, 512);
    open(log);
    try {
      assertFalse(append(log, 0).syncRequired());
      log.startCheckpoint();
      assertFalse(append(log, 1).syncRequired());
      log.endCheckpoint();
      var request = append(log, 2);
      assertTrue(request.syncRequired());
      // Without an acknowledgement the request remains pending.
      var retry = append(log, 3);
      assertTrue(retry.syncRequired());
      log.acknowledgeSync(retry.syncToken());
      log.truncate(Set.of(0L));
      assertTrue(Files.exists(segment(0)));
      assertFalse(append(log, 4).syncRequired());
      log.startCheckpoint();
      log.endCheckpoint();
      // A token from before checkpoint rotation must not acknowledge the newer rotation.
      log.acknowledgeSync(request.syncToken());
      var newer = append(log, 5);
      assertTrue(newer.syncRequired());
      log.acknowledgeSync(newer.syncToken());
      log.truncate(Set.of(0L));
      assertFalse(append(log, 6).syncRequired());
    } finally {
      log.close();
    }
  }

  /** Candidate entry is forced while empty. A force failure keeps the old file writable. */
  @Test
  public void failedRotationForceContinuesAppendAndRetriesBeforeSwitch() throws Exception {
    Assume.assumeFalse("Directory forcing is unavailable on Windows", IOUtils.isOsWindows());
    var forces = new AtomicInteger();
    var log = new DoubleWriteLogGL(512, 512, true, directory -> {
      int attempt = forces.incrementAndGet();
      if (attempt == 1) {
        assertEquals(0, Files.size(segment(0)));
      } else {
        assertEquals(0, Files.size(segment(1)));
        assertTrue(Files.size(segment(0)) >= 512);
      }
      if (attempt == 2 || attempt == 3) {
        throw new IOException("injected directory force failure");
      }
      com.jetbrains.youtrackdb.internal.common.io.FileUtils.forceDirectory(directory);
    });
    open(log);
    try {
      var first = append(log, 0);
      assertEquals(Long.valueOf(0), first.segment());
      var failed = append(log, 1);
      assertEquals(Long.valueOf(0), failed.segment());
      assertFalse(failed.syncRequired());
      assertEquals(1024, Files.size(segment(0)));
      log.truncate();
      assertTrue(Files.exists(segment(0)));
      assertEquals(Long.valueOf(0), append(log, 2).segment());
      var success = append(log, 3);
      assertEquals(Long.valueOf(1), success.segment());
      assertTrue(success.syncRequired());
      assertEquals(4, forces.get());
      assertEquals(1536, Files.size(segment(0)));
      assertEquals(512, Files.size(segment(1)));
    } finally {
      log.close();
    }
  }

  /** First entry force failure propagates from open before any page can be appended. */
  @Test
  public void firstForceFailureFailsOpen() throws Exception {
    Assume.assumeFalse("Directory forcing is unavailable on Windows", IOUtils.isOsWindows());
    var forces = new AtomicInteger();
    var log = new DoubleWriteLogGL(512, 512, true, directory -> {
      forces.incrementAndGet();
      assertEquals(0, Files.size(segment(0)));
      throw new IOException("injected first force failure");
    });
    try {
      assertThrows(IOException.class, () -> open(log));
      assertEquals(1, forces.get());
      assertEquals(0, Files.size(segment(0)));
    } finally {
      log.closeAfterFailedOpen();
    }
    assertTrue(Files.exists(segment(0)));
  }

  /** A checkpoint rotation holds the append mutex through force and switch, including identity. */
  @Test
  public void appendIdentityIsAtomicAgainstCheckpointRotation() throws Exception {
    Assume.assumeFalse("Directory forcing is unavailable on Windows", IOUtils.isOsWindows());
    var forcing = new CountDownLatch(1);
    var allowForce = new CountDownLatch(1);
    var writerStarted = new CountDownLatch(1);
    var writerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var forces = new AtomicInteger();
    var log = new DoubleWriteLogGL(Long.MAX_VALUE, 512, true, directory -> {
      if (forces.incrementAndGet() == 2) {
        assertEquals(0, Files.size(segment(1)));
        forcing.countDown();
        try {
          if (!allowForce.await(10, TimeUnit.SECONDS)) {
            throw new IOException("timed out waiting for force release");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
      }
      com.jetbrains.youtrackdb.internal.common.io.FileUtils.forceDirectory(directory);
    });
    var executor = Executors.newFixedThreadPool(2);
    open(log);
    try {
      var before = append(log, 0);
      var rotation = executor.submit(() -> {
        log.startCheckpoint();
        return null;
      });
      assertTrue(forcing.await(10, TimeUnit.SECONDS));
      var writing = executor.submit(() -> {
        writerThread.set(Thread.currentThread());
        writerStarted.countDown();
        return append(log, 1);
      });
      assertTrue(writerStarted.await(10, TimeUnit.SECONDS));
      // Prove the writer actually reaches the held mutex before releasing rotation.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (writerThread.get().getState() != Thread.State.BLOCKED
          && !writing.isDone() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(Thread.State.BLOCKED, writerThread.get().getState());
      assertFalse(writing.isDone());
      allowForce.countDown();
      rotation.get(10, TimeUnit.SECONDS);
      var after = writing.get(10, TimeUnit.SECONDS);
      log.endCheckpoint();
      assertEquals(Long.valueOf(0), before.segment());
      assertEquals(Long.valueOf(1), after.segment());
      assertEquals(512, Files.size(segment(0)));
      assertEquals(512, Files.size(segment(1)));
    } finally {
      allowForce.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      log.close();
    }
  }

  /** Cleanup of a rotation after append clears the request even with an older sync token. */
  @Test
  public void cleanupOfConcurrentRotationDoesNotRequestAnotherSync() throws Exception {
    var log = new DoubleWriteLogGL(Long.MAX_VALUE, 512);
    open(log);
    try {
      append(log, 0);
      log.startCheckpoint();
      log.endCheckpoint();
      var request = append(log, 1);
      assertTrue(request.syncRequired());
      log.startCheckpoint();
      log.endCheckpoint();
      log.acknowledgeSync(request.syncToken());
      log.truncate(Set.of());
      assertFalse(Files.exists(segment(0)));
      assertFalse(Files.exists(segment(1)));
      assertFalse(append(log, 2).syncRequired());
    } finally {
      log.close();
    }
  }

  /** Restore between append and sync suppresses both ack and cleanup, then retries cleanup. */
  @Test
  public void restoreDuringSynchronizationKeepsCleanupRequestPending() throws Exception {
    assertRestoreKeepsCleanupPending(false);
  }

  /** Restore after acknowledgement but before cleanup must also leave the request pending. */
  @Test
  public void restoreAfterAcknowledgementKeepsCleanupRequestPending() throws Exception {
    assertRestoreKeepsCleanupPending(true);
  }

  private void assertRestoreKeepsCleanupPending(boolean restoreAfterAcknowledgement)
      throws Exception {
    var log = new DoubleWriteLogGL(Long.MAX_VALUE, 512);
    open(log);
    try {
      append(log, 0);
      log.startCheckpoint();
      log.endCheckpoint();
      var request = append(log, 1);
      assertTrue(request.syncRequired());
      if (restoreAfterAcknowledgement) {
        log.acknowledgeSync(request.syncToken());
        log.restoreModeOn();
      } else {
        log.restoreModeOn();
        log.acknowledgeSync(request.syncToken());
      }
      log.truncate(Set.of());
      assertTrue(Files.exists(segment(0)));
      log.restoreModeOff();
      var retry = append(log, 2);
      assertTrue(retry.syncRequired());
      log.acknowledgeSync(retry.syncToken());
      log.truncate(Set.of());
      assertFalse(Files.exists(segment(0)));
      assertFalse(append(log, 3).syncRequired());
    } finally {
      log.close();
    }
  }

  /** Windows open and rotation succeed without invoking the unsupported directory force. */
  @Test
  public void windowsSkipsDirectoryForceAtOpenAndRotation() throws Exception {
    var log = new DoubleWriteLogGL(512, 512, true, directory -> {
      throw new AssertionError("Windows must not force a directory");
    }, true);
    open(log);
    try {
      assertEquals(Long.valueOf(0), append(log, 0).segment());
      assertEquals(Long.valueOf(1), append(log, 1).segment());
      assertEquals(512, Files.size(segment(0)));
      assertEquals(512, Files.size(segment(1)));
    } finally {
      log.close();
    }
  }

  /** A rotation after append unlock must not change the already captured physical identity. */
  @Test
  public void appendFirstIdentitySurvivesRotationBeforeReturn() throws Exception {
    var appended = new CountDownLatch(1);
    var allowReturn = new CountDownLatch(1);
    var log = new DoubleWriteLogGL(Long.MAX_VALUE, 512) {
      @Override
      void afterAppend() {
        appended.countDown();
        try {
          assertTrue(allowReturn.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
      }
    };
    var executor = Executors.newSingleThreadExecutor();
    open(log);
    try {
      var writing = executor.submit(() -> append(log, 0));
      assertTrue(appended.await(10, TimeUnit.SECONDS));
      assertEquals(512, Files.size(segment(0)));
      log.startCheckpoint();
      log.endCheckpoint();
      assertEquals(0, Files.size(segment(1)));
      allowReturn.countDown();
      var result = writing.get(10, TimeUnit.SECONDS);
      assertEquals(Long.valueOf(0), result.segment());
      assertEquals(0, result.syncToken());
      assertFalse(result.syncRequired());
    } finally {
      allowReturn.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      log.close();
    }
  }

  /** A no-op append has no physical identity and never requests synchronization. */
  @Test
  public void noOpHasNoRetentionIdentity() throws Exception {
    var log = new DoubleWriteLogNoOP();
    var result = append(log, 0);
    assertNull(result.segment());
    assertFalse(result.syncRequired());
    log.acknowledgeSync(result.syncToken());
    log.truncate(Set.of(0L));
  }
}
