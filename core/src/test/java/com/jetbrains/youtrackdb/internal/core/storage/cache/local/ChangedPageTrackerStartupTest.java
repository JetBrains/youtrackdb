package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.CRC32C;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ChangedPageTrackerStartupTest {

  private static final UUID TRACKER = new UUID(1, 2);
  private static final UUID COMPLETED = new UUID(3, 4);
  private static final UUID SEALED = new UUID(5, 6);
  private static final LogSequenceNumber COVERAGE = new LogSequenceNumber(17, 32);
  private static final Map<Integer, TreeSet<Long>> ACTIVE = Map.of(
      1, new TreeSet<>(List.of(0L, 63L)), 7, new TreeSet<>(List.of(32768L)));
  private static final Map<Integer, TreeSet<Long>> MERGED = Map.of(
      1, new TreeSet<>(List.of(0L, 1L, 63L)), 7, new TreeSet<>(List.of(32768L)),
      9, new TreeSet<>(List.of(65L)));

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  // Both trust states and both generation layouts retain exact marks, continuity and coverage.
  // Pending trust is hidden from both the direct trust check and backup/save capture.
  @Test
  public void loadedHistoryPreservesFullStateAndKeepResolutionNeverUpgradesTrust()
      throws Exception {
    for (boolean trusted : new boolean[] {false, true}) {
      for (boolean sealed : new boolean[] {false, true}) {
        var path = sideFile();
        Files.write(path, encode(trusted, sealed));
        var pending = ChangedPageTrackerStartup.load(path);
        assertEquals(ChangedPageTrackerStartup.Resolution.PENDING, pending.resolution());
        assertEquals(trusted, pending.savedTrusted());
        assertEquals(COVERAGE, pending.coverageLsn());
        assertFalse(pending.tracker().isTrusted());
        assertLoaded(pending.tracker(), sealed, false);
        pending.keepSavedTrust();
        assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, pending.resolution());
        assertEquals(trusted, pending.tracker().isTrusted());
        assertLoaded(pending.tracker(), sealed, trusted);
      }
    }
  }

  // Failed verification retains conservative marks and durable coverage, but schedules an identity
  // reset and removes completed continuity from capture. A later keep cannot undo invalidation.
  @Test
  public void distrustResolutionInvalidatesWithoutLosingMarksOrCoverage() throws Exception {
    for (boolean trusted : new boolean[] {false, true}) {
      var path = sideFile();
      Files.write(path, encode(trusted, true));
      var pending = ChangedPageTrackerStartup.load(path);
      pending.distrust();
      assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
      pending.keepSavedTrust();
      assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
      assertFalse(pending.tracker().isTrusted());
      var state = capture(pending.tracker());
      assertFalse(state.trusted());
      assertTrue(state.resetsTracker());
      assertEquals(1, state.failureVersion());
      assertNotEquals(TRACKER, state.trackerIdentifier());
      assertNull(state.lastCompletedIdentifier());
      assertNull(state.sealed());
      assertEquals(MERGED, pages(state.active()));
      assertEquals(COVERAGE, pending.tracker().durableCoverageLsn());
      assertEquals(COVERAGE, pending.coverageLsn());
      assertEquals(trusted, pending.savedTrusted());
    }
  }

  // Saved-trusted, saved-untrusted and absent histories expose resolution independently of trust.
  // Both resolution orders are safe. Distrust wins, and repeated calls do not invalidate again.
  @Test
  public void resolutionStateIsExplicitAndDistrustAlwaysWins() throws Exception {
    for (int history = 0; history < 3; history++) {
      for (boolean keepFirst : new boolean[] {false, true}) {
        var path = sideFile();
        if (history != 0) {
          Files.write(path, encode(history == 2, false));
        }
        var pending = ChangedPageTrackerStartup.load(path);
        assertEquals(ChangedPageTrackerStartup.Resolution.PENDING, pending.resolution());
        assertEquals(history == 2, pending.savedTrusted());
        assertFalse(pending.tracker().isTrusted());
        if (keepFirst) {
          pending.keepSavedTrust();
          assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, pending.resolution());
          pending.keepSavedTrust();
          assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, pending.resolution());
          assertEquals(history == 2, capture(pending.tracker()).trusted());
        }
        pending.distrust();
        assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
        pending.distrust();
        pending.keepSavedTrust();
        assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
        assertFalse(pending.tracker().isTrusted());
        var state = capture(pending.tracker());
        assertFalse(state.trusted());
        assertEquals(1, state.failureVersion());
        assertTrue(state.resetsTracker());
      }
    }
  }

  // A save during pending verification stores untrusted history. Keeping saved trust dirties the
  // tracker at the same coverage, so the next save publishes resolved trust. A repeated keep is clean.
  @Test
  public void keepingSavedTrustAfterPendingSaveRequiresAnotherSave() throws Exception {
    for (boolean trusted : new boolean[] {false, true}) {
      var path = sideFile();
      Files.write(path, encode(trusted, false));
      var pending = ChangedPageTrackerStartup.load(path);
      var tracker = pending.tracker();
      tracker.saveOrderLock().lock();
      try {
        assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
            ChangedPageTrackerFile.save(path, tracker, COVERAGE,
                new ChangedPageTrackerFile.FileOperations()));
        assertFalse(ChangedPageTrackerFile.load(path).tracker().isTrusted());
        assertFalse(tracker.hasUnsavedChanges(COVERAGE));
        pending.keepSavedTrust();
        assertTrue(tracker.hasUnsavedChanges(COVERAGE));
        assertEquals(trusted, tracker.capture().trusted());
        assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
            ChangedPageTrackerFile.save(path, tracker, COVERAGE,
                new ChangedPageTrackerFile.FileOperations()));
        assertEquals(trusted, ChangedPageTrackerFile.load(path).tracker().isTrusted());
        assertFalse(tracker.hasUnsavedChanges(COVERAGE));
        pending.keepSavedTrust();
        assertFalse(tracker.hasUnsavedChanges(COVERAGE));
      } finally {
        tracker.saveOrderLock().unlock();
      }
    }
  }

  // A completed backup during pending verification must not establish continuity. The lifecycle
  // excludes this call, but the tracker also keeps the trust gate closed on this selection path.
  @Test
  public void pendingRetirementCannotEstablishTrust() throws Exception {
    var path = sideFile();
    Files.write(path, encode(true, false));
    var pending = ChangedPageTrackerStartup.load(path);
    pending.tracker().retire(pending.tracker().beginBackup());
    pending.keepSavedTrust();
    var state = capture(pending.tracker());
    assertFalse(pending.tracker().isTrusted());
    assertFalse(state.trusted());
    assertFalse(state.resetsTracker());
    assertEquals(0, state.failureVersion());
    assertEquals(TRACKER, state.trackerIdentifier());
    assertNull(state.lastCompletedIdentifier());
    assertNull(state.sealed());
    assertEquals(Map.of(), pages(state.active()));
    assertEquals(COVERAGE, pending.tracker().durableCoverageLsn());
  }

  // Missing, empty, truncated, bad-checksum and bad-version content all start empty untrusted
  // history. A valid crash-left temporary sibling is never substituted for the primary file.
  @Test
  public void absentAndMalformedFilesGiveFreshUntrustedHistory() throws Exception {
    var valid = encode(true, true);
    var checksum = valid.clone();
    checksum[checksum.length - 1] ^= 1;
    var version = valid.clone();
    version[7] = 2;
    repairChecksum(version);
    for (byte[] content : new byte[][] {null, new byte[0],
        Arrays.copyOf(valid, valid.length - 1), checksum, version}) {
      var path = sideFile();
      Files.write(ChangedPageTrackerFile.temporaryFile(path), valid);
      if (content != null) {
        Files.write(path, content);
      }
      assertFresh(ChangedPageTrackerStartup.load(path));
    }
    var temporary = ChangedPageTrackerFile.temporaryFile(sideFile());
    Files.write(temporary, valid);
    assertFresh(ChangedPageTrackerStartup.load(temporary));
  }

  // A directory at the file path is a portable real filesystem I/O failure, not malformed bytes.
  // The facade logs the path and failure before returning fresh untrusted history.
  @Test
  public void filesystemFailureIsLoggedAndGivesFreshUntrustedHistory() throws Exception {
    var directory = folder.newFolder().toPath();
    try (var logs = LogRecordCollector.attachTo(ChangedPageTrackerStartup.class)) {
      assertFresh(ChangedPageTrackerStartup.load(directory));
      assertTrue(logs.messages().stream()
          .anyMatch(message -> message.startsWith("WARNING ") && message.contains("Failed to load")
              && message.contains(directory.toString())));
    }
  }

  // The facade must preserve a codec Error, rather than swallowing it as optional history loss.
  @Test
  public void codecErrorPropagatesUnchanged() throws Exception {
    var path = sideFile();
    var failure = new OutOfMemoryError("injected load failure");
    try (var codec = mockStatic(ChangedPageTrackerFile.class)) {
      codec.when(() -> ChangedPageTrackerFile.load(path)).thenThrow(failure);
      assertSame(failure, assertThrows(OutOfMemoryError.class,
          () -> ChangedPageTrackerStartup.load(path)));
    }
  }

  private Path sideFile() throws Exception {
    return folder.newFolder().toPath().resolve(ChangedPageTrackerFile.FILE_NAME);
  }

  private static byte[] encode(boolean trusted, boolean sealed) throws Exception {
    var builder =
        ChangedPageTracker.restoration(TRACKER, COMPLETED, trusted, sealed ? SEALED : null);
    builder.activeWord(1, 0, Long.MIN_VALUE | 1);
    builder.activeWord(7, 512, 1);
    if (sealed) {
      builder.sealedWord(1, 0, 3);
      builder.sealedWord(9, 1, 2);
    }
    var out = new ByteArrayOutputStream();
    ChangedPageTrackerFile.write(out, capture(builder.build()), COVERAGE);
    return out.toByteArray();
  }

  private static void assertLoaded(ChangedPageTracker tracker, boolean sealed, boolean trusted) {
    var state = capture(tracker);
    assertEquals(TRACKER, state.trackerIdentifier());
    assertEquals(COMPLETED, state.lastCompletedIdentifier());
    assertEquals(trusted, state.trusted());
    assertFalse(state.resetsTracker());
    assertEquals(0, state.failureVersion());
    assertNull(state.sealed());
    assertEquals(sealed ? MERGED : ACTIVE, pages(state.active()));
    assertEquals(COVERAGE, tracker.durableCoverageLsn());
  }

  private static void assertFresh(ChangedPageTrackerStartup.PendingHistory pending) {
    assertEquals(ChangedPageTrackerStartup.Resolution.PENDING, pending.resolution());
    assertNull(pending.coverageLsn());
    assertFalse(pending.savedTrusted());
    assertFalse(pending.tracker().isTrusted());
    pending.keepSavedTrust();
    assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, pending.resolution());
    var state = capture(pending.tracker());
    assertFalse(pending.tracker().isTrusted());
    assertFalse(state.trusted());
    assertTrue(state.resetsTracker());
    assertEquals(0, state.failureVersion());
    assertNotEquals(TRACKER, state.trackerIdentifier());
    assertNull(state.lastCompletedIdentifier());
    assertNull(state.sealed());
    assertEquals(Map.of(), pages(state.active()));
    assertNull(pending.tracker().durableCoverageLsn());
  }

  private static void repairChecksum(byte[] bytes) {
    var checksum = new CRC32C();
    checksum.update(bytes, 0, bytes.length - Integer.BYTES);
    ByteBuffer.wrap(bytes).putInt(bytes.length - Integer.BYTES, (int) checksum.getValue());
  }

  private static Map<Integer, TreeSet<Long>> pages(ChangedPageTracker.Generation generation) {
    var result = new TreeMap<Integer, TreeSet<Long>>();
    generation.forEachFile((file, bitmap) -> {
      var marks = new TreeSet<Long>();
      generation.forEachCandidate(file, marks::add);
      result.put(file, marks);
    });
    return result;
  }

  private static ChangedPageTracker.SaveState capture(ChangedPageTracker tracker) {
    tracker.saveOrderLock().lock();
    try {
      return tracker.capture();
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }
}
