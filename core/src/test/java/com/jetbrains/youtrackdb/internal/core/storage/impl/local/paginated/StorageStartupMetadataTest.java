package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.UUID;
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
 * Tests pin the four observable lifecycle behaviours: {@code create()} establishes a fresh
 * file with the dirty flag set; {@code open()} re-reads the persisted state; the
 * {@code makeDirty} / {@code clearDirty} pair flips the flag; {@code setLastTxId} updates
 * the persisted id. A corruption test exercises the xxhash-mismatch recovery path that
 * recreates the file when no backup is available.
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
   * (the open branch falls through to {@code create()} when neither path exists).
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
   * recreates the file fresh (logs an error and falls through to {@code create()}).
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
      // Recovery path: file recreated fresh, dirty flag set, lastTxId reset to -1.
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
   * path fires before any IO is attempted on the null channel.
   *
   * <p>This is asymmetric with {@code makeDirty()} (see
   * {@link #testMakeDirtyOnUninitialisedThrows}): {@code makeDirty} on the same fresh instance
   * falls through past the early-return guard (the flag is false, so the lock is acquired and
   * the update tries to write to a null channel) and NPEs. The asymmetry is pinned here
   * deliberately so a future refactor that unifies the two paths cannot silently change the
   * uninitialised behaviour without updating both pins.
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

  /**
   * Corruption-with-intact-backup recovery: when the primary file fails the xxhash check but
   * the backup file exists and is valid, {@code open()} deletes the corrupted primary,
   * re-enters the open loop, atomically moves the backup into the primary's place, and reads
   * the recovered metadata. The recovered values must match the pre-corruption state.
   */
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
      // After successful recovery the backup file is consumed (atomic move into primary).
      assertThat(Files.exists(backupPath))
          .as("backup file must be consumed after the atomic move")
          .isFalse();
    } finally {
      reopened.close();
    }
  }

  /**
   * Version 4 stores the dirty flag, transaction identifier, and opened-at version string.
   * Reading it must not confuse its field layout with version 3's shorter layout.
   */
  @Test
  public void testOpenVersion4ReadsAllFields() throws IOException {
    writeVersionedMetadata(4);
    var metadata = new StorageStartupMetadata(filePath, backupPath);
    try {
      metadata.open("ignored");
      assertThat(metadata.isDirty()).isFalse();
      assertThat(metadata.getLastTxId()).isEqualTo(42L);
      assertThat(metadata.getOpenedAtVersion()).isEqualTo("prior-build");
      assertThat(storedVersion()).isEqualTo(4);
    } finally {
      metadata.close();
    }
  }

  /** Version 5 has the same fields as version 4 and can be read without rewriting. */
  @Test
  public void testOpenVersion5ReadsAllFields() throws IOException {
    writeVersionedMetadata(5);
    var metadata = new StorageStartupMetadata(filePath, backupPath);
    try {
      metadata.open("ignored");
      assertThat(metadata.isDirty()).isFalse();
      assertThat(metadata.getLastTxId()).isEqualTo(42L);
      assertThat(metadata.getOpenedAtVersion()).isEqualTo("prior-build");
      assertThat(storedVersion()).isEqualTo(5);
    } finally {
      metadata.close();
    }
  }

  /** A newly created file and every later metadata update must write version 5. */
  @Test
  public void testCreateAndWriteEmitVersion5() throws IOException {
    var metadata = new StorageStartupMetadata(filePath, backupPath);
    try {
      metadata.create("new-build");
      assertThat(storedVersion()).isEqualTo(5);
      metadata.setLastTxId(42L);
      assertThat(storedVersion()).isEqualTo(5);
    } finally {
      metadata.close();
    }
  }

  /** Clean close writes the transaction identifier and upgrades a version 4 file to 5. */
  @Test
  public void testVersion4IsRewrittenAsVersion5OnCleanClose() throws IOException {
    writeVersionedMetadata(4);
    var metadata = new StorageStartupMetadata(filePath, backupPath);
    metadata.open("ignored");
    try {
      assertThat(storedVersion()).isEqualTo(4);
      // DiskStorage's clean close always updates the transaction identifier before clearing dirty.
      metadata.setLastTxId(77L);
      metadata.clearDirty();
      assertThat(storedVersion()).isEqualTo(5);
    } finally {
      metadata.close();
    }
    var reopened = new StorageStartupMetadata(filePath, backupPath);
    try {
      reopened.open("ignored");
      assertThat(reopened.isDirty()).isFalse();
      assertThat(reopened.getLastTxId()).isEqualTo(77L);
      assertThat(reopened.getOpenedAtVersion()).isEqualTo("prior-build");
    } finally {
      reopened.close();
    }
  }

  /** Version 6 is from a newer build and must fail with a database-specific mismatch. */
  @Test
  public void testVersion6NamesDatabaseAndNewerBuild() throws IOException {
    writeVersionedMetadata(6);
    var metadata = new StorageStartupMetadata(filePath, backupPath);
    try {
      assertThatThrownBy(() -> metadata.open("ignored"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("version mismatch")
          .hasMessageContaining(tmpDir.getFileName().toString())
          .hasMessageContaining("version 6")
          .hasMessageContaining("A newer build wrote the startup metadata");
      assertThat(storedVersion()).isEqualTo(6);
    } finally {
      metadata.close();
    }
  }

  /** Version 3 omits the opened-at version and remains readable after the upgrade. */
  @Test
  public void testOpenVersion3StillReadsTransactionId() throws IOException {
    var buffer = ByteBuffer.allocate(8 + 4 + 1 + 8 + 4);
    buffer.position(8);
    buffer.putInt(3);
    buffer.put((byte) 0);
    buffer.putLong(42L);
    buffer.putInt(-1);
    stampChecksum(buffer);
    Files.write(filePath, buffer.array());

    var metadata = new StorageStartupMetadata(filePath, backupPath);
    try {
      metadata.open("ignored");
      assertThat(metadata.isDirty()).isFalse();
      assertThat(metadata.getLastTxId()).isEqualTo(42L);
      assertThat(metadata.getOpenedAtVersion()).isNull();
    } finally {
      metadata.close();
    }
  }

  private int storedVersion() throws IOException {
    return ByteBuffer.wrap(Files.readAllBytes(filePath)).getInt(8);
  }

  private void writeVersionedMetadata(int version) throws IOException {
    final byte[] openedAtVersion = "prior-build".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var buffer = ByteBuffer.allocate(8 + 4 + 1 + 8 + 4 + 4 + openedAtVersion.length);
    buffer.position(8);
    buffer.putInt(version);
    buffer.put((byte) 0);
    buffer.putLong(42L);
    buffer.putInt(-1);
    buffer.putInt(openedAtVersion.length);
    buffer.put(openedAtVersion);
    stampChecksum(buffer);
    Files.write(filePath, buffer.array());
  }

  private static void stampChecksum(ByteBuffer buffer) {
    final var hash = XXHashFactory.fastestInstance().hash64()
        .hash(buffer, 8, buffer.capacity() - 8, 0xADF678FE45L);
    buffer.putLong(0, hash);
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
   * {@code size < 9} branch in {@code open()}.
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
