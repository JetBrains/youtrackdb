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
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.ProofBatch;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.ProofStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
            case PROVED_HISTORY_UNTRUSTED, PROVED_IDENTITY_UNRESOLVED -> proved && !trusted;
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
  // MORE and null shutdowns never resolve as proved. Identity uncertainty revokes proved history.
  // Saved-untrusted history is never upgraded. All paths retain marks, coverage, status and cause.
  @Test
  public void proofResolutionExhaustivelyDerivesPolicyAndKeepsOnlySavedTrust() throws Exception {
    for (var status : ProofStatus.values()) {
      for (var shutdown : new ChangedPageTrackerStartup.ShutdownState[] {
          ChangedPageTrackerStartup.ShutdownState.CLEAN,
          ChangedPageTrackerStartup.ShutdownState.UNCLEAN, null}) {
        for (boolean follows : new boolean[] {false, true}) {
          for (boolean savedTrusted : new boolean[] {false, true}) {
            for (boolean identityUnresolved : new boolean[] {false, true}) {
              var pending = savedHistory(savedTrusted);
              var cause = new IOException("diagnostic for " + status);
              var batch = new ProofBatch(List.of(), status, COVERAGE, follows, cause);
              boolean proved = shutdown != null && (status == ProofStatus.REACHED_END
                  || status == ProofStatus.BROKEN_PAGE
                      && shutdown == ChangedPageTrackerStartup.ShutdownState.UNCLEAN && !follows);
              boolean kept = proved && !identityUnresolved;
              var outcome = !proved ? ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED
                  : identityUnresolved
                      ? ChangedPageTrackerStartup.Outcome.PROVED_IDENTITY_UNRESOLVED
                      : savedTrusted ? ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_TRUSTED
                          : ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED;
              assertEquals(status + " / " + shutdown + " / " + follows + " / " + savedTrusted
                  + " / " + identityUnresolved,
                  new ChangedPageTrackerStartup.StartupResult(proved, kept && savedTrusted,
                      outcome, status, cause),
                  ChangedPageTrackerStartup.resolve(pending, batch, shutdown, identityUnresolved));
              assertEquals(kept ? ChangedPageTrackerStartup.Resolution.KEPT
                  : ChangedPageTrackerStartup.Resolution.DISTRUSTED, pending.resolution());
              assertEquals(kept && savedTrusted, pending.tracker().isTrusted());
              assertMarksAndCoverage(pending);
            }
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
      for (boolean identityUnresolved : new boolean[] {false, true}) {
        for (var batch : new ProofBatch[] {null,
            new ProofBatch(List.of(), null, COVERAGE, false, cause)}) {
          var pending = savedHistory(true);
          assertEquals(new ChangedPageTrackerStartup.StartupResult(false, false,
              ChangedPageTrackerStartup.Outcome.COVERAGE_NOT_PROVED, null,
              batch == null ? null : cause),
              ChangedPageTrackerStartup.resolve(pending, batch, shutdown, identityUnresolved));
          assertDistrusted(pending);
        }
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
              ChangedPageTrackerStartup.resolve(pending, batch, null, false));
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
        ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED,
        ChangedPageTrackerStartup.Outcome.PROVED_IDENTITY_UNRESOLVED)) {
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
                ChangedPageTrackerStartup.ShutdownState.CLEAN, false));
        assertDistrusted(pending);
        assertTrue(logs.warnedWithAll("outcome=COVERAGE_NOT_PROVED", "status=" + status,
            "cause=" + cause));
      }
      var unresolved = savedHistory(true);
      ChangedPageTrackerStartup.resolve(unresolved,
          new ProofBatch(List.of(), ProofStatus.REACHED_END, COVERAGE, false, null),
          ChangedPageTrackerStartup.ShutdownState.CLEAN, true);
      assertTrue(logs.warnedWithAll("outcome=PROVED_IDENTITY_UNRESOLVED", "status=REACHED_END"));
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
            ChangedPageTrackerStartup.ShutdownState.CLEAN, false));
    assertEquals(ChangedPageTrackerStartup.Resolution.KEPT, pending.resolution());
    assertTrue(pending.tracker().isTrusted());
    ChangedPageTrackerStartup.replayFailed(pending);
    ChangedPageTrackerStartup.replayFailed(pending);
    assertEquals(new ChangedPageTrackerStartup.StartupResult(true, false,
        ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED, ProofStatus.REACHED_END, null),
        ChangedPageTrackerStartup.resolve(pending, batch,
            ChangedPageTrackerStartup.ShutdownState.CLEAN, false));
    assertDistrusted(pending);
    assertEquals(1, capture(pending.tracker()).failureVersion());

    // Tracker-level invalidation can precede resolution. A KEPT resolution still cannot report
    // trusted history when the tracker has already lost continuity.
    var invalidated = savedHistory(true);
    invalidated.tracker().invalidate();
    assertEquals(new ChangedPageTrackerStartup.StartupResult(true, false,
        ChangedPageTrackerStartup.Outcome.PROVED_HISTORY_UNTRUSTED, ProofStatus.REACHED_END, null),
        ChangedPageTrackerStartup.resolve(invalidated, batch,
            ChangedPageTrackerStartup.ShutdownState.CLEAN, false));
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

  // Logging itself can fail when memory is exhausted. Trust is still revoked and the original
  // reader Error must reach the caller instead of the secondary logging Error.
  @Test
  public void loggingFailureCannotReplaceTheReaderFailure() throws Exception {
    var pending = savedHistory(true);
    var failure = new OutOfMemoryError("reader failure");
    try (var logger = mockStatic(LogManager.class)) {
      logger.when(LogManager::instance).thenThrow(new OutOfMemoryError("logging failure"));
      assertSame(failure, assertThrows(OutOfMemoryError.class,
          () -> ChangedPageTrackerStartup.readerFailed(pending, failure)));
    }
    assertDistrusted(pending);
  }

  private ChangedPageTrackerStartup.PendingHistory savedHistory(boolean trusted) throws Exception {
    var path = sideFile();
    Files.write(path, encode(trusted, true));
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
