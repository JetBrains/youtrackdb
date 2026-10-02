/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;

/**
 * Manages storage startup metadata including dirty flag and last transaction ID, persisted to a
 * file with checksum verification.
 *
 * @since 5/6/14
 */
public class StorageStartupMetadata {

  private static final long XX_HASH_SEED = 0xADF678FE45L;
  private static final XXHash64 XX_HASH_64;

  static {
    final var xxHashFactory = XXHashFactory.fastestInstance();
    XX_HASH_64 = xxHashFactory.hash64();
  }

  private static final int VERSION_WITHOUT_DB_OPEN_VERSION = 3;
  private static final int VERSION = 4;

  private final Path filePath;
  private final Path backupPath;

  private FileChannel channel;
  private FileLock fileLock;
  // Updated at each write phase. A failed main write leaves the backup as the only good copy.
  private volatile boolean mainKnownGood;

  private volatile boolean dirtyFlag;
  // A failed update can change dirtyFlag without writing a complete main copy.
  private volatile boolean confirmedDirtyFlag;
  // A complete synced backup also protects writers while a dirty main is being rewritten.
  private volatile boolean backupKnownGoodDirty;
  private volatile long lastTxId;
  // Unlike lastTxId, this value advances only after a complete main-file write.
  private long confirmedLastTxId = -1;
  private volatile String openedAtVersion;

  private final Lock lock = new ReentrantLock();
  private final AtomicReference<Runnable> beforeDirtyUpdateTestAction = new AtomicReference<>();
  private volatile AtomicInteger dirtyUpdateCountForTesting;

  /** Counts completed dirty updates in tests, without changing ordinary write behavior. */
  public void countDirtyUpdatesForTesting(AtomicInteger count) {
    dirtyUpdateCountForTesting = count;
  }

  /** Installs a one-shot action under the metadata lock, just before a dirty update. */
  public void setBeforeDirtyUpdateActionForTesting(Runnable action) {
    if (!beforeDirtyUpdateTestAction.compareAndSet(null, action)) {
      throw new IllegalStateException("A dirty update action is already installed");
    }
  }

  public StorageStartupMetadata(final Path filePath, final Path backupPath) {
    this.filePath = filePath;
    this.backupPath = backupPath;
  }

  public void create(final String openedAtVersion) throws IOException {
    lock.lock();
    try {

      if (Files.exists(filePath)) {
        Files.delete(filePath);
      }

      channel =
          FileChannel.open(
              filePath,
              StandardOpenOption.READ,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.SYNC);
      if (GlobalConfiguration.FILE_LOCK.getValueAsBoolean()) {
        lockFile();
      }

      dirtyFlag = true;
      lastTxId = -1;
      this.openedAtVersion = openedAtVersion;

      // This is a fresh file, with no earlier state to preserve.
      mainKnownGood = false;
      update(serialize(), true);

    } finally {
      lock.unlock();
    }
  }

  private void update(ByteBuffer buffer) throws IOException {
    update(buffer, false);
  }

  private void update(ByteBuffer buffer, boolean initialWrite) throws IOException {
    if (channel == null) {
      throw new NullPointerException("Startup metadata is not open");
    }
    if (!initialWrite && !mainKnownGood) {
      final var backup = readValidBackup();
      if (backup == null) {
        throw new IOException("No valid startup metadata copy remains for a new write");
      }
      repairMain(backup);
    }

    backupKnownGoodDirty = false;
    Files.deleteIfExists(backupPath);

    try (final var backupChannel =
        FileChannel.open(
            backupPath,
            StandardOpenOption.READ,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC)) {
      IOUtils.writeByteBuffer(buffer, backupChannel, 0);
    }

    // Publish backup validity only after its synced write completes.
    backupKnownGoodDirty = buffer.get(12) > 0;
    // The completed backup protects the state until the main write completes.
    mainKnownGood = false;
    channel.truncate(0);
    buffer.rewind();
    IOUtils.writeByteBuffer(buffer, channel, 0);
    confirmedDirtyFlag = buffer.get(12) > 0;
    confirmedLastTxId = buffer.getLong(13);
    mainKnownGood = true;
    backupKnownGoodDirty = false;

    Files.deleteIfExists(backupPath);
  }

  private void repairMain(ByteBuffer backup) throws IOException {
    // Keep the lock on this inode for the entire repair, including failures.
    mainKnownGood = false;
    channel.truncate(0);
    IOUtils.writeByteBuffer(backup, channel, 0);
    // A failed clear can leave a clean backup and a stale dirty confirmation.
    confirmedDirtyFlag = backup.get(12) > 0;
    confirmedLastTxId = backup.getLong(13);
    mainKnownGood = true;
    backupKnownGoodDirty = false;
  }

  private ByteBuffer readValidBackup() throws IOException {
    if (!Files.exists(backupPath)) {
      return null;
    }
    try (var backupChannel = FileChannel.open(backupPath, StandardOpenOption.READ)) {
      return readChecksummed(backupChannel);
    }
  }

  private ByteBuffer readChecksummed(FileChannel source) throws IOException {
    final var size = source.size();
    if (size < 25 || size > Integer.MAX_VALUE) {
      return null;
    }
    final var buffer = ByteBuffer.allocate((int) size);
    IOUtils.readByteBuffer(buffer, source, 0, true);
    buffer.rewind();
    if (XX_HASH_64.hash(buffer, 8, buffer.capacity() - 8, XX_HASH_SEED)
        != buffer.getLong(0)) {
      return null;
    }
    final var version = buffer.getInt(8);
    if (version != VERSION && version != VERSION_WITHOUT_DB_OPEN_VERSION) {
      throw new IllegalStateException(
          "Invalid version of the binary format of startup metadata file found "
              + version + " but expected " + VERSION + " or " + VERSION_WITHOUT_DB_OPEN_VERSION);
    }
    // Version 3 has a fixed size. Version 4 has a signed version-string length at byte 25.
    if (version == VERSION_WITHOUT_DB_OPEN_VERSION) {
      return size == 25 ? buffer : null;
    }
    if (size < 29) {
      return null;
    }
    final var length = buffer.getInt(25);
    return length >= -1 && size == 29L + Math.max(0, length) ? buffer : null;
  }

  private void readState(ByteBuffer buffer) {
    buffer.position(12);
    dirtyFlag = buffer.get() > 0;
    confirmedDirtyFlag = dirtyFlag;
    lastTxId = buffer.getLong();
    openedAtVersion = null;
    if (buffer.getInt(8) == VERSION) {
      final var length = buffer.getInt(25);
      if (length > 0) {
        final var raw = new byte[length];
        buffer.position(29);
        buffer.get(raw);
        openedAtVersion = new String(raw, StandardCharsets.UTF_8);
      }
    }
  }

  private void lockFile() throws IOException {
    try {
      fileLock = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      LogManager.instance().warn(this, "File is already locked by other thread", e);
    }

    if (fileLock == null) {
      throw new StorageException(null,
          "Database is locked by another process, please shutdown process and try again");
    }
  }

  public boolean exists() {
    lock.lock();
    try {
      return Files.exists(filePath);
    } finally {
      lock.unlock();
    }
  }

  public void open(final String createdAtVersion) throws IOException {
    lock.lock();
    try {
      final var missing = !Files.exists(filePath);
      channel = FileChannel.open(filePath, StandardOpenOption.SYNC, StandardOpenOption.WRITE,
          StandardOpenOption.READ, StandardOpenOption.CREATE);
      try {
        // The main inode must be locked before reading or changing either copy.
        if (GlobalConfiguration.FILE_LOCK.getValueAsBoolean()) {
          lockFile();
        }

        final var size = channel.size();
        final var main = readChecksummed(channel);
        if (main != null) {
          readState(main);
          mainKnownGood = true;
          confirmedLastTxId = lastTxId;
          Files.deleteIfExists(backupPath);
          return;
        }

        final var backup = readValidBackup();
        if (backup != null) {
          repairMain(backup);
          readState(backup);
          confirmedLastTxId = lastTxId;
          Files.deleteIfExists(backupPath);
          LogManager.instance().warn(this, "Recovered startup metadata from backup copy");
          return;
        }

        if (!missing && size == 1) {
          final var legacy = ByteBuffer.allocate(1);
          IOUtils.readByteBuffer(legacy, channel, 0, true);
          dirtyFlag = legacy.get(0) > 0;
          confirmedDirtyFlag = dirtyFlag;
          mainKnownGood = true;
          confirmedLastTxId = lastTxId;
          return;
        }
        if (size == 9) {
          final var legacy = ByteBuffer.allocate(9);
          IOUtils.readByteBuffer(legacy, channel, 0, true);
          dirtyFlag = legacy.get(0) > 0;
          confirmedDirtyFlag = dirtyFlag;
          lastTxId = legacy.getLong(1);
          mainKnownGood = true;
          confirmedLastTxId = lastTxId;
          return;
        }

        if (missing) {
          LogManager.instance().info(this,
              "File with startup metadata does not exist, creating new one");
        } else {
          LogManager.instance().error(this,
              "File with startup metadata is broken and can not be used, creation of new one",
              null);
        }
        dirtyFlag = true;
        lastTxId = -1;
        openedAtVersion = createdAtVersion;
        // Neither copy is usable. Initialize through the locked inode without replacing it.
        update(serialize(), true);
      } catch (IOException | RuntimeException e) {
        try {
          close();
        } catch (IOException closeError) {
          e.addSuppressed(closeError);
        }
        throw e;
      }
    } finally {
      lock.unlock();
    }
  }

  public void close() throws IOException {
    lock.lock();
    try {
      if (channel == null) {
        return;
      }

      if (Files.exists(filePath)) {
        if (fileLock != null) {
          fileLock.release();
          fileLock = null;
        }

        channel.close();
        channel = null;
      }

    } finally {
      lock.unlock();
    }
  }

  public void delete() throws IOException {
    lock.lock();
    try {
      if (channel == null) {
        return;
      }

      if (Files.exists(filePath)) {

        if (fileLock != null) {
          fileLock.release();
          fileLock = null;
        }

        channel.close();
        channel = null;

        Files.delete(filePath);
      }
    } finally {
      lock.unlock();
    }
  }

  public void makeDirty(final String openedAtVersion) throws IOException {
    if (isDurablyDirty()) {
      return;
    }

    lock.lock();
    try {
      if (isDurablyDirty()) {
        return;
      }

      dirtyFlag = true;
      this.openedAtVersion = openedAtVersion;
      final var beforeUpdate = beforeDirtyUpdateTestAction.getAndSet(null);
      if (beforeUpdate != null) {
        beforeUpdate.run();
      }
      update(serialize());
      final var count = dirtyUpdateCountForTesting;
      if (count != null) {
        count.incrementAndGet();
      }
    } finally {
      lock.unlock();
    }
  }

  /** True only after a complete dirty main copy has been written. */
  public boolean isDurablyDirty() {
    return dirtyFlag && confirmedDirtyFlag && (mainKnownGood || backupKnownGoodDirty);
  }

  public void clearDirty() throws IOException {
    if (!dirtyFlag) {
      return;
    }

    lock.lock();
    try {
      if (!dirtyFlag) {
        return;
      }

      dirtyFlag = false;
      try {
        update(serialize());
      } catch (IOException | RuntimeException failure) {
        // The failed clear cannot be used as the state of a later floor publication.
        dirtyFlag = true;
        throw failure;
      }
    } finally {
      lock.unlock();
    }
  }

  public void setLastTxId(long lastTxId) throws IOException {
    lock.lock();
    try {
      this.lastTxId = lastTxId;

      update(serialize());
    } finally {
      lock.unlock();
    }
  }

  /** Saves a checkpoint floor only if a complete main copy already on disk does not cover it. */
  public void publishLastTxIdFloor(long floor) throws IOException {
    lock.lock();
    try {
      if (mainKnownGood && confirmedLastTxId >= floor) {
        return;
      }
      lastTxId = Math.max(lastTxId, Math.max(confirmedLastTxId, floor));
      update(serialize());
    } finally {
      lock.unlock();
    }
  }

  public boolean isDirty() {
    return dirtyFlag;
  }

  public long getLastTxId() {
    return lastTxId;
  }

  public String getOpenedAtVersion() {
    return openedAtVersion;
  }

  private ByteBuffer serialize() {
    final ByteBuffer buffer;
    var bufferSize = 8 + 4 + 1 + 8 + 4 + 4;

    final byte[] openedAtVersionRaw;
    if (openedAtVersion != null) {
      openedAtVersionRaw = openedAtVersion.getBytes(StandardCharsets.UTF_8);
      bufferSize += openedAtVersionRaw.length;
    } else {
      openedAtVersionRaw = null;
    }

    buffer = ByteBuffer.allocate(bufferSize);

    buffer.position(8);

    buffer.putInt(VERSION);
    // dirty flag
    buffer.put(dirtyFlag ? (byte) 1 : (byte) 0);
    // transaction id
    buffer.putLong(lastTxId);

    // tx metadata
    buffer.putInt(-1);

    if (this.openedAtVersion == null) {
      buffer.putInt(-1);
    } else {

      buffer.putInt(openedAtVersionRaw.length);
      buffer.put(openedAtVersionRaw);
    }

    final var xxHash = XX_HASH_64.hash(buffer, 8, buffer.capacity() - 8, XX_HASH_SEED);
    buffer.putLong(0, xxHash);

    buffer.rewind();

    return buffer;
  }
}
