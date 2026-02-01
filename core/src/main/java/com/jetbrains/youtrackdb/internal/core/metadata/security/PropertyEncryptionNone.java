package com.jetbrains.youtrackdb.internal.core.metadata.security;

public class PropertyEncryptionNone implements PropertyEncryption {

  private static final PropertyEncryptionNone inst = new PropertyEncryptionNone();

  public static PropertyEncryption instance() {
    return inst;
  }

  public boolean isEncrypted(String name) {
    return false;
  }

  public byte[] encrypt(String name, byte[] values) {
    return values;
  }

  public byte[] decrypt(String name, byte[] values) {
    return values;
  }
}
