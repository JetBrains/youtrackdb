package com.jetbrains.youtrack.db.internal.core.storage.ridbag;

import com.jetbrains.youtrack.db.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.IntegerSerializer;

public class DiffChange implements Change {

  public static final byte TYPE = 0;
  private int delta;

  public DiffChange(int delta) {
    this.delta = delta;
  }

  @Override
  public void increment(int maxCap) {
    delta = Math.min(delta + 1, maxCap);
  }

  @Override
  public void decrement() {
    delta--;
  }

  @Override
  public int applyTo(Integer value, int maxCap) {
    int result;
    if (value == null) {
      result = delta;
    } else {
      result = Math.min(value + delta, maxCap);
    }

    if (result < 0) {
      result = 0;
    }

    return result;
  }

  @Override
  public byte getType() {
    return TYPE;
  }

  @Override
  public int getValue() {
    return delta;
  }

  @Override
  public boolean isUndefined() {
    return delta < 0;
  }

  @Override
  public void applyDiff(int delta) {
    this.delta += delta;
  }

  @Override
  public int serialize(byte[] stream, int offset) {
    ByteSerializer.INSTANCE.serializeLiteral(TYPE, stream, offset);
    IntegerSerializer.serializeLiteral(delta, stream, offset + ByteSerializer.BYTE_SIZE);
    return ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE;
  }
}
