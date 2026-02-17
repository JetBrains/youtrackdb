package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import javax.annotation.Nullable;

public interface Change {

  int SIZE = ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE;

  boolean increment(int maxCap);

  boolean decrement();

  int applyTo(Integer value, int maxCap);

  int getValue();

  byte getType();

  int serialize(byte[] stream, int offset);

  int clear();

  @Nullable
  default RID getSecondaryRid() {
    return null;
  }
}
