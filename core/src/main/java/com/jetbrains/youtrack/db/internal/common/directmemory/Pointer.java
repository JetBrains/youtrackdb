package com.jetbrains.youtrack.db.internal.common.directmemory;

import com.kenai.jffi.MemoryIO;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Pointer {

  private final long pointer;
  private final int size;
  private final Intention intention;

  private SoftReference<ByteBuffer> byteBuffer;
  private int hash = 0;

  Pointer(long pointer, int size, Intention intention) {
    this.pointer = pointer;
    this.size = size;
    this.intention = intention;
  }

  public void clear() {
    MemoryIO.getInstance().setMemory(pointer, size, (byte) 0);
  }

  public ByteBuffer getNativeByteBuffer() {
    ByteBuffer buffer;
    if (byteBuffer == null) {
      buffer = createNativeBuffer();
      byteBuffer = new SoftReference<>(buffer);
    } else {
      buffer = byteBuffer.get();
      if (buffer == null) {
        buffer = createNativeBuffer();
        byteBuffer = new SoftReference<>(buffer);
      }
    }

    return buffer;
  }

  long getNativePointer() {
    return pointer;
  }

  int getSize() {
    return size;
  }

  Intention getIntention() {
    return intention;
  }

  private ByteBuffer createNativeBuffer() {
    return MemoryIO.getInstance().newDirectByteBuffer(pointer, size).order(ByteOrder.nativeOrder());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var pointer1 = (Pointer) o;

    if (pointer != pointer1.pointer) {
      return false;
    }
    return size == pointer1.size;
  }

  @Override
  public int hashCode() {
    if (hash != 0) {
      return hash;
    }

    var result = (int) (pointer ^ (pointer >>> 32));
    result = 31 * result + size;

    hash = result;
    return hash;
  }
}
