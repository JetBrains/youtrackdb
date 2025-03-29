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
package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.StatefulEdge;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.serialization.MemoryStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The rawest representation of a record. It's schema less. Use this if you need to store Strings or
 * byte[] without matter about the content. Useful also to store multimedia contents and binary
 * files. The object can be reused across calls to the database by using the reset() at every
 * re-use.
 */
public class RecordBytes extends RecordAbstract implements Blob {

  private static final byte[] EMPTY_SOURCE = new byte[]{};

  public RecordBytes(DatabaseSessionInternal session) {
    super(session);
    source = EMPTY_SOURCE;
  }

  public RecordBytes(final DatabaseSessionInternal iDatabase, final byte[] iSource) {
    super(iDatabase, iSource);
    Objects.requireNonNull(iSource);
  }

  public RecordBytes(DatabaseSessionInternal session, final RecordId iRecordId) {
    super(session);
    assert assertIfAlreadyLoaded(recordId);

    recordId.setClusterAndPosition(iRecordId.getClusterId(), iRecordId.getClusterPosition());
  }

  @Override
  public RecordBytes fromStream(final byte[] iRecordBuffer) {
    Objects.requireNonNull(iRecordBuffer);
    if (dirty > 0) {
      throw new DatabaseException(getSession().getDatabaseName(),
          "Cannot call fromStream() on dirty records");
    }

    source = iRecordBuffer;
    status = RecordElement.STATUS.LOADED;

    return this;
  }

  @Override
  public @Nonnull byte[] toStream() {
    checkForBinding();
    return source;
  }

  public byte getRecordType() {
    return RECORD_TYPE;
  }

  /**
   * Reads the input stream in memory. This is less efficient than
   * {@link Blob#fromInputStream(InputStream, int)} because allocation is made multiple times. If
   * you already know the input size use {@link Blob#fromInputStream(InputStream, int)}.
   *
   * @param in Input Stream, use buffered input stream wrapper to speed up reading
   * @return Buffer read from the stream. It's also the internal buffer size in bytes
   */
  public int fromInputStream(final @Nonnull InputStream in) throws IOException {
    try (var out = new MemoryStream()) {
      final var buffer = new byte[MemoryStream.DEF_SIZE];
      int readBytesCount;
      while (true) {
        readBytesCount = in.read(buffer, 0, buffer.length);
        if (readBytesCount == -1) {
          break;
        }
        out.write(buffer, 0, readBytesCount);
      }
      out.flush();
      source = out.toByteArray();
    }
    size = source.length;
    return size;
  }

  /**
   * Reads the input stream in memory specifying the maximum bytes to read. This is more efficient
   * than {@link Blob#fromInputStream(InputStream)} because allocation is made only once.
   *
   * @param in      Input Stream, use buffered input stream wrapper to speed up reading
   * @param maxSize Maximum size to read
   * @return Buffer count of bytes that are read from the stream. It's also the internal buffer size
   * in bytes
   * @throws IOException if an I/O error occurs.
   */
  public int fromInputStream(final @Nonnull InputStream in, final int maxSize) throws IOException {

    final var buffer = new byte[maxSize];
    var totalBytesCount = 0;
    int readBytesCount;
    while (totalBytesCount < maxSize) {
      readBytesCount = in.read(buffer, totalBytesCount, buffer.length - totalBytesCount);
      if (readBytesCount == -1) {
        break;
      }
      totalBytesCount += readBytesCount;
    }

    if (totalBytesCount == 0) {
      source = EMPTY_SOURCE;
      size = 0;
    } else if (totalBytesCount == maxSize) {
      source = buffer;
      size = maxSize;
    } else {
      source = Arrays.copyOf(buffer, totalBytesCount);
      size = totalBytesCount;
    }

    return size;
  }

  public void toOutputStream(final @Nonnull OutputStream out) throws IOException {
    checkForBinding();

    if (source.length > 0) {
      out.write(source);
    }
  }

  @Override
  public void setOwner(RecordElement owner) {
    throw new UnsupportedOperationException("RecordBytes cannot be owned by another record");
  }

  @Override
  public boolean isBlob() {
    return true;
  }

  @Override
  public boolean isEntity() {
    return false;
  }

  @Override
  public boolean isStatefulEdge() {
    return false;
  }

  @Override
  public boolean isVertex() {
    return false;
  }

  @Nonnull
  @Override
  public Entity asEntity() {
    throw new IllegalStateException("Blob is not an Entity");
  }

  @Nonnull
  @Override
  public Blob asBlob() {
    return this;
  }

  @Nonnull
  @Override
  public StatefulEdge asStatefulEdge() {
    throw new IllegalStateException("Blob is not a StatefulEdge");
  }

  @Nonnull
  @Override
  public Vertex asVertex() {
    throw new IllegalStateException("Blob is not a Vertex");
  }

  @Nullable
  @Override
  public Entity asEntityOrNull() {
    return null;
  }

  @Nullable
  @Override
  public Blob asBlobOrNull() {
    return this;
  }

  @Nullable
  @Override
  public StatefulEdge asStatefulEdgeOrNull() {
    return null;
  }

  @Nullable
  @Override
  public Vertex asVertexOrNull() {
    return null;
  }
}
