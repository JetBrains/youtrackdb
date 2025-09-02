package com.jetbrains.youtrackdb.internal.core.storage.fs;

import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableItem;
import com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

public interface File extends ClosableItem {

  int HEADER_SIZE = 1024;

  long allocateSpace(int size) throws IOException;

  void shrink(long size) throws IOException;

  long getFileSize();

  void read(long offset, ByteBuffer buffer, boolean throwOnEof) throws IOException;

  void write(long offset, ByteBuffer buffer) throws IOException;

  IOResult write(List<RawPairLongObject<ByteBuffer>> buffers) throws IOException;

  void synch();

  void create() throws IOException;

  @Override
  void open();

  @Override
  void close();

  void delete() throws IOException, InterruptedException;

  @Override
  boolean isOpen();

  boolean exists();

  String getName();

  void renameTo(Path newFile) throws IOException, InterruptedException;

  /**
   * Returns the underlying file size. It's the physical size of the file on the file system.
   *
   * @return the size of the file
   * @throws IOException if an I/O error occurs
   */
  long getUnderlyingFileSize() throws IOException;

  void replaceContentWith(Path newContentFile) throws IOException, InterruptedException;

  @Override
  String toString();
}
