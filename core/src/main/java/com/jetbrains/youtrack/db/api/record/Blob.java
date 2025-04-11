package com.jetbrains.youtrack.db.api.record;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.Nonnull;

/**
 *
 */
public interface Blob extends DBRecord {
  byte RECORD_TYPE = 'b';

  int fromInputStream(@Nonnull final InputStream in) throws IOException;

  int fromInputStream(@Nonnull final InputStream in, final int maxSize) throws IOException;

  void toOutputStream(@Nonnull final OutputStream out) throws IOException;

  @Nonnull
  byte[] toStream();
}
