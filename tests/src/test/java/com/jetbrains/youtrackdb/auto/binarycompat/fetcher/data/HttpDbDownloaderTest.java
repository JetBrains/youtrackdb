package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.data;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HttpDbDownloaderTest {

  @Test
  public void shouldThrowOnMaliciousTarEntry() throws IOException {
    var tarContent = writeTar("../hello.txt", "Hello TAR!\n".getBytes(StandardCharsets.UTF_8));
    var exception = Assertions.assertThrows(IOException.class, () -> {
      HttpDbDownloader.untar(new ByteArrayInputStream(tarContent));
    });
    assertTrue(exception.getMessage().contains("Tar entry is outside of the target dir"));
  }

  private byte[] writeTar(String name, byte[] data) throws IOException {
    var baos = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(baos);
        var tarOut = new TarArchiveOutputStream(gzip)) {
      tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

      var entry = new TarArchiveEntry(name);
      entry.setSize(data.length);
      entry.setModTime(new Date());
      tarOut.putArchiveEntry(entry);
      tarOut.write(data);
      tarOut.closeArchiveEntry();

      tarOut.finish();
    }
    return baos.toByteArray();
  }
}
