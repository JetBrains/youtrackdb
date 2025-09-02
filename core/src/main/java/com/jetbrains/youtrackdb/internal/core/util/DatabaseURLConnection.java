package com.jetbrains.youtrackdb.internal.core.util;

import com.jetbrains.youtrackdb.api.DatabaseType;
import java.util.Optional;

/**
 *
 */
public class DatabaseURLConnection {

  private final String url;
  private final String type;
  private final String path;
  private final String dbName;
  private final Optional<DatabaseType> dbType;

  public DatabaseURLConnection(String url, String type, String path, String dbName) {
    this(url, type, path, dbName, Optional.empty());
  }

  public DatabaseURLConnection(
      String url, String type, String path, String dbName, Optional<DatabaseType> dbType) {
    this.url = url;
    this.type = type;
    this.path = path;
    this.dbName = dbName;
    this.dbType = dbType;
  }

  public String getDbName() {
    return dbName;
  }

  public String getType() {
    return type;
  }

  public String getPath() {
    return path;
  }

  public String getUrl() {
    return url;
  }

  public Optional<DatabaseType> getDbType() {
    return dbType;
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
