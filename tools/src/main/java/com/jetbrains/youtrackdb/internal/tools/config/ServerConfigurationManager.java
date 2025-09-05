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
package com.jetbrains.youtrackdb.internal.tools.config;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.ConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Server configuration manager. It manages the youtrackdb-server-config.xml file.
 */
public class ServerConfigurationManager {

  private final ServerConfigurationLoaderXml configurationLoader;
  private ServerConfiguration configuration;

  public ServerConfigurationManager(final InputStream iInputStream) throws IOException {
    configurationLoader =
        new ServerConfigurationLoaderXml(ServerConfiguration.class, iInputStream);
    configuration = configurationLoader.load();
  }

  public ServerConfigurationManager(final File iFile) throws IOException {
    configurationLoader = new ServerConfigurationLoaderXml(ServerConfiguration.class, iFile);
    configuration = configurationLoader.load();
  }

  public ServerConfigurationManager(final ServerConfiguration iConfiguration) {
    configurationLoader = null;
    configuration = iConfiguration;
  }

  public ServerConfiguration getConfiguration() {
    return configuration;
  }

  public ServerConfigurationManager setUser(
      final String iServerUserName, final String iServerUserPasswd, final String iPermissions) {
    if (iServerUserName == null || iServerUserName.length() == 0) {
      throw new IllegalArgumentException("User name is null or empty");
    }

    // An empty password is permissible as some security implementations do not require it.
    if (iServerUserPasswd == null) {
      throw new IllegalArgumentException("User password is null or empty");
    }

    if (iPermissions == null || iPermissions.length() == 0) {
      throw new IllegalArgumentException("User permissions is null or empty");
    }

    var userPositionInArray = -1;

    if (configuration.users == null) {
      configuration.users = new ServerUserConfiguration[1];
      userPositionInArray = 0;
    } else {
      // LOOK FOR EXISTENT USER
      for (var i = 0; i < configuration.users.length; ++i) {
        final var u = configuration.users[i];

        if (u != null && iServerUserName.equalsIgnoreCase(u.name)) {
          // FOUND
          userPositionInArray = i;
          break;
        }
      }

      if (userPositionInArray == -1) {
        // NOT FOUND
        userPositionInArray = configuration.users.length;
        configuration.users = Arrays.copyOf(configuration.users, configuration.users.length + 1);
      }
    }

    configuration.users[userPositionInArray] =
        new ServerUserConfiguration(iServerUserName, iServerUserPasswd, iPermissions);

    return this;
  }

  public void saveConfiguration() throws IOException {
    if (configurationLoader == null) {
      return;
    }

    configurationLoader.save(configuration);
  }

  @Nullable
  public ServerUserConfiguration getUser(final String iServerUserName) {
    if (iServerUserName == null || iServerUserName.length() == 0) {
      throw new IllegalArgumentException("User name is null or empty");
    }

    checkForAutoReloading();

    if (configuration.users != null) {
      for (var user : configuration.users) {
        if (iServerUserName.equalsIgnoreCase(user.name)) {
          // FOUND
          return user;
        }
      }
    }

    return null;
  }

  public boolean existsUser(final String iServerUserName) {
    return getUser(iServerUserName) != null;
  }

  public void dropUser(final String iServerUserName) {
    if (iServerUserName == null || iServerUserName.length() == 0) {
      throw new IllegalArgumentException("User name is null or empty");
    }

    checkForAutoReloading();

    // LOOK FOR EXISTENT USER
    for (var i = 0; i < configuration.users.length; ++i) {
      final var u = configuration.users[i];

      if (u != null && iServerUserName.equalsIgnoreCase(u.name)) {
        // FOUND
        final var newArray =
            new ServerUserConfiguration[configuration.users.length - 1];
        // COPY LEFT PART
        System.arraycopy(configuration.users, 0, newArray, 0, i);
        // COPY RIGHT PART
        if (newArray.length - i >= 0) {
          System.arraycopy(configuration.users, i + 1, newArray, i, newArray.length - i);
        }
        configuration.users = newArray;
        break;
      }
    }
  }

  public Set<ServerUserConfiguration> getUsers() {
    checkForAutoReloading();

    final var result = new HashSet<ServerUserConfiguration>();
    if (configuration.users != null) {
      for (var i = 0; i < configuration.users.length; ++i) {
        if (configuration.users[i] != null) {
          result.add(configuration.users[i]);
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
        } catch (IOException e) {
          throw BaseException.wrapException(
              new ConfigurationException("Cannot load server configuration"), e, (String) null);
        }
      }
    }
  }
}
