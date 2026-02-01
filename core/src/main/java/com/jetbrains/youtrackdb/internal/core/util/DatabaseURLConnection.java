package com.jetbrains.youtrackdb.internal.core.util;

import com.jetbrains.youtrackdb.api.DatabaseType;
import java.util.Optional;

/**
 *
 */
public record DatabaseURLConnection(String url, String type, String path, String dbName,
                                    Optional<DatabaseType> dbType) {

  public DatabaseURLConnection(String url, String type, String path, String dbName) {
    this(url, type, path, dbName, Optional.empty());
  }

  @Override
  public String toString() {
    return "DatabaseURLConnection{"
        + "url='"
        + url
        + "', type='"
        + type
        + "', path='"
        + path
        + "', dbName='"
        + dbName
        + "', dbType="
        + dbType
        + '}';
  }
}
