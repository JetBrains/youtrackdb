/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.client.db;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.ConfigurationException;
import com.jetbrains.youtrack.db.internal.client.remote.EngineRemote;
import com.jetbrains.youtrack.db.internal.client.remote.ServerAdmin;
import com.jetbrains.youtrack.db.internal.common.parser.SystemVariableResolver;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

@Deprecated
public class DatabaseHelper {

  @Deprecated
  public static void createDatabase(DatabaseSession database, final String url)
      throws IOException {
    createDatabase(database, url, "server", "disk");
  }

  @Deprecated
  public static void createDatabase(DatabaseSession database, final String url, String type)
      throws IOException {
    createDatabase(database, url, "server", type);
  }

  @Deprecated
  public static void openDatabase(DatabaseSession database) {
    ((DatabaseSessionInternal) database).open("admin", "admin");
  }

  @Deprecated
  public static void createDatabase(
      DatabaseSession database, final String url, String directory, String type)
      throws IOException {
    if (url.startsWith(EngineRemote.NAME)) {
      new ServerAdmin(url)
          .connect("root", getServerRootPassword(directory))
          .createDatabase("document", type)
          .close();
    } else {
      ((DatabaseSessionInternal) database).create();
      database.close();
    }
  }

  @Deprecated
  public static void deleteDatabase(final DatabaseSession database, String storageType)
      throws IOException {
    deleteDatabase(database, "server", storageType);
  }

  @Deprecated
  public static void deleteDatabase(
      final DatabaseSession database, final String directory, String storageType)
      throws IOException {
    dropDatabase(database, directory, storageType);
  }

  @Deprecated
  public static void dropDatabase(final DatabaseSession database, String storageType)
      throws IOException {
    dropDatabase(database, "server", storageType);
  }

  @Deprecated
  public static void dropDatabase(
      final DatabaseSession database, final String directory, String storageType)
      throws IOException {
    if (existsDatabase(database, storageType)) {
      if (database.getURL().startsWith("remote:")) {
        database.close();
        var admin =
            new ServerAdmin(database.getURL()).connect("root", getServerRootPassword(directory));
        admin.dropDatabase(storageType);
        admin.close();
      } else {
        if (database.isClosed()) {
          openDatabase(database);
        }
        ((DatabaseSessionInternal) database).drop();
      }
    }
  }

  @Deprecated
  public static boolean existsDatabase(final DatabaseSession database, String storageType)
      throws IOException {
    if (database.getURL().startsWith("remote")) {
      var admin =
          new ServerAdmin(database.getURL()).connect("root", getServerRootPassword());
      var exist = admin.existsDatabase(storageType);
      admin.close();
      return exist;
    }

    return ((DatabaseSessionInternal) database).exists();
  }

  @Deprecated
  public static void freezeDatabase(final DatabaseSession database) throws IOException {
    if (database.getURL().startsWith("remote")) {
      final var serverAdmin = new ServerAdmin(database.getURL());
      serverAdmin.connect("root", getServerRootPassword()).freezeDatabase("disk");
      serverAdmin.close();
    } else {
      database.freeze();
    }
  }

  @Deprecated
  public static void releaseDatabase(final DatabaseSession database) throws IOException {
    if (database.getURL().startsWith("remote")) {
      final var serverAdmin = new ServerAdmin(database.getURL());
      serverAdmin.connect("root", getServerRootPassword()).releaseDatabase("disk");
      serverAdmin.close();
    } else {
      database.release();
    }
  }

  @Deprecated
  public static File getConfigurationFile() {
    return getConfigurationFile(null);
  }

  @Deprecated
  public static String getServerRootPassword() throws IOException {
    return getServerRootPassword("server");
  }

  @Deprecated
  protected static String getServerRootPassword(final String iDirectory) throws IOException {
    var passwd = System.getProperty("YOUTRACKDB_ROOT_PASSWORD");
    if (passwd != null) {
      return passwd;
    }

    final var file = getConfigurationFile(iDirectory);

    final var f = new FileReader(file);
    final var buffer = new char[(int) file.length()];
    f.read(buffer);
    f.close();

    final var fileContent = new String(buffer);
    // TODO search is wrong because if first user is not root tests will fail
    var pos = fileContent.indexOf("password=\"");
    pos += "password=\"".length();
    return fileContent.substring(pos, fileContent.indexOf('"', pos));
  }

  @Deprecated
  protected static File getConfigurationFile(final String iDirectory) {
    // LOAD SERVER CONFIG FILE TO EXTRACT THE ROOT'S PASSWORD
    var sysProperty = System.getProperty("youtrackdb.config.file");
    var file = new File(sysProperty != null ? sysProperty : "");
    if (!file.exists()) {
      sysProperty = System.getenv("CONFIG_FILE");
      file = new File(sysProperty != null ? sysProperty : "");
    }
    if (!file.exists()) {
      file =
          new File(
              "../releases/youtrackdb-"
                  + YouTrackDBConstants.getRawVersion()
                  + "/config/youtrackdb-server-config.xml");
    }
    if (!file.exists()) {
      file =
          new File(
              "../releases/youtrackdb-community-"
                  + YouTrackDBConstants.getRawVersion()
                  + "/config/youtrackdb-server-config.xml");
    }
    if (!file.exists()) {
      file =
          new File(
              "../../releases/youtrackdb-"
                  + YouTrackDBConstants.getRawVersion()
                  + "/config/youtrackdb-server-config.xml");
    }
    if (!file.exists()) {
      file =
          new File(
              "../../releases/youtrackdb-community-"
                  + YouTrackDBConstants.getRawVersion()
                  + "/config/youtrackdb-server-config.xml");
    }
    if (!file.exists() && iDirectory != null) {
      file = new File(iDirectory + "/config/youtrackdb-server-config.xml");
      if (!file.exists()) {
        file = new File("../" + iDirectory + "/config/youtrackdb-server-config.xml");
      }
    }
    if (!file.exists()) {
      file =
          new File(
              SystemVariableResolver.resolveSystemVariables(
                  "${" + YouTrackDBEnginesManager.YOUTRACKDB_HOME
                      + "}/config/youtrackdb-server-config.xml"));
    }
    if (!file.exists()) {
      throw new ConfigurationException(
          "Cannot load file youtrackdb-server-config.xml to execute remote tests. Current directory"
              + " is "
              + new File(".").getAbsolutePath());
    }
    return file;
  }
}
