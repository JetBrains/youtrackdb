package com.jetbrains.youtrack.db.internal.core.util;

import com.jetbrains.youtrack.db.api.DatabaseType;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import java.io.File;
import java.util.Optional;

/**
 *
 */
public class URLHelper {

  public static DatabaseURLConnection parse(String url) {
    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    url = url.replace('\\', '/');

    var typeIndex = url.indexOf(':');
    if (typeIndex <= 0) {
      throw new ConfigurationException(
          "Error in database URL: the engine was not specified. Syntax is: "
              + YouTrackDBEnginesManager.URL_SYNTAX
              + ". URL was: "
              + url);
    }

    var databaseReference = url.substring(typeIndex + 1);
    var type = url.substring(0, typeIndex);

    if (!"remote".equals(type) && !"disk".equals(type) && !"memory".equals(type)) {
      throw new ConfigurationException(
          "Error on opening database: the engine '"
              + type
              + "' was not found. URL was: "
              + url
              + ". Registered engines are: [\"memory\",\"remote\",\"disk\"]");
    }

    var index = databaseReference.lastIndexOf('/');
    String path;
    String dbName;
    String baseUrl;
    if (index > 0) {
      path = databaseReference.substring(0, index);
      dbName = databaseReference.substring(index + 1);
    } else {
      path = "./";
      dbName = databaseReference;
    }
    if ("disk".equals(type) || "memory".equals(type)) {
      baseUrl = new File(path).getAbsolutePath();
    } else {
      baseUrl = path;
    }
    return new DatabaseURLConnection(url, type, baseUrl, dbName);
  }

  public static DatabaseURLConnection parseNew(String url) {
    if ((url.startsWith("'") && url.endsWith("'"))
        || (url.startsWith("\"") && url.endsWith("\""))) {
      url = url.substring(1, url.length() - 1);
    }

    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    url = url.replace('\\', '/');

    var typeIndex = url.indexOf(':');
    if (typeIndex <= 0) {
      throw new ConfigurationException(
          "Error in database URL: the engine was not specified. Syntax is: "
              + YouTrackDBEnginesManager.URL_SYNTAX
              + ". URL was: "
              + url);
    }

    var databaseReference = url.substring(typeIndex + 1);
    var type = url.substring(0, typeIndex);
    Optional<DatabaseType> dbType = Optional.empty();
    if ("disk".equals(type) || "memory".equals(type)) {
      switch (type) {
        case "disk":
          dbType = Optional.of(DatabaseType.DISK);
          break;
        case "memory":
          dbType = Optional.of(DatabaseType.MEMORY);
          break;
      }
      type = "embedded";
    }

    if (!"embedded".equals(type) && !"remote".equals(type)) {
      throw new ConfigurationException(
          "Error on opening database: the engine '"
              + type
              + "' was not found. URL was: "
              + url
              + ". Registered engines are: [\"embedded\",\"remote\"]");
    }

    String dbName;
    String baseUrl;
    if ("embedded".equals(type)) {
      String path;
      var index = databaseReference.lastIndexOf('/');
      if (index > 0) {
        path = databaseReference.substring(0, index);
        dbName = databaseReference.substring(index + 1);
      } else {
        path = "";
        dbName = databaseReference;
      }
      if (!path.isEmpty()) {
        baseUrl = new File(path).getAbsolutePath();
        dbType = Optional.of(DatabaseType.DISK);
      } else {
        baseUrl = path;
      }
    } else {
      var index = databaseReference.lastIndexOf('/');
      if (index > 0) {
        baseUrl = databaseReference.substring(0, index);
        dbName = databaseReference.substring(index + 1);
      } else {
        baseUrl = databaseReference;
        dbName = "";
      }
    }
    return new DatabaseURLConnection(url, type, baseUrl, dbName, dbType);
  }
}
