package com.jetbrains.youtrack.db.internal.core.db;

public class StringCacheKey {

  private final byte[] bytes;
  private final int offset;
  private final int len;
  private int hash;

  public StringCacheKey(byte[] bytes, int offset, int len) {
    this.bytes = bytes;
    this.offset = offset;
    this.len = len;
  }

  public int hashCode() {
    var h = hash;
    if (h == 0 && len > 0) {
      var finalLen = offset + len;
      for (var i = offset; i < finalLen; i++) {
        h = 31 * h + this.bytes[i];
      }
      hash = h;
    }
    return h;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof StringCacheKey sobj) {
      if (sobj.len != this.len) {
        return false;
      }
      var finalLen = this.offset + this.len;
      for (int c1 = this.offset, c2 = sobj.offset; c1 < finalLen; c1++, c2++) {
        if (this.bytes[c1] != sobj.bytes[c2]) {
          return false;
        }
      }
      return true;
    }
    return false;
  }
}
