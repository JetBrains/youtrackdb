package com.jetbrains.youtrack.db.auto.binarycompat.fetcher.data;

import java.io.IOException;

public interface DbDownloader {

  String prepareDbLocation(String location) throws IOException;
}
