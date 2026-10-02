package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link StorageStartupMetadata}. The metadata file persists a dirty flag,
 * the last transaction id, and the version string at which the database was last opened.
 * Tests pin creation, reopen, dirty-state transitions, and transaction-id persistence.
 * Interrupted-write tests check recovery from a complete backup under the main-file lock.
 *
 * <p>Per project rules every temp path includes a UUID suffix so concurrent surefire forks
 * do not collide, and tests delete on @After (no JVM-shutdown cleanup).
 */
@Category(SequentialTest.class)
public class StorageStartupMetadataTest {

  private Path tmpDir;
  private Path filePath;
  private Path backupPath;

  @Before
  public void setUp() throws IOException {
    var suffix = UUID.randomUUID();
    tmpDir = Files.createTempDirectory("youtrackdb-startup-metadata-test-" + suffix);
    filePath = tmpDir.resolve("dirty.fl");
    backupPath = tmpDir.resolve("dirty.fl.bk");
  }

  @After
  public void tearDown() throws IOException {
    if (tmpDir != null && Files.exists(tmpDir)) {
      try (Stream<Path> walk = Files.walk(tmpDir)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
          try {
            Files.deleteIfExists(p);
          } catch (IOException ignored) {
            // best effort; OS will clean tmp eventually but project rule requires no JVM-shutdown
          }
        });
      }
    }
  }

  /**
   * Disabling storage fsync does not remove synchronous open options from startup metadata.
   */
  @Test
  public void testCreateKeepsSyncOptionWhenStorageFsyncIsDisabled() throws IOException {
    final var previousFsync = GlobalConfiguration.STORAGE_CALL_FSYNC.getValue();
    final var previousFileLock = GlobalConfiguration.FILE_LOCK.getValue();
    GlobalConfiguration.STORAGE_CALL_FSYNC.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class)) {
      final var channel = mock(FileChannel.class);
      when(channel.write(any(ByteBuffer.class), anyLong()))
          .thenAnswer(
              invocation -> {
                final ByteBuffer buffer = invocation.getArgument(0);
                final var remaining = buffer.remaining();
                buffer.position(buffer.limit());
                return remaining;
              });
      channels
          .when(
              () -> FileChannel.open(
                  filePath,
                  StandardOpenOption.READ,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.SYNC))
          .thenReturn(channel);
      channels
          .when(
              () -> FileChannel.open(
                  backupPath,
                  StandardOpenOption.READ,
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.SYNC))
          .thenReturn(channel);

      final var metadata = new StorageStartupMetadata(filePath, backupPath);
      metadata.create("sync-option-test");

      channels.verify(
          () -> FileChannel.open(
              filePath,
              StandardOpenOption.READ,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.SYNC));
      channels.verify(
          () -> FileChannel.open(
              backupPath,
              StandardOpenOption.READ,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              StandardOpenOption.SYNC));
    } finally {
      GlobalConfiguration.STORAGE_CALL_FSYNC.setValue(previousFsync);
      GlobalConfiguration.FILE_LOCK.setValue(previousFileLock);
    }
  }

  /**
   * A freshly created metadata file is dirty (a database that has not yet had a clean shutdown
   * is dirty by definition), reports {@code lastTxId == -1}, and exposes the version string
   * passed to {@link StorageStartupMetadata#create(String)}. The persisted file exists on
   * disk after {@code create()}.
   */
  @Test
  public void testCreateInitialState() throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("0.5.0-test");
    try {
      assertThat(meta.exists()).isTrue();
      assertThat(meta.isDirty()).isTrue();
      assertThat(meta.getLastTxId()).isEqualTo(-1L);
      assertThat(meta.getOpenedAtVersion()).isEqualTo("0.5.0-test");
      assertThat(Files.exists(filePath)).isTrue();
    } finally {
      meta.close();
    }
  }

  /**
   * After {@code create()} writes the file, a fresh instance opening the same path reads the
   * persisted dirty flag, lastTxId, and openedAtVersion via the xxhash-checked path.
   */
  @Test
  public void testOpenAfterCreateReadsPersistedState() throws IOException {
    var first = new StorageStartupMetadata(filePath, backupPath);
    first.create("first-version");
    first.close();

    var second = new StorageStartupMetadata(filePath, backupPath);
    second.open("ignored-because-file-exists");
    try {
      assertThat(second.isDirty()).isTrue();
      assertThat(second.getLastTxId()).isEqualTo(-1L);
      assertThat(second.getOpenedAtVersion()).isEqualTo("first-version");
    } finally {
      second.close();
    }
  }

  /**
   * {@code clearDirty()} flips the persisted flag from dirty to clean and the change survives
   * close/reopen. {@code makeDirty} then flips it back.
   */
  @Test
  public void testClearDirtyAndMakeDirtyRoundTrip() throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    assertThat(meta.isDirty()).isTrue();

    meta.clearDirty();
    assertThat(meta.isDirty()).isFalse();
    meta.close();

    var reopened = new StorageStartupMetadata(filePath, backupPath);
    reopened.open("ignored");
    assertThat(reopened.isDirty()).as("clearDirty must persist across reopen").isFalse();

    reopened.makeDirty("v2");
    assertThat(reopened.isDirty()).isTrue();
    reopened.close();

    var thirdOpen = new StorageStartupMetadata(filePath, backupPath);
    thirdOpen.open("ignored");
    assertThat(thirdOpen.isDirty()).as("makeDirty must persist across reopen").isTrue();
    thirdOpen.close();
  }

  /**
   * {@code clearDirty()} is idempotent: calling it twice on a clean metadata is a no-op
   * (the early-return branch). The state remains consistent across the second call.
   */
  @Test
  public void testClearDirtyIsIdempotent() throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    meta.clearDirty();

    // Second clearDirty must be a no-op (volatile flag is already false).
    meta.clearDirty();
    assertThat(meta.isDirty()).isFalse();
    meta.close();
  }

  /**
   * {@code makeDirty} on an already-dirty metadata returns early without re-writing the file
   * (volatile flag check + double-checked locking). The version field is not updated in this
   * case.
   */
  @Test
  public void testMakeDirtyOnAlreadyDirtyIsIdempotent() throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    assertThat(meta.isDirty()).isTrue();

    // Already dirty: makeDirty must return early without touching openedAtVersion.
    meta.makeDirty("v2");
    assertThat(meta.getOpenedAtVersion())
        .as("makeDirty on already-dirty must NOT overwrite openedAtVersion")
        .isEqualTo("v1");
    meta.close();
  }

  /**
   * {@code setLastTxId(N)} persists the new id and a subsequent open reads it back.
   */
  @Test
  public void testSetLastTxIdRoundTrip() throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    meta.setLastTxId(12345L);
    assertThat(meta.getLastTxId()).isEqualTo(12345L);
    meta.close();

    var reopened = new StorageStartupMetadata(filePath, backupPath);
    reopened.open("ignored");
    assertThat(reopened.getLastTxId()).isEqualTo(12345L);
    reopened.close();
  }

  /**
   * {@code close()} on a never-opened metadata is a no-op.
   */
  @Test
  public void testCloseOnNeverOpenedIsNoOp() throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.close(); // must not throw
    assertThat(meta.exists()).isFalse();
  }

  /**
   * {@code delete()} removes the underlying file. A subsequent {@code exists()} returns
   * false.
   */
  @Test
  public void testDeleteRemovesFile() throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    assertThat(meta.exists()).isTrue();

    meta.delete();
    assertThat(meta.exists()).isFalse();
  }

  /**
   * {@code delete()} on a never-opened metadata is a no-op (channel is null, early return).
   */
  @Test
  public void testDeleteOnNeverOpenedIsNoOp() throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.delete(); // must not throw
    assertThat(meta.exists()).isFalse();
  }

  /**
   * Opening a non-existent metadata file with no backup automatically creates a new one
   * (the open branch initializes the newly locked main when neither path exists).
   */
  @Test
  public void testOpenWithNoFileCreatesNew() throws IOException {
    assertThat(Files.exists(filePath)).isFalse();
    assertThat(Files.exists(backupPath)).isFalse();

    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.open("created-on-open");
    try {
      assertThat(meta.exists()).isTrue();
      assertThat(meta.isDirty()).isTrue();
      assertThat(meta.getOpenedAtVersion()).isEqualTo("created-on-open");
    } finally {
      meta.close();
    }
  }

  /**
   * If the persisted file's xxhash check fails and no backup exists, {@code open()}
   * initializes the locked main with fresh state and logs an error.
   */
  @Test
  public void testOpenWithCorruptFileNoBackupRecreates() throws IOException {
    // Create a valid file first.
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    meta.setLastTxId(99L);
    meta.close();

    // Corrupt it by overwriting the leading hash with random bytes.
    try (var ch =
        FileChannel.open(filePath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      var buf = ByteBuffer.allocate(8);
      buf.putLong(0xDEADBEEFCAFEBABEL);
      buf.flip();
      ch.write(buf, 0);
    }

    var reopened = new StorageStartupMetadata(filePath, backupPath);
    reopened.open("v2");
    try {
      // Recovery path: fresh state, dirty flag set, lastTxId reset to -1.
      assertThat(reopened.isDirty()).isTrue();
      assertThat(reopened.getLastTxId())
          .as("corruption recovery resets lastTxId to fresh sentinel")
          .isEqualTo(-1L);
      assertThat(reopened.getOpenedAtVersion()).isEqualTo("v2");
    } finally {
      reopened.close();
    }
  }

  /**
   * After {@code create()}, calling {@code create()} again on the same instance overwrites
   * the file. The previously-persisted lastTxId is reset to the sentinel.
   */
  @Test
  public void testCreateOverridesExistingFile() throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    meta.setLastTxId(7L);
    assertThat(meta.getLastTxId()).isEqualTo(7L);

    // Re-create on the same instance: file is deleted+recreated; lastTxId resets to -1.
    meta.create("v2");
    assertThat(meta.isDirty()).isTrue();
    assertThat(meta.getLastTxId()).isEqualTo(-1L);
    assertThat(meta.getOpenedAtVersion()).isEqualTo("v2");
    meta.close();
  }

  /**
   * Calling {@code clearDirty()} before any {@code create()} or {@code open()} is a silent
   * no-op: the volatile {@code dirtyFlag} is false on a fresh instance, so the early-return
   * path fires before any IO is attempted on the null channel. In contrast, makeDirty tries
   * to write and fails when there is no channel.
   */
  @Test
  public void testClearDirtyOnUninitialisedIsSilentNoOp() {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    // Pin the early-return contract: clearDirty on a fresh instance returns without IO.
    try {
      meta.clearDirty();
    } catch (IOException e) {
      throw new AssertionError("unexpected", e);
    }
    assertThat(meta.isDirty()).isFalse();
  }

  /**
   * {@code makeDirty} on an uninitialised metadata throws because no channel is open and
   * the call falls through past the early-return guard (the flag is false, so the lock
   * is acquired and the update tries to write to a null channel).
   */
  @Test
  public void testMakeDirtyOnUninitialisedThrows() {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    // Without create/open the volatile flag is false, the early-return is skipped, and
    // update() tries to write to a null channel — pin the resulting NullPointerException.
    assertThatThrownBy(() -> meta.makeDirty("v1"))
        .isInstanceOf(NullPointerException.class);
  }

  /** A checksum-failed main is repaired through its locked channel from the valid backup. */
  @Test
  public void testOpenWithCorruptPrimaryAndIntactBackupRecoversFromBackup() throws IOException {
    // Create a valid primary, then setLastTxId so a real value persists.
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("backup-recover-version");
    meta.setLastTxId(4242L);
    meta.close();

    // The production update() deletes the backup on success, so we must hand-create the
    // backup ourselves: copy the valid primary contents into the backup path, then corrupt
    // the primary. This simulates the rare crash window where the primary write was torn
    // (or the file was tampered with) while the backup is still on disk.
    Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
    try (var ch =
        FileChannel.open(filePath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      var buf = ByteBuffer.allocate(8);
      buf.putLong(0xDEADBEEFCAFEBABEL);
      buf.flip();
      ch.write(buf, 0);
    }

    var reopened = new StorageStartupMetadata(filePath, backupPath);
    reopened.open("ignored-because-recovery-from-backup");
    try {
      // Recovery from backup: pre-corruption values must be recovered verbatim.
      assertThat(reopened.isDirty())
          .as("backup recovery must restore the pre-corruption dirty flag")
          .isTrue();
      assertThat(reopened.getLastTxId())
          .as("backup recovery must restore the pre-corruption lastTxId")
          .isEqualTo(4242L);
      assertThat(reopened.getOpenedAtVersion())
          .as("backup recovery must restore the pre-corruption openedAtVersion")
          .isEqualTo("backup-recover-version");
      assertThat(Files.exists(backupPath)).isFalse();
    } finally {
      reopened.close();
    }
  }

  /** An intact old main wins over a newer backup left after the backup write. */
  @Test
  public void intactMainWinsAndRemovesBackup() throws Exception {
    var meta = prepared(33);
    var oldMain = Files.readAllBytes(filePath);
    meta.setLastTxId(44);
    meta.close();
    Files.copy(filePath, backupPath);
    Files.write(filePath, oldMain);
    assertReopened(33, false);
  }

  /** Empty and partly written mains, including legacy-sized prefixes, use the complete backup. */
  @Test
  public void interruptedMainUsesBackupAtAllShortLengths() throws Exception {
    var meta = prepared(4242);
    meta.close();
    var complete = Files.readAllBytes(filePath);
    for (int length : new int[] {0, 1, 5, 9, 17, complete.length - 1}) {
      Files.write(filePath, Arrays.copyOf(complete, length));
      Files.write(backupPath, complete);
      assertReopened(4242, true);
      assertThat(Files.readAllBytes(filePath)).isEqualTo(complete);
    }
  }

  /** Neither a short backup nor a checksummed but incomplete backup can repair the main. */
  @Test
  public void invalidBackupCannotRepairBrokenMain() throws Exception {
    var meta = prepared(4242);
    meta.close();
    var complete = Files.readAllBytes(filePath);
    Files.write(filePath, Arrays.copyOf(complete, 17));
    Files.write(backupPath, Arrays.copyOf(complete, 9));
    assertReopened(-1, false);

    // Retain a correct checksum while declaring a version-string length beyond the actual file.
    var incomplete = ByteBuffer.wrap(complete.clone());
    incomplete.putInt(25, complete.length);
    var hash = XXHashFactory.fastestInstance().hash64()
        .hash(incomplete, 8, incomplete.capacity() - 8, 0xADF678FE45L);
    incomplete.putLong(0, hash);
    Files.write(filePath, Arrays.copyOf(complete, 17));
    Files.write(backupPath, incomplete.array());
    assertReopened(-1, false);
  }

  /** A complete backup with a flipped checksum or payload cannot repair a broken main. */
  @Test
  public void invalidChecksummedBackupCannotRepairBrokenMain() throws Exception {
    var meta = prepared(4242);
    meta.close();
    var complete = Files.readAllBytes(filePath);
    for (int offset : new int[] {0, 12}) {
      Files.write(filePath, Arrays.copyOf(complete, 17));
      var invalid = complete.clone();
      invalid[offset] ^= 1;
      Files.write(backupPath, invalid);
      assertReopened(-1, false);
      assertThat(Files.readAllBytes(filePath)).isNotEqualTo(invalid);
    }
  }

  /** A missing main is restored durably from the backup without replacing its locked inode. */
  @Test
  public void missingMainRestoresBackupWithoutReplacingLockedInode() throws Exception {
    var meta = prepared(4242);
    meta.close();
    Files.move(filePath, backupPath);
    var expected = Files.readAllBytes(backupPath);
    assertReopened(4242, true);
    assertThat(Files.readAllBytes(filePath)).isEqualTo(expected);
    assertThat(Files.exists(backupPath)).isFalse();
    var fresh = new StorageStartupMetadata(filePath, backupPath);
    fresh.open("ignored");
    try {
      assertThat(fresh.isDirty()).isTrue();
      assertThat(fresh.getLastTxId()).isEqualTo(4242);
    } finally {
      fresh.close();
    }
  }

  /** Disk storage must read the backup even if its main metadata file disappeared. */
  @Test
  public void diskOpenWithMissingMainRetainsStartupFloor() throws Exception {
    var database = "missingMain";
    try (var manager = YourTracks.instance(tmpDir.toString())) {
      manager.create(database, DatabaseType.DISK, "admin", "admin", "admin");
    }
    var main = tmpDir.resolve(database).resolve("dirty.fl");
    var backup = tmpDir.resolve(database).resolve("dirty.flb");
    var metadata = new StorageStartupMetadata(main, backup);
    metadata.open("existing");
    try {
      metadata.setLastTxId(1_000_000);
    } finally {
      metadata.close();
    }
    Files.move(main, backup);
    try (var manager = (YouTrackDBImpl) YourTracks.instance(tmpDir.toString());
        var session = manager.open(database, "admin", "admin")) {
      assertThat(((AbstractStorage) session.getStorage()).getIdGen().getLastId())
          .isGreaterThan(1_000_000);
      // Disk startup can replace the restored bytes with a newer complete state.
      assertThat(Files.exists(main)).isTrue();
      assertThat(Files.exists(backup)).isFalse();
    }
    assertThat(Files.exists(main)).isTrue();
    assertThat(Files.exists(backup)).isFalse();
    var persisted = Files.readAllBytes(main);
    assertThat(persisted).isNotEmpty();
    // Disk startup and clean shutdown can advance the floor and dirty flag after repair.
    var fresh = new StorageStartupMetadata(main, backup);
    fresh.open("ignored");
    try {
      assertThat(fresh.getLastTxId()).isGreaterThanOrEqualTo(1_000_000);
      assertThat(fresh.isDirty()).isFalse();
      assertThat(Files.readAllBytes(main)).isEqualTo(persisted);
      assertThat(Files.exists(backup)).isFalse();
    } finally {
      fresh.close();
    }
  }

  /** A 2–8 byte main is not a legacy file: it starts dirty at the unknown floor. */
  @Test
  public void incompleteLegacyLengthsWithoutBackupInitializeUnknownFloor() throws Exception {
    for (int length = 2; length <= 8; length++) {
      Files.write(filePath, new byte[length]);
      var fresh = new StorageStartupMetadata(filePath, backupPath);
      fresh.open("fallback");
      try {
        assertThat(fresh.isDirty()).as("length %s must not be clean", length).isTrue();
        assertThat(fresh.getLastTxId()).as("length %s has no known floor", length)
            .isEqualTo(-1);
        assertThat(fresh.getOpenedAtVersion()).isEqualTo("fallback");
      } finally {
        fresh.close();
      }
      assertThat(Files.exists(backupPath)).isFalse();
    }
  }

  private StorageStartupMetadata prepared(long floor) throws IOException {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    meta.setLastTxId(floor);
    return meta;
  }

  private void assertReopened(long floor, boolean warn) throws Exception {
    try (var logs = LogRecordCollector.attachTo(StorageStartupMetadata.class)) {
      var reopened = new StorageStartupMetadata(filePath, backupPath);
      reopened.open("fallback");
      try {
        assertThat(reopened.getLastTxId()).isEqualTo(floor);
        assertThat(Files.exists(backupPath)).isFalse();
        assertThat(logs.warnedWithAll("backup")).isEqualTo(warn);
      } finally {
        reopened.close();
      }
    }
  }

  /** Concurrent writers wait for one synced dirty update, not one update per writer. */
  @Test(timeout = 30_000)
  public void concurrentDirtyWritersPublishOneUpdate() throws Exception {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    try {
      meta.clearDirty();
      var updating = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var updates = new AtomicInteger();
      meta.countDirtyUpdatesForTesting(updates);
      meta.setBeforeDirtyUpdateActionForTesting(() -> {
        updating.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("writer did not release the metadata lock");
          }
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failure);
        }
      });
      assertThatThrownBy(() -> meta.setBeforeDirtyUpdateActionForTesting(() -> {
      })).isInstanceOf(IllegalStateException.class);
      var first = CompletableFuture.runAsync(() -> {
        try {
          meta.makeDirty("v1");
        } catch (IOException failure) {
          throw new RuntimeException(failure);
        }
      });
      try {
        assertThat(updating.await(10, TimeUnit.SECONDS)).isTrue();
        var secondThread = new AtomicReference<Thread>();
        var second = CompletableFuture.runAsync(() -> {
          secondThread.set(Thread.currentThread());
          try {
            meta.makeDirty("v1");
          } catch (IOException failure) {
            throw new RuntimeException(failure);
          }
        });
        // The first writer still holds the lock when the second parks in makeDirty.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !waitingInMakeDirty(secondThread.get())) {
          Thread.sleep(1);
        }
        assertThat(waitingInMakeDirty(secondThread.get())).isTrue();
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertThat(updates.get()).isEqualTo(1);
        assertThat(meta.isDurablyDirty()).isTrue();
      } finally {
        release.countDown();
      }
    } finally {
      meta.close();
    }
  }

  private static boolean waitingInMakeDirty(Thread thread) {
    if (thread == null || thread.getState() != Thread.State.WAITING) {
      return false;
    }
    return Arrays.stream(thread.getStackTrace())
        .anyMatch(frame -> frame.getClassName().equals(StorageStartupMetadata.class.getName())
            && frame.getMethodName().equals("makeDirty"));
  }

  /** A floor rewrite of a dirty image must not park an admitted writer on metadata IO. */
  @Test(timeout = 30_000)
  public void dirtyWriterSkipsWhileFloorRewritesDirtyMain() throws Exception {
    var initial = prepared(42);
    initial.close();
    var reachedMain = new AtomicBoolean();
    var blockMain = new AtomicBoolean();
    var live = new StorageStartupMetadata(filePath, backupPath);
    try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      channels.when(() -> FileChannel.open(filePath, StandardOpenOption.SYNC,
          StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE))
          .thenAnswer(call -> {
            var main = spy(new RandomAccessFile(filePath.toFile(), "rws").getChannel());
            doAnswer(write -> {
              if (blockMain.get()) {
                reachedMain.set(true);
                assertThat(Files.readAllBytes(backupPath)[12]).isEqualTo((byte) 1);
                // The floor thread holds the metadata lock until this writer returns.
                CompletableFuture.runAsync(() -> {
                  try {
                    live.makeDirty("v1");
                  } catch (IOException failure) {
                    throw new RuntimeException(failure);
                  }
                }).get(5, TimeUnit.SECONDS);
              }
              return write.callRealMethod();
            }).when(main).write(any(ByteBuffer.class), anyLong());
            return main;
          });
      live.open("v1");
      try {
        blockMain.set(true);
        live.publishLastTxIdFloor(10_000);
        assertThat(reachedMain.get()).isTrue();
      } finally {
        live.close();
      }
    }
  }

  /** A failed dirty write changes memory but never permits skipping the next disk update. */
  @Test
  public void failedDirtyWriteMustRetryDespiteInMemoryDirtyFlag() throws Exception {
    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.create("v1");
    try {
      meta.clearDirty();
      assertThat(meta.isDurablyDirty()).isFalse();
      Files.createDirectory(backupPath);
      Files.createFile(backupPath.resolve("block-delete"));
      assertThatThrownBy(() -> meta.makeDirty("v1")).isInstanceOf(IOException.class);
      assertThat(meta.isDirty()).isTrue();
      assertThat(meta.isDurablyDirty()).isFalse();
      Files.delete(backupPath.resolve("block-delete"));
      Files.delete(backupPath);
      meta.makeDirty("v1");
      assertThat(meta.isDurablyDirty()).isTrue();
    } finally {
      meta.close();
    }
    var reopened = new StorageStartupMetadata(filePath, backupPath);
    reopened.open("v1");
    try {
      assertThat(reopened.isDirty()).isTrue();
    } finally {
      reopened.close();
    }
  }

  /** A failed clean main write requires the next writer to restore a durable dirty indication. */
  @Test
  public void failedClearRequiresNextWriterToMarkDurably() throws Exception {
    withFailedClear((live, failBackup) -> {
      assertThat(live.isDirty()).isTrue();
      assertThat(live.isDurablyDirty()).isFalse();
      live.makeDirty("v1");
      assertThat(live.isDurablyDirty()).isTrue();
      assertThat(Files.readAllBytes(filePath)[12]).isEqualTo((byte) 1);
    });
  }

  /** Floor publication after a failed clear cannot persist an unprotected clean image. */
  @Test
  public void failedClearThenFloorPublicationKeepsRecoveryEnabled() throws Exception {
    withFailedClear((live, failBackup) -> {
      live.publishLastTxIdFloor(10_000);
      assertThat(Files.readAllBytes(filePath)[12]).isEqualTo((byte) 1);
      assertThat(live.isDurablyDirty()).isTrue();
    });
  }

  /** Repairing from a clean backup must discard the old dirty confirmation on backup failure. */
  @Test
  public void repairedCleanMainWithFailedBackupDoesNotLetNextWriterSkip() throws Exception {
    withFailedClear((live, failBackup) -> {
      failBackup.set(true);
      assertThatThrownBy(() -> live.makeDirty("v1"))
          .isInstanceOf(IOException.class).hasMessage("backup write interrupted");
      assertThat(Files.readAllBytes(filePath)[12]).isEqualTo((byte) 0);
      assertThat(live.isDurablyDirty()).isFalse();
      live.makeDirty("v1");
      assertThat(Files.readAllBytes(filePath)[12]).isEqualTo((byte) 1);
    });
  }

  private void withFailedClear(FailedClearAction action) throws Exception {
    var initial = prepared(42);
    initial.close();
    var failMain = new AtomicBoolean();
    var failBackup = new AtomicBoolean();
    try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
      channels.when(() -> FileChannel.open(filePath, StandardOpenOption.SYNC,
          StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE))
          .thenAnswer(call -> {
            var main = spy(new RandomAccessFile(filePath.toFile(), "rws").getChannel());
            doAnswer(write -> {
              if (failMain.getAndSet(false)) {
                throw new IOException("clean main write interrupted");
              }
              return write.callRealMethod();
            }).when(main).write(any(ByteBuffer.class), anyLong());
            return main;
          });
      channels.when(() -> FileChannel.open(backupPath, StandardOpenOption.READ,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC))
          .thenAnswer(call -> {
            if (failBackup.getAndSet(false)) {
              throw new IOException("backup write interrupted");
            }
            return call.callRealMethod();
          });
      var live = new StorageStartupMetadata(filePath, backupPath);
      live.open("v1");
      try {
        failMain.set(true);
        assertThatThrownBy(live::clearDirty)
            .isInstanceOf(IOException.class).hasMessage("clean main write interrupted");
        assertThat(Files.readAllBytes(backupPath)[12]).isEqualTo((byte) 0);
        action.accept(live, failBackup);
      } finally {
        live.close();
      }
    }
  }

  @FunctionalInterface
  private interface FailedClearAction {

    void accept(StorageStartupMetadata metadata, AtomicBoolean failBackup) throws Exception;
  }

  /** A failed main write followed by a failed later backup write retains the old floor. */
  @Test
  public void failedWriteThenInterruptedNextWriteRestoresMainBeforeDeletingBackup()
      throws Exception {
    var first = prepared(4242);
    first.close();
    var mainOpen = new StandardOpenOption[] {StandardOpenOption.SYNC, StandardOpenOption.WRITE,
        StandardOpenOption.READ, StandardOpenOption.CREATE};
    var backupOpen = new StandardOpenOption[] {StandardOpenOption.READ,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC};
    var writes = new AtomicInteger();
    var backupWrites = new AtomicInteger();
    try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class)) {
      channels.when(() -> FileChannel.open(backupPath, StandardOpenOption.READ))
          .thenAnswer(call -> new RandomAccessFile(backupPath.toFile(), "r").getChannel());
      channels.when(() -> FileChannel.open(filePath, mainOpen)).thenAnswer(call -> {
        var main = spy(new RandomAccessFile(filePath.toFile(), "rws").getChannel());
        doAnswer(write -> {
          if (writes.getAndIncrement() == 0) {
            ByteBuffer data = write.getArgument(0);
            data.limit(data.position() + 1);
            main.write(data, 0);
            throw new IOException("first main write interrupted");
          }
          return write.callRealMethod();
        }).when(main).write(any(ByteBuffer.class), anyLong());
        return main;
      });
      channels.when(() -> FileChannel.open(backupPath, backupOpen)).thenAnswer(call -> {
        Files.createFile(backupPath);
        var backup = spy(new RandomAccessFile(backupPath.toFile(), "rws").getChannel());
        doAnswer(write -> {
          if (backupWrites.getAndIncrement() > 0) {
            throw new IOException("later backup write interrupted");
          }
          return write.callRealMethod();
        }).when(backup).write(any(ByteBuffer.class), anyLong());
        return backup;
      });
      var live = new StorageStartupMetadata(filePath, backupPath);
      live.open("ignored");
      try {
        assertThatThrownBy(() -> live.setLastTxId(5000)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> live.setLastTxId(6000)).isInstanceOf(IOException.class);
      } finally {
        live.close();
      }
    }
    assertReopened(5000, false);
  }

  /** A failed repair must keep the complete backup for the next open. */
  @Test
  public void failedRepairKeepsBackupForNextOpen() throws Exception {
    var first = prepared(4242);
    first.close();
    Files.copy(filePath, backupPath);
    Files.write(filePath, new byte[0]);
    var complete = Files.readAllBytes(backupPath);
    try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class)) {
      channels.when(() -> FileChannel.open(filePath, StandardOpenOption.SYNC,
          StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE))
          .thenAnswer(call -> {
            var main = spy(new RandomAccessFile(filePath.toFile(), "rws").getChannel());
            doAnswer(write -> {
              throw new IOException("repair interrupted");
            }).when(main).write(any(ByteBuffer.class), anyLong());
            return main;
          });
      channels.when(() -> FileChannel.open(backupPath, StandardOpenOption.READ))
          .thenAnswer(call -> new RandomAccessFile(backupPath.toFile(), "r").getChannel());
      assertThatThrownBy(() -> new StorageStartupMetadata(filePath, backupPath).open("ignored"))
          .isInstanceOf(IOException.class).hasMessage("repair interrupted");
    }
    assertThat(Files.readAllBytes(backupPath)).isEqualTo(complete);
    assertReopened(4242, true);
  }

  /** A missing main is locked before any backup bytes are written into it. */
  @Test
  public void missingMainIsLockedBeforeFailedFill() throws Exception {
    var first = prepared(4242);
    first.close();
    Files.move(filePath, backupPath);
    var complete = Files.readAllBytes(backupPath);
    var lockAttempted = new java.util.concurrent.atomic.AtomicBoolean();
    try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class)) {
      channels.when(() -> FileChannel.open(filePath, StandardOpenOption.SYNC,
          StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE))
          .thenAnswer(call -> {
            var main = spy(new RandomAccessFile(filePath.toFile(), "rws").getChannel());
            doAnswer(lockCall -> {
              lockAttempted.set(true);
              return lockCall.callRealMethod();
            }).when(main).tryLock();
            doAnswer(write -> {
              assertThat(lockAttempted.get()).isTrue();
              throw new IOException("fill interrupted");
            }).when(main).write(any(ByteBuffer.class), anyLong());
            return main;
          });
      channels.when(() -> FileChannel.open(backupPath, StandardOpenOption.READ))
          .thenAnswer(call -> new RandomAccessFile(backupPath.toFile(), "r").getChannel());
      assertThatThrownBy(() -> new StorageStartupMetadata(filePath, backupPath).open("ignored"))
          .isInstanceOf(IOException.class).hasMessage("fill interrupted");
    }
    assertThat(lockAttempted.get()).isTrue();
    assertThat(Files.exists(filePath)).isTrue();
    assertThat(Files.readAllBytes(backupPath)).isEqualTo(complete);
    assertReopened(4242, true);
  }

  /** While a parent holds the main-file lock, a child cannot repair or change either copy. */
  @Test
  public void secondProcessCannotRepairWhileFirstHoldsLock() throws Exception {
    var previousLock = GlobalConfiguration.FILE_LOCK.getValue();
    GlobalConfiguration.FILE_LOCK.setValue(true);
    try {
      var first = prepared(4242);
      first.close();
      var full = Files.readAllBytes(filePath);
      Files.write(filePath, Arrays.copyOf(full, 9));
      Files.write(backupPath, full);
      // Leave the interrupted files intact while holding the same main-inode process lock.
      // Calling open() in this process would repair them before the child reaches its reader.
      try (var parent = FileChannel.open(filePath, StandardOpenOption.READ,
          StandardOpenOption.WRITE); var held = parent.lock()) {
        assertLockedChildDoesNotChangeFiles();
      }
      assertReopened(4242, true);
    } finally {
      GlobalConfiguration.FILE_LOCK.setValue(previousLock);
    }
  }

  /** A failed live main write keeps the lock and backup while another process tries to open. */
  @Test
  public void secondProcessCannotRepairAfterFailedLiveWrite() throws Exception {
    var previousLock = GlobalConfiguration.FILE_LOCK.getValue();
    GlobalConfiguration.FILE_LOCK.setValue(true);
    try {
      var first = prepared(4242);
      first.close();
      try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class)) {
        channels.when(() -> FileChannel.open(filePath, StandardOpenOption.SYNC,
            StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.CREATE))
            .thenAnswer(call -> {
              var main = spy(new RandomAccessFile(filePath.toFile(), "rws").getChannel());
              doAnswer(write -> {
                throw new IOException("main write interrupted");
              }).when(main).write(any(ByteBuffer.class), anyLong());
              return main;
            });
        channels.when(() -> FileChannel.open(backupPath, StandardOpenOption.READ,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC))
            .thenAnswer(call -> {
              Files.createFile(backupPath);
              return new RandomAccessFile(backupPath.toFile(), "rws").getChannel();
            });
        var parent = new StorageStartupMetadata(filePath, backupPath);
        parent.open("ignored");
        try {
          assertThatThrownBy(() -> parent.setLastTxId(5000))
              .isInstanceOf(IOException.class).hasMessage("main write interrupted");
          assertLockedChildDoesNotChangeFiles();
        } finally {
          parent.close();
        }
      }
      assertReopened(5000, true);
    } finally {
      GlobalConfiguration.FILE_LOCK.setValue(previousLock);
    }
  }

  private void assertLockedChildDoesNotChangeFiles() throws Exception {
    // Opening and closing another channel on the locked inode can release a POSIX process lock.
    // The child checks the bytes while the parent's channel and lock remain untouched.
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var child =
        new ProcessBuilder(java, "-cp", classpath, StorageStartupMetadataTest.class.getName(),
            filePath.toString(), backupPath.toString()).redirectErrorStream(true).start();
    try {
      assertThat(child.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
      assertThat(child.exitValue())
          .withFailMessage("Lock child: %s", new String(child.getInputStream().readAllBytes()))
          .isZero();
    } finally {
      child.destroyForcibly();
    }
  }

  /** Child returns success only when the parent lock prevents startup metadata open. */
  public static void main(String[] arguments) throws Exception {
    GlobalConfiguration.FILE_LOCK.setValue(true);
    var mainPath = Path.of(arguments[0]);
    var backup = Path.of(arguments[1]);
    var mainBefore = Files.readAllBytes(mainPath);
    var backupBefore = Files.exists(backup) ? Files.readAllBytes(backup) : null;
    var metadata = new StorageStartupMetadata(mainPath, backup);
    try {
      metadata.open("child");
      metadata.close();
      throw new AssertionError("Child unexpectedly opened metadata");
    } catch (com.jetbrains.youtrackdb.internal.core.exception.StorageException expected) {
      assertThat(Files.readAllBytes(mainPath)).isEqualTo(mainBefore);
      if (backupBefore != null) {
        assertThat(Files.readAllBytes(backup)).isEqualTo(backupBefore);
      } else {
        assertThat(Files.exists(backup)).isFalse();
      }
    }
  }

  /**
   * Legacy 9-byte format: {@code [dirty:byte][lastTxId:long]}. The reader must populate
   * {@code dirtyFlag} and {@code lastTxId} from the raw bytes (no xxhash, no version, no
   * openedAtVersion). Pinned by the {@code size == 9} branch in {@code open()}.
   */
  @Test
  public void testOpenWithLegacy9ByteFileReadsLastTxId() throws IOException {
    // Hand-craft the legacy 9-byte file: byte 0 is the dirty flag (1 = dirty), bytes 1..8
    // are the lastTxId in BIG_ENDIAN order (the production reader uses
    // ByteBuffer.allocate(9) with no explicit order(), which defaults to BIG_ENDIAN, then
    // calls buffer.getLong() at position 1).
    var legacy = ByteBuffer.allocate(9); // default BIG_ENDIAN
    legacy.put((byte) 1);
    legacy.putLong(987654321L);
    legacy.flip();
    Files.write(filePath, legacy.array());

    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.open("createdAtVersion-ignored");
    try {
      assertThat(meta.isDirty())
          .as("legacy 9-byte format must restore dirty flag from byte 0")
          .isTrue();
      assertThat(meta.getLastTxId())
          .as("legacy 9-byte format must restore lastTxId from bytes 1..8")
          .isEqualTo(987654321L);
      // The legacy reader does not populate openedAtVersion; it remains the constructor default.
      assertThat(meta.getOpenedAtVersion())
          .as("legacy 9-byte format does not encode openedAtVersion")
          .isNull();
    } finally {
      meta.close();
    }
  }

  /**
   * Legacy 1-byte format: {@code [dirty:byte]} only. The reader must populate {@code
   * dirtyFlag} from the single byte and leave {@code lastTxId} at its default. Pinned by the
   * {@code size == 1} branch in {@code open()}.
   */
  @Test
  public void testOpenWithLegacyOneByteFileReadsDirtyFlag() throws IOException {
    // Hand-craft the legacy 1-byte file: byte 0 is the dirty flag (1 = dirty).
    Files.write(filePath, new byte[] {(byte) 1});

    var meta = new StorageStartupMetadata(filePath, backupPath);
    meta.open("createdAtVersion-ignored");
    try {
      assertThat(meta.isDirty())
          .as("legacy 1-byte format must restore dirty flag from byte 0")
          .isTrue();
      // The 1-byte legacy format does not encode lastTxId; it remains the default-initialised
      // value (0L for an instance freshly opened from a 1-byte file).
      assertThat(meta.getLastTxId())
          .as("legacy 1-byte format does not encode lastTxId; default-initialised value remains")
          .isEqualTo(0L);
      assertThat(meta.getOpenedAtVersion())
          .as("legacy 1-byte format does not encode openedAtVersion")
          .isNull();
    } finally {
      meta.close();
    }
  }
}
