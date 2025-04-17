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

package com.jetbrains.youtrack.db.internal.server.handler;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseLifecycleListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.tools.config.ServerConfiguration;
import com.jetbrains.youtrack.db.internal.tools.config.ServerHookConfiguration;
import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * User: kasper fock Date: 09/11/12 Time: 22:35 Registers hooks defined the in xml configuration.
 *
 * <p>Hooks can be defined in xml as :
 *
 * <p><hooks> <hook class="HookClass"> <parameters> <parameter name="foo" value="bar" />
 * </parameters> </hook> </hooks> In case any parameters is defined the hook class should have a
 * method with following signature: public void config(YouTrackDBServer oServer,
 * ServerParameterConfiguration[] iParams)
 */
public class ConfigurableHooksManager implements DatabaseLifecycleListener {

  private List<ServerHookConfiguration> configuredHooks;

  public ConfigurableHooksManager(final ServerConfiguration iCfg) {
    configuredHooks = iCfg.hooks;
    if (configuredHooks != null && !configuredHooks.isEmpty()) {
      YouTrackDBEnginesManager.instance().addDbLifecycleListener(this);
    }
  }

  public void addHook(ServerHookConfiguration configuration) {
    if (this.configuredHooks == null) {
      configuredHooks = new ArrayList<>();
      YouTrackDBEnginesManager.instance().addDbLifecycleListener(this);
    }
    configuredHooks.add(configuration);
  }

  @Override
  public PRIORITY getPriority() {
    return PRIORITY.LAST;
  }

  @Override
  public void onCreate(final @Nonnull DatabaseSessionInternal session) {
    onOpen(session);
  }

  @Override
  public void onOpen(@Nonnull DatabaseSessionInternal session) {
    if (!session.isRemote()) {
      var db = session;
      for (var hook : configuredHooks) {
        try {
          var klass = Class.forName(hook.clazz);
          final RecordHook h;
          Constructor constructor = null;
          try {
            constructor = klass.getConstructor(DatabaseSession.class);
          } catch (NoSuchMethodException ex) {
            // Ignore
          }

          if (constructor != null) {
            h = (RecordHook) constructor.newInstance(session);
          } else {
            h = (RecordHook) klass.newInstance();
          }
          if (hook.parameters != null && hook.parameters.length > 0) {
            try {
              final var m =
                  h.getClass().getDeclaredMethod("config", ServerParameterConfiguration[].class);
              m.invoke(h, new Object[]{hook.parameters});
            } catch (Exception e) {
              LogManager.instance()
                  .warn(
                      this,
                      "[configure] Failed to configure hook '%s'. Parameters specified but hook don"
                          + " support parameters. Should have a method config with parameters"
                          + " ServerParameterConfiguration[] ",
                      hook.clazz);
            }
          }
          db.registerHook(h);
        } catch (Exception e) {
          LogManager.instance()
              .error(
                  this,
                  "[configure] Failed to configure hook '%s' due to the an error : ",
                  e,
                  hook.clazz,
                  e.getMessage());
        }
      }
    }
  }

  @Override
  public void onClose(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public void onDrop(@Nonnull DatabaseSessionInternal session) {
  }

  public String getName() {
    return "HookRegisters";
  }
}
