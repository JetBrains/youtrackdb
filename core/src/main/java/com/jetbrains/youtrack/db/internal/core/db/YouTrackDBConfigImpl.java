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
package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.api.DatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.session.SessionListener;
import com.jetbrains.youtrack.db.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrack.db.internal.core.security.DefaultSecurityConfig;
import com.jetbrains.youtrack.db.internal.core.security.GlobalUser;
import com.jetbrains.youtrack.db.internal.core.security.SecurityConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public class YouTrackDBConfigImpl implements YouTrackDBConfig {

  private ContextConfiguration configuration;
  private Map<ATTRIBUTES, Object> attributes;
  private Set<SessionListener> listeners;
  private ClassLoader classLoader;
  private final SecurityConfig securityConfig;
  private final List<GlobalUser> users;

  public YouTrackDBConfigImpl() {
    configuration = new ContextConfiguration();
    attributes = new HashMap<>();
    listeners = new HashSet<>();
    classLoader = this.getClass().getClassLoader();
    this.securityConfig = new DefaultSecurityConfig();
    this.users = new ArrayList<>();
  }

  protected YouTrackDBConfigImpl(
      ContextConfiguration configuration,
      Map<ATTRIBUTES, Object> attributes,
      Set<SessionListener> listeners,
      ClassLoader classLoader,
      SecurityConfig securityConfig,
      List<GlobalUser> users) {
    this.configuration = configuration;
    this.attributes = attributes;
    if (listeners != null) {
      this.listeners = listeners;
    } else {
      this.listeners = Collections.emptySet();
    }
    if (classLoader != null) {
      this.classLoader = classLoader;
    } else {
      this.classLoader = this.getClass().getClassLoader();
    }
    this.securityConfig = securityConfig;
    this.users = users;
  }

  @Override
  public Set<SessionListener> getListeners() {
    return listeners;
  }

  public ContextConfiguration getConfiguration() {
    return configuration;
  }

  @Override
  public Map<ATTRIBUTES, Object> getAttributes() {
    return attributes;
  }

  public ClassLoader getClassLoader() {
    return classLoader;
  }

  public SecurityConfig getSecurityConfig() {
    return securityConfig;
  }

  public List<GlobalUser> getUsers() {
    return users;
  }

  public void setParent(YouTrackDBConfigImpl parent) {
    if (parent != null) {
      if (parent.attributes != null) {
        Map<ATTRIBUTES, Object> attrs = new HashMap<>();
        attrs.putAll(parent.attributes);
        if (attributes != null) {
          attrs.putAll(attributes);
        }
        this.attributes = attrs;
      }

      if (parent.configuration != null) {
        var confis = new ContextConfiguration();
        confis.merge(parent.configuration);
        if (this.configuration != null) {
          confis.merge(this.configuration);
        }
        this.configuration = confis;
      }

      if (this.classLoader == null) {
        this.classLoader = parent.classLoader;
      }

      if (parent.listeners != null) {
        Set<SessionListener> lis = new HashSet<>();
        lis.addAll(parent.listeners);
        if (this.listeners != null) {
          lis.addAll(this.listeners);
        }
        this.listeners = lis;
      }
    }
  }
}
