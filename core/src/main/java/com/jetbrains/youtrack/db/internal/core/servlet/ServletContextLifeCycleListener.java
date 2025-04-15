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
package com.jetbrains.youtrack.db.internal.core.servlet;

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Listener which is used to automatically start/shutdown YouTrackDB engine inside of web application
 * container.
 */
@SuppressWarnings("unused")
@WebListener
public class ServletContextLifeCycleListener implements ServletContextListener {

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    if (GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.getValueAsBoolean()) {
      LogManager.instance()
          .info(this, "Start web application is detected, YouTrackDB engine is staring up...");
      YouTrackDBEnginesManager.startUp(true);
      LogManager.instance().info(this, "YouTrackDB engine is started");
    }
  }

  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    if (GlobalConfiguration.INIT_IN_SERVLET_CONTEXT_LISTENER.getValueAsBoolean()) {
      final var youTrack = YouTrackDBEnginesManager.instance();
      if (youTrack != null) {
        LogManager.instance()
            .info(
                this,
                "Shutting down of YouTrackDB engine because web application is going to be stopped");
        youTrack.shutdown();
        LogManager.instance().shutdown();
      }
    }
  }
}
