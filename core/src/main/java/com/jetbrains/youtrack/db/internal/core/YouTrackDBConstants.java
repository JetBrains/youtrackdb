package com.jetbrains.youtrack.db.internal.core;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class YouTrackDBConstants {

  public static final String YOUTRACKDB_URL = "https://github.com/youtrackdb/youtrackdb";

  private static final Properties properties = new Properties();

  static {
    try (final var inputStream =
        YouTrackDBConstants.class.getResourceAsStream(
            "/com/jetbrains/youtrack/db/youtrackdb.properties")) {
      if (inputStream != null) {
        properties.load(inputStream);
      }
    } catch (IOException e) {
      LogManager.instance()
          .error(YouTrackDBConstants.class, "Failed to load YouTrackDB properties", e);
    }
  }

  /**
   * @return Major part of YouTrackDB version
   */
  public static int getVersionMajor() {
    final var versions = properties.getProperty("version").split("\\.");
    if (versions.length == 0) {
      LogManager.instance()
          .error(YouTrackDBConstants.class, "Can not retrieve version information for this build",
              null);
      return -1;
    }

    try {
      return Integer.parseInt(versions[0]);
    } catch (NumberFormatException nfe) {
      LogManager.instance()
          .error(
              YouTrackDBConstants.class,
              "Can not retrieve major version information for this build", nfe);
      return -1;
    }
  }

  /**
   * @return Minor part of YouTrackDB version
   */
  public static int getVersionMinor() {
    final var versions = properties.getProperty("version").split("\\.");
    if (versions.length < 2) {
      LogManager.instance()
          .error(
              YouTrackDBConstants.class,
              "Can not retrieve minor version information for this build", null);
      return -1;
    }

    try {
      return Integer.parseInt(versions[1]);
    } catch (NumberFormatException nfe) {
      LogManager.instance()
          .error(
              YouTrackDBConstants.class,
              "Can not retrieve minor version information for this build", nfe);
      return -1;
    }
  }

  /**
   * @return Hotfix part of YouTrackDB version
   */
  @SuppressWarnings("unused")
  public static int getVersionHotfix() {
    final var versions = properties.getProperty("version").split("\\.");
    if (versions.length < 3) {
      return 0;
    }

    try {
      var hotfix = versions[2];
      var snapshotIndex = hotfix.indexOf("-SNAPSHOT");

      if (snapshotIndex != -1) {
        hotfix = hotfix.substring(0, snapshotIndex);
      }

      return Integer.parseInt(hotfix);
    } catch (NumberFormatException nfe) {
      LogManager.instance()
          .error(
              YouTrackDBConstants.class,
              "Can not retrieve hotfix version information for this build", nfe);
      return -1;
    }
  }

  /**
   * @return Returns only current version without build number and etc.
   */
  public static String getRawVersion() {
    return properties.getProperty("version");
  }

  /**
   * Returns the complete text of the current YouTrackDB version.
   */
  public static String getVersion() {
    return properties.getProperty("version")
        + " (build "
        + properties.getProperty("revision")
        + ", branch "
        + properties.getProperty("branch")
        + ")";
  }

  /**
   * Returns true if current YouTrackDB version is a snapshot.
   */
  public static boolean isSnapshot() {
    return properties.getProperty("version").endsWith("SNAPSHOT");
  }

  /**
   * @return the build number if any.
   */
  public static String getBuildNumber() {
    return properties.getProperty("revision");
  }
}
