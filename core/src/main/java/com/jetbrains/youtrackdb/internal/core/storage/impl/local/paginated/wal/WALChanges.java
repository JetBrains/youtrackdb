package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import java.nio.ByteBuffer;

/**
 * Keep partial changes of a page for a transaction and original values of chunks of page which were
 * changed using this container.
 *
 * <p>use get* to access to the original content decorated with the changes. use set* to add a
 * change.
 *
 * <p>
 */
public interface WALChanges {

  byte getByteValue(ByteBuffer buffer, int offset);

  byte[] getBinaryValue(ByteBuffer buffer, int offset, int len);

  short getShortValue(ByteBuffer buffer, int offset);

  int getIntValue(ByteBuffer buffer, int offset);

  long getLongValue(ByteBuffer buffer, int offset);

  void setLongValue(ByteBuffer buffer, long value, int offset);

  void setIntValue(ByteBuffer buffer, int value, int offset);

  void setShortValue(ByteBuffer buffer, short value, int offset);

  void setByteValue(ByteBuffer buffer, byte value, int offset);

  void setBinaryValue(ByteBuffer buffer, byte[] value, int offset);

  void moveData(ByteBuffer buffer, int from, int to, int len);

  boolean hasChanges();

  /**
   * Apply the changes to a page.
   *
   * @param buffer Presents page where apply the changes.
   */
  void applyChanges(ByteBuffer buffer);

  /**
   * Return the size of byte array is needed to serialize all data in it.
   *
   * @return the required size.
   */
  int serializedSize();

  void toStream(ByteBuffer byteBuffer);

  void fromStream(final ByteBuffer buffer);
}
