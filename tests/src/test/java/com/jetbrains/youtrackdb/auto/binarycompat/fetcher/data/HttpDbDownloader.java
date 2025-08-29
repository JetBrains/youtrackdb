package com.jetbrains.youtrack.db.auto.binarycompat.fetcher.data;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

public class HttpDbDownloader implements DbDownloader {

  @Override
  public String prepareDbLocation(String urlString) throws IOException {
    var url = new URL(urlString);
    var conn = (HttpURLConnection) url.openConnection();

    // Request headers
    conn.setRequestProperty("Accept-Encoding", "gzip"); // ask server for gzip if available
    conn.setConnectTimeout(10_000);
    conn.setReadTimeout(10_000);

    // Wrap input stream with GZIP decompressor
    File outputDir;
    try (InputStream in = new BufferedInputStream(conn.getInputStream());
        var gzipIn = new GZIPInputStream(in);
        var tarIn = new TarArchiveInputStream(gzipIn)) {

      outputDir = Files.createTempDirectory("ytdb-http-db-downloader").toFile();
      TarArchiveEntry entry;
      while ((entry = tarIn.getNextTarEntry()) != null) {
        var outFile = new File(outputDir, entry.getName());

        if (entry.isDirectory()) {
          outFile.mkdirs();
        } else {
          // Ensure parent directories exist
          outFile.getParentFile().mkdirs();
          try (OutputStream out = new FileOutputStream(outFile)) {
            var buffer = new byte[4096];
            int len;
            while ((len = tarIn.read(buffer)) != -1) {
              out.write(buffer, 0, len);
            }
          }
        }
      }
    } finally {
      conn.disconnect();
    }
    var absolutePath = outputDir.getAbsolutePath();
    // if there is only one directory inside outputDir, return that directory
    var files = outputDir.listFiles();
    if (files != null && files.length == 1 && files[0].isDirectory()) {
      absolutePath = files[0].getAbsolutePath();
    }
    return absolutePath;
  }
}