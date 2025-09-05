package com.jetbrains.youtrackdb.internal.client.remote;

public interface BinaryAsyncRequest<T extends BinaryResponse> extends BinaryRequest<T> {

  void setMode(byte mode);

  byte getMode();
}
