package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.serialization.types.StringSerializer;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.StorageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * V3 records: big-endian hash (8), payload length (4), id (4), logical name and disk name.
 * String lengths count UTF-16 code units, whose bytes use StringSerializer's ordering.
 * Callers serialize access with the cache's filesLock write lock.
 */
final class NameIdMapFile {

  static final int MAX_RECORD_LENGTH = 16 << 10;
  static final int LENGTH_OFFSET = Long.BYTES;
  static final int HEADER_SIZE = Long.BYTES + Integer.BYTES;

  /**
   * Marks an incomplete rewrite in the first-record length field. Rewrite steps 1 and 3 write it.
   * Must stay negative ({@code -1}) so older readers fail at {@code ByteBuffer.allocate(-1)}.
   */
  static final int INCOMPLETE_REWRITE_LENGTH = -1;

  private NameIdMapFile() {
  }

  static ByteBuffer encode(NameFileIdEntry entry, String storageName) {
    // Check in long arithmetic before StringSerializer sizes or allocations can overflow.
    final long length = 3L * Integer.BYTES
        + 2L * entry.getName().length() + 2L * entry.getFileSystemName().length();
    if (length > MAX_RECORD_LENGTH) {
      throw new StorageException(storageName,
          "Maximum record length in file registry cannot exceed "
              + MAX_RECORD_LENGTH + " bytes. Actual record length: " + length);
    }
    final var record = ByteBuffer.allocate(HEADER_SIZE + (int) length);
    record.position(LENGTH_OFFSET);
    record.putInt((int) length);
    record.putInt(entry.getFileId());
    StringSerializer.staticSerializeInByteBufferObject(entry.getName(), record);
    StringSerializer.staticSerializeInByteBufferObject(entry.getFileSystemName(), record);
    record.putLong(0, checksum(record, HEADER_SIZE, (int) length));
    return record.flip();
  }

  /**
   * Reads one whole copy once. Validation allocates no payload arrays or strings.
   * The result retains only this buffer and decodes valid entries on demand.
   */
  static Scan scan(FileChannel channel) throws IOException {
    final var size = channel.size();
    if (size > Integer.MAX_VALUE) {
      throw new IOException("Name-id map copy size " + size
          + " bytes exceeds the scan limit of " + Integer.MAX_VALUE + " bytes");
    }
    final var bytes = ByteBuffer.allocate((int) size);
    IOUtils.readByteBuffer(bytes, channel, 0, true);
    bytes.flip();
    var end = 0;
    while (end < bytes.limit() && validRecord(bytes, end)) {
      end += HEADER_SIZE + bytes.getInt(end + LENGTH_OFFSET);
    }
    return new Scan(bytes.asReadOnlyBuffer(), end, end == bytes.limit());
  }

  private static boolean validRecord(ByteBuffer bytes, int offset) {
    final var end = structuredRecordEnd(bytes, offset);
    return end >= 0
        && checksum(bytes, offset + HEADER_SIZE, end - offset - HEADER_SIZE)
            == bytes.getLong(offset);
  }

  /** Checks structure without allocating or hashing candidate payloads during resynchronization. */
  private static int structuredRecordEnd(ByteBuffer bytes, int offset) {
    if (bytes.limit() - offset < HEADER_SIZE) {
      return -1;
    }
    final var length = bytes.getInt(offset + LENGTH_OFFSET);
    final var payload = offset + HEADER_SIZE;
    if (length <= 0 || length > MAX_RECORD_LENGTH || length > bytes.limit() - payload) {
      return -1;
    }
    final var end = payload + length;
    // Validate both counted strings before the serializer can allocate from their lengths.
    var next = skipString(bytes, payload + Integer.BYTES, end);
    if (next < 0) {
      return -1;
    }
    next = skipString(bytes, next, end);
    return next == end ? end : -1;
  }

  private static int skipString(ByteBuffer bytes, int offset, int end) {
    if (end - offset < Integer.BYTES) {
      return -1;
    }
    final var count = bytes.getInt(offset);
    final var start = offset + Integer.BYTES;
    if (count < 0 || count > (end - start) / Character.BYTES) {
      return -1;
    }
    return start + count * Character.BYTES;
  }

  private static long checksum(ByteBuffer bytes, int offset, int length) {
    return DiskStorage.XX_HASH_64.hash(bytes, offset, length, DiskStorage.XX_HASH_SEED);
  }

  private static NameFileIdEntry decode(ByteBuffer bytes, int offset) {
    final var payload = bytes.duplicate();
    payload.position(offset + HEADER_SIZE);
    final var id = payload.getInt();
    // Callers bound both counted strings with structuredRecordEnd before decoding.
    final var name = StringSerializer.staticDeserializeFromByteBufferObject(payload);
    final var diskName = StringSerializer.staticDeserializeFromByteBufferObject(payload);
    return new NameFileIdEntry(name, id, diskName);
  }

  /** Names from a rejected record describe unverified bytes, not a trusted registration. */
  record DroppedRange(int start, int end, NameFileIdEntry unverifiedEntry) {
  }

  record Scan(ByteBuffer bytes, int validEnd, boolean intact) implements Iterable<NameFileIdEntry> {

    /**
     * Keeps the prefix and later validated records as raw bytes in file order. The original scan
     * stays unchanged because source selection must compare only the original valid prefixes.
     */
    Scan salvage(Consumer<DroppedRange> dropped) {
      if (intact) {
        return this;
      }
      final var image = ByteBuffer.allocate(bytes.limit());
      image.put(bytes.slice(0, validEnd));
      var start = validEnd;
      // The record at validEnd is already known to be damaged.
      var offset = validEnd + 1;
      while (offset < bytes.limit()) {
        if (!validRecord(bytes, offset)) {
          offset++;
          continue;
        }
        if (start < offset) {
          reportDropped(dropped, start, offset);
        }
        final var end = offset + HEADER_SIZE + bytes.getInt(offset + LENGTH_OFFSET);
        image.put(bytes.slice(offset, end - offset));
        offset = end;
        start = end;
      }
      if (start < bytes.limit()) {
        reportDropped(dropped, start, bytes.limit());
      }
      image.flip();
      return new Scan(image.asReadOnlyBuffer(), image.limit(), true);
    }

    private void reportDropped(Consumer<DroppedRange> dropped, int start, int end) {
      // A rejected hash does not make unchecked serializer allocations safe.
      final var entry = structuredRecordEnd(bytes, start) < 0 ? null : decode(bytes, start);
      dropped.accept(new DroppedRange(start, end, entry));
    }

    @Override
    public Iterator<NameFileIdEntry> iterator() {
      return new Iterator<>() {
        private int offset;

        @Override
        public boolean hasNext() {
          return offset < validEnd;
        }

        @Override
        public NameFileIdEntry next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          final var entry = decode(bytes, offset);
          offset += HEADER_SIZE + bytes.getInt(offset + LENGTH_OFFSET);
          return entry;
        }
      };
    }
  }

  /**
   * Rewrites an existing copy using a complete encoded image starting at byte zero.
   * The caller must force the other copy before calling this method and keep the source
   * unchanged until this copy is complete. An incomplete nonempty rewrite has length
   * {@link #INCOMPLETE_REWRITE_LENGTH}.
   */
  static void rewrite(FileChannel channel, ByteBuffer image, boolean callFsync) throws IOException {
    final var guard = ByteBuffer.allocate(Integer.BYTES).putInt(INCOMPLETE_REWRITE_LENGTH).flip();
    IOUtils.writeByteBuffer(guard, channel, LENGTH_OFFSET);
    force(channel, callFsync);

    if (image.limit() == 0) {
      channel.truncate(0);
      force(channel, callFsync);
      return;
    }
    final var firstEnd = HEADER_SIZE + image.getInt(LENGTH_OFFSET);
    final var rest = image.slice(firstEnd, image.limit() - firstEnd);
    IOUtils.writeByteBuffer(rest, channel, firstEnd);
    channel.truncate(image.limit());
    force(channel, callFsync);

    // Keep the guard while replacing the first record, including its checksum.
    final var first = ByteBuffer.allocate(firstEnd);
    first.put(image.duplicate().position(0).limit(firstEnd));
    final var length = first.getInt(LENGTH_OFFSET);
    first.putInt(LENGTH_OFFSET, INCOMPLETE_REWRITE_LENGTH);
    first.flip();
    IOUtils.writeByteBuffer(first, channel, 0);
    force(channel, callFsync);

    final var commit = ByteBuffer.allocate(Integer.BYTES).putInt(length).flip();
    IOUtils.writeByteBuffer(commit, channel, LENGTH_OFFSET);
    force(channel, callFsync);
  }

  /** Publishes a complete missing copy. A failed pre-move write cannot expose a partial copy. */
  static void create(Path path, ByteBuffer image, boolean callFsync, Object requester)
      throws IOException {
    final var temporary = Files.createTempFile(path.getParent(),
        StorageFileNames.MAP_TEMP_PREFIX, StorageFileNames.TEMP_SUFFIX);
    try {
      try (final var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        IOUtils.writeByteBuffer(image.slice(0, image.limit()), channel, 0);
        force(channel, callFsync);
      }
      if (callFsync) {
        // FileUtils retains the best-effort Windows fallback when JNA is unavailable.
        FileUtils.durableAtomicMove(temporary, path, requester);
      } else {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
      }
    } catch (IOException | RuntimeException | Error failure) {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException | RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private static void force(FileChannel channel, boolean callFsync) throws IOException {
    if (callFsync) {
      channel.force(true);
    }
  }
}
