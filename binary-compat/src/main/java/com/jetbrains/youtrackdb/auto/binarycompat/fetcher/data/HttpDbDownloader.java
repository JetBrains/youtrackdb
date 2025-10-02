package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.data;

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

    conn.setRequestProperty("Accept-Encoding", "gzip"); // ask server for gzip if available
    conn.setConnectTimeout(10_000);
    conn.setReadTimeout(10_000);

    File outputDir;
    try (var is = new BufferedInputStream(conn.getInputStream())) {
      outputDir = untar(is);
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

  static File untar(InputStream tarStream) throws IOException {
    File outputDir;
    try (var gzipIn = new GZIPInputStream(tarStream);
        var tarIn = new TarArchiveInputStream(gzipIn)) {

      var outputPath = Files.createTempDirectory("ytdb-http-db-downloader");
      var normalizedOutputPath = outputPath.normalize();
      outputDir = outputPath.toFile();
      TarArchiveEntry entry;
      while ((entry = tarIn.getNextTarEntry()) != null) {
        var outFile = new File(outputDir, entry.getName());
        if (!outFile.toPath().normalize().startsWith(normalizedOutputPath)) {
          throw new IOException("Tar entry is outside of the target dir: " + entry.getName());
        }

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
      return outputDir;
    }
  }
}