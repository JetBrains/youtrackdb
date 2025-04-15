/*
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
 */
package com.jetbrains.youtrack.db.internal.server.plugin;

import com.jetbrains.youtrack.db.internal.tools.config.ServerParameterConfiguration;

/**
 * Interface for monitoring plugin events.
 */
public interface PluginLifecycleListener {

  void onBeforeConfig(final ServerPlugin plugin, final ServerParameterConfiguration[] cfg);

  void onAfterConfig(final ServerPlugin plugin, final ServerParameterConfiguration[] cfg);

  void onBeforeStartup(final ServerPlugin plugin);

  void onAfterStartup(final ServerPlugin plugin);

  void onBeforeShutdown(final ServerPlugin plugin);

  void onAfterShutdown(final ServerPlugin plugin);
}
