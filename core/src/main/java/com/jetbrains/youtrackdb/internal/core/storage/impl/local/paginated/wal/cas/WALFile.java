package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public interface WALFile extends Closeable {

  void force(boolean forceMetadata) throws IOException;

  int write(ByteBuffer buffer) throws IOException;

  long position() throws IOException;

  void position(long position) throws IOException;

  void readBuffer(ByteBuffer buffer) throws IOException;

  long segmentId();

  static WALFile createWriteWALFile(final Path path, final long segmentId) throws IOException {

    return new WALChannelFile(
        FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING),
        segmentId);
  }

  static WALFile createReadWALFile(Path path, long segmentId) throws IOException {
    return new WALChannelFile(FileChannel.open(path, StandardOpenOption.READ), segmentId);
  }
}
