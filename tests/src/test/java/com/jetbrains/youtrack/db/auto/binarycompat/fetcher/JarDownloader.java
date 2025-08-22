package com.jetbrains.youtrack.db.auto.binarycompat.fetcher;


import java.io.File;

public interface JarDownloader {

  File prepareArtifact(String source, String version);

  enum LocationType {
    MAVEN,
    GIT,
    ;
  }
}
