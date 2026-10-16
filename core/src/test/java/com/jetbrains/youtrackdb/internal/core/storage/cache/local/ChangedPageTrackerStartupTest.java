package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartMetadataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileTruncatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.HighLevelTransactionChangeRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MemoryWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MetaDataRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperationRegistry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.UpdatePageRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALPageChangesPortion;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.ProofBatch;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.ProofStatus;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.RidbagEntryPointSetTreeSizeOp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.zip.CRC32C;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ChangedPageTrackerStartupTest {

  private static final long EXTERNAL_ID = (31L << 32) | 42;
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

  // The public internal lifecycle retains the same gated tracker, merges sealed candidates and
  // delegates proof inputs. Result trust is a snapshot. Live trust reflects later replay failure.
  @Test
  public void startupLifecyclePreservesPendingInputsAndReportsLiveReplayTrust() throws Exception {
    for (boolean trusted : new boolean[] {false, true}) {
      var path = sideFile();
      Files.write(path, encode(trusted, true));
      var pending = ChangedPageTrackerStartup.load(path);
      var selected = pending.tracker();
      assertSame(selected, pending.tracker());
      assertEquals(COVERAGE, pending.coverageLsn());
      assertEquals(trusted, pending.savedTrusted());
      assertFalse(selected.isTrusted());
      assertLoaded(selected, true, false);
      var page = new UpdatePageRecord(12, EXTERNAL_ID, 1, new WALPageChangesPortion(),
          COVERAGE);
      var files = new TestFileIds();
      try (var wal = new ScriptedWal(new ProofBatch(List.of(page), ProofStatus.REACHED_END,
          COVERAGE, false, null))) {
        var result = ChangedPageTrackerStartup.verify(pending, wal,
            ChangedPageTrackerStartup.ShutdownState.CLEAN, files);
        assertTrue(result.coverageProved());
        assertEquals(trusted, result.historyTrusted());
        assertEquals(trusted, selected.isTrusted());
        assertEquals(List.of(EXTERNAL_ID), files.converted);
        assertEquals(new TreeSet<>(List.of(12L, 32768L)), pages(capture(selected).active()).get(7));
        assertEquals(COMPLETED, capture(selected).lastCompletedIdentifier());
        assertEquals(1, wal.openCalls);
        ChangedPageTrackerStartup.replayFailed(pending);
        assertFalse(selected.isTrusted());
        assertEquals(trusted, result.historyTrusted());
        assertSame(selected, pending.tracker());
      }
    }
  }

  // Successful invalidation resolves history and clears the gate before delete and folder force.
  // It retains candidates and allows a later full backup to restore trust.
  @Test
  public void durableDistrustBeforeVerifyClearsGateOnSuccessAndAllowsFullBackupTrust()
      throws Exception {
    assertPendingBarrier(0);
  }

  // History and the gate are resolved before a failed delete. A full backup can restore trust.
  @Test
  public void durableDistrustBeforeVerifyClearsGateOnDeleteFailureAndAllowsFullBackupTrust()
      throws Exception {
    assertPendingBarrier(1);
  }

  // History and the gate are resolved before delete and a failed folder force.
  // Coverage and candidates remain available for a later full backup.
  @Test
  public void durableDistrustBeforeVerifyClearsGateOnForceFailureAndAllowsFullBackupTrust()
      throws Exception {
    assertPendingBarrier(2);
  }

  private void assertPendingBarrier(int stage) throws Exception {
    var path = sideFile();
    Files.write(path, encode(true, true));
    var pending = ChangedPageTrackerStartup.load(path);
    assertTrue(pending.savedTrusted());
    var resolutions = new ArrayList<ChangedPageTrackerStartup.Resolution>();
    var clearedGates = new ArrayList<Boolean>();
    var files = invalidationOperations(stage, new IOException("injected barrier failure"),
        () -> observeDistrustBeforeFileWork(pending, resolutions, clearedGates));
    if (stage == 0) {
      ChangedPageTrackerStartup.durableDistrust(path, pending, files);
    } else {
      assertThrows(IOException.class,
          () -> ChangedPageTrackerStartup.durableDistrust(path, pending, files));
    }
    assertDistrustObservations(stage == 1 ? 1 : 2, resolutions, clearedGates);
    assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
    assertFalse(pending.tracker().isTrusted());
    assertMarksAndCoverage(pending);
    // The package-private setter must be a no-op when the pending gate is already clear.
    long mutation = capture(pending.tracker()).mutationVersion();
    pending.tracker().startupVerificationPending(false);
    assertEquals(mutation, capture(pending.tracker()).mutationVersion());
    assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
        ChangedPageTrackerFile.save(path, pending.tracker(), COVERAGE));
    assertFalse(pending.tracker().isTrusted());
    pending.tracker().retire(pending.tracker().beginBackup());
    assertTrue(pending.tracker().isTrusted());
  }

  // The checked boundary retains the exact delete IOException as its cause.
  @Test
  public void durableDistrustChainsExactDeleteIOException() throws Exception {
    assertBarrierCause(1, new IOException("delete failed"));
  }

  // Losing the directory durability barrier retains the exact force IOException as its cause.
  @Test
  public void durableDistrustChainsExactFolderForceIOException() throws Exception {
    assertBarrierCause(2, new IOException("folder force failed"));
  }

  // A security rejection uses the checked startup boundary without replacing the root failure.
  @Test
  public void durableDistrustChainsExactDeleteSecurityException() throws Exception {
    assertBarrierCause(1, new SecurityException("delete denied"));
  }

  private void assertBarrierCause(int stage, Exception failure) throws Exception {
    var path = sideFile();
    Files.write(path, encode(true, true));
    var pending = ChangedPageTrackerStartup.load(path);
    var thrown = assertThrows(IOException.class, () -> ChangedPageTrackerStartup.durableDistrust(
        path, pending, failingInvalidation(stage, failure)));
    assertSame(failure, thrown.getCause());
    assertTrue(thrown.getMessage().contains(path.toString()));
  }

  private static void observeDistrustBeforeFileWork(
      ChangedPageTrackerStartup.PendingHistory pending,
      List<ChangedPageTrackerStartup.Resolution> resolutions, List<Boolean> clearedGates) {
    // Record instead of asserting inside I/O, where an AssertionError can trigger a cleanup retry.
    resolutions.add(pending.resolution());
    long mutation = capture(pending.tracker()).mutationVersion();
    pending.tracker().startupVerificationPending(false);
    clearedGates.add(mutation == capture(pending.tracker()).mutationVersion());
  }

  private static void assertDistrustObservations(int callbacks,
      List<ChangedPageTrackerStartup.Resolution> resolutions, List<Boolean> clearedGates) {
    // Exact counts also reject a stub that never observes a reachable file callback.
    assertEquals(Collections.nCopies(callbacks, ChangedPageTrackerStartup.Resolution.DISTRUSTED),
        resolutions);
    assertEquals(Collections.nCopies(callbacks, true), clearedGates);
  }

  private static ChangedPageTrackerFile.FileOperations failingInvalidation(int stage,
      Exception failure) {
    return invalidationOperations(stage, failure, () -> {
    });
  }

  private static ChangedPageTrackerFile.FileOperations invalidationOperations(int stage,
      Exception failure, Runnable beforeFileWork) {
    return new ChangedPageTrackerFile.FileOperations() {
      @Override
      boolean windows() {
        return false;
      }

      @Override
      void delete(Path target) throws IOException {
        beforeFileWork.run();
        if (stage == 1) {
          fail();
        }
        super.delete(target);
      }

      @Override
      void forceFolder(Path target) throws IOException {
        beforeFileWork.run();
        if (stage == 2) {
          fail();
        }
        super.forceFolder(target);
      }

      private void fail() throws IOException {
        if (failure instanceof IOException checked) {
          throw checked;
        }
        throw (RuntimeException) failure;
      }
    };
  }

  // The throwing barrier removes authority and revokes live trust. Failed I/O must throw instead
  // of returning successful fallback. Every delete observes resolved distrust and a cleared gate.
  // An invalidation Error must reach the owner by identity.
  @Test
  public void durableStartupDistrustThrowsOnFailedBarrierAndPreservesErrorIdentity()
      throws Exception {
    var path = sideFile();
    Files.write(path, encode(true, true));
    var pending = ChangedPageTrackerStartup.load(path);
    pending.keepSavedTrust();
    ChangedPageTrackerStartup.durableDistrust(path, pending);
    assertFalse(pending.tracker().isTrusted());
    assertTrue(!Files.exists(path) || Files.size(path) == 0);
    var resolutions = new ArrayList<ChangedPageTrackerStartup.Resolution>();
    var clearedGates = new ArrayList<Boolean>();
    for (Throwable failure : List.of(new IOException("injected invalidation failure"),
        new OutOfMemoryError("injected invalidation error"))) {
      Files.write(path, encode(true, true));
      var history = ChangedPageTrackerStartup.load(path);
      history.keepSavedTrust();
      int[] deletes = {0};
      var files = new ChangedPageTrackerFile.FileOperations() {
        @Override
        boolean windows() {
          return false;
        }

        @Override
        void delete(Path target) throws IOException {
          observeDistrustBeforeFileWork(history, resolutions, clearedGates);
          assertEquals(path.toAbsolutePath(), target);
          deletes[0]++;
          if (failure instanceof Error error) {
            throw error;
          }
          throw (IOException) failure;
        }
      };
      var thrown = assertThrows(failure.getClass(),
          () -> ChangedPageTrackerStartup.durableDistrust(path, history, files));
      if (failure instanceof Error) {
        assertSame(failure, thrown);
      } else {
        assertTrue(thrown.getMessage().contains(path.toString()));
      }
      assertEquals(failure instanceof Error ? 2 : 1, deletes[0]);
      assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, history.resolution());
      assertFalse(history.tracker().isTrusted());
      assertEquals(MERGED, pages(capture(history.tracker()).active()));
      assertTrue(Files.size(path) > 0);
    }
    // Both failure paths and the Error retry must reach their file callbacks before this check.
    assertDistrustObservations(3, resolutions, clearedGates);
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

  // Every status is tested under both shutdown states, a null shutdown and both lookahead results.
  // Null inputs and unknown statuses fail closed, including MORE with a null shutdown. With a known
  // shutdown, an empty MORE batch continues and only an unclean broken tail may prove.
  @Test
  public void terminalPolicyExhaustivelyChecksStatusShutdownAndLookahead() {
    for (var status : ProofStatus.values()) {
      for (var shutdown : new ChangedPageTrackerStartup.ShutdownState[] {
          ChangedPageTrackerStartup.ShutdownState.CLEAN,
          ChangedPageTrackerStartup.ShutdownState.UNCLEAN, null}) {
        for (boolean follows : new boolean[] {false, true}) {
          var expected = shutdown == null ? ChangedPageTrackerStartup.ProofDecision.NOT_PROVED
              : switch (status) {
                case MORE -> ChangedPageTrackerStartup.ProofDecision.CONTINUE;
                case REACHED_END -> ChangedPageTrackerStartup.ProofDecision.PROVED;
                case BROKEN_PAGE -> shutdown == ChangedPageTrackerStartup.ShutdownState.UNCLEAN
                    && !follows ? ChangedPageTrackerStartup.ProofDecision.PROVED
                        : ChangedPageTrackerStartup.ProofDecision.NOT_PROVED;
                default -> ChangedPageTrackerStartup.ProofDecision.NOT_PROVED;
              };
          assertEquals(status + " / " + shutdown + " / " + follows, expected,
              ChangedPageTrackerStartup.terminalDecision(
                  new ProofBatch(List.of(), status, COVERAGE, follows, null), shutdown));
        }
        assertEquals(ChangedPageTrackerStartup.ProofDecision.NOT_PROVED,
            ChangedPageTrackerStartup.terminalDecision(null, shutdown));
        assertEquals(ChangedPageTrackerStartup.ProofDecision.NOT_PROVED,
            ChangedPageTrackerStartup.terminalDecision(
                new ProofBatch(List.of(), null, COVERAGE, false, null), shutdown));
      }
    }
  }

  // A missing shutdown state cannot establish the unclean-shutdown exception for a broken page.
  // Both lookahead values fail closed rather than admitting a broken tail.
  @Test
  public void brokenPageWithNullShutdownStateNeverProvesCoverage() {
    for (boolean follows : new boolean[] {false, true}) {
      assertEquals(ChangedPageTrackerStartup.ProofDecision.NOT_PROVED,
          ChangedPageTrackerStartup.terminalDecision(
              new ProofBatch(List.of(), ProofStatus.BROKEN_PAGE, COVERAGE, follows, null), null));
    }
  }

  // Every outcome is checked against all four coverage/trust combinations. Only consistent
  // combinations are accepted, and accepted results preserve the exact diagnostic fields.
  @Test
  public void startupResultRequiresFlagsToAgreeWithEveryOutcome() {
    var cause = new IOException("diagnostic cause");
    for (var outcome : ChangedPageTrackerStartup.Outcome.values()) {
      for (boolean proved : new boolean[] {false, true}) {
        for (boolean trusted : new boolean[] {false, true}) {
          boolean valid = switch (outcome) {
            case PROVED_HISTORY_TRUSTED -> proved && trusted;
            case PROVED_HISTORY_UNTRUSTED -> proved && !trusted;
            default -> !proved && !trusted;
          };
          if (!valid) {
            assertThrows(outcome + " / " + proved + " / " + trusted,
                IllegalArgumentException.class,
                () -> new ChangedPageTrackerStartup.StartupResult(proved, trusted, outcome,
                    ProofStatus.IO_ERROR, cause));
          } else {
            var result = new ChangedPageTrackerStartup.StartupResult(proved, trusted, outcome,
                ProofStatus.IO_ERROR, cause);
            assertEquals(proved, result.coverageProved());
            assertEquals(trusted, result.historyTrusted());
            assertEquals(outcome, result.outcome());
            assertEquals(ProofStatus.IO_ERROR, result.terminalStatus());
            assertSame(cause, result.cause());
          }
        }
      }
    }
    var missing = new ChangedPageTrackerStartup.StartupResult(false, false,
        ChangedPageTrackerStartup.Outcome.MISSING_EXTENT, null, null);
    assertNull(missing.terminalStatus());
    assertNull(missing.cause());
  }

  // Resolution derives proof for every status, known or null shutdown and lookahead result.
  // MORE and null shutdowns never resolve as proved.
  // Saved-untrusted history is never upgraded. All paths retain marks, coverage, status and cause.
  @Test
  public void proofResolutionExhaustivelyDerivesPolicyAndKeepsOnlySavedTrust() throws Exception {
    for (var status : ProofStatus.values()) {
      for (var shutdown : new ChangedPageTrackerStartup.ShutdownState[] {
          ChangedPageTrackerStartup.ShutdownState.CLEAN,
          ChangedPageTrackerStartup.ShutdownState.UNCLEAN, null}) {
        for (boolean follows : new boolean[] {false, true}) {
          for (boolean savedTrusted : new boolean[] {false, true}) {
            var pending = savedHistory(savedTrusted);
            var cause = new IOException("diagnostic for " + status);
            var batch = new ProofBatch(List.of(), status, COVERAGE, follows, cause);
            boolean proved = shutdown != null && (status == ProofStatus.REACHED_END
                || status == ProofStatus.BROKEN_PAGE
                    && shutdown == ChangedPageTrackerStartup.ShutdownState.UNCLEAN && !follows);
            var outcome = !proved ? ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED
                : savedTrusted ? ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED
                    : ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED;
            assertEquals(status + " / " + shutdown + " / " + follows + " / " + savedTrusted,
                new ChangedPageTrackerStartup.StartupResult(proved, proved && savedTrusted,
                    outcome, status, cause),
                ChangedPageTrackerStartup.resolve(pending, batch, shutdown));
            assertEquals(proved ? ChangedPageTrackerStartup.Resolution.KEPT
                : ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
            assertEquals(proved && savedTrusted, pending.tracker().isTrusted());
            assertMarksAndCoverage(pending);
          }
        }
      }
    }
  }

  // Null batches and null statuses cannot prove coverage under either shutdown state or a missing
  // shutdown state. With a null shutdown, REACHED_END and BROKEN_PAGE distrust both pending and
  // already-kept history for either lookahead, preserving the exact diagnostic fields.
  @Test
  public void proofResolutionFailsClosedForNullInputs() throws Exception {
    var cause = new IOException("missing status");
    for (var shutdown : new ChangedPageTrackerStartup.ShutdownState[] {
        ChangedPageTrackerStartup.ShutdownState.CLEAN,
        ChangedPageTrackerStartup.ShutdownState.UNCLEAN, null}) {
      for (var batch : new ProofBatch[] {null,
          new ProofBatch(List.of(), null, COVERAGE, false, cause)}) {
        var pending = savedHistory(true);
        assertEquals(new ChangedPageTrackerStartup.StartupResult(false, false,
            ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED, null,
            batch == null ? null : cause),
            ChangedPageTrackerStartup.resolve(pending, batch, shutdown));
        assertDistrusted(pending);
      }
    }
    for (var status : List.of(ProofStatus.REACHED_END, ProofStatus.BROKEN_PAGE)) {
      for (boolean follows : new boolean[] {false, true}) {
        for (boolean kept : new boolean[] {false, true}) {
          var pending = savedHistory(true);
          if (kept) {
            pending.keepSavedTrust();
            assertTrue(pending.tracker().isTrusted());
          }
          var batch = new ProofBatch(List.of(), status, COVERAGE, follows, cause);
          assertEquals(new ChangedPageTrackerStartup.StartupResult(false, false,
              ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED, status, cause),
              ChangedPageTrackerStartup.resolve(pending, batch, null));
          assertDistrusted(pending);
        }
      }
    }
  }

  // Misusing the non-proof helper with any proof outcome throws only after trust is revoked.
  // This also holds for already-kept history, and exact marks and coverage survive rejection.
  @Test
  public void notProvedRejectsProofOutcomesAfterDistrustingHistory() throws Exception {
    for (var outcome : List.of(ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
        ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED)) {
      for (boolean kept : new boolean[] {false, true}) {
        var pending = savedHistory(true);
        if (kept) {
          pending.keepSavedTrust();
        }
        assertThrows(IllegalArgumentException.class,
            () -> ChangedPageTrackerStartup.notProved(pending, outcome,
                ProofStatus.REACHED_END, null));
        assertDistrusted(pending);
        assertEquals(1, capture(pending.tracker()).failureVersion());
      }
    }
  }

  // Each missing-input diagnostic and every non-proving status reaches the warning log with its
  // exact status and cause. No diagnostic path clears marks or alters the saved coverage.
  @Test
  public void nonProofDiagnosticsAreExactAndLoggedAtWarningLevel() throws Exception {
    try (var logs = LogRecordCollector.attachTo(ChangedPageTrackerStartup.class)) {
      for (var outcome : List.of(ChangedPageTrackerStartup.Outcome.MISSING_COVERAGE,
          ChangedPageTrackerStartup.Outcome.MISSING_EXTENT,
          ChangedPageTrackerStartup.Outcome.MISSING_READER,
          ChangedPageTrackerStartup.Outcome.MISSING_BATCH,
          ChangedPageTrackerStartup.Outcome.READER_FAILURE)) {
        var pending = savedHistory(true);
        var result = ChangedPageTrackerStartup.notProved(pending, outcome, null, null);
        assertEquals(new ChangedPageTrackerStartup.StartupResult(false, false, outcome, null, null),
            result);
        assertDistrusted(pending);
        assertTrue(logs.warnedWithAll("outcome=" + outcome, "status=null", "cause=null"));
      }
      for (var status : ProofStatus.values()) {
        if (status == ProofStatus.MORE || status == ProofStatus.REACHED_END) {
          continue;
        }
        var pending = savedHistory(true);
        var cause = new IOException("failure for " + status);
        assertEquals(new ChangedPageTrackerStartup.StartupResult(false, false,
            ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED, status, cause),
            ChangedPageTrackerStartup.resolve(pending,
                new ProofBatch(List.of(), status, COVERAGE, false, cause),
                ChangedPageTrackerStartup.ShutdownState.CLEAN));
        assertDistrusted(pending);
        assertTrue(logs.warnedWithAll("outcome=COVERAGE_NOT_PROVED", "status=" + status,
            "cause=" + cause));
      }
    }
  }

  // Replay revocation wins after a successful keep. Another proof cannot restore invalidated trust,
  // and repeated replay reports leave the failure version and retained marks unchanged.
  @Test
  public void replayFailureWinsOverKeepAndLaterProof() throws Exception {
    var pending = savedHistory(true);
    var batch = new ProofBatch(List.of(), ProofStatus.REACHED_END, COVERAGE, false, null);
    assertEquals(new ChangedPageTrackerStartup.StartupResult(true, true,
        ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED, ProofStatus.REACHED_END, null),
        ChangedPageTrackerStartup.resolve(pending, batch,
            ChangedPageTrackerStartup.ShutdownState.CLEAN));
    assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, pending.resolution());
    assertTrue(pending.tracker().isTrusted());
    ChangedPageTrackerStartup.replayFailed(pending);
    ChangedPageTrackerStartup.replayFailed(pending);
    assertEquals(new ChangedPageTrackerStartup.StartupResult(true, false,
        ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED, ProofStatus.REACHED_END, null),
        ChangedPageTrackerStartup.resolve(pending, batch,
            ChangedPageTrackerStartup.ShutdownState.CLEAN));
    assertDistrusted(pending);
    assertEquals(1, capture(pending.tracker()).failureVersion());

    // Tracker-level invalidation can precede resolution. A KEPT resolution still cannot report
    // trusted history when the tracker has already lost continuity.
    var invalidated = savedHistory(true);
    invalidated.tracker().invalidate();
    assertEquals(new ChangedPageTrackerStartup.StartupResult(true, false,
        ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED, ProofStatus.REACHED_END, null),
        ChangedPageTrackerStartup.resolve(invalidated, batch,
            ChangedPageTrackerStartup.ShutdownState.CLEAN));
    assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, invalidated.resolution());
    assertFalse(invalidated.tracker().isTrusted());
    assertMarksAndCoverage(invalidated);
  }

  // RuntimeException and Error propagate as the exact same instances after trust revocation.
  // Neither pending nor already-kept history can remain trusted after a reader failure.
  @Test
  public void readerFailuresDistrustAndRethrowTheSameInstance() throws Exception {
    for (boolean kept : new boolean[] {false, true}) {
      for (var failure : List.<Throwable>of(new IllegalStateException("reader failure"),
          new OutOfMemoryError("reader allocation failure"))) {
        var pending = savedHistory(true);
        if (kept) {
          pending.keepSavedTrust();
        }
        try (var logs = LogRecordCollector.attachTo(ChangedPageTrackerStartup.class)) {
          assertSame(failure, assertThrows(failure.getClass(),
              () -> ChangedPageTrackerStartup.readerFailed(pending, failure)));
          assertTrue(
              logs.warnedWithAll("outcome=READER_FAILURE", "status=null", "cause=" + failure));
        }
        assertDistrusted(pending);
      }
    }
  }

  // A failed gate clear leaves resolution pending. The original reader Error stays primary, and
  // a later distrust attempt completes the transition and clears the gate.
  @Test
  public void readerErrorSurvivesDistrustErrorAndLaterDistrustCompletes() throws Exception {
    assertReaderSurvivesDistrust(new Error("reader failure"), new Error("gate failure"));
  }

  // Checked reader failures retain their identity when trust revocation throws a runtime failure.
  @Test
  public void checkedReaderFailureSurvivesDistrustRuntimeException() throws Exception {
    assertReaderSurvivesDistrust(new IOException("reader failure"),
        new IllegalStateException("gate failure"));
  }

  // A repeated throwable must not trigger self-suppression or replace the reader failure.
  @Test
  public void readerFailureSurvivesSameFailureFromDistrust() throws Exception {
    var failure = new Error("shared failure");
    assertReaderSurvivesDistrust(failure, failure);
  }

  private void assertReaderSurvivesDistrust(Throwable primary, Throwable secondary)
      throws Exception {
    var tracker = spy(new ChangedPageTracker());
    ChangedPageTrackerStartup.PendingHistory pending;
    try (var files = mockStatic(ChangedPageTrackerFile.class)) {
      var path = sideFile();
      files.when(() -> ChangedPageTrackerFile.load(path))
          .thenReturn(new ChangedPageTrackerFile.Loaded(tracker, COVERAGE));
      pending = ChangedPageTrackerStartup.load(path);
    }
    doThrow(secondary).doCallRealMethod().when(tracker).startupVerificationPending(false);
    try (var logs = LogRecordCollector.attachTo(ChangedPageTrackerStartup.class)) {
      assertSame(primary, assertThrows(primary.getClass(),
          () -> ChangedPageTrackerStartup.readerFailed(pending, primary)));
      assertTrue(logs.warnedWithAll("outcome=READER_FAILURE", "cause=" + primary));
    }
    assertEquals(primary == secondary ? List.of() : List.of(secondary),
        Arrays.asList(primary.getSuppressed()));
    assertEquals(ChangedPageTrackerStartup.Resolution.PENDING, pending.resolution());
    pending.distrust();
    assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
    // A cleared gate makes another clear a no-op, visible through the existing mutation clock.
    long mutation = capture(tracker).mutationVersion();
    tracker.startupVerificationPending(false);
    assertEquals(mutation, capture(tracker).mutationVersion());
    assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
        ChangedPageTrackerFile.save(sideFile(), tracker, COVERAGE));
    tracker.retire(tracker.beginBackup());
    assertTrue(tracker.isTrusted());
  }

  // Logging itself can fail when memory is exhausted. Trust is still revoked and the original
  // reader Error must reach the caller instead of the secondary logging Error.
  @Test
  public void loggingFailureCannotReplaceTheReaderFailure() throws Exception {
    var pending = savedHistory(true);
    var failure = new OutOfMemoryError("reader failure");
    var diagnostic = new OutOfMemoryError("logging failure");
    try (var logger = mockStatic(LogManager.class)) {
      logger.when(LogManager::instance).thenThrow(diagnostic);
      assertSame(failure, assertThrows(OutOfMemoryError.class,
          () -> ChangedPageTrackerStartup.readerFailed(pending, failure)));
    }
    assertEquals(List.of(diagnostic), Arrays.asList(failure.getSuppressed()));
    assertDistrusted(pending);
  }

  // Distrust and logging failures are retained in order without replacing the reader Error.
  @Test
  public void readerFailureSuppressesDistrustAndLoggingFailuresInOrder() throws Exception {
    var pending = spy(savedHistory(true));
    var failure = new Error("reader failure");
    var distrust = new Error("distrust failure");
    var diagnostic = new Error("logging failure");
    doThrow(distrust).when(pending).distrust();
    try (var logger = mockStatic(LogManager.class)) {
      logger.when(LogManager::instance).thenThrow(diagnostic);
      assertSame(failure, assertThrows(Error.class,
          () -> ChangedPageTrackerStartup.readerFailed(pending, failure)));
    }
    assertEquals(List.of(distrust, diagnostic), Arrays.asList(failure.getSuppressed()));
  }

  // A logging failure that reuses the reader Error must not trigger self-suppression.
  @Test
  public void readerFailureSurvivesSameFailureFromLogging() throws Exception {
    var pending = savedHistory(true);
    var failure = new Error("shared failure");
    try (var logger = mockStatic(LogManager.class)) {
      logger.when(LogManager::instance).thenThrow(failure);
      assertSame(failure, assertThrows(Error.class,
          () -> ChangedPageTrackerStartup.readerFailed(pending, failure)));
    }
    assertEquals(List.of(), Arrays.asList(failure.getSuppressed()));
    assertDistrusted(pending);
  }

  // Missing inputs stop at their own boundary, warn once and retain all saved candidates.
  @Test
  public void verifyMissingInputsNeverCallDownstreamAndPreserveHistory() throws Exception {
    var outcomes = List.of(ChangedPageTrackerStartup.Outcome.MISSING_COVERAGE,
        ChangedPageTrackerStartup.Outcome.MISSING_EXTENT,
        ChangedPageTrackerStartup.Outcome.MISSING_READER,
        ChangedPageTrackerStartup.Outcome.MISSING_BATCH);
    for (int missing = 0; missing < outcomes.size(); missing++) {
      var pending = spy(savedHistory(true));
      if (missing == 0) {
        // The side-file codec requires coverage. Inject its absence at the pending-input seam.
        doReturn(null).when(pending).coverageLsn();
      }
      assertTrue(pending.savedTrusted());
      try (var wal = new ScriptedWal();
          var logs = LogRecordCollector.attachTo(ChangedPageTrackerStartup.class)) {
        wal.missing = missing;
        var result = ChangedPageTrackerStartup.verify(pending, wal,
            ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds());
        assertVerification(pending, result, outcomes.get(missing), null, null, MERGED);
        assertEquals(missing > 0 ? 1 : 0, wal.extentCalls);
        assertEquals(missing > 1 ? 1 : 0, wal.openCalls);
        assertEquals(missing > 2 ? 1 : 0, wal.nextCalls);
        assertEquals(1, logs.messages().stream().filter(s -> s.startsWith("WARNING ")).count());
        assertEquals(missing == 0 ? null : COVERAGE, pending.coverageLsn());
        assertEquals(COVERAGE, pending.tracker().durableCoverageLsn());
        assertEquals(0, wal.closeCalls);
      }
    }
  }

  // A non-null empty extent must open a reader. Empty MORE and full MORE are not end signals.
  // A full bounded batch followed by an empty terminal batch proves and retains every mark.
  @Test
  public void verifyConsumesEmptyMoreAndFullMoreBeforeEmptyEnd() throws Exception {
    var records = LongStream.range(100, 116)
        .mapToObj(page -> (WriteableWALRecord) update(page, EXTERNAL_ID, 7)).toList();
    var pending = savedHistory(true);
    try (var wal = new ScriptedWal(batch(ProofStatus.MORE),
        batch(ProofStatus.MORE, records.toArray(WriteableWALRecord[]::new)),
        batch(ProofStatus.REACHED_END))) {
      assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
          ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()),
          ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
          ProofStatus.REACHED_END, null,
          withPages(7, LongStream.range(100, 116).toArray()));
      assertEquals(3, wal.nextCalls);
      assertEquals(1, wal.extentCalls);
      assertEquals(1, wal.openCalls);
      assertEquals(0, wal.closeCalls);
    }
  }

  // Legacy updates and concrete logical operations in an incomplete and a rolled-back unit all
  // mark before terminal resolution. Duplicate records are idempotent. No redo or page access is
  // possible through these fixtures, and an adapter maps a storage-tagged ID to a different ID.
  @Test
  public void verifyMarksTerminalRecordsWithoutUnitOrLsnFiltersAndNeverUpgradesSavedTrust()
      throws Exception {
    for (boolean trusted : new boolean[] {false, true}) {
      var pending = savedHistory(trusted);
      var op = new RidbagEntryPointSetTreeSizeOp(120, EXTERNAL_ID, 8, COVERAGE, 3);
      var records = batch(ProofStatus.REACHED_END, new AtomicUnitStartRecord(true, 7),
          update(119, EXTERNAL_ID, 7), update(119, EXTERNAL_ID, 7),
          new AtomicUnitStartRecord(true, 8), op, new AtomicUnitEndRecord(8, true, Map.of()));
      try (var wal = new ScriptedWal(records)) {
        var files = new TestFileIds();
        assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
            ChangedPageTrackerStartup.ShutdownState.CLEAN, files),
            trusted
                ? ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED
                : ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED,
            ProofStatus.REACHED_END, null, withPages(7, 119, 120));
        assertEquals(List.of(EXTERNAL_ID, EXTERNAL_ID, EXTERNAL_ID), files.converted);
        assertEquals(1, wal.nextCalls);
        assertEquals(COVERAGE, pending.coverageLsn());
        assertEquals(COVERAGE, pending.tracker().durableCoverageLsn());
        assertEquals(COMPLETED, capture(pending.tracker()).lastCompletedIdentifier());
        assertEquals(TRACKER, capture(pending.tracker()).trackerIdentifier());
      }
    }
  }

  // Page-before-create and delete transitions keep saved trust and every saved or scanned mark.
  // Earlier and terminal events have the same policy, regardless of current names or absence.
  @Test
  public void verifyFileEventsPreserveAllMarksAndSavedTrust() throws Exception {
    for (String name : new String[] {"created", "renamed", null}) {
      var pending = savedHistory(true);
      var files = new TestFileIds();
      files.name = name;
      try (var wal = new ScriptedWal(batch(ProofStatus.MORE, update(121, EXTERNAL_ID, 7),
          new FileCreatedWALRecord(7, "created", EXTERNAL_ID)),
          batch(ProofStatus.REACHED_END, update(122, EXTERNAL_ID, 7),
              new FileDeletedWALRecord(7, EXTERNAL_ID), update(123, EXTERNAL_ID, 7)))) {
        assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
            ChangedPageTrackerStartup.ShutdownState.CLEAN, files),
            ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
            ProofStatus.REACHED_END, null, withPages(7, 121, 122, 123));
        assertEquals(0, capture(pending.tracker()).failureVersion());
        assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, pending.resolution());
        assertEquals(5, files.converted.size());
        assertEquals(0, files.nameCalls);
        assertEquals(2, wal.nextCalls);
        assertEquals(COVERAGE, pending.coverageLsn());
      }
    }
    // Either event alone in a terminal batch also keeps saved trust.
    for (var event : List.of(new FileCreatedWALRecord(7, "created", EXTERNAL_ID),
        new FileDeletedWALRecord(7, EXTERNAL_ID))) {
      var pending = savedHistory(true);
      try (var wal = new ScriptedWal(batch(ProofStatus.REACHED_END,
          update(121, EXTERNAL_ID, 7), event, update(122, EXTERNAL_ID, 7)))) {
        assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
            ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()),
            ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
            ProofStatus.REACHED_END, null, withPages(7, 121, 122));
        assertEquals(0, capture(pending.tracker()).failureVersion());
      }
    }
  }

  // Creation in an earlier batch keeps saved trust after an event-free terminal batch.
  // Both page records and all saved candidates remain available.
  @Test
  public void verifyCreateInMoreBatchKeepsTrustAfterEventFreeEnd() throws Exception {
    assertEarlierEventHistory(new FileCreatedWALRecord(7, "created", EXTERNAL_ID),
        ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
        ChangedPageTrackerStartup.Resolution.KEPT);
  }

  // Deletion in an earlier batch keeps saved trust after an event-free terminal batch.
  // Both page records and all saved candidates remain available.
  @Test
  public void verifyDeleteInMoreBatchKeepsTrustAfterEventFreeEnd() throws Exception {
    assertEarlierEventHistory(new FileDeletedWALRecord(7, EXTERNAL_ID),
        ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
        ChangedPageTrackerStartup.Resolution.KEPT);
  }

  private void assertEarlierEventHistory(WriteableWALRecord event,
      ChangedPageTrackerStartup.Outcome outcome, ChangedPageTrackerStartup.Resolution resolution)
      throws Exception {
    var pending = savedHistory(true);
    assertTrue(pending.savedTrusted());
    try (var wal = new ScriptedWal(batch(ProofStatus.MORE,
        update(121, EXTERNAL_ID, 7), event),
        batch(ProofStatus.REACHED_END, update(122, EXTERNAL_ID, 7)))) {
      assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
          ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()),
          outcome, ProofStatus.REACHED_END, null, withPages(7, 121, 122));
      assertEquals(resolution, pending.resolution());
      assertEquals(2, wal.nextCalls);
      assertEquals(COVERAGE, pending.coverageLsn());
      assertEquals(COVERAGE, pending.tracker().durableCoverageLsn());
    }
  }

  // File events retain untrusted saved history without another failure or a continuity reset.
  // Each event runs alone at the terminal batch and before an event-free terminal batch.
  @Test
  public void verifyFileEventsNeverUpgradeSavedUntrustedHistory() throws Exception {
    for (var event : List.of(new FileCreatedWALRecord(7, "created", EXTERNAL_ID),
        new FileDeletedWALRecord(7, EXTERNAL_ID))) {
      for (boolean earlierBatch : new boolean[] {false, true}) {
        var pending = savedHistory(false);
        var before = capture(pending.tracker());
        var records = batch(earlierBatch ? ProofStatus.MORE : ProofStatus.REACHED_END,
            update(121, EXTERNAL_ID, 7), event);
        try (var wal = new ScriptedWal(earlierBatch
            ? new ProofBatch[] {records, batch(ProofStatus.REACHED_END)}
            : new ProofBatch[] {records})) {
          assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
              ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()),
              ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED,
              ProofStatus.REACHED_END, null, withPages(7, 121));
          assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, pending.resolution());
          assertEquals(before.failureVersion(), capture(pending.tracker()).failureVersion());
          assertEquals(TRACKER, capture(pending.tracker()).trackerIdentifier());
          assertEquals(COMPLETED, capture(pending.tracker()).lastCompletedIdentifier());
        }
      }
    }
  }

  // Invalid page/create/delete IDs fail closed with external and converted IDs in the diagnostic.
  // Positive adapter rejection and permissive validation of nonpositive IDs both remain unsafe.
  @Test
  public void verifyRejectsInvalidIdsAndNegativePagesWithoutRetry() throws Exception {
    for (int internal : new int[] {0, -1, Integer.MIN_VALUE, 7}) {
      for (var record : List.of(update(124, EXTERNAL_ID, 7),
          new FileCreatedWALRecord(7, "created", EXTERNAL_ID),
          new FileDeletedWALRecord(7, EXTERNAL_ID))) {
        var pending = savedHistory(true);
        var files = new TestFileIds();
        files.internal = internal;
        files.valid = internal <= 0;
        try (var wal = new ScriptedWal(batch(ProofStatus.REACHED_END, record))) {
          var failure = assertThrows(IllegalArgumentException.class,
              () -> ChangedPageTrackerStartup.verify(pending, wal,
                  ChangedPageTrackerStartup.ShutdownState.CLEAN, files));
          assertTrue(failure.getMessage().contains("external=" + EXTERNAL_ID));
          assertTrue(failure.getMessage().contains("internal=" + internal));
          assertDistrusted(pending);
          assertEquals(1, wal.nextCalls);
        }
      }
    }
    var pending = savedHistory(true);
    try (var wal = new ScriptedWal(batch(ProofStatus.REACHED_END, update(-1, EXTERNAL_ID, 7)))) {
      assertThrows(IllegalArgumentException.class, () -> ChangedPageTrackerStartup.verify(
          pending, wal, ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()));
      assertDistrusted(pending);
      assertEquals(1, wal.nextCalls);
    }
  }

  // Every status passes through the actual consumer with a record, both lookaheads and all
  // shutdown states. MORE with known shutdown continues to an exact terminal batch and cause.
  @Test
  public void verifyProcessesRecordsBeforeEveryTerminalPolicyDecision() throws Exception {
    for (var status : ProofStatus.values()) {
      for (var shutdown : new ChangedPageTrackerStartup.ShutdownState[] {
          ChangedPageTrackerStartup.ShutdownState.CLEAN,
          ChangedPageTrackerStartup.ShutdownState.UNCLEAN, null}) {
        for (boolean follows : new boolean[] {false, true}) {
          var pending = savedHistory(true);
          var cause = new IOException("terminal diagnostic");
          var first = new ProofBatch(List.of(update(125, EXTERNAL_ID, 7)), status, COVERAGE,
              follows, cause);
          boolean continues = status == ProofStatus.MORE && shutdown != null;
          var terminal = continues
              ? new ProofBatch(List.of(update(126, EXTERNAL_ID, 7)), ProofStatus.REACHED_END,
                  COVERAGE, false, cause)
              : first;
          boolean proved = shutdown != null && (terminal.status() == ProofStatus.REACHED_END
              || terminal.status() == ProofStatus.BROKEN_PAGE
                  && shutdown == ChangedPageTrackerStartup.ShutdownState.UNCLEAN && !follows);
          try (var wal = new ScriptedWal(continues ? new ProofBatch[] {first, terminal}
              : new ProofBatch[] {first})) {
            assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
                shutdown, new TestFileIds()),
                proved
                    ? ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED
                    : ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED,
                terminal.status(), cause, continues ? withPages(7, 125, 126) : withPages(7, 125));
            assertEquals(continues ? 2 : 1, wal.nextCalls);
          }
        }
      }
    }
    var pending = savedHistory(true);
    try (var wal = new ScriptedWal(batch(null, update(125, EXTERNAL_ID, 7)))) {
      assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
          ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()),
          ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED, null, null, withPages(7, 125));
    }
  }

  // Extent, open, next, conversion and validation failures revoke trust and rethrow the same
  // RuntimeException or Error. A failing next is called once, never retried or treated as EOF.
  @Test
  public void verifyFailuresPropagateSameInstanceAndStopAtTheFailingBoundary() throws Exception {
    for (int stage = 1; stage <= 5; stage++) {
      for (var failure : List.<Throwable>of(new IllegalStateException("injected failure"),
          new OutOfMemoryError("injected allocation failure"))) {
        var pending = savedHistory(true);
        try (var wal = new ScriptedWal(batch(ProofStatus.MORE, update(127, EXTERNAL_ID, 7)),
            batch(ProofStatus.REACHED_END))) {
          wal.failAt = stage;
          wal.failure = failure;
          var files = new TestFileIds();
          files.failure = failure;
          files.failAt = stage;
          assertSame(failure, assertThrows(failure.getClass(),
              () -> ChangedPageTrackerStartup.verify(pending, wal,
                  ChangedPageTrackerStartup.ShutdownState.CLEAN, files)));
          assertDistrusted(pending);
          assertEquals(1, wal.extentCalls);
          assertEquals(stage >= 2 ? 1 : 0, wal.openCalls);
          assertEquals(stage >= 3 ? 1 : 0, wal.nextCalls);
          assertEquals(0, wal.closeCalls);
        }
      }
    }
  }

  // All admitted harmless markers and metadata, including a decoded truncate object, preserve
  // exact marks and allow proof. An unclassified decoded record fails closed instead.
  @Test
  public void verifySkipsOnlyKnownHarmlessRecordsAndPreservesTruncatedMarks() throws Exception {
    var pending = savedHistory(true);
    try (var wal = new ScriptedWal(batch(ProofStatus.REACHED_END,
        new AtomicUnitStartRecord(true, 7), new AtomicUnitEndRecord(7, false, Map.of()),
        new AtomicUnitStartMetadataRecord(true, 8, new byte[0]),
        new MetaDataRecord(new byte[0]), new HighLevelTransactionChangeRecord(8, new byte[0]),
        new EmptyWALRecord(),
        new FileTruncatedWALRecord(8, EXTERNAL_ID)))) {
      var files = new TestFileIds();
      assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
          ChangedPageTrackerStartup.ShutdownState.CLEAN, files),
          ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
          ProofStatus.REACHED_END, null, MERGED);
      assertTrue(files.converted.isEmpty());
    }
    pending = savedHistory(true);
    var unknownHistory = pending;
    try (var wal = new ScriptedWal(batch(ProofStatus.REACHED_END,
        mock(WriteableWALRecord.class)))) {
      assertThrows(IllegalStateException.class, () -> ChangedPageTrackerStartup.verify(
          unknownHistory, wal, ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()));
      assertDistrusted(pending);
      assertEquals(1, wal.nextCalls);
    }
  }

  // Memory WAL cannot prove persistent coverage even after successful logging. Missing coverage
  // stops before legacy memory reads, which throw rather than providing an end signal.
  @Test
  public void verifyRealMemoryWalNeverProvesPersistentHistory() throws Exception {
    try (var wal = new MemoryWriteAheadLog()) {
      wal.log(update(128, EXTERNAL_ID, 7));
      var pending = savedHistory(true);
      assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
          ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()),
          ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED,
          ProofStatus.UNAVAILABLE, null, MERGED);
      assertMarksAndCoverage(pending);
      pending = ChangedPageTrackerStartup.load(sideFile());
      assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
          ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()),
          ChangedPageTrackerStartup.Outcome.MISSING_COVERAGE, null, null, Map.of());
    }
  }

  // A real closed and reopened disk WAL includes uncommitted logical and legacy page records.
  // Inclusive saved coverage and old active/sealed candidates survive exact proof consumption.
  @Test
  public void verifyReopenedDiskWalMarksUncommittedRecordsAndPreservesSavedHistory()
      throws Exception {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
    var directory = folder.newFolder().toPath();
    LogSequenceNumber coverage;
    try (var wal = diskWal(directory)) {
      coverage = wal.log(new AtomicUnitStartRecord(true, 7));
      wal.log(new RidbagEntryPointSetTreeSizeOp(129, EXTERNAL_ID, 7, COVERAGE, 3));
      wal.log(update(130, EXTERNAL_ID, 7));
      wal.log(new MetaDataRecord(new byte[] {1}));
      wal.log(new AtomicUnitStartRecord(true, 8));
      wal.log(new RidbagEntryPointSetTreeSizeOp(131, EXTERNAL_ID, 8, COVERAGE, 4));
      wal.log(new AtomicUnitEndRecord(8, false, Map.of()));
    }
    var path = sideFile();
    Files.write(path, encode(true, true, coverage));
    var pending = ChangedPageTrackerStartup.load(path);
    try (var wal = diskWal(directory)) {
      assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
          ChangedPageTrackerStartup.ShutdownState.CLEAN, new TestFileIds()),
          ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
          ProofStatus.REACHED_END, null, withPages(7, 129, 130, 131));
      assertEquals(coverage, pending.coverageLsn());
      assertEquals(coverage, pending.tracker().durableCoverageLsn());
      assertEquals(TRACKER, capture(pending.tracker()).trackerIdentifier());
      assertEquals(COMPLETED, capture(pending.tracker()).lastCompletedIdentifier());
    }
  }

  // Three pre-open segments need three bounded batches. The terminal batch still contributes
  // marks, while a flushed page appended after reopen stays outside the immutable proof extent.
  @Test
  public void verifyDiskWalConsumesEveryPreOpenBatchAndExcludesPostOpenPage() throws Exception {
    var directory = folder.newFolder().toPath();
    LogSequenceNumber coverage;
    LogSequenceNumber lastPage = null;
    try (var wal = diskWal(directory)) {
      coverage = wal.begin();
      for (int segment = 0; segment < 3; segment++) {
        if (segment > 0) {
          assertTrue(wal.appendNewSegment());
        }
        for (int page = 0; page < 11; page++) {
          lastPage = wal.log(update(200 + segment * 11 + page, EXTERNAL_ID, 7));
        }
      }
    }
    var pending = savedHistory(true, coverage);
    try (var wal = diskWal(directory)) {
      var extent = wal.preOpenExtent();
      assertEquals(List.of(coverage.getSegment(), coverage.getSegment() + 1,
          coverage.getSegment() + 2), extent.segments().stream().map(s -> s.segment()).toList());
      for (var segment : extent.segments()) {
        assertEquals(Files.size(segmentPath(directory, segment.segment())), segment.bytes());
      }
      var appended = wal.log(update(999, EXTERNAL_ID, 7));
      wal.flush();
      assertEquals(appended, wal.end());
      assertTrue(appended.getSegment() > extent.segments().getLast().segment());
      assertEquals(extent, wal.preOpenExtent());
      var files = new TestFileIds();
      assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
          ChangedPageTrackerStartup.ShutdownState.CLEAN, files),
          ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
          ProofStatus.REACHED_END, null, withPages(7, LongStream.range(200, 233).toArray()));
      assertEquals(Collections.nCopies(33, EXTERNAL_ID), files.converted);
      assertEquals(coverage, pending.coverageLsn());
      assertEquals(coverage, pending.tracker().durableCoverageLsn());
      assertEquals(TRACKER, capture(pending.tracker()).trackerIdentifier());
      assertEquals(COMPLETED, capture(pending.tracker()).lastCompletedIdentifier());

      // StartupResult has no endpoint field. An independent reader shows the same physical
      // endpoint and proves that the last page belongs to a nonempty terminal batch.
      var reader = wal.openProofReader(coverage, 16 * 1024 * 1024);
      for (int batchIndex = 0; batchIndex < 3; batchIndex++) {
        var batch = reader.next(16);
        assertNull(batch.error());
        assertEquals(batchIndex < 2 ? 16 : 4, batch.records().size());
        assertEquals(batchIndex < 2 ? ProofStatus.MORE : ProofStatus.REACHED_END, batch.status());
        if (batchIndex == 2) {
          var last = extent.segments().getLast();
          assertEquals(lastPage, batch.records().getLast().getLsn());
          assertEquals(new LogSequenceNumber(last.segment(), (int) last.bytes()), batch.position());
          assertNotEquals(wal.end(), batch.position());
        }
      }
    }
  }

  // Removing the saved coverage segment before reopen must not substitute the new begin LSN.
  // Both shutdown states fail with COVERAGE_NOT_RETAINED and retain only the saved candidates.
  @Test
  public void verifyDiskWalRejectsDeletedCoverageSegment() throws Exception {
    var directory = folder.newFolder().toPath();
    var coverage = threeSegmentPageWal(directory);
    Files.delete(segmentPath(directory, coverage.getSegment()));
    try (var wal = diskWal(directory)) {
      assertNotEquals(coverage, wal.begin());
      assertEquals(List.of(coverage.getSegment() + 1, coverage.getSegment() + 2),
          wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
      for (var shutdown : ChangedPageTrackerStartup.ShutdownState.values()) {
        var pending = savedHistory(true, coverage);
        assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
            shutdown, new TestFileIds()),
            ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED,
            ProofStatus.COVERAGE_NOT_RETAINED, null, MERGED);
        assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
        assertEquals(coverage, pending.coverageLsn());
        assertEquals(coverage, pending.tracker().durableCoverageLsn());
      }
    }
  }

  // A later retained segment makes an interior deletion observable. The reader rejects the gap
  // before emitting any pages, regardless of shutdown state, without clearing saved candidates.
  @Test
  public void verifyDiskWalRejectsDeletedInteriorSegment() throws Exception {
    var directory = folder.newFolder().toPath();
    var coverage = threeSegmentPageWal(directory);
    Files.delete(segmentPath(directory, coverage.getSegment() + 1));
    try (var wal = diskWal(directory)) {
      assertEquals(List.of(coverage.getSegment(), coverage.getSegment() + 2),
          wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
      for (var shutdown : ChangedPageTrackerStartup.ShutdownState.values()) {
        var pending = savedHistory(true, coverage);
        assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
            shutdown, new TestFileIds()),
            ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED,
            ProofStatus.MISSING_SEGMENT, null, MERGED);
        assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
        assertEquals(coverage, pending.coverageLsn());
        assertEquals(coverage, pending.tracker().durableCoverageLsn());
      }
    }
  }

  // Deleting an unread segment after reopen preserves the captured inventory but cannot prove
  // coverage. The real filesystem cause and the readable first-segment page survive resolution.
  @Test
  public void verifyDiskWalReportsSegmentVanishedAfterExtentCapture() throws Exception {
    var directory = folder.newFolder().toPath();
    var coverage = threeSegmentPageWal(directory);
    try (var wal = diskWal(directory)) {
      var extent = wal.preOpenExtent();
      assertEquals(3, extent.segments().size());
      var vanished = segmentPath(directory, coverage.getSegment() + 1);
      Files.delete(vanished);
      assertEquals(extent, wal.preOpenExtent());
      for (var shutdown : ChangedPageTrackerStartup.ShutdownState.values()) {
        var pending = savedHistory(true, coverage);
        var result = ChangedPageTrackerStartup.verify(pending, wal, shutdown, new TestFileIds());
        assertTrue(result.cause() instanceof NoSuchFileException);
        assertEquals(vanished.toString(), ((NoSuchFileException) result.cause()).getFile());
        assertVerification(pending, result, ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED,
            ProofStatus.SEGMENT_VANISHED, result.cause(), withPages(7, 300));
        assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
        assertEquals(coverage, pending.coverageLsn());
        assertEquals(coverage, pending.tracker().durableCoverageLsn());
      }
    }
  }

  // A real create record follows pages for the same file. Neither saved nor scanned pages may be
  // cleared. A terminal event and an earlier-batch event both retain saved trust.
  @Test
  public void verifyDiskWalCreatePreservesPageBeforeCreateAndSavedTrust() throws Exception {
    assertDiskFileEventPreservesMarks(() -> new FileCreatedWALRecord(7, "created", EXTERNAL_ID));
  }

  // A real delete record independently keeps marks and saved trust even without a current name.
  // This also holds when the event precedes an event-free final batch.
  @Test
  public void verifyDiskWalDeletePreservesPagesAndSavedTrust() throws Exception {
    assertDiskFileEventPreservesMarks(() -> new FileDeletedWALRecord(7, EXTERNAL_ID));
  }

  // A damaged final pre-open page has no readable suffix, even though reopen creates a segment.
  // CLEAN distrusts and UNCLEAN keeps saved trust. Both retain complete terminal-batch page marks.
  @Test
  public void verifyDiskWalBrokenTailKeepsMarksAndProvesOnlyAfterUncleanShutdown()
      throws Exception {
    var directory = folder.newFolder().toPath();
    LogSequenceNumber coverage;
    LogSequenceNumber prefix;
    LogSequenceNumber metadata;
    try (var wal = diskWal(directory)) {
      coverage = wal.begin();
      wal.log(update(500, EXTERNAL_ID, 7));
      prefix = wal.log(update(501, EXTERNAL_ID, 7));
      wal.flush();
      var payload = new byte[4500];
      new Random(18).nextBytes(payload);
      metadata = wal.log(new MetaDataRecord(payload));
    }
    var size = Files.size(segmentPath(directory, metadata.getSegment()));
    assertEquals(0, size % CASWALPage.DEFAULT_PAGE_SIZE);
    var brokenPage = size / CASWALPage.DEFAULT_PAGE_SIZE - 1;
    assertTrue(brokenPage > prefix.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE);
    assertTrue(brokenPage > metadata.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE);
    breakPage(directory, metadata.getSegment(), brokenPage);
    try (var wal = diskWal(directory)) {
      assertEquals(List.of(coverage.getSegment()),
          wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
      assertTrue(wal.end().getSegment() > metadata.getSegment());
      assertBrokenPageVerification(wal, coverage, metadata.getSegment(), brokenPage, false);
    }
  }

  // An intact continuation page after interior damage is readable same-segment suffix evidence.
  // Even UNCLEAN cannot prove coverage. Prefix marks survive and the later page stays excluded.
  @Test
  public void verifyDiskWalBrokenInteriorPageDistrustsWithReadableSameSegmentSuffix()
      throws Exception {
    var directory = folder.newFolder().toPath();
    LogSequenceNumber coverage;
    LogSequenceNumber prefix;
    LogSequenceNumber metadata;
    try (var wal = diskWal(directory)) {
      coverage = wal.begin();
      wal.log(update(500, EXTERNAL_ID, 7));
      prefix = wal.log(update(501, EXTERNAL_ID, 7));
      wal.flush();
      var payload = new byte[10000];
      new Random(18).nextBytes(payload);
      var record = new MetaDataRecord(payload);
      metadata = wal.log(record);
      assertTrue(record.getBinaryContentLen() > 2 * CASWALPage.DEFAULT_PAGE_SIZE);
      wal.log(update(599, EXTERNAL_ID, 7));
    }
    var size = Files.size(segmentPath(directory, metadata.getSegment()));
    assertEquals(0, size % CASWALPage.DEFAULT_PAGE_SIZE);
    var brokenPage = metadata.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE + 1L;
    assertTrue(brokenPage > prefix.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE);
    assertTrue((brokenPage + 2) * CASWALPage.DEFAULT_PAGE_SIZE <= size);
    breakPage(directory, metadata.getSegment(), brokenPage);
    try (var wal = diskWal(directory)) {
      assertEquals(List.of(coverage.getSegment()),
          wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
      assertBrokenPageVerification(wal, coverage, metadata.getSegment(), brokenPage, true);
    }
  }

  // Damage ends the first segment, but a readable later segment existed before reopen.
  // Both shutdown states distrust, preserve the prefix and exclude the later segment's mark.
  @Test
  public void verifyDiskWalBrokenEarlierSegmentTailDistrustsWithReadableLaterSegment()
      throws Exception {
    var directory = folder.newFolder().toPath();
    LogSequenceNumber coverage;
    LogSequenceNumber prefix;
    LogSequenceNumber metadata;
    LogSequenceNumber suffix;
    try (var wal = diskWal(directory)) {
      coverage = wal.begin();
      wal.log(update(500, EXTERNAL_ID, 7));
      prefix = wal.log(update(501, EXTERNAL_ID, 7));
      wal.flush();
      var payload = new byte[4500];
      new Random(18).nextBytes(payload);
      metadata = wal.log(new MetaDataRecord(payload));
      assertTrue(wal.appendNewSegment());
      suffix = wal.log(update(599, EXTERNAL_ID, 7));
    }
    assertEquals(metadata.getSegment() + 1, suffix.getSegment());
    assertTrue(Files.size(segmentPath(directory, suffix.getSegment())) > 0);
    var size = Files.size(segmentPath(directory, metadata.getSegment()));
    assertEquals(0, size % CASWALPage.DEFAULT_PAGE_SIZE);
    var brokenPage = size / CASWALPage.DEFAULT_PAGE_SIZE - 1;
    assertTrue(brokenPage > prefix.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE);
    assertTrue(brokenPage > metadata.getPosition() / CASWALPage.DEFAULT_PAGE_SIZE);
    breakPage(directory, metadata.getSegment(), brokenPage);
    try (var wal = diskWal(directory)) {
      assertEquals(List.of(metadata.getSegment(), suffix.getSegment()),
          wal.preOpenExtent().segments().stream().map(s -> s.segment()).toList());
      assertBrokenPageVerification(wal, coverage, metadata.getSegment(), brokenPage, true);
    }
  }

  // Ordinary WAL logging compresses this zero-filled payload below the bound, but its expanded
  // content exceeds startup's 16 MiB limit. Both shutdown states distrust and keep earlier marks.
  @Test
  public void verifyDiskWalRejectsOversizedExpandedMetadataAndPreservesEarlierMarks()
      throws Exception {
    var directory = folder.newFolder().toPath();
    int bound = 16 * 1024 * 1024;
    LogSequenceNumber coverage;
    LogSequenceNumber oversized;
    try (var wal = diskWal(directory)) {
      coverage = wal.begin();
      wal.log(update(500, EXTERNAL_ID, 7));
      wal.log(update(501, EXTERNAL_ID, 7));
      var record = new MetaDataRecord(new byte[bound + 1]);
      oversized = wal.log(record);
      assertTrue(record.serializedSize() + 6 > bound);
      assertTrue(record.getBinaryContentLen() < bound);
      wal.log(update(599, EXTERNAL_ID, 7));
    }
    try (var wal = diskWal(directory)) {
      var batch = wal.openProofReader(coverage, bound).next(16);
      assertEquals(ProofStatus.RECORD_TOO_LARGE, batch.status());
      assertEquals(oversized, batch.position());
      assertEquals(List.of(500L, 501L), batch.records().stream()
          .filter(UpdatePageRecord.class::isInstance).map(UpdatePageRecord.class::cast)
          .map(UpdatePageRecord::getPageIndex).toList());
      assertNull(batch.error());
      for (var shutdown : ChangedPageTrackerStartup.ShutdownState.values()) {
        var pending = savedHistory(true, coverage);
        assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
            shutdown, new TestFileIds()), ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED,
            ProofStatus.RECORD_TOO_LARGE, null, withPages(7, 500, 501));
        assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
        assertEquals(coverage, pending.coverageLsn());
        assertEquals(coverage, pending.tracker().durableCoverageLsn());
      }
    }
  }

  private void assertBrokenPageVerification(CASDiskWriteAheadLog wal, LogSequenceNumber coverage,
      long segment, long brokenPage, boolean readablePageFollows) throws Exception {
    // An independent probe pins the physical failure and keeps both prefix records in its terminal
    // batch. Each consumer call starts with fresh pending history on the same single reopen.
    var batch = wal.openProofReader(coverage, 16 * 1024 * 1024).next(16);
    assertEquals(ProofStatus.BROKEN_PAGE, batch.status());
    assertEquals(new LogSequenceNumber(segment, Math.toIntExact(
        brokenPage * CASWALPage.DEFAULT_PAGE_SIZE)), batch.position());
    assertEquals(readablePageFollows, batch.readablePageFollows());
    assertNull(batch.error());
    assertEquals(List.of(500L, 501L), batch.records().stream()
        .filter(UpdatePageRecord.class::isInstance).map(UpdatePageRecord.class::cast)
        .map(UpdatePageRecord::getPageIndex).toList());
    for (var shutdown : ChangedPageTrackerStartup.ShutdownState.values()) {
      boolean proved = shutdown == ChangedPageTrackerStartup.ShutdownState.UNCLEAN
          && !readablePageFollows;
      var pending = savedHistory(true, coverage);
      assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
          shutdown, new TestFileIds()),
          proved
              ? ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED
              : ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED,
          ProofStatus.BROKEN_PAGE, null, withPages(7, 500, 501));
      assertEquals(proved ? ChangedPageTrackerStartup.Resolution.KEPT
          : ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
      assertEquals(coverage, pending.coverageLsn());
      assertEquals(coverage, pending.tracker().durableCoverageLsn());
    }
  }

  private static void breakPage(Path directory, long segment, long page) throws IOException {
    try (var file = FileChannel.open(segmentPath(directory, segment), StandardOpenOption.WRITE)) {
      var damage = ByteBuffer.wrap(new byte[Long.BYTES]);
      long offset = page * CASWALPage.DEFAULT_PAGE_SIZE;
      while (damage.hasRemaining()) {
        int written = file.write(damage, offset + damage.position());
        if (written <= 0) {
          throw new IOException("Could not overwrite WAL page at " + offset);
        }
      }
    }
  }

  private void assertDiskFileEventPreservesMarks(Supplier<WriteableWALRecord> event)
      throws Exception {
    for (boolean earlierBatch : new boolean[] {false, true}) {
      var directory = folder.newFolder().toPath();
      LogSequenceNumber coverage;
      try (var wal = diskWal(directory)) {
        coverage = wal.begin();
        wal.log(new AtomicUnitStartRecord(true, 7));
        wal.log(update(400, EXTERNAL_ID, 7));
        wal.log(update(401, EXTERNAL_ID, 7));
        // Logged records retain writer placement state, so each WAL fixture owns a fresh event.
        wal.log(event.get());
        if (earlierBatch) {
          for (long page = 402; page < 422; page++) {
            wal.log(update(page, EXTERNAL_ID, 7));
          }
          wal.log(new AtomicUnitEndRecord(7, false, Map.of()));
        }
      }
      var pending = savedHistory(true, coverage);
      try (var wal = diskWal(directory)) {
        var files = new TestFileIds();
        files.name = earlierBatch ? null : "created";
        assertVerification(pending, ChangedPageTrackerStartup.verify(pending, wal,
            ChangedPageTrackerStartup.ShutdownState.CLEAN, files),
            ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED,
            ProofStatus.REACHED_END, null,
            withPages(7, LongStream.range(400, earlierBatch ? 422 : 402).toArray()));
        assertEquals(Collections.nCopies(earlierBatch ? 23 : 3, EXTERNAL_ID),
            files.converted);
        assertEquals(0, files.nameCalls);
        assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, pending.resolution());
        assertEquals(0, capture(pending.tracker()).failureVersion());
        assertEquals(TRACKER, capture(pending.tracker()).trackerIdentifier());
        assertEquals(COMPLETED, capture(pending.tracker()).lastCompletedIdentifier());
        assertEquals(coverage, pending.coverageLsn());
        assertEquals(coverage, pending.tracker().durableCoverageLsn());
      }
    }
  }

  private static LogSequenceNumber threeSegmentPageWal(Path directory) throws IOException {
    try (var wal = diskWal(directory)) {
      var coverage = wal.begin();
      for (int segment = 0; segment < 3; segment++) {
        if (segment > 0) {
          assertTrue(wal.appendNewSegment());
        }
        wal.log(update(300 + segment, EXTERNAL_ID, 7));
      }
      return coverage;
    }
  }

  private static Path segmentPath(Path directory, long segment) {
    return directory.resolve(ContextConfiguration.WAL_DEFAULT_NAME + "." + segment + ".wal");
  }

  private static CASDiskWriteAheadLog diskWal(Path directory) throws IOException {
    // Register production logical operations before both fixture writes and proof reopens.
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
    return new CASDiskWriteAheadLog("startup", directory, directory,
        ContextConfiguration.WAL_DEFAULT_NAME, 100, 64, null, null, Integer.MAX_VALUE,
        Integer.MAX_VALUE, 20, true, Locale.US, -1, 1000, false, true, false, 10);
  }

  private static UpdatePageRecord update(long page, long file, long unit) {
    return new UpdatePageRecord(page, file, unit, new WALPageChangesPortion(), COVERAGE);
  }

  private static ProofBatch batch(ProofStatus status, WriteableWALRecord... records) {
    return new ProofBatch(List.of(records), status, COVERAGE, false, null);
  }

  private static Map<Integer, TreeSet<Long>> withPages(int file, long... added) {
    var result = new TreeMap<Integer, TreeSet<Long>>();
    MERGED.forEach((id, marks) -> result.put(id, new TreeSet<>(marks)));
    for (long page : added) {
      result.computeIfAbsent(file, ignored -> new TreeSet<>()).add(page);
    }
    return result;
  }

  private static void assertVerification(ChangedPageTrackerStartup.PendingHistory pending,
      ChangedPageTrackerStartup.StartupResult result, ChangedPageTrackerStartup.Outcome outcome,
      ProofStatus status, Throwable cause, Map<Integer, TreeSet<Long>> candidates) {
    boolean proved = outcome == ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED
        || outcome == ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED;
    boolean trusted = outcome == ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED;
    assertEquals(
        new ChangedPageTrackerStartup.StartupResult(proved, trusted, outcome, status, cause),
        result);
    assertSame(cause, result.cause());
    assertEquals(trusted, pending.tracker().isTrusted());
    assertEquals(candidates, pages(capture(pending.tracker()).active()));
  }

  // Finite scripts make an unexpected read fail loudly. No implicit null or empty EOF exists.
  private static final class ScriptedWal extends MemoryWriteAheadLog {
    private final ProofBatch[] batches;
    private int missing = -1;
    private int failAt;
    private Throwable failure;
    private int extentCalls;
    private int openCalls;
    private int nextCalls;
    private int closeCalls;

    private ScriptedWal(ProofBatch... batches) {
      this.batches = batches;
    }

    @Override
    public PreOpenExtent preOpenExtent() {
      extentCalls++;
      if (failAt == 1) {
        throwUnchecked(failure);
      }
      return missing == 1 ? null : new PreOpenExtent(List.of());
    }

    @Override
    public ProofReader openProofReader(LogSequenceNumber coverage, int maxRecordBytes) {
      openCalls++;
      assertEquals(COVERAGE, coverage);
      assertEquals(16 * 1024 * 1024, maxRecordBytes);
      if (failAt == 2) {
        throwUnchecked(failure);
      }
      return missing == 2 ? null : maxRecords -> {
        assertEquals(16, maxRecords);
        nextCalls++;
        if (failAt == 3) {
          throwUnchecked(failure);
        }
        if (missing == 3) {
          return null;
        }
        assertTrue("Unexpected read past finite script", nextCalls <= batches.length);
        return batches[nextCalls - 1];
      };
    }

    @Override
    public void close() {
      closeCalls++;
    }
  }

  private static final class TestFileIds implements ChangedPageTrackerStartup.FileIds {
    private int internal = 7;
    private boolean valid = true;
    private int failAt;
    private Throwable failure;
    private String name;
    private int nameCalls;
    private final ArrayList<Long> converted = new ArrayList<>();

    @Override
    public int internalFileId(long externalId) {
      converted.add(externalId);
      if (failAt == 4) {
        throwUnchecked(failure);
      }
      return internal;
    }

    @Override
    public boolean isValidInternalFileId(int internalId) {
      if (failAt == 5) {
        throwUnchecked(failure);
      }
      return valid;
    }

    @Override
    public String currentName(int internalId) {
      nameCalls++;
      return name;
    }
  }

  private static void throwUnchecked(Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }
    throw (RuntimeException) failure;
  }

  private ChangedPageTrackerStartup.PendingHistory savedHistory(boolean trusted) throws Exception {
    return savedHistory(trusted, COVERAGE);
  }

  private ChangedPageTrackerStartup.PendingHistory savedHistory(boolean trusted,
      LogSequenceNumber coverage) throws Exception {
    var path = sideFile();
    Files.write(path, encode(trusted, true, coverage));
    return ChangedPageTrackerStartup.load(path);
  }

  private static void assertMarksAndCoverage(ChangedPageTrackerStartup.PendingHistory pending) {
    assertEquals(MERGED, pages(capture(pending.tracker()).active()));
    assertEquals(COVERAGE, pending.coverageLsn());
    assertEquals(COVERAGE, pending.tracker().durableCoverageLsn());
  }

  private static void assertDistrusted(ChangedPageTrackerStartup.PendingHistory pending) {
    assertEquals(ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
    assertFalse(pending.tracker().isTrusted());
    assertMarksAndCoverage(pending);
  }

  // The default validation admits exactly positive IDs independently of conversion or inventory.
  @Test
  public void fileIdsValidationRequiresPositiveInternalIds() {
    ChangedPageTrackerStartup.FileIds files = new ChangedPageTrackerStartup.FileIds() {
      @Override
      public int internalFileId(long externalId) {
        return (int) externalId;
      }

      @Override
      public String currentName(int internalId) {
        return null;
      }
    };
    assertEquals(7, files.internalFileId((12L << 32) | 7));
    assertNull(files.currentName(7));
    assertFalse(files.isValidInternalFileId(Integer.MIN_VALUE));
    assertFalse(files.isValidInternalFileId(0));
    assertTrue(files.isValidInternalFileId(1));
    assertTrue(files.isValidInternalFileId(Integer.MAX_VALUE));
  }

  private Path sideFile() throws Exception {
    return folder.newFolder().toPath().resolve(ChangedPageTrackerFile.FILE_NAME);
  }

  private static byte[] encode(boolean trusted, boolean sealed) throws Exception {
    return encode(trusted, sealed, COVERAGE);
  }

  private static byte[] encode(boolean trusted, boolean sealed, LogSequenceNumber coverage)
      throws Exception {
    var builder =
        ChangedPageTracker.restoration(TRACKER, COMPLETED, trusted, sealed ? SEALED : null);
    builder.activeWord(1, 0, Long.MIN_VALUE | 1);
    builder.activeWord(7, 512, 1);
    if (sealed) {
      builder.sealedWord(1, 0, 3);
      builder.sealedWord(9, 1, 2);
    }
    var out = new ByteArrayOutputStream();
    ChangedPageTrackerFile.write(out, capture(builder.build()), coverage);
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
