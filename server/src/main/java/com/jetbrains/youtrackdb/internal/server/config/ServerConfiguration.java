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

import jakarta.xml.bind.annotation.XmlElementRef;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import java.util.List;
import javax.annotation.Nullable;

@XmlRootElement(name = "youtrackdb-server")
public class ServerConfiguration {

  public static final String FILE_NAME = "server-config.xml";
  // private static final String HEADER = "YouTrackDB Server configuration";
  public static final ServerStorageConfiguration[] EMPTY_CONFIG_ARRAY =
      new ServerStorageConfiguration[0];
  @XmlTransient
  public String location;

  @XmlElementWrapper
  @XmlElementRef(type = ServerHandlerConfiguration.class)
  public List<ServerHandlerConfiguration> handlers;

  @XmlElementWrapper
  @XmlElementRef(type = ServerHookConfiguration.class)
  public List<ServerHookConfiguration> hooks;

  @XmlElementRef(type = ServerNetworkConfiguration.class)
  public ServerNetworkConfiguration network;

  @XmlElementWrapper
  @XmlElementRef(type = ServerStorageConfiguration.class)
  public ServerStorageConfiguration[] storages;

  @XmlElementWrapper(required = false)
  @XmlElementRef(type = ServerUserConfiguration.class)
  public ServerUserConfiguration[] users;

  @XmlElementRef(type = ServerSecurityConfiguration.class)
  public ServerSecurityConfiguration security;

  @XmlElementWrapper
  @XmlElementRef(type = ServerEntryConfiguration.class)
  public ServerEntryConfiguration[] properties;

  public boolean isAfterFirstTime;

  public static final String DEFAULT_CONFIG_FILE = "config/youtrackdb-server-config.xml";

  public static final String PROPERTY_CONFIG_FILE = "youtrackdb.config.file";

  public static final String DEFAULT_ROOT_USER = "root";
  public static final String GUEST_USER = "guest";
  public static final String DEFAULT_GUEST_PASSWORD = "!!!TheGuestPw123";

  /**
   * Empty constructor for JAXB
   */
  public ServerConfiguration() {
  }

  public ServerConfiguration(ServerConfigurationLoaderXml iFactory) {
    location = FILE_NAME;
    network = new ServerNetworkConfiguration(iFactory);
    storages = EMPTY_CONFIG_ARRAY;
    security = new ServerSecurityConfiguration(iFactory);
  }

  @Nullable
  public String getStoragePath(String iURL) {
    if (storages != null) {
      for (var stg : storages) {
        if (stg.name.equals(iURL)) {
          return stg.path;
        }
      }
    }

    return null;
  }

  /**
   * Returns the property value configured, if any.
   *
   * @param iName Property name to find
   */
  public String getProperty(final String iName) {
    return getProperty(iName, null);
  }

  /**
   * Returns the property value configured, if any.
   *
   * @param iName         Property name to find
   * @param iDefaultValue Default value returned if not found
   */
  @Nullable
  public String getProperty(final String iName, final String iDefaultValue) {
    if (properties == null) {
      return null;
    }

    for (var p : properties) {
      if (p.name.equals(iName)) {
        return p.value;
      }
    }

    return null;
  }
}
