package com.jetbrains.youtrackdb.internal.core.db.record.record;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.Nonnull;

/**
 * Represents a binary large object (BLOB) record stored in the database.
 */
public interface Blob extends DBRecord {

  byte RECORD_TYPE = 'b';

  int fromInputStream(@Nonnull final InputStream in) throws IOException;

  int fromInputStream(@Nonnull final InputStream in, final int maxSize) throws IOException;

  void toOutputStream(@Nonnull final OutputStream out) throws IOException;

  @Nonnull
  byte[] toStream();
}
