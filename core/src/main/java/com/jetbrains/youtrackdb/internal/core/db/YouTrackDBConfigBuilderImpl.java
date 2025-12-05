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

package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.api.SessionListener;
import com.jetbrains.youtrackdb.api.YouTrackDB.DatabaseConfigurationParameters;
import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.config.YouTrackDBConfigBuilder;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.security.GlobalUser;
import com.jetbrains.youtrackdb.internal.core.security.GlobalUserImpl;
import com.jetbrains.youtrackdb.internal.core.security.SecurityConfig;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.apache.commons.configuration2.Configuration;

public class YouTrackDBConfigBuilderImpl implements YouTrackDBConfigBuilder {

  private ContextConfiguration configurations = new ContextConfiguration();
  private final Map<ATTRIBUTES, Object> attributes = new EnumMap<>(ATTRIBUTES.class);
  private final Set<SessionListener> listeners = new HashSet<>();
  private SecurityConfig securityConfig;
  private final List<GlobalUser> users = new ArrayList<>();

  @Nonnull
  @Override
  public YouTrackDBConfigBuilder fromApacheConfiguration(@Nonnull Configuration configuration) {
    var keysIter = configuration.getKeys();

    while (keysIter.hasNext()) {
      var key = keysIter.next();
      var value = configuration.getProperty(key);
      switch (key) {
        case DatabaseConfigurationParameters.CONFIG_DB_LOCALE_COUNTRY -> {
          attributes.put(ATTRIBUTES.LOCALE_COUNTRY, value);
        }
        case DatabaseConfigurationParameters.CONFIG_DB_LOCALE_LANGUAGE -> {
          attributes.put(ATTRIBUTES.LOCALE_LANGUAGE, value);
        }
        case DatabaseConfigurationParameters.CONFIG_DB_TIME_ZONE -> {
          attributes.put(ATTRIBUTES.TIMEZONE, value);
        }
        case DatabaseConfigurationParameters.CONFIG_DB_CHARSET -> {
          attributes.put(ATTRIBUTES.CHARSET, value);
        }
        case DatabaseConfigurationParameters.CONFIG_DB_DATE_TIME_FORMAT -> {
          attributes.put(ATTRIBUTES.DATE_TIME_FORMAT, value);
        }
        case DatabaseConfigurationParameters.CONFIG_DB_DATE_FORMAT -> {
          attributes.put(ATTRIBUTES.DATEFORMAT, value);
        }
        case DatabaseConfigurationParameters.CONFIG_USER_PWD -> {
          //skip password
        }
        default -> {
          configurations.setValue(key, value);
        }
      }
    }

    return this;
  }

  @Nonnull
  @Override
  public YouTrackDBConfigBuilderImpl fromGlobalConfigurationParameters(
      @Nonnull Map<GlobalConfiguration, Object> values) {
    for (var entry : values.entrySet()) {
      addGlobalConfigurationParameter(entry.getKey(), entry.getValue());
    }
    return this;
  }

  @Nonnull
  @Override
  public YouTrackDBConfigBuilderImpl fromMap(@Nonnull Map<String, Object> values) {
    for (var entry : values.entrySet()) {
      configurations.setValue(entry.getKey(), entry.getValue());
    }
    return this;
  }

  @Nonnull
  @Override
  public YouTrackDBConfigBuilderImpl addSessionListener(@Nonnull SessionListener listener) {
    listeners.add(listener);
    return this;
  }

  @Nonnull
  @Override
  public YouTrackDBConfigBuilderImpl addGlobalConfigurationParameter(
      final @Nonnull GlobalConfiguration configuration, final @Nonnull Object value) {
    configurations.setValue(configuration, value);
    return this;
  }

  @Nonnull
  @Override
  public YouTrackDBConfigBuilderImpl addAttribute(final @Nonnull ATTRIBUTES attribute,
      final @Nonnull Object value) {
    attributes.put(attribute, value);
    return this;
  }

  public YouTrackDBConfigBuilderImpl setSecurityConfig(SecurityConfig securityConfig) {
    this.securityConfig = securityConfig;
    return this;
  }

  @Nonnull
  @Override
  public YouTrackDBConfigImpl build() {
    return new YouTrackDBConfigImpl(
        configurations,
        attributes,
        listeners,
        securityConfig,
        users);
  }

  public YouTrackDBConfigBuilderImpl fromContext(final ContextConfiguration contextConfiguration) {
    configurations = contextConfiguration;
    return this;
  }

  public void addGlobalUser(
      final String user, final String password, final String resource) {
    users.add(new GlobalUserImpl(user, password, resource));
  }
}
