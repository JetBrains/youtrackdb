package com.jetbrains.youtrack.db.internal.core.storage.ridbag;

import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;

public interface Change {

  int SIZE = ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE;

  void increment(int maxCap);

  boolean decrement();

  int applyTo(Integer value, int maxCap);

  int applyTo(int value, int maxCap);

  int getValue();

  byte getType();

  void applyDiff(int delta);

  int serialize(byte[] stream, int offset);

  int clear();
}
