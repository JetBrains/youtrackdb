package com.jetbrains.youtrackdb.internal.core.storage.ridbag;

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Objects;
import javax.annotation.Nonnull;

public class AbsoluteChange {

  public static final int SIZE = ByteSerializer.BYTE_SIZE + IntegerSerializer.INT_SIZE;
  public static final byte TYPE = 1;
  private int value;
  @Nonnull
  private RID secondaryRid;

  public AbsoluteChange(int value, @Nonnull RID secondaryRid) {
    this.value = value;
    this.secondaryRid = Objects.requireNonNull(secondaryRid, "secondaryRid");

    checkPositive();
  }

  public int getValue() {
    return value;
  }

  @Nonnull
  public RID getSecondaryRid() {
    return secondaryRid;
  }

  public void setSecondaryRid(@Nonnull RID secondaryRid) {
    this.secondaryRid = Objects.requireNonNull(secondaryRid, "secondaryRid");
  }

  public boolean increment(int maxCap) {
    var oldValue = value;
    value = Math.min(maxCap, oldValue + 1);

    return value > oldValue;
  }

  public boolean decrement() {
    var result = value > 0;
    value--;

    checkPositive();
    return result;
  }

  public int clear() {
    var result = value;
    value = 0;
    return result;
  }

  public int applyTo(Integer value, int maxCap) {
    assert this.value <= maxCap;
    return this.value;
  }

  public byte getType() {
    return TYPE;
  }

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
