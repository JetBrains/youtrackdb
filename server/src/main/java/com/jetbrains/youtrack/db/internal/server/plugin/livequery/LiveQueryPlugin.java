/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrack.db.internal.server.plugin.livequery;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseLifecycleListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.server.YouTrackDBServer;
import com.jetbrains.youtrack.db.internal.server.plugin.ServerPluginAbstract;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;
import javax.annotation.Nonnull;

/**
 * <p>Not needed anymore, keeping the class for backward compatibilty
 */
@Deprecated
public class LiveQueryPlugin extends ServerPluginAbstract implements DatabaseLifecycleListener {

  private boolean enabled = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public void config(final YouTrackDBServer iServer,
      final ServerParameterConfiguration[] iParams) {
    super.config(iServer, iParams);
    for (var param : iParams) {
      if (param.name.equalsIgnoreCase("enabled")) {
        if (Boolean.parseBoolean(param.value)) {
          enabled = true;
        }
      }
    }
  }

  @Override
  public String getName() {
    return "LiveQueryPlugin";
  }

  @Override
  public PRIORITY getPriority() {
    return PRIORITY.LATE;
  }

  @Override
  public void startup() {
    super.startup();
  }

  @Override
  public void onCreate(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public void onOpen(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public void onClose(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public void onDrop(@Nonnull DatabaseSessionInternal session) {
  }

}
