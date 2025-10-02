package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code;

import com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code.JarDownloader.LocationType;
import java.io.File;
import java.util.Map;

public class CommonDownloader {

  private final Map<JarDownloader.LocationType, JarDownloader> downloaders;

  public CommonDownloader(Map<JarDownloader.LocationType, JarDownloader> downloaders) {
    this.downloaders = downloaders;
  }

  public File prepareArtifact(LocationType locationType, String source, String version) {
    var downloader = downloaders.get(locationType);
    if (downloader == null) {
      throw new IllegalArgumentException("Location type " + locationType + " is not supported");
    }
    return downloader.prepareArtifact(source, version);
  }
}
