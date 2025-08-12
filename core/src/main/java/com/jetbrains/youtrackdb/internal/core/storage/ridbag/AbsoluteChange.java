package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;

public class AbsoluteChange implements Change {

  public static final byte TYPE = 1;
  private int value;

  public AbsoluteChange(int value) {
    this.value = value;

    checkPositive();
  }

  @Override
  public int getValue() {
    return value;
  }

  @Override
  public boolean increment(int maxCap) {
    var oldValue = value;
    value = Math.min(maxCap, oldValue + 1);

    return value > oldValue;
  }

  @Override
  public boolean decrement() {
    var result = value > 0;
    value--;

    checkPositive();
    return result;
  }

  @Override
  public int clear() {
    var result = value;
    value = 0;
    return result;
  }

  @Override
  public int applyTo(Integer value, int maxCap) {
    assert this.value <= maxCap;
    return this.value;
  }

  @Override
  public byte getType() {
    return TYPE;
  }

  @Override
  public int serialize(byte[] stream, int offset) {
    ByteSerializer.INSTANCE.serializeLiteral(TYPE, stream, offset);
    IntegerSerializer.serializeLiteral(value, stream, offset + ByteSerializer.BYTE_SIZE);
    return ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE;
  }

  private void checkPositive() {
    if (value < 0) {
      value = 0;
    }
  }
}
