package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.memory.DirectMemoryStorage;
import java.util.List;
import org.junit.Test;

/** Covers signed-boundary arithmetic used while storage installs durable operation evidence. */
public class OperationIdFloorTest {

  /**
   * A real memory close and reopen preserves the generator progress of its reused instance.
   *
   * <p>A memory manager reuses one storage instance, so this scenario closes that instance through
   * the production forced-close path and then reopens it. The forced close stops every background
   * worker the first open started, so the reopen owns all its own background work and leaves no
   * orphan behind. The memory image survives that close, because closing an in-memory cache keeps
   * its files.
   *
   * <p>The expected outcome is a reopen that keeps the earlier generator progress and issues higher
   * identifiers for the operations of its own open.
   */
  @Test
  public void memoryStorageOpenPreservesExistingGeneratorProgress() {
    try (var manager = (YouTrackDBImpl) YourTracks.instance("memory-floor-test")) {
      manager.create("memoryFloor", DatabaseType.MEMORY, "admin", "admin", "admin");
      DirectMemoryStorage storage;
      try (var session = manager.open("memoryFloor", "admin", "admin")) {
        storage = (DirectMemoryStorage) session.getStorage();
        session.begin();
        session.newEntity();
        session.commit();
      }

      // The forced close is the production close path of this storage. It stops the stale
      // transaction monitor and every other background worker of the first open.
      storage.close(null, true);
      assertThat(storage.isClosed(null)).isTrue();

      // The mark is read after the close, so the assertion below measures the reopen alone.
      var beforeReopen = storage.getIdGen().getLastId();
      storage.open(new ContextConfiguration());

      assertThat(storage.getIdGen().getLastId()).isGreaterThan(beforeReopen);
    }
  }

  /** A near-boundary floor has one representable next identifier without wrapping. */
  @Test
  public void nearBoundaryFloorProducesMaximumIdentifier() {
    assertThat(AbstractStorage.nextOperationId(Long.MAX_VALUE - 1, "test"))
        .isEqualTo(Long.MAX_VALUE);
  }

  /** An exhausted floor is rejected before startup or table arithmetic can wrap. */
  @Test
  public void exhaustedFloorHasNoNextIdentifier() {
    assertThatThrownBy(() -> AbstractStorage.nextOperationId(Long.MAX_VALUE, "test open"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exhausted")
        .hasMessageContaining("test open");
  }

  /**
   * WAL recovery raises a lower installed floor to its higher completed operation evidence.
   *
   * <p>This test covers the replay arithmetic alone. Real open wiring of the same rule lives in
   * {@code DiskStorageBootstrapWiringTest.crashOpenAdoptsRecoveredEvidenceAboveOpeningFloor}.
   */
  @Test
  public void recoveryRaisesInstalledFloorFromHigherWalEvidence() throws Exception {
    var storage = new DirectMemoryStorage("recovery", "recovery", 1, null);
    storage.getIdGen().advanceToAtLeast(100);

    storage.restoreFrom(walWithCompletedOperation(120), new LogSequenceNumber(1, 1));

    assertThat(storage.getIdGen().getLastId()).isEqualTo(120);
  }

  /**
   * WAL recovery leaves a higher installed floor unchanged when replay evidence is lower.
   *
   * <p>This test covers the replay arithmetic alone. Real open wiring of the same rule lives in
   * {@code DiskStorageBootstrapWiringTest.crashOpenKeepsOpeningFloorAboveRecoveredEvidence}.
   */
  @Test
  public void recoveryRetainsInstalledFloorAboveWalEvidence() throws Exception {
    var storage = new DirectMemoryStorage("recovery", "recovery", 1, null);
    storage.getIdGen().advanceToAtLeast(120);

    storage.restoreFrom(walWithCompletedOperation(100), new LogSequenceNumber(1, 1));

    assertThat(storage.getIdGen().getLastId()).isEqualTo(120);
  }

  /** Recovery accepts the near-boundary high-water mark and rejects the exhausted value. */
  @Test
  public void recoveryEvidenceMustLeaveOneUsableIdentifier() {
    assertThat(
        AbstractStorage.requireUsableOperationIdFloor(
            Long.MAX_VALUE - 1, "write-ahead log recovery"))
        .isEqualTo(Long.MAX_VALUE - 1);
    assertThatThrownBy(
        () -> AbstractStorage.requireUsableOperationIdFloor(
            Long.MAX_VALUE, "write-ahead log recovery"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("write-ahead log recovery");
  }

  private static WriteAheadLog walWithCompletedOperation(long operationId) throws Exception {
    var wal = mock(WriteAheadLog.class);
    var startLsn = new LogSequenceNumber(1, 1);
    var endLsn = new LogSequenceNumber(1, 2);
    var start = new AtomicUnitStartRecord(false, operationId);
    start.setLsn(startLsn);
    var end = new AtomicUnitEndRecord(operationId, false, null);
    end.setLsn(endLsn);
    when(wal.read(startLsn, 1_000)).thenReturn(List.of(start, end));
    when(wal.next(endLsn, 1_000)).thenReturn(List.of());
    return wal;
  }
}
