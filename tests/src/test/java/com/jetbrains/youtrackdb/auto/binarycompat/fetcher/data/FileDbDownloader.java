package com.jetbrains.youtrackdb.auto.binarycompat.fetcher.data;

public class FileDbDownloader implements DbDownloader {

  @Override
  public String prepareDbLocation(String location) {
    // file type db is ok to modify in place
    return location;
  }
}
