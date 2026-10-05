package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.common.io.IOUtils;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import javax.annotation.Nullable;

/**
 * Streaming side-file codec and ordered durable publisher. Decoding proves content integrity,
 * not WAL coverage. A WAL-cut caller holds save order around saving and its associated cut.
 * Codec streams stay open so the publisher can force its channel after encoding.
 */
final class ChangedPageTrackerFile {

  private static final int MAGIC = 0x59544350;
  private static final int VERSION = 1;
  private static final int BUFFER_BYTES = 4096;

  private ChangedPageTrackerFile() {
  }

  /**
   * Holds save order from capture through publication or durable invalidation. A cut caller must
   * also hold this reentrant domain until its associated cut finishes. No short tracker or external
   * inventory lock may be held across these filesystem operations.
   */
  static SaveResult save(Path sideFile, ChangedPageTracker tracker, LogSequenceNumber coverage) {
    return save(sideFile, tracker, coverage, new FileOperations());
  }

  static SaveResult save(Path sideFile, ChangedPageTracker tracker, LogSequenceNumber coverage,
      FileOperations files) {
    var target = sideFile.toAbsolutePath();
    var temporary = target.resolveSibling(target.getFileName() + ".tmp");
    tracker.saveOrderLock().lock();
    try {
      try {
        var state = tracker.capture();
        // A crash-left temporary file has no authority. Remove it rather than following an old
        // link or reusing its bytes. CREATE_NEW gives this attempt a fresh, same-folder file.
        files.delete(temporary);
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
          files.write(channel, state, coverage);
          files.forceFile(channel);
        }
        if (!tracker.isCaptureValid(state)) {
          throw new IOException("Changed-page tracker capture invalidated before publication");
        }
        if (files.windows()) {
          files.windowsMove(temporary, target);
        } else {
          files.move(temporary, target);
          files.forceFolder(target.getParent());
        }
        // A mark can fail during either force or rename. A published file must not certify that
        // capture. Invalidate its name durably before allowing a cut instead.
        if (!tracker.saveSucceeded(state)) {
          throw new IOException("Changed-page tracker capture invalidated during publication");
        }
        return SaveResult.SAVED;
      } catch (IOException | RuntimeException | Error failure) {
        // Revoke trust before cleanup or diagnostics can allocate or fail.
        tracker.invalidate();
        var result = SaveResult.FAILED_INVALIDATED;
        Error cleanupError = null;
        try {
          invalidateFile(target, files);
        } catch (IOException | RuntimeException | Error secondary) {
          if (secondary instanceof Error && !(failure instanceof Error)) {
            suppress(secondary, failure);
          } else {
            suppress(failure, secondary);
          }
          warn(files, "Failed to invalidate changed-page tracker side file: ", target, secondary);
          result = SaveResult.FAILED_INVALIDATION_FAILED;
          if (secondary instanceof Error error) {
            cleanupError = error;
          }
        }
        warn(files, "Failed to save changed-page tracker side file: ", target, failure);
        if (failure instanceof Error error) {
          throw error;
        }
        if (cleanupError != null) {
          throw cleanupError;
        }
        return result;
      }
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  /** A future cut requires durable removal of authority, including Windows tombstone publication. */
  static InvalidationResult invalidate(Path sideFile, ChangedPageTracker tracker) {
    return invalidate(sideFile, tracker, new FileOperations());
  }

  static InvalidationResult invalidate(Path sideFile, ChangedPageTracker tracker,
      FileOperations files) {
    tracker.saveOrderLock().lock();
    try {
      tracker.invalidate();
      var target = sideFile.toAbsolutePath();
      try {
        invalidateFile(target, files);
        return InvalidationResult.INVALIDATED;
      } catch (IOException | RuntimeException | Error failure) {
        if (failure instanceof Error error) {
          try {
            invalidateFile(target, files);
          } catch (IOException | RuntimeException | Error secondary) {
            suppress(error, secondary);
          }
          warn(files, "Failed to invalidate changed-page tracker side file: ", target, error);
          throw error;
        }
        warn(files, "Failed to invalidate changed-page tracker side file: ", target, failure);
        return InvalidationResult.FAILED;
      }
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private static void invalidateFile(Path target, FileOperations files) throws IOException {
    // Cancellation must not prevent the cleanup durability barrier. Preserve the caller's flag.
    boolean interrupted = Thread.interrupted();
    try {
      if (!files.windows()) {
        files.delete(target);
        files.forceFolder(target.getParent());
      } else if (files.windowsMoveAvailable()) {
        var temporary = target.resolveSibling(target.getFileName() + ".tmp");
        files.delete(temporary);
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
          files.forceFile(channel);
        }
        files.windowsMove(temporary, target);
      } else {
        // No publication can occur without the helper. Force the existing name empty instead.
        files.truncateAndForce(target);
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void warn(FileOperations files, String prefix, Path target, Throwable failure) {
    try {
      files.warn(prefix + target, failure);
    } catch (RuntimeException | Error loggingFailure) {
      // Diagnostics cannot change a durability outcome or replace the original throwable.
      suppress(failure, loggingFailure);
    }
  }

  private static void suppress(Throwable primary, Throwable secondary) {
    if (primary != secondary) {
      try {
        primary.addSuppressed(secondary);
      } catch (RuntimeException | Error ignored) {
        // Exhausted heaps may also reject the diagnostic allocation. Trust is already revoked.
      }
    }
  }

  enum SaveResult {
    SAVED, FAILED_INVALIDATED, FAILED_INVALIDATION_FAILED;

    /** Existing data-sync and WAL protections still apply. This is only the tracker condition. */
    boolean allowsWalCut() {
      return this != FAILED_INVALIDATION_FAILED;
    }
  }

  enum InvalidationResult {
    INVALIDATED, FAILED;

    boolean allowsWalCut() {
      return this == INVALIDATED;
    }
  }

  /** Package-private fault seam. Production uses NIO or a strict Windows native replacement. */
  static class FileOperations {
    void write(FileChannel channel, ChangedPageTracker.SaveState state, LogSequenceNumber coverage)
        throws IOException {
      ChangedPageTrackerFile.write(Channels.newOutputStream(channel), state, coverage);
    }

    void forceFile(FileChannel channel) throws IOException {
      channel.force(true);
    }

    void move(Path temporary, Path target) throws IOException {
      move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    void move(Path temporary, Path target, CopyOption... options) throws IOException {
      Files.move(temporary, target, options);
    }

    void forceFolder(Path folder) throws IOException {
      try (var channel = openFolder(folder)) {
        channel.force(true);
      }
    }

    FileChannel openFolder(Path folder) throws IOException {
      return FileChannel.open(folder, StandardOpenOption.READ);
    }

    boolean windows() {
      return IOUtils.isOsWindows();
    }

    boolean windowsMoveAvailable() {
      return FileUtils.windowsWriteThroughMoveAvailable();
    }

    void windowsMove(Path temporary, Path target) throws IOException {
      FileUtils.windowsWriteThroughMove(temporary, target);
    }

    void truncateAndForce(Path target) throws IOException {
      try (var channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
        channel.truncate(0);
        channel.force(true);
      } catch (NoSuchFileException absent) {
        // An absent name has no authority. Do not create one in the helper-unavailable path.
      }
    }

    void warn(String message, Throwable failure) {
      LogManager.instance().warn(ChangedPageTrackerFile.class, message, failure);
    }

    void delete(Path path) throws IOException {
      Files.deleteIfExists(path);
    }
  }

  /** Big-endian fixed-width records avoid counts that can race with concurrent bitmap growth. */
  static void write(OutputStream stream, ChangedPageTracker.SaveState state,
      LogSequenceNumber coverage) throws IOException {
    if (coverage.getSegment() < 0 || coverage.getPosition() < 0) {
      throw new IllegalArgumentException("Invalid coverage LSN");
    }
    var checksum = new CRC32C();
    var out = new DataOutputStream(new CheckedOutputStream(
        new BufferedOutputStream(stream, BUFFER_BYTES), checksum));
    out.writeInt(MAGIC);
    out.writeInt(VERSION);
    out.writeByte((state.trusted() ? 1 : 0)
        | (state.lastCompletedIdentifier() != null ? 2 : 0) | (state.sealed() != null ? 4 : 0));
    coverage.toStream(out);
    writeId(out, state.trackerIdentifier());
    if (state.lastCompletedIdentifier() != null) {
      writeId(out, state.lastCompletedIdentifier());
    }
    if (state.sealed() != null) {
      writeId(out, state.sealed().identifier());
    }
    writeGeneration(out, state.active());
    if (state.sealed() != null) {
      writeGeneration(out, state.sealed().pages());
    }
    out.writeInt((int) checksum.getValue());
    out.flush();
  }

  private static void writeId(DataOutputStream out, UUID id) throws IOException {
    out.writeLong(id.getMostSignificantBits());
    out.writeLong(id.getLeastSignificantBits());
  }

  private static void writeGeneration(DataOutputStream out, ChangedPageTracker.Generation pages)
      throws IOException {
    try {
      pages.forEachFile((file, bitmap) -> bitmap.forEachWord((word, bits) -> {
        try {
          out.writeInt(file);
          out.writeLong(word);
          out.writeLong(bits);
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }));
    } catch (UncheckedIOException failure) {
      throw failure.getCause();
    }
    out.writeInt(0);
  }

  /** Missing content loses authority. Other filesystem errors remain distinct checked errors. */
  static Loaded load(Path path) throws IOException {
    try (var stream = Files.newInputStream(path)) {
      return read(stream);
    } catch (NoSuchFileException missing) {
      return untrusted("missing side file");
    }
  }

  /** Partial restoration is private until the checksum and exact end of stream are validated. */
  static Loaded read(InputStream stream) throws IOException {
    var checksum = new CRC32C();
    var checked = new CheckedInputStream(new BufferedInputStream(stream, BUFFER_BYTES), checksum);
    var in = new DataInputStream(new RecordInputStream(checked));
    try {
      require(in.readInt() == MAGIC, "magic");
      require(in.readInt() == VERSION, "version");
      int flags = in.readUnsignedByte();
      require((flags & ~7) == 0, "flags");
      var coverage = new LogSequenceNumber(in);
      require(coverage.getSegment() >= 0 && coverage.getPosition() >= 0, "coverage LSN");
      var trackerId = readId(in);
      var completed = (flags & 2) == 0 ? null : readId(in);
      var sealed = (flags & 4) == 0 ? null : readId(in);
      var builder = ChangedPageTracker.restoration(trackerId, completed, (flags & 1) != 0, sealed);
      readGeneration(in, builder::activeWord);
      if (sealed != null) {
        readGeneration(in, builder::sealedWord);
      }
      int expected = (int) checksum.getValue();
      require(in.readInt() == expected, "checksum");
      require(checked.read() == -1, "trailing bytes");
      return new Loaded(builder.build(), coverage);
    } catch (InvalidContent damaged) {
      return untrusted(damaged.getMessage());
    }
  }

  private static UUID readId(DataInputStream in) throws IOException {
    return new UUID(in.readLong(), in.readLong());
  }

  private static void readGeneration(DataInputStream in, LoadedWord consumer) throws IOException {
    int previousFile = 0;
    long previousWord = -1;
    while (true) {
      int file = in.readInt();
      if (file == 0) {
        return;
      }
      long word = in.readLong();
      long bits = in.readLong();
      require(file > 0 && file >= previousFile, "file index or ordering");
      require(word >= 0 && word <= (Long.MAX_VALUE >>> 6), "word index");
      require(file != previousFile || word > previousWord, "duplicate or unordered word");
      require(bits != 0, "empty word");
      consumer.accept(file, word, bits);
      previousFile = file;
      previousWord = word;
    }
  }

  private static void require(boolean valid, String field) throws InvalidContent {
    if (!valid) {
      throw new InvalidContent("Invalid changed-page tracker " + field);
    }
  }

  private static Loaded untrusted(String reason) {
    LogManager.instance().warn(ChangedPageTrackerFile.class,
        "Changed-page tracker side file is untrusted: " + reason);
    return new Loaded(new ChangedPageTracker(), null);
  }

  record Loaded(ChangedPageTracker tracker, @Nullable LogSequenceNumber coverageLsn) {
  }

  @FunctionalInterface
  private interface LoadedWord {
    void accept(int file, long word, long bits);
  }

  // Required record reads treat a returned -1 as malformed content. Exceptions from the
  // supplied stream propagate unchanged, including EOFException. The final end check bypasses
  // this wrapper because end of input is expected there.
  private static final class RecordInputStream extends FilterInputStream {
    private RecordInputStream(InputStream stream) {
      super(stream);
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value == -1) {
        throw new InvalidContent("truncated fixed-width record");
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int count = in.read(bytes, offset, length);
      if (count == -1) {
        throw new InvalidContent("truncated fixed-width record");
      }
      return count;
    }
  }

  private static final class InvalidContent extends IOException {
    private InvalidContent(String message) {
      super(message);
    }
  }
}
