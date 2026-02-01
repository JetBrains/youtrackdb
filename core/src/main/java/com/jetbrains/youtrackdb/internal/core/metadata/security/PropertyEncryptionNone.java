package com.jetbrains.youtrackdb.internal.core.metadata.security;

public class PropertyEncryptionNone implements PropertyEncryption {

  private static final PropertyEncryptionNone inst = new PropertyEncryptionNone();

  public static PropertyEncryption instance() {
    return inst;
  }

  @Override
  public boolean isEncrypted(String name) {
    return false;
  }

  @Override
  public byte[] encrypt(String name, byte[] values) {
    return values;
  }

  @Override
  public byte[] decrypt(String name, byte[] values) {
    return values;
  }
}
