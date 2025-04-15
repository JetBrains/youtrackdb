package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.ShortSerializer;
import java.nio.ByteBuffer;

/**
 * @since 8/17/2015
 */
public final class WALPageChangesPortion implements WALChanges {

  private static final int PAGE_SIZE =
      GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024;

  private static final int CHUNK_SIZE = 32;
  private static final int PORTION_SIZE = 32;
  public static final int PORTION_BYTES = PORTION_SIZE * CHUNK_SIZE;

  private byte[][][] pageChunks;

  private final int pageSize;
  private final int chunksCount;

  public WALPageChangesPortion() {
    this(PAGE_SIZE);
  }

  WALPageChangesPortion(int pageSize) {
    this.pageSize = pageSize;
    this.chunksCount = (pageSize + (PORTION_BYTES - 1)) / PORTION_BYTES;
    if (pageSize % PORTION_BYTES != 0) {
      throw new IllegalArgumentException("Page size should be a multiple of " + PORTION_BYTES);
    }
  }

  @Override
  public void setLongValue(ByteBuffer pointer, long value, int offset) {
    var data = new byte[LongSerializer.LONG_SIZE];
    LongSerializer.INSTANCE.serializeNative(value, data, 0);

    updateData(pointer, offset, data);
  }

  @Override
  public void setIntValue(ByteBuffer pointer, int value, int offset) {
    var data = new byte[IntegerSerializer.INT_SIZE];
    IntegerSerializer.serializeNative(value, data, 0);

    updateData(pointer, offset, data);
  }

  public void setShortValue(ByteBuffer pointer, short value, int offset) {
    var data = new byte[ShortSerializer.SHORT_SIZE];
    ShortSerializer.INSTANCE.serializeNative(value, data, 0);

    updateData(pointer, offset, data);
  }

  @Override
  public void setByteValue(ByteBuffer pointer, byte value, int offset) {
    var data = new byte[]{value};

    updateData(pointer, offset, data);
  }

  @Override
  public void setBinaryValue(ByteBuffer pointer, byte[] value, int offset) {
    updateData(pointer, offset, value);
  }

  @Override
  public void moveData(ByteBuffer pointer, int from, int to, int len) {
    var buff = new byte[len];
    readData(pointer, from, buff);
    updateData(pointer, to, buff);
  }

  @Override
  public long getLongValue(ByteBuffer pointer, int offset) {
    var data = new byte[LongSerializer.LONG_SIZE];

    readData(pointer, offset, data);

    return LongSerializer.INSTANCE.deserializeNative(data, 0);
  }

  @Override
  public int getIntValue(ByteBuffer pointer, int offset) {
    var data = new byte[IntegerSerializer.INT_SIZE];

    readData(pointer, offset, data);

    return IntegerSerializer.deserializeNative(data, 0);
  }

  @Override
  public short getShortValue(ByteBuffer pointer, int offset) {
    var data = new byte[ShortSerializer.SHORT_SIZE];

    readData(pointer, offset, data);

    return ShortSerializer.INSTANCE.deserializeNative(data, 0);
  }

  @Override
  public byte getByteValue(ByteBuffer pointer, int offset) {
    var data = new byte[1];

    readData(pointer, offset, data);

    return data[0];
  }

  @Override
  public byte[] getBinaryValue(ByteBuffer pointer, int offset, int len) {
    var data = new byte[len];
    readData(pointer, offset, data);

    return data;
  }

  @Override
  public void applyChanges(ByteBuffer pointer) {
    if (pageChunks == null) {
      return;
    }
    for (var i = 0; i < pageChunks.length; i++) {
      if (pageChunks[i] != null) {
        for (var j = 0; j < PORTION_SIZE; j++) {
          var chunk = pageChunks[i][j];
          if (chunk != null) {
            pointer.put(i * PORTION_BYTES + j * CHUNK_SIZE, chunk, 0, chunk.length);
          }
        }
      }
    }
  }

  @Override
  public int serializedSize() {
    int offset;

    if (pageChunks == null) {
      offset = ShortSerializer.SHORT_SIZE;
    } else {
      offset = ShortSerializer.SHORT_SIZE;

      for (var pageChunk : pageChunks) {
        if (pageChunk != null) {
          for (var j = 0; j < PORTION_SIZE; j++) {
            if (pageChunk[j] != null) {
              offset += 2 * ByteSerializer.BYTE_SIZE + CHUNK_SIZE;
            }
          }
        }
      }
    }

    return offset;
  }

  @Override
  public int toStream(int offset, byte[] stream) {
    if (pageChunks == null) {
      ShortSerializer.INSTANCE.serializeNative((short) 0, stream, offset);
      return offset + ShortSerializer.SHORT_SIZE;
    }

    var countPos = offset;
    var count = 0;
    offset += ShortSerializer.SHORT_SIZE;

    for (var i = 0; i < pageChunks.length; i++) {
      if (pageChunks[i] != null) {
        for (var j = 0; j < PORTION_SIZE; j++) {
          if (pageChunks[i][j] != null) {
            ByteSerializer.INSTANCE.serializeNative((byte) i, stream, offset);
            offset += ByteSerializer.BYTE_SIZE;
            ByteSerializer.INSTANCE.serializeNative((byte) j, stream, offset);
            offset += ByteSerializer.BYTE_SIZE;

            System.arraycopy(pageChunks[i][j], 0, stream, offset, CHUNK_SIZE);
            offset += CHUNK_SIZE;

            count++;
          }
        }
      }
    }

    ShortSerializer.INSTANCE.serializeNative((short) count, stream, countPos);
    return offset;
  }

  @Override
  public void toStream(ByteBuffer buffer) {
    if (pageChunks == null) {
      buffer.putShort((short) 0);
      return;
    }

    var countPos = buffer.position();
    buffer.position(countPos + ShortSerializer.SHORT_SIZE);
    var count = 0;

    for (var i = 0; i < pageChunks.length; i++) {
      if (pageChunks[i] != null) {
        for (var j = 0; j < PORTION_SIZE; j++) {
          if (pageChunks[i][j] != null) {
            buffer.put((byte) i);
            buffer.put((byte) j);

            buffer.put(pageChunks[i][j]);

            count++;
          }
        }
      }
    }

    buffer.putShort(countPos, (short) count);
  }

  @Override
  public int fromStream(int offset, byte[] stream) {
    int chunkLength = ShortSerializer.INSTANCE.deserializeNative(stream, offset);

    offset += ShortSerializer.SHORT_SIZE;

    for (var c = 0; c < chunkLength; c++) {
      int i = ByteSerializer.INSTANCE.deserializeNative(stream, offset);
      offset += ByteSerializer.BYTE_SIZE;
      int j = ByteSerializer.INSTANCE.deserializeNative(stream, offset);
      offset += ByteSerializer.BYTE_SIZE;

      if (pageChunks == null) {
        pageChunks = new byte[(pageSize + (PORTION_BYTES - 1)) / PORTION_BYTES][][];
      }
      if (pageChunks[i] == null) {
        pageChunks[i] = new byte[PORTION_SIZE][];
      }

      if (pageChunks[i][j] == null) {
        pageChunks[i][j] = new byte[CHUNK_SIZE];
      }

      System.arraycopy(stream, offset, pageChunks[i][j], 0, CHUNK_SIZE);
      offset += CHUNK_SIZE;
    }
    return offset;
  }

  @Override
  public void fromStream(ByteBuffer buffer) {
    int chunkLength = buffer.getShort();

    for (var c = 0; c < chunkLength; c++) {
      int i = buffer.get();
      int j = buffer.get();

      if (pageChunks == null) {
        pageChunks = new byte[(pageSize + (PORTION_BYTES - 1)) / PORTION_BYTES][][];
      }
      if (pageChunks[i] == null) {
        pageChunks[i] = new byte[PORTION_SIZE][];
      }

      if (pageChunks[i][j] == null) {
        pageChunks[i][j] = new byte[CHUNK_SIZE];
      }

      buffer.get(pageChunks[i][j]);
    }
  }

  private void readData(ByteBuffer pointer, int offset, byte[] data) {
    if (pageChunks == null) {
      if (pointer != null) {
        pointer.get(offset, data, 0, data.length);
      }
      return;
    }

    var portionIndex = offset / PORTION_BYTES;
    if (portionIndex == (offset + data.length - 1) / PORTION_BYTES
        && pageChunks[portionIndex] == null) {
      if (pointer != null) {
        pointer.get(offset, data, 0, data.length);
      }
      return;
    }

    var chunkIndex = (offset - portionIndex * PORTION_BYTES) / CHUNK_SIZE;
    var chunkOffset = offset - (portionIndex * PORTION_BYTES + chunkIndex * CHUNK_SIZE);

    var read = 0;

    while (read < data.length) {
      byte[] chunk = null;
      if (pageChunks[portionIndex] != null) {
        chunk = pageChunks[portionIndex][chunkIndex];
      }

      final var rl = Math.min(CHUNK_SIZE - chunkOffset, data.length - read);
      if (chunk == null) {
        if (pointer != null) {
          var position = portionIndex * PORTION_BYTES + (chunkIndex * CHUNK_SIZE) + chunkOffset;
          pointer.get(position, data, read, rl);
        }
      } else {
        try {
          System.arraycopy(chunk, chunkOffset, data, read, rl);
        } catch (Exception e) {
          LogManager.instance()
              .error(
                  this,
                  "System.arraycopy error: chunk.length = "
                      + chunk.length
                      + ", chunkOffset = "
                      + chunkOffset
                      + ", data.length = "
                      + data.length
                      + ", read = "
                      + read
                      + ", rl = "
                      + rl,
                  e);
          throw e;
        }
      }

      read += rl;
      chunkOffset = 0;
      chunkIndex++;
      if (chunkIndex == PORTION_SIZE && read < data.length) {
        portionIndex++;
        chunkIndex = 0;
      }
    }
  }

  private void updateData(ByteBuffer pointer, int offset, byte[] data) {
    if (pageChunks == null) {
      pageChunks = new byte[this.chunksCount][][];
    }

    var portionIndex = offset / PORTION_BYTES;
    assert portionIndex < pageChunks.length;
    assert (pageChunks.length - portionIndex) * PORTION_BYTES >= data.length
        : "wrong portionIndex:" + portionIndex + " data:" + data.length;

    if (pageChunks[portionIndex] == null) {
      pageChunks[portionIndex] = new byte[PORTION_SIZE][];
    }

    var chunkIndex = (offset - portionIndex * PORTION_BYTES) / CHUNK_SIZE;
    var chunkOffset = offset - (portionIndex * PORTION_BYTES + chunkIndex * CHUNK_SIZE);

    var written = 0;

    while (written < data.length) {
      var chunk = pageChunks[portionIndex][chunkIndex];

      if (chunk == null) {
        chunk = new byte[CHUNK_SIZE];

        // pointer can be null for new pages
        if (pointer != null) {
          var position = portionIndex * PORTION_BYTES + chunkIndex * CHUNK_SIZE;
          pointer.get(position, chunk);
        }

        pageChunks[portionIndex][chunkIndex] = chunk;
      }

      final var wl = Math.min(CHUNK_SIZE - chunkOffset, data.length - written);
      System.arraycopy(data, written, chunk, chunkOffset, wl);

      written += wl;
      chunkOffset = 0;
      chunkIndex++;

      if (chunkIndex == PORTION_SIZE && written < data.length) {
        portionIndex++;

        if (pageChunks[portionIndex] == null) {
          pageChunks[portionIndex] = new byte[PORTION_SIZE][];
        }

        chunkIndex = 0;
      }
    }
  }

  @Override
  public boolean hasChanges() {
    return pageChunks != null;
  }
}
