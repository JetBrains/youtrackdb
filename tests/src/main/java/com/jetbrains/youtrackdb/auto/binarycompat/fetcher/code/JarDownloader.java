package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.code;


import java.io.File;

public interface JarDownloader {

  File prepareArtifact(String source, String version);

  enum LocationType {
    MAVEN,
    GIT,
    ;
  }
}
