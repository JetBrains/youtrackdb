package com.jetbrains.youtrackdb.internal.core.db.tool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The per-record bounded rendering buffer of the export hardening (design M2.a-3, ruling Q-M1):
 * a record's JSON renders into this buffer — never directly into the shared dump stream — so a
 * mid-render failure discards the record WHOLE instead of leaving partial JSON in the dump.
 * Beyond the configured threshold the buffer spills to a collision-free transient file, so
 * memory stays bounded and an oversized-but-healthy record is exported, not shed.
 *
 * <p>The spill file's lifecycle is pinned: created lazily on first overflow with a unique name,
 * and deleted on EVERY path by {@link #close()} (the caller holds the buffer in
 * try-with-resources).
 */
final class SpillableRecordBuffer extends OutputStream {

  private final int spillThreshold;
  private final Path spillDirectory;

  private ByteArrayOutputStream memory = new ByteArrayOutputStream();
  private Path spillFile;
  private OutputStream spillOut;
  private long size;

  SpillableRecordBuffer(int spillThreshold, Path spillDirectory) {
    this.spillThreshold = spillThreshold;
    this.spillDirectory = spillDirectory;
  }

  @Override
  public void write(int b) throws IOException {
    ensureCapacity(1);
    if (spillOut != null) {
      spillOut.write(b);
    } else {
      memory.write(b);
    }
    size++;
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    ensureCapacity(len);
    if (spillOut != null) {
      spillOut.write(b, off, len);
    } else {
      memory.write(b, off, len);
    }
    size += len;
  }

  /**
   * Spills once the buffered size would EXCEED the threshold: a record of exactly the threshold
   * size stays in memory, one byte more spills.
   */
  private void ensureCapacity(int incoming) throws IOException {
    if (spillOut == null && size + incoming > spillThreshold) {
      spillFile = Files.createTempFile(spillDirectory, "ytdb-export-record-", ".spill");
      spillOut = new BufferedOutputStream(
          Files.newOutputStream(spillFile, StandardOpenOption.WRITE,
              StandardOpenOption.TRUNCATE_EXISTING));
      memory.writeTo(spillOut);
      memory = null;
    }
  }

  /** Whether the buffer overflowed to the transient file. */
  boolean spilled() {
    return spillFile != null;
  }

  /** The buffered size in bytes. */
  long size() {
    return size;
  }

  /**
   * Opens the buffered content for the copy-out into the dump. Must be called after the
   * rendering generator was closed (all bytes flushed).
   */
  InputStream openContent() throws IOException {
    if (spillOut != null) {
      spillOut.close();
      spillOut = null;
      return new BufferedInputStream(Files.newInputStream(spillFile));
    }
    return new ByteArrayInputStream(memory.toByteArray());
  }

  @Override
  public void close() throws IOException {
    // Deletion on EVERY path: the spill file never outlives the buffer, whether the record was
    // copied out, discarded on a render failure, or the whole export aborted.
    IOException failure = null;
    if (spillOut != null) {
      try {
        spillOut.close();
      } catch (IOException e) {
        failure = e;
      }
      spillOut = null;
    }
    if (spillFile != null) {
      try {
        Files.deleteIfExists(spillFile);
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
      spillFile = null;
    }
    if (failure != null) {
      throw failure;
    }
  }
}
