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
package com.jetbrains.youtrackdb.internal.server.config;

import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.ConfigurationException;
import com.jetbrains.youtrackdb.internal.server.YouTrackDBServer;
import com.jetbrains.youtrackdb.internal.server.plugin.gremlin.YTDBSettings;
import com.jetbrains.youtrackdb.internal.server.plugin.gremlin.YTDBSettings.YTDBUser;
import com.jetbrains.youtrackdb.internal.server.plugin.gremlin.YTDBSimpleAuthenticator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

public class ServerConfigurationManager {

  private final ServerConfigurationLoader configurationLoader;
  private YTDBSettings configuration;
  private final YouTrackDBServer server;

  public ServerConfigurationManager(final String filePath, YouTrackDBServer server)
      throws IOException {
    configurationLoader = new ServerConfigurationLoader(filePath);
    configuration = configurationLoader.load();
    configuration.server = server;

    var config = configuration.authentication.config;
    if (config == null) {
      config = new HashMap<>();
      configuration.authentication.config = config;
    }

    config.put(YTDBSimpleAuthenticator.YTDB_SERVER_PARAM, server);
    this.server = server;
  }

  public ServerConfigurationManager(final YTDBSettings ytdbSettings) {
    configurationLoader = null;
    configuration = ytdbSettings;
    server = configuration.server;
  }

  public YTDBSettings getConfiguration() {
    return configuration;
  }

  public void setUser(
      final String serverUserName, final String serverUserPasswd, final String permissions) {
    if (serverUserName == null || serverUserName.isEmpty()) {
      throw new IllegalArgumentException("User name is null or empty");
    }

    // An empty password is permissible as some security implementations do not require it.
    if (serverUserPasswd == null) {
      throw new IllegalArgumentException("User password is null or empty");
    }

    if (permissions == null || permissions.isEmpty()) {
      throw new IllegalArgumentException("User permissions is null or empty");
    }

    var userPositionInArray = -1;

    if (configuration.users == null) {
      configuration.users = new ArrayList<>();
      configuration.users.add(new YTDBUser(serverUserName, serverUserPasswd, permissions));
      return;
    }

    // LOOK FOR EXISTENT USER
    for (var i = 0; i < configuration.users.size(); ++i) {
      final var u = configuration.users.get(i);
      if (u != null && serverUserName.equalsIgnoreCase(u.name)) {
        // FOUND
        userPositionInArray = i;
        break;
      }
    }

    if (userPositionInArray == -1) {
      configuration.users.add(new YTDBUser(serverUserName, serverUserPasswd, permissions));
      return;
    }

    configuration.users.set(userPositionInArray,
        new YTDBUser(serverUserName, serverUserPasswd, permissions));
  }

  @Nullable
  public YTDBUser getUser(final String serverUserName) {
    if (serverUserName == null || serverUserName.isEmpty()) {
      throw new IllegalArgumentException("User name is null or empty");
    }

    checkForAutoReloading();

    if (configuration.users != null) {
      for (var user : configuration.users) {
        if (serverUserName.equalsIgnoreCase(user.name)) {
          // FOUND
          return user;
        }
      }
    }

    return null;
  }

  public boolean existsUser(final String serverUserName) {
    return getUser(serverUserName) != null;
  }

  public void dropUser(final String serverUserName) {
    if (serverUserName == null || serverUserName.isEmpty()) {
      throw new IllegalArgumentException("User name is null or empty");
    }

    checkForAutoReloading();

    // LOOK FOR EXISTENT USER
    for (var i = 0; i < configuration.users.size(); ++i) {
      final var u = configuration.users.get(i);

      if (u != null && serverUserName.equalsIgnoreCase(u.name)) {
        configuration.users.remove(i);
        break;
      }
    }
  }

  public Set<YTDBUser> getUsers() {
    checkForAutoReloading();

    final var result = new HashSet<YTDBUser>();
    if (configuration.users != null) {
      for (var i = 0; i < configuration.users.size(); ++i) {
        if (configuration.users.get(i) != null) {
          result.add(configuration.users.get(i));
        }
      }
    }

    return result;
  }

  private void checkForAutoReloading() {
    if (configurationLoader != null) {
      if (configurationLoader.checkForAutoReloading()) {
        try {
          configuration = configurationLoader.load();
          configuration.server = server;
        } catch (IOException e) {
          throw BaseException.wrapException(
              new ConfigurationException("Cannot load server configuration"), e, (String) null);
        }
      }
    }
  }
}
