package com.jetbrains.youtrack.db.auto.binarycompat.fetcher.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

public class CommonDbDownloader {

  private final Map<DbLocationType, DbDownloader> downloaders = Map.of(
      DbLocationType.FILE, new FileDbDownloader(),
      DbLocationType.HTTP, new HttpDbDownloader()
  );

  public String prepareDbLocation(DbMetadata dbMetadata) {
    var downloader = downloaders.get(dbMetadata.location().type());
    if (downloader == null) {
      throw new IllegalArgumentException(
          "Location type: " + dbMetadata.location().type() + " is not supported");
    }
    try {
      return downloader.prepareDbLocation(dbMetadata.location().source());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to prepare db location", e);
    }
  }


  public record DbMetadata(String name, DbLocationInfo location, String user, String password) {

  }

  public record DbLocationInfo(String source, DbLocationType type) {

  }

  public enum DbLocationType {
    FILE,
    HTTP;
  }

}
